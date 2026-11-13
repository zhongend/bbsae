package dev.bbsae.client.replay;

import dev.bbsae.comp.CompDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry contract of the replay editor's camera preview.
 *
 * <p>The composition is rendered at the document's aspect ratio, inside a viewport whose ratio is
 * whatever the user made the panel. The world plate has yet another aspect (the game window). These
 * tests pin both fits so a future layout change cannot silently stretch or crop the preview.</p>
 */
public class ReplayPreviewLayoutTest
{
    private static CompDocument doc(int width, int height)
    {
        CompDocument doc = new CompDocument();

        doc.width = width;
        doc.height = height;

        return doc;
    }

    @Test
    public void fitPreservesAspectAndCentres()
    {
        ReplayPreviewLayout.Rect rect = ReplayPreviewLayout.fit(doc(1920, 1080), 10, 20, 1000, 800, 8);

        assertEquals(1.7778D, rect.w() / (double) rect.h(), 0.01D);
        assertTrue(rect.x() >= 10 && rect.ex() <= 1010, "inside the viewport horizontally");
        assertTrue(rect.y() >= 20 && rect.ey() <= 820, "inside the viewport vertically");
        assertEquals(510, rect.x() + rect.w() / 2, 1, "centred horizontally");
        assertEquals(420, rect.y() + rect.h() / 2, 1, "centred vertically");
    }

    @Test
    public void fitUsesThePaddedViewportForAnUnconfiguredDocument()
    {
        ReplayPreviewLayout.Rect rect = ReplayPreviewLayout.fit(doc(0, 0), 0, 0, 300, 200, 10);

        assertEquals(new ReplayPreviewLayout.Rect(10, 10, 280, 180), rect);
    }

    @Test
    public void containLetterboxesAWiderTexture()
    {
        ReplayPreviewLayout.Rect bounds = new ReplayPreviewLayout.Rect(0, 0, 1000, 600);
        ReplayPreviewLayout.Rect plate = ReplayPreviewLayout.contain(2560, 1080, bounds);

        assertEquals(1000, plate.w(), "width is the limiting dimension");
        assertEquals(422, plate.h(), 1);
        assertEquals(0, plate.x());
        assertEquals(89, plate.y(), 1, "letterbox is centred vertically");
    }

    @Test
    public void containLetterboxesATallerTexture()
    {
        ReplayPreviewLayout.Rect bounds = new ReplayPreviewLayout.Rect(20, 30, 1000, 600);
        ReplayPreviewLayout.Rect plate = ReplayPreviewLayout.contain(1080, 1920, bounds);

        assertEquals(338, plate.w(), 1);
        assertEquals(600, plate.h());
        assertEquals(351, plate.x(), 1);
        assertEquals(30, plate.y());
    }

    @Test
    public void canReuseOnlyWhenTheFrameAndRasterMatch()
    {
        assertTrue(ReplayPreviewLayout.canReuse(true, 12, 42L, 960, 540, 12, 42L, 960, 540,
            false, true));
        assertFalse(ReplayPreviewLayout.canReuse(false, 12, 42L, 960, 540, 12, 42L, 960, 540,
            false, true));
        assertFalse(ReplayPreviewLayout.canReuse(true, 12, 42L, 960, 540, 13, 42L, 960, 540,
            false, true));
        assertFalse(ReplayPreviewLayout.canReuse(true, 12, 42L, 960, 540, 12, 43L, 960, 540,
            false, true));
        assertFalse(ReplayPreviewLayout.canReuse(true, 12, 42L, 960, 540, 12, 42L, 1280, 720,
            false, true));
    }

    @Test
    public void aFrameBakedBeforeTheWorldExistedIsNeverReused()
    {
        /* The first frames of a BBS-preview composition can be rendered before the world copy
         * exists; the layer is empty in them. Reusing that frame would freeze the preview on an
         * empty plate forever. */
        assertFalse(ReplayPreviewLayout.canReuse(true, 3, 99L, 800, 450, 3, 99L, 800, 450,
            true, false));
        assertTrue(ReplayPreviewLayout.canReuse(true, 3, 99L, 800, 450, 3, 99L, 800, 450,
            true, true));
    }
}
