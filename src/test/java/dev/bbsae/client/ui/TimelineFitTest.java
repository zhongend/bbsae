package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Fit the whole timeline" must map composition time [0, duration] exactly onto the lane.
 *
 * <p>The old fit ran once and cached the duration only, so a panel resize left the old
 * pixels-per-second: narrowing the lane cut the end off, widening it left a gap. BBS's
 * {@link Scale} also derives its shift from the anchor at the moment of {@code view()}, so setting
 * the anchor afterwards moved the range by half a screen. These tests drive the real Scale.</p>
 */
public class TimelineFitTest
{
    private static Scale lane(int width)
    {
        return new Scale(new Area(0, 0, width, 240), ScrollDirection.HORIZONTAL);
    }

    @Test
    public void fitMapsTheWholeDurationOntoTheLane()
    {
        int[] widths = {320, 810, 1600};
        float[] durations = {0.5F, 12F, 60F, 600F, 1800F};

        for (int width : widths)
        {
            for (float duration : durations)
            {
                Scale scale = lane(width);

                UICompTrackTimeline.applyFit(scale, duration, width);

                assertEquals(0D, scale.getMinValue(), 1.0E-6D,
                    "fit must start at composition time 0");
                assertEquals(duration, scale.getMaxValue(), 1.0E-4D,
                    "fit must end at composition time " + duration);
                assertEquals(width / (double) duration, scale.getZoom(), 1.0E-6D);
                assertEquals(0D, scale.to(0D), 0.5D);
                assertEquals(width, scale.to(duration), 0.5D);
            }
        }
    }

    @Test
    public void aPreviousAnchorCannotShiftAFittedView()
    {
        Scale scale = lane(800);

        /* This is the order-sensitive part: view() reads the anchor, so applying fit with 0.5
         * and only then resetting the anchor left the visible range at [d/2, 3d/2]. */
        scale.anchor(0.5F);
        UICompTrackTimeline.applyFit(scale, 12D, 800);

        assertEquals(0D, scale.getMinValue(), 1.0E-6D);
        assertEquals(12D, scale.getMaxValue(), 1.0E-6D);
    }

    @Test
    public void refittingAtANewWidthKeepsTheWholeDurationVisible()
    {
        Scale scale = lane(500);

        UICompTrackTimeline.applyFit(scale, 12D, 500);
        assertEquals(500D / 12D, scale.getZoom(), 1.0E-6D);

        scale.area = new Area(0, 0, 900, 240);
        UICompTrackTimeline.applyFit(scale, 12D, 900);

        assertEquals(900D / 12D, scale.getZoom(), 1.0E-6D,
            "a widened lane must recompute pixels-per-second, not keep the old zoom");
        assertEquals(12D, scale.getMaxValue(), 1.0E-6D);
    }

    @Test
    public void invalidDurationsDoNotProduceNaN()
    {
        Scale scale = lane(800);

        UICompTrackTimeline.applyFit(scale, Float.NaN, 800);
        UICompTrackTimeline.applyFit(scale, Float.POSITIVE_INFINITY, 800);
        UICompTrackTimeline.applyFit(scale, 0D, 800);

        assertEquals(true, Double.isFinite(scale.getZoom()));
        assertEquals(true, Double.isFinite(scale.getMinValue()));
        assertEquals(true, Double.isFinite(scale.getMaxValue()));
    }
}
