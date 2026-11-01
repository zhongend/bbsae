package dev.bbsae.client.ui;

import dev.bbsae.client.render.FontCoverage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fonts are a container question before they are a rendering question.
 *
 * <p>BBS' stb loader reads a single font at offset 0. Most Chinese fonts ship as {@code .ttc}
 * collections (微软雅黑, 宋体, 黑体…), which used to be rejected outright — "imported font does not
 * display" was mostly this. Extraction turns one face of a collection into a normal sfnt, which is
 * then loadable by both BBS and every other consumer.</p>
 */
public class FontImportTtcTest
{
    private static File collection()
    {
        String windir = System.getenv("WINDIR");

        if (windir != null)
        {
            for (String name : new String[] {"msyh.ttc", "simsun.ttc", "msjh.ttc", "msgothic.ttc"})
            {
                File file = new File(windir, "Fonts/" + name);

                if (file.isFile() && file.length() < 64L * 1024L * 1024L)
                {
                    return file;
                }
            }
        }

        return null;
    }

    @Test
    public void aStandaloneFontIsNotACollection()
    {
        byte[] ttf = {0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        assertFalse(FontImport.isCollection(ttf));
        assertEquals(0, FontImport.faceCount(ttf));
        assertNull(FontImport.headerError(ttf), "a plain sfnt passes the header check");
    }

    @Test
    public void woffIsStillRejected() throws Exception
    {
        byte[] woff = {'w', 'O', 'F', 'F', 0, 0, 0, 0, 0, 0, 0, 0};
        String error = FontImport.headerError(woff);

        assertNotNull(error);
        assertTrue(error.contains("WOFF") || error.contains("woff"), error);
    }

    @Test
    public void collectionFaceIsExtractedAsAStandaloneFont() throws Exception
    {
        File file = collection();

        Assumptions.assumeTrue(file != null, "no TTC font on this machine");

        byte[] data = Files.readAllBytes(file.toPath());

        assertTrue(FontImport.isCollection(data), "msyh.ttc must be recognised as a collection");
        assertTrue(FontImport.faceCount(data) > 0, "the collection must report its faces");

        byte[] face = FontImport.extractFace(data, 0);

        assertFalse(FontImport.isCollection(face), "the extracted face is not a collection");
        assertNull(FontImport.headerError(face), "the extracted face is a valid sfnt");

        /* The real acceptance criterion: the same stb path BBS uses must accept it, and a Chinese
         * font must actually have Chinese glyphs. */
        Path temp = Files.createTempFile("bbsae-face", ".ttf");

        try
        {
            Files.write(temp, face);

            assertTrue(FontCoverage.covers(temp.toFile(), '\u4e2d'),
                "the extracted face keeps the font's CJK glyphs");
            assertTrue(FontCoverage.covers(temp.toFile(), 'A') || FontCoverage.covers(temp.toFile(), '\u4e00'),
                "the extracted face has some glyphs at all");
        }
        finally
        {
            FontCoverage.clear();
            Files.deleteIfExists(temp);
        }
    }

    @Test
    public void outOfRangeFaceIndexIsRejectedCleanly() throws Exception
    {
        File file = collection();

        Assumptions.assumeTrue(file != null, "no TTC font on this machine");

        byte[] data = Files.readAllBytes(file.toPath());

        try
        {
            FontImport.extractFace(data, 9999);
        }
        catch (java.io.IOException expected)
        {
            assertTrue(expected.getMessage().contains("第") || expected.getMessage().contains("偏移"), expected.getMessage());

            return;
        }

        throw new AssertionError("an out-of-range face index must throw");
    }

    @Test
    public void systemFontListIncludesCollectionsWithReadableNames()
    {
        SystemFonts.refresh();

        List<SystemFonts.Entry> fonts = SystemFonts.list();

        Assumptions.assumeFalse(fonts.isEmpty(), "no system fonts on this machine");

        assertTrue(fonts.stream().anyMatch((entry) ->
            entry.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(".ttc")),
            "the picker lists collections instead of hiding the fonts users need");

        for (SystemFonts.Entry entry : fonts)
        {
            assertNotNull(entry.displayName);
            assertFalse(entry.displayName.isBlank(), "every entry has a label");
        }
    }

    @Test
    public void sanitizeNameKeepsChineseAndNeverProducesAnEmptyName()
    {
        assertEquals("思源黑体.ttf", FontImport.sanitizeName("思源黑体.ttf"));
        assertEquals("a_b.ttf", FontImport.sanitizeName("a:b.ttf"));
        assertEquals("font.ttf", FontImport.sanitizeName("   "));
        assertEquals("x.ttf", FontImport.withExtension("x"));
        assertEquals("x.TTF", FontImport.withExtension("x.TTF"));
    }
}
