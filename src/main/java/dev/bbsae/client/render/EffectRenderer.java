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
        return apply(layer, effect, input, context, null);
    }

    /**
     * The single source of truth for "this catalogue entry has a renderer". It is used by the
     * coverage test and by future UI validation; keeping it a set makes a reverse check possible
     * (every supported id must also exist in the registry).
     */
    private static final java.util.Set<String> SUPPORTED = java.util.Set.of(
        "blur", "box_blur", "radial_blur", "directional_blur", "sharpen",
        "color_grade", "brightness_contrast", "hue_saturation", "exposure", "tint",
        "levels", "curves", "invert", "vignette", "noise", "grain", "posterize",
        "threshold", "mosaic", "find_edges", "scanlines", "glitch", "vhs",
        "halftone", "emboss", "mirror", "kaleidoscope",
        "chroma_key", "luma_key", "matte_choker",
        "rgb_split", "shake", "block_shatter", "gradient_ramp", "noise_gradient",
        "checkerboard", "grid", "circle", "displacement", "bulge", "pinch", "twist",
        "wave", "linear_wipe", "radial_wipe", "dissolve", "glow", "outer_glow",
        "drop_shadow", "stroke", "depth_matte", "depth_fog", "depth_dof"
    );

    public static boolean supports(String type)
    {
        return type != null && SUPPORTED.contains(type);
    }

    public static java.util.Set<String> supportedTypes()
    {
        return SUPPORTED;
    }

    public static GlFramebuffer apply(CompLayer layer, CompEffect effect, GlFramebuffer input, CompRenderContext context, org.joml.Matrix4f world)
    {
        if (effect == null || !effect.enabled || input == null)
        {
            return input;
        }

        String type = effect.type == null ? "" : effect.type;
        float frame = context.frame;
        EffectSpace space = EffectSpace.of(layer, world, context);

        switch (type)
        {
            case "blur":
                return blur(input, effect.valueF("radius", 10F), effect.valueF("opacity", 1F), false);
            case "box_blur":
                return blur(input, effect.valueF("radius", 8F), effect.valueF("opacity", 1F), true);
            case "radial_blur":
                return radialBlur(input, effect, space);
            case "directional_blur":
                return directionalBlur(input, effect.valueF("radius", 20F), effect.valueF("angle", 0F), effect.valueF("opacity", 1F));
            case "sharpen":
                return sharpen(input, effect);
            case "color_grade":
                return grade(input, effect);
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
                return color(input, effect, frame, context, type, space);
            case "mosaic":
            case "find_edges":
            case "scanlines":
            case "glitch":
            case "halftone":
            case "emboss":
            case "mirror":
            case "kaleidoscope":
            case "vhs":
                return pixel(input, effect, context, space);
            case "chroma_key":
            case "luma_key":
                return key(input, effect, type);
            case "matte_choker":
                return key(input, effect, "matte_choker");
            case "rgb_split":
                return rgbSplit(input, effect);
            case "shake":
                /* Transform-only effect: CompRenderer applies it while building the model
                 * matrix, so the pixel pass must not process the image twice. */
                return input;
            case "block_shatter":
                return shatter(input, effect, space);
            case "gradient_ramp":
            case "noise_gradient":
            case "checkerboard":
            case "grid":
            case "circle":
                return generate(input, effect, context, type, space);
            case "displacement":
            case "bulge":
            case "pinch":
            case "twist":
            case "wave":
                return distort(input, effect, context, type, space);
            case "linear_wipe":
            case "radial_wipe":
            case "dissolve":
                return transition(input, effect, context, type, space);
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

    private static GlFramebuffer radialBlur(GlFramebuffer input, CompEffect effect, EffectSpace space)
    {
        GlslProgram program = CompShaders.radial;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        org.joml.Vector2f center = space.localToCompUv(
            effect.valueF("center_x", 0.5F),
            effect.valueF("center_y", 0.5F)
        );

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Center", center.x, center.y);
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

    private static GlFramebuffer color(GlFramebuffer input, CompEffect effect, float frame, CompRenderContext context, String type, EffectSpace space)
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
        space.bind(program);

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

    /**
     * The resolved uniforms for one PIXEL-family pass. Keeping this as plain data (instead of
     * reading Effect fields inside the GL method) lets the headless tests assert that every
     * catalogue parameter actually reaches the shader; a dead parameter is otherwise invisible,
     * because glUniform* on a missing/ignored name is a silent no-op.
     */
    static final class PixelPlan
    {
        final int mode;
        final float amount;
        final float invert;
        final float phase;
        final float p1;
        final float p2;
        final float p3;
        final float p4;
        final int dotColor;
        final int paperColor;

        PixelPlan(int mode, float amount, float invert, float phase,
                  float p1, float p2, float p3, float p4, int dotColor, int paperColor)
        {
            this.mode = mode;
            this.amount = amount;
            this.invert = invert;
            this.phase = phase;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.p4 = p4;
            this.dotColor = dotColor;
            this.paperColor = paperColor;
        }

        java.util.List<Object> signature()
        {
            return java.util.List.of(mode, amount, invert, phase, p1, p2, p3, p4, dotColor, paperColor);
        }
    }

    static PixelPlan planPixel(CompEffect effect, CompRenderContext context)
    {
        if (effect == null || effect.type == null)
        {
            return null;
        }

        float time = context == null ? 0F : context.time;
        String type = effect.type;
        float amount = 1F;
        float invert = 0F;
        float phase = 0F;
        float p1 = 0F;
        float p2 = 0F;
        float p3 = 0F;
        float p4 = 0F;
        int dotColor = 0xFF000000;
        int paperColor = 0xFFFFFFFF;

        switch (type)
        {
            case "mosaic" -> amount = Math.max(1F, effect.valueF("size", 16F));
            case "find_edges" ->
            {
                amount = effect.valueF("amount", 1F);
                invert = effect.valueF("invert", 1F);
            }
            case "scanlines" ->
            {
                amount = effect.valueF("opacity", 0.55F);
                p1 = Math.max(1F, effect.valueF("spacing", 4F));
                phase = effect.valueF("offset", 0F);
            }
            case "glitch" ->
            {
                amount = effect.valueF("amount", 18F);
                p1 = Math.max(2F, effect.valueF("bands", 24F));
                p2 = effect.valueF("seed", 0F);
                phase = time * Math.max(0F, effect.valueF("speed", 6F));
            }
            case "halftone" ->
            {
                amount = Math.max(0F, Math.min(1F, effect.valueF("strength", 0.5F)));
                p1 = Math.max(2F, effect.valueF("amount", 100F));
                p2 = effect.valueF("phase", 0.5F);
                p3 = effect.valueF("angle", 45F);
                dotColor = effect.color("dot_color", 0xFF000000);
                paperColor = effect.color("paper_color", 0xFFFFFFFF);
            }
            case "emboss" ->
            {
                amount = effect.valueF("amount", 1F);
                p3 = effect.valueF("angle", 45F);
            }
            case "mirror" ->
            {
                amount = Math.max(0F, Math.min(1F, effect.valueF("opacity", 1F)));
                p1 = effect.valueF("vertical", 0F) >= 0.5F ? 1F : 0F;
            }
            case "kaleidoscope" ->
            {
                p1 = Math.max(2F, effect.valueF("segments", 6F));
                p3 = effect.valueF("angle", 0F);
            }
            case "vhs" ->
            {
                amount = Math.max(0F, Math.min(1F, effect.valueF("amount", 0.5F)));
                p3 = Math.max(0F, Math.min(1F, effect.valueF("noise", 0.2F)));
                phase = time * Math.max(0F, effect.valueF("speed", 6F));
            }
            default -> {
                return null;
            }
        }

        int mode = switch (type)
        {
            case "mosaic" -> 0;
            case "find_edges" -> 1;
            case "scanlines" -> 2;
            case "glitch" -> 3;
            case "halftone" -> 4;
            case "emboss" -> 5;
            case "mirror" -> 6;
            case "kaleidoscope" -> 7;
            default -> 8;
        };

        return new PixelPlan(mode, amount, invert, phase, p1, p2, p3, p4, dotColor, paperColor);
    }

    private static GlFramebuffer pixel(GlFramebuffer input, CompEffect effect, CompRenderContext context, EffectSpace space)
    {
        GlslProgram program = CompShaders.pixel;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);
        PixelPlan plan = planPixel(effect, context);

        if (program == null || plan == null)
        {
            FboPool.release(output);
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        space.bind(program);
        program.set("Size", input.width, input.height);
        program.set("Mode", plan.mode);
        program.set("Amount", plan.amount);
        program.set("Invert", plan.invert);
        program.set("Phase", plan.phase);
        program.set("Param", plan.p1, plan.p2, plan.p3, plan.p4);
        setColor(program, "DotColor", plan.dotColor);
        setColor(program, "PaperColor", plan.paperColor);
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

    private static GlFramebuffer generate(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type, EffectSpace space)
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
        space.bind(program);
        program.set("Size", input.width, input.height);
        program.set("Type", type.equals("gradient_ramp") ? 0 : type.equals("noise_gradient") ? 1 : type.equals("checkerboard") ? 2 : type.equals("grid") ? 3 : 4);
        setColor(program, "ColorA", effect.color("start", effect.color("a", effect.color("color", 0xFF55AAFF))));
        setColor(program, "ColorB", effect.color("end", effect.color("b", 0x00000000)));
        program.set("Angle", effect.valueF("angle", 90F));

        float scale = effect.valueF("scale", effect.valueF("size", 64F));

        program.set("Scale", scale);
        /* Gradient ramp fallback: 1 = full smooth ramp. 0.05 (the old fallback) made every
         * project saved before the softness parameter existed a hard two-tone split. */
        program.set("Softness", Math.max(0.001F, effect.valueF("softness", 1F)));

        if ("grid".equals(type))
        {
            /* The registry expresses grid line width in pixels; the shader works in cells. */
            program.set("Softness", Math.max(0.001F, effect.valueF("thickness", 2F) / Math.max(1F, scale)));
        }
        else if ("circle".equals(type))
        {
            /* Shader measures in pixels normalised by the shorter layer side; radius is that
             * fraction directly (0.35 = 35% of the short side), no diagonal fudge. */
            program.set("Scale", Math.max(0.001F, effect.valueF("radius", 0.35F)));
        }

        program.set("Center", effect.valueF("center_x", 0.5F), effect.valueF("center_y", 0.5F));
        program.set("Evolution", effect.valueF("evolution", 0F));
        program.set("Octaves", Math.max(1, Math.min(8, Math.round(effect.valueF("octaves", 4F)))));
        program.set("Contrast", effect.valueF("contrast", 1F));
        program.set("Seed", effect.valueF("seed", 0F));
        program.set("Brightness", effect.valueF("brightness", 0F));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer distort(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type, EffectSpace space)
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
        space.bind(program);

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
        program.set("Amount", "wave".equals(type) ? effect.valueF("amplitude", effect.valueF("amount", 24F)) : effect.valueF("amount", 48F));
        program.set("Angle", effect.valueF("angle", 30F));
        program.set("Center", effect.valueF("center_x", 0.5F), effect.valueF("center_y", 0.5F));
        program.set("Radius", Math.max(0.01F, effect.valueF("radius", 0.75F)));
        /* Wavelength is in pixels; the shader wants radians per pixel. The old 0.02 factor made
         * a 180 px wavelength behave like 314 px and hid the effect on small layers. */
        program.set("Frequency", (float) (Math.PI * 2D / Math.max(1F, effect.valueF("wavelength", 180F))));
        program.set("Phase", effect.valueF("phase", 0F));
        program.set("Evolution", effect.valueF("evolution", 0F));
        program.set("Vertical", effect.valueF("vertical", 0F) > 0.5F ? 1 : 0);
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer transition(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type, EffectSpace space)
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
        space.bind(program);
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
        /* PassRenderer offsets are texture-space (V grows upwards); screen +Y is down, so the
         * vertical component has to be negated or a 90° shadow lands above the layer. */
        GlFramebuffer base = PassRenderer.draw(shadow, null, null, CompLayer.BLEND_NORMAL, 1F, dx, -dy, 0xFFFFFF);
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

        if ("depth_dof".equals(type))
        {
            /* DOF has focus/range, not near/far/softness. It previously read "near" and
             * "softness" here, which meant the 对焦深度 slider did nothing at all. */
            float focus = effect.valueF("focus", 0.45F);
            float range = Math.max(0.001F, effect.valueF("range", 0.15F));

            program.set("Near", focus);
            program.set("Far", focus + range);
            program.set("Softness", range);
        }
        else
        {
            program.set("Near", effect.valueF("near", 0.2F));
            program.set("Far", effect.valueF("far", 0.9F));
            program.set("Softness", Math.max(0.001F, effect.valueF("softness", 0.08F)));
        }
        program.set("Amount", effect.valueF("amount", 0.5F));
        program.set("Radius", effect.valueF("radius", 12F));
        program.set("Texel", 1F / input.width, 1F / input.height);
        setColor(program, "Color", effect.color("color", 0xFFAFC7D9));
        program.drawFullscreen();

        return output;
    }


    private static GlFramebuffer rgbSplit(GlFramebuffer input, CompEffect effect)
    {
        GlslProgram program = CompShaders.rgbSplit;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        float amount = effect.valueF("amount", 14F);
        float angle = (float) Math.toRadians(effect.valueF("angle", 0F));

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Texel", 1F / input.width, 1F / input.height);
        program.set("Offset", (float) Math.cos(angle) * amount, (float) Math.sin(angle) * amount);
        program.set("Amount", effect.valueF("mix", 1F));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer shatter(GlFramebuffer input, CompEffect effect, EffectSpace space)
    {
        GlslProgram program = CompShaders.shatter;
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
        space.bind(program);
        program.set("Size", input.width, input.height);
        program.set("Progress", effect.valueF("progress", 0.35F));
        program.set("Blocks", effect.valueF("blocks", 12F));
        program.set("Spread", effect.valueF("spread", 0.45F));
        program.set("Rotation", effect.valueF("rotation", 1F));
        program.set("Gravity", effect.valueF("gravity", 0.35F));
        program.set("Seed", effect.valueF("seed", 0F));
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer grade(GlFramebuffer input, CompEffect effect)
    {
        GlslProgram program = CompShaders.grade;
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
        setColor(program, "Lift", effect.color("lift", 0xFF808080));
        setColor(program, "Gamma", effect.color("gamma", 0xFF808080));
        setColor(program, "Gain", effect.color("gain", 0xFF808080));
        program.set("Exposure", effect.valueF("exposure", 0F));
        program.set("Contrast", effect.valueF("contrast", 0F));
        program.set("Saturation", effect.valueF("saturation", 1F));
        program.set("Vibrance", effect.valueF("vibrance", 0F));
        program.set("Temperature", effect.valueF("temperature", 0F));
        program.set("Tint", effect.valueF("tint", 0F));
        program.set("Hue", effect.valueF("hue", 0F));
        program.set("Fade", effect.valueF("fade", 0F));
        program.set("CurveBlack", effect.valueF("curve_black", 0F));
        program.set("CurveShadow", effect.valueF("curve_shadow", 0F));
        program.set("CurveMid", effect.valueF("curve_mid", 0F));
        program.set("CurveHighlight", effect.valueF("curve_highlight", 0F));
        program.set("CurveWhite", effect.valueF("curve_white", 0F));
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
