package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/**
 * A hairline groove between inspector sections.
 *
 * <p>The inspector stacks dozens of collapsible sections; without a separator the section headers
 * read as one long run of text and it is hard to tell where one group ends. The divider is a
 * two-pixel groove (light edge, dark edge) rather than a full box outline: it separates without
 * adding visual weight, and it leaves the section headers as the loudest typography on the panel.
 * </p>
 */
public class UIInspectorDivider extends UIElement
{
    private static final int LIGHT = 0x24FFFFFF;
    private static final int DARK = 0x40000000;

    public UIInspectorDivider()
    {
        this.w(1F).h(15);
    }

    @Override
    public void render(UIContext context)
    {
        int x = this.area.x + 2;
        int right = this.area.x + this.area.w - 2;
        int y = this.area.y + 8;

        context.batcher.box(x, y, right, y + 1, LIGHT);
        context.batcher.box(x, y + 1, right, y + 2, DARK);
    }
}
