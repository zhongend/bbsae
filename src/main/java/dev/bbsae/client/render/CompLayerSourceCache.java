package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompShape;
import dev.bbsae.comp.CompStyle;
import dev.bbsae.comp.CompTextAnimator;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-layer source textures: the pixels of one layer, in <b>layer space</b>.
 *
 * <h2>Why this exists</h2>
 * <p>The compositor renders every layer straight into composition space: its content FBO already
 * contains the camera projection. That is correct for the exported frame, but it means a camera
 * move invalidates <b>every</b> layer's pixels — which is exactly why orbiting a 3D composition
 * costs a full graph re-render per mouse move and feels unusable.</p>
 *
 * <p>A camera move does not change what a layer <em>is</em>; it only changes where that layer
 * lands. So the viewport keeps each layer's source rasterized once, in its own local coordinates,
 * and projects that texture as a quad. Editing text/colour/shape rebuilds one texture; orbiting,
 * panning, zooming and dragging rebuild nothing.</p>
 *
 * <h2>Straight alpha, converted at build time</h2>
 * <p>BBS draws straight-alpha sources with {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} into a
 * transparent target, so the raster comes out premultiplied. Premultiplied pixels drawn with the
 * default straight-alpha blend look darker the more transparent they are — gradient fills went
 * muddy and the BBS preview layer's sky went black. The raster is therefore un-premultiplied once
 * per rebuild (a full-screen pass, not per frame), and every consumer can use normal blending.</p>
 *
 * <h2>What is baked here</h2>
 * <p>Effects, layer styles and masks are properties of the layer's own pixels, so they are applied
 * to this raster before projection (effects, then styles, with the mask multiplied in first). This
 * is what keeps a gradient glued to a 3D plane and lets a glow keep its full falloff: the raster is
 * padded by {@link EffectPadding}. Track mattes, blend modes and adjustment layers are genuinely
 * composite operations and stay outside; depth effects need the live world depth, which this draft
 * raster does not have.</p>
 */
public final class CompLayerSourceCache
{
    /** Longest side of a cached layer raster, in pixels. */
    static final int MAX_EDGE = 1024;
    /** Total VRAM budget for source rasters (the FBOs are owned, not pooled). */
    private static final long BUDGET_BYTES = 48L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 32;

    public static final class Entry
    {
        public final GlFramebuffer texture;
        /** The premultiplied staging target; kept so an animated layer can re-render in place. */
        public final GlFramebuffer staging;
        /** Local-space rectangle the texture covers (anchor at 0,0). */
        public final float localX;
        public final float localY;
        public final float localWidth;
        public final float localHeight;
        public final long signature;
        long used;

        private Entry(GlFramebuffer texture, GlFramebuffer staging, float localX, float localY, float localWidth, float localHeight, long signature)
        {
            this.texture = texture;
            this.staging = staging == texture ? null : staging;
            this.localX = localX;
            this.localY = localY;
            this.localWidth = localWidth;
            this.localHeight = localHeight;
            this.signature = signature;
        }
    }

    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static long clock;

    private CompLayerSourceCache()
    {}

    /**
     * Returns the source texture for a layer, rasterizing it if the layer's content changed.
     * Returns null for layers that have no pixels (camera, null, adjustment).
     */
    public static Entry get(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        if (layer == null || context == null || batcher == null || !hasPixels(layer))
        {
            return null;
        }

        /* Independent bbsae models are live 3D actors, not a cacheable 2D source raster. Baking
         * them reused the shared actor target and could return an empty/overwritten texture when
         * other layers were present. Draw them directly every frame. */
        if (layer.independent)
        {
            return null;
        }

        /* Externally linked video is decoded per requested time; a static raster bake would freeze
         * the first frame forever. */
        if ("videolink".equals(layer.type))
        {
            return null;
        }

        String key = cacheKey(context.project == null ? "" : context.project.id, context.comp.id, layer.id);
        Rect rect = bounds(layer, context);
        float scale = rasterScaleFor(rect.width, rect.height, rasterMaxEdge(context), context.scale);
        int pixelWidth = Math.max(1, Math.round(rect.width * scale));
        int pixelHeight = Math.max(1, Math.round(rect.height * scale));
        long signature = signature(layer, context, pixelWidth, pixelHeight);
        Entry cached = CACHE.get(key);

        if (cached != null && cached.signature == signature)
        {
            cached.used = ++clock;

            return cached;
        }

        GlFramebuffer reuseContent = null;
        GlFramebuffer reuseStraight = null;

        if (cached != null)
        {
            /* Animated content (footage, animated text) rebuilds every frame. Allocating two
             * framebuffers per frame pushed the pool to its limit and showed as stutter during
             * playback, so the previous pair is re-used in place. Effects allocate their own
             * passes, so layers with effects keep the simple path. */
            boolean reusable = (layer.effects == null || layer.effects.isEmpty())
                && cached.staging != null
                && cached.staging.width == pixelWidth && cached.staging.height == pixelHeight
                && cached.texture.width == pixelWidth && cached.texture.height == pixelHeight;

            if (reusable)
            {
                reuseContent = cached.staging;
                reuseStraight = cached.texture;
            }

            CACHE.remove(key);

            if (!reusable)
            {
                release(cached);
            }
        }

        Entry built = rasterize(layer, context, batcher, rect, scale, pixelWidth, pixelHeight, signature,
            reuseContent, reuseStraight);

        if (built != null)
        {
            CACHE.put(key, built);
            trim();
        }

        return built;
    }

    public static void clear()
    {
        for (Entry entry : CACHE.values())
        {
            release(entry);
        }

        CACHE.clear();
    }

    static String cacheKey(String projectId, String compId, String layerId)
    {
        return (projectId == null ? "" : projectId) + "/" + compId + "/" + layerId;
    }

    /** Drops matching layer rasters in every project; callers of this legacy API have no project id. */
    public static void invalidate(String compId, String layerId)
    {
        String suffix = "/" + compId + "/" + layerId;
        Iterator<Map.Entry<String, Entry>> iterator = CACHE.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<String, Entry> entry = iterator.next();

            if (entry.getKey().endsWith(suffix))
            {
                iterator.remove();
                release(entry.getValue());
            }
        }
    }

    /**
     * Long-edge cap of a layer raster, before the proxy scale.
     *
     * <p>The 1024 px value is the small-panel guard. The encoder used to inherit it, so a 1900 px
     * layer exported from a ~1024 px raster upscaled back to full size — soft, and worse once
     * glow/blur padding enlarged the rectangle. Any non-preview render may use at least the
     * composition's long edge. A preview follows its actual device-pixel target instead of a fixed
     * guard: maximizing/fullscreening used to keep every baked 3D/text/effect layer at 1024 while
     * the composition FBO was 1920+, which is the reported fullscreen mosaic. The signature
     * contains the pixel size, so each variant keeps a separate cache entry.</p>
     */
    static float rasterMaxEdge(CompRenderContext context)
    {
        if (context == null)
        {
            return MAX_EDGE;
        }

        if (context.targetEdge > 0)
        {
            /* Explicit request (pre-render cache 1280, AE encoder frame size, …): the source raster
             * must not be capped by the draft guard. */
            return context.targetEdge;
        }

        if (context.preview)
        {
            /* The draft guard was a fixed 1024 px. That is fine for a small panel, but once the
             * preview is maximized/fullscreen the composition FBO can be 1920+ device pixels wide
             * while every baked 3D/text/effect raster stayed at 1024 — the layer was then upscaled
             * ~1.9× and read as mosaic. Follow the actual preview target, capped at 4K so an 8K
             * composition cannot allocate an 8K raster per layer. */
            int target = Math.max(context.renderWidth(), context.renderHeight());

            return Math.max(MAX_EDGE, Math.min(4096, target));
        }

        return Math.max(MAX_EDGE, Math.min(4096,
            Math.max(Math.max(context.width, context.height),
                Math.max(context.renderWidth(), context.renderHeight()))));
    }

    /**
     * Whether the free 3D workspace draft can show this effect type.
     *
     * <p>The draft draws one cached layer raster per layer quad: layer-local effects, masks and
     * styles are baked into that raster, but effects that need the projected composition (repeat
     * family, shape field, {@code Comp}-sampling AM bodies, shake, depth_*), track mattes and blend
     * modes are composition passes and are not run there. This helper documents/locks that known
     * limitation for the viewport and its HUD; the composition preview and the encoder always run
     * the full stack.</p>
     */
    static boolean draftApplies(String type)
    {
        return type != null && !CompRenderer.requiresProjectedPass(type);
    }

    /** Pure raster-scale helper (long edge cap / layer rectangle long edge). */
    static float rasterScaleFor(float rectWidth, float rectHeight, float maxEdge)
    {
        return rasterScaleFor(rectWidth, rectHeight, maxEdge, 1F);
    }

    /**
     * Raster pixels per layer pixel for a target long edge and a preview output scale.
     *
     * <p>At output scale 1 this is the historical "never upscale past the layer's own pixels" rule.
     * A supersampled preview (1.5×/2×) needs the layer raster to grow with it, otherwise a 1280 px
     * layer is baked at 1280 and then upscaled onto the 1920 px composition target. The scale is
     * capped by the output scale, so a 100 px layer in a 1920 px target is not rasterized 19×
     * larger. Export (scale 1) keeps its exact previous resolution.</p>
     */
    static float rasterScaleFor(float rectWidth, float rectHeight, float maxEdge, float outputScale)
    {
        float longest = Math.max(Math.max(1F, rectWidth), Math.max(1F, rectHeight));
        float cap = Math.max(1F, Math.min(CompRenderer.MAX_PREVIEW_SCALE, outputScale));

        return Math.max(0F, Math.min(cap, maxEdge / longest));
    }

    private static boolean hasPixels(CompLayer layer)
    {
        String type = layer.type == null ? "solid" : layer.type;

        return !"camera".equals(type) && !"null".equals(type) && !"adjustment".equals(type);
    }

    private static final class Rect
    {
        float x;
        float y;
        float width;
        float height;
    }

    /**
     * Orthographic projection for one layer-local rectangle.
     *
     * <p>Deliberately in <b>logical layer pixels</b>. The framebuffer viewport already maps this
     * rectangle onto the (possibly capped) raster; scaling the projection as well zoomed the
     * content and clipped it, which is what made a layer jump in size when an effect switched it
     * to the bake path. Package-visible for the regression test.</p>
     */
    static Matrix4f logicalProjection(float x, float y, float width, float height)
    {
        return new Matrix4f().ortho(x, x + width, y + height, y, -1000F, 1000F);
    }

    /**
     * Local rectangle covered by the layer's content.
     *
     * <p>The anchor decides the origin; shape layers can exceed the layer box because their raster
     * is allowed to grow (stroke, trim, repeater), so the union is taken. Everything else is the
     * content rectangle the inspector edits.</p>
     */
    private static Rect bounds(CompLayer layer, CompRenderContext context)
    {
        float frame = context.frame;
        Rect rect = new Rect();

        rect.width = Math.max(1F, layer.width);
        rect.height = Math.max(1F, layer.height);
        rect.x = -rect.width * layer.anchorX;
        rect.y = -rect.height * layer.anchorY;

        if ("shape".equals(layer.type))
        {
            /* The shape raster is the authority on where shape pixels live (stroke, trim and
             * repeater can all exceed the layer box). It is cached by content, so asking for it
             * here is a lookup, not a second rasterization. */
            ShapeCache.Raster raster = ShapeCache.get(layer, frame, MaskCache.cacheScope(context.project, context.comp));

            if (raster != null)
            {
                float maxX = Math.max(rect.x + rect.width, raster.originX + raster.width);
                float maxY = Math.max(rect.y + rect.height, raster.originY + raster.height);

                rect.x = Math.min(rect.x, raster.originX);
                rect.y = Math.min(rect.y, raster.originY);
                rect.width = Math.max(1F, maxX - rect.x);
                rect.height = Math.max(1F, maxY - rect.y);
            }
        }
        else if (("image".equals(layer.type) || "footage".equals(layer.type)) && layer.imageFit == 1)
        {
            /* "Fill / cover" scales the image until it covers the box, so the drawn rectangle is
             * larger than the box on one axis. Rasterizing only the box cropped the top/bottom of
             * a Fill image (and of the exported frame of a 3D layer with effects). */
            ImageTextureCache.Entry image = ImageTextureCache.get(
                context.project == null ? "" : context.project.id,
                layer.imagePath == null ? "" : layer.imagePath);

            if (image != null && image.width > 0 && image.height > 0)
            {
                float scale = Math.max(layer.width / image.width, layer.height / image.height);
                float w = image.width * scale;
                float h = image.height * scale;
                float x0 = -w * layer.anchorX;
                float y0 = -h * layer.anchorY;
                float minX = Math.min(rect.x, x0);
                float minY = Math.min(rect.y, y0);
                float maxX = Math.max(rect.x + rect.width, x0 + w);
                float maxY = Math.max(rect.y + rect.height, y0 + h);

                rect.x = minX;
                rect.y = minY;
                rect.width = Math.max(1F, maxX - minX);
                rect.height = Math.max(1F, maxY - minY);
            }
        }

        /* The raster is the effect kernel's support. Without this margin a glow/blur/shadow is
         * cut at the layer rectangle — the effects looked "not applied" on 3D layers because the
         * visible part was only the layer box. */
        float padding = EffectPadding.of(layer, frame);

        if (padding > 0F)
        {
            rect.x -= padding;
            rect.y -= padding;
            rect.width += padding * 2F;
            rect.height += padding * 2F;
        }

        return rect;
    }

    private static Entry rasterize(CompLayer layer, CompRenderContext context, Batcher2D batcher,
                                   Rect rect, float scale, int pixelWidth, int pixelHeight, long signature,
                                   GlFramebuffer reuseContent, GlFramebuffer reuseStraightTarget)
    {
        batcher.flush();
        BufferRenderer.resetCurrentVertexBuffer();

        int previousFramebuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];

        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, previousViewport);

        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        MatrixStack modelView = RenderSystem.getModelViewStack();
        boolean reused = reuseContent != null
            && reuseContent.width == pixelWidth && reuseContent.height == pixelHeight;
        GlFramebuffer target = reused ? reuseContent : FboPool.acquire(pixelWidth, pixelHeight);
        GlFramebuffer staging = null;

        modelView.push();
        modelView.peek().getPositionMatrix().identity();
        RenderSystem.applyModelViewMatrix();

        /* The batcher carries the *UI's* matrix stack (the preview translates by its own origin
         * before drawing the composition). Layer content must be rasterized in layer space, so the
         * stack is reset for the duration of the pass and restored afterwards. */
        MatrixStack batcherStack = batcher.getContext().getMatrices();

        batcherStack.push();
        batcherStack.peek().getPositionMatrix().identity();

        try
        {
            target.begin(0);

            /* The FBO covers the local rect [x, x+w] × [y, y+h]; the projection is that rect in
             * layer pixels, so content is drawn exactly where it lives in the layer. No model
             * matrix: the renderer's own "anchor at the origin" convention is the coordinate
             * system, which is what makes this texture independent of the camera.
             *
             * The projection is in LOGICAL layer pixels, not `rect * scale`. The viewport (and the
             * FBO) already scale the drawn rectangle down to `pixelWidth/pixelHeight`; multiplying
             * the projection by the raster scale as well zoomed the content by 1/scale and clipped
             * its edges. That was the "adding any effect to a 3D/precomp layer enlarges it" bug:
             * effects switch the layer from the direct path to this bake, and the bake's visible
             * rect was smaller than the layer by exactly the cap factor. */
            Matrix4f projection = logicalProjection(rect.x, rect.y, rect.width, rect.height);

            /* A precomp is a real nested composition in every mode, including the free 3D
             * workspace. The old blue "预合成（草稿）" plate was an opaque rectangle that did not
             * match the precomp's own content, alpha or transform; users correctly read it as the
             * precomposed layer being broken. Correctness wins over the cheap preview here. */
            LayerContentRenderer.draw(layer, context, batcher, new Matrix4f(), projection);

            batcher.flush();
            BufferRenderer.resetCurrentVertexBuffer();

            /* BBS draws straight-alpha sources with {@code SRC_ALPHA} into a transparent target, so
             * the raster is premultiplied. Consumers blend with straight alpha, therefore the raster
             * is converted once here (not per frame): premultiplied pixels drawn straight look dark,
             * which is what turned gradient fills and the sky of the BBS preview layer black. */
            boolean reuseStraight = reuseStraightTarget != null
                && reuseStraightTarget.width == pixelWidth && reuseStraightTarget.height == pixelHeight;
            GlFramebuffer straight;

            if (reuseStraight)
            {
                straight = PassRenderer.unpremultiplyInto(target, reuseStraightTarget);
                /* The premultiplied target survives as the staging buffer for the next rebuild. */
                staging = straight == target ? null : target;
            }
            else
            {
                straight = PassRenderer.unpremultiply(target);

                if (straight != target)
                {
                    /* The un-premultiplied copy is the raster now; the staging target goes back to
                     * the pool (a dangling reference here would be released twice later). */
                    FboPool.release(target);
                    staging = null;
                }
            }

            /* applyEffects takes ownership of its input: when a pass produces a new target it
             * releases the previous one itself. Releasing `straight` here as well put the same
             * framebuffer in the pool twice, and two later consumers rendered into one texture —
             * the "UI flickers during playback" corruption the pool guard now reports. */
            GlFramebuffer result = applyPixelOps(layer, straight, rect, scale, context);

            if (result != straight)
            {
                /* The reused staging buffer cannot be handed back any more: release it here or it
                 * leaks one raster-sized FBO per rebuild (animated layer + a style/mask). */
                releaseStaging(staging, straight, result);
                straight = result;
                staging = null;
            }

            /* Mask / Exclude: the matte layer's alpha multiplies (or inverts into) this raster.
             * Doing it here — instead of at composite time — is what lets the 3D viewport show it:
             * the draft never composites, it projects textures. */
            GlFramebuffer masked = applyMatte(layer, straight, rect, scale, context, batcher);

            if (masked != straight)
            {
                releaseStaging(staging, straight, masked);
                straight = masked;
                staging = null;
            }

            target = straight;
        }
        catch (RuntimeException e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Viewport source raster failed for layer {}", layer.id, e);

            /* The target was acquired above; an exception here used to leak one raster-sized FBO
             * per failure (a broken layer re-rasterized every frame). */
            FboPool.release(target);

            return null;
        }
        finally
        {
            batcherStack.pop();
            modelView.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFramebuffer);
            org.lwjgl.opengl.GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }

        return new Entry(target, staging, rect.x, rect.y, rect.width, rect.height, signature);
    }

    /**
     * Releases the reused staging buffer when a pixel-op produced a new target.
     *
     * <p>On the reuse path the entry's two buffers are swapped into the new raster instead of being
     * released. As soon as a style or a mask allocates another target, the staging buffer can no
     * longer be handed back, and dropping the local reference leaked it.</p>
     */
    private static void releaseStaging(GlFramebuffer staging, GlFramebuffer replaced, GlFramebuffer result)
    {
        if (staging != null && staging != replaced && staging != result)
        {
            FboPool.release(staging);
        }
    }

    /**
     * Effects, baked into the layer-local raster.
     *
     * <p>The composition pipeline runs effects in <b>composition</b> space for 3D layers (see
     * {@link EffectSpace#of}) because that is where it rasterizes them. The viewport rasterizes in
     * layer space, so a gradient must be anchored to the layer's own rectangle — otherwise moving
     * the camera would slide the gradient across the quad. The mapping is therefore explicit:
     * local pixels → raster pixels via {@code scale}, and the proxy composition is exactly the
     * raster size.</p>
     *
     * <p>Depth effects are skipped (they need the world depth buffer, which the viewport does not
     * carry); shake is a transform, not a pixel pass.</p>
     */
    private static GlFramebuffer applyPixelOps(CompLayer layer, GlFramebuffer content, Rect rect, float scale,
                                               CompRenderContext parent)
    {
        if (content == null)
        {
            return content;
        }

        /* The 蒙版 / 效果 A/B switches in the preview bar must reach the baked path too. Before
         * this the proxy context re-defaulted both flags to true, so a 3D layer kept its masks when
         * 蒙版 was off and lost its masks when 效果 was off. */
        boolean hasMasks = parent.renderMasks && layer.masks != null && !layer.masks.isEmpty();
        boolean hasEffects = parent.renderEffects && layer.effects != null && !layer.effects.isEmpty();
        boolean hasStyles = parent.renderEffects && layer.styles != null && !layer.styles.isEmpty();

        if (!hasEffects && !hasStyles && !hasMasks)
        {
            return content;
        }

        CompDocument proxy = new CompDocument();

        proxy.id = "viewport/" + layer.id;
        proxy.width = content.width;
        proxy.height = content.height;
        proxy.fps = parent.comp.fps;
        proxy.duration = parent.comp.duration;

        CompRenderContext context = new CompRenderContext(parent.project, proxy, parent.time,
            parent.projection, parent.camera, parent.depth);

        /* The proxy context must inherit the editor flags: preview placeholders are editor-only,
         * and the encoder render may run at a different raster cap. */
        context.preview = parent.preview;
        context.draftViewport = parent.draftViewport;

        Matrix4f world = new Matrix4f().scale(scale).translate(-rect.x, -rect.y, 0F);

        /* Effect pixel units are authored in composition pixels. The bake raster is capped and
         * scaled, so convert through raster px / parent comp px (the post-projection effects on the
         * same 3D layer already run on that grid). */
        float effectScale = content.width / (float) Math.max(1, parent.comp.width);

        /* The effect space is the LAYER rect, not the padded raster: the padding exists so a blur
         * can spread, but generative effects (gradient, checkerboard, circle, grid, halftone) must
         * keep covering exactly the layer or they shift and stretch whenever a glow is added. */
        float pad = EffectPadding.of(layer, parent.frame);
        float bx = rect.x + pad;
        float by = rect.y + pad;
        float bw = Math.max(1F, rect.width - pad * 2F);
        float bh = Math.max(1F, rect.height - pad * 2F);
        EffectSpace space = EffectSpace.ofBounds(layer, world, context, bx, by, bw, bh,
            Math.max(1, parent.comp.width), Math.max(1, parent.comp.height));
        GlFramebuffer current = content;

        /* Masks are layer pixels, so they belong in the same raster and before the effects: a
         * glow/stroke on a masked layer must be able to spread outside the mask, which is exactly
         * the 2D order. Before this the 3D path simply ignored masks. */
        if (hasMasks)
        {
            MaskCache.MaskTexture mask = MaskCache.get(layer, current.width, current.height, world, parent.frame, scale,
                MaskCache.cacheScope(parent.project, parent.comp));

            if (mask != null)
            {
                GlFramebuffer masked = PassRenderer.mask(current, mask.texture.id, 0);

                if (masked != null && masked != current)
                {
                    FboPool.release(current);
                    current = masked;
                }
            }
        }

        boolean previousLocalPass = context.layerLocalPass;

        context.layerLocalPass = true;

        try
        {
        if (hasEffects)
        {
        /* Split point: the bake covers the leading run of layer-space effects; the rest of the
         * stack runs after projection in its original order. */
        int projectedStart = CompRenderer.firstProjectedEffect(layer.effects);

        for (int effectIndex = 0; effectIndex < layer.effects.size(); effectIndex++)
        {
            CompEffect effect = layer.effects.get(effectIndex);

            if (effect == null || !effect.enabled || effectIndex >= projectedStart)
            {
                continue;
            }

            String type = effect.type == null ? "" : effect.type;

            /* Extrusion is geometry, not a pixel filter: the raster stays the flat layer and
             * CompRenderer stacks it along local Z when drawing the 3D quad. Applying it here would
             * only produce one flattened copy and clip the volume at the raster edge. */
            if ("extrude3d".equals(type))
            {
                continue;
            }

            try
            {
                CompEffect resolved = scaleEffectForRaster(layer.resolveEffect(effect, parent.frame), effectScale);
                GlFramebuffer next = EffectRenderer.apply(layer, resolved, current, context, space);

                if (next != current)
                {
                    FboPool.release(current);
                    current = next;
                }
            }
            catch (RuntimeException e)
            {
                dev.bbsae.BbsAeAddon.LOGGER.error("Viewport effect {} failed on layer {}", type, layer.id, e);
            }
        }
        }
        }
        finally
        {
            context.layerLocalPass = previousLocalPass;
        }

        /* Styles are part of what the layer looks like, not a post-projection annotation: a drop
         * shadow must be clipped by the same raster margin (EffectPadding) and must follow the
         * plane in 3D. Baking them here makes the viewport and the final 3D frame agree, and lets
         * the composition skip its after-projection style pass for this layer.
         *
         * <p>Exception: when the stack has effects that run after the projection, baking the styles
         * here would grade the styled image instead of styling the graded one (the 2D path applies
         * effects first and styles last). Those styles are deferred to the composition pass; the
         * composition asks the same helper.</p> */
        if (hasStyles && !CompRenderer.stylesRunAfterProjection(layer))
        {
            for (CompStyle style : layer.styles)
            {
                if (style == null || !style.enabled)
                {
                    continue;
                }

                try
                {
                    GlFramebuffer next = StyleRenderer.apply(scaleStyleForRaster(style, scale), current, context, layer, world);

                    if (next != current)
                    {
                        FboPool.release(current);
                        current = next;
                    }
                }
                catch (RuntimeException e)
                {
                    dev.bbsae.BbsAeAddon.LOGGER.error("Viewport style {} failed on layer {}", style.type, layer.id, e);
                }
            }
        }

        return current;
    }

    /**
     * Converts pixel-space effect radii from layer pixels to raster pixels.
     *
     * <p>The raster is capped at {@link #MAX_EDGE}; a 600 px layer may be rasterized at half
     * resolution. A 40 px glow is then 40 raster pixels — twice as large in the composition — and
     * the {@link EffectPadding} margin (computed in layer pixels) is only half as wide as the
     * kernel, so the effect is clipped again. Multiplying the radius by the raster scale keeps the
     * visual radius in layer pixels and makes the margin exactly cover the kernel.</p>
     *
     * <p>Deliberately NOT scaled here: every parameter the <b>shader</b> already measures through
     * the layer basis (LayerSize / LayerX / LayerY). The DISTORT family's amounts, the PIXEL
     * family's mosaic/scanline/glitch sizes and the generators' cell sizes are all expressed in
     * layer pixels and converted inside GLSL; scaling them here double-counted, so 波浪 / 置换图 /
     * 湍流置换 shrank with the proxy and a 3D layer's checkerboard grew at small rasters.</p>
     */
    static CompEffect scaleEffectForRaster(CompEffect effect, float scale)
    {
        if (effect == null || scale >= 0.999F)
        {
            return effect;
        }

        CompEffect scaled = effect.copy();
        String type = scaled.type == null ? "" : scaled.type;

        /* The data-driven blur family runs through the separable FBO blur, whose radius is a
         * framebuffer-pixel count. On a proxy raster that radius has to travel to raster pixels,
         * otherwise a 12 px reference blur becomes 12/s raster px and the reserved margin no
         * longer covers the kernel. */
        dev.bbsae.comp.AmEffectDef definition = dev.bbsae.comp.AmEffectLibrary.get(type);

        if (definition != null && definition.blurKey != null)
        {
            scaleParam(scaled, definition.blurKey, scale);
        }

        /* Classic effects convert their pixel-authored parameters through
         * {@link EffectRenderer#proxyScale(EffectSpace, GlFramebuffer)} (ref grid = parent
         * composition now), so pre-scaling them here would apply the factor twice. Only the
         * separable AM blur hands its radius straight to {@code PassRenderer.blur}, which counts
         * framebuffer pixels. */

        return scaled;
    }

    private static void scaleParam(CompEffect effect, String key, float scale)
    {
        Double value = effect.params.get(key);

        if (value != null)
        {
            effect.params.put(key, value * (double) scale);
        }
    }

    /**
     * The same conversion for styles, whose shadow/glow/stroke sizes are fields rather than effect
     * parameters.
     */
    static CompStyle scaleStyleForRaster(CompStyle style, float scale)
    {
        if (style == null || scale >= 0.999F)
        {
            return style;
        }

        CompStyle scaled = new CompStyle(style.type);

        scaled.enabled = style.enabled;
        scaled.color = style.color;
        scaled.color2 = style.color2;
        scaled.opacity = style.opacity;
        scaled.size = style.size * scale;
        scaled.distance = style.distance * scale;
        scaled.angle = style.angle;
        scaled.blur = style.blur * scale;
        scaled.blendMode = style.blendMode;

        return scaled;
    }

    /** Guards against a matte cycle (A masks B, B masks A). */
    private static int matteDepth;

    /**
     * Multiplies the raster by the matte layer's alpha (intersection) or by its inverse
     * (difference).
     *
     * <p>The matte's source texture is drawn into this layer's raster with a "destination in
     * source" blend ({@code ZERO, SRC_ALPHA} / {@code ZERO, ONE_MINUS_SRC_ALPHA}), transformed by
     * the relative matrix between the two layers. That is exactly the set operation the user sees:
     * alpha AND (mask) or alpha AND NOT (exclude).</p>
     */
    /**
     * Draft matte is disabled until its sampling is right.
     *
     * <p>The first working version multiplied by the matte raster but produced a speckled edge:
     * the matte texture is sampled across the layer-local rectangle, and that mapping is not
     * exact when the two layers have different raster sizes. The composition pipeline (and the
     * export) applies masks exactly, so the draft stays unmasked rather than showing corruption;
     * the HUD already says the 3D view is a draft, and pressing 0 shows the real frame.</p>
     */
    private static final boolean DRAFT_MATTE = false;

    private static GlFramebuffer applyMatte(CompLayer layer, GlFramebuffer content, Rect rect, float scale,
                                            CompRenderContext context, Batcher2D batcher)
    {
        if (!DRAFT_MATTE || content == null || !layer.trackMatteEnabled || layer.trackMatteId == null || layer.trackMatteId.isEmpty()
            || matteDepth > 1)
        {
            return content;
        }

        CompLayer matte = context.comp.getLayer(layer.trackMatteId);

        if (matte == null || matte == layer || "camera".equals(matte.type) || "adjustment".equals(matte.type))
        {
            return content;
        }

        matteDepth++;

        Entry source;

        try
        {
            source = get(matte, context, batcher);
        }
        finally
        {
            matteDepth--;
        }

        if (source == null)
        {
            return content;
        }

        /* Layer-local -> matte-local -> back: inv(inv(W_matte) · W_layer) = inv(W_layer) · W_matte. */
        Matrix4f relative = new Matrix4f(CompScene.world(context, layer)).invert()
            .mul(CompScene.world(context, matte));

        int previousFramebuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        MatrixStack stack = batcher.getContext().getMatrices();

        try
        {
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, content.id);
            org.lwjgl.opengl.GL11.glViewport(0, 0, content.width, content.height);
            /* Same projection the content was drawn with: logical layer pixels mapped into the
             * raster by the viewport. The scaled variant shrank the matte by the raster cap. */
            RenderSystem.setProjectionMatrix(logicalProjection(rect.x, rect.y, rect.width, rect.height),
                VertexSorter.BY_Z);

            boolean exclude = layer.trackMatteMode == CompLayer.MATTE_ALPHA_INVERTED
                || layer.trackMatteMode == CompLayer.MATTE_LUMA_INVERTED;

            RenderSystem.enableBlend();
            /* dst *= src.a  (mask)  /  dst *= 1 - src.a  (exclude). */
            org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_ZERO,
                exclude ? org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA : org.lwjgl.opengl.GL11.GL_SRC_ALPHA);

            /* Raw GL, not the batcher: `DrawContext.drawTexture` resets the blend function to
             * SRC_ALPHA/ONE_MINUS_SRC_ALPHA, which silently undid the destination-in blend this
             * pass depends on (the mask drew normally instead of multiplying — "mask does nothing"). */
            emitMatteQuad(relative, source);
        }
        finally
        {
            stack.pop();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFramebuffer);
        }

        return content;
    }

    /** One textured quad, transformed by the relative matrix, with the caller's blend still set. */
    private static void emitMatteQuad(Matrix4f relative, Entry source)
    {
        Matrix4f matrix = new Matrix4f(relative);
        float x0 = source.localX;
        float y0 = source.localY;
        float x1 = x0 + source.localWidth;
        float y1 = y0 + source.localHeight;
        float u0 = 0F;
        float u1 = 1F;
        float vTop = 1F;
        float vBottom = 0F;

        RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, source.texture.color.id);

        net.minecraft.client.render.BufferBuilder buffer = net.minecraft.client.render.Tessellator.getInstance().getBuffer();

        buffer.begin(net.minecraft.client.render.VertexFormat.DrawMode.TRIANGLES,
            net.minecraft.client.render.VertexFormats.POSITION_TEXTURE_COLOR);

        int color = 0xFFFFFFFF;

        buffer.vertex(matrix, x0, y0, 0F).texture(u0, vTop).color(color).next();
        buffer.vertex(matrix, x1, y0, 0F).texture(u1, vTop).color(color).next();
        buffer.vertex(matrix, x1, y1, 0F).texture(u1, vBottom).color(color).next();

        buffer.vertex(matrix, x0, y0, 0F).texture(u0, vTop).color(color).next();
        buffer.vertex(matrix, x1, y1, 0F).texture(u1, vBottom).color(color).next();
        buffer.vertex(matrix, x0, y1, 0F).texture(u0, vBottom).color(color).next();

        net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buffer.end());
        net.minecraft.client.render.BufferRenderer.resetCurrentVertexBuffer();
    }

    /**
     * Kept for the audit API and old callers: the 3D workspace no longer substitutes a plate for a
     * precomp. The real nested composition is drawn in every mode.
     */
    static boolean wantsPrecompPlate(CompLayer layer, CompRenderContext context)
    {
        return false;
    }

    private static void release(Entry entry)
    {
        if (entry.staging != null && entry.staging != entry.texture)
        {
            FboPool.release(entry.staging);
        }

        if (entry.texture != null)
        {
            FboPool.release(entry.texture);
        }
    }

    private static void trim()
    {
        long bytes = 0;

        for (Entry entry : CACHE.values())
        {
            bytes += (long) entry.texture.width * entry.texture.height * 4L;
        }

        if (CACHE.size() <= MAX_ENTRIES && bytes <= BUDGET_BYTES)
        {
            return;
        }

        Iterator<Map.Entry<String, Entry>> iterator = CACHE.entrySet().iterator();

        while ((CACHE.size() > 4) && (bytes > BUDGET_BYTES || CACHE.size() > MAX_ENTRIES) && iterator.hasNext())
        {
            Entry entry = iterator.next().getValue();

            bytes -= (long) entry.texture.width * entry.texture.height * 4L;
            iterator.remove();
            release(entry);
        }
    }

    /* ------------------------------------------------------------- signature */

    /**
     * Fingerprint of <b>content only</b>.
     *
     * <p>Position, rotation, scale, opacity and blend mode are excluded on purpose: they are
     * applied by the quad pass, so dragging a layer must not re-rasterize it. Anything that changes
     * a pixel of the source (text, colour, size, shapes, the frame a footage layer samples) is
     * included.</p>
     */
    static long signature(CompLayer layer, CompRenderContext context, int pixelWidth, int pixelHeight)
    {
        return signature(layer, context, pixelWidth, pixelHeight, 0, new java.util.HashSet<>());
    }

    /**
     * Recursive variant: a precomp layer's raster contains the nested composition, so editing a
     * child layer must change this layer's content fingerprint too. Same depth cap (8) and cycle
     * guard as the render graph.
     */
    private static long signature(CompLayer layer, CompRenderContext context, int pixelWidth, int pixelHeight,
                                  int depth, java.util.Set<String> stack)
    {
        long h = 0x9E3779B97F4A7C15L;
        float frame = context.frame;

        h = mix(h, layer.type == null ? 0 : layer.type.hashCode());
        h = mix(h, pixelWidth);
        h = mix(h, pixelHeight);

        /* Effect pixel parameters are authored against the composition grid (see EffectSpace's
         * refWidth/refHeight): resizing the composition must rebuild the baked raster or a 3D
         * layer keeps the old effect radii. The raster cap is part of the same grid decision. */
        h = mix(h, context.comp == null ? 0 : context.comp.width);
        h = mix(h, context.comp == null ? 0 : context.comp.height);
        h = mix(h, (long) rasterMaxEdge(context));

        /* The frame index only enters the fingerprint when the layer's *content* is time dependent.
         * Mixing it in unconditionally rebuilt every solid/image/text layer on every frame of
         * playback — the whole composition re-rasterized 30 times a second for nothing. */
        if (isTimeDependent(layer))
        {
            h = mix(h, Math.round(frame));
        }

        h = mix(h, layer.width);
        h = mix(h, layer.height);
        h = mix(h, layer.anchorX);
        h = mix(h, layer.anchorY);
        h = mix(h, layer.color);
        h = mix(h, layer.strokeColor);
        h = mix(h, layer.strokeWidth);
        h = mix(h, layer.fill);
        /* Content-level text effects are part of the drawn string; see TextContent.contentHash. */
        h = mix(h, dev.bbsae.comp.TextContent.contentHash(layer));
        h = mix(h, layer.text == null ? 0 : layer.text.hashCode());
        h = mix(h, layer.fontName == null ? 0 : layer.fontName.hashCode());
        h = mix(h, FontCoverage.contentId(layer.fontName));
        h = mix(h, layer.fontSizeAt(frame));
        h = mix(h, layer.trackingAt(frame));
        h = mix(h, layer.lineHeightAt(frame));
        h = mix(h, layer.textStrokeAt(frame));
        h = mix(h, layer.align);
        h = mix(h, layer.wrap ? 1 : 0);
        /* The wrap box decides how the text is laid out, so it changes pixels; leaving it out
         * meant the 文本框宽度 slider did nothing until some other edit invalidated the raster. */
        h = mix(h, layer.textBoxWidth);
        h = mix(h, layer.textBoxHeight);
        h = mix(h, layer.imagePath == null ? 0 : layer.imagePath.hashCode());
        h = mix(h, ImageTextureCache.contentId(context.project == null ? "" : context.project.id,
            layer.imagePath));
        h = mix(h, layer.imageFit);
        /* Filtering is applied while the source raster is built, so a policy change must rebuild
         * it. AUTO depends on the source and the raster size, both already in the fingerprint. */
        h = mix(h, TextureFilter.resolveMode(layer, context.project));
        h = mix(h, layer.precompId == null ? 0 : layer.precompId.hashCode());
        h = mix(h, layer.entityScope);
        h = mix(h, layer.entityIds == null ? 0 : layer.entityIds.hashCode());
        h = mix(h, layer.entityOcclusion ? 1 : 0);
        h = mix(h, layer.entityErode);
        h = mix(h, layer.entityFeather);
        h = mix(h, layer.entityTolerance);
        h = mix(h, layer.trackMatteEnabled ? 1 : 0);
        h = mix(h, layer.squash);

        /* The preview bar's 效果/蒙版 switches change what is baked into this raster, so they
         * belong to the fingerprint (otherwise toggling them served the old pixels). */
        h = mix(h, context.renderEffects ? 1 : 0);
        h = mix(h, context.renderMasks ? 1 : 0);

        /* The editor mode is baked into this raster too: editor-only placeholders and the
         * BBS-preview waiting plate differ from the encoder, while precomps now render their real
         * nested composition everywhere. Without these bits the first viewer won the cache and the
         * other modes showed its pixels. */
        h = mix(h, context.preview ? 1 : 0);
        h = mix(h, context.draftViewport ? 1 : 0);

        /* Masks are multiplied into the raster before the effects; their geometry and feather
         * must invalidate the cached texture just like an effect parameter does. */
        h = mix(h, layer.masks == null ? 0 : layer.masks.size());

        if (layer.masks != null)
        {
            for (CompMask mask : layer.masks)
            {
                if (mask == null)
                {
                    h = mix(h, 0);
                    continue;
                }

                h = mix(h, mask.id == null ? 0 : mask.id.hashCode());
                h = mix(h, mask.enabled ? 1 : 0);
                h = mix(h, mask.inverted ? 1 : 0);
                h = mix(h, mask.mode);

                /* The RESOLVED mask, not the base fields: animating a mask point or its feather
                 * through the mask/<id>/... channels left the signature unchanged, so a keyed mask
                 * froze at its first frame on every baked (3D) layer, in the export too. */
                CompMask resolved = layer.maskAt(mask, frame);

                h = mix(h, resolved.opacity);
                h = mix(h, resolved.feather);
                h = mix(h, resolved.expansion);
                /* The pen tool can open/close a path; an open path does not mat, so the baked
                 * layer raster must be rebuilt when this toggles. */
                h = mix(h, resolved.closed ? 1 : 0);

                for (CompMask.CompMaskPoint point : resolved.points)
                {
                    h = mix(h, point.x);
                    h = mix(h, point.y);
                    h = mix(h, point.inX);
                    h = mix(h, point.inY);
                    h = mix(h, point.outX);
                    h = mix(h, point.outY);
                }
            }
        }

        /* Effects are baked into this raster. Leaving them out of the fingerprint was the reason
         * every 3D-layer effect appeared "dead": changing a blur radius, adding an effect or
         * toggling one off did not change the signature, so the old unfiltered texture was served
         * forever. Include the resolved values (animated channels included) in draw order. */
        h = mix(h, layer.effects == null ? 0 : layer.effects.size());

        if (layer.effects != null)
        {
            for (CompEffect effect : layer.effects)
            {
                if (effect == null)
                {
                    h = mix(h, 0);
                    continue;
                }

                h = mix(h, effect.id == null ? 0 : effect.id.hashCode());
                h = mix(h, effect.type == null ? 0 : effect.type.hashCode());
                h = mix(h, effect.enabled ? 1 : 0);

                CompEffect resolved = layer.resolveEffect(effect, frame);

                for (java.util.Map.Entry<String, Double> entry : resolved.params.entrySet())
                {
                    h = mix(h, entry.getKey() == null ? 0 : entry.getKey().hashCode());
                    h = mix(h, entry.getValue() == null ? 0F : entry.getValue().floatValue());
                }

                for (java.util.Map.Entry<String, Integer> entry : resolved.colors.entrySet())
                {
                    h = mix(h, entry.getKey() == null ? 0 : entry.getKey().hashCode());
                    h = mix(h, entry.getValue() == null ? 0 : entry.getValue());
                }
            }
        }

        /* Styles are baked after the effects, so they belong to the same fingerprint. */
        h = mix(h, layer.styles == null ? 0 : layer.styles.size());

        if (layer.styles != null)
        {
            for (CompStyle style : layer.styles)
            {
                if (style == null)
                {
                    h = mix(h, 0);
                    continue;
                }

                h = mix(h, style.type == null ? 0 : style.type.hashCode());
                h = mix(h, style.enabled ? 1 : 0);
                h = mix(h, style.color);
                h = mix(h, style.color2);
                h = mix(h, style.opacity);
                h = mix(h, style.size);
                h = mix(h, style.distance);
                h = mix(h, style.angle);
                h = mix(h, style.blur);
                h = mix(h, style.blendMode);
            }
        }

        for (CompShape shape : layer.shapes)
        {
            if (shape == null)
            {
                continue;
            }

            h = mix(h, shape.id == null ? 0 : shape.id.hashCode());
            h = mix(h, shape.type == null ? 0 : shape.type.hashCode());
            h = mix(h, shape.enabled ? 1 : 0);
            h = mix(h, shape.operator);
            h = mix(h, shape.closed ? 1 : 0);
            h = mix(h, shape.x);
            h = mix(h, shape.y);
            h = mix(h, shape.width);
            h = mix(h, shape.height);
            h = mix(h, shape.roundness);
            h = mix(h, shape.rotation);
            h = mix(h, shape.sides);
            h = mix(h, shape.innerRadius);
            h = mix(h, shape.outerRadius);
            h = mix(h, shape.fillColor);
            h = mix(h, shape.fillColor2);
            h = mix(h, shape.fillGradient ? 1 : 0);
            h = mix(h, shape.fillGradientAngle);
            h = mix(h, shape.strokeColor);
            h = mix(h, shape.strokeWidth);
            h = mix(h, shape.trimStart);
            h = mix(h, shape.trimEnd);
            h = mix(h, shape.trimOffset);
            h = mix(h, shape.cornerStyle);
            h = mix(h, shape.repeaterCount);
            h = mix(h, shape.repeaterOffsetX);
            h = mix(h, shape.repeaterOffsetY);
            h = mix(h, shape.repeaterScale);
            h = mix(h, shape.repeaterRotation);
            h = mix(h, shape.repeaterOpacity);

            if (shape.corners != null)
            {
                for (Float corner : shape.corners)
                {
                    h = mix(h, corner == null ? 0F : corner);
                }
            }

            for (CompShape.Point point : shape.points)
            {
                h = mix(h, point.x);
                h = mix(h, point.y);
                h = mix(h, point.inX);
                h = mix(h, point.inY);
                h = mix(h, point.outX);
                h = mix(h, point.outY);
            }
        }

        for (CompTextAnimator animator : layer.textAnimators)
        {
            if (animator == null)
            {
                continue;
            }

            h = mix(h, animator.enabled ? 1 : 0);
            h = mix(h, animator.start);
            h = mix(h, animator.end);
            h = mix(h, animator.offset);
            h = mix(h, animator.positionX);
            h = mix(h, animator.positionY);
            h = mix(h, animator.scale);
            h = mix(h, animator.rotation);
            h = mix(h, animator.opacity);
            h = mix(h, animator.useColor ? animator.color : 0);
            h = mix(h, animator.wiggle ? 1 : 0);
            h = mix(h, animator.randomness);
            h = mix(h, animator.seed);
            h = mix(h, animator.randomOrder ? 1 : 0);
        }

        /* Animated text/layout channels: sampling them at this frame is what makes a keyframed
         * font size or tracking repaint the texture. */
        h = mix(h, layer.scalarAt("text/fontSize", layer.fontSize, frame));
        h = mix(h, layer.scalarAt("text/tracking", layer.tracking, frame));
        h = mix(h, layer.scalarAt("text/lineHeight", layer.lineHeight, frame));
        h = mix(h, layer.scalarAt("text/strokeWidth", layer.strokeWidth, frame));

        if ("footage".equals(layer.type))
        {
            h = mix(h, Math.round(layer.sourceTimeAt(context.time, frame) * Math.max(1F, context.comp.fps)));
        }

        /* A mask depends on the matte layer's pixels *and* on both layers' transforms (the content
         * signature deliberately ignores transforms, so they are folded in here for mattes). */
        if (layer.trackMatteEnabled && layer.trackMatteId != null && !layer.trackMatteId.isEmpty())
        {
            CompLayer matte = context.comp.getLayer(layer.trackMatteId);

            h = mix(h, layer.trackMatteMode);
            h = mix(h, layer.x);
            h = mix(h, layer.y);
            h = mix(h, layer.rotation);
            h = mix(h, layer.scaleX);
            h = mix(h, layer.scaleY);

            if (matte != null && matte != layer)
            {
                h = mix(h, matte.id.hashCode());
                h = mix(h, matte.x);
                h = mix(h, matte.y);
                h = mix(h, matte.rotation);
                h = mix(h, matte.scaleX);
                h = mix(h, matte.scaleY);
                h = mix(h, matte.width);
                h = mix(h, matte.height);
                h = mix(h, matte.anchorX);
                h = mix(h, matte.anchorY);
                h = mix(h, matte.threeD ? 1 : 0);
                h = mix(h, matte.scalarAt("z", matte.z, frame));
            }
        }

        h = precompContent(h, context, layer, frame, pixelWidth, pixelHeight, depth, stack);

        return h;
    }

    /**
     * Mixes the nested composition's content into a precomp layer's raster fingerprint.
     *
     * <p>The raster of a precomp layer is the real nested composition (panel preview / encoder), so
     * without this the per-layer raster cache would keep serving the old nested picture after an
     * edit inside the precomp even though the composite-level frame was invalidated. Keeping the
     * draft bit in the hash also keeps the preview/export rasters distinct.</p>
     */
    private static long precompContent(long h, CompRenderContext context, CompLayer layer, float frame,
                                       int pixelWidth, int pixelHeight, int depth, java.util.Set<String> stack)
    {
        if (layer == null || !"precomp".equals(layer.type)
            || layer.precompId == null || layer.precompId.isEmpty() || context.project == null)
        {
            return h;
        }

        h = mix(h, layer.precompId.hashCode());

        CompDocument sub = context.project.getComp(layer.precompId);

        if (sub == null)
        {
            return mix(h, 21L);
        }

        if (depth >= 8 || (sub.id != null && stack.contains(sub.id)))
        {
            return mix(h, 22L);
        }

        h = mix(h, sub.width);
        h = mix(h, sub.height);
        h = mix(h, sub.fps);
        h = mix(h, sub.activeCameraId == null ? 0 : sub.activeCameraId.hashCode());

        CompRenderContext subContext = new CompRenderContext(context.project, sub, context.time, null, null, null);
        boolean pushed = sub.id != null && stack.add(sub.id);

        try
        {
            if (sub.layers == null)
            {
                return mix(h, 23L);
            }

            for (CompLayer child : sub.layers)
            {
                h = mix(h, signature(child, subContext, pixelWidth, pixelHeight, depth + 1, stack));
            }
        }
        finally
        {
            if (pushed)
            {
                stack.remove(sub.id);
            }
        }

        return h;
    }

    /**
     * True when this layer's pixels can change without any edit: footage samples another frame,
     * world/entity textures track the replay, and animated content changes what is drawn.
     */
    private static boolean isTimeDependent(CompLayer layer)
    {
        if ("footage".equals(layer.type) || "replay".equals(layer.type) || "bbs".equals(layer.type))
        {
            return true;
        }

        if (layer.textAnimators != null && !layer.textAnimators.isEmpty())
        {
            return true;
        }

        /* Content-level text effects (timecode/counter/glyph scramble/reveal) change the drawn
         * string from frame to frame without any layer edit. */
        if (dev.bbsae.comp.TextContent.isTimeDependent(layer))
        {
            return true;
        }

        if (layer.animatedPaths() != null)
        {
            for (String path : layer.animatedPaths())
            {
                if (path != null && (path.startsWith("text/") || path.startsWith("shape/")
                    || path.startsWith("fx/") || path.startsWith("mask/")))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static long mix(long hash, long value)
    {
        long h = hash ^ value;

        h *= 0x100000001B3L;
        h ^= h >>> 29;

        return h;
    }

    private static long mix(long hash, float value)
    {
        return mix(hash, Float.floatToIntBits(value));
    }

    private static long mix(long hash, int value)
    {
        return mix(hash, (long) value);
    }

    private static long mix(long hash, boolean value)
    {
        return mix(hash, value ? 1L : 0L);
    }
}
