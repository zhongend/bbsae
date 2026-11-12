package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quick panel's resize grip must own its press instead of polling the cursor. */
class UILayerQuickPanelTest
{
    @Test
    void gripOnlyOwnsPressesThatLandOnIt()
    {
        assertTrue(UILayerQuickPanel.isGripPress(true, true, 0));
        assertTrue(UILayerQuickPanel.isGripPress(true, true, 4));

        assertFalse(UILayerQuickPanel.isGripPress(true, true, 5), "outside the 4 px grip");
        assertFalse(UILayerQuickPanel.isGripPress(true, true, -1), "left of the panel");
        assertFalse(UILayerQuickPanel.isGripPress(false, true, 2), "hidden panel must not own the press");
        assertFalse(UILayerQuickPanel.isGripPress(true, false, 2), "outside the panel rect");
    }
}
