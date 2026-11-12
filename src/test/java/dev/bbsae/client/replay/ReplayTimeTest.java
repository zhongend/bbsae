package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the film-tick ↔ composition-second/frame conversion the replay panel's playhead uses. */
public class ReplayTimeTest
{
    private static final float EPS = 0.0001F;

    @Test
    public void oneSecondIsTwentyTicks()
    {
        assertEquals(20F, ReplayTime.ticksFromSeconds(1F), EPS);
        assertEquals(2.5F, ReplayTime.secondsFromTicks(50F), EPS);
    }

    @Test
    public void aFrameMapsToTheNearestTick()
    {
        assertEquals(10L, ReplayTime.ticksFromFrame(15L, 30F));
        assertEquals(20L, ReplayTime.ticksFromFrame(30L, 30F));
        assertEquals(0L, ReplayTime.ticksFromFrame(-5L, 30F));
        assertEquals(0L, ReplayTime.ticksFromFrame(10L, 0F));
    }

    @Test
    public void ticksMapBackToFrames()
    {
        assertEquals(15F, ReplayTime.frameFromTicks(10F, 30F), EPS);
        assertEquals(30F, ReplayTime.frameFromTicks(20F, 30F), EPS);
    }

    @Test
    public void aDurationInSecondsBecomesAUsableTickCount()
    {
        assertEquals(50, ReplayTime.durationTicks(2.5F));
        assertEquals(1, ReplayTime.durationTicks(0F));
        assertEquals(1, ReplayTime.durationTicks(-3F));
    }

    @Test
    public void thePhaseOfAFloatCursorIsTheFractionalPart()
    {
        assertEquals(0.25F, ReplayTime.phase(5.25F), EPS);
        assertEquals(0F, ReplayTime.phase(5F), EPS);
        assertEquals(0F, ReplayTime.phase(Float.NaN), EPS, "a NaN cursor must not poison the sampled pose");
        assertEquals(0F, ReplayTime.phase(Float.POSITIVE_INFINITY), EPS);
    }

    @Test
    public void clampTickCoversBothEndsAndNonFiniteValues()
    {
        assertEquals(0F, ReplayTime.clampTick(-5F, 100), EPS);
        assertEquals(0F, ReplayTime.clampTick(0F, 100), EPS);
        assertEquals(50F, ReplayTime.clampTick(50F, 100), EPS);
        assertEquals(100F, ReplayTime.clampTick(100F, 100), EPS);
        assertEquals(100F, ReplayTime.clampTick(999F, 100), EPS);

        assertEquals(0F, ReplayTime.clampTick(Float.NaN, 100), EPS);
        assertEquals(0F, ReplayTime.clampTick(Float.NEGATIVE_INFINITY, 100), EPS);
        assertEquals(100F, ReplayTime.clampTick(Float.POSITIVE_INFINITY, 100), EPS);
    }

    @Test
    public void clampTickTreatsADegenerateDurationAsOneTick()
    {
        assertEquals(0F, ReplayTime.clampTick(-3F, 0), EPS);
        assertEquals(1F, ReplayTime.clampTick(3F, 0), EPS);
        assertEquals(1F, ReplayTime.clampTick(1F, -50), EPS);
    }

    @Test
    public void tickValidityMatchesTheClamp()
    {
        assertTrue(ReplayTime.isValidTick(0F, 100));
        assertTrue(ReplayTime.isValidTick(100F, 100));
        assertFalse(ReplayTime.isValidTick(-1F, 100));
        assertFalse(ReplayTime.isValidTick(101F, 100));
        assertFalse(ReplayTime.isValidTick(Float.NaN, 100));
    }
}
