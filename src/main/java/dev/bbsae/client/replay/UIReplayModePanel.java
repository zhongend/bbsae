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
    private String selectedPart = "";
    private String cachedStatus = "";
    private int knownReplayCount = -1;

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

    /** Attaches the panel to the film the dashboard's film editor currently has open. */
    public void bindFromDashboard()
    {
        if (this.built || this.dashboard == null)
        {
            return;
        }

        UIFilmPanel panel = this.dashboard.getPanel(UIFilmPanel.class);

        if (panel == null || panel.getData() == null)
        {
            return;
        }

        this.bind(panel, panel.getData(), panel.replayEditor == null ? null : panel.replayEditor.getReplay());
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
        this.selectReplay(replay != null ? replay : this.firstReplay(film));
        this.knownReplayCount = film.replays.getList().size();
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
                    this.selectReplay(selected.get(0));
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

        for (Replay candidate : replays)
        {
            if (candidate != null && Boolean.TRUE.equals(candidate.enabled.get()))
            {
                return candidate;
            }
        }

        return replays.isEmpty() ? null : replays.get(0);
    }

    private void selectReplayFromIndex(int index)
    {
        List<Replay> replays = this.film == null ? List.of() : this.film.replays.getList();

        if (index >= 0 && index < replays.size())
        {
            this.selectReplay(replays.get(index));
        }
    }

    public void selectReplay(Replay replay)
    {
        this.replay = replay;

        if (this.session != null)
        {
            this.session.setReplay(replay);
        }

        if (this.replayProperties != null)
        {
            this.replayProperties.setReplay(replay);
        }

        if (this.replaysList != null && replay != null)
        {
            this.selectedPart = this.replaysList.setBodyPartsReplay(replay, this.selectedPart);
            this.replaysList.replays.scrollToReplay(replay);
        }
        else if (this.replaysList != null)
        {
            this.replaysList.setBodyPartsReplay(null, "");
        }

        this.tracksDirty = true;

        if (this.viewport != null)
        {
            this.viewport.setStatus(replay == null ? "未选择回放" : "已选择 " + replay.getName());
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
        editor.setUndoId("replay_keyframe_editor");
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
        this.refreshStatus();

        super.render(context);
    }

    private void checkReplayListChanged()
    {
        if (this.film == null)
        {
            return;
        }

        int count = this.film.replays.getList().size();

        if (count != this.knownReplayCount)
        {
            this.knownReplayCount = count;

            if (this.replay == null || !this.film.replays.getList().contains(this.replay))
            {
                this.selectReplay(this.firstReplay(this.film));
            }

            if (this.session != null)
            {
                this.session.recreateEntities();
            }

            this.tracksDirty = true;
        }
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
            return "没有打开的影片：先在影片编辑器打开一个影片，或从合成器点「实体回放」";
        }

        int count = this.film.replays.getList().size();
        int index = this.replay == null ? -1 : this.film.replays.getList().indexOf(this.replay);
        int tick = this.session == null ? 0 : this.session.tick();
        int duration = this.session == null ? 0 : this.session.durationTicks();

        return "影片 " + this.film.getId()
            + " · 回放 " + (index < 0 ? "-" : String.valueOf(index + 1)) + "/" + count
            + " · tick " + tick + "/" + duration
            + (this.session != null && this.session.isPlaying() ? " · 播放中" : "");
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

        ReplayWriteback.position(
            tick, entity.getX(), entity.getY(), entity.getZ(),
            (t, v) -> target.keyframes.x.insert(t, v),
            (t, v) -> target.keyframes.y.insert(t, v),
            (t, v) -> target.keyframes.z.insert(t, v)
        );

        ReplayWriteback.rotation(
            tick, entity.getPitch(), yaw,
            entity.getHeadYaw() - yaw, entity.getBodyYaw() - yaw,
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
                     * would evict BBS' own UIFilmUndoHandler and break the undo chain (plan R6). */
                    if (Window.isShiftPressed())
                    {
                        this.filmPanel.redo();
                    }
                    else
                    {
                        this.filmPanel.undo();
                    }

                    return true;
                }

                if (key == GLFW.GLFW_KEY_Y && this.filmPanel != null)
                {
                    this.filmPanel.redo();

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
