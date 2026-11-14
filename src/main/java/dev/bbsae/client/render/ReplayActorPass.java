package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.overlay.BbsPreviewBridge;
import dev.bbsae.client.replay.ReplayModelPass;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.BaseFilmController;
import dev.bbsae.client.replay.ReplayTime;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.FormUtils;
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

import java.util.HashMap;
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
        CompCamera camera = CompScene.camera(context);

        /* A resolved binding is a screen-space anchor: the camera already produced the composition
         * point, so projecting the actor through the camera view again moved it twice. The model is
         * drawn in composition pixels (orthographic), exactly like the layer's other content. */
        boolean bound = CompScene.boundPoint(context, layer) != null;

        return renderModel(layer, context, batcher,
            bound || camera == null ? null : camera.view,
            bound || camera == null ? GlStateGuard.ortho(context.width, context.height) : camera.projection,
            context.renderWidth(), context.renderHeight());
    }

    /**
     * Same independent-model pass with an explicit camera. The 3D workspace calls this with the
     * free orbit camera so the bbsae actor is visible while the camera world is being navigated;
     * the composition preview calls the overload above with the composition camera.
     */
    static GlFramebuffer renderModel(CompLayer layer, CompRenderContext context, Batcher2D batcher,
                                     Matrix4f view, Matrix4f projection, int targetWidth, int targetHeight)
    {
        if (layer == null || context == null || !layer.isEntityLayer())
        {
            return null;
        }

        /* A bbsae replay added in the compositor is linked to a real BBS replay. Render that
         * replay so the replay editor's K-frames, pose, form properties and model library are
         * the same data the composition sees. Only an unlinked/legacy layer falls back to the
         * standalone layer form below. */
        if (layer.hasLinkedReplay())
        {
            GlFramebuffer linked = renderLinkedModel(layer, context, batcher, view, projection,
                targetWidth, targetHeight);

            if (linked != null)
            {
                return linked;
            }
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

        int width = Math.max(1, targetWidth);
        int height = Math.max(1, targetHeight);

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

        Matrix4f mapping = modelMapping(context, layer, 0F, 0F, 0F);

        /* Legacy model layers were created before Z scale was initialized, so a 2×/3× X/Y scale
         * with Z=1 rendered the actor depth-squashed. Treat that exact untouched state as uniform;
         * an authored scaleZ channel always wins. */
        if (!layer.hasScalarChannel("scaleZ") && layer.scaleZ == 1F && layer.scaleX != 1F)
        {
            mapping.scale(1F, 1F, layer.scaleX);
        }
        Matrix4f modelView = view == null ? mapping : new Matrix4f(view).mul(mapping);
        Matrix4f targetProjection = projection == null
            ? GlStateGuard.ortho(context.width, context.height)
            : projection;

        MatrixStack modelStack = RenderSystem.getModelViewStack();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix3f previousInverse = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        boolean queued = FormRenderLast.suspend();
        /* This pass renders into its own framebuffer; form translucency must be drawn here, not
         * deferred to the world's flush (where it would be missing from the model target). */
        boolean translucency = mchorse.bbs_mod.forms.FormTranslucentQueue.suspend();

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
            RenderSystem.setProjectionMatrix(targetProjection, VertexSorter.BY_Z);
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

            ReplayRenderLight.renderEntity(actor);
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
            mchorse.bbs_mod.forms.FormTranslucentQueue.restore(translucency);

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
     * Draws the film replay a bbsae model layer is linked to, through the same composition/free
     * camera as the layer itself.
     *
     * <p>This is the second half of the comp↔replay bridge: {@code CompReplayBridge} makes the
     * replay the shared record, and this pass renders that record instead of the layer's frozen
     * {@code formData}. A K-frame written by the replay editor is therefore visible in the
     * composition immediately, without copying keyframe channels between two timelines.</p>
     *
     * <p>The replay world position is anchored at {@code layer.replayAnchor*}; the layer's own
     * transform remains the whole-actor placement. Moving the replay offsets the actor by blocks
     * mapped through the composition's pixels-per-block, exactly like moving a film actor.</p>
     *
     * @return null when there is no live film/entity for the link, so the caller can fall back to
     *         the layer form instead of hiding the model.
     */
    private static GlFramebuffer renderLinkedModel(CompLayer layer, CompRenderContext context,
                                                    Batcher2D batcher, Matrix4f view, Matrix4f projection,
                                                    int targetWidth, int targetHeight)
    {
        /* The composition overlay sets BindingResolver.film (→ context.film) even when the
         * compositor panel is not the screen on display. BbsPreviewBridge.filmPanel only exists
         * while that panel is open; relying on it here made a linked replay fall back to the
         * layer's static form during BBS original playback/export, so the actor lost its
         * animation. Prefer the film the render is actually for. */
        Film film = context.film != null ? context.film : BbsPreviewBridge.film();
        Map<String, IEntity> entities = filmEntities(film);

        if (film == null)
        {
            return null;
        }

        Replay replay = film.replays.getById(layer.replayId);

        if (replay == null)
        {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.world == null || client.getBufferBuilders() == null)
        {
            return null;
        }

        IEntity entity = BbsPreviewBridge.entity(replay.getId());

        /* A virtual replay has a live entity only while the replay page is open. Outside it the
         * controller map may still hold the last stale copy (the replay is disabled again on
         * leaving the page); rendering that copy froze the model at whatever pose the page left.
         * The composition overlay/export therefore always uses the private, ticked stub for a
         * virtual replay. */
        if (entity != null && dev.bbsae.client.replay.CompReplayBridge.isVirtual(replay)
            && (!BbsPreviewBridge.isActive() || !Boolean.TRUE.equals(replay.enabled.get())))
        {
            entity = null;
        }

        CachedStub privateStub = null;

        if (entity == null)
        {
            /* A virtual replay is disabled in the real film outside the replay editor, so the
             * controller deliberately has no entity for it (BaseFilmController.createEntities
             * skips disabled replays). The composition still needs to draw it: build a private
             * stub and pose it from the replay's own keyframes. This is also what keeps bbsae's
             * actors out of the live world while their animation stays live in the comp. */
            privateStub = stubFor(film, replay, layer);

            if (privateStub == null || privateStub.entity.getForm() == null)
            {
                return null;
            }

            entity = privateStub.entity;
            entities = java.util.Collections.<String, IEntity>singletonMap("bbsae_linked", entity);
        }

        if (entity.getForm() == null)
        {
            return null;
        }

        int width = Math.max(1, targetWidth);

        int height = Math.max(1, targetHeight);

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

        Matrix4f mapping = modelMapping(context, layer, layer.replayAnchorX,
            layer.replayAnchorY, layer.replayAnchorZ);

        if (!layer.hasScalarChannel("scaleZ") && layer.scaleZ == 1F && layer.scaleX != 1F)
        {
            mapping.scale(1F, 1F, layer.scaleX);
        }

        Matrix4f modelView = view == null ? mapping : new Matrix4f(view).mul(mapping);
        Matrix4f targetProjection = projection == null
            ? GlStateGuard.ortho(context.width, context.height)
            : projection;

        float playhead = replayTick(context);
        int tick = (int) Math.floor(playhead);
        float transition = playhead - tick;
        float at = replay.getTick(tick);

        try
        {
            if (privateStub != null)
            {
                /* The private actor has no controller ticking it. Advance it through its form's
                 * animation state (age, limb swing, model/CEM animation) exactly once per film
                 * tick, then pose it from the replay's keyframes. Rebuilding a stub every frame
                 * left its age at 0, so the model stood in its first frame in BBS original
                 * playback/export while the same model animated in the replay editor. */
                advanceStub(privateStub, replay, tick, transition, at);
            }
            else
            {
                replay.keyframes.apply(at, entity);
                entity.setPrevX(entity.getX());
                entity.setPrevY(entity.getY());
                entity.setPrevZ(entity.getZ());
                entity.setPrevYaw(entity.getYaw());
                entity.setPrevHeadYaw(entity.getHeadYaw());
                entity.setPrevBodyYaw(entity.getBodyYaw());
                entity.setPrevPitch(entity.getPitch());

                if (entity.getForm() != null)
                {
                    replay.properties.applyProperties(entity.getForm(), at);
                }
            }

            mchorse.bbs_mod.forms.renderers.utils.RenderFrame.invalidate();
        }
        catch (RuntimeException e)
        {
            logFailure(e);

            return null;
        }

        MatrixStack modelStack = RenderSystem.getModelViewStack();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix3f previousInverse = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        boolean queued = FormRenderLast.suspend();
        boolean translucency = mchorse.bbs_mod.forms.FormTranslucentQueue.suspend();

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
            RenderSystem.setProjectionMatrix(targetProjection, VertexSorter.BY_Z);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(modelView).invert());

            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(2, ReplayModelPass.whiteLightmapId());
            RenderSystem.setShaderLights(new org.joml.Vector3f(0.2F, -1F, 0.3F),
                new org.joml.Vector3f(-0.2F, 1F, -0.3F));

            MatrixStack stack = new MatrixStack();
            VertexConsumerProvider consumers = client.getBufferBuilders().getEntityVertexConsumers();
            Camera synthetic = new SyntheticCamera();
            FilmControllerContext actor = FilmControllerContext.instance
                .setup(entities, entity, replay, synthetic, stack, consumers, transition)
                .shadow(context.project != null && context.project.settings != null
                    && context.project.settings.modelShadows, 0F)
                .nameTag("")
                .relative(false);

            ReplayRenderLight.renderEntity(actor);
            GL11.glEnable(GL11.GL_DEPTH_TEST);

            if (Boolean.getBoolean("bbsae.debugModelLog"))
            {
                dev.bbsae.BbsAeAddon.LOGGER.info("bbsae linked model: layer={} replay={} tick={} at {}x{}",
                    layer.id, replay.getId(), String.format(java.util.Locale.ROOT, "%.2f", at), width, height);
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
            mchorse.bbs_mod.forms.FormTranslucentQueue.restore(translucency);

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
        return modelMapping(context, layer, (float) cx, (float) cy, (float) cz);
    }

    /**
     * Shared model mapping: layer world · block→pixel scale · optional per-copy pixel offset ·
     * translate(-anchor). The offset is in <b>layer pixels</b>, i.e. after the block scale, which is
     * where {@code Replicate3D} computes copy transforms.
     */
    private static Matrix4f modelMapping(CompRenderContext context, CompLayer layer,
                                         float anchorX, float anchorY, float anchorZ)
    {
        float pixelsPerBlock = pixelsPerBlock(context);
        Matrix4f mapping = new Matrix4f(CompScene.world(context, layer))
            .scale(pixelsPerBlock, -pixelsPerBlock, pixelsPerBlock);

        if (context.modelPixelOffset != null)
        {
            mapping.mul(context.modelPixelOffset);
        }

        return mapping.translate(-anchorX, -anchorY, -anchorZ);
    }
    /**
     * Private stand-in actors for replays the live controller does not own.
     *
     * <p>A virtual bbsae replay is disabled in the real film, so BBS never creates an entity for
     * it. The composition still renders it from the replay's data. The stub has to persist across
     * frames: it is the entity whose {@code age}, limb animator and form state are advanced every
     * film tick. Re-creating it per render left all of that at zero, so the actor was drawn in
     * its first animation frame forever in BBS original playback/export while it animated in the
     * replay editor (which has a live entity).</p>
     */
    private static final Map<String, CachedStub> STUB_ACTORS = new HashMap<>();

    private static final class CachedStub
    {
        final StubEntity entity;
        final int formIdentity;
        int lastTick = Integer.MIN_VALUE;

        CachedStub(StubEntity entity, int formIdentity)
        {
            this.entity = entity;
            this.formIdentity = formIdentity;
        }
    }

    /**
     * Live actors for a film, even when the compositor's preview bridge is not attached.
     *
     * <p>{@link BbsPreviewBridge#entities()} is the compositor/replay-page map. The film overlay
     * path (BBS original editor and F6 recorder) renders the composition without that panel, but
     * BBS still owns a controller for the playing film — the same entities the world frame shows.
     * Falling back to it keeps a linked replay animated instead of demoting it to a static form.</p>
     */
    private static Map<String, IEntity> filmEntities(Film film)
    {
        Map<String, IEntity> entities = BbsPreviewBridge.entities();

        if (entities != null && !entities.isEmpty())
        {
            return entities;
        }

        if (film != null)
        {
            try
            {
                BaseFilmController controller = BBSModClient.getFilms() == null
                    ? null : BBSModClient.getFilms().getController(film.getId());

                if (controller != null && controller.getEntities() != null
                    && !controller.getEntities().isEmpty())
                {
                    return controller.getEntities();
                }
            }
            catch (RuntimeException ignored)
            {}
        }

        return entities == null ? java.util.Collections.emptyMap() : entities;
    }

    /**
     * Film tick a linked replay's animation is sampled at.
     *
     * <p>The overlay's absolute film tick is right for the top-level composition (it survives a
     * split/time-scaled clip), but a precomp has already applied its own offset/time scale and must
     * run on its local clock. Using the film tick inside a precomp ignored both and made nested
     * replay animation play at the wrong frame. The compositor preview never sets the absolute
     * tick, so it keeps using composition time in either case.</p>
     */
    public static float replayTick(CompRenderContext context)
    {
        if (context != null && !context.nested && BindingResolver.frameTick() >= 0F)
        {
            return BindingResolver.frameTick();
        }

        return context == null ? 0F : context.time * ReplayTime.TICKS_PER_SECOND;
    }

    private static CachedStub stubFor(Film film, Replay replay, CompLayer layer)
    {
        if (replay == null)
        {
            return null;
        }

        Form form = replay.form.get();

        if (form == null && layer != null)
        {
            form = layer.modelForm();
        }

        if (form == null)
        {
            return null;
        }

        String key = (film == null || film.getId() == null ? "-" : film.getId()) + "|" + replay.getId();
        int identity = System.identityHashCode(form);
        CachedStub cached = STUB_ACTORS.get(key);

        if (cached == null || cached.formIdentity != identity || cached.entity.getForm() == null)
        {
            StubEntity entity = new StubEntity(MinecraftClient.getInstance().world);

            entity.setForm(FormUtils.copy(form));
            cached = new CachedStub(entity, identity);
            STUB_ACTORS.put(key, cached);
        }

        return cached;
    }

    /**
     * Advances a private actor to {@code tick} and applies the replay at that tick.
     *
     * <p>An ordinary BBS actor is ticked by its controller: {@code update()} shifts prev to
     * current, increments the animation age and advances the limb animator; the replay keyframes
     * then write the current tick. A private stub has to do the same work itself, once per film
     * tick, or its procedural animation and limb swing stay parked at the first frame. Backward
     * seeks and long jumps reset the age instead of replaying hundreds of ticks, which would be
     * both slow and meaningless for a stub.</p>
     */
    private static void advanceStub(CachedStub cached, Replay replay, int tick, float transition, float at)
    {
        StubEntity entity = cached.entity;

        if (cached.lastTick == Integer.MIN_VALUE)
        {
            int previous = tick - 1;

            entity.setAge(Math.max(0, previous));
            replay.keyframes.apply(replay.getTick(previous), entity);
            copyPrev(entity);
            entity.update();

            if (entity.getForm() != null)
            {
                entity.getForm().update(entity);
            }

            cached.lastTick = tick;
        }
        else if (tick < cached.lastTick || tick - cached.lastTick > 40)
        {
            entity.setAge(Math.max(0, tick));
            replay.keyframes.apply(replay.getTick(tick), entity);
            copyPrev(entity);
            cached.lastTick = tick;
        }
        else
        {
            while (cached.lastTick < tick)
            {
                entity.update();

                if (entity.getForm() != null)
                {
                    entity.getForm().update(entity);
                }

                cached.lastTick += 1;
            }
        }

        replay.keyframes.apply(replay.getTick(tick), entity);

        if (entity.getForm() != null)
        {
            float phase = Math.max(0F, Math.min(1F, transition));

            replay.properties.applyProperties(entity.getForm(), at + phase);
        }

        if (Boolean.getBoolean("bbsae.debugModelLog") && tick % 20 == 0)
        {
            dev.bbsae.BbsAeAddon.LOGGER.info(
                "Replay stub: replay={} tick={} age={} form={} limb={}",
                replay.getId(), tick, entity.getAge(),
                entity.getForm() == null ? "-" : entity.getForm().getFormIdOrName(),
                entity.getLimbPos(0F));
        }
    }

    /** Snapshots the position/rotation channels so a freshly advanced stub does not lerp from 0. */
    private static void copyPrev(IEntity entity)
    {
        entity.setPrevX(entity.getX());
        entity.setPrevY(entity.getY());
        entity.setPrevZ(entity.getZ());
        entity.setPrevYaw(entity.getYaw());
        entity.setPrevHeadYaw(entity.getHeadYaw());
        entity.setPrevBodyYaw(entity.getBodyYaw());
        entity.setPrevPitch(entity.getPitch());
    }

    /** Releases private stand-in actors (resource reload / client shutdown). */
    public static void releaseStubs()
    {
        STUB_ACTORS.clear();
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

        if (BbsPreviewBridge.filmPanel() == null && context.film == null)
        {
            return null;
        }

        Film film = context.film != null ? context.film : BbsPreviewBridge.film();
        Map<String, IEntity> entities = filmEntities(film);

        if (film == null || film.replays == null)
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

        float playhead = replayTick(context);
        int tick = (int) Math.floor(playhead);
        float transition = playhead - tick;

        /* Pose first: the centre and the render use the exact same frame of the replay. The map
         * the render pass receives must contain the private stand-ins too — the old pass created
         * stubs here and then looked them up in the untouched live map, so every actor without a
         * live entity was silently skipped at render time (an empty 3D/flat entity layer in BBS
         * original playback). */
        Map<String, IEntity> renderEntities = new HashMap<>();

        if (entities != null)
        {
            renderEntities.putAll(entities);
        }

        Map<String, CachedStub> stubActors = new HashMap<>();
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

            IEntity entity = entities == null ? null : entities.get(replay.getId());
            CachedStub stub = null;

            if (entity == null)
            {
                /* The BBS film editor's overlay path does not own the replay editor's entity map
                 * (the bridge is attached only while the compositor is open). A live actor is not
                 * required to draw the matte actor: pose a private stub from the same keyframes. */
                stub = stubFor(film, replay, layer);

                if (stub == null)
                {
                    continue;
                }

                entity = stub.entity;
                renderEntities.put(replay.getId(), entity);
                stubActors.put(replay.getId(), stub);
            }

            if (entity.getForm() == null)
            {
                continue;
            }

            if (stub != null)
            {
                advanceStub(stub, replay, tick, transition, replay.getTick(tick));
            }
            else
            {
                applyPose(replay, entity, tick, transition);
            }

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
        boolean translucency = mchorse.bbs_mod.forms.FormTranslucentQueue.suspend();

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

                IEntity entity = renderEntities.get(replay.getId());

                if (entity == null || entity.getForm() == null)
                {
                    continue;
                }

                try
                {
                    FilmControllerContext actor = FilmControllerContext.instance
                        .setup(renderEntities, entity, replay, synthetic, stack, consumers, transition)
                        .shadow(false, 0F)
                        .nameTag("")
                        .relative(false);

                    ReplayRenderLight.renderEntity(actor);
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
            mchorse.bbs_mod.forms.FormTranslucentQueue.restore(translucency);

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
        /* Apply the integer keyframe tick; FilmEntityRenderer interpolates prev→current by the
         * transition it is given. Advancing by the transition here as well made the actor (and its
         * depth for the matte) up to a tick ahead of the world's copy. */
        float at = replay.getTick(tick);

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
            /* Sampleable depth: the compositor resolves the model against 3D text/shape planes
             * per pixel instead of treating the whole actor as one flat card. */
            GlFramebuffer created = new GlFramebuffer(width, height, true, true);

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
        releaseStubs();
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
