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
 * junk/TTC files. The real-font assertions are skipped on machines without that font.
 */
public class SystemFontsTest
{
    @Test
    public void onlyTtfAndOtfAreSupported(@TempDir Path folder) throws IOException
    {
        Path ttf = Files.createFile(folder.resolve("a.ttf"));
        Path otf = Files.createFile(folder.resolve("b.otf"));
        Path ttc = Files.createFile(folder.resolve("c.ttc"));
        Path woff = Files.createFile(folder.resolve("d.woff"));

        assertTrue(SystemFonts.isSupported(ttf));
        assertTrue(SystemFonts.isSupported(otf));
        assertFalse(SystemFonts.isSupported(ttc));
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
    public void junkAndCollectionsFallBackInsteadOfThrowing(@TempDir Path folder) throws IOException
    {
        Path junk = folder.resolve("junk.ttf");
        Path ttc = folder.resolve("collection.ttf");

        Files.write(junk, new byte[] {1, 2, 3, 4});
        Files.write(ttc, new byte[] {'t', 't', 'c', 'f', 0, 1, 0, 0, 0, 0, 0, 0});

        assertNull(SystemFonts.fullName(junk));
        assertNull(SystemFonts.fullName(ttc));
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
