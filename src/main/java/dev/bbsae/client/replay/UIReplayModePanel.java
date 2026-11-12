package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysListPanel;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The "entity replay" dashboard panel: bbsae's 3D space with BBS' original replay editing around it.
 *
 * <h2>What is original and what is bbsae</h2>
 * <p>The panel itself is a new {@link UIDashboardPanel} (registered by {@code BbsAeClient}, so the
 * main dashboard gains an 实体回放 entry). Everything inside it that BBS already does well is the
 * original control, not a copy:</p>
 * <ul>
 *   <li>the replay list is {@link UIReplaysListPanel} (add/duplicate/remove/categories/search/body
 *       parts, model picking through {@code UIFormPalette}),</li>
 *   <li>the properties column is {@link UIReplayPropertiesPanel} (enabled/label/name tag/shadow/
 *       looping/relative/axes preview),</li>
 *   <li>the timeline is {@code UIKeyframeEditor} with {@link UIFilmKeyframes} and the rows are
 *       built from {@code TrackCatalog} by {@link ReplayTrackBuilder} — the original dope sheet,
 *       ruler, interpolation menus, copy/paste and the original replay channels,</li>
 *   <li>the gizmo is {@code Gizmo.INSTANCE} driven through {@code GizmoInteraction} on top of
 *       {@code UIReplayPropTransform} — the original handles and the original keyframe writes.</li>
 * </ul>
 * <p>Only the 3D viewport is bbsae's own: the composition-space free camera plus a model pass that
 * draws the film's actors into a private framebuffer ({@link ReplayModelPass}).</p>
 *
 * <h2>Why it never creates a second film editor</h2>
 * <p>The panel borrows the dashboard's single {@link UIFilmPanel} — its film, its controller's
 * entities, its undo history, its camera clip delegate — and never constructs a second
 * {@code UIFilmPanel}, {@code UIReplaysEditor} or {@code UIFilmController}. Rebuilding any of
 * those would fight BBS' world rendering, its preview sizing and its undo slot (plan §0, R1/R2).</p>
 *
 * <h2>Fallback</h2>
 * <p>The 原版回放编辑 button is the documented route A: it switches the dashboard back to the real
 * film editor and opens its replay editor, so a user blocked by a 3D-viewport problem still gets
 * the complete original experience in one click.</p>
 */
public class UIReplayModePanel extends UIDashboardPanel
{
    private static final int HEADER = 26;
    private static final int TIMELINE = 216;
    private static final int LIST_WIDTH = 190;
    private static final int PROPS_WIDTH = 252;

    private final UILabel title;
    private final UILabel status;
    private final UIButton play;
    private final UIButton prevFrame;
    private final UIButton nextFrame;
    private final UIButton frameAll;
    private final UIButton models;
    private final UIButton fallback;
    private final UIElement header;
    private final UIElement main;

    private UIFilmPanel filmPanel;
    private Film film;
    private Replay replay;
    private ReplayEditSession session;

    private UIReplaysListPanel replaysList;
    private UIReplayPropertiesPanel replayProperties;
    private UIReplayViewport viewport;
    private UIKeyframeEditor keyframeEditor;

    private boolean built;
    private boolean tracksDirty;
    private boolean primed;
    private boolean lastPlaying;
    /** Re-entrancy guard for {@link #applySelection(Replay, boolean)}; see the method's comment. */
    private boolean selectingReplay;
    /** The replay list as it looked last frame, for identity-preserving selection across edits. */
    private final java.util.List<Replay> knownOrder = new java.util.ArrayList<>();
    private String selectedPart = "";
    private String cachedStatus = "";
    /** Visibility the borrowed film panel had before being attached hidden; restored on detach. */
    private boolean borrowedFilmPanelVisible = true;
    /** Invisible root-level holder that keeps the borrowed film panel connected (see attach). */
    private UIElement borrowedFilmPanelHolder;

    /* Verification setup requested by ReplayDebugHook; applied once the viewport has framed. */
    private boolean debugCameraPending;
    private boolean debugTickPending;
    private int debugReplayIndex;
    private int debugTick;
    private float debugYaw = Float.NaN;
    private float debugPitch = Float.NaN;
    private float debugDistance = Float.NaN;

    public UIReplayModePanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.header = new UIElement();
        this.header.relative(this).x(0).y(0).w(1F).h(HEADER);

        this.main = new UIElement();
        this.main.relative(this).x(0).y(HEADER).w(1F).h(1F, -HEADER - TIMELINE);

        this.title = new UILabel(IKey.constant("实体回放"));
        this.title.color(0xFFFFFFFF, false);
        this.title.relative(this.header).x(10).y(0).w(104).h(HEADER);

        int x = 116;

        this.prevFrame = headerButton("上一帧", x, 74, () -> this.step(-1));
        x += 78;
        this.nextFrame = headerButton("下一帧", x, 74, () -> this.step(1));
        x += 78;
        this.play = headerButton("播放", x, 66, () -> this.togglePlayback());
        x += 70;
        this.frameAll = headerButton("框选全部", x, 84, () -> this.frameAll());
        x += 88;
        this.models = headerButton("模型库", x, 72, () -> this.openModelLibrary());
        x += 76;
        this.fallback = headerButton("原版回放编辑", x, 108, () -> this.openOriginalEditor());

        this.status = new UILabel(IKey.constant(""));
        this.status.color(0xFF93A0B3, false);
        this.status.labelAnchor(1F, 0.5F);
        this.status.relative(this.header).x(1F, -560).y(0).w(548).h(HEADER);

        this.header.add(this.title, this.status);

        this.add(this.header, this.main);

        this.onAppear(this::bindFromDashboard);
        this.onDisappear(() ->
        {
            if (this.session != null)
            {
                this.session.setPlaying(false);
            }

            /* Leaving the page hands the film panel back before the dashboard reparents it. */
            this.detachBorrowedFilmPanel();
        });
    }

    private UIButton headerButton(String label, int x, int width, Runnable action)
    {
        UIButton button = new UIButton(IKey.constant(label), (b) -> action.run());

        button.relative(this.header).x(x).y(3).w(width).h(HEADER - 6);
        this.header.add(button);

        return button;
    }

    /* ------------------------------------------------------------ binding */

    /**
     * Attaches the panel to the film the dashboard's film editor currently has open.
     *
     * <p>This runs on every {@code onAppear}, not only the first one: the dashboard has a single
     * film panel whose data can change between two visits (open film A, leave, open film B, come
     * back to the replay page), and the panel must follow it instead of showing film A forever.
     * It is also the fallback path when the user opens the panel from the dashboard bar rather
     * than through the compositor button.</p>
     */
    public void bindFromDashboard()
    {
        if (this.dashboard == null)
        {
            return;
        }

        UIFilmPanel panel = this.dashboard.getPanel(UIFilmPanel.class);

        if (panel == null)
        {
            return;
        }

        Film current = panel.getData();
        Replay currentReplay = panel.replayEditor == null ? null : panel.replayEditor.getReplay();

        if (current == null)
        {
            /* No film: keep whatever was bound last, but make the header/status say how to get
             * one. bind() is not called so a still-open film's data is never replaced by null. */
            return;
        }

        if (this.built && current == this.film)
        {
            this.attachBorrowedFilmPanel();

            if (currentReplay != null && currentReplay != this.replay)
            {
                this.selectReplay(currentReplay);
            }

            return;
        }

        this.bind(panel, current, currentReplay);
    }

    /**
     * Binds the editor to a film. Safe to call again for a different film/replay; the layout and
     * the original sub-panels are built once and reused.
     */
    public void bind(UIFilmPanel panel, Film film, Replay replay)
    {
        if (panel == null || film == null)
        {
            return;
        }

        boolean filmChanged = this.film != film;

        this.filmPanel = panel;
        this.film = film;

        /* Attach it before anything below can write into the film: BBS' undo data is collected
         * through UIElement.getRoot() at the moment of the edit (see attachBorrowedFilmPanel). */
        this.attachBorrowedFilmPanel();

        if (filmChanged)
        {
            this.selectedPart = "";
        }

        if (!this.built)
        {
            this.build(panel);
        }

        if (this.session == null || filmChanged)
        {
            this.session = new ReplayEditSession(panel, film, replay);
            this.viewport.setSession(this.session);
        }

        this.session.ensureEntities();
        this.session.resetFraming();
        this.replaysList.replays.refreshReplayList();

        /* A replay handed in by the film editor may belong to the previous film (the original
         * replay editor keeps its selection when a new film is filled). Only trust it when it is
         * in this film by identity. */
        Replay initial = ReplayListOps.containsIdentical(film.replays.getList(), replay)
            ? replay
            : this.firstReplay(film);

        this.selectReplay(initial);

        this.knownOrder.clear();
        this.knownOrder.addAll(film.replays.getList());
    }

    /**
     * Keeps the borrowed {@link UIFilmPanel} connected to the UI tree (hidden), without putting it
     * inside this page.
     *
     * <p>BBS' undo handler collects the "before" UI snapshot through {@code UIElement.getRoot()} at
     * the moment of the first value edit and applies it again on undo. A detached film panel has no
     * root, so an edit made here wrote an undo entry whose UI snapshot was null; Ctrl+Z then threw
     * {@code NullPointerException: ... MapType.has ... because "data" is null}.</p>
     *
     * <p><b>Why a root-level holder and not a child of this page:</b> the first attempt attached
     * the film panel to the replay page itself, which made the dashboard render the film panel's
     * world pass and drop the page UI on the real machine (Run C screenshots were world + gizmo
     * only). Attaching it to an invisible holder under the screen root keeps {@code getRoot()}
     * non-null for the undo data without touching the page's children or layout: a hidden sibling
     * is skipped by the root's render/input passes, and the page subtree stays exactly as it was.</p>
     *
     * <p>Called from {@link #update()}, i.e. once per client tick before rendering, so the holder
     * is added while the root is not iterating its children (adding during render would throw a
     * {@code ConcurrentModificationException}).</p>
     */
    private void attachBorrowedFilmPanel()
    {
        UIFilmPanel panel = this.filmPanel;

        if (panel == null || panel.getRoot() != null)
        {
            return;
        }

        if (this.borrowedFilmPanelHolder != null && this.borrowedFilmPanelHolder.getParent() != null)
        {
            return;
        }

        UIElement root = this.getRoot();

        if (root == null)
        {
            /* Not on screen yet: retried from update() on the frame the page appears. */
            return;
        }

        if (panel.getParent() != null)
        {
            /* Someone else owns it (it is the dashboard's current panel): it already has a root. */
            return;
        }

        this.borrowedFilmPanelVisible = panel.isVisible();

        panel.setVisible(false);

        if (this.borrowedFilmPanelHolder == null)
        {
            this.borrowedFilmPanelHolder = new UIElement();
            this.borrowedFilmPanelHolder.setVisible(false);
            this.borrowedFilmPanelHolder.relative(root).x(0).y(0).w(1F).h(1F);
            this.borrowedFilmPanelHolder.add(panel);
        }

        root.add(this.borrowedFilmPanelHolder);
    }

    /** Undoes {@link #attachBorrowedFilmPanel()}, restoring the visibility it had. */
    private void detachBorrowedFilmPanel()
    {
        UIFilmPanel panel = this.filmPanel;

        if (panel != null && this.borrowedFilmPanelHolder != null
            && panel.getParent() == this.borrowedFilmPanelHolder)
        {
            panel.removeFromParent();
            panel.setVisible(this.borrowedFilmPanelVisible);
        }

        if (this.borrowedFilmPanelHolder != null)
        {
            this.borrowedFilmPanelHolder.removeFromParent();
            this.borrowedFilmPanelHolder = null;
        }
    }

    private void build(UIFilmPanel panel)
    {
        this.built = true;

        this.session = new ReplayEditSession(panel, this.film, this.replay);

        this.replaysList = new UIReplaysListPanel(
            panel,
            (selected) ->
            {
                if (!selected.isEmpty())
                {
                    this.selectReplayFromList(selected.get(0));
                }
            },
            (form) ->
            {
                if (this.replayProperties != null)
                {
                    this.replayProperties.getFormConsumer().accept(form);
                }

                this.afterFormChanged();
            },
            (part) ->
            {
                this.selectedPart = part == null ? "" : part;
                this.tracksDirty = true;
                this.primed = false;
                this.rebuildKeyframeEditor();
            }
        );

        this.replayProperties = new UIReplayPropertiesPanel(panel);
        this.replayProperties.attachReplayList(this.replaysList.replays);

        this.viewport = new UIReplayViewport(this.session);
        this.viewport.setPickHandler(this::selectReplayFromIndex);

        this.replaysList.relative(this.main).x(0).y(0).w(LIST_WIDTH).h(1F);
        this.viewport.relative(this.main).x(LIST_WIDTH).y(0).w(1F, -LIST_WIDTH - PROPS_WIDTH).h(1F);
        this.replayProperties.relative(this.main).x(1F, -PROPS_WIDTH).y(0).w(PROPS_WIDTH).h(1F);

        this.main.add(this.replaysList, this.viewport, this.replayProperties);

        this.rebuildKeyframeEditor();
    }

    public boolean isBound()
    {
        return this.filmPanel != null;
    }

    public ReplayEditSession session()
    {
        return this.session;
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

    /* ------------------------------------------------------------ selection */

    private Replay firstReplay(Film film)
    {
        List<Replay> replays = film.replays.getList();
        Replay enabledFallback = null;

        for (Replay candidate : replays)
        {
            if (candidate == null || !Boolean.TRUE.equals(candidate.enabled.get()))
            {
                continue;
            }

            if (enabledFallback == null)
            {
                enabledFallback = candidate;
            }

            /* Prefer a replay that can actually be seen: films contain placement-only replays
             * (no form) too, and selecting one on open showed a gizmo with no model under it in
             * the first real-machine screenshot. */
            if (this.session != null && this.session.hasModel(candidate))
            {
                return candidate;
            }
        }

        if (enabledFallback != null)
        {
            return enabledFallback;
        }

        return replays.isEmpty() ? null : replays.get(0);
    }

    private void selectReplayFromIndex(int index)
    {
        List<Replay> replays = this.film == null ? List.of() : this.film.replays.getList();
        Replay picked = ReplayListOps.elementAt(replays, index);

        if (picked != null)
        {
            this.selectReplay(picked);
        }
    }

    /**
     * Selection coming from the original replay list's own callback.
     *
     * <p>No {@code scrollToReplay} here: BBS' {@code UIReplayList.scrollToReplay()} re-picks the
     * row, which fires this very callback again. Scrolling from the callback is the infinite
     * recursion that crashed the first real-machine run (StackOverflowError: scrollToReplay →
     * callback → selectReplay → scrollToReplay…). The list already has the row in view when the
     * user picked it, so scrolling is only needed on programmatic/3D-pick selections.</p>
     */
    private void selectReplayFromList(Replay replay)
    {
        this.applySelection(replay, false);
    }

    /**
     * Selects a replay from outside the list (dashboard bind, 3D pick, debug hook) and keeps the
     * list scrolled to it.
     */
    public void selectReplay(Replay replay)
    {
        this.applySelection(replay, true);
    }

    private void applySelection(Replay replay, boolean scrollList)
    {
        /* Belt for every path: any callback fired below (list pick, body-part forms, property
         * editors) that comes back here must not re-enter the selection code. */
        if (this.selectingReplay)
        {
            return;
        }

        this.selectingReplay = true;

        try
        {
            /* A replay object can be stale: an undo restores the film's ValueGroup children as new
             * objects, and a film switch hands in the previous film's selection. A foreign object
             * must not become the selected replay — every gizmo/auto-key write would land on a
             * detached record that is no longer part of the film. */
            Replay effective = replay;

            if (effective != null && this.film != null
                && !ReplayListOps.containsIdentical(this.film.replays.getList(), effective))
            {
                effective = null;
            }

            this.replay = effective;

            if (this.session != null)
            {
                this.session.setReplay(effective);
            }

            if (this.replayProperties != null)
            {
                this.replayProperties.setReplay(effective);
            }

            if (this.replaysList != null && effective != null)
            {
                this.selectedPart = this.replaysList.setBodyPartsReplay(effective, this.selectedPart);

                if (scrollList)
                {
                    this.replaysList.replays.scrollToReplay(effective);
                }
            }
            else if (this.replaysList != null)
            {
                this.replaysList.setBodyPartsReplay(null, "");
            }
        }
        finally
        {
            this.selectingReplay = false;
        }

        this.tracksDirty = true;

        if (this.viewport != null)
        {
            this.viewport.setStatus(this.replay == null ? "未选择回放" : "已选择 " + this.replay.getName());
        }
    }

    /* ------------------------------------------------------------ timeline */

    private void rebuildKeyframeEditor()
    {
        if (this.keyframeEditor != null)
        {
            this.keyframeEditor.removeFromParent();
            this.keyframeEditor = null;
        }

        if (this.filmPanel == null || this.film == null)
        {
            return;
        }

        UIKeyframeEditor editor = new UIKeyframeEditor(
            (callback) -> new UIFilmKeyframes(this.filmPanel.cameraEditor, callback).absolute()
        );

        editor.target(this.replayProperties);
        /* Unique undo id: the borrowed film panel's own replay editor already registers
         * "replay_keyframe_editor" for its hidden keyframe editor. Sharing the id would let the
         * hidden editor's snapshot overwrite this one's selection on every undo. */
        editor.setUndoId("replay_mode_keyframe_editor");
        editor.view.rulerRenderer((context) -> UIReplaysEditor.renderRuler(
            context, editor.view, this.filmPanel.cameraEditor, this.film.camera, 0));
        editor.view.duration(() -> this.film.camera.calculateDuration());
        editor.view.getDopeSheet().setEmptyState(
            IKey.constant("没有轨道"),
            IKey.constant("选择一条回放后，这里显示它的关键帧轨道。")
        );

        editor.relative(this).x(0).y(1F, -TIMELINE).w(1F).h(TIMELINE);

        this.keyframeEditor = editor;
        this.tracksDirty = true;
        this.primed = false;

        this.add(editor);
        this.invalidateLayout();
    }

    private void ensureTracks()
    {
        if (!this.tracksDirty || this.keyframeEditor == null || this.replay == null)
        {
            return;
        }

        /* The dope sheet caches its rows when a sheet is added; at 0x0 the cache is empty and the
         * timeline looks broken until something forces a relayout. So the sheets are added on the
         * first frame the control has a real size — the same priming the compositor's timeline
         * does. */
        if (this.keyframeEditor.area.w <= 8 || this.keyframeEditor.area.h <= 8)
        {
            return;
        }

        this.keyframeEditor.view.removeAllSheets();

        for (UIKeyframeSheet sheet : ReplayTrackBuilder.build(this.replay, this.selectedPart))
        {
            this.keyframeEditor.view.addSheet(sheet);
        }

        this.keyframeEditor.resize();
        this.keyframeEditor.view.resize();

        if (!this.primed)
        {
            this.keyframeEditor.view.resetView();
            this.primed = true;
        }

        this.tracksDirty = false;
    }

    /* ------------------------------------------------------------ frame */

    @Override
    public void update()
    {
        /* Keep the borrowed film panel rooted for BBS' undo data collection. The dashboard calls
         * update() on the active panel once per client tick, before rendering — the only safe
         * moment to add a root child (adding during render would mutate the root's children while
         * it iterates them). */
        this.attachBorrowedFilmPanel();

        super.update();

        if (this.session != null)
        {
            this.session.advancePlayback();
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.checkReplayListChanged();
        this.ensureTracks();
        this.applyDebugSetup();
        this.refreshStatus();

        super.render(context);
    }

    /**
     * Detects any replay-list mutation (add/remove/reorder) and keeps the selection coherent.
     *
     * <p>Identity comparison, not {@code List.equals}: a removed replay must be detected even when
     * an equal-valued replay took its place, and a reorder must not look like "nothing changed".
     * When the selected replay is gone, {@link ReplayListOps#selectionAfterChange} lands on the
     * nearest index instead of jumping to the first replay.</p>
     */
    private void checkReplayListChanged()
    {
        if (this.film == null)
        {
            return;
        }

        List<Replay> current = this.film.replays.getList();

        if (ReplayListOps.sameOrder(this.knownOrder, current))
        {
            return;
        }

        Replay resolved = this.replay == null
            ? this.firstReplay(this.film)
            : ReplayListOps.selectionAfterChange(this.knownOrder, current, this.replay);

        this.knownOrder.clear();
        this.knownOrder.addAll(current);

        if (resolved != this.replay)
        {
            this.selectReplay(resolved);
        }

        if (this.session != null)
        {
            this.session.recreateEntities();
        }

        this.tracksDirty = true;
    }

    private void refreshStatus()
    {
        String text = this.statusText();

        if (this.cachedStatus.equals(text))
        {
            if (this.session != null && this.session.isPlaying() != this.lastPlaying)
            {
                this.lastPlaying = this.session.isPlaying();
                this.play.label = IKey.constant(this.lastPlaying ? "暂停" : "播放");
            }

            return;
        }

        this.cachedStatus = text;
        this.status.label = IKey.constant(text);

        if (this.session != null)
        {
            this.lastPlaying = this.session.isPlaying();
            this.play.label = IKey.constant(this.lastPlaying ? "暂停" : "播放");
        }
    }

    /** Readable state for the header; also useful in a crash report. */
    public String statusText()
    {
        if (this.filmPanel == null || this.film == null)
        {
            return "没有打开的影片：先在影片编辑器打开一个影片，或从合成器点「实体回放」"
                + "（调试可用 -Dbbsae.debugReplayFilm=<影片 id>）";
        }

        int count = this.film.replays.getList().size();
        int index = ReplayListOps.indexOfIdentical(this.film.replays.getList(), this.replay);
        int tick = this.session == null ? 0 : this.session.tick();
        int duration = this.session == null ? 0 : this.session.durationTicks();
        String base = "影片 " + this.film.getId()
            + " · 回放 " + (index < 0 ? "-" : String.valueOf(index + 1)) + "/" + count
            + " · tick " + tick + "/" + duration
            + (this.session != null && this.session.isPlaying() ? " · 播放中" : "");

        if (count == 0)
        {
            return base + " · 没有回放：点「原版回放编辑」在 BBS 原版编辑器里添加回放";
        }

        if (this.replay == null)
        {
            return base + " · 未选择回放";
        }

        if (this.session != null && !this.session.hasModel(this.replay))
        {
            return base + " · 该回放没有模型：在左侧选一条有模型的回放，或点「模型库」选择一个 BBS 模型";
        }

        return base;
    }

    /* ------------------------------------------------------------ debug hooks */

    /**
     * Verification setup used by {@code ReplayDebugHook}: pick a replay, seek the playhead and
     * (optionally) place the free camera, once the panel has been laid out.
     *
     * <p>Kept on the panel rather than the debug hook so the hook only has to find a film; the
     * camera can only be applied after the viewport framed the scene on its first real frame, so
     * the request is parked here and applied from {@link #render(UIContext)}.</p>
     */
    public void debugPrepare(int replayIndex, int tick, float yaw, float pitch, float distance)
    {
        /* A negative index means "pick a replay that actually has a model": the first real-machine
         * screenshot selected a placement-only replay and showed a gizmo floating above nothing. */
        this.debugReplayIndex = replayIndex >= 0 ? replayIndex : this.firstPlayableReplayIndex();
        this.debugTick = tick;
        this.debugTickPending = true;
        this.debugCameraPending = true;
        this.debugYaw = yaw;
        this.debugPitch = pitch;
        this.debugDistance = distance;

        if (this.built)
        {
            this.selectReplayFromIndex(this.debugReplayIndex);
        }

        BbsAeAddon.LOGGER.info("Replay debug: booked select={} tick={} yaw={} pitch={} distance={}",
            this.debugReplayIndex, tick, yaw, pitch, distance);
    }

    /** List index of the first replay with a form, or 0 when the film has none. */
    public int firstPlayableReplayIndex()
    {
        if (this.film != null)
        {
            List<Replay> replays = this.film.replays.getList();

            for (int i = 0; i < replays.size(); i++)
            {
                if (this.session != null && this.session.hasModel(replays.get(i)))
                {
                    return i;
                }
            }
        }

        return 0;
    }

    private void applyDebugSetup()
    {
        if (this.session == null || !this.built)
        {
            return;
        }

        if (this.debugTickPending)
        {
            this.session.setPlaying(false);
            this.session.seek(this.debugTick);
            this.debugTickPending = false;
            BbsAeAddon.LOGGER.info("Replay debug: playhead tick {}", this.session.tick());
        }

        if (!this.debugCameraPending || !this.session.isFramed())
        {
            return;
        }

        this.debugCameraPending = false;
        this.selectReplayFromIndex(this.debugReplayIndex);

        dev.bbsae.client.render.CompViewportCamera camera = this.session.camera();

        if (!Float.isNaN(this.debugYaw) && !Float.isNaN(this.debugPitch) && !Float.isNaN(this.debugDistance))
        {
            camera.setPose(this.debugYaw, this.debugPitch, this.debugDistance);
        }
        else if (!Float.isNaN(this.debugYaw) && !Float.isNaN(this.debugPitch))
        {
            camera.setPose(this.debugYaw, this.debugPitch, camera.distance);
        }

        camera.update();

        BbsAeAddon.LOGGER.info("Replay debug ready: film={} replays={} placeable={} selected={} tick={} yaw={} pitch={} distance={}",
            this.film == null ? "-" : this.film.getId(),
            this.session.replayCount(),
            this.session.placeableCount(),
            this.session.replayIndex(this.replay),
            this.session.tick(),
            camera.yaw, camera.pitch, camera.distance);
    }

    public UIReplayViewport viewport()
    {
        return this.viewport;
    }

    /* ------------------------------------------------------------ scripted-run hooks */

    /**
     * Verification hooks used by {@link ReplayInteractionScript}. They are public only so the
     * script can drive the same private paths a user's actions go through (selection, form swap,
     * list duplicate/remove) without duplicating that logic — the script must exercise production
     * code or its screenshots mean nothing.
     */

    public int debugReplayCount()
    {
        return this.film == null ? 0 : this.film.replays.getList().size();
    }

    public int debugSelectPlayableReplay()
    {
        int index = this.firstPlayableReplayIndex();

        this.debugSelectIndex(index);

        return index;
    }

    public void debugSelectIndex(int index)
    {
        this.selectReplayFromIndex(index);
    }

    /**
     * Re-binds the panel after an external film-data edit (undo/redo rebuilds the film's value
     * objects, so the selected {@code Replay} instance and the actor entities are stale).
     */
    public void debugAfterExternalEdit(int selectedIndex)
    {
        if (this.film != null)
        {
            this.knownOrder.clear();
            this.knownOrder.addAll(this.film.replays.getList());
        }

        if (this.session != null)
        {
            this.session.recreateEntities();
            this.session.applyAllPoses();
        }

        this.debugSelectIndex(selectedIndex);
        this.rebuildKeyframeEditor();
        this.tracksDirty = true;
    }

    /** Undo through BBS's original film history (Ctrl+Z in the replay editor). */
    public boolean undoReplay()
    {
        return this.runFilmHistory(false);
    }

    /** Redo through BBS's original film history (Ctrl+Shift+Z / Ctrl+Y). */
    public boolean redoReplay()
    {
        return this.runFilmHistory(true);
    }

    /**
     * Runs BBS's film undo/redo history while the borrowed {@link UIFilmPanel} is temporarily
     * attached to this page.
     *
     * <p>In replay mode the dashboard's film panel is deliberately not the active screen — only its
     * film data, controller and editor children are reused. BBS applies per-element undo data via
     * {@code UIElement.getRoot().applyAllUndoData(...)}, and {@code getRoot()} returns null for a
     * detached panel, so Ctrl+Z/Ctrl+Y threw a NullPointerException (the real-machine interaction
     * script caught it: "Cannot invoke UIRootElement.applyAllUndoData because getRoot() is null").
     * Attaching it for the duration of the synchronous call gives it a root; it is detached again
     * before any render happens, so there is no flicker and add/remove does not run panel
     * lifecycle callbacks.</p>
     */
    private boolean runFilmHistory(boolean redo)
    {
        UIFilmPanel panel = this.filmPanel;

        if (panel == null || panel.getData() == null)
        {
            return false;
        }

        int selected = 0;

        if (this.film != null)
        {
            int index = ReplayListOps.indexOfIdentical(this.film.replays.getList(), this.replay);

            if (index >= 0)
            {
                selected = index;
            }
        }

        boolean attached = panel.getRoot() != null;
        boolean wasVisible = panel.isVisible();

        if (!attached)
        {
            panel.setVisible(false);
            panel.relative(this).x(0).y(0).w(1F).h(1F);
            this.add(panel);
        }

        try
        {
            if (redo)
            {
                panel.redo();
            }
            else
            {
                panel.undo();
            }
        }
        finally
        {
            if (!attached)
            {
                panel.removeFromParent();
                panel.setVisible(wasVisible);
            }
        }

        this.debugAfterExternalEdit(selected);

        return true;
    }

    /** Duplicate through the original list action; returns the new replay, or null. */
    public Replay debugDuplicateReplay()
    {
        if (this.replaysList == null || this.film == null)
        {
            return null;
        }

        int before = this.film.replays.getList().size();

        try
        {
            this.replaysList.replays.dupeReplay();
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay script: list duplicate threw", e);

            return null;
        }

        if (this.film.replays.getList().size() <= before)
        {
            return null;
        }

        int selectedIndex = ReplayListOps.indexOfIdentical(this.film.replays.getList(), this.replay);

        this.debugAfterExternalEdit(selectedIndex < 0 ? 0 : selectedIndex);
        this.knownOrder.clear();
        this.knownOrder.addAll(this.film.replays.getList());

        return this.film.replays.getList().get(this.film.replays.getList().size() - 1);
    }

    /** Removes a replay added by the script (data-level; no confirmation overlay). */
    public void debugRemoveReplay(Replay replay)
    {
        if (this.film == null || replay == null)
        {
            return;
        }

        try
        {
            this.film.replays.remove(replay);
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay script: list remove threw", e);

            return;
        }

        this.debugAfterExternalEdit(0);
        this.knownOrder.clear();
        this.knownOrder.addAll(this.film.replays.getList());
    }

    /** Replaces the selected replay's form through the palette callback's copy path. */
    public boolean debugApplyForm(Form picked)
    {
        if (this.replay == null || picked == null)
        {
            return false;
        }

        ReplayFormSwap.apply(picked, FormUtils::copy, this.replay.form::set);
        this.afterFormChanged();

        return true;
    }

    /** The channel values at the playhead, for the script's log (x/y/z/yaw/headYaw/bodyYaw/pitch). */
    public String debugChannelSummary()
    {
        if (this.replay == null || this.session == null)
        {
            return "-";
        }

        float tick = this.session.keyframeTick();
        IEntity entity = this.session.entity(this.replay);
        String pose = entity == null ? "-"
            : String.format(java.util.Locale.ROOT, "pos=(%.2f,%.2f,%.2f) pitch=%.2f yaw=%.2f",
                entity.getX(), entity.getY(), entity.getZ(), entity.getPitch(), entity.getYaw());

        return String.format(java.util.Locale.ROOT,
            "tick=%.2f x=%.2f y=%.2f z=%.2f yaw=%.2f headYaw=%.2f bodyYaw=%.2f pitch=%.2f %s",
            tick,
            channel(this.replay.keyframes.x.interpolate(tick)),
            channel(this.replay.keyframes.y.interpolate(tick)),
            channel(this.replay.keyframes.z.interpolate(tick)),
            channel(this.replay.keyframes.yaw.interpolate(tick)),
            channel(this.replay.keyframes.headYaw.interpolate(tick)),
            channel(this.replay.keyframes.bodyYaw.interpolate(tick)),
            channel(this.replay.keyframes.pitch.interpolate(tick)),
            pose
        );
    }

    private static double channel(Double value)
    {
        return value == null ? Double.NaN : value;
    }

    /* ------------------------------------------------------------ transport */

    private void togglePlayback()
    {
        if (this.session == null)
        {
            return;
        }

        this.session.togglePlaying();
        this.lastPlaying = this.session.isPlaying();
        this.play.label = IKey.constant(this.lastPlaying ? "暂停" : "播放");
    }

    private void step(int ticks)
    {
        if (this.session == null)
        {
            return;
        }

        this.session.setPlaying(false);
        this.session.step(ticks);
    }

    private void frameAll()
    {
        if (this.session == null)
        {
            return;
        }

        this.session.frameAll();

        if (this.viewport != null)
        {
            this.viewport.setStatus("已框选全部模型");
        }
    }

    /* ------------------------------------------------------------ forms */

    private void openModelLibrary()
    {
        if (this.replay == null)
        {
            if (this.viewport != null)
            {
                this.viewport.setStatus("先选择一条回放");
            }

            return;
        }

        UIFormPalette palette = UIFormPalette.open(this, false, this.replay.form.get(), (form) ->
        {
            if (form == null || this.replay == null)
            {
                return;
            }

            ReplayFormSwap.apply(form, FormUtils::copy, this.replay.form::set);
            this.afterFormChanged();
        });

        if (palette != null)
        {
            palette.updatable();

            if (this.viewport != null)
            {
                this.viewport.setStatus("从 BBS 模型库选择模型（Esc 返回）");
            }
        }
        else if (this.viewport != null)
        {
            this.viewport.setStatus("模型库已经打开");
        }
    }

    /**
     * Rebuilds the live actor after its form changed and keys the placement that is on screen, so
     * "pick a model in the library" behaves like importing a model into the composition: it is
     * immediately visible, selectable and keyed at the playhead.
     */
    private void afterFormChanged()
    {
        if (this.filmPanel == null || this.replay == null)
        {
            return;
        }

        try
        {
            this.filmPanel.getController().createEntities();
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay mode could not rebuild the film's entities after a form change", e);
        }

        if (this.session != null)
        {
            this.session.recreateEntities();
            this.session.applyAllPoses();
            this.autoKeyPlacement();
        }

        this.rebuildKeyframeEditor();

        if (this.viewport != null)
        {
            this.viewport.setStatus("模型已应用");
        }
    }

    /** Writes the current position/rotation of the selected replay at the playhead. */
    private void autoKeyPlacement()
    {
        Replay target = this.replay;

        if (target == null || this.session == null)
        {
            return;
        }

        IEntity entity = this.session.entity(target);

        if (entity == null)
        {
            return;
        }

        this.session.applyPose(entity, target);

        float tick = this.session.keyframeTick();
        double yaw = entity.getYaw();

        ReplayWriteback.placement(
            tick, entity.getX(), entity.getY(), entity.getZ(),
            entity.getPitch(), yaw,
            entity.getHeadYaw() - yaw, entity.getBodyYaw() - yaw,
            (t, v) -> target.keyframes.x.insert(t, v),
            (t, v) -> target.keyframes.y.insert(t, v),
            (t, v) -> target.keyframes.z.insert(t, v),
            (t, v) -> target.keyframes.yaw.insert(t, v),
            (t, v) -> target.keyframes.headYaw.insert(t, v),
            (t, v) -> target.keyframes.bodyYaw.insert(t, v),
            (t, v) -> target.keyframes.pitch.insert(t, v)
        );

        this.tracksDirty = true;
        this.primed = false;
    }

    /* ------------------------------------------------------------ fallback & undo */

    /**
     * Route A: hand the user straight back to BBS' own replay editor in the original film panel.
     * This is the guaranteed-100% fallback when any part of the new 3D editing is insufficient.
     */
    public void openOriginalEditor()
    {
        if (this.filmPanel == null)
        {
            return;
        }

        if (this.session != null)
        {
            this.session.setPlaying(false);
        }

        this.dashboard.setPanel(this.filmPanel);
        this.filmPanel.showPanel(this.filmPanel.replayEditor);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (this.session != null && context.getKeyAction() == KeyAction.PRESSED)
        {
            int key = context.getKeyCode();

            if (Window.isCtrlPressed())
            {
                if (key == GLFW.GLFW_KEY_Z && this.filmPanel != null)
                {
                    /* Undo goes through the original panel's history. Assigning film.preCallback
                     * would evict BBS' own UIFilmUndoHandler and break the undo chain (plan R6).
                     * The helper attaches the borrowed panel for the synchronous call: its
                     * getRoot() is null while it is not the active dashboard panel and BBS undo
                     * applies UI data through the root. */
                    if (Window.isShiftPressed())
                    {
                        this.redoReplay();
                    }
                    else
                    {
                        this.undoReplay();
                    }

                    return true;
                }

                if (key == GLFW.GLFW_KEY_Y && this.filmPanel != null)
                {
                    this.redoReplay();

                    return true;
                }
            }

            if (key == GLFW.GLFW_KEY_SPACE)
            {
                this.togglePlayback();

                return true;
            }

            if (key == GLFW.GLFW_KEY_LEFT)
            {
                this.step(-1);

                return true;
            }

            if (key == GLFW.GLFW_KEY_RIGHT)
            {
                this.step(1);

                return true;
            }
        }

        return super.subKeyPressed(context);
    }
}
