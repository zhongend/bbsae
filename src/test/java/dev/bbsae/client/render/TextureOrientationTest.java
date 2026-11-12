package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two UV conventions the compositor mixes (task-27 / P3-3).
 *
 * <p>Layer content is drawn with a y-down orthographic projection into an OpenGL framebuffer whose
 * texture v axis points up; the compositor addresses content with v-down composition pixels and
 * every fullscreen shader samples with {@code compY = (1 - vUv.y) * height}. Text upside down and
 * gradients anchored to the wrong end of a layer are the visible symptom when one path gets this
 * flip wrong (three such regressions have shipped). These tests pin the two ends of the convention:
 * an uploaded top row is read back on top, and a fullscreen effect pass keeps it there.</p>
 */
public class TextureOrientationTest
{
    private static final int SIZE = 96;
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();
        AmEffectRenderer.delete();
    }

    @AfterEach
    public void drainGlErrors()
    {
        while (org.lwjgl.opengl.GL11.glGetError() != org.lwjgl.opengl.GL11.GL_NO_ERROR)
        {
            /* drain */
        }
    }

    @AfterAll
    public static void destroyContext()
    {
        AmEffectRenderer.delete();
        DepthPlanePixelHarness.shutdownGl();
    }

    /** uploadTopDown + readTopDown is the CPU-side contract: row 0 is the visual top row. */
    @Test
    public void framebufferRoundTripKeepsTheTopRowOnTop()
    {
        GlFramebuffer framebuffer = new GlFramebuffer(SIZE, SIZE);
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            int color = y < SIZE / 2 ? RED : BLUE;

            for (int x = 0; x < SIZE; x++)
            {
                pixels[x + y * SIZE] = color;
            }
        }

        framebuffer.color.uploadTopDown(pixels);

        int[] read = framebuffer.readTopDown();

        assertEquals(RED, read[SIZE / 4 * SIZE + SIZE / 2], "the uploaded top band must read back on top");
        assertEquals(BLUE, read[(SIZE * 3 / 4) * SIZE + SIZE / 2], "the uploaded bottom band stays at the bottom");
        framebuffer.delete();
    }

    /**
     * A fullscreen effect pass samples {@code Source} with the flipped v; if either side dropped the
     * flip, invert would move the red band to the bottom.
     */
    @Test
    public void fullscreenEffectPassKeepsTheTopRowOnTop()
    {
        Assumptions.assumeTrue(CompShaders.composite != null && CompShaders.composite.isValid(),
            "composite program unavailable");

        CompDocument comp = new CompDocument();

        comp.id = "comp";
        comp.width = SIZE;
        comp.height = SIZE;
        comp.fps = 60F;
        comp.duration = 10F;

        CompProject project = new CompProject();

        project.comps.add(comp);

        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        CompLayer layer = new CompLayer("layer", "layer", "solid");

        layer.width = SIZE;
        layer.height = SIZE;
        comp.addLayer(layer);

        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            int color = y < SIZE / 2 ? RED : BLUE;

            for (int x = 0; x < SIZE; x++)
            {
                pixels[x + y * SIZE] = color;
            }
        }

        input.color.uploadTopDown(pixels);

        try
        {
            GlFramebuffer output = EffectRenderer.apply(layer, new CompEffect("inv", "invert"),
                input, context, EffectSpace.identity(context));
            int[] read = output.readTopDown();
            int top = read[SIZE / 4 * SIZE + SIZE / 2];
            int bottom = read[(SIZE * 3 / 4) * SIZE + SIZE / 2];

            /* invert keeps alpha and flips RGB: red -> cyan, blue -> yellow. */
            assertEquals(0xFF00FFFF, top, "the top band must still be the inverted red band (cyan)");
            assertEquals(0xFFFFFF00, bottom, "the bottom band must still be the inverted blue band (yellow)");
            assertTrue(top != bottom);

            if (output != input)
            {
                FboPool.release(output);
            }
        }
        finally
        {
            FboPool.release(input);
        }
    }
}
