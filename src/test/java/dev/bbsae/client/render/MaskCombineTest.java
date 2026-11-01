package dev.bbsae.client.render;

import dev.bbsae.comp.CompMask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mask stacking semantics, matching AE/Alight Motion.
 *
 * <p>The first mask is applied to the layer's full alpha, not to an empty base: one Subtract or
 * Exclude mask must leave the outside visible. Subsequent masks combine against the current
 * coverage.</p>
 */
public class MaskCombineTest
{
    @Test
    public void firstMaskUsesTheLayerAlpha()
    {
        assertEquals(0.2F, MaskCache.combineFirst(CompMask.MODE_ADD, 0.2F), 1e-5F);
        assertEquals(0.8F, MaskCache.combineFirst(CompMask.MODE_SUBTRACT, 0.2F), 1e-5F);
        assertEquals(0.2F, MaskCache.combineFirst(CompMask.MODE_INTERSECT, 0.2F), 1e-5F);
        assertEquals(0.8F, MaskCache.combineFirst(CompMask.MODE_EXCLUDE, 0.2F), 1e-5F);
    }

    @Test
    public void subsequentMasksCombine()
    {
        assertEquals(0.44F, MaskCache.combine(0.2F, 0.3F, CompMask.MODE_ADD), 1e-5F);
        assertEquals(0.16F, MaskCache.combine(0.2F, 0.2F, CompMask.MODE_SUBTRACT), 1e-5F);
        assertEquals(0.04F, MaskCache.combine(0.2F, 0.2F, CompMask.MODE_INTERSECT), 1e-5F);
        assertEquals(0.5F, MaskCache.combine(0.2F, 0.5F, CompMask.MODE_EXCLUDE), 1e-5F);
    }
}
