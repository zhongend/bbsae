package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;


import java.util.HashMap;
import java.util.Map;

/**
 * The compositor's UI metrics, buttons and tooltip text.
 *
 * <h2>Why a metrics object</h2>
 * <p>The panel used to hardcode "one size" of everything: 18px rows, 20px buttons, 90px label
 * columns, 300px inspector. At Minecraft GUI scale 3–4 that reads as a wall of oversized controls,
 * while BBS's own widgets stay at {@code UIConstants.CONTROL_HEIGHT = 16}. The fix is not "make
 * everything smaller" but <b>one density number</b> that every row, button, label column, panel
 * width and toolbar derives from — exactly like AE's "UI Brightness/Scale" presets, except these
 * three tiers follow BBS's own scale when set to auto.</p>
 *
 * <h2>Why flat buttons</h2>
 * <p>BBS's {@code UIButton} fills itself with {@code BBSSettings.primaryColor}. Forty of those in an
 * inspector is a wall of saturated blocks — the accent stops meaning anything and the panel looks
 * loud. AE's own UI uses flat neutral chips with a single accent only on the primary action, so
 * {@link UICompButton} does the same: raised-surface fill, divider border, left-aligned label, accent
 * reserved for primary/danger.</p>
 */
public final class CompUi
{
    public static final int DENSITY_AUTO = 0;
    public static final int DENSITY_COMPACT = 1;
    public static final int DENSITY_NORMAL = 2;
    public static final int DENSITY_COMFORTABLE = 3;

    /** Buttons and inputs. */
    private static int control = 16;
    /** A labelled row inside a section. */
    private static int row = 18;
    /** Vertical gap between rows and sections. */
    private static int gap = 3;
    /** Label column in a "label : control" row. */
    private static int label = 76;
    /** Square icon button size. */
    private static int icon = 18;
    /** Height of a strip of buttons (toolbars, tab bars). */
    private static int bar = 22;
    /** The resolved tier, 1..3. */
    private static int density = DENSITY_NORMAL;

    private CompUi()
    {}

    /* ---------------------------------------------------------------- density */

    /** Applies the saved preference (0 = follow BBS) and resolves the tier. */
    public static void configure(int preference)
    {
        density = preference == DENSITY_AUTO ? autoDensity(bbsScale()) : Math.max(1, Math.min(3, preference));

        switch (density)
        {
            case DENSITY_COMPACT ->
            {
                control = 14;
                row = 16;
                gap = 2;
                label = 62;
                icon = 16;
                bar = 20;
            }
            case DENSITY_COMFORTABLE ->
            {
                control = 20;
                row = 22;
                gap = 4;
                label = 92;
                icon = 22;
                bar = 28;
            }
            default ->
            {
                control = 16;
                row = 18;
                gap = 3;
                label = 76;
                icon = 18;
                bar = 22;
            }
        }
    }

    /**
     * Maps BBS's effective GUI scale to a density: a big scale means big pixels, so the panel needs
     * compact controls to fit; a small scale leaves room for comfortable ones.
     */
    public static int autoDensity(float scale)
    {
        if (scale < 2.01F)
        {
            return DENSITY_COMFORTABLE;
        }

        if (scale < 3.01F)
        {
            return DENSITY_NORMAL;
        }

        return DENSITY_COMPACT;
    }

    /** BBS's applied GUI scale, or 2 when it cannot be read (headless tests, early bootstrap). */
    public static float bbsScale()
    {
        try
        {
            float scale = BBSModClient.getGUIScale();

            return scale <= 0.01F ? 2F : scale;
        }
        catch (RuntimeException | LinkageError e)
        {
            return 2F;
        }
    }

    public static int density()
    {
        return density;
    }

    public static int control()
    {
        return control;
    }

    public static int row()
    {
        return row;
    }

    public static int gap()
    {
        return gap;
    }

    public static int label()
    {
        return label;
    }

    public static int icon()
    {
        return icon;
    }

    public static int bar()
    {
        return bar;
    }

    public static int leftWidth()
    {
        return density == DENSITY_COMPACT ? 152 : density == DENSITY_COMFORTABLE ? 208 : 178;
    }

    public static int rightWidth()
    {
        return density == DENSITY_COMPACT ? 268 : density == DENSITY_COMFORTABLE ? 348 : 306;
    }

    public static int timelineHeight()
    {
        return density == DENSITY_COMPACT ? 232 : density == DENSITY_COMFORTABLE ? 288 : 252;
    }

    /* ------------------------------------------------------------- responsive */

    /**
     * Caps the timeline so the preview body can never be squeezed out of the panel.
     *
     * <p>The editor has one fixed header (24 px), one status line (18 px) and a 6 px gap. Above
     * that the preview wants at least 72 px and the timeline keeps at most 62% of the usable
     * height. A window 300 px tall therefore gets a compact strip, not a body with a negative
     * height that overlaps the timeline.</p>
     */
    public static int fitTimelineHeight(int areaHeight, int desired)
    {
        if (areaHeight <= 0)
        {
            return Math.max(80, desired);
        }

        int usable = Math.max(0, areaHeight - 24 - 18 - 6);
        int cap = Math.max(80, Math.min(usable - 72, Math.round(usable * 0.62F)));

        return Math.max(80, Math.min(Math.max(80, desired), cap));
    }

    /** Preview body height left after the header, status and fitted timeline. */
    public static int bodyHeight(int areaHeight, int timelineHeight)
    {
        if (areaHeight <= 0)
        {
            return 160;
        }

        return Math.max(56, areaHeight - 24 - 18 - 6 - Math.max(0, timelineHeight));
    }

    /** Maximum width a side column may take without making the preview a sliver. */
    public static int maxSideWidth(int areaWidth)
    {
        if (areaWidth <= 0)
        {
            return 360;
        }

        return Math.max(96, Math.min(420, areaWidth * 42 / 100));
    }

    /**
     * Fits left/right columns around a preview floor.
     *
     * @return {@code [left, right]} widths, each possibly 0 when that column is hidden.
     */
    public static int[] fitColumns(int areaWidth, int left, int right, int gaps, int minPreview)
    {
        int l = Math.max(0, left);
        int r = Math.max(0, right);
        int w = Math.max(0, areaWidth);
        int preview = Math.max(48, minPreview);
        int available = Math.max(0, w - gaps - preview);

        if (l + r <= available || l + r == 0)
        {
            return new int[] {l, r};
        }

        int total = l + r;
        int newLeft = Math.max(0, available * l / total);
        int newRight = Math.max(0, available - newLeft);

        return new int[] {newLeft, newRight};
    }

    /** Quick-panel width; it yields to the timeline on narrow panels instead of hiding it. */
    public static int quickWidth(int areaWidth, int desired)
    {
        int max = Math.max(0, areaWidth / 2 - 40);

        return Math.max(0, Math.min(Math.max(160, desired), max));
    }

    /**
     * Horizontal span of the timeline-height splitter, in body coordinates.
     *
     * <p>Only the preview column gets the grip. Spanning the whole body put the grip over the
     * bottom four pixels of the side columns, so dragging a row at the bottom edge of the
     * effect/asset list resized the timeline instead of the row — the reported "dragging data in
     * the panel also drags the panel size" bug.</p>
     *
     * @return {@code [x, width]}; width is 0 when the preview column has collapsed.
     */
    public static int[] timelineSplitterSpan(int areaWidth, int left, int right, int gapLeft, int gapRight)
    {
        int l = Math.max(0, left);
        int r = Math.max(0, right);
        int gl = Math.max(0, gapLeft);
        int gr = Math.max(0, gapRight);
        int width = Math.max(0, areaWidth);
        int x = Math.min(width, l + gl);
        int w = Math.max(0, width - l - r - gl - gr);

        return new int[] {x, Math.min(w, Math.max(0, width - x))};
    }

    public static String densityName(int tier)
    {
        return switch (tier)
        {
            case DENSITY_COMPACT -> "小";
            case DENSITY_COMFORTABLE -> "大";
            default -> "中";
        };
    }

    /* --------------------------------------------------------- toolbar geometry */

    /** Width of the optional project header action, in UI pixels. */
    public static final int PROJECTS_W = 54;

    /**
     * Layout of the preview's right-hand icon rail (camera view, 3D space, camera tool, new camera,
     * camera settings, pen, entity replay).
     *
     * <p>The rail and the floating preview bar used to share the same 20 px row with no collision
     * handling: any preview narrower than ~458 px had icons drawn on top of the bar's filter chip,
     * so clicking "线性：自动" opened the camera settings instead. The rail now degrades from the
     * left (replay → pen → settings → new camera → camera tool → 3D → camera view) and reserves a
     * usable bar width; the arithmetic lives here so a unit test can lock it.</p>
     */
    public static PreviewRailLayout previewRailLayout(int previewWidth)
    {
        int w = Math.max(0, previewWidth);
        int icons = 7;
        int count = 0;

        /* Keep at least MIN_BAR_WIDTH for the preview bar's own controls; drop rail icons from the
         * left until that fits. */
        for (int index = 0; index < icons; index++)
        {
            int left = w - 28 - 22 * index;

            if (left - 12 >= MIN_BAR_WIDTH)
            {
                count = index + 1;
            }
            else
            {
                break;
            }
        }

        int[] xs = new int[icons];
        boolean[] shown = new boolean[icons];
        int railLeft = w - 8;

        for (int index = 0; index < icons; index++)
        {
            shown[index] = index < count;
            xs[index] = shown[index] ? w - 28 - 22 * index : -1;

            if (shown[index])
            {
                railLeft = Math.min(railLeft, xs[index]);
            }
        }

        int available = count > 0 ? railLeft - 12 : w - 12;

        return new PreviewRailLayout(Math.max(32, Math.min(PREVIEW_BAR_MAX, available)), xs, shown);
    }

    /**
     * Overflow layout of a fixed tab strip (inspector / timeline / left column tabs).
     *
     * <p>Tabs are absolutely positioned with their natural width. When the dock panel is dragged
     * narrower than the sum, the trailing tabs are hidden and a "»" control (20 px) cycles the
     * active page. Returns {@code [visibleCount, overflowX, overflowFlag]}; {@code overflowX} is
     * relative to the bar's left edge and only meaningful when the flag is 1.</p>
     */
    public static int[] tabStripLayout(int[] widths, int available)
    {
        int width = Math.max(0, available);
        int total = 0;

        for (int item : widths)
        {
            total += Math.max(0, item) + 2;
        }

        if (total <= width || widths.length == 0)
        {
            return new int[] {widths.length, width, 0};
        }

        int limit = Math.max(1, width - 20);
        int used = 0;
        int count = 0;

        for (int item : widths)
        {
            if (used + Math.max(0, item) > limit && count > 0)
            {
                break;
            }

            used += Math.max(0, item) + 2;
            count++;
        }

        return new int[] {Math.max(1, count), Math.max(0, width - 20), 1};
    }

    /**
     * Visible tab window that always includes the active tab.
     *
     * <p>{@link #tabStripLayout(int[], int)} always returns the first N tabs. When the active page
     * moved behind the right edge, the bar showed no label for it — only a tiny "»" and an accent,
     * which read as "there are no more sections". This window scrolls to the active tab and
     * reserves an edge control on each side that has hidden tabs.</p>
     *
     * @return {firstVisible, endExclusive, hasHiddenLeft, hasHiddenRight}
     */
    public static int[] tabStripWindow(int[] widths, int available, int active, int arrowWidth)
    {
        int count = widths.length;
        int width = Math.max(0, available);
        int edge = Math.max(0, arrowWidth);
        int total = 0;

        for (int item : widths)
        {
            total += Math.max(0, item) + 2;
        }

        if (count == 0 || total <= width)
        {
            return new int[] {0, count, 0, 0};
        }

        int limit = Math.max(1, width - edge * 2);
        int capacity = 0;
        int used = 0;

        for (int item : widths)
        {
            if (used + Math.max(0, item) > limit && capacity > 0)
            {
                break;
            }

            used += Math.max(0, item) + 2;
            capacity++;
        }

        capacity = Math.max(1, Math.min(count, capacity));
        active = Math.max(0, Math.min(count - 1, active));

        int start = active < capacity ? 0 : Math.min(count - capacity, active - capacity + 1);

        start = Math.max(0, Math.min(start, count - 1));

        int visible = 0;
        used = 0;

        for (int index = start; index < count; index++)
        {
            if (used + Math.max(0, widths[index]) > limit && visible > 0)
            {
                break;
            }

            used += Math.max(0, widths[index]) + 2;
            visible++;
        }

        int end = Math.min(count, start + Math.max(1, visible));

        return new int[] {start, end, start > 0 ? 1 : 0, end < count ? 1 : 0};
    }

    /** Minimum width of the floating preview bar when the icon rail competes for the same row. */
    public static final int MIN_BAR_WIDTH = 150;
    public static final int PREVIEW_BAR_MAX = 292;

    /** Result of {@link #previewRailLayout(int)}; indices are in visual order right to left. */
    public static final class PreviewRailLayout
    {
        /** Width that can be passed to {@link #previewBarLayout(int, int, boolean, boolean)}. */
        public final int barWidth;
        public final int[] xs;
        public final boolean[] shown;

        PreviewRailLayout(int barWidth, int[] xs, boolean[] shown)
        {
            this.barWidth = barWidth;
            this.xs = xs;
            this.shown = shown;
        }
    }

    /**
     * Geometry of the editor's top bar.
     *
     * <p>This is pure arithmetic on purpose. The old header placed the right-hand buttons with
     * {@code x = 1F - right} and then advanced {@code right} by the <em>next</em> button's width,
     * which made "设置" (48 px wide, placed immediately after the 18 px focus button) overlap the
     * focus button by 22 px at every width. A layout that can be asserted in a unit test cannot
     * regress that way unnoticed.</p>
     *
     * <p>Priority, left to right: the right cluster is anchored to the right edge and grows
     * leftwards; the left cluster grows rightwards. When they would collide, low-priority actions
     * disappear (save → export → render → timecode → frame step → replay → title → projects → play)
     * rather than overlap. Focus, settings and the layout button are never hidden. The effect
     * library is not a header action any more: it is the left column's first tab.</p>
     */
    public static HeaderLayout headerLayout(int width, int control)
    {
        int w = Math.max(0, width);
        int c = Math.max(12, control);

        boolean showTitle = w >= 880;
        boolean showReplay = w >= 720;
        boolean showTimecode = w >= 620;
        boolean showFrames = w >= 480;
        boolean showRender = w >= 780;
        boolean showExport = w >= 860;
        boolean showSave = w >= 980;
        boolean showPlay = w >= 120;
        boolean showProjects = true;

        int[] left;
        int[] right;
        int projectsX;

        while (true)
        {
            left = leftPositions(w, c, showTitle, showReplay, showPlay, showTimecode, showFrames);
            right = rightPositions(w, c, showRender, showExport, showSave);

            projectsX = right[6] - 8 - PROJECTS_W;

            /* Optional actions must not start off-screen; hide them before they draw over the
             * left cluster. The effect library now lives in the left column, so it no longer
             * competes with the playback controls for header width. */
            if (showProjects && projectsX < 8)
            {
                showProjects = false;
                continue;
            }

            int clusterStart = showProjects ? projectsX : right[6];

            if (left[9] + 8 <= clusterStart)
            {
                break;
            }

            if (!showTitle && !showReplay && !showPlay && !showTimecode && !showFrames)
            {
                break;
            }

            if (showSave)
            {
                showSave = false;
            }
            else if (showExport)
            {
                showExport = false;
            }
            else if (showRender)
            {
                showRender = false;
            }
            else if (showTimecode)
            {
                showTimecode = false;
            }
            else if (showFrames)
            {
                showFrames = false;
            }
            else if (showReplay)
            {
                showReplay = false;
            }
            else if (showTitle)
            {
                showTitle = false;
            }
            else if (showProjects)
            {
                showProjects = false;
            }
            else
            {
                showPlay = false;
            }
        }

        return new HeaderLayout(w, c, showTitle, showReplay, showPlay, showTimecode, showFrames,
            showRender, showExport, showSave, showProjects,
            showProjects ? projectsX : -1, left, right);
    }

    /** Left cluster: title, replay selector, play, frame step, timecode. */
    private static int[] leftPositions(int w, int c, boolean title, boolean replay, boolean play,
                                       boolean timecode, boolean frames)
    {
        int x = 8;
        int titleX = x;

        if (title)
        {
            x += 92;
        }

        int replayX = x;
        int replayW = Math.max(96, Math.min(150, w - 600));

        if (replay)
        {
            x += replayW + 6;
        }

        int playX = x;

        if (play)
        {
            x += 48;
        }

        int prevX = x;
        int nextX = x + c + 2;

        if (frames)
        {
            x += c * 2 + 8;
        }

        int timecodeX = x;
        int timecodeW = Math.max(70, Math.min(86, w - x - 330));

        if (timecode)
        {
            x += timecodeW;
        }

        return new int[] {titleX, replayX, replayW, playX, prevX, nextX, timecodeX, timecodeW, x, x};
    }

    /** Right cluster: anchored to {@code width - margin}, grown leftwards by each width + gap. */
    private static int[] rightPositions(int w, int c, boolean render, boolean export, boolean save)
    {
        int right = Math.max(0, w - 8);
        int focusX = right - c;

        right = focusX - 8;

        int settingsX = right - 48;

        right = settingsX - 8;

        /* The dock-layout control sits next to settings (it is always visible: it is the only way
         * to unlock panel dragging). Everything optional moves further left. */
        int layoutX = right - 48;

        right = layoutX - 8;

        int renderX = -1;
        int exportX = -1;
        int saveX = -1;

        if (render)
        {
            renderX = right - 54;
            right = renderX - 8;
        }

        if (export)
        {
            exportX = right - 54;
            right = exportX - 8;
        }

        if (save)
        {
            saveX = right - 48;
            right = saveX - 8;
        }

        int start = focusX;

        if (settingsX < start) start = settingsX;
        if (layoutX < start) start = layoutX;
        if (renderX >= 0 && renderX < start) start = renderX;
        if (exportX >= 0 && exportX < start) start = exportX;
        if (saveX >= 0 && saveX < start) start = saveX;

        return new int[] {focusX, settingsX, layoutX, renderX, exportX, saveX, start};
    }

    /** Resolved top-bar layout. All X values are relative to the 24 px header. */
    public static final class HeaderLayout
    {
        public final int width;
        public final int control;
        public final boolean showTitle;
        public final boolean showReplay;
        public final boolean showPlay;
        public final boolean showTimecode;
        public final boolean showFrames;
        public final boolean showRender;
        public final boolean showExport;
        public final boolean showSave;
        /** Optional project button: hidden before it can overlap the left cluster. */
        public final boolean showProjects;

        public final int titleX;
        public final int replayX;
        public final int replayW;
        public final int playX;
        public final int prevX;
        public final int nextX;
        public final int timecodeX;
        public final int timecodeW;
        public final int focusX;
        public final int settingsX;
        public final int layoutX;
        public final int renderX;
        public final int exportX;
        public final int saveX;
        public final int projectsX;
        public final int leftEnd;
        public final int rightStart;

        HeaderLayout(int width, int control, boolean showTitle, boolean showReplay, boolean showPlay,
                     boolean showTimecode, boolean showFrames, boolean showRender, boolean showExport,
                     boolean showSave, boolean showProjects,
                     int projectsX, int[] left, int[] right)
        {
            this.width = width;
            this.control = control;
            this.showTitle = showTitle;
            this.showReplay = showReplay;
            this.showPlay = showPlay;
            this.showTimecode = showTimecode;
            this.showFrames = showFrames;
            this.showRender = showRender;
            this.showExport = showExport;
            this.showSave = showSave;
            this.showProjects = showProjects;
            this.projectsX = projectsX;

            this.titleX = left[0];
            this.replayX = left[1];
            this.replayW = left[2];
            this.playX = left[3];
            this.prevX = left[4];
            this.nextX = left[5];
            this.timecodeX = left[6];
            this.timecodeW = left[7];
            this.leftEnd = left[8];

            this.focusX = right[0];
            this.settingsX = right[1];
            this.layoutX = right[2];
            this.renderX = right[3];
            this.exportX = right[4];
            this.saveX = right[5];
            this.rightStart = right[6];
        }
    }

    /**
     * Geometry of the floating preview bar.
     *
     * <p>The filter chip is a collapsible group, not a three-state cycle: the header stays in the
     * row and a small three-row menu opens below it. The chip is kept even when the wide toggles
     * no longer fit, because hiding the one control this change is about would make narrow
     * previews unable to choose a filter at all. The FX / mask / BBS toggles are dropped from the
     * right (BBS first) instead of being squeezed.</p>
     */
    public static PreviewBarLayout previewBarLayout(int previewWidth, int control, boolean barCollapsed, boolean filterExpanded)
    {
        int c = Math.max(12, control);
        int toggleW = 58;
        int filterW = 82;
        int collapseW = 20;
        int pad = 4;
        int gap = 2;

        if (barCollapsed)
        {
            return new PreviewBarLayout(24, true, false, false, false, false, true,
                -1, -1, -1, -1, 2, -1, 22, 110, 58, false, c);
        }

        int width = Math.max(32, Math.min(292, Math.max(0, previewWidth) - 12));
        int collapseX = width - pad - collapseW;
        int filterX = collapseX - gap - filterW;
        boolean showFilter = filterX >= pad;
        int toggleLimit = (showFilter ? filterX : collapseX) - gap;
        int x = pad;
        boolean showEffects = false;
        boolean showMasks = false;
        boolean showBbs = false;

        if (x + toggleW <= toggleLimit)
        {
            showEffects = true;
            x += toggleW + gap;
        }

        if (x + toggleW <= toggleLimit)
        {
            showMasks = true;
            x += toggleW + gap;
        }

        if (x + toggleW <= toggleLimit)
        {
            showBbs = true;
            x += toggleW + gap;
        }

        int effectsX = pad;
        int masksX = effectsX + (showEffects ? toggleW + gap : 0);
        int bbsX = masksX + (showMasks ? toggleW + gap : 0);

        int menuW = Math.min(112, Math.max(70, width - pad * 2));
        int menuX = showFilter ? Math.max(pad, Math.min(filterX, width - pad - menuW)) : -1;
        boolean menuVisible = showFilter && filterExpanded;

        return new PreviewBarLayout(width, false, showEffects, showMasks, showBbs, showFilter, true,
            effectsX, masksX, bbsX, filterX, collapseX, menuX, 22, menuW, 58, menuVisible, c);
    }

    /** Resolved preview-bar layout. X/Y of the menu are relative to the bar. */
    public static final class PreviewBarLayout
    {
        public final int width;
        public final boolean barCollapsed;
        public final boolean showEffects;
        public final boolean showMasks;
        public final boolean showBbs;
        public final boolean showFilter;
        public final boolean showCollapse;
        public final int effectsX;
        public final int masksX;
        public final int bbsX;
        public final int filterX;
        public final int collapseX;
        public final int filterMenuX;
        public final int filterMenuY;
        public final int filterMenuW;
        public final int filterMenuH;
        public final boolean filterMenuVisible;
        public final int control;

        PreviewBarLayout(int width, boolean barCollapsed, boolean showEffects, boolean showMasks,
                         boolean showBbs, boolean showFilter, boolean showCollapse, int effectsX,
                         int masksX, int bbsX, int filterX, int collapseX, int filterMenuX,
                         int filterMenuY, int filterMenuW, int filterMenuH, boolean filterMenuVisible,
                         int control)
        {
            this.width = width;
            this.barCollapsed = barCollapsed;
            this.showEffects = showEffects;
            this.showMasks = showMasks;
            this.showBbs = showBbs;
            this.showFilter = showFilter;
            this.showCollapse = showCollapse;
            this.effectsX = effectsX;
            this.masksX = masksX;
            this.bbsX = bbsX;
            this.filterX = filterX;
            this.collapseX = collapseX;
            this.filterMenuX = filterMenuX;
            this.filterMenuY = filterMenuY;
            this.filterMenuW = filterMenuW;
            this.filterMenuH = filterMenuH;
            this.filterMenuVisible = filterMenuVisible;
            this.control = control;
        }
    }

    /* ---------------------------------------------------------------- widgets */

    /** A neutral, left-aligned button. The workhorse — replaces the accent-filled default. */
    public static UICompButton flat(String label, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.FLAT)
            .hint(describe(label));
    }

    public static UICompButton flat(String label, String tooltip, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.FLAT)
            .hint(tooltip == null ? describe(label) : tooltip);
    }

    /** The single accent action of a surface (play, create, apply). Use sparingly. */
    public static UICompButton primary(String label, String tooltip, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.PRIMARY)
            .hint(tooltip == null ? describe(label) : tooltip);
    }

    /** Destructive actions. Muted red, not a traffic light. */
    public static UICompButton danger(String label, String tooltip, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.DANGER)
            .hint(tooltip == null ? describe(label) : tooltip);
    }

    /** Icon-only button for dense toolbars; the tooltip carries the whole meaning. */
    public static UICompIconButton iconButton(Icon symbol, String tooltip, Runnable action)
    {
        return new UICompIconButton(symbol, tooltip, action);
    }

    /** A section sub-heading: small, muted, no box — the AE "group label" look. */
    public static UILabel heading(String text)
    {
        UILabel label = new UILabel(IKey.constant(text));

        label.color(0xFF8C96A5, false);
        label.h(density == DENSITY_COMPACT ? 13 : 15);

        return label;
    }

    /** Applies the shared hint to any BBS widget (toggle, trackpad, icon...). */
    public static <T extends UIElement> T hint(T element, String tooltip)
    {
        if (tooltip != null && !tooltip.isEmpty())
        {
            element.tooltip(IKey.constant(tooltip));
        }

        return element;
    }

    /** The default width for a section's label column. */
    public static int labelWidth()
    {
        return label;
    }

    /** Buttons that live in a toolbar strip. */
    public static UICompButton tool(String label, String tooltip, Runnable action)
    {
        UICompButton button = flat(label, tooltip, action);

        button.h(control);

        return button;
    }

    /**
     * Reads the density preference from a project (0 when absent) so a project can carry its own
     * UI scale, and falls back to the global default.
     */
    public static int preferenceOf(CompProject project)
    {
        return project == null || project.settings == null
            ? DENSITY_AUTO
            : Math.max(0, Math.min(3, project.settings.uiDensity));
    }

    /* ---------------------------------------------------------------- tooltips */

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    static
    {
        /* Inspector / layer */
        describe("重命名…", "给所选轨道起一个新名字，只改显示名，不改 id");
        describe("复制", "复制所选轨道的内容与关键帧，副本放在原轨道上方并右移一点");
        describe("删除", "删除所选轨道；合成里的其它图层不会跟着移动");
        describe("移除", "把这一项从列表里移除，不影响其它项");
        describe("重置变换", "把所选图层的位置、旋转、缩放恢复成默认值（0 / 0 / 100%）");
        describe("复制所选", "把当前多选的所有图层各复制一份");
        describe("删除所选", "删除当前多选的所有图层（可用 Ctrl+Z 撤销）");
        describe("选择最上层轨道", "选中时间轴最上面的一条轨道，方便快速开始编辑");
        describe("跳到入点", "把播放头移到所选轨道的入点");
        describe("跳转", "把播放头移到这个标记所在的时间");
        describe("进入预合成", "打开这个预合成图层的内部合成，Esc 可返回");

        /* Time / layer timing */
        describe("入点=播放头", "把所选轨道的入点对齐到当前播放头；源画面不滑动");
        describe("出点=播放头", "把所选轨道的出点对齐到当前播放头");
        describe("在播放头添加标记", "在播放头位置加一个合成标记，便于跳转和对齐");
        describe("在播放头添加图层标记", "在播放头的这一层上加一个图层标记");
        describe("保存图层动画", "把这一层的变换 / 不透明度 / 参数动画保存成可复用的动画预设");

        /* Shapes */
        describe("矩形", "新建一个矩形形状；随后可在下面单独调四个角的圆角");
        describe("三角形", "新建一个三角形；三个角可以分别设圆角");
        describe("星形", "新建星形；可调角数与内半径");
        describe("多边形", "新建正多边形；可调边数与圆角");
        describe("贝塞尔路径", "新建可编辑的贝塞尔路径，每个点都有进出切线");
        describe("添加路径点", "在路径末尾追加一个控制点");
        describe("删除最后一点", "移除路径的最后一个控制点");
        describe("全部归零", "把这个形状所有角的圆角清零，恢复尖角");
        describe("添加蒙版点", "在蒙版路径末尾追加一个点");
        describe("添加矩形蒙版", "新建一个矩形蒙版，用来裁切这一层");
        describe("添加椭圆蒙版", "新建一个椭圆蒙版，用来裁切这一层");

        /* Alignment / multi-select */
        describe("左", "把所选图层的左边缘对齐到合成左边");
        describe("右", "把所选图层的右边缘对齐到合成右边");
        describe("上", "把所选图层的上边缘对齐到合成顶部");
        describe("下", "把所选图层的下边缘对齐到合成底部");
        describe("水平中", "把所选图层的水平中心对齐到合成中线");
        describe("垂直中", "把所选图层的垂直中心对齐到合成中线");
        describe("水平等距", "让所选图层在水平方向按中心等距分布（至少选三个）");
        describe("垂直等距", "让所选图层在垂直方向按中心等距分布（至少选三个）");
        describe("合成预渲染", "把所选图层合并进一个子合成，再用一个预合成图层替换它们；入点会整体前移");
        describe("编组（空对象）", "在所选图层的中心建一个空对象并全部挂上去，拖动空对象即可整体移动");
        describe("显示", "把所选图层全部设为可见");
        describe("隐藏", "把所选图层全部设为不可见，渲染和导出也会跳过");
        describe("锁定", "锁定所选图层，避免在预览里误拖");

        /* Camera */
        describe("重置摄像机", "位置回到合成中心、距离回到 1:1、目标对准中心、滚转归零");
        describe("对齐到当前视图（2D 平移/缩放归零）", "保留透视与目标，只把 2D 的平移/缩放/滚转恢复成 1:1");
        describe("自动选择摄像机（最上层）", "取消手动指定，改为自动使用时间轴上最上层的可见摄像机");
        describe("设为活动摄像机", "让整条合成使用这个摄像机；同一时间只有一个活动摄像机");
        describe("当前活动摄像机", "这个摄像机正在驱动画面；点一下可以重新确认");

        /* Effects / styles / grade */
        describe("＋ 添加调色效果", "给所选图层加一个调色效果，并在右侧展开它的参数");
        describe("＋ 为整个合成添加调色调整层", "新建一个调整层并加上调色效果，作用是它下面的所有图层");
        describe("移除调色", "删除这个调色效果");
        describe("重置调色", "色轮、曲线和基础参数全部回到中性");
        describe("添加样式…", "给所选图层添加一个图层样式（投影、发光、描边等）");
        describe("添加文字动画器", "添加一个逐字动画器：位置、缩放、旋转、透明度、抖动");
        describe("添加参数", "在合成里添加一个可在表达式里引用的主参数");

        /* Precomp / output */
        describe("＋ 新建预合成（当前尺寸）", "以当前合成尺寸新建一个空子合成，并把这一层指向它");
        describe("使用合成时长", "把导出时长设成整个合成的长度");
        describe("应用时长到合成与 BBS 时间轴", "把这里的时长写进合成与 BBS 的录像长度");
        describe("同步到 BBS 时间轴（原版录制按钮也用这个长度）", "用 BBS 原版录像器的长度覆盖这里的时长");
        describe("应用比例", "把当前宽高比应用到导出尺寸，保持像素数不变");
        describe("添加 H.264 预设", "新增一个 H.264 审阅输出预设");
        describe("添加 ProRes 422 HQ", "新增一个 ProRes 422 HQ 输出预设（体积大、画质高）");
        describe("添加 ProRes 4444 Alpha", "新增一个带 alpha 通道的 ProRes 4444 输出预设");
        describe("添加任务", "把当前设置加入渲染队列");
        describe("开始", "开始渲染选中的任务");
        describe("全部开始", "按顺序渲染队列里的全部任务");
        describe("取消", "取消正在渲染的任务");
        describe("重试", "把失败的任务重新排队");
        describe("打开文件夹", "在系统文件管理器里打开导出目录");
        describe("预设…", "切换输出预设");

        /* Curves / presets */
        describe("保存", "保存工程到磁盘（Ctrl+S）");
        describe("播放", "播放 / 暂停合成预览（空格）");
        describe("渲染", "打开渲染队列，批量输出");
        describe("导出", "用 BBS 原生录像器把这一条合成录成视频");
        describe("设置", "合成器设置：导出时长、默认图层时长、界面密度、快捷键方案");
        describe("关键帧", "展开 / 收起所选轨道的属性关键帧抽屉");
        describe("吸附", "拖动与裁剪时吸附到播放头、标记和其它轨道（S）");

        /* Presets */
        describe("存为动画", "把当前图层的变换与不透明度动画存成预设");
        describe("存为效果", "把当前图层整条效果栈存成预设，可套到别的图层");
        describe("存为样式", "把当前图层的图层样式存成预设");
        describe("存为形状", "把当前图层的形状组存成预设");
        describe("存为整套", "把动画、效果、样式、形状和填充色一起存成预设");
        describe("删除这个预设", "从工程里删掉这个用户预设（内置预设不可删）");

        /* Misc */
        describe("导入图片…", "为这一层选择一张图片作为素材");
        describe("导入字体（ttf / otf）…", "从文件导入字体，供文字图层使用");
        describe("＋ 添加 BBS 预览层", "添加一个显示 BBS 世界/影片实时画面的图层");
        describe("＋ 添加空对象", "添加一个不产生像素的控制层，可作为其它图层的父级");
        describe("＋ 新建形状", "在选中图层里新建一个形状（矩形/三角/星形/多边形/路径）");
        describe("工程列表…", "返回工程列表，切换或新建工程");
        describe("快捷键表", "查看所有快捷键");
        describe("打开效果库", "在左栏打开效果库");
        describe("应用效果", "把选中的效果加到当前轨道末尾；也可以直接双击效果");
        describe("进入合成器", "用所选工程打开合成器；也可以直接双击工程卡片");
        describe("创建并进入", "按上面的名称、画布尺寸、帧率和时长新建工程并立即进入");
        describe("打开素材库…", "浏览项目素材与游戏内贴图");
    }

    /** Registers a description (also used for labels built at runtime). */
    public static void describe(String label, String text)
    {
        DESCRIPTIONS.put(label, text);
    }

    /**
     * Detailed hover text for a label. The dictionary above covers every button the panel builds;
     * unknown labels still get a sentence that names the action, never an empty tooltip.
     */
    public static String describe(String label)
    {
        if (label == null || label.isEmpty())
        {
            return "";
        }

        String text = DESCRIPTIONS.get(label);

        if (text != null)
        {
            return text;
        }

        /* Prefix-insensitive lookup: "应用 电影青橙" should hit "应用". */
        int space = label.indexOf(' ');

        if (space > 0)
        {
            text = DESCRIPTIONS.get(label.substring(0, space));

            if (text != null)
            {
                return text + "（" + label.substring(space + 1).trim() + "）";
            }
        }

        return "点击执行：" + label;
    }

    /** A separator line used between toolbar groups. */
    public static void divider(mchorse.bbs_mod.ui.framework.UIContext context, float x, float y, float h)
    {
        context.batcher.box(x, y, x + 1, y + h, BBSSettings.dividerColor());
    }

    /**
     * bbsae follows the BBS theme accent. Do not hard-code a brand colour: the material and motion
     * should carry the "premium" feel, while hue stays consistent with the rest of BBS.
     */
    public static int accent()
    {
        return BBSSettings.primaryColor() | Colors.A100;
    }

    /** Muted text. */
    public static int mutedText()
    {
        return 0xFF8C96A5;
    }

    /** Width of one W/E/R button in the preview's compact gizmo mode switcher. */
    public static final int GIZMO_MODE_BUTTON_WIDTH = 58;
    /** Gap between the W/E/R buttons. */
    public static final int GIZMO_MODE_GAP = 4;

    /**
     * Bottom-right rectangle of the compact gizmo mode switcher.
     *
     * <p>It used to be drawn at {@code y = area.ey() - 100}, which in a short preview is the middle
     * of the picture (and in a tall one floats over the content). Anchoring the strip to the
     * bottom-right corner keeps it out of the shot and next to the other preview chrome.</p>
     *
     * @return {@code {x, y, totalWidth, height}}
     */
    public static int[] gizmoModeBarRect(Area area)
    {
        int available = Math.max(3 * 24 + 2 * GIZMO_MODE_GAP, area.w - 16 - 2 * GIZMO_MODE_GAP);
        int width = Math.max(24, Math.min(GIZMO_MODE_BUTTON_WIDTH, available / 3));
        int total = 3 * width + 2 * GIZMO_MODE_GAP;
        int x = Math.max(area.x + 8, area.ex() - total - 8);
        int y = Math.max(area.y + 8, area.ey() - 46);

        return new int[] {x, y, total, 18, width};
    }
}
