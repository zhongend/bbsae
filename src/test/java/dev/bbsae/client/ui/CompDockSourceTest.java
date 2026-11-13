package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void effectsRightLayoutKeepsEveryPanelAndPutsPreviewLeftOfIt()
    {
        Set<String> ids = new HashSet<>();

        EditorLayoutNode.collectPanelIds(CompDockSource.effectsRightLayout(), ids);

        assertEquals(Set.of(CompDockSource.PANEL_EFFECTS, CompDockSource.PANEL_PREVIEW,
            CompDockSource.PANEL_INSPECTOR, CompDockSource.PANEL_TIMELINE), ids);

        /* The left child of the outer row splitter is the preview; the right column stacks the
         * effect library over the inspector. This is the "effect list on the right of the preview"
         * layout the layout menu applies. */
        EditorLayoutNode root = CompDockSource.effectsRightLayout();

        assertTrue(root instanceof EditorLayoutNode.SplitterNode);

        EditorLayoutNode.SplitterNode outer = (EditorLayoutNode.SplitterNode) root;

        assertTrue(outer.getFirst() instanceof EditorLayoutNode.SplitterNode);

        EditorLayoutNode.SplitterNode row = (EditorLayoutNode.SplitterNode) outer.getFirst();

        assertTrue(row.getFirst() instanceof EditorLayoutNode.PanelNode);
        assertEquals(CompDockSource.PANEL_PREVIEW, ((EditorLayoutNode.PanelNode) row.getFirst()).getPanelId());
    }

    @Test
    void defaultLayoutPutsTheEffectLibraryLeftOfThePreview()
    {        EditorLayoutNode root = CompDockSource.defaultLayout();

        assertTrue(root instanceof EditorLayoutNode.SplitterNode);

        EditorLayoutNode.SplitterNode outer = (EditorLayoutNode.SplitterNode) root;

        /* The editor row is above the timeline; its first child is the effects column. */
        assertTrue(outer.isHorizontal());
        assertTrue(outer.getFirst() instanceof EditorLayoutNode.SplitterNode);

        EditorLayoutNode.SplitterNode row = (EditorLayoutNode.SplitterNode) outer.getFirst();

        assertFalse(row.isHorizontal());
        assertTrue(row.getFirst() instanceof EditorLayoutNode.PanelNode);
        assertEquals(CompDockSource.PANEL_EFFECTS,
            ((EditorLayoutNode.PanelNode) row.getFirst()).getPanelId(), "source browser belongs on the left");

        assertTrue(row.getSecond() instanceof EditorLayoutNode.SplitterNode);

        EditorLayoutNode.SplitterNode previewAndInspector = (EditorLayoutNode.SplitterNode) row.getSecond();

        assertEquals(CompDockSource.PANEL_PREVIEW,
            ((EditorLayoutNode.PanelNode) previewAndInspector.getFirst()).getPanelId());
        assertEquals(CompDockSource.PANEL_INSPECTOR,
            ((EditorLayoutNode.PanelNode) previewAndInspector.getSecond()).getPanelId());
    }

    /**
     * Reproduces the splitter-drag failure: with an empty stored layout, getRoot() used to return a
     * fresh default tree on every call. BBS keys its ratio map by SplitterNode identity, so the
     * handle node and the root node never matched and dragging did nothing.
     */
    @Test
    void emptyLayoutCachesOneDefaultTreeSoSplitterIdentityMatches()
    {
        CompProject.CompSettings settings = settings();
        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        EditorLayoutNode first = source.getRoot();
        EditorLayoutNode second = source.getRoot();

        assertSame(first, second, "an empty layout must hand out the same default tree");
        assertFalse(source.isDirty(), "caching the default must not mark the project dirty");
        assertTrue(settings.dockLayout.isBlank(), "caching the default must not write it into the project");

        /* This is exactly what UIDockLayout.applySplitterDrag builds from its handle infos. */
        Map<EditorLayoutNode.SplitterNode, Float> ratios = new HashMap<>();

        ratios.put((EditorLayoutNode.SplitterNode) second, 0.61F);

        EditorLayoutNode next = EditorLayoutNode.copyWithSplitterRatios(second, ratios);

        assertNotSame(second, next, "a ratio for the cached node must actually rebuild the tree");
        assertEquals(0.61F, ((EditorLayoutNode.SplitterNode) next).getRatio(), 0.0001F);
    }

    @Test
    void splitterDragOnAnEmptyLayoutIsPersistedAndReopened()
    {
        CompProject.CompSettings settings = settings();
        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);
        Map<EditorLayoutNode.SplitterNode, Float> ratios = new HashMap<>();

        ratios.put((EditorLayoutNode.SplitterNode) source.getRoot(), 0.58F);

        source.setRoot(EditorLayoutNode.copyWithSplitterRatios(source.getRoot(), ratios));
        source.flush();

        assertFalse(settings.dockLayout.isBlank(), "the dragged ratio must reach the project");

        CompDockSource reopened = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);
        EditorLayoutNode root = reopened.getRoot();

        assertSame(root, reopened.getRoot());
        assertEquals(0.58F, ((EditorLayoutNode.SplitterNode) root).getRatio(), 0.0001F);
    }

    @Test
    void theDefaultSupplierIsOnlyCalledOnceForAnEmptyLayout()
    {
        CompProject.CompSettings settings = settings();
        AtomicInteger calls = new AtomicInteger();
        CompDockSource source = new CompDockSource(() -> settings, () ->
        {
            calls.incrementAndGet();

            return CompDockSource.defaultLayout();
        }, null);

        EditorLayoutNode first = source.getRoot();

        for (int i = 0; i < 20; i++)
        {
            assertSame(first, source.getRoot(), "repeated dock layouts must reuse the cached root");
        }

        assertEquals(1, calls.get(), "the default tree must not be rebuilt on every query");
    }

    /* The keyframe drawer lives inside the timeline panel and needs a hard minimum height. On a
     * 960×540 UI the default 72/28 split leaves the timeline 125 px — smaller than the drawer — so
     * opening it used to force-close it. The next tests pin the layout math that makes room. */

    @Test
    void drawerGrowthRaisesTheTimelineBranchOfTheDefaultLayout()
    {
        EditorLayoutNode root = CompDockSource.defaultLayout();
        int body = 478;
        int branch = Math.round(body * 0.28F); /* root ratio 0.72, timeline is the second child */
        int gutter = 9;
        int current = branch - gutter;

        EditorLayoutNode grown = CompDockSource.growPanelHeight(root,
            CompDockSource.PANEL_TIMELINE, current, 247, body);

        assertNotNull(grown, "the default tree has a horizontal splitter above the timeline");
        assertNotSame(root, grown);

        float ratio = ((EditorLayoutNode.SplitterNode) grown).getRatio();

        /* The timeline gets the requested *content* height, not that minus the gutter. */
        assertEquals(247F, (1F - ratio) * body - gutter, 2F);
        assertTrue(ratio < 0.72F, "the preview branch has to shrink to make room");
        assertTrue(ratio > 0.30F, "the preview must keep a usable size");
    }

    @Test
    void drawerGrowthAlsoWorksWhenTheTimelineIsTheFirstChild()
    {
        EditorLayoutNode root = new EditorLayoutNode.SplitterNode(true, 0.30F,
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_TIMELINE),
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_PREVIEW));

        EditorLayoutNode grown = CompDockSource.growPanelHeight(root,
            CompDockSource.PANEL_TIMELINE, 150, 250, 500);

        assertNotNull(grown);
        assertEquals(0.50F, ((EditorLayoutNode.SplitterNode) grown).getRatio(), 0.02F);
    }

    @Test
    void drawerGrowthClimbsPastANestedSideBySideSplit()
    {
        EditorLayoutNode root = new EditorLayoutNode.SplitterNode(true, 0.50F,
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_PREVIEW),
            new EditorLayoutNode.SplitterNode(false, 0.50F,
                new EditorLayoutNode.PanelNode(CompDockSource.PANEL_EFFECTS),
                new EditorLayoutNode.PanelNode(CompDockSource.PANEL_TIMELINE)));

        EditorLayoutNode grown = CompDockSource.growPanelHeight(root,
            CompDockSource.PANEL_TIMELINE, 250, 300, 500);

        assertNotNull(grown, "the outer horizontal splitter controls the nested row's height");
        assertEquals(0.40F, ((EditorLayoutNode.SplitterNode) grown).getRatio(), 0.02F);
    }

    @Test
    void drawerGrowthRefusesWhenThereIsNoVerticalSplitterOrAlreadyEnoughRoom()
    {
        EditorLayoutNode sideBySide = new EditorLayoutNode.SplitterNode(false, 0.50F,
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_TIMELINE),
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_PREVIEW));

        assertNull(CompDockSource.growPanelHeight(sideBySide,
            CompDockSource.PANEL_TIMELINE, 100, 200, 300));

        assertNull(CompDockSource.growPanelHeight(CompDockSource.defaultLayout(),
            CompDockSource.PANEL_TIMELINE, 247, 247, 478), "no-op when the drawer already fits");

        assertNull(CompDockSource.growPanelHeight(CompDockSource.defaultLayout(),
            "missing-panel", 100, 200, 478), "unknown panels are ignored");
    }

    @Test
    void drawerGrowthKeepsTheSiblingPaneUsable()
    {
        EditorLayoutNode root = new EditorLayoutNode.SplitterNode(true, 0.50F,
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_PREVIEW),
            new EditorLayoutNode.PanelNode(CompDockSource.PANEL_TIMELINE));

        /* body 200, timeline branch 100 → at most 60 % may go to the timeline (80 px preview). */
        EditorLayoutNode grown = CompDockSource.growPanelHeight(root,
            CompDockSource.PANEL_TIMELINE, 100, 190, 200);

        assertNotNull(grown);
        assertEquals(0.40F, ((EditorLayoutNode.SplitterNode) grown).getRatio(), 0.02F);
        assertEquals(120F, (1F - 0.40F) * 200F, 0.5F);
    }

    @Test
    void legacyEffectsRightDefaultIsMigratedToTheLeftOnce()
    {
        CompProject.CompSettings settings = settings();

        settings.dockLayout = CompDockSource.encode(CompDockSource.effectsRightLayout());
        settings.uiLayoutRevision = 0;

        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);
        EditorLayoutNode root = source.getRoot();

        assertEffectsColumnLeftOfPreview(root, "the pre-v7 default must move the library to the left");
        assertTrue(source.isDirty(), "the migrated tree must be written back");

        source.flush();

        assertEquals(1, settings.uiLayoutRevision);
        assertFalse(settings.dockLayout.isBlank());

        /* Second load: revision is 1, so the user's migrated tree is read as-is. */
        CompDockSource reopened = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);

        assertEffectsColumnLeftOfPreview(reopened.getRoot(), "the migrated layout must survive reopening");
    }

    @Test
    void aDeliberateEffectsRightLayoutIsNotMigrated()
    {
        CompProject.CompSettings settings = settings();

        settings.dockLayout = CompDockSource.encode(CompDockSource.effectsRightLayout());
        settings.uiLayoutRevision = CompDockSource.UI_LAYOUT_REVISION;

        CompDockSource source = new CompDockSource(() -> settings, CompDockSource::defaultLayout, null);
        EditorLayoutNode root = source.getRoot();
        EditorLayoutNode.SplitterNode outer = (EditorLayoutNode.SplitterNode) root;

        assertEquals(CompDockSource.PANEL_PREVIEW,
            ((EditorLayoutNode.PanelNode) ((EditorLayoutNode.SplitterNode) outer.getFirst()).getFirst()).getPanelId(),
            "revision 1 means the user chose this layout; do not override it");
    }

    private static void assertEffectsColumnLeftOfPreview(EditorLayoutNode root, String message)
    {
        assertTrue(root instanceof EditorLayoutNode.SplitterNode, message);

        EditorLayoutNode.SplitterNode outer = (EditorLayoutNode.SplitterNode) root;
        EditorLayoutNode.SplitterNode row = (EditorLayoutNode.SplitterNode) outer.getFirst();
        EditorLayoutNode.SplitterNode previewAndInspector = (EditorLayoutNode.SplitterNode) row.getSecond();

        assertEquals(CompDockSource.PANEL_EFFECTS,
            ((EditorLayoutNode.PanelNode) row.getFirst()).getPanelId(), message);
        assertEquals(CompDockSource.PANEL_PREVIEW,
            ((EditorLayoutNode.PanelNode) previewAndInspector.getFirst()).getPanelId(), message);
        assertEquals(CompDockSource.PANEL_INSPECTOR,
            ((EditorLayoutNode.PanelNode) previewAndInspector.getSecond()).getPanelId(), message);
        assertEquals(CompDockSource.PANEL_TIMELINE,
            ((EditorLayoutNode.PanelNode) outer.getSecond()).getPanelId(), message);
    }
}
