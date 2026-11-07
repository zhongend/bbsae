package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3D workspace resolves layer crossings with the depth buffer. This test draws two overlapping
 * quads at different z and checks the nearer one wins in both draw orders — the whole-layer painter
 * order that the draft path used before would have painted the second quad over the first one no
 * matter which was in front.
 */
public class Viewport3DRendererTest
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
        Viewport3DRenderer.delete();

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

    private static GlTexture solid(int argb)
    {
        GlTexture texture = new GlTexture();

        texture.allocate(2, 2);
        texture.uploadTopDown(new int[] {argb, argb, argb, argb});

        return texture;
    }

    private static int center(int[] pixels)
    {
        return pixels[(SIZE / 2) * SIZE + SIZE / 2];
    }

    private static int corner(int[] pixels)
    {
        return pixels[2 * SIZE + 2];
    }

    private static int[] render(boolean nearFirst)
    {
        int red = 0xFFFF0000;
        int blue = 0xFF0000FF;
        GlTexture redTexture = solid(red);
        GlTexture blueTexture = solid(blue);
        GlFramebuffer target = new GlFramebuffer(SIZE, SIZE, true);
        Matrix4f projection = new Matrix4f().setOrtho(0F, SIZE, SIZE, 0F, -100F, 100F);

        target.begin(0x00000000);

        if (nearFirst)
        {
            drawQuad(blueTexture, projection, 40F);
            drawQuad(redTexture, projection, 0F);
        }
        else
        {
            drawQuad(redTexture, projection, 0F);
            drawQuad(blueTexture, projection, 40F);
        }

        int[] pixels = target.readTopDown();

        target.delete();
        redTexture.delete();
        blueTexture.delete();

        return pixels;
    }

    private static void drawQuad(GlTexture texture, Matrix4f projection, float z)
    {
        /* The quad covers the middle of the frame; the outer ring shows the far layer. */
        float size = z > 0F ? 16F : SIZE;
        float origin = (SIZE - size) * 0.5F;
        Matrix4f world = new Matrix4f().translate(0F, 0F, z);

        Viewport3DRenderer.draw(texture, world, projection, origin, origin, origin + size, origin + size, 1F);
    }

    private static int[] renderPair(int farArgb, int nearArgb, boolean farFirst)
    {
        GlTexture farTexture = solid(farArgb);
        GlTexture nearTexture = solid(nearArgb);
        GlFramebuffer target = new GlFramebuffer(SIZE, SIZE, true);
        Matrix4f projection = new Matrix4f().setOrtho(0F, SIZE, SIZE, 0F, -100F, 100F);

        target.begin(0x00000000);

        if (farFirst)
        {
            drawQuad(farTexture, projection, 0F);
            drawQuad(nearTexture, projection, 40F);
        }
        else
        {
            drawQuad(nearTexture, projection, 40F);
            drawQuad(farTexture, projection, 0F);
        }

        int[] pixels = target.readTopDown();

        target.delete();
        farTexture.delete();
        nearTexture.delete();

        return pixels;
    }

    @Test
    public void translucentLayersBlendInsteadOfOccluding()
    {
        Assumptions.assumeTrue(Viewport3DRenderer.available(), "viewport 3D program unavailable");

        int[] nearTranslucent = renderPair(0xFFFF0000, 0x600000FF, true);

        assertTrue(((center(nearTranslucent) >> 16) & 0xFF) > 40,
            "far red must show through a near translucent layer: " + Integer.toHexString(center(nearTranslucent)));
        assertTrue((center(nearTranslucent) & 0xFF) > 40, "near blue must contribute");

        int[] farTranslucent = renderPair(0x60FF0000, 0xFF0000FF, false);

        assertEquals(0xFF0000FF, center(farTranslucent), "opaque near blue hides the translucent far red");
    }

    @Test
    public void theNearerQuadWinsWhateverTheDrawOrder()
    {
        Assumptions.assumeTrue(Viewport3DRenderer.available(), "viewport 3D program unavailable");

        int[] farFirst = render(false);
        int[] nearFirst = render(true);

        assertEquals(0xFF0000FF, center(farFirst), "the nearer blue quad must own the centre");
        assertEquals(0xFF0000FF, center(nearFirst), "drawing the near quad first must still win");
        assertEquals(0xFFFF0000, corner(farFirst), "outside the near quad the far red one shows");
        assertEquals(0xFFFF0000, corner(nearFirst), "depth must not be overwritten by the far quad");
    }

    @Test
    public void theDepthProgramLinked()
    {
        assertTrue(CompShaders.viewport3d != null && CompShaders.viewport3d.isValid(),
            "viewport 3d shader must link");
    }
}
