package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.comp.AmEffectLibrary;
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
        return apply(layer, effect, input, context, (org.joml.Matrix4f) null);
    }

    /**
     * The single source of truth for "this catalogue entry has a renderer". It is used by the
     * coverage test and by future UI validation; keeping it a set makes a reverse check possible
     * (every supported id must also exist in the registry).
     */
    private static final java.util.Set<String> SUPPORTED = java.util.Set.of(
        "blur", "box_blur", "radial_blur", "directional_blur", "sharpen",
        "cc_cross_blur", "cc_radial_fast_blur", "cc_lens_blur", "channel_blur",
        "compound_blur", "bilateral_blur", "smart_blur", "unsharp_mask", "camera_shake_deblur",
        "color_grade", "brightness_contrast", "hue_saturation", "exposure", "tint",
        "levels", "curves", "invert", "vignette", "noise", "grain", "posterize",
        "threshold", "mosaic", "find_edges", "scanlines", "glitch", "vhs",
        "halftone", "emboss", "mirror", "kaleidoscope",
        "chroma_key", "luma_key", "matte_choker",
        "rgb_split", "shake", "block_shatter", "gradient_ramp", "noise_gradient",
        "checkerboard", "grid", "circle", "displacement", "bulge", "pinch", "twist",
        "wave", "linear_wipe", "radial_wipe", "dissolve", "glow", "outer_glow",
        "offset", "polar_coordinates", "spherize", "optics_compensation", "cc_lens",
        "cc_slant", "magnify", "cc_ripple_pulse", "cc_bend", "cc_split",
        "rolling_shutter_repair", "transform",
        "drop_shadow", "stroke", "depth_matte", "depth_fog", "depth_dof",
        "grid_repeat", "random_repeat", "turbulent_displace", "colorama", "light_sweep"
    );

    public static boolean supports(String type)
    {
        return type != null && (SUPPORTED.contains(type) || AmEffectLibrary.supports(type)
            || MeshRenderer.supports(type));
    }

    public static java.util.Set<String> supportedTypes()
    {
        java.util.Set<String> types = new java.util.LinkedHashSet<>(SUPPORTED);

        for (dev.bbsae.comp.AmEffectDef definition : AmEffectLibrary.all())
        {
            types.add(definition.id);
        }

        for (String shape : new String[] {"cube", "cylinder", "sphere", "pyramid", "cone", "torus",
            "ring", "plus", "octahedron", "hexprism", "triprism", "starprism", "heart",
            "hollowbox", "starpoly", "quad"})
        {
            types.add("s3d_" + shape);
        }

        return types;
    }

    public static GlFramebuffer apply(CompLayer layer, CompEffect effect, GlFramebuffer input, CompRenderContext context, org.joml.Matrix4f world)
    {
        return apply(layer, effect, input, context, EffectSpace.of(layer, world, context));
    }

    /**
     * Explicit effect space.
     *
     * <p>The composition pipeline lets {@link EffectSpace#of} decide (composition space for 3D
     * layers, layer space for 2D ones). The 3D viewport bakes effects into a <b>layer-local</b>
     * source texture, where composition space does not exist yet — it passes the mapping that
     * belongs to that raster directly.</p>
     */
    /** Parameter key shared by every effect: 0 = original layer, 1 = full effect. */
    public static final String STRENGTH = CompEffect.STRENGTH;

    /**
     * Applies one effect and blends the result back towards the input by the effect's
     * <b>strength</b> parameter.
     *
     * <p>This is the first-principles fix for "the layer must fade in and out". Every effect —
     * including the generative ones (gradient, checkerboard, kaleidoscope) that have no natural
     * zero value — is a function {@code f(input)}. A compositor needs the interpolation
     * {@code mix(input, f(input), strength)}, not a second interpretation of whatever the
     * effect's own parameters happen to mean. At strength 0 the layer is bit-for-bit the input,
     * at 1 it is the raw effect, and every keyframe in between is a real cross-fade.</p>
     *
     * <p>The parameter is stored in the same {@code params} map as the effect's own values, so
     * existing animation channels ({@code fx/<id>/strength}) and project serialisation work
     * without a format change.</p>
     */
    public static GlFramebuffer apply(CompLayer layer, CompEffect effect, GlFramebuffer input, CompRenderContext context, EffectSpace space)
    {
        if (effect == null || !effect.enabled || input == null)
        {
            return input;
        }

        float strength = effect.valueF(STRENGTH, 1F);

        if (strength <= 0.0001F)
        {
            return input;
        }

        String type = effect.type == null ? "" : effect.type;

        /* A zero main parameter must be the original image, not "the effect running with zero
         * intensity". Some shaders clamp (a 0 px blur, a 0 px glow) and some had no zero path at
         * all; this table is the single place that answers "is this effect a no-op right now?". */
        if (isNeutral(effect, type))
        {
            return input;
        }

        GlFramebuffer result = applyRaw(layer, effect, input, context, space);

        if (result == input || strength >= 0.999F)
        {
            return result;
        }

        GlFramebuffer blended = PassRenderer.draw(result, input, null, CompLayer.BLEND_NORMAL,
            Math.min(1F, strength), 0F, 0F, 0xFFFFFF);

        FboPool.release(result);

        return blended;
    }

    /**
     * True when this effect would return its input unchanged at the current parameter values.
     *
     * <p>Deliberately per-effect: "zero" is not the same number for a blur radius, a glow opacity
     * and a saturation. Only the cases where zero is unambiguously the identity are listed; the
     * generic {@link #STRENGTH} covers everything else (generators, kaleidoscope, colour grades).</p>
     */
    public static boolean isNeutral(CompEffect effect, String type)
    {
        if (effect == null)
        {
            return true;
        }

        if (effect.valueF(STRENGTH, 1F) <= 0.0001F)
        {
            return true;
        }

        float eps = 0.0001F;

        return switch (type == null ? "" : type)
        {
            case "blur" -> effect.valueF("radius", 10F) <= 0.5F;
            case "box_blur" -> effect.valueF("radius", 8F) <= 0.5F;
            case "directional_blur" -> effect.valueF("radius", 20F) <= 0.5F;
            case "radial_blur" -> effect.valueF("radius", 0.15F) <= eps;
            case "sharpen" -> effect.valueF("amount", 1F) <= eps;
            case "cc_cross_blur", "cc_lens_blur", "channel_blur", "compound_blur",
                 "bilateral_blur", "smart_blur" ->
                effect.valueF("radius", 12F) <= 0.5F || effect.valueF("amount", 1F) <= eps;
            case "cc_radial_fast_blur" ->
                effect.valueF("radius", 0.3F) <= eps || effect.valueF("amount", 1F) <= eps;
            case "unsharp_mask", "camera_shake_deblur" ->
                effect.valueF("amount", 1F) <= eps || effect.valueF("radius", 3F) <= 0.5F;
            case "displacement", "bulge", "pinch", "twist", "turbulent_displace" ->
                effect.valueF("amount", 48F) <= eps;
            case "wave" -> effect.valueF("amplitude", effect.valueF("amount", 24F)) <= eps;
            case "offset" -> effect.valueF("amount", 1F) <= eps
                || (Math.abs(effect.valueF("offset_x", 0F)) <= eps && Math.abs(effect.valueF("offset_y", 0F)) <= eps);
            case "polar_coordinates" -> effect.valueF("amount", 1F) <= eps;
            case "spherize", "optics_compensation", "cc_lens", "cc_slant", "magnify",
                 "cc_ripple_pulse", "cc_bend", "rolling_shutter_repair" ->
                Math.abs(effect.valueF("amount", 1F)) <= eps;
            case "cc_split" ->
                Math.abs(effect.valueF("gap", 0.1F)) <= eps
                    && Math.abs(effect.valueF("offset_x", 0F)) <= eps
                    && Math.abs(effect.valueF("offset_y", 0F)) <= eps;
            case "transform" ->
                Math.abs(effect.valueF("offset_x", 0F)) <= eps
                    && Math.abs(effect.valueF("offset_y", 0F)) <= eps
                    && Math.abs(effect.valueF("scale_x", 1F) - 1F) <= eps
                    && Math.abs(effect.valueF("scale_y", 1F) - 1F) <= eps
                    && Math.abs(effect.valueF("angle", 0F)) <= eps;
            case "glow" -> effect.valueF("radius", 40F) <= 0.5F
                || effect.valueF("opacity", 1F) <= eps || effect.valueF("intensity", 1F) <= eps;
            case "outer_glow" -> effect.valueF("radius", 24F) <= 0.5F
                || effect.valueF("opacity", 1F) <= eps || effect.valueF("intensity", 1F) <= eps;
            case "drop_shadow" -> effect.valueF("opacity", 0.65F) <= eps;
            case "stroke" -> effect.valueF("width", 3F) <= eps || effect.valueF("opacity", 1F) <= eps;
            case "rgb_split" -> effect.valueF("amount", 14F) <= eps;
            /* A wipe at progress 0 is fully hidden in the shader and progress 1 is the untouched
             * layer, so the identity value is 1, not 0 (0 produced a one-frame pop at the start
             * of every keyed wipe). The fallback matches the catalogue's default of 0.5. */
            case "linear_wipe", "radial_wipe", "dissolve" -> effect.valueF("progress", 0.5F) >= 1F - eps;
            case "grid_repeat", "random_repeat" -> effect.valueF("opacity", 1F) <= eps;
            case "depth_fog", "depth_dof" -> effect.valueF("amount", 0.5F) <= eps;
            case "light_sweep" -> effect.valueF("opacity", 0.8F) <= eps;
            case "vhs" -> effect.valueF("amount", 0.5F) <= eps;
            /* Colour effects: identity is "all knobs at their neutral value", not one zero. */
            case "brightness_contrast" ->
                Math.abs(effect.valueF("brightness", 0F)) <= eps
                    && Math.abs(effect.valueF("contrast", 0F)) <= eps;
            case "hue_saturation" ->
                Math.abs(effect.valueF("hue", 0F)) <= eps
                    && Math.abs(effect.valueF("saturation", 0F)) <= eps
                    && Math.abs(effect.valueF("lightness", 0F)) <= eps;
            case "exposure" ->
                Math.abs(effect.valueF("exposure", 0F)) <= eps
                    && Math.abs(effect.valueF("gamma", 1F) - 1F) <= eps;
            case "tint" -> effect.valueF("amount", 0.5F) <= eps;
            case "levels" ->
                effect.valueF("black", 0F) <= eps
                    && effect.valueF("white", 1F) >= 1F - eps
                    && Math.abs(effect.valueF("gamma", 1F) - 1F) <= eps;
            case "curves" ->
                effect.valueF("amount", 1F) <= eps
                    || (Math.abs(effect.valueF("shadow", 0F)) <= eps
                        && Math.abs(effect.valueF("midtone", 0F)) <= eps
                        && Math.abs(effect.valueF("highlight", 0F)) <= eps);
            case "invert" -> effect.valueF("amount", 1F) <= eps;
            case "vignette" -> effect.valueF("amount", 0.45F) <= eps;
            case "noise" -> effect.valueF("amount", 0.2F) <= eps;
            case "grain" -> effect.valueF("amount", 0.12F) <= eps;
            case "shake" -> effect.valueF("amplitude", 18F) <= eps;
            case "matte_choker" ->
                Math.abs(effect.valueF("choke", 2F)) <= eps
                    && Math.abs(effect.valueF("softness", 2F)) <= eps;
            case "colorama" -> effect.valueF("amount", 1F) <= eps;
            case "color_grade" ->
                Math.abs(effect.valueF("exposure", 0F)) <= eps
                    && Math.abs(effect.valueF("contrast", 0F)) <= eps
                    && Math.abs(effect.valueF("saturation", 1F) - 1F) <= eps
                    && Math.abs(effect.valueF("vibrance", 0F)) <= eps
                    && Math.abs(effect.valueF("temperature", 0F)) <= eps
                    && Math.abs(effect.valueF("tint", 0F)) <= eps
                    && Math.abs(effect.valueF("hue", 0F)) <= eps
                    && Math.abs(effect.valueF("fade", 0F)) <= eps
                    && Math.abs(effect.valueF("curve_black", 0F)) <= eps
                    && Math.abs(effect.valueF("curve_shadow", 0F)) <= eps
                    && Math.abs(effect.valueF("curve_mid", 0F)) <= eps
                    && Math.abs(effect.valueF("curve_highlight", 0F)) <= eps
                    && Math.abs(effect.valueF("curve_white", 0F)) <= eps
                    && effect.color("lift", 0xFF808080) == 0xFF808080
                    && effect.color("gamma", 0xFF808080) == 0xFF808080
                    && effect.color("gain", 0xFF808080) == 0xFF808080;
            case "block_shatter" -> effect.valueF("progress", 0.35F) <= eps;
            case "mosaic" -> effect.valueF("size", 16F) <= 1F;
            case "find_edges" -> effect.valueF("amount", 1F) <= eps;
            case "scanlines" -> effect.valueF("opacity", 0.55F) <= eps;
            case "glitch" -> effect.valueF("amount", 18F) <= eps;
            case "halftone" -> effect.valueF("strength", 0.5F) <= eps;
            case "emboss" -> effect.valueF("amount", 1F) <= eps;
            case "mirror" -> effect.valueF("opacity", 1F) <= eps;
            default -> AmEffectLibrary.isNeutral(effect);
        };
    }

    private static GlFramebuffer applyRaw(CompLayer layer, CompEffect effect, GlFramebuffer input, CompRenderContext context, EffectSpace space)
    {
        String type = effect.type == null ? "" : effect.type;
        float frame = context.frame;

        switch (type)
        {
            case "blur":
                return blur(input, effect.valueF("radius", 10F), effect.valueF("opacity", 1F), false, space);
            case "box_blur":
                return blur(input, effect.valueF("radius", 8F), effect.valueF("opacity", 1F), true, space);
            case "radial_blur":
                return radialBlur(input, effect, space);
            case "directional_blur":
                return directionalBlur(input, effect.valueF("radius", 20F), effect.valueF("angle", 0F), effect.valueF("opacity", 1F), space);
            case "sharpen":
                return sharpen(input, effect, space);
            case "cc_cross_blur":
                return aeBlur(input, effect, 0, space);
            case "cc_radial_fast_blur":
                return aeBlur(input, effect, 1, space);
            case "cc_lens_blur":
                return aeBlur(input, effect, 2, space);
            case "channel_blur":
                return aeBlur(input, effect, 3, space);
            case "compound_blur":
                return aeBlur(input, effect, 4, space);
            case "bilateral_blur":
            case "smart_blur":
                return aeBlur(input, effect, 5, space);
            case "unsharp_mask":
            case "camera_shake_deblur":
                return aeBlur(input, effect, 6, space);
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
            case "colorama":
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
                return key(input, effect, type, space);
            case "matte_choker":
                return key(input, effect, "matte_choker", space);
            case "rgb_split":
                return rgbSplit(input, effect, space);
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
            case "turbulent_displace":
                return distort(input, effect, context, type, space);
            case "offset":
                return aeDistort(input, effect, 0, space);
            case "polar_coordinates":
                return aeDistort(input, effect, 1, space);
            case "spherize":
                return aeDistort(input, effect, 2, space);
            case "optics_compensation":
                return aeDistort(input, effect, 3, space);
            case "cc_lens":
                return aeDistort(input, effect, 4, space);
            case "cc_slant":
                return aeDistort(input, effect, 5, space);
            case "magnify":
                return aeDistort(input, effect, 6, space);
            case "cc_ripple_pulse":
                return aeDistort(input, effect, 7, space);
            case "cc_bend":
                return aeDistort(input, effect, 8, space);
            case "cc_split":
                return aeDistort(input, effect, 9, space);
            case "rolling_shutter_repair":
                return aeDistort(input, effect, 10, space);
            case "transform":
                return aeDistort(input, effect, 11, space);
            case "light_sweep":
                return pixel(input, effect, context, space);
            case "grid_repeat":
            case "random_repeat":
                return replicate(input, effect, type, space);
            case "linear_wipe":
            case "radial_wipe":
            case "dissolve":
                return transition(input, effect, context, type, space);
            case "glow":
                return glow(input, effect, false, space);
            case "outer_glow":
                return glow(input, effect, true, space);
            case "drop_shadow":
                return dropShadow(input, effect, space);
            case "stroke":
                return stroke(input, effect, space);
            case "depth_matte":
            case "depth_fog":
            case "depth_dof":
                return depth(input, effect, context, type, space);
            default:
                if (isReplication(type))
                {
                    /* Reference repeat entries live in AmEffectLibrary but are replicators, not
                     * data-driven fragment bodies. */
                    return replicate(input, effect, type, space);
                }

                if (MeshRenderer.supports(type))
                {
                    return MeshRenderer.apply(layer, effect, input, context);
                }

                return AmEffectLibrary.supports(type)
                    ? AmEffectRenderer.apply(layer, effect, input, context, space)
                    : input;
        }
    }

    /* ------------------------------------------------------------------ pixel units */

    /**
     * The reference pixel grid every shader computes in: <b>composition</b> pixels.
     *
     * <p>{@link EffectSpace} expresses layer bounds in composition pixels, but the shaders were fed
     * the framebuffer size. A preview renders the composition at a proxy scale (down to 0.2×), so
     * every pixel-unit parameter — blur radius, offset, mosaic cell, halftone dots, local
     * resampling — came out 1/scale times too large in the preview and snapped back when the panel
     * was resized, and never matched the 1:1 export. The layer basis and the shader basis have to
     * be the same grid.</p>
     */
    private static int refWidth(EffectSpace space, GlFramebuffer input)
    {
        return space == null ? Math.max(1, input.width) : Math.max(1, space.compWidth);
    }

    private static int refHeight(EffectSpace space, GlFramebuffer input)
    {
        return space == null ? Math.max(1, input.height) : Math.max(1, space.compHeight);
    }

    private static void setPixelUnits(GlslProgram program, EffectSpace space, GlFramebuffer input)
    {
        program.set("Texel", 1F / refWidth(space, input), 1F / refHeight(space, input));
        program.set("Size", refWidth(space, input), refHeight(space, input));
    }

    /**
     * Framebuffer pixels per composition pixel for this pass.
     *
     * <p>Effect parameters are authored in composition pixels while a preview renders into a
     * smaller proxy target. Any pass that hands a pixel-authored value to a consumer measured in
     * framebuffer pixels (an FBO blur radius, a raw GL quad offset, …) has to convert through this
     * factor, or the effect is 1/scale times too large in the preview and snaps back on export.</p>
     */
    static float proxyScale(EffectSpace space, GlFramebuffer input)
    {
        return proxyScale(input.width, refWidth(space, input));
    }

    /** Pure form of {@link #proxyScale(EffectSpace, GlFramebuffer)} for unit tests. */
    static float proxyScale(int targetWidth, int referenceWidth)
    {
        if (referenceWidth <= 0)
        {
            /* No composition reference: treat the target as the reference (1:1) instead of
             * producing an infinite factor that would zero out every pixel parameter. */
            return 1F;
        }

        return targetWidth / (float) referenceWidth;
    }

    /**
     * Converts a layer-local centre (0..1 inside the layer) into the composition UV the
     * AE-style programs sample with, including the FBO's bottom-up V axis.
     */
    private static org.joml.Vector2f layerCenter(EffectSpace space, CompEffect effect)
    {
        float cx = effect.valueF("center_x", 0.5F);
        float cy = effect.valueF("center_y", 0.5F);

        return space == null ? new org.joml.Vector2f(cx, 1F - cy) : space.localToCompUv(cx, cy);
    }

    private static GlFramebuffer blur(GlFramebuffer input, float radius, float mix, boolean box, EffectSpace space)
    {
        GlFramebuffer blurred = PassRenderer.blur(input, radius, box,
            1F / refWidth(space, input), 1F / refHeight(space, input));

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

        /* A radial zoom is a resample: straight alpha would drag black from the transparent
         * surround into the streaks. Same premultiplied round-trip as the AE family. */
        GlFramebuffer premultiplied = PassRenderer.premultiply(input);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, premultiplied);
        program.set("Center", center.x, center.y);
        program.set("Amount", effect.valueF("radius", 0.15F));
        program.drawFullscreen();

        if (premultiplied != input)
        {
            FboPool.release(premultiplied);
        }

        GlFramebuffer straightened = PassRenderer.unpremultiply(output);

        if (straightened != output)
        {
            FboPool.release(output);
        }

        return straightened;
    }

    private static GlFramebuffer sharpen(GlFramebuffer input, CompEffect effect, EffectSpace space)
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
        setPixelUnits(program, space, input);
        program.set("Amount", Math.max(0F, effect.valueF("amount", 1F)));
        program.drawFullscreen();

        return output;
    }

    /**
     * The AE blur family. The pass works on premultiplied pixels and converts back once, so the
     * symmetric kernels do not pull black through an alpha edge.
     */
    private static GlFramebuffer aeBlur(GlFramebuffer input, CompEffect effect, int mode, EffectSpace space)
    {
        GlslProgram program = CompShaders.aeBlur;

        if (program == null)
        {
            return input;
        }

        GlFramebuffer premultiplied = PassRenderer.premultiply(input);
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, premultiplied);
        setPixelUnits(program, space, input);
        program.set("Mode", mode);

        float radius = effect.valueF("radius", mode == 1 ? 0.3F : 12F);

        program.set("Radius", Math.max(0F, radius));
        program.set("Amount", effect.valueF("amount", 1F));
        program.set("Threshold", effect.valueF("threshold", 0.2F));
        program.set("MapSoftness", effect.valueF("softness", 0.5F));
        program.set("RadiusG", Math.max(0F, effect.valueF("radius_g", radius)));
        program.set("RadiusB", Math.max(0F, effect.valueF("radius_b", radius)));

        /* The AE programs sample composition UV; the UI authors the centre in layer space. The raw
         * value only worked while the layer happened to fill the composition (and it was mirrored in
         * Y), so 球面化/径向快速模糊 looked dead as soon as the layer was moved off-centre. */
        org.joml.Vector2f center = layerCenter(space, effect);

        program.set("Center", center.x, center.y);
        program.drawFullscreen();

        if (premultiplied != input)
        {
            FboPool.release(premultiplied);
        }

        GlFramebuffer straightened = PassRenderer.unpremultiply(output);

        if (straightened != output)
        {
            FboPool.release(output);
        }

        return straightened;
    }

    /**
     * The AE geometric distortion family. Every mode is a single-input resample; the composition
     * bakes it into the layer raster exactly like the existing distort shader.
     */
    private static GlFramebuffer aeDistort(GlFramebuffer input, CompEffect effect, int mode, EffectSpace space)
    {
        GlslProgram program = CompShaders.aeDistort;

        if (program == null)
        {
            return input;
        }

        /* A geometric warp is a resample: sampling straight alpha at a transparent edge bleeds
         * black into the falloff. Round-trip through premultiplied alpha, exactly like aeBlur. */
        GlFramebuffer premultiplied = PassRenderer.premultiply(input);
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, premultiplied);
        setPixelUnits(program, space, input);
        program.set("Mode", mode);
        program.set("Amount", effect.valueF("amount", 1F));

        /* Same layer-space centre conversion as aeBlur: the shader works in composition UV. */
        org.joml.Vector2f center = layerCenter(space, effect);

        program.set("Center", center.x, center.y);
        program.set("Radius", Math.max(0F, effect.valueF("radius", 300F)));
        program.set("Angle", effect.valueF("angle", 0F));
        program.set("Offset", effect.valueF("offset_x", 0F), effect.valueF("offset_y", 0F));
        program.set("Scale", Math.max(0.0001F, effect.valueF("scale_x", 1F)),
            Math.max(0.0001F, effect.valueF("scale_y", 1F)));
        program.set("Edge", effect.valueI("edge", 0));
        program.set("Param", effect.valueF("direction",
            effect.valueF("gap", effect.valueF("phase", 0F))));
        program.drawFullscreen();

        if (premultiplied != input)
        {
            FboPool.release(premultiplied);
        }

        GlFramebuffer straightened = PassRenderer.unpremultiply(output);

        if (straightened != output)
        {
            FboPool.release(output);
        }

        return straightened;
    }

    private static GlFramebuffer directionalBlur(GlFramebuffer input, float radius, float angle, float mix, EffectSpace space)
    {
        GlFramebuffer output = FboPool.acquire(input.width, input.height);
        GlslProgram program = CompShaders.blur;

        if (program == null || radius <= 0.5F)
        {
            FboPool.release(output);
            return input;
        }

        /* The BLUR program is shared with PassRenderer.blur, which sets Mode. Leaving it stale let
         * a box_blur elsewhere in the composition turn this pass into a box kernel. */
        GlFramebuffer premultiplied = PassRenderer.premultiply(input);
        int w = refWidth(space, input);
        int h = refHeight(space, input);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, premultiplied);
        program.set("Texel", 1F / w, 1F / h);
        program.set("Mode", 0);
        program.set("Direction", (float) Math.cos(Math.toRadians(angle)), (float) Math.sin(Math.toRadians(angle)));
        program.set("Radius", Math.min(200F, radius));
        program.drawFullscreen();

        if (premultiplied != input)
        {
            FboPool.release(premultiplied);
        }

        GlFramebuffer straightened = PassRenderer.unpremultiply(output);

        if (straightened != output)
        {
            FboPool.release(output);
        }

        if (mix < 0.999F)
        {
            GlFramebuffer blended = PassRenderer.draw(straightened, input, null, CompLayer.BLEND_NORMAL, mix, 0F, 0F, 0xFFFFFF);

            FboPool.release(straightened);
            return blended;
        }

        return straightened;
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
        program.set("Colorama", 0F);
        setColor(program, "ColoramaA", 0xFF101A3A);
        setColor(program, "ColoramaB", 0xFF2E86AB);
        setColor(program, "ColoramaC", 0xFFFFE2A8);

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

            case "colorama":
                program.set("Colorama", effect.valueF("amount", 1F));
                setColor(program, "ColoramaA", effect.color("shadow", 0xFF101A3A));
                setColor(program, "ColoramaB", effect.color("mid", 0xFF2E86AB));
                setColor(program, "ColoramaC", effect.color("highlight", 0xFFFFE2A8));
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
            case "light_sweep" ->
            {
                amount = effect.valueF("opacity", 0.8F);
                p1 = effect.valueF("angle", 45F);
                p2 = Math.max(0.001F, effect.valueF("width", 0.22F));
                p3 = effect.valueF("progress", 0.5F);
                p4 = Math.max(0.001F, effect.valueF("softness", 0.12F));
                dotColor = effect.color("color", 0xFFFFFFFF);
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
            case "vhs" -> 8;
            case "light_sweep" -> 9;
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
        setPixelUnits(program, space, input);
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

    private static GlFramebuffer key(GlFramebuffer input, CompEffect effect, String type, EffectSpace space)
    {
        GlslProgram program = CompShaders.key;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        boolean choker = "matte_choker".equals(type);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        setPixelUnits(program, space, input);
        program.set("Mode", "chroma_key".equals(type) ? 0 : "luma_key".equals(type) ? 1 : 2);
        setColor(program, "KeyColor", effect.color("key", 0xFF00FF00));
        program.set("Tolerance", effect.valueF("tolerance", 0.2F));
        /* Softness is a 0..1 distance falloff for the keyers but a pixel radius for the choker. */
        program.set("Softness", Math.max(0.001F, effect.valueF("softness", choker ? 2F : 0.08F)));
        program.set("Spill", effect.valueF("spill", 0.4F));
        program.set("Threshold", effect.valueF("threshold", 0.3F));
        program.set("Invert", effect.valueF("invert", 0F));
        program.set("Choke", choker ? effect.valueF("choke", 2F) : 0F);

        /* Screen-matte clip. Defaults are the identity (0 / 1), so projects saved before the
         * sliders existed behave exactly as before; raising them is how a residual green veil or
         * heavy grain in the matte gets finished off. */
        float clipBlack = Math.max(0F, effect.valueF("clip_black", 0F));

        program.set("ClipBlack", clipBlack);
        program.set("ClipWhite", Math.max(clipBlack + 0.001F, Math.min(1F, effect.valueF("clip_white", 1F))));
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
        setPixelUnits(program, space, input);
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

        int mode = type.equals("displacement") ? 0
            : type.equals("bulge") ? 1
            : type.equals("pinch") ? 2
            : type.equals("twist") ? 3
            : type.equals("wave") ? 4
            : 5;

        /* A geometric warp resamples: straight-alpha edges would bleed black. Same premultiplied
         * round-trip as aeDistort. Done BEFORE output.begin(): the premultiply pass binds its own
         * target, and drawing after it would land in that buffer instead of `output`. */
        GlFramebuffer sampler = PassRenderer.premultiply(input);

        output.begin();
        GlStateManager._disableBlend();
        program.use();

        PassRenderer.bind(program, "Source", 0, sampler);
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
        setPixelUnits(program, space, input);
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
        /* displacement calls its noise frequency "scale", turbulent_displace calls it "size". */
        program.set("Scale", effect.valueF("scale", effect.valueF("size", 160F)));
        program.set("Octaves", Math.max(1, Math.min(8, Math.round(effect.valueF("complexity", 4F)))));
        program.set("Seed", effect.valueF("seed", 0F));
        program.drawFullscreen();

        if (sampler != input)
        {
            FboPool.release(sampler);
        }

        GlFramebuffer straightened = PassRenderer.unpremultiply(output);

        if (straightened != output)
        {
            FboPool.release(output);
        }

        return straightened;
    }

    /**
     * Grid / random repetition. The shader works in layer space, so the pattern is part of the
     * layer and follows every transform, effect and 3D projection.
     */
    private static GlFramebuffer repeat(GlFramebuffer input, CompEffect effect, String type, EffectSpace space)    {
        GlslProgram program = CompShaders.repeat;
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        if (program == null)
        {
            FboPool.release(output);
            return input;
        }

        boolean random = "random_repeat".equals(type);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        space.bind(program);
        setPixelUnits(program, space, input);
        program.set("Mode", random ? 1 : 0);
        program.set("Grid",
            Math.max(1F, effect.valueF("columns", random ? 5F : 4F)),
            Math.max(1F, effect.valueF("rows", random ? 4F : 3F)));
        program.set("Scale", Math.max(0.01F, effect.valueF("scale", random ? 0.75F : 0.9F)));
        program.set("Gap", Math.max(0F, Math.min(0.95F, effect.valueF("gap", 0.04F))));
        program.set("Rotation", effect.valueF("rotation", random ? 45F : 0F));
        program.set("Randomness", Math.max(0F, Math.min(1F, effect.valueF("randomness", 0.4F))));
        program.set("Opacity", Math.max(0F, Math.min(1F, effect.valueF("opacity", 1F))));
        program.set("Seed", effect.valueF("seed", 7F));
        program.set("Offset", effect.valueF("offset_x", 0F), effect.valueF("offset_y", 0F));
        program.drawFullscreen();

        return output;
    }

    /**
     * Effects that replicate the layer across the composition instead of warping it in place.
     *
     * <p>Alight Motion's "repeat" category is not a pixel shader (its definitions carry no shader):
     * it draws the layer N times. Our first implementation squeezed the copies inside the layer
     * rectangle, which is why a repeated layer looked like a masked tile instead of an infinite
     * pattern. These types therefore run in composition space; see
     * {@link #isCompositionSpace(String)}.</p>
     */
    static boolean isReplication(String type)
    {
        return "grid_repeat".equals(type) || "random_repeat".equals(type)
            || "am_grid_repeat".equals(type) || "am_line_repeat".equals(type)
            || "am_repeat".equals(type) || "am_repeat_radial".equals(type)
            || "am_repeat_scatter".equals(type);
    }

    /**
     * True when the effect has to see the whole composition instead of the layer's own rectangle.
     *
     * <p>Composition-space effects are the ones that can write pixels outside the layer: the
     * layer-local bake rasterizes exactly the layer box, so they can never be "infinite" there.
     * The compositor runs them after the projection (3D layers) or directly on the composition
     * target (2D layers), where the input already covers the frame.</p>
     */
    public static boolean isCompositionSpace(String type)
    {
        return isReplication(type);
    }

    /**
     * Draws the layer several times across the composition (repeat / tile family).
     *
     * <p>The pass is a plain full-screen shader over the composition-sized layer image: for every
     * output pixel and every copy it samples the source at the inverse of that copy's transform.
     * Copy placement and size come from the effect space ({@link EffectSpace#centerUv()},
     * {@code sizeU/sizeV}) so a copy keeps the layer's own on-screen size and no copy is clipped
     * by the layer rectangle — only the composition bounds remain.</p>
     */
    private static GlFramebuffer replicate(GlFramebuffer input, CompEffect effect, String type, EffectSpace space)
    {
        GlslProgram program = CompShaders.replicate;

        if (program == null)
        {
            return input;
        }

        boolean line = "am_line_repeat".equals(type);
        boolean scatter = "random_repeat".equals(type);
        boolean repeat = "am_repeat".equals(type);
        boolean ring = "am_repeat_radial".equals(type);
        boolean scatterRing = "am_repeat_scatter".equals(type);
        float columns = 1F;
        float rows = 1F;
        float gap = 0F;
        float scale = 1F;
        float rotation = 0F;
        float randomness = 0F;
        float opacity = 1F;
        float seed = 0F;
        float offsetX = 0F;
        float offsetY = 0F;
        int vertical = 0;
        int mode = line ? 1 : scatter ? 2 : 0;
        float stepX = 0F;
        float stepY = 0F;
        float radiusX = 0F;
        float radiusY = 0F;
        float startAngle = 0F;
        float sweep = 0F;
        float baseScale = 1F;
        int compW = refWidth(space, input);
        int compH = refHeight(space, input);

        if (repeat)
        {
            /* AM repeat: `count` copies marching along `offset`, each stepped by `angle` and
             * multiplied by `scale`. Offsets are authored in composition pixels. */
            float count = clamp(effect.valueF("count", 5F), 1F, 32F);

            columns = count;
            stepX = effect.valueF("offset_x", 48F) / Math.max(1F, compW);
            stepY = -effect.valueF("offset_y", 0F) / Math.max(1F, compH);
            rotation = (float) Math.toRadians(effect.valueF("angle", 0F));
            scale = clamp(effect.valueF("scale", 1F), 0.01F, 10F);
            opacity = clamp(effect.valueF("alpha", 1F), 0F, 1F);
        }
        else if (ring)
        {
            /* AM repeat.radial: `count` copies on an arc of `radius`, from `startAngle` over
             * `sweep`, each stepped by `angle` / `baseScale` × `scale`. */
            float count = clamp(effect.valueF("count", 8F), 1F, 32F);
            float radius = Math.max(0F, effect.valueF("radius", 300F));

            columns = count;
            radiusX = radius / Math.max(1F, compW);
            radiusY = radius / Math.max(1F, compH);
            startAngle = (float) Math.toRadians(effect.valueF("orientation", 0F)
                + effect.valueF("start_angle", 0F));
            sweep = (float) Math.toRadians(effect.valueF("sweep", 360F));
            rotation = (float) Math.toRadians(effect.valueF("angle", 0F));
            scale = clamp(effect.valueF("scale", 1F), 0.01F, 10F);
            baseScale = clamp(effect.valueF("base_scale", 1F), 0.01F, 10F);
            opacity = clamp(effect.valueF("alpha", 1F), 0F, 1F);
            offsetX = effect.valueF("offset_x", 0F) / Math.max(1F, compW);
            offsetY = -effect.valueF("offset_y", 0F) / Math.max(1F, compH);
        }
        else if (scatterRing)
        {
            /* AM repeat.scatter: `count` copies on random points of a `radius` disk. */
            float count = clamp(effect.valueF("count", 12F), 1F, 32F);
            float radius = Math.max(0F, effect.valueF("radius", 300F));

            columns = count;
            radiusX = radius / Math.max(1F, compW);
            radiusY = radius / Math.max(1F, compH);
            rotation = (float) Math.toRadians(effect.valueF("angle", 0F));
            scale = clamp(effect.valueF("scale", 1F), 0.01F, 10F);
            opacity = clamp(effect.valueF("alpha", 1F), 0F, 1F);
            seed = effect.valueF("scatter_seed", 0F) + effect.valueF("evolution", 0F) * 0.37F;
            offsetX = effect.valueF("offset_x", 0F) / Math.max(1F, compW);
            offsetY = -effect.valueF("offset_y", 0F) / Math.max(1F, compH);
        }
        else if (line)
        {
            float count = clamp(effect.valueF("count", 4F), 1F, 8F);
            float duty = clamp(effect.valueF("duty", 0.7F), 0.05F, 1F);

            columns = count;
            scale = duty;
            opacity = clamp(effect.valueF("amount", 1F), 0F, 1F);
            vertical = effect.valueI("vertical", 1);
        }
        else if (scatter)
        {
            columns = clamp(effect.valueF("columns", 5F), 1F, 8F);
            rows = clamp(effect.valueF("rows", 4F), 1F, 8F);
            gap = clamp(effect.valueF("gap", 0.05F), 0F, 0.9F);
            scale = Math.max(0.02F, effect.valueF("scale", 0.75F));
            rotation = (float) Math.toRadians(effect.valueF("rotation", 45F));
            randomness = clamp(effect.valueF("randomness", 0.4F), 0F, 1F);
            opacity = clamp(effect.valueF("opacity", 1F), 0F, 1F);
            seed = effect.valueF("seed", 7F);
        }
        else if ("am_grid_repeat".equals(type))
        {
            columns = clamp(effect.valueF("columns", 2F), 1F, 8F);
            rows = clamp(effect.valueF("rows", 2F), 1F, 8F);
            gap = clamp(effect.valueF("gap", 0.05F), 0F, 0.9F);
            opacity = clamp(effect.valueF("amount", 1F), 0F, 1F);
        }
        else
        {
            columns = clamp(effect.valueF("columns", 4F), 1F, 8F);
            rows = clamp(effect.valueF("rows", 3F), 1F, 8F);
            gap = clamp(effect.valueF("gap", 0.04F), 0F, 0.9F);
            scale = Math.max(0.02F, effect.valueF("scale", 0.9F));
            rotation = (float) Math.toRadians(effect.valueF("rotation", 0F));
            /* The author-facing offsets are fractions of the layer box (down = +y). */
            offsetX = effect.valueF("offset_x", 0F) * space.sizeU;
            offsetY = -effect.valueF("offset_y", 0F) * space.sizeV;
        }

        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Size", compW, compH);
        program.set("Mode", line ? 1 : scatter ? 2 : repeat ? 3 : ring ? 4 : scatterRing ? 5 : 0);
        program.set("Grid", columns, rows);
        program.set("Gap", gap);
        program.set("Scale", Math.max(0.02F, scale));
        program.set("Rotation", rotation);
        program.set("Randomness", randomness);
        program.set("Seed", seed);
        program.set("Opacity", opacity);
        program.set("Offset", offsetX, offsetY);
        program.set("Vertical", vertical);
        program.set("Center", space.centerU, space.centerV);
        program.set("Step", stepX, stepY);
        program.set("RadiusUv", radiusX, radiusY);
        program.set("StartAngle", startAngle);
        program.set("Sweep", sweep);
        program.set("BaseScale", baseScale);
        program.drawFullscreen();

        return output;
    }

    private static float clamp(float value, float min, float max)
    {
        return Math.max(min, Math.min(max, value));
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

    /**
     * Inner shadow / inner glow / satin / bevel primitive.
     *
     * <p>Style distances and blur radii are authored in composition pixels; the optional context
     * provides the composition size so a proxy preview renders them at the same visual size
     * (previously both were measured in target pixels and doubled at scale 0.5).</p>
     */
    public static GlFramebuffer inner(GlFramebuffer input, int color, float opacity, float distance, float angle, float radius, CompRenderContext context)
    {
        int referenceWidth = context == null ? input.width : Math.max(1, context.width);
        int referenceHeight = context == null ? input.height : Math.max(1, context.height);
        float scale = proxyScale(input.width, referenceWidth);
        GlFramebuffer blurred = PassRenderer.blur(input, Math.max(0.1F, radius * scale));
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
        float dx = (float) Math.cos(radians) * distance / referenceWidth;
        float dy = (float) Math.sin(radians) * distance / referenceHeight;

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

    private static GlFramebuffer glow(GlFramebuffer input, CompEffect effect, boolean outer, EffectSpace space)
    {
        float radius = effect.valueF("radius", outer ? 24F : 40F);
        GlFramebuffer blurred = PassRenderer.blur(input, radius, false,
            1F / refWidth(space, input), 1F / refHeight(space, input));

        if (blurred == input)
        {
            return input;
        }

        int color = effect.color("color", outer ? 0xFF55AAFF : 0xFFFFFFFF);
        GlFramebuffer tinted = PassRenderer.copy(blurred, color, effect.valueF("opacity", 1F));
        float intensity = Math.max(0F, effect.valueF("intensity", 1F));

        /* A glow is added on top of the layer; an OUTER glow has to live behind it, otherwise it
         * washes colour into the layer's own pixels and is indistinguishable from 辉光. */
        GlFramebuffer output = outer
            ? PassRenderer.draw(input, tinted, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF)
            : PassRenderer.draw(tinted, input, null, CompLayer.BLEND_ADD, intensity, 0F, 0F, 0xFFFFFF);

        FboPool.release(blurred);
        FboPool.release(tinted);

        return output;
    }

    private static GlFramebuffer dropShadow(GlFramebuffer input, CompEffect effect, EffectSpace space)
    {
        float distance = effect.valueF("distance", 12F);
        float angle = (float) Math.toRadians(effect.valueF("angle", 45F));
        GlFramebuffer blurred = PassRenderer.blur(input, effect.valueF("softness", 6F), false,
            1F / refWidth(space, input), 1F / refHeight(space, input));
        GlFramebuffer shadow = PassRenderer.copy(blurred, effect.color("color", 0xFF000000), effect.valueF("opacity", 0.65F));
        float dx = (float) Math.cos(angle) * distance;
        float dy = (float) Math.sin(angle) * distance;
        /* PassRenderer offsets are texture-space (V grows upwards); screen +Y is down, so the
         * vertical component has to be negated or a 90° shadow lands above the layer. The offset is
         * in composition pixels and PassRenderer normalises by the reference size, so a proxy
         * preview keeps the same shadow distance instead of doubling it. */
        GlFramebuffer base = PassRenderer.draw(shadow, null, null, CompLayer.BLEND_NORMAL, 1F, dx, -dy, 0xFFFFFF,
            refWidth(space, input), refHeight(space, input));
        GlFramebuffer output = PassRenderer.draw(input, base, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);

        FboPool.release(shadow);
        FboPool.release(base);

        if (blurred != input)
        {
            FboPool.release(blurred);
        }

        return output;
    }

    private static GlFramebuffer stroke(GlFramebuffer input, CompEffect effect, EffectSpace space)
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
        setPixelUnits(program, space, input);
        program.set("Width", effect.valueF("width", 3F));
        int color = effect.color("color", 0xFFFFFFFF);
        color = Colors.setA(color, Colors.getA(color) * effect.valueF("opacity", 1F));
        setColor(program, "Color", color);
        program.drawFullscreen();

        GlFramebuffer output = PassRenderer.draw(input, stroke, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);

        FboPool.release(stroke);
        return output;
    }

    private static GlFramebuffer depth(GlFramebuffer input, CompEffect effect, CompRenderContext context, String type, EffectSpace space)
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
        setPixelUnits(program, space, input);
        setColor(program, "Color", effect.color("color", 0xFFAFC7D9));
        program.drawFullscreen();

        return output;
    }


    private static GlFramebuffer rgbSplit(GlFramebuffer input, CompEffect effect, EffectSpace space)
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
        /* `amount` is authored in composition pixels; the shader multiplies it by Texel, so Texel
         * must be the composition texel, not the (possibly smaller) proxy target texel. */
        program.set("Texel", 1F / refWidth(space, input), 1F / refHeight(space, input));
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
        setPixelUnits(program, space, input);
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
