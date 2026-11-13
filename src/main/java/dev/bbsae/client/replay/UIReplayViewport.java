package dev.bbsae.client.replay;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.overlay.BbsPreviewBridge;
import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompFrameServer;
import dev.bbsae.client.render.CompRenderCache;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.client.render.CompViewportRenderer;
import dev.bbsae.client.render.FboPool;
import dev.bbsae.client.render.GlFramebuffer;
import dev.bbsae.client.render.GlTexture;
import dev.bbsae.client.ui.CompPrefs;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.Pair;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The replay editor's preview surface, with two presentation modes and one interaction contract.
 *
 * <h2>Camera view — the finished shot</h2>
 * <p>The default view draws the real composition frame through the active composition camera over
 * the clean BBS world frame, letterboxed to the document's aspect ratio. Text, images, cards,
 * effects, masks, 3D layers, linked replay models and the BBS-preview layer all go through the same
 * {@link CompRenderer} graph the film preview and the export use ({@code renderFrame}), so what the
 * editor shows is a frame, not a screen recording of the game. The composition frame is cached per
 * composition frame/document/raster; a parked playhead costs one textured quad per display frame.</p>
 *
 * <h2>Free / orbit views — the same composition in 3D</h2>
 * <p>These views render the composition through {@link CompViewportRenderer}: every 2D layer keeps
 * its AE screen-fixed plane, every 3D layer/model is projected through the free camera with its
 * texture, effects, masks and depth, and the BBS-preview layer is a projected world plane instead
 * of a full-screen background. The content is the same composition as camera view; only the camera
 * control differs. {@link ReplayCompositionOverlay} adds optional alignment guides on top.</p>
 *
 * <h2>Interaction</h2>
 * <p>BBS' gizmo is a singleton whose visual is drawn in the UI pass and whose handles are picked
 * from a stencil pass; a host only has to supply the viewport projection, the area, a pick buffer
 * and {@code startGizmo}. This class implements {@link GizmoViewport} and forwards everything else
 * to {@link GizmoInteraction} — the same helper the film editor and the model block editor use — so
 * dragging a handle writes through the original {@code UIReplayPropTransform} into the replay's own
 * channels, with the original undo callbacks. The gizmo/pick area is the composition rectangle in
 * camera view and the whole viewport on the stage, so handles land on the actor in both.</p>
 *
 * <p>The projection is adopted from {@link CompCamera} via
 * {@link CompViewportCamera#lookThrough(CompCamera)} (eye, target, FOV, near/far, roll and ortho),
 * which is why the handles cannot drift from the rendered shot.</p>
 */
public final class UIReplayViewport extends UIElement implements GizmoViewport
{
    private static final int NAV_NONE = 0;
    private static final int NAV_ORBIT = 1;
    private static final int NAV_PAN = 2;

    /** Preview view modes, mirroring BBS' original free / orbit-through-camera buttons. */
    private static final int VIEW_FREE = 0;
    private static final int VIEW_ORBIT = 1;
    private static final int VIEW_CAMERA = 2;

    private final UIIcon freeView;
    private final UIIcon orbitView;
    private final UIIcon cameraView;
    private int viewMode = VIEW_CAMERA;
    private float freeYaw;
    private float freePitch;
    private float freeDistance;
    private final Vector3f freeTarget = new Vector3f();
    private boolean cameraAnchorSaved;
    private final Vector3f cameraAnchor = new Vector3f();
    private boolean freePoseInitialized;
    /** True once the first switch to the free view has framed the actors. */
    private boolean freePoseEstablished;
    /** The 3D half of a two-window preview; equals {@link #area} in other modes. */
    private final Area viewArea = new Area();
    /**
     * The composition rectangle inside the camera view. Its aspect is the document's, so the
     * composition is never stretched and the gizmo/pick projection uses the same rectangle the
     * picture was painted into.
     */
    private final Area cameraArea = new Area();
    /** Scratch rectangle for the picture-in-picture / two-window preview. */
    private final Area windowPane = new Area();
    private final Area windowPlate = new Area();
    private int buttonRight = -1;

    /* Cached composition frame for the preview. Rendering the whole graph every display frame made
     * a 1080p composition compete with the game's own world pass; the frame is baked at most once
     * per composition frame (and only when the document or the raster changed). */
    private GlFramebuffer compFrame;
    private long compFrameSignature;
    private int compFrameIndex = -1;
    private int compFrameWidth;
    private int compFrameHeight;
    private boolean compFrameWorldReady;
    /** True when the cached frame must be baked again, but may still be shown until then. */
    private boolean previewDirty;
    private String compositionError;
    private long compositionErrorMs;
    private boolean previewWaitingForWorld;
    /** Readiness snapshot for the cached model frame; see render(). */
    private int lastEntityCount = -1;
    private boolean lastBridgeActive;

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

        String initialView = System.getProperty("bbsae.replayViewMode", "camera").trim().toLowerCase(java.util.Locale.ROOT);

        if ("free".equals(initialView))
        {
            this.viewMode = VIEW_FREE;
        }
        else if ("orbit".equals(initialView))
        {
            this.viewMode = VIEW_ORBIT;
        }

        if (session != null)
        {
            CompViewportCamera camera = session.camera();

            this.freeYaw = camera.yaw;
            this.freePitch = camera.pitch;
            this.freeDistance = camera.distance;
            this.freeTarget.set(camera.target);
        }

        this.noCulling();

        /* The three buttons BBS' film preview exposes as "flight / perspective" are the useful
         * subset here: a free viewport, an orbit view that keeps the selected actor as its pivot
         * (the original orbit behaviour), and a look-through-active-camera view. They are real
         * BBS UIIcon buttons so hover/press/highlight match the rest of the editor. */
        this.freeView = new UIIcon(Icons.PLANE, (b) -> this.setViewMode(VIEW_FREE));
        this.freeView.tooltip(IKey.constant("自由视角（右键旋转 / 中键平移 / 滚轮缩放）"));
        this.freeView.relative(this).x(1F, -66).y(6).w(18).h(18);
        this.freeView.highlight(() -> this.viewMode == VIEW_FREE, Direction.BOTTOM);

        this.orbitView = new UIIcon(Icons.ORBIT, (b) -> this.setViewMode(VIEW_ORBIT));
        this.orbitView.tooltip(IKey.constant("绕对象旋转（自动跟随选中的虚拟回放）"));
        this.orbitView.relative(this).x(1F, -44).y(6).w(18).h(18);
        this.orbitView.highlight(() -> this.viewMode == VIEW_ORBIT, Direction.BOTTOM);

        this.cameraView = new UIIcon(Icons.FRUSTUM, (b) -> this.setViewMode(VIEW_CAMERA));
        this.cameraView.tooltip(IKey.constant("相机视角（活动合成相机的机位）"));
        this.cameraView.relative(this).x(1F, -22).y(6).w(18).h(18);
        this.cameraView.highlight(() -> this.viewMode == VIEW_CAMERA, Direction.BOTTOM);

        this.add(this.freeView, this.orbitView, this.cameraView);
    }

    private void setViewMode(int mode)
    {
        if (mode == this.viewMode)
        {
            return;
        }

        if (this.viewMode == VIEW_CAMERA && this.cameraAnchorSaved && this.session != null)
        {
            this.session.space().anchor.set(this.cameraAnchor);
            this.cameraAnchorSaved = false;
        }

        if (mode == VIEW_CAMERA && this.session != null)
        {
            this.cameraAnchor.set(this.session.space().anchor);
            this.cameraAnchorSaved = true;
        }

        if (this.viewMode == VIEW_FREE && this.session != null)
        {
            CompViewportCamera camera = this.session.camera();

            this.freeYaw = camera.yaw;
            this.freePitch = camera.pitch;
            this.freeDistance = camera.distance;
            this.freeTarget.set(camera.target);
        }

        this.viewMode = mode;
        this.updateCameraArea();
        this.setStatus(mode == VIEW_FREE ? "自由视角" : (mode == VIEW_ORBIT ? "绕对象旋转" : "相机视角"));

        if (mode == VIEW_FREE && this.session != null)
        {
            CompViewportCamera camera = this.session.camera();

            camera.yaw = this.freeYaw;
            camera.pitch = this.freePitch;
            camera.distance = this.freeDistance;
            camera.target.set(this.freeTarget);
            /* Leaving the shot camera must also level the horizon. */
            camera.roll = 0F;
            camera.ortho = false;
            camera.update();

            /* The first free view should frame the film's actors, not inherit the default orbit
             * distance. Later switches restore the free pose the user was just editing in. */
            if (!this.freePoseEstablished)
            {
                this.freePoseEstablished = true;

                dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
                CompDocument doc = panel == null ? null : panel.getDocument();

                if (doc != null && doc.width > 0F && doc.height > 0F)
                {
                    /* The free view is the composition workspace, so frame the composition
                     * plane instead of the actor bounds. Framing the actors at the world origin
                     * put a 320x180 composition entirely off to the right of the camera. */
                    this.session.frameComposition(doc.width, doc.height);
                }
                else
                {
                    this.session.frameAll();
                }
            }
        }

        if (mode == VIEW_ORBIT)
        {
            if (this.session != null)
            {
                this.session.camera().roll = 0F;
                this.session.camera().ortho = false;
            }

            this.followSelectedActor();
        }

        if (mode == VIEW_CAMERA)
        {
            /* Ask for the clean world frame on the same click that switches view: otherwise the
             * first camera frame has to wait for the viewport render to re-enable the capture. */
            BbsPreviewBridge.setReplayWorldPreview(true);
            this.lookThroughCompositionCamera();
        }
    }

    /** Orbit mode: the pivot follows the selected virtual actor. */
    private void followSelectedActor()
    {
        if (this.session == null)
        {
            return;
        }

        Replay replay = this.session.replay();
        IEntity entity = this.session.entity(replay);

        if (entity == null)
        {
            /* No actor to orbit: keep the composition itself in view instead of parking the
             * camera at the world origin, which put every 3D comp layer off to one side. */
            dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
            CompDocument doc = panel == null ? null : panel.getDocument();

            if (doc != null && doc.width > 0F && doc.height > 0F && !this.freePoseEstablished)
            {
                this.freePoseEstablished = true;
                this.session.frameComposition(doc.width, doc.height);
                this.syncFreePoseFromCamera();
            }

            return;
        }

        Vector3f target = this.session.space().toComp(entity.getX(), entity.getY(), entity.getZ());
        CompViewportCamera camera = this.session.camera();

        camera.target.set(target);
        camera.update();
    }

    /**
     * Camera mode: look through the active composition camera, exactly.
     *
     * <p>The old implementation rebuilt the pose by hand (eye/target, a derived yaw/pitch, forced
     * perspective) and silently dropped the camera's roll and orthographic mode. That is why a
     * rolled or flat composition previewed correctly but its gizmo/pick sat on different pixels.
     * {@link CompCamera#of} is the single camera resolver the renderer uses, and
     * {@link CompViewportCamera#lookThrough(CompCamera)} adopts its eye, target, FOV, near/far,
     * roll and projection kind, so the preview and the interactive gizmo cannot disagree.</p>
     */
    private void lookThroughCompositionCamera()
    {
        if (this.session == null)
        {
            return;
        }

        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompDocument doc = panel == null ? null : panel.getDocument();

        if (doc == null)
        {
            return;
        }

        ReplaySceneSpace sceneSpace = this.session.space();

        if (!this.cameraAnchorSaved)
        {
            this.cameraAnchor.set(sceneSpace.anchor);
            this.cameraAnchorSaved = true;
        }

        float time = this.session.playhead() / ReplayTime.TICKS_PER_SECOND;
        float frame = time * Math.max(1F, doc.fps);

        /* Put the edited actor where the composition puts it before deriving the camera pose: the
         * anchor is part of the world↔composition mapping the gizmo, pick pass and actor pass all
         * read, so it must already be aligned when the frame is drawn. */
        this.alignSpaceToLinkedLayer(doc, time);

        CompCamera composition = CompCamera.of(doc, time, frame);
        CompViewportCamera camera = this.session.camera();

        camera.setViewport(this.cameraArea.w, this.cameraArea.h);
        camera.lookThrough(composition);
        camera.update();
    }

    private CompLayer linkedLayer(CompDocument doc, Replay replay)
    {
        if (doc == null || replay == null)
        {
            return null;
        }

        for (CompLayer layer : doc.layers)
        {
            if (layer != null && replay.getId() != null && replay.getId().equals(layer.replayId))
            {
                return layer;
            }
        }

        return null;
    }

    /**
     * Aligns the replay world with the linked composition layer.
     *
     * <p>The compositor renders the actor at the layer's comp position; the replay editor maps the
     * actor's world anchor through {@link ReplaySceneSpace}. Setting the anchor so that the
     * replay's anchor lands on the layer keeps the gizmo/pick and the composition actor on the
     * same pixels. The orbit target is shifted by the same delta so a later free/orbit switch does
     * not jump.</p>
     */
    private void alignSpaceToLinkedLayer(CompDocument doc, float time)
    {
        CompLayer linked = this.linkedLayer(doc, this.session.replay());

        if (linked == null)
        {
            return;
        }

        float frame = time * Math.max(1F, doc.fps);
        ReplaySceneSpace space = this.session.space();
        float s = space.pixelsPerBlock;
        float zSign = space.flipZ ? -s : s;
        mchorse.bbs_mod.utils.pose.Transform transform = linked.transformAt(frame);
        float layerZ = linked.scalarAt("z", linked.z, frame);
        Vector3f goal = new Vector3f(transform.translate.x, transform.translate.y, layerZ);
        Vector3f old = space.toComp(linked.replayAnchorX, linked.replayAnchorY, linked.replayAnchorZ);
        Vector3f delta = new Vector3f(goal).sub(old);

        space.anchor.set(
            goal.x - s * linked.replayAnchorX,
            goal.y + s * linked.replayAnchorY,
            goal.z - zSign * linked.replayAnchorZ
        );

        if (delta.lengthSquared() > 0.000001F)
        {
            this.session.camera().target.add(delta);
            this.session.camera().update();
        }
    }

    /**
     * Draws the real bbsae composition frame through the film's active camera, over the clean BBS
     * world frame. This is the replay editor's "what will the finished shot look like" view.
     */
    private int renderCameraPreview(UIContext context)
    {
        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompProject project = panel == null ? null : panel.getProject();
        CompDocument doc = panel == null ? null : panel.getDocument();

        if (project == null || doc == null)
        {
            context.batcher.textCard("没有可预览的合成：先在合成器页打开工程并绑定影片",
                this.viewArea.x + 10, this.viewArea.y + 10, 0xFFFFB27A, 0xAA140D06);

            return 0;
        }

        float time = this.session.playhead() / ReplayTime.TICKS_PER_SECOND;

        this.renderCompositionPlate(context, project, doc, this.cameraArea, time, null);

        return this.session.placeableCount();
    }

    /**
     * PiP / two-window composition preview for the free and orbit views.
     *
     * <p>In camera view the main picture already is the composition, so a second copy would only
     * cost GPU time. The setting keeps its documented meaning for the free views, where it is the
     * side-by-side reference.</p>
     */
    private void renderCompositionWindow(UIContext context)
    {
        int mode = this.compPreviewMode();

        if (mode == 0 || this.session == null || this.viewMode == VIEW_CAMERA || !this.compositionPreviewSupported())
        {
            return;
        }

        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompProject project = panel == null ? null : panel.getProject();
        CompDocument doc = panel == null ? null : panel.getDocument();

        if (project == null || doc == null)
        {
            return;
        }

        float time = this.session.playhead() / ReplayTime.TICKS_PER_SECOND;

        if (mode == 2)
        {
            int px = this.viewArea.ex() + 2;
            int py = this.area.y;
            int pw = this.area.ex() - px;
            int ph = this.area.h;

            if (pw > 60 && ph > 60)
            {
                this.windowPane.set(px, py, pw, ph);
                this.fitPlate(doc, this.windowPane, 8, this.windowPlate);
                context.batcher.box(this.windowPane.x - 4, this.windowPane.y - 4,
                    this.windowPane.ex() + 4, this.windowPane.ey() + 4, 0xCC10141B);
                this.renderCompositionPlate(context, project, doc, this.windowPlate, time, "合成预览 · 双窗口");
            }

            return;
        }

        int pw = (int) Math.min(380, Math.max(180, this.viewArea.w * 0.34F));
        int ph = Math.max(96, Math.round(pw * doc.height / (float) Math.max(1, doc.width)));
        int px = this.viewArea.ex() - pw - 12;
        int py = this.viewArea.y + 32;

        /* Keep clear of the view buttons (top-right) and the HUD strip (bottom). */
        if (px <= this.viewArea.x + 8 || py + ph >= this.viewArea.ey() - 56)
        {
            return;
        }

        this.windowPane.set(px, py, pw, ph);
        this.fitPlate(doc, this.windowPane, 0, this.windowPlate);
        context.batcher.box(this.windowPlate.x - 4, this.windowPlate.y - 4,
            this.windowPlate.ex() + 4, this.windowPlate.ey() + 4, 0xCC10141B);
        this.renderCompositionPlate(context, project, doc, this.windowPlate, time, "合成预览");
    }

    /** The dark stage under the free/orbit actor pass. */
    private void renderStage(UIContext context, Area area)
    {
        boolean world = this.drawStageWorld(context, area);

        if (!world)
        {
            context.batcher.gradientVBox(area.x, area.y, area.ex(), area.ey(), 0xFF17202B, 0xFF070A0F);
        }
        else
        {
            /* Dim the reference picture so the actor, gizmo and grid stay readable. */
            context.batcher.gradientVBox(area.x, area.y, area.ex(), area.ey(), 0x33000000, 0x99000000);
        }

        if (CompPrefs.get().guides)
        {
            int step = Math.max(48, Math.min(128, area.w / 12));
            int color = 0x0BFFFFFF;

            for (int x = area.x + step; x < area.ex(); x += step)
            {
                context.batcher.box(x, area.y, x + 1, area.ey(), color);
            }

            for (int y = area.y + step; y < area.ey(); y += step)
            {
                context.batcher.box(area.x, y, area.ex(), y + 1, color);
            }

            int mid = area.my();

            context.batcher.box(area.x, mid, area.ex(), mid + 1, 0x24FFFFFF);
        }
    }

    /**
     * Screen-space BBS reference for the free/orbit stage. It is contained, dimmed and shifted by
     * the orbit camera's yaw/pitch (parallax), so it behaves like a distant backdrop instead of a
     * glued-on screenshot. It never participates in the composition's own background.
     */
    private boolean drawStageWorld(UIContext context, Area area)
    {
        GlTexture texture = BbsPreviewBridge.texture();

        if (texture == null || texture.width <= 0 || texture.height <= 0)
        {
            return false;
        }

        CompViewportCamera camera = this.session == null ? null : this.session.camera();
        float yaw = camera == null ? 0F : camera.yaw;
        float pitch = camera == null ? 20F : camera.pitch;
        float parallaxX = Math.max(-area.w * 0.12F, Math.min(area.w * 0.12F, -yaw / 360F * area.w * 0.35F));
        float parallaxY = Math.max(-area.h * 0.10F, Math.min(area.h * 0.10F, (pitch - 20F) / 90F * area.h * 0.20F));
        float grow = 0.28F;
        ReplayPreviewLayout.Rect plate = ReplayPreviewLayout.contain(texture.width, texture.height,
            new ReplayPreviewLayout.Rect(
                Math.round(area.x - area.w * grow / 2F + parallaxX),
                Math.round(area.y - area.h * grow / 2F + parallaxY),
                Math.round(area.w * (1F + grow)), Math.round(area.h * (1F + grow))));

        context.batcher.box(area.x, area.y, area.ex(), area.ey(), 0xFF05070A);
        context.batcher.clip(area.x, area.y, area.w, area.h, context);

        try
        {
            BbsPreviewBridge.drawFrame(context.batcher, 0xB0FFFFFF, plate.x(), plate.y(), plate.w(), plate.h());
        }
        finally
        {
            context.batcher.unclip(context);
        }

        context.batcher.textCard("BBS 参考画面", area.x + 8, area.y + area.h - 20, 0xCCDCE8F7, 0x66000000);

        return true;
    }

    /**
     * One complete preview plate: the composition's own background (or, only when the project asks
     * for it, the BBS world backdrop), the composition frame, border and optional label.
     *
     * <p>The world frame is <b>not</b> a global background by default. That was the "预览图层充当
     * 整个背景" bug: a BBS-preview layer is a layer like any other and must only cover its own
     * rectangle; the rest of the frame is the composition's background. The compositor's explicit
     * `预览背景 → BBS` setting ({@code previewBackground == 1}) is the only case that puts the world
     * behind the whole composition, matching the compositor preview.</p>
     */
    private boolean renderCompositionPlate(UIContext context, CompProject project, CompDocument doc,
                                           Area rect, float time, String label)
    {
        boolean bbsBackdrop = this.usesBbsBackdrop(project);
        int stage = doc == null ? 0xFF000000 : (bbsBackdrop ? 0xFF000000 : doc.background);

        context.batcher.box(rect.x - 1, rect.y - 1, rect.ex() + 1, rect.ey() + 1, 0xFF000000);
        context.batcher.box(rect.x, rect.y, rect.ex(), rect.ey(), stage);

        boolean world = bbsBackdrop && this.drawWorldBackdrop(context, rect);
        boolean composition = this.drawCompositionFrame(context, project, doc, rect, time, bbsBackdrop);

        if (Boolean.getBoolean("bbsae.debugReplayPreview"))
        {
            dev.bbsae.BbsAeAddon.LOGGER.info("Replay preview plate: doc={} bbsBackdrop={} world={} composition={} compFrame={} rect={}x{}",
                doc == null ? "null" : doc.id, bbsBackdrop, world, composition,
                this.compFrame == null ? "null" : this.compFrame.id, rect.w, rect.h);
        }

        if (!composition && !world)
        {
            String hint = doc == null || doc.layers == null || doc.layers.isEmpty()
                ? "合成没有可渲染的图层"
                : (bbsBackdrop ? "BBS 预览等待世界帧…" : "合成预览等待中…");

            context.batcher.textCard(hint, rect.x + 8, rect.y + 24, 0xFFFFB27A, 0xAA140D06);
        }
        else if (bbsBackdrop && !world && doc != null && BbsPreviewBridge.needsWorldFrame(doc))
        {
            context.batcher.textCard("BBS 世界帧等待中…", rect.x + 8, rect.y + 24, 0xFFFFD27A, 0x99140D06);
        }

        if (label != null && !label.isEmpty())
        {
            context.batcher.textCard(label, rect.x + 6, rect.y + 5, 0xFFE6EDF7, 0x99000000);
        }

        context.batcher.outline(rect.x - 1, rect.y - 1, rect.ex() + 1, rect.ey() + 1, 0xAA4C9AFF);

        return composition;
    }

    /** The project's explicit "BBS world behind the composition" setting. */
    private boolean usesBbsBackdrop(CompProject project)
    {
        return ReplayPreviewLayout.usesBbsBackdrop(project);
    }

    /** True when the current composition actually samples the live world. */
    private boolean previewNeedsWorld(CompProject project, CompDocument doc)
    {
        return doc != null && (BbsPreviewBridge.needsWorldFrame(doc) || this.usesBbsBackdrop(project));
    }

    private boolean cameraPreviewNeedsWorld()
    {
        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();

        return this.previewNeedsWorld(panel == null ? null : panel.getProject(),
            panel == null ? null : panel.getDocument());
    }

    /** Draws the captured BBS world frame, contained (letterboxed) inside the composition rect. */
    private boolean drawWorldBackdrop(UIContext context, Area rect)
    {
        context.batcher.box(rect.x, rect.y, rect.ex(), rect.ey(), 0xFF05070A);

        GlTexture texture = BbsPreviewBridge.texture();

        if (texture == null || texture.width <= 0 || texture.height <= 0)
        {
            return false;
        }

        ReplayPreviewLayout.Rect plate = ReplayPreviewLayout.contain(texture.width, texture.height,
            new ReplayPreviewLayout.Rect(rect.x, rect.y, rect.w, rect.h));

        context.batcher.clip(rect.x, rect.y, rect.w, rect.h, context);

        try
        {
            BbsPreviewBridge.drawFrame(context.batcher, 0xFFFFFFFF, plate.x(), plate.y(), plate.w(), plate.h());
        }
        finally
        {
            context.batcher.unclip(context);
        }

        return true;
    }

    /**
     * Draws the cached composition frame, baking it only when the frame/document/raster changed.
     *
     * <p>A composition that samples the live world (a BBS-preview or entity layer) must not bake
     * before the first world copy exists: the layer would be empty and the cache would keep that
     * empty frame for the rest of the frame. {@link ReplayPreviewLayout#canReuse} encodes that
     * rule; the frame is otherwise reused for every display refresh between two composition
     * frames, which is what keeps playback smooth.</p>
     *
     * @param transparentStage true only for the explicit BBS backdrop mode; otherwise the frame
     *        keeps the composition's own background, so a preview layer cannot become the whole
     *        background of the replay viewport
     */
    private boolean drawCompositionFrame(UIContext context, CompProject project, CompDocument doc,
                                         Area rect, float time, boolean transparentStage)
    {
        if (project == null || doc == null || doc.layers == null || doc.layers.isEmpty()
            || doc.width <= 0 || doc.height <= 0)
        {
            return false;
        }

        int frame = CompFrameServer.frameOf(doc, time);
        float frameTime = CompFrameServer.timeOf(doc, frame);
        float scale = CompRenderer.previewScale(doc, rect.w, rect.h, 0, this.deviceScale());

        /* Playback trades a little preview sharpness for a stable frame rate; a parked playhead is
         * rendered at the panel's full pixel density. */
        if (this.session.isPlaying())
        {
            scale = Math.max(0.2F, Math.round(scale * 0.85F * 16F) / 16F);
        }

        int[] size = CompRenderer.renderSizeFor(doc, scale);
        int width = Math.max(1, size[0]);
        int height = Math.max(1, size[1]);
        long signature = CompRenderCache.signature(project, doc, frameTime, true, true, 0,
            width, height, false) ^ (transparentStage ? 0x9E3779B97F4A7C15L : 0L);
        /* Only a `bbs` / entity layer samples the live world; the stage flag alone does not. */
        boolean liveWorld = BbsPreviewBridge.needsWorldFrame(doc);
        boolean liveDepth = CompRenderer.needsWorldDepth(project, doc);

        if (liveDepth)
        {
            /* Sticky for the next world render, like the compositor's syncRequests path. */
            BbsPreviewBridge.requestDepth(true);
        }

        boolean worldReady = (!liveWorld || BbsPreviewBridge.hasUsableWorld())
            && (!liveDepth || BbsPreviewBridge.depthAvailable());
        /*
         * A world copy taken at a different film time cannot be trusted for a `bbs`/entity layer,
         * but it must never block the whole composition: text, cards and independent replay models
         * do not sample the world. The first real-machine pause audit showed exactly this failure:
         * pausing left the world time one tick behind, `worldMatches` stayed false, no composition
         * frame was baked at all and the replay model disappeared. Bake regardless; a frame with a
         * not-yet-matching world is simply not cacheable and is re-baked as soon as the bridge
         * catches up.
         */
        boolean worldMatches = (!liveWorld
            || (BbsPreviewBridge.hasUsableWorld() && this.worldMatchesPreviewTime(time)))
            && (!liveDepth || BbsPreviewBridge.depthAvailable());

        if (!this.session.isPlaying() && liveWorld && !worldMatches
            && Boolean.getBoolean("bbsae.debugReplayPreview"))
        {
            dev.bbsae.BbsAeAddon.LOGGER.info(
                "Replay preview world mismatch: time={} worldTime={} hasWorld={} depth={} worldReady={}",
                time, BbsPreviewBridge.worldTimeSeconds(), BbsPreviewBridge.hasUsableWorld(),
                BbsPreviewBridge.depthAvailable(), worldReady);
        }

        if (this.previewDirty || !ReplayPreviewLayout.canReuse(this.compFrame != null, this.compFrameIndex,
            this.compFrameSignature, this.compFrameWidth, this.compFrameHeight, frame, signature,
            width, height, liveWorld || liveDepth, this.compFrameWorldReady))
        {
            /* Pose the virtual actors before the composition graph samples them. The graph also
             * poses a linked replay for its own draw, but the world/entity paths and form
             * properties must agree with the frame that is about to be baked. */
            this.session.applyAllPoses();
            context.batcher.flush();

            GlFramebuffer rendered = null;

            /* 3D layers bake their perspective raster from this edge, exactly like the frame
             * server does for export/AE frames. Leaving it at zero made the 3D cards in the
             * preview use a smaller internal target and look soft next to 2D layers. */
            dev.bbsae.client.render.CompRenderContext.setTargetEdge(Math.max(width, height));

            try
            {
                rendered = CompRenderer.renderFrame(project, doc, frame, transparentStage, scale);
            }
            catch (RuntimeException e)
            {
                this.compositionError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                this.compositionErrorMs = System.currentTimeMillis();
                dev.bbsae.BbsAeAddon.LOGGER.warn("Replay preview could not render composition frame {} of '{}'",
                    frame, doc.name, e);
            }
            finally
            {
                dev.bbsae.client.render.CompRenderContext.setTargetEdge(0);
            }

            if (rendered == null)
            {
                this.previewWaitingForWorld = liveWorld && !worldMatches;
                this.previewDirty = false;

                /* Keep the last good frame on screen if the paused bake fails transiently. The old
                 * code released it here, which turned a failed re-bake into a model/comp
                 * disappearing entirely. */
                return this.compFrame != null && this.drawCompFrame(context, rect);
            }

            this.compositionError = null;
            this.previewWaitingForWorld = liveWorld && !worldMatches;
            this.previewDirty = false;

            if (this.compFrame != null && this.compFrame != rendered)
            {
                FboPool.release(this.compFrame);
            }

            this.compFrame = rendered;
            this.compFrameSignature = signature;
            this.compFrameIndex = frame;
            this.compFrameWidth = width;
            this.compFrameHeight = height;
            /* Only a frame with a matching world copy (and depth, when needed) may be reused. */
            this.compFrameWorldReady = worldReady && worldMatches;

            if (Boolean.getBoolean("bbsae.debugReplayPreview"))
            {
                int previousFbo = org.lwjgl.opengl.GL30.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
                byte[] compPixels = rendered.readRawBytes();
                long alpha = 0L;
                long rgb = 0L;

                for (int i = 0; i < compPixels.length; i += 4)
                {
                    alpha += compPixels[i + 3] & 0xFF;
                    rgb += (compPixels[i] & 0xFF) + (compPixels[i + 1] & 0xFF) + (compPixels[i + 2] & 0xFF);
                }

                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFbo);

                dev.bbsae.BbsAeAddon.LOGGER.info(
                    "Replay preview bake: frame={} time={} stageTransparent={} liveWorld={} worldMatches={} fbo={} {}x{} rect={}x{} entities={} alpha={} rgb={}",
                    frame, time, transparentStage, liveWorld, worldMatches, rendered.id,
                    rendered.width, rendered.height, rect.w, rect.h,
                    this.session.entities() == null ? 0 : this.session.entities().size(), alpha, rgb);
            }
            else if (Boolean.getBoolean("bbsae.debugReplayPreviewBake"))
            {
                dev.bbsae.BbsAeAddon.LOGGER.info(
                    "Replay preview bake: frame={} time={} stageTransparent={} liveWorld={} worldMatches={} fbo={} {}x{} rect={}x{} entities={}",
                    frame, time, transparentStage, liveWorld, worldMatches, rendered.id,
                    rendered.width, rendered.height, rect.w, rect.h,
                    this.session.entities() == null ? 0 : this.session.entities().size());
            }
        }

        return this.drawCompFrame(context, rect);
    }

    /** Blits the cached composition frame (straight-alpha) into the plate rectangle. */
    private boolean drawCompFrame(UIContext context, Area rect)
    {
        if (this.compFrame == null)
        {
            return false;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        /* The frame is a straight-alpha export raster (transparent stage) or an opaque stage with
         * the document background; both composite the same way. */
        context.batcher.texturedBox(this.compFrame.color.id, 0xFFFFFFFF,
            rect.x, rect.y, rect.w, rect.h,
            0F, this.compFrame.height, this.compFrame.width, 0F,
            this.compFrame.width, this.compFrame.height);
        context.batcher.flush();

        return true;
    }

    /**
     * True when the captured world frame was rendered at (roughly) this preview time. One tick of
     * tolerance covers the bridge's one-render-frame lag without baking a visibly wrong backdrop.
     */
    private boolean worldMatchesPreviewTime(float time)
    {
        float world = BbsPreviewBridge.worldTimeSeconds();

        return world < 0F || Math.abs(world - time) <= 0.08F;
    }

    private void releaseCompFrame()
    {
        if (this.compFrame != null)
        {
            FboPool.release(this.compFrame);
            this.compFrame = null;
        }

        this.compFrameIndex = -1;
        this.compFrameSignature = 0L;
        this.compFrameWidth = 0;
        this.compFrameHeight = 0;
        this.compFrameWorldReady = false;
        this.previewDirty = false;
    }

    /** Fits {@code bounds} to the document's aspect; result goes into {@code out}. */
    private void fitPlate(CompDocument doc, Area bounds, int padding, Area out)
    {
        ReplayPreviewLayout.Rect rect = ReplayPreviewLayout.fit(doc,
            bounds.x, bounds.y, bounds.w, bounds.h, padding);

        out.set(rect.x(), rect.y(), rect.w(), rect.h());
    }

    /** Updates {@link #cameraArea} for the current view mode and document. */
    private void updateCameraArea()
    {
        if (this.viewMode != VIEW_CAMERA || this.session == null)
        {
            this.cameraArea.copy(this.viewArea);

            return;
        }

        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompDocument doc = panel == null ? null : panel.getDocument();

        this.fitPlate(doc, this.viewArea, 8, this.cameraArea);
    }

    private float deviceScale()
    {
        try
        {
            float scale = BBSModClient.getGUIScale();

            return scale <= 0.01F ? 1F : Math.max(1F, Math.min(4F, scale));
        }
        catch (RuntimeException | LinkageError e)
        {
            return 1F;
        }
    }

    private boolean isCameraPreviewActive()
    {
        return this.viewMode == VIEW_CAMERA && this.session != null && this.compositionPreviewSupported();
    }

    private void applyViewMode()
    {
        if (this.session == null)
        {
            return;
        }

        if (this.viewMode == VIEW_ORBIT)
        {
            this.followSelectedActor();
        }
        else if (this.viewMode == VIEW_CAMERA && this.isCameraPreviewActive())
        {
            this.lookThroughCompositionCamera();
        }
        else if (this.viewMode == VIEW_CAMERA)
        {
            /* Camera view without a composition falls back to the stage; do not keep the shot
             * camera's orthographic/rolled state on a perspective-free stage. */
            CompViewportCamera camera = this.session.camera();

            if (camera.ortho || camera.roll != 0F)
            {
                camera.ortho = false;
                camera.roll = 0F;
                camera.update();
            }
        }
    }

    public void setSession(ReplayEditSession session)
    {
        this.session = session;
        this.pick.clear();
        this.gizmo.stop();
        this.gizmoActive = false;
        this.releaseCompFrame();
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
        this.area.render(context.batcher, 0xFF070A0F);

        if (this.session == null || this.area.w <= 8 || this.area.h <= 8)
        {
            super.render(context);
            return;
        }

        this.updateViewArea();
        this.session.syncSceneScale();

        /*
         * Model/camera data becomes available one or two frames after the page opens: the film
         * panel's controller is created on bind, the bridge attaches on appear, and the runner
         * context is populated by the first world pass. A frame baked before that has no actors,
         * and the old cache key (document + time) would happily reuse it until the playhead moved
         * again — the "model only appears while the camera moves" bug. Invalidate whenever the
         * readiness snapshot changes, so the first complete frame replaces the empty one.
         */
        java.util.Map<String, IEntity> readyEntities = this.session.entities();
        int entityCount = readyEntities == null ? 0 : readyEntities.size();
        boolean bridgeActive = BbsPreviewBridge.isActive();

        if (entityCount != this.lastEntityCount || bridgeActive != this.lastBridgeActive
            || (bridgeActive && BbsPreviewBridge.filmPanel() == null))
        {
            this.lastEntityCount = entityCount;
            this.lastBridgeActive = bridgeActive;
            this.invalidatePreview();
        }

        /* A gizmo drag mutates the replay's channels every frame but the document signature does
         * not change. Without this the paused camera view kept the pre-drag model pose until the
         * next seek/play. */
        if (this.gizmoActive)
        {
            this.invalidatePreview();
        }

        /* Playback is driven from the render pass (not from the once-per-tick panel update): a
         * 60 fps composition must not step at 20 Hz, and the camera/world pass must sample the
         * same instant as the composition frame drawn below. */
        this.session.advancePlayback();

        this.updateCameraArea();

        /* The world copy is a full-framebuffer blit. Only ask for it when a picture in this page
         * actually samples the world: a `bbs`/entity layer, or the explicit BBS backdrop setting.
         * A normal composition is its own opaque stage and must not silently become a world
         * background. */
        boolean needsWorldPicture = false;

        if (this.isCameraPreviewActive() || (this.viewMode != VIEW_CAMERA && this.compPreviewMode() != 0))
        {
            dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
            CompProject project = panel == null ? null : panel.getProject();
            CompDocument doc = panel == null ? null : panel.getDocument();

            needsWorldPicture = this.previewNeedsWorld(project, doc);
        }

        /* The free/orbit stage shows the BBS world as a dimmed reference behind the actor, the
         * same picture the 3D editor had before. It is a stage reference only: the composition
         * plate still uses its own background unless the project explicitly asks for a backdrop. */
        if (!needsWorldPicture && this.viewMode != VIEW_CAMERA && BbsPreviewBridge.film() != null)
        {
            needsWorldPicture = true;
        }

        BbsPreviewBridge.setReplayWorldPreview(needsWorldPicture);

        Area interaction = this.isCameraPreviewActive() ? this.cameraArea : this.viewArea;

        this.session.camera().setViewport(interaction.w, interaction.h);
        this.session.frameIfNeeded(this.viewArea);

        if (this.viewMode == VIEW_FREE && !this.freePoseEstablished)
        {
            dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
            CompDocument doc = panel == null ? null : panel.getDocument();

            if (doc != null && doc.width > 0F && doc.height > 0F)
            {
                this.freePoseEstablished = true;
                this.session.frameComposition(doc.width, doc.height);
                this.syncFreePoseFromCamera();
            }
        }

        if (!this.freePoseInitialized)
        {
            CompViewportCamera free = this.session.camera();

            this.freeYaw = free.yaw;
            this.freePitch = free.pitch;
            this.freeDistance = free.distance;
            this.freeTarget.set(free.target);
            this.freePoseInitialized = true;
        }

        this.applyViewMode();
        this.session.camera().update();

        /* Remember the user's free/orbit pose every frame. Toggling to the camera and back must
         * return to where they were editing, not to the pre-framing default. */
        if (this.viewMode != VIEW_CAMERA && this.session.isFramed())
        {
            this.syncFreePoseFromCamera();
        }

        this.session.updateVisibility();

        int drawn;

        if (this.isCameraPreviewActive())
        {
            drawn = this.renderCameraPreview(context);
        }
        else
        {
            dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
            CompProject project = panel == null ? null : panel.getProject();
            CompDocument doc = panel == null ? null : panel.getDocument();

            if (project != null && doc != null)
            {
                /* Same composition, real 3D scene: 2D layers stay screen-fixed (AE semantics),
                 * 3D layers/models are projected through the orbit camera with their textures,
                 * depth, effects and masks. This is the textured workspace, not a flat card and
                 * not the old wireframe/model-only stage. */
                CompViewportRenderer.draw(context.batcher, project, doc, this.session.camera(),
                    this.session.playhead() / ReplayTime.TICKS_PER_SECOND,
                    this.viewArea.x, this.viewArea.y, this.viewArea.w, this.viewArea.h);
                drawn = doc.layers == null ? 1 : Math.max(1, doc.layers.size());
            }
            else
            {
                this.renderStage(context, this.viewArea);
                drawn = ReplayModelPass.draw(context.batcher, this.session,
                    this.viewArea.x, this.viewArea.y, this.viewArea.w, this.viewArea.h);
            }

            ReplayCompositionOverlay.render(context, this.session, this.viewArea.x, this.viewArea.y,
                this.viewArea.w, this.viewArea.h, true);
        }

        this.renderCompositionWindow(context);

        this.pick.render(context, this.session, interaction);

        if (this.session.canEditPlacement())
        {
            this.gizmo.renderGizmo(context);
            this.gizmo.renderSphereHighlight(context);
            this.gizmo.renderReadout(context);
        }

        this.gizmo.update(context);
        this.pollNavigation(context);
        this.renderHud(context, drawn, this.isCameraPreviewActive());

        context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(),
            this.gizmoActive ? 0xFF66CCFF : 0xFF2A3340);

        if (this.viewArea.w < this.area.w)
        {
            context.batcher.box(this.viewArea.ex(), this.area.y, this.viewArea.ex() + 1, this.area.ey(), 0xAA2A3340);
        }

        super.render(context);
    }

    private int compPreviewMode()
    {
        return CompPrefs.replayCompPreview();
    }

    /** Project/doc dimensions are usable; an empty layer stack is still a valid (world-only) preview. */
    private boolean compositionPreviewSupported()
    {
        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompProject project = panel == null ? null : panel.getProject();
        CompDocument doc = panel == null ? null : panel.getDocument();

        return project != null && doc != null && doc.width > 0 && doc.height > 0;
    }

    /** Two-window mode gives the 3D stage a left half; the camera view owns the full width. */
    private void updateViewArea()
    {
        this.viewArea.copy(this.area);

        if (this.viewMode != VIEW_CAMERA && this.compPreviewMode() == 2
            && this.compositionPreviewSupported() && this.area.w > 240)
        {
            this.viewArea.w = Math.max(160, Math.round(this.area.w * 0.52F));
        }

        /* Keep the BBS-style view buttons on the 3D half's top-right, not floating over the
         * composition panel in two-window mode. */
        int right = this.viewArea.w - 6;

        if (this.freeView != null && right != this.buttonRight)
        {
            this.buttonRight = right;
            this.freeView.x(right - 66);
            this.orbitView.x(right - 44);
            this.cameraView.x(right - 22);
            this.freeView.resize();
            this.orbitView.resize();
            this.cameraView.resize();
        }
    }

    private void renderHud(UIContext context, int drawn, boolean cameraView)
    {
        int x = this.viewArea.x + 8;
        int y = this.viewArea.y + 7;
        int replays = this.session.replayCount();
        int placeable = this.session.placeableCount();

        String view = cameraView
            ? "相机视角 · 最终合成"
            : (this.viewMode == VIEW_FREE ? "自由视角 · 虚拟世界" : "绕对象旋转 · 虚拟世界");

        context.batcher.textCard("bbsae 回放预览 · " + view, x, y);

        String selection = this.session.replay() == null
            ? "未选择回放"
            : this.session.replay().getName() + (this.session.hasModel(this.session.replay()) ? "" : "（无模型）");
        String cull = this.session.isCullActive()
            ? " · 裁剪 " + this.session.visibleCount() + "/" + replays
            : "";

        if (cameraView)
        {
            boolean needsWorld = this.cameraPreviewNeedsWorld();
            String mode = needsWorld
                ? (BbsPreviewBridge.hasUsableWorld() ? "BBS 世界帧已就绪" : "BBS 世界帧等待中")
                : "合成背景";

            context.batcher.textCard("模型 " + drawn + " 个 · " + mode + " · " + selection + cull,
                x, y + 18, 0xFFDDDDDD, 0x99000000);
        }
        else
        {
            context.batcher.textCard("模型 " + drawn + "/" + placeable + " · " + selection + cull,
                x, y + 18, 0xFFDDDDDD, 0x99000000);
        }

        String problem = this.emptyStateText(drawn, replays, placeable, cameraView);

        if (problem != null)
        {
            context.batcher.textCard(problem, x, y + 36, 0xFFFFB27A, 0xAA140D06);
        }

        this.renderCameraHud(context, cameraView);

        if (this.statusTicks > 0 && !this.status.isEmpty())
        {
            context.batcher.textCard(this.status, x, this.viewArea.ey() - 55, 0xFF8FD3FF, 0x99000000);
            this.statusTicks -= 1;
        }

        String hint;

        if (this.gizmoActive)
        {
            hint = "拖动 gizmo 写回关键帧（Ctrl+Z 撤销）";
        }
        else if (cameraView)
        {
            hint = "相机视角由活动合成相机驱动 · 切到自由/绕对象视角可导航 · I 或 K 打帧";
        }
        else
        {
            hint = "右键环绕 · Shift+右键/中键平移 · 滚轮缩放 · 左键选模型/拖 gizmo · I 或 K 打帧";
        }

        context.batcher.textCard(hint, x, this.viewArea.ey() - 17, 0xFF777777, 0x00000000);
    }

    /**
     * One user-readable line explaining why the viewport looks empty. Null when the scene is in a
     * normal state (some models drawn, or a film with no replays is still a normal state that the
     * panel header explains).
     */
    private String emptyStateText(int drawn, int replays, int placeable, boolean cameraView)
    {
        if (ReplayModelPass.hasRecentError() && !cameraView)
        {
            String error = ReplayModelPass.lastError();

            if (error != null && error.length() > 70)
            {
                error = error.substring(0, 70) + "…";
            }

            return "3D 渲染失败：" + error + "（详细堆栈见客户端日志）";
        }

        if (cameraView)
        {
            if (!this.compositionPreviewSupported())
            {
                return "没有可预览的合成：先在合成器页打开工程并绑定影片";
            }

            if (this.compositionError != null && System.currentTimeMillis() - this.compositionErrorMs < 8000L)
            {
                String error = this.compositionError;

                if (error.length() > 70)
                {
                    error = error.substring(0, 70) + "…";
                }

                return "合成预览渲染失败：" + error + "（详细堆栈见客户端日志）";
            }

            if (this.previewWaitingForWorld && this.cameraPreviewNeedsWorld()
                && !BbsPreviewBridge.hasUsableWorld())
            {
                return "BBS 世界帧尚未捕获：打开影片后会在下一次世界渲染完成";
            }

            if (this.previewWaitingForWorld && this.cameraPreviewNeedsWorld())
            {
                return "正在同步 BBS 世界帧与合成时间…";
            }

            return null;
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

    private void renderCameraHud(UIContext context, boolean cameraView)
    {
        CompViewportCamera camera = this.session.camera();
        String line;

        if (cameraView)
        {
            line = String.format(Locale.ROOT,
                "%s FOV %.0f°  滚转 %.0f°  比例 %.0fpx/格",
                camera.ortho ? "正交" : "透视", camera.clampedFov(), camera.roll,
                this.session.space().pixelsPerBlock);
        }
        else
        {
            line = String.format(Locale.ROOT,
                "偏航 %.0f°  俯仰 %.0f°  距离 %.0f  比例 %.0fpx/格",
                camera.yaw, camera.pitch, camera.distance, this.session.space().pixelsPerBlock);
        }

        context.batcher.textCard(line, this.viewArea.x + 8, this.viewArea.ey() - 34, 0xFFAAAAAA, 0x99000000);
    }

    /* ------------------------------------------------------------ input */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (this.session == null || !this.area.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        if (context.mouseButton == 0 && (this.freeView.area.isInside(context)
            || this.orbitView.area.isInside(context) || this.cameraView.area.isInside(context)))
        {
            return super.subMouseClicked(context);
        }

        if (!this.viewArea.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        if (context.mouseButton == 1 || context.mouseButton == 2)
        {
            if (this.isCameraPreviewActive())
            {
                return true;
            }

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
            this.invalidatePreview();
        }

        return super.subMouseReleased(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.session == null || !this.viewArea.isInside(context) || context.mouseWheel == 0D
            || this.isCameraPreviewActive())
        {
            return super.subMouseScrolled(context);
        }

        CompViewportCamera camera = this.session.camera();

        camera.setViewport(this.viewArea.w, this.viewArea.h);
        camera.zoomAt(context.mouseX - this.viewArea.x, context.mouseY - this.viewArea.y, (float) context.mouseWheel);

        return true;
    }

    private void beginNavigation(UIContext context, int mode)
    {
        if (this.session == null || this.isCameraPreviewActive())
        {
            return;
        }

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

        camera.setViewport(this.viewArea.w, this.viewArea.h);

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

        Area area = this.interactionArea();
        CompViewportCamera camera = this.session.camera();

        camera.setViewport(area.w, area.h);
        camera.update();

        return camera.projection;
    }

    @Override
    public Area getGizmoArea()
    {
        return this.interactionArea();
    }

    @Override
    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        if (this.session == null || !this.session.canEditPlacement())
        {
            return false;
        }

        Area area = this.interactionArea();
        CompViewportCamera camera = this.session.camera();

        camera.setViewport(area.w, area.h);
        camera.update();

        GizmoDrag drag = this.session.gizmoDrag(area);

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

    /** The screen rectangle the 3D interaction/gizmo belongs to this frame. */
    private Area interactionArea()
    {
        return this.isCameraPreviewActive() ? this.cameraArea : this.viewArea;
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

    /** Test hook: switch the preview without a mouse click (0 free, 1 orbit, 2 camera). */
    public void setViewModeFromHost(int mode)
    {
        if (mode >= VIEW_FREE && mode <= VIEW_CAMERA)
        {
            this.setViewMode(mode);
        }
    }

    public int viewModeFromHost()
    {
        return this.viewMode;
    }

    /**
     * Saves the current camera as the free/orbit pose. The host calls this after "frame all":
     * without it, toggling camera → free restored the pose from before the framing and the freshly
     * framed actor shrank back to a dot.
     */
    public void syncFreePoseFromCamera()
    {
        if (this.session == null || this.viewMode == VIEW_CAMERA)
        {
            return;
        }

        CompViewportCamera camera = this.session.camera();

        this.freeYaw = camera.yaw;
        this.freePitch = camera.pitch;
        this.freeDistance = camera.distance;
        this.freeTarget.set(camera.target);
        this.freePoseInitialized = true;
    }

    /** Marks the cached composition frame stale; the next render re-bakes it. */
    public void invalidatePreview()
    {
        this.previewDirty = true;
    }

    public void releasePreview()
    {
        this.releaseCompFrame();
    }

    public ReplayEditSession session()
    {
        return this.session;
    }
}
