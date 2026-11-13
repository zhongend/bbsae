package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The compositor timeline ruler must never treat frames as BBS' 20 ticks/second. */
public class CompTimeRulerTest
{
    @Test
    public void framesAndSecondsAreDistinct()
    {
        assertEquals("60", CompTime.rulerLabel(60, 60F, false));
        assertEquals("1.00", CompTime.rulerLabel(60, 60F, true));
        assertEquals("0.50", CompTime.rulerLabel(30, 60F, true));
        assertEquals("1.00", CompTime.rulerLabel(20, 20F, true));
        assertEquals("2.50", CompTime.rulerLabel(150, 60F, true));
    }
}
