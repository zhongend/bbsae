package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the rule that fixed the offset entity matte: render at the world size, blit from it. */
public class EntityMaskSizingTest
{
    @Test
    public void theMatteTargetIsTheWorldFrameSizeNotTheWindow()
    {
        Dimension size = EntityMaskSizing.targetSize(1920, 1080, 1280, 800);

        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
    }

    @Test
    public void anUnknownWorldSizeFallsBackToTheWindow()
    {
        Dimension size = EntityMaskSizing.targetSize(0, 0, 1280, 800);

        assertEquals(1280, size.width);
        assertEquals(800, size.height);
    }

    @Test
    public void theDepthBlitDescribesTheWorldFramebuffer()
    {
        int[] source = EntityMaskSizing.blitSource(1920, 1080, 1280, 800);

        assertEquals(1920, source[0], "source width must be the world framebuffer's");
        assertEquals(1080, source[1], "source height must be the world framebuffer's");
    }

    @Test
    public void aMissingSourceSizeFallsBackToTheTarget()
    {
        int[] source = EntityMaskSizing.blitSource(0, 0, 1280, 800);

        assertEquals(1280, source[0]);
        assertEquals(800, source[1]);
    }
}
