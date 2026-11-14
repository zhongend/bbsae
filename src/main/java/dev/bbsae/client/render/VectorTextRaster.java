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
import java.awt.geom.Rectangle2D;
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
 * existing BBS path when a glyph is not covered by the chosen font (CJK/emoji fallback only when no
 * system font covers them), when the text uses a per-glyph animator, or when the block would exceed
 * the texture budget.</p>
 *
 * <h2>Why the raster is measured from glyph outlines</h2>
 * <p>The first version sized the texture as {@code lines * lineHeight * fontHeightFactor}. That is
 * the BBS line box, not the glyphs: AWT's ascent + descent (and accents or descenders in a CJK
 * fallback) can exceed it, so the baseline placed the last line's descent below the texture edge and
 * the bottom of the text was cut off — the "文字下半部像被裁切了一样" report. The block is now
 * measured from the real outlines ({@link #plan}) and the texture always contains every ink pixel.
 * The pivot/anchoring math is unchanged: the extra space is transparent padding around the same
 * lines.</p>
 */
final class VectorTextRaster
{
    private static final int MAX_TEXTURE = 4096;
    private static final int MAX_ENTRIES = 32;
    private static final long MAX_BYTES = 96L * 1024L * 1024L;

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

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

        Geometry geometry = plan(loaded.font, fallbackFont, layout, customRenderer, fallbackRenderer,
            fontSize, fontHeight, lineHeight, textStroke, pixelScale, unitPixels, layer.align);

        if (geometry == null)
        {
            return false;
        }

        Key key = new Key(layout.text, fontFile.getAbsolutePath(), loaded.modified,
            loaded.length, fontSize, fontHeight, lineHeight, textStroke,
            fillColor, strokeColor, layer.align, pixelScale, geometry.width, geometry.height,
            fallbackFamily);

        Entry entry = CACHE.get(key);

        if (entry == null)
        {
            entry = rasterize(loaded.font, fallbackFont, customRenderer, fallbackRenderer, layout,
                fillColor, strokeColor, layer.align, geometry);

            if (entry == null)
            {
                return false;
            }

            CACHE.put(key, entry);
            bytes += entry.bytes;
            trim();
        }

        float leftPadComp = geometry.leftPad / pixelScale;
        float padComp = geometry.pad / pixelScale;

        batcher.texturedBox(entry.texture.id, 0xFFFFFFFF,
            baseX * unitScale - leftPadComp, baseY * unitScale - padComp,
            geometry.width / pixelScale, geometry.height / pixelScale,
            0F, 0F, geometry.width, geometry.height, geometry.width, geometry.height);

        return true;
    }

    /**
     * Measures the raster the block needs from the real glyph outlines.
     *
     * <p>Pure Java2D: no GL, no game state. It is package-private so a headless test can assert
     * that descending glyphs and CJK fallback ink fit inside the returned raster.</p>
     */
    static Geometry plan(Font base, Font fallbackFont,
                         LayerContentRenderer.TextLayout layout,
                         mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer customRenderer,
                         mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallbackRenderer,
                         float fontSize, float fontHeight, float lineHeight, float textStroke,
                         float pixelScale, float unitPixels, int align)
    {
        try
        {
            float awtSize = Math.max(1F, fontSize * pixelScale);
            Font font = base.deriveFont(awtSize);
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = probe.createGraphics();
            FontMetrics metrics;

            try
            {
                metrics = g.getFontMetrics(font);
            }
            finally
            {
                g.dispose();
            }

            float strokePx = Math.max(0F, textStroke * (fontSize / Math.max(1F, fontHeight))) * pixelScale;
            float lineGap = lineHeight * unitPixels;
            int pad = Math.max(2, (int) Math.ceil(strokePx * 0.5F + 2F));

            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (int lineIndex = 0; lineIndex < layout.lines.length; lineIndex++)
            {
                LayerContentRenderer.TextLine line = layout.lines[lineIndex];
                float lineOffset = lineOffsetFor(align, layout, line);

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

                    GlyphVector vector = glyphFont.createGlyphVector(FRC, glyph.text);
                    Rectangle2D bounds = vector.getVisualBounds();

                    float x = (lineOffset + glyph.x) * unitPixels;
                    float left = x + (float) bounds.getMinX() - strokePx * 0.5F;
                    float right = x + (float) bounds.getMaxX() + strokePx * 0.5F;
                    float top = (float) bounds.getMinY() - strokePx * 0.5F;
                    float bottom = (float) bounds.getMaxY() + strokePx * 0.5F;

                    minX = Math.min(minX, left);
                    maxX = Math.max(maxX, right);
                    minY = Math.min(minY, top);
                    maxY = Math.max(maxY, bottom);
                }
            }

            if (minY == Float.MAX_VALUE)
            {
                return null;
            }

            float ascent = Math.max(metrics.getAscent(), -minY) + 1F;
            float descent = Math.max(metrics.getDescent(), maxY) + 1F;

            /* Alignment can move glyphs left of the layout origin; reserve the same transparent
             * margin on the left so those glyphs are not clipped either. */
            int leftPad = Math.max(pad, (int) Math.ceil(Math.max(0F, -minX) + pad));

            float contentRight = Math.max(layout.width * unitPixels, maxX);
            int width = (int) Math.ceil(contentRight + leftPad + pad);
            int height = (int) Math.ceil((layout.lines.length - 1) * lineGap + ascent + descent + pad * 2);

            if (width <= 2 || height <= 2 || width > MAX_TEXTURE || height > MAX_TEXTURE)
            {
                return null;
            }

            return new Geometry(width, height, pad, leftPad, ascent, descent, lineGap, unitPixels,
                strokePx, awtSize);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** {@link LayerContentRenderer}'s alignment rules, in layout units. */
    private static float lineOffsetFor(int align, LayerContentRenderer.TextLayout layout,
                                       LayerContentRenderer.TextLine line)
    {
        return switch (align)
        {
            case 1 -> (layout.width - line.width) * 0.5F;
            case 2 -> layout.width - line.width;
            default -> 0F;
        };
    }

    private static Entry rasterize(Font base, Font fallbackFont,
                                   mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer customRenderer,
                                   mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer fallbackRenderer,
                                   LayerContentRenderer.TextLayout layout, int fillColor, int strokeColor,
                                   int align, Geometry geometry)
    {
        try
        {
            float awtSize = Math.max(1F, geometry.awtSize);
            Font font = base.deriveFont(awtSize);
            BufferedImage image = new BufferedImage(geometry.width, geometry.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            try
            {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                java.awt.Color fill = new java.awt.Color(fillColor, true);
                java.awt.Color stroke = new java.awt.Color(strokeColor, true);

                for (int lineIndex = 0; lineIndex < layout.lines.length; lineIndex++)
                {
                    LayerContentRenderer.TextLine line = layout.lines[lineIndex];
                    float lineOffset = lineOffsetFor(align, layout, line);
                    float baseline = geometry.pad + lineIndex * geometry.lineGap + geometry.ascent;

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

                        GlyphVector vector = glyphFont.createGlyphVector(FRC, glyph.text);
                        Shape outline = vector.getOutline();
                        AffineTransform transform = AffineTransform.getTranslateInstance(
                            geometry.leftPad + (lineOffset + glyph.x) * geometry.unitPixels, baseline);
                        Shape placed = transform.createTransformedShape(outline);

                        if (geometry.strokePx > 0F && (strokeColor >>> 24) != 0)
                        {
                            g.setColor(stroke);
                            g.setStroke(new BasicStroke(geometry.strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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

            int[] pixels = image.getRGB(0, 0, geometry.width, geometry.height, null, 0, geometry.width);
            GlTexture texture = new GlTexture();

            texture.allocate(geometry.width, geometry.height).uploadRaw(pixels);

            return new Entry(texture, (long) geometry.width * geometry.height * 4L);
        }
        catch (Exception e)
        {
            return null;
        }
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

    /** Raster dimensions and the baseline placement, in raster pixels. */
    static final class Geometry
    {
        final int width;
        final int height;
        final int pad;
        final int leftPad;
        final float ascent;
        final float descent;
        final float lineGap;
        final float unitPixels;
        final float strokePx;
        /** AWT font size the geometry was measured for; cached with the raster. */
        final float awtSize;

        Geometry(int width, int height, int pad, int leftPad, float ascent, float descent,
                 float lineGap, float unitPixels, float strokePx, float awtSize)
        {
            this.width = width;
            this.height = height;
            this.pad = pad;
            this.leftPad = leftPad;
            this.ascent = ascent;
            this.descent = descent;
            this.lineGap = lineGap;
            this.unitPixels = unitPixels;
            this.strokePx = strokePx;
            this.awtSize = awtSize;
        }
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
