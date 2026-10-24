package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;

/**
 * A flat, text-first button.
 *
 * <p>BBS's {@link UIButton} paints itself with {@code BBSSettings.primaryColor}, which is fine for
 * a dialog with one action and becomes a wall of accent blocks when forty of them sit in an
 * inspector. This subclass keeps every bit of {@code UIClickable}'s behaviour (hover, click,
 * tooltip, context menu) and only changes the skin: a raised-surface fill, a hairline divider, a
 * left-aligned label, and an accent only when the button is the surface's primary action.</p>
 */
public class UICompButton extends UIButton
{
    public enum Style
    {
        FLAT, PRIMARY, DANGER, GHOST
    }

    private Style style = Style.FLAT;
    private Icon icon;
    /** Optional right-aligned hint (e.g. a value) drawn in a muted colour. */
    private IKey value;

    public UICompButton(IKey label, Consumer<UIButton> callback)
    {
        super(label, callback);

        /* Every button carries a hover description. CompUi.describe() knows the panel's whole
         * vocabulary; anything unknown still gets a sentence naming the action, never an empty
         * tooltip. Callers with better wording override this with .hint(...). */
        String hint = CompUi.describe(label.get());

        if (hint != null && !hint.isEmpty())
        {
            this.tooltip(IKey.constant(hint));
        }
    }

    public UICompButton style(Style style)
    {
        this.style = style;

        return this;
    }

    public UICompButton symbol(Icon icon)
    {
        this.icon = icon;

        return this;
    }

    public UICompButton value(IKey value)
    {
        this.value = value;

        return this;
    }

    @Override
    public UICompButton h(int height)
    {
        super.h(height);

        return this;
    }

    @Override
    public UICompButton w(int width)
    {
        super.w(width);

        return this;
    }

    /** Sets the hover text and returns the button, so factories can chain. */
    public UICompButton hint(String text)
    {
        if (text != null && !text.isEmpty())
        {
            this.tooltip(IKey.constant(text));
        }

        return this;
    }

    @Override
    protected void renderSkin(UIContext context)
    {
        int x = this.area.x;
        int y = this.area.y;
        int ex = this.area.ex();
        int ey = this.area.ey();
        boolean hover = this.hover;
        int fill;
        int border;
        int text;

        switch (this.style)
        {
            case PRIMARY ->
            {
                fill = hover ? Colors.mulRGB(BBSSettings.primaryColor() | Colors.A100, 0.88F) : (BBSSettings.primaryColor() | Colors.A100);
                border = Colors.mulRGB(fill, 1.25F);
                text = 0xFFFFFFFF;
            }
            case DANGER ->
            {
                fill = hover ? 0xFF5A2A2A : 0xFF472323;
                border = 0xFF6E3434;
                text = 0xFFFFB4B4;
            }
            case GHOST ->
            {
                fill = hover ? 0x22FFFFFF : 0x00000000;
                border = 0x00000000;
                text = hover ? 0xFFFFFFFF : CompUi.mutedText();
            }
            default ->
            {
                /* Rest: one rung above the panel (raised). Hover does not change the fill — it
                 * switches the hairline to the accent colour, like AE's list rows. Darkening the
                 * fill on hover would sink the button into the panel instead of lifting it. */
                fill = BBSSettings.raisedSurface();
                border = hover ? (BBSSettings.primaryColor() | 0xCC000000) : BBSSettings.dividerColor();
                text = hover ? 0xFFFFFFFF : 0xFFD3DBE6;
            }
        }

        if (fill != 0)
        {
            context.batcher.box(x, y, ex, ey, fill);
        }

        if (border != 0)
        {
            context.batcher.outline(x, y, ex, ey, border);
        }

        /* A 2px accent rail marks the primary action without flooding the row with colour. */
        if (this.style == Style.PRIMARY)
        {
            context.batcher.box(x, y, x + 2, ey, Colors.mulRGB(0xFFFFFFFF, 0.85F));
        }

        FontRenderer font = context.batcher.getFont();
        int size = context.batcher.getFont().getHeight();
        int paddingX = CompUi.density() == CompUi.DENSITY_COMPACT ? 4 : 6;
        int iconSpace = this.icon == null ? 0 : CompUi.icon() - 2;
        int rightSpace = 0;

        if (this.value != null)
        {
            String valueText = font.limitToWidth(this.value.get(), Math.max(12, this.area.w / 2));
            int valueWidth = font.getWidth(valueText);

            rightSpace = valueWidth + paddingX;

            context.batcher.text(valueText, ex - paddingX - valueWidth, this.area.my(size), CompUi.mutedText(), false);
        }

        if (this.icon != null)
        {
            int iconY = this.area.my(this.icon.h);

            context.batcher.icon(this.icon, text, x + paddingX, iconY);
        }

        String label = font.limitToWidth(this.label.get(), Math.max(4, this.area.w - paddingX * 2 - iconSpace - rightSpace));

        context.batcher.text(label, x + paddingX + iconSpace, this.area.my(size), text, false);

        this.renderLockedArea(context);
    }
}
