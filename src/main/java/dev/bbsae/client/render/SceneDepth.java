package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A copy of the world framebuffer's depth attachment.
 *
 * <p>BBS does not expose depth through its addon API. At the frame-overlay point the world has
 * already been drawn, so the currently bound draw framebuffer still has its depth buffer. We blit
 * it into an R32F texture before touching any composition FBO. If the driver/Iris combination
 * refuses the blit the texture stays invalid and every depth effect degrades to a no-op; it must
 * never take the whole overlay down.</p>
 */
public final class SceneDepth
{
    private int framebuffer;
    private int texture;
    private int width;
    private int height;
    private boolean available;

    public SceneDepth()
    {
        this.framebuffer = GL30.glGenFramebuffers();
        this.texture = GL11.glGenTextures();

        GlStateManager._bindTexture(this.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
    }
    public void capture()
    {
        int[] viewport = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        int w = viewport[2];
        int h = viewport[3];

        if (w <= 0 || h <= 0)
        {
            this.available = false;
            return;
        }

        if (w != this.width || h != this.height)
        {
            this.width = w;
            this.height = h;

            GlStateManager._bindTexture(this.texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.texture, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
        }

        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        while (GL11.glGetError() != GL11.GL_NO_ERROR)
        {
            /* Clear stale errors before testing whether the driver accepted the depth blit. */
        }

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldDraw);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.framebuffer);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        this.available = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
            && GL11.glGetError() == GL11.GL_NO_ERROR;

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
    }

    public boolean isAvailable()
    {
        return this.available;
    }

    public int texture()
    {
        return this.texture;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }

    public void delete()
    {
        GL11.glDeleteTextures(this.texture);
        GL30.glDeleteFramebuffers(this.framebuffer);
        this.texture = 0;
        this.framebuffer = 0;
    }
}
