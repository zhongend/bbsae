package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.HashMap;
import java.util.Map;

/**
 * The compositor's UI metrics, buttons and tooltip text.
 *
 * <h2>Why a metrics object</h2>
 * <p>The panel used to hardcode "one size" of everything: 18px rows, 20px buttons, 90px label
 * columns, 300px inspector. At Minecraft GUI scale 3–4 that reads as a wall of oversized controls,
 * while BBS's own widgets stay at {@code UIConstants.CONTROL_HEIGHT = 16}. The fix is not "make
 * everything smaller" but <b>one density number</b> that every row, button, label column, panel
 * width and toolbar derives from — exactly like AE's "UI Brightness/Scale" presets, except these
 * three tiers follow BBS's own scale when set to auto.</p>
 *
 * <h2>Why flat buttons</h2>
 * <p>BBS's {@code UIButton} fills itself with {@code BBSSettings.primaryColor}. Forty of those in an
 * inspector is a wall of saturated blocks — the accent stops meaning anything and the panel looks
 * loud. AE's own UI uses flat neutral chips with a single accent only on the primary action, so
 * {@link UICompButton} does the same: raised-surface fill, divider border, left-aligned label, accent
 * reserved for primary/danger.</p>
 */
public final class CompUi
{
    public static final int DENSITY_AUTO = 0;
    public static final int DENSITY_COMPACT = 1;
    public static final int DENSITY_NORMAL = 2;
    public static final int DENSITY_COMFORTABLE = 3;

    /** Buttons and inputs. */
    private static int control = 16;
    /** A labelled row inside a section. */
    private static int row = 18;
    /** Vertical gap between rows and sections. */
    private static int gap = 3;
    /** Label column in a "label : control" row. */
    private static int label = 76;
    /** Square icon button size. */
    private static int icon = 18;
    /** Height of a strip of buttons (toolbars, tab bars). */
    private static int bar = 22;
    /** The resolved tier, 1..3. */
    private static int density = DENSITY_NORMAL;

    private CompUi()
    {}

    /* ---------------------------------------------------------------- density */

    /** Applies the saved preference (0 = follow BBS) and resolves the tier. */
    public static void configure(int preference)
    {
        density = preference == DENSITY_AUTO ? autoDensity(bbsScale()) : Math.max(1, Math.min(3, preference));

        switch (density)
        {
            case DENSITY_COMPACT ->
            {
                control = 14;
                row = 16;
                gap = 2;
                label = 62;
                icon = 16;
                bar = 20;
            }
            case DENSITY_COMFORTABLE ->
            {
                control = 20;
                row = 22;
                gap = 4;
                label = 92;
                icon = 22;
                bar = 28;
            }
            default ->
            {
                control = 16;
                row = 18;
                gap = 3;
                label = 76;
                icon = 18;
                bar = 22;
            }
        }
    }

    /**
     * Maps BBS's effective GUI scale to a density: a big scale means big pixels, so the panel needs
     * compact controls to fit; a small scale leaves room for comfortable ones.
     */
    public static int autoDensity(float scale)
    {
        if (scale < 2.01F)
        {
            return DENSITY_COMFORTABLE;
        }

        if (scale < 3.01F)
        {
            return DENSITY_NORMAL;
        }

        return DENSITY_COMPACT;
    }

    /** BBS's applied GUI scale, or 2 when it cannot be read (headless tests, early bootstrap). */
    public static float bbsScale()
    {
        try
        {
            float scale = BBSModClient.getGUIScale();

            return scale <= 0.01F ? 2F : scale;
        }
        catch (RuntimeException | LinkageError e)
        {
            return 2F;
        }
    }

    public static int density()
    {
        return density;
    }

    public static int control()
    {
        return control;
    }

    public static int row()
    {
        return row;
    }

    public static int gap()
    {
        return gap;
    }

    public static int label()
    {
        return label;
    }

    public static int icon()
    {
        return icon;
    }

    public static int bar()
    {
        return bar;
    }

    public static int leftWidth()
    {
        return density == DENSITY_COMPACT ? 152 : density == DENSITY_COMFORTABLE ? 208 : 178;
    }

    public static int rightWidth()
    {
        return density == DENSITY_COMPACT ? 268 : density == DENSITY_COMFORTABLE ? 348 : 306;
    }

    public static int timelineHeight()
    {
        return density == DENSITY_COMPACT ? 232 : density == DENSITY_COMFORTABLE ? 288 : 252;
    }

    public static String densityName(int tier)
    {
        return switch (tier)
        {
            case DENSITY_COMPACT -> "小";
            case DENSITY_COMFORTABLE -> "大";
            default -> "中";
        };
    }

    /* ---------------------------------------------------------------- widgets */

    /** A neutral, left-aligned button. The workhorse — replaces the accent-filled default. */
    public static UICompButton flat(String label, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.FLAT)
            .hint(describe(label));
    }

    public static UICompButton flat(String label, String tooltip, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.FLAT)
            .hint(tooltip == null ? describe(label) : tooltip);
    }

    /** The single accent action of a surface (play, create, apply). Use sparingly. */
    public static UICompButton primary(String label, String tooltip, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.PRIMARY)
            .hint(tooltip == null ? describe(label) : tooltip);
    }

    /** Destructive actions. Muted red, not a traffic light. */
    public static UICompButton danger(String label, String tooltip, Runnable action)
    {
        return new UICompButton(IKey.constant(label), (button) -> action.run())
            .style(UICompButton.Style.DANGER)
            .hint(tooltip == null ? describe(label) : tooltip);
    }

    /** Icon-only button for dense toolbars; the tooltip carries the whole meaning. */
    public static UICompIconButton iconButton(Icon symbol, String tooltip, Runnable action)
    {
        return new UICompIconButton(symbol, tooltip, action);
    }

    /** A section sub-heading: small, muted, no box — the AE "group label" look. */
    public static UILabel heading(String text)
    {
        UILabel label = new UILabel(IKey.constant(text));

        label.color(0xFF8C96A5, false);
        label.h(density == DENSITY_COMPACT ? 13 : 15);

        return label;
    }

    /** Applies the shared hint to any BBS widget (toggle, trackpad, icon...). */
    public static <T extends UIElement> T hint(T element, String tooltip)
    {
        if (tooltip != null && !tooltip.isEmpty())
        {
            element.tooltip(IKey.constant(tooltip));
        }

        return element;
    }

    /** The default width for a section's label column. */
    public static int labelWidth()
    {
        return label;
    }

    /** Buttons that live in a toolbar strip. */
    public static UICompButton tool(String label, String tooltip, Runnable action)
    {
        UICompButton button = flat(label, tooltip, action);

        button.h(control);

        return button;
    }

    /**
     * Reads the density preference from a project (0 when absent) so a project can carry its own
     * UI scale, and falls back to the global default.
     */
    public static int preferenceOf(CompProject project)
    {
        return project == null || project.settings == null
            ? DENSITY_AUTO
            : Math.max(0, Math.min(3, project.settings.uiDensity));
    }

    /* ---------------------------------------------------------------- tooltips */

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    static
    {
        /* Inspector / layer */
        describe("重命名…", "给所选轨道起一个新名字，只改显示名，不改 id");
        describe("复制", "复制所选轨道的内容与关键帧，副本放在原轨道上方并右移一点");
        describe("删除", "删除所选轨道；合成里的其它图层不会跟着移动");
        describe("移除", "把这一项从列表里移除，不影响其它项");
        describe("重置变换", "把所选图层的位置、旋转、缩放恢复成默认值（0 / 0 / 100%）");
        describe("复制所选", "把当前多选的所有图层各复制一份");
        describe("删除所选", "删除当前多选的所有图层（可用 Ctrl+Z 撤销）");
        describe("选择最上层轨道", "选中时间轴最上面的一条轨道，方便快速开始编辑");
        describe("跳到入点", "把播放头移到所选轨道的入点");
        describe("跳转", "把播放头移到这个标记所在的时间");
        describe("进入预合成", "打开这个预合成图层的内部合成，Esc 可返回");

        /* Time / layer timing */
        describe("入点=播放头", "把所选轨道的入点对齐到当前播放头；源画面不滑动");
        describe("出点=播放头", "把所选轨道的出点对齐到当前播放头");
        describe("在播放头添加标记", "在播放头位置加一个合成标记，便于跳转和对齐");
        describe("在播放头添加图层标记", "在播放头的这一层上加一个图层标记");
        describe("保存图层动画", "把这一层的变换 / 不透明度 / 参数动画保存成可复用的动画预设");

        /* Shapes */
        describe("矩形", "新建一个矩形形状；随后可在下面单独调四个角的圆角");
        describe("三角形", "新建一个三角形；三个角可以分别设圆角");
        describe("星形", "新建星形；可调角数与内半径");
        describe("多边形", "新建正多边形；可调边数与圆角");
        describe("贝塞尔路径", "新建可编辑的贝塞尔路径，每个点都有进出切线");
        describe("添加路径点", "在路径末尾追加一个控制点");
        describe("删除最后一点", "移除路径的最后一个控制点");
        describe("全部归零", "把这个形状所有角的圆角清零，恢复尖角");
        describe("添加蒙版点", "在蒙版路径末尾追加一个点");
        describe("添加矩形蒙版", "新建一个矩形蒙版，用来裁切这一层");
        describe("添加椭圆蒙版", "新建一个椭圆蒙版，用来裁切这一层");

        /* Alignment / multi-select */
        describe("左", "把所选图层的左边缘对齐到合成左边");
        describe("右", "把所选图层的右边缘对齐到合成右边");
        describe("上", "把所选图层的上边缘对齐到合成顶部");
        describe("下", "把所选图层的下边缘对齐到合成底部");
        describe("水平中", "把所选图层的水平中心对齐到合成中线");
        describe("垂直中", "把所选图层的垂直中心对齐到合成中线");
        describe("水平等距", "让所选图层在水平方向按中心等距分布（至少选三个）");
        describe("垂直等距", "让所选图层在垂直方向按中心等距分布（至少选三个）");
        describe("合成预渲染", "把所选图层合并进一个子合成，再用一个预合成图层替换它们；入点会整体前移");
        describe("编组（空对象）", "在所选图层的中心建一个空对象并全部挂上去，拖动空对象即可整体移动");
        describe("显示", "把所选图层全部设为可见");
        describe("隐藏", "把所选图层全部设为不可见，渲染和导出也会跳过");
        describe("锁定", "锁定所选图层，避免在预览里误拖");

        /* Camera */
        describe("重置摄像机", "位置回到合成中心、距离回到 1:1、目标对准中心、滚转归零");
        describe("对齐到当前视图（2D 平移/缩放归零）", "保留透视与目标，只把 2D 的平移/缩放/滚转恢复成 1:1");
        describe("自动选择摄像机（最上层）", "取消手动指定，改为自动使用时间轴上最上层的可见摄像机");
        describe("设为活动摄像机", "让整条合成使用这个摄像机；同一时间只有一个活动摄像机");
        describe("当前活动摄像机", "这个摄像机正在驱动画面；点一下可以重新确认");

        /* Effects / styles / grade */
        describe("＋ 添加调色效果", "给所选图层加一个调色效果，并在右侧展开它的参数");
        describe("＋ 为整个合成添加调色调整层", "新建一个调整层并加上调色效果，作用是它下面的所有图层");
        describe("移除调色", "删除这个调色效果");
        describe("重置调色", "色轮、曲线和基础参数全部回到中性");
        describe("添加样式…", "给所选图层添加一个图层样式（投影、发光、描边等）");
        describe("添加文字动画器", "添加一个逐字动画器：位置、缩放、旋转、透明度、抖动");
        describe("添加参数", "在合成里添加一个可在表达式里引用的主参数");

        /* Precomp / output */
        describe("＋ 新建预合成（当前尺寸）", "以当前合成尺寸新建一个空子合成，并把这一层指向它");
        describe("使用合成时长", "把导出时长设成整个合成的长度");
        describe("应用时长到合成与 BBS 时间轴", "把这里的时长写进合成与 BBS 的录像长度");
        describe("同步到 BBS 时间轴（原版录制按钮也用这个长度）", "用 BBS 原版录像器的长度覆盖这里的时长");
        describe("应用比例", "把当前宽高比应用到导出尺寸，保持像素数不变");
        describe("添加 H.264 预设", "新增一个 H.264 审阅输出预设");
        describe("添加 ProRes 422 HQ", "新增一个 ProRes 422 HQ 输出预设（体积大、画质高）");
        describe("添加 ProRes 4444 Alpha", "新增一个带 alpha 通道的 ProRes 4444 输出预设");
        describe("添加任务", "把当前设置加入渲染队列");
        describe("开始", "开始渲染选中的任务");
        describe("全部开始", "按顺序渲染队列里的全部任务");
        describe("取消", "取消正在渲染的任务");
        describe("重试", "把失败的任务重新排队");
        describe("打开文件夹", "在系统文件管理器里打开导出目录");
        describe("预设…", "切换输出预设");

        /* Curves / presets */
        describe("保存", "保存工程到磁盘（Ctrl+S）");
        describe("播放", "播放 / 暂停合成预览（空格）");
        describe("渲染", "打开渲染队列，批量输出");
        describe("导出", "用 BBS 原生录像器把这一条合成录成视频");
        describe("设置", "合成器设置：导出时长、默认图层时长、界面密度、快捷键方案");
        describe("关键帧", "展开 / 收起所选轨道的属性关键帧抽屉");
        describe("吸附", "拖动与裁剪时吸附到播放头、标记和其它轨道（S）");

        /* Presets */
        describe("存为动画", "把当前图层的变换与不透明度动画存成预设");
        describe("存为效果", "把当前图层整条效果栈存成预设，可套到别的图层");
        describe("存为样式", "把当前图层的图层样式存成预设");
        describe("存为形状", "把当前图层的形状组存成预设");
        describe("存为整套", "把动画、效果、样式、形状和填充色一起存成预设");
        describe("删除这个预设", "从工程里删掉这个用户预设（内置预设不可删）");

        /* Misc */
        describe("导入图片…", "为这一层选择一张图片作为素材");
        describe("导入字体（ttf / otf）…", "从文件导入字体，供文字图层使用");
        describe("＋ 添加 BBS 预览层", "添加一个显示 BBS 世界/影片实时画面的图层");
        describe("＋ 添加空对象", "添加一个不产生像素的控制层，可作为其它图层的父级");
        describe("＋ 新建形状", "在选中图层里新建一个形状（矩形/三角/星形/多边形/路径）");
        describe("工程列表…", "返回工程列表，切换或新建工程");
        describe("快捷键表", "查看所有快捷键");
        describe("打开效果库", "在左栏打开效果库");
        describe("应用效果", "把选中的效果加到当前轨道末尾；也可以直接双击效果");
        describe("进入合成器", "用所选工程打开合成器；也可以直接双击工程卡片");
        describe("创建并进入", "按上面的名称、画布尺寸、帧率和时长新建工程并立即进入");
        describe("打开素材库…", "浏览项目素材与游戏内贴图");
    }

    /** Registers a description (also used for labels built at runtime). */
    public static void describe(String label, String text)
    {
        DESCRIPTIONS.put(label, text);
    }

    /**
     * Detailed hover text for a label. The dictionary above covers every button the panel builds;
     * unknown labels still get a sentence that names the action, never an empty tooltip.
     */
    public static String describe(String label)
    {
        if (label == null || label.isEmpty())
        {
            return "";
        }

        String text = DESCRIPTIONS.get(label);

        if (text != null)
        {
            return text;
        }

        /* Prefix-insensitive lookup: "应用 电影青橙" should hit "应用". */
        int space = label.indexOf(' ');

        if (space > 0)
        {
            text = DESCRIPTIONS.get(label.substring(0, space));

            if (text != null)
            {
                return text + "（" + label.substring(space + 1).trim() + "）";
            }
        }

        return "点击执行：" + label;
    }

    /** A separator line used between toolbar groups. */
    public static void divider(mchorse.bbs_mod.ui.framework.UIContext context, float x, float y, float h)
    {
        context.batcher.box(x, y, x + 1, y + h, BBSSettings.dividerColor());
    }

    /** Accent color used for emphasis lines (not fills). */
    public static int accent()
    {
        return BBSSettings.primaryColor() | Colors.A100;
    }

    /** Muted text. */
    public static int mutedText()
    {
        return 0xFF8C96A5;
    }
}
