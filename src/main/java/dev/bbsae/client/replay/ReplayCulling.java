package dev.bbsae.client.replay;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Which replay actors the 3D viewport has to draw this frame.
 *
 * <p>A film can contain a few hundred replays; issuing a full actor pass for every one of them
 * (pose application included) is what makes the viewport crawl on a big film. The pass therefore
 * frustum-tests the actors against the viewport camera and skips the ones that cannot contribute
 * a pixel, with two deliberate exceptions:</p>
 * <ul>
 *   <li>the <b>selected</b> replay is always drawn — its gizmo and its current pose are the thing
 *       being edited, and a selection that vanishes because the model is just off screen is
 *       confusing;</li>
 *   <li>small films are never culled at all, so culling cannot introduce a pop-in on the one
 *       scene a user is most likely to inspect.</li>
 * </ul>
 *
 * <p>Pure JOML on purpose: the visibility rules are testable without a GL context, and the camera
 * matrix is the only input.</p>
 */
public final class ReplayCulling
{
    private ReplayCulling()
    {}

    /**
     * @param viewProjection world → clip (cull) matrix
     * @param worldPositions the actors' world positions, one per replay (missing entities are
     *                       represented by {@code null} and are treated as visible so the caller's
     *                       "no entity" path is not mistaken for a culled one)
     * @param radius         actor bounding radius in world blocks
     * @param selectedIndex  replay list index to force visible, or -1
     */
    public static boolean[] visible(Matrix4f viewProjection, List<Vector3f> worldPositions,
                                    float radius, int selectedIndex)
    {
        int count = worldPositions == null ? 0 : worldPositions.size();
        boolean[] visible = new boolean[count];

        if (count == 0)
        {
            return visible;
        }

        /* A NaN/infinite matrix (corrupt camera or framing) makes every frustum plane NaN, and
         * then every actor would be culled and the viewport would look empty with no error. In
         * that case draw everything: a slow frame is recoverable, an invisible scene is not. */
        if (!isFinite(viewProjection))
        {
            return allVisible(count);
        }

        float r = Math.max(0.001F, radius);
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(viewProjection));

        for (int i = 0; i < count; i++)
        {
            Vector3f position = worldPositions.get(i);
            boolean selected = i == selectedIndex;

            if (position == null)
            {
                /* No entity is a caller concern (the pass skips it), not a culling decision. */
                visible[i] = true;
            }
            else if (!ReplayFraming.isFinite(position))
            {
                /* NaN position: keep the selection visible so the gizmo does not vanish, skip
                 * the corrupt actor in the pass. */
                visible[i] = selected;
            }
            else
            {
                visible[i] = frustum.testSphere(position.x, position.y, position.z, r) || selected;
            }
        }

        return visible;
    }

    /** True when every matrix element is finite. */
    public static boolean isFinite(Matrix4f matrix)
    {
        if (matrix == null)
        {
            return false;
        }

        return ReplayFraming.isFinite(matrix.m00()) && ReplayFraming.isFinite(matrix.m01())
            && ReplayFraming.isFinite(matrix.m02()) && ReplayFraming.isFinite(matrix.m03())
            && ReplayFraming.isFinite(matrix.m10()) && ReplayFraming.isFinite(matrix.m11())
            && ReplayFraming.isFinite(matrix.m12()) && ReplayFraming.isFinite(matrix.m13())
            && ReplayFraming.isFinite(matrix.m20()) && ReplayFraming.isFinite(matrix.m21())
            && ReplayFraming.isFinite(matrix.m22()) && ReplayFraming.isFinite(matrix.m23())
            && ReplayFraming.isFinite(matrix.m30()) && ReplayFraming.isFinite(matrix.m31())
            && ReplayFraming.isFinite(matrix.m32()) && ReplayFraming.isFinite(matrix.m33());
    }

    public static boolean[] allVisible(int count)
    {
        boolean[] visible = new boolean[Math.max(0, count)];

        java.util.Arrays.fill(visible, true);

        return visible;
    }

    /** True when a film is big enough to be worth culling (a small film is drawn in full). */
    public static boolean shouldCull(int replayCount)
    {
        return replayCount > ReplayCalibration.cullThreshold();
    }

    /** Count the visible flags — used by the viewport HUD to explain an empty-looking view. */
    public static int countVisible(boolean[] visible)
    {
        if (visible == null)
        {
            return 0;
        }

        int count = 0;

        for (boolean flag : visible)
        {
            if (flag)
            {
                count += 1;
            }
        }

        return count;
    }
}
