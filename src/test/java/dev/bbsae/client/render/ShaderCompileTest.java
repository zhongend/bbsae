package dev.bbsae.client.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless compile check for every runtime GLSL source.
 *
 * <p>Minecraft itself is not launched and no screenshot is taken: a hidden GLFW window provides
 * the minimum OpenGL 3.2 core context needed to prove that the effect programs still compile and
 * link after a shader edit. On machines without a desktop the test is skipped rather than failed.</p>
 */
public class ShaderCompileTest
{
    @Test
    public void allRuntimeShadersCompileAndLink()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        long window = 0L;

        try
        {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(64, 64, "bbsae shader check", 0L, 0L);

            Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            String[] names = {
                "composite", "premultiply", "unpremultiply", "inner", "gradient", "blur",
                "sharpen", "radial", "color", "key", "generate", "distort", "pixel",
                "stroke", "transition", "depth", "shatter", "grade", "rgb_split"
            };
            String[] fragments = {
                ShaderSources.COMPOSITE,
                ShaderSources.PREMULTIPLY,
                ShaderSources.UNPREMULTIPLY,
                ShaderSources.INNER,
                ShaderSources.GRADIENT,
                ShaderSources.BLUR,
                ShaderSources.SHARPEN,
                ShaderSources.RADIAL,
                ShaderSources.COLOR,
                ShaderSources.KEY,
                ShaderSources.GENERATE,
                ShaderSources.DISTORT,
                ShaderSources.PIXEL,
                ShaderSources.STROKE,
                ShaderSources.TRANSITION,
                ShaderSources.DEPTH,
                ShaderSources.SHATTER,
                ShaderSources.GRADE,
                ShaderSources.RGB_SPLIT
            };

            for (int i = 0; i < fragments.length; i++)
            {
                int program = link(names[i], ShaderSources.VERTEX, fragments[i]);

                assertTrue(program != 0, "shader program " + names[i] + " failed to link");
                GL20.glDeleteProgram(program);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error after shader compile: " + names[i]);
            }
        }
        finally
        {
            if (window != 0L)
            {
                GLFW.glfwDestroyWindow(window);
            }

            if (GLFW.glfwInit())
            {
                GLFW.glfwTerminate();
            }
        }
    }

    private static int link(String name, String vertexSource, String fragmentSource)
    {
        int vertex = compile(name + " vertex", GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(name + " fragment", GL20.GL_FRAGMENT_SHADER, fragmentSource);

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
            System.err.println("Link failed (" + name + "): " + GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }

    private static int compile(String name, int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("Compile failed (" + name + "): " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    @Test
    public void compositeUsesStraightAlpha()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        long window = 0L;
        int program = 0;
        int texture = 0;
        int target = 0;
        int framebuffer = 0;
        int vao = 0;
        int vbo = 0;

        try
        {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(64, 64, "bbsae alpha check", 0L, 0L);

            Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            program = link("alpha composite", ShaderSources.VERTEX, ShaderSources.COMPOSITE);
            assertTrue(program != 0, "composite program failed to link");

            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            java.nio.ByteBuffer pixel = org.lwjgl.BufferUtils.createByteBuffer(4);

            pixel.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 128);
            pixel.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);

            target = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);

            vao = GL30.glGenVertexArrays();
            vbo = GL30.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            java.nio.FloatBuffer vertices = org.lwjgl.BufferUtils.createFloatBuffer(6);

            vertices.put(-1F).put(-1F).put(3F).put(-1F).put(-1F).put(3F);
            vertices.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);

            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Source"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseBackdrop"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseMask"), 0);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Opacity"), 1F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Offset"), 0F, 0F);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Tint"), 1F, 1F, 1F, 1F);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glViewport(0, 0, 1, 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            java.nio.ByteBuffer output = org.lwjgl.BufferUtils.createByteBuffer(4);

            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);

            int r = output.get(0) & 0xFF;
            int g = output.get(1) & 0xFF;
            int b = output.get(2) & 0xFF;
            int a = output.get(3) & 0xFF;

            assertEquals(255, r);
            assertEquals(0, g);
            assertEquals(0, b);
            assertEquals(128, a);
        }
        finally
        {
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (target != 0) GL11.glDeleteTextures(target);
            if (program != 0) GL20.glDeleteProgram(program);
            if (window != 0L) GLFW.glfwDestroyWindow(window);
            if (GLFW.glfwInit()) GLFW.glfwTerminate();
        }
    }
}
