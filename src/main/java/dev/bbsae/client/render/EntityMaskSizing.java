package dev.bbsae.client.render;

import java.awt.Dimension;

/**
 * Sizing rules for the replay-entity coverage pass.
 *
 * <p>The matte must be rendered at the <b>world frame's</b> pixel size, not the window's: BBS'
 * custom-size preview/recorder renders the world at the film resolution while the Minecraft window
 * can have another aspect. Sampling a window-shaped coverage texture over the world plate stretched
 * the matte by the aspect difference, which is the "entity key is offset" report. The same size is
 * needed for the depth blit, whose source rectangle must describe the world framebuffer.</p>
 */
final class EntityMaskSizing
{
    private EntityMaskSizing()
    {}

    /** World-frame size when known, otherwise the window framebuffer (the old fallback). */
    static Dimension targetSize(int worldWidth, int worldHeight, int windowWidth, int windowHeight)
    {
        int width = worldWidth > 0 ? worldWidth : windowWidth;
        int height = worldHeight > 0 ? worldHeight : windowHeight;

        return new Dimension(Math.max(1, width), Math.max(1, height));
    }

    /** Source rectangle for the depth blit: the world framebuffer's extent, not the target's. */
    static int[] blitSource(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight)
    {
        return new int[]
        {
            sourceWidth > 0 ? sourceWidth : targetWidth,
            sourceHeight > 0 ? sourceHeight : targetHeight,
        };
    }
}
