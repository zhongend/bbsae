package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.client.replay.ReplayModelPass;
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
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * An actor-only colour pass for the selected replay entities: RGB = the actor's own pixels,
 * alpha = coverage. The compositor's replay layer samples this texture like any other RGBA image.
 *
 * <h2>Why coverage instead of a depth texture</h2>
 * <p>The matte used to be a depth-only target compared with the world depth inside the layer
 * shader. That comparison is the right <em>idea</em> — depth is the only lighting-independent
 * identity signal — but sampling a {@code DEPTH_COMPONENT24} attachment from the compositor's own
 * program turned out to be unreliable on the target stack (Iris 1.7.6 + Sodium 0.13 + NVIDIA): the
 * depth target verifiably contained the actor (a readback counted 232 279 covered pixels), the same
 * full-screen program rendered solid red, yet every {@code texture(EntityDepth, uv)} in the layer
 * shader read 1.0. Everything outside the matte was therefore rejected and the layer stayed
 * transparent.</p>
 *
 * <p>The replacement removes the questionable operation instead of trying to guess at sampler
 * state. The actor is rendered <b>once more</b>, into an RGBA target, with the world depth already
 * copied into that target's depth attachment. The hardware then performs exactly the comparison the
 * shader used to do: an actor fragment passes {@code LEQUAL} only where the world depth at that
 * pixel is not nearer, so blocks in front still hide the actor. What remains in the colour target
 * is a transparent-background cut-out of the selected actors — a normal colour texture whose alpha
 * the layer shader can sample with no depth-texture special case at all.</p>
 *
 * <h2>Occlusion is baked, not sampled</h2>
 * <p>{@code entityOcclusion} now selects which of two targets a layer reads: one rendered after the
 * world depth was blitted in (occluded), and one rendered without it (the "see through walls"
 * option). A composition that only uses one mode pays for one pass; a mixed composition pays for
 * two. The per-layer setting also participates in the request signature, so toggling it re-renders
 * the matte instead of silently doing nothing until the next unrelated edit.</p>
 *
 * <h2>When the pass runs</h2>
 * <p>At {@code WorldRenderEvents.AFTER_ENTITIES}, right after BBS drew the film's actors, using the
 * same camera, pose stack and transition, so the copied world depth and the actor geometry describe
 * the same frame. The request is carried over from the previous frame (a 16 ms delay on a checkbox
 * toggle) and, when the signature really changed, the pass is re-run lazily at the overlay with the
 * stored camera and the stored world-depth framebuffer. That is what makes frame 0 of an export
 * correct instead of one frame late.</p>
 */
public final class ReplayEntityMask
{
    /* Request: the union of every visible replay-entity layer of the composition being drawn. */
    private static String requestedFilm = "";
    private static boolean requestAll = true;
    private static final Set<String> requestedIds = new LinkedHashSet<>();
    private static String requestedSignature = "";
    private static boolean requested;
    private static boolean requestOccluded;
    private static boolean requestUnoccluded;

    /* Two coverage targets: with and without the world depth test. */
    private static final Target occludedTarget = new Target(true);
    private static final Target plainTarget = new Target(false);

    /* Frame bookkeeping. */
    private static int serial;
    private static int renderedSerial = -1;
    private static String renderedSignature = "";
    private static int renderedEntities;
    private static boolean loggedFailure;

    /* Where the world depth for the current mask came from. The lazy overlay re-run needs the same
     * source, and it is still bound to the world framebuffer at that point in the frame. */
    private static int lastDepthSource;
    private static boolean lastDepthSourceValid;

    /* Camera and world matrices of the current frame.
     *
     * <p>Captured at BEFORE_ENTITIES, not at AFTER_ENTITIES. The film actor pass runs between the
     * two and may leave the RenderSystem model-view or the pose stack on a form-local transform;
     * reusing that state is what made the coverage pass render the actor at a completely different
     * scale than the world (a 232 279 px silhouette for an actor a few thousand pixels tall on
     * screen). The mask must use the exact matrices the world's own geometry used.</p> */
    private static Camera lastCamera;
    private static final MatrixStack lastStack = new MatrixStack();
    private static final Matrix4f lastModelView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static boolean hasCamera;
    private static int capturedSerial = -1;

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
        lastActorTick = -1;
    }

    /** Marks the comp-camera actor pass so its {@code ACTOR_BEFORE} event is not captured as world. */
    static void setCompositeActorPass(boolean active)
    {
        inCompositeActorPass = active;
    }

    /** True while any composition on screen wants a replay entity matte. */
    public static boolean isRequested()
    {
        return requested;
    }

    /**
     * True only when the mask texture belongs to the world frame currently being composited.
     *
     * <p>Before this check the only freshness rule was "a target exists", so after a failed mask
     * pass (no controller, request cleared, film change) the previous frame's silhouette kept
     * keying the new world frame. Under motion that is exactly the background leak the user sees:
     * the old actor outline is where the actor was, not where it is now. Refusing to compose is
     * strictly safer than composing a stale matte; the layer draws its waiting placeholder.</p>
     */
    public static boolean isCurrentFrame()
    {
        return renderedSerial == serial && texture() != 0;
    }

    /** Serial of the world frame currently being rendered (diagnostics/tests). */
    public static int frameSerial()
    {
        return serial;
    }

    /** Serial the current mask texture was produced for (diagnostics/tests). */
    public static int renderedSerial()
    {
        return renderedSerial;
    }

    /**
     * Captures the exact camera, pose stack and GL matrices the world actor is about to be drawn
     * with.
     *
     * <p>Called from BBS' own {@code FormPoseEvents.ACTOR_BEFORE}, i.e. inside
     * {@code FilmEntityRenderer.renderEntity} before any form code runs. Neither of the two
     * earlier attempts worked: {@code BEFORE_ENTITIES} can fire before the world projection exists
     * (it captured a {@code +Inf} projection and an identity model-view), and the live state at
     * {@code AFTER_ENTITIES} is whatever the last form renderer left behind. The actor pass's own
     * state is the only one guaranteed to be the one that just put the actor on screen.</p>
     *
     * <p>Our own mask pass re-enters {@code FilmEntityRenderer}, which fires this event again;
     * {@link #inMaskRender} keeps that re-entry from overwriting the world capture.</p>
     */
    public static void captureActorState(FilmControllerContext context)
    {
        if (inMaskRender || inCompositeActorPass
            || context == null || context.stack == null || context.camera == null)
        {
            return;
        }

        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        lastCamera = context.camera;
        lastStack.peek().getPositionMatrix().set(context.stack.peek().getPositionMatrix());
        lastModelView.set(RenderSystem.getModelViewMatrix());
        lastProjection.set(RenderSystem.getProjectionMatrix());
        lastActorTransition = context.transition;
        capturedSerial = serial;
        hasCamera = true;
        updateClipPlanes(lastProjection);

        if (!loggedCapture)
        {
            loggedCapture = true;
            BbsAeAddon.LOGGER.info("Replay entity mask captured the world actor state: camera={} modelView={} projection={}",
                lastCamera.getPos(), lastModelView, lastProjection);
        }
    }

    private static boolean loggedCapture;
    private static boolean loggedLiveFallback;
    private static boolean inMaskRender;
    /**
     * True while {@link ReplayActorPass} renders the same actors into the composition camera.
     * That pass also fires {@code FormPoseEvents.ACTOR_BEFORE}; without this flag it would replace
     * the world-frame matte pose with the composition frame's synthetic camera.
     */
    private static boolean inCompositeActorPass;

    /**
     * The fractional tick the world actor was actually drawn with.
     *
     * <p>This is not Minecraft's {@code tickDelta}: the compositor drives the film with a
     * seconds*20 cursor, and BBS' editor controller derives its own fraction from that cursor.
     * The mask used to pass MC's tick delta, which has a different phase, so the matte actor was
     * up to one tick away from the actor in the world frame — the "matte lags the preview" ghost.
     * Capturing the actor's own transition makes both passes use the same sub-tick.</p>
     */
    private static float lastActorTransition;
    /** Controller tick the world frame's actors were rendered at (-1 = not captured yet). */
    private static int lastActorTick = -1;
    /** Pixel size of the framebuffer the world frame was rendered into (0 = unknown). */
    private static int lastWorldWidth;
    private static int lastWorldHeight;

    /**
     * Records what the current composition needs. The common no-entity path performs an
     * allocation-free preflight; request objects are only built when an entity layer exists.
     */
    public static void request(CompProject project, CompDocument doc)
    {
        if (doc == null)
        {
            return;
        }

        if (!hasEntityLayer(doc, project, 0))
        {
            clearLayerRequest();

            return;
        }

        String film = filmId(project);
        Set<String> ids = new TreeSet<>();
        boolean[] all = {false};
        boolean[] any = {false};
        boolean[] occluded = {false};
        boolean[] unoccluded = {false};

        collect(doc, project, ids, all, any, occluded, unoccluded, 0);

        if (!any[0])
        {
            clearLayerRequest();

            return;
        }

        String signature = film + "|" + all[0] + "|" + occluded[0] + "|" + unoccluded[0] + "|" + String.join(",", ids);

        if (requested && signature.equals(requestedSignature))
        {
            return;
        }

        requested = true;
        requestedFilm = film;
        requestAll = all[0];
        requestOccluded = occluded[0];
        requestUnoccluded = unoccluded[0];
        requestedIds.clear();
        requestedIds.addAll(ids);
        requestedSignature = signature;
    }

    static boolean hasEntityLayer(CompDocument doc, CompProject project, int depth)
    {
        if (doc == null || doc.layers == null || depth > 8)
        {
            return false;
        }

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || layer.type == null)
            {
                continue;
            }

            if (layer.isEntityLayer())
            {
                /* Independent bbsae model actors are rendered directly with the comp camera and
                 * a transparent depth target; they must not request a real-world film matte. */
                if (layer.independent || layer.hasModelForm())
                {
                    continue;
                }

                return true;
            }

            if ("precomp".equals(layer.type) && project != null && layer.precompId != null
                && !layer.precompId.isEmpty()
                && hasEntityLayer(project.getComp(layer.precompId), project, depth + 1))
            {
                return true;
            }
        }

        return false;
    }

    private static void clearLayerRequest()
    {
        if (!requested)
        {
            return;
        }

        requested = false;
        requestedSignature = "";
        requestedIds.clear();
        requestedFilm = "";
        requestOccluded = false;
        requestUnoccluded = false;
    }

    private static void collect(CompDocument doc, CompProject project, Set<String> ids, boolean[] all,
                                boolean[] any, boolean[] occluded, boolean[] unoccluded, int depth)
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
                if (layer.independent || layer.hasModelForm())
                {
                    continue;
                }

                any[0] = true;

                if (layer.entityOcclusion)
                {
                    occluded[0] = true;
                }
                else
                {
                    unoccluded[0] = true;
                }

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
                collect(project.getComp(layer.precompId), project, ids, all, any, occluded, unoccluded, depth + 1);
            }
        }
    }

    private static String filmId(CompProject project)
    {
        if (project == null || project.id == null)
        {
            return "";
        }

        /* The project id is sanitised for storage (parentheses and spaces become underscores),
         * while the film controller is keyed by the original film id. Prefer the live film panel:
         * a mismatch here is silent and makes the actor matte find no controller at all. */
        try
        {
            UIFilmPanel panel = BBSModClient.getDashboard() == null
                ? null
                : BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            if (panel != null && panel.getData() != null)
            {
                return panel.getData().getId();
            }
        }
        catch (RuntimeException ignored)
        {}

        return project.id.startsWith("film_") ? project.id.substring(5) : "";
    }

    /* ------------------------------------------------------------------ render */

    /**
     * Renders the coverage mask for the current frame. Called at {@code AFTER_ENTITIES}, after BBS
     * drew the film's actors, so the depth buffer and the pose stack are the ones the world frame
     * used.
     */
    public static void render(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context)
    {
        if (context == null || context.matrixStack() == null || context.camera() == null)
        {
            return;
        }

        /* The world frame's own pixel size. The matte target must have this exact size, not the
         * window framebuffer's: under BBS' custom-size preview/recorder the world is rendered at the
         * film's resolution (e.g. 1920x1080) while the window may be another aspect. Rendering the
         * coverage into a window-shaped target and sampling it 0..1 over the world plate stretched
         * the matte by the aspect difference — the "entity key is offset" report. */
        int[] viewport = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        if (viewport[2] > 0 && viewport[3] > 0)
        {
            lastWorldWidth = viewport[2];
            lastWorldHeight = viewport[3];
        }

        /* Fallback for hosts that did not call beforeEntities (a load order issue, or a test
         * driving this method directly). Normally the matrices were captured before the actor
         * pass and are the ones the world really used. */
        if (!hasCamera || capturedSerial != serial)
        {
            lastCamera = context.camera();
            lastStack.peek().getPositionMatrix().set(context.matrixStack().peek().getPositionMatrix());
            lastModelView.set(RenderSystem.getModelViewMatrix());
            lastProjection.set(RenderSystem.getProjectionMatrix());
            capturedSerial = serial;
            hasCamera = true;
            updateClipPlanes(lastProjection);

            if (!loggedLiveFallback)
            {
                loggedLiveFallback = true;
                BbsAeAddon.LOGGER.warn("Replay entity mask had no BEFORE_ENTITIES capture; using the live matrices at AFTER_ENTITIES");
            }
        }

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

        /* Snapshot the controller tick that produced the world frame. The lazy overlay path used
         * to read the live controller tick again; a film cursor update between AFTER_ENTITIES and
         * the composition put the matte actor one keyframe away from the world actor. */
        lastActorTick = controller.getTick();

        /* The world colour/depth are in the framebuffer that is current at AFTER_ENTITIES:
         * Minecraft's main target, BBS' custom-size target, or Iris' g-buffer. Copying its depth
         * attachment into the matte target is what turns the actor pass into a hardware occlusion
         * test. */
        int depthSource = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        /* Authoritative world-frame size: ask the framebuffer's colour attachment, not the current
         * viewport (BBS may have restored the window viewport already) and not the window size. */
        int[] attached = FramebufferSize.of(depthSource);

        if (attached != null)
        {
            lastWorldWidth = attached[0];
            lastWorldHeight = attached[1];
        }

        lastDepthSource = depthSource;
        lastDepthSourceValid = depthSource != 0;

        /* A private copy of the pre-actor stack: the live stack may have been left wherever the
         * last form renderer put it. */
        MatrixStack stack = new MatrixStack();

        stack.peek().getPositionMatrix().set(lastStack.peek().getPositionMatrix());

        renderedEntities = draw(controller, lastCamera, stack, context.consumers(),
            transitionOf(controller, context.tickDelta()), depthSource);
        renderedSerial = serial;
        renderedSignature = requestedSignature;
    }

    /**
     * Makes sure the mask belongs to the current frame and the current selection.
     *
     * <p>Called from the frame overlay before a composition is drawn. Normally this is a no-op
     * (the AFTER_ENTITIES pass already produced it). It only renders when the frame has not been
     * masked yet — the first frame of a newly opened composition or of an export — or when the
     * selection/occlusion changed after the world was rendered.</p>
     */
    public static void ensureCurrent(CompProject project, CompDocument doc)
    {
        request(project, doc);

        if (!requested || !hasCamera)
        {
            return;
        }

        /* The world pass has already produced the mask for this frame. Re-rendering it here — to
         * pick up a request that changed between the panel's last UI frame and this overlay — was
         * clearing a valid target with a second render whose GL state is the composition's, not
         * the world's. Let the next AFTER_ENTITIES pass apply a changed selection instead; the
         * only case that genuinely needs a lazy render is the first frame of an export, where the
         * mask has not run for this serial at all. */
        /* Once a coverage target exists, keep it. Under Iris the world frame can bump the serial
         * more than once (shadow/main/extra passes); re-rendering at the overlay then ran the
         * actor pass in a composition GL state, cleared the valid target and left the layer
         * transparent — the "works without shaders, empty with BSL" split. Only the first frame
         * of a composition/export has no target and needs the lazy render. */
        if (isAvailable())
        {
            renderedSignature = requestedSignature;

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
            renderedEntities = draw(controller, lastCamera, stack, null,
                transitionOf(controller, MinecraftClient.getInstance().getTickDelta()),
                lastDepthSourceValid ? lastDepthSource : 0);
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
     * Renders every requested coverage target and returns how many actors were drawn.
     */
    private static int draw(BaseFilmController controller, Camera camera, MatrixStack stack, VertexConsumerProvider consumers,
                            float transition, int depthSource)
    {
        if (controller.film == null || controller.film.replays == null)
        {
            return 0;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.getFramebuffer() == null)
        {
            return 0;
        }

        Framebuffer main = client.getFramebuffer();
        /* Match the world frame, not the window: see render(). */
        java.awt.Dimension size = EntityMaskSizing.targetSize(lastWorldWidth, lastWorldHeight,
            main.textureWidth, main.textureHeight);
        int width = size.width;
        int height = size.height;

        int rendered = 0;

        if (requestOccluded)
        {
            if (occludedTarget.ensure(width, height))
            {
                rendered = Math.max(rendered, drawInto(occludedTarget, controller, camera, stack, consumers,
                    transition, depthSource, width, height, true));
            }
        }

        if (requestUnoccluded)
        {
            if (plainTarget.ensure(width, height))
            {
                rendered = Math.max(rendered, drawInto(plainTarget, controller, camera, stack, consumers,
                    transition, 0, width, height, false));
            }
        }

        return rendered;
    }

    /**
     * Draws the selected replays into one coverage target.
     *
     * @param depthSource world framebuffer whose depth attachment should occlude the actors, or 0
     *                    for an unoccluded pass
     */
    private static int drawInto(Target target, BaseFilmController controller, Camera camera, MatrixStack stack,
                                VertexConsumerProvider consumers, float transition, int depthSource,
                                int worldWidth, int worldHeight, boolean occluded)
    {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        boolean previousDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int[] previousBlendSrc = new int[1];
        int[] previousBlendDst = new int[1];
        int[] previousBlendSrcAlpha = new int[1];
        int[] previousBlendDstAlpha = new int[1];
        boolean previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int[] previousColorMask = new int[4];

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, previousColorMask);
        GL11.glGetIntegerv(GL14.GL_BLEND_SRC_RGB, previousBlendSrc);
        GL11.glGetIntegerv(GL14.GL_BLEND_DST_RGB, previousBlendDst);
        GL11.glGetIntegerv(GL14.GL_BLEND_SRC_ALPHA, previousBlendSrcAlpha);
        GL11.glGetIntegerv(GL14.GL_BLEND_DST_ALPHA, previousBlendDstAlpha);

        boolean queued = FormRenderLast.suspend();
        int rendered = 0;
        boolean depthReady = false;

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
            GlStateManager._viewport(0, 0, target.width, target.height);
            GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            /* Coverage must respect the form's real alpha. With blending disabled, a transparent
             * texel still wrote alpha=1 into the mask, so the matte carried the actor's whole
             * rectangular texture/background. Blending keeps coverage equal to the rendered
             * silhouette instead of the quad. */
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager._enableBlend();
            GL11.glDisable(GL11.GL_CULL_FACE);
            /* Raw GL on purpose: the coverage pass depends on these four bits, and a stale
             * GlStateManager cache would silently skip the call that enables them. */
            GL11.glColorMask(true, true, true, true);
            /* Coverage is a silhouette, not a depth buffer. Writing depth made a nearer body part
             * reject the part behind it, which showed up as transparent holes in the extracted
             * actor. Keep depth *testing* for world occlusion, but never write self depth here. */
            GL11.glDepthMask(false);
            GL11.glDepthFunc(GL11.GL_LEQUAL);

            /* Transparent background: alpha is the matte. */
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            if (occluded)
            {
                depthReady = copyWorldDepth(target, depthSource, worldWidth, worldHeight);
            }

            if (depthReady)
            {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            }
            else
            {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            }

            /* Re-apply the world matrices captured before the actor pass. Without this the form
             * renderers run under whatever model-view the actor pass happened to leave behind,
             * which is how the same actor was drawn at an entirely different screen scale. */
            Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            MatrixStack modelView = RenderSystem.getModelViewStack();

            modelView.push();
            modelView.peek().getPositionMatrix().set(lastModelView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(lastProjection, VertexSorter.BY_Z);
            inMaskRender = true;

            /* The exact sub-tick the world actor used, not Minecraft's tick delta. */
            float actorTransition = hasCamera ? lastActorTransition : transition;

            try
            {
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
                        /* The world actor pass is not necessarily the last thing that touched this
                         * entity. BBS' editor controller renders onion skins AFTER the current
                         * actor, and each onion re-applies the replay's keyframes to a different
                         * tick; the entity is therefore left posed at an onion tick, not at the
                         * shot's tick. Re-rendering the matte without fixing that produced a
                         * second, differently-posed actor (a huge close-up that did not overlap
                         * the real one). Playback does not do onion skins, which is exactly why
                         * the matte "worked" when the compositor was opened from film playback.
                         * Re-apply the replay at the current tick before the coverage render. */
                        float tick = replay.getTick(lastActorTick >= 0 ? lastActorTick : controller.getTick())
                            + actorTransition;

                        replay.keyframes.apply(tick, entity);

                        if (entity.getForm() != null)
                        {
                            replay.properties.applyProperties(entity.getForm(), tick);
                        }

                        mchorse.bbs_mod.forms.renderers.utils.RenderFrame.invalidate();

                        FilmControllerContext filmContext = FilmControllerContext.instance
                            .setup(controller.entities, entity, replay, camera, stack, consumers, actorTransition)
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
                inMaskRender = false;
                RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
                modelView.pop();
                RenderSystem.applyModelViewMatrix();
            }

            /* The coverage target now doubles as the actor-only color image. Use the same white
             * lightmap as the model pass so the extracted actor is not a black silhouette at night;
             * only the actor's own pixels and alpha are used. */
            RenderSystem.setShaderTexture(2, ReplayModelPass.whiteLightmapId());

            target.hasColor = true;
        }
        finally
        {
            FormRenderLast.restore(queued);

            GL11.glColorMask(previousColorMask[0] != 0, previousColorMask[1] != 0, previousColorMask[2] != 0, previousColorMask[3] != 0);
            GlStateManager._depthFunc(previousDepthFunc);
            GlStateManager._depthMask(previousDepthMask);

            if (previousDepth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);

            if (previousBlend)
            {
                GlStateManager._enableBlend();
                GL14.glBlendFuncSeparate(previousBlendSrc[0], previousBlendDst[0],
                    previousBlendSrcAlpha[0], previousBlendDstAlpha[0]);
            }
            else
            {
                GlStateManager._disableBlend();
            }

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

        if (target == occludedTarget)
        {
            renderedOcclusion = occluded && depthReady;

            logOnce("Replay entity coverage: {}x{} entities={} occlusion={} depthSource={}",
                target.width, target.height, rendered, renderedOcclusion, depthSource);
        }
        else
        {
            logOnce("Replay entity coverage (unoccluded): {}x{} entities={}",
                target.width, target.height, rendered);
        }

        if (Boolean.getBoolean("bbsae.debugEntityMask") && target.hasColor)
        {
            debugStats(target, rendered);
        }

        return rendered;
    }

    /**
     * Copies the world depth attachment into the coverage target.
     *
     * <p>Hardware depth testing then gives exactly the result the old shader comparison wanted:
     * the actor's own depth equals the world depth (the same geometry, the same matrices), so it
     * passes {@code LEQUAL}; a block in front is nearer and rejects it. The blit is probed once per
     * target size. If the driver refuses it the pass still renders — unoccluded — because an actor
     * that shows through a wall is a far better failure than an invisible layer.</p>
     */
    private static boolean copyWorldDepth(Target target, int sourceFbo, int sourceWidth, int sourceHeight)
    {
        if (sourceFbo == 0 || sourceFbo == target.framebuffer || !target.withDepth)
        {
            return false;
        }

        if (target.depthChecked && !target.depthOk)
        {
            return false;
        }

        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = false;

        try
        {
            if (!target.depthChecked)
            {
                drainErrors();
            }

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.framebuffer);
            int[] source = EntityMaskSizing.blitSource(sourceWidth, sourceHeight, target.width, target.height);
            int srcW = source[0];
            int srcH = source[1];

            /* Source rect must describe the WORLD framebuffer; using the target's size read the
             * bottom-left corner (or overran) whenever the custom-size world differed from the
             * window, so occlusion no longer matched the plate. glBlitFramebuffer scales between
             * differently sized rects, which is exactly the mapping needed here. */
            GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, target.width, target.height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

            if (!target.depthChecked)
            {
                int error = GL11.glGetError();

                target.depthChecked = true;
                target.depthOk = error == GL11.GL_NO_ERROR;
                ok = target.depthOk;

                if (ok)
                {
                    BbsAeAddon.LOGGER.info("Replay entity occlusion: world depth {}x{} copied from framebuffer {}",
                        target.width, target.height, sourceFbo);
                }
                else
                {
                    BbsAeAddon.LOGGER.warn("Replay entity occlusion unavailable (GL error {}); the matte will render unoccluded",
                        error);
                }
            }
            else
            {
                ok = target.depthOk;
            }
        }
        catch (RuntimeException e)
        {
            target.depthChecked = true;
            target.depthOk = false;

            BbsAeAddon.LOGGER.warn("Replay entity occlusion unavailable; the matte will render unoccluded", e);
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                oldDraw == target.framebuffer ? target.framebuffer : oldDraw);
        }

        return ok;
    }

    private static void drainErrors()
    {
        for (int i = 0; i < 8; i++)
        {
            if (GL11.glGetError() == GL11.GL_NO_ERROR)
            {
                return;
            }
        }
    }

    private static boolean loggedCoverage;
    private static boolean loggedPlain;

    private static void logOnce(String message, Object... args)
    {
        if (message.startsWith("Replay entity coverage:") ? loggedCoverage : loggedPlain)
        {
            return;
        }

        if (message.startsWith("Replay entity coverage:"))
        {
            loggedCoverage = true;
        }
        else
        {
            loggedPlain = true;
        }

        BbsAeAddon.LOGGER.info(message, args);
    }

    /** One-shot readback: the proof that the coverage target is not empty. */
    private static void debugStats(Target target, int rendered)
    {
        if (target.statsLogged)
        {
            return;
        }

        target.statsLogged = true;

        ByteBuffer buffer = MemoryUtil.memAlloc(target.width * target.height * 4);
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.framebuffer);
            GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            int covered = 0;
            int minX = target.width;
            int minY = target.height;
            int maxX = -1;
            int maxY = -1;

            for (int y = 0; y < target.height; y++)
            {
                for (int x = 0; x < target.width; x++)
                {
                    int i = (x + y * target.width) * 4;
                    int r = buffer.get(i) & 0xFF;
                    int g = buffer.get(i + 1) & 0xFF;
                    int b = buffer.get(i + 2) & 0xFF;
                    int a = buffer.get(i + 3) & 0xFF;

                    if (a > 8 || Math.max(r, Math.max(g, b)) > 8)
                    {
                        covered++;
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }

            BbsAeAddon.LOGGER.info("Replay entity coverage stats: {} covered of {} ({}x{}), bbox=[{},{}..{},{}], entities={}",
                covered, target.width * target.height, target.width, target.height, minX, minY, maxX, maxY, rendered);

            if (Boolean.getBoolean("bbsae.debugEntityDump"))
            {
                dumpCoverage(target, buffer);
            }
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            MemoryUtil.memFree(buffer);
        }
    }

    /** Writes the raw coverage target to a PNG so the mask can be inspected directly. */
    private static void dumpCoverage(Target target, ByteBuffer buffer)
    {
        try
        {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                target.width, target.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < target.height; y++)
            {
                for (int x = 0; x < target.width; x++)
                {
                    int i = (x + y * target.width) * 4;
                    int r = buffer.get(i) & 0xFF;
                    int g = buffer.get(i + 1) & 0xFF;
                    int b = buffer.get(i + 2) & 0xFF;
                    int a = buffer.get(i + 3) & 0xFF;

                    image.setRGB(x, target.height - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            java.io.File file = new java.io.File(MinecraftClient.getInstance().runDirectory,
                "screenshots/bbsae-entity-coverage.png");

            javax.imageio.ImageIO.write(image, "png", file);
            BbsAeAddon.LOGGER.info("Replay entity coverage dumped to {}", file.getAbsolutePath());
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not dump the replay entity coverage", e);
        }
    }

    private static float nearPlane = 0.05F;
    private static float farPlane = 1024F;

    /**
     * Extracts the world camera's near/far from its projection matrix. Kept as diagnostics and for
     * the depth-linearisation helper used by the mask inspector; the matte itself no longer
     * compares depth in the shader.
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

    /* ------------------------------------------------------------------ targets */

    /**
     * One RGBA coverage target. The optional depth attachment is only needed for the occluded
     * pass; the unoccluded pass never enables depth testing.
     */
    private static final class Target
    {
        final boolean withDepth;
        int framebuffer;
        int colorTexture;
        int depthTexture;
        int width;
        int height;
        boolean available;
        boolean hasColor;
        boolean statsLogged;
        boolean depthChecked;
        boolean depthOk;

        Target(boolean withDepth)
        {
            this.withDepth = withDepth;
        }

        boolean ensure(int w, int h)
        {
            if (this.framebuffer != 0 && this.width == w && this.height == h && this.available)
            {
                return true;
            }

            this.release();

            /* Allocation happens while the composition's layer target is current. Restore the
             * framebuffer, texture unit and texture binding afterwards: leaving unit 0 or the
             * default framebuffer bound here would make the next layer draw into the screen. */
            int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int previousActive = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            this.width = w;
            this.height = h;
            this.colorTexture = GL11.glGenTextures();

            GlStateManager._bindTexture(this.colorTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

            this.framebuffer = GL30.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.colorTexture, 0);
            GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

            if (this.withDepth)
            {
                this.depthTexture = GL11.glGenTextures();
                GlStateManager._bindTexture(this.depthTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, w, h, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.depthTexture, 0);
            }

            this.available = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;

            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL13.glActiveTexture(previousActive);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);

            this.hasColor = false;
            this.statsLogged = false;
            this.depthChecked = false;
            this.depthOk = false;

            if (!this.available)
            {
                BbsAeAddon.LOGGER.warn("Replay entity coverage target {}x{} is incomplete; the layer will be empty", w, h);
            }

            return this.available;
        }

        void release()
        {
            if (this.framebuffer != 0)
            {
                GL30.glDeleteFramebuffers(this.framebuffer);
                this.framebuffer = 0;
            }

            if (this.colorTexture != 0)
            {
                GL11.glDeleteTextures(this.colorTexture);
                this.colorTexture = 0;
            }

            if (this.depthTexture != 0)
            {
                GL11.glDeleteTextures(this.depthTexture);
                this.depthTexture = 0;
            }

            this.available = false;
            this.hasColor = false;
            this.depthChecked = false;
            this.depthOk = false;
            this.width = 0;
            this.height = 0;
        }
    }

    public static boolean isAvailable()
    {
        return (occludedTarget.hasColor && occludedTarget.available)
            || (plainTarget.hasColor && plainTarget.available);
    }

    /**
     * The coverage texture for an entity layer.
     *
     * @param occluded true selects the target rendered with the world depth preloaded
     */
    public static int texture(boolean occluded)
    {
        if (occluded)
        {
            return occludedTarget.available && occludedTarget.hasColor ? occludedTarget.colorTexture : 0;
        }

        return plainTarget.available && plainTarget.hasColor ? plainTarget.colorTexture : 0;
    }

    /** The texture of whichever pass ran; diagnostics only. */
    public static int texture()
    {
        if (occludedTarget.hasColor)
        {
            return occludedTarget.colorTexture;
        }

        return plainTarget.hasColor ? plainTarget.colorTexture : 0;
    }

    public static int width()
    {
        return occludedTarget.width != 0 ? occludedTarget.width : plainTarget.width;
    }

    public static int height()
    {
        return occludedTarget.height != 0 ? occludedTarget.height : plainTarget.height;
    }

    /** How many entities the current mask contains; 0 means "nothing to key". */
    public static int entityCount()
    {
        return renderedEntities;
    }

    /** True when the last occluded pass really tested against a copy of the world depth. */
    public static boolean occlusionAvailable()
    {
        return renderedOcclusion;
    }

    private static boolean renderedOcclusion;

    public static void release()
    {
        occludedTarget.release();
        plainTarget.release();
        renderedSerial = -1;
    }

    /** Drops the request; used when the compositor closes. */
    public static void clearRequest()
    {
        requested = false;
        requestedSignature = "";
        requestedIds.clear();
        requestedFilm = "";
        requestOccluded = false;
        requestUnoccluded = false;
        renderedSignature = "";
        renderedSerial = -1;
        renderedEntities = 0;
        hasCamera = false;
        lastDepthSource = 0;
        lastDepthSourceValid = false;
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
