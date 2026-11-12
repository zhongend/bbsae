package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Curve edits must reach the value the renderer reads.
 *
 * <p>Dragging a keyframe of {@code fx/<id>/<param>} in the curve editor used to update only the
 * keyframe: {@link CompLayer#putScalar(String, float)} is a no-op for {@code fx/} paths (the
 * parameter lives in {@link CompEffect#params}), so the effect kept rendering the old base value
 * and removing the last key snapped the picture back to it. Mask and shape paths are supported by
 * {@code putScalar()} and are covered here as a regression guard.</p>
 */
public class CompCurveChannelFxWritebackTest
{
    private static CompCurveChannel channel(CompLayer layer, String path)
    {
        for (CompCurveChannel channel : CompCurveChannel.of(layer))
        {
            if (path.equals(channel.path))
            {
                return channel;
            }
        }

        return null;
    }

    private static CompLayer layerWithBlur()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        CompEffect effect = new CompEffect("fx1", "blur");

        effect.put("amount", 5D);
        layer.effects.add(effect);
        layer.scalarChannel("fx/fx1/amount").insertInheriting(0F, 5F);

        return layer;
    }

    @Test
    public void insertMirrorsTheKeyValueIntoTheEffectParameter()
    {
        CompLayer layer = layerWithBlur();
        CompEffect effect = layer.getEffect("fx1");
        CompCurveChannel channel = channel(layer, "fx/fx1/amount");

        assertNotNull(channel);

        channel.insert(10F, 42F);

        assertEquals(42F, effect.valueF("amount", -1F), 1e-5F,
            "the effect parameter must follow the inserted key");
        assertEquals(42F, layer.effectValue(effect, "amount", -1F, 10F), 1e-5F,
            "the renderer reads the same value the curve shows");
    }

    @Test
    public void setKeyValueMirrorsTheDraggedValueIntoTheEffectParameter()
    {
        CompLayer layer = layerWithBlur();
        CompEffect effect = layer.getEffect("fx1");
        CompCurveChannel channel = channel(layer, "fx/fx1/amount");

        assertNotNull(channel);

        Keyframe<?> key = channel.insert(10F, 42F);

        channel.setKeyValue(key, 7F);

        assertEquals(7F, effect.valueF("amount", -1F), 1e-5F,
            "dragging the point must write the effect parameter, not only the keyframe");

        /* Removing every key keeps the last dragged value as the base: the bug report's symptom was
         * the parameter jumping back to the pre-animation value (5). */
        channel.removeAll();

        assertFalse(channel.hasKeys());
        assertEquals(7F, effect.valueF("amount", -1F), 1e-5F,
            "clearing the curve must not restore a stale base value");
    }

    @Test
    public void anFxChannelForADeletedEffectStillKeysWithoutThrowing()
    {
        CompLayer layer = new CompLayer("orphan", "Orphan", "solid");

        layer.scalarChannel("fx/fx9/amount").insertInheriting(0F, 1F);

        CompCurveChannel channel = channel(layer, "fx/fx9/amount");

        assertNotNull(channel);
        channel.insert(1F, 3F);

        assertEquals(3F, layer.scalarAt("fx/fx9/amount", 0F, 1F), 1e-5F,
            "the key itself is still stored even when the effect was deleted");
    }

    @Test
    public void maskAndShapeChannelsStillWriteTheirBaseProperties()
    {
        CompLayer layer = new CompLayer("layer2", "Layer", "solid");
        CompMask mask = new CompMask("m1");
        CompShape shape = new CompShape("s1", "rect");

        mask.feather = 1F;
        shape.x = 0F;
        layer.masks.add(mask);
        layer.shapes.add(shape);
        layer.scalarChannel("mask/m1/feather").insertInheriting(0F, 1F);
        layer.scalarChannel("shape/s1/x").insertInheriting(0F, 0F);

        CompCurveChannel feather = channel(layer, "mask/m1/feather");
        CompCurveChannel shapeX = channel(layer, "shape/s1/x");

        assertNotNull(feather);
        assertNotNull(shapeX);

        feather.insert(5F, 9F);
        shapeX.insert(5F, 20F);

        assertEquals(9F, mask.feather, 1e-5F, "putScalar supports mask feather");
        assertEquals(20F, shape.x, 1e-5F, "putScalar supports shape position");
    }
}
