package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BufferRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Saves and restores every piece of GL state this renderer touches.
 *
 * <p>BBS shares the context with Sodium/Iris and with Minecraft's own UI renderer. A composition
 * must not leak its framebuffer, shader, VAO, texture units, scissor or blend state into the next
 * frame. This guard is deliberately conservative: restoring a little more than necessary is
 * cheaper than tracking down a corrupted world render.</p>
 */
public final class GlStateGuard implements AutoCloseable
{
    private final int program;
    private final int vao;
    private final int arrayBuffer;
    private final int framebuffer;
    private final int readFramebuffer;
    private final int drawFramebuffer;
    private final int activeTexture;
    private final int[] viewport = new int[4];
    private final int[] textures = new int[4];
    private final int[] blendSrc = new int[1];
    private final int[] blendDst = new int[1];
    private final int[] blendSrcAlpha = new int[1];
    private final int[] blendDstAlpha = new int[1];
    private final int[] blendEquation = new int[1];
    private final int[] blendEquationAlpha = new int[1];
    private final int[] scissorBox = new int[4];
    private final boolean blend;
    private final boolean depth;
    private final boolean cull;
    private final boolean scissor;
    private final boolean stencil;
    private final boolean depthMask;
    private final boolean[] colorMask = new boolean[4];
    private final int depthFunc;
    private final float[] clearColor = new float[4];

    public GlStateGuard()
    {
        this.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        this.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        this.arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        this.framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        this.readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        this.drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        this.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
        this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        this.depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        this.stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        this.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        this.colorMask[0] = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        this.colorMask[1] = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        this.colorMask[2] = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        this.colorMask[3] = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        this.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, this.clearColor);
        GL11.glGetIntegerv(GL14.GL_BLEND_SRC_RGB, this.blendSrc);
        GL11.glGetIntegerv(GL14.GL_BLEND_DST_RGB, this.blendDst);
        GL11.glGetIntegerv(GL14.GL_BLEND_SRC_ALPHA, this.blendSrcAlpha);
        GL11.glGetIntegerv(GL14.GL_BLEND_DST_ALPHA, this.blendDstAlpha);
        GL11.glGetIntegerv(GL20.GL_BLEND_EQUATION_RGB, this.blendEquation);
        GL11.glGetIntegerv(GL20.GL_BLEND_EQUATION_ALPHA, this.blendEquationAlpha);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, this.scissorBox);

        for (int i = 0; i < this.textures.length; i++)
        {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
            this.textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        GlStateManager._activeTexture(this.activeTexture);
    }

    @Override
    public void close()
    {
        GlStateManager._glUseProgram(this.program);
        GlStateManager._glBindVertexArray(this.vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, this.arrayBuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
        GlStateManager._viewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);

        for (int i = 0; i < this.textures.length; i++)
        {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
            GlStateManager._bindTexture(this.textures[i]);
        }

        GlStateManager._activeTexture(this.activeTexture);

        setBlend(this.blend);
        setDepth(this.depth);
        setCull(this.cull);
        setScissor(this.scissor);

        if (this.stencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
        else GL11.glDisable(GL11.GL_STENCIL_TEST);

        GlStateManager._depthMask(this.depthMask);
        GlStateManager._colorMask(this.colorMask[0], this.colorMask[1], this.colorMask[2], this.colorMask[3]);
        GlStateManager._depthFunc(this.depthFunc);
        GlStateManager._blendFuncSeparate(this.blendSrc[0], this.blendDst[0], this.blendSrcAlpha[0], this.blendDstAlpha[0]);
        GlStateManager._blendEquation(this.blendEquation[0]);

        if (this.blendEquationAlpha[0] != this.blendEquation[0])
        {
            GL20.glBlendEquationSeparate(this.blendEquation[0], this.blendEquationAlpha[0]);
        }

        if (this.scissor)
        {
            GlStateManager._scissorBox(this.scissorBox[0], this.scissorBox[1], this.scissorBox[2], this.scissorBox[3]);
        }

        GlStateManager._clearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]);

        BufferRenderer.resetCurrentVertexBuffer();
        RenderSystem.applyModelViewMatrix();
    }

    private static void setBlend(boolean enabled)
    {
        if (enabled) GlStateManager._enableBlend();
        else GlStateManager._disableBlend();
    }

    private static void setDepth(boolean enabled)
    {
        if (enabled) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();
    }

    private static void setCull(boolean enabled)
    {
        if (enabled) GlStateManager._enableCull();
        else GlStateManager._disableCull();
    }

    private static void setScissor(boolean enabled)
    {
        if (enabled) GlStateManager._enableScissorTest();
        else GlStateManager._disableScissorTest();
    }

    public static Matrix4f ortho(float width, float height)
    {
        return new Matrix4f().ortho(0F, width, height, 0F, -1000F, 1000F);
    }
}
