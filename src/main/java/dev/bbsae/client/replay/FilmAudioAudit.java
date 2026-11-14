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

                /* The film needs a composition window for the original page and the BBS export
                 * audio render to see this composition at all. */
                mchorse.bbs_mod.film.Film film = host.film();

                if (film == null && this.panel.filmPanel() != null)
                {
                    film = this.panel.filmPanel().getData();
                }

                if (film != null && host.getProject() != null)
                {
                    dev.bbsae.comp.CompositionClip.ensureForFilm(film, host.getProject().id, doc.id, doc.duration);
                    BbsAeAddon.LOGGER.info("Film audio audit: composition clip ensured for film {} ({} clips)",
                        film.getId(), film.camera.getClips(dev.bbsae.comp.CompositionClip.class).size());
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

            /* Play the film in the ORIGINAL editor. This is the path the report is about: the
             * composition audio is not an AudioClip, so it only plays when CompFilmAudio follows
             * the runner's clock. */
            UIFilmPanel filmPanel = this.panel.filmPanel();

            if (filmPanel != null && filmPanel.getData() != null)
            {
                filmPanel.togglePlayback();
                BbsAeAddon.LOGGER.info("Film audio audit: original film playback started={}", filmPanel.isRunning());
            }
        }
        else if (this.tick == 160)
        {
            UIFilmPanel filmPanel = this.panel.filmPanel();

            BbsAeAddon.LOGGER.info("Film audio audit: original playback running={} compAudioSources={} status='{}'",
                filmPanel != null && filmPanel.isRunning(),
                dev.bbsae.client.audio.CompFilmAudio.sourceCount(),
                dev.bbsae.client.audio.CompFilmAudio.status());

            if (filmPanel != null && filmPanel.isRunning())
            {
                filmPanel.togglePlayback();
            }
        }
        else if (this.tick == 175)
        {
            this.renderBbsExportAudio();
        }
        else if (this.tick == 195)
        {
            BbsAeAddon.LOGGER.info("Film audio audit: screenshot");
            BbsAeClient.debugScreenshot("film-audio", Boolean.getBoolean("bbsae.debugQuitAfterShot"));
        }
    }

    /**
     * Runs BBS' own export audio renderer on the test film.
     *
     * <p>This is the exact call {@code PanelVideoExportSession.prepare()} and the F6 session make.
     * The test film has no AudioClip, so a non-empty WAV here proves the bbsae composition audio
     * entered BBS' export path (the report: "the BBS export has no sound").</p>
     */
    private void renderBbsExportAudio()
    {
        UIFilmPanel filmPanel = this.panel.filmPanel();

        if (filmPanel == null || filmPanel.getData() == null)
        {
            BbsAeAddon.LOGGER.warn("Film audio audit: no film for the export audio render");
            return;
        }

        mchorse.bbs_mod.film.Film film = filmPanel.getData();
        java.io.File out = new java.io.File(
            MinecraftClient.getInstance().runDirectory, "bbsae-audio-audit.wav");

        out.delete();

        try
        {
            boolean ok = mchorse.bbs_mod.audio.AudioRenderer.renderAudio(
                out,
                film.camera.getClips(mchorse.bbs_mod.camera.clips.misc.AudioClip.class),
                film.camera.calculateDuration(),
                48000,
                0F,
                0F
            );

            float peak = 0F;
            int frames = 0;

            if (out.isFile())
            {
                try (java.io.InputStream stream = new java.io.FileInputStream(out))
                {
                    mchorse.bbs_mod.audio.Wave wave = new mchorse.bbs_mod.audio.wav.WaveReader().read(stream);

                    if (wave != null)
                    {
                        frames = wave.data.length / 2 / Math.max(1, wave.numChannels);

                        for (int i = 0; i + 1 < wave.data.length; i += 2)
                        {
                            int value = (short) ((wave.data[i] & 0xFF) | (wave.data[i + 1] << 8));

                            peak = Math.max(peak, Math.abs(value) / 32768F);
                        }
                    }
                }
            }

            BbsAeAddon.LOGGER.info("Film audio audit: BBS export audio ok={} file={} bytes={} frames={} peak={} comps={}",
                ok, out.getName(), out.length(), frames, peak,
                film.camera.getClips(dev.bbsae.comp.CompositionClip.class).size());
        }
        catch (Throwable e)
        {
            BbsAeAddon.LOGGER.warn("Film audio audit: BBS export audio render failed", e);
        }
    }
}
