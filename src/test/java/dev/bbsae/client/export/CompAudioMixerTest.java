package dev.bbsae.client.export;

import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audio mixdown.
 *
 * <p>This is the pure part of audio export: given a composition and a time range, the mixer must
 * place every clip at its in-point, apply gain and fades, and leave the rest silent. Getting this
 * wrong is not visible in the UI — the export simply has the wrong sound — so it is pinned here
 * instead of by hand.</p>
 */
public class CompAudioMixerTest
{
    private static final int RATE = CompAudioMixer.SAMPLE_RATE;

    private static Path write(Path folder, String name, float seconds, float firstHalf, float secondHalf) throws Exception
    {
        int frames = Math.round(seconds * RATE);
        byte[] data = new byte[frames * 2];

        for (int i = 0; i < frames; i++)
        {
            float level = i < frames / 2 ? firstHalf : secondHalf;
            short value = (short) Math.round(level * 32767F);

            data[i * 2] = (byte) (value & 0xFF);
            data[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }

        Path file = folder.resolve(name);

        WaveWriter.write(file.toFile(), new Wave(1, 1, RATE, RATE * 2, 2, 16, data));

        return file;
    }

    private static Path tone(Path folder, String name, float seconds, float level) throws Exception
    {
        return write(folder, name, seconds, level, level);
    }

    private static void writeAscii(byte[] target, int offset, String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            target[offset + i] = (byte) text.charAt(i);
        }
    }

    private static void writeInt(byte[] target, int offset, int value)
    {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShort(byte[] target, int offset, int value)
    {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static double sample(float[] mix, int frame, int channel)
    {
        return mix[frame * CompAudioMixer.CHANNELS + channel];
    }

    @Test
    public void clipsLandAtTheirInPointAndRespectGain(@TempDir Path folder) throws Exception
    {
        Path file = tone(folder, "a.wav", 1F, 0.5F);
        CompDocument comp = new CompDocument();
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.start = 1F;
        clip.gain = 0.5F;
        comp.audio.add(clip);

        float[] mix = CompAudioMixer.mix(comp, (path) -> file, 0F, 3F, null);

        assertEquals(3D * RATE * CompAudioMixer.CHANNELS, mix.length, 1D, "mix length is the export range");

        /* Before the in-point: silence. */
        assertEquals(0D, sample(mix, RATE / 2, 0), 0.001D);

        /* Inside: source level 0.5 x gain 0.5, on both channels (a mono source is duplicated). */
        assertEquals(0.25D, sample(mix, (int) (1.5F * RATE), 0), 0.01D);
        assertEquals(0.25D, sample(mix, (int) (1.5F * RATE), 1), 0.01D);

        /* After the clip's natural end (1s long, started at 1s): silence again. */
        assertEquals(0D, sample(mix, (int) (2.5F * RATE), 0), 0.001D);
    }

    @Test
    public void overlappingClipsSum(@TempDir Path folder) throws Exception
    {
        Path a = tone(folder, "a.wav", 2F, 0.2F);
        Path b = tone(folder, "b.wav", 2F, 0.3F);
        CompDocument comp = new CompDocument();

        CompAudio first = new CompAudio("a", "A", "a.wav");
        CompAudio second = new CompAudio("b", "B", "b.wav");

        second.start = 0.5F;
        comp.audio.add(first);
        comp.audio.add(second);

        float[] mix = CompAudioMixer.mix(comp, (path) -> "b.wav".equals(path) ? b : a, 0F, 2F, null);

        assertEquals(0.2D, sample(mix, RATE / 4, 0), 0.01D, "only the first clip is playing at 0.25s");
        assertEquals(0.5D, sample(mix, (int) (0.75F * RATE), 0), 0.01D, "both clips overlap at 0.75s");
    }

    @Test
    public void mutedClipsContributeNothing(@TempDir Path folder) throws Exception
    {
        Path file = tone(folder, "a.wav", 1F, 0.5F);
        CompDocument comp = new CompDocument();
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.muted = true;
        comp.audio.add(clip);

        float[] mix = CompAudioMixer.mix(comp, (path) -> file, 0F, 1F, null);

        assertEquals(0, mix.length, "a fully muted track must not produce a buffer");
    }

    /**
     * Exporting 2s…3s must play the file's <em>third</em> second, not its first. The source here is
     * silent for its first half, so an offset bug reads as silence instead of sound.
     */
    @Test
    public void theMixIsCutToTheExportRange(@TempDir Path folder) throws Exception
    {
        Path file = write(folder, "a.wav", 4F, 0F, 0.5F);
        CompDocument comp = new CompDocument();
        comp.audio.add(new CompAudio("a", "A", "a.wav"));

        float[] mix = CompAudioMixer.mix(comp, (path) -> file, 2F, 3F, null);

        assertEquals(1D * RATE * CompAudioMixer.CHANNELS, mix.length, 1D);
        assertEquals(0.5D, sample(mix, RATE / 2, 0), 0.01D, "the export range starts inside the clip");

        /* And a range that lands in the silent half stays silent. */
        float[] early = CompAudioMixer.mix(comp, (path) -> file, 0.5F, 1.5F, null);

        assertEquals(0D, sample(early, RATE / 4, 0), 0.01D);
    }

    @Test
    public void aMissingFileIsReportedNotFatal(@TempDir Path folder) throws Exception
    {
        CompDocument comp = new CompDocument();
        StringBuilder log = new StringBuilder();

        comp.audio.add(new CompAudio("a", "A", "missing.wav"));

        float[] mix = CompAudioMixer.mix(comp, (path) -> folder.resolve(path), 0F, 1F, log::append);

        assertEquals(0, mix.length);
        assertTrue(log.toString().contains("missing.wav"), "the render log must name the file");
    }

    @Test
    public void pcmConversionClipsToFullScale()
    {
        byte[] pcm = CompAudioMixer.toPcm16(new float[] {2F, -2F, 0F});

        assertEquals((short) 32767, (short) ((pcm[1] & 0xFF) << 8 | (pcm[0] & 0xFF)));
        assertEquals((short) -32767, (short) ((pcm[3] & 0xFF) << 8 | (pcm[2] & 0xFF)));
        assertEquals((short) 0, (short) ((pcm[5] & 0xFF) << 8 | (pcm[4] & 0xFF)));
    }

    /**
     * A WAV that ends right after its data chunk (what python's {@code wave} module writes) has no
     * chunk terminator, and BBS's reader throws EOF there. The mixer must fall back to its own RIFF
     * parser instead of dropping the clip.
     */
    @Test
    public void aWavWithoutATrailingChunkStillMixes(@TempDir Path folder) throws Exception
    {
        int frames = RATE / 4;
        int dataBytes = frames * 2 * 2;
        byte[] file = new byte[44 + dataBytes];

        writeAscii(file, 0, "RIFF");
        writeInt(file, 4, 36 + dataBytes);
        writeAscii(file, 8, "WAVE");
        writeAscii(file, 12, "fmt ");
        writeInt(file, 16, 16);
        writeShort(file, 20, 1);
        writeShort(file, 22, 2);
        writeInt(file, 24, RATE);
        writeInt(file, 28, RATE * 4);
        writeShort(file, 32, 4);
        writeShort(file, 34, 16);
        writeAscii(file, 36, "data");
        writeInt(file, 40, dataBytes);

        for (int i = 0; i < frames; i++)
        {
            short value = (short) Math.round(0.5F * 32767F);

            file[44 + i * 4] = (byte) (value & 0xFF);
            file[44 + i * 4 + 1] = (byte) ((value >> 8) & 0xFF);
            file[44 + i * 4 + 2] = (byte) (value & 0xFF);
            file[44 + i * 4 + 3] = (byte) ((value >> 8) & 0xFF);
        }

        Path path = folder.resolve("python_style.wav");

        java.nio.file.Files.write(path, file);

        CompDocument comp = new CompDocument();
        StringBuilder log = new StringBuilder();

        comp.audio.add(new CompAudio("a", "A", "python_style.wav"));

        float[] mix = CompAudioMixer.mix(comp, (ignored) -> path, 0F, 0.25F, log::append);

        assertEquals(CompAudioMixer.SAMPLE_RATE * CompAudioMixer.CHANNELS / 4, mix.length, 2);
        assertEquals(0.5D, sample(mix, RATE / 8, 0), 0.02D, "the fallback parser must return the samples (" + log + ")");
    }

    @Test
    public void garbageIsNotDecodedAsAudio()
    {
        assertNull(CompAudioMixer.parseWav(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        assertNull(CompAudioMixer.parseWav(new byte[64]));
    }

    @Test
    public void writtenWavIsReadableAgain(@TempDir Path folder) throws Exception
    {
        Path file = folder.resolve("out.wav");
        float[] mix = new float[RATE * CompAudioMixer.CHANNELS];

        for (int i = 0; i < RATE; i++)
        {
            mix[i * 2] = 0.25F;
            mix[i * 2 + 1] = -0.25F;
        }

        CompAudioMixer.writeWav(file, mix);

        Wave wave;

        try (FileInputStream in = new FileInputStream(file.toFile()))
        {
            wave = new WaveReader().read(in);
        }

        assertEquals(CompAudioMixer.CHANNELS, wave.numChannels);
        assertEquals(RATE, wave.sampleRate);
        assertEquals(16, wave.bitsPerSample);
        assertEquals(RATE * CompAudioMixer.CHANNELS * 2, wave.data.length);
    }
}
