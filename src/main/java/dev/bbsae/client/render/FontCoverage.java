package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which characters a TTF/OTF can actually draw.
 *
 * <p>First principle: a text renderer must draw <b>every</b> character it is given. A font file is a
 * partial map from code points to glyphs — Times Bold has no 汉字, most CJK fonts have no emoji, a
 * display font may have only capitals. Minecraft's {@code FontStorage} answers a missing glyph with
 * {@code BuiltinEmptyGlyph.MISSING}, which has no renderer and an advance of 1 px, so a text layer
 * that uses a Latin font for Chinese text renders <b>nothing at all</b> (or one-pixel slivers), and
 * the same happens for a Chinese font asked to draw "…" or an emoji. That is exactly the
 * "imported font shows nothing" report.</p>
 *
 * <p>The fix cannot live in BBS's font manager: it builds a {@code FontStorage} with a single font
 * and has no fallback chain. So the renderer asks this class per character and falls back to the
 * default BBS font when the chosen file does not provide the glyph. The check is the same one
 * stb/Minecraft use when they build the atlas: {@code stbtt_FindGlyphIndex} returns 0 when the
 * font's cmap has no entry for the code point.</p>
 *
 * <h2>No I/O on the per-frame path</h2>
 *
 * <p>The old implementation resolved/stat'ed the font file and read its modification time every
 * single time {@link #covers(File, int)} was called. A text layer draws its stroke four times plus
 * the fill, so a 200-glyph layer could perform thousands of {@code isFile()/length()/lastModified()}
 * calls per frame — and Windows' {@code canonicalPath} alone costs tens of microseconds, which was
 * the single biggest cost of text rendering.</p>
 *
 * <p>Now:</p>
 * <ul>
 *   <li>{@link #coverage(File)} is resolved once at the draw-text entry point and passed down;</li>
 *   <li>a {@link File} seen before hits an {@link IdentityHashMap} and performs <b>zero</b> file
 *       system calls (no canonical path, no stat);</li>
 *   <li>the first query of a code point fills a {@code long[1024]} bitmap of the whole Unicode BMP,
 *       so repeated glyphs never call into stb again;</li>
 *   <li>{@link #resolve(String)} remembers hits (and briefly remembers misses), so a cached font
 *       name does not touch the disk every frame.</li>
 * </ul>
 *
 * <p>Because the caches deliberately stop staleness checks, {@link #clear()} must be called after a
 * font is imported, replaced or deleted. The font import UI and BBS' cache reload already do this
 * through {@code CompRenderer.clearCaches()} / {@code LayerContentRenderer.clearFonts()}.</p>
 */
public final class FontCoverage
{
    /** Enough for the layers of one composition; fonts are megabytes. */
    private static final int MAX_CACHED = 4;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    /** Code points 0..0xFFFF, one bit each. */
    private static final int BMP_LONGS = 1024;

    /** A missing font name is re-probed at most once a second, not once a frame. */
    private static final long MISS_TTL_NANOS = 1_000_000_000L;

    /** -Dbbsae.debugTextPerf: count covers calls and time, and log a summary every few seconds. */
    private static final boolean DEBUG = Boolean.getBoolean("bbsae.debugTextPerf");
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;

    /**
     * Per-file fast path. Identity semantics are intentional: a File instance that was resolved
     * once keeps its coverage without any further disk access. Font reloads call {@link #clear()}.
     */
    private static final Map<File, Coverage> BY_FILE = new IdentityHashMap<>();

    /** Canonical-path index, access ordered so the oldest font can be evicted without a stat. */
    private static final Map<String, Coverage> CACHE = new LinkedHashMap<>(8, 0.75F, true);

    /** font name → file, with no {@code isFile()} probe on hits. */
    private static final Map<String, File> RESOLVED = new HashMap<>();

    /** font name → nanoTime of the last failed resolution. */
    private static final Map<String, Long> MISSES = new HashMap<>();

    /* Cheap always-on counters; they exist so tests can prove the zero-I/O claim. */
    private static long canonicalPathCalls;
    private static long statCalls;
    private static long readCalls;
    private static long stbQueries;
    private static long lookupHits;
    private static long lookupMisses;
    private static long resolveMisses;

    /* debug-only counters, logged every few seconds when -Dbbsae.debugTextPerf is set */
    private static long perfFrames;
    private static long perfCovers;
    private static long perfCoversNanos;
    private static long perfLastLog = System.nanoTime();

    private FontCoverage()
    {}

    /**
     * The coverage oracle for a font file, resolved once per draw call.
     *
     * <p>Returns {@code null} only when the file is missing/unreadable. A file that exists but is
     * not a parsable font returns a non-null sentinel whose {@link #covers(Coverage, int)} answers
     * {@code false} or {@code true} exactly like the old implementation did (a stb init failure
     * means "assume the font can draw it", an I/O error means "it cannot").</p>
     */
    /**
     * Content identity of a font file (path + mtime + size), or 0 for the built-in font / missing
     * file. Signatures that only hash the font name did not notice a replaced font file: text
     * layers (and their baked 3D rasters) kept the old glyph atlas.
     */
    public static long contentId(String fontName)
    {
        if (fontName == null || fontName.isBlank())
        {
            return 0L;
        }

        File file = RESOLVED.get(fontName);

        if (file == null)
        {
            file = resolve(fontName);
        }

        if (file == null || !file.isFile())
        {
            return 0L;
        }

        return file.getAbsolutePath().hashCode() * 31L + file.lastModified() * 17L + file.length();
    }

    public static Coverage coverage(File file)
    {
        if (file == null)
        {
            return null;
        }

        return lookup(file);
    }

    /**
     * True when {@code coverage} maps {@code codePoint} to a glyph.
     *
     * <p>The coverage is the value resolved by {@link #coverage(File)} at the entry point of a
     * draw, so this method itself never touches the file system. A {@code null} coverage means the
     * file is not readable, which is what the old per-call {@code isReadable()} check answered.</p>
     */
    public static boolean covers(Coverage coverage, int codePoint)
    {
        if (coverage == null || codePoint <= 0)
        {
            return false;
        }

        if (!DEBUG)
        {
            return coverage.covers(codePoint);
        }

        long start = System.nanoTime();
        boolean result = coverage.covers(codePoint);

        perfCovers++;
        perfCoversNanos += System.nanoTime() - start;

        return result;
    }

    /**
     * True when {@code file} maps {@code codePoint} to a glyph.
     *
     * <p>Convenience wrapper for callers outside the render loop (font import warnings, the
     * inspector). The first call touches the disk; every later call is a bitmap lookup.</p>
     */
    public static boolean covers(File file, int codePoint)
    {
        if (file == null || codePoint <= 0)
        {
            return false;
        }

        return covers(coverage(file), codePoint);
    }

    /** Cheap "is this a real font file at all" probe used by the fallback decision. */
    public static boolean isReadable(File file)
    {
        if (file == null)
        {
            return false;
        }

        statCalls++;
        boolean fileExists = file.isFile();

        if (!fileExists)
        {
            return false;
        }

        statCalls++;
        long length = file.length();

        return length > 0 && length <= MAX_BYTES;
    }

    public static void clear()
    {
        for (Coverage coverage : CACHE.values())
        {
            coverage.delete();
        }

        CACHE.clear();
        BY_FILE.clear();
        RESOLVED.clear();
        MISSES.clear();
    }

    /**
     * Drops the cached coverage of one file.
     *
     * <p>Called when BBS reloads a font renderer (the font file changed or its idle entry was
     * evicted). The zero-I/O design cannot notice a file change by itself; the renderer identity
     * is the cheap signal that the file behind a name was reloaded.</p>
     */
    static void invalidate(File file)
    {
        if (file == null)
        {
            return;
        }

        Coverage coverage = BY_FILE.get(file);

        if (coverage == null)
        {
            return;
        }

        BY_FILE.values().removeIf(value -> value == coverage);
        CACHE.values().removeIf(value -> value == coverage);
        coverage.delete();
    }

    /**
     * Resolves a stored {@code fontName} to a file inside BBS' fonts folder.
     *
     * <p>Accepts what projects and the picker actually store: a bare file name, a name without an
     * extension, a sub-folder path under {@code fonts/}, and a case that differs from the file on
     * disk (Windows paths are case-insensitive, the asset provider's lookup is not).</p>
     *
     * <p>A hit is answered from memory without any file system call. A miss is remembered for a
     * second so a typo cannot make every frame scan the fonts folder; importing the font under the
     * same name within that window is picked up on the next probe.</p>
     */
    public static File resolve(String fontName)
    {
        if (fontName == null || fontName.isBlank())
        {
            return null;
        }

        String name = fontName.trim().replace('\\', '/');
        File cached = RESOLVED.get(name);

        if (cached != null)
        {
            return cached;
        }

        Long missedAt = MISSES.get(name);

        if (missedAt != null && System.nanoTime() - missedAt < MISS_TTL_NANOS)
        {
            return null;
        }

        java.util.List<String> candidates = new java.util.ArrayList<>();

        candidates.add(name);

        String lower = name.toLowerCase(java.util.Locale.ROOT);

        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf"))
        {
            candidates.add(name + ".ttf");
            candidates.add(name + ".otf");
        }

        for (String candidate : candidates)
        {
            try
            {
                File file = mchorse.bbs_mod.BBSMod.getProvider().getFile(
                    mchorse.bbs_mod.resources.Link.assets("fonts/" + candidate));

                if (file != null && file.isFile())
                {
                    RESOLVED.put(name, file);
                    MISSES.remove(name);

                    return file;
                }
            }
            catch (Throwable ignored)
            {
                /* Provider not ready yet; the folder scan below is the fallback. BBSMod's static
                 * initialiser throws an Error (not a RuntimeException) when there is no client. */
            }
        }

        File folder;

        try
        {
            folder = mchorse.bbs_mod.fonts.FontManager.getFolder();
        }
        catch (Throwable ignored)
        {
            /* BBS' asset folders do not exist in a unit test / before client init. */
            folder = null;
        }

        if (folder != null && folder.isDirectory())
        {
            String fileName = lower.endsWith(".ttf") || lower.endsWith(".otf") ? name : name + ".ttf";
            File[] files = folder.listFiles();

            if (files != null)
            {
                for (File file : files)
                {
                    if (file.isFile() && file.getName().equalsIgnoreCase(fileName))
                    {
                        RESOLVED.put(name, file);
                        MISSES.remove(name);

                        return file;
                    }
                }
            }
        }

        MISSES.put(name, System.nanoTime());
        resolveMisses++;

        return null;
    }

    /** The relative path of a resolved file under BBS' fonts folder, e.g. {@code sub/Name.ttf}. */
    public static String relativeName(File file)
    {
        if (file == null)
        {
            return "";
        }

        File folder;

        try
        {
            folder = mchorse.bbs_mod.fonts.FontManager.getFolder();
        }
        catch (Throwable ignored)
        {
            folder = null;
        }

        if (folder != null)
        {
            try
            {
                String path = folder.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize()).toString();

                return path.replace('\\', '/');
            }
            catch (IllegalArgumentException ignored)
            {
                /* Not under the fonts folder (a resource pack); fall back to the file name. */
            }
        }

        return file.getName();
    }

    /**
     * Code points of {@code text} that {@code file} has no glyph for, at most one entry per
     * distinct code point. Used by the inspector/import warnings.
     */
    public static java.util.List<Integer> missingGlyphs(File file, String text)
    {
        java.util.List<Integer> missing = new java.util.ArrayList<>();

        if (file == null || text == null || text.isEmpty() || !isReadable(file))
        {
            return missing;
        }

        Coverage coverage = coverage(file);
        java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();

        for (int i = 0; i < text.length(); )
        {
            int codePoint = text.codePointAt(i);

            i += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint))
            {
                continue;
            }

            if (seen.add(codePoint) && !covers(coverage, codePoint))
            {
                missing.add(codePoint);
            }
        }

        return missing;
    }

    /* ---------------------------------------------------------------- lookup */

    private static Coverage lookup(File file)
    {
        Coverage fast = BY_FILE.get(file);

        if (fast != null)
        {
            lookupHits++;

            return fast;
        }

        lookupMisses++;

        if (!isReadable(file))
        {
            /* The sentinel makes a deleted/replaced font cost one stat in total, not one per
             * frame. It holds no native memory. */
            Coverage unreadable = Coverage.unreadable();

            BY_FILE.put(file, unreadable);

            return unreadable;
        }

        String key;

        try
        {
            canonicalPathCalls++;
            key = file.getCanonicalPath();
        }
        catch (Exception e)
        {
            key = file.getAbsolutePath();
        }

        Coverage cached = CACHE.get(key);

        if (cached != null)
        {
            /* Another File instance for the same path: remember this identity too. */
            BY_FILE.put(file, cached);

            return cached;
        }

        Coverage loaded = Coverage.load(file);

        CACHE.put(key, loaded);
        BY_FILE.put(file, loaded);
        trim();

        return loaded;
    }

    private static void trim()
    {
        while (CACHE.size() > MAX_CACHED)
        {
            Iterator<Map.Entry<String, Coverage>> iterator = CACHE.entrySet().iterator();
            Coverage oldest = iterator.next().getValue();

            iterator.remove();
            BY_FILE.values().removeIf(value -> value == oldest);
            oldest.delete();
        }
    }

    /* ---------------------------------------------------------------- counters */

    /** Test/debug hook: zero the always-on I/O counters. */
    static void resetIoCounters()
    {
        canonicalPathCalls = 0;
        statCalls = 0;
        readCalls = 0;
        stbQueries = 0;
        lookupHits = 0;
        lookupMisses = 0;
        resolveMisses = 0;
    }

    static long canonicalPathCalls()
    {
        return canonicalPathCalls;
    }

    static long statCalls()
    {
        return statCalls;
    }

    static long readCalls()
    {
        return readCalls;
    }

    static long stbQueries()
    {
        return stbQueries;
    }

    static long lookupHits()
    {
        return lookupHits;
    }

    static long lookupMisses()
    {
        return lookupMisses;
    }

    /** Called once per text layer per frame; logs a summary every few seconds in debug mode. */
    static void noteTextFrame()
    {
        if (!DEBUG)
        {
            return;
        }

        perfFrames++;

        long now = System.nanoTime();

        if (now - perfLastLog < LOG_INTERVAL_NANOS)
        {
            return;
        }

        double seconds = Math.max(0.001D, (now - perfLastLog) / 1_000_000_000D);
        double frames = Math.max(1L, perfFrames);
        double coversPerFrame = perfCovers / frames;
        double coversAvgNs = perfCovers == 0 ? 0D : perfCoversNanos / (double) perfCovers;

        BbsAeAddon.LOGGER.info(
            "text perf: frames={} ({}/s) covers={} ({}/frame, {} ns avg) lookupHit={} lookupMiss={} "
                + "io(canonical={} stat={} read={}) stb={} resolveMiss={}",
            perfFrames, String.format(java.util.Locale.ROOT, "%.1f", perfFrames / seconds),
            perfCovers, String.format(java.util.Locale.ROOT, "%.1f", coversPerFrame),
            String.format(java.util.Locale.ROOT, "%.0f", coversAvgNs),
            lookupHits, lookupMisses, canonicalPathCalls, statCalls, readCalls, stbQueries, resolveMisses);

        perfFrames = 0;
        perfCovers = 0;
        perfCoversNanos = 0;
        perfLastLog = now;
        resetIoCounters();
    }

    /* ---------------------------------------------------------------- coverage */

    /**
     * One font's cmap, plus a lazy BMP bitmap of the answers.
     *
     * <p>The bitmap is what makes a repeated {@code covers()} call a shift-and-test: the first
     * query of a code point asks stb, and the result (including "missing") is remembered forever
     * for that loaded font.</p>
     */
    public static final class Coverage
    {
        private static final int KIND_FONT = 0;
        private static final int KIND_UNPARSEABLE = 1;
        private static final int KIND_BROKEN = 2;
        private static final int KIND_UNREADABLE = 3;

        private static final Coverage UNPARSEABLE = new Coverage(KIND_UNPARSEABLE, null, null);
        private static final Coverage BROKEN = new Coverage(KIND_BROKEN, null, null);
        private static final Coverage UNREADABLE = new Coverage(KIND_UNREADABLE, null, null);

        private final int kind;
        private final STBTTFontinfo info;
        private final ByteBuffer buffer;
        private final long[] bmpKnown;
        private final long[] bmpCovered;
        /** Code points outside the BMP are rare (emoji); a tiny map is cheaper than 128 KB. */
        private final Map<Integer, Boolean> beyondBmp;

        private Coverage(int kind, STBTTFontinfo info, ByteBuffer buffer)
        {
            this.kind = kind;
            this.info = info;
            this.buffer = buffer;

            if (kind == KIND_FONT)
            {
                this.bmpKnown = new long[BMP_LONGS];
                this.bmpCovered = new long[BMP_LONGS];
                this.beyondBmp = new HashMap<>();
            }
            else
            {
                this.bmpKnown = null;
                this.bmpCovered = null;
                this.beyondBmp = null;
            }
        }

        public boolean covers(int codePoint)
        {
            if (codePoint <= 0)
            {
                return false;
            }

            if (this.kind != KIND_FONT)
            {
                /* Old behaviour, preserved exactly: an unparsable font is treated as "assume it
                 * can draw it" (it is never selected anyway), an I/O error as "cannot draw". */
                return this.kind == KIND_UNPARSEABLE;
            }

            if (codePoint <= 0xFFFF)
            {
                int word = codePoint >>> 6;
                long mask = 1L << (codePoint & 63);

                if ((this.bmpKnown[word] & mask) != 0L)
                {
                    return (this.bmpCovered[word] & mask) != 0L;
                }

                boolean result = this.query(codePoint);

                this.bmpKnown[word] |= mask;

                if (result)
                {
                    this.bmpCovered[word] |= mask;
                }

                return result;
            }

            Boolean cached = this.beyondBmp.get(codePoint);

            if (cached != null)
            {
                return cached;
            }

            boolean result = this.query(codePoint);

            this.beyondBmp.put(codePoint, result);

            return result;
        }

        private boolean query(int codePoint)
        {
            if (this.info == null)
            {
                return false;
            }

            stbQueries++;

            try
            {
                return STBTruetype.stbtt_FindGlyphIndex(this.info, codePoint) != 0;
            }
            catch (Throwable e)
            {
                /* A crashed stb query must not make a font look empty. */
                return true;
            }
        }

        static Coverage load(File file)
        {
            ByteBuffer buffer = null;
            STBTTFontinfo info = null;

            try
            {
                readCalls++;
                byte[] bytes = Files.readAllBytes(file.toPath());

                /* stb keeps reading from this buffer for as long as the font lives. */
                buffer = MemoryUtil.memAlloc(bytes.length);
                buffer.put(bytes);
                buffer.flip();

                info = STBTTFontinfo.malloc();

                if (!STBTruetype.stbtt_InitFont(info, buffer))
                {
                    info.free();
                    MemoryUtil.memFree(buffer);

                    return UNPARSEABLE;
                }

                return new Coverage(KIND_FONT, info, buffer);
            }
            catch (Throwable e)
            {
                if (info != null)
                {
                    info.free();
                }

                if (buffer != null)
                {
                    MemoryUtil.memFree(buffer);
                }

                /* Unreadable/invalid: remember that as "no glyphs" without keeping memory. */
                return BROKEN;
            }
        }

        static Coverage unreadable()
        {
            return UNREADABLE;
        }

        void delete()
        {
            if (this.info != null)
            {
                this.info.free();
            }

            if (this.buffer != null)
            {
                MemoryUtil.memFree(this.buffer);
            }
        }
    }
}
