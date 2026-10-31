package dev.bbsae.client.render;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel checks for the Grid / Random Repeat shader.
 *
 * <p>A compile-only test would not catch the most likely regression: a wrong local-to-source
 * mapping that still compiles. These assertions prove that a 1×1 grid is an identity, that a 2×2
 * grid repeats the same local sample in every cell, and that random mode is seed-stable.</p>
 */
public class ShaderRepeatTest
{
    private static final int SIZE = 32;
    private static long window;

    @BeforeAll
    public static void createContext()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "bbsae repeat check", 0L, 0L);
        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll
    public static void destroyContext()
    {
        if (window != 0L)
        {
            GLFW.glfwDestroyWindow(window);
            window = 0L;
        }
    }

    private static int[] source()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                int r = x * 255 / (SIZE - 1);
                int g = y * 255 / (SIZE - 1);

                pixels[y * SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | 32;
            }
        }

        return pixels;
    }

    private static int[] render(int columns, int rows, float scale, float gap, float rotation, int mode, float seed, float randomness)
    {
        int texture = GL11.glGenTextures();
        int target = GL11.glGenTextures();
        int fbo = GL30.glGenFramebuffers();
        int vao = GL30.glGenVertexArrays();
        int vbo = GL30.glGenBuffers();
        int program = 0;
        int vs = 0;
        int fs = 0;

        try
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            int[] source = source();
            ByteBuffer upload = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

            for (int pixel : source)
            {
                upload.put((byte) ((pixel >> 16) & 0xFF));
                upload.put((byte) ((pixel >> 8) & 0xFF));
                upload.put((byte) (pixel & 0xFF));
                upload.put((byte) ((pixel >>> 24) & 0xFF));
            }

            upload.flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            vs = compile(GL20.GL_VERTEX_SHADER, ShaderSources.VERTEX);
            fs = compile(GL20.GL_FRAGMENT_SHADER, ShaderSources.REPEAT);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glLinkProgram(program);
            assertTrue(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE, "REPEAT shader must link");

            GL30.glBindVertexArray(vao);
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            java.nio.FloatBuffer vertices = BufferUtils.createFloatBuffer(6);

            vertices.put(-1F).put(-1F).put(3F).put(-1F).put(-1F).put(3F);
            vertices.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);

            GL20.glUseProgram(program);
            GL11.glDisable(GL11.GL_BLEND);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Source"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Size"), SIZE, SIZE);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode"), mode);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Grid"), columns, rows);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Scale"), scale);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Gap"), gap);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Rotation"), rotation);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Randomness"), randomness);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Seed"), seed);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Opacity"), 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Offset"), 0F, 0F);

            /* Identity mapping: the whole composition is the layer. */
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerU"), 1F, 0F, 0F);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerV"), 0F, -1F, 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerSize"), SIZE, SIZE);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerX"), 1F, 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerY"), 0F, 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerOrigin"), 0F, 0F);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            ByteBuffer output = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);

            int[] result = new int[SIZE * SIZE];

            for (int i = 0; i < result.length; i++)
            {
                int r = output.get(i * 4) & 0xFF;
                int g = output.get(i * 4 + 1) & 0xFF;
                int b = output.get(i * 4 + 2) & 0xFF;
                int a = output.get(i * 4 + 3) & 0xFF;

                result[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            return result;
        }
        finally
        {
            if (program != 0) GL20.glDeleteProgram(program);
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (target != 0) GL11.glDeleteTextures(target);
        }
    }

    private static int compile(int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println(GL20.glGetShaderInfoLog(shader));
        }

        return shader;
    }

    @Test
    public void oneCellRepeatIsIdentity()
    {
        int[] source = source();
        int[] output = render(1, 1, 1F, 0F, 0F, 0, 0F, 0F);

        assertArrayEquals(source, output, "1×1 grid, no gap, scale 1 must be an exact identity");
    }

    @Test
    public void gridRepeatsTheSameLocalSampleInEveryCell()
    {
        int[] output = render(2, 2, 1F, 0F, 0F, 0, 0F, 0F);

        /* Cell coordinates (4, 4) and (20, 4) are both local (0.25, 0.25) inside a cell. */
        int a = output[4 * SIZE + 4];
        int b = output[4 * SIZE + 20];

        assertEquals(a, b, "the same local sample must appear in each cell");
    }

    @Test
    public void gapProducesTransparentCellBorders()
    {
        int[] output = render(2, 2, 1F, 0.4F, 0F, 0, 0F, 0F);

        assertEquals(0, output[0] >>> 24, "gap must clear the cell border");
    }

    @Test
    public void randomRepeatIsSeedStableButCanDiffer()
    {
        int[] a = render(3, 3, 0.8F, 0.05F, 20F, 1, 17F, 1F);
        int[] b = render(3, 3, 0.8F, 0.05F, 20F, 1, 17F, 1F);
        int[] c = render(3, 3, 0.8F, 0.05F, 20F, 1, 18F, 1F);

        assertArrayEquals(a, b, "the same seed must give the same random layout");
        assertNotEquals(java.util.Arrays.hashCode(a), java.util.Arrays.hashCode(c), "a new seed should change the layout");
    }
}
