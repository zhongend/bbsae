package dev.bbsae.client.replay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.Map;

/**
 * The replay viewport's pick pass: models and the original gizmo handles are drawn as ids into a
 * private framebuffer, and the pixel under the cursor says what was clicked.
 *
 * <p>This follows BBS' own {@code FilmStencilPicker} sequence (§5.5 of the plan): the same
 * {@link StencilFormFramebuffer}, the same {@link StencilMap}, the same
 * {@code Gizmo.STENCIL_MAX} boundary between gizmo handles and everything behind them, and the
 * same {@code FilmEntityRenderer} path so a bone/bone-owner form reports the same ids the film
 * editor would. Replay ids are {@code Gizmo.STENCIL_MAX + 1 + <replay list index>}, so the picked
 * value converts back to the list position with no second lookup table — and stays correct when
 * disabled or culled replays are skipped.</p>
 */
public final class ReplayPickPass
{
    /** Where replay ids begin, past the ids the gizmo's own handles claim. */
    public static final int REPLAY_STENCIL_OFFSET = ReplayPickIds.offsetFor(Gizmo.STENCIL_MAX);

    /** Gizmo handle hover tolerance in UI pixels (BBS exposes a setting for this; the viewport is small). */
    private static final int HANDLE_PICK_RADIUS = 7;

    private final StencilFormFramebuffer stencil = new StencilFormFramebuffer();
    private final StencilMap stencilMap = new StencilMap();
    private final ReplayLogLimiter failures = new ReplayLogLimiter();

    /** The replay list index of the last picked model, or -1. */
    private int pickedReplay = -1;
    private boolean gizmoHandlePicked;
    /** Form part/bone under the cursor on the selected actor, or null when none. */
    private Pair<Form, String> pickedForm;

    public StencilFormFramebuffer stencil()
    {
        return this.stencil;
    }

    public int pickedReplay()
    {
        return this.pickedReplay;
    }

    public boolean gizmoHandlePicked()
    {
        return this.gizmoHandlePicked;
    }

    /**
     * The form part under the cursor on the selected actor, or null.
     *
     * <p>Only the selected actor is rendered with an incrementing stencil map, so this pair can
     * only describe that actor. Its empty bone means "the whole form/part", which is what BBS'
     * body-part list selects.</p>
     */
    public Pair<Form, String> pickedForm()
    {
        return this.pickedForm;
    }

    /**
     * Renders the id pass and (when the cursor is over the viewport) picks under it.
     *
     * <p>Runs in the UI pass, so it owns the matrices, the framebuffer and the readback itself;
     * the cursor coordinates are converted exactly the way the film editor converts them
     * ({@code pickGUI}, which honours the GUI scale).</p>
     *
     * <p>The pass runs even when the cursor is outside the viewport: the original gizmo's visual
     * is drawn from the matrix this pass captures ({@code Gizmo.captureVisual}), and a verification
     * screenshot with the pointer outside the viewport must still show the handles. Picking is
     * skipped in that case, which is also what keeps an out-of-area readback from reporting a
     * clamped pixel.</p>
     */
    public void render(UIContext context, ReplayEditSession session, Area area)
    {
        this.pickedReplay = -1;
        this.gizmoHandlePicked = false;

        if (context == null || session == null || area == null || area.w <= 0 || area.h <= 0)
        {
            return;
        }

        boolean cursorInside = area.isInside(context);

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getFramebuffer() == null)
        {
            return;
        }

        Map<String, IEntity> entities = session.entities();

        if (entities == null || entities.isEmpty())
        {
            return;
        }

        try
        {
            this.ensureStencil(area);
        }
        catch (RuntimeException e)
        {
            /* The pick framebuffer is optional: without it the viewport still renders, only the
             * click-to-select/gizmo pick is unavailable. A missing pack texture must not crash the
             * whole dashboard panel. */
            if (this.failures.allow(System.currentTimeMillis()))
            {
                BbsAeAddon.LOGGER.warn("Replay mode could not create its pick framebuffer{}",
                    this.failures.describeSuppressed(), e);
            }

            return;
        }

        if (this.stencil.getFramebuffer() == null)
        {
            return;
        }

        Camera viewCamera = session.viewCamera();
        net.minecraft.client.render.Camera actorCamera = session.actorCamera();
        float transition = session.transition();

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        MatrixStack modelView = RenderSystem.getModelViewStack();
        boolean queued = FormRenderLast.suspend();

        try
        {
            Gizmo.INSTANCE.setViewportHeight(area.h);

            this.stencilMap.setup();
            this.stencil.apply();

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);

            modelView.push();
            modelView.peek().getPositionMatrix().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(viewCamera.projection, VertexSorter.BY_Z);

            MatrixStack stack = new MatrixStack();
            List<Replay> replays = session.film().replays.getList();
            int selectedIndex = session.replayIndex(session.replay());

            /* Every other actor is one pickable object id. The selected actor is drawn last with
             * an incrementing id map, exactly like BBS' FilmStencilPicker: its individual form
             * parts/bones get their own ids, which is what makes the Alt pick able to select a
             * body part instead of only the whole replay. */
            for (int i = 0; i < replays.size(); i++)
            {
                if (i == selectedIndex)
                {
                    continue;
                }

                this.renderReplay(session, entities, replays, i, actorCamera, stack, transition, false, 0);
            }

            if (selectedIndex >= 0)
            {
                this.renderReplay(session, entities, replays, selectedIndex, actorCamera, stack, transition,
                    true, replays.size() + ReplayPickIds.offsetFor(Gizmo.STENCIL_MAX));
            }

            this.renderGizmoStencil(session, viewCamera, transition);

            /* The readback has to happen while the pick framebuffer is still bound. A pointer
             * outside the viewport means "no hover", not a clamped readback. */
            int picked = 0;

            if (cursorInside)
            {
                this.stencil.pickGUI(context, area, HANDLE_PICK_RADIUS, Gizmo.STENCIL_MAX);
                picked = this.stencil.getIndex();
            }
            else
            {
                this.stencil.clearIndex();
            }

            this.stencil.unbind(this.stencilMap);

            this.gizmoHandlePicked = ReplayPickIds.isGizmoHandle(picked, Gizmo.STENCIL_X, Gizmo.STENCIL_MAX);
            this.pickedReplay = ReplayPickIds.indexFor(picked, Gizmo.STENCIL_MAX, replays.size());
            this.pickedForm = cursorInside && !this.gizmoHandlePicked ? this.stencil.getPicked() : null;
        }
        catch (RuntimeException e)
        {
            if (this.failures.allow(System.currentTimeMillis()))
            {
                BbsAeAddon.LOGGER.warn("Replay mode pick pass failed{}", this.failures.describeSuppressed(), e);
            }
        }
        finally
        {
            FormRenderLast.restore(queued);

            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            modelView.pop();
            RenderSystem.applyModelViewMatrix();

            GL11.glDepthFunc(previousDepthFunc);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            BufferRenderer.resetCurrentVertexBuffer();

            client.getFramebuffer().beginWrite(true);

            if (previousViewport[2] > 0 && previousViewport[3] > 0
                && client.getFramebuffer().fbo == previousFramebuffer)
            {
                GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            }
        }
    }

    /**
     * The original replay-placement gizmo, as ids. The matrix is built exactly as the film
     * editor's world pass builds the captured gizmo matrix — camera view times the actor's
     * camera-relative placement — because that is the matrix {@code Gizmo.renderInterface}
     * expects when it projects the visual back onto the screen.
     */
    /** Draws one replay into the pick target, optionally with per-form-part incrementing ids. */
    private void renderReplay(ReplayEditSession session, Map<String, IEntity> entities, List<Replay> replays,
                              int index, net.minecraft.client.render.Camera actorCamera, MatrixStack stack, float transition,
                              boolean increment, int objectIndex)
    {
        Replay replay = replays.get(index);

        if (replay == null || !Boolean.TRUE.equals(replay.enabled.get()) || !session.isReplayVisible(index))
        {
            return;
        }

        IEntity entity = entities.get(replay.getId());

        if (entity == null || entity.getForm() == null)
        {
            return;
        }

        try
        {
            session.applyPose(entity, replay);

            this.stencilMap.objectIndex = increment ? objectIndex : ReplayPickIds.idFor(index, Gizmo.STENCIL_MAX);
            this.stencilMap.setIncrement(increment);

            FilmEntityRenderer.renderEntity(FilmControllerContext.instance
                .setup(entities, entity, replay, actorCamera, stack, null, transition)
                .stencil(this.stencilMap)
                .relative(Boolean.TRUE.equals(replay.relative.get())));
        }
        catch (RuntimeException e)
        {
            if (this.failures.allow(System.currentTimeMillis()))
            {
                BbsAeAddon.LOGGER.warn("Replay mode could not pick replay {}{}",
                    replay.getId(), this.failures.describeSuppressed(), e);
            }
        }
    }

    private void renderGizmoStencil(ReplayEditSession session, Camera camera, float transition)
    {
        if (!session.canEditPlacement())
        {
            return;
        }

        IEntity entity = session.selectedEntity();

        if (entity == null)
        {
            return;
        }

        MatrixStack stack = new MatrixStack();
        Matrix4f placement = null;
        String bone = session.isBoneGizmo() ? session.selectedBone() : "";

        if (bone != null && !bone.isEmpty() && entity.getForm() != null)
        {
            /* The selected body part is where the original editor puts the gizmo. Drawing the
             * handles at the replay's feet while a bone is selected would make every drag start
             * from the wrong origin. */
            placement = FilmMatrices.getGizmoBoneCompositeMatrix(
                session.entities(), entity, session.replay(),
                camera.position.x, camera.position.y, camera.position.z, transition, bone, true);
        }

        if (placement == null)
        {
            placement = FilmMatrices.getMatrixForRenderWithRotation(
                entity, camera.position.x, camera.position.y, camera.position.z, transition
            );
        }

        stack.peek().getPositionMatrix().set(camera.view);
        MatrixStackUtils.multiply(stack, placement);

        Gizmo.INSTANCE.reorientForSpace(stack, TransformSpace.GLOBAL, camera.view,
            FilmMatrices.getReplayWorldAxes(entity, transition));
        Gizmo.INSTANCE.renderStencil(stack, UIReplayPropTransform.MASK);
    }

    private void ensureStencil(Area area)
    {
        this.stencil.setup(Link.bbs("bbsae_replay_mode_stencil"));

        float scale = BBSModClient.getGUIScale();
        int width = Math.max(1, Math.round(area.w * scale));
        int height = Math.max(1, Math.round(area.h * scale));
        Texture texture = this.stencil.getFramebuffer().getMainTexture();

        if (texture.width != width || texture.height != height)
        {
            this.stencil.resize(width, height);
        }
    }

    /** Forgets the last pick (selection changes make a stale id meaningless). */
    public void clear()
    {
        this.stencil.clearPicking();
        this.pickedReplay = -1;
        this.gizmoHandlePicked = false;
    }

    public void resetLimiter()
    {
        this.failures.reset();
    }
}
