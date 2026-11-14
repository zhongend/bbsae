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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        /* One decode per physical file per mix. A project may use the same music bed twice (an
         * intro and a reprise); decoding it twice was pure latency and memory. */
        Map<String, Source> sources = new HashMap<>();
        Map<String, Source> retimed = new HashMap<>();
        Map<String, Source> shifted = new HashMap<>();

        for (CompAudio clip : comp.audio)
        {
            if (clip == null || clip.muted || clip.path == null || clip.path.isBlank())
            {
                continue;
            }

            Source source = decode(resolver, clip, log, sources);

            if (source == null)
            {
                continue;
            }

            float speed = clip.safeSpeed();

            if (Math.abs(speed - 1F) > 0.001F)
            {
                String key = sourceKey(clip.path, source) + "|speed:" + speed;
                final Source input = source;

                source = retimed.computeIfAbsent(key, (ignored) -> timeStretch(input, speed));
            }

            float pitch = clip.safePitch();

            if (Math.abs(pitch) > 0.001F)
            {
                String key = sourceKey(clip.path, source) + "|pitch:" + pitch;
                final Source input = source;

                source = shifted.computeIfAbsent(key, (ignored) -> pitchShift(input, pitch));
            }

            if (mixClip(out, total, clip, source, start, comp.duration))
            {
                any = true;
            }
        }

        return any ? out : new float[0];
    }

    private static boolean mixClip(float[] out, int total, CompAudio clip, Source source,
                                   float exportStart, float compDuration)
    {
        float speed = clip.safeSpeed();
        float clipStart = clip.start;
        float sourceOffsetSeconds = Math.max(0F, clip.offset) / speed;
        float clipLength = clip.duration > 0F
            ? clip.duration
            : Math.max(0F, source.duration() - sourceOffsetSeconds);

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
        int sourceOffset = Math.round((sourceOffsetSeconds + seek) * SAMPLE_RATE);
        int loopStart = Math.round(sourceOffsetSeconds * SAMPLE_RATE);
        int sourceFrames = source.frames;

        /* An in-point at or past the end of the decoded file has nothing to play — not even to
         * loop. (It used to compute loopLength = 1 and then index past the array, and the thrown
         * exception dropped the WHOLE mix in the caller, silently removing all audio.) */
        if (sourceFrames <= 0 || sourceOffset >= sourceFrames)
        {
            return false;
        }

        if (loopStart < 0 || loopStart >= sourceFrames)
        {
            loopStart = 0;
        }

        int loopLength = Math.max(1, sourceFrames - loopStart);
        float gain = Math.max(0F, clip.gain);
        float fadeIn = Math.max(0F, clip.fadeIn);
        float fadeOut = Math.max(0F, clip.fadeOut);
        float pan = clip.safePan();
        float leftGain = pan > 0F ? 1F - pan : 1F;
        float rightGain = pan < 0F ? 1F + pan : 1F;
        boolean any = false;

        for (int frame = firstFrame; frame < lastFrame; frame++)
        {
            int local = frame - firstFrame;
            long index = sourceOffset + (long) local;

            if (index >= sourceFrames)
            {
                if (!clip.loop)
                {
                    break;
                }

                /* Loop from the clip's own in-point, not from the beginning of the file: a clip
                 * trimmed into a 12 s bed must repeat that region, not jump back to 0. */
                index = loopStart + (index - loopStart) % loopLength;
            }

            if (index < 0 || index >= sourceFrames)
            {
                break;
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

            int sample = (int) index;

            out[frame * 2] += source.left[sample] * envelope * leftGain;
            out[frame * 2 + 1] += source.right[sample] * envelope * rightGain;
            any = true;
        }

        return any;
    }

    /**
     * Time-stretches a source by {@code speed} without changing its pitch.
     *
     * <p>Speed used to be implemented by skipping samples in the mix loop. That makes a 2x clip
     * also play an octave up, which is why "变速" and "音调" could not be independent. Grains are
     * copied 1:1 (no resampling) and the input hop is multiplied by the speed, so the waveform is
     * compressed/expanded while every grain keeps its original frequency.</p>
     */
    static Source timeStretch(Source input, float speed)
    {
        if (input.frames < 4 || Math.abs(speed - 1F) < 0.001F)
        {
            return input;
        }

        speed = Math.max(0.05F, Math.min(8F, speed));

        /* Never allocate more than the mix's own upper bound: a 3-minute file at 0.05x would be
         * an hour-long buffer for a clip that no composition can use. */
        float floorSpeed = input.frames / (float) (SAMPLE_RATE * MAX_MIX_SECONDS);
        speed = Math.max(speed, Math.max(0.05F, floorSpeed));

        int outputFrames = Math.max(1, Math.round(input.frames / speed));
        int grain = Math.min(PITCH_GRAIN, Math.max(32, input.frames));
        int hop = Math.max(1, grain / 2);
        float[] left = new float[outputFrames];
        float[] right = new float[outputFrames];
        float[] weight = new float[outputFrames];
        float[] window = new float[grain];

        for (int i = 0; i < grain; i++)
        {
            window[i] = (float) (0.5D - 0.5D * Math.cos(2D * Math.PI * i / (grain - 1D)));
        }

        for (int outStart = 0; outStart < outputFrames; outStart += hop)
        {
            int inStart = (int) Math.round(outStart * (double) speed);
            int count = Math.min(grain, Math.min(outputFrames - outStart, input.frames - inStart));

            if (count <= 0)
            {
                continue;
            }

            for (int i = 0; i < count; i++)
            {
                float w = window[i];

                left[outStart + i] += input.left[inStart + i] * w;
                right[outStart + i] += input.right[inStart + i] * w;
                weight[outStart + i] += w;
            }
        }

        for (int i = 0; i < outputFrames; i++)
        {
            float w = weight[i];

            if (w > 0.0001F)
            {
                left[i] /= w;
                right[i] /= w;
            }
        }

        return new Source(left, right, outputFrames);
    }

    /**
     * Granular overlap-add pitch shifter.
     *
     * <p>Resampling alone would make +12 semitones play twice as fast. Grains of {@value #PITCH_GRAIN}
     * samples are read at the pitch ratio and laid down at the original hop, so the clip keeps its
     * timeline length while the tone moves. This is the classic cheap granular approach: good
     * enough for a Minecraft film's dialogue/music bed, deterministic, and dependency-free.</p>
     */
    private static final int PITCH_GRAIN = 2048;

    static Source pitchShift(Source input, float semitones)
    {
        int frames = input.frames;

        if (frames < 4 || Math.abs(semitones) < 0.001F)
        {
            return input;
        }

        double ratio = Math.pow(2D, Math.max(-48F, Math.min(48F, semitones)) / 12D);
        int grain = Math.min(PITCH_GRAIN, Math.max(32, frames));
        int hop = Math.max(1, grain / 2);
        float[] left = new float[frames];
        float[] right = new float[frames];
        float[] weight = new float[frames];
        float[] window = new float[grain];

        for (int i = 0; i < grain; i++)
        {
            window[i] = (float) (0.5D - 0.5D * Math.cos(2D * Math.PI * i / (grain - 1D)));
        }

        for (int start = 0; start < frames; start += hop)
        {
            int count = Math.min(grain, frames - start);

            for (int i = 0; i < count; i++)
            {
                double source = start + i * ratio;
                int index = (int) Math.floor(source);
                float fraction = (float) (source - index);

                if (index >= frames - 1)
                {
                    index = frames - 1;
                    fraction = 0F;
                }

                if (index < 0)
                {
                    index = 0;
                    fraction = 0F;
                }

                float valueL = input.left[index];
                float valueR = input.right[index];

                if (index + 1 < frames)
                {
                    valueL += (input.left[index + 1] - valueL) * fraction;
                    valueR += (input.right[index + 1] - valueR) * fraction;
                }

                float w = window[i];

                left[start + i] += valueL * w;
                right[start + i] += valueR * w;
                weight[start + i] += w;
            }
        }

        for (int i = 0; i < frames; i++)
        {
            float w = weight[i];

            if (w > 0.0001F)
            {
                left[i] /= w;
                right[i] /= w;
            }
        }

        return new Source(left, right, frames);
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

    static final class Source
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

    private static Source decode(Resolver resolver, CompAudio clip, Log log, Map<String, Source> cache)
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

        String key;

        try
        {
            key = file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis()
                + "|" + Files.size(file);
        }
        catch (Exception e)
        {
            key = file.toAbsolutePath().toString();
        }

        Source cached = cache.get(key);

        if (cached != null)
        {
            return cached;
        }

        try
        {
            byte[] bytes = Files.readAllBytes(file);
            Wave wave = readWave(file, bytes);

            if (wave == null)
            {
                warn(log, "无法解码音频（WAV/OGG 原生；MP3/FLAC/M4A/AAC/OPUS/WMA/AIFF 需 FFmpeg）：" + clip.path);

                return null;
            }

            Source source = toSource(wave);

            cache.put(key, source);

            return source;
        }
        catch (Exception e)
        {
            warn(log, "音频解码失败：" + clip.path + " (" + e.getMessage() + ")");

            return null;
        }
    }

    private static String sourceKey(String path, Source source)
    {
        return path + "|" + source.frames;
    }

    public static Wave readWave(Path file) throws Exception
    {
        return readWave(file, Files.readAllBytes(file));
    }

    private static Wave readWave(Path file, byte[] bytes) throws Exception
    {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

        if (name.endsWith(".wav") || startsWith(bytes, 'R', 'I', 'F', 'F'))
        {
            /* Our parser handles 8/16/24/32-bit PCM and 32-bit float and does not need a chunk
             * terminator. BBS's reader is kept as the fallback for exotic chunks it understands
             * (lists/cues) and for WAVE_FORMAT_EXTENSIBLE. Trying the small parser first also
             * avoids logging an EOF for every ordinary 8-bit file. */
            Wave parsed = parseWav(bytes);

            if (parsed != null)
            {
                return parsed;
            }

            try (InputStream in = new ByteArrayInputStream(bytes))
            {
                return new WaveReader().read(in);
            }
        }

        if (name.endsWith(".ogg") || startsWith(bytes, 'O', 'g', 'g', 'S'))
        {
            try (InputStream in = new ByteArrayInputStream(bytes))
            {
                return VorbisReader.read(new Link("bbsae", file.getFileName().toString()), in);
            }
        }

        /*
         * More formats without copying the source into the project: ask FFmpeg for raw PCM on
         * stdout and build the Wave in memory. The link stays external: no file is written under
         * assets/. MP3/FLAC/M4A/AAC/OPUS/WMA/AIFF are all handled by the same path.
         */
        return decodeWithFfmpeg(file);
    }

    /**
     * Decodes any FFmpeg-readable audio file to raw stereo 16-bit 48 kHz PCM on stdout. Raw PCM
     * avoids the non-seekable WAV header problem (FFmpeg writes 0xFFFFFFFF sizes on a pipe, which
     * BBS's WaveReader correctly rejects); the Wave object is constructed directly.
     */
    private static Wave decodeWithFfmpeg(Path file)
    {
        String ffmpeg = mchorse.bbs_mod.utils.FFMpegUtils.getFFMPEG();

        if (ffmpeg == null || ffmpeg.isBlank())
        {
            return null;
        }

        try
        {
            Process process = new ProcessBuilder(
                ffmpeg,
                "-nostdin",
                "-v", "error",
                "-i", file.toAbsolutePath().toString(),
                "-vn",
                "-f", "s16le",
                "-acodec", "pcm_s16le",
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(CHANNELS),
                "-"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();

            if (exit != 0 || output.length == 0)
            {
                return null;
            }

            int blockAlign = CHANNELS * 2;

            /* Drop a partial final frame; every sample must be a complete L/R pair. */
            if (output.length % blockAlign != 0)
            {
                byte[] aligned = new byte[output.length - output.length % blockAlign];
                System.arraycopy(output, 0, aligned, 0, aligned.length);
                output = aligned;
            }

            return new Wave(1, CHANNELS, SAMPLE_RATE, SAMPLE_RATE * blockAlign, blockAlign, 16, output);
        }
        catch (Exception e)
        {
            return null;
        }
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
        int channels = Math.max(1, wave.numChannels);
        int bits = Math.max(8, wave.bitsPerSample);
        int bytesPerSample = Math.max(1, bits / 8);
        int frameBytes = channels * bytesPerSample;
        int frames = wave.data == null ? 0 : wave.data.length / Math.max(1, frameBytes);

        float[] left = new float[frames];
        float[] right = new float[frames];
        boolean floatFormat = wave.audioFormat == 3;

        for (int frame = 0; frame < frames; frame++)
        {
            int base = frame * frameBytes;

            left[frame] = sample(wave.data, base, bits, floatFormat);
            right[frame] = channels > 1
                ? sample(wave.data, base + bytesPerSample, bits, floatFormat)
                : left[frame];
        }

        if (wave.sampleRate == SAMPLE_RATE || wave.sampleRate <= 0)
        {
            return new Source(left, right, frames);
        }

        return resample(left, right, wave.sampleRate);
    }

    /**
     * One sample in -1..1 for 8/16/24/32-bit PCM and 32-bit IEEE float.
     *
     * <p>The old conversion delegated non-16-bit formats to BBS's {@code Wave.convertTo16()},
     * which cannot handle 8-bit PCM (it reads four bytes from a one-byte buffer) and treats every
     * 32-bit sample as integer. An 8-bit WAV therefore produced no waveform AND no playback —
     * exactly the kind of "the file is valid but the editor is silent" bug this method removes.</p>
     */
    private static float sample(byte[] data, int offset, int bits, boolean floatFormat)
    {
        if (offset < 0 || offset >= data.length)
        {
            return 0F;
        }

        if (bits <= 8)
        {
            return ((data[offset] & 0xFF) - 128) / 128F;
        }

        if (bits <= 16)
        {
            int low = data[offset] & 0xFF;
            int high = offset + 1 < data.length ? data[offset + 1] & 0xFF : 0;
            short value = (short) ((high << 8) | low);

            return value / 32768F;
        }

        if (bits <= 24)
        {
            int b0 = data[offset] & 0xFF;
            int b1 = offset + 1 < data.length ? data[offset + 1] & 0xFF : 0;
            int b2 = offset + 2 < data.length ? data[offset + 2] : 0;
            int value = (b2 << 16) | (b1 << 8) | b0;

            return value / 8388608F;
        }

        int b0 = data[offset] & 0xFF;
        int b1 = offset + 1 < data.length ? data[offset + 1] & 0xFF : 0;
        int b2 = offset + 2 < data.length ? data[offset + 2] & 0xFF : 0;
        int b3 = offset + 3 < data.length ? data[offset + 3] & 0xFF : 0;
        int intValue = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);

        if (floatFormat)
        {
            return Float.intBitsToFloat(intValue);
        }

        return intValue / 2147483648F;
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
        /* Empty clips must not index left[-1] (index >= length-1 is true for 0 and -1). */
        if (left.length == 0 || right.length == 0)
        {
            return new Source(left, right, 0);
        }

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
            if (path.startsWith("external:"))
            {
                return Path.of(path.substring("external:".length()));
            }

            Path direct = Path.of(path);

            if (direct.isAbsolute())
            {
                return direct;
            }

            String relative = path.startsWith("assets/") ? path.substring("assets/".length()) : path;

            return assetRoot.resolve(relative);
        };
    }

    /** Resolver for a real project, including the old project-relative spellings. */
    public static Resolver projectResolver(String projectId)
    {
        return (path) -> dev.bbsae.comp.CompStore.resolveAsset(projectId, path);
    }

    /**
     * Source length of an audio file in seconds, or -1 when it cannot be decoded.
     *
     * <p>Called at import: the timeline needs a real bar length, and "as long as the source"
     * is not something a bar can draw. This is deliberately cheaper than a full decode:</p>
     * <ul>
     *   <li>a RIFF header answers for WAV without reading the samples;</li>
     *   <li>FFmpeg's banner answers for everything else in one short process;</li>
     *   <li>only when FFmpeg is unavailable does it fall back to reading the whole file.</li>
     * </ul>
     */
    public static float probeDuration(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return -1F;
        }

        try
        {
            byte[] head = readHead(file, 256 * 1024);

            if (startsWith(head, 'R', 'I', 'F', 'F'))
            {
                float wav = parseWavHeaderDuration(head);

                if (wav > 0F)
                {
                    return wav;
                }
            }

            float ffmpeg = probeDurationWithFfmpeg(file);

            if (ffmpeg > 0F)
            {
                return ffmpeg;
            }

            byte[] bytes = Files.readAllBytes(file);
            Wave wave = readWave(file, bytes);

            return wave == null ? -1F : wave.getDuration();
        }
        catch (Exception e)
        {
            return -1F;
        }
    }

    private static byte[] readHead(Path file, int max) throws IOException
    {
        try (InputStream in = Files.newInputStream(file))
        {
            return in.readNBytes(max);
        }
    }

    /**
     * Declared length of a RIFF/WAVE header, in seconds, without reading the samples.
     * Returns -1 for anything that is not a complete-enough PCM header.
     */
    private static float parseWavHeaderDuration(byte[] bytes)
    {
        if (bytes == null || bytes.length < 44)
        {
            return -1F;
        }

        int channels = 0;
        int sampleRate = 0;
        int byteRate = 0;
        int blockAlign = 0;
        int dataLength = -1;
        int offset = 12;

        while (offset + 8 <= bytes.length)
        {
            String id = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (bytes[offset + 4] & 0xFF) | ((bytes[offset + 5] & 0xFF) << 8)
                | ((bytes[offset + 6] & 0xFF) << 16) | ((bytes[offset + 7] & 0xFF) << 24);
            int body = offset + 8;

            if ("fmt ".equals(id) && body + 16 <= bytes.length)
            {
                channels = (bytes[body + 2] & 0xFF) | ((bytes[body + 3] & 0xFF) << 8);
                sampleRate = (bytes[body + 4] & 0xFF) | ((bytes[body + 5] & 0xFF) << 8)
                    | ((bytes[body + 6] & 0xFF) << 16) | ((bytes[body + 7] & 0xFF) << 24);
                byteRate = (bytes[body + 8] & 0xFF) | ((bytes[body + 9] & 0xFF) << 8)
                    | ((bytes[body + 10] & 0xFF) << 16) | ((bytes[body + 11] & 0xFF) << 24);
                blockAlign = (bytes[body + 12] & 0xFF) | ((bytes[body + 13] & 0xFF) << 8);
            }
            else if ("data".equals(id))
            {
                dataLength = size;
            }

            if (size < 0)
            {
                return -1F;
            }

            offset = body + size + (size & 1);
        }

        if (dataLength <= 0 || channels <= 0 || sampleRate <= 0 || blockAlign <= 0)
        {
            return -1F;
        }

        int bytesPerSecond = byteRate > 0 ? byteRate : sampleRate * blockAlign;

        return dataLength / (float) Math.max(1, bytesPerSecond);
    }

    /**
     * Reads {@code Duration: hh:mm:ss.xx} from FFmpeg's input banner.
     *
     * <p>FFmpeg exits with status 1 when only probing (there is no output), which is expected;
     * only the banner matters.</p>
     */
    public static float probeDurationWithFfmpeg(Path file)
    {
        String ffmpeg = mchorse.bbs_mod.utils.FFMpegUtils.getFFMPEG();

        if (ffmpeg == null || ffmpeg.isBlank())
        {
            return -1F;
        }

        try
        {
            Process process = new ProcessBuilder(ffmpeg, "-nostdin", "-hide_banner", "-i", file.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
            String output;

            try (InputStream in = process.getInputStream())
            {
                byte[] bytes = in.readAllBytes();

                output = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }

            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();

            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)")
                .matcher(output);

            if (matcher.find())
            {
                return Integer.parseInt(matcher.group(1)) * 3600F
                    + Integer.parseInt(matcher.group(2)) * 60F
                    + Float.parseFloat(matcher.group(3));
            }
        }
        catch (Exception e)
        {
            return -1F;
        }

        return -1F;
    }

    /**
     * Peak envelope of a file for the timeline, independent of any clip trim.
     *
     * <p>The previous waveform drew the whole file across the clip bar, so trimming a clip made
     * the picture lie. The editor now slices this envelope by the clip's source window.</p>
     */
    public static float[] peakEnvelope(Path file, int buckets)
    {
        if (file == null || buckets < 2 || !Files.isRegularFile(file))
        {
            return null;
        }

        try
        {
            Wave wave = readWave(file);
            Source source = wave == null ? null : toSource(wave);

            if (source == null || source.frames <= 0)
            {
                return null;
            }

            float[] peaks = new float[buckets];
            int frames = source.frames;

            for (int frame = 0; frame < frames; frame++)
            {
                int bucket = (int) Math.min(buckets - 1, (long) frame * buckets / frames);
                float level = Math.max(Math.abs(source.left[frame]), Math.abs(source.right[frame]));

                if (level > peaks[bucket])
                {
                    peaks[bucket] = level;
                }
            }

            return peaks;
        }
        catch (Exception e)
        {
            return null;
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
