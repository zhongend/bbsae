package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor view state (selection, drawer, timeline zoom/scroll, inspector/left page) lives in the
 * project file so "reopen and continue where I was" works. It must survive a save/snapshot
 * round-trip without being confused with composition content.
 */
public class EditorViewStateRoundTripTest
{
    @Test
    public void editorViewStateSurvivesSnapshotRoundTrip()
    {
        CompProject project = CompStore.demo("view-state-test");

        assertNotNull(project);
        assertNotNull(project.settings);

        project.settings.keyframeDrawerOpen = true;
        project.settings.selectedLayerIds = "layer_a,layer_b";
        project.settings.selectedAudioId = "audio_9";
        project.settings.inspectorPage = 3;
        project.settings.leftPage = 2;
        project.settings.timelineZoom = 3.5D;
        project.settings.timelineShift = 1.25D;
        project.settings.timelineScrollY = 42;

        String json = CompStore.snapshot(project);
        CompProject restored = CompStore.restore(json);

        assertNotNull(restored);
        assertNotNull(restored.settings);

        assertTrue(restored.settings.keyframeDrawerOpen, "drawer open must round-trip");
        assertEquals("layer_a,layer_b", restored.settings.selectedLayerIds);
        assertEquals("audio_9", restored.settings.selectedAudioId);
        assertEquals(3, restored.settings.inspectorPage);
        assertEquals(2, restored.settings.leftPage);
        assertEquals(3.5D, restored.settings.timelineZoom, 1.0E-9D);
        assertEquals(1.25D, restored.settings.timelineShift, 1.0E-9D);
        assertEquals(42, restored.settings.timelineScrollY);
    }

    @Test
    public void editorViewStateIsSeparateFromCompositionContent()
    {
        CompProject project = CompStore.demo("view-state-test");
        CompDocument comp = project.getCompOrFirst(CompStore.DEFAULT_COMP);

        assertNotNull(comp);

        int layersBefore = comp.layers.size();
        int compsBefore = project.comps.size();

        project.settings.selectedLayerIds = "ghost_layer";
        project.settings.keyframeDrawerOpen = true;
        project.settings.timelineZoom = 9D;

        CompProject restored = CompStore.restore(CompStore.snapshot(project));

        /* View state must never add/remove layers or comps — it is ids/numbers only. */
        assertEquals(compsBefore, restored.comps.size());
        assertEquals(layersBefore, restored.getCompOrFirst(CompStore.DEFAULT_COMP).layers.size());
        assertEquals("ghost_layer", restored.settings.selectedLayerIds,
            "a stale id is kept verbatim; the UI drops ids that no longer resolve");
        assertFalse(restored.settings.selectedLayerIds.isEmpty());
    }
}
