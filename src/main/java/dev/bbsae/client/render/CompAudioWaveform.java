package dev.bbsae.client.render;

import dev.bbsae.client.export.CompAudioMixer;
import mchorse.bbs_mod.audio.Wave;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small waveform cache for the audio track.
 *
 * <p>Uses BBS' own WAV/Vorbis readers (the same ones the mixer uses), so OGG clips get a waveform
 * too. Peaks are cached by path+mtime+bucket count.</p>
 */
public final class CompAudioWaveform
{
    private static final Map<String, float[]> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    private CompAudioWaveform()
    {}

    public static float[] peaks(Path file, int buckets)
    {
        if (file == null || buckets < 2 || !Files.isRegularFile(file))
        {
            return null;
        }

        String key = file.toAbsolutePath() + "|" + mtime(file) + "|" + buckets;
        float[] cached = CACHE.get(key);

        if (cached != null)
        {
            return cached;
        }

        try
        {
            Wave wave = CompAudioMixer.readWave(file);

            if (wave == null || wave.data == null || wave.data.length == 0)
            {
                return null;
            }

            int channels = Math.max(1, wave.numChannels);
            int bytes = Math.max(1, wave.bitsPerSample / 8);
            int frameBytes = Math.max(bytes, wave.blockAlign);
            int frames = Math.max(1, wave.data.length / frameBytes);
            float[] peaks = new float[buckets];

            for (int frame = 0; frame < frames; frame++)
            {
                int bucket = (int) Math.min(buckets - 1, (long) frame * buckets / frames);
                float max = 0F;

                for (int channel = 0; channel < channels; channel++)
                {
                    int offset = frame * frameBytes + channel * bytes;

                    if (offset + bytes > wave.data.length)
                    {
                        break;
                    }

                    max = Math.max(max, Math.abs(sample(wave.data, offset, wave.bitsPerSample)));
                }

                peaks[bucket] = Math.max(peaks[bucket], max);
            }

            CACHE.put(key, peaks);

            return peaks;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static float sample(byte[] data, int offset, int bits)
    {
        if (bits == 8)
        {
            return ((data[offset] & 0xFF) - 128) / 128F;
        }

        if (bits == 16)
        {
            return (short) ((data[offset + 1] << 8) | (data[offset] & 0xFF)) / 32768F;
        }

        if (bits == 24)
        {
            int value = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] << 16);
            return value / 8388608F;
        }

        int value = 0;

        for (int i = 0; i < 4 && offset + i < data.length; i++)
        {
            value |= (data[offset + i] & 0xFF) << (i * 8);
        }

        return value / 2147483648F;
    }

    private static long mtime(Path file)
    {
        try
        {
            return Files.getLastModifiedTime(file).toMillis();
        }
        catch (Exception e)
        {
            return 0L;
        }
    }
}
