package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompStyle;

/**
 * How much transparent margin a layer raster needs so its pixel effects are not cut off.
 *
 * <p>A 2D layer is composited at composition resolution, so a blur or a glow simply samples the
 * transparent stage around the layer. The 3D path rasterizes the layer into a rectangle and
 * projects that rectangle; without extra margin a 40 px glow is clipped at the layer's edge and
 * looks like a hard border. This is the support region of the effect kernel, expressed in layer
 * pixels.</p>
 *
 * <p>Deliberately an upper bound: a little extra transparent pixels cost one larger texture, a
 * missing margin is visible in the render and in the export.</p>
 */
public final class EffectPadding
{
    private static final float MAX_PADDING = 512F;

    private EffectPadding()
    {}

    public static float of(CompLayer layer, float frame)
    {
        if (layer == null)
        {
            return 0F;
        }

        float padding = 0F;

        if (layer.effects != null)
        {
            for (CompEffect effect : layer.effects)
            {
                if (effect == null || !effect.enabled)
                {
                    continue;
                }

                CompEffect resolved = layer.resolveEffect(effect, frame);
                String type = resolved.type == null ? "" : resolved.type;

                if (EffectRenderer.isNeutral(resolved, type))
                {
                    continue;
                }

                padding = Math.max(padding, effectPadding(resolved, type, layer));
            }
        }

        if (layer.styles != null)
        {
            for (CompStyle style : layer.styles)
            {
                if (style == null || !style.enabled || style.opacity <= 0.0001F)
                {
                    continue;
                }

                padding = Math.max(padding, stylePadding(style));
            }
        }

        return Math.min(MAX_PADDING, padding);
    }

    private static float effectPadding(CompEffect effect, String type, CompLayer layer)
    {
        return switch (type)
        {
            case "blur" -> effect.valueF("radius", 10F) + 2F;
            case "box_blur" -> effect.valueF("radius", 8F) + 2F;
            case "directional_blur" -> effect.valueF("radius", 20F) + 2F;
            case "sharpen" -> 2F;
            case "glow" -> effect.valueF("radius", 40F) + 2F;
            case "outer_glow" -> effect.valueF("radius", 24F) + 2F;
            case "drop_shadow" -> effect.valueF("distance", 12F) + effect.valueF("softness", 6F) + 2F;
            case "stroke" -> effect.valueF("width", 3F) + 1F;
            case "rgb_split" -> effect.valueF("amount", 14F) + 2F;
            case "displacement", "bulge", "pinch", "twist", "turbulent_displace" ->
                effect.valueF("amount", 48F) + 2F;
            case "wave" -> effect.valueF("amplitude", effect.valueF("amount", 24F)) + 2F;
            case "glitch" -> effect.valueF("amount", 18F) + 2F;
            case "radial_blur" -> effect.valueF("radius", 0.15F) * Math.max(layer.width, layer.height) + 2F;
            default -> 0F;
        };
    }

    private static float stylePadding(CompStyle style)
    {
        String type = style.type == null ? "" : style.type;

        return switch (type)
        {
            case CompStyle.DROP_SHADOW -> style.distance + style.blur + 2F;
            case CompStyle.OUTER_GLOW -> style.size + 2F;
            case CompStyle.STROKE -> style.size + 1F;
            default -> 0F;
        };
    }
}
