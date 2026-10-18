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
                composition = renderCompToFbo(project, doc, time, depth, bindingProjection, client.gameRenderer.getCamera(), new HashSet<>(), fboBatcher, 0, renderEffects, renderMasks);
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

            return renderCompToFbo(project, doc, time, null, null, null, new HashSet<>(), batcher, 0, true, true);
        }
    }

    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks)
    {
        if (comp == null || comp.layers == null || comp.layers.isEmpty() || recursion > 8 || stack.contains(comp.id))
        {
            return null;
        }

        stack.add(comp.id);

        CompRenderContext context = new CompRenderContext(project, comp, time, projection, camera, depth);
        GlFramebuffer accumulation = FboPool.acquire(comp.width, comp.height);
        int previousFramebuffer = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        accumulation.begin(comp.background);

        boolean solo = false;

        for (CompLayer layer : comp.layers)
        {
            if (layer.visible && layer.solo)
            {
                solo = true;
                break;
            }
        }

        try
        {
            for (CompLayer layer : comp.layers)
            {
                if (!layer.activeAt(time) || layer.guide || "null".equals(layer.type))
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

                Matrix4f world = worldMatrix(comp, layer, context, new HashSet<>());
                GlFramebuffer content = renderLayerContent(layer, context, world, stack, batcher);

                if (content == null)
                {
                    continue;
                }

                if (renderMasks && layer.masks != null && !layer.masks.isEmpty())
                {
                    MaskCache.MaskTexture mask = MaskCache.get(layer, comp.width, comp.height, world, context.frame);

                    if (mask != null)
                    {
                        GlFramebuffer masked = PassRenderer.mask(content, mask.texture.id, 0);

                        FboPool.release(content);
                        content = masked;
                    }
                }

                if (renderMasks && layer.trackMatteEnabled && layer.trackMatteId != null && !layer.trackMatteId.isEmpty())
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
                        GlFramebuffer processed = EffectRenderer.apply(layer, resolved, content, context);

                        if (processed != content)
                        {
                            FboPool.release(content);
                            content = processed;
                        }
                    }
                }

                if (renderEffects)
                {
                    content = applyStyles(layer, content, context);
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

    private static GlFramebuffer renderLayerContent(CompLayer layer, CompRenderContext context, Matrix4f world, Set<String> stack, Batcher2D batcher)
    {
        GlFramebuffer target = FboPool.acquire(context.width, context.height);

        target.begin();
        BufferRenderer.resetCurrentVertexBuffer();

        try
        {
            LayerContentRenderer.draw(layer, context, batcher, world);
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

            GlFramebuffer next = EffectRenderer.apply(layer, layer.resolveEffect(effect, context.frame), processed, context);

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

    private static GlFramebuffer applyStyles(CompLayer layer, GlFramebuffer content, CompRenderContext context)
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

            GlFramebuffer styled = StyleRenderer.apply(style, content, context);

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

    public static Matrix4f worldMatrix(CompDocument comp, CompLayer layer, CompRenderContext context, Set<String> seen)
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

        Vector2f bound = BindingResolver.resolve(layer, context);

        if (bound != null)
        {
            x = bound.x;
            y = bound.y;
        }

        Matrix4f local = new Matrix4f()
            .translate(x, y, 0F)
            .rotate(new Quaternionf().rotateZ((float) Math.toRadians(rotation)))
            .scale(Math.max(0.0001F, transform.scale.x), Math.max(0.0001F, transform.scale.y), 1F);

        if (layer.parentId != null && !layer.parentId.isEmpty())
        {
            CompLayer parent = comp.getLayer(layer.parentId);

            if (parent != null && parent != layer)
            {
                return worldMatrix(comp, parent, context, seen).mul(local);
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
