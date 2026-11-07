package dev.bbsae.client.ui;

/**
 * Screen-space math for the 3D transform gizmo.
 *
 * <p>The gizmo is drawn in viewport pixels but edits composition units. Keeping the projection
 * helpers pure makes the "does dragging the red arrow move X in the direction the arrow points"
 * question testable without a GL context or the client.</p>
 */
public final class CompGizmo3D
{
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /** Pixels of drag along an axis that double/halve its scale. */
    public static final float SCALE_DRAG_PIXELS = 200F;

    /** Screen length of the drawn arrows, in pixels. */
    public static final float ARM_PIXELS = 74F;
    /** Distance used to measure pixels-per-composition-unit along an axis. */
    public static final float PROBE_UNITS = 100F;

    private CompGizmo3D()
    {}

    /**
     * @return {dirX, dirY, length} where the direction is normalised; length 0 for a degenerate
     *         (edge-on) axis.
     */
    public static float[] screenAxis(float originX, float originY, float endX, float endY)
    {
        float dx = endX - originX;
        float dy = endY - originY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 0.0001F || Float.isNaN(length) || Float.isInfinite(length))
        {
            return new float[] {0F, 0F, 0F};
        }

        return new float[] {dx / length, dy / length, length};
    }

    /**
     * Pixels per world unit along one axis. A probe point far from the eye projects with a shorter
     * pixel delta under perspective, so using the probe length (and not the arm length) is what
     * keeps fast and slow drags equally responsive at different depths.
     */
    public static float pixelsPerUnit(float originX, float originY, float probeX, float probeY, float probeUnits)
    {
        if (probeUnits <= 0.0001F)
        {
            return 0F;
        }

        float[] axis = screenAxis(originX, originY, probeX, probeY);

        return axis[2] / probeUnits;
    }

    /** Value after dragging the pointer by (mouseDx, mouseDy) along a screen axis. */
    public static float dragValue(float startValue, float mouseDx, float mouseDy,
                                  float dirX, float dirY, float pixelsPerUnit)
    {
        if (pixelsPerUnit <= 0.0001F)
        {
            return startValue;
        }

        return startValue + (mouseDx * dirX + mouseDy * dirY) / pixelsPerUnit;
    }

    /** Distance from a point to a line segment, used as the gizmo's screen-space hit test. */
    public static float distanceToSegment(float px, float py, float ax, float ay, float bx, float by)
    {
        float dx = bx - ax;
        float dy = by - ay;
        float lengthSquared = dx * dx + dy * dy;

        if (lengthSquared < 0.0001F)
        {
            return (float) Math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay));
        }

        float t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared;

        t = Math.max(0F, Math.min(1F, t));

        float cx = ax + dx * t;
        float cy = ay + dy * t;

        return (float) Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
    }

    /** Scale factor for a drag along an axis: 1 is unchanged, 2 doubles, 0.5 halves. */
    public static float scaleFactor(float deltaPixels)
    {
        return Math.max(0.01F, 1F + deltaPixels / SCALE_DRAG_PIXELS);
    }

    /** Screen angle in degrees, measured like the 2D rotate handle (y down). */
    public static float angleDegrees(float dx, float dy)
    {
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /** Shortest signed difference between two screen angles. */
    public static float angleDelta(float startDegrees, float currentDegrees)
    {
        float delta = currentDegrees - startDegrees;

        while (delta > 180F)
        {
            delta -= 360F;
        }

        while (delta < -180F)
        {
            delta += 360F;
        }

        return delta;
    }

    /** Snap a drag to whole composition units; Shift-drag is exact. */
    public static float snap(float value, boolean whole)
    {
        return whole ? Math.round(value) : value;
    }
}
