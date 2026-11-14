package dev.bbsae.client.audio;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.export.CompAudioMixer;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Adds composition audio to the WAV BBS renders for a film export or for "render audio".
 *
 * <h2>Why the composition is not an {@code AudioClip}</h2>
 * <p>BBS' export only knows the camera timeline's {@link AudioClip}s: its
 * {@code AudioRenderer.renderAudio()} decodes exactly those into the mono WAV that the recorder
 * muxes. A bbsae {@link CompositionClip} is a different clip type, so it contributed picture but
 * no sound. The fix has to enter at that boundary: once BBS has produced its own track, the
 * composition's mix is added at the compositions' film-timeline positions.</p>
 *
 * <h2>Time mapping</h2>
 * <p>A composition window maps film time to composition time exactly like the picture does:
 * {@code compSeconds = (clipLocalTicks * timeScale + offset) / 20}. Using the same formula as
 * {@link dev.bbsae.comp.CompInstance#time()} is what keeps a split, offset or time-scaled clip's
 * audio aligned with what is on screen.</p>
 */
public final class CompFilmAudioExport
{
    /** Set by the export sessions around their prepare() call, where BBS renders the film audio. */
    private static final ThreadLocal<Film> CURRENT_FILM = new ThreadLocal<>();

    private CompFilmAudioExport()
    {}

    public static void begin(Film film)
    {
        CURRENT_FILM.set(film);
    }

    public static void end()
    {
        CURRENT_FILM.remove();
    }

    /**
     * Mixes every composition window of {@code film} into the WAV at {@code file}.
     *
     * @param bbsHadAudio whether BBS wrote its own track (and therefore whether {@code file} exists)
     * @return true when at least one composition sample was written
     */
    public static boolean mixInto(Film film, File file, int totalDurationTicks, int sampleRate,
                                  float from, float to, boolean bbsHadAudio)
    {
        if (film == null || file == null)
        {
            return false;
        }

        List<CompositionClip> clips = film.camera.getClips(CompositionClip.class);

        if (clips.isEmpty())
        {
            return false;
        }

        float filmSeconds = Math.max(0F, totalDurationTicks / 20F);
        float start;
        float end;

        if (from != to && from >= 0F && to >= 0F)
        {
            start = Math.min(from, to);
            end = Math.max(from, to);
        }
        else
        {
            start = 0F;
            end = filmSeconds;
        }

        start = Math.max(0F, start);
        end = Math.max(start, Math.min(filmSeconds, end));

        if (end <= start)
        {
            return false;
        }

        int frames = Math.round((end - start) * CompAudioMixer.SAMPLE_RATE);

        if (frames <= 0)
        {
            return false;
        }

        float[] mixed = new float[frames * CompAudioMixer.CHANNELS];
        boolean bbs = bbsHadAudio && readInto(file, mixed);

        boolean added = false;
        int windows = 0;

        for (CompositionClip clip : clips)
        {
            if (clip == null || !Boolean.TRUE.equals(clip.enabled.get()))
            {
                continue;
            }

            CompProject project = CompStore.getForFrame(clip.project.get());
            CompDocument doc = project == null ? null : project.getComp(clip.comp.get());

            if (doc == null || !CompMixCache.hasAudio(doc))
            {
                continue;
            }

            float[] samples = CompMixCache.getBlocking(project, doc);

            if (samples.length == 0)
            {
                continue;
            }

            int compFrames = samples.length / CompAudioMixer.CHANNELS;
            float clipStart = clip.tick.get() / 20F;
            float clipEnd = (clip.tick.get() + clip.duration.get()) / 20F;
            float windowStart = Math.max(start, clipStart);
            float windowEnd = Math.min(end, clipEnd);

            if (windowEnd <= windowStart)
            {
                continue;
            }

            int firstFrame = Math.max(0, (int) Math.floor((windowStart - start) * CompAudioMixer.SAMPLE_RATE));
            int lastFrame = Math.min(frames, (int) Math.ceil((windowEnd - start) * CompAudioMixer.SAMPLE_RATE));
            boolean clipAdded = false;

            if (Math.abs(clip.timeScale.get()) < 0.0001F)
            {
                continue;
            }

            for (int frame = firstFrame; frame < lastFrame; frame++)
            {
                float filmTime = start + frame / (float) CompAudioMixer.SAMPLE_RATE;
                float compTime = compositionTimeAt(clip, filmTime);

                if (compTime < 0F || compTime >= doc.duration)
                {
                    continue;
                }

                float sourceFrame = compTime * CompAudioMixer.SAMPLE_RATE;
                int index = (int) sourceFrame;

                if (index < 0 || index >= compFrames)
                {
                    continue;
                }

                int next = Math.min(compFrames - 1, index + 1);
                float fraction = sourceFrame - index;
                int out = frame * CompAudioMixer.CHANNELS;
                int a = index * CompAudioMixer.CHANNELS;
                int b = next * CompAudioMixer.CHANNELS;

                mixed[out] += samples[a] + (samples[b] - samples[a]) * fraction;
                mixed[out + 1] += samples[a + 1] + (samples[b + 1] - samples[a + 1]) * fraction;

                clipAdded = true;
            }

            if (clipAdded)
            {
                added = true;
                windows += 1;
            }
        }

        if (!added)
        {
            /* Nothing audible in the composition: leave BBS' own track exactly as rendered. */
            return false;
        }

        for (int i = 0; i < mixed.length; i++)
        {
            mixed[i] = Math.max(-1F, Math.min(1F, mixed[i]));
        }

        try
        {
            CompAudioMixer.writeWav(file.toPath(), mixed);
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not write composition audio into {}", file, e);

            return false;
        }

        BbsAeAddon.LOGGER.info("Film audio: composition audio mixed into {} (window={}..{}s, {} comp window(s), {} frame(s), BBS track={})",
            file.getName(), String.format(Locale.ROOT, "%.2f", start), String.format(Locale.ROOT, "%.2f", end),
            windows, frames, bbs);

        return true;
    }

    /**
     * Composition-local time (seconds) a clip shows at a film time.
     *
     * <p>Identical to {@link dev.bbsae.comp.CompInstance#time()}: offset is expressed in composition
     * ticks and applied after the clip's time scale. Kept as a pure static so the audio mapping is
     * regression-tested without a GL context.</p>
     */
    static float compositionTimeAt(CompositionClip clip, float filmTimeSeconds)
    {
        if (clip == null)
        {
            return -1F;
        }

        float localTicks = filmTimeSeconds * 20F - clip.tick.get();

        return (localTicks * clip.timeScale.get()) / 20F + clip.offset.get() / 20F;
    }

    /** Reads BBS' rendered mono/stereo 16-bit WAV into the first channels of {@code out}. */
    private static boolean readInto(File file, float[] out)
    {
        if (file == null || !file.isFile())
        {
            return false;
        }

        try (InputStream stream = new FileInputStream(file))
        {
            Wave wave = new WaveReader().read(stream);

            if (wave == null || wave.data == null || wave.data.length == 0 || wave.getBytesPerSample() != 2)
            {
                return false;
            }

            int channels = Math.max(1, wave.numChannels);
            int frames = wave.data.length / 2 / channels;
            int limit = Math.min(frames, out.length / CompAudioMixer.CHANNELS);

            for (int frame = 0; frame < limit; frame++)
            {
                int in = frame * channels * 2;
                float left = readShort(wave.data, in) / 32768F;
                float right = channels > 1 ? readShort(wave.data, in + 2) / 32768F : left;
                int dest = frame * CompAudioMixer.CHANNELS;

                out[dest] += left;
                out[dest + 1] += right;
            }

            return limit > 0;
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not read BBS' rendered film audio {}", file, e);

            return false;
        }
    }

    private static float readShort(byte[] data, int offset)
    {
        int lo = data[offset] & 0xFF;
        int hi = data[offset + 1];

        return (short) ((hi << 8) | lo);
    }

    /** The film whose audio is being rendered: the export session's, or the open film panel's. */
    public static Film currentFilm()
    {
        Film film = CURRENT_FILM.get();

        if (film != null)
        {
            return film;
        }

        try
        {
            if (BBSModClient.getDashboard() == null)
            {
                return null;
            }

            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            return panel == null ? null : panel.getData();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** True when this film has at least one composition window with an audio track. */
    public static boolean hasCompositionAudio(Film film)
    {
        if (film == null)
        {
            return false;
        }

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            if (clip == null || !Boolean.TRUE.equals(clip.enabled.get()))
            {
                continue;
            }

            CompProject project = CompStore.getForFrame(clip.project.get());
            CompDocument doc = project == null ? null : project.getComp(clip.comp.get());

            if (doc != null && CompMixCache.hasAudio(doc))
            {
                return true;
            }
        }

        return false;
    }
}
