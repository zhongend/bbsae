package dev.bbsae.client.render;

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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/**
 * Generates the preview thumbnails the effect library shows.
 *
 * <p>Run with {@code -PbbsaeThumbs} (which sets {@code -Dbbsae.thumbs=true}); it is skipped in normal
 * test runs. The output is committed under {@code assets/bbsae/textures/effects/} so the library can
 * display real rendered previews without doing GPU work in the UI thread.</p>
 */
public class EffectThumbnailTest
{
    private static final int SIZE = 80;

    private static long window;

    @BeforeAll
    public static void createContext()
    {
        Assumptions.assumeTrue(Boolean.getBoolean("bbsae.thumbs"), "thumbnail generation not requested");
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "bbsae effect thumbnails", 0L, 0L);

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

        /* VAOs are context objects: another GL test class in this JVM may have populated the mesh
         * cache in a context that no longer exists. */
        MeshRenderer.delete();
        Viewport3DRenderer.delete();
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

    private static int[] source()
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                float u = x / (float) (SIZE - 1);
                float v = y / (float) (SIZE - 1);
                int r = Math.round(80F + 170F * u);
                int g = Math.round(40F + 160F * v);
                int b = Math.round(150F + 90F * (1F - u));

                if (((x / 8) + (y / 8)) % 2 == 1)
                {
                    r = r * 2 / 3;
                    g = g * 2 / 3;
                    b = b * 2 / 3;
                }

                if (Math.abs(x - y) < 4)
                {
                    r = 250;
                    g = 245;
                    b = 220;
                }

                pixels[y * SIZE + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
    }

    private static int[] render(CompEffect effect, CompProject project)
    {
        int[] source = source();
        CompRenderContext context = new CompRenderContext(project, project.getCompOrFirst("main"), 0F, null, null, null);
        CompLayer layer = new CompLayer("thumb", "thumb", "shape");

        layer.x = SIZE * 0.5F;
        layer.y = SIZE * 0.5F;
        layer.width = SIZE;
        layer.height = SIZE;

        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);

        input.color.uploadTopDown(source);

        GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, EffectSpace.identity(context));

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

    private static boolean differs(int[] a, int[] b)
    {
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
            {
                return true;
            }
        }

        return false;
    }

    /**
     * A usable preview must both differ from the test card and keep enough visible pixels. The old
     * "differs" test accepted a completely empty image, and a replica whose copies landed off the
     * composition produced a uniform clamped-edge colour — both technically "different" from the
     * card but useless as a preview, so a near-uniform image is rejected too.
     */
    private static boolean usable(int[] pixels, int[] source)
    {
        if (!differs(pixels, source))
        {
            return false;
        }

        int visible = 0;
        java.util.Map<Integer, Integer> buckets = new java.util.HashMap<>();

        for (int pixel : pixels)
        {
            if (((pixel >>> 24) & 0xFF) > 8)
            {
                visible += 1;

                /* Quantise to 5 bits per channel: gradients still produce many buckets, a flat
                 * fill produces one. */
                int key = ((pixel >> 19) & 0xE0) << 16 | ((pixel >> 11) & 0xE0) << 8
                    | ((pixel >> 3) & 0xE0) | ((pixel >>> 24) & 0xE0);

                buckets.merge(key, 1, Integer::sum);
            }
        }

        if (visible <= pixels.length / 50)
        {
            return false;
        }

        int largest = 0;

        for (int count : buckets.values())
        {
            largest = Math.max(largest, count);
        }

        return largest < visible * 0.92F;
    }

    /** Finds a parameter choice that visibly shows the effect; zero-strength defaults would otherwise
     * produce a plain test card and make every preview look identical. */
    private static CompEffect showcase(CompEffectRegistry.Definition definition, CompProject project)
    {
        int[] source = source();

        if (usable(render(CompEffectRegistry.create(definition.id, 1), project), source))
        {
            return CompEffectRegistry.create(definition.id, 1);
        }

        for (CompEffectRegistry.Param param : definition.params)
        {
            float span = param.max - param.min;
            float[] values = {param.min + span * 0.5F, param.min + span * 0.72F, param.min + span * 0.25F};

            for (float value : values)
            {
                if (Math.abs(value - param.defaultValue) < 0.0001F)
                {
                    continue;
                }

                CompEffect candidate = CompEffectRegistry.create(definition.id, 1);

                candidate.put(param.key, (double) value);

                if (usable(render(candidate, project), source))
                {
                    return candidate;
                }
            }
        }

        /* Pixel-authored parameters (radius, offset, …) can have reference defaults that are larger
         * than this 80 px thumbnail composition. A combined candidate keeps neutral values and
         * scales the large pixel-ish ranges down to something visible in the preview. */
        CompEffect scaled = CompEffectRegistry.create(definition.id, 1);

        for (CompEffectRegistry.Param param : definition.params)
        {
            float span = param.max - param.min;

            if (param.min < 0F && param.max > 0F)
            {
                scaled.put(param.key, 0D);
            }
            else if (span >= 100F)
            {
                scaled.put(param.key, (double) (param.min + span * 0.01F));
            }
        }

        if (usable(render(scaled, project), source))
        {
            return scaled;
        }

        return CompEffectRegistry.create(definition.id, 1);
    }

    @Test
    public void writeEffectThumbnails() throws Exception
    {
        Assumptions.assumeTrue(Boolean.getBoolean("bbsae.thumbs"), "thumbnail generation not requested");

        File root = new File("src/main/resources/assets/bbsae/textures/effects");

        root.mkdirs();

        CompProject project = new CompProject();
        project.addComp("Main", SIZE, SIZE, 60, 10F);

        int written = 0;

        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            if (CompEffectRegistry.isLibraryHidden(definition.id))
            {
                continue;
            }

            CompEffect effect = showcase(definition, project);
            int[] pixels = render(effect, project);
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);

            image.setRGB(0, 0, SIZE, SIZE, pixels, 0, SIZE);

            String name = definition.id.replaceAll("[^a-z0-9_]", "_");

            ImageIO.write(image, "png", new File(root, name + ".png"));
            written++;
        }

        try
        {
            Files.writeString(java.nio.file.Path.of("_research/am/thumb-log.txt"),
                "written=" + written + " mesh=" + (CompShaders.mesh == null ? "null" : CompShaders.mesh.isValid()));
        }
        catch (Exception ignored)
        {
        }

        Assumptions.assumeTrue(written > 0, "no effects to render");
    }
}
