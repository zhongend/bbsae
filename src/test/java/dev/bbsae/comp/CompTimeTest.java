package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview clock must advance in seconds, not in Minecraft tick fractions.
 *
 * <p>Regression: {@code UICompositorPanel.startRenderFrame} added {@code tickDelta / 20} once per
 * rendered frame. {@code tickDelta} is 0..1 since the last client tick, so the playback rate was
 * a function of the display refresh rate: 60 fps happened to be close to 1×, 144 fps was about
 * 3.6×, and 30 fps was 0.75×. That is why the compositor preview and BBS's own 20-tick player
 * drifted apart.</p>
 */
public class CompTimeTest
{
    @Test
    public void playbackDeltaConvertsNanosecondsToSeconds()
    {
        assertEquals(0.016F, CompTime.playbackDelta(1_000_000_000L, 1_016_000_000L), 0.0001F);
        /* The first sample only establishes the baseline; it must not add a bogus second. */
        assertEquals(0F, CompTime.playbackDelta(0L, 1_000_000_000L));
    }

    @Test
    public void longStallIsClamped()
    {
        assertEquals(CompTime.MAX_PLAYBACK_STEP,
            CompTime.playbackDelta(1_000_000_000L, 10_000_000_000L), 0.0001F);
    }

    @Test
    public void pausedOrBackwardsIntervalAddsNothing()
    {
        assertEquals(0F, CompTime.playbackDelta(0L, 1_000_000_000L));
        assertEquals(0F, CompTime.playbackDelta(2_000_000_000L, 1_000_000_000L));
        assertEquals(0F, CompTime.playbackDelta(1_000_000_000L, 1_000_000_000L));
    }

    @Test
    public void refreshRateDoesNotChangePlaybackSpeed()
    {
        /* One second of wall time at 60 and at 144 frames per second. The old tickDelta sum
         * depended on the sampling grid; a monotonic clock always adds up to the same second. */
        for (int fps : new int[] {30, 60, 90, 144, 240})
        {
            long step = 1_000_000_000L / fps;
            long now = 1L;
            float total = 0F;

            for (int frame = 0; frame < fps; frame++)
            {
                long next = now + step;

                total += CompTime.playbackDelta(now, next);
                now = next;
            }

            assertTrue(Math.abs(total - 1F) < 0.002F, fps + " fps advanced " + total + "s in one second");
        }
    }

    /**
     * Reproduces the old formula: {@code sum(tickDelta / 20)} once per rendered frame, where
     * {@code tickDelta} is the sawtooth fraction of the current client tick. It happens to be
     * near 1× only at frame rates that divide 20 evenly (60, 120...), which is why the bug was
     * easy to miss on one monitor and obvious on another.
     */
    @Test
    public void oldTickDeltaFormulaWasRefreshRateDependent()
    {
        assertTrue(secondsPerSecondOfOldFormula(144) > 3F,
            "the old formula ran a 144 Hz preview far too fast");
        assertTrue(secondsPerSecondOfOldFormula(30) < 0.75F,
            "the old formula ran a 30 fps preview too slowly");
        assertEquals(1F, secondsPerSecondOfOldFormula(60), 0.05F,
            "60 fps was the accidental frame rate at which the old formula looked correct");
    }

    private static float secondsPerSecondOfOldFormula(int fps)
    {
        float total = 0F;

        for (int frame = 0; frame < fps; frame++)
        {
            /* Fraction of the current client tick at the moment this frame is drawn. */
            float tickDelta = (frame * 20F / fps) % 1F;

            total += tickDelta / 20F;
        }

        return total;
    }
}
