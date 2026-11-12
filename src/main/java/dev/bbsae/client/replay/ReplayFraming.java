package dev.bbsae.client.replay;

import org.joml.Vector3f;

/**
 * Guard rails for the auto-framing math.
 *
 * <p>{@code Camera.frame} divides by the bounds size and feeds the result into the view matrix. A
 * NaN or infinite coordinate — a replay keyframe driven by a molang expression that evaluated to
 * NaN, or a non-finite camera distance from a corrupt project — poisons every matrix downstream and
 * the viewport silently goes black with no error. These helpers let {@code frameAll} skip
 * non-finite actors, refuse inverted/degenerate bounds, and fall back to a safe distance instead of
 * handing NaN to the renderer. Pure functions, so the cases are pinned in a unit test.</p>
 */
public final class ReplayFraming
{
    /** Fallback distance when the framed distance is unusable. */
    public static final float FALLBACK_DISTANCE = 2000F;

    private ReplayFraming()
    {}

    public static boolean isFinite(float value)
    {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static boolean isFinite(Vector3f vector)
    {
        return vector != null && isFinite(vector.x) && isFinite(vector.y) && isFinite(vector.z);
    }

    /**
     * Accumulates one actor position into the bounds.
     *
     * @return false when the position is not finite and was <b>not</b> accumulated
     */
    public static boolean accumulate(Vector3f min, Vector3f max, float x, float y, float z)
    {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z))
        {
            return false;
        }

        min.min(new Vector3f(x, y, z));
        max.max(new Vector3f(x, y, z));

        return true;
    }

    /** True when the bounds are usable: every component finite and min <= max on all axes. */
    public static boolean isValidBounds(Vector3f min, Vector3f max)
    {
        if (!isFinite(min) || !isFinite(max))
        {
            return false;
        }

        return min.x <= max.x && min.y <= max.y && min.z <= max.z;
    }

    public static Vector3f center(Vector3f min, Vector3f max, Vector3f out)
    {
        return out.set(
            (min.x + max.x) * 0.5F,
            (min.y + max.y) * 0.5F,
            (min.z + max.z) * 0.5F
        );
    }

    /** A finite, positive distance; {@code fallback} otherwise (also when the fallback is unusable). */
    public static float safeDistance(float distance, float fallback)
    {
        float safeFallback = isFinite(fallback) && fallback > 0F ? fallback : FALLBACK_DISTANCE;

        return isFinite(distance) && distance > 0F ? distance : safeFallback;
    }
}
