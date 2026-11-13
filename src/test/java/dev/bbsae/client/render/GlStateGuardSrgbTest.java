package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris renders into sRGB framebuffers. The compositor works in display space, so a pass has to
 * force GL_FRAMEBUFFER_SRGB off and restore the pack's setting afterwards. Leaving it on washed out
 * every layer under a shader pack; leaving it off permanently changed the world render after the
 * compositor closed.
 */
public class GlStateGuardSrgbTest
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
        window = GLFW.glfwCreateWindow(32, 32, "bbsae srgb guard", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        try
        {
            RenderSystem.initRenderThread();
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
    public void passDisablesSrgbAndRestoresIt()
    {
        GLFW.glfwMakeContextCurrent(window);
        GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
        assertTrue(GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB), "test setup could not enable sRGB");

        try (GlStateGuard ignored = new GlStateGuard())
        {
            assertFalse(GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB),
                "a composition pass must not write through the pack's sRGB conversion");
        }

        assertTrue(GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB),
            "the shader pack's sRGB state must be restored after the pass");
    }
}
