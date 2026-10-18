package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small, curated effect catalogue. The goal is not AE's 239 effects: it is a useful set for
 * screen-space motion graphics, each of which maps to one of the renderer's shader families.
 */
public final class CompEffectRegistry
{
    public static final class Param
    {
        public final String key;
        public final String label;
        public final float min;
        public final float max;
        public final float defaultValue;
        public final float step;

        public Param(String key, String label, float min, float max, float defaultValue, float step)
        {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.step = step;
        }
    }

    public static final class ColorParam
    {
        public final String key;
        public final String label;
        public final int defaultValue;

        public ColorParam(String key, String label, int defaultValue)
        {
            this.key = key;
            this.label = label;
            this.defaultValue = defaultValue;
        }
    }

    public static final class Definition
    {
        public final String id;
        public final String name;
        public final String category;
        public final List<Param> params = new ArrayList<>();
        public final List<ColorParam> colorParams = new ArrayList<>();

        public Definition(String id, String name, String category)
        {
            this.id = id;
            this.name = name;
            this.category = category;
        }

        public Definition p(String key, String label, float min, float max, float def, float step)
        {
            this.params.add(new Param(key, label, min, max, def, step));
            return this;
        }

        public Definition c(String key, String label, int def)
        {
            this.colorParams.add(new ColorParam(key, label, def));
            return this;
        }
    }

    private static final Map<String, Definition> DEFS = new LinkedHashMap<>();
    private static final List<Definition> LIST = new ArrayList<>();

    static
    {
        add(new Definition("blur", "高斯模糊", "模糊与锐化")
            .p("radius", "半径", 0, 200, 12, 1)
            .p("opacity", "混合", 0, 1, 1, 0.01F));
        add(new Definition("box_blur", "方框模糊", "模糊与锐化")
            .p("radius", "半径", 0, 200, 8, 1)
            .p("opacity", "混合", 0, 1, 1, 0.01F));
        add(new Definition("directional_blur", "方向模糊", "模糊与锐化")
            .p("radius", "长度", 0, 300, 24, 1)
            .p("angle", "角度", -180, 180, 0, 1));
        add(new Definition("radial_blur", "径向模糊", "模糊与锐化")
            .p("radius", "强度", -1, 1, 0.15F, 0.01F)
            .p("center_x", "中心 X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("sharpen", "锐化", "模糊与锐化")
            .p("amount", "强度", 0, 4, 1, 0.01F));

        add(new Definition("brightness_contrast", "亮度对比度", "色彩校正")
            .p("brightness", "亮度", -1, 1, 0, 0.01F)
            .p("contrast", "对比度", -1, 2, 0, 0.01F));
        add(new Definition("hue_saturation", "色相饱和度", "色彩校正")
            .p("hue", "色相", -180, 180, 0, 1)
            .p("saturation", "饱和度", -1, 3, 0, 0.01F)
            .p("lightness", "明度", -1, 1, 0, 0.01F));
        add(new Definition("exposure", "曝光", "色彩校正")
            .p("exposure", "曝光", -5, 5, 0, 0.01F)
            .p("gamma", "伽马", 0.1F, 4, 1, 0.01F));
        add(new Definition("tint", "着色", "色彩校正")
            .p("amount", "强度", 0, 1, 1, 0.01F)
            .c("color", "着色", 0xFFFFAA55));
        add(new Definition("levels", "色阶", "色彩校正")
            .p("black", "输入黑场", 0, 1, 0, 0.01F)
            .p("white", "输入白场", 0, 1, 1, 0.01F)
            .p("gamma", "伽马", 0.1F, 4, 1, 0.01F));
        add(new Definition("curves", "曲线", "色彩校正")
            .p("amount", "强度", 0, 1, 1, 0.01F)
            .p("shadow", "阴影", -1, 1, -0.08F, 0.01F)
            .p("midtone", "中间调", -1, 1, 0, 0.01F)
            .p("highlight", "高光", -1, 1, 0.08F, 0.01F));
        add(new Definition("invert", "反转", "色彩校正")
            .p("amount", "混合", 0, 1, 1, 0.01F));
        add(new Definition("vignette", "暗角", "色彩校正")
            .p("amount", "强度", 0, 1, 0.45F, 0.01F)
            .p("radius", "半径", 0.05F, 1.5F, 0.75F, 0.01F)
            .p("softness", "柔和度", 0.01F, 1, 0.4F, 0.01F)
            .c("color", "颜色", 0xFF000000));

        add(new Definition("noise", "噪点", "噪点与颗粒")
            .p("amount", "强度", 0, 1, 0.2F, 0.01F)
            .p("scale", "缩放", 1, 512, 64, 1)
            .p("seed", "随机种子", 0, 9999, 1, 1));
        add(new Definition("grain", "颗粒", "噪点与颗粒")
            .p("amount", "强度", 0, 1, 0.12F, 0.01F)
            .p("size", "大小", 1, 16, 2, 0.1F)
            .p("seed", "随机种子", 0, 9999, 3, 1));

        add(new Definition("gradient_ramp", "渐变", "生成")
            .p("angle", "角度", -180, 180, 90, 1)
            .p("softness", "柔和度", 0.001F, 1, 1, 0.01F)
            .c("start", "起始色", 0xFF55AAFF)
            .c("end", "结束色", 0x00000000));
        add(new Definition("noise_gradient", "分形杂色", "生成")
            .p("scale", "缩放", 1, 400, 80, 1)
            .p("octaves", "细节", 1, 8, 4, 1)
            .p("evolution", "演化", 0, 100, 0, 0.01F)
            .p("contrast", "对比度", 0, 3, 1, 0.01F)
            .p("brightness", "亮度", -1, 1, 0, 0.01F)
            .p("seed", "随机种子", 0, 9999, 0, 1)
            .c("start", "颜色 A", 0xFF55AAFF)
            .c("end", "颜色 B", 0x00000000));
        add(new Definition("checkerboard", "棋盘格", "生成")
            .p("size", "单元格大小", 4, 512, 64, 1)
            .p("angle", "角度", -180, 180, 0, 1)
            .c("a", "颜色 A", 0xFF20242A)
            .c("b", "颜色 B", 0xFF353B44));
        add(new Definition("grid", "网格", "生成")
            .p("size", "单元格大小", 4, 512, 80, 1)
            .p("thickness", "粗细", 0.5F, 20, 2, 0.1F)
            .c("color", "线条颜色", 0x8055AAFF));
        add(new Definition("circle", "圆形", "生成")
            .p("radius", "半径", 0, 1.5F, 0.35F, 0.01F)
            .p("center_x", "中心 X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0, 1, 0.5F, 0.01F)
            .p("softness", "柔和度", 0.001F, 0.5F, 0.05F, 0.001F)
            .c("color", "颜色", 0xFFFFFFFF));

        add(new Definition("displacement", "置换图", "扭曲")
            .p("amount", "强度（像素）", -400, 400, 48, 1)
            .p("depth", "使用深度", 0, 1, 0, 1)
            .p("scale", "噪波缩放", 1, 400, 90, 1)
            .p("evolution", "演化", 0, 100, 0, 0.01F));
        add(new Definition("bulge", "凸出", "扭曲")
            .p("amount", "强度", -1, 1, 0.45F, 0.01F)
            .p("radius", "半径", 0.01F, 2, 0.75F, 0.01F)
            .p("center_x", "中心 X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("pinch", "收缩", "扭曲")
            .p("amount", "强度", -1, 1, 0.35F, 0.01F)
            .p("radius", "半径", 0.01F, 2, 0.75F, 0.01F)
            .p("center_x", "中心 X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("twist", "扭转", "扭曲")
            .p("angle", "角度", -720, 720, 60, 1)
            .p("radius", "半径", 0.01F, 2, 0.75F, 0.01F)
            .p("center_x", "中心 X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("wave", "波浪变形", "扭曲")
            .p("amplitude", "振幅（像素）", 0, 300, 24, 1)
            .p("wavelength", "波长（像素）", 1, 1000, 180, 1)
            .p("phase", "相位", -100, 100, 0, 0.01F)
            .p("vertical", "垂直", 0, 1, 0, 1));

        add(new Definition("chroma_key", "色度抠像", "抠像")
            .p("tolerance", "容差", 0, 1, 0.18F, 0.01F)
            .p("softness", "柔和度", 0.001F, 0.5F, 0.08F, 0.001F)
            .p("spill", "溢色抑制", 0, 1, 0.4F, 0.01F)
            .c("key", "抠像颜色", 0xFF00FF00));
        add(new Definition("luma_key", "亮度抠像", "抠像")
            .p("threshold", "阈值", 0, 1, 0.3F, 0.01F)
            .p("softness", "柔和度", 0.001F, 0.5F, 0.05F, 0.001F)
            .p("invert", "反转", 0, 1, 0, 1));
        add(new Definition("matte_choker", "蒙版收缩", "遮罩")
            .p("choke", "收缩", -20, 20, 2, 1)
            .p("softness", "柔和度", 0, 20, 2, 1));

        add(new Definition("rgb_split", "RGB 分离", "风格化")
            .p("amount", "分离（像素）", 0, 200, 14, 1)
            .p("angle", "方向", -180, 180, 0, 1)
            .p("mix", "混合", 0, 1, 1, 0.01F));

        add(new Definition("shake", "抖动 / 震屏", "运动")
            .p("amplitude", "幅度（像素）", 0, 300, 18, 1)
            .p("frequency", "频率", 0.1F, 30, 6, 0.1F)
            .p("rotation", "旋转幅度", 0, 45, 2, 0.1F)
            .p("seed", "种子", 0, 9999, 0, 1));

        add(new Definition("glow", "辉光", "风格化")
            .p("radius", "半径", 0, 200, 40, 1)
            .p("intensity", "强度", 0, 4, 1.2F, 0.01F)
            .c("color", "着色", 0xFFFFFFFF));
        add(new Definition("drop_shadow", "投影", "风格化")
            .p("distance", "距离", -300, 300, 12, 1)
            .p("angle", "角度", -180, 180, 45, 1)
            .p("softness", "柔和度", 0, 100, 6, 1)
            .p("opacity", "不透明度", 0, 1, 0.65F, 0.01F)
            .c("color", "投影颜色", 0xFF000000));
        add(new Definition("stroke", "描边", "风格化")
            .p("width", "宽度", 0, 40, 3, 0.5F)
            .p("opacity", "不透明度", 0, 1, 1, 0.01F)
            .c("color", "颜色", 0xFFFFFFFF));
        add(new Definition("outer_glow", "外发光", "风格化")
            .p("radius", "大小", 0, 200, 24, 1)
            .p("intensity", "强度", 0, 4, 1, 0.01F)
            .p("opacity", "不透明度", 0, 1, 0.8F, 0.01F)
            .c("color", "颜色", 0xFF55AAFF));
        add(new Definition("mosaic", "马赛克", "风格化")
            .p("size", "单元格大小", 1, 256, 16, 1));
        add(new Definition("posterize", "色调分离", "风格化")
            .p("levels", "色阶", 2, 64, 8, 1));
        add(new Definition("threshold", "阈值", "风格化")
            .p("threshold", "阈值", 0, 1, 0.5F, 0.01F));
        add(new Definition("find_edges", "查找边缘", "风格化")
            .p("amount", "强度", 0, 4, 1, 0.01F)
            .p("invert", "反转", 0, 1, 1, 1));

        add(new Definition("linear_wipe", "线性擦除", "转场")
            .p("progress", "进度", 0, 1, 0.5F, 0.01F)
            .p("angle", "角度", -180, 180, 90, 1)
            .p("softness", "柔和度", 0, 0.5F, 0.02F, 0.001F));
        add(new Definition("radial_wipe", "径向擦除", "转场")
            .p("progress", "进度", 0, 1, 0.5F, 0.01F)
            .p("softness", "柔和度", 0, 0.5F, 0.02F, 0.001F)
            .p("center_x", "中心 X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("dissolve", "溶解", "转场")
            .p("progress", "进度", 0, 1, 0.5F, 0.01F)
            .p("scale", "缩放", 1, 300, 60, 1)
            .p("seed", "随机种子", 0, 9999, 7, 1));

        add(new Definition("depth_matte", "深度遮罩", "3D 通道")
            .p("near", "近", 0, 1, 0.2F, 0.01F)
            .p("far", "远", 0, 1, 0.8F, 0.01F)
            .p("softness", "柔和度", 0.001F, 0.5F, 0.05F, 0.001F));
        add(new Definition("depth_fog", "深度雾", "3D 通道")
            .p("near", "近", 0, 1, 0.25F, 0.01F)
            .p("far", "远", 0, 1, 0.9F, 0.01F)
            .p("amount", "强度", 0, 1, 0.5F, 0.01F)
            .c("color", "雾颜色", 0xFFAFC7D9));
        add(new Definition("depth_dof", "景深", "3D 通道")
            .p("focus", "对焦深度", 0, 1, 0.45F, 0.01F)
            .p("range", "对焦范围", 0.01F, 1, 0.15F, 0.01F)
            .p("radius", "模糊", 0, 100, 12, 1));

        add(new Definition("block_shatter", "方块破碎", "风格化")
            .p("progress", "进度", 0, 1, 0.35F, 0.01F)
            .p("blocks", "方块数", 2, 60, 12, 1)
            .p("spread", "飞散", 0, 1.5F, 0.45F, 0.01F)
            .p("rotation", "方块旋转", 0, 4, 1, 0.01F)
            .p("gravity", "重力", -2, 2, 0.35F, 0.01F)
            .p("seed", "随机种子", 0, 9999, 0, 1));

        add(new Definition("color_grade", "调色", "色彩校正")
            .p("exposure", "曝光", -3, 3, 0, 0.01F)
            .p("contrast", "对比度", -1, 1, 0, 0.01F)
            .p("saturation", "饱和度", 0, 2, 1, 0.01F)
            .p("vibrance", "自然饱和度", -1, 1, 0, 0.01F)
            .p("temperature", "色温", -1, 1, 0, 0.01F)
            .p("tint", "色调", -1, 1, 0, 0.01F)
            .p("hue", "色相旋转", -180, 180, 0, 1)
            .p("fade", "褪色 / 胶片", 0, 1, 0, 0.01F)
            .p("curve_black", "曲线 · 黑", -0.5F, 0.5F, 0, 0.01F)
            .p("curve_shadow", "曲线 · 阴影", -0.5F, 0.5F, 0, 0.01F)
            .p("curve_mid", "曲线 · 中间调", -0.5F, 0.5F, 0, 0.01F)
            .p("curve_highlight", "曲线 · 高光", -0.5F, 0.5F, 0, 0.01F)
            .p("curve_white", "曲线 · 白", -0.5F, 0.5F, 0, 0.01F)
            .c("lift", "阴影色", 0xFF808080)
            .c("gamma", "中间调色", 0xFF808080)
            .c("gain", "高光色", 0xFF808080));

        /* AlightMotion-style screen / retro effects. They share the PIXEL shader family, so the
         * catalogue grows without a new program per effect. */
        add(new Definition("scanlines", "扫描线", "屏幕 / 复古")
            .p("opacity", "强度", 0, 1, 0.55F, 0.01F)
            .p("spacing", "行距（像素）", 2, 64, 4, 1)
            .p("offset", "滚动偏移（像素）", 0, 2000, 0, 1));
        add(new Definition("glitch", "故障 Glitch", "屏幕 / 复古")
            .p("amount", "位移（像素）", 0, 200, 18, 1)
            .p("bands", "条带数", 4, 80, 24, 1)
            .p("speed", "速度", 0, 30, 6, 0.1F)
            .p("seed", "随机种子", 0, 9999, 0, 1));
        add(new Definition("vhs", "VHS 录像带", "屏幕 / 复古")
            .p("amount", "强度", 0, 1, 0.5F, 0.01F)
            .p("speed", "抖动速度", 0, 30, 6, 0.1F)
            .p("noise", "噪点", 0, 1, 0.2F, 0.01F));

        /* Alight Motion's Halftone Dots is defined in "dots across the project width", with a
         * row-stagger phase and separate ink/paper colours. Keeping those four properties is
         * what makes the effect animate the way AM users expect; a layer-pixel cell size would
         * change pattern density whenever the layer is resized. */
        add(new Definition("halftone", "半色调", "风格化")
            .p("amount", "网点数量（横向）", 2, 500, 100, 1)
            .p("strength", "强度", 0, 1, 0.5F, 0.01F)
            .p("angle", "角度", -180, 180, 45, 1)
            .p("phase", "行相位", -1, 1, 0.5F, 0.01F)
            .c("dot_color", "网点颜色", 0xFF000000)
            .c("paper_color", "纸张颜色", 0xFFFFFFFF));
        add(new Definition("emboss", "浮雕", "风格化")
            .p("amount", "强度", 0, 3, 1, 0.01F)
            .p("angle", "光照角度", -180, 180, 45, 1));
        add(new Definition("mirror", "镜像", "风格化")
            .p("vertical", "方向（0=左右 1=上下）", 0, 1, 0, 1)
            .p("opacity", "镜像不透明度", 0, 1, 1, 0.01F));
        add(new Definition("kaleidoscope", "万花筒", "风格化")
            .p("segments", "分段", 2, 16, 6, 1)
            .p("angle", "旋转", -180, 180, 0, 1));
    }

    private CompEffectRegistry()
    {}

    private static void add(Definition definition)
    {
        DEFS.put(definition.id, definition);
        LIST.add(definition);
    }

    public static Definition get(String id)
    {
        return DEFS.get(id);
    }

    public static List<Definition> all()
    {
        return Collections.unmodifiableList(LIST);
    }

    public static CompEffect create(String type, int serial)
    {
        Definition definition = get(type);
        CompEffect effect = new CompEffect("fx" + serial, type);

        if (definition != null)
        {
            for (Param param : definition.params)
            {
                effect.put(param.key, param.defaultValue);
            }
            for (ColorParam param : definition.colorParams)
            {
                effect.putColor(param.key, param.defaultValue);
            }
        }

        return effect;
    }

    public static String displayName(String type)
    {
        Definition definition = get(type);

        return definition == null ? type : definition.name;
    }
}
