package dev.bbsae.client.replay;

import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.client.ui.CompPrefs;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.Pair;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The panel's free 3D viewport: draws the film's replay models, navigates like the compositor's 3D
 * space (right drag orbits, shift+right or middle drag pans, wheel zooms about the cursor), and
 * hosts BBS' own transform gizmo.
 *
 * <p>The gizmo host is the interesting part. BBS' gizmo is a singleton whose visual is drawn in
 * the UI pass and whose handles are picked from a stencil pass; a host only has to supply the
 * viewport projection, the area, a pick buffer and {@code startGizmo}. This class therefore
 * implements {@link GizmoViewport} and forwards everything else to {@link GizmoInteraction} — the
 * same helper the film editor and the model block editor use — so dragging a handle writes
 * through the original {@code UIReplayPropTransform} into the replay's own channels, with the
 * original undo callbacks.</p>
 */
public final class UIReplayViewport extends UIElement implements GizmoViewport
{
    private static final int NAV_NONE = 0;
    private static final int NAV_ORBIT = 1;
    private static final int NAV_PAN = 2;

    private ReplayEditSession session;
    private final ReplayPickPass pick = new ReplayPickPass();
    private final GizmoInteraction gizmo = new GizmoInteraction(this);

    private IntConsumer pickHandler;
    private int navMode = NAV_NONE;
    private int navButton;
    private int navStartX;
    private int navStartY;
    private float navStartYaw;
    private float navStartPitch;
    private final Vector3f navStartTarget = new Vector3f();

    private boolean gizmoActive;
    private String status = "";
    private int statusTicks;

    public UIReplayViewport(ReplayEditSession session)
    {
        this.session = session;

        this.noCulling();
    }

    public void setSession(ReplayEditSession session)
    {
        this.session = session;
        this.pick.clear();
        this.gizmo.stop();
        this.gizmoActive = false;
    }

    public void setPickHandler(IntConsumer pickHandler)
    {
        this.pickHandler = pickHandler;
    }

    /** Human-readable state for the viewport HUD (camera, selection, gestures). */
    public void setStatus(String status)
    {
        this.status = status == null ? "" : status;
        this.statusTicks = 120;
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, 0xFF0B0E13);

        if (this.session == null || this.area.w <= 8 || this.area.h <= 8)
        {
            super.render(context);
            return;
        }

        this.session.camera().setViewport(this.area.w, this.area.h);
        this.session.camera().update();
        this.session.frameIfNeeded(this.area);
        this.session.updateVisibility();

        int drawn = ReplayModelPass.draw(context.batcher, this.session, this.area.x, this.area.y, this.area.w, this.area.h);

        ReplayCompositionOverlay.render(context, this.session, this.area.x, this.area.y, this.area.w, this.area.h);
        this.renderCompositionPreview(context);

        this.pick.render(context, this.session, this.area);

        if (this.session.canEditPlacement())
        {
            this.gizmo.renderGizmo(context);
            this.gizmo.renderSphereHighlight(context);
            this.gizmo.renderReadout(context);
        }

        this.gizmo.update(context);
        this.pollNavigation(context);
        this.renderHud(context, drawn);

        context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(),
            this.gizmoActive ? 0xFF66CCFF : 0xFF2A3340);

        super.render(context);
    }

    /**
     * A small, real composition preview inside the replay viewport.
     *
     * <p>The 3D guides tell you where the camera and the other layers are; this thumbnail tells you
     * what the active composition camera actually sees (text, cards, effects and the linked replay
     * actors). It is only drawn while paused: during playback the per-frame composition render
     * would compete with the replay actor pass, and the thumbnail is a composition aid rather than
     * the playback output.</p>
     *
     * <p>Compositions that contain a BBS preview layer are skipped deliberately: that layer samples
     * the live Minecraft world, and the replay editor's promise is that its preview is the bbsae
     * virtual world, not the real one.</p>
     */
    private void renderCompositionPreview(UIContext context)
    {
        if (this.session == null || this.session.isPlaying() || !CompPrefs.get().guides)
        {
            return;
        }

        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompProject project = panel == null ? null : panel.getProject();
        CompDocument doc = panel == null ? null : panel.getDocument();

        if (project == null || doc == null || doc.layers == null || doc.layers.isEmpty() || doc.width <= 0 || doc.height <= 0)
        {
            return;
        }

        for (CompLayer layer : doc.layers)
        {
            if (layer != null && "bbs".equals(layer.type))
            {
                return;
            }
        }

        int pw = (int) Math.min(360, Math.max(160, this.area.w * 0.34F));
        int ph = Math.max(90, Math.round(pw * doc.height / (float) doc.width));
        int px = this.area.ex() - pw - 10;
        int py = this.area.y + 10;

        if (px <= this.area.x + 8 || py + ph >= this.area.ey() - 8)
        {
            return;
        }

        context.batcher.box(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xCC10141B);
        context.batcher.clip(px, py, pw, ph, context);

        var stack = context.batcher.getContext().getMatrices();

        stack.push();
        stack.translate(px, py, 0F);

        try
        {
            CompRenderer.renderCached(project, doc, this.session.playhead() / ReplayTime.TICKS_PER_SECOND,
                1F, pw, ph, 0, true, true, false, context.batcher);
        }
        finally
        {
            stack.pop();
            context.batcher.unclip(context);
        }

        context.batcher.outline(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xAA4C9AFF);
        context.batcher.textCard("合成预览", px + 4, py + 3, 0xFFFFFFFF, 0x99000000);
    }

    private void renderHud(UIContext context, int drawn)
    {
        int x = this.area.x + 8;
        int y = this.area.y + 7;
        int replays = this.session.replayCount();
        int placeable = this.session.placeableCount();

        context.batcher.textCard("bbsae 虚拟回放 · 3D 空间", x, y);

        String selection = this.session.replay() == null
            ? "未选择回放"
            : this.session.replay().getName() + (this.session.hasModel(this.session.replay()) ? "" : "（无模型）");
        String cull = this.session.isCullActive()
            ? " · 裁剪 " + this.session.visibleCount() + "/" + replays
            : "";

        context.batcher.textCard("模型 " + drawn + "/" + placeable + " · " + selection + cull,
            x, y + 18, 0xFFDDDDDD, 0x99000000);

        String problem = this.emptyStateText(drawn, replays, placeable);

        if (problem != null)
        {
            context.batcher.textCard(problem, x, y + 36, 0xFFFFB27A, 0xAA140D06);
        }

        this.renderCameraHud(context);

        if (this.statusTicks > 0 && !this.status.isEmpty())
        {
            context.batcher.textCard(this.status, x, this.area.ey() - 55, 0xFF8FD3FF, 0x99000000);
            this.statusTicks -= 1;
        }

        String hint = this.gizmoActive
            ? "拖动 gizmo 写回关键帧（Ctrl+Z 撤销）"
            : "右键环绕 · Shift+右键/中键平移 · 滚轮缩放 · 左键选模型/拖 gizmo · I 或 K 打帧";

        context.batcher.textCard(hint, x, this.area.ey() - 17, 0xFF777777, 0x00000000);
    }

    /**
     * One user-readable line explaining why the viewport looks empty. Null when the scene is in a
     * normal state (some models drawn, or a film with no replays is still a normal state that the
     * panel header explains).
     */
    private String emptyStateText(int drawn, int replays, int placeable)
    {
        if (ReplayModelPass.hasRecentError())
        {
            String error = ReplayModelPass.lastError();

            if (error != null && error.length() > 70)
            {
                error = error.substring(0, 70) + "…";
            }

            return "3D 渲染失败：" + error + "（详细堆栈见客户端日志）";
        }

        if (replays == 0)
        {
            return "影片里没有回放：点「原版回放编辑」在 BBS 原版编辑器里添加回放";
        }

        if (placeable == 0)
        {
            return "回放还没有模型：选中回放后点「模型库」选择一个 BBS 模型";
        }

        if (this.session.replay() == null)
        {
            return "未选择回放：左键点 3D 模型，或在左侧列表选择一条回放";
        }

        if (!this.session.hasModel(this.session.replay()))
        {
            return "当前回放没有模型：在左侧选一条有模型的回放，或点「模型库」选择一个 BBS 模型";
        }

        if (drawn == 0)
        {
            if (this.session.isCullActive() && this.session.visibleCount() == 0)
            {
                return "模型不在视野内：点「框选全部」或滚轮缩小";
            }

            return "模型未显示：检查回放是否启用、模型是否加载（客户端日志有失败原因）";
        }

        return null;
    }

    private void renderCameraHud(UIContext context)
    {
        CompViewportCamera camera = this.session.camera();
        String line = String.format(Locale.ROOT,
            "偏航 %.0f°  俯仰 %.0f°  距离 %.0f  比例 %.0fpx/格",
            camera.yaw, camera.pitch, camera.distance, this.session.space().pixelsPerBlock);

        context.batcher.textCard(line, this.area.x + 8, this.area.ey() - 34, 0xFFAAAAAA, 0x99000000);
    }

    /* ------------------------------------------------------------ input */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (this.session == null || !this.area.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        if (context.mouseButton == 1 || context.mouseButton == 2)
        {
            this.beginNavigation(context, context.mouseButton == 1 && !Window.isShiftPressed() ? NAV_ORBIT : NAV_PAN);

            return true;
        }

        if (context.mouseButton == 0 && this.session.canEditPlacement() && this.gizmo.mouseClickedHandle(context))
        {
            this.setStatus("gizmo：拖动中，松开提交关键帧");

            return true;
        }

        if (context.mouseButton == 0)
        {
            int picked = this.pick.pickedReplay();

            if (picked >= 0)
            {
                if (this.pickHandler != null)
                {
                    this.pickHandler.accept(picked);
                }

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        if (this.navMode != NAV_NONE)
        {
            this.navMode = NAV_NONE;
        }

        if (context.mouseButton == 0)
        {
            this.gizmo.mouseReleased(context);
            this.gizmo.stop();
            this.gizmoActive = false;
            this.pick.clear();
        }

        return super.subMouseReleased(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.session == null || !this.area.isInside(context) || context.mouseWheel == 0D)
        {
            return super.subMouseScrolled(context);
        }

        CompViewportCamera camera = this.session.camera();

        camera.setViewport(this.area.w, this.area.h);
        camera.zoomAt(context.mouseX - this.area.x, context.mouseY - this.area.y, (float) context.mouseWheel);

        return true;
    }

    private void beginNavigation(UIContext context, int mode)
    {
        CompViewportCamera camera = this.session.camera();

        this.navMode = mode;
        this.navButton = context.mouseButton;
        this.navStartX = context.mouseX;
        this.navStartY = context.mouseY;
        this.navStartYaw = camera.yaw;
        this.navStartPitch = camera.pitch;
        this.navStartTarget.set(camera.target);
    }

    /**
     * Drag polling rather than drag events: BBS' UI event path does not guarantee non-left button
     * moves to a custom element (the compositor's viewport polls for the same reason), and a
     * button released outside the panel must still end the gesture.
     */
    private void pollNavigation(UIContext context)
    {
        if (this.navMode == NAV_NONE)
        {
            return;
        }

        if (!Window.isMouseButtonPressed(this.navButton))
        {
            this.navMode = NAV_NONE;

            return;
        }

        CompViewportCamera camera = this.session.camera();
        float dx = context.mouseX - this.navStartX;
        float dy = context.mouseY - this.navStartY;

        camera.setViewport(this.area.w, this.area.h);

        if (this.navMode == NAV_ORBIT)
        {
            camera.setPose(
                this.navStartYaw - dx * CompViewportCamera.ORBIT_DEGREES_PER_PIXEL,
                this.navStartPitch + dy * CompViewportCamera.ORBIT_DEGREES_PER_PIXEL,
                camera.distance
            );
        }
        else
        {
            camera.target.set(this.navStartTarget);
            camera.pan(dx, dy);
        }

        camera.update();
    }

    /* ------------------------------------------------------------ GizmoViewport */

    @Override
    public StencilFormFramebuffer getGizmoStencil()
    {
        return this.pick.stencil();
    }

    @Override
    public Matrix4f getGizmoProjection()
    {
        if (this.session == null)
        {
            return null;
        }

        CompViewportCamera camera = this.session.camera();

        camera.setViewport(this.area.w, this.area.h);
        camera.update();

        return camera.projection;
    }

    @Override
    public Area getGizmoArea()
    {
        return this.area;
    }

    @Override
    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        if (this.session == null || !this.session.canEditPlacement())
        {
            return false;
        }

        CompViewportCamera camera = this.session.camera();

        camera.setViewport(this.area.w, this.area.h);
        camera.update();

        GizmoDrag drag = this.session.gizmoDrag(this.area);

        if (drag == null)
        {
            return false;
        }

        UIReplayPropTransform transform = this.session.gizmoTransform();
        boolean started = Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, transform, drag);

        if (started)
        {
            this.gizmoActive = true;
            this.setStatus("写回关键帧：" + (this.session.replay() == null ? "" : this.session.replay().getName()));
        }

        return started;
    }

    @Override
    public void pickGizmoForm(UIContext context, Form form, String bone)
    {
        if (form == null || this.session == null)
        {
            return;
        }

        Film film = this.session.film();
        Form root = FormUtils.getRoot(form);
        List<Replay> replays = film.replays.getList();

        for (int i = 0; i < replays.size(); i++)
        {
            Replay replay = replays.get(i);
            IEntity entity = this.session.entity(replay);

            if (entity != null && entity.getForm() != null && FormUtils.getRoot(entity.getForm()) == root)
            {
                if (this.pickHandler != null)
                {
                    this.pickHandler.accept(i);
                }

                return;
            }
        }
    }

    /** The last model click's replay index, for tests and the panel's status line. */
    public int pickedReplay()
    {
        return this.pick.pickedReplay();
    }

    /** True while the gizmo gesture is being dragged; used by the panel to show the right hint. */
    public boolean isGizmoActive()
    {
        return this.gizmoActive;
    }

    /** Test hook: the pair the pick stencil currently reports, or null. */
    public Pair<Form, String> pickedForm()
    {
        return this.pick.stencil().getPicked();
    }

    public ReplayEditSession session()
    {
        return this.session;
    }
}
