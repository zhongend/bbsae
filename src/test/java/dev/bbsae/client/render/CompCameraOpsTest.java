package dev.bbsae.client.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure camera-tool geometry: orbit keeps its pivot/radius and Track Z keeps direction. */
public class CompCameraOpsTest
{
    @Test
    public void orbitKeepsThePivotAndRadius()
    {
        Vector3f target = new Vector3f(960F, 540F, 0F);
        Vector3f eye = new Vector3f(960F, 540F, 900F);
        Vector3f next = CompCameraOps.orbit(eye, target, 25F, 10F);

        assertEquals(900F, new Vector3f(next).sub(target).length(), 0.5F);
        assertTrue(next.z > 0F, "a front camera must stay in front after orbit");
    }

    @Test
    public void dollyFactorDirectionsAreConsistent()
    {
        assertTrue(CompCameraOps.dollyFactor(100F) < 1F, "dragging right moves closer");
        assertTrue(CompCameraOps.dollyFactor(-100F) > 1F, "dragging left moves farther");
        assertEquals(1F, CompCameraOps.dollyFactor(0F), 0.0001F);
    }
}
