package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 3D bake projection must be the layer rectangle in logical pixels.
 *
 * <p>The bake used to multiply the ortho bounds by the raster cap factor while drawing vertices in
 * logical pixels. Content was therefore zoomed by {@code 1/scale} and clipped at its edges the
 * moment an effect switched the layer to the baked path — the reported "precomp grows after adding
 * an effect". The FBO's viewport already performs the scaling; the projection must not repeat it.
 */
public class RasterProjectionTest
{
    private static Vector3f project(Matrix4f matrix, float x, float y)
    {
        return matrix.transformProject(new Vector3f(x, y, 0F));
    }

    @Test
    public void theLayerRectangleMapsExactlyToTheClipVolume()
    {
        float x = -960F;
        float y = -540F;
        float w = 1920F;
        float h = 1080F;
        Matrix4f projection = CompLayerSourceCache.logicalProjection(x, y, w, h);

        Vector3f topLeft = project(projection, x, y);
        Vector3f bottomRight = project(projection, x + w, y + h);
        Vector3f centre = project(projection, x + w * 0.5F, y + h * 0.5F);

        assertEquals(-1F, topLeft.x, 1.0E-5F);
        assertEquals(1F, topLeft.y, 1.0E-5F);
        assertEquals(1F, bottomRight.x, 1.0E-5F);
        assertEquals(-1F, bottomRight.y, 1.0E-5F);
        assertEquals(0F, centre.x, 1.0E-5F);
        assertEquals(0F, centre.y, 1.0E-5F);
    }

    @Test
    public void paddingDoesNotChangeTheContentMapping()
    {
        /* A glow adds 42 px around the layer. The same content point must still land at the same
         * normalised position inside the padded rectangle; the quad later covers the padding too. */
        float x = -960F;
        float y = -540F;
        float w = 1920F;
        float h = 1080F;
        float pad = 42F;
        Matrix4f content = CompLayerSourceCache.logicalProjection(x, y, w, h);
        Matrix4f padded = CompLayerSourceCache.logicalProjection(x - pad, y - pad, w + pad * 2F, h + pad * 2F);

        Vector3f contentPoint = project(content, x, y);
        Vector3f paddedPoint = project(padded, x, y);

        /* Same logical point, same normalised position in its own rect. The old scaled projection
         * made these disagree whenever the padding changed the cap factor. */
        assertEquals(-1F, contentPoint.x, 1.0E-5F);
        assertEquals(-1F + 2F * pad / (w + pad * 2F), paddedPoint.x, 1.0E-5F);
        assertEquals(1F - 2F * pad / (h + pad * 2F), paddedPoint.y, 1.0E-5F);
    }
}
