package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The 3D workspace must sort with the orbit camera. Sorting against the composition camera made the
 * order (and therefore occlusion) change incorrectly as the user rotated the view.
 */
public class CompViewportSortTest
{
    private static CompLayer layer(String id, float z)
    {
        CompLayer layer = new CompLayer(id, id, "shape");

        layer.threeD = true;
        layer.z = z;
        layer.width = 200F;
        layer.height = 200F;

        return layer;
    }

    @Test
    public void theOrbitCameraDecidesWhichPlaneIsFarther()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);

        comp.layers.add(layer("back", 0F));
        comp.layers.add(layer("front", 100F));

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        List<CompLayer> order = new ArrayList<>(comp.layers);

        /* Eye in front of the comp, looking down -Z: the layer at z=0 is farther than z=100. */
        CompViewportRenderer.sortByView(context, order, new Vector3f(0F, 0F, 1000F), new Vector3f(0F, 0F, -1F));
        assertEquals("back", order.get(0).id);
        assertEquals("front", order.get(1).id);

        /* Orbit behind the comp: the same two planes swap depth order. */
        CompViewportRenderer.sortByView(context, order, new Vector3f(0F, 0F, -1000F), new Vector3f(0F, 0F, 1F));
        assertEquals("front", order.get(0).id, "from behind, z=100 is the farther plane");
        assertEquals("back", order.get(1).id);
    }

    @Test
    public void coplanarLayersKeepTheStackOrder()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);

        comp.layers.add(layer("bottom", 0F));
        comp.layers.add(layer("top", 0F));

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        List<CompLayer> order = new ArrayList<>(comp.layers);

        CompViewportRenderer.sortByView(context, order, new Vector3f(0F, 0F, 1000F), new Vector3f(0F, 0F, -1F));
        assertEquals("bottom", order.get(0).id);
        assertEquals("top", order.get(1).id);
    }

    /**
     * P0 regression: a 2D layer owns no depth, so it must keep its stack slot and break a 3D run
     * instead of being sorted with the planes around it.
     */
    @Test
    public void a2DLayerKeepsItsStackSlotAndBreaksThe3DRun()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);
        CompLayer front = layer("front", 100F);
        CompLayer mid = new CompLayer("mid", "mid", "solid");
        CompLayer back = layer("back", 0F);

        mid.width = 200F;
        mid.height = 200F;

        comp.layers.add(front);
        comp.layers.add(mid);
        comp.layers.add(back);

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        List<CompLayer> order = new ArrayList<>(comp.layers);

        CompViewportRenderer.sortByViewRuns(context, order, new Vector3f(0F, 0F, 1000F), new Vector3f(0F, 0F, -1F));

        assertEquals("front", order.get(0).id, "the first 3D run stays in stack order around the 2D layer");
        assertEquals("mid", order.get(1).id, "the 2D layer never moves");
        assertEquals("back", order.get(2).id, "the second 3D run stays in stack order around the 2D layer");
    }

    /** Without the 2D break the same three 3D layers sort far-to-near. */
    @Test
    public void aPure3DRunStillSortsAroundThe2DLayerRule()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);

        comp.layers.add(layer("front", 100F));
        comp.layers.add(layer("back", 0F));

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        List<CompLayer> order = new ArrayList<>(comp.layers);

        CompViewportRenderer.sortByViewRuns(context, order, new Vector3f(0F, 0F, 1000F), new Vector3f(0F, 0F, -1F));

        assertEquals("back", order.get(0).id, "far first");
        assertEquals("front", order.get(1).id);
    }

    private static CompViewportCamera viewport(int width, int height)
    {
        CompViewportCamera camera = new CompViewportCamera();

        camera.setViewport(width, height);
        camera.setTarget(960F, 540F, 0F);
        camera.setPose(0F, 0F, 2000F);
        camera.update();

        return camera;
    }

    /**
     * P0 regression: in the 3D workspace a 2D layer is mapped by the viewport contain-fit of the
     * composition, not by the orbit camera. Orbiting must not move it.
     */
    @Test
    public void a2DLayerQuadIsScreenFixedInThe3DWorkspace()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);
        CompLayer flat = new CompLayer("flat", "flat", "solid");

        flat.width = 400F;
        flat.height = 200F;
        flat.x = 960F;
        flat.y = 540F;
        comp.layers.add(flat);

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        CompViewportCamera camera = viewport(960, 540);

        float[] straight = CompViewportRenderer.layerQuad(context, flat, camera, 0F, 0F);

        /* Fit: min(960/1920, 540/1080) = 0.5, no offset. TL is the layer centre minus half its
         * size, BR is the centre plus half its size. */
        assertEquals(380F, straight[0], 0.01F);
        assertEquals(220F, straight[1], 0.01F);
        assertEquals(580F, straight[4], 0.01F);
        assertEquals(320F, straight[5], 0.01F);

        camera.setPose(60F, 25F, 900F);
        camera.update();

        float[] orbited = CompViewportRenderer.layerQuad(context, flat, camera, 0F, 0F);

        for (int i = 0; i < straight.length; i++)
        {
            assertEquals(straight[i], orbited[i], 0.01F, "orbiting must not move a screen-fixed 2D layer");
        }
    }

    /** The hit test follows the same screen-fixed rule: a 2D layer stays clickable where drawn. */
    @Test
    public void a2DLayerRemainsPickableUnderAnOrbitedCamera()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);
        CompLayer flat = new CompLayer("flat", "flat", "solid");

        flat.width = 400F;
        flat.height = 200F;
        flat.x = 960F;
        flat.y = 540F;
        comp.layers.add(flat);

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        CompViewportCamera camera = viewport(960, 540);

        camera.setPose(75F, 35F, 700F);
        camera.update();

        assertEquals("flat", CompViewportRenderer.layerAt(context, camera, 0F, 0F,
            480F, 270F).id, "layer centre is screen-fixed at the viewport centre");
        assertNull(CompViewportRenderer.layerAt(context, camera, 0F, 0F, 5F, 5F));
    }
}
