package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Executes the curated effect catalogue on the GPU. Effects are deliberately expressed as a
 * handful of shader families plus parameter interpretation, rather than 239 separate programs.
 */
public final class EffectRenderer
{
    private EffectRenderer()
    {}

    public static GlFramebuffer apply(CompLayer layer, CompEffect effect, GlFramebuffer input, CompRenderContext context)
    {
        if (effect == null || !effect.enabled || input == null)
        {
            return input;
        }

        String type = effect.type == null ? "" : effect.type;
        float frame = context.frame;

        switch (type)
        {
            case "blur":
                return blur(input, effect.valueF("radius", 10F), effect.valueF("opacity", 1F), false);
            case "box_blur":
                return blur(input, effect.valueF("radius", 8F), effect.valueF("opacity", 1F), true);
            case "radial_blur":
                return radialBlur(input, effect);
            case "directional_blur":
                return directionalBlur(input, effect.valueF("radius", 20F), effect.valueF("angle", 0F), effect.valueF("opacity", 1F));
            case "sharpen":
                return sharpen(input, effect);
            case "brightness_contrast":
            case "hue_saturation":
            case "exposure":
            case "tint":
            case "levels":
            case "curves":
            case "invert":
            case "vignette":
            case "noise":
            case "grain":
            case "posterize":
            case "threshold":
                return color(input, effect, frame, context, type);
            case "mosaic":
            case "find_edges":
                return pixel(input, effect, context, type);
            case "chroma_key":
            case "luma_key":
                return key(input, effect, type);
            case "matte_choker":
                return key(input, effect, "matte_choker");
            case "gradient_ramp":
            case "noise_gradient":
            case "checkerboard":
            case "grid":
            case "circle":
                return generate(input, effect, context, type);
            case "displacement":
            case "bulge":
            case "pinch":
            case "twist":
            case "wave":
                return distort(input, effect, context, type);
            case "linear_wipe":
            case "radial_wipe":
            case "dissolve":
                return transition(input, effect, context, type);
            case "glow":
                return glow(input, effect, false);
            case "outer_glow":
                return glow(input, effect, true);
            case "drop_shadow":
                return dropShadow(input, effect);
            case "stroke":
                return stroke(input, effect);
            case "depth_matte":
            case "depth_fog":
            case "depth_dof":
                return depth(input, effect, context, type);
            default:
                return input;
        }
    }

    private static GlFramebuffer blur(GlFramebuffer input, float radius, float mix, boolean box)
    {
        GlFramebuffer blurred = PassRenderer.blur(input, radius, box);

        if (blurred == input || mix >= 0.999F)
        {
            return blurred;
        }

        GlFramebuffer output = PassRenderer.draw(blurred, input, null, CompLayer.BLEND_NORMAL, mix, 0F, 0F, 0xFFFFFF);

        FboPool.release(blurred);
        return output;
    }

    private static GlFramebuffer radialBlur(GlFramebuffer input, CompEffect effect)
    {
        GlslProgram program = CompShaders.radial;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Center", effect.valueF("center_x", 0.5F), effect.valueF("center_y", 0.5F));
        program.set("Amount", effect.valueF("radius", 0.15F));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer sharpen(GlFramebuffer input, CompEffect effect)
    {
        GlslProgram program = CompShaders.sharpen;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Texel", 1F / input.width, 1F / input.height);
        program.set("Amount", Math.max(0F, effect.valueF("amount", 1F)));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer directionalBlur(GlFramebuffer input, float radius, float angle, float mix)
    {
        GlFramebuffer output = FboPool.acquire(input.width, input.height);
        GlslProgram program = CompShaders.blur;

        if (program == null || radius <= 0.5F)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Texel", 1F / input.width, 1F / input.height);
        program.set("Direction", (float) Math.cos(Math.toRadians(angle)), (float) Math.sin(Math.toRadians(angle)));
        program.set("Radius", Math.min(200F, radius));
        program.drawFullscreen();

        if (mix < 0.999F)
        {
            GlFramebuffer blended = PassRenderer.draw(output, input, null, CompLayer.BLEND_NORMAL, mix, 0F, 0F, 0xFFFFFF);

            FboPool.release(output);
            return blended;
        }

        return output;
    }

    private static GlFramebuffer color(GlFramebuffer input, CompEffect effect, float frame, CompRenderContext context, String type)
    {
        GlslProgram program = CompShaders.color;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);

        program.set("Size", input.width, input.height);
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
        program.set("Tint", 1F, 1F, 1F, 1F);
        program.set("TintAmount", 0F);
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

        switch (type)
        {
            case "brightness_contrast":
                program.set("Brightness", effect.valueF("brightness", 0F));
                program.set("Contrast", effect.valueF("contrast", 0F));
                break;

            case "hue_saturation":
                program.set("Hue", effect.valueF("hue", 0F));
                program.set("Saturation", effect.valueF("saturation", 0F));
                program.set("Lightness", effect.valueF("lightness", 0F));
                break;

            case "exposure":
                program.set("Exposure", effect.valueF("exposure", 0F));
                program.set("Gamma", Math.max(0.01F, effect.valueF("gamma", 1F)));
                break;

            case "tint":
                setColor(program, "Tint", effect.color("color", 0xFFFFFFFF));
                program.set("TintAmount", effect.valueF("amount", 1F));
                break;

            case "levels":
            {
                float black = effect.valueF("black", 0F);
                float white = Math.max(black + 0.001F, effect.valueF("white", 1F));

                program.set("BlackPoint", black);
                program.set("WhitePoint", white);
                program.set("Gamma", Math.max(0.01F, effect.valueF("gamma", 1F)));
                break;
            }

            case "curves":
            {
                float amount = effect.valueF("amount", 1F);

                program.set("Amount", amount);
                program.set("Shadow", effect.valueF("shadow", 0F) * amount);
                program.set("Midtone", effect.valueF("midtone", 0F) * amount);
                program.set("Highlight", effect.valueF("highlight", 0F) * amount);
                break;
            }

            case "invert":
                program.set("Invert", effect.valueF("amount", 1F));
                break;

            case "vignette":
                program.set("Vignette", effect.valueF("amount", 0.5F));
                program.set("VignetteRadius", effect.valueF("radius", 0.75F));
                program.set("VignetteSoftness", Math.max(0.001F, effect.valueF("softness", 0.4F)));
                setColor(program, "VignetteColor", effect.color("color", 0xFF000000));
                break;

            case "noise":
                program.set("NoiseAmount", effect.valueF("amount", 0.2F));
                program.set("NoiseScale", effect.valueF("scale", 64F));
                program.set("Seed", effect.valueF("seed", 0F));
                break;

            case "grain":
                program.set("NoiseAmount", effect.valueF("amount", 0.12F));
                program.set("NoiseScale", effect.valueF("size", 2F));
                program.set("Seed", effect.valueF("seed", 0F));
                break;

            case "posterize":
                program.set("Posterize", Math.max(2F, effect.valueF("levels", 8F)));
                break;

            case "threshold":
                program.set("Threshold", effect.valueF("threshold", 0.5F));
                break;

            default:
                break;
        }

        program.drawFullscreen();
        return output;
    }

    private static GlFramebuffer pixel(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type)
    {
        GlslProgram program = CompShaders.pixel;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Size", input.width, input.height);
        program.set("Mode", "mosaic".equals(type) ? 0 : 1);
        program.set("Amount", "mosaic".equals(type) ? effect.valueF("size", 16F) : effect.valueF("amount", 1F));
        program.set("Invert", "find_edges".equals(type) ? effect.valueF("invert", 1F) : 0F);
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer key(GlFramebuffer input, CompEffect effect, String type)
    {
        GlslProgram program = CompShaders.key;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Mode", "chroma_key".equals(type) ? 0 : "luma_key".equals(type) ? 1 : 2);
        setColor(program, "KeyColor", effect.color("key", 0xFF00FF00));
        program.set("Tolerance", effect.valueF("tolerance", 0.2F));
        program.set("Softness", Math.max(0.001F, effect.valueF("softness", 0.08F)));
        program.set("Spill", effect.valueF("spill", 0.4F));
        program.set("Threshold", effect.valueF("threshold", effect.valueF("choke", 0.3F)));
        program.set("Invert", effect.valueF("invert", 0F));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer generate(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type)
    {
        GlslProgram program = CompShaders.generate;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Size", input.width, input.height);
        program.set("Type", type.equals("gradient_ramp") ? 0 : type.equals("noise_gradient") ? 1 : type.equals("checkerboard") ? 2 : type.equals("grid") ? 3 : 4);
        setColor(program, "ColorA", effect.color("start", effect.color("a", effect.color("color", 0xFF55AAFF))));
        setColor(program, "ColorB", effect.color("end", effect.color("b", 0x00000000)));
        program.set("Angle", effect.valueF("angle", 90F));

        float scale = effect.valueF("scale", effect.valueF("size", 64F));

        program.set("Scale", scale);
        program.set("Softness", Math.max(0.001F, effect.valueF("softness", 0.05F)));

        if ("grid".equals(type))
        {
            /* The registry expresses grid line width in pixels; the shader works in cells. */
            program.set("Softness", Math.max(0.001F, effect.valueF("thickness", 2F) / Math.max(1F, scale)));
        }
        else if ("circle".equals(type))
        {
            program.set("Scale", Math.max(0.001F, effect.valueF("radius", 0.35F) * 0.7071F));
        }

        program.set("Center", effect.valueF("center_x", 0.5F), effect.valueF("center_y", 0.5F));
        program.set("Evolution", effect.valueF("evolution", 0F));
        program.set("Octaves", Math.max(1, Math.min(8, Math.round(effect.valueF("octaves", 4F)))));
        program.set("Contrast", effect.valueF("contrast", 1F));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer distort(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type)
    {
        GlslProgram program = CompShaders.distort;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        int mode = type.equals("displacement") ? 0 : type.equals("bulge") ? 1 : type.equals("pinch") ? 2 : type.equals("twist") ? 3 : 4;

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);

        if (context.hasDepth())
        {
            PassRenderer.bindRaw(program, "Map", 1, context.depth.texture());
            program.set("UseMap", effect.valueF("depth", 0F) > 0.5F ? 1 : 0);
        }
        else
        {
            PassRenderer.bindRaw(program, "Map", 1, 0);
            program.set("UseMap", 0);
        }

        program.set("Mode", mode);
        program.set("Size", input.width, input.height);
        program.set("Amount", "wave".equals(type) ? effect.valueF("amplitude", effect.valueF("amount", 18F)) : effect.valueF("amount", 24F));
        program.set("Angle", effect.valueF("angle", 30F));
        program.set("Center", effect.valueF("center_x", 0.5F), effect.valueF("center_y", 0.5F));
        program.set("Radius", effect.valueF("radius", 0.5F));
        program.set("Frequency", effect.valueF("wavelength", 180F) * 0.02F);
        program.set("Phase", effect.valueF("phase", 0F));
        program.set("Evolution", effect.valueF("evolution", 0F));
        program.set("Vertical", effect.valueF("vertical", 0F) > 0.5F ? 1 : 0);
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer transition(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type)
    {
        GlslProgram program = CompShaders.transition;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Mode", type.equals("linear_wipe") ? 0 : type.equals("radial_wipe") ? 1 : 2);
        program.set("Progress", effect.valueF("progress", 0.5F));
        program.set("Softness", effect.valueF("softness", 0.02F));
        program.set("Angle", effect.valueF("angle", 90F));
        program.set("Center", effect.valueF("center_x", 0.5F), effect.valueF("center_y", 0.5F));
        program.set("Scale", effect.valueF("scale", 60F));
        program.set("Seed", effect.valueF("seed", 7F));
        program.drawFullscreen();

        return output;
    }

    public static GlFramebuffer inner(GlFramebuffer input, int color, float opacity, float distance, float angle, float radius)
    {
        GlFramebuffer blurred = PassRenderer.blur(input, Math.max(0.1F, radius));
        GlslProgram program = CompShaders.inner;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);

            if (blurred != input)
            {
                FboPool.release(blurred);
            }

            return input;
        }

        float radians = (float) Math.toRadians(angle);
        float dx = (float) Math.cos(radians) * distance / Math.max(1, input.width);
        float dy = (float) Math.sin(radians) * distance / Math.max(1, input.height);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        PassRenderer.bind(program, "Blurred", 1, blurred);
        setColor(program, "Color", color);
        program.set("Opacity", opacity);
        program.set("Offset", dx, dy);
        program.drawFullscreen();

        if (blurred != input)
        {
            FboPool.release(blurred);
        }

        return output;
    }

    private static GlFramebuffer glow(GlFramebuffer input, CompEffect effect, boolean outer)
    {
        float radius = effect.valueF("radius", outer ? 24F : 40F);
        GlFramebuffer blurred = PassRenderer.blur(input, radius);

        if (blurred == input)
        {
            return input;
        }

        int color = effect.color("color", outer ? 0xFF55AAFF : 0xFFFFFFFF);
        GlFramebuffer tinted = PassRenderer.copy(blurred, color, effect.valueF("opacity", 1F));
        GlFramebuffer output = PassRenderer.draw(tinted, input, null, CompLayer.BLEND_ADD, effect.valueF("intensity", 1F), 0F, 0F, 0xFFFFFF);

        FboPool.release(blurred);
        FboPool.release(tinted);

        return output;
    }

    private static GlFramebuffer dropShadow(GlFramebuffer input, CompEffect effect)
    {
        float distance = effect.valueF("distance", 12F);
        float angle = (float) Math.toRadians(effect.valueF("angle", 45F));
        GlFramebuffer blurred = PassRenderer.blur(input, effect.valueF("softness", 6F));
        GlFramebuffer shadow = PassRenderer.copy(blurred, effect.color("color", 0xFF000000), effect.valueF("opacity", 0.65F));
        float dx = (float) Math.cos(angle) * distance;
        float dy = (float) Math.sin(angle) * distance;
        GlFramebuffer base = PassRenderer.draw(shadow, null, null, CompLayer.BLEND_NORMAL, 1F, dx, dy, 0xFFFFFF);
        GlFramebuffer output = PassRenderer.draw(input, base, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);

        FboPool.release(shadow);
        FboPool.release(base);

        if (blurred != input)
        {
            FboPool.release(blurred);
        }

        return output;
    }

    private static GlFramebuffer stroke(GlFramebuffer input, CompEffect effect)
    {
        GlslProgram program = CompShaders.stroke;
        GlFramebuffer stroke = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(stroke);
            return input;
        }

        stroke.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Texel", 1F / input.width, 1F / input.height);
        program.set("Width", effect.valueF("width", 3F));
        int color = effect.color("color", 0xFFFFFFFF);
        color = Colors.setA(color, Colors.getA(color) * effect.valueF("opacity", 1F));
        setColor(program, "Color", color);
        program.drawFullscreen();

        GlFramebuffer output = PassRenderer.draw(input, stroke, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);

        FboPool.release(stroke);
        return output;
    }

    private static GlFramebuffer depth(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type)
    {
        GlslProgram program = CompShaders.depth;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);

        int depthTexture = 0;

        if (context.hasDepth())
        {
            depthTexture = context.depth.texture();
        }

        PassRenderer.bindRaw(program, "Depth", 1, depthTexture);
        program.set("DepthAvailable", depthTexture == 0 ? 0 : 1);
        program.set("Mode", type.equals("depth_matte") ? 0 : type.equals("depth_fog") ? 1 : 2);
        program.set("Near", effect.valueF("near", type.equals("depth_dof") ? 0.45F : 0.2F));
        program.set("Far", effect.valueF("far", 0.9F));
        program.set("Softness", Math.max(0.001F, effect.valueF("softness", effect.valueF("range", 0.08F))));
        program.set("Amount", effect.valueF("amount", 0.5F));
        program.set("Radius", effect.valueF("radius", 12F));
        program.set("Texel", 1F / input.width, 1F / input.height);
        setColor(program, "Color", effect.color("color", 0xFFAFC7D9));
        program.drawFullscreen();

        return output;
    }

    private static void setColor(GlslProgram program, String name, int argb)
    {
        program.set(name,
            Colors.getR(argb),
            Colors.getG(argb),
            Colors.getB(argb),
            Colors.getA(argb)
        );
    }
}
