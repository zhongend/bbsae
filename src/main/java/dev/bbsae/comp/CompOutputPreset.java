package dev.bbsae.comp;

/**
 * Output module preset: the container/encoding half of a render, separate from render settings
 * (resolution/fps/range). This mirrors AE's render settings vs output module split.
 */
public class CompOutputPreset
{
    /** "video" (frames, optionally with audio) or "audio" (mixdown only). */
    public String kind = "video";
    public String id = "review_h264";
    public String name = "Review H.264";
    public String container = "mp4";
    public String codec = "libx264";
    public String extension = "mp4";
    public boolean alpha = false;
    public int crf = 18;
    public String bitrate = "";
    public String pixelFormat = "yuv420p";
    /** 0 means native composition resolution. */
    public int width = 0;
    public int height = 0;
    public float scale = 1F;
    public int fps = 0;
    /**
     * Legacy field from the first draft; never read. Kept so old files round-trip unchanged.
     */
    public String audio = "";
    /** Audio codec muxed into the container ("aac", "pcm_s16le", ...). */
    public String audioCodec = "aac";
    public String audioBitrate = "192k";
    public String postAction = "open_folder";
    public boolean keepFrames = false;

    public CompOutputPreset()
    {}

    public static CompOutputPreset reviewH264()
    {
        CompOutputPreset preset = new CompOutputPreset();
        preset.id = "review_h264";
        preset.name = "Review H.264";
        preset.container = "mp4";
        preset.codec = "libx264";
        preset.extension = "mp4";
        preset.crf = 20;
        preset.pixelFormat = "yuv420p";
        preset.audioCodec = "aac";
        preset.audioBitrate = "192k";
        return preset;
    }

    public static CompOutputPreset masterProRes()
    {
        CompOutputPreset preset = new CompOutputPreset();
        preset.id = "master_prores";
        preset.name = "Master ProRes 422 HQ";
        preset.container = "mov";
        preset.codec = "prores_ks";
        preset.extension = "mov";
        preset.bitrate = "220M";
        preset.pixelFormat = "yuv422p10le";
        preset.crf = 0;
        preset.audioCodec = "pcm_s16le";
        preset.audioBitrate = "";
        return preset;
    }

    public static CompOutputPreset alphaProRes()
    {
        CompOutputPreset preset = new CompOutputPreset();
        preset.id = "alpha_prores4444";
        preset.name = "Comp Alpha ProRes 4444";
        preset.container = "mov";
        preset.codec = "prores_ks";
        preset.extension = "mov";
        preset.alpha = true;
        preset.pixelFormat = "yuva444p10le";
        preset.crf = 0;
        preset.audioCodec = "pcm_s16le";
        preset.audioBitrate = "";
        return preset;
    }

    /**
     * Audio-only module: mixes the composition's audio track and writes a 48 kHz stereo WAV.
     * No GPU pass, no frames — an audio export must not cost a video render.
     */
    public static CompOutputPreset audioWav()
    {
        CompOutputPreset preset = new CompOutputPreset();

        preset.id = "audio_wav";
        preset.name = "Audio WAV 48k";
        preset.kind = "audio";
        preset.container = "wav";
        preset.codec = "pcm_s16le";
        preset.extension = "wav";
        preset.pixelFormat = "";
        preset.audioCodec = "pcm_s16le";
        preset.audioBitrate = "";
        preset.crf = 0;
        preset.keepFrames = true;
        preset.postAction = "open_folder";

        return preset;
    }

    /** Audio-only module that encodes the mixdown to a compressed file with FFmpeg. */
    public static CompOutputPreset audioM4a()
    {
        CompOutputPreset preset = audioWav();

        preset.id = "audio_m4a";
        preset.name = "Audio M4A (AAC 192k)";
        preset.container = "m4a";
        preset.extension = "m4a";
        preset.audioCodec = "aac";
        preset.audioBitrate = "192k";

        return preset;
    }

    public static CompOutputPreset pngSequence()
    {
        CompOutputPreset preset = new CompOutputPreset();
        preset.id = "png_sequence";
        preset.name = "PNG Sequence (Alpha)";
        preset.container = "png";
        preset.codec = "png";
        preset.extension = "png";
        preset.alpha = true;
        preset.pixelFormat = "rgba";
        preset.keepFrames = true;
        preset.postAction = "open_folder";
        return preset;
    }
}
