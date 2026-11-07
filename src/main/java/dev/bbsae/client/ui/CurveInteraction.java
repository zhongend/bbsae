package dev.bbsae.client.ui;

import dev.bbsae.comp.CompCurves;

/**
 * Pointer-delta math for the composition graph editor.
 *
 * <p>This class deliberately has no Minecraft or window-state dependency. A drag always starts
 * from the value captured on mouse-down; rendering frequency must never change its result.</p>
 */
final class CurveInteraction
{
    /** Smallest handle radius in frames — one source of truth with {@code CompCurves.MIN_HANDLE}. */
    static final float MIN_HANDLE_FRAMES = CompCurves.MIN_HANDLE;

    private CurveInteraction()
    {}

    /**
     * Converts horizontal pointer movement to a key tick.
     *
     * <p>A vertical-only drag must not round a fractional tick: snapping applies to an actual
     * horizontal edit, not to the unchanged coordinate.</p>
     */
    static float keyTick(float startTick, int deltaX, float pxPerFrame, boolean snapping)
    {
        if (deltaX == 0)
        {
            return startTick;
        }

        float tick = startTick + deltaX / Math.max(0.0001F, pxPerFrame);

        return Math.max(0F, snapping ? Math.round(tick) : tick);
    }

    /** Converts pointer movement to a positive Bezier handle length in frames. */
    static float handleFrames(float start, int deltaX, float pxPerFrame, boolean outgoing, float maximum)
    {
        float delta = deltaX / Math.max(0.0001F, pxPerFrame);
        float value = start + (outgoing ? delta : -delta);

        return Math.max(MIN_HANDLE_FRAMES, Math.min(Math.max(MIN_HANDLE_FRAMES, maximum), value));
    }

    /** Screen Y grows downwards, while graph values grow upwards. */
    static float handleValue(float start, int deltaY, float pxPerValue)
    {
        return start - deltaY / Math.max(0.00001F, pxPerValue);
    }

    /** Nearest target wins; ties retain the first target and no target outside the radius wins. */
    static int nearest(float mouseX, float mouseY, float[] xs, float[] ys, int count, double radius)
    {
        int best = -1;
        double bestDistance = radius * radius;

        for (int i = 0; i < count; i++)
        {
            double dx = mouseX - xs[i];
            double dy = mouseY - ys[i];
            double distance = dx * dx + dy * dy;

            if (distance <= bestDistance && (best < 0 || distance < bestDistance))
            {
                best = i;
                bestDistance = distance;
            }
        }

        return best;
    }
}
