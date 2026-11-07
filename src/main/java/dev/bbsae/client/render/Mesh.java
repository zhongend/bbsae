package dev.bbsae.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A small indexed triangle mesh with position + UV.
 *
 * <p>The 3D shape effects are convex primitives rendered with back-face culling and no depth
 * buffer: on a convex mesh no front-facing triangle can overlap another one in screen space, so
 * culling alone gives correct occlusion and the whole feature fits the existing colour-only
 * framebuffers. Geometry is generated in unit space (x/y/z in -0.5..0.5) and scaled by the layer's
 * own size at draw time.</p>
 */
public final class Mesh
{
    /** Position (3) + UV (2) + normal (3). */
    static final int STRIDE = 8;

    final float[] data;
    final int[] indices;

    private int vao;
    private int vbo;
    private int ebo;

    Mesh(float[] data, int[] indices)
    {
        this.data = data;
        this.indices = indices;
    }

    int size()
    {
        return this.indices.length;
    }

    void draw()
    {
        if (this.vao == 0)
        {
            this.upload();
        }

        GL30.glBindVertexArray(this.vao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, this.indices.length, GL11.GL_UNSIGNED_INT, 0L);
        GL30.glBindVertexArray(0);
    }

    private void upload()
    {
        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        this.ebo = GL15.glGenBuffers();

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);

        FloatBuffer vertices = MemoryUtil.memAllocFloat(this.data.length);

        vertices.put(this.data).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(vertices);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.ebo);

        IntBuffer elements = MemoryUtil.memAllocInt(this.indices.length);

        elements.put(this.indices).flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, elements, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(elements);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE * 4, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE * 4, 3L * 4L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, STRIDE * 4, 5L * 4L);

        GL30.glBindVertexArray(0);
    }

    void delete()
    {
        if (this.vbo != 0)
        {
            GL15.glDeleteBuffers(this.vbo);
            this.vbo = 0;
        }

        if (this.ebo != 0)
        {
            GL15.glDeleteBuffers(this.ebo);
            this.ebo = 0;
        }

        if (this.vao != 0)
        {
            GL30.glDeleteVertexArrays(this.vao);
            this.vao = 0;
        }
    }
}
