package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-GL half of the mask regression suite.
 *
 * <p>{@link MaskPathRasterTest} proves the CPU coverage; this one proves that the coverage really
 * reaches a texture, that open/degenerate paths do not produce a matte texture at all, and that a
 * 3D-ish world matrix rotates the coverage instead of dropping it. A hidden GL 3.2 window is used,
 * the same pattern as {@code ShaderPixelTest}; the class is skipped when no display is available.</p>
 */
public class MaskGlRenderTest
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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae mask check", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        /* GlTexture goes through Minecraft's GlStateManager, which asserts that the calling thread
         * is the render thread. These headless tests own the only GL context, so declaring this
         * thread to be it is exactly right. Another GL test class in this JVM may already have done
         * so (the flag is global); that is not an error here. */
        try
        {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        }
        catch (IllegalStateException ignored)
        {
            /* Already initialized by an earlier hidden-context test class. */
        }

        MaskCache.clear();
    }

    @AfterAll
    public static void destroyContext()
    {
        MaskCache.clear();

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

    private static CompLayer layer(CompMask mask)
    {
        CompLayer layer = new CompLayer("gl_layer", "GL Layer", "solid");

        layer.width = SIZE;
        layer.height = SIZE;
        layer.masks.add(mask);

        return layer;
    }

    private static float[] readAlpha(GlTexture texture)
    {
        ByteBuffer buffer = BufferUtils.createByteBuffer(texture.width * texture.height * 4);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.id);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        float[] alpha = new float[texture.width * texture.height];

        /* uploadTopDown() flips the image rows into GL order, and glGetTexImage hands them back in
         * GL order; flip once here so array indices are image (x, y) coordinates like every other
         * assertion in this test. */
        for (int y = 0; y < texture.height; y++)
        {
            int imageRow = texture.height - 1 - y;

            for (int x = 0; x < texture.width; x++)
            {
                alpha[imageRow * texture.width + x] = (buffer.get((y * texture.width + x) * 4 + 3) & 0xFF) / 255F;
            }
        }

        return alpha;
    }

    private static float centroidX(float[] alpha)
    {
        float sum = 0F;
        int count = 0;

        for (int i = 0; i < alpha.length; i++)
        {
            if (alpha[i] > 0.5F)
            {
                sum += i % SIZE;
                count += 1;
            }
        }

        return count == 0 ? -1F : sum / count;
    }

    private static float centroidY(float[] alpha)
    {
        float sum = 0F;
        int count = 0;

        for (int i = 0; i < alpha.length; i++)
        {
            if (alpha[i] > 0.5F)
            {
                sum += i / SIZE;
                count += 1;
            }
        }

        return count == 0 ? -1F : sum / count;
    }

    private static float coverage(float[] alpha)
    {
        int covered = 0;

        for (float value : alpha)
        {
            if (value > 0.5F)
            {
                covered += 1;
            }
        }

        return covered / (float) alpha.length;
    }

    @Test
    public void closedMaskUploadsItsCoverage()
    {
        CompMask mask = new CompMask("square");

        mask.rectangle(10F, 10F, 20F, 20F);

        MaskCache.MaskTexture texture = MaskCache.get(layer(mask), SIZE, SIZE, null, 0F);

        assertNotNull(texture, "a closed mask must produce a matte texture");
        assertEquals(SIZE, texture.width);
        assertEquals(SIZE, texture.height);

        float[] alpha = readAlpha(texture.texture);

        assertEquals(400F / (SIZE * SIZE), coverage(alpha), 0.02F, "20x20 square coverage");
        assertEquals(1F, alpha[20 * SIZE + 20], 0.01F, "inside is opaque");
        assertEquals(0F, alpha[2 * SIZE + 2], 0.01F, "outside is transparent");
    }

    @Test
    public void openPathMatsAndDegeneratePathsDoNot()
    {
        CompMask open = new CompMask("open");

        open.rectangle(10F, 10F, 20F, 20F);
        open.closed = false;
        MaskCache.MaskTexture openTexture = MaskCache.get(layer(open), SIZE, SIZE, null, 0F);
        assertNotNull(openTexture, "an open 4-point path is filled by its implicit closing edge");
        assertEquals(400F / (SIZE * SIZE), coverage(readAlpha(openTexture.texture)), 0.02F);

        CompMask twoPoints = new CompMask("two");

        twoPoints.add(0F, 0F);
        twoPoints.add(20F, 0F);
        assertNull(MaskCache.get(layer(twoPoints), SIZE, SIZE, null, 0F), "two points must not matte");

        CompMask onePoint = new CompMask("one");

        onePoint.add(5F, 5F);
        assertNull(MaskCache.get(layer(onePoint), SIZE, SIZE, null, 0F), "one point must not matte");
    }

    @Test
    public void rotatedWorldMatrixMovesCoverageInsteadOfDroppingIt()
    {
        CompMask mask = new CompMask("rotated");

        mask.rectangle(22F, 22F, 20F, 20F);

        Matrix4f world = new Matrix4f()
            .translate(32F, 32F, 0F)
            .rotateZ((float) Math.toRadians(30D))
            .translate(-32F, -32F, 0F);

        MaskCache.MaskTexture texture = MaskCache.get(layer(mask), SIZE, SIZE, world, 0F);

        assertNotNull(texture);

        float[] alpha = readAlpha(texture.texture);

        assertEquals(400F / (SIZE * SIZE), coverage(alpha), 0.03F, "rotation preserves the area");
        assertEquals(1F, alpha[32 * SIZE + 32], 0.01F, "the rotated square still covers its centre");
        assertEquals(0F, alpha[2 * SIZE + 2], 0.5F, "the far corner is outside the rotated square");
    }

    @Test
    public void worldRotationFollowsTheSceneMatrix()
    {
        CompMask mask = new CompMask("direction");

        mask.rectangle(5F, 5F, 10F, 10F);

        /* Local centre (10,10); rotate +90 degrees about the origin (JOML: +x -> +y) and translate
         * to the composition centre. Expected raster centre: (-10,10) + (32,32) = (22,42). A
         * transposed 2D part would put it at (42,22) instead. */
        Matrix4f world = new Matrix4f()
            .translate(32F, 32F, 0F)
            .rotateZ((float) Math.toRadians(90D));

        MaskCache.MaskTexture texture = MaskCache.get(layer(mask), SIZE, SIZE, world, 0F);

        assertNotNull(texture);

        float[] alpha = readAlpha(texture.texture);

        assertEquals(22F, centroidX(alpha), 1.5F, "rotation direction must match the scene matrix");
        assertEquals(42F, centroidY(alpha), 1.5F, "rotation direction must match the scene matrix");
    }

    @Test
    public void cubicBezierEllipseRasterizes()
    {
        CompMask mask = new CompMask("ellipse");

        mask.ellipse(32F, 32F, 20F, 20F);

        MaskCache.MaskTexture texture = MaskCache.get(layer(mask), SIZE, SIZE, null, 0F);

        assertNotNull(texture);

        float[] alpha = readAlpha(texture.texture);
        float fraction = coverage(alpha);

        assertEquals((float) (Math.PI * 20D * 20D) / (SIZE * SIZE), fraction, 0.04F,
            "four cubic segments approximate a circle");
        assertEquals(1F, alpha[32 * SIZE + 32], 0.01F);
        assertEquals(0F, alpha[1 * SIZE + 1], 0.01F);
    }

    @Test
    public void identicalGeometryReusesTheCachedTexture()
    {
        CompMask mask = new CompMask("cached");

        mask.rectangle(8F, 8F, 16F, 16F);

        CompLayer layer = layer(mask);
        MaskCache.MaskTexture first = MaskCache.get(layer, SIZE, SIZE, null, 0F);
        MaskCache.MaskTexture second = MaskCache.get(layer, SIZE, SIZE, null, 0F);

        assertNotNull(first);
        assertSame(first, second, "the alpha texture is cached by geometry hash");

        /* Opening the path still mattes, but it misses the cache because closed is part of the
         * geometry hash. The cache may reuse the same texture object and refresh its contents. */
        mask.closed = false;
        MaskCache.MaskTexture third = MaskCache.get(layer, SIZE, SIZE, null, 0F);
        assertNotNull(third, "an open 4-point mask still fills");
        assertEquals(256F / (SIZE * SIZE), coverage(readAlpha(third.texture)), 0.02F);
    }

    @Test
    public void expansionAndFeatherStillProduceATexture()
    {
        CompMask mask = new CompMask("soft");

        mask.rectangle(16F, 16F, 16F, 16F);
        mask.feather = 6F;
        mask.expansion = 3F;

        MaskCache.MaskTexture texture = MaskCache.get(layer(mask), SIZE, SIZE, null, 0F);

        assertNotNull(texture);

        float[] alpha = readAlpha(texture.texture);
        int soft = 0;

        for (float value : alpha)
        {
            if (value > 0.01F && value < 0.99F)
            {
                soft += 1;
            }
        }

        assertTrue(soft > 40, "feather must create a soft edge, got " + soft + " fractional pixels");
    }
}
