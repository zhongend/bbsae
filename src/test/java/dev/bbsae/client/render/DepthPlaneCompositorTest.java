package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-pixel depth compositing for 3D layers in the main composition.
 *
 * <p>Two full-frame planes at different z are composited in both orders. The nearer one must own the
 * result whichever order they are drawn in; whole-layer painter order would paint the second one on
 * top in the far-first case.</p>
 */
public class DepthPlaneCompositorTest
{
    private static final int SIZE = 64;
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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae test", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        try
        {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        }
        catch (IllegalStateException ignored)
        {
            /* Another GL test class in this JVM already owns the global render thread. */
        }

        CompShaders.reload();
        CompShaders.init();
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

    private static GlFramebuffer solid(int argb)
    {
        GlFramebuffer framebuffer = new GlFramebuffer(SIZE, SIZE);
        int[] pixels = new int[SIZE * SIZE];

        for (int i = 0; i < pixels.length; i++)
        {
            pixels[i] = argb;
        }

        framebuffer.color.uploadTopDown(pixels);

        return framebuffer;
    }

    private static int[] composite(boolean nearFirst)
    {
        CompCamera camera = CompCamera.identity(SIZE, SIZE);
        GlFramebuffer red = solid(0xFFFF0000);
        GlFramebuffer blue = solid(0xFF0000FF);
        GlFramebuffer accumulation = new GlFramebuffer(SIZE, SIZE);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(SIZE, SIZE);

        accumulation.begin(0x00000000);

        Matrix4f farWorld = new Matrix4f().translate(0F, 0F, 0F);
        Matrix4f nearWorld = new Matrix4f().translate(0F, 0F, 30F);

        if (nearFirst)
        {
            accumulation = compositor.composite(blue, nearWorld, camera, accumulation, CompLayer.BLEND_NORMAL, 1F);
            accumulation = compositor.composite(red, farWorld, camera, accumulation, CompLayer.BLEND_NORMAL, 1F);
        }
        else
        {
            accumulation = compositor.composite(red, farWorld, camera, accumulation, CompLayer.BLEND_NORMAL, 1F);
            accumulation = compositor.composite(blue, nearWorld, camera, accumulation, CompLayer.BLEND_NORMAL, 1F);
        }

        int[] pixels = accumulation.readTopDown();

        compositor.delete();
        accumulation.delete();
        red.delete();
        blue.delete();

        return pixels;
    }

    private static int center(int[] pixels)
    {
        return pixels[(SIZE / 2) * SIZE + SIZE / 2];
    }

    @Test
    public void theNearerPlaneOwnsTheResultInBothOrders()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        int[] farFirst = composite(false);
        int[] nearFirst = composite(true);

        assertEquals(0xFF0000FF, center(farFirst), "drawing far first must still end with the near layer");
        assertEquals(0xFF0000FF, center(nearFirst), "drawing near first must keep it in front");
        assertTrue(CompShaders.depthPlane.isValid(), "depth plane program must link");
    }
}
