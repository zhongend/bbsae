package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the non-linear feedback layer: the press latch, the feedback curves, the
 * 60-frame prune cadence the hosts run, the stagger delay ordering, and the hit helpers the
 * animated widgets use.
 *
 * <p>Nothing here needs a GL context or the Fabric runtime: {@link StubMotion} overrides the
 * preference reads, the clock is injected through {@link UiMotion#tickFrame(long)}, and the
 * helpers under test are pure functions. That is the point of the split — the drawing code stays
 * untestable by nature, the numbers it draws from do not.</p>
 */
class UiFeedbackMotionTest
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

    /** Records the stagger delays it is asked for, so the curve itself is deterministic. */
    private static final class RecordingMotion extends UiMotion
    {
        final List<Float> delays = new ArrayList<>();
        float progress;

        @Override
        public float intro(float delay)
        {
            this.delays.add(delay);

            return this.progress;
        }
    }

    @Test
    void pressLatchOwnsAndReleasesAPress()
    {
        UICompButton.PressLatch press = new UICompButton.PressLatch();

        assertFalse(press.held());
        assertTrue(press.press(true, true), "a press inside an enabled button is ours");
        assertTrue(press.held());
        assertTrue(press.poll(true, true), "the latch survives while the button is really down");

        /* The release event is not guaranteed to reach a rebuilt button; the poll is the fallback. */
        assertFalse(press.poll(false, true));
        assertFalse(press.held());

        /* A disabled button (selection changed under the pointer) also drops the latch. */
        assertTrue(press.press(true, true));
        assertFalse(press.poll(true, false));

        /* A press outside the button, or on a disabled one, was never ours. */
        assertFalse(press.press(false, true));
        assertFalse(press.press(true, false));
        assertTrue(press.press(true, true));
        assertFalse(press.release());
        assertFalse(press.held());
    }

    @Test
    void pressInsetAppearsOnlyPastHalfWay()
    {
        assertEquals(0, UICompButton.pressInset(0F));
        assertEquals(0, UICompButton.pressInset(0.25F));
        assertEquals(1, UICompButton.pressInset(0.5F));
        assertEquals(1, UICompButton.pressInset(1F));
        assertEquals(0, UICompButton.pressInset(-1F), "out-of-range values are clamped");
        assertEquals(1, UICompButton.pressInset(2F), "the inset never grows past 1 px");
    }

    @Test
    void liftCollapsesWhenPressedDisabledOrStatic()
    {
        assertEquals(2F, UICompButton.lift(1F, 0F, 1F, 1F), 0.0001F);
        assertEquals(0F, UICompButton.lift(1F, 1F, 1F, 1F), 0.0001F, "a pressed button sinks");
        assertEquals(0F, UICompButton.lift(1F, 0F, 0F, 1F), 0.0001F, "intensity 0 means no motion");
        assertEquals(0F, UICompButton.lift(1F, 0F, 1F, 0F), 0.0001F, "disabled buttons do not lift");
        assertEquals(1F, UICompButton.lift(0.5F, 0F, 1F, 1F), 0.0001F);
    }

    @Test
    void disabledDimIsBoundedAndMonotonic()
    {
        assertEquals(1F, UICompButton.dim(1F), 0.0001F);
        assertEquals(0.5F, UICompButton.dim(0F), 0.0001F);
        assertEquals(0.5F, UICompButton.dim(-3F), 0.0001F);
        assertEquals(1F, UICompButton.dim(9F), 0.0001F);
        assertTrue(UICompButton.dim(0.4F) < UICompButton.dim(0.8F));
    }

    @Test
    void animationsOffSnapsEveryFeedbackChannel()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        motion.enabled = false;

        /* The same calls the widgets make: with motion off they return the target on frame one. */
        float press = motion.spring("press", 1F, 520F, 30F);

        assertEquals(1F, press, 0F);
        assertEquals(1, UICompButton.pressInset(press));
        assertEquals(0F, UICompButton.lift(1F, press, 1F, 1F), 0F, "the pressed pose cancels the lift");
        assertEquals(0.6F, motion.anim("hover", 0.6F, 20F), 0F);
        assertEquals(1F, motion.tween("page", 1F, 2F, UiMotion.Easing.OUT_ELASTIC), 0F);
        assertEquals(0.5F, motion.breathe(1.4F), 0F, "a breathing indicator freezes mid-way");
        assertFalse(motion.isActive());
    }

    @Test
    void pruneCadenceKeepsHostChannelsBounded()
    {
        StubMotion motion = new StubMotion();
        int touchedPerFrame = 4;

        motion.tickFrame(0L);

        /* The host pattern: every visible row touches its channel each frame, rows that scroll
         * away are touched once, and prune runs every 60th frame. */
        for (int frame = 1; frame <= 600; frame++)
        {
            motion.tickFrame(frame * 16_000_000L);

            for (int i = 0; i < touchedPerFrame; i++)
            {
                motion.anim("row:" + i, 1F, 24F);
            }

            if (frame % 60 == 0)
            {
                motion.prune(2F);
            }
        }

        assertEquals(touchedPerFrame, motion.channelCount(),
            "a long session must not keep one channel per row ever drawn");

        /* A channel that stopped being touched is dropped by the next prune. */
        motion.anim("row:gone", 1F, 24F);
        assertEquals(touchedPerFrame + 1, motion.channelCount());

        for (int frame = 601; frame <= 780; frame++)
        {
            motion.tickFrame(frame * 16_000_000L);

            for (int i = 0; i < touchedPerFrame; i++)
            {
                motion.anim("row:" + i, 1F, 24F);
            }

            if (frame % 60 == 0)
            {
                motion.prune(2F);
            }
        }

        assertEquals(touchedPerFrame, motion.channelCount());
    }

    @Test
    void staggerDelaysAreMonotonicAndCapped()
    {
        RecordingMotion motion = new RecordingMotion();

        motion.markShown();
        UiStagger.progress(motion, 0, 0.02F, 0.2F);
        UiStagger.progress(motion, 3, 0.02F, 0.2F);
        UiStagger.progress(motion, 1000, 0.02F, 0.2F);

        assertEquals(3, motion.delays.size());
        assertEquals(0F, motion.delays.get(0), 0F, "the first row starts immediately");
        assertEquals(0.06F, motion.delays.get(1), 0.0001F);
        assertEquals(0.2F, motion.delays.get(2), 0.0001F, "the delay is capped for long lists");
        assertTrue(motion.delays.get(1) > motion.delays.get(0), "later rows start later");
        assertTrue(motion.delays.get(2) >= motion.delays.get(1));

        /* The derived offsets are monotonic in progress: a row at 0% is offset the most. */
        int previous = Integer.MAX_VALUE;

        for (int i = 0; i <= 10; i++)
        {
            int offset = UiStagger.offsetPx(i / 10F, 4);

            assertTrue(offset <= previous, "entrance offset must shrink as the row appears");
            previous = offset;
            assertTrue(UiStagger.alpha(i / 10F) > 0F, "a row never starts fully invisible");
        }

        assertEquals(1F, UiStagger.progress(null, 5, 0.02F, 0.2F), 0F, "no clock means no animation");
    }

    @Test
    void curveEditorHoverTargetsTheNearestPointWithinRadius()
    {
        float[] identity = new float[5];

        /* Identity curve in a 100×100 box: point 0 is the bottom-left corner, point 4 top-right. */
        assertEquals(0, UICurveEditor.pointNear(1, 98, 0, 0, 100, 100, identity));
        assertEquals(4, UICurveEditor.pointNear(99, 1, 0, 0, 100, 100, identity));
        assertEquals(2, UICurveEditor.pointNear(51, 50, 0, 0, 100, 100, identity));
        assertEquals(-1, UICurveEditor.pointNear(50, 0, 0, 0, 100, 100, identity),
            "the middle of the box is nowhere near a point");

        /* Moving a point's value moves its hit box with it. */
        float[] raised = new float[5];

        raised[0] = 0.4F;
        assertEquals(0, UICurveEditor.pointNear(0, 60, 0, 0, 100, 100, raised));

        assertEquals(1F, UICurveEditor.pointValue(4, 4, new float[5]), 0F);
        assertEquals(0F, UICurveEditor.pointValue(0, 4, new float[5]), 0F);
        assertEquals(0.7F, UICurveEditor.pointValue(2, 4, new float[] {0F, 0F, 0.2F, 0F, 0F}), 0.0001F);
        assertEquals(0F, UICurveEditor.pointValue(0, 4, new float[] {-5F, 0F, 0F, 0F, 0F}),
            "out-of-range offsets are clamped into the widget");
    }

    @Test
    void colorWheelDiskKeepsStripSpace()
    {
        assertEquals(170, UIColorWheel.diskSide(200, 200));
        assertEquals(16, UIColorWheel.diskSide(20, 200));
        assertTrue(UIColorWheel.diskSide(40, 40) < 24, "a too-small widget reports a non-drawable disk");
    }

    @Test
    void importBrowserScrollArithmeticStaysInsideContent()
    {
        assertEquals(0, UIImportBrowser.rowIndexAt(10, 10, 0, 16));
        assertEquals(3, UIImportBrowser.rowIndexAt(10 + 3 * 16 + 5, 10, 0, 16));
        assertEquals(1, UIImportBrowser.rowIndexAt(15, 10, 16, 16), "a scrolled list shifts the row index");
        assertEquals(0, UIImportBrowser.rowIndexAt(10, 10, 0, 0), "a zero row height must not divide by zero");

        assertEquals(0, UIImportBrowser.clampScrollOffset(100, 50, -10));
        assertEquals(50, UIImportBrowser.clampScrollOffset(100, 50, 500));
        assertEquals(30, UIImportBrowser.clampScrollOffset(100, 50, 30));
        assertEquals(0, UIImportBrowser.clampScrollOffset(20, 50, 30), "shorter content cannot scroll");
    }
}
