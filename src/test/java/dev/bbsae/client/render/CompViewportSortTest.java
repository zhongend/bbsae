package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
