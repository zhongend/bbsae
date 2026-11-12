package dev.bbsae.client.ui;

import dev.bbsae.comp.CompCurves;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-8: the curve graph's per-column sampling must not allocate.
 *
 * <p>The old path ({@code channel.valueAt()} → {@code KeyframeChannel.interpolate()}) creates a
 * {@code KeyframeSegment} and a boxed {@code Float} per sample. The new
 * {@link UICompCurveEditor.CurveSampler} walks the sorted key list itself with one reused segment.
 * These tests pin both halves of the contract: the values are the same as the old algorithm, and
 * repeated sampling allocates nothing.</p>
 */
class CurveSamplingPerfTest
{
    private static KeyframeChannel<Float> channel()
    {
        CompLayer layer = new CompLayer("curve_sampling", "Curve sampling", "solid");
        KeyframeChannel<Float> channel = layer.axisChannel(CompCurves.AXIS_X);

        /* Deliberately uneven ticks and values so every segment is distinguishable. */
        key(channel, 0F, 0F);
        key(channel, 10F, 100F);
        key(channel, 20F, 40F);
        key(channel, 33.5F, -25F);
        key(channel, 45F, 12F);

        return channel;
    }

    private static void key(KeyframeChannel<Float> channel, float tick, float value)
    {
        Keyframe<Float> key = channel.insertInheriting(tick, value);

        /* Non-default handles give the Bezier case a real curve instead of a straight line. */
        key.lx = 3.5F;
        key.ly = -6F;
        key.rx = 4.25F;
        key.ry = 7.5F;
    }

    private static void setInterpolation(KeyframeChannel<Float> channel, IInterp interpolation)
    {
        for (Keyframe<Float> key : channel.getKeyframes())
        {
            key.getInterpolation().setInterp(interpolation);
        }
    }

    @Test
    void samplerMatchesTheOldInterpolationPointForPoint()
    {
        IInterp[] interpolations = {
            Interpolations.LINEAR,
            Interpolations.BEZIER,
            Interpolations.AUTO,
            Interpolations.AUTO_CLAMPED,
            Interpolations.CUBIC,
            Interpolations.CONST
        };

        for (IInterp interpolation : interpolations)
        {
            KeyframeChannel<Float> channel = channel();

            setInterpolation(channel, interpolation);

            UICompCurveEditor.CurveSampler sampler = new UICompCurveEditor.CurveSampler();
            int revision = 7;

            for (float frame = -8F; frame <= 52F; frame += 0.05F)
            {
                float expected = channel.interpolate(frame, 0F);
                float actual = sampler.sampleFloat(channel, frame, 0F, revision);

                assertEquals(expected, actual, 1.0E-6F,
                    "interpolation " + interpolation.getKey() + " at frame " + frame);
            }

            assertEquals(0, sampler.fallbackSamples, "a loop-less float channel must use the fast path");
        }
    }

    @Test
    void twoSamplersAndTwoPassesProduceTheSameCurve()
    {
        KeyframeChannel<Float> channel = channel();

        setInterpolation(channel, Interpolations.BEZIER);

        UICompCurveEditor.CurveSampler first = new UICompCurveEditor.CurveSampler();
        UICompCurveEditor.CurveSampler second = new UICompCurveEditor.CurveSampler();
        int count = 1500;
        float[] a = new float[count];
        float[] b = new float[count];

        for (int i = 0; i < count; i++)
        {
            a[i] = first.sampleFloat(channel, i * 0.04F, 0F, 3);
            b[i] = second.sampleFloat(channel, i * 0.04F, 0F, 3);
        }

        assertArrayEquals(a, b, 0F, "a fresh sampler must reproduce the same values");
        assertArrayEquals(a, repeat(a.length, channel), 0F,
            "sampling the same input twice must return exactly the same doubles");

        assertTrue(first.cursorHits > 0, "left-to-right columns should reuse the segment cursor");
    }

    private static float[] repeat(int count, KeyframeChannel<Float> channel)
    {
        UICompCurveEditor.CurveSampler sampler = new UICompCurveEditor.CurveSampler();
        float[] values = new float[count];

        for (int i = 0; i < count; i++)
        {
            values[i] = sampler.sampleFloat(channel, i * 0.04F, 0F, 3);
        }

        return values;
    }

    @Test
    void cursorDoesNotOutliveExternalKeyMoves()
    {
        KeyframeChannel<Float> channel = channel();
        UICompCurveEditor.CurveSampler sampler = new UICompCurveEditor.CurveSampler();
        int revision = 5;

        float first = sampler.sampleFloat(channel, 15F, 0F, revision);

        assertEquals(channel.interpolate(15F, 0F), first, 1.0E-6F);

        /* A track drag shifts keys through CompLayer without bumping this channel's revision.
         * The cursor hint must notice that its lower-bound invariant no longer holds. */
        channel.moveX(100F);

        assertEquals(channel.interpolate(15F, 0F), sampler.sampleFloat(channel, 15F, 0F, revision), 1.0E-6F,
            "keys moved right: frame 15 is now before the first key");

        channel.moveX(-200F);

        assertEquals(channel.interpolate(-95F, 0F), sampler.sampleFloat(channel, -95F, 0F, revision), 1.0E-6F,
            "keys moved left: the cursor must not reuse a segment from the old timeline");
        assertEquals(channel.interpolate(15F, 0F), sampler.sampleFloat(channel, 15F, 0F, revision), 1.0E-6F,
            "frame 15 is now after the last key");
    }

    @Test
    void repeatedSamplingAllocatesNothing()
    {
        KeyframeChannel<Float> channel = channel();

        setInterpolation(channel, Interpolations.AUTO_CLAMPED);

        UICompCurveEditor.CurveSampler sampler = new UICompCurveEditor.CurveSampler();
        int count = 6000;
        float[] frames = new float[count];

        for (int i = 0; i < count; i++)
        {
            frames[i] = -5F + i * 0.01F;
        }

        /* Warm the JIT and every lazily-loaded BBS helper before measuring. */
        for (int pass = 0; pass < 3; pass++)
        {
            for (int i = 0; i < count; i++)
            {
                sampler.sampleFloat(channel, frames[i], 0F, 11);
            }
        }

        long before = allocatedBytes();

        Assumptions.assumeTrue(before >= 0L, "thread allocation counters are unavailable");

        for (int i = 0; i < count; i++)
        {
            sampler.sampleFloat(channel, frames[i], 0F, 11);
        }

        long allocated = allocatedBytes() - before;

        assertTrue(allocated < 64L * 1024L,
            "sampling " + count + " columns allocated " + allocated + " bytes; the old path allocated a"
                + " KeyframeSegment and a boxed Float per sample");
        assertEquals(1, sampler.segmentAllocations, "the sampler must reuse one segment for its lifetime");
        assertEquals(0, sampler.fallbackSamples, "no fallback samples are allowed for an unlooped float channel");
    }

    private static long allocatedBytes()
    {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();

        if (bean instanceof com.sun.management.ThreadMXBean sun)
        {
            return sun.getThreadAllocatedBytes(Thread.currentThread().getId());
        }

        return -1L;
    }

    @Test
    void wideGraphsAreDownsampledButCanBeSwitchedOff()
    {
        assertEquals(1, UICompCurveEditor.sampleStride(400, true));
        assertEquals(2, UICompCurveEditor.sampleStride(401, true));
        assertEquals(2, UICompCurveEditor.sampleStride(800, true));
        assertEquals(10, UICompCurveEditor.sampleStride(4000, true));
        assertEquals(1, UICompCurveEditor.sampleStride(4000, false), "the dev switch disables downsampling");

        for (int columns = 401; columns <= 8000; columns += 17)
        {
            int stride = UICompCurveEditor.sampleStride(columns, true);
            int evaluated = (columns + stride - 1) / stride;

            assertTrue(evaluated <= 401,
                columns + " columns evaluated " + evaluated + " times (stride " + stride + ")");
            assertEquals(1, UICompCurveEditor.sampleStride(columns, false));
        }
    }

    @Test
    void downsamplingFillsTheGapLinearly()
    {
        float[] samples = new float[8];

        UICompCurveEditor.fillColumns(samples, 1, 5, 10F, 50F);

        assertEquals(20F, samples[2], 0.0001F);
        assertEquals(30F, samples[3], 0.0001F);
        assertEquals(40F, samples[4], 0.0001F);
        assertEquals(50F, samples[5], 0.0001F);

        /* A NaN endpoint breaks the line instead of interpolating through it. */
        float[] broken = new float[4];

        UICompCurveEditor.fillColumns(broken, 0, 3, 0F, Float.NaN);

        for (int i = 1; i < broken.length; i++)
        {
            assertTrue(Float.isNaN(broken[i]), "a sampled NaN must stay a gap");
        }
    }
}
