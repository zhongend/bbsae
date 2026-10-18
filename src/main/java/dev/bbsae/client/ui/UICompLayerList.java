package dev.bbsae.client.ui;

import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;

import java.util.List;
import java.util.function.Consumer;

/**
 * The composition's layer stack, drawn by BBS's own {@link UIList}. Selection, scrolling,
 * keyboard navigation, row highlighting and the empty state are BBS behaviour; this class only
 * supplies the row text and the layer order.
 */
public class UICompLayerList extends UIList<CompLayer>
{
    public UICompLayerList(Consumer<List<CompLayer>> callback)
    {
        super(callback);

        this.scroll.scrollItemSize = 16;
    }

    @Override
    protected boolean sortElements()
    {
        return false;
    }

    @Override
    protected String elementToString(UIContext context, int i, CompLayer element)
    {
        String name = element.name == null || element.name.isEmpty() ? element.id : element.name;
        StringBuilder label = new StringBuilder();

        if (!element.visible)
        {
            label.append("[H] ");
        }
        if (element.solo)
        {
            label.append("[S] ");
        }
        if (element.markers != null && !element.markers.isEmpty())
        {
            label.append("[M] ");
        }

        label.append(name == null ? "" : name);

        return label.toString();
    }
}
