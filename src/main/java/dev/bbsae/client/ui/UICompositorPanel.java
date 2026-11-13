package dev.bbsae.client.ui;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.export.CompExportManager;
import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.client.render.CompScene;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.client.render.CompViewportRenderer;
import dev.bbsae.client.render.ImageTextureCache;
import dev.bbsae.client.render.TextureFilter;
import dev.bbsae.client.replay.UIReplayModePanel;
import dev.bbsae.comp.CompBinding;
import dev.bbsae.comp.CompAnimationPreset;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMarker;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompMotion;
import dev.bbsae.comp.CompOutputPreset;
import dev.bbsae.comp.CompParameter;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompRenderJob;
import dev.bbsae.comp.CompShape;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompStyle;
import dev.bbsae.comp.CompTextAnimator;
import dev.bbsae.comp.CompTime;
import dev.bbsae.comp.CompTracker;
import dev.bbsae.comp.CompCurves;
import dev.bbsae.comp.CompositionClip;
import dev.bbsae.client.render.CompRenderContext;
import dev.bbsae.client.ui.CompInput;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IFocusedUIElement;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIStringOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.ui.utils.renderers.TimelineRulerRenderer;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The independent compositor workspace.
 *
 * <p>Everything here is a BBS element: the panel is a {@link UIDashboardPanel}, the layer stack is
 * a {@link UIList}, the timeline is {@link UIKeyframeEditor} with its dope sheet/graph editor and
 * native keyframe context menus, and every button, toggle, number field, color swatch and section
 * comes from BBS. The only custom drawing is the composition viewport itself, which is a viewport
 * rather than a second window toolkit.</p>
 */
public class UICompositorPanel extends UIDashboardPanel
{
    private final UILabel title;
    private final UILabel timecode;
    private final UILabel status;
    private final UILabel renderStatus;

    private final UIButton save;
    private final UIButton play;
    private final UIButton renderMode;
    private final UIButton projectsButton;
    private UIEffectLibraryOverlay effectLibraryOverlay;
    private final UIButton exportFilm;
    private final UIButton replaySelector;
    private final UIIcon focusButton;
    private final UIButton settingsButton;
    private final UIIcon prevFrame;
    private final UIIcon nextFrame;
    private final UIIcon addLayer;

    private final UIElement header;
    private UIElement body;
    /** 合成 / 回放 workspace switch, directly under the header. */
    private final UIElement modeBar;
    private final UICompTabBar modeTabs;
    private final UICompIconButton replayPrev;
    private final UICompIconButton replayNext;
    private final UICompIconButton replayAll;
    private final UICompIconButton replayModels;
    private final UICompIconButton replayOriginal;
    private final UICompButton replayPlayButton;
    private final UILabel replayTimecode;
    private final UIReplayModePanel replayWorkspace;
    private final UiPageTransition workspaceMotion = new UiPageTransition(0.22F, 6F);
    private int workspaceMode;
    private static final int MODE_BAR_H = 22;
    private static final int WORKSPACE_COMPOSITE = 0;
    private static final int WORKSPACE_REPLAY = 1;
    private final UIElement timeline;
    private final UIElement timelineHeader;
    private final UIElement effectColumn;
    private final UICompTabBar leftTabs;
    private final UIEffectLibrary effectLibrary;
    private final UIScrollView assetPanel;
    private final UICompPresetBrowser presetBrowser;
    private UICompTabBar timelineTabs;
    private UICompCurveEditor curveEditor;
    private int timelinePage;
    private final List<UIElement> trackOnlyTools = new ArrayList<>();
    private final UIElement preview;
    /** Cancel hook for the preview's own drag gestures (layer/gizmo/camera handles). */
    private java.util.function.BooleanSupplier previewGestureCancel;
    private final UIElement previewBar;
    private final UILabel previewInfo;
    private final UIElement inspectorColumn;
    private final UICompTabBar inspectorTabs;
    private final UIScrollView inspector;
    private final UIKeyframeEditor keyframeEditor;
    private final UIIcon keyframeRowUp;
    private final UIIcon keyframeRowDown;
    private final UICompTrackTimeline trackTimeline;
    private final UIElement keyframeDrawer;
    private final UILabel keyframeTitle;
    private final UICompButton keyframeCurve;
    private final UIButton snapButton;
    private final UIButton keyframeButton;
    private UICompButton precacheButton;
    private final UIElement renderPanel;
    private UIAssetBrowser assetBrowser;
    private UIImportBrowser importBrowser;
    private UILayerLibrary layerLibrary;
    private final UIElement modalLayer;
    private final UIElement modalBox;
    private final UILabel modalTitle;
    private final UILabel modalHint;
    /** Shared panel entrance/breathing clock; child widgets own their own channels. */
    private final UiMotion panelMotion = new UiMotion();
    private UICompSettings settingsOverlay;
    private UIOnboarding onboarding;

    private final UICompRenderJobList queue;
    /** Output module (video or audio) chosen for the next job; shows the preset's real name. */
    private final mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> queuePreset;
    private final UITextbox queueName;
    private final UITrackpad queueStart;
    private final UITrackpad queueEnd;
    private final UIButton queueAdd;
    private final UIButton queueStartButton;
    private final UIButton queueStartAll;
    private final UIButton queueCancel;
    private final UIButton queueOpen;
    private final UIButton queuePresetCycle;
    private final UIButton queueRemove;
    private final UIButton queueRetry;

    private CompProject project;
    private CompDocument doc;
    private CompLayer selectedLayer;
    /** Multi-selection, in click order; {@link #selectedLayer} is always its primary member. */
    private final java.util.LinkedHashSet<String> selectedIds = new java.util.LinkedHashSet<>();
    private CompRenderJob selectedJob;
    private dev.bbsae.comp.CompAudio selectedAudio;
    private mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel;
    private mchorse.bbs_mod.film.Film film;
    private mchorse.bbs_mod.film.replays.Replay replay;
    private final List<String> filmIds = new ArrayList<>();
    private float time;
    private boolean playing;
    /** Monotonic timestamp of the previous playback frame; 0 means "first frame after resume". */
    private long lastPlayNanos;
    private boolean renderView;
    private int presetIndex;
    private long lastAutoSaveMs;
    private long lastSnapshotMs;
    private boolean resetTimelineView = true;
    private int exportWidth = 1920;
    private int exportHeight = 1080;
    private int exportFps = 30;
    private UITrackpad exportDurationField;
    private UITrackpad settingsDurationField;
    private int primedWidth = -1;
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    /** True when the document changed since the last snapshot; keeps idle frames off the JSON path. */
    private boolean documentDirty = true;
    /** A write is pending; see {@link #save()}. */
    private boolean saveRequested;
    private long saveRequestedMs;
    private static final long SAVE_DEBOUNCE_MS = 600L;
    /** Throttles the camera inspector rebuild while the wheel is spinning. */
    private long lastCameraRefreshMs;
    private int previewInfoTick;
    private long undoChars;
    private long redoChars;
    /** Snapshot budget: a 20-layer project snapshot is ~1 MB, so this is ~30 steps of real history. */
    private static final long SNAPSHOT_BUDGET = 32L * 1024L * 1024L;
    private String lastSnapshot = "";
    private boolean restoring;
    /** Set by beginAction; the next render records the post-command baseline without pushing it. */
    private boolean actionInProgress;
    private int primedHeight = -1;
    private boolean buildingInspector;
    private int inspectorPage = PAGE_EFFECTS;
    private boolean focusMode;
    private boolean previewEffects = true;
    private boolean previewMasks = true;
    private boolean previewControlsCollapsed;
    /** Look through the active 3D camera instead of the default front view. */
    private boolean cameraPreviewMode;
    /** Debug-view override for the 3D gizmo mode; -1 = unset. */
    private int previewGizmoModeOverride = -1;
    /** Previous BBS onion-skin state, non-null while the compositor has suppressed it. */
    private Boolean onionSkinRestore;
    private boolean debugProjectLoaded;
    /** BBS-style camera flight: WASD/mouse free look, F enters and exits. */
    private boolean cameraFlying;
    /** True while the AE camera tool owns a mouse drag (Esc checks it before the generic ladder). */
    private boolean cameraToolDragging;
    private boolean cameraToolCancelRequested;
    private CompLayer flightCamera;
    private int flightLastMouseX = Integer.MIN_VALUE;
    private int flightLastMouseY = Integer.MIN_VALUE;
    private long flightLastNanos;
    private float flightSpeed = 780F;
    /** The Blender-like free viewport is open (see {@link #enter3DSpace()}). */
    private boolean viewportMode;
    /** Pen tool instance; created lazily by {@link #penTool()}. */
    private UIPenTool penTool;
    /** Preview geometry captured for the pen overlay; the marquee needs it again on release. */
    UIPenTool.View lastPenView;
    /** Toolbar toggle for the pen tool (mask/shape path editing in 2D and 3D). */
    private UIIcon penButton;
    /** The editor's own camera; never rendered into a frame. */
    private final dev.bbsae.client.render.CompViewportCamera viewportCamera = new dev.bbsae.client.render.CompViewportCamera();
    /** Red "AE 编码器" chip over the preview; visible only while the frame server drives the view. */
    private UIElement encoderBadge;
    /** True while the preview is auditioning the composition's mix. */
    private boolean previewAudioRunning;
    private UIToggle effectsToggle;
    private UIToggle masksToggle;
    private UIToggle bbsBackdropToggle;
    /** Preview-bar shortcut for the project texture-filter policy (auto/linear/nearest). */
    private UICompButton previewFilter;
    /** The three explicit options shown when the filter chip is expanded. */
    private UICompButton previewFilterAuto;
    private UICompButton previewFilterLinear;
    private UICompButton previewFilterNearest;
    /** The dropdown surface under the filter chip. */
    private UIElement previewFilterMenu;
    private boolean previewFilterExpanded;
    /** Last preview width seen by {@link #layoutPreviewBar(int)}; used by state-only re-layouts. */
    private int lastPreviewWidth = 300;
    private UIIcon cameraViewButton;
    private UIIcon space3DButton;
    /** AE camera tool toggle (left orbit / middle track XY / right dolly). */
    private UIIcon cameraToolButton;
    /** Explicit "new camera" action; the only way a camera is created besides the compat switch. */
    private UIIcon createCameraButton;
    /** Opens Settings → 开发者, where the 3D logic and camera defaults live. */
    private UIIcon cameraSettingsButton;
    /** Switches to the entity-replay editing page (BBS's own replay timeline + gizmo + models). */
    private UIIcon replayModeButton;
    private UIIcon precompBack;
    private final UILabel compPath;
    private final java.util.ArrayDeque<PrecompReturn> precompStack = new java.util.ArrayDeque<>();

    private static final class PrecompReturn
    {
        final CompDocument doc;
        final String layerId;
        final float time;
        final double viewShift;
        final double viewZoom;

        PrecompReturn(CompDocument doc, String layerId, float time, double viewShift, double viewZoom)
        {
            this.doc = doc;
            this.layerId = layerId;
            this.time = time;
            this.viewShift = viewShift;
            this.viewZoom = viewZoom;
        }
    }
    private UIIcon previewCollapse;
    private int leftPage;
    private boolean keyframeOpen;
    /**
     * User's intent for the drawer, independent of whether the current layout has room to show
     * it. The layout grows the timeline panel to make room; only when even that is impossible does
     * the drawer render at the height the panel allows, and this flag keeps the request so a later
     * resize can reveal it.
     */
    private boolean keyframeOpenRequested;
    /** doc.id + primary layer id of the sheets currently in the drawer (selection carry gate). */
    private String lastSheetsIdentity = "";
    private boolean keyframeViewNeedsFit = true;
    private float playRate;
    private String statusMessage = "";
    private int statusTicks;
    /** Throttles the background-export status line shown on the edit page. */
    private long lastExportStatusMs;
    private float lastInspectorTime = Float.NaN;
    private final List<Runnable> inspectorBindings = new ArrayList<>();
    private final Map<String, Boolean> sectionFolds = new HashMap<>();
    private final LinkedList<String> recentEffects = new LinkedList<>();

    private static final int PAGE_EFFECTS = 0;
    private static final int PAGE_GRADE = 1;
    private static final int PAGE_LAYER = 2;
    private static final int PAGE_MASK = 3;
    private static final int PAGE_TEXT = 4;
    private static final int PAGE_COMP = 5;
    private static final int PAGE_OUTPUT = 6;

    /** Inspector tab index → page id. Must match the addTab() order in the constructor. */
    private static final int[] INSPECTOR_TAB_PAGES = {
        PAGE_GRADE, PAGE_LAYER, PAGE_MASK, PAGE_EFFECTS, PAGE_TEXT, PAGE_COMP, PAGE_OUTPUT
    };

    private static int inspectorTabForPage(int page)
    {
        for (int index = 0; index < INSPECTOR_TAB_PAGES.length; index++)
        {
            if (INSPECTOR_TAB_PAGES[index] == page)
            {
                return index;
            }
        }

        return 3;
    }

    private static final int TIMELINE_HEIGHT = 250;

    /* One-shot page switch motion (left tabs / inspector page / timeline page). The veil hides the
     * hard cut without animating BBS controls' alpha; see UiPageTransition. */
    private final UiPageTransition leftPageMotion = new UiPageTransition(0.16F, 6F);
    private final UiPageTransition inspectorPageMotion = new UiPageTransition(0.16F, 6F);
    private final UiPageTransition timelinePageMotion = new UiPageTransition(0.18F, 8F);

    /* Last size the three-column layout was computed for. The panel is laid out once during bind(),
     * when the dashboard has not given it a real area yet, so the body kept its 160 px floor and a
     * blank strip sat between the preview and the timeline until something forced a re-layout
     * (dragging a splitter). Re-applying when the area actually changes removes that whole class of
     * "only looks right after you touch it" bug. */
    private int lastLayoutWidth = -1;
    private int lastLayoutHeight = -1;
    private UILayerQuickPanel quickPanel;
    private int quickPanelWidth = 210;
    private int leftPanelWidth = 190;
    private int rightPanelWidth = 300;
    private int timelinePanelHeight = 250;
    private PanelResizeHandle leftHandle;
    private PanelResizeHandle rightHandle;
    private PanelResizeHandle timelineHandle;
    private PanelResizeHandle keyframeHandle;
    /** One-shot status for "AE depth effects degraded" (depth_* without world depth). */
    private String depthDegradedNotice;

    /* BBS-native dockable panel layout. Null when the legacy fixed three-column splitter layout is
     * used (Settings → 开发者 → 停靠式面板, read when the panel is constructed). */
    private UIDockLayout dock;
    private CompDockSource dockSource;
    /** Header button: left click locks/unlocks the dock, right click opens the layout menu. */
    private UIButton layoutButton;
    private boolean dockLayoutEnabled;
    /**
     * Top inset of the timeline content in dock mode.
     *
     * <p>BBS's dock splitter handle is 14 px wide and centred on the seam, so 7 px of it sit inside
     * each panel. The timeline's toolbar starts at y=0/3, which meant the top few pixels of its
     * buttons belonged to the splitter (a press there resized the panel instead of pressing the
     * button). Keeping the content 6 px below the panel edge removes that overlap without touching
     * BBS's dock internals.</p>
     */
    private static final int TIMELINE_DOCK_INSET = 6;
    private static final int TIMELINE_HEADER_H = 26;
    /** Dock structure/visibility changed this frame; the source is flushed once per frame. */
    private boolean dockLayoutDirty;
    private long lastDockFlushMs;
    private static final long DOCK_FLUSH_THROTTLE_MS = 250L;
    private boolean flushingDock;
    /** Snapshot used by the transient focus mode (preview-only tree). */
    private mchorse.bbs_mod.settings.values.ui.EditorLayoutNode focusReturnRoot;
    private java.util.Set<String> focusReturnHidden;
    /** Guards the dock flex pass against re-entering resize() through the dock's own children. */
    private boolean layoutPass;
    private int keyframeDrawerHeight = 154;
    /** 0 = fully below the timeline, 1 = fully open; drives the slide-up drawer animation. */
    private float keyframeDrawerSlide;
    private long keyframeSlideNanos;
    private static final int KEYFRAME_DRAWER_MIN = 96;
    /** Guards the "make the timeline tall enough for the drawer" pass against re-entry. */
    private boolean drawerSpacePass;
    /** The too-short status line is emitted once per open attempt, not on every layout pass. */
    private boolean drawerSpaceWarned;
    /** Last (timeline height, required height, dock height) the grow pass already tried. */
    private int drawerGrowTriedHeight = Integer.MIN_VALUE;
    private int drawerGrowTriedRequired = -1;
    private int drawerGrowTriedBody = -1;
    /** Fallback row height when the track timeline has not been built/laid out yet. */
    private static final int KEYFRAME_TRACK_ROW_FALLBACK = 26;
    private static final int KEYFRAME_TRACK_RULER = 20;
    private UIStartScreen startScreen;
    private boolean startScreenOpen;
    private int pendingScheme;

    public UICompositorPanel(UIDashboard dashboard)
    {
        super(dashboard);

        CompPrefs.Data preferences = CompPrefs.get();

        this.pendingScheme = preferences.shortcutScheme;
        CompUi.configure(preferences.uiDensity);

        this.title = new UILabel(IKey.constant("BBS 合成器"));
        this.title.w(92).h(20);


        /* Header: one accent action (播放), everything else flat text or an icon. The old build
         * painted seven primary-coloured blocks across the title bar, which is what made the top of
         * the editor read as a control panel instead of a title bar. */
        int control = CompUi.control();
        int inset = Math.max(2, (24 - control) / 2);

        this.replaySelector = CompUi.flat("回放：-", "选择这条合成对应的回放或影片；右键可切到其它影片", this::openReplaySelector);
        this.replaySelector.context(this::replayContextMenu);

        this.projectsButton = CompUi.flat("工程", "工程列表：保存当前工程并回到选择工程页面（也可在设置中打开）", () ->
        {
            this.save();
            this.showStartScreen();
        });

        this.save = CompUi.flat("保存", "把工程写入磁盘（Ctrl+S）；工程在 config/bbsae/projects", this::saveNow);

        this.addLayer = CompUi.iconButton(Icons.ADD, "打开图层库（纯色/文字/形状/媒体/界面/摄像机…）", this::showAddLayerMenu);

        this.play = CompUi.primary("播放", "播放 / 暂停合成预览（空格）", this::togglePlay);

        this.prevFrame = CompUi.iconButton(Icons.FRAME_PREV, "上一帧（←）", () -> this.stepFrame(-1));
        this.nextFrame = CompUi.iconButton(Icons.FRAME_NEXT, "下一帧（→）", () -> this.stepFrame(1));

        this.timecode = new UILabel(IKey.constant("00:00:00:00"));
        this.timecode.w(94).h(control);

        this.exportFilm = CompUi.flat("导出", "用 BBS 原生录像器把这条合成录成视频；长度取设置里的导出时长", this::exportFilm);

        this.renderMode = CompUi.flat("渲染", "打开渲染队列：批量输出、PNG 序列、ProRes/H.264 预设", () -> this.toggleRenderView());

        this.focusButton = CompUi.iconButton(Icons.MAXIMIZE, "聚焦预览：把预览铺满合成器面板（F 再按还原）", () -> this.toggleFocusMode());

        this.settingsButton = CompUi.flat("设置", "合成器设置：导出时长、默认图层时长、界面密度、快捷键方案", () -> this.showSettings());

        this.layoutButton = CompUi.flat("布局",
            "面板布局：左键锁定/解锁拖拽；右键菜单可重置布局/隐藏面板/撤销布局改动", () ->
        {
            if (this.dock != null)
            {
                this.toggleDockLock();
            }
            else
            {
                this.setStatus("当前是旧版固定分栏：可在 设置 → 开发者 → 停靠式面板 启用后重开面板");
            }
        });
        this.layoutButton.context((manager) -> this.dockLayoutMenu(manager));

        this.header = new UIElement();
        this.header.h(24);

        this.title.relative(this.header).x(8).y(inset).w(86).h(control);
        this.replaySelector.relative(this.header).x(100).y(inset).w(150).h(control);
        this.play.relative(this.header).x(256).y(inset).w(44).h(control);
        this.prevFrame.relative(this.header).x(302).y(inset).w(control).h(control);
        this.nextFrame.relative(this.header).x(302 + control + 2).y(inset).w(control).h(control);
        this.timecode.relative(this.header).x(302 + control * 2 + 8).y(inset).w(86).h(control);
        this.projectsButton.relative(this.header).x(1F, -396).y(inset).w(54).h(control);
        this.save.relative(this.header).x(1F, -336).y(inset).w(48).h(control);
        this.exportFilm.relative(this.header).x(1F, -248).y(inset).w(54).h(control);
        this.renderMode.relative(this.header).x(1F, -190).y(inset).w(54).h(control);
        this.settingsButton.relative(this.header).x(1F, -132).y(inset).w(48).h(control);
        this.focusButton.relative(this.header).x(1F, -40).y(inset).w(control).h(control);

        this.header.add(this.title, this.replaySelector, this.play, this.prevFrame, this.nextFrame,
            this.timecode, this.projectsButton, this.save, this.exportFilm,
            this.renderMode, this.settingsButton, this.layoutButton, this.focusButton);

        /* One mode bar for the whole workspace. The replay editor used to be a second dashboard
         * page with its own header and its own visual language; it is the same editor with the same
         * chrome now, just a different body. */
        this.modeBar = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF12161D);
                context.batcher.box(this.area.x, this.area.ey() - 1, this.area.ex(), this.area.ey(), 0xFF2A303A);
                super.render(context);
            }
        };
        this.modeBar.relative(this).x(0).y(24).w(1F).h(MODE_BAR_H);

        this.replayWorkspace = new UIReplayModePanel(this.dashboard);
        this.replayWorkspace.setEmbedded(true);
        this.replayWorkspace.relative(this).x(0).y(24 + MODE_BAR_H).w(1F)
            .h(1F, -24 - MODE_BAR_H - 18);
        this.replayWorkspace.setVisible(false);
        this.replayWorkspace.setEnabled(false);

        this.modeTabs = new UICompTabBar();
        this.modeTabs.relative(this.modeBar).x(8).y(2).w(112).h(18);
        this.modeTabs.addTab("合成");
        this.modeTabs.addTab("回放");
        this.modeTabs.active(() -> this.workspaceMode);
        this.modeTabs.onSelect((index) -> this.switchWorkspace(index == 1 ? WORKSPACE_REPLAY : WORKSPACE_COMPOSITE));

        this.replayPlayButton = CompUi.flat("播放", "回放：播放 / 暂停", () -> this.replayWorkspace.togglePlaybackFromHost());
        this.replayPlayButton.relative(this.modeBar).x(1F, -184).y(2).w(52).h(18);
        this.replayTimecode = new UILabel(IKey.constant("00:00:00:00"));
        this.replayTimecode.color(0xFFD8DEE9, false);
        this.replayTimecode.labelAnchor(1F, 0.5F);
        this.replayTimecode.relative(this.modeBar).x(1F, -262).y(2).w(70).h(18);
        this.replayPrev = CompUi.iconButton(Icons.FRAME_PREV, "回放：上一帧", () -> this.replayWorkspace.stepFromHost(-1));
        this.replayNext = CompUi.iconButton(Icons.FRAME_NEXT, "回放：下一帧", () -> this.replayWorkspace.stepFromHost(1));
        this.replayAll = CompUi.iconButton(Icons.MAXIMIZE, "回放：框选全部模型", () -> this.replayWorkspace.frameAllFromHost());
        this.replayModels = CompUi.iconButton(Icons.PLAYER, "回放：打开模型库", () -> this.replayWorkspace.openModelLibraryFromHost());
        this.replayOriginal = CompUi.iconButton(Icons.FILM, "回放：切到 BBS 原版回放编辑", () -> this.replayWorkspace.openOriginalEditorFromHost());
        this.replayPrev.relative(this.modeBar).x(1F, -128).y(2).w(18).h(18);
        this.replayNext.relative(this.modeBar).x(1F, -106).y(2).w(18).h(18);
        this.replayAll.relative(this.modeBar).x(1F, -84).y(2).w(18).h(18);
        this.replayModels.relative(this.modeBar).x(1F, -62).y(2).w(18).h(18);
        this.replayOriginal.relative(this.modeBar).x(1F, -40).y(2).w(18).h(18);

        this.modeBar.add(this.modeTabs, this.replayPlayButton, this.replayTimecode,
            this.replayPrev, this.replayNext, this.replayAll, this.replayModels, this.replayOriginal);

        /* Left column: the two panels an editor actually browses from - effects and media.
         * The layer stack lives in the timeline's track headers; keeping a second layer list
         * here would be a second source of truth for selection and z-order. */
        this.effectLibrary = new UIEffectLibrary(this);
        this.effectLibrary.relative(this.effectColumn = new UIElement()).x(0).y(22).w(1F).h(1F, -22);

        this.assetPanel = new UIScrollView(ScrollDirection.VERTICAL);
        /* 6 px right inset: the dock splitter hot zone is 7 px wide and would otherwise eat the scrollbar drag. */
        this.assetPanel.relative(this.effectColumn).x(0).y(22).w(1F, -6).h(1F, -22);
        this.assetPanel.column(4).vertical().stretch().scroll().padding(4);
        this.assetPanel.setVisible(false);

        UISection assetSection = new UISection(IKey.constant("素材"));

        assetSection.fields.add(UI.label(IKey.constant("按分类、搜索和缩略图浏览项目素材与游戏素材。"), 16));
        assetSection.fields.add(new UICompButton(IKey.constant("打开素材库…"), (button) -> this.openAssetBrowser(false)));
        assetSection.fields.add(new UICompButton(IKey.constant("导入图片到所选轨道…"), (button) ->
        {
            if (this.selectedLayer == null)
            {
                this.setStatus("先在时间轴选择一条轨道");
                return;
            }

            this.importImage(this.selectedLayer);
        }));
        assetSection.fields.add(new UICompButton(IKey.constant("替换所选轨道素材…"), (button) ->
        {
            if (this.selectedLayer == null)
            {
                this.setStatus("先在时间轴选择一条轨道");
                return;
            }

            this.openAssetBrowser(true);
        }));
        assetSection.fields.add(new UICompButton(IKey.constant("导入视频…"), (button) -> this.importVideo())
            .tooltip(IKey.constant("把 mp4/mov/mkv/webm 拆成逐帧图片，并新建一条 footage 轨道（按合成帧率、合成尺寸）")));
        this.assetPanel.add(assetSection);

        this.leftTabs = new UICompTabBar();
        /* 6 px dock-splitter inset on the right: the tab bar's "»" overflow control otherwise sits
         * under the splitter hot zone and a click there starts a panel drag. */
        this.leftTabs.relative(this.effectColumn).x(0).y(0).w(1F, -22).h(22);
        this.presetBrowser = new UICompPresetBrowser(this);
        this.presetBrowser.relative(this.effectColumn).x(0).y(22).w(1F).h(1F, -22);
        this.presetBrowser.setVisible(false);

        this.leftTabs.addTab("效果库");
        this.leftTabs.addTab("素材");
        this.leftTabs.addTab("预设");
        this.leftTabs.onSelect((index) -> this.applyLeftPage(index, true));
        this.leftTabs.active(() -> this.leftPage);

        /* The reference-style browser (thumbnails, categories) stays reachable from the left
         * column instead of the removed header button. */
        UIIcon effectGallery = new UIIcon(Icons.IMAGE, (icon) -> this.openEffectLibrary());
        effectGallery.relative(this.effectColumn).x(1F, -20).y(2).w(18).h(18);
        effectGallery.tooltip(IKey.constant("打开带预览图的效果库（单击效果即可叠加到当前轨道）"));

        this.effectColumn.w(190).h(1F);
        this.effectColumn.add(this.leftTabs, effectGallery, this.effectLibrary, this.assetPanel, this.presetBrowser);

        this.preview = new UIElement()
        {
            private static final int DRAG_NONE = 0;
            private static final int DRAG_MOVE = 1;
            private static final int DRAG_CORNER = 2;
            private static final int DRAG_EDGE = 3;
            private static final int DRAG_ROTATE = 4;
            private static final int DRAG_CAMERA_BODY = 5;
            private static final int DRAG_CAMERA_TARGET = 6;
    private static final int DRAG_GIZMO = 7;
    private static final int DRAG_BOX_3D = 8;

    private static final int GIZMO_MOVE = 0;
    private static final int GIZMO_SCALE = 1;
    private static final int GIZMO_ROTATE = 2;
    /** A scale drag at the centre square changes all three axes. */
    private static final int GIZMO_UNIFORM = 3;

            private int dragMode = DRAG_NONE;
            private int dragHandle;
            private int dragStartX;
            private int dragStartY;
            private float layerStartX;
            private float layerStartY;
            private float targetStartX;
            private float targetStartY;
            private float startScaleX;
            private float startScaleY;
            private float startRotation;
            private float startLocalX;
            private float startLocalY;
            private float startAngle;
            private final Matrix4f startWorld = new Matrix4f();
            private final Matrix4f startInverse = new Matrix4f();
            /** A 3D layer seen edge-on has no usable plane hit; the screen-distance fallback runs. */
            private boolean startPlaneValid;
            private float startScreenCenterX;
            private float startScreenCenterY;
            private float startScreenDistance;
            private float startScreenAngle;
            private float cameraStartTargetX;
            private float cameraStartTargetY;
            private float cameraStartTargetZ;
            /** Full press-time pose snapshot: Esc must really restore every field a drag writes. */
            private float startZ;
            private float startRotationX;
            private float startRotationY;
            private float startScaleZ = 1F;

            {
                /* The panel's Esc handler cannot see this anonymous class; register a cancel hook
                 * so a preview drag can be aborted instead of letting Esc clear the selection
                 * while the mouse is still down. */
                UICompositorPanel.this.previewGestureCancel = this::cancelActiveLayerGesture;
            }

            /**
             * Esc during a layer/gizmo/camera-handle drag: restore the press-time transform, drop
             * the gesture's undo checkpoint and keep the selection. Returns false when nothing is
             * being dragged so the Esc ladder can continue.
             */
            private boolean cancelActiveLayerGesture()
            {
                if (this.dragMode == DRAG_NONE)
                {
                    return false;
                }

                CompLayer layer = this.gizmoLayerId == null || UICompositorPanel.this.doc == null
                    ? null : UICompositorPanel.this.doc.getLayer(this.gizmoLayerId);

                if (layer != null)
                {
                    this.writeTransform(layer, this.startScaleX, this.startScaleY, this.startRotation,
                        this.layerStartX, this.layerStartY);

                    if (layer.threeD)
                    {
                        UICompositorPanel.this.setScalar(layer, "z", this.startZ);
                        UICompositorPanel.this.setScalar(layer, "rotationX", this.startRotationX);
                        UICompositorPanel.this.setScalar(layer, "rotationY", this.startRotationY);
                        UICompositorPanel.this.setScalar(layer, "scaleZ", this.startScaleZ);
                    }

                    if (this.dragMode == DRAG_CAMERA_BODY || this.dragMode == DRAG_CAMERA_TARGET)
                    {
                        UICompositorPanel.this.setScalar(layer, "cameraTargetX", this.cameraStartTargetX);
                        UICompositorPanel.this.setScalar(layer, "cameraTargetY", this.cameraStartTargetY);
                        UICompositorPanel.this.setScalar(layer, "cameraTargetZ", this.cameraStartTargetZ);
                    }
                }

                this.dragMode = DRAG_NONE;
                this.gizmoAxisIndex = -1;
                this.gizmoLayerId = null;
                UICompositorPanel.this.refreshInspector();
                UICompositorPanel.this.refreshSheets();
                UICompositorPanel.this.discardCancelledAction();
                UICompositorPanel.this.setStatus("已取消拖动（Esc）");

                return true;
            }

            /* AE camera tool drag state. The tool turns the three mouse buttons into AE's three
             * camera gestures, so it must not share the layer drag modes above: those are written
             * by the click handler, while a camera gesture starts from raw button polling (the
             * right button is also what BBS wants to use for context menus). */
            private static final int CT_NONE = 0;
            private static final int CT_ORBIT = 1;
            private static final int CT_TRACK_XY = 2;
            private static final int CT_TRACK_Z = 3;
            private int ctMode = CT_NONE;
            private int ctButton = -1;
            private int ctStartX;
            private int ctStartY;
            private float ctStartDistance;
            private float ctStartRadius;
            private float ctStartZoom = 1F;
            private boolean ctStartLookAt;
            private final org.joml.Vector3f ctStartEye = new org.joml.Vector3f();
            private final org.joml.Vector3f ctStartTarget = new org.joml.Vector3f();
            private final org.joml.Vector3f ctStartRight = new org.joml.Vector3f(1F, 0F, 0F);
            private final org.joml.Vector3f ctStartUp = new org.joml.Vector3f(0F, 1F, 0F);
            private boolean ctLeftWasDown;
            private boolean ctMiddleWasDown;
            private boolean ctRightWasDown;

            /* 3D translate gizmo drag. Axis direction and pixels-per-unit are captured when the
             * drag starts so the layer follows the pointer even while the camera moves. */
            private int gizmoAxisIndex = -1;
            private String gizmoLayerId;
            private int gizmoStartMouseX;
            private int gizmoStartMouseY;
            private float gizmoStartValue;
            private float gizmoDirX;
            private float gizmoDirY;
            private float gizmoPixelsPerUnit;
            private int gizmoMode = GIZMO_SCALE;
            private float gizmoStartScaleX;
            private float gizmoStartScaleY;
            private float gizmoStartScaleZ;
            private float gizmoStartAngle;
            private float gizmoStartAxisRotation;
            private float gizmoOriginX;
            private float gizmoOriginY;

            /* 3D viewport navigation state. Absolute snapshots are used instead of incremental
             * deltas: applying "delta since the last event" to a camera that already moved makes
             * orbiting accelerate away, which is the classic broken-orbit feel. */
            /* Raw button state, polled every frame. BBS's UI event routing is not guaranteed to
             * deliver non-left buttons to a custom element (and a right click also opens context
             * menus), so navigation cannot depend on subMouseClicked alone. Polling the window is
             * what makes "right drag orbits" work everywhere, including look-through. */
            private boolean vpRightWasDown;
            private boolean vpMiddleWasDown;
            private boolean vpLeftWasDown;
            /** The current drag started on empty canvas (so a click without movement deselects). */
            private boolean vpFromEmpty;
            private boolean vpMoved;

            private static final int VP_NONE = 0;
            private static final int VP_ORBIT = 10;
            private static final int VP_PAN = 11;
            private static final int VP_MOVE = 12;
            private static final int VP_CAM_BODY = 13;
            private static final int VP_CAM_TARGET = 14;
            private static final int VP_CAM_ORBIT = 15;
            private int vpMode = VP_NONE;
            private int vpButton;
            private int vpStartX;
            private int vpStartY;
            private float vpStartYaw;
            private float vpStartPitch;
            private final org.joml.Vector3f vpStartTarget = new org.joml.Vector3f();
            private final org.joml.Vector3f vpStartEye = new org.joml.Vector3f();
            private final org.joml.Vector3f vpShotTarget = new org.joml.Vector3f();
            private String vpCameraId;
            /** Start x/y of every selected layer, for a multi-layer drag. */
            private final java.util.LinkedHashMap<String, float[]> dragStarts = new java.util.LinkedHashMap<>();

            /* Mask point editing. The preview already knows how to project a layer-local point to
             * the screen (selectionCorners uses the same CompScene.project path), so the mask
             * overlay and its hit test reuse that coordinate system instead of inventing a second
             * one. */
            private static final int MASK_DRAG_NONE = 0;
            private static final int MASK_DRAG_POINT = 1;
            private static final int MASK_DRAG_IN = 2;
            private static final int MASK_DRAG_OUT = 3;
            private static final int MASK_DRAG_MOVE = 4;

            private int maskDrag = MASK_DRAG_NONE;
            private int maskEditMask = -1;
            private int maskEditPoint = -1;
            private int maskEditHandle = 0;
            private int maskDragMask = -1;
            private int maskDragPoint = -1;
            private int maskDragStartX;
            private int maskDragStartY;
            private boolean maskActionStarted;
            private final java.util.List<float[]> maskStartPoints = new java.util.ArrayList<>();

            @Override
            public void render(UIContext context)
            {
                this.pollViewportInput(context);
                this.pollCameraToolInput(context);
                this.applyDrag(context);
                this.renderBackground(context);

                if (UICompositorPanel.this.is3DSpace())
                {
                    /* One overlay for the whole 3D workspace: it projects through the viewport
                     * camera, so it is not the 2D safe-area/guides/selection path. */
                    this.renderViewportOverlay(context);
                }
                else
                {
                    this.renderSafeArea(context);
                    this.renderGuides(context);
                    this.renderSelection(context);

                    /* The pen overlay draws every masked path itself (closed or open, with its own
                     * anchor/handle state), so the old mask overlay would double-draw the same
                     * outlines while the tool is armed. */
                    if (!UICompositorPanel.this.penTool().isActive())
                    {
                        this.renderMaskOverlay(context);
                    }
                }

                /* Pen overlay on top of both preview modes: 2D and the free 3D workspace use the
                 * same drawing code, only the projection differs. */
                UIPenTool.View penView = this.penView();
                UICompositorPanel.this.lastPenView = penView;
                UICompositorPanel.this.penTool().renderOverlay(context, penView);

                this.updateCursor(context);
                super.render(context);
                this.renderStageFrame(context);
            }

            /**
             * Premium stage edge: four corner brackets instead of a full border. It frames the
             * picture without competing with the 2D guides, the camera gizmo or the floating
             * preview bar, and it is only four quads.
             */
            private void renderStageFrame(UIContext context)
            {
                if (!CompPrefs.get().glow || this.area.w < 160 || this.area.h < 100)
                {
                    return;
                }

                int accent = BBSSettings.primaryColor() | 0xFF000000;
                int len = 13;
                float x0 = this.area.x + 4;
                float y0 = this.area.y + 4;
                float x1 = this.area.ex() - 4;
                float y1 = this.area.ey() - 4;
                float damp = 0.22F + 0.06F * (float) Math.sin(UICompositorPanel.this.panelMotion.time * 1.1F);
                int color = UiGfx.alpha(accent, damp);

                context.batcher.box(x0, y0, x0 + len, y0 + 1, color);
                context.batcher.box(x0, y0, x0 + 1, y0 + len, color);
                context.batcher.box(x1 - len, y0, x1, y0 + 1, color);
                context.batcher.box(x1 - 1, y0, x1, y0 + len, color);
                context.batcher.box(x0, y1 - 1, x0 + len, y1, color);
                context.batcher.box(x0, y1 - len, x0 + 1, y1, color);
                context.batcher.box(x1 - len, y1 - 1, x1, y1, color);
                context.batcher.box(x1 - 1, y1 - len, x1, y1, color);

                if (UICompositorPanel.this.cameraFlying)
                {
                    context.batcher.box(x0 + len + 8, y0, x1 - len - 8, y0 + 1,
                        UiGfx.alpha(0xFF7AD8FF, 0.38F));
                }
            }

            private void updateCursor(UIContext context)
            {
                if (!this.area.isInside(context))
                {
                    return;
                }

                int cursor = -1;

                if (this.vpMode == VP_MOVE || this.vpMode == VP_CAM_BODY || this.vpMode == VP_CAM_TARGET || this.vpMode == VP_CAM_ORBIT)
                {
                    cursor = org.lwjgl.glfw.GLFW.GLFW_HAND_CURSOR;
                }
                else if (this.vpMode == VP_ORBIT || this.vpMode == VP_PAN)
                {
                    cursor = org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR;
                }
                else if (this.dragMode == DRAG_MOVE)
                {
                    cursor = org.lwjgl.glfw.GLFW.GLFW_HAND_CURSOR;
                }
                else if (this.dragMode == DRAG_ROTATE)
                {
                    cursor = org.lwjgl.glfw.GLFW.GLFW_HAND_CURSOR;
                }
                else if (this.dragMode == DRAG_EDGE)
                {
                    cursor = this.dragHandle == 20 || this.dragHandle == 22
                        ? org.lwjgl.glfw.GLFW.GLFW_VRESIZE_CURSOR
                        : org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR;
                }
                else if (this.dragMode != DRAG_NONE)
                {
                    cursor = org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR;
                }
                else
                {
                    int handle = this.handleAt(context, UICompositorPanel.this.selectedLayer);

                    if (handle == 30)
                    {
                        cursor = org.lwjgl.glfw.GLFW.GLFW_HAND_CURSOR;
                    }
                    else if (handle == 20 || handle == 22)
                    {
                        cursor = org.lwjgl.glfw.GLFW.GLFW_VRESIZE_CURSOR;
                    }
                    else if (handle >= 0)
                    {
                        cursor = org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR;
                    }
                }

                if (cursor >= 0)
                {
                    context.requestCursor(cursor);
                }
            }

            /**
             * Model-editor-ish ground grid for the 3D space. It is drawn in screen space from
             * projected comp-plane points, so it participates in the same camera as the layers
             * without introducing a second projection path.
             */
            private void render3DGrid(UIContext context)
            {
                CompProject project = UICompositorPanel.this.project;
                CompDocument doc = UICompositorPanel.this.doc;

                if (project == null || doc == null || project.settings == null || !project.settings.show3DGrid)
                {
                    return;
                }

                boolean has3D = false;

                for (CompLayer layer : doc.layers)
                {
                    if (layer.threeD && layer.visible && !"camera".equals(layer.type))
                    {
                        has3D = true;
                        break;
                    }
                }

                if (!has3D)
                {
                    return;
                }

                CompRenderContext renderContext = new CompRenderContext(project, doc, UICompositorPanel.this.time, null, null, null);

                /* One camera, the same one the renderer uses: the grid is world points drawn
                 * through it, so it can never drift away from the composition. */
                CompScene.camera(renderContext);

                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return;
                }

                float ox = this.area.x + (this.area.w - doc.width * scale) / 2F;
                float oy = this.area.y + (this.area.h - doc.height * scale) / 2F;
                /* 200px cells and coarse segments: the grid is a reference, not geometry. The old
                 * build emitted one 1x1 quad per screen pixel per line, which is what made the
                 * camera view crawl as soon as a 3D layer existed. */
                float step = 240F;
                int lines = (int) Math.ceil(Math.max(doc.width, doc.height) / step) + 1;

                context.batcher.clip(this.area, context);

                try
                {
                    for (int i = -lines; i <= lines; i++)
                    {
                        float value = i * step;

                        this.draw3DLine(context, renderContext, ox, oy, scale, value, -step, value, doc.height + step, 0x12FFFFFF);
                        this.draw3DLine(context, renderContext, ox, oy, scale, -step, value, doc.width + step, value, 0x12FFFFFF);
                    }

                    /* X/Y axes through the composition origin. */
                    this.draw3DLine(context, renderContext, ox, oy, scale, 0F, 0F, doc.width, 0F, 0x44FF6B6B);
                    this.draw3DLine(context, renderContext, ox, oy, scale, 0F, 0F, 0F, doc.height, 0x4455D68A);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            private void draw3DLine(UIContext context, CompRenderContext renderContext, float ox, float oy, float scale,
                                    float x1, float y1, float x2, float y2, int color)
            {
                org.joml.Vector2f a = CompScene.projectWorld(renderContext, new org.joml.Vector3f(x1, y1, 0F));
                org.joml.Vector2f b = CompScene.projectWorld(renderContext, new org.joml.Vector3f(x2, y2, 0F));

                if (a == null || b == null)
                {
                    return;
                }

                this.coarseLine(context, ox + a.x * scale, oy + a.y * scale, ox + b.x * scale, oy + b.y * scale, color);
            }

            private void renderBackground(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF111419);

                CompProject.CompSettings settings = UICompositorPanel.this.project == null ? null : UICompositorPanel.this.project.settings;
                int grid = settings == null ? 64 : Math.max(8, settings.gridSize);

                if (settings != null && settings.showGrid)
                {
                    for (int x = this.area.x; x < this.area.ex(); x += grid)
                    {
                        context.batcher.box(x, this.area.y, x + 1, this.area.ey(), 0x18FFFFFF);
                    }
                    for (int y = this.area.y; y < this.area.ey(); y += grid)
                    {
                        context.batcher.box(this.area.x, y, this.area.ex(), y + 1, 0x18FFFFFF);
                    }
                }

                if (UICompositorPanel.this.project != null && UICompositorPanel.this.doc != null)
                {
                    /* Keep the BBS camera on the composition playhead even while the panel is
                     * paused: scrubbing the timeline must move the film frame too. A running
                     * point tracker drives the runner itself (frame by frame), so the two must
                     * not fight — the engine's seek wins until the analysis finishes. */
                    if (!dev.bbsae.client.render.TrackerEngine.isRunning())
                    {
                        dev.bbsae.client.overlay.BbsPreviewBridge.sync(UICompositorPanel.this.time);
                    }

                    /* Re-derive the colour/depth capture requests from the document every frame.
                     * The old build only set them from a toggle callback, so a reopened project
                     * with a persisted BBS backdrop (or a depth effect) never asked for a capture
                     * and the preview stayed flat and depth-less. */
                    dev.bbsae.client.overlay.BbsPreviewBridge.syncRequests(UICompositorPanel.this.project, UICompositorPanel.this.doc);

                    MatrixStack stack = context.batcher.getContext().getMatrices();

                    /* The world backdrop is a BBS-mode feature: in AE mode the stage is the
                     * composition's own colour, because the frame that gets encoded must not depend
                     * on what the game happened to draw behind the panel. */
                    if (settings != null && settings.previewBackground == 1 && !UICompositorPanel.this.usesAeEncoder()
                        && !UICompositorPanel.this.is3DSpace())
                    {
                        this.renderBbsBackdrop(context);
                    }

                    if (UICompositorPanel.this.is3DSpace() && !UICompositorPanel.this.cameraPreviewMode)
                    {
                        /* Free viewport: a draft render through the editor camera. This is the
                         * only path that runs while orbiting, and it costs one textured draw per
                         * layer instead of the whole composition graph. */
                        context.batcher.clip(this.area, context);

                        try
                        {
                            CompViewportRenderer.draw(
                                context.batcher,
                                UICompositorPanel.this.project,
                                UICompositorPanel.this.doc,
                                UICompositorPanel.this.viewportCamera,
                                UICompositorPanel.this.time,
                                this.area.x,
                                this.area.y,
                                Math.max(1, this.area.w),
                                Math.max(1, this.area.h)
                            );
                        }
                        finally
                        {
                            context.batcher.unclip(context);
                        }
                    }
                    else
                    {
                        stack.push();
                        stack.translate(this.area.x, this.area.y, 0F);

                        try
                        {
                            if (UICompositorPanel.this.usesAeEncoder())
                            {
                                /* AE mode: the playhead's frame, at composition resolution, from the
                                 * same function the encoder calls. */
                                CompRenderer.renderFrameCached(
                                    UICompositorPanel.this.project,
                                    UICompositorPanel.this.doc,
                                    UICompositorPanel.this.time,
                                    1F,
                                    this.area.w,
                                    this.area.h,
                                    0,
                                    UICompositorPanel.this.encoderProxy(),
                                    context.batcher
                                );
                            }
                            else
                            {
                                /* Cached preview: one textured quad while nothing changed, one render
                                 * per composition frame while playing. */
                                CompRenderer.renderCached(
                                    UICompositorPanel.this.project,
                                    UICompositorPanel.this.doc,
                                    UICompositorPanel.this.time,
                                    1F,
                                    this.area.w,
                                    this.area.h,
                                    0,
                                    UICompositorPanel.this.previewEffects,
                                    UICompositorPanel.this.previewMasks,
                                    UICompositorPanel.this.cameraPreviewMode,
                                    context.batcher
                                );
                            }
                        }
                        catch (RuntimeException e)
                        {
                            dev.bbsae.BbsAeAddon.LOGGER.error("Composition preview failed", e);
                        }

                        stack.pop();
                    }
                }

                if (UICompositorPanel.this.encoderBadge != null)
                {
                    UICompositorPanel.this.encoderBadge.setVisible(UICompositorPanel.this.usesAeEncoder());
                }

                if (UICompositorPanel.this.previewInfo != null)
                {
                    /* The 3D HUD owns the bottom strip; two readouts on top of each other read as
                     * a rendering bug. */
                    UICompositorPanel.this.previewInfo.setVisible(
                        CompPrefs.get().previewHints
                            && !UICompositorPanel.this.is3DSpace() && !UICompositorPanel.this.cameraFlying);
                }

                if (!UICompositorPanel.this.is3DSpace())
                {
                    this.render3DGrid(context);
                }

                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1F, 0xFF2A303A);
                context.batcher.box(this.area.x, this.area.ey() - 1F, this.area.ex(), this.area.ey(), 0xFF2A303A);
                context.batcher.box(this.area.x, this.area.y, this.area.x + 1F, this.area.ey(), 0xFF2A303A);
                context.batcher.box(this.area.ex() - 1F, this.area.y, this.area.ex(), this.area.ey(), 0xFF2A303A);
            }

            /**
             * The live BBS frame behind the composition (Preview → BBS). Cover-scaled into the
             * composition rectangle, because the window and the comp rarely share an aspect ratio.
             */
            private void renderBbsBackdrop(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;

                if (doc == null)
                {
                    return;
                }

                float scale = this.fitScale();
                float w = doc.width * scale;
                float h = doc.height * scale;
                float x = this.area.x + (this.area.w - w) / 2F;
                float y = this.area.y + (this.area.h - h) / 2F;
                dev.bbsae.client.render.GlTexture texture = dev.bbsae.client.overlay.BbsPreviewBridge.texture();

                if (texture == null)
                {
                    context.batcher.box(x, y, x + w, y + h, 0xFF151A21);
                    context.batcher.textShadow("BBS 预览不可用（未打开影片或世界未渲染）", x + 8, y + 8, 0xFFFF8A8A);
                    return;
                }

                /* Contain, not cover: BBS's own preview never crops the film frame, it letterboxes
                 * it. Cover-scaling the world into the comp rectangle silently changed the
                 * framing the director composed — the exact "preview doesn't match BBS" symptom. */
                float contain = Math.min(w / texture.width, h / texture.height);
                float dw = texture.width * contain;
                float dh = texture.height * contain;

                context.batcher.box(x, y, x + w, y + h, 0xFF000000);
                context.batcher.clip((int) x, (int) y, (int) w, (int) h, context);

                try
                {
                    dev.bbsae.client.overlay.BbsPreviewBridge.drawFrame(context.batcher, 0xFFFFFFFF,
                        x + (w - dw) / 2F, y + (h - dh) / 2F, dw, dh);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            private void renderSafeArea(UIContext context)
            {
                if (UICompositorPanel.this.doc == null || UICompositorPanel.this.project == null
                    || UICompositorPanel.this.project.settings == null
                    || !UICompositorPanel.this.project.settings.showSafeArea
                    || !CompPrefs.get().guides)
                {
                    return;
                }

                /* The safe area belongs to the composition rectangle, not to the whole preview
                 * panel. On a non-matching aspect ratio the old version drew it over the
                 * letterbox and looked unrelated to the frame. */
                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return;
                }

                float compW = UICompositorPanel.this.doc.width * scale;
                float compH = UICompositorPanel.this.doc.height * scale;
                float compX = this.area.x + (this.area.w - compW) / 2F;
                float compY = this.area.y + (this.area.h - compH) / 2F;
                float w = compW * 0.9F;
                float h = compH * 0.9F;
                float x = compX + (compW - w) / 2F;
                float y = compY + (compH - h) / 2F;

                context.batcher.box(x, y, x + w, y + 1, 0x22FFFFFF);
                context.batcher.box(x, y + h - 1, x + w, y + h, 0x22FFFFFF);
                context.batcher.box(x, y, x + 1, y + h, 0x22FFFFFF);
                context.batcher.box(x + w - 1, y, x + w, y + h, 0x22FFFFFF);
            }

            private void renderGuides(UIContext context)
            {
                if (!CompPrefs.get().guides || UICompositorPanel.this.doc == null
                    || UICompositorPanel.this.project == null)
                {
                    return;
                }

                float scale = this.fitScale();
                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                CompRenderContext renderContext = new CompRenderContext(
                    UICompositorPanel.this.project,
                    UICompositorPanel.this.doc,
                    UICompositorPanel.this.time,
                    null,
                    null,
                    null
                );

                context.batcher.clip(this.area, context);

                try
                {
                    for (CompLayer layer : UICompositorPanel.this.doc.layers)
                    {
                        /* A camera gizmo is drawn even when the track's eye is off or the playhead
                         * is outside its trim: the camera still drives the view (see
                         * CompDocument#activeCamera), so hiding its gizmo would leave the user with
                         * a transformed picture and no handle to fix it. */
                        if (!"camera".equals(layer.type)
                            && (!layer.activeAt(UICompositorPanel.this.time) || !layer.visible))
                        {
                            continue;
                        }

                        if ("null".equals(layer.type))
                        {
                            org.joml.Vector2f projected = CompScene.project(renderContext, layer, 0F, 0F, 0F);

                            if (projected == null)
                            {
                                continue;
                            }

                            float x = this.area.x + ox + projected.x * scale;
                            float y = this.area.y + oy + projected.y * scale;
                            int color = 0xFF74D6FF;

                            context.batcher.box(x - 7F, y - 1F, x + 7F, y + 1F, color);
                            context.batcher.box(x - 1F, y - 7F, x + 1F, y + 7F, color);
                            context.batcher.box(x - 2F, y - 2F, x + 2F, y + 2F, 0xFFFFFFFF);
                            context.batcher.textShadow("◇ " + UICompositorPanel.this.layerLabel(layer), x + 9F, y - 4F, color);
                        }
                        else if ("camera".equals(layer.type))
                        {
                            this.renderCameraGizmo(context, renderContext, layer, ox, oy, scale);
                        }
                    }
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            /**
             * The camera gizmo, drawn in <b>composition space</b>.
             *
             * <p>The old version projected the gizmo through the camera itself, so the eye always
             * landed on the exact centre of the frame — the icon never moved when the camera was
             * panned, and the frustum lines connected the centre to the four comp corners (which
             * are only correct for a 1:1, unrotated camera). A view is the wrong space for a
             * diagram of the view: the gizmo must live where the camera <em>is</em>, so its
             * position, its hit area and the drag all agree.</p>
             *
             * <p>So: eye and target are comp-space points mapped straight onto the comp rectangle
             * (no camera transform), and the frustum is eye → comp corners. Dragging the body pans,
             * dragging the crosshair aims.</p>
             */
            private void renderCameraGizmo(UIContext context, CompRenderContext renderContext, CompLayer layer, float ox, float oy, float scale)
            {
                if (!CompPrefs.get().cameraGizmos)
                {
                    return;
                }

                CompDocument doc = UICompositorPanel.this.doc;
                CompLayer active = doc.activeCamera(UICompositorPanel.this.time);

                if (active != layer)
                {
                    return;
                }

                CompCamera camera = CompScene.camera(renderContext);
                boolean selected = UICompositorPanel.this.selectedLayer == layer;
                int color = selected ? 0xFFFFC14D : 0xB3FFC14D;
                /* The animated transform, not the base field: an animated camera must move its
                 * gizmo with the picture (see CompLayer#cameraEye). */
                org.joml.Vector2f eye = layer.cameraEye(UICompositorPanel.this.getFrame());
                float eyeX = eye.x;
                float eyeY = eye.y;
                float cx = this.area.x + ox + eyeX * scale;
                float cy = this.area.y + oy + eyeY * scale;

                for (int i = 0; i < 4; i++)
                {
                    float wx = i == 1 || i == 2 ? doc.width : 0F;
                    float wy = i >= 2 ? doc.height : 0F;

                    this.line(context, cx, cy,
                        this.area.x + ox + wx * scale,
                        this.area.y + oy + wy * scale, 0x33FFC14D);
                }

                /* The point of interest is a comp-space point too (that is what cameraTargetX/Y
                 * mean), so it needs no projection and can never end up behind the camera. */
                if (layer.cameraLookAt || camera.lookAt)
                {
                    org.joml.Vector3f target = layer.cameraTarget(UICompositorPanel.this.getFrame());
                    float tx = this.area.x + ox + target.x * scale;
                    float ty = this.area.y + oy + target.y * scale;

                    this.line(context, cx, cy, tx, ty, 0x55FFC14D);
                    context.batcher.box(tx - 6F, ty - 1F, tx + 6F, ty + 1F, color);
                    context.batcher.box(tx - 1F, ty - 6F, tx + 1F, ty + 6F, color);
                    context.batcher.box(tx - 2F, ty - 2F, tx + 2F, ty + 2F, 0xFF101014);
                    context.batcher.textShadow("⌖ 目标", tx + 9F, ty - 4F, color);
                }

                context.batcher.box(cx - 7F, cy - 5F, cx + 7F, cy + 5F, 0xFF101014);
                context.batcher.outline(cx - 7F, cy - 5F, cx + 7F, cy + 5F, color);
                context.batcher.box(cx - 2F, cy - 2F, cx + 2F, cy + 2F, color);
                context.batcher.textShadow("🎥 " + UICompositorPanel.this.layerLabel(layer)
                    + "  " + (camera.perspective ? "透视" : "正交")
                    + "  " + Math.round(camera.zoom * 100F) + "%", cx + 10F, cy - 6F, color);
            }

            /**
             * The 3D workspace overlay: selection outlines, the camera gizmo and the position HUD.
             *
             * <p>Everything is projected through the same {@link CompViewportCamera} that drew the
             * scene, so an outline can never drift from the quad it belongs to. Nothing here draws
             * per-pixel boxes: lines are rotated quads (see {@link #line}), which is what keeps a
             * gizmo from costing thousands of vertices per frame.</p>
             */
            private void renderViewportOverlay(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;
                CompProject project = UICompositorPanel.this.project;

                if (doc == null || project == null)
                {
                    return;
                }

                UICompositorPanel.this.syncViewportSize();
                UICompositorPanel.this.viewportCamera.setViewport(
                    Math.max(1, this.area.w), Math.max(1, this.area.h));
                UICompositorPanel.this.viewportCamera.update();

                context.batcher.clip(this.area, context);

                try
                {
                    if (UICompositorPanel.this.cameraPreviewMode)
                    {
                        this.renderLookThroughFrame(context, doc);

                        return;
                    }

                    CompRenderContext renderContext = new CompRenderContext(project, doc, UICompositorPanel.this.time, null, null, null);
                    dev.bbsae.client.render.CompViewportCamera camera = UICompositorPanel.this.viewportCamera;

                    for (CompLayer layer : CompPrefs.get().selectionBoxes
                        ? UICompositorPanel.this.getSelectedLayers() : java.util.Collections.<CompLayer>emptyList())
                    {
                        if (layer == null)
                        {
                            continue;
                        }

                        float[] quad = dev.bbsae.client.render.CompViewportRenderer.layerQuad(
                            renderContext, layer, camera, this.area.x, this.area.y);

                        if (quad == null)
                        {
                            continue;
                        }

                        int color = this.overlayColor(layer.locked ? 0xFF8A6A6A : 0xFF55AAFF);

                        for (int i = 0; i < 4; i++)
                        {
                            int j = (i + 1) % 4;

                            this.line(context, quad[i * 2], quad[i * 2 + 1], quad[j * 2], quad[j * 2 + 1], color);
                        }

                        /* Handles belong to the scale mode: showing arrows and grips at the same time
                         * was the main "too busy" complaint. BBS' placement gizmo likewise has one
                         * box and its grips. */
                        if (this.gizmoMode == GIZMO_SCALE)
                        {
                            for (int i = 0; i < 4; i++)
                            {
                                context.batcher.box(quad[i * 2] - 7F, quad[i * 2 + 1] - 7F,
                                    quad[i * 2] + 7F, quad[i * 2 + 1] + 7F, this.overlayColor(0xFF101418));
                                context.batcher.box(quad[i * 2] - 6F, quad[i * 2 + 1] - 6F,
                                    quad[i * 2] + 6F, quad[i * 2 + 1] + 6F, this.overlayColor(0xFFFFFFFF));
                            }

                            for (int i = 0; i < 4; i++)
                            {
                                int j = (i + 1) % 4;
                                float mx = (quad[i * 2] + quad[j * 2]) * 0.5F;
                                float my = (quad[i * 2 + 1] + quad[j * 2 + 1]) * 0.5F;

                                context.batcher.box(mx - 5F, my - 5F, mx + 5F, my + 5F, this.overlayColor(0xFF101418));
                                context.batcher.box(mx - 4F, my - 4F, mx + 4F, my + 4F, this.overlayColor(0xFFFFFFFF));
                            }

                            float cx = (quad[0] + quad[2] + quad[4] + quad[6]) * 0.25F;
                            float cy = (quad[1] + quad[3] + quad[5] + quad[7]) * 0.25F;

                            context.batcher.box(cx - 7F, cy - 7F, cx + 7F, cy + 7F, this.overlayColor(0xFF101418));
                            context.batcher.box(cx - 6F, cy - 6F, cx + 6F, cy + 6F, this.overlayColor(0xFFFFFFFF));
                        }
                    }

                    CompLayer primary = UICompositorPanel.this.selectedLayer;

                    if (primary != null && !"camera".equals(primary.type))
                    {
                        this.renderViewportTransformGizmo(context, primary, renderContext, camera);
                    }

                    this.renderGizmoModeBar(context);

                    CompLayer cameraLayer = doc.activeCamera(UICompositorPanel.this.time);

                    if (cameraLayer != null)
                    {
                        this.renderCameraMotionPath(context, cameraLayer, doc);
                    }

                    /* Every camera layer gets a gizmo, not only the active one: a second camera
                     * that is invisible in the viewport is a camera you cannot select or move. */
                    for (CompLayer layer : doc.layers)
                    {
                        if ("camera".equals(layer.type) && layer.visible)
                        {
                            this.renderViewportCameraGizmo(context, layer, doc, layer == cameraLayer);
                        }
                    }

                    this.renderViewportHud(context, doc, cameraLayer);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            /** Look-through: the exact frame is drawn by the normal graph; this is the frame chrome. */
            private void renderLookThroughFrame(UIContext context, CompDocument doc)
            {
                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return;
                }

                float w = doc.width * scale;
                float h = doc.height * scale;
                float x = this.area.x + (this.area.w - w) / 2F;
                float y = this.area.y + (this.area.h - h) / 2F;

                context.batcher.outline(x - 1F, y - 1F, x + w + 1F, y + h + 1F, 0xAAFF6B6B);
                context.batcher.box(x + 6F, y + 6F, x + 6F + 5F, y + 6F + 2F, 0xFFFF6B6B);

                /* Bottom-left, above the frame edge: the top strip is where the preview bar's
                 * FX/mask/BBS toggles live, and a label underneath them is unreadable. */
                if (CompPrefs.get().previewHints)
                {
                    context.batcher.textShadow("看穿摄像机 · 最终画面", x + 8F, y + h - 26F, 0xFFFFB4B4);
                    context.batcher.textShadow("按 0 回到自由视图", x + 8F, y + h - 14F, 0x99FFB4B4);
                }
            }

            /**
             * Motion path: where the camera's eye travels over the composition, plus a dot on every
             * transform keyframe.
             *
             * <p>This is a <b>read</b> of the same animation the renderer plays ({@code cameraEye}
             * samples the transform channel), not a stored path, so it can never disagree with the
             * shot. Sampling is capped (40 segments) because it runs every frame while the viewport
             * is open, and a polyline is exactly the resolution the eye can read.</p>
             */
            private void renderCameraMotionPath(UIContext context, CompLayer layer, CompDocument doc)
            {
                dev.bbsae.client.render.CompViewportCamera camera = UICompositorPanel.this.viewportCamera;
                float fps = Math.max(1F, doc.fps);
                float from = Math.max(0F, layer.in);
                float to = Math.min(doc.duration, layer.out > layer.in ? layer.out : doc.duration);

                if (to - from < 1F && doc.duration > 0F)
                {
                    from = 0F;
                    to = doc.duration;
                }

                if (to <= from)
                {
                    return;
                }

                boolean selected = UICompositorPanel.this.isLayerSelected(layer);
                int pathColor = selected ? 0xCCFFC14D : 0x55FFC14D;
                int dotColor = selected ? 0xFFFFC14D : 0x99FFC14D;
                int samples = 40;
                float lastX = 0F;
                float lastY = 0F;
                boolean hasLast = false;

                for (int i = 0; i <= samples; i++)
                {
                    float time = from + (to - from) * (i / (float) samples);
                    org.joml.Vector3f eye = CompCamera.eyeOf(layer, time * fps, doc.height);
                    org.joml.Vector2f point = camera.project(eye);

                    if (point == null)
                    {
                        hasLast = false;

                        continue;
                    }

                    float x = this.area.x + point.x;
                    float y = this.area.y + point.y;

                    if (hasLast)
                    {
                        this.line(context, lastX, lastY, x, y, pathColor);
                    }

                    lastX = x;
                    lastY = y;
                    hasLast = true;
                }

                /* Keyframe dots: one per independent position-axis key. X and Y now have their own
                 * times, so the motion path must not read the removed shared transform channel. */
                java.util.LinkedHashSet<Float> keyTicks = new java.util.LinkedHashSet<>();

                for (Object entry : layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_X).getKeyframes())
                {
                    keyTicks.add(((mchorse.bbs_mod.utils.keyframes.Keyframe<?>) entry).getTick());
                }
                for (Object entry : layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_Y).getKeyframes())
                {
                    keyTicks.add(((mchorse.bbs_mod.utils.keyframes.Keyframe<?>) entry).getTick());
                }

                if (layer.threeD)
                {
                    for (Object entry : layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_Z).getKeyframes())
                    {
                        keyTicks.add(((mchorse.bbs_mod.utils.keyframes.Keyframe<?>) entry).getTick());
                    }
                }

                for (float keyTick : keyTicks)
                {
                    org.joml.Vector3f eye = CompCamera.eyeOf(layer, keyTick, doc.height);
                    org.joml.Vector2f point = camera.project(eye);

                    if (point == null)
                    {
                        continue;
                    }

                    float x = this.area.x + point.x;
                    float y = this.area.y + point.y;

                    context.batcher.box(x - 3F, y - 3F, x + 3F, y + 3F, 0xFF101014);
                    context.batcher.box(x - 2F, y - 2F, x + 2F, y + 2F, dotColor);
                }
            }

            /** Camera body + frustum + target crosshair, all in viewport pixels. */
            private void renderViewportCameraGizmo(UIContext context, CompLayer layer, CompDocument doc, boolean active)
            {
                if (!CompPrefs.get().cameraGizmos)
                {
                    return;
                }

                dev.bbsae.client.render.CompViewportCamera camera = UICompositorPanel.this.viewportCamera;
                org.joml.Vector3f eye = CompCamera.eyeOf(layer, UICompositorPanel.this.getFrame(), doc.height);
                org.joml.Vector2f eyeScreen = camera.project(eye);

                if (eyeScreen == null)
                {
                    return;
                }

                float ex = this.area.x + eyeScreen.x;
                float ey = this.area.y + eyeScreen.y;
                int color = UICompositorPanel.this.isLayerSelected(layer) ? 0xFFFFC14D
                    : active ? 0xCCFFC14D : 0x77FFC14D;

                /* The frame the camera sees: four lines from the eye to the composition corners.
                 * Cheap (four segments) and it reads as a frustum from any angle, which a drawn
                 * pyramid of the wrong aspect would not. */
                for (int i = 0; i < 4; i++)
                {
                    float wx = (i == 1 || i == 2) ? doc.width : 0F;
                    float wy = i >= 2 ? doc.height : 0F;
                    org.joml.Vector2f corner = camera.project(new org.joml.Vector3f(wx, wy, 0F));

                    if (corner != null)
                    {
                        this.line(context, ex, ey, this.area.x + corner.x, this.area.y + corner.y, 0x44FFC14D);
                    }
                }

                org.joml.Vector3f target = layer.cameraTarget(UICompositorPanel.this.getFrame());
                org.joml.Vector2f targetScreen = camera.project(target);

                if (targetScreen != null)
                {
                    float tx = this.area.x + targetScreen.x;
                    float ty = this.area.y + targetScreen.y;

                    this.line(context, ex, ey, tx, ty, 0x77FFC14D);
                    context.batcher.box(tx - 6F, ty - 1F, tx + 6F, ty + 1F, color);
                    context.batcher.box(tx - 1F, ty - 6F, tx + 1F, ty + 6F, color);
                    context.batcher.box(tx - 2F, ty - 2F, tx + 2F, ty + 2F, 0xFF101014);
                    context.batcher.textShadow("⌖ 目标", tx + 9F, ty - 4F, color);
                }

                context.batcher.box(ex - 8F, ey - 6F, ex + 8F, ey + 6F, 0xFF101014);
                context.batcher.outline(ex - 8F, ey - 6F, ex + 8F, ey + 6F, color);
                context.batcher.box(ex - 2F, ey - 2F, ex + 2F, ey + 2F, color);

                /* Position readout next to the icon: the user asked to always know where the
                 * camera is, and a number that follows the gizmo beats an inspector row. */
                if (CompPrefs.get().previewHints)
                {
                    String label = String.format(java.util.Locale.ROOT, "🎥 %s  %.0f, %.0f, %.0f",
                        UICompositorPanel.this.layerLabel(layer), eye.x, eye.y, eye.z);

                    context.batcher.textShadow(label, ex + 12F, ey - 12F, color);
                }
            }

            /* ------------------------------------------------------- 3D transform gizmo */

            /**
             * Pointer position on the layer's own plane, in pre-drag local pixels. The BBS-style box
             * handles scale on this plane, so a rotated 3D layer still scales along its own axes.
             */
            private float[] viewportLocalPoint(UIContext context, CompLayer layer,
                                               CompRenderContext renderContext,
                                               dev.bbsae.client.render.CompViewportCamera camera,
                                               org.joml.Matrix4f world)
            {
                try
                {
                    org.joml.Vector3f origin = world.transformPosition(new org.joml.Vector3f());
                    org.joml.Vector3f normal = world.transformDirection(new org.joml.Vector3f(0F, 0F, 1F)).normalize();
                    org.joml.Vector3f point = camera.unprojectPlane(
                        context.mouseX - this.area.x, context.mouseY - this.area.y, origin, normal);

                    if (point == null)
                    {
                        return null;
                    }

                    new org.joml.Matrix4f(world).invert().transformPosition(point);

                    return new float[] {point.x, point.y};
                }
                catch (RuntimeException e)
                {
                    return null;
                }
            }

            /** BBS placement-gizmo style handles: four corners and four edge midpoints. */
            private int viewportBoxHandleAt(UIContext context, CompLayer layer,
                                           CompRenderContext renderContext,
                                           dev.bbsae.client.render.CompViewportCamera camera)
            {
                if (!CompPrefs.get().selectionBoxes || this.gizmoMode != GIZMO_SCALE
                    || layer == null || layer.locked)
                {
                    return -1;
                }

                float[] quad = dev.bbsae.client.render.CompViewportRenderer.layerQuad(
                    renderContext, layer, camera, this.area.x, this.area.y);

                if (quad == null)
                {
                    return -1;
                }

                float cx = (quad[0] + quad[2] + quad[4] + quad[6]) * 0.25F;
                float cy = (quad[1] + quad[3] + quad[5] + quad[7]) * 0.25F;

                if (Math.abs(context.mouseX - cx) <= 12F && Math.abs(context.mouseY - cy) <= 12F)
                {
                    return 30;
                }

                for (int i = 0; i < 4; i++)
                {
                    if (Math.abs(context.mouseX - quad[i * 2]) <= 11F
                        && Math.abs(context.mouseY - quad[i * 2 + 1]) <= 11F)
                    {
                        return 10 + i;
                    }
                }

                for (int i = 0; i < 4; i++)
                {
                    int j = (i + 1) % 4;
                    float mx = (quad[i * 2] + quad[j * 2]) * 0.5F;
                    float my = (quad[i * 2 + 1] + quad[j * 2 + 1]) * 0.5F;

                    if (Math.abs(context.mouseX - mx) <= 10F && Math.abs(context.mouseY - my) <= 10F)
                    {
                        return 20 + i;
                    }
                }

                return -1;
            }

            private void beginBox3DDrag(UIContext context, CompLayer layer, int handle)
            {
                CompRenderContext renderContext = this.viewportContext();
                dev.bbsae.client.render.CompViewportCamera camera = this.viewport();
                org.joml.Matrix4f world = dev.bbsae.client.render.CompScene.world(renderContext, layer);
                float[] local = this.viewportLocalPoint(context, layer, renderContext, camera, world);

                if (local == null)
                {
                    return;
                }

                layer.ensureIndependentAxes();

                this.dragMode = DRAG_BOX_3D;
                this.dragHandle = handle;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.startLocalX = local[0];
                this.startLocalY = local[1];
                this.startScaleX = layer.scaleX;
                this.startScaleY = layer.scaleY;
                this.startWorld.set(world);
                this.startInverse.set(world).invert();
                this.startPlaneValid = true;

                float[] quad = dev.bbsae.client.render.CompViewportRenderer.layerQuad(
                    renderContext, layer, camera, this.area.x, this.area.y);

                if (quad != null)
                {
                    this.startScreenCenterX = (quad[0] + quad[2] + quad[4] + quad[6]) * 0.25F;
                    this.startScreenCenterY = (quad[1] + quad[3] + quad[5] + quad[7]) * 0.25F;
                    this.startScreenDistance = Math.max(1F, (float) Math.sqrt(
                        (context.mouseX - this.startScreenCenterX) * (context.mouseX - this.startScreenCenterX)
                        + (context.mouseY - this.startScreenCenterY) * (context.mouseY - this.startScreenCenterY)));
                }
            }

            /**
             * Screen geometry of one gizmo axis: {originX, originY, dirX, dirY, pixelsPerUnit,
             * lengthPixels}. Length 0 means the axis points almost straight at the camera, where a
             * screen drag cannot express depth; such an axis is drawn but not hit-tested.
             */
            private float[] viewportGizmoAxis(CompLayer layer, CompRenderContext renderContext,
                                              dev.bbsae.client.render.CompViewportCamera camera, int axis)
            {
                org.joml.Matrix4f world = dev.bbsae.client.render.CompScene.world(renderContext, layer);
                org.joml.Vector3f origin = world.transformPosition(new org.joml.Vector3f());
                org.joml.Vector3f direction = new org.joml.Vector3f(
                    axis == 0 ? 1F : 0F, axis == 1 ? 1F : 0F, axis == 2 ? 1F : 0F);

                world.transformDirection(direction);

                if (direction.lengthSquared() < 0.000001F)
                {
                    return null;
                }

                direction.normalize();

                org.joml.Vector3f probe = new org.joml.Vector3f(origin)
                    .fma(CompGizmo3D.PROBE_UNITS, direction);
                org.joml.Vector2f a = camera.project(origin);
                org.joml.Vector2f b = camera.project(probe);

                if (a == null || b == null)
                {
                    return null;
                }

                float originX = this.area.x + a.x;
                float originY = this.area.y + a.y;
                float[] screen = CompGizmo3D.screenAxis(a.x, a.y, b.x, b.y);

                return new float[] {
                    originX, originY, screen[0], screen[1],
                    screen[2] / CompGizmo3D.PROBE_UNITS, screen[2]
                };
            }

            /**
             * Projected rotation ring around one world axis. Points are packed as x0,y0,x1,y1...
             * and sampled at 40 segments; the gizmo only needs enough to read as a circle and for a
             * 6 px hit test to be reliable.
             */
            private float[] viewportGizmoRing(CompLayer layer, CompRenderContext renderContext,
                                              dev.bbsae.client.render.CompViewportCamera camera, int axis)
            {
                float[] geometry = this.viewportGizmoAxis(layer, renderContext, camera, axis);

                if (geometry == null || geometry[5] < 8F)
                {
                    return null;
                }

                org.joml.Matrix4f world = dev.bbsae.client.render.CompScene.world(renderContext, layer);
                org.joml.Vector3f origin = world.transformPosition(new org.joml.Vector3f());
                float worldRadius = CompGizmo3D.ARM_PIXELS * 0.82F / Math.max(0.0001F, geometry[4]);
                org.joml.Vector3f u;
                org.joml.Vector3f v;

                if (axis == 0)
                {
                    u = new org.joml.Vector3f(0F, 1F, 0F);
                    v = new org.joml.Vector3f(0F, 0F, 1F);
                }
                else if (axis == 1)
                {
                    u = new org.joml.Vector3f(1F, 0F, 0F);
                    v = new org.joml.Vector3f(0F, 0F, 1F);
                }
                else
                {
                    u = new org.joml.Vector3f(1F, 0F, 0F);
                    v = new org.joml.Vector3f(0F, 1F, 0F);
                }

                int samples = 40;
                float[] points = new float[samples * 2];
                boolean any = false;

                for (int i = 0; i < samples; i++)
                {
                    float angle = (float) (Math.PI * 2D * i / samples);
                    org.joml.Vector3f point = new org.joml.Vector3f(origin)
                        .fma((float) Math.cos(angle) * worldRadius, u)
                        .fma((float) Math.sin(angle) * worldRadius, v);
                    org.joml.Vector2f projected = camera.project(point);

                    if (projected == null)
                    {
                        points[i * 2] = Float.NaN;
                        points[i * 2 + 1] = Float.NaN;

                        continue;
                    }

                    points[i * 2] = this.area.x + projected.x;
                    points[i * 2 + 1] = this.area.y + projected.y;
                    any = true;
                }

                return any ? points : null;
            }

            private int gizmoModeButtonAt(UIContext context)
            {
                if (!CompPrefs.get().gizmos)
                {
                    return -1;
                }

                int x = this.area.x + 8;
                /* Above the bottom HUD: the top strip belongs to the preview's own toolbar, and
                 * the first version of this bar was hidden underneath it. */
                int y = this.area.ey() - 100;
                int width = 58;
                int height = 18;

                for (int i = 0; i < 3; i++)
                {
                    if (context.mouseX >= x + i * (width + 4) && context.mouseX <= x + i * (width + 4) + width
                        && context.mouseY >= y && context.mouseY <= y + height)
                    {
                        return i;
                    }
                }

                return -1;
            }

            private void renderGizmoModeBar(UIContext context)
            {
                if (!CompPrefs.get().gizmos)
                {
                    return;
                }

                String[] labels = {"W 移动", "E 缩放", "R 旋转"};
                int x = this.area.x + 8;
                int y = this.area.ey() - 100;

                for (int i = 0; i < 3; i++)
                {
                    int width = 58;
                    boolean active = this.gizmoMode == i;
                    int fill = active ? 0xFF2F5C9E : 0xDD1A2129;

                    context.batcher.box(x + i * (width + 4), y, x + i * (width + 4) + width, y + 18, fill);
                    context.batcher.text(labels[i], x + i * (width + 4) + 5, y + 5,
                        active ? 0xFFFFFFFF : 0xFFB8C4D6);
                }
            }

            private int viewportGizmoHit(UIContext context, CompLayer layer, CompRenderContext renderContext,
                                         dev.bbsae.client.render.CompViewportCamera camera)
            {
                if (!CompPrefs.get().gizmos || layer == null || layer.locked || "camera".equals(layer.type))
                {
                    return -1;
                }

                if (this.gizmoMode == GIZMO_ROTATE)
                {
                    int axes = layer.threeD ? 3 : 2;

                    for (int axis = 0; axis < axes; axis++)
                    {
                        float[] ring = this.viewportGizmoRing(layer, renderContext, camera, axis);

                        if (ring == null)
                        {
                            continue;
                        }

                        for (int i = 0; i < ring.length / 2; i++)
                        {
                            int j = (i + 1) % (ring.length / 2);
                            float ax = ring[i * 2];
                            float ay = ring[i * 2 + 1];
                            float bx = ring[j * 2];
                            float by = ring[j * 2 + 1];

                            if (Float.isNaN(ax) || Float.isNaN(bx))
                            {
                                continue;
                            }

                            if (CompGizmo3D.distanceToSegment(context.mouseX, context.mouseY, ax, ay, bx, by) <= 6F)
                            {
                                return axis;
                            }
                        }
                    }

                    return -1;
                }

                float[] first = this.viewportGizmoAxis(layer, renderContext, camera, 0);
                float centerX = first == null ? this.area.x + this.area.w * 0.5F : first[0];
                float centerY = first == null ? this.area.y + this.area.h * 0.5F : first[1];

                if (Math.abs(context.mouseX - centerX) <= 9F && Math.abs(context.mouseY - centerY) <= 9F)
                {
                    return GIZMO_UNIFORM;
                }

                int axes = layer.threeD ? 3 : 2;

                for (int axis = 0; axis < axes; axis++)
                {
                    float[] geometry = this.viewportGizmoAxis(layer, renderContext, camera, axis);

                    if (geometry == null || geometry[5] < 12F)
                    {
                        continue;
                    }

                    float tipX = geometry[0] + geometry[2] * CompGizmo3D.ARM_PIXELS;
                    float tipY = geometry[1] + geometry[3] * CompGizmo3D.ARM_PIXELS;

                    if (this.gizmoMode == GIZMO_SCALE)
                    {
                        if (Math.abs(context.mouseX - tipX) <= 10F && Math.abs(context.mouseY - tipY) <= 10F)
                        {
                            return axis;
                        }
                    }
                    else
                    {
                        float distance = CompGizmo3D.distanceToSegment(context.mouseX, context.mouseY,
                            geometry[0], geometry[1], tipX, tipY);

                        if (distance <= 8F
                            || Math.abs(context.mouseX - tipX) <= 10F && Math.abs(context.mouseY - tipY) <= 10F)
                        {
                            return axis;
                        }
                    }
                }

                return -1;
            }

            private void renderViewportTransformGizmo(UIContext context, CompLayer layer,
                                                      CompRenderContext renderContext,
                                                      dev.bbsae.client.render.CompViewportCamera camera)
            {
                if (!CompPrefs.get().gizmos)
                {
                    return;
                }

                if (UICompositorPanel.this.previewGizmoModeOverride >= 0)
                {
                    this.gizmoMode = UICompositorPanel.this.previewGizmoModeOverride;
                    UICompositorPanel.this.previewGizmoModeOverride = -1;
                }

                int axes = layer.threeD ? 3 : 2;
                int[] colors = {0xFFFF5A5A, 0xFF6BE06B, 0xFF5AA9FF};
                String[] labels = {"X", "Y", "Z"};
                float[] first = this.viewportGizmoAxis(layer, renderContext, camera, 0);
                float centerX = first == null ? this.area.x + this.area.w * 0.5F : first[0];
                float centerY = first == null ? this.area.y + this.area.h * 0.5F : first[1];

                if (this.gizmoMode == GIZMO_ROTATE)
                {
                    for (int axis = 0; axis < axes; axis++)
                    {
                        float[] ring = this.viewportGizmoRing(layer, renderContext, camera, axis);

                        if (ring == null)
                        {
                            continue;
                        }

                        int color = layer.locked ? 0xFF8A6A6A : colors[axis];
                        int count = ring.length / 2;

                        for (int i = 0; i < count; i++)
                        {
                            int j = (i + 1) % count;
                            float ax = ring[i * 2];
                            float ay = ring[i * 2 + 1];
                            float bx = ring[j * 2];
                            float by = ring[j * 2 + 1];

                            if (!Float.isNaN(ax) && !Float.isNaN(bx))
                            {
                                this.line(context, ax, ay, bx, by, color);
                            }
                        }

                        context.batcher.textShadow(labels[axis], centerX + 10F + axis * 8F,
                            centerY - 18F, color);
                    }

                    context.batcher.box(centerX - 4F, centerY - 4F, centerX + 4F, centerY + 4F, this.overlayColor(0xFF101418));
                    context.batcher.box(centerX - 2F, centerY - 2F, centerX + 2F, centerY + 2F, this.overlayColor(0xFFFFFFFF));

                    return;
                }

                for (int axis = 0; axis < axes; axis++)
                {
                    float[] geometry = this.viewportGizmoAxis(layer, renderContext, camera, axis);

                    if (geometry == null)
                    {
                        continue;
                    }

                    boolean usable = geometry[5] >= 12F;
                    int color = this.overlayColor(layer.locked ? 0xFF8A6A6A : colors[axis]);

                    if (!usable)
                    {
                        color = UiGfx.alpha(color, 0.35F);
                    }

                    float tipX = geometry[0] + geometry[2] * CompGizmo3D.ARM_PIXELS;
                    float tipY = geometry[1] + geometry[3] * CompGizmo3D.ARM_PIXELS;

                    this.line(context, geometry[0], geometry[1], tipX, tipY, color);

                    if (this.gizmoMode == GIZMO_SCALE)
                    {
                        context.batcher.box(tipX - 6F, tipY - 6F, tipX + 6F, tipY + 6F, this.overlayColor(0xFF101418));
                        context.batcher.box(tipX - 5F, tipY - 5F, tipX + 5F, tipY + 5F, color);
                    }
                    else
                    {
                        float baseX = tipX - geometry[2] * 11F;
                        float baseY = tipY - geometry[3] * 11F;
                        float perpX = -geometry[3];
                        float perpY = geometry[2];

                        this.line(context, tipX, tipY, baseX + perpX * 5F, baseY + perpY * 5F, color);
                        this.line(context, tipX, tipY, baseX - perpX * 5F, baseY - perpY * 5F, color);
                    }

                    context.batcher.textShadow(labels[axis], tipX + geometry[2] * 7F + 2F,
                        tipY + geometry[3] * 7F - 3F, color);
                }

                context.batcher.box(centerX - 5F, centerY - 5F, centerX + 5F, centerY + 5F, this.overlayColor(0xFF101418));

                if (this.gizmoMode == GIZMO_SCALE)
                {
                    context.batcher.box(centerX - 4F, centerY - 4F, centerX + 4F, centerY + 4F, this.overlayColor(0xFFFFFFFF));
                }
                else
                {
                    context.batcher.box(centerX - 3F, centerY - 3F, centerX + 3F, centerY + 3F, this.overlayColor(0xFFFFFFFF));
                }
            }

            private void beginGizmoDrag(UIContext context, CompLayer layer, int axis)
            {
                int geometryAxis = Math.min(2, axis);
                float[] geometry = this.viewportGizmoAxis(layer, this.viewportContext(), this.viewport(), geometryAxis);

                if (geometry == null || (axis < GIZMO_UNIFORM && geometry[5] < 12F))
                {
                    return;
                }

                this.gizmoAxisIndex = axis;
                this.gizmoLayerId = layer.id;
                this.gizmoStartMouseX = context.mouseX;
                this.gizmoStartMouseY = context.mouseY;
                this.gizmoDirX = geometry[2];
                this.gizmoDirY = geometry[3];
                this.gizmoPixelsPerUnit = Math.max(0.0001F, geometry[4]);
                this.gizmoOriginX = geometry[0];
                this.gizmoOriginY = geometry[1];
                this.gizmoStartAngle = CompGizmo3D.angleDegrees(
                    context.mouseX - geometry[0], context.mouseY - geometry[1]);
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;

                if (this.gizmoMode == GIZMO_SCALE)
                {
                    layer.ensureIndependentAxes();
                    this.gizmoStartScaleX = layer.scaleX;
                    this.gizmoStartScaleY = layer.scaleY;
                    this.gizmoStartScaleZ = layer.scalarAt("scaleZ", layer.scaleZ,
                        UICompositorPanel.this.getFrame());
                    this.gizmoStartValue = 0F;
                }
                else if (this.gizmoMode == GIZMO_ROTATE)
                {
                    this.gizmoStartAxisRotation = axis == 0
                        ? layer.scalarAt("rotationX", layer.rotationX, UICompositorPanel.this.getFrame())
                        : axis == 1
                            ? layer.scalarAt("rotationY", layer.rotationY, UICompositorPanel.this.getFrame())
                            : layer.transformAt(UICompositorPanel.this.getFrame()).rotate.z;
                    this.gizmoStartValue = 0F;
                }
                else
                {
                    this.gizmoStartValue = axis == 0 ? layer.transformAt(UICompositorPanel.this.getFrame()).translate.x
                        : axis == 1 ? layer.transformAt(UICompositorPanel.this.getFrame()).translate.y
                        : layer.scalarAt("z", layer.z, UICompositorPanel.this.getFrame());
                }

                this.dragMode = DRAG_GIZMO;
            }

            private void applyGizmoDrag(UIContext context)
            {
                CompLayer layer = this.gizmoLayerId == null || UICompositorPanel.this.doc == null
                    ? null : UICompositorPanel.this.doc.getLayer(this.gizmoLayerId);

                if (layer == null || this.gizmoAxisIndex < 0)
                {
                    this.endDrag();

                    return;
                }

                if (this.gizmoMode == GIZMO_SCALE)
                {
                    float delta = (context.mouseX - this.gizmoStartMouseX) * this.gizmoDirX
                        + (context.mouseY - this.gizmoStartMouseY) * this.gizmoDirY;
                    float factor = CompGizmo3D.scaleFactor(delta);
                    float sx = this.gizmoStartScaleX;
                    float sy = this.gizmoStartScaleY;
                    float sz = this.gizmoStartScaleZ;

                    if (this.gizmoAxisIndex == GIZMO_UNIFORM || this.gizmoAxisIndex == 0) sx *= factor;
                    if (this.gizmoAxisIndex == GIZMO_UNIFORM || this.gizmoAxisIndex == 1) sy *= factor;
                    if (this.gizmoAxisIndex == GIZMO_UNIFORM || this.gizmoAxisIndex == 2) sz *= factor;

                    this.writeTransform(layer, sx, sy, null);
                    UICompositorPanel.this.setScalar(layer, "scaleZ", sz);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                        "缩放 X %.0f%%  Y %.0f%%  Z %.0f%%", sx * 100F, sy * 100F, sz * 100F));

                    return;
                }

                if (this.gizmoMode == GIZMO_ROTATE)
                {
                    float current = CompGizmo3D.angleDegrees(
                        context.mouseX - this.gizmoOriginX, context.mouseY - this.gizmoOriginY);
                    float value = this.gizmoStartAxisRotation
                        + CompGizmo3D.angleDelta(this.gizmoStartAngle, current);

                    if (Window.isShiftPressed())
                    {
                        value = Math.round(value / 15F) * 15F;
                    }

                    if (this.gizmoAxisIndex == 0)
                    {
                        UICompositorPanel.this.setScalar(layer, "rotationX", value);
                    }
                    else if (this.gizmoAxisIndex == 1)
                    {
                        UICompositorPanel.this.setScalar(layer, "rotationY", value);
                    }
                    else
                    {
                        this.writeTransform(layer, null, null, value);
                    }

                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                        "旋转 %s %.1f°", this.gizmoAxisIndex == 0 ? "X" : this.gizmoAxisIndex == 1 ? "Y" : "Z", value));

                    return;
                }

                float value = CompGizmo3D.dragValue(this.gizmoStartValue,
                    context.mouseX - this.gizmoStartMouseX,
                    context.mouseY - this.gizmoStartMouseY,
                    this.gizmoDirX, this.gizmoDirY, this.gizmoPixelsPerUnit);

                if (Window.isShiftPressed())
                {
                    value = CompGizmo3D.snap(value, true);
                }

                String path = this.gizmoAxisIndex == 0 ? "x" : this.gizmoAxisIndex == 1 ? "y" : "z";

                UICompositorPanel.this.setScalar(layer, path, value);
                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                    "%s %s %.1f", path.toUpperCase(java.util.Locale.ROOT),
                    UICompositorPanel.this.layerLabel(layer), value));
            }

            /** Bottom-left HUD: mode, camera pose and the navigation keys. */
            private void renderViewportHud(UIContext context, CompDocument doc, CompLayer cameraLayer)
            {
                if (!CompPrefs.get().previewHints)
                {
                    return;
                }

                dev.bbsae.client.render.CompViewportCamera camera = UICompositorPanel.this.viewportCamera;
                int x = this.area.x + 8;
                int y = this.area.ey() - 46;

                /* Draft honesty: the free viewport draws each layer's raster and only the
                 * layer-local effects; effects that must sample the whole composition (repeat /
                 * shape field / glass / displacement-map …), track mattes and layer blend modes
                 * need the full compositor and are only visible in the preview/export. Saying so
                 * avoids the "3D mode has no effects" bug report being about the draft view. */
                String mode = "3D 空间 · 草稿（重复/合成采样效果、轨迹遮罩、混合模式不显示）"
                    + (camera.ortho ? " · 正交" : " · 透视");
                String pose;

                if (cameraLayer == null)
                {
                    pose = "没有摄像机 · 点击左侧球体新建";
                }
                else
                {
                    org.joml.Vector3f eye = CompCamera.eyeOf(cameraLayer, UICompositorPanel.this.getFrame(), doc.height);

                    pose = String.format(java.util.Locale.ROOT,
                        "摄像机  X %.0f   Y %.0f   Z %.0f   FOV %.0f°   视图距离 %.0f",
                        eye.x, eye.y, eye.z,
                        cameraLayer.scalarAt("cameraFov", cameraLayer.cameraFov, UICompositorPanel.this.getFrame()),
                        camera.distance);
                }

                String hint = "右键拖动环绕 · Shift+右键平移 · 滚轮缩放 · W/E/R 移动/缩放/旋转 · 0 看穿 · 5 正交 · Ctrl+Home 全览";

                context.batcher.box(x - 4F, y - 4F, this.area.ex() - 8F, y + 40F, 0x990A0D12);
                context.batcher.textShadow(mode, x, y, 0xFFB9C6DA);
                context.batcher.textShadow(pose, x, y + 13, 0xFFFFC14D);
                context.batcher.textShadow(hint, x, y + 26, 0xFF7E8BA0);
            }

            private void renderSelection(UIContext context)
            {
                if (!CompPrefs.get().selectionBoxes)
                {
                    return;
                }

                CompLayer layer = UICompositorPanel.this.selectedLayer;

                if (layer == null || UICompositorPanel.this.doc == null || UICompositorPanel.this.project == null)
                {
                    return;
                }

                context.batcher.clip(this.area, context);

                try
                {
                    this.renderSelectionUnclipped(context, layer);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            private void renderSelectionUnclipped(UIContext context, CompLayer layer)
            {
                float[] corners = this.selectionCorners(layer);

                if (corners == null)
                {
                    return;
                }

                int color = layer.locked ? 0xFF8A6A6A : 0xFF55AAFF;

                for (int i = 0; i < 4; i++)
                {
                    int j = (i + 1) % 4;

                    this.line(context, corners[i * 2], corners[i * 2 + 1], corners[j * 2], corners[j * 2 + 1], color);
                }

                /* Small corner grips + edge grips + a rotation stem. These are the hit targets
                 * used by handleAt() below; drawing them without hit testing was the reason the
                 * old selection box looked like decoration. */
                for (int i = 0; i < 4; i++)
                {
                    float x = corners[i * 2];
                    float y = corners[i * 2 + 1];

                    context.batcher.box(x - 5F, y - 5F, x + 5F, y + 5F, this.overlayColor(0xFF101418));
                    context.batcher.box(x - 4F, y - 4F, x + 4F, y + 4F, this.overlayColor(0xFFFFFFFF));
                }

                for (int i = 0; i < 4; i++)
                {
                    int j = (i + 1) % 4;
                    float mx = (corners[i * 2] + corners[j * 2]) * 0.5F;
                    float my = (corners[i * 2 + 1] + corners[j * 2 + 1]) * 0.5F;

                    /* Edge grips carry the axis they scale: top/bottom (20/22) edit Y (green),
                     * left/right (21/23) edit X (red). The colour is the same convention as the 3D
                     * arrows, so "which axis will move" is readable before the drag starts. */
                    int grip = (i == 0 || i == 2) ? 0xFF6BE06B : 0xFFFF5A5A;

                    context.batcher.box(mx - 3F, my - 3F, mx + 3F, my + 3F, this.overlayColor(0xFF101418));
                    context.batcher.box(mx - 2F, my - 2F, mx + 2F, my + 2F, this.overlayColor(grip));
                }

                float rx = (corners[0] + corners[2]) * 0.5F;
                float ry = (corners[1] + corners[3]) * 0.5F;
                float bx = (corners[6] + corners[4]) * 0.5F;
                float by = (corners[7] + corners[5]) * 0.5F;
                float dx = rx - bx;
                float dy = ry - by;
                float length = (float) Math.sqrt(dx * dx + dy * dy);

                if (length > 0.001F)
                {
                    dx /= length;
                    dy /= length;
                }
                else
                {
                    dx = 0F;
                    dy = -1F;
                }

                float hx = rx + dx * 26F;
                float hy = ry + dy * 26F;

                this.line(context, rx, ry, hx, hy, color);
                context.batcher.box(hx - 4F, hy - 4F, hx + 4F, hy + 4F, this.overlayColor(0xFF101418));
                context.batcher.box(hx - 3F, hy - 3F, hx + 3F, hy + 3F, this.overlayColor(0xFFFFD27A));
            }

            /** Four screen-space corners in TL, TR, BR, BL order. */
            /**
             * Screen corners of the selection box.
             *
             * <p>Every corner goes through {@link CompScene#project}, the same call the renderer and
             * the gizmos use, so the box cannot drift away from the layer when the camera pans,
             * zooms, rolls or goes perspective. The old code mixed a raw world matrix (2D) with a
             * hand-rolled projection (3D) and disagreed with both.</p>
             */
            private float[] selectionCorners(CompLayer layer)
            {
                if (layer == null || UICompositorPanel.this.doc == null)
                {
                    return null;
                }

                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return null;
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                CompRenderContext renderContext = new CompRenderContext(
                    UICompositorPanel.this.project,
                    UICompositorPanel.this.doc,
                    UICompositorPanel.this.time,
                    null,
                    null,
                    null
                );
                float x = -layer.width * layer.anchorX;
                float y = -layer.height * layer.anchorY;
                float[] result = new float[8];

                /* Nulls and cameras have no content rectangle: a fixed gizmo around the anchor is
                 * what keeps their scale/rotate handles usable. */
                boolean pointGizmo = "null".equals(layer.type) || "camera".equals(layer.type);

                try
                {
                    if (pointGizmo)
                    {
                        /* A camera is drawn where it sits in composition space (see
                         * renderCameraGizmo): projecting it through its own view would put the
                         * selection box at the frame centre no matter where the eye is. */
                        org.joml.Vector2f anchor;

                        if ("camera".equals(layer.type))
                        {
                            anchor = layer.cameraEye(UICompositorPanel.this.getFrame());
                        }
                        else
                        {
                            anchor = CompScene.project(renderContext, layer, 0F, 0F, 0F);
                        }

                        if (anchor == null)
                        {
                            return null;
                        }

                        float cx = this.area.x + ox + anchor.x * scale;
                        float cy = this.area.y + oy + anchor.y * scale;
                        float r = 26F;

                        return new float[] {cx - r, cy - r, cx + r, cy - r, cx + r, cy + r, cx - r, cy + r};
                    }

                    for (int i = 0; i < 4; i++)
                    {
                        float lx = (i == 0 || i == 3) ? x : x + layer.width;
                        float ly = (i == 0 || i == 1) ? y : y + layer.height;
                        org.joml.Vector2f projected = CompScene.project(renderContext, layer, lx, ly, 0F);

                        if (projected == null)
                        {
                            return null;
                        }

                        result[i * 2] = this.area.x + ox + projected.x * scale;
                        result[i * 2 + 1] = this.area.y + oy + projected.y * scale;
                    }
                }
                catch (RuntimeException e)
                {
                    return null;
                }

                return result;
            }

            /* ------------------------------------------------------------------ masks */

            private CompRenderContext maskContext()
            {
                return new CompRenderContext(UICompositorPanel.this.project, UICompositorPanel.this.doc,
                    UICompositorPanel.this.time, null, null, null);
            }

            private float[] maskProject(CompRenderContext rc, CompLayer layer, float lx, float ly,
                                        float scale, float ox, float oy)
            {
                org.joml.Vector2f p = CompScene.project(rc, layer, lx, ly, 0F);

                if (p == null)
                {
                    return null;
                }

                return new float[] {
                    this.area.x + ox + p.x * scale,
                    this.area.y + oy + p.y * scale
                };
            }

            /**
             * Draws every enabled mask of the selected layer and the bezier handles of the point
             * under the mouse. Points are projected with CompScene.project — the same path the
             * selection box and the renderer use — so the overlay cannot drift from the pixels.
             */
            private void renderMaskOverlay(UIContext context)
            {
                CompLayer layer = UICompositorPanel.this.selectedLayer;

                if (!UICompositorPanel.this.previewMasks || layer == null || layer.masks == null
                    || layer.masks.isEmpty() || UICompositorPanel.this.doc == null
                    || UICompositorPanel.this.project == null || UICompositorPanel.this.is3DSpace())
                {
                    return;
                }

                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return;
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                CompRenderContext rc = this.maskContext();

                context.batcher.clip(this.area, context);

                try
                {
                    for (int mi = 0; mi < layer.masks.size(); mi++)
                    {
                        CompMask mask = layer.masks.get(mi);

                        if (mask == null || !mask.enabled || mask.points.size() < 2)
                        {
                            continue;
                        }

                        int color = mi == this.maskEditMask
                            ? 0xFFFFD27A
                            : mask.mode == CompMask.MODE_EXCLUDE ? 0xAAFF8A8A : 0xAA55AAFF;

                        /* Sampled cubic path: the same in/out handles MaskCache evaluates, so the
                         * outline the user drags is the outline that gets rasterized. */
                        for (int i = 0; i < mask.points.size(); i++)
                        {
                            CompMask.CompMaskPoint a = mask.points.get(i);
                            CompMask.CompMaskPoint b = mask.points.get((i + 1) % mask.points.size());
                            float[] previous = null;

                            for (int s = 0; s <= 12; s++)
                            {
                                float t = s / 12F;
                                float mt = 1F - t;
                                float x = mt * mt * mt * a.x
                                    + 3F * mt * mt * t * (a.x + a.outX)
                                    + 3F * mt * t * t * (b.x + b.inX)
                                    + t * t * t * b.x;
                                float y = mt * mt * mt * a.y
                                    + 3F * mt * mt * t * (a.y + a.outY)
                                    + 3F * mt * t * t * (b.y + b.inY)
                                    + t * t * t * b.y;
                                float[] point = this.maskProject(rc, layer, x, y, scale, ox, oy);

                                if (point != null && previous != null)
                                {
                                    this.line(context, previous[0], previous[1], point[0], point[1], color);
                                }

                                previous = point;
                            }
                        }

                        for (int pi = 0; pi < mask.points.size(); pi++)
                        {
                            CompMask.CompMaskPoint point = mask.points.get(pi);
                            float[] screen = this.maskProject(rc, layer, point.x, point.y, scale, ox, oy);

                            if (screen == null)
                            {
                                continue;
                            }

                            boolean active = mi == this.maskEditMask && pi == this.maskEditPoint;
                            float half = active ? 4.5F : 3.5F;

                            context.batcher.box(screen[0] - half, screen[1] - half, screen[0] + half, screen[1] + half, 0xFF101418);
                            context.batcher.box(screen[0] - half + 1F, screen[1] - half + 1F,
                                screen[0] + half - 1F, screen[1] + half - 1F, active ? 0xFFFFD27A : 0xFFFFFFFF);

                            if (active)
                            {
                                float[] in = this.maskProject(rc, layer, point.x + point.inX, point.y + point.inY, scale, ox, oy);
                                float[] out = this.maskProject(rc, layer, point.x + point.outX, point.y + point.outY, scale, ox, oy);

                                if (in != null)
                                {
                                    this.line(context, screen[0], screen[1], in[0], in[1], 0xAA7AD8FF);
                                    context.batcher.box(in[0] - 3F, in[1] - 3F, in[0] + 3F, in[1] + 3F, 0xFF7AD8FF);
                                }

                                if (out != null)
                                {
                                    this.line(context, screen[0], screen[1], out[0], out[1], 0xAAFF9BD2);
                                    context.batcher.box(out[0] - 3F, out[1] - 3F, out[0] + 3F, out[1] + 3F, 0xFFFF9BD2);
                                }
                            }
                        }
                    }
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            /**
             * Point/handle hit test. Only 2D layers are draggable: a 3D layer's mask is baked in
             * layer space and is visible, but mapping a perspective screen ray back onto an
             * arbitrary plane is a separate unprojection step (the numeric/curve editor still
             * works for it).
             */
            private boolean maskHit(UIContext context, CompLayer layer)
            {
                if (!UICompositorPanel.this.previewMasks || layer == null || layer.locked
                    || layer.masks == null || layer.masks.isEmpty() || UICompositorPanel.this.doc == null
                    || UICompositorPanel.this.project == null || UICompositorPanel.this.is3DSpace())
                {
                    return false;
                }

                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return false;
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                CompRenderContext rc = this.maskContext();
                int bestMask = -1;
                int bestPoint = -1;
                int bestHandle = 0;
                float bestDistance = 9F;

                for (int mi = 0; mi < layer.masks.size(); mi++)
                {
                    CompMask mask = layer.masks.get(mi);

                    if (mask == null || !mask.enabled)
                    {
                        continue;
                    }

                    for (int pi = 0; pi < mask.points.size(); pi++)
                    {
                        CompMask.CompMaskPoint point = mask.points.get(pi);
                        float[] screen = this.maskProject(rc, layer, point.x, point.y, scale, ox, oy);

                        if (screen == null)
                        {
                            continue;
                        }

                        float[] in = this.maskProject(rc, layer, point.x + point.inX, point.y + point.inY, scale, ox, oy);
                        float[] out = this.maskProject(rc, layer, point.x + point.outX, point.y + point.outY, scale, ox, oy);
                        int[] handles = {2, 3};

                        for (int h = 0; h < 2; h++)
                        {
                            float[] handle = h == 0 ? in : out;

                            if (handle == null)
                            {
                                continue;
                            }

                            float distance = (float) Math.hypot(context.mouseX - handle[0], context.mouseY - handle[1]);

                            if (distance <= bestDistance)
                            {
                                bestDistance = distance;
                                bestMask = mi;
                                bestPoint = pi;
                                bestHandle = handles[h];
                            }
                        }

                        float distance = (float) Math.hypot(context.mouseX - screen[0], context.mouseY - screen[1]);

                        if (distance <= bestDistance)
                        {
                            bestDistance = distance;
                            bestMask = mi;
                            bestPoint = pi;
                            bestHandle = 0;
                        }
                    }
                }

                if (bestMask < 0)
                {
                    return false;
                }

                this.maskEditMask = bestMask;
                this.maskEditPoint = bestPoint;
                this.maskEditHandle = bestHandle;
                this.maskDrag = bestHandle == 0 ? MASK_DRAG_POINT : bestHandle == 2 ? MASK_DRAG_IN : MASK_DRAG_OUT;
                this.maskDragMask = bestMask;
                this.maskDragPoint = bestPoint;
                this.maskDragStartX = context.mouseX;
                this.maskDragStartY = context.mouseY;
                this.maskActionStarted = false;

                return true;
            }

            private void applyMaskDrag(UIContext context)
            {
                CompLayer layer = UICompositorPanel.this.selectedLayer;

                if (!Window.isMouseButtonPressed(0))
                {
                    this.endMaskDrag();

                    return;
                }

                if (layer == null || layer.masks == null || this.maskDragMask < 0
                    || this.maskDragMask >= layer.masks.size())
                {
                    this.endMaskDrag();

                    return;
                }

                if (Math.abs(context.mouseX - this.maskDragStartX) < 2 && Math.abs(context.mouseY - this.maskDragStartY) < 2)
                {
                    return;
                }

                if (!this.maskActionStarted)
                {
                    UICompositorPanel.this.beginAction("编辑蒙版");
                    this.maskActionStarted = true;
                }

                CompMask mask = layer.masks.get(this.maskDragMask);

                if (mask == null || this.maskDragPoint < 0 || this.maskDragPoint >= mask.points.size())
                {
                    this.endMaskDrag();

                    return;
                }

                float[] local = this.screenToLayerLocal(layer, context.mouseX, context.mouseY);

                if (local == null)
                {
                    return;
                }

                CompMask.CompMaskPoint point = mask.points.get(this.maskDragPoint);
                String base = "mask/" + mask.id + "/p/" + this.maskDragPoint + "/";

                /* Route through setScalar, not point.x = …: when the mask point already has a
                 * keyframe channel, the renderer reads the channel, so a base-only edit would be
                 * invisible. setScalar writes the key at the playhead exactly like the inspector. */
                if (this.maskDrag == MASK_DRAG_POINT)
                {
                    UICompositorPanel.this.setScalar(layer, base + "x", local[0]);
                    UICompositorPanel.this.setScalar(layer, base + "y", local[1]);
                }
                else if (this.maskDrag == MASK_DRAG_IN)
                {
                    UICompositorPanel.this.setScalar(layer, base + "inX", local[0] - point.x);
                    UICompositorPanel.this.setScalar(layer, base + "inY", local[1] - point.y);
                }
                else if (this.maskDrag == MASK_DRAG_OUT)
                {
                    UICompositorPanel.this.setScalar(layer, base + "outX", local[0] - point.x);
                    UICompositorPanel.this.setScalar(layer, base + "outY", local[1] - point.y);
                }

                UICompositorPanel.this.setStatus("蒙版 " + (this.maskDragPoint + 1) + " · 拖动编辑");
            }

            private void endMaskDrag()
            {
                if (this.maskDrag == MASK_DRAG_NONE)
                {
                    return;
                }

                if (this.maskActionStarted)
                {
                    UICompositorPanel.this.save();
                    UICompositorPanel.this.refreshInspector();
                }

                this.maskDrag = MASK_DRAG_NONE;
                this.maskDragMask = -1;
                this.maskDragPoint = -1;
                this.maskActionStarted = false;
                this.maskStartPoints.clear();
            }

            /**
             * Screen pixel → the layer's own mask plane.
             *
             * <p>For a 2D layer this is the old inverse-model mapping. For a 3D layer the mouse
             * ray is intersected with the layer plane (origin + z normal from its world matrix),
             * which is the only way a perspective mask point can follow the cursor exactly.</p>
             */
            private float[] screenToLayerLocal(CompLayer layer, float mouseX, float mouseY)
            {
                float scale = this.fitScale();

                if (scale <= 0F || UICompositorPanel.this.doc == null || UICompositorPanel.this.project == null)
                {
                    return null;
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                CompRenderContext rc = this.maskContext();
                float compX = (mouseX - this.area.x - ox) / scale;
                float compY = (mouseY - this.area.y - oy) / scale;

                try
                {
                    /* One shared inverse: CompScene knows whether this layer is screen-fixed
                     * (AE 2D: composition pixels are the layer's world pixels), flat 3D
                     * (view2D + ray onto the plane) or perspective (camera ray). The editor used
                     * to re-derive those inverses here, which is how gizmos drifted from the
                     * rendered frame whenever the camera moved. */
                    return CompScene.compToLayerLocal(rc, layer, compX, compY);
                }
                catch (RuntimeException e)
                {
                    return null;
                }
            }

            /** 10 + corner, 20 + edge, 30 = rotate handle. */
            private int handleAt(UIContext context, CompLayer layer)
            {
                if (layer == null || layer.locked)
                {
                    return -1;
                }

                /* A hidden / trimmed-out / soloed-out layer is not drawn, so it must not offer
                 * drag handles either: "select it in the timeline, drag the empty preview" used to
                 * move an invisible layer. */
                if (!layer.visible || !layer.activeAt(UICompositorPanel.this.time)
                    || (UICompositorPanel.this.doc != null && UICompositorPanel.this.doc.isSoloedOut(layer)))
                {
                    return -1;
                }

                if ("camera".equals(layer.type))
                {
                    if (UICompositorPanel.this.doc == null || UICompositorPanel.this.doc.activeCamera(UICompositorPanel.this.time) != layer)
                    {
                        return -1;
                    }

                    float scale = Math.max(0.0001F, this.fitScale());
                    float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                    float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                    float compX = (context.mouseX - this.area.x - ox) / scale;
                    float compY = (context.mouseY - this.area.y - oy) / scale;
                    float frame = UICompositorPanel.this.getFrame();
                    org.joml.Vector2f eye = layer.cameraEye(frame);
                    org.joml.Vector3f target = layer.cameraTarget(frame);

                    /* Same space as the drawn gizmo: comp pixels, not view pixels. The old hit test
                     * compared the cursor against the frame centre, so the only draggable camera
                     * was one whose eye happened to be centred. */
                    if (layer.cameraLookAt
                        && Math.abs(target.x - compX) * scale <= 12F
                        && Math.abs(target.y - compY) * scale <= 12F)
                    {
                        return 41;
                    }

                    if (Math.abs(eye.x - compX) * scale <= 14F
                        && Math.abs(eye.y - compY) * scale <= 14F)
                    {
                        return 40;
                    }

                    return -1;
                }

                if (!CompPrefs.get().selectionBoxes)
                {
                    return -1;
                }

                float[] corners = this.selectionCorners(layer);

                if (corners == null)
                {
                    return -1;
                }

                float mx = context.mouseX;
                float my = context.mouseY;
                float hit = 12F;

                for (int i = 0; i < 4; i++)
                {
                    if (Math.abs(mx - corners[i * 2]) <= hit && Math.abs(my - corners[i * 2 + 1]) <= hit)
                    {
                        return 10 + i;
                    }
                }

                for (int i = 0; i < 4; i++)
                {
                    int j = (i + 1) % 4;
                    float ex = (corners[i * 2] + corners[j * 2]) * 0.5F;
                    float ey = (corners[i * 2 + 1] + corners[j * 2 + 1]) * 0.5F;

                    if (Math.abs(mx - ex) <= hit && Math.abs(my - ey) <= hit)
                    {
                        return 20 + i;
                    }
                }

                float rx = (corners[0] + corners[2]) * 0.5F;
                float ry = (corners[1] + corners[3]) * 0.5F;
                float bx = (corners[6] + corners[4]) * 0.5F;
                float by = (corners[7] + corners[5]) * 0.5F;
                float dx = rx - bx;
                float dy = ry - by;
                float length = (float) Math.sqrt(dx * dx + dy * dy);

                if (length > 0.001F)
                {
                    dx /= length;
                    dy /= length;
                }

                float hx = rx + dx * 26F;
                float hy = ry + dy * 26F;

                if (Math.abs(mx - hx) <= 10F && Math.abs(my - hy) <= 10F)
                {
                    return 30;
                }

                return -1;
            }

            /** Mouse position in the layer's local pixel space (anchor at 0,0). */
            private float[] localPoint(UIContext context, CompLayer layer)
            {
                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return new float[] {0F, 0F};
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                float compX = (context.mouseX - this.area.x - ox) / scale;
                float compY = (context.mouseY - this.area.y - oy) / scale;
                CompRenderContext renderContext = new CompRenderContext(
                    UICompositorPanel.this.project,
                    UICompositorPanel.this.doc,
                    UICompositorPanel.this.time,
                    null,
                    null,
                    null
                );

                try
                {
                    float[] local = CompScene.compToLayerLocal(renderContext, layer, compX, compY);

                    return local == null ? new float[] {0F, 0F} : local;
                }
                catch (RuntimeException e)
                {
                    return new float[] {0F, 0F};
                }
            }

            /**
             * Mouse in the transform space captured when the handle drag started. Using the live
             * matrix here would feed the scale change back into the ratio and make the handle run
             * away from the cursor.
             */
            private float[] startLocalPoint(UIContext context, CompLayer layer)
            {
                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return new float[] {this.startLocalX, this.startLocalY};
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                CompRenderContext renderContext = new CompRenderContext(
                    UICompositorPanel.this.project, UICompositorPanel.this.doc, UICompositorPanel.this.time, null, null, null
                );
                float screenX = (context.mouseX - this.area.x - ox) / scale;
                float screenY = (context.mouseY - this.area.y - oy) / scale;

                if (!layer.threeD)
                {
                    /* 2D handle drag: invert the drag-start transform, not the live one. In AE
                     * logic a 2D layer is screen-fixed (no camera view at all); legacy mode still
                     * folds in view2D. */
                    Matrix4f model = new Matrix4f(this.startWorld);

                    if (CompScene.cameraAffects(renderContext, layer))
                    {
                        CompCamera camera = CompScene.camera(renderContext);

                        model = new Matrix4f(camera.view2D).mul(this.startWorld);
                    }

                    Vector3f point = new Vector3f(screenX, screenY, 0F);

                    new Matrix4f(model).invert().transformPosition(point);

                    return new float[] {point.x, point.y};
                }

                Vector3f point = CompScene.camera(renderContext).unproject(
                    screenX, screenY, UICompositorPanel.this.doc.width, UICompositorPanel.this.doc.height, 0F
                );

                this.startInverse.transformPosition(point);

                return new float[] {point.x, point.y};
            }

            /**
             * Pointer in the 3D layer's own plane, measured with the drag-start transform.
             *
             * <p>The old 3D path ignored the handle direction and scaled by the ratio of the
             * cursor's distance from the screen centre. That is not an edge stretch: every handle
             * behaved like a uniform corner and the grabbed handle did not track the pointer under
             * perspective. Casting the mouse ray onto the layer plane and applying the start
             * inverse gives the same pre-drag local coordinates the 2D path uses.</p>
             */
            private float[] startLocalPointOnLayerPlane(UIContext context)
            {
                float scale = this.fitScale();

                if (scale <= 0F || UICompositorPanel.this.doc == null || UICompositorPanel.this.project == null)
                {
                    return null;
                }

                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                float compX = (context.mouseX - this.area.x - ox) / scale;
                float compY = (context.mouseY - this.area.y - oy) / scale;
                CompRenderContext renderContext = new CompRenderContext(
                    UICompositorPanel.this.project, UICompositorPanel.this.doc, UICompositorPanel.this.time, null, null, null
                );

                try
                {
                    CompCamera camera = CompScene.camera(renderContext);
                    Vector3f rayA = camera.unproject(compX, compY, UICompositorPanel.this.doc.width,
                        UICompositorPanel.this.doc.height, 0F);
                    Vector3f rayB = camera.unproject(compX, compY, UICompositorPanel.this.doc.width,
                        UICompositorPanel.this.doc.height, 1000F);
                    Vector3f origin = this.startWorld.transformPosition(new Vector3f(0F, 0F, 0F));
                    Vector3f normal = this.startWorld.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
                    Vector3f direction = new Vector3f(rayB).sub(rayA);
                    float denominator = direction.dot(normal);

                    if (Math.abs(denominator) < 0.00001F)
                    {
                        return null;
                    }

                    float t = new Vector3f(origin).sub(rayA).dot(normal) / denominator;

                    if (t < 0F)
                    {
                        return null;
                    }

                    Vector3f local = new Vector3f(rayA).fma(t, direction);

                    this.startInverse.transformPosition(local);

                    return new float[] {local.x, local.y};
                }
                catch (RuntimeException e)
                {
                    return null;
                }
            }

            /** Ray-casting point-in-quad for the projected 3D selection box. */
            private boolean pointInQuad(float x, float y, float[] corners)
            {
                boolean inside = false;

                for (int i = 0, j = 3; i < 4; j = i++)
                {
                    float xi = corners[i * 2];
                    float yi = corners[i * 2 + 1];
                    float xj = corners[j * 2];
                    float yj = corners[j * 2 + 1];

                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                    {
                        inside = !inside;
                    }
                }

                return inside;
            }

            /**
             * A line, as <b>one</b> rotated quad.
             *
             * <p>The old version stepped along the segment and emitted a 2×2 box every two pixels:
             * a 700 px frustum edge cost 350 quads, and the 3D grid emitted tens of thousands per
             * frame. Rotating the matrix stack once and drawing a single box is the same picture
             * for one quad — this alone was a measurable part of "the camera view crawls".</p>
             */
            /** Overlay alpha setting, clamped so a stray config value cannot erase the UI. */
            private int overlayColor(int color)
            {
                return UiGfx.mulA(color, Math.max(0F, Math.min(1F, CompPrefs.get().gizmoOpacity)));
            }

            private float overlayThickness()
            {
                return Math.max(0.4F, Math.min(3F, CompPrefs.get().gizmoThickness));
            }

            private void line(UIContext context, float x1, float y1, float x2, float y2, int color)
            {
                color = this.overlayColor(color);
                float dx = x2 - x1;
                float dy = y2 - y1;
                float length = (float) Math.sqrt(dx * dx + dy * dy);

                if (length < 0.01F)
                {
                    return;
                }

                MatrixStack stack = context.batcher.getContext().getMatrices();

                stack.push();
                stack.translate(x1, y1, 0F);
                stack.multiply(new org.joml.Quaternionf().rotateZ((float) Math.atan2(dy, dx)));
                float half = 0.75F * this.overlayThickness();

                context.batcher.box(0F, -half, length, half, color);
                stack.pop();
            }

            /** Faint reference lines (3D grid). Kept as one quad per segment, like {@link #line}. */
            private void coarseLine(UIContext context, float x1, float y1, float x2, float y2, int color)
            {
                this.line(context, x1, y1, x2, y2, color);
            }

            private float fitScale()
            {
                return Math.min(this.area.w / (float) UICompositorPanel.this.doc.width, this.area.h / (float) UICompositorPanel.this.doc.height);
            }

            /**
             * Snapshot of everything the pen tool needs to project a layer-local point to the
             * screen this frame. The preview is fit-only (no zoom/pan), so scale/ox/oy are simply
             * the letterbox fit; the 3D workspace instead projects through the free viewport camera.
             */
            private UIPenTool.View penView()
            {
                UIPenTool.View view = new UIPenTool.View();

                view.area = this.area;
                view.areaX = this.area.x;
                view.areaY = this.area.y;
                view.areaW = this.area.w;
                view.areaH = this.area.h;
                view.space3D = UICompositorPanel.this.is3DSpace();

                if (UICompositorPanel.this.doc != null)
                {
                    view.rc = new CompRenderContext(UICompositorPanel.this.project, UICompositorPanel.this.doc,
                        UICompositorPanel.this.time, null, null, null);
                    view.compWidth = UICompositorPanel.this.doc.width;
                    view.compHeight = UICompositorPanel.this.doc.height;
                }

                if (view.space3D)
                {
                    view.viewport = this.viewport();
                }
                else if (UICompositorPanel.this.doc != null)
                {
                    view.scale = this.fitScale();
                    view.ox = (this.area.w - UICompositorPanel.this.doc.width * view.scale) / 2F;
                    view.oy = (this.area.h - UICompositorPanel.this.doc.height * view.scale) / 2F;
                }

                return view;
            }

            private void applyDrag(UIContext context)
            {
                /* The pen gets first refusal on a held left button: while a vertex/handle drag is
                 * armed the layer must not also move. */
                if (UICompositorPanel.this.penTool().onDrag(context, UICompositorPanel.this.lastPenView))
                {
                    return;
                }

                if (this.maskDrag != MASK_DRAG_NONE)
                {
                    this.applyMaskDrag(context);

                    return;
                }

                if (this.vpMode != VP_NONE)
                {
                    this.applyViewportDrag(context);

                    return;
                }

                if (this.dragMode == DRAG_NONE)
                {
                    return;
                }

                if (!Window.isMouseButtonPressed(0) || UICompositorPanel.this.selectedLayer == null || UICompositorPanel.this.doc == null)
                {
                    this.endDrag();
                    return;
                }

                CompLayer layer = UICompositorPanel.this.selectedLayer;
                float scale = Math.max(0.0001F, this.fitScale());
                /* A click with a 1px jitter must not nudge the layer. The old threshold was in
                 * composition units (0.01px), which is effectively zero at any preview scale. */
                if (Math.abs(context.mouseX - this.dragStartX) < 3 && Math.abs(context.mouseY - this.dragStartY) < 3)
                {
                    return;
                }

                float dx = (context.mouseX - this.dragStartX) / scale;
                float dy = (context.mouseY - this.dragStartY) / scale;
                float[] local = this.startLocalPoint(context, layer);

                if (this.dragMode == DRAG_CAMERA_BODY || this.dragMode == DRAG_CAMERA_TARGET)
                {
                    this.dragCamera(context, layer, scale);
                    return;
                }

                if (this.dragMode == DRAG_GIZMO)
                {
                    this.applyGizmoDrag(context);

                    return;
                }

                if (this.dragMode == DRAG_BOX_3D)
                {
                    if (this.dragHandle == 30)
                    {
                        float distance = (float) Math.sqrt(
                            (context.mouseX - this.startScreenCenterX) * (context.mouseX - this.startScreenCenterX)
                            + (context.mouseY - this.startScreenCenterY) * (context.mouseY - this.startScreenCenterY));
                        float factor = Math.max(0.01F, distance / Math.max(1F, this.startScreenDistance));
                        float sx = Math.max(0.01F, Math.min(100F, this.startScaleX * factor));
                        float sy = Math.max(0.01F, Math.min(100F, this.startScaleY * factor));

                        this.writeTransform(layer, sx, sy, null);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "等比缩放 X %.0f%%  Y %.0f%%", sx * 100F, sy * 100F));

                        return;
                    }

                    float[] boxLocal = this.viewportLocalPoint(context, layer,
                        this.viewportContext(), this.viewport(), this.startWorld);

                    if (boxLocal == null)
                    {
                        return;
                    }

                    float[] scales = CompTransformHandles.scaled(this.dragHandle,
                        this.startLocalX, this.startLocalY, boxLocal[0], boxLocal[1],
                        this.startScaleX, this.startScaleY, false);

                    this.writeTransform(layer, scales[0], scales[1], null);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                        "缩放 X %.0f%%  Y %.0f%%", scales[0] * 100F, scales[1] * 100F));

                    return;
                }

                if (layer.threeD)
                {
                    float nowX = context.mouseX - this.startScreenCenterX;
                    float nowY = context.mouseY - this.startScreenCenterY;
                    float distance = (float) Math.sqrt(nowX * nowX + nowY * nowY);
                    float angle = (float) Math.toDegrees(Math.atan2(nowY, nowX));

                    if (this.dragMode == DRAG_ROTATE)
                    {
                        float delta = angle - this.startScreenAngle;

                        while (delta > 180F) delta -= 360F;
                        while (delta < -180F) delta += 360F;

                        this.writeTransform(layer, null, null, this.startRotation + delta);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "旋转 %.1f°", this.startRotation + delta));

                        return;
                    }

                    if (this.dragMode == DRAG_CORNER || this.dragMode == DRAG_EDGE)
                    {
                        float[] planeLocal = this.startPlaneValid ? this.startLocalPointOnLayerPlane(context) : null;
                        float sx;
                        float sy;

                        if (planeLocal != null)
                        {
                            float[] scales = CompTransformHandles.scaled(
                                this.dragHandle,
                                this.startLocalX, this.startLocalY,
                                planeLocal[0], planeLocal[1],
                                this.startScaleX, this.startScaleY,
                                this.dragMode == DRAG_CORNER && Window.isShiftPressed()
                            );

                            sx = scales[0];
                            sy = scales[1];
                        }
                        else
                        {
                            /* Edge-on plane: no reliable local hit. Fall back to the old radial
                             * ratio, but still honour the handle's axis so a side grip never
                             * changes height. */
                            float ratio = Math.max(0.01F,
                                this.startScreenDistance <= 0.001F ? 1F : distance / this.startScreenDistance);

                            sx = this.startScaleX;
                            sy = this.startScaleY;

                            if (this.dragMode == DRAG_EDGE)
                            {
                                if (this.dragHandle == 20 || this.dragHandle == 22)
                                {
                                    sy *= ratio;
                                }
                                else
                                {
                                    sx *= ratio;
                                }
                            }
                            else
                            {
                                sx *= ratio;
                                sy *= ratio;
                            }

                            sx = Math.max(CompTransformHandles.MIN_SCALE, Math.min(CompTransformHandles.MAX_SCALE, sx));
                            sy = Math.max(CompTransformHandles.MIN_SCALE, Math.min(CompTransformHandles.MAX_SCALE, sy));
                        }

                        this.writeTransform(layer, sx, sy, null);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "缩放 X %.0f%%   Y %.0f%%%s", sx * 100F, sy * 100F,
                            this.dragMode == DRAG_CORNER ? "（Shift 等比）" : ""));

                        return;
                    }
                }

                if (this.dragMode == DRAG_ROTATE)
                {
                    float angle = (float) Math.toDegrees(Math.atan2(local[1], local[0]));
                    float delta = angle - this.startAngle;

                    while (delta > 180F) delta -= 360F;
                    while (delta < -180F) delta += 360F;

                    float rotation = this.startRotation + delta;

                    if (Window.isShiftPressed())
                    {
                        rotation = Math.round(rotation / 15F) * 15F;
                    }

                    this.writeTransform(layer, null, null, rotation);

                    return;
                }

                if (this.dragMode == DRAG_CORNER || this.dragMode == DRAG_EDGE)
                {
                    float[] scales = CompTransformHandles.scaled(
                        this.dragHandle,
                        this.startLocalX, this.startLocalY,
                        local[0], local[1],
                        this.startScaleX, this.startScaleY,
                        this.dragMode == DRAG_CORNER && Window.isShiftPressed()
                    );
                    float sx = scales[0];
                    float sy = scales[1];

                    this.writeTransform(layer, sx, sy, null);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                        "缩放 X %.0f%%   Y %.0f%%%s", sx * 100F, sy * 100F,
                        this.dragMode == DRAG_CORNER ? "（Shift 等比）" : ""));

                    return;
                }

                if (Math.abs(dx) < 0.01F && Math.abs(dy) < 0.01F)
                {
                    return;
                }

                /* A parented layer's x/y live in the parent's space. A raw comp-pixel delta would be
                 * scaled/rotated twice by a transformed parent, so the cursor and the layer would
                 * drift apart. Convert the delta into the parent's basis first. */
                float moveDx = dx;
                float moveDy = dy;

                if (layer.threeD)
                {
                    /* A 3D layer lives on its own Z plane: the screen-to-comp scale there is
                     * zoom * distance / (distance - z), so a flat pixel delta drifts. Unproject
                     * both ends of the drag onto that plane instead. */
                    CompRenderContext renderContext = new CompRenderContext(
                        UICompositorPanel.this.project, UICompositorPanel.this.doc, UICompositorPanel.this.time, null, null, null
                    );
                    CompCamera camera = CompScene.camera(renderContext);
                    float fit = Math.max(0.0001F, this.fitScale());
                    float ox = (this.area.w - UICompositorPanel.this.doc.width * fit) / 2F;
                    float oy = (this.area.h - UICompositorPanel.this.doc.height * fit) / 2F;
                    float z = layer.scalarAt("z", layer.z, UICompositorPanel.this.getFrame());
                    org.joml.Vector3f from = camera.unproject(
                        (this.dragStartX - this.area.x - ox) / fit,
                        (this.dragStartY - this.area.y - oy) / fit,
                        UICompositorPanel.this.doc.width, UICompositorPanel.this.doc.height, z
                    );
                    org.joml.Vector3f to = camera.unproject(
                        (context.mouseX - this.area.x - ox) / fit,
                        (context.mouseY - this.area.y - oy) / fit,
                        UICompositorPanel.this.doc.width, UICompositorPanel.this.doc.height, z
                    );

                    moveDx = to.x - from.x;
                    moveDy = to.y - from.y;
                }

                org.joml.Vector2f localDelta = dev.bbsae.comp.CompParenting.toLocalDelta(
                    UICompositorPanel.this.doc, layer, UICompositorPanel.this.getFrame(), moveDx, moveDy
                );

                if (UICompositorPanel.this.getSelectionCount() > 1)
                {
                    /* Multi-drag: every selected layer moves by the same composition-space delta,
                     * each converted into its own parent's basis. */
                    for (CompLayer moving : UICompositorPanel.this.getSelectedLayers())
                    {
                        float[] start = this.dragStarts.get(moving.id);

                        if (start == null || moving.locked)
                        {
                            continue;
                        }

                        org.joml.Vector2f movingDelta = dev.bbsae.comp.CompParenting.toLocalDelta(
                            UICompositorPanel.this.doc, moving, UICompositorPanel.this.getFrame(), dx, dy
                        );

                        this.writeTransform(moving, null, null, null, start[0] + movingDelta.x, start[1] + movingDelta.y);
                    }

                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                        "移动 %d 个图层  Δ %.0f, %.0f", UICompositorPanel.this.getSelectionCount(), dx, dy));

                    return;
                }

                float x = this.layerStartX + localDelta.x;
                float y = this.layerStartY + localDelta.y;

                this.writeTransform(layer, null, null, null, x, y);
                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "位置 X %.0f  Y %.0f", x, y));

                if ("camera".equals(layer.type) && layer.cameraLookAt)
                {
                    /* Dragging a two-node camera must pan, not spin: the point of interest keeps
                     * its offset from the camera instead of staying nailed to the comp centre. */
                    float tx = this.targetStartX + dx;
                    float ty = this.targetStartY + dy;

                    layer.cameraTargetX = tx;
                    layer.cameraTargetY = ty;
                    UICompositorPanel.this.setScalar(layer, "cameraTargetX", tx);
                    UICompositorPanel.this.setScalar(layer, "cameraTargetY", ty);
                }
            }

            /* ------------------------------------------------------- 3D viewport */

            /**
             * Starts viewport navigation from raw mouse state.
             *
             * <p>Right button orbits (middle too). This runs before anything else in the frame, so
             * a press is seen even if the framework consumed the click for a context menu. Look-
             * through is exited on the press, exactly like Blender's numpad-0 view.</p>
             */
            private void pollViewportInput(UIContext context)
            {
                if (!UICompositorPanel.this.is3DSpace() || Boolean.getBoolean("bbsae.debugViewLock"))
                {
                    this.vpRightWasDown = false;
                    this.vpMiddleWasDown = false;
                    this.vpLeftWasDown = false;

                    return;
                }

                /* Verification hook: orbit without any input. If the picture does not move with
                 * this on, the problem is the camera/render path; if it moves, the problem is
                 * input delivery. */
                String autoOrbit = System.getProperty("bbsae.debugOrbit", "");

                if (!autoOrbit.isBlank())
                {
                    try
                    {
                        float amount = Float.parseFloat(autoOrbit);

                        UICompositorPanel.this.viewportCamera.orbit(amount, 0F);
                        UICompositorPanel.this.viewportCamera.update();
                    }
                    catch (NumberFormatException ignored)
                    {
                        /* a bad value must not break the viewport */
                    }
                }

                boolean right = Window.isMouseButtonPressed(1);
                boolean middle = Window.isMouseButtonPressed(2);
                boolean left = Window.isMouseButtonPressed(0);
                boolean inside = this.area.isInside(context);

                if (this.vpMode == VP_NONE && inside)
                {
                    if (right && !this.vpRightWasDown)
                    {
                        if (UICompositorPanel.this.cameraPreviewMode)
                        {
                            UICompositorPanel.this.toggleViewportLookThrough();
                        }

                        this.beginViewportDrag(context, CompInput.shiftDown() ? VP_PAN : VP_ORBIT, 1);
                    }
                    else if (middle && !this.vpMiddleWasDown)
                    {
                        this.beginViewportDrag(context, CompInput.shiftDown() ? VP_PAN : VP_ORBIT, 2);
                    }
                    else if (left && !this.vpLeftWasDown)
                    {
                        /* Left drag is the gesture everybody tries first. On empty canvas (or with
                         * Alt held) it orbits; on a layer it keeps its old meaning (move the
                         * layer), which the click handler decides. This is also the only way to
                         * naviate on a two-button trackpad where the right click is emulated. */
                        boolean empty = this.viewportCameraHandle(context) < 0
                            && dev.bbsae.client.render.CompViewportRenderer.pick(
                                this.viewportContext(), this.viewport(), this.area.x, this.area.y,
                                context.mouseX, context.mouseY) == null;

                        if (empty || CompInput.altDown())
                        {
                            if (UICompositorPanel.this.cameraPreviewMode)
                            {
                                UICompositorPanel.this.toggleViewportLookThrough();
                            }

                            this.vpFromEmpty = empty;
                            this.vpMoved = false;
                            this.beginViewportDrag(context, CompInput.shiftDown() ? VP_PAN : VP_ORBIT, 0);
                        }
                    }
                }

                this.vpRightWasDown = right;
                this.vpMiddleWasDown = middle;
                this.vpLeftWasDown = left;
            }

            /**
             * AE camera tool input.
             *
             * <p>The tool is a mode, like AE's unified camera tool: while it is on, the three mouse
             * buttons are camera gestures and the preview stops picking layers. That separation is
             * the whole reason the mode exists — every gesture on a camera is destructive to the
             * frame, so it must not also be a layer drag or a context menu.</p>
             *
             * <ul>
             *   <li>left drag — orbit around the point of interest</li>
             *   <li>middle drag — track XY (move camera and target in the view plane)</li>
             *   <li>right drag — track Z (dolly along the view axis)</li>
             * </ul>
             *
             * <p>With no camera every gesture is a status hint. It never creates a camera: an
             * explicit "新建摄像机" action is the only creator in AE.</p>
             */
            private void pollCameraToolInput(UIContext context)
            {
                if (UICompositorPanel.this.cameraToolCancelRequested)
                {
                    UICompositorPanel.this.cameraToolCancelRequested = false;

                    if (this.ctMode != CT_NONE)
                    {
                        CompLayer cancelledCamera = UICompositorPanel.this.doc == null ? null
                            : UICompositorPanel.this.doc.activeCamera(UICompositorPanel.this.time);

                        if (cancelledCamera != null)
                        {
                            /* Restore the pose captured on press, exactly like every other drag:
                             * "ended" without a restore is the same trust bug as a fake cancel. */
                            this.writeTransform(cancelledCamera, null, null, null, this.ctStartEye.x, this.ctStartEye.y);
                            cancelledCamera.cameraZ = this.ctStartEye.z;
                            cancelledCamera.z = this.ctStartEye.z;
                            UICompositorPanel.this.setScalar(cancelledCamera, "cameraZ", this.ctStartEye.z);
                            cancelledCamera.cameraLookAt = this.ctStartLookAt;

                            if (cancelledCamera.cameraLookAt)
                            {
                                UICompositorPanel.this.setScalar(cancelledCamera, "cameraTargetX", this.ctStartTarget.x);
                                UICompositorPanel.this.setScalar(cancelledCamera, "cameraTargetY", this.ctStartTarget.y);
                                UICompositorPanel.this.setScalar(cancelledCamera, "cameraTargetZ", this.ctStartTarget.z);
                            }
                        }

                        this.endCameraToolDrag();
                        UICompositorPanel.this.discardCancelledAction();
                        UICompositorPanel.this.setStatus("已取消摄像机拖动（Esc）");
                    }
                }

                if (!UICompositorPanel.this.cameraToolActive()
                    || UICompositorPanel.this.is3DSpace()
                    || UICompositorPanel.this.doc == null
                    || Boolean.getBoolean("bbsae.debugViewLock"))
                {
                    if (this.ctMode != CT_NONE)
                    {
                        this.endCameraToolDrag();
                    }

                    /* Turning the tool off mid-press left cameraToolDragging true; the next Esc
                     * was then swallowed by a drag that no longer existed. */
                    UICompositorPanel.this.cameraToolDragging = false;
                    this.ctLeftWasDown = false;
                    this.ctMiddleWasDown = false;
                    this.ctRightWasDown = false;
                    this.ctMode = CT_NONE;

                    return;
                }

                boolean left = Window.isMouseButtonPressed(0);
                boolean middle = Window.isMouseButtonPressed(2);
                boolean right = Window.isMouseButtonPressed(1);
                boolean inside = this.area.isInside(context);

                if (this.ctMode == CT_NONE && inside)
                {
                    if ((left && !this.ctLeftWasDown) || (middle && !this.ctMiddleWasDown)
                        || (right && !this.ctRightWasDown))
                    {
                        CompLayer camera = UICompositorPanel.this.doc.activeCamera(UICompositorPanel.this.time);

                        if (camera == null)
                        {
                            UICompositorPanel.this.setStatus("还没有摄像机：点预览右侧「新建摄像机」（AE 摄像机工具不会自动创建）");
                        }
                        else
                        {
                            int mode = left ? CT_ORBIT : (middle ? CT_TRACK_XY : CT_TRACK_Z);
                            int button = left ? 0 : (middle ? 2 : 1);

                            this.beginCameraToolDrag(camera, mode, button, context);
                        }
                    }
                }
                else if (this.ctMode != CT_NONE)
                {
                    boolean stillDown = this.ctButton == 0 ? left : (this.ctButton == 2 ? middle : right);

                    if (stillDown)
                    {
                        this.applyCameraToolDrag(context);
                    }
                    else
                    {
                        this.endCameraToolDrag();
                    }
                }

                this.ctLeftWasDown = left;
                this.ctMiddleWasDown = middle;
                this.ctRightWasDown = right;
            }

            private void beginCameraToolDrag(CompLayer camera, int mode, int button, UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;
                CompRenderContext renderContext = new CompRenderContext(UICompositorPanel.this.project,
                    doc, UICompositorPanel.this.time, null, null, null);
                CompCamera view = CompScene.camera(renderContext);

                this.ctMode = mode;
                this.ctButton = button;
                this.ctStartX = context.mouseX;
                this.ctStartY = context.mouseY;
                this.ctStartEye.set(CompCamera.eyeOf(camera, UICompositorPanel.this.getFrame(), doc.height));
                this.ctStartZoom = Math.max(0.0001F, view.zoom);
                this.ctStartLookAt = camera.cameraLookAt;
                this.ctStartDistance = Math.max(1F, view.distance);
                this.ctStartRight.set(view.right);
                this.ctStartUp.set(view.up);
                UICompositorPanel.this.cameraToolDragging = true;
                /* One drag = one undo step; the sampler alone merged camera and layer edits made
                 * within the same second. */
                UICompositorPanel.this.beginAction("摄像机工具");

                if (camera.cameraLookAt)
                {
                    this.ctStartTarget.set(camera.cameraTarget(UICompositorPanel.this.getFrame()));
                }
                else
                {
                    /* A free camera has no authored pivot, so orbit/dolly use the point at the
                     * current dolly distance along the view direction — the same pivot the 3D
                     * viewport orbits around. */
                    this.ctStartTarget.set(this.ctStartEye)
                        .add(new org.joml.Vector3f(view.forward).mul(this.ctStartDistance));
                }

                /* Dolly must scale the eye→target radius, not |cameraZ|: with an off-axis point of
                 * interest (the normal AE framing) the two differ and the first Track Z event
                 * teleported the eye onto the z axis. */
                this.ctStartRadius = Math.max(1F, this.ctStartEye.distance(this.ctStartTarget));
            }

            private void applyCameraToolDrag(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;
                CompLayer camera = doc.activeCamera(UICompositorPanel.this.time);

                if (camera == null)
                {
                    this.ctMode = CT_NONE;

                    return;
                }

                float frame = UICompositorPanel.this.getFrame();
                float scale = Math.max(0.0001F, this.fitScale());
                float dx = context.mouseX - this.ctStartX;
                float dy = context.mouseY - this.ctStartY;

                switch (this.ctMode)
                {
                    case CT_ORBIT ->
                    {
                        if (camera.cameraStyle == CompLayer.CAMERA_BBS)
                        {
                            /* A BBS fly camera has no point of interest: its rotationY/X *is* the
                             * view. Orbiting it on a look-at pivot would move the eye while the
                             * renderer ignored the pivot and kept the old direction, so drive the
                             * free-camera fields instead. */
                            CompViewControls.orbit(camera, frame, dx * 0.35F, dy * 0.35F);
                            this.writeTransform(camera, null, null, null, camera.x, camera.y);
                            UICompositorPanel.this.setScalar(camera, "rotationY", camera.rotationY);
                            UICompositorPanel.this.setScalar(camera, "rotationX", camera.rotationX);
                            UICompositorPanel.this.setScalar(camera, "cameraZ", camera.cameraZ);
                            UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                                "AE 环绕（BBS 飞行摄像机）· 位置 %.0f, %.0f, %.0f",
                                camera.x, camera.y, camera.cameraZ));

                            return;
                        }

                        org.joml.Vector3f eye = dev.bbsae.client.render.CompCameraOps.orbit(
                            this.ctStartEye, this.ctStartTarget, dx * 0.35F, dy * 0.35F);

                        /* Orbiting an AE camera is a two-node operation: the point of interest is
                         * the pivot, so the camera keeps looking at it while its position moves. */
                        camera.cameraLookAt = true;
                        this.writeCameraToolPose(camera, eye, this.ctStartTarget);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "AE 环绕 · 位置 %.0f, %.0f, %.0f", eye.x, eye.y, eye.z));
                    }
                    case CT_TRACK_XY ->
                    {
                        /* Screen delta -> composition pixels (fit) -> camera-space units at the point
                         * of interest (view zoom). Without the zoom factor, Track XY ran ahead of the
                         * cursor after any dolly; AE/effectcraft use distance/focal (1/zoom) here. */
                        float worldPerPixel = dev.bbsae.client.render.CameraToolMath.worldPerScreenPixel(
                            scale, this.ctStartZoom);
                        org.joml.Vector3f delta = new org.joml.Vector3f(this.ctStartRight).mul(dx * worldPerPixel)
                            .add(new org.joml.Vector3f(this.ctStartUp).mul(-dy * worldPerPixel));
                        org.joml.Vector3f eye = new org.joml.Vector3f(this.ctStartEye).add(delta);
                        org.joml.Vector3f target = new org.joml.Vector3f(this.ctStartTarget).add(delta);

                        this.writeCameraToolPose(camera, eye, target);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "AE Track XY · 位置 %.0f, %.0f", eye.x, eye.y));
                    }
                    case CT_TRACK_Z ->
                    {
                        float factor = dev.bbsae.client.render.CompCameraOps.dollyFactor(dx);
                        float distance = Math.max(camera.cameraNear + 10F,
                            Math.min(camera.cameraFar - 10F, this.ctStartRadius * factor));
                        org.joml.Vector3f direction = new org.joml.Vector3f(this.ctStartEye).sub(this.ctStartTarget);
                        float length = direction.length();

                        if (length < 0.0001F)
                        {
                            return;
                        }

                        direction.mul(1F / length);

                        org.joml.Vector3f eye = new org.joml.Vector3f(this.ctStartTarget).add(direction.mul(distance));

                        this.writeCameraToolPose(camera, eye, this.ctStartTarget);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "AE Track Z · 距离 %.0f", distance));
                    }
                    default ->
                    {
                        /* nothing to do */
                    }
                }
            }

            private void writeCameraToolPose(CompLayer camera, org.joml.Vector3f eye, org.joml.Vector3f target)
            {
                this.writeTransform(camera, null, null, null, eye.x, eye.y);
                camera.cameraZ = eye.z;
                camera.z = eye.z;
                UICompositorPanel.this.setScalar(camera, "cameraZ", eye.z);

                if (camera.cameraLookAt)
                {
                    camera.cameraTargetX = target.x;
                    camera.cameraTargetY = target.y;
                    camera.cameraTargetZ = target.z;
                    UICompositorPanel.this.setScalar(camera, "cameraTargetX", target.x);
                    UICompositorPanel.this.setScalar(camera, "cameraTargetY", target.y);
                    UICompositorPanel.this.setScalar(camera, "cameraTargetZ", target.z);
                }
            }

            private void endCameraToolDrag()
            {
                UICompositorPanel.this.cameraToolDragging = false;
                UICompositorPanel.this.cameraToolCancelRequested = false;

                if (this.ctMode != CT_NONE)
                {
                    this.ctMode = CT_NONE;
                    UICompositorPanel.this.refreshInspector();
                    UICompositorPanel.this.save();
                }
            }

            private boolean viewportActive()
            {
                return UICompositorPanel.this.is3DSpace() && !UICompositorPanel.this.cameraPreviewMode
                    && !Boolean.getBoolean("bbsae.debugViewLock");
            }

            private dev.bbsae.client.render.CompViewportCamera viewport()
            {
                dev.bbsae.client.render.CompViewportCamera camera = UICompositorPanel.this.viewportCamera;

                camera.setViewport(Math.max(1, this.area.w), Math.max(1, this.area.h));
                camera.update();

                return camera;
            }

            private CompRenderContext viewportContext()
            {
                return new CompRenderContext(UICompositorPanel.this.project, UICompositorPanel.this.doc,
                    UICompositorPanel.this.time, null, null, null);
            }

            private void beginViewportDrag(UIContext context, int mode, int button)
            {
                if (this.vpMode != VP_NONE)
                {
                    /* The poller and the click event can both see the same press; the second one
                     * must not reset the drag origin (that makes the view jitter). */
                    return;
                }

                dev.bbsae.client.render.CompViewportCamera camera = this.viewport();
                CompDocument doc = UICompositorPanel.this.doc;

                if (Boolean.getBoolean("bbsae.debugViewport"))
                {
                    dev.bbsae.BbsAeAddon.LOGGER.info("viewport input: drag start mode={} button={} at {},{}",
                        mode, button, context.mouseX, context.mouseY);
                }

                this.vpMode = mode;
                this.vpButton = button;
                this.vpStartX = context.mouseX;
                this.vpStartY = context.mouseY;
                this.vpStartYaw = camera.yaw;
                this.vpStartPitch = camera.pitch;
                this.vpStartTarget.set(camera.target);

                CompLayer active = doc == null ? null : doc.activeCamera(UICompositorPanel.this.time);

                this.vpCameraId = active == null ? null : active.id;

                if (active != null)
                {
                    this.vpStartEye.set(CompCamera.eyeOf(active, UICompositorPanel.this.getFrame(), doc.height));
                    this.vpShotTarget.set(active.cameraTarget(UICompositorPanel.this.getFrame()));
                }

                if (mode == VP_MOVE)
                {
                    this.dragStarts.clear();

                    for (CompLayer moving : UICompositorPanel.this.getSelectedLayers())
                    {
                        Transform start = moving.transformAt(UICompositorPanel.this.getFrame());

                        this.dragStarts.put(moving.id, new float[] {
                            start.translate.x,
                            start.translate.y,
                            moving.scalarAt("z", moving.z, UICompositorPanel.this.getFrame())
                        });
                    }
                }
            }

            private void endViewportDrag()
            {
                boolean moved = this.vpMode != VP_NONE;

                if (moved && Boolean.getBoolean("bbsae.debugViewport"))
                {
                    dev.bbsae.BbsAeAddon.LOGGER.info("viewport input: drag end mode={} moved={}", this.vpMode, this.vpMoved);
                }

                /* A left press on empty canvas that did not move is still a click: clear the
                 * selection, exactly like before the orbit existed. */
                if (this.vpMode == VP_ORBIT && this.vpButton == 0 && this.vpFromEmpty && !this.vpMoved)
                {
                    UICompositorPanel.this.clearSelection();
                }

                this.vpFromEmpty = false;
                this.vpMoved = false;
                this.vpMode = VP_NONE;

                if (moved)
                {
                    UICompositorPanel.this.saveViewportPose();
                    UICompositorPanel.this.refreshInspector();
                    UICompositorPanel.this.savePanelLayout();
                }
            }

            /**
             * Orbit / pan / object drag, all computed from the drag start.
             *
             * <p>Object and camera drags intersect the cursor ray with the plane that contains the
             * object and faces the view. That is why they track the cursor exactly at any zoom,
             * FOV or angle — a screen-delta times a scale constant (the old approach) drifts as
             * soon as the view is not square-on.</p>
             */
            private void applyViewportDrag(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;

                if (doc == null || !Window.isMouseButtonPressed(this.vpButton))
                {
                    this.endViewportDrag();

                    return;
                }

                dev.bbsae.client.render.CompViewportCamera camera = this.viewport();
                float dx = context.mouseX - this.vpStartX;
                float dy = context.mouseY - this.vpStartY;

                if (Math.abs(dx) > 3F || Math.abs(dy) > 3F)
                {
                    this.vpMoved = true;
                }
                float localX = context.mouseX - this.area.x;
                float localY = context.mouseY - this.area.y;
                float startLocalX = this.vpStartX - this.area.x;
                float startLocalY = this.vpStartY - this.area.y;

                switch (this.vpMode)
                {
                    case VP_ORBIT ->
                    {
                        camera.setPose(
                            this.vpStartYaw - dx * dev.bbsae.client.render.CompViewportCamera.ORBIT_DEGREES_PER_PIXEL,
                            this.vpStartPitch + dy * dev.bbsae.client.render.CompViewportCamera.ORBIT_DEGREES_PER_PIXEL,
                            camera.distance);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "环绕  偏航 %.0f°  俯仰 %.0f°", camera.yaw, camera.pitch));
                    }
                    case VP_PAN ->
                    {
                        camera.target.set(this.vpStartTarget);
                        camera.pan(dx, dy);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                            "平移  目标 %.0f, %.0f, %.0f", camera.target.x, camera.target.y, camera.target.z));
                    }
                    case VP_MOVE -> this.dragLayersInViewport(doc, camera, startLocalX, startLocalY, localX, localY, false);
                    case VP_CAM_BODY -> this.dragCameraInViewport(doc, camera, startLocalX, startLocalY, localX, localY);
                    case VP_CAM_TARGET -> this.aimCameraInViewport(doc, camera, localX, localY);
                    case VP_CAM_ORBIT -> this.orbitCameraInViewport(doc, localX, localY);
                    default -> { }
                }
            }

            /** Moves the selected layers on the plane that faces the view. */
            private void dragLayersInViewport(CompDocument doc, dev.bbsae.client.render.CompViewportCamera camera,
                                              float startX, float startY, float mouseX, float mouseY, boolean vertical)
            {
                CompLayer primary = UICompositorPanel.this.selectedLayer;

                if (primary == null)
                {
                    return;
                }

                CompRenderContext context = this.viewportContext();
                Matrix4f world = CompScene.world(context, primary);
                org.joml.Vector3f origin = new org.joml.Vector3f();

                world.transformPosition(origin);

                org.joml.Vector3f normal = new org.joml.Vector3f(0F, 0F, 1F);

                world.transformDirection(normal);
                normal.normalize();

                org.joml.Vector3f from = camera.unprojectPlane(startX, startY, origin, normal);
                org.joml.Vector3f to = camera.unprojectPlane(mouseX, mouseY, origin, normal);

                if (from == null || to == null)
                {
                    return;
                }

                float dx = to.x - from.x;
                float dy = to.y - from.y;
                float dz = to.z - from.z;

                for (CompLayer moving : UICompositorPanel.this.getSelectedLayers())
                {
                    float[] start = this.dragStarts.get(moving.id);

                    if (start == null || moving.locked)
                    {
                        continue;
                    }

                    org.joml.Vector2f delta = dev.bbsae.comp.CompParenting.toLocalDelta(doc, moving,
                        UICompositorPanel.this.getFrame(), dx, dy);

                    this.writeTransform(moving, null, null, null, start[0] + delta.x, start[1] + delta.y);

                    /* A 3D layer also has a z channel; the drag's depth component is applied there
                     * so the object stays under the cursor while the view is tilted. The start value
                     * comes from the drag snapshot — reading the live channel would add the delta
                     * again on every mouse event and the layer would fly away. */
                    if (moving.threeD && start.length > 2 && Math.abs(dz) > 0.0001F)
                    {
                        float z = start[2] + dz;

                        moving.z = z;
                        UICompositorPanel.this.setScalar(moving, "z", z);
                    }
                }

                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                    "X %.0f  Y %.0f  Z %.0f", primary.x, primary.y, primary.z));
            }

            /** Moves the composition camera's eye; the point of interest follows, so the aim holds. */
            private void dragCameraInViewport(CompDocument doc, dev.bbsae.client.render.CompViewportCamera camera,
                                              float startX, float startY, float mouseX, float mouseY)
            {
                CompLayer layer = this.vpCameraId == null ? null : doc.getLayer(this.vpCameraId);

                if (layer == null)
                {
                    return;
                }

                org.joml.Vector3f delta;

                if (CompInput.shiftDown())
                {
                    /* Shift = along the view direction (Blender's "move along local Z"). */
                    float amount = -(mouseY - startY) * camera.worldPerPixel();

                    delta = new org.joml.Vector3f(camera.forward).mul(-amount);
                }
                else
                {
                    org.joml.Vector3f from = camera.unprojectPlane(startX, startY, this.vpStartEye, camera.forward);
                    org.joml.Vector3f to = camera.unprojectPlane(mouseX, mouseY, this.vpStartEye, camera.forward);

                    if (from == null || to == null)
                    {
                        return;
                    }

                    delta = to.sub(from);
                }

                float x = this.vpStartEye.x + delta.x;
                float y = this.vpStartEye.y + delta.y;
                float z = this.vpStartEye.z + delta.z;

                this.writeTransform(layer, null, null, null, x, y);

                /* The distance channel is the camera's Z; writing it through setScalar keeps it
                 * animatable and keeps the inspector row in sync. */
                layer.cameraZ = z;
                layer.z = z;
                UICompositorPanel.this.setScalar(layer, "cameraZ", z);

                if (layer.cameraLookAt)
                {
                    float tx = this.vpStartTarget.x + delta.x;
                    float ty = this.vpStartTarget.y + delta.y;
                    float tz = this.vpStartTarget.z + delta.z;

                    layer.cameraTargetX = tx;
                    layer.cameraTargetY = ty;
                    layer.cameraTargetZ = tz;
                    UICompositorPanel.this.setScalar(layer, "cameraTargetX", tx);
                    UICompositorPanel.this.setScalar(layer, "cameraTargetY", ty);
                    UICompositorPanel.this.setScalar(layer, "cameraTargetZ", tz);
                }

                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                    "摄像机  X %.0f  Y %.0f  Z %.0f", x, y, z));
            }

            /**
             * Orbits the composition camera around its point of interest.
             *
             * <p>The eye moves on a sphere around the target and the camera keeps looking at it,
             * so the frame tilts exactly like a real camera on a tripod head. Pitch is clamped
             * short of the poles: at 90° the view direction is parallel to the world up axis and
             * the camera's right vector becomes undefined, which makes the image spin.</p>
             */
            private void orbitCameraInViewport(CompDocument doc, float mouseX, float mouseY)
            {
                CompLayer layer = this.vpCameraId == null ? null : doc.getLayer(this.vpCameraId);

                if (layer == null)
                {
                    return;
                }

                float dx = mouseX - this.vpStartX;
                float dy = mouseY - this.vpStartY;

                org.joml.Vector3f target = new org.joml.Vector3f(this.vpShotTarget);
                org.joml.Vector3f offset = new org.joml.Vector3f(this.vpStartEye).sub(target);
                float length = offset.length();

                if (length < 1F)
                {
                    return;
                }

                /* Yaw around the world up axis (comp Y is down, so up is -Y). */
                offset.rotate(new org.joml.Quaternionf().fromAxisAngleRad(
                    new org.joml.Vector3f(0F, -1F, 0F), (float) Math.toRadians(dx * 0.35F)));

                /* Pitch around the horizontal axis that is perpendicular to the orbit radius. */
                org.joml.Vector3f right = new org.joml.Vector3f(offset).cross(new org.joml.Vector3f(0F, -1F, 0F));

                if (right.lengthSquared() > 0.0001F)
                {
                    right.normalize();

                    float elevation = (float) Math.toDegrees(Math.asin(
                        Math.max(-1F, Math.min(1F, offset.y / Math.max(0.0001F, offset.length())))));
                    float wanted = Math.max(-85F, Math.min(85F, elevation + dy * 0.35F));

                    offset.rotate(new org.joml.Quaternionf().fromAxisAngleRad(
                        right, (float) Math.toRadians(wanted - elevation)));
                }

                org.joml.Vector3f eye = new org.joml.Vector3f(target).add(offset);

                /* Looking at the target is what "orbit" means; a free camera has no pivot. */
                layer.cameraLookAt = true;
                this.writeTransform(layer, null, null, null, eye.x, eye.y);
                layer.cameraZ = eye.z;
                layer.z = eye.z;
                UICompositorPanel.this.setScalar(layer, "cameraZ", eye.z);
                UICompositorPanel.this.setScalar(layer, "cameraTargetX", target.x);
                UICompositorPanel.this.setScalar(layer, "cameraTargetY", target.y);
                UICompositorPanel.this.setScalar(layer, "cameraTargetZ", target.z);
                layer.cameraTargetX = target.x;
                layer.cameraTargetY = target.y;
                layer.cameraTargetZ = target.z;

                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                    "环绕摄像机  X %.0f  Y %.0f  Z %.0f", eye.x, eye.y, eye.z));
            }

            /** Drags the crosshair on the target's own Z plane. */
            private void aimCameraInViewport(CompDocument doc, dev.bbsae.client.render.CompViewportCamera camera,
                                             float mouseX, float mouseY)
            {
                CompLayer layer = this.vpCameraId == null ? null : doc.getLayer(this.vpCameraId);

                if (layer == null)
                {
                    return;
                }

                org.joml.Vector3f point = camera.unproject(mouseX, mouseY, this.vpStartTarget.z);

                layer.cameraLookAt = true;
                layer.cameraTargetX = point.x;
                layer.cameraTargetY = point.y;
                layer.cameraTargetZ = point.z;
                UICompositorPanel.this.setScalar(layer, "cameraTargetX", point.x);
                UICompositorPanel.this.setScalar(layer, "cameraTargetY", point.y);
                UICompositorPanel.this.setScalar(layer, "cameraTargetZ", point.z);
                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                    "目标点  %.0f, %.0f, %.0f", point.x, point.y, point.z));
            }

            /** The camera icon / crosshair under the cursor, if any. */
            private int viewportCameraHandle(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;

                if (doc == null)
                {
                    return -1;
                }

                CompLayer active = doc.activeCamera(UICompositorPanel.this.time);

                if (active == null)
                {
                    return -1;
                }

                dev.bbsae.client.render.CompViewportCamera camera = this.viewport();
                org.joml.Vector3f eye = CompCamera.eyeOf(active, UICompositorPanel.this.getFrame(), doc.height);
                org.joml.Vector2f eyeScreen = camera.project(eye);

                if (eyeScreen != null
                    && Math.abs(this.area.x + eyeScreen.x - context.mouseX) <= 14F
                    && Math.abs(this.area.y + eyeScreen.y - context.mouseY) <= 14F)
                {
                    return 40;
                }

                org.joml.Vector2f target = camera.project(active.cameraTarget(UICompositorPanel.this.getFrame()));

                if (target != null
                    && Math.abs(this.area.x + target.x - context.mouseX) <= 12F
                    && Math.abs(this.area.y + target.y - context.mouseY) <= 12F)
                {
                    return 41;
                }

                return -1;
            }

            /**
             * Camera drag.
             *
             * <p>Body: pan the eye and carry the point of interest with it, so the view direction
             * is preserved (this is what "pan the camera" means). Target: unproject the cursor onto
             * the target's Z plane and aim there, switching the camera to look-at mode — the exact
             * opposite of what the old build did, where dragging the camera spun the world.</p>
             */
            private void dragCamera(UIContext context, CompLayer layer, float scale)
            {
                float ox = (this.area.w - UICompositorPanel.this.doc.width * scale) / 2F;
                float oy = (this.area.h - UICompositorPanel.this.doc.height * scale) / 2F;
                float compX = (context.mouseX - this.area.x - ox) / scale;
                float compY = (context.mouseY - this.area.y - oy) / scale;

                if (this.dragMode == DRAG_CAMERA_BODY)
                {
                    /* 1:1 with the cursor: the gizmo is drawn in comp pixels, so a drag of N screen
                     * pixels must move the eye by N / fitScale comp pixels. Dividing by the camera
                     * zoom (the old code) made the icon lag behind the cursor as soon as the camera
                     * was dollied, because zoom is the projection scale, not the editor scale. */
                    float dxComp = (context.mouseX - this.dragStartX) / Math.max(0.0001F, scale);
                    float dyComp = (context.mouseY - this.dragStartY) / Math.max(0.0001F, scale);
                    float x = this.layerStartX + dxComp;
                    float y = this.layerStartY + dyComp;

                    this.writeTransform(layer, null, null, null, x, y);
                    layer.cameraTargetX = this.cameraStartTargetX + dxComp;
                    layer.cameraTargetY = this.cameraStartTargetY + dyComp;
                    UICompositorPanel.this.setScalar(layer, "cameraTargetX", layer.cameraTargetX);
                    UICompositorPanel.this.setScalar(layer, "cameraTargetY", layer.cameraTargetY);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "摄像机位置 %.0f, %.0f", x, y));

                    return;
                }

                /* Aim: the crosshair follows the cursor in comp space. Unprojecting through the
                 * camera (the old behaviour) meant the handle jumped away from the cursor the
                 * moment the camera was panned, and did nothing at all when the point was behind
                 * the near plane. */
                layer.cameraLookAt = true;
                layer.cameraTargetX = compX;
                layer.cameraTargetY = compY;
                UICompositorPanel.this.setScalar(layer, "cameraTargetX", compX);
                UICompositorPanel.this.setScalar(layer, "cameraTargetY", compY);
                UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "目标点 %.0f, %.0f", compX, compY));
            }

            /** Writes translation/scale/rotation either into the base fields or the native channel. */
            private void writeTransform(CompLayer layer, Float scaleX, Float scaleY, Float rotation)
            {
                this.writeTransform(layer, scaleX, scaleY, rotation, null, null);
            }

            private void writeTransform(CompLayer layer, Float scaleX, Float scaleY, Float rotation, Float translateX, Float translateY)
            {
                layer.ensureIndependentAxes();

                if (layer.hasAnyTransformKey())
                {
                    float frame = UICompositorPanel.this.getFrame();

                    if (scaleX != null) layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_SCALE_X, frame, scaleX);
                    if (scaleY != null) layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_SCALE_Y, frame, scaleY);
                    if (rotation != null) layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_ROTATION, frame, rotation);
                    if (translateX != null) layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_X, frame, translateX);
                    if (translateY != null) layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_Y, frame, translateY);
                }
                else
                {
                    if (scaleX != null) layer.scaleX = scaleX;
                    if (scaleY != null) layer.scaleY = scaleY;
                    if (rotation != null) layer.rotation = rotation;
                    if (translateX != null) layer.x = translateX;
                    if (translateY != null) layer.y = translateY;
                }
            }

            private void endDrag()
            {
                if (this.dragMode == DRAG_NONE)
                {
                    return;
                }

                this.dragMode = DRAG_NONE;
                UICompositorPanel.this.refreshInspector();
                UICompositorPanel.this.save();
            
                this.gizmoAxisIndex = -1;
                this.gizmoLayerId = null;
            }

            /**
             * Topmost visible layer whose content rectangle contains the cursor. Shapes use their
             * raster bounds, everything else its anchor-adjusted box; the world matrix (parents and
             * camera included) is inverted instead of approximating.
             */
            private CompLayer layerAt(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;
                CompProject project = UICompositorPanel.this.project;

                if (doc == null || project == null)
                {
                    return null;
                }

                float scale = this.fitScale();

                if (scale <= 0F)
                {
                    return null;
                }

                float ox = (this.area.w - doc.width * scale) / 2F;
                float oy = (this.area.h - doc.height * scale) / 2F;
                float screenX = (context.mouseX - this.area.x - ox) / scale;
                float screenY = (context.mouseY - this.area.y - oy) / scale;
                CompRenderContext renderContext = new CompRenderContext(project, doc, UICompositorPanel.this.time, null, null, null);
                CompCamera camera = CompScene.camera(renderContext);
                /* This unprojection is only the composition pixel for layers the camera still
                 * projects (legacy flat layers). AE 2D layers are screen-fixed, so their hit
                 * coordinate is screenX/screenY directly — unprojecting them made picking drift
                 * with the camera while the picture stayed put. */
                org.joml.Vector3f compPoint = camera.unproject(screenX, screenY, doc.width, doc.height, 0F);
                float cameraCompX = compPoint.x;
                float cameraCompY = compPoint.y;

                for (int i = doc.layers.size() - 1; i >= 0; i--)
                {
                    CompLayer layer = doc.layers.get(i);

                    if (!layer.visible || !layer.activeAt(UICompositorPanel.this.time))
                    {
                        continue;
                    }

                    /* Soloed-out layers are not pickable: selecting a layer the preview/export
                     * hides let the user drag something invisible. */
                    if (doc.isSoloedOut(layer))
                    {
                        continue;
                    }

                    /* Cameras have no content rectangle: they are picked by the same gizmo the
                     * editor draws (see renderCameraGizmo / handleAt). Re-implementing that geometry
                     * here is how the old build ended up with a hit test that only worked when the
                     * camera happened to be centred — clicking the visible camera body did nothing. */
                    if ("camera".equals(layer.type))
                    {
                        if (doc.activeCamera(UICompositorPanel.this.time) != layer)
                        {
                            continue;
                        }

                        if (this.handleAt(context, layer) >= 40)
                        {
                            return layer;
                        }

                        continue;
                    }

                    if (layer.threeD)
                    {
                        float[] corners = this.selectionCorners(layer);

                        if (corners != null && this.pointInQuad(context.mouseX, context.mouseY, corners))
                        {
                            return layer;
                        }

                        continue;
                    }

                    /* Shared inverse: screen-fit composition pixels -> layer-local point. An AE 2D
                     * layer is screen-fixed, so its composition pixel is the pixel under the
                     * cursor; a legacy 2D layer still goes through the camera (cameraCompX/Y). */
                    boolean cameraAffects = CompScene.cameraAffects(renderContext, layer);
                    float hitX = cameraAffects ? cameraCompX : screenX;
                    float hitY = cameraAffects ? cameraCompY : screenY;
                    float[] local = CompScene.compToLayerLocal(renderContext, layer, hitX, hitY);

                    if (local == null)
                    {
                        continue;
                    }

                    float bw;
                    float bh;
                    float bx;
                    float by;

                    if ("shape".equals(layer.type))
                    {
                        dev.bbsae.client.render.ShapeCache.Raster raster = dev.bbsae.client.render.ShapeCache.get(
                            layer, renderContext.frame,
                            dev.bbsae.client.render.MaskCache.cacheScope(project, doc));

                        if (raster == null)
                        {
                            continue;
                        }

                        bx = raster.originX;
                        by = raster.originY;
                        bw = raster.width;
                        bh = raster.height;
                    }
                    else
                    {
                        bw = Math.max(1F, layer.width);
                        bh = Math.max(1F, layer.height);
                        bx = -bw * layer.anchorX;
                        by = -bh * layer.anchorY;
                    }

                    if (local[0] >= bx && local[0] <= bx + bw && local[1] >= by && local[1] <= by + bh)
                    {
                        return layer;
                    }
                }

                return null;
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (!this.area.isInside(context))
                {
                    return super.subMouseClicked(context);
                }

                /* The pen owns every press over the preview while it is armed, including the right
                 * button (its context menu). It must run before the camera tool and the 3D
                 * navigation branches, which would otherwise eat the click or enter the 3D
                 * workspace. Splitter drags own the mouse entirely. */
                if (!UICompositorPanel.this.isPanelResizing()
                    && UICompositorPanel.this.penTool().onPress(context, UICompositorPanel.this.lastPenView))
                {
                    return true;
                }

                if (!UICompositorPanel.this.is3DSpace() && UICompositorPanel.this.cameraToolActive())
                {
                    /* The AE camera tool owns every mouse button over the preview. Consuming the
                     * press here also stops BBS from opening its context menu on the right button
                     * and stops the click from selecting or moving a layer. The gesture itself is
                     * polled from the window (pollCameraToolInput) because non-left buttons are not
                     * guaranteed to reach a custom element through the UI event path. */
                    return true;
                }

                if (!UICompositorPanel.this.is3DSpace() && context.mouseButton == 1
                    && !Boolean.getBoolean("bbsae.debugViewLock"))
                {
                    /* AE: the right button never changes the workspace. It used to force the whole
                     * editor into the 3D view on a right-drag and steal the layer selection — the
                     * exact opposite of the camera contract users expect. Orbit stays inside the
                     * 3D workspace, which is entered explicitly (sphere button / 0 key). */
                    UICompositorPanel.this.setStatus("2D 预览不响应右键环绕：点预览右侧球体或按 0 进入 3D 空间");

                    return true;
                }

                if (UICompositorPanel.this.is3DSpace() && (context.mouseButton == 1 || context.mouseButton == 2)
                    && !Boolean.getBoolean("bbsae.debugViewLock"))
                {
                    /* Both right (1) and middle (2) orbit; consuming them here also stops BBS from
                     * opening a context menu on the same click, which would eat the drag. */
                    if (UICompositorPanel.this.cameraPreviewMode)
                    {
                        UICompositorPanel.this.toggleViewportLookThrough();
                    }

                    this.beginViewportDrag(context, CompInput.shiftDown() ? VP_PAN : VP_ORBIT, context.mouseButton);

                    return true;
                }

                if (this.viewportActive())
                {
                    /* The poller may already have armed an orbit from this same press; consuming the
                     * click here keeps it from deselecting or starting a layer move mid-orbit. */
                    if (this.vpMode != VP_NONE)
                    {
                        return true;
                    }

                    return this.viewportMouseClicked(context);
                }

                if (context.mouseButton != 0)
                {
                    return super.subMouseClicked(context);
                }

                CompLayer selected = UICompositorPanel.this.selectedLayer;

                if (this.maskHit(context, selected))
                {
                    return true;
                }

                int handle = this.handleAt(context, selected);

                if (handle >= 0)
                {
                    this.beginHandleDrag(context, selected, handle);

                    return true;
                }

                CompLayer hit = this.layerAt(context);

                if (hit == null)
                {
                    /* Empty space: deselect. Dragging whatever happened to be selected made the
                     * preview feel like it ignored where the mouse actually was. */
                    UICompositorPanel.this.clearSelection();

                    return true;
                }

                if (Window.isCtrlPressed())
                {
                    UICompositorPanel.this.toggleLayerSelection(hit);
                }
                else if (!UICompositorPanel.this.isLayerSelected(hit))
                {
                    UICompositorPanel.this.selectLayer(hit);
                }

                if ("camera".equals(hit.type))
                {
                    int cameraHandle = this.handleAt(context, hit);

                    if (cameraHandle >= 40)
                    {
                        this.beginHandleDrag(context, hit, cameraHandle);

                        return true;
                    }
                }

                if (hit.locked)
                {
                    UICompositorPanel.this.setStatus("图层已锁定，解锁后才能拖动");

                    return true;
                }

                this.dragMode = DRAG_MOVE;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.dragStarts.clear();

                for (CompLayer moving : UICompositorPanel.this.getSelectedLayers())
                {
                    Transform movingTransform = moving.transformAt(UICompositorPanel.this.getFrame());

                    this.dragStarts.put(moving.id, new float[] {movingTransform.translate.x, movingTransform.translate.y});
                }

                Transform start = hit.transformAt(UICompositorPanel.this.getFrame());

                this.layerStartX = start.translate.x;
                this.layerStartY = start.translate.y;
                this.startScaleX = start.scale.x;
                this.startScaleY = start.scale.y;
                this.startRotation = start.rotate.z;
                this.targetStartX = hit.scalarAt("cameraTargetX", hit.cameraTargetX, UICompositorPanel.this.getFrame());
                this.targetStartY = hit.scalarAt("cameraTargetY", hit.cameraTargetY, UICompositorPanel.this.getFrame());

                return true;
            }

            /**
             * Viewport click routing: middle = orbit/pan, left = pick and drag.
             *
             * <p>Middle-drag is Blender's orbit and Shift+middle is pan; keeping the left button for
             * selection means a 3D viewport never "moves the world by accident" while you are
             * trying to click a layer.</p>
             */
            /** Debug-view hook: pick a gizmo mode without pressing a key. */
            void debugGizmoMode(String mode)
            {
                if ("scale".equals(mode))
                {
                    this.gizmoMode = GIZMO_SCALE;
                }
                else if ("rotate".equals(mode))
                {
                    this.gizmoMode = GIZMO_ROTATE;
                }
                else
                {
                    this.gizmoMode = GIZMO_MOVE;
                }
            }

            @Override
            protected boolean subKeyPressed(UIContext context)
            {
                if (this.area.isInside(context) && UICompositorPanel.this.is3DSpace()
                    && !UICompositorPanel.this.cameraPreviewMode
                    && context.getKeyAction() == KeyAction.PRESSED && !Window.isCtrlPressed())
                {
                    switch (context.getKeyCode())
                    {
                        case GLFW.GLFW_KEY_W:
                            this.gizmoMode = GIZMO_MOVE;
                            UICompositorPanel.this.setStatus("移动 gizmo（W）");
                            return true;

                        case GLFW.GLFW_KEY_E:
                            this.gizmoMode = GIZMO_SCALE;
                            UICompositorPanel.this.setStatus("缩放 gizmo（E）");
                            return true;

                        case GLFW.GLFW_KEY_R:
                            this.gizmoMode = GIZMO_ROTATE;
                            UICompositorPanel.this.setStatus("旋转 gizmo（R）");
                            return true;

                        default:
                            break;
                    }
                }

                return super.subKeyPressed(context);
            }

            private boolean viewportMouseClicked(UIContext context)
            {
                if (context.mouseButton != 0 || UICompositorPanel.this.doc == null)
                {
                    return super.subMouseClicked(context);
                }

                int modeButton = this.gizmoModeButtonAt(context);

                if (modeButton >= 0)
                {
                    this.gizmoMode = modeButton;
                    UICompositorPanel.this.setStatus(modeButton == 0 ? "移动 gizmo（W）"
                        : modeButton == 1 ? "缩放 gizmo（E）" : "旋转 gizmo（R）");

                    return true;
                }

                CompDocument doc = UICompositorPanel.this.doc;
                CompLayer selected = UICompositorPanel.this.selectedLayer;
                int boxHandle = this.viewportBoxHandleAt(context, selected, this.viewportContext(), this.viewport());

                if (boxHandle >= 0)
                {
                    this.beginBox3DDrag(context, selected, boxHandle);

                    return true;
                }

                int gizmoAxis = this.viewportGizmoHit(context, selected, this.viewportContext(), this.viewport());

                if (gizmoAxis >= 0)
                {
                    this.beginGizmoDrag(context, selected, gizmoAxis);

                    return true;
                }

                int handle = this.viewportCameraHandle(context);

                if (handle >= 0)
                {
                    CompLayer active = doc.activeCamera(UICompositorPanel.this.time);

                    if (active != null)
                    {
                        UICompositorPanel.this.selectLayer(active);
                    }

                    /* Alt + drag on the camera body orbits around its point of interest: the
                     * only gesture that turns "move the camera in the plane" into a real 3D
                     * move, because the eye has to leave the comp plane for the view to tilt. */
                    int mode = handle == 40
                        ? (CompInput.altDown() ? VP_CAM_ORBIT : VP_CAM_BODY)
                        : VP_CAM_TARGET;

                    this.beginViewportDrag(context, mode, 0);

                    return true;
                }

                dev.bbsae.client.render.CompViewportCamera camera = this.viewport();
                CompLayer hit = dev.bbsae.client.render.CompViewportRenderer.pick(
                    this.viewportContext(), camera, this.area.x, this.area.y, context.mouseX, context.mouseY);

                if (hit == null)
                {
                    UICompositorPanel.this.clearSelection();

                    return true;
                }

                if (Window.isCtrlPressed())
                {
                    UICompositorPanel.this.toggleLayerSelection(hit);
                }
                else if (!UICompositorPanel.this.isLayerSelected(hit))
                {
                    UICompositorPanel.this.selectLayer(hit);
                }

                if (hit.locked)
                {
                    UICompositorPanel.this.setStatus("图层已锁定，解锁后才能拖动");

                    return true;
                }

                this.beginViewportDrag(context, VP_MOVE, 0);

                return true;
            }

            private void beginHandleDrag(UIContext context, CompLayer layer, int handle)
            {
                if (layer == null)
                {
                    return;
                }

                Transform start = layer.transformAt(UICompositorPanel.this.getFrame());

                this.dragMode = handle == 40 ? DRAG_CAMERA_BODY
                    : handle == 41 ? DRAG_CAMERA_TARGET
                    : handle == 30 ? DRAG_ROTATE
                    : handle >= 20 ? DRAG_EDGE : DRAG_CORNER;
                this.dragHandle = handle;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.cameraStartTargetX = layer.scalarAt("cameraTargetX", layer.cameraTargetX, UICompositorPanel.this.getFrame());
                this.cameraStartTargetY = layer.scalarAt("cameraTargetY", layer.cameraTargetY, UICompositorPanel.this.getFrame());
                this.cameraStartTargetZ = layer.scalarAt("cameraTargetZ", layer.cameraTargetZ, UICompositorPanel.this.getFrame());
                /* Every handle drag (not only the 3D gizmo) needs the target id: Esc restore used
                 * to look at gizmoLayerId, which only beginGizmoDrag ever set, so 12 of the 13
                 * gestures "cancelled" without restoring anything. */
                this.gizmoLayerId = layer.id;
                this.startZ = layer.scalarAt("z", layer.z, UICompositorPanel.this.getFrame());
                this.startRotationX = layer.scalarAt("rotationX", layer.rotationX, UICompositorPanel.this.getFrame());
                this.startRotationY = layer.scalarAt("rotationY", layer.rotationY, UICompositorPanel.this.getFrame());
                this.startScaleZ = Math.max(0.0001F,
                    layer.scalarAt("scaleZ", layer.scaleZ, UICompositorPanel.this.getFrame()));
                this.startScaleX = start.scale.x;
                this.startScaleY = start.scale.y;
                this.startRotation = start.rotate.z;
                this.layerStartX = start.translate.x;
                this.layerStartY = start.translate.y;

                CompRenderContext renderContext = new CompRenderContext(
                    UICompositorPanel.this.project,
                    UICompositorPanel.this.doc,
                    UICompositorPanel.this.time,
                    null,
                    null,
                    null
                );

                this.startPlaneValid = false;

                try
                {
                    Matrix4f world = CompRenderer.worldMatrix(UICompositorPanel.this.doc, layer, renderContext, new java.util.HashSet<>());

                    this.startWorld.set(world);
                    this.startInverse.set(world).invert();
                }
                catch (RuntimeException e)
                {
                    this.startWorld.identity();
                    this.startInverse.identity();
                }

                float[] local = null;

                if (layer.threeD)
                {
                    local = this.startLocalPointOnLayerPlane(context);
                    this.startPlaneValid = local != null;
                }

                if (local == null)
                {
                    local = this.localPoint(context, layer);
                }

                this.startLocalX = local[0];
                this.startLocalY = local[1];
                this.startAngle = (float) Math.toDegrees(Math.atan2(local[1], local[0]));

                float[] corners = this.selectionCorners(layer);

                if (corners != null)
                {
                    this.startScreenCenterX = (corners[0] + corners[2] + corners[4] + corners[6]) * 0.25F;
                    this.startScreenCenterY = (corners[1] + corners[3] + corners[5] + corners[7]) * 0.25F;
                    float sx = context.mouseX - this.startScreenCenterX;
                    float sy = context.mouseY - this.startScreenCenterY;

                    this.startScreenDistance = (float) Math.sqrt(sx * sx + sy * sy);
                    this.startScreenAngle = (float) Math.toDegrees(Math.atan2(sy, sx));
                }

                UICompositorPanel.this.setStatus(handle == 30
                    ? "拖动旋转；Shift 15° 吸附"
                    : handle >= 20
                        ? "拖动单轴缩放；边柄只改变对应的 X 或 Y"
                        : "拖动等比缩放；Shift 可单独改 X/Y");
            }

            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                UICompositorPanel.this.penTool().onRelease(context);
                this.endViewportDrag();
                this.endMaskDrag();
                this.endDrag();

                return super.subMouseReleased(context);
            }

            /**
             * Camera-view navigation: the wheel dollies the active camera along Z, Alt+wheel
             * changes its field of view. Both write the same scalar channels the inspector edits,
             * so the value stays animatable and visible in the camera section.
             */
            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                /* The camera is always live now, so dolly/fov must not depend on the 3D-overlay
                 * toggle. With no camera the wheel falls through to the UI. */
                if (!this.area.isInside(context) || UICompositorPanel.this.doc == null)
                {
                    return super.subMouseScrolled(context);
                }

                if (this.viewportActive())
                {
                    double viewportWheel = context.mouseWheel;

                    if (Math.abs(viewportWheel) < 0.0001D)
                    {
                        return false;
                    }

                    dev.bbsae.client.render.CompViewportCamera camera = this.viewport();
                    CompLayer shot = UICompositorPanel.this.doc.activeCamera(UICompositorPanel.this.time);

                    if (CompInput.altDown() && shot != null)
                    {
                        /* Alt: the shot's field of view (same mapping as the camera view). */
                        float fov = shot.scalarAt("cameraFov", shot.cameraFov, UICompositorPanel.this.getFrame());
                        float next = Math.max(5F, Math.min(120F, fov - (float) viewportWheel * 2F));

                        UICompositorPanel.this.setScalar(shot, "cameraFov", next);
                        shot.cameraFov = next;
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "视场 %.0f°", next));

                        return true;
                    }

                    if (CompInput.ctrlDown() && shot != null)
                    {
                        /* Ctrl: dolly the shot towards/away from its target. */
                        float z = shot.scalarAt("cameraZ", shot.cameraZ, UICompositorPanel.this.getFrame());
                        float next = z * (float) Math.pow(0.9D, viewportWheel);

                        shot.cameraZ = next;
                        shot.z = next;
                        UICompositorPanel.this.setScalar(shot, "cameraZ", next);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "摄像机距离 %.0f", next));

                        return true;
                    }

                    camera.zoomAt(context.mouseX - this.area.x, context.mouseY - this.area.y, (float) viewportWheel);
                    UICompositorPanel.this.saveViewportPose();
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "视图距离 %.0f", camera.distance));

                    return true;
                }

                /* Camera dolly/fov only while looking through the camera. A plain 2D preview
                 * wheel used to edit the shot camera (and insert keyframes) silently, so the
                 * exported frame differed from what the user saw before scrolling. */
                if (!UICompositorPanel.this.cameraPreviewMode)
                {
                    return super.subMouseScrolled(context);
                }

                CompLayer camera = UICompositorPanel.this.doc.activeCamera(UICompositorPanel.this.time);

                if (camera == null)
                {
                    return false;
                }

                double wheel = context.mouseWheel;

                if (Math.abs(wheel) < 0.0001D)
                {
                    return false;
                }

                if (CompInput.altDown())
                {
                    float fov = camera.scalarAt("cameraFov", camera.cameraFov, UICompositorPanel.this.getFrame());
                    /* Wheel up narrows the field of view: the same direction every 3D application
                     * uses. The old code added, which zoomed *out* on a forward scroll. */
                    float value = Math.max(1F, Math.min(179F, fov - (float) wheel * 2F));

                    camera.cameraFov = value;
                    UICompositorPanel.this.setScalar(camera, "cameraFov", value);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "摄像机视场 %.0f°", value));
                }
                else
                {
                    float z = camera.scalarAt("cameraZ", camera.cameraZ, UICompositorPanel.this.getFrame());

                    if (Math.abs(z) < 0.001F)
                    {
                        z = UICompositorPanel.this.defaultCameraZ();
                    }

                    /* Dolly towards the comp on a forward scroll (smaller distance = closer). */
                    float value = Math.max(camera.cameraNear + 10F, Math.min(camera.cameraFar - 10F,
                        z - (float) wheel * Math.max(20F, z * 0.08F)));

                    camera.cameraZ = value;
                    camera.z = value;
                    UICompositorPanel.this.setScalar(camera, "cameraZ", value);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "摄像机 Z %.0f", value));
                }

                /* Rebuilding the inspector is the expensive part (dozens of widgets); once per
                 * gesture is enough to keep the numbers live, and it must never run per notch. */
                long now = System.currentTimeMillis();

                if (now - UICompositorPanel.this.lastCameraRefreshMs > 140L)
                {
                    UICompositorPanel.this.lastCameraRefreshMs = now;
                    UICompositorPanel.this.refreshInspector();
                }

                UICompositorPanel.this.save();

                return true;
            }
        };
        this.preview.h(1F);

        /* The mode has to be visible at a glance: AE mode changes what the picture and the export
         * mean, so it gets a red chip on the image itself rather than a line in a settings dialog. */
        this.encoderBadge = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                CompDocument doc = UICompositorPanel.this.doc;
                int frame = dev.bbsae.client.render.CompFrameServer.frameOf(doc, UICompositorPanel.this.time);
                int total = dev.bbsae.client.render.CompFrameServer.frameCount(doc);

                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xE6B3261E);
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFFFF8A80);
                float proxy = UICompositorPanel.this.encoderProxy();
                String factor = proxy >= 0.999F ? "" : " · 代理 " + proxyLabel(proxy);

                context.batcher.textShadow("AE 编码器" + factor, this.area.x + 6, this.area.y + 4, 0xFFFFFFFF);
                context.batcher.textShadow("帧 " + frame + " / " + total, this.area.x + 6, this.area.y + 14, 0xFFFFD9D5);

                super.render(context);
            }
        };
        this.encoderBadge.relative(this.preview).x(1F, -126).y(28).w(120).h(24);
        this.encoderBadge.tooltip(IKey.constant("AE 编码器：预览与导出来自同一个帧服务器（第 N 帧 = N / 帧率）；点“设置”可切回 BBS 原生"));
        this.encoderBadge.setVisible(false);
        this.preview.add(this.encoderBadge);

        this.previewBar = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xCC0D1017);
                super.render(context);
            }
        };
        this.previewBar.relative(this.preview).x(6).y(6).w(200).h(20);
        this.effectsToggle = new UIToggle(IKey.constant("效果"), true, (toggle) -> this.previewEffects = toggle.getValue());
        this.effectsToggle.relative(this.previewBar).x(4).y(1).w(58).h(18);
        this.effectsToggle.tooltip(IKey.constant("预览效果与样式"));
        this.masksToggle = new UIToggle(IKey.constant("蒙版"), true, (toggle) -> this.previewMasks = toggle.getValue());
        this.masksToggle.relative(this.previewBar).x(64).y(1).w(58).h(18);
        this.masksToggle.tooltip(IKey.constant("预览蒙版与轨道遮罩"));

        /* Preview → BBS: the live BBS frame behind the composition, exactly what the film editor
         * shows as its own preview background. */
        this.bbsBackdropToggle = new UIToggle(IKey.constant("BBS"), false, (toggle) ->
        {
            if (this.project != null && this.project.settings != null)
            {
                this.project.settings.previewBackground = toggle.getValue() ? 1 : 0;
                this.project.settings.previewBackgroundChosen = true;
                dev.bbsae.client.overlay.BbsPreviewBridge.setBackdropRequested(this.project.settings.previewBackground == 1);

                /* The BBS live backdrop frame is the only source of world depth in the editor.
                 * Switching it on may re-enable depth_* effects that were degraded without a
                 * world frame; give the renderer a fresh chance and re-arm the notice. */
                if (toggle.getValue())
                {
                    dev.bbsae.client.render.CompRenderer.resetDepthEffectsStatus();
                    this.depthDegradedNotice = null;
                }
                this.save();
            }
        });
        this.bbsBackdropToggle.relative(this.previewBar).x(124).y(1).w(58).h(18);
        this.bbsBackdropToggle.tooltip(IKey.constant("在合成后面显示 BBS 实时画面"));

        /* Texture filtering is a first-class image concern, not a hidden global. The chip shows the
         * active policy and folds out three explicit choices: a cycle button makes "go back one
         * step" impossible and hides the other two states. */
        this.previewFilter = new UICompButton(IKey.constant("线性: 自动"), (button) -> this.toggleFilterMenu());
        this.previewFilter.relative(this.previewBar).x(184).y(1).w(82).h(18);
        this.previewFilter.tooltip(IKey.constant("纹理过滤：自动 / 线性 / 最近邻。点击展开三个选项，再点收起；自动规则见设置 → 界面布局"));
        this.updateFilterButton();

        this.previewFilterMenu = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xF20B0E14);
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF3B4556);
                super.render(context);
            }
        };
        this.previewFilterMenu.relative(this.preview).x(190).y(28).w(110).h(58);
        this.previewFilterAuto = this.filterOption("自动", TextureFilter.AUTO, 2);
        this.previewFilterLinear = this.filterOption("线性", TextureFilter.LINEAR, 20);
        this.previewFilterNearest = this.filterOption("最近邻", TextureFilter.NEAREST, 38);
        this.previewFilterMenu.add(this.previewFilterAuto, this.previewFilterLinear, this.previewFilterNearest);
        this.previewFilterMenu.setVisible(false);
        this.updateFilterButton();

        /* Collapse handle: the toggles cover the top-left of the frame, which is usually where the
         * composition has content. One click folds them into an arrow. */
        this.previewCollapse = new UIIcon(
            () -> this.previewControlsCollapsed ? Icons.ARROW_DOWN : Icons.ARROW_UP,
            (button) ->
            {
                this.previewControlsCollapsed = !this.previewControlsCollapsed;

                if (this.project != null && this.project.settings != null)
                {
                    this.project.settings.previewControlsCollapsed = this.previewControlsCollapsed;
                }

                this.applyPreviewBarState();
                this.save();
            });
        this.previewCollapse.tooltip(IKey.constant("折叠/展开预览开关"));
        this.previewCollapse.relative(this.previewBar).x(266).y(1).w(20).h(18);

        this.previewBar.relative(this.preview).x(6).y(6).w(292).h(20);
        this.previewBar.add(this.effectsToggle, this.masksToggle, this.bbsBackdropToggle, this.previewFilter, this.previewCollapse);
        this.applyPreviewBarState();
        this.preview.add(this.previewBar);
        /* After the bar so the dropdown is drawn over it, and clipped by the preview like every
         * other floating control. */
        this.preview.add(this.previewFilterMenu);

        this.cameraViewButton = new UIIcon(
            () -> this.cameraPreviewMode ? Icons.CAMERA : Icons.FRUSTUM,
            (button) ->
            {
                /* Look through the shot / back to the free view. This is Blender's numpad 0: the
                 * camera itself is always live; this only decides whose view fills the panel. */
                this.toggleViewportLookThrough();
            });

        this.cameraViewButton.relative(this.preview).x(1F, -28).y(6).w(20).h(20);
        this.cameraViewButton.tooltip(IKey.constant("看穿摄像机（最终画面）/ 回到自由视图；数字键盘 0 同效"));
        this.preview.add(this.cameraViewButton);

        /* One-click 3D workspace: live Minecraft frame as the virtual stage, a real perspective
         * camera, a ground grid and the camera selected for navigation. */
        this.space3DButton = new UIIcon(Icons.SPHERE, (button) ->
        {
            if (this.is3DSpace())
            {
                this.exit3DSpace();
            }
            else
            {
                this.enter3DSpace();
            }
        });
        this.space3DButton.relative(this.preview).x(1F, -50).y(6).w(20).h(20);
        this.space3DButton.tooltip(IKey.constant("进入/退出 3D 空间（自由视口：右键拖动环绕、Shift+右键平移、滚轮缩放、0 看穿摄像机）"));
        this.preview.add(this.space3DButton);

        /* Entity replay: switches to bbsae's own 3D replay editing page, which reuses BBS's replay
         * timeline, gizmo and model library instead of re-implementing them. The dashboard bar also
         * carries the same entry as a registered panel. */
        this.replayModeButton = new UIIcon(Icons.PLAYER,
            (button) -> this.openReplayWorkspace());
        this.replayModeButton.relative(this.preview).x(1F, -160).y(6).w(20).h(20);
        this.replayModeButton.tooltip(IKey.constant("实体回放：在 3D 空间里编辑影片回放（原版轨道 / 原版 gizmo / 原版模型库）"));
        this.preview.add(this.replayModeButton);

        /* AE camera tool: a mode, not a modifier. While it is on the preview stops picking layers
         * and the three mouse buttons are orbit / track XY / dolly. */
        this.cameraToolButton = new UIIcon(
            () -> this.cameraToolActive() ? Icons.LOOKING : Icons.ORBIT,
            (button) -> this.toggleCameraTool());
        this.cameraToolButton.relative(this.preview).x(1F, -72).y(6).w(20).h(20);
        this.cameraToolButton.tooltip(IKey.constant("AE 摄像机工具：左键环绕 / 中键 Track XY / 右键 Track Z 推拉；没有摄像机时不会自动创建"));
        this.preview.add(this.cameraToolButton);

        /* A camera is an explicit object (AE: Layer → New → Camera). */
        this.createCameraButton = new UIIcon(Icons.CAMERA, (button) -> this.createCamera());
        this.createCameraButton.relative(this.preview).x(1F, -94).y(6).w(20).h(20);
        this.createCameraButton.tooltip(IKey.constant("新建摄像机（已有摄像机时选中它）。没有摄像机时预览拖动永远不会自动创建"));
        this.preview.add(this.createCameraButton);

        this.cameraSettingsButton = new UIIcon(Icons.GEAR, (button) -> this.openSettings("dev"));
        this.cameraSettingsButton.relative(this.preview).x(1F, -116).y(6).w(20).h(20);
        this.cameraSettingsButton.tooltip(IKey.constant("摄像机与 3D 逻辑设置（设置 → 开发者）"));
        this.preview.add(this.cameraSettingsButton);

        /* Pen tool: 2D/3D mask and shape-path editing. The icon changes with the armed state so the
         * preview itself says which mouse behaviour is active. */
        this.penButton = new UIIcon(
            () -> this.penTool().isActive() ? Icons.ARC : Icons.LINE,
            (button) -> this.togglePenTool());
        this.penButton.relative(this.preview).x(1F, -138).y(6).w(20).h(20);
        this.penButton.tooltip(IKey.constant("钢笔（G）：单击加角点、按下拖动加平滑点、点击起点闭合；2D 与 3D 图层的蒙版/形状路径都可编辑"));
        this.preview.add(this.penButton);

        this.previewInfo = new UILabel(IKey.constant("预览"));
        this.previewInfo.relative(this.preview).x(6).y(1F, -18).w(1F, -12).h(14);
        this.previewInfo.color(0xFF9AA6B8, false);
        this.previewInfo.labelAnchor(0F, 0.5F);
        this.preview.add(this.previewInfo);

        this.inspectorColumn = new UIElement();
        this.inspectorColumn.w(300).h(1F);

        this.inspectorTabs = new UICompTabBar();
        this.inspectorTabs.relative(this.inspectorColumn).x(0).y(0).w(1F, -6).h(22);
        /* Tab order is an editing order, not a constant order: grade → layer → mask → effects is
         * the "select a layer, fix its transform, then add visual effects" path. The effect stack
         * used to live at index 0 together with transform/opacity, which is the crowding this split
         * removes. PAGE_* keeps its numeric contract for project files; the map below translates. */
        this.inspectorTabs.addTab("调色");
        this.inspectorTabs.addTab("图层");
        this.inspectorTabs.addTab("蒙版");
        this.inspectorTabs.addTab("效果");
        this.inspectorTabs.addTab("文字");
        this.inspectorTabs.addTab("合成");
        this.inspectorTabs.addTab("输出");
        this.inspectorTabs.onSelect((index) ->
        {
            this.inspectorPage = INSPECTOR_TAB_PAGES[Math.max(0, Math.min(INSPECTOR_TAB_PAGES.length - 1, index))];
            this.beginPageMotion(this.inspectorPageMotion, "inspector:" + this.inspectorPage);
            this.refreshInspector();
            this.markViewStateDirty();
        });
        this.inspectorTabs.active(() -> inspectorTabForPage(this.inspectorPage));

        this.inspector = new UIScrollView(ScrollDirection.VERTICAL);
        this.inspector.relative(this.inspectorColumn).x(0).y(22).w(1F).h(1F, -22);
        this.inspector.column(4).vertical().stretch().scroll().padding(4);
        this.inspectorColumn.add(this.inspectorTabs, this.inspector);

        this.keyframeEditor = new UIKeyframeEditor((callback) -> new UICompositorKeyframes(this, callback));
        this.keyframeEditor.target(this.inspector);
        this.keyframeEditor.w(1F).h(1F);
        this.keyframeEditor.view.duration(() -> this.doc == null ? 720 : Math.max(1, Math.round(this.doc.duration * this.doc.fps)));
        this.keyframeEditor.view.getDopeSheet().setEmptyState(
            IKey.constant("没有关键帧"),
            IKey.constant("先选中轨道，再点秒表或在播放头添加关键帧。")
        );
        this.keyframeEditor.view.rulerRenderer((context) -> TimelineRulerRenderer.render(
            context,
            this.keyframeEditor.view.graphArea,
            (int) this.keyframeEditor.view.fromGraphX(this.keyframeEditor.view.graphArea.x),
            this.getDurationTicks(),
            this.keyframeEditor.view::toGraphX,
            TimeUtils::formatTime
        ));
        this.keyframeEditor.view.context((menu) ->
        {
            menu.action(Icons.ADD, IKey.constant("在播放头添加变换关键帧"), () -> this.addTransformKey(false));
            menu.action(Icons.ADD, IKey.constant("在播放头添加不透明度关键帧"), this::addOpacityKey);
        });

        /* BBS calls this after any dope-sheet edit (drag, delete, paste, duplicate). Without it
         * editing keys in the drawer neither marked the project dirty (no disk write, no undo
         * sampler, stale green frames) nor refreshed the inspector values — the only write path
         * was the 60 s autosave. Undo granularity stays the 1 s sampler because BBS does not
         * expose an edit-start hook. */
        this.keyframeEditor.view.changed(() ->
        {
            if (this.restoring)
            {
                return;
            }

            this.rebindInspectorValues();
            this.save();
        });
        this.keyframeEditor.setUndoId("bbsae_compositor_timeline");
        this.keyframeEditor.view.resetView();

        /* Obvious buttons for the dope-sheet track height. The Alt+wheel gesture exists, but the
         * editor's own wheel handling can swallow it depending on which child is under the cursor;
         * a visible control is the only reliable fix. */
        this.keyframeRowUp = new UIIcon(Icons.ARROW_UP, (icon) -> this.stepKeyframeRowHeight(2));
        this.keyframeRowDown = new UIIcon(Icons.ARROW_DOWN, (icon) -> this.stepKeyframeRowHeight(-2));
        this.keyframeRowUp.relative(this.keyframeEditor).x(1F, -42).y(3).w(16).h(16);
        this.keyframeRowDown.relative(this.keyframeEditor).x(1F, -24).y(3).w(16).h(16);
        this.keyframeRowUp.tooltip(IKey.constant("加高关键帧轨道（Alt+滚轮）"));
        this.keyframeRowDown.tooltip(IKey.constant("降低关键帧轨道（Alt+滚轮）"));
        this.keyframeEditor.add(this.keyframeRowUp, this.keyframeRowDown);

        this.keyframeDrawer = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.clip(this.area, context);

                try
                {
                    context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF151A22);
                    context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, 0xFF2A303A);
                    super.render(context);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }
        };
        this.keyframeDrawer.setVisible(false);

        this.keyframeTitle = new UILabel(IKey.constant("属性关键帧"));
        this.keyframeTitle.color(0xFFB8C4D6, false);
        this.keyframeTitle.relative(this.keyframeDrawer).x(8).y(1).w(1F, -250).h(20);

        /* BBS's original curve picker, one click from the keyframe bar. It applies to the
         * selected keyframes (or the key at the playhead) and is the exact menu the film
         * editor opens from the graph icon. */
        this.keyframeCurve = new UICompButton(IKey.constant("插值曲线"), (button) -> this.openCurveMenu());
        this.keyframeCurve.relative(this.keyframeDrawer).x(1F, -186).y(2).w(68).h(18);
        this.keyframeCurve.tooltip(IKey.constant("BBS 原生插值曲线：为选中的关键帧选择贝塞尔/缓动/自动等曲线"));

        UIIcon closeKeyframes = new UIIcon(Icons.CLOSE, (icon) -> this.toggleKeyframes());
        closeKeyframes.relative(this.keyframeDrawer).x(1F, -24).y(2).w(18).h(18);

        UIIcon prevKey = new UIIcon(Icons.LEFTLOAD, (icon) -> this.navigateKeyframe(-1));
        prevKey.relative(this.keyframeDrawer).x(1F, -112).y(2).w(18).h(18);
        prevKey.tooltip(IKey.constant("上一个关键帧（,）"));

        UIIcon addKey = new UIIcon(Icons.ADD, (icon) ->
        {
            this.addTransformKey(false);
            this.addOpacityKey();
        });
        addKey.relative(this.keyframeDrawer).x(1F, -90).y(2).w(18).h(18);
        addKey.tooltip(IKey.constant("在播放头为变换和不透明度打关键帧（Alt+K 只打变换）"));

        UIIcon nextKey = new UIIcon(Icons.RIGHTLOAD, (icon) -> this.navigateKeyframe(1));
        nextKey.relative(this.keyframeDrawer).x(1F, -68).y(2).w(18).h(18);
        nextKey.tooltip(IKey.constant("下一个关键帧（.）"));

        UIIcon fitKeys = new UIIcon(Icons.MAXIMIZE, (icon) ->
        {
            this.keyframeEditor.view.resize();
            this.keyframeEditor.view.getXAxis().view(0D, Math.max(1, this.getDurationTicks()));
        });
        fitKeys.relative(this.keyframeDrawer).x(1F, -46).y(2).w(18).h(18);
        fitKeys.tooltip(IKey.constant("关键帧视图适配合成全长"));

        /* Visible XYZ-track row height controls, right in the drawer title bar. */
        UIIcon drawerZoomOut = new UIIcon(Icons.ARROW_DOWN, (icon) -> this.zoomKeyframeTime(-1));
        UIIcon drawerZoomIn = new UIIcon(Icons.ARROW_UP, (icon) -> this.zoomKeyframeTime(1));

        drawerZoomOut.relative(this.keyframeDrawer).x(1F, -248).y(2).w(18).h(18);
        drawerZoomIn.relative(this.keyframeDrawer).x(1F, -228).y(2).w(18).h(18);
        drawerZoomOut.tooltip(IKey.constant("缩小关键帧时间轴（Alt+滚轮）"));
        drawerZoomIn.tooltip(IKey.constant("放大关键帧时间轴（Alt+滚轮）"));

        this.keyframeEditor.relative(this.keyframeDrawer).x(0).y(22).w(1F).h(1F, -22);

        this.keyframeDrawer.add(this.keyframeTitle, this.keyframeCurve, prevKey, addKey, nextKey, fitKeys,
            closeKeyframes, drawerZoomOut, drawerZoomIn, this.keyframeEditor);

        this.status = new UILabel(IKey.constant(""));
        this.status.color(0xFF8A94A6, false);
        this.status.h(16);

        this.renderPanel = new UIElement();
        this.renderPanel.relative(this).x(0).y(24).w(1F).h(1F, -292);
        this.renderPanel.setVisible(false);

        UILabel queueTitle = new UILabel(IKey.constant("渲染队列 / 输出模块"));
        queueTitle.relative(this.renderPanel).x(0).y(0).w(1F).h(18);

        this.queue = new UICompRenderJobList((list) ->
        {
            this.selectedJob = list.isEmpty() ? null : list.get(0);
        }, this::presetLabel);
        this.queue.relative(this.renderPanel).x(0).y(76).w(1F).h(1F, -128);
        this.queue.background();

        this.queueName = new UITextbox(128, (text) -> {});
        this.queueName.setText("comp_render");
        this.queueStart = new UITrackpad();
        this.queueStart.setValue(0);
        this.queueEnd = new UITrackpad();
        this.queueEnd.setValue(-1);

        /* The output module is a real chooser, not a text field.
         *
         * Typing an internal preset id ("review_h264") was the only way to pick an output — nobody
         * can guess those, and an invalid id silently fell back to the first preset, so "export as
         * audio" and "export with alpha" looked identical. The choice button lists every module by
         * its display name, video modules first, audio modules last. */
        this.queuePreset = new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
            java.util.List.of("review_h264", "master_prores", "alpha_prores4444", "png_sequence", "audio_wav", "audio_m4a"),
            (id) -> this.presetKind(id).startsWith("音频") ? Icons.SOUND : Icons.FILM,
            (id) -> IKey.constant(this.presetLabel(id))
        );
        this.queuePreset.callback((id) ->
        {
            if (id != null)
            {
                this.queueName.setText(this.defaultOutputName(id));
            }
        });
        this.queuePreset.setValue("review_h264");

        /* Absolute positions, two rows.
         *
         * This used to be one {@code row(4)} of four label+field rows. BBS's row() ignores a
         * child's requested width and divides the container equally, so each 110-px label column
         * lived inside a quarter-width box and the fields beside them got negative widths: the
         * preset/name/range boxes overlapped each other and the labels. Explicit two columns per
         * row is boring and always correct. */
        UIElement queueFields = new UIElement();
        queueFields.relative(this.renderPanel).x(0).y(22).w(1F).h(48);

        UIElement presetRow = this.fieldRow("输出模块", 110, this.queuePreset);
        UIElement nameRow = this.fieldRow("名称", 110, this.queueName);
        UIElement startRow = this.fieldRow("开始（秒）", 110, this.queueStart);
        UIElement endRow = this.fieldRow("结束（-1 = 全片）", 110, this.queueEnd);

        presetRow.relative(queueFields).x(0).y(0).w(0.56F, -4).h(22);
        nameRow.relative(queueFields).x(0.56F, 4).y(0).w(0.44F, 0).h(22);
        startRow.relative(queueFields).x(0).y(24).w(0.34F, -4).h(22);
        endRow.relative(queueFields).x(0.34F, 4).y(24).w(0.66F, 0).h(22);
        queueFields.add(presetRow, nameRow, startRow, endRow);

        this.queueAdd = new UICompButton(IKey.constant("添加任务"), (button) -> this.addRenderJob());
        this.queueAdd.w(68);
        this.queueStartButton = new UICompButton(IKey.constant("开始"), (button) ->
        {
            this.startSelectedRenderJob();
        });
        this.queueStartButton.w(56);
        this.queueStartAll = new UICompButton(IKey.constant("全部开始"), (button) ->
        {
            this.startRenderQueue();
        });
        this.queueStartAll.w(68);
        this.queueCancel = new UICompButton(IKey.constant("取消"), (button) ->
        {
            CompExportManager.cancel();
            this.setStatus("已取消导出（队列中剩余任务也一并取消）");
        });
        this.queueCancel.w(58);
        this.queueOpen = new UICompButton(IKey.constant("打开文件夹"), (button) -> this.openExportFolder());
        this.queueOpen.w(56);
        this.queuePresetCycle = new UICompButton(IKey.constant("预设…"), (button) -> this.queuePreset.open());
        this.queuePresetCycle.tooltip(IKey.constant("选择输出模块：视频（H.264 / ProRes / PNG 序列）或音频（WAV / M4A）"));
        this.queuePresetCycle.w(68);
        this.queueRemove = new UICompButton(IKey.constant("移除"), (button) ->
        {
            if (this.project != null && this.selectedJob != null)
            {
                if (this.selectedJob.status == CompRenderJob.RENDERING
                    || this.selectedJob.status == CompRenderJob.ENCODING)
                {
                    this.setStatus("该任务正在导出：先点「取消」再移除");

                    return;
                }

                this.project.renderQueue.remove(this.selectedJob);
                this.selectedJob = null;
                this.refreshQueue();
                this.save();
            }
        });
        this.queueRemove.w(58);
        this.queueRetry = new UICompButton(IKey.constant("重试"), (button) ->
        {
            if (this.selectedJob != null)
            {
                this.selectedJob.status = CompRenderJob.QUEUED;
                this.selectedJob.message = "";
                this.selectedJob.progress = 0F;
                this.refreshQueue();
            }
        });
        this.queueRetry.w(54);

        UIElement queueButtons = new UIElement();
        queueButtons.relative(this.renderPanel).x(0).y(1F, -46).w(1F).h(0F, 22);
        queueButtons.row(4);
        queueButtons.add(this.queueAdd, this.queueStartButton, this.queueStartAll, this.queueCancel,
            this.queuePresetCycle, this.queueRetry, this.queueRemove, this.queueOpen);

        this.renderStatus = new UILabel(IKey.constant("空闲"));
        this.renderStatus.color(0xFF9AA4B2, false);
        this.renderStatus.relative(this.renderPanel).x(0).y(1F, -20).w(1F).h(16);

        this.renderPanel.add(queueTitle, queueFields, this.queue, queueButtons, this.renderStatus);

        this.dockLayoutEnabled = CompPrefs.get().dockLayout;

        if (this.dockLayoutEnabled)
        {
            /* BBS's own dockable layout. Panels are registered after they are built (below); the
             * legacy body stays null so no code accidentally positions the panels twice. */
            this.dock = new UIDockLayout();
        }
        else
        {
            this.body = new UIElement();
            this.body.add(this.effectColumn, this.preview, this.inspectorColumn);

            this.leftHandle = new PanelResizeHandle(0);
            this.rightHandle = new PanelResizeHandle(1);
            this.timelineHandle = new PanelResizeHandle(2);
            this.body.add(this.leftHandle, this.rightHandle, this.timelineHandle);
            this.body.relative(this).x(0).y(24).w(1F).h(1F, -292);
        }

        this.renderPanel.relative(this).x(0).y(24).w(1F).h(1F, -292);

        this.timeline = new UIElement();
        this.timeline.relative(this).x(0).y(1F, -268).w(1F).h(TIMELINE_HEIGHT);

        /* Transform values live next to the tracks: keyframing a move should not require a trip to
         * the bottom of the inspector and back. */
        this.quickPanel = new UILayerQuickPanel(this);
        this.quickPanel.setVisible(false);
        this.quickPanel.relative(this).x(1F, -this.quickPanelWidth).y(1F, -268)
            .w(this.quickPanelWidth).h(TIMELINE_HEIGHT);

        this.trackTimeline = new UICompTrackTimeline(this);
        this.trackTimeline.relative(this.timeline).x(0).y(this.timelineContentTop()).w(1F).h(1F, -this.timelineContentTop());

        /* The same strip hosts two editors: the track timeline and the AE curve editor. They are
         * mutually exclusive views of the selected layer's animation, never a second source of
         * keyframes - both edit the very same BBS channels. */
        this.curveEditor = new UICompCurveEditor(this);
        this.curveEditor.relative(this.timeline).x(0).y(this.timelineContentTop()).w(1F).h(1F, -this.timelineContentTop());
        this.curveEditor.setVisible(false);

        this.timelineHeader = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF171C24);
                context.batcher.box(this.area.x, this.area.ey() - 1, this.area.ex(), this.area.ey(), 0xFF2A303A);
                super.render(context);
            }
        };
        this.timelineHeader.relative(this.timeline).x(0).y(this.timelineHeaderTop()).w(1F).h(TIMELINE_HEADER_H);

        this.addLayer.relative(this.timelineHeader).x(4).y(3).w(20).h(20);

        UIIcon split = new UIIcon(Icons.CUT, (button) -> this.splitSelectedAtPlayhead());
        split.relative(this.timelineHeader).x(28).y(3).w(20).h(20);
        split.tooltip(IKey.constant("在播放头拆分所选轨道（C / Ctrl+K）"));

        UIIcon marker = new UIIcon(Icons.BOOKMARK, (button) -> this.addMarkerAtPlayhead());
        marker.relative(this.timelineHeader).x(52).y(3).w(20).h(20);
        marker.tooltip(IKey.constant("在播放头添加合成标记（M）"));

        this.snapButton = new UICompButton(IKey.constant("吸附 开"), (button) ->
        {
            this.trackTimeline.toggleSnapping();
            this.updateToolbarLabels();
        });
        this.snapButton.relative(this.timelineHeader).x(78).y(3).w(52).h(CompUi.control());
        this.snapButton.tooltip(IKey.constant("拖动/裁剪时吸附到播放头、标记和其它轨道（S）"));

        this.keyframeButton = new UICompButton(IKey.constant("关键帧"), (button) -> this.toggleKeyframes());
        this.keyframeButton.relative(this.timelineHeader).x(134).y(3).w(52).h(CompUi.control());
        this.keyframeButton.tooltip(IKey.constant("显示所选轨道的属性关键帧"));

        int zoomSize = CompUi.control();

        UIButton zoomOut = CompUi.flat("-", "缩小时间轴（-）：一屏看更多时间", () -> this.trackTimeline.zoom(-1D));
        zoomOut.relative(this.timelineHeader).x(190).y(3).w(zoomSize).h(zoomSize);
        UIButton zoomIn = CompUi.flat("+", "放大时间轴（=）：看清更细的关键帧", () -> this.trackTimeline.zoom(1D));
        zoomIn.relative(this.timelineHeader).x(190 + zoomSize + 2).y(3).w(zoomSize).h(zoomSize);
        UIIcon fitTimeline = CompUi.iconButton(Icons.MAXIMIZE, "时间轴适配全长（反斜杠键）", () -> this.trackTimeline.fit());
        fitTimeline.relative(this.timelineHeader).x(190 + zoomSize * 2 + 4).y(3).w(zoomSize).h(zoomSize);

        this.trackOnlyTools.add(this.snapButton);
        this.trackOnlyTools.add(this.keyframeButton);
        this.trackOnlyTools.add(zoomOut);
        this.trackOnlyTools.add(zoomIn);
        this.trackOnlyTools.add(fitTimeline);

        this.timelineTabs = new UICompTabBar();
        this.timelineTabs.relative(this.timelineHeader).x(282).y(3).w(124).h(20);
        this.timelineTabs.addTab("时间轴");
        this.timelineTabs.addTab("曲线");
        this.timelineTabs.active(() -> this.timelinePage);
        this.timelineTabs.onSelect((index) ->
        {
            boolean grow = index != 0 && this.timelinePage == 0;

            this.timelinePage = index;
            this.beginPageMotion(this.timelinePageMotion, "timeline:" + index);
            this.applyTimelinePage(grow);

            if (this.project != null && this.project.settings != null)
            {
                this.project.settings.timelinePage = index;
            }

            this.saveUiState();
        });
        fitTimeline.tooltip(IKey.constant("时间轴适配全长（\\）"));

        /* Pre-render: fills the green bar. Once the range is green, playback is a texture
         * lookup instead of a full render graph per frame. */
        this.precacheButton = new UICompButton(IKey.constant("预渲染"), (button) -> this.togglePrecache());
        this.precacheButton.relative(this.timelineHeader).x(412).y(3).w(56).h(CompUi.control());
        this.precacheButton.tooltip(IKey.constant("把工作区（或整段）渲染到缓存：条子变绿后预览不再卡顿"));

        UIIcon clearCache = CompUi.iconButton(Icons.TRASH, "清除本合成的预渲染缓存", () ->
        {
            dev.bbsae.client.render.CompPrecache.clear(this.project, this.doc);
            this.setStatus("已清除预渲染缓存");
        });
        clearCache.relative(this.timelineHeader).x(470).y(3).w(CompUi.control()).h(CompUi.control());

        this.trackOnlyTools.add(this.precacheButton);
        this.trackOnlyTools.add(clearCache);

        this.precompBack = new UIIcon(Icons.BACKWARD, (button) -> this.exitPrecomp());
        this.precompBack.relative(this.timelineHeader).x(258).y(3).w(20).h(20);
        this.precompBack.tooltip(IKey.constant("返回上一层合成（Esc）"));
        this.precompBack.setVisible(false);

        /* A text breadcrumb is the minimum needed to answer "where am I?" inside nested
         * precomps. It is not clickable yet; the back arrow and Esc are. */
        this.compPath = new UILabel(IKey.constant(""));
        this.compPath.color(0xFF8A94A6, false);
        this.compPath.labelAnchor(0F, 0.5F);
        this.compPath.relative(this.timelineHeader).x(496).y(3).w(1F, -526).h(20);

        UIIcon shortcuts = new UIIcon(Icons.HELP, (button) -> this.showShortcutHelp());
        shortcuts.relative(this.timelineHeader).x(1F, -24).y(3).w(20).h(20);
        shortcuts.tooltip(IKey.constant("快捷键（Ctrl+/）"));

        this.timelineHeader.add(this.addLayer, split, marker, this.snapButton, this.keyframeButton,
            zoomOut, zoomIn, fitTimeline, this.timelineTabs, this.precompBack, this.compPath, shortcuts);

        this.keyframeHandle = new PanelResizeHandle(3);
        this.keyframeHandle.setVisible(false);

        this.timeline.add(this.timelineHeader, this.trackTimeline, this.curveEditor, this.keyframeDrawer, this.keyframeHandle);

        if (this.dock != null)
        {
            /* The quick transform panel belongs to the timeline: it shares the timeline's dock
             * panel, so dragging the timeline to another edge/tab keeps it with its tracks. */
            this.quickPanel.relative(this.timeline).x(1F, -210).y(this.timelineContentTop()).w(210).h(1F, -this.timelineContentTop());

            this.timeline.add(this.quickPanel);

            this.dock
                .source(this.dockSource = new CompDockSource(this::dockSettings,
                    CompDockSource::defaultLayout, this::onDockLayoutSaved))
                .locked(!this.dockUnlocked())
                .frameless(CompDockSource.PANEL_PREVIEW)
                .onChanged(() -> this.dockLayoutDirty = true)
                .onLayoutSettled(this::onDockLayoutSettled);

            this.dock.addPanel(CompDockSource.PANEL_EFFECTS, this.effectColumn, Icons.MATERIAL,
                IKey.constant("效果/素材/预设"));
            this.dock.addPanel(CompDockSource.PANEL_PREVIEW, this.preview, Icons.CAMERA,
                IKey.constant("预览"));
            this.dock.addPanel(CompDockSource.PANEL_INSPECTOR, this.inspectorColumn, Icons.PROPERTIES,
                IKey.constant("属性"));
            this.dock.addPanel(CompDockSource.PANEL_TIMELINE, this.timeline, Icons.FILM,
                IKey.constant("时间轴"));
            this.dock.mount();
        }

        this.applyLayout();

        this.header.relative(this).x(0).y(0).w(1F).h(24);
        this.status.relative(this).x(0).y(1F, -18).w(1F).h(16);

        this.modalLayer = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xC8101216);
                super.render(context);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }

            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                return this.area.isInside(context);
            }
        };
        this.modalLayer.relative(this).x(0).y(0).w(1F).h(1F);
        this.modalLayer.setVisible(false);

        this.modalBox = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF1A1F27);
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF35404F);
                super.render(context);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }
        };
        this.modalBox.relative(this.modalLayer).x(0.5F, -220).y(0.5F, -150).w(440).h(300);

        this.modalTitle = new UILabel(IKey.constant(""));
        this.modalTitle.relative(this.modalBox).x(16).y(12).w(1F, -32).h(20);

        this.modalHint = new UILabel(IKey.constant(""));
        this.modalHint.color(0xFF9AA4B2, false);
        this.modalHint.relative(this.modalBox).x(16).y(34).w(1F, -32).h(16);

        this.modalBox.add(this.modalTitle, this.modalHint);
        this.modalLayer.add(this.modalBox);

        this.startScreen = new UIStartScreen(this::openProject, this::createProject, this::setShortcutScheme, this::leaveStartScreen);
        this.startScreen.relative(this).x(0).y(0).w(1F).h(1F);
        this.startScreen.setVisible(false);

        this.onboarding = new UIOnboarding(this);
        this.onboarding.relative(this).x(0).y(0).w(1F).h(1F);
        this.onboarding.setVisible(false);

        this.settingsOverlay = new UICompSettings(this);
        this.settingsOverlay.relative(this).x(0).y(0).w(1F).h(1F);
        this.settingsOverlay.setVisible(false);

        if (this.dock != null)
        {
            this.add(this.header, this.modeBar, this.dock, this.renderPanel, this.replayWorkspace, this.status,
                this.modalLayer, this.startScreen, this.onboarding, this.settingsOverlay);
        }
        else
        {
            this.add(this.header, this.modeBar, this.body, this.renderPanel, this.timeline, this.quickPanel,
                this.replayWorkspace, this.status,
                this.modalLayer, this.startScreen, this.onboarding, this.settingsOverlay);
        }

        this.focusButton.h(18);

        this.onAppear(() ->
        {
            this.ensureBound();
            this.lastPlayNanos = 0L;

            if (this.project == null)
            {
                this.showStartScreen();
            }

            /* Coming back from another dashboard page: re-derive the strip instead of trusting
             * the visibility flags left behind. */
            this.applyTimelinePage();

            if (!Boolean.getBoolean("bbsae.debugNoOnboarding") && CompPrefs.get().needsOnboarding())
            {
                this.openOnboarding();
            }
        });
        this.onAppear(() -> dev.bbsae.client.overlay.BbsPreviewBridge.attach(this.filmPanel, this.doc));
        this.onClose(() ->
        {
            this.lastPlayNanos = 0L;
            this.keyframeEditor.view.pickKeyframe(null);
            this.restoreFocusBeforePersist();
            this.leavePanel(true);
        });
        this.onDisappear(() ->
        {
            this.lastPlayNanos = 0L;
            this.keyframeEditor.view.pickKeyframe(null);
            this.restoreFocusBeforePersist();
            this.leavePanel(false);
        });
    }

    /**
     * Shared leave logic. Closing the panel (the user is done) may stop background work, but
     * merely switching to another dashboard page must NOT silently cancel a running export: the
     * export keeps ticking from the client and is still reachable through the render page/status
     * line. Precache only advances while this panel renders, so it is stopped either way.
     */
    private void leavePanel(boolean closed)
    {
        this.stopCameraFlightOnLeave();

        /* Do not leave the embedded replay editor running behind another dashboard page. */
        if (this.workspaceMode == WORKSPACE_REPLAY)
        {
            this.workspaceMode = WORKSPACE_COMPOSITE;
            this.replayWorkspace.disappear();
            this.replayWorkspace.setVisible(false);
            this.replayWorkspace.setEnabled(false);
        }

        this.savePanelLayout();
        this.flushSave();
        CompPrefs.flush();

        if (closed)
        {
            /* No visible export UI exists any more once the panel is closed. */
            if (CompExportManager.isBusy())
            {
                CompExportManager.cancel();
                this.setStatus("已取消导出（关闭合成器面板）");
            }

            if (this.settingsOverlay != null)
            {
                this.settingsOverlay.close();
            }
        }

        dev.bbsae.client.render.CompPrecache.stop();
        dev.bbsae.client.overlay.BbsPreviewBridge.detach();
    }

    /** A dashboard page switch must not leave the hidden-pointer BBS flight controller running. */
    private void stopCameraFlightOnLeave()
    {
        if (this.cameraFlying)
        {
            this.cameraFlying = false;
            this.flightCamera = null;
            this.flightLastMouseX = Integer.MIN_VALUE;

            try
            {
                mchorse.bbs_mod.graphics.window.Window.setCursorHidden("bbsae_bbs_flight", false);
            }
            catch (RuntimeException ignored)
            {}
        }
    }

    /* Film-scoped lifecycle */

    /**
     * A compositor belongs to exactly one BBS film (replay project). This is the only entry
     * point that gives the panel data; the dashboard tab alone deliberately has no project.
     */
    /**
     * Opens the embedded replay workspace. Kept public for {@code BbsAeClient.openReplayMode} and
     * the debug scripts; the mode bar calls the same path.
     */
    public void openReplayWorkspace()
    {
        this.switchWorkspace(WORKSPACE_REPLAY);
    }

    public void openCompositeWorkspace()
    {
        this.switchWorkspace(WORKSPACE_COMPOSITE);
    }

    public UIReplayModePanel replayWorkspacePanel()
    {
        return this.replayWorkspace;
    }

    public mchorse.bbs_mod.film.Film film()
    {
        return this.film;
    }

    public void bind(mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel, mchorse.bbs_mod.film.Film film, mchorse.bbs_mod.film.replays.Replay replay)
    {        if (film == null)        {
            return;
        }

        if (this.project != null)
        {
            this.flushSave();
        }

        this.precompStack.clear();

        if (this.precompBack != null)
        {
            this.precompBack.setVisible(false);
        }

        this.filmPanel = filmPanel;
        this.film = film;
        this.replay = replay;

        if (this.replay == null && !film.replays.getList().isEmpty())
        {
            this.replay = film.replays.getList().get(0);
        }

        this.replaySelector.label = IKey.constant("回放：" + (this.replay == null ? "-" : this.replayLabel(this.replay)));

        mchorse.bbs_mod.ui.ContentType.FILMS.getRepository().requestKeys((keys) ->
        {
            List<String> ids = new ArrayList<>(keys);

            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
            {
                this.filmIds.clear();
                this.filmIds.addAll(ids);
            });
        });

        int width = Math.max(1, mchorse.bbs_mod.BBSSettings.videoWidth.get());
        int height = Math.max(1, mchorse.bbs_mod.BBSSettings.videoHeight.get());
        float fps = Math.max(1F, mchorse.bbs_mod.BBSSettings.videoFrameRate.get());
        float duration = Math.max(1F, film.calculateDuration() / 20F);

        this.project = CompStore.loadFilmProject(film.getId(), film.getId(), width, height, fps, duration);

        /* The stage colour, decided once per project.
         *
         * A compositor's preview is a picture of the frame that will be exported, so it starts on
         * the composition's own background. The old migration defaulted to the live BBS world
         * behind a transparent stage; with a film open that reads as "the compositor is
         * transparent / the panel is broken", and with no film it is just a grey void. The world
         * backdrop is still one click away in the preview bar (预览背景 → BBS 实时画面). */
        if (this.project.settings != null && !this.project.settings.previewBackgroundChosen)
        {
            this.project.settings.previewBackground = 0;
            this.project.settings.previewBackgroundChosen = true;
        }

        this.doc = replay == null
            ? this.project.getCompOrFirst(CompStore.DEFAULT_COMP)
            : CompStore.ensureReplayComp(this.project,
                replay.getId() == null || replay.getId().isBlank() ? replay.getName() : replay.getId(),
                replay.getName(), width, height, fps, duration);
        this.resetHistory();

        if (this.doc != null)
        {
            this.doc.duration = Math.max(this.doc.duration, duration);
            this.doc.workAreaOut = Math.max(this.doc.workAreaOut, duration);
            normalizeWorkArea(this.doc);
        }

        if (this.trackTimeline != null)
        {
            this.trackTimeline.fit();
        }

        this.time = 0F;

        try
        {
            if (filmPanel != null)
            {
                this.time = Math.max(0F, Math.min(duration, filmPanel.getCursor() / 20F));
            }
        }
        catch (RuntimeException ignored)
        {}

        this.playing = false;
        this.resetTimelineView = true;
        this.setSingleSelection(this.doc == null || this.doc.layers.isEmpty() ? null : this.doc.layers.get(this.doc.layers.size() - 1));

        if (this.doc != null)
        {
            CompositionClip.ensureForFilm(film, this.project.id, this.doc.id, this.doc.duration);
            this.syncBbsLoopToDocument();
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();

        /* A project may have been saved in AE mode: drop the frames of the previous document and
         * put the badge / export label back in sync before the first draw. */
        dev.bbsae.client.render.CompFrameServer.invalidate();
        dev.bbsae.client.audio.CompAudioPlayer.invalidate();
        this.refreshEncoderState();

        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);

        if (this.bbsBackdropToggle != null)
        {
            this.bbsBackdropToggle.setValue(this.project != null && this.project.settings != null && this.project.settings.previewBackground == 1);
        }

        this.previewControlsCollapsed = this.project != null && this.project.settings != null && this.project.settings.previewControlsCollapsed;
        this.previewFilterExpanded = this.project != null && this.project.settings != null && this.project.settings.previewFilterExpanded;
        this.applyPreviewBarState();
        this.loadPanelLayout();

        this.save();
        this.primedWidth = -1;

        /* Opening the compositor from a film already has project + replay + playhead. Showing
         * the project chooser here forced an extra click and, worse, openProject() reset the
         * playhead to 0 through bindComposition(). The landing screen now only appears when
         * there is no project at all (see onAppear/ensureBound). */
        this.hideStartScreen();
        this.updateCompPath();
    }

    private void hideStartScreen()
    {
        this.startScreenOpen = false;

        if (this.startScreen != null)
        {
            this.startScreen.setVisible(false);
        }
    }

    /** Root → ... → current composition, shown in the timeline toolbar. */
    private void updateCompPath()
    {
        if (this.compPath == null)
        {
            return;
        }

        if (this.doc == null)
        {
            this.compPath.label = IKey.constant("");
            return;
        }

        List<String> names = new ArrayList<>();

        names.add(this.doc.name == null || this.doc.name.isBlank() ? this.doc.id : this.doc.name);

        for (PrecompReturn state : this.precompStack)
        {
            String name = state.doc.name == null || state.doc.name.isBlank() ? state.doc.id : state.doc.name;

            names.add(0, name);
        }

        this.compPath.label = IKey.constant(String.join(" / ", names));
    }

    /**
     * Old projects (and every project created before the work-area fix) carry the 12-second
     * default work-area out point even after the composition duration was raised. Playback loops
     * at workAreaOut, so the editor appeared to stop at 12s. Follow the duration when the out
     * point is still the untouched default; leave a deliberately shorter loop alone.
     */
    /**
     * BBS's own record button uses the film panel's loop range before it uses the camera duration.
     * Keep that range on the composition length too, so "open compositor, press BBS record" and
     * "press compositor Export" produce the same length.
     */
    private void syncBbsLoopToDocument()
    {
        if (this.doc != null)
        {
            this.syncBbsLoop(Math.max(20, Math.round(this.doc.duration * 20F) + 1));
        }
    }

    /**
     * The timeline's drag range is {@code doc.duration}. If a duration field is raised, the
     * composition itself has to grow first; otherwise the user sees a barrier at the old end
     * (the reported "20s wall") even though the export field says 40.
     */
    private void adoptCompositionDuration(float seconds)
    {
        if (this.doc == null || seconds <= 0F)
        {
            return;
        }

        if (seconds <= this.doc.duration + 0.0001F)
        {
            return;
        }

        this.doc.duration = seconds;
        this.doc.workAreaOut = Math.max(this.doc.workAreaIn + 0.1F, seconds);

        if (this.film != null && this.filmPanel != null && this.project != null)
        {
            this.syncBbsLoop(Math.max(20, Math.round(seconds * 20F) + 1));
            CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id, seconds);
            this.filmPanel.forceSave();
        }

        this.refreshLayers();
        this.refreshSheets();
        this.save();

        if (this.trackTimeline != null)
        {
            this.trackTimeline.fit();
        }
    }

    private void syncBbsLoop(int ticks)
    {
        if (this.filmPanel == null
            || this.filmPanel.cameraEditor == null || this.filmPanel.cameraEditor.clips == null)
        {
            return;
        }

        this.filmPanel.cameraEditor.clips.loopMin = 0;
        this.filmPanel.cameraEditor.clips.loopMax = Math.max(20, ticks);
    }

    private static void normalizeWorkArea(CompDocument doc)
    {
        if (doc == null)
        {
            return;
        }

        if (doc.workAreaOut <= 0F
            || (doc.duration > 12.001F && Math.abs(doc.workAreaOut - 12F) < 0.001F)
            || doc.workAreaOut > doc.duration)
        {
            doc.workAreaOut = doc.duration;
        }

        if (doc.workAreaIn < 0F || doc.workAreaIn >= doc.workAreaOut)
        {
            doc.workAreaIn = 0F;
        }
    }

    /** Fallback when the dashboard tab is opened directly: locate the currently open film. */
    private void ensureBound()
    {
        if (this.project != null)
        {
            return;
        }

        mchorse.bbs_mod.ui.film.UIFilmPanel panel = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

        if (panel != null && panel.getData() != null)
        {
            this.bind(panel, panel.getData(), this.replay);
        }
        else
        {
            this.showStartScreen();
        }
    }

    /* Landing screen / project lifecycle */

    public void showStartScreen()
    {
        if (this.startScreen == null)
        {
            return;
        }

        this.playing = false;
        this.playRate = 0F;
        this.startScreenOpen = true;

        if (this.project != null)
        {
            this.startScreen.setCurrentProject(this.project.id);
            this.startScreen.setScheme(this.getShortcutScheme());
        }
        else
        {
            this.startScreen.setScheme(this.pendingScheme);
            this.startScreen.reload();
        }

        this.startScreen.setVisible(true);
        this.startScreen.resize();
        this.startScreen.replayMotion();
        this.updatePlayLabel();
    }

    private void leaveStartScreen()
    {
        if (this.filmPanel != null && this.dashboard != null)
        {
            this.startScreenOpen = false;
            this.startScreen.setVisible(false);
            this.dashboard.setPanel(this.filmPanel);
            mchorse.bbs_mod.ui.framework.UIScreen.open(this.dashboard);
        }
        else
        {
            /* Standalone dashboard tab: keep the landing screen up rather than dropping the user
             * into an editor with no project behind it. */
            this.setStatus("请先创建或选择一个工程");
        }
    }

    private void openProject(CompStore.ProjectInfo info)
    {
        if (info == null)
        {
            return;
        }

        this.startScreenOpen = false;
        this.startScreen.setVisible(false);

        CompProject project = CompStore.loadOrNull(info.id, true);

        if (project == null)
        {
            /* Do not leave the user on an editor with no project behind it. The damaged file has
             * been quarantined as .corrupt-*, so the original data is still recoverable. */
            this.startScreenOpen = true;
            this.startScreen.setVisible(true);
            this.setStatus("工程加载失败：" + info.id + "（文件可能损坏，已隔离为 .corrupt-* 备份）");

            return;
        }

        if (project.settings != null && CompProject.ENCODER_AE.equals(project.settings.encoder)
            && project.needsBbsWorldRender())
        {
            project.settings.encoder = CompProject.ENCODER_BBS;
            this.setStatus("工程包含 BBS 预览/回放图层，已切回 BBS 原生导出");
        }

        CompDocument comp = project.getCompOrFirst(CompStore.DEFAULT_COMP);

        if (comp == null)
        {
            comp = project.addComp("Main", info.width, info.height, info.fps, info.duration);
        }

        if (this.filmPanel != null && this.film != null)
        {
            this.bindComposition(this.filmPanel, this.film, project.id, comp.id);
        }
        else
        {
            this.bindStandalone(project, comp);
        }

        this.setStatus("已打开工程：" + project.name);
    }

    private void createProject(String name, int width, int height, float fps, float duration)
    {
        String base = CompStore.safe(name).toLowerCase(java.util.Locale.ROOT);

        /* safe() strips non-ASCII, so a Chinese project name would otherwise become "___".
         * Fall back to a readable prefix plus a stable hash, then de-duplicate below. */
        if (base.replace("_", "").replace(".", "").isEmpty())
        {
            base = "proj_" + Integer.toHexString(name.hashCode() & 0x7FFFFFFF);
        }

        String id = base;
        int serial = 2;

        while (CompStore.exists(id))
        {
            id = base + "_" + serial;
            serial += 1;
        }

        CompProject project = CompStore.newProject(id, name, width, height, fps, duration);

        project.settings.shortcutScheme = this.getShortcutScheme();
        project.settings.defaultLayerDuration = this.project == null ? 0F : this.project.settings.defaultLayerDuration;
        project.settings.showDuringExternalPlayback = this.project == null || this.project.settings.showDuringExternalPlayback;

        CompStore.save(project);

        CompStore.ProjectInfo info = new CompStore.ProjectInfo(project.id, project.name, project.comps.size(),
            width, height, fps, duration, System.currentTimeMillis());

        this.openProject(info);

        /* A brand-new project inherits the workspace the user chose during onboarding. Existing
         * projects keep their own saved layout. */
        this.applyLayoutPreset(CompPrefs.get().layoutPreset);
    }

    /** Standalone project (no film): editing, grading and the render queue still work. */
    private void bindStandalone(CompProject project, CompDocument comp)
    {
        /* Write the OUTGOING project before `this.project` points at the new one: flushSave()
         * serializes `this.project`, so a debounced edit inside the 600 ms window would otherwise
         * be written into the newly opened project (or lost). */
        this.flushProjectBeforeSwitch();
        this.hideStartScreen();
        this.precompStack.clear();

        if (this.precompBack != null)
        {
            this.precompBack.setVisible(false);
        }

        this.project = project;
        this.doc = comp;
        this.resetHistory();
        this.replay = null;
        this.filmPanel = null;
        this.film = null;
        this.time = 0F;
        this.playing = false;
        this.playRate = 0F;
        this.setSingleSelection(comp == null || comp.layers.isEmpty() ? null : comp.layers.get(comp.layers.size() - 1));
        this.replaySelector.label = IKey.constant("工程：" + (project.name == null ? project.id : project.name));

        if (this.trackTimeline != null)
        {
            this.trackTimeline.fit();
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();
        this.loadPanelLayout();
        this.primedWidth = -1;
        this.updateCompPath();
        this.save();
    }

    public int getShortcutScheme()
    {
        if (this.project != null && this.project.settings != null)
        {
            return this.project.settings.shortcutScheme == 1 ? 1 : 0;
        }

        return this.pendingScheme;
    }

    private void setShortcutScheme(int scheme)
    {
        this.pendingScheme = scheme == 1 ? 1 : 0;

        if (this.project != null && this.project.settings != null)
        {
            this.project.settings.shortcutScheme = this.pendingScheme;
            this.save();
        }

        this.setStatus(this.pendingScheme == 1 ? "快捷键方案：BBS 原版" : "快捷键方案：Premiere");
        CompPrefs.get().shortcutScheme = this.pendingScheme;
        CompPrefs.save();
    }

    /** Opens a specific project/comp pair stored on a composition clip. */
    public void bindComposition(mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel, mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        if (filmPanel == null || film == null)
        {
            return;
        }

        this.flushProjectBeforeSwitch();
        this.hideStartScreen();
        this.precompStack.clear();

        if (this.precompBack != null)
        {
            this.precompBack.setVisible(false);
        }

        this.filmPanel = filmPanel;
        this.film = film;
        this.replay = null;

        CompProject requested = projectId == null || projectId.isBlank() ? null : CompStore.loadOrNull(projectId, true);

        /* A missing comp must not silently swap the whole project: the clip may point at a
         * project that exists but whose comp was renamed/deleted. Fall back inside that project. */
        if (requested == null)
        {
            requested = CompStore.loadFilmProject(film.getId(), film.getId(),
                Math.max(1, mchorse.bbs_mod.BBSSettings.videoWidth.get()),
                Math.max(1, mchorse.bbs_mod.BBSSettings.videoHeight.get()),
                Math.max(1F, mchorse.bbs_mod.BBSSettings.videoFrameRate.get()),
                Math.max(1F, film.calculateDuration() / 20F));
        }

        this.project = requested;
        this.doc = compId == null ? this.project.getCompOrFirst(CompStore.DEFAULT_COMP) : this.project.getComp(compId);
        this.resetHistory();

        if (this.doc == null)
        {
            this.doc = this.project.getCompOrFirst(CompStore.DEFAULT_COMP);
        }

        this.time = 0F;
        this.playing = false;
        this.playRate = 0F;
        this.setSingleSelection(this.doc == null || this.doc.layers.isEmpty() ? null : this.doc.layers.get(this.doc.layers.size() - 1));
        this.replaySelector.label = IKey.constant("回放：片段");

        if (this.trackTimeline != null)
        {
            this.trackTimeline.fit();
        }

        if (this.doc != null)
        {
            CompositionClip.ensureForFilm(film, this.project.id, this.doc.id, this.doc.duration);
            this.syncBbsLoopToDocument();
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();
        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);

        if (this.bbsBackdropToggle != null)
        {
            this.bbsBackdropToggle.setValue(this.project != null && this.project.settings != null && this.project.settings.previewBackground == 1);
        }

        this.previewControlsCollapsed = this.project != null && this.project.settings != null && this.project.settings.previewControlsCollapsed;
        this.previewFilterExpanded = this.project != null && this.project.settings != null && this.project.settings.previewFilterExpanded;
        this.applyPreviewBarState();
        this.loadPanelLayout();
        this.updateCompPath();

        this.save();
    }

    private void exportFilm()
    {
        if (this.usesAeEncoder())
        {
            this.exportWithAeEncoder();

            return;
        }

        if (this.filmPanel == null)
        {
            this.filmPanel = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);
        }

        if (this.filmPanel == null || this.filmPanel.getData() == null)
        {
            this.notifyError("请先打开一个影片");
            return;
        }

        this.showExportDialog();
    }

    /* ------------------------------------------------------------ encoder mode */

    /** True while the compositor's own frame server drives preview and export. */
    public boolean usesAeEncoder()
    {
        return this.project != null
            && this.project.settings != null
            && CompProject.ENCODER_AE.equals(this.project.settings.encoder);
    }

    /** AE preview proxy factor; 1 outside AE mode or when the setting is missing. */
    public float encoderProxy()
    {
        if (this.project == null || this.project.settings == null || !this.usesAeEncoder())
        {
            return 1F;
        }

        return Math.max(0.1F, Math.min(1F, this.project.settings.encoderProxy <= 0F ? 1F : this.project.settings.encoderProxy));
    }

    /** Human label for a proxy factor: 1/1, 3/4, 1/2, 1/4. */
    static String proxyLabel(float proxy)
    {
        if (proxy >= 0.999F) return "1/1";
        if (proxy >= 0.74F) return "3/4";
        if (proxy >= 0.49F) return "1/2";

        return "1/4";
    }

    private String currentEncoder()
    {
        return this.project == null || this.project.settings == null || this.project.settings.encoder == null
            ? CompProject.ENCODER_BBS
            : this.project.settings.encoder;
    }

    /** Keeps the badge, the export button and the status line in sync with the setting. */
    private void refreshEncoderState()
    {
        boolean ae = this.usesAeEncoder();

        if (this.encoderBadge != null)
        {
            this.encoderBadge.setVisible(ae);
        }

        if (this.exportFilm != null)
        {
            this.exportFilm.label = IKey.constant(ae ? "AE 导出" : "导出");
        }

        if (this.play != null)
        {
            this.play.tooltip(IKey.constant(ae
                ? "播放 / 暂停（AE 编码器：按合成帧率取帧）"
                : "播放 / 暂停合成预览（空格）"));
        }

        /* A toggle that cannot change the picture is worse than no toggle: in AE mode the frame
         * server always renders effects and masks, and the stage never shows the world backdrop
         * (see the risk list). Hide them instead of letting the user click into a no-op. */
        if (this.effectsToggle != null)
        {
            this.effectsToggle.setVisible(!ae);
            this.masksToggle.setVisible(!ae);
            this.bbsBackdropToggle.setVisible(!ae);
        }
    }

    /** Choice-button entry point: AE needs an explicit acknowledgement before it is applied. */
    private void requestEncoder(String encoder)
    {
        String next = CompProject.ENCODER_AE.equals(encoder) ? CompProject.ENCODER_AE : CompProject.ENCODER_BBS;

        if (next.equals(this.currentEncoder()))
        {
            return;
        }

        /* The AE renderer produces frames directly from the comp; it cannot sample the tick-driven
         * BBS world. A project containing a BBS preview or replay-entity key layer is defined by
         * that world frame, so switching would silently change what those layers show. */
        if (CompProject.ENCODER_AE.equals(next) && this.project != null && this.project.needsBbsWorldRender())
        {
            this.closeModal();

            if (this.settingsOverlay != null)
            {
                this.settingsOverlay.close();
            }

            this.setStatus("本项目包含 BBS 预览/回放抠像图层，只能使用 BBS 原生导出；请先移除这些图层再切换 AE");

            return;
        }

        if (CompProject.ENCODER_AE.equals(next)
            && (this.project == null || this.project.settings == null || !this.project.settings.encoderRiskAcknowledged))
        {
            /* Closing the settings dialog makes the rejected choice unambiguous: there is no
             * half-selected state to explain. */
            this.closeModal();

            if (this.settingsOverlay != null)
            {
                this.settingsOverlay.close();
            }

            this.showEncoderRisk();

            return;
        }

        this.applyEncoder(next);

        /* Rebuild the dialog so the hint under the chooser describes the pipeline that is now
         * active; keeping the old sentence would be the exact kind of stale label this mode is
         * supposed to remove. */
        this.openSettings("advanced");
    }

    private void applyEncoder(String encoder)
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        this.project.settings.encoder = encoder;

        if (CompProject.ENCODER_AE.equals(encoder))
        {
            this.project.settings.encoderRiskAcknowledged = true;
        }

        /* The two pipelines keep different caches; crossing over with either still populated would
         * mix frames from two different meanings of "frame N". */
        dev.bbsae.client.render.CompFrameServer.invalidate();
        dev.bbsae.client.render.CompRenderCache.clear();
        dev.bbsae.client.audio.CompAudioPlayer.invalidate();

        this.refreshEncoderState();
        this.save();

        this.setStatus(CompProject.ENCODER_AE.equals(encoder)
            ? "已启用 AE 编码器：预览与导出都走合成帧服务器（第 N 帧 = N / 帧率）"
            : "已切回 BBS 原生：合成作为影片叠加层，由 BBS 录像器导出");
    }

    /** The risk list shown once, before AE mode is enabled. */
    private void showEncoderRisk()
    {
        this.openModalSized("启用 AE 编码器前请先读这些",
            "这不是换一个导出按钮，而是换掉整条渲染 / 编码管线。", 660, 392);

        String[] risks = {
            "① 导出不再经过 BBS 录像器（F6）。成片分辨率 = 合成分辨率，帧率 = 合成帧率；BBS 的录制设置、相机路径、loop 区间不再影响它。",
            "② 3D 世界与演员只能通过“BBS 预览”图层进入：该图层是游戏主帧缓冲的拷贝，预览时实时，离线导出时是最后一帧静态画面；依赖世界深度的效果在导出里退化为无深度。",
            "③ 预览按合成分辨率全量渲染（1080p 每帧约 8 MB）。复杂合成可能掉帧，但帧号永远正确，不会再出现半帧或撕裂。",
            "④ 效果 / 蒙版对比开关、BBS 世界底图、参考层在 AE 模式下不参与成片；参考层只在编辑视图里可见。",
            "⑤ 合成时长 / 帧率与 BBS 影片不一致时，两者不再自动对齐；在 BBS 影片里播放时仍然按 BBS 模式渲染。",
            "⑥ 随时可以在 设置 → 渲染 / 编码管线 切回 BBS 原生，两个模式共用同一份工程文件。"
        };

        int y = 52;

        for (String risk : risks)
        {
            UILabel line = new UILabel(IKey.constant(risk));

            line.color(0xFFFFB4A9, false);
            line.relative(this.modalBox).x(18).y(y).w(1F, -36).h(32);
            this.modalBox.add(line);
            y += 34;
        }

        UIButton cancel = CompUi.flat("取消", "保持 BBS 原生管线（不做任何修改）", () ->
        {
            this.closeModal();
            this.showSettings();
        });
        cancel.relative(this.modalBox).x(18).y(348).w(120).h(20);

        UIButton accept = CompUi.flat("我了解风险，启用 AE 编码器", "接受上面六条，切换到合成帧服务器管线", () ->
        {
            this.applyEncoder(CompProject.ENCODER_AE);
            this.closeModal();
        });
        accept.relative(this.modalBox).x(146).y(348).w(300).h(20);

        this.modalBox.add(cancel, accept);
    }

    /**
     * AE-mode export: queue the composition through the encoder instead of the BBS recorder.
     *
     * <p>Same queue, same presets, same FFmpeg — the difference is where the frames come from. The
     * queue already asks {@code CompRenderer.renderForExport}, which since this round is
     * frame-indexed, so the file contains exactly the frames the preview showed.</p>
     */
    private void exportWithAeEncoder()
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        String presetId = this.project.settings == null || this.project.settings.defaultOutputPreset == null
            || this.project.settings.defaultOutputPreset.isBlank()
            ? "review_h264"
            : this.project.settings.defaultOutputPreset;

        CompOutputPreset chosen = this.project.getPreset(presetId);

        /* "AE 导出" means a video. If the project's default module happens to be an audio one
         * (exporting a music bed is a legitimate default), fall back to the review video module
         * instead of silently writing a WAV from a button that says "export". */
        if (chosen == null || "audio".equals(chosen.kind) || "wav".equalsIgnoreCase(chosen.container))
        {
            presetId = "review_h264";
        }

        CompRenderJob job = new CompRenderJob(
            "job_" + (this.project.renderQueue.size() + 1),
            this.project.id,
            this.doc.id,
            presetId
        );

        job.outputName = this.doc.id + "_ae";
        job.rangeStart = 0F;
        job.rangeEnd = this.doc.duration;

        this.project.renderQueue.add(job);
        this.selectedJob = job;
        this.refreshQueue();
        this.save();
        this.toggleRenderView();

        boolean staticWorld = false;

        for (CompLayer layer : this.doc.layers)
        {
            if (layer != null && "bbs".equals(layer.type) && layer.visible)
            {
                staticWorld = true;
                break;
            }
        }

        if (CompExportManager.start(this.project, job))
        {
            this.setStatus("AE 导出：" + job.outputName + " · " + Math.round(this.doc.width) + "×" + Math.round(this.doc.height)
                + " @" + Math.round(this.doc.fps) + "fps"
                + (staticWorld ? " · 注意：BBS 预览层是静态帧" : ""));
        }
        else
        {
            this.notifyError("AE 导出启动失败（渲染队列忙？）");
        }
    }

    /* Modal helpers */

    private void openModal(String title, String hint)
    {
        this.modalTitle.label = IKey.constant(title);
        this.modalHint.label = IKey.constant(hint);
        this.modalBox.removeAll();
        this.modalBox.relative(this.modalLayer).x(0.5F, -220).y(0.5F, -150).w(440).h(300);
        this.modalBox.add(this.modalTitle, this.modalHint);
        this.modalLayer.setVisible(true);
        this.modalLayer.resize();
        this.modalBox.resize();
    }

    private void closeModal()
    {
        this.modalLayer.setVisible(false);
    }

    private void openModalSized(String title, String hint, int width, int height)
    {
        this.openModal(title, hint);
        this.modalBox.relative(this.modalLayer).x(0.5F, -width / 2).y(0.5F, -height / 2).w(width).h(height);
        this.modalBox.resize();
    }

    /**
     * Opens the new BBS-style settings surface. The old modal is retained as an explicit
     * "advanced" fallback because the encoder risk list and a few diagnostics are still written
     * for that fixed-size dialog; the primary path is now the scrollable, animated overlay.
     */
    private void showSettings()
    {
        this.openSettings("motion");
    }

    public void openSettings(String category)
    {
        if (this.settingsOverlay == null)
        {
            return;
        }

        this.settingsOverlay.open(category);
    }

    public void openOnboarding()
    {
        if (this.onboarding == null || this.onboarding.isOpen())
        {
            return;
        }

        this.onboarding.open(() ->
        {
            this.setStatus("引导已完成；所有选项都能在设置中重新调整");
            this.onboardingClosed();
        });
        this.onboarding.debugPage(Integer.getInteger("bbsae.debugOnboardingPage", 0));
    }

    private void onboardingClosed()
    {
        if (this.startScreenOpen)
        {
            this.showStartScreen();
        }
    }

    public CompProject getProject()
    {
        return this.project;
    }

    public CompProject.CompSettings getProjectSettings()
    {
        if (this.project == null)
        {
            return null;
        }

        if (this.project.settings == null)
        {
            this.project.settings = new CompProject.CompSettings();
        }

        return this.project.settings;
    }

    public void saveSettings()
    {
        this.save();
    }

    public int getDensityPreference()
    {
        return this.project != null && this.project.settings != null
            ? this.project.settings.uiDensity
            : CompPrefs.get().uiDensity;
    }

    public void applyDensityPreference(int preference)
    {
        CompPrefs.get().uiDensity = preference;
        CompPrefs.save();
        this.applyDensity(preference);
    }

    public int getShortcutSchemeForSettings()
    {
        return this.getShortcutScheme();
    }

    public void applyShortcutScheme(int scheme)
    {
        this.setShortcutScheme(scheme);
        CompPrefs.get().shortcutScheme = scheme == 1 ? 1 : 0;
        CompPrefs.save();
    }

    public void applyMotionPreset(int preset)
    {
        CompPrefs.Data prefs = CompPrefs.get();

        switch (preset)
        {
            case 1 ->
            {
                prefs.animations = true;
                prefs.motionIntensity = 1.25F;
                prefs.motionSpeed = 0.85F;
                prefs.glow = true;
                prefs.shadows = true;
                prefs.backdropIntensity = 1.25F;
            }
            case 2 ->
            {
                prefs.animations = false;
                prefs.motionIntensity = 0.4F;
                prefs.motionSpeed = 1.25F;
                prefs.glow = false;
                prefs.shadows = false;
                prefs.backdropIntensity = 0.35F;
            }
            default ->
            {
                prefs.animations = true;
                prefs.motionIntensity = 1F;
                prefs.motionSpeed = 1F;
                prefs.glow = true;
                prefs.shadows = true;
                prefs.backdropIntensity = 1F;
            }
        }

        CompPrefs.save();
        this.panelMotion.replay();
        this.setStatus("动效预设已应用");
    }

    public void applyLayoutPreset(int preset)
    {
        /* Applying a workspace preset is an explicit layout choice: it supersedes any transient
         * focus view, otherwise the next F press would restore the pre-preset tree over it. */
        this.focusMode = false;
        this.focusReturnRoot = null;
        this.focusReturnHidden = null;

        if (this.dockSource != null)
        {
            this.dockSource.suspendPersist(false);
        }

        int density = switch (preset)
        {
            case UIOnboarding.LAYOUT_COMPACT -> CompUi.DENSITY_COMPACT;
            case UIOnboarding.LAYOUT_WIDE -> CompUi.DENSITY_COMFORTABLE;
            default -> CompUi.DENSITY_AUTO;
        };

        this.applyDensity(density);

        switch (preset)
        {
            case UIOnboarding.LAYOUT_FOCUS ->
            {
                this.leftPanelWidth = 160;
                this.rightPanelWidth = 280;
                this.timelinePanelHeight = 230;
                this.quickPanelWidth = 190;
            }
            case UIOnboarding.LAYOUT_COMPACT ->
            {
                this.leftPanelWidth = 152;
                this.rightPanelWidth = 260;
                this.timelinePanelHeight = 232;
                this.quickPanelWidth = 184;
            }
            case UIOnboarding.LAYOUT_WIDE ->
            {
                this.leftPanelWidth = 214;
                this.rightPanelWidth = 350;
                this.timelinePanelHeight = 286;
                this.quickPanelWidth = 240;
            }
            default ->
            {
                this.leftPanelWidth = 190;
                this.rightPanelWidth = 300;
                this.timelinePanelHeight = 250;
                this.quickPanelWidth = 210;
            }
        }

        CompPrefs.get().layoutPreset = preset;
        CompPrefs.save();

        if (this.dock != null && this.dockSource != null)
        {
            /* Workspace presets are dock trees in dock mode; the side panels stay registered, the
             * focus preset instead hides them so the ensure pass does not re-add empty slots. */
            this.dockSource.setRoot(CompDockSource.presetLayout(preset));

            java.util.Set<String> hidden = new java.util.LinkedHashSet<>();

            if (preset == UIOnboarding.LAYOUT_FOCUS)
            {
                hidden.add(CompDockSource.PANEL_EFFECTS);
                hidden.add(CompDockSource.PANEL_INSPECTOR);
            }

            this.dockSource.setHiddenPanels(hidden);
            this.dock.refresh();
            this.dockLayoutDirty = true;
        }

        this.applyLayout();
        this.savePanelLayout();
        this.setStatus("工作区布局：" + UIOnboarding.LAYOUT_NAMES[Math.max(0, Math.min(UIOnboarding.LAYOUT_NAMES.length - 1, preset))]);
    }

    public void resetEditorLayout()
    {
        this.applyLayoutPreset(CompPrefs.get().layoutPreset);
    }

    public void applyExportDurationSetting(float seconds)
    {
        if (this.project == null)
        {
            return;
        }

        float duration = Math.max(0F, Math.min(3600F, seconds));

        this.project.settings.exportDuration = duration;

        if (duration > 0F)
        {
            if (this.doc != null)
            {
                this.doc.duration = Math.max(0.1F, duration);
                this.doc.workAreaOut = this.doc.duration;
            }

            if (this.film != null && this.filmPanel != null)
            {
                CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id, duration);
                this.syncBbsLoop(Math.max(20, Math.round(duration * 20F) + 1));
                this.filmPanel.forceSave();
            }

            this.refreshLayers();
            this.refreshSheets();
        }

        this.save();
        this.setStatus(duration <= 0F ? "导出时长：使用合成时长" : "导出时长：" + duration + " 秒");
    }

    public String getEncoderId()
    {
        return this.currentEncoder();
    }

    public void chooseEncoder(String encoder)
    {
        this.requestEncoder(encoder);
    }

    public float getEncoderProxySetting()
    {
        return this.project == null || this.project.settings == null ? 1F : this.encoderProxy();
    }

    public void setEncoderProxySetting(float proxy)
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        this.project.settings.encoderProxy = Math.max(0.1F, Math.min(1F, proxy));
        this.save();
        this.setStatus("AE 预览代理：" + proxyLabel(this.project.settings.encoderProxy));
    }

    public void clearPreviewCache()
    {
        if (this.project != null && this.doc != null)
        {
            dev.bbsae.client.render.CompPrecache.clear(this.project, this.doc);
        }
    }

    public void showStartScreenFromSettings()
    {
        if (this.settingsOverlay != null)
        {
            this.settingsOverlay.close();
        }

        this.showStartScreen();
    }

    public void showLegacySettings()
    {
        this.openLegacySettingsDialog();
    }

    /**
     * The original fixed-size settings modal. Kept behind ``高级`` because the encoder risk list
     * and a few legacy diagnostics are written for this layout; the normal entry point is the
     * scrollable overlay opened by :meth:`showSettings`.
     */
    private void openLegacySettingsDialog()
    {
        this.openModalSized("合成器设置", "设置会随当前工程保存；没有工程时先进入工程列表。", 520, 520);

        if (this.project != null && this.project.settings == null)
        {
            this.project.settings = new CompProject.CompSettings();
        }

        CompProject.CompSettings settings = this.project == null ? new CompProject.CompSettings() : this.project.settings;

        UILabel durationLabel = new UILabel(IKey.constant("默认图层初始时长"));
        durationLabel.relative(this.modalBox).x(18).y(60).w(160).h(18);

        UITrackpad duration = new UITrackpad((value) ->
        {
            if (this.project == null)
            {
                return;
            }

            this.project.settings.defaultLayerDuration = Math.max(0F, Math.min(3600F, value.floatValue()));
            this.save();
        });

        duration.limit(0, 3600);
        duration.setValue(settings.defaultLayerDuration);
        duration.relative(this.modalBox).x(190).y(58).w(120).h(20);
        duration.tooltip(IKey.constant("新建图层的出点 = 入点 + 该时长；0 表示铺满整个合成"));

        UILabel durationHint = new UILabel(IKey.constant("秒；0 = 铺满合成"));
        durationHint.color(0xFF8A94A6, false);
        durationHint.relative(this.modalBox).x(318).y(62).w(170).h(16);

        UIToggle external = new UIToggle(IKey.constant("外部播放回放时显示合成器效果"), settings.showDuringExternalPlayback, (toggle) ->
        {
            if (this.project == null)
            {
                return;
            }

            this.project.settings.showDuringExternalPlayback = toggle.getValue();
            this.save();
        });
        external.relative(this.modalBox).x(18).y(92).w(1F, -36).h(20);
        external.tooltip(IKey.constant("关闭后，在影片编辑器之外播放回放时不叠加合成器"));

        UILabel schemeLabel = new UILabel(IKey.constant("快捷键方案"));
        schemeLabel.relative(this.modalBox).x(18).y(128).w(160).h(18);

        java.util.List<Integer> schemes = java.util.List.of(0, 1);
        UIChoiceButton<Integer> scheme = new UIChoiceButton<>(schemes, (value) -> Icons.GEAR,
            (value) -> IKey.constant(value == 1 ? "BBS 原版" : "Premiere Pro"));

        scheme.callback((value) -> this.setShortcutScheme(value == null ? 0 : value));
        scheme.setValue(this.getShortcutScheme());
        scheme.relative(this.modalBox).x(190).y(126).w(140).h(20);
        scheme.tooltip(IKey.constant("Premiere：Alt+滚轮缩放；BBS：Ctrl+滚轮缩放、Alt+滚轮改行高"));

        UIToggle autoSave = new UIToggle(IKey.constant("自动保存"), settings.autoSave, (toggle) ->
        {
            if (this.project != null)
            {
                this.project.settings.autoSave = toggle.getValue();
                this.save();
            }
        });
        autoSave.relative(this.modalBox).x(18).y(160).w(150).h(20);

        UIToggle safeArea = new UIToggle(IKey.constant("显示安全框"), settings.showSafeArea, (toggle) ->
        {
            if (this.project != null)
            {
                this.project.settings.showSafeArea = toggle.getValue();
                this.save();
            }
        });
        safeArea.relative(this.modalBox).x(180).y(160).w(140).h(20);

        UIToggle grid = new UIToggle(IKey.constant("显示网格"), settings.showGrid, (toggle) ->
        {
            if (this.project != null)
            {
                this.project.settings.showGrid = toggle.getValue();
                this.save();
            }
        });
        grid.relative(this.modalBox).x(334).y(160).w(140).h(20);

        UILabel previewLabel = new UILabel(IKey.constant("预览背景"));
        previewLabel.relative(this.modalBox).x(18).y(196).w(160).h(18);

        UIChoiceButton<Integer> background = new UIChoiceButton<>(java.util.List.of(0, 1), (value) -> Icons.IMAGE,
            (value) -> IKey.constant(value == 1 ? "BBS 实时画面" : "纯色"));

        background.callback((value) ->
        {
            if (this.project != null)
            {
                this.project.settings.previewBackground = value == null ? 0 : value;
                this.bbsBackdropToggle.setValue(this.project.settings.previewBackground == 1);
                this.save();
            }
        });
        background.setValue(settings.previewBackground == 1 ? 1 : 0);
        background.relative(this.modalBox).x(190).y(194).w(140).h(20);

        UIButton resetLayout = CompUi.flat("重置面板布局", "把左右分栏与时间轴高度恢复成当前界面密度的默认值", () ->
        {
            this.leftPanelWidth = CompUi.leftWidth();
            this.rightPanelWidth = CompUi.rightWidth();
            this.timelinePanelHeight = CompUi.timelineHeight();
            this.keyframeDrawerHeight = 154;
            this.applyLayout();
            this.savePanelLayout();
        });
        resetLayout.relative(this.modalBox).x(18).y(230).w(150).h(20);

        UIButton openProjects = CompUi.flat("工程列表…", "重新打开工程列表（当前工程会先保存，不会丢）", () ->
        {
            this.closeModal();
            this.showStartScreen();
        });
        openProjects.relative(this.modalBox).x(176).y(230).w(110).h(20);

        UIButton shortcuts = CompUi.flat("快捷键表", "查看当前快捷键方案（Premiere / BBS 两套）", this::showShortcutHelp);
        shortcuts.relative(this.modalBox).x(294).y(230).w(100).h(20);

        /* Density: the three tiers plus "follow BBS". Auto resolves from BBS's applied GUI scale,
         * so the compositor stops looking oversized at scale 3–4. */
        UILabel densityLabel = new UILabel(IKey.constant("界面密度"));
        densityLabel.relative(this.modalBox).x(18).y(366).w(160).h(18);

        java.util.List<Integer> densities = java.util.List.of(0, 1, 2, 3);
        UIChoiceButton<Integer> density = new UIChoiceButton<>(densities, (value) -> Icons.GEAR,
            (value) -> IKey.constant(value == 0
                ? "跟随 BBS（" + CompUi.densityName(CompUi.density()) + "）"
                : CompUi.densityName(value) + " 档"));

        density.callback((value) -> this.applyDensity(value == null ? 0 : value));
        density.setValue(this.project == null || this.project.settings == null ? 0 : this.project.settings.uiDensity);
        density.relative(this.modalBox).x(190).y(364).w(180).h(20);
        density.tooltip(IKey.constant("小 = 行更矮、面板更窄，能塞下更多信息；大 = 触屏/高 DPI 更好点；自动跟随 BBS 的界面缩放"));

        UILabel densityHint = new UILabel(IKey.constant("小 / 中 / 大，自动跟随 BBS"));
        densityHint.color(0xFF8A94A6, false);
        densityHint.relative(this.modalBox).x(376).y(368).w(150).h(16);

        UILabel note = new UILabel(IKey.constant("提示：时间轴空白处拖动 = 框选播放头；轨道两端 7px 内拖动 = 裁剪入/出点。"));
        note.color(0xFF8A94A6, false);
        note.relative(this.modalBox).x(18).y(266).w(1F, -36).h(28);

        UILabel exportLabel = new UILabel(IKey.constant("合成 / 导出时长（秒）"));
        exportLabel.relative(this.modalBox).x(18).y(302).w(160).h(18);

        UITrackpad exportDuration = new UITrackpad((value) ->
        {
            if (this.project == null)
            {
                return;
            }

            this.project.settings.exportDuration = Math.max(0F, Math.min(3600F, value.floatValue()));

            if (this.project.settings.exportDuration > 0F)
            {
                this.adoptCompositionDuration(this.project.settings.exportDuration);
            }

            this.save();
        });
        exportDuration.limit(0F, 3600F);
        exportDuration.setValue(settings.exportDuration > 0F
            ? settings.exportDuration
            : (this.doc == null ? 0F : this.doc.duration));
        exportDuration.relative(this.modalBox).x(190).y(300).w(120).h(20);
        exportDuration.tooltip(IKey.constant("0 = 使用合成时长；>0 时直接传给 BBS 原生录像器"));
        this.settingsDurationField = exportDuration;

        UILabel exportHint = new UILabel(IKey.constant("秒；0 = 当前合成时长"));
        exportHint.color(0xFF8A94A6, false);
        exportHint.relative(this.modalBox).x(318).y(304).w(170).h(16);

        UIButton applyExportDuration = new UICompButton(IKey.constant("应用时长到合成与 BBS 时间轴"), (button) ->
        {
            if (this.doc == null || this.film == null || this.filmPanel == null || this.project == null)
            {
                this.setStatus("先进入一个影片工程");

                return;
            }

            /* Read the live trackpad, not the stored setting: if the user types 40 and clicks this
             * button without pressing Enter, the stored value may still be 0. */
            float seconds = this.settingsDurationField == null
                ? 0F
                : (float) this.settingsDurationField.getValue();

            if (seconds <= 0F)
            {
                seconds = this.doc.duration;
            }

            this.project.settings.exportDuration = Math.max(0F, Math.min(3600F, seconds));
            this.doc.duration = Math.max(0.1F, seconds);
            this.doc.workAreaOut = this.doc.duration;
            this.syncBbsLoopToDocument();
            CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id, this.doc.duration);
            this.filmPanel.forceSave();
            this.save();
            this.refreshSheets();
            this.refreshLayers();

            if (this.trackTimeline != null)
            {
                this.trackTimeline.fit();
            }
            this.setStatus("导出时长 / 合成 / BBS 时间轴 = "
                + String.format(java.util.Locale.ROOT, "%.2fs", this.doc.duration));
        });
        applyExportDuration.relative(this.modalBox).x(18).y(330).w(250).h(20);
        applyExportDuration.tooltip(IKey.constant("三个地方使用同一时长，BBS 原版录制按钮也不会再截断"));

        /* ---- render / encoder pipeline ------------------------------------------------
         * The most consequential switch in the editor, so it gets its own row, a red hint and a
         * risk dialog instead of being one more toggle among "show grid". */
        UILabel encoderLabel = new UILabel(IKey.constant("渲染 / 编码管线"));
        encoderLabel.relative(this.modalBox).x(18).y(398).w(160).h(18);

        /* A project that needs the live BBS world cannot run on the AE encoder at all: offering the
         * entry and then rejecting the click is worse UX than not offering it. */
        boolean bbsOnly = this.project != null && this.project.needsBbsWorldRender();
        java.util.List<String> encoderValues = bbsOnly
            ? java.util.List.of(CompProject.ENCODER_BBS)
            : java.util.List.of(CompProject.ENCODER_BBS, CompProject.ENCODER_AE);
        mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> encoder =
            new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
                encoderValues,
                (value) -> CompProject.ENCODER_AE.equals(value) ? Icons.SERVER : Icons.FILM,
                (value) -> IKey.constant(CompProject.ENCODER_AE.equals(value) ? "AE 编码器（帧服务器）" : "BBS 原生（默认）"));

        encoder.callback(this::requestEncoder);
        encoder.setValue(this.currentEncoder());
        encoder.relative(this.modalBox).x(190).y(396).w(190).h(20);
        encoder.tooltip(IKey.constant(bbsOnly
            ? "本项目包含 BBS 预览 / 回放抠像图层，只能使用 BBS 原生导出（AE 渲染器无法复现按 tick 渲染的世界帧）"
            : "BBS：合成是影片叠加层，BBS 录像器导出。AE：合成自带帧服务器，预览与导出逐帧一致（有风险，首次切换会提示）"));

        /* Proxy is a *preview* option and says so: export always renders at 1/1, so the only thing
         * the factor changes is how many pixels the viewer spends. */
        java.util.List<String> proxies = java.util.List.of("1.0", "0.75", "0.5", "0.25");

        mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> proxy =
            new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
                proxies,
                (value) -> Icons.IMAGE,
                (value) -> IKey.constant("代理 " + proxyLabel(Float.parseFloat(value))));

        proxy.callback((value) ->
        {
            if (this.project == null || this.project.settings == null || value == null)
            {
                return;
            }

            this.project.settings.encoderProxy = Math.max(0.1F, Math.min(1F, Float.parseFloat(value)));
            dev.bbsae.client.render.CompFrameServer.invalidate();
            this.save();
            this.setStatus("AE 预览代理：" + proxyLabel(this.project.settings.encoderProxy) + "（导出始终 1/1）");
            this.showSettings();
        });

        float storedProxy = this.project == null || this.project.settings == null || this.project.settings.encoderProxy <= 0F
            ? 1F
            : this.project.settings.encoderProxy;

        proxy.setValue(proxies.contains(String.valueOf(storedProxy)) ? String.valueOf(storedProxy) : "1.0");
        proxy.relative(this.modalBox).x(390).y(396).w(112).h(20);
        proxy.tooltip(IKey.constant("AE 预览分辨率：1/2 表示按合成分辨率的一半渲染并放大显示。导出永远是全分辨率，徽标会标出代理倍率"));

        CompDocument encoderDoc = this.doc;
        boolean ae = CompProject.ENCODER_AE.equals(this.currentEncoder());

        UILabel encoderHint = new UILabel(IKey.constant(ae
            ? "AE：预览 = 导出，分辨率 / 帧率取合成；不经过 BBS 录像器。"
            : "BBS：合成作为影片叠加层，由 BBS 的 FFmpeg 录像器写出。"));
        encoderHint.color(ae ? 0xFFFF8A80 : 0xFF8A94A6, false);
        encoderHint.relative(this.modalBox).x(18).y(420).w(1F, -36).h(30);

        UIButton clearFrames = CompUi.flat("清理帧缓存", "删除磁盘上的 AE 帧缓存（config/bbsae/cache/frames）与显存缓存；不影响工程和素材", () ->
        {
            dev.bbsae.client.render.CompFrameDisk.clear();
            dev.bbsae.client.render.CompFrameServer.invalidate();
            this.setStatus("帧缓存已清理（磁盘 + 显存）；下一次预览会重新渲染");
        });
        clearFrames.relative(this.modalBox).x(18).y(452).w(120).h(18);

        UIToggle previewAudio = new UIToggle(IKey.constant("预览音频"), settings.previewAudio, (toggle) ->
        {
            if (this.project == null || this.project.settings == null)
            {
                return;
            }

            this.project.settings.previewAudio = toggle.getValue();

            if (!toggle.getValue())
            {
                dev.bbsae.client.audio.CompAudioPlayer.stop();
                this.previewAudioRunning = false;
            }

            this.save();
        });
        previewAudio.relative(this.modalBox).x(150).y(451).w(110).h(20);
        previewAudio.tooltip(IKey.constant("AE 模式下播放时试听合成音频轨（导出时无论如何都会混入）"));

        this.modalBox.add(encoderLabel, encoder, proxy, clearFrames, previewAudio, encoderHint);

        this.modalBox.add(durationLabel, duration, durationHint, external, schemeLabel, scheme,
            autoSave, safeArea, grid, previewLabel, background, resetLayout, openProjects, shortcuts, note,
            exportLabel, exportDuration, exportHint, applyExportDuration, densityLabel, density, densityHint,
            this.modalButton("关闭", 18, 484, 484, this::closeModal));
    }

    private UIButton modalButton(String label, int x, int y, int w, Runnable action)
    {
        UIButton button = CompUi.flat(label, "关闭这个对话框", action);

        button.relative(this.modalBox).x(x).y(y).w(w).h(20);

        return button;
    }

    /* Asset library */

    private void openEffectLibrary()
    {
        if (this.effectLibraryOverlay != null)
        {
            this.effectLibraryOverlay.removeFromParent();
            this.effectLibraryOverlay = null;
        }

        this.effectLibraryOverlay = new UIEffectLibraryOverlay(this, () ->
        {
            UIEffectLibraryOverlay overlay = this.effectLibraryOverlay;

            if (overlay == null || overlay.isClosing())
            {
                return;
            }

            /* Fade the overlay out first; beginClose runs the removal after this frame's render,
             * so the parent's child list is not mutated while it is being traversed. */
            overlay.beginClose(() ->
            {
                overlay.setVisible(false);
                overlay.removeFromParent();

                if (this.effectLibraryOverlay == overlay)
                {
                    this.effectLibraryOverlay = null;
                }
            });
        });
        this.effectLibraryOverlay.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.effectLibraryOverlay);
        this.resize();
    }

    private void openAssetBrowser(boolean replaceMode)
    {
        if (this.assetBrowser != null)
        {
            this.assetBrowser.removeFromParent();
            this.assetBrowser = null;
        }

        this.assetBrowser = new UIAssetBrowser(
            () -> this.project == null ? CompStore.DEFAULT_PROJECT : this.project.id,
            (path, replace) ->
            {
                if (replace)
                {
                    this.replaceLayerImage(path);
                }
                else
                {
                    this.addImageLayer(path);
                }
            },
            this::importLibraryImage,
            this::importLibraryImagePath,
            this::importSoundId,
            () ->
            {
                if (this.assetBrowser != null)
                {
                    this.assetBrowser.setVisible(false);
                    this.assetBrowser.removeFromParent();
                    this.assetBrowser = null;
                }
            },
            replaceMode);

        this.assetBrowser.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.assetBrowser);
        this.resize();
    }

    /**
     * Copies a TTF/OTF into BBS's own fonts folder. The text renderer then loads it through
     * {@code BBSModClient.getFonts()}, so the glyph atlas, fallback and hot reload are all BBS's.
     *
     * <p>Uses the in-game browser like every other importer: the OS dialog is a native window that
     * may never come back with a result inside a fullscreen game (see {@link UIImportBrowser}).</p>
     */
    private void importFont(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        NativeFileDialog.pickFile(
            "导入字体",
            FontImport.EXTENSIONS,
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importFontFile(layer, file);
                }
            },
            () -> this.openFontBrowser(layer)
        );
    }

    /** Fallback path: the in-game browser, used when the OS dialog cannot be shown. */
    private void openFontBrowser(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        if (this.importBrowser != null)
        {
            this.closeImportBrowser();
        }

        this.importBrowser = new UIImportBrowser(
            "导入字体",
            FontImport.EXTENSIONS,
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importFontFile(layer, file);
                }

                this.closeImportBrowser();
            },
            this::closeImportBrowser
        );

        this.importBrowser.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.importBrowser);
        this.resize();
    }

    /**
     * Lists the operating system's TTF/OTF files and imports the chosen one through the same
     * path every other font uses. The fonts are not copied until they are chosen: a machine can
     * have hundreds of installed fonts, and BBS' loader only accepts links inside its own fonts
     * folder. Names come from the font's name table, so the list reads "Microsoft YaHei" rather
     * than "msyh".
     */
    private void openSystemFontPicker(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        java.util.List<SystemFonts.Entry> fonts = SystemFonts.list();

        if (fonts.isEmpty())
        {
            this.setStatus("没有找到系统 TTF/OTF/TTC 字体");
            return;
        }

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.Map<String, SystemFonts.Entry> byLabel = new java.util.LinkedHashMap<>();

        for (SystemFonts.Entry entry : fonts)
        {
            String label = entry.displayName;

            if (byLabel.containsKey(label))
            {
                label = label + " · " + entry.fileName();
            }

            int serial = 2;

            while (byLabel.containsKey(label))
            {
                label = entry.displayName + " (" + (serial++) + ")";
            }

            labels.add(label);
            byLabel.put(label, entry);
        }

        UIStringOverlayPanel panel = new UIStringOverlayPanel(IKey.constant("系统字体"), labels, (label) ->
        {
            SystemFonts.Entry entry = byLabel.get(label);

            if (entry != null)
            {
                this.importFontFile(layer, entry.path.toFile());
            }
        });

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void importFontFile(CompLayer layer, java.io.File file)
    {
        if (layer == null || file == null)
        {
            return;
        }

        try
        {
            long length = java.nio.file.Files.size(file.toPath());

            if (length <= 0 || length > FontImport.MAX_BYTES)
            {
                this.setStatus(length <= 0 ? "字体导入失败：文件为空" : "字体导入失败：文件超过 64 MB");
                return;
            }

            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());

            /* A .ttc is a container of faces, and BBS' loader only reads a single font at offset 0:
             * most Chinese fonts (微软雅黑, 宋体, 黑体…) ship that way, so "the imported font does not
             * display" was mostly this. Extract face 0 into a standalone .ttf first. */
            String requestedName = file.getName();
            boolean collection = FontImport.isCollection(data);

            if (collection)
            {
                try
                {
                    data = FontImport.extractFace(data, 0);
                }
                catch (java.io.IOException e)
                {
                    this.setStatus("字体导入失败：无法从字体集合中提取字体（" + e.getMessage() + "）");
                    return;
                }

                String stem = requestedName;
                int dot = stem.lastIndexOf('.');

                if (dot > 0)
                {
                    stem = stem.substring(0, dot);
                }

                requestedName = stem + "-face0.ttf";
            }

            String header = FontImport.headerError(data);

            if (header != null)
            {
                this.setStatus("字体导入失败：" + header);
                return;
            }

            java.io.File folder = mchorse.bbs_mod.fonts.FontManager.getFolder();

            if (folder == null)
            {
                this.setStatus("字体导入失败：BBS 资源目录尚未初始化");
                return;
            }

            java.nio.file.Files.createDirectories(folder.toPath());

            /* Keep the real (including Chinese) name and never overwrite a different font that
             * happens to share it; re-importing the same bytes reuses the existing copy. */
            java.nio.file.Path target = collection
                ? FontImport.uniqueTarget(folder.toPath(), requestedName, data)
                : FontImport.uniqueTarget(folder.toPath(), requestedName, file.toPath());

            if (collection)
            {
                java.nio.file.Files.write(target, data);
            }
            else if (!target.toAbsolutePath().equals(file.toPath().toAbsolutePath()))
            {
                java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String name = target.getFileName().toString();
            mchorse.bbs_mod.resources.Link link = mchorse.bbs_mod.resources.Link.assets("fonts/" + name);

            /* The same name may have been imported before; drop the cached atlas before probing
             * so the verification below actually reads the new bytes. */
            mchorse.bbs_mod.BBSModClient.getFonts().accept(target,
                mchorse.bbs_mod.utils.watchdog.WatchDogEvent.CREATED);

            /* Verify through BBS' own loader. An OTF with CFF outlines can pass the header check and
             * still fail stb; reporting that instead of silently selecting a font that renders as
             * the default is the point. */
            mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer loaded =
                mchorse.bbs_mod.BBSModClient.getFonts().get(link, 32, 1F);

            if (loaded == null)
            {
                this.setStatus("字体已复制到 " + folder.getName() + "/，但 BBS 无法解析（OTF/CFF 或损坏）；文件已保留");
                return;
            }

            layer.fontName = name;
            this.refreshInspector();
            this.save();

            /* Tell the user up front when the font cannot draw the layer's own text — otherwise the
             * import "does nothing" as far as they can see (the default font draws those chars). */
            java.util.List<Integer> missing = dev.bbsae.client.render.FontCoverage.missingGlyphs(
                target.toFile(), layer.text == null ? "" : layer.text);

            if (!missing.isEmpty())
            {
                this.setStatus("已导入字体：" + name + "；但该字体缺少当前文字里的 " + missing.size()
                    + " 个字符（例如汉字），这些字符会自动用默认字体显示。若要整体使用该字体，请导入包含这些字符的字体。");
            }
            else
            {
                this.setStatus("已导入字体：" + name + "（BBS 字体目录）");
            }
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Could not import font {}", file, e);
            this.setStatus("字体导入失败：" + e.getMessage());
        }
    }

    /** Video import: pick a file, then extract frames into a footage layer. */
    private void importVideo()
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        if (dev.bbsae.client.export.CompVideoImport.isRunning())
        {
            this.setStatus("已经有一个视频正在导入，请等它完成");

            return;
        }

        NativeFileDialog.pickFile(
            "导入视频",
            new String[] {"mp4", "mov", "mkv", "webm", "avi", "m4v"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importVideoFile(file);
                }
            },
            this::openVideoBrowser
        );
    }

    /** Fallback path: the in-game browser, used when the OS dialog cannot be shown. */
    private void openVideoBrowser()
    {
        if (this.importBrowser != null)
        {
            this.closeImportBrowser();
        }

        this.importBrowser = new UIImportBrowser(
            "导入视频（拆帧为 footage 轨道）",
            new String[] {"mp4", "mov", "mkv", "webm", "avi", "m4v"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importVideoFile(file);
                }

                this.closeImportBrowser();
            },
            this::closeImportBrowser
        );

        this.importBrowser.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.importBrowser);
        this.resize();
    }

    /**
     * Extracts frames on a worker thread and creates the footage layer on the client thread.
     *
     * <p>The layer is only created if the same composition is still open: the extraction can take
     * tens of seconds, and dropping a clip into a project the user has since switched away from is
     * worse than telling them it was cancelled.</p>
     */
    private void importVideoFile(java.io.File file)
    {
        final CompDocument target = this.doc;
        final CompProject project = this.project;

        this.setStatus("正在用 FFmpeg 拆帧：" + file.getName() + "（最长 "
            + Math.round(dev.bbsae.client.export.CompVideoImport.MAX_SECONDS) + " 秒）");

        dev.bbsae.client.export.CompVideoImport.start(project, target, file,
            new dev.bbsae.client.export.CompVideoImport.Callback()
            {
                @Override
                public void done(String pattern, int frames, int width, int height, float fps)
                {
                    net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                    {
                        if (UICompositorPanel.this.doc != target || UICompositorPanel.this.project != project)
                        {
                            UICompositorPanel.this.setStatus("视频已拆帧，但工程已切换，未创建轨道：" + pattern);

                            return;
                        }

                        CompLayer layer = new CompLayer("footage", file.getName(), "footage");

                        layer.imagePath = pattern;
                        layer.width = width;
                        layer.height = height;
                        layer.x = target.width / 2F;
                        layer.y = target.height / 2F;
                        layer.in = 0F;
                        layer.out = Math.min(target.duration, Math.max(1F / fps, frames / fps));
                        layer.imageFit = 0;
                        layer.name = file.getName();

                        target.addLayer(layer);

                        dev.bbsae.BbsAeAddon.LOGGER.info("video import: {} frames @ {} fps -> out {}s ({}x{})",
                            frames, Math.round(fps), layer.out, width, height);
                        UICompositorPanel.this.selectedIds.clear();
                        UICompositorPanel.this.selectedIds.add(layer.id);
                        UICompositorPanel.this.selectedLayer = layer;
                        UICompositorPanel.this.selectedAudio = null;
                        UICompositorPanel.this.refreshLayers();
                        UICompositorPanel.this.refreshInspector();
                        UICompositorPanel.this.save();
                        UICompositorPanel.this.setStatus("视频已导入：" + frames + " 帧 · " + width + "×" + height
                            + " · " + Math.round(fps) + "fps → 已创建 footage 轨道");
                    });
                }

                @Override
                public void failed(String message)
                {
                    net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                        UICompositorPanel.this.notifyError("视频导入失败：" + message));
                }
            });
    }

    private void importLibraryImage()
    {
        if (this.project == null)
        {
            return;
        }

        NativeFileDialog.pickFile(
            "导入图片",
            new String[] {"png", "jpg", "jpeg", "gif"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importLibraryFile(file);
                }
            },
            this::openLibraryImageBrowser
        );
    }

    /** Fallback path: the in-game browser, used when the OS dialog cannot be shown. */
    private void openLibraryImageBrowser()
    {
        if (this.importBrowser != null)
        {
            this.closeImportBrowser();
        }

        this.importBrowser = new UIImportBrowser(
            "导入图片",
            new String[] {"png", "jpg", "jpeg", "gif"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importLibraryFile(file);
                }

                this.closeImportBrowser();
            },
            this::closeImportBrowser
        );

        this.importBrowser.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.importBrowser);
        this.resize();
    }

    private void closeImportBrowser()
    {
        UIImportBrowser browser = this.importBrowser;

        if (browser == null || browser.isClosing())
        {
            return;
        }

        /* Same close-fade contract as the effect library: removal happens in the callback after the
         * frame is rendered. */
        browser.beginClose(() ->
        {
            browser.setVisible(false);
            browser.removeFromParent();

            if (this.importBrowser == browser)
            {
                this.importBrowser = null;
            }
        });
    }

    /**
     * Imports from a typed path.
     *
     * <p>The native dialog is the nice path, but it is also a black box: on a system where
     * tinyfd cannot open a window it returns nothing and the callback never fires, so the button
     * looks dead. This gives the user a path that always works, and it is also the only way to
     * import when the game runs in a sandbox without a desktop portal.</p>
     */
    private void importLibraryImagePath(String path)
    {
        if (this.project == null || path == null || path.isBlank())
        {
            return;
        }

        java.io.File file = new java.io.File(path.trim());

        if (!file.isFile())
        {
            this.notifyError("找不到文件：" + path);

            return;
        }

        this.importLibraryFile(file);
    }

    private void importLibraryFile(java.io.File file)
    {
        try
        {
            if (this.project == null)
            {
                return;
            }

            String name = this.copyIntoProjectImages(file);

            if (this.assetBrowser != null)
            {
                this.assetBrowser.refresh();
                /* Show the file that was just imported, selected. Without this the library simply
                 * re-listed itself and the new picture was one anonymous tile among hundreds. */
                this.assetBrowser.selectPath("assets/images/" + name);
            }

            this.setStatus("已导入：" + name + "（已在素材库选中，双击插入）");
            this.save();
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Could not import {}", file, e);
            this.notifyError("导入失败：" + e.getMessage());
        }
    }

    /**
     * Copies a file into {@code assets/images} and returns the stored file name.
     *
     * <p>One copy path for every importer (library, layer, audio): the asset browser, the layer
     * inspector and the render queue all resolve "images/name" through
     * {@link dev.bbsae.client.render.ImageTextureCache}, so a second naming scheme would produce
     * files that exist but never load.</p>
     */
    private String copyIntoProjectImages(java.io.File file) throws java.io.IOException
    {
        java.nio.file.Path folder = CompStore.assetRoot(this.project.id).resolve("images");

        java.nio.file.Files.createDirectories(folder);

        String name = file.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        java.nio.file.Path target = folder.resolve(name);

        if (!target.toAbsolutePath().equals(file.toPath().toAbsolutePath()))
        {
            java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        return name;
    }

    private void addImageLayer(String path)
    {
        if (this.doc == null)
        {
            return;
        }

        CompLayer layer = new CompLayer("image", "图片", "image");

        layer.imagePath = path;
        layer.x = this.doc.width / 2F;
        layer.y = this.doc.height / 2F;
        layer.width = Math.min(480F, this.doc.width * 0.35F);
        layer.height = layer.width * 0.5625F;
        layer.out = this.doc.duration;
        layer.imageFit = 0;

        this.doc.addLayer(layer);
        this.setSingleSelection(layer);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    private void replaceLayerImage(String path)
    {
        if (this.selectedLayer == null || !"image".equals(this.selectedLayer.type) && !"footage".equals(this.selectedLayer.type))
        {
            this.notifyError("替换需要先选中图片/序列图层；当前是 "
                + (this.selectedLayer == null ? "未选中" : this.selectedLayer.type)
                + "，可改用“插入为新图层”");

            return;
        }

        this.selectedLayer.imagePath = path;
        this.refreshInspector();
        this.save();
    }

    private String replayLabel(mchorse.bbs_mod.film.replays.Replay replay)
    {
        String name = replay.getName();

        if (name == null || name.isBlank() || "-".equals(name.trim()))
        {
            int index = this.film == null ? 0 : this.film.replays.getList().indexOf(replay);

            return "回放 " + (index + 1);
        }

        return name;
    }

    private void showReplayPicker()
    {
        if (this.film == null)
        {
            return;
        }

        this.openModal("选择回放", "先选择要编辑的回放，再进入合成。");

        List<mchorse.bbs_mod.film.replays.Replay> replays = this.film.replays.getList();
        int y = 56;

        for (mchorse.bbs_mod.film.replays.Replay replay : replays)
        {
            final mchorse.bbs_mod.film.replays.Replay target = replay;
            UIButton button = this.modalButton(this.replayLabel(replay), 16, y, 408, () ->
            {
                this.closeModal();
                this.selectReplay(target);
            });

            this.modalBox.add(button);
            y += 26;
        }

        if (replays.isEmpty())
        {
            UILabel empty = new UILabel(IKey.constant("这个影片还没有回放，请先在 BBS 影片编辑器里添加回放。"));
            empty.relative(this.modalBox).x(16).y(60).w(1F, -32).h(18);
            this.modalBox.add(empty);
        }

        this.modalBox.add(this.modalButton("打开其他影片…", 16, 234, 200, () ->
        {
            this.closeModal();
            this.openReplaySelector();
        }));
        this.modalBox.add(this.modalButton("返回影片编辑器", 232, 234, 192, () ->
        {
            this.closeModal();

            if (this.filmPanel != null)
            {
                this.dashboard.setPanel(this.filmPanel);
                mchorse.bbs_mod.ui.framework.UIScreen.open(this.dashboard);
            }
        }));
    }

    private void showExportDialog()
    {
        int width = Math.max(64, mchorse.bbs_mod.BBSSettings.videoWidth.get());
        int height = Math.max(64, mchorse.bbs_mod.BBSSettings.videoHeight.get());

        /* Low BBS defaults (e.g. 320x180 preview size) are what made exports look blurry.
         * Start from at least 1080p and let the user step down. */
        if (width < 1280 || height < 720)
        {
            width = 1920;
            height = 1080;
        }

        this.exportWidth = width;
        this.exportHeight = height;
        this.exportFps = Math.max(1, mchorse.bbs_mod.BBSSettings.videoFrameRate.get());

        if (this.exportFps < 24)
        {
            this.exportFps = 30;
        }

        this.buildExportDialog();
    }

    private void buildExportDialog()
    {
        this.openModalSized("导出视频", "使用 BBS 原生录像器；宽、高、比例和帧率都会写入 BBS 设置。", 540, 400);

        int[] widths = {1280, 1920, 2560, 3840};

        for (int i = 0; i < widths.length; i++)
        {
            final int width = widths[i];
            this.modalBox.add(this.modalButton(String.valueOf(width), 110 + i * 96, 58, 88, () ->
            {
                this.exportWidth = width;
                this.exportHeight = Math.max(2, Math.round(width * 9F / 16F));
                this.buildExportDialog();
            }));
        }

        UILabel resolutionLabel = new UILabel(IKey.constant("常用宽度"));
        resolutionLabel.relative(this.modalBox).x(16).y(60).w(88).h(18);
        this.modalBox.add(resolutionLabel);

        String[] aspects = {"16:9", "9:16", "1:1", "4:3", "21:9", "2.35:1"};
        float[] ratios = {9F / 16F, 16F / 9F, 1F, 3F / 4F, 9F / 21F, 1F / 2.35F};

        for (int i = 0; i < aspects.length; i++)
        {
            final float ratio = ratios[i];
            this.modalBox.add(this.modalButton(aspects[i], 110 + (i % 3) * 96, 86 + (i / 3) * 26, 88, () ->
            {
                /* Keep the long edge, derive the short edge from the chosen ratio. */
                int base = Math.max(this.exportWidth, this.exportHeight);

                if (ratio <= 1F)
                {
                    this.exportWidth = base;
                    this.exportHeight = Math.max(2, Math.round(base * ratio));
                }
                else
                {
                    this.exportHeight = base;
                    this.exportWidth = Math.max(2, Math.round(base / ratio));
                }

                this.buildExportDialog();
            }));
        }

        UILabel aspectLabel = new UILabel(IKey.constant("画面比例"));
        aspectLabel.relative(this.modalBox).x(16).y(88).w(88).h(18);
        this.modalBox.add(aspectLabel);

        int[] rates = {24, 30, 60, 120};

        for (int i = 0; i < rates.length; i++)
        {
            final int fps = rates[i];
            this.modalBox.add(this.modalButton(fps + " fps", 110 + i * 96, 146, 88, () ->
            {
                this.exportFps = fps;
                this.buildExportDialog();
            }));
        }

        UILabel fpsLabel = new UILabel(IKey.constant("帧率"));
        fpsLabel.relative(this.modalBox).x(16).y(148).w(88).h(18);
        this.modalBox.add(fpsLabel);

        /* Custom numeric size + ratio: the two things a fixed preset list always gets wrong. */
        UILabel customLabel = new UILabel(IKey.constant("自定义"));
        customLabel.relative(this.modalBox).x(16).y(186).w(88).h(18);

        UITrackpad customWidth = new UITrackpad((value) ->
        {
            this.exportWidth = Math.max(16, Math.min(7680, value.intValue()));
        });

        customWidth.limit(16, 7680).integer().setValue(this.exportWidth);
        customWidth.relative(this.modalBox).x(110).y(184).w(96).h(20);
        customWidth.tooltip(IKey.constant("导出宽度（像素）"));

        UITrackpad customHeight = new UITrackpad((value) ->
        {
            this.exportHeight = Math.max(16, Math.min(4320, value.intValue()));
        });

        customHeight.limit(16, 4320).integer().setValue(this.exportHeight);
        customHeight.relative(this.modalBox).x(216).y(184).w(96).h(20);
        customHeight.tooltip(IKey.constant("导出高度（像素）"));

        UILabel times = new UILabel(IKey.constant("×"));
        times.relative(this.modalBox).x(198).y(186).w(16).h(18);

        float currentRatio = this.exportWidth / (float) Math.max(1, this.exportHeight);
        UITrackpad customRatio = new UITrackpad();
        customRatio.setValue(currentRatio);
        customRatio.relative(this.modalBox).x(330).y(184).w(78).h(20);
        customRatio.tooltip(IKey.constant("比例 = 宽 / 高，例如 2.35 表示 2.35:1"));

        UIButton applyRatio = new UICompButton(IKey.constant("应用比例"), (button) ->
        {
            double ratio = customRatio.getValue();

            if (ratio <= 0.01D)
            {
                return;
            }

            this.exportHeight = Math.max(16, Math.min(4320, (int) Math.round(this.exportWidth / ratio)));

            this.buildExportDialog();
        });

        applyRatio.relative(this.modalBox).x(416).y(184).w(104).h(20);
        applyRatio.tooltip(IKey.constant("按当前宽度重算高度"));

        UILabel summary = new UILabel(IKey.constant("当前：" + this.exportWidth + " × " + this.exportHeight + "   "
            + this.exportFps + " fps   " + String.format(java.util.Locale.ROOT, "%.3f:1", currentRatio)));
        summary.color(0xFFB8C4D6, false);
        summary.relative(this.modalBox).x(16).y(216).w(1F, -32).h(18);

        UILabel warning = new UILabel(IKey.constant("导出由 BBS 录像器执行；1080p60 需要客户端能稳定跑满 60 fps。"));
        warning.color(0xFF8A94A6, false);
        warning.relative(this.modalBox).x(16).y(238).w(1F, -32).h(18);

        if (this.project != null && this.project.settings == null)
        {
            this.project.settings = new CompProject.CompSettings();
        }

        CompProject.CompSettings settings = this.project == null ? new CompProject.CompSettings() : this.project.settings;
        float compositionDuration = this.doc == null ? 12F : Math.max(0.1F, this.doc.duration);
        float exportDuration = settings.exportDuration > 0F ? settings.exportDuration : compositionDuration;

        UILabel durationLabel = new UILabel(IKey.constant("导出时长（秒）"));
        durationLabel.relative(this.modalBox).x(16).y(262).w(116).h(18);

        UITrackpad duration = new UITrackpad((value) ->
        {
            if (this.project == null)
            {
                return;
            }

            this.project.settings.exportDuration = Math.max(0F, Math.min(3600F, value.floatValue()));

            if (this.project.settings.exportDuration > 0F)
            {
                this.adoptCompositionDuration(this.project.settings.exportDuration);
            }

            this.save();
        });
        duration.limit(0F, 3600F).setValue(exportDuration);
        duration.relative(this.modalBox).x(136).y(260).w(100).h(20);
        duration.tooltip(IKey.constant("直接传给 BBS 原生录像器的秒数；不再受相机/loop 限制"));
        this.exportDurationField = duration;

        UIButton useComposition = new UICompButton(IKey.constant("使用合成时长"), (button) ->
        {
            if (this.project != null)
            {
                this.project.settings.exportDuration = 0F;
                this.save();
            }

            this.buildExportDialog();
        });
        useComposition.relative(this.modalBox).x(244).y(260).w(124).h(20);
        useComposition.tooltip(IKey.constant("把导出时长重置为当前 composition 的 duration"));

        UILabel durationHint = new UILabel(IKey.constant("20s / 25s / 45s 直接填；0 即合成时长。"));
        durationHint.color(0xFF8A94A6, false);
        durationHint.relative(this.modalBox).x(376).y(264).w(148).h(16);

        UIButton syncTimeline = new UICompButton(IKey.constant("同步到 BBS 时间轴（原版录制按钮也用这个长度）"), (button) ->
        {
            if (this.doc == null || this.film == null || this.filmPanel == null)
            {
                return;
            }

            float seconds = settings.exportDuration > 0F ? settings.exportDuration : this.doc.duration;

            this.doc.duration = Math.max(0.1F, seconds);
            this.doc.workAreaOut = this.doc.duration;
            this.syncBbsLoopToDocument();
            CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id, this.doc.duration);
            this.filmPanel.forceSave();
            this.save();
            this.refreshSheets();
            this.setStatus("已同步 BBS 时间轴：" + String.format(java.util.Locale.ROOT, "%.2fs", this.doc.duration));
        });
        syncTimeline.relative(this.modalBox).x(16).y(286).w(1F, -32).h(20);
        syncTimeline.tooltip(IKey.constant("把导出时长写入 composition 和 BBS composition clip；然后 BBS 原版导出也一致"));

        this.modalBox.add(customLabel, times, customWidth, customHeight, customRatio, applyRatio, summary, warning,
            durationLabel, duration, useComposition, durationHint, syncTimeline);
        this.modalBox.add(this.modalButton("取消", 16, 350, 240, this::closeModal));
        this.modalBox.add(this.modalButton("开始导出", 268, 350, 256, this::startBbsExport));
    }

    private void startBbsExport()
    {
        if (this.filmPanel == null || this.filmPanel.getData() == null)
        {
            this.closeModal();
            this.setStatus("导出需要先打开一个影片；独立工程请用渲染队列或 BBS 影片编辑器导出");

            return;
        }

        mchorse.bbs_mod.BBSSettings.videoWidth.set(this.exportWidth);
        mchorse.bbs_mod.BBSSettings.videoHeight.set(this.exportHeight);
        mchorse.bbs_mod.BBSSettings.videoFrameRate.set(this.exportFps);

        mchorse.bbs_mod.ui.film.UIFilmPanel.applyExportSizeToBBS();

        this.save();

        /* Read the live trackpad first. A user who types 40 and immediately clicks "Start" must
         * not lose the value if the field has not fired its blur/commit callback yet. */
        float exportSeconds = this.exportDurationField == null
            ? 0F
            : (float) this.exportDurationField.getValue();

        if (exportSeconds <= 0F && this.project != null && this.project.settings != null
            && this.project.settings.exportDuration > 0F)
        {
            exportSeconds = this.project.settings.exportDuration;
        }

        if (exportSeconds <= 0F)
        {
            exportSeconds = this.doc == null ? 0F : this.doc.duration;
        }

        if (exportSeconds <= 0F)
        {
            this.closeModal();
            this.setStatus("导出时长必须大于 0；可在导出对话框里设置或使用合成时长");

            return;
        }

        this.adoptCompositionDuration(exportSeconds);

        /* Make the physical film agree with this panel before BBS starts recording. A clip
         * trimmed to a few seconds used to leave the rest of the export as the raw BBS frame,
         * even though the compositor preview showed the composition the whole time. */
        mchorse.bbs_mod.film.Film exportFilm = this.filmPanel.getData();
        String projectId = this.project == null ? null : this.project.id;
        String compId = this.doc == null ? null : this.doc.id;
        CompositionClip exportClip = projectId == null || compId == null
            ? null
            : CompositionClip.prepareForExport(exportFilm, projectId, compId, exportSeconds);

        if (exportClip != null)
        {
            this.filmPanel.forceSave();
        }

        BbsAeAddon.LOGGER.info(
            "Compositor export: film={} panelFilm={} project={} comp={} clip={} clipTick={} clipDuration={}",
            this.film == null ? "-" : this.film.getId(),
            exportFilm == null ? "-" : exportFilm.getId(),
            projectId, compId,
            exportClip == null ? "missing" : "ok",
            exportClip == null ? -1 : exportClip.tick.get(),
            exportClip == null ? -1 : exportClip.duration.get()
        );

        /* The composition duration is the authority for a compositor export. BBS's own record
         * button derives its end from camera.calculateDuration(), and the film panel's loop range
         * can shorten that further (the reported "stops at 20 seconds" was the loop/camera
         * duration, not the composition). Start the same native VideoRecorder session directly
         * with the composition's tick count instead of patching a larger camera clip. */
        int exportTicks = Math.max(20, Math.round(exportSeconds * 20F) + 1);

        this.syncBbsLoop(exportTicks);

        final boolean previousLoop = mchorse.bbs_mod.BBSSettings.editorLoop.get();

        mchorse.bbs_mod.BBSSettings.editorLoop.set(false);
        this.filmPanel.recorder.setFinishedListener((cancelled) ->
            mchorse.bbs_mod.BBSSettings.editorLoop.set(previousLoop));

        BbsAeAddon.LOGGER.info("Compositor export duration: {} ticks ({}s), loop disabled for export",
            exportTicks, String.format(java.util.Locale.ROOT, "%.3f", exportTicks / 20F));

        this.closeModal();

        this.dashboard.setPanel(this.filmPanel);
        mchorse.bbs_mod.ui.framework.UIScreen.open(this.dashboard);

        mchorse.bbs_mod.ui.film.UIFilmPanel.applyExportSizeToBBS();
        mchorse.bbs_mod.client.BBSRendering.scheduleAfterNextExportFrame(() ->
        {
            mchorse.bbs_mod.graphics.texture.Texture texture = mchorse.bbs_mod.client.BBSRendering.getTexture();

            this.filmPanel.recorder.startRecording(
                exportTicks,
                texture.id,
                mchorse.bbs_mod.client.BBSRendering.getVideoWidth(),
                mchorse.bbs_mod.client.BBSRendering.getVideoHeight()
            );
        });
    }

    private void cycleReplay()
    {
        if (this.film == null || this.film.replays.getList().isEmpty())
        {
            return;
        }

        List<mchorse.bbs_mod.film.replays.Replay> replays = this.film.replays.getList();
        int index = replays.indexOf(this.replay);

        this.selectReplay(replays.get((index + 1 + replays.size()) % replays.size()));
    }

    private void openReplaySelector()
    {
        List<String> values = new ArrayList<>();

        if (this.film != null)
        {
            for (mchorse.bbs_mod.film.replays.Replay replay : this.film.replays.getList())
            {
                values.add("Replay · " + replay.getName());
            }
        }

        for (String filmId : this.filmIds)
        {
            if (this.film == null || !filmId.equals(this.film.getId()))
            {
                values.add("Film · " + filmId);
            }
        }

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("回放 / 影片"), (value) ->
        {
            if (value.startsWith("Replay · ") && this.film != null)
            {
                String name = value.substring("Replay · ".length());

                for (mchorse.bbs_mod.film.replays.Replay replay : this.film.replays.getList())
                {
                    if (replay.getName().equals(name))
                    {
                        this.selectReplay(replay);
                        return;
                    }
                }
            }
            else if (value.startsWith("Film · "))
            {
                this.loadFilm(value.substring("Film · ".length()));
            }
        });

        panel.addValues(values);
        UIOverlay.addOverlay(this.getContext(), panel, 0.45F, 0.65F);
    }

    private void replayContextMenu(ContextMenuManager manager)
    {
        if (this.film == null)
        {
            return;
        }

        for (mchorse.bbs_mod.film.replays.Replay replay : this.film.replays.getList())
        {
            manager.action(Icons.SCENE, IKey.constant(replay.getName()), () -> this.selectReplay(replay));
        }

        for (String filmId : this.filmIds)
        {
            if (this.film != null && filmId.equals(this.film.getId()))
            {
                continue;
            }

            manager.action(Icons.FILM, IKey.constant("影片：" + filmId), () -> this.loadFilm(filmId));
        }
    }

    private void loadFilm(String filmId)
    {
        if (this.filmPanel == null)
        {
            return;
        }

        mchorse.bbs_mod.ui.ContentType.FILMS.getRepository().load(filmId, (data) ->
        {
            if (!(data instanceof mchorse.bbs_mod.film.Film loaded))
            {
                return;
            }

            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
            {
                this.filmPanel.fill(loaded);
                dev.bbsae.client.BbsAeClient.openCompositorForFilm(this.filmPanel, loaded, null);
            });
        });
    }

    public void selectReplay(mchorse.bbs_mod.film.replays.Replay replay)
    {
        this.save();
        this.replay = replay;

        if (this.project != null && replay != null)
        {
            this.precompStack.clear();

            if (this.precompBack != null)
            {
                this.precompBack.setVisible(false);
            }

            int width = Math.max(1, mchorse.bbs_mod.BBSSettings.videoWidth.get());
            int height = Math.max(1, mchorse.bbs_mod.BBSSettings.videoHeight.get());
            float fps = Math.max(1F, mchorse.bbs_mod.BBSSettings.videoFrameRate.get());
            float duration = Math.max(1F, this.film == null ? 12F : this.film.calculateDuration() / 20F);
            String replayKey = replay.getId() == null || replay.getId().isBlank() ? replay.getName() : replay.getId();

            this.doc = CompStore.ensureReplayComp(this.project, replayKey, replay.getName(), width, height, fps, duration);
            this.doc.duration = Math.max(this.doc.duration, duration);
            this.doc.workAreaOut = Math.max(this.doc.workAreaOut, duration);
            normalizeWorkArea(this.doc);
            this.time = 0F;
            this.setSingleSelection(this.doc.layers.isEmpty() ? null : this.doc.layers.get(this.doc.layers.size() - 1));
            this.resetTimelineView = true;

            if (this.trackTimeline != null)
            {
                this.trackTimeline.fit();
            }

            if (this.film != null)
            {
                CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id, this.doc.duration);
                this.syncBbsLoopToDocument();
            }

            this.refreshLayers();
            this.refreshInspector();
            this.refreshSheets();
            this.updateCompPath();
        }

        this.replaySelector.label = IKey.constant("回放：" + (replay == null ? "-" : this.replayLabel(replay)));
        this.save();
    }

    private void refreshComp(String compId)
    {
        if (this.project == null)
        {
            return;
        }

        CompDocument next = this.project.getComp(compId);

        if (next == null)
        {
            return;
        }

        this.doc = next;
        this.time = 0F;
        this.playing = false;
        this.playRate = 0F;
        this.resetTimelineView = true;

        /* A different composition means different layers; the pen's target is gone. */
        if (this.penTool != null)
        {
            this.penTool.forget();
        }
        this.setSingleSelection(this.doc.layers.isEmpty() ? null : this.doc.layers.get(this.doc.layers.size() - 1));

        if (this.trackTimeline != null)
        {
            this.trackTimeline.fit();
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.updatePlayLabel();
        this.applyTimelinePage();
    }

    private void refreshLayers()
    {
        if (this.doc == null)
        {
            this.selectedLayer = null;
            this.selectedIds.clear();
        }
        else
        {
            if (this.selectedLayer != null && !this.doc.layers.contains(this.selectedLayer))
            {
                this.selectedLayer = this.doc.layers.isEmpty() ? null : this.doc.layers.get(this.doc.layers.size() - 1);
            }

            /* The set is the multi-select truth; ids pointing at layers that no longer exist in
             * the CURRENT document made the batch gates ("多选 · N 个图层") fire inside a single
             * selection after entering a precomp or switching replay. */
            this.selectedIds.removeIf((id) -> this.doc.getLayer(id) == null);

            if (this.selectedIds.isEmpty() && this.selectedLayer != null)
            {
                this.selectedIds.add(this.selectedLayer.id);
            }
        }

        if (this.trackTimeline != null)
        {
            this.trackTimeline.refresh();
        }

        if (this.effectLibrary != null)
        {
            this.effectLibrary.refreshStatus();
        }
    }

    /**
     * Marks the project as changed and schedules a write.
     *
     * <p><b>This used to write the whole project to disk.</b> With 121 call sites — every slider
     * tick, every wheel notch, every drag delta — that meant a full Gson serialization, a
     * {@code .bak} copy, a temp-file write and an atomic move <em>per mouse event</em>. It is the
     * single largest cause of the editor's stutter, and it is completely unnecessary: a project
     * file is a snapshot, not a transaction log.</p>
     *
     * <p>Writes are now debounced ({@link #SAVE_DEBOUNCE_MS}) and only happen when the mouse is
     * released, so a drag produces exactly one file write instead of hundreds.</p>
     */
    void save()
    {
        this.documentDirty = true;
        this.saveRequested = true;
        this.saveRequestedMs = System.currentTimeMillis();

        /* Green frames are only trustworthy until the next edit. */
        dev.bbsae.client.render.CompPrecache.markDirty();
    }

    /**
     * Save button / Ctrl+S: writes now and reports the real result. The old handler marked the
     * project dirty, printed "saved" and left the actual write to the 600 ms debounce (which can
     * itself be deferred while the mouse is down) — a read-only file or a full disk looked like a
     * successful save until much later.
     */
    public boolean saveNow()
    {
        if (this.project == null)
        {
            return false;
        }

        this.save();
        this.flushSave();

        String error = CompStore.lastSaveError();

        if (error == null || error.isBlank())
        {
            this.setStatus("工程已保存");

            return true;
        }

        String message = "保存失败：" + error;

        this.notifyError(message);
        this.setStatus(message);

        return false;
    }

    /** CLIENT_STOPPING flush: writes pending document + view state without closing the panel. */
    public void flushNow()
    {
        if (this.project != null)
        {
            this.savePanelLayout();
            this.save();
            this.flushSave();
        }

        CompPrefs.flush();
    }

    /**
     * Persists UI-only state (dock layout, panel sizes, editor modes) without marking the document
     * edited.
     *
     * <p>Panel sizes and the dock tree live in the project file for convenience, but they are not
     * composition data: dragging a splitter must not create an undo step or invalidate the
     * pre-rendered frames. {@link #save()} is for content edits; this is for the workspace.</p>
     */
    void saveUiState()
    {
        this.saveRequested = true;
        this.saveRequestedMs = System.currentTimeMillis();
    }

    /** Writes the current project immediately before switching away from it. */
    private void flushProjectBeforeSwitch()
    {
        if (this.project == null)
        {
            return;
        }

        /* The selection / drawer / timeline view are workspace state; without this they were
         * lost when switching projects (only panel close saved them). */
        this.savePanelLayout();
        this.save();
        this.flushSave();

        /* An export keeps reading this project's live document each frame; switching projects
         * would leave it writing frames of a project the user can no longer see (audit P1). */
        if (CompExportManager.isBusy())
        {
            CompExportManager.cancel();
            this.setStatus("已取消正在进行的导出（切换工程）");
        }
    }

    /** Writes now. Called on panel close, before export, and by the debounce timer. */
    void flushSave()
    {
        if (!this.saveRequested)
        {
            return;
        }

        this.saveRequested = false;

        if (this.dockSource != null)
        {
            this.dockSource.flush();
        }

        /* Content and view state live in the same file: refresh the view fields whenever the file
         * is about to hit disk, otherwise a crash/undo after a selection change wrote the old
         * selection back (the fields were only filled by savePanelLayout on close). */
        this.writeEditorViewState();

        if (this.project != null && !CompStore.saveChecked(this.project))
        {
            /* Writing the file can fail (disk full, read-only, locked). "Saved" was previously
             * printed unconditionally, so the user kept editing into a file that never changed. */
            String error = CompStore.lastSaveError();
            String message = "保存失败：" + (error == null || error.isBlank() ? "未知原因" : error);

            dev.bbsae.BbsAeAddon.LOGGER.error(message);
            this.notifyError(message);
            this.setStatus(message);
        }
    }

    /**
     * Copies the live selection / drawer / page / timeline view into the project settings.
     * Pure field assignment: callers decide when the project is written.
     */
    private void writeEditorViewState()
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        CompProject.CompSettings settings = this.project.settings;

        settings.keyframeDrawerOpen = this.keyframeOpenRequested;
        settings.selectedLayerIds = String.join(",", this.selectedIds);
        settings.selectedAudioId = this.selectedAudio != null && this.doc != null && this.doc.audio != null
            && this.doc.audio.contains(this.selectedAudio) ? this.selectedAudio.id : "";
        settings.inspectorPage = this.inspectorPage;
        settings.leftPage = this.leftPage;

        if (this.trackTimeline != null && this.trackTimeline.isViewReady())
        {
            double[] view = this.trackTimeline.viewState();

            settings.timelineShift = view[0];
            settings.timelineZoom = view[1];
            settings.timelineScrollY = this.trackTimeline.scrollY();
        }
    }

    /** Schedules a write when only workspace/view state changed. */
    private void markViewStateDirty()
    {
        if (!this.restoring && this.project != null)
        {
            this.saveUiState();
        }
    }

    /* Undo history is bounded by both step count and bytes. Keeping 50 multi-megabyte JSON strings
     * alive was enough garbage to make the JVM pause on its own. */

    private void pushUndo(String json)
    {
        if (json == null || json.isEmpty() || json.equals(this.undoStack.peek()))
        {
            return;
        }

        this.undoStack.push(json);
        this.undoChars += json.length();

        while (this.undoStack.size() > 50 || (this.undoChars > SNAPSHOT_BUDGET && this.undoStack.size() > 1))
        {
            this.undoChars -= this.undoStack.removeLast().length();
        }
    }

    private void pushRedo(String json)
    {
        this.redoStack.push(json);
        this.redoChars += json.length();

        while (this.redoStack.size() > 50 || (this.redoChars > SNAPSHOT_BUDGET && this.redoStack.size() > 1))
        {
            this.redoChars -= this.redoStack.removeLast().length();
        }
    }

    /**
     * Switches the bottom strip between the track timeline and the curve editor.
     *
     * <p>Both are views of the same animation data; only one is visible at a time so the strip
     * never shows two competing time axes.</p>
     */
    private void applyTimelinePage()
    {
        this.applyTimelinePage(false);
    }

    /**
     * @param grow true when the user just switched to the curve page: a graph squeezed into the
     *             224 px default strip is unusable, so the strip takes 55% of the panel once.
     */
    private void applyTimelinePage(boolean grow)
    {
        if (this.trackTimeline == null || this.curveEditor == null)
        {
            return;
        }

        boolean tracks = this.timelinePage == 0;

        this.trackTimeline.setVisible(tracks);
        this.curveEditor.setVisible(!tracks);

        for (UIElement tool : this.trackOnlyTools)
        {
            tool.setVisible(tracks);
        }

        if (!tracks)
        {
            this.curveEditor.refresh();

            /* In dock mode the timeline's height is a dock ratio; the user drags the splitter to make
             * the graph bigger, so the old "steal 55% of the panel" one-shot only exists in the
             * legacy fixed layout. */
            if (grow && this.dock == null && this.area.h > 0)
            {
                int max = Math.max(140, this.area.h - 24 - 160 - 18);
                int target = Math.min(max, Math.max(this.timelinePanelHeight, Math.round(this.area.h * 0.55F)));

                if (target > this.timelinePanelHeight)
                {
                    this.timelinePanelHeight = target;
                    this.applyLayout();
                }
            }
        }

        this.layoutKeyframeDrawer();

        /* Explicit resize: the curve editor must own a real rectangle before the next draw.
         * Relying on the panel's layout flush left it at 0×0 after a page switch, and a
         * zero-height clip silently erases the whole graph — the "blank curve editor" bug. */
        this.curveEditor.resize();
        this.trackTimeline.resize();
    }

    /**
     * Reconciles the bottom strip with {@link #timelinePage} once per frame.
     *
     * <p>Layout used to be event-driven only (tab click → apply). Anything that changed the strip
     * while the panel was off screen — a dashboard page switch, a project re-bind, a resize —
     * left the curve editor visible with a stale rectangle, which renders as an empty box. The
     * strip is a pure function of (page, visibility, area), so it is cheap to re-derive it.</p>
     */
    private void reconcileTimelinePage()
    {
        if (this.trackTimeline == null || this.curveEditor == null || this.timeline == null)
        {
            return;
        }

        this.tickKeyframeDrawerAnimation();

        boolean tracks = this.timelinePage == 0;

        if (this.curveEditor.isVisible() == tracks || this.trackTimeline.isVisible() != tracks)
        {
            this.applyTimelinePage();

            return;
        }

        if (tracks || this.curveEditor.area.h <= 0 || this.curveEditor.area.w <= 0)
        {
            this.layoutKeyframeDrawer();
            this.curveEditor.resize();

            /* A strip that cannot host a graph falls back instead of showing an empty box. */
            if (!tracks && this.area.h > 200 && this.curveEditor.area.h < 80)
            {
                this.timelinePage = 0;
                this.applyTimelinePage();
                this.setStatus("曲线区太窄，已切回时间轴；拖高时间轴后可再次打开曲线");
            }
        }
    }

    /** Undo checkpoint for one curve gesture (drag, ease preset, key insert). */
    void beginCurveAction()
    {
        this.beginAction("曲线编辑");
    }

    /**
     * Called once per curve gesture, after the edits landed.
     *
     * @param structural true when keys were added/removed or their type changed (the inspector's
     *                   widget set changes), false for a drag.
     *
     * <p>A drag used to rebuild the whole inspector and every keyframe sheet on mouse release.
     * That is 3–10 ms landing exactly on the frame the user lets go of the mouse — the "it jumps
     * when I release" symptom. A drag only changes values, and the inspector already has value
     * bindings for those, so re-running them is enough.</p>
     */
    void curveChanged(boolean structural)
    {
        this.rebindInspectorValues();

        if (structural)
        {
            this.refreshSheets();
            this.refreshInspector();
        }

        this.save();
    }

    /** Runs the inspector's value bindings without rebuilding any widget. */
    private void rebindInspectorValues()
    {
        for (Runnable binding : this.inspectorBindings)
        {
            try
            {
                binding.run();
            }
            catch (RuntimeException e)
            {
                dev.bbsae.BbsAeAddon.LOGGER.error("Could not refresh an inspector value", e);
            }
        }
    }

    /**
     * A curve gesture is mutating the document right now.
     *
     * <p>{@code save()} is the usual dirty hook, but a drag deliberately does not save on every
     * mouse move. Without this the pre-render cache keeps answering with the last green frame and
     * the preview freezes while the curve changes — a regression introduced when the cache was
     * put in front of the content signature.</p>
     */
    void markCurveDirty()
    {
        dev.bbsae.client.render.CompPrecache.markDirty();
    }

    /** Drops the preview to a cheaper resolution while a curve is being dragged. */
    void setCurveInteraction(boolean interacting)
    {
        dev.bbsae.client.render.CompRenderer.setInteractionPreview(interacting);
    }

    /** The edited segment, in seconds; the preview may reuse its frame outside this range. */
    void setCurveDirtyRange(float from, float to)
    {
        dev.bbsae.client.render.CompRenderCache.setTimeGuard(from, to);
    }

    void clearCurveDirtyRange()
    {
        dev.bbsae.client.render.CompRenderCache.clearTimeGuard();
    }

    /** Called after a preset is applied or saved. */
    void afterPresetChange()
    {
        if (this.presetBrowser != null)
        {
            this.presetBrowser.refresh();
        }

        this.refreshSheets();
        this.refreshInspector();
        this.save();
    }

    /**
     * Undo history is per project. Without this, opening another project left the previous
     * project's snapshots on the stack and Ctrl+Z could replace the new project with an old one.
     */
    private void resetHistory()
    {
        this.undoStack.clear();
        this.redoStack.clear();
        this.undoChars = 0L;
        this.redoChars = 0L;
        this.documentDirty = true;
        this.lastSnapshot = "";
        this.lastSnapshotMs = 0L;
        this.restoring = false;
        this.actionInProgress = false;
    }

    /** Temporary verification hook: a clean comp with BBS preview, a gradient shape and a null. */
    /**
     * Development-only: drives the panel into one of the states a screenshot has to prove.
     *
     * <p>Part of the same harness as {@link #debugDemoEffects()}: the compositor is an editor, so
     * "it compiles" says nothing about whether a curve is drawn or a button overlaps. These
     * states are reachable only through the mouse otherwise.</p>
     *
     * @param view {@code curve} (keyed layer + curve editor), {@code assets} (asset library),
     *             {@code camera} (3D space) or {@code queue} (render queue).
     */
    public void debugView(String view)
    {
        if (this.doc == null)
        {
            return;
        }

        if (view == null)
        {
            view = "curve";
        }

        /* A verification view must be visible immediately, even on a fresh config where the
         * first-run onboarding is still up. Debugging the camera through a welcome card is not a
         * useful screenshot. */
        if (!"onboarding".equals(view))
        {
            if (this.onboarding != null && this.onboarding.isOpen())
            {
                this.onboarding.close(true);
            }

            if (this.settingsOverlay != null && this.settingsOverlay.isOpen())
            {
                this.settingsOverlay.close();
            }
        }

        /* Verification hook: open the preview-bar filter dropdown so a screenshot shows the three
         * explicit options instead of only the folded chip. */
        if (Boolean.getBoolean("bbsae.debugFilterMenu"))
        {
            this.previewFilterExpanded = true;
            this.previewControlsCollapsed = false;

            if (this.project != null && this.project.settings != null)
            {
                this.project.settings.previewFilterExpanded = true;
                this.project.settings.previewControlsCollapsed = false;
            }

            this.applyPreviewBarState();
        }

        /* Verification hook: force the frame-server pipeline without walking the risk dialog. */
        if (Boolean.getBoolean("bbsae.debugEncoderAe"))
        {
            this.applyEncoder(CompProject.ENCODER_AE);

            /* Verification hooks for the preview audio path: add a clip and press play. */
            String audio = System.getProperty("bbsae.debugAudio", "");

            if (!audio.isBlank() && this.doc != null)
            {
                dev.bbsae.comp.CompAudio clip = new dev.bbsae.comp.CompAudio("debug_audio", "debug", audio);

                clip.start = 0F;
                this.doc.audio.add(clip);
                dev.bbsae.client.audio.CompAudioPlayer.invalidate();
                this.setStatus("调试音频已加入：" + audio);
            }

            float proxy = Float.parseFloat(System.getProperty("bbsae.debugProxy", "1"));

            if (proxy > 0F && proxy < 0.999F && this.project != null && this.project.settings != null)
            {
                this.project.settings.encoderProxy = Math.max(0.1F, Math.min(1F, proxy));
                dev.bbsae.client.render.CompFrameServer.invalidate();
                this.save();
            }
        }

        /* Verification hook: run one AE export through the raw-frame pipe and leave the file on
         * disk, so "the encoder path works" is checked by a real mp4 instead of by inspection. */
        if (Boolean.getBoolean("bbsae.debugAeExport"))
        {
            this.debugAeExport();
        }

        /* Verification hook: import a video file, so the frame extraction and the footage layer it
         * creates can be checked without a file dialog. */
        String video = System.getProperty("bbsae.debugVideoImport", "");

        if (!video.isBlank())
        {
            this.importVideoFile(new java.io.File(video));
        }

        /* Verification hook: jump to an inspector page so a screenshot can show the section
         * dividers on a page with several groups (the effect page only has one). */
        String debugPage = System.getProperty("bbsae.debugInspectorPage", "");

        if (!debugPage.isBlank())
        {
            switch (debugPage)
            {
                case "layer" -> this.setInspectorPage(PAGE_LAYER);
                case "mask" -> this.setInspectorPage(PAGE_MASK);
                case "text" -> this.setInspectorPage(PAGE_TEXT);
                case "comp" -> this.setInspectorPage(PAGE_COMP);
                case "output" -> this.setInspectorPage(PAGE_OUTPUT);
                default -> this.setInspectorPage(PAGE_EFFECTS);
            }
        }

        /* Verification hook: select a layer by (case-insensitive) name substring so screenshots can
         * show a page for that layer type without mouse input. */
        String debugLayer = System.getProperty("bbsae.debugSelectLayer", "");

        if (!debugLayer.isBlank() && this.doc != null)
        {
            for (CompLayer candidate : this.doc.layers)
            {
                String wanted = debugLayer.toLowerCase(java.util.Locale.ROOT);
                String layerName = candidate == null || candidate.name == null ? "" : candidate.name.toLowerCase(java.util.Locale.ROOT);
                String layerText = candidate == null || candidate.text == null ? "" : candidate.text.toLowerCase(java.util.Locale.ROOT);

                if (candidate != null && (layerName.contains(wanted) || layerText.contains(wanted)))
                {
                    this.selectLayer(candidate);

                    break;
                }
            }
        }

        String debugScroll = System.getProperty("bbsae.debugInspectorScroll", "");

        if (!debugScroll.isBlank())
        {
            try
            {
                this.inspector.scroll.setScroll(Double.parseDouble(debugScroll));
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        String debugProject = System.getProperty("bbsae.debugProject", "");

        if (!debugProject.isBlank() && !this.debugProjectLoaded)
        {
            this.debugProjectLoaded = true;
            this.debugOpenProject(debugProject, Boolean.getBoolean("bbsae.debugCameraPreview"));
        }

        if (Boolean.getBoolean("bbsae.debugOpenEffectLibrary"))
        {
            this.openEffectLibrary();
        }

        switch (view)
        {
            case "settings":
                this.openSettings("motion");
                return;

            case "settingskeys":
                this.openSettings("keys");
                return;

            case "settingslayout":
                this.openSettings("layout");
                return;

            case "settingsoverlay":
                this.openSettings("overlay");
                return;

            case "demo":
                /* No scene changes: verification runs use this to reach the pre-switch hooks
                 * (filter dropdown, debug shots) without opening the curve editor. */
                break;

            case "zerotest":
                this.debugZeroTest();
                return;

            case "cubetest":
                this.debugCubeTest();

                int keyframeRowHeight = Integer.getInteger("bbsae.keyframeRowHeight", 0);

                if (keyframeRowHeight > 0 && this.keyframeEditor.view instanceof UICompositorKeyframes keyframes)
                {
                    keyframes.debugSetTrackHeight(keyframeRowHeight);
                }

                int zoomSteps = Integer.getInteger("bbsae.keyframeZoomSteps", 0);

                for (int i = 0; i < Math.abs(zoomSteps); i++)
                {
                    this.zoomKeyframeTime(zoomSteps > 0 ? 1 : -1);
                }

                return;

            case "onboarding":
                this.openOnboarding();
                return;

            case "sponsor":
                this.openSettings("sponsor");
                return;

            case "cameraflight":
            {
                CompLayer camera = this.doc.activeCamera(this.time);

                if (camera == null)
                {
                    this.addLayer("camera");
                    camera = this.doc.activeCamera(this.time);
                }

                if (camera != null)
                {
                    this.selectLayer(camera);
                    this.applyCameraStyle(camera, CompLayer.CAMERA_BBS);
                    this.setCameraFlying(true);
                }

                return;
            }

            case "assets":
                this.openAssetBrowser(false);

                if (this.assetBrowser != null)
                {
                    int debugCategory = Integer.getInteger("bbsae.debugAssetCategory", -1);

                    if (debugCategory >= 0)
                    {
                        this.assetBrowser.debugShowCategory(debugCategory);
                    }

                    if (Boolean.getBoolean("bbsae.debugAssetBottom"))
                    {
                        this.assetBrowser.debugScrollToBottom();
                    }
                }

                break;

            case "camera":
                this.enter3DSpace();

                /* Verification only: give the camera a real move so the motion path has something
                 * to draw. Never runs during normal use (-Dbbsae.debugViewLock is a test flag). */
                if (Boolean.getBoolean("bbsae.debugViewLock"))
                {
                    CompLayer camera = this.doc.activeCamera(this.time);

                    if (camera != null && !camera.hasAnyTransformKey())
                    {
                        float end = Math.max(1F, this.doc.duration);
                        float[] xs = {this.doc.width * 0.5F, this.doc.width * 0.28F, this.doc.width * 0.72F, this.doc.width * 0.5F};
                        float[] ys = {this.doc.height * 0.62F, this.doc.height * 0.42F, this.doc.height * 0.5F, this.doc.height * 0.38F};

                        for (int i = 0; i < xs.length; i++)
                        {
                            mchorse.bbs_mod.utils.pose.Transform pose = camera.transformAt(0F).copy();

                            pose.translate.x = xs[i];
                            pose.translate.y = ys[i];
                            camera.insertTransformPose(Math.round(end * i / (xs.length - 1) * Math.max(1F, this.doc.fps)), pose);
                        }

                        this.refreshSheets();
                    }
                }

                break;

            case "gradtest":
            case "gradlook":
            {
                /* Controlled A/B scene: one solid with one gradient, so "the gradient is wrong in
                 * 3D" can be judged against the composition view instead of against a busy demo. */
                this.debugGradientScene();
                this.enter3DSpace();

                if ("gradlook".equals(view))
                {
                    this.toggleViewportLookThrough();
                }

                break;
            }

            case "cameralook":
                /* Verification: the free viewport, then Blender's numpad 0. */
                this.enter3DSpace();
                this.toggleViewportLookThrough();
                break;

            case "entity":
                this.debugEntityScene();
                break;

            case "filmreplay":
                this.debugOpenFilm(System.getProperty("bbsae.debugFilmId", "A2(MAIN)_"));
                return;

            case "quick":
                this.debugQuick3D();

                if (Boolean.getBoolean("bbsae.debugGizmo3D"))
                {
                    CompLayer card = this.doc.getLayer("card_3d");

                    this.cameraPreviewMode = false;
                    this.enter3DSpace();
                    this.selectLayer(card);

                    String mode = System.getProperty("bbsae.gizmoMode", "move");

                    this.previewGizmoModeOverride = "scale".equals(mode) ? 1
                        : "rotate".equals(mode) ? 2 : 0;

                    this.setStatus("3D gizmo：W 移动 · E 缩放 · R 旋转");
                }

                break;

            case "queue":
                this.toggleRenderView();
                break;

            case "textfx":
            {
                /* Verification view for the "text layer + effect is upside down" report: a text
                 * layer near the top of the frame, a glow (content resampling) and a blur (kernel
                 * with padding) applied. The screenshot must show upright, readable text. */
                if (this.viewportMode)
                {
                    this.exit3DSpace();
                }

                this.cameraPreviewMode = false;

                CompLayer text = null;

                for (CompLayer candidate : this.doc.layers)
                {
                    if ("text".equals(candidate.type))
                    {
                        text = candidate;
                        break;
                    }
                }

                if (text != null)
                {
                    text.y = this.doc.height * 0.32F;
                    text.text = "文字方向 TEST 0123";
                    text.effects.clear();

                    CompEffect glow = CompEffectRegistry.create("am_glow", 1);

                    glow.put("radius", 30D);
                    glow.put("intensity", 1.2D);
                    text.effects.add(glow);
                    text.effects.add(CompEffectRegistry.create("blur", 1));

                    this.selectLayer(text);
                    this.time = 0F;
                    this.setStatus("验证：文字图层 + 发光 + 模糊（文字方向应正常）");
                }

                break;
            }

            case "replay":
                /* Entity-replay editing page: BBS's own replay timeline/gizmo/model library inside
                 * bbsae's 3D space, as the compositor's second workspace. */
                this.openReplayWorkspace();
                break;

            case "docktest":
            {
                /* Verification view: a tab-stacked dock tree (effects + inspector share a slot),
                 * which is the arrangement plain screenshots cannot reach without mouse dragging. */
                if (this.dock != null && this.dockSource != null)
                {
                    this.dockSource.setRoot(new mchorse.bbs_mod.settings.values.ui.EditorLayoutNode.SplitterNode(true, 0.72F,
                        new mchorse.bbs_mod.settings.values.ui.EditorLayoutNode.SplitterNode(false, 0.24F,
                            new mchorse.bbs_mod.settings.values.ui.EditorLayoutNode.StackNode(
                                java.util.List.of(CompDockSource.PANEL_EFFECTS, CompDockSource.PANEL_INSPECTOR),
                                CompDockSource.PANEL_EFFECTS),
                            new mchorse.bbs_mod.settings.values.ui.EditorLayoutNode.PanelNode(CompDockSource.PANEL_PREVIEW)),
                        new mchorse.bbs_mod.settings.values.ui.EditorLayoutNode.PanelNode(CompDockSource.PANEL_TIMELINE)));
                    this.dockSource.setHiddenPanels(new java.util.LinkedHashSet<>());
                    this.dock.locked(false);
                    this.dock.refresh();
                    this.dockLayoutDirty = true;
                    this.setStatus("停靠布局验证：左栏与属性栏已堆叠成标签；布局已解锁");
                }

                break;
            }

            case "curve":
            default:
            {
                CompLayer layer = this.doc.getLayer("motion_card");

                if (layer == null)
                {
                    layer = new CompLayer("motion_card", "Motion Card", "shape");
                    layer.x = this.doc.width * 0.3F;
                    layer.y = this.doc.height * 0.55F;
                    layer.width = 420F;
                    layer.height = 240F;
                    layer.shapes.add(CompShape.rect("motion_shape", 0F, 0F, 300F, 180F, 0xFFFFFFFF));
                    this.doc.layers.add(layer);
                }

                /* Three eased position keys across the whole composition, so the graph has a real
                 * curve at a readable scale (and handles to drag). */
                if (!layer.hasAnyTransformKey())
                {
                    float fps = Math.max(1F, this.doc.fps);
                    float end = Math.max(0.5F, this.doc.duration);
                    float[][] keys = {
                        {0F, 380F, 620F},
                        {end * 0.45F * fps, 960F, 380F},
                        {end * 0.9F * fps, 1500F, 720F}
                    };

                    for (float[] key : keys)
                    {
                        layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_X, key[0], key[1]);
                        layer.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_Y, key[0], key[2]);
                    }

                    dev.bbsae.comp.CompCurves.applyEase(
                        layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_X),
                        (List) layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_X).getKeyframes(),
                        dev.bbsae.comp.CompCurves.EASE_BOTH, dev.bbsae.comp.CompCurves.DEFAULT_INFLUENCE);
                    dev.bbsae.comp.CompCurves.applyEase(
                        layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_Y),
                        (List) layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_Y).getKeyframes(),
                        dev.bbsae.comp.CompCurves.EASE_BOTH, dev.bbsae.comp.CompCurves.DEFAULT_INFLUENCE);
                }

                this.selectLayer(layer);
                this.timelinePage = 1;
                this.applyTimelinePage(true);
                this.curveEditor.refresh();
                this.curveEditor.debugSelectFirstKey();

                if (Boolean.getBoolean("bbsae.debugBezier"))
                {
                    this.curveEditor.debugMakeFirstBezier();
                }

                this.refreshSheets();
                this.time = 1.5F;
                this.updatePlayLabel();

                if (Boolean.getBoolean("bbsae.debugPlay"))
                {
                    this.togglePlay();
                }

                if (Boolean.getBoolean("bbsae.debugCurve"))
                {
                    StringBuilder keys = new StringBuilder();

                    for (Object key : layer.axisChannel(dev.bbsae.comp.CompCurves.AXIS_X).getKeyframes())
                    {
                        keys.append(((mchorse.bbs_mod.utils.keyframes.Keyframe<?>) key).getTick()).append(' ');
                    }

                    dev.bbsae.BbsAeAddon.LOGGER.info("debugView curve: comp fps={} duration={} keys(tick)={} durationTicks={}",
                        this.doc.fps, this.doc.duration, keys, this.getDurationTicks());
                }
                break;
            }
        }

        this.save();
    }

    /** Development-only: queue the whole composition through the AE encoder. */
    public void debugAeExport()
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        this.applyEncoder(CompProject.ENCODER_AE);
        this.exportWithAeEncoder();
    }

    /** A single gradient layer and a camera: the A/B scene for "gradient in 3D". */
    public void debugGradientScene()
    {
        if (this.project == null)
        {
            return;
        }

        CompDocument doc = this.doc;

        doc.layers.clear();
        doc.audio.clear();

        CompLayer card = new CompLayer("grad", "Gradient", "solid");

        card.x = doc.width / 2F;
        card.y = doc.height / 2F;
        card.width = 900F;
        card.height = 600F;
        card.color = 0xFFFFFFFF;
        card.threeD = true;
        card.out = doc.duration;

        CompEffect gradient = CompEffectRegistry.create("gradient_ramp", 1);

        gradient.putColor("start", 0xFFFF3366);
        gradient.putColor("end", 0xFF3366FF);
        /* Vertical on purpose: a vertical flip is invisible on a horizontal ramp, so the A/B test
         * has to use an angle where top and bottom differ. */
        gradient.put("angle", 90D);
        gradient.put("softness", 1D);
        card.effects.add(gradient);
        doc.layers.add(card);

        CompLayer camera = new CompLayer("cam", "Camera", "camera");

        camera.threeD = true;
        camera.camera3D = true;
        camera.x = doc.width / 2F;
        camera.y = doc.height / 2F;
        camera.cameraZ = this.defaultCameraZ();
        camera.z = camera.cameraZ;
        camera.cameraTargetX = doc.width / 2F;
        camera.cameraTargetY = doc.height / 2F;
        camera.cameraTargetZ = 0F;
        camera.cameraFov = 50F;
        camera.out = doc.duration;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        this.selectedLayer = card;
        this.selectedIds.clear();
        this.selectedIds.add(card.id);
        this.time = 2F;
        this.refreshLayers();
        this.refreshInspector();
        this.save();
    }

    /**
     * Verification state: a 3D layer selected with real Z / pitch / yaw / Z-scale keys, the
     * keyframe drawer open and the BBS interpolation entry point visible. Used for the quick
     * panel 3D rows and the curve picker screenshot.
     */
    /** Loads a named project and optionally enters camera preview (verification only). */
    public void debugOpenProject(String projectId, boolean cameraPreview)
    {
        CompProject project = CompStore.loadOrNull(projectId, true);

        if (project == null || project.comps == null || project.comps.isEmpty())
        {
            return;
        }

        CompDocument comp = project.getCompOrFirst(CompStore.DEFAULT_COMP);

        if (comp == null)
        {
            comp = project.comps.get(0);
        }

        this.bindStandalone(project, comp);
        this.cameraPreviewMode = cameraPreview;
        this.project.settings.cameraPreviewMode = cameraPreview;
        this.time = 0F;

        if (cameraPreview)
        {
            this.viewportMode = true;
            CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
            this.syncViewportSize();
            this.viewportCamera.lookThrough(CompScene.camera(context));
            this.viewportCamera.update();
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.trackTimeline.fit();
        this.updatePlayLabel();
        this.setStatus("调试工程：" + projectId + (cameraPreview ? " · 摄像机预览" : ""));
    }

    private void debugQuick3D()
    {
        if (this.doc == null)
        {
            return;
        }

        CompLayer layer = this.doc.getLayer("card_3d");

        if (layer == null)
        {
            layer = new CompLayer("card_3d", "3D Card", "shape");
            layer.x = this.doc.width * 0.5F;
            layer.y = this.doc.height * 0.5F;
            layer.width = 420F;
            layer.height = 240F;
            layer.shapes.add(CompShape.rect("quick_shape", 0F, 0F, 360F, 200F, 0xFF66CCFF));
            this.doc.addLayer(layer);
        }

        layer.threeD = true;
        layer.z = 320F;
        layer.rotationX = -15F;
        layer.rotationY = 22F;
        layer.scaleZ = 1.1F;

        float fps = Math.max(1F, this.doc.fps);
        float end = Math.max(1F, this.doc.duration) * fps;

        if (layer.scalarChannel("z").getKeyframes().isEmpty())
        {
            layer.scalarChannel("z").insert(0F, 120F);
            layer.scalarChannel("z").insert(end * 0.55F, 360F);
            layer.scalarChannel("rotationX").insert(0F, 0F);
            layer.scalarChannel("rotationX").insert(end * 0.55F, -20F);
            layer.scalarChannel("scaleZ").insert(0F, 1F);
            layer.scalarChannel("scaleZ").insert(end * 0.55F, 1.2F);
        }

        this.inspectorPage = PAGE_LAYER;
        this.timelinePage = 0;
        this.applyTimelinePage(false);
        this.selectLayer(layer);
        this.time = Math.max(0F, end * 0.55F / fps);
        this.openKeyframes();
        this.refreshSheets();

        for (Object entry : layer.scalarChannel("z").getKeyframes())
        {
            this.keyframeEditor.view.getGraph().selectKeyframe((Keyframe) entry);

            break;
        }

        if (Boolean.getBoolean("bbsae.debugCurveMenu"))
        {
            this.openCurveMenu();
        }

        this.setStatus("调试：3D XYZ 快捷面板 · BBS 插值曲线");
        this.save();
    }

    /** Verification state: a replay entity layer selected, so its table and matte are visible. */
    private void debugEntityScene()
    {
        CompLayer layer = null;

        for (CompLayer candidate : this.doc.layers)
        {
            if (candidate.isEntityLayer())
            {
                layer = candidate;
            }
        }

        if (layer == null)
        {
            this.addLayer("replay");

            for (CompLayer candidate : this.doc.layers)
            {
                if (candidate.isEntityLayer())
                {
                    layer = candidate;
                }
            }
        }

        if (layer != null)
        {
            if (Boolean.getBoolean("bbsae.debugNoEntityOcclusion"))
            {
                layer.entityOcclusion = false;
            }

            if (Boolean.getBoolean("bbsae.debugEntityOcclusion"))
            {
                layer.entityOcclusion = true;
            }

            /* Verification: a saturated stage makes "transparent layer" and "opaque black layer"
             * impossible to confuse in a screenshot. */
            if (Boolean.getBoolean("bbsae.debugEntityStage"))
            {
                this.doc.background = 0xFFFF00FF;
            }

            /* Isolation view: the entity layer normally sits over the BBS world plate, so a
             * screenshot cannot tell a working matte from the plate showing the actor. */
            if (Boolean.getBoolean("bbsae.debugHideWorldPlate"))
            {
                for (CompLayer candidate : this.doc.layers)
                {
                    if (candidate != null && "bbs".equals(candidate.type))
                    {
                        candidate.visible = false;
                    }
                }

                this.refreshLayers();
            }

            /* Re-show the world plate for the "actor + plate, no duplicate" check. Applied after
             * the hide flags so a single run can request either isolation or composition. */
            if (Boolean.getBoolean("bbsae.debugWorldPlate"))
            {
                for (CompLayer candidate : this.doc.layers)
                {
                    if (candidate != null && "bbs".equals(candidate.type))
                    {
                        candidate.visible = true;
                    }
                }

                this.refreshLayers();
            }

            /* Strongest isolation: the entity layer and its camera only. Every other pixel in the
             * preview is therefore either the matte or the stage colour. */
            if (Boolean.getBoolean("bbsae.debugOnlyEntityLayer"))
            {
                for (CompLayer candidate : this.doc.layers)
                {
                    if (candidate != null && !candidate.isEntityLayer() && !"camera".equals(candidate.type))
                    {
                        candidate.visible = false;
                    }
                }

                this.refreshLayers();
            }

            this.inspectorPage = PAGE_LAYER;
            this.selectLayer(layer);
            this.refreshInspector();
            this.setStatus("调试：回放实体图层 · 本帧抠出 " + dev.bbsae.client.render.ReplayEntityMask.entityCount() + " 个实体");
        }
    }

    /**
     * Verification only: one 3D layer with a mesh effect, nothing else. This isolates whether the
     * projected mesh output is composited at all, without relying on layer visibility flags.
     */
    /**
     * Verification only: the user's "2D shatter at zero must restore a normal square" contract,
     * next to a broken shatter for comparison and a stacked 3D primitive.
     */
    public void debugZeroTest()
    {
        this.project = CompStore.demo("zero-test");
        this.doc = this.project.getComp(CompStore.DEFAULT_COMP);
        this.doc.width = 1280;
        this.doc.height = 720;
        this.doc.layers.clear();

        CompLayer restored = new CompLayer("zero_restored", "Shatter 0", "shape");

        restored.x = 300F;
        restored.y = 360F;
        restored.width = 320F;
        restored.height = 220F;
        restored.shapes.add(CompShape.rect("zero_shape", 0F, 0F, 320F, 220F, 0xFFFFFFFF));
        CompEffect restoredGradient = CompEffectRegistry.create("gradient_ramp", 1);

        restoredGradient.putColor("start", 0xFF7C3AED);
        restoredGradient.putColor("end", 0xFF06B6D4);
        restoredGradient.put("angle", 45D);
        restored.effects.add(restoredGradient);
        CompEffect shatterZero = CompEffectRegistry.create("block_shatter", 1);

        shatterZero.put("progress", 0.0);
        restored.effects.add(shatterZero);
        this.doc.layers.add(restored);

        CompLayer broken = new CompLayer("zero_broken", "Shatter 0.35", "shape");

        broken.x = 640F;
        broken.y = 360F;
        broken.width = 320F;
        broken.height = 220F;
        broken.shapes.add(CompShape.rect("broken_shape", 0F, 0F, 320F, 220F, 0xFFFFFFFF));
        CompEffect brokenGradient = CompEffectRegistry.create("gradient_ramp", 1);

        brokenGradient.putColor("start", 0xFF7C3AED);
        brokenGradient.putColor("end", 0xFF06B6D4);
        brokenGradient.put("angle", 45D);
        broken.effects.add(brokenGradient);
        CompEffect shatterBroken = CompEffectRegistry.create("block_shatter", 1);

        shatterBroken.put("progress", 0.35);
        broken.effects.add(shatterBroken);
        this.doc.layers.add(broken);

        CompLayer stack = new CompLayer("zero_stack", "3D stacked", "shape");

        stack.x = 980F;
        stack.y = 360F;
        stack.width = 280F;
        stack.height = 180F;
        stack.threeD = true;
        stack.z = 120F;
        stack.rotationY = 24F;
        stack.rotationX = -12F;
        stack.shapes.add(CompShape.rect("stack_shape", 0F, 0F, 280F, 180F, 0xFFFFFFFF));
        CompEffect stackGradient = CompEffectRegistry.create("gradient_ramp", 1);

        stackGradient.putColor("start", 0xFFF97316);
        stackGradient.putColor("end", 0xFF06B6D4);
        stackGradient.put("angle", 45D);
        stack.effects.add(stackGradient);
        stack.effects.add(CompEffectRegistry.create("s3d_cube", 1));
        this.doc.layers.add(stack);

        CompLayer camera = new CompLayer("camera_3d", "3D Camera", "camera");

        camera.width = 100F;
        camera.height = 100F;
        camera.threeD = true;
        camera.camera3D = true;
        camera.z = this.defaultCameraZ();
        camera.cameraZ = camera.z;
        camera.cameraTargetX = this.doc.width / 2F;
        camera.cameraTargetY = this.doc.height / 2F;
        camera.cameraTargetZ = 0F;
        this.doc.layers.add(camera);
        this.doc.activeCameraId = camera.id;
        this.selectedLayer = restored;
        this.selectedIds.clear();
        this.selectedIds.add(restored.id);
        this.time = 2F;
        this.startScreenOpen = false;

        if (this.startScreen != null)
        {
            this.startScreen.setVisible(false);
        }

        this.inspectorPage = PAGE_EFFECTS;
        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.trackTimeline.fit();
        this.updatePlayLabel();
    }

    public void debugCubeTest()
    {
        this.project = CompStore.demo("cube-test");
        this.doc = this.project.getComp(CompStore.DEFAULT_COMP);
        this.doc.width = 1280;
        this.doc.height = 720;
        this.doc.layers.clear();

        CompLayer card = new CompLayer("cube_card", "Cube Card", "shape");
        card.x = this.doc.width / 2F;
        card.y = this.doc.height / 2F;
        if (Boolean.getBoolean("bbsae.debugAllShapes"))
        {
            String[] names =
            {
                "cube", "cylinder", "sphere", "pyramid", "cone", "torus", "ring", "plus",
                "octahedron", "hexprism", "triprism", "starprism", "heart", "hollowbox",
                "starpoly", "quad",
            };

            this.doc.layers.clear();
            this.doc.width = 1280;
            this.doc.height = 720;
            this.doc.background = 0xFF101014;

            for (int i = 0; i < names.length; i++)
            {
                int column = i % 4;
                int row = i / 4;
                CompLayer tile = new CompLayer("shape_" + names[i], names[i], "shape");

                tile.x = 160F + column * 320F;
                tile.y = 120F + row * 175F;
                tile.width = 220F;
                tile.height = 130F;
                tile.shapes.add(CompShape.rect("tile_shape", 0F, 0F, 220F, 130F, 0xFFFFFFFF));

                CompEffect gradient = CompEffectRegistry.create("gradient_ramp", 1);

                gradient.putColor("start", 0xFF7C3AED);
                gradient.putColor("end", 0xFF06B6D4);
                gradient.put("angle", 45D);
                tile.effects.add(gradient);
                tile.effects.add(CompEffectRegistry.create("s3d_" + names[i], 1));
                tile.out = this.doc.duration;
                this.doc.layers.add(tile);
            }

            CompLayer camera = new CompLayer("camera_3d", "3D Camera", "camera");

            camera.width = 100F;
            camera.height = 100F;
            camera.threeD = true;
            camera.camera3D = true;
            camera.z = this.defaultCameraZ();
            camera.cameraZ = camera.z;
            camera.cameraTargetX = this.doc.width / 2F;
            camera.cameraTargetY = this.doc.height / 2F;
            camera.cameraTargetZ = 0F;
            this.doc.layers.add(camera);
            this.doc.activeCameraId = camera.id;
            this.selectedLayer = this.doc.layers.get(0);
            this.selectedIds.clear();
            this.selectedIds.add(this.selectedLayer.id);
            this.time = 2F;
            this.startScreenOpen = false;

            if (this.startScreen != null)
            {
                this.startScreen.setVisible(false);
            }

            this.inspectorPage = PAGE_EFFECTS;
            dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
            this.refreshLayers();
            this.refreshInspector();
            this.refreshSheets();
            this.trackTimeline.fit();
            this.updatePlayLabel();

            return;
        }

        card.width = 420F;
        card.height = 260F;
        card.threeD = !Boolean.getBoolean("bbsae.debugCube2D");
        card.z = 120F;
        card.rotationY = card.threeD ? 24F : 0F;
        card.rotationX = card.threeD ? -12F : 0F;
        card.shapes.add(CompShape.rect("cube_shape", 0F, 0F, 420F, 260F, 0xFFFFFFFF));
        CompEffect gradient = CompEffectRegistry.create("gradient_ramp", 1);

        gradient.putColor("start", 0xFF7C3AED);
        gradient.putColor("end", 0xFF06B6D4);
        gradient.put("angle", 45D);
        card.effects.add(gradient);
        card.effects.add(CompEffectRegistry.create(
            System.getProperty("bbsae.debugShape", "s3d_cube"), 1));
        this.doc.layers.add(card);

        CompLayer camera = new CompLayer("camera_3d", "3D Camera", "camera");

        camera.width = 100F;
        camera.height = 100F;
        camera.threeD = true;
        camera.camera3D = true;
        camera.z = this.defaultCameraZ();
        camera.cameraZ = camera.z;
        camera.cameraTargetX = this.doc.width / 2F;
        camera.cameraTargetY = this.doc.height / 2F;
        camera.cameraTargetZ = 0F;
        this.doc.layers.add(camera);
        this.doc.activeCameraId = camera.id;
        this.project.settings.cameraPreviewMode = true;
        this.cameraPreviewMode = true;
        this.selectedLayer = card;
        this.selectedIds.clear();
        this.selectedIds.add(card.id);
        this.time = 2F;
        this.startScreenOpen = false;

        if (this.startScreen != null)
        {
            this.startScreen.setVisible(false);
        }

        this.inspectorPage = PAGE_EFFECTS;
        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.trackTimeline.fit();
        this.updatePlayLabel();
    }

    /**
     * Verification only: render the current composition's first frame through CompRenderer and
     * write it as a PNG. This is the authoritative pixel result; the viewport also draws gizmos and
     * editor overlays, which made a 3D-placement bug ambiguous.
     */
    public void debugRenderCompShot(String name)
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        dev.bbsae.client.render.GlFramebuffer framebuffer =
            dev.bbsae.client.render.CompRenderer.renderFrame(this.project, this.doc, 0, false, 1F);

        if (framebuffer == null)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("comp shot: the composition renderer returned no frame");

            return;
        }

        try
        {
            int[] pixels = framebuffer.readTopDown();
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                this.doc.width, this.doc.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);

            image.setRGB(0, 0, this.doc.width, this.doc.height, pixels, 0, this.doc.width);

            java.io.File output = new java.io.File(
                net.minecraft.client.MinecraftClient.getInstance().runDirectory, "screenshots/" + name + ".png");

            output.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(image, "png", output);
            dev.bbsae.BbsAeAddon.LOGGER.info("comp shot: {}", output.getAbsolutePath());
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("comp shot failed", e);
        }
        finally
        {
            dev.bbsae.client.render.FboPool.release(framebuffer);

            if (Boolean.getBoolean("bbsae.debugQuitAfterShot"))
            {
                net.minecraft.client.MinecraftClient.getInstance().execute(
                    net.minecraft.client.MinecraftClient.getInstance()::scheduleStop);
            }
        }
    }

    public void debugDemoEffects()
    {
        this.project = CompStore.demo("dev-effects");
        this.doc = this.project.getComp(CompStore.DEFAULT_COMP);
        this.doc.layers.clear();

        CompLayer bbs = new CompLayer("bbs_preview", "BBS 预览", "bbs");
        bbs.width = this.doc.width;
        bbs.height = this.doc.height;
        bbs.out = this.doc.duration;
        this.doc.layers.add(bbs);

        CompLayer shape = new CompLayer("gradient_shape", "Gradient Shape", "shape");
        shape.x = this.doc.width / 2F;
        shape.y = this.doc.height / 2F;
        shape.width = 520F;
        shape.height = 320F;
        shape.shapes.add(CompShape.rect("grad_shape", 0F, 0F, 360F, 220F, 0xFFFFFFFF));
        CompEffect gradient = CompEffectRegistry.create("gradient_ramp", 1);
        gradient.putColor("start", 0xFFFF3366);
        gradient.putColor("end", 0xFF33CCFF);
        gradient.put("angle", 90D);
        shape.effects.add(gradient);
        this.doc.layers.add(shape);

        /* Verification layer for the text-content features (timecode/counter/scramble/reveal):
         * the 文字 inspector page needs a real text layer selected. */
        CompLayer textDemo = new CompLayer("text_demo", "文字", "text");
        textDemo.x = this.doc.width / 2F;
        textDemo.y = this.doc.height / 2F + 180F;
        textDemo.width = 900F;
        textDemo.height = 160F;
        textDemo.fontSize = 64F;
        textDemo.text = "文字效果 Aa 123";
        textDemo.color = 0xFFFFFFFF;
        textDemo.out = this.doc.duration;
        this.doc.layers.add(textDemo);

        CompLayer nullLayer = new CompLayer("null_ctrl", "Null Controller", "null");
        nullLayer.x = this.doc.width / 2F - 300F;
        nullLayer.y = this.doc.height / 2F - 200F;
        nullLayer.out = this.doc.duration;
        this.doc.layers.add(nullLayer);

        CompLayer card3D = new CompLayer("card_3d", "3D Card", "shape");
        card3D.x = this.doc.width / 2F + 160F;
        card3D.y = this.doc.height / 2F + 60F;
        card3D.width = 420F;
        card3D.height = 260F;
        card3D.threeD = true;
        card3D.z = 180F;
        card3D.rotationY = 28F;
        card3D.rotationX = -10F;
        card3D.shapes.add(CompShape.rect("card_shape", 0F, 0F, 340F, 200F, 0xFFFFFFFF));
        CompEffect cardGradient = CompEffectRegistry.create("gradient_ramp", 1);
        cardGradient.putColor("start", 0xFF7C3AED);
        cardGradient.putColor("end", 0xFF06B6D4);
        cardGradient.put("angle", 45D);
        card3D.effects.add(cardGradient);

        /* Verification scenario: a mesh effect on a 3D layer exercises the projected-pass path
         * and the bake split in the real client. */
        card3D.effects.add(CompEffectRegistry.create("s3d_cube", 1));

        this.doc.layers.add(card3D);

        CompLayer camera = new CompLayer("camera_3d", "3D Camera", "camera");
        camera.width = 100F;
        camera.height = 100F;
        camera.threeD = true;
        camera.camera3D = true;
        camera.z = this.defaultCameraZ();
        camera.cameraZ = camera.z;
        camera.cameraTargetX = this.doc.width / 2F;
        camera.cameraTargetY = this.doc.height / 2F;
        camera.cameraTargetZ = 0F;
        this.doc.layers.add(camera);
        this.doc.activeCameraId = camera.id;
        this.project.settings.cameraPreviewMode = true;
        this.cameraPreviewMode = true;

        /* Verification only: an ellipse used as a mask on the 3D card, so the intersection is
         * visible in a screenshot (and the shape itself is hidden, as the button does). */
        if (Boolean.getBoolean("bbsae.debugViewLock"))
        {
            CompLayer mask = new CompLayer("mask_ellipse", "Mask Ellipse", "shape");

            mask.x = this.doc.width / 2F;
            mask.y = this.doc.height / 2F;
            mask.width = 200F;
            mask.height = 120F;
            mask.shapes.add(CompShape.ellipse("mask_shape", 0F, 0F, 180F, 110F, 0xFFFFFFFF));
            this.doc.layers.add(mask);

            /* The big gradient card is the one that makes "ellipse vs rectangle" obvious. */
            CompLayer gradientCard = null;

            for (CompLayer layer : this.doc.layers)
            {
                if ("gradient_shape".equals(layer.id))
                {
                    gradientCard = layer;
                }
            }

            if (gradientCard != null)
            {
                gradientCard.trackMatteId = mask.id;
                gradientCard.trackMatteEnabled = true;
                gradientCard.trackMatteMode = CompLayer.MATTE_ALPHA;
            }

            /* Mask the 3D card as well: it is the one at the camera's target, so the ellipse is
             * unmistakable in a screenshot. */
            card3D.trackMatteId = mask.id;
            card3D.trackMatteEnabled = true;
            card3D.trackMatteMode = CompLayer.MATTE_ALPHA;

            mask.visible = false;
            mask.guide = true;
        }

        /* Verification helper: hide everything except the named layer (and the camera) so a
         * screenshot shows exactly one effect result. */
        String onlyLayer = System.getProperty("bbsae.debugOnlyLayer", "");

        if (!onlyLayer.isBlank())
        {
            for (CompLayer candidate : this.doc.layers)
            {
                boolean keep = "camera".equals(candidate.type)
                    || onlyLayer.equals(candidate.id)
                    || (candidate.name != null && candidate.name.contains(onlyLayer));

                candidate.visible = keep;
            }
        }

        this.selectedLayer = card3D;
        this.selectedIds.clear();
        this.selectedIds.add(card3D.id);
        this.time = 2F;
        this.startScreenOpen = false;

        if (this.startScreen != null)
        {
            this.startScreen.setVisible(false);
        }

        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.trackTimeline.fit();
        this.updatePlayLabel();
    }

    /**
     * Development-only: open an existing film and its replay composition, without starting an
     * export. Used to verify the replay entity matte against a real film actor under Iris.
     */
    public void debugOpenFilm(String filmId)
    {
        mchorse.bbs_mod.ui.ContentType.FILMS.getRepository().load(filmId, (data) ->
        {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
            {
                if (!(data instanceof mchorse.bbs_mod.film.Film film))
                {
                    BbsAeAddon.LOGGER.error("Debug film: {} not found", filmId);

                    return;
                }

                mchorse.bbs_mod.ui.film.UIFilmPanel panel = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

                if (panel == null)
                {
                    panel = new mchorse.bbs_mod.ui.film.UIFilmPanel(this.dashboard);
                }

                panel.fill(film);
                BbsAeClient.openCompositorForFilm(panel, film, film.replays.getList().isEmpty() ? null : film.replays.getList().get(0));

                this.debugView("entity");
                this.time = Math.min(2F, Math.max(0F, this.doc.duration * 0.25F));

                /* The film panel's asynchronous fill can win the dashboard race and leave the
                 * replay properties on screen. Re-assert the compositor once the callback has
                 * settled; normal use opens it from the replay menu after the film is filled. */
                final mchorse.bbs_mod.ui.film.UIFilmPanel debugPanel = panel;
                final mchorse.bbs_mod.film.Film debugFilm = film;

                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                    new net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick()
                    {
                        private int reassert = 40;

                        @Override
                        public void onEndTick(net.minecraft.client.MinecraftClient client)
                        {
                            if (--this.reassert == 0)
                            {
                                BbsAeClient.openCompositorForFilm(debugPanel, debugFilm,
                                    debugFilm.replays.getList().isEmpty() ? null : debugFilm.replays.getList().get(0));
                            }
                        }
                    });
                this.applyTimelinePage(true);
                this.refreshSheets();
                this.setStatus("调试：真实影片回放抠像 · " + filmId);
                BbsAeAddon.LOGGER.info("Debug film replay opened: {} replays={} time={} maskAvailable={} maskSize={}x{} entities={}",
                    filmId, film.replays.getList().size(), this.time,
                    dev.bbsae.client.render.ReplayEntityMask.isAvailable(),
                    dev.bbsae.client.render.ReplayEntityMask.width(),
                    dev.bbsae.client.render.ReplayEntityMask.height(),
                    dev.bbsae.client.render.ReplayEntityMask.entityCount());
            });
        });
    }

    /**
     * Development-only: load a film, attach a composition clip and start BBS's own recorder at an
     * explicit size. It is the same path the Export button uses, but reachable from a headless test
     * run so a 1080p60 export can be verified without clicking through the UI.
     */
    public void debugExportFilm(String filmId, String projectId, String compId, int width, int height, int fps)
    {
        mchorse.bbs_mod.ui.ContentType.FILMS.getRepository().load(filmId, (data) ->
        {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
            {
                if (!(data instanceof mchorse.bbs_mod.film.Film film))
                {
                    BbsAeAddon.LOGGER.error("Debug export: film {} not found", filmId);

                    return;
                }

                CompProject debugProject = CompStore.loadOrNull(projectId, true);
                CompDocument debugComp = debugProject == null ? null : debugProject.getComp(compId);
                float debugSeconds = debugComp == null ? 0F : debugComp.duration;

                if (debugProject != null && debugProject.settings != null && debugProject.settings.exportDuration > 0F)
                {
                    debugSeconds = debugProject.settings.exportDuration;
                }

                CompositionClip.prepareForExport(film, projectId, compId, debugSeconds);

                mchorse.bbs_mod.ui.film.UIFilmPanel panel = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

                if (panel == null)
                {
                    BbsAeAddon.LOGGER.error("Debug export: film panel missing");

                    return;
                }

                this.filmPanel = panel;
                this.film = film;
                this.dashboard.setPanel(panel);
                mchorse.bbs_mod.ui.framework.UIScreen.open(this.dashboard);
                panel.fill(film);

                mchorse.bbs_mod.BBSSettings.videoWidth.set(width);
                mchorse.bbs_mod.BBSSettings.videoHeight.set(height);
                mchorse.bbs_mod.BBSSettings.videoFrameRate.set(fps);
                mchorse.bbs_mod.BBSSettings.editorLoop.set(false);
                mchorse.bbs_mod.ui.film.UIFilmPanel.applyExportSizeToBBS();

                int debugTicks = debugSeconds <= 0F ? 20 : Math.max(20, Math.round(debugSeconds * 20F) + 1);

                mchorse.bbs_mod.client.BBSRendering.scheduleAfterNextExportFrame(() ->
                {
                    mchorse.bbs_mod.graphics.texture.Texture texture = mchorse.bbs_mod.client.BBSRendering.getTexture();

                    panel.recorder.startRecording(
                        debugTicks,
                        texture.id,
                        mchorse.bbs_mod.client.BBSRendering.getVideoWidth(),
                        mchorse.bbs_mod.client.BBSRendering.getVideoHeight()
                    );
                });

                BbsAeAddon.LOGGER.info("Debug export started: {}x{} @{}fps film={} duration={} ticks",
                    width, height, fps, filmId, debugTicks);
            });
        });
    }

    public CompLayer getSelectedLayer()
    {
        return this.selectedLayer;
    }

    /** Every selected layer, in document order. Empty when nothing is selected. */
    public List<CompLayer> getSelectedLayers()
    {
        List<CompLayer> layers = new ArrayList<>();

        if (this.doc == null)
        {
            return layers;
        }

        for (CompLayer layer : this.doc.layers)
        {
            if (this.selectedIds.contains(layer.id))
            {
                layers.add(layer);
            }
        }

        return layers;
    }

    public int getSelectionCount()
    {
        return this.selectedIds.size();
    }

    public boolean isLayerSelected(CompLayer layer)
    {
        return layer != null && this.selectedIds.contains(layer.id);
    }

    /** Click: the clicked layer becomes the only selected one. */
    /** Audio clip currently selected on the timeline's audio lane, or null. */
    public dev.bbsae.comp.CompAudio getSelectedAudio()
    {
        return this.selectedAudio;
    }

    /**
     * Selects an audio clip (or clears the selection with null).
     *
     * <p>Selecting audio clears the layer selection: the two share the Delete key and the
     * inspector's focus, and "which thing am I editing" must have exactly one answer.</p>
     */
    public void selectAudioClip(dev.bbsae.comp.CompAudio clip)
    {
        this.selectedAudio = clip;

        if (clip != null)
        {
            this.selectedIds.clear();
            this.selectedLayer = null;
            this.inspectorPage = PAGE_COMP;
            this.setStatus("音频：" + clip.name + String.format(java.util.Locale.ROOT, " · 入点 %.2fs", clip.start));

            if (this.trackTimeline != null)
            {
                this.trackTimeline.revealAudio();
            }
        }

        this.refreshInspector();
        this.refreshLayers();
    }

    public void selectLayer(CompLayer layer)
    {
        if (layer != null)
        {
            /* Selecting a layer is the point where old shared Transform keys become independent
             * axis channels. Doing it here means the inspector's X/Y fields and the graph agree
             * before the first edit, not only after the keyframe drawer has been opened. */
            layer.ensureIndependentAxes();
        }

        if (layer == this.selectedLayer && this.selectedIds.size() == (layer == null ? 0 : 1))
        {
            return;
        }

        /* The pen is bound to one layer's path; switching layers mid-gesture must release it. */
        if (this.penTool != null && layer != this.selectedLayer)
        {
            this.penTool.forget();
        }

        if (layer != null)
        {
            this.selectedAudio = null;
        }

        this.selectedIds.clear();

        if (layer != null)
        {
            this.selectedIds.add(layer.id);
        }

        this.applySelection(layer);

        /* Human expectation: selecting a layer shows that layer's controls. If the inspector was
         * left on a composition-level page (合成/输出), jump back to 效果控件; explicitly chosen
         * per-layer pages (调色/图层/蒙版/文字) are kept so the tab does not fight the user. */
        if (layer != null && this.inspectorTabs != null
            && (this.inspectorPage == PAGE_COMP || this.inspectorPage == PAGE_OUTPUT))
        {
            this.setInspectorPage(PAGE_EFFECTS);
        }
    }

    /**
     * The one writer for "exactly this layer is selected" outside of user clicks.
     *
     * <p>{@code getSelectedLayers()} resolves through {@code selectedIds}, so a bare
     * {@code selectedLayer = x} leaves the set holding stale ids — Ctrl+K selected the right
     * fragment while "删除所选"/"复制所选" still acted on the old one, multi-select pages stayed
     * stuck after entering a precomp, and Ctrl+D made the original steal the primary back. Every
     * programmatic re-selection goes through here so the two fields cannot diverge.</p>
     */
    private void setSingleSelection(CompLayer layer)
    {
        this.selectedIds.clear();

        if (layer != null)
        {
            this.selectedIds.add(layer.id);
            this.selectedAudio = null;
        }

        this.selectedLayer = layer;
    }

    /**
     * Moves the primary selection without touching the highlighted set.
     *
     * <p>Used when the user right-clicks inside an existing multi-selection: the context menu is
     * built from the set, so collapsing it to one row would hide every batch command.</p>
     */
    public void setPrimarySelection(CompLayer layer)
    {
        if (layer == null || !this.selectedIds.contains(layer.id))
        {
            return;
        }

        this.applySelection(layer);
    }

    /** Ctrl+click: add or remove one layer without disturbing the rest. */    public void toggleLayerSelection(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        if (!this.selectedIds.remove(layer.id))
        {
            this.selectedIds.add(layer.id);
        }

        CompLayer primary = this.selectedLayer != null && this.selectedIds.contains(this.selectedLayer.id)
            ? this.selectedLayer
            : this.firstSelected();

        this.applySelection(primary);
    }

    /** Shift+click: select the range between the primary and the clicked row. */
    public void selectLayerRange(CompLayer layer)
    {
        if (this.doc == null || layer == null)
        {
            this.selectLayer(layer);

            return;
        }

        CompLayer anchor = this.selectedLayer == null ? layer : this.selectedLayer;
        int from = this.doc.layers.indexOf(anchor);
        int to = this.doc.layers.indexOf(layer);

        if (from < 0 || to < 0)
        {
            this.selectLayer(layer);

            return;
        }

        this.selectedIds.clear();

        for (int i = Math.min(from, to); i <= Math.max(from, to); i++)
        {
            this.selectedIds.add(this.doc.layers.get(i).id);
        }

        this.applySelection(layer);
    }

    public void selectAllLayers()
    {
        if (this.doc == null)
        {
            return;
        }

        this.selectedIds.clear();

        for (CompLayer layer : this.doc.layers)
        {
            this.selectedIds.add(layer.id);
        }

        this.applySelection(this.selectedLayer != null && this.selectedIds.contains(this.selectedLayer.id)
            ? this.selectedLayer
            : this.firstSelected());
    }

    private CompLayer firstSelected()
    {
        if (this.doc == null)
        {
            return null;
        }

        for (CompLayer layer : this.doc.layers)
        {
            if (this.selectedIds.contains(layer.id))
            {
                return layer;
            }
        }

        return null;
    }

    /** Keeps the panel's single-layer views (inspector, sheets, gizmos) in sync with the set. */
    private void applySelection(CompLayer primary)
    {
        this.selectedLayer = primary;

        /* One kind of selection at a time: a layer selection clears a highlighted audio clip, so
         * Delete can never remove the audio while a layer is the visible selection. */
        if (primary != null)
        {
            this.selectedAudio = null;
        }

        if (this.trackTimeline != null)
        {
            this.trackTimeline.ensureSelectionVisible();
        }

        if (this.effectLibrary != null)
        {
            this.effectLibrary.refreshStatus();
        }

        this.refreshInspector();
        this.refreshSheets();
        this.markViewStateDirty();
    }

    public void clearSelection()
    {
        if (this.selectedLayer == null && this.selectedIds.isEmpty() && this.selectedAudio == null)
        {
            return;
        }

        this.selectedIds.clear();

        this.selectedLayer = null;
        /* Esc/empty-click must clear the highlighted audio clip too, otherwise the audio bar
         * stays "selected" and Delete removes it on the next keypress. */
        this.selectedAudio = null;

        if (this.effectLibrary != null)
        {
            this.effectLibrary.refreshStatus();
        }

        this.refreshInspector();
        this.refreshSheets();
    }

    public void setStatus(String message)
    {
        this.statusMessage = message == null ? "" : message;
        this.statusTicks = 80;
    }

    /**
     * Errors must not be written straight into {@code status.label}: render() rewrites that label
     * every frame, so a direct assignment lives for zero frames and the user sees nothing.
     * Use this for anything that failed, and keep it on screen longer than a normal status.
     */
    private void notifyError(String message)
    {
        this.statusMessage = message == null ? "" : message;
        this.statusTicks = 200;
        BbsAeAddon.LOGGER.warn("Compositor: {}", message);
    }

    /** Timing edits do not rebuild the inspector: that would destroy the field the user is typing in. */
    private void commitLayerTiming()
    {
        this.save();

        if (this.trackTimeline != null)
        {
            this.trackTimeline.refresh();
        }

        this.refreshSheets();
    }

    public void onTrackEdited()
    {
        this.resetTimelineView = false;
        this.refreshInspector();
        this.refreshSheets();

        if (this.trackTimeline != null)
        {
            this.trackTimeline.refresh();
        }

        this.save();
    }

    /** Esc cancelled a timeline drag: values were restored, no edit is committed. */
    public void onTrackDragCancelled()
    {
        this.refreshInspector();
        this.refreshSheets();
        this.discardCancelledAction();
        this.setStatus("已取消拖动");
    }

    /**
     * Drops the undo point raised by a gesture that was cancelled back to its start state.
     *
     * <p>beginAction() records the pre-gesture snapshot. A cancelled drag restores exactly that
     * state, so keeping the checkpoint would add a no-op undo step. Only pop when the top entry
     * equals the current state (a real edit since the checkpoint must keep its history).</p>
     */
    void discardCancelledAction()
    {
        if (this.actionInProgress)
        {
            this.actionInProgress = false;
        }

        String top = this.undoStack.peek();

        if (top != null && this.project != null && top.equals(this.snapshotForUndo()))
        {
            this.undoChars -= this.undoStack.pop().length();

            if (!this.lastSnapshot.isEmpty())
            {
                this.lastSnapshot = top;
            }
        }
    }

    /**
     * Deletes every keyframe channel that belongs to an effect (`fx/&lt;id&gt;/...`).
     *
     * <p>Effect ids are the smallest free `fxN`, and the channels are namespaced by that id. If the
     * channels survived the effect, the next effect that got the same id would silently inherit the
     * old animation (and the orphan track would stay visible in the dope sheet / curve editor).
     * The channel-to-data table is rebuilt by {@code CompLayer.saveTracks()} on save/snapshot, so
     * removing it from the runtime map is enough.</p>
     */
    private void removeEffectChannels(CompLayer layer, String effectId)
    {
        if (layer == null || effectId == null || effectId.isBlank() || layer.animatedPaths() == null)
        {
            return;
        }

        String prefix = CompLayer.effectPath(effectId, "");

        for (String path : new ArrayList<>(layer.animatedPaths()))
        {
            if (path != null && path.startsWith(prefix))
            {
                layer.removeChannel(path);
            }
        }
    }

    public void toggleLayerVisible(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        this.beginAction("显示/隐藏图层");
        layer.visible = !layer.visible;
        this.refreshInspector();
        this.save();
        this.setStatus(layer.visible ? "轨道已显示" : "轨道已隐藏");
    }

    /** 50° camera at this distance maps the z=0 plane 1:1 to the composition. */
    private float defaultCameraZ()
    {
        return (this.doc == null ? 540F : this.doc.height * 0.5F) / (float) Math.tan(Math.toRadians(25D));
    }

    /** Track-header 3D switch. 2D layers stay screen-attached; 3D layers go through the camera. */
    public void toggleLayer3D(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        /* Adjustment layers always process the whole composition; the 3D switch would be a
         * silent no-op (audit P1-2). Refuse it with an explanation instead. */
        if ("adjustment".equals(layer.type))
        {
            this.setStatus("调整层始终作用于整幅合成：3D 开关对它无效（如需空间化请用 3D 图层+效果）");

            return;
        }

        this.beginAction("切换 3D 图层");
        layer.threeD = !layer.threeD;

        if (layer.threeD && "camera".equals(layer.type))
        {
            layer.camera3D = true;
        }

        this.refreshInspector();
        this.refreshSheets();
        this.save();

        if (layer.threeD)
        {
            boolean hasCamera = this.doc != null && this.doc.activeCamera(this.time) != null;

            this.setStatus(hasCamera
                ? "已开启 3D：" + this.layerLabel(layer) + "（跟随摄像机；可在图层页调 Z / 旋转 X/Y）"
                : "已开启 3D：" + this.layerLabel(layer) + "（还没有摄像机，点预览左下角球体进入 3D 空间）");
        }
        else
        {
            this.setStatus("已关闭 3D：" + this.layerLabel(layer));
        }
    }

    public void toggleLayerLocked(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        this.beginAction("锁定/解锁图层");
        layer.locked = !layer.locked;
        this.refreshInspector();
        this.save();
        this.setStatus(layer.locked ? "轨道已锁定" : "轨道已解锁");
    }

    public void renameLayer(CompLayer layer)
    {
        if (layer == null)
        {
            return;
        }

        UIPromptOverlayPanel prompt = new UIPromptOverlayPanel(
            IKey.constant("重命名轨道"),
            IKey.constant("输入新的轨道名称"),
            (text) ->
            {
                if (text == null || text.isBlank())
                {
                    return;
                }

                this.beginAction("重命名图层");
                layer.name = text.trim();
                this.refreshInspector();
                this.save();
            }
        );

        prompt.text.setText(layer.name);
        UIOverlay.addOverlay(this.getContext(), prompt, 320, 110);
    }

    public void duplicateLayer(CompLayer layer)
    {
        if (this.doc == null || layer == null)
        {
            return;
        }

        this.beginAction("复制图层");
        CompLayer copy = layer.duplicate();

        copy.id = CompStore.uniqueId(layer.id + "_copy", this.doc.layers, (candidate) -> candidate.id);
        copy.name = this.layerLabel(layer) + " 副本";
        dev.bbsae.comp.CompLayerOps.offsetCopy(copy, 24F, 24F);
        this.doc.layers.add(this.doc.layers.indexOf(layer) + 1, copy);
        this.setSingleSelection(copy);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    /* ---------------------------------------------------------------- multi selection */

    public void deleteSelection()
    {
        List<CompLayer> layers = this.getSelectedLayers();

        if (layers.isEmpty() || this.doc == null)
        {
            return;
        }

        this.beginAction("删除所选图层");

        int index = this.doc.layers.indexOf(layers.get(0));

        for (CompLayer layer : layers)
        {
            this.doc.removeLayer(layer.id);
        }

        CompLayer next = this.doc.layers.isEmpty()
            ? null
            : this.doc.layers.get(Math.max(0, Math.min(index, this.doc.layers.size() - 1)));

        this.selectedIds.clear();

        if (next != null)
        {
            this.selectedIds.add(next.id);
        }

        this.applySelection(next);

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已删除 " + layers.size() + " 个图层");
    }

    public void duplicateSelection()
    {
        if (this.doc == null)
        {
            return;
        }

        /* Nothing selected: do not touch the undo stack at all (beginAction would push an
         * identical checkpoint and clear redo for a no-op). */
        if (this.getSelectedLayers().isEmpty())
        {
            this.setStatus("先在时间轴选择要复制的轨道");

            return;
        }

        this.beginAction("复制所选图层");
        List<CompLayer> copies = dev.bbsae.comp.CompLayerOps.duplicate(this.doc, this.getSelectedLayers());

        this.selectedIds.clear();

        for (CompLayer copy : copies)
        {
            this.selectedIds.add(copy.id);
        }

        this.applySelection(copies.isEmpty() ? null : copies.get(copies.size() - 1));
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已复制 " + copies.size() + " 个图层");
    }

    /** "Combine into one pre-render": the selection becomes a nested composition. */
    public void precomposeSelection()
    {
        if (this.doc == null || this.project == null)
        {
            return;
        }

        List<CompLayer> layers = this.getSelectedLayers();

        if (layers.size() < 2)
        {
            this.setStatus("预合成需要至少两个图层（单选请用轨道菜单的“预合成”）");
            return;
        }

        /* Precomposing changes the layer stack and retimes the selected clips. State the effect
         * before doing it instead of surprising the artist with a rearranged timeline. */
        if (!Boolean.getBoolean("bbsae.skipPrecomposeConfirm") && this.getContext() != null)
        {
            String hint = "将把 " + layers.size() + " 个图层移入一个新子合成，再用一个预合成图层替换。"
                + "原图层入点会整体对齐；父级/遮罩引用会自动重指向。此操作可用 Ctrl+Z 撤销。";

            UIOverlay.addOverlay(this.getContext(), new mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel(
                IKey.constant("合并为预合成？"), IKey.constant(hint), (confirmed) ->
            {
                if (Boolean.TRUE.equals(confirmed))
                {
                    this.doPrecomposeSelection(layers);
                }
            }));

            return;
        }

        this.doPrecomposeSelection(layers);
    }

    private void doPrecomposeSelection(List<CompLayer> layers)
    {
        this.beginAction("预合成所选");

        CompLayer precomp = dev.bbsae.comp.CompLayerOps.precompose(this.project, this.doc, layers, "预合成");

        if (precomp == null)
        {
            this.setStatus("预合成失败：所选图层没有内容");
            return;
        }

        this.selectedIds.clear();
        this.selectedIds.add(precomp.id);
        this.applySelection(precomp);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已把 " + layers.size() + " 个图层合成预渲染：" + precomp.name);
    }

    /** Group under a fresh null so the selection can be transformed as one. */
    public void groupSelection()
    {
        if (this.doc == null)
        {
            return;
        }

        List<CompLayer> layers = this.getSelectedLayers();

        if (layers.size() < 2)
        {
            this.setStatus("编组需要至少两个图层");
            return;
        }

        this.beginAction("编组");

        CompLayer group = dev.bbsae.comp.CompLayerOps.groupUnderNull(this.doc, layers);

        if (group == null)
        {
            return;
        }

        this.selectedIds.clear();
        this.selectedIds.add(group.id);
        this.applySelection(group);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已编组到空对象，拖动空对象即可整体移动");
    }

    public void alignSelection(int edge)
    {
        if (this.doc == null)
        {
            return;
        }

        this.beginAction("对齐");

        dev.bbsae.comp.CompLayerOps.align(this.doc, this.getSelectedLayers(), edge);
        this.refreshInspector();
        this.save();
    }

    public void distributeSelection(boolean horizontal)
    {
        if (this.doc == null)
        {
            return;
        }

        this.beginAction("分布");

        dev.bbsae.comp.CompLayerOps.distribute(this.doc, this.getSelectedLayers(), horizontal);
        this.refreshInspector();
        this.save();
    }

    public void setSelectionVisible(boolean visible)
    {
        this.beginAction(visible ? "显示所选" : "隐藏所选");

        for (CompLayer layer : this.getSelectedLayers())
        {
            layer.visible = visible;
        }

        this.refreshLayers();
        this.save();
    }

    public void setSelectionLocked(boolean locked)
    {
        this.beginAction(locked ? "锁定所选" : "解锁所选");

        for (CompLayer layer : this.getSelectedLayers())
        {
            layer.locked = locked;
        }

        this.refreshLayers();
        this.refreshInspector();
        this.save();
    }

    /** Compact inspector page shown when more than one layer is selected. */
    private UISection multiSelectionSection()
    {
        UISection section = new UISection(IKey.constant("多选 · " + this.getSelectionCount() + " 个图层"));

        section.fields.add(UI.label(IKey.constant("批量操作作用于所选全部图层；单层属性请只选一个。"), 14));

        UIElement row1 = new UIElement();

        row1.w(1F).h(20);

        UIButton precomp = this.smallButton("合成预渲染", this::precomposeSelection);
        UIButton group = this.smallButton("编组（空对象）", this::groupSelection);

        precomp.relative(row1).x(0).y(1).w(0.5F, -2).h(18);
        group.relative(row1).x(0.5F, 2).y(1).w(0.5F, -2).h(18);
        row1.add(precomp, group);
        section.fields.add(row1);

        UIElement row2 = new UIElement();

        row2.w(1F).h(20);

        UIButton show = this.smallButton("显示", () -> this.setSelectionVisible(true));
        UIButton hide = this.smallButton("隐藏", () -> this.setSelectionVisible(false));
        UIButton lock = this.smallButton("锁定", () -> this.setSelectionLocked(true));

        show.relative(row2).x(0).y(1).w(0.33F, -2).h(18);
        hide.relative(row2).x(0.33F, 1).y(1).w(0.33F, -2).h(18);
        lock.relative(row2).x(0.66F, 2).y(1).w(0.34F, -2).h(18);
        row2.add(show, hide, lock);
        section.fields.add(row2);

        section.fields.add(UI.label(IKey.constant("对齐到合成"), 15));
        section.fields.add(UI.row(
            this.smallButton("左", () -> this.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_LEFT)),
            this.smallButton("水平中", () -> this.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_CENTER_H)),
            this.smallButton("右", () -> this.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_RIGHT))
        ));
        section.fields.add(UI.row(
            this.smallButton("上", () -> this.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_TOP)),
            this.smallButton("垂直中", () -> this.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_CENTER_V)),
            this.smallButton("下", () -> this.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_BOTTOM))
        ));
        section.fields.add(UI.row(
            this.smallButton("水平等距", () -> this.distributeSelection(true)),
            this.smallButton("垂直等距", () -> this.distributeSelection(false))
        ));

        section.fields.add(UI.row(
            this.smallButton("复制所选", this::duplicateSelection),
            this.smallButton("删除所选", this::deleteSelection)
        ));

        for (CompLayer layer : this.getSelectedLayers())
        {
            UIButton jump = new UICompButton(IKey.constant("● " + this.layerLabel(layer) + "  ·  " + layer.type), (button) ->
            {
                this.selectLayer(layer);
                this.trackTimeline.revealTime(layer.in);
            });

            section.fields.add(jump);
        }

        return section;
    }

    public void deleteLayer(CompLayer layer)
    {
        if (this.doc == null || layer == null)
        {
            return;
        }

        this.beginAction("删除图层");
        int index = this.doc.layers.indexOf(layer);

        this.doc.removeLayer(layer.id);
        this.setSingleSelection(this.doc.layers.isEmpty()
            ? null
            : this.doc.layers.get(Math.max(0, Math.min(index, this.doc.layers.size() - 1))));
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    /**
     * Reorder from the timeline's visual row space: row 0 is the top-most layer, i.e. the last
     * entry in {@code doc.layers}. The timeline reverse-maps its rows before calling this.
     */
    public void moveLayerToDisplayIndex(CompLayer layer, int displayIndex)
    {
        if (this.doc == null || layer == null)
        {
            return;
        }

        /* A click on the drag grip (or a drop back onto the same row) asks for a reorder that is
         * already true. beginAction() would push a duplicate checkpoint and clear the redo stack,
         * so the no-op has to be detected before the undo bookkeeping. */
        if (!this.doc.wouldMoveLayerToDisplayIndex(layer, displayIndex))
        {
            return;
        }

        this.beginAction("调整图层顺序");

        if (!this.doc.moveLayerToDisplayIndex(layer, displayIndex))
        {
            return;
        }

        /* Programmatic navigation must keep selectedIds and selectedLayer in sync; assigning the
         * field directly left "删除所选"/"复制所选" acting on a different layer than the inspector. */
        this.setSingleSelection(layer);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    public void moveLayerOrder(CompLayer layer, int direction)
    {
        if (this.doc == null || layer == null || direction == 0)
        {
            return;
        }

        int index = this.doc.layers.indexOf(layer);
        int target = Math.max(0, Math.min(this.doc.layers.size() - 1, index + direction));

        if (index < 0 || target == index)
        {
            return;
        }

        this.beginAction("移动图层顺序");
        this.doc.layers.remove(index);
        this.doc.layers.add(target, layer);
        this.refreshLayers();
        this.refreshInspector();
        this.save();
    }

    public void splitSelectedAtPlayhead()
    {
        if (this.doc == null || this.selectedLayer == null)
        {
            this.setStatus("先在时间轴选择一条轨道");

            return;
        }

        CompLayer layer = this.selectedLayer;
        float fps = Math.max(1F, this.doc.fps);
        float frame = 1F / fps;

        /* Bail out BEFORE beginAction(): a failed split used to push an undo checkpoint and wipe
         * the redo stack, so Ctrl+Z then "undid" into an identical state. This is the same
         * condition CompLayer.splitAt() rejects — checked without mutating the layer. */
        if (this.time <= layer.in + frame || this.time >= layer.out - frame)
        {
            this.setStatus("播放头不在所选轨道的范围内");

            return;
        }

        this.beginAction("拆分图层");
        String id = CompStore.uniqueId(layer.id + "_b", this.doc.layers, (candidate) -> candidate.id);
        CompLayer right = layer.splitAt(this.time, fps, id, this.layerLabel(layer) + " · B");

        this.doc.layers.add(this.doc.layers.indexOf(layer) + 1, right);
        this.setSingleSelection(right);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已在播放头拆分轨道");
    }

    public void nudgeSelected(int frames)
    {
        if (this.doc == null || this.selectedLayer == null || frames == 0)
        {
            return;
        }

        CompLayer layer = this.selectedLayer;

        if (layer.locked)
        {
            this.setStatus("轨道已锁定：先解锁再移动");

            return;
        }

        this.beginAction("微移图层");
        layer.moveTo(layer.in + frames / Math.max(1F, this.doc.fps), Math.max(1F, this.doc.fps), this.doc.duration);
        this.refreshInspector();
        this.save();
    }

    public void applyEffect(String type)
    {
        if (this.doc == null)
        {
            return;
        }

        if (this.selectedLayer == null)
        {
            this.setStatus("先在时间轴选择一条轨道，再应用效果");

            return;
        }

        /* The inspector shows the multi-selection panel when more than one layer is highlighted, so
         * an effect added to the primary in that state had no visible parameter rows at all: the
         * status line said "已添加" and nothing on screen changed. */
        if (this.getSelectionCount() > 1)
        {
            this.setStatus("已选中 " + this.getSelectionCount() + " 个图层：请只选中一个图层再应用效果");

            return;
        }

        if (this.selectedLayer.locked)
        {
            this.setStatus("图层已锁定：先解锁再应用效果");

            return;
        }

        this.beginAction("添加效果");
        CompEffect effect = CompEffectRegistry.create(type, this.selectedLayer.effects.size() + 1);

        effect.id = CompStore.uniqueEffectId(this.selectedLayer);
        this.selectedLayer.effects.add(effect);
        this.recentEffects.remove(type);
        this.recentEffects.addFirst(type);

        while (this.recentEffects.size() > 6)
        {
            this.recentEffects.removeLast();
        }

        if (this.inspectorPage != PAGE_EFFECTS)
        {
            this.setInspectorPage(PAGE_EFFECTS);
        }
        else
        {
            this.refreshInspector();
        }

        /* The new effect is appended at the bottom of the stack. Without moving the view the
         * panel looks unchanged; the status line alone was too far away to notice. */
        this.inspector.scroll.scrollToEnd();

        this.save();
        this.setStatus("已添加效果：" + CompEffectRegistry.displayName(type));
    }

    public List<String> getRecentEffects()
    {
        return this.recentEffects;
    }

    public void focusEffectLibrary()
    {
        /* The effect library is the left column's first tab now. The header button and the
         * full-screen overlay are gone: adding an effect is a left-to-right workflow (browse on
         * the left, edit the applied stack on the right), not a modal detour. */
        this.leftPage = 0;
        this.applyLeftPage(0, true);
        this.revealDockPanel(CompDockSource.PANEL_EFFECTS);
        this.setStatus("在左侧效果库中单击效果即可叠加到当前轨道");
    }

    /** Brings a dock panel back when the user hid it, so a header/library action can still work. */
    private void revealDockPanel(String panelId)
    {
        if (this.dock == null || this.dockSource == null || panelId == null)
        {
            return;
        }

        java.util.Set<String> hidden = this.dockSource.getHiddenPanels();

        if (hidden.remove(panelId))
        {
            this.dockSource.setHiddenPanels(hidden);
            this.dock.refresh();
            this.dockLayoutDirty = true;
        }
    }

    public void setInspectorPage(int page)
    {
        this.inspectorPage = Math.max(PAGE_EFFECTS, Math.min(PAGE_OUTPUT, page));
        this.inspectorTabs.select(inspectorTabForPage(this.inspectorPage));
    }

    /** Time-axis zoom for the keyframe drawer; the title-bar arrows call this. */
    public void zoomKeyframeTime(int direction)
    {
        if (this.keyframeEditor.view == null)
        {
            return;
        }

        mchorse.bbs_mod.ui.utils.Scale axis = this.keyframeEditor.view.getXAxis();
        double duration = Math.max(2D, this.getDurationTicks());
        double min = axis.getMinValue();
        double max = axis.getMaxValue();
        double span = Math.max(1D, max - min);
        double target = span * (direction > 0 ? 0.8D : 1.25D);

        target = Math.max(2D, Math.min(duration, target));

        double center = (min + max) * 0.5D;
        double from = center - target * 0.5D;
        double to = center + target * 0.5D;

        if (from < 0D)
        {
            to -= from;
            from = 0D;
        }

        if (to > duration)
        {
            from -= to - duration;
            to = duration;
        }

        from = Math.max(0D, from);
        axis.view(from, to);
        this.setStatus(String.format(java.util.Locale.ROOT,
            "关键帧时间轴 %s：%.0f–%.0f 帧", direction > 0 ? "放大" : "缩小", from, to));
    }

    /** Applies a dope-sheet row height from Settings or the editor's +/- buttons. */
    public void applyKeyframeRowHeight(int height)
    {
        int clamped = Math.max(12, Math.min(60, height));

        if (this.keyframeEditor.view instanceof UICompositorKeyframes keyframes)
        {
            keyframes.debugSetTrackHeight(clamped);
        }

        if (this.curveEditor != null)
        {
            this.curveEditor.setRowHeight(clamped);
        }
    }

    public void stepKeyframeRowHeight(int delta)
    {
        if (this.keyframeEditor.view instanceof UICompositorKeyframes keyframes)
        {
            double current = this.keyframeEditor.view.getDopeSheet().getTrackHeight();
            int next = (int) Math.round(Math.max(12D, Math.min(60D, current + delta)));

            keyframes.debugSetTrackHeight(next);

            if (this.curveEditor != null)
            {
                this.curveEditor.setRowHeight(next);
            }

            CompPrefs.get().keyframeRowHeight = next;
            CompPrefs.markDirty();
            this.setStatus("关键帧/曲线轨道高度 " + next + "px");
        }
    }

    public void applyTimelineRowHeight(int height)
    {
        this.trackTimeline.setRowHeight(Math.max(18, Math.min(62, height)));
        CompPrefs.get().timelineRowHeight = this.trackTimeline.rowHeight();
        CompPrefs.markDirty();
        this.trackTimeline.resize();
    }

    public void openKeyframes()
    {
        this.setKeyframeOpen(true);
    }

    /** Left column page (effects / assets / presets), used by the tabs and view-state restore. */
    private void applyLeftPage(int index, boolean animate)
    {
        this.leftPage = Math.max(0, Math.min(2, index));
        this.effectLibrary.setVisible(this.leftPage == 0);
        this.assetPanel.setVisible(this.leftPage == 1);
        this.presetBrowser.setVisible(this.leftPage == 2);

        if (animate)
        {
            this.beginPageMotion(this.leftPageMotion, "left:" + this.leftPage);
        }

        if (this.leftPage == 2)
        {
            this.presetBrowser.refresh();
        }

        if (animate)
        {
            this.markViewStateDirty();
        }
    }

    public void toggleKeyframes()
    {
        this.setKeyframeOpen(!this.keyframeOpen);
    }

    private void setKeyframeOpen(boolean open)
    {
        /* Do not early-return when the flag already says "open": switching to the curve page
         * hides the drawer without clearing the flag, and returning to the track page must put
         * the drawer back. The old early return made the button say "open" while the drawer stayed
         * invisible until it was clicked twice. */
        boolean wasOpen = this.keyframeOpen;

        this.keyframeOpen = open;
        this.keyframeOpenRequested = open;
        this.keyframeSlideNanos = System.nanoTime();

        /* Only the closed→open transition replays the slide. selectKeyframe()/navigateKeyframe()
         * call this on every click, and resetting the slide each time made the drawer replay its
         * opening animation (and the sheet rebuild below reset the time zoom) while the user was
         * simply walking keyframes. `slide == 0` also covers the curve-page → track-page return,
         * where the flag stayed true but the drawer was hidden. */
        boolean needsReveal = open && (!wasOpen || this.keyframeDrawerSlide < 0.01F);

        if (needsReveal)
        {
            this.keyframeDrawerSlide = 0F;
            this.drawerSpaceWarned = false;
        }

        if (open)
        {
            /* The drawer has a hard minimum height (header + a few track rows + the editor). If the
             * timeline panel is shorter than that, enlarging it is the only way to display the
             * drawer; opening it into 30 px would look like the click did nothing. */
            this.ensureKeyframeDrawerSpace();
        }

        if (!open)
        {
            this.drawerSpaceWarned = false;
            /* The keyframe properties panel is parented to the inspector, not to the drawer.
             * Hiding the drawer used to leave that panel covering Effect Controls with no way
             * to dismiss it. Clearing the picked keyframe removes it with the drawer. */
            this.keyframeEditor.view.pickKeyframe(null);
        }

        this.layoutKeyframeDrawer();
        this.markViewStateDirty();

        if (open)
        {
            if (needsReveal)
            {
                this.primedWidth = -1;
                this.primeTimeline();
            }

            this.keyframeEditor.resize();
        }

        this.updateToolbarLabels();
        this.timeline.resize();
        this.resize();

        if (open && this.keyframeViewNeedsFit && this.keyframeEditor.area.w > 0)
        {
            this.keyframeEditor.view.resize();
            this.keyframeEditor.view.getXAxis().view(0D, Math.max(1, this.getDurationTicks()));
            this.keyframeViewNeedsFit = false;
        }
    }

    /** Top of the timeline content (below the strip header) in the current layout engine. */
    private int timelineContentTop()
    {
        return TIMELINE_HEADER_H + this.timelineHeaderTop();
    }

    /**
     * Dock splitter handles are 14 px wide and centred on the seam: 7 px of each panel edge is a
     * resize hot zone. The timeline strip starts 6 px lower in dock mode so its toolbar buttons do
     * not own pixels the splitter consumes.
     */
    private int timelineHeaderTop()
    {
        return this.dock != null ? TIMELINE_DOCK_INSET : 0;
    }

    private void layoutKeyframeDrawer()
    {
        if (this.timeline == null || this.trackTimeline == null || this.keyframeHandle == null)
        {
            return;
        }

        /* The curve editor owns the whole strip; the keyframe drawer belongs to the track view. */
        int quickWidth = this.dock != null && this.quickPanel != null && this.quickPanel.isVisible()
            ? Math.max(0, CompUi.quickWidth(this.timeline.area.w, this.quickPanelWidth))
            : 0;
        int stripWidth = Math.max(1, this.timeline.area.w - quickWidth);
        int top = this.timelineContentTop();

        if (quickWidth > 0)
        {
            this.quickPanel.relative(this.timeline).x(1F, -quickWidth).y(top)
                .w(quickWidth).h(1F, -top);
        }

        if (this.timelinePage != 0)
        {
            if (this.curveEditor != null)
            {
                this.curveEditor.relative(this.timeline).x(0).y(top).w(stripWidth).h(1F, -top);
            }
            this.keyframeDrawer.setVisible(false);
            this.keyframeHandle.setVisible(false);
            this.keyframeDrawerSlide = 0F;

            return;
        }

        int timelineHeight = this.timelineLayoutHeight();

        /* The drawer must leave room for the strip header (plus the dock top inset), the track
         * ruler, three track rows and the 5 px splitter. The old "timelineHeight - 96" cap ignored
         * the header: in focus mode the timeline is only ~16 % of the dock, so opening the drawer
         * produced a negative track height (the tracks vanished and the drawer covered the
         * toolbar). */
        int max = this.keyframeDrawerMaxHeight(timelineHeight);

        if (this.keyframeOpen && max < KEYFRAME_DRAWER_MIN)
        {
            /* Dock ratios are user-controlled, and the default 72/28 split leaves the timeline
             * ~125 px on a 960×540 UI: less than the drawer minimum. Grow the timeline panel (a
             * layout can and must honour a hard minimum) and re-measure; do not flip the open flag
             * off, because that made the button lie and made a resize unable to reveal the drawer. */
            this.ensureKeyframeDrawerSpace();
            timelineHeight = this.timelineLayoutHeight();
            max = this.keyframeDrawerMaxHeight(timelineHeight);
        }

        if (this.keyframeOpen && max < KEYFRAME_DRAWER_MIN && !this.drawerSpaceWarned)
        {
            this.drawerSpaceWarned = true;
            this.setStatus("时间轴太矮，关键帧抽屉只能显示 " + max + "px；拖高时间轴可完整显示");
        }
        else if (max >= KEYFRAME_DRAWER_MIN)
        {
            this.drawerSpaceWarned = false;
        }

        /* Keep the preferred height across short layouts: clamping the stored value to the current
         * maximum made the drawer permanently 96 px after one too-short frame. The visible height
         * is the minimum of the preference and the room that actually exists. */
        this.keyframeDrawerHeight = Math.max(KEYFRAME_DRAWER_MIN, this.keyframeDrawerHeight);

        float slide = Math.max(0F, Math.min(1F, this.keyframeDrawerSlide));
        int shownHeight = Math.round(Math.min(this.keyframeDrawerHeight, max) * slide);

        /* Visibility is a function of the page and the open flag here, not a one-shot side effect
         * in setKeyframeOpen(). The page switch sets visible=false; restoring it here is what makes
         * "switch tabs and come back" reliable. */
        this.keyframeDrawer.setVisible(shownHeight > 1);
        this.keyframeHandle.setVisible(shownHeight > 1);

        if (shownHeight > 1)
        {
            this.trackTimeline.relative(this.timeline).x(0).y(top).w(stripWidth).h(1F, -top - shownHeight);
            this.keyframeDrawer.relative(this.timeline).x(0).y(1F, -shownHeight).w(stripWidth).h(shownHeight);

            /* The splitter sits entirely above the drawer, not on its title bar. With the old
             * "-shownHeight - 2" the top 3 px of the title bar (and the top of the close button)
             * started a resize drag instead of pressing the control. */
            this.keyframeHandle.relative(this.timeline).x(0).y(1F, -shownHeight - 5).w(stripWidth).h(5);
        }
        else
        {
            this.trackTimeline.relative(this.timeline).x(0).y(top).w(stripWidth).h(1F, -top);
        }

        this.keyframeEditor.setVisible(shownHeight > 30);
        this.keyframeEditor.relative(this.keyframeDrawer).x(0).y(22).w(1F).h(Math.max(0, shownHeight - 22));

        if (shownHeight > 30)
        {
            /* A drawer that was hidden while the page was elsewhere has no meaningful area until
             * it is laid out and resized. Do both here so the next draw cannot see 0x0. */
            this.keyframeEditor.resize();

            if (this.keyframeEditor.area.w > 0 && this.keyframeViewNeedsFit)
            {
                this.keyframeEditor.view.resize();
                this.keyframeEditor.view.getXAxis().view(0D, Math.max(1, this.getDurationTicks()));
                this.keyframeViewNeedsFit = false;
            }
        }
    }

    /** Pixel height of the timeline panel in whichever layout engine is active. */
    private int timelineLayoutHeight()
    {
        return this.dock != null ? Math.max(0, this.timeline.area.h) : this.timelinePanelHeight;
    }

    /** Width the legacy column splitters may divide: the body, or the dock timeline in dock mode. */
    private int layoutBodyWidth()
    {
        if (this.body != null)
        {
            return Math.max(1, this.body.area.w);
        }

        if (this.timeline != null && this.timeline.area.w > 0)
        {
            return this.timeline.area.w;
        }

        return Math.max(1, this.area.w);
    }

    /** Room left for the drawer after the strip header, the visible tracks and the splitter. */
    private int keyframeDrawerMaxHeight(int timelineHeight)
    {
        return Math.max(0, timelineHeight - this.timelineContentTop() - this.keyframeDrawerTrackReserve() - 5);
    }

    /**
     * Pixels the track lane must keep above the drawer: its ruler plus three full rows.
     *
     * <p>The old constant was 56 px, which is less than ruler (20) + two default rows (52). A drawer
     * opening at the "minimum" therefore covered half of the second track — the user saw the
     * drawer "eat" the tracks. Row height is user-adjustable, so this is measured, not assumed.</p>
     */
    private int keyframeDrawerTrackReserve()
    {
        int row = KEYFRAME_TRACK_ROW_FALLBACK;

        if (this.trackTimeline != null)
        {
            row = Math.max(18, this.trackTimeline.rowHeight());
        }

        return KEYFRAME_TRACK_RULER + row * 3;
    }

    /**
     * Makes the timeline panel tall enough to hold the open keyframe drawer.
     *
     * <p>In dock mode this rewrites the outermost vertical split ratio on the timeline's path and
     * lets {@code UIDockLayout} re-run its flex pass; in the legacy fixed layout it raises the
     * persisted timeline height (still capped by {@link CompUi#fitTimelineHeight}). The call is
     * idempotent and guarded against the dock's own layout pass, and a failed attempt is not
     * retried for the same geometry.</p>
     */
    private void ensureKeyframeDrawerSpace()
    {
        if (!this.keyframeOpen || this.timelinePage != 0 || this.timeline == null
            || this.project == null || this.renderView || this.drawerSpacePass)
        {
            return;
        }

        /* Never rewrite the dock tree from inside the dock's own flex pass, and never while the
         * user is dragging a dock splitter: refresh() would clear that drag. Recording the attempt
         * in either case would also suppress the retry on the next frame, when it is safe. */
        if (this.dock != null && (this.layoutPass || this.dock.isAnySplitterDragging()))
        {
            return;
        }

        int current = this.timelineLayoutHeight();
        int required = this.timelineContentTop() + this.keyframeDrawerTrackReserve() + 5
            + Math.max(KEYFRAME_DRAWER_MIN, this.keyframeDrawerHeight);
        int body = this.dock != null ? this.dock.area.h : this.area.h;

        if (current <= 0 || body <= 0 || current >= required - 2)
        {
            return;
        }

        if (current == this.drawerGrowTriedHeight && required == this.drawerGrowTriedRequired
            && body == this.drawerGrowTriedBody)
        {
            return;
        }

        this.drawerGrowTriedHeight = current;
        this.drawerGrowTriedRequired = required;
        this.drawerGrowTriedBody = body;
        this.drawerSpacePass = true;

        try
        {
            if (this.dock == null)
            {
                int target = CompUi.fitTimelineHeight(this.area.h, required);

                if (target > this.timelinePanelHeight)
                {
                    this.timelinePanelHeight = target;
                    this.applyLegacyLayout();
                }
            }
            else if (this.dockSource != null)
            {
                EditorLayoutNode root = this.dock.getLayoutRoot();
                EditorLayoutNode grown = CompDockSource.growPanelHeight(root,
                    CompDockSource.PANEL_TIMELINE, current, required, body);

                if (grown != null && grown != root)
                {
                    this.dockSource.setRoot(grown);
                    this.dock.refresh();
                    this.dockLayoutDirty = true;
                    this.primedWidth = -1;
                }
            }
        }
        finally
        {
            this.drawerSpacePass = false;
        }
    }

    /** Per-frame slide update; the drawer is a pure function of keyframeOpen + slide. */
    private void tickKeyframeDrawerAnimation()
    {
        float target = this.keyframeOpen ? 1F : 0F;

        if (Math.abs(this.keyframeDrawerSlide - target) < 0.002F)
        {
            if (this.keyframeDrawerSlide != target)
            {
                this.keyframeDrawerSlide = target;
                this.layoutKeyframeDrawer();
                this.timeline.resize();
                this.keyframeDrawer.resize();
                this.keyframeHandle.resize();
            }

            return;
        }

        long now = System.nanoTime();
        float dt = this.keyframeSlideNanos == 0L ? 0.016F
            : Math.min(0.05F, (now - this.keyframeSlideNanos) / 1_000_000_000F);

        this.keyframeSlideNanos = now;
        this.keyframeDrawerSlide = UiMotion.approach(this.keyframeDrawerSlide, target,
            target > this.keyframeDrawerSlide ? 9F : 14F, dt);

        this.layoutKeyframeDrawer();
        this.timeline.resize();
        this.keyframeDrawer.resize();
        this.keyframeHandle.resize();
    }

    /* ------------------------------------------------------------- BBS camera flight */

    /** True when the selected (or active) camera is a BBS-style fly camera. */
    public boolean canFlyCamera()
    {
        CompLayer camera = this.selectedLayer != null && this.selectedLayer.isCamera()
            ? this.selectedLayer
            : (this.doc == null ? null : this.doc.activeCamera(this.time));

        return camera != null && camera.cameraStyle == CompLayer.CAMERA_BBS;
    }

    public boolean isCameraFlying()
    {
        return this.cameraFlying;
    }

    /**
     * Switches a camera layer between the AE target model and the BBS free-flight model without
     * destroying either set of keyframes. The current pose is converted so the first frame after
     * the switch does not jump.
     */
    public void applyCameraStyle(CompLayer layer, int style)
    {
        if (layer == null)
        {
            return;
        }

        if (this.cameraFlying && this.flightCamera == layer)
        {
            this.setCameraFlying(false);
        }

        float frame = this.getFrame();

        this.beginAction(style == CompLayer.CAMERA_BBS ? "切换 BBS 摄像机" : "切换 AE 摄像机");

        if (style == CompLayer.CAMERA_BBS)
        {
            float tx = layer.scalarAt("cameraTargetX", layer.cameraTargetX, frame);
            float ty = layer.scalarAt("cameraTargetY", layer.cameraTargetY, frame);
            float tz = layer.scalarAt("cameraTargetZ", layer.cameraTargetZ, frame);
            float z = layer.scalarAt("cameraZ", layer.cameraZ, frame);

            if (Math.abs(z) < 0.001F)
            {
                z = layer.scalarAt("z", layer.z, frame);
            }

            if (Math.abs(z) < 0.001F)
            {
                z = this.defaultCameraZ();
            }

            Transform transform = layer.transformAt(frame);
            float dx = tx - transform.translate.x;
            float dy = ty - transform.translate.y;
            float dz = tz - z;
            float horizontal = (float) Math.sqrt(dx * dx + dz * dz);

            layer.cameraStyle = CompLayer.CAMERA_BBS;
            layer.cameraLookAt = false;
            layer.camera3D = true;
            layer.cameraZ = z;
            layer.z = z;
            layer.rotationY = (float) Math.toDegrees(Math.atan2(dx, -dz));
            layer.rotationX = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001F, horizontal)));
        }
        else
        {
            Transform transform = layer.transformAt(frame);
            float yaw = (float) Math.toRadians(layer.scalarAt("rotationY", layer.rotationY, frame));
            float pitch = (float) Math.toRadians(layer.scalarAt("rotationX", layer.rotationX, frame));
            float z = layer.scalarAt("cameraZ", layer.cameraZ, frame);
            float cp = (float) Math.cos(pitch);

            if (Math.abs(z) < 0.001F)
            {
                z = layer.scalarAt("z", layer.z, frame);
            }

            if (Math.abs(z) < 0.001F)
            {
                z = this.defaultCameraZ();
            }

            layer.cameraStyle = CompLayer.CAMERA_AE;
            layer.cameraLookAt = true;
            layer.camera3D = true;
            layer.cameraZ = z;
            layer.z = z;
            layer.cameraTargetX = transform.translate.x + (float) (Math.sin(yaw) * cp * 1000F);
            layer.cameraTargetY = transform.translate.y - (float) (Math.sin(pitch) * 1000F);
            layer.cameraTargetZ = z - (float) (Math.cos(yaw) * cp * 1000F);
            layer.putScalar("cameraTargetX", layer.cameraTargetX);
            layer.putScalar("cameraTargetY", layer.cameraTargetY);
            layer.putScalar("cameraTargetZ", layer.cameraTargetZ);
        }

        this.save();
        this.refreshInspector();
        this.setStatus(style == CompLayer.CAMERA_BBS
            ? "BBS 原版摄像机：F 进入飞行并用鼠标转向；预览右键拖动仍是 3D 视口环绕，不是旋转镜头"
            : "已切换到 AE 摄像机；目标点已根据当前朝向重建");
    }

    /**
     * Enters/leaves the BBS-style flight camera.
     *
     * <p>Flight is a view authoring gesture, not a separate renderer: every frame writes the
     * ordinary position/rotation/fov keyframes, so the same camera is evaluated by the normal
     * {@link CompCamera} path during preview and export. Leaving flight writes one final key at
     * the playhead, which is the "keyframe where I stopped" behaviour users expect from BBS.</p>
     */
    public void setCameraFlying(boolean flying)
    {
        if (flying)
        {
            CompLayer camera = this.selectedLayer != null && this.selectedLayer.isCamera()
                ? this.selectedLayer
                : (this.doc == null ? null : this.doc.activeCamera(this.time));

            if (camera == null || camera.cameraStyle != CompLayer.CAMERA_BBS)
            {
                this.setStatus("请先在摄像机设置里切换到 BBS 原版摄像机");
                return;
            }

            this.pause();
            this.beginAction("BBS 摄像机飞行");
            this.flightCamera = camera;
            this.doc.activeCameraId = camera.id;
            this.selectLayer(camera);
            camera.cameraLookAt = false;
            camera.camera3D = true;
            camera.ensureIndependentAxes();

            float frame = this.getFrame();

            /* Base fields must be the interpolated pose, not the last authored value: with an X/Y
             * animation, pressing F at a mid-timeline frame snapped the camera to the old base
             * position (and then wrote that wrong position as a keyframe). */
            Transform currentPose = camera.transformAt(frame);

            camera.x = currentPose.translate.x;
            camera.y = currentPose.translate.y;
            camera.rotationX = camera.scalarAt("rotationX", camera.rotationX, frame);
            camera.rotationY = camera.scalarAt("rotationY", camera.rotationY, frame);
            camera.cameraZ = camera.scalarAt("cameraZ", camera.cameraZ, frame);
            camera.cameraFov = camera.scalarAt("cameraFov", camera.cameraFov, frame);

            if (Math.abs(camera.cameraZ) < 1F)
            {
                camera.cameraZ = this.defaultCameraZ();
                camera.z = camera.cameraZ;
            }

            this.keyCameraPose(camera);
            this.cameraFlying = true;
            this.cameraPreviewMode = true;
            this.flightLastMouseX = Integer.MIN_VALUE;
            this.flightLastNanos = System.nanoTime();
            this.flightSpeed = Math.max(80F, Math.min(8000F, Math.abs(camera.cameraZ) * 0.55F));

            try
            {
                mchorse.bbs_mod.graphics.window.Window.setCursorHidden("bbsae_bbs_flight", true);
            }
            catch (RuntimeException ignored)
            {}

            this.refreshInspector();
            this.setStatus("BBS 飞行模式：WASD 移动，空格/Shift 升降，鼠标转动视角，滚轮调速，F 退出并打关键帧");
        }
        else if (this.cameraFlying)
        {
            this.cameraFlying = false;
            this.flightCamera = this.flightCamera == null ? this.doc.activeCamera(this.time) : this.flightCamera;

            if (this.flightCamera != null)
            {
                this.keyCameraPose(this.flightCamera);
                this.openKeyframes();
                this.onTrackEdited();
            }

            try
            {
                mchorse.bbs_mod.graphics.window.Window.setCursorHidden("bbsae_bbs_flight", false);
            }
            catch (RuntimeException ignored)
            {}

            this.flightCamera = null;
            this.flightLastMouseX = Integer.MIN_VALUE;
            this.refreshInspector();
            this.save();
            this.setStatus("已退出 BBS 飞行模式；当前位置已写入关键帧");
        }
    }

    private void keyCameraPose(CompLayer camera)
    {
        float frame = this.getFrame();

        camera.ensureIndependentAxes();
        camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_X, frame, camera.x);
        camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_Y, frame, camera.y);
        camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_ROTATION, frame, camera.scalarAt("rotation", camera.rotation, frame));
        camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_ROTATION_X, frame, camera.rotationX);
        camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_ROTATION_Y, frame, camera.rotationY);
        camera.putScalar("cameraZ", camera.cameraZ);
        camera.putScalar("cameraFov", camera.cameraFov);
        camera.scalarChannel("cameraZ").insert(frame, camera.cameraZ);
        camera.scalarChannel("cameraFov").insert(frame, camera.cameraFov);
    }

    /** Polls the physical keyboard/mouse while flying. Called once per rendered frame. */
    private void updateCameraFlight(UIContext context)
    {
        CompLayer camera = this.flightCamera;

        if (!this.cameraFlying || camera == null || this.doc == null)
        {
            return;
        }

        long now = System.nanoTime();
        float dt = this.flightLastNanos == 0L ? 0.016F : Math.min(0.08F, (now - this.flightLastNanos) / 1.0E9F);

        this.flightLastNanos = now;

        /* Minecraft hands the pointer back whenever a screen opens; flight outlives that, so the
         * hidden-cursor request is renewed every frame exactly like BBS's UIOrbitCamera. */
        try
        {
            Window.setCursorHidden("bbsae_bbs_flight", true);
        }
        catch (RuntimeException ignored)
        {}

        /* Mouse look. The pointer is hidden by BBS's shared cursor owner; clamping a large jump
         * prevents a window re-entry from spinning the camera by hundreds of degrees. */
        if (this.flightLastMouseX != Integer.MIN_VALUE)
        {
            int dx = context.mouseX - this.flightLastMouseX;
            int dy = context.mouseY - this.flightLastMouseY;

            if (Math.abs(dx) < 160 && Math.abs(dy) < 160)
            {
                float yaw = camera.scalarAt("rotationY", camera.rotationY, this.getFrame());
                float pitch = camera.scalarAt("rotationX", camera.rotationX, this.getFrame());

                yaw += dx * 0.18F;
                pitch = Math.max(-89F, Math.min(89F, pitch - dy * 0.18F));
                camera.rotationY = yaw;
                camera.rotationX = pitch;
                camera.putScalar("rotationY", yaw);
                camera.putScalar("rotationX", pitch);
            }
        }

        this.flightLastMouseX = context.mouseX;
        this.flightLastMouseY = context.mouseY;

        boolean forward = Window.isKeyPressed(GLFW.GLFW_KEY_W) || Window.isKeyPressed(GLFW.GLFW_KEY_UP);
        boolean back = Window.isKeyPressed(GLFW.GLFW_KEY_S) || Window.isKeyPressed(GLFW.GLFW_KEY_DOWN);
        boolean left = Window.isKeyPressed(GLFW.GLFW_KEY_A) || Window.isKeyPressed(GLFW.GLFW_KEY_LEFT);
        boolean right = Window.isKeyPressed(GLFW.GLFW_KEY_D) || Window.isKeyPressed(GLFW.GLFW_KEY_RIGHT);
        boolean up = Window.isKeyPressed(GLFW.GLFW_KEY_SPACE);
        boolean down = Window.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT);

        float yaw = (float) Math.toRadians(camera.scalarAt("rotationY", camera.rotationY, this.getFrame()));
        float pitch = (float) Math.toRadians(camera.scalarAt("rotationX", camera.rotationX, this.getFrame()));
        float cp = (float) Math.cos(pitch);
        float fx = (float) (Math.sin(yaw) * cp);
        float fy = (float) (-Math.sin(pitch));
        float fz = (float) (-Math.cos(yaw) * cp);
        float rx = (float) Math.cos(yaw);
        float rz = (float) Math.sin(yaw);
        float speed = this.flightSpeed * dt * (CompInput.ctrlDown() ? 3F : CompInput.altDown() ? 0.3F : 1F);
        float mx = (forward ? 1F : 0F) - (back ? 1F : 0F);
        float my = (right ? 1F : 0F) - (left ? 1F : 0F);
        float mz = (up ? 1F : 0F) - (down ? 1F : 0F);

        camera.x += fx * mx * speed + rx * my * speed;
        camera.y += fy * mx * speed - mz * speed;
        camera.cameraZ += fz * mx * speed + rz * my * speed;
        camera.z = camera.cameraZ;

        if (Math.abs(camera.cameraZ) < 1F)
        {
            camera.cameraZ = camera.cameraZ < 0F ? -1F : 1F;
            camera.z = camera.cameraZ;
        }

        this.keyCameraPose(camera);

        /* Flight writes the pose every frame without going through save(); green pre-rendered
         * frames only see markDirty(), so without this the preview froze on the old cached frame
         * until flight ended (and then jumped). */
        dev.bbsae.client.render.CompPrecache.markDirty();
    }

    private boolean hasDepthEffects()
    {
        if (this.doc == null)
        {
            return false;
        }

        for (CompLayer layer : this.doc.layers)
        {
            if (layer == null || layer.effects == null)
            {
                continue;
            }

            for (CompEffect effect : layer.effects)
            {
                if (effect != null && effect.enabled && effect.type != null && effect.type.startsWith("depth_"))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private String layerFilterLabel(CompLayer layer)
    {
        int mode = TextureFilter.layerMode(layer);

        return mode == TextureFilter.SOURCE_INHERIT
            ? "纹理过滤：跟随工程（" + TextureFilter.name(TextureFilter.projectMode(this.project)) + "）"
            : "纹理过滤：" + TextureFilter.name(mode);
    }

    /** Public hook for the settings page after changing the automatic/linear/nearest policy. */
    public void refreshTextureFilterUi()
    {
        this.updateFilterButton();
        this.refreshInspector();
        this.setStatus(this.describeTextureFilter());
    }

    /** Creates one row of the filter dropdown. */
    private UICompButton filterOption(String label, int mode, int y)
    {
        UICompButton button = new UICompButton(IKey.constant(label), (b) -> this.applyPreviewFilter(mode));

        button.relative(this.previewFilterMenu).x(2).y(y).w(1F, -4).h(18);
        button.tooltip(IKey.constant(TextureFilter.describe(mode)));

        return button;
    }

    /** Expands/folds the filter group. The chip always shows the active value. */
    private void toggleFilterMenu()
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        this.previewFilterExpanded = !this.previewFilterExpanded;
        this.project.settings.previewFilterExpanded = this.previewFilterExpanded;
        this.applyPreviewBarState();
        this.save();

        int mode = TextureFilter.projectMode(this.project);

        this.setStatus(this.previewFilterExpanded
            ? "选择纹理过滤方式（当前 " + TextureFilter.name(mode) + "）"
            : "纹理过滤：" + TextureFilter.name(mode));
    }

    /** Applies one explicit filter policy. Unlike the old cycle, every choice is one click away. */
    private void applyPreviewFilter(int mode)
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        int resolved = Math.max(TextureFilter.AUTO, Math.min(TextureFilter.NEAREST, mode));

        this.project.settings.textureFilter = resolved;
        this.updateFilterButton();
        this.applyPreviewBarState();
        this.save();
        this.setStatus("纹理过滤：" + TextureFilter.name(resolved) + " — " + TextureFilter.describe(resolved));
    }

    /** Refreshes the preview-bar texture-filter chip from the project settings. */
    private void updateFilterButton()
    {
        if (this.previewFilter == null)
        {
            return;
        }

        int mode = TextureFilter.projectMode(this.project);

        this.previewFilter.label = IKey.constant("线性: " + TextureFilter.name(mode));
        this.previewFilter.tooltip(IKey.constant(TextureFilter.describe(mode)
            + "。点击展开 自动 / 线性 / 最近邻，再点收起；自动的检测结果也会显示在设置 → 界面布局 中。"));

        UICompButton[] options = {this.previewFilterAuto, this.previewFilterLinear, this.previewFilterNearest};

        for (int i = 0; i < options.length; i++)
        {
            if (options[i] != null)
            {
                options[i].label = IKey.constant((i == mode ? "✓ " : "") + TextureFilter.name(i));
            }
        }
    }

    /**
     * Human-readable automatic-detection summary for the settings page. It scans only image and
     * footage layers of the current composition; textures that are not loaded yet are counted as
     * "pending", never silently reported as low resolution.
     */
    public String describeTextureFilter()
    {
        CompProject.CompSettings settings = this.project == null ? null : this.project.settings;
        int mode = settings == null ? TextureFilter.AUTO : settings.textureFilter;

        if (mode != TextureFilter.AUTO)
        {
            return "当前：" + TextureFilter.name(mode) + "（所有图片/序列层）";
        }

        int high = 0;
        int pixel = 0;
        int pending = 0;

        if (this.doc != null)
        {
            for (CompLayer layer : this.doc.layers)
            {
                if (layer == null || (!"image".equals(layer.type) && !"footage".equals(layer.type)))
                {
                    continue;
                }

                ImageTextureCache.Entry image = ImageTextureCache.get(
                    this.project == null ? CompStore.DEFAULT_PROJECT : this.project.id, layer.imagePath);

                if (image == null)
                {
                    pending++;
                }
                else if (TextureFilter.isHighResolution(image.width, image.height))
                {
                    high++;
                }
                else
                {
                    pixel++;
                }
            }
        }

        return "自动：高分辨率 " + high + " · 像素素材 " + pixel
            + (pending > 0 ? " · 待检测 " + pending : "");
    }

    private void updateToolbarLabels()
    {
        this.snapButton.label = IKey.constant(this.trackTimeline.isSnapping() ? "吸附 开" : "吸附 关");
        this.keyframeButton.label = IKey.constant(this.keyframeOpen ? "关键帧 ▾" : "关键帧 ▸");
        this.keyframeTitle.label = IKey.constant("属性关键帧" + (this.selectedLayer == null ? "" : " · " + this.layerLabel(this.selectedLayer)));
        this.updatePrecacheLabel();
    }

    /**
     * Starts or stops a pre-render pass.
     *
     * <p>Range: the work area when it is narrower than the composition, otherwise the whole
     * composition. That is the AE behaviour — you mark the part you are working on, pre-render
     * it, and the ruler turns green there.</p>
     */
    private void togglePrecache()
    {
        if (this.doc == null || this.project == null)
        {
            return;
        }

        if (dev.bbsae.client.render.CompPrecache.isRunning())
        {
            dev.bbsae.client.render.CompPrecache.stop();
            this.setStatus("预渲染已停止");
            this.updatePrecacheLabel();

            return;
        }

        if (dev.bbsae.client.render.CompPrecache.dependsOnLiveWorld(this.doc))
        {
            this.setStatus("合成依赖实时世界（BBS 预览层或深度效果），无法缓存");
            this.notifyError("含 BBS 预览层或深度效果的合成不能预渲染（画面依赖实时世界）");

            return;
        }

        float fps = Math.max(1F, this.doc.fps);
        int total = Math.max(1, Math.round(this.doc.duration * fps));
        int from = 0;
        int to = total;
        float in = this.doc.workAreaIn;
        float out = this.doc.workAreaOut;

        if (out > in + 0.01F && out - in < this.doc.duration - 0.01F)
        {
            from = Math.max(0, Math.round(in * fps));
            to = Math.min(total, Math.round(out * fps));
        }

        dev.bbsae.client.render.CompPrecache.start(this.project, this.doc, from, to);
        this.setStatus(String.format(java.util.Locale.ROOT, "预渲染 %d 帧（%.1fs–%.1fs）", to - from, from / fps, to / fps));
        this.updatePrecacheLabel();
    }

    /** Progress label: the button doubles as the green-bar readout. */
    private void updatePrecacheLabel()
    {
        if (this.precacheButton == null)
        {
            return;
        }

        if (dev.bbsae.client.render.CompPrecache.isRunning())
        {
            float progress = dev.bbsae.client.render.CompPrecache.progress(this.project, this.doc);

            this.precacheButton.label = IKey.constant("停止 " + Math.round(progress * 100F) + "%");

            return;
        }

        int cached = dev.bbsae.client.render.CompPrecache.cachedCount(this.project, this.doc);

        this.precacheButton.label = IKey.constant(cached > 0 ? "预渲染 " + cached : "预渲染");
    }

    private void toggleRenderView()
    {
        this.renderView = !this.renderView;
        this.renderMode.label = IKey.constant(this.renderView ? "返回编辑" : "渲染");

        if (this.dock != null)
        {
            /* Dock mode: the dock hides as a whole; the render queue page takes the same body
             * rectangle. Panel visibility inside the dock is preserved for the return trip. */
            this.applyLayout();
            this.applyTimelinePage();

            return;
        }

        this.body.setVisible(!this.renderView);
        this.timeline.setVisible(!this.renderView);
        this.renderPanel.setVisible(this.renderView);
        this.effectColumn.setVisible(!this.renderView && !this.focusMode);
        this.inspectorColumn.setVisible(!this.renderView && !this.focusMode);
        this.renderPanel.resize();
        this.applyLayout();
        this.applyTimelinePage();
    }

    private void toggleFocusMode()
    {
        this.focusMode = !this.focusMode;

        if (this.dock != null)
        {
            this.applyDockFocusMode();

            return;
        }

        this.effectColumn.setVisible(!this.focusMode && !this.renderView);
        this.inspectorColumn.setVisible(!this.focusMode && !this.renderView);
        this.applyLayout();
    }

    /**
     * Focus mode in dock mode: a transient preview-over-timeline tree.
     *
     * <p>Simply hiding the side panel elements would leave empty dock slots; the layout tree has to
     * change. The previous tree/hidden set are remembered for the return trip and are not written
     * into the project layout (focus is a view, not an edit).</p>
     */
    private void applyDockFocusMode()
    {
        if (this.dock == null || this.dockSource == null)
        {
            return;
        }

        if (this.focusMode)
        {
            this.focusReturnRoot = this.dock.getLayoutRoot();
            this.focusReturnHidden = this.dockSource.getHiddenPanels();

            /* The focus tree is a session view: suspend persistence before replacing the tree so
             * the project file can never end up with the side panels hidden. Without this the
             * debounced flush (dockLayoutDirty → flushDockLayout) wrote the focus tree into
             * CompSettings.dockLayout/dockHiddenPanels, and reopening the project showed only
             * preview + timeline ("my panels disappeared"). */
            this.dockSource.suspendPersist(true);
            this.dockSource.setRoot(CompDockSource.focusLayout());

            java.util.Set<String> hidden = this.dockSource.getHiddenPanels();

            hidden.add(CompDockSource.PANEL_EFFECTS);
            hidden.add(CompDockSource.PANEL_INSPECTOR);
            this.dockSource.setHiddenPanels(hidden);
            this.dock.refresh();
            this.setStatus("聚焦预览：按 F 或右上角按钮恢复面板布局");
        }
        else
        {
            if (this.focusReturnRoot != null)
            {
                this.dockSource.setRoot(this.focusReturnRoot);
            }

            if (this.focusReturnHidden != null)
            {
                this.dockSource.setHiddenPanels(this.focusReturnHidden);
            }

            this.focusReturnRoot = null;
            this.focusReturnHidden = null;
            this.dock.refresh();

            /* Restored tree equals the persisted one again; resume normal persistence. */
            this.dockSource.suspendPersist(false);
            this.setStatus("已恢复面板布局");
        }

        this.dockLayoutDirty = false;
        this.syncDockDerivedLayout();
    }

    /**
     * Panel layout entry point. Two engines share the same chrome:
     *
     * <ul>
     *   <li><b>dock</b> (default) — BBS's {@code UIDockLayout} owns the four panel rectangles:
     *       splitter drag, drag-to-dock, tab stacks, hidden panels. Only the header, status and
     *       render-queue page are positioned here.</li>
     *   <li><b>legacy</b> — the fixed three-column + timeline splitter layout, kept behind
     *       Settings → 开发者 for projects/users that prefer it (and as a safety net).</li>
     * </ul>
     */
    private void applyLayout()
    {
        if (this.preview == null || this.timeline == null)
        {
            return;
        }

        this.layoutHeader();
        this.updateFilterButton();

        if (this.dock != null)
        {
            this.applyDockLayout();
        }
        else
        {
            this.applyLegacyLayout();
        }
    }

    /** Shows the transport controls only while the replay workspace is active. */
    private void layoutModeBar()
    {
        boolean replay = this.workspaceMode == WORKSPACE_REPLAY;

        this.modeTabs.relative(this.modeBar).x(8).y(2).w(112).h(18);
        this.replayPlayButton.setVisible(replay);
        this.replayTimecode.setVisible(replay);
        this.replayPrev.setVisible(replay);
        this.replayNext.setVisible(replay);
        this.replayAll.setVisible(replay);
        this.replayModels.setVisible(replay);
        this.replayOriginal.setVisible(replay);

        /* The header's transport belongs to the composition. In replay mode it is hidden so there
         * is exactly one play button and one timecode on screen, both owned by the mode bar. */
        this.title.label = IKey.constant(replay ? "BBS 合成器 · 回放" : "BBS 合成器");
        this.play.setVisible(!replay);
        this.prevFrame.setVisible(!replay);
        this.nextFrame.setVisible(!replay);
        this.timecode.setVisible(!replay);
        this.replaySelector.setVisible(!replay);

        if (replay)
        {
            this.replayPlayButton.label = IKey.constant(this.replayWorkspace.isPlayingFromHost() ? "暂停" : "播放");

            int frames = Math.max(0, Math.round(this.replayWorkspace.playheadSeconds() * 20F));
            int ff = frames % 20;
            int totalSeconds = frames / 20;
            int ss = totalSeconds % 60;
            int mm = (totalSeconds / 60) % 60;
            int hh = totalSeconds / 3600;

            this.replayTimecode.label = IKey.constant(String.format(java.util.Locale.ROOT,
                "%02d:%02d:%02d:%02d", hh, mm, ss, ff));
        }
    }

    private void switchWorkspace(int mode)
    {
        if (mode == this.workspaceMode)
        {
            return;
        }

        if (mode == WORKSPACE_REPLAY)
        {
            if (this.film == null || this.filmPanel == null)
            {
                this.setStatus("没有打开的影片：先从 BBS 影片编辑器打开合成器，或点上方「回放」前先绑定影片");

                return;
            }

            if (this.renderView)
            {
                this.toggleRenderView();
            }

            this.workspaceMode = WORKSPACE_REPLAY;

            try
            {
                this.replayWorkspace.appear();
                this.replayWorkspace.seekSeconds(this.time);
            }
            catch (RuntimeException e)
            {
                /* A malformed film or a BBS panel that refuses to build must not take the whole
                 * compositor down. Stay in the composition workspace and surface the reason. */
                dev.bbsae.BbsAeAddon.LOGGER.error("Could not open the embedded replay workspace", e);
                this.workspaceMode = WORKSPACE_COMPOSITE;
                this.applyLayout();
                this.setStatus("回放工作区打开失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));

                return;
            }

            this.beginPageMotion(this.workspaceMotion, "mode:replay");
        }
        else
        {
            this.workspaceMode = WORKSPACE_COMPOSITE;

            if (this.replayWorkspace != null)
            {
                /* Keep the comp playhead where the replay editor left it. The replay panel owns
                 * the film cursor while it is visible; without writing it back the timeline jumped
                 * to the last comp time as soon as the user returned. */
                this.time = Math.max(0F, Math.min(this.doc == null ? this.time : this.doc.duration,
                    this.replayWorkspace.playheadSeconds()));

                try
                {
                    this.replayWorkspace.disappear();
                }
                catch (RuntimeException e)
                {
                    dev.bbsae.BbsAeAddon.LOGGER.warn("Replay workspace failed to disappear cleanly", e);
                }

                this.invalidatePlayheadUi();
                this.beginPageMotion(this.workspaceMotion, "mode:composite");
            }
        }

        this.applyLayout();
        this.updateToolbarLabels();
        this.setStatus(this.workspaceMode == WORKSPACE_REPLAY
            ? "回放编辑：上方「合成 / 回放」可随时切回合成器"
            : "已回到合成器");
    }

    private void invalidatePlayheadUi()
    {
        if (this.trackTimeline != null)
        {
            this.trackTimeline.resize();
        }

        this.previewInfoTick = 0;
    }

    @Override
    public void update()
    {
        super.update();

        if (this.workspaceMode == WORKSPACE_REPLAY && this.replayWorkspace != null)
        {
            this.replayWorkspace.update();
        }
    }

    /** Dock mode: the dock owns the panel rectangles; the rest of the chrome is positioned here. */
    private void applyDockLayout()
    {
        int bodyHeight = Math.max(1, this.area.h - 24 - MODE_BAR_H - 18);
        boolean replay = this.workspaceMode == WORKSPACE_REPLAY;

        this.header.relative(this).x(0).y(0).w(1F).h(24);
        this.modeBar.relative(this).x(0).y(24).w(1F).h(MODE_BAR_H);
        this.status.relative(this).x(0).y(1F, -18).w(1F).h(16);
        this.dock.relative(this).x(0).y(24 + MODE_BAR_H).w(1F).h(bodyHeight);
        this.dock.setVisible(!this.renderView && !replay);
        this.dock.setEnabled(!this.renderView && !replay);
        this.renderPanel.relative(this).x(0).y(24 + MODE_BAR_H).w(1F).h(bodyHeight);
        this.renderPanel.setVisible(this.renderView && !replay);
        this.replayWorkspace.relative(this).x(0).y(24 + MODE_BAR_H).w(1F).h(bodyHeight);
        this.replayWorkspace.setVisible(replay);
        this.replayWorkspace.setEnabled(replay);
        this.layoutModeBar();

        /* super.resize() propagates the chrome rectangles; the dock's own flex pass runs in the
         * resize() override below (it needs a real area before it can divide it). */
        this.resize();
    }

    /** Legacy fixed layout: three columns in a body, timeline below, splitter handles between. */
    private void applyLegacyLayout()
    {
        boolean left = this.effectColumn.isVisible();
        boolean right = this.inspectorColumn.isVisible();
        boolean replay = this.workspaceMode == WORKSPACE_REPLAY;
        int usableHeight = Math.max(1, this.area.h - MODE_BAR_H);
        int lw = left ? this.leftPanelWidth : 0;
        int rw = right ? this.rightPanelWidth : 0;
        int gapL = left ? 6 : 0;
        int gapR = right ? 6 : 0;

        /* Responsive geometry is a pure calculation, shared with CompUi tests:
         *  - the timeline never grows so tall that the preview body disappears;
         *  - the two columns shrink proportionally before they can make the preview negative;
         *  - on a very narrow panel the side columns still fit, but their configured widths are
         *    capped instead of turning the preview into a zero-width strip. */
        this.timelinePanelHeight = CompUi.fitTimelineHeight(usableHeight, this.timelinePanelHeight);
        int maxSide = CompUi.maxSideWidth(this.area.w);

        this.leftPanelWidth = Math.min(this.leftPanelWidth, maxSide);
        this.rightPanelWidth = Math.min(this.rightPanelWidth, maxSide);

        int[] columns = CompUi.fitColumns(this.area.w, lw, rw, gapL + gapR, 120);

        lw = columns[0];
        rw = columns[1];

        this.effectColumn.relative(this.body).x(0).y(0).w(lw).h(1F);
        this.preview.relative(this.body).x(lw + gapL).y(0).w(1F, -(lw + rw + gapL + gapR)).h(1F);
        this.inspectorColumn.relative(this.body).x(1F, -rw).y(0).w(rw).h(1F);

        this.layoutPreviewBar(Math.max(48, this.area.w - lw - rw - gapL - gapR));

        int bodyHeight = CompUi.bodyHeight(usableHeight, this.timelinePanelHeight);

        this.modeBar.relative(this).x(0).y(24).w(1F).h(MODE_BAR_H);
        this.body.relative(this).x(0).y(24 + MODE_BAR_H).w(1F).h(bodyHeight);
        this.body.setVisible(!replay && !this.renderView);
        this.body.setEnabled(!replay && !this.renderView);
        boolean quick = this.quickPanel != null && this.quickPanel.isVisible();
        int quickWidth = quick ? CompUi.quickWidth(this.area.w, this.quickPanelWidth) : 0;

        this.timeline.relative(this).x(0).y(1F, -(this.timelinePanelHeight + 18))
            .w(1F, -quickWidth).h(this.timelinePanelHeight);
        this.timeline.setVisible(!replay && !this.renderView);
        this.timeline.setEnabled(!replay && !this.renderView);

        if (quick)
        {
            this.quickPanel.relative(this).x(1F, -quickWidth).y(1F, -(this.timelinePanelHeight + 18))
                .w(quickWidth).h(this.timelinePanelHeight);
            this.quickPanel.setVisible(!replay && !this.renderView);
            this.quickPanel.setEnabled(!replay && !this.renderView);
        }
        this.renderPanel.relative(this).x(0).y(24 + MODE_BAR_H).w(1F)
            .h(this.renderView ? Math.max(1, usableHeight - 24 - 18) : bodyHeight);
        this.renderPanel.setVisible(this.renderView && !replay);
        this.replayWorkspace.relative(this).x(0).y(24 + MODE_BAR_H).w(1F)
            .h(1F, -24 - MODE_BAR_H - 18);
        this.replayWorkspace.setVisible(replay);
        this.replayWorkspace.setEnabled(replay);
        this.layoutModeBar();

        if (this.leftHandle != null)
        {
            /* The horizontal splitter only spans the preview. It used to span the whole body, which
             * put a timeline-height resize grip on the bottom 4 px of the left effect/asset column:
             * dragging the last list row (or any data at that edge) resized the timeline instead of
             * selecting/dragging the row. That is exactly the reported "dragging data in the left
             * panel also drags the panel size" bug. */
            int[] splitter = CompUi.timelineSplitterSpan(this.area.w, lw, rw, gapL, gapR);

            this.leftHandle.relative(this.body).x(lw + 2).y(0).w(4).h(1F);
            this.leftHandle.setVisible(left);
            this.rightHandle.relative(this.body).x(1F, -rw - 6).y(0).w(4).h(1F);
            this.rightHandle.setVisible(right);
            this.timelineHandle.relative(this.body).x(splitter[0]).y(1F, -4).w(splitter[1]).h(4);
            this.timelineHandle.setVisible(true);
        }

        this.layoutKeyframeDrawer();
        this.resize();
    }

    /**
     * Every size change ends here. In dock mode the dock has to re-divide its area and re-apply the
     * pixel minimums before the derived overlays (preview bar, quick panel, keyframe drawer) can be
     * positioned inside their panels.
     *
     * <p>The guard matters: {@code setupFlex()} resizes the dock, the dock resizes its slots, the
     * slots resize their panels — one of them is this panel, whose {@code resize()} would start the
     * flex pass again. Only the outermost call may run it.</p>
     */
    @Override
    public void resize()
    {
        super.resize();

        if (this.dock == null || this.layoutPass || this.dock.area.w <= 0 || this.dock.area.h <= 0)
        {
            return;
        }

        this.layoutPass = true;

        try
        {
            this.dock.setupFlex(true);
            this.syncDockDerivedLayout();
        }
        finally
        {
            this.layoutPass = false;
        }
    }

    /** Positions the things that live inside a dock panel (they are not dock panels themselves). */
    private void syncDockDerivedLayout()
    {
        if (this.dock == null || this.preview == null || this.timeline == null)
        {
            return;
        }

        this.layoutPreviewBar(Math.max(48, this.preview.area.w));
        this.syncViewportSize();
        this.layoutKeyframeDrawer();
    }

    private CompProject.CompSettings dockSettings()
    {
        return this.project == null ? null : this.project.settings;
    }

    /** Starts a page-switch transition unless the user disabled UI motion. */
    private void beginPageMotion(UiPageTransition motion, String page)
    {
        if (motion != null && CompPrefs.animating())
        {
            motion.begin(page);
        }
    }

    private boolean dockUnlocked()
    {
        CompProject.CompSettings settings = this.dockSettings();

        return settings != null && settings.dockUnlocked;
    }

    /** Called by the dock source once per settled layout change; batches the project write. */
    private void onDockLayoutSaved()
    {
        if (this.dockPersistSuspended())
        {
            return;
        }

        this.dockLayoutDirty = true;
    }

    /** Called by the dock when visibility / active stack tab changed: keep the chrome in sync. */
    private void onDockLayoutChanged()
    {
        if (this.dockPersistSuspended())
        {
            return;
        }

        this.dockLayoutDirty = true;
    }

    private boolean dockPersistSuspended()
    {
        return this.focusMode || (this.dockSource != null && this.dockSource.isPersistSuspended());
    }

    /**
     * Leaving the panel must not persist the transient focus tree. The debounced flush could fire
     * after the panel was closed, so restore the real layout first (cheap: one dock refresh).
     */
    private void restoreFocusBeforePersist()
    {
        if (this.focusMode && this.dock != null)
        {
            this.focusMode = false;
            this.applyDockFocusMode();
        }
    }

    private void onDockLayoutSettled()
    {
        this.syncDockDerivedLayout();

        if (!this.dockPersistSuspended())
        {
            this.dockLayoutDirty = true;
        }

        if (Boolean.getBoolean("bbsae.debugDock") && this.dock != null)
        {
            dev.bbsae.BbsAeAddon.LOGGER.info("dock settled: {}x{} locked={} effectsActive={} effectsVis={} effectsArea={}x{}@{},{}",
                this.dock.area.w, this.dock.area.h, this.dock.isLocked(),
                this.dock.isPanelActive(CompDockSource.PANEL_EFFECTS), this.effectColumn.isVisible(),
                this.effectColumn.area.w, this.effectColumn.area.h, this.effectColumn.area.x, this.effectColumn.area.y);
            dev.bbsae.BbsAeAddon.LOGGER.info("dock children: tabs={}x{}@{},{} tabsVis={} list={}x{}@{},{} listVis={} preview={}x{}@{},{} timeline={}x{}@{},{}",
                this.leftTabs.area.w, this.leftTabs.area.h, this.leftTabs.area.x, this.leftTabs.area.y,
                this.leftTabs.isVisible(),
                this.effectLibrary.area.w, this.effectLibrary.area.h, this.effectLibrary.area.x, this.effectLibrary.area.y,
                this.effectLibrary.isVisible(),
                this.preview.area.w, this.preview.area.h, this.preview.area.x, this.preview.area.y,
                this.timeline.area.w, this.timeline.area.h, this.timeline.area.x, this.timeline.area.y);
        }
    }

    /** Flushes a dock layout change without treating it as a document edit (no undo step). */
    private void flushDockLayout()
    {
        if (this.flushingDock)
        {
            return;
        }

        /* Focus mode must never be written into the project (see applyDockFocusMode). */
        if (this.dockPersistSuspended())
        {
            this.dockLayoutDirty = false;

            return;
        }

        this.dockLayoutDirty = false;

        if (this.dockSource == null)
        {
            return;
        }

        this.flushingDock = true;

        try
        {
            CompProject.CompSettings settings = this.dockSettings();
            String layoutBefore = settings == null ? null : settings.dockLayout;
            String hiddenBefore = settings == null ? null : settings.dockHiddenPanels;

            this.dockSource.flush();

            settings = this.dockSettings();

            if (settings != null)
            {
                settings.dockUnlocked = this.dock != null && !this.dock.isLocked();
            }

            /* Window resize re-runs setupFlex constantly; flushing unconditionally scheduled a
             * full project serialisation (all keyframes) every throttle window even when the
             * encoded layout was byte-identical. Only a real layout change is worth a write. */
            boolean changed = settings != null
                && (!java.util.Objects.equals(layoutBefore, settings.dockLayout)
                    || !java.util.Objects.equals(hiddenBefore, settings.dockHiddenPanels));

            if (changed)
            {
                this.saveUiState();
            }
        }
        finally
        {
            this.flushingDock = false;
        }
    }

    private void toggleDockLock()
    {
        if (this.dock == null)
        {
            return;
        }

        this.dock.toggleLock();

        CompProject.CompSettings settings = this.dockSettings();

        if (settings != null)
        {
            settings.dockUnlocked = !this.dock.isLocked();
        }

        this.saveUiState();
        this.setStatus(this.dock.isLocked()
            ? "面板布局已锁定：拖动标题条不会再改变布局"
            : "面板布局已解锁：拖动面板标题条/标签即可拆分或堆叠（Esc 取消拖动）");
    }

    /** Right-click menu of the header 「布局」 button. */
    private void dockLayoutMenu(ContextMenuManager manager)
    {
        if (this.dock == null)
        {
            manager.action(Icons.HELP, IKey.constant("当前是旧版固定分栏（设置 → 开发者 可启用停靠式面板）"), () -> {});
            return;
        }

        manager.action(this.dock.isLocked() ? Icons.UNLOCKED : Icons.LOCKED,
            IKey.constant(this.dock.isLocked() ? "解锁布局（可拖拽面板）" : "锁定布局"), this::toggleDockLock);
        manager.action(Icons.REFRESH, IKey.constant("重置为默认布局"), () ->
        {
            this.dock.resetLayout();
            this.dockLayoutDirty = true;
            this.setStatus("面板布局已重置");
        });
        manager.action(Icons.MATERIAL, IKey.constant("效果库在右侧（旧版布局）"), () ->
        {
            this.dockSource.setRoot(CompDockSource.effectsRightLayout());
            this.dockSource.setHiddenPanels(new java.util.LinkedHashSet<>());
            this.dock.refresh();
            this.dockLayoutDirty = true;
            this.setStatus("已切换为旧版布局（预览在左、效果库与属性在右）；「重置为默认布局」回到效果库在左");
        });
        manager.action(Icons.UNDO, IKey.constant("撤销布局改动（Ctrl+Alt+Z）"), () ->
        {
            if (this.dock.undoLayout())
            {
                this.dockLayoutDirty = true;
                this.setStatus("已撤销一次布局改动");
            }
        });
        manager.action(Icons.MAXIMIZE, IKey.constant("最大化鼠标下的面板（Ctrl+空格）"),
            () -> this.dock.toggleMaximizeUnderCursor());
        manager.action(Icons.LIST, IKey.constant("循环堆叠标签（Ctrl+Tab / Ctrl+Shift+Tab）"),
            () -> this.dock.cycleDockStackTab(1));

        /* Hidden panels: hiding happens on the panel's own title-bar context menu while unlocked;
         * every hidden panel gets a "show" entry here so it can always be recovered. */
        manager.action(Icons.HELP, IKey.constant("隐藏面板：解锁后右键面板标题条 → 隐藏"), () -> {});
        this.dock.fillHiddenPanelsMenu(manager);
    }

    /**
     * A very quiet atmosphere under the editor chrome.
     *
     * <p>First principle: an editor is read for hours, so "advanced" cannot mean more contrast.
     * This layer is a vertical tonal gradient plus two wide, low-alpha pools at the upper corners.
     * It only shows in the gutters between panels; no control loses contrast, and the glow is only
     * two primitives.</p>
     */
    /** BBS onion skins render extra film frames; while compositing they can look like ghosting. */
    private void suppressBbsOnionSkin()
    {
        if (!CompPrefs.get().suppressOnionSkin || BBSSettings.editorOnionSkin == null)
        {
            return;
        }

        if (this.onionSkinRestore == null)
        {
            this.onionSkinRestore = BBSSettings.editorOnionSkin.enabled.get();
        }

        if (Boolean.TRUE.equals(this.onionSkinRestore) && BBSSettings.editorOnionSkin.enabled.get())
        {
            BBSSettings.editorOnionSkin.enabled.set(false);
        }
    }

    private void restoreBbsOnionSkin()
    {
        if (this.onionSkinRestore != null && BBSSettings.editorOnionSkin != null)
        {
            BBSSettings.editorOnionSkin.enabled.set(this.onionSkinRestore);
        }

        this.onionSkinRestore = null;
    }

    @Override
    public void setVisible(boolean visible)
    {
        super.setVisible(visible);

        if (!visible)
        {
            this.restoreBbsOnionSkin();
        }
    }

    private void renderShellAtmosphere(UIContext context)
    {
        if (this.area.w <= 0 || this.area.h <= 0)
        {
            return;
        }

        UiGfx.gradientV(context.batcher, this.area.x, this.area.y, this.area.w, this.area.h,
            0xFF111821, 0xFF080A0E);

        if (!CompPrefs.get().glow)
        {
            return;
        }

        int accent = BBSSettings.primaryColor() | 0xFF000000;

        UiGfx.glow(context.batcher, this.area.x + 40, this.area.y + 10,
            Math.max(90, this.area.w / 7), UiGfx.alpha(accent, 0.035F), 36);
        UiGfx.glow(context.batcher, this.area.ex() - 40, this.area.y + 10,
            Math.max(90, this.area.w / 8), UiGfx.alpha(0xFFD9A85C, 0.028F), 36);

        /* Bottom inner shadow: separates the timeline strip from the desktop without a border. */
        UiGfx.gradientV(context.batcher, this.area.x, this.area.ey() - 36, this.area.w, 36,
            0x00000000, 0x33000000);
    }

    /**
     * Reflows the fixed-position header for the current width.
     *
     * <p>The header used to be absolutely positioned for a 1920 px editor. At 800 px the replay
     * selector, play controls and right-hand actions overlapped, and at 500 px the save/settings
     * buttons were painted on top of the timecode. This method is the same information
     * architecture, with lower-priority actions disappearing before they can collide.</p>
     */
    private void layoutHeader()
    {
        if (this.header == null)
        {
            return;
        }

        /* Pure geometry in CompUi: the right cluster is laid out from the right edge by each
         * control's own width. The previous implementation advanced by the *next* width and made
         * the 48 px settings button overlap the 18 px focus button by 22 px at every size. */
        CompUi.HeaderLayout layout = CompUi.headerLayout(this.area.w, CompUi.control());
        int control = layout.control;
        int inset = Math.max(2, (24 - control) / 2);

        this.title.setVisible(layout.showTitle);
        this.replaySelector.setVisible(layout.showReplay);
        this.play.setVisible(layout.showPlay);
        this.timecode.setVisible(layout.showTimecode);
        this.prevFrame.setVisible(layout.showFrames);
        this.nextFrame.setVisible(layout.showFrames);
        this.save.setVisible(layout.showSave);
        this.exportFilm.setVisible(layout.showExport);
        this.renderMode.setVisible(layout.showRender);

        if (layout.showTitle)
        {
            this.title.relative(this.header).x(layout.titleX).y(inset).w(86).h(control);
        }

        if (layout.showReplay)
        {
            this.replaySelector.relative(this.header).x(layout.replayX).y(inset).w(layout.replayW).h(control);
        }

        if (layout.showPlay)
        {
            this.play.relative(this.header).x(layout.playX).y(inset).w(44).h(control);
        }

        if (layout.showFrames)
        {
            this.prevFrame.relative(this.header).x(layout.prevX).y(inset).w(control).h(control);
            this.nextFrame.relative(this.header).x(layout.nextX).y(inset).w(control).h(control);
        }

        if (layout.showTimecode)
        {
            this.timecode.relative(this.header).x(layout.timecodeX).y(inset).w(layout.timecodeW).h(control);
        }

        /* Focus and settings are always visible: they are the two actions the header exists for. */
        this.focusButton.relative(this.header).x(layout.focusX).y(inset).w(control).h(control);
        this.settingsButton.relative(this.header).x(layout.settingsX).y(inset).w(48).h(control);
        this.layoutButton.relative(this.header).x(layout.layoutX).y(inset).w(48).h(control);

        if (layout.showRender)
        {
            this.renderMode.relative(this.header).x(layout.renderX).y(inset).w(54).h(control);
        }

        if (layout.showExport)
        {
            this.exportFilm.relative(this.header).x(layout.exportX).y(inset).w(54).h(control);
        }

        if (layout.showSave)
        {
            this.save.relative(this.header).x(layout.saveX).y(inset).w(48).h(control);
        }

        /* Optional action: the project switcher sits left of the right cluster and disappears
         * before it can overlap the left group. The effect library is the left column's first tab,
         * not a header action. */
        this.projectsButton.setVisible(layout.showProjects);

        if (layout.showProjects)
        {
            this.projectsButton.relative(this.header).x(layout.projectsX).y(inset)
                .w(CompUi.PROJECTS_W).h(control);
        }
    }

    /** Keeps the floating preview controls inside a very narrow preview. */
    private void layoutPreviewBar(int previewWidth)
    {
        if (this.previewBar == null)
        {
            return;
        }

        this.lastPreviewWidth = Math.max(48, previewWidth);

        /* The camera/pen/replay icon rail shares this 20 px row with the floating preview bar.
         * Size the rail first and feed the leftover width to the bar: before this, a preview
         * narrower than ~458 px had icons painted over the bar's filter chip (clicking "线性：自动"
         * opened the camera settings). */
        CompUi.PreviewRailLayout rail = CompUi.previewRailLayout(this.lastPreviewWidth);

        this.applyPreviewRail(rail);

        CompUi.PreviewBarLayout layout = CompUi.previewBarLayout(rail.barWidth, CompUi.control(),
            this.previewControlsCollapsed, this.previewFilterExpanded);

        this.previewBar.relative(this.preview).x(6).y(6).w(layout.width).h(20);
        this.effectsToggle.setVisible(layout.showEffects);
        this.masksToggle.setVisible(layout.showMasks);
        this.bbsBackdropToggle.setVisible(layout.showBbs);
        this.previewFilter.setVisible(layout.showFilter);
        this.previewCollapse.setVisible(layout.showCollapse);

        if (layout.showEffects)
        {
            this.effectsToggle.relative(this.previewBar).x(layout.effectsX).y(1).w(58).h(18);
        }

        if (layout.showMasks)
        {
            this.masksToggle.relative(this.previewBar).x(layout.masksX).y(1).w(58).h(18);
        }

        if (layout.showBbs)
        {
            this.bbsBackdropToggle.relative(this.previewBar).x(layout.bbsX).y(1).w(58).h(18);
        }

        if (layout.showFilter)
        {
            this.previewFilter.relative(this.previewBar).x(layout.filterX).y(1).w(82).h(18);
        }

        this.previewCollapse.relative(this.previewBar).x(layout.collapseX).y(1).w(20).h(18);

        if (this.previewFilterMenu != null)
        {
            this.previewFilterMenu.setVisible(layout.filterMenuVisible);

            if (layout.filterMenuVisible)
            {
                this.previewFilterMenu.relative(this.preview)
                    .x(6 + layout.filterMenuX).y(6 + layout.filterMenuY)
                    .w(layout.filterMenuW).h(layout.filterMenuH);
                this.previewFilterMenu.resize();
            }
        }

        this.previewBar.resize();
    }

    /** Positions/hides the preview's right-hand icon rail for the current preview width. */
    private void applyPreviewRail(CompUi.PreviewRailLayout rail)
    {
        UIIcon[] icons = {
            this.cameraViewButton, this.space3DButton, this.cameraToolButton, this.createCameraButton,
            this.cameraSettingsButton, this.penButton, this.replayModeButton
        };

        for (int index = 0; index < icons.length; index++)
        {
            if (icons[index] == null)
            {
                continue;
            }

            boolean shown = index < rail.shown.length && rail.shown[index];

            icons[index].setVisible(shown);

            if (shown)
            {
                icons[index].relative(this.preview).x(rail.xs[index]).y(6).w(20).h(20);
            }
        }
    }

    private void savePanelLayout()
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        this.project.settings.leftPanelWidth = this.leftPanelWidth;
        this.project.settings.rightPanelWidth = this.rightPanelWidth;
        this.project.settings.timelineHeight = this.timelinePanelHeight;
        this.project.settings.keyframeDrawerHeight = this.keyframeDrawerHeight;
        this.project.settings.cameraPreviewMode = this.cameraPreviewMode;
        this.project.settings.timelinePage = this.timelinePage;
        /* Editor view state: reopening the project resumes the selection, drawer and timeline
         * view instead of resetting the timeline to the full composition. */
        this.writeEditorViewState();

        this.saveViewportPose();

        if (this.dockSource != null)
        {
            this.dockSource.flush();
        }

        if (this.dock != null)
        {
            this.project.settings.dockUnlocked = !this.dock.isLocked();
        }

        /* Workspace state, not composition content: no undo step, no pre-cache invalidation. */
        this.saveUiState();
    }

    /**
     * Switches the UI density (0 = follow BBS's GUI scale, 1 compact, 2 normal, 3 comfortable).
     *
     * <p>Every row height, control height, label column and panel width comes from {@link CompUi},
     * so one call re-tiers the whole editor. The inspector is rebuilt because its rows carry their
     * heights at construction time.</p>
     */
    public void applyDensity(int preference)
    {
        if (this.project != null && this.project.settings != null)
        {
            this.project.settings.uiDensity = Math.max(0, Math.min(3, preference));
        }

        CompUi.configure(preference);

        this.leftPanelWidth = CompUi.leftWidth();
        this.rightPanelWidth = CompUi.rightWidth();
        this.timelinePanelHeight = CompUi.timelineHeight();
        this.inspector.column(CompUi.gap()).padding(CompUi.gap());

        this.applyLayout();
        this.refreshInspector();
        this.refreshLayers();
        this.updateToolbarLabels();
        this.savePanelLayout();

        this.setStatus("界面密度：" + (preference == CompUi.DENSITY_AUTO
            ? "跟随 BBS（" + CompUi.densityName(CompUi.density()) + "）"
            : CompUi.densityName(preference)));
    }

    private void loadPanelLayout()
    {
        /* Source textures are keyed by comp + layer id; a different project must not reuse them. */
        dev.bbsae.client.render.CompLayerSourceCache.clear();
        CompUi.configure(CompUi.preferenceOf(this.project));
        if (this.project != null && this.project.settings != null)
        {
            this.leftPanelWidth = Math.max(140, this.project.settings.leftPanelWidth);
            this.quickPanelWidth = Math.max(160, Math.min(340, this.project.settings.quickPanelWidth));
            this.rightPanelWidth = Math.max(296, this.project.settings.rightPanelWidth);
            this.timelinePanelHeight = Math.max(140, this.project.settings.timelineHeight);
            this.keyframeDrawerHeight = Math.max(KEYFRAME_DRAWER_MIN, this.project.settings.keyframeDrawerHeight);
            this.cameraPreviewMode = this.project.settings.cameraPreviewMode;
            this.viewportMode = this.project.settings.viewport3D;
            this.restoreViewportPose();
            this.timelinePage = Math.max(0, Math.min(1, this.project.settings.timelinePage));
        }

        if (this.dock != null)
        {
            /* A different project may carry a different dock tree / hidden-panel set / lock state. */
            if (this.dockSource != null)
            {
                this.dockSource.reload();
            }

            this.dock.locked(!this.dockUnlocked());
            /* A project switch cancels any transient focus view: keeping the old flag would leave
             * the button/state disagreeing with the restored tree on the next F press. */
            this.focusMode = false;
            this.focusReturnRoot = null;
            this.focusReturnHidden = null;

            if (this.dockSource != null)
            {
                this.dockSource.suspendPersist(false);
            }

            this.dock.refresh();
        }

        this.inspector.column(CompUi.gap()).padding(CompUi.gap());
        this.applyLayout();
        this.applyTimelinePage();
        this.restoreEditorViewState();
    }

    /**
     * Restores the per-project editor view state written by {@link #savePanelLayout()}: layer /
     * audio selection, inspector and left-column page, keyframe drawer, timeline zoom and scroll.
     * Everything is resolved by id against the freshly bound document, so stale ids from a
     * different project simply drop out.
     */
    private void restoreEditorViewState()
    {
        CompProject.CompSettings settings = this.project == null ? null : this.project.settings;

        if (settings == null || this.doc == null)
        {
            return;
        }

        CompLayer primary = null;

        if (settings.selectedLayerIds != null && !settings.selectedLayerIds.isBlank())
        {
            java.util.List<String> ids = new ArrayList<>();

            for (String part : settings.selectedLayerIds.split(","))
            {
                CompLayer layer = this.doc.getLayer(part.trim());

                if (layer != null)
                {
                    ids.add(layer.id);

                    if (primary == null)
                    {
                        primary = layer;
                    }
                }
            }

            if (primary != null)
            {
                this.selectedIds.clear();
                this.selectedIds.addAll(ids);
                this.applySelection(primary);
            }
        }

        this.selectedAudio = null;

        if (this.doc.audio != null && settings.selectedAudioId != null && !settings.selectedAudioId.isBlank())
        {
            for (dev.bbsae.comp.CompAudio clip : this.doc.audio)
            {
                if (clip != null && settings.selectedAudioId.equals(clip.id))
                {
                    this.selectedAudio = clip;

                    break;
                }
            }
        }

        this.setInspectorPage(Math.max(PAGE_EFFECTS, Math.min(PAGE_OUTPUT, settings.inspectorPage)));
        this.leftTabs.select(Math.max(0, Math.min(2, settings.leftPage)));
        this.applyLeftPage(settings.leftPage, false);

        this.keyframeOpen = settings.keyframeDrawerOpen;
        this.keyframeOpenRequested = settings.keyframeDrawerOpen;
        this.keyframeDrawerSlide = this.keyframeOpen ? 1F : 0F;
        this.keyframeSlideNanos = System.nanoTime();

        if (this.trackTimeline != null)
        {
            if (settings.timelineZoom > 0D)
            {
                this.trackTimeline.restoreView(settings.timelineShift, settings.timelineZoom);
            }

            this.trackTimeline.restoreScrollY(settings.timelineScrollY);
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
    }

    /** A 4px splitter. Drag updates one of the three layout sizes and persists on release. */
    /** True while a column/drawer splitter is being dragged (legacy handles or BBS dock splitters). */
    public boolean isPanelResizing()
    {
        if (this.dock != null && this.dock.isAnySplitterDragging())
        {
            return true;
        }

        return (this.leftHandle != null && this.leftHandle.isDragging())
            || (this.rightHandle != null && this.rightHandle.isDragging())
            || (this.timelineHandle != null && this.timelineHandle.isDragging())
            || (this.keyframeHandle != null && this.keyframeHandle.isDragging());
    }

    /** Esc during a legacy splitter drag: restore the size captured on press. */
    private boolean cancelPanelResize()
    {
        PanelResizeHandle[] handles = {this.leftHandle, this.rightHandle, this.timelineHandle, this.keyframeHandle};

        for (PanelResizeHandle handle : handles)
        {
            if (handle != null && handle.cancelDrag())
            {
                this.setStatus("已取消分栏拖动（Esc）");

                return true;
            }
        }

        /* BBS' own dock splitters have no cancel API. Swallow the key instead of letting the menu
         * ladder clear the layer selection while the layout keeps following the mouse. */
        if (this.dock != null && this.dock.isAnySplitterDragging())
        {
            this.setStatus("松开鼠标结束停靠分栏拖动（Esc 无法撤销 BBS 停靠布局）");

            return true;
        }

        return false;
    }

    private class PanelResizeHandle extends UIElement
    {
        private final int mode;
        private boolean dragging;
        private int startX;
        private int startY;
        private int startLeft;
        private int startRight;
        private int startHeight;
        private int startDrawerHeight;

        PanelResizeHandle(int mode)
        {
            this.mode = mode;
        }

        boolean isDragging()
        {
            return this.dragging;
        }

        /** Esc during a splitter drag: restore the press-time panel sizes. */
        boolean cancelDrag()
        {
            if (!this.dragging)
            {
                return false;
            }

            this.dragging = false;
            UICompositorPanel.this.leftPanelWidth = this.startLeft;
            UICompositorPanel.this.rightPanelWidth = this.startRight;

            if (this.mode == 3)
            {
                /* The keyframe splitter's startHeight is a drawer height, not the timeline height.
                 * Writing it into timelinePanelHeight (the old code) shrank the timeline to 154 px
                 * when Esc cancelled a drawer drag. */
                UICompositorPanel.this.keyframeDrawerHeight = this.startDrawerHeight;
                UICompositorPanel.this.layoutKeyframeDrawer();
                UICompositorPanel.this.timeline.resize();
            }
            else
            {
                UICompositorPanel.this.timelinePanelHeight = this.startHeight;
                UICompositorPanel.this.applyLayout();
            }

            return true;
        }

        @Override
        public void render(UIContext context)
        {
            boolean hot = this.dragging || this.area.isInside(context);
            int color = hot ? 0xFF4C9AFF : 0x1AFFFFFF;

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), color);
            this.drag(context);
            super.render(context);
        }

        private void drag(UIContext context)
        {
            if (!this.dragging)
            {
                return;
            }

            if (!Window.isMouseButtonPressed(0))
            {
                this.dragging = false;
                UICompositorPanel.this.savePanelLayout();

                return;
            }

            int dx = context.mouseX - this.startX;
            int dy = context.mouseY - this.startY;
            /* In dock mode the legacy body is deliberately null (the dock positions the panels).
             * Reading body.area.w here crashed every keyframe-splitter drag with an NPE. */
            int width = UICompositorPanel.this.layoutBodyWidth();
            int height = UICompositorPanel.this.dock != null
                ? UICompositorPanel.this.timelineLayoutHeight()
                : UICompositorPanel.this.area.h;

            if (this.mode == 0)
            {
                UICompositorPanel.this.leftPanelWidth = Math.max(140, Math.min(this.startLeft + dx, Math.max(160, width - UICompositorPanel.this.rightPanelWidth - 220)));
            }
            else if (this.mode == 1)
            {
                UICompositorPanel.this.rightPanelWidth = Math.max(296, Math.min(this.startRight - dx, Math.max(296, width - UICompositorPanel.this.leftPanelWidth - 220)));
            }
            else if (this.mode == 3)
            {
                int max = Math.max(KEYFRAME_DRAWER_MIN,
                    UICompositorPanel.this.keyframeDrawerMaxHeight(height));

                UICompositorPanel.this.keyframeDrawerHeight = Math.max(KEYFRAME_DRAWER_MIN, Math.min(max, this.startDrawerHeight - dy));
                UICompositorPanel.this.layoutKeyframeDrawer();
                UICompositorPanel.this.timeline.resize();
                UICompositorPanel.this.primedWidth = -1;

                return;
            }
            else
            {
                UICompositorPanel.this.timelinePanelHeight = Math.max(140, Math.min(this.startHeight - dy, Math.max(160, height - 24 - 160 - 18)));
            }

            UICompositorPanel.this.applyLayout();
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (!this.visible)
            {
                /* BBS dispatches clicks to hidden children too (it only checks isEnabled), so a
                 * hidden splitter whose old rectangle still sits over the content would silently
                 * eat the press. Visibility must be part of the hit test. */
                return false;
            }

            if (context.mouseButton == 0 && this.area.isInside(context))
            {
                this.dragging = true;
                this.startX = context.mouseX;
                this.startY = context.mouseY;
                this.startLeft = UICompositorPanel.this.leftPanelWidth;
                this.startRight = UICompositorPanel.this.rightPanelWidth;
                this.startHeight = UICompositorPanel.this.timelinePanelHeight;
                this.startDrawerHeight = UICompositorPanel.this.keyframeDrawerHeight;

                return true;
            }

            return super.subMouseClicked(context);
        }
    }

    /* Layer actions */

    private void addMask(CompLayer layer, boolean ellipse)
    {
        this.beginAction("添加蒙版");

        /* "size + 1" reuses an id after a removal (delete mask 2 of 3, add one → two masks claim
         * mask_3), and duplicate ids make every mask/<id>/... channel address both masks. */
        String id = CompStore.uniqueId("mask", layer.masks, (mask) -> mask.id);
        CompMask mask = new CompMask(id);

        mask.name = ellipse ? "Ellipse Mask" : "Rectangle Mask";

        /* The default mask is the layer's CONTENT rectangle, not its nominal size: content is drawn
         * at [-w·anchorX, w·(1-anchorX)], so an anchor of 0.5 means the rectangle has to start at
         * -w/2. Using the un-anchored size put the mask half a layer off ("画了蒙版但没盖住内容"). */
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;

        if (ellipse)
        {
            mask.ellipse(layer.width * (0.5F - layer.anchorX), layer.height * (0.5F - layer.anchorY), layer.width / 2F, layer.height / 2F);
        }
        else
        {
            mask.rectangle(x, y, layer.width, layer.height);
        }

        layer.masks.add(mask);
        this.refreshInspector();
        this.save();
    }

    private void addStyle(CompLayer layer, String type)
    {
        this.beginAction("添加图层样式");
        CompStyle style = new CompStyle(type);

        style.color = CompStyle.DROP_SHADOW.equals(type) ? 0xFF000000 : 0xFF55AAFF;
        style.size = 8F;
        style.distance = 6F;
        style.blur = 6F;
        layer.styles.add(style);
        this.refreshInspector();
        this.save();
    }

    /**
     * Imports a picture into an existing layer through the in-game browser.
     *
     * <p>The OS dialog is not used anywhere any more: see {@link UIImportBrowser} for why a native
     * window cannot be the primary import path inside a fullscreen game.</p>
     */
    private void importImage(CompLayer layer)
    {
        if (this.project == null || layer == null)
        {
            return;
        }

        NativeFileDialog.pickFile(
            "导入图片到图层",
            new String[] {"png", "jpg", "jpeg", "gif"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importFileIntoLayer(layer, file);
                }
            },
            () -> this.openImageBrowser(layer)
        );
    }

    /** Fallback path: the in-game browser, used when the OS dialog cannot be shown. */
    private void openImageBrowser(CompLayer layer)
    {
        if (this.importBrowser != null)
        {
            this.closeImportBrowser();
        }

        this.importBrowser = new UIImportBrowser(
            "导入图片到图层",
            new String[] {"png", "jpg", "jpeg", "gif"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.importFileIntoLayer(layer, file);
                }

                this.closeImportBrowser();
            },
            this::closeImportBrowser
        );

        this.importBrowser.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.importBrowser);
        this.resize();
    }

    /** Copies the file into the project, then points the layer at it and fits its size. */
    private void importFileIntoLayer(CompLayer layer, java.io.File file)
    {
        try
        {
            String name = this.copyIntoProjectImages(file);

            /* Importing into a text/shape/solid layer used to write imagePath that the renderer
             * ignored, so the button looked dead. Promote the layer to an image layer; transform,
             * effects and timing are preserved. */
            if (!"image".equals(layer.type) && !"footage".equals(layer.type))
            {
                layer.type = "image";
            }

            layer.imagePath = "assets/images/" + name;

            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
                CompStore.assetRoot(this.project.id).resolve("images").resolve(name).toFile());

            if (image != null)
            {
                float scale = Math.min(1F, Math.min(this.doc.width * 0.8F / image.getWidth(), this.doc.height * 0.8F / image.getHeight()));

                layer.width = Math.max(16F, image.getWidth() * scale);
                layer.height = Math.max(16F, image.getHeight() * scale);
            }

            this.refreshInspector();
            this.save();
            this.setStatus("已导入到图层：" + name);
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Could not import image {}", file, e);
            this.notifyError("图片导入失败：" + e.getMessage());
        }
    }

    private void openAddLayerMenu(UIIcon icon)
    {
        this.addLayerContextMenu(icon);
    }

    private void addLayerContextMenu(UIElement element)
    {
        element.context((manager) ->
        {
            manager.action(Icons.ADD, IKey.constant("添加图层…"), this::showAddLayerMenu);
            manager.action(Icons.SQUARE, IKey.constant("纯色"), () -> this.addLayer("solid"));
            manager.action(Icons.SQUARE, IKey.constant("渐变"), () -> this.addLayer("gradient"));
            manager.action(Icons.SQUARE, IKey.constant("棋盘格"), () -> this.addLayer("checker"));
            manager.action(Icons.SQUARE, IKey.constant("网格"), () -> this.addLayer("grid"));
            manager.action(Icons.SQUARE, IKey.constant("分形杂色"), () -> this.addLayer("noise"));
            manager.action(Icons.FONT, IKey.constant("文字"), () -> this.addLayer("text"));
            manager.action(Icons.SHAPES, IKey.constant("矩形"), () -> this.addLayer("shape"));
            manager.action(Icons.SHAPES, IKey.constant("椭圆"), () -> this.addLayer("ellipse"));
            manager.action(Icons.SHAPES, IKey.constant("星形"), () -> this.addLayer("star"));
            manager.action(Icons.SHAPES, IKey.constant("多边形"), () -> this.addLayer("polygon"));
            manager.action(Icons.SHAPES, IKey.constant("贝塞尔路径"), () -> this.addLayer("path"));
            manager.action(Icons.IMAGE, IKey.constant("图片"), () -> this.addLayer("image"));
            manager.action(Icons.FILM, IKey.constant("图片序列"), () -> this.addLayer("footage"));
            manager.action(Icons.FILM, IKey.constant("预合成"), () -> this.addLayer("precomp"));
            manager.action(Icons.FILM, IKey.constant("BBS 预览层"), () -> this.addLayer("bbs"));
            manager.action(Icons.FILM, IKey.constant("回放实体层（深度抠像）"), () -> this.addLayer("replay"));
            manager.action(Icons.SPHERE, IKey.constant("空对象"), () -> this.addLayer("null"));
            manager.action(Icons.CAMERA, IKey.constant("摄像机"), () -> this.addLayer("camera"));
            manager.action(Icons.SUN, IKey.constant("调整图层"), () -> this.addLayer("adjustment"));
            manager.action(Icons.SQUARE, IKey.constant("HUD 面板"), () -> this.addLayer("panel"));
            manager.action(Icons.SQUARE, IKey.constant("进度条"), () -> this.addLayer("bar"));
        });
    }

    private void layerContextMenu(ContextMenuManager manager)
    {
        manager.action(Icons.ADD, IKey.constant("添加图层…"), this::showAddLayerMenu);
        manager.action(Icons.COPY, IKey.constant("复制图层"), this::duplicateSelected);
        manager.action(Icons.TRASH, IKey.constant("删除"), this::deleteSelected);
        manager.action(Icons.FILM, IKey.constant("预合成所选图层"), this::precomposeSelected);
        manager.action(Icons.STOPWATCH, IKey.constant("变换关键帧"), () -> this.addTransformKey(true));
        manager.action(Icons.STOPWATCH, IKey.constant("不透明度关键帧"), () -> this.addOpacityKey());
    }

    /** Layer library, opened from the "+" button and from the track context menus. */
    /**
     * Opens the layer library.
     *
     * <p>The "+" button and every "add layer" menu entry land here. It used to open a fixed-size
     * modal with three columns of identical buttons, which needed scrolling it did not have and
     * told the user nothing about what each type does; the library is the same action with room
     * for an icon, a sentence and a search box.</p>
     */
    public void showAddLayerMenu()
    {
        if (this.doc == null)
        {
            this.setStatus("请先在影片编辑器里选择回放");

            return;
        }

        this.openLayerLibrary();
    }

    private void openLayerLibrary()
    {
        if (this.layerLibrary != null)
        {
            this.closeLayerLibrary();
        }

        this.layerLibrary = new UILayerLibrary((id) ->
        {
            this.closeLayerLibrary();
            this.addLayer(id);
        }, this::closeLayerLibrary);

        this.layerLibrary.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.layerLibrary);
        this.resize();
    }

    private void closeLayerLibrary()
    {
        if (this.layerLibrary != null)
        {
            this.layerLibrary.removeFromParent();
            this.layerLibrary = null;
        }
    }

    private String layerPresetLabel(String preset)
    {
        dev.bbsae.comp.LayerCatalog.Entry entry = dev.bbsae.comp.LayerCatalog.get(preset);

        return entry == null ? preset : entry.name;
    }

    /**
     * Creates a layer from a preset name. Presets that are the same renderer with a different
     * starting configuration (gradient / checker / grid / noise are solids with a generator
     * effect, ellipse / star / polygon / path are shapes) map onto the small set of real types,
     * so the renderer does not need a class per preset.
     */
    public void addLayer(String preset)
    {
        if (this.doc == null)
        {
            return;
        }

        this.beginAction("添加图层");
        String type = switch (preset)
        {
            case "gradient", "checker", "grid", "noise" -> "solid";
            case "ellipse", "star", "polygon", "path" -> "shape";
            default -> preset;
        };
        String name = switch (preset)
        {
            case "text" -> "文字";
            case "shape" -> "矩形";
            case "ellipse" -> "椭圆";
            case "star" -> "星形";
            case "polygon" -> "多边形";
            case "path" -> "贝塞尔路径";
            case "image" -> "图片";
            case "footage" -> "图片序列";
            case "precomp" -> "预合成";
            case "null" -> "空对象";
            case "adjustment" -> "调整层";
            case "bbs" -> "BBS 预览";
            case "replay" -> "回放实体";
            case "camera" -> "摄像机";
            case "panel" -> "HUD 面板";
            case "bar" -> "进度条";
            case "gradient" -> "渐变";
            case "checker" -> "棋盘格";
            case "grid" -> "网格";
            case "noise" -> "分形杂色";
            default -> "纯色";
        };

        CompLayer layer = new CompLayer(preset, name, type);

        layer.x = this.doc.width / 2F;
        layer.y = this.doc.height / 2F;
        layer.width = Math.min(640F, this.doc.width * 0.4F);
        layer.height = Math.min(360F, this.doc.height * 0.3F);

        /* New layers start at the playhead (AE/PR behaviour). The initial duration is a project
         * setting: 0 means "fill the rest of the composition". */
        float frame = 1F / Math.max(1F, this.doc.fps);
        float preferred = this.project != null && this.project.settings != null
            ? Math.max(0F, this.project.settings.defaultLayerDuration) : 0F;
        float start = Math.max(0F, Math.min(this.doc.duration - frame, Math.round(this.time / frame) * frame));

        layer.in = start;
        layer.out = preferred > 0F
            ? Math.min(this.doc.duration, start + Math.max(frame, preferred))
            : this.doc.duration;

        if (layer.out <= layer.in)
        {
            layer.out = Math.min(this.doc.duration, layer.in + frame);
        }

        switch (preset)
        {
            case "text":
                layer.text = "新建文字";
                layer.fontSize = 72F;
                layer.color = 0xFFFFFFFF;
                layer.width = 900F;
                layer.height = 160F;
                break;
            case "shape":
                layer.shapes.add(CompShape.rect("shape_1", 0F, 0F, 360F, 220F, 0xFF55AAFF));
                layer.width = 360F;
                layer.height = 220F;
                break;
            case "ellipse":
                layer.shapes.add(CompShape.ellipse("shape_1", 0F, 0F, 360F, 240F, 0xFF55AAFF));
                layer.width = 360F;
                layer.height = 240F;
                break;
            case "star":
            {
                CompShape star = new CompShape("shape_1", CompShape.STAR);

                star.width = 320F;
                star.height = 320F;
                star.sides = 5;
                star.innerRadius = 0.45F;
                star.fillColor = 0xFFFFC14D;
                layer.shapes.add(star);
                layer.width = 320F;
                layer.height = 320F;
                break;
            }
            case "polygon":
            {
                CompShape polygon = new CompShape("shape_1", CompShape.POLYGON);

                polygon.width = 320F;
                polygon.height = 320F;
                polygon.sides = 6;
                polygon.fillColor = 0xFF55D68A;
                layer.shapes.add(polygon);
                layer.width = 320F;
                layer.height = 320F;
                break;
            }
            case "path":
            {
                CompShape path = new CompShape("shape_1", CompShape.PATH);

                path.fillColor = 0xFF55D68A;
                path.add(-140F, 90F);
                path.add(0F, -120F);
                path.add(140F, 90F);
                layer.shapes.add(path);
                layer.width = 300F;
                layer.height = 240F;
                break;
            }
            case "image":
                layer.imagePath = "assets/image.png";
                layer.width = 480F;
                layer.height = 270F;
                break;
            case "footage":
                layer.imagePath = "assets/frames/frame_%04d.png";
                layer.width = 480F;
                layer.height = 270F;
                break;
            case "precomp":
            {
                /* A precomp layer without a target renders nothing, which is exactly why the
                 * old "预合成" button felt like a name-only feature. Always create a real,
                 * empty composition and point the layer at it; the inspector can retarget it
                 * to any other composition afterwards. */
                int subIndex = this.project.comps.size() + 1;
                CompDocument sub = this.project.addComp("precomp_" + subIndex,
                    this.doc.width, this.doc.height, this.doc.fps, this.doc.duration);

                sub.name = "预合成 " + subIndex;
                sub.workAreaOut = sub.duration;
                layer.precompId = sub.id;
                layer.name = sub.name;
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                break;
            }
            case "adjustment":
                /* Adjustment layers are comp-wide by definition: their effects run on the
                 * accumulated image below them. A 640x324 default made them look broken. */
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.name = "调整层";
                layer.effects.add(CompEffectRegistry.create("vignette", 1));
                break;
            case "null":
                layer.width = 100F;
                layer.height = 100F;
                break;
            case "bbs":
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.imageFit = 0;
                layer.name = "BBS 预览";
                break;
            case "replay":
                /* The entity layer is a full-frame plate by definition: it keys the film frame,
                 * so a 640x324 default would letterbox the world and key a postage stamp. */
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.imageFit = 0;
                layer.entityScope = CompLayer.ENTITY_SCOPE_ALL;
                layer.entityIds = "";
                layer.name = "回放实体";
                break;
            case "camera":
                layer.width = 100F;
                layer.height = 100F;
                layer.cameraTargetX = this.doc.width / 2F;
                layer.cameraTargetY = this.doc.height / 2F;
                layer.cameraZ = this.defaultCameraZ();
                layer.z = layer.cameraZ;
                layer.camera3D = true;
                layer.threeD = true;
                /* A new camera starts as a plain pan/zoom view. With look-at on and the target
                 * sitting exactly on the camera, the first pan would rotate the frame 180°. */
                layer.cameraLookAt = false;
                layer.name = "摄像机";
                break;
            case "gradient":
            {
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.color = 0xFFFFFFFF;

                CompEffect gradient = CompEffectRegistry.create("gradient_ramp", 1);

                gradient.putColor("start", 0xFF2563EB);
                gradient.putColor("end", 0xFFDB2777);
                gradient.put("angle", 90D);
                layer.effects.add(gradient);
                break;
            }
            case "checker":
            {
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.color = 0xFFFFFFFF;

                CompEffect checker = CompEffectRegistry.create("checkerboard", 1);

                checker.putColor("a", 0xFF1E293B);
                checker.putColor("b", 0xFF334155);
                checker.put("size", 64D);
                layer.effects.add(checker);
                break;
            }
            case "grid":
            {
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.color = 0xFFFFFFFF;

                CompEffect grid = CompEffectRegistry.create("grid", 1);

                grid.putColor("color", 0x8055AAFF);
                grid.put("size", 80D);
                grid.put("thickness", 2D);
                layer.effects.add(grid);
                break;
            }
            case "noise":
            {
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                layer.color = 0xFFFFFFFF;

                CompEffect noise = CompEffectRegistry.create("noise_gradient", 1);

                noise.putColor("start", 0xFF0F172A);
                noise.putColor("end", 0xFF64748B);
                noise.put("scale", 80D);
                noise.put("contrast", 1.2D);
                noise.put("seed", 7D);
                layer.effects.add(noise);
                break;
            }
            case "panel":
                layer.color = 0xCC0E1218;
                layer.width = 640F;
                layer.height = 360F;
                break;
            case "bar":
                layer.color = 0xFF55AAFF;
                layer.fill = 0.65F;
                layer.width = 420F;
                layer.height = 24F;
                break;
            default:
                layer.color = 0xFF335577;
                break;
        }

        if ("bbs".equals(preset))
        {
            /* One BBS preview per comp, and it belongs at the bottom: it is the background the
             * rest of the composition is drawn over. Appending it (the old behaviour) put a
             * stale/black full-frame layer on top of everything. */
            for (CompLayer existing : this.doc.layers)
            {
                if ("bbs".equals(existing.type))
                {
                    this.selectLayer(existing);
                    this.setStatus("BBS 预览层已在最底层，直接拖动/裁剪它即可");
                    return;
                }
            }

            layer.id = CompStore.uniqueId("bbs_preview", this.doc.layers, (existing) -> existing.id);
            this.doc.layers.add(0, layer);
        }
        else
        {
            this.doc.addLayer(layer);
        }

        this.setSingleSelection(layer);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    /**
     * Debug-only: adds a full-frame 3D replay layer to the current composition. Used by the replay
     * verification hook to exercise the composition-camera actor pass without hand-building a
     * project in the UI.
     */
    public void debugReplayEntityLayer3D()
    {
        if (this.doc == null)
        {
            return;
        }

        this.addLayer("replay");

        CompLayer layer = this.getSelectedLayer();

        if (layer == null || !layer.isEntityLayer())
        {
            return;
        }

        layer.threeD = true;
        layer.width = this.doc.width;
        layer.height = this.doc.height;
        layer.x = this.doc.width / 2F;
        layer.y = this.doc.height / 2F;
        layer.z = 0F;
        layer.entityScope = CompLayer.ENTITY_SCOPE_ALL;
        layer.name = "回放实体（3D 合成相机）";
        this.refreshInspector();
        this.setStatus("验证：回放实体在合成相机空间的 3D 渲染");
    }

    private void duplicateSelected()
    {        /* One Ctrl+D, one meaning: duplicate what is highlighted. The single-layer path was wired
         * to the key even when the inspector showed "多选 · 5 个图层", so four of them silently
         * stayed behind. */
        if (this.getSelectionCount() > 1)
        {
            this.duplicateSelection();

            return;
        }

        this.duplicateLayer(this.selectedLayer);
    }

    private void deleteSelected()
    {
        /* One Delete key, two kinds of selection: audio first, because selecting a clip clears the
         * layer selection (see selectAudioClip), so this order cannot delete the wrong thing. */
        if (this.selectedAudio != null && this.doc != null && this.doc.audio != null
            && this.doc.audio.contains(this.selectedAudio))
        {
            dev.bbsae.comp.CompAudio clip = this.selectedAudio;

            this.beginAction("移除音频");
            this.doc.audio.remove(clip);
            this.selectedAudio = null;
            dev.bbsae.client.audio.CompAudioPlayer.invalidate();
            this.refreshInspector();
            this.save();
            this.setStatus("已移除音频：" + clip.name);

            return;
        }

        /* The highlighted set is the selection. Deleting only the primary left every other
         * highlighted row on screen and made the key look broken. */
        if (this.getSelectionCount() > 1)
        {
            this.deleteSelection();

            return;
        }

        this.deleteLayer(this.selectedLayer);
    }

    /** Opens a precomp as the edited document, keeping a breadcrumb to return. */
    public void enterPrecomp(CompLayer layer)
    {
        if (layer == null || this.project == null || this.doc == null)
        {
            return;
        }

        if (layer.precompId == null || layer.precompId.isEmpty())
        {
            this.setStatus("这个预合成图层还没有目标；先在图层检查器里选择或新建一个预合成");
            return;
        }

        CompDocument sub = this.project.getComp(layer.precompId);

        if (sub == null)
        {
            this.setStatus("预合成目标不存在；请选择或新建一个预合成");
            return;
        }

        /* Enter at the frame the user was looking at, not at 0. The precomp layer's own time
         * mapping (startTime / timeScale / precomp offset+scale) defines which inner frame that
         * is; using raw parent time made an 8s parent playhead land on the inner comp's 0s. */
        float local = (layer.sourceTimeAt(this.time, this.getFrame()) + layer.precompTimeOffset) * layer.precompTimeScale;

        local = Math.max(0F, Math.min(Math.max(0.001F, sub.duration), local));

        double[] view = this.trackTimeline.viewState();

        this.precompStack.push(new PrecompReturn(this.doc,
            this.selectedLayer == null ? "" : this.selectedLayer.id, this.time, view[0], view[1]));
        this.doc = sub;
        this.playing = false;
        this.playRate = 0F;
        this.time = local;
        this.setSingleSelection(sub.layers.isEmpty() ? null : sub.layers.get(sub.layers.size() - 1));
        this.precompBack.setVisible(true);
        this.primedWidth = -1;
        this.keyframeEditor.view.pickKeyframe(null);
        this.trackTimeline.fit();
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();
        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
        this.updateCompPath();
        this.setStatus("已进入预合成：" + sub.name);
    }

    public void exitPrecomp()
    {
        PrecompReturn state = this.precompStack.poll();

        if (state == null)
        {
            return;
        }

        this.doc = state.doc;
        this.time = state.time;
        this.setSingleSelection(state.layerId == null || state.layerId.isEmpty() ? null : this.doc.getLayer(state.layerId));

        if (this.selectedLayer == null && !this.doc.layers.isEmpty())
        {
            this.setSingleSelection(this.doc.layers.get(this.doc.layers.size() - 1));
        }

        this.precompBack.setVisible(!this.precompStack.isEmpty());
        this.primedWidth = -1;
        this.keyframeEditor.view.pickKeyframe(null);
        this.trackTimeline.restoreView(state.viewShift, state.viewZoom);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();
        dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
        this.updateCompPath();
        this.setStatus("已返回：" + this.doc.name);
    }

    /**
     * Opens the 3D workspace.
     *
     * <p>This is a viewport, not a camera view: the editor gets its own orbit camera
     * ({@link #viewportCamera}) and the composition camera becomes an object you can see, select
     * and drag like any other layer. Blender's split between "where I am looking from" and "what
     * the shot sees" is the whole point — without it, navigating means moving the shot, which is
     * both wrong and (because every mouse move re-renders the shot) unusably slow.</p>
     */
    public void enter3DSpace()
    {
        if (this.doc == null || this.project == null)
        {
            return;
        }

        /* The workspace uses a different projection; an in-flight pen gesture must not continue
         * across that switch with stale screen coordinates. */
        if (this.penTool != null)
        {
            this.penTool.cancel();
        }

        CompProject.CompSettings settings = this.project.settings;
        CompLayer existing = this.doc.activeCamera(this.time);

        /* AE camera rule: entering a view never creates a camera. Only the explicit "新建摄像机"
         * action may add one. The developer compatibility switch restores the pre-AE side effect
         * for projects authored around it, and is off by default. */
        boolean compatCreate = settings != null && settings.cameraAutoCreateOnDrag;
        CompLayer camera = dev.bbsae.client.render.CompCameraOps.ensureCamera(this.doc, this.time,
            compatCreate, settings == null ? CompCamera.DEFAULT_FOV : settings.defaultCameraFov);

        if (existing == null && camera != null)
        {
            /* The compat switch actually created a layer: refresh the panels like addLayer() does,
             * then leave the camera active. */
            this.doc.activeCameraId = camera.id;
            this.refreshLayers();
            this.selectLayer(camera);
        }

        this.viewportMode = true;
        /* Enter on the free view. Look-through is one key away (numpad 0) and is the only mode that
         * renders the exact frame; starting there would make the first orbit drag re-render the
         * graph, which is the bug this round removes. */
        this.cameraPreviewMode = false;
        this.project.settings.viewport3D = true;
        this.project.settings.cameraPreviewMode = false;
        this.project.settings.show3DGrid = true;

        /* The stage stays the composition's own colour. Turning the world backdrop on here used to
         * make "3D space" mean "transparent stage", which is the one thing a compositor preview
         * must not be: you cannot judge a frame against the game world. The backdrop is a separate,
         * explicit toggle in the preview bar. */
        this.bbsBackdropToggle.setValue(this.project.settings.previewBackground == 1);
        this.restoreViewportPose();

        if (!this.project.settings.viewportPoseValid || Boolean.getBoolean("bbsae.debugViewLock"))
        {
            /* A verification run needs the same framing every time, independent of whatever pose a
             * previous run happened to save. */
            this.frameViewportOnComposition();
            this.project.settings.viewportPoseValid = true;
        }

        if (camera != null)
        {
            /* Entering a view must not steal the selection: picking the camera made "look around"
             * silently deselect the layer the user was editing (audit P1-4). */
        }

        this.refreshInspector();
        this.savePanelLayout();

        if (camera == null)
        {
            this.setStatus("3D 空间（自由视图）· 还没有摄像机：点预览右侧「新建摄像机」创建（AE 不会自动创建）");
        }
        else
        {
            this.setStatus("3D 空间：右键拖动环绕（中键同效）· Shift+拖动平移 · 滚轮缩放 · 0 看穿摄像机 · 1/3/7 前/右/顶 · 5 正交");
        }
    }

    /** True when the AE camera tool is on. Persisted per project, like every other editor mode. */
    public boolean cameraToolActive()
    {
        return this.project != null && this.project.settings != null && this.project.settings.cameraToolActive;
    }

    /** Settings-page entry point: turns the AE camera tool on/off without a preview click. */
    public void setCameraTool(boolean active)
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        this.project.settings.cameraToolActive = active;

        /* Two editors cannot own the same mouse: arming the camera tool must disarm the pen, or
         * the first click creates a mask/path point AND starts a camera drag (one wasted undo
         * step, two tools lit at once). */
        if (active && this.penTool != null && this.penTool.isActive())
        {
            this.penTool.cancel();
        }

        this.save();
        this.setStatus(active
            ? "AE 摄像机工具：左键环绕 · 中键 Track XY · 右键 Track Z 推拉；没有摄像机时不会自动创建"
            : "已退出 AE 摄像机工具（恢复图层选择/拖动）");
    }

    private void toggleCameraTool()
    {
        if (this.project == null || this.project.settings == null)
        {
            return;
        }

        this.setCameraTool(!this.project.settings.cameraToolActive);
    }

    /**
     * Creates the composition camera on explicit request.
     *
     * <p>This is the only creation path in the editor (AE: Layer → New → Camera). Calling it when a
     * camera already exists selects that camera instead of silently adding a second one, so the
     * button never doubles as "orbit" or "drag" side effects.</p>
     */
    public void createCamera()
    {
        if (this.doc == null || this.project == null)
        {
            this.setStatus("请先打开一个合成");

            return;
        }

        CompLayer existing = this.doc.activeCamera(this.time);

        if (existing != null)
        {
            this.setSingleSelection(existing);
            this.refreshInspector();
            this.setStatus("已选中现有摄像机；用 AE 摄像机工具或检查器调整它");

            return;
        }

        this.beginAction("新建摄像机");
        this.addLayer("camera");

        CompLayer created = this.doc.activeCamera(this.time);

        if (created == null)
        {
            return;
        }

        CompProject.CompSettings settings = this.project.settings;
        float fov = settings == null ? CompCamera.DEFAULT_FOV : settings.defaultCameraFov;
        float focal = CompCamera.focalFor(fov, this.doc.height);

        created.cameraFov = Math.max(1F, Math.min(179F, fov));
        created.cameraZ = focal;
        created.z = focal;
        this.refreshInspector();
        this.save();
        this.setStatus("已新建摄像机（AE：只有显式新建才会有摄像机）。看穿用预览右侧摄像机图标，调整用 AE 摄像机工具");
    }

    /** True when the free 3D viewport (not the shot camera view) is the active preview. */
    private boolean is3DSpace()
    {
        return this.viewportMode;
    }

    public void exit3DSpace()
    {
        if (this.penTool != null)
        {
            this.penTool.cancel();
        }

        this.viewportMode = false;
        this.cameraPreviewMode = false;

        if (this.project != null && this.project.settings != null)
        {
            this.project.settings.viewport3D = false;
            this.project.settings.cameraPreviewMode = false;
            this.project.settings.show3DGrid = false;
        }

        if (this.bbsBackdropToggle != null)
        {
            this.bbsBackdropToggle.setValue(this.project != null && this.project.settings != null && this.project.settings.previewBackground == 1);
        }

        this.saveViewportPose();
        this.refreshInspector();
        this.save();
        this.setStatus("已退出 3D 空间");
    }

    /** Current preview rectangle in screen pixels; the viewport camera needs it for its aspect. */
    private void syncViewportSize()
    {
        /* Before the first layout pass the preview has no size; framing against 1×1 would pick a
         * wildly wrong distance that then gets persisted. Fall back to 16:9 until it is real. */
        int width = this.preview.area.w;
        int height = this.preview.area.h;

        if (width < 32 || height < 32)
        {
            width = 800;
            height = 450;
        }

        this.viewportCamera.setViewport(width, height);
    }

    private void restoreViewportPose()
    {
        CompProject.CompSettings settings = this.project == null ? null : this.project.settings;

        if (settings == null)
        {
            return;
        }

        this.viewportCamera.setTarget(settings.viewportTargetX, settings.viewportTargetY, settings.viewportTargetZ);
        this.viewportCamera.setPose(settings.viewportYaw, settings.viewportPitch,
            settings.viewportDistance > 1F ? settings.viewportDistance : 2000F);
        this.viewportCamera.ortho = settings.viewportOrtho;
        this.syncViewportSize();
        this.viewportCamera.update();
    }

    private void saveViewportPose()
    {
        CompProject.CompSettings settings = this.project == null ? null : this.project.settings;

        if (settings == null)
        {
            return;
        }

        this.viewportCamera.update();
        settings.viewportYaw = this.viewportCamera.yaw;
        settings.viewportPitch = this.viewportCamera.pitch;
        settings.viewportDistance = this.viewportCamera.distance;
        settings.viewportTargetX = this.viewportCamera.target.x;
        settings.viewportTargetY = this.viewportCamera.target.y;
        settings.viewportTargetZ = this.viewportCamera.target.z;
        settings.viewportOrtho = this.viewportCamera.ortho;
        settings.viewport3D = this.viewportMode;
    }

    /** Frames the whole composition from a 3/4 angle; used on first enter and by Home. */
    private void frameViewportOnComposition()
    {
        if (this.doc == null)
        {
            return;
        }

        this.syncViewportSize();
        this.viewportCamera.setPose(32F, 20F, Math.max(1000F, this.doc.height * 2F));

        this.viewportCamera.frame(new org.joml.Vector3f(0F, 0F, 0F),
            new org.joml.Vector3f(this.doc.width, this.doc.height, 0F));

        /* Keep the composition centred, then widen only as much as needed to also show the
         * camera. Framing the union of "comp + camera" pushed the comp off to one side; framing
         * only the comp left a camera parked far in front of the plane outside the view, so
         * entering the workspace showed no gizmo and the camera looked broken. */
        CompLayer camera = this.doc.activeCamera(this.time);

        if (camera != null)
        {
            org.joml.Vector3f eye = CompCamera.eyeOf(camera, this.getFrame(), this.doc.height);
            int w = this.viewportCamera.width;
            int h = this.viewportCamera.height;

            for (int i = 0; i < 10; i++)
            {
                org.joml.Vector2f point = this.viewportCamera.project(eye);

                if (point != null && point.x > w * 0.06F && point.x < w * 0.94F
                    && point.y > h * 0.06F && point.y < h * 0.94F)
                {
                    break;
                }

                this.viewportCamera.setPose(this.viewportCamera.yaw, this.viewportCamera.pitch,
                    this.viewportCamera.distance * 1.3F);
            }
        }
    }

    /** Frames the current selection, or the composition when nothing is selected. */
    private void frameViewportOnSelection()
    {
        if (this.doc == null)
        {
            return;
        }

        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            this.frameViewportOnComposition();

            return;
        }

        CompRenderContext context = new CompRenderContext(this.project, this.doc, this.time, null, null, null);

        try
        {
            org.joml.Matrix4f world = CompScene.world(context, layer);
            float halfWidth = Math.max(1F, layer.width) * 0.5F;
            float halfHeight = Math.max(1F, layer.height) * 0.5F;
            org.joml.Vector3f min = new org.joml.Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            org.joml.Vector3f max = new org.joml.Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

            for (int i = 0; i < 4; i++)
            {
                org.joml.Vector3f corner = new org.joml.Vector3f(
                    (i == 1 || i == 2) ? halfWidth : -halfWidth,
                    i >= 2 ? halfHeight : -halfHeight,
                    0F
                );

                world.transformPosition(corner);
                min.min(corner);
                max.max(corner);
            }

            this.syncViewportSize();
            this.viewportCamera.frame(min, max);
        }
        catch (RuntimeException e)
        {
            this.frameViewportOnComposition();

            return;
        }
    }

    /** Blender's numpad 0: look through the composition camera, or back to the free view. */
    private void toggleViewportLookThrough()
    {
        if (this.doc != null && this.doc.activeCamera(this.time) == null)
        {
            /* AE has no camera view without a camera, and this path must not be a back door that
             * creates one either. */
            this.setStatus("还没有摄像机：点预览右侧「新建摄像机」（看穿视图不会自动创建）");

            return;
        }

        if (!this.viewportMode)
        {
            this.enter3DSpace();
        }

        this.cameraPreviewMode = !this.cameraPreviewMode;

        if (this.project != null && this.project.settings != null)
        {
            this.project.settings.cameraPreviewMode = this.cameraPreviewMode;
        }

        if (this.cameraPreviewMode)
        {
            CompRenderContext context = new CompRenderContext(this.project, this.doc, this.time, null, null, null);

            /* The look-through pose is adopted (that is Blender's numpad 0), but the viewport's
             * ortho/perspective choice belongs to the editor, not to the shot. */
            boolean ortho = this.viewportCamera.ortho;

            this.syncViewportSize();
            this.viewportCamera.lookThrough(CompScene.camera(context));
            this.viewportCamera.setOrtho(ortho);
            this.viewportCamera.update();
            this.setStatus("看穿摄像机：这是最终画面（含效果与蒙版）；再按 0 回到自由视图");
        }
        else
        {
            this.setStatus("自由视图（草稿）：中键环绕 · Shift+中键平移 · 滚轮缩放");
        }

        this.savePanelLayout();
    }

    /**
     * Viewport navigation keys, Blender's numpad layout (top-row digits work too, so a laptop
     * without a numpad can still use the workspace).
     *
     * <p>Deliberately parsed here rather than through BBS's keybind registry: these keys only mean
     * something while the 3D workspace is open, and registering them globally would steal digits
     * from every other panel.</p>
     */
    private boolean subViewportKey(int key)
    {
        if (Boolean.getBoolean("bbsae.debugViewLock"))
        {
            return false;
        }

        boolean ctrl = CompInput.ctrlDown();

        if (key == GLFW.GLFW_KEY_KP_0 || key == GLFW.GLFW_KEY_0)
        {
            this.toggleViewportLookThrough();

            return true;
        }

        if (key == GLFW.GLFW_KEY_KP_1 || key == GLFW.GLFW_KEY_1)
        {
            this.viewportCamera.snap(ctrl ? 1 : 0);
            this.afterViewportSnap("前视图");

            return true;
        }

        if (key == GLFW.GLFW_KEY_KP_3 || key == GLFW.GLFW_KEY_3)
        {
            this.viewportCamera.snap(ctrl ? 3 : 2);
            this.afterViewportSnap("右视图");

            return true;
        }

        if (key == GLFW.GLFW_KEY_KP_7 || key == GLFW.GLFW_KEY_7)
        {
            this.viewportCamera.snap(ctrl ? 5 : 4);
            this.afterViewportSnap("顶视图");

            return true;
        }

        if (key == GLFW.GLFW_KEY_KP_5 || key == GLFW.GLFW_KEY_5)
        {
            this.viewportCamera.setOrtho(!this.viewportCamera.ortho);
            this.viewportCamera.update();
            this.saveViewportPose();
            this.savePanelLayout();
            this.setStatus(this.viewportCamera.ortho ? "正交视图" : "透视视图");

            return true;
        }

        /* Keyboard navigation: a guaranteed path when a mouse has no middle button and the user
         * does not discover right-drag. Blender-ish and conflict-free inside the workspace. */
        if (key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_D || key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_S)
        {
            float step = ctrl ? 16F : 4F;
            float yaw = key == GLFW.GLFW_KEY_A ? step : key == GLFW.GLFW_KEY_D ? -step : 0F;
            float pitch = key == GLFW.GLFW_KEY_W ? step : key == GLFW.GLFW_KEY_S ? -step : 0F;

            this.viewportCamera.orbit(-yaw, pitch);
            this.viewportCamera.update();
            this.saveViewportPose();
            this.setStatus(String.format(java.util.Locale.ROOT, "环绕  偏航 %.0f°  俯仰 %.0f°",
                this.viewportCamera.yaw, this.viewportCamera.pitch));

            return true;
        }

        if (key == GLFW.GLFW_KEY_Q || key == GLFW.GLFW_KEY_E)
        {
            this.viewportCamera.dolly(key == GLFW.GLFW_KEY_Q ? 1F : -1F);
            this.viewportCamera.update();
            this.saveViewportPose();
            this.setStatus(String.format(java.util.Locale.ROOT, "视图距离 %.0f", this.viewportCamera.distance));

            return true;
        }

        if (key == GLFW.GLFW_KEY_KP_DECIMAL)
        {
            this.frameViewportOnSelection();
            this.saveViewportPose();
            this.setStatus("框住所选");

            return true;
        }

        if (key == GLFW.GLFW_KEY_HOME && ctrl)
        {
            this.frameViewportOnComposition();
            this.saveViewportPose();
            this.setStatus("框住整个合成");

            return true;
        }

        return false;
    }

    private void afterViewportSnap(String label)
    {
        this.viewportCamera.update();
        this.saveViewportPose();
        this.setStatus(label + "：" + (this.viewportCamera.ortho ? "正交" : "透视"));
    }

    /**
     * Comp-wide grade: an adjustment layer at the top of the stack with a neutral color_grade
     * effect. It affects every layer below it (the renderer applies adjustment effects to the
     * accumulation), which is what "调色整个合成" means without duplicating an effect per layer.
     */
    public void addGradeAdjustment()
    {
        if (this.doc == null)
        {
            return;
        }

        this.beginAction("添加调色调整层");
        CompLayer adjustment = new CompLayer("adjustment", "调色调整层", "adjustment");

        adjustment.x = this.doc.width / 2F;
        adjustment.y = this.doc.height / 2F;
        adjustment.width = this.doc.width;
        adjustment.height = this.doc.height;
        adjustment.in = 0F;
        adjustment.out = this.doc.duration;
        adjustment.effects.add(CompEffectRegistry.create("color_grade", 1));
        this.doc.addLayer(adjustment);
        this.selectedLayer = adjustment;
        this.selectedIds.clear();
        this.selectedIds.add(adjustment.id);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已添加调色调整层：影响其下所有图层");
    }

    private void precomposeSelected()
    {
        if (this.doc == null || this.selectedLayer == null)
        {
            return;
        }

        this.beginAction("预合成所选图层");
        String sourceName = this.layerLabel(this.selectedLayer);
        CompLayer precomp = CompStore.precompose(this.project, this.doc, this.selectedLayer, "预合成");

        if (precomp == null)
        {
            return;
        }

        this.setSingleSelection(precomp);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
        this.setStatus("已预合成：" + sourceName + " → " + precomp.name);
    }

    /* Inspector */

    private void refreshInspector()
    {
        this.buildingInspector = true;
        this.inspector.removeAll();
        this.inspectorBindings.clear();

        if (this.doc == null)
        {
            this.buildingInspector = false;
            this.inspector.resize();
            return;
        }

        if (this.selectedLayer != null)
        {
            this.selectedLayer.ensureIndependentAxes();
        }

        switch (this.inspectorPage)
        {
            case PAGE_EFFECTS -> this.addEffectPage();
            case PAGE_GRADE -> this.inspector.add(this.gradeSection(this.selectedLayer));
            case PAGE_LAYER -> this.addLayerPage();
            case PAGE_MASK -> this.inspector.add(this.maskSection(this.selectedLayer));
            case PAGE_TEXT ->
            {
                if (this.selectedLayer != null && "text".equals(this.selectedLayer.type))
                {
                    this.inspector.add(this.textSection(this.selectedLayer));
                    this.inspector.add(this.textAnimatorSection(this.selectedLayer));
                }
                else
                {
                    this.inspector.add(this.emptySelectionSection("文字", "选中文字轨道后可编辑排版。"));
                }
            }
            case PAGE_COMP ->
            {
                this.inspector.add(this.compSection());
                this.inspector.add(this.markerSection());
                this.inspector.add(this.parameterSection());
            }
            case PAGE_OUTPUT -> this.inspector.add(this.outputPresetSection());
            default -> this.addEffectPage();
        }

        this.decorateInspector();
        this.inspector.resize();
        this.buildingInspector = false;
        this.lastInspectorTime = this.time;
        this.updateToolbarLabels();

        if (this.curveEditor != null)
        {
            this.curveEditor.refresh();
        }
    }

    /**
     * Inserts a hairline groove before every inspector section.
     *
     * <p>Sections are built in a dozen different methods, so instead of touching every builder the
     * decoration happens once after the page is assembled: the children are detached, re-added in
     * their original order with a {@link UIInspectorDivider} in front of each section after the
     * first one. Plain labels and rows keep their existing rhythm, and the divider only appears
     * where a new group really starts.</p>
     */
    private void decorateInspector()
    {
        java.util.List<IUIElement> children = new java.util.ArrayList<>(this.inspector.getChildren());

        if (children.isEmpty())
        {
            return;
        }

        this.inspector.removeAll();

        boolean first = true;

        for (IUIElement child : children)
        {
            if (!first && child instanceof UISection)
            {
                this.inspector.add(new UIInspectorDivider());
            }

            this.inspector.add(child);
            first = false;
        }
    }

    private UISection emptySelectionSection(String title, String hint)
    {
        UISection section = new UISection(IKey.constant(title));

        section.fields.add(UI.label(IKey.constant(hint), 16));
        section.fields.add(new UICompButton(IKey.constant("选择最上层轨道"), (button) ->
        {
            if (this.doc != null && !this.doc.layers.isEmpty())
            {
                this.selectLayer(this.doc.layers.get(this.doc.layers.size() - 1));
            }
        }));

        return section;
    }

    /**
     * Effects page: only the selected layer's applied effect stack and layer styles.
     *
     * <p>Transform, opacity, source/clip, camera and binding controls moved to {@link #addLayerPage()}.
     * The old "效果控件" page mixed all of them, so a user with five effects had to scroll past
     * motion/opacity/source controls to reach the second effect. The left library adds effects, this
     * page edits the ones the layer owns.</p>
     */
    private void addEffectPage()
    {
        CompLayer layer = this.selectedLayer;

        if (this.getSelectionCount() > 1)
        {
            this.inspector.add(this.multiSelectionSection());
            return;
        }

        if (layer == null)
        {
            this.inspector.add(this.quickStartSection());
            return;
        }

        this.inspector.add(UI.label(IKey.constant(this.layerLabel(layer) + "  ·  " + layer.type), 16));

        if ("camera".equals(layer.type))
        {
            this.inspector.add(this.emptySelectionSection("效果", "摄像机没有可应用的效果；摄像机参数在「图层」页。"));
            return;
        }

        if ("adjustment".equals(layer.type))
        {
            int below = this.doc == null ? 0 : Math.max(0, this.doc.indexOfLayer(layer.id));

            this.inspector.add(UI.label(IKey.constant("调整层：下面的 " + below + " 个图层会被本层效果处理；本层自身不产生像素。"), 14));
        }

        this.inspector.add(this.effectHeaderSection(layer));

        for (CompEffect effect : layer.effects)
        {
            this.inspector.add(this.effectControlSection(layer, effect));
        }

        this.inspector.add(this.styleHeaderSection(layer));

        for (CompStyle style : layer.styles)
        {
            this.inspector.add(this.styleControlSection(layer, style));
        }
    }

    /**
     * Layer page: identity/timing, transform, opacity, source/clip, camera and binding.
     *
     * <p>This is the "what is this layer and where is it" half of the old Effect Controls page;
     * the effect stack itself is on its own page.</p>
     */
    private void addLayerPage()
    {
        CompLayer layer = this.selectedLayer;

        if (this.getSelectionCount() > 1)
        {
            this.inspector.add(this.multiSelectionSection());
            return;
        }

        if (layer == null)
        {
            this.inspector.add(this.layerSection());
            return;
        }

        this.inspector.add(UI.label(IKey.constant(this.layerLabel(layer) + "  ·  " + layer.type), 16));
        this.inspector.add(this.fragmentSection(layer));

        if ("camera".equals(layer.type))
        {
            this.inspector.add(this.cameraSection(layer));
            this.inspector.add(this.bindingSection(layer));
            this.inspector.add(this.layerMarkerSection(layer));
            this.inspector.add(this.animationPresetSection(layer));

            return;
        }

        this.inspector.add(this.layerSection());
        this.inspector.add(this.motionSection(layer));
        this.inspector.add(this.motionTemplateSection(layer));
        this.inspector.add(this.opacitySection(layer));
        this.inspector.add(this.bindingSection(layer));
        this.inspector.add(this.layerMarkerSection(layer));
        this.inspector.add(this.animationPresetSection(layer));

        if ("shape".equals(layer.type))
        {
            this.inspector.add(this.shapeSection(layer));
        }
    }

    private void applyPreviewBarState()
    {
        if (this.previewBar == null || this.effectsToggle == null)
        {
            return;
        }

        /* The toggles are views of the settings, not their owners: a project that was saved with
         * the BBS backdrop on must show the switch as on when it is reopened. */
        if (this.project != null && this.project.settings != null)
        {
            this.bbsBackdropToggle.setValue(this.project.settings.previewBackground == 1);
        }

        this.updateFilterButton();
        this.layoutPreviewBar(this.lastPreviewWidth > 0 ? this.lastPreviewWidth : 300);
    }

    private UISection quickStartSection()
    {
        UISection section = new UISection(IKey.constant("快速开始"));

        section.fields.add(UI.label(IKey.constant("① 添加或选择一条轨道"), 16));
        section.fields.add(UI.label(IKey.constant("② 左侧效果库/素材库负责添加；右侧「图层」调运动和不透明度、「效果」编辑效果"), 14));
        section.fields.add(UI.label(IKey.constant("③ 在底部时间轴拖动或裁剪片段"), 14));
        section.fields.add(UI.label(IKey.constant("不确定怎么操作？点时间轴右端的 ? 看快捷键。"), 14));

        section.fields.add(new UICompButton(IKey.constant("＋ 新建形状"), (button) -> this.addLayer("shape")));
        section.fields.add(new UICompButton(IKey.constant("＋ 添加 BBS 预览层"), (button) -> this.addLayer("bbs")));
        section.fields.add(new UICompButton(IKey.constant("＋ 添加空对象"), (button) -> this.addLayer("null")));
        section.fields.add(new UICompButton(IKey.constant("去左侧效果库添加效果"), (button) -> this.focusEffectLibrary()));

        if (this.doc != null && !this.doc.layers.isEmpty())
        {
            section.fields.add(this.smallButton("选择最上层轨道", () -> this.selectLayer(this.doc.layers.get(this.doc.layers.size() - 1))));
        }

        return section;
    }

    /* --------------------------------------------------------------- replay entity matte */

    /**
     * The "key out replay entities" table.
     *
     * <p>Two controls, one meaning: the switch decides whether <em>every</em> replay is keyed, and
     * the checkbox table decides which ones. They cannot contradict each other because editing a
     * row while "all" is on first materialises the full list and then removes that one row — the
     * switch is a shortcut for "check everything", not a second source of truth.</p>
     */
    private void appendEntityControls(UISection section, CompLayer layer)
    {
        section.fields.add(UI.label(IKey.constant("回放实体抠像 · 只保留勾选的回放实体，其余像素透明"), 15));
        section.fields.add(UI.label(IKey.constant("做法：把勾选的实体单独渲染到透明背景，并用世界深度做硬件遮挡测试；合成器再把这层覆盖贴回世界颜色。不比较颜色，也不依赖深度纹理采样，所以换光影、换材质都不影响。"), 13));

        boolean all = layer.entityScope == CompLayer.ENTITY_SCOPE_ALL;

        section.fields.add(new UIToggle(IKey.constant("抠出所有回放实体"), all, (toggle) ->
        {
            if (toggle.getValue())
            {
                layer.entityScope = CompLayer.ENTITY_SCOPE_ALL;
            }
            else
            {
                /* Leaving "all" must not blank the layer: start from every replay checked, then
                 * the user unchecks what should not be keyed. */
                layer.entityScope = CompLayer.ENTITY_SCOPE_SELECTED;

                if (layer.entityIdSet().isEmpty())
                {
                    layer.entitySelectAll(this.replayIds());
                }
            }

            this.save();
            this.refreshInspector();
        }));

        java.util.List<mchorse.bbs_mod.film.replays.Replay> replays = this.film == null
            ? java.util.Collections.emptyList()
            : this.film.replays.getList();

        if (this.film == null)
        {
            section.fields.add(UI.label(IKey.constant("没有绑定影片：请从影片的回放属性里打开合成器。"), 13));
        }
        else if (replays.isEmpty())
        {
            section.fields.add(UI.label(IKey.constant("这部影片还没有回放，先在影片编辑器里添加一个。"), 13));
        }
        else
        {
            section.fields.add(UI.label(IKey.constant("回放实体（勾选 = 抠出；取消勾选任意一项会自动切到“仅勾选”）"), 13));

            for (mchorse.bbs_mod.film.replays.Replay replay : replays)
            {
                String id = replay.getId();
                boolean enabled = Boolean.TRUE.equals(replay.enabled.get());
                boolean hasForm = replay.form.get() != null;
                boolean kept = all || layer.entityIdSet().contains(id);
                String label = replay.getName() + (enabled ? "" : " · 已禁用") + (hasForm ? "" : " · 无形态");

                section.fields.add(new UIToggle(IKey.constant(label), kept, (toggle) ->
                {
                    if (layer.entityScope == CompLayer.ENTITY_SCOPE_ALL)
                    {
                        layer.entityScope = CompLayer.ENTITY_SCOPE_SELECTED;
                        layer.entitySelectAll(this.replayIds());
                    }

                    layer.entitySetKept(id, toggle.getValue());
                    this.save();
                    this.refreshInspector();
                }));
            }

            section.fields.add(UI.row(
                this.smallButton("全选", () ->
                {
                    layer.entityScope = CompLayer.ENTITY_SCOPE_SELECTED;
                    layer.entitySelectAll(this.replayIds());
                    this.save();
                    this.refreshInspector();
                }),
                this.smallButton("全不选", () ->
                {
                    layer.entityScope = CompLayer.ENTITY_SCOPE_SELECTED;
                    layer.entityIds = "";
                    this.save();
                    this.refreshInspector();
                })
            ));
        }

        section.fields.add(new UIToggle(IKey.constant("世界深度遮挡（实体在方块后会被挡住）"), layer.entityOcclusion, (toggle) ->
        {
            layer.entityOcclusion = toggle.getValue();
            this.save();
        }));

        section.fields.add(this.numberRow("羽化（像素）", layer.entityFeather, 0D, 32D, false, (value) ->
        {
            layer.entityFeather = (float) Math.max(0D, value);
            this.save();
        }));
        section.fields.add(this.numberRow("收缩（像素）", layer.entityErode, 0D, 32D, false, (value) ->
        {
            layer.entityErode = (float) Math.max(0D, value);
            this.save();
        }));
        /* Depth tolerance is deliberately not exposed any more: occlusion is a hardware depth
         * test against the world depth, not an epsilon comparison in a shader. The field stays in
         * the project format so older compositions still load without a migration. */
        section.fields.add(UI.label(IKey.constant("遮挡由显卡深度测试完成，旧工程的“深度容差”已不再需要。"), 13));
        section.fields.add(this.numberRow("适配（0 包含 1 覆盖 2 拉伸）", layer.imageFit, 0D, 2D, true, (value) -> layer.imageFit = value.intValue()));

        section.fields.add(UI.label(IKey.constant(this.entityStatus(layer)), 13));
    }

    private java.util.List<String> replayIds()
    {
        java.util.List<String> ids = new java.util.ArrayList<>();

        if (this.film != null)
        {
            for (mchorse.bbs_mod.film.replays.Replay replay : this.film.replays.getList())
            {
                if (replay.getId() != null && !replay.getId().isBlank())
                {
                    ids.add(replay.getId());
                }
            }
        }

        return ids;
    }

    /** One line that says whether the matte currently has anything to show, and why not. */
    private String entityStatus(CompLayer layer)
    {
        if (!dev.bbsae.client.overlay.BbsPreviewBridge.hasCapturedWorld())
        {
            return "等待世界帧：让影片预览跑起来后才有可抠的画面。";
        }

        int count = dev.bbsae.client.render.ReplayEntityMask.entityCount();

        if (count <= 0)
        {
            return "本帧没有抠出实体：检查回放是否启用、是否勾选、播放头是否在实体出现的时间段。";
        }

        String status = "本帧抠出 " + count + " 个实体";

        if (!dev.bbsae.client.render.ReplayEntityMask.isAvailable())
        {
            status += " · 覆盖目标尚未就绪（等待下一帧）";
        }
        else if (layer.entityOcclusion && !dev.bbsae.client.render.ReplayEntityMask.occlusionAvailable())
        {
            status += " · 世界深度复制失败，本帧退化为无遮挡";
        }

        return status;
    }

    private UISection fragmentSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("片段"));

        section.remember(this.sectionFolds, "fragment", false);
        section.fields.add(UI.label(IKey.constant(this.layerLabel(layer) + "  ·  " + layer.type), 16));

        float frame = 1F / Math.max(1F, this.doc.fps);
        float max = this.doc.duration;

        /* Direct numeric editing. Dragging the bar edges is fast, but "make this exactly 3 s"
         * was previously impossible without arithmetic on the ruler. */
        section.fields.add(this.numberRow("入点（秒）", layer.in, 0F, max, false, (value) ->
        {
            layer.trimInTo((float) Math.max(0D, Math.min(layer.out - frame, value)), this.doc.fps);
            this.commitLayerTiming();
        }));
        section.fields.add(this.numberRow("出点（秒）", layer.out, 0, max, false, (value) ->
        {
            layer.trimOutTo((float) Math.max(layer.in + frame, Math.min(max, value)), this.doc.fps, max);
            this.commitLayerTiming();
        }));
        section.fields.add(this.numberRow("时长（秒）", Math.max(frame, layer.out - layer.in), frame, max, false, (value) ->
        {
            float duration = Math.max(frame, (float) value.doubleValue());

            layer.trimOutTo(Math.min(max, layer.in + duration), this.doc.fps, max);
            this.commitLayerTiming();
        }));

        UIElement quick = new UIElement();

        quick.w(1F).h(18);

        String[] labels = {"1s", "2s", "5s", "整段"};
        float[] durations = {1F, 2F, 5F, -1F};

        for (int i = 0; i < labels.length; i++)
        {
            final float duration = durations[i];
            UIButton button = new UICompButton(IKey.constant(labels[i]), (b) ->
            {
                if (duration < 0F)
                {
                    layer.trimInTo(0F, this.doc.fps);
                    layer.trimOutTo(this.doc.duration, this.doc.fps, this.doc.duration);
                }
                else
                {
                    layer.trimOutTo(Math.min(this.doc.duration, layer.in + duration), this.doc.fps, this.doc.duration);
                }

                this.commitLayerTiming();
            });

            button.relative(quick).x(i / 4F, 1).y(0).w(1F / 4F, -4).h(18);
            button.tooltip(IKey.constant(duration < 0F ? "让片段铺满整个合成" : "把时长设为 " + labels[i]));
            quick.add(button);
        }

        section.fields.add(quick);

        UIButton toIn = this.smallButton("入点=播放头", () ->
        {
            layer.trimInTo(this.time, this.doc.fps);
            this.commitLayerTiming();
        });
        UIButton toOut = this.smallButton("出点=播放头", () ->
        {
            layer.trimOutTo(this.time, this.doc.fps, this.doc.duration);
            this.commitLayerTiming();
        });
        UIButton jump = this.smallButton("跳到入点", () ->
        {
            this.setFrame(Math.round(layer.in * Math.max(1F, this.doc.fps)));
            this.trackTimeline.revealTime(layer.in);
        });
        UIButton duplicate = this.smallButton("复制", () -> this.duplicateLayer(layer));
        UIButton delete = this.smallButton("删除", () -> this.deleteLayer(layer));

        UIElement row2 = new UIElement();

        row2.w(1F).h(18);
        toIn.relative(row2).x(0).y(0).w(0.5F, -2).h(18);
        toOut.relative(row2).x(0.5F, 2).y(0).w(0.5F, -4).h(18);
        row2.add(toIn, toOut);

        UIElement row3 = new UIElement();

        row3.w(1F).h(18);
        jump.relative(row3).x(0).y(0).w(1F / 3F, -2).h(18);
        duplicate.relative(row3).x(1F / 3F, 1).y(0).w(1F / 3F, -3).h(18);
        delete.relative(row3).x(2F / 3F, 2).y(0).w(1F / 3F, -4).h(18);
        row3.add(jump, duplicate, delete);

        section.fields.add(row2);
        section.fields.add(row3);

        return section;
    }

    private UISection motionSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("运动"));

        section.remember(this.sectionFolds, "motion", true);

        Transform transform = layer.transformAt(this.getFrame());

        section.fields.add(this.animatedNumberRow("位置 X", layer, "x", transform.translate.x, (value) -> this.setTransformX(value)));
        section.fields.add(this.animatedNumberRow("位置 Y", layer, "y", transform.translate.y, (value) -> this.setTransformY(value)));
        section.fields.add(this.animatedNumberRow("缩放 X", layer, "scaleX", transform.scale.x, (value) -> this.setTransformScaleX(value)));
        section.fields.add(this.animatedNumberRow("缩放 Y", layer, "scaleY", transform.scale.y, (value) -> this.setTransformScaleY(value)));
        section.fields.add(this.animatedNumberRow("挤压 / 拉伸", layer, "squash", layer.scalarAt("squash", layer.squash, this.getFrame()), (value) ->
        {
            layer.squash = Math.max(-0.9F, Math.min(0.9F, value.floatValue()));
            this.setScalar(layer, "squash", layer.squash);
        }, -0.9F, 0.9F));
        section.fields.add(this.animatedNumberRow("旋转", layer, "rotation", transform.rotate.z, (value) -> this.setTransformRotation(value)));

        UIToggle threeD = new UIToggle(IKey.constant("3D 图层（跟随摄像机）"), layer.threeD, (toggle) ->
        {
            layer.threeD = toggle.getValue();

            if (layer.threeD && "camera".equals(layer.type))
            {
                layer.camera3D = true;
            }

            this.refreshInspector();
            this.save();
        });

        /* AE semantics, spelled out where the switch is: this is the one control that decides
         * whether the camera affects a layer at all. */
        threeD.tooltip(IKey.constant("AE 语义：只有开启 3D 的图层跟随摄像机透视；2D 图层始终屏幕附着。"
            + "需要 0.91 之前的全图层 3D 行为，可在 设置 → 开发者 切换 3D 逻辑"));

        if ("adjustment".equals(layer.type))
        {
            /* The renderer applies adjustment effects to the whole accumulation buffer, so a
             * "3D" adjustment has no plane to project; disabling the switch is honest. */
            threeD.setEnabled(false);
            threeD.tooltip(IKey.constant("调整层始终作用于整幅合成：3D 开关无效"));
        }

        section.fields.add(threeD);

        if (layer.threeD && !layer.isCamera())
        {
            section.fields.add(this.animatedNumberRow("Z 深度", layer, "z", layer.scalarAt("z", layer.z, this.getFrame()), (value) ->
            {
                layer.z = value.floatValue();
                this.setScalar(layer, "z", layer.z);
            }, -100000F, 100000F));
            section.fields.add(this.animatedNumberRow("旋转 X", layer, "rotationX", layer.scalarAt("rotationX", layer.rotationX, this.getFrame()), (value) ->
            {
                layer.rotationX = value.floatValue();
                this.setScalar(layer, "rotationX", layer.rotationX);
            }, -720F, 720F));
            section.fields.add(this.animatedNumberRow("旋转 Y", layer, "rotationY", layer.scalarAt("rotationY", layer.rotationY, this.getFrame()), (value) ->
            {
                layer.rotationY = value.floatValue();
                this.setScalar(layer, "rotationY", layer.rotationY);
            }, -720F, 720F));
            section.fields.add(this.animatedNumberRow("缩放 Z", layer, "scaleZ", layer.scalarAt("scaleZ", layer.scaleZ, this.getFrame()), (value) ->
            {
                layer.scaleZ = value.floatValue();
                this.setScalar(layer, "scaleZ", layer.scaleZ);
            }, 0.01F, 100F));
        }
        section.fields.add(this.numberRow("锚点 X", layer.anchorX, 0F, 1F, false, (value) -> layer.anchorX = value.floatValue()));
        section.fields.add(this.numberRow("锚点 Y", layer.anchorY, 0F, 1F, false, (value) -> layer.anchorY = value.floatValue()));
        section.fields.add(this.smallButton("重置变换", () ->
        {
            this.beginAction("重置变换");

            /* The base fields are ignored as soon as an axis channel has a key, so resetting only
             * x/rotation/scale did nothing on an animated layer. Clearing the channels first makes
             * the reset actually take effect; all nine components are restored, including the 3D
             * ones, because a layer that was tilted kept its tilt behind a "reset" button. */
            layer.clearAllTransformAxes();
            layer.x = this.doc.width / 2F;
            layer.y = this.doc.height / 2F;
            layer.z = 0F;
            layer.rotation = 0F;
            layer.rotationX = 0F;
            layer.rotationY = 0F;
            layer.scaleX = 1F;
            layer.scaleY = 1F;
            layer.scaleZ = 1F;
            layer.squash = 0F;
            this.refreshInspector();
            this.save();
        }));

        return section;
    }

    /**
     * One-click motion presets. They write transform/opacity keys at the layer's in point, so a
     * preset still fits after the clip is moved or trimmed. Content and effects are untouched.
     */
    private UISection motionTemplateSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("运动模板"));

        section.remember(this.sectionFolds, "motion_templates", false);
        section.fields.add(UI.label(IKey.constant("在片段入点写入关键帧；可再手动微调。"), 13));

        List<CompMotion.Template> templates = CompMotion.all();
        int rows = (templates.size() + 2) / 3;

        UIElement grid = new UIElement();

        grid.w(1F).h(rows * 22);

        for (int i = 0; i < templates.size(); i++)
        {
            CompMotion.Template template = templates.get(i);
            UIButton button = new UICompButton(IKey.constant(template.name), (b) ->
            {
                if (CompMotion.apply(layer, this.doc, template.id))
                {
                    this.openKeyframes();
                    this.onTrackEdited();
                    this.refreshInspector();
                    this.setStatus("已应用运动模板：" + template.name);
                }
                else
                {
                    this.setStatus("片段时长为 0，无法写入运动关键帧");
                }
            });

            button.relative(grid).x((i % 3) / 3F, 1).y((i / 3) * 22).w(1F / 3F, -4).h(20);
            button.tooltip(IKey.constant(template.description));
            grid.add(button);
        }

        section.fields.add(grid);

        return section;
    }

    private UISection cameraSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("摄像机"));

        section.remember(this.sectionFolds, "camera", true);

        float frame = this.getFrame();
        mchorse.bbs_mod.utils.pose.Transform transform = layer.transformAt(frame);
        float targetX = layer.scalarAt("cameraTargetX", layer.cameraTargetX, frame);
        float targetY = layer.scalarAt("cameraTargetY", layer.cameraTargetY, frame);
        float fov = layer.scalarAt("cameraFov", layer.cameraFov, frame);
        float focal = dev.bbsae.client.render.CompCamera.focalFor(fov, this.doc == null ? 1080F : this.doc.height);
        float distance = layer.scalarAt("cameraZ", layer.cameraZ, frame);

        if (Math.abs(distance) < 0.001F)
        {
            distance = focal / Math.max(0.01F, Math.abs(transform.scale.x));
        }

        /* One model: the camera always defines the view. The old "3D 摄像机" switch gated the whole
         * perspective path, which is why a fresh camera layer did nothing until it was turned on. */
        section.fields.add(UI.label(IKey.constant("摄像机同时控制 2D 图层（平移/缩放/旋转）和 3D 图层的透视。"), 14));
        section.fields.add(UI.label(IKey.constant("视口里选中摄像机：拖动 = 平移，Alt+拖动 = 环绕，拖动准星 = 重新取景，滚轮 = 推拉，Alt+滚轮 = 视场。"), 13));
        section.fields.add(UI.label(IKey.constant("倾斜视角后，所有图层都会按各自的 Z 做透视投影（产生视差），不再只是平面移动。"), 13));
        section.fields.add(UI.label(IKey.constant(String.format(java.util.Locale.ROOT,
            "当前：距离 %.0f · 焦距 %.0f · 画面缩放 %.0f%%", distance, focal, focal / Math.max(1F, distance) * 100F)), 14));

        /* Camera authoring model. AE cameras aim at a target; BBS cameras are free-flown and can
         * leave a keyframe at the pilot's stopping point. Both are ordinary camera layers, so a
         * composition can keep one of each and switch the active camera without conversion. */
        UIElement cameraStyles = new UIElement();

        cameraStyles.w(1F).h(20);

        UICompButton aeStyle = new UICompButton(IKey.constant("AE 摄像机"), (button) ->
        {
            if (layer.cameraStyle != CompLayer.CAMERA_AE)
            {
                this.applyCameraStyle(layer, CompLayer.CAMERA_AE);
            }
        });
        UICompButton bbsStyle = new UICompButton(IKey.constant("BBS 原版摄像机"), (button) ->
        {
            if (layer.cameraStyle != CompLayer.CAMERA_BBS)
            {
                this.applyCameraStyle(layer, CompLayer.CAMERA_BBS);
            }
        });

        aeStyle.style(layer.cameraStyle == CompLayer.CAMERA_AE ? UICompButton.Style.SELECTED : UICompButton.Style.FLAT);
        bbsStyle.style(layer.cameraStyle == CompLayer.CAMERA_BBS ? UICompButton.Style.SELECTED : UICompButton.Style.FLAT);
        aeStyle.relative(cameraStyles).x(0).y(0).w(0.5F, -2).h(20);
        bbsStyle.relative(cameraStyles).x(0.5F).y(0).w(0.5F, 0).h(20);
        aeStyle.tooltip(IKey.constant("AE 双节点相机：位置 + 目标点，适合精确构图"));
        bbsStyle.tooltip(IKey.constant("BBS 自由飞行相机：F 进入飞行，WASD + 鼠标控制，退出时在播放头打关键帧"));
        cameraStyles.add(aeStyle, bbsStyle);
        section.fields.add(cameraStyles);

        if (layer.cameraStyle == CompLayer.CAMERA_BBS)
        {
            UICompButton flight = new UICompButton(
                IKey.constant(this.cameraFlying ? "退出飞行（F）" : "✈ 进入飞行模式（F）"),
                (button) -> this.setCameraFlying(!this.cameraFlying));

            flight.symbol(Icons.PLANE);
            flight.tooltip(IKey.constant("BBS 原版飞行：WASD 移动，空格/Shift 升降，鼠标转向，滚轮调速；再按 F 退出并在当前位置留下关键帧"));
            section.fields.add(flight);
            section.fields.add(UI.label(IKey.constant("BBS 相机使用自由偏航/俯仰；退出飞行时自动写入当前位置、朝向、滚转和 FOV 关键帧。AE 相机仍保留在轨道上，可随时切回。"), 14));
            section.fields.add(UI.label(IKey.constant("BBS 快捷键差异：右键拖动 = 3D 视口环绕"), 14));
            section.fields.add(UI.label(IKey.constant("旋转镜头：按 F 进入飞行，用鼠标转向"), 14));
        }

        UIToggle perspective = new UIToggle(IKey.constant("透视投影（关 = 正交/平面）"), layer.camera3D, (toggle) ->
        {
            layer.camera3D = toggle.getValue();
            this.save();
        });

        perspective.tooltip(IKey.constant("透视：3D 图层近大远小；正交：3D 图层保留位移与缩放，但不会因 Z 改变大小"));
        section.fields.add(perspective);

        section.fields.add(this.animatedNumberRow("位置 X", layer, "x", transform.translate.x, this::setTransformX, -100000F, 100000F));
        section.fields.add(this.animatedNumberRow("位置 Y", layer, "y", transform.translate.y, this::setTransformY, -100000F, 100000F));
        section.fields.add(this.animatedNumberRow("距离 Z", layer, "cameraZ", distance, (value) ->
        {
            float next = Math.max(1F, value.floatValue());

            layer.cameraZ = next;
            layer.z = next;
            this.setScalar(layer, "cameraZ", next);
        }, 1F, 1000000F));
        section.fields.add(this.animatedNumberRow("视场 FOV", layer, "cameraFov", fov, (value) ->
        {
            layer.cameraFov = Math.max(1F, Math.min(179F, value.floatValue()));
            this.setScalar(layer, "cameraFov", layer.cameraFov);
        }, 1F, 179F));
        section.fields.add(this.animatedNumberRow("旋转（滚转）", layer, "rotation", transform.rotate.z, this::setTransformRotation, -360F, 360F));

        if (layer.cameraStyle == CompLayer.CAMERA_AE)
        {
            section.fields.add(UI.label(IKey.constant("目标点"), 15));
            section.fields.add(this.animatedNumberRow("目标 X", layer, "cameraTargetX", targetX, (value) ->
            {
                layer.cameraTargetX = value.floatValue();
                this.setScalar(layer, "cameraTargetX", value.floatValue());
            }, -100000F, 100000F));
            section.fields.add(this.animatedNumberRow("目标 Y", layer, "cameraTargetY", targetY, (value) ->
            {
                layer.cameraTargetY = value.floatValue();
                this.setScalar(layer, "cameraTargetY", value.floatValue());
            }, -100000F, 100000F));
            section.fields.add(this.animatedNumberRow("目标 Z", layer, "cameraTargetZ", layer.scalarAt("cameraTargetZ", layer.cameraTargetZ, frame), (value) ->
            {
                layer.cameraTargetZ = value.floatValue();
                this.setScalar(layer, "cameraTargetZ", layer.cameraTargetZ);
            }, -100000F, 100000F));

            UIToggle lookAt = new UIToggle(IKey.constant("看向目标"), layer.cameraLookAt, (toggle) ->
            {
                layer.cameraLookAt = toggle.getValue();
                this.save();
            });

            lookAt.tooltip(IKey.constant("开：镜头始终对准目标点（改为自由镜头时用下面的俯仰/偏航）"));
            section.fields.add(lookAt);
        }

        section.fields.add(this.animatedNumberRow("俯仰 X", layer, "rotationX", layer.scalarAt("rotationX", layer.rotationX, frame), (value) ->
        {
            layer.rotationX = value.floatValue();
            this.setScalar(layer, "rotationX", layer.rotationX);
        }, -89F, 89F));
        section.fields.add(this.animatedNumberRow("偏航 Y", layer, "rotationY", layer.scalarAt("rotationY", layer.rotationY, frame), (value) ->
        {
            layer.rotationY = value.floatValue();
            this.setScalar(layer, "rotationY", layer.rotationY);
        }, -180F, 180F));

        if (layer.camera3D)
        {
            section.fields.add(this.numberRow("近裁剪", layer.cameraNear, 0.1F, 10000F, false, (value) -> layer.cameraNear = Math.max(0.1F, value.floatValue())));
            section.fields.add(this.numberRow("远裁剪", layer.cameraFar, 100F, 1000000F, false, (value) -> layer.cameraFar = Math.max(layer.cameraNear + 1F, value.floatValue())));
        }

        section.fields.add(this.smallButton("重置摄像机", () ->
        {
            layer.x = this.doc.width / 2F;
            layer.y = this.doc.height / 2F;
            layer.cameraZ = this.defaultCameraZ();
            layer.z = layer.cameraZ;
            layer.scaleX = 1F;
            layer.scaleY = 1F;
            layer.rotation = 0F;
            layer.rotationX = 0F;
            layer.rotationY = 0F;
            layer.cameraTargetX = this.doc.width / 2F;
            layer.cameraTargetY = this.doc.height / 2F;
            layer.cameraTargetZ = 0F;
            layer.cameraLookAt = layer.cameraStyle != CompLayer.CAMERA_BBS;
            layer.removeChannel("cameraZ");
            layer.removeChannel("cameraTargetX");
            layer.removeChannel("cameraTargetY");
            this.refreshInspector();
            this.save();
        }));

        section.fields.add(this.smallButton("对齐到当前视图（2D 平移/缩放归零）", () ->
        {
            layer.x = this.doc.width / 2F;
            layer.y = this.doc.height / 2F;
            layer.cameraZ = this.defaultCameraZ();
            layer.z = layer.cameraZ;
            layer.rotation = 0F;
            layer.cameraTargetX = this.doc.width / 2F;
            layer.cameraTargetY = this.doc.height / 2F;
            layer.cameraTargetZ = 0F;
            layer.cameraLookAt = layer.cameraStyle != CompLayer.CAMERA_BBS;
            this.refreshInspector();
            this.save();
        }));

        UIButton active = new UICompButton(IKey.constant(this.doc.activeCameraId.equals(layer.id) ? "当前活动摄像机" : "设为活动摄像机"), (button) ->
        {
            this.doc.activeCameraId = layer.id;
            this.refreshInspector();
            this.save();
            this.setStatus("已切换到摄像机：" + this.layerLabel(layer));
        });
        section.fields.add(active);

        if (!this.doc.activeCameraId.isEmpty())
        {
            section.fields.add(this.smallButton("自动选择摄像机（最上层）", () ->
            {
                this.doc.activeCameraId = "";
                this.refreshInspector();
                this.save();
            }));
        }

        section.fields.add(UI.label(IKey.constant("提示：预览里拖摄像机本体 = 平移；拖橙色准星 = 瞄准；滚轮推拉；Alt+滚轮改视场。"), 13));

        return section;
    }

    private UISection opacitySection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("不透明度"));

        section.remember(this.sectionFolds, "opacity", true);
        section.fields.add(this.animatedSliderRow("不透明度", layer, "opacity", layer.opacityFrame(this.getFrame()), (value) -> this.setOpacity(layer, value.floatValue()), 0F, 1F));

        /* A dropdown instead of a click-to-cycle button: clicking through a couple of dozen modes
         * is impossible to aim at, and the user needs to see what the modes are called. */
        java.util.List<Integer> blendModes = new java.util.ArrayList<>();

        for (int i = 0; i <= CompLayer.BLEND_MAX; i++)
        {
            blendModes.add(i);
        }

        UIChoiceButton<Integer> blend = new UIChoiceButton<>(blendModes, (mode) -> Icons.NONE, (mode) -> IKey.constant(blendName(mode)));

        blend.setValue(layer.blendMode);
        blend.callback((mode) ->
        {
            layer.blendMode = mode;
            this.save();
        });

        section.fields.add(this.fieldRow("混合模式", blend));

        return section;
    }

    private UISection effectHeaderSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("效果"));

        section.remember(this.sectionFolds, "effects", true);
        section.fields.add(UI.label(IKey.constant("已应用 " + layer.effects.size() + " 个效果，按从上到下顺序渲染。"), 16));
        section.fields.add(new UICompButton(IKey.constant("在左侧效果库添加效果…"), (button) -> this.focusEffectLibrary()));

        return section;
    }

    private CompEffect findEffect(CompLayer layer, String type)
    {
        if (layer == null)
        {
            return null;
        }

        for (CompEffect effect : layer.effects)
        {
            if (effect != null && type.equals(effect.type))
            {
                return effect;
            }
        }

        return null;
    }

    private UIElement gradeRow(CompLayer layer, CompEffect effect, String label, String key, float min, float max)
    {
        String path = CompLayer.effectPath(effect.id, key);
        float fallback = effect.valueF(key, "saturation".equals(key) ? 1F : 0F);

        fallback = layer.scalarAt(path, fallback, this.getFrame());

        return this.animatedNumberRow(label, layer, path, fallback, (value) -> effect.put(key, value), min, max);
    }

    private static final String[] CURVE_KEYS = {
        "curve_black", "curve_shadow", "curve_mid", "curve_highlight", "curve_white"
    };

    private static final String[] CURVE_LABELS = {"黑", "阴影", "中间调", "高光", "白"};

    /**
     * Graphical grade page: three colour wheels (lift / gamma / gain), a master tone curve and the
     * numeric controls below. Values live on a real {@code color_grade} effect, so the grade sits
     * in the normal effect stack and can be reordered or keyframed like every other effect.
     */
    private UISection gradeSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("调色"));

        if (layer == null)
        {
            section.fields.add(UI.label(IKey.constant("选中一条轨道后添加调色效果。"), 14));
            section.fields.add(new UICompButton(IKey.constant("＋ 为整个合成添加调色调整层"), (button) -> this.addGradeAdjustment()));
            section.fields.add(UI.label(IKey.constant("调整层只影响它下面的所有图层，等价于 AE 的调整图层。"), 13));
            section.fields.add(new UICompButton(IKey.constant("选择最上层轨道"), (button) ->
            {
                if (this.doc != null && !this.doc.layers.isEmpty())
                {
                    this.selectLayer(this.doc.layers.get(this.doc.layers.size() - 1));
                }
            }));

            return section;
        }

        CompEffect grade = this.findEffect(layer, "color_grade");

        if (grade == null)
        {
            section.fields.add(UI.label(IKey.constant("三段色轮 + 主曲线 + 曝光/对比度/饱和度/色温。参数都能打关键帧。"), 14));
            section.fields.add(new UICompButton(IKey.constant("＋ 添加调色效果"), (button) ->
            {
                this.beginAction("添加调色效果");

                CompEffect added = CompEffectRegistry.create("color_grade", layer.effects.size() + 1);

                /* "fx" + size+1 collided after a deletion: a layer with [fx1, fx2] that loses fx1
                 * has size 1, so the new grade took the id "fx2" and every parameter edit and every
                 * keyframe landed on the old effect that happened to come first in the list. */
                added.id = CompStore.uniqueEffectId(layer);
                layer.effects.add(added);
                this.refreshInspector();
                this.save();
            }));

            return section;
        }

        section.fields.add(UI.label(IKey.constant("色轮中心为中性；拖出色彩，下方滑条调亮度。"), 13));
        section.fields.add(this.gradeWheels(grade));
        section.fields.add(UI.label(IKey.constant("主曲线（横轴：输入亮度　纵轴：输出亮度）"), 13));
        section.fields.add(this.gradeCurve(grade));
        section.fields.add(this.gradePresetRow(layer, grade));

        section.fields.add(this.gradeRow(layer, grade, "曝光", "exposure", -3F, 3F));
        section.fields.add(this.gradeRow(layer, grade, "对比度", "contrast", -1F, 1F));
        section.fields.add(this.gradeRow(layer, grade, "饱和度", "saturation", 0F, 2F));
        section.fields.add(this.gradeRow(layer, grade, "自然饱和度", "vibrance", -1F, 1F));
        section.fields.add(this.gradeRow(layer, grade, "色温", "temperature", -1F, 1F));
        section.fields.add(this.gradeRow(layer, grade, "色调", "tint", -1F, 1F));
        section.fields.add(this.gradeRow(layer, grade, "色相旋转", "hue", -180F, 180F));
        section.fields.add(this.gradeRow(layer, grade, "褪色 / 胶片", "fade", 0F, 1F));

        section.fields.add(this.smallButton("重置调色", () ->
        {
            applyGradePreset(grade, "neutral");
            this.refreshInspector();
            this.save();
        }));
        section.fields.add(this.smallButton("移除调色", () ->
        {
            this.beginAction("移除调色");
            this.removeEffectChannels(layer, grade.id);
            layer.effects.remove(grade);
            this.refreshInspector();
            this.save();
        }));

        return section;
    }

    private UIElement gradeWheels(CompEffect grade)
    {
        UIElement row = new UIElement();

        row.w(1F).h(116);

        String[] labels = {"阴影 Lift", "中间调 Gamma", "高光 Gain"};
        String[] keys = {"lift", "gamma", "gain"};

        for (int i = 0; i < 3; i++)
        {
            final String key = keys[i];
            UIColorWheel wheel = new UIColorWheel(labels[i], grade.color(key, 0xFF808080), (value) ->
                grade.putColor(key, value)).commit(this::save);

            wheel.relative(row).x(i / 3F, i == 0 ? 0 : 2).y(0).w(1F / 3F, -6).h(116);
            row.add(wheel);
        }

        return row;
    }

    private UIElement gradeCurve(CompEffect grade)
    {
        float[] values = new float[CURVE_KEYS.length];

        for (int i = 0; i < CURVE_KEYS.length; i++)
        {
            values[i] = grade.valueF(CURVE_KEYS[i], 0F);
        }

        UICurveEditor curve = new UICurveEditor(values, (index, value) ->
        {
            grade.put(CURVE_KEYS[index], value.doubleValue());
        }, this::save);

        curve.w(1F).h(112);

        return curve;
    }

    private UIElement gradePresetRow(CompLayer layer, CompEffect grade)
    {
        UIElement row = new UIElement();

        row.w(1F).h(18);

        String[] labels = {"中性", "电影", "暖阳", "冷峻", "黑白"};
        String[] ids = {"neutral", "cinematic", "warm", "cool", "mono"};

        for (int i = 0; i < labels.length; i++)
        {
            final String id = ids[i];
            UIButton button = new UICompButton(IKey.constant(labels[i]), (b) ->
            {
                applyGradePreset(grade, id);
                this.refreshInspector();
                this.save();
            });

            button.relative(row).x(i / 5F, 1).y(0).w(1F / 5F, -4).h(18);
            button.tooltip(IKey.constant("应用调色预设：" + labels[i]));
            row.add(button);
        }

        return row;
    }

    private static void applyGradePreset(CompEffect grade, String id)
    {
        grade.put("exposure", 0D).put("contrast", 0D).put("saturation", 1D)
            .put("vibrance", 0D).put("temperature", 0D).put("tint", 0D)
            .put("hue", 0D).put("fade", 0D);
        grade.putColor("lift", 0xFF808080).putColor("gamma", 0xFF808080).putColor("gain", 0xFF808080);

        for (String key : CURVE_KEYS)
        {
            grade.put(key, 0D);
        }

        switch (id)
        {
            case "cinematic" ->
            {
                grade.putColor("lift", 0xFF74808F).putColor("gain", 0xFFFFE2C0);
                grade.put("contrast", 0.12D).put("saturation", 1.06D).put("temperature", 0.06D);
                grade.put("curve_shadow", -0.03D).put("curve_highlight", 0.04D);
            }
            case "warm" ->
            {
                grade.putColor("lift", 0xFF8A7B6C).putColor("gain", 0xFFFFD7A6);
                grade.put("temperature", 0.18D).put("saturation", 1.08D);
            }
            case "cool" ->
            {
                grade.putColor("lift", 0xFF6C7C90).putColor("gain", 0xFFC6DAFF);
                grade.put("temperature", -0.16D).put("saturation", 0.92D).put("contrast", 0.08D);
            }
            case "mono" ->
            {
                grade.put("saturation", 0D).put("contrast", 0.16D);
                grade.put("curve_shadow", -0.02D).put("curve_highlight", 0.03D);
            }
            default ->
            {}
        }
    }

    private UISection effectControlSection(CompLayer layer, CompEffect effect)
    {
        CompEffectRegistry.Definition definition = CompEffectRegistry.get(effect.type);
        String name = definition == null ? effect.type : definition.name;
        int index = layer.effects.indexOf(effect) + 1;
        UISection section = new UISection(IKey.constant(index + ". " + name));

        section.remember(this.sectionFolds, "fx:" + layer.id + ":" + effect.id, true);

        UIToggle enabled = new UIToggle(IKey.constant("启用"), effect.enabled, (toggle) ->
        {
            this.beginAction("启用/禁用效果");
            effect.enabled = toggle.getValue();
            this.save();
        });
        UIIcon up = new UIIcon(Icons.MOVE_UP, (icon) -> this.moveEffect(layer, effect, -1));
        UIIcon down = new UIIcon(Icons.MOVE_DOWN, (icon) -> this.moveEffect(layer, effect, 1));
        UIIcon remove = new UIIcon(Icons.TRASH, (icon) ->
        {
            this.beginAction("删除效果");
            /* Effect ids are recycled (smallest free fxN); orphan fx/<id>/* channels would be
             * silently re-attached to the next effect that gets the same id. */
            this.removeEffectChannels(layer, effect.id);
            layer.effects.remove(effect);
            this.refreshInspector();
            this.save();
        });

        UIElement controls = new UIElement();

        controls.w(1F).h(18);
        enabled.relative(controls).x(0).y(1).w(Math.max(46, 64)).h(16);
        up.relative(controls).x(1F, -56).y(1).w(16).h(16);
        down.relative(controls).x(1F, -38).y(1).w(16).h(16);
        remove.relative(controls).x(1F, -20).y(1).w(16).h(16);
        controls.add(enabled, up, down, remove);
        section.fields.add(controls);

        if (definition == null)
        {
            section.fields.add(UI.label(IKey.constant("未知效果：" + effect.type), 16));
            return section;
        }

        /* Every effect gets a strength row: 0 is exactly the original layer, 1 is the raw effect,
         * and the value is keyframeable like any other parameter. This is what makes a gradual
         * "effect fades in" possible even for generative effects that have no natural zero. */
        String strengthPath = CompLayer.effectPath(effect.id, dev.bbsae.client.render.EffectRenderer.STRENGTH);
        float strength = layer.scalarAt(strengthPath,
            (float) effect.value(dev.bbsae.client.render.EffectRenderer.STRENGTH, 1D), this.getFrame());

        section.fields.add(this.animatedNumberRow("效果强度（0 = 原图层）", layer, strengthPath, strength,
            (value) -> effect.put(dev.bbsae.client.render.EffectRenderer.STRENGTH, value), 0F, 1F));

        if (definition.params.isEmpty() && definition.colorParams.isEmpty())
        {
            section.fields.add(UI.label(IKey.constant("这个效果没有其他可调参数；用上面的强度做淡入淡出。"), 16));
        }

        if (!definition.params.isEmpty())
        {
            UICompButton keyAll = new UICompButton(IKey.constant("AE 关键帧"), (button) ->
                this.keyEffectAtPlayhead(layer, effect, definition));

            keyAll.hint("在当前播放头为这个效果的所有可动画参数自动添加独立关键帧，并在下方 AE 曲线中展开该属性");
            keyAll.w(1F).h(20);
            section.fields.add(keyAll);
        }

        if (effect.type != null && effect.type.startsWith("depth_"))
        {
            section.fields.add(UI.label(IKey.constant("提示：深度类效果需要影片深度通道；纯合成预览没有真实世界深度，可能看不到变化。"), 13));
        }
        else if ("brightness_contrast".equals(effect.type) || "hue_saturation".equals(effect.type)
            || "exposure".equals(effect.type) || "levels".equals(effect.type) || "curves".equals(effect.type))
        {
            section.fields.add(UI.label(IKey.constant("提示：这些是中性校正效果，参数为 0 时画面不变；拖动滑块或打关键帧后才可见。"), 13));
        }

        for (CompEffectRegistry.Param param : definition.params)
        {
            float fallback = (float) effect.value(param.key, param.defaultValue);
            String path = CompLayer.effectPath(effect.id, param.key);

            fallback = layer.scalarAt(path, fallback, this.getFrame());
            section.fields.add(this.animatedNumberRow(param.label, layer, path, fallback, (value) -> effect.put(param.key, value), param.min, param.max));
        }

        for (CompEffectRegistry.ColorParam param : definition.colorParams)
        {
            int color = effect.color(param.key, param.defaultValue);

            section.fields.add(this.colorRow(param.label, color, (value) ->
            {
                effect.putColor(param.key, value);
                this.save();
            }));
        }

        return section;
    }

    /**
     * Keys every animatable parameter of an effect at the playhead.
     *
     * <p>This is the explicit "AE keyframe" affordance for an effect: one undo checkpoint, one
     * status line, independent X/Y/Z-like scalar channels per parameter, and the graph is focused
     * on the first parameter so the newly appeared keys are visible immediately.</p>
     */
    private void keyEffectAtPlayhead(CompLayer layer, CompEffect effect, CompEffectRegistry.Definition definition)
    {
        if (layer == null || effect == null || definition == null)
        {
            return;
        }

        this.beginAction("添加效果 AE 关键帧");

        float frame = this.getFrame();
        String firstPath = CompLayer.effectPath(effect.id, dev.bbsae.client.render.EffectRenderer.STRENGTH);
        float strength = layer.scalarAt(firstPath,
            (float) effect.value(dev.bbsae.client.render.EffectRenderer.STRENGTH, 1D), frame);

        /* Make sure the parameter also has a base value, so resolveEffect has a fallback even
         * before the first keyframe (and for projects saved before the strength existed). */
        effect.put(dev.bbsae.client.render.EffectRenderer.STRENGTH, (double) strength);
        layer.scalarChannel(firstPath).insertInheriting(frame, strength);

        for (CompEffectRegistry.Param param : definition.params)
        {
            String path = CompLayer.effectPath(effect.id, param.key);
            float value = layer.scalarAt(path, (float) effect.value(param.key, param.defaultValue), frame);

            layer.scalarChannel(path).insertInheriting(frame, value);
        }

        this.refreshSheets();
        this.refreshInspector();
        this.showCurveFor(firstPath);
        this.setStatus("已为「" + CompEffectRegistry.displayName(effect.type) + "」添加 "
            + (definition.params.size() + 1) + " 个 AE 关键帧（含效果强度）");
        this.save();
    }

    private void moveEffect(CompLayer layer, CompEffect effect, int direction)
    {
        int index = layer.effects.indexOf(effect);
        int target = Math.max(0, Math.min(layer.effects.size() - 1, index + direction));

        if (index < 0 || target == index)
        {
            return;
        }

        this.beginAction("调整效果顺序");
        layer.effects.remove(index);
        layer.effects.add(target, effect);
        this.refreshInspector();
        this.save();
    }

    private UISection styleHeaderSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("图层样式"));

        section.remember(this.sectionFolds, "styles", false);

        UIButton add = new UICompButton(IKey.constant("添加样式…"), (button) -> this.openStylePicker(layer));

        add.context((manager) ->
        {
            manager.action(Icons.FADING, IKey.constant("投影"), () -> this.addStyle(layer, CompStyle.DROP_SHADOW));
            manager.action(Icons.FADING, IKey.constant("内阴影"), () -> this.addStyle(layer, CompStyle.INNER_SHADOW));
            manager.action(Icons.LIGHT, IKey.constant("外发光"), () -> this.addStyle(layer, CompStyle.OUTER_GLOW));
            manager.action(Icons.LIGHT, IKey.constant("内发光"), () -> this.addStyle(layer, CompStyle.INNER_GLOW));
            manager.action(Icons.SUN, IKey.constant("斜面和浮雕"), () -> this.addStyle(layer, CompStyle.BEVEL));
            manager.action(Icons.COLOR, IKey.constant("颜色叠加"), () -> this.addStyle(layer, CompStyle.COLOR_OVERLAY));
            manager.action(Icons.COLOR, IKey.constant("渐变叠加"), () -> this.addStyle(layer, CompStyle.GRADIENT_OVERLAY));
            manager.action(Icons.LIGHT, IKey.constant("光泽"), () -> this.addStyle(layer, CompStyle.SATIN));
            manager.action(Icons.OUTLINE, IKey.constant("描边"), () -> this.addStyle(layer, CompStyle.STROKE));
        });
        section.fields.add(add);

        return section;
    }

    private void openStylePicker(CompLayer layer)
    {
        List<String> labels = List.of("投影", "内阴影", "外发光", "内发光", "斜面和浮雕", "颜色叠加", "渐变叠加", "光泽", "描边");
        List<String> types = List.of(
            CompStyle.DROP_SHADOW, CompStyle.INNER_SHADOW, CompStyle.OUTER_GLOW, CompStyle.INNER_GLOW,
            CompStyle.BEVEL, CompStyle.COLOR_OVERLAY, CompStyle.GRADIENT_OVERLAY, CompStyle.SATIN, CompStyle.STROKE
        );
        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("添加图层样式"), (value) ->
        {
            int index = labels.indexOf(value);

            if (index >= 0)
            {
                this.addStyle(layer, types.get(index));
            }
        });

        panel.addValues(labels);
        UIOverlay.addOverlay(this.getContext(), panel, 260, 300);
    }

    private void moveStyle(CompLayer layer, CompStyle style, int direction)
    {
        int index = layer.styles.indexOf(style);
        int target = Math.max(0, Math.min(layer.styles.size() - 1, index + direction));

        if (index < 0 || target == index)
        {
            return;
        }

        this.beginAction("调整图层样式顺序");
        layer.styles.remove(index);
        layer.styles.add(target, style);
        this.refreshInspector();
        this.save();
    }

    private UISection styleControlSection(CompLayer layer, CompStyle style)
    {
        UISection section = new UISection(IKey.constant(style.type));

        section.remember(this.sectionFolds, "style:" + layer.id + ":" + style.type + ":" + layer.styles.indexOf(style), false);

        UIToggle enabled = new UIToggle(IKey.constant("启用"), style.enabled, (toggle) ->
        {
            style.enabled = toggle.getValue();
            this.save();
        });
        UIIcon up = new UIIcon(Icons.MOVE_UP, (icon) -> this.moveStyle(layer, style, -1));
        UIIcon down = new UIIcon(Icons.MOVE_DOWN, (icon) -> this.moveStyle(layer, style, 1));
        UIIcon remove = new UIIcon(Icons.TRASH, (icon) ->
        {
            this.beginAction("删除图层样式");
            layer.styles.remove(style);
            this.refreshInspector();
            this.save();
        });

        UIElement controls = new UIElement();

        controls.w(1F).h(18);
        enabled.relative(controls).x(0).y(1).w(64).h(16);
        up.relative(controls).x(1F, -56).y(1).w(16).h(16);
        down.relative(controls).x(1F, -38).y(1).w(16).h(16);
        remove.relative(controls).x(1F, -20).y(1).w(16).h(16);
        controls.add(enabled, up, down, remove);
        section.fields.add(controls);

        section.fields.add(this.colorRow("颜色", style.color, (color) -> style.color = color));
        section.fields.add(this.colorRow("颜色 2", style.color2, (color) -> style.color2 = color));
        section.fields.add(this.numberRow("不透明度", style.opacity, 0F, 1F, false, (value) -> style.opacity = value.floatValue()));
        section.fields.add(this.numberRow("大小", style.size, 0F, 200F, false, (value) -> style.size = value.floatValue()));
        section.fields.add(this.numberRow("距离", style.distance, -300F, 300F, false, (value) -> style.distance = value.floatValue()));
        section.fields.add(this.numberRow("角度", style.angle, -180F, 180F, false, (value) -> style.angle = value.floatValue()));
        section.fields.add(this.numberRow("模糊", style.blur, 0F, 100F, false, (value) -> style.blur = value.floatValue()));

        return section;
    }

    private UISection outputPresetSection()
    {
        UISection section = new UISection(IKey.constant("输出模块"));

        if (this.project == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("添加 H.264 预设", () ->
        {
            CompOutputPreset preset = CompOutputPreset.reviewH264();

            preset.id = CompStore.uniquePresetId(this.project, "review_h264");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("添加 ProRes 422 HQ", () ->
        {
            CompOutputPreset preset = CompOutputPreset.masterProRes();

            preset.id = CompStore.uniquePresetId(this.project, "master_prores");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("添加 ProRes 4444 Alpha", () ->
        {
            CompOutputPreset preset = CompOutputPreset.alphaProRes();

            preset.id = CompStore.uniquePresetId(this.project, "alpha_prores4444");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("添加音频 WAV（48k 立体声）", () ->
        {
            CompOutputPreset preset = CompOutputPreset.audioWav();

            preset.id = CompStore.uniquePresetId(this.project, "audio_wav");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("添加音频 M4A（AAC 192k）", () ->
        {
            CompOutputPreset preset = CompOutputPreset.audioM4a();

            preset.id = CompStore.uniquePresetId(this.project, "audio_m4a");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));

        for (CompOutputPreset preset : this.project.outputPresets)
        {
            section.fields.add(UI.label(IKey.constant("[" + preset.id + "]  " + ("audio".equals(preset.kind) ? "音频模块" : "视频模块")), 14));
            section.fields.add(this.textRow("名称", preset.name, (value) -> preset.name = value));
            section.fields.add(this.textRow("容器", preset.container, (value) -> preset.container = value));
            section.fields.add(this.textRow("编码器", preset.codec, (value) -> preset.codec = value));
            section.fields.add(this.textRow("扩展名", preset.extension, (value) -> preset.extension = value));
            section.fields.add(new UIToggle(IKey.constant("透明度"), preset.alpha, (toggle) -> preset.alpha = toggle.getValue()));
            section.fields.add(this.numberRow("画质 CRF", preset.crf, 0, 63, true, (value) -> preset.crf = value.intValue()));
            section.fields.add(this.textRow("码率", preset.bitrate, (value) -> preset.bitrate = value));
            section.fields.add(this.textRow("像素格式", preset.pixelFormat, (value) -> preset.pixelFormat = value));
            /* Audio is a property of the module, not of the composition: the mix always comes from
             * the comp's audio track, the codec comes from here. */
            section.fields.add(this.textRow("音频编码（aac / pcm_s16le）", preset.audioCodec, (value) -> preset.audioCodec = value));
            section.fields.add(this.textRow("音频码率（如 192k，可空）", preset.audioBitrate, (value) -> preset.audioBitrate = value));
            section.fields.add(this.numberRow("宽度（0 = 跟随合成）", preset.width, 0, 16384, true, (value) -> preset.width = value.intValue()));
            section.fields.add(this.numberRow("高度（0 = 跟随合成）", preset.height, 0, 16384, true, (value) -> preset.height = value.intValue()));
            section.fields.add(this.numberRow("缩放", preset.scale, 0.05F, 4F, false, (value) -> preset.scale = value.floatValue()));
            section.fields.add(this.numberRow("帧率（0 = 跟随合成）", preset.fps, 0, 240, true, (value) -> preset.fps = value.intValue()));
            section.fields.add(this.textRow("完成后动作", preset.postAction, (value) -> preset.postAction = value));
            section.fields.add(new UIToggle(IKey.constant("保留帧"), preset.keepFrames, (toggle) -> preset.keepFrames = toggle.getValue()));
            section.fields.add(new UICompButton(IKey.constant("移除预设"), (button) ->
            {
                this.beginAction("移除输出预设");
                this.project.outputPresets.remove(preset);
                this.presetIndex = Math.min(this.presetIndex, Math.max(0, this.project.outputPresets.size() - 1));
                this.refreshInspector();
                this.save();
            }));
        }

        return section;
    }

    private UISection compSection()
    {
        UISection section = new UISection(IKey.constant("合成"));
        CompDocument comp = this.doc;

        section.fields.add(this.textRow("合成名称", comp.name, (value) ->
        {
            if (value != null && !value.isBlank())
            {
                comp.name = value.trim();
                this.updateCompPath();
                this.trackTimeline.refresh();
                this.save();
            }
        }));

        section.fields.add(this.numberRow("宽度", comp.width, 1, 16384, true, (value) -> comp.width = value.intValue()));
        section.fields.add(this.numberRow("高度", comp.height, 1, 16384, true, (value) -> comp.height = value.intValue()));
        section.fields.add(this.numberRow("帧率", comp.fps, 1, 240, false, (value) -> comp.fps = value.floatValue()));
        section.fields.add(this.numberRow("时长", comp.duration, 0.1F, 36000F, false, (value) ->
        {
            float previous = comp.duration;

            comp.duration = Math.max(0.1F, value.floatValue());

            /* The work area is the playback loop. It used to stay at the 12-second default after
             * the composition duration was raised, so the compositor appeared to stop at 12s
             * even though the timeline and the project said 30s. Only follow the duration when
             * the work area was the old full-length range; a deliberately shorter loop stays. */
            if (comp.workAreaOut <= previous + 0.001F || comp.workAreaOut > comp.duration)
            {
                comp.workAreaOut = comp.duration;
            }

            if (comp.workAreaIn > comp.duration)
            {
                comp.workAreaIn = 0F;
            }

            this.time = Math.min(this.time, comp.duration);

            if (this.film != null && this.project != null && this.doc != null)
            {
                /* The camera clip is what BBS's recorder uses for its length. Keep it in sync
                 * with the composition as soon as the user edits the duration. */
                CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id, this.doc.duration);
                this.syncBbsLoopToDocument();

                if (this.filmPanel != null)
                {
                    this.filmPanel.forceSave();
                }
            }

            this.save();
            this.refreshSheets();
        }));
        section.fields.add(this.colorRow("背景", comp.background, (color) -> comp.background = color));

        if (this.project != null && this.project.settings != null)
        {
            CompProject.CompSettings settings = this.project.settings;

            section.fields.add(new UIToggle(IKey.constant("网格"), settings.showGrid, (toggle) -> settings.showGrid = toggle.getValue()));
            section.fields.add(this.numberRow("网格大小", settings.gridSize, 8, 512, true, (value) -> settings.gridSize = value.intValue()));
            section.fields.add(new UIToggle(IKey.constant("安全框"), settings.showSafeArea, (toggle) -> settings.showSafeArea = toggle.getValue()));
            section.fields.add(new UIToggle(IKey.constant("景深效果"), settings.depthEnabled, (toggle) -> settings.depthEnabled = toggle.getValue()));
            section.fields.add(new UIToggle(IKey.constant("自动保存"), settings.autoSave, (toggle) -> settings.autoSave = toggle.getValue()));
        }

        this.appendAudioSection(section, comp);

        return section;
    }

    /* --------------------------------------------------------------- audio track */

    /**
     * Audio lives on the composition, not on the output module.
     *
     * <p>A music bed has an in-point, a duration and a gain exactly like a layer does, and the same
     * mix has to feed three places: the video export's AAC track, the standalone WAV module and the
     * keep-frames option of the PNG sequence. Storing it once on the document is what makes those
     * three agree; storing it in the preset would duplicate it per output format.</p>
     */
    private void appendAudioSection(UISection section, CompDocument comp)
    {
        section.fields.add(UI.label(IKey.constant("音频轨道 · 导出视频时自动混入"), 15));
        section.fields.add(this.smallButton("导入音频…", this::importAudioFile));

        if (comp.audio.isEmpty())
        {
            section.fields.add(UI.label(IKey.constant("还没有音频。支持 WAV / OGG；其它格式请先转换。"), 13));
        }

        for (dev.bbsae.comp.CompAudio clip : new ArrayList<>(comp.audio))
        {
            section.fields.add(this.audioRow(comp, clip));
        }

        if (!comp.audio.isEmpty())
        {
            /* Outside the per-clip block on purpose: exporting the mix must not require selecting
             * a clip first. It used to appear only after a clip was picked, so "audio export" was
             * invisible in exactly the state a new user is in. */
            section.fields.add(this.smallButton("导出音频（WAV 48k）", this::exportAudioOnly));
        }

        dev.bbsae.comp.CompAudio clip = this.selectedAudio;

        if (clip == null || !comp.audio.contains(clip))
        {
            return;
        }

        section.fields.add(UI.label(IKey.constant("选中：" + clip.name), 14));
        section.fields.add(this.numberRow("入点（秒）", clip.start, 0D, 36000D, false, (value) ->
        {
            clip.start = Math.max(0F, value.floatValue());
            this.save();
        }));
        section.fields.add(this.numberRow("源偏移（秒）", clip.offset, 0D, 36000D, false, (value) ->
        {
            clip.offset = Math.max(0F, value.floatValue());
            this.save();
        }));
        section.fields.add(this.numberRow("时长（-1 全长）", clip.duration, -1D, 36000D, false, (value) ->
        {
            clip.duration = value.floatValue() <= 0F ? -1F : value.floatValue();
            this.save();
        }));
        section.fields.add(this.numberRow("增益（%）", clip.gain * 100F, 0D, 400D, true, (value) ->
        {
            clip.gain = Math.max(0F, value.floatValue() / 100F);
            this.save();
        }));
        section.fields.add(this.numberRow("淡入（秒）", clip.fadeIn, 0D, 60D, false, (value) ->
        {
            clip.fadeIn = Math.max(0F, value.floatValue());
            this.save();
        }));
        section.fields.add(this.numberRow("淡出（秒）", clip.fadeOut, 0D, 60D, false, (value) ->
        {
            clip.fadeOut = Math.max(0F, value.floatValue());
            this.save();
        }));

        UIToggle loop = new UIToggle(IKey.constant("循环"), clip.loop, (toggle) ->
        {
            clip.loop = toggle.getValue();
            this.save();
        });
        section.fields.add(loop);

        UIToggle muted = new UIToggle(IKey.constant("静音"), clip.muted, (toggle) ->
        {
            clip.muted = toggle.getValue();
            this.save();
        });
        section.fields.add(muted);

        section.fields.add(this.smallButton("移除这条音频", () ->
        {
            this.beginAction("移除音频");
            comp.audio.remove(clip);
            this.selectedAudio = null;
            dev.bbsae.client.audio.CompAudioPlayer.invalidate();
            this.refreshInspector();
            this.save();
            this.setStatus("已移除音频");
        }));

    }

    /** One clickable row per clip: name selects it, the × removes it. */
    private UIElement audioRow(CompDocument comp, dev.bbsae.comp.CompAudio clip)
    {
        UIElement row = new UIElement();

        row.w(1F).h(CompUi.control());

        UICompButton name = new UICompButton(IKey.constant((clip.muted ? "（静音）" : "") + clip.name), (button) ->
        {
            /* Through the canonical selector: it clears the layer selection so Delete cannot
             * remove a layer that is still highlighted on the timeline. */
            this.selectAudioClip(clip);
        });

        name.relative(row).x(0).y(0).w(1F, -24).h(CompUi.control());
        name.tooltip(IKey.constant(clip.path + " · 入点 " + String.format(java.util.Locale.ROOT, "%.2fs", clip.start)));

        UICompIconButton remove = CompUi.iconButton(Icons.X, "移除这条音频", () ->
        {
            comp.audio.remove(clip);

            if (this.selectedAudio == clip)
            {
                this.selectedAudio = null;
            }

            this.refreshInspector();
            this.save();
        });

        remove.relative(row).x(1F, -22).y(0).w(20).h(CompUi.control());
        row.add(name, remove);

        return row;
    }

    /**
     * Runs the in-game browser for audio files, copies the pick into {@code assets/audio} and adds
     * a clip at the playhead.
     */
    private void importAudioFile()
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        NativeFileDialog.pickFile(
            "导入音频",
            new String[] {"wav", "ogg"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.addAudioClip(file);
                }
            },
            this::openAudioBrowser
        );
    }

    /** Fallback path: the in-game browser, used when the OS dialog cannot be shown. */
    private void openAudioBrowser()
    {
        if (this.importBrowser != null)
        {
            this.closeImportBrowser();
        }

        this.importBrowser = new UIImportBrowser(
            "导入音频",
            new String[] {"wav", "ogg"},
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.addAudioClip(file);
                }

                this.closeImportBrowser();
            },
            this::closeImportBrowser
        );

        this.importBrowser.relative(this).x(0).y(0).w(1F).h(1F);
        this.add(this.importBrowser);
        this.resize();
    }

    /**
     * Adds a sound from the library (a sound event id or a {@code bbs:}/{@code mc:} ogg link) to
     * the composition's audio track.
     *
     * <p>The composition stores project-relative files, so the sound is materialised into
     * {@code assets/audio} first. That keeps preview, export and the standalone WAV on one path:
     * they all read the same file.</p>
     */
    private void importSoundId(String id)
    {
        if (this.project == null || this.doc == null || id == null || id.isBlank())
        {
            return;
        }

        try
        {
            java.io.File file = SoundImport.materialize(id);

            this.addAudioClip(file);
            this.setStatus("已导入音效：" + id + " → 音频轨道（入点 " + String.format(java.util.Locale.ROOT, "%.2fs", this.time) + "）");
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not import sound {}", id, e);
            this.notifyError("音效导入失败：" + id + " · " + e.getMessage());
        }
    }

    private void addAudioClip(java.io.File file)
    {
        try
        {
            java.nio.file.Path folder = CompStore.assetRoot(this.project.id).resolve("audio");

            java.nio.file.Files.createDirectories(folder);

            String name = file.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
            java.nio.file.Path target = folder.resolve(name);

            if (!target.toAbsolutePath().equals(file.toPath().toAbsolutePath()))
            {
                java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            dev.bbsae.comp.CompAudio clip = new dev.bbsae.comp.CompAudio(
                "audio_" + (this.doc.audio.size() + 1),
                name,
                "audio/" + name
            );

            clip.start = this.time;
            clip.name = name.substring(0, name.lastIndexOf('.') > 0 ? name.lastIndexOf('.') : name.length());

            /* Store the real length now: the timeline draws it and the mixer plays it, so a clip
             * without an explicit duration must not be a guess in two different places. */
            float length = dev.bbsae.client.export.CompAudioMixer.probeDuration(target);

            if (length > 0F)
            {
                clip.duration = length;
            }

            this.doc.audio.add(clip);
            this.selectAudioClip(clip);
            dev.bbsae.client.audio.CompAudioPlayer.invalidate();
            this.refreshInspector();
            this.save();
            this.setStatus("已导入音频：" + name + "（入点 " + String.format(java.util.Locale.ROOT, "%.2fs", clip.start) + "）");
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.error("Could not import audio {}", file, e);
            this.notifyError("音频导入失败：" + e.getMessage());
        }
    }

    /** Queues an audio-only render of the current composition and shows the render view. */
    private void exportAudioOnly()
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        if (this.doc.audio == null || this.doc.audio.isEmpty())
        {
            this.notifyError("这条合成还没有音频：先在“合成”页导入音频，再导出");

            return;
        }

        String presetId = "audio_wav";
        boolean found = false;

        for (CompOutputPreset preset : this.project.outputPresets)
        {
            if (preset != null && "audio".equals(preset.kind) && preset.id != null && preset.id.startsWith("audio_wav"))
            {
                presetId = preset.id;
                found = true;
                break;
            }
        }

        if (!found)
        {
            for (CompOutputPreset preset : this.project.outputPresets)
            {
                if (preset != null && "audio".equals(preset.kind))
                {
                    presetId = preset.id;
                    break;
                }
            }
        }

        /* A project created before audio modules existed has video presets only. Add the WAV module
         * on demand, otherwise the job would fall back to the first video preset. */
        if (this.project.getPreset(presetId) == null)
        {
            CompOutputPreset wav = CompOutputPreset.audioWav();

            this.project.outputPresets.add(wav);
            presetId = wav.id;
        }

        CompRenderJob job = new CompRenderJob(
            "job_" + (this.project.renderQueue.size() + 1),
            this.project.id,
            this.doc.id,
            presetId
        );

        job.outputName = this.doc.id + "_audio";
        job.rangeStart = 0F;
        job.rangeEnd = this.doc.duration;
        this.project.renderQueue.add(job);
        this.selectedJob = job;
        this.refreshQueue();
        this.save();
        this.toggleRenderView();

        if (CompExportManager.start(this.project, job))
        {
            this.setStatus("正在导出音频：" + job.outputName);
        }
        else
        {
            this.notifyError("音频导出启动失败（渲染队列忙？）");
        }
    }

    private UISection layerSection()
    {
        UISection section = new UISection(IKey.constant("图层"));

        section.remember(this.sectionFolds, "layer", true);

        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            section.fields.add(UI.label(IKey.constant("请在时间轴中选择一条轨道"), 16));
            return section;
        }

        CompLayer l = layer;

        section.fields.add(UI.label(IKey.constant(l.type + " · " + l.id), 16));
        section.fields.add(UI.label(IKey.constant("变换、不透明度、来源与绑定都在本页；效果在「效果」页。"), 14));

        UITextbox name = new UITextbox(64, (text) ->
        {
            /* A rename that never called save() was not undoable and, with auto-save off, never
             * reached the project file: the row looked edited until the panel was reopened. */
            l.name = text;
            this.save();
        });

        name.setText(l.name);
        section.fields.add(this.fieldRow("名称", name));

        UIToggle visible = new UIToggle(IKey.constant("可见"), l.visible, (toggle) ->
        {
            this.beginAction("图层可见");
            l.visible = toggle.getValue();
            this.save();
        });
        UIToggle locked = new UIToggle(IKey.constant("锁定"), l.locked, (toggle) ->
        {
            this.beginAction("图层锁定");
            l.locked = toggle.getValue();
            this.save();
        });
        UIToggle solo = new UIToggle(IKey.constant("独显"), l.solo, (toggle) ->
        {
            this.beginAction("图层独显");
            l.solo = toggle.getValue();
            this.save();
        });
        UIToggle guide = new UIToggle(IKey.constant("参考层"), l.guide, (toggle) ->
        {
            this.beginAction("参考层");
            l.guide = toggle.getValue();
            this.save();
        });
        section.fields.add(UI.row(visible, locked));
        section.fields.add(UI.row(solo, guide));

        /* In/out and the quick trim buttons are the 片段 section above; keeping a second copy here
         * made the layer page twice as tall with two controls editing the same value. */
        section.fields.add(this.numberRow("源时间偏移", l.startTime, -36000F, 36000F, false, (value) -> l.startTime = value.floatValue()));
        section.fields.add(this.numberRow("时间缩放", l.timeScale, -10F, 10F, false, (value) -> l.timeScale = value.floatValue()));

        UIToggle freeze = new UIToggle(IKey.constant("冻结帧"), l.freeze, (toggle) ->
        {
            this.beginAction("冻结帧");
            l.freeze = toggle.getValue();
            this.refreshInspector();
            this.save();
        });
        section.fields.add(freeze);

        if (l.freeze)
        {
            section.fields.add(this.numberRow("冻结时间（秒）", l.freezeTime, 0F, 36000F, false, (value) ->
            {
                l.freezeTime = Math.max(0F, value.floatValue());
                this.commitLayerTiming();
            }));
        }

        UIToggle timeRemap = new UIToggle(IKey.constant("时间重映射"), l.timeRemap, (toggle) ->
        {
            this.beginAction("时间重映射");
            l.timeRemap = toggle.getValue();
            this.refreshInspector();
            this.save();
        });
        section.fields.add(timeRemap);

        if (l.timeRemap)
        {
            section.fields.add(this.animatedNumberRow(
                "源时间（秒）", l, "timeRemap",
                l.sourceTimeAt(this.time, this.getFrame()),
                (value) -> l.timeRemap = true,
                -36000F, 36000F
            ));
        }

        CompLayer parentLayer = this.doc == null || l.parentId == null || l.parentId.isEmpty() ? null : this.doc.getLayer(l.parentId);
        UIButton parent = new UICompButton(IKey.constant(parentLayer == null ? "无（根）" : this.layerLabel(parentLayer) + "  ·  " + parentLayer.id), (button) -> this.chooseParent(l));
        parent.tooltip(IKey.constant("选择一个空对象或其它图层作为父级；子级会继承父级的移动、缩放、旋转"));
        section.fields.add(this.fieldRow("父级", parent));

        /* Mask / Exclude, the way Alight Motion presents it: pick the shape layer, press one button,
         * and the layer underneath is clipped by it (intersection) or cut out of it (difference).
         * The manual "track matte" rows below stay for advanced cases. */
        section.fields.add(UI.label(IKey.constant("蒙版 / 排除：选中一个形状层，作用到它下面那一层"), 14));
        section.fields.add(UI.row(
            this.smallButton("用作蒙版（交集）", () -> this.applyMatteToBelow(l, false)),
            this.smallButton("用作排除（差集）", () -> this.applyMatteToBelow(l, true))
        ));

        if (l.trackMatteEnabled && !l.trackMatteId.isEmpty())
        {
            CompLayer source = this.doc == null ? null : this.doc.getLayer(l.trackMatteId);

            section.fields.add(UI.label(IKey.constant("当前：" + (source == null ? "（缺失）" : this.layerLabel(source))
                + " · " + matteName(l.trackMatteMode)), 13));
            section.fields.add(this.smallButton("清除蒙版", () ->
            {
                this.beginAction("清除蒙版");
                l.trackMatteEnabled = false;
                l.trackMatteId = "";
                this.refreshInspector();
                this.save();
                this.setStatus("已清除蒙版");
            }));
        }

        CompLayer matteLayer = this.doc == null || l.trackMatteId == null || l.trackMatteId.isEmpty() ? null : this.doc.getLayer(l.trackMatteId);
        UIButton matte = new UICompButton(IKey.constant(matteLayer == null ? "无" : this.layerLabel(matteLayer) + "  ·  " + matteLayer.id), (button) -> this.chooseMatte(l));
        matte.tooltip(IKey.constant("选择作为遮罩的图层；开启下面的“使用遮罩”后生效"));
        section.fields.add(this.fieldRow("轨道遮罩", matte));

        UIToggle matteEnabled = new UIToggle(IKey.constant("使用遮罩"), l.trackMatteEnabled, (toggle) ->
        {
            this.beginAction("使用遮罩");
            l.trackMatteEnabled = toggle.getValue();
            this.save();
        });
        UIButton matteMode = new UICompButton(IKey.constant(matteName(l.trackMatteMode)), (button) ->
        {
            this.beginAction("切换遮罩模式");
            l.trackMatteMode = (l.trackMatteMode + 1) % 4;
            button.label = IKey.constant(matteName(l.trackMatteMode));
            this.save();
        });
        section.fields.add(UI.row(matteEnabled, matteMode));

        section.fields.add(this.textRow("表达式：位置 X", l.expressionX, (text) -> l.expressionX = text));
        section.fields.add(this.textRow("表达式：位置 Y", l.expressionY, (text) -> l.expressionY = text));
        section.fields.add(this.textRow("表达式：旋转", l.expressionRotation, (text) -> l.expressionRotation = text));
        section.fields.add(this.textRow("表达式：不透明度", l.expressionOpacity, (text) -> l.expressionOpacity = text));

        /* Point tracker: follows a feature across the film; the result either bakes into this
         * layer's position or feeds expressions like tracker.<id>.x. */
        if (this.doc != null)
        {
            section.fields.add(UI.label(IKey.constant("跟踪器"), 14));

            for (CompTracker tracker : this.doc.trackers)
            {
                boolean trackerRunning = dev.bbsae.client.render.TrackerEngine.isRunning()
                    && dev.bbsae.client.render.TrackerEngine.current() == tracker;

                section.fields.add(UI.label(IKey.constant(tracker.name + " · " + trackerStateLabel(tracker)
                    + " · " + tracker.samples.size() + " 帧"
                    + (tracker.message == null || tracker.message.isEmpty() ? "" : " · " + tracker.message)), 13));
                section.fields.add(this.numberRow("锚点 X", (double) tracker.x, 0D, (double) Math.max(1, this.doc.width), false,
                    (value) -> tracker.x = value.floatValue()));
                section.fields.add(this.numberRow("锚点 Y", (double) tracker.y, 0D, (double) Math.max(1, this.doc.height), false,
                    (value) -> tracker.y = value.floatValue()));
                section.fields.add(this.numberRow("模板半径（像素）", (double) tracker.pattern, 4D, 64D, true,
                    (value) -> tracker.pattern = value.intValue()));
                section.fields.add(this.numberRow("搜索半径（像素）", (double) tracker.search, 4D, 128D, true,
                    (value) -> tracker.search = value.intValue()));
                section.fields.add(this.numberRow("置信度阈值", (double) tracker.confidence, 0D, 1D, false,
                    (value) -> tracker.confidence = value.floatValue()));
                section.fields.add(UI.row(
                    this.smallButton(trackerRunning ? "停止" : "开始分析", () -> this.toggleTracker(tracker)),
                    this.smallButton("应用到位置", () -> this.bakeTrackerToLayer(tracker))
                ));
                section.fields.add(UI.row(
                    this.smallButton("清除结果", () ->
                    {
                        tracker.clearSamples();
                        this.refreshInspector();
                        this.save();
                    }),
                    this.smallButton("删除跟踪器", () ->
                    {
                        if (dev.bbsae.client.render.TrackerEngine.current() == tracker)
                        {
                            dev.bbsae.client.render.TrackerEngine.cancel();
                        }

                        this.beginAction("删除跟踪器");
                        this.doc.removeTracker(tracker.id);
                        this.refreshInspector();
                        this.save();
                    })
                ));
            }

            section.fields.add(this.smallButton("＋ 新建跟踪器", () -> this.createTracker()));
        }

        if ("image".equals(l.type) || "footage".equals(l.type))
        {
            section.fields.add(this.textRow("序列".equals(l.type) ? "图片序列 (%04d)" : "图片路径", l.imagePath, (text) -> l.imagePath = text));
            section.fields.add(this.smallButton("导入图片…", () -> this.importImage(l)));
            section.fields.add(this.numberRow("图片适配（0 包含 1 覆盖 2 拉伸）", l.imageFit, 0, 2, true, (value) -> l.imageFit = value.intValue()));

            UICompButton textureFilter = new UICompButton(IKey.constant(this.layerFilterLabel(l)), (button) ->
            {
                int mode = TextureFilter.layerMode(l);

                mode = mode <= TextureFilter.SOURCE_INHERIT ? TextureFilter.AUTO
                    : mode == TextureFilter.AUTO ? TextureFilter.LINEAR
                    : mode == TextureFilter.LINEAR ? TextureFilter.NEAREST
                    : TextureFilter.SOURCE_INHERIT;

                l.textureFilter = mode;
                button.label = IKey.constant(this.layerFilterLabel(l));
                this.setStatus("本层纹理过滤：" + this.layerFilterLabel(l));
                this.save();
            });
            textureFilter.tooltip(IKey.constant("该图片/序列层的采样过滤；「跟随工程」使用设置里的自动检测。自动会按素材分辨率和显示尺寸开启或关闭线性过滤"));
            section.fields.add(textureFilter);
        }
        if ("bbs".equals(l.type))
        {
            section.fields.add(UI.label(IKey.constant("显示 BBS 影片编辑器当前的预览帧；可作为底层参考或与合成混合。"), 14));
            section.fields.add(this.numberRow("适配（0 包含 1 覆盖 2 拉伸）", l.imageFit, 0, 2, true, (value) -> l.imageFit = value.intValue()));
        }
        if (l.isEntityLayer())
        {
            this.appendEntityControls(section, l);
        }
        if ("precomp".equals(l.type))
        {
            java.util.List<String> compLabels = new java.util.ArrayList<>();
            java.util.Map<String, String> compIds = new java.util.LinkedHashMap<>();
            String currentLabel = "（未选择）";

            for (CompDocument candidate : this.project.comps)
            {
                /* A comp cannot contain itself; the renderer's cycle guard would only show
                 * "循环预合成" after the user had already picked it. */
                if (candidate == this.doc)
                {
                    continue;
                }

                String label = candidate.name + "  ·  " + candidate.id + "  (" + candidate.width + "×" + candidate.height + ")";

                compLabels.add(label);
                compIds.put(label, candidate.id);

                if (candidate.id.equals(l.precompId))
                {
                    currentLabel = label;
                }
            }

            UIChoiceButton<String> precompChoice = new UIChoiceButton<>(compLabels, (value) -> Icons.STRUCTURE, IKey::constant);

            precompChoice.setValue(currentLabel);
            precompChoice.callback((value) ->
            {
                l.precompId = compIds.getOrDefault(value, "");
                this.refreshInspector();
                this.save();
            });
            section.fields.add(this.fieldRow("预合成目标", precompChoice));
            section.fields.add(this.smallButton("＋ 新建预合成（当前尺寸）", () ->
            {
                int index = this.project.comps.size() + 1;
                CompDocument sub = this.project.addComp("precomp_" + index, this.doc.width, this.doc.height, this.doc.fps, this.doc.duration);

                sub.name = "预合成 " + index;
                sub.duration = this.doc.duration;
                sub.workAreaOut = this.doc.duration;
                l.precompId = sub.id;
                this.refreshInspector();
                this.save();
                this.setStatus("已创建预合成：" + sub.name);
            }));
            section.fields.add(this.smallButton("进入预合成", () -> this.enterPrecomp(l)));
            section.fields.add(this.numberRow("预合成时间偏移", l.precompTimeOffset, -36000F, 36000F, false, (value) -> l.precompTimeOffset = value.floatValue()));
            section.fields.add(this.numberRow("预合成时间缩放", l.precompTimeScale, -10F, 10F, false, (value) -> l.precompTimeScale = value.floatValue()));
        }

        return section;
    }

    /** Matte picker: a list of layer ids instead of asking the user to type an id by hand. */
    public void chooseMatte(CompLayer layer)
    {
        if (this.doc == null || layer == null)
        {
            return;
        }

        java.util.List<String> values = new java.util.ArrayList<>();
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();

        values.add("无");
        ids.put("无", "");

        for (CompLayer candidate : this.doc.layers)
        {
            if (candidate == layer)
            {
                continue;
            }

            /* Cameras, nulls and adjustment layers have no pixels: using one as a matte multiplied
             * the target's alpha by zero and made the layer disappear with no message. */
            if ("camera".equals(candidate.type) || "null".equals(candidate.type)
                || "adjustment".equals(candidate.type))
            {
                continue;
            }

            String label = this.layerLabel(candidate) + "  ·  " + candidate.id;

            values.add(label);
            ids.put(label, candidate.id);
        }

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("选择轨道遮罩"), (value) ->
        {
            if (value == null)
            {
                return;
            }

            layer.trackMatteId = ids.getOrDefault(value, "");
            layer.trackMatteEnabled = !layer.trackMatteId.isEmpty();
            this.refreshInspector();
            this.save();
        });

        panel.addValues(values);
        UIOverlay.addOverlay(this.getContext(), panel, 320, 340);
    }

    public void chooseParent(CompLayer layer)
    {
        if (this.doc == null || layer == null)
        {
            return;
        }

        java.util.List<String> values = new java.util.ArrayList<>();
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();

        values.add("无（根）");
        ids.put("无（根）", "");

        for (CompLayer candidate : this.doc.layers)
        {
            if (candidate == layer || "camera".equals(candidate.type) || this.isDescendant(candidate, layer))
            {
                continue;
            }

            String label = this.layerLabel(candidate) + "  ·  " + candidate.id + "  [" + candidate.type + "]";

            values.add(label);
            ids.put(label, candidate.id);
        }

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("选择父级"), (value) ->
        {
            if (value == null)
            {
                return;
            }

            /* Parenting is a change of basis, not an assignment of a field: re-express the child's
             * transform so it stays exactly where it is (see CompParenting). Assigning parentId
             * directly is what made bound layers jump away from each other. */
            String parentId = ids.getOrDefault(value, "");

            if (!dev.bbsae.comp.CompParenting.bind(this.doc, layer, parentId, this.getFrame()))
            {
                this.setStatus("无法绑定：目标是不是自身或子级");
                return;
            }

            this.refreshInspector();
            this.refreshSheets();
            this.save();
            this.setStatus(parentId.isEmpty() ? "已解除父级（位置保持不变）" : "已绑定父级，位置保持不变");
        });

        panel.addValues(values);
        UIOverlay.addOverlay(this.getContext(), panel, 300, 320);
    }

    private boolean isDescendant(CompLayer candidate, CompLayer ancestor)
    {
        java.util.Set<String> seen = new java.util.HashSet<>();
        CompLayer current = candidate;

        while (current != null && current.parentId != null && !current.parentId.isEmpty() && seen.add(current.id))
        {
            if (current.parentId.equals(ancestor.id))
            {
                return true;
            }

            current = this.doc.getLayer(current.parentId);
        }

        return false;
    }

    private UISection bindingSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("绑定 / 跟踪"));

        if (layer == null)
        {
            return section;
        }

        CompBinding binding = layer.binding;

        UIToggle enabled = new UIToggle(IKey.constant("启用"), binding.enabled, (toggle) ->
        {
            this.beginAction("启用绑定");
            binding.enabled = toggle.getValue();
            this.save();
        });
        UIButton type = new UICompButton(IKey.constant(binding.type), (button) ->
        {
            this.beginAction("切换绑定类型");
            binding.type = switch (binding.type)
            {
                case CompBinding.CAMERA -> CompBinding.REPLAY;
                case CompBinding.REPLAY -> CompBinding.LAYER;
                case CompBinding.LAYER -> CompBinding.SCREEN;
                default -> CompBinding.CAMERA;
            };
            button.label = IKey.constant(binding.type);
            this.save();
        });
        section.fields.add(UI.row(enabled, type));

        UITextbox target = new UITextbox(64, (text) ->
        {
            binding.target = text;
            this.save();
        });

        target.setText(binding.target);
        section.fields.add(this.fieldRow("回放 / 图层目标", target));
        section.fields.add(this.numberRow("偏移 X", binding.offsetX, -10000F, 10000F, false, (value) -> binding.offsetX = value.floatValue()));
        section.fields.add(this.numberRow("偏移 Y", binding.offsetY, -10000F, 10000F, false, (value) -> binding.offsetY = value.floatValue()));
        section.fields.add(this.numberRow("世界 Y 偏移", binding.worldOffsetY, -16F, 16F, false, (value) -> binding.worldOffsetY = value.floatValue()));
        section.fields.add(this.numberRow("平滑", binding.smoothing, 0F, 0.99F, false, (value) -> binding.smoothing = value.floatValue()));

        return section;
    }

    private UISection textSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("文字"));
        UITextbox text = new UITextbox(2048, (value) ->
        {
            layer.text = value;
            this.save();
        });

        text.setText(layer.text);
        section.fields.add(text);

        /* Text content effects: these change the string (timecode, counter, scramble, typewriter)
         * before layout, so they work with the existing per-character animators instead of
         * duplicating them. */
        String[] textModeNames = {"静态文字", "时间码", "计数器"};
        UICompButton textMode = new UICompButton(IKey.constant(textModeNames[Math.max(0, Math.min(2, layer.textMode == null ? 0 : layer.textMode))]), (button) ->
        {
            this.beginAction("切换文字模式");
            int mode = layer.textMode == null ? 0 : layer.textMode;
            mode = (mode + 1) % textModeNames.length;
            layer.textMode = mode;
            button.label = IKey.constant(textModeNames[mode]);
            this.refreshInspector();
            this.save();
        });
        section.fields.add(this.fieldRow("文字模式", textMode));

        float progress = layer.textProgress == null ? 1F : layer.textProgress;

        section.fields.add(this.animatedNumberRow("打字进度", layer, "text/progress", progress,
            (value) -> layer.textProgress = value.floatValue(), 0F, 1F));
        section.fields.add(this.numberRow("随机字符比例", (double) (layer.textRandom == null ? 0F : layer.textRandom), 0D, 1D, false,
            (value) -> layer.textRandom = value.floatValue()));
        section.fields.add(this.numberRow("随机种子", (double) (layer.textRandomSeed == null ? 0 : layer.textRandomSeed), 0D, 9999D, true,
            (value) -> layer.textRandomSeed = value.intValue()));
        section.fields.add(this.numberRow("计数器偏移", (double) (layer.textCounterOffset == null ? 0F : layer.textCounterOffset), -100000D, 100000D, false,
            (value) -> layer.textCounterOffset = value.floatValue()));
        section.fields.add(this.numberRow("计数器倍率", (double) (layer.textCounterScale == null ? 1F : layer.textCounterScale), -1000D, 1000D, false,
            (value) -> layer.textCounterScale = value.floatValue()));

        java.util.List<String> fontLabels = new java.util.ArrayList<>();
        java.util.Map<String, String> fontFiles = new java.util.LinkedHashMap<>();

        fontLabels.add("默认（BBS）");
        fontFiles.put("默认（BBS）", "");

        /* Merge the provider's link list with a direct scan of the fonts folder. The provider is
         * the authority for loading, but it can lag a file that was just copied in (and a stale
         * list made a successful import look like it did nothing). The folder scan is what makes
         * the newly imported font appear in the dropdown immediately. */
        java.util.TreeSet<String> fontNames = new java.util.TreeSet<>();

        for (mchorse.bbs_mod.resources.Link link : mchorse.bbs_mod.fonts.FontManager.getFontLinks())
        {
            /* Keep the path *under* fonts/, not just the basename: a font in fonts/sub/Name.ttf
             * is loaded as "fonts/sub/Name.ttf", and storing only "Name.ttf" used to look up a
             * different (or missing) root file. Root fonts are unchanged. */
            String path = link.path;
            int slash = path.indexOf('/');

            fontNames.add(slash >= 0 ? path.substring(slash + 1) : path);
        }

        java.io.File fontFolder = mchorse.bbs_mod.fonts.FontManager.getFolder();

        if (fontFolder != null && fontFolder.isDirectory())
        {
            java.io.File[] files = fontFolder.listFiles();

            if (files != null)
            {
                for (java.io.File fontFile : files)
                {
                    String name = fontFile.getName().toLowerCase(java.util.Locale.ROOT);

                    if (fontFile.isFile() && (name.endsWith(".ttf") || name.endsWith(".otf")))
                    {
                        fontNames.add(fontFile.getName());
                    }
                }
            }
        }

        for (String name : fontNames)
        {
            fontLabels.add(name);
            fontFiles.put(name, name);
        }

        String currentFont = layer.fontName == null || layer.fontName.isBlank() ? "默认（BBS）" : layer.fontName;
        String currentLabel = "默认（BBS）";

        /* Match case-insensitively: a project written on Windows can carry "Timesbd.TTF" while the
         * folder lists "timesbd.ttf", and an exact match would show 默认 while the text actually
         * uses the custom font. */
        for (String label : fontLabels)
        {
            if (label.equalsIgnoreCase(currentFont))
            {
                currentLabel = label;

                break;
            }
        }

        UIChoiceButton<String> fontChoice = new UIChoiceButton<>(fontLabels, (value) -> Icons.FONT, IKey::constant);

        fontChoice.setValue(currentLabel);
        fontChoice.callback((value) ->
        {
            layer.fontName = fontFiles.getOrDefault(value, "");
            this.save();
        });
        section.fields.add(this.fieldRow("字体", fontChoice));

        /* A font is a partial map: say so instead of letting the user wonder why half the line is
         * missing (or why the whole line renders in the default font). */
        if (!layer.fontName.isBlank() && layer.text != null && !layer.text.isBlank())
        {
            java.io.File fontFile = dev.bbsae.client.render.FontCoverage.resolve(layer.fontName);

            if (fontFile == null)
            {
                section.fields.add(UI.label(IKey.constant("找不到字体文件 " + layer.fontName + "：已回退默认字体。用下面的“打开 BBS 字体文件夹”确认文件还在。"), 13));
            }
            else
            {
                java.util.List<Integer> missing = dev.bbsae.client.render.FontCoverage.missingGlyphs(fontFile, layer.text);

                if (!missing.isEmpty())
                {
                    StringBuilder sample = new StringBuilder();

                    for (int i = 0; i < missing.size() && i < 8; i++)
                    {
                        sample.appendCodePoint(missing.get(i));
                    }

                    section.fields.add(UI.label(IKey.constant("该字体缺少 " + missing.size() + " 个字符（" + sample
                        + (missing.size() > 8 ? "…" : "") + "），这些字符已自动使用默认字体。要全部用该字体，请选择包含这些字符的字体文件。"), 13));
                }
            }
        }
        section.fields.add(UI.row(
            this.smallButton("导入字体（ttf / otf）…", () -> this.importFont(layer)),
            this.smallButton("系统字体…", () -> this.openSystemFontPicker(layer))
        ));
        section.fields.add(UI.row(
            this.smallButton("打开 BBS 字体文件夹…", () ->
            {
                java.io.File folder = mchorse.bbs_mod.fonts.FontManager.getFolder();

                if (folder == null || !OsOpen.openFolder(folder.toPath()))
                {
                    this.setStatus("无法打开 BBS 字体文件夹");
                }
            }),
            this.smallButton("刷新字体列表", () -> {
                SystemFonts.refresh();
                this.refreshInspector();
                this.setStatus("字体列表已刷新");
            })
        ));

        section.fields.add(this.animatedNumberRow("字号", layer, "text/fontSize", layer.fontSizeAt(this.getFrame()), (value) -> layer.fontSize = (float) Math.max(1D, value), 1F, 2000F));
        section.fields.add(this.animatedNumberRow("字距", layer, "text/tracking", layer.trackingAt(this.getFrame()), (value) -> layer.tracking = value.floatValue(), -200F, 500F));
        section.fields.add(this.animatedNumberRow("行高", layer, "text/lineHeight", layer.lineHeightAt(this.getFrame()), (value) -> layer.lineHeight = (float) Math.max(0.1D, value), 0.1F, 5F));
        section.fields.add(this.colorRow("颜色", layer.color, (color) -> layer.color = color));
        section.fields.add(this.colorRow("描边", layer.strokeColor, (color) -> layer.strokeColor = color));
        section.fields.add(this.animatedNumberRow("描边宽度", layer, "text/strokeWidth", layer.textStrokeAt(this.getFrame()), (value) -> layer.strokeWidth = (float) Math.max(0D, value), 0F, 40F));
        section.fields.add(this.numberRow("对齐（0 左 1 中 2 右）", layer.align, 0, 2, true, (value) -> layer.align = value.intValue()));
        section.fields.add(new UIToggle(IKey.constant("自动换行"), layer.wrap, (toggle) ->
        {
            this.beginAction("自动换行");
            layer.wrap = toggle.getValue();
            this.refreshInspector();
            this.save();
        }));
        section.fields.add(this.numberRow("换行宽度", layer.textBoxWidth, 10F, 10000F, false, (value) -> layer.textBoxWidth = value.floatValue()));

        return section;
    }

    private UISection textAnimatorSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("文字动画器"));

        section.fields.add(this.smallButton("添加文字动画器", () ->
        {
            CompTextAnimator animator = new CompTextAnimator();

            animator.id = "animator_" + (layer.textAnimators.size() + 1);
            animator.name = animator.id;
            layer.textAnimators.add(animator);
            this.refreshInspector();
        }));

        for (CompTextAnimator animator : layer.textAnimators)
        {
            section.fields.add(UI.label(IKey.constant(animator.name), 16));
            section.fields.add(new UIToggle(IKey.constant("启用"), animator.enabled, (toggle) ->
            {
                this.beginAction("启用文字动画");
                animator.enabled = toggle.getValue();
                this.save();
            }));
            section.fields.add(this.numberRow("起点", animator.start, 0F, 1F, false, (value) -> animator.start = value.floatValue()));
            section.fields.add(this.numberRow("结束", animator.end, 0F, 1F, false, (value) -> animator.end = value.floatValue()));
            section.fields.add(this.numberRow("偏移", animator.offset, -1F, 1F, false, (value) -> animator.offset = value.floatValue()));
            section.fields.add(new UIToggle(IKey.constant("摆动"), animator.wiggle, (toggle) -> animator.wiggle = toggle.getValue()));
            section.fields.add(this.numberRow("随机度", animator.randomness, 0F, 200F, false, (value) -> animator.randomness = value.floatValue()));
            section.fields.add(this.numberRow("位置 X", animator.positionX, -500F, 500F, false, (value) -> animator.positionX = value.floatValue()));
            section.fields.add(this.numberRow("位置 Y", animator.positionY, -500F, 500F, false, (value) -> animator.positionY = value.floatValue()));
            section.fields.add(this.numberRow("缩放", animator.scale, 0.01F, 8F, false, (value) -> animator.scale = value.floatValue()));
            section.fields.add(this.numberRow("旋转", animator.rotation, -720F, 720F, false, (value) -> animator.rotation = value.floatValue()));
            section.fields.add(this.numberRow("不透明度", animator.opacity, 0F, 1F, false, (value) -> animator.opacity = value.floatValue()));
        }

        return section;
    }

    private UISection shapeSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("形状"));

        /* Create row: absolute-positioned buttons. BBS's row() divides widths equally and ignores
         * w(), which would squeeze these labels into unreadable slivers. */
        UIElement create = new UIElement();

        create.w(1F).h(60);

        UIButton rect = this.smallButton("矩形", () ->
        {
            CompShape shape = CompShape.rect("shape_" + (layer.shapes.size() + 1), 0F, 0F, 240F, 160F, 0xFF55AAFF);

            shape.ensureCorners();
            layer.shapes.add(shape);
            this.refreshInspector();
            this.save();
        });
        UIButton triangle = this.smallButton("三角形", () ->
        {
            CompShape shape = new CompShape("triangle_" + (layer.shapes.size() + 1), CompShape.TRIANGLE);

            shape.width = 240F;
            shape.height = 210F;
            shape.fillColor = 0xFFFFB86B;
            shape.setCorner(0, 12F);
            shape.setCorner(1, 12F);
            shape.setCorner(2, 12F);
            layer.shapes.add(shape);
            this.refreshInspector();
            this.save();
        });
        UIButton star = this.smallButton("星形", () ->
        {
            CompShape shape = new CompShape("star_" + (layer.shapes.size() + 1), CompShape.STAR);

            shape.width = 240F;
            shape.height = 240F;
            shape.sides = 5;
            shape.innerRadius = 0.45F;
            shape.fillColor = 0xFF55D68A;
            layer.shapes.add(shape);
            this.refreshInspector();
            this.save();
        });
        UIButton polygon = this.smallButton("多边形", () ->
        {
            CompShape shape = new CompShape("polygon_" + (layer.shapes.size() + 1), CompShape.POLYGON);

            shape.width = 240F;
            shape.height = 240F;
            shape.sides = 6;
            shape.fillColor = 0xFF55AAFF;
            layer.shapes.add(shape);
            this.refreshInspector();
            this.save();
        });
        UIButton path = this.smallButton("贝塞尔路径", () ->
        {
            CompShape shape = new CompShape("path_" + (layer.shapes.size() + 1), CompShape.PATH);

            shape.fillColor = 0xFF55AAFF;
            shape.add(-120F, 80F);
            shape.add(0F, -100F);
            shape.add(120F, 80F);
            layer.shapes.add(shape);
            this.refreshInspector();
            this.save();
        });

        rect.relative(create).x(0).y(0).w(0.5F, -2).h(18);
        triangle.relative(create).x(0.5F, 2).y(0).w(0.5F, -2).h(18);
        star.relative(create).x(0).y(20).w(0.5F, -2).h(18);
        polygon.relative(create).x(0.5F, 2).y(20).w(0.5F, -2).h(18);
        path.relative(create).x(0).y(40).w(1F).h(18);
        create.add(rect, triangle, star, polygon, path);
        section.fields.add(create);

        for (CompShape shape : layer.shapes)
        {
            section.fields.add(this.shapeTitleRow(layer, shape));
            section.fields.add(this.animatedNumberRow("X", layer, CompLayer.shapePath(shape.id, "x"), animValue(layer, CompLayer.shapePath(shape.id, "x"), shape.x), (value) -> shape.x = value.floatValue(), -5000F, 5000F));
            section.fields.add(this.animatedNumberRow("Y", layer, CompLayer.shapePath(shape.id, "y"), animValue(layer, CompLayer.shapePath(shape.id, "y"), shape.y), (value) -> shape.y = value.floatValue(), -5000F, 5000F));
            section.fields.add(this.animatedNumberRow("宽度", layer, CompLayer.shapePath(shape.id, "width"), animValue(layer, CompLayer.shapePath(shape.id, "width"), shape.width), (value) -> shape.width = value.floatValue(), 1F, 5000F));
            section.fields.add(this.animatedNumberRow("高度", layer, CompLayer.shapePath(shape.id, "height"), animValue(layer, CompLayer.shapePath(shape.id, "height"), shape.height), (value) -> shape.height = value.floatValue(), 1F, 5000F));
            section.fields.add(this.animatedNumberRow("旋转", layer, CompLayer.shapePath(shape.id, "rotation"), animValue(layer, CompLayer.shapePath(shape.id, "rotation"), shape.rotation), (value) -> shape.rotation = value.floatValue(), -720F, 720F));

            if (CompShape.STAR.equals(shape.type))
            {
                section.fields.add(this.numberRow("角数", shape.sides, 3, 64, true, (value) ->
                {
                    shape.sides = value.intValue();
                    shape.ensureCorners();
                    this.refreshInspector();
                    this.save();
                }));
                section.fields.add(this.numberRow("内半径", shape.innerRadius, 0.01F, 1F, false, (value) -> shape.innerRadius = value.floatValue()));
            }
            else if (CompShape.POLYGON.equals(shape.type) || CompShape.TRIANGLE.equals(shape.type))
            {
                /* Sides are what make the three corners of a triangle exist in the first place,
                 * so the polygon keeps them adjustable; a triangle stays three-sided but can
                 * still be re-oriented by its rotation and corner radii. */
                if (CompShape.POLYGON.equals(shape.type))
                {
                    section.fields.add(this.numberRow("边数", shape.sides, 3, 64, true, (value) ->
                    {
                        shape.sides = value.intValue();
                        shape.ensureCorners();
                        this.refreshInspector();
                        this.save();
                    }));
                }
            }

            if (shape.cornerCount() > 0)
            {
                section.fields.add(UI.label(IKey.constant("角半径（像素，可单独动画）"), 16));

                shape.ensureCorners();

                int count = Math.min(12, shape.cornerCount());

                for (int i = 0; i < count; i++)
                {
                    String cornerPath = CompLayer.shapePath(shape.id, "c/" + i);
                    final int index = i;

                    section.fields.add(this.animatedNumberRow(
                        this.cornerLabel(shape, i),
                        layer,
                        cornerPath,
                        animValue(layer, cornerPath, shape.corner(i)),
                        (value) -> shape.setCorner(index, (float) Math.max(0D, value)),
                        0F, 2000F
                    ));
                }

                UIButton style = new UICompButton(IKey.constant(shape.cornerStyle == CompShape.CORNER_BEVEL ? "切角" : "圆角"), (button) ->
                {
                    shape.cornerStyle = shape.cornerStyle == CompShape.CORNER_BEVEL ? CompShape.CORNER_ROUND : CompShape.CORNER_BEVEL;
                    button.label = IKey.constant(shape.cornerStyle == CompShape.CORNER_BEVEL ? "切角" : "圆角");
                    this.save();
                });

                UIElement styleRow = new UIElement();

                styleRow.w(1F).h(20);
                style.relative(styleRow).x(0).y(1).w(0.5F, -2).h(18);

                UIButton allZero = this.smallButton("全部归零", () ->
                {
                    shape.ensureCorners();

                    for (int i = 0; i < shape.corners.size(); i++)
                    {
                        shape.corners.set(i, 0F);
                    }

                    this.refreshInspector();
                    this.save();
                });

                allZero.relative(styleRow).x(0.5F, 2).y(1).w(0.5F, -2).h(18);
                styleRow.add(style, allZero);
                section.fields.add(styleRow);
            }

            section.fields.add(this.colorRow("填充颜色", shape.fillColor, (color) ->
            {
                shape.fillColor = color;
                this.save();
            }));
            section.fields.add(new UIToggle(IKey.constant("填充渐变"), shape.fillGradient, (toggle) ->
            {
                shape.fillGradient = toggle.getValue();
                this.save();
            }));
            section.fields.add(this.colorRow("渐变颜色 2", shape.fillColor2, (color) -> shape.fillColor2 = color));
            section.fields.add(this.animatedNumberRow("渐变角度", layer, CompLayer.shapePath(shape.id, "fillGradientAngle"), animValue(layer, CompLayer.shapePath(shape.id, "fillGradientAngle"), shape.fillGradientAngle), (value) -> shape.fillGradientAngle = value.floatValue(), -720F, 720F));
            section.fields.add(this.colorRow("描边颜色", shape.strokeColor, (color) -> shape.strokeColor = color));
            section.fields.add(this.numberRow("描边宽度", shape.strokeWidth, 0F, 80F, false, (value) -> shape.strokeWidth = value.floatValue()));
            section.fields.add(this.animatedNumberRow("修剪开始", layer, CompLayer.shapePath(shape.id, "trimStart"), animValue(layer, CompLayer.shapePath(shape.id, "trimStart"), shape.trimStart), (value) -> shape.trimStart = value.floatValue(), 0F, 1F));
            section.fields.add(this.animatedNumberRow("修剪结束", layer, CompLayer.shapePath(shape.id, "trimEnd"), animValue(layer, CompLayer.shapePath(shape.id, "trimEnd"), shape.trimEnd), (value) -> shape.trimEnd = value.floatValue(), 0F, 1F));
            section.fields.add(this.animatedNumberRow("修剪偏移", layer, CompLayer.shapePath(shape.id, "trimOffset"), animValue(layer, CompLayer.shapePath(shape.id, "trimOffset"), shape.trimOffset), (value) -> shape.trimOffset = value.floatValue(), -1F, 1F));
            section.fields.add(this.animatedNumberRow("重复", layer, CompLayer.shapePath(shape.id, "repeaterCount"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterCount"), shape.repeaterCount), (value) -> shape.repeaterCount = (int) Math.max(0, Math.round(value)), 0F, 64F));
            section.fields.add(this.animatedNumberRow("重复偏移 X", layer, CompLayer.shapePath(shape.id, "repeaterOffsetX"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterOffsetX"), shape.repeaterOffsetX), (value) -> shape.repeaterOffsetX = value.floatValue(), -1000F, 1000F));
            section.fields.add(this.animatedNumberRow("重复偏移 Y", layer, CompLayer.shapePath(shape.id, "repeaterOffsetY"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterOffsetY"), shape.repeaterOffsetY), (value) -> shape.repeaterOffsetY = value.floatValue(), -1000F, 1000F));
            section.fields.add(this.animatedNumberRow("重复缩放", layer, CompLayer.shapePath(shape.id, "repeaterScale"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterScale"), shape.repeaterScale), (value) -> shape.repeaterScale = value.floatValue(), 0.01F, 4F));
            section.fields.add(this.animatedNumberRow("重复旋转", layer, CompLayer.shapePath(shape.id, "repeaterRotation"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterRotation"), shape.repeaterRotation), (value) -> shape.repeaterRotation = value.floatValue(), -720F, 720F));

            if (CompShape.PATH.equals(shape.type))
            {
                for (int i = 0; i < shape.points.size(); i++)
                {
                    CompShape.Point point = shape.points.get(i);
                    int index = i + 1;
                    String base = CompLayer.shapePath(shape.id, "p/" + i + "/");

                    section.fields.add(this.animatedNumberRow("路径" + index + " X", layer, base + "x", animValue(layer, base + "x", point.x), (value) -> point.x = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("路径" + index + " Y", layer, base + "y", animValue(layer, base + "y", point.y), (value) -> point.y = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("路径" + index + " In X", layer, base + "inX", animValue(layer, base + "inX", point.inX), (value) -> point.inX = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("路径" + index + " In Y", layer, base + "inY", animValue(layer, base + "inY", point.inY), (value) -> point.inY = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("路径" + index + " Out X", layer, base + "outX", animValue(layer, base + "outX", point.outX), (value) -> point.outX = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("路径" + index + " Out Y", layer, base + "outY", animValue(layer, base + "outY", point.outY), (value) -> point.outY = value.floatValue(), -5000F, 5000F));
                }

                /* Pen entry points, mirroring the mask section: a shape path can be closed/opened,
                 * keyed as a whole, or handed to the canvas pen tool from here. */
                section.fields.add(UI.row(
                    this.smallButton("用钢笔编辑", () -> this.penTool().editShape(shape)),
                    this.smallButton(shape.closed ? "断开路径" : "闭合路径", () -> this.toggleShapeClosed(shape))
                ));
                section.fields.add(this.smallButton("整条路径打关键帧", () -> this.penTool().keyShape(layer, shape)));

                section.fields.add(UI.row(
                    this.smallButton("添加路径点", () ->
                        this.penTool().appendShapePoint(layer, shape, 0F, 0F)),
                    this.smallButton("删除最后一点", () ->
                        this.penTool().removeShapePoint(layer, shape, shape.points.size() - 1))
                ));
            }
        }

        return section;
    }

    /** Per-shape header: type, boolean operator and delete. */
    private UIElement shapeTitleRow(CompLayer layer, CompShape shape)
    {
        UIElement row = new UIElement();

        row.w(1F).h(CompUi.row());

        UILabel name = new UILabel(IKey.constant(shape.id + " · " + this.shapeTypeLabel(shape.type)));
        UIButton operator = new UICompButton(IKey.constant(this.shapeOperatorLabel(shape.operator)), (button) ->
        {
            shape.operator = (shape.operator + 1) % 4;
            button.label = IKey.constant(this.shapeOperatorLabel(shape.operator));
            this.save();
        });
        UICompIconButton remove = CompUi.iconButton(Icons.CLOSE, "删除这个形状（连同它的角半径与渐变设置）", () ->
        {
            layer.shapes.remove(shape);
            this.refreshInspector();
            this.save();
        });

        int titleHeight = CompUi.row();

        name.color(0xFFB8C4D6, false);
        name.relative(row).x(0).y(1).w(1F, -76).h(titleHeight - 2);
        operator.relative(row).x(1F, -72).y(1).w(48).h(titleHeight - 2);
        operator.tooltip(IKey.constant("布尔运算：相加 / 相减 / 相交 / 排除；点击循环切换"));
        remove.relative(row).x(1F, -CompUi.icon()).y(1).w(CompUi.icon()).h(titleHeight - 2);
        row.add(name, operator, remove);

        return row;
    }

    private String shapeTypeLabel(String type)
    {
        return switch (type)
        {
            case CompShape.ELLIPSE -> "椭圆";
            case CompShape.TRIANGLE -> "三角形";
            case CompShape.POLYGON -> "多边形";
            case CompShape.STAR -> "星形";
            case CompShape.PATH -> "路径";
            default -> "矩形";
        };
    }

    private String shapeOperatorLabel(int operator)
    {
        return switch (operator)
        {
            case CompShape.OP_SUBTRACT -> "相减";
            case CompShape.OP_INTERSECT -> "相交";
            case CompShape.OP_EXCLUDE -> "排除";
            default -> "相加";
        };
    }

    private String cornerLabel(CompShape shape, int index)
    {
        if (CompShape.RECT.equals(shape.type))
        {
            return switch (index)
            {
                case 1 -> "角 · 右上";
                case 2 -> "角 · 右下";
                case 3 -> "角 · 左下";
                default -> "角 · 左上";
            };
        }

        return "角 " + (index + 1);
    }

    private UISection maskSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("蒙版"));

        if (layer == null)
        {
            return section;
        }

        if (layer.threeD)
        {
            /* Masks are baked in layer space since 0.20.4, so they render on the 3D plane. The
             * on-canvas point drag is currently limited to 2D layers; the numeric/curve fields
             * still edit a 3D mask exactly. */
            section.fields.add(UI.label(IKey.constant("3D 图层蒙版在层空间烘焙，会跟随 3D 平面；画布拖点目前用于 2D 图层。"), 13));
        }

        section.fields.add(this.smallButton("添加矩形蒙版", () -> this.addMask(layer, false)));
        section.fields.add(this.smallButton("添加椭圆蒙版", () -> this.addMask(layer, true)));

        for (CompMask mask : layer.masks)
        {
            section.fields.add(UI.label(IKey.constant(mask.name), 16));

            UIToggle enabled = new UIToggle(IKey.constant("启用"), mask.enabled, (toggle) ->
            {
                this.beginAction("启用蒙版");
                mask.enabled = toggle.getValue();
                this.save();
            });
            UIButton remove = new UICompButton(IKey.constant("移除"), (button) ->
            {
                this.beginAction("删除蒙版");
                layer.masks.remove(mask);
                this.refreshInspector();
                this.save();
            });
            section.fields.add(UI.row(enabled, remove));

            /* Pen entry points. They are inspector shortcuts for the same gestures the canvas tool
             * provides, so a path can always be closed/opened and keyed even if the preview is
             * hidden or the mask is too small to click. */
            section.fields.add(UI.row(
                this.smallButton("用钢笔编辑", () -> this.penTool().editMask(mask)),
                this.smallButton(mask.closed ? "断开路径" : "闭合路径", () -> this.toggleMaskClosed(mask))
            ));
            section.fields.add(this.smallButton("整条路径打关键帧", () -> this.penTool().keyMask(layer, mask)));
            section.fields.add(this.animatedNumberRow("羽化", layer, CompLayer.maskPath(mask.id, "feather"), layer.scalarAt(CompLayer.maskPath(mask.id, "feather"), mask.feather, this.getFrame()), (value) -> mask.feather = (float) Math.max(0D, value), 0F, 200F));
            section.fields.add(this.animatedNumberRow("不透明度", layer, CompLayer.maskPath(mask.id, "opacity"), layer.scalarAt(CompLayer.maskPath(mask.id, "opacity"), mask.opacity, this.getFrame()), (value) -> mask.opacity = (float) Math.max(0D, Math.min(1D, value)), 0F, 1F));
            section.fields.add(this.animatedNumberRow("扩展", layer, CompLayer.maskPath(mask.id, "expansion"), layer.scalarAt(CompLayer.maskPath(mask.id, "expansion"), mask.expansion, this.getFrame()), (value) -> mask.expansion = value.floatValue(), -100F, 100F));

            for (int i = 0; i < mask.points.size(); i++)
            {
                CompMask.CompMaskPoint point = mask.points.get(i);
                int index = i + 1;
                String xPath = "mask/" + mask.id + "/p/" + i + "/x";
                String yPath = "mask/" + mask.id + "/p/" + i + "/y";

                section.fields.add(this.animatedNumberRow("点" + index + " X", layer, xPath, layer.scalarAt(xPath, point.x, this.getFrame()), (value) -> point.x = value.floatValue(), -10000F, 10000F));
                section.fields.add(this.animatedNumberRow("点" + index + " Y", layer, yPath, layer.scalarAt(yPath, point.y, this.getFrame()), (value) -> point.y = value.floatValue(), -10000F, 10000F));

                String inXPath = "mask/" + mask.id + "/p/" + i + "/inX";
                String inYPath = "mask/" + mask.id + "/p/" + i + "/inY";
                String outXPath = "mask/" + mask.id + "/p/" + i + "/outX";
                String outYPath = "mask/" + mask.id + "/p/" + i + "/outY";

                section.fields.add(this.animatedNumberRow("入点" + index + " X", layer, inXPath, layer.scalarAt(inXPath, point.inX, this.getFrame()), (value) -> point.inX = value.floatValue(), -5000F, 5000F));
                section.fields.add(this.animatedNumberRow("入点" + index + " Y", layer, inYPath, layer.scalarAt(inYPath, point.inY, this.getFrame()), (value) -> point.inY = value.floatValue(), -5000F, 5000F));
                section.fields.add(this.animatedNumberRow("出点" + index + " X", layer, outXPath, layer.scalarAt(outXPath, point.outX, this.getFrame()), (value) -> point.outX = value.floatValue(), -5000F, 5000F));
                section.fields.add(this.animatedNumberRow("出点" + index + " Y", layer, outYPath, layer.scalarAt(outYPath, point.outY, this.getFrame()), (value) -> point.outY = value.floatValue(), -5000F, 5000F));
            }

            final CompMask modeMask = mask;
            UIButton mode = new UICompButton(IKey.constant(maskModeName(mask.mode)), (button) ->
                this.setMaskMode(modeMask, (modeMask.mode + 1) % 4));

            mode.context((manager) ->
            {
                manager.action(Icons.FADING, IKey.constant("相加"), () -> this.setMaskMode(modeMask, CompMask.MODE_ADD));
                manager.action(Icons.FADING, IKey.constant("相减"), () -> this.setMaskMode(modeMask, CompMask.MODE_SUBTRACT));
                manager.action(Icons.FADING, IKey.constant("相交"), () -> this.setMaskMode(modeMask, CompMask.MODE_INTERSECT));
                manager.action(Icons.FADING, IKey.constant("排除"), () -> this.setMaskMode(modeMask, CompMask.MODE_EXCLUDE));
            });
            section.fields.add(this.fieldRow("模式", mode));
            section.fields.add(new UIToggle(IKey.constant("反转"), mask.inverted, (toggle) ->
            {
                this.beginAction("反转蒙版");
                mask.inverted = toggle.getValue();
                this.save();
            }));
            section.fields.add(UI.row(
                this.smallButton("添加蒙版点", () ->
                    this.penTool().appendMaskPoint(layer, mask, layer.width / 2F, layer.height / 2F)),
                this.smallButton("删除最后一点", () ->
                    this.penTool().removeMaskPoint(layer, mask, mask.points.size() - 1))
            ));
        }

        return section;
    }

    private UISection layerMarkerSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("图层标记"));

        if (layer == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("在播放头添加图层标记", () ->
        {
            this.beginAction("添加图层标记");

            /* "size + 1" reuses an id after a deletion: delete marker 1 of 2, add another and both
             * live markers claim "layer_marker_2", so keying or deleting one edited the other. */
            String id = CompStore.uniqueId("layer_marker", layer.markers, (candidate) -> candidate.id);

            layer.markers.add(new CompMarker(id, "Marker", this.time));
            this.refreshInspector();
            this.save();
        }));

        for (CompMarker marker : layer.markers)
        {
            UITextbox name = new UITextbox(128, (value) ->
            {
                marker.name = value;
                this.save();
            });

            name.setText(marker.name);
            section.fields.add(this.fieldRow("名称", name));
            section.fields.add(this.numberRow("时间", marker.time, 0F, 36000F, false, (value) -> marker.time = value.floatValue()));

            UIButton remove = new UICompButton(IKey.constant("移除"), (button) ->
            {
                this.beginAction("删除图层标记");
                layer.markers.remove(marker);
                this.refreshInspector();
                this.save();
            });

            section.fields.add(UI.row(this.smallButton("跳转", () -> this.setTime(marker.time)), remove));
        }

        return section;
    }

    private UISection animationPresetSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("动画预设"));

        if (layer == null || this.project == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("保存图层动画", () ->
        {
            String base = (layer.name == null || layer.name.isBlank() ? "motion" : layer.name).toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
            String id = CompStore.uniqueId(base, this.project.animationPresets, (preset) -> preset.id);
            CompAnimationPreset preset = CompAnimationPreset.capture(id, base, layer);

            if (preset != null)
            {
                this.project.animationPresets.add(preset);
                this.refreshInspector();
                this.save();
            }
        }));

        for (CompAnimationPreset preset : this.project.animationPresets)
        {
            UIButton apply = new UICompButton(IKey.constant("应用 " + preset.name), (button) ->
            {
                if (preset.applyTo(layer))
                {
                    this.refreshSheets();
                    this.refreshInspector();
                    this.save();
                }
            });

            section.fields.add(UI.row(apply, new UICompButton(IKey.constant("移除"), (button) ->
            {
                this.project.animationPresets.remove(preset);
                this.refreshInspector();
            })));
        }

        return section;
    }

    private UISection markerSection()
    {
        UISection section = new UISection(IKey.constant("合成标记"));

        section.fields.add(this.smallButton("在播放头添加标记", () ->
        {
            this.beginAction("添加合成标记");

            /* Unique id, not "size + 1": deleting a marker and adding another produced two markers
             * with the same id, and the list then highlight/removes the wrong one. */
            String id = CompStore.uniqueId("marker", this.doc.markers, (candidate) -> candidate.id);

            this.doc.markers.add(new CompMarker(id, "Marker", this.time));
            this.refreshInspector();
            this.save();
        }));

        for (CompMarker marker : this.doc.markers)
        {
            UIButton jump = new UICompButton(IKey.constant(CompTime.timecode(marker.time, this.doc.fps) + "  " + marker.name), (button) ->
            {
                this.setFrame(Math.round(marker.time * this.doc.fps));
                this.trackTimeline.revealTime(marker.time);
            });
            UIButton remove = new UICompButton(IKey.constant("删除"), (button) ->
            {
                this.beginAction("删除合成标记");
                this.doc.markers.remove(marker);
                this.refreshInspector();
                this.save();
            });

            UIElement row = new UIElement();

            row.w(1F).h(18);
            jump.relative(row).x(0).y(0).w(1F, -48).h(18);
            remove.relative(row).x(1F, -44).y(0).w(44).h(18);
            row.add(jump, remove);
            section.fields.add(row);
        }

        return section;
    }

    private UISection parameterSection()
    {
        UISection section = new UISection(IKey.constant("主属性"));

        section.fields.add(this.smallButton("添加参数", () ->
        {
            int serial = this.doc.parameters.size() + 1;

            this.doc.parameters.add(new CompParameter("parameter_" + serial, 0F));
            this.refreshInspector();
        }));

        for (CompParameter parameter : this.doc.parameters)
        {
            section.fields.add(this.textRow("名称", parameter.name, (value) ->
            {
                if (value != null && !value.isBlank())
                {
                    parameter.name = value;
                    parameter.label = value;
                }
            }));
            section.fields.add(this.numberRow(parameter.label, parameter.value, parameter.min, parameter.max, false, (value) -> parameter.value = value.floatValue()));
            section.fields.add(this.numberRow("最小", parameter.min, -100000F, 100000F, false, (value) -> parameter.min = value.floatValue()));
            section.fields.add(this.numberRow("最大", parameter.max, -100000F, 100000F, false, (value) -> parameter.max = value.floatValue()));
            section.fields.add(new UICompButton(IKey.constant("移除"), (button) ->
            {
                this.doc.parameters.remove(parameter);
                this.refreshInspector();
            }));
        }

        return section;
    }

    /* Reusable rows (all BBS controls) */

    private UIElement fieldRow(String label, UIElement field)
    {
        return this.fieldRow(label, CompUi.labelWidth(), field);
    }

    private UIElement fieldRow(String label, int labelWidth, UIElement field)
    {
        UIElement row = new UIElement();
        UILabel text = new UILabel(IKey.constant(label));
        int height = CompUi.row();

        row.w(1F).h(height);
        text.relative(row).x(0).y(0).w(labelWidth).h(height);
        field.relative(row).x(labelWidth + 4).y(0).w(1F, -labelWidth - 4).h(height);
        row.add(text, field);

        return row;
    }

    private UIElement numberRow(String label, double value, double min, double max, boolean integer, Consumer<Double> callback)
    {
        /* Every row callback mutates the document one way or another; save() here is what makes
         * the edit undoable (the sampler keys off documentDirty) and durable (debounced write). */
        UITrackpad trackpad = new UITrackpad((next) ->
        {
            callback.accept(next);
            this.save();
        });

        trackpad.limit(min, max, integer);
        trackpad.setValue(value);

        return this.fieldRow(label, trackpad);
    }

    private UIElement animatedNumberRow(String label, CompLayer layer, String path, float value, Consumer<Double> setter, float min, float max)
    {
        boolean transformPath = "x".equals(path) || "y".equals(path) || "rotation".equals(path) || "scaleX".equals(path) || "scaleY".equals(path);

        UITrackpad trackpad = new UITrackpad((next) ->
        {
            setter.accept(next);

            if (!transformPath) this.setScalar(layer, path, next.floatValue());

            /* Direct setters (shape, style and text-metric fields) bypass setScalar — mark the
             * document here so every gesture is undoable. save() is just dirty flags. */
            this.save();
        });

        trackpad.limit(min, max, false);
        trackpad.setValue(value);

        UIIcon key = this.keyButton(layer, path, () ->
        {
            if (transformPath)
            {
                this.addTransformAxisKey(layer, path);
                return;
            }

            if ("opacity".equals(path))
            {
                this.addOpacityKey();
                return;
            }

            this.addScalarKey(layer, path, (float) trackpad.getValue());
        });

        if (transformPath || "opacity".equals(path) || layer.hasScalarChannel(path))
        {
            this.inspectorBindings.add(() -> trackpad.setValue(this.readAnimatedValue(layer, path, value)));
        }

        UILabel labelWidget = new UILabel(IKey.constant(label));

        labelWidget.color(0xFFB8C4D6, false);

        UIElement row = new UIElement();
        int height = CompUi.row();
        int labelWidth = CompUi.labelWidth();
        int keySize = Math.max(12, CompUi.control() - 2);

        row.w(1F).h(height);
        labelWidget.relative(row).x(0).y(1).w(labelWidth).h(height - 2);
        trackpad.relative(row).x(labelWidth + 4).y(1).w(1F, -labelWidth - keySize - 10).h(height - 2);
        key.relative(row).x(1F, -keySize - 2).y(1).w(keySize).h(height - 2);
        row.add(labelWidget, trackpad, key);

        return row;
    }

    private UIElement animatedSliderRow(String label, CompLayer layer, String path, float value, Consumer<Double> setter, float min, float max)
    {
        UISliderTrackpad slider = new UISliderTrackpad((next) ->
        {
            setter.accept(next);
            this.setScalar(layer, path, next.floatValue());
        });

        slider.limit(min, max, false);
        slider.setValue(value);

        UIIcon key = this.keyButton(layer, path, () ->
        {
            this.setScalar(layer, path, (float) slider.getValue());
            this.addScalarKey(layer, path);
        });

        this.inspectorBindings.add(() -> slider.setValue(this.readAnimatedValue(layer, path, value)));

        UILabel labelWidget = new UILabel(IKey.constant(label));

        labelWidget.color(0xFFB8C4D6, false);

        UIElement row = new UIElement();
        int height = CompUi.row();
        int labelWidth = CompUi.labelWidth();
        int keySize = Math.max(12, CompUi.control() - 2);

        row.w(1F).h(height);
        labelWidget.relative(row).x(0).y(1).w(labelWidth).h(height - 2);
        slider.relative(row).x(labelWidth + 4).y(1).w(1F, -labelWidth - keySize - 10).h(height - 2);
        key.relative(row).x(1F, -keySize - 2).y(1).w(keySize).h(height - 2);
        row.add(labelWidget, slider, key);

        return row;
    }

    private UIIcon keyButton(CompLayer layer, String path, Runnable action)
    {
        UIIcon key = new UIIcon(Icons.STOPWATCH, (icon) -> action.run());

        key.wh(16, 16);
        key.tooltip(IKey.constant("在播放头添加关键帧"));
        /* Every animatable path can create its FIRST key: the old gate only enabled the button
         * once a channel already existed, so Z depth, pitch/yaw, Z scale, squash and camera
         * distance/target/FOV could never be keyed from the inspector at all. */
        key.active(true);

        return key;
    }

    private float readAnimatedValue(CompLayer layer, String path, float fallback)
    {
        if ("opacity".equals(path))
        {
            return layer.opacityFrame(this.getFrame());
        }

        if ("timeRemap".equals(path))
        {
            return layer.scalarAt(path, layer.sourceTimeAt(this.time, this.getFrame()), this.getFrame());
        }

        Transform transform;

        switch (path)
        {
            case "x", "y", "rotation", "scaleX", "scaleY":
                transform = layer.transformAt(this.getFrame());

                return switch (path)
                {
                    case "x" -> transform.translate.x;
                    case "y" -> transform.translate.y;
                    case "rotation" -> transform.rotate.z;
                    case "scaleX" -> transform.scale.x;
                    default -> transform.scale.y;
                };
            default:
                return layer.scalarAt(path, fallback, this.getFrame());
        }
    }

    private UIElement animatedNumberRow(String label, CompLayer layer, String path, float value, Consumer<Double> setter)
    {
        return this.animatedNumberRow(label, layer, path, value, setter, -100000F, 100000F);
    }

    private float animValue(CompLayer layer, String path, float fallback)
    {
        return this.readAnimatedValue(layer, path, fallback);
    }

    private UIElement textRow(String label, String value, Consumer<String> callback)
    {
        UITextbox box = new UITextbox(2048, (text) ->
        {
            callback.accept(text);
            this.save();
        });

        box.setText(value);
        return this.fieldRow(label, box);
    }

    private UIElement colorRow(String label, int value, Consumer<Integer> callback)
    {
        UIColor color = new UIColor((next) ->
        {
            /* UIColor's picker applies its initial value while it is being constructed. Building
             * the inspector must never write that value back into the document. */
            if (!this.buildingInspector)
            {
                callback.accept(next);
                this.save();
            }
        }).withAlpha();

        color.setColor(value);
        return this.fieldRow(label, color);
    }

    /**
     * Section action. Flat by default: the accent is reserved for the one primary action of a
     * surface, otherwise forty filled rows read as a wall of colour. Every one carries a hover
     * description (see CompUi.describe).
     */
    private UICompButton smallButton(String label, Runnable callback)
    {
        return CompUi.flat(label, callback).h(CompUi.control());
    }

    private UICompButton smallButton(String label, String tooltip, Runnable callback)
    {
        return CompUi.flat(label, tooltip, callback).h(CompUi.control());
    }

    private UICompButton buttonRow(String label, Runnable callback)
    {
        return CompUi.flat(label, callback).h(CompUi.control());
    }

    /* Keyframes and timeline */

    /**
     * Pen-tool bridge: structural path edits (insert/remove anchor, set-first-vertex) add or
     * re-index animated channels, so the dope sheet / curve sheet list has to be rebuilt.
     * UIPenTool looks this method up from the host (see PEN-TOOL-REPORT §12.5).
     */
    void penRefreshSheets()
    {
        this.refreshSheets();
    }

    private void refreshSheets()
    {
        /* BBS applies a cached KeyframeState by SHEET INDEX, so replaying it onto a different
         * layer/comp would highlight (and let Delete remove) unrelated keys at the same indices.
         * Only carry the selection when the sheet target itself did not change. */
        String identity = (this.doc == null ? "" : this.doc.id) + "|"
            + (this.selectedLayer == null ? "" : this.selectedLayer.id);
        boolean sameTarget = identity.equals(this.lastSheetsIdentity);

        this.lastSheetsIdentity = identity;

        /* The sheet list is rebuilt from scratch here, and BBS stores each sheet's selection on
         * the sheet object — a refresh therefore used to clear the dope-sheet multi-selection.
         * BBS offers cacheState()/applyState() for exactly this; selections are re-applied by
         * sheet id afterwards (channels that disappeared simply drop out). */
        mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeState selection =
            sameTarget && this.keyframeEditor != null ? this.keyframeEditor.view.cacheState() : null;

        this.keyframeEditor.view.removeAllSheets();

        if (this.selectedLayer == null || this.doc == null)
        {
            if (selection != null)
            {
                this.keyframeEditor.view.applyState(selection);
            }

            return;
        }

        CompLayer layer = this.selectedLayer;

        layer.ensureIndependentAxes();

        /* The native drawer gets one float channel per axis, not one shared Transform channel:
         * deleting Position X must not delete Position Y. The curve editor uses the same channels,
         * so the two views can never disagree about which axes are animated. */
        for (int axis : dev.bbsae.comp.CompCurves.axes(layer.threeD))
        {
            String path = dev.bbsae.comp.CompCurves.axisPath(axis);

            this.keyframeEditor.view.addSheet(new UIKeyframeSheet(
                path,
                IKey.constant(dev.bbsae.comp.CompCurves.axisLabel(axis)),
                dev.bbsae.comp.CompCurves.axisColor(axis),
                layer.axisChannel(axis),
                null
            ).seed(() -> layer.scalar(path, 0F)));
        }

        this.keyframeEditor.view.addSheet(new UIKeyframeSheet(
            "opacity",
            IKey.constant("不透明度"),
            Colors.GREEN,
            layer.opacityChannel(),
            null
        ).seed(() -> layer.opacity));

        int color = 0;

        for (String path : layer.animatedPaths())
        {
            if (dev.bbsae.comp.CompCurves.isTransformAxisPath(path))
            {
                continue;
            }

            KeyframeChannel<Float> channel = layer.scalarChannel(path);

            /* Undo / deleted effects can leave empty channel objects behind (removeChannel has no
             * data-layer counterpart yet). They have nothing to edit, so keep them out of the make-
             * key list instead of showing a permanently blank "Effect · param" row. */
            if (channel == null || channel.getKeyframes().isEmpty())
            {
                continue;
            }

            int c = UIKeyframeEditor.COLORS[color % UIKeyframeEditor.COLORS.length];

            color += 1;

            this.keyframeEditor.view.addSheet(new UIKeyframeSheet(
                path,
                IKey.constant(this.sheetTitle(path)),
                c,
                channel,
                null
            ).seed(() -> layer.scalar(path, 0F)));
        }

        this.keyframeEditor.resize();
        this.keyframeEditor.view.resize();

        if (this.resetTimelineView)
        {
            this.resetTimelineView = false;

            /* Reset to the composition's full duration, not to the keyframes' bounding box:
             * the property editor belongs to the same timeline as the track lanes. */
            if (this.keyframeOpen && this.keyframeEditor.area.w > 0)
            {
                this.keyframeEditor.view.getXAxis().view(0D, Math.max(1, this.getDurationTicks()));
                this.keyframeViewNeedsFit = false;
            }
            else
            {
                this.keyframeViewNeedsFit = true;
            }
        }

        if (selection != null)
        {
            this.keyframeEditor.view.applyState(selection);
        }
    }

    private String sheetTitle(String path)
    {
        if ("timeRemap".equals(path)) return "源时间";

        if (path.startsWith("text/"))
        {
            return switch (path.substring(5))
            {
                case "fontSize" -> "Text Size";
                case "tracking" -> "Tracking";
                case "lineHeight" -> "Line Height";
                case "strokeWidth" -> "Text Stroke";
                default -> path.substring(5);
            };
        }

        if (path.startsWith("mask/"))
        {
            String[] parts = path.split("/");
            return parts.length >= 3 ? "Mask " + parts[1] + " · " + parts[parts.length - 1] : path;
        }

        if (path.startsWith("shape/"))
        {
            String[] parts = path.split("/");
            return parts.length >= 3 ? "Shape " + parts[1] + " · " + parts[parts.length - 1] : path;
        }

        if (path.startsWith("fx/"))
        {
            String[] parts = path.split("/", 3);

            if (parts.length == 3)
            {
                CompEffect effect = this.selectedLayer == null ? null : this.selectedLayer.getEffect(parts[1]);
                return (effect == null ? "Effect" : effect.type) + " · " + parts[2];
            }
        }

        return path;
    }

    private void setScalar(CompLayer layer, String path, float value)
    {
        if (dev.bbsae.comp.CompCurves.isTransformAxisPath(path))
        {
            layer.ensureIndependentAxes();
        }

        if (path.startsWith("fx/"))
        {
            String[] parts = path.split("/", 3);

            if (parts.length == 3)
            {
                CompEffect effect = layer.getEffect(parts[1]);

                if (effect != null)
                {
                    effect.put(parts[2], value);
                }
            }
        }
        else
        {
            layer.putScalar(path, value);
        }

        if (layer.hasScalarChannel(path))
        {
            layer.scalarChannel(path).insert(this.getFrame(), value);
        }

        /* The undo sampler and the debounced disk write both key off save(): without this,
         * typing a value left documentDirty false, so Ctrl+Z skipped the edit entirely and
         * (with autosave off) the value never reached the project file. */
        this.save();
    }

    private void addScalarKey(CompLayer layer, String path)
    {
        this.addScalarKey(layer, path, this.scalarValue(layer, path));
    }

    /**
     * Keys an explicit value. The inspector already shows the current animated value; passing it
     * in keeps the undo checkpoint on the pre-action state and makes the inserted key independent
     * of whether {@code CompLayer.scalar()} happens to know the path.
     */
    private void addScalarKey(CompLayer layer, String path, float value)
    {
        this.beginAction("添加关键帧");

        if (dev.bbsae.comp.CompCurves.isTransformAxisPath(path))
        {
            layer.ensureIndependentAxes();
        }

        layer.scalarChannel(path).insert(this.getFrame(), value);

        if (path.startsWith("fx/"))
        {
            this.setScalar(layer, path, value);
        }

        this.refreshSheets();
        this.refreshInspector();
        this.setStatus("已在播放头添加关键帧");
        this.save();

        if (path.startsWith("fx/") && CompPrefs.get().autoEffectCurve)
        {
            this.showCurveFor(path);
        }
    }

    /**
     * Opens the AE graph on one property. Called when an effect key is created, so the artist does
     * not have to hunt for a row that appeared somewhere in the list.
     */
    private void showCurveFor(String path)
    {
        if (this.curveEditor == null || this.selectedLayer == null)
        {
            return;
        }

        this.timelinePage = 1;
        this.applyTimelinePage(true);
        this.curveEditor.refresh();
        this.curveEditor.focusChannel(path);
        this.setStatus("AE 曲线已定位：" + dev.bbsae.comp.CompCurveChannel.displayName(this.selectedLayer, path));
    }

    private float scalarValue(CompLayer layer, String path)
    {
        if ("opacity".equals(path))
        {
            return layer.opacityFrame(this.getFrame());
        }

        if ("timeRemap".equals(path))
        {
            return layer.scalarAt(path, layer.sourceTimeAt(this.time, this.getFrame()), this.getFrame());
        }

        /* The 3D components live in scalar channels but CompLayer.scalar() does not know them
         * (its fallback is the caller's default). Keying Z used to read 0 and insert 0, which
         * teleported a layer that sat at z=500 back onto the comp plane. */
        if ("z".equals(path))
        {
            return layer.scalarAt(path, layer.z, this.getFrame());
        }

        if ("rotationX".equals(path))
        {
            return layer.scalarAt(path, layer.rotationX, this.getFrame());
        }

        if ("rotationY".equals(path))
        {
            return layer.scalarAt(path, layer.rotationY, this.getFrame());
        }

        if ("scaleZ".equals(path))
        {
            return Math.max(0.0001F, layer.scalarAt(path, layer.scaleZ, this.getFrame()));
        }

        if ("cameraTargetX".equals(path))
        {
            return layer.scalarAt(path, layer.cameraTargetX, this.getFrame());
        }

        if ("cameraTargetY".equals(path))
        {
            return layer.scalarAt(path, layer.cameraTargetY, this.getFrame());
        }

        if (path.startsWith("fx/"))
        {
            String[] parts = path.split("/", 3);

            if (parts.length == 3)
            {
                CompEffect effect = layer.getEffect(parts[1]);

                if (effect != null)
                {
                    return layer.scalarAt(path, effect.valueF(parts[2], 0F), this.getFrame());
                }
            }
        }

        return layer.scalarAt(path, layer.scalar(path, 0F), this.getFrame());
    }

    private void addTransformKey(boolean unused)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        this.beginAction("添加变换关键帧");
        this.selectedLayer.ensureIndependentAxes();
        this.selectedLayer.insertTransformKeys(this.getFrame());
        this.refreshSheets();
        this.setStatus("已在播放头添加变换关键帧（各轴独立）");
        this.save();
    }

    private void addTransformAxisKey(CompLayer layer, String path)
    {
        int axis = dev.bbsae.comp.CompCurves.axisForPath(path);

        if (axis < 0)
        {
            return;
        }

        this.beginAction("添加 " + dev.bbsae.comp.CompCurves.axisLabel(axis) + " 关键帧");
        layer.insertAxisKeyAt(axis, this.getFrame());
        this.refreshSheets();
        this.refreshInspector();
        this.setStatus("已在播放头添加 " + dev.bbsae.comp.CompCurves.axisLabel(axis) + " 关键帧");
        this.save();
    }

    private void addOpacityKey()
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        this.beginAction("添加不透明度关键帧");
        this.selectedLayer.opacityChannel().insert(this.getFrame(), this.selectedLayer.opacityFrame(this.getFrame()));
        this.refreshSheets();
        this.setStatus("已在播放头添加不透明度关键帧");
        this.save();
    }

    /* ------------------------------------------------------- quick panel API */

    /** Width of the quick panel; the panel itself calls this while its grip is dragged. */
    public void setQuickPanelWidth(int width)
    {
        this.quickPanelWidth = Math.max(160, Math.min(340, width));
        this.applyLayout();

        if (this.project != null && this.project.settings != null)
        {
            this.project.settings.quickPanelWidth = this.quickPanelWidth;
        }

        /* Drag-release persistence: dock users never hit savePanelLayout(), so this UI state used
         * to be forgotten after closing the panel. */
        this.saveUiState();
    }

    public void quickSetX(double value) { this.setTransformX(value); }
    public void quickSetY(double value) { this.setTransformY(value); }
    public void quickSetRotation(double value) { this.setTransformRotation(value); }

    public void quickSetZ(double value)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        this.selectedLayer.z = (float) value;
        this.setScalar(this.selectedLayer, "z", (float) value);
        this.save();
    }

    public void quickSetScale(double percent)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        float scale = Math.max(0.01F, (float) percent / 100F);

        this.setTransformComponent(3, scale);
        this.setTransformComponent(4, scale);
        this.save();
    }

    public void quickSetScaleX(double percent)
    {
        this.setTransformComponent(3, Math.max(0.01F, (float) percent / 100F));
    }

    public void quickSetScaleY(double percent)
    {
        this.setTransformComponent(4, Math.max(0.01F, (float) percent / 100F));
    }

    public void quickSetScaleZ(double percent)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        float scale = Math.max(0.01F, (float) percent / 100F);

        this.selectedLayer.scaleZ = scale;
        this.setScalar(this.selectedLayer, "scaleZ", scale);
    }

    public void quickSetRotationX(double degrees)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        this.selectedLayer.rotationX = (float) degrees;
        this.setScalar(this.selectedLayer, "rotationX", (float) degrees);
    }

    public void quickSetRotationY(double degrees)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        this.selectedLayer.rotationY = (float) degrees;
        this.setScalar(this.selectedLayer, "rotationY", (float) degrees);
    }

    public void quickSetOpacity(double percent)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        float opacity = Math.max(0F, Math.min(1F, (float) percent / 100F));

        this.selectedLayer.opacity = opacity;

        if (!this.selectedLayer.opacityChannel().getKeyframes().isEmpty())
        {
            this.selectedLayer.opacityChannel().insert(this.getFrame(), opacity);
        }

        this.refreshSheets();
        this.save();
    }

    public void quickKeyTransform() { this.addTransformKey(true); }
    public void quickKeyOpacity() { this.addOpacityKey(); }

    public void quickKeyZ()
    {
        if (this.selectedLayer != null)
        {
            this.addScalarKey(this.selectedLayer, "z");
        }
    }

    /**
     * Position key: x/y live in the transform vector and z in its scalar channel.
     * One discrete command/undo checkpoint, like every other key action.
     */
    public void quickKeyPosition()
    {
        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            return;
        }

        this.beginAction("添加位置关键帧");
        this.insertAxisKeys(layer, layer.threeD
            ? new int[] {dev.bbsae.comp.CompCurves.AXIS_X, dev.bbsae.comp.CompCurves.AXIS_Y, dev.bbsae.comp.CompCurves.AXIS_Z}
            : new int[] {dev.bbsae.comp.CompCurves.AXIS_X, dev.bbsae.comp.CompCurves.AXIS_Y});

        this.afterQuickKey("位置");
    }

    public void quickKeyRotation()
    {
        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            return;
        }

        this.beginAction("添加旋转关键帧");
        this.insertAxisKeys(layer, layer.threeD
            ? new int[] {
                dev.bbsae.comp.CompCurves.AXIS_ROTATION,
                dev.bbsae.comp.CompCurves.AXIS_ROTATION_X,
                dev.bbsae.comp.CompCurves.AXIS_ROTATION_Y}
            : new int[] {dev.bbsae.comp.CompCurves.AXIS_ROTATION});

        this.afterQuickKey("旋转");
    }

    public void quickKeyScale()
    {
        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            return;
        }

        this.beginAction("添加缩放关键帧");
        this.insertAxisKeys(layer, layer.threeD
            ? new int[] {dev.bbsae.comp.CompCurves.AXIS_SCALE_X, dev.bbsae.comp.CompCurves.AXIS_SCALE_Y, dev.bbsae.comp.CompCurves.AXIS_SCALE_Z}
            : new int[] {dev.bbsae.comp.CompCurves.AXIS_SCALE_X, dev.bbsae.comp.CompCurves.AXIS_SCALE_Y});

        this.afterQuickKey("缩放");
    }

    private void insertAxisKeys(CompLayer layer, int[] axes)
    {
        for (int axis : axes)
        {
            layer.insertAxisKeyAt(axis, this.getFrame());
        }
    }

    private void insertTransformKey(CompLayer layer)
    {
        layer.insertTransformKeys(this.getFrame());
    }

    private void insertScalarKey(CompLayer layer, String path)
    {
        layer.scalarChannel(path).insert(this.getFrame(), this.scalarValue(layer, path));
    }

    private void afterQuickKey(String label)
    {
        this.refreshSheets();
        this.setStatus("已在播放头添加" + label + "关键帧");
        this.save();
    }

    /* ---------------------------------------------------- BBS interpolation */

    /**
     * Opens BBS's own interpolation picker for the keyframe selection.
     *
     * <p>The picker is {@code UIInterpolationContextMenu}: the same graph preview, icon grid,
     * four bezier arguments and copy/paste the film editor's keyframe properties use. It is
     * opened from the quick panel and from the keyframe drawer header so the curve is reachable
     * without selecting a keyframe and then hunting for the small graph icon in the inspector.</p>
     *
     * <p>If nothing is selected, the first key at the playhead is selected first; this makes the
     * button useful immediately after scrubbing to an animated frame.</p>
     */
    public void openCurveMenu()
    {
        Keyframe keyframe = this.selectedKeyframeForCurve();

        if (keyframe == null)
        {
            this.setStatus("先在关键帧栏选中一个关键帧，或把播放头移到有键的帧");

            return;
        }

        mchorse.bbs_mod.utils.interps.Interpolation interpolation = keyframe.getInterpolation();

        mchorse.bbs_mod.ui.framework.elements.context.UIInterpolationContextMenu menu =
            new mchorse.bbs_mod.ui.framework.elements.context.UIInterpolationContextMenu(interpolation);

        menu.callback(() -> this.applyInterpolation(interpolation));
        this.getContext().replaceContextMenu(menu);
    }

    private Keyframe selectedKeyframeForCurve()
    {
        if (this.keyframeEditor == null)
        {
            return null;
        }

        mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph graph =
            this.keyframeEditor.view.getGraph();
        Keyframe selected = graph.getSelected();

        if (selected != null)
        {
            return selected;
        }

        float frame = this.getFrame();

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            for (Object entry : sheet.channel.getKeyframes())
            {
                Keyframe keyframe = (Keyframe) entry;

                if (Math.abs(keyframe.getTick() - frame) < 0.5F)
                {
                    graph.selectKeyframe(keyframe);

                    return keyframe;
                }
            }
        }

        return null;
    }

    /** Copies the picker's interpolation onto every selected key, like BBS does per sheet. */
    private void applyInterpolation(mchorse.bbs_mod.utils.interps.Interpolation interpolation)
    {
        if (this.keyframeEditor == null)
        {
            return;
        }

        this.keyframeEditor.view.getGraph().setInterpolation(interpolation);
        this.keyframeEditor.view.triggerChange();
        this.save();
    }

    private void setTransformX(double value) { this.setTransformComponent(0, (float) value); }
    private void setTransformY(double value) { this.setTransformComponent(1, (float) value); }
    private void setTransformRotation(double value) { this.setTransformComponent(2, (float) value); }
    private void setTransformScaleX(double value) { this.setTransformComponent(3, (float) value); }
    private void setTransformScaleY(double value) { this.setTransformComponent(4, (float) value); }

    private void setTransformComponent(int component, float value)
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        CompLayer layer = this.selectedLayer;

        layer.ensureIndependentAxes();

        int axis = component;

        if (axis < 0 || axis >= dev.bbsae.comp.CompCurves.AXIS_COUNT)
        {
            return;
        }

        if (layer.hasAnyTransformKey())
        {
            /* Only this axis receives a key. X, Y and Z are independent channels now, so changing
             * Y can never overwrite X's key at the same frame. */
            layer.insertAxisKey(axis, this.getFrame(), value);
        }
        else
        {
            layer.putScalar(dev.bbsae.comp.CompCurves.axisPath(axis), value);
        }

        /* Same invariant as setScalar(): mark the document dirty so the edit is undoable and
         * reaches the project file. */
        this.save();
    }

    private void setOpacity(CompLayer layer, float value)
    {
        if (layer.opacityChannel().getKeyframes().isEmpty())
        {
            layer.opacity = value;
        }
        else
        {
            layer.opacityChannel().insert(this.getFrame(), value);
        }

        this.save();
    }

    /* Render queue */

    private void refreshQueue()
    {
        if (this.project == null)
        {
            this.queue.setList(List.of());
            return;
        }

        this.queue.setList(this.project.renderQueue);

        /* The queue is rebuilt on project switch/undo; a selected job from the previous object
         * graph must not stay "selected" (start/remove/retry would target a job that is no longer
         * listed). */
        if (this.selectedJob != null && !this.project.renderQueue.contains(this.selectedJob))
        {
            this.selectedJob = null;
        }
    }

    /* ------------------------------------------------------------ render queue */

    /**
     * The queue renders AE/Ffmpeg frames, so it must obey the same encoder and world-layer rules
     * as the export button. Without this, a BBS-mode project could be started from the queue and
     * silently lose its live world (the exact "preview != export" trap the export page warns
     * about) — and the risk acknowledgement was never asked.
     */
    private boolean canRunAeQueue()
    {
        if (this.project == null)
        {
            return false;
        }

        if (!this.usesAeEncoder())
        {
            this.setStatus("当前工程使用 BBS 原生编码器：渲染队列只服务 AE/Ffmpeg；请先在「输出」页切换编码器，或用 BBS 录像器（F6）导出");

            return false;
        }

        if (this.project.needsBbsWorldRender())
        {
            this.setStatus("工程含 BBS 世界/回放实体层，AE 队列取不到实时世界：请用 BBS 录像器导出");

            return false;
        }

        return true;
    }

    private void startSelectedRenderJob()
    {
        if (this.project == null || this.selectedJob == null)
        {
            this.setStatus("先在列表里选一个任务");

            return;
        }

        if (!this.canRunAeQueue())
        {
            return;
        }

        if (CompExportManager.isBusy())
        {
            this.setStatus("已有导出在运行：等它结束或先取消");

            return;
        }

        CompRenderJob job = this.selectedJob;

        if (job.status == CompRenderJob.RENDERING || job.status == CompRenderJob.ENCODING)
        {
            this.setStatus("该任务正在导出");

            return;
        }

        if (!CompExportManager.start(this.project, job))
        {
            this.setStatus("无法开始导出：" + (job.message == null || job.message.isBlank()
                ? "未知原因" : job.message));
        }
    }

    private void startRenderQueue()
    {
        if (this.project == null || !this.canRunAeQueue())
        {
            return;
        }

        if (CompExportManager.isBusy())
        {
            this.setStatus("已有导出在运行：等它结束或先取消");

            return;
        }

        boolean anyQueued = false;

        for (CompRenderJob job : this.project.renderQueue)
        {
            if (job != null && job.status == CompRenderJob.QUEUED)
            {
                anyQueued = true;

                break;
            }
        }

        if (!anyQueued)
        {
            this.setStatus("队列里没有待渲染任务（先「添加任务」）");

            return;
        }

        CompExportManager.startQueue(this.project);
        this.setStatus("渲染队列已开始（串行执行）");
    }

    private void addRenderJob()
    {
        if (this.project == null || this.doc == null)
        {
            return;
        }

        String presetId = this.queuePreset.getValue();

        if (presetId == null || presetId.isBlank())
        {
            presetId = "review_h264";
        }

        if (this.project.getPreset(presetId) == null)
        {
            this.setStatus("输出模块不存在：" + presetId + "（可能已被删除，请重新选择）");

            return;
        }

        CompRenderJob job = new CompRenderJob(
            this.nextRenderJobId(),
            this.project.id,
            this.doc.id,
            presetId
        );

        job.outputName = this.queueName.getText().isBlank() ? this.doc.id : this.queueName.getText();

        /* Two queued jobs with the same output name write the same folder/file. The second one
         * clears the first one's frames and both report success ("the export I made earlier is
         * gone"). Deduplicate within the queue. */
        String baseOutput = job.outputName;
        int suffix = 2;

        while (this.renderQueueNameTaken(job.outputName))
        {
            job.outputName = baseOutput + "_" + suffix;
            suffix += 1;
        }

        float start = (float) this.queueStart.getValue();
        float end = (float) this.queueEnd.getValue();

        /* "结束 = -1" means the whole composition. Guard reversed/out-of-range values here:
         * start > duration used to repeat the last frame and start >= end produced one frame,
         * both silently. */
        if (end <= 0F || end > this.doc.duration)
        {
            end = this.doc.duration;
        }

        start = Math.max(0F, Math.min(start, this.doc.duration));

        if (end - start < 0.001F)
        {
            this.setStatus("开始/结束时间无效：结束需大于开始（结束 = -1 表示全片）");

            return;
        }

        job.rangeStart = start;
        job.rangeEnd = end;

        this.project.renderQueue.add(job);
        this.selectedJob = job;
        this.refreshQueue();
        this.save();

        CompOutputPreset preset = this.project.getPreset(presetId);

        this.setStatus("已添加任务：" + job.outputName + " · " + (preset == null ? presetId : preset.name));
    }

    /** Unique job id: "job_" + size+1 collided after removing a middle job, and UI motion channels
     *  are keyed by id, so two live jobs shared hover/flash state. */
    /** True when another queued job already uses this output name. */
    private boolean renderQueueNameTaken(String outputName)
    {
        if (outputName == null || this.project == null)
        {
            return false;
        }

        for (CompRenderJob existing : this.project.renderQueue)
        {
            if (existing != null && outputName.equals(existing.outputName))
            {
                return true;
            }
        }

        return false;
    }

    private String nextRenderJobId()
    {
        java.util.Set<String> used = new java.util.HashSet<>();

        for (CompRenderJob existing : this.project.renderQueue)
        {
            if (existing != null && existing.id != null)
            {
                used.add(existing.id);
            }
        }

        int candidate = used.size() + 1;

        while (used.contains("job_" + candidate))
        {
            candidate += 1;
        }

        return "job_" + candidate;
    }

    /* Output-module naming, shared by the chooser, the queue rows and the buttons. */

    /** Display name of an output module id; unknown ids show themselves rather than lying. */
    private String presetLabel(String id)
    {
        CompOutputPreset preset = this.project == null ? null : this.project.getPreset(id);

        if (preset == null || preset.name == null || preset.name.isBlank())
        {
            return id;
        }

        return preset.name;
    }

    /** "视频" / "音频" for the chooser icon and the queue list. */
    private String presetKind(String id)
    {
        CompOutputPreset preset = this.project == null ? null : this.project.getPreset(id);

        if (preset == null)
        {
            return id != null && id.startsWith("audio") ? "音频" : "视频";
        }

        if ("audio".equals(preset.kind))
        {
            return "音频";
        }

        return "wav".equalsIgnoreCase(preset.container) ? "音频" : "视频";
    }

    private String defaultOutputName(String presetId)
    {
        String name = this.doc == null ? "comp" : this.doc.id;

        return "音频".equals(this.presetKind(presetId)) ? name + "_audio" : name;
    }

    private void openExportFolder()
    {
        java.nio.file.Path path = CompStore.exportRoot(this.project == null ? CompStore.DEFAULT_PROJECT : this.project.id);

        if (!OsOpen.openFolder(path))
        {
            this.setStatus("无法打开导出文件夹：" + path + "（可手动复制这个路径）");
        }
    }

    /* Host lifecycle */

    /**
     * Keeps preview audio on the playhead.
     *
     * <p>One place instead of a hook in every transport action: the audio is a function of
     * {@code (playing, ae mode, has audio, playhead)}, so it is derived every frame. Scrubbing while
     * paused therefore never starts a sound, and any code that sets {@code playing = false} stops it
     * without having to remember to.</p>
     */
    private void syncPreviewAudio()
    {
        CompProject.CompSettings settings = this.project == null ? null : this.project.settings;
        boolean enabled = this.playing
            && this.usesAeEncoder()
            && settings != null && settings.previewAudio
            && this.doc != null && dev.bbsae.client.audio.CompAudioPlayer.hasAudio(this.doc);

        if (enabled)
        {
            if (!this.previewAudioRunning)
            {
                this.previewAudioRunning = true;
                dev.bbsae.client.audio.CompAudioPlayer.play(this.project, this.doc, this.time);
            }

            dev.bbsae.client.audio.CompAudioPlayer.sync(this.time);
        }
        else if (this.previewAudioRunning)
        {
            this.previewAudioRunning = false;
            dev.bbsae.client.audio.CompAudioPlayer.pause();
        }

        /* Drains the mixer thread; must run even when disabled so a mix started a moment ago is
         * picked up (or dropped) instead of leaking. */
        dev.bbsae.client.audio.CompAudioPlayer.tick();
    }

    /** The keyframe editor is built before the panel is laid out: adding sheets at 0x0 leaves its
     *  dope sheet cache empty, which is why the timeline looked blank until something was clicked.
     *  Prime it again on the first frame that has a real size. */
    private void primeTimeline()
    {
        int w = this.keyframeEditor.area.w;
        int h = this.keyframeEditor.area.h;

        if (w <= 0 || h <= 0 || (w == this.primedWidth && h == this.primedHeight))
        {
            return;
        }

        boolean first = this.primedWidth < 0;

        this.primedWidth = w;
        this.primedHeight = h;

        if (first || this.resetTimelineView)
        {
            this.resetTimelineView = true;
            this.refreshSheets();
        }
    }

    /**
     * Push the state as it is *before* a discrete user command. The 1-second sampler is fine for
     * continuous edits, but it cannot tell "delete a layer" from "delete a layer plus the three
     * edits made in the same second"; this makes one command = one undo step.
     *
     * <p>The name is reserved for the undo UI; it is not shown yet.</p>
     */
    void beginAction(String name)
    {
        if (this.restoring || this.project == null)
        {
            return;
        }

        this.actionInProgress = true;

        String json = this.snapshotForUndo();

        if (json.isEmpty())
        {
            return;
        }

        this.pushUndo(json);
        this.documentDirty = false;

        this.redoStack.clear();
        /* Keep the sampler's baseline on the pre-action state. The sampler skips pushing a
         * state that is already the top of the undo stack, so it will not duplicate this
         * checkpoint; it will still push the post-action state later, which gives a following
         * continuous edit a proper baseline. */
        this.lastSnapshot = json;
        this.lastSnapshotMs = System.currentTimeMillis();

        if (name != null && !name.isBlank())
        {
            BbsAeAddon.LOGGER.debug("Undo checkpoint: {}", name);
        }
    }

    /** Periodic snapshot: every mutation ends up in the project JSON, so a 1-second sampler gives
     *  a real undo history without hooking every widget. */
    /**
     * Serializes the project for undo with workspace/UI fields blanked.
     *
     * <p>Ctrl+Z restores the whole {@link CompProject}, and its settings carry the dock tree, panel
     * widths, camera-preview mode and 3D viewport pose. Without this mask an undo taken after a
     * content edit silently reset the user's panel layout (entering/leaving focus mode was enough):
     * the snapshot contained whatever layout existed when the edit was captured.</p>
     */
    private String snapshotForUndo()
    {
        CompProject project = this.project;

        if (project == null || project.settings == null)
        {
            return project == null ? "" : CompStore.snapshot(project);
        }

        CompProject.CompSettings settings = project.settings;

        String dockLayout = settings.dockLayout;
        String dockHidden = settings.dockHiddenPanels;
        boolean dockUnlocked = settings.dockUnlocked;
        int leftWidth = settings.leftPanelWidth;
        int rightWidth = settings.rightPanelWidth;
        int timelineHeight = settings.timelineHeight;
        int drawerHeight = settings.keyframeDrawerHeight;
        int quickWidth = settings.quickPanelWidth;
        int timelinePage = settings.timelinePage;
        int density = settings.uiDensity;
        boolean cameraPreview = settings.cameraPreviewMode;
        boolean viewport3D = settings.viewport3D;
        boolean poseValid = settings.viewportPoseValid;
        float yaw = settings.viewportYaw;
        float pitch = settings.viewportPitch;
        float distance = settings.viewportDistance;
        float targetX = settings.viewportTargetX;
        float targetY = settings.viewportTargetY;
        float targetZ = settings.viewportTargetZ;
        boolean ortho = settings.viewportOrtho;
        boolean collapse = settings.previewControlsCollapsed;
        boolean filterExpanded = settings.previewFilterExpanded;
        /* Editor view state is workspace, not content: it must not leak into undo snapshots or
         * undo would also restore "the selection I had back then". */
        boolean drawerOpen = settings.keyframeDrawerOpen;
        String selectedIds = settings.selectedLayerIds;
        String selectedAudio = settings.selectedAudioId;
        int inspectorPage = settings.inspectorPage;
        int leftPage = settings.leftPage;
        double timelineZoom = settings.timelineZoom;
        double timelineShift = settings.timelineShift;
        int timelineScroll = settings.timelineScrollY;

        try
        {
            settings.dockLayout = "";
            settings.dockHiddenPanels = "";
            settings.dockUnlocked = false;
            settings.leftPanelWidth = 0;
            settings.rightPanelWidth = 0;
            settings.timelineHeight = 0;
            settings.keyframeDrawerHeight = 0;
            settings.quickPanelWidth = 0;
            settings.timelinePage = 0;
            settings.uiDensity = 0;
            settings.cameraPreviewMode = false;
            settings.viewport3D = false;
            settings.viewportPoseValid = false;
            settings.viewportYaw = 0F;
            settings.viewportPitch = 0F;
            settings.viewportDistance = 0F;
            settings.viewportTargetX = 0F;
            settings.viewportTargetY = 0F;
            settings.viewportTargetZ = 0F;
            settings.viewportOrtho = false;
            settings.previewControlsCollapsed = false;
            settings.previewFilterExpanded = false;
            settings.keyframeDrawerOpen = false;
            settings.selectedLayerIds = "";
            settings.selectedAudioId = "";
            settings.inspectorPage = 0;
            settings.leftPage = 0;
            settings.timelineZoom = 0D;
            settings.timelineShift = 0D;
            settings.timelineScrollY = 0;

            return CompStore.snapshot(project);
        }
        finally
        {
            settings.dockLayout = dockLayout;
            settings.dockHiddenPanels = dockHidden;
            settings.dockUnlocked = dockUnlocked;
            settings.leftPanelWidth = leftWidth;
            settings.rightPanelWidth = rightWidth;
            settings.timelineHeight = timelineHeight;
            settings.keyframeDrawerHeight = drawerHeight;
            settings.quickPanelWidth = quickWidth;
            settings.timelinePage = timelinePage;
            settings.uiDensity = density;
            settings.cameraPreviewMode = cameraPreview;
            settings.viewport3D = viewport3D;
            settings.viewportPoseValid = poseValid;
            settings.viewportYaw = yaw;
            settings.viewportPitch = pitch;
            settings.viewportDistance = distance;
            settings.viewportTargetX = targetX;
            settings.viewportTargetY = targetY;
            settings.viewportTargetZ = targetZ;
            settings.viewportOrtho = ortho;
            settings.previewControlsCollapsed = collapse;
            settings.previewFilterExpanded = filterExpanded;
            settings.keyframeDrawerOpen = drawerOpen;
            settings.selectedLayerIds = selectedIds;
            settings.selectedAudioId = selectedAudio;
            settings.inspectorPage = inspectorPage;
            settings.leftPage = leftPage;
            settings.timelineZoom = timelineZoom;
            settings.timelineShift = timelineShift;
            settings.timelineScrollY = timelineScroll;
        }
    }

    /** Restores the live workspace fields over a project graph rebuilt from an undo snapshot. */
    private void preserveWorkspaceSettings(CompProject.CompSettings from, CompProject.CompSettings to)
    {
        if (from == null || to == null)
        {
            return;
        }

        to.dockLayout = from.dockLayout;
        to.dockHiddenPanels = from.dockHiddenPanels;
        to.dockUnlocked = from.dockUnlocked;
        to.leftPanelWidth = from.leftPanelWidth;
        to.rightPanelWidth = from.rightPanelWidth;
        to.timelineHeight = from.timelineHeight;
        to.keyframeDrawerHeight = from.keyframeDrawerHeight;
        to.quickPanelWidth = from.quickPanelWidth;
        to.timelinePage = from.timelinePage;
        to.uiDensity = from.uiDensity;
        to.cameraPreviewMode = from.cameraPreviewMode;
        to.viewport3D = from.viewport3D;
        to.viewportPoseValid = from.viewportPoseValid;
        to.viewportYaw = from.viewportYaw;
        to.viewportPitch = from.viewportPitch;
        to.viewportDistance = from.viewportDistance;
        to.viewportTargetX = from.viewportTargetX;
        to.viewportTargetY = from.viewportTargetY;
        to.viewportTargetZ = from.viewportTargetZ;
        to.viewportOrtho = from.viewportOrtho;
        to.previewControlsCollapsed = from.previewControlsCollapsed;
        to.previewFilterExpanded = from.previewFilterExpanded;
        to.keyframeDrawerOpen = from.keyframeDrawerOpen;
        to.selectedLayerIds = from.selectedLayerIds;
        to.selectedAudioId = from.selectedAudioId;
        to.inspectorPage = from.inspectorPage;
        to.leftPage = from.leftPage;
        to.timelineZoom = from.timelineZoom;
        to.timelineShift = from.timelineShift;
        to.timelineScrollY = from.timelineScrollY;
    }

    private void captureSnapshot()
    {
        long now = System.currentTimeMillis();
        /* A discrete command just ran. Record its result as the sampler's new baseline, but do
         * not push it: beginAction already pushed the pre-command state. Without this, a value
         * edit made right after the command would be merged into the next undo step. */
        if (this.actionInProgress)
        {
            /* A discrete command is over in one frame, but beginAction() is also raised by
             * continuous gestures (curve key drag, pen handle drag, timeline drag). For those the
             * first frame is NOT the post-command baseline: consuming it here made the sampler
             * push the "dragged a few pixels" state as the next undo step, so the first Ctrl+Z
             * after a long drag only undid a few pixels (the user had to press it twice). Wait
             * until the gesture has actually finished, then record its end state as baseline. */
            boolean gestureActive = mchorse.bbs_mod.graphics.window.Window.isMouseButtonPressed(0)
                || (this.trackTimeline != null && this.trackTimeline.isInteracting())
                || (this.curveEditor != null && this.curveEditor.isInteracting());

            if (gestureActive)
            {
                return;
            }

            this.actionInProgress = false;

            if (!this.restoring && this.project != null)
            {
                String json = this.snapshotForUndo();

                if (!json.isEmpty())
                {
                    this.lastSnapshot = json;
                    this.lastSnapshotMs = now;
                }
            }

            return;
        }

        /* Time-based, not frame-based: render() runs at the display refresh rate, so counting
         * frames made the undo history fill in ~0.3 s instead of 1 s. */
        if (now - this.lastSnapshotMs < 1000L || this.restoring || this.project == null
            || (this.trackTimeline != null && this.trackTimeline.isInteracting())
            || (this.curveEditor != null && this.curveEditor.isInteracting())
            || mchorse.bbs_mod.graphics.window.Window.isMouseButtonPressed(0))
        {
            return;
        }

        this.lastSnapshotMs = now;

        /* Nothing changed: serializing the whole project every second was the editor's steady
         * background cost (megabytes of JSON + garbage) even with the playhead parked. */
        if (!this.documentDirty)
        {
            return;
        }

        String json = this.snapshotForUndo();

        this.documentDirty = false;

        if (json.isEmpty() || json.equals(this.lastSnapshot))
        {
            return;
        }

        if (!this.lastSnapshot.isEmpty())
        {
            this.pushUndo(this.lastSnapshot);
        }

        this.lastSnapshot = json;
        this.redoStack.clear();
    }

    /**
     * Undo/redo replaces the whole project object graph. Doing that while a drag is still
     * mutating the old graph left the drag writing into orphan objects ("the drag did nothing" /
     * values snapping back), so gestures win and the shortcut is ignored until the mouse is up.
     */
    private boolean gestureBlocksHistory()
    {
        boolean active = mchorse.bbs_mod.graphics.window.Window.isMouseButtonPressed(0)
            || (this.trackTimeline != null && this.trackTimeline.isInteracting())
            || (this.curveEditor != null && this.curveEditor.isInteracting())
            || this.isPanelResizing();

        if (active)
        {
            this.setStatus("正在拖动：先松开鼠标再撤销/重做");
        }

        return active;
    }

    public void undo()
    {
        if (this.gestureBlocksHistory())
        {
            return;
        }

        if (this.undoStack.isEmpty())
        {
            this.setStatus("没有可撤销的操作");

            return;
        }

        this.pushRedo(this.snapshotForUndo());
        String json = this.undoStack.pop();

        this.undoChars = Math.max(0L, this.undoChars - json.length());
        this.applySnapshot(json);
        this.setStatus("已撤销");
    }

    public void redo()
    {
        if (this.gestureBlocksHistory())
        {
            return;
        }

        if (this.redoStack.isEmpty())
        {
            this.setStatus("没有可重做的操作");

            return;
        }

        this.pushUndo(this.snapshotForUndo());
        String json = this.redoStack.pop();

        this.redoChars = Math.max(0L, this.redoChars - json.length());
        this.applySnapshot(json);
        this.setStatus("已重做");
    }

    private void applySnapshot(String json)
    {
        CompProject restored = CompStore.restore(json);

        if (restored == null)
        {
            return;
        }

        String compId = this.doc == null ? null : this.doc.id;
        String layerId = this.selectedLayer == null ? null : this.selectedLayer.id;
        CompProject.CompSettings liveSettings = this.project == null ? null : this.project.settings;
        /* Undo replaces the object graph; keep the user's selection / picker state instead of
         * collapsing a 3-layer selection to the primary layer and resetting the dope-sheet view. */
        java.util.List<String> restoreIds = new ArrayList<>(this.selectedIds);
        String restoreAudioId = this.selectedAudio == null ? null : this.selectedAudio.id;
        mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeState restoreDrawer =
            this.keyframeEditor == null ? null : this.keyframeEditor.view.cacheState();
        /* Ancestor breadcrumb before the object graph is replaced; entries are re-resolved on the
         * restored graph below so undo inside a precomp does not trap the user in it. */
        java.util.List<PrecompReturn> restorePrecompPath = new ArrayList<>(this.precompStack);

        this.restoring = true;

        try
        {
            this.project = restored;
            /* Undo restores content, not the workspace: keep the live dock tree / panel widths /
             * camera-preview mode instead of jumping to the layout captured with the edit. */
            this.preserveWorkspaceSettings(liveSettings, restored.settings);
            this.doc = compId == null ? restored.getCompOrFirst(CompStore.DEFAULT_COMP) : restored.getComp(compId);

            if (this.doc == null)
            {
                this.doc = restored.getCompOrFirst(CompStore.DEFAULT_COMP);
            }

            this.setSingleSelection(null);

            /* Re-resolve the whole selection by id (multi-select survives undo). */
            CompLayer primary = null;

            if (this.doc != null)
            {
                for (String id : restoreIds)
                {
                    CompLayer layer = this.doc.getLayer(id);

                    if (layer != null)
                    {
                        this.selectedIds.add(layer.id);

                        if (primary == null)
                        {
                            primary = layer;
                        }
                    }
                }

                if (primary == null && layerId != null)
                {
                    primary = this.doc.getLayer(layerId);
                }

                if (primary != null)
                {
                    this.selectedIds.clear();
                    this.selectedIds.add(primary.id);

                    for (String id : restoreIds)
                    {
                        CompLayer layer = this.doc.getLayer(id);

                        if (layer != null && !this.selectedIds.contains(layer.id))
                        {
                            this.selectedIds.add(layer.id);
                        }
                    }
                }
                else if (!this.doc.layers.isEmpty())
                {
                    primary = this.doc.layers.get(this.doc.layers.size() - 1);
                    this.selectedIds.add(primary.id);
                }
            }

            this.selectedLayer = primary;

            if (this.selectedAudio != null)
            {
                this.selectedAudio = null;
            }

            if (restoreAudioId != null && this.doc != null && this.doc.audio != null)
            {
                for (dev.bbsae.comp.CompAudio clip : this.doc.audio)
                {
                    if (clip != null && restoreAudioId.equals(clip.id))
                    {
                        this.selectedAudio = clip;

                        break;
                    }
                }
            }

            this.lastSnapshot = json;
            this.primedWidth = this.keyframeEditor == null ? -1 : Math.max(1, this.keyframeEditor.area.w);
            this.primedHeight = this.keyframeEditor == null ? -1 : this.keyframeEditor.area.h;
            this.resetTimelineView = false;

            /* The restored project is a new object graph. Rebuild the precomp breadcrumb by id
             * instead of clearing it: clearing while `doc` is the nested comp left the user
             * inside a precomp with no return button, and Esc has nothing to pop — Ctrl+Z trapped
             * the user there until the panel was closed. */
            this.precompStack.clear();

            for (int i = restorePrecompPath.size() - 1; i >= 0; i--)
            {
                PrecompReturn state = restorePrecompPath.get(i);
                CompDocument ancestor = restored.getComp(state.doc.id);

                if (ancestor != null)
                {
                    this.precompStack.push(new PrecompReturn(ancestor, state.layerId, state.time,
                        state.viewShift, state.viewZoom));
                }
            }

            if (this.precompBack != null)
            {
                this.precompBack.setVisible(!this.precompStack.isEmpty());
            }

            if (this.keyframeEditor != null)
            {
                this.keyframeEditor.view.pickKeyframe(null);
            }

            this.updateCompPath();

            if (this.trackTimeline != null)
            {
                this.trackTimeline.refresh();
            }

            this.refreshLayers();
            this.refreshInspector();
            this.refreshSheets();
            this.refreshQueue();

            /* The dope sheet was just rebuilt with fresh sheets, so restore the drawer's selected
             * keys and time zoom on top of them (cacheState was taken before the undo). */
            if (restoreDrawer != null && this.keyframeEditor != null)
            {
                this.keyframeEditor.view.applyState(restoreDrawer);
            }
            /* The bridge caches the document it renders; without this an undo leaves the BBS
             * preview/backdrop pointing at the pre-undo object graph. */
            dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);

            /* `restored` is a new object graph; the layout source has to resolve the new settings
             * instance and the dock must re-layout instead of keeping stale panel rectangles. */
            if (this.dock != null && this.dockSource != null)
            {
                this.dockSource.reload();
                this.dock.locked(!this.dockUnlocked());
                this.dock.refresh();
                this.applyLayout();
            }

            this.save();
        }
        finally
        {
            this.restoring = false;
            this.actionInProgress = false;
        }
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if ((this.settingsOverlay != null && this.settingsOverlay.isOpen())
            || (this.onboarding != null && this.onboarding.isOpen()))
        {
            return super.subMouseScrolled(context);
        }

        if (this.cameraFlying && Math.abs(context.mouseWheel) > 0.001D)
        {
            this.flightSpeed = Math.max(20F, Math.min(20000F,
                this.flightSpeed * (float) Math.pow(1.2D, context.mouseWheel)));
            this.setStatus(String.format(java.util.Locale.ROOT, "飞行速度 %.0f /s", this.flightSpeed));

            return true;
        }

        /* Alt+wheel must work over the preview as well as over the timeline. The timeline
         * consumes it when the cursor is there; this is the fallback for the rest of the panel. */
        if (!this.startScreenOpen && !this.modalLayer.isVisible() && this.doc != null
            && CompInput.altDown() && Math.abs(context.mouseWheel) > 0.001D)
        {
            this.trackTimeline.zoom(context.mouseWheel > 0D ? 1D : -1D);

            return true;
        }

        return super.subMouseScrolled(context);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        int key = context.getKeyCode();
        KeyAction action = context.getKeyAction();
        boolean pressed = action == KeyAction.PRESSED;
        boolean repeat = action == KeyAction.REPEAT;
        boolean typing = context.activeElement instanceof IFocusedUIElement;

        boolean ctrl = CompInput.ctrlDown();
        boolean shift = CompInput.shiftDown();
        boolean alt = CompInput.altDown();

        /* Shortcut capture is global: the settings overlay arms it, and the next physical key
         * becomes the binding. Modifier-only presses are ignored by CompKeybinds. */
        if (CompKeybinds.isCapturing())
        {
            if (pressed)
            {
                CompKeybinds.capture(key, ctrl, shift, alt);
            }

            return true;
        }

        if (this.onboarding != null && this.onboarding.isOpen())
        {
            this.onboarding.subKeyPressed(context);

            return true;
        }

        if (this.settingsOverlay != null && this.settingsOverlay.isOpen())
        {
            if (pressed && key == GLFW.GLFW_KEY_ESCAPE)
            {
                this.settingsOverlay.close();

                return true;
            }

            if (typing)
            {
                return super.subKeyPressed(context);
            }

            return true;
        }

        if (this.startScreenOpen)
        {
            if (pressed && !typing && key == GLFW.GLFW_KEY_ESCAPE)
            {
                this.leaveStartScreen();

                return true;
            }

            /* Text fields inside the landing screen still need their keystrokes; editor
             * shortcuts must not fire while the project chooser is up. */
            return super.subKeyPressed(context);
        }

        /* BBS dock shortcuts (same keys as the film editor): undo layout change / maximize panel /
         * cycle the tabs of a panel stack under the cursor. */
        if (pressed && this.dock != null && !typing)
        {
            if (ctrl && alt && key == GLFW.GLFW_KEY_Z)
            {
                if (this.dock.undoLayout())
                {
                    this.dockLayoutDirty = true;
                    this.setStatus("已撤销一次布局改动");
                }

                return true;
            }

            if (ctrl && !alt && key == GLFW.GLFW_KEY_TAB)
            {
                if (this.dock.cycleDockStackTab(shift ? -1 : 1))
                {
                    this.dockLayoutDirty = true;
                }

                return true;
            }

            if (ctrl && !alt && key == GLFW.GLFW_KEY_SPACE)
            {
                if (this.dock.toggleMaximizeUnderCursor())
                {
                    this.dockLayoutDirty = true;
                }

                return true;
            }
        }

        /* Pen gestures (Enter/Esc/Delete) must win over the generic Esc handling below: ending the
         * path is not "deselect the layer". */
        if (!typing && this.penTool != null && this.penTool.onKey(context))
        {
            return true;
        }

        /* Esc priority: cancel the thing that is actually in progress before the generic ladder
         * below closes the drawer / exits a precomp / clears the selection. The generic handler
         * used to run first, so Esc during BBS camera flight deselected the layer instead of
         * ending the flight (and during a track/audio drag it just closed a drawer). */
        if (pressed && !typing && key == GLFW.GLFW_KEY_ESCAPE)
        {
            if (this.cameraFlying)
            {
                this.setCameraFlying(false);

                return true;
            }

            if (this.cameraToolDragging)
            {
                this.cameraToolCancelRequested = true;

                return true;
            }

            if (this.trackTimeline != null && this.trackTimeline.cancelDrag())
            {
                return true;
            }

            /* Esc must also stop the gestures the panel owns itself: curve keys/handles, preview
             * layer/gizmo/camera-handle drags and legacy splitter drags. Previously these fell
             * through to the generic ladder and cleared the selection while the mouse was still
             * down, which then wrote the drag into a fresh selection. */
            if (this.curveEditor != null && this.curveEditor.endDrag())
            {
                this.setStatus("已结束曲线拖动（Esc）");

                return true;
            }

            if (this.previewGestureCancel != null && this.previewGestureCancel.getAsBoolean())
            {
                return true;
            }

            if (this.cancelPanelResize())
            {
                return true;
            }

            if (this.cameraToolActive())
            {
                this.setCameraTool(false);
                this.setStatus("AE 摄像机工具已关闭（Esc）");

                return true;
            }
        }

        /* G arms/disarms the pen. Deliberately not in CompKeybinds: the keybind table is shared
         * with other panels, and a tool that also has a visible toolbar button does not need to be
         * remappable. */
        if (pressed && !typing && !ctrl && !shift && !alt && key == GLFW.GLFW_KEY_G)
        {
            this.togglePenTool();

            return true;
        }

        if (key == GLFW.GLFW_KEY_ESCAPE && pressed)
        {
            if (this.importBrowser != null)
            {
                this.closeImportBrowser();

                return true;
            }

            if (this.assetBrowser != null)
            {
                this.assetBrowser.removeFromParent();
                this.assetBrowser = null;

                return true;
            }

            if (this.modalLayer.isVisible())
            {
                this.closeModal();

                return true;
            }

            if (typing)
            {
                return super.subKeyPressed(context);
            }

            if (this.keyframeOpen)
            {
                this.setKeyframeOpen(false);

                return true;
            }

            if (!this.precompStack.isEmpty())
            {
                this.exitPrecomp();

                return true;
            }

            if (this.selectedLayer != null)
            {
                this.clearSelection();

                return true;
            }
        }

        if (this.assetBrowser != null || this.importBrowser != null || this.modalLayer.isVisible())
        {
            return super.subKeyPressed(context);
        }

        if (this.cameraFlying)
        {
            /* Flight owns the keyboard while it is active. Movement is polled from the physical
             * keys in updateFlight(); here we only need the exit gesture, and swallowing the rest
             * prevents editor shortcuts from firing mid-flight. */
            if (pressed && (key == GLFW.GLFW_KEY_F || key == GLFW.GLFW_KEY_ESCAPE))
            {
                this.setCameraFlying(false);

                return true;
            }

            return true;
        }

        /* F is the BBS flight key, exactly as in BBS's film editor. When a BBS-style camera is
         * selected it takes priority over the global F focus shortcut; with any other selection F
         * still toggles focus mode. */
        if (pressed && !typing && !ctrl && !shift && !alt && key == GLFW.GLFW_KEY_F && this.canFlyCamera())
        {
            this.setCameraFlying(true);

            return true;
        }

        if (this.viewportMode && pressed && !typing && this.subViewportKey(key))
        {
            return true;
        }

        boolean repeatable = key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
            || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN;

        if ((!pressed && !(repeat && repeatable)) || typing)
        {
            return super.subKeyPressed(context);
        }

        int scheme = this.getShortcutScheme();
        String shortcut = CompKeybinds.find(key, ctrl, shift, alt, scheme);

        if (shortcut != null && this.performShortcut(shortcut))
        {
            return true;
        }

        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)
        {
            int direction = key == GLFW.GLFW_KEY_RIGHT ? 1 : -1;
            int amount = direction * (shift ? 5 : 1);

            if (alt && !ctrl) this.nudgeSelected(amount); else this.stepFrame(amount);

            return true;
        }

        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)
        {
            int direction = key == GLFW.GLFW_KEY_UP ? -1 : 1;

            if (ctrl) this.selectRelative(direction);
            else if (alt) this.moveLayerOrder(this.selectedLayer, key == GLFW.GLFW_KEY_UP ? 1 : -1);
            else this.jumpToEditPoint(direction);

            return true;
        }

        return super.subKeyPressed(context);
    }

    /** Executes an editable shortcut action; returns false when this context cannot run it. */
    private boolean performShortcut(String action)
    {
        if (action == null)
        {
            return false;
        }

        switch (action)
        {
            case "play.pause" -> { this.togglePlay(); return true; }
            case "play.back" -> { this.shuttle(-1); return true; }
            case "play.forward" -> { this.shuttle(1); return true; }
            case "play.stop" -> { this.pause(); return true; }
            case "key.transform" -> { this.addTransformKey(false); this.openKeyframes(); return true; }
            case "key.prev" -> { this.navigateKeyframe(-1); return true; }
            case "key.next" -> { this.navigateKeyframe(1); return true; }
            case "clip.split" -> { this.splitSelectedAtPlayhead(); return true; }
            case "clip.trimIn" -> { this.trimSelectedToPlayhead(true); return true; }
            case "clip.trimOut", "clip.trimOutAlt" -> { this.trimSelectedToPlayhead(false); return true; }
            case "timeline.snap" -> { this.trackTimeline.toggleSnapping(); this.updateToolbarLabels(); return true; }
            case "timeline.fit" -> { this.trackTimeline.fit(); return true; }
            case "timeline.zoomIn" -> { this.trackTimeline.zoom(1D); return true; }
            case "timeline.zoomOut" -> { this.trackTimeline.zoom(-1D); return true; }
            case "track.rowUp" -> { this.trackTimeline.stepRowHeight(2); return true; }
            case "track.rowDown" -> { this.trackTimeline.stepRowHeight(-2); return true; }
            case "work.in" -> { this.setWorkAreaIn(); return true; }
            case "work.out" -> { this.setWorkAreaOut(); return true; }
            case "work.clearIn" -> { this.clearWorkAreaIn(); return true; }
            case "work.clearOut" -> { this.clearWorkAreaOut(); return true; }
            case "marker.add" -> { this.addMarkerAtPlayhead(); return true; }
            case "marker.next" -> { this.jumpMarker(1); return true; }
            case "marker.prev" -> { this.jumpMarker(-1); return true; }
            case "go.start" -> { this.setFrame(0F); return true; }
            case "go.end" -> { this.setFrame(this.getDurationTicks()); return true; }
            case "selection.delete" -> { this.deleteSelected(); return true; }
            case "edit.undo" -> { this.undo(); return true; }
            case "edit.redo" -> { this.redo(); return true; }
            case "layer.duplicate" -> { this.duplicateSelected(); return true; }
            case "select.all" -> { this.selectAllLayers(); this.setStatus("已全选 " + this.getSelectionCount() + " 个图层"); return true; }
            case "tool.select" -> { this.setStatus("选择工具：本合成器始终为选择模式"); return true; }
            case "file.save" -> { this.saveNow(); return true; }
            case "file.export" -> { this.showExportDialog(); return true; }
            case "view.focus" -> { this.toggleFocusMode(); return true; }
            case "settings.open" -> { this.openSettings("motion"); return true; }
            case "help.shortcuts" -> { this.openSettings("keys"); return true; }
            default -> { return false; }
        }
    }
    private void togglePlay()
    {
        if (this.playing)
        {
            this.pause();

            return;
        }

        this.playing = true;
        this.playRate = 1F;
        /* The first frame after a resume must not consume the time spent paused. */
        this.lastPlayNanos = 0L;
        this.updatePlayLabel();
    }

    private void pause()
    {
        this.playing = false;
        this.playRate = 0F;
        this.lastPlayNanos = 0L;
        this.updatePlayLabel();
    }

    private void shuttle(int direction)
    {
        if (direction > 0)
        {
            this.playRate = this.playing && this.playRate > 0F ? Math.min(4F, this.playRate * 2F) : 1F;
        }
        else
        {
            this.playRate = this.playing && this.playRate < 0F ? Math.max(-4F, this.playRate * 2F) : -1F;
        }

        this.playing = true;
        this.lastPlayNanos = 0L;
        this.updatePlayLabel();
    }

    private void jumpToEditPoint(int direction)
    {
        if (this.doc == null)
        {
            return;
        }

        List<Float> times = new ArrayList<>();

        times.add(0F);
        times.add(this.doc.duration);
        times.add(this.doc.workAreaIn);
        times.add(this.doc.workAreaOut);

        for (CompMarker marker : this.doc.markers)
        {
            times.add(marker.time);
        }

        for (CompLayer layer : this.doc.layers)
        {
            times.add(layer.in);
            times.add(layer.out);
        }

        times.sort(Float::compare);
        float now = this.time;
        float epsilon = 1F / Math.max(1F, this.doc.fps);
        float target = direction > 0 ? this.doc.duration : 0F;

        if (direction > 0)
        {
            for (float time : times)
            {
                if (time > now + epsilon)
                {
                    target = time;
                    break;
                }
            }
        }
        else
        {
            for (float time : times)
            {
                if (time < now - epsilon)
                {
                    target = time;
                }
            }
        }

        this.setFrame(Math.round(target * this.doc.fps));
    }

    private void selectRelative(int direction)
    {
        if (this.doc == null || this.doc.layers.isEmpty())
        {
            return;
        }

        List<CompLayer> rows = new ArrayList<>(this.doc.layers);

        java.util.Collections.reverse(rows);

        int index = this.selectedLayer == null ? -1 : rows.indexOf(this.selectedLayer);
        int next = Math.max(0, Math.min(rows.size() - 1, index + direction));

        this.selectLayer(rows.get(next));
    }

    private void trimSelectedToPlayhead(boolean in)
    {
        if (this.doc == null || this.selectedLayer == null)
        {
            this.setStatus("先在时间轴选择一条轨道");

            return;
        }

        if (this.selectedLayer.locked)
        {
            this.setStatus("轨道已锁定：先解锁再裁剪");

            return;
        }

        if (in)
        {
            this.selectedLayer.trimInTo(this.time, this.doc.fps);
        }
        else
        {
            this.selectedLayer.trimOutTo(this.time, this.doc.fps, this.doc.duration);
        }

        this.onTrackEdited();
        this.setStatus(in ? "已裁剪入点到播放头" : "已裁剪出点到播放头");
    }

    private void setWorkAreaIn()
    {
        if (this.doc == null)
        {
            return;
        }

        this.doc.workAreaIn = Math.max(0F, Math.min(this.doc.workAreaOut - 1F / Math.max(1F, this.doc.fps), this.time));
        this.setStatus("工作区入点：" + String.format(java.util.Locale.ROOT, "%.2fs", this.doc.workAreaIn));
        this.save();
    }

    private void setWorkAreaOut()
    {
        if (this.doc == null)
        {
            return;
        }

        this.doc.workAreaOut = Math.min(this.doc.duration, Math.max(this.doc.workAreaIn + 1F / Math.max(1F, this.doc.fps), this.time));
        this.setStatus("工作区出点：" + String.format(java.util.Locale.ROOT, "%.2fs", this.doc.workAreaOut));
        this.save();
    }

    private void clearWorkAreaIn()
    {
        if (this.doc == null)
        {
            return;
        }

        this.doc.workAreaIn = 0F;
        this.setStatus("已清除工作区入点");
        this.save();
    }

    private void clearWorkAreaOut()
    {
        if (this.doc == null)
        {
            return;
        }

        this.doc.workAreaOut = this.doc.duration;
        this.setStatus("已清除工作区出点");
        this.save();
    }

    private void addMarkerAtPlayhead()
    {
        if (this.doc == null)
        {
            return;
        }

        CompMarker marker = new CompMarker("marker_" + (this.doc.markers.size() + 1), "标记", this.time);

        this.doc.markers.add(marker);

        if (this.inspectorPage == PAGE_COMP)
        {
            this.refreshInspector();
        }

        this.save();
        this.setStatus("已添加标记");
    }

    private void jumpMarker(int direction)
    {
        if (this.doc == null || this.doc.markers.isEmpty())
        {
            return;
        }

        List<CompMarker> markers = new ArrayList<>(this.doc.markers);

        markers.sort((a, b) -> Float.compare(a.time, b.time));

        CompMarker target = null;
        float epsilon = 1F / Math.max(1F, this.doc.fps);

        if (direction > 0)
        {
            for (CompMarker marker : markers)
            {
                if (marker.time > this.time + epsilon)
                {
                    target = marker;
                    break;
                }
            }

            if (target == null)
            {
                target = markers.get(0);
            }
        }
        else
        {
            for (CompMarker marker : markers)
            {
                if (marker.time < this.time - epsilon)
                {
                    target = marker;
                }
            }

            if (target == null)
            {
                target = markers.get(markers.size() - 1);
            }
        }

        this.setFrame(Math.round(target.time * this.doc.fps));
    }

    /** Select the keyframe at (or nearest to) the given tick and put the playhead on it. */
    public void selectKeyframe(CompLayer layer, float tick)
    {
        if (layer == null)
        {
            return;
        }

        Keyframe target = null;
        float best = 0.5F;

        for (Keyframe keyframe : layer.allKeyframes())
        {
            float distance = Math.abs(keyframe.getTick() - tick);

            if (distance <= best)
            {
                target = keyframe;
                best = distance;
            }
        }

        if (target == null)
        {
            return;
        }

        this.openKeyframes();
        /* pickKeyframe() only feeds the keyframe-properties panel; the dope-sheet highlight (and
         * the Delete/duplicate actions that read the sheet's selection) come from the graph. */
        this.keyframeEditor.view.pickKeyframe(target);
        this.keyframeEditor.view.getGraph().selectKeyframe(target);
        this.setFrame(target.getTick());
        this.trackTimeline.revealTime(this.time);
    }

    /** Jumps to the previous/next keyframe of the selected layer and selects it in the dope sheet. */
    public void navigateKeyframe(int direction)
    {
        if (this.selectedLayer == null)
        {
            this.setStatus("先在时间轴选择一条轨道");

            return;
        }

        List<mchorse.bbs_mod.utils.keyframes.Keyframe> keyframes = new ArrayList<>(this.selectedLayer.allKeyframes());

        if (keyframes.isEmpty())
        {
            this.setStatus("该轨道还没有关键帧；点属性旁的秒表添加");

            return;
        }

        keyframes.sort((a, b) -> Float.compare(a.getTick(), b.getTick()));

        float now = this.getFrame();
        float epsilon = 0.01F;
        mchorse.bbs_mod.utils.keyframes.Keyframe target = null;

        if (direction > 0)
        {
            for (mchorse.bbs_mod.utils.keyframes.Keyframe keyframe : keyframes)
            {
                if (keyframe.getTick() > now + epsilon)
                {
                    target = keyframe;
                    break;
                }
            }

            if (target == null)
            {
                target = keyframes.get(0);
            }
        }
        else
        {
            for (mchorse.bbs_mod.utils.keyframes.Keyframe keyframe : keyframes)
            {
                if (keyframe.getTick() < now - epsilon)
                {
                    target = keyframe;
                }
            }

            if (target == null)
            {
                target = keyframes.get(keyframes.size() - 1);
            }
        }

        this.openKeyframes();
        /* pickKeyframe() only feeds the keyframe-properties panel; the dope-sheet highlight (and
         * the Delete/duplicate actions that read the sheet's selection) come from the graph. */
        this.keyframeEditor.view.pickKeyframe(target);
        this.keyframeEditor.view.getGraph().selectKeyframe(target);
        this.setFrame(target.getTick());
        this.trackTimeline.revealTime(this.time);
    }

    /** The editable list is the single source of truth; the old static card is kept as legacy. */
    public void showShortcutHelp()
    {
        this.openSettings("keys");
    }

    private void showShortcutHelpLegacy()
    {
        boolean premiere = this.getShortcutScheme() == 0;

        this.openModalSized("快捷键", (premiere ? "Premiere Pro 方案" : "BBS 原版方案")
            + "；时间轴获得焦点时可用，Esc 关闭。", 520, 430);

        UIScrollView scroll = new UIScrollView(ScrollDirection.VERTICAL);

        scroll.relative(this.modalBox).x(12).y(52).w(1F, -24).h(1F, -104);
        scroll.column(2).vertical().stretch().scroll().padding(4);

        List<String[]> list = new ArrayList<>();

        list.add(new String[] {"空格", "播放 / 暂停"});
        list.add(new String[] {"J / K / L", "倒放 / 暂停 / 正放（连按加速）"});
        list.add(new String[] {"← / →", "上一帧 / 下一帧"});
        list.add(new String[] {"Shift + ← / →", "前后 5 帧"});
        list.add(new String[] {"Alt + ← / →", "所选轨道前后挪 1 帧"});
        list.add(new String[] {"Alt + Shift + ← / →", "前后挪 5 帧"});
        list.add(new String[] {"Home / End", "合成开头 / 结尾"});
        list.add(new String[] {"↑ / ↓", "上一个 / 下一个剪辑点"});
        list.add(new String[] {"Alt + 滚轮", "时间轴缩放（Premiere 习惯）"});
        list.add(new String[] {"Shift + 滚轮", "时间轴水平平移"});
        list.add(new String[] {", / .", "上一个 / 下一个关键帧"});
        list.add(new String[] {"[ / ]", "裁剪所选轨道入点 / 出点到播放头"});
        list.add(new String[] {premiere ? "Alt + [ / ]" : "I / O", "设置工作区入点 / 出点"});
        list.add(new String[] {premiere ? "E" : "Ctrl + Shift + I / O", premiere ? "裁剪出点到播放头" : "清除工作区入点 / 出点"});
        list.add(new String[] {"C 或 Ctrl + K", "在播放头拆分轨道"});
        list.add(new String[] {"S", "吸附开关"});
        list.add(new String[] {"M", "添加合成标记"});
        list.add(new String[] {"Shift + M / Ctrl + Shift + M", "下一个 / 上一个标记"});
        list.add(new String[] {"= / -", "时间轴放大 / 缩小（1.35× 一档）"});
        list.add(new String[] {"Alt + = / -", "轨道高度放大 / 缩小（同 Alt+滚轮）"});
        list.add(new String[] {"\\", "时间轴适配全长"});
        list.add(new String[] {"F", "聚焦预览"});
        list.add(new String[] {"Alt + K", "在播放头添加变换关键帧"});
        list.add(new String[] {"Ctrl + ↑ / ↓", "选择上一条 / 下一条轨道"});
        list.add(new String[] {"Alt + ↑ / ↓", "轨道上移 / 下移一层"});
        list.add(new String[] {premiere ? "Ctrl + Shift + D" : "Ctrl + D", "复制轨道"});
        list.add(new String[] {"Delete", "删除轨道"});
        list.add(new String[] {"Ctrl + Z / Ctrl + Shift + Z", "撤销 / 重做"});
        list.add(new String[] {"Ctrl + S", "保存工程"});
        list.add(new String[] {"Ctrl + M", "导出视频"});
        list.add(new String[] {premiere ? "Ctrl + Alt + K" : "Ctrl + /", "打开这份快捷键表"});
        list.add(new String[] {"Esc", "关闭弹窗 / 取消选择"});

        String[][] shortcuts = list.toArray(new String[0][]);

        for (String[] pair : shortcuts)
        {
            UIElement row = new UIElement();
            UILabel keys = new UILabel(IKey.constant(pair[0]));
            UILabel description = new UILabel(IKey.constant(pair[1]));

            row.w(1F).h(14);
            keys.relative(row).x(0).y(0).w(104).h(14);
            keys.color(0xFF8FD0FF, false);
            description.relative(row).x(108).y(0).w(1F, -108).h(14);
            row.add(keys, description);
            scroll.add(row);
        }

        this.modalBox.add(scroll);
        this.modalBox.add(this.modalButton("关闭", 16, 394, 488, this::closeModal));
    }

    /**
     * Files dropped on the game window.
     *
     * <p>Routing by extension is the whole feature: a user drags a png, an mp4, a wav or a font and
     * it becomes the thing that type means here — an image layer, a footage layer (via the FFmpeg
     * importer), an audio clip, a text layer with that font. No dialog, no "which menu was it in".</p>
     */
    public void handleDroppedFiles(java.util.List<java.nio.file.Path> files)
    {
        if (this.project == null || this.doc == null)
        {
            this.setStatus("先打开一个工程，再把文件拖进来");

            return;
        }

        int imported = 0;

        for (java.nio.file.Path path : files)
        {
            if (path == null || !java.nio.file.Files.isRegularFile(path))
            {
                continue;
            }

            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

            try
            {
                if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif"))
                {
                    this.importDroppedImage(path.toFile());
                    imported++;
                }
                else if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv")
                    || name.endsWith(".webm") || name.endsWith(".avi") || name.endsWith(".m4v"))
                {
                    this.importVideoFile(path.toFile());
                    imported++;
                }
                else if (name.endsWith(".wav") || name.endsWith(".ogg"))
                {
                    this.addAudioClip(path.toFile());
                    imported++;
                }
                else if (name.endsWith(".ttf") || name.endsWith(".otf"))
                {
                    this.dropFont(path.toFile());
                    imported++;
                }
                else
                {
                    this.setStatus("不支持的文件类型：" + path.getFileName());
                }
            }
            catch (Exception e)
            {
                dev.bbsae.BbsAeAddon.LOGGER.error("Could not import dropped file {}", path, e);
                this.notifyError("导入失败：" + path.getFileName() + " · " + e.getMessage());
            }
        }

        if (imported > 0)
        {
            this.refreshLayers();
            this.refreshInspector();
            this.save();
            this.setStatus("已从拖放导入 " + imported + " 个文件");
        }
    }

    /** A dropped picture becomes an image layer sized to fit the composition. */
    private void importDroppedImage(java.io.File file) throws java.io.IOException
    {
        String name = this.copyIntoProjectImages(file);
        CompLayer layer = new CompLayer("image", name, "image");

        layer.imagePath = "assets/images/" + name;
        layer.x = this.doc.width / 2F;
        layer.y = this.doc.height / 2F;
        layer.out = this.doc.duration;

        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
            CompStore.assetRoot(this.project.id).resolve("images").resolve(name).toFile());

        if (image != null)
        {
            float scale = Math.min(1F, Math.min(this.doc.width * 0.8F / image.getWidth(), this.doc.height * 0.8F / image.getHeight()));

            layer.width = Math.max(16F, image.getWidth() * scale);
            layer.height = Math.max(16F, image.getHeight() * scale);
        }

        this.doc.addLayer(layer);
        this.selectLayer(layer);
        this.setStatus("已拖入图片：" + name);
    }

    /** A dropped font applies to the selected text layer, or creates one. */
    private void dropFont(java.io.File file)
    {
        CompLayer layer = this.selectedLayer;

        if (layer == null || !"text".equals(layer.type))
        {
            CompLayer text = new CompLayer("text", file.getName(), "text");

            text.text = "Sample Text";
            text.x = this.doc.width / 2F;
            text.y = this.doc.height / 2F;
            text.out = this.doc.duration;
            this.doc.addLayer(text);
            this.selectLayer(text);
            layer = text;
        }

        this.importFontFile(layer, file);
    }

    /**
     * Advances the editor playhead. BBS passes {@code MinecraftClient.getTickDelta()} here, but
     * that is the fraction of a <b>client tick</b> since the last tick, not the duration of the
     * previous frame. Summing it made the preview speed depend on the display refresh rate (at
     * 144 fps it ran at roughly 3.6× the film's speed, while a 30 fps window ran at 0.75×).
     * Playback therefore uses a monotonic wall clock, exactly like BBS's tick-based player.
     */
    @Override
    public void startRenderFrame(float tickDelta)
    {
        if (this.workspaceMode == WORKSPACE_REPLAY)
        {
            /* The replay workspace owns its own 3D viewport; driving the film camera/compositor
             * playhead here would fight its session cursor. */
            this.lastPlayNanos = 0L;

            return;
        }

        if (!this.playing || this.doc == null || this.renderView)
        {
            this.lastPlayNanos = 0L;
            /* BBS calls this before the world pass. Seeding the film camera here (instead of
             * waiting for the preview widget's render call) removes the one-frame lag between
             * the BBS world frame and the composition that samples it. */
            dev.bbsae.client.overlay.BbsPreviewBridge.sync(this.time);

            return;
        }

        long now = System.nanoTime();
        float delta = CompTime.playbackDelta(this.lastPlayNanos, now);

        this.lastPlayNanos = now;

        float rate = this.playRate == 0F ? 1F : this.playRate;
        float in = Math.max(0F, Math.min(this.doc.duration, this.doc.workAreaIn));
        float out = Math.max(0F, Math.min(this.doc.duration, this.doc.workAreaOut));

        if (out - in < 0.001F)
        {
            in = 0F;
            out = this.doc.duration;
        }

        this.time += delta * rate;

        if (this.time >= out)
        {
            this.time = in + (this.time - out) % Math.max(0.001F, out - in);
        }
        else if (this.time < in)
        {
            this.time = out - (in - this.time) % Math.max(0.001F, out - in);
        }

        /* The world is rendered after this method returns. Set the BBS film camera to the new
         * playhead now so the world frame and the composition frame are the same instant. */
        dev.bbsae.client.overlay.BbsPreviewBridge.sync(this.time);

        /* Keep the playhead visible while playing, but never fight a manual pan/zoom: revealTime
         * only scrolls when the playhead is outside the comfortable centre band. */
        if (this.trackTimeline != null && !this.trackTimeline.isInteracting())
        {
            this.trackTimeline.revealTime(this.time);
        }
    }

    @Override
    public void render(UIContext context)
    {
        /* Quick panel changes the timeline width; doing that on the press frame of a drag made the
         * track's x->time mapping change under the cursor (clips jumped a few pixels on grab).
         * Defer layout changes until the mouse is released. */
        boolean wantQuick = this.selectedLayer != null && !this.renderView && !this.focusMode
            && this.workspaceMode == WORKSPACE_COMPOSITE
            && !mchorse.bbs_mod.graphics.window.Window.isMouseButtonPressed(0);

        if (this.quickPanel != null && this.quickPanel.isVisible() != wantQuick)
        {
            this.quickPanel.setVisible(wantQuick);
            this.applyLayout();
        }

        if (this.area.w > 0 && this.area.h > 0
            && (this.area.w != this.lastLayoutWidth || this.area.h != this.lastLayoutHeight))
        {
            this.lastLayoutWidth = this.area.w;
            this.lastLayoutHeight = this.area.h;
            this.applyLayout();
        }

        this.updateCameraFlight(context);
        this.syncPreviewAudio();

        /* AE preview/export cannot capture the world depth buffer unless the BBS live frame is
         * available; depth_* effects then silently no-op (and BBS-overlay export differs). The
         * renderer exposes a degraded state + a one-line reason — surface it once instead of
         * letting the user wonder why the fog does nothing. */
        String degraded = dev.bbsae.client.render.CompRenderer.depthEffectsDegradedMessage();

        if (degraded == null || degraded.isEmpty())
        {
            this.depthDegradedNotice = null;
        }
        else if (!degraded.equals(this.depthDegradedNotice))
        {
            this.depthDegradedNotice = degraded;
            this.setStatus(degraded);
        }

        /* An export continues while the user edits (it now runs on a snapshot), but its only
         * visible controls live on the render page. Surface progress on the status line so the
         * user is not left with an invisible background job after switching pages. */
        if (!this.renderView && CompExportManager.isBusy())
        {
            long now = System.currentTimeMillis();

            if (now - this.lastExportStatusMs > 2000L && this.statusTicks <= 0)
            {
                this.lastExportStatusMs = now;
                this.setStatus("导出中 " + Math.round(CompExportManager.progress() * 100F)
                    + "% · 打开「渲染」页可查看/取消");
            }
        }

        /* Opaque backdrop, unconditionally.
         *
         * The dashboard draws its own background from BBSSettings, which is intentionally a
         * translucent dark layer over the (blurred) world so BBS panels feel like an overlay. That
         * is wrong for an editor: a compositor UI with the game shining through makes every label
         * harder to read and makes the preview look like it is floating. Painting the panel's own
         * rect here means "no transparency" does not depend on a global setting the user cannot
         * find. */
        this.suppressBbsOnionSkin();

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF0E1116);
        this.renderShellAtmosphere(context);

        if (this.workspaceMode == WORKSPACE_REPLAY)
        {
            /* The replay workspace owns its own 3D viewport and timeline. Do not run the
             * composition's prime/reconcile/precache/save paths while it is visible: they would
             * rebuild the hidden timeline and advance the film camera behind the replay editor. */
            this.layoutModeBar();
            super.render(context);

            return;
        }

        this.primeTimeline();
        this.reconcileTimelinePage();
        this.captureSnapshot();

        /* Debounced persistence: one write per gesture instead of one per mouse event. */
        if (this.saveRequested
            && System.currentTimeMillis() - this.saveRequestedMs >= SAVE_DEBOUNCE_MS
            && !Window.isMouseButtonPressed(0))
        {
            this.flushSave();
        }

        /* A dock layout change is workspace state: flush it once the drag settled, never mid-drag
         * (the tree is re-encoded for the project file, which must not happen 60 times a second).
         * The throttle also keeps a window resize from encoding the tree on every frame. */
        if (this.dockLayoutDirty
            && !Window.isMouseButtonPressed(0)
            && System.currentTimeMillis() - this.lastDockFlushMs >= DOCK_FLUSH_THROTTLE_MS)
        {
            this.lastDockFlushMs = System.currentTimeMillis();
            this.flushDockLayout();
        }

        /* Pre-render job: a couple of frames per display frame, so the editor stays responsive
         * while the ruler fills up behind the playhead. */
        if (dev.bbsae.client.render.CompPrecache.isRunning())
        {
            dev.bbsae.client.render.CompPrecache.tick(this.project, this.doc);
        }

        /* Point tracker: one analyzed frame per display frame, the same cooperative budget idea as
         * the pre-render cache. The engine seeks the film runner itself (the preview sync above is
         * suspended while it runs), so a composition with a BBS world layer tracks real pixels. */
        if (dev.bbsae.client.render.TrackerEngine.isRunning())
        {
            dev.bbsae.client.render.TrackerEngine.update();

            dev.bbsae.comp.CompTracker running = dev.bbsae.client.render.TrackerEngine.current();

            if (running != null && running.state != dev.bbsae.comp.CompTracker.STATE_TRACKING)
            {
                this.setStatus("跟踪器：" + (running.message == null || running.message.isEmpty() ? "结束" : running.message));
                this.refreshInspector();
                this.save();
            }
            else if (running != null)
            {
                int total = Math.max(1, running.endFrame - running.startFrame + 1);
                int done = Math.max(0, running.cursorFrame - running.startFrame);

                this.setStatus(String.format(java.util.Locale.ROOT, "跟踪中：%d / %d 帧", done, total));
            }
        }

        if (this.statusTicks > 0)
        {
            this.statusTicks -= 1;
        }

        long nowMs = System.currentTimeMillis();

        if (nowMs - this.lastAutoSaveMs >= 60000L && this.project != null
            && this.project.settings != null && this.project.settings.autoSave)
        {
            this.lastAutoSaveMs = nowMs;
            this.save();
        }

        if (!this.buildingInspector
            && !this.inspectorBindings.isEmpty()
            && this.time != this.lastInspectorTime
            && context.activeElement == null
            && !Window.isMouseButtonPressed(0))
        {
            this.lastInspectorTime = this.time;
            this.rebindInspectorValues();
        }

        this.timecode.label = IKey.constant(this.doc == null ? "00:00:00:00" : CompTime.timecode(this.getTime(), this.doc.fps));

        String selection = this.selectedLayer == null
            ? ""
            : " / 已选：" + this.layerLabel(this.selectedLayer)
                + String.format(java.util.Locale.ROOT, "  入 %.2fs  出 %.2fs  时长 %.2fs",
                    this.selectedLayer.in, this.selectedLayer.out, Math.max(0F, this.selectedLayer.out - this.selectedLayer.in));
        String defaultStatus = this.doc == null
            ? "未打开合成"
            : (this.doc.name == null || this.doc.name.isBlank() ? this.doc.id : this.doc.name)
                + " / " + this.doc.layers.size() + " 条轨道" + selection;

        this.status.label = IKey.constant(this.statusTicks > 0 && !this.statusMessage.isEmpty() ? this.statusMessage : defaultStatus);

        /* The readout is a summary, not a per-frame invariant: counting effects and querying the
         * cache ten times a second is plenty and keeps the label allocation off the hot path. */
        if (++this.previewInfoTick >= 10)
        {
            this.previewInfoTick = 0;

            /* Re-trusts the pre-render cache when a "dirty" save turned out not to change any
             * pixel (panel settings, for example). Cheap and throttled inside the cache. */
            dev.bbsae.client.render.CompPrecache.validate(this.project, this.doc);

            if (this.doc != null)
            {
                int effects = 0;

                for (CompLayer layer : this.doc.layers)
                {
                    for (CompEffect effect : layer.effects)
                    {
                        if (effect != null && effect.enabled)
                        {
                            effects++;
                        }
                    }
                }

                String shader = dev.bbsae.client.render.ShaderCompat.label();
                String depth = this.hasDepthEffects() && !dev.bbsae.client.overlay.BbsPreviewBridge.depthAvailable()
                    ? "  ·  深度降级" : "";
                /* What the world capture actually produced this frame: under a shader pack a
                 * fallback (colortex0, un-tonemapped) or a missing final frame is exactly the kind
                 * of thing that would otherwise read as "the preview is just dark". */
                String capture = dev.bbsae.client.render.SceneColor.status();
                float previewScale = dev.bbsae.client.render.CompRenderer.previewScale(
                    this.doc, Math.max(1, this.preview.area.w), Math.max(1, this.preview.area.h), 0);
                String scaleText = this.preview.area.w >= 360
                    ? " · 预览 " + Math.round(previewScale * 100F) + "%" : "";

                /* One short line. The old readout appended shader/depth/camera/cache status and
                 * overflowed the preview's own width, which made it collide with the BBS flight
                 * HUD. The authoritative camera hint lives in the inspector and the status bar. */
                this.previewInfo.label = IKey.constant(this.doc.width + "x" + this.doc.height + "  " + (int) this.doc.fps
                    + "fps  " + effects + " FX  ·  " + shader
                    + (capture == null || "世界帧".equals(capture) ? "" : " · " + capture)
                    + scaleText + depth);
                this.updatePrecacheLabel();
            }
            else
            {
                this.previewInfo.label = IKey.constant("预览");
            }
        }

        if (this.renderView)
        {
            this.renderStatus.label = IKey.constant(CompExportManager.status());
        }

        this.panelMotion.tickFrame();
        this.panelMotion.markShown();

        /* A single accent hairline under the header, with a very slow breath. It is the only
         * always-on glow in the editor: everything else lights up in response to the pointer or a
         * state change. */
        if (this.header != null && this.header.area.h > 0 && CompPrefs.get().glow)
        {
            float breathe = this.panelMotion.breathe(3.6F);
            int accent = mchorse.bbs_mod.BBSSettings.primaryColor() | 0xFF000000;

            context.batcher.box(this.area.x, this.header.area.ey() - 1, this.area.ex(), this.header.area.ey(),
                UiGfx.alpha(accent, 0.16F + 0.12F * breathe));
        }

        boolean overlay = (this.onboarding != null && this.onboarding.isOpen())
            || (this.settingsOverlay != null && this.settingsOverlay.isOpen());

        if (overlay)
        {
            super.render(context);

            /* BBS draws tooltips after the panel tree; an editor tooltip would otherwise float
             * on top of the onboarding/settings card. */
            context.resetTooltip();

            return;
        }

        float entrance = this.panelMotion.intro(0F);
        MatrixStack stack = context.batcher.getContext().getMatrices();

        this.leftPageMotion.update();
        this.inspectorPageMotion.update();
        this.timelinePageMotion.update();

        stack.push();
        stack.translate(0F, (1F - entrance) * 6F, 0F);

        try
        {
            super.render(context);

            /* Page switches: a fast veil over the page that just changed. Drawn after the children
             * (and inside the entrance transform) so it covers the hard cut without touching any
             * BBS control's own alpha. */
            this.leftPageMotion.renderVeil(context, this.effectColumn.area);
            this.inspectorPageMotion.renderVeil(context, this.inspectorColumn.area);
            this.timelinePageMotion.renderVeil(context, this.timeline.area);

            if (this.cameraFlying && this.preview != null)
            {
                int x = this.preview.area.x + 8;
                int y = this.preview.area.ey() - 42;

                context.batcher.box(x, y, x + 300, y + 34, 0xD90B1118);
                context.batcher.outline(x, y, x + 300, y + 34, 0xFF4C9AFF);
                context.batcher.textShadow("BBS 飞行模式  ·  F 退出并打关键帧", x + 7, y + 4, 0xFF8CC8FF);
                context.batcher.textShadow("WASD 移动  空格/Shift 升降  鼠标转向  滚轮调速", x + 7, y + 18, 0xFFAEBBCD);
            }
        }
        finally
        {
            stack.pop();
        }
    }

    private void updatePlayLabel()
    {
        if (!this.playing)
        {
            this.play.label = IKey.constant("播放");

            return;
        }

        if (this.playRate == 0F || this.playRate == 1F)
        {
            this.play.label = IKey.constant("暂停");

            return;
        }

        this.play.label = IKey.constant((this.playRate < 0F ? "◀ " : "▶ ") + (int) Math.abs(this.playRate) + "×");
    }

    private void stepFrame(int direction)
    {
        if (this.doc == null)
        {
            return;
        }

        this.setFrame(this.getFrame() + direction);
    }

    public CompDocument getDocument()
    {
        return this.doc;
    }

    public float getTime()
    {
        return this.time;
    }

    public void setTime(float time)
    {
        float duration = this.doc == null ? 1F : Math.max(0.001F, this.doc.duration);

        this.time = Math.max(0F, Math.min(duration, time));

        if (this.previewAudioRunning)
        {
            dev.bbsae.client.audio.CompAudioPlayer.seek(this.time);
        }
    }

    public float getFrame()
    {
        return this.doc == null ? 0F : this.getTime() * this.doc.fps;
    }

    public int getDurationTicks()
    {
        return this.doc == null ? 1 : Math.max(1, Math.round(this.doc.duration * this.doc.fps));
    }

    public void setFrame(float frame)
    {
        if (this.doc == null)
        {
            return;
        }

        float fps = Math.max(1F, this.doc.fps);

        this.playing = false;
        this.playRate = 0F;
        this.lastPlayNanos = 0L;
        this.time = Math.max(0F, Math.min(this.doc.duration, frame / fps));

        this.updatePlayLabel();
    }

    @Override
    public boolean needsBackground()
    {
        return true;
    }

    String layerLabel(CompLayer layer)
    {
        if (layer == null)
        {
            return "";
        }

        return layer.name == null || layer.name.isBlank() ? layer.id : layer.name;
    }

    private static String blendName(int mode)
    {
        return switch (mode)
        {
            case CompLayer.BLEND_ADD -> "相加";
            case CompLayer.BLEND_MULTIPLY -> "正片叠底";
            case CompLayer.BLEND_SCREEN -> "滤色";
            case CompLayer.BLEND_OVERLAY -> "叠加";
            case CompLayer.BLEND_SOFT_LIGHT -> "柔光";
            case CompLayer.BLEND_HARD_LIGHT -> "强光";
            case CompLayer.BLEND_DIFFERENCE -> "差值";
            case CompLayer.BLEND_EXCLUSION -> "排除";
            case CompLayer.BLEND_DARKEN -> "变暗";
            case CompLayer.BLEND_LIGHTEN -> "变亮";
            case CompLayer.BLEND_COLOR_DODGE -> "颜色减淡";
            case CompLayer.BLEND_COLOR_BURN -> "颜色加深";
            case CompLayer.BLEND_MASK -> "蒙版";
            case CompLayer.BLEND_EXCLUDE -> "排除（蒙版）";
            case CompLayer.BLEND_LINEAR_BURN -> "线性加深";
            case CompLayer.BLEND_LINEAR_DODGE -> "线性减淡";
            case CompLayer.BLEND_LINEAR_LIGHT -> "线性光";
            case CompLayer.BLEND_VIVID_LIGHT -> "亮光";
            case CompLayer.BLEND_PIN_LIGHT -> "点光";
            case CompLayer.BLEND_SOFT_OVERLAY -> "柔光叠加";
            case CompLayer.BLEND_SUBTRACT -> "减去";
            case CompLayer.BLEND_DIVIDE -> "划分";
            case CompLayer.BLEND_DARKER_COLOR -> "较暗颜色";
            case CompLayer.BLEND_LIGHTER_COLOR -> "较亮颜色";
            case CompLayer.BLEND_HUE -> "色相";
            case CompLayer.BLEND_SATURATION -> "饱和度";
            case CompLayer.BLEND_COLOR -> "颜色";
            case CompLayer.BLEND_LUMINANCE -> "明度";
            case CompLayer.BLEND_COLOR_MULTIPLY -> "颜色相乘";
            default -> "正常";
        };
    }

    /**
     * Applies the selected layer as a mask (intersection) or an exclude (difference) for the layer
     * directly <b>below</b> it.
     *
     * <p>Layer order is bottom-first: index 0 is drawn first, so "visually below" is {@code index-1}
     * and "above" is {@code index+1}. Alight Motion's flow is "put the shape above, select it, press
     * mask", which means the *target* is the previous element.</p>
     *
     * <p>The shape itself stops being visible (it becomes a guide) — otherwise the fill would cover
     * exactly the region it is supposed to reveal. Toggling its eye brings it back for editing.</p>
     */
    private void applyMatteToBelow(CompLayer source, boolean exclude)
    {
        if (this.doc == null || source == null)
        {
            return;
        }

        int index = this.doc.layers.indexOf(source);
        CompLayer target = index > 0 ? this.doc.layers.get(index - 1) : null;

        if (target == null)
        {
            this.setStatus("这一层下面没有图层：把形状层放到要蒙版的图层上方");

            return;
        }

        this.beginAction(exclude ? "排除蒙版" : "蒙版");
        target.trackMatteId = source.id;
        target.trackMatteEnabled = true;
        target.trackMatteMode = exclude ? CompLayer.MATTE_ALPHA_INVERTED : CompLayer.MATTE_ALPHA;

        /* The shape becomes a guide: visible while editing, never part of the frame. */
        source.guide = true;
        source.visible = false;

        this.selectLayer(target);
        this.refreshLayers();
        this.refreshInspector();
        this.save();
        this.setStatus((exclude ? "排除" : "蒙版") + "：" + this.layerLabel(target)
            + " ← " + this.layerLabel(source) + "（形状层已隐藏，点眼睛可显示）");
    }

    private static String matteName(int mode)
    {
        return switch (mode)
        {
            case CompLayer.MATTE_LUMA -> "亮度";
            case CompLayer.MATTE_ALPHA_INVERTED -> "Alpha 反转";
            case CompLayer.MATTE_LUMA_INVERTED -> "亮度反转";
            default -> "Alpha";
        };
    }

    /* ------------------------------------------------------------------ point tracker */

    private void createTracker()
    {
        if (this.doc == null)
        {
            return;
        }

        this.beginAction("新建跟踪器");

        CompTracker tracker = new CompTracker();

        tracker.id = CompStore.uniqueId("tracker", this.doc.trackers, (t) -> t.id);
        tracker.name = "跟踪器 " + (this.doc.trackers.size() + 1);
        tracker.x = this.doc.width / 2F;
        tracker.y = this.doc.height / 2F;

        int frames = this.doc.getFrameCount();

        tracker.startFrame = 0;
        tracker.endFrame = Math.max(0, frames - 1);

        /* Default the range to the selected layer's trim, which is almost always what a tracked
         * element is for; the anchor defaults to the frame centre, to be moved onto the feature. */
        if (this.selectedLayer != null)
        {
            float fps = Math.max(1F, this.doc.fps);

            tracker.startFrame = Math.max(0, Math.min(frames - 1, Math.round(this.selectedLayer.in * fps)));
            tracker.endFrame = Math.max(tracker.startFrame, Math.min(frames - 1, Math.round(this.selectedLayer.out * fps)));
        }

        this.doc.trackers.add(tracker);
        this.refreshInspector();
        this.save();
        this.setStatus("已创建 " + tracker.name + "：把锚点 X/Y 移到目标上（用预览对位），再点「开始分析」");
    }

    private void toggleTracker(CompTracker tracker)
    {
        if (this.project == null || this.doc == null || tracker == null)
        {
            return;
        }

        if (dev.bbsae.client.render.TrackerEngine.isRunning())
        {
            dev.bbsae.client.render.TrackerEngine.cancel();
            this.setStatus("跟踪已停止");
            this.refreshInspector();

            return;
        }

        dev.bbsae.client.render.TrackerEngine.start(this.project, this.doc, tracker);
        this.setStatus("开始跟踪：" + tracker.name + "（每显示帧分析一帧，期间预览会停在分析帧上）");
    }

    /**
     * Writes the tracker's samples into the selected layer's position channels.
     *
     * <p>The tracked offset is relative to the anchor: the layer keeps its base pose at the anchor
     * frame and is moved by "how far the feature travelled", which is what "让这层跟着那个点走"
     * means. Existing position keys are replaced — the layer now follows the tracker, and a mixture
     * of old keys and baked keys would jitter between them.</p>
     */
    private void bakeTrackerToLayer(CompTracker tracker)
    {
        if (this.doc == null || tracker == null)
        {
            return;
        }

        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            this.setStatus("先在时间轴选择要应用跟踪的图层");

            return;
        }

        if (tracker.samples.isEmpty())
        {
            this.setStatus("跟踪器还没有分析结果，先点「开始分析」");

            return;
        }

        this.beginAction("应用跟踪到位置");

        int anchor = Math.max(0, Math.min(this.doc.getFrameCount() - 1, tracker.startFrame));
        float baseX = layer.transformAt(anchor).translate.x;
        float baseY = layer.transformAt(anchor).translate.y;
        float anchorX = tracker.xAt(anchor);
        float anchorY = tracker.yAt(anchor);

        /* First insert migrates a legacy shared Transform channel into the per-axis channels the
         * bake is about to replace; without it an old project's original position keys would
         * resurrect between the baked frames. */
        layer.insertAxisKey(CompCurves.AXIS_X, anchor, baseX);
        layer.insertAxisKey(CompCurves.AXIS_Y, anchor, baseY);
        layer.axisChannel(CompCurves.AXIS_X).removeAll();
        layer.axisChannel(CompCurves.AXIS_Y).removeAll();

        for (double[] sample : tracker.samples)
        {
            int frame = (int) Math.round(sample[0]);

            layer.insertAxisKey(CompCurves.AXIS_X, frame, baseX + ((float) sample[1] - anchorX));
            layer.insertAxisKey(CompCurves.AXIS_Y, frame, baseY + ((float) sample[2] - anchorY));
        }

        this.refreshInspector();
        this.save();
        this.setStatus("已把 " + tracker.samples.size() + " 帧跟踪结果写入 " + this.layerLabel(layer) + " 的位置");
    }

    private static String trackerStateLabel(CompTracker tracker)
    {
        if (tracker.state == CompTracker.STATE_TRACKING)
        {
            return "跟踪中";
        }

        if (tracker.state == CompTracker.STATE_DONE)
        {
            return "已完成";
        }

        if (tracker.state == CompTracker.STATE_FAILED)
        {
            return "失败";
        }

        return tracker.samples.isEmpty() ? "未分析" : "有结果";
    }

    private void setMaskMode(CompMask mask, int mode)
    {
        this.beginAction("蒙版模式");
        mask.mode = mode;
        this.refreshInspector();
        this.save();
    }

    private static String maskModeName(int mode)
    {
        return switch (mode)
        {
            case CompMask.MODE_SUBTRACT -> "相减";
            case CompMask.MODE_INTERSECT -> "相交";
            case CompMask.MODE_EXCLUDE -> "排除";
            default -> "相加";
        };
    }

    /* ------------------------------------------------------------------ pen tool bridge */

    /**
     * The pen tool. Lazy because the preview element is built before the toolbar in the
     * constructor and starts calling {@link UIPenTool#renderOverlay} on its first render — by then
     * construction is over, so the lazy accessor is always safe.
     */
    UIPenTool penTool()
    {
        if (this.penTool == null)
        {
            this.penTool = new UIPenTool(this);
        }

        return this.penTool;
    }

    /** One undo checkpoint per discrete pen command (see {@code beginAction}). */
    void penBeginAction(String name)
    {
        this.beginAction(name);
    }

    /**
     * Animation-aware scalar write. Every pen drag/edit goes through here, so a mask or shape path
     * that already has keyframes keeps writing keys at the playhead instead of editing stale base
     * values the renderer ignores.
     */
    void penSetScalar(CompLayer layer, String path, float value)
    {
        this.setScalar(layer, path, value);
    }

    /** Rebuilds the inspector after a structural pen change (path created/point added/removed). */
    void penRefreshInspector()
    {
        this.refreshInspector();
    }

    /** Marks the document dirty after a pen edit (debounced write + undo sampler baseline). */
    void penSave()
    {
        this.save();
    }

    /**
     * Drops the caches whose fingerprints predate the {@code closed} field.
     *
     * <p>{@code CompRenderCache}/{@code CompFrameServer}/{@code CompLayerSourceCache} fingerprint
     * mask and shape points but not the closed flag (those files are owned by other work streams),
     * so a parked preview would keep serving the old picture after closing/opening a path. This is
     * the deliberate stop-gap; the real fix is adding {@code closed} to the signatures.</p>
     */
    private void invalidatePathClosedCaches()
    {
        dev.bbsae.client.render.CompFrameServer.invalidate();
        dev.bbsae.client.render.CompRenderCache.clear();
        dev.bbsae.client.render.CompLayerSourceCache.clear();
        dev.bbsae.client.render.CompPrecache.markDirty();
    }

    private void toggleMaskClosed(CompMask mask)
    {
        this.beginAction(mask.closed ? "蒙版路径断开" : "蒙版路径闭合");
        mask.closed = !mask.closed;
        this.invalidatePathClosedCaches();
        this.refreshInspector();
        this.save();
    }

    private void toggleShapeClosed(CompShape shape)
    {
        this.beginAction(shape.closed ? "形状路径断开" : "形状路径闭合");
        shape.closed = !shape.closed;
        this.invalidatePathClosedCaches();
        this.refreshInspector();
        this.save();
    }

    private void togglePenTool()
    {
        this.penTool().toggle();

        /* The pen and the AE camera tool both own the preview's mouse buttons; arming one must
         * disarm the other or a click does two things at once. */
        if (this.penTool().isActive() && this.cameraToolActive())
        {
            this.setCameraTool(false);
        }

        this.refreshInspector();
    }
}
