package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AE 3D ordering contract (P0-2): only consecutive runs of real 3D pixel layers are depth-sorted
 * far-to-near. A 2D layer, an adjustment layer, a control layer or a layer without pixels keeps
 * its stack slot and <b>breaks</b> the run, so the composition painter order matches AE.
 */
public class CompLayer3DRunOrderTest
{
    private static CompLayer layer(String id, String type, boolean threeD, float z)
    {
        CompLayer layer = new CompLayer(id, id, type);

        layer.threeD = threeD;
        layer.z = z;
        layer.width = 400F;
        layer.height = 200F;
        layer.x = 960F;
        layer.y = 540F;

        return layer;
    }

    private static CompProject project(boolean legacy)
    {
        CompProject project = new CompProject();

        project.settings.legacy3DLogic = legacy;

        return project;
    }

    /**
     * Tilted camera so plane depth is a real 3D quantity (a straight camera keeps every z = 0
     * plane tied, which would hide the sorting under test).
     */
    private static CompDocument comp(CompProject project)
    {
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.threeD = true;
        camera.camera3D = true;
        camera.x = 960F;
        camera.y = 540F;
        camera.cameraZ = CompCamera.focalFor(50F, 1080);
        camera.z = camera.cameraZ;
        camera.cameraTargetX = 960F;
        camera.cameraTargetY = 540F;
        camera.cameraTargetZ = 0F;
        camera.cameraFov = 50F;
        camera.cameraLookAt = false;
        camera.rotationY = 20F;
        camera.out = 10F;
        comp.addLayer(camera);
        comp.activeCameraId = camera.id;

        return comp;
    }

    private static List<CompLayer> ordered(CompProject project, CompDocument comp)
    {
        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);

        return CompRenderer.orderLayers(comp, context);
    }

    @Test
    public void a2DLayerBreaksThe3DRunAndKeepsItsSlot()
    {
        CompProject project = project(false);
        CompDocument comp = comp(project);
        CompLayer front = layer("front", "solid", true, 400F);
        CompLayer flat = layer("flat", "solid", false, 0F);
        CompLayer back = layer("back", "solid", true, -400F);

        comp.addLayer(front);
        comp.addLayer(flat);
        comp.addLayer(back);

        List<CompLayer> order = ordered(project, comp);

        assertEquals("front", order.get(1).id, "the 3D layer below the 2D one is not sorted past it");
        assertEquals("flat", order.get(2).id, "the 2D layer keeps its stack slot");
        assertEquals("back", order.get(3).id, "the 3D layer above the 2D one is not sorted past it");
    }

    @Test
    public void aPure3DRunSortsFarToNear()
    {
        CompProject project = project(false);
        CompDocument comp = comp(project);

        comp.addLayer(layer("front", "solid", true, 400F));
        comp.addLayer(layer("back", "solid", true, -400F));

        List<CompLayer> order = ordered(project, comp);

        assertEquals("back", order.get(1).id, "far first");
        assertEquals("front", order.get(2).id);
    }

    @Test
    public void adjustmentAndControlLayersBreakTheRun()
    {
        CompProject project = project(false);
        CompDocument comp = comp(project);

        comp.addLayer(layer("frontA", "solid", true, 400F));
        comp.addLayer(layer("adjust", "adjustment", false, 0F));
        comp.addLayer(layer("frontB", "solid", true, -400F));
        comp.addLayer(layer("null", "null", false, 0F));
        comp.addLayer(layer("frontC", "solid", true, 200F));

        List<CompLayer> order = ordered(project, comp);

        /* addLayer lower-cases the generated ids. */
        assertEquals("fronta", order.get(1).id);
        assertEquals("adjust", order.get(2).id);
        assertEquals("frontb", order.get(3).id);
        assertEquals("null", order.get(4).id);
        assertEquals("frontc", order.get(5).id);
    }

    /**
     * Legacy logic keeps the pre-AE rule: once the frame is spatial, every content layer is
     * depth-sorted together, including 2D layers. A 2D layer always lies on the z = 0 plane
     * (its z field is not part of its transform), so it sorts between two 3D planes.
     */
    @Test
    public void legacyLogicSorts2DLayersToo()
    {
        CompProject project = project(true);
        CompDocument comp = comp(project);

        comp.addLayer(layer("front", "solid", true, 400F));
        comp.addLayer(layer("flat", "solid", false, 0F));
        comp.addLayer(layer("back", "solid", true, -400F));

        List<CompLayer> order = ordered(project, comp);

        assertEquals("back", order.get(1).id, "legacy: the whole spatial frame sorts far-to-near");
        assertEquals("flat", order.get(2).id, "the 2D layer was sorted between the two 3D planes");
        assertEquals("front", order.get(3).id);
    }

    /** An orthographic camera removes perspective but not 3D depth order: the run must still sort. */
    @Test
    public void orthographicCameraStillSorts3DRuns()
    {
        CompProject project = project(false);
        CompDocument comp = comp(project);

        comp.getLayer("camera").camera3D = false;

        comp.addLayer(layer("front", "solid", true, 400F));
        comp.addLayer(layer("back", "solid", true, -400F));

        List<CompLayer> order = ordered(project, comp);

        assertEquals("back", order.get(1).id, "orthographic 3D run still sorts far-to-near");
        assertEquals("front", order.get(2).id);
    }

    /** A 2D layer between two 3D groups is not picked up by either run. */
    @Test
    public void a2DLayerBetweenTwoRunsIsNeverMoved()
    {
        CompProject project = project(false);
        CompDocument comp = comp(project);

        comp.addLayer(layer("a1", "solid", true, 100F));
        comp.addLayer(layer("a2", "solid", true, -100F));
        comp.addLayer(layer("flat", "solid", false, 0F));
        comp.addLayer(layer("b1", "solid", true, 100F));
        comp.addLayer(layer("b2", "solid", true, -100F));

        List<CompLayer> order = ordered(project, comp);

        assertEquals("a2", order.get(1).id);
        assertEquals("a1", order.get(2).id);
        assertEquals("flat", order.get(3).id);
        assertEquals("b2", order.get(4).id);
        assertEquals("b1", order.get(5).id);
    }
}
