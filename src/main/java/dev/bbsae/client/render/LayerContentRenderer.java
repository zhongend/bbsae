package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompShape;
import dev.bbsae.comp.CompTextAnimator;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Draws one layer's content into a transparent composition-sized target. */
final class LayerContentRenderer
{
    private LayerContentRenderer()
    {}

    static void draw(CompLayer layer, CompRenderContext context, Batcher2D batcher, Matrix4f world)
    {
        draw(layer, context, batcher, world, null);
    }

    /**
     * @param projection null for the normal 2D ortho; a perspective matrix for a 3D layer.
     */
    static void draw(CompLayer layer, CompRenderContext context, Batcher2D batcher, Matrix4f world, Matrix4f projection)
    {
        MatrixStack stack = batcher.getContext().getMatrices();

        stack.push();
        stack.peek().getPositionMatrix().set(world);
        RenderSystem.setProjectionMatrix(projection == null
            ? GlStateGuard.ortho(context.width, context.height)
            : projection, com.mojang.blaze3d.systems.VertexSorter.BY_Z);

        String type = layer.type == null ? "solid" : layer.type;

        switch (type)
        {
            case "text":
                drawText(layer, context, batcher);
                break;
            case "image":
            case "footage":
                drawImage(layer, context, batcher);
                break;
            case "shape":
                drawShapes(layer, context, batcher);
                break;
            case "bbs":
                drawBbsPreview(layer, batcher);
                break;
            case "replay":
                drawReplayEntity(layer, context, batcher, world, projection);
                break;
            case "precomp":
                drawPrecomp(layer, context, batcher);
                break;
            case "bar":
                drawBar(layer, context, batcher);
                break;
            case "panel":
                drawSolid(layer, batcher, true);
                break;
            case "null":
            case "adjustment":
            case "camera":
                break;
            case "rect":
            case "solid":
            default:
                drawSolid(layer, batcher, false);
                break;
        }

        stack.pop();
    }

    private static void drawSolid(CompLayer layer, Batcher2D batcher, boolean shadow)
    {
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;

        if (shadow)
        {
            batcher.box(x - 2F, y - 2F, x + layer.width + 2F, y + layer.height + 2F, withAlpha(0xFF000000, 0.4F));
        }

        batcher.box(x, y, x + layer.width, y + layer.height, layer.color);
    }

    private static void drawBar(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;
        float fill = layer.fill >= 0F ? layer.fill : (float) ((Math.sin(context.time * 1.6F) + 1F) * 0.5F);

        fill = Math.max(0F, Math.min(1F, fill));

        batcher.box(x, y, x + layer.width, y + layer.height, withAlpha(0xFF0A0C10, 0.72F));
        batcher.box(x, y, x + layer.width * fill, y + layer.height, layer.color);
        batcher.box(x, y, x + layer.width, y + 1F, withAlpha(0xFFFFFFFF, 0.25F));
    }

    private static void drawImage(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        String path = layer.imagePath;

        if ("footage".equals(layer.type) && path != null && path.matches(".*%0?\\d*d.*"))
        {
            float sourceTime = layer.sourceTimeAt(context.time, context.frame);
            int index = Math.max(0, Math.round(sourceTime * Math.max(1F, context.comp.fps)));

            try
            {
                path = String.format(path, index);
            }
            catch (IllegalArgumentException ignored)
            {
                path = layer.imagePath;
            }
        }

        ImageTextureCache.Entry image = ImageTextureCache.get(context.project.id, path);

        if (image == null)
        {
            /* Missing assets are visible, not silent: a dashed magenta placeholder would be
             * harder to miss than an absent layer. */
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.box(x, y, x + layer.width, y + layer.height, 0x33FF00FF);
            batcher.box(x, y, x + layer.width, y + 2F, 0xFFFF00FF);
            return;
        }

        float scale;

        if (layer.imageFit == 2)
        {
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            applyTextureFilter(layer, context, image, layer.width, layer.height);
            batcher.texturedBox(image.texture.id, layer.color, x, y, layer.width, layer.height, 0F, 0F, image.width, image.height, image.width, image.height);

            return;
        }

        if (layer.imageFit == 1)
        {
            scale = Math.max(layer.width / image.width, layer.height / image.height);
        }
        else
        {
            scale = Math.min(layer.width / image.width, layer.height / image.height);
        }

        float w = image.width * scale;
        float h = image.height * scale;
        float x = -w * layer.anchorX;
        float y = -h * layer.anchorY;

        applyTextureFilter(layer, context, image, w, h);
        batcher.texturedBox(image.texture.id, layer.color, x, y, w, h, 0F, 0F, image.width, image.height, image.width, image.height);
    }

    /**
     * Applies the layer/project texture filter immediately before its draw.
     *
     * <p>OpenGL filtering is texture state, not draw state, and the cache shares textures between
     * layers. Setting it here means a low-resolution icon and a 4K photo can use different filters
     * in the same frame without cloning either texture.</p>
     */
    private static void applyTextureFilter(CompLayer layer, CompRenderContext context, ImageTextureCache.Entry image,
                                           float targetWidth, float targetHeight)
    {
        int mode = TextureFilter.resolveMode(layer, context == null ? null : context.project);
        boolean linear = TextureFilter.isLinear(mode, image.width, image.height, targetWidth, targetHeight);

        image.texture.setLinear(linear);
    }

    private static void drawShapes(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        ShapeCache.Raster raster = ShapeCache.get(layer, context.frame);

        if (raster == null)
        {
            return;
        }

        batcher.texturedBox(raster.texture.id, 0xFFFFFFFF, raster.originX, raster.originY, raster.width, raster.height, 0F, 0F, raster.width, raster.height, raster.width, raster.height);
    }

    /**
     * The original BBS film/world frame, as a first-class layer.
     *
     * <p>BBS keeps the last rendered film frame in {@link BBSRendering#getTexture()}; the film
     * editor's own preview samples exactly that texture. A compositor "BBS preview" layer can
     * therefore show the world under (or above) the composition without re-rendering the world.
     * Because it is an ordinary layer it can be moved, trimmed, masked, blended and animated like
     * any other source.</p>
     */
    private static void drawBbsPreview(CompLayer layer, Batcher2D batcher)
    {
        /* The BBS preview layer is a real layer, not an editor-only decoration. It used to be
         * skipped outside the compositor panel ("the world is already under the composition"),
         * which made it vanish from the film-editor preview and from the F6 export while the
         * compositor still showed it. Instead, the overlay captures the clean world frame at
         * WorldRenderEvents.LAST into SceneColor and this layer samples that copy — exactly the
         * same source the compositor preview uses. */
        boolean active = dev.bbsae.client.overlay.BbsPreviewBridge.isActive();
        boolean captured = dev.bbsae.client.overlay.BbsPreviewBridge.hasCapturedWorld();

        if (!active && !captured)
        {
            return;
        }

        dev.bbsae.client.render.GlTexture texture = dev.bbsae.client.overlay.BbsPreviewBridge.texture();

        if (texture == null)
        {
            /* Active but no frame yet: draw an unmistakable placeholder instead of a transparent
             * rectangle that reads as "the layer does nothing". In the export path returning
             * transparent is safer than baking a placeholder into the video. */
            if (!active)
            {
                return;
            }

            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.box(x, y, x + layer.width, y + layer.height, 0x3300AAFF);
            batcher.box(x, y, x + layer.width, y + 2F, 0xFF55AAFF);
            batcher.box(x, y + layer.height - 2F, x + layer.width, y + layer.height, 0xFF55AAFF);
            batcher.textShadow("BBS 预览：等待世界帧…", x + 10F, y + 10F, 0xFFFFD27A);
            return;
        }

        drawWorldPlate(layer, batcher, texture);
    }

    /**
     * The depth-keyed replay entity layer.
     *
     * <p>Two things happen here and nowhere else: the world frame is sampled through a matte that
     * was produced by a depth-only pass of the selected replay entities, and everything the matte
     * rejects becomes transparent. The layer then behaves like any other source — it can be moved,
     * masked, feathered, graded, blended and keyframed, and it exports with alpha.</p>
     *
     * <p>A 3D (perspective-projected) entity layer cannot be keyed: the matte is a screen-space
     * property of the world camera, not of an arbitrary plane in the composition. Rather than
     * silently drawing a wrong matte, a 3D entity layer degrades to the un-keyed world plate.</p>
     */
    private static void drawReplayEntity(CompLayer layer, CompRenderContext context, Batcher2D batcher, Matrix4f world, Matrix4f projection)
    {
        dev.bbsae.client.render.GlTexture texture = dev.bbsae.client.overlay.BbsPreviewBridge.texture();
        boolean active = dev.bbsae.client.overlay.BbsPreviewBridge.isActive();

        if (texture == null)
        {
            if (active && context.preview)
            {
                float x = -layer.width * layer.anchorX;
                float y = -layer.height * layer.anchorY;

                batcher.box(x, y, x + layer.width, y + layer.height, 0x3300AAFF);
                batcher.outline(x, y, x + layer.width, y + layer.height, 0x8855AAFF);
                batcher.textShadow("回放实体：等待世界帧…", x + 10F, y + 10F, 0xFFFFD27A);
            }

            return;
        }

        /* Flush first: the matte is drawn with its own program into the target, and any quad
         * still queued in the batcher would be flushed after it and paint over the result. */
        batcher.flush();

        /* The matte is a screen-space key of the world camera. A 3D entity layer used to fall
         * back to the un-keyed world plate here, which is exactly why its background was the
         * whole frame instead of transparent. Keep the key for 3D too, but project it with the
         * composition's 2D ortho matrix: the actor's screen position already comes from the world
         * camera, so baking the layer's z/rotationX/Y on top of it would move the matte away from
         * the actor it was extracted from. */
        Matrix4f matteWorld = layer.threeD ? CompScene.world(context, layer) : world;
        Matrix4f matteProjection = layer.threeD ? GlStateGuard.ortho(context.width, context.height) : projection;

        /* Production path: the full-screen coverage shader. The batcher variant is kept
         * behind a flag for A/B verification of sampler/state issues. */
        boolean keyed = Boolean.getBoolean("bbsae.entityKeyTextured")
            ? dev.bbsae.client.render.ReplayEntityKey.drawTextured(layer, context, batcher)
            : dev.bbsae.client.render.ReplayEntityKey.draw(layer, context, matteWorld, matteProjection);

        if (keyed)
        {
            return;
        }

        /* No mask yet: stay transparent. Falling back to the un-keyed world plate here would
         * silently turn "nothing is keyed" into "the whole background is keyed", which is the
         * one result a keying layer must never produce. */
        if (context.preview)
        {
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.outline(x, y, x + layer.width, y + layer.height, 0x4455AAFF);
        }
    }

    /** Draws the world frame as an opaque picture, honouring the layer's fit and anchor. */
    private static void drawWorldPlate(CompLayer layer, Batcher2D batcher, dev.bbsae.client.render.GlTexture texture)
    {
        float w;
        float h;

        if (layer.imageFit == 2)
        {
            w = Math.max(1F, layer.width);
            h = Math.max(1F, layer.height);
        }
        else
        {
            float scale = layer.imageFit == 1
                ? Math.max(layer.width / texture.width, layer.height / texture.height)
                : Math.min(layer.width / texture.width, layer.height / texture.height);

            w = texture.width * scale;
            h = texture.height * scale;
        }

        batcher.texturedBox(texture.id, layer.color | 0xFF000000,
            -w * layer.anchorX, -h * layer.anchorY, w, h,
            0F, texture.height, texture.width, 0F, texture.width, texture.height);
        dev.bbsae.client.overlay.BbsPreviewBridge.noteFrameDrawn(texture.width, texture.height);
    }

    private static void drawPrecomp(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        CompDocument sub = layer.precompId == null || layer.precompId.isEmpty()
            ? null
            : context.project.getComp(layer.precompId);

        if (sub == null || context.stack.contains(sub.id))
        {
            /* Empty precompId used to resolve to the *first* composition (the current one), and
             * the cycle guard then swallowed the draw entirely. Say so instead of drawing air.
             * The message is editor-only: baking it into the export would put UI text in the film. */
            if (context.preview)
            {
                drawPrecompPlaceholder(layer, batcher, sub == null ? "未指定预合成 · 在图层检查器选择目标" : "循环预合成");
            }

            return;
        }

        /* An empty precomp used to render nothing at all, so the layer looked broken. Draw a
         * placeholder that is still selectable in the preview. */
        if (sub.layers == null || sub.layers.isEmpty())
        {
            if (context.preview)
            {
                drawPrecompPlaceholder(layer, batcher, "空预合成 · 双击轨道进入编辑");
            }

            return;
        }

        /* A precomp is a clip like any other: it must obey startTime, timeScale, freeze and time
         * remap. Using raw comp time here made trimming/moving the precomp layer appear to do
         * nothing, which is why the layer felt like a name-only placeholder. */
        float base = layer.sourceTimeAt(context.time, context.frame);
        float time = (base + layer.precompTimeOffset) * layer.precompTimeScale;

        /* Flush the outer batch first: the nested composition binds its own framebuffers, and any
         * queued quad would otherwise be flushed into the nested target instead of this layer. */
        batcher.flush();

        /* The child inherits the parent pass: a depth/guide pass that disabled effects must not
         * get them back just because the layer happens to be a precomp. The stage is transparent:
         * a nested comp is not the film canvas, so its background colour must not paint an opaque
         * rectangle over every layer below (that is what turned "预合成" into a black box). */
        GlFramebuffer framebuffer = CompRenderer.renderCompToFbo(context.project, sub, time, context.depth, context.projection, context.camera, context.stack, batcher, 0,
            context.renderEffects, context.renderMasks, context.preview, context.scale, true);

        if (framebuffer == null)
        {
            return;
        }

        /* The nested comp used custom VAOs and left blending disabled. Rebind the state
         * Minecraft's Batcher expects before compositing its result into this layer. */
        BufferRenderer.resetCurrentVertexBuffer();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        float sx = layer.width / sub.width;
        float sy = layer.height / sub.height;
        float scale = layer.imageFit == 1 ? Math.max(sx, sy) : layer.imageFit == 2 ? 0F : Math.min(sx, sy);

        /* Only the RGB is a tint; the alpha must stay 255 or the nested comp is multiplied by the
         * colour's alpha AND by the layer opacity (double transparency). */
        int tint = layer.color | 0xFF000000;

        if (scale == 0F)
        {
            batcher.texturedBox(framebuffer.color.id, tint, -layer.width * layer.anchorX, -layer.height * layer.anchorY, layer.width, layer.height, 0F, sub.height, sub.width, 0F, sub.width, sub.height);
        }
        else
        {
            float w = sub.width * scale;
            float h = sub.height * scale;

            batcher.texturedBox(framebuffer.color.id, tint, -w * layer.anchorX, -h * layer.anchorY, w, h, 0F, sub.height, sub.width, 0F, sub.width, sub.height);
        }

        /* The Batcher only queues the textured quad. Flush before returning the FBO to the pool,
         * otherwise a later layer can acquire and overwrite the texture still referenced by the
         * queued draw. */
        batcher.flush();
        BufferRenderer.resetCurrentVertexBuffer();
        FboPool.release(framebuffer);
    }

    private static void drawPrecompPlaceholder(CompLayer layer, Batcher2D batcher, String hint)
    {
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;

        batcher.box(x, y, x + layer.width, y + layer.height, 0x221E88E5);
        batcher.outline(x, y, x + layer.width, y + layer.height, 0x8855AAFF);
        batcher.textShadow(hint, x + 12F, y + 12F, 0xFF9CC7FF);
    }

    private static void drawText(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        String text = layer.text == null ? "" : layer.text;

        if (text.isEmpty())
        {
            return;
        }

        float fontSize = layer.fontSizeAt(context.frame);
        FontRenderer fallback = batcher.getFont() == null
            ? Batcher2D.getDefaultTextRenderer()
            : batcher.getFont();
        LayerFont chosen = fontFor(layer, batcher, fontSize);
        FontRenderer font = chosen.renderer;
        int fontHeight = Math.max(1, font.getHeight());        float tracking = layer.trackingAt(context.frame);
        float lineHeightFactor = layer.lineHeightAt(context.frame);
        float textStroke = layer.textStrokeAt(context.frame);
        float scale = Math.max(0.001F, fontSize / fontHeight);
        List<String> lines = layout(text, chosen, fallback, layer, tracking, layer.textBoxWidth / scale);
        float lineHeight = Math.max(1F, fontHeight * lineHeightFactor);
        float width = 0F;

        for (String line : lines)
        {
            width = Math.max(width, lineWidth(chosen, fallback, line, tracking));
        }

        float totalHeight = lines.size() * lineHeight;
        float baseX = -width * layer.anchorX;
        float baseY = -totalHeight * layer.anchorY;

        if (Boolean.getBoolean("bbsae.debugFonts") && FONT_WARNINGS.add("glyphs:" + layer.id + ":" + text.hashCode()))
        {
            StringBuilder detail = new StringBuilder();

            for (int i = 0; i < text.length() && detail.length() < 400; )
            {
                int codePoint = text.codePointAt(i);
                String glyph = new String(Character.toChars(codePoint));
                FontRenderer glyphFont = glyphFont(chosen, fallback, codePoint);

                detail.append(String.format(java.util.Locale.ROOT, " U+%04X[%s w=%.1f x%.1f]",
                    codePoint,
                    chosen.custom() && glyphFont == chosen.renderer ? "custom" : "default",
                    glyphFont.getWidth(glyph) * glyphScale(chosen, fallback, glyphFont),
                    glyphScale(chosen, fallback, glyphFont)));

                i += Character.charCount(codePoint);
            }

            dev.bbsae.BbsAeAddon.LOGGER.info("glyphs {} '{}'{}", layer.id, text, detail);
        }

        MatrixStack stack = batcher.getContext().getMatrices();

        stack.push();
        stack.scale(scale, scale, 1F);

        int visible = 0;
        int total = 0;

        for (String line : lines)
        {
            for (int i = 0; i < line.length(); )
            {
                int codePoint = line.codePointAt(i);

                if (!Character.isWhitespace(codePoint))
                {
                    total += 1;
                }

                i += Character.charCount(codePoint);
            }
        }

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++)
        {
            String line = lines.get(lineIndex);
            float currentLineWidth = lineWidth(chosen, fallback, line, tracking);
            float x = layer.align == 0 ? baseX : layer.align == 2 ? baseX + width - currentLineWidth : baseX + (width - currentLineWidth) / 2F;
            float y = baseY + lineIndex * lineHeight;
            float cursor = 0F;

            /* Iterate code points, not UTF-16 units: an emoji is two chars and splitting it drew
             * two replacement boxes instead of one glyph. */
            for (int i = 0; i < line.length(); )
            {
                int codePoint = line.codePointAt(i);
                String glyph = new String(Character.toChars(codePoint));

                /* The whole point of this pass: a character the chosen font cannot draw is drawn by
                 * BBS's default font instead of vanishing (or becoming a 1 px missing-glyph sliver). */
                FontRenderer glyphFont = glyphFont(chosen, fallback, codePoint);
                float glyphScale = glyphScale(chosen, fallback, glyphFont);
                float advance = glyphFont.getWidth(glyph) * glyphScale + tracking;
                float charX = x + cursor;

                if (!Character.isWhitespace(codePoint))
                {
                    float progress = total <= 1 ? 0F : visible / (float) (total - 1);
                    CompTextAnimator animator = selectAnimator(layer, progress, visible);

                    stack.push();

                    if (animator != null)
                    {
                        float wx = animator.positionX;
                        float wy = animator.positionY;
                        float animScale = animator.scale;
                        float rotation = animator.rotation;

                        if (animator.wiggle)
                        {
                            wx += (CompExpressionNoise.noise(visible * 3.17F, animator.seed) * 2F - 1F) * animator.randomness;
                            wy += (CompExpressionNoise.noise(visible * 5.13F, animator.seed + 11F) * 2F - 1F) * animator.randomness;
                        }

                        stack.translate(charX + advance / 2F + wx, y + fontHeight / 2F + wy, 0F);
                        stack.multiply(new Quaternionf().rotateZ((float) Math.toRadians(rotation)));
                        stack.scale(animScale, animScale, 1F);
                        stack.translate(-charX - advance / 2F, -y - fontHeight / 2F, 0F);
                    }

                    if (animator != null)
                    {
                        int color = animator.useColor ? animator.color : layer.color;

                        color = withAlpha(color, Colors.getA(color) * animator.opacity);
                        drawGlyph(batcher, glyphFont, glyphScale, glyph, charX, y, color, layer.strokeColor, textStroke);
                    }
                    else
                    {
                        drawGlyph(batcher, glyphFont, glyphScale, glyph, charX, y, layer.color, layer.strokeColor, textStroke);
                    }

                    stack.pop();
                    visible += 1;
                }

                cursor += advance;
                i += Character.charCount(codePoint);
            }
        }

        stack.pop();
    }

    /**
     * The layer's font file plus BBS's renderer for it.
     *
     * <p>{@code renderer} is never null: when the project names a font that is missing, unreadable
     * or that BBS cannot parse, it is the default renderer and {@code file} is null. The renderer
     * still chooses per character (see {@link #glyphFont}) because a font is a partial map.</p>
     */
    private static final class LayerFont
    {
        final FontRenderer renderer;
        final File file;
        final String name;

        LayerFont(FontRenderer renderer, File file, String name)
        {
            this.renderer = renderer;
            this.file = file;
            this.name = name;
        }

        boolean custom()
        {
            return this.file != null;
        }
    }

    private static final class ResolvedFont
    {
        final Link link;
        final File file;

        ResolvedFont(Link link, File file)
        {
            this.link = link;
            this.file = file;
        }
    }

    /** fontName → link/file, so the per-frame path does not rescan the folder. */
    private static final java.util.Map<String, ResolvedFont> FONT_FILES = new java.util.HashMap<>();
    private static final java.util.Set<String> FONT_WARNINGS = new java.util.HashSet<>();

    /** Drops the resolved names (after an import or a font folder change). */
    static void clearFonts()
    {
        FONT_FILES.clear();
        FONT_WARNINGS.clear();
        FontCoverage.clear();
    }

    /** Custom fonts come from BBS's own TTF loader; an empty name keeps the default font. */
    private static LayerFont fontFor(CompLayer layer, Batcher2D batcher, float fontSize)
    {
        FontRenderer fallback = batcher.getFont() == null
            ? Batcher2D.getDefaultTextRenderer()
            : batcher.getFont();
        String name = layer.fontName == null ? "" : layer.fontName.trim();

        if (name.isEmpty())
        {
            return new LayerFont(fallback, null, "");
        }

        int size = Math.max(4, Math.min(96, Math.round(fontSize)));

        /* The atlas is rasterized at `size`; a 200 px text layer must not be a 96 px atlas blown
         * up 2x. BBS' FontManager turns this ratio into its oversampling factor. */
        float rasterScale = Math.max(1F, fontSize / Math.max(1, size));
        ResolvedFont resolved = resolve(name);

        if (resolved == null)
        {
            warn(name, "the font file is not in BBS' fonts folder: " + name);
            return new LayerFont(fallback, null, name);
        }

        FontRenderer custom;

        try
        {
            custom = BBSModClient.getFonts().get(resolved.link, size, rasterScale);
        }
        catch (RuntimeException e)
        {
            warn(name, "BBS could not load the font: " + e);
            custom = null;
        }

        if (custom == null || custom.getRenderer() == null)
        {
            warn(name, "BBS could not parse the font (OTF/CFF or corrupt): " + resolved.file);
            return new LayerFont(fallback, null, name);
        }

        if (Boolean.getBoolean("bbsae.debugFonts") && FONT_WARNINGS.add("debug:" + name + ":" + size))
        {
            dev.bbsae.BbsAeAddon.LOGGER.info(
                "font: name={} file={} size={} oversample={} height={} width(H)={} width(新)={} coversH={} coversHan={}",
                name, resolved.file, size, rasterScale, custom.getHeight(),
                custom.getWidth("H"), custom.getWidth("新"),
                FontCoverage.covers(resolved.file, 'H'), FontCoverage.covers(resolved.file, '新'));
        }

        return new LayerFont(custom, resolved.file, name);
    }

    /**
     * Resolves a stored {@code fontName} to a link and a file.
     *
     * <p>Accepts what older projects and the picker actually store: a plain file name
     * ({@code timesbd.ttf}), a name without an extension, a sub-folder path under {@code fonts/},
     * and a name whose case differs from the file on disk.</p>
     */
    private static ResolvedFont resolve(String name)
    {
        ResolvedFont cached = FONT_FILES.get(name);

        if (cached != null && cached.file != null && cached.file.isFile())
        {
            return cached;
        }

        File file = FontCoverage.resolve(name);

        if (file == null)
        {
            return null;
        }

        ResolvedFont resolved = new ResolvedFont(
            Link.assets("fonts/" + FontCoverage.relativeName(file)), file);

        FONT_FILES.put(name, resolved);

        return resolved;
    }

    /** Logs one line per unloadable font instead of spamming the console every frame. */
    private static void warn(String name, String message)
    {
        if (FONT_WARNINGS.add(name))
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Text layer font '{}': {}", name, message);
        }
    }

    /**
     * The renderer for one character.
     *
     * <p>This is the fix for "the imported font shows nothing": the chosen font is used where its
     * cmap has a glyph and BBS's default font everywhere else, instead of letting Minecraft answer
     * with a missing glyph (no renderer, 1 px advance) for every character it cannot draw.</p>
     */
    private static FontRenderer glyphFont(LayerFont font, FontRenderer fallback, int codePoint)
    {
        if (font == null || !font.custom())
        {
            return fallback;
        }

        if (FontCoverage.covers(font.file, codePoint))
        {
            return font.renderer;
        }

        return fallback;
    }

    /**
     * Scale that makes a fallback glyph match the size of the chosen font's glyphs.
     *
     * <p>The two renderers have different native metrics: BBS's default font is ~9 px tall while a
     * custom font is laid out at {@code size} (72 px text is ~54 px of ascent). Drawing the fallback
     * at scale 1 next to the custom glyphs produced tiny 汉字 next to full-size Latin letters, so the
     * fallback is drawn in the custom font's own scale.</p>
     */
    private static float glyphScale(LayerFont font, FontRenderer fallback, FontRenderer glyphFont)
    {
        if (font == null || !font.custom() || glyphFont != fallback)
        {
            return 1F;
        }

        int fallbackHeight = Math.max(1, fallback.getHeight());
        float scale = font.renderer.getHeight() / (float) fallbackHeight;

        /* A default font 1 px taller than the custom one is not a reason to shrink text. */
        return Math.max(0.05F, scale);
    }

    /**
     * Draws one glyph with the layer's FontRenderer.
     *
     * <p>Batcher2D.text() always uses {@code batcher.getFont()}. The imported font was only used
     * for measuring (line width/height), while the glyphs themselves were drawn with BBS's
     * default font — the classic "font metrics change but the letters do not" bug. Setting the
     * batcher font around the draw makes the renderer and the measurement agree.</p>
     */
    private static void drawGlyph(Batcher2D batcher, FontRenderer font, float glyphScale, String glyph,
                                  float x, float y, int color, int strokeColor, float strokeWidth)
    {
        if (Colors.getA(color) <= 0F)
        {
            return;
        }

        MatrixStack stack = glyphScale == 1F ? null : batcher.getContext().getMatrices();

        if (stack != null)
        {
            /* Scale about the glyph origin so the baseline stays where the layout put it. */
            stack.push();
            stack.translate(x, y, 0F);
            stack.scale(glyphScale, glyphScale, 1F);
            stack.translate(-x, -y, 0F);
        }

        FontRenderer previous = batcher.setFont(font);

        try
        {
            if (Colors.getA(strokeColor) > 0F && strokeWidth > 0F)
            {
                float offset = Math.max(1F, strokeWidth * 0.5F);

                batcher.text(glyph, x - offset, y, strokeColor, false);
                batcher.text(glyph, x + offset, y, strokeColor, false);
                batcher.text(glyph, x, y - offset, strokeColor, false);
                batcher.text(glyph, x, y + offset, strokeColor, false);
            }

            batcher.text(glyph, x, y, color, false);
        }
        finally
        {
            batcher.setFont(previous);

            if (stack != null)
            {
                stack.pop();
            }
        }
    }

    /**
     * Width of a string in the font that will actually draw each character.
     *
     * <p>Measuring with one font and drawing with another is how a centred line drifts; the
     * per-character fallback has to be applied to both sides.</p>
     */
    private static float lineWidth(LayerFont font, FontRenderer fallback, String line, float tracking)
    {
        if (line.isEmpty())
        {
            return 0F;
        }

        float width = 0F;
        int count = 0;

        for (int i = 0; i < line.length(); )
        {
            int codePoint = line.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            FontRenderer glyphFont = glyphFont(font, fallback, codePoint);

            width += glyphFont.getWidth(glyph) * glyphScale(font, fallback, glyphFont);
            count += 1;
            i += Character.charCount(codePoint);
        }

        return width + tracking * Math.max(0, count - 1);
    }

    private static List<String> layout(String text, LayerFont font, FontRenderer fallback, CompLayer layer,
                                       float tracking, float maxWidth)
    {
        List<String> lines = new ArrayList<>();

        for (String raw : text.split("\\n", -1))
        {
            if (!layer.wrap)
            {
                lines.add(raw);
                continue;
            }

            String[] words = raw.split(" ");
            StringBuilder line = new StringBuilder();

            for (String word : words)
            {
                String candidate = line.isEmpty() ? word : line + " " + word;

                if (!line.isEmpty() && lineWidth(font, fallback, candidate, tracking) > maxWidth)
                {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
                else
                {
                    line.setLength(0);
                    line.append(candidate);
                }

                /* CJK has no spaces and a long word can exceed the box on its own. Break it by
                 * characters instead of letting it overflow. */
                if (lineWidth(font, fallback, line.toString(), tracking) > maxWidth)
                {
                    String overflow = line.toString();
                    StringBuilder chunk = new StringBuilder();

                    line.setLength(0);

                    for (int i = 0; i < overflow.length(); i++)
                    {
                        char c = overflow.charAt(i);

                        if (chunk.length() > 0 && lineWidth(font, fallback, chunk.toString() + c, tracking) > maxWidth)
                        {
                            lines.add(chunk.toString());
                            chunk.setLength(0);
                        }

                        chunk.append(c);
                    }

                    line.append(chunk);
                }
            }

            lines.add(line.toString());
        }

        return lines;
    }

    private static CompTextAnimator selectAnimator(CompLayer layer, float progress, int index)
    {
        for (CompTextAnimator animator : layer.textAnimators)
        {
            if (!animator.enabled)
            {
                continue;
            }

            float p = progress;

            if (animator.randomOrder)
            {
                p = CompExpressionNoise.noise(index * 2.31F, animator.seed);
            }

            float start = animator.start + animator.offset;
            float end = animator.end + animator.offset;

            if (p >= start && p <= end)
            {
                return animator;
            }
        }

        return null;
    }

    private static int withAlpha(int color, float alpha)
    {
        return Colors.setA(color, Colors.getA(color) * Math.max(0F, Math.min(1F, alpha)));
    }

    /** Small local noise function so text animation does not depend on the expression parser. */
    static final class CompExpressionNoise
    {
        static float noise(float x, float seed)
        {
            double value = Math.sin(x * 12.9898D + seed * 78.233D) * 43758.5453D;

            return (float) (value - Math.floor(value));
        }
    }
}
