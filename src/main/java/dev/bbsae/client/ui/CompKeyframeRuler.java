package dev.bbsae.client.ui;

import dev.bbsae.comp.CompTime;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.IntUnaryOperator;

/**
 * Frame-aware timeline ruler for the compositor's keyframe drawer.
 *
 * <h2>Two bugs, one cause</h2>
 * <p>BBS' {@code TimelineRulerRenderer} assumes its axis is in <b>ticks at 20 per second</b>: its
 * step tables are tick counts and its labels go through {@code TimeUtils.formatTime}. The
 * compositor's keyframe axis is in <b>video frames</b> ({@code doc.fps}, often 60). Feeding frames
 * to that renderer produced labels three times too large and, at sub-second zoom, repeated
 * {@code 0:00} labels that visibly overlapped.</p>
 *
 * <p>The previous fix drew a second full ruler from the {@code rulerRenderer} decorator. That
 * decorator runs <b>inside</b> the default ruler, so the default frame-unit labels were still
 * painted afterwards: two rulers, two label sets, permanent overlap. This class replaces the
 * default tick pass (via a mixin) for exactly the frame the decorator marked, instead of drawing
 * on top of it:</p>
 *
 * <ul>
 *   <li>step selection is in frames, snapped to round seconds when the editor is in seconds mode;</li>
 *   <li>labels use {@link CompTime#rulerLabel(int, float, boolean)} (hundredths of a second), so
 *       nearby labels cannot collapse to the same {@code 0:00} string;</li>
 *   <li>the label width of the actual start/end labels feeds the step selection, so the major gap
 *       is always wide enough for the text.</li>
 * </ul>
 */
public final class CompKeyframeRuler
{
    private static final int MIN_MAJOR_GAP = 44;
    private static final int MIN_LABEL_PADDING = 18;
    private static final int MIN_MINOR_GAP = 5;
    private static final int ITERATION_CAP = 8192;

    /** Nice 1-2-5 frame steps for frame-number labels. */
    private static final int[] STEP_FRAMES =
    {
        1, 2, 5, 10, 20, 50, 100, 200, 500,
        1000, 2000, 5000, 10000, 20000, 50000, 100000
    };

    /** Nice tick steps (20 per second); converted to whole frames for seconds labels. */
    private static final int[] STEP_TICKS =
    {
        1, 2, 5, 10, 20, 50, 100, 200, 500,
        1000, 2000, 5000, 10000, 20000, 50000, 100000
    };

    private static final float MAJOR_ALPHA = 0.70F;
    private static final float MINOR_ALPHA = 0.38F;
    private static final float LABEL_ALPHA = 0.72F;

    private CompKeyframeRuler()
    {}

    /**
     * Replaces {@code TimelineRulerRenderer.renderTicks} for the compositor's ruler.
     *
     * <p>The caller (the mixin) has already decided that this frame carries a frame-unit override;
     * this method clears it in a {@code finally}, so a later editor's ruler is never affected.</p>
     */
    public static void renderTicks(UIContext context, Area area, int startFrame, int timelineBottom,
                                   int visibleEx, IntUnaryOperator toGraphX, float fps, boolean seconds)
    {
        try
        {
            float safeFps = Math.max(1F, fps);
            double pxPerFrame = pixelsPerFrame(toGraphX);

            if (pxPerFrame <= 0D || area == null || context == null)
            {
                return;
            }

            int rightFrame = startFrame + (int) (area.w / pxPerFrame);
            FontRenderer font = context.batcher.getFont();
            String leftLabel = CompTime.rulerLabel(Math.max(0, startFrame), safeFps, seconds);
            String rightLabel = CompTime.rulerLabel(Math.max(0, rightFrame), safeFps, seconds);
            int labelWidth = Math.max(font.getWidth(leftLabel), font.getWidth(rightLabel));
            int minMajorPx = Math.max(MIN_MAJOR_GAP, labelWidth + MIN_LABEL_PADDING);
            int step = chooseStepFrames((int) Math.ceil(minMajorPx / pxPerFrame), safeFps, seconds);
            int minor = minorStep(step, pxPerFrame);
            int labelMargin = (int) Math.ceil(step * pxPerFrame);

            int majorColor = Colors.setA(BBSSettings.dividerColor(), MAJOR_ALPHA);
            int minorColor = Colors.setA(BBSSettings.dividerColor(), MINOR_ALPHA);
            int labelColor = Colors.setA(Colors.WHITE, LABEL_ALPHA);

            int lineBottom = timelineBottom - 1;
            int majorTop = area.y + 2;
            int minorTop = lineBottom - Math.max(4, Math.round((lineBottom - majorTop) * 0.4F));

            long first = Math.max(0L, (long) Math.floor(startFrame / (double) minor) * minor - step);

            context.batcher.beginBatch();

            try
            {
                for (long frame = first, i = 0; i < ITERATION_CAP; frame += minor, i++)
                {
                    int x = toGraphX.applyAsInt((int) frame);

                    if (x >= visibleEx)
                    {
                        break;
                    }

                    boolean major = frame % step == 0;

                    if (x >= area.x)
                    {
                        context.batcher.box(x, major ? majorTop : minorTop, x + 1, lineBottom,
                            major ? majorColor : minorColor);
                    }

                    if (major && x > area.x - labelMargin)
                    {
                        context.batcher.textShadow(CompTime.rulerLabel((int) frame, safeFps, seconds),
                            x + 4, area.y + 2, labelColor);
                    }
                }
            }
            finally
            {
                context.batcher.endBatch();
            }
        }
        finally
        {
            CompTimelineUnits.clear();
        }
    }

    /**
     * The major step in frames.
     *
     * <p>Seconds mode converts the BBS tick steps (1/20 s, 2/20 s, …) to whole frames, skipping a
     * step that does not divide into whole frames at this fps. That keeps labels on round
     * hundredths instead of drifting through 0.07, 0.13, … at 30 fps.</p>
     */
    static int chooseStepFrames(int desiredFrames, float fps, boolean seconds)
    {
        int desired = Math.max(1, desiredFrames);

        if (!seconds)
        {
            return niceStep(desired, STEP_FRAMES);
        }

        int safeFps = Math.max(1, Math.round(fps));
        int fallback = -1;

        for (int tickStep : STEP_TICKS)
        {
            int frames = Math.round(tickStep * safeFps / 20F);

            if (frames < 1)
            {
                frames = 1;
            }

            if (fallback < 0)
            {
                fallback = frames;
            }

            /* Whole frames only: a 30 fps project cannot show 1.5-frame steps on a pixel axis. */
            if (tickStep * safeFps % 20 == 0 && frames >= desired)
            {
                return frames;
            }
        }

        return fallback < 1 ? desired : fallback;
    }

    /**
     * A 1-2-5 step at least {@code desired} frames, used by frame-number labels and as the
     * seconds-mode fallback.
     */
    static int niceStep(int desired, int[] steps)
    {
        for (int step : steps)
        {
            if (step >= desired)
            {
                return step;
            }
        }

        return steps[steps.length - 1];
    }

    static int minorStep(int stepFrames, double pxPerFrame)
    {
        int minor = Math.max(1, stepFrames / subdivisions(stepFrames));

        return minor * pxPerFrame < MIN_MINOR_GAP ? stepFrames : minor;
    }

    private static int subdivisions(int step)
    {
        int decade = 1;

        while (decade * 10 <= step)
        {
            decade *= 10;
        }

        return switch (Math.round(step / (float) decade))
        {
            case 2, 4 -> 4;
            default -> 5;
        };
    }

    /**
     * Pixels per frame from the axis projection, probed over a wide span so integer rounding at
     * low zoom cannot make the step selection oscillate.
     */
    static double pixelsPerFrame(IntUnaryOperator toGraphX)
    {
        int probe = 100000;

        return (toGraphX.applyAsInt(probe) - toGraphX.applyAsInt(0)) / (double) probe;
    }
}
