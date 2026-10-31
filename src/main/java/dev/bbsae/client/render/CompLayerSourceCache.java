package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompShape;
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
 * <h2>What is not here</h2>
 * <p>Effects, masks, track mattes and blending modes are <b>composite</b> operations, not source
 * pixels. The viewport is a draft: it says so in the HUD. The exact frame is one key away
 * (look through the camera), and that path still runs the full graph.</p>
 */
public final class CompLayerSourceCache
{
    /** Longest side of a cached layer raster, in pixels. */
    private static final int MAX_EDGE = 1024;
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

        String key = context.comp.id + "/" + layer.id;
        Rect rect = bounds(layer, context.frame);
        float scale = Math.min(1F, MAX_EDGE / Math.max(rect.width, rect.height));
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

    /** Drops one layer's raster (used when a layer is deleted). */
    public static void invalidate(String compId, String layerId)
    {
        Entry entry = CACHE.remove(compId + "/" + layerId);

        if (entry != null)
        {
            release(entry);
        }
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
     * Local rectangle covered by the layer's content.
     *
     * <p>The anchor decides the origin; shape layers can exceed the layer box because their raster
     * is allowed to grow (stroke, trim, repeater), so the union is taken. Everything else is the
     * content rectangle the inspector edits.</p>
     */
    private static Rect bounds(CompLayer layer, float frame)
    {
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
            ShapeCache.Raster raster = ShapeCache.get(layer, frame);

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
             * system, which is what makes this texture independent of the camera. */
            Matrix4f projection = new Matrix4f().ortho(
                rect.x * scale, (rect.x + rect.width) * scale,
                (rect.y + rect.height) * scale, rect.y * scale,
                -1000F, 1000F
            );

            if ("precomp".equals(layer.type))
            {
                drawPrecompPlate(layer, batcher, rect);
            }
            else
            {
                LayerContentRenderer.draw(layer, context, batcher, new Matrix4f(), projection);
            }

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
            GlFramebuffer result = applyEffects(layer, straight, rect, scale, context);

            if (result != straight)
            {
                straight = result;
                staging = null;
            }

            /* Mask / Exclude: the matte layer's alpha multiplies (or inverts into) this raster.
             * Doing it here — instead of at composite time — is what lets the 3D viewport show it:
             * the draft never composites, it projects textures. */
            GlFramebuffer masked = applyMatte(layer, straight, rect, scale, context, batcher);

            if (masked != straight)
            {
                straight = masked;
                staging = null;
            }

            target = straight;
        }
        catch (RuntimeException e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Viewport source raster failed for layer {}", layer.id, e);

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
    private static GlFramebuffer applyEffects(CompLayer layer, GlFramebuffer content, Rect rect, float scale,
                                              CompRenderContext parent)
    {
        if (content == null || layer.effects == null || layer.effects.isEmpty())
        {
            return content;
        }

        CompDocument proxy = new CompDocument();

        proxy.id = "viewport/" + layer.id;
        proxy.width = content.width;
        proxy.height = content.height;
        proxy.fps = parent.comp.fps;
        proxy.duration = parent.comp.duration;

        CompRenderContext context = new CompRenderContext(parent.project, proxy, parent.time, null, null, null);
        Matrix4f world = new Matrix4f().scale(scale).translate(-rect.x, -rect.y, 0F);
        EffectSpace space = EffectSpace.ofBounds(layer, world, context, rect.x, rect.y, rect.width, rect.height);
        GlFramebuffer current = content;

        for (CompEffect effect : layer.effects)
        {
            if (effect == null || !effect.enabled)
            {
                continue;
            }

            String type = effect.type == null ? "" : effect.type;

            if (type.startsWith("depth_") || "shake".equals(type))
            {
                continue;
            }

            try
            {
                CompEffect resolved = layer.resolveEffect(effect, parent.frame);
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

        return current;
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
            /* Same projection the content was drawn with: layer-local pixels, scaled into the
             * raster. Using ortho(0..width) here put the matte in the raster's corner instead of on
             * the layer — the first build drew a rectangle with a bite out of its corner. */
            RenderSystem.setProjectionMatrix(new Matrix4f().ortho(
                rect.x * scale, (rect.x + rect.width) * scale,
                (rect.y + rect.height) * scale, rect.y * scale,
                -1000F, 1000F), VertexSorter.BY_Z);

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
     * A precomp is another composition, and rendering it here would run a second full graph per
     * mouse move. The draft shows a plate with the target's name instead; look-through renders the
     * real thing.
     */
    private static void drawPrecompPlate(CompLayer layer, Batcher2D batcher, Rect rect)
    {
        float x = rect.x;
        float y = rect.y;

        batcher.box(x, y, x + rect.width, y + rect.height, 0x331E88E5);
        batcher.box(x, y, x + rect.width, y + 2F, 0xAA55AAFF);
        batcher.box(x, y + rect.height - 2F, x + rect.width, y + rect.height, 0xAA55AAFF);
        batcher.textShadow("预合成（草稿）", x + 10F, y + 10F, 0xFF9CC7FF);
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
    private static long signature(CompLayer layer, CompRenderContext context, int pixelWidth, int pixelHeight)
    {
        long h = 0x9E3779B97F4A7C15L;
        float frame = context.frame;

        h = mix(h, layer.type == null ? 0 : layer.type.hashCode());
        h = mix(h, pixelWidth);
        h = mix(h, pixelHeight);

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
        h = mix(h, layer.text == null ? 0 : layer.text.hashCode());
        h = mix(h, layer.fontName == null ? 0 : layer.fontName.hashCode());
        h = mix(h, layer.fontSizeAt(frame));
        h = mix(h, layer.trackingAt(frame));
        h = mix(h, layer.lineHeightAt(frame));
        h = mix(h, layer.textStrokeAt(frame));
        h = mix(h, layer.align);
        h = mix(h, layer.wrap ? 1 : 0);
        h = mix(h, layer.imagePath == null ? 0 : layer.imagePath.hashCode());
        h = mix(h, layer.imageFit);
        h = mix(h, layer.precompId == null ? 0 : layer.precompId.hashCode());
        h = mix(h, layer.trackMatteEnabled ? 1 : 0);
        h = mix(h, layer.squash);

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

        return h;
    }

    /**
     * True when this layer's pixels can change without any edit: footage samples another frame,
     * text animators move glyphs, and keyed text/shape/effect channels change what is drawn.
     */
    private static boolean isTimeDependent(CompLayer layer)
    {
        if ("footage".equals(layer.type))
        {
            return true;
        }

        if (layer.textAnimators != null && !layer.textAnimators.isEmpty())
        {
            return true;
        }

        if (layer.animatedPaths() != null)
        {
            for (String path : layer.animatedPaths())
            {
                if (path != null && (path.startsWith("text/") || path.startsWith("shape/") || path.startsWith("fx/")))
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
