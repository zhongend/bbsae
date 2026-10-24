package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
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
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The composition render graph.
 *
 * <p>Every layer is rendered into its own transparent target, then mask -> track matte -> effects ->
 * styles -> blend happen in that order. Adjustment layers apply their chain to the accumulated
 * target instead of producing content. Precomps recurse with their own time. The result is one
 * RGBA texture that the BBS frame overlay (or the render queue) can consume.</p>
 *
 * <p>Transform composition, parenting and the camera all live in {@link CompScene} / {@link CompCamera};
 * this class only decides <em>what</em> to draw and in which order. That split is what keeps the
 * editor gizmos, the depth sort and the actual pixels on the same matrices.</p>
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

    public static void render(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean preview, boolean captureDepth, Matrix4f projection, Batcher2D batcher)
    {
        render(project, doc, time, alpha, frameWidth, frameHeight, fit, preview, captureDepth, true, true, projection, batcher);
    }

    public static void render(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean preview, boolean captureDepth, boolean renderEffects, boolean renderMasks, Matrix4f projection, Batcher2D batcher)
    {
        render(project, doc, time, alpha, frameWidth, frameHeight, fit, preview, captureDepth, renderEffects, renderMasks, projection, batcher, true);
    }

    /**
     * @param cameraPreview kept for source compatibility; the camera is always applied now. It only
     *                      controls whether the editor draws 3D overlays.
     */
    public static void render(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean preview, boolean captureDepth, boolean renderEffects, boolean renderMasks, Matrix4f projection, Batcher2D batcher, boolean cameraPreview)
    {
        GlFramebuffer composition = renderToTexture(project, doc, time, captureDepth, renderEffects, renderMasks, preview, projection);

        if (composition == null)
        {
            return;
        }

        blit(batcher, composition, doc, alpha, frameWidth, frameHeight, fit);
        FboPool.release(composition);
    }

    /**
     * Editor preview with frame caching.
     *
     * <p>Renders at most once per composition frame <b>and</b> only when the content signature
     * changed. A parked playhead with no edits costs one textured quad per display frame instead of
     * the whole graph; playing back a 30 fps composition on a 120 Hz display renders 30 times a
     * second instead of 120.</p>
     *
     * <p>Two further shortcuts live here, in this order:</p>
     * <ol>
     *   <li><b>Pre-render cache</b> — if the frame is green on the ruler, the pixels are already
     *       on disk/VRAM and playback is a texture lookup. This is what makes a heavy composition
     *       play back smoothly.</li>
     *   <li><b>Preview proxy</b> — otherwise the graph is rendered at the size the viewport
     *       actually displays ({@link #previewScale}), not at composition resolution. A 1920×1080
     *       comp shown in a 700 px wide panel costs a quarter of the pixels it used to.</li>
     * </ol>
     */
    public static void renderCached(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, boolean renderEffects, boolean renderMasks, boolean cameraOverlays, Batcher2D batcher)
    {
        if (project == null || doc == null || doc.layers == null || doc.layers.isEmpty() || frameWidth <= 0F || frameHeight <= 0F)
        {
            return;
        }

        float fps = Math.max(1F, doc.fps);
        int frameIndex = Math.round(Math.max(0F, time) * fps);

        /* 1. Green frames: the whole point of the pre-render cache. */
        GlTexture cached = CompPrecache.texture(project, doc, frameIndex);

        if (cached != null)
        {
            blitTexture(batcher, cached.id, doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        float scale = previewScale(doc, frameWidth, frameHeight, fit);
        long signature = CompRenderCache.signature(project, doc, time, renderEffects, renderMasks, fit, Math.round(frameWidth), Math.round(frameHeight), cameraOverlays) ^ ((long) Math.round(scale * 1000F) << 48);

        if (CompRenderCache.matches(signature, doc, doc.width, doc.height))
        {
            blit(batcher, CompRenderCache.get(), doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        GlFramebuffer composition = renderToTexture(project, doc, time, false, renderEffects, renderMasks, true, null, scale);

        if (composition == null)
        {
            return;
        }

        /* The cache owns this framebuffer now; releasing it here would hand a live texture back to
         * the pool and the next layer would draw over the cached frame. */
        CompRenderCache.put(composition, signature, doc, doc.width, doc.height);
        blit(batcher, composition, doc, alpha, frameWidth, frameHeight, fit);
    }

    /**
     * Resolution factor that makes the render target match the displayed pixels.
     *
     * <p>Never above 1 (a preview is not a render), never below 0.2 (a very small panel should
     * still be legible), and quantised to 1/16 so dragging a splitter does not re-render on every
     * pixel of movement.</p>
     */
    public static float previewScale(CompDocument doc, float frameWidth, float frameHeight, int fit)
    {
        float dw = Math.max(1F, doc.width);
        float dh = Math.max(1F, doc.height);
        float sx = frameWidth / dw;
        float sy = frameHeight / dh;
        float uniform = fit == 2 ? Math.max(sx, sy) : fit == 1 ? Math.max(sx, sy) : Math.min(sx, sy);

        uniform = Math.max(0.2F, Math.min(1F, uniform));

        return Math.round(uniform * 16F) / 16F;
    }

    private static GlFramebuffer renderToTexture(CompProject project, CompDocument doc, float time, boolean captureDepth, boolean renderEffects, boolean renderMasks, boolean preview, Matrix4f projection)
    {
        return renderToTexture(project, doc, time, captureDepth, renderEffects, renderMasks, preview, projection, 1F);
    }

    private static GlFramebuffer renderToTexture(CompProject project, CompDocument doc, float time, boolean captureDepth, boolean renderEffects, boolean renderMasks, boolean preview, Matrix4f projection, float scale)
    {
        if (project == null || doc == null || doc.layers == null || doc.layers.isEmpty())
        {
            return null;
        }

        SceneDepth depth = null;

        if (needsDepth(project, doc))
        {
            /* In the editor the world has already been drawn for this frame, so its depth is the
             * only correct depth for a depth effect: copying the *compositor's* framebuffer here
             * (the old behaviour) read an empty depth attachment and every depth effect silently
             * degraded to a no-op. The bridge copies the world depth at WorldRenderEvents.LAST;
             * use that copy while previewing, and a fresh capture only in the export path. */
            SceneDepth bridged = preview ? dev.bbsae.client.overlay.BbsPreviewBridge.depth() : null;

            if (bridged != null && bridged.isAvailable())
            {
                depth = bridged;
            }
            else if (captureDepth)
            {
                depth = ensureDepth();
                depth.capture();
            }
        }

        GlFramebuffer composition;

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
                composition = renderCompToFbo(project, doc, time, depth, projection, client.gameRenderer.getCamera(), new HashSet<>(), fboBatcher, 0, renderEffects, renderMasks, preview, scale);
            }
            finally
            {
                modelView.pop();
                RenderSystem.applyModelViewMatrix();
            }
        }

        /* Our passes bind their own VAOs. Minecraft's BufferRenderer caches the last
         * VertexBuffer object, and that cache is only invalidated when the buffer object
         * changes; without this reset the next UI draw could reuse the wrong VAO. */
        BufferRenderer.resetCurrentVertexBuffer();

        return composition;
    }

    private static void blit(Batcher2D batcher, GlFramebuffer composition, CompDocument doc, float alpha, float frameWidth, float frameHeight, int fit)
    {
        if (composition == null || batcher == null)
        {
            return;
        }

        blitTexture(batcher, composition.color.id, doc, alpha, frameWidth, frameHeight, fit);
    }

    /**
     * Paints a composition texture into the viewport. Used by the live FBO, the render cache and
     * the pre-render cache alike, so a cached frame can never be framed differently from a fresh
     * one.
     */
    private static void blitTexture(Batcher2D batcher, int texture, CompDocument doc, float alpha, float frameWidth, float frameHeight, int fit)
    {
        if (batcher == null || texture <= 0)
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

        /* The FBO is top-down because layer content is drawn with an ortho whose y axis points
         * down. GL's texture origin is the opposite, so the final blit swaps v. */
        int color = Colors.setA(0xFFFFFF, Math.max(0F, Math.min(1F, alpha)));

        batcher.texturedBox(
            texture,
            color,
            ox, oy,
            doc.width * scaleX, doc.height * scaleY,
            0F, doc.height, doc.width, 0F,
            doc.width, doc.height
        );

        batcher.flush();
    }

    /**
     * Depth capture is a real GPU readback-style blit; it is only worth doing when some layer
     * actually samples depth. Skipping it is the difference between "the editor idles" and "the
     * editor copies a 1080p depth buffer 60 times a second for nothing".
     */
    private static boolean needsDepth(CompProject project, CompDocument doc)
    {
        if (project.settings != null && !project.settings.depthEnabled)
        {
            return false;
        }

        return hasDepthEffect(doc);
    }

    private static boolean hasDepthEffect(CompDocument doc)
    {
        for (CompLayer layer : doc.layers)
        {
            if (layer.effects == null)
            {
                continue;
            }

            for (CompEffect effect : layer.effects)
            {
                if (effect != null && effect.enabled && effect.type != null && effect.type.startsWith("depth_"))
                {
                    return true;
                }
            }
        }

        return false;
    }

    public static GlFramebuffer renderOffscreen(CompProject project, CompDocument doc, float time, Batcher2D batcher)
    {
        try (GlStateGuard guard = new GlStateGuard())
        {
            CompShaders.init();
            exportDepth += 1;

            try
            {
                return renderCompToFbo(project, doc, time, null, null, null, new HashSet<>(), batcher, 0, true, true, true, 1F);
            }
            finally
            {
                exportDepth -= 1;
            }
        }
    }

    /**
     * Renders one frame for the pre-render cache at an explicit pixel size.
     *
     * <p>Same graph, same effects, same masks as the preview — the only difference is the target
     * resolution and the fact that the caller keeps the texture. Guide layers and the editor's
     * 3D overlays are suppressed because a cached frame must equal an exported frame.</p>
     *
     * <p>It deliberately goes through {@link #renderToTexture} instead of drawing with the caller's
     * batcher: the UI's {@link Batcher2D} has queued quads for the panel, and flushing those into
     * the composition target would both corrupt the cached frame and lose the UI batch.</p>
     */
    public static GlFramebuffer renderForCache(CompProject project, CompDocument doc, float time, int width, int height)
    {
        if (project == null || doc == null || width <= 0 || height <= 0)
        {
            return null;
        }

        float scale = Math.max(0.05F, Math.min(1F, Math.min(width / (float) Math.max(1, doc.width), height / (float) Math.max(1, doc.height))));

        return renderToTexture(project, doc, time, false, true, true, false, null, scale);
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

    /** True while rendering for the recorder/render queue; editor-only overlays must be suppressed. */
    public static boolean isExporting()
    {
        return exportDepth > 0;
    }

    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks)
    {
        return renderCompToFbo(project, comp, time, depth, projection, camera, stack, batcher, recursion, renderEffects, renderMasks, true, 1F);
    }

    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks, boolean preview)
    {
        return renderCompToFbo(project, comp, time, depth, projection, camera, stack, batcher, recursion, renderEffects, renderMasks, preview, 1F);
    }

    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks, boolean preview, float scale)
    {
        if (comp == null || comp.layers == null || comp.layers.isEmpty() || recursion > 8 || stack.contains(comp.id))
        {
            return null;
        }

        stack.add(comp.id);

        CompRenderContext context = new CompRenderContext(project, comp, time, projection, camera, depth);

        context.preview = preview;
        context.scale = Math.max(0.05F, Math.min(1F, scale));

        GlFramebuffer accumulation = FboPool.acquire(context.renderWidth(), context.renderHeight());
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
            for (CompLayer layer : orderLayers(comp, context))
            {
                /* Camera layers drive the view; they have no pixels of their own. */
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
                        accumulation = applyAdjustment(layer, accumulation, context, stack, batcher);
                    }
                    continue;
                }

                Matrix4f model = CompScene.model(context, layer);
                Matrix4f layerProjection = CompScene.projection(context, layer);
                GlFramebuffer content = renderLayerContent(layer, context, model, layerProjection, stack, batcher);

                if (content == null)
                {
                    continue;
                }

                boolean is2D = !CompScene.usePerspective(context, layer);

                if (is2D && renderMasks && layer.masks != null && !layer.masks.isEmpty())
                {
                    MaskCache.MaskTexture mask = MaskCache.get(layer, comp.width, comp.height, model, context.frame);

                    if (mask != null)
                    {
                        GlFramebuffer masked = PassRenderer.mask(content, mask.texture.id, 0);

                        FboPool.release(content);
                        content = masked;
                    }
                }

                if (is2D && renderMasks && layer.trackMatteEnabled && layer.trackMatteId != null && !layer.trackMatteId.isEmpty())
                {
                    CompLayer matte = comp.getLayer(layer.trackMatteId);

                    if (matte != null && matte != layer)
                    {
                        Matrix4f matteModel = CompScene.model(context, matte);
                        GlFramebuffer matteFbo = renderLayerContent(matte, context, matteModel, CompScene.projection(context, matte), stack, batcher);

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

                Matrix4f effectSpace = effectSpace(context, layer, model);

                for (CompEffect effect : layer.effects)
                {
                    if (renderEffects && effect != null && effect.enabled)
                    {
                        CompEffect resolved = layer.resolveEffect(effect, context.frame);
                        GlFramebuffer processed = EffectRenderer.apply(layer, resolved, content, context, effectSpace);

                        if (processed != content)
                        {
                            FboPool.release(content);
                            content = processed;
                        }
                    }
                }

                if (renderEffects)
                {
                    content = applyStyles(layer, content, context, effectSpace);
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
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
        }

        return accumulation;
    }

    /**
     * Effects and styles work in layer-local UV space. For 2D layers that space is the camera-view
     * model matrix; a 3D layer keeps its world matrix (a perspective homography cannot be expressed
     * as the affine map those effects expect).
     */
    private static Matrix4f effectSpace(CompRenderContext context, CompLayer layer, Matrix4f model)
    {
        return CompScene.usePerspective(context, layer) ? CompScene.world(context, layer) : model;
    }

    /**
     * Painter's order: 3D slots are permuted far-to-near. Adjustment layers and 2D layers keep
     * their stack position, because an adjustment layer affects exactly the pixels accumulated
     * before it.
     */
    private static List<CompLayer> orderLayers(CompDocument comp, CompRenderContext context)
    {
        List<CompLayer> order = new ArrayList<>(comp.layers);
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

    private static void sortSegment(List<CompLayer> order, int start, int end, CompRenderContext context)
    {
        List<Integer> slots = new ArrayList<>();
        List<CompLayer> three = new ArrayList<>();

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

        /* Ascending depth = far first. CompScene caches the value for this frame, so the comparator
         * does a map lookup instead of rebuilding two matrices per comparison. */
        three.sort((a, b) -> Float.compare(CompScene.depth(context, a), CompScene.depth(context, b)));

        for (int i = 0; i < slots.size(); i++)
        {
            order.set(slots.get(i), three.get(i));
        }
    }

    private static GlFramebuffer renderLayerContent(CompLayer layer, CompRenderContext context, Matrix4f model, Set<String> stack, Batcher2D batcher)
    {
        return renderLayerContent(layer, context, model, CompScene.projection(context, layer), stack, batcher);
    }

    private static GlFramebuffer renderLayerContent(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f layerProjection, Set<String> stack, Batcher2D batcher)
    {
        GlFramebuffer target = FboPool.acquire(context.renderWidth(), context.renderHeight());

        target.begin();
        BufferRenderer.resetCurrentVertexBuffer();

        try
        {
            LayerContentRenderer.draw(layer, context, batcher, model, layerProjection);
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

    private static GlFramebuffer applyAdjustment(CompLayer layer, GlFramebuffer original, CompRenderContext context, Set<String> stack, Batcher2D batcher)
    {
        GlFramebuffer processed = original;
        boolean changed = false;
        Matrix4f space = new Matrix4f();

        for (CompEffect effect : layer.effects)
        {
            if (effect == null || !effect.enabled)
            {
                continue;
            }

            GlFramebuffer next = EffectRenderer.apply(layer, layer.resolveEffect(effect, context.frame), processed, context, space);

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
            MaskCache.MaskTexture mask = MaskCache.get(layer, context.width, context.height, CompScene.world(context, layer), context.frame);

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
            value = dev.bbsae.comp.CompExpression.eval(expression, new dev.bbsae.comp.CompExpression.Context()
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
                    return layer.opacityFrame(context.frame);
                }

                @Override
                public float variable(String path)
                {
                    return dev.bbsae.comp.CompExpression.variable(context.project, context.comp, path, layer.opacityFrame(context.frame), context.frame);
                }
            }, value);
        }

        return Math.max(0F, Math.min(1F, value));
    }

    /* Compatibility shims: the editor's gizmos and hit test used these before CompScene existed. */

    /** Resolves the frame's camera; kept so old call sites read naturally. */
    public static void prepare3D(CompRenderContext context, boolean cameraPreview)
    {
        CompScene.camera(context);
    }

    public static CompCamera camera(CompRenderContext context)
    {
        return CompScene.camera(context);
    }

    /** Model matrix (camera view included) for a layer, in the current frame. */
    public static Matrix4f world3D(CompDocument comp, CompLayer layer, CompRenderContext context)
    {
        return CompScene.model(context, layer);
    }

    /**
     * Projects a local point of an already-built <b>model</b> matrix (camera view included) to comp
     * pixels. The view must not be applied twice, which is why this only runs the projection.
     */
    public static Vector2f project3D(CompRenderContext context, Matrix4f model, float localX, float localY)
    {
        CompCamera camera = CompScene.camera(context);
        Vector3f point = new Vector3f(localX, localY, 0F);

        model.transformPosition(point);

        if (!camera.perspective)
        {
            return new Vector2f(point.x, point.y);
        }

        camera.projection.transformProject(point);

        return new Vector2f(
            (point.x * 0.5F + 0.5F) * context.width,
            (1F - (point.y * 0.5F + 0.5F)) * context.height
        );
    }

    /** World matrix (parents included) for a layer at the context's frame. */
    public static Matrix4f worldMatrix(CompDocument comp, CompLayer layer, CompRenderContext context, Set<String> seen)
    {
        return CompScene.world(context, layer);
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
        CompRenderCache.clear();
        CompPrecache.clearAll();
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
