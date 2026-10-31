package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Disk L2 for the AE frame server.
 *
 * <h2>Why the RAM cache is not enough</h2>
 * <p>{@link CompFrameServer} keys frames by the document's content fingerprint, so an edit makes the
 * old frames unreachable — correct, but it also means a frame is thrown away the moment you type a
 * value and change it back, or reopen the project tomorrow. Expensive frames (a 4K comp with a
 * dozen effects) cost tens of milliseconds each and are worth keeping across those states.</p>
 *
 * <p>This is the same idea as Olive's frame hash cache: the file name <em>is</em> the content hash,
 * so a cache entry can never be stale — different content means a different file. There is no
 * invalidation pass, no "clear cache" button to forget, and a corrupt entry is simply a miss.</p>
 *
 * <h2>What is stored, and what is not</h2>
 * <p>Raw bottom-up <b>BGRA</b> ints — the exact layout the composition FBO reads back, so loading a
 * frame is a file read plus one texture upload, with no PNG encode/decode in the middle (ImageIO is
 * slower than re-rendering a simple comp, which would defeat the point). The trade-off is size:
 * 8 MB per 1080p frame. That is why the budget is enforced by {@link #BUDGET_BYTES} and entries are
 * evicted oldest-first.</p>
 *
 * <p>Only frames that took longer than {@link #MIN_RENDER_MS} to render are written: caching a
 * 3 ms frame costs more than it saves.</p>
 */
public final class CompFrameDisk
{
    /** 512 MB, i.e. ~64 frames of 1080p. Disk, not VRAM, so the budget can be larger. */
    static final long BUDGET_BYTES = 512L * 1024 * 1024;

    /** Below this render time a frame is not worth writing to disk. */
    static final float MIN_RENDER_MS = 45F;

    private static final int MAGIC = 0x42425346; // "BBSF"
    private static final int VERSION = 1;

    private static boolean sweeping;

    private CompFrameDisk()
    {}

    public static Path root()
    {
        try
        {
            return FabricLoader.getInstance().getConfigDir().resolve("bbsae").resolve("cache").resolve("frames");
        }
        catch (Throwable unavailable)
        {
            /* No game (unit tests, headless tools): a temp folder keeps the cache usable instead of
             * throwing from a static initialiser. */
            return Path.of(System.getProperty("java.io.tmpdir"), "bbsae-frames");
        }
    }

    private static Path folder(CompProject project, CompDocument doc)
    {
        String projectId = project == null || project.id == null ? "default" : project.id;
        String compId = doc == null || doc.id == null ? "main" : doc.id;

        return root().resolve(safe(projectId)).resolve(safe(compId));
    }

    private static String safe(String value)
    {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static Path file(CompProject project, CompDocument doc, long signature, int frame, int width, int height)
    {
        return folder(project, doc).resolve(Long.toHexString(signature) + "_" + frame + "_" + width + "x" + height + ".rgba");
    }

    /**
     * Reads a cached frame.
     *
     * @return bottom-up ARGB pixels (the FBO's native order), or null when there is no valid entry.
     */
    public static int[] load(CompProject project, CompDocument doc, long signature, int frame, int width, int height)
    {
        Path file = file(project, doc, signature, frame, width, height);

        if (!Files.isRegularFile(file))
        {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16)))
        {
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
            {
                Files.deleteIfExists(file);

                return null;
            }

            int w = in.readInt();
            int h = in.readInt();
            int index = in.readInt();

            if (w != width || h != height || index != frame)
            {
                Files.deleteIfExists(file);

                return null;
            }

            int[] pixels = new int[width * height];

            for (int i = 0; i < pixels.length; i++)
            {
                pixels[i] = in.readInt();
            }

            /* Touch: eviction is oldest-first, and a frame that is still being used is not old. */
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

            return pixels;
        }
        catch (Exception e)
        {
            try
            {
                Files.deleteIfExists(file);
            }
            catch (IOException ignored)
            {}

            return null;
        }
    }

    public static void save(CompProject project, CompDocument doc, long signature, int frame, int width, int height, int[] pixels)
    {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height)
        {
            return;
        }

        Path file = file(project, doc, signature, frame, width, height);

        try
        {
            Files.createDirectories(file.getParent());

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE), 1 << 16)))
            {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(width);
                out.writeInt(height);
                out.writeInt(frame);

                for (int i = 0; i < width * height; i++)
                {
                    out.writeInt(pixels[i]);
                }
            }
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not write frame cache {}", file, e);

            return;
        }

        sweep(file.getParent());
    }

    /** Total bytes currently on disk. */
    public static long size()
    {
        long total = 0;

        try (Stream<Path> stream = Files.walk(root()))
        {
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator)
            {
                try
                {
                    total += Files.size(path);
                }
                catch (IOException ignored)
                {}
            }
        }
        catch (Exception ignored)
        {}

        return total;
    }

    /**
     * Deletes the oldest entries until the folder is back under 80 % of the budget.
     *
     * <p>Evicting to 80 % (instead of exactly the budget) keeps the sweep from running on every
     * single save once the cache is full.</p>
     */
    private static void sweep(Path folder)
    {
        if (sweeping)
        {
            return;
        }

        sweeping = true;

        try
        {
            List<Path> files = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(root()))
            {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            catch (Exception ignored)
            {}

            long total = 0;
            List<long[]> sizes = new ArrayList<>();

            for (int i = 0; i < files.size(); i++)
            {
                try
                {
                    long bytes = Files.size(files.get(i));

                    total += bytes;
                    sizes.add(new long[] {i, bytes, Files.getLastModifiedTime(files.get(i)).toMillis()});
                }
                catch (IOException ignored)
                {}
            }

            if (total <= BUDGET_BYTES)
            {
                return;
            }

            sizes.sort(Comparator.comparingLong((long[] entry) -> entry[2]));

            long target = (long) (BUDGET_BYTES * 0.8D);

            for (long[] entry : sizes)
            {
                if (total <= target)
                {
                    break;
                }

                try
                {
                    Files.deleteIfExists(files.get((int) entry[0]));
                    total -= entry[1];
                }
                catch (IOException ignored)
                {}
            }
        }
        finally
        {
            sweeping = false;
        }
    }

    /** Drops everything; used by the settings page and by tests. */
    public static void clear()
    {
        try (Stream<Path> stream = Files.walk(root()))
        {
            stream.sorted(Comparator.reverseOrder()).forEach((path) ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {}
            });
        }
        catch (Exception ignored)
        {}
    }
}
