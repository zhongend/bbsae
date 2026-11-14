package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two precomps must be independent objects.
 *
 * <p>A precomp layer stores only a {@code precompId}, so sharing is a real possibility: duplicate a
 * layer, paste a layer, or re-point a precomp target, and the copy can end up playing the original's
 * nested composition. This suite covers the model-level guarantees behind those gestures — every
 * path that is supposed to produce "a second, private precomp" is asserted to produce a distinct
 * {@link CompDocument} whose edits cannot leak into the source.</p>
 */
public class PrecompIndependenceTest
{
    private static CompProject project()
    {
        CompProject project = new CompProject();
        CompDocument main = new CompDocument();

        main.id = "main";
        main.width = 1920;
        main.height = 1080;
        main.fps = 30F;
        main.duration = 10F;
        project.comps.add(main);

        return project;
    }

    private static CompDocument comp(CompProject project, String id, String name)
    {
        CompDocument doc = new CompDocument();

        doc.id = id;
        doc.name = name;
        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 30F;
        doc.duration = 10F;
        project.comps.add(doc);

        return doc;
    }

    private static CompLayer precomp(CompDocument doc, String id, String target)
    {
        CompLayer layer = new CompLayer(id, id, "precomp");

        layer.precompId = target;
        layer.out = doc.duration;
        doc.layers.add(layer);

        return layer;
    }

    @Test
    public void duplicatingACompositionCreatesADeepIndependentCopy()
    {
        CompProject project = project();
        CompDocument source = comp(project, "child", "Child");
        CompLayer inner = new CompLayer("inner", "Inner", "solid");

        inner.x = 100F;
        source.layers.add(inner);

        CompDocument clone = CompLayerOps.duplicateComposition(project, source);

        assertNotNull(clone);
        assertNotEquals(source.id, clone.id, "the clone needs its own composition id");
        assertEquals("Child 副本", clone.name);
        assertEquals(3, project.comps.size());

        /* The layer object and the document object must be different objects, not a shallow view. */
        assertTrue(clone != source);
        assertTrue(clone.layers.get(0) != inner);

        clone.layers.get(0).x = 999F;

        assertEquals(100F, inner.x, 0.001F, "editing the copy must never move the original layer");
        assertEquals(100F, source.layers.get(0).x, 0.001F);
    }

    @Test
    public void aDuplicatedCompositionClonesItsNestedPrecompTree()
    {
        CompProject project = project();
        CompDocument child = comp(project, "child", "Child");
        CompDocument grandchild = comp(project, "grandchild", "Grandchild");

        precomp(child, "nested", grandchild.id);
        grandchild.layers.add(new CompLayer("deep", "Deep", "solid"));

        CompDocument clone = CompLayerOps.duplicateComposition(project, child);
        CompLayer clonedPrecomp = clone.layers.get(0);

        assertNotEquals(grandchild.id, clonedPrecomp.precompId,
            "the cloned tree must not point back at the original grandchild");
        assertNotNull(project.getComp(clonedPrecomp.precompId));
        assertEquals(5, project.comps.size(), "main + child + grandchild + two clones");
    }

    @Test
    public void aLayerCanBeGivenAPrivatePrecompTarget()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");
        CompLayer inner = new CompLayer("inner", "Inner", "solid");

        inner.x = 100F;
        shared.layers.add(inner);

        CompLayer a = precomp(main, "a", shared.id);
        CompLayer b = precomp(main, "b", shared.id);

        assertEquals(a.precompId, b.precompId, "both layers start on the same target");

        CompDocument copy = CompLayerOps.duplicatePrecompTarget(project, a);

        assertNotNull(copy);
        assertNotEquals(shared.id, a.precompId, "the requesting layer must be re-pointed at the copy");
        assertEquals(shared.id, b.precompId, "the other layer keeps the original target");

        copy.layers.get(0).x = 777F;

        assertEquals(100F, inner.x, 0.001F, "editing one precomp must not edit the other");
        assertEquals(100F, shared.layers.get(0).x, 0.001F);
    }

    @Test
    public void copyingTheSamePrecompLayerTwiceProducesTwoDistinctComps()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        CompLayer layer = precomp(main, "pc", shared.id);
        CompLayer first = CompLayerOps.duplicateLayer(project, layer);
        CompLayer second = CompLayerOps.duplicateLayer(project, layer);

        assertNotEquals(first.precompId, second.precompId, "each copy is its own precomp");
        assertNotEquals(shared.id, first.precompId);
        assertNotEquals(shared.id, second.precompId);
        assertEquals(4, project.comps.size(), "main + shared + two clones");
    }

    @Test
    public void thePrivateTargetSurvivesSaveAndReload()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        CompLayer layer = precomp(main, "pc", shared.id);
        CompDocument copy = CompLayerOps.duplicatePrecompTarget(project, layer);

        CompProject restored = CompStore.restore(CompStore.snapshot(project));

        assertNotNull(restored);

        CompLayer reloaded = restored.getCompOrFirst("main").getLayer("pc");

        assertNotNull(reloaded);
        assertEquals(copy.id, reloaded.precompId, "the re-pointed target is persisted");
        assertNotNull(restored.getComp(reloaded.precompId));
    }

    @Test
    public void onlyPrecompLayersCanBeRePointed()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompLayer solid = new CompLayer("solid", "Solid", "solid");

        main.layers.add(solid);

        assertNull(CompLayerOps.duplicatePrecompTarget(project, solid));
        assertNull(CompLayerOps.duplicatePrecompTarget(project, null));

        CompLayer dangling = precomp(main, "dangling", "missing");

        assertNull(CompLayerOps.duplicatePrecompTarget(project, dangling),
            "a dangling target has nothing to clone");
    }

    @Test
    public void duplicateAndPasteKeepTheirOwnTrees()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        CompLayer layer = precomp(main, "pc", shared.id);
        List<CompLayer> copies = CompLayerOps.duplicate(project, main, List.of(layer));
        List<CompLayer> pasted = CompLayerOps.paste(project, main, List.of(layer.duplicate()), main.layers.size());

        assertEquals(1, copies.size());
        assertEquals(1, pasted.size());
        assertNotEquals(shared.id, copies.get(0).precompId);
        assertNotEquals(shared.id, pasted.get(0).precompId);
        assertNotEquals(copies.get(0).precompId, pasted.get(0).precompId,
            "every copy gets a fresh private tree, even when made the same way twice");
    }

    @Test
    public void enteringALegacyCopyDetachesItFirst()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "text"));

        precomp(main, "pc", shared.id);

        CompLayer copy = precomp(main, "pc_copy", shared.id);

        copy.name = "文字组 副本";

        assertEquals(1, CompLayerOps.countSharedSiblings(main, copy));
        assertTrue(CompLayerOps.detachIfSharedCopy(project, main, copy),
            "opening a legacy copy is the last chance to make it independent");
        assertEquals(0, CompLayerOps.countSharedSiblings(main, copy));
        assertFalse(CompLayerOps.detachIfSharedCopy(project, main, copy),
            "already independent");
    }

    @Test
    public void detachOnlyTouchesCopyLookingLayers()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        precomp(main, "pc", shared.id);

        CompLayer second = precomp(main, "second_instance", shared.id);

        assertFalse(CompLayerOps.detachIfSharedCopy(project, main, second),
            "a deliberately shared instance must keep sharing");
        assertEquals(shared.id, second.precompId);
        assertEquals(0, CompLayerOps.countRepairableSharedPrecompCopies(project));
    }

    /* ------------------------------------------------- legacy shared-copy repair */

    @Test
    public void aLegacySharedCopyIsDetachedOnRepair()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");
        CompLayer inner = new CompLayer("inner", "Inner", "text");

        inner.text = "original";
        shared.layers.add(inner);

        CompLayer source = precomp(main, "pc", shared.id);
        CompLayer legacyCopy = precomp(main, "pc_copy", shared.id);

        legacyCopy.name = "Precomp 副本";

        assertEquals(2, main.layers.size());
        assertEquals(1, CompLayerOps.countRepairableSharedPrecompCopies(project));

        int repaired = CompLayerOps.repairSharedPrecompCopies(project);

        assertEquals(1, repaired);
        assertEquals(shared.id, source.precompId, "the first layer keeps the original target");
        assertNotEquals(shared.id, legacyCopy.precompId, "the copy gets its own composition");

        CompDocument copy = project.getComp(legacyCopy.precompId);

        assertNotNull(copy);
        assertNotEquals(shared, copy);

        copy.layers.get(0).text = "copy only";

        assertEquals("original", inner.text, "editing the detached copy must not edit the original");
        assertEquals(0, CompLayerOps.repairSharedPrecompCopies(project), "repair is idempotent");
    }

    @Test
    public void aDeliberatelySharedInstanceIsNotDetached()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        /* Two ordinary layers (no copy marker) pointing at one comp: this is a legitimate
         * "two instances of the same nested composition" setup. */
        CompLayer a = precomp(main, "left", shared.id);
        CompLayer b = precomp(main, "right", shared.id);

        assertEquals(0, CompLayerOps.countRepairableSharedPrecompCopies(project));
        assertEquals(0, CompLayerOps.repairSharedPrecompCopies(project));
        assertEquals(shared.id, a.precompId);
        assertEquals(shared.id, b.precompId);
        assertEquals(2, project.comps.size(), "no composition was added");
    }

    @Test
    public void everyLegacyCopyInAGroupGetsItsOwnTree()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        CompLayer source = precomp(main, "pc", shared.id);
        CompLayer second = precomp(main, "pc_copy", shared.id);
        CompLayer third = precomp(main, "pc_copy_2", shared.id);

        second.name = "A 副本";
        third.name = "B 副本";

        assertEquals(2, CompLayerOps.repairSharedPrecompCopies(project));
        assertEquals(shared.id, source.precompId);
        assertNotEquals(shared.id, second.precompId);
        assertNotEquals(shared.id, third.precompId);
        assertNotEquals(second.precompId, third.precompId);
        assertEquals(4, project.comps.size(), "main + shared + two private copies");
    }

    @Test
    public void crossCompositionReuseIsLeftAlone()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument other = comp(project, "other", "Other");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        CompLayer inMain = precomp(main, "pc_copy", shared.id);
        CompLayer inOther = precomp(other, "pc_copy_2", shared.id);

        inMain.name = "副本";
        inOther.name = "副本";

        /* Sharing across compositions is project structure, not a duplicate inside one comp. */
        assertEquals(0, CompLayerOps.repairSharedPrecompCopies(project));
        assertEquals(shared.id, inMain.precompId);
        assertEquals(shared.id, inOther.precompId);
    }

    @Test
    public void aDanglingSharedTargetIsNotCloned()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompLayer a = precomp(main, "pc", "missing");
        CompLayer b = precomp(main, "pc_copy", "missing");

        b.name = "副本";

        assertEquals(0, CompLayerOps.countRepairableSharedPrecompCopies(project));
        assertEquals(0, CompLayerOps.repairSharedPrecompCopies(project));
        assertEquals("missing", a.precompId);
        assertEquals("missing", b.precompId);
    }

    @Test
    public void theRepairedCopySurvivesSaveAndReload()
    {
        CompProject project = project();
        CompDocument main = project.getCompOrFirst("main");
        CompDocument shared = comp(project, "shared", "Shared");

        shared.layers.add(new CompLayer("inner", "Inner", "solid"));

        CompLayer source = precomp(main, "pc", shared.id);
        CompLayer legacyCopy = precomp(main, "pc_copy", shared.id);

        legacyCopy.name = "副本";

        assertEquals(1, CompLayerOps.repairSharedPrecompCopies(project));
        assertEquals(shared.id, source.precompId);

        String saved = CompStore.snapshot(project);
        CompProject restored = CompStore.restore(saved);

        assertNotNull(restored);

        CompLayer reloaded = restored.getCompOrFirst("main").getLayer("pc_copy");
        CompDocument detached = restored.getComp(reloaded.precompId);

        assertNotNull(detached);
        assertNotEquals(shared.id, detached.id);
        assertEquals(0, CompLayerOps.repairSharedPrecompCopies(restored),
            "a repaired project has nothing left to repair");
    }
}
