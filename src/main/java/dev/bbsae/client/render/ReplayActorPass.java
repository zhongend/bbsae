package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.overlay.BbsPreviewBridge;
import dev.bbsae.client.replay.ReplayModelPass;
import dev.bbsae.client.replay.ReplayTime;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Map;

/**
 * Renders BBS replay actors through the <b>composition camera</b>, not the real world camera.
 *
 * <h2>Why this exists</h2>
 * <p>A replay entity layer used to display a crop of the real-world frame. That is a photograph of
 * the actor from the film camera, not an object in the composition: orbiting the bbsae 3D camera
 * left it glued to the screen, and a 3D layer could not move it in depth at all. The user's request
 * is specific — the model has to live in bbsae's 3D camera space.</p>
 *
 * <h2>Mapping</h2>
 * <p>Minecraft entities live in a Y-up block world; composition space is X right, Y down, Z toward
 * the viewer, in pixels. The pass chooses the layer's centre as the actor group's anchor and maps
 * blocks to composition pixels with {@link #PIXELS_PER_BLOCK}:</p>
 * <pre>comp = layerPosition + scale(px, -px, px) · (world - actorCentre)</pre>
 * <p>That matrix is composed <b>after</b> {@link CompCamera#view}, so moving the comp camera or
 * orbiting the free viewport rotates and dollies the actual model. The model is rendered by BBS'
 * own {@link FilmEntityRenderer}, so poses, anchors, form properties and skin rendering are the
 * same code path as the film editor.</p>
 *
 * <p>3D entity layers use the composition camera. A 2D entity layer keeps the keyed world plate
 * when it is available; if the live plate/matte is missing, the same actor renderer falls back to
 * the composition ortho so the preview still shows the model instead of an empty placeholder.</p>
 */
public final class ReplayActorPass
{
    /**
     * Blocks-to-pixels. 64 px/block is the replay editor's own scale at 1080p; smaller
     * compositions scale down so a default mob does not fill/crop the whole frame.
     */
    private static float pixelsPerBlock(CompRenderContext context)
    {
        int custom = context.project == null || context.project.settings == null
            ? 0
            : context.project.settings.modelPixelsPerBlock;

        if (custom > 0)
        {
            return Math.max(8F, Math.min(128F, custom));
        }

        return Math.max(8F, Math.min(64F, context.height * 0.12F));
    }

    /**
     * Independent bbsae model mode: render the layer's BBS form from the composition camera.
     *
     * <p>This deliberately does not touch the film/real-world replay system. The form is placed at
     * the layer origin and drawn into a transparent depth-enabled target, so there is no world
     * plate, no matte background and no cross-actor depth bleed. The layer's normal transform
     * channels animate the model exactly like any other bbsae layer.</p>
     */
    static GlFramebuffer renderModel(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        if (layer == null || context == null || !layer.isEntityLayer())
        {
            return null;
        }

        Form form = layer.modelForm();

        if (form == null)
        {
            if (Boolean.getBoolean("bbsae.debugModelLog"))
            {
                dev.bbsae.BbsAeAddon.LOGGER.info("bbsae model: layer {} has no form", layer.id);
            }

            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.world == null || client.getBufferBuilders() == null)
        {
            if (Boolean.getBoolean("bbsae.debugModelLog"))
            {
                dev.bbsae.BbsAeAddon.LOGGER.info("bbsae model: missing client/world/builders");
            }

            return null;
        }

        int width = Math.max(1, context.renderWidth());
        int height = Math.max(1, context.renderHeight());

        if (context.project != null && context.project.settings != null)
        {
            float quality = Math.max(0.25F, Math.min(1F, context.project.settings.modelRenderScale));

            width = Math.max(1, Math.round(width * quality));
            height = Math.max(1, Math.round(height * quality));
        }

        GlFramebuffer buffer = modelTarget(layer, width, height);

        if (buffer == null)
        {
            return null;
        }

        /* Independent bbsae actors are 3D by definition, even when the layer was created before
         * the threeD flag existed. Never fall back to the ortho 2D projection here: that was the
         * "3D replay looks flattened" bug. */
        boolean useCamera = true;
        CompCamera camera = CompScene.camera(context);
        float pixelsPerBlock = pixelsPerBlock(context);
        Matrix4f mapping = new Matrix4f(CompScene.world(context, layer))
            .scale(pixelsPerBlock, -pixelsPerBlock, pixelsPerBlock);

        /* Legacy model layers were created before Z scale was initialized, so a 2×/3× X/Y scale
         * with Z=1 rendered the actor depth-squashed. Treat that exact untouched state as uniform;
         * an authored scaleZ channel always wins. */
        if (!layer.hasScalarChannel("scaleZ") && layer.scaleZ == 1F && layer.scaleX != 1F)
        {
            mapping.scale(1F, 1F, layer.scaleX);
        }
        Matrix4f modelView = camera == null ? mapping : new Matrix4f(camera.view).mul(mapping);
        Matrix4f projection = camera == null
            ? GlStateGuard.ortho(context.width, context.height)
            : camera.projection;

        MatrixStack modelStack = RenderSystem.getModelViewStack();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix3f previousInverse = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        boolean queued = FormRenderLast.suspend();

        ReplayEntityMask.setCompositeActorPass(true);

        try (GlStateGuard guard = new GlStateGuard())
        {
            batcher.flush();
            buffer.begin(0x00000000);

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);

            modelStack.push();
            modelStack.peek().getPositionMatrix().set(modelView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(modelView).invert());

            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(2, ReplayModelPass.whiteLightmapId());
            RenderSystem.setShaderLights(new org.joml.Vector3f(0.2F, -1F, 0.3F),
                new org.joml.Vector3f(-0.2F, 1F, -0.3F));

            StubEntity entity = new StubEntity(client.world);

            entity.setForm(form);
            java.util.Map<String, IEntity> entities = java.util.Collections.singletonMap("bbsae_model", entity);
            MatrixStack stack = new MatrixStack();
            VertexConsumerProvider consumers = client.getBufferBuilders().getEntityVertexConsumers();
            Camera synthetic = new SyntheticCamera();
            FilmControllerContext actor = FilmControllerContext.instance
                .setup(entities, entity, null, synthetic, stack, consumers, 0F)
                .shadow(context.project != null && context.project.settings != null
                    && context.project.settings.modelShadows, 0F)
                .nameTag("")
                .relative(false);

            FilmEntityRenderer.renderEntity(actor);
            GL11.glEnable(GL11.GL_DEPTH_TEST);

            if (Boolean.getBoolean("bbsae.debugModelLog"))
            {
                dev.bbsae.BbsAeAddon.LOGGER.info("bbsae model: draw call {} form={} at {}x{}",
                    layer.id, form.getClass().getSimpleName(), width, height);
            }
        }
        catch (RuntimeException e)
        {
            logFailure(e);
            releaseTarget();

            return null;
        }
        finally
        {
            ReplayEntityMask.setCompositeActorPass(false);
            FormRenderLast.restore(queued);

            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(previousInverse);
            modelStack.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            BufferRenderer.resetCurrentVertexBuffer();
        }

        return buffer;
    }

    /**
     * World blocks -> composition pixels: put the actor group's centre at the layer origin and
     * convert blocks with the composition's pixels-per-block scale (Y inverted because comp Y is
     * down). The layer's world matrix (translation/rotation/scale/parents) is applied first.
     *
     * <p>Package-private and pure so the anchor/scale can be tested without a GL context.</p>
     */
    static Matrix4f actorMapping(CompLayer layer, CompRenderContext context, double cx, double cy, double cz)
    {
        float pixelsPerBlock = pixelsPerBlock(context);

        return new Matrix4f(CompScene.world(context, layer))
            .mul(new Matrix4f()
                .scale(pixelsPerBlock, -pixelsPerBlock, pixelsPerBlock)
                .translate((float) -cx, (float) -cy, (float) -cz));
    }
    private static GlFramebuffer target;
    private static int targetWidth;
    /** One target per independent model layer: a shared target was cleared by the next bake. */
    private static final java.util.Map<CompLayer, GlFramebuffer> MODEL_TARGETS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<CompLayer, int[]> MODEL_TARGET_SIZES =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static int targetHeight;
    private static long lastFailureLog;
    private static int suppressedFailures;

    private ReplayActorPass()
    {}

    /**
     * Renders the actors kept by {@code layer} into a composition-sized transparent target.
     *
     * @return the target, or null when there is no film/actor/entity binding or the pass failed.
     */
    static GlFramebuffer render(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        return render(layer, context, batcher, true);
    }

    /**
     * Screen-fixed fallback for a 2D entity layer when the live world plate/matte is unavailable.
     * It uses the composition ortho and the layer's screen-space world matrix, so the model still
     * previews in the compositor instead of leaving an empty placeholder.
     */
    static GlFramebuffer renderFlat(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        return render(layer, context, batcher, false);
    }

    private static GlFramebuffer render(CompLayer layer, CompRenderContext context, Batcher2D batcher, boolean useCamera)
    {
        if (layer == null || context == null || layer.isEntityLayer() == false || (useCamera && !layer.threeD))
        {
            return null;
        }

        if (BbsPreviewBridge.filmPanel() == null)
        {
            return null;
        }

        Film film = BbsPreviewBridge.film();
        Map<String, IEntity> entities = BbsPreviewBridge.entities();

        if (film == null || film.replays == null || entities == null || entities.isEmpty())
        {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getBufferBuilders() == null)
        {
            return null;
        }

        int width = Math.max(1, context.renderWidth());
        int height = Math.max(1, context.renderHeight());
        GlFramebuffer buffer = target(width, height);

        if (buffer == null)
        {
            return null;
        }

        float playhead = context.time * ReplayTime.TICKS_PER_SECOND;
        int tick = (int) Math.floor(playhead);
        float transition = playhead - tick;

        /* Pose first: the centre and the render use the exact same frame of the replay. */
        int kept = 0;
        double cx = 0D;
        double cy = 0D;
        double cz = 0D;

        for (Replay replay : film.replays.getList())
        {
            if (replay == null || !Boolean.TRUE.equals(replay.enabled.get()) || !layer.entityKeeps(replay.getId()))
            {
                continue;
            }

            IEntity entity = entities.get(replay.getId());

            if (entity == null || entity.getForm() == null)
            {
                continue;
            }

            applyPose(replay, entity, tick, transition);
            cx += entity.getX();
            cy += entity.getY();
            cz += entity.getZ();
            kept += 1;
        }

        if (kept == 0)
        {
            return null;
        }

        cx /= kept;
        cy /= kept;
        cz /= kept;

        CompCamera camera = useCamera ? CompScene.camera(context) : null;
        Matrix4f mapping = actorMapping(layer, context, cx, cy, cz);
        Matrix4f modelView = camera == null ? mapping : new Matrix4f(camera.view).mul(mapping);
        Matrix4f projection = camera == null
            ? GlStateGuard.ortho(context.width, context.height)
            : camera.projection;

        MatrixStack modelStack = RenderSystem.getModelViewStack();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix3f previousInverse = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        boolean queued = FormRenderLast.suspend();

        /* The pass fires ACTOR_BEFORE too; that event is how ReplayEntityMask snapshots the world
         * actor. Without the guard the composition camera would overwrite the world matte pose. */
        ReplayEntityMask.setCompositeActorPass(true);

        try (GlStateGuard guard = new GlStateGuard())
        {
            batcher.flush();
            buffer.begin(0x00000000);

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);

            modelStack.push();
            modelStack.peek().getPositionMatrix().set(modelView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(modelView).invert());

            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(2, ReplayModelPass.whiteLightmapId());
            RenderSystem.setShaderLights(new org.joml.Vector3f(0.2F, -1F, 0.3F), new org.joml.Vector3f(-0.2F, 1F, -0.3F));

            MatrixStack stack = new MatrixStack();
            VertexConsumerProvider consumers = client.getBufferBuilders().getEntityVertexConsumers();
            Camera synthetic = new SyntheticCamera();

            for (Replay replay : film.replays.getList())
            {
                if (replay == null || !Boolean.TRUE.equals(replay.enabled.get()) || !layer.entityKeeps(replay.getId()))
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
                    FilmControllerContext actor = FilmControllerContext.instance
                        .setup(entities, entity, replay, synthetic, stack, consumers, transition)
                        .shadow(false, 0F)
                        .nameTag("")
                        .relative(false);

                    FilmEntityRenderer.renderEntity(actor);
                }
                catch (RuntimeException e)
                {
                    logFailure(e);
                }
            }

            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        catch (RuntimeException e)
        {
            logFailure(e);
            releaseTarget();

            return null;
        }
        finally
        {
            ReplayEntityMask.setCompositeActorPass(false);
            FormRenderLast.restore(queued);

            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(previousInverse);
            modelStack.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableDepthTest();
            BufferRenderer.resetCurrentVertexBuffer();
        }

        return buffer;
    }

    /**
     * Camera at the origin of the actor world. The composition placement lives in {@code modelView}
     * instead; this object only satisfies the camera-relative coordinate contract and supplies the
     * billboard orientation.
     */
    private static final class SyntheticCamera extends Camera
    {
        SyntheticCamera()
        {
            this.setPos(0D, 0D, 0D);
            this.setRotation(0F, 0F);
        }
    }

    private static void applyPose(Replay replay, IEntity entity, int tick, float transition)
    {
        float at = replay.getTick(tick) + transition;

        replay.keyframes.apply(at, entity);

        if (entity.getForm() != null)
        {
            replay.properties.applyProperties(entity.getForm(), at);
        }

        mchorse.bbs_mod.forms.renderers.utils.RenderFrame.invalidate();
    }

    private static GlFramebuffer modelTarget(CompLayer layer, int width, int height)
    {
        GlFramebuffer existing = MODEL_TARGETS.get(layer);
        int[] size = MODEL_TARGET_SIZES.get(layer);

        if (existing != null && size != null && size[0] == width && size[1] == height)
        {
            return existing;
        }

        if (existing != null)
        {
            existing.delete();
            MODEL_TARGETS.remove(layer);
            MODEL_TARGET_SIZES.remove(layer);
        }

        try
        {
            GlFramebuffer created = new GlFramebuffer(width, height, true);

            MODEL_TARGETS.put(layer, created);
            MODEL_TARGET_SIZES.put(layer, new int[] {width, height});

            return created;
        }
        catch (RuntimeException e)
        {
            logFailure(e);

            return null;
        }
    }

    private static GlFramebuffer target(int width, int height)
    {
        if (target != null && targetWidth == width && targetHeight == height)
        {
            return target;
        }

        releaseTarget();

        try
        {
            target = new GlFramebuffer(width, height, true);
            targetWidth = width;
            targetHeight = height;
        }
        catch (RuntimeException e)
        {
            logFailure(e);
            target = null;
        }

        return target;
    }

    private static void releaseTarget()
    {
        if (target != null)
        {
            target.delete();
            target = null;
        }

        targetWidth = 0;
        targetHeight = 0;
    }

    /** Releases the offscreen actor target (client shutdown / resource reload). */
    public static void release()
    {
        releaseTarget();

        synchronized (MODEL_TARGETS)
        {
            for (GlFramebuffer framebuffer : MODEL_TARGETS.values())
            {
                framebuffer.delete();
            }

            MODEL_TARGETS.clear();
            MODEL_TARGET_SIZES.clear();
        }
    }

    private static void logFailure(RuntimeException e)
    {
        long now = System.currentTimeMillis();

        if (now - lastFailureLog > 10000L)
        {
            lastFailureLog = now;
            BbsAeAddon.LOGGER.warn("Could not render replay actors in composition camera space{}",
                suppressedFailures > 0 ? " (" + suppressedFailures + " similar failures suppressed)" : "", e);
            suppressedFailures = 0;
        }
        else
        {
            suppressedFailures += 1;
        }
    }
}
