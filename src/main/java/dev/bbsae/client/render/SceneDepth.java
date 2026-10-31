package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
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
    private boolean tested;

    public SceneDepth()
    {
        this.framebuffer = GL30.glGenFramebuffers();
        this.texture = GL11.glGenTextures();

        GlStateManager._bindTexture(this.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        /* CLAMP_TO_EDGE, not GL_CLAMP: the core-profile 3.2 context removed GL_CLAMP and
         * glTexParameteri answers it with GL_INVALID_ENUM. */
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
    /**
     * Copies the depth attachment of the currently bound draw framebuffer.
     *
     * <p>The old version called {@code glGetError()} in a loop and {@code glCheckFramebufferStatus}
     * <b>every frame</b>. Both are pipeline syncs: they force the CPU to wait for the GPU and can
     * cost tens of milliseconds per frame on their own. The status is now probed once per
     * allocation (and only retried when the size changes), and the per-frame path is a single
     * blit.</p>
     */
    public void capture()
    {
        int[] viewport = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        this.captureFrom(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), viewport[2], viewport[3]);
    }

    /**
     * Copies the depth attachment of a specific framebuffer.
     *
     * <p>Used by the compositor preview: the world's depth is copied at
     * {@code WorldRenderEvents.LAST} from Minecraft's main framebuffer, which is the only source
     * that contains the film's real geometry. Relying on "whatever is bound" worked for the
     * overlay path but read an empty attachment once the compositor had bound its own target.</p>
     */
    public void captureFrom(int readFramebuffer, int width, int height)
    {
        int w = width;
        int h = height;

        if (w <= 0 || h <= 0)
        {
            this.available = false;
            return;
        }

        if (w != this.width || h != this.height || !this.tested)
        {
            this.width = w;
            this.height = h;
            this.tested = true;

            GlStateManager._bindTexture(this.texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.texture, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);

            this.available = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }

        if (!this.available)
        {
            return;
        }

        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.framebuffer);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

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
