package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch operations must keep the three things that are easy to lose: document order, timing, and
 * relations (parent / matte) that cross the selection boundary.
 */
public class CompLayerOpsTest
{
    private static CompProject project()
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 30F;
        doc.duration = 10F;
        project.comps.add(doc);

        return project;
    }

    private static CompLayer layer(CompDocument doc, String id, float x, float y)
    {
        CompLayer layer = new CompLayer(id, id, "solid");

        layer.x = x;
        layer.y = y;
        layer.width = 200F;
        layer.height = 100F;
        layer.out = doc.duration;
        doc.layers.add(layer);

        return layer;
    }

    @Test
    public void precomposeMovesTheGroupAndRebasesTime()
    {
        CompProject project = project();
        CompDocument doc = project.getCompOrFirst("main");
        CompLayer a = layer(doc, "a", 400F, 300F);
        CompLayer b = layer(doc, "b", 800F, 500F);
        CompLayer outside = layer(doc, "outside", 1200F, 700F);

        a.in = 2F;
        a.out = 6F;
        b.in = 3F;
        b.out = 8F;
        b.parentId = a.id;
        outside.parentId = b.id;

        CompLayer precomp = CompLayerOps.precompose(project, doc, List.of(a, b), "组");

        assertNotNull(precomp);
        assertEquals(2, project.comps.size());

        CompDocument sub = project.getComp(precomp.precompId);

        assertNotNull(sub);
        assertEquals(2, sub.layers.size());
        assertEquals("a", sub.layers.get(0).id);
        assertEquals("b", sub.layers.get(1).id);

        /* Earliest in point (2s) becomes 0; the child keeps its relative offset. */
        assertEquals(0F, sub.layers.get(0).in, 0.001F);
        assertEquals(1F, sub.layers.get(1).in, 0.001F);
        assertEquals(a.id, sub.layers.get(1).parentId);

        assertEquals(2F, precomp.in, 0.001F);
        assertEquals(8F, precomp.out, 0.001F);
        assertEquals(precomp.id, outside.parentId);
        assertFalse(doc.layers.contains(a));
        assertFalse(doc.layers.contains(b));
        assertTrue(doc.layers.contains(precomp));
    }

    @Test
    public void precomposeDropsRelationsThatPointOutsideTheGroup()
    {
        CompProject project = project();
        CompDocument doc = project.getCompOrFirst("main");
        CompLayer outsideParent = layer(doc, "parent", 100F, 100F);
        CompLayer child = layer(doc, "child", 400F, 300F);
        CompLayer other = layer(doc, "other", 600F, 400F);

        child.parentId = outsideParent.id;
        other.trackMatteId = child.id;
        other.trackMatteEnabled = true;

        CompLayer precomp = CompLayerOps.precompose(project, doc, List.of(child), "单个");

        CompDocument sub = project.getComp(precomp.precompId);

        assertTrue(sub.layers.get(0).parentId.isEmpty());
        assertEquals(precomp.id, other.trackMatteId, "the matte user outside the group must follow it");
    }

    @Test
    public void groupUnderNullParentsWithoutMoving()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;

        CompLayer a = layer(doc, "a", 400F, 300F);
        CompLayer b = layer(doc, "b", 800F, 500F);

        float[] beforeA = CompLayerOps.bounds(doc, a);
        float[] beforeB = CompLayerOps.bounds(doc, b);

        CompLayer group = CompLayerOps.groupUnderNull(doc, List.of(a, b));

        assertNotNull(group);
        assertEquals(group.id, a.parentId);
        assertEquals(group.id, b.parentId);

        float[] afterA = CompLayerOps.bounds(doc, a);
        float[] afterB = CompLayerOps.bounds(doc, b);

        for (int i = 0; i < 4; i++)
        {
            assertEquals(beforeA[i], afterA[i], 0.05F);
            assertEquals(beforeB[i], afterB[i], 0.05F);
        }
    }

    @Test
    public void alignMovesToTheCompositionEdges()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;

        CompLayer a = layer(doc, "a", 400F, 300F);
        CompLayer b = layer(doc, "b", 800F, 500F);

        CompLayerOps.align(doc, List.of(a, b), CompLayerOps.ALIGN_LEFT);

        assertEquals(0F, CompLayerOps.bounds(doc, a)[0], 0.01F);
        assertEquals(0F, CompLayerOps.bounds(doc, b)[0], 0.01F);

        CompLayerOps.align(doc, List.of(a, b), CompLayerOps.ALIGN_RIGHT);

        assertEquals(1920F, CompLayerOps.bounds(doc, a)[2], 0.01F);
        assertEquals(1920F, CompLayerOps.bounds(doc, b)[2], 0.01F);

        CompLayerOps.align(doc, List.of(a), CompLayerOps.ALIGN_CENTER_H);

        assertEquals(960F, (CompLayerOps.bounds(doc, a)[0] + CompLayerOps.bounds(doc, a)[2]) * 0.5F, 0.01F);
    }

    @Test
    public void distributeSpacesCentresEvenly()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;

        CompLayer a = layer(doc, "a", 100F, 0F);
        CompLayer b = layer(doc, "b", 300F, 0F);
        CompLayer c = layer(doc, "c", 900F, 0F);

        CompLayerOps.distribute(doc, List.of(a, b, c), true);

        float first = CompLayerOps.centerX(doc, a);
        float middle = CompLayerOps.centerX(doc, b);
        float last = CompLayerOps.centerX(doc, c);

        assertEquals((middle - first), (last - middle), 0.01F);
    }

    @Test
    public void duplicateCreatesIndependentLayers()
    {
        CompDocument doc = new CompDocument();

        CompLayer a = layer(doc, "a", 400F, 300F);
        List<CompLayer> copies = CompLayerOps.duplicate(doc, List.of(a));

        assertEquals(1, copies.size());
        assertEquals(2, doc.layers.size());
        assertNotSame(a, copies.get(0));
        assertFalse(copies.get(0).id.equals(a.id));
        assertEquals(a.x + 24F, copies.get(0).x, 0.001F);
    }
}
