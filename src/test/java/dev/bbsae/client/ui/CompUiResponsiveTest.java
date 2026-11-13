package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layout arithmetic for windows smaller than the old 1600 px design target. */
public class CompUiResponsiveTest
{
    @Test
    public void timelineNeverEatsThePreviewBody()
    {
        for (int height : new int[] {240, 320, 480, 720, 1080})
        {
            int timeline = CompUi.fitTimelineHeight(height, 420);
            int body = CompUi.bodyHeight(height, timeline);

            assertTrue(timeline >= 80, "timeline too small at " + height);
            assertTrue(body >= 56, "preview body too small at " + height);
            assertTrue(timeline + body <= Math.max(200, height), "layout overflows at " + height);
        }
    }

    @Test
    public void narrowColumnsPreserveThePreview()
    {
        int[] columns = CompUi.fitColumns(420, 190, 300, 12, 120);

        assertEquals(288, columns[0] + columns[1], "gap/preview budget");
        assertTrue(columns[0] > 0 && columns[1] > 0, "both columns must remain usable");
        assertTrue(420 - 12 - columns[0] - columns[1] >= 120, "preview floor must survive");
    }

    @Test
    public void sideColumnsAreCappedOnSmallPanels()
    {
        assertTrue(CompUi.maxSideWidth(800) <= 336);
        assertTrue(CompUi.maxSideWidth(400) <= 168);
    }

    @Test
    public void quickPanelYieldsToTheTimeline()
    {
        assertTrue(CompUi.quickWidth(420, 210) <= 210);
        assertTrue(CompUi.quickWidth(300, 210) <= 150);
        assertTrue(CompUi.quickWidth(300, 210) >= 0);
    }

    /**
     * Regression: the timeline-height splitter used to span the whole body, so its grip sat on the
     * bottom edge of the left and right columns. Dragging a list row there resized the timeline
     * instead of the row ("拖拽数据时面板大小也跟着拖拽"). The grip must stay inside the preview.
     */
    @Test
    public void timelineSplitterStaysInsideThePreview()
    {
        int[] widths = {360, 420, 640, 800, 960, 1280, 1600, 1920};

        for (int width : widths)
        {
            int[] columns = CompUi.fitColumns(width, 190, 300, 12, 120);
            int[] span = CompUi.timelineSplitterSpan(width, columns[0], columns[1], 6, 6);

            assertTrue(span[0] >= columns[0], "splitter starts inside the left column at " + width);
            assertTrue(span[0] + span[1] <= width - columns[1],
                "splitter reaches into the right column at " + width);
            assertTrue(span[1] > 0, "splitter must stay draggable at " + width);
        }

        int[] hidden = CompUi.timelineSplitterSpan(800, 0, 0, 0, 0);
        assertEquals(0, hidden[0]);
        assertEquals(800, hidden[1], "with no side columns the splitter spans the body");

        int[] tiny = CompUi.timelineSplitterSpan(80, 40, 40, 0, 0);
        assertTrue(tiny[0] >= 0 && tiny[1] >= 0 && tiny[0] + tiny[1] <= 80, "tiny panel stays in bounds");
    }

    /**
     * Regression: the 48 px settings button used to be placed at {@code 1F - 66} while the 18 px
     * focus button occupied {@code 1F - 40 .. 1F - 22}, so the two overlapped by 22 px at every
     * window size. The layout is now arithmetic, and arithmetic can be asserted.
     */
    @Test
    public void headerButtonsNeverOverlap()
    {
        int[] widths = {320, 360, 420, 480, 520, 620, 640, 720, 780, 800, 860, 960, 980, 1080, 1280, 1600, 1920};
        int[] controls = {14, 16, 18, 20, 22};

        for (int control : controls)
        {
            for (int width : widths)
            {
                CompUi.HeaderLayout layout = CompUi.headerLayout(width, control);
                int[][] right = {
                    {layout.focusX, control, 1},
                    {layout.settingsX, 48, 1},
                    {layout.layoutX, 48, 1},
                    {layout.renderX, 54, layout.showRender ? 1 : 0},
                    {layout.exportX, 54, layout.showExport ? 1 : 0},
                    {layout.saveX, 48, layout.showSave ? 1 : 0},
                    {layout.projectsX, CompUi.PROJECTS_W, layout.showProjects ? 1 : 0}
                };

                int previousEnd = -1;

                /* The right cluster grows leftwards: walk it left-to-right so "previous end" is
                 * always the right edge of the control immediately to the left. */
                for (int index = right.length - 1; index >= 0; index--)
                {
                    int[] button = right[index];

                    if (button[2] == 0)
                    {
                        continue;
                    }

                    assertTrue(button[0] >= 0, "right button off-screen at " + width + "px: " + button[0]);
                    assertTrue(button[0] + button[1] <= width, "right button past the edge at " + width + "px");

                    if (previousEnd >= 0)
                    {
                        assertTrue(button[0] >= previousEnd,
                            "right buttons overlap at " + width + "px, control " + control
                                + " (start " + button[0] + " < previous end " + previousEnd + ")");
                    }

                    previousEnd = button[0] + button[1];
                }

                if (layout.showTimecode || layout.showTitle || layout.showReplay || layout.showFrames || layout.showPlay)
                {
                    assertTrue(layout.leftEnd + 8 <= layout.rightStart,
                        "left/right header clusters collide at " + width + "px, control " + control
                            + " (left ends " + layout.leftEnd + ", right starts " + layout.rightStart + ")");
                }
            }
        }
    }

    @Test
    public void previewBarFilterIsCollapsibleAndNeverOverlaps()
    {
        int[] widths = {64, 100, 120, 140, 160, 200, 240, 280, 320, 420, 640, 900};

        for (int width : widths)
        {
            for (boolean expanded : new boolean[] {false, true})
            {
                CompUi.PreviewBarLayout layout = CompUi.previewBarLayout(width, 18, false, expanded);
                int[] xs = {layout.effectsX, layout.masksX, layout.bbsX, layout.filterX, layout.collapseX};
                int[] ws = {58, 58, 58, 82, 20};
                boolean[] shown = {layout.showEffects, layout.showMasks, layout.showBbs, layout.showFilter, layout.showCollapse};
                int previousEnd = -1;

                for (int i = 0; i < xs.length; i++)
                {
                    if (!shown[i])
                    {
                        continue;
                    }

                    assertTrue(xs[i] >= 0, "preview control off-screen at " + width + "px");
                    assertTrue(xs[i] + ws[i] <= layout.width + 1, "preview control past the bar at " + width + "px");

                    if (previousEnd >= 0)
                    {
                        assertTrue(xs[i] >= previousEnd,
                            "preview controls overlap at " + width + "px (x=" + xs[i] + ", previous end=" + previousEnd + ")");
                    }

                    previousEnd = xs[i] + ws[i];
                }

                if (expanded)
                {
                    assertEquals(layout.showFilter && width >= 112, layout.filterMenuVisible,
                        "filter menu visibility at " + width + "px");

                    if (layout.filterMenuVisible)
                    {
                        assertTrue(layout.filterMenuX >= 0);
                        assertTrue(layout.filterMenuX + layout.filterMenuW <= layout.width,
                            "filter menu past the bar at " + width + "px");
                    }
                }
                else
                {
                    assertTrue(!layout.filterMenuVisible, "folded filter must not show its menu");
                }
            }
        }

        /* The chip is the control being made collapsible: it must survive far longer than the
         * three wide toggles, otherwise narrow previews cannot change the filter at all. */
        assertTrue(CompUi.previewBarLayout(140, 18, false, false).showFilter, "filter chip must survive at 140px");
        assertTrue(!CompUi.previewBarLayout(64, 18, false, true).filterMenuVisible, "no menu in a 64px preview");
        assertTrue(CompUi.previewBarLayout(300, 18, true, true).barCollapsed, "collapsed bar keeps only the arrow");
        assertTrue(!CompUi.previewBarLayout(300, 18, true, true).showFilter, "collapsed bar hides the filter");
    }

    /**
     * Regression: the preview's camera/pen/replay icon rail was pinned to {@code 1F - 28 - 22n}
     * and the floating preview bar to the same 20 px row. In a narrower preview the icons were
     * painted (and clicked) on top of the bar's filter chip, so "线性：自动" opened the camera
     * settings. The rail must degrade from the left and never intersect the bar.
     */
    @Test
    public void previewRailNeverCoversThePreviewBar()
    {
        int[] widths = {40, 64, 80, 100, 120, 160, 200, 240, 280, 304, 320, 400, 458, 480, 640, 900};

        for (int width : widths)
        {
            CompUi.PreviewRailLayout rail = CompUi.previewRailLayout(width);
            CompUi.PreviewBarLayout bar = CompUi.previewBarLayout(rail.barWidth, 18, false, true);
            int barRight = 6 + bar.width;
            int leftmost = Integer.MAX_VALUE;

            /* Icons are ordered right to left: each one must end before the previous one starts. */
            int previousStart = Integer.MAX_VALUE;

            for (int index = 0; index < rail.shown.length; index++)
            {
                if (!rail.shown[index])
                {
                    assertEquals(-1, rail.xs[index], "hidden rail icon must have no position at " + width + "px");

                    continue;
                }

                assertTrue(rail.xs[index] >= 0, "rail icon off-screen at " + width + "px");
                assertTrue(rail.xs[index] + 20 <= width, "rail icon past the preview at " + width + "px");
                assertTrue(rail.xs[index] + 20 <= previousStart, "rail icons overlap at " + width + "px");

                previousStart = rail.xs[index];
                leftmost = Math.min(leftmost, rail.xs[index]);
            }

            if (leftmost != Integer.MAX_VALUE)
            {
                assertTrue(barRight + 2 <= leftmost,
                    "preview bar overlaps the rail at " + width + "px (bar ends " + barRight
                        + ", rail starts " + leftmost + ")");
            }
        }

        /* Narrow previews hide the rail from the left, but never the camera view first. */
        assertTrue(CompUi.previewRailLayout(640).shown[0], "camera view must stay visible when it fits");
        assertTrue(!CompUi.previewRailLayout(120).shown[0], "nothing fits in a 120px preview");
    }

    /**
     * Regression: a dock panel dragged narrow used to draw its overflow tabs on top of the
     * neighbour. The strip now hides trailing tabs and shows a "»" cycle control; the arithmetic
     * is pure so the "never zero tabs / never off-screen control" invariant is testable.
     */
    @Test
    public void tabStripDegradesToAnOverflowControl()
    {
        int[] sevenTabs = {48, 44, 44, 44, 44, 44, 44};

        int[] wide = CompUi.tabStripLayout(sevenTabs, 400);

        assertEquals(7, wide[0], "all tabs fit in a wide bar");
        assertEquals(0, wide[2], "no overflow control in a wide bar");

        for (int width : new int[] {80, 120, 180, 240, 300, 340})
        {
            int[] layout = CompUi.tabStripLayout(sevenTabs, width);

            assertTrue(layout[0] >= 1, "at least one tab must stay visible at " + width + "px");
            assertTrue(layout[0] <= 7, "cannot show more tabs than exist at " + width + "px");

            if (layout[2] == 1)
            {
                assertTrue(layout[1] >= 0, "overflow control off-screen at " + width + "px");
                assertTrue(layout[1] + 20 <= width, "overflow control past the bar at " + width + "px");
                assertTrue(layout[0] < 7, "overflow flag with all tabs visible at " + width + "px");
            }
        }

        int[] empty = CompUi.tabStripLayout(new int[0], 200);

        assertEquals(0, empty[0]);
        assertEquals(0, empty[2]);
    }
}
