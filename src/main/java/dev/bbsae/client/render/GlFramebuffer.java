package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A colour-only off-screen target used by the composition render graph.
 *
 * <p>Layers, masks and effect ping-pong buffers are all ordinary RGBA8 targets. Deliberately no
 * depth attachment: 2D composition order is explicit, and a depth buffer would only make the
 * layer texture format needlessly heavier.</p>
 */
public final class GlFramebuffer
{
    public final int id;
    public final GlTexture color;
    public final int width;
    public final int height;

    public GlFramebuffer(int width, int height)
    {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.color = new GlTexture();
        this.color.allocate(this.width, this.height);

        this.id = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.color.id, 0);
        GL30.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);

        /* glCheckFramebufferStatus is a sync point. It used to run for every target the pool
         * created — including the ones created mid-frame when the pool was exhausted — so the
         * debug-only probe below keeps the hot path clean. Set -Dbbsae.debugFbo=true to verify. */
        if (Boolean.getBoolean("bbsae.debugFbo"))
        {
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

            if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
            {
                org.slf4j.LoggerFactory.getLogger("bbsae").warn("Composition framebuffer {}x{} is incomplete: 0x{}", this.width, this.height, Integer.toHexString(status));
            }
        }
    }

    public void begin()
    {
        this.begin(0x00000000);
    }

    public void begin(int clearColor)
    {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
        GlStateManager._viewport(0, 0, this.width, this.height);
        GlStateManager._disableScissorTest();
        GlStateManager._disableDepthTest();
        GlStateManager._disableCull();
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GlStateManager._enableBlend();
        GlStateManager._blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._clearColor(((clearColor >> 16) & 0xFF) / 255F, ((clearColor >> 8) & 0xFF) / 255F, (clearColor & 0xFF) / 255F, ((clearColor >>> 24) & 0xFF) / 255F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    public void bindTexture(int unit)
    {
        this.color.bind(unit);
    }

    /** Reads the target as top-down ARGB rows, which is what PNG and video encoders want. */
    public int[] readTopDown()
    {
        ByteBuffer buffer = MemoryUtil.memAlloc(this.width * this.height * 4);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
        GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        int[] pixels = new int[this.width * this.height];

        for (int y = 0; y < this.height; y++)
        {
            int sourceY = this.height - 1 - y;

            for (int x = 0; x < this.width; x++)
            {
                int i = (x + sourceY * this.width) * 4;
                int r = buffer.get(i) & 0xFF;
                int g = buffer.get(i + 1) & 0xFF;
                int b = buffer.get(i + 2) & 0xFF;
                int a = buffer.get(i + 3) & 0xFF;

                pixels[x + y * this.width] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        MemoryUtil.memFree(buffer);

        return pixels;
    }

    /**
     * Reads the target as raw RGBA bytes in GL order (bottom-up).
     *
     * <p>This is the format the pre-render cache stores: no channel swap, no row reversal, no
     * per-pixel Java loop — a straight copy that can be deflated and uploaded back verbatim. At
     * 1280×720 the per-pixel conversion in {@link #readTopDown()} costs several milliseconds,
     * which is exactly the budget a cached frame is supposed to save.</p>
     */
    public byte[] readRawBytes()
    {
        ByteBuffer buffer = MemoryUtil.memAlloc(this.width * this.height * 4);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
            GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            byte[] bytes = new byte[this.width * this.height * 4];

            buffer.get(bytes);

            return bytes;
        }
        finally
        {
            MemoryUtil.memFree(buffer);
        }
    }

    public void delete()
    {
        GL30.glDeleteFramebuffers(this.id);
        this.color.delete();
    }

    public static void unbind(int screenWidth, int screenHeight)
    {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, screenWidth, screenHeight);
    }
}
