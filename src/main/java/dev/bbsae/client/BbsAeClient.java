package dev.bbsae.client;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.export.CompExportManager;
import dev.bbsae.client.overlay.BbsPreviewBridge;
import dev.bbsae.client.overlay.CompositionOverlay;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.client.ui.UICompositionClip;
import dev.bbsae.client.ui.UICompositorPanel;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.BBSClientReadyEvent;
import mchorse.bbs_mod.api.client.events.RegisterClipPanelsEvent;
import mchorse.bbs_mod.api.client.events.RegisterDashboardPanelsEvent;
import mchorse.bbs_mod.api.client.events.RegisterFrameOverlaysEvent;
import mchorse.bbs_mod.api.client.events.RegisterReplayActionsEvent;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Client half: registers the frame overlay, the film-scoped compositor workspace and the
 * clip/replay actions that open it.
 */
public class BbsAeClient implements BBSAddonMod
{
    private static UICompositorPanel compositorPanel;

    @Subscribe
    public void onClientReady(BBSClientReadyEvent event)
    {
        CompStore.ensureDemo();
        BbsAeAddon.LOGGER.info("BBS Compositor client ready");

        if (Boolean.getBoolean("bbsae.debugPreview"))
        {
            final int[] ticks = {0};
            final boolean[] opened = {false};

            ClientTickEvents.END_CLIENT_TICK.register((client) ->
            {
                if (client.player == null || client.world == null)
                {
                    return;
                }

                if (client.player == null || client.world == null)
                {
                    return;
                }

                if (!opened[0] && ++ticks[0] > 120 && compositorPanel != null)
                {
                    opened[0] = true;
                    BBSModClient.getDashboard().setPanel(compositorPanel);
                    UIScreen.open(BBSModClient.getDashboard());

                    if (Boolean.getBoolean("bbsae.debugExport"))
                    {
                        compositorPanel.debugExportFilm("export_test", "film_export_test", "main", 1920, 1080, 60);
                    }
                    else if (!Boolean.getBoolean("bbsae.debugStart"))
                    {
                        compositorPanel.debugDemoEffects();
                    }
                }
            });
        }

        ClientTickEvents.END_CLIENT_TICK.register((client) -> CompExportManager.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register((client) ->
        {
            BbsPreviewBridge.detach();
            CompRenderer.clearCaches();
        });

        /* The compositor's "BBS preview" layer shows BBS's own frame. Replay actors are drawn by
         * the film editor controller into the world pass, so the bridge needs the same hook the
         * film panel uses; it is inert while the compositor is not on screen. */
        WorldRenderEvents.AFTER_ENTITIES.register(BbsPreviewBridge::renderFilmActors);

        /* AFTER_ENTITIES draws the replays; LAST runs after translucents/particles, so the copy
         * contains the complete world frame the compositor should show. */
        WorldRenderEvents.LAST.register((context) -> BbsPreviewBridge.captureFrame());
    }

    /**
     * Opens the compositor for the film that is currently open in the film editor. This is the
     * film-scoped entry point; a compositor without a film has no data and is intentionally
     * useless.
     */
    public static void openCompositor()
    {
        if (compositorPanel == null)
        {
            return;
        }

        mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard = BBSModClient.getDashboard();
        UIFilmPanel filmPanel = dashboard.getPanel(UIFilmPanel.class);

        if (filmPanel != null && filmPanel.getData() != null)
        {
            Replay replay = filmPanel.replayEditor == null ? null : filmPanel.replayEditor.getReplay();

            openCompositorForFilm(filmPanel, filmPanel.getData(), replay);
            return;
        }

        dashboard.setPanel(compositorPanel);
        UIScreen.open(dashboard);
    }

    public static void openCompositorForFilm(UIFilmPanel filmPanel, Film film, Replay replay)
    {
        if (compositorPanel == null || filmPanel == null || film == null)
        {
            return;
        }

        compositorPanel.bind(filmPanel, film, replay);

        mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard = BBSModClient.getDashboard();

        dashboard.setPanel(compositorPanel);
        UIScreen.open(dashboard);
    }

    public static void openCompositorForClip(UIFilmPanel filmPanel, Film film, CompositionClip clip)
    {
        if (compositorPanel == null || filmPanel == null || film == null || clip == null)
        {
            return;
        }

        compositorPanel.bindComposition(filmPanel, film, clip.project.get(), clip.comp.get());

        mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard = BBSModClient.getDashboard();

        dashboard.setPanel(compositorPanel);
        UIScreen.open(dashboard);
    }

    @Subscribe
    public void onReplayActions(RegisterReplayActionsEvent event)
    {
        event.register((filmPanel, replaySupplier) ->
        {
            UISection section = new UISection(IKey.constant("合成器"));

            UIButton open = new UIButton(IKey.constant("打开影片合成器"), (button) ->
            {
                Replay replay = replaySupplier.get();

                if (filmPanel.getData() != null)
                {
                    openCompositorForFilm(filmPanel, filmPanel.getData(), replay);
                }
            });

            section.fields.add(open);

            return section;
        });
    }

    @Subscribe
    public void onFrameOverlays(RegisterFrameOverlaysEvent event)
    {
        event.register(new CompositionOverlay());
    }

    @Subscribe
    public void onClipPanels(RegisterClipPanelsEvent event)
    {
        event.register(CompositionClip.class, UICompositionClip::new);
    }

    @Subscribe
    public void onDashboardPanels(RegisterDashboardPanelsEvent event)
    {
        compositorPanel = new UICompositorPanel(event.dashboard);

        event.dashboard.getPanels().registerPanel(
            compositorPanel,
            IKey.constant("合成器"),
            Icons.FILM
        );

        BbsAeAddon.LOGGER.info("BBS Compositor dashboard panel registered");
    }
}
