package dev.bbsae.client.ui;

/**
 * Staggered list entrances: row {@code i} starts {@code i * perItem} seconds after the host's
 * {@link UiMotion#markShown()}, capped at {@code maxDelay}.
 *
 * <p>Stagger is what makes a list feel like it was laid out rather than pasted: rows arrive a few
 * milliseconds apart instead of all at once. The delay is computed, not stored, so a list of 200
 * rows costs the same as two. Only the visible rows are drawn by BBS's list clipping, so the cost is
 * bounded by the viewport.</p>
 */
public final class UiStagger
{
    private UiStagger()
    {}

    /** Entrance progress of row {@code index}; 1 when motion is off. */
    public static float progress(UiMotion motion, int index, float perItem, float maxDelay)
    {
        if (motion == null)
        {
            return 1F;
        }

        float delay = Math.min(Math.max(0F, maxDelay), Math.max(0, index) * Math.max(0F, perItem));

        return motion.intro(delay);
    }

    /** Vertical offset in pixels for an entrance progress and a distance. */
    public static int offsetPx(float progress, int distance)
    {
        return Math.round((1F - UiMotion.clamp01(progress)) * distance);
    }

    /** Alpha multiplier for an entrance progress (never fully transparent: the row appears fast). */
    public static float alpha(float progress)
    {
        return 0.35F + 0.65F * UiMotion.clamp01(progress);
    }
}
