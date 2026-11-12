package dev.bbsae.client.render;

import dev.bbsae.comp.AmEffectDef;
import dev.bbsae.comp.AmEffectLibrary;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel contract for the second reference batch (blur / colour / distort, 0.94).
 *
 * <p>Every entry has to satisfy two user-facing properties: applying it with its defaults actually
 * changes the image (otherwise the effect library shows a dead slider) and its declared neutral
 * configuration is the input bit for bit — both through the renderer shortcut and inside the
 * fragment body (the zero audit bypasses the shortcut, so a missing identity branch would fail
 * here).</p>
 */
public class EffectP1BatchTest
{
    private static final int SIZE = 48;

    private static final String[] BATCH =
    {
        "am_unsharp_mask", "am_lens_blur_2", "am_spin_blur", "am_streak_strips", "am_warp_blur",
        "am_chromatic_zoom_blur", "am_spectral_map", "am_soft_glow", "am_dark_glow",
        "am_light_glow", "am_color_hot", "am_pinch_bulge_2", "am_swirl_4", "am_hex_rotate",
    };

    /** 0.95: remaining reference distort family. */
    private static final String[] DISTORT_BATCH =
    {
        "am_pinch_bulge_inside", "am_wave_warp", "am_turbulent_displace_3",
        "am_tileshift_2", "am_stretch_segment", "am_omino_glass",
    };

    private static java.util.List<String> allBatch()
    {
        java.util.List<String> types = new ArrayList<>(java.util.Arrays.asList(BATCH));

        types.addAll(java.util.Arrays.asList(DISTORT_BATCH));

        return types;
    }

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
        window = GLFW.glfwCreateWindow(64, 64, "bbsae p1 batch", 0L, 0L);

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

    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("card", "Card", "solid");

        layer.width = SIZE;
        layer.height = SIZE;
        layer.anchorX = 0F;
        layer.anchorY = 0F;

        return layer;
    }

    /**
     * Opaque test card with a large dark half and a large bright half (plus a checker for high
     * frequencies): every effect in the batch — including the dark/light threshold glows — has a
     * region large enough to act on.
     */
    private static int[] source()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                float u = x / (float) (SIZE - 1);
                float v = y / (float) (SIZE - 1);
                int r = Math.round(190F + 60F * u);
                int g = Math.round(190F + 60F * v);
                int b = Math.round(180F + 70F * (1F - u));
                boolean darkSide = x < SIZE / 2;
                boolean checker = ((x / 6) + (y / 6)) % 2 == 0;
                float shade = (darkSide ? 0.18F : 1F) * (checker ? 1F : 0.7F);

                pixels[y * SIZE + x] = 0xFF000000
                    | (Math.round(r * shade) << 16)
                    | (Math.round(g * shade) << 8)
                    | Math.round(b * shade);
            }
        }

        return pixels;
    }

    private static CompEffect neutral(AmEffectDef definition)
    {
        CompEffect effect = CompEffectRegistry.create(definition.id, 1);

        if (definition.neutralValues != null)
        {
            for (java.util.Map.Entry<String, Double> entry : definition.neutralValues.entrySet())
            {
                effect.put(entry.getKey(), entry.getValue());
            }
        }
        else if (definition.zeroKey != null)
        {
            effect.put(definition.zeroKey, 0D);
        }

        return effect;
    }

    @Test
    public void defaultsProduceAVisibleResult()
    {
        int[] source = source();
        List<String> flat = new ArrayList<>();

        for (String type : allBatch())
        {
            CompEffect effect = CompEffectRegistry.create(type, 1);
            int[] pixels = apply(effect, source, false);
            int differences = 0;

            for (int i = 0; i < source.length; i++)
            {
                if (pixels[i] != source[i])
                {
                    differences += 1;
                }
            }

            if (differences < source.length / 20)
            {
                flat.add(type + " (" + differences + " px)");
            }
        }

        assertTrue(flat.isEmpty(), "defaults must visibly change the image: " + flat);
    }

    @Test
    public void declaredNeutralIsTheIdentityInsideTheShader()
    {
        int[] source = source();
        List<String> failures = new ArrayList<>();

        for (String type : allBatch())
        {
            AmEffectDef definition = AmEffectLibrary.get(type);

            assertNotNull(definition, "missing definition " + type);

            CompEffect neutral = neutral(definition);

            assertTrue(AmEffectLibrary.isNeutral(neutral), type + " neutral configuration not detected");

            /* Renderer path: the shortcut must return the input target itself. */
            CompRenderContext context = context();
            CompLayer layer = layer();
            GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

            input.color.uploadTopDown(source);

            try
            {
                assertSame(input, EffectRenderer.apply(layer, neutral, input, context, EffectSpace.identity(context)),
                    type + " must be a no-op through the renderer");

                /* Shader path: bypass the shortcut and compare pixels. */
                GlFramebuffer forced = AmEffectRenderer.applyForced(layer, neutral, input, context,
                    EffectSpace.identity(context));

                try
                {
                    int[] pixels = forced.readTopDown();

                    for (int i = 0; i < source.length; i++)
                    {
                        if (pixels[i] != source[i])
                        {
                            failures.add(type + " differs at " + i);
                            break;
                        }
                    }
                }
                finally
                {
                    if (forced != input)
                    {
                        FboPool.release(forced);
                    }
                }
            }
            finally
            {
                FboPool.release(input);
            }
        }

        assertTrue(failures.isEmpty(), "neutral configurations must be shader-level identities: " + failures);
    }

    @Test
    public void everyBatchEntryIsRegisteredAndRenderable()
    {
        for (String type : allBatch())
        {
            AmEffectDef definition = AmEffectLibrary.get(type);

            assertNotNull(definition, "missing definition " + type);
            assertNotNull(CompEffectRegistry.get(type), "not registered " + type);
            assertTrue(EffectRenderer.supports(type), "no renderer " + type);
            assertTrue(definition.params.size() >= 1, type + " needs editable parameters");
        }
    }

    private static int[] apply(CompEffect effect, int[] source, boolean projected)
    {
        CompRenderContext context = context();
        CompLayer layer = layer();
        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(source);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, EffectSpace.identity(context));

        assertNotNull(output, effect.type + " produced no framebuffer");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error after " + effect.type);

        try
        {
            return output.readTopDown();
        }
        finally
        {
            if (output != input)
            {
                FboPool.release(output);
            }

            FboPool.release(input);
        }
    }
}
