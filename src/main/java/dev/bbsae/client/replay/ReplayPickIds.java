package dev.bbsae.client.replay;

/**
 * The pick-id arithmetic shared by the stencil pass and the click handler.
 *
 * <p>The pick pass writes ids as {@code gizmoMax + 1 + replayListIndex}; the click handler turns
 * the read-back id into a list index. The two conversions are the same function inverted, and a
 * one-off mistake here selects the wrong actor (or silently -1). Keeping the formulas in one pure
 * place makes the round trip testable without a GL context, and the gizmo's own id range explicit
 * so a replay can never be confused with a gizmo handle.</p>
 *
 * <p>The gizmo bounds are passed in rather than read from {@code Gizmo} so this class stays
 * loadable in the headless test JVM (BBS' UI classes require the game bootstrap).</p>
 */
public final class ReplayPickIds
{
    private ReplayPickIds()
    {}

    /** First id available to replays: right after the gizmo's handles. */
    public static int offsetFor(int gizmoMax)
    {
        return Math.max(0, gizmoMax) + 1;
    }

    /** The id the pick pass writes for the replay at {@code replayIndex}. */
    public static int idFor(int replayIndex, int gizmoMax)
    {
        return offsetFor(gizmoMax) + Math.max(0, replayIndex);
    }

    /**
     * The replay list index for a read-back id, or -1 when the pixel was not a replay (nothing,
     * a form part, or a gizmo handle).
     */
    public static int indexFor(int pickedId, int gizmoMax, int replayCount)
    {
        int offset = offsetFor(gizmoMax);

        if (pickedId < offset || replayCount <= 0)
        {
            return -1;
        }

        int index = pickedId - offset;

        return index < replayCount ? index : -1;
    }

    /** True when the id belongs to the gizmo's own handle range {@code [gizmoMin, gizmoMax]}. */
    public static boolean isGizmoHandle(int pickedId, int gizmoMin, int gizmoMax)
    {
        return pickedId >= gizmoMin && pickedId <= gizmoMax;
    }
}
