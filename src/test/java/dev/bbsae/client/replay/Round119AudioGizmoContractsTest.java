package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for the "silent original film page", "flickering gizmo", "Alt click does nothing"
 * reports.
 */
public class Round119AudioGizmoContractsTest
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
    public void compositionAudioRunsInTheOriginalPlaybackClock() throws IOException
    {
        String mixin = source("mixin", "AudioClientClipMixin.java");
        String player = source("client", "audio", "CompFilmAudio.java");
        String config = Files.readString(Path.of("src", "main", "resources", "bbsae.mixins.json"),
            StandardCharsets.UTF_8);

        assertTrue(mixin.contains("@Mixin(AudioClientClip.class)") && mixin.contains("manageSounds"),
            "the composition mix must follow BBS' own per-frame audio clock");
        assertTrue(mixin.contains("CompFilmAudio.manage(context)"),
            "the frame hook must feed the composition player");
        assertTrue(player.contains("instance.time()"), "the player seeks to composition time");
        assertTrue(player.contains("context.playing"), "pausing the film must pause the composition");
        assertTrue(config.contains("AudioClientClipMixin"), "the mixin must be registered");
    }

    @Test
    public void bbsExportMixesCompositionAudio() throws IOException
    {
        String renderer = source("mixin", "AudioRendererMixin.java");
        String session = source("mixin", "PanelVideoExportSessionMixin.java");
        String world = source("mixin", "WorldVideoExportSessionMixin.java");
        String exporter = source("client", "audio", "CompFilmAudioExport.java");
        String config = Files.readString(Path.of("src", "main", "resources", "bbsae.mixins.json"),
            StandardCharsets.UTF_8);

        assertTrue(renderer.contains("renderAudio") && renderer.contains("mixInto")
                && renderer.contains("setReturnValue(true)"),
            "BBS' rendered track must gain the composition mix and report success when only the "
                + "composition has audio");
        assertTrue(session.contains("editor.getData()"), "the panel export must scope its film");
        assertTrue(world.contains("@Shadow") && world.contains("Film film"),
            "the F6 export must scope its film");
        assertTrue(exporter.contains("compositionTimeAt") && exporter.contains("writeWav"),
            "the composition audio must be placed on the film timeline and written as WAV");
        assertTrue(config.contains("AudioRendererMixin")
                && config.contains("PanelVideoExportSessionMixin")
                && config.contains("WorldVideoExportSessionMixin"),
            "all three export hooks must be registered");
    }

    @Test
    public void gizmoIsCapturedEveryFrameEvenWhenPickingIsCached() throws IOException
    {
        String viewport = source("client", "replay", "UIReplayViewport.java");
        String pick = source("client", "replay", "ReplayPickPass.java");

        assertTrue(pick.contains("captureGizmoVisual") && pick.contains("captureVisual"),
            "the visual gizmo placement must be captured outside the cached id pass");
        assertTrue(viewport.contains("captureGizmoVisual(this.session"),
            "the viewport must capture the gizmo on every displayed frame");
        assertTrue(viewport.indexOf("captureGizmoVisual") < viewport.indexOf("gizmo.renderGizmo"),
            "the capture has to precede the deferred UI pass that draws it");
    }

    @Test
    public void altClickSelectsOtherModelsAndHoveredPartsLightUp() throws IOException
    {
        String viewport = source("client", "replay", "UIReplayViewport.java");
        String pick = source("client", "replay", "ReplayPickPass.java");

        assertTrue(viewport.contains("ReplayClickRules.action(")
                && viewport.contains("Window.isAltPressed()"),
            "Alt + click must select the other actor; the branch is decided by the pure rules");
        assertTrue(occurrences(viewport, "this.pickHandler.accept(picked)") == 1,
            "a plain click must not select another actor; that made body-part picks impossible");
        assertTrue(pick.contains("renderHighlight") && pick.contains("renderPreview"),
            "the hovered/selected part must be lit with the picker-preview shader like BBS");
        assertTrue(viewport.contains("pick.renderHighlight(context"),
            "the viewport must draw the stencil highlight");
        assertTrue(pick.contains("indexOf(part.form, part.bone)"),
            "the selected bone stays lit when the pointer leaves it");
    }

    private static int occurrences(String text, String needle)
    {
        int count = 0;
        int index = 0;

        while ((index = text.indexOf(needle, index)) >= 0)
        {
            count += 1;
            index += needle.length();
        }

        return count;
    }
}
