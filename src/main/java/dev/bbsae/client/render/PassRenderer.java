package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * The small set of full-screen passes the render graph composes. Keeping texture binding and
 * uniform setup in one place is what prevents a state from one effect leaking into the next.
 */
public final class PassRenderer
{
    private PassRenderer()
    {}

    public static GlFramebuffer copy(GlFramebuffer input, int tint, float opacity)
    {
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        drawInto(output, input, null, null, 0, opacity, 0F, 0F, tint, 0);

        return output;
    }

    /**
     * Draws a source texture into a fresh target. When {@code backdrop} is non-null the two are
     * blended using the requested mode; when it is null the source is copied with tint/opacity.
     */
    public static GlFramebuffer draw(GlFramebuffer source, GlFramebuffer backdrop, GlFramebuffer mask, int blendMode, float opacity, float offsetX, float offsetY, int tint)
    {
        return draw(source, backdrop, mask, blendMode, opacity, offsetX, offsetY, tint,
            Math.max(1, source.width), Math.max(1, source.height));
    }

    /**
     * Same draw, but {@code offsetX/offsetY} are expressed in <b>reference pixels</b> (composition
     * pixels) and normalised by the given reference size.
     *
     * <p>A preview renders the same composition into a smaller proxy target, while effect
     * parameters (shadow distance, chroma separation, …) are authored in composition pixels. The
     * offset must therefore be divided by the composition size, not by the proxy target size,
     * otherwise "offset 12 px" becomes 12 / proxyScale in the 960 px preview.</p>
     */
    public static GlFramebuffer draw(GlFramebuffer source, GlFramebuffer backdrop, GlFramebuffer mask, int blendMode,
                                     float opacity, float offsetX, float offsetY, int tint,
                                     int offsetReferenceWidth, int offsetReferenceHeight)
    {
        GlFramebuffer output = FboPool.acquire(source.width, source.height);

        drawInto(output, source, backdrop, mask, blendMode, opacity, offsetX, offsetY, tint, 0,
            offsetReferenceWidth, offsetReferenceHeight);

        return output;
    }

    public static GlFramebuffer mask(GlFramebuffer source, int maskTexture, int channel)
    {
        GlFramebuffer output = FboPool.acquire(source.width, source.height);
        GlslProgram program = CompShaders.composite;

        if (program == null)
        {
            /* Ownership: returning the input while also releasing the freshly acquired target was a
             * double-release (the caller cannot tell whether it still owns source, and the pool got
             * the same target back twice). Failure means "no result", so the caller keeps its input. */
            FboPool.release(output);

            return null;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        bind(program, "Backdrop", 0, null);
        bind(program, "Source", 1, source);
        bindRaw(program, "Mask", 2, maskTexture);
        program.set("UseBackdrop", 0);
        program.set("UseMask", 1);
        program.set("MaskChannel", channel);
        program.set("Blend", 0);
        program.set("Opacity", 1F);
        program.set("Offset", 0F, 0F);
        program.set("Tint", 1F, 1F, 1F, 1F);
        program.drawFullscreen();

        return output;
    }
    public static void drawInto(GlFramebuffer output, GlFramebuffer source, GlFramebuffer backdrop, GlFramebuffer mask, int blendMode, float opacity, float offsetX, float offsetY, int tint, int maskChannel)
    {
        drawInto(output, source, backdrop, mask, blendMode, opacity, offsetX, offsetY, tint, maskChannel,
            Math.max(1, source.width), Math.max(1, source.height));
    }

    public static void drawInto(GlFramebuffer output, GlFramebuffer source, GlFramebuffer backdrop, GlFramebuffer mask, int blendMode, float opacity, float offsetX, float offsetY, int tint, int maskChannel, int offsetReferenceWidth, int offsetReferenceHeight)
    {
        GlslProgram program = CompShaders.composite;

        if (program == null)
        {
            return;
        }

        output.begin();
        GlStateManager._disableScissorTest();
        GlStateManager._disableDepthTest();
        GlStateManager._disableCull();
        GlStateManager._disableBlend();

        program.use();

        bind(program, "Backdrop", 0, backdrop);
        bind(program, "Source", 1, source);
        bind(program, "Mask", 2, mask);

        program.set("UseBackdrop", backdrop == null ? 0 : 1);
        program.set("UseMask", mask == null ? 0 : 1);
        program.set("MaskChannel", maskChannel);
        program.set("Blend", blendMode);
        program.set("Opacity", opacity);
        program.set("Offset", offsetX / Math.max(1, offsetReferenceWidth), offsetY / Math.max(1, offsetReferenceHeight));
        program.set("Tint", ((tint >> 16) & 0xFF) / 255F, ((tint >> 8) & 0xFF) / 255F, (tint & 0xFF) / 255F, 1F);

        program.drawFullscreen();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
    }

    public static GlFramebuffer premultiply(GlFramebuffer input)
    {
        return alphaConvert(input, CompShaders.premultiply);
    }

    public static GlFramebuffer unpremultiply(GlFramebuffer input)
    {
        return alphaConvert(input, CompShaders.unpremultiply);
    }

    /**
     * Un-premultiply into an existing target.
     *
     * <p>Used by the viewport's source cache: an animated layer (footage, animated text) rebuilds
     * every frame, and allocating two framebuffers per frame kept the pool permanently at its
     * limit — visible as stutter while a video played. With a stable pair of targets the same
     * layer re-renders into the same textures.</p>
     */
    public static GlFramebuffer unpremultiplyInto(GlFramebuffer input, GlFramebuffer output)
    {
        GlslProgram program = CompShaders.unpremultiply;

        if (program == null || input == null || output == null || input == output)
        {
            return input;
        }

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        bind(program, "Source", 0, input);
        program.drawFullscreen();

        return output;
    }

    private static GlFramebuffer alphaConvert(GlFramebuffer input, GlslProgram program)
    {
        if (input == null)
        {
            return null;
        }

        if (program == null)
        {
            return input;
        }

        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        bind(program, "Source", 0, input);
        program.drawFullscreen();

        return output;
    }

    public static GlFramebuffer blur(GlFramebuffer input, float radius)
    {
        return blur(input, radius, false);
    }

    public static GlFramebuffer blur(GlFramebuffer input, float radius, boolean box)
    {
        return blur(input, radius, box, 1F / Math.max(1, input.width), 1F / Math.max(1, input.height));
    }

    /**
     * @param texelX/texelY one <b>composition</b> pixel expressed in UV. The composition is often
     *        rendered into a smaller proxy target, and a radius authored in composition pixels must
     *        not grow by 1/scale just because the target is smaller.
     */
    public static GlFramebuffer blur(GlFramebuffer input, float radius, boolean box, float texelX, float texelY)
    {
        if (radius <= 0.5F)
        {
            return input;
        }

        GlFramebuffer premultiplied = premultiply(input);

        if (premultiplied == input)
        {
            return input;
        }

        int r = Math.max(1, Math.min(1024, Math.round(radius)));
        GlFramebuffer horizontal = FboPool.acquire(input.width, input.height);

        horizontal.begin();
        GlStateManager._disableBlend();
        GlslProgram program = CompShaders.blur;

        if (program == null)
        {
            FboPool.release(premultiplied);
            FboPool.release(horizontal);
            return input;
        }

        program.use();
        bind(program, "Source", 0, premultiplied);
        program.set("Texel", texelX, texelY);
        program.set("Direction", 1F, 0F);
        program.set("Radius", (float) r);
        program.set("Mode", box ? 1 : 0);
        program.drawFullscreen();

        FboPool.release(premultiplied);

        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        bind(program, "Source", 0, horizontal);
        program.set("Texel", texelX, texelY);
        program.set("Direction", 0F, 1F);
        program.set("Radius", (float) r);
        program.set("Mode", box ? 1 : 0);
        program.drawFullscreen();

        FboPool.release(horizontal);

        GlFramebuffer straight = unpremultiply(output);

        if (straight != output)
        {
            FboPool.release(output);
        }

        return straight;
    }

    public static void bind(GlslProgram program, String sampler, int unit, GlFramebuffer texture)
    {
        bindRaw(program, sampler, unit, texture == null ? 0 : texture.color.id);
    }

    /**
     * Binds a texture to a unit with raw GL calls.
     *
     * <p>{@link GlStateManager} caches the binding per texture unit. BBS, Sodium and Iris all issue
     * raw GL calls too, so a cached "already bound" can be a lie; for the replay matte that lie was
     * indistinguishable from "the depth texture is empty". Raw calls and an explicit
     * {@code glBindSampler(unit, 0)} make the binding an observed fact rather than an assumption,
     * and also neutralise a leftover sampler object (a shadow sampler with
     * {@code COMPARE_REF_TO_TEXTURE} would turn every {@code sampler2D} sample into 0/1).</p>
     */
    public static void bindRaw(GlslProgram program, String sampler, int unit, int texture)
    {
        /* Force the actual active unit first, then bring GlStateManager's caches in line.
         *
         * <p>Both halves matter. A raw-only bind left GlStateManager's active-unit cache stale, so
         * the next cached {@code _activeTexture(0)} was skipped and later layers sampled the wrong
         * unit. A cached-only bind has the opposite failure: BBS/Iris raw GL calls can leave the
         * cache pointing at a different unit than the one GL is really on, so the "active" unit is
         * never selected and the mask texture lands somewhere the sampler does not read. Forcing
         * raw first and then syncing the cache makes both the actual state and the cache correct.</p> */
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        clearSampler(unit);
        program.set(sampler, unit);
    }

    private static void clearSampler(int unit)
    {
        try
        {
            if (GL.getCapabilities().GL_ARB_sampler_objects)
            {
                org.lwjgl.opengl.ARBSamplerObjects.glBindSampler(unit, 0);
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            /* A context without sampler objects cannot have one bound. */
        }
    }
}
