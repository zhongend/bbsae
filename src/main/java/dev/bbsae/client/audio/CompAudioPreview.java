package dev.bbsae.client.audio;

import dev.bbsae.client.export.CompAudioMixer;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.Waveform;
import mchorse.bbs_mod.resources.Link;

import java.nio.file.Path;

/**
 * One-shot audition for an asset-browser audio file.
 *
 * <p>Uses the same decoder as the composition (WAV/OGG natively, compressed formats through
 * FFmpeg raw PCM), so the preview is exactly what the timeline/export will hear. A second click
 * stops the previous source before starting the new one.</p>
 */
public final class CompAudioPreview
{
    private static final Link LINK = new Link("bbsae", "asset_preview");

    private static SoundPlayer player;
    private static SoundBuffer buffer;
    private static Path playingFile;

    private CompAudioPreview()
    {}

    public static boolean play(Path file)
    {
        stop();

        if (file == null)
        {
            return false;
        }

        try
        {
            Wave wave = CompAudioMixer.readWave(file);

            if (wave == null)
            {
                return false;
            }

            Waveform waveform = new Waveform();
            waveform.populate(wave, 512, 128);

            buffer = new SoundBuffer(LINK, wave, waveform);
            player = new SoundPlayer(buffer);
            player.setRelative(true);
            player.setVolume(1F);
            player.play();
            playingFile = file.toAbsolutePath();

            return true;
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not preview audio {}", file, e);
            stop();

            return false;
        }
    }

    public static boolean isPlaying()
    {
        return player != null && player.isPlaying();
    }

    public static boolean isPlaying(Path file)
    {
        return file != null && playingFile != null && isPlaying()
            && playingFile.equals(file.toAbsolutePath());
    }

    public static void stop()
    {
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
