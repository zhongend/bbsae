package dev.bbsae.client.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Filename and container rules for importing a TTF/OTF into BBS's fonts folder.
 *
 * <p>Two problems made the old importer look broken even when the copy succeeded:</p>
 * <ul>
 *   <li>The filename was reduced to {@code [a-zA-Z0-9._-]}. A Chinese font called
 *       {@code 思源黑体.ttf} became {@code ____.ttf}; several such fonts all collided on the same
 *       name, each import overwriting the previous one, and the picker showed a meaningless label.</li>
 *   <li>A TTC collection or a WOFF (or a corrupt download) was copied and selected as if it were a
 *       font. BBS' stb loader then refused it and silently fell back to the default renderer, so the
 *       import "did nothing". Checking the container before copying turns that into a message.</li>
 * </ul>
 *
 * <p>Kept free of Minecraft/BBS types so it is unit-testable without a client.</p>
 */
public final class FontImport
{
    public static final String[] EXTENSIONS = {"ttf", "otf", "ttc"};

    /** Real fonts are a few MB; anything this large is a mistake or a decompression bomb. */
    public static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final int TTC_MAGIC = 0x74746366;

    private FontImport()
    {}

    /** True for a TrueType collection ({@code ttcf}), which holds several faces in one file. */
    public static boolean isCollection(byte[] data)
    {
        return data != null && data.length >= 12 && magic(data) == TTC_MAGIC;
    }

    /** Number of faces in a collection, or 0 when the bytes are not a collection. */
    public static int faceCount(byte[] data)
    {
        if (!isCollection(data))
        {
            return 0;
        }

        long count = readU32(data, 8);

        return count < 0 || count > 1024 ? 0 : (int) count;
    }

    /**
     * Extracts one face of a {@code .ttc} collection as a standalone font file.
     *
     * <p>First principle: a collection is a container, a font is one face's table directory. BBS'
     * stb loader only accepts a single font at offset 0, which is why importing {@code msyh.ttc}
     * (Microsoft YaHei — the font most Chinese users actually want) produced "cannot parse" and
     * then the default font. Extracting face 0 writes a normal {@code .ttf} that every loader
     * understands.</p>
     *
     * <p>The table offsets stored in a TTC are absolute from the beginning of the file, so the
     * tables are copied from those positions into a fresh, 4-byte-aligned sfnt. Checksums are
     * copied verbatim; stb and BBS do not verify them, and the {@code head} checksum adjustment is
     * the only field that would go stale.</p>
     */
    public static byte[] extractFace(byte[] data, int faceIndex) throws IOException
    {
        if (!isCollection(data))
        {
            return data;
        }

        int count = faceCount(data);

        if (count <= 0)
        {
            throw new IOException("TTC 字体集合的表头无效");
        }

        if (faceIndex < 0 || faceIndex >= count)
        {
            throw new IOException("TTC 字体集合没有第 " + (faceIndex + 1) + " 个字体（共 " + count + " 个）");
        }

        long headerOffset = readU32(data, 12 + faceIndex * 4L);

        if (headerOffset < 0 || headerOffset + 12 > data.length)
        {
            throw new IOException("TTC 字体集合的偏移越界");
        }

        int base = (int) headerOffset;
        int tables = readU16(data, base + 4);

        if (tables <= 0 || tables > 512 || base + 12 + tables * 16L > data.length)
        {
            throw new IOException("字体表目录无效（" + tables + " 个表）");
        }

        long dataSize = 12L + tables * 16L;

        for (int i = 0; i < tables; i++)
        {
            int record = base + 12 + i * 16;
            long length = readU32(data, record + 12);

            if (length < 0 || length > data.length)
            {
                throw new IOException("字体表长度无效");
            }

            dataSize += align4(length);
        }

        if (dataSize > MAX_BYTES)
        {
            throw new IOException("字体表过大（" + dataSize + " 字节）");
        }

        byte[] output = new byte[(int) dataSize];
        int cursor = 12 + tables * 16;

        /* sfntVersion + the binary-search fields, copied verbatim. */
        System.arraycopy(data, base, output, 0, 12);

        for (int i = 0; i < tables; i++)
        {
            int record = base + 12 + i * 16;
            long offset = readU32(data, record + 8);
            long length = readU32(data, record + 12);

            if (offset < 0 || offset + length > data.length)
            {
                throw new IOException("字体表偏移越界");
            }

            int destination = 12 + i * 16;

            /* tag + checksum stay; the offset becomes the new file position. */
            System.arraycopy(data, record, output, destination, 8);
            writeU32(output, destination + 8, cursor);
            writeU32(output, destination + 12, length);
            System.arraycopy(data, (int) offset, output, cursor, (int) length);

            cursor += align4(length);
        }

        return output;
    }

    /**
     * Returns a user-facing error when the bytes cannot be a single TTF/OTF, or null when the
     * container is plausible and the stb parser should get a chance at it.
     */
    public static String headerError(byte[] data)
    {
        if (data == null)
        {
            return "无法读取字体文件";
        }

        if (data.length > MAX_BYTES)
        {
            return "字体文件超过 64 MB";
        }

        if (data.length < 4)
        {
            return "字体文件太小，不是有效的 TTF/OTF";
        }

        long magic = ((long) (data[0] & 0xFF) << 24)
            | ((long) (data[1] & 0xFF) << 16)
            | ((long) (data[2] & 0xFF) << 8)
            | (data[3] & 0xFF);

        if (isCollection(data))
        {
            /* Supported: the importer extracts face 0 (see extractFace). Reaching this method with a
             * collection means a caller asked "is this a single font" — answer that. */
            return null;
        }

        if (magic == 0x774F4646L || magic == 0x774F4632L)
        {
            return "WOFF/WOFF2 不受支持，请使用原始 TTF/OTF";
        }

        if (magic == 0x00010000L || magic == 0x74727565L || magic == 0x74797031L || magic == 0x4F54544FL)
        {
            /* 0x4F54544F is "OTTO" (CFF outlines). stb can read some CFF fonts and refuses others;
             * the actual BBS load after the copy is the final authority. */
            return null;
        }

        return "不是 TTF/OTF 文件（文件头 0x" + Long.toHexString(magic).toUpperCase(Locale.ROOT) + "）";
    }

    /**
     * Keeps the font's real name (including CJK, spaces and parentheses) and only replaces the
     * characters a Windows/Linux path cannot hold. This is what makes the picker readable and
     * stops unrelated fonts from overwriting each other.
     */
    public static String sanitizeName(String fileName)
    {
        String name = fileName == null ? "" : fileName;
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();

        if (name.isEmpty())
        {
            name = "font.ttf";
        }

        /* Leave the extension intact when truncating a very long name. */
        if (name.length() > 96)
        {
            String extension = extension(name);
            String stem = name.substring(0, name.length() - extension.length());

            name = stem.substring(0, Math.max(1, 96 - extension.length())) + extension;
        }

        return name;
    }

    /** Ensures a .ttf/.otf suffix so BBS' font catalogue recognises the file. */
    public static String withExtension(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".ttf") || lower.endsWith(".otf"))
        {
            return name;
        }

        return name + ".ttf";
    }

    /**
     * A target path in {@code folder} that does not overwrite a different font.
     *
     * <p>Importing the same file twice reuses the existing copy (byte-for-byte), so re-selecting a
     * font does not litter the folder with {@code font-2}, {@code font-3}. A different file with
     * the same display name gets a numeric suffix instead of replacing the first one.</p>
     */
    public static Path uniqueTarget(Path folder, String requestedName, Path source) throws IOException
    {
        Path absoluteFolder = folder.toAbsolutePath().normalize();
        String name = withExtension(sanitizeName(requestedName));
        Path first = absoluteFolder.resolve(name);

        if (!Files.exists(first) || sameContent(first, source))
        {
            return first;
        }

        String extension = extension(name);
        String stem = name.substring(0, name.length() - extension.length());

        for (int i = 2; i < 1000; i++)
        {
            Path candidate = absoluteFolder.resolve(stem + "-" + i + extension);

            if (!Files.exists(candidate) || sameContent(candidate, source))
            {
                return candidate;
            }
        }

        throw new IOException("字体目录里同名文件过多：" + name);
    }

    /**
     * Content-aware target for bytes that were written by the importer itself (a TTC face).
     *
     * <p>Re-importing the same collection reuses the extracted file instead of writing
     * {@code msyh-1}, {@code msyh-2}, …; a different face with the same name gets a suffix.</p>
     */
    public static Path uniqueTarget(Path folder, String requestedName, byte[] data) throws IOException
    {
        Path absoluteFolder = folder.toAbsolutePath().normalize();
        String name = withExtension(sanitizeName(requestedName));
        Path first = absoluteFolder.resolve(name);

        if (!Files.exists(first) || sameBytes(first, data))
        {
            return first;
        }

        String extension = extension(name);
        String stem = name.substring(0, name.length() - extension.length());

        for (int i = 2; i < 1000; i++)
        {
            Path candidate = absoluteFolder.resolve(stem + "-" + i + extension);

            if (!Files.exists(candidate) || sameBytes(candidate, data))
            {
                return candidate;
            }
        }

        throw new IOException("字体目录里同名文件过多：" + name);
    }

    public static boolean sameContent(Path a, Path b) throws IOException
    {
        return Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1L;
    }

    public static boolean sameBytes(Path file, byte[] data) throws IOException
    {
        if (data == null || Files.size(file) != data.length)
        {
            return false;
        }

        byte[] existing = Files.readAllBytes(file);

        return java.util.Arrays.equals(existing, data);
    }

    private static String extension(String name)
    {
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));

        return dot > slash ? name.substring(dot) : "";
    }

    /* ------------------------------------------------------------------- primitives */

    private static int magic(byte[] data)
    {
        return (int) readU32(data, 0);
    }

    private static int readU16(byte[] data, long offset)
    {
        if (offset < 0 || offset + 2 > data.length)
        {
            return -1;
        }

        int index = (int) offset;

        return ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
    }

    private static long readU32(byte[] data, long offset)
    {
        if (offset < 0 || offset + 4 > data.length)
        {
            return -1L;
        }

        int index = (int) offset;

        return ((long) (data[index] & 0xFF) << 24)
            | ((long) (data[index + 1] & 0xFF) << 16)
            | ((long) (data[index + 2] & 0xFF) << 8)
            | (data[index + 3] & 0xFF);
    }

    private static void writeU32(byte[] data, int offset, long value)
    {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static long align4(long value)
    {
        return (value + 3L) & ~3L;
    }
}
