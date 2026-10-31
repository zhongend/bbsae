package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * A depth-only pass containing nothing but the selected replay entities.
 *
 * <h2>Why a second entity pass</h2>
 * <p>The compositor receives one finished world frame: colour and depth. Nothing in that image
 * says which pixel belongs to an actor — a white helmet against a white wall is the same colour,
 * and under a shader pack it is not even the same tone. The only reliable identity signal is
 * <b>geometry</b>: render the actors again, alone, into a depth target. Then a pixel of the world
 * frame is an actor pixel when the two depths agree.</p>
 *
 * <p>That comparison is also exactly what makes occlusion work. A block in front of an actor wrote
 * a nearer depth into the world buffer, so the actor's depth and the world's differ, and the pixel
 * is rejected. The same is true for an actor that was <em>not</em> selected standing in front of a
 * selected one: the world depth is the unselected actor, so the selected one stays hidden. There
 * is no per-pixel bookkeeping to get wrong — the hardware depth buffer already did it.</p>
 *
 * <h2>When the pass runs</h2>
 * <p>At {@code WorldRenderEvents.AFTER_ENTITIES}, right after BBS drew the film's actors, using
 * the same camera, the same pose stack and the same transition. Rendering the same geometry with
 * the same matrices means the depth values are identical to the ones in the world buffer; the
 * shader only needs a small, distance-scaled tolerance for the 24-bit quantisation of the captured
 * depth.</p>
 *
 * <p>The one ordering problem is that a composition only becomes known at the frame overlay, which
 * runs <em>after</em> the world. The request is therefore carried over from the previous frame
 * (invisible: it is a 16 ms delay on a checkbox toggle) and, when the signature really changed,
 * the pass is re-run lazily at the overlay with the stored camera. That is what makes frame 0 of
 * an export correct instead of one frame late.</p>
 */
public final class ReplayEntityMask
{
    /* Request: the union of every visible replay-entity layer of the composition being drawn. */
    private static String requestedFilm = "";
    private static boolean requestAll = true;
    private static final Set<String> requestedIds = new LinkedHashSet<>();
    private static String requestedSignature = "";
    private static boolean requested;

    /* Target. */
    private static int framebuffer;
    private static int depthTexture;
    private static int width;
    private static int height;
    private static boolean available;

    /* Frame bookkeeping. */
    private static int serial;
    private static int renderedSerial = -1;
    private static String renderedSignature = "";
    private static int renderedEntities;
    private static boolean loggedFailure;

    /* Camera of the current world frame, captured at AFTER_ENTITIES for the lazy re-run. */
    private static Camera lastCamera;
    private static final MatrixStack lastStack = new MatrixStack();
    private static final Matrix4f lastModelView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static boolean hasCamera;

    private ReplayEntityMask()
    {}

    /* ------------------------------------------------------------------ frame lifecycle */

    /**
     * Starts a new world frame. Called from {@code WorldRenderEvents.START}; the shadow pass of a
     * shader pack is not a frame, and counting it would make the mask look one frame stale.
     */
    public static void beginFrame()
    {
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        serial += 1;
    }

    /** True while any composition on screen wants a replay entity matte. */
    public static boolean isRequested()
    {
        return requested;
    }

    /**
     * Records what the current composition needs. Cheap: it walks the layers of one document and
     * compares a signature string; nothing is allocated when nothing changed.
     */
    public static void request(CompProject project, CompDocument doc)
    {
        if (doc == null)
        {
            return;
        }

        String film = filmId(project);
        Set<String> ids = new TreeSet<>();
        boolean[] all = {false};
        boolean[] any = {false};

        collect(doc, project, ids, all, any, 0);

        if (!any[0])
        {
            if (requested)
            {
                requested = false;
                requestedSignature = "";
                requestedIds.clear();
                requestedFilm = "";
            }

            return;
        }

        String signature = film + "|" + all[0] + "|" + String.join(",", ids);

        if (requested && signature.equals(requestedSignature))
        {
            return;
        }

        requested = true;
        requestedFilm = film;
        requestAll = all[0];
        requestedIds.clear();
        requestedIds.addAll(ids);
        requestedSignature = signature;
    }

    private static void collect(CompDocument doc, CompProject project, Set<String> ids, boolean[] all, boolean[] any, int depth)
    {
        if (doc == null || doc.layers == null || depth > 8)
        {
            return;
        }

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || layer.type == null)
            {
                continue;
            }

            if (layer.isEntityLayer())
            {
                any[0] = true;

                if (layer.entityScope == CompLayer.ENTITY_SCOPE_ALL)
                {
                    all[0] = true;
                }
                else
                {
                    ids.addAll(layer.entityIdSet());
                }

                continue;
            }

            if ("precomp".equals(layer.type) && project != null && layer.precompId != null && !layer.precompId.isEmpty())
            {
                collect(project.getComp(layer.precompId), project, ids, all, any, depth + 1);
            }
        }
    }

    private static String filmId(CompProject project)
    {
        if (project == null || project.id == null)
        {
            return "";
        }

        return project.id.startsWith("film_") ? project.id.substring(5) : "";
    }

    /* ------------------------------------------------------------------ render */

    /**
     * Renders the mask for the current frame. Called at {@code AFTER_ENTITIES}, after BBS drew the
     * film's actors, so the depth test order and the pose stack are the ones the world frame used.
     */
    public static void render(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context)
    {
        if (context == null || context.matrixStack() == null || context.camera() == null)
        {
            return;
        }

        /* Remember the frame's camera even when nothing is requested: the first frame of a
         * composition is only known at the overlay, which is too late to be inside the world. */
        lastCamera = context.camera();
        lastStack.peek().getPositionMatrix().set(context.matrixStack().peek().getPositionMatrix());
        lastModelView.set(RenderSystem.getModelViewMatrix());
        lastProjection.set(RenderSystem.getProjectionMatrix());
        hasCamera = true;
        updateClipPlanes(lastProjection);

        if (!requested)
        {
            return;
        }

        BaseFilmController controller = resolveController(requestedFilm);

        if (controller == null)
        {
            renderedEntities = 0;
            renderedSerial = serial;
            renderedSignature = requestedSignature;

            return;
        }

        renderedEntities = draw(controller, lastCamera, context.matrixStack(), context.consumers(), transitionOf(controller, context.tickDelta()));
        renderedSerial = serial;
        renderedSignature = requestedSignature;
    }

    /**
     * Makes sure the mask belongs to the current frame and the current selection.
     *
     * <p>Called from the frame overlay before a composition is drawn. Normally this is a no-op
     * (the AFTER_ENTITIES pass already produced it). It only renders when the frame has not been
     * masked yet — the first frame of a newly opened composition or of an export — or when the
     * selection changed after the world was rendered.</p>
     */
    public static void ensureCurrent(CompProject project, CompDocument doc)
    {
        request(project, doc);

        if (!requested || !hasCamera)
        {
            return;
        }

        if (renderedSerial == serial && requestedSignature.equals(renderedSignature))
        {
            return;
        }

        BaseFilmController controller = resolveController(requestedFilm);

        if (controller == null)
        {
            renderedEntities = 0;
            renderedSerial = serial;
            renderedSignature = requestedSignature;

            return;
        }

        MatrixStack stack = new MatrixStack();

        stack.peek().getPositionMatrix().set(lastStack.peek().getPositionMatrix());

        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        MatrixStack modelView = RenderSystem.getModelViewStack();

        modelView.push();
        modelView.peek().getPositionMatrix().set(lastModelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(lastProjection, VertexSorter.BY_Z);

        try
        {
            renderedEntities = draw(controller, lastCamera, stack, null, transitionOf(controller, MinecraftClient.getInstance().getTickDelta()));
        }
        finally
        {
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            modelView.pop();
            RenderSystem.applyModelViewMatrix();
        }

        renderedSerial = serial;
        renderedSignature = requestedSignature;
    }

    private static float transitionOf(BaseFilmController controller, float tickDelta)
    {
        /* BBS freezes the interpolation while the film is paused. Using the live tick delta then
         * would draw the actor a fraction of a tick away from where the world frame has it, which
         * shows up as a one-pixel halo along every silhouette. */
        return controller != null && controller.paused ? 0F : MathHelper.clamp(tickDelta, 0F, 1F);
    }

    private static BaseFilmController resolveController(String filmId)
    {
        try
        {
            UIFilmPanel panel = BBSModClient.getDashboard() == null ? null : BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            if (panel != null && panel.getData() != null)
            {
                Film film = panel.getData();

                if (filmId.isEmpty() || filmId.equals(film.getId()))
                {
                    BaseFilmController playing = BBSModClient.getFilms().getController(film.getId());

                    if (playing != null)
                    {
                        return playing;
                    }

                    UIFilmController controller = panel.getController();

                    if (controller != null && controller.editorController != null)
                    {
                        return controller.editorController;
                    }
                }
            }

            for (BaseFilmController candidate : BBSModClient.getFilms().getControllers())
            {
                if (candidate == null || candidate.film == null)
                {
                    continue;
                }

                if (filmId.isEmpty() || filmId.equals(candidate.film.getId()))
                {
                    return candidate;
                }
            }
        }
        catch (RuntimeException e)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                BbsAeAddon.LOGGER.warn("Replay entity mask could not find the film controller", e);
            }
        }

        return null;
    }

    /**
     * Draws the selected replays into the depth target and returns how many were rendered.
     */
    private static int draw(BaseFilmController controller, Camera camera, MatrixStack stack, VertexConsumerProvider consumers, float transition)
    {
        if (controller.film == null || controller.film.replays == null)
        {
            return 0;
        }

        if (!ensureTarget())
        {
            return 0;
        }

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        boolean previousDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int[] previousColorMask = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, previousColorMask);

        boolean queued = FormRenderLast.suspend();
        int rendered = 0;

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GlStateManager._viewport(0, 0, width, height);
            GlStateManager._disableScissorTest();
            GlStateManager._disableBlend();
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(true);
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            /* Depth only: the entity shaders may write any colour they like, and letting them
             * would cost fill rate for pixels nobody reads. */
            GlStateManager._colorMask(false, false, false, false);

            GL11.glClearDepth(1.0D);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            for (Replay replay : controller.film.replays.getList())
            {
                if (replay == null || !keeps(replay))
                {
                    continue;
                }

                IEntity entity = controller.entities.get(replay.getId());

                if (entity == null || entity.getForm() == null)
                {
                    continue;
                }

                try
                {
                    FilmControllerContext filmContext = FilmControllerContext.instance
                        .setup(controller.entities, entity, replay, camera, stack, consumers, transition)
                        .shadow(false, 0F)
                        .nameTag("")
                        .relative(false);

                    FilmEntityRenderer.renderEntity(filmContext);
                    rendered += 1;
                }
                catch (RuntimeException e)
                {
                    if (!loggedFailure)
                    {
                        loggedFailure = true;
                        BbsAeAddon.LOGGER.warn("Replay entity mask could not render replay {}", replay.getId(), e);
                    }
                }
            }
        }
        finally
        {
            FormRenderLast.restore(queued);

            GlStateManager._colorMask(previousColorMask[0] != 0, previousColorMask[1] != 0, previousColorMask[2] != 0, previousColorMask[3] != 0);
            GlStateManager._depthFunc(previousDepthFunc);
            GlStateManager._depthMask(previousDepthMask);

            if (previousDepth) GlStateManager._enableDepthTest();
            else GlStateManager._disableDepthTest();

            if (previousBlend) GlStateManager._enableBlend();
            else GlStateManager._disableBlend();

            if (previousCull) GlStateManager._enableCull();
            else GlStateManager._disableCull();

            if (previousScissor) GlStateManager._enableScissorTest();
            else GlStateManager._disableScissorTest();

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GlStateManager._viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);

            /* The form renderers use their own VAOs and the shared buffer builder; Minecraft's
             * cached vertex buffer must be invalidated or the next world/UI draw reuses ours. */
            net.minecraft.client.render.BufferRenderer.resetCurrentVertexBuffer();
        }

        return rendered;
    }

    private static float nearPlane = 0.05F;
    private static float farPlane = 1024F;

    /**
     * Extracts the world camera's near/far from its projection matrix.
     *
     * <p>The depth comparison happens in view-space blocks, so the shader needs the same clip
     * planes the world was rendered with. Reading them from the matrix is exact and survives a
     * changed render distance or a custom film camera; the defaults only apply for an
     * orthographic projection, where depth is linear anyway.</p>
     */
    private static void updateClipPlanes(Matrix4f projection)
    {
        float m22 = projection.m22();
        float m23 = projection.m23();

        if (Math.abs(m22 - 1F) < 1.0E-5F)
        {
            return;
        }

        float near = m23 / (m22 - 1F);
        float far = m23 / (m22 + 1F);

        if (near > 0F && far > near && Float.isFinite(near) && Float.isFinite(far))
        {
            nearPlane = near;
            farPlane = far;
        }
    }

    public static float nearPlane()
    {
        return nearPlane;
    }

    public static float farPlane()
    {
        return farPlane;
    }

    private static boolean keeps(Replay replay)
    {
        if (!Boolean.TRUE.equals(replay.enabled.get()))
        {
            return false;
        }

        return requestAll || requestedIds.contains(replay.getId());
    }

    /* ------------------------------------------------------------------ target */

    private static boolean ensureTarget()
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getFramebuffer() == null)
        {
            return false;
        }

        Framebuffer main = client.getFramebuffer();
        int w = main.textureWidth;
        int h = main.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return false;
        }

        if (depthTexture != 0 && width == w && height == h && available)
        {
            return true;
        }

        release();

        width = w;
        height = h;
        depthTexture = GL11.glGenTextures();

        GlStateManager._bindTexture(depthTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, width, height, 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);

        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        ReplayEntityMask.framebuffer = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, ReplayEntityMask.framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        available = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        GlStateManager._bindTexture(0);

        if (!available)
        {
            BbsAeAddon.LOGGER.warn("Replay entity mask target {}x{} is incomplete; the layer will be empty", width, height);
        }

        return available;
    }

    public static boolean isAvailable()
    {
        return available && depthTexture != 0;
    }

    public static int texture()
    {
        return isAvailable() ? depthTexture : 0;
    }

    public static int width()
    {
        return width;
    }

    public static int height()
    {
        return height;
    }

    /** How many entities the current mask contains; 0 means "nothing to key". */
    public static int entityCount()
    {
        return renderedEntities;
    }

    public static void release()
    {
        if (framebuffer != 0)
        {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }

        if (depthTexture != 0)
        {
            GL11.glDeleteTextures(depthTexture);
            depthTexture = 0;
        }

        available = false;
        width = 0;
        height = 0;
        renderedSerial = -1;
    }

    /** Drops the request; used when the compositor closes. */
    public static void clearRequest()
    {
        requested = false;
        requestedSignature = "";
        requestedIds.clear();
        requestedFilm = "";
        renderedSignature = "";
        renderedSerial = -1;
        renderedEntities = 0;
        hasCamera = false;
    }

    /** Replay ids the current request keeps, for diagnostics and the inspector. */
    public static Collection<String> requestedIds()
    {
        return requestedIds;
    }

    public static boolean requestsAll()
    {
        return requestAll;
    }
}
