package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curve channel revision counter.
 *
 * <p>The curve editor caches per-column geometry and invalidates it from this counter instead of
 * hashing every keyframe each frame. If a write path forgets to bump it, the graph keeps drawing
 * the old shape — so every write path is asserted here.</p>
 */
public class CompCurveChannelRevisionTest
{
    private static CompCurveChannel channel(CompLayer layer, String id)
    {
        for (CompCurveChannel channel : CompCurveChannel.of(layer))
        {
            if (channel.id.equals(id))
            {
                return channel;
            }
        }

        return null;
    }

    @Test
    public void everyWritePathBumpsTheRevision()
    {
        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");

        layer.x = 100F;
        layer.y = 100F;

        CompCurveChannel x = channel(layer, "transform.x");

        assertNotNull(x);

        int initial = x.revision();

        Keyframe<?> key = x.insert(10F, 200F);

        assertNotNull(key);
        assertTrue(x.revision() > initial, "insert did not bump the revision");

        int afterInsert = x.revision();

        x.setKeyValue(key, 300F);
        assertTrue(x.revision() > afterInsert, "setKeyValue did not bump the revision");

        int afterValue = x.revision();

        x.remove(key);
        assertTrue(x.revision() > afterValue, "remove did not bump the revision");

        int afterRemove = x.revision();

        x.insert(5F, 1F);
        x.removeAll();
        assertTrue(x.revision() > afterRemove, "removeAll did not bump the revision");
    }

    @Test
    public void scalarChannelsTrackTheirOwnRevision()
    {
        CompLayer layer = new CompLayer("layer_2", "Layer", "solid");

        layer.putScalar("opacity", 1F);

        CompCurveChannel opacity = channel(layer, "opacity");

        assertNotNull(opacity);

        int initial = opacity.revision();

        opacity.insert(1F, 0.5F);

        assertTrue(opacity.revision() > initial);
    }

    @Test
    public void sharedFallbackTransformDoesNotChangeTheValue()
    {
        CompLayer layer = new CompLayer("layer_3", "Layer", "solid");

        layer.x = 640F;
        layer.y = 360F;

        CompCurveChannel x = channel(layer, "transform.x");

        assertNotNull(x);

        /* No keys: both overloads must fall back to the layer's base transform. */
        assertEquals(640F, x.valueAt(0F), 0.001F);
        assertEquals(640F, x.valueAt(0F, layer.defaultTransform()), 0.001F);
    }
}
