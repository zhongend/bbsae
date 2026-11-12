package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure feedback curves of the settings page (Round 5): row stagger, category underline, section
 * collapse chevron, footer entrance and the three-state depth-mode display.
 *
 * <p>These are the numbers the page draws from; the page itself needs a GL context. The mappings
 * are static so they can be pinned headlessly, including the "motion off" path where
 * {@link UiMotion} snaps to the target on the first frame.</p>
 */
class UiSettingsMotionTest
{
    /** A motion clock whose switches are test-controlled. */
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

    @Test
    void rowStaggerDelayIsMonotonicAndCapped()
    {
        assertEquals(0F, UICompSettings.staggerDelay(0), 0F, "the first row starts immediately");
        assertEquals(0F, UICompSettings.staggerDelay(-3), 0F, "negative indices are clamped");
        assertEquals(0.015F, UICompSettings.staggerDelay(1), 0.0001F, "12-18 ms per row");
        assertEquals(0.06F, UICompSettings.staggerDelay(4), 0.0001F);

        float previous = -1F;

        for (int index = 0; index <= 30; index++)
        {
            float delay = UICompSettings.staggerDelay(index);

            assertTrue(delay >= previous, "later rows must not start earlier");
            previous = delay;
        }

        assertEquals(0.2F, UICompSettings.staggerDelay(100), 0F, "the delay is capped for long pages");
    }

    @Test
    void staggerOffsetScalesWithProgressAndIntensity()
    {
        assertEquals(4, UICompSettings.staggerOffset(0F, 1F), "a row at 0% starts 4 px low");
        assertEquals(0, UICompSettings.staggerOffset(1F, 1F), "a settled row is in place");
        assertEquals(4, UICompSettings.staggerOffset(-2F, 1F), "negative progress behaves like 0%");
        assertEquals(2, UICompSettings.staggerOffset(0F, 0.5F), "half intensity halves the distance");
        assertEquals(0, UICompSettings.staggerOffset(0F, 0F), "intensity 0 means no offset");

        int previous = Integer.MAX_VALUE;

        for (int i = 0; i <= 10; i++)
        {
            int offset = UICompSettings.staggerOffset(i / 10F, 1F);

            assertTrue(offset <= previous, "the offset shrinks as the row appears");
            previous = offset;
        }
    }

    @Test
    void navUnderlineSlidesToZeroWhenActive()
    {
        assertEquals(10, UICompSettings.navUnderlineInset(0F, 1F), "inactive underline is fully inset");
        assertEquals(0, UICompSettings.navUnderlineInset(1F, 1F), "the active underline spans the button");
        assertEquals(5, UICompSettings.navUnderlineInset(0.5F, 1F));
        assertEquals(0, UICompSettings.navUnderlineInset(0F, 0F), "intensity 0 keeps it static");
        assertEquals(0, UICompSettings.navUnderlineInset(3F, 1F), "clamped to the active end");
    }

    @Test
    void sectionChevronMovesTwoPixelsBetweenStates()
    {
        assertEquals(-2, UICompSettings.sectionChevronOffset(0F), "open group points left");
        assertEquals(2, UICompSettings.sectionChevronOffset(1F), "folded group points right");
        assertEquals(0, UICompSettings.sectionChevronOffset(0.5F));
        assertEquals(-2, UICompSettings.sectionChevronOffset(-4F), "clamped");
        assertEquals(2, UICompSettings.sectionChevronOffset(9F), "clamped");
    }

    @Test
    void footerAlphaFadesInLast()
    {
        assertEquals(0.3F, UICompSettings.footerAlpha(0F), 0.0001F, "the file/version line starts quiet");
        assertEquals(1F, UICompSettings.footerAlpha(1F), 0.0001F);
        assertEquals(1F, UICompSettings.footerAlpha(2F), 0.0001F, "progress is clamped");

        assertTrue(UICompSettings.footerAlpha(0.25F) < UICompSettings.footerAlpha(0.75F),
            "the footer gets louder as the page settles");
    }

    @Test
    void depthModeIndexKeepsLegacyBooleanWorking()
    {
        assertEquals(0, UICompSettings.depthModeIndex(0, false), "off stays off");
        assertEquals(1, UICompSettings.depthModeIndex(0, true),
            "a 0.94 boolean preference displays as ON until the user picks a state");
        assertEquals(1, UICompSettings.depthModeIndex(1, false));
        assertEquals(2, UICompSettings.depthModeIndex(2, true), "auto wins over the legacy boolean");
        assertEquals(2, UICompSettings.depthModeIndex(9, false), "out-of-range modes are clamped");
        assertEquals(0, UICompSettings.depthModeIndex(-3, false));
    }

    @Test
    void animationsOffSnapsEverySettingsChannel()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        motion.enabled = false;

        float row = motion.anim("row:0", 1F, 22F);
        float nav = motion.anim("nav:0", 1F, 22F);
        float fold = motion.spring("sec:0:c", 1F, 420F, 26F);

        assertEquals(1F, row, 0F);
        assertEquals(1F, nav, 0F);
        assertEquals(1F, fold, 0F);
        assertEquals(0, UICompSettings.staggerOffset(row, 1F), "rows start in place");
        assertEquals(0, UICompSettings.navUnderlineInset(nav, 1F), "the underline is already at the active end");
        assertEquals(2, UICompSettings.sectionChevronOffset(fold), "the folded chevron is at its target");
        assertEquals(1F, UICompSettings.footerAlpha(motion.intro(0F)), 0F, "the footer is fully visible");
        assertFalse(motion.isActive());
    }
}
