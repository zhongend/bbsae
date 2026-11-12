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

        assertTrue(ids.size() >= 115, "the catalogue should have grown with the transition batch");
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
        assertNeutral("am_border", "width", 0F);
        assertNeutral("am_energy_beam", "intensity", 0F);
        assertNeutral("am_hex_array", "intensity", 0F);
        assertNeutral("am_color_separate", "amount", 0F);
        assertNeutral("am_stroke_taper", "width", 0F);
        assertNeutral("am_channel_map", "map_r", 0F);
        assertNeutral("am_channel_map_hsv", "hue_shift", 0F);
        assertNeutral("am_fill_behind", "amount", 0F);
        assertNeutral("am_solid_matte", "amount", 0F);
        assertNeutral("am_matte_fringe", "width", 0F);
        assertNeutral("am_flicker", "amount", 0F);
        assertNeutral("am_flip", "angle", 0F);
        assertNeutral("am_skew", "amount", 0F);
        assertNeutral("am_squeeze", "amount", 0F);
        assertNeutral("am_mask_blur", "radius", 0F);
        assertNeutral("am_color_gain", "gain_r", 1F);
        assertNeutral("am_block_dissolve", "progress", 1F);
        assertNeutral("am_feather", "radius", 0F);
        assertNeutral("am_fade", "progress", 1F);
        assertNeutral("am_blink", "amount", 0F);
        assertNeutral("am_halftone_dots", "alpha", 0F);
        assertNeutral("am_contour_grad", "alpha", 0F);
        assertNeutral("am_glow_scan", "intensity", 0F);
        assertNeutral("am_chroma_key", "strength", 0F);
        assertNeutral("am_luma_key", "strength", 0F);
        assertNeutral("am_grid_repeat", "amount", 0F);
        assertNeutral("am_line_repeat", "amount", 0F);
        assertNeutral("am_contour", "alpha", 0F);
        assertNeutral("am_glow", "intensity", 0F);
        assertNeutral("am_bevel", "strength", 0F);
        assertNeutral("am_reflection", "strength", 0F);
        assertNeutral("am_inner_shadow", "opacity", 0F);
        assertNeutral("am_gloss", "intensity", 0F);
        assertNeutral("am_checker_dissolve", "progress", 1F);
        assertNeutral("am_pixel_dissolve", "progress", 1F);
        assertNeutral("am_radial_wipe", "progress", 1F);
        assertNeutral("am_gradient_mask", "progress", 1F);
        assertNeutral("am_speed_lines", "alpha", 0F);

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

    /** The P1 blur/colour/distort batch: every entry has a real neutral value. */
    @Test
    public void p1BatchDeclaresItsZeroValues()
    {
        assertNeutral("am_unsharp_mask", "amount", 0F);
        assertNeutral("am_lens_blur_2", "strength", 0F);
        assertNeutral("am_spin_blur", "angle", 0F);
        assertNeutral("am_streak_strips", "alpha", 0F);
        assertNeutral("am_warp_blur", "intensity", 0F);
        assertNeutral("am_chromatic_zoom_blur", "strength", 0F);
        assertNeutral("am_spectral_map", "alpha", 0F);
        assertNeutral("am_soft_glow", "strength", 0F);
        assertNeutral("am_dark_glow", "strength", 0F);
        assertNeutral("am_light_glow", "strength", 0F);
        assertNeutral("am_color_hot", "intensity", 0F);
        assertNeutral("am_pinch_bulge_2", "strength", 0F);
        assertNeutral("am_swirl_4", "strength", 0F);
        assertNeutral("am_pinch_bulge_inside", "strength", 0F);
        assertNeutral("am_wave_warp", "m2", 0F);
        assertNeutral("am_turbulent_displace_3", "intensity", 0F);
        assertNeutral("am_tileshift_2", "strength", 0F);
        assertNeutral("am_stretch_segment", "stretch", 0F);
        assertNeutral("am_omino_glass", "strength", 0F);
        assertNeutral("am_shape_field", "alpha", 0F);
        assertNeutral("am_electric_edges", "alpha", 0F);
        assertNeutral("am_roughen_edges", "strength", 0F);
        assertNeutral("am_smooth_bevel", "radius", 0F);
        assertNeutral("am_voronoi_2", "alpha", 0F);
        assertNeutral("am_ridges_2", "alpha", 0F);
        assertNeutral("am_noise_3", "amount", 0F);
        assertNeutral("am_block_noise", "amount", 0F);
        assertNeutral("am_halftone_cmyk", "strength", 0F);
        assertNeutral("am_rgb_sep", "strength", 0F);
        assertNeutral("am_simplestars_2", "alpha", 0F);
        assertNeutral("am_volumetric_clouds", "amount", 0F);

        /* Hex rotate's identity is "no rotation at all", i.e. a combination of six zero values. */
        CompEffect hex = CompEffectRegistry.create("am_hex_rotate", 1);

        for (String key : new String[] {"rotate", "warp_angle", "angle", "twirl", "position_x", "position_y"})
        {
            hex.put(key, 0D);
        }

        assertTrue(AmEffectLibrary.isNeutral(hex), "am_hex_rotate with no rotation is the identity");

        CompEffect rotated = CompEffectRegistry.create("am_hex_rotate", 1);

        rotated.put("rotate", 45D);
        assertFalse(AmEffectLibrary.isNeutral(rotated), "a rotated hex grid is not the identity");
    }

    /**
     * Ranges/steps that mean the same thing on the same scale as the reference schema were aligned
     * with it (docs/EFFECTS-AM-GAP.zh.md). Defaults deliberately stay non-neutral for the library
     * previews, so only the bounds/step are pinned here.
     */
    @Test
    public void highPriorityRangesFollowTheReferenceSchema()
    {
        assertRange("am_hue_shift", "hue", 0F, 360F, 0.5F);
        assertRange("am_glow", "radius", 0F, 400F, 0.5F);
        assertRange("am_inner_glow", "radius", 0F, 400F, 0.5F);
        assertRange("am_pixelate", "size", 1F, 500F, 1F);
        assertRange("am_tile", "scale", 0.005F, 20F, 0.005F);
        assertRange("am_hex_array", "size", 0.5F, 200F, 0.1F);
        assertRange("am_hex_shift", "size", 0.5F, 200F, 0.1F);
        assertRange("am_hex_tile", "size", 0.5F, 200F, 0.1F);
        assertRange("am_four_color_gradient", "alpha", 0F, 1F, 0.001F);
        assertRange("am_grid_lines", "angle", -180F, 180F, 0.5F);
        assertRange("am_stripes", "angle", -180F, 180F, 0.5F);
    }

    private static void assertRange(String id, String key, float min, float max, float step)
    {
        AmEffectDef definition = AmEffectLibrary.get(id);

        assertNotNull(definition, "missing definition " + id);

        AmEffectDef.Param found = null;

        for (AmEffectDef.Param param : definition.params)
        {
            if (param.key.equals(key))
            {
                found = param;
            }
        }

        assertNotNull(found, id + " has no parameter " + key);
        assertEquals(min, found.min, 0.0001F, id + "." + key + " min");
        assertEquals(max, found.max, 0.0001F, id + "." + key + " max");
        assertEquals(step, found.step, 0.0001F, id + "." + key + " step");
    }

    /**
     * The AM repeat family is neutral exactly in its "one copy, no transform" configuration, not
     * when every parameter is zero (count 0 is not a thing and radius 0 with count 1 is still the
     * identity).
     */
    @Test
    public void repeatFamilyNeutralIsASingleUntransformedCopy()
    {
        CompEffect repeat = CompEffectRegistry.create("am_repeat", 1);

        repeat.put("count", 1D);
        repeat.put("offset_x", 0D);
        repeat.put("offset_y", 0D);
        repeat.put("angle", 0D);
        repeat.put("scale", 1D);
        repeat.put("alpha", 1D);
        assertTrue(AmEffectLibrary.isNeutral(repeat), "am_repeat with one untransformed copy is the input");
        repeat.put("count", 2D);
        assertFalse(AmEffectLibrary.isNeutral(repeat), "two copies must not report neutral");

        CompEffect radial = CompEffectRegistry.create("am_repeat_radial", 1);

        radial.put("count", 1D);
        radial.put("radius", 0D);
        radial.put("angle", 0D);
        radial.put("base_scale", 1D);
        radial.put("scale", 1D);
        radial.put("offset_x", 0D);
        radial.put("offset_y", 0D);
        radial.put("alpha", 1D);
        assertTrue(AmEffectLibrary.isNeutral(radial), "one copy at radius 0 is the input");
        radial.put("radius", 120D);
        assertFalse(AmEffectLibrary.isNeutral(radial), "a copy pushed off-centre is not the input");

        CompEffect scatter = CompEffectRegistry.create("am_repeat_scatter", 1);

        scatter.put("count", 1D);
        scatter.put("radius", 0D);
        scatter.put("angle", 0D);
        scatter.put("scale", 1D);
        scatter.put("offset_x", 0D);
        scatter.put("offset_y", 0D);
        scatter.put("alpha", 1D);
        assertTrue(AmEffectLibrary.isNeutral(scatter), "one copy with zero scatter radius is the input");
        scatter.put("radius", 80D);
        assertFalse(AmEffectLibrary.isNeutral(scatter), "scattered copies are not the input");
    }

    /** The P1 colour batch: an identity colour triple and a fill with a real zero alpha. */
    @Test
    public void colourMixerAndSolidFillHaveZeroConfigurations()
    {
        CompEffect mixer = CompEffectRegistry.create("am_rgb_mixer", 1);

        assertTrue(AmEffectLibrary.isNeutral(mixer),
            "the RGB mixer defaults are the red/green/blue identity triple");

        mixer.put("gamma", 2D);
        assertFalse(AmEffectLibrary.isNeutral(mixer), "a non-unit gamma is not the identity");

        CompEffect solid = CompEffectRegistry.create("am_solid_color", 1);

        solid.put("alpha", 0D);
        assertTrue(AmEffectLibrary.isNeutral(solid), "a solid fill at alpha 0 is the identity");

        solid.put("alpha", 1D);
        assertFalse(AmEffectLibrary.isNeutral(solid), "an opaque solid fill changes the layer");
    }

    /**
     * The new repeat effects are registered through the same data path as every other entry, and
     * the library previews require a thumbnail for each of them.
     */
    @Test
    public void repeatFamilyIsPartOfTheCatalogue()
    {
        for (String id : new String[] {"am_repeat", "am_repeat_radial", "am_repeat_scatter"})
        {
            AmEffectDef definition = AmEffectLibrary.get(id);

            assertNotNull(definition, "missing definition " + id);
            assertEquals("AM · 重复", definition.category, id + " category");
            assertTrue(definition.params.size() >= 6, id + " should expose the AM repeat controls");
            assertTrue(EffectRenderer.supports(id), id + " has no renderer");
        }
    }

    private static void assertNeutral(String id, String key, float value)
    {
        CompEffect effect = CompEffectRegistry.create(id, 1);

        effect.put(key, (double) value);

        assertTrue(AmEffectLibrary.isNeutral(effect), id + " must be an identity with " + key + " = 0");

        /* Probe another value inside the parameter's allowed range that must change the image.
         * Stepping up is preferred; identities at the maximum (a dissolve at progress 1) step down
         * instead, and the probe never leaves the slider range. */
        CompEffectRegistry.Definition definition = CompEffectRegistry.get(id);
        float min = 0F;
        float max = 1F;

        for (CompEffectRegistry.Param param : definition.params)
        {
            if (param.key.equals(key))
            {
                min = param.min;
                max = param.max;
            }
        }

        float probe = value + 1F <= max ? value + 1F : Math.max(min, value - 0.5F);

        effect.put(key, (double) probe);

        assertFalse(AmEffectLibrary.isNeutral(effect),
            id + " must not be an identity with " + key + " = " + probe);
    }
}
