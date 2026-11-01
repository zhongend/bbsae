package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The raster margin must cover the effect kernel, otherwise a glow/shadow is clipped at the layer
 * box in the 3D path. These are upper bounds: extra transparent pixels are harmless.
 */
public class EffectPaddingTest
{
    private static CompLayer layer()
    {
        return new CompLayer("layer", "Layer", "solid");
    }

    @Test
    public void blurAndGlowReserveTheirRadius()
    {
        CompLayer layer = layer();
        CompEffect blur = new CompEffect("fx1", "blur");
        blur.put("radius", 20D);
        layer.effects.add(blur);

        assertEquals(22F, EffectPadding.of(layer, 0F), 0.01F);

        layer.effects.clear();

        CompEffect glow = new CompEffect("fx2", "glow");
        glow.put("radius", 40D);
        layer.effects.add(glow);

        assertEquals(42F, EffectPadding.of(layer, 0F), 0.01F);
    }

    @Test
    public void stylesReserveShadowAndGlowSpace()
    {
        CompLayer layer = layer();
        CompStyle shadow = new CompStyle(CompStyle.DROP_SHADOW);

        shadow.distance = 30F;
        shadow.blur = 10F;
        layer.styles.add(shadow);

        assertEquals(42F, EffectPadding.of(layer, 0F), 0.01F);

        layer.styles.clear();

        CompStyle glow = new CompStyle(CompStyle.OUTER_GLOW);

        glow.size = 24F;
        layer.styles.add(glow);

        assertEquals(26F, EffectPadding.of(layer, 0F), 0.01F);
    }

    @Test
    public void neutralOrDisabledEffectsNeedNoMargin()
    {
        CompLayer layer = layer();
        CompEffect blur = new CompEffect("fx1", "blur");

        blur.put("radius", 0D);
        layer.effects.add(blur);

        assertEquals(0F, EffectPadding.of(layer, 0F), 0.01F);

        blur.put("radius", 50D);
        blur.put(CompEffect.STRENGTH, 0D);

        assertEquals(0F, EffectPadding.of(layer, 0F), 0.01F);

        blur.enabled = false;

        assertEquals(0F, EffectPadding.of(layer, 0F), 0.01F);
    }

    @Test
    public void paddingIsCapped()
    {
        CompLayer layer = layer();
        CompEffect blur = new CompEffect("fx1", "blur");

        blur.put("radius", 600D);
        layer.effects.add(blur);

        assertEquals(512F, EffectPadding.of(layer, 0F), 0.01F);
    }
}
