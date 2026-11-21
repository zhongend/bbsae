package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyframe-drawer splitter needs real travel, not just a visible 5 px strip.
 *
 * <p>The regression was geometric: on a normal docked timeline the ruler + three-row track reserve
 * left the drawer a max of ~101 px against a 96 px minimum, so dragging it changed almost nothing.
 * These tests pin the pure max-height/clamp functions used by the layout and the handle.</p>
 */
public class KeyframeDrawerResizeTest
{
    private static final int CONTENT_TOP = 32; // header 26 + dock inset 6
    private static final int ROW = 26;
    private static final int TIMELINE = 236; // the common docked timeline height

    @Test
    public void theOldThreeRowReserveLeftAPracticallyFrozenSplitter()
    {
        int oldMax = TIMELINE - CONTENT_TOP - (20 + ROW * 3) - 5;

        assertEquals(101, oldMax, "the old reserve is the 5 px-travel regression");
        assertTrue(oldMax - UICompositorPanel.KEYFRAME_DRAWER_MIN <= 5);
    }

    @Test
    public void theNewReserveLeavesAUsableSplitterRange()
    {
        int max = UICompositorPanel.keyframeDrawerMaxHeight(TIMELINE, CONTENT_TOP, ROW);

        assertTrue(max - UICompositorPanel.KEYFRAME_DRAWER_MIN >= 40,
            "drawer must have meaningful travel; max=" + max);
    }

    @Test
    public void draggingUpGrowsAndDraggingDownShrinks()
    {
        int max = UICompositorPanel.keyframeDrawerMaxHeight(TIMELINE, CONTENT_TOP, ROW);

        assertEquals(96, UICompositorPanel.resizeKeyframeDrawerHeight(96, 40, max),
            "dragging down past the minimum clamps to the minimum");
        assertEquals(136, UICompositorPanel.resizeKeyframeDrawerHeight(96, -40, max),
            "dragging up grows the drawer");
        assertEquals(max, UICompositorPanel.resizeKeyframeDrawerHeight(96, -10000, max),
            "dragging up past the ceiling clamps to the available room");
    }

    @Test
    public void aShortTimelineStillExposesTheMinimum()
    {
        int max = UICompositorPanel.keyframeDrawerMaxHeight(150, CONTENT_TOP, ROW);

        assertEquals(UICompositorPanel.KEYFRAME_DRAWER_MIN,
            UICompositorPanel.resizeKeyframeDrawerHeight(UICompositorPanel.KEYFRAME_DRAWER_MIN, -20, max),
            "a too-short timeline must not make the drawer smaller than its minimum");
    }
}
