package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A copy of the finished world frame, owned by the compositor.
 *
 * <p>This exists so the compositor never has to enable {@code BBSRendering.setCustomSize}. That
 * mode makes BBS render the world into its own framebuffer and blit it over the screen; while a
 * non-film dashboard panel is on screen the blit wins over the panel's UI, which is exactly the
 * "compositor page is blank, every other BBS page is fine" bug.</p>
 *
 * <p>Instead, the world renders normally into Minecraft's main framebuffer, the film camera and
 * replay actors are driven by {@link dev.bbsae.client.overlay.BbsPreviewBridge}, and this class
 * copies the finished color once per frame with {@code glCopyTexSubImage2D}. The copy is bottom-up
 * like every GL framebuffer, so consumers keep swapping V when drawing it.</p>
 */
public final class SceneColor
{
    private static GlTexture texture;
    private static int width;
    private static int height;

    /**
     * A second copy taken after Iris' final pass. This is the image that includes the shader
     * pack's exposure, bloom, tonemap and colour grading; the early copy is only the pre-composite
     * G-buffer scene and is kept as a fallback for non-Iris setups.
     */
    private static GlTexture finalTexture;
    private static int finalWidth;
    private static int finalHeight;
    private static boolean finalValid;
    private static boolean finalRequested;
    private static boolean finalLogged;

    private SceneColor()
    {}

    /** Called from {@code WorldRenderEvents.LAST}, after actors/particles but before the UI. */
    public static void capture()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null)
        {
            return;
        }

        /* Iris renders a shadow pass with only depth bound. Copying that pass would replace the
         * preview with a black/depth image, so it must be skipped until the colour pass arrives. */
        if (!ShaderCompat.canCaptureWorld())
        {
            return;
        }

        Framebuffer framebuffer = mc.getFramebuffer();

        /* BBS may point its own world framebuffer at a custom target. In the editor (custom size
         * off) the main framebuffer is authoritative; this fallback only covers the case where
         * Minecraft's reference is null but BBS still has a valid colour target. */
        if (framebuffer == null)
        {
            try
            {
                framebuffer = mchorse.bbs_mod.client.BBSRendering.getFramebuffer();
            }
            catch (RuntimeException | LinkageError ignored)
            {
                framebuffer = null;
            }
        }

        if (framebuffer == null)
        {
            return;
        }

        int w = framebuffer.textureWidth;
        int h = framebuffer.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return;
        }

        if (texture == null || width != w || height != h)
        {
            if (texture != null)
            {
                texture.delete();
            }

            texture = new GlTexture();
            texture.allocate(w, h);
            width = w;
            height = h;
        }

        int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer.fbo);
            GlStateManager._bindTexture(texture.id);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._bindTexture(0);
        }

        /* Iris will call captureFinal() at the tail of finalizeLevelRendering(). The early copy
         * remains the fallback for vanilla/no-shader and for the very first frame while the
         * pipeline is still compiling. */
        finalRequested = ShaderCompat.irisLoaded();
    }

    /**
     * Copies the framebuffer that Iris has just finished compositing.
     *
     * <p>Called from an optional mixin at the tail of {@code IrisRenderingPipeline.finalizeLevelRendering}.
     * The draw framebuffer at that point is the final target (main framebuffer or BBS' target),
     * so reading it back with {@code glCopyTexSubImage2D} gives the exact graded frame. The copy is
     * bottom-up, the same convention as {@link #capture()}.</p>
     */
    public static void captureFinal()
    {
        if (!finalRequested || !ShaderCompat.irisLoaded())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.world == null)
        {
            return;
        }

        int drawFramebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        int w = viewport[2];
        int h = viewport[3];

        /* At the tail of the final pass the viewport may already have been restored to the window.
         * Prefer the known main-framebuffer size when the draw target is that framebuffer. */
        Framebuffer main = mc.getFramebuffer();
        int mainFbo = main == null ? 0 : main.fbo;

        if (drawFramebuffer == mainFbo && main != null)
        {
            w = main.textureWidth;
            h = main.textureHeight;
        }

        if (w <= 0 || h <= 0)
        {
            return;
        }

        if (finalTexture == null || finalWidth != w || finalHeight != h)
        {
            if (finalTexture != null)
            {
                finalTexture.delete();
            }

            finalTexture = new GlTexture();
            finalTexture.allocate(w, h);
            finalWidth = w;
            finalHeight = h;
        }

        int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._bindTexture(finalTexture.id);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            finalValid = true;
        }
        catch (RuntimeException e)
        {
            finalValid = false;
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._bindTexture(0);
        }

        if (finalValid && !finalLogged)
        {
            finalLogged = true;
            dev.bbsae.BbsAeAddon.LOGGER.info("Iris final-pass capture enabled: {}x{} from framebuffer {}",
                w, h, drawFramebuffer);
        }
    }

    /** The shader-final copy when Iris is active, otherwise the early world copy. */
    public static GlTexture texture()
    {
        return ShaderCompat.irisLoaded() && finalValid && finalTexture != null ? finalTexture : texture;
    }

    /** True when a shader-pack final frame has actually been captured. */
    public static boolean hasFinal()
    {
        return finalValid && finalTexture != null;
    }

    public static void delete()
    {
        if (texture != null)
        {
            texture.delete();
            texture = null;
        }

        if (finalTexture != null)
        {
            finalTexture.delete();
            finalTexture = null;
        }

        width = 0;
        height = 0;
        finalWidth = 0;
        finalHeight = 0;
        finalValid = false;
        finalRequested = false;
        finalLogged = false;
    }
}
