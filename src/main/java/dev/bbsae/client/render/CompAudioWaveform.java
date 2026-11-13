package dev.bbsae.client.render;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small waveform cache for the audio track.
 *
 * <p>Only WAV can be decoded with the JDK's AudioSystem; OGG/MP3 clips simply render without a
 * waveform instead of failing the track. Peaks are cached by path+mtime+bucket count.</p>
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

        try (AudioInputStream input = AudioSystem.getAudioInputStream(file.toFile()))
        {
            AudioFormat format = input.getFormat();
            int channels = Math.max(1, format.getChannels());
            int frameSize = Math.max(1, format.getFrameSize());
            float[] peaks = new float[buckets];
            byte[] buffer = new byte[8192];
            int read;
            long totalFrames = Math.max(1L, input.getFrameLength());
            long frameIndex = 0L;

            while ((read = input.read(buffer)) > 0)
            {
                for (int offset = 0; offset + frameSize <= read; offset += frameSize)
                {
                    int bucket = (int) Math.min(buckets - 1, frameIndex * buckets / totalFrames);
                    float max = 0F;

                    for (int channel = 0; channel < channels; channel++)
                    {
                        int sampleOffset = offset + channel * Math.max(1, frameSize / channels);
                        int sample = 0;

                        if (format.getSampleSizeInBits() == 16)
                        {
                            int lo = buffer[sampleOffset] & 0xFF;
                            int hi = buffer[sampleOffset + 1];

                            sample = (short) ((hi << 8) | lo);
                        }
                        else if (format.getSampleSizeInBits() == 8)
                        {
                            sample = (buffer[sampleOffset] & 0xFF) - 128;
                        }

                        max = Math.max(max, Math.abs(sample) / 32768F);
                    }

                    peaks[bucket] = Math.max(peaks[bucket], max);
                    frameIndex++;
                }
            }

            CACHE.put(key, peaks);

            return peaks;
        }
        catch (Exception e)
        {
            return null;
        }
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
