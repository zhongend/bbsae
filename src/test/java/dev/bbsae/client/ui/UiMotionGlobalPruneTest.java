package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-3 motion plumbing: the automatic prune inside {@link UiMotion#tickFrame()} and the
 * {@link UiPageTransition.CloseFade} contract used by the fade-out overlays.
 *
 * <p>Both are pure state machines once the preference reads are overridden and the clock is
 * injected, so the interesting properties — bounded channel tables, exactly-once finish,
 * animations-off snapping — can be pinned without a window or a Fabric runtime.</p>
 */
class UiMotionGlobalPruneTest
{
    /** A motion clock whose switches are test-controlled. */
    private static class StubMotion extends UiMotion
    {
        boolean enabled = true;

        @Override
        protected boolean animationsEnabled()
        {
            return this.enabled;
        }

        @Override
        protected float speedFactor()
        {
            return 1F;
        }

        @Override
        protected float intensityValue()
        {
            return 1F;
        }
    }

    private static long frameTime(int frame)
    {
        return frame * 16_000_000L;
    }

    @Test
    void autoPruneDropsIdleChannelsWithoutHostCalls()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        motion.anim("row:0", 1F, 20F);
        motion.anim("row:1", 1F, 20F);

        assertEquals(2, motion.channelCount(), "channels are created on first use");

        /* 180 frames = 2.88 s: the automatic sweeps run at 60/120/180 frames and the idle window
         * is 2 s, so by the last sweep both channels are old enough to drop. No host prune call. */
        for (int frame = 1; frame <= 180; frame++)
        {
            motion.tickFrame(frameTime(frame));
        }

        assertEquals(0, motion.channelCount(), "tickFrame must prune abandoned channels by itself");
    }

    @Test
    void autoPruneKeepsChannelsTouchedThisFrame()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);

        for (int frame = 1; frame <= 600; frame++)
        {
            motion.tickFrame(frameTime(frame));
            motion.anim("live", 1F, 20F);

            if (frame % 100 == 0)
            {
                assertEquals(1, motion.channelCount(), "a live channel survives every sweep");
            }
        }
    }

    @Test
    void autoPruneDropsIdleTweenAndFlashes()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        /* A long tween that the host stops querying: the element was hidden mid-transition. */
        motion.tween("page", 1F, 5F, UiMotion.Easing.EASE_OUT);
        motion.flash("ping");

        assertEquals(1, motion.tweenCount());
        assertEquals(1, motion.flashCount());
        assertTrue(motion.isActive(), "the unfinished tween reports active");

        for (int frame = 1; frame <= 180; frame++)
        {
            motion.tickFrame(frameTime(frame));
        }

        assertEquals(0, motion.tweenCount(), "an abandoned tween must not outlive the idle window");
        assertEquals(0, motion.flashCount(), "an unread flash must not outlive the idle window");
        assertEquals(0, motion.channelCount());
        assertFalse(motion.isActive(), "dropping the stale tween also clears isActive()");
    }

    @Test
    void autoPruneBoundsChannelChurnInLongSession()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);

        /* Host pattern: a few persistent channels plus one new row/tile channel every frame as
         * lists scroll. Without pruning this grows forever; the automatic sweep must bound it to
         * roughly "persistent + channels touched inside the idle window". */
        for (int frame = 1; frame <= 900; frame++)
        {
            motion.tickFrame(frameTime(frame));

            for (int live = 0; live < 3; live++)
            {
                motion.anim("live:" + live, 1F, 20F);
            }

            motion.anim("churn:" + frame, 1F, 20F);
        }

        /* 2 s of frames is ~125; allow a sweep of margin instead of pinning an exact count. */
        assertTrue(motion.channelCount() <= 3 + 130,
            "a long session must not keep every channel ever created: " + motion.channelCount());
        assertNotEquals(900, motion.channelCount());
    }

    @Test
    void closeFadeCallsFinishExactlyOnce()
    {
        StubMotion motion = new StubMotion();
        UiPageTransition.CloseFade fade = new UiPageTransition.CloseFade(motion, 0.2F);

        motion.tickFrame(0L);
        fade.begin(0L);

        assertTrue(fade.isClosing());
        assertEquals(0F, fade.progress(), 0F);
        assertEquals(1F, fade.remaining(), 0F);

        int finishes = 0;
        float previous = 0F;

        for (int frame = 1; frame <= 60; frame++)
        {
            if (fade.tick(frameTime(frame)))
            {
                finishes++;
            }

            assertTrue(fade.progress() >= previous, "close progress must not go backwards");
            previous = fade.progress();
        }

        assertEquals(1, finishes, "the removal callback may only be reported once");
        assertEquals(1F, fade.progress(), 0F);
        assertEquals(0F, fade.remaining(), 0F);
        assertFalse(fade.tick(frameTime(61)), "a finished fade never reports again");
    }

    @Test
    void closeFadeSnapsWhenAnimationsAreOff()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        motion.enabled = false;

        UiPageTransition.CloseFade fade = new UiPageTransition.CloseFade(motion, 0.5F);

        fade.begin(0L);
        assertTrue(fade.tick(16_000_000L), "with motion off the fade finishes on the first frame");
        assertEquals(1F, fade.progress(), 0F);
        assertFalse(fade.tick(32_000_000L));
    }

    @Test
    void closeFadeIgnoresRepeatedBegin()
    {
        StubMotion motion = new StubMotion();
        UiPageTransition.CloseFade fade = new UiPageTransition.CloseFade(motion, 0.2F);

        motion.tickFrame(0L);
        fade.begin(0L);

        for (int frame = 1; frame <= 5; frame++)
        {
            fade.tick(frameTime(frame));
        }

        float progress = fade.progress();

        assertTrue(progress > 0F && progress < 1F);
        /* Esc pressed again, or a second close button: the fade must not restart. */
        fade.begin(10_000_000_000L);
        assertEquals(progress, fade.progress(), 0F);

        int finishes = 0;

        for (int frame = 6; frame <= 60; frame++)
        {
            if (fade.tick(frameTime(frame)))
            {
                finishes++;
            }
        }

        assertEquals(1, finishes);

        /* Nor may a late begin() resurrect a finished fade. */
        fade.begin(20_000_000_000L);
        assertFalse(fade.tick(20_016_000_000L));
    }

    @Test
    void onboardingPageDelayIsMonotonicAndCapped()
    {
        assertEquals(0F, UIOnboarding.pageItemDelay(0), 0F, "the first item starts immediately");
        assertEquals(0F, UIOnboarding.pageItemDelay(-3), 0F, "negative indices are clamped");

        float previous = -1F;

        for (int index = 0; index <= 20; index++)
        {
            float delay = UIOnboarding.pageItemDelay(index);

            assertTrue(delay >= previous, "later cards must not start earlier");
            previous = delay;
        }

        assertEquals(0.18F, UIOnboarding.pageItemDelay(100), 0F, "the delay is capped for long pages");
        assertTrue(UIOnboarding.pageItemDelay(1) > 0F, "the second item is delayed at least a little");
    }
}
