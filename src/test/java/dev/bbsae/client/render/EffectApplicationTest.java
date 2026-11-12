package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
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

    /**
     * Effects outside the bake run on the pixels the pass holds. For a perspective 3D layer those
     * pixels are already camera-projected, so the pass must use composition space; using the
     * layer's world matrix looked at a different part of the plane (glass/displace map sampled
     * mirrored or empty regions — "the effect does nothing in 3D").
     */
    @Test
    public void projectedLayersRunTheirPostBakeEffectsInCompositionSpace()
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = 1920;
        comp.height = 1080;
        project.comps.add(comp);

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        CompLayer threeD = new CompLayer("card", "Card", "solid");

        threeD.threeD = true;

        CompLayer flat = new CompLayer("flat", "Flat", "solid");

        assertTrue(CompRenderer.effectsRunOnProjectedContent(context, threeD),
            "a 3D layer is baked and projected, so composition effects must use composition space");
        assertFalse(CompRenderer.effectsRunOnProjectedContent(context, flat),
            "a 2D layer is painted in composition space and keeps layer-space effect mapping");
    }
}
