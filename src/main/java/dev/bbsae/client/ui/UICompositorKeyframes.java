package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCanvas;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

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

    public UICompositorKeyframes(UICompositorPanel panel, Consumer<Keyframe> callback)
    {
        super(callback);

        this.panel = panel;
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
        super.renderOverlay(context);

        /* The editor's own ruler is supplied by UICompositorPanel via
         * UIKeyframes.rulerRenderer(...), so this overlay only draws the shared BBS cursor. */
        float tick = this.getPlayheadTick(context);
        int x = this.toGraphX(tick);

        if (x < this.graphArea.x || x > this.graphArea.ex())
        {
            return;
        }

        String label = String.format(Locale.ROOT, "%.0f", tick);

        UITimelineCanvas.renderCursor(context, label, this.graphArea, x);
    }
}
