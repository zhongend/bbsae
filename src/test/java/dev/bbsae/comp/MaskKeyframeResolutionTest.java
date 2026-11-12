package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mask scalar animation: feather / opacity / expansion are keyframed channels, and both render
 * paths (2D {@code CompRenderer} and 3D {@code CompLayerSourceCache}) rasterize through
 * {@code MaskCache}, which resolves them with {@link CompLayer#maskAt} before drawing. These tests
 * pin that resolution contract; the 3D bake path's use of the same call is verified by inspection
 * in the pen report (§12) because GL rendering is out of this task's write scope.
 */
public class MaskKeyframeResolutionTest
{
    private static CompLayer layer(CompMask mask)
    {
        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");

        mask.rectangle(0F, 0F, 100F, 100F);
        layer.masks.add(mask);

        return layer;
    }

    private static void key(CompLayer layer, String path, float tick, float value)
    {
        layer.scalarChannel(path).insert(tick, value);
    }

    @Test
    public void featherOpacityAndExpansionFollowTheirKeyframes()
    {
        CompMask mask = new CompMask("mask_1");
        CompLayer layer = layer(mask);

        key(layer, "mask/mask_1/feather", 0F, 0F);
        key(layer, "mask/mask_1/feather", 24F, 20F);
        key(layer, "mask/mask_1/opacity", 0F, 1F);
        key(layer, "mask/mask_1/opacity", 24F, 0.5F);
        key(layer, "mask/mask_1/expansion", 0F, 0F);
        key(layer, "mask/mask_1/expansion", 24F, 10F);

        CompMask atZero = layer.maskAt(mask, 0F);

        assertEquals(0F, atZero.feather, 0.001F);
        assertEquals(1F, atZero.opacity, 0.001F);
        assertEquals(0F, atZero.expansion, 0.001F);

        CompMask atEnd = layer.maskAt(mask, 24F);

        assertEquals(20F, atEnd.feather, 0.001F);
        assertEquals(0.5F, atEnd.opacity, 0.001F);
        assertEquals(10F, atEnd.expansion, 0.001F);

        CompMask halfway = layer.maskAt(mask, 12F);

        assertEquals(10F, halfway.feather, 0.001F);
        assertEquals(0.75F, halfway.opacity, 0.001F);
        assertEquals(5F, halfway.expansion, 0.001F);

        /* Resolution must not disturb the authored mask or its point count/closure. */
        assertEquals(0F, mask.feather, 0.001F, "maskAt works on a copy");
        assertEquals(4, atEnd.points.size());
        assertTrue(atEnd.closed);
    }

    @Test
    public void featherIsOneScalarPerMaskNotPerPoint()
    {
        CompMask mask = new CompMask("mask_1");
        CompLayer layer = layer(mask);

        /* ASSESSMENT (report §12): the model has a single mask feather, applied by one box blur over
         * the whole mask alpha. There is no per-point feather/FeatherPoint data, and the rasterizer
         * has no consumer for one. If a field were ever added, this guard fails to remind whoever
         * added it that the renderer and the inspector have to be wired too. */
        assertThrows(NoSuchFieldException.class, () -> CompMask.CompMaskPoint.class.getField("feather"));
        assertThrows(NoSuchFieldException.class, () -> CompMask.CompMaskPoint.class.getField("featherIn"));
        assertThrows(NoSuchFieldException.class, () -> CompMask.CompMaskPoint.class.getField("featherOut"));

        mask.feather = 12F;

        CompMask resolved = layer.maskAt(mask, 5F);

        /* Uniform across the mask: one scalar, no per-point values to vary. */
        assertEquals(12F, resolved.feather, 0.001F);
    }
}
