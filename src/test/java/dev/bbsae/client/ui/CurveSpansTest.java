package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curve geometry cache.
 *
 * <p>These assertions pin the two properties the editor depends on:</p>
 * <ul>
 *   <li>a steep segment must not leave gaps between adjacent columns (the reason each column
 *       stores the span to the <em>next</em> sample rather than a single point);</li>
 *   <li>a partial rebuild must not disturb columns outside the dirty range (the reason a handle
 *       drag can cost 20 columns instead of the whole graph).</li>
 * </ul>
 */
public class CurveSpansTest
{
    @Test
    public void oneSpanPerColumn()
    {
        CurveSpans spans = new CurveSpans();

        spans.ensure(10);
        spans.build(0, 9, (column) -> 100 - column);

        assertEquals(10, spans.columns());

        for (int column = 0; column < 10; column++)
        {
            assertTrue(spans.has(column));

            /* Each column spans from its own sample to the next one, so a line that moves one
             * pixel per column covers exactly two rows. */
            int a = 100 - column;
            int b = 100 - Math.min(9, column + 1);

            assertEquals(Math.min(a, b), spans.lo(column), "lo of column " + column);
            /* At least one pixel tall, so a flat curve is still visible. */
            assertTrue(spans.hi(column) >= Math.max(a, b), "hi of column " + column);
            assertTrue(spans.hi(column) - spans.lo(column) >= 1, "column " + column + " is empty");
        }
    }

    @Test
    public void steepSegmentStaysContinuous()
    {
        CurveSpans spans = new CurveSpans();

        spans.ensure(8);
        /* 0 -> 700 over eight columns: 100 px per column. */
        spans.build(0, 7, (column) -> 700 - column * 100);

        for (int column = 0; column < 7; column++)
        {
            /* Adjacent rectangles must touch (or overlap) in Y, otherwise a steep curve renders
             * as a dotted line. A descending line means the next column sits above this one, so
             * the check is symmetric rather than directional. */
            int top = Math.max(spans.lo(column), spans.lo(column + 1));
            int bottom = Math.min(spans.hi(column), spans.hi(column + 1));

            assertTrue(bottom >= top - 1, "gap between column " + column + " and " + (column + 1));
        }
    }

    @Test
    public void partialBuildLeavesOtherColumnsUntouched()
    {
        CurveSpans spans = new CurveSpans();

        spans.ensure(20);
        spans.build(0, 19, (column) -> 50);

        int before = spans.lo(2);

        spans.build(10, 12, (column) -> 400);

        assertEquals(before, spans.lo(2), "column outside the dirty range changed");
        assertEquals(400, spans.lo(10));
        assertEquals(400, spans.lo(12));
        assertEquals(50, spans.lo(13), "column after the dirty range changed");
    }

    @Test
    public void resizeInvalidatesTheContent()
    {
        CurveSpans spans = new CurveSpans();

        spans.ensure(10);
        spans.build(0, 9, (column) -> 10);
        spans.stamp = 7L;

        assertTrue(spans.has(0));

        spans.ensure(12);

        assertFalse(spans.has(0), "a resize must drop the stale geometry");
        assertEquals(12, spans.columns());
    }

    @Test
    public void clearDropsEveryFlag()
    {
        CurveSpans spans = new CurveSpans();

        spans.ensure(5);
        spans.build(0, 4, (column) -> 1);
        spans.clear();

        for (int column = 0; column < 5; column++)
        {
            assertFalse(spans.has(column));
        }
    }
}
