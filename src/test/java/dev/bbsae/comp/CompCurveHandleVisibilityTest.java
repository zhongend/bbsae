package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only handles that belong to a real Bezier segment may be drawn or grabbed. */
public class CompCurveHandleVisibilityTest
{
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Keyframe> keys(KeyframeChannel<Float> channel)
    {
        return (List<Keyframe>) (List<?>) channel.getKeyframes();
    }

    private static KeyframeChannel<Float> channel()
    {
        CompLayer layer = new CompLayer("curve_handles", "Curve handles", "solid");
        KeyframeChannel<Float> channel = layer.axisChannel(CompCurves.AXIS_X);

        channel.insert(0F, 0F);
        channel.insert(10F, 100F);
        channel.insert(20F, 50F);
        CompCurves.applyBezier(channel, keys(channel));

        return channel;
    }

    @Test
    public void endpointsDoNotOfferHandlesWithoutASegment()
    {
        KeyframeChannel<Float> channel = channel();

        assertFalse(CompCurves.hasBezierHandle(channel, keys(channel).get(0), false));
        assertTrue(CompCurves.hasBezierHandle(channel, keys(channel).get(0), true));
        assertTrue(CompCurves.hasBezierHandle(channel, keys(channel).get(2), false));
        assertFalse(CompCurves.hasBezierHandle(channel, keys(channel).get(2), true));
    }

    @Test
    public void incomingHandleBelongsToThePreviousKeysSegment()
    {
        KeyframeChannel<Float> channel = channel();
        List<Keyframe> keys = keys(channel);

        assertTrue(CompCurves.hasBezierHandle(channel, keys.get(1), false));

        CompCurves.applyLinear(channel, List.of(keys.get(0)));

        assertFalse(CompCurves.hasBezierHandle(channel, keys.get(1), false));
        assertTrue(CompCurves.hasBezierHandle(channel, keys.get(1), true));
    }

    @Test
    public void automaticAndSingleKeysDoNotExposeStaleHandles()
    {
        KeyframeChannel<Float> channel = channel();
        List<Keyframe> keys = keys(channel);

        CompCurves.applyAuto(channel, List.of(keys.get(1)), true);

        assertFalse(CompCurves.hasBezierHandle(channel, keys.get(1), true));

        CompLayer layer = new CompLayer("single_key", "Single key", "solid");
        KeyframeChannel<Float> single = layer.axisChannel(CompCurves.AXIS_X);
        Keyframe<Float> key = single.insertInheriting(0F, 1F);

        key.getInterpolation().setInterp(mchorse.bbs_mod.utils.interps.Interpolations.BEZIER);

        assertFalse(CompCurves.hasBezierHandle(single, key, true));
        assertFalse(CompCurves.hasBezierHandle(single, key, false));
    }
}
