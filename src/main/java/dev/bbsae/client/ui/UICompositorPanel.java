package dev.bbsae.client.ui;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.export.CompExportManager;
import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.client.render.CompScene;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.client.render.CompViewportRenderer;
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
import dev.bbsae.comp.CompositionClip;
import dev.bbsae.client.render.CompRenderContext;
import dev.bbsae.client.ui.CompInput;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IFocusedUIElement;
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
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
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
    private final UIButton exportFilm;
    private final UIButton replaySelector;
    private final UIIcon focusButton;
    private final UIButton settingsButton;
    private final UIIcon prevFrame;
    private final UIIcon nextFrame;
    private final UIIcon addLayer;

    private final UIElement header;
    private final UIElement body;
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
    private final UIElement previewBar;
    private final UILabel previewInfo;
    private final UIElement inspectorColumn;
    private final UICompTabBar inspectorTabs;
    private final UIScrollView inspector;
    private final UIKeyframeEditor keyframeEditor;
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
    /** The Blender-like free viewport is open (see {@link #enter3DSpace()}). */
    private boolean viewportMode;
    /** The editor's own camera; never rendered into a frame. */
    private final dev.bbsae.client.render.CompViewportCamera viewportCamera = new dev.bbsae.client.render.CompViewportCamera();
    /** Red "AE 编码器" chip over the preview; visible only while the frame server drives the view. */
    private UIElement encoderBadge;
    /** True while the preview is auditioning the composition's mix. */
    private boolean previewAudioRunning;
    private UIToggle effectsToggle;
    private UIToggle masksToggle;
    private UIToggle bbsBackdropToggle;
    private UIIcon cameraViewButton;
    private UIIcon space3DButton;
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
    private boolean keyframeViewNeedsFit = true;
    private float playRate;
    private String statusMessage = "";
    private int statusTicks;
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

    private static final int TIMELINE_HEIGHT = 250;

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
    private int keyframeDrawerHeight = 154;
    private static final int KEYFRAME_DRAWER_MIN = 96;
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

        this.save = CompUi.flat("保存", "把工程写入磁盘（Ctrl+S）；工程在 config/bbsae/projects", () ->
        {
            this.save();
            this.setStatus("工程已保存");
        });

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

        this.header = new UIElement();
        this.header.h(24);

        this.title.relative(this.header).x(8).y(inset).w(86).h(control);
        this.replaySelector.relative(this.header).x(100).y(inset).w(150).h(control);
        this.play.relative(this.header).x(256).y(inset).w(44).h(control);
        this.prevFrame.relative(this.header).x(302).y(inset).w(control).h(control);
        this.nextFrame.relative(this.header).x(302 + control + 2).y(inset).w(control).h(control);
        this.timecode.relative(this.header).x(302 + control * 2 + 8).y(inset).w(86).h(control);
        this.save.relative(this.header).x(1F, -300).y(inset).w(48).h(control);
        this.exportFilm.relative(this.header).x(1F, -248).y(inset).w(54).h(control);
        this.renderMode.relative(this.header).x(1F, -190).y(inset).w(54).h(control);
        this.settingsButton.relative(this.header).x(1F, -132).y(inset).w(48).h(control);
        this.focusButton.relative(this.header).x(1F, -40).y(inset).w(control).h(control);

        this.header.add(this.title, this.replaySelector, this.play, this.prevFrame, this.nextFrame,
            this.timecode, this.save, this.exportFilm, this.renderMode, this.settingsButton, this.focusButton);

        /* Left column: the two panels an editor actually browses from - effects and media.
         * The layer stack lives in the timeline's track headers; keeping a second layer list
         * here would be a second source of truth for selection and z-order. */
        this.effectLibrary = new UIEffectLibrary(this);
        this.effectLibrary.relative(this.effectColumn = new UIElement()).x(0).y(22).w(1F).h(1F, -22);

        this.assetPanel = new UIScrollView(ScrollDirection.VERTICAL);
        this.assetPanel.relative(this.effectColumn).x(0).y(22).w(1F).h(1F, -22);
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
        this.leftTabs.relative(this.effectColumn).x(0).y(0).w(1F).h(22);
        this.presetBrowser = new UICompPresetBrowser(this);
        this.presetBrowser.relative(this.effectColumn).x(0).y(22).w(1F).h(1F, -22);
        this.presetBrowser.setVisible(false);

        this.leftTabs.addTab("效果库");
        this.leftTabs.addTab("素材");
        this.leftTabs.addTab("预设");
        this.leftTabs.onSelect((index) ->
        {
            this.leftPage = index;
            this.effectLibrary.setVisible(index == 0);
            this.assetPanel.setVisible(index == 1);
            this.presetBrowser.setVisible(index == 2);

            if (index == 2)
            {
                this.presetBrowser.refresh();
            }
        });
        this.leftTabs.active(() -> this.leftPage);

        this.effectColumn.w(190).h(1F);
        this.effectColumn.add(this.leftTabs, this.effectLibrary, this.assetPanel, this.presetBrowser);

        this.preview = new UIElement()
        {
            private static final int DRAG_NONE = 0;
            private static final int DRAG_MOVE = 1;
            private static final int DRAG_CORNER = 2;
            private static final int DRAG_EDGE = 3;
            private static final int DRAG_ROTATE = 4;
            private static final int DRAG_CAMERA_BODY = 5;
            private static final int DRAG_CAMERA_TARGET = 6;

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
            private final Matrix4f startInverse = new Matrix4f();
            private float startScreenCenterX;
            private float startScreenCenterY;
            private float startScreenDistance;
            private float startScreenAngle;
            private float cameraStartTargetX;
            private float cameraStartTargetY;

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

            @Override
            public void render(UIContext context)
            {
                this.pollViewportInput(context);
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

                    /* Say which view this is: "dragging does nothing" was really "this is the 2D
                     * editor, not the 3D workspace". */
                    context.batcher.textShadow("2D 编辑视图 · 右键拖动进入 3D 视口 · 选中摄像机后 Alt+拖动 = 环绕",
                        this.area.ex() - 340, this.area.ey() - 16, 0x77B9C6DA);
                }

                this.updateCursor(context);
                super.render(context);
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
                     * paused: scrubbing the timeline must move the film frame too. */
                    dev.bbsae.client.overlay.BbsPreviewBridge.sync(UICompositorPanel.this.time);

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
                    UICompositorPanel.this.previewInfo.setVisible(!UICompositorPanel.this.is3DSpace());
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
                    || !UICompositorPanel.this.project.settings.showSafeArea)
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
                if (UICompositorPanel.this.doc == null || UICompositorPanel.this.project == null)
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

                    for (CompLayer layer : UICompositorPanel.this.getSelectedLayers())
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

                        int color = layer.locked ? 0xFF8A6A6A : 0xFF55AAFF;

                        for (int i = 0; i < 4; i++)
                        {
                            int j = (i + 1) % 4;

                            this.line(context, quad[i * 2], quad[i * 2 + 1], quad[j * 2], quad[j * 2 + 1], color);
                        }

                        for (int i = 0; i < 4; i++)
                        {
                            context.batcher.box(quad[i * 2] - 2F, quad[i * 2 + 1] - 2F,
                                quad[i * 2] + 2F, quad[i * 2 + 1] + 2F, color);
                        }
                    }

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
                context.batcher.textShadow("看穿摄像机 · 最终画面", x + 8F, y + h - 26F, 0xFFFFB4B4);
                context.batcher.textShadow("按 0 回到自由视图", x + 8F, y + h - 14F, 0x99FFB4B4);
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
                String label = String.format(java.util.Locale.ROOT, "🎥 %s  %.0f, %.0f, %.0f",
                    UICompositorPanel.this.layerLabel(layer), eye.x, eye.y, eye.z);

                context.batcher.textShadow(label, ex + 12F, ey - 12F, color);
            }

            /** Bottom-left HUD: mode, camera pose and the navigation keys. */
            private void renderViewportHud(UIContext context, CompDocument doc, CompLayer cameraLayer)
            {
                dev.bbsae.client.render.CompViewportCamera camera = UICompositorPanel.this.viewportCamera;
                int x = this.area.x + 8;
                int y = this.area.ey() - 46;

                String mode = "3D 空间 · 草稿（效果/蒙版跳过）" + (camera.ortho ? " · 正交" : " · 透视");
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

                String hint = "右键拖动环绕 · Shift+右键平移 · 滚轮缩放 · 0 看穿 · 5 正交 · Ctrl+Home 全览";

                context.batcher.box(x - 4F, y - 4F, this.area.ex() - 8F, y + 40F, 0x990A0D12);
                context.batcher.textShadow(mode, x, y, 0xFFB9C6DA);
                context.batcher.textShadow(pose, x, y + 13, 0xFFFFC14D);
                context.batcher.textShadow(hint, x, y + 26, 0xFF7E8BA0);
            }

            private void renderSelection(UIContext context)
            {
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

                    context.batcher.box(x - 5F, y - 5F, x + 5F, y + 5F, 0xFF101418);
                    context.batcher.box(x - 4F, y - 4F, x + 4F, y + 4F, 0xFFFFFFFF);
                }

                for (int i = 0; i < 4; i++)
                {
                    int j = (i + 1) % 4;
                    float mx = (corners[i * 2] + corners[j * 2]) * 0.5F;
                    float my = (corners[i * 2 + 1] + corners[j * 2 + 1]) * 0.5F;

                    context.batcher.box(mx - 3F, my - 3F, mx + 3F, my + 3F, 0xFF101418);
                    context.batcher.box(mx - 2F, my - 2F, mx + 2F, my + 2F, 0xFFFFFFFF);
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
                context.batcher.box(hx - 4F, hy - 4F, hx + 4F, hy + 4F, 0xFF101418);
                context.batcher.box(hx - 3F, hy - 3F, hx + 3F, hy + 3F, 0xFFFFD27A);
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

            /** 10 + corner, 20 + edge, 30 = rotate handle. */
            private int handleAt(UIContext context, CompLayer layer)
            {
                if (layer == null || layer.locked)
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
                    Matrix4f world = CompRenderer.worldMatrix(UICompositorPanel.this.doc, layer, renderContext, new java.util.HashSet<>());
                    Vector3f point = new Vector3f(compX, compY, 0F);

                    new Matrix4f(world).invert().transformPosition(point);

                    return new float[] {point.x, point.y};
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
            private float[] startLocalPoint(UIContext context)
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
                Vector3f point = CompScene.camera(renderContext).unproject(
                    screenX, screenY, UICompositorPanel.this.doc.width, UICompositorPanel.this.doc.height, 0F
                );

                this.startInverse.transformPosition(point);

                return new float[] {point.x, point.y};
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
            private void line(UIContext context, float x1, float y1, float x2, float y2, int color)
            {
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
                context.batcher.box(0F, -0.75F, length, 0.75F, color);
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

            private void applyDrag(UIContext context)
            {
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
                float[] local = this.startLocalPoint(context);

                if (this.dragMode == DRAG_CAMERA_BODY || this.dragMode == DRAG_CAMERA_TARGET)
                {
                    this.dragCamera(context, layer, scale);
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
                    }
                    else
                    {
                        float ratio = Math.max(0.01F, this.startScreenDistance <= 0.001F ? 1F : distance / this.startScreenDistance);
                        float sx = Math.max(0.01F, Math.min(100F, this.startScaleX * ratio));
                        float sy = Math.max(0.01F, Math.min(100F, this.startScaleY * ratio));

                        this.writeTransform(layer, sx, sy, null);
                        UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT, "缩放 X %.0f%%   Y %.0f%%", sx * 100F, sy * 100F));
                    }

                    return;
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
                    float sx = Math.abs(this.startLocalX) < 0.001F ? this.startScaleX : this.startScaleX * (local[0] / this.startLocalX);
                    float sy = Math.abs(this.startLocalY) < 0.001F ? this.startScaleY : this.startScaleY * (local[1] / this.startLocalY);

                    if (this.dragMode == DRAG_CORNER && !Window.isShiftPressed())
                    {
                        /* Corner = proportional scale (AE). Shift frees the axes, edge handles
                         * are always single-axis. */
                        float uniform = (sx / Math.max(0.0001F, this.startScaleX) + sy / Math.max(0.0001F, this.startScaleY)) * 0.5F;

                        sx = this.startScaleX * uniform;
                        sy = this.startScaleY * uniform;
                    }
                    else if (this.dragMode == DRAG_EDGE)
                    {
                        if (this.dragHandle == 20 || this.dragHandle == 22)
                        {
                            sy = this.startScaleY;
                        }
                        else
                        {
                            sx = this.startScaleX;
                        }
                    }

                    sx = Math.max(0.01F, Math.min(100F, sx));
                    sy = Math.max(0.01F, Math.min(100F, sy));
                    this.writeTransform(layer, sx, sy, null);
                    UICompositorPanel.this.setStatus(String.format(java.util.Locale.ROOT,
                        "缩放 X %.0f%%   Y %.0f%%%s", sx * 100F, sy * 100F,
                        this.dragMode == DRAG_CORNER ? "（Shift 单轴）" : ""));

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
                /* Screen -> composition space. With a panned/zoomed camera the on-screen position
                 * of a 2D layer is not its comp position, so the hit test has to undo the view. */
                org.joml.Vector3f compPoint = camera.unproject(screenX, screenY, doc.width, doc.height, 0F);
                float compX = compPoint.x;
                float compY = compPoint.y;

                for (int i = doc.layers.size() - 1; i >= 0; i--)
                {
                    CompLayer layer = doc.layers.get(i);

                    if (!layer.visible || !layer.activeAt(UICompositorPanel.this.time))
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

                    Matrix4f world;

                    try
                    {
                        world = CompRenderer.worldMatrix(doc, layer, renderContext, new java.util.HashSet<>());
                    }
                    catch (RuntimeException e)
                    {
                        continue;
                    }

                    Vector3f point = new Vector3f(compX, compY, 0F);

                    new Matrix4f(world).invert().transformPosition(point);

                    float bw;
                    float bh;
                    float bx;
                    float by;

                    if ("shape".equals(layer.type))
                    {
                        dev.bbsae.client.render.ShapeCache.Raster raster = dev.bbsae.client.render.ShapeCache.get(layer, renderContext.frame);

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

                    if (point.x >= bx && point.x <= bx + bw && point.y >= by && point.y <= by + bh)
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

                if (!UICompositorPanel.this.is3DSpace() && context.mouseButton == 1
                    && !Boolean.getBoolean("bbsae.debugViewLock"))
                {
                    /* Right-drag is the orbit gesture everywhere in the preview. In the 2D editor it
                     * enters the 3D workspace first, so "drag to look around" can never silently do
                     * nothing because the mode was not what the user assumed. */
                    UICompositorPanel.this.enter3DSpace();
                    this.beginViewportDrag(context, CompInput.shiftDown() ? VP_PAN : VP_ORBIT, 1);
                    UICompositorPanel.this.setStatus("已进入 3D 视口：拖动即可环绕");

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
            private boolean viewportMouseClicked(UIContext context)
            {
                if (context.mouseButton != 0 || UICompositorPanel.this.doc == null)
                {
                    return super.subMouseClicked(context);
                }

                CompDocument doc = UICompositorPanel.this.doc;
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

                try
                {
                    Matrix4f world = CompRenderer.worldMatrix(UICompositorPanel.this.doc, layer, renderContext, new java.util.HashSet<>());

                    this.startInverse.set(world).invert();
                }
                catch (RuntimeException e)
                {
                    this.startInverse.identity();
                }

                float[] local = this.localPoint(context, layer);

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
                    : "拖动缩放；角点默认等比，Shift 可单独改 X/Y");
            }

            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                this.endViewportDrag();
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
        this.encoderBadge.relative(this.preview).x(1F, -126).y(6).w(120).h(24);
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
                this.save();
            }
        });
        this.bbsBackdropToggle.relative(this.previewBar).x(124).y(1).w(58).h(18);
        this.bbsBackdropToggle.tooltip(IKey.constant("在合成后面显示 BBS 实时画面"));

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
        this.previewCollapse.relative(this.previewBar).x(176).y(1).w(20).h(18);

        this.previewBar.add(this.effectsToggle, this.masksToggle, this.bbsBackdropToggle, this.previewCollapse);
        this.applyPreviewBarState();
        this.preview.add(this.previewBar);

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

        this.previewInfo = new UILabel(IKey.constant("预览"));
        this.previewInfo.relative(this.preview).x(6).y(1F, -18).w(1F, -12).h(14);
        this.previewInfo.color(0xFF9AA6B8, false);
        this.previewInfo.labelAnchor(0F, 0.5F);
        this.preview.add(this.previewInfo);

        this.inspectorColumn = new UIElement();
        this.inspectorColumn.w(300).h(1F);

        this.inspectorTabs = new UICompTabBar();
        this.inspectorTabs.relative(this.inspectorColumn).x(0).y(0).w(1F).h(22);
        this.inspectorTabs.addTab("效果控件");
        this.inspectorTabs.addTab("调色");
        this.inspectorTabs.addTab("图层");
        this.inspectorTabs.addTab("蒙版");
        this.inspectorTabs.addTab("文字");
        this.inspectorTabs.addTab("合成");
        this.inspectorTabs.addTab("输出");
        this.inspectorTabs.onSelect((index) ->
        {
            this.inspectorPage = index;
            this.refreshInspector();
        });
        this.inspectorTabs.active(() -> this.inspectorPage);

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
        this.keyframeEditor.setUndoId("bbsae_compositor_timeline");
        this.keyframeEditor.view.resetView();

        this.keyframeDrawer = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF151A22);
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, 0xFF2A303A);
                super.render(context);
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

        this.keyframeEditor.relative(this.keyframeDrawer).x(0).y(22).w(1F).h(1F, -22);

        this.keyframeDrawer.add(this.keyframeTitle, this.keyframeCurve, prevKey, addKey, nextKey, fitKeys, closeKeyframes, this.keyframeEditor);

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
            java.util.List.of("review_h264", "master_prores", "alpha_prores", "png_sequence", "audio_wav", "audio_m4a"),
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
            if (this.project != null && this.selectedJob != null)
            {
                CompExportManager.start(this.project, this.selectedJob);
            }
        });
        this.queueStartButton.w(56);
        this.queueStartAll = new UICompButton(IKey.constant("全部开始"), (button) ->
        {
            if (this.project != null)
            {
                CompExportManager.startQueue(this.project);
            }
        });
        this.queueStartAll.w(68);
        this.queueCancel = new UICompButton(IKey.constant("取消"), (button) -> CompExportManager.cancel());
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

        this.body = new UIElement();
        this.body.add(this.effectColumn, this.preview, this.inspectorColumn);

        this.leftHandle = new PanelResizeHandle(0);
        this.rightHandle = new PanelResizeHandle(1);
        this.timelineHandle = new PanelResizeHandle(2);
        this.body.add(this.leftHandle, this.rightHandle, this.timelineHandle);
        this.body.relative(this).x(0).y(24).w(1F).h(1F, -292);

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
        this.trackTimeline.relative(this.timeline).x(0).y(26).w(1F).h(1F, -26);

        /* The same strip hosts two editors: the track timeline and the AE curve editor. They are
         * mutually exclusive views of the selected layer's animation, never a second source of
         * keyframes - both edit the very same BBS channels. */
        this.curveEditor = new UICompCurveEditor(this);
        this.curveEditor.relative(this.timeline).x(0).y(26).w(1F).h(1F, -26);
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
        this.timelineHeader.relative(this.timeline).x(0).y(0).w(1F).h(26);

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
            this.applyTimelinePage(grow);
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

        this.add(this.header, this.body, this.renderPanel, this.timeline, this.quickPanel, this.status,
            this.modalLayer, this.startScreen, this.onboarding, this.settingsOverlay);

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
            this.flushSave();
            CompPrefs.flush();

            if (this.settingsOverlay != null)
            {
                this.settingsOverlay.close();
            }

            dev.bbsae.client.overlay.BbsPreviewBridge.detach();
        });
        this.onDisappear(() ->
        {
            this.lastPlayNanos = 0L;
            this.keyframeEditor.view.pickKeyframe(null);
            this.flushSave();
            CompPrefs.flush();

            if (this.settingsOverlay != null)
            {
                this.settingsOverlay.close();
            }

            dev.bbsae.client.render.CompPrecache.stop();
            dev.bbsae.client.overlay.BbsPreviewBridge.detach();
        });
    }

    /* Film-scoped lifecycle */

    /**
     * A compositor belongs to exactly one BBS film (replay project). This is the only entry
     * point that gives the panel data; the dashboard tab alone deliberately has no project.
     */
    public void bind(mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel, mchorse.bbs_mod.film.Film film, mchorse.bbs_mod.film.replays.Replay replay)
    {        if (film == null)
        {
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

        CompProject project = CompStore.load(info.id);

        if (project == null)
        {
            /* Do not leave the user on an editor with no project behind it. */
            this.startScreenOpen = true;
            this.startScreen.setVisible(true);
            this.setStatus("工程加载失败：" + info.id);

            return;
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
        this.save();
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

        this.save();
        this.hideStartScreen();
        this.precompStack.clear();

        if (this.precompBack != null)
        {
            this.precompBack.setVisible(false);
        }

        this.filmPanel = filmPanel;
        this.film = film;
        this.replay = null;

        CompProject requested = projectId == null || projectId.isBlank() ? null : CompStore.load(projectId);

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

        mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<String> encoder =
            new mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton<>(
                java.util.List.of(CompProject.ENCODER_BBS, CompProject.ENCODER_AE),
                (value) -> CompProject.ENCODER_AE.equals(value) ? Icons.SERVER : Icons.FILM,
                (value) -> IKey.constant(CompProject.ENCODER_AE.equals(value) ? "AE 编码器（帧服务器）" : "BBS 原生（默认）"));

        encoder.callback(this::requestEncoder);
        encoder.setValue(this.currentEncoder());
        encoder.relative(this.modalBox).x(190).y(396).w(190).h(20);
        encoder.tooltip(IKey.constant("BBS：合成是影片叠加层，BBS 录像器导出。AE：合成自带帧服务器，预览与导出逐帧一致（有风险，首次切换会提示）"));

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
            new String[] {"ttf", "otf"},
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
            new String[] {"ttf", "otf"},
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

    private void importFontFile(CompLayer layer, java.io.File file)
    {
        try
        {
            java.io.File folder = mchorse.bbs_mod.fonts.FontManager.getFolder();

            java.nio.file.Files.createDirectories(folder.toPath());

            String name = file.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
            String lower = name.toLowerCase(java.util.Locale.ROOT);

            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf"))
            {
                name = name + ".ttf";
            }

            java.nio.file.Path target = folder.toPath().resolve(name);

            if (!target.toAbsolutePath().equals(file.toPath().toAbsolutePath()))
            {
                java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            layer.fontName = name;
            this.refreshInspector();
            this.save();
            this.setStatus("已导入字体：" + name);
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
        if (this.importBrowser != null)
        {
            this.importBrowser.removeFromParent();
            this.importBrowser = null;
        }
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

    /** Writes now. Called on panel close, before export, and by the debounce timer. */
    void flushSave()
    {
        if (!this.saveRequested)
        {
            return;
        }

        this.saveRequested = false;

        if (this.project != null)
        {
            CompStore.save(this.project);
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

            if (grow && this.area.h > 0)
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

            case "onboarding":
                this.openOnboarding();
                return;

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

            case "quick":
                this.debugQuick3D();
                break;

            case "queue":
                this.toggleRenderView();
                break;

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
            this.inspectorPage = PAGE_LAYER;
            this.selectLayer(layer);
            this.refreshInspector();
            this.setStatus("调试：回放实体图层 · 本帧抠出 " + dev.bbsae.client.render.ReplayEntityMask.entityCount() + " 个实体");
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

                CompProject debugProject = CompStore.load(projectId);
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

    /** Ctrl+click: add or remove one layer without disturbing the rest. */
    public void toggleLayerSelection(CompLayer layer)
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
    }

    public void clearSelection()
    {
        if (this.selectedLayer == null && this.selectedIds.isEmpty())
        {
            return;
        }

        this.selectedIds.clear();

        this.selectedLayer = null;

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
        copy.x += 24F;
        copy.y += 24F;
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

        this.beginAction("调整图层顺序");

        if (!this.doc.moveLayerToDisplayIndex(layer, displayIndex))
        {
            return;
        }

        this.selectedLayer = layer;
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

        this.beginAction("移动图层顺序");
        int index = this.doc.layers.indexOf(layer);
        int target = Math.max(0, Math.min(this.doc.layers.size() - 1, index + direction));

        if (index < 0 || target == index)
        {
            return;
        }

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
        this.leftPage = 0;
        this.leftTabs.select(0);
        this.effectLibrary.setVisible(true);
        this.assetPanel.setVisible(false);
        this.setStatus("在左侧效果库中双击效果，或选中后点“应用效果”");
    }

    public void setInspectorPage(int page)
    {
        this.inspectorTabs.select(page);
    }

    public void openKeyframes()
    {
        this.setKeyframeOpen(true);
    }

    public void toggleKeyframes()
    {
        this.setKeyframeOpen(!this.keyframeOpen);
    }

    private void setKeyframeOpen(boolean open)
    {
        if (this.keyframeOpen == open)
        {
            return;
        }

        this.keyframeOpen = open;
        this.keyframeDrawer.setVisible(open);

        if (!open)
        {
            /* The keyframe properties panel is parented to the inspector, not to the drawer.
             * Hiding the drawer used to leave that panel covering Effect Controls with no way
             * to dismiss it. Clearing the picked keyframe removes it with the drawer. */
            this.keyframeEditor.view.pickKeyframe(null);
        }

        this.layoutKeyframeDrawer();

        if (open)
        {
            this.primedWidth = -1;
            this.keyframeEditor.resize();
            this.primeTimeline();
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

    private void layoutKeyframeDrawer()
    {
        if (this.timeline == null || this.trackTimeline == null || this.keyframeHandle == null)
        {
            return;
        }

        /* The curve editor owns the whole strip; the keyframe drawer belongs to the track view. */
        if (this.timelinePage != 0)
        {
            if (this.curveEditor != null)
            {
                this.curveEditor.relative(this.timeline).x(0).y(26).w(1F).h(1F, -26);
            }

            this.keyframeDrawer.setVisible(false);
            this.keyframeHandle.setVisible(false);

            return;
        }

        int max = Math.max(KEYFRAME_DRAWER_MIN, this.timelinePanelHeight - 96);

        this.keyframeDrawerHeight = Math.max(KEYFRAME_DRAWER_MIN, Math.min(max, this.keyframeDrawerHeight));

        if (this.keyframeOpen)
        {
            this.trackTimeline.relative(this.timeline).x(0).y(26).w(1F).h(1F, -26 - this.keyframeDrawerHeight);
            this.keyframeDrawer.relative(this.timeline).x(0).y(1F, -this.keyframeDrawerHeight).w(1F).h(this.keyframeDrawerHeight);
            this.keyframeHandle.relative(this.timeline).x(0).y(1F, -this.keyframeDrawerHeight - 2).w(1F).h(5);
            this.keyframeHandle.setVisible(true);
        }
        else
        {
            this.trackTimeline.relative(this.timeline).x(0).y(26).w(1F).h(1F, -26);
            this.keyframeHandle.setVisible(false);
        }

        this.keyframeEditor.relative(this.keyframeDrawer).x(0).y(22).w(1F).h(1F, -22);
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
        this.effectColumn.setVisible(!this.focusMode && !this.renderView);
        this.inspectorColumn.setVisible(!this.focusMode && !this.renderView);
        this.applyLayout();
    }

    /** Absolute three-column layout; the old row(6) divided the width equally and ignored w(). */
    private void applyLayout()
    {
        if (this.body == null || this.preview == null || this.timeline == null)
        {
            return;
        }

        boolean left = this.effectColumn.isVisible();
        boolean right = this.inspectorColumn.isVisible();
        int lw = left ? this.leftPanelWidth : 0;
        int rw = right ? this.rightPanelWidth : 0;
        int gapL = left ? 6 : 0;
        int gapR = right ? 6 : 0;

        /* Keep the preview and the timeline from colliding on a short window. Without this the
         * body's 160 px minimum ran underneath the timeline as soon as the panel was < 430 px. */
        if (this.area.h > 200 && this.area.w > 300)
        {
            int maxTimeline = Math.max(140, this.area.h - 24 - 160 - 18);
            int maxSide = Math.max(160, this.area.w - 220 - 80);

            this.timelinePanelHeight = Math.min(this.timelinePanelHeight, maxTimeline);
            this.leftPanelWidth = Math.min(this.leftPanelWidth, maxSide);
            this.rightPanelWidth = Math.min(this.rightPanelWidth, maxSide);
        }

        /* Second pass: a side-by-side layout that leaves no room for the picture is not a layout.
         * The two clamps above cap each column independently, so at ~360 logical pixels the preview
         * was already negative and simply stopped rendering — the panel looked empty even though
         * every widget was there. Shrink both columns proportionally until the preview has a floor. */
        int minPreview = 160;

        if (this.area.w > 0 && lw + rw + gapL + gapR + minPreview > this.area.w)
        {
            int available = Math.max(0, this.area.w - gapL - gapR - minPreview);
            int total = lw + rw;

            if (total > 0)
            {
                lw = Math.max(0, lw * available / total);
                rw = Math.max(0, available - lw);
            }
        }

        this.effectColumn.relative(this.body).x(0).y(0).w(lw).h(1F);
        this.preview.relative(this.body).x(lw + gapL).y(0).w(1F, -(lw + rw + gapL + gapR)).h(1F);
        this.inspectorColumn.relative(this.body).x(1F, -rw).y(0).w(rw).h(1F);

        int bodyHeight = Math.max(160, this.area.h - 24 - this.timelinePanelHeight - 18);

        this.body.relative(this).x(0).y(24).w(1F).h(bodyHeight);
        boolean quick = this.quickPanel != null && this.quickPanel.isVisible();
        int quickWidth = quick ? Math.max(160, Math.min(340, this.quickPanelWidth)) : 0;

        this.timeline.relative(this).x(0).y(1F, -(this.timelinePanelHeight + 18))
            .w(1F, -quickWidth).h(this.timelinePanelHeight);

        if (quick)
        {
            this.quickPanel.relative(this).x(1F, -quickWidth).y(1F, -(this.timelinePanelHeight + 18))
                .w(quickWidth).h(this.timelinePanelHeight);
        }
        this.renderPanel.relative(this).x(0).y(24).w(1F).h(bodyHeight);

        if (this.leftHandle != null)
        {
            this.leftHandle.relative(this.body).x(lw + 2).y(0).w(4).h(1F);
            this.leftHandle.setVisible(left);
            this.rightHandle.relative(this.body).x(1F, -rw - 6).y(0).w(4).h(1F);
            this.rightHandle.setVisible(right);
            this.timelineHandle.relative(this.body).x(0).y(1F, -4).w(1F).h(4);
            this.timelineHandle.setVisible(true);
        }

        this.layoutKeyframeDrawer();
        this.resize();
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
        this.saveViewportPose();
        this.save();
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

        this.inspector.column(CompUi.gap()).padding(CompUi.gap());
        this.applyLayout();
        this.applyTimelinePage();
    }

    /** A 4px splitter. Drag updates one of the three layout sizes and persists on release. */
    private class PanelResizeHandle extends UIElement
    {
        private final int mode;
        private boolean dragging;
        private int startX;
        private int startY;
        private int startLeft;
        private int startRight;
        private int startHeight;

        PanelResizeHandle(int mode)
        {
            this.mode = mode;
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
            int width = UICompositorPanel.this.body.area.w;
            int height = UICompositorPanel.this.area.h;

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
                int max = Math.max(KEYFRAME_DRAWER_MIN, UICompositorPanel.this.timelinePanelHeight - 96);

                UICompositorPanel.this.keyframeDrawerHeight = Math.max(KEYFRAME_DRAWER_MIN, Math.min(max, this.startHeight - dy));
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
            if (context.mouseButton == 0 && this.area.isInside(context))
            {
                this.dragging = true;
                this.startX = context.mouseX;
                this.startY = context.mouseY;
                this.startLeft = UICompositorPanel.this.leftPanelWidth;
                this.startRight = UICompositorPanel.this.rightPanelWidth;
                this.startHeight = this.mode == 3
                    ? UICompositorPanel.this.keyframeDrawerHeight
                    : UICompositorPanel.this.timelinePanelHeight;

                return true;
            }

            return super.subMouseClicked(context);
        }
    }

    /* Layer actions */

    private void addMask(CompLayer layer, boolean ellipse)
    {
        this.beginAction("添加蒙版");
        CompMask mask = new CompMask("mask_" + (layer.masks.size() + 1));

        mask.name = ellipse ? "Ellipse Mask" : "Rectangle Mask";

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

    private void duplicateSelected()
    {
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

            this.doc.audio.remove(clip);
            this.selectedAudio = null;
            dev.bbsae.client.audio.CompAudioPlayer.invalidate();
            this.refreshInspector();
            this.save();
            this.setStatus("已移除音频：" + clip.name);

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

        CompLayer camera = this.doc.activeCamera(this.time);

        if (camera == null)
        {
            this.addLayer("camera");
            camera = this.selectedLayer;
        }

        if (camera == null)
        {
            return;
        }

        this.doc.activeCameraId = camera.id;
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

        this.selectLayer(camera);
        this.refreshInspector();
        this.savePanelLayout();
        this.setStatus("3D 空间：右键拖动环绕（中键同效）· Shift+拖动平移 · 滚轮缩放 · 0 看穿摄像机 · 1/3/7 前/右/顶 · 5 正交");
    }

    /** True when the free 3D viewport (not the shot camera view) is the active preview. */
    private boolean is3DSpace()
    {
        return this.viewportMode;
    }

    public void exit3DSpace()
    {
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
            case PAGE_EFFECTS -> this.addEffectControls();
            case PAGE_GRADE -> this.inspector.add(this.gradeSection(this.selectedLayer));
            case PAGE_LAYER ->
            {
                this.inspector.add(this.layerSection());
                this.inspector.add(this.bindingSection(this.selectedLayer));
                this.inspector.add(this.layerMarkerSection(this.selectedLayer));
                this.inspector.add(this.animationPresetSection(this.selectedLayer));

                if (this.selectedLayer != null && "shape".equals(this.selectedLayer.type))
                {
                    this.inspector.add(this.shapeSection(this.selectedLayer));
                }
            }
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
            default -> this.addEffectControls();
        }

        this.inspector.resize();
        this.buildingInspector = false;
        this.lastInspectorTime = this.time;
        this.updateToolbarLabels();

        if (this.curveEditor != null)
        {
            this.curveEditor.refresh();
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

    private void addEffectControls()
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
        this.inspector.add(this.fragmentSection(layer));

        if ("camera".equals(layer.type))
        {
            this.inspector.add(this.cameraSection(layer));
            return;
        }

        if ("adjustment".equals(layer.type))
        {
            int below = this.doc == null ? 0 : Math.max(0, this.doc.indexOfLayer(layer.id));

            this.inspector.add(UI.label(IKey.constant("调整层：下面的 " + below + " 个图层会被本层效果处理；本层自身不产生像素。"), 14));
        }

        this.inspector.add(this.motionSection(layer));
        this.inspector.add(this.motionTemplateSection(layer));
        this.inspector.add(this.opacitySection(layer));
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

    private void applyPreviewBarState()
    {
        if (this.previewBar == null || this.effectsToggle == null)
        {
            return;
        }

        boolean collapsed = this.previewControlsCollapsed;

        /* The toggles are views of the settings, not their owners: a project that was saved with
         * the BBS backdrop on must show the switch as on when it is reopened. */
        if (this.project != null && this.project.settings != null)
        {
            this.bbsBackdropToggle.setValue(this.project.settings.previewBackground == 1);
        }

        this.previewBar.w(collapsed ? 24 : 200);
        this.effectsToggle.setVisible(!collapsed);
        this.masksToggle.setVisible(!collapsed);
        this.bbsBackdropToggle.setVisible(!collapsed);
        this.previewCollapse.relative(this.previewBar).x(collapsed ? 2 : 176).y(1).w(20).h(18);
        this.previewBar.resize();
    }

    private UISection quickStartSection()
    {
        UISection section = new UISection(IKey.constant("快速开始"));

        section.fields.add(UI.label(IKey.constant("① 添加或选择一条轨道"), 16));
        section.fields.add(UI.label(IKey.constant("② 在右侧调整运动、不透明度和效果"), 14));
        section.fields.add(UI.label(IKey.constant("③ 在底部时间轴拖动或裁剪片段"), 14));
        section.fields.add(UI.label(IKey.constant("不确定怎么操作？点时间轴右端的 ? 看快捷键。"), 14));

        section.fields.add(new UICompButton(IKey.constant("＋ 新建形状"), (button) -> this.addLayer("shape")));
        section.fields.add(new UICompButton(IKey.constant("＋ 添加 BBS 预览层"), (button) -> this.addLayer("bbs")));
        section.fields.add(new UICompButton(IKey.constant("＋ 添加空对象"), (button) -> this.addLayer("null")));
        section.fields.add(new UICompButton(IKey.constant("打开效果库"), (button) -> this.focusEffectLibrary()));

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
        section.fields.add(UI.label(IKey.constant("深度抠像 · 只保留勾选的回放实体，其余像素透明"), 15));
        section.fields.add(UI.label(IKey.constant("判断依据是“实体深度 = 世界深度”：方块挡在前面时两个深度不同，实体自然被挡住；全程不比较颜色，所以换光影、换材质都不影响。"), 13));

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
        section.fields.add(this.numberRow("深度容差（方块）", layer.entityTolerance, 0.001D, 4D, false, (value) ->
        {
            layer.entityTolerance = (float) Math.max(0.001D, value);
            this.save();
        }));
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

        if (layer.entityOcclusion)
        {
            dev.bbsae.client.render.SceneDepth depth = dev.bbsae.client.overlay.BbsPreviewBridge.depth();

            if (depth == null || !depth.isAvailable())
            {
                status += " · 世界深度不可用，已退化为无遮挡（可在上面关闭遮挡）";
            }
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
        section.fields.add(threeD);

        if (layer.threeD)
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
            layer.x = this.doc.width / 2F;
            layer.y = this.doc.height / 2F;
            layer.rotation = 0F;
            layer.scaleX = 1F;
            layer.scaleY = 1F;
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
            layer.cameraLookAt = true;
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
            layer.cameraLookAt = true;
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

        /* A dropdown instead of a click-to-cycle button: 13 modes are impossible to aim at by
         * clicking through them, and the user needs to see what the modes are called. */
        java.util.List<Integer> blendModes = new java.util.ArrayList<>();

        for (int i = 0; i < 13; i++)
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
        section.fields.add(new UICompButton(IKey.constant("打开效果库（添加）"), (button) -> this.focusEffectLibrary()));

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
                layer.effects.add(CompEffectRegistry.create("color_grade", layer.effects.size() + 1));
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

        section.remember(this.sectionFolds, "fx:" + effect.id, true);

        UIToggle enabled = new UIToggle(IKey.constant("启用"), effect.enabled, (toggle) ->
        {
            effect.enabled = toggle.getValue();
            this.save();
        });
        UIIcon up = new UIIcon(Icons.MOVE_UP, (icon) -> this.moveEffect(layer, effect, -1));
        UIIcon down = new UIIcon(Icons.MOVE_DOWN, (icon) -> this.moveEffect(layer, effect, 1));
        UIIcon remove = new UIIcon(Icons.TRASH, (icon) ->
        {
            this.beginAction("删除效果");
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

        if (definition.params.isEmpty() && definition.colorParams.isEmpty())
        {
            section.fields.add(UI.label(IKey.constant("这个效果没有可调参数。"), 16));
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
        if (layer == null || effect == null || definition == null || definition.params.isEmpty())
        {
            return;
        }

        this.beginAction("添加效果 AE 关键帧");

        float frame = this.getFrame();
        String firstPath = null;

        for (CompEffectRegistry.Param param : definition.params)
        {
            String path = CompLayer.effectPath(effect.id, param.key);

            if (firstPath == null)
            {
                firstPath = path;
            }

            float value = layer.scalarAt(path, (float) effect.value(param.key, param.defaultValue), frame);

            layer.scalarChannel(path).insertInheriting(frame, value);
        }

        this.refreshSheets();
        this.refreshInspector();

        if (firstPath != null)
        {
            this.showCurveFor(firstPath);
        }

        this.setStatus("已为「" + CompEffectRegistry.displayName(effect.type) + "」添加 "
            + definition.params.size() + " 个 AE 关键帧");
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

        section.remember(this.sectionFolds, "style:" + style.type + ":" + layer.styles.indexOf(style), false);

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
                this.project.outputPresets.remove(preset);
                this.presetIndex = Math.min(this.presetIndex, Math.max(0, this.project.outputPresets.size() - 1));
                this.refreshInspector();
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
        section.fields.add(UI.label(IKey.constant("变换、不透明度与效果在“效果控件”页。"), 14));

        UITextbox name = new UITextbox(64, (text) -> l.name = text);

        name.setText(l.name);
        section.fields.add(this.fieldRow("名称", name));

        UIToggle visible = new UIToggle(IKey.constant("可见"), l.visible, (toggle) -> l.visible = toggle.getValue());
        UIToggle locked = new UIToggle(IKey.constant("锁定"), l.locked, (toggle) -> l.locked = toggle.getValue());
        UIToggle solo = new UIToggle(IKey.constant("独显"), l.solo, (toggle) -> l.solo = toggle.getValue());
        UIToggle guide = new UIToggle(IKey.constant("参考层"), l.guide, (toggle) -> l.guide = toggle.getValue());
        section.fields.add(UI.row(visible, locked));
        section.fields.add(UI.row(solo, guide));

        float editFrame = 1F / Math.max(1F, this.doc.fps);

        section.fields.add(this.numberRow("入点", l.in, 0F, 36000F, false, (value) ->
        {
            /* Route this through trimInTo like the timeline and the Clip section do; writing
             * l.in directly skipped the startTime compensation and shifted the source frame. */
            l.trimInTo((float) Math.max(0D, Math.min(l.out - editFrame, value)), this.doc.fps);
            this.commitLayerTiming();
        }));
        section.fields.add(this.numberRow("出点", l.out, 0F, 36000F, false, (value) ->
        {
            l.trimOutTo((float) Math.max(l.in + editFrame, Math.min(this.doc.duration, value)), this.doc.fps, this.doc.duration);
            this.commitLayerTiming();
        }));
        section.fields.add(this.numberRow("源时间偏移", l.startTime, -36000F, 36000F, false, (value) -> l.startTime = value.floatValue()));
        section.fields.add(this.numberRow("时间缩放", l.timeScale, -10F, 10F, false, (value) -> l.timeScale = value.floatValue()));

        UIToggle freeze = new UIToggle(IKey.constant("冻结帧"), l.freeze, (toggle) ->
        {
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
            l.timeRemap = toggle.getValue();
            this.refreshInspector();
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

        UIToggle matteEnabled = new UIToggle(IKey.constant("使用遮罩"), l.trackMatteEnabled, (toggle) -> l.trackMatteEnabled = toggle.getValue());
        UIButton matteMode = new UICompButton(IKey.constant(matteName(l.trackMatteMode)), (button) ->
        {
            l.trackMatteMode = (l.trackMatteMode + 1) % 4;
            button.label = IKey.constant(matteName(l.trackMatteMode));
        });
        section.fields.add(UI.row(matteEnabled, matteMode));

        section.fields.add(this.textRow("表达式：位置 X", l.expressionX, (text) -> l.expressionX = text));
        section.fields.add(this.textRow("表达式：位置 Y", l.expressionY, (text) -> l.expressionY = text));
        section.fields.add(this.textRow("表达式：旋转", l.expressionRotation, (text) -> l.expressionRotation = text));
        section.fields.add(this.textRow("表达式：不透明度", l.expressionOpacity, (text) -> l.expressionOpacity = text));

        if ("image".equals(l.type) || "footage".equals(l.type))
        {
            section.fields.add(this.textRow("序列".equals(l.type) ? "图片序列 (%04d)" : "图片路径", l.imagePath, (text) -> l.imagePath = text));
            section.fields.add(this.smallButton("导入图片…", () -> this.importImage(l)));
            section.fields.add(this.numberRow("图片适配（0 包含 1 覆盖 2 拉伸）", l.imageFit, 0, 2, true, (value) -> l.imageFit = value.intValue()));
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

        UIToggle enabled = new UIToggle(IKey.constant("启用"), binding.enabled, (toggle) -> binding.enabled = toggle.getValue());
        UIButton type = new UICompButton(IKey.constant(binding.type), (button) ->
        {
            binding.type = switch (binding.type)
            {
                case CompBinding.CAMERA -> CompBinding.REPLAY;
                case CompBinding.REPLAY -> CompBinding.LAYER;
                case CompBinding.LAYER -> CompBinding.SCREEN;
                default -> CompBinding.CAMERA;
            };
            button.label = IKey.constant(binding.type);
        });
        section.fields.add(UI.row(enabled, type));

        UITextbox target = new UITextbox(64, (text) -> binding.target = text);

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
        UITextbox text = new UITextbox(2048, (value) -> layer.text = value);

        text.setText(layer.text);
        section.fields.add(text);

        java.util.List<String> fontLabels = new java.util.ArrayList<>();
        java.util.Map<String, String> fontFiles = new java.util.LinkedHashMap<>();

        fontLabels.add("默认（BBS）");
        fontFiles.put("默认（BBS）", "");

        for (mchorse.bbs_mod.resources.Link link : mchorse.bbs_mod.fonts.FontManager.getFontLinks())
        {
            String name = link.path.substring(link.path.lastIndexOf('/') + 1);

            fontLabels.add(name);
            fontFiles.put(name, name);
        }

        String currentFont = layer.fontName == null || layer.fontName.isBlank() ? "默认（BBS）" : layer.fontName;
        UIChoiceButton<String> fontChoice = new UIChoiceButton<>(fontLabels, (value) -> Icons.FONT, IKey::constant);

        fontChoice.setValue(fontLabels.contains(currentFont) ? currentFont : "默认（BBS）");
        fontChoice.callback((value) ->
        {
            layer.fontName = fontFiles.getOrDefault(value, "");
            this.save();
        });
        section.fields.add(this.fieldRow("字体", fontChoice));
        section.fields.add(this.smallButton("导入字体（ttf / otf）…", () -> this.importFont(layer)));

        section.fields.add(this.animatedNumberRow("字号", layer, "text/fontSize", layer.fontSizeAt(this.getFrame()), (value) -> layer.fontSize = (float) Math.max(1D, value), 1F, 2000F));
        section.fields.add(this.animatedNumberRow("字距", layer, "text/tracking", layer.trackingAt(this.getFrame()), (value) -> layer.tracking = value.floatValue(), -200F, 500F));
        section.fields.add(this.animatedNumberRow("行高", layer, "text/lineHeight", layer.lineHeightAt(this.getFrame()), (value) -> layer.lineHeight = (float) Math.max(0.1D, value), 0.1F, 5F));
        section.fields.add(this.colorRow("颜色", layer.color, (color) -> layer.color = color));
        section.fields.add(this.colorRow("描边", layer.strokeColor, (color) -> layer.strokeColor = color));
        section.fields.add(this.animatedNumberRow("描边宽度", layer, "text/strokeWidth", layer.textStrokeAt(this.getFrame()), (value) -> layer.strokeWidth = (float) Math.max(0D, value), 0F, 40F));
        section.fields.add(this.numberRow("对齐（0 左 1 中 2 右）", layer.align, 0, 2, true, (value) -> layer.align = value.intValue()));
        section.fields.add(new UIToggle(IKey.constant("自动换行"), layer.wrap, (toggle) -> layer.wrap = toggle.getValue()));
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
            section.fields.add(new UIToggle(IKey.constant("启用"), animator.enabled, (toggle) -> animator.enabled = toggle.getValue()));
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

                section.fields.add(UI.row(
                    this.smallButton("添加路径点", () ->
                    {
                        shape.points.add(new CompShape.Point(0F, 0F));
                        this.refreshInspector();
                    }),
                    this.smallButton("删除最后一点", () ->
                    {
                        if (!shape.points.isEmpty())
                        {
                            shape.points.remove(shape.points.size() - 1);
                        }
                        this.refreshInspector();
                    })
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
            /* Masks and track mattes are rasterised in composition space; a 3D layer's pixels
             * only exist after the perspective projection. Saying so is better than offering
             * controls that the renderer intentionally skips. */
            section.fields.add(UI.label(IKey.constant("3D 图层暂不支持蒙版/轨道遮罩：蒙版在屏幕空间栅格化，3D 图层在透视投影后才合成。"), 13));
        }

        section.fields.add(this.smallButton("添加矩形蒙版", () -> this.addMask(layer, false)));
        section.fields.add(this.smallButton("添加椭圆蒙版", () -> this.addMask(layer, true)));

        for (CompMask mask : layer.masks)
        {
            section.fields.add(UI.label(IKey.constant(mask.name), 16));

            UIToggle enabled = new UIToggle(IKey.constant("启用"), mask.enabled, (toggle) -> mask.enabled = toggle.getValue());
            UIButton remove = new UICompButton(IKey.constant("移除"), (button) ->
            {
                this.beginAction("删除蒙版");
                layer.masks.remove(mask);
                this.refreshInspector();
            });
            section.fields.add(UI.row(enabled, remove));
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

            UIButton mode = new UICompButton(IKey.constant(maskModeName(mask.mode)), (button) ->
            {
                mask.mode = (mask.mode + 1) % 4;
                button.label = IKey.constant(maskModeName(mask.mode));
            });
            section.fields.add(this.fieldRow("模式", mode));
            section.fields.add(new UIToggle(IKey.constant("反转"), mask.inverted, (toggle) -> mask.inverted = toggle.getValue()));
            section.fields.add(UI.row(
                this.smallButton("添加蒙版点", () ->
                {
                    mask.points.add(new CompMask.CompMaskPoint(layer.width / 2F, layer.height / 2F));
                    this.refreshInspector();
                }),
                this.smallButton("删除最后一点", () ->
                {
                    if (!mask.points.isEmpty())
                    {
                        mask.points.remove(mask.points.size() - 1);
                    }
                    this.refreshInspector();
                })
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
            CompMarker marker = new CompMarker("layer_marker_" + (layer.markers.size() + 1), "Marker", this.time);

            layer.markers.add(marker);
            this.refreshInspector();
        }));

        for (CompMarker marker : layer.markers)
        {
            UITextbox name = new UITextbox(128, (value) -> marker.name = value);

            name.setText(marker.name);
            section.fields.add(this.fieldRow("名称", name));
            section.fields.add(this.numberRow("时间", marker.time, 0F, 36000F, false, (value) -> marker.time = value.floatValue()));

            UIButton remove = new UICompButton(IKey.constant("移除"), (button) ->
            {
                this.beginAction("删除图层标记");
                layer.markers.remove(marker);
                this.refreshInspector();
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
            CompMarker marker = new CompMarker("marker_" + (this.doc.markers.size() + 1), "Marker", this.time);

            this.doc.markers.add(marker);
            this.refreshInspector();
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

    private void refreshSheets()
    {
        this.keyframeEditor.view.removeAllSheets();

        if (this.selectedLayer == null || this.doc == null)
        {
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

        CompRenderJob job = new CompRenderJob(
            "job_" + (this.project.renderQueue.size() + 1),
            this.project.id,
            this.doc.id,
            presetId
        );

        job.outputName = this.queueName.getText().isBlank() ? this.doc.id : this.queueName.getText();
        job.rangeStart = (float) this.queueStart.getValue();
        job.rangeEnd = (float) this.queueEnd.getValue();
        this.project.renderQueue.add(job);
        this.selectedJob = job;
        this.refreshQueue();
        this.save();

        CompOutputPreset preset = this.project.getPreset(presetId);

        this.setStatus("已添加任务：" + job.outputName + " · " + (preset == null ? presetId : preset.name));
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
        try
        {
            java.nio.file.Path path = CompStore.exportRoot(this.project == null ? CompStore.DEFAULT_PROJECT : this.project.id);

            java.nio.file.Files.createDirectories(path);

            if (java.awt.Desktop.isDesktopSupported())
            {
                java.awt.Desktop.getDesktop().open(path.toFile());
            }
        }
        catch (Exception ignored)
        {}
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
    private void beginAction(String name)
    {
        if (this.restoring || this.project == null)
        {
            return;
        }

        this.actionInProgress = true;

        String json = CompStore.snapshot(this.project);

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
    private void captureSnapshot()
    {
        long now = System.currentTimeMillis();

        /* A discrete command just ran. Record its result as the sampler's new baseline, but do
         * not push it: beginAction already pushed the pre-command state. Without this, a value
         * edit made right after the command would be merged into the next undo step. */
        if (this.actionInProgress)
        {
            this.actionInProgress = false;

            if (!this.restoring && this.project != null)
            {
                String json = CompStore.snapshot(this.project);

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

        String json = CompStore.snapshot(this.project);

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

    public void undo()
    {
        if (this.undoStack.isEmpty())
        {
            this.setStatus("没有可撤销的操作");

            return;
        }

        this.pushRedo(CompStore.snapshot(this.project));
        String json = this.undoStack.pop();

        this.undoChars = Math.max(0L, this.undoChars - json.length());
        this.applySnapshot(json);
        this.setStatus("已撤销");
    }

    public void redo()
    {
        if (this.redoStack.isEmpty())
        {
            this.setStatus("没有可重做的操作");

            return;
        }

        this.pushUndo(CompStore.snapshot(this.project));
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

        this.restoring = true;

        try
        {
            this.project = restored;
            this.doc = compId == null ? restored.getCompOrFirst(CompStore.DEFAULT_COMP) : restored.getComp(compId);

            if (this.doc == null)
            {
                this.doc = restored.getCompOrFirst(CompStore.DEFAULT_COMP);
            }

            this.setSingleSelection(null);

            if (layerId != null && this.doc != null)
            {
                this.setSingleSelection(this.doc.getLayer(layerId));
            }

            if (this.selectedLayer == null && this.doc != null && !this.doc.layers.isEmpty())
            {
                this.setSingleSelection(this.doc.layers.get(this.doc.layers.size() - 1));
            }

            this.lastSnapshot = json;
            this.primedWidth = -1;

            /* The restored project is a new object graph. The old precomp stack pointed at
             * documents that no longer belong to it, so restoring while inside a nested comp
             * would edit orphan objects. Keep the current comp, drop the stale breadcrumb. */
            this.precompStack.clear();

            if (this.precompBack != null)
            {
                this.precompBack.setVisible(false);
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
            /* The bridge caches the document it renders; without this an undo leaves the BBS
             * preview/backdrop pointing at the pre-undo object graph. */
            dev.bbsae.client.overlay.BbsPreviewBridge.updateDocument(this.doc);
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
            case "file.save" -> { this.save(); this.setStatus("工程已保存"); return true; }
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
        this.keyframeEditor.view.pickKeyframe(target);
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
        this.keyframeEditor.view.pickKeyframe(target);
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
        boolean wantQuick = this.selectedLayer != null && !this.renderView && !this.focusMode;

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

        this.syncPreviewAudio();

        /* Opaque backdrop, unconditionally.
         *
         * The dashboard draws its own background from BBSSettings, which is intentionally a
         * translucent dark layer over the (blurred) world so BBS panels feel like an overlay. That
         * is wrong for an editor: a compositor UI with the game shining through makes every label
         * harder to read and makes the preview look like it is floating. Painting the panel's own
         * rect here means "no transparency" does not depend on a global setting the user cannot
         * find. */
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF0E1116);

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

        /* Pre-render job: a couple of frames per display frame, so the editor stays responsive
         * while the ruler fills up behind the playhead. */
        if (dev.bbsae.client.render.CompPrecache.isRunning())
        {
            dev.bbsae.client.render.CompPrecache.tick(this.project, this.doc);
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

                int cached = dev.bbsae.client.render.CompPrecache.cachedCount(this.project, this.doc);
                String cachedLabel = cached > 0 ? "  ·  " + cached + " 帧缓存" : "";

                this.previewInfo.label = IKey.constant(this.doc.width + "x" + this.doc.height + "  " + (int) this.doc.fps + "fps  " + effects + " FX" + cachedLabel);
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

        stack.push();
        stack.translate(0F, (1F - entrance) * 6F, 0F);

        try
        {
            super.render(context);
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
}
