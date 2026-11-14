package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab strip must never hide the active section without telling the user that more sections
 * exist. {@link CompUi#tabStripWindow(int[], int, int, int)} is the pure part of that rule.
 */
public class CompUiTabWindowTest
{
    private static int[] seven()
    {
        int[] widths = new int[7];

        java.util.Arrays.fill(widths, 34);

        return widths;
    }

    @Test
    public void aWideBarShowsEveryTab()
    {
        int[] window = CompUi.tabStripWindow(seven(), 400, 3, 20);

        assertEquals(0, window[0]);
        assertEquals(7, window[1]);
        assertEquals(0, window[2]);
        assertEquals(0, window[3]);
    }

    @Test
    public void theFirstTabKeepsTheWindowAtTheStartAndSignalsTheRightEdge()
    {
        int[] window = CompUi.tabStripWindow(seven(), 180, 0, 20);

        assertEquals(0, window[0]);
        assertTrue(window[1] < 7, "not all seven fit in 180 px");
        assertEquals(0, window[2]);
        assertEquals(1, window[3], "hidden tabs to the right need an overflow cue");
        assertTrue(window[0] <= 0 && window[1] > 0);
    }

    @Test
    public void theLastTabScrollsTheWindowAndSignalsTheLeftEdge()
    {
        int[] window = CompUi.tabStripWindow(seven(), 180, 6, 20);

        assertTrue(window[0] > 0, "the window must scroll to reveal the last active tab");
        assertEquals(7, window[1], "the last tab must be visible");
        assertEquals(1, window[2], "hidden tabs to the left need an overflow cue");
        assertEquals(0, window[3]);
    }

    @Test
    public void aMiddleTabCanNeedBothEdges()
    {
        int[] window = CompUi.tabStripWindow(seven(), 120, 3, 20);

        assertTrue(window[0] <= 3 && window[1] > 3, "the active tab must be inside the window");
        assertEquals(1, window[2]);
        assertEquals(1, window[3]);
    }

    @Test
    public void theWindowAlwaysContainsTheActiveTab()
    {
        int[] widths = seven();

        for (int width = 40; width <= 400; width += 10)
        {
            for (int active = 0; active < widths.length; active++)
            {
                int[] window = CompUi.tabStripWindow(widths, width, active, 20);

                assertTrue(window[0] <= active && window[1] > active,
                    "active " + active + " must be visible at width " + width
                        + " (window " + window[0] + ".." + window[1] + ")");
                assertFalse(window[1] <= window[0]);
            }
        }
    }

    @Test
    public void anEmptyBarHasNoWindow()
    {
        int[] window = CompUi.tabStripWindow(new int[0], 200, 0, 20);

        assertEquals(0, window[0]);
        assertEquals(0, window[1]);
    }
}
