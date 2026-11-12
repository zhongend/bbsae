package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Cache-identity tests for {@link MaskCache}: the content key must be world-free and stable for a
 * static mask, while an animated mask has to produce a different key at every distinct frame.
 *
 * <p>This is the CPU half of the P0-4 fix ("camera moves -&gt; every mask misses"). The old key
 * contained {@code hash(world)}, so a 3D camera invalidated all masks every frame; the resolved
 * mask stack is now identified by its own values and the world only decides whether the texture
 * slot has to be re-uploaded.</p>
 */
public class MaskCacheKeyTest
{
    private static CompLayer layer(CompMask... masks)
    {
        CompLayer layer = new CompLayer("mask_key", "Mask Key", "solid");

        layer.width = 1920F;
        layer.height = 1080F;

        for (CompMask mask : masks)
        {
            layer.masks.add(mask);
        }

        return layer;
    }

    private static CompMask rect(String id)
    {
        CompMask mask = new CompMask(id);

        mask.rectangle(200F, 200F, 400F, 300F);

        return mask;
    }

    @Test
    public void staticMaskKeepsOneKeyFor120Frames()
    {
        CompLayer layer = layer(rect("m"));
        long key = MaskCache.contentKey(layer, 0F, 1F);

        for (int frame = 1; frame <= 120; frame++)
        {
            assertEquals(key, MaskCache.contentKey(layer, frame / 30F, 1F),
                "a static mask must keep its content key at frame " + frame);
        }
    }

    @Test
    public void worldChangesDoNotEnterTheContentKeyOrTheSlot()
    {
        CompLayer layer = layer(rect("m"));
        Matrix4f first = new Matrix4f().translate(10F, 20F, 0F);
        Matrix4f second = new Matrix4f().translate(600F, 400F, 0F).rotateZ(0.3F);

        assertEquals(MaskCache.contentKey(layer, 0F, 1F), MaskCache.contentKey(layer, 0F, 1F),
            "the content key is world-free by construction");
        assertEquals(MaskCache.textureSlot(layer, 640, 360), MaskCache.textureSlot(layer, 640, 360),
            "a layer+size maps to one reusable texture slot regardless of its transform");

        assertNotEquals(
            MaskCache.rasterState(layer, 640, 360, first, 0F, 1F, false),
            MaskCache.rasterState(layer, 640, 360, second, 0F, 1F, false),
            "a changed world must still re-upload the pixels into the slot");
    }

    @Test
    public void animatedMaskChangesItsKeyPerFrame()
    {
        CompLayer layer = layer(rect("m"));

        layer.scalarChannel("mask/m/p/0/x").insertInheriting(0F, 200F);
        layer.scalarChannel("mask/m/p/0/x").insertInheriting(12F, 300F);

        long start = MaskCache.contentKey(layer, 0F, 1F);

        assertNotEquals(start, MaskCache.contentKey(layer, 6F, 1F),
            "an interpolated mask point is different content");
        assertNotEquals(start, MaskCache.contentKey(layer, 12F, 1F));
        assertEquals(MaskCache.contentKey(layer, 6F, 1F), MaskCache.contentKey(layer, 6F, 1F),
            "the same frame twice is the same content");
    }

    @Test
    public void featherAndExpansionArePartOfTheKey()
    {
        CompLayer layer = layer(rect("m"));
        long key = MaskCache.contentKey(layer, 0F, 1F);

        layer.masks.get(0).feather = 8F;
        long feathered = MaskCache.contentKey(layer, 0F, 1F);

        assertNotEquals(key, feathered, "feather must invalidate the cached raster");

        layer.masks.get(0).expansion = 4F;
        assertNotEquals(feathered, MaskCache.contentKey(layer, 0F, 1F),
            "expansion must invalidate the cached raster");
    }

    @Test
    public void closingThePathInvalidatesTheKey()
    {
        CompLayer layer = layer(rect("m"));
        long closed = MaskCache.contentKey(layer, 0F, 1F);

        layer.masks.get(0).closed = false;

        assertNotEquals(closed, MaskCache.contentKey(layer, 0F, 1F),
            "an open path must not reuse the closed mask's texture");
    }

    @Test
    public void rasterScaleOnlyChangesTheKeyWhenFeatherOrExpansionIsAuthored()
    {
        CompLayer layer = layer(rect("m"));

        /* No feather, no expansion: feather/expansion are the only scale-dependent inputs, so the
         * rasterized content is identical and a preview/export size change must still hit. */
        assertEquals(MaskCache.contentKey(layer, 0F, 1F), MaskCache.contentKey(layer, 0F, 2F));

        layer.masks.get(0).feather = 4F;

        assertNotEquals(MaskCache.contentKey(layer, 0F, 1F), MaskCache.contentKey(layer, 0F, 2F),
            "a scaled feather is a different raster");
    }
}
