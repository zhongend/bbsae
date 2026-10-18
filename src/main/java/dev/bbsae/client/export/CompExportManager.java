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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline (non-real-time) renderer for composition-only output.
 *
 * <p>Baked film export already goes through BBS's recorder because the overlay is part of the
 * frame. This manager covers the other half of AE's output story: rendering the comp itself at its
 * own resolution, with alpha, independent of the game window. Frames are written as PNGs on the
 * client thread (one per tick) and then encoded with FFmpeg, which means a slow effect can never
 * desynchronise the output.</p>
 */
public final class CompExportManager
{
    private static Session session;
    private static CompProject queuedProject;
    private static final List<String> LOG = new ArrayList<>();

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

        CompOutputPreset preset = project.getPresetOrFirst(job.presetId);

        if (preset == null)
        {
            preset = CompOutputPreset.reviewH264();
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

            if (!current.framesDone)
            {
                current.renderFrame();

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
        private int frame;
        private boolean framesDone;
        private boolean encoded;
        private boolean finished;
        private boolean cancelled;
        private Process encoder;
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
            this.batcher = new Batcher2D(new DrawContext(MinecraftClient.getInstance(), MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers()));
        }

        private void begin()
        {
            try
            {
                Files.createDirectories(this.framesFolder);

                this.job.status = CompRenderJob.RENDERING;
                this.job.message = "Rendering";
                this.job.totalFrames = this.totalFrames;
                this.job.progress = 0F;
                this.job.outputPath = this.folder.toString();
                this.status = "Rendering " + this.totalFrames + " frames";
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        private void renderFrame()
        {
            float time = this.start + this.frame / this.fps;
            GlFramebuffer fbo = CompRenderer.renderForExport(this.project, this.comp, time, this.batcher);

            if (fbo == null)
            {
                throw new IllegalStateException("Composition produced no frame");
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

            if ("png".equalsIgnoreCase(this.preset.container))
            {
                this.job.status = CompRenderJob.DONE;
                this.job.message = "PNG sequence";
                this.job.progress = 1F;
                this.job.outputPath = this.framesFolder.toString();
                this.status = "PNG sequence complete";
                this.finished = true;
                this.openFolder();
                return;
            }

            String extension = this.preset.extension == null || this.preset.extension.isBlank() ? "mp4" : this.preset.extension;
            Path output = this.folder.resolve(safeName(this.job.outputName) + "." + extension);
            List<String> args = new ArrayList<>();

            args.add(FFMpegUtils.getFFMPEG());
            args.add("-y");
            args.add("-framerate");
            args.add(String.valueOf(this.fps));
            args.add("-i");
            args.add(new File(this.framesFolder.toFile(), "frame_%06d.png").getAbsolutePath());
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
                this.job.message = "Encoding";
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
            return this.totalFrames <= 0 ? 0F : Math.min(1F, this.frame / (float) this.totalFrames);
        }
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
