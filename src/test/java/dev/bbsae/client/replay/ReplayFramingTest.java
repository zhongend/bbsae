package dev.bbsae.client.replay;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NaN/infinite guards around the auto-framing math (black-viewport causes that never throw). */
public class ReplayFramingTest
{
    private static final float EPS = 0.0001F;

    @Test
    public void finiteValuesAreDetected()
    {
        assertTrue(ReplayFraming.isFinite(0F));
        assertTrue(ReplayFraming.isFinite(-123.5F));
        assertFalse(ReplayFraming.isFinite(Float.NaN));
        assertFalse(ReplayFraming.isFinite(Float.POSITIVE_INFINITY));
        assertFalse(ReplayFraming.isFinite(Float.NEGATIVE_INFINITY));
    }

    @Test
    public void nonFinitePositionsAreNotAccumulated()
    {
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

        assertFalse(ReplayFraming.accumulate(min, max, Float.NaN, 0F, 0F));
        assertFalse(ReplayFraming.accumulate(min, max, 0F, Float.POSITIVE_INFINITY, 0F));
        assertTrue(ReplayFraming.accumulate(min, max, 1F, 2F, 3F));
        assertTrue(ReplayFraming.accumulate(min, max, -1F, -2F, -3F));

        assertEquals(-1F, min.x, EPS);
        assertEquals(1F, max.x, EPS);
    }

    @Test
    public void invertedOrNonFiniteBoundsAreRejected()
    {
        assertTrue(ReplayFraming.isValidBounds(new Vector3f(0F, 0F, 0F), new Vector3f(1F, 1F, 1F)));
        assertTrue(ReplayFraming.isValidBounds(new Vector3f(0F, 0F, 0F), new Vector3f(0F, 0F, 0F)));
        assertFalse(ReplayFraming.isValidBounds(new Vector3f(1F, 0F, 0F), new Vector3f(0F, 1F, 1F)));
        assertFalse(ReplayFraming.isValidBounds(new Vector3f(Float.NaN, 0F, 0F), new Vector3f(1F, 1F, 1F)));
        assertFalse(ReplayFraming.isValidBounds(null, new Vector3f(1F, 1F, 1F)));
    }

    @Test
    public void theCenterIsTheMidpoint()
    {
        Vector3f center = ReplayFraming.center(new Vector3f(-10F, 0F, 2F), new Vector3f(10F, 4F, 6F), new Vector3f());

        assertEquals(0F, center.x, EPS);
        assertEquals(2F, center.y, EPS);
        assertEquals(4F, center.z, EPS);
    }

    @Test
    public void safeDistanceFallsBackForNonFiniteOrNonPositiveValues()
    {
        assertEquals(600F, ReplayFraming.safeDistance(600F, 2000F), EPS);
        assertEquals(2000F, ReplayFraming.safeDistance(Float.NaN, 2000F), EPS);
        assertEquals(2000F, ReplayFraming.safeDistance(Float.POSITIVE_INFINITY, 2000F), EPS);
        assertEquals(2000F, ReplayFraming.safeDistance(0F, 2000F), EPS);
        assertEquals(2000F, ReplayFraming.safeDistance(-5F, 2000F), EPS);
    }

    @Test
    public void anUnusableFallbackStillProducesAFiniteDistance()
    {
        assertEquals(ReplayFraming.FALLBACK_DISTANCE,
            ReplayFraming.safeDistance(Float.NaN, Float.NaN), EPS);
        assertEquals(ReplayFraming.FALLBACK_DISTANCE,
            ReplayFraming.safeDistance(Float.NaN, -1F), EPS);
    }
}
