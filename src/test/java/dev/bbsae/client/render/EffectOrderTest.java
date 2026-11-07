package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3D layer splits its effect stack: the leading run of layer-space effects is baked into the
 * layer raster, and everything from the first projected effect onward runs after projection.
 *
 * <p>This split is what keeps the author's order. The old code ran <b>only</b> the projected
 * effects post-projection, so a stack like {@code s3d_cube -> blur} executed the blur first (baked)
 * and the cube afterwards — the reverse of what the user arranged. These tests pin the split point
 * itself, which is pure list logic and needs no GL.</p>
 */
public class EffectOrderTest
{
    private static CompEffect effect(String type)
    {
        return CompEffectRegistry.create(type, 1);
    }

    private static List<CompEffect> stack(String... types)
    {
        List<CompEffect> effects = new ArrayList<>();

        for (String type : types)
        {
            effects.add(effect(type));
        }

        return effects;
    }

    @Test
    public void aLayerOnlyStackHasNoProjectedPart()
    {
        List<CompEffect> effects = stack("blur", "am_bend", "am_color_gain");

        assertEquals(effects.size(), CompRenderer.firstProjectedEffect(effects));
    }

    @Test
    public void theFirstProjectedEffectStartsThePostProjectionRun()
    {
        List<CompEffect> effects = stack("blur", "am_bend", "s3d_cube", "am_color_gain", "am_glass");

        assertEquals(4, CompRenderer.firstProjectedEffect(effects));
        assertEquals(2, CompRenderer.firstProjectedEffect(stack("s3d_cube", "blur")));
        assertEquals(1, CompRenderer.firstProjectedEffect(stack("blur", "am_glass")));
        assertEquals(1, CompRenderer.firstProjectedEffect(stack("blur", "depth_fog")));
    }

    @Test
    public void disabledProjectedEffectsDoNotSplitTheStack()
    {
        CompEffect cube = effect("s3d_cube");

        cube.enabled = false;

        List<CompEffect> effects = stack("blur", "am_color_gain");

        effects.add(1, cube);

        assertEquals(effects.size(), CompRenderer.firstProjectedEffect(effects));
    }

    @Test
    public void theClassificationMatchesTheRenderingRules()
    {
        /* Mesh shape effects bake into the layer raster now: their pixels are layer-local, so
         * running them after projection was what misplaced them. */
        assertFalse(CompRenderer.requiresProjectedPass("s3d_cube"));
        assertTrue(CompRenderer.requiresProjectedPass("am_displace_map"));
        assertTrue(CompRenderer.requiresProjectedPass("depth_fog"));
        assertTrue(CompRenderer.requiresProjectedPass("shake"));
        assertFalse(CompRenderer.requiresProjectedPass("blur"));
        assertFalse(CompRenderer.requiresProjectedPass("am_bend"));
        assertFalse(CompRenderer.requiresProjectedPass(null));
    }
}
