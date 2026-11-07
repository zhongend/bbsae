package dev.bbsae.client.ui;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(1F, data.gizmoOpacity, 0.0001F);
        assertEquals(1F, data.gizmoThickness, 0.0001F);
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
