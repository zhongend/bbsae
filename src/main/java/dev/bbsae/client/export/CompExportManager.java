package dev.bbsae.client.export;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.client.render.FboPool;
import dev.bbsae.client.render.GlFramebuffer;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompOutputPreset;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompRenderJob;
import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.utils.FFMpegUtils;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline (non-real-time) renderer for composition-only output.
 *
 * <p>Baked film export already goes through BBS's recorder because the overlay is part of the
 * frame. This manager covers the other half of AE's output story: rendering the comp itself at its
 * own resolution, independent of the game window. Frames are written as PNGs on the client thread
 * (one per tick) and then encoded with FFmpeg, which means a slow effect can never desynchronise
 * the output.</p>
 *
 * <h2>Audio</h2>
 * <p>The composition's audio track is mixed by {@link CompAudioMixer} into one 48 kHz stereo WAV
 * before encoding, and that WAV is muxed into the video (AAC by default, PCM for ProRes). An
 * audio-only output module writes the same WAV (or encodes it to MP3/M4A) without rendering a
 * single frame — audio export must not require a GPU pass per frame.</p>
 */
public final class CompExportManager
{
    private static Session session;
    private static CompProject queuedProject;
    private static final List<String> LOG = new ArrayList<>();

    /** Wall-clock slice of one client tick spent on export frames (50 ms tick at 20 TPS). */
    private static final long FRAME_BUDGET_MS = 22L;
    /** Upper bound per tick, so a 3 ms frame cannot turn into a 30-frame stall. */
    private static final int MAX_FRAMES_PER_TICK = 6;

    private CompExportManager()
    {}

    public static boolean isBusy()
    {
        return session != null;
    }

    public static float progress()
    {
        return session == null ? 0F : session.progress();
    }

    public static String status()
    {
        return session == null ? "Idle" : session.status;
    }

    public static List<String> log()
    {
        return LOG;
    }

    public static synchronized boolean start(CompProject project, CompRenderJob job)
    {
        if (session != null || project == null || job == null)
        {
            return false;
        }

        CompDocument comp = project.getComp(job.compId);

        if (comp == null)
        {
            return false;
        }

        CompOutputPreset preset = project.getPreset(job.presetId);

        if (preset == null)
        {
            /* Deterministic fallback. getPresetOrFirst() used to return "whatever is first", which
             * turned a missing audio module into a full video render (or the other way round)
             * without a word. The job's own id decides the kind. */
            preset = job.presetId != null && job.presetId.startsWith("audio")
                ? CompOutputPreset.audioWav()
                : CompOutputPreset.reviewH264();
        }

        session = new Session(project, comp, preset, job);

        try
        {
            session.begin();
        }
        catch (RuntimeException e)
        {
            job.status = CompRenderJob.FAILED;
            job.message = String.valueOf(e.getMessage());
            session = null;

            BbsAeAddon.LOGGER.error("Could not start composition export", e);
            return false;
        }

        return true;
    }

    public static synchronized void cancel()
    {
        queuedProject = null;

        if (session != null)
        {
            session.cancelled = true;
            session.stopEncoder();
            session.job.status = CompRenderJob.CANCELLED;
            session.job.message = "Cancelled";
            session = null;
        }
    }

    /** Runs every queued job for a project serially. */
    public static synchronized void startQueue(CompProject project)
    {
        if (project == null || session != null)
        {
            return;
        }

        queuedProject = project;
        startNext();
    }

    private static void startNext()
    {
        if (queuedProject == null)
        {
            return;
        }

        for (CompRenderJob job : queuedProject.renderQueue)
        {
            if (job.status == CompRenderJob.QUEUED)
            {
                if (start(queuedProject, job))
                {
                    return;
                }

                job.status = CompRenderJob.FAILED;
                job.message = "Could not start job";
            }
        }

        queuedProject = null;
    }

    /** Called once per client tick from the addon entry point. */
    public static void tick()
    {
        Session current = session;

        if (current == null)
        {
            if (queuedProject != null)
            {
                startNext();
            }

            return;
        }

        try
        {
            if (current.cancelled)
            {
                session = null;
                return;
            }

            /* The audio mix runs once, before any frame: it is the cheapest step and a failure
             * there (missing file) must not waste a full video render. */
            if (!current.prepared)
            {
                current.prepareAudio();
                current.prepared = true;

                if (current.audioOnly)
                {
                    current.framesDone = true;
                    current.job.status = CompRenderJob.ENCODING;
                    current.job.message = "Encoding audio";
                }

                return;
            }

            if (!current.framesDone)
            {
                current.renderFrames();

                if (current.frame >= current.totalFrames)
                {
                    current.framesDone = true;
                    current.job.status = CompRenderJob.ENCODING;
                    current.job.message = "Encoding";
                }

                return;
            }

            if (!current.encoded)
            {
                current.encode();
                return;
            }

            current.pollEncoder();

            if (current.finished)
            {
                session = null;
            }
        }
        catch (Throwable e)
        {
            BbsAeAddon.LOGGER.error("Composition export failed", e);
            current.job.status = CompRenderJob.FAILED;
            current.job.message = String.valueOf(e.getMessage());
            current.status = "Failed: " + e.getMessage();
            current.finished = true;
            session = null;
        }
    }

    private static final class Session
    {
        private final CompProject project;
        private final CompDocument comp;
        private final CompOutputPreset preset;
        private final CompRenderJob job;
        private final int width;
        private final int height;
        private final float fps;
        private final float start;
        private final float end;
        private final int totalFrames;
        private final Path folder;
        private final Path framesFolder;
        private final boolean audioOnly;
        private int frame;
        private boolean prepared;
        private boolean framesDone;
        private boolean encoded;
        private boolean finished;
        private boolean cancelled;
        private Path audioFile;
        private int audioFrames;
        private Process encoder;
        /**
         * Raw frames straight into FFmpeg's stdin instead of a PNG per frame.
         *
         * <p>PNG is the slowest part of an export: encoding 1080p takes ~30-60 ms and writing it
         * another few milliseconds, per frame, against a composition that may render in 5 ms. The
         * pipe removes both (no ImageIO, no per-frame disk write) and keeps the encoder's input at
         * exactly the composition raster, so no resampling can sneak in. PNG stays for the sequence
         * module, for resized presets, and when the user asked to keep the frames.</p>
         */
        private final boolean pipeMode;
        private java.io.OutputStream pipe;
        private String status = "Preparing";
        private final Batcher2D batcher;

        private Session(CompProject project, CompDocument comp, CompOutputPreset preset, CompRenderJob job)
        {
            this.project = project;
            this.comp = comp;
            this.preset = preset;
            this.job = job;
            this.fps = preset.fps > 0 ? preset.fps : comp.fps;
            this.width = preset.width > 0 ? preset.width : Math.max(1, Math.round(comp.width * Math.max(0.01F, preset.scale)));
            this.height = preset.height > 0 ? preset.height : Math.max(1, Math.round(comp.height * Math.max(0.01F, preset.scale)));
            this.start = Math.max(0F, job.rangeStart);
            this.end = job.rangeEnd > 0F ? Math.min(job.rangeEnd, comp.duration) : comp.duration;
            this.totalFrames = Math.max(1, Math.round((this.end - this.start) * this.fps));
            this.folder = CompStore.exportRoot(project.id).resolve(safeName(job.outputName));
            this.framesFolder = this.folder.resolve("frames");
            this.audioOnly = "wav".equalsIgnoreCase(preset.container) || "audio".equalsIgnoreCase(preset.kind);
            this.pipeMode = usesPipe(preset, this.audioOnly, this.width, this.height, comp.width, comp.height);
            this.batcher = new Batcher2D(new DrawContext(MinecraftClient.getInstance(), MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers()));
        }

        private void begin()
        {
            try
            {
                if (!this.audioOnly && !this.pipeMode)
                {
                    Files.createDirectories(this.framesFolder);
                }

                this.job.status = CompRenderJob.RENDERING;
                this.job.message = this.audioOnly ? "Mixing audio" : "Rendering";
                this.job.totalFrames = this.audioOnly ? 0 : this.totalFrames;
                this.job.progress = 0F;
                this.job.outputPath = this.folder.toString();
                this.status = this.audioOnly
                    ? "Mixing audio"
                    : "Rendering " + this.totalFrames + " frames" + (this.pipeMode ? " (pipe)" : "");

                if (this.pipeMode)
                {
                    this.startPipe();
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        /** Mixes the composition's audio track to {@code audio.wav} next to the frames. */
        private void prepareAudio()
        {
            if (this.comp.audio == null || this.comp.audio.isEmpty())
            {
                return;
            }

            try
            {
                Files.createDirectories(this.folder);

                float[] samples = CompAudioMixer.mix(
                    this.comp,
                    (path) -> CompAudioMixer.resolver(CompStore.assetRoot(this.project.id)).apply(path),
                    this.start,
                    this.end,
                    (message) ->
                    {
                        LOG.add(message);
                        BbsAeAddon.LOGGER.warn("[bbsae] {}", message);
                    }
                );

                if (samples.length == 0)
                {
                    return;
                }

                Path wav = this.folder.resolve("audio.wav");

                CompAudioMixer.writeWav(wav, samples);
                this.audioFile = wav;
                this.audioFrames = samples.length / CompAudioMixer.CHANNELS;
                this.status = "Audio mixed: " + (this.audioFrames / (float) CompAudioMixer.SAMPLE_RATE) + "s";
            }
            catch (Exception e)
            {
                LOG.add("音频混音失败：" + e.getMessage());
                BbsAeAddon.LOGGER.error("Could not mix composition audio", e);
            }
        }

        /**
         * Renders as many frames as fit in {@link #FRAME_BUDGET_MS} of this client tick.
         *
         * <h2>Why not one frame per tick</h2>
         * <p>One frame per tick caps an export at 20 fps of wall-clock time no matter how fast the
         * composition is — a 12-second 60 fps comp took 36 seconds to write even when each frame
         * rendered in 3 ms. The GL work cannot move to another thread (the context belongs to the
         * render thread), so the "render farm" is a <b>budget</b>: spend most of the tick producing
         * frames, keep a slice for the game, and stop after {@link #MAX_FRAMES_PER_TICK} so a slow
         * composition cannot freeze the client either.</p>
         */
        private void renderFrames()
        {
            long deadline = System.nanoTime() + FRAME_BUDGET_MS * 1_000_000L;
            int guard = 0;

            do
            {
                this.renderFrame();
            }
            while (this.frame < this.totalFrames
                && ++guard < MAX_FRAMES_PER_TICK
                && System.nanoTime() < deadline);
        }

        private void renderFrame()
        {
            float time = this.start + this.frame / this.fps;
            GlFramebuffer fbo = CompRenderer.renderForExport(this.project, this.comp, time, this.batcher, this.preset.alpha);

            if (fbo == null)
            {
                throw new IllegalStateException("Composition produced no frame");
            }

            if (this.pipeMode)
            {
                byte[] rgba = fbo.readTopDownRgba();

                FboPool.release(fbo);
                this.writePipe(rgba);
                this.frame += 1;
                this.job.progress = this.frame / (float) this.totalFrames;
                this.status = "Rendering frame " + this.frame + " / " + this.totalFrames + " (pipe)";

                return;
            }

            int[] pixels = fbo.readTopDown();

            FboPool.release(fbo);

            BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);

            if (this.width == this.comp.width && this.height == this.comp.height)
            {
                image.setRGB(0, 0, this.width, this.height, pixels, 0, this.width);
            }
            else
            {
                BufferedImage source = new BufferedImage(this.comp.width, this.comp.height, BufferedImage.TYPE_INT_ARGB);

                source.setRGB(0, 0, source.getWidth(), source.getHeight(), pixels, 0, source.getWidth());

                java.awt.Graphics2D g = image.createGraphics();

                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source, 0, 0, this.width, this.height, null);
                g.dispose();
            }

            try
            {
                ImageIO.write(image, "png", this.framesFolder.resolve(String.format(Locale.ROOT, "frame_%06d.png", this.frame)).toFile());
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }

            this.frame += 1;
            this.job.progress = this.frame / (float) this.totalFrames;
            this.status = "Rendering frame " + this.frame + " / " + this.totalFrames;
        }

        private void encode()
        {
            this.encoded = true;

            if (this.pipeMode && this.encoder != null)
            {
                /* Closing stdin is what tells FFmpeg the stream ended; the process then writes the
                 * container and exits. pollEncoder() reports the result. */
                try
                {
                    this.pipe.close();
                }
                catch (IOException ignored)
                {}

                this.pipe = null;
                this.job.status = CompRenderJob.ENCODING;
                this.job.message = this.audioFile != null ? "Encoding video + audio" : "Encoding";
                this.status = "Encoding with FFmpeg (piped " + this.totalFrames + " frames)";

                return;
            }

            if ("png".equalsIgnoreCase(this.preset.container))
            {
                this.job.status = CompRenderJob.DONE;
                this.job.message = this.audioFile != null ? "PNG sequence + WAV" : "PNG sequence";
                this.job.progress = 1F;
                this.job.outputPath = this.framesFolder.toString();
                this.status = "PNG sequence complete";
                this.finished = true;
                this.openFolder();
                return;
            }

            String extension = this.preset.extension == null || this.preset.extension.isBlank()
                ? (this.preset.container == null ? "mp4" : this.preset.container)
                : this.preset.extension;
            Path output = this.folder.resolve(safeName(this.job.outputName) + "." + extension);

            /* Audio-only modules with no audio track have nothing to do; succeeding silently with
             * an empty file would be worse than saying so. */
            if (this.audioOnly && this.audioFile == null)
            {
                this.job.status = CompRenderJob.FAILED;
                this.job.message = "No audio track";
                this.status = "这条合成没有可导出的音频（在“合成”页添加音频）";
                this.finished = true;
                return;
            }

            /* WAV pass-through: a straight copy keeps the mix bit-exact and needs no FFmpeg. */
            if ("wav".equalsIgnoreCase(extension) && this.audioFile != null)
            {
                try
                {
                    Files.copy(this.audioFile, output, StandardCopyOption.REPLACE_EXISTING);

                    this.job.status = CompRenderJob.DONE;
                    this.job.message = "WAV";
                    this.job.progress = 1F;
                    this.job.outputPath = output.toString();
                    this.status = "Done: " + output.getFileName();
                    this.finished = true;
                    this.openFolder();
                }
                catch (IOException e)
                {
                    this.job.status = CompRenderJob.FAILED;
                    this.job.message = String.valueOf(e.getMessage());
                    this.status = "Audio export failed: " + e.getMessage();
                    this.finished = true;
                }

                return;
            }

            List<String> args = new ArrayList<>();

            args.add(FFMpegUtils.getFFMPEG());
            args.add("-y");

            if (!this.audioOnly)
            {
                args.add("-framerate");
                args.add(String.valueOf(this.fps));
                args.add("-i");
                args.add(new File(this.framesFolder.toFile(), "frame_%06d.png").getAbsolutePath());
            }

            if (this.audioFile != null)
            {
                args.add("-i");
                args.add(this.audioFile.toAbsolutePath().toString());
            }

            if (!this.audioOnly)
            {
                args.add("-c:v");
                args.add(this.preset.codec == null || this.preset.codec.isBlank() ? "libx264" : this.preset.codec);

                if (this.preset.crf > 0 && ("libx264".equals(this.preset.codec) || "libx265".equals(this.preset.codec)))
                {
                    args.add("-crf");
                    args.add(String.valueOf(this.preset.crf));
                }

                if (this.preset.bitrate != null && !this.preset.bitrate.isBlank())
                {
                    args.add("-b:v");
                    args.add(this.preset.bitrate);
                }

                if (this.preset.pixelFormat != null && !this.preset.pixelFormat.isBlank())
                {
                    args.add("-pix_fmt");
                    args.add(this.preset.pixelFormat);
                }
            }

            if (this.audioFile != null)
            {
                args.add("-c:a");
                args.add(this.preset.audioCodec == null || this.preset.audioCodec.isBlank() ? "aac" : this.preset.audioCodec);

                if (this.preset.audioBitrate != null && !this.preset.audioBitrate.isBlank())
                {
                    args.add("-b:a");
                    args.add(this.preset.audioBitrate);
                }

                if (!this.audioOnly)
                {
                    /* Pad, then cut. The mix is generated from the same range as the frames, but
                     * frame rounding (N frames at fps) and sample rounding (48 kHz) never agree to
                     * the last millisecond. Without apad, -shortest would drop the final video frame
                     * whenever the audio came out 20 ms short — an off-by-one-frame at the end of
                     * every export. */
                    args.add("-af");
                    args.add("apad");
                    args.add("-shortest");
                }
            }

            args.add(output.toAbsolutePath().toString());

            try
            {
                ProcessBuilder builder = new ProcessBuilder(args);
                File log = this.folder.resolve("ffmpeg.log").toFile();

                builder.directory(this.folder.toFile());
                builder.redirectErrorStream(true);
                builder.redirectOutput(log);

                this.encoder = builder.start();
                this.job.status = CompRenderJob.ENCODING;
                this.job.message = this.audioFile != null ? "Encoding video + audio" : "Encoding";
                this.status = "Encoding with FFmpeg";
            }
            catch (IOException e)
            {
                this.job.status = CompRenderJob.FAILED;
                this.job.message = String.valueOf(e.getMessage());
                this.status = "Encode failed: " + e.getMessage();
                this.finished = true;
            }
        }

        /**
         * Spawns FFmpeg with the raw-frame pipe as input 0.
         *
         * <p>The audio (input 1) is a normal file because the mix is produced once, before the
         * frames; keeping it out of the pipe avoids interleaving two streams by hand.</p>
         */
        private void startPipe()
        {
            List<String> args = new ArrayList<>();

            args.add(FFMpegUtils.getFFMPEG());
            args.add("-y");
            args.add("-f");
            args.add("rawvideo");
            args.add("-pixel_format");
            args.add("rgba");
            args.add("-video_size");
            args.add(this.width + "x" + this.height);
            args.add("-framerate");
            args.add(String.valueOf(this.fps));
            args.add("-i");
            args.add("pipe:0");

            if (this.audioFile != null)
            {
                args.add("-i");
                args.add(this.audioFile.toAbsolutePath().toString());
            }

            args.add("-c:v");
            args.add(this.preset.codec == null || this.preset.codec.isBlank() ? "libx264" : this.preset.codec);

            if (this.preset.crf > 0 && ("libx264".equals(this.preset.codec) || "libx265".equals(this.preset.codec)))
            {
                args.add("-crf");
                args.add(String.valueOf(this.preset.crf));
            }

            if (this.preset.bitrate != null && !this.preset.bitrate.isBlank())
            {
                args.add("-b:v");
                args.add(this.preset.bitrate);
            }

            if (this.preset.pixelFormat != null && !this.preset.pixelFormat.isBlank())
            {
                args.add("-pix_fmt");
                args.add(this.preset.pixelFormat);
            }

            if (this.audioFile != null)
            {
                args.add("-c:a");
                args.add(this.preset.audioCodec == null || this.preset.audioCodec.isBlank() ? "aac" : this.preset.audioCodec);

                if (this.preset.audioBitrate != null && !this.preset.audioBitrate.isBlank())
                {
                    args.add("-b:a");
                    args.add(this.preset.audioBitrate);
                }

                args.add("-af");
                args.add("apad");
                args.add("-shortest");
            }

            String extension = this.preset.extension == null || this.preset.extension.isBlank()
                ? (this.preset.container == null ? "mp4" : this.preset.container)
                : this.preset.extension;

            args.add(this.folder.resolve(safeName(this.job.outputName) + "." + extension).toAbsolutePath().toString());

            try
            {
                Files.createDirectories(this.folder);

                ProcessBuilder builder = new ProcessBuilder(args);
                File log = this.folder.resolve("ffmpeg.log").toFile();

                builder.directory(this.folder.toFile());
                builder.redirectErrorStream(true);
                builder.redirectOutput(log);

                this.encoder = builder.start();
                this.pipe = new java.io.BufferedOutputStream(this.encoder.getOutputStream(), 1 << 20);
            }
            catch (IOException e)
            {
                this.job.status = CompRenderJob.FAILED;
                this.job.message = String.valueOf(e.getMessage());
                this.status = "Could not start FFmpeg: " + e.getMessage();
                this.finished = true;
            }
        }

        private void writePipe(byte[] rgba)
        {
            if (this.pipe == null)
            {
                throw new IllegalStateException("FFmpeg pipe is closed");
            }

            try
            {
                this.pipe.write(rgba);
            }
            catch (IOException e)
            {
                throw new RuntimeException("FFmpeg stopped accepting frames: " + e.getMessage(), e);
            }
        }

        /** Polls the asynchronous FFmpeg process once per client tick. */
        private void pollEncoder()
        {
            if (this.encoder == null || this.encoder.isAlive())
            {
                return;
            }

            int code = this.encoder.exitValue();

            this.encoder = null;

            if (code == 0)
            {
                String extension = this.preset.extension == null || this.preset.extension.isBlank() ? "mp4" : this.preset.extension;
                Path output = this.folder.resolve(safeName(this.job.outputName) + "." + extension);

                this.job.status = CompRenderJob.DONE;
                this.job.message = "Done";
                this.job.progress = 1F;
                this.job.outputPath = output.toString();
                this.status = "Done: " + output.getFileName();
                this.finished = true;

                if (!this.preset.keepFrames)
                {
                    this.deleteFrames();
                }

                this.openFolder();
            }
            else
            {
                this.job.status = CompRenderJob.FAILED;
                this.job.message = "FFmpeg exit " + code;
                this.status = "FFmpeg failed (" + code + "); see " + this.folder.resolve("ffmpeg.log");
                this.finished = true;
            }
        }

        private void stopEncoder()
        {
            if (this.pipe != null)
            {
                try
                {
                    this.pipe.close();
                }
                catch (IOException ignored)
                {}

                this.pipe = null;
            }

            if (this.encoder != null)
            {
                this.encoder.destroy();
                this.encoder = null;
            }
        }

        private void deleteFrames()
        {
            try (var stream = Files.list(this.framesFolder))
            {
                stream.forEach((path) ->
                {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException ignored)
                    {}
                });
            }
            catch (IOException ignored)
            {}
        }

        private void openFolder()
        {
            if (!"open_folder".equals(this.preset.postAction))
            {
                return;
            }

            try
            {
                if (java.awt.Desktop.isDesktopSupported())
                {
                    java.awt.Desktop.getDesktop().open(this.folder.toFile());
                }
            }
            catch (Exception ignored)
            {}
        }

        private float progress()
        {
            return this.totalFrames <= 0 ? (this.finished ? 1F : 0F) : Math.min(1F, this.frame / (float) this.totalFrames);
        }
    }

    /**
     * Whether this job can stream raw frames into FFmpeg.
     *
     * <p>Three conditions, each with a reason:</p>
     * <ul>
     *   <li><b>video, not audio/PNG</b> — those modules either render no frames or need files;</li>
     *   <li><b>no kept frames</b> — "keep the frames" is a request for PNGs on disk;</li>
     *   <li><b>output size == composition size</b> — the pipe carries the composition raster
     *       as-is, so a resized preset would need a resampler in the middle, and doing that
     *       silently would break the "export = preview" promise. Resized presets keep the PNG
     *       path (and its bilinear scale).</li>
     * </ul>
     */
    static boolean usesPipe(CompOutputPreset preset, boolean audioOnly, int width, int height, int compWidth, int compHeight)
    {
        if (preset == null || audioOnly)
        {
            return false;
        }

        if ("png".equalsIgnoreCase(preset.container) || preset.keepFrames)
        {
            return false;
        }

        return width == Math.max(1, compWidth) && height == Math.max(1, compHeight);
    }

    private static String safeName(String name)
    {
        if (name == null || name.isBlank())
        {
            return "comp";
        }

        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
