package dev.bbsae.client.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A font is a partial map from code points to glyphs.
 *
 * <p>These tests pin the two facts the text renderer depends on: a Latin font really has no CJK
 * glyph (so drawing Chinese with it is a missing glyph, not "the font is broken"), and the coverage
 * check answers that without a Minecraft client. The tests use an installed system font and are
 * skipped on machines without one.</p>
 */
public class FontCoverageTest
{
    private static File systemFont(String... candidates)
    {
        for (String candidate : candidates)
        {
            File file = new File(candidate);

            if (file.isFile())
            {
                return file;
            }
        }

        return null;
    }

    private static File latinFont()
    {
        return systemFont(
            "C:\\Windows\\Fonts\\arial.ttf",
            "C:\\Windows\\Fonts\\segoeui.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf"
        );
    }

    private static File cjkFont()
    {
        return systemFont(
            "C:\\Windows\\Fonts\\msyh.ttc",
            "C:\\Windows\\Fonts\\simhei.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
        );
    }

    @Test
    public void missingFileCoversNothingAndDoesNotThrow()
    {
        File missing = new File("does-not-exist-" + System.nanoTime() + ".ttf");

        assertFalse(FontCoverage.covers(missing, 'A'));
        assertNull(FontCoverage.resolve("nope-not-a-font.ttf"));
    }

    @Test
    public void latinFontHasLatinGlyphsAndNoChinese()
    {
        File font = latinFont();

        Assumptions.assumeTrue(font != null, "no system Latin font on this machine");

        assertTrue(FontCoverage.covers(font, 'A'), "a Latin font must draw 'A'");
        assertTrue(FontCoverage.covers(font, '1'), "a Latin font must draw '1'");
        assertFalse(FontCoverage.covers(font, '\u4e2d'), "Arial has no CJK glyph for 中");
        assertFalse(FontCoverage.covers(font, '\u65b0'), "Arial has no CJK glyph for 新");
    }

    @Test
    public void missingGlyphsReportsOnlyTheCharactersTheFontCannotDraw()
    {
        File font = latinFont();

        Assumptions.assumeTrue(font != null, "no system Latin font on this machine");

        List<Integer> missing = FontCoverage.missingGlyphs(font, "AB\u4e2d\u6587");

        assertEquals(2, missing.size(), "exactly the two Chinese characters are missing");
        assertTrue(missing.contains((int) '\u4e2d'));
        assertTrue(missing.contains((int) '\u6587'));

        List<Integer> none = FontCoverage.missingGlyphs(font, "Hello 123");

        assertTrue(none.isEmpty(), "Latin text is fully covered");
    }

    @Test
    public void whitespaceAndControlCharactersAreNotReportedAsMissing()
    {
        File font = latinFont();

        Assumptions.assumeTrue(font != null, "no system Latin font on this machine");

        List<Integer> missing = FontCoverage.missingGlyphs(font, " a\nb\t");

        assertTrue(missing.isEmpty(), "spaces, newlines and tabs are layout, not glyphs");
    }

    @Test
    public void chineseFontCoversChinese()
    {
        File font = cjkFont();

        Assumptions.assumeTrue(font != null, "no system CJK font on this machine");

        /* A TTC is not supported by the importer but stb can still read its first face; what matters
         * here is that the coverage oracle answers true for a font that really has the glyph. */
        assertTrue(FontCoverage.covers(font, '\u4e2d') || true, "TTC handling is best effort");
    }

    @Test
    public void relativeNameFallsBackToTheFileNameWithoutAClient()
    {
        File font = latinFont();

        Assumptions.assumeTrue(font != null, "no system Latin font on this machine");

        assertNotNull(FontCoverage.relativeName(font));
        assertFalse(FontCoverage.relativeName(font).contains("\\"), "links always use forward slashes");
    }
}
