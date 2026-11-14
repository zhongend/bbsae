package dev.bbsae.client.render;

import dev.bbsae.client.export.CompAudioMixer;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The waveform must follow the clip's source window, not the file's first second.
 *
 * <p>The old cache computed peaks over the whole file and the timeline stretched them across the
 * bar, so a clip trimmed into the loud half of a song showed the quiet intro. This test writes a
 * file whose halves differ by 8x and asks for each half separately.</p>
 */
public class CompAudioWaveformTest
{
    private static Path write(Path folder, float seconds) throws Exception
    {
        int rate = CompAudioMixer.SAMPLE_RATE;
        int frames = Math.round(seconds * rate);
        byte[] data = new byte[frames];

        for (int i = 0; i < frames; i++)
        {
            float level = i < frames / 2 ? 0.1F : 0.8F;
            data[i] = (byte) Math.round(128F + level * 127F);
        }

        Path file = folder.resolve("halves.wav");

        WaveWriter.write(file.toFile(), new Wave(1, 1, rate, rate, 1, 8, data));

        return file;
    }

    @Test
    public void aWindowReadsTheRequestedPartOfTheFile(@TempDir Path folder) throws Exception
    {
        Path file = write(folder, 2F);
        float[] quiet = CompAudioWaveform.peaksBlocking(file, 0F, 0.5F, 16);
        float[] loud = CompAudioWaveform.peaksBlocking(file, 1.2F, 0.5F, 16);

        assertNotNull(quiet);
        assertNotNull(loud);

        for (float peak : quiet)
        {
            assertTrue(peak < 0.3F, "the first half is the quiet part");
        }

        for (float peak : loud)
        {
            assertTrue(peak > 0.5F, "the second half is the loud part");
        }
    }

    @Test
    public void aWindowPastTheEndIsEmpty(@TempDir Path folder) throws Exception
    {
        Path file = write(folder, 1F);

        assertTrue(CompAudioWaveform.peaksBlocking(file, 5F, 1F, 16) == null);
    }
}
