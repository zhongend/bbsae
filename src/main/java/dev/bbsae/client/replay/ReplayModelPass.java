package dev.bbsae.client.replay;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.render.GlFramebuffer;
import dev.bbsae.client.render.GlStateGuard;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Map;

/**
 * Draws the film's replay models into a private, depth-enabled framebuffer and blits it into the
 * panel's viewport rectangle.
 *
 * <h2>Why an off-screen pass</h2>
 * <p>Same reason {@code CompViewportRenderer} has one: the UI pass has its own ortho projection
 * and a shared vertex batcher, so drawing perspective geometry straight into the middle of it
 * inherits state no model renderer expects. One target, one projection, one blit.</p>
 *
 * <h2>Which matrix goes where</h2>
 * <p>The actor pass is written the way BBS' own world pass is: the geometry stack is
 * camera-relative and {@code RenderSystem}'s model-view matrix carries the camera view. So the
 * pass sets the model-view to the mapped camera view, uses an identity {@link MatrixStack}, and
 * feeds {@link FilmEntityRenderer} the same {@link FilmControllerContext} the film editor uses —
 * replay keyframes, form properties, shadows off (there is no ground), name tags off (they would
 * only overlap the model), relative anchors honoured.</p>
 *
 * <h2>State</h2>
 * <p>Everything is wrapped in a {@link GlStateGuard}; the only things restored by hand are the
 * projection and model-view matrices, which the guard does not own. The vertex buffer is
 * invalidated on the way out for the same reason the entity matte does it: the form renderers
 * share Minecraft's immediate buffer, and the UI's next draw would otherwise replay our vertices
 * into the screen.</p>
 */
public final class ReplayModelPass
{
    private static GlFramebuffer target;
    private static int targetWidth;
    private static int targetHeight;
    private static boolean loggedFailure;

    private ReplayModelPass()
    {}

    /**
     * Renders every enabled replay of the session's film into the viewport rectangle and blits it.
     *
     * @return how many actors were drawn
     */
    public static int draw(Batcher2D batcher, ReplayEditSession session, float screenX, float screenY, int width, int height)
    {
        if (batcher == null || session == null || width <= 0 || height <= 0)
        {
            return 0;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getBufferBuilders() == null)
        {
            return 0;
        }

        session.camera().setViewport(width, height);

        GlFramebuffer buffer = target(width, height);

        if (buffer == null)
        {
            return 0;
        }

        Camera viewCamera = session.viewCamera();
        net.minecraft.client.render.Camera actorCamera = session.actorCamera();
        Map<String, IEntity> entities = session.entities();
        float transition = session.transition();
        int drawn = 0;

        /* The batch queued for the panel must reach the screen before the framebuffer changes,
         * or those vertices land in the 3D target instead of the UI. */
        batcher.flush();

        MatrixStack modelView = RenderSystem.getModelViewStack();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix3f previousInverse = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        boolean queued = FormRenderLast.suspend();

        try (GlStateGuard guard = new GlStateGuard())
        {
            buffer.begin(0x00000000);

            /* GlFramebuffer.begin() leaves depth testing off (it is a 2D target normally); the
             * model pass needs it, and it must be the raw call rather than a cached one. */
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);

            modelView.push();
            modelView.peek().getPositionMatrix().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(viewCamera.projection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(viewCamera.view).invert());

            MatrixStack stack = new MatrixStack();
            VertexConsumerProvider consumers = client.getBufferBuilders().getEntityVertexConsumers();

            if (entities != null)
            {
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

                        FilmControllerContext context = FilmControllerContext.instance
                            .setup(entities, entity, replay, actorCamera, stack, consumers, transition)
                            .shadow(false, 0F)
                            .nameTag("")
                            .relative(Boolean.TRUE.equals(replay.relative.get()));

                        FilmEntityRenderer.renderEntity(context);
                        drawn += 1;
                    }
                    catch (RuntimeException e)
                    {
                        if (!loggedFailure)
                        {
                            loggedFailure = true;
                            BbsAeAddon.LOGGER.warn("Replay mode could not render replay {}", replay.getId(), e);
                        }
                    }
                }
            }

            /* A renderer that leaves depth testing off would break the next actor in the list;
             * FilmEntityRenderer re-enables it, and this is the belt for a form that throws. */
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        finally
        {
            FormRenderLast.restore(queued);

            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(previousInverse);
            modelView.pop();
            RenderSystem.applyModelViewMatrix();

            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            BufferRenderer.resetCurrentVertexBuffer();
        }

        batcher.texturedBox(buffer.color.id, 0xFFFFFFFF,
            screenX, screenY, width, height,
            0F, height, width, 0F, width, height);
        batcher.flush();

        return drawn;
    }

    private static GlFramebuffer target(int width, int height)
    {
        if (target != null && targetWidth == width && targetHeight == height)
        {
            return target;
        }

        if (target != null)
        {
            target.delete();
            target = null;
        }

        try
        {
            target = new GlFramebuffer(width, height, true);
            targetWidth = width;
            targetHeight = height;
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay mode could not create its model framebuffer", e);
            target = null;
        }

        return target;
    }

    /** Releases the persistent target; called when the client stops. */
    public static void release()
    {
        if (target != null)
        {
            target.delete();
            target = null;
        }

        targetWidth = 0;
        targetHeight = 0;
        loggedFailure = false;
    }

    /** Diagnostics hook: the target's GL id, or 0. */
    public static int targetId()
    {
        return target != null ? GL30.glIsFramebuffer(target.id) ? target.id : 0 : 0;
    }
}
