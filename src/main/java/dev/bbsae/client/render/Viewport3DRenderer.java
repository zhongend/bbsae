package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Draws one layer as a real depth-tested quad in the 3D workspace.
 *
 * <p>The workspace draft used to emit every layer as a screen-space quad with z=0, so layers could
 * only be ordered as whole pictures: a plane pushed through another one drew complete quads and they
 * visibly interpenetrated. Here the four corners are transformed by the layer's world matrix and the
 * camera's view-projection, so the GPU interpolates the true per-pixel depth and the scene depth
 * buffer resolves crossings the way a 3D viewport should. The draft path uses fixed-function
 * blending, which is what the batcher used before, so no blend-mode semantics are lost.</p>
 */
public final class Viewport3DRenderer
{
    private static int vao;
    private static int vbo;

    private Viewport3DRenderer()
    {}

    public static boolean available()
    {
        return CompShaders.viewport3d != null && CompShaders.viewport3d.isValid();
    }

    /**
     * @param world            layer local -> world
     * @param viewProjection   world -> clip
     * @param x0,y0,x1,y1      layer-local rectangle of the raster
     * @param opacity          0..1
     */
    public static void draw(GlTexture texture, Matrix4f world, Matrix4f viewProjection,
                            float x0, float y0, float x1, float y1, float opacity)
    {
        GlslProgram program = CompShaders.viewport3d;

        if (program == null || texture == null || !program.isValid() || opacity <= 0.001F)
        {
            return;
        }

        ensureGeometry();

        /* Four corners, world space. V is flipped: the raster is y-down, so local y0 is the top
         * row and therefore v = 1. */
        Vector3f p00 = new Vector3f(x0, y0, 0F);
        Vector3f p10 = new Vector3f(x1, y0, 0F);
        Vector3f p11 = new Vector3f(x1, y1, 0F);
        Vector3f p01 = new Vector3f(x0, y1, 0F);

        world.transformPosition(p00);
        world.transformPosition(p10);
        world.transformPosition(p11);
        world.transformPosition(p01);

        float[] data =
        {
            p00.x, p00.y, p00.z, 0F, 1F,
            p10.x, p10.y, p10.z, 1F, 1F,
            p11.x, p11.y, p11.z, 1F, 0F,
            p00.x, p00.y, p00.z, 0F, 1F,
            p11.x, p11.y, p11.z, 1F, 0F,
            p01.x, p01.y, p01.z, 0F, 0F,
        };

        FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);

        buffer.put(data).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        MemoryUtil.memFree(buffer);

        /* Raw GL, not GlStateManager: after a context change or a BBS raw-GL pass the cached state
         * can claim blend/depth are already in the wanted state and skip the call, which left
         * translucent layers opaque (and made GL tests order dependent). */
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);

        program.use();
        PassRenderer.bindRaw(program, "Source", 0, texture.id);
        program.set("ViewProjection", viewProjection);
        program.set("Tint", 1F, 1F, 1F, opacity);

        GL30.glBindVertexArray(vao);

        /* Opaque part: writes depth so crossings resolve. */
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDepthMask(opacity >= 0.999F);
        program.set("AlphaMode", 0);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        /* Translucent part: blends over what is already there, never writes depth. */
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        program.set("AlphaMode", 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        GL30.glBindVertexArray(0);

        /* Restore conservative depth state: leaving depth writes/tests armed leaked into whatever
         * was drawn next (UI preview, composition blit) and could keep stale pixels on screen. */
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    private static void ensureGeometry()
    {
        if (vao != 0)
        {
            return;
        }

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * 4, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * 4, 3L * 4L);
        GL30.glBindVertexArray(0);
    }

    public static void delete()
    {
        if (vbo != 0)
        {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }

        if (vao != 0)
        {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
