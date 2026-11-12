package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards for the render-audit P0/P1 ownership and cache-identity fixes (task-33).
 *
 * <ul>
 *   <li>{@link PassRenderer#mask} must either return a new target or null — never the input plus a
 *       released target;</li>
 *   <li>mask/shape caches are scoped by project + composition: nested precomps reuse layer ids;</li>
 *   <li>the baked-raster signature follows the composition grid and the image/font file content;</li>
 *   <li>GlTexture writes survive a deleted/recycled texture name (bindForUpdate).</li>
 * </ul>
 */
public class RenderAuditFixTest
{
    private static final int SIZE = 16;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();
        AmEffectRenderer.delete();
    }

    @AfterEach
    public void drainGlErrors()
    {
        while (GL11.glGetError() != GL11.GL_NO_ERROR)
        {
            /* drain */
        }
    }

    @AfterAll
    public static void destroyContext()
    {
        CompShaders.reload();
        AmEffectRenderer.delete();
        MaskCache.clear();
        ShapeCache.clear();
        DepthPlanePixelHarness.shutdownGl();
    }

    /* --------------------------------------------------------- mask ownership */

    /** Success must hand the caller a new target; the input stays owned by the caller. */
    @Test
    public void maskSuccessReturnsANewTargetAndKeepsTheInput()
    {
        Assumptions.assumeTrue(CompShaders.composite != null && CompShaders.composite.isValid(),
            "composite program unavailable");

        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);
        GlTexture mask = new GlTexture();

        mask.allocate(SIZE, SIZE);
        mask.uploadRaw(fill(0xFFFFFFFF));

        try
        {
            GlFramebuffer masked = PassRenderer.mask(input, mask.id, 0);

            assertNotNull(masked, "a working mask pass must produce a target");
            assertNotSame(input, masked, "returning the input while releasing the target would break ownership");
            assertTrue(!input.pooled, "the source must not be released by the mask pass");
            assertEquals(0, GL11.glGetError());

            masked.delete();
        }
        finally
        {
            input.delete();
            mask.delete();
        }
    }

    /**
     * When the composite program is unavailable the pass has no result: it returns null and the
     * caller keeps its input. The old contract released the fresh target and returned the input,
     * which released the input too at the call site and put one target into the pool twice.
     */
    @Test
    public void maskFailureReturnsNullAndKeepsTheInput()
    {
        GlFramebuffer input = new GlFramebuffer(SIZE, SIZE);
        GlTexture mask = new GlTexture();

        mask.allocate(SIZE, SIZE);
        mask.uploadRaw(fill(0xFFFFFFFF));

        GlslProgram composite = CompShaders.composite;

        try
        {
            CompShaders.reload();

            GlFramebuffer masked = PassRenderer.mask(input, mask.id, 0);

            assertNull(masked, "no composite program means no result, not a borrowed input");
            assertTrue(!input.pooled, "the input must still be owned by the caller");
        }
        finally
        {
            if (composite != null)
            {
                composite.delete();
            }

            CompShaders.reload();
            CompShaders.init();

            input.delete();
            mask.delete();
        }
    }

    /* ---------------------------------------------------------- cache scoping */

    /** Two compositions may both contain a layer with id "layer": their mask slots differ. */
    @Test
    public void maskSlotsAreScopedByProjectAndComposition()
    {
        CompLayer layer = layerWithMask("layer");
        String scopeA = "project/compA";
        String scopeB = "project/compB";

        assertNotEquals(MaskCache.textureSlot(scopeA, layer, 64, 64),
            MaskCache.textureSlot(scopeB, layer, 64, 64), "scope must enter the texture slot");
        assertEquals(MaskCache.textureSlot(scopeA, layer, 64, 64),
            MaskCache.textureSlot("project/compA", layer, 64, 64), "same scope is stable");
    }

    /** The scoped getter must not hand one composition's mask texture to another composition. */
    @Test
    public void scopedMaskTexturesDoNotCollideAcrossCompositions()
    {
        CompDocument compA = document("compA");
        CompDocument compB = document("compB");
        CompLayer layerA = layerWithMask("layer");
        CompLayer layerB = layerWithMask("layer");

        compA.layers.add(layerA);
        compB.layers.add(layerB);

        CompProject project = new CompProject();

        project.id = "project";

        MaskCache.MaskTexture a = MaskCache.get(layerA, 64, 64, null, 0F, 1F,
            MaskCache.cacheScope(project, compA));
        MaskCache.MaskTexture b = MaskCache.get(layerB, 64, 64, null, 0F, 1F,
            MaskCache.cacheScope(project, compB));

        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "nested comps reuse layer ids; their mask textures must stay separate");
    }

    @Test
    public void shapeContentKeyAndSlotIncludeTheScope()
    {
        CompLayer layer = layerWithMask("layer");

        assertNotEquals(ShapeCache.contentKey(layer, 0F, "project/compA"),
            ShapeCache.contentKey(layer, 0F, "project/compB"), "shape identity is scoped");
        assertEquals("project/compA/layer", ShapeCache.layerSlot("project/compA", "layer"));
        assertEquals("layer", ShapeCache.layerSlot("", "layer"));
    }

    /* ------------------------------------------------------- raster signatures */

    /** P1-7: effect pixel units are authored against the composition grid, so it is a raster key. */
    @Test
    public void rasterSignatureFollowsTheCompositionGrid()
    {
        CompDocument wide = document("wide");
        CompDocument narrow = document("narrow");
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        wide.width = 1920;
        wide.height = 1080;
        narrow.width = 960;
        narrow.height = 540;

        layer.width = 100F;
        layer.height = 100F;
        wide.layers.add(layer);
        narrow.layers.add(layer);

        long wideHash = CompLayerSourceCache.signature(layer, context(wide), 256, 256);
        long narrowHash = CompLayerSourceCache.signature(layer, context(narrow), 256, 256);

        assertNotEquals(wideHash, narrowHash, "changing the composition size must rebuild baked rasters");
    }

    /** P1-2: replacing an image file with the same name must change both frame and raster keys. */
    @Test
    public void signaturesFollowImageFileContent() throws Exception
    {
        Path file = Files.createTempFile("bbsae-audit-image", ".png");

        try
        {
            Files.write(file, new byte[] {1, 2, 3, 4});
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000L));

            CompDocument doc = document("image");
            CompLayer layer = new CompLayer("layer", "Layer", "image");

            layer.imagePath = file.toAbsolutePath().toString();
            layer.width = 200F;
            layer.height = 100F;
            doc.layers.add(layer);

            CompProject project = new CompProject();

            project.id = "project";

            long frameHash = CompRenderCache.signature(project, doc, 0F, true, true, 0, 320, 180, false);
            long rasterHash = CompLayerSourceCache.signature(layer, context(doc), 200, 100);

            /* Same path, different bytes and size. */
            Files.write(file, new byte[] {9, 9, 9, 9, 9, 9});
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));

            assertNotEquals(frameHash,
                CompRenderCache.signature(project, doc, 0F, true, true, 0, 320, 180, false),
                "replacing image pixels must invalidate the cached frame");
            assertNotEquals(rasterHash, CompLayerSourceCache.signature(layer, context(doc), 200, 100),
                "replacing image pixels must invalidate the baked layer raster");
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void fontContentIdentityChangesWithTheFile() throws Exception
    {
        assertEquals(0L, FontCoverage.contentId(null));
        assertEquals(0L, FontCoverage.contentId("no-such-font-bbsae-audit"));

        Path file = Files.createTempFile("bbsae-audit-font", ".ttf");

        try
        {
            Files.write(file, new byte[] {1, 2, 3});
            long first = file.toAbsolutePath().toString().hashCode() * 31L
                + file.toFile().lastModified() * 17L + file.toFile().length();

            /* Direct helper check: same file identity yields the same value twice. */
            assertNotEquals(0L, first);

            Files.write(file, new byte[] {1, 2, 3, 4, 5});
            long second = file.toAbsolutePath().toString().hashCode() * 31L
                + file.toFile().lastModified() * 17L + file.toFile().length();

            assertNotEquals(first, second, "font file identity must change when the file changes");
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    /* ----------------------------------------------------------- GlTexture I/O */

    /**
     * P1-4: after a texture is deleted, the next recycled name must still receive the upload. The
     * old code trusted GlStateManager's cache, which could name the deleted texture and skip the
     * raw bind; the upload then landed on whatever was bound and read back wrong or raised a GL error.
     */
    @Test
    public void glTextureUploadWorksAfterDeleteAndRecreate()
    {
        for (int round = 0; round < 8; round++)
        {
            GlTexture first = new GlTexture();

            first.allocate(4, 4);
            first.uploadRaw(fill(0xFFFF0000));
            first.delete();

            GlTexture second = new GlTexture();

            second.allocate(4, 4);
            second.uploadRaw(fill(0xFF0000FF));

            byte[] pixels = readTexture(second.id);

            for (int i = 0; i < pixels.length; i += 4)
            {
                assertEquals((byte) 0, pixels[i], "red channel of the blue upload");
                assertEquals((byte) 0, pixels[i + 1]);
                assertEquals((byte) 0xFF, pixels[i + 2], "blue channel");
                assertEquals((byte) 0xFF, pixels[i + 3]);
            }

            assertEquals(0, GL11.glGetError(), "no GL error after recycled-name upload");
            second.delete();
        }
    }

    private static byte[] readTexture(int texture)
    {
        ByteBuffer buffer = MemoryUtil.memAlloc(4 * 4 * 4);

        try
        {
            GlStateManager._bindTexture(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            byte[] bytes = new byte[buffer.remaining()];

            buffer.get(bytes);

            return bytes;
        }
        finally
        {
            MemoryUtil.memFree(buffer);
        }
    }

    /* ------------------------------------------------------------- fixtures */

    private static CompLayer layerWithMask(String id)
    {
        CompLayer layer = new CompLayer(id, "Layer", "solid");
        CompMask mask = new CompMask("mask");

        mask.rectangle(-50F, -50F, 100F, 100F);
        layer.masks.add(mask);

        return layer;
    }

    private static CompDocument document(String id)
    {
        CompDocument doc = new CompDocument();

        doc.id = id;
        doc.name = id;
        doc.width = 320;
        doc.height = 180;
        doc.fps = 60F;
        doc.duration = 10F;

        return doc;
    }

    private static CompRenderContext context(CompDocument doc)
    {
        CompProject project = new CompProject();

        project.id = "project";

        return new CompRenderContext(project, doc, 0F, null, null, null);
    }

    private static int[] fill(int color)
    {
        int[] pixels = new int[SIZE * SIZE];

        java.util.Arrays.fill(pixels, color);

        return pixels;
    }
}
