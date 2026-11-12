package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost measurement for the per-pixel depth path (task-19, AUTO/default policy input).
 *
 * <p>Analytically, a perspective 3D layer costs <b>4 full-screen passes</b> with per-pixel depth
 * (depth pre-write + opaque colour + translucent colour into the private colour/depth target, then
 * the target composited once onto the accumulation) versus <b>1</b> with whole-layer painter order.
 * On top of that the frame holds one colour+depth target the size of the render target.</p>
 *
 * <p>This test measures the real difference on the machine that runs the suite, for a parallel-plane
 * scene (the common AE 3D frame, where the depth path changes no pixel at all — see
 * {@link PerPixelDepthAutoPolicyTest}) at a fixed 960×540 render target. It writes the numbers to
 * {@code build/depth-cost-benchmark.txt} so the report can quote them without making the suite
 * depend on absolute timing. Assertions stay loose: correctness (depth == painter) is strict, the
 * timing check only rejects an absurd regression.</p>
 */
public class DepthPlaneCostBenchmarkTest
{
    private static final int BENCH_W = 960;
    private static final int BENCH_H = 540;
    private static final int REPS = 7;

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

    @Test
    public void depthPassCostIsMeasuredAndDocumented()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompProject project = DepthPlanePixelHarness.project(false);
        StringBuilder report = new StringBuilder();

        report.append("# per-pixel-depth cost benchmark (headless GL, render-only, 960x540, ")
            .append(REPS).append(" reps, glFinish sync, readPixels excluded)\n");
        report.append("# layers painter_ms depth_ms extra_ms extra_over_painter\n");

        int[] extraLayerCounts = {1, 2, 4};

        for (int extraLayers : extraLayerCounts)
        {
            CompDocument comp = DepthPlanePixelHarness.multiLayerScene(extraLayers);
            CompCamera camera = CompCamera.of(comp, 0F, 0F);
            int totalLayers = 2 + extraLayers;

            /* Warm up both paths once (shader compilation, raster caches). */
            warmUp(project, comp, camera);

            long painterNanos = medianFrameNanos(project, comp, camera, false);
            long depthNanos = medianFrameNanos(project, comp, camera, true);
            double painterMs = painterNanos / 1.0e6;
            double depthMs = depthNanos / 1.0e6;

            /* Correctness is part of the measurement: a parallel-plane scene must be identical. */
            DepthPlaneCompositor compositor = new DepthPlaneCompositor(BENCH_W, BENCH_H);

            try
            {
                int[] painter = DepthPlanePixelHarness.paint(project, comp, camera, BENCH_W, BENCH_H,
                    DepthPlanePixelHarness.STAGE);
                int[] depth = DepthPlanePixelHarness.depthAll(project, comp, camera, compositor,
                    BENCH_W, BENCH_H, DepthPlanePixelHarness.STAGE);

                assertEquals(0, DepthPlanePixelHarness.pixelDiff(painter, depth),
                    totalLayers + " layers: depth path must reproduce the painter frame");
            }
            finally
            {
                compositor.delete();
            }

            report.append(String.format(Locale.ROOT, "%d %.2f %.2f %.2f %.2fx%n",
                totalLayers, painterMs, depthMs, depthMs - painterMs,
                depthMs / Math.max(0.001, painterMs)));

            /* Sanity only: depth is more passes, but never catastrophically slower on the test GPU. */
            assertTrue(depthNanos < painterNanos * 60L,
                totalLayers + " layers: depth path took an absurd multiple of the painter path");
            assertTrue(depthNanos > 0 && painterNanos > 0);
        }

        /* Per-3D-layer extra full-screen passes: 3 (depth pre-write + opaque + translucent) + the
         * one-target composite replaces the direct pass, so the net extra is 3. */
        report.append("# analytic extra full-screen passes per depth 3D layer: +3 (4 vs 1)\n");
        report.append("# frame-level extra target: one colour+depth FBO at render resolution\n");
        report.append("# renderer: ").append(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER))
            .append(" / ").append(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR)).append("\n");

        appendCompositeStageCost(report, project);

        writeReport(report.toString());
        System.out.println(report);
    }

    /**
     * Composite-stage-only measurement: the layer content is rendered once and reused, so the number
     * shows exactly what the depth path adds for the composite step (three full-screen passes into
     * the private target plus the target-over-accumulation pass, versus one direct pass).
     */
    private static void appendCompositeStageCost(StringBuilder report, CompProject project)
    {
        report.append("\n# composite stage only (pre-rendered content; isolates the depth pass cost)\n");
        report.append("# layers painter_composite_ms depth_composite_ms extra_ms ratio\n");

        int[] extraElementCounts = {0, 1, 2, 4};

        for (int extraLayers : extraElementCounts)
        {
            CompDocument comp = DepthPlanePixelHarness.multiLayerScene(extraLayers);
            CompCamera camera = CompCamera.of(comp, 0F, 0F);
            CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
            List<CompLayer> layers = DepthPlanePixelHarness.farFirst(context,
                DepthPlanePixelHarness.layersOf(comp));
            List<GlFramebuffer> contents = new java.util.ArrayList<>();
            int totalLayers = layers.size();

            try
            {
                for (CompLayer layer : layers)
                {
                    contents.add(DepthPlanePixelHarness.content(context, camera, layer, BENCH_W, BENCH_H));
                }

                /* Warm up. */
                medianCompositeNanos(contents, false);
                medianCompositeNanos(contents, true);

                double painterMs = medianCompositeNanos(contents, false) / 1.0e6;
                double depthMs = medianCompositeNanos(contents, true) / 1.0e6;

                report.append(String.format(Locale.ROOT, "%d %.3f %.3f %.3f %.2fx%n",
                    totalLayers, painterMs, depthMs, depthMs - painterMs,
                    depthMs / Math.max(0.001, painterMs)));
            }
            finally
            {
                for (GlFramebuffer content : contents)
                {
                    content.delete();
                }
            }
        }
    }

    private static long medianCompositeNanos(List<GlFramebuffer> contents, boolean depth)
    {
        long[] samples = new long[REPS];

        for (int i = 0; i < REPS; i++)
        {
            DepthPlaneCompositor compositor = depth ? new DepthPlaneCompositor(BENCH_W, BENCH_H) : null;
            List<GlFramebuffer> owned = new java.util.ArrayList<>();
            GlFramebuffer accumulation = new GlFramebuffer(BENCH_W, BENCH_H);

            owned.add(accumulation);
            accumulation.begin(DepthPlanePixelHarness.STAGE);

            long start = System.nanoTime();

            for (GlFramebuffer content : contents)
            {
                if (depth)
                {
                    /* Identity camera/world: this measures pass cost, not projection. */
                    accumulation = compositor.composite(content, new org.joml.Matrix4f(),
                        CompCamera.identity(BENCH_W, BENCH_H), accumulation,
                        dev.bbsae.comp.CompLayer.BLEND_NORMAL, 1F);
                }
                else
                {
                    accumulation = PassRenderer.draw(content, accumulation, null,
                        dev.bbsae.comp.CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);
                }

                owned.add(accumulation);
            }

            org.lwjgl.opengl.GL11.glFinish();
            samples[i] = System.nanoTime() - start;

            for (GlFramebuffer framebuffer : owned)
            {
                if (framebuffer != null)
                {
                    framebuffer.delete();
                }
            }

            if (compositor != null)
            {
                compositor.delete();
            }
        }

        Arrays.sort(samples);

        /* The minimum is the least noisy sample under a shared build machine. */
        return samples[0];
    }

    private static void warmUp(CompProject project, CompDocument comp, CompCamera camera)
    {
        DepthPlanePixelHarness.paint(project, comp, camera, BENCH_W, BENCH_H, DepthPlanePixelHarness.STAGE);

        DepthPlaneCompositor compositor = new DepthPlaneCompositor(BENCH_W, BENCH_H);

        try
        {
            DepthPlanePixelHarness.depthAll(project, comp, camera, compositor, BENCH_W, BENCH_H,
                DepthPlanePixelHarness.STAGE);
        }
        finally
        {
            compositor.delete();
        }
    }

    private static long medianFrameNanos(CompProject project, CompDocument comp, CompCamera camera,
                                         boolean depth)
    {
        long[] samples = new long[REPS];

        for (int i = 0; i < REPS; i++)
        {
            DepthPlaneCompositor compositor = depth ? new DepthPlaneCompositor(BENCH_W, BENCH_H) : null;
            long start = System.nanoTime();
            GlFramebuffer frame = DepthPlanePixelHarness.renderFrameFbo(project, comp, camera, depth,
                compositor, BENCH_W, BENCH_H, DepthPlanePixelHarness.STAGE);

            /* The frame is async until the pipeline syncs: glFinish makes the measurement real. */
            org.lwjgl.opengl.GL11.glFinish();
            samples[i] = System.nanoTime() - start;

            frame.delete();

            if (compositor != null)
            {
                compositor.delete();
            }
        }

        Arrays.sort(samples);

        /* The minimum is the least noisy sample under a shared build machine. */
        return samples[0];
    }

    private static void writeReport(String text)
    {
        File directory = new File("build");
        directory.mkdirs();

        try (Writer writer = Files.newBufferedWriter(new File("build/depth-cost-benchmark.txt").toPath(),
            StandardCharsets.UTF_8))
        {
            writer.write(text);
        }
        catch (IOException ignored)
        {
            /* The printed report is enough for a human; the file is for the docs step. */
        }
    }
}
