package dev.bbsae.comp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The project panel's media index.
 *
 * <h2>Why this is not a file listing</h2>
 * <p>A Premiere project panel is an <b>index with identity</b>: a clip has a stable token, a name
 * the user can rename, a bin it belongs to, and an online/offline state. The old asset library
 * re-walked the folder on every search, showed every frame of an imported video as an individual
 * picture (a 60 s clip is 3 600 tiles), and lost external videos entirely because nothing wrote
 * them to the external index.</p>
 *
 * <p>This class fixes the identity part. It scans the project's {@code assets} folder once and
 * produces four kinds of item:</p>
 * <ul>
 *   <li><b>IMAGE</b> — png/jpg/jpeg/gif;</li>
 *   <li><b>AUDIO</b> — wav/ogg/mp3/flac/m4a/aac/opus/wma/aiff;</li>
 *   <li><b>VIDEO</b> — an external video or a video file kept in the project;</li>
 *   <li><b>SEQUENCE</b> — a {@code frames/<clip>/frame_0000.png} folder as <b>one</b> item, because
 *       that is what a footage layer actually is.</li>
 * </ul>
 *
 * <p>Bins only store assignments ({@link CompProject.CompSettings#mediaBinAssignments}); deleting a
 * bin never deletes media, and a missing external file stays visible as offline instead of silently
 * disappearing.</p>
 */
public final class CompMediaLibrary
{
    public static final String DEFAULT_BIN_NAME = "项目素材";
    public static final String DEFAULT_BIN_ID = "bin_default";

    private static final Pattern FRAME = Pattern.compile("frame_(\\d+)\\.png$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DEPTH = 8;

    public enum Kind
    {
        IMAGE, AUDIO, VIDEO, SEQUENCE
    }

    /** One bin/list entry. Immutable: a rescan builds a fresh list. */
    public static final class Item
    {
        public final String path;
        public final String name;
        public final Kind kind;
        public final String binId;
        public final boolean external;
        public final boolean offline;
        public final long size;
        public final String detail;

        Item(String path, String name, Kind kind, String binId, boolean external, boolean offline,
             long size, String detail)
        {
            this.path = path;
            this.name = name;
            this.kind = kind;
            this.binId = binId;
            this.external = external;
            this.offline = offline;
            this.size = size;
            this.detail = detail;
        }

        public boolean isVisual()
        {
            return this.kind == Kind.IMAGE || this.kind == Kind.VIDEO || this.kind == Kind.SEQUENCE;
        }

        public boolean isAudio()
        {
            return this.kind == Kind.AUDIO;
        }

        /** File extension, lowercase, without the dot. */
        public String extension()
        {
            String token = this.path.startsWith("external:") ? this.path : this.name;
            int dot = token.lastIndexOf('.');

            return dot < 0 ? "" : token.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
    }

    private CompMediaLibrary()
    {}

    /* --------------------------------------------------------------- bins */

    /** Ensures a project has at least one bin; called on scan and new-project creation. */
    public static CompMediaBin ensureDefaultBin(CompProject project)
    {
        CompProject.CompSettings settings = settings(project);

        if (settings.mediaBins == null)
        {
            settings.mediaBins = new ArrayList<>();
        }

        if (settings.mediaBins.isEmpty())
        {
            CompMediaBin bin = new CompMediaBin(DEFAULT_BIN_ID, DEFAULT_BIN_NAME);

            settings.mediaBins.add(bin);

            return bin;
        }

        for (CompMediaBin bin : settings.mediaBins)
        {
            if (bin != null && DEFAULT_BIN_ID.equals(bin.id))
            {
                return bin;
            }
        }

        for (CompMediaBin bin : settings.mediaBins)
        {
            if (bin != null)
            {
                return bin;
            }
        }

        CompMediaBin bin = new CompMediaBin(DEFAULT_BIN_ID, DEFAULT_BIN_NAME);

        settings.mediaBins.add(bin);

        return bin;
    }

    public static String defaultBinId(CompProject project)
    {
        return ensureDefaultBin(project).id;
    }

    public static List<CompMediaBin> bins(CompProject project)
    {
        ensureDefaultBin(project);

        return project.settings.mediaBins;
    }

    public static CompMediaBin addBin(CompProject project, String name)
    {
        ensureDefaultBin(project);

        String base = "bin_" + (project.settings.mediaBins.size() + 1);
        String id = base;
        int suffix = 2;

        while (findBin(project, id) != null)
        {
            id = base + "_" + suffix++;
        }

        CompMediaBin bin = new CompMediaBin(id, name == null || name.isBlank()
            ? "素材箱 " + (project.settings.mediaBins.size() + 1) : name.trim());

        project.settings.mediaBins.add(bin);

        return bin;
    }

    public static CompMediaBin findBin(CompProject project, String id)
    {
        if (project == null || project.settings == null || project.settings.mediaBins == null || id == null)
        {
            return null;
        }

        for (CompMediaBin bin : project.settings.mediaBins)
        {
            if (bin != null && id.equals(bin.id))
            {
                return bin;
            }
        }

        return null;
    }

    /**
     * Deletes a bin and moves its items to the default bin.
     *
     * <p>Returns false for the last bin: a project panel with zero locations has nowhere to show
     * its media, which reads as "my imports disappeared".</p>
     */
    public static boolean removeBin(CompProject project, String id)
    {
        ensureDefaultBin(project);

        if (project.settings.mediaBins.size() <= 1 || id == null || DEFAULT_BIN_ID.equals(id))
        {
            return false;
        }

        CompMediaBin bin = findBin(project, id);

        if (bin == null)
        {
            return false;
        }

        project.settings.mediaBins.remove(bin);

        for (Map.Entry<String, String> entry : project.settings.mediaBinAssignments.entrySet())
        {
            if (id.equals(entry.getValue()))
            {
                entry.setValue(defaultBinId(project));
            }
        }

        return true;
    }

    public static boolean renameBin(CompProject project, String id, String name)
    {
        CompMediaBin bin = findBin(project, id);

        if (bin == null || name == null || name.isBlank())
        {
            return false;
        }

        bin.name = name.trim();

        return true;
    }

    public static String binOf(CompProject project, String token)
    {
        CompProject.CompSettings settings = settings(project);
        String assigned = settings.mediaBinAssignments.get(token);

        if (assigned != null && findBin(project, assigned) != null)
        {
            return assigned;
        }

        return defaultBinId(project);
    }

    public static void assign(CompProject project, String token, String binId)
    {
        if (project == null || token == null || token.isBlank())
        {
            return;
        }

        CompProject.CompSettings settings = settings(project);

        if (binId == null || findBin(project, binId) == null)
        {
            binId = defaultBinId(project);
        }

        settings.mediaBinAssignments.put(token, binId);
    }

    /* --------------------------------------------------------------- scan */

    /** Scans the project folder and the external index. Never throws; unreadable folders are empty. */
    public static List<Item> scan(CompProject project)
    {
        List<Item> items = new ArrayList<>();

        if (project == null)
        {
            return items;
        }

        ensureDefaultBin(project);

        Map<String, Sequence> sequences = new LinkedHashMap<>();
        Path root = CompStore.assetRoot(project.id);

        if (Files.isDirectory(root))
        {
            try (Stream<Path> stream = Files.walk(root, MAX_DEPTH))
            {
                stream.filter(Files::isRegularFile).forEach((file) ->
                {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    String lower = relative.toLowerCase(Locale.ROOT);

                    if (lower.startsWith("frames/"))
                    {
                        Matcher matcher = FRAME.matcher(lower);

                        if (matcher.find())
                        {
                            String folder = relative.substring(0, relative.lastIndexOf('/'));
                            String digits = matcher.group(1);

                            sequences.computeIfAbsent(folder, (ignored) ->
                                new Sequence(folder, digits.length())).count++;
                        }

                        return;
                    }

                    Kind kind = kindOf(relative);

                    if (kind == null || kind == Kind.SEQUENCE)
                    {
                        return;
                    }

                    long size = sizeOf(file);
                    String name = stripExtension(file.getFileName().toString());
                    String bin = binOf(project, relative);

                    items.add(new Item(relative, name, kind, bin, false, false, size,
                        kindLabel(kind) + " · " + sizeLabel(size)));
                });
            }
            catch (Exception ignored)
            {
                /* A vanished folder simply contributes nothing. */
            }
        }

        for (Sequence sequence : sequences.values())
        {
            String token = sequence.token();
            String bin = binOf(project, token);
            String name = sequence.folder.substring(sequence.folder.lastIndexOf('/') + 1);

            items.add(new Item(token, name + "（" + sequence.count + " 帧）", Kind.SEQUENCE, bin,
                false, false, 0L, "帧序列 · " + sequence.count + " 帧"));
        }

        List<String> external = project.settings == null || project.settings.externalAssets == null
            ? java.util.Collections.emptyList() : project.settings.externalAssets;
        java.util.Set<String> seenExternal = new java.util.HashSet<>();

        for (String link : external)
        {
            if (link == null || !link.startsWith("external:"))
            {
                continue;
            }

            if (!seenExternal.add(link))
            {
                continue;
            }

            String raw = link.substring("external:".length());
            Path file = safePath(raw);
            boolean offline = file == null || !Files.isRegularFile(file);
            String fileName = raw.replace('\\', '/');
            fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
            String lower = fileName.toLowerCase(Locale.ROOT);
            Kind kind = kindOf(lower);

            if (kind == null)
            {
                continue;
            }

            String name = stripExtension(fileName);
            String bin = binOf(project, link);

            items.add(new Item(link, name, kind, bin, true, offline, sizeOf(file),
                (offline ? "外链 · 缺失" : "外链 · " + sizeLabel(sizeOf(file)))));
        }

        items.sort(Comparator.comparing((item) -> item.name.toLowerCase(Locale.ROOT)));
        assignOrphanedBins(project, items);

        return items;
    }

    /** Keeps assignments pointing at bins that still exist; prunes entries for files deleted outside. */
    private static void assignOrphanedBins(CompProject project, List<Item> items)
    {
        CompProject.CompSettings settings = settings(project);

        java.util.Set<String> tokens = new java.util.HashSet<>();

        for (Item item : items)
        {
            tokens.add(item.path);

            String assigned = settings.mediaBinAssignments.get(item.path);

            if (assigned != null && findBin(project, assigned) == null)
            {
                settings.mediaBinAssignments.put(item.path, defaultBinId(project));
            }
        }

        settings.mediaBinAssignments.keySet().removeIf((token) -> !tokens.contains(token));
    }

    /* --------------------------------------------------------------- lookup */

    public static Item find(List<Item> items, String token)
    {
        if (token == null)
        {
            return null;
        }

        for (Item item : items)
        {
            if (token.equals(item.path))
            {
                return item;
            }
        }

        return null;
    }

    public static List<Item> inBin(List<Item> items, String binId)
    {
        List<Item> out = new ArrayList<>();

        for (Item item : items)
        {
            if (binId == null || binId.equals(item.binId))
            {
                out.add(item);
            }
        }

        return out;
    }

    public static List<Item> search(List<Item> items, String query)
    {
        if (query == null || query.isBlank())
        {
            return new ArrayList<>(items);
        }

        String needle = query.toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();

        for (Item item : items)
        {
            if (item.name.toLowerCase(Locale.ROOT).contains(needle)
                || item.path.toLowerCase(Locale.ROOT).contains(needle))
            {
                out.add(item);
            }
        }

        return out;
    }

    /* --------------------------------------------------------------- helpers */

    public static Kind kindOf(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif"))
        {
            return Kind.IMAGE;
        }

        if (lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".mp3")
            || lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".aac")
            || lower.endsWith(".opus") || lower.endsWith(".wma") || lower.endsWith(".aiff")
            || lower.endsWith(".aif"))
        {
            return Kind.AUDIO;
        }

        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
            || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".m4v"))
        {
            return Kind.VIDEO;
        }

        return null;
    }

    public static String stripExtension(String name)
    {
        int dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Concrete file for frame {@code frame} of a sequence token.
     *
     * <p>The token keeps the printf width ({@code frame_%04d.png}) so the first/last frame paths
     * match the extraction. Formatting with a hard-coded four digits broke 5-digit sequences.</p>
     */
    public static String sequenceFrame(Item item, int frame)
    {
        if (item == null || item.kind != Kind.SEQUENCE)
        {
            return item == null ? null : item.path;
        }

        Matcher matcher = Pattern.compile("frame_%(\\d*)d\\.png$", Pattern.CASE_INSENSITIVE).matcher(item.path);

        if (!matcher.find())
        {
            return item.path;
        }

        int width = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));

        return item.path.substring(0, matcher.start())
            + "frame_" + String.format(Locale.ROOT, "%0" + width + "d", Math.max(0, frame)) + ".png";
    }

    /** Frame count of a sequence; 0 when it is not one or the count was lost. */
    public static int sequenceFrames(Item item)
    {
        if (item == null)
        {
            return 0;
        }

        Matcher matcher = Pattern.compile("(\\d+)\\s*帧").matcher(item.detail == null ? "" : item.detail);

        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    public static String kindLabel(Kind kind)
    {
        return switch (kind)
        {
            case IMAGE -> "图片";
            case AUDIO -> "音频";
            case VIDEO -> "视频";
            case SEQUENCE -> "帧序列";
        };
    }

    private static long sizeOf(Path file)
    {
        try
        {
            return Files.size(file);
        }
        catch (Exception e)
        {
            return 0L;
        }
    }

    public static String sizeLabel(long bytes)
    {
        if (bytes >= 1024L * 1024L)
        {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024F * 1024F));
        }

        if (bytes >= 1024L)
        {
            return Math.max(1L, bytes / 1024L) + " KB";
        }

        return bytes > 0 ? bytes + " B" : "—";
    }

    private static Path safePath(String raw)
    {
        try
        {
            return Path.of(raw);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static CompProject.CompSettings settings(CompProject project)
    {
        if (project.settings == null)
        {
            project.settings = new CompProject.CompSettings();
        }

        if (project.settings.mediaBins == null)
        {
            project.settings.mediaBins = new ArrayList<>();
        }

        if (project.settings.mediaBinAssignments == null)
        {
            project.settings.mediaBinAssignments = new LinkedHashMap<>();
        }

        return project.settings;
    }

    private static final class Sequence
    {
        final String folder;
        final int digits;
        int count;

        Sequence(String folder, int digits)
        {
            this.folder = folder;
            this.digits = Math.max(1, digits);
        }

        String token()
        {
            String padding = this.digits < 10 ? "0" + this.digits : String.valueOf(this.digits);

            return "assets/" + this.folder + "/frame_%" + padding + "d.png";
        }
    }
}
