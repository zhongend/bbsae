package dev.bbsae.client.audio;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompInstance;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays a composition's own audio track while a film plays in BBS' original interface.
 *
 * <h2>Why this exists</h2>
 * <p>A {@link CompositionClip} is a camera-timeline window, not an {@code AudioClip}: BBS' film
 * editor plays only audio clips through {@code AudioClientClip.manageSounds()}, so a composition
 * added to the film timeline was a silent picture. The BBS recorder renders only the same audio
 * clips, which is why the exported video was silent too. The composition's sound has to be fed into
 * BBS' own playback/recording pipeline, not only into the compositor's preview.</p>
 *
 * <h2>How it is driven</h2>
 * <p>The original controller calls this every displayed frame, right after it has managed BBS'
 * audio clips. The active composition instances are the ones the same frame's {@link ClipContext}
 * already collected (the clip puts them in {@code clipData}), so the playback clock is the film
 * clock — the same value the composition picture is sampled at. Pausing, scrubbing and looping need
 * no callbacks: the mix is a fixed buffer and the player is seeked to the composition time.</p>
 */
public final class CompFilmAudio
{
    /** Stop a source whose clip has not been seen for this long (page change, clip deleted). */
    private static final long EXPIRE_MS = 1500L;

    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
    private static long lastManageMs;
    private static boolean loggedSilent;

    private CompFilmAudio()
    {}

    /**
     * Frame hook: keeps the composition mix on the film playhead.
     *
     * <p>Called from {@code AudioClientClip.manageSounds()} after BBS has scheduled its own clips.
     * The context's {@code playing} flag is BBS' own (the runner sets it), so the composition pauses
     * exactly when the film does.</p>
     */
    public static void manage(ClipContext context)
    {
        if (context == null)
        {
            return;
        }

        CompMixCache.tick();

        lastManageMs = System.currentTimeMillis();

        List<CompInstance> instances = context.clipData.get(CompositionClip.KEY, ArrayList::new);
        Map<String, Boolean> seen = new LinkedHashMap<>();

        /* The film's own playback supersedes the film-page audition: both would otherwise play the
         * same mix at two positions. */
        if (!instances.isEmpty() && context.playing && CompAudioPlayer.isPlaying())
        {
            CompAudioPlayer.pause();
        }

        for (CompInstance instance : instances)
        {
            if (instance == null)
            {
                continue;
            }

            CompProject project = CompStore.getForFrame(instance.project);

            if (project == null)
            {
                continue;
            }

            CompDocument doc = project.getComp(instance.comp);

            if (doc == null || !CompMixCache.hasAudio(doc))
            {
                continue;
            }

            float compTime = instance.time();

            /* The composition clip's start tick is what makes two windows of the same composition
             * distinct sources; filmTicks - ticks is constant for every frame of one clip. */
            long clipTick = Math.round(instance.filmTicks - instance.ticks);
            String key = project.id + "/" + instance.comp + "@" + clipTick;

            seen.put(key, Boolean.TRUE);

            Source source = SOURCES.get(key);
            String mixKey = CompMixCache.keyOf(doc);

            if (source == null || !source.mixKey.equals(mixKey))
            {
                if (source != null)
                {
                    release(source);
                }

                source = new Source(key, mixKey);
                SOURCES.put(key, source);
            }

            source.lastSeenMs = lastManageMs;

            float[] samples = CompMixCache.get(project, doc);

            if (samples == null)
            {
                source.lastState = "混音中…";
                continue;
            }

            if (samples.length == 0)
            {
                source.lastState = "音频缺失";
                continue;
            }

            if (source.player == null && !source.create(samples))
            {
                continue;
            }

            boolean audible = instance.timeScale > 0F
                && compTime >= 0F
                && compTime < doc.duration
                && instance.opacity > 0.001F;

            if (!context.playing || !audible)
            {
                if (source.player.isPlaying())
                {
                    source.player.pause();
                }

                if (audible)
                {
                    /* Keep the paused player on the frame the user scrubbed to, so resuming — or
                     * the next play — starts at the right sample instead of wherever it stopped. */
                    seek(source.player, compTime);
                }

                source.lastState = context.playing ? "不在音轨范围" : "已暂停";
                continue;
            }

            float position = source.player.getPlaybackPosition();

            if (!source.player.isPlaying())
            {
                seek(source.player, compTime);
                source.player.play();
            }
            else if (Math.abs(position - compTime) > 0.12F)
            {
                seek(source.player, compTime);
            }

            source.lastState = "播放中";
        }

        /* A composition window that disappeared (clip deleted, page changed) must not keep playing
         * into the next scene. expire() handles the common case on the next tick; this immediate
         * pause covers the transition frame. */
        long now = lastManageMs;

        for (Source source : SOURCES.values())
        {
            if (!seen.containsKey(source.key) && source.player != null && source.player.isPlaying())
            {
                source.player.pause();
                source.lastSeenMs = now;
                source.lastState = "已离开";
            }
        }
    }

    /** Client-tick upkeep: drain mixes and release sources that left the screen. */
    public static void tick()
    {
        CompMixCache.tick();

        long now = System.currentTimeMillis();

        /* The original controller only calls manage() on frames that have an active camera clip.
         * A gap in the film (or a different dashboard page) therefore stopped the updates while
         * OpenAL kept playing the last window. Pausing after a very short silence keeps a gap
         * silent without reacting to a single dropped render frame. */
        if (now - lastManageMs > 250L)
        {
            for (Source source : SOURCES.values())
            {
                if (source.player != null && source.player.isPlaying())
                {
                    source.player.pause();
                    source.lastState = "等待播放头";
                }
            }
        }

        Iterator<Map.Entry<String, Source>> it = SOURCES.entrySet().iterator();

        while (it.hasNext())
        {
            Source source = it.next().getValue();

            if (now - source.lastSeenMs <= EXPIRE_MS)
            {
                continue;
            }

            release(source);
            it.remove();
        }
    }

    /** Called when the game stops, or when a film/project is replaced. */
    public static void releaseAll()
    {
        for (Source source : SOURCES.values())
        {
            release(source);
        }

        SOURCES.clear();
        CompMixCache.clear();
    }

    /** Inspector/debug status: what the composition audio is doing in the original film page. */
    public static String status()
    {
        if (SOURCES.isEmpty())
        {
            return "无合成器音频";
        }

        int playing = 0;
        String state = "—";

        for (Source source : SOURCES.values())
        {
            if (source.player != null && source.player.isPlaying())
            {
                playing += 1;
            }

            state = source.lastState;
        }

        return (playing > 0 ? "播放中 · " : "") + SOURCES.size() + " 个合成音频窗口 · " + state;
    }

    /** Verification helper: number of live sources. */
    public static int sourceCount()
    {
        return SOURCES.size();
    }

    private static void seek(SoundPlayer player, float seconds)
    {
        player.setPlaybackPosition(Math.max(0F, seconds));
    }

    private static void release(Source source)
    {
        if (source.player != null)
        {
            try
            {
                source.player.stop();
                source.player.delete();
            }
            catch (Exception ignored)
            {}

            source.player = null;
        }

        if (source.buffer != null)
        {
            try
            {
                source.buffer.delete();
            }
            catch (Exception ignored)
            {}

            source.buffer = null;
        }
    }

    private static final class Source
    {
        final String key;
        String mixKey;
        SoundBuffer buffer;
        SoundPlayer player;
        long lastSeenMs;
        String lastState = "未开始";

        Source(String key, String mixKey)
        {
            this.key = key;
            this.mixKey = mixKey;
        }

        boolean create(float[] samples)
        {
            try
            {
                Wave wave = dev.bbsae.client.export.CompAudioMixer.toWave(samples);

                this.buffer = new SoundBuffer(new Link("bbsae", "film_" + Integer.toHexString(this.key.hashCode())), wave, null);
                this.player = new SoundPlayer(this.buffer);

                this.player.setRelative(true);
                this.player.setLooping(false);
                this.player.setVolume(1F);

                return true;
            }
            catch (Exception e)
            {
                if (!CompFilmAudio.loggedSilent)
                {
                    CompFilmAudio.loggedSilent = true;
                    BbsAeAddon.LOGGER.warn("Could not create a film audio source for {}; the original film page will be silent", this.key, e);
                }

                this.lastState = "无法创建音源";
                release(this);

                return false;
            }
        }
    }
}
