package dev.bbsae.client.replay;

import dev.bbsae.comp.CompLayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-120 contracts: the compositor audio must not be paused by the BBS film-audio bridge, new
 * text starts at the requested letter spacing, and the replay gizmo uses BBS' original target
 * cascade plus per-target handle masks.
 */
public class Round120AudioGizmoDefaultsTest
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
    public void theCompositorOwnsItsPreviewAudio() throws IOException
    {
        String filmAudio = source("client", "audio", "CompFilmAudio.java");
        String player = source("client", "audio", "CompAudioPlayer.java");
        String client = source("client", "BbsAeClient.java");

        assertTrue(client.contains("isCompositorActive()"),
            "the audio bridge needs to know when the compositor (and its own transport) is up");
        assertTrue(filmAudio.contains("isCompositorActive()") && filmAudio.contains("pauseSources()"),
            "while the compositor is visible the film bridge must stay out of the way");
        assertFalse(filmAudio.contains("CompAudioPlayer.pause()"),
            "the bridge must never pause the compositor's preview player: pause() clears `wanted`, "
                + "so the compositor never resumes and the whole page goes silent");
        assertTrue(player.contains("wanted = true;"),
            "CompAudioPlayer.sync must be able to restore playback intent after an external pause");
    }

    @Test
    public void newTextDefaultsToNineSpacingUnits()
    {
        assertEquals(9F, new CompLayer("text", "Text", "text").tracking, 0.0001F);
    }

    @Test
    public void gizmoUsesTheOriginalBoneAnchorRootCascade() throws IOException
    {
        String session = source("client", "replay", "ReplayEditSession.java");
        String viewport = source("client", "replay", "UIReplayViewport.java");
        String pick = source("client", "replay", "ReplayPickPass.java");
        String panel = source("client", "replay", "UIReplayModePanel.java");

        assertTrue(session.contains("FilmTarget editTarget("),
            "the session must expose the one edit-target cascade");
        assertTrue(session.contains("FilmTarget.bone(") && session.contains("FilmTarget.anchor(")
                && session.contains("FilmTarget.root("),
            "all three BBS edit targets must be produced");
        assertTrue(session.contains("getGizmoAnchorCompositeMatrix") && session.contains("anchorGizmoDrag"),
            "the anchor gizmo needs BBS' anchor composite for its drag");
        assertTrue(pick.contains("target.kind() == FilmTarget.Kind.ROOT")
                && pick.contains("Gizmo.HandleMask.ALL"),
            "bones/anchors get every handle; the replay root keeps BBS' restricted mask");
        assertTrue(pick.contains("FilmMatrices.getGizmoBoneCompositeMatrix")
                && pick.contains("FilmMatrices.getGizmoAnchorCompositeMatrix"),
            "the gizmo placement must come from BBS' own matrix helpers");
        assertTrue(viewport.contains("setEditTarget") && panel.contains("setEditTarget(this::currentEditTarget)"),
            "the panel's target must reach the pick pass and the visual capture");
    }
}
