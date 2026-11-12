package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quantitative evaluation of an "AUTO" policy for {@code CompPrefs.perPixelDepth} (task-19).
 *
 * <p>Per-pixel depth is only <b>visible</b> when what painter order cannot represent actually
 * happens: two perspective layers that overlap on screen and whose planes are <b>not parallel</b>,
 * so the depth order flips somewhere inside the overlap. For the common AE 3D frame — parallel
 * planes at different z, or a single 3D layer — whole-layer depth sorting is already exact, and the
 * depth path costs extra full-screen passes for no pixel change. The ____bug project is exactly
 * that common case: two parallel planes (z=198 / z=736) under a tilted camera, where the depth
 * path is pixel-identical to the painter path (see {@link DepthPlaneGhostRegressionTest}).</p>
 *
 * <p>This class evaluates that heuristic (implemented here as a prototype; the patch design is in
 * {@code docs/DEPTH-GHOST-FIX.zh.md}) and backs it with pixel evidence: parallel scene ⇒ painter and
 * depth frames are identical (equality asserted), crossing scene ⇒ they differ (per-pixel depth is
 * doing work). No production default is changed.</p>
 */
public class PerPixelDepthAutoPolicyTest
{
    private static final float NORMAL_EPSILON = 0.0005F;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();
    }

    @AfterAll
    public static void destroyContext()
    {
        DepthPlanePixelHarness.shutdownGl();
    }

    /**
     * The production AUTO decision. The predicate lives in {@link CompRenderer#needsPerPixelDepthAuto}
     * so the editor, the AE frame server and the encoder share one rule; this class keeps the
     * pixel evidence for it.
     */
    static boolean autoNeedsPerPixelDepth(CompRenderContext context)
    {
        return CompRenderer.needsPerPixelDepthAuto(context);
    }

    /* ---------------------------------------------------------------- scenes */

    /**
     * Two full-composition rectangle layers rotated around Y in opposite directions, so their
     * planes cross inside the visible frame. Painter order cannot represent the crossing; the
     * per-pixel depth path is exactly for this.
     */
    static CompDocument crossingScene()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();

        for (CompLayer layer : new ArrayList<>(DepthPlanePixelHarness.layersOf(comp)))
        {
            comp.removeLayer(layer.id);
        }

        CompLayer low = DepthPlanePixelHarness.addRect(comp, "low", 960F, 540F, 100F,
            1920F, 1080F, 0xFFE53935, 1F, true);
        CompLayer high = DepthPlanePixelHarness.addRect(comp, "high", 960F, 540F, 500F,
            1920F, 1080F, 0xFF1E88E5, 1F, true);

        /* CompLayer.rotationY is part of the world matrix of a 3D layer. */
        low.rotationY = 32F;
        high.rotationY = -32F;

        return comp;
    }

    /* ---------------------------------------------------------------- tests */

    @Test
    public void parallelPlanesDoNotNeedPerPixelDepth()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertTrue(CompScene.spatial(context), "the tilted camera makes the frame spatial");
        assertFalse(autoNeedsPerPixelDepth(context),
            "the ____bug layers are parallel planes: painter order is already exact");
    }

    @Test
    public void crossingPlanesNeedPerPixelDepth()
    {
        CompDocument comp = crossingScene();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertTrue(autoNeedsPerPixelDepth(context),
            "two non-parallel overlapping planes can only be resolved per pixel");
    }

    @Test
    public void onePerspectiveLayerDoesNotNeedPerPixelDepth()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();

        comp.removeLayer("star");

        assertFalse(autoNeedsPerPixelDepth(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp)));
    }

    @Test
    public void nonOverlappingRotatedLayersDoNotNeedPerPixelDepth()
    {
        CompDocument comp = crossingScene();

        /* Move one rotated plane completely off the composition: no overlap, no crossings. */
        comp.getLayer("high").x = 9000F;

        assertFalse(autoNeedsPerPixelDepth(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp)));
    }

    @Test
    public void legacyModeDoesNotChangeTheParallelSceneDecision()
    {
        CompDocument comp = DepthPlanePixelHarness.twoDPlusThreeDScene();

        assertFalse(autoNeedsPerPixelDepth(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(true), comp)),
            "legacy mode projects more layers, but parallel planes still need no per-pixel depth");
    }

    /**
     * Pixel evidence for the decision: the parallel scene is identical with and without the depth
     * path, the crossing scene is not. If "different" ever became false the heuristic (and the
     * whole per-pixel depth feature) would have lost its reason to exist.
     */
    @Test
    public void pixelEvidenceMatchesTheAutoDecision()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompProject project = DepthPlanePixelHarness.project(false);

        /* Parallel: depth path changes nothing (the common AE 3D frame). */
        CompDocument parallel = DepthPlanePixelHarness.bugScene();
        CompCamera parallelCamera = CompCamera.of(parallel, 0F, 0F);
        DepthPlaneCompositor parallelCompositor = new DepthPlaneCompositor(
            DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

        try
        {
            int parallelDiff = DepthPlanePixelHarness.pixelDiff(
                DepthPlanePixelHarness.paint(project, parallel, parallelCamera),
                DepthPlanePixelHarness.depthAll(project, parallel, parallelCamera, parallelCompositor));

            org.junit.jupiter.api.Assertions.assertEquals(0, parallelDiff,
                "parallel planes: per-pixel depth must not change a single pixel");
        }
        finally
        {
            parallelCompositor.delete();
        }

        /* Crossing: depth path resolves the crossing, painter order cannot. */
        CompDocument crossing = crossingScene();
        CompCamera crossingCamera = CompCamera.of(crossing, 0F, 0F);
        DepthPlaneCompositor crossingCompositor = new DepthPlaneCompositor(
            DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

        try
        {
            int[] painter = DepthPlanePixelHarness.paint(project, crossing, crossingCamera);
            int[] depth = DepthPlanePixelHarness.depthAll(project, crossing, crossingCamera, crossingCompositor);
            int crossingDiff = DepthPlanePixelHarness.pixelDiff(painter, depth);

            assertTrue(crossingDiff > 1000,
                "crossing planes: per-pixel depth must change the picture (diff=" + crossingDiff + " pixels)");
        }
        finally
        {
            crossingCompositor.delete();
        }
    }
}
