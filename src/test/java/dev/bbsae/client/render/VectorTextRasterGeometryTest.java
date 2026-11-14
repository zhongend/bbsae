package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vector text raster must contain every ink pixel of the block.
 *
 * <p>The old raster was sized by the BBS line box ({@code lines * lineHeight * fontSize/fontHeight}).
 * A font's real ascent + descent can exceed that box (most visibly for descenders and CJK fallback
 * fonts), so the last line's bottom was drawn below the texture edge and cut off. These tests
 * reconstruct the outline bounds independently and require the planned raster to contain them.</p>
 */
public class VectorTextRasterGeometryTest
{
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    @Test
    public void descendersAndStrokeFitInsideTheRaster()
    {
        Font base = new Font(Font.DIALOG, Font.PLAIN, 64);
        Font awt = base.deriveFont(128F);
        LayerContentRenderer.TextLayout layout = layout(base, "gjpqy AQ 0123");
        float pixelScale = 2F;
        float unitPixels = 2F;
        float stroke = 6F;
        VectorTextRaster.Geometry geometry = VectorTextRaster.plan(
            base, null, layout, null, null,
            64F, 64F, 1.15F, stroke, pixelScale, unitPixels, 0);

        assertNotNull(geometry, "the block must be rasterizable");

        float strokePx = stroke * (64F / 64F) * pixelScale;
        float top = geometry.pad + geometry.ascent + visualTop(awt, layout) - strokePx * 0.5F;
        float bottom = geometry.pad + geometry.ascent + visualBottom(awt, layout) + strokePx * 0.5F;
        float right = visualRight(awt, layout, unitPixels) + strokePx * 0.5F;

        assertTrue(top >= 0F, "ink above the raster: top=" + top);
        assertTrue(bottom <= geometry.height - 1F,
            "descenders/stroke are clipped: bottom=" + bottom + " height=" + geometry.height);
        assertTrue(right <= geometry.width - 1F,
            "the last glyph is clipped: right=" + right + " width=" + geometry.width);
        assertTrue(geometry.height > 64F * 1.15F * 2F,
            "the raster must be at least as tall as the old line-box raster; it is " + geometry.height);
    }

    @Test
    public void multiLineWithDifferentWidthsKeepsEveryLineInside()
    {
        Font base = new Font(Font.DIALOG, Font.PLAIN, 40);
        Font awt = base.deriveFont(80F);
        LayerContentRenderer.TextLayout layout = layout(base, "WWWWWWWW", "ii");
        VectorTextRaster.Geometry geometry = VectorTextRaster.plan(
            base, null, layout, null, null,
            40F, 40F, 1.1F, 0F, 2F, 2F, 2);

        assertNotNull(geometry);

        float bottom = geometry.pad + geometry.ascent + (layout.lines.length - 1) * geometry.lineGap
            + visualBottom(awt, layout);
        float right = visualRight(awt, layout, 2F);

        assertTrue(bottom <= geometry.height - 1F,
            "the second line's descent is clipped: bottom=" + bottom + " height=" + geometry.height);
        assertTrue(right <= geometry.width - 1F,
            "the right-aligned widest line is clipped: right=" + right + " width=" + geometry.width);
        assertTrue(geometry.leftPad >= geometry.pad);
    }

    private static LayerContentRenderer.TextLayout layout(Font font, String... strings)
    {
        List<LayerContentRenderer.TextLine> lines = new ArrayList<>();
        float width = 0F;
        int count = 0;

        for (String text : strings)
        {
            List<LayerContentRenderer.Glyph> glyphs = new ArrayList<>();
            float x = 0F;

            for (int i = 0; i < text.length(); )
            {
                int codePoint = text.codePointAt(i);
                String glyph = new String(Character.toChars(codePoint));
                Rectangle2D bounds = font.getStringBounds(glyph, FRC);

                glyphs.add(new LayerContentRenderer.Glyph(codePoint, glyph, null, 1F,
                    (float) bounds.getWidth(), x));
                x += (float) bounds.getWidth();
                count += 1;
                i += Character.charCount(codePoint);
            }

            lines.add(new LayerContentRenderer.TextLine(text, x, glyphs.size(), glyphs, null));
            width = Math.max(width, x);
        }

        return new LayerContentRenderer.TextLayout(strings[0],
            lines.toArray(new LayerContentRenderer.TextLine[0]), width, count);
    }

    private static float visualTop(Font awt, LayerContentRenderer.TextLayout layout)
    {
        float min = 0F;

        for (LayerContentRenderer.TextLine line : layout.lines)
        {
            for (LayerContentRenderer.Glyph glyph : line.glyphs)
            {
                min = Math.min(min, (float) awt.getStringBounds(glyph.text, FRC).getMinY());
            }
        }

        return min;
    }

    private static float visualBottom(Font awt, LayerContentRenderer.TextLayout layout)
    {
        float max = 0F;

        for (LayerContentRenderer.TextLine line : layout.lines)
        {
            for (LayerContentRenderer.Glyph glyph : line.glyphs)
            {
                max = Math.max(max, (float) awt.getStringBounds(glyph.text, FRC).getMaxY());
            }
        }

        return max;
    }

    private static float visualRight(Font awt, LayerContentRenderer.TextLayout layout, float unitPixels)
    {
        float max = 0F;

        for (LayerContentRenderer.TextLine line : layout.lines)
        {
            for (LayerContentRenderer.Glyph glyph : line.glyphs)
            {
                max = Math.max(max, glyph.x * unitPixels
                    + (float) awt.getStringBounds(glyph.text, FRC).getMaxX());
            }
        }

        return max;
    }
}
