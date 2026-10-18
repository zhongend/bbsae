package dev.bbsae.comp;

/** Formatting helpers for the editor's time readout and film-friendly marker labels. */
public final class CompTime
{
    private CompTime()
    {}

    public static String timecode(float seconds, float fps)
    {
        int totalFrames = Math.max(0, Math.round(seconds * Math.max(1F, fps)));
        int frames = totalFrames % Math.max(1, Math.round(fps));
        int totalSeconds = totalFrames / Math.max(1, Math.round(fps));
        int s = totalSeconds % 60;
        int m = (totalSeconds / 60) % 60;
        int h = totalSeconds / 3600;

        return String.format("%02d:%02d:%02d:%02d", h, m, s, frames);
    }

    public static String seconds(float seconds, float fps)
    {
        return String.format("%.2fs / f%d", seconds, Math.round(seconds * fps));
    }

    public static float clamp(float value, float min, float max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
