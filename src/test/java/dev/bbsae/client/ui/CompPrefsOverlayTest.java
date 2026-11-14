package dev.bbsae.client.ui;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The overlay keys must exist with sane defaults and survive a preferences round-trip. */
public class CompPrefsOverlayTest
{
    @Test
    public void overlayDefaultsAreVisibleAndTunable()
    {
        CompPrefs.Data data = new CompPrefs.Data();

        assertTrue(data.gizmos);
        assertTrue(data.selectionBoxes);
        assertTrue(data.cameraGizmos);
        assertTrue(data.guides);
        assertTrue(data.previewHints);
        assertTrue(data.suppressOnionSkin);
        assertFalse(data.perPixelDepth);
        assertEquals(1F, data.gizmoOpacity, 0.0001F);
        assertEquals(1F, data.gizmoThickness, 0.0001F);
        assertEquals(0, data.replayCompPreview);
        assertNull(data.dragThresholdPx,
            "null means 'not stored yet'; the helper resolves it to the 4 px default");
        assertNull(data.previewDragMove,
            "null means 'not stored yet'; preview body drag defaults to on");
    }

    @Test
    public void dragPreferencesRoundTripWithoutChangingOldDefaults()
    {
        CompPrefs.Data data = new CompPrefs.Data();

        data.dragThresholdPx = 9F;
        data.previewDragMove = false;

        CompPrefs.Data loaded = new Gson().fromJson(new Gson().toJson(data), CompPrefs.Data.class);

        assertEquals(9F, loaded.dragThresholdPx, 0.0001F);
        assertEquals(false, loaded.previewDragMove);
    }

    @Test
    public void overlayAndTrackPreferencesRoundTrip()
    {
        CompPrefs.Data data = new CompPrefs.Data();

        data.gizmos = false;
        data.gizmoOpacity = 0.4F;
        data.gizmoThickness = 2.5F;
        data.previewHints = false;
        data.timelineRowHeight = 34;
        data.keyframeRowHeight = 28;

        CompPrefs.Data loaded = new Gson().fromJson(new Gson().toJson(data), CompPrefs.Data.class);

        assertEquals(false, loaded.gizmos);
        assertEquals(0.4F, loaded.gizmoOpacity, 0.0001F);
        assertEquals(2.5F, loaded.gizmoThickness, 0.0001F);
        assertEquals(false, loaded.previewHints);
        assertEquals(34, loaded.timelineRowHeight);
        assertEquals(28, loaded.keyframeRowHeight);
    }
}
