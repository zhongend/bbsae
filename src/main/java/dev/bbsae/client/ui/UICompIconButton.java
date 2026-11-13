package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import net.minecraft.client.util.math.MatrixStack;

/**
 * An icon button with a flat hover plate.
 *
 * <p>Icon-only controls are what makes a dense toolbar (the curve editor has thirteen actions) fit
 * without a row of text blocks. The plate only appears on hover, so a resting toolbar is a strip of
 * glyphs — AE's language — instead of a grid of filled rectangles. The tooltip is mandatory: it is
 * the only thing that names the action, so {@link CompUi#iconButton} always sets one.</p>
 *
 * <p>The plate and the press are non-linear: the hover plate fades in on an exponential approach and
 * a press shrinks the glyph a few percent around its centre (then springs back). Only drawing is
 * transformed — the hit area stays the original {@code area}, so a fast click cannot miss a button
 * that is mid-animation.</p>
 */
public class UICompIconButton extends UIIcon
{
    private final UiMotion motion = new UiMotion();
    private final UICompButton.PressLatch press = new UICompButton.PressLatch();
    private int motionFrames;

    public UICompIconButton(Icon icon, String tooltip, Runnable action)
    {
        super(icon, (button) -> action.run());

        this.wh(CompUi.icon(), CompUi.icon());

        if (tooltip != null && !tooltip.isEmpty())
        {
            this.tooltip(IKey.constant(tooltip));
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();

        if (++this.motionFrames >= UICompButton.PRUNE_EVERY)
        {
            this.motionFrames = 0;
            this.motion.prune(UICompButton.PRUNE_IDLE);
        }

        this.hover = this.area.isInside(context);
        this.press.poll(Window.isMouseButtonPressed(0), this.isEnabled());

        float hv = this.motion.anim("hover", this.hover ? 1F : 0F, 24F);
        float pv = this.motion.press("press", this.press.held());
        float en = this.motion.spring("enabled", this.isEnabled() ? 1F : 0F, 320F, 28F);
        int inset = UICompButton.pressInset(pv);
        int x = this.area.x + inset;
        int y = this.area.y + inset;
        int ex = this.area.ex() - inset;
        int ey = this.area.ey() - inset;

        if (hv > 0.01F)
        {
            context.batcher.box(x, y, ex, ey, UiGfx.mulA(BBSSettings.baseSurface(), hv * en));
            context.batcher.outline(x, y, ex, ey, UiGfx.mulA(BBSSettings.dividerColor(), hv * en));
        }

        /* 5% is enough to read as "pressed" at 16–20 px without the glyph looking resized. */
        float scale = 1F - 0.05F * pv * en * UiMotion.clamp01(this.motion.intensity());
        MatrixStack stack = Math.abs(scale - 1F) > 0.001F ? context.batcher.getContext().getMatrices() : null;

        if (stack != null)
        {
            float cx = this.area.mx();
            float cy = this.area.my();

            stack.push();
            stack.translate(cx, cy, 0F);
            stack.scale(scale, scale, 1F);
            stack.translate(-cx, -cy, 0F);
        }

        try
        {
            /* super.render() would overwrite the hover flag from its own hit test — it computes
             * the same value, so the plate above and the glyph below always agree. */
            super.render(context);
        }
        finally
        {
            if (stack != null)
            {
                stack.pop();
            }
        }
    }
}
