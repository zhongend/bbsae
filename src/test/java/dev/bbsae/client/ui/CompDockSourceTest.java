package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project-side storage of BBS's dock layout.
 *
 * <p>The dock itself is BBS's ({@code UIDockLayout}); what can go wrong here is the mapping: a
 * default tree that names a panel the compositor does not register, a layout string that does not
 * survive a round trip, a corrupt string that prevents the editor from opening, or a write path that
 * dirties the project on every splitter frame. Those are pure data cases and are pinned here.</p>
 */
class CompDockSourceTest
{
    private static CompProject.CompSettings settings()
    {
        CompProject project = new CompProject();

        return project.settings;
    }

    @Test
    void defaultTreeRegistersExactlyTheCompositorPanels()
    {
        Set<String> ids = new HashSet<>();

        EditorLayoutNode.collectPanelIds(CompDockSource.defaultLayout(), ids);

        assertEquals(Set.of(CompDockSource.PANEL_EFFECTS, CompDockSource.PANEL_PREVIEW,
            CompDockSource.PANEL_INSPECTOR, CompDockSource.PANEL_TIMELINE), ids);
    }

    @Test
    void focusAndPresetTreesOnlyReferenceRegisteredPanels()
    {
        Set<String> registered = Set.of(CompDockSource.PANEL_EFFECTS, CompDockSource.PANEL_PREVIEW,
            CompDockSource.PANEL_INSPECTOR, CompDockSource.PANEL_TIMELINE);

        Set<String> focus = new HashSet<>();

        EditorLayoutNode.collectPanelIds(CompDockSource.focusLayout(), focus);
        assertEquals(Set.of(CompDockSource.PANEL_PREVIEW, CompDockSource.PANEL_TIMELINE), focus);

        for (int preset = 0; preset <= 3; preset++)
        {
            Set<String> ids = new HashSet<>();

            EditorLayoutNode.collectPanelIds(CompDockSource.presetLayout(preset), ids);
            assertTrue(registered.containsAll(ids),
                "preset " + preset + " references an unregistered panel: " + ids);

            if (preset != 1)
            {
                assertEquals(registered, ids, "preset " + preset + " must keep every panel reachable");
            }
        }
    }

    @Test
    void layoutRoundTripsThroughTheProjectSettings()
    {
        CompProject.CompSettings settings = settings();
        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);
        EditorLayoutNode custom = new EditorLayoutNode.SplitterNode(true, 0.5F,
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_PREVIEW),
            new EditorLayoutNode.StackNode(
                java.util.List.of(CompDockSource.PANEL_INSPECTOR, CompDockSource.PANEL_EFFECTS),
                CompDockSource.PANEL_INSPECTOR));

        source.setRoot(custom);

        assertTrue(settings.dockLayout.isBlank(), "a dock drag must not write the project every frame");
        assertTrue(source.isDirty());

        source.flush();

        assertFalse(source.isDirty());
        assertFalse(settings.dockLayout.isBlank(), "flush must persist the tree");

        CompDockSource reloaded = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);
        EditorLayoutNode read = reloaded.getRoot();

        assertNotNull(read);
        assertEquals(CompDockSource.encode(custom), CompDockSource.encode(read),
            "a saved layout must come back byte for byte");

        /* The stack survived: both panels are still in one stack with the same active tab. */
        Set<String> ids = new LinkedHashSet<>();

        EditorLayoutNode.collectPanelIds(read, ids);
        assertEquals(3, ids.size());
    }

    @Test
    void splitterDragFramesAreBatched()
    {
        CompProject.CompSettings settings = settings();
        int[] changes = {0};
        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout,
            () -> changes[0]++);

        for (int i = 0; i < 30; i++)
        {
            source.setRoot(CompDockSource.defaultLayout());
        }

        assertEquals(0, changes[0], "simulated drag frames must not notify the host");
        assertTrue(settings.dockLayout.isBlank());

        source.flush();

        assertEquals(1, changes[0], "one flush, one notification");
        assertFalse(settings.dockLayout.isBlank());

        source.flush();

        assertEquals(1, changes[0], "a clean source must not notify again");
    }

    @Test
    void oldProjectWithoutALayoutUsesTheDefault()
    {
        CompProject.CompSettings settings = settings();

        settings.dockLayout = null;

        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        assertEquals(CompDockSource.encode(CompDockSource.defaultLayout()),
            CompDockSource.encode(source.getRoot()));
    }

    @Test
    void corruptLayoutFallsBackToTheDefaultInsteadOfBreakingTheEditor()
    {
        CompProject.CompSettings settings = settings();

        settings.dockLayout = "{not a layout";
        settings.dockHiddenPanels = "preview,,";

        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        assertEquals(CompDockSource.encode(CompDockSource.defaultLayout()),
            CompDockSource.encode(source.getRoot()));
        assertEquals(1, source.getHiddenPanels().size(), "empty parts are ignored");
        assertTrue(source.getHiddenPanels().contains(CompDockSource.PANEL_PREVIEW));
    }

    @Test
    void hiddenPanelsRoundTripAsCommaSeparatedIds()
    {
        CompProject.CompSettings settings = settings();
        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        source.setHiddenPanels(new LinkedHashSet<>(
            java.util.List.of(CompDockSource.PANEL_EFFECTS, CompDockSource.PANEL_INSPECTOR)));

        assertTrue(settings.dockHiddenPanels.isBlank(), "hidden panels are batched too");
        source.flush();

        assertEquals(CompDockSource.PANEL_EFFECTS + "," + CompDockSource.PANEL_INSPECTOR,
            settings.dockHiddenPanels);

        CompDockSource reloaded = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        assertEquals(Set.of(CompDockSource.PANEL_EFFECTS, CompDockSource.PANEL_INSPECTOR),
            reloaded.getHiddenPanels());
    }

    @Test
    void reloadDropsTheCacheAfterAProjectSwitch()
    {
        CompProject.CompSettings settings = settings();
        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        source.setRoot(new EditorLayoutNode.StackNode(
            java.util.List.of(CompDockSource.PANEL_PREVIEW), CompDockSource.PANEL_PREVIEW));
        source.flush();

        settings.dockLayout = CompDockSource.encode(CompDockSource.defaultLayout());

        source.reload();

        assertEquals(CompDockSource.encode(CompDockSource.defaultLayout()),
            CompDockSource.encode(source.getRoot()), "reload must read the new project's layout");
        assertFalse(source.isDirty());
    }
}
