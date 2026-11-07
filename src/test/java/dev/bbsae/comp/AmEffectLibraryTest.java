package dev.bbsae.comp;

import dev.bbsae.client.render.EffectRenderer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the data-driven reference-compatible catalogue.
 *
 * <p>The important property is not "an effect exists" but "its parameters mean something": every
 * definition must be registered (so the library and the inspector show it), supported by the
 * renderer, and — where the effect has a natural identity — report {@code neutral} at the zero
 * value so the pass is skipped and the layer is untouched.</p>
 */
public class AmEffectLibraryTest
{
    @Test
    public void everyDefinitionIsRegisteredAndRenderable()
    {
        Set<String> ids = new HashSet<>();

        for (AmEffectDef definition : AmEffectLibrary.all())
        {
            assertTrue(ids.add(definition.id), "duplicate id " + definition.id);
            assertNotNull(CompEffectRegistry.get(definition.id), "not registered: " + definition.id);
            assertTrue(EffectRenderer.supports(definition.id), "no renderer: " + definition.id);
            assertTrue(!definition.params.isEmpty() || !definition.colors.isEmpty(),
                "no editable parameters: " + definition.id);
            assertTrue(definition.body.contains("void main()"), "body lacks main(): " + definition.id);
            assertTrue(definition.body.contains("fragColor"), "body writes no colour: " + definition.id);
        }

        assertTrue(ids.size() >= 84, "the catalogue should have grown with the procedural batch");
    }

    @Test
    public void registryCopiesTheParameterSchema()
    {
        CompEffectRegistry.Definition bend = CompEffectRegistry.get("am_bend");

        assertNotNull(bend);
        assertEquals("弯曲", bend.name);
        assertEquals(3, bend.params.size());
        assertEquals("amount", bend.params.get(0).key);
        assertEquals(0.25F, bend.params.get(0).defaultValue, 0.0001F);

        CompEffectRegistry.Definition cartoon = CompEffectRegistry.get("am_cartoon");

        assertNotNull(cartoon);
        assertEquals(1, cartoon.colorParams.size());
        assertEquals("outline", cartoon.colorParams.get(0).key);
    }

    @Test
    public void zeroValueMeansTheOriginalLayer()
    {
        assertNeutral("am_bend", "amount", 0F);
        assertNeutral("am_long_shadow", "size", 0F);
        assertNeutral("am_cartoon", "strength", 0F);
        assertNeutral("am_temperature", "temperature", 0F);
        assertNeutral("am_outline", "width", 0F);
        assertNeutral("am_pinch_bulge", "strength", 0F);
        assertNeutral("am_circular_ripple", "strength", 0F);
        assertNeutral("am_swirl", "angle", 0F);
        assertNeutral("am_mirror_warp", "strength", 0F);
        assertNeutral("am_polar", "amount", 0F);
        assertNeutral("am_fractal_warp", "amount", 0F);
        assertNeutral("am_colorize", "strength", 0F);
        assertNeutral("am_replace_color", "strength", 0F);
        assertNeutral("am_gradient_map", "amount", 0F);
        assertNeutral("am_radial_shadow", "size", 0F);
        assertNeutral("am_gaussian_blur", "strength", 0F);
        assertNeutral("am_box_blur", "strength", 0F);
        assertNeutral("am_motion_blur", "length", 0F);
        assertNeutral("am_zoom_blur", "strength", 0F);
        assertNeutral("am_lens_blur", "radius", 0F);
        assertNeutral("am_chromatic_aberration", "amount", 0F);
        assertNeutral("am_tilt_shift", "radius", 0F);
        assertNeutral("am_stretch_axis", "amount", 0F);
        assertNeutral("am_slice_glitch", "amount", 0F);
        assertNeutral("am_four_color_gradient", "alpha", 0F);
        assertNeutral("am_stripes", "alpha", 0F);
        assertNeutral("am_grid_lines", "alpha", 0F);
        assertNeutral("am_starfield", "alpha", 0F);
        assertNeutral("am_displace_map", "amount", 0F);
        assertNeutral("am_gradient_displace", "strength", 0F);
        assertNeutral("am_glass", "strength", 0F);
        assertNeutral("am_split_tone", "amount", 0F);
        assertNeutral("am_duotone", "amount", 0F);
        assertNeutral("am_palette_map", "amount", 0F);
        assertNeutral("am_hue_shift", "amount", 0F);
        assertNeutral("am_light_leak", "alpha", 0F);
        assertNeutral("am_light_wrap", "strength", 0F);
        assertNeutral("am_pulse_opacity", "amount", 0F);
        assertNeutral("am_sepia", "amount", 0F);
        assertNeutral("am_color_bands", "amount", 0F);
        assertNeutral("am_oil_paint", "radius", 0F);
        assertNeutral("am_heat_map", "amount", 0F);
        assertNeutral("am_color_tune", "amount", 0F);
        assertNeutral("am_clouds", "alpha", 0F);
        assertNeutral("am_contour_lines", "alpha", 0F);
        assertNeutral("am_curl", "angle", 0F);
        assertNeutral("am_hex_shift", "amount", 0F);
        assertNeutral("am_ripple_pulse", "strength", 0F);
        assertNeutral("am_inner_glow", "radius", 0F);
        assertNeutral("am_desaturate", "amount", 0F);
        assertNeutral("am_monochrome", "amount", 0F);
        assertNeutral("am_gradient_overlay", "alpha", 0F);
        assertNeutral("am_radial_gradient", "alpha", 0F);
        assertNeutral("am_edge_light", "radius", 0F);
        assertNeutral("am_tile", "scale", 1F);
        assertNeutral("am_pixelate", "size", 1F);
        assertNeutral("am_lens_flare", "intensity", 0F);
        assertNeutral("am_spin_blur", "angle", 0F);
        assertNeutral("am_liquify", "strength", 0F);
        assertNeutral("am_sketch", "strength", 0F);
        assertNeutral("am_chromatic_blur", "strength", 0F);
        assertNeutral("am_bokeh", "intensity", 0F);
        assertNeutral("am_jitter", "amount", 0F);
        assertNeutral("am_swing", "angle", 0F);
        assertNeutral("am_pulsate", "amount", 0F);
        assertNeutral("am_spin", "speed", 0F);
        assertNeutral("am_random_displace", "amount", 0F);
        assertNeutral("am_scale_assist", "scale_x", 1F);
        assertNeutral("am_rgb_gamma", "gamma_r", 1F);
        assertNeutral("am_sat_vib", "saturation", 0F);
        assertNeutral("am_spot_color", "amount", 0F);
        assertNeutral("am_edge_glow", "strength", 0F);
        assertNeutral("am_vortex_blur", "angle", 0F);
        assertNeutral("am_inner_blur", "radius", 0F);
        assertNeutral("am_radial_rays", "alpha", 0F);
        assertNeutral("am_dots", "alpha", 0F);
        assertNeutral("am_star_shape", "alpha", 0F);
        assertNeutral("am_smooth_edges", "radius", 0F);
        assertNeutral("am_halftone_lines", "alpha", 0F);

        CompEffect balance = CompEffectRegistry.create("am_color_balance", 1);

        assertTrue(AmEffectLibrary.isNeutral(balance), "all-zero colour balance must be an identity");

        CompEffect bend = CompEffectRegistry.create("am_bend", 1);

        bend.put("amount", 0.4D);
        assertFalse(AmEffectLibrary.isNeutral(bend), "a bent layer is not the identity");
    }

    @Test
    public void createFillsDefaultsSoTheFirstLookIsTheDefaultLook()
    {
        CompEffect effect = CompEffectRegistry.create("am_bend", 7);

        assertEquals("fx7", effect.id);
        assertEquals("am_bend", effect.type);
        assertEquals(0.25D, effect.value("amount", -1D), 0.0001D);
        assertEquals(0.5D, effect.value("center_x", -1D), 0.0001D);
        assertEquals(1D, effect.value(CompEffect.STRENGTH, -1D), 0.0001D);
    }

    private static void assertNeutral(String id, String key, float value)
    {
        CompEffect effect = CompEffectRegistry.create(id, 1);

        effect.put(key, (double) value);

        assertTrue(AmEffectLibrary.isNeutral(effect), id + " must be an identity with " + key + " = 0");

        /* Probe just past the identity: a value inside the parameter's allowed range that must
         * change the image (0.5 is below the minimum size of the pixelate effect). */
        effect.put(key, (double) (value + 1F));

        assertFalse(AmEffectLibrary.isNeutral(effect),
            id + " must not be an identity with " + key + " = " + (value + 1F));
    }
}
