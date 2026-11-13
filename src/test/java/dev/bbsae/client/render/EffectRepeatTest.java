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

    /**
     * AM repeat: `count` copies marching along `offset`, each rotated/scaled one step further.
     * Composition is 64 px, so a 16 px offset puts three copies at x = 16 / 32 / 48.
     */
    @Test
    public void amRepeatDrawsAProgressiveLineOfCopies()
    {
        CompEffect effect = effect("am_repeat");

        effect.put("count", 3D);
        effect.put("offset_x", 16D);
        effect.put("offset_y", 0D);
        effect.put("angle", 0D);
        effect.put("scale", 1D);
        effect.put("alpha", 1D);

        int[] pixels = applyProjected(effect, centreBlock());

        assertTrue(alphaAt(pixels, 16, 32) > 200, "first copy one step left of centre");
        assertTrue(alphaAt(pixels, 32, 32) > 200, "the centre copy stays in place");
        assertTrue(alphaAt(pixels, 48, 32) > 200, "last copy one step right of centre");
    }

    /**
     * AM repeat.radial: copies sit on an arc of `radius`. With 4 copies and a full sweep the
     * quarter angles land on the four cardinal points (composition UV is bottom-up, so the
     * +Y copy is 16 px above the centre).
     */
    @Test
    public void amRepeatRadialPlacesCopiesOnTheRing()
    {
        CompEffect effect = effect("am_repeat_radial");

        effect.put("count", 4D);
        effect.put("radius", 16D);
        effect.put("orientation", 0D);
        effect.put("start_angle", 0D);
        effect.put("sweep", 360D);
        effect.put("base_scale", 1D);
        effect.put("angle", 0D);
        effect.put("scale", 1D);
        effect.put("alpha", 1D);

        int[] pixels = applyProjected(effect, centreBlock());

        assertTrue(alphaAt(pixels, 48, 32) > 200, "right copy of the ring");
        assertTrue(alphaAt(pixels, 32, 16) > 200, "top copy of the ring");
        assertTrue(alphaAt(pixels, 16, 32) > 200, "left copy of the ring");
        assertTrue(alphaAt(pixels, 32, 48) > 200, "bottom copy of the ring");
    }

    /** AM repeat.scatter: copies land on deterministic random points of a disk. */
    @Test
    public void amRepeatScatterSpreadsCopiesAcrossTheDisk()
    {
        CompEffect effect = effect("am_repeat_scatter");

        effect.put("count", 10D);
        effect.put("radius", 24D);
        effect.put("evolution", 0D);
        effect.put("scatter_seed", 0D);
        effect.put("angle", 0D);
        effect.put("scale", 1D);
        effect.put("alpha", 1D);

        int[] pixels = applyProjected(effect, centreBlock());
        int visible = 0;
        int outside = 0;

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                if (alphaAt(pixels, x, y) > 200)
                {
                    visible += 1;

                    if (x < 24 || x >= 40 || y < 24 || y >= 40)
                    {
                        outside += 1;
                    }
                }
            }
        }

        /* Ten 8x8 copies, several of which must land outside the original 8x8 layer box. */
        assertTrue(visible >= 10 * 16, "scatter copies must be visible, got " + visible + " px");
        assertTrue(outside >= 8 * 8, "at least one copy must leave the layer rectangle, got " + outside);
    }

    @Test
    public void repeatFamilyNeutralReturnsTheInput()
    {
        CompRenderContext context = context();
        CompLayer layer = new CompLayer("solid", "Repeat", "solid");

        layer.width = BLOCK;
        layer.height = BLOCK;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        EffectSpace space = EffectSpace.identity(context, 0.5F, 0.5F, BLOCK / (float) SIZE, BLOCK / (float) SIZE);
        String[][] cases =
        {
            {"am_repeat", "count"},
            {"am_repeat_radial", "radius"},
            {"am_repeat_scatter", "radius"},
        };

        for (String[] entry : cases)
        {
            CompEffect effect = effect(entry[0]);

            effect.put("count", 1D);
            effect.put("radius", 0D);
            effect.put("offset_x", 0D);
            effect.put("offset_y", 0D);
            effect.put("angle", 0D);
            effect.put("scale", 1D);
            effect.put("base_scale", 1D);
            effect.put("alpha", 1D);

            GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

            input.color.uploadTopDown(centreBlock());

            try
            {
                assertSame(input, EffectRenderer.apply(layer, effect, input, context, space),
                    entry[0] + " must be the identity in its neutral configuration");
            }
            finally
            {
                FboPool.release(input);
            }
        }
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

    /**
     * The pass must composite straight-alpha layers without darkening them.
     *
     * <p>Layer pixels are straight alpha: an α=128 red pixel is (255,0,0,128), not (128,0,0,128).
     * The old accumulation wrote premultiplied RGB into a straight-alpha target, so any translucent
     * copy (and every antialiased/glow edge) came out dark — the same bug showed as "opacity 0.5
     * makes copies black".</p>
     */
    @Test
    public void translucentCopiesKeepTheirColour()
    {
        CompEffect effect = effect("am_repeat");

        /* One copy, but not the neutral configuration (alpha 0.999) so the pass really runs. */
        effect.put("count", 1D);
        effect.put("offset_x", 0D);
        effect.put("offset_y", 0D);
        effect.put("angle", 0D);
        effect.put("scale", 1D);
        effect.put("alpha", 0.999D);

        int[] source = solid(0x80FF0000);
        int[] pixels = applyProjected(effect, source);
        int center = pixels[32 * SIZE + 32];

        assertTrue(red(center) > 245, "a translucent copy must keep its colour, got " + Integer.toHexString(center));
        assertTrue(alphaAt(pixels, 32, 32) >= 124 && alphaAt(pixels, 32, 32) <= 130,
            "a translucent copy must keep its alpha, got " + alphaAt(pixels, 32, 32));
    }

    /** Same contract for the per-copy opacity: colour stays, only alpha is scaled. */
    @Test
    public void halfOpacityKeepsTheCopyColour()
    {
        CompEffect effect = effect("am_repeat");

        effect.put("count", 1D);
        effect.put("offset_x", 0D);
        effect.put("offset_y", 0D);
        effect.put("angle", 0D);
        effect.put("scale", 1D);
        effect.put("alpha", 0.5D);

        int[] pixels = applyProjected(effect, solid(0xFFFF0000));
        int center = pixels[32 * SIZE + 32];

        assertTrue(red(center) > 245, "opacity 0.5 must not darken the copy, got " + Integer.toHexString(center));
        assertTrue(alphaAt(pixels, 32, 32) >= 120 && alphaAt(pixels, 32, 32) <= 136,
            "opacity 0.5 must halve the alpha, got " + alphaAt(pixels, 32, 32));
    }

    private static int[] solid(int argb)
    {
        int[] pixels = new int[SIZE * SIZE];

        java.util.Arrays.fill(pixels, argb);

        return pixels;
    }

    private static int red(int argb)
    {
        return (argb >> 16) & 0xFF;
    }

    /**
     * Independent replay models are not rendered through renderBaked3D, so their repeat has to be
     * discovered without the projected-effect split. Its copies are layer-local 3D transforms.
     */
    @Test
    public void independentModelRepeatFindsCopyTransforms()
    {
        CompRenderContext context = context();
        CompLayer model = new CompLayer("model", "Model", "replay");

        model.independent = true;
        model.replayId = "r1";
        model.threeD = true;
        model.effects.add(effect("am_repeat"));

        CompEffect repeat = Replicate3D.find(model, context);

        assertNotNull(repeat, "the model repeat must be found even though there is no baked raster");
        repeat.put("count", 3D);
        repeat.put("offset_x", 16D);
        repeat.put("offset_y", 0D);
        repeat.put("angle", 0D);
        repeat.put("scale", 1D);
        repeat.put("alpha", 1D);

        java.util.List<Matrix4f> copies = Replicate3D.transforms(repeat, model, context, 0F, 0F);

        assertEquals(3, copies.size());

        org.joml.Vector3f first = copies.get(0).getTranslation(new org.joml.Vector3f());
        org.joml.Vector3f last = copies.get(2).getTranslation(new org.joml.Vector3f());

        assertEquals(-16F, first.x, 0.001F, "first copy must sit one offset left");
        assertEquals(16F, last.x, 0.001F, "last copy must sit one offset right");
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
