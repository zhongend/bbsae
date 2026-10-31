package dev.bbsae.client.ui;

import dev.bbsae.comp.CompAudio;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeline's two "nothing happens" bugs, pinned as arithmetic.
 *
 * <p>The zoom bug was a unit error: BBS's {@code Scale.zoom} <b>adds</b> to a value measured in
 * pixels per second, and the editor passed ±1. At a fitted 12-second composition (~67 px/s) that is
 * a 1.5 % change — indistinguishable from a dead key. A zoom step must be relative, and the test
 * below states it in those terms: one step must be a visible fraction of the current zoom, not a
 * constant number of pixels.</p>
 */
public class CompTimelineZoomTest
{
    private static final double FIT = 810D / 12D; // 12-second comp in an 810 px lane

    @Test
    public void oneStepIsThirtyFivePercent()
    {
        assertEquals(FIT * 1.35D, UICompTrackTimeline.zoomTarget(FIT, 1D), 0.001D);
        assertEquals(FIT / 1.35D, UICompTrackTimeline.zoomTarget(FIT, -1D), 0.001D);
    }

    @Test
    public void stepsAreReversible()
    {
        double out = UICompTrackTimeline.zoomTarget(FIT, 4D);
        double back = UICompTrackTimeline.zoomTarget(out, -4D);

        assertEquals(FIT, back, 0.001D, "zooming in and out must return to the same scale");
        assertEquals(FIT, UICompTrackTimeline.zoomTarget(FIT, 0D), 0.001D, "no step, no change");
    }

    /** The regression: at a typical zoom, a step has to be a visible change, not ±1 px/s. */
    @Test
    public void aStepIsVisibleAtATypicalZoom()
    {
        double after = UICompTrackTimeline.zoomTarget(FIT, 1D);

        assertTrue(after - FIT > FIT * 0.2D, "a step must change the scale by more than 20%");
        assertTrue(after - FIT < FIT * 0.5D, "a step must not overshoot either");
    }

    @Test
    public void zoomIsClamped()
    {
        assertEquals(200_000D, UICompTrackTimeline.zoomTarget(190_000D, 10D), 0.001D, "upper clamp");
        assertEquals(0.05D, UICompTrackTimeline.zoomTarget(0.06D, -10D), 0.001D, "lower clamp");
    }

    /** Audio clips with no explicit length play (and are drawn) to the end of the composition. */
    @Test
    public void audioLengthFallsBackToTheCompositionEnd()
    {
        CompAudio clip = new CompAudio("a", "A", "audio/a.wav");

        clip.start = 3F;

        assertEquals(9F, clip.length(12F), 0.001F);

        clip.duration = 2F;

        assertEquals(2F, clip.length(12F), 0.001F, "an explicit duration wins");

        clip.start = 20F;
        clip.duration = -1F;

        assertEquals(0F, clip.length(12F), 0.001F, "a clip past the end has no length");
    }
}
