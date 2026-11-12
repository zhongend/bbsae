package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The script's schedule is the only part of the single-client-run verification that can be tested
 * without a client, so an off-by-one here would waste the one launch the user allows.
 */
public class ReplayScriptTimelineTest
{
    @Test
    public void theScheduleIsStrictlyOrdered()
    {
        assertTrue(ReplayScriptTimeline.isWellOrdered());

        assertTrue(ReplayScriptTimeline.MOVE_ACTION < ReplayScriptTimeline.MOVE_SHOT);
        assertTrue(ReplayScriptTimeline.UNDO_ACTION < ReplayScriptTimeline.UNDO_SHOT);
        assertTrue(ReplayScriptTimeline.FORM_ACTION < ReplayScriptTimeline.FORM_SHOT);
        assertTrue(ReplayScriptTimeline.LIST_ACTION < ReplayScriptTimeline.LIST_SHOT);
        assertTrue(ReplayScriptTimeline.CLEANUP_ACTION < ReplayScriptTimeline.QUIT_TICK);
    }

    @Test
    public void everyScreenshotComesAfterItsActionByTheSettleWindow()
    {
        assertEquals(ReplayScriptTimeline.SETTLE_TICKS,
            ReplayScriptTimeline.MOVE_SHOT - ReplayScriptTimeline.MOVE_ACTION);
        assertEquals(ReplayScriptTimeline.SETTLE_TICKS,
            ReplayScriptTimeline.UNDO_SHOT - ReplayScriptTimeline.UNDO_ACTION);
        assertEquals(ReplayScriptTimeline.SETTLE_TICKS,
            ReplayScriptTimeline.FORM_SHOT - ReplayScriptTimeline.FORM_ACTION);
        assertEquals(ReplayScriptTimeline.SETTLE_TICKS,
            ReplayScriptTimeline.LIST_SHOT - ReplayScriptTimeline.LIST_ACTION);
    }

    @Test
    public void thereAreFourUniquelyNamedScreenshots()
    {
        assertEquals(4, ReplayScriptTimeline.SHOT_COUNT);

        Set<String> names = new HashSet<>();

        for (int i = 0; i < ReplayScriptTimeline.SHOT_COUNT; i++)
        {
            String name = ReplayScriptTimeline.shotName(i);

            assertTrue(name.startsWith("replay-interact-"), name);
            assertTrue(names.add(name), "duplicate screenshot name " + name);
        }

        assertTrue(names.contains("replay-interact-move"));
        assertTrue(names.contains("replay-interact-undo"));
        assertTrue(names.contains("replay-interact-form"));
        assertTrue(names.contains("replay-interact-list"));
    }

    @Test
    public void theRunGetsAPositiveWarmupAndReasonableLength()
    {
        assertTrue(ReplayScriptTimeline.OPEN_DELAY >= ReplayScriptTimeline.SETTLE_TICKS * 2,
            "the panel needs time to open, frame the scene and render");
        assertTrue(ReplayScriptTimeline.QUIT_TICK > ReplayScriptTimeline.LIST_SHOT);
        assertTrue(ReplayScriptTimeline.QUIT_TICK < 20 * 60, "a scripted run must finish inside a minute");
    }
}
