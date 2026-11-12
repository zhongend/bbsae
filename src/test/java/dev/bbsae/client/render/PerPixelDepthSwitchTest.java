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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderer-level acceptance for the {@code CompPrefs.perPixelDepth} switch (Settings → 显示与线框).
 *
 * <p>The document switch has to be a pure look change: with the same scene and camera, the frame
 * rendered through the per-pixel depth path must be pixel-identical to the frame rendered with the
 * switch off. These tests drive the same routing {@code CompRenderer} uses:</p>
 *
 * <pre>
 * switch off            -> every layer: whole-layer painter order
 * switch on + spatial   -> shared {@link DepthPlaneCompositor}; only {@link CompScene#usePerspective}
 *                          layers go through it, the rest keep painter order
 * </pre>
 *
 * <p>{@code CompRenderer} itself cannot be instantiated headless (it needs {@code MinecraftClient}
 * and a {@code Batcher2D}); {@link DepthPlanePixelHarness#routed} reproduces its branch and the
 * content/composite primitives exactly. The switch value is explicit here because
 * {@code CompPrefs} needs FabricLoader.</p>
 */
public class PerPixelDepthSwitchTest
{
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

    /** Switch on and switch off must produce the same picture for the user's repro project. */
    @Test
    public void switchOnMatchesSwitchOffForTheBugProject()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);

        /* The renderer only acquires the compositor when the composition is spatial. */
        assertTrue(CompScene.spatial(context), "the ____bug frame must be spatial");
        for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
        {
            assertTrue(CompScene.usePerspective(context, layer),
                "both 3D shape layers must be perspective-projected");
        }

        int[] off = DepthPlanePixelHarness.routed(project, comp, camera, false, null);
        int[] on = DepthPlanePixelHarness.routed(project, comp, camera, true, null);
        int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);

        assertEquals(0, pixelDiff(painter, off), "switch off must be the painter path");
        assertEquals(0, pixelDiff(painter, on), "switch on must not change the frame");
        assertEquals(0, pixelDiff(off, on), "toggling the switch must not change a single pixel");
    }

    /**
     * AE 3D logic: a 2D layer stays screen-attached. With the switch on, only the 3D layer is
     * routed through per-pixel depth; the frame must still equal the all-painter frame.
     */
    @Test
    public void mixed2DAnd3DLayersFollowAEProjection()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.twoDPlusThreeDScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);

        assertFalse(CompScene.usePerspective(context, comp.getLayer("flat2d")),
            "AE logic: a 2D layer must not be perspective-projected");
        assertTrue(CompScene.usePerspective(context, comp.getLayer("star")));
        assertTrue(CompScene.usePerspective(context, comp.getLayer("polygon")));

        int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
        int[] on = DepthPlanePixelHarness.routed(project, comp, camera, true, null);

        assertEquals(0, pixelDiff(painter, on),
            "the mixed depth(painter) routing must match the painter frame exactly");
    }

    /**
     * Legacy developer logic (pre-0.92): every layer follows the camera, so with the switch on the
     * 2D layer is perspective-projected and goes through the depth path as well. The frame still
     * has to match whole-layer order.
     */
    @Test
    public void legacy3DLogicProjectsEveryLayerThroughTheDepthPath()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.twoDPlusThreeDScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject legacy = DepthPlanePixelHarness.project(true);
        CompRenderContext context = DepthPlanePixelHarness.context(legacy, comp);

        assertTrue(CompScene.spatial(context));
        assertTrue(CompScene.usePerspective(context, comp.getLayer("flat2d")),
            "legacy logic: every layer follows the camera");

        int[] painter = DepthPlanePixelHarness.paint(legacy, comp, camera);
        int[] on = DepthPlanePixelHarness.routed(legacy, comp, camera, true, null);

        assertEquals(0, pixelDiff(painter, on),
            "legacy-mode depth path must match the painter frame exactly");
    }

    /**
     * A composition whose layers are all 2D: the switch acquires the compositor only when the frame
     * is spatial, but no layer qualifies, so turning it on is a no-op (and must not corrupt the
     * frame). This is the "everyday 2D comp" path.
     */
    @Test
    public void switchOnIsANoOpForAnAll2DComposition()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();

        for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
        {
            layer.threeD = false;
        }

        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);

        assertTrue(CompScene.spatial(context), "the tilted camera still marks the frame spatial");
        for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
        {
            assertFalse(CompScene.usePerspective(context, layer),
                "an all-2D frame has no layer for the depth path to take");
        }

        int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
        int[] on = DepthPlanePixelHarness.routed(project, comp, camera, true, null);

        assertEquals(0, pixelDiff(painter, on));
    }

    /** A translucent 3D layer (opacity 0.5) must composite exactly like the painter path. */
    @Test
    public void translucentLayerOpacityMatchesThePainterPath()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();

        comp.getLayer("star").opacity = 0.5F;
        comp.getLayer("polygon").opacity = 0.75F;

        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);

        int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
        int[] on = DepthPlanePixelHarness.routed(project, comp, camera, true, null);

        assertEquals(0, pixelDiff(painter, on),
            "layer opacity must be applied exactly once by the final composite");
    }

    /**
     * The pooled compositor is reused across composition renders (that is how the ghost leaked
     * between frames). Two different camera poses rendered through the same acquired instance —
     * with a release/re-acquire in between — must both equal their painter frames, and the pool
     * must actually hand the same instance back.
     */
    @Test
    public void pooledCompositorStaysCorrectAcrossCompositionRenders()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompLayer cameraLayer = comp.getLayer("camera");
        CompProject project = DepthPlanePixelHarness.project(false);

        DepthPlaneCompositor first = DepthPlanePixelHarness.acquire(
            DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

        DepthPlanePixelHarness.release(first);
        first = DepthPlanePixelHarness.acquire(DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

        DepthPlaneCompositor second = null;
        DepthPlaneCompositor pooledInstance = first;

        try
        {
            cameraLayer.rotationY = -25.73563F;

            CompCamera cameraA = CompCamera.of(comp, 0F, 0F);

            assertEquals(0, pixelDiff(
                DepthPlanePixelHarness.paint(project, comp, cameraA),
                DepthPlanePixelHarness.routed(project, comp, cameraA, true, first)),
                "first pose through the pooled compositor");

            DepthPlanePixelHarness.release(first);
            first = null;

            second = DepthPlanePixelHarness.acquire(DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

            /* The pool must hand the same target back, otherwise "pooled reuse" is not covered. */
            assertSame(pooledInstance, second, "the released target must be reused from the pool");

            cameraLayer.rotationY = -8F;

            CompCamera cameraB = CompCamera.of(comp, 0F, 0F);

            assertEquals(0, pixelDiff(
                DepthPlanePixelHarness.paint(project, comp, cameraB),
                DepthPlanePixelHarness.routed(project, comp, cameraB, true, second)),
                "second pose through the reacquired compositor (stale depth/colour would show)");
        }
        finally
        {
            if (first != null)
            {
                DepthPlanePixelHarness.release(first);
            }

            if (second != null)
            {
                DepthPlanePixelHarness.release(second);
            }

            DepthPlaneCompositor.deleteAll();
        }
    }

    /**
     * The renderer's gate is {@code spatial && (perPixelDepth || -Dbbsae.debugForceDepthPlane)} and
     * the per-layer branch is {@code spatialDepth != null && usePerspective && !-Dbbsae.debugNoDepthPlane}.
     * The debug properties must stay drop-in replacements: forcing the path on yields the same
     * pixels as the preference, and forcing it off yields the painter frame.
     */
    @Test
    public void debugForceDepthPlaneSelectsTheSamePixelsAsThePreference()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
        boolean spatial = CompScene.spatial(context);
        boolean preference = false;

        try
        {
            System.setProperty("bbsae.debugForceDepthPlane", "true");

            boolean forced = preference || Boolean.getBoolean("bbsae.debugForceDepthPlane");

            assertTrue(spatial && forced, "the debug property must request the same depth path");

            int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
            int[] forcedFrame = DepthPlanePixelHarness.routed(project, comp, camera, forced, null);

            assertEquals(0, pixelDiff(painter, forcedFrame), "forced depth path must match painter");
        }
        finally
        {
            System.clearProperty("bbsae.debugForceDepthPlane");
        }

        boolean disabled = !Boolean.getBoolean("bbsae.debugNoDepthPlane");

        assertTrue(disabled, "debugNoDepthPlane must keep its default (depth branch enabled)");
    }

    private static int pixelDiff(int[] a, int[] b)
    {
        if (a.length != b.length)
        {
            return Integer.MAX_VALUE;
        }

        int different = 0;

        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
            {
                different += 1;
            }
        }

        return different;
    }

    private static boolean depthProgramAvailable()
    {
        return CompShaders.depthPlane != null && CompShaders.depthPlane.isValid();
    }
}
