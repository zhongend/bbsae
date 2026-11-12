package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory-budget and pre-render correctness tests (task-34).
 *
 * <ul>
 *   <li>{@link FboPool}: idle VRAM is bounded by <b>bytes</b>, not only by a target count, and
 *       {@code trimTo} honours an explicit budget (4K targets are ~16x a 1080p one);</li>
 *   <li>{@link CompRenderContext#targetEdge}: the layer-source raster cap follows the consumer
 *       (pre-render 1280, encoder composition edge, panel preview 1024);</li>
 *   <li>{@link CompPrecache}: cache identity survives id truncation and 4K pre-renders are bounded
 *       to 1280 px; {@code residentBytes()} reports a clean slate after {@code clearAll};</li>
 *   <li>{@link CompFrameDisk}: the alpha stage is part of the file identity.</li>
 * </ul>
 */
public class RenderMemoryBudgetTest
{
    private static final String BUDGET_PROPERTY = "bbsae.fboBudgetBytes";

    @BeforeAll
    public static void createContext()
    {
        Assumptions.assumeTrue(true);
        DepthPlanePixelHarness.initGl();
        FboPool.clear();
        CompPrecache.clearAll();
    }

    @AfterEach
    public void drainGlErrors()
    {
        System.clearProperty(BUDGET_PROPERTY);
        FboPool.clear();

        while (GL11.glGetError() != GL11.GL_NO_ERROR)
        {
            /* drain */
        }
    }

    @AfterAll
    public static void destroyContext()
    {
        System.clearProperty(BUDGET_PROPERTY);
        FboPool.clear();
        CompPrecache.clearAll();
        DepthPlanePixelHarness.shutdownGl();
    }

    /* ------------------------------------------------------------- FboPool */

    /** Releasing many large targets must stay under the byte budget, not just under MAX_IDLE. */
    @Test
    public void fboPoolHonoursTheByteBudget()
    {
        System.setProperty(BUDGET_PROPERTY, Long.toString(2L * 1024 * 1024));

        int width = 512;
        int height = 512;
        int count = 40;

        for (int i = 0; i < count; i++)
        {
            GlFramebuffer framebuffer = FboPool.acquire(width, height);

            FboPool.release(framebuffer);
        }

        long budget = FboPool.budgetBytes();

        assertTrue(FboPool.bytes() <= budget,
            "idle bytes " + FboPool.bytes() + " must respect the " + budget + " byte budget");
        assertTrue(FboPool.idleCount() <= 32, "the count cap still applies as a second bound");
    }

    @Test
    public void trimToZeroReleasesEveryIdleTarget()
    {
        GlFramebuffer first = FboPool.acquire(128, 64);
        GlFramebuffer second = FboPool.acquire(128, 64);

        FboPool.release(first);
        FboPool.release(second);

        assertTrue(FboPool.bytes() > 0F);
        FboPool.trimTo(0L);
        assertEquals(0L, FboPool.bytes(), "an empty budget must drop every idle target");
        assertEquals(0, FboPool.idleCount());
    }

    /* ----------------------------------------------------------- targetEdge */

    @Test
    public void targetEdgeFollowsTheConsumer()
    {
        CompDocument doc = document("4k", 3840, 2160);
        CompProject project = project();

        CompRenderContext preview = new CompRenderContext(project, doc, 0F, null, null, null);

        assertEquals(1024F, CompLayerSourceCache.rasterMaxEdge(preview), 0.001F,
            "the panel preview keeps the draft cap");

        preview.preview = false;

        assertEquals(3840F, CompLayerSourceCache.rasterMaxEdge(preview), 0.001F,
            "the encoder may use the composition long edge");

        try
        {
            CompRenderContext.setTargetEdge(CompPrecache.MAX_WIDTH);

            CompRenderContext precache = new CompRenderContext(project, doc, 0F, null, null, null);

            assertEquals(CompPrecache.MAX_WIDTH, precache.targetEdge);
            assertEquals(1280F, CompLayerSourceCache.rasterMaxEdge(precache), 0.001F,
                "pre-render frames are 1280 px, so 3D layer bakes may reach 1280");
        }
        finally
        {
            CompRenderContext.setTargetEdge(0);
        }
    }

    /* ---------------------------------------------------------- pre-cache */

    @Test
    public void precacheKeySurvivesSafeTruncation()
    {
        String prefix = "project-with-a-very-long-name-that-exceeds-the-sixty-four-character-limit";

        assertNotEquals(CompPrecache.cacheKey(prefix + "A", "comp"),
            CompPrecache.cacheKey(prefix + "B", "comp"),
            "ids that differ after safe()'s 64-char cut must still get different cache directories");
        assertNotEquals(CompPrecache.cacheKey("a/b", "c"), CompPrecache.cacheKey("a", "b/c"),
            "sanitising punctuation must not merge ids");
    }

    @Test
    public void fourKPrecacheRasterIsBoundedTo1280()
    {
        CompDocument doc = document("4k", 3840, 2160);
        int[] size = CompPrecache.cacheSize(doc);

        assertTrue(size[0] <= CompPrecache.MAX_WIDTH, "cache width " + size[0]);
        assertTrue(size[1] <= CompPrecache.MAX_HEIGHT, "cache height " + size[1]);
        assertEquals(1F, CompPrecache.cacheScale(document("hd", 1280, 720)), 0.0001F,
            "a comp at the cache resolution does not downscale");
    }

    @Test
    public void residentBytesDropToZeroAfterClearAll()
    {
        CompPrecache.clearAll();

        assertEquals(0L, CompPrecache.residentBytes(), "no composition cache means no resident VRAM");
    }

    /* ------------------------------------------------------- disk identity */

    @Test
    public void frameDiskFilenameIncludesTheAlphaStage() throws Exception
    {
        CompDocument doc = document("disk", 64, 64);
        CompProject project = project();
        Path opaque = CompFrameDisk.file(project, doc, 0x1234L, 5, 64, 64, false);
        Path alpha = CompFrameDisk.file(project, doc, 0x1234L, 5, 64, 64, true);

        assertNotEquals(opaque, alpha, "opaque and alpha frames must not share a file");

        int[] pixels = new int[64 * 64];

        java.util.Arrays.fill(pixels, 0x7F336699);

        try
        {
            CompFrameDisk.save(project, doc, 0x1234L, 5, 64, 64, pixels, true);
            CompFrameDisk.save(project, doc, 0x1234L, 5, 64, 64, pixels, false);

            assertArrayEquals(pixels, CompFrameDisk.load(project, doc, 0x1234L, 5, 64, 64, true));
            assertArrayEquals(pixels, CompFrameDisk.load(project, doc, 0x1234L, 5, 64, 64, false));
        }
        finally
        {
            Files.deleteIfExists(opaque);
            Files.deleteIfExists(alpha);
        }
    }

    /* ------------------------------------------------------------- fixtures */

    private static CompDocument document(String id, int width, int height)
    {
        CompDocument doc = new CompDocument();

        doc.id = id;
        doc.name = id;
        doc.width = width;
        doc.height = height;
        doc.fps = 60F;
        doc.duration = 5F;

        return doc;
    }

    private static CompProject project()
    {
        CompProject project = new CompProject();

        project.id = "project";

        return project;
    }
}
