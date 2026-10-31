package dev.bbsae.comp;

/** Formatting helpers for the editor's time readout and film-friendly marker labels. */
public final class CompTime
{
    /**
     * Upper bound for one playback step. A frame normally advances by 4–20 ms; anything above
     * half a second means the render thread was stalled (alt-tab, GC, a modal dialog) and adding
     * the full stall would teleport the playhead far past where the user expects it.
     */
    public static final float MAX_PLAYBACK_STEP = 0.5F;

    private CompTime()
    {}

    /**
     * Real elapsed playback time between two {@link System#nanoTime()} samples.
     *
     * <p>This replaces accumulating Minecraft's {@code tickDelta}. That value is the fraction of
     * a client tick that has elapsed since the last tick, not the time since the last frame: at
     * 144 fps the samples average ~0.5 of a tick, so a compositor that added {@code tickDelta/20}
     * per frame ran its preview at 144 × 0.5 / 20 = 3.6× the film's speed. BBS's own player
     * advances at 20 ticks per second, so the editor's clock has to be wall-clock based too.</p>
     *
     * <p>Negative or paused intervals return zero; very long stalls are clamped to
     * {@link #MAX_PLAYBACK_STEP}.</p>
     */
    public static float playbackDelta(long previousNanos, long nowNanos)
    {
        if (previousNanos <= 0L || nowNanos <= previousNanos)
        {
            return 0F;
        }

        float delta = (nowNanos - previousNanos) / 1.0e9F;

        return Math.max(0F, Math.min(MAX_PLAYBACK_STEP, delta));
    }

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
