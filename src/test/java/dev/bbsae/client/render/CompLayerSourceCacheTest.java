package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

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
