package dev.bbsae.client.ui;

import dev.bbsae.comp.CompCurves;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.utils.pose.Transform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph mapping, verified without a game.
 *
 * <p>Reproduces the geometry a screenshot came from: three eased position keys, and a mapping from
 * screen column to composition frame. The invariants here are the ones the drawing code depends
 * on:</p>
 * <ol>
 *   <li>every column is sampled (a hole is a visible gap in the line);</li>
 *   <li>the sampled value is monotonic inside a segment — an oscillating value is what a broken
 *       cache or a mis-sorted channel looks like;</li>
 *   <li>past the last key the value holds, it does not extrapolate;</li>
 *   <li>the stored span is the sampled Y minus the graph origin, once.</li>
 * </ol>
 */
public class CurveGeometryReproTest
{
    private static final int Y = 284;
    private static final int W = 810;
    private static final int H = 218;
    private static final float FPS = 60F;

    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("motion_card", "Motion Card", "shape");

        layer.x = 576F;
        layer.y = 594F;

        float[][] keys = {{0F, 380F, 620F}, {36F, 960F, 400F}, {72F, 1500F, 720F}};

        for (float[] key : keys)
        {
            Transform transform = layer.defaultTransform().copy();

            transform.translate.x = key[1];
            transform.translate.y = key[2];
            layer.transformChannel().insert(key[0], transform);
        }

        CompCurves.applyEase(layer.transformChannel(), (List) layer.transformChannel().getKeyframes(),
            CompCurves.EASE_BOTH, CompCurves.DEFAULT_INFLUENCE);

        return layer;
    }

    private static float frameAt(int column, double viewDuration)
    {
        double pxPerSecond = W / viewDuration;

        return (float) (column / pxPerSecond) * FPS;
    }

    private static float valueAt(CompLayer layer, float frame)
    {
        return CompCurves.transformValue(layer.transformChannel(), CompCurves.AXIS_Y, frame,
            layer.defaultTransform().translate.y);
    }

    @Test
    public void sampledValuesAreMonotonicInsideSegmentsAndFlatAfterTheLastKey()
    {
        CompLayer layer = layer();
        float previous = Float.NaN;

        for (int column = 0; column < W; column++)
        {
            float frame = frameAt(column, 12D);
            float value = valueAt(layer, frame);

            if (!Float.isNaN(previous))
            {
                float delta = value - previous;

                if (Math.abs(delta) > 0.001F)
                {
                    /* First segment falls (620 → 400), second rises (400 → 720), then it is flat. */
                    /* Inclusive upper bounds: the sample that lands exactly on a key still belongs
                     * to the segment that ends there. */
                    int expected = frame < 36F ? -1 : (frame <= 72F ? 1 : 0);
                    int direction = delta > 0F ? 1 : -1;

                    assertEquals(expected, direction, "value direction at frame " + frame);
                }
            }

            previous = value;
        }

        assertEquals(720F, previous, 0.01F, "past the last key the curve must hold its last value");
    }

    @Test
    public void everyColumnIsSampledAndSpansCarryTheSampledY()
    {
        CompLayer layer = layer();
        double viewDuration = 12D;
        float axisMin = 350F;
        float axisMax = 800F;
        double pxPerSecond = W / viewDuration;

        CurveSpans spans = new CurveSpans();

        spans.ensure(W);
        spans.build(0, W - 1, Y, (column) ->
        {
            float value = valueAt(layer, (float) (column / pxPerSecond) * FPS);

            return Math.round((Y + H) - (value - axisMin) / (axisMax - axisMin) * H);
        });

        for (int column = 0; column < W; column++)
        {
            assertTrue(spans.has(column), "column " + column + " was not sampled");
        }

        /* Spot-check the mapping: the first sample is key 0 (620) on a 350…800 axis. */
        float expectedY = (Y + H) - (620F - axisMin) / (axisMax - axisMin) * H;

        assertEquals(Math.round(expectedY) - Y, spans.lo(0), 1, "span must be origin-relative");
    }

}
