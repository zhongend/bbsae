package dev.bbsae.client.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Render-thread framebuffer cache. Frames are sequential, so one target per resolution is enough;
 * the cache is capped so a malformed project cannot allocate unbounded GPU memory.
 */
public final class CompFramebufferPool
{
    private static final int MAX_ENTRIES = 4;
    private static final Map<Long, CompFramebuffer> CACHE = new HashMap<>();

    private CompFramebufferPool()
    {}

    public static CompFramebuffer get(int width, int height)
    {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        long key = ((long) w << 32) | (h & 0xFFFFFFFFL);

        CompFramebuffer framebuffer = CACHE.get(key);

        if (framebuffer == null)
        {
            if (CACHE.size() >= MAX_ENTRIES)
            {
                Long oldest = CACHE.keySet().iterator().next();

                CACHE.remove(oldest).close();
            }

            framebuffer = new CompFramebuffer(w, h);
            CACHE.put(key, framebuffer);
        }

        return framebuffer;
    }

    public static void clear()
    {
        for (CompFramebuffer framebuffer : CACHE.values())
        {
            framebuffer.close();
        }

        CACHE.clear();
    }
}
