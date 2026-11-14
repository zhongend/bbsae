package dev.bbsae.client.ui;

import java.util.List;

/**
 * Alignment/snapping math for the 2D preview.
 *
 * <p>Pure on purpose: the canvas hands in composition-space rectangles, so the exact same rule can
 * be unit-tested without a window and the renderer can draw the returned guide lines. Snapping
 * compares three moving anchors (left/centre/right, top/middle/bottom) against the same anchors of
 * every other visible layer and the composition rectangle. The smallest movement inside the
 * threshold wins; X and Y are independent, so a layer can snap to the centre vertically and to a
 * neighbour's edge horizontally in one drag.</p>
 */
public final class CompSnap
{
    public static final class Result
    {
        public final float dx;
        public final float dy;
        public final boolean snappedX;
        public final boolean snappedY;
        public final float guideX;
        public final float guideY;

        Result(float dx, float dy, boolean snappedX, boolean snappedY, float guideX, float guideY)
        {
            this.dx = dx;
            this.dy = dy;
            this.snappedX = snappedX;
            this.snappedY = snappedY;
            this.guideX = guideX;
            this.guideY = guideY;
        }

        public static Result none()
        {
            return new Result(0F, 0F, false, false, 0F, 0F);
        }
    }

    private CompSnap()
    {}

    public static float[] groupBounds(List<float[]> bounds)
    {
        if (bounds == null || bounds.isEmpty())
        {
            return new float[] {0F, 0F, 0F, 0F};
        }

        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;

        for (float[] rect : bounds)
        {
            if (rect == null || rect.length < 4)
            {
                continue;
            }

            left = Math.min(left, rect[0]);
            top = Math.min(top, rect[1]);
            right = Math.max(right, rect[2]);
            bottom = Math.max(bottom, rect[3]);
        }

        if (left == Float.MAX_VALUE)
        {
            return new float[] {0F, 0F, 0F, 0F};
        }

        return new float[] {left, top, right, bottom};
    }

    /**
     * @param moving    current composition-space {left, top, right, bottom} of the dragged layer
     *                  or selection
     * @param targets   other layers' rectangles, same layout
     * @param threshold maximum composition-space distance that still snaps
     */
    public static Result compute(float[] moving, List<float[]> targets,
                                 float compWidth, float compHeight, float threshold)
    {
        if (moving == null || moving.length < 4 || compWidth <= 0F || compHeight <= 0F || threshold <= 0F)
        {
            return Result.none();
        }

        float movingCX = (moving[0] + moving[2]) * 0.5F;
        float movingCY = (moving[1] + moving[3]) * 0.5F;
        float bestX = Float.MAX_VALUE;
        float bestY = Float.MAX_VALUE;
        float guideX = 0F;
        float guideY = 0F;
        boolean snappedX = false;
        boolean snappedY = false;

        float[] xAnchors = {moving[0], movingCX, moving[2]};
        float[] yAnchors = {moving[1], movingCY, moving[3]};

        /* The composition itself is always a target: centre and all four edges. */
        float[] compXs = {0F, compWidth * 0.5F, compWidth};
        float[] compYs = {0F, compHeight * 0.5F, compHeight};

        for (int anchor = 0; anchor < 3; anchor++)
        {
            for (float target : compXs)
            {
                float delta = target - xAnchors[anchor];

                if (Math.abs(delta) <= threshold && Math.abs(delta) < Math.abs(bestX))
                {
                    bestX = delta;
                    guideX = target;
                    snappedX = true;
                }
            }

            for (float target : compYs)
            {
                float delta = target - yAnchors[anchor];

                if (Math.abs(delta) <= threshold && Math.abs(delta) < Math.abs(bestY))
                {
                    bestY = delta;
                    guideY = target;
                    snappedY = true;
                }
            }
        }

        if (targets != null)
        {
            for (float[] target : targets)
            {
                if (target == null || target.length < 4)
                {
                    continue;
                }

                float[] xs = {target[0], (target[0] + target[2]) * 0.5F, target[2]};
                float[] ys = {target[1], (target[1] + target[3]) * 0.5F, target[3]};

                for (int anchor = 0; anchor < 3; anchor++)
                {
                    for (float value : xs)
                    {
                        float delta = value - xAnchors[anchor];

                        if (Math.abs(delta) <= threshold && Math.abs(delta) < Math.abs(bestX))
                        {
                            bestX = delta;
                            guideX = value;
                            snappedX = true;
                        }
                    }

                    for (float value : ys)
                    {
                        float delta = value - yAnchors[anchor];

                        if (Math.abs(delta) <= threshold && Math.abs(delta) < Math.abs(bestY))
                        {
                            bestY = delta;
                            guideY = value;
                            snappedY = true;
                        }
                    }
                }
            }
        }

        return new Result(
            snappedX ? bestX : 0F,
            snappedY ? bestY : 0F,
            snappedX,
            snappedY,
            guideX,
            guideY
        );
    }

    public static float[] translated(float[] rect, float dx, float dy)
    {
        if (rect == null || rect.length < 4)
        {
            return rect;
        }

        return new float[] {rect[0] + dx, rect[1] + dy, rect[2] + dx, rect[3] + dy};
    }
}
