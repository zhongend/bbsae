package dev.bbsae.client.render;

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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks for the real effect pipeline.
 *
 * <p>The shader unit tests prove single families in isolation; this test drives
 * {@link EffectRenderer#apply} — the function the render graph actually calls — for every entry of
 * {@link CompEffectRegistry}, with the real FBO pool, the real pass renderer and the real shader
 * programs. That is the only layer where "the slider does nothing", "the effect crashes" or "the
 * effect eats the alpha" can be caught before a user sees it.</p>
 */
public class EffectPipelineTest
{
    private static final int SIZE = 48;

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

    /**
     * Full-bleed opaque source: every pixel has alpha 255. The normal {@link #source()} square has
     * transparent margins, so an identity bug at the layer's border could hide in those margins.
     */
    private static int[] opaqueSource()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                int r = 40 + (x * 175) / SIZE;
                int g = 30 + (y * 175) / SIZE;
                int b = 60 + ((x + y) * 120) / (SIZE * 2);

                pixels[y * SIZE + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
    }

    private static int[] source()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                float u = x / (float) (SIZE - 1);
                float v = y / (float) (SIZE - 1);
                int r = Math.round(30F + 220F * u);
                int g = Math.round(40F + 190F * v);
                int b = Math.round(60F + 170F * (1F - u));

                if (((x / 6) + (y / 6)) % 2 == 1)
                {
                    r /= 2;
                    g /= 2;
                    b /= 2;
                }

                if (Math.abs(x - y) < 2)
                {
                    r = 250;
                    g = 250;
                    b = 250;
                }

                pixels[y * SIZE + x] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
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

    /** The layer whose bounds the effect space should follow: the whole test card. */
    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("solid", "Effect Test", "solid");

        layer.width = SIZE;
        layer.height = SIZE;
        layer.anchorX = 0F;
        layer.anchorY = 0F;
        layer.x = 0F;
        layer.y = 0F;

        return layer;
    }

    private static final class Outcome
    {
        final int width;
        final int height;
        final int[] pixels;

        Outcome(int width, int height, int[] pixels)
        {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        int at(int x, int y)
        {
            return this.pixels[Math.max(0, Math.min(this.height - 1, y)) * this.width
                + Math.max(0, Math.min(this.width - 1, x))];
        }

        /** Same coordinate on both images, in fractions of the image size. */
        int sample(float u, float v)
        {
            return this.at(Math.round(u * (this.width - 1)), Math.round(v * (this.height - 1)));
        }
    }

    /** A rounded test card: transparent outside a centred disc, opaque inside. */
    private static int[] cardSource()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                float dx = (x - (SIZE - 1) / 2F) / (SIZE * 0.35F);
                float dy = (y - (SIZE - 1) / 2F) / (SIZE * 0.35F);

                if (dx * dx + dy * dy > 1F)
                {
                    pixels[y * SIZE + x] = 0;
                }
                else
                {
                    pixels[y * SIZE + x] = (255 << 24) | (0x33 << 16) | (0x66 << 8) | 0xCC;
                }
            }
        }

        return pixels;
    }

    /**
     * Effects must stack: each pass takes the previous pass's output and the chain still produces a
     * valid frame without GL errors. This covers the new reference effects and the 3D primitive in
     * the same chain (a mesh pass followed by a colour pass, and the reverse order).
     */
    @Test
    public void effectsStackInOrderInTwoAndThreeDimensions()
    {
        String[][] chains =
        {
            {"blur", "am_color_gain"},
            {"am_bend", "am_edge_glow"},
            {"am_gaussian_blur", "am_gradient_overlay"},
            {"am_chromatic_aberration", "am_solid_matte"},
            {"s3d_cube", "am_color_gain"},
            {"am_color_gain", "s3d_cube"},
            {"am_heat_map", "am_palette_map"},
        };

        int[] source = source();

        for (String[] chain : chains)
        {
            int[] pixels = source;

            for (String type : chain)
            {
                if (CompEffectRegistry.get(type) == null)
                {
                    continue;
                }

                Outcome outcome = applyTo(pixels, CompEffectRegistry.create(type, 1));

                assertEquals(SIZE, outcome.width, type + " changed the frame width");
                assertEquals(SIZE, outcome.height, type + " changed the frame height");
                assertNotNull(outcome.pixels, type + " produced no pixels");
                pixels = outcome.pixels;
            }
        }
    }

    /**
     * Just above zero the untouched cells must not show the shard border.
     *
     * <p>The border used to be applied independently of the cell's own progress k, so a layer with
     * progress 0.02 (all cells still k = 0) was covered by a dark grid: "pulling the slider to zero
     * does not restore the layer" in practice. The fix ties the border to k; this test allows
     * resampling noise but not a 45% edge darkening.</p>
     */
    @Test
    public void blockShatterNearZeroKeepsTheCellsIntact()
    {
        Outcome outcome = apply("block_shatter", (effect) -> effect.put("progress", 0.005D));
        int[] source = source();

        for (int i = 0; i < source.length; i++)
        {
            for (int shift = 0; shift <= 24; shift += 8)
            {
                int expected = (source[i] >> shift) & 0xFF;
                int actual = (outcome.pixels[i] >> shift) & 0xFF;

                assertTrue(Math.abs(expected - actual) <= 24,
                    "block_shatter at progress 0.005 darkened pixel " + i + " channel " + shift
                        + ": " + expected + " -> " + actual);
            }
        }
    }

    /**
     * The user-facing zero contract, at pixel level: pulling the effect's main value to its
     * identity must hand back the exact input image (not just "a similar" one). This is checked
     * through the real renderer, so a shader that silently scales or darkens at zero fails here
     * even if isNeutral() was not updated.
     */
    @Test
    public void zeroValuesRestoreTheInputPixelForPixel()
    {
        String[][] cases =
        {
            {"block_shatter", "progress", "0"},
            {"wave", "amplitude", "0"},
            {"blur", "radius", "0"},
            {"turbulent_displace", "amount", "0"},
            {"am_bend", "amount", "0"},
            {"am_gaussian_blur", "strength", "0"},
            {"am_pinch_bulge", "strength", "0"},
            {"am_circular_ripple", "strength", "0"},
            {"am_swirl", "angle", "0"},
            {"am_mirror_warp", "strength", "0"},
            {"am_stretch_axis", "amount", "0"},
            {"am_slice_glitch", "amount", "0"},
            {"am_chromatic_aberration", "amount", "0"},
        };

        int[] source = source();

        for (String[] entry : cases)
        {
            CompEffect effect = CompEffectRegistry.create(entry[0], 1);

            effect.put(entry[1], Double.parseDouble(entry[2]));

            Outcome outcome = applyTo(source, effect);

            for (int i = 0; i < source.length; i++)
            {
                assertEquals(source[i], outcome.pixels[i],
                    entry[0] + " changed pixel " + i + " at " + entry[1] + "=0");
            }
        }
    }

    /**
     * The zero-value contract must hold inside the shader, not only through the neutral early-out.
     *
     * <p>The renderer skips a pass entirely when {@code isNeutral} says so; that makes a
     * "zero restores the source" pixel test pass even if the shader itself is not the identity. This
     * test bypasses the early-out and calls {@link AmEffectRenderer} directly for every effect that
     * declares a zero parameter, so a body whose zero value still changes pixels is caught.</p>
     */
    @Test
    public void everyDeclaredZeroIsIdentityInsideTheShader()
    {
        int[] source = opaqueSource();
        List<String> failures = new ArrayList<>();

        for (dev.bbsae.comp.AmEffectDef definition : dev.bbsae.comp.AmEffectLibrary.all())
        {
            if (definition.zeroKey == null || definition.neutralPredicate != null)
            {
                continue;
            }

            CompEffect effect = CompEffectRegistry.create(definition.id, 1);

            effect.put(definition.zeroKey, 0.0);

            CompRenderContext context = context();
            CompLayer layer = layer();
            GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

            input.color.uploadTopDown(source);

            GlFramebuffer output = AmEffectRenderer.applyForced(layer, effect, input, context,

                EffectSpace.identity(context));

            try
            {
                int[] pixels = output.readTopDown();
                int mismatches = 0;
                int first = -1;

                for (int i = 0; i < pixels.length; i++)
                {
                    if (pixels[i] != source[i])
                    {
                        if (first < 0)
                        {
                            first = i;
                        }

                        mismatches++;
                    }
                }

                if (mismatches > 0)
                {
                    failures.add(definition.id + " (" + definition.zeroKey + "=0) differs at "
                        + first + " (" + mismatches + " px)");
                }
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

        assertTrue(failures.isEmpty(), "zero values must be identities inside the shader: " + failures);
    }

    /**
     * Custom identities (scale 1, progress 1, identity channel maps) must also be shader-level
     * identities, not just predicate shortcuts.
     *
     * <p>The predicate cannot be inverted generically, so a few neutral candidates are tried and the
     * first one the definition accepts is rendered directly through {@link AmEffectRenderer}. This
     * is how the gradient wipe's progress=1 bug was found: the predicate said "neutral" while the
     * shader still faded the sweep edge.</p>
     */
    /**
     * Effects whose identity is a <b>set</b> of zero parameters (colour tone's nine gains,
     * highlight/shadow's two amounts) get the same shader-level treatment: set every declared key to
     * zero, force the shader and compare.
     */
    @Test
    public void everyDeclaredZeroSetIsIdentityInsideTheShader()
    {
        int[] source = opaqueSource();
        List<String> failures = new ArrayList<>();

        for (dev.bbsae.comp.AmEffectDef definition : dev.bbsae.comp.AmEffectLibrary.all())
        {
            if (definition.zeroKey != null || definition.neutralPredicate != null
                || definition.zeroKeys().isEmpty())
            {
                continue;
            }

            CompEffect effect = CompEffectRegistry.create(definition.id, 1);

            for (String key : definition.zeroKeys())
            {
                effect.put(key, 0.0);
            }

            CompRenderContext context = context();
            CompLayer layer = layer();
            GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

            input.color.uploadTopDown(source);

            GlFramebuffer output = AmEffectRenderer.applyForced(layer, effect, input, context,

                EffectSpace.identity(context));

            try
            {
                int[] pixels = output.readTopDown();
                int mismatches = 0;
                int first = -1;

                for (int i = 0; i < pixels.length; i++)
                {
                    if (pixels[i] != source[i])
                    {
                        if (first < 0)
                        {
                            first = i;
                        }

                        mismatches++;
                    }
                }

                if (mismatches > 0)
                {
                    failures.add(definition.id + " zero set differs at " + first + " (" + mismatches + " px)");
                }
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

        assertTrue(failures.isEmpty(), "declared zero sets must be identities inside the shader: " + failures);
    }

    @Test
    public void probeGradientMaskDefaults()
    {
        CompEffect effect = CompEffectRegistry.create("am_gradient_mask", 1);
        assertEquals(0.5F, effect.valueF("progress", -1F), 0.0001F, "default progress");
        effect.put("progress", 1.0);
        assertEquals(1F, effect.valueF("progress", -1F), 0.0001F, "stored progress");
        assertEquals(0.1F, effect.valueF("softness", -1F), 0.0001F, "default softness");
        dev.bbsae.comp.AmEffectDef def = dev.bbsae.comp.AmEffectLibrary.get("am_gradient_mask");
        assertNotNull(def, "definition");
        assertTrue(def.neutralPredicate != null, "predicate");
        assertTrue(def.neutralPredicate.test(effect), "predicate accepts progress 1");
    }

    @Test
    public void everyCustomIdentityIsIdentityInsideTheShader()
    {
        int[] source = opaqueSource();
        List<String> failures = new ArrayList<>();

        for (dev.bbsae.comp.AmEffectDef definition : dev.bbsae.comp.AmEffectLibrary.all())
        {
            if (definition.neutralPredicate == null)
            {
                continue;
            }

            CompEffect neutral = neutralCandidate(definition);

            if (neutral == null)
            {
                failures.add(definition.id + ": no neutral candidate satisfied the predicate");

                continue;
            }

            CompRenderContext context = context();
            CompLayer layer = layer();
            GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

            input.color.uploadTopDown(source);

            GlFramebuffer output = AmEffectRenderer.applyForced(layer, neutral, input, context,

                EffectSpace.identity(context));

            try
            {
                int[] pixels = output.readTopDown();
                int mismatches = 0;
                int first = -1;

                for (int i = 0; i < pixels.length; i++)
                {
                    if (pixels[i] != source[i])
                    {
                        if (first < 0)
                        {
                            first = i;
                        }

                        mismatches++;
                    }
                }

                if (mismatches > 0)
                {
                    failures.add(definition.id + " custom identity differs at " + first
                        + " (" + mismatches + " px)");
                }
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

        assertTrue(failures.isEmpty(), "custom identities must be identities inside the shader: " + failures);
    }

    private static CompEffect neutralCandidate(dev.bbsae.comp.AmEffectDef definition)
    {
        List<CompEffect> candidates = new ArrayList<>();

        candidates.add(CompEffectRegistry.create(definition.id, 1));

        CompEffect progress = CompEffectRegistry.create(definition.id, 1);

        progress.put("progress", 1.0);
        candidates.add(progress);

        CompEffect ones = CompEffectRegistry.create(definition.id, 1);

        for (dev.bbsae.comp.AmEffectDef.Param param : definition.params)
        {
            ones.put(param.key, 1.0);
        }

        candidates.add(ones);

        for (dev.bbsae.comp.AmEffectDef.Param param : definition.params)
        {
            for (double value : new double[] {0.0, 1.0})
            {
                CompEffect candidate = CompEffectRegistry.create(definition.id, 1);

                candidate.put(param.key, value);
                candidates.add(candidate);
            }
        }

        for (CompEffect candidate : candidates)
        {
            if (definition.neutralPredicate.test(candidate))
            {
                return candidate;
            }
        }

        return null;
    }

    private static Outcome applyTo(int[] sourcePixels, CompEffect effect)
    {
        CompRenderContext context = context();
        CompLayer layer = layer();
        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(sourcePixels);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, EffectSpace.identity(context));
        Outcome outcome;

        try
        {
            assertNotNull(output, "effect " + effect.type + " returned no framebuffer");
            outcome = new Outcome(output.width, output.height, output.readTopDown());
        }
        finally
        {
            if (output != null && output != input)
            {
                FboPool.release(output);
            }

            FboPool.release(input);
        }

        return outcome;
    }

    private static Outcome apply(String type, Consumer<CompEffect> configure)
    {
        CompEffect effect = CompEffectRegistry.create(type, 1);

        if (configure != null)
        {
            configure.accept(effect);
        }

        return apply(effect);
    }

    private static Outcome apply(CompEffect effect)
    {
        CompRenderContext context = context();
        CompLayer layer = layer();
        int[] sourcePixels = source();
        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(sourcePixels);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, EffectSpace.identity(context));
        Outcome outcome;

        try
        {
            assertNotNull(output, "effect " + effect.type + " returned no framebuffer");
            outcome = new Outcome(output.width, output.height, output.readTopDown());
        }
        finally
        {
            if (output != null && output != input)
            {
                FboPool.release(output);
            }

            FboPool.release(input);
        }

        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error after effect " + effect.type);

        return outcome;
    }

    private static int red(int argb)
    {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb)
    {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb)
    {
        return argb & 0xFF;
    }

    private static int alpha(int argb)
    {
        return (argb >>> 24) & 0xFF;
    }

    /**
     * Every catalogue entry must survive a real apply.
     *
     * <p>A missing program, a uniform name typo or an index-out-of-bounds in the parameter switch
     * throws here — with the effect id in the message — instead of silently doing nothing in game.</p>
     */
    @Test
    public void everyCatalogueEntryAppliesWithoutError()
    {
        List<String> broken = new ArrayList<>();

        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            try
            {
                Outcome outcome = apply(definition.id, null);

                assertNotNull(outcome);
            }
            catch (Throwable e)
            {
                broken.add(definition.id + " -> " + e);
            }
        }

        assertTrue(broken.isEmpty(), "effects that failed to apply: " + broken);
    }

    /** Writes a contact sheet of every effect so a visual regression is visible in one image. */
    @Test
    public void writePipelineContactSheet()
    {
        List<CompEffectRegistry.Definition> definitions = CompEffectRegistry.all();
        int columns = 10;
        int rows = (definitions.size() + columns - 1) / columns;
        int tile = SIZE * 2;
        int label = 14;
        BufferedImage sheet = new BufferedImage(columns * tile, rows * (tile + label), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = sheet.createGraphics();

        graphics.setColor(new java.awt.Color(18, 20, 26));
        graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));

        int[] sourcePixels = source();

        for (int index = 0; index < definitions.size(); index++)
        {
            CompEffectRegistry.Definition definition = definitions.get(index);
            int originX = (index % columns) * tile;
            int originY = (index / columns) * (tile + label);
            Outcome outcome;

            try
            {
                outcome = apply(definition.id, null);
            }
            catch (RuntimeException e)
            {
                continue;
            }

            for (int y = 0; y < tile; y++)
            {
                for (int x = 0; x < tile; x++)
                {
                    int sx = Math.min(outcome.width - 1, x / 2);
                    int sy = Math.min(outcome.height - 1, y / 2);

                    sheet.setRGB(originX + x, originY + y, outcome.at(sx, sy));
                }
            }

            graphics.setColor(new java.awt.Color(226, 232, 240));
            graphics.drawString(definition.id, originX + 3, originY + tile + 11);
        }

        graphics.dispose();
        java.io.File output = new java.io.File("docs/verify/effect-pipeline.png");

        output.getParentFile().mkdirs();

        try
        {
            javax.imageio.ImageIO.write(sheet, "png", output);
            assertTrue(output.isFile() && output.length() > 0, "contact sheet was not written");
        }
        catch (java.io.IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ---------------------------------------------------------------- behaviour */

    @Test
    public void strengthZeroIsTheOriginalImage()
    {
        int[] sourcePixels = source();

        for (String type : List.of("blur", "color_grade", "gradient_ramp", "kaleidoscope", "mirror", "vhs"))
        {
            CompEffect effect = CompEffectRegistry.create(type, 1);

            effect.put(CompEffect.STRENGTH, 0D);

            Outcome outcome = apply(effect);

            for (int i = 0; i < sourcePixels.length; i++)
            {
                assertEquals(sourcePixels[i], outcome.pixels[i],
                    type + " at strength 0 must be the untouched input at pixel " + i);
            }
        }
    }

    @Test
    public void invertInvertsRgbAndKeepsAlpha()
    {
        Outcome outcome = apply("invert", null);
        int color = outcome.at(24, 24);

        assertEquals(255, alpha(color), "invert must keep alpha");
        assertTrue(red(color) < 40, "invert must flip a bright ramp, red=" + red(color));
    }

    @Test
    public void gradientRampFillsTheWholeLayerWithAlpha()
    {
        Outcome outcome = apply("gradient_ramp", (effect) ->
        {
            effect.putColor("start", 0xFFFF0000);
            effect.putColor("end", 0xFF0000FF);
            effect.put("angle", 0D);
        });

        assertEquals(255, alpha(outcome.sample(0.5F, 0.5F)), "gradient must be opaque");

        /* Angle 0 is a horizontal ramp; which end gets the start colour is a shader convention, so
         * assert that the two ends are the two colours and that the middle is a blend of them. */
        int left = outcome.sample(0.02F, 0.5F);
        int right = outcome.sample(0.98F, 0.5F);
        int middle = outcome.sample(0.5F, 0.5F);
        boolean leftRed = red(left) > 150 && blue(left) < 100;
        boolean rightBlue = blue(right) > 150 && red(right) < 100;
        boolean leftBlue = blue(left) > 150 && red(left) < 100;
        boolean rightRed = red(right) > 150 && blue(right) < 100;

        assertTrue((leftRed && rightBlue) || (leftBlue && rightRed),
            "the two ends must carry the start/end colours, got "
                + Integer.toHexString(left) + " / " + Integer.toHexString(right));
        assertTrue(red(middle) > 40 && blue(middle) > 40, "the middle must be a blend, got "
            + Integer.toHexString(middle));
    }

    @Test
    public void blurPreservesAlphaAndSoftensTheEdge()
    {
        int[] sourcePixels = source();
        Outcome outcome = apply("blur", (effect) -> effect.put("radius", 8D));

        /* The hard white diagonal must lose contrast after a blur, not disappear. */
        int center = outcome.at(24, 24);

        assertEquals(alpha(sourcePixels[24 * SIZE + 24]), alpha(center), "blur must keep the alpha");
        assertTrue(red(center) < 250, "blur must soften the bright diagonal");
    }

    @Test
    public void colourEffectsDoNotLeakIntoTransparentPixels()
    {
        CompEffect effect = CompEffectRegistry.create("brightness_contrast", 1);

        effect.put("brightness", 0.5D);
        effect.put("contrast", 0.2D);

        Outcome outcome = applyTo(cardSource(), effect);

        assertEquals(0, alpha(outcome.at(0, 0)), "a colour effect must not create alpha");
        assertEquals(0, alpha(outcome.at(SIZE - 1, SIZE - 1)), "a colour effect must not create alpha");
        assertEquals(255, alpha(outcome.at(SIZE / 2, SIZE / 2)), "the opaque core must stay opaque");
    }

    /**
     * The blur kernel is normalised by the tap count.
     *
     * <p>Before the fix a radius of 8 sampled up to 8 × 8 = 64 px, so the "soft" edge of a small
     * layer was dragged most of the way across it and the 3D padding (radius + 2) clipped the halo.
     * A radius of 4 must stay local: the layer centre keeps its colour and the transparent corner
     * gains only a faint alpha.
     */
    @Test
    public void blurRadiusIsInPixelsNotTapCounts()
    {
        int[] source = cardSource();
        Outcome outcome = applyTo(source, CompEffectRegistry.create("blur", 1).put("radius", 3D));
        int core = outcome.at(SIZE / 2, SIZE / 2);

        assertTrue(red(core) > 40 && blue(core) > 150,
            "a 3 px blur must not wash the layer core out, got " + Integer.toHexString(core));
        assertTrue(alpha(outcome.at(0, 0)) < 60,
            "a 3 px blur must not reach the far corner, alpha=" + alpha(outcome.at(0, 0)));
    }
}
