package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent replay models are depth volumes, not planes.
 *
 * <p>The AUTO per-pixel-depth predicate used to reason only about parallel/non-parallel
 * <b>planes</b>. A model has thickness, so two parallel planes can still cut through it; keeping
 * AUTO off is exactly the "replay entity penetrates the text/shape layer" bug. These tests pin the
 * gate without needing a GL context.</p>
 */
public class ModelVolumeDepthTest
{
    private static CompLayer modelLayer()
    {
        CompLayer layer = new CompLayer("model", "Model", "replay");

        layer.visible = true;
        layer.in = 0F;
        layer.out = 12F;
        layer.independent = true;
        layer.replayId = "replay";
        /* Deliberately the legacy state: an old project can have an independent model whose
         * threeD switch was never set. It is still rendered through the camera. */
        layer.threeD = false;
        layer.x = 960F;
        layer.y = 540F;
        layer.z = 0F;
        layer.width = 320F;
        layer.height = 320F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;
        layer.opacity = 1F;

        return layer;
    }

    private static CompDocument parallelPlanePlusModel()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompLayer card = new CompLayer("card", "Card", "shape");

        card.visible = true;
        card.in = 0F;
        card.out = 12F;
        card.threeD = true;
        card.x = 960F;
        card.y = 540F;
        card.z = 0F;
        card.width = 640F;
        card.height = 360F;
        card.anchorX = 0.5F;
        card.anchorY = 0.5F;
        card.opacity = 1F;

        comp.addLayer(card);
        comp.addLayer(modelLayer());

        return comp;
    }

    @Test
    public void aModelOnTheCompositionPlaneIsStillDepthContent()
    {
        CompDocument comp = parallelPlanePlusModel();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertTrue(CompScene.isModelVolume(comp.getLayer("model")));
        assertTrue(CompScene.hasDepth(context), "a model has thickness even at z=0");
        assertTrue(CompScene.spatial(context), "the frame must take the spatial path");
    }

    @Test
    public void autoDepthTurnsOnForAParallelCardAndAModel()
    {
        CompDocument comp = parallelPlanePlusModel();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertTrue(CompRenderer.needsPerPixelDepthAuto(context),
            "parallel planes are only exact for planes; a model volume needs per-pixel depth");
        assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false));
    }

    @Test
    public void autoDepthStaysOffForOneModelAlone()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();

        comp.removeLayer("star");
        comp.removeLayer("polygon");
        comp.addLayer(modelLayer());

        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertFalse(CompRenderer.needsPerPixelDepthAuto(context),
            "a single model resolves its own self-occlusion in its depth target");
    }

    @Test
    public void aLegacyModelLayerJoinsTheThreeDSortRun()
    {
        CompDocument comp = parallelPlanePlusModel();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);
        List<CompLayer> ordered = CompRenderer.orderLayers(comp, context);

        assertTrue(ordered.indexOf(comp.getLayer("model")) >= 0);
        assertTrue(CompScene.sortsIn3D(context, comp.getLayer("model")),
            "an old independent model must not break the 3D run");
        assertTrue(CompScene.usePerspective(context, comp.getLayer("model")),
            "models are rendered through the camera regardless of the legacy flag");
    }
}
