package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contracts for the fixes that need a live Minecraft/BBS client to observe end to end.
 *
 * <p>Each assertion names the regression it prevents; if a later refactor removes the mechanism,
 * the test fails before a user has to rediscover the bug in the game.</p>
 */
public class Round114ContractsTest
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
    public void theEntityMatteSuspendsTheTranslucentQueue() throws IOException
    {
        String mask = source("client", "render", "ReplayEntityMask.java");

        assertTrue(mask.contains("FormTranslucentQueue.suspend()"),
            "the mask pass must draw semi-transparent form parts into its own target; without "
                + "suspending the deferred queue they go to the world framebuffer and the cutout has holes");
        assertTrue(mask.contains("FormTranslucentQueue.restore"),
            "the queue state must be restored after the mask pass");
    }

    @Test
    public void theEntityMatteDoesNotApplyTheSubTickTwice() throws IOException
    {
        String mask = source("client", "render", "ReplayEntityMask.java");

        assertFalse(mask.contains("replay.getTick(lastActorTick >= 0 ? lastActorTick : controller.getTick())\n                            + actorTransition"),
            "FilmEntityRenderer interpolates prev/current itself; applying keyframes at tick+transition "
                + "and passing transition again shifts the matte by up to one tick and cuts faces out");
    }

    @Test
    public void theRasterBakeUsesLogicalLayerPixels() throws IOException
    {
        String cache = source("client", "render", "CompLayerSourceCache.java");

        assertTrue(cache.contains("logicalProjection("),
            "the bake projection must be the layer rect in logical pixels");
        assertFalse(cache.contains("rect.x * scale"),
            "scaling the projection while vertices stay logical zooms the baked layer by 1/scale");
    }

    @Test
    public void theFilmEditorHidesVirtualReplayRows() throws IOException
    {
        String mixin = source("mixin", "UIReplayListMixin.java");
        String visibility = source("client", "replay", "VirtualReplayVisibility.java");

        assertTrue(mixin.contains("VirtualReplayVisibility.visible"),
            "the original BBS replay list must filter bbsae's virtual rows");
        assertTrue(visibility.contains("CompReplayBridge.isVirtual"),
            "the filter must use the same marker as the bridge");
        assertTrue(visibility.contains("borrow"),
            "bbsae's own replay mode must be able to keep showing those rows");
    }

    @Test
    public void clipSplitHasBothAPrGestureAndContinuity() throws IOException
    {
        String split = source("client", "ui", "BbsClipSplit.java");
        String clip = source("comp", "CompositionClip.java");

        assertTrue(split.contains("GLFW_KEY_K") && split.contains("GLFW_KEY_LEFT_CONTROL"),
            "Ctrl+K is the Premiere razor gesture");
        assertTrue(split.contains("isInside") || split.contains("tick > start"),
            "a cut on a clip edge must be a no-op");
        assertTrue(clip.contains("breakDownClip"),
            "the composition clip must advance its source offset when split");
        assertTrue(clip.contains("manualTiming"),
            "split clips must opt out of the automatic full-length rewrite");
    }

    @Test
    public void bindingSurvivesAnUnresolvableTarget() throws IOException
    {
        String scene = source("client", "render", "CompScene.java");
        String overlay = source("client", "overlay", "CompositionOverlay.java");

        assertTrue(scene.contains("boundPoint(ctx, layer) != null"),
            "camera/sort/projection semantics must follow the resolved point, not the enabled flag");
        assertTrue(overlay.contains("BindingResolver.setFilm(film)"),
            "the BBS film overlay must provide the film to binding resolution");
        assertTrue(overlay.contains("instance.filmTicks"),
            "a split/time-scaled composition clip needs its absolute film tick for binding");
    }
}
