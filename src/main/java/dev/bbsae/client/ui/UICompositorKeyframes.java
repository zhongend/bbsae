package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCanvas;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * BBS's own keyframe timeline, wired to the composition's playhead.
 *
 * <p>This is the same {@link UIKeyframes} implementation the film editor uses; the composition
 * only supplies the cursor and duration, so dope sheet/graph editing, interpolation, copy/paste
 * and undo all remain BBS behaviour.</p>
 */
public class UICompositorKeyframes extends UIKeyframes
{
    private final UICompositorPanel panel;
    /** The drawer's only self-drawn motion: the playhead glow behind BBS' cursor. */
    private final UiMotion motion = new UiMotion();

    public UICompositorKeyframes(UICompositorPanel panel, Consumer<Keyframe> callback)
    {
        super(callback);

        this.panel = panel;

        int savedHeight = CompPrefs.get().keyframeRowHeight;

        if (savedHeight > 0)
        {
            this.setTrackHeight(savedHeight);
        }
    }

    /**
     * BBS' dope sheet has a real {@code setTrackHeight}; the compositor editor simply never exposed
     * it. Alt+Shift+wheel (and Alt+= / Alt+-) is the same vertical zoom gesture as the track
     * timeline and the curve editor, so all three keyframe surfaces behave alike.
     */
    /** Verification hook: change the dope-sheet row height without a wheel event. */
    public void debugSetTrackHeight(double height)
    {
        this.setTrackHeight(height);
    }

    private void setTrackHeight(double height)
    {
        UIKeyframeDopeSheet dopeSheet = this.getDopeSheet();

        if (dopeSheet != null)
        {
            dopeSheet.setTrackHeight(Math.max(12D, Math.min(60D, height)));
        }
    }

    private void changeTrackHeight(int delta)
    {
        UIKeyframeDopeSheet dopeSheet = this.getDopeSheet();

        if (dopeSheet == null)
        {
            return;
        }

        double next = Math.max(12D, Math.min(60D, dopeSheet.getTrackHeight() + delta));

        if (Math.abs(next - dopeSheet.getTrackHeight()) < 0.01D)
        {
            return;
        }

        dopeSheet.setTrackHeight(next);
        CompPrefs.get().keyframeRowHeight = (int) Math.round(next);
        CompPrefs.markDirty();
        this.panel.setStatus("关键帧轨道高度 " + Math.round(next) + "px（Alt+Shift+滚轮）");
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.area.isInside(context))
        {
            double wheel = context.mouseWheel;

            if (Math.abs(wheel) < 0.0001D)
            {
                wheel = context.mouseWheelHorizontal;
            }

            if (Math.abs(wheel) >= 0.0001D)
            {
                if (CompInput.altDown() && CompInput.shiftDown())
                {
                    /* Same split as the track timeline: Alt+Shift+wheel is the vertical (row)
                     * zoom, Alt+wheel alone is the horizontal (time) zoom. */
                    this.changeTrackHeight(wheel > 0D ? 2 : -2);

                    return true;
                }

                if (CompInput.altDown())
                {
                    /* The user asked for Alt+wheel to zoom the TIME axis from anywhere in the
                     * keyframe drawer, including over the XYZ track labels. BBS' dope sheet only
                     * zooms when the pointer is over the graph, so this path normalises the
                     * gesture and makes it identical to the track timeline's. */
                    this.panel.zoomKeyframeTime(wheel > 0D ? 1 : -1, context.mouseX);

                    return true;
                }
            }
        }

        return super.subMouseScrolled(context);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (this.area.isInside(context) && CompInput.altDown()
            && context.getKeyAction() == KeyAction.PRESSED)
        {
            if (context.getKeyCode() == GLFW.GLFW_KEY_EQUAL)
            {
                this.changeTrackHeight(2);

                return true;
            }

            if (context.getKeyCode() == GLFW.GLFW_KEY_MINUS)
            {
                this.changeTrackHeight(-2);

                return true;
            }
        }

        return super.subKeyPressed(context);
    }

    @Override
    public float getTick()
    {
        return this.panel.getFrame();
    }

    @Override
    public float getPlayheadTick(UIContext context)
    {
        return this.panel.getFrame();
    }

    @Override
    public Float getAutoKeyframeTick()
    {
        return this.panel.getFrame();
    }

    /**
     * Selecting a keyframe must make that keyframe's time the current time. The rest of the
     * compositor (Effect Controls, preview drag, Alt+K) writes at the playhead, so leaving the
     * playhead elsewhere silently wrote a second keyframe at the old time.
     */
    @Override
    public void pickKeyframe(Keyframe keyframe)
    {
        super.pickKeyframe(keyframe);

        if (keyframe != null)
        {
            this.panel.setFrame(keyframe.getTick());
        }
    }

    @Override
    protected boolean hasCursor()
    {
        return true;
    }

    @Override
    protected void moveNoKeyframes(UIContext context)
    {
        this.panel.setFrame(this.fromGraphCursor(context.mouseX));
    }

    @Override
    protected void renderOverlay(UIContext context)
    {
        this.motion.tickFrame();

        super.renderOverlay(context);

        /* The editor's own ruler is supplied by UICompositorPanel via
         * UIKeyframes.rulerRenderer(...), so this overlay only draws the shared BBS cursor. */
        float tick = this.getPlayheadTick(context);
        int x = this.toGraphX(tick);

        if (x < this.graphArea.x || x > this.graphArea.ex())
        {
            return;
        }

        /* A slow breath behind the shared BBS cursor (glow switch respected). The line and label
         * stay BBS-drawn; this is the drawer's half of the curve editor's playhead pulse, so the
         * two keyframe surfaces read as one editor. It is paint-only: the cursor X is the model
         * time, never an animated value. */
        if (CompPrefs.get().glow)
        {
            float glow = UICompCurveEditor.playheadGlowAlpha(this.motion.breathe(2.8F), this.motion.intensity());

            if (glow > 0.001F)
            {
                int spread = 2 + Math.round(2F * this.motion.intensity());

                context.batcher.box(x - spread, this.graphArea.y, x + 1 + spread, this.graphArea.ey(),
                    UiMotion.alpha(0xFFFF5A5A, glow));
            }
        }

        String label = String.format(Locale.ROOT, "%.0f", tick);

        UITimelineCanvas.renderCursor(context, label, this.graphArea, x);
    }
}
