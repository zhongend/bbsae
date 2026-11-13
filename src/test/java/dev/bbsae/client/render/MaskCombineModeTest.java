package dev.bbsae.client.render;

import dev.bbsae.comp.CompMask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** EffectCraft-style boolean mask modes, in addition to the original add/subtract/intersect. */
public class MaskCombineModeTest
{
    @Test
    public void extendedModesCombineCoverage()
    {
        assertEquals(0.8F, MaskCache.combine(0.2F, 0.8F, CompMask.MODE_LIGHTEN), 0.0001F);
        assertEquals(0.2F, MaskCache.combine(0.2F, 0.8F, CompMask.MODE_DARKEN), 0.0001F);
        assertEquals(0.6F, MaskCache.combine(0.2F, 0.8F, CompMask.MODE_DIFFERENCE), 0.0001F);
        assertEquals(0.2F, MaskCache.combine(0.2F, 0.8F, CompMask.MODE_NONE), 0.0001F);
    }

    @Test
    public void firstMaskModesStillProduceCoverage()
    {
        assertEquals(0.8F, MaskCache.combineFirst(CompMask.MODE_LIGHTEN, 0.8F), 0.0001F);
        assertEquals(0.8F, MaskCache.combineFirst(CompMask.MODE_DARKEN, 0.8F), 0.0001F);
        assertEquals(0.8F, MaskCache.combineFirst(CompMask.MODE_DIFFERENCE, 0.8F), 0.0001F);
        assertEquals(0F, MaskCache.combineFirst(CompMask.MODE_NONE, 0.8F), 0.0001F);
    }
}
