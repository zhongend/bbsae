package dev.bbsae.client.replay;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frustum culling rules for the 3D model pass. The two guarantees that matter: an off-screen actor
 * is skipped (that is the 200-replay performance win) and the selected actor is never skipped (a
 * selection that disappears off the edge of the viewport would look like a bug).
 */
public class ReplayCullingTest
{
    /** Camera at the origin looking down -Z, ±10 units across, depth 0.1..100. */
    private static Matrix4f viewProjection()
    {
        return new Matrix4f().ortho(-10F, 10F, -10F, 10F, 0.1F, 100F);
    }

    @Test
    public void anActorInsideTheFrustumIsVisible()
    {
        boolean[] visible = ReplayCulling.visible(viewProjection(),
            List.of(new Vector3f(0F, 0F, -5F), new Vector3f(5F, 5F, -50F)), 1F, -1);

        assertTrue(visible[0]);
        assertTrue(visible[1]);
    }

    @Test
    public void anActorOutsideTheFrustumIsCulled()
    {
        boolean[] visible = ReplayCulling.visible(viewProjection(),
            List.of(new Vector3f(100F, 0F, -5F), new Vector3f(0F, 0F, 5F), new Vector3f(0F, 0F, -5F)), 1F, -1);

        assertFalse(visible[0], "far to the side");
        assertFalse(visible[1], "behind the camera");
        assertTrue(visible[2]);
    }

    @Test
    public void theSelectedActorIsAlwaysVisible()
    {
        List<Vector3f> positions = List.of(new Vector3f(0F, 0F, -5F), new Vector3f(500F, 0F, -5F));
        boolean[] visible = ReplayCulling.visible(viewProjection(), positions, 1F, 1);

        assertTrue(visible[0]);
        assertTrue(visible[1], "selected actor must not be culled");
    }

    @Test
    public void aMissingActorIsTreatedAsVisible()
    {
        boolean[] visible = ReplayCulling.visible(viewProjection(),
            Arrays.asList(null, new Vector3f(500F, 0F, -5F)), 1F, -1);

        assertTrue(visible[0], "no entity is a caller concern, not a culling decision");
        assertFalse(visible[1]);
    }

    @Test
    public void theRadiusKeepsPartlyVisibleActors()
    {
        List<Vector3f> positions = List.of(new Vector3f(10.5F, 0F, -5F));
        Matrix4f projection = viewProjection();

        assertFalse(ReplayCulling.visible(projection, positions, 0.1F, -1)[0]);
        assertTrue(ReplayCulling.visible(projection, positions, 2F, -1)[0]);
    }

    @Test
    public void allVisibleAndCountingAgreeWithTheFlags()
    {
        boolean[] all = ReplayCulling.allVisible(3);

        assertTrue(all[0] && all[1] && all[2]);
        assertEquals(3, ReplayCulling.countVisible(all));
        assertEquals(0, ReplayCulling.countVisible(ReplayCulling.allVisible(0)));
    }

    @Test
    public void cullingOnlyKicksInAboveTheThreshold()
    {
        int threshold = ReplayCalibration.cullThreshold();

        assertFalse(ReplayCulling.shouldCull(Math.max(0, threshold - 1)));
        assertFalse(ReplayCulling.shouldCull(threshold));
        assertTrue(ReplayCulling.shouldCull(threshold + 1));
    }
}
