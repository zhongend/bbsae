package dev.bbsae.client.ui;

import dev.bbsae.comp.CompTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step/label maths of the compositor's frame-aware keyframe ruler.
 *
 * <p>The drawer's axis is in video frames, not BBS' 20 ticks/second. These tests pin the step
 * conversion and the sub-second label format that stop two label sets from overlapping (the old
 * decorator drew a second ruler) and stop adjacent labels from collapsing to the same {@code 0:00}
 * string at high zoom.</p>
 */
public class CompKeyframeRulerTest
{
    @Test
    public void frameNumberStepsAreOneTwoFive()
    {
        assertEquals(1, CompKeyframeRuler.chooseStepFrames(1, 30F, false));
        assertEquals(2, CompKeyframeRuler.chooseStepFrames(2, 30F, false));
        assertEquals(5, CompKeyframeRuler.chooseStepFrames(4, 30F, false));
        assertEquals(20, CompKeyframeRuler.chooseStepFrames(12, 30F, false));
        assertEquals(100, CompKeyframeRuler.chooseStepFrames(75, 60F, false));
    }

    @Test
    public void secondsStepsLandOnWholeFramesAtSixtyFps()
    {
        /* 60 fps: one BBS tick is 3 frames, five ticks 15, twenty ticks 60. */
        assertEquals(3, CompKeyframeRuler.chooseStepFrames(1, 60F, true));
        assertEquals(15, CompKeyframeRuler.chooseStepFrames(10, 60F, true));
        assertEquals(60, CompKeyframeRuler.chooseStepFrames(40, 60F, true));
        assertEquals(300, CompKeyframeRuler.chooseStepFrames(200, 60F, true));
    }

    @Test
    public void secondsStepsSkipFractionalFrameStepsAtThirtyFps()
    {
        /* 30 fps: 1 tick = 1.5 frames; only even tick counts are whole frames. */
        assertEquals(3, CompKeyframeRuler.chooseStepFrames(1, 30F, true));
        assertEquals(15, CompKeyframeRuler.chooseStepFrames(10, 30F, true));
        assertEquals(30, CompKeyframeRuler.chooseStepFrames(20, 30F, true));
    }

    @Test
    public void subSecondLabelsCannotCollapseToTheSameString()
    {
        /* 60 fps: frame 30 is 0.50 s, frame 31 is 0.51 s. */
        assertEquals("0.50", CompTime.rulerLabel(30, 60F, true));
        assertEquals("0.51", CompTime.rulerLabel(31, 60F, true));
        assertFalse(CompTime.rulerLabel(30, 60F, true)
            .equals(CompTime.rulerLabel(31, 60F, true)));
    }

    @Test
    public void frameModeLabelsAreFrameNumbers()
    {
        assertEquals("0", CompTime.rulerLabel(0, 60F, false));
        assertEquals("37", CompTime.rulerLabel(37, 60F, false));
    }

    @Test
    public void minorTicksStayReadable()
    {
        assertEquals(12, CompKeyframeRuler.minorStep(60, 1D));
        assertEquals(3, CompKeyframeRuler.minorStep(3, 1D), "a 3-frame major step cannot be subdivided");
        assertEquals(60, CompKeyframeRuler.minorStep(60, 0.01D));
    }

    @Test
    public void pixelsPerFrameProbesTheAxisWithoutIntegerRounding()
    {
        assertEquals(2D, CompKeyframeRuler.pixelsPerFrame((frame) -> frame * 2), 0.000001D);
        assertTrue(CompKeyframeRuler.pixelsPerFrame((frame) -> frame) > 0D);
    }
}
