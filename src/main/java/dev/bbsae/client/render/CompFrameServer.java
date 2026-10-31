package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The compositor's own frame source — the "AE encoder" pipeline.
 *
 * <h2>Why a frame server exists at all</h2>
 * <p>In BBS mode the composition is an overlay drawn into whatever framebuffer the game happens to
 * be rendering, at whatever size the window is, at whatever time the display refresh lands on. That
 * is exactly right for a film overlay and exactly wrong for an editor: the picture the artist sees
 * is not a frame, it is a slice of a screen.</p>
 *
 * <p>An NLE/VFX application is built the other way round. The composition owns a <b>time domain</b>
 * (frame N happens at N / fps), a <b>raster</b> (the composition's own resolution) and a
 * <b>pure frame function</b> {@code (document, frame) → image}. The viewer asks that function for
 * the playhead's frame, the encoder asks it for every frame of the range, and both get the same
 * pixels. Everything else — scrubbing, green-bar pre-rendering, export queues — is a cache in
 * front of that one function.</p>
 *
 * <p>That is what this class is: two caches in front of {@link CompRenderer#renderFrame}. It does
 * not encode anything itself; {@code CompExportManager} is the encoder. The name "frame server" is
 * the one open-source editors use for this component (MLT's producer, Olive's render manager),
 * because the export "asks the server for frame N" instead of driving the UI.</p>
 *
 * <h2>Cache layers</h2>
 * <ol>
 *   <li><b>RAM</b> — {@link #BUDGET_BYTES} LRU of framebuffers, keyed by the document fingerprint.
 *       An edit produces a new key, so entries become unreachable instead of stale.</li>
 *   <li><b>Disk</b> — {@link CompFrameDisk}, keyed by the same fingerprint, for frames that were
 *       expensive to render. This is what makes a frame survive an edit-and-undo or a restart.</li>
 * </ol>
 */
public final class CompFrameServer
{
    /** Roughly 24 frames of 1080p, or ~90 of 720p. VRAM is the constraint, not the count. */
    private static final long BUDGET_BYTES = 192L * 1024 * 1024;
    /** Dev switch: log every rendered frame with its duration and cache state. */
    private static final boolean DEBUG = Boolean.getBoolean("bbsae.debugEncoder");

    private static final LinkedHashMap<Key, Entry> CACHE = new LinkedHashMap<>(32, 0.75F, true);
    private static long cachedBytes;
    private static long hits;
    private static long misses;
    private static long diskHits;
    private static long diskWrites;
    private static float lastMillis;

    private CompFrameServer()
    {}

    /** Identity of one rendered frame: document state, frame number, alpha or not, proxy scale. */
    private static final class Key
    {
        final long signature;
        final int frame;
        final boolean alpha;
        final int scale;

        Key(long signature, int frame, boolean alpha, int scale)
        {
            this.signature = signature;
            this.frame = frame;
            this.alpha = alpha;
            this.scale = scale;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof Key other))
            {
                return false;
            }

            return this.signature == other.signature && this.frame == other.frame
                && this.alpha == other.alpha && this.scale == other.scale;
        }

        @Override
        public int hashCode()
        {
            long mixed = this.signature * 0x9E3779B97F4A7C15L + this.frame * 31L
                + (this.alpha ? 1 : 0) + this.scale * 131L;

            return (int) (mixed ^ (mixed >>> 32));
        }
    }

    private static final class Entry
    {
        final GlFramebuffer framebuffer;
        final long bytes;
        final int frame;

        Entry(GlFramebuffer framebuffer, long bytes, int frame)
        {
            this.framebuffer = framebuffer;
            this.bytes = bytes;
            this.frame = frame;
        }
    }

    /** Frame index the playhead is on. Rounding (not flooring) matches the timecode readout. */
    public static int frameOf(CompDocument doc, float time)
    {
        return CompRenderer.frameIndex(doc, time);
    }

    /** The exact time a frame is evaluated at — the inverse of {@link #frameOf}. */
    public static float timeOf(CompDocument doc, int frame)
    {
        float fps = doc == null ? 60F : Math.max(1F, doc.fps);

        return Math.max(0, frame) / fps;
    }

    /** Total frames in a composition (at least one). */
    public static int frameCount(CompDocument doc)
    {
        if (doc == null)
        {
            return 1;
        }

        return Math.max(1, Math.round(Math.max(0.01F, doc.duration) * Math.max(1F, doc.fps)));
    }

    /** Pixel width of a frame at {@code scale}. Shared with the preview so both agree on the raster. */
    public static int widthOf(CompDocument doc, float scale)
    {
        return Math.max(1, Math.round((doc == null ? 1920 : doc.width) * Math.max(0.05F, Math.min(1F, scale))));
    }

    public static int heightOf(CompDocument doc, float scale)
    {
        return Math.max(1, Math.round((doc == null ? 1080 : doc.height) * Math.max(0.05F, Math.min(1F, scale))));
    }

    /**
     * Frame {@code frame} of {@code doc}, rendered once and reused until the document changes.
     *
     * @return a framebuffer owned by the server; do not release it. Null when the composition has
     *         nothing to render.
     */
    public static GlFramebuffer frame(CompProject project, CompDocument doc, int frame)
    {
        return frame(project, doc, frame, false, 1F);
    }

    /**
     * @param scale 1 for export; below 1 only for the AE proxy preview
     *              ({@code settings.encoderProxy}), where the badge shows the factor.
     */
    public static GlFramebuffer frame(CompProject project, CompDocument doc, int frame, boolean transparentStage, float scale)
    {
        if (project == null || doc == null)
        {
            return null;
        }

        float clampedScale = Math.max(0.05F, Math.min(1F, scale));
        int frameIndex = Math.max(0, Math.min(frameCount(doc) - 1, frame));
        Key key = key(project, doc, frameIndex, transparentStage, clampedScale);
        Entry entry = CACHE.get(key);

        if (entry != null)
        {
            hits++;

            return entry.framebuffer;
        }

        misses++;

        int width = widthOf(doc, clampedScale);
        int height = heightOf(doc, clampedScale);

        /* Disk L2 before the GPU: an 8 MB read plus an upload is cheaper than re-running a heavy
         * composition, and it is the only layer that survives a restart. */
        GlFramebuffer cached = fromDisk(project, doc, key, width, height);

        if (cached != null)
        {
            diskHits++;
            store(key, cached, frameIndex);

            return cached;
        }

        long started = System.nanoTime();
        GlFramebuffer rendered = CompRenderer.renderFrame(project, doc, frameIndex, transparentStage, clampedScale);

        lastMillis = (System.nanoTime() - started) / 1_000_000F;

        if (DEBUG)
        {
            dev.bbsae.BbsAeAddon.LOGGER.info("frame server: f{} @{}% {} ms (cache {} frames / {} MB, hit {} miss {} disk {})",
                frameIndex, Math.round(clampedScale * 100F), Math.round(lastMillis),
                CACHE.size(), cachedBytes / (1024 * 1024), hits, misses, diskHits);
        }

        if (rendered == null)
        {
            return null;
        }

        store(key, rendered, frameIndex);

        /* Expensive frames are worth keeping across document states; a 3 ms frame is not. */
        if (lastMillis >= CompFrameDisk.MIN_RENDER_MS)
        {
            int[] pixels = rendered.readTopDown();

            diskWrites++;
            CompFrameDisk.save(project, doc, key.signature, frameIndex, width, height, pixels);
        }

        return rendered;
    }

    private static void store(Key key, GlFramebuffer framebuffer, int frame)
    {
        long bytes = (long) Math.max(1, framebuffer.width) * Math.max(1, framebuffer.height) * 4L;

        CACHE.put(key, new Entry(framebuffer, bytes, frame));
        cachedBytes += bytes;

        while (cachedBytes > BUDGET_BYTES && CACHE.size() > 1)
        {
            Map.Entry<Key, Entry> oldest = CACHE.entrySet().iterator().next();

            CACHE.remove(oldest.getKey());
            cachedBytes -= oldest.getValue().bytes;
            FboPool.release(oldest.getValue().framebuffer);
        }
    }

    /**
     * Loads a frame from the disk cache into a pooled framebuffer.
     *
     * <p>The file stores top-down rows (what {@code readTopDown()} produced, which is also what a
     * PNG or a video encoder wants); the FBO texture is bottom-up, so the rows are reversed on the
     * way in. Keeping the flip here means the orientation rule lives in one place.</p>
     */
    private static GlFramebuffer fromDisk(CompProject project, CompDocument doc, Key key, int width, int height)
    {
        int[] pixels = CompFrameDisk.load(project, doc, key.signature, key.frame, width, height);

        if (pixels == null)
        {
            return null;
        }

        GlFramebuffer framebuffer = FboPool.acquire(width, height);

        if (framebuffer.width != width || framebuffer.height != height)
        {
            FboPool.release(framebuffer);

            return null;
        }

        int[] bottomUp = new int[pixels.length];

        for (int y = 0; y < height; y++)
        {
            System.arraycopy(pixels, y * width, bottomUp, (height - 1 - y) * width, width);
        }

        try (GlStateGuard guard = new GlStateGuard())
        {
            framebuffer.color.uploadRaw(bottomUp);
        }

        return framebuffer;
    }

    private static Key key(CompProject project, CompDocument doc, int frame, boolean transparentStage, float scale)
    {
        /* The signature already covers the frame index (it quantises time to the comp frame rate)
         * and every field that can change a pixel, so it doubles as the cache key — a document edit
         * produces a new signature and the old frames become unreachable. */
        long signature = CompRenderCache.signature(project, doc, timeOf(doc, frame), true, true, 0,
            Math.max(1, doc.width), Math.max(1, doc.height), false);

        return new Key(signature, frame, transparentStage, Math.round(scale * 100F));
    }

    /** Drops the VRAM cache. The disk L2 is content-addressed, so it stays valid. */
    public static void invalidate()
    {
        for (Entry entry : CACHE.values())
        {
            FboPool.release(entry.framebuffer);
        }

        CACHE.clear();
        cachedBytes = 0;
        hits = 0;
        misses = 0;
        diskHits = 0;
    }

    public static int size()
    {
        return CACHE.size();
    }

    public static long bytes()
    {
        return cachedBytes;
    }

    public static long hits()
    {
        return hits;
    }

    public static long misses()
    {
        return misses;
    }

    public static long diskHits()
    {
        return diskHits;
    }

    public static long diskWrites()
    {
        return diskWrites;
    }

    public static float lastMillis()
    {
        return lastMillis;
    }

    /** One line for the status bar / render log. */
    public static String describe()
    {
        return "帧服务器 " + CACHE.size() + " 帧 · " + (cachedBytes / (1024 * 1024)) + " MB · 命中 "
            + hits + " / 未命中 " + misses + " · 磁盘命中 " + diskHits + " · 上一帧 " + Math.round(lastMillis) + " ms";
    }
}
