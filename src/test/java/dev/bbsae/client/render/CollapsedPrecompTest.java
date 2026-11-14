package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collapse Transformations must be a projection decision, not a pixel-operation side effect.
 *
 * <p>Adding any effect/mask/style to a collapsed precomp used to silently disable collapse, which
 * changed the nested composition's camera and made the precomp visibly jump in size. These tests
 * pin both halves: the camera choice depends only on the switch, and the layer-local bake is
 * skipped for a collapsed precomp so its pixel ops run on the composed parent-space image.</p>
 */
public class CollapsedPrecompTest
{
    private static CompLayer precomp()
    {
        CompLayer layer = new CompLayer("pc", "Precomp", "precomp");

        layer.threeD = true;
        layer.precompId = "child";
        layer.out = 12F;

        return layer;
    }

    @Test
    public void collapseIsOffByDefault()
    {
        assertFalse(LayerContentRenderer.usesParentCameraForPrecomp(precomp()));
    }

    @Test
    public void addingPixelOperationsDoesNotChangeTheNestedCamera()
    {
        CompLayer layer = precomp();

        layer.collapse = true;

        assertTrue(LayerContentRenderer.usesParentCameraForPrecomp(layer));

        layer.effects.add(new CompEffect("fx1", "glow"));
        assertTrue(LayerContentRenderer.usesParentCameraForPrecomp(layer),
            "an effect must not flip the child's projection");

        layer.masks.add(new CompMask("mask1"));
        assertTrue(LayerContentRenderer.usesParentCameraForPrecomp(layer));

        layer.styles.add(new CompStyle("drop_shadow"));
        assertTrue(LayerContentRenderer.usesParentCameraForPrecomp(layer));
    }

    @Test
    public void aCollapsedPrecompSkipsTheLayerLocalBake()
    {
        CompLayer layer = precomp();

        layer.effects.add(new CompEffect("fx1", "glow"));

        assertTrue(CompRenderer.shouldBake3D(layer),
            "a normal 3D layer with an effect bakes in layer space");

        layer.collapse = true;

        assertFalse(CompRenderer.shouldBake3D(layer),
            "a collapsed precomp applies its effects to the composed parent-space image instead");
    }

    @Test
    public void aFlattenedPrecompStillBakes()
    {
        CompLayer layer = precomp();

        layer.effects.add(new CompEffect("fx1", "glow"));

        assertFalse(layer.collapse);
        assertTrue(CompRenderer.shouldBake3D(layer));
    }
}
