package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.utils.FFMpegUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On-demand frame extraction for externally linked video layers.
 *
 * <p>The editor needs random access, which a streaming decoder does not provide. This cache asks
 * FFmpeg for one still per requested frame and stores it outside the project. Preview requests are
 * asynchronous (a missing frame returns null for one frame instead of freezing the whole editor);
 * export requests are synchronous because the encoder cannot skip a frame.</p>
 */
public final class CompVideoFrames
{
    private static final Map<String, Path> CACHE = new LinkedHashMap<>(32, 0.75F, true);
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor((r) ->
    {
        Thread thread = new Thread(r, "bbsae-video-frames");
        thread.setDaemon(true);

        return thread;
    });
    private static final int MAX_ENTRIES = 96;

    private CompVideoFrames()
    {}

    /**
     * Returns a PNG for the requested source time.
     *
     * @param blocking true for export (wait for FFmpeg), false for preview (queue and return null)
     */
    public static Path frame(String projectId, String externalPath, float sourceTime,
                             float fps, int width, int height, boolean blocking)
    {
        if (externalPath == null || externalPath.isBlank())
        {
            return null;
        }

        String raw = externalPath.startsWith("external:")
            ? externalPath.substring("external:".length()) : externalPath;
        File source = new File(raw);

        if (!source.isFile())
        {
            return null;
        }

        int frame = Math.max(0, Math.round(sourceTime * Math.max(1F, fps)));
        int w = Math.max(2, width & ~1);
        int h = Math.max(2, height & ~1);
        String key = key(source, frame, w, h);
        Path cached = cached(key);

        if (cached != null)
        {
            return cached;
        }

        if (!blocking)
        {
            queue(source, sourceTime, fps, w, h, frame, key);

            return null;
        }

        try
        {
            return extract(source, sourceTime, w, h, frame, key);
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not extract video frame from {}: {}", source, e.getMessage());

            return null;
        }
    }

    private static void queue(File source, float sourceTime, float fps, int w, int h, int frame, String key)
    {
        if (!PENDING.add(key))
        {
            return;
        }

        WORKER.submit(() ->
        {
            try
            {
                extract(source, sourceTime, w, h, frame, key);

                /* Prefetch the next two display frames; playback then usually hits the cache. */
                for (int i = 1; i <= 2; i++)
                {
                    int next = frame + i;
                    float time = sourceTime + i / Math.max(1F, fps);
                    String nextKey = key(source, next, w, h);

                    if (cached(nextKey) == null)
                    {
                        extract(source, time, w, h, next, nextKey);
                    }
                }
            }
            catch (Exception e)
            {
                BbsAeAddon.LOGGER.debug("Video frame prefetch failed: {}", e.getMessage());
            }
            finally
            {
                PENDING.remove(key);
            }
        });
    }

    private static Path cached(String key)
    {
        Path path = CACHE.get(key);

        return path != null && Files.isRegularFile(path) ? path : null;
    }

    private static String key(File source, int frame, int w, int h)
    {
        return source.getAbsolutePath() + "|" + source.lastModified() + "|" + frame + "|" + w + "x" + h;
    }

    private static Path extract(File source, float sourceTime, int w, int h, int frame, String key) throws Exception
    {
        String ffmpeg = FFMpegUtils.getFFMPEG();

        if (ffmpeg == null || ffmpeg.isBlank())
        {
            return null;
        }

        Path folder = net.minecraft.client.MinecraftClient.getInstance().runDirectory.toPath()
            .resolve("config").resolve("bbsae").resolve("cache").resolve("video")
            .resolve(Integer.toHexString(key.hashCode()));
        Files.createDirectories(folder);

        Path out = folder.resolve("frame_" + frame + ".png");

        if (!Files.isRegularFile(out))
        {
            ProcessBuilder builder = new ProcessBuilder(ffmpeg, "-y", "-nostdin",
                "-ss", String.format(java.util.Locale.ROOT, "%.3f", Math.max(0F, sourceTime)),
                "-i", source.getAbsolutePath(),
                "-frames:v", "1",
                "-vf", "scale=" + w + ":" + h,
                out.toAbsolutePath().toString());

            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process process = builder.start();

            if (!process.waitFor(30, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0 || !Files.isRegularFile(out))
            {
                return null;
            }
        }

        synchronized (CACHE)
        {
            CACHE.put(key, out);

            while (CACHE.size() > MAX_ENTRIES)
            {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
        }

        return out;
    }
}
