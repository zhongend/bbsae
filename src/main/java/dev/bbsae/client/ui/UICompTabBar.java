package dev.bbsae.client.ui;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UITextTab;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A fixed tab bar with deterministic hit testing.
 *
 * <p>BBS's {@code UITabStrip} is a scroll view: inside a dashboard panel its click dispatch is
 * unreliable (the strip keeps eating presses), which made the whole inspector unreachable. This
 * keeps the exact same visual language (text tabs, active highlight, underline) but every tab is a
 * plain {@link UITextTab} that handles its own click.</p>
 *
 * <p>Motion is per tab and non-linear: the active underline slides in on an exponential approach,
 * hover brightens the label, and a press shrinks the tab a few percent for one frame. Channel keys
 * are built once when a tab is added (never per frame), so a long session does not allocate keys.</p>
 */
public class UICompTabBar extends UIElement
{
    private final List<UITextTab> tabs = new ArrayList<>();
    private int nextX;
    private final UiMotion motion = new UiMotion();
    private int motionFrames;

    /**
     * Which tab the pointer button went down on, or -1. BBS's click fires on the press and clears
     * its pressed flag on a release event; the release can be swallowed by a host page swap, so the
     * bar polls the physical button once per frame as a fallback.
     */
    private int pressedTab = -1;

    private IntSupplier active = () -> 0;
    private IntConsumer onSelect;

    public UICompTabBar()
    {
        this.h(22);
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

        if (this.pressedTab >= 0 && !Window.isMouseButtonPressed(0))
        {
            this.pressedTab = -1;
        }

        super.render(context);
    }

    /**
     * Tabs are absolutely positioned and never clipped, so in a narrow dock panel the overflow tabs
     * sit on top of the neighbouring panel. BBS dispatches clicks without testing the parent's
     * rectangle; this guard keeps a clipped tab from stealing a click outside its bar.
     */
    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            return false;
        }

        return super.subMouseClicked(context);
    }

    public UICompTabBar active(IntSupplier active)
    {
        this.active = active;

        return this;
    }

    public UICompTabBar onSelect(IntConsumer onSelect)
    {
        this.onSelect = onSelect;

        return this;
    }

    public void addTab(String label)
    {
        final int index = this.tabs.size();
        /* Stable channel names: building "tab:" + index inside render would allocate every frame. */
        final String selectKey = "tab:" + index;
        final String hoverKey = "tab-hover:" + index;
        final String pressKey = "tab-press:" + index;
        UITextTab tab = new UITextTab(IKey.constant(label))
        {
            @Override
            public void render(UIContext context)
            {
                boolean selected = UICompTabBar.this.active.getAsInt() == index;
                float sel = UICompTabBar.this.motion.select(selectKey, selected);
                float hov = UICompTabBar.this.motion.anim(hoverKey,
                    this.area.isInside(context) ? 1F : 0F, 22F);
                float press = UICompTabBar.this.motion.spring(pressKey,
                    UICompTabBar.this.pressedTab == index ? 1F : 0F, 520F, 30F);
                float scale = 1F - 0.04F * press * UiMotion.clamp01(UICompTabBar.this.motion.intensity());
                MatrixStack stack = Math.abs(scale - 1F) > 0.001F
                    ? context.batcher.getContext().getMatrices() : null;

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
                    float bright = Math.max(sel, hov * (1F - 0.35F * press));

                    if (hov > 0.01F && !selected)
                    {
                        context.batcher.box(this.area.x + 2, this.area.y + 2,
                            this.area.ex() - 2, this.area.ey() - 4,
                            UiGfx.mulA(0xFFFFFFFF, 0.035F * hov * (1F - 0.5F * press)));
                    }

                    this.color = UiMotion.lerpColor(0xFF8A94A6, 0xFFFFFFFF, bright);
                    /* UITextTab itself swaps to hoverColor while the cursor is inside; point it at
                     * the animated value too, otherwise entering a tab still brightens in one frame
                     * and only the exit fades. */
                    this.hoverColor = this.color;
                    super.render(context);

                    if (sel > 0.01F)
                    {
                        float inset = 4F + (1F - sel) * 6F;
                        /* The underline sits 1 px lower while pressed, so the label reads as
                         * being pushed down even before the scale settles. */
                        float down = 1F * press;

                        context.batcher.box(this.area.x + inset, this.area.ey() - 2 + down,
                            this.area.ex() - inset, this.area.ey() - 1 + down,
                            UiGfx.mulA(0xFF4C9AFF, sel));

                        if (CompPrefs.get().glow)
                        {
                            context.batcher.box(this.area.x + inset, this.area.ey() - 3 + down,
                                this.area.ex() - inset, this.area.ey() - 2 + down,
                                UiGfx.mulA(0xFF4C9AFF, 0.18F * sel));
                        }
                    }
                }
                finally
                {
                    if (stack != null)
                    {
                        stack.pop();
                    }
                }
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context))
                {
                    UICompTabBar.this.pressedTab = index;
                    UICompTabBar.this.select(index);

                    return true;
                }

                return super.subMouseClicked(context);
            }
        };

        /* Absolute layout: BBS's row() divides the width equally and ignores w(), which squeezed
         * seven Chinese tabs into seven 42 px slots and made the labels overlap. */
        int width = Math.max(34, 12 + label.length() * 9);

        /* h(1F) fills whatever bar height the host gives us, so the tab can never overflow it. */
        tab.relative(this).x(this.nextX).y(0).w(width).h(1F);
        this.nextX += width + 2;
        this.tabs.add(tab);
        this.add(tab);
    }

    public void select(int index)
    {
        if (index >= 0 && index < this.tabs.size() && this.onSelect != null)
        {
            this.onSelect.accept(index);
        }
    }
}
