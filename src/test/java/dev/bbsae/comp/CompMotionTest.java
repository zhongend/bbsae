package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Motion templates must be relative to the layer's in point, not to comp time 0. A clip that is
 * moved after the preset was applied would otherwise animate before it is even visible.
 */
public class CompMotionTest
{
    private static CompDocument document()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 20F;
        doc.duration = 10F;

        return doc;
    }

    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("layer", "Title", "solid");

        layer.in = 2F;
        layer.out = 6F;
        layer.x = 960F;
        layer.y = 540F;
        layer.width = 400F;
        layer.height = 200F;

        return layer;
    }

    @Test
    public void fadeInWritesKeysAtTheInPoint()
    {
        CompLayer layer = layer();

        assertTrue(CompMotion.apply(layer, document(), "fade_in"));

        var keys = layer.opacityChannel().getKeyframes();

        assertEquals(2, keys.size(), "fade in writes exactly two keys");
        assertEquals(40F, keys.get(0).getTick(), 0.001F, "first key at in point (2s * 20fps)");
        assertEquals(0F, keys.get(0).getValue(), 0.001F);
        assertEquals(1F, keys.get(1).getValue(), 0.001F);
        assertTrue(keys.get(1).getTick() > keys.get(0).getTick());
    }

    @Test
    public void slideStartsOffCentreAndEndsAtTheLayerTransform()
    {
        CompLayer layer = layer();

        assertTrue(CompMotion.apply(layer, document(), "slide_left"));

        var keys = layer.transformChannel().getKeyframes();

        assertEquals(2, keys.size());
        assertEquals(40F, keys.get(0).getTick(), 0.001F);

        Transform start = (Transform) keys.get(0).getValue();
        Transform end = (Transform) keys.get(1).getValue();

        assertTrue(start.translate.x < end.translate.x, "left slide must start left of the final x");
        assertEquals(960F, end.translate.x, 0.001F, "final key keeps the layer's original x");
        assertEquals(540F, end.translate.y, 0.001F, "final key keeps the layer's original y");
    }

    @Test
    public void popKeepsThreeKeysAndEndsAtBaseScale()
    {
        CompLayer layer = layer();

        assertTrue(CompMotion.apply(layer, document(), "pop"));

        var keys = layer.transformChannel().getKeyframes();

        assertEquals(3, keys.size());

        Transform start = (Transform) keys.get(0).getValue();
        Transform peak = (Transform) keys.get(1).getValue();
        Transform end = (Transform) keys.get(2).getValue();

        assertTrue(start.scale.x < 1F, "pop starts small");
        assertTrue(peak.scale.x > 1F, "pop overshoots");
        assertEquals(1F, end.scale.x, 0.001F);
    }

    @Test
    public void unknownTemplateLeavesTheLayerAlone()
    {
        CompLayer layer = layer();

        assertFalse(CompMotion.apply(layer, document(), "does_not_exist"));
        assertTrue(layer.transformChannel().getKeyframes().isEmpty());
        assertTrue(layer.opacityChannel().getKeyframes().isEmpty());
    }
}
