package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production tri-state for the per-pixel depth switch (task-23): OFF / ON / AUTO.
 *
 * <p>{@code CompPrefs.perPixelDepthMode} (0=off, 1=on, 2=auto) is consumed through
 * {@link CompRenderer#perPixelDepthRequested(CompRenderContext)}; the legacy
 * {@code perPixelDepth} boolean still means ON when the mode is 0. AUTO follows the predicate in
 * {@link CompRenderer#needsPerPixelDepthAuto}: the path is unnecessary unless two non-parallel
 * perspective layers overlap on screen.</p>
 *
 * <p>Tests use the explicit variant
 * {@link CompRenderer#perPixelDepthFrame(CompRenderContext, int, boolean)} because the
 * FabricLoader-backed preference store is unreachable headless — the production entry point reads
 * the same helper from the preference.</p>
 */
public class PerPixelDepthAutoModeTest
{
    private static final int EXPORT_W = 768;
    private static final int EXPORT_H = 432;
    private static final int PREVIEW_W = 384;
    private static final int PREVIEW_H = 216;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();
    }

    @AfterAll
    public static void destroyContext()
    {
        DepthPlanePixelHarness.shutdownGl();
        System.clearProperty("bbsae.debugForceDepthPlane");
        System.clearProperty("bbsae.debugNoDepthPlane");
    }

    /* ------------------------------------------------------------- decisions */

    @Test
    public void autoStaysOffForTheParallelPlaneBugProject()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertFalse(CompRenderer.needsPerPixelDepthAuto(context),
            "the ____bug layers are parallel planes: painter order is exact");
        assertFalse(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false),
            "AUTO must not acquire the depth target for a parallel-plane frame");
        assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_ON, false),
            "explicit ON still takes the depth path");
    }

    @Test
    public void autoTurnsOnForCrossingPlanes()
    {
        CompDocument comp = PerPixelDepthAutoPolicyTest.crossingScene();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertTrue(CompRenderer.needsPerPixelDepthAuto(context),
            "non-parallel overlapping layers can only be resolved per pixel");
        assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false));
    }

    @Test
    public void autoStaysOffForSingleAndNonOverlappingAndFlatScenes()
    {
        /* Single 3D layer. */
        CompDocument single = DepthPlanePixelHarness.bugScene();
        single.removeLayer("star");

        CompRenderContext singleContext = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), single);

        assertFalse(CompRenderer.needsPerPixelDepthAuto(singleContext));

        /* Two rotated layers that do not overlap on screen. */
        CompDocument apart = PerPixelDepthAutoPolicyTest.crossingScene();
        apart.getLayer("high").x = 9000F;

        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), apart)));

        /* All-2D composition (tilted camera, but no layer takes the perspective path). */
        CompDocument flat = DepthPlanePixelHarness.bugScene();

        for (CompLayer layer : DepthPlanePixelHarness.layersOf(flat))
        {
            layer.threeD = false;
        }

        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), flat)));
    }

    @Test
    public void legacy3DLogicUsesTheSameAutoPredicate()
    {
        CompDocument parallel = DepthPlanePixelHarness.twoDPlusThreeDScene();

        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(true), parallel)),
            "legacy projects more layers, but parallel planes still need no per-pixel depth");

        CompDocument crossing = PerPixelDepthAutoPolicyTest.crossingScene();

        assertTrue(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(true), crossing)));
    }

    /**
     * P1 regression: only real 3D pixel layers may turn AUTO on. A 2D layer (even a rotated,
     * overlapping one), a hidden/trimmed/soloed-out 3D layer, or a control layer without pixels
     * cannot produce the crossing AUTO exists for.
     */
    @Test
    public void autoOnlyCountsReal3DPixelLayers()
    {
        CompProject project = DepthPlanePixelHarness.project(false);

        /* Two crossing 3D planes: AUTO on. */
        CompDocument crossing = PerPixelDepthAutoPolicyTest.crossingScene();

        assertTrue(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(project, crossing)));

        /* Demote one plane to 2D: its bounds still overlap, but it is screen-fixed, so painter
         * order is exact again. */
        crossing.getLayer("high").threeD = false;
        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(project, crossing)),
            "a 2D layer cannot cross a 3D plane");

        /* The same scene in legacy logic must ignore the 2D layer too: AUTO counts only threeD. */
        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(true), crossing)),
            "AUTO is a 3D-pixel concept even in legacy projection mode");

        /* Hidden, trimmed out and soloed out 3D layers are not part of the frame. */
        CompDocument visible = PerPixelDepthAutoPolicyTest.crossingScene();

        visible.getLayer("high").visible = false;
        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(project, visible)));

        CompDocument trimmed = PerPixelDepthAutoPolicyTest.crossingScene();

        trimmed.getLayer("high").in = 5F;
        trimmed.getLayer("high").out = 6F;
        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(project, trimmed)),
            "the frame time is 0s, outside the layer's trim");

        CompDocument soloedOut = PerPixelDepthAutoPolicyTest.crossingScene();

        soloedOut.getLayer("low").solo = true;
        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(project, soloedOut)),
            "only the soloed layer is visible, so there is nothing to cross");

        /* A null layer owns no pixels, even when forced 3D. */
        CompDocument control = PerPixelDepthAutoPolicyTest.crossingScene();

        control.getLayer("high").type = "null";
        control.getLayer("high").threeD = true;
        assertFalse(CompRenderer.needsPerPixelDepthAuto(DepthPlanePixelHarness.context(project, control)));
        assertFalse(CompScene.hasPixels(control.getLayer("high")));
    }

    /* --------------------------------------------------------- gating table */

    @Test
    public void triStateGateMatchesTheDocumentedSemantics()
    {
        CompDocument parallel = DepthPlanePixelHarness.bugScene();
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, parallel);
        CompDocument crossing = PerPixelDepthAutoPolicyTest.crossingScene();
        CompRenderContext crossingContext = DepthPlanePixelHarness.context(project, crossing);

        /* OFF + legacy false -> no depth target. */
        assertFalse(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_OFF, false));

        /* OFF + legacy true -> ON (migration path for pre-tri-state preference files). */
        assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_OFF, true));

        /* Explicit ON ignores the legacy flag. */
        assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_ON, false));

        /* AUTO follows the geometry predicate; the legacy flag is irrelevant for AUTO. */
        assertFalse(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, true),
            "AUTO on a parallel-plane frame must not be flipped by the legacy boolean");
        assertTrue(CompRenderer.perPixelDepthFrame(crossingContext, CompRenderer.DEPTH_MODE_AUTO, false));

        /* Unknown mode values are treated as OFF (forward compatibility). */
        assertFalse(CompRenderer.perPixelDepthFrame(context, 7, false));

        /* The developer override forces ON from every mode. */
        try
        {
            System.setProperty("bbsae.debugForceDepthPlane", "true");

            assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_OFF, false));
            assertTrue(CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false));
        }
        finally
        {
            System.clearProperty("bbsae.debugForceDepthPlane");
        }
    }

    @Test
    public void autoDecisionIgnoresPreviewAndScale()
    {
        CompDocument crossing = PerPixelDepthAutoPolicyTest.crossingScene();
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext preview = DepthPlanePixelHarness.context(project, crossing);
        CompRenderContext exporter = DepthPlanePixelHarness.context(project, crossing);

        preview.preview = true;
        preview.scale = 0.5F;
        exporter.preview = false;
        exporter.scale = 1F;

        assertEquals(CompRenderer.needsPerPixelDepthAuto(preview),
            CompRenderer.needsPerPixelDepthAuto(exporter),
            "AUTO is frame geometry, not an editor/export parameter");

        assertTrue(CompRenderer.needsPerPixelDepthAuto(preview));
    }

    /* --------------------------------------------------------------- pixels */

    @Test
    public void autoKeepsTheBugProjectFrameIdenticalToPainterOrder()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
        boolean auto = CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false);

        int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
        int[] routed = DepthPlanePixelHarness.routed(project, comp, camera, auto, null);

        assertFalse(auto, "AUTO must stay off for this frame");
        assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, routed),
            "AUTO off must render exactly the painter frame");
    }

    @Test
    public void autoMatchesExplicitOnForCrossingPlanes()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = PerPixelDepthAutoPolicyTest.crossingScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
        boolean auto = CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false);

        assertTrue(auto, "AUTO must enable the path for crossing planes");

        int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
        int[] autoFrame = DepthPlanePixelHarness.routed(project, comp, camera, auto, null);
        int[] forcedOn = DepthPlanePixelHarness.routed(project, comp, camera, true, null);

        assertEquals(0, DepthPlanePixelHarness.pixelDiff(autoFrame, forcedOn),
            "AUTO must be pixel-identical to explicit ON when it enables the path");
        assertTrue(DepthPlanePixelHarness.pixelDiff(painter, autoFrame) > 1000,
            "the crossing frame must actually differ from painter order (otherwise AUTO gained nothing)");
    }

    @Test
    public void autoFramesMatchAtExportAndPreviewScales()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = PerPixelDepthAutoPolicyTest.crossingScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
        boolean auto = CompRenderer.perPixelDepthFrame(context, CompRenderer.DEPTH_MODE_AUTO, false);

        assertTrue(auto);

        /* Export-like and preview-proxy-like rasters both render the AUTO frame; it must equal the
         * explicit-ON frame at each scale (AUTO only chooses the path, never the pixels). */
        for (int[] size : new int[][] {{EXPORT_W, EXPORT_H}, {PREVIEW_W, PREVIEW_H}})
        {
            int[] autoFrame = DepthPlanePixelHarness.routed(project, comp, camera, auto, null,
                size[0], size[1], DepthPlanePixelHarness.STAGE);
            int[] forcedOn = DepthPlanePixelHarness.routed(project, comp, camera, true, null,
                size[0], size[1], DepthPlanePixelHarness.STAGE);

            assertEquals(0, DepthPlanePixelHarness.pixelDiff(autoFrame, forcedOn),
                "AUTO and ON must match at " + size[0] + "x" + size[1]);
        }
    }

    private static boolean depthProgramAvailable()
    {
        return CompShaders.depthPlane != null && CompShaders.depthPlane.isValid();
    }
}
