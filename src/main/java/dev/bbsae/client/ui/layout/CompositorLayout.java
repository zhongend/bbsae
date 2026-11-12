package dev.bbsae.client.ui.layout;

import java.util.List;

/**
 * Pure geometry of the compositor's editor area.
 *
 * <h2>Why a pure function</h2>
 * <p>The old layout mixed three things in one method: measuring the panel, clamping the numbers,
 * and handing the rectangles to {@code UIElement}s. Every "panel overlaps / cannot be dragged /
 * resizing the wrong panel" bug then had to be debugged through a live window. This class keeps the
 * arithmetic separate: it reads no UI element, no GL state and no preferences, so every rule
 * (minimum sizes, the splitter hit strips, the breakpoints) is a unit test.</p>
 *
 * <h2>Requested vs effective size</h2>
 * <p>{@link Inputs} carries the sizes the user dragged; {@link Result} reports both the requested
 * and the effective (clamped) values. The panel writes back only the requested ones, so shrinking
 * the window and growing it again restores the user's widths instead of permanently squeezing them.
 * This is also what makes the layout a solid base for the BBS dock tree: the dock stores ratios,
 * this class is the reference for the pixel minimums the dock snaps to.</p>
 */
public final class CompositorLayout
{
    public static final int HEADER_H = 24;
    public static final int STATUS_H = 18;
    public static final int GAP = 6;

    public static final int MIN_LEFT = 140;
    public static final int MIN_RIGHT = 296;
    public static final int MIN_PREVIEW = 120;
    public static final int MIN_TIMELINE = 80;
    public static final int MIN_BODY = 72;
    /** Width of the invisible drag strip centred on a seam. */
    public static final int SPLITTER_HIT = 4;
    /** Below this width the three columns no longer fit side by side; the dock should stack them. */
    public static final int DOCK_BREAKPOINT = 860;
    /** Below this height the editor is in "short window" mode (timeline takes the minimum). */
    public static final int SHORT_HEIGHT = 520;

    public enum Mode
    {
        WIDE, MEDIUM, NARROW, SHORT
    }

    public enum Splitter
    {
        NONE, LEFT, RIGHT, TIMELINE
    }

    /** A rectangle in panel-local pixel coordinates. */
    public record Rect(int x, int y, int w, int h)
    {
        public static final Rect EMPTY = new Rect(0, 0, 0, 0);

        public int ex()
        {
            return this.x + this.w;
        }

        public int ey()
        {
            return this.y + this.h;
        }

        public boolean isEmpty()
        {
            return this.w <= 0 || this.h <= 0;
        }

        public boolean inside(int px, int py)
        {
            return px >= this.x && px < this.ex() && py >= this.y && py < this.ey();
        }
    }

    /**
     * Everything the layout depends on. {@code requested*} are the values the user dragged;
     * {@code *Visible} are the user's panel switches.
     */
    public record Inputs(
        int areaW, int areaH,
        int requestedLeft, int requestedRight, int requestedTimeline,
        boolean leftVisible, boolean rightVisible,
        boolean quickVisible, int requestedQuick,
        boolean renderMode)
    {
        public static Inputs of(int areaW, int areaH, int left, int right, int timeline)
        {
            return new Inputs(areaW, areaH, left, right, timeline, true, true, false, 210, false);
        }
    }

    /**
     * The computed rectangles plus the effective sizes and the mode. Empty rectangles have w/h = 0
     * and must not be used for hit testing.
     */
    public record Result(
        Rect body, Rect left, Rect preview, Rect right, Rect timeline, Rect quick,
        Rect leftSplit, Rect rightSplit, Rect timelineSplit,
        int effectiveLeft, int effectiveRight, int effectiveTimeline,
        Mode mode, boolean needsDock)
    {}

    private CompositorLayout()
    {}

    public static Result compute(Inputs in)
    {
        int areaW = Math.max(0, in.areaW);
        int areaH = Math.max(0, in.areaH);
        Mode mode = modeFor(areaW, areaH);
        int contentH = Math.max(0, areaH - HEADER_H - STATUS_H);

        if (contentH <= 0 || areaW <= 0)
        {
            return new Result(Rect.EMPTY, Rect.EMPTY, Rect.EMPTY, Rect.EMPTY, Rect.EMPTY, Rect.EMPTY,
                Rect.EMPTY, Rect.EMPTY, Rect.EMPTY,
                0, 0, 0, mode, true);
        }

        /* Vertical split: editor body above, timeline below. The timeline never squeezes the body
         * out of existence; in a window too short for both, the body keeps MIN_BODY and the
         * timeline takes whatever remains (down to zero). */
        int timelineH = clampTimelineHeight(areaH, in.requestedTimeline);
        boolean needsDock = mode == Mode.NARROW || mode == Mode.SHORT;

        if (contentH < MIN_BODY + GAP + MIN_TIMELINE)
        {
            needsDock = true;
            timelineH = Math.max(0, contentH - MIN_BODY - GAP);

            if (contentH < MIN_BODY)
            {
                timelineH = 0;
            }
        }

        int bodyH = Math.max(0, contentH - timelineH - (timelineH > 0 ? GAP : 0));
        int bodyY = HEADER_H;
        int timelineY = bodyY + bodyH + (timelineH > 0 ? GAP : 0);

        /* Horizontal split: left column, preview, right column. */
        int leftW = in.leftVisible ? clamp(in.requestedLeft, MIN_LEFT, Math.max(MIN_LEFT, areaW * 42 / 100)) : 0;
        int rightW = in.rightVisible ? clamp(in.requestedRight, MIN_RIGHT, Math.max(MIN_RIGHT, areaW * 42 / 100)) : 0;
        int gaps = (in.leftVisible ? GAP : 0) + (in.rightVisible ? GAP : 0);
        int available = Math.max(0, areaW - gaps - MIN_PREVIEW);

        if (leftW + rightW > available)
        {
            if (areaW < DOCK_BREAKPOINT)
            {
                needsDock = true;
            }

            int total = leftW + rightW;
            int newLeft = total > 0 ? available * leftW / total : 0;

            leftW = Math.max(0, newLeft);
            rightW = Math.max(0, available - leftW);
        }

        int previewX = in.leftVisible ? leftW + GAP : 0;
        int previewW = Math.max(1, areaW - leftW - rightW - gaps);
        int rightX = areaW - rightW;

        /* The quick panel is docked to the right of the timeline, exactly like before; it yields to
         * the timeline on narrow panels instead of hiding it. */
        int quickW = in.quickVisible ? quickWidth(areaW, in.requestedQuick) : 0;
        int timelineW = Math.max(0, areaW - quickW);

        Rect body = bodyH > 0 ? new Rect(0, bodyY, areaW, bodyH) : Rect.EMPTY;
        Rect left = leftW > 0 && bodyH > 0 ? new Rect(0, bodyY, leftW, bodyH) : Rect.EMPTY;
        Rect right = rightW > 0 && bodyH > 0 ? new Rect(rightX, bodyY, rightW, bodyH) : Rect.EMPTY;
        Rect preview = bodyH > 0
            ? new Rect(previewX, bodyY, previewW, bodyH)
            : Rect.EMPTY;
        Rect timeline = timelineH > 0 ? new Rect(0, timelineY, timelineW, timelineH) : Rect.EMPTY;
        Rect quick = quickW > 0 && timelineH > 0 ? new Rect(areaW - quickW, timelineY, quickW, timelineH) : Rect.EMPTY;

        /* Splitter strips only exist where the two neighbouring panels actually meet. A strip that
         * covers a side column (the old full-width timeline splitter) is what swallowed drags that
         * belonged to list rows. */
        int half = SPLITTER_HIT / 2;
        Rect leftSplit = leftW > 0 && bodyH > 0
            ? new Rect(leftW + GAP / 2 - half, bodyY, SPLITTER_HIT, bodyH)
            : Rect.EMPTY;
        Rect rightSplit = rightW > 0 && bodyH > 0
            ? new Rect(areaW - rightW - GAP / 2 - half, bodyY, SPLITTER_HIT, bodyH)
            : Rect.EMPTY;
        Rect timelineSplit = bodyH > 0 && timelineH > 0
            ? new Rect(previewX, bodyY + bodyH + GAP / 2 - half, previewW, SPLITTER_HIT)
            : Rect.EMPTY;

        return new Result(body, left, preview, right, timeline, quick,
            leftSplit, rightSplit, timelineSplit,
            leftW, rightW, timelineH, mode, needsDock);
    }

    /**
     * Which splitter, if any, owns a click at this point.
     *
     * <p>Order matters only on paper: the strips are disjoint by construction, so a point can at
     * most hit one. The test suite pins the important negative case — a point at the bottom of a
     * side column is NOT the timeline splitter.</p>
     */
    public static Splitter hitTest(Result result, int mouseX, int mouseY)
    {
        if (result == null)
        {
            return Splitter.NONE;
        }

        if (result.leftSplit.inside(mouseX, mouseY))
        {
            return Splitter.LEFT;
        }

        if (result.rightSplit.inside(mouseX, mouseY))
        {
            return Splitter.RIGHT;
        }

        if (result.timelineSplit.inside(mouseX, mouseY))
        {
            return Splitter.TIMELINE;
        }

        return Splitter.NONE;
    }

    public static Mode modeFor(int areaW, int areaH)
    {
        if (areaH > 0 && areaH < SHORT_HEIGHT)
        {
            return Mode.SHORT;
        }

        if (areaW < DOCK_BREAKPOINT)
        {
            return Mode.NARROW;
        }

        if (areaW < 1180)
        {
            return Mode.MEDIUM;
        }

        return Mode.WIDE;
    }

    /** Timeline height clamped so the preview body always keeps its floor. */
    public static int clampTimelineHeight(int areaH, int requested)
    {
        int usable = Math.max(0, Math.max(0, areaH) - HEADER_H - STATUS_H - GAP);
        int cap = Math.max(MIN_TIMELINE, Math.min(usable - MIN_BODY, Math.round(usable * 0.62F)));

        return Math.max(MIN_TIMELINE, Math.min(Math.max(MIN_TIMELINE, requested), cap));
    }

    /** Left/right columns fitted around the preview floor. */
    public static int[] fitColumns(int areaW, int left, int right, int gaps, int minPreview)
    {
        int l = Math.max(0, left);
        int r = Math.max(0, right);
        int w = Math.max(0, areaW);
        int preview = Math.max(48, minPreview);
        int available = Math.max(0, w - Math.max(0, gaps) - preview);

        if (l + r <= available || l + r == 0)
        {
            return new int[] {l, r};
        }

        int total = l + r;
        int newLeft = Math.max(0, available * l / total);

        return new int[] {newLeft, Math.max(0, available - newLeft)};
    }

    /** Quick-panel width; it yields to the timeline on narrow panels instead of hiding it. */
    public static int quickWidth(int areaW, int desired)
    {
        int max = Math.max(0, areaW / 2 - 40);

        return Math.max(0, Math.min(Math.max(160, desired), max));
    }

    public static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    /** A short human-readable description for the status bar / diagnostics. */
    public static String describe(Result result)
    {
        if (result == null)
        {
            return "layout: none";
        }

        return "layout: " + result.mode + " left=" + result.effectiveLeft
            + " right=" + result.effectiveRight + " timeline=" + result.effectiveTimeline
            + (result.needsDock ? " (dock recommended)" : "");
    }

    /* Immutable by construction; this only keeps the empty-list accessor honest in tests. */
    public static List<Rect> rects(Result result)
    {
        if (result == null)
        {
            return List.of();
        }

        return List.of(result.body, result.left, result.preview, result.right,
            result.timeline, result.quick, result.leftSplit, result.rightSplit, result.timelineSplit);
    }
}
