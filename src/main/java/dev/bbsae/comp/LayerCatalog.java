package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalogue behind the layer library.
 *
 * <p>Layer types used to be a bare {@code String[][]} of ids and labels, which is why the chooser
 * could only ever be a grid of identical buttons: there was nothing else to show. A library needs
 * one more thing per entry — a sentence that says what the layer is for — so the data lives here,
 * next to the renderer's type names, and a unit test keeps it in sync with
 * {@code UICompositorPanel.LAYER_TYPES}.</p>
 */
public final class LayerCatalog
{
    public static final class Entry
    {
        public final String id;
        public final String name;
        public final String description;
        public final String category;
        /** Icon key resolved by the UI (see {@code UILayerLibrary}). */
        public final String icon;

        public Entry(String id, String name, String description, String category, String icon)
        {
            this.id = id;
            this.name = name;
            this.description = description;
            this.category = category;
            this.icon = icon;
        }
    }

    public static final String COMMON = "常用";
    public static final String GENERATE = "生成";
    public static final String SHAPE = "形状";
    public static final String MEDIA = "媒体";
    public static final String UI = "界面";
    public static final String CAMERA = "摄像机";

    public static final String[] CATEGORIES = {COMMON, GENERATE, SHAPE, MEDIA, UI, CAMERA};

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static
    {
        add("solid", "纯色", "纯色底图，最常用的背景。", COMMON, "square");
        add("text", "文字", "逐字动画文字，可描边。", COMMON, "font");
        add("image", "图片", "PNG/JPG，可替换可蒙版。", COMMON, "image");
        add("shape", "矩形", "矩形，圆角与描边。", COMMON, "shapes");
        add("null", "空对象", "无像素的父级控制器。", COMMON, "sphere");
        add("adjustment", "调整层", "把效果作用到下方合成。", COMMON, "sun");

        add("gradient", "渐变", "两色渐变，角度可动画。", GENERATE, "square");
        add("checker", "棋盘格", "程序化棋盘格图案。", GENERATE, "square");
        add("grid", "网格", "可调间距的网格叠加。", GENERATE, "square");
        add("noise", "分形杂色", "分形噪声，做颗粒云雾。", GENERATE, "square");

        add("ellipse", "椭圆", "圆形 / 椭圆形状。", SHAPE, "shapes");
        add("star", "星形", "多角星，内外半径可调。", SHAPE, "shapes");
        add("polygon", "多边形", "正多边形，边数可调。", SHAPE, "shapes");
        add("path", "贝塞尔", "自由路径，逐点可动画。", SHAPE, "shapes");

        add("footage", "图片序列", "编号序列帧，视频拆帧用。", MEDIA, "film");
        add("precomp", "预合成", "嵌套另一条合成。", MEDIA, "structure");
        add("bbs", "BBS 预览", "BBS 影片当前画面。", MEDIA, "film");
        add("replay", "回放实体", "深度抠出回放实体。", MEDIA, "replay");

        add("panel", "HUD 面板", "半透明面板底。", UI, "square");
        add("bar", "进度条", "带填充比例的进度条。", UI, "square");

        add("camera", "摄像机", "位置/朝向/FOV 与透视。", CAMERA, "camera");
    }

    private LayerCatalog()
    {}

    private static void add(String id, String name, String description, String category, String icon)
    {
        ENTRIES.add(new Entry(id, name, description, category, icon));
    }

    public static List<Entry> all()
    {
        return ENTRIES;
    }

    public static List<Entry> byCategory(String category)
    {
        List<Entry> list = new ArrayList<>();

        for (Entry entry : ENTRIES)
        {
            if (entry.category.equals(category))
            {
                list.add(entry);
            }
        }

        return list;
    }

    /** Case-insensitive search over the name and the description. */
    public static List<Entry> search(String query)
    {
        if (query == null || query.isBlank())
        {
            return ENTRIES;
        }

        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Entry> list = new ArrayList<>();

        for (Entry entry : ENTRIES)
        {
            if (entry.name.toLowerCase(java.util.Locale.ROOT).contains(needle)
                || entry.id.contains(needle)
                || entry.description.toLowerCase(java.util.Locale.ROOT).contains(needle))
            {
                list.add(entry);
            }
        }

        return list;
    }

    public static Entry get(String id)
    {
        for (Entry entry : ENTRIES)
        {
            if (entry.id.equals(id))
            {
                return entry;
            }
        }

        return null;
    }
}
