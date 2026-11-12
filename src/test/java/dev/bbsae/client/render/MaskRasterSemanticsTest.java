package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel-semantics parity for the pooled {@link MaskCache} rasterizer.
 *
 * <p>The cached path reuses its AWT image, its float buffers and the layer-space bezier, and it
 * derives feather/expansion scaling from the same resolved mask values as before. These tests
 * compare it against the reference implementation
 * ({@link CompLayer#maskAt} + {@link MaskCache#alphaFor}) mask by mask and mode by mode, so a
 * faster rasterizer can never silently change a matte.</p>
 */
public class MaskRasterSemanticsTest
{
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    private static CompLayer layer(float width, float height, CompMask... masks)
    {
        CompLayer layer = new CompLayer("mask_semantics", "Mask Semantics", "solid");

        layer.width = width;
        layer.height = height;

        for (CompMask mask : masks)
        {
            layer.masks.add(mask);
        }

        return layer;
    }

    private static CompMask rect(String id, float x, float y, float w, float h)
    {
        CompMask mask = new CompMask(id);

        mask.rectangle(x, y, w, h);

        return mask;
    }

    /** The pre-refactor resolution pipeline, used as the reference. */
    private static float[] reference(CompLayer layer, int width, int height, Matrix4f world,
                                     float frame, float scale)
    {
        List<CompMask> resolved = new ArrayList<>();

        for (CompMask mask : layer.masks)
        {
            if (!MaskCache.isMatte(mask))
            {
                continue;
            }

            CompMask copy = layer.maskAt(mask, frame);

            copy.feather = Math.min(128F, Math.max(0F, copy.feather));
            copy.expansion *= scale;

            if (Math.abs(scale - 1F) > 0.0001F)
            {
                copy.feather *= scale;
            }

            resolved.add(copy);
        }

        return MaskCache.alphaFor(resolved, width, height, world);
    }

    private static void assertSamePixels(float[] expected, float[] actual, String message)
    {
        assertNotNull(expected, message + " (reference)");
        assertNotNull(actual, message + " (optimized)");
        assertEquals(expected.length, actual.length, message);

        for (int i = 0; i < expected.length; i++)
        {
            if (Math.abs(expected[i] - actual[i]) > 1F / 255F)
            {
                throw new AssertionError(message + ": pixel " + (i % WIDTH) + "," + (i / WIDTH)
                    + " expected " + expected[i] + " but was " + actual[i]);
            }
        }
    }

    /** Width, in pixels, of the anti-aliased/feathered transition across the left vertical edge. */
    private static float featherWidth(float[] alpha, int row)
    {
        int first = -1;
        int saturated = -1;

        for (int x = 0; x < WIDTH; x++)
        {
            float value = alpha[row * WIDTH + x];

            if (value > 0.02F && first < 0)
            {
                first = x;
            }

            if (first >= 0 && value > 0.98F)
            {
                saturated = x;
                break;
            }
        }

        return first < 0 || saturated < 0 ? -1F : saturated - first + 1F;
    }

    @Test
    public void singleMaskMatchesTheReference()
    {
        CompLayer layer = layer(WIDTH, HEIGHT, rect("single", 60F, 50F, 120F, 90F));

        assertSamePixels(
            reference(layer, WIDTH, HEIGHT, null, 0F, 1F),
            MaskCache.coverageForTest(layer, WIDTH, HEIGHT, null, 0F, 1F, false),
            "a plain closed rectangle");
    }

    @Test
    public void featherAndExpansionMatchTheReference()
    {
        CompMask mask = rect("soft", 60F, 50F, 120F, 90F);

        mask.feather = 7F;
        mask.expansion = 3F;

        CompLayer layer = layer(WIDTH, HEIGHT, mask);

        assertSamePixels(
            reference(layer, WIDTH, HEIGHT, null, 0F, 1F),
            MaskCache.coverageForTest(layer, WIDTH, HEIGHT, null, 0F, 1F, false),
            "feathered and expanded mask");
    }

    @Test
    public void negativeExpansionAndInvertMatchTheReference()
    {
        CompMask mask = rect("shrink", 60F, 50F, 120F, 90F);

        mask.expansion = -6F;
        mask.inverted = true;
        mask.opacity = 0.6F;

        CompLayer layer = layer(WIDTH, HEIGHT, mask);

        assertSamePixels(
            reference(layer, WIDTH, HEIGHT, null, 0F, 1F),
            MaskCache.coverageForTest(layer, WIDTH, HEIGHT, null, 0F, 1F, false),
            "negative expansion + invert + opacity");
    }

    @Test
    public void everyBooleanModeMatchesTheReference()
    {
        CompMask add = rect("add", 40F, 40F, 200F, 160F);
        CompMask subtract = rect("subtract", 90F, 60F, 80F, 80F);

        subtract.mode = CompMask.MODE_SUBTRACT;

        CompMask intersect = rect("intersect", 60F, 60F, 160F, 120F);

        intersect.mode = CompMask.MODE_INTERSECT;

        CompMask exclude = rect("exclude", 120F, 100F, 120F, 90F);

        exclude.mode = CompMask.MODE_EXCLUDE;

        CompLayer layer = layer(WIDTH, HEIGHT, add, subtract, intersect, exclude);

        assertSamePixels(
            reference(layer, WIDTH, HEIGHT, null, 0F, 1F),
            MaskCache.coverageForTest(layer, WIDTH, HEIGHT, null, 0F, 1F, false),
            "add/subtract/intersect/exclude stack");
    }

    @Test
    public void firstSubtractMaskLeavesTheOutsideVisible()
    {
        CompMask subtract = rect("first_subtract", 80F, 60F, 120F, 100F);

        subtract.mode = CompMask.MODE_SUBTRACT;

        CompLayer layer = layer(WIDTH, HEIGHT, subtract);

        float[] alpha = MaskCache.coverageForTest(layer, WIDTH, HEIGHT, null, 0F, 1F, false);

        assertNotNull(alpha);
        assertEquals(1F, alpha[4 * WIDTH + 4], 1F / 255F, "outside stays visible");
        assertEquals(0F, alpha[100 * WIDTH + 140], 1F / 255F, "inside is cut out");
    }

    @Test
    public void worldTransformMatchesTheReference()
    {
        CompMask mask = rect("rotated", 80F, 60F, 120F, 90F);
        Matrix4f world = new Matrix4f()
            .translate(160F, 120F, 0F)
            .rotateZ((float) Math.toRadians(24D))
            .scale(1.3F, 0.8F, 1F)
            .translate(-160F, -120F, 0F);

        CompLayer layer = layer(WIDTH, HEIGHT, mask);

        assertSamePixels(
            reference(layer, WIDTH, HEIGHT, world, 0F, 1F),
            MaskCache.coverageForTest(layer, WIDTH, HEIGHT, world, 0F, 1F, false),
            "non-uniform world transform");
    }

    /**
     * Non-uniform world scale: the caller's raster scale is the maximum axis scale, feather and
     * expansion are multiplied by it. The refactor must not change that compensation — the
     * optimized raster has to stay within one pixel of the previous implementation's soft edge.
     */
    @Test
    public void nonUniformScaleKeepsTheFeatherWithinOnePixelOfTheLegacyRaster()
    {
        CompMask mask = rect("feather_scale", 100F, 80F, 120F, 80F);

        mask.feather = 6F;
        mask.expansion = 2F;

        CompLayer layer = layer(WIDTH, HEIGHT, mask);
        Matrix4f world = new Matrix4f().scale(1.4F, 0.7F, 1F);

        float[] legacy = reference(layer, WIDTH, HEIGHT, world, 0F, 1.4F);
        float[] optimized = MaskCache.coverageForTest(layer, WIDTH, HEIGHT, world, 0F, 1.4F, false);

        assertSamePixels(legacy, optimized, "non-uniform feather/expansion");

        float legacyWidth = featherWidth(legacy, 84);
        float optimizedWidth = featherWidth(optimized, 84);

        assertTrue(legacyWidth > 0F && optimizedWidth > 0F, "both rasters need a soft edge");
        assertEquals(legacyWidth, optimizedWidth, 1F,
            "the compensated feather may drift by at most one pixel");
    }

    @Test
    public void projectedMaskMatchesTheReference()
    {
        CompMask mask = rect("projected", 60F, 50F, 120F, 90F);
        Matrix4f viewProjection = new Matrix4f()
            .perspective((float) Math.toRadians(50D), WIDTH / (float) HEIGHT, 10F, 1000F)
            .translate(0F, 0F, -300F)
            .rotateY(0.25F);

        CompLayer layer = layer(WIDTH, HEIGHT, mask);

        List<CompMask> resolved = new ArrayList<>();

        resolved.add(layer.maskAt(mask, 0F));

        assertSamePixels(
            MaskCache.alphaForProjected(resolved, WIDTH, HEIGHT, viewProjection),
            MaskCache.coverageForTest(layer, WIDTH, HEIGHT, viewProjection, 0F, 1F, true),
            "projected 3D mask");
    }

    @Test
    public void pooledRasterizerDoesNotMutateTheSourceMask()
    {
        CompMask mask = rect("source", 60F, 50F, 120F, 90F);

        mask.feather = 5F;
        mask.expansion = 2F;

        CompLayer layer = layer(WIDTH, HEIGHT, mask);

        layer.scalarChannel("mask/source/feather").insertInheriting(0F, 9F);

        MaskCache.coverageForTest(layer, WIDTH, HEIGHT, null, 0F, 2F, false);

        assertEquals(5F, mask.feather, 1e-6F, "the live mask keeps its authored feather");
        assertEquals(2F, mask.expansion, 1e-6F, "the live mask keeps its authored expansion");
        assertEquals(1F, mask.opacity, 1e-6F);
        assertEquals(4, mask.points.size(), "the point list is never replaced");
    }
}
