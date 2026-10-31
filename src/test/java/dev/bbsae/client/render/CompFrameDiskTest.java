package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disk L2 of the frame server.
 *
 * <p>Content-addressed: the file name is the document fingerprint, so a cache entry can never be
 * stale — different content is a different file. What has to be true is that a frame round-trips
 * exactly, that a mismatched request is a miss instead of a wrong picture, and that a corrupt file
 * is deleted rather than decoded into garbage.</p>
 */
public class CompFrameDiskTest
{
    private static CompProject project()
    {
        CompDocument doc = new CompDocument();

        doc.id = "disk_test";
        doc.width = 8;
        doc.height = 4;

        CompProject project = new CompProject();

        project.id = "disk_test_project";
        project.comps.add(doc);

        return project;
    }

    private static int[] pixels(int width, int height, int seed)
    {
        int[] pixels = new int[width * height];

        for (int i = 0; i < pixels.length; i++)
        {
            pixels[i] = 0xFF000000 | ((seed * 37 + i * 11) & 0xFFFFFF);
        }

        return pixels;
    }

    @Test
    public void framesRoundTripExactly()
    {
        CompProject project = project();
        CompDocument doc = project.comps.get(0);
        int[] source = pixels(doc.width, doc.height, 3);

        CompFrameDisk.save(project, doc, 12345L, 7, doc.width, doc.height, source);

        int[] loaded = CompFrameDisk.load(project, doc, 12345L, 7, doc.width, doc.height);

        assertNotNull(loaded, "a saved frame must be readable");
        assertArrayEquals(source, loaded, "the cache must not alter a single pixel");
    }

    @Test
    public void aDifferentSignatureIsAMiss()
    {
        CompProject project = project();
        CompDocument doc = project.comps.get(0);

        CompFrameDisk.save(project, doc, 1L, 0, doc.width, doc.height, pixels(doc.width, doc.height, 1));

        assertNull(CompFrameDisk.load(project, doc, 2L, 0, doc.width, doc.height),
            "a different document state must not return the old frame");
    }

    @Test
    public void aDifferentFrameOrSizeIsAMiss()
    {
        CompProject project = project();
        CompDocument doc = project.comps.get(0);

        CompFrameDisk.save(project, doc, 1L, 0, doc.width, doc.height, pixels(doc.width, doc.height, 1));

        assertNull(CompFrameDisk.load(project, doc, 1L, 1, doc.width, doc.height), "wrong frame");
        assertNull(CompFrameDisk.load(project, doc, 1L, 0, doc.width * 2, doc.height), "wrong width");
    }

    @Test
    public void aCorruptFileIsDeletedInsteadOfDecoded()
    {
        CompProject project = project();
        CompDocument doc = project.comps.get(0);
        java.nio.file.Path file = CompFrameDisk.file(project, doc, 5L, 2, doc.width, doc.height);

        try
        {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, new byte[] {1, 2, 3, 4, 5});
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        assertNull(CompFrameDisk.load(project, doc, 5L, 2, doc.width, doc.height));
        assertTrue(!java.nio.file.Files.exists(file), "a truncated entry must be removed, not retried");
    }

    @Test
    public void theFileIsNamedAfterItsContent()
    {
        CompProject project = project();
        CompDocument doc = project.comps.get(0);

        java.nio.file.Path a = CompFrameDisk.file(project, doc, 0xABC, 3, 8, 4);
        java.nio.file.Path b = CompFrameDisk.file(project, doc, 0xABD, 3, 8, 4);

        assertTrue(a.getFileName().toString().startsWith("abc_3_8x4"), a.getFileName().toString());
        assertTrue(!a.equals(b), "different signatures must not collide");
    }

    @Test
    public void frameCountAndProxyRasterAgreeWithTheComposition()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 60F;
        doc.duration = 2F;

        assertEquals(120, CompFrameServer.frameCount(doc));
        assertEquals(1920, CompFrameServer.widthOf(doc, 1F));
        assertEquals(960, CompFrameServer.widthOf(doc, 0.5F));
        assertEquals(540, CompFrameServer.heightOf(doc, 0.5F));
        assertEquals(480, CompFrameServer.widthOf(doc, 0.25F));
    }
}
