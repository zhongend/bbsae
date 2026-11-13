package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.utils.FFMpegUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * On-demand frame extraction for externally linked video layers.
 *
 * <p>The editor needs random access ("frame 91", backwards, twice), which a streaming decoder does
 * not provide. This cache asks FFmpeg for one still per requested frame and stores it outside the
 * project. The source file stays where the user put it; only the small frame cache is local.</p>
 */
public final class CompVideoFrames
{
    private static final Map<String, Path> CACHE = new LinkedHashMap<>(32, 0.75F, true);
    private static final int MAX_ENTRIES = 96;

    private CompVideoFrames()
    {}

    /** Returns a PNG for the requested source time, or null when extraction failed. */
    public static synchronized Path frame(String projectId, String externalPath, float sourceTime,
                                          float fps, int width, int height)
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
        String key = source.getAbsolutePath() + "|" + source.lastModified() + "|" + frame + "|" + w + "x" + h;
        Path cached = CACHE.get(key);

        if (cached != null && Files.isRegularFile(cached))
        {
            return cached;
        }

        try
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

            CACHE.put(key, out);

            while (CACHE.size() > MAX_ENTRIES)
            {
                String oldest = CACHE.keySet().iterator().next();
                CACHE.remove(oldest);
            }

            return out;
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not extract video frame from {}: {}", source, e.getMessage());

            return null;
        }
    }
}
