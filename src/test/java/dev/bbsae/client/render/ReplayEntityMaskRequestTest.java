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
}
