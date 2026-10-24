package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icon;

/**
 * An icon button with a flat hover plate.
 *
 * <p>Icon-only controls are what makes a dense toolbar (the curve editor has thirteen actions) fit
 * without a row of text blocks. The plate only appears on hover, so a resting toolbar is a strip of
 * glyphs — AE's language — instead of a grid of filled rectangles. The tooltip is mandatory: it is
 * the only thing that names the action, so {@link CompUi#iconButton} always sets one.</p>
 */
public class UICompIconButton extends UIIcon
{
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
        if (this.hover)
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.baseSurface());
            context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.dividerColor());
        }

        super.render(context);
    }
}
