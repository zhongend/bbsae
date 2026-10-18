package dev.bbsae.client.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompInstance;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.camera.data.Placement;
import mchorse.bbs_mod.ui.film.FrameOverlays;
import mchorse.bbs_mod.ui.film.UIImageRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.clips.ClipContext;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws every composition contributed by the camera timeline's composition clips.
 *
 * <p>BBS calls frame overlays after the world has rendered but before it blits the framebuffer
 * into the texture the video recorder reads. That makes this the one place where a composition
 * ends up in both the preview and the exported video. The composition is rendered into its own
 * off-screen target first, so masks/effects/blends never depend on the world framebuffer state.</p>
 */
public class CompositionOverlay implements FrameOverlays.IFrameOverlayRenderer
{
    @Override
    public void render(MatrixStack stack, Batcher2D batcher, ClipContext context)
    {
        List<CompInstance> instances = context.clipData.get(CompositionClip.KEY, () -> new ArrayList<CompInstance>());
        boolean trace = Boolean.getBoolean("bbsae.traceExport");

        if (trace)
        {
            traceFrame(context, instances);
        }

        if (instances.isEmpty())
        {
            return;
        }

        float width = UIImageRenderer.getUnitWidth();
        float height = Placement.HEIGHT;

        if (width <= 0F || height <= 0F)
        {
            return;
        }

        Matrix4f worldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f previous = new Matrix4f(worldProjection);
        MatrixStack ui = batcher.getContext().getMatrices();
        boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean previousDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int[] previousScissorBox = new int[4];

        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previousScissorBox);

        batcher.flush();
        ui.push();
        ui.peek().getPositionMatrix().identity();

        RenderSystem.setProjectionMatrix(new Matrix4f().ortho(0F, width, height, 0F, -100F, 100F), VertexSorter.BY_Z);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        /* This is a 2D overlay. Leaving depth test/writes on made the composition write into
         * BBS's world framebuffer depth; the next world pass then failed the depth test in those
         * pixels, so the previous composition stayed in the framebuffer. A BBS preview layer
         * samples that framebuffer — the result was a feedback loop of the previous composition,
         * visible as scanlined/blurred ghosts in the exported video. */
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        /* The film editor UI may have left a panel-sized scissor enabled. The export overlay is
         * full-frame, so a leftover scissor would clip it away without any GL error. */
        RenderSystem.disableScissor();

        boolean external = !isFilmEditorOpen();

        try
        {
            for (CompInstance instance : instances)
            {
                try
                {
                    CompProject project = CompStore.load(instance.project);

                    if (project == null)
                    {
                        dev.bbsae.BbsAeAddon.LOGGER.warn("Composition overlay: project '{}' is missing", instance.project);
                        continue;
                    }

                    if (external && (project.settings == null || !project.settings.showDuringExternalPlayback))
                    {
                        continue;
                    }

                    CompDocument doc = project.getComp(instance.comp);

                    if (doc == null)
                    {
                        dev.bbsae.BbsAeAddon.LOGGER.warn("Composition overlay: comp '{}' is missing in project '{}'",
                            instance.comp, instance.project);
                        continue;
                    }

                    float time = instance.time();

                    if (!instance.hold && time > doc.duration)
                    {
                        continue;
                    }

                    if (BbsPreviewBridge.hasBbsPreviewLayer(doc))
                    {
                        BbsPreviewBridge.requestWorldCapture(true);

                        /* The first exported frame can reach the overlay before the next LAST
                         * event has filled SceneColor; take the copy here so the first frame
                         * already contains the BBS preview layer. */
                        if (!BbsPreviewBridge.hasCapturedWorld())
                        {
                            BbsPreviewBridge.captureWorldNow();
                        }
                    }

                    CompRenderer.render(project, doc, time, instance.opacity, width, height, instance.fit, false, true, worldProjection, batcher);
                }
                catch (RuntimeException e)
                {
                    /* One broken project must not hide every other composition clip. */
                    dev.bbsae.BbsAeAddon.LOGGER.error("Composition overlay failed for {}:{}", instance.project, instance.comp, e);
                }
            }
        }
        finally
        {
            RenderSystem.setProjectionMatrix(previous, VertexSorter.BY_Z);
            RenderSystem.depthFunc(previousDepthFunc);

            if (previousDepth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();

            RenderSystem.depthMask(previousDepthMask);

            if (previousBlend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();

            if (previousCull) RenderSystem.enableCull();
            else RenderSystem.disableCull();

            if (previousScissor)
            {
                com.mojang.blaze3d.platform.GlStateManager._scissorBox(
                    previousScissorBox[0], previousScissorBox[1], previousScissorBox[2], previousScissorBox[3]);
                RenderSystem.enableScissor(
                    previousScissorBox[0],
                    previousScissorBox[1],
                    previousScissorBox[2],
                    previousScissorBox[3]
                );
            }
            else
            {
                RenderSystem.disableScissor();
            }

            ui.pop();
        }
    }

    private static long lastTrace;

    private static void traceFrame(ClipContext context, List<CompInstance> instances)
    {
        long now = System.currentTimeMillis();

        if (now - lastTrace < 1000L)
        {
            return;
        }

        lastTrace = now;

        String menu = "none";
        String panel = "none";

        try
        {
            Object current = mchorse.bbs_mod.ui.framework.UIScreen.getCurrentMenu();

            menu = current == null ? "null" : current.getClass().getSimpleName();

            if (current instanceof mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard)
            {
                panel = dashboard.getPanels().panel == null ? "null" : dashboard.getPanels().panel.getClass().getSimpleName();
            }
        }
        catch (RuntimeException ignored)
        {}

        StringBuilder instancesText = new StringBuilder();

        for (CompInstance instance : instances)
        {
            if (instancesText.length() > 0)
            {
                instancesText.append(", ");
            }

            instancesText.append(instance.project).append(':').append(instance.comp)
                .append('@').append(String.format(java.util.Locale.ROOT, "%.3f", instance.time()));
        }

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "[export trace] instances={} [{}] ticks={} rel={} transition={} customSize={} menu={} panel={}",
            instances.size(), instancesText, context.ticks, context.relativeTick, context.transition,
            mchorse.bbs_mod.client.BBSRendering.isCustomSize(), menu, panel
        );
    }

    /**
     * The overlay runs in two very different places: the film editor/recorder (where the compositor
     * is the point) and external replay playback in the world (where it may be unwanted). The
     * current dashboard panel is the reliable discriminator; {@code ClipContext} carries no flag.
     */
    private static boolean isFilmEditorOpen()
    {
        try
        {
            /* The film editor and the F6 recorder both run BBS in custom-size mode. External
             * replay playback does not, so this is the one flag that survives the recorder
             * hiding the dashboard while it writes frames. */
            if (mchorse.bbs_mod.client.BBSRendering.isCustomSize())
            {
                return true;
            }

            Object menu = mchorse.bbs_mod.ui.framework.UIScreen.getCurrentMenu();

            return menu instanceof mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard
                && dashboard.getPanels().panel instanceof mchorse.bbs_mod.ui.film.UIFilmPanel;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }
}
