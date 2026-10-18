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

    private SceneColor()
    {}

    /** Called from {@code WorldRenderEvents.LAST}, after actors/particles but before the UI. */
    public static void capture()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.getFramebuffer() == null)
        {
            return;
        }

        Framebuffer framebuffer = mc.getFramebuffer();
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
    }

    public static GlTexture texture()
    {
        return texture;
    }

    public static void delete()
    {
        if (texture != null)
        {
            texture.delete();
            texture = null;
        }

        width = 0;
        height = 0;
    }
}
