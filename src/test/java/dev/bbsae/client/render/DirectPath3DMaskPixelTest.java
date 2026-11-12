package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel regression for P1-C: a 3D layer whose bake is skipped (replay-entity layers never bake,
 * precomps without a cached raster fall back) still has to render its masks.
 *
 * <p>The direct path draws the layer content already camera-projected in composition space, so the
 * affine 2D mask cannot be used. The fix projects the mask path through the same
 * {@code projection · view · world} and rasterizes it at render size
 * ({@link MaskCache#getProjected}); the resulting mask is then multiplied into the content exactly
 * like the 2D case. This test reproduces the direct path: content rendered with the camera, mask
 * projected with the same matrices, {@link PassRenderer#mask} applied, then checks that the mask
 * region is preserved and the rest is cut out — including a rotated 3D layer, which an
 * axis-aligned screen-space mask could not follow.</p>
 */
public class DirectPath3DMaskPixelTest
{
    private static final int W = DepthPlanePixelHarness.W;
    private static final int H = DepthPlanePixelHarness.H;

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
    public void projectedMaskCutsTheVisibleAreaOfADirect3dLayer()
    {
        Assumptions.assumeTrue(CompShaders.composite != null && CompShaders.composite.isValid(),
            "composite program unavailable");

        verifyMaskedLayer(0F, "unrotated");
    }

    @Test
    public void projectedMaskFollowsARotated3dLayer()
    {
        Assumptions.assumeTrue(CompShaders.composite != null && CompShaders.composite.isValid(),
            "composite program unavailable");

        verifyMaskedLayer(28F, "rotated");
    }

    private static void verifyMaskedLayer(float rotationY, String what)
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();

        /* Remove the demo shape layers: this test owns exactly one 3D layer. */
        for (CompLayer existing : new java.util.ArrayList<>(DepthPlanePixelHarness.layersOf(comp)))
        {
            comp.removeLayer(existing.id);
        }

        CompLayer layer = DepthPlanePixelHarness.addRect(comp, "masked3d", 1310F, 615F, 300F,
            800F, 500F, 0xFF33CC66, 1F, true);

        layer.rotationY = rotationY;

        CompMask mask = new CompMask("mask");

        /* Shape rasters are centred on the layer origin: content covers local (-400,-250)..(400,250).
         * The mask keeps the central 400x250 and cuts the border. */
        mask.rectangle(-200F, -125F, 400F, 250F);
        layer.masks.add(mask);

        CompProject project = DepthPlanePixelHarness.project(false);
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
        CompCamera camera = CompCamera.of(comp, 0F, 0F);

        GlFramebuffer content = DepthPlanePixelHarness.content(context, camera, layer);
        Matrix4f world = CompScene.world(context, layer);
        Matrix4f viewProjection = new Matrix4f(camera.projection).mul(camera.view).mul(world);
        MaskCache.MaskTexture maskTexture = MaskCache.getProjected(layer, W, H, viewProjection,
            context.frame, 1F);

        assertNotNull(maskTexture, what + ": projected mask must rasterize");

        GlFramebuffer masked = PassRenderer.mask(content, maskTexture.texture.id, 0);

        int[] contentPixels = content.readTopDown();
        int[] maskedPixels = masked.readTopDown();

        int center = projectedPixel(camera, world, comp, 0F, 0F);
        int cut = projectedPixel(camera, world, comp, 300F, 200F);

        assertEquals(0xFF, alpha(contentPixels, center), what + ": layer content is opaque at the centre; "
            + describe(contentPixels, center, cut) + " corners=" + projectedCorners(camera, world, comp));
        assertTrue(alpha(maskedPixels, center) > 200,
            what + ": the mask keeps the layer visible at its centre (alpha=" + alpha(maskedPixels, center) + ")");
        assertEquals(0xFF, alpha(contentPixels, cut), what + ": layer content covers the cut point");
        assertEquals(0, alpha(maskedPixels, cut),
            what + ": the projected mask removes coverage outside the mask path");

        content.delete();
        masked.delete();
    }

    private static int projectedPixel(CompCamera camera, Matrix4f world, CompDocument comp, float localX, float localY)
    {
        Vector3f point = new Vector3f(localX, localY, 0F);

        world.transformPosition(point);

        Vector2f projected = camera.project(point, comp.width, comp.height);

        assertNotNull(projected, "layer point must be on screen");

        int x = Math.max(0, Math.min(W - 1, Math.round(projected.x * W / (float) comp.width)));
        int y = Math.max(0, Math.min(H - 1, Math.round(projected.y * H / (float) comp.height)));

        return x + y * W;
    }

    private static int alpha(int[] pixels, int index)
    {
        return (pixels[index] >>> 24) & 0xFF;
    }

    private static String describe(int[] pixels, int center, int cut)
    {
        int covered = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < H; y++)
        {
            for (int x = 0; x < W; x++)
            {
                if (((pixels[x + y * W] >>> 24) & 0xFF) > 8)
                {
                    covered++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        return "covered=" + covered + " bbox=" + minX + "," + minY + ".." + maxX + "," + maxY
            + " center=" + center + "(alpha=" + alpha(pixels, center) + ")"
            + " cut=" + cut + "(alpha=" + alpha(pixels, cut) + ")";
    }

    private static String projectedCorners(CompCamera camera, Matrix4f world, CompDocument comp)
    {
        StringBuilder builder = new StringBuilder();

        for (float[] corner : new float[][] {{-400F, -250F}, {400F, -250F}, {400F, 250F}, {-400F, 250F}})
        {
            Vector3f point = new Vector3f(corner[0], corner[1], 0F);

            world.transformPosition(point);
            Vector2f projected = camera.project(point, comp.width, comp.height);
            builder.append(projected == null ? "null" : String.format(java.util.Locale.ROOT, "(%.0f,%.0f)", projected.x, projected.y)).append(' ');
        }

        return builder.toString();
    }
}
