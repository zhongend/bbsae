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
            final int[] afterOpen = {0};
            final String view = System.getProperty("bbsae.debugView", "");

            ClientTickEvents.END_CLIENT_TICK.register((client) ->
            {
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

                    return;
                }

                /* A requested verification state is applied after the panel has been laid out for
                 * a few frames: the curve editor's geometry is only valid once it has a size.
                 * Several views may be requested ("curve,assets"): one is applied every 8 seconds
                 * so a single client run can be screenshotted in each of them. */
                if (opened[0] && !view.isBlank())
                {
                    final String[] views = view.split(",");
                    final int[] applied = {0};

                    /* One view = one application. Re-applying it every eight seconds used to
                     * re-enter the workspace and fight whatever the user was doing (and a live
                     * window receives real mouse input), which made verification screenshots
                     * non-deterministic. A list still cycles, for multi-shot runs. */
                    if (++afterOpen[0] == 20 + applied[0] * 160 && (views.length > 1 || applied[0] == 0))
                    {
                        compositorPanel.debugView(views[applied[0] % views.length].trim());
                        applied[0]++;
                    }
                }
            });
        }

        /* Verification hook: save one screenshot after a fixed number of ticks, so a rendering
         * change can be checked by looking at it instead of by trusting a log line.
         *
         *   ./gradlew runClientView -PbbsaeView=camera -Dbbsae.debugShot=viewport
         */
        String shot = System.getProperty("bbsae.debugShot", "");

        if (!shot.isBlank())
        {
            int shotAt = Integer.getInteger("bbsae.debugShotTicks", 260);
            final int[] shotTicks = {0};

            ClientTickEvents.END_CLIENT_TICK.register((client) ->
            {
                if (client.world == null || client.player == null)
                {
                    return;
                }

                if (++shotTicks[0] == shotAt)
                {
                    net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
                        client.runDirectory,
                        shot,
                        client.getFramebuffer(),
                        (text) -> BbsAeAddon.LOGGER.info("screenshot: {}", text.getString())
                    );
                }
            });
        }

        /* OS drag & drop: one GLFW callback, routed to the compositor when it is the open screen. */
        dev.bbsae.client.ui.CompFileDrop.install((files) ->
        {
            if (compositorPanel == null)
            {
                BbsAeAddon.LOGGER.info("Ignored a file drop: the compositor panel was never built");

                return;
            }

            /* Only when the compositor is the panel on screen: dropping a file into a dashboard
             * page the user is not looking at would edit a project invisibly. */
            if (compositorPanel.isVisible())
            {
                compositorPanel.handleDroppedFiles(files);
            }
            else
            {
                BbsAeAddon.LOGGER.info("Ignored a file drop: open the compositor first");
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register((client) -> CompExportManager.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register((client) ->
        {
            BbsPreviewBridge.detach();
            CompRenderer.clearCaches();
        });

        /* The compositor's "BBS preview" layer shows BBS's own frame. Replay actors are drawn by
         * the film editor controller into the world pass, so the bridge needs the same hook the
         * film panel uses; it is inert while the compositor is not on screen. */
        WorldRenderEvents.START.register((context) -> dev.bbsae.client.render.ReplayEntityMask.beginFrame());
        WorldRenderEvents.AFTER_ENTITIES.register(BbsPreviewBridge::renderFilmActors);

        /* The replay entity matte needs a second, depth-only pass of the film's actors. It runs
         * after the actor pass so the pose stack, the camera and the transition are exactly the
         * ones the world frame used, which is what makes its depth comparable with the world's. */
        WorldRenderEvents.AFTER_ENTITIES.register(BbsPreviewBridge::renderEntityMask);

        /* AFTER_ENTITIES draws the replays; AFTER_TRANSLUCENT runs after translucents/particles
         * but still inside the world pass, before BBS's FrameOverlays draw the composition. Using
         * LAST here was too late in practice: the BBS preview layer could sample the previous
         * composition frame, producing a scanlined feedback ghost in the exported video. */
        WorldRenderEvents.AFTER_TRANSLUCENT.register((context) -> BbsPreviewBridge.captureFrame());
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
            BbsAeAddon.ICON
        );

        BbsAeAddon.LOGGER.info("BBS Compositor dashboard panel registered");
    }
}
