package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
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
    private final PressLatch press = new PressLatch();
    private int motionFrames;

    /** How often idle motion channels are pruned; see {@link UiMotion#prune(float)}. */
    static final int PRUNE_EVERY = 60;
    /** Channels untouched for this long are dead rows/buttons and can be dropped. */
    static final float PRUNE_IDLE = 2F;

    /**
     * Press ownership for a BBS clickable.
     *
     * <p>BBS fires a button's action on the <b>press</b> and clears its own {@code pressed} flag on a
     * release event. That release is delivered by walking the element tree, so a press whose owner
     * is rebuilt (an inspector page swap, a row list refresh) never sees it and would stay latched
     * in its pressed pose forever. This latch follows the click <b>and</b> re-checks the physical
     * button once per frame: a lost release can never leave a button looking held down.</p>
     */
    static final class PressLatch
    {
        private boolean held;

        /** Latch a press that landed on this element; a press outside/disabled is not ours. */
        boolean press(boolean inside, boolean enabled)
        {
            this.held = inside && enabled;

            return this.held;
        }

        boolean release()
        {
            this.held = false;

            return false;
        }

        /** Frame poll: the latch can only stay held while the pointer button really is down. */
        boolean poll(boolean physicalDown, boolean enabled)
        {
            if (!physicalDown || !enabled)
            {
                this.held = false;
            }

            return this.held;
        }

        boolean held()
        {
            return this.held;
        }
    }

    /** Drawing inset of the pressed pose: 1 px once the spring is past half way (pure, testable). */
    static int pressInset(float press)
    {
        return UiMotion.clamp01(press) >= 0.5F ? 1 : 0;
    }

    /** Hover lift in pixels; pressing cancels it so the button sinks instead of floating. */
    static float lift(float hover, float press, float intensity, float enabled)
    {
        return UiMotion.clamp01(hover) * (1F - UiMotion.clamp01(press))
            * UiMotion.clamp01(intensity) * UiMotion.clamp01(enabled) * 2F;
    }

    /** Alpha factor of the disabled pose (1 = fully enabled, 0.5 = faded). */
    static float dim(float enabled)
    {
        return 0.5F + 0.5F * UiMotion.clamp01(enabled);
    }

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
    public boolean subMouseClicked(UIContext context)
    {
        boolean handled = super.subMouseClicked(context);

        if (handled && context.mouseButton == 0)
        {
            this.press.press(this.area.isInside(context), this.isEnabled());
        }

        return handled;
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        this.press.release();

        return super.subMouseReleased(context);
    }

    private void tickMotion()
    {
        this.motion.tickFrame();

        if (++this.motionFrames >= PRUNE_EVERY)
        {
            this.motionFrames = 0;
            /* Buttons are rebuilt constantly (inspector pages, list rows); without this the
             * channel maps would keep one entry per button ever created. */
            this.motion.prune(PRUNE_IDLE);
        }
    }

    @Override
    protected void renderSkin(UIContext context)
    {
        this.tickMotion();

        boolean enabled = this.isEnabled();

        /* The press is owned by the click, but the release is not guaranteed to reach a button
         * whose parent was rebuilt under the pointer — poll the physical button as a fallback. */
        this.press.poll(Window.isMouseButtonPressed(0), enabled);

        int x = this.area.x;
        int y = this.area.y;
        int ex = this.area.ex();
        int ey = this.area.ey();
        float hv = this.motion.anim("hover", this.hover ? 1F : 0F, 20F);
        float pv = this.motion.spring("press", this.press.held() ? 1F : 0F, 520F, 30F);
        float en = this.motion.spring("enabled", enabled ? 1F : 0F, 320F, 28F);
        float intensity = this.motion.intensity();
        float lift = lift(hv, pv, intensity, en);
        int inset = pressInset(pv);
        float dim = dim(en);
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

        /* Pressed = a darker, 1 px smaller chip: the spring supplies the rebound. Disabled fades
         * the whole chip instead of switching it instantly. */
        if (fill != 0)
        {
            fill = UiMotion.lerpColor(fill, Colors.mulRGB(fill, 0.90F), pv);
            fill = UiMotion.alpha(fill, dim);
        }

        border = UiMotion.alpha(border, dim);
        text = UiMotion.alpha(text, dim);

        /* Only the painted rectangle shrinks/rises; the hit area (area) never moves, so a button
         * cannot become harder to click while it animates. */
        float left = x + inset;
        float top = y - lift + inset;
        float right = ex - inset;
        float bottom = ey - lift - inset;

        if (hv > 0.01F && CompPrefs.get().shadows)
        {
            UiGfx.shadow(context.batcher, left, top, right - left, bottom - top, 2.5F,
                UiGfx.alpha(0xFF000000, 0.10F * hv * intensity));
        }

        if (fill != 0)
        {
            context.batcher.box(left, top, right, bottom, fill);

            /* A 1 px top highlight sells the "raised chip" without a second fill colour. */
            UiGfx.gradientV(context.batcher, left + 1, top, Math.max(0F, right - left - 2F), 2,
                UiGfx.alpha(0xFFFFFFFF, 0.05F + 0.05F * hv), 0x00FFFFFF);
        }

        if (border != 0)
        {
            context.batcher.outline(left, top, right, bottom, border);
        }

        /* A 2 px accent rail marks the primary action without flooding the row with colour. */
        if (this.style == Style.PRIMARY)
        {
            context.batcher.box(left, top, left + 2, bottom, Colors.mulRGB(0xFFFFFFFF, 0.85F));
        }
        else if (this.style == Style.SELECTED)
        {
            context.batcher.box(left, top, left + 2, bottom,
                BBSSettings.primaryColor() | 0xFF000000);
        }
        else if (hv > 0.01F && this.style != Style.GHOST)
        {
            UiGfx.accentRail(context.batcher, left, top + 1, bottom - top - 2, hv, BBSSettings.primaryColor());
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
