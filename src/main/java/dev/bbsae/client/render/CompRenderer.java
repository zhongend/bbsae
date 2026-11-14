package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.client.ui.CompPrefs;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompStyle;
import mchorse.bbs_mod.client.BBSRendering;
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
        GlFramebuffer composition = renderOffscreen(() ->
            renderToTexture(project, doc, time, captureDepth, renderEffects, renderMasks, preview, projection));

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
         * masked yet (the first frame after opening a project or changing the selection). The
         * replay preview filters the live-world layer out, so capturing the world actor mask there
         * would only waste a world pass (and can never be drawn). */
        if (!CompPreviewFilter.active())
        {
            dev.bbsae.client.render.ReplayEntityMask.ensureCurrent(project, doc);
        }

        float fps = Math.max(1F, doc.fps);
        /* Clamped like every other frame lookup: past the composition's end the green bar and the
         * AE preview must agree instead of the cache silently missing. */
        int frameIndex = frameIndex(doc, time);
        float scale = previewScale(doc, frameWidth, frameHeight, fit);
        boolean filtered = CompPreviewFilter.active();

        /* 1. Green frames: the whole point of the pre-render cache. Only when the A/B switches are
         * on, because a cached frame always contains effects and masks; otherwise the 效果/蒙版
         * toggles would do nothing while the playhead sits on a green frame. */
        GlTexture cached = (renderEffects && renderMasks && !filtered) ? CompPrecache.texture(project, doc, frameIndex) : null;

        /* The cached frame is only accepted when it was rendered at least at the requested preview
         * scale (same rule as the AE path). The pre-render is capped at 1280x720; a larger panel
         * used to upscale that frame and look permanently soft even though a real render would have
         * been sharper. */
        if (cached != null && CompPrecache.cacheScale(doc) + 0.0001F >= scale)
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
        if (!filtered && !liveWorld && CompRenderCache.reuseForTime(time, frameIndex))
        {
            blit(batcher, CompRenderCache.get(), doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        long signature = CompRenderCache.signature(project, doc, time, renderEffects, renderMasks, fit, Math.round(frameWidth), Math.round(frameHeight), cameraOverlays) ^ ((long) Math.round(scale * 1000F) << 48);

        if (!filtered && !cameraOverlays && !liveWorld && CompRenderCache.matches(signature, doc, doc.width, doc.height))
        {
            blit(batcher, CompRenderCache.get(), doc, alpha, frameWidth, frameHeight, fit);

            return;
        }

        boolean previousOpaque = previewOpaqueStage;

        previewOpaqueStage = cameraOverlays;

        GlFramebuffer composition;

        try
        {
            composition = renderOffscreen(() ->
                renderToTexture(project, doc, time, false, renderEffects, renderMasks, true, null, scale));
        }
        finally
        {
            previewOpaqueStage = previousOpaque;
        }

        if (composition == null)
        {
            return;
        }

        /* The cache owns this framebuffer now; releasing it here would hand a live texture back to
         * the pool and the next layer would draw over the cached frame. */
        if (!filtered && !liveWorld)
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

        /* Green-bar pre-render first: it is a disk cache of exactly these frames. Only use it when
         * it was rendered at least at the requested proxy scale: the pre-render is capped at
         * 1280x720, and an AE preview that asked for 1:1 used to be served the 720p frame and
         * upscale it. Falling through re-renders at the real scale. */
        GlTexture precached = CompPrecache.texture(project, doc, frame);

        if (precached != null && CompPrecache.cacheScale(doc) + 0.0001F >= scale)
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
        return previewScale(doc, frameWidth, frameHeight, fit, deviceScale());
    }

    /**
     * Device-pixel-aware preview scale. {@code frameWidth/Height} are GUI units; one GUI pixel is
     * {@code deviceScale()} framebuffer pixels. Sizing the composition FBO from GUI units made the
     * preview undersample by 2× on every GUI scale-2 setup (the default), while original BBS
     * supersamples its film preview by 2×. The old pure 4-arg form is kept for tests/callers that
     * explicitly want a 1:1 GUI reference.
     */
    public static float previewScale(CompDocument doc, float frameWidth, float frameHeight, int fit, float deviceScale)
    {
        float dw = Math.max(1F, doc.width);
        float dh = Math.max(1F, doc.height);
        float device = Math.max(1F, Math.min(4F, deviceScale));
        float sx = frameWidth * device / dw;
        float sy = frameHeight * device / dh;
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

    /** MC's GUI scale (device pixels per GUI pixel); 1 when it cannot be read (headless/tests). */
    private static float deviceScale()
    {
        try
        {
            float scale = mchorse.bbs_mod.BBSModClient.getGUIScale();

            return scale <= 0.01F ? 1F : Math.min(4F, scale);
        }
        catch (RuntimeException | LinkageError e)
        {
            return 1F;
        }
    }

    /** Extra factor applied to the preview while a curve/handle is being dragged. */
    private static final float INTERACTION_SCALE = 0.7F;
    /** Camera-preview frames use an opaque stage: a transparent stage lets the previous frame/
     * world show through and reads as ghosting. */
    private static boolean previewOpaqueStage;
    private static boolean interactionPreview;

    public static void setInteractionPreview(boolean interacting)
    {
        interactionPreview = interacting;
    }

    /**
     * Runs one composition render inside BBS' offscreen contract.
     *
     * <p>With Iris installed, {@code BBSRendering.renderOffscreen} is what tells the shader pack
     * that this is not a world pass (it saves/restores the shadow state and the render-target
     * listener). Rendering our FBOs without it meant a pack overriding shaders could substitute its
     * own program/vertex format into the composition graph; the UI would then show wrong colours or
     * fail to draw. BBSRendering is a no-op when Iris is absent.</p>
     */
    private static boolean offscreenFailureLogged;

    private static GlFramebuffer renderOffscreen(java.util.function.Supplier<GlFramebuffer> action)
    {
        GlFramebuffer[] result = new GlFramebuffer[1];

        try
        {
            BBSRendering.renderOffscreen(() -> result[0] = action.get());
        }
        catch (RuntimeException | LinkageError e)
        {
            /* A shader-pack pipeline that cannot enter its offscreen contract must not take the
             * compositor down. Fall back to the plain pass; the frame is still correct vanilla,
             * it simply may not receive the pack's post-processing. */
            if (!offscreenFailureLogged)
            {
                offscreenFailureLogged = true;
                BbsAeAddon.LOGGER.warn("BBS offscreen contract unavailable; rendering the composition directly", e);
            }

            if (result[0] == null)
            {
                result[0] = action.get();
            }
        }

        return result[0];
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
             * degraded to a no-op. The bridge copies the world depth at WorldRenderEvents.LAST.
             *
             * AE mode (renderFrame / renderForExport) used to pass preview=false and therefore
             * never looked at the bridge at all: depth_fog/dof silently did nothing in the AE
             * preview and the encoder while the BBS-overlay export (which runs inside the live
             * world) worked. A non-capturing frame now reuses the bridge copy; an explicit
             * {@code captureDepth} request still takes the fresh capture it asks for, and a frame
             * with neither reports the visible degradation instead of failing silently. */
            SceneDepth bridged = dev.bbsae.client.overlay.BbsPreviewBridge.depth();

            if (captureDepth)
            {
                /* Explicit request (overlay/legacy path, right after the world was drawn): the
                 * freshly captured depth is the authoritative copy for this frame. */
                depth = ensureDepth();
                depth.capture();
                noteDepthAvailable();
            }
            else if (bridged != null && bridged.isAvailable())
            {
                depth = bridged;
                noteDepthAvailable();
            }
            else
            {
                noteDepthMissing(project, doc, preview);
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

    /**
     * True when this composition needs the world depth buffer (enabled depth effects). Public so
     * the replay editor's camera preview can request the depth copy before baking a frame; the
     * package-private visibility is kept for the degradation regression test.
     */
    public static boolean needsWorldDepth(CompProject project, CompDocument doc)
    {
        return project != null && doc != null && doc.layers != null && needsDepth(project, doc);
    }

    /* --- depth degradation status (AE path) ------------------------------
     * depth_fog / depth_dof need the live world depth. The deterministic AE frame path can only
     * see the copy BbsPreviewBridge took during the last rendered world frame; when that copy does
     * not exist (e.g. exporting without ever opening the compositor preview), the effects cannot
     * run. Silently doing nothing was the audit finding: BBS-overlay exports worked, AE exports
     * did not, with no hint. The message below is the visible degradation the UI can surface. */
    private static boolean depthDegraded;
    private static String depthDegradedMessage;
    private static boolean depthDegradedLogged;

    /** True when the last AE frame needed world depth but could not get it. */
    public static boolean isDepthEffectsDegraded()
    {
        return depthDegraded;
    }

    /** User-visible explanation for the degraded depth effects, or null when they are fine. */
    public static String depthEffectsDegradedMessage()
    {
        return depthDegradedMessage;
    }

    /** Called by the editor when it (re)enters the live preview, which captures a fresh depth copy. */
    public static void resetDepthEffectsStatus()
    {
        depthDegraded = false;
        depthDegradedMessage = null;
        depthDegradedLogged = false;
    }

    /**
     * Drops every frame cached under the old per-pixel-depth mode. The render gate reads the mode
     * every frame, but a cache hit never reaches the gate — without this, flipping the setting
     * while the playhead is parked kept showing the previous mode's frame.
     */
    public static void invalidateDepthModeCaches()
    {
        CompRenderCache.clear();
        CompFrameServer.invalidate();
        CompPrecache.clearAll();
    }

    private static void noteDepthAvailable()
    {
        depthDegraded = false;
        depthDegradedMessage = null;
    }

    private static void noteDepthMissing(CompProject project, CompDocument doc, boolean preview)
    {
        if (!needsDepth(project, doc))
        {
            return;
        }

        depthDegraded = true;
        depthDegradedMessage = "深度效果（depth_*）已降级：AE 模式没有可用的世界深度。"
            + "请先在合成器预览里播放一帧（BBS 模式会采集世界深度），或改用 BBS 叠加导出。";

        if (!depthDegradedLogged)
        {
            depthDegradedLogged = true;
            BbsAeAddon.LOGGER.warn("AE frame path has no world depth copy; depth_* effects are disabled "
                + "(comp={}, preview={}). Open the compositor preview once or use the BBS-overlay encoder.",
                doc.id, preview);
        }
    }

    /**
     * Render pixels per layer pixel for a projected 3D mask: the projected diagonal of the layer's
     * content rectangle at the current camera, over the rectangle's own diagonal. Feather and
     * expansion are authored in layer pixels and are scaled by this before rasterization, exactly
     * like the affine proxy path's {@code maskScale}.
     */
    private static float projectedMaskScale(CompRenderContext context, CompLayer layer, CompCamera camera)
    {
        float width = Math.max(1F, layer.width);
        float height = Math.max(1F, layer.height);
        float x = -width * layer.anchorX;
        float y = -height * layer.anchorY;
        Matrix4f world = CompScene.world(context, layer);
        Vector3f a = new Vector3f(x, y, 0F);
        Vector3f b = new Vector3f(x + width, y + height, 0F);

        world.transformPosition(a);
        world.transformPosition(b);

        Vector2f projectedA = camera.project(a, context.width, context.height);
        Vector2f projectedB = camera.project(b, context.width, context.height);

        if (projectedA == null || projectedB == null)
        {
            return 1F;
        }

        float renderScaleX = context.renderWidth() / (float) Math.max(1, context.width);
        float renderScaleY = context.renderHeight() / (float) Math.max(1, context.height);
        float dx = (projectedB.x - projectedA.x) * renderScaleX;
        float dy = (projectedB.y - projectedA.y) * renderScaleY;
        float projectedDiagonal = (float) Math.sqrt(dx * dx + dy * dy);
        float layerDiagonal = (float) Math.sqrt(width * width + height * height);

        return Math.max(0.01F, projectedDiagonal / Math.max(0.0001F, layerDiagonal));
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
                return renderOffscreen(() ->
                    renderToTexture(project, doc, clamped / fps, false, true, true, false, null, clampedScale, transparentStage));
            }
            finally
            {
                exportDepth -= 1;
            }
        }
    }

    /**
     * Same frame, rendered through an explicit camera. Used by the replay editor's free/orbit
     * views: the composition graph (textures, masks, effects, BBS preview, models) is the camera
     * view's graph, only the view matrix differs.
     */
    public static GlFramebuffer renderFrameWithCamera(CompProject project, CompDocument doc, int frame,
                                                      boolean transparentStage, float scale, CompCamera camera)
    {
        CompScene.pushCamera(camera);

        try
        {
            return renderFrame(project, doc, frame, transparentStage, scale);
        }
        finally
        {
            CompScene.popCamera();
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

        return renderForCache(project, doc, time, scale);
    }

    /**
     * Pre-render cache frame at an explicit scale.
     *
     * <p>The scale is passed straight to the graph (not re-derived from a requested pixel size):
     * the pre-render cache used to compute a size by rounding each axis, the renderer then derived
     * a single scale back from that size, and odd/proxy rasters could disagree by a pixel — the
     * cached texture and the rendered FBO then had different dimensions. Both sides now use
     * {@link #renderSizeFor} so the dimensions are the same formula.</p>
     */
    public static GlFramebuffer renderForCache(CompProject project, CompDocument doc, float time, float scale)
    {
        if (project == null || doc == null)
        {
            return null;
        }

        /* A cached frame has to be exactly the exported frame, and guide layers are editor-only.
         * Without this the green bar (and therefore the AE preview, which trusts it first) contained
         * 参考层 shapes that the encoder never wrote. */
        beginExport();

        try
        {
            return renderOffscreen(() ->
                renderToTexture(project, doc, time, false, true, true, false, null,
                    Math.max(0.05F, Math.min(1F, scale)), previewStageTransparent(project)));
        }
        finally
        {
            endExport();
        }
    }

    /** Render-target size the graph will use for {@code scale}; one formula shared by all caches. */
    public static int[] renderSizeFor(CompDocument doc, float scale)
    {
        float clamped = Math.max(0.05F, Math.min(1F, scale));

        return new int[]
        {
            Math.max(1, Math.round((doc == null ? 1920 : doc.width) * clamped)),
            Math.max(1, Math.round((doc == null ? 1080 : doc.height) * clamped)),
        };
    }

    /**
     * True when the BBS-mode preview shows the live world behind the composition (transparent
     * stage). The pre-render cache has to bake the same stage or the green frame hides the world
     * that the live preview shows. AE mode keeps the encoder-exact opaque/alpha stage.
     */
    static boolean previewStageTransparent(CompProject project)
    {
        return project != null
            && project.settings != null
            && project.settings.previewBackground == 1
            && !CompProject.ENCODER_AE.equals(project.settings.encoder);
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

    /* --- per-pixel depth switch: OFF / ON / AUTO -------------------------
     * CompPrefs.perPixelDepthMode is the authoritative switch:
     *   0 = off, 1 = on, 2 = auto.
     * The legacy perPixelDepth boolean stays readable as a migration path: with mode 0 a true
     * boolean means ON, so preference files written before the tri-state existed keep working.
     *
     * AUTO only enables the path when whole-layer painter order cannot represent the frame: at
     * least two perspective layers whose projected bounds overlap and whose planes are not
     * parallel. Parallel planes (the common AE 3D frame, including the ____bug project) are
     * already exact with painter order, so AUTO keeps them on the cheap path instead of paying
     * three extra full-screen passes per 3D layer for no visible change.
     *
     * -Dbbsae.debugForceDepthPlane still forces the path ON for any mode; -Dbbsae.debugNoDepthPlane
     * remains the per-layer kill switch.
     */
    static final int DEPTH_MODE_OFF = 0;
    static final int DEPTH_MODE_ON = 1;
    static final int DEPTH_MODE_AUTO = 2;

    /** Last switch state seen from the preference store, for cache invalidation on a UI toggle. */
    private static int lastDepthMode = Integer.MIN_VALUE;
    private static boolean lastDepthLegacy;

    /**
     * Per-pixel depth gate, shared by the preview and the export path.
     *
     * <p>Both paths go through {@link #renderCompToFbo}: the preview passes {@code preview=true} and
     * a proxy scale, the export/render-cache passes {@code preview=false} and scale 1 (or the
     * alpha-stage flag). None of those parameters appears in this decision — only the frame's
     * geometry ({@link CompScene#spatial}) and the user/developer switch — so a frame cannot use
     * per-pixel depth in the editor and whole-layer order in the encoder, or the reverse.</p>
     */
    static boolean perPixelDepthFrame(CompRenderContext context)
    {
        return CompScene.spatial(context) && perPixelDepthRequested(context);
    }

    /** Testable frame gate: explicit mode + legacy boolean, no preference store involved. */
    static boolean perPixelDepthFrame(CompRenderContext context, int mode, boolean legacyBoolean)
    {
        return CompScene.spatial(context) && perPixelDepthRequested(context, mode, legacyBoolean);
    }

    /** Production gate: reads the preference (headless-safe) and applies the developer override. */
    static boolean perPixelDepthRequested(CompRenderContext context)
    {
        int mode;
        boolean legacy;

        try
        {
            CompPrefs.Data prefs = CompPrefs.get();

            mode = prefs.perPixelDepthMode;
            legacy = prefs.perPixelDepth;
        }
        catch (RuntimeException | LinkageError ignored)
        {
            /* Headless unit test / early init: the preference store needs FabricLoader. */
            mode = DEPTH_MODE_OFF;
            legacy = false;
        }

        if (mode != lastDepthMode || legacy != lastDepthLegacy)
        {
            boolean known = lastDepthMode != Integer.MIN_VALUE;

            lastDepthMode = mode;
            lastDepthLegacy = legacy;

            if (known)
            {
                /* The depth switch is a display preference, not document content, so it is not in
                 * CompRenderCache's document fingerprint. A parked playhead could keep serving the
                 * frame rendered with the old mode after the user toggles it; drop the frame caches
                 * so the next render uses the new gate. (The cache-hit path returns before this
                 * runs; see the route note in docs/DEPTH-GHOST-FIX.zh.md — the UI should also mix
                 * the mode into the signature via the documented patch, or call
                 * CompRenderCache.clear() itself when it writes the preference.) */
                CompRenderCache.clear();
                CompFrameServer.invalidate();
                /* Pre-render textures may be queued for this frame's UI blit; clearing them here
                 * can delete a texture the batcher still references. The UI code that writes the
                 * preference is the safe point for CompPrecache.clearAll() — documented as the
                 * hook in docs/DEPTH-GHOST-FIX.zh.md. */
            }
        }

        return perPixelDepthRequested(context, mode, legacy);
    }

    /**
     * The tri-state decision without touching the preference store.
     *
     * <p>This is the whole switch: the editor, the AE frame server and the encoder all call it, so
     * the three states cannot disagree between preview and export. Headless tests call this
     * variant directly because the preference store needs FabricLoader.</p>
     */
    static boolean perPixelDepthRequested(CompRenderContext context, int mode, boolean legacyBoolean)
    {
        if (Boolean.getBoolean("bbsae.debugForceDepthPlane"))
        {
            return true;
        }

        if (mode == DEPTH_MODE_AUTO)
        {
            return needsPerPixelDepthAuto(context);
        }

        if (mode == DEPTH_MODE_ON)
        {
            return true;
        }

        /* Mode 0 (or an unknown value, treated as off). A legacy "true" boolean is the migration
         * path of preference files written before the tri-state existed. */
        return mode == DEPTH_MODE_OFF && legacyBoolean;
    }

    /**
     * AUTO predicate: does this frame actually need per-pixel depth?
     *
     * <p>Whole-layer painter order sorts by plane depth, which is exact for parallel planes. It
     * fails only where two non-parallel layers overlap on screen, so that is the only case AUTO
     * enables the depth path. The decision uses the same functions the renderer uses for the real
     * routing ({@link CompScene#usePerspective}, {@link CompScene#world},
     * {@link CompCamera#project}), so editor/export geometry cannot disagree; the preview/scale
     * flags never enter it.</p>
     *
     * <p>Only <b>real 3D pixel layers</b> can cross a plane: a 2D layer is screen-fixed (it is
     * composited in stack order and never takes the perspective path), and cameras, nulls and
     * adjustment layers own no projected pixels. Counting any of them used to switch the whole
     * frame to per-pixel depth for geometry that cannot cross anything.</p>
     */
    static boolean needsPerPixelDepthAuto(CompRenderContext context)
    {
        if (context == null || context.comp == null || context.comp.layers == null)
        {
            return false;
        }

        CompCamera camera = CompScene.camera(context);
        List<CompLayer> perspective = new ArrayList<>();

        boolean modelVolume = false;

        for (CompLayer layer : context.comp.layers)
        {
            if (layer == null || !layer.visible || !layer.activeAt(context.time)
                || context.comp.isSoloedOut(layer)
                || !CompScene.hasPixels(layer)
                /* Legacy projection makes every layer perspective once the frame is spatial;
                 * AUTO is still a 3D-pixel concept, so a demoted 2D layer must not count. A
                 * model is the one exception: it is geometry even without the legacy flag. */
                || (!layer.threeD && !CompScene.isModelVolume(layer))
                || !CompScene.usePerspective(context, layer))
            {
                continue;
            }

            if (CompScene.isModelVolume(layer))
            {
                modelVolume = true;
            }

            perspective.add(layer);
        }

        if (perspective.size() < 2)
        {
            return false;
        }

        /*
         * A model is not a plane, so parallel-plane painter order cannot resolve it: the actor has
         * thickness and a text/shape plane can cut through it. Any frame that contains a model and
         * another 3D pixel layer needs the shared per-pixel depth target, regardless of whether
         * the layer planes themselves are parallel.
         */
        if (modelVolume)
        {
            return true;
        }

        for (int i = 0; i < perspective.size(); i++)
        {
            for (int j = i + 1; j < perspective.size(); j++)
            {
                if (!planesAreParallel(context, perspective.get(i), perspective.get(j))
                    && projectedBoundsOverlap(context, camera, perspective.get(i), perspective.get(j)))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Parallel (or anti-parallel) planes can be ordered exactly as whole layers. */
    private static boolean planesAreParallel(CompRenderContext context, CompLayer a, CompLayer b)
    {
        Vector3f normalA = planeNormal(context, a);
        Vector3f normalB = planeNormal(context, b);

        return Math.abs(normalA.dot(normalB)) >= 1F - 0.0005F;
    }

    private static Vector3f planeNormal(CompRenderContext context, CompLayer layer)
    {
        Vector3f normal = new Vector3f(0F, 0F, 1F);

        CompScene.world(context, layer).transformDirection(normal);

        return normal.normalize();
    }

    private static boolean projectedBoundsOverlap(CompRenderContext context, CompCamera camera,
                                                  CompLayer a, CompLayer b)
    {
        float[] boundsA = projectedBounds(context, camera, a);
        float[] boundsB = projectedBounds(context, camera, b);

        if (boundsA == null || boundsB == null)
        {
            return false;
        }

        return boundsA[0] <= boundsB[2] && boundsB[0] <= boundsA[2]
            && boundsA[1] <= boundsB[3] && boundsB[1] <= boundsA[3];
    }

    /** Projected screen AABB {minX, minY, maxX, maxY} of the layer's content rectangle, or null. */
    private static float[] projectedBounds(CompRenderContext context, CompCamera camera, CompLayer layer)
    {
        float width = Math.max(1F, layer.width);
        float height = Math.max(1F, layer.height);
        float x = -width * layer.anchorX;
        float y = -height * layer.anchorY;
        Matrix4f world = CompScene.world(context, layer);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int corner = 0; corner < 4; corner++)
        {
            Vector3f point = new Vector3f(
                (corner == 0 || corner == 3) ? x : x + width,
                (corner == 0 || corner == 1) ? y : y + height,
                0F);

            world.transformPosition(point);

            Vector2f projected = camera.project(point, context.width, context.height);

            if (projected == null)
            {
                return null;
            }

            minX = Math.min(minX, projected.x);
            minY = Math.min(minY, projected.y);
            maxX = Math.max(maxX, projected.x);
            maxY = Math.max(maxY, projected.y);
        }

        return new float[] {minX, minY, maxX, maxY};
    }

    /**
     * True when this layer must be composited through the depth plane. Everything here is frame
     * geometry too (shared depth target, perspective projection) plus the debug kill switch; the
     * preview/export flags never enter it.
     */
    /** Composite one independent model target at its place in the layer order. */
    private static GlFramebuffer compositeIndependentModel(CompLayer layer, CompRenderContext context,
                                                            Batcher2D batcher, GlFramebuffer accumulation)
    {
        GL11.glColorMask(true, true, true, true);
        com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);

        float opacity = opacityAt(layer, context);

        /*
         * An independent model is not routed through renderBaked3D, so a repeat effect used to be
         * ignored entirely. Worse, when a projected repeat fell through the normal effect loop it
         * resampled the already-projected model target: a flat 2D copy of a 3D actor. Render the
         * model once per copy transform instead, so every copy is real geometry and follows the
         * composition camera.
         */
        CompEffect replication = context.renderEffects ? Replicate3D.find(layer, context) : null;

        if (replication != null)
        {
            java.util.List<Matrix4f> copies = Replicate3D.transforms(replication, layer, context, 0F, 0F);

            if (copies.isEmpty())
            {
                return accumulation;
            }

            float alpha = Math.max(0F, Math.min(1F,
                replication.valueF("alpha", replication.valueF("amount", 1F))));
            float copyOpacity = opacity * alpha;

            try
            {
                for (Matrix4f copy : copies)
                {
                    /* The copy transform is in layer pixels; ReplayActorPass applies it after the
                     * block→pixel scale, so a rotated/scaled layer does not multiply the repeat
                     * offset by its own scale. */
                    context.modelPixelOffset = copy;

                    GlFramebuffer modelContent = ReplayActorPass.renderModel(layer, context, batcher);

                    if (modelContent != null)
                    {
                        /* Masks are layer pixels and must apply before effects, exactly as on a
                         * normal layer. The independent-model branch used to bypass the mask pass
                         * entirely, so a mask on a replay model did nothing. */
                        GlFramebuffer persistent = modelContent;
                        boolean straight = false;
                        GlFramebuffer masked = applyModelMask(modelContent, layer, context);

                        if (masked != modelContent)
                        {
                            modelContent = masked;
                            /* applyModelMask returns straight alpha (the mask shader leaves RGB
                             * as authored while changing coverage). The old premultiplied path
                             * multiplied only alpha, so masked actors stayed too bright. */
                            straight = true;
                        }

                        GlFramebuffer original = modelContent;
                        GlFramebuffer effected = applyModelPostEffects(layer, context, modelContent, !straight);

                        if (effected != modelContent)
                        {
                            modelContent = effected;
                            straight = true;
                        }

                        accumulation = compositeModelTarget(layer, context, modelContent, accumulation,
                            copyOpacity, !straight);

                        if (modelContent != original)
                        {
                            FboPool.release(modelContent);
                        }
                        else if (original != persistent)
                        {
                            FboPool.release(original);
                        }
                    }
                }
            }
            finally
            {
                context.modelPixelOffset = null;
            }

            return accumulation;
        }

        GlFramebuffer modelContent = ReplayActorPass.renderModel(layer, context, batcher);

        if (modelContent == null)
        {
            return accumulation;
        }

        debugFboSum("model-content-" + layer.id, modelContent);

        /*
         * A model's own raster is composition-sized, so screen-space pixel effects such as glitch
         * can run on it directly. Without this, effects on an independent replay model were
         * silently dropped (the independent-model branch never entered the per-layer effect loop).
         * Only source-sampling post effects are applied here: generators would flood the whole
         * composition instead of the actor.
         */
        GlFramebuffer persistent = modelContent;
        boolean straight = false;
        GlFramebuffer masked = applyModelMask(modelContent, layer, context);

        if (masked != modelContent)
        {
            modelContent = masked;
            straight = true;
        }

        GlFramebuffer original = modelContent;
        GlFramebuffer effected = applyModelPostEffects(layer, context, modelContent, !straight);

        if (effected != modelContent)
        {
            modelContent = effected;
            straight = true;
        }

        GlFramebuffer result = compositeModelTarget(layer, context, modelContent, accumulation, opacity, !straight);

        if (modelContent != original)
        {
            FboPool.release(modelContent);
        }
        else if (original != persistent)
        {
            FboPool.release(original);
        }

        return result;
    }

    /**
     * Apply the layer's path masks to a replay-model target. Models are composition-sized and may
     * be true 3D volumes, so the projected mask branch of {@link #applyLayerMask} is the correct
     * one; 2D models fall back to the layer-space branch. The pass is a no-op when {@code 蒙版} is
     * switched off or the layer has no masks.
     */
    private static GlFramebuffer applyModelMask(GlFramebuffer content, CompLayer layer, CompRenderContext context)
    {
        if (content == null || layer == null || !context.renderMasks
            || layer.masks == null || layer.masks.isEmpty())
        {
            return content;
        }

        int maskWidth = context.renderWidth();
        int maskHeight = context.renderHeight();
        MaskCache.MaskTexture mask;

        if (CompScene.usePerspective(context, layer))
        {
            CompCamera maskCamera = CompScene.camera(context);
            Matrix4f viewProjection = new Matrix4f(maskCamera.projection)
                .mul(maskCamera.view)
                .mul(CompScene.world(context, layer));

            mask = MaskCache.getProjected(layer, maskWidth, maskHeight, viewProjection,
                context.frame, projectedMaskScale(context, layer, maskCamera),
                MaskCache.cacheScope(context.project, context.comp));
        }
        else
        {
            float scale = Math.max(0.0001F, maskWidth / (float) Math.max(1, context.comp.width));
            Matrix4f maskModel = new Matrix4f(CompScene.model(context, layer)).scale(scale, scale, 1F);

            mask = MaskCache.get(layer, maskWidth, maskHeight, maskModel, context.frame,
                maskLayerScale(maskModel, scale), MaskCache.cacheScope(context.project, context.comp));
        }

        if (mask == null)
        {
            return content;
        }

        /*
         * The model target is premultiplied, but the shared mask shader operates on straight
         * alpha: it changes coverage without multiplying RGB. Feeding it premultiplied pixels
         * left the actor's colour too bright under the mask. Un-premultiply a temporary copy, mask
         * that (straight) result, then hand the caller a straight-alpha framebuffer.
         *
         * Do NOT release the input: a model target is owned by ReplayActorPass's persistent
         * per-layer cache, not by FboPool. Only the temporary straight copy and the mask output
         * belong to this method/caller.
         */
        GlFramebuffer straight = PassRenderer.unpremultiply(content);
        GlFramebuffer base = straight == null ? content : straight;

        if (base == content)
        {
            /* Could not convert: masking premultiplied data would be wrong, so keep the original
             * unmasked frame rather than producing a bright halo. */
            return content;
        }

        GlFramebuffer masked = PassRenderer.mask(base, mask.texture.id, 0);

        FboPool.release(base);

        return masked == null ? content : masked;
    }

    private static GlFramebuffer applyModelPostEffects(CompLayer layer, CompRenderContext context,
                                                       GlFramebuffer model, boolean premultiplied)
    {
        if (model == null || !context.renderEffects || layer.effects == null || layer.effects.isEmpty())
        {
            return model;
        }

        GlFramebuffer current = model;

        for (CompEffect effect : layer.effects)
        {
            if (effect == null || !effect.enabled)
            {
                continue;
            }

            String type = effect.type == null ? "" : effect.type;

            /* Glitch is the reported case: its displaced bands must leave the actor/shape edge. */
            if (!"glitch".equals(type))
            {
                continue;
            }

            CompEffect resolved = layer.resolveEffect(effect, context.frame);

            if (EffectRenderer.isNeutral(resolved, type))
            {
                continue;
            }

            if (current == model && premultiplied)
            {
                GlFramebuffer straight = PassRenderer.unpremultiply(model);

                if (straight == null)
                {
                    return model;
                }

                current = straight;
            }

            GlFramebuffer output = EffectRenderer.apply(layer, resolved, current, context,
                EffectSpace.identity(context));

            if (output != current)
            {
                FboPool.release(current);
                current = output;
            }
        }

        return current;
    }

    private static GlFramebuffer compositeModelTarget(CompLayer layer, CompRenderContext context,
                                                      GlFramebuffer modelContent, GlFramebuffer accumulation,
                                                      float opacity, boolean premultiplied)
    {
        /*
         * A model is a depth volume, not a plane. When the frame already has the shared per-pixel
         * depth target, composite through the model's own depth so a text/shape plane in front
         * occludes exactly the part of the actor behind it. The flat path stays as the fallback
         * for frames where the depth path is off or unavailable.
         *
         * Both paths consume the premultiplied model colour directly (one fewer full-screen pass
         * and framebuffer per actor than the old unpremultiply-then-draw sequence).
         */
        if (opacity > 0.0001F && context.spatialDepth != null
            && CompScene.usePerspective(context, layer) && modelContent.depthTextureId() != 0
            && !Boolean.getBoolean("bbsae.debugNoDepthPlane"))
        {
            GlFramebuffer previous = accumulation;

            accumulation = context.spatialDepth.compositeModel(modelContent, modelContent, previous,
                layer.blendMode, opacity, premultiplied);
            debugFboSum("model-blend-" + layer.id, accumulation);
            FboPool.release(previous);

            return accumulation;
        }

        if (opacity > 0.0001F)
        {
            GlFramebuffer blended = FboPool.acquire(accumulation.width, accumulation.height);

            if (premultiplied)
            {
                PassRenderer.drawIntoPremultiplied(blended, modelContent, accumulation,
                    layer.blendMode, opacity, 0xFFFFFF);
            }
            else
            {
                PassRenderer.drawInto(blended, modelContent, accumulation, null,
                    layer.blendMode, opacity, 0F, 0F, 0xFFFFFF, 0);
            }

            debugFboSum("model-blend-" + layer.id, blended);
            FboPool.release(accumulation);
            accumulation = blended;
        }

        return accumulation;
    }

    static boolean perPixelDepthLayer(CompRenderContext context, CompLayer layer)
    {
        return context.spatialDepth != null
            && CompScene.usePerspective(context, layer)
            && !Boolean.getBoolean("bbsae.debugNoDepthPlane");
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

        if (perPixelDepthFrame(context))
        {
            context.spatialDepth = DepthPlaneCompositor.acquire(context.renderWidth(), context.renderHeight());
        }

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
                if (!CompPreviewFilter.visible(layer))
                {
                    continue;
                }

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
                        accumulation = applyAdjustment(layer, accumulation, context, stack, batcher, renderMasks);
                    }
                    continue;
                }

                /* Independent bbsae models are depth targets, but they are ordinary layers in the
                 * composition order. Composite them here, not in a final overlay: text/shape/3D
                 * cards above the model must occlude it, and a model above them must cover them.
                 * The old final overlay made every model draw on top of every layer. */
                if (layer.independent)
                {
                    accumulation = compositeIndependentModel(layer, context, batcher, accumulation);

                    continue;
                }

                Matrix4f model = CompScene.model(context, layer);
                Matrix4f layerProjection = CompScene.projection(context, layer);
                /* A previous 3D/matte layer may have left a depth-only RGB write mask behind
                 * (especially when a live-world layer is unavailable). Forcing the mask here
                 * keeps a paused frame from turning into opaque black. */
                GL11.glColorMask(true, true, true, true);
                com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);
                LayerRender rendered = renderLayerContent(layer, context, model, layerProjection, stack, batcher, renderEffects);
                GlFramebuffer content = rendered == null ? null : rendered.framebuffer;

                if (content == null)
                {
                    continue;
                }

                /* Every acquired framebuffer below is owned by this iteration. A malformed layer
                 * (bad replay data, an effect that throws) must not leak its content target into
                 * the void: the outer finally only knows the accumulation target. The catch is
                 * deliberately at this scope so normal releases keep their inline shape. */
                try
                {
                boolean effectsBaked = rendered.effectsBaked;

                content = applyLayerMask(content, layer, context, model, effectsBaked, renderMasks);

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

                    /* The 3D replicator quads already drew this effect in layer-local space. */
                    if (effect.id != null && context.consumedEffectIds.remove(effect.id))
                    {
                        continue;
                    }

                    CompEffect resolved = layer.resolveEffect(effect, context.frame);
                    /* Effects outside the bake run on the pixels this pass holds. For a
                     * perspective 3D layer that image is already camera-projected, so a
                     * layer-space mapping would find a different region of the plane than the
                     * pixel it is looking at (backdrop readers like glass/displace map sampled
                     * mirrored or empty areas). Composition space is the honest space for them. */
                    GlFramebuffer processed;

                    if (depthEffect || effectsRunOnProjectedContent(context, layer))
                    {
                        /* Replication still needs to know where the layer is and how big it looks,
                         * which only the projected corners can answer. */
                        EffectSpace projectedSpace = EffectRenderer.isReplication(resolved.type)
                            ? projectedEffectSpace(context, layer)
                            : EffectSpace.identity(context);

                        processed = EffectRenderer.apply(layer, resolved, content, context, projectedSpace);
                    }
                    else
                    {
                        processed = EffectRenderer.apply(layer, resolved, content, context, effectSpace);
                    }

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

                /* Styles are baked with the layer-space raster, except when the stack has effects
                 * that run after the projection: then the bake deferred the styles so this pass can
                 * keep the 2D order (effects first, styles last). The fallback (no bake) applies
                 * them here too. */
                if (renderEffects && (!effectsBaked || stylesRunAfterProjection(layer)))
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

                    if (matte != null && matte != layer && CompScene.hasPixels(matte))
                    {
                        Matrix4f matteModel = CompScene.model(context, matte);
                        LayerRender matteRender = renderLayerContent(matte, context, matteModel, CompScene.projection(context, matte), stack, batcher, renderEffects);
                        GlFramebuffer matteFbo = matteRender == null ? null : matteRender.framebuffer;

                        if (matteFbo != null)
                        {
                            /* The matte's own masks belong to its own pixels; the bake already
                             * contains them when it ran, in which case applying them again squared
                             * the coverage (dimmed matte edges). One shared helper keeps the
                             * once-only contract for both the target and the matte. */
                            matteFbo = applyLayerMask(matteFbo, matte, context, matteModel,
                                matteRender.effectsBaked, renderMasks);

                            int channel = layer.trackMatteMode == CompLayer.MATTE_LUMA ? 1 : layer.trackMatteMode == CompLayer.MATTE_ALPHA_INVERTED ? 2 : layer.trackMatteMode == CompLayer.MATTE_LUMA_INVERTED ? 3 : 0;
                            GlFramebuffer matted = PassRenderer.mask(content, matteFbo.color.id, channel);

                            FboPool.release(matteFbo);

                            if (matted == null)
                            {
                                /* Shader unavailable: the track matte cannot be applied; keep the
                                 * unmasked content instead of dropping the layer or leaking it. */
                                continue;
                            }

                            FboPool.release(content);
                            content = matted;
                        }
                    }
                }

                float opacity = opacityAt(layer, context);

                if (opacity <= 0.0001F)
                {
                    FboPool.release(content);
                    continue;
                }

                GlFramebuffer blended;

                if (perPixelDepthLayer(context, layer)
                    && layer.blendMode != CompLayer.BLEND_MASK
                    && layer.blendMode != CompLayer.BLEND_EXCLUDE)
                {
                    /* 3D layers carry a plane; per-pixel depth resolves crossings that whole-layer
                     * painter order cannot. Blend mode/opacity still goes through PassRenderer.
                     * Mask/Exclude are full-backdrop alpha operations: they must not write the
                     * source silhouette into the shared depth, or later layers treat it as solid. */
                    blended = context.spatialDepth.composite(content, CompScene.world(context, layer),
                        CompScene.camera(context), accumulation, layer.blendMode, opacity);
                }
                else
                {
                    blended = PassRenderer.draw(content, accumulation, null, layer.blendMode, opacity, 0F, 0F, 0xFFFFFF);
                }

                FboPool.release(content);
                FboPool.release(accumulation);
                accumulation = blended;
                }
                catch (RuntimeException | Error e)
                {
                    if (content != null && !content.pooled)
                    {
                        FboPool.release(content);
                    }

                    throw e;
                }
            }

            debugFboSum("after-models", accumulation);
            batcher.flush();
            BufferRenderer.resetCurrentVertexBuffer();
            completed = true;
        }
        finally
        {
            stack.remove(comp.id);

            /* A malformed project that throws mid-graph must not leak the accumulation target:
             * the exception propagates and nobody else knows this framebuffer exists. */
            if (context.spatialDepth != null)
            {
                DepthPlaneCompositor.release(context.spatialDepth);
                context.spatialDepth = null;
            }

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

    private static void debugFboSum(String tag, GlFramebuffer framebuffer)
    {
        if (!Boolean.getBoolean("bbsae.debugReplayPreview") || framebuffer == null)
        {
            return;
        }

        int previous = org.lwjgl.opengl.GL30.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        byte[] pixels = framebuffer.readRawBytes();
        long alpha = 0L;
        long rgb = 0L;

        for (int i = 0; i < pixels.length; i += 4)
        {
            alpha += pixels[i + 3] & 0xFF;
            rgb += (pixels[i] & 0xFF) + (pixels[i + 1] & 0xFF) + (pixels[i + 2] & 0xFF);
        }

        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previous);

        dev.bbsae.BbsAeAddon.LOGGER.info("Replay preview {}: fbo={} {}x{} alpha={} rgb={}",
            tag, framebuffer.id, framebuffer.width, framebuffer.height, alpha, rgb);
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

        if (previewOpaqueStage)
        {
            return comp == null ? 0xFF000000 : comp.background;
        }

        if (preview && project != null && project.settings != null && project.settings.previewBackground == 1)
        {
            return 0;
        }

        return comp == null ? 0xFF000000 : comp.background;
    }

    /**
     * Effects and styles work in layer-local UV space. For a screen-fixed 2D layer that space is
     * the composition-space world matrix; a perspective 3D layer keeps its world matrix (a
     * perspective homography cannot be expressed as the affine map those effects expect).
     */
    private static Matrix4f effectSpace(CompRenderContext context, CompLayer layer, Matrix4f model)
    {
        return CompScene.usePerspective(context, layer) ? CompScene.world(context, layer) : model;
    }

    /**
     * Painter's order: <b>runs</b> of consecutive 3D pixel layers are permuted far-to-near.
     *
     * <p>A run is a maximal sequence of layers for which {@link CompScene#sortsIn3D} is true
     * (AE logic: {@code threeD && hasPixels}). A 2D layer, an adjustment layer, a camera/null, or
     * any layer without pixels keeps its stack slot and <b>breaks</b> the run. That is what makes
     * a 2D layer composite between two 3D groups in stack order instead of being dragged by a
     * depth it does not have. Legacy logic keeps the old rule (once the frame is spatial, every
     * content layer is spatial), so old projects keep their look.</p>
     */
    static List<CompLayer> orderLayers(CompDocument comp, CompRenderContext context)
    {
        List<CompLayer> order = new ArrayList<>(comp.layers);
        int start = 0;

        for (int i = 0; i <= order.size(); i++)
        {
            CompLayer current = i == order.size() ? null : order.get(i);
            /*
             * Replay models are volumes, not flat planes: their layer-origin depth is not a
             * valid painter-order key. Sorting them with the 3D planes let a lower model with a
             * nearer origin render after (and therefore cover) an upper model. Keep every model
             * as a boundary in composition-stack order; the shared per-pixel depth comparator
             * still interleaves its fragments with planes when depth is on, and when depth is
             * off the stack order is the only correct answer.
             */
            boolean boundary = i == order.size() || current.independent
                || !CompScene.sortsIn3D(context, current);

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

        /* Independent models are ordinary layers in the composition stack: keeping them in the
         * same 3D sort run is what lets a text/shape plane in front occlude them, and a model in
         * front cover the plane. The old split forced every model to the top. */
        return order;
    }

    private static void sortSegment(List<CompLayer> order, int start, int end, CompRenderContext context)
    {
        /* Ascending depth = far first. CompScene caches the value for this frame, so the comparator
         * does a map lookup instead of rebuilding two matrices per comparison. The sort is stable,
         * so coplanar layers keep their stack order. */
        order.subList(start, end).sort((a, b) ->
        {
            int depth = Float.compare(CompScene.planeDepth(context, a), CompScene.planeDepth(context, b));

            if (depth != 0)
            {
                return depth;
            }

            /* Coplanar layers tie: stable sort keeps the composition stack order, so a model does
             * not jump above a text/shape layer that is stacked over it. */
            return 0;
        });
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

    /**
     * Applies a layer's masks to its content exactly once.
     *
     * <p>Two paths already contain the mask and must not apply it again: the 3D bake rasterizes
     * masks into the layer raster, and this is the only place that knows whether the content came
     * from that bake ({@code effectsBaked}). Track-matte layers used to be masked twice (once by the
     * bake, once by the matte block), which squared the coverage and dimmed the edges. The 2D path
     * uses the affine layer model; a perspective layer whose bake was skipped uses the projected
     * path, because its content already lives in composition space.</p>
     */
    static GlFramebuffer applyLayerMask(GlFramebuffer content, CompLayer layer, CompRenderContext context,
                                        Matrix4f model, boolean masksBaked, boolean masksEnabled)
    {
        if (content == null || masksBaked || !masksEnabled || layer == null
            || layer.masks == null || layer.masks.isEmpty())
        {
            return content;
        }

        /* Rasterize at the RENDER size, not the composition size. The mask texture is sampled
         * with the content FBO's own UVs, so a full-resolution mask carries no extra information —
         * only cost: an animated mask re-rasterized a 1920x1080 AWT path plus a box blur per frame,
         * which is the "编辑蒙版时预览卡顿" complaint. The 3D bake path already worked this way. */
        int maskWidth = context.renderWidth();
        int maskHeight = context.renderHeight();
        MaskCache.MaskTexture mask;

        if (CompScene.usePerspective(context, layer))
        {
            /* The direct-path content is already camera-projected in composition space, so the
             * layer-local affine mask cannot address it: project the mask path with the same
             * view-projection and rasterize it at render size. Before this the mask was simply
             * dropped, so a masked 3D entity/precomp layer showed unmasked. */
            CompCamera maskCamera = CompScene.camera(context);
            Matrix4f maskViewProjection = new Matrix4f(maskCamera.projection)
                .mul(maskCamera.view)
                .mul(CompScene.world(context, layer));
            float maskScale = projectedMaskScale(context, layer, maskCamera);

            mask = MaskCache.getProjected(layer, maskWidth, maskHeight, maskViewProjection,
                context.frame, maskScale, MaskCache.cacheScope(context.project, context.comp));
        }
        else
        {
            float maskScale = Math.max(0.0001F, maskWidth / (float) Math.max(1, context.comp.width));
            Matrix4f maskModel = new Matrix4f(model)
                .scale(maskScale, maskScale, 1F);
            mask = MaskCache.get(layer, maskWidth, maskHeight, maskModel, context.frame,
                maskLayerScale(model, maskScale), MaskCache.cacheScope(context.project, context.comp));
        }

        if (mask == null)
        {
            return content;
        }

        GlFramebuffer masked = PassRenderer.mask(content, mask.texture.id, 0);

        if (masked == null)
        {
            /* No mask pass available: keep the unmasked content; the caller still owns it. */
            return content;
        }

        FboPool.release(content);

        return masked;
    }

    /**
     * Raster pixels per layer pixel for mask feather/expansion: the composition-to-raster factor
     * times the layer's own transform scale. Feather is authored in layer pixels, so a scaled layer
     * must scale its feather too; before this the feather stayed constant while the rest of the
     * mask moved/scaled.
     */
    static float maskLayerScale(Matrix4f model, float rasterPerComp)
    {
        if (model == null)
        {
            return rasterPerComp;
        }

        float x = (float) Math.sqrt(model.m00() * model.m00() + model.m01() * model.m01());
        float y = (float) Math.sqrt(model.m10() * model.m10() + model.m11() * model.m11());

        return rasterPerComp * Math.max(0.0001F, Math.max(x, y));
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
    /**
     * Whether a 3D layer's pixel operations belong in the layer-local bake. Replay entity layers
     * are excluded: their matte is projected in composition space, so baking them layer-locally
     * made 3D effects sample the wrong region of the world frame.
     */
    static boolean shouldBake3D(CompLayer layer)
    {
        if (layer == null || !layer.threeD || "camera".equals(layer.type) || "adjustment".equals(layer.type))
        {
            return false;
        }

        if (layer.isEntityLayer())
        {
            return false;
        }

        boolean masked = layer.masks != null && !layer.masks.isEmpty();

        return hasEnabledEffects(layer) || hasEnabledStyles(layer) || masked;
    }

    private static GlFramebuffer renderBaked3D(CompLayer layer, CompRenderContext context, Matrix4f model,
                                               Matrix4f layerProjection, Batcher2D batcher)
    {
        if (!shouldBake3D(layer))
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

            /* A leading replicator must become real 3D quads here. Running it later on the
             * projected image is what made "3D repeats" look like a flat 2D tiling: every copy was
             * a screen-space resample of one plane. */
            CompEffect replicated = Replicate3D.leading(layer, context);
            java.util.List<Matrix4f> copies = Replicate3D.transforms(replicated, layer, context, source);
            boolean hasCopies = !copies.isEmpty();

            if (hasCopies)
            {
                context.consumedEffectIds.add(replicated.id);
            }

            /* Extrusion is a geometry operation: stack the flat layer raster along its local Z.
             * The source cache deliberately leaves extrude3d out of the pixel bake; doing it here
             * (instead of flattening a copy into the raster) makes the side volume survive the
             * projection and the camera orbit. */
            CompEffect extrusion = findEffect(layer, "extrude3d");

            if (extrusion != null)
            {
                extrusion = layer.resolveEffect(extrusion, context.frame);
            }
            float extrudeDepth = extrusion == null ? 0F
                : Math.max(0F, Math.min(400F, extrusion.valueF("depth", 60F)));
            int slices = extrusion == null ? 0
                : Math.max(1, Math.min(48, Math.round(extrusion.valueF("slices", 16F))));
            float sideShade = extrusion == null ? 0F
                : Math.max(0F, Math.min(1F, extrusion.valueF("shade", 0.35F)));
            int firstSlice = extrudeDepth > 0.5F ? slices : 0;
            int iterations = hasCopies ? copies.size() : firstSlice + 1;

            for (int iteration = 0; iteration < iterations; iteration++)
            {
                if (hasCopies)
                {
                    stack.peek().getPositionMatrix().set(model).mul(copies.get(iteration));
                }
                else
                {
                    int slice = firstSlice - iteration;
                    float z = firstSlice == 0 ? 0F : -extrudeDepth * (slice / (float) firstSlice);

                    stack.peek().getPositionMatrix().set(model).translate(0F, 0F, z);
                }

                float shade = 1F;

                if (!hasCopies && firstSlice > 0)
                {
                    int slice = firstSlice - iteration;
                    shade = 1F - sideShade * (slice / (float) firstSlice);
                }

                int value = Math.max(0, Math.min(255, Math.round(255F * shade)));
                int tint = 0xFF000000 | (value << 16) | (value << 8) | value;

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

                        batcher.texturedBox(source.texture.color.id, tint,
                            lx0, ly0, stepX, stepY,
                            u0 * texW, vTop * texH, u1 * texW, vBottom * texH,
                            (int) texW, (int) texH);
                    }
                }

                /* One flush per copy/slice keeps painter order explicit. */
                batcher.flush();
            }
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

    private static GlFramebuffer applyAdjustment(CompLayer layer, GlFramebuffer original, CompRenderContext context, Set<String> stack, Batcher2D batcher, boolean renderMasks)
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

        if (renderMasks && layer.masks != null && !layer.masks.isEmpty())
        {
            int maskWidth = context.renderWidth();
            int maskHeight = context.renderHeight();
            float maskScale = Math.max(0.0001F, maskWidth / (float) Math.max(1, context.width));
            Matrix4f maskModel = new Matrix4f(CompScene.world(context, layer)).scale(maskScale, maskScale, 1F);
            MaskCache.MaskTexture mask = MaskCache.get(layer, maskWidth, maskHeight, maskModel, context.frame,
                maskLayerScale(CompScene.world(context, layer), maskScale),
                MaskCache.cacheScope(context.project, context.comp));

            if (mask != null)
            {
                GlFramebuffer masked = PassRenderer.mask(processed, mask.texture.id, 0);

                if (masked != null)
                {
                    GlFramebuffer result = PassRenderer.draw(masked, original, null, CompLayer.BLEND_NORMAL, opacity, 0F, 0F, 0xFFFFFF);

                    FboPool.release(masked);
                    FboPool.release(processed);
                    FboPool.release(original);

                    return result;
                }

                /* Fall through: no mask pass means the adjusted pixels are the result. */
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
     * True when the framebuffer handed to the composition effect loop already contains the
     * camera-projected layer rather than a composition-space image.
     *
     * <p>It is exactly {@link CompScene#usePerspective}: those layers are drawn through
     * {@code projection · view}, either by the layer-local bake or by the direct fallback, so
     * their pixels are screen pixels. Everything else (2D layers, flat 3D layers under a
     * non-perspective camera) is painted in composition space and composited there, because the
     * orthographic projection is already the composition rectangle. Effects that resample their
     * input need to know which one they got.</p>
     */
    static boolean effectsRunOnProjectedContent(CompRenderContext context, CompLayer layer)
    {
        return CompScene.usePerspective(context, layer);
    }

    /**
     * Composition-space effect basis for a projected layer: comp-space mapping plus the projected
     * centre and screen size of the layer box.
     *
     * <p>Replication copies must keep the layer's on-screen size; on a projected 3D layer that is
     * neither {@code layer.width} nor the composition size, it is the bounding box of the four
     * projected corners. Corners behind the camera are ignored when at least two of them survive;
     * otherwise the layer's nominal size scaled by the camera zoom is a stable fallback.</p>
     */
    static EffectSpace projectedEffectSpace(CompRenderContext context, CompLayer layer)
    {
        float compW = Math.max(1F, context.width);
        float compH = Math.max(1F, context.height);
        float w = Math.max(1F, Math.abs(layer.width));
        float h = Math.max(1F, Math.abs(layer.height));
        /* Local coordinates of the layer box corners relative to the anchor. */
        float x0 = -w * layer.anchorX;
        float y0 = -h * layer.anchorY;
        Vector2f centre = CompScene.project(context, layer, x0 + w * 0.5F, y0 + h * 0.5F, 0F);

        if (centre == null)
        {
            return EffectSpace.identity(context);
        }

        float[] xs = new float[4];
        float[] ys = new float[4];
        boolean complete = true;

        for (int i = 0; i < 4; i++)
        {
            Vector2f point = CompScene.project(context, layer,
                x0 + ((i == 1 || i == 2) ? w : 0F),
                y0 + (i >= 2 ? h : 0F), 0F);

            if (point == null)
            {
                complete = false;
                break;
            }

            xs[i] = point.x;
            ys[i] = point.y;
        }

        float sizeX;
        float sizeY;

        if (complete)
        {
            minMax(xs);
            minMax(ys);
            sizeX = xs[1] - xs[0];
            sizeY = ys[1] - ys[0];
        }
        else
        {
            float zoom = Math.abs(CompScene.camera(context).zoom);

            sizeX = w * zoom;
            sizeY = h * zoom;
        }

        return EffectSpace.identity(context,
            centre.x / compW,
            1F - centre.y / compH,
            Math.max(0.0001F, sizeX) / compW,
            Math.max(0.0001F, sizeY) / compH);
    }

    /** Sorts four values into min/max in place (xs[0] = min, xs[1] = max). */
    private static void minMax(float[] values)
    {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;

        for (float value : values)
        {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        values[0] = min;
        values[1] = max;
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

        return !effectsBaked || requiresProjectedPassFor(effect);
    }

    /**
     * True when the effect can only run on the projected frame: depth effects read the world depth
     * buffer, shake samples the composition, backdrop effects need the accumulated comp, and the
     * AM bodies that read {@code Comp} need the composition under the layer (they must not be baked
     * into a layer-local raster where there is no composition at all).
     *
     * <p>Mesh shape effects are deliberately NOT here: they draw into the layer-local raster during
     * the bake, so the projected quad carries them exactly like any other content effect. Running
     * them after projection made the primitive use the composition matrix while its pixels were
     * still a layer raster, which is what produced the tiny/offset 3D shapes.</p>
     */
    static boolean requiresProjectedPass(String type)
    {
        if (type == null)
        {
            return false;
        }

        /* Glitch is a screen-space displacement: on a 3D layer a layer-local bake clips its
         * displaced bands to the layer raster. Running it after projection lets it use the full
         * composition-sized frame, so the effect can extend past the shape instead of looking like
         * a mask. 2D layers already run it on the composition target, so this only changes 3D. */
        return type.startsWith("depth_") || "shake".equals(type) || "glitch".equals(type)
            || dev.bbsae.comp.AmEffectLibrary.usesComp(type)
            || EffectRenderer.isCompositionSpace(type);
    }

    /**
     * Effect-aware form of {@link #requiresProjectedPass(String)}.
     *
     * <p>The classic displacement map has a depth-map mode that samples the world depth buffer.
     * A layer-local bake has no depth attachment, so the effect silently fell back to the noise
     * map on 3D layers. Only the projected pass can honour the mode, therefore it is classified
     * dynamically from the effect's parameters.</p>
     */
    static boolean requiresProjectedPassFor(CompEffect effect)
    {
        if (effect == null)
        {
            return false;
        }

        if (requiresProjectedPass(effect.type))
        {
            return true;
        }

        return "displacement".equals(effect.type) && effect.valueF("depth", 0F) > 0.5F;
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

            if (effect != null && effect.enabled && requiresProjectedPassFor(effect))
            {
                return i;
            }
        }

        return effects.size();
    }

    /**
     * True when a 3D layer's styles have to run after the projected effects instead of in the bake.
     *
     * <p>The 2D path applies effects first and styles last; the layer-local bake used to apply
     * styles immediately after the baked effect prefix, so a 3D layer with a projected effect (for
     * example a repeat/shape-field/glass pass) graded its styled image instead of styling the
     * graded one. That is an order difference the user can see on a 3D layer with a drop shadow or
     * stroke below a repeat effect, and it made "the same stack looks different in 2D and 3D".
     * When the stack has any projected effect the styles are deferred to the composition pass,
     * where the same "effects then styles" order as the 2D path applies.</p>
     */
    static boolean stylesRunAfterProjection(CompLayer layer)
    {
        if (layer == null || layer.styles == null || layer.styles.isEmpty())
        {
            return false;
        }

        if (layer.effects == null || layer.effects.isEmpty())
        {
            return false;
        }

        return firstProjectedEffect(layer.effects) < layer.effects.size();
    }

    /** First enabled effect of a type, or null. */
    private static CompEffect findEffect(CompLayer layer, String type)
    {
        if (layer == null || layer.effects == null)
        {
            return null;
        }

        for (CompEffect effect : layer.effects)
        {
            if (effect != null && effect.enabled && type.equals(effect.type))
            {
                return effect;
            }
        }

        return null;
    }

    private static boolean hasEnabledEffects(CompLayer layer)
    {        if (layer.effects == null)
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
        /* Frame-server FBOs/textures belong to the caches being dropped; leaving them alive kept
         * dangling GL objects after a resource reload or shutdown. */
        CompFrameServer.invalidate();
        /* The viewport's source textures own FBOs from the pool; return them before the pool is
         * wiped, otherwise a later acquire can hand out a deleted texture. */
        CompLayerSourceCache.clear();
        CompViewportRenderer.clearCaches();
        AmEffectRenderer.delete();
        MeshRenderer.delete();
        DepthPlaneCompositor.deleteAll();
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

    /**
     * Export one explicit composition frame.
     *
     * <p>The render queue maps every output frame to a composition frame number itself and passes
     * it here instead of round-tripping through a float time and quantising twice. That keeps a
     * preset fps different from the composition fps deterministic: frame N of the output samples
     * {@code round(N / outputFps * compFps)} of the composition, regardless of float drift.</p>
     */
    public static GlFramebuffer renderForExport(CompProject project, CompDocument comp, int frame,
                                                Batcher2D batcher, boolean alpha)
    {
        return renderFrame(project, comp, frame, alpha);
    }
}
