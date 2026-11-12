package dev.bbsae.client.replay;

/**
 * The tick schedule of the scripted real-machine interaction run.
 *
 * <p>Pure so the timeline itself is unit-tested: the expensive part of the script is the one
 * client run it drives, so an off-by-one in the schedule ("screenshot before the action") must be
 * caught by a test, not by a wasted launch. All ticks are relative to the script's first tick,
 * which starts after {@code ReplayDebugHook} opened the panel and the viewport framed the scene.</p>
 *
 * <p>Every screenshot is taken {@link #SETTLE_TICKS} ticks after its action so the UI has rendered
 * the new state (gizmo, keyframe rows, inspector fields) before the framebuffer is read.</p>
 */
public final class ReplayScriptTimeline
{
    /** How long the panel gets to open, frame the scene and render before the first action. */
    public static final int OPEN_DELAY = 40;
    /** Ticks between an action and its screenshot. */
    public static final int SETTLE_TICKS = 20;

    public static final int MOVE_ACTION = OPEN_DELAY;
    public static final int MOVE_SHOT = MOVE_ACTION + SETTLE_TICKS;
    public static final int UNDO_ACTION = MOVE_SHOT + SETTLE_TICKS;
    public static final int UNDO_SHOT = UNDO_ACTION + SETTLE_TICKS;
    public static final int REDO_ACTION = UNDO_SHOT + 10;
    public static final int FORM_ACTION = REDO_ACTION + SETTLE_TICKS;
    public static final int FORM_SHOT = FORM_ACTION + SETTLE_TICKS;
    public static final int LIST_ACTION = FORM_SHOT + SETTLE_TICKS;
    public static final int LIST_SHOT = LIST_ACTION + SETTLE_TICKS;
    public static final int CLEANUP_ACTION = LIST_SHOT + SETTLE_TICKS;
    public static final int QUIT_TICK = CLEANUP_ACTION + SETTLE_TICKS;

    /** Number of screenshots the script produces: move, undo, form, list. */
    public static final int SHOT_COUNT = 4;

    private ReplayScriptTimeline()
    {}

    public static String shotName(int index)
    {
        return switch (index)
        {
            case 0 -> "replay-interact-move";
            case 1 -> "replay-interact-undo";
            case 2 -> "replay-interact-form";
            case 3 -> "replay-interact-list";
            default -> "replay-interact-" + index;
        };
    }

    /** Schedules are strictly increasing, action before screenshot before cleanup. */
    public static boolean isWellOrdered()
    {
        return MOVE_ACTION < MOVE_SHOT
            && MOVE_SHOT < UNDO_ACTION
            && UNDO_ACTION < UNDO_SHOT
            && UNDO_SHOT < REDO_ACTION
            && REDO_ACTION < FORM_ACTION
            && FORM_ACTION < FORM_SHOT
            && FORM_SHOT < LIST_ACTION
            && LIST_ACTION < LIST_SHOT
            && LIST_SHOT < CLEANUP_ACTION
            && CLEANUP_ACTION < QUIT_TICK;
    }
}
