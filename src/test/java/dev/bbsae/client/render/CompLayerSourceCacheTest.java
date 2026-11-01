package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The viewport/3D source raster is cached by a content fingerprint. Effects are baked into that
 * raster, so their identity, order, enabled state and resolved values must be part of the
 * fingerprint; otherwise every 3D-layer effect appears dead until unrelated content changes.
 */
public class CompLayerSourceCacheTest
{
    private static CompRenderContext context(CompDocument doc)
    {
        return new CompRenderContext(new CompProject(), doc, 0F, null, null, null);
    }

    @Test
    public void effectChangesInvalidateTheLayerRaster()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = new CompLayer("card", "Card", "solid");

        layer.width = 400F;
        layer.height = 200F;
        doc.layers.add(layer);

        CompRenderContext context = context(doc);
        long plain = CompLayerSourceCache.signature(layer, context, 400, 200);

        CompEffect blur = CompEffectRegistry.create("blur", 1);
        layer.effects.add(blur);

        long withBlur = CompLayerSourceCache.signature(layer, context, 400, 200);
        assertNotEquals(plain, withBlur, "adding an effect must rebuild the baked raster");

        blur.params.put("radius", 55.0);
        long changedRadius = CompLayerSourceCache.signature(layer, context, 400, 200);
        assertNotEquals(withBlur, changedRadius, "changing a parameter must rebuild the baked raster");

        blur.enabled = false;
        long disabled = CompLayerSourceCache.signature(layer, context, 400, 200);
        assertNotEquals(changedRadius, disabled, "disabling an effect must rebuild the baked raster");
    }

    @Test
    public void styleChangesInvalidateTheLayerRaster()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = new CompLayer("card", "Card", "solid");
        dev.bbsae.comp.CompStyle shadow = new dev.bbsae.comp.CompStyle(dev.bbsae.comp.CompStyle.DROP_SHADOW);

        doc.layers.add(layer);
        layer.styles.add(shadow);

        CompRenderContext context = context(doc);
        long before = CompLayerSourceCache.signature(layer, context, 400, 200);

        shadow.blur = 24F;
        long changed = CompLayerSourceCache.signature(layer, context, 400, 200);

        assertNotEquals(before, changed, "a baked style must be part of the raster fingerprint");
    }

    @Test
    public void rasterScaleConvertsPixelRadiiWithoutMutatingTheSourceEffect()
    {
        CompEffect blur = new CompEffect("fx", "blur");

        blur.put("radius", 40D);

        CompEffect scaled = CompLayerSourceCache.scaleEffectForRaster(blur, 0.5F);

        assertEquals(20F, scaled.valueF("radius", 0F), 1e-4F);
        assertEquals(40F, blur.valueF("radius", 0F), 1e-4F, "the saved effect must not be mutated");

        CompEffect shadow = new CompEffect("fx2", "drop_shadow");

        shadow.put("distance", 20D);
        shadow.put("softness", 8D);

        CompEffect scaledShadow = CompLayerSourceCache.scaleEffectForRaster(shadow, 0.5F);

        assertEquals(10F, scaledShadow.valueF("distance", 0F), 1e-4F);
        assertEquals(4F, scaledShadow.valueF("softness", 0F), 1e-4F);
    }

    @Test
    public void rasterScaleConvertsStyleSizes()
    {
        dev.bbsae.comp.CompStyle style = new dev.bbsae.comp.CompStyle(dev.bbsae.comp.CompStyle.DROP_SHADOW);

        style.size = 10F;
        style.distance = 20F;
        style.blur = 8F;

        dev.bbsae.comp.CompStyle scaled = CompLayerSourceCache.scaleStyleForRaster(style, 0.5F);

        assertEquals(5F, scaled.size, 1e-4F);
        assertEquals(10F, scaled.distance, 1e-4F);
        assertEquals(4F, scaled.blur, 1e-4F);
        assertEquals(10F, style.size, 1e-4F);
    }

    @Test
    public void maskChangesInvalidateTheLayerRaster()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = new CompLayer("card", "Card", "solid");
        dev.bbsae.comp.CompMask mask = new dev.bbsae.comp.CompMask("m");

        mask.add(0F, 0F).add(100F, 0F).add(100F, 100F);
        doc.layers.add(layer);
        layer.masks.add(mask);

        CompRenderContext context = context(doc);
        long before = CompLayerSourceCache.signature(layer, context, 400, 200);

        mask.feather = 24F;
        long changed = CompLayerSourceCache.signature(layer, context, 400, 200);

        assertNotEquals(before, changed, "a baked mask must be part of the raster fingerprint");
    }

    @Test
    public void effectOrderAndColorChangesInvalidateTheLayerRaster()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = new CompLayer("card", "Card", "solid");
        CompEffect tint = CompEffectRegistry.create("tint", 1);
        CompEffect glow = CompEffectRegistry.create("glow", 2);

        doc.layers.add(layer);
        layer.effects.add(tint);
        layer.effects.add(glow);

        CompRenderContext context = context(doc);
        long before = CompLayerSourceCache.signature(layer, context, 400, 200);

        tint.colors.put("color", 0xFF00FF00);
        long color = CompLayerSourceCache.signature(layer, context, 400, 200);
        assertNotEquals(before, color, "a colour parameter must rebuild the baked raster");

        layer.effects.add(0, layer.effects.remove(1));
        long reordered = CompLayerSourceCache.signature(layer, context, 400, 200);
        assertNotEquals(color, reordered, "effect order must rebuild the baked raster");
    }
}
