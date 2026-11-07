package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3D workspace sorts layers with its own orbit camera. These tests pin the plane-depth math for
 * an explicit eye/forward pair, which is what keeps a layer pushed towards the viewer in front of
 * the others while orbiting (the "layers penetrate in 3D space" report).
 */
public class CompScenePlaneDepthTest
{
    private static final Vector3f EYE = new Vector3f(0F, 0F, 100F);
    private static final Vector3f FORWARD = new Vector3f(0F, 0F, -1F);

    @Test
    public void depthFollowsDistanceAlongTheViewAxis()
    {
        float atZero = CompScene.planeDepth(new Matrix4f().translate(0F, 0F, 0F), EYE, FORWARD);
        float atFifty = CompScene.planeDepth(new Matrix4f().translate(0F, 0F, 50F), EYE, FORWARD);
        float behind = CompScene.planeDepth(new Matrix4f().translate(0F, 0F, -50F), EYE, FORWARD);

        assertEquals(-100F, atZero, 0.001F, "plane at z=0 is 100 in front of the eye");
        assertEquals(-50F, atFifty, 0.001F, "z=50 is nearer the eye");
        assertEquals(-150F, behind, 0.001F, "z=-50 is farther");
        assertTrue(atFifty > atZero && atZero > behind, "nearer planes must have larger (less negative) depth");
    }

    @Test
    public void lateralOffsetDoesNotChangeAParallelPlanesDepth()
    {
        float centred = CompScene.planeDepth(new Matrix4f().translate(0F, 0F, 0F), EYE, FORWARD);
        float offset = CompScene.planeDepth(new Matrix4f().translate(400F, -250F, 0F), EYE, FORWARD);

        assertEquals(centred, offset, 0.001F, "a plane parallel to the view plane has one depth");
    }

    @Test
    public void anEdgeOnPlaneFallsBackToItsOriginDepthInsteadOfInfinity()
    {
        Matrix4f edgeOn = new Matrix4f().rotateY((float) Math.PI * 0.5F);
        float depth = CompScene.planeDepth(edgeOn, EYE, FORWARD);

        assertTrue(Float.isFinite(depth), "an edge-on plane must not produce an infinite depth");
        assertEquals(-100F, depth, 0.001F, "its origin still sits 100 in front of the eye");
    }
}
