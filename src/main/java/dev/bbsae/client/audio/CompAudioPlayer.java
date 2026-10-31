package dev.bbsae.client.audio;

import dev.bbsae.client.export.CompAudioMixer;
import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.Waveform;
import mchorse.bbs_mod.resources.Link;

/**
 * Preview audio for the composition's own audio track.
 *
 * <h2>Why this is not "just play the WAV"</h2>
 * <p>The mix is not a file: it is the sum of the clips on the composition's audio track, positioned
 * on the composition's timeline. So the thing to play is <b>the composition's mix</b>, and the thing
 * to keep in sync is <b>the playhead</b>. Two consequences:</p>
 * <ul>
 *   <li>the mix is rendered once per audio-edit (not per play), on a worker thread, because decoding
 *       and mixing a 12-second track costs tens of milliseconds and must not hitch the editor;</li>
 *   <li>playback is driven by {@link #sync(float)}: the playhead is authoritative, the sound is
 *       corrected when it drifts or loops. That is why pausing, scrubbing and looping all sound
 *       right without any callback from the audio device.</li>
 * </ul>
 *
 * <p>Playback goes through BBS's own {@link SoundPlayer}, i.e. the same OpenAL device as the game,
 * so there is no second audio backend and no javax.sound dependency. OpenAL calls must happen on the
 * client thread, which is where {@link #play}, {@link #tick} and {@link #sync} are called from.</p>
 */
public final class CompAudioPlayer
{
    private static final Link LINK = new Link("bbsae", "preview_mix");

    private static SoundPlayer player;
    private static SoundBuffer buffer;
    private static String loadedKey = "";
    private static boolean wanted;
    private static float startTime;

    private static Thread worker;
    private static volatile float[] pending;
    private static volatile String pendingKey;
    private static volatile int generation;

    private CompAudioPlayer()
    {}

    /**
     * Content key of the mix: the clips and the range. Any edit to a clip changes it, which is the
     * only thing that has to invalidate the buffer.
     */
    private static String keyOf(CompDocument doc)
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
                    .append(clip.fadeIn).append(',').append(clip.fadeOut).append(',')
                    .append(clip.muted ? 1 : 0).append(',').append(clip.loop ? 1 : 0).append(';');
            }
        }

        return key.toString();
    }

    public static boolean hasAudio(CompDocument doc)
    {
        return doc != null && doc.audio != null && !doc.audio.isEmpty();
    }

    /** Starts (or resumes) playback at {@code time} seconds. Safe to call every frame. */
    public static void play(CompProject project, CompDocument doc, float time)
    {
        if (project == null || !hasAudio(doc))
        {
            return;
        }

        wanted = true;
        startTime = time;

        ensureBuffer(project, doc);

        if (player == null)
        {
            /* Still mixing: tick() will start it as soon as the buffer exists. */
            return;
        }

        player.setVolume(1F);
        player.setRelative(true);
        player.setLooping(false);
        player.setPlaybackPosition(Math.max(0F, time));

        if (!player.isPlaying())
        {
            player.play();
        }
    }

    public static void pause()
    {
        wanted = false;

        if (player != null && player.isPlaying())
        {
            player.pause();
        }
    }

    public static void stop()
    {
        wanted = false;

        if (player != null)
        {
            player.stop();
        }
    }

    /** Re-seeks without changing whether we are playing. */
    public static void seek(float time)
    {
        startTime = time;

        if (player != null)
        {
            player.setPlaybackPosition(Math.max(0F, time));
        }
    }

    /**
     * Keeps the sound on the playhead. Called every frame while playing: a drift over 250 ms (or a
     * loop back to the start) is corrected by seeking; smaller drift is left alone so the audio does
     * not stutter.
     */
    public static void sync(float time)
    {
        if (!wanted || player == null)
        {
            return;
        }

        if (!player.isPlaying())
        {
            player.setPlaybackPosition(Math.max(0F, time));
            player.play();

            return;
        }

        float drift = Math.abs(player.getPlaybackPosition() - time);

        if (drift > 0.25F)
        {
            player.setPlaybackPosition(Math.max(0F, time));
        }
    }

    /** Drains the mixer thread on the client thread. Call once per frame. */
    public static void tick()
    {
        float[] samples = pending;

        if (samples == null)
        {
            return;
        }

        pending = null;

        String key = pendingKey;

        if (samples.length == 0 || key == null || !key.equals(keyOf(currentDoc)))
        {
            return;
        }

        releaseBuffer();

        try
        {
            Wave wave = CompAudioMixer.toWave(samples);
            Waveform waveform = new Waveform();

            waveform.populate(wave, 512, 128);

            buffer = new SoundBuffer(LINK, wave, waveform);
            player = new SoundPlayer(buffer);
            player.setRelative(true);
            player.setVolume(1F);
            loadedKey = key;

            if (Boolean.getBoolean("bbsae.debugEncoder"))
            {
                dev.bbsae.BbsAeAddon.LOGGER.info("preview audio ready: {} samples ({}) files={}",
                    samples.length, describe(), currentDoc == null ? 0 : currentDoc.audio.size());
            }

            if (wanted)
            {
                player.setPlaybackPosition(Math.max(0F, startTime));
                player.play();
            }
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not create the preview audio buffer", e);
            releaseBuffer();
        }
    }

    /** The document the buffer belongs to; set by {@link #play}. */
    private static CompDocument currentDoc;

    private static void ensureBuffer(CompProject project, CompDocument doc)
    {
        currentDoc = doc;

        String key = keyOf(doc);

        if (key.equals(loadedKey) && player != null)
        {
            return;
        }

        /* The clips changed: silence the old mix immediately, the new one starts when the worker
         * hands it over. Leaving the old buffer playing would be "audio from the previous edit". */
        if (player != null)
        {
            player.stop();
            player.setPlaybackPosition(0F);
        }

        if (pendingKey != null && pendingKey.equals(key))
        {
            return;
        }

        final int ticket = ++generation;

        pendingKey = key;
        pending = null;

        if (worker != null && worker.isAlive())
        {
            return;
        }

        worker = new Thread(() ->
        {
            try
            {
                float[] samples = CompAudioMixer.mix(doc,
                    (path) -> CompAudioMixer.resolver(dev.bbsae.comp.CompStore.assetRoot(project.id)).apply(path),
                    0F, Math.max(0.05F, doc.duration),
                    (message) -> dev.bbsae.BbsAeAddon.LOGGER.warn("[bbsae] {}", message));

                if (ticket == generation)
                {
                    pending = samples;
                }
            }
            catch (Exception e)
            {
                dev.bbsae.BbsAeAddon.LOGGER.warn("Could not mix preview audio", e);
            }
        }, "bbsae-preview-audio");

        worker.setDaemon(true);
        worker.start();
    }

    /** Drops the buffer; call when the project changes or the mode is switched. */
    public static void invalidate()
    {
        generation++;
        pending = null;
        pendingKey = null;
        loadedKey = "";
        wanted = false;

        releaseBuffer();
    }

    private static void releaseBuffer()
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
    }

    /** @return true when the mix is ready and a sound is running. */
    public static boolean isPlaying()
    {
        return player != null && player.isPlaying();
    }

    public static String describe()
    {
        if (player == null)
        {
            return pending != null ? "混音中…" : "无预览音频";
        }

        return "预览音频 " + String.format(java.util.Locale.ROOT, "%.2fs", player.getPlaybackPosition())
            + (player.isPlaying() ? " 播放中" : " 已暂停");
    }
}
