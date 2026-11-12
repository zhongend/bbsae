package dev.bbsae.client.replay;

/**
 * The replay editor's time units.
 *
 * <p>BBS films are authored in ticks (20 per second); bbsae compositions are authored in seconds
 * and frames. The replay panel plays back on the film's tick cursor, because the original BBS
 * keyframe editor, ruler and undo all speak ticks — converting to seconds first would make the
 * timeline disagree with the film by a rounding error on every keyframe. These helpers are the
 * single place the two dialects meet, so the conversion is testable instead of scattered through
 * the UI.</p>
 */
public final class ReplayTime
{
    public static final float TICKS_PER_SECOND = 20F;

    private ReplayTime()
    {}

    public static float ticksFromSeconds(float seconds)
    {
        return seconds * TICKS_PER_SECOND;
    }

    public static float secondsFromTicks(float ticks)
    {
        return ticks / TICKS_PER_SECOND;
    }

    /** A composition frame index to the film tick it lands on (nearest tick, never negative). */
    public static long ticksFromFrame(long frame, float fps)
    {
        if (fps <= 0F)
        {
            return 0L;
        }

        return Math.max(0L, Math.round(frame / (double) fps * TICKS_PER_SECOND));
    }

    /** A film tick to a composition frame index at the given frame rate. */
    public static float frameFromTicks(float ticks, float fps)
    {
        if (fps <= 0F)
        {
            return 0F;
        }

        return ticks / TICKS_PER_SECOND * fps;
    }

    /** The length of a composition (seconds) in whole ticks, at least one tick so a duration is usable. */
    public static int durationTicks(float durationSeconds)
    {
        return Math.max(1, Math.round(Math.max(0F, durationSeconds) * TICKS_PER_SECOND));
    }

    /** The fractional phase inside the current tick, from a float cursor. */
    public static float phase(float tickCursor)
    {
        float whole = (float) Math.floor(tickCursor);

        return tickCursor - whole;
    }
}
