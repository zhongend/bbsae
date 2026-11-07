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
import mchorse.bbs_mod.api.client.events.RegisterL10nEvent;
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

    /**
     * Localises the clip type in BBS's own clip/effect picker.
     *
     * <p>BBS labels a clip type through {@code UIKeys.C_CLIP}, whose key is
     * {@code bbs.ui.camera.clips.<link>}. Without an entry BBS falls back to the key itself, so
     * the add-clip menu showed the raw string
     * {@code bbs.ui.camera.clips.bbsae:composition} — the "一串英文" the user saw. The keys are
     * registered here (and again on ready for the case where the addon loads after BBS built its
     * language table) so the menu shows the same name as the rest of the panel: 合成器.</p>
     */
    @Subscribe
    public void onL10n(RegisterL10nEvent event)
    {
        localizeClipName(event.l10n);
    }

    @Subscribe
    public void onClientReady(BBSClientReadyEvent event)
    {
        CompStore.ensureDemo();

        if (BBSModClient.getL10n() != null)
        {
            localizeClipName(BBSModClient.getL10n());
        }

        BbsAeAddon.LOGGER.info("BBS AE client ready");

        /* Verification hook: force the OS window (and therefore the framebuffer) to an exact
         * size. Minecraft's --width/--height are not reliable on every launcher/window manager;
         * layout screenshots must be taken at a known pixel size. */
        final String debugWindow = System.getProperty("bbsae.debugWindow", "");

        if (!debugWindow.isBlank())
        {
            final int[] windowTicks = {0};

            ClientTickEvents.END_CLIENT_TICK.register((client) ->
            {
                if (client.getWindow() == null || ++windowTicks[0] != 40)
                {
                    return;
                }

                try
                {
                    String[] size = debugWindow.split("x");

                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.getWindow().getHandle(),
                        Integer.parseInt(size[0]), Integer.parseInt(size[1]));
                    BbsAeAddon.LOGGER.info("Debug window resized to {}", debugWindow);
                }
                catch (RuntimeException e)
                {
                    BbsAeAddon.LOGGER.warn("Could not resize the debug window to {}", debugWindow, e);
                }
            });
        }

        if (Boolean.getBoolean("bbsae.debugPreview"))
        {
            final int[] ticks = {0};
            final boolean[] opened = {false};
            final int[] afterOpen = {0};
            final String view = System.getProperty("bbsae.debugView", "");
            final String[] views = view.split(",");
            final int[] applied = {0};

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
                    /* One view = one application. Re-applying it every eight seconds used to
                     * re-enter the workspace and fight whatever the user was doing (and a live
                     * window receives real mouse input), which made verification screenshots
                     * non-deterministic. A list still cycles, for multi-shot runs. */
                    if (++afterOpen[0] == 20 + applied[0] * 160 && (views.length > 1 || applied[0] == 0))
                    {
                        String viewName = views[applied[0] % views.length].trim();

                        compositorPanel.debugView(viewName);
                        applied[0]++;

                        /* Multi-shot verification: one screenshot per requested view. This keeps
                         * a multi-state visual audit in a single client launch instead of starting
                         * the game once per screenshot. */
                        if (Boolean.getBoolean("bbsae.debugShotEachView"))
                        {
                            String prefix = System.getProperty("bbsae.debugShotPrefix", "round31-");
                            String shotName = prefix + viewName.replaceAll("[^A-Za-z0-9_-]", "-");
                            final int[] wait = {70};
                            final boolean[] done = {false};

                            ClientTickEvents.END_CLIENT_TICK.register((c) ->
                            {
                                if (c.world == null || c.player == null || done[0])
                                {
                                    return;
                                }

                                if (--wait[0] <= 0)
                                {
                                    done[0] = true;
                                    saveVerificationShot(c, shotName);
                                }
                            });
                        }
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
                    saveVerificationShot(client, shot);
                }
            });
        }

        /* Composition-frame verification: writes the exact CompRenderer output (no viewport gizmos
         * or editor overlays) so a 3D placement question can be answered from pixels. */
        String compShot = System.getProperty("bbsae.debugCompShot", "");

        if (!compShot.isBlank())
        {
            int at = Integer.getInteger("bbsae.debugCompShotTicks", 300);
            final int[] compTicks = {0};

            ClientTickEvents.END_CLIENT_TICK.register((client) ->
            {
                if (client.world == null || client.player == null || compositorPanel == null)
                {
                    return;
                }

                if (++compTicks[0] == at)
                {
                    compositorPanel.debugRenderCompShot(compShot);
                }
            });
        }

        /* Font verification: renders a text sheet through the composition renderer, so the
         * "imported font does not show" report can be checked by looking at pixels. */
        String fontShot = System.getProperty("bbsae.debugFontShot", "");

        if (!fontShot.isBlank())
        {
            int fontShotAt = Integer.getInteger("bbsae.debugFontShotTicks", 120);
            final int[] fontTicks = {0};

            ClientTickEvents.END_CLIENT_TICK.register((client) ->
            {
                if (client.world == null || client.player == null)
                {
                    return;
                }

                if (++fontTicks[0] == fontShotAt)
                {
                    saveFontSheet(client, fontShot);
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

        /* Capture the camera/stack/matrices at the exact moment BBS is about to draw a world
         * actor. BEFORE_ENTITIES fires before the world projection exists, and the live state
         * after the actor pass may be on a limb transform; the actor's own entry point is the only
         * state guaranteed to be the one that put it on screen. */
        mchorse.bbs_mod.api.client.events.FormPoseEvents.ACTOR_BEFORE.register(
            dev.bbsae.client.render.ReplayEntityMask::captureActorState);
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
        WorldRenderEvents.AFTER_ENTITIES.register((context) -> BbsPreviewBridge.captureEarlyWorldForCustomSize());
    }

    /**
     * Saves a screenshot for a verification run and, with {@code -Dbbsae.debugQuitAfterShot=true},
     * closes the client once the PNG is on disk.
     *
     * <p>Automated visual checks need a bounded run: without this the client stays open forever
     * after the shot (the screenshot callback only logs), so a script would have to guess when it
     * is safe to kill the process. Quitting from the callback is deterministic and keeps the test
     * client's world saved cleanly.</p>
     */
    private static void saveVerificationShot(net.minecraft.client.MinecraftClient client, String name)
    {
        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
            client.runDirectory,
            name,
            client.getFramebuffer(),
            (text) ->
            {
                BbsAeAddon.LOGGER.info("screenshot: {}", text.getString());

                if (Boolean.getBoolean("bbsae.debugQuitAfterShot"))
                {
                    client.execute(client::scheduleStop);
                }
            }
        );
    }

    /**
     * Renders a font test sheet through the real composition renderer and writes it as a PNG.
     *
     * <p>Verification only ({@code -Dbbsae.debugFontShot=<name>}). It exists because the font path
     * cannot be exercised without a client: BBS' FontManager needs Minecraft's texture manager, and
     * "the imported font does not show" is a pixel-level question. The sheet draws the same text
     * with a Latin-only font (Times Bold) and with the default font, in Latin and in Chinese, so
     * the per-character fallback is visible in one image.</p>
     */
    private static void saveFontSheet(net.minecraft.client.MinecraftClient client, String name)
    {
        dev.bbsae.comp.CompProject project = new dev.bbsae.comp.CompProject();
        dev.bbsae.comp.CompDocument comp = new dev.bbsae.comp.CompDocument();

        comp.id = "fonttest";
        comp.width = 1280;
        comp.height = 720;
        comp.fps = 30F;
        comp.duration = 2F;
        comp.background = 0xFF10141C;
        project.comps.add(comp);

        String font = System.getProperty("bbsae.debugFontShotFont", "timesbd.ttf");

        /* Optional end-to-end import: run the same extraction/copy the editor runs, then render
         * with the resulting file. A .ttc here proves the collection path, which is the one that
         * matters for Chinese fonts. */
        String importSource = System.getProperty("bbsae.debugFontImportSrc", "");

        if (!importSource.isBlank())
        {
            String imported = importFontForSheet(new java.io.File(importSource));

            if (imported != null)
            {
                font = imported;
            }
        }

        float sheetSize = Float.parseFloat(System.getProperty("bbsae.debugFontShotSize", "54"));

        fontSheetLayer(comp, "latin_custom", 80F, 100F, "ABC abc 123 — " + font, font, sheetSize);
        fontSheetLayer(comp, "han_custom", 80F, 240F, "中文：新建文字（使用导入的字体）", font, sheetSize);
        fontSheetLayer(comp, "punct_custom", 80F, 380F, "标点：（），。；：！？“”《》、", font, sheetSize);
        fontSheetLayer(comp, "latin_default", 80F, 520F, "ABC abc 123 — Default font", "", sheetSize);
        fontSheetLayer(comp, "han_default", 80F, 640F, "中文：新建文字（默认字体）", "", sheetSize);

        dev.bbsae.client.render.GlFramebuffer framebuffer =
            dev.bbsae.client.render.CompRenderer.renderFrame(project, comp, 0, false, 1F);

        if (framebuffer == null)
        {
            BbsAeAddon.LOGGER.warn("font shot: the composition renderer returned no frame");

            return;
        }

        try
        {
            int[] pixels = framebuffer.readTopDown();
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                comp.width, comp.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);

            image.setRGB(0, 0, comp.width, comp.height, pixels, 0, comp.width);

            java.io.File output = new java.io.File(client.runDirectory, "screenshots/" + name + ".png");

            output.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(image, "png", output);
            BbsAeAddon.LOGGER.info("font shot: {}", output.getAbsolutePath());
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.error("font shot failed", e);
        }
        finally
        {
            dev.bbsae.client.render.FboPool.release(framebuffer);

            if (Boolean.getBoolean("bbsae.debugQuitAfterShot"))
            {
                client.execute(client::scheduleStop);
            }
        }
    }

    /**
     * Copies (and, for a {@code .ttc}, extracts) a font into BBS' fonts folder exactly like the
     * editor's importer does. Returns the stored file name, or null when it failed.
     */
    private static String importFontForSheet(java.io.File file)
    {
        try
        {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            String name = file.getName();

            if (dev.bbsae.client.ui.FontImport.isCollection(data))
            {
                data = dev.bbsae.client.ui.FontImport.extractFace(data, 0);

                int dot = name.lastIndexOf('.');

                name = (dot > 0 ? name.substring(0, dot) : name) + "-face0.ttf";
            }

            java.io.File folder = mchorse.bbs_mod.fonts.FontManager.getFolder();

            if (folder == null)
            {
                return null;
            }

            java.nio.file.Files.createDirectories(folder.toPath());

            java.nio.file.Path target = dev.bbsae.client.ui.FontImport.uniqueTarget(folder.toPath(), name, data);

            java.nio.file.Files.write(target, data);
            mchorse.bbs_mod.BBSModClient.getFonts().accept(target,
                mchorse.bbs_mod.utils.watchdog.WatchDogEvent.CREATED);
            BbsAeAddon.LOGGER.info("font sheet: imported {} as {}", file, target.getFileName());

            return target.getFileName().toString();
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.error("font sheet: could not import {}", file, e);

            return null;
        }
    }

    private static void fontSheetLayer(dev.bbsae.comp.CompDocument comp, String id, float x, float y,
                                       String text, String fontName, float fontSize)
    {
        dev.bbsae.comp.CompLayer layer = new dev.bbsae.comp.CompLayer(id, id, "text");

        layer.x = x;
        layer.y = y;
        layer.width = 1120F;
        layer.height = 120F;
        layer.anchorX = 0F;
        layer.anchorY = 0F;
        layer.align = 0;
        layer.text = text;
        layer.fontName = fontName == null ? "" : fontName;
        layer.fontSize = fontSize;
        layer.color = 0xFFFFFFFF;
        layer.out = 2F;
        comp.layers.add(layer);
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

    /**
     * The clip-picker key is derived from the registered {@link Link}, not from the display name.
     * Keep both the current and the historical link spelling localised so films authored with an
     * older addon build keep showing 合成器 as well.
     */
    private static void localizeClipName(mchorse.bbs_mod.l10n.L10n l10n)
    {
        if (l10n == null)
        {
            return;
        }

        /* getKey(key, fallback) does not replace an already-requested key's content (BBS's
         * UIKeys.C_CLIP requests every registered clip link at class-init time), so set the
         * content explicitly rather than relying on the fallback. */
        apply(l10n, "bbs.ui.camera.clips." + BbsAeAddon.CLIP_ID);
        apply(l10n, "bbs.ui.camera.clips.composition");
    }

    private static void apply(mchorse.bbs_mod.l10n.L10n l10n, String key)
    {
        mchorse.bbs_mod.l10n.keys.LangKey langKey = l10n.getKey(key);

        langKey.content = "合成器";
        BbsAeAddon.LOGGER.info("Clip picker localization: {} = {}", key, langKey.content);
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

        BbsAeAddon.LOGGER.info("BBS AE dashboard panel registered");
    }
}
