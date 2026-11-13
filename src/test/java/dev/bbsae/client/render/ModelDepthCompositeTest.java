package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pixel test for the model-depth composite: a plane in front of the model must keep the plane's
 * pixels, a plane behind it must not hide the model, and the result must be per pixel rather than
 * per whole layer.
 *
 * <p>Runs against the same hidden GL context as the depth-plane tests; skipped on machines without
 * a display/GL. The depth values are written straight into the model target's sampleable depth
 * texture, so the test pins the shader's {@code gl_FragDepth} path without needing a Minecraft
 * world or a BBS form renderer.</p>
 */
public class ModelDepthCompositeTest
{
    private static final int SIZE = 64;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();
    }

    @AfterAll
    public static void destroyContext()
    {
        DepthPlanePixelHarness.shutdownGl();
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

    private static float planeDepth(CompCamera camera, float z)
    {
        Vector4f clip = new Vector4f(SIZE / 2F, SIZE / 2F, z, 1F);

        camera.view.transform(clip);
        camera.projection.transform(clip);

        float ndcZ = clip.z / clip.w;

        return ndcZ * 0.5F + 0.5F;
    }

    private static void uploadDepth(GlFramebuffer target, float left, float right)
    {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(SIZE * SIZE);

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                buffer.put(x < SIZE / 2 ? left : right);
            }
        }

        buffer.flip();

        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.depth.id);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
    }

    @Test
    public void aPlaneOnlyOccludesTheModelPixelsBehindIt()
    {
        Assumptions.assumeTrue(CompShaders.modelDepth != null && CompShaders.modelDepth.isValid(),
            "model depth program unavailable");
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompCamera camera = CompCamera.identity(SIZE, SIZE);
        GlFramebuffer plane = solid(0xFF0000FF);
        GlFramebuffer model = new GlFramebuffer(SIZE, SIZE, true, true);
        GlFramebuffer accumulation = new GlFramebuffer(SIZE, SIZE);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(SIZE, SIZE);
        int[] red = new int[SIZE * SIZE];

        for (int i = 0; i < red.length; i++)
        {
            red[i] = 0xFFFF0000;
        }

        model.color.uploadTopDown(red);

        float planeZ = 30F;
        float depth = planeDepth(camera, planeZ);

        /* Left half of the model is in front of the plane, right half behind it. */
        uploadDepth(model, Math.max(0F, depth - 0.02F), Math.min(1F, depth + 0.02F));
        accumulation.begin(0xFF000000);

        Matrix4f world = new Matrix4f().translate(0F, 0F, planeZ);
        accumulation = compositor.composite(plane, world, camera, accumulation, CompLayer.BLEND_NORMAL, 1F);
        accumulation = compositor.compositeModel(model, model, accumulation, CompLayer.BLEND_NORMAL, 1F, false);

        int[] pixels = accumulation.readTopDown();
        int left = pixels[(SIZE / 2) * SIZE + SIZE / 4];
        int right = pixels[(SIZE / 2) * SIZE + SIZE * 3 / 4];

        assertEquals(0xFFFF0000, left, "the model half in front of the plane must survive");
        assertEquals(0xFF0000FF, right, "the model half behind the plane must be occluded");

        compositor.delete();
        accumulation.delete();
        plane.delete();
        model.delete();
    }

    @Test
    public void modelDepthKeepsEverythingWhenNoPlaneWasDrawn()
    {
        Assumptions.assumeTrue(CompShaders.modelDepth != null && CompShaders.modelDepth.isValid(),
            "model depth program unavailable");

        GlFramebuffer model = new GlFramebuffer(SIZE, SIZE, true, true);
        GlFramebuffer accumulation = new GlFramebuffer(SIZE, SIZE);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(SIZE, SIZE);
        int[] green = new int[SIZE * SIZE];

        for (int i = 0; i < green.length; i++)
        {
            green[i] = 0xFF00FF00;
        }

        model.color.uploadTopDown(green);
        uploadDepth(model, 0.4F, 0.6F);
        accumulation.begin(0x00000000);
        accumulation = compositor.compositeModel(model, model, accumulation, CompLayer.BLEND_NORMAL, 1F, false);

        int[] pixels = accumulation.readTopDown();

        assertEquals(0xFF00FF00, pixels[(SIZE / 2) * SIZE + SIZE / 4]);
        assertEquals(0xFF00FF00, pixels[(SIZE / 2) * SIZE + SIZE * 3 / 4]);
        assertNotEquals(0x00000000, pixels[(SIZE / 2) * SIZE]);

        compositor.delete();
        accumulation.delete();
        model.delete();
    }
}
