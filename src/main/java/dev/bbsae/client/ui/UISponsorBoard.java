package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/**
 * Sponsor board embedded in Settings.
 *
 * <p>It is intentionally not a dashboard page: the sponsor list belongs with credits/about, not
 * with the shot the editor is working on. The board keeps the gold-orbit / drifting-light language
 * of the dedicated sponsor page, but composes it inside the settings card and the scroll view.</p>
 *
 * <p>Content is deliberately minimal: names and one plain thank-you. No per-person motivational
 * copy, no tiers, no sales pitch.</p>
 */
public class UISponsorBoard extends UIElement
{
    public static final String[] NAMES = {"星辰久梦君", "血之书", "Szv文森特37"};
    private static final int[] ACCENTS = {0xFFFFD27A, 0xFFFF8A8A, 0xFF7AD8FF};

    private final UiMotion motion = new UiMotion();

    public UISponsorBoard()
    {
        this.w(1F).h(326);
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();
        this.motion.markShown();

        int x = this.area.x;
        int y = this.area.y;
        int w = this.area.w;
        int h = this.area.h;
        int gold = 0xFFFFD27A;

        /* A dark plate instead of another settings row: the board should read as a small poster. */
        context.batcher.box(x, y, x + w, y + h, 0xFF0D0F13);
        UiGfx.gradientV(context.batcher, x, y, w, h, 0x181A160F, 0xFF0D0F13);
        context.batcher.outline(x, y, x + w, y + h, 0x33FFD27A);

        context.batcher.clip(this.area, context);

        try
        {
            this.renderLights(context, x, y, w, h);
            this.renderHeader(context, x, y, w);

            float cardTop = y + 78F;
            boolean wide = w >= 540F;
            float gap = 10F;
            int columns = wide ? 3 : 1;
            float cardW = wide ? (w - 32F - gap * 2F) / 3F : w - 32F;
            float cardH = wide ? 150F : 64F;

            for (int i = 0; i < NAMES.length; i++)
            {
                float cx = x + 16F + (i % columns) * (cardW + gap);
                float cy = cardTop + (i / columns) * (cardH + gap);

                this.renderCard(context, cx, cy, cardW, cardH, i);
            }

            context.batcher.textShadow("感谢你们赞助支持本插件的开发。", x + 16F, y + h - 22F, 0xFF918573);
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void renderHeader(UIContext context, float x, float y, float w)
    {
        int gold = 0xFFFFD27A;
        String kicker = "THANK YOU  /  BBS COMPOSITOR";

        context.batcher.textShadow(kicker, x + 16F, y + 12F, UiGfx.alpha(gold, 0.65F));
        context.batcher.textShadow("赞助者", x + 16F, y + 28F, 0xFFFFF4DE);
        context.batcher.textShadow("你们的赞助支持了本插件的开发。", x + 16F + context.batcher.getFont().getWidth("赞助者") + 20F,
            y + 30F, 0xFF8C8171);

        /* A small orbital mark, using the same vocabulary as the previous sponsor page. */
        float cx = x + w - 42F;
        float cy = y + 30F;
        float radius = 24F;

        for (int i = 0; i < 2; i++)
        {
            context.batcher.outlineCenter(cx, cy, radius * (1F + i * 0.28F), 0,
                UiGfx.alpha(gold, 0.16F - i * 0.05F));
        }

        float angle = this.motion.time * 0.8F;

        for (int i = 0; i < 5; i++)
        {
            double a = angle + i * Math.PI * 2D / 5D;
            float px = cx + (float) Math.cos(a) * radius * 1.28F;
            float py = cy + (float) Math.sin(a) * radius * 0.4F;

            context.batcher.box(px - 1F, py - 1F, px + 1F, py + 1F,
                UiGfx.alpha(gold, 0.30F + 0.20F * (float) Math.sin(this.motion.time * 1.4F + i)));
        }
    }

    private void renderLights(UIContext context, float x, float y, float w, float h)
    {
        int gold = 0xFFFFD27A;

        /* Vertical light shafts; deterministic motion, no audio/random state. */
        for (int i = 0; i < 3; i++)
        {
            float phase = (this.motion.time * (0.025F + i * 0.004F) + i * 0.31F) % 1F;
            float px = x + 24F + phase * (w - 48F);

            UiGfx.gradientV(context.batcher, px, y + 54F, 1F, h - 74F,
                UiGfx.alpha(gold, 0.05F), 0x00FFD27A);
        }

        for (int i = 0; i < 26; i++)
        {
            float px = x + 16F + ((i * 37 + 11) % 100) / 100F * (w - 32F);
            float py = y + 60F + ((i * 53 + 7) % 100) / 100F * (h - 82F);
            float twinkle = 0.5F + 0.5F * (float) Math.sin(this.motion.time * (0.5F + i * 0.03F) + i * 0.9F);

            context.batcher.box(px, py, px + 1F, py + 1F,
                UiGfx.alpha(gold, 0.04F + 0.10F * twinkle));
        }
    }

    private void renderCard(UIContext context, float x, float y, float w, float h, int index)
    {
        float intro = this.motion.introBack(0.06F + index * 0.06F);
        float hover = this.motion.anim("card:" + index,
            this.area.isInside(context.mouseX, context.mouseY)
                && context.mouseX >= x && context.mouseX <= x + w
                && context.mouseY >= y && context.mouseY <= y + h ? 1F : 0F, 16F);
        int accent = ACCENTS[index];
        float lift = (1F - intro) * 8F - hover * 2F;

        y += lift;

        UiGfx.gradientV(context.batcher, x, y, w, h, 0xFF1A1815, 0xFF0B0C0F);
        context.batcher.outline(x, y, x + w, y + h, UiGfx.alpha(accent, 0.24F + hover * 0.28F));
        context.batcher.box(x, y, x + w, y + 2F, UiGfx.alpha(accent, 0.55F + hover * 0.25F));
        UiGfx.glow(context.batcher, (int) (x + w * 0.5F), (int) (y + h * 0.55F),
            (int) Math.max(44F, Math.min(w, h) * 0.34F), UiGfx.alpha(accent, 0.07F + hover * 0.06F), 28);

        float nameY = y + (h < 90F ? 16F : 34F);

        context.batcher.textShadow(NAMES[index], x + 14F, nameY, 0xFFF4EBDD);
        context.batcher.textShadow("感谢你赞助本插件的开发。", x + 14F, nameY + 22F, 0xFFA99D8B);
        context.batcher.textShadow(String.format(java.util.Locale.ROOT, "%02d", index + 1),
            x + w - 26F, y + 12F, UiGfx.alpha(accent, 0.35F));

        /* The only ornament below the line: a short accent rule that responds to the pointer. */
        int barY = (int) (y + h - 12F);
        float progress = 0.32F + hover * 0.5F + 0.08F * (float) Math.sin(this.motion.time * 1.4F + index);

        context.batcher.box(x + 14F, barY, x + w - 14F, barY + 1F, 0x22FFFFFF);
        context.batcher.box(x + 14F, barY, x + 14F + (w - 28F) * progress, barY + 1F,
            UiGfx.alpha(accent, 0.7F));
    }
}
