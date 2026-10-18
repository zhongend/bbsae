package dev.bbsae.comp;

/**
 * A layer style is a separate render pass attached to one layer. It is intentionally distinct
 * from a blend mode: styles add shadow/glow/stroke geometry around the layer's own pixels.
 */
public class CompStyle
{
    public static final String DROP_SHADOW = "drop_shadow";
    public static final String INNER_SHADOW = "inner_shadow";
    public static final String OUTER_GLOW = "outer_glow";
    public static final String INNER_GLOW = "inner_glow";
    public static final String BEVEL = "bevel";
    public static final String COLOR_OVERLAY = "color_overlay";
    public static final String GRADIENT_OVERLAY = "gradient_overlay";
    public static final String SATIN = "satin";
    public static final String STROKE = "stroke";

    public String type = DROP_SHADOW;
    public boolean enabled = true;
    public int color = 0xFF000000;
    public int color2 = 0x00000000;
    public float opacity = 0.65F;
    public float size = 8F;
    public float distance = 6F;
    public float angle = 45F;
    public float blur = 6F;
    public int blendMode = CompLayer.BLEND_NORMAL;

    public CompStyle()
    {}

    public CompStyle(String type)
    {
        this.type = type;
    }
}
