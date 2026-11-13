package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The replay preview must render the comp but never the live-world BBS layer. */
public class CompPreviewFilterTest
{
    @Test
    public void replayFilterHidesOnlyLiveWorldLayers()
    {
        CompLayer bbs = new CompLayer("bbs", "BBS Preview", "bbs");
        CompLayer text = new CompLayer("text", "Title", "text");

        CompPreviewFilter.push((layer) -> layer == null || !"bbs".equals(layer.type));

        try
        {
            assertTrue(CompPreviewFilter.active());
            assertFalse(CompPreviewFilter.visible(bbs));
            assertTrue(CompPreviewFilter.visible(text));
        }
        finally
        {
            CompPreviewFilter.pop();
        }

        assertFalse(CompPreviewFilter.active());
        assertTrue(CompPreviewFilter.visible(bbs));
    }
}
