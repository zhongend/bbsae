package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A replay entity layer's matte is composition-space; it must never enter the layer-local bake. */
public class EntityLayerBakeTest
{
    private static CompLayer layer(String type, boolean threeD)
    {
        CompLayer layer = new CompLayer(type, type, type);

        layer.threeD = threeD;

        return layer;
    }

    @Test
    public void replayEntityLayersAreNeverBakedLocally()
    {
        CompLayer replay = layer("replay", true);

        replay.effects.add(CompEffectRegistry.create("s3d_cube", 1));

        assertFalse(CompRenderer.shouldBake3D(replay));
    }

    @Test
    public void aNormal3DShapeStillBakesItsEffect()
    {
        CompLayer shape = layer("shape", true);

        shape.effects.add(CompEffectRegistry.create("s3d_cube", 1));

        assertTrue(CompRenderer.shouldBake3D(shape));
    }

    @Test
    public void a2DLayerNeverBakesAsA3DLayer()
    {
        CompLayer shape = layer("shape", false);

        shape.effects.add(CompEffectRegistry.create("s3d_cube", 1));

        assertFalse(CompRenderer.shouldBake3D(shape));
    }
}
