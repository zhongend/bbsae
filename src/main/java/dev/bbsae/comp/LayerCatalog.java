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
        add("solid", "纯色", "一个填满画面的矩形色块，最常用的底图与转场底。", COMMON, "square");
        add("text", "文字", "可逐字动画的文字层，支持字距、行距、描边与字体。", COMMON, "font");
        add("image", "图片", "一张 PNG/JPG，可替换、可裁剪、可做蒙版。", COMMON, "image");
        add("shape", "矩形", "带圆角、描边和填充的形状层。", COMMON, "shapes");
        add("null", "空对象", "没有像素的父级控制器，用来带动一组图层。", COMMON, "sphere");
        add("adjustment", "调整层", "把效果作用到它下面已经合成的所有图层。", COMMON, "sun");

        add("gradient", "渐变", "两色线性渐变，可关键帧角度。", GENERATE, "square");
        add("checker", "棋盘格", "程序化棋盘，适合做透明度参考或图案。", GENERATE, "square");
        add("grid", "网格", "可调间距与线宽的网格叠加。", GENERATE, "square");
        add("noise", "分形杂色", "可动画的噪声渐变，做颗粒与云雾。", GENERATE, "square");

        add("ellipse", "椭圆", "圆形/椭圆形状层。", SHAPE, "shapes");
        add("star", "星形", "多角星，可调内外半径。", SHAPE, "shapes");
        add("polygon", "多边形", "正多边形，边数可调。", SHAPE, "shapes");
        add("path", "贝塞尔", "自由路径形状，逐点可动画。", SHAPE, "shapes");

        add("footage", "图片序列", "按 %04d 编号的序列帧，用于视频拆帧。", MEDIA, "film");
        add("precomp", "预合成", "把另一条合成当作素材嵌套进来。", MEDIA, "structure");
        add("bbs", "BBS 预览", "BBS 影片当前的画面，作为底图或混合源。", MEDIA, "film");
        add("replay", "回放实体", "按深度把选中的回放实体从画面里抠出来，背景透明。", MEDIA, "replay");

        add("panel", "HUD 面板", "带阴影的半透明面板底。", UI, "square");
        add("bar", "进度条", "带填充比例的进度条。", UI, "square");

        add("camera", "摄像机", "定义画面视角：位置、朝向、FOV；3D 图层随它透视。", CAMERA, "camera");
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
