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
            "the unoccluded cutout remains the fallback when no world depth is available");
    }

    @Test
    public void theWhollyOccludedCutoutIsPreferredOverTheUnoccludedUnion() throws IOException
    {
        String key = source("client", "render", "ReplayEntityKey.java");
        String layer = source("client", "render", "LayerContentRenderer.java");
        int occluded = layer.indexOf("ReplayEntityKey.drawCutoutOccluded(");
        int unoccluded = layer.indexOf("ReplayEntityKey.drawCutout(");

        assertTrue(key.contains("drawCutoutOccluded"),
            "the world-color cutout needs a world-depth variant");
        assertTrue(occluded > 0 && occluded < unoccluded,
            "a layer with entityOcclusion must cut with the occluded matte first; the unoccluded "
                + "union includes body pixels behind a wall and paints the wall into the actor");
        assertTrue(layer.contains("layer.entityOcclusion"));
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

    @Test
    public void vectorShapesAndCustomTextFollowTheSupersample() throws IOException
    {
        String shape = source("client", "render", "ShapeCache.java");
        String layer = source("client", "render", "LayerContentRenderer.java");

        assertTrue(shape.contains("g.scale(pixelScale, pixelScale)"),
            "a supersampled preview must rasterize vector shapes at the same pixel scale");
        assertTrue(shape.contains("rasterScale"),
            "ShapeCache needs an explicit raster-scale entry point");
        assertTrue(layer.contains("fontSize * textOversample"),
            "custom font atlases must be requested at the preview scale");
        assertTrue(layer.contains("raster.texture.width"),
            "shape quads must sample the full supersampled texture, not the composition extents");
    }
}
