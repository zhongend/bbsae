package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompShape;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-GL half of the mask/shape cache tests: the cache reuses one texture per layer+size even
 * when the transform changes every frame, and the uploaded pixels still follow the transform.
 *
 * <p>The CPU tests ({@link MaskCacheKeyTest}, {@link ShapeCacheTest}) prove the identities and the
 * pixel parity; this class proves that the reused texture really contains the new pixels (the CPU
 * array could be right while the GL upload was skipped). Skipped when no display is available, like
 * the other hidden-context tests.</p>
 */
public class CacheTextureReuseGlTest
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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae cache check", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        try
        {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        }
        catch (IllegalStateException ignored)
        {
            /* Already initialized by an earlier hidden-context test class. */
        }

        MaskCache.clear();
        ShapeCache.clear();
    }

    @AfterAll
    public static void destroyContext()
    {
        MaskCache.clear();
        ShapeCache.clear();

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

    private static CompLayer maskLayer(String id, CompMask mask)
    {
        CompLayer layer = new CompLayer(id, "Mask", "solid");

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

    @Test
    public void staticMaskReturnsTheSameTextureFor120Frames()
    {
        CompMask mask = new CompMask("static");

        mask.rectangle(16F, 16F, 32F, 32F);

        CompLayer layer = maskLayer("gl_static", mask);
        Matrix4f world = new Matrix4f();
        MaskCache.MaskTexture first = MaskCache.get(layer, SIZE, SIZE, world, 0F);

        assertNotNull(first);

        for (int frame = 1; frame <= 120; frame++)
        {
            assertSame(first, MaskCache.get(layer, SIZE, SIZE, world, frame / 30F),
                "frame " + frame + " must not allocate a new mask texture");
        }
    }

    @Test
    public void movingCameraReusesTheSlotAndReuploadsthePixels()
    {
        CompMask mask = new CompMask("moving_camera");

        mask.rectangle(16F, 16F, 20F, 20F);

        CompLayer layer = maskLayer("gl_camera", mask);
        MaskCache.MaskTexture first = MaskCache.get(layer, SIZE, SIZE, new Matrix4f(), 0F);

        assertNotNull(first);

        float before = centroidX(readAlpha(first.texture));
        MaskCache.MaskTexture moved = MaskCache.get(layer, SIZE, SIZE,
            new Matrix4f().translate(16F, 0F, 0F), 0F);

        assertSame(first, moved, "the world matrix must not create a new texture slot");
        assertEquals(before + 16F, centroidX(readAlpha(moved.texture)), 1.5F,
            "the reused texture holds the newly rasterized pixels");
    }

    @Test
    public void animatedMaskUpdatesPixelsInTheReusedTexture()
    {
        CompMask mask = new CompMask("animated");

        mask.rectangle(10F, 10F, 16F, 16F);

        CompLayer layer = maskLayer("gl_animated", mask);

        layer.scalarChannel("mask/animated/p/0/x").insert(0F, 10F);
        layer.scalarChannel("mask/animated/p/0/x").insert(10F, 30F);
        layer.scalarChannel("mask/animated/p/1/x").insert(0F, 26F);
        layer.scalarChannel("mask/animated/p/1/x").insert(10F, 46F);
        layer.scalarChannel("mask/animated/p/2/x").insert(0F, 26F);
        layer.scalarChannel("mask/animated/p/2/x").insert(10F, 46F);
        layer.scalarChannel("mask/animated/p/3/x").insert(0F, 10F);
        layer.scalarChannel("mask/animated/p/3/x").insert(10F, 30F);

        MaskCache.MaskTexture start = MaskCache.get(layer, SIZE, SIZE, null, 0F);

        assertNotNull(start);

        float before = centroidX(readAlpha(start.texture));
        MaskCache.MaskTexture later = MaskCache.get(layer, SIZE, SIZE, null, 10F);

        assertSame(start, later, "an animated mask re-uploades into the same slot");
        assertEquals(before + 20F, centroidX(readAlpha(later.texture)), 1.5F,
            "the animated point positions are really uploaded");
    }

    private static CompLayer shapeLayer(String id, CompShape shape)
    {
        CompLayer layer = new CompLayer(id, "Shape", "shape");

        layer.width = 400F;
        layer.height = 300F;
        layer.shapes.add(shape);

        return layer;
    }

    @Test
    public void staticShapeReturnsTheSameRaster()
    {
        CompShape shape = CompShape.rect("rect", 100F, 80F, 120F, 90F, 0xFF20C0E0);
        CompLayer layer = shapeLayer("gl_shape_static", shape);
        ShapeCache.Raster first = ShapeCache.get(layer, 0F);

        assertNotNull(first);
        assertSame(first, ShapeCache.get(layer, 0F), "identical frame reuses the cached raster");
    }

    @Test
    public void positionKeyframedShapeReusesTheTextureAndMovesOnlyTheOrigin()
    {
        CompShape shape = CompShape.rect("rect", 100F, 80F, 120F, 90F, 0xFF20C0E0);
        CompLayer layer = shapeLayer("gl_shape_moving", shape);

        layer.scalarChannel("shape/rect/x").insert(0F, 100F);
        layer.scalarChannel("shape/rect/x").insert(10F, 150F);

        ShapeCache.Raster start = ShapeCache.get(layer, 0F);

        assertNotNull(start);

        ShapeCache.Raster moved = ShapeCache.get(layer, 10F);

        assertNotNull(moved);
        assertNotSame(start, moved, "the returned raster carries the new origin");
        assertSame(start.texture, moved.texture, "the GL texture is reused instead of reallocated");
        assertEquals(start.originX + 50F, moved.originX, 0.01F);
        assertEquals(start.originY, moved.originY, 0.01F);
        assertEquals(start.width, moved.width);
        assertEquals(start.height, moved.height);
    }

    @Test
    public void rotatingShapeReusesTheTextureWhenTheSizeDoesNotChange()
    {
        /* A square rotated by 90 degrees has exactly the same bounds, so the texture size is
         * stable and the slot can be reused even though the pixels change. */
        CompShape shape = CompShape.rect("square", 120F, 100F, 80F, 80F, 0xFFFFFFFF);
        CompLayer layer = shapeLayer("gl_shape_rotating", shape);

        layer.scalarChannel("shape/square/rotation").insert(0F, 0F);
        layer.scalarChannel("shape/square/rotation").insert(10F, 90F);

        ShapeCache.Raster start = ShapeCache.get(layer, 0F);
        ShapeCache.Raster rotated = ShapeCache.get(layer, 10F);

        assertNotNull(start);
        assertNotNull(rotated);
        assertEquals(start.width, rotated.width, "90 degree rotation keeps the bounds");
        assertEquals(start.height, rotated.height);
        assertSame(start.texture, rotated.texture, "the texture slot is reused");
    }
}
