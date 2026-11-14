package dev.bbsae.client.render;

import dev.bbsae.client.export.CompAudioMixer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Waveform cache for the audio track.
 *
 * <h2>The bug this replaces</h2>
 * <p>The old cache computed peaks for the <em>whole file</em>, then the timeline stretched those
 * peaks across the clip bar. A clip trimmed to the middle of a song therefore showed the song's
 * intro, and changing the zoom changed the bucket count and re-decoded the file on the render
 * thread. Zooming a 5-minute MP3 visibly froze the editor.</p>
 *
 * <p>The model is now two-stage and trim-aware:</p>
 * <ol>
 *   <li>one <b>envelope</b> per file (fixed {@value #ENVELOPE_BUCKETS} buckets over its whole
 *       length), decoded once on a worker thread;</li>
 *   <li>a <b>window</b> query ({@code offset}, {@code source length}, {@code buckets}) that slices
 *       that envelope, so any trim, speed or zoom is just index arithmetic.</li>
 * </ol>
 * <p>Until the envelope is ready the timeline draws no waveform for that clip instead of blocking
 * the frame; the next frame after the worker completes picks it up.</p>
 */
public final class CompAudioWaveform
{
    /** Envelope resolution over the whole source. 8192 is finer than a pixel at normal zoom. */
    private static final int ENVELOPE_BUCKETS = 8192;
    private static final int MAX_CACHE = 48;

    private static final Map<String, Envelope> ENVELOPES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor((r) ->
    {
        Thread thread = new Thread(r, "bbsae-audio-waveform");
        thread.setDaemon(true);

        return thread;
    });

    private CompAudioWaveform()
    {}

    private static final class Envelope
    {
        final String key;
        final float[] peaks;
        final float duration;

        Envelope(String key, float[] peaks, float duration)
        {
            this.key = key;
            this.peaks = peaks;
            this.duration = duration;
        }
    }

    /** Cache key: path + mtime + size, so replacing a file invalidates its picture. */
    private static String key(Path file)
    {
        try
        {
            return file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis()
                + "|" + Files.size(file);
        }
        catch (Exception e)
        {
            return file.toAbsolutePath().toString();
        }
    }

    /**
     * Starts (or reuses) the background load for a file. Safe to call every frame and from the
     * render thread: an already-cached/loading file is a set lookup.
     */
    public static void prewarm(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return;
        }

        String key = key(file);

        if (ENVELOPES.containsKey(key) || FAILED.contains(key) || !LOADING.add(key))
        {
            return;
        }

        WORKER.submit(() ->
        {
            try
            {
                float[] peaks = CompAudioMixer.peakEnvelope(file, ENVELOPE_BUCKETS);
                float duration = CompAudioMixer.probeDuration(file);

                if (peaks != null && duration > 0F)
                {
                    if (ENVELOPES.size() > MAX_CACHE)
                    {
                        ENVELOPES.clear();
                    }

                    ENVELOPES.put(key, new Envelope(key, peaks, duration));
                }
                else
                {
                    FAILED.add(key);
                }
            }
            catch (Exception e)
            {
                /* A missing/unsupported file simply has no waveform; remember that so the render
                 * thread does not queue a decode every frame. A modified file gets a new key. */
                FAILED.add(key);
            }
            finally
            {
                LOADING.remove(key);
            }
        });
    }

    /**
     * Peaks for a source window, or null while the file is still loading.
     *
     * @param offset source seconds at the clip's in-point
     * @param length source seconds the clip consumes (timeline length x speed)
     */
    public static float[] peaks(Path file, float offset, float length, int buckets)
    {
        if (file == null || buckets < 2 || !Files.isRegularFile(file) || length <= 0F)
        {
            return null;
        }

        String key = key(file);
        Envelope envelope = ENVELOPES.get(key);

        if (envelope == null)
        {
            prewarm(file);

            return null;
        }

        return peaksFromEnvelope(envelope, offset, length, buckets);
    }

    /** Synchronous variant used by tests; runtime callers use {@link #prewarm}/{@link #peaks}. */
    public static float[] peaksBlocking(Path file, float offset, float length, int buckets)
    {
        prewarm(file);

        String key = key(file);
        Envelope envelope = ENVELOPES.get(key);

        if (envelope == null)
        {
            try
            {
                float[] peaks = CompAudioMixer.peakEnvelope(file, ENVELOPE_BUCKETS);
                float duration = CompAudioMixer.probeDuration(file);

                if (peaks != null && duration > 0F)
                {
                    envelope = new Envelope(key, peaks, duration);
                    ENVELOPES.put(key, envelope);
                }
            }
            catch (Exception e)
            {
                return null;
            }
        }

        return envelope == null ? null : peaksFromEnvelope(envelope, offset, length, buckets);
    }

    private static float[] peaksFromEnvelope(Envelope envelope, float offset, float length, int buckets)
    {
        float sourceStart = Math.max(0F, Math.min(envelope.duration, offset));
        float sourceEnd = Math.max(sourceStart, Math.min(envelope.duration, offset + length));

        if (sourceEnd - sourceStart < 0.0001F)
        {
            return null;
        }

        float[] out = new float[buckets];
        int envelopeSize = envelope.peaks.length;

        for (int i = 0; i < buckets; i++)
        {
            double from = sourceStart + (sourceEnd - sourceStart) * (i / (double) buckets);
            double to = sourceStart + (sourceEnd - sourceStart) * ((i + 1D) / buckets);
            int first = (int) Math.floor(from / envelope.duration * envelopeSize);
            int last = (int) Math.ceil(to / envelope.duration * envelopeSize);

            first = Math.max(0, Math.min(envelopeSize - 1, first));
            last = Math.max(first + 1, Math.min(envelopeSize, last));

            float peak = 0F;

            for (int index = first; index < last; index++)
            {
                if (envelope.peaks[index] > peak)
                {
                    peak = envelope.peaks[index];
                }
            }

            out[i] = peak;
        }

        return out;
    }

    public static void clear()
    {
        ENVELOPES.clear();
        LOADING.clear();
        FAILED.clear();
    }
}
