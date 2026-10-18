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
            float sx = layer.width / image.width;
            float sy = layer.height / image.height;
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

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

        batcher.texturedBox(image.texture.id, layer.color, x, y, w, h, 0F, 0F, image.width, image.height, image.width, image.height);
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
        /* Outside the compositor (film editor overlay / F6 export) the world is already under the
         * composition, so a BBS preview layer must contribute nothing — sampling the preview
         * texture there would copy the previous finished frame back into itself. */
        if (!dev.bbsae.client.overlay.BbsPreviewBridge.isActive())
        {
            return;
        }

        dev.bbsae.client.render.GlTexture texture = dev.bbsae.client.overlay.BbsPreviewBridge.texture();

        if (texture == null)
        {
            /* Active but no frame yet: draw an unmistakable placeholder instead of a transparent
             * rectangle that reads as "the layer does nothing". */
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.box(x, y, x + layer.width, y + layer.height, 0x3300AAFF);
            batcher.box(x, y, x + layer.width, y + 2F, 0xFF55AAFF);
            batcher.box(x, y + layer.height - 2F, x + layer.width, y + layer.height, 0xFF55AAFF);
            batcher.textShadow("BBS 预览：等待世界帧…", x + 10F, y + 10F, 0xFFFFD27A);
            return;
        }

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

        batcher.texturedBox(texture.id, layer.color,
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
             * the cycle guard then swallowed the draw entirely. Say so instead of drawing air. */
            drawPrecompPlaceholder(layer, batcher, sub == null ? "未指定预合成 · 在图层检查器选择目标" : "循环预合成");
            return;
        }

        /* An empty precomp used to render nothing at all, so the layer looked broken. Draw a
         * placeholder that is still selectable in the preview. */
        if (sub.layers == null || sub.layers.isEmpty())
        {
            drawPrecompPlaceholder(layer, batcher, "空预合成 · 双击轨道进入编辑");

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

        GlFramebuffer framebuffer = CompRenderer.renderCompToFbo(context.project, sub, time, context.depth, context.projection, context.camera, context.stack, batcher, 0, true, true);

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

        if (scale == 0F)
        {
            batcher.texturedBox(framebuffer.color.id, layer.color, -layer.width * layer.anchorX, -layer.height * layer.anchorY, layer.width, layer.height, 0F, sub.height, sub.width, 0F, sub.width, sub.height);
        }
        else
        {
            float w = sub.width * scale;
            float h = sub.height * scale;

            batcher.texturedBox(framebuffer.color.id, layer.color, -w * layer.anchorX, -h * layer.anchorY, w, h, 0F, sub.height, sub.width, 0F, sub.width, sub.height);
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
        FontRenderer font = fontFor(layer, batcher, fontSize);
        int fontHeight = Math.max(1, font.getHeight());
        float tracking = layer.trackingAt(context.frame);
        float lineHeightFactor = layer.lineHeightAt(context.frame);
        float textStroke = layer.textStrokeAt(context.frame);
        float scale = Math.max(0.001F, fontSize / fontHeight);
        List<String> lines = layout(text, font, layer, tracking, layer.textBoxWidth / scale);
        float lineHeight = Math.max(1F, fontHeight * lineHeightFactor);
        float width = 0F;

        for (String line : lines)
        {
            width = Math.max(width, lineWidth(font, line, tracking));
        }

        float totalHeight = lines.size() * lineHeight;
        float baseX = -width * layer.anchorX;
        float baseY = -totalHeight * layer.anchorY;

        MatrixStack stack = batcher.getContext().getMatrices();

        stack.push();
        stack.scale(scale, scale, 1F);

        int visible = 0;
        int total = 0;

        for (String line : lines)
        {
            for (int i = 0; i < line.length(); i++)
            {
                if (!Character.isWhitespace(line.charAt(i)))
                {
                    total += 1;
                }
            }
        }

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++)
        {
            String line = lines.get(lineIndex);
            float currentLineWidth = lineWidth(font, line, tracking);
            float x = layer.align == 0 ? baseX : layer.align == 2 ? baseX + width - currentLineWidth : baseX + (width - currentLineWidth) / 2F;
            float y = baseY + lineIndex * lineHeight;
            float cursor = 0F;

            for (int i = 0; i < line.length(); i++)
            {
                char c = line.charAt(i);
                String glyph = String.valueOf(c);
                float advance = font.getWidth(glyph) + tracking;
                float charX = x + cursor;

                if (!Character.isWhitespace(c))
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
                        drawGlyph(batcher, glyph, charX, y, color, layer.strokeColor, textStroke);
                    }
                    else
                    {
                        drawGlyph(batcher, glyph, charX, y, layer.color, layer.strokeColor, textStroke);
                    }

                    stack.pop();
                    visible += 1;
                }

                cursor += advance;
            }
        }

        stack.pop();
    }

    /** Custom fonts come from BBS's own TTF loader; an empty name keeps the default font. */
    private static FontRenderer fontFor(CompLayer layer, Batcher2D batcher, float fontSize)
    {
        if (layer.fontName == null || layer.fontName.isBlank())
        {
            return batcher.getFont();
        }

        int size = Math.max(4, Math.min(96, Math.round(fontSize)));

        try
        {
            FontRenderer custom = BBSModClient.getFonts().get(Link.assets("fonts/" + layer.fontName), size, 1F);

            return custom == null ? batcher.getFont() : custom;
        }
        catch (RuntimeException e)
        {
            return batcher.getFont();
        }
    }

    private static void drawGlyph(Batcher2D batcher, String glyph, float x, float y, int color, int strokeColor, float strokeWidth)
    {
        if (Colors.getA(color) <= 0F)
        {
            return;
        }

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

    private static float lineWidth(FontRenderer font, String line, float tracking)
    {
        if (line.isEmpty())
        {
            return 0F;
        }

        return font.getWidth(line) + tracking * Math.max(0, line.length() - 1);
    }

    private static List<String> layout(String text, FontRenderer font, CompLayer layer, float tracking, float maxWidth)
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

                if (!line.isEmpty() && lineWidth(font, candidate, tracking) > maxWidth)
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
                if (lineWidth(font, line.toString(), tracking) > maxWidth)
                {
                    String overflow = line.toString();
                    StringBuilder chunk = new StringBuilder();

                    line.setLength(0);

                    for (int i = 0; i < overflow.length(); i++)
                    {
                        char c = overflow.charAt(i);

                        if (chunk.length() > 0 && lineWidth(font, chunk.toString() + c, tracking) > maxWidth)
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
