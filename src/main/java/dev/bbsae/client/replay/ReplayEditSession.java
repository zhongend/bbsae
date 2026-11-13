package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * The editing session behind the replay mode panel: which film/replay is being edited, the
 * free 3D camera, the playhead, and the bridge to the film's live actor entities.
 *
 * <h2>What this reuses instead of rebuilding</h2>
 * <p>Everything here leans on the objects the film editor already owns: the dashboard's single
 * {@link UIFilmPanel} supplies the film, the undo history and the BBS replay gizmo target; its
 * {@link UIFilmController} supplies the actor entities and the pose application; and
 * {@code UIReplayPropTransform} is the original gizmo-to-keyframe adapter. The panel is a new
 * view onto those, never a second editor instance (see the plan's §0/R1/R2).</p>
 *
 * <p>The playhead is the film panel's own tick cursor, driven by this panel's transport while it
 * is on screen. That is what keeps the original keyframe editor, its ruler, the undo snapshots and
 * the written keyframes all speaking the same tick — an independent clock here would put every
 * key this panel writes a fraction off the frame the user sees.</p>
 */
public final class ReplayEditSession
{
    private final UIFilmPanel filmPanel;
    private final Film film;
    private final CompViewportCamera camera = new CompViewportCamera();
    private final ReplaySceneSpace space = new ReplaySceneSpace();
    private final UIReplayPropTransform replayTransform = new UIReplayPropTransform();

    private Replay replay;
    /** Set once the viewport had a real size and the scene was framed. */
    private boolean framed;
    private boolean playing;
    private long lastPlayNanos;
    private int debugAdvance;
    /** The replay page owns its transport; the borrowed film runner may be rewritten by BBS. */
    private float localPlayhead;
    private boolean localPlayheadValid;

    /* Per-frame visibility (frustum culling for big films); recomputed by the viewport before the
     * model and pick passes so both draw the same set. */
    private boolean[] visible = new boolean[0];
    private int visibleCount;
    private int culledCount;
    private boolean cullActive;
    /** Optional user-facing filter (the replay editor only shows virtual-world replays). */
    private java.util.function.Predicate<Replay> replayFilter;

    public void setReplayFilter(java.util.function.Predicate<Replay> filter)
    {
        this.replayFilter = filter;
        this.visible = new boolean[0];
    }

    public boolean isAllowed(Replay replay)
    {
        return this.replayFilter == null || this.replayFilter.test(replay);
    }

    public ReplayEditSession(UIFilmPanel filmPanel, Film film, Replay replay)
    {
        this.filmPanel = filmPanel;
        this.film = film;
        this.replay = replay;
        /* The replay editor is a 3D perspective workspace. CompViewportCamera defaults to
         * perspective, but older persisted/debug state could have left ortho on; make the contract
         * explicit so the actor/gizmo projection cannot silently flatten. */
        this.camera.setOrtho(false);
        this.syncSceneScale();

        if (filmPanel != null && filmPanel.getRunner() != null && filmPanel.getRunner().isRunning())
        {
            filmPanel.getRunner().setPlaying(false);
        }
    }

    /**
     * Use exactly the same blocks-to-composition-pixels scale as the linked-model pass.
     *
     * <p>The compositor derives it from the composition height or the project's
     * {@code modelPixelsPerBlock}. The replay editor used to keep its own 64 px/block default, so
     * the same box was about three times larger there and its depth read as a different thickness.
     * The calibration property remains the fallback only when no composition is open.</p>
     */
    public void syncSceneScale()
    {
        dev.bbsae.client.ui.UICompositorPanel panel = BbsAeClient.compositorPanel();
        CompProject project = panel == null ? null : panel.getProject();
        CompDocument doc = panel == null ? null : panel.getDocument();

        if (doc == null)
        {
            return;
        }

        int custom = project == null || project.settings == null ? 0 : project.settings.modelPixelsPerBlock;
        float pixelsPerBlock = custom > 0
            ? Math.max(8F, Math.min(128F, custom))
            : Math.max(8F, Math.min(64F, doc.height * 0.12F));

        this.space.pixelsPerBlock = pixelsPerBlock;
    }

    public UIFilmPanel filmPanel()
    {
        return this.filmPanel;
    }

    public Film film()
    {
        return this.film;
    }

    public Replay replay()
    {
        return this.replay;
    }

    public void setReplay(Replay replay)
    {
        this.replay = replay;
    }

    public CompViewportCamera camera()
    {
        return this.camera;
    }

    public ReplaySceneSpace space()
    {
        return this.space;
    }

    /* ------------------------------------------------------------ entities */

    /**
     * The controller whose entity map the actor pass renders.
     *
     * <p>The film editor's own controller is preferred: it is the one the original replay editor
     * writes to, so a gizmo drag here and a gizmo drag there move the same entity. A playing film
     * owns its own controller; only a film that is not open in the editor could ever fall back
     * to it, and then the actor pass still renders correctly.</p>
     */
    public BaseFilmController controller()
    {
        try
        {
            UIFilmController controller = this.filmPanel.getController();

            if (controller != null && controller.editorController != null
                && controller.editorController.film == this.film)
            {
                return controller.editorController;
            }

            return BBSModClient.getFilms() == null ? null : BBSModClient.getFilms().getController(this.film.getId());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    public Map<String, IEntity> entities()
    {
        BaseFilmController controller = this.controller();

        return controller == null ? null : controller.getEntities();
    }

    public IEntity entity(Replay replay)
    {
        Map<String, IEntity> entities = this.entities();

        return entities == null || replay == null ? null : entities.get(replay.getId());
    }

    public IEntity selectedEntity()
    {
        return this.entity(this.replay);
    }

    /** Index of a replay in the film's replay list, or -1.
     *
     * <p>Identity, not {@code List.indexOf}: {@code Replay} extends {@code ValueGroup}, whose
     * {@code equals} compares contents, so an equal-valued replay would win the lookup and the
     * selection/pick could address the wrong actor.</p>
     */
    public int replayIndex(Replay replay)
    {
        return ReplayListOps.indexOfIdentical(this.film.replays.getList(), replay);
    }

    public Replay replayAt(int index)
    {
        return ReplayListOps.elementAt(this.film.replays.getList(), index);
    }

    /* ------------------------------------------------------------ visibility */

    /**
     * Recomputes which replays the 3D passes should process this frame.
     *
     * <p>Small films keep every actor; big films frustum-test them against the viewport camera and
     * skip everything that cannot contribute a pixel, with the selected replay always kept so the
     * gizmo never disappears on the thing being edited. The list is computed once and consumed by
     * both the colour pass and the pick pass, so "visible there" and "pickable here" cannot
     * disagree.</p>
     */
    public void updateVisibility()
    {
        List<Replay> replays = this.film.replays.getList();
        int count = replays.size();
        Map<String, IEntity> entities = this.entities();

        this.visibleCount = count;
        this.culledCount = 0;
        this.cullActive = count > 0 && entities != null && ReplayCulling.shouldCull(count);

        if (!this.cullActive)
        {
            this.visible = ReplayCulling.allVisible(count);
            this.applyReplayFilter(replays);

            return;
        }

        Camera camera = this.viewCamera();
        Matrix4f viewProjection = new Matrix4f(camera.projection).mul(camera.view);
        List<Vector3f> positions = new java.util.ArrayList<>(count);

        for (Replay candidate : replays)
        {
            IEntity entity = candidate == null ? null : entities.get(candidate.getId());

            positions.add(entity == null ? null
                : new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ()));
        }

        this.visible = ReplayCulling.visible(viewProjection, positions,
            ReplayCalibration.cullRadius(), this.replayIndex(this.replay));
        this.applyReplayFilter(replays);
    }

    /** Hides replays excluded by the editor's virtual-world filter from every 3D pass. */
    private void applyReplayFilter(List<Replay> replays)
    {
        if (this.replayFilter == null)
        {
            this.visibleCount = ReplayCulling.countVisible(this.visible);
            this.culledCount = replays.size() - this.visibleCount;

            return;
        }

        for (int i = 0; i < this.visible.length && i < replays.size(); i++)
        {
            if (!this.replayFilter.test(replays.get(i)))
            {
                this.visible[i] = false;
            }
        }

        this.visibleCount = ReplayCulling.countVisible(this.visible);
        this.culledCount = replays.size() - this.visibleCount;
    }

    /** True when the replay at the list index should be processed this frame (defaults to true). */
    public boolean isReplayVisible(int index)
    {
        if (index < 0 || this.visible.length == 0 || index >= this.visible.length)
        {
            return true;
        }

        return this.visible[index];
    }

    public int replayCount()
    {
        if (this.replayFilter == null)
        {
            return this.film.replays.getList().size();
        }

        int count = 0;

        for (Replay replay : this.film.replays.getList())
        {
            if (this.isAllowed(replay))
            {
                count += 1;
            }
        }

        return count;
    }

    /** Enabled replays that have a live entity with a form — what the 3D pass can draw at all. */
    public int placeableCount()
    {
        Map<String, IEntity> entities = this.entities();

        if (entities == null)
        {
            return 0;
        }

        int count = 0;

        for (Replay candidate : this.film.replays.getList())
        {
            if (candidate == null || !Boolean.TRUE.equals(candidate.enabled.get())
                || !this.isAllowed(candidate))
            {
                continue;
            }

            IEntity entity = entities.get(candidate.getId());

            if (entity != null && entity.getForm() != null)
            {
                count += 1;
            }
        }

        return count;
    }

    /** True when this replay has something to draw/appear in the viewport (entity with a form). */
    public boolean hasModel(Replay replay)
    {
        IEntity entity = this.entity(replay);

        return entity != null && entity.getForm() != null;
    }

    public int visibleCount()
    {
        return this.visibleCount;
    }

    public int culledCount()
    {
        return this.culledCount;
    }

    public boolean isCullActive()
    {
        return this.cullActive;
    }

    /**
     * Makes sure the film has live actor entities. The film editor builds them when it opens a
     * film; opening the replay mode from the compositor can happen while that panel never came on
     * screen, so this repeats the same call the original editor makes.
     */
    public void ensureEntities()
    {
        try
        {
            UIFilmController controller = this.filmPanel.getController();

            if (controller == null || this.filmPanel.getData() == null)
            {
                return;
            }

            if (controller.editorController == null || controller.editorController.film != this.film
                || controller.getEntities().isEmpty())
            {
                controller.createEntities();
            }
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay mode could not create the film's replay entities", e);
        }
    }

    /**
     * Recreates the actor entities unconditionally. Used when the replay set itself changed (a
     * replay was added, duplicated or its form swapped): {@link #ensureEntities()} only heals a
     * missing controller, not a controller that is one entity short.
     */
    public void recreateEntities()
    {
        try
        {
            UIFilmController controller = this.filmPanel.getController();

            if (controller != null && this.filmPanel.getData() != null)
            {
                controller.createEntities();
            }
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay mode could not recreate the film's replay entities", e);
        }
    }

    /* ------------------------------------------------------------ playhead */

    /** Whole ticks of the playhead; the original keyframe editor's cursor. */
    public int tick()
    {
        return (int) Math.floor(this.playhead());
    }

    /** Film tick plus the sub-tick fraction, exactly what the actor pass samples. */
    public float playhead()
    {
        if (this.localPlayheadValid)
        {
            return this.localPlayhead;
        }

        return this.filmPanel.getCursor(0F);
    }

    /** Where authoring writes land (respects BBS' "snap to ticks" setting, like the original). */
    public float keyframeTick()
    {
        return BBSSettings.editorSnapToTicks.get() ? Math.round(this.playhead()) : this.playhead();
    }

    /** Sub-tick phase inside the playhead, used by the actor renderer. */
    public float transition()
    {
        return ReplayTime.phase(this.playhead());
    }

    public int durationTicks()
    {
        int duration = this.film.camera.calculateDuration();

        return Math.max(1, duration);
    }

    /** Seeks the shared film cursor, clamped to the film's duration (NaN-safe). */
    public void seek(float ticks)
    {
        float value = ReplayTime.clampTick(ticks, this.durationTicks());

        this.localPlayhead = value;
        this.localPlayheadValid = true;

        /* Drive BBS' runner directly, like the original transport does. UIFilmPanel.setCursor
         * also fires a SEEK packet and can schedule an action restart on every call; at the replay
         * page's per-frame playback cadence that restarts the actors continuously and makes the
         * tracks look like they never play. The runner is the single source the keyframe editor,
         * the controller and all editor cursors read. */
        if (this.filmPanel.getRunner() != null)
        {
            this.filmPanel.getRunner().setCursor(value);
        }
        else
        {
            this.filmPanel.setCursor(value);
        }
    }

    public void step(int ticks)
    {
        this.playing = false;
        this.seek(this.tick() + ticks);
    }

    public boolean isPlaying()
    {
        return this.playing;
    }

    public void setPlaying(boolean playing)
    {
        /* The borrowed film panel may still have its own runner playing from the film editor. If
         * it is left running, its update and our manual cursor fight each other and the runner
         * rewinds to 0 at the clip end. The replay page owns the transport while it is open. */
        if (playing && this.filmPanel.getRunner() != null && this.filmPanel.getRunner().isRunning())
        {
            this.filmPanel.getRunner().setPlaying(false);
        }

        if (playing && !this.localPlayheadValid)
        {
            this.localPlayhead = this.filmPanel.getCursor(0F);
            this.localPlayheadValid = true;
        }

        this.playing = playing;
        this.lastPlayNanos = 0L;
    }

    public void togglePlaying()
    {
        this.setPlaying(!this.playing);
    }

    /**
     * Advances the playhead while the transport runs. Driven from the panel's update, which runs
     * once per UI frame while it is on screen — the film editor's own runner only advances while
     * that panel is the visible one, so the replay mode cannot rely on it.
     */
    public void advancePlayback()
    {
        if (!this.playing)
        {
            this.lastPlayNanos = 0L;

            return;
        }

        long now = System.nanoTime();

        if (this.lastPlayNanos == 0L)
        {
            this.lastPlayNanos = now;

            return;
        }

        float delta = (now - this.lastPlayNanos) / 1_000_000_000F;

        this.lastPlayNanos = now;

        int duration = this.durationTicks();
        float before = this.playhead();
        float next = before + delta * ReplayTime.TICKS_PER_SECOND;

        /* BBS' RunnerCameraController advances one tick per client tick and stops at the clip
         * duration; it does not wrap. Wrapping here made the replay loop forever and looked like
         * the track was not honoring the film end. */
        if (next >= duration)
        {
            next = duration;
            this.playing = false;
            this.lastPlayNanos = 0L;
        }

        this.seek(next);
        this.debugAdvance += 1;

        if (Boolean.getBoolean("bbsae.debugReplayPlayback") && (this.debugAdvance % 10 == 0))
        {
            BbsAeAddon.LOGGER.info("Replay advance #{}: delta={}s before={} next={} after={} ticks={} running={} duration={}",
                this.debugAdvance, delta, before, next, this.playhead(),
                this.filmPanel.getRunner() == null ? -1 : this.filmPanel.getRunner().ticks,
                this.filmPanel.getRunner() != null && this.filmPanel.getRunner().isRunning(),
                duration);
        }
    }

    /* ------------------------------------------------------------ pose */

    /**
     * Applies a replay's keyframes and form properties to its entity at the current playhead.
     * Mirrors what the original film editor's update loop does before it renders an actor, but at
     * the exact playhead instead of the simulation tick, so scrubbing shows the written keys.
     */
    public void applyPose(IEntity entity, Replay replay)
    {
        if (entity == null || replay == null)
        {
            return;
        }

        float playhead = this.playhead();
        float tick = replay.getTick((int) Math.floor(playhead)) + ReplayTime.phase(playhead);

        replay.keyframes.apply(tick, entity);

        if (entity.getForm() != null)
        {
            replay.properties.applyProperties(entity.getForm(), tick);
        }

        RenderFrame.invalidate();
    }

    /** Applies the pose to every enabled replay of the film; returns how many entities it touched. */
    public int applyAllPoses()
    {
        Map<String, IEntity> entities = this.entities();

        if (entities == null)
        {
            return 0;
        }

        int applied = 0;

        for (Replay candidate : this.film.replays.getList())
        {
            if (candidate == null || !Boolean.TRUE.equals(candidate.enabled.get())
                || !this.isAllowed(candidate))
            {
                continue;
            }

            IEntity entity = entities.get(candidate.getId());

            if (entity == null)
            {
                continue;
            }

            this.applyPose(entity, candidate);
            applied += 1;
        }

        return applied;
    }

    /* ------------------------------------------------------------ cameras & gizmos */

    /** The BBS camera for gizmo dragging and the gizmo frame, built from the viewport camera. */
    public Camera viewCamera()
    {
        return ReplayViewCamera.create(this.camera, this.space);
    }

    /** The Minecraft camera the actor pass and the form renderers read. */
    public net.minecraft.client.render.Camera actorCamera()
    {
        return ReplayViewCamera.actorCamera(this.camera, this.space);
    }

    /** The original replay-placement gizmo target, refreshed at the playhead. */
    public UIReplayPropTransform gizmoTransform()
    {
        this.replayTransform.syncFromReplay(this.replay, this.selectedEntity(), this.keyframeTick());

        return this.replayTransform;
    }

    /**
     * The drag session the original gizmo needs: screen motion is projected through the same
     * mapped camera that drew the handles, and the GLOBAL frame is the replay's own facing (the
     * original film gizmo sets the same axes).
     */
    public GizmoDrag gizmoDrag(Area area)
    {
        Camera camera = this.viewCamera();
        GizmoDrag drag = GizmoDrag.fromRenderedGizmo(camera, area);

        if (drag != null)
        {
            IEntity entity = this.selectedEntity();

            if (entity != null)
            {
                drag.setGlobalAxes(FilmMatrices.getReplayWorldAxes(entity, this.transition()));
            }
        }

        return drag;
    }

    /** Gizmo interaction is only valid while a replay with a live entity is selected. */
    public boolean canEditPlacement()
    {
        return this.replay != null && this.selectedEntity() != null;
    }

    /* ------------------------------------------------------------ framing */

    /**
     * Frames every enabled actor in the viewport and re-centres the composition origin on them.
     *
     * <p>{@link ReplaySceneSpace#centerOn} is what makes the scene orbit around the actors
     * regardless of where in the world the film was shot, and it is cheap enough to run once per
     * selection change.</p>
     */
    public void frameAll()
    {
        Map<String, IEntity> entities = this.entities();

        if (entities == null || this.film.replays.getList().isEmpty())
        {
            return;
        }

        /* Frame the replays that actually have a model. A film can contain placement-only replays
         * (no form) parked at the world origin; framing every entity made the camera sit 5590
         * composition pixels away from a one-block actor in the first real-machine screenshot.
         * Only when the film has no models at all does it fall back to every live entity. */
        List<Replay> frameList = new java.util.ArrayList<>();

        for (Replay candidate : this.film.replays.getList())
        {
            if (candidate == null || !Boolean.TRUE.equals(candidate.enabled.get())
                || !this.isAllowed(candidate))
            {
                continue;
            }

            IEntity entity = entities.get(candidate.getId());

            if (entity != null && entity.getForm() != null)
            {
                frameList.add(candidate);
            }
        }

        if (frameList.isEmpty())
        {
            for (Replay candidate : this.film.replays.getList())
            {
                if (candidate == null || !Boolean.TRUE.equals(candidate.enabled.get())
                    || !this.isAllowed(candidate))
                {
                    continue;
                }

                if (entities.get(candidate.getId()) != null)
                {
                    frameList.add(candidate);
                }
            }
        }

        if (frameList.isEmpty())
        {
            /* No replay with a live entity: a normal state (empty film, plugin-less forms), not an
             * error. Reset the camera quietly; the panel's status line explains how to add one. */
            this.defaultFraming();

            return;
        }

        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

        for (Replay candidate : frameList)
        {
            IEntity entity = entities.get(candidate.getId());

            this.applyPose(entity, candidate);

            Vector3f comp = this.space.toComp(entity.getX(), entity.getY(), entity.getZ());

            ReplayFraming.accumulate(min, max, comp.x, comp.y, comp.z);
        }

        /* A NaN/infinite actor position (molang-driven keyframe, corrupt value) must not reach the
         * camera: one bad coordinate turns the whole view matrix into NaN and the viewport goes
         * blank with no error. Nothing valid to frame → safe default camera. */
        if (!ReplayFraming.isValidBounds(min, max))
        {
            this.fallbackFraming();

            return;
        }

        /* Second pass, after the origin moved: the bounds have to be measured in the final
         * composition space (the first pass measured them around the old anchor). */
        Vector3f centerWorld = ReplayFraming.center(min, max, new Vector3f());
        Vector3f center = this.space.toWorld(centerWorld);

        if (!ReplayFraming.isFinite(center))
        {
            this.fallbackFraming();

            return;
        }

        this.space.centerOn(center.x, center.y, center.z);

        min.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        max.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

        for (Replay candidate : frameList)
        {
            IEntity entity = entities.get(candidate.getId());

            if (entity == null)
            {
                continue;
            }

            Vector3f comp = this.space.toComp(entity.getX(), entity.getY(), entity.getZ());

            ReplayFraming.accumulate(min, max, comp.x, comp.y, comp.z);
        }

        if (!ReplayFraming.isValidBounds(min, max))
        {
            this.fallbackFraming();

            return;
        }

        this.camera.frame(min, max);

        /* A single actor has (almost) zero bounds, and framing that put the camera at distance 3
         * composition pixels — i.e. inside the model — in the second real-machine screenshot.
         * Keep a human-scale minimum distance so the actor is actually visible. */
        float minDistance = ReplayFraming.safeDistance(
            ReplayCalibration.frameMinDistance(), ReplayFraming.FALLBACK_DISTANCE);
        float distance = ReplayFraming.safeDistance(this.camera.distance, minDistance);

        if (distance < minDistance)
        {
            distance = minDistance;
        }

        this.camera.setPose(this.camera.yaw, this.camera.pitch, distance);
        this.camera.update();
        this.framed = true;
    }

    /** The camera state used whenever there is nothing sane to frame. */
    private void fallbackFraming()
    {
        this.defaultFraming();

        BbsAeAddon.LOGGER.warn("Replay mode framing had no usable bounds; using the default camera");
    }

    /** Quiet reset to the default free camera (empty scene / no models). */
    private void defaultFraming()
    {
        this.space.anchor.set(0F, 0F, 0F);
        this.camera.setTarget(0F, 0F, 0F).setPose(0F, 20F, ReplayFraming.FALLBACK_DISTANCE);
        this.camera.update();
        this.framed = true;
    }


    public boolean isFramed()
    {
        return this.framed;
    }

    /** One-time framing as soon as the viewport has a real size. */
    public void frameIfNeeded(Area area)
    {
        if (this.framed || area == null || area.w < 16 || area.h < 16)
        {
            return;
        }

        this.camera.setViewport(area.w, area.h);
        this.frameAll();
    }

    public void resetFraming()
    {
        this.framed = false;
    }
}
