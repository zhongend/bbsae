package dev.bbsae.comp;

import mchorse.bbs_mod.utils.clips.Clip;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a composition clip must keep composition time continuous.
 *
 * <p>A clip's source time is {@code (relativeTick * timeScale + offset) / 20}. The right half
 * restarts at relative tick 0, so unless its offset is advanced by the cut distance times the time
 * scale the composition jumps back to its beginning at the cut. This is also what makes Ctrl+K on a
 * BBS camera timeline behave like Premiere's razor.</p>
 */
public class CompositionClipSplitTest
{
    private static CompositionClip clip(float offset, float timeScale, int duration)
    {
        CompositionClip clip = new CompositionClip();

        clip.offset.set((int) offset);
        clip.timeScale.set(timeScale);
        clip.duration.set(duration);
        clip.tick.set(0);

        return clip;
    }

    private static float sourceTime(float relativeTick, int offset, float timeScale)
    {
        return (relativeTick * timeScale + offset) / 20F;
    }

    @Test
    public void theRightHalfContinuesAtTheSameSourceTime()
    {
        CompositionClip left = clip(40F, 1F, 100);
        float cutSource = sourceTime(50F, 40, 1F);
        Clip rightClip = left.breakDown(50);

        assertNotNull(rightClip);
        CompositionClip right = (CompositionClip) rightClip;

        assertEquals(100, left.duration.get(),
            "breakDown returns the right half; trimming the left half is the caller's step");
        assertEquals(50, right.duration.get());
        assertEquals(90, right.offset.get());
        assertEquals(cutSource, sourceTime(0F, right.offset.get(), right.timeScale.get()), 0.0001F);
        assertEquals(sourceTime(100F, 40, 1F), sourceTime(50F, right.offset.get(), 1F), 0.0001F,
            "the end of the original clip is still the end of the split pair");
    }

    @Test
    public void timeScaleIsAppliedToTheCutDistance()
    {
        CompositionClip left = clip(0F, 0.5F, 100);
        CompositionClip right = (CompositionClip) left.breakDown(40);

        assertEquals(20, right.offset.get());
        assertEquals(sourceTime(40F, 0, 0.5F), sourceTime(0F, right.offset.get(), 0.5F), 0.0001F);
    }

    @Test
    public void negativeTimeScaleStillContinues()
    {
        CompositionClip left = clip(100F, -1F, 100);
        CompositionClip right = (CompositionClip) left.breakDown(30);

        assertEquals(70, right.offset.get());
        assertEquals(sourceTime(30F, 100, -1F), sourceTime(0F, right.offset.get(), -1F), 0.0001F);
    }

    @Test
    public void bothHalvesAreMarkedAsManualTiming()
    {
        CompositionClip left = clip(0F, 1F, 100);
        CompositionClip right = (CompositionClip) left.breakDown(25);

        assertTrue(left.manualTiming.get(),
            "the left half must opt out of automatic full-length rewrites");
        assertTrue(right.manualTiming.get());
    }

    @Test
    public void cuttingOnAnEdgeReturnsNoRightHalf()
    {
        CompositionClip clip = clip(0F, 1F, 100);

        assertEquals(null, clip.breakDown(0));
        assertEquals(null, clip.breakDown(100));
    }

    @Test
    public void aCompositionClipCannotBeTenHoursLong()
    {
        assertEquals(20, CompositionClip.exportDurationTicks(0, 0F));
        assertEquals(36001, CompositionClip.exportDurationTicks(0, 36000F),
            "the clip ceiling is the same half-hour canvas as the composition, in ticks");
        assertEquals(600, CompositionClip.exportDurationTicks(600, 0F),
            "a normal film length is preserved");
    }
}
