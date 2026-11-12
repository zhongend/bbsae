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
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Map;

/**
 * The replay viewport's pick pass: models and the original gizmo handles are drawn as ids into a
 * private framebuffer, and the pixel under the cursor says what was clicked.
 *
 * <p>This follows BBS' own {@code FilmStencilPicker} sequence (§5.5 of the plan): the same
 * {@link StencilFormFramebuffer}, the same {@link StencilMap}, the same
 * {@code Gizmo.STENCIL_MAX} boundary between gizmo handles and everything behind them, and the
 * same {@code FilmEntityRenderer} path so a bone/bone-owner form reports the same ids the film
 * editor would. Replay ids start right after the gizmo's range, so a click can never be ambiguous
 * about which of the two it hit.</p>
 */
public final class ReplayPickPass
{
    /** Where replay ids begin, past the ids the gizmo's own handles claim. */
    public static final int REPLAY_STENCIL_OFFSET = Gizmo.STENCIL_MAX + 1;

    /** Gizmo handle hover tolerance in UI pixels (BBS exposes a setting for this; the viewport is small). */
    private static final int HANDLE_PICK_RADIUS = 7;

    private final StencilFormFramebuffer stencil = new StencilFormFramebuffer();
    private final StencilMap stencilMap = new StencilMap();

    /** The replay list index of the last picked model, or -1. */
    private int pickedReplay = -1;
    private boolean gizmoHandlePicked;
    private boolean initialized;
    private boolean loggedFailure;

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
     * Renders the id pass and picks under the cursor.
     *
     * <p>Runs in the UI pass, so it owns the matrices, the framebuffer and the readback itself;
     * the cursor coordinates are converted exactly the way the film editor converts them
     * ({@code pickGUI}, which honours the GUI scale).</p>
     */
    public void render(UIContext context, ReplayEditSession session, Area area)
    {
        this.pickedReplay = -1;
        this.gizmoHandlePicked = false;

        if (context == null || session == null || area == null || area.w <= 0 || area.h <= 0)
        {
            return;
        }

        if (!area.isInside(context))
        {
            this.stencil.clearIndex();

            return;
        }

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

        this.ensureStencil(area);

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
            int index = 0;

            for (Replay replay : session.film().replays.getList())
            {
                if (replay == null || !Boolean.TRUE.equals(replay.enabled.get()))
                {
                    continue;
                }

                IEntity entity = entities.get(replay.getId());

                if (entity == null || entity.getForm() == null)
                {
                    continue;
                }

                try
                {
                    session.applyPose(entity, replay);

                    this.stencilMap.objectIndex = REPLAY_STENCIL_OFFSET + index;
                    this.stencilMap.setIncrement(false);

                    FilmEntityRenderer.renderEntity(FilmControllerContext.instance
                        .setup(entities, entity, replay, actorCamera, stack, null, transition)
                        .stencil(this.stencilMap)
                        .relative(Boolean.TRUE.equals(replay.relative.get())));
                }
                catch (RuntimeException e)
                {
                    if (!this.loggedFailure)
                    {
                        this.loggedFailure = true;
                        BbsAeAddon.LOGGER.warn("Replay mode could not pick replay {}", replay.getId(), e);
                    }
                }

                index += 1;
            }

            this.renderGizmoStencil(session, viewCamera, transition);

            /* The readback has to happen while the pick framebuffer is still bound. */
            this.stencil.pickGUI(context, area, HANDLE_PICK_RADIUS, Gizmo.STENCIL_MAX);
            this.stencil.unbind(this.stencilMap);

            int picked = this.stencil.getIndex();

            this.gizmoHandlePicked = picked >= Gizmo.STENCIL_X && picked <= Gizmo.STENCIL_MAX;

            if (picked >= REPLAY_STENCIL_OFFSET)
            {
                this.pickedReplay = picked - REPLAY_STENCIL_OFFSET;
            }
        }
        catch (RuntimeException e)
        {
            if (!this.loggedFailure)
            {
                this.loggedFailure = true;
                BbsAeAddon.LOGGER.warn("Replay mode pick pass failed", e);
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
        Matrix4f placement = FilmMatrices.getMatrixForRenderWithRotation(
            entity, camera.position.x, camera.position.y, camera.position.z, transition
        );

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
            this.initialized = true;
        }
        else if (!this.initialized)
        {
            this.initialized = true;
        }
    }

    /** Forgets the last pick (selection changes make a stale id meaningless). */
    public void clear()
    {
        this.stencil.clearPicking();
        this.pickedReplay = -1;
        this.gizmoHandlePicked = false;
    }
}
