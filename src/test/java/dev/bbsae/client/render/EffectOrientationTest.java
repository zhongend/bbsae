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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The raster/effect axis contract: an effect must not turn the layer upside down.
 *
 * <p>Layer content (text in particular) is rasterized with a y-down ortho projection, so local
 * y=0 is the <b>top</b> row of the texture (v = 1). Everything that maps a shader UV back to a
 * layer pixel — {@link EffectSpace#localToCompUv} in the composition path, and the same mapping in
 * the layer-local bake — has to obeys that flip exactly once. The bug this pins down: the 3D bake
 * sampled its own raster with the layer UV directly (no V flip), so every 3D layer with a
 * content-sampling effect came out vertically mirrored, which reads as "text upside down" after
 * adding an effect. The text itself was never the problem; the effect sampling was.</p>
 *
 * <p>Text rendering needs the BBS font stack, which is not available headless, so the tests drive
 * the same raster → effect → readback path with an asymmetric "glyph-like" alpha mask. That is the
 * exact contract the text layer goes through; only the glyph pixels differ.</p>
 */
public class EffectOrientationTest
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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae effect orientation", 0L, 0L);

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

        /* Every GL test class creates its own hidden context. Any cache that survived from the
         * previous class references GL names that no longer exist — and a later object can reuse
         * one of those names, so delete the caches BEFORE compiling the new programs. */
        AmEffectRenderer.delete();
        MeshRenderer.delete();
        Viewport3DRenderer.delete();
        FboPool.clear();
        CompShaders.reload();
        CompShaders.init();
    }

    @AfterAll
    public static void destroyContext()
    {
        /* Leave the shared caches empty for the next GL test class in this JVM. */
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

    /** Red band across the top quarter, transparent elsewhere: "text is up". */
    private static int[] topBand()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                boolean band = y >= 8 && y < 24 && x >= 8 && x < SIZE - 8;

                pixels[y * SIZE + x] = band ? 0xFFFF2020 : 0;
            }
        }

        return pixels;
    }

    /** Mean row of the visible pixels; smaller = higher in the layer. */
    private static float centroidY(int[] pixels)
    {
        double sum = 0;
        double weight = 0;

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                int alpha = (pixels[y * SIZE + x] >>> 24) & 0xFF;

                sum += y * (double) alpha;
                weight += alpha;
            }
        }

        return weight < 1E-6 ? -1F : (float) (sum / weight);
    }

    private static CompRenderContext context(int width, int height)
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = width;
        comp.height = height;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    private static CompLayer textLayer(float width, float height)
    {
        CompLayer layer = new CompLayer("text", "Text", "text");

        layer.width = width;
        layer.height = height;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        return layer;
    }

    /** Mirrors {@code CompLayerSourceCache.applyPixelOps}: proxy composition = the raster. */
    private static CompRenderContext layerLocalContext(CompRenderContext parent, int rasterWidth, int rasterHeight)
    {
        CompDocument proxy = new CompDocument();

        proxy.id = "viewport/test";
        proxy.width = rasterWidth;
        proxy.height = rasterHeight;
        proxy.fps = parent.comp.fps;
        proxy.duration = parent.comp.duration;

        CompRenderContext context = new CompRenderContext(parent.project, proxy, 0F, null, null, null);

        context.layerLocalPass = true;

        return context;
    }

    @Test
    public void compositionSpaceKeepsTheRasterOriented()
    {
        CompLayer layer = textLayer(SIZE, SIZE);
        CompRenderContext context = context(SIZE, SIZE);
        int[] source = topBand();
        float expected = centroidY(source);

        EffectSpace space = EffectSpace.ofBounds(layer, new Matrix4f().translate(SIZE / 2F, SIZE / 2F, 0F),
            context, -SIZE / 2F, -SIZE / 2F, SIZE, SIZE);

        for (String type : List.of("am_gaussian_blur", "am_four_color_gradient", "am_glow", "blur"))
        {
            int[] pixels = apply(layer, effect(type), source, context, space);

            assertTrue(centroidY(pixels) < SIZE / 2F,
                type + " in composition space must keep the band in the top half");
            assertTrue(Math.abs(centroidY(pixels) - expected) < SIZE / 4F,
                type + " in composition space must keep the band where it was, expected ~"
                    + expected + " got " + centroidY(pixels));
        }
    }

    @Test
    public void layerLocalBakeKeepsTheRasterOriented()
    {
        CompLayer layer = textLayer(SIZE, SIZE);
        CompRenderContext parent = context(192, 108);
        CompRenderContext context = layerLocalContext(parent, SIZE, SIZE);
        /* The raster covers the layer box exactly; local pixel -> raster is the identity. */
        EffectSpace space = EffectSpace.ofBounds(layer, new Matrix4f(), context, -SIZE / 2F, -SIZE / 2F, SIZE, SIZE);

        assertLayerLocalOrientation(layer, context, space, topBand(), null);
    }

    @Test
    public void layerLocalBakeWithPaddingKeepsTheLayerRectOriented()
    {
        CompLayer layer = textLayer(SIZE, SIZE);
        int pad = 16;
        int raster = SIZE + pad * 2;
        CompRenderContext parent = context(192, 108);
        CompRenderContext context = layerLocalContext(parent, raster, raster);
        /* Raster = layer box + margin; world maps layer local pixels into the raster. */
        Matrix4f world = new Matrix4f().scale(1F).translate(pad, pad, 0F);
        EffectSpace space = EffectSpace.ofBounds(layer, world, context,
            -SIZE / 2F, -SIZE / 2F, SIZE, SIZE);

        int[] source = new int[raster * raster];

        for (int y = 0; y < raster; y++)
        {
            for (int x = 0; x < raster; x++)
            {
                boolean inside = x >= pad && x < pad + SIZE && y >= pad && y < pad + SIZE;

                source[y * raster + x] = inside ? topBand()[(y - pad) * SIZE + (x - pad)] : 0;
            }
        }

        int[] pixels = apply(layer, effect("am_four_color_gradient"), source, context, space);
        float centroid = centroidY(pixels);

        assertTrue(centroid > 0F, "the padded raster must still contain pixels");
        assertTrue(centroid < raster / 2F,
            "the band must stay in the top half of the padded raster, centroid row " + centroid);
    }

    /** The default parameters of the real effect library must not mirror content. */
    @Test
    public void everyContentSamplingEffectKeepsTheBandOnTop()
    {
        List<String> types = List.of(
            "am_gaussian_blur", "am_box_blur", "am_four_color_gradient", "am_glow",
            "am_edge_glow", "am_inner_glow", "am_light_wrap", "am_glass",
            "am_color_gain", "am_rgb_gamma", "am_motion_blur", "am_lens_blur"
        );

        CompLayer layer = textLayer(SIZE, SIZE);
        CompRenderContext parent = context(192, 108);
        CompRenderContext context = layerLocalContext(parent, SIZE, SIZE);
        EffectSpace space = EffectSpace.ofBounds(layer, new Matrix4f(), context, -SIZE / 2F, -SIZE / 2F, SIZE, SIZE);

        for (String type : types)
        {
            if (CompEffectRegistry.get(type) == null)
            {
                continue;
            }

            int[] pixels = apply(layer, effect(type), topBand(), context, space);
            float centroid = centroidY(pixels);

            assertTrue(centroid > 0F && centroid < SIZE / 2F,
                type + " in the 3D bake must keep the band in the top half, centroid " + centroid);
        }
    }

    private static void assertLayerLocalOrientation(CompLayer layer, CompRenderContext context,
                                                    EffectSpace space, int[] source, String type)
    {
        float expected = centroidY(source);

        for (String effectType : type == null
            ? List.of("am_gaussian_blur", "am_four_color_gradient", "am_glow", "blur")
            : List.of(type))
        {
            int[] pixels = apply(layer, effect(effectType), source, context, space);
            float centroid = centroidY(pixels);

            assertTrue(centroid > 0F && centroid < SIZE / 2F,
                effectType + " in the layer-local bake must keep the band in the top half, centroid "
                    + centroid);
            assertTrue(Math.abs(centroid - expected) < SIZE / 4F,
                effectType + " in the layer-local bake must keep the band where it was, expected ~"
                    + expected + " got " + centroid);
        }
    }

    private static CompEffect effect(String type)
    {
        return CompEffectRegistry.create(type, 1);
    }

    private static int[] apply(CompLayer layer, CompEffect effect, int[] source,
                               CompRenderContext context, EffectSpace space)
    {
        GlFramebuffer input = new GlFramebuffer(context.width, context.height);

        input.color.uploadTopDown(source);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, space);

        try
        {
            assertTrue(output != null, effect.type + " produced no framebuffer");
            assertEqualsNoGlError(effect.type);

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

    private static void assertEqualsNoGlError(String type)
    {
        int error = GL11.glGetError();

        assertTrue(error == GL11.GL_NO_ERROR, "GL error after effect " + type + ": " + error);
    }
}
