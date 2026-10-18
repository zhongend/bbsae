package dev.bbsae.client.ui;

import dev.bbsae.comp.CompTime;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * A wide, always-visible current-time bar above the dope sheet.
 *
 * <p>The BBS keyframe canvas still owns its ruler and keyframe editing. This strip is the
 * dependable scrub target: it is large, it draws the playhead even when the graph is empty, and
 * it keeps tracking the pointer while the mouse button is held, like a film timeline.</p>
 */
public class UICompositorScrubber extends UIElement
{
    private final UICompositorPanel panel;
    private boolean dragging;

    public UICompositorScrubber(UICompositorPanel panel)
    {
        this.panel = panel;
        this.h(26);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.dragging && !Window.isMouseButtonPressed(0))
        {
            this.dragging = false;
        }

        if (this.dragging)
        {
            this.setFromMouse(context.mouseX);
        }

        int trackY = this.area.ey() - 7;

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xCC151820);
        context.batcher.box(this.area.x + 4, trackY, this.area.ex() - 4, trackY + 5, 0xFF2A303A);

        int duration = Math.max(1, this.panel.getDurationTicks());
        float frame = Math.max(0F, Math.min(duration, this.panel.getFrame()));
        int x = this.area.x + 4 + Math.round((this.area.w - 8) * (frame / duration));

        context.batcher.box(this.area.x + 4, trackY, x, trackY + 5, 0xFF55AAFF);
        context.batcher.box(x - 1, this.area.y + 1, x + 2, this.area.ey(), Colors.WHITE);

        float fps = this.panel.getDocument() == null ? 60F : this.panel.getDocument().fps;
        float seconds = duration / Math.max(1F, fps);
        float pxPerSecond = Math.max(1F, (this.area.w - 8F) / Math.max(0.01F, seconds));
        int step = 1;

        while (pxPerSecond * step < 58F)
        {
            step = step == 1 ? 2 : step == 2 ? 5 : step + 5;
        }

        for (int second = 0; second <= (int) Math.ceil(seconds); second += step)
        {
            int tickX = this.area.x + 4 + Math.round(pxPerSecond * second);

            if (tickX > this.area.ex() - 2)
            {
                break;
            }

            context.batcher.box(tickX, this.area.y + 13, tickX + 1, trackY, 0x66FFFFFF);
            context.batcher.textShadow(second + "s", tickX + 2, this.area.y + 1, 0xAAFFFFFF);
        }

        String label = CompTime.timecode(this.panel.getTime(), fps);

        context.batcher.textShadow(label, this.area.ex() - 6 - context.batcher.getFont().getWidth(label), this.area.y + 1, Colors.WHITE);

        super.render(context);
    }

    private void setFromMouse(int mouseX)
    {
        float ratio = (mouseX - this.area.x - 4F) / Math.max(1F, this.area.w - 8F);

        ratio = Math.max(0F, Math.min(1F, ratio));
        this.panel.setFrame(ratio * Math.max(1, this.panel.getDurationTicks()));
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.area.isInside(context))
        {
            this.dragging = true;
            this.setFromMouse(context.mouseX);

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        this.dragging = false;

        return super.subMouseReleased(context);
    }
}
