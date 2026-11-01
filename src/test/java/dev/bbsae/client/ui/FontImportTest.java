package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure part of font import: container checks, filename preservation and collision handling.
 *
 * <p>These failures were invisible before: a Chinese font was renamed to {@code ____.ttf}, a TTC
 * was accepted and then rejected by stb, and re-importing the same font created duplicates. None
 * of those need a running client to test.</p>
 */
public class FontImportTest
{
    @Test
    public void headerRecognisesTheContainersBbsCanRead()
    {
        assertNull(FontImport.headerError(new byte[] {0, 1, 0, 0}));
        assertNull(FontImport.headerError("true".getBytes()));
        assertNull(FontImport.headerError("typ1".getBytes()));
        assertNull(FontImport.headerError("OTTO".getBytes()));

        assertTrue(FontImport.headerError("ttcf".getBytes()).contains("TTC"));
        assertTrue(FontImport.headerError("wOFF".getBytes()).contains("WOFF"));
        assertTrue(FontImport.headerError("wOF2".getBytes()).contains("WOFF"));
        assertTrue(FontImport.headerError("junk".getBytes()).contains("不是"));
        assertNotNull(FontImport.headerError(new byte[0]));
        assertNotNull(FontImport.headerError(null));
    }

    @Test
    public void namesKeepUnicodeAndLoseOnlyPathCharacters()
    {
        assertEquals("思源黑体.ttf", FontImport.sanitizeName("思源黑体.ttf"));
        assertEquals("My Font (Bold).otf", FontImport.sanitizeName("My Font (Bold).otf"));
        assertEquals("a_b_c.ttf", FontImport.sanitizeName("a/b:c.ttf"));
        assertEquals("font.ttf", FontImport.sanitizeName("   "));
        assertEquals("font.ttf", FontImport.sanitizeName(null));

        String longName = FontImport.sanitizeName("x".repeat(200) + ".ttf");

        assertTrue(longName.length() <= 96, "long names are shortened");
        assertTrue(longName.endsWith(".ttf"), "the extension survives truncation");
    }

    @Test
    public void extensionsAreCaseInsensitive()
    {
        assertEquals("a.TTF", FontImport.withExtension("a.TTF"));
        assertEquals("a.otf", FontImport.withExtension("a.otf"));
        assertEquals("a.ttf", FontImport.withExtension("a"));
    }

    @Test
    public void identicalFontReusesTheCopyButDifferentBytesGetASuffix(@TempDir Path folder) throws IOException
    {
        Path source = folder.resolve("source.ttf");

        Files.write(source, new byte[] {1, 2, 3, 4});

        Path first = FontImport.uniqueTarget(folder, "思源黑体.ttf", source);

        assertEquals("思源黑体.ttf", first.getFileName().toString());

        Files.write(first, new byte[] {1, 2, 3, 4});

        Path again = FontImport.uniqueTarget(folder, "思源黑体.ttf", source);

        assertEquals(first, again, "importing the same bytes again must not duplicate the file");

        Path other = folder.resolve("other.ttf");

        Files.write(other, new byte[] {9, 9, 9, 9});

        Path second = FontImport.uniqueTarget(folder, "思源黑体.ttf", other);

        assertEquals("思源黑体-2.ttf", second.getFileName().toString());
    }

    @Test
    public void aRealWindowsFontPassesTheHeaderCheck() throws IOException
    {
        Path arial = Path.of("C:\\Windows\\Fonts\\arial.ttf");

        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(arial));

        assertNull(FontImport.headerError(Files.readAllBytes(arial)));
    }

    @Test
    public void sameContentComparesSizeAndBytes(@TempDir Path folder) throws IOException
    {
        Path a = folder.resolve("a.ttf");
        Path b = folder.resolve("b.ttf");

        Files.write(a, new byte[] {1, 2, 3});
        Files.write(b, new byte[] {1, 2, 3});

        assertTrue(FontImport.sameContent(a, b));

        Files.write(b, new byte[] {1, 2, 4});
        assertTrue(!FontImport.sameContent(a, b));
    }
}
