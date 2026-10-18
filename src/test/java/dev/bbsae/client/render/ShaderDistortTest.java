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
 * Pixel-level regression for the distortion family.
 *
 * <p>The previous releases shipped distortions whose strength was expressed in normalised UV while
 * the UI labelled it in pixels. On a 200 px title that made bulge/pinch/displacement invisible, so
 * "some distortions do nothing" was a real report. The shader now measures in pixels and uses a
 * half-diagonal radius; this test renders a 16×16 square and proves that a bulge pulls transparent
 * pixels outside the square into it.</p>
 */
public class ShaderDistortTest
{
    private static final int SIZE = 64;

    @Test
    public void bulgeSamplesOutsideTheSquare() throws Exception
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
            window = GLFW.glfwCreateWindow(64, 64, "bbsae distort check", 0L, 0L);

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
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseMap"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Vertical"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Size"), SIZE, SIZE);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Amount"), 0.8F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Angle"), 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Center"), 0.5F, 0.5F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Radius"), 0.9F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Frequency"), 0.1F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Phase"), 0F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Evolution"), 0F);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerU"), 1F, 0F, 0F);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerV"), 0F, 1F, 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerSize"), SIZE, SIZE);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerX"), 1F, 0F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerY"), 0F, 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerOrigin"), 0F, 0F);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            ByteBuffer output = BufferUtils.createByteBuffer(4);

            /* x=42 is outside the 24..39 square. A 0.8 bulge samples ~0.588 local, inside it. */
            GL11.glReadPixels(42, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);
            assertEquals(255, output.get(0) & 0xFF, "bulge must pull the square outward");
            assertEquals(255, output.get(3) & 0xFF, "bulge output must be opaque");

            /* The centre must stay opaque and white (no fold-through). */
            output.clear();
            GL11.glReadPixels(32, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);
            assertEquals(255, output.get(3) & 0xFF, "centre must remain inside the layer");
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
