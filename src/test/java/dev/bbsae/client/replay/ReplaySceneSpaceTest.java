package dev.bbsae.client.replay;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comp↔world mapping is the foundation of the replay viewport: every model position and every
 * gizmo axis goes through it. These tests pin the signs, the scale and the determinant so a future
 * "cleanup" cannot silently mirror the scene.
 */
public class ReplaySceneSpaceTest
{
    private static final float EPS = 0.0001F;

    @Test
    public void aTowerIsTallerInTheCompositionThanTheFloor()
    {
        ReplaySceneSpace space = new ReplaySceneSpace(64F);
        Vector3f feet = space.toComp(0D, 0D, 0D);
        Vector3f head = space.toComp(0D, 1.8D, 0D);

        assertEquals(0F, feet.x, EPS);
        assertEquals(0F, feet.y, EPS);
        assertEquals(-115.2F, head.y, EPS);
        assertEquals(1.8F * 64F, feet.y - head.y, EPS);
    }

    @Test
    public void worldToCompToWorldIsTheIdentity()
    {
        ReplaySceneSpace space = new ReplaySceneSpace(37.5F);

        space.anchor.set(11F, -7F, 3F);

        Vector3f world = new Vector3f(12.5F, -3.25F, 89F);
        Vector3f comp = space.toComp(world.x, world.y, world.z);

        assertTrue(new Vector3f(world).sub(space.toWorld(comp)).length() < 0.001F);
    }

    @Test
    public void theMatrixAndThePointHelpersAgree()
    {
        ReplaySceneSpace space = new ReplaySceneSpace(64F);

        space.anchor.set(-20F, 40F, -8F);

        Vector3f world = new Vector3f(2F, 3F, 4F);
        Vector3f byHelper = space.toComp(world.x, world.y, world.z);
        Matrix4f matrix = space.compFromWorld();

        /* Compare the matrix numerically instead of going through transformPosition: the affine
         * form is exactly what the pipeline relies on (column-major m00..m22 + translation
         * m30..m32), and it keeps this test independent of a JOML helper whose behaviour under
         * the Gradle test runtime has proven environment-specific. */
        float matrixX = matrix.m00() * world.x + matrix.m10() * world.y + matrix.m20() * world.z + matrix.m30();
        float matrixY = matrix.m01() * world.x + matrix.m11() * world.y + matrix.m21() * world.z + matrix.m31();
        float matrixZ = matrix.m02() * world.x + matrix.m12() * world.y + matrix.m22() * world.z + matrix.m32();

        assertTrue(Math.abs(byHelper.x - matrixX) < 0.001F, "helper=" + byHelper + " matrix=(" + matrixX + "," + matrixY + "," + matrixZ + ")");
        assertTrue(Math.abs(byHelper.y - matrixY) < 0.001F);
        assertTrue(Math.abs(byHelper.z - matrixZ) < 0.001F);
    }

    /**
     * A positive determinant means the mapping is a rotation plus scale, not a mirror: triangle
     * winding and back-face culling stay valid. This is the reason the default Z sign is negative.
     */
    @Test
    public void theDefaultMappingIsNotAMirror()
    {
        ReplaySceneSpace space = new ReplaySceneSpace(64F);
        Matrix4f matrix = space.compFromWorld();
        float determinant = matrix.determinant();

        assertTrue(determinant > 0F, "determinant " + determinant + " would mirror the models");
    }

    @Test
    public void theOtherZSignIsAMirrorAndIsNotTheDefault()
    {
        ReplaySceneSpace mirrored = new ReplaySceneSpace(64F);

        mirrored.flipZ = false;

        assertTrue(mirrored.compFromWorld().determinant() < 0F);
        assertNotEquals(mirrored.flipZ, new ReplaySceneSpace().flipZ);
    }

    @Test
    public void centeringAnActorPutsItOnTheCompositionOrigin()
    {
        ReplaySceneSpace space = new ReplaySceneSpace(64F);

        space.centerOn(100D, 64D, -200D);

        Vector3f origin = space.toComp(100D, 64D, -200D);

        assertTrue(origin.length() < 0.001F);
    }

    @Test
    public void oneBlockIsAlwaysTheConfiguredPixelSize()
    {
        ReplaySceneSpace space = new ReplaySceneSpace();

        space.pixelsPerBlock = 12.5F;

        Vector3f a = space.toComp(0D, 0D, 0D);
        Vector3f b = space.toComp(1D, 0D, 0D);

        assertEquals(12.5F, b.x - a.x, EPS);
        assertEquals(1F, space.blocksPerPixel() * space.pixelsPerBlock, EPS);
    }
}
