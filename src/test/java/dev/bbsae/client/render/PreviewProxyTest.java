package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview proxy and pre-render cache sizing.
 *
 * <p>Both are pure functions of the composition and the viewport, which is exactly why they are
 * testable without a GL context — and exactly why they should be tested: a wrong scale renders the
 * preview at the wrong resolution forever, and a wrong cache size makes every cached frame a
 * differently-scaled copy of the composition.</p>
 */
public class PreviewProxyTest
{
    private static CompDocument document(int width, int height)
    {
        CompDocument doc = new CompDocument();

        doc.width = width;
        doc.height = height;
        doc.fps = 30F;
        doc.duration = 10F;

        return doc;
    }

    @Test
    public void previewNeverRendersAboveCompositionResolution()
    {
        CompDocument doc = document(1920, 1080);

        /* A viewport larger than the comp must not upscale the render target: a preview is not a
         * render, and 4K pixels for a 1080p comp would be four times the cost for no detail. */
        assertEquals(1F, CompRenderer.previewScale(doc, 4000F, 3000F, 0));
    }

    @Test
    public void previewShrinksWithTheViewport()
    {
        CompDocument doc = document(1920, 1080);
        float half = CompRenderer.previewScale(doc, 960F, 540F, 0);

        assertEquals(0.5F, half, 0.001F);
        assertEquals(0.25F, CompRenderer.previewScale(doc, 480F, 270F, 0), 0.001F);
    }

    @Test
    public void previewUsesTheSmallerAxisSoTheCompositionFits()
    {
        CompDocument doc = document(1920, 1080);

        /* 700x600 contain: the width is the limiting axis (700/1920 = 0.364), so the height ratio
         * (600/1080 = 0.555) must not be used. */
        float scale = CompRenderer.previewScale(doc, 700F, 600F, 0);

        assertEquals(0.375F, scale, 0.001F);
    }

    @Test
    public void previewIsClampedAndQuantised()
    {
        CompDocument doc = document(1920, 1080);

        /* The floor is 0.2 before quantisation, so the smallest representable step is 3/16. */
        assertEquals(0.1875F, CompRenderer.previewScale(doc, 1F, 1F, 0), 0.001F);

        /* Quantised to 1/16 so a splitter drag does not re-render on every pixel. */
        float scale = CompRenderer.previewScale(doc, 1000F, 1000F, 0);

        assertEquals(Math.round(scale * 16F) / 16F, scale, 0.0001F);
    }

    @Test
    public void cacheSizeIsBoundedAndKeepsTheAspectRatio()
    {
        int[] hd = CompPrecache.cacheSize(document(1920, 1080));

        assertEquals(1280, hd[0]);
        assertEquals(720, hd[1]);

        int[] uhd = CompPrecache.cacheSize(document(3840, 2160));

        assertEquals(1280, uhd[0]);
        assertEquals(720, uhd[1]);

        /* Smaller than the bound: cached at native size, never upscaled. */
        int[] small = CompPrecache.cacheSize(document(640, 360));

        assertEquals(640, small[0]);
        assertEquals(360, small[1]);
    }

    @Test
    public void cacheSizeIsEvenSoRowPackingIsStable()
    {
        int[] size = CompPrecache.cacheSize(document(1366, 768));

        assertEquals(0, size[0] & 1);
        assertEquals(0, size[1] & 1);
        assertTrue(size[0] <= CompPrecache.MAX_WIDTH);
    }

    @Test
    public void aWorldLayerCannotBeCached()
    {
        CompDocument doc = document(1920, 1080);

        assertTrue(!CompPrecache.hasWorldLayer(doc));

        dev.bbsae.comp.CompLayer layer = new dev.bbsae.comp.CompLayer("l1", "BBS", "bbs");

        layer.visible = true;
        doc.layers.add(layer);

        assertTrue(CompPrecache.hasWorldLayer(doc));
    }
}
