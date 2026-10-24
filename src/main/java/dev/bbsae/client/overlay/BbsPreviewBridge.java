package dev.bbsae.client.overlay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.render.GlTexture;
import dev.bbsae.client.render.SceneColor;
import dev.bbsae.comp.CompDocument;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;

/**
 * Gives the compositor a live BBS world/film frame without touching BBS's preview mode.
 *
 * <h2>What BBS does, and why the compositor cannot reuse it directly</h2>
 * <p>The film editor calls {@code BBSRendering.setCustomSize(true)} in
 * {@code UIFilmPanel.enterEditing()}: the world is rendered into BBS's private framebuffer,
 * {@code onRenderBeforeScreen} blits it into {@code BBSRendering.getTexture()}, and the film panel
 * paints that texture as its background. Leaving the editor turns the mode off again.</p>
 *
 * <p>The compositor is a different dashboard panel, so the film panel disappears and this whole
 * pipeline is torn down. Re-enabling it from here was the first attempt, and it was wrong:
 * {@code setCustomSize} is a global mode whose final blit overwrites the screen for any dashboard
 * panel that is not the film panel. The result was that every other BBS page worked while the
 * compositor page was a blank world frame.</p>
 *
 * <h2>What this bridge does instead</h2>
 * <ul>
 *   <li>Re-adds the film editor's {@link RunnerCameraController} to the camera controller, so the
 *       world renders from the film's camera at the compositor playhead (20 ticks/second).</li>
 *   <li>Renders the film's replay actors from {@code WorldRenderEvents.AFTER_ENTITIES}, the same
 *       call the film editor makes while it is on screen.</li>
 *   <li>Copies the finished world color once per frame through {@link SceneColor}
 *       ({@code WorldRenderEvents.LAST}). The compositor's "BBS preview" layer and the optional
 *       BBS preview backdrop sample that copy.</li>
 * </ul>
 *
 * <p>No {@code BBSRendering} state is touched at all, so BBS's own UI and video export keep
 * working exactly as before.</p>
 */
public final class BbsPreviewBridge
{
    /** BBS camera clips are authored in ticks; the composition timeline is in seconds. */
    private static final float TICKS_PER_SECOND = 20F;

    private static UIFilmPanel filmPanel;
    private static CompDocument document;
    private static boolean active;
    private static boolean runnerAdded;
    private static boolean renderFailureLogged;
    /** True while the open composition actually contains a "BBS 预览" layer. */
    private static boolean worldCaptureRequested;
    /** True while the editor preview uses the live world frame as its backdrop. */
    private static boolean backdropRequested;
    /** True while a depth effect needs the world depth buffer. */
    private static boolean depthRequested;
    /** Copy of the world depth attachment, filled together with {@link SceneColor}. */
    private static dev.bbsae.client.render.SceneDepth worldDepth;

    /**
     * The film panel is removed from the dashboard element tree when it disappears, so its
     * {@code UIFilmController.getContext()} walks to a null root and {@code isPlaying()} NPEs.
     * A hidden carrier under the dashboard's main element keeps the controller parented to a live
     * root while the compositor is on screen; it is put back on the film panel in {@link #detach()}.
     */
    private static UIElement carrier;
    private static UIFilmController carriedController;

    private BbsPreviewBridge()
    {}

    /** Called from the compositor's {@code onAppear}; the film panel has already left editing. */
    public static void attach(UIFilmPanel panel, CompDocument comp)
    {
        /* `UIDashboard.open()` can set the already-current panel again, which fires appear twice. */
        if (active && panel == filmPanel && comp == document)
        {
            return;
        }

        detach();

        filmPanel = panel;
        document = comp;
        active = true;
        renderFailureLogged = false;
        worldCaptureRequested = hasBbsPreviewLayer(comp);

        BbsAeAddon.LOGGER.info("BBS preview attached: film={}, comp={}x{}",
            panel == null || panel.getData() == null ? "-" : panel.getData().getId(),
            comp == null ? 0 : comp.width,
            comp == null ? 0 : comp.height);

        if (panel != null && panel.getData() != null)
        {
            try
            {
                Film film = panel.getData();

                /* A film that is actually playing already owns the camera and draws its own
                 * actors; adding the editor runner on top would fight it and double the actors. */
                if (BBSModClient.getFilms().getController(film.getId()) != null)
                {
                    BbsAeAddon.LOGGER.info("BBS preview uses the playing film {} instead of the editor runner", film.getId());
                    return;
                }

                RunnerCameraController runner = panel.getRunner();

                runner.setWork(film.camera);
                runner.setCursor(0F);

                if (runner.isRunning())
                {
                    runner.setPlaying(false);
                }

                if (!BBSModClient.getCameraController().has(runner))
                {
                    BBSModClient.getCameraController().add(runner);
                    runnerAdded = true;
                }

                ensureControllerContext(panel);
            }
            catch (RuntimeException e)
            {
                BbsAeAddon.LOGGER.warn("Could not attach the BBS film camera to the compositor preview", e);
            }
        }
    }

    /** The composition changed while the panel stayed on screen. */
    public static void updateDocument(CompDocument comp)
    {
        if (active)
        {
            document = comp;
        }

        if (comp != null)
        {
            worldCaptureRequested = hasBbsPreviewLayer(comp);
        }
    }

    /**
     * A BBS preview layer is a real layer: it must also exist in the film-editor/export path,
     * not only while the compositor dashboard panel is open. The overlay asks for the world
     * capture as soon as it sees such a layer; the capture itself happens at
     * WorldRenderEvents.LAST, before BBS draws its frame overlays.
     */
    public static void setBackdropRequested(boolean requested)
    {
        backdropRequested = requested;
    }

    /**
     * Re-derives the capture requests from the project settings.
     *
     * <p>This is the fix for "the preview has no depth and the BBS backdrop is missing after
     * reopening a project": both flags used to be set <em>only</em> by a toggle callback, so a
     * persisted {@code previewBackground = 1} or a {@code depth_*} effect never asked for a
     * capture until the user flipped the switch twice. The panel calls this once per frame; it is
     * three boolean reads.</p>
     */
    public static void syncRequests(dev.bbsae.comp.CompProject project, CompDocument comp)
    {
        document = comp;

        boolean world = hasBbsPreviewLayer(comp);
        boolean depth = needsDepth(comp);

        worldCaptureRequested = world || depth;
        depthRequested = depth;

        if (project != null && project.settings != null)
        {
            backdropRequested = project.settings.previewBackground == 1;
        }
    }

    private static boolean needsDepth(CompDocument comp)
    {
        if (comp == null || comp.layers == null)
        {
            return false;
        }

        for (dev.bbsae.comp.CompLayer layer : comp.layers)
        {
            if (layer == null || layer.effects == null)
            {
                continue;
            }

            for (dev.bbsae.comp.CompEffect effect : layer.effects)
            {
                if (effect != null && effect.enabled && effect.type != null && effect.type.startsWith("depth_"))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** The world depth copy for the compositor preview, or null when it is not being captured. */
    public static dev.bbsae.client.render.SceneDepth depth()
    {
        return worldDepth;
    }

    public static void requestWorldCapture(boolean requested)
    {
        if (requested)
        {
            worldCaptureRequested = true;
        }
    }

    public static boolean hasBbsPreviewLayer(CompDocument comp)
    {
        if (comp == null || comp.layers == null)
        {
            return false;
        }

        for (dev.bbsae.comp.CompLayer layer : comp.layers)
        {
            if (layer != null && "bbs".equals(layer.type) && layer.visible)
            {
                return true;
            }
        }

        return false;
    }

    public static boolean hasCapturedWorld()
    {
        return SceneColor.texture() != null;
    }

    /** Force a world copy right now; used on the first overlay frame, before LAST runs again. */
    public static void captureWorldNow()
    {
        SceneColor.capture();

        if (depthRequested)
        {
            captureDepthNow();
        }
    }

    public static void detach()
    {
        releaseControllerContext();

        if (runnerAdded && filmPanel != null)
        {
            try
            {
                BBSModClient.getCameraController().remove(filmPanel.getRunner());
            }
            catch (RuntimeException ignored)
            {}
        }

        runnerAdded = false;
        active = false;
        backdropRequested = false;
        depthRequested = false;
        filmPanel = null;
        document = null;
    }

    public static boolean isActive()
    {
        return active;
    }

    private static void ensureControllerContext(UIFilmPanel panel)
    {
        try
        {
            UIFilmController controller = panel.getController();

            if (controller == null || controller.getContext() != null)
            {
                return;
            }

            UIDashboard dashboard = BBSModClient.getDashboard();

            if (dashboard == null || dashboard.main == null)
            {
                return;
            }

            carrier = new UIElement();
            carrier.setVisible(false);
            dashboard.main.add(carrier);
            carrier.add(controller);
            carriedController = controller;
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Could not keep the film controller attached for the preview", e);
        }
    }

    private static void releaseControllerContext()
    {
        if (carriedController != null && filmPanel != null)
        {
            try
            {
                filmPanel.add(carriedController);
            }
            catch (RuntimeException ignored)
            {}
        }

        if (carrier != null)
        {
            carrier.removeFromParent();
        }

        carrier = null;
        carriedController = null;
    }

    /** Seek the film camera to the compositor's playhead. Cheap; safe to call every frame. */
    public static void sync(float seconds)
    {
        if (!active || filmPanel == null)
        {
            return;
        }

        try
        {
            filmPanel.getRunner().setCursor(Math.max(0F, seconds) * TICKS_PER_SECOND);
        }
        catch (RuntimeException ignored)
        {}
    }

    /**
     * Draws the film's replay actors into the current world render, exactly like the film editor's
     * {@code UIFilmController.renderFrame} does while that panel is on screen.
     */
    public static void renderFilmActors(WorldRenderContext context)
    {
        if (!active || filmPanel == null)
        {
            return;
        }

        try
        {
            Film film = filmPanel.getData();

            /* A playing film renders its own actors; the editor controller would duplicate them. */
            if (film != null && BBSModClient.getFilms().getController(film.getId()) != null)
            {
                return;
            }

            UIFilmController controller = filmPanel.getController();

            if (controller == null || controller.editorController == null)
            {
                return;
            }

            float tickDelta = MinecraftClient.getInstance().getTickDelta();

            controller.editorController.startRenderFrame(tickDelta);
            controller.editorController.render(context);
        }
        catch (RuntimeException e)
        {
            if (!renderFailureLogged)
            {
                renderFailureLogged = true;
                BbsAeAddon.LOGGER.warn("BBS preview layer could not render the film actors", e);
            }
        }
    }

    /**
     * Copies the finished world frame for the compositor. Called from
     * {@code WorldRenderEvents.LAST}, before BBS draws its frame overlays, so the copy is the
     * clean world/film frame the BBS preview layer is supposed to show.
     */
    public static void captureFrame()
    {
        /* active: compositor panel preview.
         * worldCaptureRequested + custom size: film editor preview and the F6 recorder, where
         * the BBS preview layer used to disappear from the exported video. */
        /* Copying the world colour is a full framebuffer copy. Doing it every frame even when no
         * layer samples it was pure GPU cost, so it now requires an actual consumer. */
        boolean editorNeeds = active && (worldCaptureRequested || backdropRequested);
        boolean exportNeeds = worldCaptureRequested
            && (mchorse.bbs_mod.client.BBSRendering.isCustomSize()
                || BBSModClient.getVideoRecorder().isRecording());

        if (editorNeeds || exportNeeds)
        {
            SceneColor.capture();

            /* Depth is copied in the same pass, from the same framebuffer, so colour and depth can
             * never come from different frames. It is only requested when an effect samples it:
             * a 1080p depth blit per frame for nothing is a measurable cost. */
            if (depthRequested)
            {
                captureDepthNow();
            }

            if (Boolean.getBoolean("bbsae.traceExport"))
            {
                captureTrace++;

                if (captureTrace % 20 == 0)
                {
                    BbsAeAddon.LOGGER.info("[export trace] world capture #{} size={}x{} active={} requested={}",
                        captureTrace,
                        SceneColor.texture() == null ? 0 : SceneColor.texture().width,
                        SceneColor.texture() == null ? 0 : SceneColor.texture().height,
                        active, worldCaptureRequested);
                }
            }
        }
    }

    private static int captureTrace;

    private static void captureDepthNow()
    {
        try
        {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

            if (mc == null || mc.getFramebuffer() == null)
            {
                return;
            }

            net.minecraft.client.gl.Framebuffer framebuffer = mc.getFramebuffer();

            if (worldDepth == null)
            {
                worldDepth = new dev.bbsae.client.render.SceneDepth();
            }

            worldDepth.captureFrom(framebuffer.fbo, framebuffer.textureWidth, framebuffer.textureHeight);
        }
        catch (RuntimeException e)
        {
            /* Depth is optional by design: every depth effect degrades to a no-op without it. */
            worldDepth = null;
        }
    }

    /** The captured world frame, or null until the first world render after attach. */
    public static GlTexture texture()
    {
        GlTexture texture = SceneColor.texture();

        return texture != null && texture.width > 0 && texture.height > 0 ? texture : null;
    }

    /** Diagnostic: called by the BBS preview layer the first time it composites a real frame. */
    public static void noteFrameDrawn(int width, int height)
    {
        if (!frameLogged)
        {
            frameLogged = true;
            BbsAeAddon.LOGGER.info("BBS preview layer composited a {}x{} world frame", width, height);
        }
    }

    private static boolean frameLogged;

    /**
     * Paints the captured frame into a rectangle. Framebuffer copies are bottom-up, so V is swapped
     * exactly like BBS paints its own preview texture.
     */
    public static boolean drawFrame(Batcher2D batcher, int color, float x, float y, float width, float height)
    {
        GlTexture texture = texture();

        if (texture == null)
        {
            return false;
        }

        batcher.texturedBox(texture.id, color, x, y, width, height,
            0F, texture.height, texture.width, 0F, texture.width, texture.height);

        noteFrameDrawn(texture.width, texture.height);

        return true;
    }
}
