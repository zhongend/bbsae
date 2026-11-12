package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeat is a replication of the layer, not a warp inside the layer rectangle.
 *
 * <p>The old tile implementations sampled the layer's own pixels inside a grid, so the result was
 * always cut at the layer box ("a mask"). Alight Motion's repeat category has no shader at all — it
 * draws the layer N times — and that is the semantics these tests pin: copies appear outside the
 * original rectangle, and only the composition bounds limit them. The 2D composition path and the
 * already-projected (3D) composition path share the same composition-space pass.</p>
 */
public class EffectRepeatTest
{
    private static final int SIZE = 64;
    /** The layer content: an 8x8 opaque red square (so a copy outside the layer rect is visible). */
    private static final int BLOCK = 8;

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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae repeat check", 0L, 0L);

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

        /* Delete caches from any previous hidden context before compiling new programs: a stale GL
         * name can collide with a fresh object and silently invalidate it. */
        AmEffectRenderer.delete();
        MeshRenderer.delete();
        Viewport3DRenderer.delete();
        FboPool.clear();
        CompShaders.reload();
        CompShaders.init();

        assertNotNull(CompShaders.replicate, "replicate shader failed to compile");
        assertTrue(CompShaders.replicate.isValid(), "replicate shader is invalid");
    }

    @AfterAll
    public static void destroyContext()
    {
        AmEffectRenderer.delete();
        MeshRenderer.delete();
        Viewport3DRenderer.delete();
        FboPool.clear();
        CompShaders.delete();

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

    private static CompRenderContext context()
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = SIZE;
        comp.height = SIZE;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    /** 8x8 opaque red block in the exact centre of the composition. */
    private static int[] centreBlock()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = SIZE / 2 - BLOCK / 2; y < SIZE / 2 + BLOCK / 2; y++)
        {
            for (int x = SIZE / 2 - BLOCK / 2; x < SIZE / 2 + BLOCK / 2; x++)
            {
                pixels[y * SIZE + x] = 0xFFFF2020;
            }
        }

        return pixels;
    }

    private static int alphaAt(int[] pixels, int x, int y)
    {
        return (pixels[y * SIZE + x] >>> 24) & 0xFF;
    }

    private static int[] apply(CompEffect effect, int[] source)
    {
        CompRenderContext context = context();
        CompLayer layer = new CompLayer("solid", "Repeat", "solid");

        layer.width = BLOCK;
        layer.height = BLOCK;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        /* The 2D composition path: model places the layer box at the comp centre, effects run on
         * the composition-sized layer image. */
        Matrix4f model = new Matrix4f().translate(SIZE / 2F, SIZE / 2F, 0F);
        EffectSpace space = EffectSpace.ofBounds(layer, model, context,
            -BLOCK / 2F, -BLOCK / 2F, BLOCK, BLOCK);
        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(source);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, space);

        try
        {
            assertNotNull(output, effect.type + " produced no framebuffer");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error after " + effect.type);

            return output.readTopDown();
        }
        finally
        {
            if (output != null && output != input)
            {
                FboPool.release(output);
            }

            FboPool.release(input);
        }
    }

    private static int[] applyProjected(CompEffect effect, int[] source)
    {
        CompRenderContext context = context();
        CompLayer layer = new CompLayer("solid", "Repeat", "solid");

        layer.width = BLOCK;
        layer.height = BLOCK;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        /* The projected path: the layer image is already composited, so the pass only knows the
         * projected centre and screen size of the layer box. */
        EffectSpace space = EffectSpace.identity(context, 0.5F, 0.5F, BLOCK / (float) SIZE, BLOCK / (float) SIZE);
        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(source);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, space);

        try
        {
            assertNotNull(output, effect.type + " produced no framebuffer");

            return output.readTopDown();
        }
        finally
        {
            if (output != null && output != input)
            {
                FboPool.release(output);
            }

            FboPool.release(input);
        }
    }

    private static CompEffect effect(String type)
    {
        return CompEffectRegistry.create(type, 1);
    }

    @Test
    public void gridRepeatDrawsCopiesOutsideTheLayerRectangle()
    {
        CompEffect effect = effect("grid_repeat");

        effect.put("columns", 2D);
        effect.put("rows", 1D);
        effect.put("gap", 0D);
        effect.put("scale", 1D);
        effect.put("rotation", 0D);
        effect.put("offset_x", 0D);
        effect.put("offset_y", 0D);

        int[] pixels = apply(effect, centreBlock());

        /* Cell centres of a 2x1 grid: x = 16 and 48. The layer box itself only covers 28..36. */
        assertTrue(alphaAt(pixels, 16, 32) > 200,
            "the left copy must be visible at x=16, well outside the source layer rect");
        assertTrue(alphaAt(pixels, 48, 32) > 200,
            "the right copy must be visible at x=48, well outside the source layer rect");
    }

    @Test
    public void projectedLayersGetTheSameCopies()
    {
        CompEffect effect = effect("grid_repeat");

        effect.put("columns", 2D);
        effect.put("rows", 1D);
        effect.put("gap", 0D);
        effect.put("scale", 1D);
        effect.put("rotation", 0D);

        int[] pixels = applyProjected(effect, centreBlock());

        assertTrue(alphaAt(pixels, 16, 32) > 200, "the projected 3D path must replicate too");
        assertTrue(alphaAt(pixels, 48, 32) > 200, "the projected 3D path must replicate too");
    }

    @Test
    public void referenceRepeatEntriesReplicateAsWell()
    {
        int[] pixels = applyProjected(effect("am_grid_repeat"), centreBlock());

        /* am_grid_repeat defaults to a 2x2 grid: all four cell centres carry a copy. */
        assertTrue(alphaAt(pixels, 16, 16) > 200, "top-left copy");
        assertTrue(alphaAt(pixels, 48, 16) > 200, "top-right copy");
        assertTrue(alphaAt(pixels, 16, 48) > 200, "bottom-left copy");
        assertTrue(alphaAt(pixels, 48, 48) > 200, "bottom-right copy");
    }

    @Test
    public void lineRepeatDrawsCopiesAlongTheAxis()
    {
        CompEffect effect = effect("am_line_repeat");

        effect.put("count", 2D);
        effect.put("duty", 1D);
        effect.put("amount", 1D);
        effect.put("vertical", 0D);

        int[] pixels = applyProjected(effect, centreBlock());

        assertTrue(alphaAt(pixels, 16, 32) > 200, "first copy along the horizontal axis");
        assertTrue(alphaAt(pixels, 48, 32) > 200, "second copy along the horizontal axis");
    }

    @Test
    public void zeroOpacityLeavesTheInputUntouched()
    {
        CompRenderContext context = context();
        CompLayer layer = new CompLayer("solid", "Repeat", "solid");

        layer.width = BLOCK;
        layer.height = BLOCK;

        EffectSpace space = EffectSpace.identity(context, 0.5F, 0.5F, 0.125F, 0.125F);
        CompEffect effect = effect("grid_repeat");

        effect.put("opacity", 0D);

        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(centreBlock());

        try
        {
            assertSame(input, EffectRenderer.apply(layer, effect, input, context, space),
                "a repeat with zero alpha must not allocate a pass");
        }
        finally
        {
            FboPool.release(input);
        }
    }

    @Test
    public void repeatEffectsNeverRunInsideTheLayerBake()
    {
        /* The bake raster is exactly the layer box, so a replicator there could never write
         * outside it — the whole point of running these in composition space. */
        assertTrue(CompRenderer.requiresProjectedPass("grid_repeat"));
        assertTrue(CompRenderer.requiresProjectedPass("random_repeat"));
        assertTrue(CompRenderer.requiresProjectedPass("am_grid_repeat"));
        assertTrue(CompRenderer.requiresProjectedPass("am_line_repeat"));

        java.util.List<CompEffect> effects = new java.util.ArrayList<>();

        effects.add(effect("am_grid_repeat"));

        assertEquals(0, CompRenderer.firstProjectedEffect(effects),
            "the first projected effect must be the replicator itself, so it is not baked");

        assertFalse(CompRenderer.requiresProjectedPass("am_glow"));
    }
}
