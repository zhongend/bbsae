package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview snap must be predictable: nearest anchor wins, the threshold is respected, and X/Y
 * are independent so centre + edge snapping can happen in the same drag.
 */
public class CompSnapTest
{
    @Test
    public void aNearlyCentredLayerSnapsItsCentreToTheCompositionCentre()
    {
        CompSnap.Result result = CompSnap.compute(
            new float[] {453F, 300F, 553F, 400F}, List.of(), 1000F, 1000F, 8F);

        assertTrue(result.snappedX);
        assertEquals(-3F, result.dx, 0.001F);
        assertEquals(500F, result.guideX, 0.001F);
        assertFalse(result.snappedY);
    }

    @Test
    public void edgesSnapToNeighbourEdgesAndGuidesReportTheTarget()
    {
        float[] moving = new float[] {195F, 100F, 295F, 200F};
        float[] neighbour = new float[] {300F, 100F, 500F, 300F};

        CompSnap.Result result = CompSnap.compute(moving, List.of(neighbour), 1000F, 800F, 8F);

        assertTrue(result.snappedX);
        assertEquals(5F, result.dx, 0.001F);
        assertEquals(300F, result.guideX, 0.001F);
    }

    @Test
    public void xAndYSnapIndependently()
    {
        float[] moving = new float[] {0F, 0F, 100F, 100F};
        float[] neighbour = new float[] {0F, 0F, 100F, 100F};

        CompSnap.Result result = CompSnap.compute(moving, List.of(neighbour), 1000F, 800F, 10F);

        assertTrue(result.snappedX);
        assertTrue(result.snappedY);
        assertEquals(0F, result.dx, 0.001F);
        assertEquals(0F, result.dy, 0.001F);
    }

    @Test
    public void beyondTheThresholdNothingMoves()
    {
        CompSnap.Result result = CompSnap.compute(
            new float[] {460F, 400F, 560F, 500F}, List.of(), 1000F, 800F, 8F);

        assertFalse(result.snappedX);
        assertEquals(0F, result.dx, 0.001F);
    }

    @Test
    public void theClosestTargetWins()
    {
        float[] moving = new float[] {30F, 100F, 40F, 200F};
        float[] near = new float[] {31F, 500F, 60F, 520F};
        float[] veryNear = new float[] {32F, 500F, 60F, 520F};

        CompSnap.Result result = CompSnap.compute(moving, List.of(near, veryNear), 1000F, 800F, 8F);

        assertTrue(result.snappedX);
        assertEquals(1F, result.dx, 0.001F);
        assertEquals(31F, result.guideX, 0.001F);
    }

    @Test
    public void groupBoundsUnionsEverySelectedRectangle()
    {
        float[] bounds = CompSnap.groupBounds(List.of(
            new float[] {10F, 20F, 30F, 40F},
            new float[] {-5F, 35F, 20F, 60F}
        ));

        assertEquals(-5F, bounds[0], 0.001F);
        assertEquals(20F, bounds[1], 0.001F);
        assertEquals(30F, bounds[2], 0.001F);
        assertEquals(60F, bounds[3], 0.001F);
    }
}
