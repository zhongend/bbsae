package dev.bbsae.client.render;

import org.junit.jupiter.api.Assumptions;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel-level regression for 波浪变形 (DISTORT mode 4).
 *
 * <p>The wave used to be invisible: its pixel displacement was divided by the layer size once in
 * Java-style UV units and then again multiplied through a layer basis whose columns are already
 * "comp pixels per layer pixel", so a 24 px amplitude moved the image by a fraction of a pixel.
 * This test renders a square and proves a 12 px wave genuinely pulls content from outside it.</p>
 */
public class ShaderWaveTest
{
    private static final int SIZE = 64;

    @Test
    public void waveMovesPixelsByTheAmplitude() throws Exception
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        long window = 0L;
        int program = 0;
        int vao = 0;
        int vbo = 0;
        int source = 0;
        int target = 0;
        int fbo = 0;

        try
        {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(SIZE, SIZE, "bbsae wave check", 0L, 0L);

            Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            program = link(ShaderSources.DISTORT);
            assertTrue(program != 0, "distort program failed to link");

            source = texture();
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

            for (int y = 0; y < SIZE; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    boolean inside = x >= 24 && x < 40 && y >= 24 && y < 40;

                    pixels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (inside ? 255 : 0));
                }
            }

            pixels.flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

            target = texture();
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);

            vao = GL30.glGenVertexArrays();
            vbo = GL30.glGenBuffers();
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
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Source"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Map"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode"), 4);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseMap"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Vertical"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Size"), SIZE, SIZE);
            /* 12 px amplitude, one wavelength across the layer: at u where sin = -1 the wave
             * samples 12 px to the left. */
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Amount"), 12F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Frequency"), (float) (Math.PI * 2D / SIZE));
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Phase"), 0F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Angle"), 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Center"), 0.5F, 0.5F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Radius"), 0.9F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Evolution"), 0F);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerU"), 1F, 0F, 0F);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerV"), 0F, 1F, 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerSize"), SIZE, SIZE);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerX"), 1F, 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerY"), 0F, 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "CompSize"), SIZE, SIZE);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerOrigin"), 0F, 0F);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            ByteBuffer output = BufferUtils.createByteBuffer(4);

            /* The centre row (luv.y = 0.5) at x = 45: travel 45 px into a 64 px wavelength gives
             * sin ≈ -0.996, so the shader samples ≈ 11.9 px to the left — inside the square
             * (33.1). With the old double division the displacement was ~0.19 px and this pixel
             * sampled outside the square (transparent). */
            GL11.glReadPixels(45, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);
            assertEquals(255, output.get(3) & 0xFF, "the wave must displace by the full amplitude, not by amplitude/layerSize");
            assertEquals(255, output.get(0) & 0xFF, "the displaced sample lands on the square's interior");

            /* Where the sine crosses zero (travel = 0 → x = 0) the wave displacement is 0, so the
             * untouched outside of the square stays outside. */
            output.clear();
            GL11.glReadPixels(0, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);
            assertEquals(0, output.get(3) & 0xFF, "where the sine crosses zero the image must not move");
        }
        finally
        {
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (source != 0) GL11.glDeleteTextures(source);
            if (target != 0) GL11.glDeleteTextures(target);
            if (program != 0) GL20.glDeleteProgram(program);
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            if (GLFW.glfwInit()) GLFW.glfwTerminate();
        }
    }

    private static int texture()
    {
        int texture = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        return texture;
    }

    private static int link(String fragmentSource)
    {
        int vertex = compile(GL20.GL_VERTEX_SHADER, ShaderSources.VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);

        int program = GL20.glCreateProgram();

        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);

        return GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE ? 0 : program;
    }

    private static int compile(int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        return GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE ? 0 : shader;
    }
}
