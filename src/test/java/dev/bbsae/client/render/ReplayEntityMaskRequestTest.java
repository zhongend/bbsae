package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The allocation-free entity request preflight must preserve nested-precomp visibility. */
public class ReplayEntityMaskRequestTest
{
    @Test
    public void ordinaryAndHiddenLayersNeedNoEntityRequest()
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.layers.add(new CompLayer("solid", "Solid", "solid"));

        CompLayer hidden = new CompLayer("actor", "Actor", "replay");
        hidden.visible = false;
        doc.layers.add(hidden);

        assertFalse(ReplayEntityMask.hasEntityLayer(doc, project, 0));
    }

    @Test
    public void visibleEntitiesInsidePrecompsAreFoundWithinTheExistingDepthLimit()
    {
        CompProject project = new CompProject();
        CompDocument root = new CompDocument();
        CompDocument nested = new CompDocument();
        CompLayer precomp = new CompLayer("nested", "Nested", "precomp");

        nested.id = "nested";
        nested.layers.add(new CompLayer("actor", "Actor", "replay"));
        precomp.precompId = nested.id;
        root.layers.add(precomp);
        project.comps.add(root);
        project.comps.add(nested);

        assertTrue(ReplayEntityMask.hasEntityLayer(root, project, 0));
        assertFalse(ReplayEntityMask.hasEntityLayer(root, project, 9),
            "the preflight must use the same recursion limit as request collection");
    }

    /**
     * The world/depth request and the overlay's entity preflight must see into a precomp too.
     * Scanning only the root document silently disabled the BBS-preview world capture and the
     * entity matte for every nested layer.
     */
    @Test
    public void nestedWorldAndEntityLayersAreVisibleToTheCaptureChecks()
    {
        CompProject project = new CompProject();
        CompDocument root = new CompDocument();
        CompDocument nested = new CompDocument();
        CompLayer precomp = new CompLayer("nested", "Nested", "precomp");
        CompLayer bbs = new CompLayer("world", "World", "bbs");
        CompLayer actor = new CompLayer("actor", "Actor", "replay");

        nested.id = "nested";
        nested.layers.add(bbs);
        nested.layers.add(actor);
        precomp.precompId = nested.id;
        root.layers.add(precomp);
        project.comps.add(root);
        project.comps.add(nested);

        assertTrue(dev.bbsae.client.overlay.BbsPreviewBridge.needsWorldFrame(project, root),
            "a nested bbs/actor layer still samples the live world");
        assertTrue(dev.bbsae.client.overlay.BbsPreviewBridge.hasReplayEntityLayer(project, root),
            "the overlay's entity request must recurse into precomps");
    }
}
