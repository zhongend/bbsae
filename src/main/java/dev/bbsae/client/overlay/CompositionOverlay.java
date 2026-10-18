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
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        batcher.flush();
        ui.push();
        ui.peek().getPositionMatrix().identity();

        RenderSystem.setProjectionMatrix(new Matrix4f().ortho(0F, width, height, 0F, -100F, 100F), VertexSorter.BY_Z);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        try
        {
            for (CompInstance instance : instances)
            {
                CompProject project = CompStore.load(instance.project);
                CompDocument doc = project.getComp(instance.comp);

                if (doc == null)
                {
                    continue;
                }

                float time = instance.time();

                if (!instance.hold && time > doc.duration)
                {
                    continue;
                }

                CompRenderer.render(project, doc, time, instance.opacity, width, height, instance.fit, false, true, worldProjection, batcher);
            }
        }
        catch (RuntimeException e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Composition overlay failed", e);
        }
        finally
        {
            RenderSystem.setProjectionMatrix(previous, VertexSorter.BY_Z);
            RenderSystem.depthFunc(previousDepthFunc);

            if (previousDepth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();

            if (previousBlend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();

            if (previousCull) RenderSystem.enableCull();
            else RenderSystem.disableCull();

            ui.pop();
        }
    }
}
