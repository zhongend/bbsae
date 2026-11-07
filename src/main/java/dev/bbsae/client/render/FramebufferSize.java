package dev.bbsae.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Reads the real pixel size of a framebuffer's colour attachment.
 *
 * <p>The replay matte must render at the world frame's size. Querying GL_VIEWPORT is only a guess:
 * BBS' custom-size preview/recorder can leave the viewport at the window size while the world was
 * rendered into a differently sized target, and a framebuffer can be multisampled (renderbuffer)
 * instead of having a texture attachment. Asking the attachment object itself is the authoritative
 * answer.</p>
 */
final class FramebufferSize
{
    private FramebufferSize()
    {}

    /**
     * @return {width, height} of COLOR_ATTACHMENT0 of {@code framebuffer}, or null when it cannot be
     *         determined (incomplete FBO, no colour attachment, GL error).
     */
    static int[] of(int framebuffer)
    {
        if (framebuffer == 0)
        {
            return null;
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] size = null;

        try
        {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);

            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);

            if (name != 0 && type == GL11.GL_TEXTURE)
            {
                int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);

                if (width > 0 && height > 0)
                {
                    size = new int[] {width, height};
                }
            }
            else if (name != 0 && type == GL30.GL_RENDERBUFFER)
            {
                int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);

                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
                int width = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
                int height = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);

                if (width > 0 && height > 0)
                {
                    size = new int[] {width, height};
                }
            }
        }
        catch (RuntimeException ignored)
        {
            size = null;
        }
        finally
        {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        }

        return size;
    }
}
