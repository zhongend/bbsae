package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression tests for pointer precision in the composition graph editor. */
public class CurveInteractionTest
{
    @Test
    public void verticalDragDoesNotRoundAnUnchangedFractionalTick()
    {
        assertEquals(1.5F, CurveInteraction.keyTick(1.5F, 0, 2F, true), 0F);
        assertEquals(-0.5F, CurveInteraction.keyTick(-0.5F, 0, 2F, true), 0F);
    }

    @Test
    public void horizontalDragUsesThePressSnapshotAndOptionalSnapping()
    {
        assertEquals(11.75F, CurveInteraction.keyTick(10.25F, 3, 2F, false), 0.0001F);
        assertEquals(12F, CurveInteraction.keyTick(10.25F, 3, 2F, true), 0.0001F);
        assertEquals(0F, CurveInteraction.keyTick(2F, -10, 2F, false), 0F);
    }

    @Test
    public void handleDeltasRespectBothGraphAxes()
    {
        assertEquals(6F, CurveInteraction.handleFrames(4F, 6, 3F, true, 100F), 0.0001F);
        assertEquals(2F, CurveInteraction.handleFrames(4F, 6, 3F, false, 100F), 0.0001F);
        assertEquals(22.5F, CurveInteraction.handleValue(20F, -5, 2F), 0.0001F);
        assertEquals(17.5F, CurveInteraction.handleValue(20F, 5, 2F), 0.0001F);
    }

    @Test
    public void handleLengthClampsToTheAdjacentSegment()
    {
        assertEquals(5F, CurveInteraction.handleFrames(4F, 9, 3F, true, 5F), 0.0001F);
        assertEquals(CurveInteraction.MIN_HANDLE_FRAMES,
            CurveInteraction.handleFrames(4F, -30, 3F, true, 100F), 0.0001F);
    }

    @Test
    public void nearestHandleWinsWhenHitAreasOverlap()
    {
        float[] xs = {8F, 4F, 2F};
        float[] ys = {0F, 0F, 1F};

        assertEquals(2, CurveInteraction.nearest(0F, 0F, xs, ys, 3, 10D));
        assertEquals(1, CurveInteraction.nearest(0F, 0F, xs, ys, 2, 10D));
    }

    @Test
    public void hitRadiusIsInclusiveAndMissesStayEmpty()
    {
        float[] xs = {10F, 11F};
        float[] ys = {0F, 0F};

        assertEquals(0, CurveInteraction.nearest(0F, 0F, xs, ys, 2, 10D));
        assertEquals(-1, CurveInteraction.nearest(-1F, 0F, xs, ys, 1, 10D));
        assertEquals(-1, CurveInteraction.nearest(0F, 0F, xs, ys, 0, 10D));
    }
}
