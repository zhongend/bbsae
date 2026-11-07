package dev.bbsae.client.render;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The matte sizes itself from the world framebuffer's attachment, not from a guessed viewport. */
public class FramebufferSizeTest
{
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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae framebuffer size check", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        try
        {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        }
        catch (IllegalStateException ignored)
        {
        }
    }

    @AfterAll
    public static void destroyContext()
    {
        if (window != 0L)
        {
            GLFW.glfwDestroyWindow(window);
            window = 0L;
        }

        if (GLFW.glfwInit())
        {
            GLFW.glfwTerminate();
        }
    }

    @Test
    public void aTextureBackedTargetReportsItsRealSize()
    {
        GlFramebuffer framebuffer = new GlFramebuffer(48, 32);
        int[] size = FramebufferSize.of(framebuffer.id);

        assertNotNull(size);
        assertEquals(48, size[0]);
        assertEquals(32, size[1]);
        framebuffer.delete();
    }

    @Test
    public void everyTargetSizeIsReportedIndependently()
    {
        GlFramebuffer first = new GlFramebuffer(64, 48);
        GlFramebuffer second = new GlFramebuffer(20, 12);

        assertEquals(64, FramebufferSize.of(first.id)[0]);
        assertEquals(12, FramebufferSize.of(second.id)[1]);

        first.delete();
        second.delete();
    }

    @Test
    public void theDefaultFramebufferIsNotQueried()
    {
        assertNull(FramebufferSize.of(0));
    }
}
