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
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
 * <h2>Which actors are drawn</h2>
 * <p>{@link ReplayEditSession#isReplayVisible(int)} decides. The viewport updates the visibility
 * list once per frame with a frustum test ({@link ReplayCulling}); this pass draws the selected
 * actor plus every visible one, and skips the rest before paying for a pose application. On a
 * small film the list is all-true, so nothing changes for the common case.</p>
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

    /**
     * A 16x16 fully white texture bound to the entity shaders' light-map slot (Sampler2).
     *
     * <p>The world light map is not bound during the UI pass, so every actor drawn here sampled a
     * black/undefined light map and came out as a black silhouette — the bug the first
     * real-machine screenshot showed. BBS solves the same problem for its particle editor
     * ({@code UIParticleSchemeRenderer}) with exactly this white light map; binding it makes any
     * light-map coordinate sample white, i.e. full-bright, which is what an editor viewport wants
     * (the film preview renders the real lighting in the world pass).</p>
     */
    private static NativeImageBackedTexture whiteLightmapTexture;

    private static final Vector3f LIGHT0 = new Vector3f(0.2F, -1F, 0.3F);
    private static final Vector3f LIGHT1 = new Vector3f(-0.2F, 1F, -0.3F);

    private static final ReplayLogLimiter FAILURES = new ReplayLogLimiter();

    private static volatile String lastError;
    private static volatile long lastErrorMs;
    private static int lastDrawn;
    private static int lastAttempted;
    private static int lastSkipped;

    private ReplayModelPass()
    {}

    /**
     * Renders every visible actor of the session's film into the viewport rectangle and blits it.
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
        int attempted = 0;
        int skipped = 0;

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

            /* Geometry produced by FilmEntityRenderer is camera-relative WORLD space (blocks): the
             * matrix stack it fills holds each actor's camera-relative placement, and the view is
             * expected to arrive through RenderSystem's model-view matrix exactly like BBS' world
             * pass. So the model-view is the mapped camera view (blocks → comp → view), NOT
             * identity: with identity the actors stayed at the origin in block units, far outside
             * the projection's pixel-scale frustum, and the viewport looked empty. */
            modelView.push();
            modelView.peek().getPositionMatrix().set(viewCamera.view);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(viewCamera.projection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(viewCamera.view).invert());

            /* See whiteLightmapTexture: without this every actor is a black silhouette in the UI
             * pass. The light directions are explicit for the same reason — world rendering last
             * set them, but a UI pass must not rely on leftovers from another pipeline. */
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(2, whiteLightmapId());
            RenderSystem.setShaderLights(LIGHT0, LIGHT1);

            MatrixStack stack = new MatrixStack();
            VertexConsumerProvider consumers = client.getBufferBuilders().getEntityVertexConsumers();

            if (entities != null)
            {
                for (int i = 0; i < session.film().replays.getList().size(); i++)
                {
                    Replay replay = session.film().replays.getList().get(i);

                    if (replay == null || !Boolean.TRUE.equals(replay.enabled.get()))
                    {
                        continue;
                    }

                    IEntity entity = entities.get(replay.getId());

                    if (entity == null || entity.getForm() == null)
                    {
                        continue;
                    }

                    attempted += 1;

                    if (!session.isReplayVisible(i))
                    {
                        skipped += 1;

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
                        long now = System.currentTimeMillis();

                        if (FAILURES.allow(now))
                        {
                            recordError("render replay " + replay.getId() + ": " + e.getMessage());
                            BbsAeAddon.LOGGER.warn("Replay mode could not render replay {}{}",
                                replay.getId(), FAILURES.describeSuppressed(), e);
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
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            BufferRenderer.resetCurrentVertexBuffer();

            lastDrawn = drawn;
            lastAttempted = attempted;
            lastSkipped = skipped;
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
            recordError("create framebuffer " + width + "x" + height + ": " + e.getMessage());
            BbsAeAddon.LOGGER.warn("Replay mode could not create its model framebuffer", e);
            target = null;
        }

        return target;
    }

    private static void recordError(String message)
    {
        lastError = message;
        lastErrorMs = System.currentTimeMillis();
    }

    /**
     * Lazily builds the full-bright light map (16x16, every texel white). Created on the render
     * thread from this pass; released in {@link #release()}.
     */
    private static int whiteLightmapId()
    {
        if (whiteLightmapTexture == null)
        {
            NativeImageBackedTexture texture = new NativeImageBackedTexture(16, 16, false);
            NativeImage image = texture.getImage();

            for (int y = 0; y < 16; y++)
            {
                for (int x = 0; x < 16; x++)
                {
                    image.setColor(x, y, -1);
                }
            }

            texture.upload();
            whiteLightmapTexture = texture;
        }

        return whiteLightmapTexture.getGlId();
    }

    /** Releases the persistent target; called when the client stops. */
    public static void release()
    {
        if (target != null)
        {
            target.delete();
            target = null;
        }

        if (whiteLightmapTexture != null)
        {
            int id = whiteLightmapTexture.getGlId();

            whiteLightmapTexture.close();

            if (id > 0)
            {
                GL11.glDeleteTextures(id);
            }

            whiteLightmapTexture = null;
        }

        targetWidth = 0;
        targetHeight = 0;
        FAILURES.reset();
        lastError = null;
        lastErrorMs = 0L;
        lastDrawn = 0;
        lastAttempted = 0;
        lastSkipped = 0;
    }

    /** Last render/creation failure, or null. Stale entries are ignored by the panel HUD. */
    public static String lastError()
    {
        return lastError;
    }

    public static long lastErrorMs()
    {
        return lastErrorMs;
    }

    public static boolean hasRecentError()
    {
        return lastError != null && System.currentTimeMillis() - lastErrorMs < 6000L;
    }

    public static int lastDrawn()
    {
        return lastDrawn;
    }

    public static int lastAttempted()
    {
        return lastAttempted;
    }

    public static int lastSkipped()
    {
        return lastSkipped;
    }

    /** Diagnostics hook: the target's GL id, or 0. */
    public static int targetId()
    {
        return target != null ? GL30.glIsFramebuffer(target.id) ? target.id : 0 : 0;
    }
}
