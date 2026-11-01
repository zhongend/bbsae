package dev.bbsae.client.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The fonts installed on the operating system, for the "system fonts" picker.
 *
 * <p>BBS' {@code FontManager} only loads links under {@code assets:fonts}. A system font therefore
 * cannot be rendered in place; the correct integration is the same one the original BBS folder
 * workflow uses — put a copy in {@code config/bbs/assets/fonts}, then use the normal link. This
 * class only discovers and names the candidates; the UI copies the chosen file through
 * {@link FontImport}.</p>
 *
 * <p>Names come from the OpenType {@code name} table (full name, then family), not the filename,
 * so "msyhbd.ttc" would be "Microsoft YaHei Bold". Collections ({@code .ttc}) are listed and named
 * by their first face, because that is the face the importer extracts before handing the font to
 * BBS' stb loader.</p>
 */
public final class SystemFonts
{
    public static final class Entry
    {
        public final Path path;
        public final String displayName;

        Entry(Path path, String displayName)
        {
            this.path = path;
            this.displayName = displayName;
        }

        public String fileName()
        {
            return this.path.getFileName().toString();
        }
    }

    private static List<Entry> cache;

    private SystemFonts()
    {}

    public static synchronized List<Entry> list()
    {
        if (cache == null)
        {
            cache = scan();
        }

        return cache;
    }

    public static synchronized void refresh()
    {
        cache = null;
    }

    public static boolean isSupported(Path path)
    {
        if (path == null || !Files.isRegularFile(path))
        {
            return false;
        }

        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }

    private static List<Entry> scan()
    {
        Set<Path> roots = roots();
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Path root : roots)
        {
            if (!Files.isDirectory(root))
            {
                continue;
            }

            try (var stream = Files.walk(root, 6))
            {
                stream.filter(SystemFonts::isSupported).forEach((path) ->
                {
                    try
                    {
                        if (Files.size(path) > 64L * 1024L * 1024L)
                        {
                            return;
                        }
                    }
                    catch (IOException ignored)
                    {
                        return;
                    }

                    String key = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);

                    if (!seen.add(key))
                    {
                        return;
                    }

                    String display = fullName(path);

                    if (display == null || display.isBlank())
                    {
                        display = stem(path);
                    }

                    entries.add(new Entry(path, display));
                });
            }
            catch (IOException | RuntimeException ignored)
            {
                /* A protected font folder or an unreadable link must not take the whole picker
                 * down; the other roots are still worth listing. */
            }
        }

        entries.sort(Comparator.comparing((Entry entry) -> entry.displayName.toLowerCase(Locale.ROOT))
            .thenComparing((Entry entry) -> entry.path.toString()));

        return entries;
    }

    private static Set<Path> roots()
    {
        Set<Path> roots = new LinkedHashSet<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");

        try
        {
        if (os.contains("win"))
        {
            String windir = System.getenv("WINDIR");
            String localAppData = System.getenv("LOCALAPPDATA");

            if (windir != null)
            {
                roots.add(Path.of(windir, "Fonts"));
            }

            if (localAppData != null)
            {
                roots.add(Path.of(localAppData, "Microsoft", "Windows", "Fonts"));
            }
        }
        else if (os.contains("mac"))
        {
            roots.add(Path.of("/System/Library/Fonts"));
            roots.add(Path.of("/Library/Fonts"));

            if (home != null)
            {
                roots.add(Path.of(home, "Library", "Fonts"));
            }
        }
        else
        {
            roots.add(Path.of("/usr/share/fonts"));
            roots.add(Path.of("/usr/local/share/fonts"));

            if (home != null)
            {
                roots.add(Path.of(home, ".local", "share", "fonts"));
                roots.add(Path.of(home, ".fonts"));
            }
        }
        }
        catch (RuntimeException ignored)
        {
            /* Invalid environment paths: return whatever valid roots were collected. */
        }

        return roots;
    }

    private static String stem(Path path)
    {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Full name (nameID 4) if present, otherwise family (nameID 1), from the OpenType name table. */
    static String fullName(Path file)
    {
        try (SeekableByteChannel channel = Files.newByteChannel(file))
        {
            ByteBuffer header = read(channel, 0L, 12);

            if (header == null)
            {
                return null;
            }

            header.order(ByteOrder.BIG_ENDIAN);

            long fontOffset = 0L;

            if (header.getInt(0) == 0x74746366)
            {
                /* ttcf: a collection. Face 0's table directory lives at the offset in its header,
                 * and its table offsets are absolute from the file start, so parsing continues
                 * exactly like a standalone font once the directory position is known. */
                long faces = header.getInt(8) & 0xFFFFFFFFL;

                if (faces <= 0L)
                {
                    return null;
                }

                ByteBuffer offsets = read(channel, 12L, 4);

                if (offsets == null)
                {
                    return null;
                }

                offsets.order(ByteOrder.BIG_ENDIAN);
                fontOffset = offsets.getInt(0) & 0xFFFFFFFFL;

                if (fontOffset <= 0L || fontOffset + 12L > Files.size(file))
                {
                    return null;
                }

                header = read(channel, fontOffset, 12);

                if (header == null)
                {
                    return null;
                }

                header.order(ByteOrder.BIG_ENDIAN);
            }

            int tables = header.getShort(4) & 0xFFFF;
            ByteBuffer directory = read(channel, fontOffset + 12L, Math.min(tables, 256) * 16);

            if (directory == null)
            {
                return null;
            }

            directory.order(ByteOrder.BIG_ENDIAN);

            long nameOffset = -1L;
            int nameLength = 0;

            for (int i = 0; i < Math.min(tables, 256); i++)
            {
                int record = i * 16;
                int tag = directory.getInt(record);

                if (tag == 0x6E616D65)
                {
                    nameOffset = directory.getInt(record + 8) & 0xFFFFFFFFL;
                    nameLength = directory.getInt(record + 12);

                    break;
                }
            }

            if (nameOffset < 0L || nameLength < 6 || nameLength > 4 * 1024 * 1024)
            {
                return null;
            }

            ByteBuffer table = read(channel, nameOffset, nameLength);

            if (table == null)
            {
                return null;
            }

            table.order(ByteOrder.BIG_ENDIAN);

            /* name table: format(0), count(2), stringOffset(4). Reading count from 0 made every
             * valid font parse as "0 records", so the list fell back to filenames. */
            int count = table.getShort(2) & 0xFFFF;
            int stringOffset = table.getShort(4) & 0xFFFF;
            String best = null;
            int bestRank = Integer.MAX_VALUE;

            for (int i = 0; i < Math.min(count, 512); i++)
            {
                int record = 6 + i * 12;

                if (record + 12 > table.limit())
                {
                    break;
                }

                int platform = table.getShort(record) & 0xFFFF;
                int encoding = table.getShort(record + 2) & 0xFFFF;
                int language = table.getShort(record + 4) & 0xFFFF;
                int nameId = table.getShort(record + 6) & 0xFFFF;
                int length = table.getShort(record + 8) & 0xFFFF;
                int offset = table.getShort(record + 10) & 0xFFFF;

                if ((nameId != 4 && nameId != 1) || length <= 0)
                {
                    continue;
                }

                int start = stringOffset + offset;

                if (start < 0 || start + length > table.limit())
                {
                    continue;
                }

                ByteBuffer slice = table.duplicate();

                slice.position(start);
                slice.limit(start + length);

                String text;

                if (platform == 3 || platform == 0)
                {
                    text = StandardCharsets.UTF_16BE.decode(slice).toString();
                }
                else
                {
                    text = StandardCharsets.ISO_8859_1.decode(slice).toString();
                }

                text = text.replace("\u0000", "").trim();

                if (text.isEmpty() || text.length() > 120)
                {
                    continue;
                }

                /* Prefer the Windows Unicode full name, then any full name, then the family. */
                int rank = (nameId == 4 ? 0 : 10) + (platform == 3 ? 0 : platform == 0 ? 1 : 2)
                    + (language == 0x0409 ? 0 : 1);

                if (rank < bestRank)
                {
                    bestRank = rank;
                    best = text;
                }
            }

            return best;
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    private static ByteBuffer read(SeekableByteChannel channel, long position, int length) throws IOException
    {
        if (length <= 0)
        {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);

        channel.position(position);

        while (buffer.hasRemaining())
        {
            int read = channel.read(buffer);

            if (read <= 0)
            {
                break;
            }
        }

        buffer.flip();

        return buffer.hasRemaining() ? buffer : null;
    }
}
