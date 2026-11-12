package dev.bbsae.client.ui;

import dev.bbsae.comp.CompCurves;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-11: the timeline only touches visible rows and only keys inside the visible tick range.
 *
 * <p>The renderer already skipped fully clipped rows, but the arithmetic that defines "visible"
 * was inline and untested. It is now shared with these tests, which also pin the binary range
 * that replaced {@code allKeyframes()} in the tick strip.</p>
 */
class TimelineVisibleRowFilterTest
{
    @Test
    void firstRowFollowsTheScrollOffset()
    {
        assertEquals(0, UICompTrackTimeline.firstVisibleRow(0, 26));
        assertEquals(0, UICompTrackTimeline.firstVisibleRow(25, 26));
        assertEquals(3, UICompTrackTimeline.firstVisibleRow(100, 26));
        assertEquals(0, UICompTrackTimeline.firstVisibleRow(-40, 26), "negative scroll clamps to the first row");
        assertTrue(UICompTrackTimeline.firstVisibleRow(100, 0) >= 0, "a zero row height cannot divide by zero");
    }

    @Test
    void endRowCoversPartialRowsAndClampsToTheLayerCount()
    {
        assertEquals(15, UICompTrackTimeline.visibleRowEnd(3, 260, 26, 100));
        assertEquals(12, UICompTrackTimeline.visibleRowEnd(0, 260, 26, 12), "never past the layer count");
        assertEquals(0, UICompTrackTimeline.visibleRowEnd(0, 0, 26, 0));
        assertEquals(2, UICompTrackTimeline.visibleRowEnd(0, 10, 26, 100), "one partial row plus the overdraw margin");
    }

    @Test
    void everyPixelVisibleRowIsInsideTheComputedRange()
    {
        int laneTop = 120;
        int rowHeight = 23;
        int laneHeight = 311;
        int rowCount = 80;

        for (int scrollY = 0; scrollY < rowCount * rowHeight; scrollY += 7)
        {
            int first = UICompTrackTimeline.firstVisibleRow(scrollY, rowHeight);
            int end = UICompTrackTimeline.visibleRowEnd(first, laneHeight, rowHeight, rowCount);

            for (int row = 0; row < rowCount; row++)
            {
                int y = laneTop + row * rowHeight - scrollY;
                boolean visible = y + rowHeight > laneTop && y < laneTop + laneHeight;

                if (visible)
                {
                    assertTrue(row >= first && row < end,
                        "row " + row + " is visible at scroll " + scrollY + " but outside [" + first + ", " + end + ")");
                }
            }
        }
    }

    @Test
    void offsetRowsAreNotQueried()
    {
        int first = UICompTrackTimeline.firstVisibleRow(10_000, 26);

        assertTrue(first > 300, "a deep scroll must skip the rows above the viewport");
        assertFalse(first < 10_000 / 26);
    }

    @Test
    void keyRangeBinarySearchMatchesTheSortedChannel()
    {
        CompLayer layer = new CompLayer("tick_range", "Tick range", "solid");
        KeyframeChannel<Float> channel = layer.axisChannel(CompCurves.AXIS_X);

        channel.insertInheriting(0F, 0F);
        channel.insertInheriting(10F, 1F);
        channel.insertInheriting(20F, 2F);
        channel.insertInheriting(30F, 3F);

        List<Keyframe<?>> keys = UICompCurveEditor.keyframesOf(channel);

        assertEquals(0, UICompCurveEditor.lowerBoundTick(keys, -1F));
        assertEquals(0, UICompCurveEditor.lowerBoundTick(keys, 0F));
        assertEquals(1, UICompCurveEditor.lowerBoundTick(keys, 0.1F));
        assertEquals(1, UICompCurveEditor.lowerBoundTick(keys, 10F));
        assertEquals(3, UICompCurveEditor.lowerBoundTick(keys, 25F));
        assertEquals(4, UICompCurveEditor.lowerBoundTick(keys, 31F));

        assertEquals(0, UICompCurveEditor.upperBoundTick(keys, -1F));
        assertEquals(1, UICompCurveEditor.upperBoundTick(keys, 0F));
        assertEquals(2, UICompCurveEditor.upperBoundTick(keys, 10F));
        assertEquals(4, UICompCurveEditor.upperBoundTick(keys, 30F));
        assertEquals(4, UICompCurveEditor.upperBoundTick(keys, 99F));
    }

    @Test
    void visibleTickRangeContainsOnlyTheKeysThatCanDraw()
    {
        CompLayer layer = new CompLayer("tick_draw", "Tick draw", "solid");
        KeyframeChannel<Float> channel = layer.axisChannel(CompCurves.AXIS_X);

        channel.insertInheriting(0F, 0F);
        channel.insertInheriting(10F, 1F);
        channel.insertInheriting(20F, 2F);
        channel.insertInheriting(30F, 3F);

        List<Keyframe<?>> keys = UICompCurveEditor.keyframesOf(channel);
        int first = UICompCurveEditor.lowerBoundTick(keys, 9F);
        int last = UICompCurveEditor.upperBoundTick(keys, 21F);

        assertEquals(2, last - first);
        assertEquals(10F, keys.get(first).getTick());
        assertEquals(20F, keys.get(last - 1).getTick());
    }
}
