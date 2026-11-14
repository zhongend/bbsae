package dev.bbsae.client.audio;

import dev.bbsae.client.export.CompAudioMixer;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.Waveform;
import mchorse.bbs_mod.resources.Link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot audition for an asset-browser audio file.
 *
 * <p>Uses the same decoder as the composition (WAV/OGG natively, compressed formats through
 * FFmpeg raw PCM), so the preview is exactly what the timeline/export will hear. A second click
 * stops the previous source before starting the new one.</p>
 *
 * <p>Decoding happens on a worker thread: audited MP3/FLAC files can take hundreds of milliseconds
 * (or seconds for a long track), and doing that inside a click handler froze the whole editor. Only
 * the OpenAL object creation and playback touch the client thread.</p>
 */
public final class CompAudioPreview
{
    private static final Link LINK = new Link("bbsae", "asset_preview");

    private static SoundPlayer player;
    private static SoundBuffer buffer;
    private static Path playingFile;
    private static Path pendingFile;
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private CompAudioPreview()
    {}

    public static boolean play(Path file)
    {
        stop();

        if (file == null || !Files.isRegularFile(file))
        {
            return false;
        }

        final int ticket = GENERATION.incrementAndGet();

        pendingFile = file.toAbsolutePath();

        Thread worker = new Thread(() ->
        {
            try
            {
                Wave wave = CompAudioMixer.readWave(file);

                if (wave == null)
                {
                    return;
                }

                net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                {
                    if (ticket != GENERATION.get() || pendingFile == null)
                    {
                        return;
                    }

                    try
                    {
                        Waveform waveform = new Waveform();

                        waveform.populate(wave, 512, 128);

                        buffer = new SoundBuffer(LINK, wave, waveform);
                        player = new SoundPlayer(buffer);
                        player.setRelative(true);
                        player.setVolume(1F);
                        player.play();
                        playingFile = pendingFile;
                    }
                    catch (Exception e)
                    {
                        dev.bbsae.BbsAeAddon.LOGGER.warn("Could not preview audio {}", file, e);
                        stop();
                    }
                    finally
                    {
                        pendingFile = null;
                    }
                });
            }
            catch (Exception e)
            {
                dev.bbsae.BbsAeAddon.LOGGER.warn("Could not decode audio for preview {}", file, e);

                if (ticket == GENERATION.get())
                {
                    pendingFile = null;
                }
            }
        }, "bbsae-audio-preview");

        worker.setDaemon(true);
        worker.start();

        return true;
    }

    public static boolean isPlaying()
    {
        return pendingFile != null || (player != null && player.isPlaying());
    }

    public static boolean isPlaying(Path file)
    {
        if (file == null)
        {
            return false;
        }

        Path absolute = file.toAbsolutePath();

        return (pendingFile != null && pendingFile.equals(absolute))
            || (playingFile != null && playingFile.equals(absolute) && isPlaying());
    }

    public static void stop()
    {
        GENERATION.incrementAndGet();
        pendingFile = null;

        if (player != null)
        {
            try
            {
                player.stop();
                player.delete();
            }
            catch (Exception ignored)
            {}

            player = null;
        }

        if (buffer != null)
        {
            try
            {
                buffer.delete();
            }
            catch (Exception ignored)
            {}

            buffer = null;
        }

        playingFile = null;
    }
}
