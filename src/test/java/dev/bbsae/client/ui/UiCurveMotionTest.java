package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure feedback curves of the AE curve editor (Round 4): channel stroke weight, keyframe
 * hover/selection ring, playhead breath and the drag readouts.
 *
 * <p>The graph itself needs a GL context, but every number it draws from is a static mapping from
 * an animated 0..1 amount. Those are pinned here, including the "motion off" path where
 * {@link UiMotion} snaps to the target on the first frame.</p>
 */
class UiCurveMotionTest
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
    void curveThicknessAndOpacityReachTheActiveEnds()
    {
        assertEquals(1F, UICompCurveEditor.curveThickness(0F), 0.0001F, "a ghost channel is 1 px");
        assertEquals(1.6F, UICompCurveEditor.curveThickness(1F), 0.0001F, "the active channel is thicker");
        assertEquals(1.6F, UICompCurveEditor.curveThickness(2F), 0.0001F, "emphasis is clamped");
        assertEquals(1F, UICompCurveEditor.curveThickness(-1F), 0.0001F);

        assertEquals(0.35F, UICompCurveEditor.curveOpacity(0F), 0.0001F, "inactive channels stay readable");
        assertEquals(1F, UICompCurveEditor.curveOpacity(1F), 0.0001F);
        assertEquals(1F, UICompCurveEditor.curveOpacity(4F), 0.0001F);

        float previous = -1F;

        for (int i = 0; i <= 10; i++)
        {
            float amount = i / 10F;
            float thickness = UICompCurveEditor.curveThickness(amount);
            float opacity = UICompCurveEditor.curveOpacity(amount);

            assertTrue(thickness >= previous, "thickness must grow with the active amount");
            assertTrue(opacity >= 0.35F && opacity <= 1F, "opacity stays in range");
            previous = thickness;
        }
    }

    @Test
    void keyEmphasisGrowsRingAndGlyphButStaysInsideTheHitRadius()
    {
        assertEquals(4.5F, UICompCurveEditor.keyRingRadius(0F, 1F), 0.0001F, "no ring at rest");
        assertEquals(7F, UICompCurveEditor.keyRingRadius(1F, 1F), 0.0001F);
        assertEquals(4.5F, UICompCurveEditor.keyRingRadius(1F, 0F), 0.0001F, "intensity 0 means no ring");
        assertTrue(UICompCurveEditor.keyRingRadius(1F, 1.5F) <= 7F, "intensity is clamped, the ring stays small");
        assertTrue(UICompCurveEditor.keyRingRadius(1F, 1F) < 10F,
            "the ring must stay inside the editor's 10 px key hit radius");

        assertEquals(0, UICompCurveEditor.keyGlyphGrowth(0F, 1F));
        assertEquals(1, UICompCurveEditor.keyGlyphGrowth(1F, 1F));
        assertEquals(0, UICompCurveEditor.keyGlyphGrowth(1F, 0F), "intensity 0 means no growth");
        assertTrue(UICompCurveEditor.keyGlyphGrowth(5F, 5F) <= 1, "the glyph growth is one pixel at most");
    }

    @Test
    void playheadGlowFollowsBreatheAndIntensity()
    {
        assertEquals(0.10F, UICompCurveEditor.playheadGlowAlpha(0F, 1F), 0.0001F);
        assertEquals(0.20F, UICompCurveEditor.playheadGlowAlpha(1F, 1F), 0.0001F);
        assertEquals(0.075F, UICompCurveEditor.playheadGlowAlpha(0.5F, 0.5F), 0.0001F);
        assertEquals(0F, UICompCurveEditor.playheadGlowAlpha(1F, 0F), 0F, "intensity 0 means no glow");
        assertEquals(0.20F, UICompCurveEditor.playheadGlowAlpha(2F, 3F), 0.0001F, "inputs are clamped");
    }

    @Test
    void animationsOffSnapsEveryCurveFeedbackChannel()
    {
        StubMotion motion = new StubMotion();

        motion.tickFrame(0L);
        motion.enabled = false;

        /* The same calls the graph makes; with motion off the first frame is already at target. */
        float active = motion.anim("curve:0:a", 1F, 22F);
        float hover = motion.anim("kf:0:h", 1F, 28F);

        assertEquals(1F, active, 0F);
        assertEquals(1F, hover, 0F);
        assertEquals(1.6F, UICompCurveEditor.curveThickness(active), 0.0001F);
        assertEquals(1F, UICompCurveEditor.curveOpacity(active), 0.0001F);
        assertEquals(7F, UICompCurveEditor.keyRingRadius(Math.max(active, hover), 1F), 0.0001F);
        assertEquals(1, UICompCurveEditor.keyGlyphGrowth(active, 1F));
        assertEquals(0.5F, motion.breathe(2.8F), 0F, "a static interface freezes the breath mid-way");
        assertEquals(0.15F, UICompCurveEditor.playheadGlowAlpha(motion.breathe(2.8F), 1F), 0.0001F);
        assertFalse(motion.isActive());

        /* And the same build with motion enabled takes more than one frame to reach the target. */
        StubMotion animated = new StubMotion();

        animated.tickFrame(0L);
        animated.anim("curve:0:a", 0F, 22F);
        animated.tickFrame(16_000_000L);

        assertTrue(animated.anim("curve:0:a", 1F, 22F) < 0.999F, "motion on animates towards the target");
    }

    @Test
    void dragHudTextFormatsTheFrameOnce()
    {
        assertEquals("位置 X  120.5  帧 12.3", UICompCurveEditor.dragHudText("位置 X", "120.5", 12.34F));

        String text = UICompCurveEditor.dragHudText(null, null, 0F);

        assertFalse(text.contains("null"));
        assertTrue(text.endsWith("帧 0.0"));
    }

    @Test
    void handleHudTextNamesDirectionInfluenceAndSpeed()
    {
        String outgoing = UICompCurveEditor.handleHudText(true, 0.333F, 120F);

        assertTrue(outgoing.startsWith("出切线"));
        assertTrue(outgoing.contains("33%"), outgoing);
        assertTrue(outgoing.contains("120.0/s"), outgoing);

        String incoming = UICompCurveEditor.handleHudText(false, 1F, 0F);

        assertTrue(incoming.startsWith("入切线"));
        assertTrue(incoming.contains("100%"));
    }
}
