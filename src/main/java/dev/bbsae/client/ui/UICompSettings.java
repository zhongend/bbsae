package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BBS-style settings surface for the compositor.
 *
 * <p>The old settings were a 520×520 modal of absolutely positioned controls. It did not scale with
 * the panel, could not show more than a dozen rows and had no place for the new motion/shake
 * preferences. This follows BBS 2.7's own settings page: a category rail on the left, a live
 * search field, and one scrollable column of labelled rows on the right.</p>
 *
 * <p>Everything animates through {@link UiMotion}: the card enters with an overshoot, rows and
 * tabs react through the shared {@link UICompButton} skin, and the scrim fades. The motion
 * switches themselves are at the top, so a user who wants the classic static UI can turn it off
 * in one click without leaving the page.</p>
 */
public class UICompSettings extends UIElement
{
    private static final String[] CATEGORIES =
    {
        "动画与光效", "界面布局", "显示与线框", "快捷键", "项目设置", "高级", "开发者", "新手引导", "赞助者"
    };
    private static final String[] CATEGORY_IDS =
    {
        "motion", "layout", "overlay", "keys", "project", "advanced", "dev", "guide", "sponsor"
    };

    private final UICompositorPanel panel;
    private final UiMotion motion = new UiMotion();
    private final UIElement card;
    private final UILabel title;
    private final UILabel subtitle;
    private final UILabel footer;
    private final UITextbox search;
    private final UIScrollView scroll;
    private final List<UICompButton> navs = new ArrayList<>();
    private String category = "motion";
    private String filter = "";
    private boolean laidOut;

    public UICompSettings(UICompositorPanel panel)
    {
        this.panel = panel;
        this.setVisible(false);

        this.card = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                this.renderCard(context);
                super.render(context);
            }

            private void renderCard(UIContext context)
            {
                int x = this.area.x;
                int y = this.area.y;
                int w = this.area.w;
                int h = this.area.h;

                UiGfx.shadow(context.batcher, x, y, w, h, 16F, 0x66000000);
                context.batcher.box(x, y, x + w, y + h, 0xFF121820);
                context.batcher.box(x, y, x + w, y + 2, BBSSettings.primaryColor() | 0xFF000000);
                UiGfx.gradientV(context.batcher, x, y + 2, w, 40,
                    UiGfx.alpha(BBSSettings.primaryColor(), 0.07F), 0x00FFFFFF);
                context.batcher.outline(x, y, x + w, y + h, 0xFF35404F);
            }
        };

        this.card.relative(this);
        super.add(this.card);

        this.title = new UILabel(IKey.constant("合成器设置"));
        this.title.color(0xFFF2F5FA, false);
        this.subtitle = new UILabel(IKey.constant("外观、动效、快捷键与工程默认值。修改会立即生效并保存。"));
        this.subtitle.color(0xFF8995A8, false);
        this.footer = new UILabel(IKey.constant("BBS AE " + versionText() + " · 设置随工程和全局偏好保存"));
        this.footer.color(0xFF687386, false);

        this.search = new UITextbox(96, (text) ->
        {
            this.filter = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
            this.rebuild();
        });
        this.search.placeholder(IKey.constant("搜索设置…"));

        this.scroll = new UIScrollView(ScrollDirection.VERTICAL);
        this.scroll.column(3).vertical().stretch().scroll().padding(10);
        this.scroll.scroll.scrollSpeed = 50;

        this.card.add(this.title, this.subtitle, this.search, this.scroll, this.footer);

        for (int i = 0; i < CATEGORIES.length; i++)
        {
            final String id = CATEGORY_IDS[i];
            final int index = i;
            UICompButton nav = new UICompButton(IKey.constant(CATEGORIES[i]), (button) -> this.select(id));

            nav.symbol(switch (i)
            {
                case 1 -> Icons.LAYOUT;
                case 2 -> Icons.BOOKMARK;
                case 3 -> Icons.FILM;
                case 4 -> Icons.WRENCH;
                case 5 -> Icons.GEAR;
                case 6 -> Icons.CODE;
                case 7 -> Icons.HELP;
                case 8 -> Icons.HEART;
                default -> Icons.LIGHT;
            });
            nav.h(22);
            this.navs.add(nav);
            this.card.add(nav);
        }

        UICompButton close = new UICompButton(IKey.constant("关闭"), (button) -> this.close());

        close.style(UICompButton.Style.GHOST);
        close.h(22);
        this.card.add(close);
        this.close = close;

        this.layout();
    }

    private UICompButton close;

    public void open()
    {
        this.open("motion");
    }

    public void open(String category)
    {
        if (category != null)
        {
            this.category = category;
        }

        this.filter = "";
        this.search.setText("");
        this.setVisible(true);
        this.motion.replay();
        this.layout();
        this.rebuild();

        if (this.scroll != null)
        {
            this.scroll.scroll.setScroll(0);
        }

        this.panel.resize();
    }

    public void close()
    {
        if (CompKeybinds.isCapturing())
        {
            CompKeybinds.cancelCapture();
        }

        CompPrefs.flush();
        this.setVisible(false);
    }

    public boolean isOpen()
    {
        return this.visible;
    }

    public void refresh()
    {
        if (this.visible)
        {
            this.rebuild();
        }
    }

    private void select(String id)
    {
        this.category = id;
        this.rebuild();

        if (this.scroll != null)
        {
            this.scroll.scroll.setScroll(0);
        }

        this.motion.flash("category");
    }

    @Override
    public void resize()
    {
        super.resize();
        this.layout();
    }

    private void layout()
    {
        if (this.area.w <= 0 || this.area.h <= 0)
        {
            return;
        }

        /* The old minimum was 560×410 and the card overflowed every smaller panel. The card now
         * fits the available rectangle; below 560 px the category rail narrows instead of pushing
         * the content off-screen. */
        int w = Math.min(960, Math.max(300, this.area.w - 16));
        int h = Math.min(650, Math.max(240, this.area.h - 16));
        int x = this.area.x + (this.area.w - w) / 2;
        int y = this.area.y + (this.area.h - h) / 2;
        int navWidth = Math.max(72, Math.min(164, w / 3));
        int contentX = navWidth + 20;

        this.card.relative(this).x(x).y(y).w(w).h(h);
        this.title.relative(this.card).x(14).y(10).w(1F, -90).h(22);
        this.subtitle.relative(this.card).x(14).y(32).w(1F, -90).h(14);
        this.close.relative(this.card).x(1F, -68).y(12).w(54).h(20);
        this.search.relative(this.card).x(10).y(58).w(navWidth).h(20);
        this.scroll.relative(this.card).x(contentX).y(54).w(1F, -contentX - 16).h(1F, -88);
        this.footer.relative(this.card).x(contentX).y(1F, -24).w(1F, -contentX - 16).h(14);

        for (int i = 0; i < this.navs.size(); i++)
        {
            this.navs.get(i).relative(this.card).x(10).y(88 + i * 26).w(navWidth).h(22);
        }

        this.laidOut = true;
        this.card.resize();
        this.rebuild();
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();
        this.motion.markShown();

        float p = this.motion.intro(0F);

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(),
            UiGfx.alpha(0xFF070A10, 0.78F * UiMotion.easeOut(p)));

        MatrixStack stack = context.batcher.getContext().getMatrices();
        float cx = this.card.area.mx();
        float cy = this.card.area.my();
        float scale = 0.985F + 0.015F * p;

        stack.push();
        stack.translate(cx, cy, 0F);
        stack.scale(scale, scale, 1F);
        stack.translate(-cx, -cy, 0F);
        stack.translate(0F, (1F - p) * 7F, 0F);

        try
        {
            super.render(context);

            float categorySweep = this.motion.flash("category", 0.22F);

            if (categorySweep < 1F && this.scroll != null)
            {
                float width = this.scroll.area.w * UiMotion.easeOut(categorySweep);

                context.batcher.box(this.scroll.area.x, this.scroll.area.y,
                    this.scroll.area.x + width, this.scroll.area.y + 2,
                    UiGfx.mulA(BBSSettings.primaryColor() | 0xFF000000, 0.6F * (1F - categorySweep)));
            }
        }
        finally
        {
            stack.pop();
        }
    }

    @Override
    protected boolean subKeyPressed(mchorse.bbs_mod.ui.framework.UIContext context)
    {
        if (CompKeybinds.isCapturing())
        {
            if (context.getKeyAction() == mchorse.bbs_mod.ui.utils.keys.KeyAction.PRESSED)
            {
                CompKeybinds.capture(context.getKeyCode(), CompInput.ctrlDown(), CompInput.shiftDown(), CompInput.altDown());
            }

            return true;
        }

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
        /* The dimmed area belongs to the overlay too; without this a click outside the card
         * edits whatever was underneath the settings. */
        return this.visible || super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        return this.visible || super.subMouseScrolled(context);
    }

    private void rebuild()
    {
        if (!this.laidOut || this.scroll == null)
        {
            return;
        }

        this.scroll.removeAll();

        for (int i = 0; i < this.navs.size(); i++)
        {
            this.navs.get(i).style(CATEGORY_IDS[i].equals(this.category)
                ? UICompButton.Style.SELECTED : UICompButton.Style.FLAT);
        }

        switch (this.category)
        {
            case "layout" -> this.buildLayout();
            case "overlay" -> this.buildOverlay();
            case "keys" -> this.buildKeys();
            case "project" -> this.buildProject();
            case "advanced" -> this.buildAdvanced();
            case "dev" -> this.buildDev();
            case "guide" -> this.buildGuide();
            case "sponsor" -> this.buildSponsors();
            default -> this.buildMotion();
        }

        this.scroll.resize();
    }

    private boolean matches(String text)
    {
        return this.filter.isEmpty() || text.toLowerCase(Locale.ROOT).contains(this.filter);
    }

    private UIElement section(String label)
    {
        if (!this.matches(label))
        {
            return null;
        }

        UIElement section = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                int y = this.area.ey() - 1;

                context.batcher.textShadow(label, this.area.x + 2, this.area.y + 2, 0xFFA9B7C9);
                context.batcher.box(this.area.x, y, this.area.ex(), y + 1, 0x22FFFFFF);
                super.render(context);
            }
        };

        section.w(1F).h(22);

        return section;
    }

    private UIElement row(String label, UIElement control, int controlWidth, String hint)
    {
        if (!this.matches(label + " " + hint))
        {
            return null;
        }

        Row row = new Row();
        UILabel text = new UILabel(IKey.constant(label));

        row.w(1F).h(24);
        text.color(0xFFD2DCEA, false);
        text.relative(row).x(10).y(2).w(1F, -controlWidth - 20).h(18);
        control.relative(row).x(1F, -controlWidth - 8).y(2).w(controlWidth).h(18);
        row.add(text, control);

        if (hint != null && !hint.isEmpty())
        {
            row.tooltip(IKey.constant(hint));
            control.tooltip(IKey.constant(hint));
        }

        return row;
    }

    /** A settings row that lights up non-linearly under the pointer. */
    private final class Row extends UIElement
    {
        private final UiMotion motion = new UiMotion();

        @Override
        public void render(UIContext context)
        {
            this.motion.tickFrame();

            float hover = this.motion.anim("hover", this.area.isInside(context) ? 1F : 0F, 20F);

            if (hover > 0.01F)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(),
                    UiGfx.mulA(0xFFFFFFFF, 0.045F * hover));
                context.batcher.box(this.area.x, this.area.y + 2, this.area.x + 2, this.area.ey() - 2,
                    UiGfx.mulA(BBSSettings.primaryColor() | 0xFF000000, 0.75F * hover));
            }

            super.render(context);
        }
    }

    private UIElement fullButton(String label, String hint, Runnable action)
    {
        if (!this.matches(label + " " + hint))
        {
            return null;
        }

        UICompButton button = new UICompButton(IKey.constant(label), (b) -> action.run());

        button.hint(hint);
        button.w(1F).h(22);

        return button;
    }

    private UIToggle toggle(String label, boolean value, java.util.function.Consumer<Boolean> callback)
    {
        UIToggle toggle = new UIToggle(IKey.constant(label), value, (t) -> callback.accept(t.getValue()));

        return toggle;
    }

    private UISliderTrackpad slider(double value, double min, double max, java.util.function.DoubleConsumer callback)
    {
        UISliderTrackpad slider = new UISliderTrackpad((value2) -> callback.accept(value2));

        slider.limit(min, max);
        slider.setValue(value);

        return slider;
    }

    /** A compact BBS-style segmented control; no giant primary-coloured value bar. */
    private UIElement segmented(String[] labels, int selected, java.util.function.IntConsumer callback)
    {
        UIElement segment = new UIElement();

        segment.w(1F).h(18);

        for (int i = 0; i < labels.length; i++)
        {
            final int index = i;
            UICompButton button = new UICompButton(IKey.constant(labels[i]), (b) -> callback.accept(index));

            button.style(i == selected ? UICompButton.Style.SELECTED : UICompButton.Style.FLAT);
            button.relative(segment).x(i / (float) labels.length).y(0).w(1F / labels.length, -2).h(18);
            segment.add(button);
        }

        return segment;
    }

    private void add(UIElement element)
    {
        if (element != null)
        {
            this.scroll.add(element);
        }
    }

    /* ----------------------------------------------------------------- motion */

    private void buildMotion()
    {
        CompPrefs.Data prefs = CompPrefs.get();

        this.add(this.section("全局动效"));
        this.add(this.row("启用界面动效", this.toggle("", prefs.animations, (value) ->
        {
            prefs.animations = value;
            CompPrefs.save();
        }), 170, "关闭后所有 transition 立即完成，界面保持静态但功能不变"));
        this.add(this.row("动效强度", this.slider(prefs.motionIntensity, 0F, 1.5F, (value) ->
        {
            prefs.motionIntensity = (float) value;
            CompPrefs.markDirty();
        }), 170, "缩放、位移和透明度的整体倍率"));
        this.add(this.row("动效速度", this.slider(prefs.motionSpeed, 0.5F, 2.5F, (value) ->
        {
            prefs.motionSpeed = (float) value;
            CompPrefs.markDirty();
        }), 170, "越大越快，1.0 为默认"));
        this.add(this.row("光效 / 辉光", this.toggle("", prefs.glow, (value) ->
        {
            prefs.glow = value;
            CompPrefs.save();
        }), 170, "引导背景、页签下划线和高亮边缘的发光"));
        this.add(this.row("阴影", this.toggle("", prefs.shadows, (value) ->
        {
            prefs.shadows = value;
            CompPrefs.save();
        }), 170, "浮层、卡片和 hover 的柔和阴影"));
        this.add(this.row("自动定位 AE 曲线", this.toggle("", prefs.autoEffectCurve, (value) ->
        {
            prefs.autoEffectCurve = value;
            CompPrefs.save();
        }), 170, "给效果参数打关键帧时自动展开对应曲线；关闭后仍可从效果区的「AE 关键帧」按钮手动定位"));

        this.add(this.section("引导背景"));
        this.add(this.row("背景亮度", this.slider(prefs.backdropIntensity, 0F, 1.5F, (value) ->
        {
            prefs.backdropIntensity = (float) value;
            CompPrefs.markDirty();
        }), 170, "BBS 原版引导背景的光影强度"));

        this.add(this.section("预设"));
        this.add(this.fullButton("平衡（推荐）", "默认动效：快速、克制、有层次", () ->
        {
            this.applyMotionPreset(0);
            this.rebuild();
        }));
        this.add(this.fullButton("电影感", "更慢、更强的光影和视差", () ->
        {
            this.applyMotionPreset(1);
            this.rebuild();
        }));
        this.add(this.fullButton("极简 / 静态", "关闭光效和阴影，只保留快速响应", () ->
        {
            this.applyMotionPreset(2);
            this.rebuild();
        }));
    }

    public void applyMotionPreset(int preset)
    {
        CompPrefs.Data prefs = CompPrefs.get();

        switch (preset)
        {
            case 1 ->
            {
                prefs.animations = true;
                prefs.motionIntensity = 1.25F;
                prefs.motionSpeed = 0.85F;
                prefs.glow = true;
                prefs.shadows = true;
                prefs.backdropIntensity = 1.25F;
            }
            case 2 ->
            {
                prefs.animations = false;
                prefs.motionIntensity = 0.4F;
                prefs.motionSpeed = 1.25F;
                prefs.glow = false;
                prefs.shadows = false;
                prefs.backdropIntensity = 0.35F;
            }
            default ->
            {
                prefs.animations = true;
                prefs.motionIntensity = 1F;
                prefs.motionSpeed = 1F;
                prefs.glow = true;
                prefs.shadows = true;
                prefs.backdropIntensity = 1F;
            }
        }

        CompPrefs.save();
        this.motion.replay();
    }

    /* ----------------------------------------------------------------- layout */

    private void buildLayout()
    {
        CompProject.CompSettings settings = this.panel.getProjectSettings();

        this.add(this.section("工作区预设"));
        for (int i = 0; i < UIOnboarding.LAYOUT_NAMES.length; i++)
        {
            final int preset = i;
            String label = UIOnboarding.LAYOUT_NAMES[i] + " — " + UIOnboarding.LAYOUT_HINTS[i];

            this.add(this.fullButton(label, "立即应用；工程布局会保存", () ->
            {
                this.panel.applyLayoutPreset(preset);
                this.rebuild();
            }));
        }

        this.add(this.section("控件密度"));
        UIElement density = this.segmented(new String[] {"自动", "紧凑", "标准", "宽松"},
            this.panel.getDensityPreference(), (value) ->
        {
            this.panel.applyDensityPreference(value);
            this.rebuild();
        });

        this.add(this.row("界面密度", density, 250, "跟随 BBS 会根据 GUI 缩放自动选择"));

        this.add(this.section("预览"));
        if (settings != null)
        {
            this.add(this.row("显示安全框", this.toggle("", settings.showSafeArea, (value) ->
            {
                settings.showSafeArea = value;
                this.panel.saveSettings();
            }), 170, "动作安全框 / 标题安全框"));
            this.add(this.row("显示网格", this.toggle("", settings.showGrid, (value) ->
            {
                settings.showGrid = value;
                this.panel.saveSettings();
            }), 170, "预览中显示构图网格"));

            UIElement textureFilter = this.segmented(new String[] {"自动", "线性", "最近邻"},
                Math.max(0, Math.min(2, settings.textureFilter)), (value) ->
            {
                settings.textureFilter = value;
                this.panel.saveSettings();
                this.panel.refreshTextureFilterUi();
                this.rebuild();
                this.status("纹理过滤：" + dev.bbsae.client.render.TextureFilter.name(value));
            });

            this.add(this.row("线性过滤", textureFilter, 250,
                "自动：检测素材是否高分辨率，高分辨率素材自动开，像素素材自动关。也显示在预览栏和图层检查器"));
            this.add(UI.label(IKey.constant("自动检测状态 · " + this.panel.describeTextureFilter()), 14));
            this.add(this.fullButton("重新检测当前合成素材", "重新读取当前合成的图片/序列分辨率；文件新增后立即刷新自动判断", () ->
            {
                this.panel.refreshTextureFilterUi();
                this.rebuild();
            }));
        }

        this.add(this.fullButton("重置面板布局", "恢复当前密度下的左右分栏、时间轴和快捷面板尺寸", () ->
        {
            this.panel.resetEditorLayout();
            this.rebuild();
        }));
    }

    /* -------------------------------------------------------------- shortcuts */

    /* ---------------------------------------------------------------- overlay */

    private void applyOverlayPreset(boolean visible)
    {
        CompPrefs.Data prefs = CompPrefs.get();

        prefs.gizmos = visible;
        prefs.selectionBoxes = visible;
        prefs.cameraGizmos = visible;
        prefs.guides = visible;
        prefs.previewHints = visible;
        CompPrefs.save();
        this.rebuild();
        this.panel.setStatus(visible ? "已显示全部线框与提示" : "已进入纯净预览");
    }

    private void resetOverlayPrefs()
    {
        CompPrefs.Data prefs = CompPrefs.get();

        prefs.gizmos = true;
        prefs.gizmoOpacity = 1F;
        prefs.gizmoThickness = 1F;
        prefs.selectionBoxes = true;
        prefs.cameraGizmos = true;
        prefs.guides = true;
        prefs.previewHints = true;
        prefs.timelineRowHeight = 0;
        prefs.keyframeRowHeight = 0;
        CompPrefs.save();
        this.panel.applyKeyframeRowHeight(24);
        this.panel.applyTimelineRowHeight(26);
        this.rebuild();
        this.panel.setStatus("外观设置已恢复默认");
    }

    private void buildOverlay()
    {
        CompPrefs.Data prefs = CompPrefs.get();

        this.add(this.section("线框与 Gizmo"));
        this.add(this.row("显示 Gizmo", this.toggle("", prefs.gizmos, (value) ->
        {
            prefs.gizmos = value;
            CompPrefs.save();
        }), 170, "3D 移动/缩放/旋转箭头与圆环；关闭后仍可用检查器改数值"));
        this.add(this.row("Gizmo 不透明度", this.slider(prefs.gizmoOpacity, 0.15F, 1F, (value) ->
        {
            prefs.gizmoOpacity = (float) value;
            CompPrefs.markDirty();
        }), 170, "箭头、圆环、选择框和参考线的整体透明度"));
        this.add(this.row("线框粗细", this.slider(prefs.gizmoThickness, 0.5F, 3F, (value) ->
        {
            prefs.gizmoThickness = (float) value;
            CompPrefs.markDirty();
        }), 170, "选择框、Gizmo、摄像机视锥与参考线的描边粗细"));

        this.add(this.section("其他线框"));
        this.add(this.row("选择框", this.toggle("", prefs.selectionBoxes, (value) ->
        {
            prefs.selectionBoxes = value;
            CompPrefs.save();
        }), 170, "图层选择框与角点/边柄；关闭后不会误点缩放手柄"));
        this.add(this.row("摄像机线框", this.toggle("", prefs.cameraGizmos, (value) ->
        {
            prefs.cameraGizmos = value;
            CompPrefs.save();
        }), 170, "2D/3D 摄像机图标、目标十字与视锥"));
        this.add(this.row("安全区与参考线", this.toggle("", prefs.guides, (value) ->
        {
            prefs.guides = value;
            CompPrefs.save();
        }), 170, "标题/动作安全区、三分线和中心参考线"));
        this.add(this.row("预览文字提示", this.toggle("", prefs.previewHints, (value) ->
        {
            prefs.previewHints = value;
            CompPrefs.save();
        }), 170, "预览里的模式/摄像机 HUD、分辨率条和看穿提示；关闭后画面更干净"));
        this.add(this.row("关闭 BBS 洋葱皮残影", this.toggle("", prefs.suppressOnionSkin, (value) ->
        {
            prefs.suppressOnionSkin = value;
            CompPrefs.save();
        }), 170, "摄像机预览出现多重残影时保持开启；它会在合成器打开期间临时关闭 BBS 编辑器的洋葱皮"));
        this.add(this.row("3D 逐像素深度", this.toggle("", prefs.perPixelDepth, (value) ->
        {
            prefs.perPixelDepth = value;
            CompPrefs.save();
        }), 170, "默认关闭；开启后主合成器用逐像素深度解决 3D 平面相交，但部分工程在摄像机预览会出现重影"));
        this.add(this.row("关键帧轨道高度", this.slider(prefs.keyframeRowHeight > 0 ? prefs.keyframeRowHeight : 24,
            12F, 60F, (value) ->
        {
            prefs.keyframeRowHeight = (int) Math.round(value);
            CompPrefs.markDirty();
            this.panel.applyKeyframeRowHeight((int) Math.round(value));
        }), 170, "关键帧编辑器左侧轨道行高；也可在编辑器右上角用 +/- 按钮或 Alt+滚轮"));
        this.add(this.row("时间轴轨道高度", this.slider(prefs.timelineRowHeight > 0 ? prefs.timelineRowHeight : 26,
            18F, 62F, (value) ->
        {
            prefs.timelineRowHeight = (int) Math.round(value);
            CompPrefs.markDirty();
            this.panel.applyTimelineRowHeight((int) Math.round(value));
        }), 170, "主时间轴每条轨道的行高；Alt+滚轮也可调整"));

        this.add(this.section("快捷预设"));
        this.add(this.fullButton("纯净预览（隐藏线框与文字）", "一键关闭 Gizmo、选择框、摄像机线框、参考线和预览文字",
            () -> this.applyOverlayPreset(false)));
        this.add(this.fullButton("显示全部线框与提示", "重新打开所有辅助显示",
            () -> this.applyOverlayPreset(true)));
        this.add(this.fullButton("恢复默认外观设置", "不透明度/粗细回到 1，行高回到默认，所有开关打开",
            this::resetOverlayPrefs));
    }

    private void buildKeys()
    {
        this.add(this.section("方案"));
        UIElement scheme = this.segmented(new String[] {"Premiere", "BBS 原版"},
            this.panel.getShortcutScheme(), (value) ->
        {
            this.panel.applyShortcutScheme(value);
            this.rebuild();
        });

        this.add(this.row("默认方案", scheme, 220, "只改变未自定义的按键；自定义绑定优先"));

        this.add(this.section("点击右侧按键开始录制；Esc 取消，Backspace 清除"));
        String lastCategory = null;

        for (CompKeybinds.Action action : CompKeybinds.actions())
        {
            String display = CompKeybinds.display(action, this.panel.getShortcutScheme());

            if (!this.matches(action.label + " " + action.category + " " + display))
            {
                continue;
            }

            if (!action.category.equals(lastCategory))
            {
                lastCategory = action.category;
                this.add(this.section(action.category));
            }

            UICompButton bind = new UICompButton(IKey.constant(display), (button) ->
            {
                CompKeybinds.beginCapture(action.id, () ->
                {
                    this.rebuild();

                    CompKeybinds.Binding current = CompKeybinds.primary(action, this.panel.getShortcutScheme());
                    String conflict = CompKeybinds.conflict(action, current, this.panel.getShortcutScheme());

                    this.panel.setStatus(conflict == null
                        ? "快捷键已保存：" + action.label + " = "
                            + CompKeybinds.display(action, this.panel.getShortcutScheme())
                        : "已绑定，但与“" + conflict + "”冲突；先改掉其中一个");
                });
                button.label = IKey.constant("按下按键…");
            });

            bind.alignRight();

            bind.style(UICompButton.Style.FLAT);
            bind.hint("点击后按下新组合键");
            this.add(this.row(action.label, bind, 190,
                "默认 " + (CompKeybinds.defaults(action, this.panel.getShortcutScheme()).isEmpty()
                    ? "无" : CompKeybinds.defaults(action, this.panel.getShortcutScheme()).get(0).display())
                    + (CompKeybinds.isCustom(action) ? " · 已自定义" : "")));
        }

        this.add(this.fullButton("恢复全部默认快捷键", "清除所有自定义绑定", () ->
        {
            CompKeybinds.resetAll();
            this.rebuild();
        }));
    }

    /* ---------------------------------------------------------------- project */

    private void buildProject()
    {
        CompProject.CompSettings settings = this.panel.getProjectSettings();

        if (settings == null)
        {
            this.add(this.section("当前没有打开工程"));
            this.add(this.fullButton("返回工程列表", "选择一个工程后再修改项目设置", this.panel::showStartScreenFromSettings));
            return;
        }

        this.add(this.section("时间"));
        this.add(this.row("默认图层时长（秒）", this.number(settings.defaultLayerDuration, 0, 3600, (value) ->
        {
            settings.defaultLayerDuration = (float) value;
            this.panel.saveSettings();
        }), 160, "0 表示新建图层铺满整个合成"));
        this.add(this.row("导出时长（秒）", this.number(settings.exportDuration, 0, 3600, (value) ->
        {
            settings.exportDuration = (float) value;
            this.panel.saveSettings();
        }), 160, "0 表示使用合成时长"));
        this.add(this.fullButton("把导出时长应用到合成与 BBS 时间轴", "更新合成时长和影片相机时间轴长度", () ->
        {
            this.panel.applyExportDurationSetting(settings.exportDuration);
            this.status("已应用导出时长");
        }));

        this.add(this.section("保存与播放"));
        this.add(this.row("自动保存", this.toggle("", settings.autoSave, (value) ->
        {
            settings.autoSave = value;
            this.panel.saveSettings();
        }), 170, "每 60 秒写一次工程"));
        this.add(this.row("外部回放显示合成", this.toggle("", settings.showDuringExternalPlayback, (value) ->
        {
            settings.showDuringExternalPlayback = value;
            this.panel.saveSettings();
        }), 170, "关闭后，影片编辑器之外的播放不叠加合成器"));

        this.add(this.section("预览"));
        this.add(this.row("预览音频", this.toggle("", settings.previewAudio, (value) ->
        {
            settings.previewAudio = value;
            this.panel.saveSettings();
        }), 170, "AE 模式下试听音轨"));

        UIElement background = this.segmented(new String[] {"合成底色", "BBS 画面"},
            settings.previewBackground, (value) ->
        {
            settings.previewBackground = value;
            settings.previewBackgroundChosen = true;
            dev.bbsae.client.overlay.BbsPreviewBridge.setBackdropRequested(settings.previewBackground == 1);
            this.panel.saveSettings();
        });
        this.add(this.row("预览背景", background, 220, "BBS 画面：用当前影片帧做背景"));
    }

    private UITrackpad number(double value, double min, double max, java.util.function.DoubleConsumer callback)
    {
        UITrackpad field = new UITrackpad((v) -> callback.accept(v));

        field.limit(min, max);
        field.setValue(value);

        return field;
    }

    private static int proxyIndex(float proxy)
    {
        if (proxy >= 0.99F) return 0;
        if (proxy >= 0.7F) return 1;
        if (proxy >= 0.45F) return 2;

        return 3;
    }

    /* --------------------------------------------------------------- advanced */

    private void buildAdvanced()
    {
        this.add(this.section("渲染管线"));
        UIElement encoder = this.segmented(new String[] {"BBS 原生", "AE 帧服务器"},
            CompProject.ENCODER_AE.equals(this.panel.getEncoderId()) ? 1 : 0, (value) ->
        {
            this.panel.chooseEncoder(value == 1 ? CompProject.ENCODER_AE : CompProject.ENCODER_BBS);
            this.rebuild();
        });
        this.add(this.row("编码器", encoder, 250,
            "BBS 原生：BBS 负责世界/录制；AE：合成器帧服务器接管预览与导出"));

        UIElement proxy = this.segmented(new String[] {"1/1", "3/4", "1/2", "1/4"},
            proxyIndex(this.panel.getEncoderProxySetting()), (value) ->
        {
            this.panel.setEncoderProxySetting(new float[] {1F, 0.75F, 0.5F, 0.25F}[value]);
        });
        this.add(this.row("AE 预览代理", proxy, 220, "导出始终使用 1/1"));

        this.add(this.section("光影 / 外部渲染"));
        this.add(UI.label(IKey.constant(dev.bbsae.client.render.ShaderCompat.describe()), 14));
        this.add(UI.label(IKey.constant("合成层通过 BBS 的 FrameOverlays 进入世界帧；预览与导出使用同一张合成图。深度不可用时深度效果会自动变为无操作，不会把画面抠黑。"), 13));

        this.add(this.fullButton("清空预渲染帧缓存", "删除磁盘/LRU 中的预览帧，下一次播放重新渲染", () ->
        {
            this.panel.clearPreviewCache();
            this.status("预渲染缓存已清空");
        }));
        this.add(this.fullButton("打开旧版高级对话框", "保留给编码器风险提示和极端故障排查", this.panel::showLegacySettings));
    }

    /* -------------------------------------------------------------------- dev */

    /**
     * Developer settings: compatibility switches and camera defaults.
     *
     * <p>These are grouped apart from the ordinary pages because they change how the whole
     * composition is interpreted, not how the editor looks. The 3D-logic switch in particular is a
     * <b>file compatibility</b> control: projects authored before 0.91 expect every layer to follow
     * the camera, and this is the only way to reopen them with that look.</p>
     */
    private void buildDev()
    {
        CompProject.CompSettings settings = this.panel.getProjectSettings();

        this.add(UI.label(IKey.constant("开发者设置：兼容旧工程、排查渲染问题。普通编辑不需要改这里。"), 13));

        /* Global (not project) because the layout engine is chosen when the compositor panel is
         * constructed; the project-specific dock tree lives in CompSettings.dockLayout. */
        this.add(this.section("界面布局引擎"));
        this.add(this.row("停靠式面板（BBS 原版）", this.toggle("", CompPrefs.get().dockLayout, (value) ->
        {
            CompPrefs.get().dockLayout = value;
            CompPrefs.save();
            this.panel.setStatus(value
                ? "已启用停靠式面板：重开合成器面板后生效（可拖拽组合/标签堆叠/分栏）"
                : "已回退到旧版三栏布局：重开合成器面板后生效");
        }), 170, "启用后复用 BBS 原版 UIDockLayout：拖动面板标题条/标签即可拆分或堆叠；"
            + "布局锁与重置在合成器顶栏的「布局」按钮。切换后需重开合成器面板生效"));

        this.add(this.section("渲染"));
        this.add(this.row("逐像素深度（主合成）", this.toggle("", CompPrefs.get().perPixelDepth, (value) ->
        {
            CompPrefs.get().perPixelDepth = value;
            CompPrefs.save();
            /* The flag is part of the render-cache signature; drop the green frames so the next
             * preview frame actually goes through the newly selected path. */
            dev.bbsae.client.render.CompPrecache.markDirty();
            this.panel.setStatus(value
                ? "已开启逐像素深度：相交/穿插的 3D 图层按真实像素遮挡（每层多 3 个全屏 pass，低配机可能掉帧）"
                : "已关闭逐像素深度：回到图层级排序（默认，兼容性最好）");
        }), 170, "0.93 修复了逐像素深度的重影 bug；开启后相交的 3D 图层按像素深度正确遮挡，"
            + "代价是每个 3D 层多 3 个全屏 pass。对性能敏感或旧显卡工程建议保持关闭"));

        if (settings == null)
        {
            this.add(this.section("当前没有打开工程"));
            this.add(UI.label(IKey.constant("3D 逻辑与摄像机默认值保存在工程文件里，打开一个工程后才能修改。"), 13));
            this.add(this.fullButton("返回工程列表", "选择一个工程后再修改开发者设置", this.panel::showStartScreenFromSettings));

            return;
        }

        this.add(this.section("3D 逻辑（兼容旧版本）"));
        UIElement logic = this.segmented(new String[] {"AE：逐图层", "旧版：全图层"}, settings.legacy3DLogic ? 1 : 0,
            (value) ->
        {
            settings.legacy3DLogic = value == 1;
            this.panel.saveSettings();
            this.rebuild();
            this.panel.setStatus(value == 1
                ? "已切换到旧版 3D 逻辑：镜头一旦有透视/倾斜，所有图层都会被投影（0.91 之前的行为）"
                : "已切换到 AE 3D 逻辑：只有开启「3D 图层」的图层跟随摄像机，2D 图层始终屏幕附着");
        });

        this.add(this.row("图层 3D 逻辑", logic, 250,
            "AE：只有勾选「3D 图层」的图层参与摄像机透视，2D 图层保持屏幕附着；"
                + "旧版：只要镜头有倾斜或任意图层 z≠0，所有图层都被投影（兼容 0.91 前的旧工程）"));

        this.add(this.section("摄像机"));
        this.add(this.row("无摄像机时拖动自动创建（旧版）", this.toggle("", settings.cameraAutoCreateOnDrag,
            (value) ->
        {
            settings.cameraAutoCreateOnDrag = value;
            this.panel.saveSettings();
        }), 170, "默认关闭：AE 里摄像机是显式对象，只有「新建摄像机」按钮会创建；"
            + "开启后右键拖动预览框会退回旧版行为（不推荐）"));

        this.add(this.row("AE 摄像机工具", this.segmented(new String[] {"关闭", "开启"},
            settings.cameraToolActive ? 1 : 0, (value) ->
        {
            this.panel.setCameraTool(value == 1);
            this.rebuild();
        }), 170, "开启后：左键环绕 / 中键 Track XY / 右键 Track Z 推拉；"
            + "预览里不会再选中或拖动图层，也没有摄像机时不会自动创建"));

        this.add(this.row("新摄像机视场角 FOV", this.slider(settings.defaultCameraFov, 10F, 120F, (value) ->
        {
            settings.defaultCameraFov = (float) value;
            CompPrefs.markDirty();
            this.panel.saveSettings();
        }), 170, "只影响之后新建的摄像机；已存在的摄像机在检查器「摄像机」页调整"));

        this.add(this.fullButton("在这个工程里新建摄像机", "已有摄像机时只会选中它；AE 不会在拖动时偷偷创建", () ->
        {
            this.panel.createCamera();
            this.rebuild();
        }));

        this.add(this.section("状态"));
        this.add(UI.label(IKey.constant("版本 " + versionText()
            + " · 当前 3D 逻辑：" + (settings.legacy3DLogic ? "旧版全图层" : "AE 逐图层")
            + " · 摄像机工具：" + (settings.cameraToolActive ? "开启" : "关闭")), 13));
        this.add(UI.label(IKey.constant("3D 工作区（自由视口）与「看穿摄像机」是两种视图；"
            + "后者才是最终画面。右键拖预览框只进入自由视口，没有摄像机也不会创建。"), 13));
    }

    /** The mod version from Fabric metadata, so the footer can never drift from the build again. */
    private static String versionText()
    {
        try
        {
            return FabricLoader.getInstance().getModContainer("bbsae")
                .map((container) -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        }
        catch (RuntimeException e)
        {
            return "dev";
        }
    }

    /* ------------------------------------------------------------------ guide */

    private void buildGuide()
    {
        this.add(this.section("新手引导"));
        this.add(this.fullButton("重新播放新手引导", "重新选择界面布局、动效和快捷键方案", () ->
        {
            this.close();
            this.panel.openOnboarding();
        }));
        this.add(this.fullButton("打开快捷键编辑器", "查看并修改每一个合成器命令", () ->
        {
            this.category = "keys";
            this.rebuild();
        }));
        this.add(this.section("关于"));
        this.add(this.fullButton("赞助者", "查看赞助名单；感谢每一位支持合成器的人", () ->
        {
            this.category = "sponsor";
            this.rebuild();
        }));
        this.add(this.fullButton("快捷键一览", "列出当前方案和所有可编辑命令", this.panel::showShortcutHelp));
        this.add(this.fullButton("查看项目文档", "打开工程内的 docs/HANDOFF.zh.md", () ->
            this.status("文档位于项目目录 docs/HANDOFF.zh.md")));
    }

    /**
     * Sponsor credits are part of Settings, not a dashboard page. There is no per-person pitch:
     * the board is only names and a plain thank-you.
     */
    private void buildSponsors()
    {
        this.add(this.section("感谢"));
        this.add(UI.label(IKey.constant("这些名字留在这里，是因为他们支持了 BBS 合成器。"), 14));

        UISponsorBoard board = new UISponsorBoard();

        board.w(1F).h(326);
        this.add(board);
    }

    private void status(String text)
    {
        this.panel.setStatus(text);
    }
}
