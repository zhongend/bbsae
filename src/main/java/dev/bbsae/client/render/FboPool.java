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

    /** Default VRAM ceiling for idle targets; -Dbbsae.fboBudgetBytes overrides (tests use this). */
    static final long DEFAULT_BUDGET_BYTES = 256L * 1024 * 1024;
    private static final Map<Long, Deque<GlFramebuffer>> CACHE = new HashMap<>();
    private static int idle;
    private static long idleBytes;

    /** Bytes one framebuffer occupies (RGBA8 colour + optional DEPTH24 renderbuffer). */
    static long bytesOf(GlFramebuffer framebuffer)
    {
        long pixels = (long) Math.max(1, framebuffer.width) * Math.max(1, framebuffer.height);

        return pixels * (framebuffer.depthBuffer != 0 ? 8L : 4L);
    }

    /** Idle (pooled) bytes, i.e. VRAM the pool is holding for reuse. */
    public static long bytes()
    {
        return idleBytes;
    }

    public static int idleCount()
    {
        return idle;
    }

    /** Budget read on every release so tests/debug can adjust the property at runtime. */
    static long budgetBytes()
    {
        return Math.max(0L, Long.getLong("bbsae.fboBudgetBytes", DEFAULT_BUDGET_BYTES));
    }

    /**
     * Deletes the oldest idle targets (insertion order within each size bucket) until the pool is
     * under {@code budget} bytes. Count caps alone were not a memory bound: 32 targets of a 4K
     * composition is 1 GB, while 32 targets of a thumbnail is 2 MB.
     */
    public static void trimTo(long budget)
    {
        long limit = Math.max(0L, budget);

        while (idleBytes > limit)
        {
            Deque<GlFramebuffer> victim = null;

            for (Deque<GlFramebuffer> deque : CACHE.values())
            {
                if (!deque.isEmpty())
                {
                    victim = deque;
                    break;
                }
            }

            if (victim == null)
            {
                idleBytes = 0;

                return;
            }

            GlFramebuffer framebuffer = victim.pollLast();

            idle -= 1;
            idleBytes -= bytesOf(framebuffer);
            framebuffer.delete();
        }
    }

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
            GlFramebuffer framebuffer = deque.pop();

            framebuffer.pooled = false;
            idle -= 1;
            idleBytes -= bytesOf(framebuffer);

            return framebuffer;
        }

        return new GlFramebuffer(w, h);
    }

    public static void release(GlFramebuffer framebuffer)
    {
        if (framebuffer == null)
        {
            return;
        }

        /* A double release puts the same target in the pool twice, so two later consumers render
         * into one texture — the classic "random UI flicker, no GL error" bug. Detect it loudly
         * instead of corrupting the frame. */
        if (framebuffer.pooled)
        {
            org.slf4j.LoggerFactory.getLogger("bbsae").warn(
                "FboPool: double release of framebuffer {} ({}x{})",
                framebuffer.id, framebuffer.width, framebuffer.height,
                Boolean.getBoolean("bbsae.debugFbo") ? new Throwable("double release") : null);

            return;
        }

        framebuffer.pooled = true;

        long key = ((long) framebuffer.width << 32) | (framebuffer.height & 0xFFFFFFFFL);
        Deque<GlFramebuffer> deque = CACHE.computeIfAbsent(key, (k) -> new ArrayDeque<>());

        if (idle >= MAX_IDLE)
        {
            framebuffer.delete();
            return;
        }

        deque.push(framebuffer);
        idle += 1;
        idleBytes += bytesOf(framebuffer);

        /* Byte budget, not just a count cap: 32 idle 4K targets would be ~1 GB. */
        trimTo(budgetBytes());
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
        idleBytes = 0;
    }
}
