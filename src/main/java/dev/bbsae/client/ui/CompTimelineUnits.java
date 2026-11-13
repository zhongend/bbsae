package dev.bbsae.client.ui;

/**
 * One-frame hand-off between the compositor's keyframe editor and BBS' ruler renderer.
 *
 * <p>{@code UIKeyframes.rulerRenderer} is a <b>decorator</b> called from inside
 * {@code TimelineRulerRenderer.render}, not a replacement for it. The compositor used to draw a
 * second complete ruler from that decorator with frame-to-tick conversion; the default ruler then
 * drew its own (frame-unit) labels on top, so two label sets overlapped and read as garbled text.
 * This class lets the decorator tell the one ruler what unit the axis is in instead of drawing a
 * second one.</p>
 *
 * <p>Render-thread only. The value is set immediately before the ruler's ticks are drawn and
 * cleared by {@link CompTimelineRuler} when they have been drawn. A stale value is harmless for
 * one frame (the next editor clears it), but it is still cleared eagerly.</p>
 */
public final class CompTimelineUnits
{
    private static float framesPerSecond;
    private static boolean secondsMode;

    private CompTimelineUnits()
    {}

    public static void set(float fps, boolean seconds)
    {
        framesPerSecond = Math.max(1F, fps);
        secondsMode = seconds;
    }

    public static float framesPerSecond()
    {
        return framesPerSecond;
    }

    public static boolean seconds()
    {
        return secondsMode;
    }

    public static void clear()
    {
        framesPerSecond = 0F;
        secondsMode = false;
    }
}
