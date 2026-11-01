package dev.bbsae.client.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System font discovery: extension filtering, a real TTF name table, and graceful fallbacks for
 * junk files. Collections are supported (the importer extracts face 0), which matters because most
 * Chinese fonts on Windows are .ttc.
 */
public class SystemFontsTest
{
    @Test
    public void ttfOtfAndCollectionsAreSupported(@TempDir Path folder) throws IOException
    {
        Path ttf = Files.createFile(folder.resolve("a.ttf"));
        Path otf = Files.createFile(folder.resolve("b.otf"));
        Path ttc = Files.createFile(folder.resolve("c.ttc"));
        Path woff = Files.createFile(folder.resolve("d.woff"));

        assertTrue(SystemFonts.isSupported(ttf));
        assertTrue(SystemFonts.isSupported(otf));
        assertTrue(SystemFonts.isSupported(ttc), "collections are listed; the importer extracts a face");
        assertFalse(SystemFonts.isSupported(woff));
        assertFalse(SystemFonts.isSupported(folder.resolve("missing.ttf")));
    }

    @Test
    public void realWindowsFontNameComesFromTheNameTable()
    {
        Path arial = Path.of("C:\\Windows\\Fonts\\arial.ttf");

        Assumptions.assumeTrue(Files.isRegularFile(arial));

        String name = SystemFonts.fullName(arial);

        assertNotNull(name, "Arial should have a name table");
        assertTrue(name.toLowerCase(java.util.Locale.ROOT).contains("arial"), "unexpected name: " + name);
    }

    @Test
    public void junkFallsBackInsteadOfThrowing(@TempDir Path folder) throws IOException
    {
        Path junk = folder.resolve("junk.ttf");
        Path emptyCollection = folder.resolve("collection.ttf");

        Files.write(junk, new byte[] {1, 2, 3, 4});
        /* ttcf header that claims zero faces: nothing to name, so the picker uses the file stem. */
        Files.write(emptyCollection, new byte[] {'t', 't', 'c', 'f', 0, 1, 0, 0, 0, 0, 0, 0});

        assertNull(SystemFonts.fullName(junk));
        assertNull(SystemFonts.fullName(emptyCollection));
    }

    @Test
    public void collectionFaceNameIsReadFromTheFirstFace()
    {
        String windir = System.getenv("WINDIR");

        Assumptions.assumeTrue(windir != null);

        Path collected = Path.of(windir, "Fonts", "msyh.ttc");

        Assumptions.assumeTrue(Files.isRegularFile(collected));

        String name = SystemFonts.fullName(collected);

        assertNotNull(name, "the first face of msyh.ttc must have a name table");
        assertFalse(name.isBlank());
    }

    @Test
    public void installedFontsAreListedInOrder()
    {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));

        List<SystemFonts.Entry> fonts = SystemFonts.list();

        assertFalse(fonts.isEmpty(), "Windows should expose at least one TTF/OTF");

        for (int i = 1; i < fonts.size(); i++)
        {
            int previous = fonts.get(i - 1).displayName.compareToIgnoreCase(fonts.get(i).displayName);

            assertTrue(previous <= 0, "system fonts are sorted by display name");
        }
    }
}
