package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextureFilterTest
{
    @Test
    public void pixelArtStaysNearestEvenWhenUpscaled()
    {
        assertFalse(TextureFilter.auto(16, 16, 128F, 128F), "a 16 px icon must stay crisp");
        assertFalse(TextureFilter.auto(64, 64, 640F, 640F), "64 px art is still pixel art");
    }

    @Test
    public void highResolutionSourcesUseLinear()
    {
        assertTrue(TextureFilter.auto(1024, 1024, 400F, 400F), "large source downscaled should be linear");
        assertTrue(TextureFilter.auto(2048, 512, 1800F, 450F), "a 2K source is high resolution");
        assertTrue(TextureFilter.auto(128, 128, 32F, 32F), "a 4× downscale benefits from linear minification");
    }

    @Test
    public void explicitOverridesWin()
    {
        assertTrue(TextureFilter.isLinear(TextureFilter.LINEAR, 16, 16, 512F, 512F));
        assertFalse(TextureFilter.isLinear(TextureFilter.NEAREST, 4096, 4096, 128F, 128F));
    }

    @Test
    public void layerOverrideFallsBackToProjectSetting()
    {
        CompProject project = new CompProject();
        CompLayer layer = new CompLayer("image", "Image", "image");

        project.settings.textureFilter = TextureFilter.LINEAR;
        assertTrue(TextureFilter.resolveMode(layer, project) == TextureFilter.LINEAR,
            "inherit must follow the project");

        layer.textureFilter = TextureFilter.NEAREST;
        assertTrue(TextureFilter.resolveMode(layer, project) == TextureFilter.NEAREST,
            "a layer override must win");
    }
}
