package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keeps the preview's edge/corner scaling on the axis the handle actually points at. */
public class CompTransformHandlesTest
{
    @Test
    public void rightAndLeftEdgesChangeWidthOnly()
    {
        float[] right = CompTransformHandles.scaled(21, 100F, 50F, 200F, 150F, 1F, 1F, false);
        float[] left = CompTransformHandles.scaled(23, -100F, 50F, -200F, 150F, 1F, 1F, false);

        assertEquals(2F, right[0], 0.0001F);
        assertEquals(1F, right[1], 0.0001F);
        assertEquals(2F, left[0], 0.0001F);
        assertEquals(1F, left[1], 0.0001F);
    }

    @Test
    public void topAndBottomEdgesChangeHeightOnly()
    {
        float[] top = CompTransformHandles.scaled(20, 100F, 50F, 200F, 150F, 1F, 1F, false);
        float[] bottom = CompTransformHandles.scaled(22, 100F, -50F, 200F, -150F, 1F, 1F, false);

        assertEquals(1F, top[0], 0.0001F);
        assertEquals(3F, top[1], 0.0001F);
        assertEquals(1F, bottom[0], 0.0001F);
        assertEquals(3F, bottom[1], 0.0001F);
    }

    @Test
    public void cornerWithoutShiftKeepsTheOriginalAspectRatio()
    {
        float[] result = CompTransformHandles.scaled(13, 100F, 50F, 150F, 100F, 2F, 1F, true);

        assertEquals(3.5F, result[0], 0.0001F);
        assertEquals(1.75F, result[1], 0.0001F);
        assertEquals(2F, result[0] / result[1], 0.0001F);
    }

    @Test
    public void shiftOnACornerAllowsIndependentAxes()
    {
        float[] result = CompTransformHandles.scaled(11, 100F, 50F, 150F, 100F, 2F, 1F, false);

        assertEquals(3F, result[0], 0.0001F);
        assertEquals(2F, result[1], 0.0001F);
    }

    @Test
    public void zeroSizedAxisDoesNotExplodeAndScalesStayClamped()
    {
        assertEquals(1F, CompTransformHandles.scaled(21, 0F, 0F, 50F, 50F, 1F, 1F, false)[0], 0.0001F);
        assertEquals(CompTransformHandles.MIN_SCALE,
            CompTransformHandles.scaled(10, 100F, 100F, 0F, 0F, 1F, 1F, true)[0], 0.0001F);
        assertEquals(CompTransformHandles.MAX_SCALE,
            CompTransformHandles.scaled(10, 1F, 1F, 10000F, 10000F, 1F, 1F, true)[0], 0.0001F);
    }
}
