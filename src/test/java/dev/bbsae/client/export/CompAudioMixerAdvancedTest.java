package dev.bbsae.client.export;

import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview/export audio parameters: speed must not change pitch, pitch must not change length, and
 * pan/fades must be audible in the mix rather than only in the inspector.
 */
public class CompAudioMixerAdvancedTest
{
    private static final int RATE = CompAudioMixer.SAMPLE_RATE;

    private static Path stereo(Path folder, String name, float seconds, float left, float right) throws Exception
    {
        int frames = Math.round(seconds * RATE);
        byte[] data = new byte[frames * 4];

        for (int i = 0; i < frames; i++)
        {
            short l = (short) Math.round(left * 32767F);
            short r = (short) Math.round(right * 32767F);

            data[i * 4] = (byte) (l & 0xFF);
            data[i * 4 + 1] = (byte) ((l >> 8) & 0xFF);
            data[i * 4 + 2] = (byte) (r & 0xFF);
            data[i * 4 + 3] = (byte) ((r >> 8) & 0xFF);
        }

        Path file = folder.resolve(name);

        WaveWriter.write(file.toFile(), new Wave(1, 2, RATE, RATE * 4, 4, 16, data));

        return file;
    }

    private static double sample(float[] mix, int frame, int channel)
    {
        return mix[frame * CompAudioMixer.CHANNELS + channel];
    }

    @Test
    public void doubleSpeedConsumesTwiceTheSourceButKeepsTheSampleLevel(@TempDir Path folder) throws Exception
    {
        Path file = stereo(folder, "a.wav", 2F, 0.4F, 0.4F);
        CompDocument comp = new CompDocument();
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.sourceDuration = 2F;
        clip.speed = 2F;
        comp.audio.add(clip);

        float[] mix = CompAudioMixer.mix(comp, (ignored) -> file, 0F, 2F, null);

        assertEquals(0.4D, sample(mix, RATE / 2, 0), 0.02D, "speed is time-stretched, not resampled");
        assertEquals(0D, sample(mix, (int) (1.2F * RATE), 0), 0.02D, "a 2x clip ends after one second");
    }

    @Test
    public void pitchShiftKeepsTheClipLength(@TempDir Path folder) throws Exception
    {
        Path file = stereo(folder, "a.wav", 1F, 0.3F, 0.3F);
        CompDocument comp = new CompDocument();
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.sourceDuration = 1F;
        clip.pitch = 12F;
        comp.audio.add(clip);

        float[] mix = CompAudioMixer.mix(comp, (ignored) -> file, 0F, 2F, null);

        assertEquals(0.3D, sample(mix, RATE / 2, 0), 0.05D);
        assertEquals(0D, sample(mix, (int) (1.2F * RATE), 0), 0.01D);
    }

    @Test
    public void hardPanIsReflectedInTheMix(@TempDir Path folder) throws Exception
    {
        Path file = stereo(folder, "a.wav", 1F, 0.5F, 0.5F);
        CompDocument comp = new CompDocument();
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.pan = -1F;
        comp.audio.add(clip);

        float[] mix = CompAudioMixer.mix(comp, (ignored) -> file, 0F, 1F, null);

        assertEquals(0.5D, sample(mix, RATE / 2, 0), 0.02D);
        assertEquals(0D, sample(mix, RATE / 2, 1), 0.02D);
    }

    @Test
    public void timeStretchPreservesDurationMath()
    {
        CompAudioMixer.Source source = new CompAudioMixer.Source(new float[RATE * 2], new float[RATE * 2], RATE * 2);
        CompAudioMixer.Source half = CompAudioMixer.timeStretch(source, 2F);

        assertNotNull(half);
        assertEquals(RATE, half.frames, 2);
        assertTrue(half.duration() > 0.99F && half.duration() < 1.01F);
    }

    @Test
    public void pitchShiftProducesFiniteSamplesAndKeepsFrameCount()
    {
        int frames = RATE / 4;
        float[] left = new float[frames];
        float[] right = new float[frames];

        for (int i = 0; i < frames; i++)
        {
            left[i] = (float) Math.sin(i * 2D * Math.PI * 440D / RATE) * 0.5F;
            right[i] = left[i];
        }

        CompAudioMixer.Source shifted = CompAudioMixer.pitchShift(
            new CompAudioMixer.Source(left, right, frames), 12F);

        assertEquals(frames, shifted.frames);

        for (int i = 0; i < frames; i++)
        {
            assertTrue(Float.isFinite(shifted.left[i]));
            assertTrue(Float.isFinite(shifted.right[i]));
        }

        assertTrue(Math.abs(shifted.left[frames / 2]) > 0.01F);
    }

    @Test
    public void eightBitWaveFilesAreMixedAndTheirWaveformIsNotSilent(@TempDir Path folder) throws Exception
    {
        int frames = RATE / 2;
        byte[] data = new byte[frames];

        for (int i = 0; i < frames; i++)
        {
            data[i] = (byte) Math.round(128F + 0.5F * 127F);
        }

        Path file = folder.resolve("eight.wav");

        WaveWriter.write(file.toFile(), new Wave(1, 1, RATE, RATE, 1, 8, data));

        CompDocument comp = new CompDocument();
        CompAudio clip = new CompAudio("a", "A", "eight.wav");

        clip.sourceDuration = 0.5F;
        comp.audio.add(clip);

        float[] mix = CompAudioMixer.mix(comp, (ignored) -> file, 0F, 0.5F, null);

        assertEquals(0.5D, sample(mix, RATE / 4, 0), 0.03D,
            "8-bit PCM used to be all-zero because convertTo16 underflowed");
        assertNotNull(CompAudioMixer.peakEnvelope(file, 16));
    }

    @Test
    public void probeDurationReadsTheWaveHeaderWithoutDecoding(@TempDir Path folder) throws Exception
    {
        Path file = stereo(folder, "probe.wav", 3.25F, 0.1F, 0.1F);

        assertEquals(3.25F, CompAudioMixer.probeDuration(file), 0.01F);
    }
}
