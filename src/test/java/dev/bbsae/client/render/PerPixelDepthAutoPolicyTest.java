package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
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
     * Prototype of the AUTO decision: does any pair of perspective layers overlap on screen with
     * non-parallel planes? Pure math over {@link CompScene} + {@link CompCamera}, no GL needed for
     * the decision itself.
     */
    static boolean autoNeedsPerPixelDepth(CompRenderContext context)
    {
        List<CompLayer> perspective = new ArrayList<>();

        for (CompLayer layer : DepthPlanePixelHarness.layersOf(context.comp))
        {
            if (layer.visible && CompScene.usePerspective(context, layer))
            {
                perspective.add(layer);
            }
        }

        if (perspective.size() < 2)
        {
            return false;
        }

        CompCamera camera = CompScene.camera(context);

        for (int i = 0; i < perspective.size(); i++)
        {
            for (int j = i + 1; j < perspective.size(); j++)
            {
                CompLayer a = perspective.get(i);
                CompLayer b = perspective.get(j);

                if (planesAreParallel(context, a, b))
                {
                    continue;
                }

                if (projectedBoundsOverlap(context, camera, a, b))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean planesAreParallel(CompRenderContext context, CompLayer a, CompLayer b)
    {
        Vector3f normalA = planeNormal(context, a);
        Vector3f normalB = planeNormal(context, b);

        return Math.abs(normalA.dot(normalB)) >= 1F - NORMAL_EPSILON;
    }

    private static Vector3f planeNormal(CompRenderContext context, CompLayer layer)
    {
        Vector3f normal = new Vector3f(0F, 0F, 1F);

        CompScene.world(context, layer).transformDirection(normal);

        return normal.normalize();
    }

    private static boolean projectedBoundsOverlap(CompRenderContext context, CompCamera camera,
                                                  CompLayer a, CompLayer b)
    {
        float[] boundsA = projectedBounds(context, camera, a);
        float[] boundsB = projectedBounds(context, camera, b);

        if (boundsA == null || boundsB == null)
        {
            return false;
        }

        return boundsA[0] <= boundsB[2] && boundsB[0] <= boundsA[2]
            && boundsA[1] <= boundsB[3] && boundsB[1] <= boundsA[3];
    }

    /** Projected screen AABB {minX, minY, maxX, maxY} of the layer's content rectangle, or null. */
    private static float[] projectedBounds(CompRenderContext context, CompCamera camera, CompLayer layer)
    {
        float width = Math.max(1F, layer.width);
        float height = Math.max(1F, layer.height);
        float x = -width * layer.anchorX;
        float y = -height * layer.anchorY;
        Matrix4f world = CompScene.world(context, layer);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int corner = 0; corner < 4; corner++)
        {
            Vector3f point = new Vector3f(
                (corner == 0 || corner == 3) ? x : x + width,
                (corner == 0 || corner == 1) ? y : y + height,
                0F);

            world.transformPosition(point);

            Vector2f projected = camera.project(point, context.width, context.height);

            if (projected == null)
            {
                return null;
            }

            minX = Math.min(minX, projected.x);
            minY = Math.min(minY, projected.y);
            maxX = Math.max(maxX, projected.x);
            maxY = Math.max(maxY, projected.y);
        }

        return new float[] {minX, minY, maxX, maxY};
    }

    /* ---------------------------------------------------------------- scenes */

    /**
     * Two full-composition rectangle layers rotated around Y in opposite directions, so their
     * planes cross inside the visible frame. Painter order cannot represent the crossing; the
     * per-pixel depth path is exactly for this.
     */
    private static CompDocument crossingScene()
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
