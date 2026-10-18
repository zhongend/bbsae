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
