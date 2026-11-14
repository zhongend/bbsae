package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.ui.CompPrefs;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
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
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.film.replays.UIReplaysListPanel;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IFocusedUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.utils.Direction;
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
    /** Drag strip thickness between the replay page's columns/rows. */
    private static final int SPLIT = 6;
    private static final int MIN_LIST_WIDTH = 120;
    private static final int MIN_PROPS_WIDTH = 160;
    private static final int MIN_TIMELINE = 96;
    private static final int MIN_VIEWPORT = 220;

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
    /** Resizable column widths and keyframe-strip height (persisted in CompPrefs). */
    private int listWidth = LIST_WIDTH;
    private int propsWidth = PROPS_WIDTH;
    private int timelineOverride;
    private mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable listSplitter;
    private mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable propsSplitter;
    private mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable timelineSplitter;

    private boolean built;
    private boolean tracksDirty;
    private boolean primed;
    /** Height of the bottom keyframe strip, recomputed from the page height. */
    private int timelineHeight = TIMELINE;
    /** Original replay-editor category filter; null is the "all tracks" view. */
    private TrackCategory categoryFilter;
    private UIElement categoryBar;
    /** Logs the first render after an undo/redo to prove whether the page still draws. */
    private boolean logNextRender;
    private boolean lastPlaying;
    private boolean previousEditorRewind = true;
    private boolean previousRestartOnSeek;
    private boolean spaceWasDown;
    /** Re-entrancy guard for {@link #applySelection(Replay, boolean)}; see the method's comment. */
    private boolean selectingReplay;
    /** The replay list as it looked last frame, for identity-preserving selection across edits. */
    private final java.util.List<Replay> knownOrder = new java.util.ArrayList<>();
    private String selectedPart = "";
    /** Part pick waiting for the keyframe editor to finish relayout before it selects a track. */
    private Form pendingPartForm;
    private String pendingPartBone;
    /**
     * The bone the keyframe editor reported on the previous frame.
     *
     * <p>The editor is the authority while the user clicks tracks, but it must not overwrite a bone
     * that just came from a viewport pick: BBS' pose editor can keep its previous current bone for
     * a frame while the picked sheet is being rebuilt, and blindly copying {@code getBone()} back
     * every frame turned a click on the head into "anchor" (the demo's default bone). Only a real
     * change in the editor selection is mirrored into the session.</p>
     */
    private String lastEditorBone = "";
    /** Cursor adapter: BBS' pick logic seeks through this, and it must move our playhead. */
    private final mchorse.bbs_mod.ui.film.ICursor replayCursor = new mchorse.bbs_mod.ui.film.ICursor()
    {
        @Override
        public int getCursor()
        {
            return UIReplayModePanel.this.session == null ? 0 : UIReplayModePanel.this.session.tick();
        }

        @Override
        public void setCursor(int tick)
        {
            if (UIReplayModePanel.this.session != null)
            {
                UIReplayModePanel.this.session.setPlaying(false);
                UIReplayModePanel.this.session.seek(tick);
            }
        }

        @Override
        public boolean isRunning()
        {
            return UIReplayModePanel.this.session != null && UIReplayModePanel.this.session.isPlaying();
        }

        @Override
        public float getCursor(float transition)
        {
            return UIReplayModePanel.this.session == null ? 0F : UIReplayModePanel.this.session.playhead();
        }
    };
    private String cachedStatus = "";
    /** The virtual-only list filter is rebuilt only when the film/replay list changed. */
    private boolean virtualFilterDirty = true;
    /** Visibility the borrowed film panel had before being attached hidden; restored on detach. */
    private boolean borrowedFilmPanelVisible = true;
    /** Invisible holder that keeps the borrowed film panel connected (see attach). */
    private UIElement borrowedFilmPanelHolder;
    /** Private UIRootElement for the borrowed film panel (never attached to the screen). */
    private mchorse.bbs_mod.ui.framework.UIBaseMenu.UIRootElement borrowedFilmPanelRoot;

    /* Verification setup requested by ReplayDebugHook; applied once the viewport has framed. */
    private boolean debugCameraPending;
    private boolean debugTickPending;
    private int debugReplayIndex;
    private int debugTick;
    private float debugYaw = Float.NaN;
    private float debugPitch = Float.NaN;
    private float debugDistance = Float.NaN;
    /** True when the panel is a child of the compositor instead of a dashboard page. */
    private boolean embedded;

    public UIReplayModePanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.loadLayoutPrefs();

        this.header = new UIElement();
        this.header.relative(this).x(0).y(0).w(1F).h(HEADER);

        this.main = new UIElement();
        this.main.relative(this).x(0).y(HEADER).w(1F).h(1F, -HEADER - TIMELINE);

        this.title = new UILabel(IKey.constant("bbsae 虚拟回放"));
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
        this.buildCategoryBar();

        this.onAppear(() ->
        {
            /* The borrowed film panel must not rewind its runner to 0 while the replay page owns
             * the transport, and per-frame seek must not restart actions. Both are editor-global
             * settings; save and restore them around the replay page. */
            this.previousEditorRewind = BBSSettings.editorRewind.get();
            this.previousRestartOnSeek = BBSSettings.editorRestartOnSeek.get();
            BBSSettings.editorRewind.set(false);
            BBSSettings.editorRestartOnSeek.set(false);
            this.spaceWasDown = Window.isKeyPressed(GLFW.GLFW_KEY_SPACE);

            /* The camera view is a picture of the film, so it needs the clean BBS world frame and
             * the world camera must follow the replay playhead. Both are scoped to this page: the
             * compositor restores its own clock when this page disappears. */
            dev.bbsae.client.overlay.BbsPreviewBridge.setTimeOverride(this::playheadSeconds);
            dev.bbsae.client.overlay.BbsPreviewBridge.setReplayWorldPreview(true);

            this.bindFromDashboard();

            /* While this page owns the borrowed film panel, its replay list must keep showing the
             * virtual rows. Outside it the same widget is the original BBS film editor's list,
             * where those rows are hidden (see VirtualReplayVisibility). */
            if (this.filmPanel != null)
            {
                VirtualReplayVisibility.borrow(this.filmPanel);

                /* The same BBS widget is used by both editors. Rebuilding here makes a real-editor
                 * list (virtual rows filtered out) show the virtual rows again when this page is
                 * opened; the original editor does the reverse in onDisappear. */
                if (this.filmPanel.replayEditor != null)
                {
                    this.filmPanel.replayEditor.replaysList.replays.refreshReplayList();
                }
            }
        });
        this.onDisappear(() ->
        {
            if (this.session != null)
            {
                this.session.setPlaying(false);
            }

            BBSSettings.editorRewind.set(this.previousEditorRewind);
            BBSSettings.editorRestartOnSeek.set(this.previousRestartOnSeek);

            dev.bbsae.client.overlay.BbsPreviewBridge.clearTimeOverride();
            dev.bbsae.client.overlay.BbsPreviewBridge.setReplayWorldPreview(false);

            /* Leaving the page hands the film panel back to the original editor: hiding the
             * virtual rows only affects rows rebuilt after this point, so they are refreshed here.
             * Virtual replays also go back to disabled — otherwise they would stay as invisible
             * (but simulated) actors in the film after leaving this page. */
            VirtualReplayVisibility.release(this.filmPanel);

            if (this.film != null)
            {
                CompReplayBridge.setVirtualEnabled(this.film, false);
            }

            if (this.filmPanel != null && this.filmPanel.replayEditor != null)
            {
                this.filmPanel.replayEditor.replaysList.replays.refreshReplayList();
            }

            /* The cached composition frame belongs to this page; releasing it on leave keeps the
             * FBO pool from holding a full-resolution preview texture while another workspace is
             * open. It is re-baked on the first frame the page appears again. */
            if (this.viewport != null)
            {
                this.viewport.releasePreview();
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

    /**
     * The original replay editor's category strip: all tracks plus the same category icons the
     * film editor uses ({@link TrackCategories}). The default is "all tracks" — the wider view —
     * and a category narrows the dope sheet exactly like the original tab bar did. This is not a
     * second track model: the filtering uses {@link UIReplaysEditor#categoryOf(UIKeyframeSheet)},
     * the original category rule.
     */
    private void buildCategoryBar()
    {
        this.categoryBar = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF10151C);
                context.batcher.box(this.area.ex() - 1, this.area.y, this.area.ex(), this.area.ey(), 0xFF2A303A);
                super.render(context);
            }
        };
        this.categoryBar.relative(this).x(0).y(1F, -this.timelineHeight).w(20).h(this.timelineHeight);

        int y = 2;
        UIIcon all = new UIIcon(Icons.LIST, (button) -> this.setCategoryFilter(null));
        all.tooltip(IKey.constant("所有轨道（原版回放编辑器视图）"), Direction.RIGHT);
        all.highlight(() -> this.categoryFilter == null, Direction.LEFT);
        all.relative(this.categoryBar).x(2).y(y).w(16).h(16);
        this.categoryBar.add(all);
        y += 20;

        for (TrackCategory category : TrackCategories.values())
        {
            UIIcon button = new UIIcon(category.icon, (clicked) -> this.setCategoryFilter(category));
            button.tooltip(category.tooltip, Direction.RIGHT);
            button.highlight(() -> this.categoryFilter == category, Direction.LEFT);
            button.relative(this.categoryBar).x(2).y(y).w(16).h(16);
            this.categoryBar.add(button);
            y += 20;
        }

        UIIcon collapse = new UIIcon(Icons.COLLAPSE_ALL, (button) ->
        {
            if (this.keyframeEditor != null)
            {
                boolean expanded = this.keyframeEditor.view.getDopeSheet().hasExpandedSections();
                this.keyframeEditor.view.getDopeSheet().setAllSectionsExpanded(!expanded);
            }
        });
        collapse.tooltip(IKey.constant("展开 / 收起所有轨道分组"), Direction.RIGHT);
        collapse.relative(this.categoryBar).x(2).y(1F, -20).w(16).h(16);
        this.categoryBar.add(collapse);

        this.add(this.categoryBar);
    }

    private void setCategoryFilter(TrackCategory category)
    {
        this.categoryFilter = category;
        this.tracksDirty = true;
        this.primed = true;
        this.ensureTracks();
    }

    /**
     * Embeds the replay editor as the compositor's second workspace.
     *
     * <p>The default dashboard header is hidden: the compositor's own mode bar provides the
     * 合成/回放 switch and the transport, so the user gets one chrome instead of a second page with
     * a second visual language. The main row grows into the header's space; the keyframe editor
     * still owns the bottom strip.</p>
     */
    public void setEmbedded(boolean embedded)
    {
        this.embedded = embedded;
        this.header.setVisible(!embedded);
        this.timelineHeight = this.preferredTimelineHeight();
        this.main.relative(this).x(0).y(embedded ? 0 : HEADER).w(1F)
            .h(1F, -(embedded ? 0 : HEADER) - this.timelineHeight);
        this.repositionKeyframeEditor();
        this.resize();
    }

    /**
     * More of the page goes to the dope sheet than the old fixed 216 px did. The full original
     * replay editor shows every category's tracks at once here, and eight visible rows is not a
     * timeline an animator can work in. The strip stays proportional so a 1080p page gets a tall
     * dope sheet while a small window still leaves the 3D viewport usable.
     */
    private int preferredTimelineHeight()
    {
        int page = this.area.h > 0 ? this.area.h : 640;
        int auto = timelineHeightFor(page, this.embedded);
        int max = Math.max(MIN_TIMELINE, (this.embedded ? page : page - HEADER) - MIN_VIEWPORT);
        int height = this.timelineOverride > 0 ? this.timelineOverride : auto;

        return Math.max(MIN_TIMELINE, Math.min(480, Math.min(height, max)));
    }

    /**
     * Pure layout arithmetic for the embedded replay page: the bottom dope sheet must never grow
     * into the list/properties body. Kept package-private so a headless test can pin it.
     */
    static int timelineHeightFor(int pageHeight, boolean embedded)
    {
        int available = Math.max(0, pageHeight - (embedded ? 0 : HEADER));
        int height = Math.round(pageHeight * 0.38F);
        int maxTimeline = Math.max(80, available - 180);

        return Math.max(80, Math.min(480, Math.min(height, maxTimeline)));
    }

    private void loadLayoutPrefs()
    {
        CompPrefs.Data prefs = CompPrefs.get();

        this.listWidth = Math.max(MIN_LIST_WIDTH,
            Math.min(420, prefs.replayListWidth <= 0 ? LIST_WIDTH : prefs.replayListWidth));
        this.propsWidth = Math.max(MIN_PROPS_WIDTH,
            Math.min(520, prefs.replayPropsWidth <= 0 ? PROPS_WIDTH : prefs.replayPropsWidth));
        this.timelineOverride = Math.max(0, prefs.replayTimelineHeight);
    }

    private void saveLayoutPrefs()
    {
        CompPrefs.Data prefs = CompPrefs.get();

        prefs.replayListWidth = this.listWidth;
        prefs.replayPropsWidth = this.propsWidth;
        prefs.replayTimelineHeight = this.timelineOverride;
        CompPrefs.markDirty();
    }

    /**
     * Lays out the three body columns with user-adjustable widths.
     *
     * <p>The page used to hard-code 190/252 px columns and a proportional keyframe strip. There
     * was no splitter anywhere, so unlocking the compositor's dock layout could never change this
     * page: that lock only covers dock panels, not this internal layout. These handles are always
     * live and their sizes persist in {@link CompPrefs}.</p>
     */
    private void layoutReplayColumns()
    {
        if (this.replaysList == null || this.viewport == null || this.replayProperties == null
            || this.listSplitter == null || this.propsSplitter == null)
        {
            return;
        }

        int available = Math.max(MIN_VIEWPORT, this.main.area.w > 0 ? this.main.area.w : this.area.w);
        int lw = Math.max(MIN_LIST_WIDTH, Math.min(420, this.listWidth));
        int pw = Math.max(MIN_PROPS_WIDTH, Math.min(520, this.propsWidth));

        if (lw + pw + MIN_VIEWPORT + SPLIT * 2 > available)
        {
            int excess = lw + pw + MIN_VIEWPORT + SPLIT * 2 - available;

            if (lw > MIN_LIST_WIDTH)
            {
                int cut = Math.min(excess, lw - MIN_LIST_WIDTH);

                lw -= cut;
                excess -= cut;
            }

            if (excess > 0 && pw > MIN_PROPS_WIDTH)
            {
                pw = Math.max(MIN_PROPS_WIDTH, pw - excess);
            }
        }

        this.listWidth = lw;
        this.propsWidth = pw;

        this.replaysList.relative(this.main).x(0).y(0).w(lw).h(1F);
        this.listSplitter.relative(this.main).x(lw).y(0).w(SPLIT).h(1F);
        this.viewport.relative(this.main).x(lw + SPLIT).y(0)
            .w(1F, -(lw + SPLIT) - (pw + SPLIT)).h(1F);
        this.propsSplitter.relative(this.main).x(1F, -(pw + SPLIT)).y(0).w(SPLIT).h(1F);
        this.replayProperties.relative(this.main).x(1F, -pw).y(0).w(pw).h(1F);
        this.main.resize();
    }

    private void applyReplayTimelineLayout()
    {
        int height = this.preferredTimelineHeight();

        this.timelineHeight = height;
        this.main.relative(this).x(0).y(this.embedded ? 0 : HEADER).w(1F)
            .h(1F, -(this.embedded ? 0 : HEADER) - height);
        this.repositionKeyframeEditor();

        if (this.timelineSplitter != null)
        {
            this.timelineSplitter.relative(this).x(20).y(1F, -height - SPLIT).w(1F, -20).h(SPLIT);
        }
    }

    private void buildReplaySplitters()
    {
        this.listSplitter = new mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable((context) ->
        {
            this.listWidth = Math.max(MIN_LIST_WIDTH,
                Math.min(420, (int) context.mouseX - this.main.area.x));
            this.layoutReplayColumns();
            this.invalidateLayout();
        });
        this.listSplitter.rendering((context) -> context.batcher.box(
            this.listSplitter.area.x + SPLIT / 2F - 1F, this.listSplitter.area.y,
            this.listSplitter.area.x + SPLIT / 2F + 1F, this.listSplitter.area.ey(), 0x884C9AFF));
        this.listSplitter.cursors(org.lwjgl.glfw.GLFW.GLFW_RESIZE_EW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_RESIZE_EW_CURSOR);
        this.listSplitter.dragEnd(this::saveLayoutPrefs);

        this.propsSplitter = new mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable((context) ->
        {
            this.propsWidth = Math.max(MIN_PROPS_WIDTH,
                Math.min(520, (int) this.main.area.ex() - context.mouseX));
            this.layoutReplayColumns();
            this.invalidateLayout();
        });
        this.propsSplitter.rendering((context) -> context.batcher.box(
            this.propsSplitter.area.x + SPLIT / 2F - 1F, this.propsSplitter.area.y,
            this.propsSplitter.area.x + SPLIT / 2F + 1F, this.propsSplitter.area.ey(), 0x884C9AFF));
        this.propsSplitter.cursors(org.lwjgl.glfw.GLFW.GLFW_RESIZE_EW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_RESIZE_EW_CURSOR);
        this.propsSplitter.dragEnd(this::saveLayoutPrefs);

        this.timelineSplitter = new mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable((context) ->
        {
            int h = (int) this.area.ey() - context.mouseY - SPLIT / 2;
            int max = Math.max(MIN_TIMELINE,
                (this.embedded ? this.area.h : this.area.h - HEADER) - MIN_VIEWPORT);

            this.timelineOverride = Math.max(MIN_TIMELINE, Math.min(480, Math.min(h, max)));

            this.applyReplayTimelineLayout();
            this.invalidateLayout();
        });
        this.timelineSplitter.rendering((context) -> context.batcher.box(
            this.timelineSplitter.area.x, this.timelineSplitter.area.y + SPLIT / 2F - 1F,
            this.timelineSplitter.area.ex(), this.timelineSplitter.area.y + SPLIT / 2F + 1F, 0x884C9AFF));
        this.timelineSplitter.cursors(org.lwjgl.glfw.GLFW.GLFW_RESIZE_NS_CURSOR, org.lwjgl.glfw.GLFW.GLFW_RESIZE_NS_CURSOR);
        this.timelineSplitter.dragEnd(this::saveLayoutPrefs);

        this.main.add(this.listSplitter, this.propsSplitter);
        this.add(this.timelineSplitter);
    }

    private void repositionKeyframeEditor()
    {
        if (this.categoryBar != null)
        {
            this.categoryBar.relative(this).x(0).y(1F, -this.timelineHeight).w(20).h(this.timelineHeight);
        }

        if (this.keyframeEditor != null)
        {
            this.keyframeEditor.relative(this).x(20).y(1F, -this.timelineHeight)
                .w(1F, -20).h(this.timelineHeight);
        }
    }

    @Override
    public void resize()
    {
        if (this.main == null)
        {
            super.resize();
            return;
        }

        this.applyReplayTimelineLayout();
        super.resize();
        this.layoutReplayColumns();
    }

    public boolean isEmbedded()
    {
        return this.embedded;
    }

    public void togglePlaybackFromHost()
    {
        this.togglePlayback();
    }

    public void stepFromHost(int ticks)
    {
        this.step(ticks);
    }

    public void frameAllFromHost()
    {
        this.frameAll();
    }

    /** Test hook: drive the preview view mode without synthesising a click. */
    public void setPreviewViewModeFromHost(int mode)
    {
        if (this.viewport != null)
        {
            this.viewport.setViewModeFromHost(mode);
        }
    }

    public int previewViewModeFromHost()
    {
        return this.viewport == null ? -1 : this.viewport.viewModeFromHost();
    }

    public void openModelLibraryFromHost()
    {
        this.openModelLibrary();
    }

    /**
     * The original replay editor's "insert frame" (BBS keybind <b>I</b>): write the selected
     * actor's current position and rotation at the film cursor through the replay's own keyframe
     * channels, inside one {@code BaseValue.edit} so it is a single undo step.
     *
     * <p>With {@code pose} the form's whole pose tracks are keyed as well (BBS does this from its
     * POSE tab). Both paths operate on the original {@link Replay} data, which is what the visible
     * actor and the original dope sheet read — not on a parallel bbsae timeline.</p>
     */
    /**
     * Test-only mouse scrub: dispatches a real UI click at the ruler's middle and verifies that
     * the replay session playhead moved. Enabled by {@code -Dbbsae.debugReplayScrub=true}; this is
     * the regression check for the "playhead cannot be dragged" bug.
     */
    public boolean debugScrubTimeline()
    {
        if (this.keyframeEditor == null || this.session == null)
        {
            return false;
        }

        mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes view = this.keyframeEditor.view;
        UIContext context = new UIContext(mchorse.bbs_mod.BBSModClient.getDashboard());

        int absoluteX = 0;
        int absoluteY = 0;

        for (mchorse.bbs_mod.ui.framework.elements.UIElement element = view; element != null; element = element.getParent())
        {
            absoluteX += element.area.x;
            absoluteY += element.area.y;
        }

        context.mouseX = 700;
        context.mouseY = 650;
        context.mouseButton = 0;

        ReplayFilmKeyframes debugView = view instanceof ReplayFilmKeyframes ? (ReplayFilmKeyframes) view : null;

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "Replay scrub audit: area={},{} {}x{} absolute={},{} graph={},{} {}x{} hasCursor={} graphTick={}",
            view.area.x, view.area.y, view.area.w, view.area.h, absoluteX, absoluteY,
            view.graphArea.x, view.graphArea.y, view.graphArea.w, view.graphArea.h,
            debugView != null && debugView.debugHasCursor(),
            debugView == null ? -1F : debugView.debugGraphTick(context.mouseX));

        float before = this.session.playhead();

        if (debugView != null)
        {
            debugView.debugScrub(context.mouseX);
        }

        float after = this.session.playhead();

        boolean moved = Math.abs(after - before) > 0.01F;

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "Replay scrub audit: before={} after={} moved={}", before, after, moved);

        return moved;
    }

    public void insertKeyframeFromHost(boolean pose)
    {        if (this.replay == null || this.session == null)
        {
            this.setViewportStatus("先选择一条回放，再打关键帧");
            return;
        }

        IEntity entity = this.session.entity(this.replay);

        if (entity == null)
        {
            this.setViewportStatus("当前回放没有可写入的实体（先在模型库选一个模型）");
            return;
        }

        this.session.setPlaying(false);
        this.session.applyPose(entity, this.replay);

        int tick = (int) Math.floor(this.session.keyframeTick());
        String bone = this.boneGizmoPath();
        Form partForm = this.session.selectedPartForm();

        /* A picked part has no pose editor until a pose key exists. The first I/K therefore has to
         * use the picked bone itself: it inserts into that part's pose track, selects the new key
         * and thereby opens the pose gizmo. Falling back to the replay root here was why a part
         * could be "selected" but never keyed. */
        if (bone.isEmpty() && this.session.selectedBone() != null)
        {
            bone = this.session.selectedBone();
        }

        /* A selected body part is keyed on its own track, exactly like the original editor's
         * I key while a bone is picked. The replay-root position/rotation key is only for a
         * whole-actor selection. */
        if (!pose && !bone.isEmpty() && partForm != null && this.keyframeEditor != null)
        {
            try
            {
                UIReplaysEditorUtils.pickForm(this.keyframeEditor, this.replayCursor, partForm, bone, true);

                this.tracksDirty = true;
                this.primed = false;
                this.viewportInvalidate();
                this.setViewportStatus("已在 tick " + tick + " 写入骨骼「" + bone + "」关键帧");

                return;
            }
            catch (RuntimeException e)
            {
                BbsAeAddon.LOGGER.warn("Replay mode could not key the selected bone '{}'", bone, e);
            }
        }

        try
        {
            if (pose)
            {
                mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils.insertPoseKeyframesAtTick(
                    this.replay, tick, new mchorse.bbs_mod.ui.framework.elements.input.items.FoldState<>());
            }
            else
            {
                List<String> groups = List.of(
                    mchorse.bbs_mod.film.replays.ReplayKeyframes.GROUP_POSITION,
                    mchorse.bbs_mod.film.replays.ReplayKeyframes.GROUP_ROTATION
                );

                mchorse.bbs_mod.settings.values.base.BaseValue.edit(this.replay.keyframes,
                    (keyframes) -> keyframes.record(tick, entity, groups));
            }
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay keyframe insertion failed", e);
            this.setViewportStatus("打关键帧失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));

            return;
        }

        this.tracksDirty = true;
        this.viewportInvalidate();
        this.setViewportStatus("已在 tick " + tick + " 写入" + (pose ? "姿态" : "位置/旋转") + "关键帧");
    }

    /** Drops the cached 3D preview after an edit that changes what the camera frame must show. */
    private void viewportInvalidate()
    {
        if (this.viewport != null)
        {
            this.viewport.invalidatePreview();
        }
    }

    private void setViewportStatus(String status)
    {
        if (this.viewport != null)
        {
            this.viewport.setStatus(status);
        }
    }

    public void openOriginalEditorFromHost()
    {
        this.openOriginalEditor();
    }

    /** Playhead in seconds for the host's timecode/status line. */
    public float playheadSeconds()
    {
        return this.session == null ? 0F : this.session.playhead() / ReplayTime.TICKS_PER_SECOND;
    }

    /** Seeks from the host's playhead without changing the film's cursor semantics. */
    public void seekSeconds(float seconds)
    {
        if (this.session != null)
        {
            this.session.seek(Math.max(0F, seconds) * ReplayTime.TICKS_PER_SECOND);
        }
    }

    public boolean isPlayingFromHost()
    {
        return this.session != null && this.session.isPlaying();
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
        this.virtualFilterDirty = true;
        VirtualReplayVisibility.borrow(panel);

        /* Attach it before anything below can write into the film: BBS' undo data is collected
         * through UIElement.getRoot() at the moment of the edit (see attachBorrowedFilmPanel). */
        this.attachBorrowedFilmPanel();

        if (filmChanged)
        {
            this.selectedPart = "";
        }

        boolean builtNow = !this.built;

        if (!this.built)
        {
            this.build(panel);
        }

        boolean replacedSession = false;

        /*
         * build() has just created a session for this film. Replacing it here was the playhead-drag
         * bug: the timeline held the build() session while the panel read a second one, so a scrub
         * wrote the old session and the needle stayed at 0. Only replace on a real film switch.
         */
        if (this.session == null || (filmChanged && !builtNow))
        {
            this.session = new ReplayEditSession(panel, film, replay);
            this.session.setReplayFilter(CompReplayBridge::isVirtual);
            this.session.setPreviewInvalidator(this.viewport::invalidatePreview);
            this.session.setContextSupplier(this::getContext);
            this.viewport.setSession(this.session);
            replacedSession = true;
        }

        this.session.syncSceneScale();
        this.session.ensureEntities();
        this.session.resetFraming();
        this.replaysList.replays.refreshReplayList();

        /* A replay handed in by the film editor may belong to the previous film (the original
         * replay editor keeps its selection when a new film is filled). Only trust it when it is
         * in this film by identity. */
        Replay initial = CompReplayBridge.isVirtual(replay) && ReplayListOps.containsIdentical(film.replays.getList(), replay)
            ? replay
            : this.firstReplay(film);

        this.selectReplay(initial);

        if (replacedSession)
        {
            /* The timeline was built against the old session; rebuild it for the new film. */
            this.rebuildKeyframeEditor();
            this.tracksDirty = true;
        }

        this.knownOrder.clear();
        this.knownOrder.addAll(film.replays.getList());
    }

    /**
     * Gives the borrowed {@link UIFilmPanel} a private {@code UIRootElement} while replay mode
     * borrows it.
     *
     * <p>BBS' film undo handler collects and applies per-element UI data through
     * {@code UIElement.getRoot()}. A detached panel has a null root: applying the snapshot threw an
     * NPE (Run A) and the write-time snapshot was empty (Run B, {@code uiBefore=null}). Mounting a
     * hidden holder under the real screen root fixed the data but made BBS apply the film's UI
     * snapshot to the whole screen: after Ctrl+Z the replay page stopped drawing (Runs C–F showed
     * world + gizmo only), because the film panel's own showPanel/dock side effects ran while the
     * borrowed tree lived inside the real root.</p>
     *
     * <p>A private root keeps collect/apply inside the film panel's own subtree: the undo data of
     * the film is complete (the panel and all editors are under it) while the replay page, the
     * dashboard and the real screen root are never visited. The fake root is attached to no screen,
     * so it is not rendered, laid out or hit-tested.</p>
     *
     * <p>Called from {@link #update()} / bind while the page is on screen, so a real UIContext is
     * available for the private root (children may query it while applying undo data).</p>
     */
    private void attachBorrowedFilmPanel()
    {
        UIFilmPanel panel = this.filmPanel;

        if (panel == null || panel.getRoot() != null)
        {
            return;
        }

        if (panel.getParent() != null)
        {
            /* Someone else owns it (it is the dashboard's current panel): it already has a root. */
            return;
        }

        mchorse.bbs_mod.ui.framework.UIContext context = this.getContext();

        if (context == null)
        {
            /* Not on screen yet: retried from update() on the frame the page appears. */
            return;
        }

        this.borrowedFilmPanelVisible = panel.isVisible();

        panel.setVisible(false);

        if (this.borrowedFilmPanelRoot == null)
        {
            this.borrowedFilmPanelRoot = new mchorse.bbs_mod.ui.framework.UIBaseMenu.UIRootElement(context);
            this.borrowedFilmPanelRoot.setVisible(false);
        }

        if (this.borrowedFilmPanelHolder == null)
        {
            this.borrowedFilmPanelHolder = new UIElement();
            this.borrowedFilmPanelHolder.setVisible(false);
            this.borrowedFilmPanelHolder.relative(this.borrowedFilmPanelRoot).x(0).y(0).w(1F).h(1F);
            this.borrowedFilmPanelHolder.add(panel);
            this.borrowedFilmPanelRoot.add(this.borrowedFilmPanelHolder);
        }
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

        this.borrowedFilmPanelRoot = null;
    }

    private void build(UIFilmPanel panel)
    {
        this.built = true;

        this.session = new ReplayEditSession(panel, this.film, this.replay);
        this.session.setReplayFilter(CompReplayBridge::isVirtual);
        this.session.setContextSupplier(this::getContext);

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
        this.viewport.setPartHandler(this::selectPartFromViewport);
        this.viewport.setBoneTarget(this::boneGizmoTransform, this::boneGizmoPath);
        this.session.setPreviewInvalidator(this.viewport::invalidatePreview);

        this.main.add(this.replaysList, this.viewport, this.replayProperties);
        this.buildReplaySplitters();
        this.layoutReplayColumns();

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
            if (candidate == null || !Boolean.TRUE.equals(candidate.enabled.get())
                || !CompReplayBridge.isVirtual(candidate))
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

        return enabledFallback;
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
     * Selects a model part (and optionally one of its bones) from a viewport click.
     *
     * <p>This is the missing half of the original K-frame workflow: clicking a form/bone in the
     * 3D view did nothing here — the click only selected the whole record. The path is handed to
     * the original body-part list (so the timeline shows that part's tracks) and, once the rows
     * have been rebuilt, to {@code UIReplaysEditorUtils.pickForm}, the original "select this bone
     * for editing" entry point. The deferred application matters because selecting a body part
     * rebuilds the keyframe editor; using the old instance would select a track in a widget that
     * is no longer on screen.</p>
     */
    public void selectPartFromViewport(Form form, String bone)
    {
        if (this.replay == null || form == null || this.replaysList == null)
        {
            return;
        }

        String path = FormUtils.getPath(form);

        if (path == null)
        {
            path = "";
        }

        String selected = this.replaysList.setBodyPartsReplay(this.replay, path);

        this.selectedPart = selected;
        this.tracksDirty = true;
        this.primed = false;

        if (this.session != null)
        {
            this.session.setSelectedPart(form, selected, bone == null ? "" : bone);
        }

        this.pendingPartForm = form;
        this.pendingPartBone = bone == null ? "" : bone;

        if (this.viewport != null)
        {
            String label = path.isEmpty() ? "模型" : path;
            String boneLabel = bone == null || bone.isEmpty() ? "" : " · 骨骼 " + bone;

            this.viewport.setStatus("已选择部位：" + label + boneLabel + "（关键帧/绕对象旋转跟随它）");
            this.viewport.invalidatePreview();
        }
    }

    /** The bone transform of the keyframe timeline's current track, or null when it is not a bone. */
    private mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform boneGizmoTransform()
    {
        if (this.keyframeEditor == null || this.boneGizmoPath().isEmpty())
        {
            return null;
        }

        try
        {
            return UIReplaysEditorUtils.getEditableTransform(this.keyframeEditor);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** The bone path the keyframe timeline is currently editing, or "". */
    private String boneGizmoPath()
    {
        if (this.keyframeEditor == null)
        {
            return "";
        }

        try
        {
            mchorse.bbs_mod.utils.Pair<String, mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace> bone =
                this.keyframeEditor.getBone();

            return bone == null || bone.a == null ? "" : bone.a;
        }
        catch (RuntimeException e)
        {
            return "";
        }
    }

    /** Runs the actual "open this bone's track" selection once the rebuilt timeline exists. */
    private void applyPendingPartPick()
    {
        Form form = this.pendingPartForm;

        if (form == null || this.keyframeEditor == null || this.session == null)
        {
            return;
        }

        this.pendingPartForm = null;

        String bone = this.pendingPartBone == null ? "" : this.pendingPartBone;

        try
        {
            /* The original helper selects the closest keyframe/climbs to the pose editor. Without
             * it the part's rows appear but the timeline still edits whatever was selected before. */
            UIReplaysEditorUtils.pickForm(this.keyframeEditor, this.replayCursor, form, bone);

            /* A viewport pick names a specific bone, but pickForm can leave the pose editor on its
             * previous group when the sheet itself did not change. Force the named bone so the
             * gizmo/timeline both point at what the user clicked (clicking "head" used to settle
             * on "anchor"). */
            if (this.keyframeEditor.editor instanceof mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory poseFactory)
            {
                if (!bone.equals(this.boneGizmoPath()))
                {
                    poseFactory.poseEditor.selectBone(bone);
                }
            }
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.warn("Replay mode could not select the picked body part '{}'", bone, e);
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
        this.virtualFilterDirty = true;

        try
        {
            /* A replay object can be stale: an undo restores the film's ValueGroup children as new
             * objects, and a film switch hands in the previous film's selection. A foreign object
             * must not become the selected replay — every gizmo/auto-key write would land on a
             * detached record that is no longer part of the film. */
            Replay effective = replay;

            if (effective != null && this.film != null
                && (!ReplayListOps.containsIdentical(this.film.replays.getList(), effective)
                    || !CompReplayBridge.isVirtual(effective)))
            {
                if (Boolean.getBoolean("bbsae.debugReplayBridge"))
                {
                    BbsAeAddon.LOGGER.info("Replay selection rejected: virtual={} inFilm={} label='{}'",
                        CompReplayBridge.isVirtual(effective),
                        ReplayListOps.containsIdentical(this.film.replays.getList(), effective),
                        effective.label.get());
                }

                effective = null;
            }

            Replay previous = this.replay;

            this.replay = effective;

            if (this.session != null)
            {
                this.session.setReplay(effective);

                if (previous != effective)
                {
                    /* A part belongs to one actor. Carrying a bone path across a selection would
                     * point the orbit pivot/gizmo at a bone of the previous actor. */
                    this.session.setSelectedPart(null, "", "");
                    this.session.setBoneTransform(null);
                    this.pendingPartForm = null;
                    this.pendingPartBone = null;
                    this.lastEditorBone = "";
                }
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
            this.viewport.invalidatePreview();
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
            (callback) -> new ReplayFilmKeyframes(this.filmPanel.cameraEditor, callback, this.session).absolute()
        );

        /* No target: the keyframe editor keeps its 140 px properties strip inside the timeline.
         * Targeting the replay properties panel made UIKeyframeEditor attach the selected
         * keyframe's numeric factory full-size over that panel, which is exactly the overlapping
         * "data interface" seen while K-ing pose frames. BBS itself targets a dedicated edit area;
         * in this embedded layout the in-timeline strip is that dedicated area. */
        /* The timeline's own empty-state hint ("Click a keyframe...") was drawn over the replay
         * properties in the narrow embedded layout. The dope sheet below has a proper Chinese
         * empty state, so suppress this legacy label. */
        editor.setEmptyState(() -> IKey.constant(""));
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

        editor.relative(this).x(20).y(1F, -this.timelineHeight).w(1F, -20).h(this.timelineHeight);

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

        List<UIKeyframeSheet> sheets = ReplayTrackBuilder.build(this.replay, this.selectedPart);

        if (this.categoryFilter != null)
        {
            /* Same rule as the original tab bar: the category is derived from the sheet id and
             * its owner, never from a second local list of "which tracks are pose". */
            sheets.removeIf((sheet) -> UIReplaysEditor.categoryOf(sheet) != this.categoryFilter);
            UIReplaysEditorUtils.pruneTree(sheets);
        }

        for (UIKeyframeSheet sheet : sheets)
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

        /* Space is BBS' play/pause key. The borrowed film panel's own space binding can still be
         * active while it is hidden, and the compositor panel is the focused panel now, so relying
         * on subKeyPressed alone missed presses. Poll the physical key while this page is active;
         * text fields keep the key for typing. A physical press edge prevents repeats. */
        boolean spaceDown = Window.isKeyPressed(GLFW.GLFW_KEY_SPACE);
        UIContext inputContext = this.getContext();
        boolean typing = inputContext != null && inputContext.activeElement instanceof IFocusedUIElement;

        if (spaceDown && !this.spaceWasDown && !typing)
        {
            this.togglePlayback();
        }

        this.spaceWasDown = spaceDown;

        super.update();

        if (this.session != null)
        {
            /* The playhead is advanced from the render pass, not from this once-per-tick update:
             * driving a 60 fps composition from a 20 Hz clock made playback visibly stutter, and
             * every displayed frame must sample the same wall-clock instant as the composition
             * preview it is compared with. */

            if (this.session.isPlaying() && this.keyframeEditor != null)
            {
                /* Keep the manually driven playhead inside the dope sheet. BBS' runner does this
                 * when playback starts; the replay page has its own transport, so it must do the
                 * same or the cursor runs off screen and the tracks look frozen. */
                this.keyframeEditor.view.getXAxis().shiftIntoMiddle(this.session.tick());
            }
        }
    }

    @Override
    public void render(UIContext context)
    {
        if (this.logNextRender)
        {
            this.logNextRender = false;
            BbsAeAddon.LOGGER.info("Replay history: page render reached after undo/redo (visible={}, area={}x{}@{}, parent={}, fbo={}, depth={}, blend={})",
                this.isVisible(), this.area.w, this.area.h, this.area.x, this.area.y,
                this.getParent() == null ? "null" : this.getParent().getClass().getSimpleName(),
                org.lwjgl.opengl.GL30.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND));
        }

        this.checkReplayListChanged();
        this.applyVirtualReplayFilter();
        this.ensureTracks();
        this.applyPendingPartPick();

        /* Keep the session's gizmo/pivot target in step with whatever the timeline currently
         * exposes. A form-transform track has no bone path and falls back to the replay root. */
        if (this.session != null)
        {
            String bone = this.boneGizmoPath();

            if (this.session.selectedPartForm() != null && !bone.isEmpty())
            {
                /* Mirror only an actual editor selection change; see lastEditorBone. */
                if (!bone.equals(this.lastEditorBone))
                {
                    this.session.setSelectedBone(bone);
                    this.session.setBoneTransform(this.boneGizmoTransform());
                }
            }
            else
            {
                this.session.setBoneTransform(null);
            }

            this.lastEditorBone = bone;
        }

        this.applyDebugSetup();
        this.refreshStatus();

        /* Dragging a keyframe/value edits the replay without seeking the playhead; the cached
         * camera frame must follow the edit instead of waiting for the next scrub. */
        if (this.keyframeEditor != null && this.keyframeEditor.view.isInteracting())
        {
            this.viewportInvalidate();
        }

        super.render(context);
    }

    /**
     * Restricts the list to replays created by bbsae's virtual world.
     *
     * <p>The embedded editor borrows the film's replay list because that is the original BBS
     * editor widget, but the real film's recorded actors are not part of bbsae's virtual scene.
     * {@link CompReplayBridge#VIRTUAL_MARKER} is a zero-width prefix on virtual replays; the
     * original list's own text filter therefore hides every real-world replay without changing
     * the BBS list class. Search and "add" are disabled while this filter is active: a search
     * would replace the marker, and a replay added here would not be a bbsae virtual actor.</p>
     */
    private void applyVirtualReplayFilter()
    {
        if (this.replaysList == null || !this.virtualFilterDirty)
        {
            return;
        }

        this.virtualFilterDirty = false;

        if (this.film != null)
        {
            for (Replay replay : this.film.replays.getList())
            {
                /* Do not fight the label textbox while its replay is selected: prepending the
                 * zero-width marker on the same frame the user is typing would move the caret.
                 * Once the selection moves away, the marker is restored, so the row cannot be
                 * lost permanently. */
                if (replay != this.replay && CompReplayBridge.isVirtual(replay))
                {
                    CompReplayBridge.markVirtual(replay);
                }
            }
        }

        this.replaysList.replays.filter(CompReplayBridge.VIRTUAL_MARKER);
        this.replaysList.search.setEnabled(false);
        this.replaysList.addReplay.setVisible(false);
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

        this.virtualFilterDirty = true;
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
            return "没有打开的影片：先在影片编辑器打开一个影片，或从合成器点「回放」"
                + "（调试可用 -Dbbsae.debugReplayFilm=<影片 id>）";
        }

        int count = this.session == null ? 0 : this.session.replayCount();
        int index = -1;

        for (Replay candidate : this.film.replays.getList())
        {
            if (CompReplayBridge.isVirtual(candidate))
            {
                index += 1;

                if (candidate == this.replay)
                {
                    break;
                }
            }
        }

        if (this.replay == null)
        {
            index = -1;
        }
        int tick = this.session == null ? 0 : this.session.tick();
        int duration = this.session == null ? 0 : this.session.durationTicks();
        String base = "影片 " + this.film.getId()
            + " · 回放 " + (index < 0 ? "-" : String.valueOf(index + 1)) + "/" + count
            + " · tick " + tick + "/" + duration
            + (this.session != null && this.session.isPlaying() ? " · 播放中" : "");

        if (count == 0)
        {
            return base + " · 没有虚拟回放：回到合成器点「＋模型」添加 bbsae 回放";
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

    /** List index of the first replay with a form (virtual-world replays only), or 0 when none. */
    public int firstPlayableReplayIndex()
    {
        if (this.film != null)
        {
            List<Replay> replays = this.film.replays.getList();

            for (int i = 0; i < replays.size(); i++)
            {
                if (this.session != null && this.session.isAllowed(replays.get(i))
                    && this.session.hasModel(replays.get(i)))
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
        this.viewportInvalidate();
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

        boolean wasVisible = panel.isVisible();
        boolean attached = panel.getRoot() != null;

        if (!attached)
        {
            panel.setVisible(false);
            panel.relative(this).x(0).y(0).w(1F).h(1F);
            this.add(panel);
        }

        /* BBS' film-panel undo path runs showPanel() -> applyPreviewSizeToBBS(), which writes the
         * film's custom preview size (the test film stores 4x2) into the global BBS video size.
         * That global drives the film preview / recorder; a 4x2 preview upscaled to the screen is
         * what the blank "world only" screenshots were showing after Ctrl+Z. Borrowed-panel undo
         * must not change the client's preview resolution, so restore it afterwards. */
        int bbsVideoWidth = mchorse.bbs_mod.BBSSettings.videoWidth.get();
        int bbsVideoHeight = mchorse.bbs_mod.BBSSettings.videoHeight.get();
        int bbsVideoFps = mchorse.bbs_mod.BBSSettings.videoFrameRate.get();
        boolean bbsCustomSize = mchorse.bbs_mod.client.BBSRendering.isCustomSize();

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
            if (mchorse.bbs_mod.BBSSettings.videoWidth.get() != bbsVideoWidth)
            {
                mchorse.bbs_mod.BBSSettings.videoWidth.set(bbsVideoWidth);
            }

            if (mchorse.bbs_mod.BBSSettings.videoHeight.get() != bbsVideoHeight)
            {
                mchorse.bbs_mod.BBSSettings.videoHeight.set(bbsVideoHeight);
            }

            if (mchorse.bbs_mod.BBSSettings.videoFrameRate.get() != bbsVideoFps)
            {
                mchorse.bbs_mod.BBSSettings.videoFrameRate.set(bbsVideoFps);
            }

            /* The film panel's undo path also toggles BBS' custom render size, which replaces the
             * frame buffer used while the film preview is active. Leaving it on after undo made the
             * whole dashboard stop appearing (world + gizmo only). The replay page is not the film
             * preview, so restore the window-sized path. */
            if (!bbsCustomSize && mchorse.bbs_mod.client.BBSRendering.isCustomSize())
            {
                mchorse.bbs_mod.client.BBSRendering.setCustomSize(false);
            }

            if (!attached)
            {
                panel.removeFromParent();
                panel.setVisible(wasVisible);
            }
        }

        this.logNextRender = true;
        this.debugAfterExternalEdit(selected);

        /* BBS' applyUndoData() path is allowed to restructure the film panel's own UI (showPanel,
         * dock refresh, createEntities). On the real machine the whole dashboard sometimes lost
         * its panel visibility during undo, leaving only the world (the page's getRoot() becomes
         * null while the client screen is still open). Re-open the dashboard so the user is not
         * stranded on an invisible editor; the log line makes the underlying bug visible. */
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        BbsAeAddon.LOGGER.info("Replay history after {}: root={} screen={} pageVisible={} panelVisible={} panelArea={}x{} currentPanel={}",
            redo ? "redo" : "undo", this.getRoot() != null, client == null ? null : client.currentScreen,
            this.isVisible(), panel.isVisible(), this.area.w, this.area.h,
            this.dashboard == null || this.dashboard.getPanels() == null || this.dashboard.getPanels().panel == null
                ? "null" : this.dashboard.getPanels().panel.getClass().getSimpleName());

        if (client != null && this.getRoot() == null && !this.embedded)
        {
            BbsAeAddon.LOGGER.warn("Replay history: page detached from the UI root; reopening the dashboard");

            try
            {
                mchorse.bbs_mod.ui.framework.UIScreen.open(this.dashboard);
            }
            catch (RuntimeException e)
            {
                BbsAeAddon.LOGGER.error("Could not reopen the dashboard after replay undo/redo", e);
            }
        }
        else if (!this.embedded && this.dashboard != null && this.dashboard.getPanels() != null
            && this.dashboard.getPanels().panel != this)
        {
            /* BBS' film-panel undo path (applyUndoData -> showPanel/createEntities) can re-select a
             * dashboard panel as a side effect. The page then stays "visible" but is no longer the
             * one being rendered — the user saw only the world and the gizmo after Ctrl+Z. */
            BbsAeAddon.LOGGER.warn("Replay history: dashboard panel switched away; restoring the replay page");
            this.dashboard.setPanel(this);
        }

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

    /* ------------------------------------------------------------ interaction debug */

    /** Debug: how many full pick passes actually ran (cache effectiveness). */
    public int debugPickRenders()
    {
        return this.viewport == null ? -1 : this.viewport.debugPickRenders();
    }

    /** Debug: project a bone and run the real stencil pick + click path. */
    public String debugClickBone(String bone)
    {
        return this.viewport == null ? "no-viewport" : this.viewport.debugClickBone(bone);
    }

    /** Runs one real gizmo gesture; see {@link UIReplayViewport#debugDragGizmo}. */
    public boolean debugGizmoDrag(int handleIndex, int dx, int dy)
    {
        return this.viewport != null && this.viewport.debugDragGizmo(handleIndex, dx, dy);
    }

    /**
     * Debug: selects the first replay whose form is a model with a bone track, then selects its
     * first bone. Returns the bone path, or "" when the film has no posed model.
     */
    public String debugSelectModelBone()
    {
        if (this.film == null)
        {
            return "";
        }

        for (Replay replay : this.film.replays.getList())
        {
            if (replay == null || !this.session.isAllowed(replay))
            {
                continue;
            }

            Form form = replay.form.get();

            if (!(form instanceof mchorse.bbs_mod.forms.forms.ModelForm))
            {
                continue;
            }

            for (mchorse.bbs_mod.film.replays.tracks.TrackDescriptor track
                : mchorse.bbs_mod.film.replays.tracks.TrackCatalog.of(form))
            {
                if (track.id().kind() == mchorse.bbs_mod.film.replays.tracks.TrackKind.BONE
                    && track.id().subject() != null && !track.id().subject().isEmpty())
                {
                    this.selectReplay(replay);
                    this.selectPartFromViewport(form, track.id().subject());

                    return track.id().subject();
                }
            }
        }

        return "";
    }

    /** Selects a known body part/bone through the production part-click path. */
    public boolean debugSelectPart(String bone)
    {
        Form form = this.replay == null ? null : this.replay.form.get();

        if (form == null)
        {
            return false;
        }

        this.selectPartFromViewport(form, bone == null ? "" : bone);

        return true;
    }

    /** One-line state for the part/gizmo/orbit verification. */
    public String debugPartSummary()
    {
        if (this.session == null)
        {
            return "session=null";
        }

        org.joml.Vector3f pivot = this.session.selectedPartComp();

        return String.format(java.util.Locale.ROOT,
            "partPath='%s' bone='%s' editorBone='%s' boneGizmo=%s boneTransform=%s pivot=%s selectedPart='%s'",
            this.session.selectedPartPath(), this.session.selectedBone(), this.boneGizmoPath(),
            this.session.isBoneGizmo(), this.session.boneGizmoTransform() != null,
            pivot == null ? "-" : String.format(java.util.Locale.ROOT, "(%.1f,%.1f)", pivot.x, pivot.y),
            this.selectedPart);
    }

    /** Geometry proof that the page's internal splitters exist and are laid out. */
    public String debugLayoutSummary()
    {
        return String.format(java.util.Locale.ROOT,
            "list=%dx%d@%d viewport=%dx%d@%d props=%dx%d@%d listSplit=%dx%d propsSplit=%dx%d timelineSplit=%dx%d@%d main=%dx%d timelineH=%d",
            this.replaysList.area.w, this.replaysList.area.h, this.replaysList.area.x,
            this.viewport.area.w, this.viewport.area.h, this.viewport.area.x,
            this.replayProperties.area.w, this.replayProperties.area.h, this.replayProperties.area.x,
            this.listSplitter.area.w, this.listSplitter.area.h,
            this.propsSplitter.area.w, this.propsSplitter.area.h,
            this.timelineSplitter.area.w, this.timelineSplitter.area.h, this.timelineSplitter.area.y,
            this.main.area.w, this.main.area.h, this.timelineHeight);
    }

    /** Debug: asks the splitters' production geometry to resize (not a synthetic field write). */
    public void debugResizeColumns(int list, int props, int timeline)
    {
        this.listWidth = Math.max(MIN_LIST_WIDTH, Math.min(420, list));
        this.propsWidth = Math.max(MIN_PROPS_WIDTH, Math.min(520, props));
        this.timelineOverride = Math.max(MIN_TIMELINE, Math.min(480, timeline));
        this.timelineHeight = this.preferredTimelineHeight();
        this.layoutReplayColumns();
        this.applyReplayTimelineLayout();
        this.invalidateLayout();
        this.resize();
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

        /* BBS' original transport does this when playback starts: the playhead is put in the
         * middle of the dope sheet so the tracks can be followed. Without it the cursor runs off
         * the visible range and the timeline looks frozen even though the film is playing. */
        if (this.lastPlaying && this.keyframeEditor != null)
        {
            this.keyframeEditor.view.getXAxis().shiftIntoMiddle(this.session.tick());
        }
    }

    private void step(int ticks)
    {
        if (this.session == null)
        {
            return;
        }

        this.session.setPlaying(false);
        this.session.step(ticks);

        if (this.keyframeEditor != null)
        {
            this.keyframeEditor.view.getXAxis().shiftIntoMiddle(this.session.tick());
        }
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
            /* Keep the free/orbit pose in sync with the new framing; otherwise a later
             * camera → free switch restores the pre-framing pose and the actor becomes a dot. */
            this.viewport.syncFreePoseFromCamera();
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
            this.viewport.invalidatePreview();
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

            if (!Window.isCtrlPressed() && (key == GLFW.GLFW_KEY_I || key == GLFW.GLFW_KEY_K))
            {
                /* BBS keys a frame with I; K is accepted as the animation-community spelling of
                 * the same action. Shift additionally keys the form's pose tracks. */
                this.insertKeyframeFromHost(Window.isShiftPressed());

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
