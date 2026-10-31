package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
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
        GlFramebuffer output = FboPool.acquire(source.width, source.height);

        drawInto(output, source, backdrop, mask, blendMode, opacity, offsetX, offsetY, tint, 0);

        return output;
    }

    public static GlFramebuffer mask(GlFramebuffer source, int maskTexture, int channel)
    {
        GlFramebuffer output = FboPool.acquire(source.width, source.height);
        GlslProgram program = CompShaders.composite;

        if (program == null)
        {
            FboPool.release(output);
            return source;
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
        program.set("Offset", offsetX / Math.max(1, source.width), offsetY / Math.max(1, source.height));
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
        if (radius <= 0.5F)
        {
            return input;
        }

        GlFramebuffer premultiplied = premultiply(input);

        if (premultiplied == input)
        {
            return input;
        }

        int r = Math.max(1, Math.min(200, Math.round(radius)));
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
        program.set("Texel", 1F / input.width, 1F / input.height);
        program.set("Direction", 1F, 0F);
        program.set("Radius", (float) r);
        program.set("Mode", box ? 1 : 0);
        program.drawFullscreen();

        FboPool.release(premultiplied);

        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        bind(program, "Source", 0, horizontal);
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
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture == null ? 0 : texture.color.id);
        program.set(sampler, unit);
    }

    public static void bindRaw(GlslProgram program, String sampler, int unit, int texture)
    {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        program.set(sampler, unit);
    }
}
