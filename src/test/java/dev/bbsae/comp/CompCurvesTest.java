package dev.bbsae.comp;

import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curve editor is only trustworthy if the curve it draws is the curve that plays. These tests
 * pin the two things that used to make that impossible: BBS's Transform channel interpolates the
 * whole vector with one interpolation (ignoring per-key handles), and "easy ease" has to actually
 * change the sampled values.
 */
public class CompCurvesTest
{
    private static CompLayer animatedLayer()
    {
        CompLayer layer = new CompLayer("layer", "Title", "solid");

        layer.x = 0F;
        layer.y = 0F;

        KeyframeChannel<Transform> channel = layer.transformChannel();
        Transform a = new Transform();
        Transform b = new Transform();

        a.translate.set(0F, 0F, 0F);
        b.translate.set(100F, 0F, 0F);

        channel.insert(0F, a);
        channel.insert(10F, b);

        return layer;
    }

    @Test
    public void linearTransformSamplesLinearly()
    {
        CompLayer layer = animatedLayer();

        assertEquals(25F, layer.transformAt(2.5F).translate.x, 0.01F);
        assertEquals(50F, layer.transformAt(5F).translate.x, 0.01F);
    }

    @Test
    public void transformHonoursBezierHandles()
    {
        CompLayer layer = animatedLayer();
        List<Keyframe> keys = keys(layer);

        CompCurves.applyEase(layer.transformChannel(), keys, CompCurves.EASE_BOTH, CompCurves.DEFAULT_INFLUENCE);

        /* Easy ease: slow at both ends, symmetric at the middle, so the quarter point must sit
         * clearly below the linear 25 and the midpoint must stay at 50. */
        float quarter = layer.transformAt(2.5F).translate.x;
        float middle = layer.transformAt(5F).translate.x;

        assertTrue(quarter < 24F, "quarter point should lag a linear curve, was " + quarter);
        assertEquals(50F, middle, 0.5F);
        assertTrue(layer.transformAt(9.5F).translate.x > 76F);
    }

    @Test
    public void easeHandlesCoverAThirdOfTheSegment()
    {
        CompLayer layer = animatedLayer();
        List<Keyframe> keys = keys(layer);

        CompCurves.applyEase(layer.transformChannel(), keys, CompCurves.EASE_BOTH, CompCurves.DEFAULT_INFLUENCE);

        Keyframe first = keys.get(0);

        assertEquals(CompCurves.DEFAULT_INFLUENCE, first.rx / 10F, 0.0001F);
        assertEquals(0F, first.ry, 0.0001F);
        assertEquals(CompCurves.DEFAULT_INFLUENCE, keys.get(1).lx / 10F, 0.0001F);
        assertTrue(first.getInterpolation().has(Interpolations.BEZIER));
    }

    @Test
    public void holdFreezesUntilTheNextKey()
    {
        CompLayer layer = animatedLayer();
        List<Keyframe> keys = keys(layer);

        CompCurves.applyHold(layer.transformChannel(), keys);

        assertEquals(0F, layer.transformAt(0.1F).translate.x, 0.001F);
        assertEquals(0F, layer.transformAt(9.9F).translate.x, 0.001F);
        assertEquals(100F, layer.transformAt(10F).translate.x, 0.001F);
    }

    @Test
    public void transformAtDoesNotLeakTheFactorysSharedInstance()
    {
        CompLayer one = animatedLayer();
        CompLayer two = new CompLayer("two", "Two", "solid");

        two.x = 500F;
        two.y = 500F;

        Transform a = new Transform();
        Transform b = new Transform();

        a.translate.set(500F, 500F, 0F);
        b.translate.set(600F, 500F, 0F);
        two.transformChannel().insert(0F, a);
        two.transformChannel().insert(10F, b);

        Transform first = one.transformAt(5F);

        two.transformAt(5F);

        assertEquals(50F, first.translate.x, 0.01F);
        assertEquals(50F, one.transformAt(5F).translate.x, 0.01F);
    }

    @Test
    public void curveChannelAdapterReadsAndWritesComponents()
    {
        CompLayer layer = animatedLayer();
        List<CompCurveChannel> channels = CompCurveChannel.of(layer);
        CompCurveChannel x = channels.get(0);

        assertEquals("transform.x", x.id);
        assertEquals(50F, x.valueAt(5F), 0.01F);

        List<CompCurveChannel.Key> keys = x.keys();

        assertEquals(2, keys.size());

        x.setKeyValue(keys.get(1).raw, 240F);

        assertEquals(240F, layer.transformAt(10F).translate.x, 0.01F);
        assertEquals(240F, layer.x, 0.01F);
    }

    @SuppressWarnings("unchecked")
    private static List<Keyframe> keys(CompLayer layer)
    {
        return new ArrayList<>((List<Keyframe>) (List<?>) layer.transformChannel().getKeyframes());
    }
}
