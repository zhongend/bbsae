package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P1-11: the green bar's coverage BitSet is cached by the values that can change it.
 *
 * <p>{@code CompPrecache.coverage()} used to allocate a {@code BitSet} and walk every cached frame
 * on every displayed frame. The key is (generation, frame count, pre-render cursor, stale). These
 * tests pin the two behaviours that matter for the timeline: an idle editor reuses the same
 * instance forever, and a progressing pre-render (or any edit) invalidates it exactly once.</p>
 */
class TimelineCoverageCacheTest
{
    private static BitSet bits(int... frames)
    {
        BitSet bits = new BitSet();

        for (int frame : frames)
        {
            bits.set(frame);
        }

        return bits;
    }

    @Test
    void sameGenerationReusesTheSameBitset()
    {
        CompPrecache.CoverageCache cache = new CompPrecache.CoverageCache();
        BitSet built = bits(0, 1, 5, 8);

        assertNull(cache.cached(2, 9, -1, false), "an empty cache must ask the caller to build");
        assertSame(built, cache.publish(built, 2, 9, -1, false));

        BitSet stored = cache.cached(2, 9, -1, false);

        assertSame(built, stored, "the cached instance is returned, not a copy");

        /* A static editor asks once per displayed frame; the count of rebuilds must stay at one
         * for as long as the key does not change. */
        for (int i = 0; i < 5000; i++)
        {
            assertSame(built, cache.cached(2, 9, -1, false));
        }

        assertEquals(1, cache.rebuilds());
    }

    @Test
    void preRenderProgressInvalidatesCoverage()
    {
        CompPrecache.CoverageCache cache = new CompPrecache.CoverageCache();
        BitSet first = bits(0, 1);
        BitSet second = bits(0, 1, 2);

        cache.publish(first, 4, 3, 2, false);
        assertSame(first, cache.cached(4, 3, 2, false), "the job cursor may repeat between ticks");

        assertNull(cache.cached(4, 3, 3, false), "a pre-render that advanced must rebuild the bar");
        cache.publish(second, 4, 3, 3, false);
        assertSame(second, cache.cached(4, 3, 3, false));
        assertEquals(2, cache.rebuilds());

        assertNull(cache.cached(4, 4, 3, false), "a newly cached frame changes the frame count");
    }

    @Test
    void generationAndStaleFlagInvalidateCoverage()
    {
        CompPrecache.CoverageCache cache = new CompPrecache.CoverageCache();
        BitSet built = bits(0, 7);

        cache.publish(built, 1, 8, -1, false);

        assertNull(cache.cached(2, 8, -1, false), "a signature edit bumps the generation");
        assertNull(cache.cached(1, 8, -1, true), "a stale cache must not serve the old green bar");

        cache.publish(built, 2, 8, -1, false);
        assertEquals(2, cache.rebuilds());
    }

    @Test
    void jobCursorOnlyMattersWhileThisCacheIsTheJob()
    {
        CompPrecache.CoverageCache cache = new CompPrecache.CoverageCache();
        BitSet bits = bits(3);

        /* Not running: both calls encode the cursor as -1 and must share one BitSet. */
        cache.publish(bits, 0, 1, -1, false);
        assertSame(bits, cache.cached(0, 1, -1, false));

        /* Running: the cursor is carried into the key. */
        BitSet running = bits(3, 4);

        cache.publish(running, 0, 2, 4, false);
        assertSame(running, cache.cached(0, 2, 4, false));
        assertNull(cache.cached(0, 2, -1, false), "stopping the job restores static coverage");
    }

    @Test
    void cachedEmptyCoverageIsAlsoReused()
    {
        CompPrecache.CoverageCache cache = new CompPrecache.CoverageCache();
        BitSet empty = new BitSet();

        cache.publish(empty, 0, 0, -1, true);

        assertSame(empty, cache.cached(0, 0, -1, true));
        assertEquals(0, empty.cardinality());
        assertEquals(1, cache.rebuilds());
    }
}
