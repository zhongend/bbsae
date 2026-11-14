package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.ui.UICompositorPanel;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Debug run for the "composition freezes in BBS original playback" report.
 *
 * <p>Enabled by {@code -Dbbsae.bbsPlaybackAudit=true}. It shortens the composition to 3 s, puts a
 * long composition clip on the camera timeline, switches to the original BBS film editor and plays
 * it for 10 s. The overlay trace prints the clipped render time every second; without the hold fix
 * the whole composition became an empty stage after 3 s. Screenshots are taken at 2 s and 8 s.</p>
 */
public final class BbsPlaybackAudit
{
    private static boolean started;

    private final UIReplayModePanel panel;
    private int tick;
    private boolean failed;

    private BbsPlaybackAudit(UIReplayModePanel panel)
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

        BbsPlaybackAudit audit = new BbsPlaybackAudit(panel);

        BbsAeAddon.LOGGER.info("BBS playback audit: armed");

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

        if (this.tick == 60)
        {
            this.prepare();
        }
        else if (this.tick == 70)
        {
            this.play();
        }
        else if (this.tick == 110)
        {
            this.shot("bbs-play-2s");
        }
        else if (this.tick == 190)
        {
            this.shot("bbs-play-8s");
        }
        else if (this.tick == 210)
        {
            this.finish(client);
        }
    }

    private void prepare()
    {
        UICompositorPanel compositor = BbsAeClient.compositorPanel();
        UIFilmPanel filmPanel = this.panel.filmPanel();
        CompProject project = compositor == null ? null : compositor.getProject();
        CompDocument doc = compositor == null ? null : compositor.getDocument();

        if (filmPanel == null || filmPanel.getData() == null || project == null || doc == null)
        {
            this.fail("missing film/compositor/doc");
            return;
        }

        Film film = filmPanel.getData();

        /* Short composition whose layers end with it. Past 3 s every layer is inactive: without
         * the hold clamp the overlay then paints only the stage background. */
        for (CompLayer layer : doc.layers)
        {
            if (layer != null && layer.out > 0F)
            {
                layer.out = Math.min(layer.out, 3F);
            }
        }

        doc.resizeDuration(3F);
        doc.workAreaOut = 3F;

        CompositionClip clip = CompositionClip.ensureForFilm(film, project.id, doc.id, 0F);

        if (clip == null)
        {
            this.fail("could not put a composition clip on the camera timeline");
            return;
        }

        clip.tick.set(0);
        clip.duration.set(400);
        clip.hold.set(true);
        clip.timeScale.set(1F);
        clip.offset.set(0);
        clip.manualTiming.set(true);

        if (project.settings != null)
        {
            project.settings.showDuringExternalPlayback = true;
        }

        BbsAeAddon.LOGGER.info("BBS playback audit: prepared comp={} duration=3s layers={} clip={} ticks",
            doc.id, doc.layers.size(), clip.duration.get());

        this.panel.openOriginalEditorFromHost();
    }

    private void play()
    {
        UIFilmPanel filmPanel = this.panel.filmPanel();

        if (filmPanel == null || filmPanel.getData() == null)
        {
            this.fail("film panel disappeared before playback");
            return;
        }

        try
        {
            Film film = filmPanel.getData();

            filmPanel.getRunner().setWork(film.camera);
            filmPanel.getRunner().setManual(null);
            filmPanel.getRunner().setCursor(0F);
            filmPanel.getRunner().setPlaying(true);

            BbsAeAddon.LOGGER.info("BBS playback audit: playing film={} duration={} running={}",
                film.getId(), film.camera.calculateDuration(), filmPanel.getRunner().isRunning());
        }
        catch (RuntimeException e)
        {
            this.fail("playback start threw " + e);
        }
    }

    private void shot(String name)
    {
        BbsAeAddon.LOGGER.info("BBS playback audit: shot {} at tick={} playhead={}s",
            name, this.tick, this.panel.playheadSeconds());
        BbsAeClient.debugScreenshot(name, false);
    }

    private void finish(MinecraftClient client)
    {
        UIFilmPanel filmPanel = this.panel.filmPanel();

        if (filmPanel != null)
        {
            try
            {
                filmPanel.getRunner().setPlaying(false);
            }
            catch (RuntimeException ignored)
            {}
        }

        BbsAeAddon.LOGGER.info("BBS playback audit: done failed={}", this.failed);

        if (Boolean.getBoolean("bbsae.debugQuitAfterShot"))
        {
            client.execute(client::scheduleStop);
        }
    }

    private void fail(String reason)
    {
        this.failed = true;
        BbsAeAddon.LOGGER.error("BBS playback audit: {}", reason);
    }
}
