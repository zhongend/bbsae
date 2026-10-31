package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The zoom fix against BBS's real {@link Scale}, not against a model of it.
 *
 * <p>{@code Scale.zoom(delta, min, max)} <b>adds</b> {@code delta} to a value measured in pixels
 * per second, so the editor has to turn a multiplicative step into an additive delta. This test
 * drives the same object the timeline drives: fit a 12-second composition into a 720-pixel lane
 * (60 px/s), apply one step, and check the result is 1.35× — which is what "zoom in" means and
 * what the old {@code +1} could never achieve (it produced 61 px/s, a 1.7 % change).</p>
 */
public class CompTimelineZoomScaleTest
{
    private static Scale fitted()
    {
        Scale scale = new Scale(new Area(0, 0, 720, 300), ScrollDirection.HORIZONTAL);

        scale.view(0D, 12D);

        return scale;
    }

    @Test
    public void oneStepIsARealZoomOnTheRealScale()
    {
        Scale scale = fitted();
        double before = scale.getZoom();

        assertEquals(60D, before, 0.001D, "12 seconds in 720 px is 60 px/s");

        double target = UICompTrackTimeline.zoomTarget(before, 1D);

        scale.zoomAnchor(0.5F, target - before, 0.05D, 200_000D);

        assertEquals(target, scale.getZoom(), 0.001D, "the additive API must land exactly on the target");
        assertTrue(scale.getZoom() > before * 1.3D, "and the target must be a visible change");
    }

    @Test
    public void zoomOutAndBackRestoresTheFittedView()
    {
        Scale scale = fitted();

        for (int i = 0; i < 3; i++)
        {
            double before = scale.getZoom();

            scale.zoomAnchor(0.5F, UICompTrackTimeline.zoomTarget(before, -1D) - before, 0.05D, 200_000D);
        }

        for (int i = 0; i < 3; i++)
        {
            double before = scale.getZoom();

            scale.zoomAnchor(0.5F, UICompTrackTimeline.zoomTarget(before, 1D) - before, 0.05D, 200_000D);
        }

        assertEquals(60D, scale.getZoom(), 0.01D, "three steps out and three steps in must cancel");
    }

    /** The visible range must shrink when zooming in — the thing a user actually sees. */
    @Test
    public void zoomingInShowsLessTime()
    {
        Scale scale = fitted();
        double before = scale.getZoom();
        double visibleBefore = 720D / before;

        double target = UICompTrackTimeline.zoomTarget(before, 2D);

        scale.zoomAnchor(0F, target - before, 0.05D, 200_000D);

        double visibleAfter = 720D / scale.getZoom();

        assertTrue(visibleAfter < visibleBefore * 0.6D, "two steps in must show well under half the time");
        assertTrue(visibleAfter > 0.5D, "and still a sane amount of time");
    }
}
