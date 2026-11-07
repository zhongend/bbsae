package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
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
 * Pixel-level regression for the blend set.
 *
 * <p>Each case composites one solid source pixel over one solid backdrop pixel through
 * {@code ShaderSources.COMPOSITE} and compares the produced pixel with the documented formula.
 * The two mask modes are alpha operations, so they are checked on the alpha channel with the
 * backdrop colour preserved; the colour modes are checked on RGB.</p>
 */
public class BlendModesTest
{
    @Test
    public void blendModesProduceTheDocumentedPixels()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        long window = 0L;
        int program = 0;
        int vao = 0;
        int vbo = 0;
        int source = 0;
        int backdrop = 0;
        int target = 0;
        int framebuffer = 0;

        try
        {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(64, 64, "bbsae blend check", 0L, 0L);

            Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            program = link();
            assertTrue(program != 0, "composite program failed to link");

            vao = GL30.glGenVertexArrays();
            vbo = GL30.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            java.nio.FloatBuffer vertices = BufferUtils.createFloatBuffer(6);

            vertices.put(-1F).put(-1F).put(3F).put(-1F).put(-1F).put(3F);
            vertices.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);

            source = texture(255, 0, 0, 255);
            backdrop = texture(0, 0, 255, 255);
            target = texture(0, 0, 0, 0);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            GL11.glViewport(0, 0, 1, 1);

            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Source"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Backdrop"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseMask"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseBackdrop"), 1);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Offset"), 0F, 0F);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Tint"), 1F, 1F, 1F, 1F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Opacity"), 1F);
            GL11.glDisable(GL11.GL_BLEND);

            /* Normal: source opaque red over opaque blue. */
            int[] normal = pixel(program, source, backdrop, CompLayer.BLEND_NORMAL, 255);

            assertPixel(255, 0, 0, 255, normal, "normal");

            /* Mask keeps the backdrop colour and multiplies alpha by the source alpha. */
            int[] mask = pixel(program, source, backdrop, CompLayer.BLEND_MASK, 128);

            assertPixel(0, 0, 255, 128, mask, "mask");

            /* Exclude does the same with (1 - source alpha). */
            int[] exclude = pixel(program, source, backdrop, CompLayer.BLEND_EXCLUDE, 128);

            assertPixel(0, 0, 255, 128, exclude, "exclude");

            /* Multiply of 0.5 over 0.5 is 0.25. */
            int[] multiply = pixel(program, texture(128, 128, 128, 255), texture(128, 128, 128, 255),
                CompLayer.BLEND_MULTIPLY, 255);

            assertPixel(64, 64, 64, 255, multiply, "multiply");

            /* Linear burn: s + b - 1 with 0.6 + 0.6 - 1 = 0.2. */
            int[] burn = pixel(program, texture(153, 153, 153, 255), texture(153, 153, 153, 255),
                CompLayer.BLEND_LINEAR_BURN, 255);

            assertPixel(51, 51, 51, 255, burn, "linear burn");

            /* Subtract: b - s with 0.8 - 0.3 = 0.5. */
            int[] subtract = pixel(program, texture(77, 77, 77, 255), texture(204, 204, 204, 255),
                CompLayer.BLEND_SUBTRACT, 255);

            assertPixel(128, 128, 128, 255, subtract, "subtract");

            /* Linear dodge: b + s clamps to 1. */
            int[] dodge = pixel(program, texture(204, 204, 204, 255), texture(204, 204, 204, 255),
                CompLayer.BLEND_LINEAR_DODGE, 255);

            assertPixel(255, 255, 255, 255, dodge, "linear dodge");

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error after blend cases");
        }
        finally
        {
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            for (int t : new int[] {source, backdrop, target})
            {
                if (t != 0) GL11.glDeleteTextures(t);
            }
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (program != 0) GL20.glDeleteProgram(program);
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int[] pixel(int program, int source, int backdrop, int blend, int sourceAlpha)
    {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
        GL11.glViewport(0, 0, 1, 1);
        GL11.glClearColor(0F, 0F, 0F, 0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Blend"), blend);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "Opacity"), sourceAlpha / 255F);
        GL20.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
        GL20.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdrop);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        ByteBuffer out = BufferUtils.createByteBuffer(4);

        GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);

        return new int[] {out.get(0) & 0xFF, out.get(1) & 0xFF, out.get(2) & 0xFF, out.get(3) & 0xFF};
    }

    private static int texture(int r, int g, int b, int a)
    {
        int texture = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);

        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        pixel.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        return texture;
    }

    private static void assertPixel(int r, int g, int b, int a, int[] actual, String mode)
    {
        assertEquals(r, actual[0], 3, mode + " red: " + describe(actual));
        assertEquals(g, actual[1], 3, mode + " green: " + describe(actual));
        assertEquals(b, actual[2], 3, mode + " blue: " + describe(actual));
        assertEquals(a, actual[3], 3, mode + " alpha: " + describe(actual));
    }

    private static String describe(int[] pixel)
    {
        return "got " + pixel[0] + "," + pixel[1] + "," + pixel[2] + "," + pixel[3];
    }

    private static int link()
    {
        int vertex = compile(GL20.GL_VERTEX_SHADER, ShaderSources.VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, ShaderSources.COMPOSITE);

        if (vertex == 0 || fragment == 0)
        {
            return 0;
        }

        int program = GL20.glCreateProgram();

        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("Composite link failed: " + GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);

            return 0;
        }

        return program;
    }

    private static int compile(int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("Composite compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);

            return 0;
        }

        return shader;
    }
}
