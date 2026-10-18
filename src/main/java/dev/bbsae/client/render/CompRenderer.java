package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompExpression;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompStyle;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * The composition render graph.
 *
 * <p>Every layer is rendered into its own transparent target, then mask -> track matte -> effects ->
 * styles -> blend happen in that order. Adjustment layers apply their chain to the accumulated
 * target instead of producing content. Precomps recurse with their own time. The result is one
 * RGBA texture that the BBS frame overlay (or the render queue) can consume.</p>
 */
public final class CompRenderer
{
    private static SceneDepth sceneDepth;
    /** >0 while rendering for export/recording; guide layers are editor-only. */
    private static int exportDepth;

    private CompRenderer()
    {}

    /** Compatibility entry point for callers that only have a document. */
    public static void render(MatrixStack stack, Batcher2D batcher, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit)
    {
        CompProject project = CompStore.load(CompStore.DEFAULT_PROJECT);

        render(project, doc, time, alpha, frameWidth, frameHeight, fit, false, true, null, batcher);
    }

    /**
     * @param captureDepth whether the current world framebuffer's depth should be copied for depth
     *                     effects. Pass false in editor previews and off-screen comp-only exports.
     * @param projection   the film/world projection at the start of the overlay, or null to use a
     *                     perspective projection derived from the composition.
     */
    public static void render(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean preview, boolean captureDepth, Matrix4f projection, Batcher2D batcher)
    {
        render(project, doc, time, alpha, frameWidth, frameHeight, fit, preview, captureDepth, true, true, projection, batcher);
    }

    /**
     * Editor previews can disable effects or masks to compare a layer with and without its
     * post chain. Export and the film overlay always use the full graph.
     */
    public static void render(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean preview, boolean captureDepth, boolean renderEffects, boolean renderMasks, Matrix4f projection, Batcher2D batcher)
    {
        render(project, doc, time, alpha, frameWidth, frameHeight, fit, preview, captureDepth, renderEffects, renderMasks, projection, batcher, true);
    }

    /**
     * @param cameraPreview editor-only switch: false looks at the 3D space from the default front
     *                      camera, true looks through the active 3D camera. Export always uses true.
     */
    public static void render(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean preview, boolean captureDepth, boolean renderEffects, boolean renderMasks, Matrix4f projection, Batcher2D batcher, boolean cameraPreview)
    {
        if (project == null || doc == null || doc.layers == null || doc.layers.isEmpty() || frameWidth <= 0F || frameHeight <= 0F)
        {
            return;
        }

        float scaleX;
        float scaleY;

        if (fit == 2)
        {
            scaleX = frameWidth / doc.width;
            scaleY = frameHeight / doc.height;
        }
        else
        {
            float uniform = fit == 1
                ? Math.max(frameWidth / doc.width, frameHeight / doc.height)
                : Math.min(frameWidth / doc.width, frameHeight / doc.height);

            scaleX = uniform;
            scaleY = uniform;
        }

        float ox = (frameWidth - doc.width * scaleX) / 2F;
        float oy = (frameHeight - doc.height * scaleY) / 2F;
        Matrix4f bindingProjection = projection;

        if (bindingProjection == null)
        {
            bindingProjection = new Matrix4f().perspective(
                (float) Math.toRadians(70F),
                Math.max(0.01F, doc.width / (float) doc.height),
                0.05F,
                1000F
            );
        }

        SceneDepth depth = null;

        if (captureDepth && (project.settings == null || project.settings.depthEnabled))
        {
            depth = ensureDepth();
            depth.capture();
        }

        GlFramebuffer composition;

        batcher.flush();

        try (GlStateGuard guard = new GlStateGuard())
        {
            CompShaders.init();
            MinecraftClient client = MinecraftClient.getInstance();
            Batcher2D fboBatcher = new Batcher2D(new net.minecraft.client.gui.DrawContext(client, client.getBufferBuilders().getEntityVertexConsumers()));
            MatrixStack modelView = RenderSystem.getModelViewStack();

            modelView.push();
            modelView.peek().getPositionMatrix().identity();
            RenderSystem.applyModelViewMatrix();

            try
            {
                composition = renderCompToFbo(project, doc, time, depth, bindingProjection, client.gameRenderer.getCamera(), new HashSet<>(), fboBatcher, 0, renderEffects, renderMasks, cameraPreview);
            }
            finally
            {
                modelView.pop();
                RenderSystem.applyModelViewMatrix();
            }
        }

        if (composition == null)
        {
            return;
        }

        /* Our passes bind their own VAOs. Minecraft's BufferRenderer caches the last
         * VertexBuffer object, and that cache is only invalidated when the buffer object
         * changes; without this reset the next UI draw could reuse the wrong VAO. */
        BufferRenderer.resetCurrentVertexBuffer();

        /* The FBO is top-down because layer content is drawn with an ortho whose y axis points
         * down. GL's texture origin is the opposite, so the final blit swaps v. */
        int color = Colors.setA(0xFFFFFF, Math.max(0F, Math.min(1F, alpha)));

        batcher.texturedBox(
            composition.color.id,
            color,
            ox, oy,
            doc.width * scaleX, doc.height * scaleY,
            0F, doc.height, doc.width, 0F,
            doc.width, doc.height
        );

        batcher.flush();
        FboPool.release(composition);
    }

    public static GlFramebuffer renderOffscreen(CompProject project, CompDocument doc, float time, Batcher2D batcher)
    {
        try (GlStateGuard guard = new GlStateGuard())
        {
            CompShaders.init();
            exportDepth += 1;

            try
            {
                return renderCompToFbo(project, doc, time, null, null, null, new HashSet<>(), batcher, 0, true, true);
            }
            finally
            {
                exportDepth -= 1;
            }
        }
    }

    /** Marks the film-overlay/recording path, where guide layers must not be baked in. */
    public static void beginExport()
    {
        exportDepth += 1;
    }

    public static void endExport()
    {
        exportDepth = Math.max(0, exportDepth - 1);
    }

    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks)
    {
        return renderCompToFbo(project, comp, time, depth, projection, camera, stack, batcher, recursion, renderEffects, renderMasks, true);
    }

    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks, boolean cameraPreview)
    {
        if (comp == null || comp.layers == null || comp.layers.isEmpty() || recursion > 8 || stack.contains(comp.id))
        {
            return null;
        }

        stack.add(comp.id);

        CompRenderContext context = new CompRenderContext(project, comp, time, projection, camera, depth);

        setup3D(context, cameraPreview);

        GlFramebuffer accumulation = FboPool.acquire(comp.width, comp.height);
        int previousFramebuffer = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        accumulation.begin(comp.background);

        boolean solo = false;

        for (CompLayer layer : comp.layers)
        {
            /* A camera is a view, not content: it must never enter the solo scan either, or
             * soloing a camera would hide the whole composition. */
            if (layer.visible && layer.solo && !"camera".equals(layer.type))
            {
                solo = true;
                break;
            }
        }

        try
        {
            for (CompLayer layer : orderLayersFor3D(comp, context))
            {
                /* Camera layers drive {@link #cameraView}; they have no pixels of their own.
                 * Without this check they fell through to the solid drawing path and left a
                 * coloured rectangle at the camera position. */
                if (!layer.activeAt(time) || (layer.guide && exportDepth > 0)
                    || "null".equals(layer.type) || "camera".equals(layer.type))
                {
                    continue;
                }

                if (solo && !layer.solo)
                {
                    continue;
                }

                if ("adjustment".equals(layer.type))
                {
                    if (renderEffects)
                    {
                        Matrix4f adjustmentWorld = worldMatrix(comp, layer, context, new HashSet<>());
                        accumulation = applyAdjustment(layer, accumulation, context, adjustmentWorld, stack, batcher);
                    }
                    continue;
                }

                boolean is3D = layer.threeD && !"camera".equals(layer.type) && !"adjustment".equals(layer.type);
                Matrix4f world;
                Matrix4f layerProjection = null;

                if (is3D)
                {
                    world = context.view3D.mul(localWorldMatrix3D(comp, layer, context, new HashSet<>()));
                    layerProjection = context.projection3D;
                }
                else
                {
                    world = worldMatrix(comp, layer, context, new HashSet<>());
                }

                GlFramebuffer content = renderLayerContent(layer, context, world, layerProjection, stack, batcher);

                if (content == null)
                {
                    continue;
                }

                if (!is3D && renderMasks && layer.masks != null && !layer.masks.isEmpty())
                {
                    MaskCache.MaskTexture mask = MaskCache.get(layer, comp.width, comp.height, world, context.frame);

                    if (mask != null)
                    {
                        GlFramebuffer masked = PassRenderer.mask(content, mask.texture.id, 0);

                        FboPool.release(content);
                        content = masked;
                    }
                }

                if (!is3D && renderMasks && layer.trackMatteEnabled && layer.trackMatteId != null && !layer.trackMatteId.isEmpty())
                {
                    CompLayer matte = comp.getLayer(layer.trackMatteId);

                    if (matte != null && matte != layer)
                    {
                        Matrix4f matteWorld = worldMatrix(comp, matte, context, new HashSet<>());
                        GlFramebuffer matteFbo = renderLayerContent(matte, context, matteWorld, stack, batcher);

                        if (matteFbo != null)
                        {
                            int channel = layer.trackMatteMode == CompLayer.MATTE_LUMA ? 1 : layer.trackMatteMode == CompLayer.MATTE_ALPHA_INVERTED ? 2 : layer.trackMatteMode == CompLayer.MATTE_LUMA_INVERTED ? 3 : 0;
                            GlFramebuffer matted = PassRenderer.mask(content, matteFbo.color.id, channel);

                            FboPool.release(content);
                            FboPool.release(matteFbo);
                            content = matted;
                        }
                    }
                }

                for (var effect : layer.effects)
                {
                    if (renderEffects && effect != null && effect.enabled)
                    {
                        CompEffect resolved = layer.resolveEffect(effect, context.frame);
                        GlFramebuffer processed = EffectRenderer.apply(layer, resolved, content, context, world);

                        if (processed != content)
                        {
                            FboPool.release(content);
                            content = processed;
                        }
                    }
                }

                if (renderEffects)
                {
                    content = applyStyles(layer, content, context, world);
                }

                float opacity = opacityAt(layer, context);

                if (opacity <= 0.0001F)
                {
                    FboPool.release(content);
                    continue;
                }

                GlFramebuffer blended = PassRenderer.draw(content, accumulation, null, layer.blendMode, opacity, 0F, 0F, 0xFFFFFF);

                FboPool.release(content);
                FboPool.release(accumulation);
                accumulation = blended;
            }

            batcher.flush();
            BufferRenderer.resetCurrentVertexBuffer();
        }
        finally
        {
            stack.remove(comp.id);

            /* A precomp is rendered while its parent's layer target is bound. Restore that
             * framebuffer, viewport and projection so the caller can continue drawing into it. */
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
        }

        return accumulation;
    }

    /**
     * Painter's order for 3D layers: only the 3D slots are permuted (far to near), so adjustment
     * layers and 2D screen-attached layers keep their stack position. Without this a card at
     * z=+300 could be drawn before a card behind it and win the blend.
     *
     * <p>Sorting is confined to the segment between two adjustment layers. An adjustment layer
     * affects exactly the pixels accumulated before it, so letting a 3D layer from above cross
     * that boundary would silently change which layers the adjustment sees.</p>
     */
    private static java.util.List<CompLayer> orderLayersFor3D(CompDocument comp, CompRenderContext context)
    {
        java.util.List<CompLayer> order = new java.util.ArrayList<>(comp.layers);
        int start = 0;

        for (int i = 0; i <= order.size(); i++)
        {
            boolean boundary = i == order.size() || "adjustment".equals(order.get(i).type);

            if (!boundary)
            {
                continue;
            }

            if (i - start > 1)
            {
                sortSegment(order, start, i, context);
            }

            start = i + 1;
        }

        return order;
    }

    private static void sortSegment(java.util.List<CompLayer> order, int start, int end, CompRenderContext context)
    {
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<CompLayer> three = new java.util.ArrayList<>();

        for (int i = start; i < end; i++)
        {
            CompLayer layer = order.get(i);

            if (layer.threeD && !"camera".equals(layer.type) && !"adjustment".equals(layer.type))
            {
                slots.add(i);
                three.add(layer);
            }
        }

        if (three.size() < 2)
        {
            return;
        }

        three.sort((a, b) -> Float.compare(depthOf(a, context), depthOf(b, context)));

        for (int i = 0; i < slots.size(); i++)
        {
            order.set(slots.get(i), three.get(i));
        }
    }

    private static float depthOf(CompLayer layer, CompRenderContext context)
    {
        try
        {
            Matrix4f world = context.view3D.mul(localWorldMatrix3D(context.comp, layer, context, new HashSet<>()));
            org.joml.Vector4f center = new org.joml.Vector4f(0F, 0F, 0F, 1F);

            world.transform(center);

            return center.z;
        }
        catch (RuntimeException e)
        {
            return 0F;
        }
    }

    private static GlFramebuffer renderLayerContent(CompLayer layer, CompRenderContext context, Matrix4f world, Set<String> stack, Batcher2D batcher)
    {
        return renderLayerContent(layer, context, world, null, stack, batcher);
    }

    private static GlFramebuffer renderLayerContent(CompLayer layer, CompRenderContext context, Matrix4f world, Matrix4f layerProjection, Set<String> stack, Batcher2D batcher)
    {
        GlFramebuffer target = FboPool.acquire(context.width, context.height);

        target.begin();
        BufferRenderer.resetCurrentVertexBuffer();

        try
        {
            LayerContentRenderer.draw(layer, context, batcher, world, layerProjection);
            batcher.flush();
            BufferRenderer.resetCurrentVertexBuffer();

            /* BBS draws text/images with straight-alpha source textures, which leaves the
             * transparent target premultiplied. Every later pass works in straight alpha. */
            GlFramebuffer straightened = PassRenderer.unpremultiply(target);

            if (straightened != target)
            {
                FboPool.release(target);
                target = straightened;
            }
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.error("Could not render composition layer {}", layer.id, e);
            FboPool.release(target);
            return null;
        }

        return target;
    }

    private static GlFramebuffer applyAdjustment(CompLayer layer, GlFramebuffer original, CompRenderContext context, Matrix4f world, Set<String> stack, Batcher2D batcher)
    {
        GlFramebuffer processed = original;
        boolean changed = false;

        for (var effect : layer.effects)
        {
            if (effect == null || !effect.enabled)
            {
                continue;
            }

            GlFramebuffer next = EffectRenderer.apply(layer, layer.resolveEffect(effect, context.frame), processed, context, world);

            if (next != processed)
            {
                if (processed != original)
                {
                    FboPool.release(processed);
                }

                processed = next;
                changed = true;
            }
        }

        if (!changed)
        {
            return original;
        }

        float opacity = opacityAt(layer, context);

        if (layer.masks != null && !layer.masks.isEmpty())
        {
            MaskCache.MaskTexture mask = MaskCache.get(layer, context.width, context.height, world, context.frame);

            if (mask != null)
            {
                GlFramebuffer masked = PassRenderer.mask(processed, mask.texture.id, 0);
                GlFramebuffer result = PassRenderer.draw(masked, original, null, CompLayer.BLEND_NORMAL, opacity, 0F, 0F, 0xFFFFFF);

                FboPool.release(masked);
                FboPool.release(processed);
                FboPool.release(original);

                return result;
            }
        }

        if (opacity < 0.999F)
        {
            GlFramebuffer result = PassRenderer.draw(processed, original, null, CompLayer.BLEND_NORMAL, opacity, 0F, 0F, 0xFFFFFF);

            FboPool.release(processed);
            FboPool.release(original);
            return result;
        }

        FboPool.release(original);
        return processed;
    }

    private static GlFramebuffer applyStyles(CompLayer layer, GlFramebuffer content, CompRenderContext context, Matrix4f world)
    {
        if (layer.styles == null)
        {
            return content;
        }

        for (CompStyle style : layer.styles)
        {
            if (style == null || !style.enabled)
            {
                continue;
            }

            GlFramebuffer styled = StyleRenderer.apply(style, content, context, layer, world);

            if (styled != content)
            {
                FboPool.release(content);
                content = styled;
            }
        }

        return content;
    }

    private static float opacityAt(CompLayer layer, CompRenderContext context)
    {
        float value = layer.opacityFrame(context.frame);
        String expression = layer.expressionOpacity;

        if (expression != null && !expression.isBlank())
        {
            value = CompExpression.eval(expression, expressionContext(layer, context, value), value);
        }

        return Math.max(0F, Math.min(1F, value));
    }

    /**
     * Builds the 3D camera once per composition frame.
     *
     * <p>World space is composition pixels with Y up (the UI is Y down, so every layer matrix
     * converts its local Y with a final scale(1,-1,1)). The camera sits on +Z looking towards the
     * composition; a default 50° camera at {@code z = focal} maps the z=0 plane exactly 1:1 to
     * the composition, which makes switching between 2D and 3D non-destructive.</p>
     */
    private static void setup3D(CompRenderContext context, boolean cameraPreview)
    {
        CompLayer camera = context.comp.activeCamera(context.time);
        boolean useCamera = camera != null && camera.camera3D && cameraPreview;
        float width = context.width;
        float height = context.height;
        float fov = 50F;
        float near = 10F;
        float far = 100000F;
        float cx = width * 0.5F;
        float cy = height * 0.5F;
        float cz;
        float tx = width * 0.5F;
        float ty = height * 0.5F;
        float tz = 0F;

        float roll = 0F;
        float rotationX = 0F;
        float rotationY = 0F;

        if (useCamera)
        {
            mchorse.bbs_mod.utils.pose.Transform transform = camera.transformAt(context.frame);

            cx = transform.translate.x;
            cy = transform.translate.y;
            /* cameraZ is the AE-style camera distance. Older projects only wrote the layer's
             * generic z, so fall back to it instead of silently collapsing the camera onto the
             * point of interest. */
            cz = camera.scalarAt("cameraZ", camera.cameraZ, context.frame);

            if (Math.abs(cz) < 0.001F)
            {
                cz = camera.scalarAt("z", camera.z, context.frame);
            }

            tx = camera.scalarAt("cameraTargetX", camera.cameraTargetX, context.frame);
            ty = camera.scalarAt("cameraTargetY", camera.cameraTargetY, context.frame);
            tz = camera.scalarAt("cameraTargetZ", camera.cameraTargetZ, context.frame);
            fov = camera.scalarAt("cameraFov", camera.cameraFov, context.frame);
            near = Math.max(0.1F, camera.scalarAt("cameraNear", camera.cameraNear, context.frame));
            far = Math.max(near + 1F, camera.scalarAt("cameraFar", camera.cameraFar, context.frame));
            rotationX = camera.scalarAt("rotationX", camera.rotationX, context.frame);
            rotationY = camera.scalarAt("rotationY", camera.rotationY, context.frame);
            roll = transform.rotate.z;

            if (Math.abs(cz - tz) < 0.001F && Math.abs(cx - tx) < 0.001F && Math.abs(cy - ty) < 0.001F)
            {
                cz = tz + height * 1.1F;
            }
        }
        else
        {
            float focal = (height * 0.5F) / (float) Math.tan(Math.toRadians(fov) * 0.5D);

            cz = focal;
        }

        fov = Math.max(1F, Math.min(179F, fov));

        float focal = (height * 0.5F) / (float) Math.tan(Math.toRadians(fov) * 0.5D);

        context.focal3D = focal;
        /* A 3D camera must not pan/zoom 2D layers: they are screen-attached by definition. */
        context.camera2DLayer = camera != null && !camera.camera3D ? camera : null;
        context.view2D = cameraView(context.camera2DLayer, context);
        context.view2DPrepared = true;
        context.camera3DLayer = useCamera ? camera : null;
        context.camera3DMode = useCamera;

        /* Camera orientation. World space is Y-up (the UI is Y-down, hence the -cy), +Z points
         * towards the viewer and the default camera sits at +Z looking at the origin.
         *
         * Two modes, like AE:
         *  - look-at: direction = point of interest - position, roll around that direction;
         *  - free:    direction from the camera's own X/Y rotation (yaw/pitch), roll from Z.
         *
         * The previous build ignored rotationX/rotationY entirely, which is why rotating a
         * camera in the inspector appeared to do nothing. */
        org.joml.Vector3f position = new org.joml.Vector3f(cx, -cy, cz);
        org.joml.Vector3f direction = new org.joml.Vector3f();

        if (useCamera && camera.cameraLookAt)
        {
            direction.set(tx - cx, -ty + cy, tz - cz);
        }
        else
        {
            float yaw = (float) Math.toRadians(rotationY);
            float pitch = (float) Math.toRadians(rotationX);

            direction.set(
                (float) (Math.sin(yaw) * Math.cos(pitch)),
                (float) Math.sin(pitch),
                (float) (-Math.cos(yaw) * Math.cos(pitch))
            );
        }

        if (direction.lengthSquared() < 0.000001F)
        {
            direction.set(0F, 0F, -1F);
        }

        direction.normalize();

        org.joml.Vector3f up = new org.joml.Vector3f(0F, 1F, 0F);
        org.joml.Vector3f right = new org.joml.Vector3f(direction).cross(up);

        if (right.lengthSquared() < 0.000001F)
        {
            right.set(1F, 0F, 0F);
        }

        right.normalize();
        up.set(right).cross(direction).normalize();

        if (Math.abs(roll) > 0.0001F)
        {
            float radians = (float) Math.toRadians(roll);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            org.joml.Vector3f rolledUp = new org.joml.Vector3f(up).mul(cos).add(new org.joml.Vector3f(right).mul(sin));

            up.set(rolledUp).normalize();
        }

        context.view3D = new Matrix4f().lookAt(position, new org.joml.Vector3f(position).add(direction), up);
        context.projection3D = new Matrix4f()
            .perspective((float) Math.toRadians(fov), Math.max(0.01F, width / height), near, far);
    }

    /** UI helper: build the 3D view/projection for gizmos and picking. */
    public static void prepare3D(CompRenderContext context, boolean cameraPreview)
    {
        setup3D(context, cameraPreview);
    }

    /** UI helper: model-view matrix for a 3D layer. */
    public static Matrix4f world3D(CompDocument comp, CompLayer layer, CompRenderContext context)
    {
        return context.view3D.mul(localWorldMatrix3D(comp, layer, context, new HashSet<>()));
    }

    /**
     * Projects a local layer point through the prepared 3D camera into composition pixels.
     * Returns null when the point is behind the camera.
     */
    public static org.joml.Vector2f project3D(CompRenderContext context, Matrix4f world, float localX, float localY)
    {
        org.joml.Vector4f point = new org.joml.Vector4f(localX, localY, 0F, 1F);

        world.transform(point);
        context.projection3D.transform(point);

        if (Math.abs(point.w) < 0.00001F)
        {
            return null;
        }

        float ndcX = point.x / point.w;
        float ndcY = point.y / point.w;

        return new org.joml.Vector2f(
            (ndcX * 0.5F + 0.5F) * context.width,
            (1F - (ndcY * 0.5F + 0.5F)) * context.height
        );
    }

    public static Matrix4f worldMatrix(CompDocument comp, CompLayer layer, CompRenderContext context, Set<String> seen)
    {
        Matrix4f local = localWorldMatrix(comp, layer, context, seen);

        if (layer == null || "adjustment".equals(layer.type) || "camera".equals(layer.type))
        {
            return local;
        }

        if (!context.view2DPrepared)
        {
            context.camera2DLayer = comp.activeCamera(context.time);
            context.view2D = cameraView(context.camera2DLayer, context);
            context.view2DPrepared = true;
        }

        if (context.camera2DLayer == null)
        {
            return local;
        }

        return context.view2D.mul(local);
    }

    /**
     * AE-style two-node camera. The camera layer's normal transform is the position, the target
     * is a separate point (animatable through cameraTargetX/Y), and the transform's X scale is
     * the zoom. The view keeps the camera position pinned to the comp centre, which is how a
     * two-node camera reads in a 2D comp: pan moves the world, the target turns it, zoom scales it.
     *
     * <p>The view is the <b>inverse</b> of the camera's own rotation: turning the camera clockwise
     * turns the world counter-clockwise. Both the look-at branch and the manual rotation branch
     * therefore use a negated angle — they used to disagree, so switching "look at target" off
     * silently reversed the rotation.</p>
     */
    public static Matrix4f cameraView(CompLayer camera, CompRenderContext context)
    {
        if (camera == null)
        {
            return new Matrix4f();
        }

        mchorse.bbs_mod.utils.pose.Transform transform = camera.transformAt(context.frame);
        float cx = transform.translate.x;
        float cy = transform.translate.y;
        float rotation = transform.rotate.z;

        if (camera.expressionX != null && !camera.expressionX.isBlank())
        {
            cx = CompExpression.eval(camera.expressionX, expressionContext(camera, context, cx), cx);
        }
        if (camera.expressionY != null && !camera.expressionY.isBlank())
        {
            cy = CompExpression.eval(camera.expressionY, expressionContext(camera, context, cy), cy);
        }
        if (camera.expressionRotation != null && !camera.expressionRotation.isBlank())
        {
            rotation = CompExpression.eval(camera.expressionRotation, expressionContext(camera, context, rotation), rotation);
        }

        /* One zoom value, like a real camera. The inspector writes both scale channels so a
         * layer-style transform (which has separate X/Y) cannot desync it. */
        float zoom = Math.max(0.0001F, Math.abs(transform.scale.x));
        float angle = (float) Math.toRadians(rotation);

        if (camera.cameraLookAt)
        {
            float tx = camera.scalarAt("cameraTargetX", camera.cameraTargetX, context.frame);
            float ty = camera.scalarAt("cameraTargetY", camera.cameraTargetY, context.frame);
            float dx = tx - cx;
            float dy = ty - cy;

            if (Math.abs(dx) + Math.abs(dy) > 0.0001F)
            {
                angle = (float) (Math.atan2(dy, dx) + Math.PI / 2D);
            }
        }

        return new Matrix4f()
            .translate(context.width / 2F, context.height / 2F, 0F)
            .scale(zoom, zoom, 1F)
            .rotateZ(-angle)
            .translate(-cx, -cy, 0F);
    }

    private static Matrix4f localWorldMatrix(CompDocument comp, CompLayer layer, CompRenderContext context, Set<String> seen)
    {
        if (layer == null || !seen.add(layer.id))
        {
            return new Matrix4f();
        }

        mchorse.bbs_mod.utils.pose.Transform transform = layer.transformAt(context.frame);
        float x = transform.translate.x;
        float y = transform.translate.y;
        float rotation = transform.rotate.z;

        if (layer.expressionX != null && !layer.expressionX.isBlank())
        {
            x = CompExpression.eval(layer.expressionX, expressionContext(layer, context, x), x);
        }
        if (layer.expressionY != null && !layer.expressionY.isBlank())
        {
            y = CompExpression.eval(layer.expressionY, expressionContext(layer, context, y), y);
        }
        if (layer.expressionRotation != null && !layer.expressionRotation.isBlank())
        {
            rotation = CompExpression.eval(layer.expressionRotation, expressionContext(layer, context, rotation), rotation);
        }

        if (layer.motion)
        {
            x += (float) Math.sin(context.time * 1.8F) * layer.motionAmount;
        }

        Shake shake = shakeOf(layer, context);

        if (shake != null)
        {
            x += shake.x;
            y += shake.y;
            rotation += shake.rotation;
        }

        Vector2f bound = BindingResolver.resolve(layer, context);

        if (bound != null)
        {
            x = bound.x;
            y = bound.y;
        }

        Matrix4f local = new Matrix4f()
            .translate(x, y, 0F)
            .rotate(new Quaternionf().rotateZ((float) Math.toRadians(rotation)))
            .scale(Math.max(0.0001F, transform.scale.x * (1F + layer.squash)),
                Math.max(0.0001F, transform.scale.y * (1F - layer.squash)), 1F);

        if (layer.parentId != null && !layer.parentId.isEmpty())
        {
            CompLayer parent = comp.getLayer(layer.parentId);

            if (parent != null && parent != layer)
            {
                return localWorldMatrix(comp, parent, context, seen).mul(local);
            }
        }

        return local;
    }

    private static final class Shake
    {
        float x;
        float y;
        float rotation;
    }

    private static Shake shakeOf(CompLayer layer, CompRenderContext context)
    {
        if (layer == null || layer.effects == null)
        {
            return null;
        }

        for (dev.bbsae.comp.CompEffect effect : layer.effects)
        {
            if (effect != null && effect.enabled && "shake".equals(effect.type))
            {
                float amount = Math.max(0F, effect.valueF("amplitude", 18F));
                float frequency = Math.max(0.01F, effect.valueF("frequency", 6F));
                float rotation = Math.max(0F, effect.valueF("rotation", 2F));
                float seed = effect.valueF("seed", 0F);
                float t = context.time * frequency * 6.2831855F;
                Shake shake = new Shake();

                shake.x = (float) (Math.sin(t + seed * 1.7F) * 0.65F + Math.sin(t * 2.3F + seed * 3.1F) * 0.35F) * amount;
                shake.y = (float) (Math.sin(t * 1.37F + seed * 5.3F) * 0.65F + Math.sin(t * 2.9F + seed * 7.7F) * 0.35F) * amount;
                shake.rotation = (float) Math.sin(t * 1.9F + seed * 11.1F) * rotation;

                return shake;
            }
        }

        return null;
    }

    /** Model matrix for a 3D layer: comp pixels, Y-up world, z towards the camera. */
    private static Matrix4f localWorldMatrix3D(CompDocument comp, CompLayer layer, CompRenderContext context, Set<String> seen)
    {
        if (layer == null || !seen.add(layer.id))
        {
            return new Matrix4f();
        }

        mchorse.bbs_mod.utils.pose.Transform transform = layer.transformAt(context.frame);
        float x = transform.translate.x;
        float y = transform.translate.y;
        float z = layer.scalarAt("z", layer.z, context.frame);
        float rotation = transform.rotate.z;
        float rotationX = layer.scalarAt("rotationX", layer.rotationX, context.frame);
        float rotationY = layer.scalarAt("rotationY", layer.rotationY, context.frame);
        float scaleZ = Math.max(0.0001F, layer.scalarAt("scaleZ", layer.scaleZ, context.frame));

        if (layer.expressionX != null && !layer.expressionX.isBlank())
        {
            x = CompExpression.eval(layer.expressionX, expressionContext(layer, context, x), x);
        }
        if (layer.expressionY != null && !layer.expressionY.isBlank())
        {
            y = CompExpression.eval(layer.expressionY, expressionContext(layer, context, y), y);
        }
        if (layer.expressionRotation != null && !layer.expressionRotation.isBlank())
        {
            rotation = CompExpression.eval(layer.expressionRotation, expressionContext(layer, context, rotation), rotation);
        }

        Shake shake = shakeOf(layer, context);

        if (shake != null)
        {
            x += shake.x;
            y += shake.y;
            rotation += shake.rotation;
        }

        float sx = Math.max(0.0001F, transform.scale.x * (1F + layer.squash));
        float sy = Math.max(0.0001F, transform.scale.y * (1F - layer.squash));
        Matrix4f local = new Matrix4f()
            .translate(x, -y, z)
            .rotateZ((float) Math.toRadians(-rotation))
            .rotateX((float) Math.toRadians(rotationX))
            .rotateY((float) Math.toRadians(rotationY))
            .scale(sx, sy, scaleZ)
            .scale(1F, -1F, 1F);

        if (layer.parentId != null && !layer.parentId.isEmpty())
        {
            CompLayer parent = comp.getLayer(layer.parentId);

            if (parent != null && parent != layer)
            {
                if (parent.threeD)
                {
                    return localWorldMatrix3D(comp, parent, context, seen).mul(local);
                }

                Matrix4f parent2D = localWorldMatrix(comp, parent, context, seen);
                Matrix4f converted = new Matrix4f().scale(1F, -1F, 1F).mul(parent2D).scale(1F, -1F, 1F);

                return converted.mul(local);
            }
        }

        return local;
    }

    private static CompExpression.Context expressionContext(CompLayer layer, CompRenderContext context, float value)
    {
        return new CompExpression.Context()
        {
            @Override
            public float time()
            {
                return context.time;
            }

            @Override
            public float frame()
            {
                return context.frame;
            }

            @Override
            public float fps()
            {
                return context.comp.fps;
            }

            @Override
            public float value()
            {
                return value;
            }

            @Override
            public float variable(String path)
            {
                return CompExpression.variable(context.project, context.comp, path, value, context.frame);
            }
        };
    }

    private static SceneDepth ensureDepth()
    {
        if (sceneDepth == null)
        {
            sceneDepth = new SceneDepth();
        }

        return sceneDepth;
    }

    /** Called from BBS resource reload / client shutdown. */
    public static void clearCaches()
    {
        FboPool.clear();
        ShapeCache.clear();
        MaskCache.clear();
        ImageTextureCache.clear();
        SceneColor.delete();
        BindingResolver.resetSmoothing();
        CompShaders.delete();

        if (sceneDepth != null)
        {
            sceneDepth.delete();
            sceneDepth = null;
        }
    }

    /** Exposed for the render queue, which needs a composition texture without drawing it. */
    public static GlFramebuffer renderForExport(CompProject project, CompDocument comp, float time, Batcher2D batcher)
    {
        return renderOffscreen(project, comp, time, batcher);
    }
}
