package dev.bbsae.comp;

import dev.bbsae.client.render.EffectRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "pull the value to zero and the layer comes back" contract.
 *
 * <p>For every effect whose neutral configuration is known, this test builds the neutral instance
 * and asserts the renderer short-circuits before the shader. That is stronger than "the shader
 * happens to output the input at 0": it also guarantees no extra FBO/pass is spent, and it catches
 * a parameter that was added later without a neutral case.</p>
 *
 * <p>Effects with no natural zero (generators, keyers, posterise, kaleidoscope, …) are listed
 * explicitly at the bottom so the gap is documented instead of silently passing.</p>
 */
public class EffectNeutralTest
{
    /** Effects whose zero/neutral configuration must be the untouched input. */
    private static final String[][] NEUTRAL =
    {
        {"blur", "radius", "0"},
        {"box_blur", "radius", "0"},
        {"directional_blur", "radius", "0"},
        {"radial_blur", "radius", "0"},
        {"sharpen", "amount", "0"},
        {"brightness_contrast", "brightness", "0"},
        {"hue_saturation", "hue", "0"},
        {"exposure", "exposure", "0"},
        {"tint", "amount", "0"},
        {"levels", "black", "0"},
        {"curves", "shadow", "0"},
        {"invert", "amount", "0"},
        {"vignette", "amount", "0"},
        {"noise", "amount", "0"},
        {"grain", "amount", "0"},
        {"shake", "amplitude", "0"},
        {"matte_choker", "choke", "0"},
        {"colorama", "amount", "0"},
        {"color_grade", "exposure", "0"},
        {"block_shatter", "progress", "0"},
        {"mosaic", "size", "1"},
        {"find_edges", "amount", "0"},
        {"scanlines", "opacity", "0"},
        {"glitch", "amount", "0"},
        {"halftone", "strength", "0"},
        {"emboss", "amount", "0"},
        {"mirror", "opacity", "0"},
        {"rgb_split", "amount", "0"},
        {"wave", "amplitude", "0"},
        {"offset", "amount", "0"},
        {"polar_coordinates", "amount", "0"},
        {"spherize", "amount", "0"},
        {"displacement", "amount", "0"},
        {"bulge", "amount", "0"},
        {"pinch", "amount", "0"},
        {"twist", "amount", "0"},
        {"cc_split", "gap", "0"},
        {"transform", "offset_x", "0"},
        {"glow", "radius", "0"},
        {"outer_glow", "radius", "0"},
        {"drop_shadow", "opacity", "0"},
        {"stroke", "width", "0"},
        {"linear_wipe", "progress", "1"},
        {"radial_wipe", "progress", "1"},
        {"dissolve", "progress", "1"},
        {"vhs", "amount", "0"},
        {"grid_repeat", "opacity", "0"},
        {"random_repeat", "opacity", "0"},
        {"depth_fog", "amount", "0"},
        {"depth_dof", "amount", "0"},
        {"light_sweep", "opacity", "0"},
        {"am_color_balance", "cr_low", "0"},
        {"am_temperature", "temperature", "0"},
        {"am_highlight_shadow", "highlights", "0"},
        {"am_cartoon", "strength", "0"},
        {"am_long_shadow", "size", "0"},
        {"am_bend", "amount", "0"},
        {"am_outline", "width", "0"},
        {"am_pinch_bulge", "strength", "0"},
        {"am_circular_ripple", "strength", "0"},
        {"am_swirl", "angle", "0"},
        {"am_mirror_warp", "strength", "0"},
        {"am_tile", "scale", "1"},
        {"am_pixelate", "size", "1"},
    };

    /** Effects that legitimately have no zero state (generators, keyers, quantisers). */
    private static final String[] NO_ZERO =
    {
        "gradient_ramp", "noise_gradient", "checkerboard", "grid", "circle",
        "chroma_key", "luma_key", "posterize", "threshold", "depth_matte",
        "kaleidoscope", "am_kaleidoscope", "am_rotate_tile", "am_hex_tile",
    };

    @Test
    public void zeroMeansTheOriginalLayer()
    {
        for (String[] entry : NEUTRAL)
        {
            CompEffect effect = CompEffectRegistry.create(entry[0], 1);

            assertTrue(effect != null, "missing registry entry: " + entry[0]);

            /* Start from the documented default, then force the neutral value. Some effects
             * (colour grade, curves) have non-zero defaults on other parameters, so set every
             * parameter of the definition to its neutral value explicitly below. */
            neutralise(effect, entry[0]);
            effect.put(entry[1], Double.parseDouble(entry[2]));

            assertTrue(EffectRenderer.isNeutral(effect, entry[0]),
                entry[0] + " is not neutral at " + entry[1] + "=" + entry[2]);
        }
    }

    @Test
    public void aNonZeroValueIsNotNeutral()
    {
        CompEffect brightness = CompEffectRegistry.create("brightness_contrast", 1);

        neutralise(brightness, "brightness_contrast");
        brightness.put("brightness", 0.4D);

        assertFalse(EffectRenderer.isNeutral(brightness, "brightness_contrast"));

        CompEffect curve = CompEffectRegistry.create("curves", 1);

        neutralise(curve, "curves");
        curve.put("amount", 1D);
        curve.put("shadow", 0.3D);

        assertFalse(EffectRenderer.isNeutral(curve, "curves"), "a lifted shadow is not the identity");

        CompEffect bend = CompEffectRegistry.create("am_bend", 1);

        bend.put("amount", 0.3D);
        assertFalse(EffectRenderer.isNeutral(bend, "am_bend"));
    }

    @Test
    public void documentedNoZeroEffectsAreNotNeutral()
    {
        for (String id : NO_ZERO)
        {
            CompEffect effect = CompEffectRegistry.create(id, 1);

            assertTrue(effect != null, "missing registry entry: " + id);
            assertFalse(EffectRenderer.isNeutral(effect, id),
                id + " is listed as having no zero state but reported neutral");
        }
    }

    /**
     * Sets every numeric parameter to its identity value: 0 when the range contains it, otherwise
     * the value closest to 0 (so a 0.1..4 gamma becomes 1). Colours are set to the neutral wheel.
     */
    private static void neutralise(CompEffect effect, String id)
    {
        CompEffectRegistry.Definition definition = CompEffectRegistry.get(id);

        if (definition == null)
        {
            return;
        }

        for (CompEffectRegistry.Param param : definition.params)
        {
            float value = 0F;

            if (param.min > 0F)
            {
                value = param.min;
            }
            else if (param.max < 0F)
            {
                value = param.max;
            }

            /* Fields whose identity is 1, not 0. Saturation is an offset in most effects (0 is
             * neutral) but a gain in the colour grade (1 is neutral). */
            boolean unit = "gamma".equals(param.key) || "white".equals(param.key)
                || "scale_x".equals(param.key) || "scale_y".equals(param.key);

            if ("saturation".equals(param.key))
            {
                unit = "color_grade".equals(id);
            }

            if (unit)
            {
                value = 1F;
            }

            effect.put(param.key, (double) value);
        }

        for (CompEffectRegistry.ColorParam color : definition.colorParams)
        {
            effect.putColor(color.key, 0xFF808080);
        }
    }
}
