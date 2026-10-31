package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Pre-render cache: the green bar.
 *
 * <h2>First principles</h2>
 * <p>Playing a composition is expensive because every displayed frame re-runs the whole graph
 * (one full-screen pass per layer, mask, effect and style). Editing is not the expensive part —
 * <em>evaluating the same pixels again</em> is. After Effects solves this with a disk cache and
 * paints the cached ranges green on the time ruler; once a range is green, playback is a texture
 * lookup instead of a render.</p>
 *
 * <p>So the cache is keyed by <b>(composition, frame index)</b>, stores the finished composition
 * pixels, and is invalidated by the same content fingerprint the live preview uses
 * ({@link CompRenderCache#signature}). Nothing about the document is duplicated: a cached frame is
 * only ever a function of that signature, so any edit throws the whole cache away.</p>
 *
 * <h2>Why disk, and why raw</h2>
 * <p>1080p RGBA is 8 MB per frame; a twelve second composition does not fit in VRAM. The cache
 * therefore lives on disk under {@code config/bbsae/cache/<project>/<comp>/}, compressed with
 * {@link Deflater} level 1. PNG would be smaller but costs 50–100 ms per frame to encode and
 * ~20 ms to decode, which defeats the purpose. Deflate level 1 is ~2 ms each way at 1280 px,
 * which is the same order as the GPU upload that follows it.</p>
 *
 * <p>Frames are rendered at a bounded <b>cache resolution</b> (1280×720 by default), not at the
 * composition resolution: the cache feeds a preview viewport that is smaller than the comp, and
 * a 4K cache would be eight times the disk for pixels nobody sees.</p>
 */
public final class CompPrecache
{
    /** Cache resolution bounds. The preview never needs more than this. */
    public static final int MAX_WIDTH = 1280;
    public static final int MAX_HEIGHT = 720;

    /** Resident textures. 16 × 1280×720 RGBA ≈ 59 MB of VRAM, which is a reasonable ceiling. */
    private static final int MAX_TEXTURES = 16;
    /** Spare textures kept for reuse; recreating one per frame is a driver stall. */
    private static final int MAX_FREE = 8;
    /** Frames rendered per display frame before the job yields, so the UI never freezes. */
    private static final int FRAMES_PER_TICK = 2;
    /** Hard budget for one {@link #tick} call. */
    private static final long TICK_BUDGET_MS = 18L;

    private static final Map<String, Cache> CACHES = new HashMap<>();
    /** Keys whose {@code meta.txt} was already probed, so a miss is not a file-system hit per frame. */
    private static final java.util.Set<String> RESTORE_TRIED = new java.util.HashSet<>();
    private static final Deque<GlTexture> FREE = new ArrayDeque<>();
    /**
     * One writer thread, bounded queue.
     *
     * <p>Deflating and writing are ~2 ms per frame; doing that on the render thread would show up
     * as a stutter, and spawning a thread per frame (the first implementation) is worse: a
     * pre-render pass would create hundreds of them. A single daemon worker with a small backlog
     * is the whole requirement.</p>
     */
    private static final java.util.concurrent.ExecutorService WRITER =
        new java.util.concurrent.ThreadPoolExecutor(1, 1, 10L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(32),
            (runnable) ->
            {
                Thread thread = new Thread(runnable, "bbsae-precache-writer");

                thread.setDaemon(true);

                return thread;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    private static int freeWidth;
    private static int freeHeight;

    private static Cache job;

    private CompPrecache()
    {}

    /* ------------------------------------------------------------------ state */

    public enum State
    {
        /** Nothing cached for this frame. */
        NONE,
        /** Queued or currently being rendered. */
        PENDING,
        /** Present on disk or in VRAM; playback can blit it. */
        CACHED
    }

    private static final class Frame
    {
        int index;
        GlTexture texture;
        long lastUsed;
        boolean onDisk;
        boolean failed;
    }

    private static final class Cache
    {
        final String key;
        final String projectId;
        final String compId;
        long signature;
        /** Bumped whenever the on-disk frames are wiped (signature change); queued disk writes
         * from the previous generation must not resurrect deleted files. */
        int generation;
        int width;
        int height;
        boolean valid;
        boolean broken;
        /** Set by {@link #markDirty()}: an edit happened, so the pixels are no longer trusted. */
        boolean stale;
        long dirtyAt;
        long validatedAt;
        final Map<Integer, Frame> frames = new LinkedHashMap<>();
        int rangeStart;
        int rangeEnd;
        int cursor;
        int failed;
        long lastFrameMs;
        int cooldown;

        Cache(String key, String projectId, String compId)
        {
            this.key = key;
            this.projectId = projectId;
            this.compId = compId;
        }
    }

    /* ------------------------------------------------------------------ paths */

    private static Path root()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("bbsae").resolve("cache");
    }

    private static Path dir(String key)
    {
        return root().resolve(safe(key));
    }

    private static String safe(String value)
    {
        if (value == null || value.isEmpty())
        {
            return "unknown";
        }

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < value.length() && builder.length() < 64; i++)
        {
            char c = value.charAt(i);

            builder.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }

        return builder.toString();
    }

    private static Path frameFile(Cache cache, int frame)
    {
        return dir(cache.key).resolve(String.format(java.util.Locale.ROOT, "f%06d.rgba", frame));
    }

    private static Path metaFile(Cache cache)
    {
        return dir(cache.key).resolve("meta.txt");
    }

    /* ------------------------------------------------------------------ setup */

    /**
     * Ensures the cache entry matches the current document. Returns the entry, or null when the
     * composition cannot be cached (a live BBS-world layer makes every frame depend on the world).
     */
    private static Cache prepare(CompProject project, CompDocument comp)
    {
        if (project == null || comp == null || comp.layers == null || comp.layers.isEmpty())
        {
            return null;
        }

        if (dependsOnLiveWorld(comp))
        {
            return null;
        }

        String key = safe(project.id) + "/" + safe(comp.id);
        int[] size = cacheSize(comp);
        long signature = CompRenderCache.signature(project, comp, 0F, true, true, 0, size[0], size[1], false);
        Cache cache = CACHES.get(key);

        if (cache != null && cache.signature == signature && cache.width == size[0] && cache.height == size[1])
        {
            cache.stale = false;

            return cache;
        }

        if (cache == null)
        {
            cache = new Cache(key, safe(project.id), safe(comp.id));
            CACHES.put(key, cache);
        }
        else
        {
            clearFrames(cache);
        }

        cache.signature = signature;
        cache.width = size[0];
        cache.height = size[1];
        cache.valid = true;
        cache.broken = false;
        cache.stale = false;

        /* A signature change means every pixel is stale. Delete the files instead of leaving
         * them to accumulate: a cache directory is not a project archive. */
        cache.generation += 1;

        try
        {
            Path directory = dir(key);

            if (Files.isDirectory(directory))
            {
                try (java.util.stream.Stream<Path> stream = Files.list(directory))
                {
                    stream.filter((path) -> !path.getFileName().toString().equals("meta.txt"))
                        .forEach((path) ->
                        {
                            try
                            {
                                Files.deleteIfExists(path);
                            }
                            catch (IOException ignored)
                            {}
                        });
                }
            }

            Files.createDirectories(directory);
            Files.writeString(metaFile(cache), signature + " " + size[0] + " " + size[1] + " " + comp.duration);
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not prepare the pre-render cache at {}", dir(key), e);
            cache.broken = true;
        }

        return cache;
    }

    /** A BBS-world layer samples the live game frame; caching it would freeze the world. */
    public static boolean hasWorldLayer(CompDocument comp)
    {
        if (comp == null || comp.layers == null)
        {
            return false;
        }

        for (CompLayer layer : comp.layers)
        {
            if (layer != null && layer.visible && "bbs".equals(layer.type))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * True when the composition cannot be cached because its pixels depend on the live world.
     *
     * <p>Two cases: a {@code BBS 预览} layer samples the game frame, and a {@code depth_*} effect
     * samples the world depth buffer. A cached frame would freeze both — and a depth effect
     * evaluated without the world depth is a silent no-op, which is worse than refusing.</p>
     */
    public static boolean dependsOnLiveWorld(CompDocument comp)
    {
        if (comp == null || comp.layers == null)
        {
            return false;
        }

        for (CompLayer layer : comp.layers)
        {
            if (layer == null || !layer.visible)
            {
                continue;
            }

            if ("bbs".equals(layer.type) || layer.isEntityLayer())
            {
                return true;
            }

            if (layer.effects == null)
            {
                continue;
            }

            for (dev.bbsae.comp.CompEffect effect : layer.effects)
            {
                if (effect != null && effect.enabled && effect.type != null && effect.type.startsWith("depth_"))
                {
                    return true;
                }
            }
        }

        return false;
    }

    public static int[] cacheSize(CompDocument comp)
    {
        int width = Math.max(1, comp.width);
        int height = Math.max(1, comp.height);
        float scale = Math.min(1F, Math.min(MAX_WIDTH / (float) width, MAX_HEIGHT / (float) height));
        int w = Math.max(16, Math.round(width * scale));
        int h = Math.max(16, Math.round(height * scale));

        return new int[] {w - (w & 1), h - (h & 1)};
    }

    /* ------------------------------------------------------------------ job */

    /** True while a pre-render job is filling frames. */
    public static boolean isRunning()
    {
        return job != null;
    }

    public static String runningKey()
    {
        return job == null ? null : job.key;
    }

    /**
     * Starts (or restarts) a pre-render pass over {@code [fromFrame, toFrame)}.
     *
     * <p>The pass is cooperative: {@link #tick} renders a couple of frames per display frame so
     * the editor stays responsive, and the ruler turns green behind the playhead as it goes.</p>
     */
    public static void start(CompProject project, CompDocument comp, int fromFrame, int toFrame)
    {
        stop();

        Cache cache = prepare(project, comp);

        if (cache == null || cache.broken)
        {
            return;
        }

        int start = Math.max(0, fromFrame);
        int end = Math.max(start + 1, toFrame);

        cache.rangeStart = start;
        cache.rangeEnd = end;
        cache.cursor = start;
        cache.failed = 0;
        job = cache;

        BbsAeAddon.LOGGER.info("Pre-render started: {} frames {}..{} at {}x{}", cache.key, start, end, cache.width, cache.height);
    }

    public static void stop()
    {
        if (job != null)
        {
            BbsAeAddon.LOGGER.info("Pre-render stopped at frame {}", job.cursor);
            job = null;
        }
    }

    /**
     * Renders at most {@link #FRAMES_PER_TICK} frames. Called once per display frame from the
     * compositor panel; safe to call when no job is running.
     */
    public static void tick(CompProject project, CompDocument comp)
    {
        Cache cache = job;

        if (cache == null || comp == null || project == null)
        {
            return;
        }

        if (!cache.compId.equals(safe(comp.id)) || !cache.projectId.equals(safe(project.id)))
        {
            stop();
            return;
        }

        if (cache.cursor >= cache.rangeEnd)
        {
            BbsAeAddon.LOGGER.info("Pre-render finished: {} frames, {} failed", cache.rangeEnd - cache.rangeStart, cache.failed);
            stop();
            return;
        }

        /* A single heavy frame can exceed the budget (a GL render cannot be preempted). When that
         * happens, skip a display frame so the editor's own rendering still gets a slot. */
        if (cache.cooldown > 0)
        {
            cache.cooldown -= 1;

            return;
        }

        long deadline = System.currentTimeMillis() + TICK_BUDGET_MS;
        float fps = Math.max(1F, comp.fps);

        for (int i = 0; i < FRAMES_PER_TICK && cache.cursor < cache.rangeEnd; i++)
        {
            int frame = cache.cursor;
            float time = frame / fps;
            Frame existing = cache.frames.get(frame);

            /* Re-running a pass after an interruption must not redo frames that are already
             * green — that is the whole point of a cache. */
            if (existing != null && (existing.texture != null || existing.onDisk))
            {
                cache.cursor += 1;

                continue;
            }

            long started = System.currentTimeMillis();

            try
            {
                renderFrame(project, comp, cache, frame, time);
            }
            catch (RuntimeException e)
            {
                cache.failed += 1;

                if (cache.failed < 3)
                {
                    BbsAeAddon.LOGGER.warn("Pre-render frame {} failed", frame, e);
                }

                Frame entry = cache.frames.computeIfAbsent(frame, (key) -> new Frame());
                entry.failed = true;
            }

            cache.cursor += 1;
            cache.lastFrameMs = System.currentTimeMillis() - started;

            if (cache.lastFrameMs > TICK_BUDGET_MS)
            {
                cache.cooldown = 2;
                break;
            }

            if (System.currentTimeMillis() > deadline)
            {
                break;
            }
        }
    }

    private static void renderFrame(CompProject project, CompDocument comp, Cache cache, int frame, float time)
    {
        GlFramebuffer fbo = CompRenderer.renderForCache(project, comp, time, cache.width, cache.height);

        if (fbo == null)
        {
            return;
        }

        byte[] pixels;

        try
        {
            pixels = fbo.readRawBytes();
        }
        finally
        {
            FboPool.release(fbo);
        }

        GlTexture texture;

        /* Uploading touches texture units and the bound texture; the UI is mid-frame when the
         * job runs, so the state is saved and restored like any other pass. */
        try (GlStateGuard guard = new GlStateGuard())
        {
            texture = takeTexture(cache.width, cache.height);
            texture.uploadBytes(pixels);
        }

        Frame entry = cache.frames.computeIfAbsent(frame, (key) -> new Frame());

        entry.index = frame;
        entry.texture = texture;
        entry.lastUsed = System.nanoTime();
        entry.failed = false;

        evictTextures(cache);

        /* Persisting is the expensive half (deflate + fsync), so it happens off-thread. The
         * frame is already usable from VRAM the moment this returns. */
        writeAsync(cache, frame, pixels);
    }

    private static void writeAsync(Cache cache, int frame, byte[] pixels)
    {
        final Frame entry = cache.frames.get(frame);
        final Path file = frameFile(cache, frame);
        final int generation = cache.generation;

        WRITER.execute(() ->
        {
            /* A signature change wiped this directory while our task sat in the queue; writing
             * now would put pre-edit pixels under the NEW signature (served after a restart). */
            if (cache.generation != generation)
            {
                return;
            }

            byte[] compressed = compress(pixels);

            try
            {
                Files.createDirectories(file.getParent());
                Files.write(file, compressed);

                if (entry != null)
                {
                    entry.onDisk = true;
                }
            }
            catch (IOException e)
            {
                BbsAeAddon.LOGGER.warn("Could not write a cached frame to {}", file, e);
            }
        });
    }

    /* ------------------------------------------------------------------ lookup */

    public static State state(CompProject project, CompDocument comp, int frame)
    {
        Cache cache = cached(project, comp);

        if (cache == null)
        {
            return State.NONE;
        }

        Frame entry = cache.frames.get(frame);

        if (entry != null && (entry.texture != null || entry.onDisk))
        {
            return State.CACHED;
        }

        if (cache == job && frame >= cache.rangeStart && frame < cache.rangeEnd)
        {
            return State.PENDING;
        }

        return State.NONE;
    }

    /** The cached texture for this frame, loading it from disk if it is not resident. */
    public static GlTexture texture(CompProject project, CompDocument comp, int frame)
    {
        Cache cache = cached(project, comp);

        if (cache == null || cache.stale)
        {
            return null;
        }

        Frame entry = cache.frames.get(frame);

        if (entry == null)
        {
            Path file = frameFile(cache, frame);

            if (!Files.isRegularFile(file))
            {
                return null;
            }

            entry = cache.frames.computeIfAbsent(frame, (key) -> new Frame());
            entry.index = frame;
        }

        if (entry.texture != null)
        {
            entry.lastUsed = System.nanoTime();

            return entry.texture;
        }

        if (entry.onDisk)
        {
            GlTexture texture = load(cache, frame);

            if (texture != null)
            {
                entry.texture = texture;
                entry.lastUsed = System.nanoTime();
                evictTextures(cache);

                return texture;
            }
        }

        return null;
    }

    private static Cache cached(CompProject project, CompDocument comp)
    {
        if (project == null || comp == null)
        {
            return null;
        }

        String key = safe(project.id) + "/" + safe(comp.id);
        Cache cache = CACHES.get(key);

        if (cache == null)
        {
            cache = restore(project, comp, key);
        }

        return cache == null || cache.broken ? null : cache;
    }

    /**
     * Re-attaches a cache written in a previous session.
     *
     * <p>The disk cache is only worth having if it survives a restart: a green bar that
     * disappears when the game is relaunched is just a progress indicator. {@code meta.txt}
     * stores the content fingerprint and the cache size; if the fingerprint still matches the
     * current document, the frame list is rebuilt from the directory (textures are loaded lazily,
     * on the frame that is actually displayed).</p>
     */
    private static Cache restore(CompProject project, CompDocument comp, String key)
    {
        if (!RESTORE_TRIED.add(key))
        {
            return null;
        }

        Path meta = dir(key).resolve("meta.txt");

        if (!Files.isRegularFile(meta))
        {
            return null;
        }

        try
        {
            String[] parts = Files.readString(meta).trim().split("\\s+");

            if (parts.length < 3)
            {
                return null;
            }

            long signature = Long.parseLong(parts[0]);
            int width = Integer.parseInt(parts[1]);
            int height = Integer.parseInt(parts[2]);
            int[] size = cacheSize(comp);

            if (width != size[0] || height != size[1])
            {
                return null;
            }

            long current = CompRenderCache.signature(project, comp, 0F, true, true, 0, width, height, false);

            if (current != signature)
            {
                return null;
            }

            Cache cache = new Cache(key, safe(project.id), safe(comp.id));

            cache.signature = signature;
            cache.width = width;
            cache.height = height;
            cache.valid = true;

            try (java.util.stream.Stream<Path> stream = Files.list(dir(key)))
            {
                stream.forEach((path) ->
                {
                    String name = path.getFileName().toString();

                    if (!name.startsWith("f") || !name.endsWith(".rgba"))
                    {
                        return;
                    }

                    try
                    {
                        int frame = Integer.parseInt(name.substring(1, name.length() - 5));
                        Frame entry = new Frame();

                        entry.index = frame;
                        entry.onDisk = true;
                        cache.frames.put(frame, entry);
                    }
                    catch (NumberFormatException ignored)
                    {}
                });
            }

            CACHES.put(key, cache);

            return cache;
        }
        catch (IOException | NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * Any document edit invalidates the cached pixels.
     *
     * <p>Called from the panel's {@code save()}, which every edit already goes through. Cheap:
     * it flips a flag instead of re-hashing the document, and it makes "green means what you see"
     * true again — otherwise a cached frame would keep hiding an edit until the next pre-render
     * pass.</p>
     */
    public static void markDirty()
    {
        for (Cache cache : CACHES.values())
        {
            cache.stale = true;
            cache.dirtyAt = System.currentTimeMillis();
        }
    }

    /**
     * Re-checks a "dirty" cache against the document once it has been quiet for a moment.
     *
     * <p>Marking dirty on every {@code save()} is deliberately pessimistic — the panel also saves
     * when a panel setting changes, which does not affect a single pixel. If the fingerprint still
     * matches after the edit settles, the cache is trusted again, so opening a project that was
     * pre-rendered in a previous session keeps its green bar.</p>
     */
    public static void validate(CompProject project, CompDocument comp)
    {
        Cache cache = cached(project, comp);

        if (cache == null || !cache.stale)
        {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - cache.dirtyAt < 250L || now - cache.validatedAt < 250L)
        {
            return;
        }

        cache.validatedAt = now;

        long signature = CompRenderCache.signature(project, comp, 0F, true, true, 0, cache.width, cache.height, false);

        if (signature == cache.signature)
        {
            cache.stale = false;
        }
    }

    private static GlTexture load(Cache cache, int frame)
    {
        Path file = frameFile(cache, frame);

        try
        {
            byte[] compressed = Files.readAllBytes(file);
            byte[] pixels = decompress(compressed, cache.width * cache.height * 4);

            if (pixels == null)
            {
                Files.deleteIfExists(file);

                return null;
            }

            GlTexture texture = takeTexture(cache.width, cache.height);

            texture.uploadBytes(pixels);

            return texture;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /* ------------------------------------------------------------------ stats */

    /** Cached frame count in the current document's range; used by the green bar and the label. */
    public static int cachedCount(CompProject project, CompDocument comp)
    {
        Cache cache = cached(project, comp);

        if (cache == null || cache.stale)
        {
            return 0;
        }

        int count = 0;

        for (Frame entry : cache.frames.values())
        {
            if (entry.texture != null || entry.onDisk)
            {
                count++;
            }
        }

        return count;
    }

    public static int rangeFrames(CompDocument comp)
    {
        return comp == null ? 0 : Math.max(1, Math.round(comp.duration * Math.max(1F, comp.fps)));
    }

    /** 0..1 progress of the running job, or of the resident coverage when idle. */
    public static float progress(CompProject project, CompDocument comp)
    {
        Cache cache = cached(project, comp);

        if (cache == null)
        {
            return 0F;
        }

        int total = Math.max(1, cache.rangeEnd - cache.rangeStart);

        if (cache == job)
        {
            return Math.min(1F, (cache.cursor - cache.rangeStart) / (float) total);
        }

        return Math.min(1F, cachedCount(project, comp) / (float) Math.max(1, rangeFrames(comp)));
    }

    public static boolean isCachedFrame(CompProject project, CompDocument comp, int frame)
    {
        return state(project, comp, frame) == State.CACHED;
    }

    /**
     * Which frames are green, as a bitset.
     *
     * <p>The ruler asks once per displayed frame and merges runs, instead of calling
     * {@link #state} for every frame of the composition (which would rebuild a string key
     * thousands of times per frame).</p>
     */
    public static java.util.BitSet coverage(CompProject project, CompDocument comp)
    {
        java.util.BitSet bits = new java.util.BitSet();
        Cache cache = cached(project, comp);

        if (cache == null || cache.stale)
        {
            return bits;
        }

        for (Frame entry : cache.frames.values())
        {
            if (entry.texture != null || entry.onDisk)
            {
                bits.set(entry.index);
            }
        }

        return bits;
    }

    public static void clear(CompProject project, CompDocument comp)
    {
        Cache cache = cached(project, comp);

        if (cache == null)
        {
            return;
        }

        if (cache == job)
        {
            stop();
        }

        clearFrames(cache);
        cache.signature = 0L;

        try (java.util.stream.Stream<Path> stream = Files.list(dir(cache.key)))
        {
            List<Path> files = new ArrayList<>();

            stream.forEach(files::add);

            for (Path file : files)
            {
                Files.deleteIfExists(file);
            }
        }
        catch (IOException ignored)
        {}
    }

    public static void clearAll()
    {
        stop();

        for (Cache cache : CACHES.values())
        {
            clearFrames(cache);
        }

        CACHES.clear();
        RESTORE_TRIED.clear();

        for (GlTexture texture : FREE)
        {
            texture.delete();
        }

        FREE.clear();
    }

    private static void clearFrames(Cache cache)
    {
        for (Frame entry : cache.frames.values())
        {
            if (entry.texture != null)
            {
                recycle(entry.texture);
                entry.texture = null;
            }
        }

        cache.frames.clear();
    }

    /* ------------------------------------------------------------------ textures */

    private static GlTexture takeTexture(int width, int height)
    {
        if (!FREE.isEmpty() && freeWidth == width && freeHeight == height)
        {
            return FREE.pop();
        }

        GlTexture texture = new GlTexture();

        texture.allocate(width, height);

        return texture;
    }

    private static void recycle(GlTexture texture)
    {
        if (texture == null)
        {
            return;
        }

        if (FREE.size() >= MAX_FREE || (freeWidth != 0 && (freeWidth != texture.width || freeHeight != texture.height)))
        {
            texture.delete();

            return;
        }

        freeWidth = texture.width;
        freeHeight = texture.height;
        FREE.push(texture);
    }

    /** Drops the least recently used textures when the cache exceeds its VRAM budget. */
    private static void evictTextures(Cache cache)
    {
        List<Frame> resident = new ArrayList<>();

        for (Frame entry : cache.frames.values())
        {
            if (entry.texture != null)
            {
                resident.add(entry);
            }
        }

        if (resident.size() <= MAX_TEXTURES)
        {
            return;
        }

        resident.sort(Comparator.comparingLong((Frame entry) -> entry.lastUsed));

        for (int i = 0; i < resident.size() - MAX_TEXTURES; i++)
        {
            Frame entry = resident.get(i);

            recycle(entry.texture);
            entry.texture = null;
        }
    }

    /* ------------------------------------------------------------------ codec */

    private static byte[] compress(byte[] pixels)
    {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);

        try
        {
            deflater.setInput(pixels);
            deflater.finish();

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(64, pixels.length / 4));
            byte[] chunk = new byte[16384];

            while (!deflater.finished())
            {
                int count = deflater.deflate(chunk);

                output.write(chunk, 0, count);
            }

            return output.toByteArray();
        }
        finally
        {
            deflater.end();
        }
    }

    private static byte[] decompress(byte[] data, int byteCount)
    {
        Inflater inflater = new Inflater();
        byte[] raw = new byte[byteCount];

        try
        {
            inflater.setInput(data);

            int offset = 0;

            while (!inflater.finished() && offset < raw.length)
            {
                int count = inflater.inflate(raw, offset, raw.length - offset);

                if (count == 0)
                {
                    if (inflater.needsInput() || inflater.needsDictionary())
                    {
                        break;
                    }
                }

                offset += count;
            }

            if (offset < raw.length)
            {
                return null;
            }
        }
        catch (DataFormatException e)
        {
            return null;
        }
        finally
        {
            inflater.end();
        }

        return raw;
    }
}
