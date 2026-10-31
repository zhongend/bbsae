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
}
