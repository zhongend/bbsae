package dev.bbsae.client.render;

import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.Renderbuffer;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * One off-screen RGBA target at composition resolution.
 *
 * <p>Compositing into an off-screen target is what makes layer isolation, masks and effects
 * possible. The texture is drawn back through BBS's own {@code Batcher2D}, so the result still
 * reaches BBS's framebuffer and the video recorder through the normal overlay path.</p>
 */
public final class CompFramebuffer implements AutoCloseable
{
    private final int width;
    private final int height;
    private final Texture texture = new Texture();
    private final Framebuffer framebuffer = new Framebuffer();
    private final Renderbuffer depth = new Renderbuffer();

    public CompFramebuffer(int width, int height)
    {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);

        this.texture.setFilter(GL11.GL_LINEAR);
        this.texture.setSize(this.width, this.height);
        this.framebuffer.attach(this.texture, GL30.GL_COLOR_ATTACHMENT0);
        this.depth.resize(this.width, this.height);
        this.framebuffer.attach(this.depth);
        this.framebuffer.attachments(1);
        this.framebuffer.bind();
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        dev.bbsae.BbsAeAddon.LOGGER.info("Comp FBO {}x{} id={} tex={} status={} err={}", this.width, this.height, this.framebuffer.id, this.texture.id, status, GL11.glGetError());
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }

    public int textureId()
    {
        return this.texture.id;
    }

    public void begin()
    {
        GL11.glClearColor(0F, 0F, 0F, 0F);
        this.framebuffer.applyClear();

        RenderSystem.disableScissor();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public void end()
    {
        MinecraftClient.getInstance().getFramebuffer().beginWrite(true);
    }

    @Override
    public void close()
    {
        this.framebuffer.delete();
        this.texture.delete();
    }
}
