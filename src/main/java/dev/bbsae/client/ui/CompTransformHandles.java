package dev.bbsae.client.ui;

/**
 * Pure scale math for the compositor preview's transform box.
 *
 * <p>Handles use the same numbering as {@code UICompositorPanel.handleAt()}: 10–13 are the
 * corners (TL, TR, BR, BL), 20–23 are the edge midpoints (top, right, bottom, left).  Keeping
 * this out of the panel makes the axis mapping testable without a running Minecraft client.</p>
 *
 * <p>Edges scale exactly one local axis.  Corners are proportional by default (AE behaviour) and
 * free both axes while Shift is held.  Scaling happens around the layer's anchor, which remains
 * the pivot while the dragged handle follows the pointer.</p>
 */
public final class CompTransformHandles
{
    public static final float MIN_SCALE = 0.01F;
    public static final float MAX_SCALE = 100F;

    private CompTransformHandles()
    {}

    /**
     * @param handle       10–13 for corners, 20–23 for edges
     * @param startLocalX  pointer position at drag start, in pre-drag layer-local pixels
     * @param startLocalY  pointer position at drag start, in pre-drag layer-local pixels
     * @param localX       current pointer position in the same pre-drag local space
     * @param localY       current pointer position in the same pre-drag local space
     * @param proportional corners only: keep the X/Y ratio unless Shift is held
     */
    public static float[] scaled(int handle, float startLocalX, float startLocalY,
                                 float localX, float localY,
                                 float startScaleX, float startScaleY,
                                 boolean proportional)
    {
        return scaled(handle, startLocalX, startLocalY, localX, localY,
            startScaleX, startScaleY, proportional, false);
    }

    /**
     * @param lockAspect when true every handle scales both axes by one factor, preserving the
     *                   layer's original X:Y ratio. This is the "don't deform it" mode: an edge
     *                   drag that used to touch only one axis now changes both uniformly.
     */
    public static float[] scaled(int handle, float startLocalX, float startLocalY,
                                 float localX, float localY,
                                 float startScaleX, float startScaleY,
                                 boolean proportional, boolean lockAspect)
    {
        float ratioX = axisRatio(startLocalX, localX);
        float ratioY = axisRatio(startLocalY, localY);
        float sx = startScaleX * ratioX;
        float sy = startScaleY * ratioY;

        if (lockAspect)
        {
            float uniform = (handle == 20 || handle == 22) ? ratioY
                : (handle == 21 || handle == 23) ? ratioX
                : (ratioX + ratioY) * 0.5F;

            sx = startScaleX * uniform;
            sy = startScaleY * uniform;
        }
        else if (handle == 20 || handle == 22)
        {
            /* Top / bottom edge: height only. The old code changed X here, so dragging a side
             * handle visibly stretched the wrong axis. */
            sx = startScaleX;
        }
        else if (handle == 21 || handle == 23)
        {
            /* Right / left edge: width only. */
            sy = startScaleY;
        }
        else if (proportional)
        {
            /* Average the two factors, not the final scales: layers may start with uneven X/Y
             * scales (e.g. 2.0 x 1.0) and must keep that ratio. */
            float uniform = (ratioX + ratioY) * 0.5F;

            sx = startScaleX * uniform;
            sy = startScaleY * uniform;
        }

        return new float[] {clampScale(sx), clampScale(sy)};
    }

    /**
     * Clamps the magnitude but keeps the sign: crossing the anchor must mirror the layer (AE does,
     * and negative scale is legal renderer state), not collapse it to a positive sliver.
     */
    private static float clampScale(float value)
    {
        if (Float.isNaN(value) || Float.isInfinite(value))
        {
            return MIN_SCALE;
        }

        float magnitude = Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.abs(value)));

        return value < 0F ? -magnitude : magnitude;
    }

    private static float axisRatio(float start, float current)
    {
        /* A handle exactly on the anchor has no direction to measure; keeping the old scale is
         * less surprising than a division by zero that snaps the layer to 1% or 10000%. */
        if (Math.abs(start) < 0.0001F)
        {
            return 1F;
        }

        return current / start;
    }

    /**
     * The value the other scale axis must take so the current X:Y ratio survives a typed edit.
     */
    public static float linkedScale(float edited, float original, float other)
    {
        if (Math.abs(original) < 0.00001F)
        {
            return other;
        }

        return other * (edited / original);
    }
}
