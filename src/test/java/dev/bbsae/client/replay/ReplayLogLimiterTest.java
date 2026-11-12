package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The failure-log rate limiter: first line immediately, one per interval afterwards, counted. */
public class ReplayLogLimiterTest
{
    @Test
    public void theFirstFailureIsAlwaysLogged()
    {
        ReplayLogLimiter limiter = new ReplayLogLimiter(1000L);

        assertTrue(limiter.allow(0L));
        assertEquals(0, limiter.suppressed());
    }

    @Test
    public void repeatedFailuresInsideTheIntervalAreSwallowedAndCounted()
    {
        ReplayLogLimiter limiter = new ReplayLogLimiter(1000L);

        assertTrue(limiter.allow(0L));
        assertFalse(limiter.allow(100L));
        assertFalse(limiter.allow(900L));
        assertEquals(2, limiter.suppressed());
    }

    @Test
    public void theNextIntervalAllowsAnotherLineAndReportsTheSuppressedCount()
    {
        ReplayLogLimiter limiter = new ReplayLogLimiter(1000L);

        assertTrue(limiter.allow(0L));
        assertFalse(limiter.allow(10L));
        assertTrue(limiter.allow(1000L));
        assertEquals(" (+1 suppressed)", limiter.describeSuppressed());
        assertEquals("", limiter.describeSuppressed(), "the counter is consumed by the line");
    }

    @Test
    public void theFirstLineHasNoSuppressedSuffix()
    {
        ReplayLogLimiter limiter = new ReplayLogLimiter(1000L);

        limiter.allow(500L);

        assertEquals("", limiter.describeSuppressed());
    }

    @Test
    public void resetForgetsEverything()
    {
        ReplayLogLimiter limiter = new ReplayLogLimiter(1000L);

        limiter.allow(0L);
        limiter.allow(1L);
        limiter.reset();

        assertEquals(0, limiter.suppressed());
        assertTrue(limiter.allow(1L));
    }

    @Test
    public void aNonPositiveIntervalLogsEveryTime()
    {
        ReplayLogLimiter limiter = new ReplayLogLimiter(0L);

        assertTrue(limiter.allow(5L));
        assertTrue(limiter.allow(5L));
    }
}
