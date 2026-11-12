package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Hit/miss rules of the pen's frame-scoped path evaluation memo.
 *
 * <p>The cache is keyed by (layer identity, path identity, frame, revision, component-array length)
 * — exactly the things that can change the evaluated values: another layer/path, a different
 * playhead, or an edit (the pen bumps the revision on every changed/structureChanged). Layer and
 * path identity also protects against undo/redo, which can leave a stale-but-equal-looking object
 * graph behind.</p>
 */
public class PenEvaluationCacheTest
{
    private static float[] values(float seed)
    {
        return new float[] {seed, seed + 1F, seed + 2F};
    }

    @Test
    public void sameKeyReturnsTheCachedArray()
    {
        UIPenTool.EvaluationCache cache = new UIPenTool.EvaluationCache();
        Object layer = new Object();
        Object path = new Object();
        float[] values = values(1F);

        assertNull(cache.fetch(layer, path, 12F, 0L, 3), "empty cache misses");
        cache.store(layer, path, 12F, 0L, values);
        assertSame(values, cache.fetch(layer, path, 12F, 0L, 3));
        assertArrayEquals(new float[] {1F, 2F, 3F}, cache.fetch(layer, path, 12F, 0L, 3));
    }

    @Test
    public void frameRevisionLayerPathAndLengthAllInvalidate()
    {
        UIPenTool.EvaluationCache cache = new UIPenTool.EvaluationCache();
        Object layer = new Object();
        Object path = new Object();
        Object otherLayer = new Object();
        Object otherPath = new Object();

        cache.store(layer, path, 12F, 5L, values(0F));

        assertNotNull(cache.fetch(layer, path, 12F, 5L, 3), "control");
        assertNull(cache.fetch(layer, path, 13F, 5L, 3), "different frame");
        assertNull(cache.fetch(layer, path, 12F, 6L, 3), "different revision (an edit happened)");
        assertNull(cache.fetch(otherLayer, path, 12F, 5L, 3), "different layer");
        assertNull(cache.fetch(layer, otherPath, 12F, 5L, 3), "different path");
        assertNull(cache.fetch(layer, path, 12F, 5L, 6), "different point count");
    }

    @Test
    public void invalidateDropsTheMemo()
    {
        UIPenTool.EvaluationCache cache = new UIPenTool.EvaluationCache();
        Object layer = new Object();
        Object path = new Object();

        cache.store(layer, path, 0F, 0L, values(7F));
        assertNotNull(cache.fetch(layer, path, 0F, 0L, 3));

        cache.invalidate();

        assertNull(cache.fetch(layer, path, 0F, 0L, 3));
        assertNull(cache.fetch(layer, path, 0F, 0L, 3), "stays empty until the next store");
    }

    @Test
    public void onlyOneEntryIsKept()
    {
        UIPenTool.EvaluationCache cache = new UIPenTool.EvaluationCache();
        Object layer = new Object();
        Object first = new Object();
        Object second = new Object();

        cache.store(layer, first, 0F, 0L, values(1F));
        cache.store(layer, second, 0F, 0L, values(2F));

        assertNull(cache.fetch(layer, first, 0F, 0L, 3), "one-entry memo: the previous path is evicted");
        assertNotNull(cache.fetch(layer, second, 0F, 0L, 3));
    }
}
