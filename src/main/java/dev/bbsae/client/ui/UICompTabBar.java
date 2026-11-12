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
    private static final int OVERFLOW_W = 20;
    private static final String OVERFLOW_HOVER_KEY = "tab-overflow-hover";
    private static final String OVERFLOW_PRESS_KEY = "tab-overflow-press";

    private final List<UITextTab> tabs = new ArrayList<>();
    private final List<Integer> tabWidths = new ArrayList<>();
    private int nextX;
    private final UiMotion motion = new UiMotion();
    private int motionFrames;

    /**
     * When the bar is narrower than its tabs (dock panel dragged narrow), the trailing tabs are
     * hidden and a "»" control advances the active page. Before this the overflow tabs were drawn
     * on top of the neighbouring panel (and, since BBS dispatches clicks by child rectangle, they
     * could steal those panels' clicks — 0.94 added a hit guard, this makes the hidden pages
     * reachable instead of merely unreachable).
     */
    private boolean overflow;
    private int visibleTabs;
    private int overflowX;

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
        this.renderOverflow(context);
    }

    /** True when the tab strip does not fit and the "»" control is active. */
    public boolean isOverflowing()
    {
        return this.overflow;
    }

    public int visibleTabCount()
    {
        return this.visibleTabs;
    }

    @Override
    public void resize()
    {
        super.resize();
        this.layoutTabs();
    }

    /**
     * Fits the absolute-positioned tabs into the current bar width. Tabs keep their natural width;
     * the ones that do not fit are hidden and the active page can still be reached through the
     * overflow control. Must run after addTab and on every resize.
     */
    private void layoutTabs()
    {
        if (this.area.w <= 0 || this.tabs.isEmpty())
        {
            return;
        }

        int[] widths = new int[this.tabWidths.size()];

        for (int index = 0; index < widths.length; index++)
        {
            widths[index] = this.tabWidths.get(index);
        }

        int[] layout = CompUi.tabStripLayout(widths, this.area.w);

        this.overflow = layout[2] == 1;
        this.visibleTabs = layout[0];
        this.overflowX = layout[1];

        for (int index = 0; index < this.tabs.size(); index++)
        {
            this.tabs.get(index).setVisible(index < this.visibleTabs);
        }
    }

    /** Draws the "»" control (and the hidden-active-page accent) when tabs overflow. */
    private void renderOverflow(UIContext context)
    {
        if (!this.overflow || this.area.w <= 0)
        {
            return;
        }

        int x = this.area.x + this.overflowX;
        int y = this.area.y;
        boolean hot = context.mouseX >= x && context.mouseX < this.area.ex()
            && context.mouseY >= y && context.mouseY < this.area.ey();
        float hover = this.motion.anim(OVERFLOW_HOVER_KEY, hot ? 1F : 0F, 22F);
        float press = this.motion.spring(OVERFLOW_PRESS_KEY, this.pressedTab == -2 ? 1F : 0F, 520F, 30F);
        int active = this.active.getAsInt();
        boolean hiddenActive = active >= this.visibleTabs;

        if (hover > 0.01F)
        {
            context.batcher.box(x + 2, y + 3, this.area.ex() - 2, this.area.ey() - 3,
                UiGfx.mulA(0xFFFFFFFF, 0.05F * hover * (1F - 0.5F * press)));
        }

        int color = UiMotion.lerpColor(hiddenActive ? 0xFFCCD6E6 : 0xFF8A94A6, 0xFFFFFFFF, hover);
        context.batcher.textShadow("»", x + 6, y + 6, color);

        if (hiddenActive)
        {
            context.batcher.box(x + 5, this.area.ey() - 2, this.area.ex() - 5, this.area.ey() - 1,
                UiGfx.mulA(0xFF4C9AFF, 0.9F));
        }

        if (press > 0.01F && this.pressedTab == -2 && !Window.isMouseButtonPressed(0))
        {
            this.pressedTab = -1;
        }
    }

    private void cycleOverflow()
    {
        int size = this.tabs.size();

        if (size <= 0 || this.onSelect == null)
        {
            return;
        }

        int next = (Math.max(0, this.active.getAsInt()) + 1) % size;

        this.select(next);
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

        if (this.overflow && context.mouseButton == 0 && context.mouseX >= this.area.ex() - OVERFLOW_W)
        {
            this.pressedTab = -2;
            this.cycleOverflow();

            return true;
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
        this.tabWidths.add(width);
        this.tabs.add(tab);
        this.add(tab);
        this.layoutTabs();
    }

    public void select(int index)
    {
        if (index >= 0 && index < this.tabs.size() && this.onSelect != null)
        {
            this.onSelect.accept(index);
        }
    }
}
