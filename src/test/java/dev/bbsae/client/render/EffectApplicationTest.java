package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where each effect runs, relative to the layer-space bake.
 *
 * <p>3D layers bake their non-depth effects into the source raster before projection. The
 * composition loop must then skip them; running them again is the double-application bug
 * (two blurs, two brightness passes, a composition-space gradient replacing the layer-anchored
 * one). Depth effects are the exception: they need the world depth buffer and run after the
 * projection. When the raster could not be built, the loop is the fallback and must run them.</p>
 */
public class EffectApplicationTest
{
    private static CompEffect effect(String type)
    {
        return new CompEffect("fx", type);
    }

    @Test
    public void bakedNonDepthEffectsAreNotAppliedAgain()
    {
        assertFalse(CompRenderer.needsCompositionEffect(true, effect("blur")));
        assertFalse(CompRenderer.needsCompositionEffect(true, effect("gradient_ramp")));
        assertFalse(CompRenderer.needsCompositionEffect(true, effect("brightness_contrast")));
        assertFalse(CompRenderer.needsCompositionEffect(true, effect("stroke")));
    }

    @Test
    public void depthEffectsRunAfterProjectionEvenWhenTheRestIsBaked()
    {
        assertTrue(CompRenderer.needsCompositionEffect(true, effect("depth_fog")));
        assertTrue(CompRenderer.needsCompositionEffect(true, effect("depth_dof")));
        assertTrue(CompRenderer.needsCompositionEffect(true, effect("depth_matte")));
    }

    @Test
    public void aKeyframedStrengthIsResolvedEvenWithoutABaseValue()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        CompEffect effect = new CompEffect("fx1", "blur");

        layer.effects.add(effect);
        layer.scalarChannel(CompLayer.effectPath("fx1", CompEffect.STRENGTH)).insertInheriting(0F, 0.25F);

        CompEffect resolved = layer.resolveEffect(effect, 0F);

        assertEquals(0.25F, resolved.valueF(CompEffect.STRENGTH, 1F), 1e-5F,
            "a channel must animate even when the parameter is not in the saved map");
    }

    @Test
    public void fallbackRunsEveryEnabledEffectOnce()
    {
        assertTrue(CompRenderer.needsCompositionEffect(false, effect("blur")));
        assertTrue(CompRenderer.needsCompositionEffect(false, effect("depth_fog")));
        assertFalse(CompRenderer.needsCompositionEffect(false, null));
    }
}
