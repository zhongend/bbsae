package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompStyle;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.opengl.GL11;

/** Layer styles implemented as short effect chains. */
public final class StyleRenderer
{
    private StyleRenderer()
    {}

    public static GlFramebuffer apply(CompStyle style, GlFramebuffer content, CompRenderContext context, CompLayer layer, org.joml.Matrix4f world)
    {
        switch (style.type)
        {
            case CompStyle.DROP_SHADOW:
            {
                CompEffect effect = new CompEffect("style", "drop_shadow");

                effect.putColor("color", style.color);
                effect.put("distance", style.distance);
                effect.put("angle", style.angle);
                effect.put("softness", style.blur);
                effect.put("opacity", style.opacity);

                return EffectRenderer.apply(null, effect, content, context);
            }

            case CompStyle.INNER_SHADOW:
                return EffectRenderer.inner(content, style.color, style.opacity, style.distance, style.angle, Math.max(1F, style.blur));

            case CompStyle.OUTER_GLOW:
            {
                CompEffect effect = new CompEffect("style", "outer_glow");

                effect.putColor("color", style.color);
                effect.put("radius", style.size);
                effect.put("opacity", style.opacity);
                effect.put("intensity", 1F);

                return EffectRenderer.apply(null, effect, content, context);
            }

            case CompStyle.INNER_GLOW:
                return EffectRenderer.inner(content, style.color, style.opacity, 0F, 0F, Math.max(1F, style.size));

            case CompStyle.SATIN:
                return EffectRenderer.inner(content, style.color, style.opacity * 0.6F, style.distance, style.angle + 180F, Math.max(1F, style.blur));

            case CompStyle.STROKE:
            {
                CompEffect effect = new CompEffect("style", "stroke");

                effect.putColor("color", style.color);
                effect.put("width", Math.max(1F, style.size));

                return EffectRenderer.apply(null, effect, content, context);
            }

            case CompStyle.COLOR_OVERLAY:
                return tint(content, style.color, style.opacity);

            case CompStyle.GRADIENT_OVERLAY:
                return gradient(content, style.color, style.color2, style.angle, style.opacity, EffectSpace.of(layer, world, context));

            case CompStyle.BEVEL:
            {
                GlFramebuffer light = EffectRenderer.inner(content, style.color, style.opacity * 0.5F, style.distance, style.angle + 180F, Math.max(1F, style.blur));
                GlFramebuffer result = EffectRenderer.inner(light, style.color2, style.opacity * 0.5F, style.distance, style.angle, Math.max(1F, style.blur));

                if (light != content)
                {
                    FboPool.release(light);
                }

                return result;
            }

            default:
                return content;
        }
    }

    private static GlFramebuffer gradient(GlFramebuffer content, int colorA, int colorB, float angle, float opacity, EffectSpace space)
    {
        GlslProgram program = CompShaders.gradient;
        GlFramebuffer output = FboPool.acquire(content.width, content.height);

        if (program == null)
        {
            FboPool.release(output);
            return content;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, content);
        space.bind(program);
        program.set("ColorA", Colors.getR(colorA), Colors.getG(colorA), Colors.getB(colorA), 1F);
        program.set("ColorB", Colors.getR(colorB), Colors.getG(colorB), Colors.getB(colorB), 1F);
        program.set("Angle", angle);
        program.set("Opacity", Math.max(0F, Math.min(1F, opacity)));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer tint(GlFramebuffer content, int color, float opacity)
    {
        GlslProgram program = CompShaders.color;
        GlFramebuffer output = FboPool.acquire(content.width, content.height);

        if (program == null)
        {
            FboPool.release(output);
            return content;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, content);
        program.set("Size", content.width, content.height);
        program.set("Brightness", 0F);
        program.set("Contrast", 0F);
        program.set("Saturation", 0F);
        program.set("Hue", 0F);
        program.set("Exposure", 0F);
        program.set("Gamma", 1F);
        program.set("BlackPoint", 0F);
        program.set("WhitePoint", 1F);
        program.set("Lightness", 0F);
        program.set("Shadow", 0F);
        program.set("Midtone", 0F);
        program.set("Highlight", 0F);
        program.set("Tint", Colors.getR(color), Colors.getG(color), Colors.getB(color), 1F);
        program.set("TintAmount", Math.max(0F, Math.min(1F, opacity)));
        program.set("Invert", 0F);
        program.set("Posterize", 0F);
        program.set("Threshold", -1F);
        program.set("Vignette", 0F);
        program.set("VignetteRadius", 0.55F);
        program.set("VignetteSoftness", 0.45F);
        program.set("VignetteColor", 0F, 0F, 0F, 1F);
        program.set("NoiseAmount", 0F);
        program.set("NoiseScale", 64F);
        program.set("Seed", 0F);
        program.drawFullscreen();

        return output;
    }
}
