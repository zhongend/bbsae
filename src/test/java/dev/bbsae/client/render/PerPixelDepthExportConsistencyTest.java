package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export/preview consistency of the {@code CompPrefs.perPixelDepth} switch (task-19).
 *
 * <p>The export path ({@code CompExportManager} → {@code CompRenderer.renderForExport} →
 * {@code renderFrame} → {@code renderToTexture(..., preview=false, scale=…, transparentStage)})
 * and the preview path ({@code renderCached}/{@code renderFrameCached}) both go through the same
 * {@code renderCompToFbo}. The depth decision itself uses only {@link CompRenderer#perPixelDepthFrame}
 * and {@link CompRenderer#perPixelDepthLayer} — frame geometry plus the preference/debug switch,
 * never {@code preview}, {@code scale} or the stage colour — so the encoder cannot render a
 * different depth path than the editor.</p>
 *
 * <p>What can still differ between export (scale 1) and preview (proxy scale) is ordinary
 * rasterisation: a 0.5-resolution raster decides edge coverage at half the sample rate. The test
 * therefore asserts the switch changes <b>no pixel at either scale</b> (depth-on == depth-off with
 * zero tolerance at export-like and preview-proxy-like resolutions), which is the strongest
 * resolution-independent statement that can be made headless. A full export cannot be instantiated
 * here because {@code CompRenderer} needs {@code MinecraftClient}/{@code Batcher2D}.</p>
 */
public class PerPixelDepthExportConsistencyTest
{
    /** Export-like target (a 2:1 downscale of 1920×1080; NDC math is resolution independent). */
    private static final int EXPORT_W = 768;
    private static final int EXPORT_H = 432;
    /** Preview proxy target at 0.5 of the export-like target (the renderer's proxy is quantised). */
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
    }

    @AfterEach
    public void clearTestState()
    {
        DepthPlanePixelHarness.clearPlates();
        System.clearProperty("bbsae.debugForceDepthPlane");
        System.clearProperty("bbsae.debugNoDepthPlane");
    }

    /**
     * The switch must be a no-op at both the export resolution and the preview proxy resolution:
     * a frame the encoder writes and a frame the editor shows both keep the exact depth-off pixels.
     */
    @Test
    public void depthOnChangesNoPixelAtExportOrPreviewScale()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);

        assertScalePair(project, comp, camera, EXPORT_W, EXPORT_H, "export-scale-768x432");
        assertScalePair(project, comp, camera, PREVIEW_W, PREVIEW_H, "preview-scale-384x216");
    }

    private static void assertScalePair(CompProject project, CompDocument comp, CompCamera camera,
                                        int width, int height, String what)
    {
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(width, height);

        try
        {
            int[] painter = DepthPlanePixelHarness.paint(project, comp, camera, width, height,
                DepthPlanePixelHarness.STAGE);
            int[] depth = DepthPlanePixelHarness.depthAll(project, comp, camera, compositor,
                width, height, DepthPlanePixelHarness.STAGE);
            int[] routedOn = DepthPlanePixelHarness.routed(project, comp, camera, true, null,
                width, height, DepthPlanePixelHarness.STAGE);

            assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, depth),
                what + ": the depth path must reproduce the depth-off frame exactly");
            assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, routedOn),
                what + ": the renderer's switch-on routing must reproduce the depth-off frame exactly");
        }
        finally
        {
            compositor.delete();
        }
    }

    /** Alpha export: the stage is transparent, the depth path must still match painter order. */
    @Test
    public void alphaExportWithTransparentStageMatchesPainterOrder()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        CompProject project = DepthPlanePixelHarness.project(false);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(DepthPlanePixelHarness.W,
            DepthPlanePixelHarness.H);

        try
        {
            int[] painter = DepthPlanePixelHarness.paint(project, comp, camera,
                DepthPlanePixelHarness.W, DepthPlanePixelHarness.H,
                DepthPlanePixelHarness.TRANSPARENT_STAGE);
            int[] depth = DepthPlanePixelHarness.depthAll(project, comp, camera, compositor,
                DepthPlanePixelHarness.W, DepthPlanePixelHarness.H,
                DepthPlanePixelHarness.TRANSPARENT_STAGE);
            int[] routedOn = DepthPlanePixelHarness.routed(project, comp, camera, true, null,
                DepthPlanePixelHarness.W, DepthPlanePixelHarness.H,
                DepthPlanePixelHarness.TRANSPARENT_STAGE);

            assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, depth),
                "transparent-stage (alpha export) frame must be identical with the depth path");
            assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, routedOn),
                "transparent-stage routing must be identical with the switch on");
        }
        finally
        {
            compositor.delete();
        }
    }

    /**
     * The gate helpers must not depend on the editor-only parameters: preview vs export
     * ({@code preview} flag) and full vs proxy resolution ({@code scale}) produce the same
     * decision for the same composition.
     */
    @Test
    public void exportAndPreviewShareTheSameDepthGate()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext preview = DepthPlanePixelHarness.context(project, comp);
        CompRenderContext exporter = DepthPlanePixelHarness.context(project, comp);

        preview.preview = true;
        preview.scale = 0.5F;
        exporter.preview = false;
        exporter.scale = 1F;

        /* Headless: the preference store is unreachable, so the switch reads as off; the debug
         * override is the only way to request the path here — and it must behave identically for
         * both contexts. */
        assertFalse(CompRenderer.perPixelDepthRequested(),
            "without the preference (or the debug override) the path stays off");
        assertEquals(CompRenderer.perPixelDepthFrame(preview), CompRenderer.perPixelDepthFrame(exporter),
            "the frame gate must ignore preview/scale");

        try
        {
            System.setProperty("bbsae.debugForceDepthPlane", "true");

            assertTrue(CompRenderer.perPixelDepthRequested(), "debug force must request the path");
            assertTrue(CompRenderer.perPixelDepthFrame(preview), "spatial frame with the switch on");
            assertTrue(CompRenderer.perPixelDepthFrame(exporter), "export sees the same gate");
            assertEquals(CompRenderer.perPixelDepthFrame(preview), CompRenderer.perPixelDepthFrame(exporter),
                "the frame gate must ignore preview/scale");
        }
        finally
        {
            System.clearProperty("bbsae.debugForceDepthPlane");
        }

        /* Per-layer gate: only perspective layers, and only while a shared depth target exists. */
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(DepthPlanePixelHarness.W,
            DepthPlanePixelHarness.H);

        try
        {
            preview.spatialDepth = compositor;
            exporter.spatialDepth = compositor;

            for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
            {
                assertEquals(CompRenderer.perPixelDepthLayer(preview, layer),
                    CompRenderer.perPixelDepthLayer(exporter, layer),
                    "the layer gate must ignore preview/scale");
                assertTrue(CompRenderer.perPixelDepthLayer(preview, layer),
                    "3D shape layers are perspective-projected");
            }

            try
            {
                System.setProperty("bbsae.debugNoDepthPlane", "true");

                for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
                {
                    assertFalse(CompRenderer.perPixelDepthLayer(preview, layer),
                        "the debug kill switch must disable the layer gate");
                }
            }
            finally
            {
                System.clearProperty("bbsae.debugNoDepthPlane");
            }

            /* The renderer's per-layer gate also requires the frame gate to have acquired a
             * target; a composition that never acquired one cannot route a layer through it. */
            exporter.spatialDepth = null;

            for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
            {
                assertFalse(CompRenderer.perPixelDepthLayer(exporter, layer),
                    "no shared depth target means no depth layer routing");
            }
        }
        finally
        {
            preview.spatialDepth = null;
            exporter.spatialDepth = null;
            compositor.delete();
        }
    }

    /**
     * Combined regression: depth-on with a precomp plate, a translucent layer and several 3D
     * layers. Parallel planes keep whole-layer order exact, so the depth path must reproduce the
     * painter frame pixel for pixel.
     */
    @Test
    public void precompTranslucentAndMultiple3dLayersCombineExactly()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        /* Nested composition of the precomp layer, rendered exactly like renderCompToFbo would. */
        CompDocument nested = new CompDocument();

        nested.id = "nested";
        nested.name = "Nested";
        nested.width = 1920;
        nested.height = 1080;
        nested.fps = 60F;
        nested.duration = 12F;
        DepthPlanePixelHarness.addRect(nested, "n-red", 700F, 460F, 0F, 700F, 300F,
            0xFFE53935, 1F, false);
        DepthPlanePixelHarness.addRect(nested, "n-green", 1250F, 640F, 0F, 700F, 300F,
            0xFF43A047, 1F, false);

        CompCamera nestedCamera = CompCamera.identity(1920F, 1080F);
        GlFramebuffer plate = DepthPlanePixelHarness.renderNestedPlate(
            new CompProject(), nested, nestedCamera, DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

        try
        {
            CompDocument comp = DepthPlanePixelHarness.bugScene();

            CompLayer precomp = new CompLayer("precomp", "Precomp", "precomp");

            precomp.visible = true;
            precomp.in = 0F;
            precomp.out = 12F;
            precomp.threeD = true;
            precomp.x = 1100F;
            precomp.y = 560F;
            precomp.z = 150F;
            precomp.width = 900F;
            precomp.height = 520F;
            precomp.anchorX = 0.5F;
            precomp.anchorY = 0.5F;
            precomp.opacity = 1F;
            precomp.blendMode = CompLayer.BLEND_NORMAL;
            precomp.precompId = "nested";
            comp.addLayer(precomp);
            DepthPlanePixelHarness.setPlate(precomp, plate);

            /* A translucent 3D layer in front of the plate and behind the near star. */
            DepthPlanePixelHarness.addRect(comp, "glass", 1200F, 620F, 300F, 1000F, 700F,
                0x80FF00FF, 0.5F, true);
            /* One more opaque 3D layer, so at least four layers are combined. */
            DepthPlanePixelHarness.addRect(comp, "near-plate", 800F, 700F, 500F, 500F, 300F,
                0xFF8E24AA, 1F, true);

            CompCamera camera = CompCamera.of(comp, 0F, 0F);
            CompProject project = DepthPlanePixelHarness.project(false);
            DepthPlaneCompositor compositor = new DepthPlaneCompositor(DepthPlanePixelHarness.W,
                DepthPlanePixelHarness.H);

            try
            {
                int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
                int[] depth = DepthPlanePixelHarness.depthAll(project, comp, camera, compositor);
                int[] routedOn = DepthPlanePixelHarness.routed(project, comp, camera, true, null);

                assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, depth),
                    "precomp + translucent + multiple 3D layers must composite identically");
                assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, routedOn),
                    "the renderer's routing must composite the combined scene identically");
            }
            finally
            {
                compositor.delete();
            }
        }
        finally
        {
            DepthPlanePixelHarness.clearPlates();
            plate.delete();
        }
    }

    /**
     * Extreme near/far ratios: {@code DEPTH_PLANE} writes {@code gl_FragDepth} and the colour pass
     * compares with {@code GL_EQUAL}. Both passes compute the value from identical uniforms, so a
     * large depth range must not make the equality pass drop pixels — otherwise a layer would
     * disappear only with per-pixel depth on. The frame must still equal painter order exactly.
     */
    @Test
    public void extremeNearFarKeepsTheEqualityPassExact()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        float[][] ranges = {{0.1F, 1.0e6F}, {10F, 1.0e7F}, {0.1F, 5.0e6F}};

        for (float[] range : ranges)
        {
            CompDocument comp = DepthPlanePixelHarness.bugScene();
            CompLayer cameraLayer = comp.getLayer("camera");

            cameraLayer.cameraNear = range[0];
            cameraLayer.cameraFar = range[1];

            CompCamera camera = CompCamera.of(comp, 0F, 0F);
            CompProject project = DepthPlanePixelHarness.project(false);
            DepthPlaneCompositor compositor = new DepthPlaneCompositor(DepthPlanePixelHarness.W,
                DepthPlanePixelHarness.H);

            try
            {
                int[] painter = DepthPlanePixelHarness.paint(project, comp, camera);
                int[] depth = DepthPlanePixelHarness.depthAll(project, comp, camera, compositor);

                assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, depth),
                    "near=" + range[0] + " far=" + range[1]
                        + ": GL_EQUAL must not drop fragments at extreme depth ranges");
            }
            finally
            {
                compositor.delete();
            }
        }
    }

    private static boolean depthProgramAvailable()
    {
        return CompShaders.depthPlane != null && CompShaders.depthPlane.isValid();
    }
}
