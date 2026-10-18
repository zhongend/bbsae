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
        add(new Definition("blur", "Gaussian Blur", "Blur & Sharpen")
            .p("radius", "Radius", 0, 200, 12, 1)
            .p("opacity", "Mix", 0, 1, 1, 0.01F));
        add(new Definition("box_blur", "Box Blur", "Blur & Sharpen")
            .p("radius", "Radius", 0, 200, 8, 1)
            .p("opacity", "Mix", 0, 1, 1, 0.01F));
        add(new Definition("directional_blur", "Directional Blur", "Blur & Sharpen")
            .p("radius", "Length", 0, 300, 24, 1)
            .p("angle", "Angle", -180, 180, 0, 1));
        add(new Definition("radial_blur", "Radial Blur", "Blur & Sharpen")
            .p("radius", "Amount", -1, 1, 0.15F, 0.01F)
            .p("center_x", "Center X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "Center Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("sharpen", "Sharpen", "Blur & Sharpen")
            .p("amount", "Amount", 0, 4, 1, 0.01F));

        add(new Definition("brightness_contrast", "Brightness & Contrast", "Color Correction")
            .p("brightness", "Brightness", -1, 1, 0, 0.01F)
            .p("contrast", "Contrast", -1, 2, 0, 0.01F));
        add(new Definition("hue_saturation", "Hue & Saturation", "Color Correction")
            .p("hue", "Hue", -180, 180, 0, 1)
            .p("saturation", "Saturation", -1, 3, 0, 0.01F)
            .p("lightness", "Lightness", -1, 1, 0, 0.01F));
        add(new Definition("exposure", "Exposure", "Color Correction")
            .p("exposure", "Exposure", -5, 5, 0, 0.01F)
            .p("gamma", "Gamma", 0.1F, 4, 1, 0.01F));
        add(new Definition("tint", "Tint", "Color Correction")
            .p("amount", "Amount", 0, 1, 1, 0.01F)
            .c("color", "Tint Color", 0xFFFFAA55));
        add(new Definition("levels", "Levels", "Color Correction")
            .p("black", "Input Black", 0, 1, 0, 0.01F)
            .p("white", "Input White", 0, 1, 1, 0.01F)
            .p("gamma", "Gamma", 0.1F, 4, 1, 0.01F));
        add(new Definition("curves", "Curves", "Color Correction")
            .p("amount", "Amount", 0, 1, 1, 0.01F)
            .p("shadow", "Shadows", -1, 1, 0, 0.01F)
            .p("midtone", "Midtones", -1, 1, 0, 0.01F)
            .p("highlight", "Highlights", -1, 1, 0, 0.01F));
        add(new Definition("invert", "Invert", "Color Correction")
            .p("amount", "Blend", 0, 1, 1, 0.01F));
        add(new Definition("vignette", "Vignette", "Color Correction")
            .p("amount", "Amount", 0, 1, 0.45F, 0.01F)
            .p("radius", "Radius", 0.05F, 1.5F, 0.75F, 0.01F)
            .p("softness", "Softness", 0.01F, 1, 0.4F, 0.01F)
            .c("color", "Color", 0xFF000000));

        add(new Definition("noise", "Noise", "Noise & Grain")
            .p("amount", "Amount", 0, 1, 0.2F, 0.01F)
            .p("scale", "Scale", 1, 512, 64, 1)
            .p("seed", "Seed", 0, 9999, 1, 1));
        add(new Definition("grain", "Grain", "Noise & Grain")
            .p("amount", "Amount", 0, 1, 0.12F, 0.01F)
            .p("size", "Size", 1, 16, 2, 0.1F)
            .p("seed", "Seed", 0, 9999, 3, 1));

        add(new Definition("gradient_ramp", "Gradient Ramp", "Generate")
            .p("angle", "Angle", -180, 180, 90, 1)
            .p("softness", "Softness", 0.001F, 1, 1, 0.01F)
            .c("start", "Start", 0xFF55AAFF)
            .c("end", "End", 0x00000000));
        add(new Definition("noise_gradient", "Fractal Noise", "Generate")
            .p("scale", "Scale", 1, 400, 80, 1)
            .p("octaves", "Detail", 1, 8, 4, 1)
            .p("evolution", "Evolution", 0, 100, 0, 0.01F)
            .p("contrast", "Contrast", 0, 3, 1, 0.01F));
        add(new Definition("checkerboard", "Checkerboard", "Generate")
            .p("size", "Cell Size", 4, 512, 64, 1)
            .p("angle", "Angle", -180, 180, 0, 1)
            .c("a", "Color A", 0xFF20242A)
            .c("b", "Color B", 0xFF353B44));
        add(new Definition("grid", "Grid", "Generate")
            .p("size", "Cell Size", 4, 512, 80, 1)
            .p("thickness", "Thickness", 0.5F, 20, 2, 0.1F)
            .c("color", "Line Color", 0x8055AAFF));
        add(new Definition("circle", "Circle", "Generate")
            .p("radius", "Radius", 0, 1.5F, 0.35F, 0.01F)
            .p("center_x", "Center X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "Center Y", 0, 1, 0.5F, 0.01F)
            .p("softness", "Softness", 0.001F, 0.5F, 0.05F, 0.001F)
            .c("color", "Color", 0xFFFFFFFF));

        add(new Definition("displacement", "Displacement Map", "Distort")
            .p("amount", "Amount", -400, 400, 24, 1)
            .p("depth", "Use Depth", 0, 1, 0, 1)
            .p("scale", "Noise Scale", 1, 400, 90, 1)
            .p("evolution", "Evolution", 0, 100, 0, 0.01F));
        add(new Definition("bulge", "Bulge", "Distort")
            .p("amount", "Amount", -1, 1, 0.35F, 0.01F)
            .p("radius", "Radius", 0.01F, 2, 0.5F, 0.01F)
            .p("center_x", "Center X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "Center Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("pinch", "Pinch", "Distort")
            .p("amount", "Amount", -1, 1, 0.25F, 0.01F)
            .p("center_x", "Center X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "Center Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("twist", "Twist", "Distort")
            .p("angle", "Angle", -720, 720, 30, 1)
            .p("radius", "Radius", 0.01F, 2, 0.6F, 0.01F)
            .p("center_x", "Center X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "Center Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("wave", "Wave Warp", "Distort")
            .p("amplitude", "Amplitude", 0, 300, 18, 1)
            .p("wavelength", "Wavelength", 1, 1000, 180, 1)
            .p("phase", "Phase", -100, 100, 0, 0.01F)
            .p("vertical", "Vertical", 0, 1, 0, 1));

        add(new Definition("chroma_key", "Chroma Key", "Keying")
            .p("tolerance", "Tolerance", 0, 1, 0.18F, 0.01F)
            .p("softness", "Softness", 0.001F, 0.5F, 0.08F, 0.001F)
            .p("spill", "Spill Suppression", 0, 1, 0.4F, 0.01F)
            .c("key", "Key Color", 0xFF00FF00));
        add(new Definition("luma_key", "Luma Key", "Keying")
            .p("threshold", "Threshold", 0, 1, 0.3F, 0.01F)
            .p("softness", "Softness", 0.001F, 0.5F, 0.05F, 0.001F)
            .p("invert", "Invert", 0, 1, 0, 1));
        add(new Definition("matte_choker", "Matte Choker", "Matte")
            .p("choke", "Choke", -20, 20, 0, 1)
            .p("softness", "Softness", 0, 20, 0, 1));

        add(new Definition("glow", "Glow", "Stylize")
            .p("radius", "Radius", 0, 200, 40, 1)
            .p("intensity", "Intensity", 0, 4, 1.2F, 0.01F)
            .c("color", "Tint", 0xFFFFFFFF));
        add(new Definition("drop_shadow", "Drop Shadow", "Stylize")
            .p("distance", "Distance", -300, 300, 12, 1)
            .p("angle", "Angle", -180, 180, 45, 1)
            .p("softness", "Softness", 0, 100, 6, 1)
            .p("opacity", "Opacity", 0, 1, 0.65F, 0.01F)
            .c("color", "Shadow Color", 0xFF000000));
        add(new Definition("stroke", "Stroke", "Stylize")
            .p("width", "Width", 0, 40, 3, 0.5F)
            .p("opacity", "Opacity", 0, 1, 1, 0.01F)
            .c("color", "Color", 0xFFFFFFFF));
        add(new Definition("outer_glow", "Outer Glow", "Stylize")
            .p("radius", "Size", 0, 200, 24, 1)
            .p("intensity", "Intensity", 0, 4, 1, 0.01F)
            .p("opacity", "Opacity", 0, 1, 0.8F, 0.01F)
            .c("color", "Color", 0xFF55AAFF));
        add(new Definition("mosaic", "Mosaic", "Stylize")
            .p("size", "Cell Size", 1, 256, 16, 1));
        add(new Definition("posterize", "Posterize", "Stylize")
            .p("levels", "Levels", 2, 64, 8, 1));
        add(new Definition("threshold", "Threshold", "Stylize")
            .p("threshold", "Threshold", 0, 1, 0.5F, 0.01F));
        add(new Definition("find_edges", "Find Edges", "Stylize")
            .p("amount", "Amount", 0, 4, 1, 0.01F)
            .p("invert", "Invert", 0, 1, 1, 1));

        add(new Definition("linear_wipe", "Linear Wipe", "Transition")
            .p("progress", "Progress", 0, 1, 0.5F, 0.01F)
            .p("angle", "Angle", -180, 180, 90, 1)
            .p("softness", "Softness", 0, 0.5F, 0.02F, 0.001F));
        add(new Definition("radial_wipe", "Radial Wipe", "Transition")
            .p("progress", "Progress", 0, 1, 0.5F, 0.01F)
            .p("softness", "Softness", 0, 0.5F, 0.02F, 0.001F)
            .p("center_x", "Center X", 0, 1, 0.5F, 0.01F)
            .p("center_y", "Center Y", 0, 1, 0.5F, 0.01F));
        add(new Definition("dissolve", "Dissolve", "Transition")
            .p("progress", "Progress", 0, 1, 0.5F, 0.01F)
            .p("scale", "Scale", 1, 300, 60, 1)
            .p("seed", "Seed", 0, 9999, 7, 1));

        add(new Definition("depth_matte", "Depth Matte", "3D Channel")
            .p("near", "Near", 0, 1, 0.2F, 0.01F)
            .p("far", "Far", 0, 1, 0.8F, 0.01F)
            .p("softness", "Softness", 0.001F, 0.5F, 0.05F, 0.001F));
        add(new Definition("depth_fog", "Depth Fog", "3D Channel")
            .p("near", "Near", 0, 1, 0.25F, 0.01F)
            .p("far", "Far", 0, 1, 0.9F, 0.01F)
            .p("amount", "Amount", 0, 1, 0.5F, 0.01F)
            .c("color", "Fog Color", 0xFFAFC7D9));
        add(new Definition("depth_dof", "Depth of Field", "3D Channel")
            .p("focus", "Focus Depth", 0, 1, 0.45F, 0.01F)
            .p("range", "Focus Range", 0.01F, 1, 0.15F, 0.01F)
            .p("radius", "Blur", 0, 100, 12, 1));
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
