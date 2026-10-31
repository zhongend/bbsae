package dev.bbsae.client.export;

import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.ogg.VorbisReader;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.resources.Link;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Mixes a composition's audio track into one 48 kHz stereo buffer and writes it as a WAV.
 *
 * <h2>Why a real mix and not "pass the file to FFmpeg"</h2>
 * <p>AE's output module does not hand FFmpeg a list of clips — it renders the audio of the
 * <em>composition</em> and muxes it. A composition can have several clips that start at different
 * times, with their own gain and fades; a video exported with only the first file's audio would be
 * wrong for every project that has a music bed and a voice-over.</p>
 *
 * <p>So the mix is computed here, in a pure function over (document, sample range), which also makes
 * it testable without a game: the same code produces the WAV that is muxed into the MP4, and the
 * standalone WAV output module writes that same WAV.</p>
 *
 * <h2>Decoding</h2>
 * <p>WAV and OGG are decoded with BBS's own readers (the ones the film editor and sound library
 * use), so the addon has no extra dependencies and matches BBS's loudness behaviour. Anything else
 * is reported back through the log instead of silently producing silence.</p>
 */
public final class CompAudioMixer
{
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 2;
    /** Upper bound for one in-memory mix (~660 MB of floats); longer ranges are truncated with a log line. */
    public static final float MAX_MIX_SECONDS = 1800F;

    private CompAudioMixer()
    {}

    /** Resolves a project-relative audio path to a file on disk. */
    public interface Resolver
    {
        Path resolve(String path);
    }

    /** Receives one line per skipped/unreadable clip. */
    public interface Log
    {
        void warn(String message);
    }

    /** Number of interleaved stereo frames for a time range. */
    public static int frames(float start, float duration)
    {
        return Math.max(0, Math.round(Math.max(0F, duration) * SAMPLE_RATE));
    }

    /**
     * Mixes every audible clip of {@code comp} into an interleaved stereo buffer.
     *
     * @param start   composition time the export starts at (audio is cut to match).
     * @param end     composition time the export ends at.
     * @return interleaved L/R samples in -1..1; empty when the comp has no audible audio.
     */
    public static float[] mix(CompDocument comp, Resolver resolver, float start, float end, Log log)
    {
        if (comp == null || comp.audio == null || comp.audio.isEmpty())
        {
            return new float[0];
        }

        /* The mix is one float[] in memory (stereo 48 kHz = 384 KB per second). A bogus or absurd
         * end time used to allocate gigabytes (and past ~12.4 h the frame count overflows to
         * negative) — clamp instead, and say so. */
        float requested = Math.max(0F, end - start);

        if (requested > MAX_MIX_SECONDS)
        {
            if (log != null)
            {
                log.warn("音频区间 " + String.format(java.util.Locale.ROOT, "%.0f", requested)
                    + "s 过长，已截断到 " + MAX_MIX_SECONDS + "s");
            }

            end = start + MAX_MIX_SECONDS;
        }

        int total = frames(start, Math.max(0F, end - start));

        if (total <= 0)
        {
            return new float[0];
        }

        float[] out = new float[total * CHANNELS];
        boolean any = false;

        for (CompAudio clip : comp.audio)
        {
            if (clip == null || clip.muted || clip.path == null || clip.path.isBlank())
            {
                continue;
            }

            Source source = decode(resolver, clip, log);

            if (source == null)
            {
                continue;
            }

            if (mixClip(out, total, clip, source, start))
            {
                any = true;
            }
        }

        return any ? out : new float[0];
    }

    private static boolean mixClip(float[] out, int total, CompAudio clip, Source source, float exportStart)
    {
        float clipStart = clip.start;
        float clipLength = clip.duration > 0F ? clip.duration : Math.max(0F, source.duration() - Math.max(0F, clip.offset));

        if (clipLength <= 0F)
        {
            return false;
        }

        int firstFrame = Math.max(0, Math.round((clipStart - exportStart) * SAMPLE_RATE));
        int lastFrame = Math.min(total, Math.round((clipStart - exportStart + clipLength) * SAMPLE_RATE));

        if (lastFrame <= 0 || firstFrame >= total)
        {
            return false;
        }

        firstFrame = Math.max(0, firstFrame);

        /* How far into the clip the export range already is.
         *
         * Without this, exporting 2s…3s of a composition whose music starts at 0s played the
         * first second of the file instead of the third — every range export was shifted by its
         * start time. `local` alone is only the offset from the first *exported* frame. */
        float seek = Math.max(0F, exportStart - clipStart);
        int sourceOffset = Math.round((Math.max(0F, clip.offset) + seek) * SAMPLE_RATE);

        if (sourceOffset < 0)
        {
            sourceOffset = 0;
        }

        float gain = Math.max(0F, clip.gain);
        float fadeIn = Math.max(0F, clip.fadeIn);
        float fadeOut = Math.max(0F, clip.fadeOut);
        int sourceFrames = source.frames;

        /* An in-point at or past the end of the decoded file has nothing to play — not even to
         * loop. (It used to compute loopLength = 1 and then index past the array, and the thrown
         * exception dropped the WHOLE mix in the caller, silently removing all audio.) */
        if (sourceFrames <= 0 || sourceOffset >= sourceFrames)
        {
            return false;
        }

        int loopLength = sourceFrames - sourceOffset;
        boolean any = false;

        for (int frame = firstFrame; frame < lastFrame; frame++)
        {
            int local = frame - firstFrame;
            int index = sourceOffset + local;

            if (index >= sourceFrames)
            {
                if (!clip.loop)
                {
                    break;
                }

                /* sourceOffset < sourceFrames, so this always lands inside the file. */
                index = sourceOffset + (local % loopLength);
            }

            /* Time since the clip's own in-point: fades are anchored to the clip, not to the
             * export range, so a range export cannot re-trigger a fade-in. */
            float time = seek + local / (float) SAMPLE_RATE;
            float envelope = gain;

            if (fadeIn > 0F && time < fadeIn)
            {
                envelope *= time / fadeIn;
            }

            if (fadeOut > 0F)
            {
                float remaining = clipLength - time;

                if (remaining < fadeOut)
                {
                    envelope *= Math.max(0F, remaining / fadeOut);
                }
            }

            out[frame * 2] += source.left[index] * envelope;
            out[frame * 2 + 1] += source.right[index] * envelope;
            any = true;
        }

        return any;
    }

    /** Clips to -1..1 and converts to little-endian 16-bit PCM. */
    public static byte[] toPcm16(float[] samples)
    {
        byte[] data = new byte[samples.length * 2];

        for (int i = 0; i < samples.length; i++)
        {
            float value = samples[i];

            if (value > 1F) value = 1F;
            if (value < -1F) value = -1F;

            int pcm = Math.round(value * 32767F);

            data[i * 2] = (byte) (pcm & 0xFF);
            data[i * 2 + 1] = (byte) ((pcm >> 8) & 0xFF);
        }

        return data;
    }

    /**
     * The same PCM as {@link #writeWav}, as an in-memory {@link Wave}.
     *
     * <p>Used by the preview player: BBS's {@code SoundBuffer} uploads a {@code Wave} straight to
     * OpenAL, so the editor can audition the composition's mix without ever touching the disk.</p>
     */
    public static Wave toWave(float[] samples)
    {
        int blockAlign = CHANNELS * 2;

        return new Wave(1, CHANNELS, SAMPLE_RATE, SAMPLE_RATE * blockAlign, blockAlign, 16, toPcm16(samples));
    }

    /** Writes a 48 kHz / 16-bit / stereo WAV. */
    public static void writeWav(Path file, float[] samples) throws IOException
    {
        Files.createDirectories(file.toAbsolutePath().getParent());

        byte[] data = toPcm16(samples);
        int blockAlign = CHANNELS * 2;

        Wave wave = new Wave(1, CHANNELS, SAMPLE_RATE, SAMPLE_RATE * blockAlign, blockAlign, 16, data);

        WaveWriter.write(file.toFile(), wave);
    }

    /* Decoding */

    private static final class Source
    {
        final float[] left;
        final float[] right;
        final int frames;

        Source(float[] left, float[] right, int frames)
        {
            this.left = left;
            this.right = right;
            this.frames = frames;
        }

        float duration()
        {
            return this.frames / (float) SAMPLE_RATE;
        }
    }

    private static Source decode(Resolver resolver, CompAudio clip, Log log)
    {
        Path file;

        try
        {
            file = resolver.resolve(clip.path);
        }
        catch (RuntimeException e)
        {
            file = null;
        }

        if (file == null || !Files.isRegularFile(file))
        {
            warn(log, "音频不存在：" + clip.path);

            return null;
        }

        try
        {
            byte[] bytes = Files.readAllBytes(file);
            Wave wave = readWave(file, bytes);

            if (wave == null)
            {
                warn(log, "无法解码音频（支持 WAV / OGG）：" + clip.path);

                return null;
            }

            return toSource(wave);
        }
        catch (Exception e)
        {
            warn(log, "音频解码失败：" + clip.path + " (" + e.getMessage() + ")");

            return null;
        }
    }

    private static Wave readWave(Path file, byte[] bytes) throws Exception
    {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

        if (name.endsWith(".wav") || startsWith(bytes, 'R', 'I', 'F', 'F'))
        {
            try (InputStream in = new ByteArrayInputStream(bytes))
            {
                return new WaveReader().read(in);
            }
            catch (Throwable readerFailure)
            {
                /* BBS's reader walks chunks until it hits a terminator, so a perfectly valid file
                 * that simply ends after its data chunk (python's wave module, a few exporters)
                 * throws EOFException there. Fall back to a plain RIFF parser instead of losing
                 * the clip: the frames are already in the bytes. */
                Wave fallback = parseWav(bytes);

                if (fallback != null)
                {
                    return fallback;
                }

                throw readerFailure;
            }
        }

        if (name.endsWith(".ogg") || startsWith(bytes, 'O', 'g', 'g', 'S'))
        {
            try (InputStream in = new ByteArrayInputStream(bytes))
            {
                return VorbisReader.read(new Link("bbsae", file.getFileName().toString()), in);
            }
        }

        return null;
    }

    /**
     * Minimal RIFF/WAVE reader: {@code fmt } + {@code data}, PCM 8/16/24/32-bit and 32-bit float.
     *
     * <p>Deliberately small and strict: it only exists to survive files whose chunk list has no
     * terminator (see the call site). Anything it does not understand returns null, and the caller
     * reports "cannot decode" — guessing at a format would produce noise, not audio.</p>
     */
    static Wave parseWav(byte[] bytes)
    {
        if (bytes == null || bytes.length < 44)
        {
            return null;
        }

        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int format = 1;
        int dataOffset = -1;
        int dataLength = 0;
        int offset = 12;

        while (offset + 8 <= bytes.length)
        {
            String id = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (bytes[offset + 4] & 0xFF) | ((bytes[offset + 5] & 0xFF) << 8)
                | ((bytes[offset + 6] & 0xFF) << 16) | ((bytes[offset + 7] & 0xFF) << 24);

            if (size < 0)
            {
                return null;
            }

            int body = offset + 8;

            if ("fmt ".equals(id) && body + 16 <= bytes.length)
            {
                format = (bytes[body] & 0xFF) | ((bytes[body + 1] & 0xFF) << 8);
                channels = (bytes[body + 2] & 0xFF) | ((bytes[body + 3] & 0xFF) << 8);
                sampleRate = (bytes[body + 4] & 0xFF) | ((bytes[body + 5] & 0xFF) << 8)
                    | ((bytes[body + 6] & 0xFF) << 16) | ((bytes[body + 7] & 0xFF) << 24);
                bits = (bytes[body + 14] & 0xFF) | ((bytes[body + 15] & 0xFF) << 8);
            }
            else if ("data".equals(id))
            {
                dataOffset = body;
                dataLength = Math.min(size, bytes.length - body);
            }

            offset = body + size + (size & 1);
        }

        if (dataOffset < 0 || channels <= 0 || sampleRate <= 0 || bits <= 0)
        {
            return null;
        }

        /* 3 = IEEE float, 1 = PCM. WAVE_FORMAT_EXTENSIBLE (0xFFFE) is not worth guessing at. */
        if (format != 1 && format != 3)
        {
            return null;
        }

        int bytesPerSample = Math.max(1, bits / 8);
        int blockAlign = channels * bytesPerSample;
        byte[] pcm = new byte[dataLength - dataLength % Math.max(1, blockAlign)];

        System.arraycopy(bytes, dataOffset, pcm, 0, pcm.length);

        return new Wave(format == 3 ? 3 : 1, channels, sampleRate, sampleRate * blockAlign, blockAlign, bits, pcm);
    }

    private static boolean startsWith(byte[] bytes, char... magic)
    {
        if (bytes.length < magic.length)
        {
            return false;
        }

        for (int i = 0; i < magic.length; i++)
        {
            if ((bytes[i] & 0xFF) != magic[i])
            {
                return false;
            }
        }

        return true;
    }

    /** Converts any decoded wave to 48 kHz stereo floats. */
    private static Source toSource(Wave wave)
    {
        Wave pcm = wave.bitsPerSample == 16 ? wave : wave.convertTo16();
        int channels = Math.max(1, pcm.numChannels);
        int bytesPerSample = Math.max(1, pcm.getBytesPerSample());
        int frameBytes = channels * bytesPerSample;
        int frames = pcm.data.length / Math.max(1, frameBytes);

        float[] left = new float[frames];
        float[] right = new float[frames];

        for (int frame = 0; frame < frames; frame++)
        {
            int base = frame * frameBytes;

            left[frame] = sample(pcm.data, base, bytesPerSample);
            right[frame] = channels > 1
                ? sample(pcm.data, base + bytesPerSample, bytesPerSample)
                : left[frame];
        }

        if (pcm.sampleRate == SAMPLE_RATE || pcm.sampleRate <= 0)
        {
            return new Source(left, right, frames);
        }

        return resample(left, right, pcm.sampleRate);
    }

    private static float sample(byte[] data, int offset, int bytesPerSample)
    {
        if (bytesPerSample == 1)
        {
            return ((data[offset] & 0xFF) - 128) / 128F;
        }

        int low = data[offset] & 0xFF;
        int high = bytesPerSample > 1 ? data[offset + 1] : 0;
        int value = (short) ((high << 8) | low);

        return value / 32768F;
    }

    /**
     * Linear resampling to 48 kHz.
     *
     * <p>Deliberately simple: this is a mixdown for a game film, not a mastering chain. Linear
     * interpolation is inaudible on the short clips the compositor is used for, and it keeps the
     * mixer dependency-free and deterministic for the tests.</p>
     */
    private static Source resample(float[] left, float[] right, int sourceRate)
    {
        double ratio = SAMPLE_RATE / (double) sourceRate;
        int frames = Math.max(1, (int) Math.floor(left.length * ratio));
        float[] outLeft = new float[frames];
        float[] outRight = new float[frames];

        for (int i = 0; i < frames; i++)
        {
            double source = i / ratio;
            int index = (int) source;
            double fraction = source - index;

            if (index >= left.length - 1)
            {
                outLeft[i] = left[left.length - 1];
                outRight[i] = right[right.length - 1];
                continue;
            }

            outLeft[i] = (float) (left[index] * (1D - fraction) + left[index + 1] * fraction);
            outRight[i] = (float) (right[index] * (1D - fraction) + right[index + 1] * fraction);
        }

        return new Source(outLeft, outRight, frames);
    }

    private static void warn(Log log, String message)
    {
        if (log != null)
        {
            log.warn(message);
        }
    }

    /** Convenience for the render queue: resolves clips under the project's assets folder. */
    public static Function<String, Path> resolver(Path assetRoot)
    {
        return (path) ->
        {
            String relative = path.startsWith("assets/") ? path.substring("assets/".length()) : path;

            return assetRoot.resolve(relative);
        };
    }

    /**
     * Source length of an audio file in seconds, or -1 when it cannot be decoded.
     *
     * <p>Called once at import: the timeline needs a real bar length, and "as long as the source"
     * is not something a bar can draw. Decoding is fine here — it happens on a user action, not per
     * frame.</p>
     */
    public static float probeDuration(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return -1F;
        }

        try
        {
            byte[] bytes = Files.readAllBytes(file);
            Wave wave = readWave(file, bytes);

            return wave == null ? -1F : wave.getDuration();
        }
        catch (Exception e)
        {
            return -1F;
        }
    }

    /** Diagnostics for the render log: how many clips would contribute. */
    public static List<String> describe(CompDocument comp)
    {
        List<String> lines = new ArrayList<>();

        if (comp == null || comp.audio == null)
        {
            return lines;
        }

        for (CompAudio clip : comp.audio)
        {
            if (clip == null)
            {
                continue;
            }

            lines.add(clip.name + " @ " + clip.start + "s  " + (clip.muted ? "(muted)" : "gain " + Math.round(clip.gain * 100F) + "%"));
        }

        return lines;
    }
}
