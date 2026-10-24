package dev.bbsae.client.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Render-thread FBO cache. Frames are sequential, so a few targets per resolution are enough.
 * The cap is deliberately conservative; a malformed project must not be able to allocate an
 * unbounded amount of VRAM.
 */
public final class FboPool
{
    /**
     * Peak simultaneous targets is small (accumulation + one layer + an effect ping-pong), but a
     * frame touches more distinct buffers than that. Keeping only 12 meant the pool destroyed and
     * re-created 8 MB textures across a frame boundary, which is a hard stutter; 32 comfortably
     * covers a busy composition without unbounded VRAM.
     */
    private static final int MAX_IDLE = 32;
    private static final Map<Long, Deque<GlFramebuffer>> CACHE = new HashMap<>();
    private static int idle;

    private FboPool()
    {}

    public static GlFramebuffer acquire(int width, int height)
    {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        long key = ((long) w << 32) | (h & 0xFFFFFFFFL);
        Deque<GlFramebuffer> deque = CACHE.get(key);

        if (deque != null && !deque.isEmpty())
        {
            idle -= 1;
            return deque.pop();
        }

        return new GlFramebuffer(w, h);
    }

    public static void release(GlFramebuffer framebuffer)
    {
        if (framebuffer == null)
        {
            return;
        }

        long key = ((long) framebuffer.width << 32) | (framebuffer.height & 0xFFFFFFFFL);
        Deque<GlFramebuffer> deque = CACHE.computeIfAbsent(key, (k) -> new ArrayDeque<>());

        if (idle >= MAX_IDLE)
        {
            framebuffer.delete();
            return;
        }

        deque.push(framebuffer);
        idle += 1;
    }

    public static void clear()
    {
        for (Deque<GlFramebuffer> deque : CACHE.values())
        {
            for (GlFramebuffer framebuffer : deque)
            {
                framebuffer.delete();
            }
        }

        CACHE.clear();
        idle = 0;
    }
}
