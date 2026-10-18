package dev.bbsae.client.ui;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UITextTab;

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
 */
public class UICompTabBar extends UIElement
{
    private final List<UITextTab> tabs = new ArrayList<>();
    private int nextX;

    private IntSupplier active = () -> 0;
    private IntConsumer onSelect;

    public UICompTabBar()
    {
        this.h(22);
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
        UITextTab tab = new UITextTab(IKey.constant(label))
        {
            @Override
            public void render(UIContext context)
            {
                boolean selected = UICompTabBar.this.active.getAsInt() == index;

                this.color = selected ? 0xFFFFFFFF : 0xFF8A94A6;
                super.render(context);

                if (selected)
                {
                    context.batcher.box(this.area.x + 4, this.area.ey() - 2, this.area.ex() - 4, this.area.ey(), 0xFF4C9AFF);
                }
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context))
                {
                    UICompTabBar.this.select(index);

                    return true;
                }

                return super.subMouseClicked(context);
            }
        };

        /* Absolute layout: BBS's row() divides the width equally and ignores w(), which squeezed
         * seven Chinese tabs into seven 42 px slots and made the labels overlap. */
        int width = Math.max(38, 14 + label.length() * 10);

        tab.relative(this).x(this.nextX).y(0).w(width).h(22);
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
