package dev.bbsae.client.export;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.utils.FFMpegUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a video file into a <b>footage layer</b> by extracting its frames with FFmpeg.
 *
 * <h2>Why frames and not a decoder</h2>
 * <p>The compositor's frame function is {@code (document, frame) → image}: it must be able to ask
 * for frame 91 of a clip at any time, backwards, twice, while another layer is being dragged. A
 * streaming decoder cannot answer that; a folder of numbered frames can, and it already has a
 * consumer — the {@code footage} layer type, which formats {@code frame_%04d.png} from
 * {@code sourceTime × fps}.</p>
 *
 * <p>So "import a video" is a conversion, not a playback mode: extract at the composition's frame
 * rate, at the composition's raster (letterboxed, never cropped), capped in duration, and create a
 * layer that points at the result. The export then reads those frames like any other footage.</p>
 *
 * <p>Extraction runs on a worker thread — it takes seconds — and hands the result back through
 * {@link Callback} on the client thread.</p>
 */
public final class CompVideoImport
{
    /** Longest clip accepted, in seconds. A 10-minute export would be 36 000 PNGs. */
    public static final float MAX_SECONDS = 60F;

    private static final Pattern DIMENSIONS = Pattern.compile("(\\d{2,5})x(\\d{2,5})");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private CompVideoImport()
    {}

    public interface Callback
    {
        /**
         * @param pattern project-relative path with a printf index, e.g.
         *                {@code assets/frames/clip/frame_%04d.png}
         * @param frames  number of extracted frames
         * @param width   extracted width in pixels
         * @param height  extracted height in pixels
         * @param fps     frame rate the sequence was extracted at
         */
        void done(String pattern, int frames, int width, int height, float fps);

        void failed(String message);
    }

    public static boolean isRunning()
    {
        return RUNNING.get();
    }

    /**
     * Starts an extraction. Returns false when another import is already running or the input is
     * not a readable file.
     */
    public static boolean start(CompProject project, CompDocument doc, File file, Callback callback)
    {
        if (project == null || doc == null || file == null || !file.isFile())
        {
            callback.failed("请选择一个视频文件");

            return false;
        }

        if (!RUNNING.compareAndSet(false, true))
        {
            callback.failed("已有一个视频正在导入");

            return false;
        }

        final float fps = Math.max(1F, doc.fps);
        final int maxWidth = Math.max(2, doc.width);
        final int maxHeight = Math.max(2, doc.height);
        final String name = baseName(file);

        Thread worker = new Thread(() ->
        {
            try
            {
                Result result = extract(project, file, fps, maxWidth, maxHeight, name);

                callback.done(result.pattern, result.frames, result.width, result.height, fps);
            }
            catch (Exception e)
            {
                BbsAeAddon.LOGGER.error("Could not import video {}", file, e);
                callback.failed(String.valueOf(e.getMessage()));
            }
            finally
            {
                RUNNING.set(false);
            }
        }, "bbsae-video-import");

        worker.setDaemon(true);
        worker.start();

        return true;
    }

    private static final class Result
    {
        String pattern;
        int frames;
        int width;
        int height;
    }

    private static Result extract(CompProject project, File file, float fps, int maxWidth, int maxHeight, String name) throws Exception
    {
        String ffmpeg = FFMpegUtils.getFFMPEG();
        int[] size = probe(ffmpeg, file, maxWidth, maxHeight);
        int width = size[0];
        int height = size[1];

        Path folder = CompStore.assetRoot(project.id).resolve("frames").resolve(name);
        int index = 2;

        /* Never overwrite a previous import: two clips can share a file name ("clip.mp4"). */
        while (Files.isDirectory(folder) && !isEmpty(folder))
        {
            folder = CompStore.assetRoot(project.id).resolve("frames").resolve(name + "_" + index);
            index++;
        }

        Files.createDirectories(folder);

        List<String> args = new ArrayList<>();

        args.add(ffmpeg);
        args.add("-y");
        args.add("-i");
        args.add(file.getAbsolutePath());
        args.add("-vf");
        args.add(String.format(Locale.ROOT, "fps=%s,scale=%d:%d", trimFloat(fps), width, height));
        args.add("-frames:v");
        args.add(String.valueOf(Math.max(1, Math.round(fps * MAX_SECONDS))));
        args.add("-start_number");
        args.add("0");
        args.add(folder.resolve("frame_%04d.png").toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(args);

        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder log = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                if (log.length() < 8192)
                {
                    log.append(line).append('\n');
                }
            }
        }

        int code = process.waitFor();

        int frames = 0;

        try (var stream = Files.list(folder))
        {
            frames = (int) stream.filter((path) -> path.getFileName().toString().endsWith(".png")).count();
        }

        if (code != 0 || frames == 0)
        {
            throw new IllegalStateException("FFmpeg 未能提取视频帧（exit " + code + "）。请确认 FFmpeg 可用，且文件是视频。");
        }

        Result result = new Result();

        result.pattern = "assets/frames/" + folder.getFileName() + "/frame_%04d.png";
        result.frames = frames;
        result.width = width;
        result.height = height;

        return result;
    }

    /**
     * Reads the video's pixel size from FFmpeg's banner and fits it into the composition.
     *
     * <p>Letterbox instead of fill: a portrait clip cropped to a landscape comp loses content that
     * the user can never get back, while black bars are obvious and removable by scaling the layer.</p>
     */
    private static int[] probe(String ffmpeg, File file, int maxWidth, int maxHeight) throws Exception
    {
        ProcessBuilder builder = new ProcessBuilder(ffmpeg, "-hide_banner", "-i", file.getAbsolutePath());

        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder log = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;

            while ((line = reader.readLine()) != null && log.length() < 16384)
            {
                log.append(line).append('\n');
            }
        }

        process.waitFor();

        int sourceWidth = maxWidth;
        int sourceHeight = maxHeight;
        Matcher matcher = DIMENSIONS.matcher(log.toString());

        /* The first "WxH" in the banner belongs to the video stream; "0x1" style hex noise is ruled
         * out by the 2..5 digit bounds. */
        while (matcher.find())
        {
            int w = Integer.parseInt(matcher.group(1));
            int h = Integer.parseInt(matcher.group(2));

            if (w >= 16 && h >= 16)
            {
                sourceWidth = w;
                sourceHeight = h;
                break;
            }
        }

        float scale = Math.min(1F, Math.min(maxWidth / (float) sourceWidth, maxHeight / (float) sourceHeight));
        int width = Math.max(2, Math.round(sourceWidth * scale) & ~1);
        int height = Math.max(2, Math.round(sourceHeight * scale) & ~1);

        return new int[] {width, height};
    }

    private static boolean isEmpty(Path folder)
    {
        try (var stream = Files.list(folder))
        {
            return stream.findAny().isEmpty();
        }
        catch (Exception e)
        {
            return true;
        }
    }

    private static String trimFloat(float value)
    {
        return Math.abs(value - Math.round(value)) < 0.001F
            ? String.valueOf(Math.round(value))
            : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String baseName(File file)
    {
        String name = file.getName();
        int dot = name.lastIndexOf('.');

        if (dot > 0)
        {
            name = name.substring(0, dot);
        }

        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");

        return name.isBlank() ? "clip" : name;
    }
}
