package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AE-style camera tool conversions: Track XY must follow the cursor at any dolly distance, and the
 * dolly gesture must stay inside the camera's near/far range.
 */
public class CameraToolMathTest
{
    @Test
    public void trackXYFollowsTheCursorAtAnyZoom()
    {
        /* fit 1 / zoom 1: 200 screen px = 200 comp px. */
        assertEquals(200F, 200F * CameraToolMath.worldPerScreenPixel(1F, 1F), 0.0001F);

        /* Dolly to 2x zoom: the same 200 px now covers 100 comp px, so the camera moves 100 px —
         * the old code moved it 200 px and the camera ran ahead of the mouse. */
        assertEquals(100F, 200F * CameraToolMath.worldPerScreenPixel(1F, 2F), 0.0001F);

        /* Half-size preview: fit 0.5, zoom 2 => world per pixel 1.0. */
        assertEquals(200F, 200F * CameraToolMath.worldPerScreenPixel(0.5F, 2F), 0.0001F);
    }

    @Test
    public void worldPerScreenPixelIsMonotonicAndSafe()
    {
        assertTrue(CameraToolMath.worldPerScreenPixel(1F, 1F) > CameraToolMath.worldPerScreenPixel(1F, 2F));
        assertTrue(CameraToolMath.worldPerScreenPixel(1F, 1F) < CameraToolMath.worldPerScreenPixel(1F, 0.5F));

        /* Zero/negative inputs (unmaximised panel, camera exactly on the plane) must not divide by
         * zero or produce negative movement. */
        assertTrue(Float.isFinite(CameraToolMath.worldPerScreenPixel(0F, 0F)));
        assertTrue(CameraToolMath.worldPerScreenPixel(0F, 0F) > 0F);
        assertTrue(CameraToolMath.worldPerScreenPixel(-1F, -1F) > 0F);
    }

    @Test
    public void dollyStaysInsideNearFar()
    {
        assertEquals(500F, CameraToolMath.dollyDistance(500F, 1F, 1F, 10000F), 0.0001F);

        /* A huge factor clamps just inside far, a tiny one just outside near. */
        float far = CameraToolMath.dollyDistance(500F, 1000F, 1F, 1000F);
        float near = CameraToolMath.dollyDistance(500F, 0.000001F, 1F, 1000F);

        assertTrue(far <= 990F + 0.0001F);
        assertTrue(near >= 11F - 0.0001F);
        assertTrue(near >= 1F);
    }
}
