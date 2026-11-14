package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for the two follow-up reports: the replay cutout must be the complete actor, and a
 * maximized/fullscreen preview must not keep baking its layers at the 1024 draft cap.
 */
public class CutoutClarityContractsTest
{
    private static String source(String... path) throws IOException
    {
        Path file = Path.of("src", "main", "java", "dev", "bbsae");

        for (String part : path)
        {
            file = file.resolve(part);
        }

        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    public void theCutoutUsesTheWorldActorsOwnPixelsFirst() throws IOException
    {
        String renderer = source("client", "render", "LayerContentRenderer.java");
        int cutout = renderer.indexOf("ReplayEntityKey.drawCutout(");
        int actors = renderer.indexOf("ReplayEntityKey.drawActors(");

        assertTrue(cutout > 0, "the complete world-color cutout path must exist");
        assertTrue(actors > cutout,
            "drawCutout must run before the actor-only fallback; the fallback can show draw-order "
                + "back faces as the reported self-clipping");
    }

    @Test
    public void theCutoutUsesTheUnoccludedCoverage() throws IOException
    {
        String key = source("client", "render", "ReplayEntityKey.java");

        assertTrue(key.contains("drawCutout"), "the cutout entry point must exist");
        assertTrue(key.contains("draw(layer, context, model, projection, false)"),
            "the complete cutout uses the unoccluded coverage, not the world-depth matte");
    }

    @Test
    public void theCoverageIsAUnionNotADepthImage() throws IOException
    {
        String mask = source("client", "render", "ReplayEntityMask.java");

        assertTrue(mask.contains("plainTarget = new Target(false)"),
            "the unoccluded cutout coverage must be a union; self depth would hide body parts "
                + "behind semi-transparent outer layers and create holes");
        assertTrue(mask.contains("GL11.glDepthMask(false)"),
            "the unoccluded pass must not write self depth");
    }

    @Test
    public void theMaskUsesTheSameTickAsTheWorldActor() throws IOException
    {
        String mask = source("client", "render", "ReplayEntityMask.java");

        assertTrue(mask.contains("actorTick(controller)"),
            "FilmEditorController renders running actors at cursor + 1; the matte must match");
        assertTrue(mask.contains("capturedSerial == serial"),
            "the first actor capture wins, so an onion-skin pass cannot overwrite the pose");
    }

    @Test
    public void aPreviewRasterFollowsItsActualTarget() throws IOException
    {
        String cache = source("client", "render", "CompLayerSourceCache.java");

        assertTrue(cache.contains("context.renderWidth()"),
            "the preview raster cap must follow the device-pixel target");
        assertTrue(cache.contains("Math.min(4096"),
            "there is still a hard 4K raster ceiling");
        assertFalse(cache.contains("return MAX_EDGE;\n        }\n\n        return Math.max(MAX_EDGE"),
            "a fixed 1024 preview cap was the fullscreen mosaic");
    }
}
