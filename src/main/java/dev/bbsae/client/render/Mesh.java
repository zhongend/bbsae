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
    private int sortedEbo;
    private long[] sortKeys;

    Mesh(float[] data, int[] indices)
    {
        this.data = data;
        this.indices = indices;
    }

    int size()
    {
        return this.indices.length;
    }

    /**
     * Painter's algorithm for non-convex primitives (torus, ring, hollow box, star polyhedron).
     *
     * <p>The colour-only targets have no depth buffer; back-face culling alone is enough for convex
     * meshes but the far side of a torus can overwrite the near side. Triangles are sorted far to
     * near in clip space and drawn through a dynamic index buffer.</p>
     */
    void drawSorted(org.joml.Matrix4f mvp)
    {
        int triangles = this.indices.length / 3;

        if (this.vao == 0)
        {
            this.upload();
        }

        if (this.sortKeys == null || this.sortKeys.length != triangles)
        {
            this.sortKeys = new long[triangles];
        }

        float[] data = this.data;

        for (int t = 0; t < triangles; t++)
        {
            int a = this.indices[t * 3] * STRIDE;
            int b = this.indices[t * 3 + 1] * STRIDE;
            int c = this.indices[t * 3 + 2] * STRIDE;
            /* JOML is column-major: clip z = m02*x + m12*y + m22*z + m32. */
            float za = mvp.m02() * data[a] + mvp.m12() * data[a + 1] + mvp.m22() * data[a + 2] + mvp.m32();
            float zb = mvp.m02() * data[b] + mvp.m12() * data[b + 1] + mvp.m22() * data[b + 2] + mvp.m32();
            float zc = mvp.m02() * data[c] + mvp.m12() * data[c + 1] + mvp.m22() * data[c + 2] + mvp.m32();
            float depth = (za + zb + zc) / 3F;
            int quantized = (int) Math.max(0, Math.min(0xFFFFF, Math.round((depth + 1F) * 0.5F * 0xFFFFF)));

            this.sortKeys[t] = ((long) quantized << 32) | (t & 0xFFFFFFFFL);
        }

        java.util.Arrays.sort(this.sortKeys);

        if (this.sortedEbo == 0)
        {
            this.sortedEbo = GL15.glGenBuffers();
        }

        int[] sorted = new int[this.indices.length];

        for (int t = 0; t < triangles; t++)
        {
            /* NDC z grows with distance (near plane is -1), so walk from the largest key: the
             * farthest triangles are drawn first and the near ones paint over them. */
            int triangle = (int) (this.sortKeys[triangles - 1 - t] & 0xFFFFFFFFL);

            sorted[t * 3] = this.indices[triangle * 3];
            sorted[t * 3 + 1] = this.indices[triangle * 3 + 1];
            sorted[t * 3 + 2] = this.indices[triangle * 3 + 2];
        }

        /* sortKeys is ascending by quantised depth; drawing from the far end gives near-last. */
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.sortedEbo);

        IntBuffer elements = MemoryUtil.memAllocInt(sorted.length);

        elements.put(sorted).flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, elements, GL15.GL_STREAM_DRAW);
        MemoryUtil.memFree(elements);
        GL11.glDrawElements(GL11.GL_TRIANGLES, sorted.length, GL11.GL_UNSIGNED_INT, 0L);
        GL30.glBindVertexArray(0);
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

        if (this.sortedEbo != 0)
        {
            GL15.glDeleteBuffers(this.sortedEbo);
            this.sortedEbo = 0;
        }

        if (this.vao != 0)
        {
            GL30.glDeleteVertexArrays(this.vao);
            this.vao = 0;
        }
    }
}
