package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** A small raw-GL texture. Keeps the composition pipeline independent of BBS's texture registry. */
public final class GlTexture
{
    public int id;
    public int width;
    public int height;
    public int internalFormat = GL11.GL_RGBA8;

    public GlTexture()
    {
        this.id = GL11.glGenTextures();
        GlStateManager._bindTexture(this.id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    public GlTexture allocate(int width, int height)
    {
        return this.allocate(width, height, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
    }

    public GlTexture allocate(int width, int height, int internalFormat, int format, int type)
    {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.internalFormat = internalFormat;

        GlStateManager._bindTexture(this.id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, this.width, this.height, 0, format, type, (ByteBuffer) null);
        return this;
    }

    public void bind(int unit)
    {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(this.id);
    }

    /**
     * Uploads top-down ARGB. GL's first row is the bottom of the texture, while images and
     * composition FBOs are addressed top-down here, so rows are reversed once at upload time.
     */
    public void uploadTopDown(int[] argb)
    {
        int w = this.width;
        int h = this.height;
        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);

        for (int y = h - 1; y >= 0; y--)
        {
            for (int x = 0; x < w; x++)
            {
                int color = argb[x + y * w];

                buffer.put((byte) ((color >> 16) & 0xFF));
                buffer.put((byte) ((color >> 8) & 0xFF));
                buffer.put((byte) (color & 0xFF));
                buffer.put((byte) ((color >>> 24) & 0xFF));
            }
        }

        buffer.flip();
        this.upload(buffer);
        MemoryUtil.memFree(buffer);
    }

    /**
     * Uploads rows in image order. BBS's UI texture convention treats v=0 as the top of the
     * image, which is what {@code Batcher2D} expects for images and shape rasters.
     */
    public void uploadRaw(int[] argb)
    {
        int w = this.width;
        int h = this.height;
        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);

        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int color = argb[x + y * w];

                buffer.put((byte) ((color >> 16) & 0xFF));
                buffer.put((byte) ((color >> 8) & 0xFF));
                buffer.put((byte) (color & 0xFF));
                buffer.put((byte) ((color >>> 24) & 0xFF));
            }
        }

        buffer.flip();
        this.upload(buffer);
        MemoryUtil.memFree(buffer);
    }

    private void upload(ByteBuffer buffer)
    {
        GlStateManager._bindTexture(this.id);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

        /* Mask/texture uploads are tightly packed. BBS image uploads may leave a non-zero
         * UNPACK_ROW_LENGTH in the GL state; with that still active the driver reads past this
         * buffer and can crash inside nvoglv64 rather than raising a GL error. */
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);

        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
    }

    /**
     * Uploads tightly packed RGBA bytes in GL order (bottom-up, no conversion).
     *
     * <p>This is the pre-render cache path: the bytes came from {@code glReadPixels} on a
     * composition target, so they are already in the exact layout this texture wants.</p>
     */
    public void uploadBytes(byte[] rgba)
    {
        this.upload(ByteBuffer.wrap(rgba));
    }

    public void delete()
    {
        if (this.id > 0)
        {
            GL11.glDeleteTextures(this.id);
            this.id = 0;
        }
    }

    public static void unbind(int unit)
    {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(0);
    }

    public static boolean isComplete()
    {
        return GL11.glGetError() == GL11.GL_NO_ERROR;
    }

    public int target()
    {
        return this.internalFormat == GL30.GL_DEPTH_COMPONENT24 ? GL30.GL_TEXTURE_2D : GL11.GL_TEXTURE_2D;
    }
}
