package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.audio.FilmAudioAudition;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Small verification run for the film-page audio browser: open the original film editor from
 * the replay page, then open the audio overlay and screenshot it. Kept separate from the
 * compositor-opening debug view so the film page runs without the compositor bridge.
 */
public final class FilmAudioAudit
{
    private static boolean started;

    private final UIReplayModePanel panel;
    private int tick;

    private FilmAudioAudit(UIReplayModePanel panel)
    {
        this.panel = panel;
    }

    public static void start(UIReplayModePanel panel)
    {
        if (started || panel == null)
        {
            return;
        }

        started = true;

        FilmAudioAudit audit = new FilmAudioAudit(panel);

        BbsAeAddon.LOGGER.info("Film audio audit: armed");

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            if (client.player != null && client.world != null)
            {
                audit.tick(client);
            }
        });
    }

    private void tick(MinecraftClient client)
    {
        this.tick += 1;

        if (this.tick == 55)
        {
            /* The saved test film has no audio track. Add the verification tone to its project
             * in memory so the browser can show both a file and the "composition mix" entry. */
            dev.bbsae.client.ui.UICompositorPanel host = BbsAeClient.compositorPanel();

            if (host != null && host.getDocument() != null)
            {
                dev.bbsae.comp.CompDocument doc = host.getDocument();

                if (doc.audio.stream().noneMatch((clip) -> clip != null && "audio/test-tone.wav".equals(clip.path)))
                {
                    dev.bbsae.comp.CompAudio clip = new dev.bbsae.comp.CompAudio(
                        "debug_tone", "Debug tone", "audio/test-tone.wav");

                    clip.sourceDuration = 1F;
                    doc.audio.add(clip);
                    BbsAeAddon.LOGGER.info("Film audio audit: added debug audio clip to {}", doc.id);
                }
            }
        }
        else if (this.tick == 60)
        {
            this.panel.openOriginalEditorFromHost();
        }
        else if (this.tick == 85)
        {
            UIFilmPanel filmPanel = this.panel.filmPanel();

            if (filmPanel == null || filmPanel.getData() == null)
            {
                BbsAeAddon.LOGGER.warn("Film audio audit: no film panel");
                return;
            }

            BbsAeAddon.LOGGER.info("Film audio audit: opening browser for film {}", filmPanel.getData().getId());
            FilmAudioAudition.open(filmPanel);
        }
        else if (this.tick == 105)
        {
            dev.bbsae.client.ui.UICompositorPanel host = BbsAeClient.compositorPanel();

            if (host != null && host.getProject() != null && host.getDocument() != null)
            {
                dev.bbsae.client.audio.CompAudioPlayer.play(host.getProject(), host.getDocument(), 0F);
                BbsAeAddon.LOGGER.info("Film audio audit: comp mix play requested");
            }
        }
        else if (this.tick == 130)
        {
            BbsAeAddon.LOGGER.info("Film audio audit: comp mix status='{}' playing={}",
                dev.bbsae.client.audio.CompAudioPlayer.status(),
                dev.bbsae.client.audio.CompAudioPlayer.isPlaying());
            dev.bbsae.client.audio.CompAudioPlayer.stop();
            BbsAeAddon.LOGGER.info("Film audio audit: screenshot");
            BbsAeClient.debugScreenshot("film-audio", Boolean.getBoolean("bbsae.debugQuitAfterShot"));
        }
    }
}
