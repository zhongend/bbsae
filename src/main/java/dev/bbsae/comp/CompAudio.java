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
    /** Clip length in seconds; &lt;= 0 = natural length. */
    public float duration = -1F;
    /** Linear gain, 1 = unity. */
    public float gain = 1F;
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
        audio.fadeIn = this.fadeIn;
        audio.fadeOut = this.fadeOut;
        audio.muted = this.muted;
        audio.loop = this.loop;

        return audio;
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
        return this.duration > 0F
            ? this.duration
            : Math.max(0F, compDuration - this.start);
    }
}
