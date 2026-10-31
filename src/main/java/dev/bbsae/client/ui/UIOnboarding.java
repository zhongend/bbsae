package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BBS-style first-run onboarding.
 *
 * <p>The background is the same language as BBS's own welcome overlay: a deep surface, drifting
 * lights, a pointer-following glow and sparks that are pushed away by the cursor. The pages are
 * deliberately choices, not a wall of text: layout, motion intensity, shortcut scheme — the three
 * decisions that make the first ten minutes of a creative tool feel personal.</p>
 *
 * <p>The component is drawn manually instead of being a stack of BBS widgets because the cards need
 * their own hover/selection/entrance motion and a diagram preview; the hit regions are explicit and
 * keyboard navigation (←/→, Enter, Esc) mirrors the click targets.</p>
 */
public class UIOnboarding extends UIElement
{
    public static final String[] LAYOUT_NAMES = {"标准", "专注", "紧凑", "宽屏"};
    public static final int LAYOUT_STANDARD = 0;
    public static final int LAYOUT_FOCUS = 1;
    public static final int LAYOUT_COMPACT = 2;
    public static final int LAYOUT_WIDE = 3;
    public static final String[] LAYOUT_HINTS =
    {
        "左侧效果/右侧检查器平衡", "更大预览，更小面板", "最大化编辑面积", "宽屏双栏"
    };
    public static final String[] MOTION_NAMES = {"平衡", "电影感", "极简"};
    public static final String[] MOTION_HINTS =
    {
        "快速、克制、有层次", "更慢的光影与视差", "关闭光效，只保留响应"
    };

    private static final int PAGES = 5;

    private final UICompositorPanel panel;
    private final UiBackdrop backdrop = new UiBackdrop();
    private final UiMotion motion = new UiMotion();
    private final List<Hit> hits = new ArrayList<>();
    private int page;
    private int pageDirection = 1;
    private int selectedLayout;
    private int selectedMotion;
    private int selectedScheme;
    private long pageAt;
    private long lastActionAt;
    private Runnable onClose;

    private static final class Hit
    {
        final int x;
        final int y;
        final int w;
        final int h;
        final Runnable action;

        Hit(int x, int y, int w, int h, Runnable action)
        {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }

        boolean inside(int mx, int my)
        {
            return mx >= this.x && mx <= this.x + this.w && my >= this.y && my <= this.y + this.h;
        }
    }

    public UIOnboarding(UICompositorPanel panel)
    {
        this.panel = panel;
        this.setVisible(false);
        this.selectedLayout = CompPrefs.get().layoutPreset;
        this.selectedScheme = CompPrefs.get().shortcutScheme;

        CompPrefs.Data prefs = CompPrefs.get();

        this.selectedMotion = !prefs.animations ? 2 : (prefs.motionIntensity > 1.1F ? 1 : 0);
    }

    public void open(Runnable onClose)
    {
        this.onClose = onClose;
        this.page = 0;
        this.pageAt = System.nanoTime();
        this.setVisible(true);
        this.motion.replay();
        this.panel.resize();
    }

    public void close(boolean finish)
    {
        if (finish)
        {
            CompPrefs.setOnboardingDone();
        }
        else
        {
            /* Skipping still counts as seen: otherwise every dashboard open would nag. The
             * settings page has an explicit "replay" button. */
            CompPrefs.setOnboardingDone();
        }

        this.setVisible(false);

        Runnable callback = this.onClose;

        this.onClose = null;

        if (callback != null)
        {
            callback.run();
        }
    }

    public boolean isOpen()
    {
        return this.visible;
    }

    /** Verification hook: lock the page so automated input cannot walk the wizard. */
    public void debugPage(int page)
    {
        this.page = Math.max(0, Math.min(PAGES - 1, page));
        this.pageAt = System.nanoTime();
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();
        this.motion.markShown();
        this.hoverX = context.mouseX;
        this.hoverY = context.mouseY;
        this.backdrop.render(context, this.area);
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0x4A000000);

        float p = this.motion.introBack(0F);
        int cardW = Math.min(820, Math.max(620, this.area.w - 48));
        int cardH = Math.min(470, Math.max(380, this.area.h - 56));
        int cardX = this.area.x + (this.area.w - cardW) / 2;
        int cardY = this.area.y + (this.area.h - cardH) / 2;

        /* A restrained scale/translate entrance. */
        MatrixStack stack = context.batcher.getContext().getMatrices();
        float cx = cardX + cardW / 2F;
        float cy = cardY + cardH / 2F;
        float scale = 0.97F + 0.03F * p;

        stack.push();
        stack.translate(cx, cy, 0F);
        stack.scale(scale, scale, 1F);
        stack.translate(-cx, -cy, 0F);
        stack.translate(0F, (1F - p) * 10F, 0F);

        try
        {
            this.hits.clear();
            this.renderCard(context, cardX, cardY, cardW, cardH);

            /* The backdrop light is behind an opaque card; reflect a restrained version of it
             * on the card surface so pointer movement is still felt while reading a page. */
            if (CompPrefs.get().glow)
            {
                context.batcher.clip(cardX + 2, cardY + 2, cardW - 4, cardH - 4, context);

                try
                {
                    UiGfx.glow(context.batcher, context.mouseX, context.mouseY, 210,
                        UiGfx.alpha(BBSSettings.primaryColor(), 0.10F * CompPrefs.intensity()), 48);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            this.renderPage(context, cardX, cardY, cardW, cardH);
            this.renderNavigation(context, cardX, cardY, cardW, cardH);
        }
        finally
        {
            stack.pop();
        }

        super.render(context);
    }

    private void renderCard(UIContext context, int x, int y, int w, int h)
    {
        UiGfx.shadow(context.batcher, x, y, w, h, 22F, 0x88000000);
        context.batcher.box(x, y, x + w, y + h, 0xF20D131C);
        context.batcher.box(x, y, x + w, y + 2, BBSSettings.primaryColor() | 0xFF000000);
        UiGfx.gradientV(context.batcher, x, y + 2, w, 70,
            UiGfx.alpha(BBSSettings.primaryColor(), 0.10F), 0x00FFFFFF);
        context.batcher.outline(x, y, x + w, y + h, 0xFF3A4658);

        String title = "BBS 合成器   ·   欢迎";
        FontRenderer font = context.batcher.getFont();
        int tw = font.getWidth(title) * 2;

        MatrixStack stack = context.batcher.getContext().getMatrices();

        stack.push();
        stack.translate(x + (w - tw) / 2F, y + 30, 0F);
        stack.scale(2F, 2F, 1F);
        context.batcher.text(title, 0F, 0F, 0xFFFFFFFF, true);
        stack.pop();

        context.batcher.text("用专业工具的思路，在 BBS 影片上再叠一层画面",
            x + w / 2F - context.batcher.getFont().getWidth("用专业工具的思路，在 BBS 影片上再叠一层画面") / 2F,
            y + 66, 0xFF9EABBD);

        /* Close/skip in the corner, intentionally quiet. */
        int sx = x + w - 72;

        context.batcher.text("跳过", sx + 10, y + 16, 0x99B8C4D6);
        this.hits.add(new Hit(sx, y + 8, 58, 24, () -> this.close(false)));
        context.batcher.box(sx, y + 30, sx + 52, y + 31, 0x44FFFFFF);
    }

    private void renderNavigation(UIContext context, int x, int y, int w, int h)
    {
        int dots = PAGES;
        int gap = 8;
        int total = dots * 6 + (dots - 1) * gap;
        int dx = x + (w - total) / 2;
        int dy = y + h - 24;

        for (int i = 0; i < dots; i++)
        {
            boolean active = i == this.page;
            int color = active ? (BBSSettings.primaryColor() | 0xFF000000) : 0x66758595;
            int dotX = dx + i * (6 + gap);

            context.batcher.box(dotX, dy, dotX + (active ? 14 : 6), dy + 6, color);

            final int target = i;
            this.hits.add(new Hit(dotX - 4, dy - 6, 18, 18, () -> this.show(target)));
        }

        context.batcher.text((this.page + 1) + " / " + PAGES, x + w - 52, y + h - 28, 0xFF6F7C8E);

        if (this.page > 0)
        {
            this.arrow(context, x - 44, y + h / 2 - 24, -1);
        }

        if (this.page < PAGES - 1)
        {
            this.arrow(context, x + w + 4, y + h / 2 - 24, 1);
        }
    }

    private void arrow(UIContext context, int x, int y, int direction)
    {
        boolean hover = this.area.isInside(context.mouseX, context.mouseY)
            && context.mouseX >= x && context.mouseX <= x + 40
            && context.mouseY >= y && context.mouseY <= y + 48;
        int color = hover ? 0xFFFFFFFF : 0x99A9B6C8;
        int iconX = x + 12;
        int iconY = y + 16;

        context.batcher.icon(direction > 0 ? mchorse.bbs_mod.ui.utils.icons.Icons.ARROW_RIGHT
            : mchorse.bbs_mod.ui.utils.icons.Icons.ARROW_LEFT, color, iconX, iconY);

        this.hits.add(new Hit(x, y, 40, 48, () -> this.show(this.page + direction)));
    }

    private void renderPage(UIContext context, int x, int y, int w, int h)
    {
        float elapsed = (System.nanoTime() - this.pageAt) / 1.0E9F;
        float p = CompPrefs.animating()
            ? UiMotion.easeOut((elapsed * Math.max(0.5F, CompPrefs.speed())) / 0.26F)
            : 1F;
        MatrixStack stack = context.batcher.getContext().getMatrices();

        stack.push();
        stack.translate((1F - p) * 18F * this.pageDirection, 0F, 0F);

        try
        {
            switch (this.page)
            {
                case 0 -> this.pageWelcome(context, x, y, w, h);
                case 1 -> this.pageLayout(context, x, y, w, h);
                case 2 -> this.pageMotion(context, x, y, w, h);
                case 3 -> this.pageShortcuts(context, x, y, w, h);
                default -> this.pageFinish(context, x, y, w, h);
            }
        }
        finally
        {
            stack.pop();
        }
    }

    private void pageWelcome(UIContext context, int x, int y, int w, int h)
    {
        int left = x + 48;

        context.batcher.textShadow("文本、形状、图片、蒙版、效果、AE 曲线、3D 图层……", left, y + 108, 0xFFDCE5F2);
        context.batcher.textShadow("所有的编辑都在 BBS 影片之上完成，并且随 BBS 一起预览和导出。", left, y + 132, 0xFF96A4B8);

        String[] lines =
        {
            "01   选择一套适合自己的工作区",
            "02   设置动效强度和光影",
            "03   选好快捷键方案，之后随时可以改"
        };

        for (int i = 0; i < lines.length; i++)
        {
            context.batcher.box(left, y + 174 + i * 30, left + 3, y + 184 + i * 30,
                BBSSettings.primaryColor() | 0xFF000000);
            context.batcher.textShadow(lines[i], left + 16, y + 170 + i * 30, 0xFFB7C3D3);
        }

        /* Three quiet capability cards fill the page without competing with the primary action. */
        String[] features = {"AE 曲线", "3D 图层", "BBS 原生导出"};
        mchorse.bbs_mod.ui.utils.icons.Icon[] icons = {
            mchorse.bbs_mod.ui.utils.icons.Icons.GRAPH,
            mchorse.bbs_mod.ui.utils.icons.Icons.SPHERE,
            mchorse.bbs_mod.ui.utils.icons.Icons.FILM
        };
        int chipW = Math.min(190, (w - 96 - 24) / 3);
        int chipY = y + 282;

        for (int i = 0; i < features.length; i++)
        {
            int cx = x + 48 + i * (chipW + 12);

            context.batcher.box(cx, chipY, cx + chipW, chipY + 64, 0x33151E28);
            context.batcher.box(cx, chipY, cx + 2, chipY + 64, 0x884C9AFF);
            context.batcher.icon(icons[i], 0xFF9FB2C8, cx + 12, chipY + 12);
            context.batcher.text(features[i], cx + 34, chipY + 12, 0xFFD6DEEB);
            context.batcher.text(i == 0 ? "贝塞尔与缓动曲线" : (i == 1 ? "透视、Z 深度与旋转" : "录像器直接渲染"),
                cx + 12, chipY + 38, 0xFF7C8A9E);
        }

        this.bigButton(context, x + w / 2 - 96, y + h - 86, 192, 30, "开始设置", true, () -> this.show(1));
    }

    private void pageLayout(UIContext context, int x, int y, int w, int h)
    {
        this.pageTitle(context, x, y, w, "选择工作区布局", "之后可以在 设置 → 界面布局 里随时切换");

        int cardW = Math.min(210, (w - 96) / 4);
        int gap = 12;
        int total = cardW * 4 + gap * 3;
        int start = x + (w - total) / 2;
        int cardY = y + 146;
        int cardH = 190;

        for (int i = 0; i < 4; i++)
        {
            int cx = start + i * (cardW + gap);
            float hover = this.motion.hover("layout:" + i, this.hovered(cx, cardY, cardW, cardH) ? "layout:" + i : null);
            float sel = this.motion.select("layout:" + i, i == this.selectedLayout);
            int fill = UiMotion.lerpColor(0xFF1A222D, 0xFF243040, hover);
            int outline = UiMotion.lerpColor(0xFF334052, BBSSettings.primaryColor(), sel);

            if (sel > 0.01F)
            {
                UiGfx.shadow(context.batcher, cx, cardY, cardW, cardH, 5F,
                    UiGfx.alpha(BBSSettings.primaryColor(), 0.12F * sel));
            }

            context.batcher.box(cx, cardY - Math.round(hover * 2F), cx + cardW, cardY + cardH - Math.round(hover * 2F), fill);
            context.batcher.outline(cx, cardY, cx + cardW, cardY + cardH, outline);
            this.layoutDiagram(context, cx + 22, cardY + 24, cardW - 44, 72, i, sel);
            context.batcher.text(LAYOUT_NAMES[i], cx + 12, cardY + 112, sel > 0.5F ? 0xFFFFFFFF : 0xFFD3DCE8);
            context.batcher.text(LAYOUT_HINTS[i], cx + 12, cardY + 132, 0xFF8492A6);
            context.batcher.text("点击应用", cx + 12, cardY + 160, 0xFF66758A);

            final int chosen = i;
            this.hits.add(new Hit(cx, cardY, cardW, cardH, () ->
            {
                this.selectedLayout = chosen;
                CompPrefs.get().layoutPreset = chosen;
                CompPrefs.save();
                this.panel.applyLayoutPreset(chosen);
            }));
        }
    }

    private void layoutDiagram(UIContext context, int x, int y, int w, int h, int preset, float sel)
    {
        int accent = BBSSettings.primaryColor() | 0xFF000000;
        int left = 0;
        int right = 0;
        int bottom = 0;

        switch (preset)
        {
            case 1 -> { left = 18; right = 18; bottom = 22; }
            case 2 -> { left = 14; right = 14; bottom = 18; }
            case 3 -> { left = 26; right = 30; bottom = 26; }
            default -> { left = 22; right = 24; bottom = 26; }
        }

        context.batcher.box(x, y, x + w, y + h, 0xFF0E141B);
        context.batcher.box(x, y, x + left, y + h, 0xFF2A3747);
        context.batcher.box(x + w - right, y, x + w, y + h, 0xFF2A3747);
        context.batcher.box(x + left, y + h - bottom, x + w - right, y + h, 0xFF2A3747);
        context.batcher.outline(x, y, x + w, y + h, UiGfx.mulA(accent, 0.3F + 0.5F * sel));
    }

    private void pageMotion(UIContext context, int x, int y, int w, int h)
    {
        this.pageTitle(context, x, y, w, "动效与光影", "可以在 设置 → 动效与光效 里逐项调节");

        int cardW = Math.min(210, (w - 96) / 3);
        int gap = 14;
        int total = cardW * 3 + gap * 2;
        int start = x + (w - total) / 2;
        int cardY = y + 146;
        int cardH = 176;

        for (int i = 0; i < 3; i++)
        {
            int cx = start + i * (cardW + gap);
            float hover = this.motion.hover("motion:" + i, this.hovered(cx, cardY, cardW, cardH) ? "motion:" + i : null);
            float sel = this.motion.select("motion:" + i, i == this.selectedMotion);
            int fill = UiMotion.lerpColor(0xFF1A222D, 0xFF243040, hover);

            context.batcher.box(cx, cardY - Math.round(hover * 2F), cx + cardW, cardY + cardH - Math.round(hover * 2F), fill);
            context.batcher.outline(cx, cardY, cx + cardW, cardY + cardH,
                UiMotion.lerpColor(0xFF334052, BBSSettings.primaryColor(), sel));

            /* Wave preview: amplitude/colour follows the preset. */
            int mid = cardY + 54;

            for (int px = 0; px < cardW - 28; px++)
            {
                float phase = (px / (float) (cardW - 28)) * (float) Math.PI * 2F;
                float amp = i == 2 ? 4F : (i == 1 ? 15F : 9F);
                int py = Math.round(mid + (float) Math.sin(phase + this.motion.time * (i == 2 ? 0.3F : 1.2F)) * amp);

                context.batcher.box(cx + 14 + px, py, cx + 15 + px, py + 1,
                    i == 2 ? 0x66808A98 : (BBSSettings.primaryColor() | 0xFF000000));
            }

            context.batcher.text(MOTION_NAMES[i], cx + 12, cardY + 104, sel > 0.5F ? 0xFFFFFFFF : 0xFFD3DCE8);
            context.batcher.text(MOTION_HINTS[i], cx + 12, cardY + 126, 0xFF8492A6);
            context.batcher.text(i == 2 ? "静态界面" : "非线性过渡", cx + 12, cardY + 150, 0xFF66758A);

            final int chosen = i;
            this.hits.add(new Hit(cx, cardY, cardW, cardH, () ->
            {
                this.selectedMotion = chosen;
                this.panel.applyMotionPreset(chosen);
            }));
        }
    }

    private void pageShortcuts(UIContext context, int x, int y, int w, int h)
    {
        this.pageTitle(context, x, y, w, "快捷键方案", "选一个熟悉的；每个命令之后都能在设置里单独改");

        int cardW = Math.min(300, (w - 100) / 2);
        int gap = 18;
        int total = cardW * 2 + gap;
        int start = x + (w - total) / 2;
        int cardY = y + 152;
        int cardH = 142;

        for (int i = 0; i < 2; i++)
        {
            int cx = start + i * (cardW + gap);
            float hover = this.motion.hover("scheme:" + i, this.hovered(cx, cardY, cardW, cardH) ? "scheme:" + i : null);
            float sel = this.motion.select("scheme:" + i, i == this.selectedScheme);
            int fill = UiMotion.lerpColor(0xFF1A222D, 0xFF243040, hover);

            context.batcher.box(cx, cardY, cx + cardW, cardY + cardH, fill);
            context.batcher.outline(cx, cardY, cx + cardW, cardY + cardH,
                UiMotion.lerpColor(0xFF334052, BBSSettings.primaryColor(), sel));
            context.batcher.text(i == 0 ? "Premiere Pro" : "BBS 原版", cx + 16, cardY + 18,
                sel > 0.5F ? 0xFFFFFFFF : 0xFFD3DCE8);
            context.batcher.text(i == 0 ? "Alt+滚轮缩放 · Ctrl+Shift+D 复制" : "Ctrl+滚轮缩放 · Ctrl+D 复制",
                cx + 16, cardY + 44, 0xFF8492A6);
            context.batcher.text(i == 0 ? "空格播放 · J/K/L 倒放/暂停/正放" : "空格播放 · I/O 工作区",
                cx + 16, cardY + 64, 0xFF8492A6);
            context.batcher.text("点击选择", cx + 16, cardY + 108, 0xFF66758A);

            final int chosen = i;
            this.hits.add(new Hit(cx, cardY, cardW, cardH, () ->
            {
                this.selectedScheme = chosen;
                this.panel.applyShortcutScheme(chosen);
            }));
        }

        this.bigButton(context, x + w / 2 - 120, cardY + cardH + 24, 240, 28, "稍后在设置中编辑快捷键",
            false, () -> {
                this.close(true);
                this.panel.openSettings("keys");
            });
    }

    private void pageFinish(UIContext context, int x, int y, int w, int h)
    {
        this.pageTitle(context, x, y, w, "准备完成", "所有选项都可以在设置里重新调整");

        int left = x + 72;
        int boxW = w - 144;
        int boxY = y + 146;

        context.batcher.box(left, boxY, left + boxW, boxY + 134, 0xFF161F2A);
        context.batcher.outline(left, boxY, left + boxW, boxY + 134, 0xFF334052);

        context.batcher.text("工作区", left + 20, boxY + 18, 0xFF8492A6);
        context.batcher.text(LAYOUT_NAMES[this.selectedLayout] + " — " + LAYOUT_HINTS[this.selectedLayout],
            left + 120, boxY + 18, 0xFFE4EBF5);
        context.batcher.text("动效", left + 20, boxY + 52, 0xFF8492A6);
        context.batcher.text(MOTION_NAMES[this.selectedMotion] + " — " + MOTION_HINTS[this.selectedMotion],
            left + 120, boxY + 52, 0xFFE4EBF5);
        context.batcher.text("快捷键", left + 20, boxY + 86, 0xFF8492A6);
        context.batcher.text(CompKeybinds.schemeName(this.selectedScheme), left + 120, boxY + 86, 0xFFE4EBF5);

        this.bigButton(context, x + w / 2 - 110, y + h - 92, 220, 32, "进入合成器", true, () -> this.close(true));
    }

    private void pageTitle(UIContext context, int x, int y, int w, String title, String subtitle)
    {
        context.batcher.textShadow(title, x + w / 2F - context.batcher.getFont().getWidth(title) / 2F,
            y + 92, 0xFFF2F6FB);
        context.batcher.text(subtitle, x + w / 2F - context.batcher.getFont().getWidth(subtitle) / 2F,
            y + 112, 0xFF8492A6);
    }

    private void bigButton(UIContext context, int x, int y, int w, int h, String label, boolean primary, Runnable action)
    {
        boolean hover = this.hovered(x, y, w, h);
        float hv = this.motion.anim("button:" + label, hover ? 1F : 0F, 20F);
        int fill = primary
            ? UiMotion.lerpColor(BBSSettings.primaryColor() | 0xFF000000,
                Colors.mulRGB(BBSSettings.primaryColor() | 0xFF000000, 1.18F), hv)
            : UiMotion.lerpColor(0xFF1B2430, 0xFF263343, hv);
        int border = primary ? Colors.mulRGB(fill, 1.2F) : 0xFF3A4658;
        int text = primary ? 0xFFFFFFFF : 0xFFD3DCE8;

        context.batcher.box(x, y - Math.round(hv * 2F), x + w, y + h - Math.round(hv * 2F), fill);
        context.batcher.outline(x, y, x + w, y + h, border);

        FontRenderer font = context.batcher.getFont();

        context.batcher.text(label, x + (w - font.getWidth(label)) / 2F, y + (h - font.getHeight()) / 2F,
            text, false);

        this.hits.add(new Hit(x, y, w, h, action));
    }

    private boolean hovered(int x, int y, int w, int h)
    {
        return this.hoverX >= x && this.hoverX <= x + w && this.hoverY >= y && this.hoverY <= y + h;
    }

    private int hoverX;
    private int hoverY;

    private void show(int page)
    {
        if (this.visible)
        {
            int next = Math.max(0, Math.min(PAGES - 1, page));
            long now = System.nanoTime();

            /* BBS dispatches click/key callbacks generously; a held pointer must not run through
             * all five pages before the first one has been read. One action per 180 ms. */
            if (now - this.lastActionAt < 180_000_000L)
            {
                return;
            }

            this.lastActionAt = now;
            this.pageDirection = next >= this.page ? 1 : -1;
            this.page = next;
            this.pageAt = now;
        }
    }

    @Override
    public boolean subMouseClicked(mchorse.bbs_mod.ui.framework.UIContext context)
    {
        if (Boolean.getBoolean("bbsae.debugOnboardingLock"))
        {
            return true;
        }

        if (!this.area.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        for (int i = this.hits.size() - 1; i >= 0; i--)
        {
            Hit hit = this.hits.get(i);

            if (hit.inside(context.mouseX, context.mouseY))
            {
                hit.action.run();

                return true;
            }
        }

        return true;
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (Boolean.getBoolean("bbsae.debugOnboardingLock"))
        {
            return true;
        }

        if (context.getKeyAction() == mchorse.bbs_mod.ui.utils.keys.KeyAction.PRESSED)
        {
            switch (context.getKeyCode())
            {
                case GLFW.GLFW_KEY_LEFT -> this.show(this.page - 1);
                case GLFW.GLFW_KEY_RIGHT -> this.show(this.page + 1);
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER ->
                {
                    if (this.page == PAGES - 1)
                    {
                        this.close(true);
                    }
                    else
                    {
                        this.show(this.page + 1);
                    }
                }
                case GLFW.GLFW_KEY_ESCAPE -> this.close(false);
                default -> {
                    return super.subKeyPressed(context);
                }
            }

            return true;
        }

        return super.subKeyPressed(context);
    }
}
