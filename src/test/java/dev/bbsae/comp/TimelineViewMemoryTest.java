package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-composition time-axis memory: codec plus project persistence.
 *
 * <p>The point of the map is that switching pages/compositions must not re-fit the timeline. A
 * malformed or zoom-0 entry has to be ignored (fit instead of a degenerate axis), and the fit flag
 * has to survive a save/load so a fitted view keeps following the composition.</p>
 */
public class TimelineViewMemoryTest
{
    @Test
    public void anExplicitViewRoundTrips()
    {
        CompProject.CompSettings settings = new CompProject.CompSettings();

        settings.putTimelineView("main", false, 3.5D, 42D, 7, 1.25D, 60D);

        CompProject.CompSettings.TimelineView view =
            CompProject.CompSettings.timelineView(settings.timelineViews.get("main"));

        assertNotNull(view);
        assertFalse(view.fit());
        assertEquals(3.5D, view.shift(), 0.0001D);
        assertEquals(42D, view.zoom(), 0.0001D);
        assertEquals(7, view.scrollY());
        assertEquals(1.25D, view.keyShift(), 0.0001D);
        assertEquals(60D, view.keyZoom(), 0.0001D);
    }

    @Test
    public void theLaneWidthTravelsWithTheView()
    {
        CompProject.CompSettings settings = new CompProject.CompSettings();

        settings.putTimelineView("main", false, 3.5D, 42D, 7, 1.25D, 60D, 840);

        CompProject.CompSettings.TimelineView view =
            CompProject.CompSettings.timelineView(settings.timelineViews.get("main"));

        assertNotNull(view);
        assertEquals(840, view.laneWidth(),
            "restoring in a different window must be able to rescale pixels/second");
    }

    @Test
    public void oldEntriesWithoutAWidthAreStillAccepted()
    {
        CompProject.CompSettings settings = new CompProject.CompSettings();

        settings.putTimelineView("main", false, 3.5D, 42D, 7, 1.25D, 60D);

        CompProject.CompSettings.TimelineView view =
            CompProject.CompSettings.timelineView(settings.timelineViews.get("main"));

        assertNotNull(view);
        assertEquals(0, view.laneWidth(), "legacy entries keep their raw zoom");
    }

    @Test
    public void theFitFlagIsPreserved()
    {
        CompProject.CompSettings settings = new CompProject.CompSettings();

        settings.putTimelineView("precomp_1", true, 0D, 12D, 0, 0D, 0D);

        CompProject.CompSettings.TimelineView view =
            CompProject.CompSettings.timelineView(settings.timelineViews.get("precomp_1"));

        assertNotNull(view);
        assertTrue(view.fit(), "fit is a mode, not a pair of numbers");
    }

    @Test
    public void theLegacyFiveFieldFormatStillParses()
    {
        /* shift,zoom,scroll,keyShift,keyZoom — written before the fit flag existed. */
        CompProject.CompSettings.TimelineView view =
            CompProject.CompSettings.timelineView("2.0,30.0,4,1.0,20.0");

        assertNotNull(view);
        assertFalse(view.fit());
        assertEquals(2D, view.shift(), 0.0001D);
        assertEquals(30D, view.zoom(), 0.0001D);
        assertEquals(4, view.scrollY());
        assertEquals(20D, view.keyZoom(), 0.0001D);
    }

    @Test
    public void malformedEntriesAreRejected()
    {
        assertNull(CompProject.CompSettings.timelineView(null));
        assertNull(CompProject.CompSettings.timelineView(""));
        assertNull(CompProject.CompSettings.timelineView("nope"));
        assertNull(CompProject.CompSettings.timelineView("0,0,0,0,0"), "zoom 0 is degenerate");
        assertNull(CompProject.CompSettings.timelineView("0,NaN,0,0,0"));
    }

    @Test
    public void viewsSurviveProjectSaveAndReload()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 30F, 12F);

        project.settings.putTimelineView(comp.id, false, 1.5D, 24D, 3, 0.5D, 8D);
        project.settings.putTimelineView("other", true, 0D, 12D, 0, 0D, 0D);

        CompProject restored = CompStore.restore(CompStore.snapshot(project));

        assertNotNull(restored);
        assertNotNull(restored.settings);
        assertEquals(2, restored.settings.timelineViews.size());

        CompProject.CompSettings.TimelineView view =
            CompProject.CompSettings.timelineView(restored.settings.timelineViews.get(comp.id));

        assertNotNull(view);
        assertEquals(24D, view.zoom(), 0.0001D);
        assertEquals(1.5D, view.shift(), 0.0001D);

        CompProject.CompSettings.TimelineView fitted =
            CompProject.CompSettings.timelineView(restored.settings.timelineViews.get("other"));

        assertNotNull(fitted);
        assertTrue(fitted.fit());
    }
}
