package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Area;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * The sponsor hall.
 *
 * <p>This is deliberately not another settings list. A sponsor page is a thank-you, so it gets its
 * own visual language: a deep navy stage, drifting gold light, an orbital ring behind the title,
 * and three hand-composed cards whose names shimmer when the pointer comes near. Everything is
 * drawn with BBS's batcher and the shared {@link UiMotion} clock, which keeps it free of GL state,
 * shaders and framebuffer lifetimes — the same reason the onboarding screen is safe.</p>
 *
 * <p>Responsive: three columns on a wide editor, a vertical stack below 720 px. The card is capped
 * to the available area, so a small window still gets the whole list (and can scroll the page with
 * the wheel when it is shorter than the content).</p>
 */
public class UISponsors extends UIElement
{
    private static final String[] NAMES = {"星辰久梦君", "血之书", "Szv文森特37"};
    private static final String[] TITLES = {"星海守望者", "赤诚之书", "光影同行者"};
    private static final String[] QUOTES =
    {
        "把漫长的夜，做成可以回放的星轨。",
        "每一帧热爱，都值得被认真对待。",
        "让想象先抵达，再让画面跟上。"
    };
    private static final int[] ACCENTS = {0xFFFFD27A, 0xFFFF7D7D, 0xFF7AD8FF};

    private final UICompositorPanel panel;
    private final UiBackdrop backdrop = new UiBackdrop();
    private final UiMotion motion = new UiMotion();
    private final UICompButton close;
    private float scroll;
    private float scrollTarget;

    public UISponsors(UICompositorPanel panel)
    {
        this.panel = panel;
        this.setVisible(false);

        this.close = new UICompButton(IKey.constant("关闭"), (button) -> this.close());
        this.close.style(UICompButton.Style.GHOST);
        this.close.h(20);
        this.close.w(54);
        this.close.tooltip(IKey.constant("关闭赞助者页面（Esc）"));
        super.add(this.close);
    }

    public void open()
    {
        this.scroll = 0F;
        this.scrollTarget = 0F;
        this.setVisible(true);
        this.motion.replay();
        this.panel.resize();
        this.resize();
    }

    public void close()
    {
        this.setVisible(false);
    }

    public boolean isOpen()
    {
        return this.visible;
    }

    @Override
    public void resize()
    {
        super.resize();
        this.close.relative(this).x(1F, -74).y(18).w(54).h(20);
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();
        this.motion.markShown();

        if (!CompPrefs.animating())
        {
            this.scroll = this.scrollTarget;
        }
        else
        {
            this.scroll += (this.scrollTarget - this.scroll) * (1F - (float) Math.exp(-12F * this.motion.dt));
        }

        this.backdrop.render(context, this.area);

        /* A warm vignette over the BBS-green backdrop, so the page is recognisably a thank-you
         * rather than another settings dialog. */
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0x66120C04);
        context.batcher.clip(this.area, context);

        try
        {
            this.renderBeams(context);
            this.renderContent(context);
        }
        finally
        {
            context.batcher.unclip(context);
        }

        /* The close button is a real BBS widget, so keyboard focus and hover stay native. */
        super.render(context);
    }

    private void renderBeams(UIContext context)
    {
        if (!CompPrefs.animating())
        {
            return;
        }

        float t = this.motion.time;

        for (int i = 0; i < 4; i++)
        {
            float phase = (t * (0.022F + i * 0.006F) + i * 0.27F) % 1F;
            float x = this.area.x + this.area.w * (0.15F + i * 0.25F) + (phase - 0.5F) * 120F;
            int alpha = (int) (10 + 10 * Math.sin((phase + i * 0.2F) * Math.PI * 2F));

            if (alpha <= 0)
            {
                continue;
            }

            MatrixStack stack = context.batcher.getContext().getMatrices();

            stack.push();
            stack.translate(x, this.area.y + this.area.h * 0.2F, 0F);
            context.batcher.gradientVBox(0F, 0F, 2F, this.area.h * 0.65F,
                UiGfx.alpha(0xFFFFD27A, alpha / 255F * 0.4F), 0x00FFD27A);
            stack.pop();
        }
    }

    private void renderOrbit(UIContext context, float cx, float cy, float radius)
    {
        if (radius < 40F)
        {
            return;
        }

        for (int i = 0; i < 3; i++)
        {
            float a = 0.14F - i * 0.035F;
            float rx = radius * (1F + i * 0.18F);
            float ry = radius * (0.32F + i * 0.07F);

            context.batcher.outlineCenter(cx, cy, rx, 0, UiGfx.alpha(0xFFFFD27A, a));
        }

        float angle = this.motion.time * 0.22F;

        for (int i = 0; i < 13; i++)
        {
            double a = angle + i * Math.PI * 2D / 13D;
            float x = cx + (float) Math.cos(a) * radius * 1.28F;
            float y = cy + (float) Math.sin(a) * radius * 0.42F;
            float pulse = 0.4F + 0.6F * (float) Math.sin(this.motion.time * 1.5F + i * 1.7F);

            context.batcher.box(x - 1F, y - 1F, x + 1F, y + 1F,
                UiGfx.alpha(0xFFFFD27A, 0.18F + 0.25F * Math.max(0F, pulse)));
        }
    }

    private float listTop(float top)
    {
        return top + 170F;
    }

    private float cardHeight()
    {
        return Math.max(150F, Math.min(300F, this.area.h * 0.30F));
    }

    private float layoutHeight(float cardWidth)
    {
        int columns = cardWidth >= 700F ? 3 : 1;
        float card = this.cardHeight();
        float cards = columns == 3 ? card : card * 3F + 24F;

        return 170F + cards + 150F;
    }

    private void renderContent(UIContext context)
    {
        float cardWidth = Math.min(this.area.w - 48F, 980F);
        float total = this.layoutHeight(cardWidth);
        float left = this.area.mx() - cardWidth * 0.5F;
        float top = Math.max(this.area.y + 32F, this.area.my() - total * 0.5F) - this.scroll;
        int gold = 0xFFFFD27A;

        /* Hero: a generous, centred title rather than a settings-row label. */
        float heroCx = this.area.mx();
        float heroCy = top + 64F;
        float radius = Math.min(cardWidth * 0.30F, 220F);

        UiGfx.glow(context.batcher, (int) heroCx, (int) heroCy, (int) Math.max(90F, radius * 1.1F),
            UiGfx.alpha(gold, 0.09F), 48);
        this.renderOrbit(context, heroCx, heroCy, radius);

        String kicker = "SUPPORTERS   /   BBS COMPOSITOR   /   2026";
        String title = "赞助者殿堂";
        String tagline = "感谢你们，让每一帧都有了继续生长的理由。";
        int kickerWidth = context.batcher.getFont().getWidth(kicker);

        context.batcher.textShadow(kicker, heroCx - kickerWidth / 2F, top + 8F, UiGfx.alpha(gold, 0.78F));
        context.batcher.box(heroCx - 170F, top + 26F, heroCx - 12F, top + 27F, UiGfx.alpha(gold, 0.35F));
        context.batcher.box(heroCx + 12F, top + 26F, heroCx + 170F, top + 27F, UiGfx.alpha(gold, 0.35F));
        this.drawScaledText(context, title, heroCx, top + 40F, 2.35F, 0xFFFFF3D6);
        context.batcher.textShadow(tagline, heroCx - context.batcher.getFont().getWidth(tagline) / 2F,
            top + 80F, 0xFFC8BBA8);

        String stats = "03  位同行者      ·      1  个社区      ·      ∞  次更新";
        context.batcher.textShadow(stats, heroCx - context.batcher.getFont().getWidth(stats) / 2F,
            top + 102F, 0xFF8E806E);

        /* Cards. */
        int columns = cardWidth >= 700F ? 3 : 1;
        float gap = 14F;
        float cellW = columns == 3 ? (cardWidth - gap * 2F) / 3F : cardWidth;
        float cellH = this.cardHeight();
        float listTop = this.listTop(top);

        for (int i = 0; i < NAMES.length; i++)
        {
            int col = columns == 3 ? i : 0;
            int row = columns == 3 ? 0 : i;
            float x = left + col * (cellW + gap);
            float y = listTop + row * (cellH + gap);

            this.renderSponsorCard(context, x, y, cellW, cellH, i);
        }

        float bottom = listTop + (columns == 3 ? cellH : cellH * 3F + gap * 2F) + 22F;

        this.renderLetter(context, left, bottom, cardWidth);
        context.batcher.textShadow("排名不分先后 · 名单会随支持持续更新 · github.com/zhongend/bbsae",
            left, bottom + 76F, 0xFF7C7267);

        if (!this.area.isInside(context.mouseX, context.mouseY))
        {
            return;
        }

        context.resetTooltip();
    }

    private void renderLetter(UIContext context, float x, float y, float width)
    {
        context.batcher.box(x, y, x + width, y + 64F, 0xB30E0D0A);
        context.batcher.outline(x, y, x + width, y + 64F, 0x33FFD27A);
        context.batcher.box(x, y, x + 2F, y + 64F, 0xAAFFD27A);
        context.batcher.textShadow("“因为你们，合成器才有下一个版本。”", x + 18F, y + 14F, 0xFFE7D9C2);
        context.batcher.textShadow("— 终end  /  以及每一位愿意把它分享出去的人", x + 18F, y + 34F, 0xFF988A76);
        context.batcher.textShadow("关注更新、反馈问题、把作品发给朋友，都是支持。", x + 18F, y + 48F, 0xFF7C7267);
    }

    /** Draws text at a larger scale; used for the hero title and card ranks. */
    private void drawScaledText(UIContext context, String text, float centerX, float y, float scale, int color)
    {
        MatrixStack stack = context.batcher.getContext().getMatrices();
        float width = context.batcher.getFont().getWidth(text) * scale;

        stack.push();
        stack.translate(centerX - width / 2F, y, 0F);
        stack.scale(scale, scale, 1F);
        context.batcher.textShadow(text, 0F, 0F, color);
        stack.pop();
    }

    private void renderSponsorCard(UIContext context, float x, float y, float w, float h, int index)
    {
        float intro = this.motion.introBack(0.10F + index * 0.08F);
        float lift = (1F - intro) * 18F;
        float hover = this.motion.anim("sponsor:" + index,
            this.area.isInside(context.mouseX, context.mouseY) && this.cardContains(context, x, y, w, h) ? 1F : 0F, 14F);
        int accent = ACCENTS[index];
        boolean compact = h < 180F;
        float shimmer = 0.5F + 0.5F * (float) Math.sin(this.motion.time * 1.7F + index * 2.1F);

        y += lift - hover * 4F;

        float alpha = 0.55F + 0.45F * intro;
        float inner = x + 1F;

        UiGfx.shadow(context.batcher, x, y, w, h, 12F + hover * 10F, 0x77000000);
        UiGfx.gradientV(context.batcher, x, y, w, h,
            UiGfx.alpha(0xFF211E1A, alpha), UiGfx.alpha(0xFF0B0C0F, alpha));
        context.batcher.outline(x, y, x + w, y + h, UiGfx.alpha(0x66FFFFFF, alpha));
        UiGfx.gradientH(context.batcher, x, y, w, 2F,
            UiGfx.alpha(accent, 0.9F * alpha), UiGfx.alpha(accent, 0.04F * alpha));

        /* A second accent rail at the bottom makes the card feel framed rather than taped on. */
        context.batcher.box(x, y + h - 1F, x + w, y + h, UiGfx.alpha(accent, 0.18F * alpha));

        UiGfx.glow(context.batcher, (int) (x + w * 0.5F), (int) (y + h * 0.42F),
            (int) Math.max(60F, Math.min(w, h) * (0.34F + hover * 0.10F)),
            UiGfx.alpha(accent, 0.09F + hover * 0.08F), 40);

        /* Giant translucent rank number, right-aligned like a catalogue plate. */
        MatrixStack stack = context.batcher.getContext().getMatrices();
        String rank = String.format(Locale.ROOT, "%02d", index + 1);
        float rankScale = compact ? 1.8F : 3.2F;
        float rankWidth = context.batcher.getFont().getWidth(rank) * rankScale;

        stack.push();
        stack.translate(x + w - 18F - rankWidth, y + 18F, 0F);
        stack.scale(rankScale, rankScale, 1F);
        context.batcher.textShadow(rank, 0F, 0F, UiGfx.alpha(accent, 0.08F + hover * 0.05F));
        stack.pop();

        /* Seal. A rotated square would need a custom vertex path; a gold frame plus a diamond
         * glyph reads as a seal at this size and stays within the batcher. */
        float cx = x + 27F;
        float cy = y + 30F;

        context.batcher.box(cx - 11F, cy - 11F, cx + 11F, cy + 11F, UiGfx.alpha(accent, 0.12F));
        context.batcher.outline(cx - 11F, cy - 11F, cx + 11F, cy + 11F, UiGfx.alpha(accent, 0.8F));
        context.batcher.outline(cx - 7F, cy - 7F, cx + 7F, cy + 7F, UiGfx.alpha(accent, 0.25F));
        context.batcher.textShadow("◆", cx - 3F, cy - 4F, accent);

        float nameX = x + 46F;
        float nameY = y + 22F;
        int nameColor = UiGfx.lerp(0xFFFFF7E8, accent, 0.06F + hover * 0.22F);

        context.batcher.textShadow(NAMES[index], nameX, nameY, nameColor);
        context.batcher.textShadow(TITLES[index], nameX, nameY + 16F,
            UiGfx.alpha(accent, 0.68F + shimmer * 0.20F));

        if (compact)
        {
            context.batcher.textShadow(QUOTES[index], x + 16F, y + 70F, 0xFFBDB3A4);
            context.batcher.textShadow("FOUNDING SUPPORTER", x + 16F, y + h - 24F,
                UiGfx.alpha(accent, 0.45F));
            this.renderCardSparkles(context, x, y, w, h, accent, alpha, index);

            return;
        }

        context.batcher.box(x + 16F, y + 64F, x + w - 16F, y + 65F, 0x22FFFFFF);

        /* Quote block, with an oversized opening mark as the only ornament. */
        context.batcher.textShadow("“", x + 14F, y + 70F, UiGfx.alpha(accent, 0.28F));
        context.batcher.textShadow(QUOTES[index], x + 30F, y + 78F, 0xFFD2C7B6);
        context.batcher.textShadow("— 感谢支持", x + 30F, y + 100F, 0xFF81776A);

        context.batcher.textShadow("FOUNDING SUPPORTER  ·  " + rank, x + 16F, y + h - 42F,
            UiGfx.alpha(accent, 0.54F));
        context.batcher.textShadow("BBS COMPOSITOR  /  2026", x + 16F, y + h - 24F, 0xFF6D655B);

        this.renderCardSparkles(context, x, y, w, h, accent, alpha, index);

        int barY = (int) (y + h - 12F);
        float progress = UiMotion.clamp01(0.22F + hover * 0.55F + shimmer * 0.14F);

        context.batcher.box(x + 16F, barY, x + w - 16F, barY + 2F, 0x22FFFFFF);
        context.batcher.box(x + 16F, barY, x + 16F + (w - 32F) * progress, barY + 2F,
            UiGfx.alpha(accent, 0.76F));

        /* The pointer card gets a travelling top highlight. */
        if (hover > 0.01F)
        {
            float sweep = (this.motion.time * 0.42F + index * 0.21F) % 1F;
            float sx = x + 12F + sweep * (w - 24F);
            context.batcher.box(sx - 28F, y + 2F, sx + 28F, y + 4F,
                UiGfx.alpha(accent, 0.30F * hover));
        }
    }

    private void renderCardSparkles(UIContext context, float x, float y, float w, float h, int accent, float alpha, int index)
    {
        for (int i = 0; i < 20; i++)
        {
            float px = x + 14F + ((i * 37 + index * 19) % 100) / 100F * (w - 28F);
            float py = y + h - 26F - ((i * 53 + index * 11) % 100) / 100F * Math.max(40F, h * 0.34F);
            float twinkle = 0.5F + 0.5F * (float) Math.sin(this.motion.time * (0.9F + i * 0.07F) + i);
            int a = (int) ((0.08F + 0.22F * twinkle) * alpha * 255F);

            context.batcher.box(px, py, px + 1F, py + 1F, (a << 24) | (accent & 0xFFFFFF));
        }
    }

    private boolean cardContains(UIContext context, float x, float y, float w, float h)
    {
        return context.mouseX >= x && context.mouseX <= x + w && context.mouseY >= y && context.mouseY <= y + h;
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (context.getKeyAction() == mchorse.bbs_mod.ui.utils.keys.KeyAction.PRESSED
            && context.getKeyCode() == GLFW.GLFW_KEY_ESCAPE)
        {
            this.close();

            return true;
        }

        return super.subKeyPressed(context);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        /* Children first (the close button is a real widget), then swallow clicks on the stage so
         * a stray click cannot edit the editor underneath. */
        if (super.subMouseClicked(context))
        {
            return true;
        }

        return this.visible;
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.visible && Math.abs(context.mouseWheel) > 0.001D)
        {
            float content = this.layoutHeight(Math.min(this.area.w - 48F, 980F));
            float max = Math.max(0F, content - this.area.h + 80F);

            this.scrollTarget = Math.max(0F, Math.min(max, this.scrollTarget - (float) context.mouseWheel * 26F));
            this.scroll = this.scrollTarget;

            return true;
        }

        return super.subMouseScrolled(context);
    }
}
