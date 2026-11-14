package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vector-rasterized text, drawn as one cached texture.
 *
 * <h2>Why the BBS atlas is not enough</h2>
 * <p>BBS renders imported fonts through STB at a fixed layout size and then scales the glyph quads
 * to the layer's font size. That is fine at 1:1, but a text layer scaled to 300 %, a zoomed camera
 * or a 2x preview samples those point-sampled glyph atlases at a larger pixel size: the outlines
 * turn into visible stair steps. No oversampling factor fixes every zoom because the final scale is
 * not known until the layer is projected, and the font manager caps its atlas at 192 px.</p>
 *
 * <p>This renderer takes the actual glyph outlines from the font file (Java2D's vector
 * {@link GlyphVector}), rasterizes the whole block at the target pixel density with antialiasing,
 * and draws it as one linear-filtered texture. The glyphs are therefore vector at every zoom, and
 * the text layer's effects/masks still see a normal RGBA source. It deliberately falls back to the
 * existing BBS path when a glyph is not covered by the chosen font (CJK/emoji fallback), when the
 * text uses a per-glyph animator, or when the block would exceed the texture budget.</p>
 */
final class VectorTextRaster
{
    private static final int MAX_TEXTURE = 4096;
    private static final int MAX_ENTRIES = 32;
    private static final long MAX_BYTES = 96L * 1024L * 1024L;

    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<File, LoadedFont> FONTS = new LinkedHashMap<>(4, 0.75F, true);
    private static long bytes;

    private VectorTextRaster()
    {}

    /**
     * Tries to draw the block as a vector texture at {@code baseX/baseY}.
     *
     * @return false when the caller must use the original BBS glyph path.
     */
    static boolean draw(CompLayer layer, LayerContentRenderer.TextLayout layout, File fontFile,
                        mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer customRenderer,
                        mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallbackRenderer,
                        float fontSize, float fontHeight, float lineHeight, float textStroke,
                        int fillColor, int strokeColor, float outputScale, float frame,
                        float baseX, float baseY, Batcher2D batcher)
    {
        if (fontFile == null || layout == null || layout.lines == null || layout.lines.length == 0)
        {
            return false;
        }

        if (!allGlyphsUse(layout, customRenderer, fallbackRenderer))
        {
            return false;
        }

        LoadedFont loaded = loadedFont(fontFile);

        if (loaded == null)
        {
            return false;
        }

        /* Identity comes from the cached load, not from a stat per layer per frame. A replaced
         * font reaches this cache through clear() (the font import/reload path already clears
         * FontCoverage and the layout cache). */

        /* The final text pixel size is the font size times the layer scale times the composition
         * render scale. Render at 2x that (capped at 4x) so linear downsampling never reveals a
         * stair step even when the viewport is zoomed. */
        float layerScale = Math.max(1F, Math.max(
            Math.abs(layer.scalarAt("scaleX", layer.scaleX, frame)),
            Math.abs(layer.scalarAt("scaleY", layer.scaleY, frame))));
        float pixelScale = Math.max(1F, Math.min(4F, Math.max(1F, outputScale) * layerScale * 2F));
        /* BBS' layout is in font units; the existing draw path applies fontSize/fontHeight as
         * the stack scale. Texture pixels and AWT font size are both in final composition pixels. */
        float unitScale = fontSize / Math.max(1F, fontHeight);
        float unitPixels = unitScale * pixelScale;
        float strokeComp = Math.max(0F, textStroke * unitScale);
        int imagePad = Math.max(2, (int) Math.ceil(Math.max(2F, strokeComp) * pixelScale + 2F));
        int imageW = (int) Math.ceil(layout.width * unitPixels) + imagePad * 2;
        int imageH = (int) Math.ceil(layout.lines.length * lineHeight * unitPixels) + imagePad * 2;

        if (imageW <= 2 || imageH <= 2 || imageW > MAX_TEXTURE || imageH > MAX_TEXTURE)
        {
            return false;
        }

        /* BBS' fallback glyphs are Minecraft bitmap glyphs. Use a real system font for those
         * codepoints too, so Chinese text drawn with a Latin display font is vector as well.
         * If no installed font covers them, the caller's original fallback path stays in charge. */
        String fallbackFamily = null;
        Font fallbackFont = null;

        if (hasFallbackGlyphs(layout, customRenderer, fallbackRenderer))
        {
            Font[] chosen = fallbackFont(layout, customRenderer, fallbackRenderer,
                Math.max(1F, fontSize * pixelScale));

            if (chosen == null)
            {
                return false;
            }

            fallbackFont = chosen[0];
            fallbackFamily = chosen[0].getFamily();
        }

        Key key = new Key(layout.text, fontFile.getAbsolutePath(), loaded.modified,
            loaded.length, fontSize, fontHeight, lineHeight, textStroke,
            fillColor, strokeColor, layer.align, pixelScale, imageW, imageH, fallbackFamily);

        Entry entry = CACHE.get(key);

        if (entry == null)
        {
            entry = rasterize(loaded.font, fallbackFont, customRenderer, fallbackRenderer, layout,
                fontSize, fontHeight, lineHeight, textStroke, fillColor, strokeColor, layer.align,
                pixelScale, unitPixels, imageW, imageH, imagePad);

            if (entry == null)
            {
                return false;
            }

            CACHE.put(key, entry);
            bytes += entry.bytes;
            trim();
        }

        float padComp = imagePad / pixelScale;

        batcher.texturedBox(entry.texture.id, 0xFFFFFFFF,
            baseX * unitScale - padComp, baseY * unitScale - padComp,
            imageW / pixelScale, imageH / pixelScale,
            0F, 0F, imageW, imageH, imageW, imageH);

        return true;
    }

    /** True when every glyph is one the chosen font or BBS' fallback produced. */
    private static boolean allGlyphsUse(LayerContentRenderer.TextLayout layout,
                                        mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer custom,
                                        mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallback)
    {
        if (custom == null || fallback == null)
        {
            return false;
        }

        for (LayerContentRenderer.TextLine line : layout.lines)
        {
            for (LayerContentRenderer.Glyph glyph : line.glyphs)
            {
                if ((glyph.font != custom && glyph.font != fallback)
                    || glyph.text == null || glyph.text.isEmpty())
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean hasFallbackGlyphs(LayerContentRenderer.TextLayout layout,
                                             mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer custom,
                                             mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallback)
    {
        for (LayerContentRenderer.TextLine line : layout.lines)
        {
            for (LayerContentRenderer.Glyph glyph : line.glyphs)
            {
                if (glyph.font == fallback)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * A system font that can draw every fallback glyph. BBS' fallback is the Minecraft bitmap
     * font; vectorizing those characters requires a real font that actually covers them.
     */
    private static Font[] fallbackFont(LayerContentRenderer.TextLayout layout,
                                       mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer custom,
                                       mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallback,
                                       float size)
    {
        String[] families = {
            "Microsoft YaHei", "Microsoft YaHei UI", "SimSun", "NSimSun",
            "Noto Sans CJK SC", "Noto Sans SC", "Arial Unicode MS",
            "Segoe UI", "Dialog", Font.SANS_SERIF
        };

        for (String family : families)
        {
            Font font = new Font(family, Font.PLAIN, Math.max(1, Math.round(size)));
            boolean covers = true;

            for (LayerContentRenderer.TextLine line : layout.lines)
            {
                for (LayerContentRenderer.Glyph glyph : line.glyphs)
                {
                    if (glyph.font == fallback && !font.canDisplay(glyph.codePoint))
                    {
                        covers = false;
                        break;
                    }
                }

                if (!covers)
                {
                    break;
                }
            }

            if (covers)
            {
                return new Font[] { font };
            }
        }

        return null;
    }

    private static LoadedFont loadedFont(File file)
    {
        LoadedFont cached = FONTS.get(file);

        if (cached != null)
        {
            return cached;
        }

        try
        {
            Font base = Font.createFont(Font.TRUETYPE_FONT, file);
            LoadedFont loaded = new LoadedFont(base, file.lastModified(), file.length());

            FONTS.put(file, loaded);
            trimFonts();

            return loaded;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Entry rasterize(Font base, Font fallbackFont,
                                   mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer customRenderer,
                                   mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallbackRenderer,
                                   LayerContentRenderer.TextLayout layout,
                                   float fontSize, float fontHeight, float lineHeight,
                                   float textStroke, int fillColor, int strokeColor, int align,
                                   float pixelScale, float unitPixels, int imageW, int imageH, int pad)
    {
        try
        {
            float awtSize = Math.max(1F, fontSize * pixelScale);
            Font font = base.deriveFont(awtSize);
            BufferedImage image = new BufferedImage(imageW, imageH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            try
            {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                FontRenderContext frc = g.getFontRenderContext();
                FontMetrics metrics = g.getFontMetrics(font);
                float strokePx = Math.max(0F, textStroke * (fontSize / Math.max(1F, fontHeight))) * pixelScale;
                java.awt.Color fill = new java.awt.Color(fillColor, true);
                java.awt.Color stroke = new java.awt.Color(strokeColor, true);
                float lineGap = lineHeight * unitPixels;

                for (int lineIndex = 0; lineIndex < layout.lines.length; lineIndex++)
                {
                    LayerContentRenderer.TextLine line = layout.lines[lineIndex];
                    float lineOffset = switch (align)
                    {
                        case 1 -> (layout.width - line.width) * 0.5F;
                        case 2 -> layout.width - line.width;
                        default -> 0F;
                    };
                    float baseline = pad + lineIndex * lineGap + metrics.getAscent();

                    for (LayerContentRenderer.Glyph glyph : line.glyphs)
                    {
                        if (glyph.text == null || glyph.text.isEmpty())
                        {
                            continue;
                        }

                        Font glyphFont = glyph.font == fallbackRenderer && fallbackFont != null
                            ? fallbackFont : font;

                        if (!glyphFont.canDisplay(glyph.codePoint))
                        {
                            return null;
                        }

                        GlyphVector vector = glyphFont.createGlyphVector(frc, glyph.text);
                        Shape outline = vector.getOutline();
                        AffineTransform transform = AffineTransform.getTranslateInstance(
                            pad + (lineOffset + glyph.x) * unitPixels, baseline);
                        Shape placed = transform.createTransformedShape(outline);

                        if (strokePx > 0F && (strokeColor >>> 24) != 0)
                        {
                            g.setColor(stroke);
                            g.setStroke(new BasicStroke(strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g.draw(placed);
                        }

                        g.setColor(fill);
                        g.fill(placed);
                    }
                }
            }
            finally
            {
                g.dispose();
            }

            int[] pixels = image.getRGB(0, 0, imageW, imageH, null, 0, imageW);
            GlTexture texture = new GlTexture();

            texture.allocate(imageW, imageH).uploadRaw(pixels);

            return new Entry(texture, (long) imageW * imageH * 4L);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void trim()
    {
        while (!CACHE.isEmpty() && (CACHE.size() > MAX_ENTRIES || bytes > MAX_BYTES))
        {
            Iterator<Map.Entry<Key, Entry>> iterator = CACHE.entrySet().iterator();
            Map.Entry<Key, Entry> oldest = iterator.next();

            bytes -= oldest.getValue().bytes;
            oldest.getValue().texture.delete();
            iterator.remove();
        }
    }

    private static void trimFonts()
    {
        while (FONTS.size() > 4)
        {
            Iterator<File> iterator = FONTS.keySet().iterator();

            iterator.next();
            iterator.remove();
        }
    }

    static void clear()
    {
        for (Entry entry : CACHE.values())
        {
            entry.texture.delete();
        }

        CACHE.clear();
        FONTS.clear();
        bytes = 0L;
    }

    /** Font base plus file identity, so a replaced font cannot serve old glyphs. */
    private static final class LoadedFont
    {
        final Font font;
        final long modified;
        final long length;

        LoadedFont(Font font, long modified, long length)
        {
            this.font = font;
            this.modified = modified;
            this.length = length;
        }
    }

    private static final class Entry
    {
        final GlTexture texture;
        final long bytes;

        Entry(GlTexture texture, long bytes)
        {
            this.texture = texture;
            this.bytes = bytes;
        }
    }

    private static final class Key
    {
        final String text;
        final String file;
        final long modified;
        final long length;
        final float fontSize;
        final float fontHeight;
        final float lineHeight;
        final float stroke;
        final int fill;
        final int strokeColor;
        final int align;
        final float scale;
        final int width;
        final int height;
        final String fallbackFamily;
        private final int hash;

        Key(String text, String file, long modified, long length, float fontSize, float fontHeight,
            float lineHeight, float stroke, int fill, int strokeColor, int align,
            float scale, int width, int height, String fallbackFamily)
        {
            this.text = text;
            this.file = file;
            this.modified = modified;
            this.length = length;
            this.fontSize = fontSize;
            this.fontHeight = fontHeight;
            this.lineHeight = lineHeight;
            this.stroke = stroke;
            this.fill = fill;
            this.strokeColor = strokeColor;
            this.align = align;
            this.scale = scale;
            this.width = width;
            this.height = height;
            this.fallbackFamily = fallbackFamily;

            int h = text.hashCode();

            h = h * 31 + file.hashCode();
            h = h * 31 + Long.hashCode(modified);
            h = h * 31 + Long.hashCode(length);
            h = h * 31 + Float.floatToIntBits(fontSize);
            h = h * 31 + Float.floatToIntBits(fontHeight);
            h = h * 31 + Float.floatToIntBits(lineHeight);
            h = h * 31 + Float.floatToIntBits(stroke);
            h = h * 31 + fill;
            h = h * 31 + strokeColor;
            h = h * 31 + align;
            h = h * 31 + Float.floatToIntBits(scale);
            h = h * 31 + width;
            h = h * 31 + height;
            h = h * 31 + (fallbackFamily == null ? 0 : fallbackFamily.hashCode());
            this.hash = h;
        }

        @Override
        public int hashCode()
        {
            return this.hash;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof Key other))
            {
                return false;
            }

            return this.hash == other.hash
                && this.width == other.width && this.height == other.height
                && this.fill == other.fill && this.strokeColor == other.strokeColor
                && this.align == other.align
                && this.modified == other.modified && this.length == other.length
                && Float.compare(this.fontSize, other.fontSize) == 0
                && Float.compare(this.fontHeight, other.fontHeight) == 0
                && Float.compare(this.lineHeight, other.lineHeight) == 0
                && Float.compare(this.stroke, other.stroke) == 0
                && Float.compare(this.scale, other.scale) == 0
                && java.util.Objects.equals(this.fallbackFamily, other.fallbackFamily)
                && this.text.equals(other.text) && this.file.equals(other.file);
        }
    }
}
