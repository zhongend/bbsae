package dev.bbsae.comp;

/**
 * One audio clip on a composition's audio track.
 *
 * <p>A compositor's audio is <b>timeline content</b>, not a render setting: a music bed, a
 * voice-over or a sound effect has an in-point and a duration just like a layer does. Storing it on
 * the document (rather than in the output preset) is what lets the same mix feed the preview, the
 * video export and a standalone WAV.</p>
 *
 * <p>Times are composition seconds. {@link #duration} &lt;= 0 means "use the rest of the source".</p>
 */
public class CompAudio
{
    public String id = "audio_1";
    public String name = "Audio";
    /** Project-relative path under the project's assets folder, e.g. {@code audio/bgm.wav}. */
    public String path = "";
    /** Composition time where the clip starts. */
    public float start = 0F;
    /** Offset into the source file. */
    public float offset = 0F;
    /** Clip length in timeline seconds; &lt;= 0 = natural length. */
    public float duration = -1F;
    /** Linear gain, 1 = unity. */
    public float gain = 1F;
    /**
     * Pitch shift in semitones, independent of {@link #speed}.
     *
     * <p>AE/Premiere's audio "pitch" is a musical transposition: the clip keeps its place and
     * length on the timeline while the source is shifted up or down. The mixer implements that
     * with a granular overlap-add resampler (see {@code CompAudioMixer}), so +12 really is one
     * octave up rather than "plays twice as fast".</p>
     */
    public float pitch = 0F;
    /**
     * Source seconds consumed per timeline second. 1 = real-time, 2 = double speed.
     *
     * <p>This is deliberately separate from {@link #pitch}: speed changes timing, pitch changes
     * tone. Both are applied by the same mix used by preview, WAV export and video export.</p>
     */
    public float speed = 1F;
    /** Stereo balance, -1 = hard left, 0 = centre, 1 = hard right. */
    public float pan = 0F;
    /**
     * Decoded source length in seconds, recorded at import. 0 = not probed yet.
     *
     * <p>It is what lets a clip without an explicit {@link #duration} draw a real bar and lets the
     * inspector change speed without forcing the user to compute the new duration by hand.</p>
     */
    public float sourceDuration = 0F;
    public float fadeIn = 0F;
    public float fadeOut = 0F;
    public boolean muted;
    public boolean loop;

    public CompAudio()
    {}

    public CompAudio(String id, String name, String path)
    {
        this.id = id;
        this.name = name;
        this.path = path;
    }

    public CompAudio copy()
    {
        CompAudio audio = new CompAudio();

        audio.id = this.id;
        audio.name = this.name;
        audio.path = this.path;
        audio.start = this.start;
        audio.offset = this.offset;
        audio.duration = this.duration;
        audio.gain = this.gain;
        audio.pitch = this.pitch;
        audio.speed = this.speed;
        audio.pan = this.pan;
        audio.sourceDuration = this.sourceDuration;
        audio.fadeIn = this.fadeIn;
        audio.fadeOut = this.fadeOut;
        audio.muted = this.muted;
        audio.loop = this.loop;

        return audio;
    }

    /** Speed clamped to the range the mixer can consume without exploding the sample count. */
    public float safeSpeed()
    {
        return Math.max(0.05F, Math.min(8F, this.speed <= 0F ? 1F : this.speed));
    }

    /** Pitch clamped to +/- four octaves, which is wider than any usable edit. */
    public float safePitch()
    {
        return Math.max(-48F, Math.min(48F, this.pitch));
    }

    /** Stereo balance clamped to -1..1. */
    public float safePan()
    {
        return Math.max(-1F, Math.min(1F, this.pan));
    }

    /** Source seconds this clip consumes at its current speed, or -1 when unknown. */
    public float sourceLength()
    {
        if (this.sourceDuration > 0F)
        {
            return Math.max(0F, this.sourceDuration - Math.max(0F, this.offset));
        }

        return -1F;
    }

    /**
     * Timeline length, in seconds, that the source can actually fill.
     *
     * <p>An explicit duration may be longer than the file (the user trimmed a clip out past its
     * end); the bar still shows the trimmed clip, but the waveform and the mixer only fill the
     * part that has samples.</p>
     */
    public float playableLength(float compDuration)
    {
        float length = this.length(compDuration);
        float source = this.sourceLength();

        if (source > 0F)
        {
            return Math.min(length, source / this.safeSpeed());
        }

        return length;
    }

    /**
     * Changes speed while keeping "this clip currently covers its whole source" true.
     *
     * <p>If the user had trimmed the clip, retiming must not silently undo that trim; only the
     * common untrimmed case is rescaled. The inspector calls this for the speed field, then the
     * user can still trim the result by hand.</p>
     */
    public void retimeForSpeed(float newSpeed)
    {
        float old = this.safeSpeed();
        float next = Math.max(0.05F, Math.min(8F, newSpeed));
        float source = this.sourceLength();
        boolean wholeSource = source > 0F && this.duration > 0F
            && Math.abs(this.duration - source / old) <= Math.max(0.05F, 2F / Math.max(1F, this.sourceDuration));

        this.speed = next;

        if (wholeSource)
        {
            this.duration = source / next;
        }
    }

    /**
     * Length in composition seconds as the timeline draws it.
     *
     * <p>{@code duration <= 0} means "as long as the source", and the source length is unknown
     * until it is decoded — the importer stores the real length, so this fallback only applies to
     * a hand-edited file. "Rest of the composition" is the only honest answer there: it is what the
     * mixer will produce.</p>
     */
    public float length(float compDuration)
    {
        if (this.duration > 0F)
        {
            return this.duration;
        }

        float source = this.sourceLength();

        if (source > 0F)
        {
            return Math.max(0F, source / this.safeSpeed());
        }

        return Math.max(0F, compDuration - this.start);
    }
}
