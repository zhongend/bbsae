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
        FLAT, PRIMARY, DANGER, GHOST, SELECTED
    }

    private Style style = Style.FLAT;
    private Icon icon;
    /** Optional right-aligned hint (e.g. a value) drawn in a muted colour. */
    private IKey value;
    private boolean alignRight;
    private final UiMotion motion = new UiMotion();

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

    /** Right-align the label; used by value/keycap buttons in settings rows. */
    public UICompButton alignRight()
    {
        this.alignRight = true;

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
        this.motion.tickFrame();

        int x = this.area.x;
        int y = this.area.y;
        int ex = this.area.ex();
        int ey = this.area.ey();
        boolean hover = this.hover;
        float hv = this.motion.anim("hover", hover ? 1F : 0F, 20F);
        float intensity = this.motion.intensity();
        float lift = hv * Math.min(1F, intensity) * 2F;
        int fill;
        int border;
        int text;

        switch (this.style)
        {
            case PRIMARY ->
            {
                int base = BBSSettings.primaryColor() | Colors.A100;
                fill = UiMotion.lerpColor(base, Colors.mulRGB(base, 1.14F), hv);
                border = Colors.mulRGB(fill, 1.25F);
                text = 0xFFFFFFFF;
            }
            case DANGER ->
            {
                fill = UiMotion.lerpColor(0xFF472323, 0xFF5A2A2A, hv);
                border = 0xFF6E3434;
                text = UiMotion.lerpColor(0xFFFFB4B4, 0xFFFFFFFF, hv * 0.5F);
            }
            case GHOST ->
            {
                fill = UiMotion.alpha(0xFFFFFFFF, 0.10F * hv);
                border = 0x00000000;
                text = UiMotion.lerpColor(CompUi.mutedText(), 0xFFFFFFFF, hv);
            }
            case SELECTED ->
            {
                /* A settings category, not a primary action: tinted surface + accent rail,
                 * never the full accent fill. */
                fill = UiMotion.lerpColor(0xFF2A3B4F, 0xFF34495F, hv);
                border = UiMotion.lerpColor(BBSSettings.primaryColor() | 0x55000000,
                    BBSSettings.primaryColor() | 0xAA000000, hv);
                text = 0xFFFFFFFF;
            }
            default ->
            {
                /* Rest: one rung above the panel (raised). Hover switches the hairline to the
                 * accent colour and lifts the fill a little, like AE's list rows. */
                fill = UiMotion.lerpColor(BBSSettings.raisedSurface(), 0xFF2A3442, hv);
                border = UiMotion.lerpColor(BBSSettings.dividerColor(),
                    BBSSettings.primaryColor() | 0xCC000000, hv);
                text = UiMotion.lerpColor(0xFFD3DBE6, 0xFFFFFFFF, hv);
            }
        }

        if (hv > 0.01F && CompPrefs.get().shadows)
        {
            UiGfx.shadow(context.batcher, x, y - lift, ex - x, ey - y, 2.5F,
                UiGfx.alpha(0xFF000000, 0.10F * hv * intensity));
        }

        if (fill != 0)
        {
            context.batcher.box(x, y - lift, ex, ey - lift, fill);

            /* A 1 px top highlight sells the "raised chip" without a second fill colour. */
            UiGfx.gradientV(context.batcher, x + 1, y - lift, ex - x - 2, 2,
                UiGfx.alpha(0xFFFFFFFF, 0.05F + 0.05F * hv), 0x00FFFFFF);
        }

        if (border != 0)
        {
            context.batcher.outline(x, y - lift, ex, ey - lift, border);
        }

        /* A 2 px accent rail marks the primary action without flooding the row with colour. */
        if (this.style == Style.PRIMARY)
        {
            context.batcher.box(x, y - lift, x + 2, ey - lift, Colors.mulRGB(0xFFFFFFFF, 0.85F));
        }
        else if (this.style == Style.SELECTED)
        {
            context.batcher.box(x, y - lift, x + 2, ey - lift,
                BBSSettings.primaryColor() | 0xFF000000);
        }
        else if (hv > 0.01F && this.style != Style.GHOST)
        {
            UiGfx.accentRail(context.batcher, x, y - lift + 1, ey - y - 2, hv, BBSSettings.primaryColor());
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

            context.batcher.text(valueText, ex - paddingX - valueWidth, this.area.my(size) - lift, CompUi.mutedText(), false);
        }

        if (this.icon != null)
        {
            int iconY = this.area.my(this.icon.h);

            context.batcher.icon(this.icon, text, x + paddingX, iconY - lift);
        }

        String label = font.limitToWidth(this.label.get(), Math.max(4, this.area.w - paddingX * 2 - iconSpace - rightSpace));
        int labelX = this.alignRight
            ? ex - paddingX - font.getWidth(label)
            : x + paddingX + iconSpace;

        context.batcher.text(label, labelX, this.area.my(size) - lift, text, false);

        this.renderLockedArea(context);
    }
}
