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
    public static final String[] EXTENSIONS = {"ttf", "otf"};

    /** Real fonts are a few MB; anything this large is a mistake or a decompression bomb. */
    public static final long MAX_BYTES = 64L * 1024L * 1024L;

    private FontImport()
    {}

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

        if (magic == 0x74746366L)
        {
            return "TTC 字体集合不受支持，请在字体软件中导出单个 TTF/OTF";
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

    public static boolean sameContent(Path a, Path b) throws IOException
    {
        return Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1L;
    }

    private static String extension(String name)
    {
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));

        return dot > slash ? name.substring(dot) : "";
    }
}
