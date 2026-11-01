package dev.bbsae.client.render;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
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
 * <p>Font files are read once and kept in native memory (stb reads from that buffer), with a small
 * LRU so a project that uses four fonts does not keep every font it ever touched. A missing,
 * unreadable or invalid file is cached as "draws nothing" so a broken project cannot cause a file
 * read per frame.</p>
 */
public final class FontCoverage
{
    /** Enough for the layers of one composition; fonts are megabytes. */
    private static final int MAX_CACHED = 4;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final Map<String, Coverage> CACHE = new LinkedHashMap<>(8, 0.75F, true);

    private FontCoverage()
    {}

    /**
     * True when {@code file} maps {@code codePoint} to a glyph.
     *
     * <p>Returns <b>true</b> when the coverage cannot be determined (LWJGL's stb natives missing,
     * an I/O error that is not "the file is gone"): pretending the font cannot draw anything would
     * silently replace a working custom font with the default one.</p>
     */
    public static boolean covers(File file, int codePoint)
    {
        if (file == null || codePoint <= 0 || !isReadable(file))
        {
            return false;
        }

        Coverage coverage = lookup(file);

        if (coverage == null)
        {
            return true;
        }

        return coverage.covers(codePoint);
    }

    /** Cheap "is this a real font file at all" probe used by the fallback decision. */
    public static boolean isReadable(File file)
    {
        return file != null && file.isFile() && file.length() > 0 && file.length() <= MAX_BYTES;
    }

    public static void clear()
    {
        for (Coverage coverage : CACHE.values())
        {
            coverage.delete();
        }

        CACHE.clear();
        RESOLVED.clear();
    }

    /**
     * Resolves a stored {@code fontName} to a file inside BBS' fonts folder.
     *
     * <p>Accepts what projects and the picker actually store: a bare file name, a name without an
     * extension, a sub-folder path under {@code fonts/}, and a case that differs from the file on
     * disk (Windows paths are case-insensitive, the asset provider's lookup is not).</p>
     */
    public static File resolve(String fontName)
    {
        if (fontName == null || fontName.isBlank())
        {
            return null;
        }

        String name = fontName.trim().replace('\\', '/');
        File cached = RESOLVED.get(name);

        if (cached != null && cached.isFile())
        {
            return cached;
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

                        return file;
                    }
                }
            }
        }

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

        java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();

        for (int i = 0; i < text.length(); )
        {
            int codePoint = text.codePointAt(i);

            i += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint))
            {
                continue;
            }

            if (seen.add(codePoint) && !covers(file, codePoint))
            {
                missing.add(codePoint);
            }
        }

        return missing;
    }

    private static final Map<String, File> RESOLVED = new java.util.HashMap<>();

    private static Coverage lookup(File file)
    {
        if (!isReadable(file))
        {
            return null;
        }

        String key;

        try
        {
            key = file.getCanonicalPath();
        }
        catch (Exception e)
        {
            key = file.getAbsolutePath();
        }

        long modified = file.lastModified();
        long length = file.length();
        Coverage cached = CACHE.get(key);

        if (cached != null && cached.modified == modified && cached.length == length)
        {
            return cached;
        }

        if (cached != null)
        {
            cached.delete();
            CACHE.remove(key);
        }

        Coverage loaded = Coverage.load(file, modified, length);

        if (loaded != null)
        {
            CACHE.put(key, loaded);
            trim();
        }

        return loaded;
    }

    private static void trim()
    {
        while (CACHE.size() > MAX_CACHED)
        {
            String oldest = CACHE.keySet().iterator().next();

            CACHE.remove(oldest).delete();
        }
    }

    private static final class Coverage
    {
        private final STBTTFontinfo info;
        private final ByteBuffer buffer;
        private final long modified;
        private final long length;

        private Coverage(STBTTFontinfo info, ByteBuffer buffer, long modified, long length)
        {
            this.info = info;
            this.buffer = buffer;
            this.modified = modified;
            this.length = length;
        }

        static Coverage load(File file, long modified, long length)
        {
            ByteBuffer buffer = null;
            STBTTFontinfo info = null;

            try
            {
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

                    return null;
                }

                return new Coverage(info, buffer, modified, length);
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
                return new Coverage(null, null, modified, length);
            }
        }

        boolean covers(int codePoint)
        {
            if (this.info == null)
            {
                return false;
            }

            try
            {
                return STBTruetype.stbtt_FindGlyphIndex(this.info, codePoint) != 0;
            }
            catch (Throwable e)
            {
                return true;
            }
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
