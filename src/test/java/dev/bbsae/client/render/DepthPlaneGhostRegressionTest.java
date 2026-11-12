package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pixel regression for the main-composition per-pixel depth path (user requirement 3).
 *
 * <p>The depth-plane compositor keeps one colour + depth target per composition render. The depth
 * buffer has to survive from layer to layer, but the colour attachment must be reset before every
 * layer: the target is reused across layers, frames and pooled renders, so any stale colour is
 * composited onto the accumulation a second (or nth) time. That is what the "camera preview
 * duplicated star/polygon layers and stripes" ghost was: the colour attachment was never actually
 * cleared, so every frame left its picture in the reused target and each new frame composited all
 * older pictures plus itself.</p>
 *
 * <p>All frames here are compared against the whole-layer painter path with <b>zero tolerance</b>:
 * per-pixel depth only resolves crossings, it may not change what a flat layer looks like.</p>
 */
public class DepthPlaneGhostRegressionTest
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

    /**
     * The exact repro: the user project composed twice, once through the painter path and once
     * through the per-pixel depth path, at the ghost-reproduction camera pose.
     */
    @Test
    public void theBugProjectFrameMatchesTheDepthOffFrame()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(DepthPlanePixelHarness.W,
            DepthPlanePixelHarness.H);

        try
        {
            int[] painter = DepthPlanePixelHarness.paint(
                DepthPlanePixelHarness.project(false), comp, camera);
            int[] depth = DepthPlanePixelHarness.depthAll(
                DepthPlanePixelHarness.project(false), comp, camera, compositor);

            DepthPlanePixelHarness.assertFramesEqual(painter, depth, 0, "bug-project-single-frame");
        }
        finally
        {
            compositor.delete();
        }
    }

    /**
     * The ghost was temporal: the target is pooled across composition renders, and the camera is
     * moved every frame while the user works. Every frame must still be exactly its own picture.
     * Before the fix the colour attachment was never cleared, so frame N contained the silhouettes
     * of frames N-1..0 as well (the "duplicated star/polygon + stripes" screenshot).
     */
    @Test
    public void movingCameraFramesDoNotAccumulateGhosts()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompLayer cameraLayer = comp.getLayer("camera");
        float[] yaws = {-25.73563F, -18F, -10F};
        DepthPlaneCompositor compositor = null;

        try
        {
            for (int frame = 0; frame < yaws.length; frame++)
            {
                cameraLayer.rotationY = yaws[frame];

                CompCamera camera = CompCamera.of(comp, 0F, 0F);

                /* Pooled across renders, exactly like the main composition path. */
                compositor = DepthPlaneCompositor.acquire(DepthPlanePixelHarness.W,
                    DepthPlanePixelHarness.H);

                int[] painter = DepthPlanePixelHarness.paint(
                    DepthPlanePixelHarness.project(false), comp, camera);
                int[] depth = DepthPlanePixelHarness.depthAll(
                    DepthPlanePixelHarness.project(false), comp, camera, compositor);

                DepthPlanePixelHarness.assertFramesEqual(painter, depth, 0,
                    "bug-project-moving-frame-" + frame);

                DepthPlaneCompositor.release(compositor);
                compositor = null;
            }
        }
        finally
        {
            if (compositor != null)
            {
                DepthPlaneCompositor.release(compositor);
            }

            DepthPlaneCompositor.deleteAll();
        }
    }

    /**
     * Minimal root-cause pin, no shape rasterizer involved: a full red frame is composed, the
     * compositor goes back to the pool, and the next composition (a small blue square on a
     * transparent frame) must not contain a single red pixel. Before the fix the reused colour
     * attachment still held the red frame and it was composited again under the blue square.
     */
    @Test
    public void aReusedTargetDoesNotKeepThePreviousCompositionsPixels()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompCamera camera = CompCamera.identity(DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);
        Matrix4f world = new Matrix4f();
        GlFramebuffer first = DepthPlanePixelHarness.solid(0xFFFF0000);
        DepthPlaneCompositor compositor = DepthPlaneCompositor.acquire(
            DepthPlanePixelHarness.W, DepthPlanePixelHarness.H);

        try
        {
            GlFramebuffer accumulation = new GlFramebuffer(DepthPlanePixelHarness.W,
                DepthPlanePixelHarness.H);

            accumulation.begin(DepthPlanePixelHarness.STAGE);
            accumulation = compositor.composite(first, world, camera, accumulation,
                CompLayer.BLEND_NORMAL, 1F);

            int centerRed = accumulation.readTopDown()[DepthPlanePixelHarness.W / 2
                + DepthPlanePixelHarness.H / 2 * DepthPlanePixelHarness.W];

            assertEquals(0xFFFF0000, centerRed, "the first composition must render its frame");
            accumulation.delete();
        }
        finally
        {
            first.delete();
            DepthPlaneCompositor.release(compositor);
        }

        GlFramebuffer square = DepthPlanePixelHarness.rectangle(0xFF0000FF,
            DepthPlanePixelHarness.W / 2 - 20, DepthPlanePixelHarness.H / 2 - 20,
            DepthPlanePixelHarness.W / 2 + 20, DepthPlanePixelHarness.H / 2 + 20);

        try
        {
            GlFramebuffer accumulator = new GlFramebuffer(DepthPlanePixelHarness.W,
                DepthPlanePixelHarness.H);

            accumulator.begin(DepthPlanePixelHarness.STAGE);
            accumulator = compositor.composite(square, world, camera, accumulator,
                CompLayer.BLEND_NORMAL, 1F);

            int[] pixels = accumulator.readTopDown();

            /* Everything outside the small blue square must be the fresh (black) stage. */
            assertEquals(DepthPlanePixelHarness.STAGE, pixels[0],
                "left edge must not retain the previous frame");
            assertEquals(DepthPlanePixelHarness.STAGE, pixels[DepthPlanePixelHarness.W - 1],
                "right edge must not retain the previous frame");
            assertEquals(0xFF0000FF, pixels[DepthPlanePixelHarness.W / 2
                + DepthPlanePixelHarness.H / 2 * DepthPlanePixelHarness.W],
                "the square itself must be drawn");
            accumulator.delete();
        }
        finally
        {
            square.delete();
        }
    }

    /**
     * Two non-overlapping translucent layers: per-pixel depth must composite each visible pixel
     * exactly once, matching whole-layer order. Before the fix the second layer's pass re-composited
     * the first layer's stale pixels from the target, doubling their opacity (edge "stripes").
     */
    @Test
    public void translucentLayersAreCompositedExactlyOnce()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        int w = DepthPlanePixelHarness.W;
        int h = DepthPlanePixelHarness.H;

        CompCamera camera = CompCamera.identity(w, h);
        /* alpha 0x60 < 0.5: translucent, so it must blend instead of writing depth. */
        GlFramebuffer far = DepthPlanePixelHarness.rectangle(0x60FF0000, 0, 0, w / 2, h);
        GlFramebuffer near = DepthPlanePixelHarness.rectangle(0x6000FF00, w / 2, 0, w, h);
        Matrix4f farWorld = new Matrix4f().translate(0F, 0F, 0F);
        Matrix4f nearWorld = new Matrix4f().translate(0F, 0F, 80F);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(w, h);

        try
        {
            /* Painter path: far, then near (their painted order here is also far to near). */
            GlFramebuffer painter = new GlFramebuffer(w, h);

            painter.begin(DepthPlanePixelHarness.STAGE);
            painter = PassRenderer.draw(far, painter, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);
            painter = PassRenderer.draw(near, painter, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);

            /* Depth path: same order, one shared compositor. */
            GlFramebuffer depth = new GlFramebuffer(w, h);

            depth.begin(DepthPlanePixelHarness.STAGE);
            depth = compositor.composite(far, farWorld, camera, depth, CompLayer.BLEND_NORMAL, 1F);
            depth = compositor.composite(near, nearWorld, camera, depth, CompLayer.BLEND_NORMAL, 1F);

            int[] expected = painter.readTopDown();
            int[] actual = depth.readTopDown();

            DepthPlanePixelHarness.assertFramesEqual(expected, actual, 2, "translucent-halves");

            painter.delete();
            depth.delete();
        }
        finally
        {
            far.delete();
            near.delete();
            compositor.delete();
        }
    }
}
