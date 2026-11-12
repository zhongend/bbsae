package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }
}
