package dev.bbsae.client.ui;

import dev.bbsae.client.export.CompExportManager;
import dev.bbsae.client.render.CompRenderer;
import dev.bbsae.comp.CompBinding;
import dev.bbsae.comp.CompAnimationPreset;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMarker;
import dev.bbsae.comp.CompMask;
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
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.UITabStrip;
import mchorse.bbs_mod.ui.framework.elements.utils.UITextTab;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
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
    private final UILabel info;
    private final UILabel timecode;
    private final UILabel status;
    private final UILabel renderStatus;

    private final UIButton save;
    private final UIButton play;
    private final UIButton renderMode;
    private final UIButton exportFilm;
    private final UIButton replaySelector;
    private final UIIcon focusButton;
    private final UIIcon prevFrame;
    private final UIIcon nextFrame;
    private final UIIcon addLayer;

    private final UIElement header;
    private final UIElement body;
    private final UIElement timeline;
    private final UIElement timelineHeader;
    private final UICompositorScrubber scrubber;
    private final UICompLayerList layers;
    private final UIElement preview;
    private final UIElement previewBar;
    private final UILabel previewInfo;
    private final UIElement inspectorColumn;
    private final UITabStrip inspectorTabs;
    private final UIScrollView inspector;
    private final UIKeyframeEditor keyframeEditor;
    private final UIElement renderPanel;

    private final UICompRenderJobList queue;
    private final UITextbox queuePreset;
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
    private CompRenderJob selectedJob;
    private mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel;
    private mchorse.bbs_mod.film.Film film;
    private mchorse.bbs_mod.film.replays.Replay replay;
    private final List<String> filmIds = new ArrayList<>();
    private float time;
    private boolean playing;
    private boolean renderView;
    private int presetIndex;
    private int autoSaveTicks;
    private boolean resetTimelineView = true;
    private boolean buildingInspector;
    private int inspectorPage;
    private boolean focusMode;
    private boolean previewEffects = true;
    private boolean previewMasks = true;

    public UICompositorPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.title = new UILabel(IKey.constant("BBS Compositor"));
        this.title.w(92).h(20);

        this.info = new UILabel(IKey.constant("Open this film's replay compositor"));
        this.info.color(0xFF8A94A6, false);
        this.info.w(140).h(20);
        this.info.labelAnchor(0F, 0.5F);

        this.replaySelector = new UIButton(IKey.constant("Replay: -"), (button) -> this.openReplaySelector());
        this.replaySelector.w(132).h(20);
        this.replaySelector.tooltip(IKey.constant("Choose a replay or another saved film"));
        this.replaySelector.context(this::replayContextMenu);

        this.save = new UIButton(IKey.constant("Save"), (button) -> this.save());
        this.save.w(44).h(20);

        this.addLayer = new UIIcon(Icons.ADD, (icon) -> this.addLayer("solid"));
        this.addLayer.w(20).h(20);
        this.addLayer.tooltip(IKey.constant("Add layer (right-click for types)"));
        this.addLayerContextMenu(this.addLayer);

        this.play = new UIButton(IKey.constant("Play"), (button) ->
        {
            this.playing = !this.playing;
            this.updatePlayLabel();
        });
        this.play.w(44).h(20);

        this.prevFrame = new UIIcon(Icons.FRAME_PREV, (icon) -> this.stepFrame(-1));
        this.prevFrame.w(20).h(20);
        this.nextFrame = new UIIcon(Icons.FRAME_NEXT, (icon) -> this.stepFrame(1));
        this.nextFrame.w(20).h(20);

        this.timecode = new UILabel(IKey.constant("00:00:00:00"));
        this.timecode.w(94).h(20);

        this.exportFilm = new UIButton(IKey.constant("Export"), (button) -> this.exportFilm());
        this.exportFilm.w(68).h(20);
        this.exportFilm.tooltip(IKey.constant("Export this film with BBS' own video recorder"));

        this.renderMode = new UIButton(IKey.constant("Render"), (button) -> this.toggleRenderView());
        this.renderMode.w(62).h(20);

        this.focusButton = new UIIcon(Icons.MAXIMIZE, (icon) -> this.toggleFocusMode());
        this.focusButton.w(20).h(20);
        this.focusButton.tooltip(IKey.constant("Focus preview"));

        this.header = new UIElement();
        this.header.row(3);
        this.header.h(24);
        this.header.add(this.title, this.replaySelector, this.save, this.addLayer, this.play,
            this.prevFrame, this.nextFrame, this.timecode, this.exportFilm, this.renderMode);

        this.layers = new UICompLayerList((selection) ->
        {
            this.selectedLayer = selection.isEmpty() ? null : selection.get(0);
            this.refreshInspector();
            this.refreshSheets();
        });
        this.layers.w(190).h(1F);
        this.layers.background();
        this.layers.context(this::layerContextMenu);

        this.preview = new UIElement()
        {
            private boolean dragging;
            private int dragStartX;
            private int dragStartY;
            private float layerStartX;
            private float layerStartY;

            @Override
            public void render(UIContext context)
            {
                this.applyDrag(context);
                this.renderBackground(context);
                this.renderSafeArea(context);
                this.renderSelection(context);
                super.render(context);
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
                    MatrixStack stack = context.batcher.getContext().getMatrices();

                    stack.push();
                    stack.translate(this.area.x, this.area.y, 0F);

                    try
                    {
                        CompRenderer.render(
                            UICompositorPanel.this.project,
                            UICompositorPanel.this.doc,
                            UICompositorPanel.this.time,
                            1F,
                            this.area.w,
                            this.area.h,
                            0,
                            true,
                            false,
                            UICompositorPanel.this.previewEffects,
                            UICompositorPanel.this.previewMasks,
                            null,
                            context.batcher
                        );
                    }
                    catch (RuntimeException e)
                    {
                        dev.bbsae.BbsAeAddon.LOGGER.error("Composition preview failed", e);
                    }

                    stack.pop();
                }

                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1F, 0xFF2A303A);
                context.batcher.box(this.area.x, this.area.ey() - 1F, this.area.ex(), this.area.ey(), 0xFF2A303A);
                context.batcher.box(this.area.x, this.area.y, this.area.x + 1F, this.area.ey(), 0xFF2A303A);
                context.batcher.box(this.area.ex() - 1F, this.area.y, this.area.ex(), this.area.ey(), 0xFF2A303A);
            }

            private void renderSafeArea(UIContext context)
            {
                if (UICompositorPanel.this.doc == null || UICompositorPanel.this.project == null
                    || UICompositorPanel.this.project.settings == null
                    || !UICompositorPanel.this.project.settings.showSafeArea)
                {
                    return;
                }

                float w = this.area.w * 0.9F;
                float h = this.area.h * 0.9F;
                float x = this.area.x + (this.area.w - w) / 2F;
                float y = this.area.y + (this.area.h - h) / 2F;

                context.batcher.box(x, y, x + w, y + 1, 0x22FFFFFF);
                context.batcher.box(x, y + h - 1, x + w, y + h, 0x22FFFFFF);
                context.batcher.box(x, y, x + 1, y + h, 0x22FFFFFF);
                context.batcher.box(x + w - 1, y, x + w, y + h, 0x22FFFFFF);
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
                Matrix4f world;

                try
                {
                    world = CompRenderer.worldMatrix(UICompositorPanel.this.doc, layer, renderContext, new java.util.HashSet<>());
                }
                catch (RuntimeException e)
                {
                    return;
                }
                float x = -layer.width * layer.anchorX;
                float y = -layer.height * layer.anchorY;
                Vector3f[] corners = new Vector3f[]
                {
                    new Vector3f(x, y, 0F),
                    new Vector3f(x + layer.width, y, 0F),
                    new Vector3f(x + layer.width, y + layer.height, 0F),
                    new Vector3f(x, y + layer.height, 0F)
                };

                for (Vector3f corner : corners)
                {
                    world.transformPosition(corner);

                    corner.x = this.area.x + ox + corner.x * scale;
                    corner.y = this.area.y + oy + corner.y * scale;
                }

                int color = 0xFF55AAFF;

                for (int i = 0; i < 4; i++)
                {
                    Vector3f a = corners[i];
                    Vector3f b = corners[(i + 1) % 4];

                    this.line(context, a.x, a.y, b.x, b.y, color);
                }

                for (Vector3f corner : corners)
                {
                    context.batcher.box(corner.x - 2F, corner.y - 2F, corner.x + 2F, corner.y + 2F, 0xFFFFFFFF);
                    context.batcher.box(corner.x - 1F, corner.y - 1F, corner.x + 1F, corner.y + 1F, color);
                }
            }

            private void line(UIContext context, float x1, float y1, float x2, float y2, int color)
            {
                int steps = Math.max(1, (int) Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));

                for (int i = 0; i <= steps; i++)
                {
                    float t = i / (float) steps;
                    float x = x1 + (x2 - x1) * t;
                    float y = y1 + (y2 - y1) * t;

                    context.batcher.box(x, y, x + 1F, y + 1F, color);
                }
            }

            private float fitScale()
            {
                return Math.min(this.area.w / (float) UICompositorPanel.this.doc.width, this.area.h / (float) UICompositorPanel.this.doc.height);
            }

            private void applyDrag(UIContext context)
            {
                if (!this.dragging)
                {
                    return;
                }

                if (!Window.isMouseButtonPressed(0) || UICompositorPanel.this.selectedLayer == null || UICompositorPanel.this.doc == null)
                {
                    this.dragging = false;
                    UICompositorPanel.this.refreshInspector();
                    UICompositorPanel.this.save();
                    return;
                }

                CompLayer layer = UICompositorPanel.this.selectedLayer;
                float scale = Math.max(0.0001F, this.fitScale());
                float dx = (context.mouseX - this.dragStartX) / scale;
                float dy = (context.mouseY - this.dragStartY) / scale;

                if (Math.abs(dx) < 0.01F && Math.abs(dy) < 0.01F)
                {
                    return;
                }

                float x = this.layerStartX + dx;
                float y = this.layerStartY + dy;

                if (!layer.transformChannel().getKeyframes().isEmpty())
                {
                    Transform transform = layer.transformAt(UICompositorPanel.this.getFrame()).copy();

                    transform.translate.x = x;
                    transform.translate.y = y;
                    layer.transformChannel().insert(UICompositorPanel.this.getFrame(), transform);
                }
                else
                {
                    layer.x = x;
                    layer.y = y;
                }
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context) && UICompositorPanel.this.selectedLayer != null)
                {
                    this.dragging = true;
                    this.dragStartX = context.mouseX;
                    this.dragStartY = context.mouseY;
                    Transform start = UICompositorPanel.this.selectedLayer.transformAt(UICompositorPanel.this.getFrame());

                    this.layerStartX = start.translate.x;
                    this.layerStartY = start.translate.y;

                    return true;
                }

                return super.subMouseClicked(context);
            }

            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                if (this.dragging)
                {
                    this.dragging = false;
                    UICompositorPanel.this.refreshInspector();
                    UICompositorPanel.this.save();
                }

                return super.subMouseReleased(context);
            }
        };
        this.preview.h(1F);

        this.previewBar = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xCC0D1017);
                super.render(context);
            }
        };
        this.previewBar.relative(this.preview).x(6).y(6).w(134).h(20);
        this.previewBar.row(4);

        UIToggle effectsToggle = new UIToggle(IKey.constant("FX"), true, (toggle) -> this.previewEffects = toggle.getValue());
        effectsToggle.w(62).h(18);
        effectsToggle.tooltip(IKey.constant("Preview effects and styles"));
        UIToggle masksToggle = new UIToggle(IKey.constant("Mask"), true, (toggle) -> this.previewMasks = toggle.getValue());
        masksToggle.w(62).h(18);
        masksToggle.tooltip(IKey.constant("Preview masks and track mattes"));

        this.previewBar.add(effectsToggle, masksToggle);
        this.preview.add(this.previewBar);

        this.previewInfo = new UILabel(IKey.constant("Preview"));
        this.previewInfo.relative(this.preview).x(6).y(1F, -18).w(1F, -12).h(14);
        this.previewInfo.color(0xFF9AA6B8, false);
        this.previewInfo.labelAnchor(0F, 0.5F);
        this.preview.add(this.previewInfo);

        this.inspectorColumn = new UIElement();
        this.inspectorColumn.w(300).h(1F);

        this.inspectorTabs = new UITabStrip(ScrollDirection.HORIZONTAL);
        this.inspectorTabs.relative(this.inspectorColumn).x(0).y(0).w(1F).h(22);
        this.inspectorTabs.fixed();
        this.inspectorTabs.addTab(new UITextTab(IKey.constant("Comp")).w(42).h(20));
        this.inspectorTabs.addTab(new UITextTab(IKey.constant("Layer")).w(44).h(20));
        this.inspectorTabs.addTab(new UITextTab(IKey.constant("FX")).w(34).h(20));
        this.inspectorTabs.addTab(new UITextTab(IKey.constant("Mask")).w(42).h(20));
        this.inspectorTabs.addTab(new UITextTab(IKey.constant("Text")).w(40).h(20));
        this.inspectorTabs.addTab(new UITextTab(IKey.constant("Out")).w(34).h(20));
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
            IKey.constant("No keyframes"),
            IKey.constant("Select a layer, then click a stopwatch or add a keyframe at the playhead.")
        );
        this.keyframeEditor.view.context((menu) ->
        {
            menu.action(Icons.ADD, IKey.constant("Transform keyframe at playhead"), () -> this.addTransformKey(false));
            menu.action(Icons.ADD, IKey.constant("Opacity keyframe at playhead"), this::addOpacityKey);
        });
        this.keyframeEditor.setUndoId("bbsae_compositor_timeline");
        this.keyframeEditor.view.resetView();

        this.status = new UILabel(IKey.constant(""));
        this.status.color(0xFF8A94A6, false);
        this.status.h(16);

        this.renderPanel = new UIElement();
        this.renderPanel.relative(this).x(0).y(24).w(1F).h(1F, -266);
        this.renderPanel.setVisible(false);

        UILabel queueTitle = new UILabel(IKey.constant("Render Queue / Output Modules"));
        queueTitle.relative(this.renderPanel).x(0).y(0).w(1F).h(18);

        this.queue = new UICompRenderJobList((list) ->
        {
            this.selectedJob = list.isEmpty() ? null : list.get(0);
        });
        this.queue.relative(this.renderPanel).x(0).y(44).w(1F).h(1F, -96);
        this.queue.background();

        this.queuePreset = new UITextbox(64, (text) -> {});
        this.queuePreset.setText("review_h264");
        this.queueName = new UITextbox(128, (text) -> {});
        this.queueName.setText("comp_render");
        this.queueStart = new UITrackpad();
        this.queueStart.setValue(0);
        this.queueEnd = new UITrackpad();
        this.queueEnd.setValue(-1);

        UIElement queueFields = new UIElement();
        queueFields.relative(this.renderPanel).x(0).y(22).w(1F).h(22);
        queueFields.row(4);
        queueFields.add(
            UI.labelRow(IKey.constant("Preset"), 110, this.queuePreset),
            UI.labelRow(IKey.constant("Name"), 140, this.queueName),
            UI.labelRow(IKey.constant("Start"), 60, this.queueStart),
            UI.labelRow(IKey.constant("End (-1 full)"), 60, this.queueEnd)
        );

        this.queueAdd = new UIButton(IKey.constant("Add Job"), (button) -> this.addRenderJob());
        this.queueAdd.w(68);
        this.queueStartButton = new UIButton(IKey.constant("Start"), (button) ->
        {
            if (this.project != null && this.selectedJob != null)
            {
                CompExportManager.start(this.project, this.selectedJob);
            }
        });
        this.queueStartButton.w(56);
        this.queueStartAll = new UIButton(IKey.constant("Start All"), (button) ->
        {
            if (this.project != null)
            {
                CompExportManager.startQueue(this.project);
            }
        });
        this.queueStartAll.w(68);
        this.queueCancel = new UIButton(IKey.constant("Cancel"), (button) -> CompExportManager.cancel());
        this.queueCancel.w(58);
        this.queueOpen = new UIButton(IKey.constant("Folder"), (button) -> this.openExportFolder());
        this.queueOpen.w(56);
        this.queuePresetCycle = new UIButton(IKey.constant("Preset..."), (button) -> this.cyclePreset());
        this.queuePresetCycle.w(68);
        this.queueRemove = new UIButton(IKey.constant("Remove"), (button) ->
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
        this.queueRetry = new UIButton(IKey.constant("Retry"), (button) ->
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

        this.renderStatus = new UILabel(IKey.constant("Idle"));
        this.renderStatus.color(0xFF9AA4B2, false);
        this.renderStatus.relative(this.renderPanel).x(0).y(1F, -20).w(1F).h(16);

        this.renderPanel.add(queueTitle, queueFields, this.queue, queueButtons, this.renderStatus);

        this.body = new UIElement();
        this.body.row(6);
        this.body.add(this.layers, this.preview, this.inspectorColumn);
        this.body.relative(this).x(0).y(24).w(1F).h(1F, -266);

        this.renderPanel.relative(this).x(0).y(24).w(1F).h(1F, -266);

        this.timeline = new UIElement();
        this.timeline.relative(this).x(0).y(1F, -242).w(1F).h(224);

        this.scrubber = new UICompositorScrubber(this);
        UIButton zoomOut = new UIButton(IKey.constant("-"), (button) ->
        {
            this.keyframeEditor.view.getXAxis().zoomAnchor(0.5F, -1D);
        });
        zoomOut.w(20).h(18);
        UIButton zoomIn = new UIButton(IKey.constant("+"), (button) ->
        {
            this.keyframeEditor.view.getXAxis().zoomAnchor(0.5F, 1D);
        });
        zoomIn.w(20).h(18);
        UIButton fitTimeline = new UIButton(IKey.constant("Fit"), (button) ->
        {
            this.keyframeEditor.view.resetView();
            this.resetTimelineView = false;
        });
        fitTimeline.w(38).h(18);

        this.timelineHeader = new UIElement();
        this.timelineHeader.row(4);
        this.timelineHeader.relative(this.timeline).x(0).y(0).w(1F).h(26);
        this.timelineHeader.add(zoomOut, zoomIn, fitTimeline, this.scrubber, this.focusButton);

        this.keyframeEditor.relative(this.timeline).x(0).y(26).w(1F).h(1F, -26);
        this.timeline.add(this.timelineHeader, this.keyframeEditor);

        this.header.relative(this).x(0).y(0).w(1F).h(24);
        this.status.relative(this).x(0).y(1F, -18).w(1F).h(16);

        this.add(this.header, this.body, this.renderPanel, this.timeline, this.status);

        this.focusButton.h(18);

        this.onAppear(() -> this.ensureBound());
        this.onClose(() -> this.save());
        this.onDisappear(() -> this.save());
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
            this.save();
        }

        this.filmPanel = filmPanel;
        this.film = film;
        this.replay = replay;

        if (this.replay == null && !film.replays.getList().isEmpty())
        {
            this.replay = film.replays.getList().get(0);
        }

        this.replaySelector.label = IKey.constant("Replay: " + (this.replay == null ? "-" : this.replay.getName()));

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
        this.doc = replay == null
            ? this.project.getCompOrFirst(CompStore.DEFAULT_COMP)
            : CompStore.ensureReplayComp(this.project,
                replay.getId() == null || replay.getId().isBlank() ? replay.getName() : replay.getId(),
                replay.getName(), width, height, fps, duration);

        if (this.doc != null)
        {
            this.doc.duration = Math.max(this.doc.duration, duration);
            this.doc.workAreaOut = Math.max(this.doc.workAreaOut, duration);
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
        this.selectedLayer = this.doc == null || this.doc.layers.isEmpty() ? null : this.doc.layers.get(0);
        this.info.label = IKey.constant("Film: " + film.getId() + "  /  Comp: " + (this.doc == null ? "-" : this.doc.id));

        if (this.doc != null)
        {
            CompositionClip.ensureForFilm(film, this.project.id, this.doc.id);
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();
        this.save();
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
            this.info.label = IKey.constant("Open a film's Replay properties and press \"Open Compositor\"");
        }
    }

    /** Opens a specific project/comp pair stored on a composition clip. */
    public void bindComposition(mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel, mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        if (filmPanel == null || film == null)
        {
            return;
        }

        this.save();
        this.filmPanel = filmPanel;
        this.film = film;
        this.replay = null;

        CompProject requested = projectId == null || projectId.isBlank() ? null : CompStore.load(projectId);

        if (requested == null || requested.getComp(compId) == null)
        {
            requested = CompStore.loadFilmProject(film.getId(), film.getId(),
                Math.max(1, mchorse.bbs_mod.BBSSettings.videoWidth.get()),
                Math.max(1, mchorse.bbs_mod.BBSSettings.videoHeight.get()),
                Math.max(1F, mchorse.bbs_mod.BBSSettings.videoFrameRate.get()),
                Math.max(1F, film.calculateDuration() / 20F));
        }

        this.project = requested;
        this.doc = compId == null ? this.project.getCompOrFirst(CompStore.DEFAULT_COMP) : this.project.getComp(compId);

        if (this.doc == null)
        {
            this.doc = this.project.getCompOrFirst(CompStore.DEFAULT_COMP);
        }

        this.time = 0F;
        this.playing = false;
        this.selectedLayer = this.doc == null || this.doc.layers.isEmpty() ? null : this.doc.layers.get(0);
        this.replaySelector.label = IKey.constant("Replay: clip");
        this.info.label = IKey.constant("Film: " + film.getId() + "  /  Comp: " + (this.doc == null ? "-" : this.doc.id));

        if (this.doc != null)
        {
            CompositionClip.ensureForFilm(film, this.project.id, this.doc.id);
        }

        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.refreshQueue();
        this.updatePlayLabel();
        this.save();
    }

    private void exportFilm()
    {
        if (this.filmPanel == null)
        {
            this.filmPanel = this.dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);
        }

        if (this.filmPanel == null || this.filmPanel.getData() == null)
        {
            this.status.label = IKey.constant("Open a film before exporting");
            return;
        }

        this.save();

        mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard = this.dashboard;

        dashboard.setPanel(this.filmPanel);
        mchorse.bbs_mod.ui.framework.UIScreen.open(dashboard);
        this.filmPanel.preview.recordVideo.clickItself();
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

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("Replay / Film"), (value) ->
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

            manager.action(Icons.FILM, IKey.constant("Film: " + filmId), () -> this.loadFilm(filmId));
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
            int width = Math.max(1, mchorse.bbs_mod.BBSSettings.videoWidth.get());
            int height = Math.max(1, mchorse.bbs_mod.BBSSettings.videoHeight.get());
            float fps = Math.max(1F, mchorse.bbs_mod.BBSSettings.videoFrameRate.get());
            float duration = Math.max(1F, this.film == null ? 12F : this.film.calculateDuration() / 20F);
            String replayKey = replay.getId() == null || replay.getId().isBlank() ? replay.getName() : replay.getId();

            this.doc = CompStore.ensureReplayComp(this.project, replayKey, replay.getName(), width, height, fps, duration);
            this.doc.duration = Math.max(this.doc.duration, duration);
            this.doc.workAreaOut = Math.max(this.doc.workAreaOut, duration);
            this.time = 0F;
            this.selectedLayer = this.doc.layers.isEmpty() ? null : this.doc.layers.get(0);
            this.resetTimelineView = true;

            if (this.film != null)
            {
                CompositionClip.ensureForFilm(this.film, this.project.id, this.doc.id);
            }

            this.refreshLayers();
            this.refreshInspector();
            this.refreshSheets();
        }

        this.replaySelector.label = IKey.constant("Replay: " + (replay == null ? "-" : replay.getName()));
        this.info.label = IKey.constant("Film: " + (this.film == null ? "-" : this.film.getId()) + "  /  Comp: " + (this.doc == null ? "-" : this.doc.id));
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
        this.resetTimelineView = true;
        this.selectedLayer = this.doc.layers.isEmpty() ? null : this.doc.layers.get(0);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.updatePlayLabel();
    }

    private void refreshLayers()
    {
        if (this.doc == null)
        {
            this.layers.setList(List.of());
            return;
        }

        this.layers.setList(this.doc.layers);

        if (this.selectedLayer != null)
        {
            this.layers.setCurrent(this.selectedLayer);
        }
    }

    private void save()
    {
        if (this.project != null)
        {
            CompStore.save(this.project);
            this.info.label = IKey.constant("Saved " + CompStore.file(this.project.id).getFileName());
        }
    }

    private void toggleRenderView()
    {
        this.renderView = !this.renderView;
        this.renderMode.label = IKey.constant(this.renderView ? "Preview" : "Render");

        this.body.setVisible(!this.renderView);
        this.timeline.setVisible(!this.renderView);
        this.renderPanel.setVisible(this.renderView);
        this.layers.setVisible(!this.renderView && !this.focusMode);
        this.inspectorColumn.setVisible(!this.renderView && !this.focusMode);
        this.renderPanel.resize();
        this.resize();
    }

    private void toggleFocusMode()
    {
        this.focusMode = !this.focusMode;
        this.layers.setVisible(!this.focusMode && !this.renderView);
        this.inspectorColumn.setVisible(!this.focusMode && !this.renderView);
        this.resize();
    }

    /* Layer actions */

    private void addMask(CompLayer layer, boolean ellipse)
    {
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
    }

    private void addStyle(CompLayer layer, String type)
    {
        CompStyle style = new CompStyle(type);

        style.color = CompStyle.DROP_SHADOW.equals(type) ? 0xFF000000 : 0xFF55AAFF;
        style.size = 8F;
        style.distance = 6F;
        style.blur = 6F;
        layer.styles.add(style);
        this.refreshInspector();
    }

    /** BBS's native OS file picker, copied into the film's own asset folder. */
    private void importImage(CompLayer layer)
    {
        if (this.project == null)
        {
            return;
        }

        mchorse.bbs_mod.ui.utils.UIFileDialogs.pickFile(
            IKey.constant("Import Image"),
            java.nio.file.Path.of(".").toFile(),
            new String[] {"*.png", "*.jpg", "*.jpeg", "*.gif"},
            IKey.constant("Image"),
            (file) ->
            {
                if (file == null || !file.isFile())
                {
                    return;
                }

                try
                {
                    java.nio.file.Path folder = CompStore.assetRoot(this.project.id).resolve("images");
                    java.nio.file.Files.createDirectories(folder);

                    String name = file.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
                    java.nio.file.Path target = folder.resolve(name);

                    if (!target.toAbsolutePath().equals(file.toPath().toAbsolutePath()))
                    {
                        java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    layer.imagePath = "assets/images/" + name;

                    java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(target.toFile());

                    if (image != null)
                    {
                        float scale = Math.min(1F, Math.min(this.doc.width * 0.8F / image.getWidth(), this.doc.height * 0.8F / image.getHeight()));

                        layer.width = Math.max(16F, image.getWidth() * scale);
                        layer.height = Math.max(16F, image.getHeight() * scale);
                    }

                    this.refreshInspector();
                    this.save();
                }
                catch (Exception e)
                {
                    dev.bbsae.BbsAeAddon.LOGGER.error("Could not import image {}", file, e);
                    this.status.label = IKey.constant("Image import failed: " + e.getMessage());
                }
            }
        );
    }

    private void openAddLayerMenu(UIIcon icon)
    {
        this.addLayerContextMenu(icon);
    }

    private void addLayerContextMenu(UIElement element)
    {
        element.context((manager) ->
        {
            manager.action(Icons.SQUARE, IKey.constant("Solid"), () -> this.addLayer("solid"));
            manager.action(Icons.FONT, IKey.constant("Text"), () -> this.addLayer("text"));
            manager.action(Icons.SHAPES, IKey.constant("Shape"), () -> this.addLayer("shape"));
            manager.action(Icons.IMAGE, IKey.constant("Image"), () -> this.addLayer("image"));
            manager.action(Icons.FILM, IKey.constant("Footage Sequence"), () -> this.addLayer("footage"));
            manager.action(Icons.FILM, IKey.constant("Precomp"), () -> this.addLayer("precomp"));
            manager.action(Icons.SPHERE, IKey.constant("Null"), () -> this.addLayer("null"));
            manager.action(Icons.SUN, IKey.constant("Adjustment"), () -> this.addLayer("adjustment"));
        });
    }

    private void layerContextMenu(ContextMenuManager manager)
    {
        manager.action(Icons.ADD, IKey.constant("Add Layer"), () -> this.addLayer("solid"));
        manager.action(Icons.COPY, IKey.constant("Duplicate"), this::duplicateSelected);
        manager.action(Icons.TRASH, IKey.constant("Delete"), this::deleteSelected);
        manager.action(Icons.FILM, IKey.constant("Precompose Selected"), this::precomposeSelected);
        manager.action(Icons.STOPWATCH, IKey.constant("Add Transform Key"), () -> this.addTransformKey(true));
        manager.action(Icons.STOPWATCH, IKey.constant("Add Opacity Key"), () -> this.addOpacityKey());
    }

    private void addLayer(String type)
    {
        if (this.doc == null)
        {
            return;
        }

        CompLayer layer = new CompLayer(type, switch (type)
        {
            case "text" -> "Text";
            case "shape" -> "Shape";
            case "image" -> "Image";
            case "footage" -> "Footage";
            case "precomp" -> "Precomp";
            case "null" -> "Null";
            case "adjustment" -> "Adjustment";
            default -> "Solid";
        }, type);

        layer.x = this.doc.width / 2F;
        layer.y = this.doc.height / 2F;
        layer.width = Math.min(640F, this.doc.width * 0.4F);
        layer.height = Math.min(360F, this.doc.height * 0.3F);
        layer.out = this.doc.duration;

        switch (type)
        {
            case "text":
                layer.text = "New Text";
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
                if (this.project.comps.size() > 1)
                {
                    CompDocument other = this.project.comps.get(0) == this.doc ? this.project.comps.get(1) : this.project.comps.get(0);

                    layer.precompId = other.id;
                }
                layer.width = this.doc.width;
                layer.height = this.doc.height;
                break;
            case "adjustment":
                layer.effects.add(CompEffectRegistry.create("vignette", 1));
                break;
            case "null":
                layer.width = 100F;
                layer.height = 100F;
                break;
            default:
                layer.color = 0xFF335577;
                break;
        }

        this.doc.addLayer(layer);
        this.selectedLayer = layer;
        this.refreshLayers();
        this.layers.setCurrent(layer);
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    private void duplicateSelected()
    {
        if (this.doc == null || this.selectedLayer == null)
        {
            return;
        }

        CompLayer copy = this.selectedLayer.duplicate();

        copy.id = CompStore.uniqueId(this.selectedLayer.id + "_copy", this.doc.layers, (layer) -> layer.id);
        copy.name = this.selectedLayer.name + " Copy";
        copy.x += 24F;
        copy.y += 24F;
        this.doc.layers.add(copy);
        this.selectedLayer = copy;
        this.refreshLayers();
        this.layers.setCurrent(copy);
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    private void deleteSelected()
    {
        if (this.doc == null || this.selectedLayer == null)
        {
            return;
        }

        this.doc.removeLayer(this.selectedLayer.id);
        this.selectedLayer = this.doc.layers.isEmpty() ? null : this.doc.layers.get(0);
        this.refreshLayers();
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    private void precomposeSelected()
    {
        if (this.doc == null || this.selectedLayer == null)
        {
            return;
        }

        CompDocument sub = this.project.addComp("Precomp", this.doc.width, this.doc.height, this.doc.fps, this.doc.duration);

        sub.duration = this.doc.duration;
        sub.workAreaOut = this.doc.duration;

        for (CompLayer layer : new ArrayList<>(this.doc.layers))
        {
            if (layer == this.selectedLayer || layer.solo)
            {
                CompLayer copy = layer.duplicate();

                copy.parentId = "";
                sub.layers.add(copy);
                this.doc.removeLayer(layer.id);
            }
        }

        CompLayer precomp = new CompLayer("precomp", "Precomp " + sub.name, "precomp");

        precomp.precompId = sub.id;
        precomp.x = this.doc.width / 2F;
        precomp.y = this.doc.height / 2F;
        precomp.width = this.doc.width;
        precomp.height = this.doc.height;
        precomp.out = this.doc.duration;
        this.doc.addLayer(precomp);
        this.selectedLayer = precomp;
        this.refreshLayers();
        this.layers.setCurrent(precomp);
        this.refreshInspector();
        this.refreshSheets();
        this.save();
    }

    /* Inspector */

    private void refreshInspector()
    {
        this.buildingInspector = true;
        this.inspector.removeAll();

        if (this.doc == null)
        {
            this.buildingInspector = false;
            this.inspector.resize();
            return;
        }

        switch (this.inspectorPage)
        {
            case 0 ->
            {
                this.inspector.add(this.compSection());
                this.inspector.add(this.markerSection());
                this.inspector.add(this.parameterSection());
            }
            case 1 ->
            {
                this.inspector.add(this.layerSection());
                this.inspector.add(this.bindingSection(this.selectedLayer));
                this.inspector.add(this.styleSection(this.selectedLayer));
                this.inspector.add(this.layerMarkerSection(this.selectedLayer));
                this.inspector.add(this.animationPresetSection(this.selectedLayer));

                if (this.selectedLayer != null && "shape".equals(this.selectedLayer.type))
                {
                    this.inspector.add(this.shapeSection(this.selectedLayer));
                }
            }
            case 2 -> this.inspector.add(this.effectSection(this.selectedLayer));
            case 3 -> this.inspector.add(this.maskSection(this.selectedLayer));
            case 4 ->
            {
                if (this.selectedLayer != null && "text".equals(this.selectedLayer.type))
                {
                    this.inspector.add(this.textSection(this.selectedLayer));
                    this.inspector.add(this.textAnimatorSection(this.selectedLayer));
                }
                else
                {
                    UISection hint = new UISection(IKey.constant("Text"));

                    hint.fields.add(UI.label(IKey.constant("Select a text layer to edit typography."), 16));
                    this.inspector.add(hint);
                }
            }
            case 5 -> this.inspector.add(this.outputPresetSection());
            default -> this.inspector.add(this.compSection());
        }

        this.inspector.resize();
        this.buildingInspector = false;
    }

    private UISection outputPresetSection()
    {
        UISection section = new UISection(IKey.constant("Output Modules"));

        if (this.project == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("Add H.264 Preset", () ->
        {
            CompOutputPreset preset = CompOutputPreset.reviewH264();

            preset.id = CompStore.uniquePresetId(this.project, "review_h264");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("Add ProRes 422 HQ", () ->
        {
            CompOutputPreset preset = CompOutputPreset.masterProRes();

            preset.id = CompStore.uniquePresetId(this.project, "master_prores");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("Add ProRes 4444 Alpha", () ->
        {
            CompOutputPreset preset = CompOutputPreset.alphaProRes();

            preset.id = CompStore.uniquePresetId(this.project, "alpha_prores4444");
            preset.name = preset.id;
            this.project.outputPresets.add(preset);
            this.refreshInspector();
        }));

        for (CompOutputPreset preset : this.project.outputPresets)
        {
            section.fields.add(UI.label(IKey.constant("[" + preset.id + "]"), 14));
            section.fields.add(this.textRow("Name", preset.name, (value) -> preset.name = value));
            section.fields.add(this.textRow("Container", preset.container, (value) -> preset.container = value));
            section.fields.add(this.textRow("Codec", preset.codec, (value) -> preset.codec = value));
            section.fields.add(this.textRow("Extension", preset.extension, (value) -> preset.extension = value));
            section.fields.add(new UIToggle(IKey.constant("Alpha"), preset.alpha, (toggle) -> preset.alpha = toggle.getValue()));
            section.fields.add(this.numberRow("CRF", preset.crf, 0, 63, true, (value) -> preset.crf = value.intValue()));
            section.fields.add(this.textRow("Bitrate", preset.bitrate, (value) -> preset.bitrate = value));
            section.fields.add(this.textRow("Pixel Format", preset.pixelFormat, (value) -> preset.pixelFormat = value));
            section.fields.add(this.numberRow("Width (0 = comp)", preset.width, 0, 16384, true, (value) -> preset.width = value.intValue()));
            section.fields.add(this.numberRow("Height (0 = comp)", preset.height, 0, 16384, true, (value) -> preset.height = value.intValue()));
            section.fields.add(this.numberRow("Scale", preset.scale, 0.05F, 4F, false, (value) -> preset.scale = value.floatValue()));
            section.fields.add(this.numberRow("FPS (0 = comp)", preset.fps, 0, 240, true, (value) -> preset.fps = value.intValue()));
            section.fields.add(this.textRow("Post Action", preset.postAction, (value) -> preset.postAction = value));
            section.fields.add(new UIToggle(IKey.constant("Keep Frames"), preset.keepFrames, (toggle) -> preset.keepFrames = toggle.getValue()));
            section.fields.add(new UIButton(IKey.constant("Remove Preset"), (button) ->
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
        UISection section = new UISection(IKey.constant("Composition"));
        CompDocument comp = this.doc;

        section.fields.add(this.numberRow("Width", comp.width, 1, 16384, true, (value) -> comp.width = value.intValue()));
        section.fields.add(this.numberRow("Height", comp.height, 1, 16384, true, (value) -> comp.height = value.intValue()));
        section.fields.add(this.numberRow("FPS", comp.fps, 1, 240, false, (value) -> comp.fps = value.floatValue()));
        section.fields.add(this.numberRow("Duration", comp.duration, 0.1F, 36000F, false, (value) -> comp.duration = value.floatValue()));
        section.fields.add(this.colorRow("Background", comp.background, (color) -> comp.background = color));

        if (this.project != null && this.project.settings != null)
        {
            CompProject.CompSettings settings = this.project.settings;

            section.fields.add(new UIToggle(IKey.constant("Grid"), settings.showGrid, (toggle) -> settings.showGrid = toggle.getValue()));
            section.fields.add(this.numberRow("Grid Size", settings.gridSize, 8, 512, true, (value) -> settings.gridSize = value.intValue()));
            section.fields.add(new UIToggle(IKey.constant("Safe Area"), settings.showSafeArea, (toggle) -> settings.showSafeArea = toggle.getValue()));
            section.fields.add(new UIToggle(IKey.constant("Depth Effects"), settings.depthEnabled, (toggle) -> settings.depthEnabled = toggle.getValue()));
            section.fields.add(new UIToggle(IKey.constant("Auto Save"), settings.autoSave, (toggle) -> settings.autoSave = toggle.getValue()));
        }

        return section;
    }

    private UISection layerSection()
    {
        UISection section = new UISection(IKey.constant("Layer"));
        CompLayer layer = this.selectedLayer;

        if (layer == null)
        {
            section.fields.add(UI.label(IKey.constant("Select a layer in the stack"), 16));
            return section;
        }

        CompLayer l = layer;

        section.fields.add(UI.label(IKey.constant(l.type + " · " + l.id), 16));

        UITextbox name = new UITextbox(64, (text) -> l.name = text);

        name.setText(l.name);
        section.fields.add(UI.labelRow(IKey.constant("Name"), name));

        UIToggle visible = new UIToggle(IKey.constant("Visible"), l.visible, (toggle) -> l.visible = toggle.getValue());
        UIToggle locked = new UIToggle(IKey.constant("Locked"), l.locked, (toggle) -> l.locked = toggle.getValue());
        UIToggle solo = new UIToggle(IKey.constant("Solo"), l.solo, (toggle) -> l.solo = toggle.getValue());
        UIToggle guide = new UIToggle(IKey.constant("Guide"), l.guide, (toggle) -> l.guide = toggle.getValue());
        section.fields.add(UI.row(visible, locked));
        section.fields.add(UI.row(solo, guide));

        section.fields.add(this.numberRow("In", l.in, 0F, 36000F, false, (value) -> l.in = value.floatValue()));
        section.fields.add(this.numberRow("Out", l.out, 0F, 36000F, false, (value) -> l.out = value.floatValue()));
        section.fields.add(this.numberRow("Start", l.startTime, -36000F, 36000F, false, (value) -> l.startTime = value.floatValue()));
        section.fields.add(this.numberRow("Time Scale", l.timeScale, -10F, 10F, false, (value) -> l.timeScale = value.floatValue()));

        UIToggle freeze = new UIToggle(IKey.constant("Freeze Frame"), l.freeze, (toggle) -> l.freeze = toggle.getValue());
        section.fields.add(freeze);

        UIToggle timeRemap = new UIToggle(IKey.constant("Time Remap"), l.timeRemap, (toggle) ->
        {
            l.timeRemap = toggle.getValue();
            this.refreshInspector();
        });
        section.fields.add(timeRemap);

        if (l.timeRemap)
        {
            section.fields.add(this.animatedNumberRow(
                "Source Time (s)", l, "timeRemap",
                l.sourceTimeAt(this.time, this.getFrame()),
                (value) -> l.timeRemap = true,
                -36000F, 36000F
            ));
        }

        UIButton blend = new UIButton(IKey.constant(blendName(l.blendMode)), (button) ->
        {
            l.blendMode = (l.blendMode + 1) % 13;
            button.label = IKey.constant(blendName(l.blendMode));
        });
        section.fields.add(UI.labelRow(IKey.constant("Blend"), blend));

        Transform transform = l.transformAt(this.getFrame());
        section.fields.add(this.animatedNumberRow("Position X", l, "x", transform.translate.x, (value) -> this.setTransformX(value)));
        section.fields.add(this.animatedNumberRow("Position Y", l, "y", transform.translate.y, (value) -> this.setTransformY(value)));
        section.fields.add(this.animatedNumberRow("Rotation", l, "rotation", transform.rotate.z, (value) -> this.setTransformRotation(value)));
        section.fields.add(this.animatedNumberRow("Scale X", l, "scaleX", transform.scale.x, (value) -> this.setTransformScaleX(value)));
        section.fields.add(this.animatedNumberRow("Scale Y", l, "scaleY", transform.scale.y, (value) -> this.setTransformScaleY(value)));
        section.fields.add(this.animatedNumberRow("Opacity", l, "opacity", l.opacityFrame(this.getFrame()), (value) -> this.setOpacity(l, value.floatValue())));
        section.fields.add(this.numberRow("Anchor X", l.anchorX, 0F, 1F, false, (value) -> l.anchorX = value.floatValue()));
        section.fields.add(this.numberRow("Anchor Y", l.anchorY, 0F, 1F, false, (value) -> l.anchorY = value.floatValue()));

        UITextbox parent = new UITextbox(64, (text) -> l.parentId = text);

        parent.setText(l.parentId);
        section.fields.add(UI.labelRow(IKey.constant("Parent"), parent));

        UITextbox matte = new UITextbox(64, (text) -> l.trackMatteId = text);

        matte.setText(l.trackMatteId);
        section.fields.add(UI.labelRow(IKey.constant("Track Matte"), matte));

        UIToggle matteEnabled = new UIToggle(IKey.constant("Use Matte"), l.trackMatteEnabled, (toggle) -> l.trackMatteEnabled = toggle.getValue());
        UIButton matteMode = new UIButton(IKey.constant(matteName(l.trackMatteMode)), (button) ->
        {
            l.trackMatteMode = (l.trackMatteMode + 1) % 4;
            button.label = IKey.constant(matteName(l.trackMatteMode));
        });
        section.fields.add(UI.row(matteEnabled, matteMode));

        section.fields.add(this.textRow("Expression Position X", l.expressionX, (text) -> l.expressionX = text));
        section.fields.add(this.textRow("Expression Position Y", l.expressionY, (text) -> l.expressionY = text));
        section.fields.add(this.textRow("Expression Rotation", l.expressionRotation, (text) -> l.expressionRotation = text));
        section.fields.add(this.textRow("Expression Opacity", l.expressionOpacity, (text) -> l.expressionOpacity = text));

        if ("image".equals(l.type) || "footage".equals(l.type))
        {
            section.fields.add(this.textRow("footage".equals(l.type) ? "Image Sequence (%04d)" : "Image Path", l.imagePath, (text) -> l.imagePath = text));
            section.fields.add(this.smallButton("Import Image...", () -> this.importImage(l)));
            section.fields.add(this.numberRow("Image Fit (0 contain, 1 cover, 2 stretch)", l.imageFit, 0, 2, true, (value) -> l.imageFit = value.intValue()));
        }
        if ("precomp".equals(l.type))
        {
            section.fields.add(this.textRow("Precomp Id", l.precompId, (text) -> l.precompId = text));
            section.fields.add(this.numberRow("Precomp Time Offset", l.precompTimeOffset, -36000F, 36000F, false, (value) -> l.precompTimeOffset = value.floatValue()));
            section.fields.add(this.numberRow("Precomp Time Scale", l.precompTimeScale, -10F, 10F, false, (value) -> l.precompTimeScale = value.floatValue()));
        }

        return section;
    }

    private UISection bindingSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Binding / Tracking"));

        if (layer == null)
        {
            return section;
        }

        CompBinding binding = layer.binding;

        UIToggle enabled = new UIToggle(IKey.constant("Enabled"), binding.enabled, (toggle) -> binding.enabled = toggle.getValue());
        UIButton type = new UIButton(IKey.constant(binding.type), (button) ->
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
        section.fields.add(UI.labelRow(IKey.constant("Replay / Layer Target"), target));
        section.fields.add(this.numberRow("Offset X", binding.offsetX, -10000F, 10000F, false, (value) -> binding.offsetX = value.floatValue()));
        section.fields.add(this.numberRow("Offset Y", binding.offsetY, -10000F, 10000F, false, (value) -> binding.offsetY = value.floatValue()));
        section.fields.add(this.numberRow("World Y Offset", binding.worldOffsetY, -16F, 16F, false, (value) -> binding.worldOffsetY = value.floatValue()));
        section.fields.add(this.numberRow("Smoothing", binding.smoothing, 0F, 0.99F, false, (value) -> binding.smoothing = value.floatValue()));

        return section;
    }

    private UISection textSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Text"));
        UITextbox text = new UITextbox(2048, (value) -> layer.text = value);

        text.setText(layer.text);
        section.fields.add(text);
        section.fields.add(this.animatedNumberRow("Font Size", layer, "text/fontSize", layer.fontSizeAt(this.getFrame()), (value) -> layer.fontSize = (float) Math.max(1D, value), 1F, 2000F));
        section.fields.add(this.animatedNumberRow("Tracking", layer, "text/tracking", layer.trackingAt(this.getFrame()), (value) -> layer.tracking = value.floatValue(), -200F, 500F));
        section.fields.add(this.animatedNumberRow("Line Height", layer, "text/lineHeight", layer.lineHeightAt(this.getFrame()), (value) -> layer.lineHeight = (float) Math.max(0.1D, value), 0.1F, 5F));
        section.fields.add(this.colorRow("Color", layer.color, (color) -> layer.color = color));
        section.fields.add(this.colorRow("Stroke", layer.strokeColor, (color) -> layer.strokeColor = color));
        section.fields.add(this.animatedNumberRow("Stroke Width", layer, "text/strokeWidth", layer.textStrokeAt(this.getFrame()), (value) -> layer.strokeWidth = (float) Math.max(0D, value), 0F, 40F));
        section.fields.add(this.numberRow("Align (0 left, 1 center, 2 right)", layer.align, 0, 2, true, (value) -> layer.align = value.intValue()));
        section.fields.add(new UIToggle(IKey.constant("Wrap"), layer.wrap, (toggle) -> layer.wrap = toggle.getValue()));
        section.fields.add(this.numberRow("Wrap Width", layer.textBoxWidth, 10F, 10000F, false, (value) -> layer.textBoxWidth = value.floatValue()));

        return section;
    }

    private UISection textAnimatorSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Text Animators"));

        section.fields.add(this.smallButton("Add Text Animator", () ->
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
            section.fields.add(new UIToggle(IKey.constant("Enabled"), animator.enabled, (toggle) -> animator.enabled = toggle.getValue()));
            section.fields.add(this.numberRow("Start", animator.start, 0F, 1F, false, (value) -> animator.start = value.floatValue()));
            section.fields.add(this.numberRow("End", animator.end, 0F, 1F, false, (value) -> animator.end = value.floatValue()));
            section.fields.add(this.numberRow("Offset", animator.offset, -1F, 1F, false, (value) -> animator.offset = value.floatValue()));
            section.fields.add(new UIToggle(IKey.constant("Wiggle"), animator.wiggle, (toggle) -> animator.wiggle = toggle.getValue()));
            section.fields.add(this.numberRow("Randomness", animator.randomness, 0F, 200F, false, (value) -> animator.randomness = value.floatValue()));
            section.fields.add(this.numberRow("Position X", animator.positionX, -500F, 500F, false, (value) -> animator.positionX = value.floatValue()));
            section.fields.add(this.numberRow("Position Y", animator.positionY, -500F, 500F, false, (value) -> animator.positionY = value.floatValue()));
            section.fields.add(this.numberRow("Scale", animator.scale, 0.01F, 8F, false, (value) -> animator.scale = value.floatValue()));
            section.fields.add(this.numberRow("Rotation", animator.rotation, -720F, 720F, false, (value) -> animator.rotation = value.floatValue()));
            section.fields.add(this.numberRow("Opacity", animator.opacity, 0F, 1F, false, (value) -> animator.opacity = value.floatValue()));
        }

        return section;
    }

    private UISection shapeSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Shapes"));

        section.fields.add(this.smallButton("Add Shape", () ->
        {
            CompShape shape = CompShape.rect("shape_" + (layer.shapes.size() + 1), 0F, 0F, 240F, 160F, 0xFF55AAFF);

            layer.shapes.add(shape);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("Add Star", () ->
        {
            CompShape shape = new CompShape("star_" + (layer.shapes.size() + 1), CompShape.STAR);

            shape.width = 240F;
            shape.height = 240F;
            shape.sides = 5;
            shape.innerRadius = 0.45F;
            shape.fillColor = 0xFF55D68A;
            layer.shapes.add(shape);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("Add Polygon", () ->
        {
            CompShape shape = new CompShape("polygon_" + (layer.shapes.size() + 1), CompShape.POLYGON);

            shape.width = 240F;
            shape.height = 240F;
            shape.sides = 6;
            shape.fillColor = 0xFF55AAFF;
            layer.shapes.add(shape);
            this.refreshInspector();
        }));
        section.fields.add(this.smallButton("Add Bezier Path", () ->
        {
            CompShape shape = new CompShape("path_" + (layer.shapes.size() + 1), CompShape.PATH);

            shape.fillColor = 0xFF55AAFF;
            shape.add(-120F, 80F);
            shape.add(0F, -100F);
            shape.add(120F, 80F);
            layer.shapes.add(shape);
            this.refreshInspector();
        }));

        for (CompShape shape : layer.shapes)
        {
            section.fields.add(UI.label(IKey.constant(shape.id + " (" + shape.type + ")"), 16));
            section.fields.add(this.animatedNumberRow("X", layer, CompLayer.shapePath(shape.id, "x"), animValue(layer, CompLayer.shapePath(shape.id, "x"), shape.x), (value) -> shape.x = value.floatValue(), -5000F, 5000F));
            section.fields.add(this.animatedNumberRow("Y", layer, CompLayer.shapePath(shape.id, "y"), animValue(layer, CompLayer.shapePath(shape.id, "y"), shape.y), (value) -> shape.y = value.floatValue(), -5000F, 5000F));
            section.fields.add(this.animatedNumberRow("Width", layer, CompLayer.shapePath(shape.id, "width"), animValue(layer, CompLayer.shapePath(shape.id, "width"), shape.width), (value) -> shape.width = value.floatValue(), 1F, 5000F));
            section.fields.add(this.animatedNumberRow("Height", layer, CompLayer.shapePath(shape.id, "height"), animValue(layer, CompLayer.shapePath(shape.id, "height"), shape.height), (value) -> shape.height = value.floatValue(), 1F, 5000F));
            section.fields.add(this.animatedNumberRow("Roundness", layer, CompLayer.shapePath(shape.id, "roundness"), animValue(layer, CompLayer.shapePath(shape.id, "roundness"), shape.roundness), (value) -> shape.roundness = (float) Math.max(0D, Math.min(1D, value)), 0F, 500F));
            section.fields.add(this.numberRow("Sides", shape.sides, 3, 64, true, (value) -> shape.sides = value.intValue()));
            section.fields.add(this.numberRow("Inner Radius", shape.innerRadius, 0.01F, 1F, false, (value) -> shape.innerRadius = value.floatValue()));
            section.fields.add(this.colorRow("Fill", shape.fillColor, (color) -> shape.fillColor = color));
            section.fields.add(this.colorRow("Stroke", shape.strokeColor, (color) -> shape.strokeColor = color));
            section.fields.add(this.numberRow("Stroke Width", shape.strokeWidth, 0F, 80F, false, (value) -> shape.strokeWidth = value.floatValue()));
            section.fields.add(this.animatedNumberRow("Rotation", layer, CompLayer.shapePath(shape.id, "rotation"), animValue(layer, CompLayer.shapePath(shape.id, "rotation"), shape.rotation), (value) -> shape.rotation = value.floatValue(), -720F, 720F));
            section.fields.add(this.animatedNumberRow("Trim Start", layer, CompLayer.shapePath(shape.id, "trimStart"), animValue(layer, CompLayer.shapePath(shape.id, "trimStart"), shape.trimStart), (value) -> shape.trimStart = value.floatValue(), 0F, 1F));
            section.fields.add(this.animatedNumberRow("Trim End", layer, CompLayer.shapePath(shape.id, "trimEnd"), animValue(layer, CompLayer.shapePath(shape.id, "trimEnd"), shape.trimEnd), (value) -> shape.trimEnd = value.floatValue(), 0F, 1F));
            section.fields.add(this.animatedNumberRow("Trim Offset", layer, CompLayer.shapePath(shape.id, "trimOffset"), animValue(layer, CompLayer.shapePath(shape.id, "trimOffset"), shape.trimOffset), (value) -> shape.trimOffset = value.floatValue(), -1F, 1F));
            section.fields.add(this.animatedNumberRow("Repeater", layer, CompLayer.shapePath(shape.id, "repeaterCount"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterCount"), shape.repeaterCount), (value) -> shape.repeaterCount = (int) Math.max(0, Math.round(value)), 0F, 64F));
            section.fields.add(this.animatedNumberRow("Repeater Offset X", layer, CompLayer.shapePath(shape.id, "repeaterOffsetX"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterOffsetX"), shape.repeaterOffsetX), (value) -> shape.repeaterOffsetX = value.floatValue(), -1000F, 1000F));
            section.fields.add(this.animatedNumberRow("Repeater Offset Y", layer, CompLayer.shapePath(shape.id, "repeaterOffsetY"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterOffsetY"), shape.repeaterOffsetY), (value) -> shape.repeaterOffsetY = value.floatValue(), -1000F, 1000F));
            section.fields.add(this.animatedNumberRow("Repeater Scale", layer, CompLayer.shapePath(shape.id, "repeaterScale"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterScale"), shape.repeaterScale), (value) -> shape.repeaterScale = value.floatValue(), 0.01F, 4F));
            section.fields.add(this.animatedNumberRow("Repeater Rotation", layer, CompLayer.shapePath(shape.id, "repeaterRotation"), animValue(layer, CompLayer.shapePath(shape.id, "repeaterRotation"), shape.repeaterRotation), (value) -> shape.repeaterRotation = value.floatValue(), -720F, 720F));

            if (CompShape.PATH.equals(shape.type))
            {
                for (int i = 0; i < shape.points.size(); i++)
                {
                    CompShape.Point point = shape.points.get(i);
                    int index = i + 1;
                    String base = CompLayer.shapePath(shape.id, "p/" + i + "/");

                    section.fields.add(this.animatedNumberRow("Path " + index + " X", layer, base + "x", animValue(layer, base + "x", point.x), (value) -> point.x = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("Path " + index + " Y", layer, base + "y", animValue(layer, base + "y", point.y), (value) -> point.y = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("Path " + index + " In X", layer, base + "inX", animValue(layer, base + "inX", point.inX), (value) -> point.inX = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("Path " + index + " In Y", layer, base + "inY", animValue(layer, base + "inY", point.inY), (value) -> point.inY = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("Path " + index + " Out X", layer, base + "outX", animValue(layer, base + "outX", point.outX), (value) -> point.outX = value.floatValue(), -5000F, 5000F));
                    section.fields.add(this.animatedNumberRow("Path " + index + " Out Y", layer, base + "outY", animValue(layer, base + "outY", point.outY), (value) -> point.outY = value.floatValue(), -5000F, 5000F));
                }

                section.fields.add(UI.row(
                    this.smallButton("Add Path Point", () ->
                    {
                        shape.points.add(new CompShape.Point(0F, 0F));
                        this.refreshInspector();
                    }),
                    this.smallButton("Remove Last Point", () ->
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

    private UISection effectSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Effects"));

        if (layer == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("Add Blur", () ->
        {
            CompEffect effect = CompEffectRegistry.create("blur", layer.effects.size() + 1);

            effect.id = CompStore.uniqueEffectId(layer);
            layer.effects.add(effect);
            this.refreshInspector();
        }));

        UIButton add = new UIButton(IKey.constant("Effect Catalogue (right click)"), (button) -> {});

        add.context((manager) ->
        {
            for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
            {
                manager.action(Icons.ADD, IKey.constant(definition.category + " / " + definition.name), () ->
                {
                    CompEffect effect = CompEffectRegistry.create(definition.id, layer.effects.size() + 1);

                    effect.id = CompStore.uniqueEffectId(layer);
                    layer.effects.add(effect);
                    this.refreshInspector();
                });
            }
        });
        section.fields.add(add);

        for (CompEffect effect : layer.effects)
        {
            CompEffectRegistry.Definition definition = CompEffectRegistry.get(effect.type);

            if (definition == null)
            {
                continue;
            }

            section.fields.add(UI.label(IKey.constant(definition.name + " [" + effect.id + "]"), 16));

            UIToggle enabled = new UIToggle(IKey.constant("Enabled"), effect.enabled, (toggle) -> effect.enabled = toggle.getValue());
            UIButton remove = new UIButton(IKey.constant("Remove"), (button) ->
            {
                layer.effects.remove(effect);
                this.refreshInspector();
            });
            section.fields.add(UI.row(enabled, remove));

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

                section.fields.add(this.colorRow(param.label, color, (value) -> effect.putColor(param.key, value)));
            }
        }

        return section;
    }

    private UISection maskSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Masks"));

        if (layer == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("Add Rect Mask", () -> this.addMask(layer, false)));
        section.fields.add(this.smallButton("Add Ellipse Mask", () -> this.addMask(layer, true)));

        for (CompMask mask : layer.masks)
        {
            section.fields.add(UI.label(IKey.constant(mask.name), 16));

            UIToggle enabled = new UIToggle(IKey.constant("Enabled"), mask.enabled, (toggle) -> mask.enabled = toggle.getValue());
            UIButton remove = new UIButton(IKey.constant("Remove"), (button) ->
            {
                layer.masks.remove(mask);
                this.refreshInspector();
            });
            section.fields.add(UI.row(enabled, remove));
            section.fields.add(this.animatedNumberRow("Feather", layer, CompLayer.maskPath(mask.id, "feather"), layer.scalarAt(CompLayer.maskPath(mask.id, "feather"), mask.feather, this.getFrame()), (value) -> mask.feather = (float) Math.max(0D, value), 0F, 200F));
            section.fields.add(this.animatedNumberRow("Opacity", layer, CompLayer.maskPath(mask.id, "opacity"), layer.scalarAt(CompLayer.maskPath(mask.id, "opacity"), mask.opacity, this.getFrame()), (value) -> mask.opacity = (float) Math.max(0D, Math.min(1D, value)), 0F, 1F));
            section.fields.add(this.animatedNumberRow("Expansion", layer, CompLayer.maskPath(mask.id, "expansion"), layer.scalarAt(CompLayer.maskPath(mask.id, "expansion"), mask.expansion, this.getFrame()), (value) -> mask.expansion = value.floatValue(), -100F, 100F));

            for (int i = 0; i < mask.points.size(); i++)
            {
                CompMask.CompMaskPoint point = mask.points.get(i);
                int index = i + 1;
                String xPath = "mask/" + mask.id + "/p/" + i + "/x";
                String yPath = "mask/" + mask.id + "/p/" + i + "/y";

                section.fields.add(this.animatedNumberRow("Point " + index + " X", layer, xPath, layer.scalarAt(xPath, point.x, this.getFrame()), (value) -> point.x = value.floatValue(), -10000F, 10000F));
                section.fields.add(this.animatedNumberRow("Point " + index + " Y", layer, yPath, layer.scalarAt(yPath, point.y, this.getFrame()), (value) -> point.y = value.floatValue(), -10000F, 10000F));

                String inXPath = "mask/" + mask.id + "/p/" + i + "/inX";
                String inYPath = "mask/" + mask.id + "/p/" + i + "/inY";
                String outXPath = "mask/" + mask.id + "/p/" + i + "/outX";
                String outYPath = "mask/" + mask.id + "/p/" + i + "/outY";

                section.fields.add(this.animatedNumberRow("In " + index + " X", layer, inXPath, layer.scalarAt(inXPath, point.inX, this.getFrame()), (value) -> point.inX = value.floatValue(), -5000F, 5000F));
                section.fields.add(this.animatedNumberRow("In " + index + " Y", layer, inYPath, layer.scalarAt(inYPath, point.inY, this.getFrame()), (value) -> point.inY = value.floatValue(), -5000F, 5000F));
                section.fields.add(this.animatedNumberRow("Out " + index + " X", layer, outXPath, layer.scalarAt(outXPath, point.outX, this.getFrame()), (value) -> point.outX = value.floatValue(), -5000F, 5000F));
                section.fields.add(this.animatedNumberRow("Out " + index + " Y", layer, outYPath, layer.scalarAt(outYPath, point.outY, this.getFrame()), (value) -> point.outY = value.floatValue(), -5000F, 5000F));
            }

            UIButton mode = new UIButton(IKey.constant(maskModeName(mask.mode)), (button) ->
            {
                mask.mode = (mask.mode + 1) % 4;
                button.label = IKey.constant(maskModeName(mask.mode));
            });
            section.fields.add(UI.labelRow(IKey.constant("Mode"), mode));
            section.fields.add(new UIToggle(IKey.constant("Inverted"), mask.inverted, (toggle) -> mask.inverted = toggle.getValue()));
            section.fields.add(UI.row(
                this.smallButton("Add Point", () ->
                {
                    mask.points.add(new CompMask.CompMaskPoint(layer.width / 2F, layer.height / 2F));
                    this.refreshInspector();
                }),
                this.smallButton("Remove Last Point", () ->
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
        UISection section = new UISection(IKey.constant("Layer Markers"));

        if (layer == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("Add Marker At Playhead", () ->
        {
            CompMarker marker = new CompMarker("layer_marker_" + (layer.markers.size() + 1), "Marker", this.time);

            layer.markers.add(marker);
            this.refreshInspector();
        }));

        for (CompMarker marker : layer.markers)
        {
            UITextbox name = new UITextbox(128, (value) -> marker.name = value);

            name.setText(marker.name);
            section.fields.add(UI.labelRow(IKey.constant("Name"), name));
            section.fields.add(this.numberRow("Time", marker.time, 0F, 36000F, false, (value) -> marker.time = value.floatValue()));

            UIButton remove = new UIButton(IKey.constant("Remove"), (button) ->
            {
                layer.markers.remove(marker);
                this.refreshInspector();
            });

            section.fields.add(UI.row(this.smallButton("Go", () -> this.setTime(marker.time)), remove));
        }

        return section;
    }

    private UISection animationPresetSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Animation Presets"));

        if (layer == null || this.project == null)
        {
            return section;
        }

        section.fields.add(this.smallButton("Save Layer Animation", () ->
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
            UIButton apply = new UIButton(IKey.constant("Apply " + preset.name), (button) ->
            {
                if (preset.applyTo(layer))
                {
                    this.refreshSheets();
                    this.refreshInspector();
                    this.save();
                }
            });

            section.fields.add(UI.row(apply, new UIButton(IKey.constant("Remove"), (button) ->
            {
                this.project.animationPresets.remove(preset);
                this.refreshInspector();
            })));
        }

        return section;
    }

    private UISection styleSection(CompLayer layer)
    {
        UISection section = new UISection(IKey.constant("Layer Styles"));

        if (layer == null)
        {
            return section;
        }

        UIButton shadow = this.smallButton("Add Drop Shadow", () -> this.addStyle(layer, CompStyle.DROP_SHADOW));

        shadow.context((manager) ->
        {
            manager.action(Icons.FADING, IKey.constant("Drop Shadow"), () -> this.addStyle(layer, CompStyle.DROP_SHADOW));
            manager.action(Icons.FADING, IKey.constant("Inner Shadow"), () -> this.addStyle(layer, CompStyle.INNER_SHADOW));
            manager.action(Icons.LIGHT, IKey.constant("Outer Glow"), () -> this.addStyle(layer, CompStyle.OUTER_GLOW));
            manager.action(Icons.LIGHT, IKey.constant("Inner Glow"), () -> this.addStyle(layer, CompStyle.INNER_GLOW));
            manager.action(Icons.SUN, IKey.constant("Bevel & Emboss"), () -> this.addStyle(layer, CompStyle.BEVEL));
            manager.action(Icons.COLOR, IKey.constant("Color Overlay"), () -> this.addStyle(layer, CompStyle.COLOR_OVERLAY));
            manager.action(Icons.COLOR, IKey.constant("Gradient Overlay"), () -> this.addStyle(layer, CompStyle.GRADIENT_OVERLAY));
            manager.action(Icons.LIGHT, IKey.constant("Satin"), () -> this.addStyle(layer, CompStyle.SATIN));
            manager.action(Icons.OUTLINE, IKey.constant("Stroke"), () -> this.addStyle(layer, CompStyle.STROKE));
        });
        section.fields.add(shadow);
        section.fields.add(this.smallButton("Add Outer Glow", () -> this.addStyle(layer, CompStyle.OUTER_GLOW)));
        section.fields.add(this.smallButton("Add Stroke", () -> this.addStyle(layer, CompStyle.STROKE)));
        section.fields.add(this.smallButton("Add Color Overlay", () -> this.addStyle(layer, CompStyle.COLOR_OVERLAY)));

        for (CompStyle style : layer.styles)
        {
            section.fields.add(UI.label(IKey.constant(style.type), 16));

            UIToggle enabled = new UIToggle(IKey.constant("Enabled"), style.enabled, (toggle) -> style.enabled = toggle.getValue());
            UIButton remove = new UIButton(IKey.constant("Remove"), (button) ->
            {
                layer.styles.remove(style);
                this.refreshInspector();
            });
            section.fields.add(UI.row(enabled, remove));
            section.fields.add(this.colorRow("Color", style.color, (color) -> style.color = color));
            section.fields.add(this.colorRow("Color 2", style.color2, (color) -> style.color2 = color));
            section.fields.add(this.numberRow("Opacity", style.opacity, 0F, 1F, false, (value) -> style.opacity = value.floatValue()));
            section.fields.add(this.numberRow("Size", style.size, 0F, 200F, false, (value) -> style.size = value.floatValue()));
            section.fields.add(this.numberRow("Distance", style.distance, -300F, 300F, false, (value) -> style.distance = value.floatValue()));
            section.fields.add(this.numberRow("Angle", style.angle, -180F, 180F, false, (value) -> style.angle = value.floatValue()));
            section.fields.add(this.numberRow("Blur", style.blur, 0F, 100F, false, (value) -> style.blur = value.floatValue()));
        }

        return section;
    }

    private UISection markerSection()
    {
        UISection section = new UISection(IKey.constant("Composition Markers"));

        section.fields.add(this.smallButton("Add Marker at Playhead", () ->
        {
            CompMarker marker = new CompMarker("marker_" + (this.doc.markers.size() + 1), "Marker", this.time);

            this.doc.markers.add(marker);
            this.refreshInspector();
        }));

        for (CompMarker marker : this.doc.markers)
        {
            UIButton remove = new UIButton(IKey.constant(CompTime.timecode(marker.time, this.doc.fps) + "  " + marker.name), (button) ->
            {
                this.doc.markers.remove(marker);
                this.refreshInspector();
            });

            section.fields.add(remove);
        }

        return section;
    }

    private UISection parameterSection()
    {
        UISection section = new UISection(IKey.constant("Master Properties"));

        section.fields.add(this.smallButton("Add Parameter", () ->
        {
            int serial = this.doc.parameters.size() + 1;

            this.doc.parameters.add(new CompParameter("parameter_" + serial, 0F));
            this.refreshInspector();
        }));

        for (CompParameter parameter : this.doc.parameters)
        {
            section.fields.add(this.textRow("Name", parameter.name, (value) ->
            {
                if (value != null && !value.isBlank())
                {
                    parameter.name = value;
                    parameter.label = value;
                }
            }));
            section.fields.add(this.numberRow(parameter.label, parameter.value, parameter.min, parameter.max, false, (value) -> parameter.value = value.floatValue()));
            section.fields.add(this.numberRow("Min", parameter.min, -100000F, 100000F, false, (value) -> parameter.min = value.floatValue()));
            section.fields.add(this.numberRow("Max", parameter.max, -100000F, 100000F, false, (value) -> parameter.max = value.floatValue()));
            section.fields.add(new UIButton(IKey.constant("Remove"), (button) ->
            {
                this.doc.parameters.remove(parameter);
                this.refreshInspector();
            }));
        }

        return section;
    }

    /* Reusable rows (all BBS controls) */

    private UIElement numberRow(String label, double value, double min, double max, boolean integer, Consumer<Double> callback)
    {
        UITrackpad trackpad = new UITrackpad(callback);

        trackpad.limit(min, max, integer);
        trackpad.setValue(value);

        return UI.labelRow(IKey.constant(label), trackpad);
    }

    private UIElement animatedNumberRow(String label, CompLayer layer, String path, float value, Consumer<Double> setter, float min, float max)
    {
        boolean transformPath = "x".equals(path) || "y".equals(path) || "rotation".equals(path) || "scaleX".equals(path) || "scaleY".equals(path);

        UITrackpad trackpad = new UITrackpad((next) ->
        {
            setter.accept(next);

            if (!transformPath) this.setScalar(layer, path, next.floatValue());
        });

        trackpad.limit(min, max, false);
        trackpad.setValue(value);

        UIIcon key = new UIIcon(Icons.STOPWATCH, (icon) ->
        {
            if (transformPath)
            {
                this.addTransformKey(true);
                return;
            }

            if ("opacity".equals(path))
            {
                this.addOpacityKey();
                return;
            }

            this.setScalar(layer, path, (float) trackpad.getValue());
            this.addScalarKey(layer, path);
        });

        key.wh(16, 16);
        key.tooltip(IKey.constant("Add keyframe at playhead"));
        key.active(layer.hasScalarChannel(path) || "opacity".equals(path) || transformPath && !layer.transformChannel().getKeyframes().isEmpty());

        UIElement row = new UIElement();

        row.row(2).height(20);
        row.add(UI.label(IKey.constant(label), 16), key, trackpad.w(82));

        return row;
    }

    private UIElement animatedNumberRow(String label, CompLayer layer, String path, float value, Consumer<Double> setter)
    {
        return this.animatedNumberRow(label, layer, path, value, setter, -100000F, 100000F);
    }

    private float animValue(CompLayer layer, String path, float fallback)
    {
        return layer.scalarAt(path, fallback, this.getFrame());
    }

    private UIElement textRow(String label, String value, Consumer<String> callback)
    {
        UITextbox box = new UITextbox(2048, callback);

        box.setText(value);
        return UI.labelRow(IKey.constant(label), box);
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
            }
        }).withAlpha();

        color.setColor(value);
        return UI.labelRow(IKey.constant(label), color);
    }

    private UIButton smallButton(String label, Runnable callback)
    {
        return new UIButton(IKey.constant(label), (button) -> callback.run());
    }

    private UIButton buttonRow(String label, Runnable callback)
    {
        return new UIButton(IKey.constant(label), (button) -> callback.run());
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

        this.keyframeEditor.view.addSheet(new UIKeyframeSheet(
            "transform",
            IKey.constant("Transform"),
            Colors.BLUE,
            layer.transformChannel(),
            null
        ).seed(layer::defaultTransform));

        this.keyframeEditor.view.addSheet(new UIKeyframeSheet(
            "opacity",
            IKey.constant("Opacity"),
            Colors.GREEN,
            layer.opacityChannel(),
            null
        ).seed(() -> layer.opacity));

        int color = 0;

        for (String path : layer.animatedPaths())
        {
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
            this.keyframeEditor.view.resetView();
        }
    }

    private String sheetTitle(String path)
    {
        if ("timeRemap".equals(path)) return "Source Time";

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
    }

    private void addScalarKey(CompLayer layer, String path)
    {
        float value = this.scalarValue(layer, path);

        layer.scalarChannel(path).insert(this.getFrame(), value);

        if (path.startsWith("fx/"))
        {
            this.setScalar(layer, path, value);
        }

        this.refreshSheets();
        this.refreshInspector();
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

        this.selectedLayer.transformChannel().insert(this.getFrame(), this.selectedLayer.transformAt(this.getFrame()).copy());
        this.refreshSheets();
    }

    private void addOpacityKey()
    {
        if (this.selectedLayer == null)
        {
            return;
        }

        this.selectedLayer.opacityChannel().insert(this.getFrame(), this.selectedLayer.opacityFrame(this.getFrame()));
        this.refreshSheets();
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
        Transform transform = layer.transformAt(this.getFrame()).copy();

        switch (component)
        {
            case 0 -> transform.translate.x = value;
            case 1 -> transform.translate.y = value;
            case 2 -> transform.rotate.z = value;
            case 3 -> transform.scale.x = value;
            case 4 -> transform.scale.y = value;
            default -> { }
        }

        if (!layer.transformChannel().getKeyframes().isEmpty())
        {
            layer.transformChannel().insert(this.getFrame(), transform);
        }
        else
        {
            switch (component)
            {
                case 0 -> layer.x = value;
                case 1 -> layer.y = value;
                case 2 -> layer.rotation = value;
                case 3 -> layer.scaleX = value;
                case 4 -> layer.scaleY = value;
                default -> { }
            }
        }
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

        CompRenderJob job = new CompRenderJob(
            "job_" + (this.project.renderQueue.size() + 1),
            this.project.id,
            this.doc.id,
            this.queuePreset.getText().isBlank() ? "review_h264" : this.queuePreset.getText()
        );

        job.outputName = this.queueName.getText().isBlank() ? this.doc.id : this.queueName.getText();
        job.rangeStart = (float) this.queueStart.getValue();
        job.rangeEnd = (float) this.queueEnd.getValue();
        this.project.renderQueue.add(job);
        this.selectedJob = job;
        this.refreshQueue();
        this.save();
    }

    private void cyclePreset()
    {
        if (this.project == null || this.project.outputPresets.isEmpty())
        {
            return;
        }

        this.presetIndex = (this.presetIndex + 1) % this.project.outputPresets.size();

        CompOutputPreset preset = this.project.outputPresets.get(this.presetIndex);

        this.queuePreset.setText(preset.id);
        this.queueName.setText(this.doc == null ? "comp" : this.doc.id);
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

    @Override
    public void startRenderFrame(float tickDelta)
    {
        if (!this.playing || this.doc == null || this.renderView)
        {
            return;
        }

        this.time += tickDelta / 20F;

        if (this.time >= this.doc.duration)
        {
            this.time = 0F;
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.autoSaveTicks += 1;

        if (this.autoSaveTicks >= 1200 && this.project != null && this.project.settings != null && this.project.settings.autoSave)
        {
            this.autoSaveTicks = 0;
            this.save();
        }

        this.timecode.label = IKey.constant(this.doc == null ? "00:00:00:00" : CompTime.timecode(this.getTime(), this.doc.fps));
        this.status.label = IKey.constant(this.doc == null ? "No composition" : this.doc.id + " / " + this.doc.layers.size() + " layers");

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

            this.previewInfo.label = IKey.constant(this.doc.width + "x" + this.doc.height + "  " + (int) this.doc.fps + "fps  " + effects + " FX");
        }
        else
        {
            this.previewInfo.label = IKey.constant("Preview");
        }

        if (this.renderView)
        {
            this.renderStatus.label = IKey.constant(CompExportManager.status());
        }

        super.render(context);
    }

    private void updatePlayLabel()
    {
        this.play.label = IKey.constant(this.playing ? "Pause" : "Play");
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
        this.time = Math.max(0F, Math.min(this.doc.duration, frame / fps));

        this.updatePlayLabel();
    }

    @Override
    public boolean needsBackground()
    {
        return true;
    }

    private static String blendName(int mode)
    {
        return switch (mode)
        {
            case CompLayer.BLEND_ADD -> "Add";
            case CompLayer.BLEND_MULTIPLY -> "Multiply";
            case CompLayer.BLEND_SCREEN -> "Screen";
            case CompLayer.BLEND_OVERLAY -> "Overlay";
            case CompLayer.BLEND_SOFT_LIGHT -> "Soft Light";
            case CompLayer.BLEND_HARD_LIGHT -> "Hard Light";
            case CompLayer.BLEND_DIFFERENCE -> "Difference";
            case CompLayer.BLEND_EXCLUSION -> "Exclusion";
            case CompLayer.BLEND_DARKEN -> "Darken";
            case CompLayer.BLEND_LIGHTEN -> "Lighten";
            case CompLayer.BLEND_COLOR_DODGE -> "Color Dodge";
            case CompLayer.BLEND_COLOR_BURN -> "Color Burn";
            default -> "Normal";
        };
    }

    private static String matteName(int mode)
    {
        return switch (mode)
        {
            case CompLayer.MATTE_LUMA -> "Luma";
            case CompLayer.MATTE_ALPHA_INVERTED -> "Alpha Inverted";
            case CompLayer.MATTE_LUMA_INVERTED -> "Luma Inverted";
            default -> "Alpha";
        };
    }

    private static String maskModeName(int mode)
    {
        return switch (mode)
        {
            case CompMask.MODE_SUBTRACT -> "Subtract";
            case CompMask.MODE_INTERSECT -> "Intersect";
            case CompMask.MODE_EXCLUDE -> "Exclude";
            default -> "Add";
        };
    }
}
