package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity rules for the effect catalogue.
 *
 * <p>"Parameter = 0" is not one number: a 0 px blur, a 0 px glow and a 0 amount of distortion are
 * identity, while a 0 threshold is a real threshold. These tests pin the table down so an effect
 * can be faded in/out without silently keeping a pass alive at zero.</p>
 */
public class EffectNeutralTest
{
    private static CompEffect effect(String type)
    {
        return new CompEffect("fx", type);
    }

    @Test
    public void zeroMainParameterIsIdentity()
    {
        assertTrue(EffectRenderer.isNeutral(effect("blur").put("radius", 0D), "blur"));
        assertTrue(EffectRenderer.isNeutral(effect("box_blur").put("radius", 0D), "box_blur"));
        assertTrue(EffectRenderer.isNeutral(effect("directional_blur").put("radius", 0D), "directional_blur"));
        assertTrue(EffectRenderer.isNeutral(effect("radial_blur").put("radius", 0D), "radial_blur"));
        assertTrue(EffectRenderer.isNeutral(effect("sharpen").put("amount", 0D), "sharpen"));
        assertTrue(EffectRenderer.isNeutral(effect("bulge").put("amount", 0D), "bulge"));
        assertTrue(EffectRenderer.isNeutral(effect("wave").put("amplitude", 0D), "wave"));
        assertTrue(EffectRenderer.isNeutral(effect("glow").put("radius", 0D), "glow"));
        assertTrue(EffectRenderer.isNeutral(effect("outer_glow").put("opacity", 0D), "outer_glow"));
        assertTrue(EffectRenderer.isNeutral(effect("drop_shadow").put("opacity", 0D), "drop_shadow"));
        assertTrue(EffectRenderer.isNeutral(effect("stroke").put("width", 0D), "stroke"));
        assertTrue(EffectRenderer.isNeutral(effect("rgb_split").put("amount", 0D), "rgb_split"));
        assertTrue(EffectRenderer.isNeutral(effect("linear_wipe").put("progress", 0D), "linear_wipe"));
        assertTrue(EffectRenderer.isNeutral(effect("radial_wipe").put("progress", 0D), "radial_wipe"));
        assertTrue(EffectRenderer.isNeutral(effect("dissolve").put("progress", 0D), "dissolve"));
        assertTrue(EffectRenderer.isNeutral(effect("depth_fog").put("amount", 0D), "depth_fog"));
        assertTrue(EffectRenderer.isNeutral(effect("depth_dof").put("amount", 0D), "depth_dof"));
        assertTrue(EffectRenderer.isNeutral(effect("light_sweep").put("opacity", 0D), "light_sweep"));
        assertTrue(EffectRenderer.isNeutral(effect("vhs").put("amount", 0D), "vhs"));
        assertTrue(EffectRenderer.isNeutral(effect("mosaic").put("size", 1D), "mosaic"));
        assertTrue(EffectRenderer.isNeutral(effect("scanlines").put("opacity", 0D), "scanlines"));
        assertTrue(EffectRenderer.isNeutral(effect("mirror").put("opacity", 0D), "mirror"));
    }

    @Test
    public void defaultsAreNotNeutral()
    {
        assertFalse(EffectRenderer.isNeutral(effect("blur"), "blur"));
        assertFalse(EffectRenderer.isNeutral(effect("glow"), "glow"));
        assertFalse(EffectRenderer.isNeutral(effect("stroke"), "stroke"));
        assertFalse(EffectRenderer.isNeutral(effect("radial_wipe"), "radial_wipe"));
        assertFalse(EffectRenderer.isNeutral(effect("mosaic"), "mosaic"));
    }

    @Test
    public void strengthZeroIsIdentityForEverythingIncludingGenerators()
    {
        assertTrue(EffectRenderer.isNeutral(effect("gradient_ramp").put(EffectRenderer.STRENGTH, 0D), "gradient_ramp"));
        assertTrue(EffectRenderer.isNeutral(effect("kaleidoscope").put(EffectRenderer.STRENGTH, 0D), "kaleidoscope"));
        assertTrue(EffectRenderer.isNeutral(effect("color_grade").put(EffectRenderer.STRENGTH, 0D), "color_grade"));
        assertTrue(EffectRenderer.isNeutral(effect("chroma_key").put(EffectRenderer.STRENGTH, 0D), "chroma_key"));

        assertFalse(EffectRenderer.isNeutral(effect("gradient_ramp"), "gradient_ramp"));
        assertFalse(EffectRenderer.isNeutral(effect("gradient_ramp").put(EffectRenderer.STRENGTH, 1D), "gradient_ramp"));
    }
}
