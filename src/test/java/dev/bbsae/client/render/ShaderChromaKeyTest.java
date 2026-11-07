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
 * Pixel-level regression for 色度抠像 (KEY mode 0).
 *
 * <p>The old keyer measured RGB distance to the key colour. Every pixel that was "the right hue at
 * the wrong brightness" — a lit corner of a green screen, a yellowish green — kept a translucent
 * green veil, and every effect stacked on top re-mixed that veil into the frame. The new keyer
 * works on hue (brightness-independent chroma) with a saturation gate and a screen-matte clip, so
 * these cases must key out fully while genuine footage colours survive.</p>
 */
public class ShaderChromaKeyTest
{
    private static final int SIZE = 64;

    /* Column x holds one case: 0 = pure screen green, 1 = dark green, 2 = yellowish green
     * (the classic "浅绿色残留"), 3 = white, 4 = red. */
    private static final int[] CASE_R = {0, 0, 51, 255, 255};
    private static final int[] CASE_G = {255, 128, 255, 255, 0};
    private static final int[] CASE_B = {0, 0, 26, 255, 0};

    @Test
    public void hueBasedKeyerCleansTheScreenColours() throws Exception
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
            window = GLFW.glfwCreateWindow(SIZE, SIZE, "bbsae key check", 0L, 0L);

            Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            program = link(ShaderSources.KEY);
            assertTrue(program != 0, "key program failed to link");

            source = texture();
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

            for (int y = 0; y < SIZE; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    int column = Math.min(x / 13, 4);

                    pixels.put((byte) CASE_R[column]).put((byte) CASE_G[column]).put((byte) CASE_B[column]).put((byte) 255);
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
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode"), 0);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "KeyColor"), 0F, 1F, 0F, 1F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Tolerance"), 0.18F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Softness"), 0.08F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Spill"), 1F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Threshold"), 0.3F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Invert"), 0F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Choke"), 0F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "ClipBlack"), 0F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "ClipWhite"), 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Texel"), 1F / SIZE, 1F / SIZE);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Size"), SIZE, SIZE);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            /* Screen colours — including shaded and off-hue green — must go fully transparent. */
            assertAlpha(fbo, 6, 0, "pure green must key out");
            assertAlpha(fbo, 19, 0, "shaded green (dark) must key out like pure green");
            assertAlpha(fbo, 32, 0, "yellowish green (the residual-veil case) must key out");

            /* The yellowish-green case is exactly the old keyer's blind spot: RGB distance ≈ 0.22
             * sat below the old tolerance ceiling and survived as a translucent veil. */
            ByteBuffer output = BufferUtils.createByteBuffer(4);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glReadPixels(32, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);

            assertTrue((output.get(3) & 0xFF) <= 2, "the veil pixel's alpha must be fully keyed, got " + (output.get(3) & 0xFF));
            assertTrue((output.get(1) & 0xFF) <= 55, "despill must remove the green above the other channels, got g=" + (output.get(1) & 0xFF));

            /* Footage colours survive at full alpha. */
            assertAlpha(fbo, 45, 255, "white must survive the key");
            assertAlpha(fbo, 58, 255, "red must survive the key");
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

    private static void assertAlpha(int fbo, int x, int expected, String message)
    {
        ByteBuffer output = BufferUtils.createByteBuffer(4);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glReadPixels(x, 32, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);

        int alpha = output.get(3) & 0xFF;

        if (expected == 0)
        {
            assertTrue(alpha <= 2, message + ", got alpha " + alpha);
        }
        else
        {
            assertTrue(alpha >= expected - 2, message + ", got alpha " + alpha);
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
