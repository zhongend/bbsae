package dev.bbsae.client.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the core of the text-renderer performance fix: after the first coverage resolution a
 * frame performs <b>zero</b> file system calls.
 *
 * <p>Before this change every {@code covers()} call did {@code isFile()} twice, {@code length()}
 * twice, {@code getCanonicalPath()}, {@code lastModified()} and {@code length()} again. A 200
 * glyph text layer with a stroke called it 1000 times per frame — thousands of stats and canonical
 * path resolutions per frame, the single largest cost of text rendering.</p>
 *
 * <p>The counters are intentionally cheap always-on longs (production cost: one increment per
 * code-point query), so this test measures the real path rather than a test-only stub.</p>
 */
public class FontCoverageIoTest
{
    private File font;

    @BeforeEach
    public void setUp()
    {
        FontCoverage.clear();
        FontCoverage.resetIoCounters();
        this.font = latinFont();
    }

    @AfterEach
    public void tearDown()
    {
        FontCoverage.clear();
        FontCoverage.resetIoCounters();
    }

    private static File latinFont()
    {
        String[] candidates = {
            "C:\\Windows\\Fonts\\arial.ttf",
            "C:\\Windows\\Fonts\\segoeui.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf"
        };

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

    private File requireFont()
    {
        Assumptions.assumeTrue(this.font != null, "no system Latin font on this machine");

        return this.font;
    }

    @Test
    public void firstCheckReadsAndLaterChecksDoNoIo()
    {
        File font = this.requireFont();

        assertTrue(FontCoverage.covers(font, 'A'));

        assertTrue(FontCoverage.readCalls() >= 1L, "the first query has to read the font once");
        assertTrue(FontCoverage.canonicalPathCalls() >= 1L, "the first query resolves the canonical path once");

        FontCoverage.resetIoCounters();

        assertTrue(FontCoverage.covers(font, 'A'));
        assertTrue(FontCoverage.covers(font, 'B'));
        assertTrue(FontCoverage.covers(font, '1'));

        assertEquals(0L, FontCoverage.canonicalPathCalls(), "canonicalPath is not called again");
        assertEquals(0L, FontCoverage.statCalls(), "isFile/length are not called again");
        assertEquals(0L, FontCoverage.readCalls(), "the font file is not read again");
        assertTrue(FontCoverage.lookupHits() >= 3L, "every check hits the identity map");
    }

    @Test
    public void secondFileInstanceForTheSamePathDoesNotReadTheFontAgain()
    {
        File font = this.requireFont();

        assertTrue(FontCoverage.covers(font, 'A'));
        FontCoverage.resetIoCounters();

        File samePath = new File(font.getAbsolutePath());

        assertTrue(FontCoverage.covers(samePath, 'A'), "same path, new File instance");
        assertEquals(0L, FontCoverage.readCalls(), "the cmap is keyed by canonical path too");
        assertEquals(1L, FontCoverage.canonicalPathCalls(), "one canonical path for the new File instance");
        assertTrue(FontCoverage.statCalls() > 0L, "a new File identity needs one readability probe");
    }

    @Test
    public void repeatedGlyphChecksUseTheBitmapWithoutCallingStb()
    {
        File font = this.requireFont();

        /* Warm the font, then query a fresh set of code points twice. */
        assertTrue(FontCoverage.covers(font, 'A'));
        FontCoverage.resetIoCounters();

        for (char c = 'a'; c <= 'z'; c++)
        {
            FontCoverage.covers(font, c);
        }

        long firstPass = FontCoverage.stbQueries();

        assertEquals(26L, firstPass, "each previously unseen code point asks stb exactly once");

        for (char c = 'a'; c <= 'z'; c++)
        {
            FontCoverage.covers(font, c);
        }

        assertEquals(firstPass, FontCoverage.stbQueries(), "the long[1024] BMP bitmap answers repeated glyphs");
        assertEquals(0L, FontCoverage.readCalls());

        /* A missing glyph is cached in the same bitmap, including the negative answer. */
        FontCoverage.covers(font, '\u4e2d');
        long afterMissing = FontCoverage.stbQueries();

        FontCoverage.covers(font, '\u4e2d');
        assertEquals(afterMissing, FontCoverage.stbQueries(), "a missing glyph is cached too");
    }

    @Test
    public void missingFileIsRememberedWithoutFurtherIo()
    {
        File missing = new File("does-not-exist-" + System.nanoTime() + ".ttf");

        assertFalse(FontCoverage.covers(missing, 'A'));

        FontCoverage.resetIoCounters();

        assertFalse(FontCoverage.covers(missing, 'A'));
        assertFalse(FontCoverage.covers(missing, 'B'));

        assertEquals(0L, FontCoverage.canonicalPathCalls());
        assertEquals(0L, FontCoverage.statCalls(), "the unreadable sentinel costs no stat");
        assertEquals(0L, FontCoverage.readCalls());
    }

    @Test
    public void clearForcesARealReload()
    {
        File font = this.requireFont();

        assertTrue(FontCoverage.covers(font, 'A'));
        FontCoverage.clear();
        FontCoverage.resetIoCounters();

        assertTrue(FontCoverage.covers(font, 'A'));
        assertTrue(FontCoverage.readCalls() >= 1L, "after clear() the font must be read again, not stat-checked");
    }

    @Test
    public void missingGlyphReportingReusesTheLoadedCoverage()
    {
        File font = this.requireFont();

        assertNotNull(font);

        List<Integer> first = FontCoverage.missingGlyphs(font, "AB\u4e2d\u6587");

        assertFalse(first.isEmpty());
        FontCoverage.resetIoCounters();

        List<Integer> second = FontCoverage.missingGlyphs(font, "AB\u4e2d\u6587");

        assertEquals(first, second);
        assertEquals(0L, FontCoverage.canonicalPathCalls(), "the inspector path shares the cache");
        assertEquals(0L, FontCoverage.readCalls());
    }
}
