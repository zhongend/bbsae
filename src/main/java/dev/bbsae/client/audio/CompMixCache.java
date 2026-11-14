package dev.bbsae.client.audio;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.export.CompAudioMixer;
import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One decoded, mixed composition audio track, shared by every consumer that needs it.
 *
 * <h2>Why a cache and not a player</h2>
 * <p>The same mix has to be heard in the compositor preview, in the original BBS film editor and in
 * BBS's own recorder, and it has to be mixed once per audio edit. The old code had a single
 * {@link CompAudioPlayer} that mixed for the compositor only; the original film page had no way to
 * ask for the same samples, which is why a composition clip was silent there and in the BBS
 * recording while the compositor played it fine.</p>
 *
 * <p>The samples are keyed by the clips' actual content, not by the document object: moving a clip,
 * changing gain or replacing the file invalidates the mix, while scrubbing the playhead does not.
 * Mixing happens on a worker thread (a twelve second track costs tens of milliseconds), and the
 * finished buffer is handed to the client thread by {@link #tick()}, which is the only place that may
 * touch OpenAL.</p>
 */
public final class CompMixCache
{
    /** Enough for a handful of compositions without pinning long decodes in memory. */
    private static final int MAX_ENTRIES = 4;
    private static final long MAX_BYTES = 192L * 1024L * 1024L;

    private static final Map<String, Entry> READY = new LinkedHashMap<>(8, 0.75F, true);
    private static final Map<String, Long> LOADING = new LinkedHashMap<>();
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();

    private static long bytes;

    private CompMixCache()
    {}

    /**
     * Content key of a mix: the clips and the document length. Deliberately excludes the playhead
     * and the clip selection — those do not change a single sample.
     */
    public static String keyOf(CompDocument doc)
    {
        if (doc == null)
        {
            return "";
        }

        StringBuilder key = new StringBuilder(doc.id).append('|').append(doc.duration).append('|');

        if (doc.audio != null)
        {
            for (CompAudio clip : doc.audio)
            {
                if (clip == null)
                {
                    continue;
                }

                key.append(clip.path).append(',').append(clip.start).append(',').append(clip.offset)
                    .append(',').append(clip.duration).append(',').append(clip.gain).append(',')
                    .append(clip.pitch).append(',').append(clip.speed).append(',').append(clip.pan).append(',')
                    .append(clip.fadeIn).append(',').append(clip.fadeOut).append(',')
                    .append(clip.muted ? 1 : 0).append(',').append(clip.loop ? 1 : 0).append(';');
            }
        }

        return key.toString();
    }

    /**
     * The mix for this document, or null while it is being prepared.
     *
     * <p>Calling this repeatedly is cheap: an in-flight mix is not restarted, and a finished one is
     * returned straight from the cache. An empty document (no audio clips) is represented by null
     * and must not start a worker.</p>
     */
    public static float[] get(CompProject project, CompDocument doc)
    {
        if (project == null || doc == null || doc.audio == null || doc.audio.isEmpty())
        {
            return null;
        }

        String key = keyOf(doc);
        Entry entry = READY.get(key);

        if (entry != null)
        {
            return entry.samples;
        }

        if (!LOADING.containsKey(key))
        {
            mixAsync(project, doc, key);
        }

        return null;
    }

    /** Synchronous variant for export preparation, where blocking the frame is correct. */
    public static float[] getBlocking(CompProject project, CompDocument doc)
    {
        if (project == null || doc == null || doc.audio == null || doc.audio.isEmpty())
        {
            return new float[0];
        }

        String key = keyOf(doc);
        Entry entry = READY.get(key);

        if (entry != null)
        {
            return entry.samples;
        }

        float[] samples = mix(project, doc);

        store(key, samples);

        return samples;
    }

    /** True when this document has at least one audio clip (muted-only still counts). */
    public static boolean hasAudio(CompDocument doc)
    {
        return doc != null && doc.audio != null && !doc.audio.isEmpty();
    }

    /** Drains finished mixes on the client/render thread. */
    public static void tick()
    {
        Result result;

        while ((result = RESULTS.poll()) != null)
        {
            /* The document may have been edited while the worker was mixing: its result describes
             * a key that no longer matches the live clips, so it must not enter the cache. */
            boolean current;

            try
            {
                current = result.key.equals(keyOf(result.doc));
            }
            catch (RuntimeException e)
            {
                /* The audio list was mutated while it was being hashed; treat it as stale. */
                current = false;
            }

            if (current)
            {
                store(result.key, result.samples);
            }
            else
            {
                LOADING.remove(result.key);
            }
        }
    }

    public static void clear()
    {
        READY.clear();
        LOADING.clear();
        RESULTS.clear();
        bytes = 0L;
    }

    public static String status(CompDocument doc)
    {
        if (!hasAudio(doc))
        {
            return "无音轨";
        }

        String key = keyOf(doc);

        if (LOADING.containsKey(key))
        {
            return "混音中…";
        }

        Entry entry = READY.get(key);

        if (entry == null)
        {
            return "尚未混音";
        }

        if (entry.samples.length == 0)
        {
            return "音频无法解码或文件缺失";
        }

        return "已就绪 " + (entry.samples.length / CompAudioMixer.CHANNELS
            / (float) CompAudioMixer.SAMPLE_RATE) + "s";
    }

    private static void mixAsync(CompProject project, CompDocument doc, String key)
    {
        LOADING.put(key, System.currentTimeMillis());

        Thread worker = new Thread(() ->
        {
            float[] samples;

            try
            {
                samples = mix(project, doc);
            }
            catch (Throwable e)
            {
                BbsAeAddon.LOGGER.warn("Could not mix film audio for {}", doc.id, e);
                samples = new float[0];
            }

            RESULTS.add(new Result(key, doc, samples));
        }, "bbsae-film-audio");

        worker.setDaemon(true);
        worker.start();
    }

    private static float[] mix(CompProject project, CompDocument doc)
    {
        try
        {
            return CompAudioMixer.mix(doc, CompAudioMixer.projectResolver(project.id), 0F,
                Math.max(0.05F, doc.duration),
                (message) -> BbsAeAddon.LOGGER.warn("[bbsae film audio] {}", message));
        }
        catch (Throwable e)
        {
            BbsAeAddon.LOGGER.warn("Film audio mix failed for {}", doc.id, e);

            return new float[0];
        }
    }

    private static void store(String key, float[] samples)
    {
        Entry previous = READY.remove(key);

        if (previous != null)
        {
            bytes -= previous.bytes;
        }

        Entry entry = new Entry(samples);
        READY.put(key, entry);
        LOADING.remove(key);
        bytes += entry.bytes;

        trim();
    }

    private static void trim()
    {
        Iterator<Map.Entry<String, Entry>> it = READY.entrySet().iterator();

        while (it.hasNext() && (READY.size() > MAX_ENTRIES || bytes > MAX_BYTES))
        {
            Map.Entry<String, Entry> oldest = it.next();

            bytes -= oldest.getValue().bytes;
            it.remove();
        }
    }

    private static final class Entry
    {
        final float[] samples;
        final long bytes;

        Entry(float[] samples)
        {
            this.samples = samples;
            this.bytes = samples == null ? 0L : samples.length * 4L;
        }
    }

    private static final class Result
    {
        final String key;
        final CompDocument doc;
        final float[] samples;

        Result(String key, CompDocument doc, float[] samples)
        {
            this.key = key;
            this.doc = doc;
            this.samples = samples;
        }
    }
}
