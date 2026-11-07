package dev.bbsae.client.ui;

import dev.bbsae.comp.CompEffectRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The effect library shows a rendered preview per effect. The thumbnails are generated assets, so a
 * missing file would silently turn a tile into an icon; this keeps the resource set in sync with the
 * registry without opening the client.
 */
public class EffectThumbnailResourceTest
{
    @Test
    public void everyVisibleEffectHasAPreviewImage()
    {
        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            if (CompEffectRegistry.isLibraryHidden(definition.id))
            {
                continue;
            }

            String path = "/assets/bbsae/textures/effects/" + definition.id + ".png";
            InputStream stream = EffectThumbnailResourceTest.class.getResourceAsStream(path);

            assertNotNull(stream, "missing effect preview: " + path);
        }
    }
}
