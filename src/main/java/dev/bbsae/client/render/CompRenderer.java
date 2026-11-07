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

        /* The entity matte is a per-frame product of the world render. Normally the
         * AFTER_ENTITIES pass already produced it; this only renders when the frame has not been
         * masked yet (the first frame after opening a project or changing the selection). */
        dev.bbsae.client.render.ReplayEntityMask.ensureCurrent(project, doc);

        float fps = Math.max(1F, doc.fps);
        /* Clamped like every other frame lookup: past the composition's end the green bar and the
         * AE preview must agree instead of the cache silently missing. */
        int frameIndex = frameIndex(doc, time);

        /* 1. Green frames: the whole point of the pre-render cache. Only when the A/B switches are
         * on, because a cached frame always contains effects and masks; otherwise the 效果/蒙版
         * toggles would do nothing while the playhead sits on a green frame. */
        GlTexture cached = (renderEffects && renderMasks) ? CompPrecache.texture(project, doc, frameIndex) : null;

        if (cached != null)
        {
            blitTexture(batcher, cached.id, doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        /* A composition that samples the live world (a BBS preview layer or a depth-keyed entity
         * layer) must not be served from the content cache: its pixels change when the film moves
         * even though the document did not. */
        boolean liveWorld = CompPrecache.dependsOnLiveWorld(doc);

        /* 2. A curve edit outside the playhead's segment cannot change this frame. Reuse the
         *    pixels instead of hashing the document and re-running the graph. */
        if (!liveWorld && CompRenderCache.reuseForTime(time, frameIndex))
        {
            blit(batcher, CompRenderCache.get(), doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        float scale = previewScale(doc, frameWidth, frameHeight, fit);
        long signature = CompRenderCache.signature(project, doc, time, renderEffects, renderMasks, fit, Math.round(frameWidth), Math.round(frameHeight), cameraOverlays) ^ ((long) Math.round(scale * 1000F) << 48);

        if (!liveWorld && CompRenderCache.matches(signature, doc, doc.width, doc.height))
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
        if (!liveWorld)
        {
            CompRenderCache.put(composition, signature, doc, doc.width, doc.height, frameIndex);
            blit(batcher, composition, doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        /* A live-world composition is never cached, so nobody else owns this target: without the
         * release every display frame leaked one composition-sized FBO (8 MB at 1080p), which
         * exhausted VRAM after a few seconds of playing a BBS-preview / entity / depth comp. */
        try
        {
            blit(batcher, composition, doc, alpha, frameWidth, frameHeight, fit);
        }
        finally
        {
            FboPool.release(composition);
        }
    }

    /**
     * AE-encoder preview: the playhead's <b>frame</b>, not a slice of screen time.
     *
     * <p>BBS mode ({@link #renderCached}) renders at the panel's resolution and at whatever moment
     * the display refresh lands on, because it is an overlay on a game frame. AE mode renders the
     * composition's own frame at the composition's own resolution and displays it scaled — which is
     * why the picture cannot drift from the export, and why scrubbing back to a frame is free once
     * the pre-render cache or the frame server has it.</p>
     *
     * <p>Effects and masks are always on here: an A/B toggle that changes the preview but not the
     * exported file is exactly the surprise this mode exists to remove.</p>
     */
    public static void renderFrameCached(CompProject project, CompDocument doc, float time, float alpha, float frameWidth, float frameHeight, int fit, float proxy, Batcher2D batcher)
    {
        if (project == null || doc == null || batcher == null || frameWidth <= 0F || frameHeight <= 0F)
        {
            return;
        }

        dev.bbsae.client.render.ReplayEntityMask.ensureCurrent(project, doc);

        int frame = CompFrameServer.frameOf(doc, time);
        float scale = Math.max(0.05F, Math.min(1F, proxy));

        /* Green-bar pre-render first: it is a disk cache of exactly these frames. */
        GlTexture precached = CompPrecache.texture(project, doc, frame);

        if (precached != null)
        {
            blitTexture(batcher, precached.id, doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        GlFramebuffer framebuffer = CompFrameServer.frame(project, doc, frame, false, scale);

        if (framebuffer != null)
        {
            blitTexture(batcher, framebuffer.color.id, doc, alpha, frameWidth, frameHeight, fit);
        }
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

        /* Adaptive resolution while a curve is being dragged: the graph is being evaluated on
         * every mouse move, so the preview trades a little sharpness for a stable frame rate and
         * restores it 150 ms after the mouse is released. */
        if (interactionPreview)
        {
            uniform *= INTERACTION_SCALE;
        }

        uniform = Math.max(0.2F, Math.min(1F, uniform));

        return Math.round(uniform * 16F) / 16F;
    }

    /** Extra factor applied to the preview while a curve/handle is being dragged. */
    private static final float INTERACTION_SCALE = 0.7F;
    private static boolean interactionPreview;

    public static void setInteractionPreview(boolean interacting)
    {
        interactionPreview = interacting;
    }

    private static GlFramebuffer renderToTexture(CompProject project, CompDocument doc, float time, boolean captureDepth, boolean renderEffects, boolean renderMasks, boolean preview, Matrix4f projection)
    {
        return renderToTexture(project, doc, time, captureDepth, renderEffects, renderMasks, preview, projection, 1F, false);
    }

    private static GlFramebuffer renderToTexture(CompProject project, CompDocument doc, float time, boolean captureDepth, boolean renderEffects, boolean renderMasks, boolean preview, Matrix4f projection, float scale)
    {
        return renderToTexture(project, doc, time, captureDepth, renderEffects, renderMasks, preview, projection, scale, false);
    }

    private static GlFramebuffer renderToTexture(CompProject project, CompDocument doc, float time, boolean captureDepth, boolean renderEffects, boolean renderMasks, boolean preview, Matrix4f projection, float scale, boolean transparentStage)
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
                composition = renderCompToFbo(project, doc, time, depth, projection, client.gameRenderer.getCamera(), new HashSet<>(), fboBatcher, 0, renderEffects, renderMasks, preview, scale, transparentStage);
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
        return renderOffscreen(project, doc, time, batcher, false);
    }

    /**
     * @param transparentStage true for alpha output modules: the frame keeps transparency instead
     *        of being flattened onto the document background.
     */
    public static GlFramebuffer renderOffscreen(CompProject project, CompDocument doc, float time, Batcher2D batcher, boolean transparentStage)
    {
        if (doc == null)
        {
            return null;
        }

        return renderFrame(project, doc, frameIndex(doc, time), transparentStage);
    }

    /** Quantised frame index for a time, clamped to the composition. */
    public static int frameIndex(CompDocument doc, float time)
    {
        if (doc == null)
        {
            return 0;
        }

        int frames = Math.max(1, Math.round(Math.max(0.01F, doc.duration) * Math.max(1F, doc.fps)));

        return Math.max(0, Math.min(frames - 1, Math.round(Math.max(0F, time) * doc.fps)));
    }

    /**
     * One composition frame, deterministically.
     *
     * <p>This is the only way a frame leaves the compositor: frame N is evaluated at N / fps, at
     * composition resolution, with editor chrome (empty-precomp placeholders, guide layers,
     * selection) suppressed. The preview's AE mode and the render queue both call it, so "what the
     * playhead shows" and "what the encoder writes" are the same function of (document, frame) --
     * the property that makes a compositor usable as an editor instead of a screen recorder.</p>
     *
     * @param transparentStage true for alpha output modules (the stage keeps its alpha).
     */
    public static GlFramebuffer renderFrame(CompProject project, CompDocument doc, int frame, boolean transparentStage)
    {
        return renderFrame(project, doc, frame, transparentStage, 1F);
    }

    /**
     * Same frame, rendered into a smaller raster.
     *
     * <p>The scale only exists for the AE preview's proxy mode (see {@code settings.encoderProxy});
     * an export always asks for {@code 1}. It is a parameter here rather than a separate path so a
     * proxy frame and a full frame can never disagree about <em>what</em> they show — only about
     * how many pixels they use to show it.</p>
     */
    public static GlFramebuffer renderFrame(CompProject project, CompDocument doc, int frame, boolean transparentStage, float scale)
    {
        if (project == null || doc == null)
        {
            return null;
        }

        float fps = Math.max(1F, doc.fps);
        int frames = Math.max(1, Math.round(Math.max(0.01F, doc.duration) * fps));
        int clamped = Math.max(0, Math.min(frames - 1, frame));
        float clampedScale = Math.max(0.05F, Math.min(1F, scale));

        try (GlStateGuard guard = new GlStateGuard())
        {
            CompShaders.init();
            exportDepth += 1;

            try
            {
                return renderToTexture(project, doc, clamped / fps, false, true, true, false, null, clampedScale, transparentStage);
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

        /* A cached frame has to be exactly the exported frame, and guide layers are editor-only.
         * Without this the green bar (and therefore the AE preview, which trusts it first) contained
         * 参考层 shapes that the encoder never wrote. */
        beginExport();

        try
        {
            return renderToTexture(project, doc, time, false, true, true, false, null, scale);
        }
        finally
        {
            endExport();
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
        return renderCompToFbo(project, comp, time, depth, projection, camera, stack, batcher, recursion, renderEffects, renderMasks, preview, scale, false);
    }

    /**
     * @param transparentStage clears the stage with alpha 0 instead of {@link CompDocument#background}.
     *        Used by the BBS-backdrop preview (the world must show through) and by alpha exports.
     */
    static GlFramebuffer renderCompToFbo(CompProject project, CompDocument comp, float time, SceneDepth depth, Matrix4f projection, Camera camera, Set<String> stack, Batcher2D batcher, int recursion, boolean renderEffects, boolean renderMasks, boolean preview, float scale, boolean transparentStage)
    {
        if (comp == null || comp.layers == null || comp.layers.isEmpty() || recursion > 8 || stack.contains(comp.id))
        {
            return null;
        }

        stack.add(comp.id);

        CompRenderContext context = new CompRenderContext(project, comp, time, projection, camera, depth);

        /* Nested comps (precomps) read these back when they recurse. */
        context.renderEffects = renderEffects;
        context.renderMasks = renderMasks;

        context.preview = preview;
        context.scale = Math.max(0.05F, Math.min(1F, scale));

        GlFramebuffer accumulation = FboPool.acquire(context.renderWidth(), context.renderHeight());
        int previousFramebuffer = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        accumulation.begin(stageColor(project, comp, preview, transparentStage));

        /* One shared solo rule (see CompDocument.hasSolo): a camera never counts as a solo layer. */
        boolean solo = comp.hasSolo();
        boolean completed = false;

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
                LayerRender rendered = renderLayerContent(layer, context, model, layerProjection, stack, batcher, renderEffects);
                GlFramebuffer content = rendered == null ? null : rendered.framebuffer;

                if (content == null)
                {
                    continue;
                }

                boolean effectsBaked = rendered.effectsBaked;

                boolean is2D = !CompScene.usePerspective(context, layer);

                if (is2D && renderMasks && layer.masks != null && !layer.masks.isEmpty())
                {
                    /* Rasterize at the RENDER size, not the composition size. The mask texture is
                     * sampled with the content FBO's own UVs, so a full-resolution mask carries no
                     * extra information — only cost: an animated mask re-rasterized a
                     * 1920x1080 AWT path plus a box blur per frame, which is the "编辑蒙版时预览
                     * 卡顿" complaint. The proxy preview now pays proxy pixels; the 3D bake path
                     * already worked this way. */
                    int maskWidth = context.renderWidth();
                    int maskHeight = context.renderHeight();
                    float maskScale = Math.max(0.0001F, maskWidth / (float) Math.max(1, comp.width));
                    Matrix4f maskModel = new Matrix4f(model).scale(maskScale, maskScale, 1F);
                    MaskCache.MaskTexture mask = MaskCache.get(layer, maskWidth, maskHeight, maskModel, context.frame, maskScale);

                    if (mask != null)
                    {
                        GlFramebuffer masked = PassRenderer.mask(content, mask.texture.id, 0);

                        FboPool.release(content);
                        content = masked;
                    }
                }

                Matrix4f effectSpace = effectSpace(context, layer, model);

                /* The layer-space raster already contains the non-depth effect stack:
                 * CompLayerSourceCache.applyEffects runs it in the layer's own space before the
                 * raster is projected. That is what keeps a gradient glued to a 3D plane. Running
                 * the loop for those effects again (0.20.0 did) applied every effect twice and
                 * overwrote the layer-space gradient with a composition-space one. Only depth
                 * effects still need the projected image and the world depth buffer. */
                java.util.List<CompEffect> effects = layer.effects == null
                    ? java.util.Collections.emptyList()
                    : layer.effects;
                /* Effects that need the projected frame (depth, shake, backdrop, mesh) split the
                 * stack: everything before the first such effect is baked into the layer raster,
                 * everything from it onward runs here, in the author's order. Previously only the
                 * projected effects themselves ran here, which reordered them after later baked
                 * effects (a mesh followed by a blur ended up blurred before the mesh). */
                int projectedStart = effectsBaked ? firstProjectedEffect(effects) : 0;
                int effectIndex = 0;

                /* Backdrop-reading effects (displacement maps, glass) sample what is already
                 * composited below this layer. Keep the previous value so nested precomps and the
                 * adjustment pass can restore their own. */
                GlFramebuffer previousBackdrop = context.backdrop;

                context.backdrop = accumulation;

                try
                {
                for (CompEffect effect : effects)
                {
                    if (!renderEffects || effect == null || !effect.enabled)
                    {
                        continue;
                    }

                    /* Depth effects cannot run in the layer-local bake: they need the world depth
                     * buffer and the composition-space rectangle. They are therefore applied after
                     * the 3D quad has been projected, while every non-depth effect was already
                     * baked. Skipping the whole loop when effectsBaked was true is why depth_fog /
                     * depth_dof silently did nothing on 3D layers. */
                    boolean depthEffect = effect.type != null && effect.type.startsWith("depth_");

                    int index = effectIndex++;

                    if (effectsBaked && index < projectedStart)
                    {
                        continue;
                    }

                    CompEffect resolved = layer.resolveEffect(effect, context.frame);
                    GlFramebuffer processed = depthEffect
                        ? EffectRenderer.apply(layer, resolved, content, context, EffectSpace.identity(context))
                        : EffectRenderer.apply(layer, resolved, content, context, effectSpace);

                    if (processed != content)
                    {
                        FboPool.release(content);
                        content = processed;
                    }
                }
                }
                finally
                {
                    context.backdrop = previousBackdrop;
                }

                /* Styles were baked into the layer-space raster together with the effects; running
                 * them again here would double a shadow/glow and apply it in composition space
                 * instead of on the plane. Only the fallback (cache unavailable) styles here. */
                if (renderEffects && !effectsBaked)
                {
                    content = applyStyles(layer, content, context, effectSpace);
                }

                /* Track matte last, for 2D and 3D alike.
                 *
                 * <p>The matte clips the layer's finished pixels, which is what a track matte means
                 * in AE; applying it before the effects made a 2D glow bleed outside the matte while
                 * the same setup clipped it on a 3D layer. The matte layer's own masks and effects
                 * are applied to its content, so "masked shape used as matte" behaves identically in
                 * both paths.</p> */
                if (renderMasks && layer.trackMatteEnabled && layer.trackMatteId != null && !layer.trackMatteId.isEmpty())
                {
                    CompLayer matte = comp.getLayer(layer.trackMatteId);

                    if (matte != null && matte != layer && hasPixels(matte))
                    {
                        Matrix4f matteModel = CompScene.model(context, matte);
                        LayerRender matteRender = renderLayerContent(matte, context, matteModel, CompScene.projection(context, matte), stack, batcher, renderEffects);
                        GlFramebuffer matteFbo = matteRender == null ? null : matteRender.framebuffer;

                        if (matteFbo != null)
                        {
                            /* The matte's own masks belong to its own pixels. Same render-size
                             * rasterization as the layer path above. */
                            if (matte.masks != null && !matte.masks.isEmpty())
                            {
                                int matteMaskWidth = context.renderWidth();
                                int matteMaskHeight = context.renderHeight();
                                float matteMaskScale = Math.max(0.0001F, matteMaskWidth / (float) Math.max(1, comp.width));
                                Matrix4f matteMaskModel = new Matrix4f(matteModel).scale(matteMaskScale, matteMaskScale, 1F);
                                MaskCache.MaskTexture matteMask = MaskCache.get(matte, matteMaskWidth, matteMaskHeight, matteMaskModel, context.frame, matteMaskScale);

                                if (matteMask != null)
                                {
                                    GlFramebuffer maskedMatte = PassRenderer.mask(matteFbo, matteMask.texture.id, 0);

                                    FboPool.release(matteFbo);
                                    matteFbo = maskedMatte;
                                }
                            }

                            /* A 2D matte over a 3D target has to be projected too, otherwise the
                             * matte lives in camera-view space while the target content is already
                             * in perspective composition space. */
                            if (!is2D && CompScene.spatial(context) && !CompScene.usePerspective(context, matte))
                            {
                                GlFramebuffer projectedMatte = projectPlane(matteFbo, matte, context, batcher);

                                FboPool.release(matteFbo);
                                matteFbo = projectedMatte;
                            }

                            int channel = layer.trackMatteMode == CompLayer.MATTE_LUMA ? 1 : layer.trackMatteMode == CompLayer.MATTE_ALPHA_INVERTED ? 2 : layer.trackMatteMode == CompLayer.MATTE_LUMA_INVERTED ? 3 : 0;
                            GlFramebuffer matted = PassRenderer.mask(content, matteFbo.color.id, channel);

                            FboPool.release(content);
                            FboPool.release(matteFbo);
                            content = matted;
                        }
                    }
                }

                /* A flat layer under a tilted camera: the finished comp-space image is projected
                 * onto the layer's z plane. Doing it here — after masks, effects and styles — is
                 * what keeps those passes exact: they never see the perspective, they just draw
                 * the layer where it lives. */
                if (CompScene.spatial(context) && !CompScene.usePerspective(context, layer))
                {
                    GlFramebuffer projected = projectPlane(content, layer, context, batcher);

                    FboPool.release(content);
                    content = projected;
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
            completed = true;
        }
        finally
        {
            stack.remove(comp.id);

            /* A malformed project that throws mid-graph must not leak the accumulation target:
             * the exception propagates and nobody else knows this framebuffer exists. */
            if (!completed)
            {
                FboPool.release(accumulation);
            }

            /* A precomp is rendered while its parent's layer target is bound. Restore that
             * framebuffer, viewport and projection so the caller can continue drawing into it. */
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
        }

        return accumulation;
    }

    /**
     * Colour the composition stage is cleared with.
     *
     * <p>Three different questions, one answer each:</p>
     * <ul>
     *   <li><b>BBS backdrop preview</b> — the world frame is drawn <em>behind</em> the composition,
     *       so the stage must be transparent or the backdrop is invisible. This is why making the
     *       document background opaque cannot simply be the whole story.</li>
     *   <li><b>Alpha export</b> — the file must carry transparency, so the stage is cleared to 0
     *       regardless of the document colour.</li>
     *   <li><b>Everything else</b> — the document's own background, which is an opaque colour by
     *       default (a transparent stage made the preview read as "the compositor is empty").</li>
     * </ul>
     */
    private static int stageColor(CompProject project, CompDocument comp, boolean preview, boolean transparentStage)
    {
        if (transparentStage)
        {
            return 0;
        }

        if (preview && project != null && project.settings != null && project.settings.previewBackground == 1)
        {
            return 0;
        }

        return comp == null ? 0xFF000000 : comp.background;
    }

    /**
     * Draws a finished comp-space layer image onto its z plane, seen through the camera.
     *
     * <p>The image already contains everything the layer is (content, mask, effects, styles) at its
     * comp position, so all this pass has to do is put that plane into the camera's view. A single
     * textured quad is exact: the GPU interpolates UVs perspective-correctly, and the plane really
     * is flat.</p>
     */
    private static GlFramebuffer projectPlane(GlFramebuffer source, CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        CompCamera camera = CompScene.camera(context);
        GlFramebuffer target = FboPool.acquire(context.renderWidth(), context.renderHeight());
        MatrixStack stack = batcher.getContext().getMatrices();
        float z = layer.scalarAt("z", layer.z, context.frame);

        target.begin();
        BufferRenderer.resetCurrentVertexBuffer();

        stack.push();
        stack.peek().getPositionMatrix().set(new Matrix4f(camera.view).translate(0F, 0F, z));
        RenderSystem.setProjectionMatrix(camera.projection, VertexSorter.BY_Z);

        try
        {
            /* Subdivide the plane instead of drawing one giant quad. A single quad whose corner
             * falls behind the camera is clipped as a whole — the layer vanished when the camera
             * tilted near its edge. Small cells clip independently, so the visible part survives.
             * The UVs are the same bottom-up mapping the old single quad used. */
            int cells = 16;
            float cw = context.width / (float) cells;
            float ch = context.height / (float) cells;

            for (int j = 0; j < cells; j++)
            {
                for (int i = 0; i < cells; i++)
                {
                    float x0 = i * cw;
                    float y0 = j * ch;
                    float x1 = x0 + cw;
                    float y1 = y0 + ch;
                    /* The quad lives in composition pixels; the source texture may be smaller
                     * (proxy scale), so the UVs are normalised through the comp size. */
                    float u0 = x0 / context.width * source.width;
                    float u1 = x1 / context.width * source.width;
                    float vTop = (1F - y0 / context.height) * source.height;
                    float vBottom = (1F - y1 / context.height) * source.height;

                    batcher.texturedBox(source.color.id, 0xFFFFFFFF,
                        x0, y0, cw, ch,
                        u0, vTop, u1, vBottom,
                        source.width, source.height);
                }
            }

            batcher.flush();
        }
        finally
        {
            stack.pop();
            RenderSystem.setProjectionMatrix(GlStateGuard.ortho(context.width, context.height), VertexSorter.BY_Z);
            BufferRenderer.resetCurrentVertexBuffer();
        }

        /* The source is straight alpha; drawing it into a transparent target with SRC_ALPHA makes
         * the result premultiplied. Every consumer (composite, track matte, effects after the
         * projection) expects straight alpha, so convert exactly once — the same correction
         * renderBaked3D already does. Without it, a projected 2D layer gets dark fringes and a
         * projected luma matte reads the darkened RGB. */
        GlFramebuffer straightened = PassRenderer.unpremultiply(target);

        if (straightened != target)
        {
            FboPool.release(target);
        }

        return straightened;
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
    static List<CompLayer> orderLayers(CompDocument comp, CompRenderContext context)
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

            /* Under a spatial camera a flat layer is a plane in the same 3D space as the 3D ones,
             * so it must be sorted with them; otherwise a layer the tilt pushes behind keeps
             * painting over one in front. Ties (all z = 0) keep the stack order. */
            boolean projected = layer.threeD || CompScene.spatial(context);

            if (projected && !"camera".equals(layer.type) && !"adjustment".equals(layer.type))
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
        three.sort((a, b) -> Float.compare(CompScene.planeDepth(context, a), CompScene.planeDepth(context, b)));

        for (int i = 0; i < slots.size(); i++)
        {
            order.set(slots.get(i), three.get(i));
        }
    }

    /**
     * A layer's content for one pass, plus whether its non-depth effects, styles and masks are
     * already in it.
     *
     * <p>The "baked" bit cannot be recomputed by the caller from {@code layer.threeD}: a 3D layer
     * whose source raster could not be built falls back to the direct path, and then its effects
     * still have to run after projection. Returning the fact with the pixels keeps the two paths
     * from disagreeing.</p>
     */
    private static final class LayerRender
    {
        final GlFramebuffer framebuffer;
        final boolean effectsBaked;

        LayerRender(GlFramebuffer framebuffer, boolean effectsBaked)
        {
            this.framebuffer = framebuffer;
            this.effectsBaked = effectsBaked;
        }
    }

    private static LayerRender renderLayerContent(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f layerProjection, Set<String> stack, Batcher2D batcher, boolean renderEffects)
    {
        /* A 3D layer with effects is drawn from its layer-space raster.
         *
         * <p>Effects are a property of the layer's pixels, not of the composition: a gradient must
         * stay glued to the layer when the camera moves. The composition pipeline used to apply
         * them in composition space (because it rasterized there), which made every 3D layer's
         * gradient slide across the plane while the shot moved. The viewport already bakes them in
         * layer space; this path makes the exported frame agree with it.</p>
         */
        /* Masks and layer styles are not "effects": a 3D layer needs the baked path whenever it
         * has any pixel operation. The 效果/蒙版 A/B switches are honoured *inside* the bake, so
         * turning 效果 off no longer silently removes the layer's masks and styles. */
        GlFramebuffer baked = renderBaked3D(layer, context, model, layerProjection, batcher);

        if (baked != null)
        {
            /* The source cache already ran the non-depth effects in layer space. */
            return new LayerRender(baked, true);
        }

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

        return new LayerRender(target, false);
    }

    /** Cells per axis when a baked 3D layer is drawn as a projected quad. */
    private static final int BAKED_CELLS = 16;

    /**
     * Draws a 3D layer from its layer-space raster (content + masks + effects + styles), or returns
     * null when none of those pixel operations exist (the direct path is cheaper and already
     * correct).
     */
    private static GlFramebuffer renderBaked3D(CompLayer layer, CompRenderContext context, Matrix4f model,
                                               Matrix4f layerProjection, Batcher2D batcher)
    {
        if (!layer.threeD || "camera".equals(layer.type) || "adjustment".equals(layer.type))
        {
            return null;
        }

        boolean masked = layer.masks != null && !layer.masks.isEmpty();

        if (!hasEnabledEffects(layer) && !hasEnabledStyles(layer) && !masked)
        {
            return null;
        }

        CompLayerSourceCache.Entry source = CompLayerSourceCache.get(layer, context, batcher);

        if (source == null)
        {
            return null;
        }

        GlFramebuffer target = FboPool.acquire(context.renderWidth(), context.renderHeight());
        MatrixStack stack = batcher.getContext().getMatrices();

        target.begin();
        BufferRenderer.resetCurrentVertexBuffer();

        stack.push();
        stack.peek().getPositionMatrix().set(model);
        RenderSystem.setProjectionMatrix(layerProjection, VertexSorter.BY_Z);

        try
        {
            float x0 = source.localX;
            float y0 = source.localY;
            float w = Math.max(1F, source.localWidth);
            float h = Math.max(1F, source.localHeight);
            float texW = source.texture.width;
            float texH = source.texture.height;
            float stepX = w / BAKED_CELLS;
            float stepY = h / BAKED_CELLS;

            for (int j = 0; j < BAKED_CELLS; j++)
            {
                for (int i = 0; i < BAKED_CELLS; i++)
                {
                    float lx0 = x0 + i * stepX;
                    float ly0 = y0 + j * stepY;
                    float lx1 = lx0 + stepX;
                    float ly1 = ly0 + stepY;

                    /* straight alpha raster: u across, v up (the raster was drawn y-down).
                     *
                     * texturedBox takes the two UV *corners* in the same order as the quad corners
                     * (top-left first): (u, v) is the top-left, (uw, uh) the bottom-right. Passing
                     * them the other way round flipped every baked 3D layer vertically — invisible
                     * on a horizontal gradient, but a vertical/diagonal gradient came out with its
                     * colours reversed and any text upside down. */
                    float u0 = (lx0 - x0) / w;
                    float u1 = (lx1 - x0) / w;
                    float vTop = 1F - (ly0 - y0) / h;
                    float vBottom = 1F - (ly1 - y0) / h;

                    batcher.texturedBox(source.texture.color.id, 0xFFFFFFFF,
                        lx0, ly0, stepX, stepY,
                        u0 * texW, vTop * texH, u1 * texW, vBottom * texH,
                        (int) texW, (int) texH);
                }
            }

            batcher.flush();
        }
        finally
        {
            stack.pop();
            RenderSystem.setProjectionMatrix(GlStateGuard.ortho(context.width, context.height), VertexSorter.BY_Z);
            BufferRenderer.resetCurrentVertexBuffer();
        }

        /* Straight alpha content in a transparent target: same conversion every other layer does. */
        GlFramebuffer straightened = PassRenderer.unpremultiply(target);

        if (straightened != target)
        {
            FboPool.release(target);
        }

        return straightened;
    }

    private static GlFramebuffer applyAdjustment(CompLayer layer, GlFramebuffer original, CompRenderContext context, Set<String> stack, Batcher2D batcher)
    {
        if (layer.effects == null || layer.effects.isEmpty())
        {
            return original;
        }

        GlFramebuffer processed = original;
        boolean changed = false;
        Matrix4f space = new Matrix4f();
        GlFramebuffer previousBackdrop = context.backdrop;

        context.backdrop = original;

        try
        {
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
        }
        finally
        {
            context.backdrop = previousBackdrop;
        }

        if (!changed)
        {
            return original;
        }

        float opacity = opacityAt(layer, context);

        if (layer.masks != null && !layer.masks.isEmpty())
        {
            int maskWidth = context.renderWidth();
            int maskHeight = context.renderHeight();
            float maskScale = Math.max(0.0001F, maskWidth / (float) Math.max(1, context.width));
            Matrix4f maskModel = new Matrix4f(CompScene.world(context, layer)).scale(maskScale, maskScale, 1F);
            MaskCache.MaskTexture mask = MaskCache.get(layer, maskWidth, maskHeight, maskModel, context.frame, maskScale);

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

    /**
     * Whether an effect still has to run on the projected content.
     *
     * <p>3D layers with effects are rasterized by {@link CompLayerSourceCache}, which runs the
     * non-depth stack in <b>layer</b> space; the projected image already contains it. Running the
     * same effect again here applied every 3D effect twice (2x blur, 2x brightness, a composition
     * gradient overwriting the layer-anchored one). Depth effects cannot be baked (they need the
     * world depth attachment), so those still run after projection.</p>
     *
     * <p>A 3D layer whose raster could not be built reports {@code effectsBaked == false}; its
     * effects then run here as the fallback, exactly once.</p>
     */
    static boolean needsCompositionEffect(boolean effectsBaked, CompEffect effect)
    {
        if (effect == null)
        {
            return false;
        }

        return !effectsBaked || requiresProjectedPass(effect.type);
    }

    /**
     * True when the effect can only run on the projected frame: depth effects read the world depth
     * buffer, shake samples the composition, backdrop effects need the accumulated comp and mesh
     * effects are projected with the layer's world matrix (baking would apply it twice).
     */
    static boolean requiresProjectedPass(String type)
    {
        if (type == null)
        {
            return false;
        }

        return type.startsWith("depth_") || "shake".equals(type)
            || dev.bbsae.comp.AmEffectLibrary.usesComp(type) || MeshRenderer.supports(type);
    }

    /** Index of the first effect that must run after projection, or the list size if none. */
    static int firstProjectedEffect(java.util.List<CompEffect> effects)
    {
        if (effects == null)
        {
            return 0;
        }

        for (int i = 0; i < effects.size(); i++)
        {
            CompEffect effect = effects.get(i);

            if (effect != null && effect.enabled && requiresProjectedPass(effect.type))
            {
                return i;
            }
        }

        return effects.size();
    }

    /**
     * Camera / null / adjustment layers produce no pixels of their own.
     *
     * <p>Using one as a track matte multiplied the target by a fully transparent raster, so the
     * layer simply vanished with no message. The matte picker still lists them, so the renderer has
     * to refuse them as well.</p>
     */
    private static boolean hasPixels(CompLayer layer)
    {
        return layer != null
            && !"camera".equals(layer.type)
            && !"null".equals(layer.type)
            && !"adjustment".equals(layer.type);
    }

    private static boolean hasEnabledEffects(CompLayer layer)
    {
        if (layer.effects == null)
        {
            return false;
        }

        for (CompEffect effect : layer.effects)
        {
            if (effect != null && effect.enabled)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean hasEnabledStyles(CompLayer layer)
    {
        if (layer.styles == null)
        {
            return false;
        }

        for (dev.bbsae.comp.CompStyle style : layer.styles)
        {
            if (style != null && style.enabled)
            {
                return true;
            }
        }

        return false;
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
        LayerContentRenderer.clearFonts();
        FontCoverage.clear();
        /* The viewport's source textures own FBOs from the pool; return them before the pool is
         * wiped, otherwise a later acquire can hand out a deleted texture. */
        CompLayerSourceCache.clear();
        CompViewportRenderer.clearCaches();
        AmEffectRenderer.delete();
        MeshRenderer.delete();
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
        return renderForExport(project, comp, time, batcher, false);
    }

    public static GlFramebuffer renderForExport(CompProject project, CompDocument comp, float time, Batcher2D batcher, boolean alpha)
    {
        return renderFrame(project, comp, frameIndex(comp, time), alpha);
    }
}
