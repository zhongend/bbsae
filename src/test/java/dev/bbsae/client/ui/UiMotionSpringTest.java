package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The non-linear motion layer is pure math once the preference reads are overridden, so it can be
 * pinned headlessly (no window, no Fabric runtime).
 */
class UiMotionSpringTest
{
    /** A motion clock whose preference switches are test-controlled. */
    private static final class StubMotion extends UiMotion
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

    private static StubMotion motion()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);

        return motion;
    }

    @Test
    void criticallyDampedSpringDoesNotOvershoot()
    {
        StubMotion motion = motion();
        float value = 0F;
        float stiffness = 120F;

        for (int i = 1; i <= 240; i++)
        {
            motion.tickFrame(i * 16_000_000L);
            value = motion.spring("k", 1F, stiffness, 2F * (float) Math.sqrt(stiffness));
            assertTrue(value <= 1.0001F, "critically damped spring overshot at frame " + i + ": " + value);
        }

        assertEquals(1F, value, 0.001F, "spring must reach its target");
    }

    @Test
    void springSnapsAndStops()
    {
        StubMotion motion = motion();
        float value = motion.spring("k", 0.5F, 200F, 20F);

        for (int i = 1; i <= 500; i++)
        {
            motion.tickFrame(i * 16_000_000L);
            value = motion.spring("k", 0.5F, 200F, 20F);
        }

        assertEquals(0.5F, value, 0F, "a settled spring must report exactly its target");
        assertFalse(motion.isActive(), "settled motion must report inactive");
    }

    @Test
    void tweenEndsExactlyAtTarget()
    {
        StubMotion motion = motion();
        float value = motion.tween("p", 1F, 0.2F, UiMotion.Easing.EASE_OUT);

        for (int i = 1; i <= 60 && motion.isActive(); i++)
        {
            motion.tickFrame(i * 16_000_000L);
            value = motion.tween("p", 1F, 0.2F, UiMotion.Easing.EASE_OUT);
        }

        assertEquals(1F, value, 0F, "a finished tween must land on the exact target");
        assertFalse(motion.isActive());
    }

    @Test
    void tweenIsMonotonicForEaseOut()
    {
        StubMotion motion = motion();
        float previous = motion.tween("p", 1F, 0.4F, UiMotion.Easing.EASE_OUT);

        for (int i = 1; i <= 40; i++)
        {
            motion.tickFrame(i * 16_000_000L);
            float value = motion.tween("p", 1F, 0.4F, UiMotion.Easing.EASE_OUT);

            assertTrue(value >= previous - 0.0001F, "eased tween must not go backwards");
            previous = value;
        }
    }

    @Test
    void animationsOffReturnsTargetImmediately()
    {
        StubMotion motion = motion();

        motion.enabled = false;

        assertEquals(1F, motion.tween("p", 1F, 2F, UiMotion.Easing.OUT_ELASTIC), 0F);
        assertEquals(0.75F, motion.spring("s", 0.75F, 200F, 10F), 0F);
        assertEquals(1F, motion.anim("a", 1F, 4F), 0F);
        assertEquals(1F, motion.intro(0F), 0F);
        assertEquals(1F, motion.introBack(0F), 0F);
        assertFalse(motion.isActive());
    }

    @Test
    void pruneRemovesIdleChannels()
    {
        StubMotion motion = motion();

        motion.tickFrame(0L);
        motion.anim("idle", 1F, 20F);
        assertTrue(motion.channelCount() > 0);

        motion.tickFrame(2_000_000_000L);
        motion.prune(1F);

        assertEquals(0, motion.channelCount(), "an untouched channel must be pruned");
    }

    @Test
    void dtIsClamped()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        motion.tickFrame(10_000_000_000L);

        assertTrue(motion.dt <= 0.05F, "a ten second pause must not teleport animations: " + motion.dt);
        assertTrue(motion.dt > 0F);
    }

    @Test
    void pageTransitionSlidesAndFadesOut()
    {
        UiPageTransition transition = new UiPageTransition(0.2F, 12F);

        transition.begin("inspector");

        assertTrue(transition.isActive());
        assertEquals("inspector", transition.page());
        assertEquals(0F, transition.progress(), 0F, "a fresh transition starts at 0 elapsed");
        assertEquals(12F, transition.slideY(), 0.0001F);
        assertEquals(0.28F, transition.veilAlpha(), 0.0001F);

        for (int i = 1; i <= 30; i++)
        {
            transition.update(i * 16_000_000L);
        }

        assertFalse(transition.isActive());
        assertEquals(0F, transition.slideY(), 0.0001F);
        assertEquals(0F, transition.veilAlpha(), 0.0001F);
    }

    @Test
    void staggerDelaysLaterRowsAndCaps()
    {
        StubMotion motion = motion();

        motion.markShown();

        float first = UiStagger.progress(motion, 0, 0.02F, 0.2F);
        float later = UiStagger.progress(motion, 3, 0.02F, 0.2F);
        float capped = UiStagger.progress(motion, 100, 0.02F, 0.2F);

        assertTrue(first >= later, "later rows must not arrive earlier than the first row");
        assertTrue(capped >= 0F && capped <= 1F);
        assertEquals(0, UiStagger.offsetPx(1F, 12));
        assertEquals(12, UiStagger.offsetPx(0F, 12));
        assertTrue(UiStagger.alpha(0F) > 0F, "rows never start fully invisible");
    }
}
