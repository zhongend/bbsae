package dev.bbsae;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard tab and the composition clip use the addon's own icon. BBS loads it through the
 * addon resource namespace; a missing/mis-sized PNG would show a blank icon at runtime, which is
 * easy to miss in code review.
 */
public class IconResourceTest
{
    @Test
    public void compositorIconIsPackagedAtButtonSize() throws Exception
    {
        try (InputStream input = getClass().getResourceAsStream("/assets/bbsae/assets/textures/gui/compositor_icon.png"))
        {
            assertNotNull(input, "compositor_icon.png is missing from the addon resources");

            BufferedImage image = ImageIO.read(input);

            assertNotNull(image, "compositor_icon.png is not a readable PNG");
            assertEquals(16, image.getWidth(), "icon width");
            assertEquals(16, image.getHeight(), "icon height");
            assertTrue(image.getColorModel().hasAlpha(), "icon must keep its transparency");
        }
    }

    /** Fabric/Mod Menu reads this file from the mod metadata, not from the BBS addon namespace. */
    @Test
    public void modMenuIconIsPackaged() throws Exception
    {
        try (InputStream input = getClass().getResourceAsStream("/assets/bbsae/icon.png"))
        {
            assertNotNull(input, "assets/bbsae/icon.png is missing; fabric.mod.json points at it");

            BufferedImage image = ImageIO.read(input);

            assertNotNull(image, "mod icon is not a readable PNG");
            assertTrue(image.getWidth() >= 64, "mod icon should be at least 64 px for the mod list");
            assertEquals(image.getWidth(), image.getHeight(), "mod icon must be square");
            assertTrue(image.getColorModel().hasAlpha(), "mod icon should keep its rounded corners");
        }
    }

    /**
     * The repository/GitHub cover is uploaded as {@code .png}. A JPEG saved under that extension
     * still renders in a browser but some launchers/uploaders reject it (and the wrong old cover
     * was reaching the plugin page), so pin the actual container format here.
     */
    @Test
    public void galleryCoverIsARealSquarePng() throws Exception
    {
        java.io.File file = new java.io.File("docs/gallery/cover.png");

        assertTrue(file.isFile(), "docs/gallery/cover.png is missing");

        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(file))
        {
            assertNotNull(input, "cover cannot be opened");
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);

            assertTrue(readers.hasNext(), "cover is not a readable image");

            javax.imageio.ImageReader reader = readers.next();

            try
            {
                assertEquals("png", reader.getFormatName().toLowerCase(java.util.Locale.ROOT),
                    "cover must really be a PNG, not a JPEG renamed to .png");

                reader.setInput(input, true, true);

                assertEquals(reader.getWidth(0), reader.getHeight(0), "cover must be square");
                assertTrue(reader.getWidth(0) >= 512, "cover must be at least 512 px");
            }
            finally
            {
                reader.dispose();
            }
        }
    }
}
