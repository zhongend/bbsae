package dev.bbsae.client.ui.layout;

import dev.bbsae.client.ui.layout.CompositorLayout.Inputs;
import dev.bbsae.client.ui.layout.CompositorLayout.Mode;
import dev.bbsae.client.ui.layout.CompositorLayout.Rect;
import dev.bbsae.client.ui.layout.CompositorLayout.Result;
import dev.bbsae.client.ui.layout.CompositorLayout.Splitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's panel geometry, tested as arithmetic.
 *
 * <p>These assertions encode the layout contract the UI depends on: nothing overlaps, nothing
 * leaves the panel, a splitter exists only where two panels meet, and the splitter under the
 * preview is never reachable from a side column (the root cause of "dragging data also dragged the
 * panel size").</p>
 */
class CompositorLayoutTest
{
    private static Inputs inputs(int width, int height)
    {
        return new Inputs(width, height, 190, 300, 250, true, true, false, 210, false);
    }

    @Test
    void noPanelOverlapsAndNothingLeavesTheArea()
    {
        int[] widths = {320, 360, 420, 640, 800, 860, 960, 1180, 1280, 1600, 1920};
        int[] heights = {260, 300, 420, 520, 720, 1080};

        for (int width : widths)
        {
            for (int height : heights)
            {
                Result result = CompositorLayout.compute(inputs(width, height));

                for (Rect rect : CompositorLayout.rects(result))
                {
                    if (rect.isEmpty())
                    {
                        continue;
                    }

                    assertTrue(rect.x() >= 0 && rect.y() >= 0, "negative origin at " + width + "x" + height);
                    assertTrue(rect.ex() <= width, "rect past the width at " + width + "x" + height + ": " + rect);
                    assertTrue(rect.ey() <= height, "rect past the height at " + width + "x" + height + ": " + rect);
                }

                if (!result.left().isEmpty() && !result.preview().isEmpty())
                {
                    assertTrue(result.left().ex() <= result.preview().x(),
                        "left column overlaps preview at " + width + "x" + height);
                }

                if (!result.preview().isEmpty() && !result.right().isEmpty())
                {
                    assertTrue(result.preview().ex() <= result.right().x(),
                        "preview overlaps inspector at " + width + "x" + height);
                }

                if (!result.body().isEmpty() && !result.timeline().isEmpty())
                {
                    assertTrue(result.body().ey() <= result.timeline().y(),
                        "body overlaps timeline at " + width + "x" + height);
                }

                assertTrue(result.preview().w() >= 1, "preview disappeared at " + width + "x" + height);
            }
        }
    }

    @Test
    void sideColumnBottomBelongsToTheColumnNotTheTimelineSplitter()
    {
        for (int width : new int[] {1280, 1600, 1920})
        {
            Result result = CompositorLayout.compute(inputs(width, 720));

            if (result.left().isEmpty())
            {
                continue;
            }

            int leftX = result.left().x() + result.left().w() / 2;
            int bottom = result.body().ey() - 1;

            assertEquals(Splitter.NONE, CompositorLayout.hitTest(result, leftX, bottom),
                "a point in the bottom of the left column must not be a splitter at " + width);

            int rightX = result.right().x() + result.right().w() / 2;

            assertEquals(Splitter.NONE, CompositorLayout.hitTest(result, rightX, bottom),
                "a point in the bottom of the right column must not be a splitter at " + width);

            /* The timeline splitter still exists above the preview itself. */
            int previewX = result.preview().x() + result.preview().w() / 2;
            int seamY = result.body().ey() + CompositorLayout.GAP / 2;

            assertEquals(Splitter.TIMELINE, CompositorLayout.hitTest(result, previewX, seamY),
                "the preview bottom edge must still resize the timeline at " + width);
        }
    }

    @Test
    void columnsAreClampedToTheirSemanticMinimumsWhenThereIsRoom()
    {
        Result wide = CompositorLayout.compute(inputs(1920, 1080));

        assertEquals(190, wide.effectiveLeft());
        assertEquals(300, wide.effectiveRight());
        assertEquals(250, wide.effectiveTimeline());

        /* Asking for absurdly small columns snaps them to the content minimum. */
        Inputs tiny = new Inputs(1920, 1080, 10, 20, 250, true, true, false, 210, false);
        Result snapped = CompositorLayout.compute(tiny);

        assertTrue(snapped.effectiveLeft() >= 140);
        assertTrue(snapped.effectiveRight() >= 296);
    }

    @Test
    void hiddenColumnsGiveTheirSpaceToThePreview()
    {
        Inputs none = new Inputs(1600, 720, 190, 300, 250, false, false, false, 210, false);
        Result result = CompositorLayout.compute(none);

        assertTrue(result.left().isEmpty());
        assertTrue(result.right().isEmpty());
        assertEquals(1600, result.preview().w());
        assertTrue(result.leftSplit().isEmpty());
        assertTrue(result.rightSplit().isEmpty());
    }

    @Test
    void quickPanelTakesFromTheTimelineOnly()
    {
        Inputs withQuick = new Inputs(1920, 1080, 190, 300, 250, true, true, true, 210, false);
        Result result = CompositorLayout.compute(withQuick);

        assertFalse(result.quick().isEmpty());
        assertEquals(210, result.quick().w());
        assertEquals(1920, result.quick().ex());
        assertEquals(1920 - 210, result.timeline().w());
        /* The splitter sits above the preview only, never above the quick panel. */
        assertEquals(result.preview().w(), result.timelineSplit().w());
        assertEquals(result.preview().x(), result.timelineSplit().x());
    }

    @Test
    void shortWindowsKeepThePreviewBodyAlive()
    {
        for (int height = 120; height <= 520; height += 20)
        {
            Result result = CompositorLayout.compute(inputs(1280, height));

            if (height - CompositorLayout.HEADER_H - CompositorLayout.STATUS_H < CompositorLayout.MIN_BODY + 1)
            {
                assertTrue(result.timeline().isEmpty() || result.body().isEmpty(),
                    "impossible height must not create overlapping strips at " + height);
                continue;
            }

            assertTrue(result.body().h() >= Math.min(CompositorLayout.MIN_BODY, result.body().h()),
                "body height became negative at " + height);
            assertTrue(result.body().h() >= 0);

            if (!result.body().isEmpty() && !result.timeline().isEmpty())
            {
                assertTrue(result.body().h() >= CompositorLayout.MIN_BODY,
                    "body was squeezed below its floor at " + height);
            }
        }

        assertEquals(Mode.SHORT, CompositorLayout.modeFor(1280, 480));
        assertEquals(Mode.NARROW, CompositorLayout.modeFor(700, 720));
        assertEquals(Mode.MEDIUM, CompositorLayout.modeFor(960, 720));
        assertEquals(Mode.WIDE, CompositorLayout.modeFor(1600, 720));
    }

    @Test
    void breakpointsRecommendTheDock()
    {
        assertTrue(CompositorLayout.compute(inputs(700, 720)).needsDock(), "narrow panel wants stacking");
        assertTrue(CompositorLayout.compute(inputs(1280, 420)).needsDock(), "short panel wants stacking");
        assertFalse(CompositorLayout.compute(inputs(1600, 900)).needsDock());
    }

    @Test
    void timelineClampNeverEatsTheBody()
    {
        for (int height : new int[] {160, 240, 320, 480, 720, 1080})
        {
            int timeline = CompositorLayout.clampTimelineHeight(height, 1000);
            int body = height - CompositorLayout.HEADER_H - CompositorLayout.STATUS_H - timeline - CompositorLayout.GAP;

            assertTrue(timeline >= CompositorLayout.MIN_TIMELINE || body <= 0,
                "timeline below its floor at " + height);
            assertTrue(body >= 0 || height < 200, "body went negative at " + height + ": " + body);
        }
    }

    @Test
    void fitColumnsKeepsThePreviewFloor()
    {
        int[] columns = CompositorLayout.fitColumns(420, 190, 300, 12, 120);

        assertTrue(420 - 12 - columns[0] - columns[1] >= 120);
        assertEquals(288, columns[0] + columns[1]);
    }

    @Test
    void hitTestIsEmptyForAnEmptyResult()
    {
        Result empty = CompositorLayout.compute(new Inputs(0, 0, 190, 300, 250, true, true, false, 210, false));

        assertEquals(Splitter.NONE, CompositorLayout.hitTest(empty, 0, 0));
        assertNotEquals(Splitter.TIMELINE, CompositorLayout.hitTest(empty, 10, 10));
        List<Rect> rects = CompositorLayout.rects(empty);

        assertTrue(rects.stream().allMatch(Rect::isEmpty));
    }
}
