package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the 3D gizmo's screen projection and drag direction honest without a GL context. */
public class CompGizmo3DTest
{
    @Test
    public void anAxisIsNormalisedToADirection()
    {
        float[] axis = CompGizmo3D.screenAxis(10F, 20F, 40F, 60F);

        assertEquals(0.6F, axis[0], 0.0001F);
        assertEquals(0.8F, axis[1], 0.0001F);
        assertEquals(50F, axis[2], 0.0001F);
    }

    @Test
    public void anEdgeOnAxisHasNoDirection()
    {
        float[] axis = CompGizmo3D.screenAxis(0F, 0F, 0F, 0F);

        assertEquals(0F, axis[2], 0.0001F);
    }

    @Test
    public void draggingAlongTheArrowAddsUnitsAndPerpendicularMotionDoesNot()
    {
        float along = CompGizmo3D.dragValue(100F, 50F, 0F, 1F, 0F, 2F);
        float across = CompGizmo3D.dragValue(100F, 0F, 50F, 1F, 0F, 2F);

        assertEquals(125F, along, 0.0001F);
        assertEquals(100F, across, 0.0001F);
    }

    @Test
    public void pixelsPerUnitUsesTheProbeDistance()
    {
        assertEquals(0.5F, CompGizmo3D.pixelsPerUnit(0F, 0F, 50F, 0F, 100F), 0.0001F);
    }

    @Test
    public void theHitTestOnlyAcceptsTheSegmentAndItsTips()
    {
        assertTrue(CompGizmo3D.distanceToSegment(5F, 2F, 0F, 0F, 10F, 0F) <= 2F);
        assertTrue(CompGizmo3D.distanceToSegment(5F, 20F, 0F, 0F, 10F, 0F) > 8F);
        assertEquals(0F, CompGizmo3D.distanceToSegment(-20F, 0F, 0F, 0F, 10F, 0F) - 20F, 0.0001F);
    }

    @Test
    public void shiftSnapsToWholeUnits()
    {
        assertEquals(123F, CompGizmo3D.snap(122.6F, true), 0.0001F);
        assertEquals(122.6F, CompGizmo3D.snap(122.6F, false), 0.0001F);
    }
}
