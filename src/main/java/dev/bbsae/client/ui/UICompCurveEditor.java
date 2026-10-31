package dev.bbsae.client.ui;

import dev.bbsae.comp.CompCurveChannel;
import dev.bbsae.comp.CompCurves;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The After Effects curve editor (value graph + speed graph).
 *
 * <p>First principle: an animation curve is a graph of <b>time</b> against <b>value</b>, and every
 * key owns two tangent handles. Everything else in this file — the ruler, the grid, marquee
 * selection, the handle mirroring, the ease presets — is a consequence of that one model. The
 * editor therefore does not re-implement interpolation: it edits the very handles BBS stores on
 * {@link Keyframe} ({@code lx/ly/rx/ry}) and the very interpolation type each key holds, so what is
 * drawn here is what the renderer plays.</p>
 *
 * <p>Two views, like AE:</p>
 * <ul>
 *   <li><b>值图表</b> — y is the property value. Handles are dragged in (time, value).</li>
 *   <li><b>速度图表</b> — y is the derivative, value units per second. The same handles are
 *       edited as (influence, speed), which is how AE presents them in that view.</li>
 * </ul>
 */
public class UICompCurveEditor extends UIElement
{
    private final UICompositorPanel panel;
    private final UIElement toolbar;
    private final Graph graph = new Graph();

    private final UIButton modeButton;
    private final UITrackpad influence;

    public UICompCurveEditor(UICompositorPanel panel)
    {
        this.panel = panel;

        this.toolbar = new UIElement();
        this.toolbar.relative(this).x(0).y(0).w(1F).h(24);

        this.modeButton = CompUi.flat("值图表", "切换 AE 的两种图表：值图表（y = 属性值）与速度图表（y = 每秒变化量）", this::toggleGraphMode);
        this.modeButton.w(62).h(CompUi.control());

        this.influence = new UITrackpad((value) ->
        {
            this.graph.influence = (float) Math.max(0.01D, Math.min(1D, value));
        });
        this.influence.limit(0.01F, 1F, false);
        this.influence.setValue(CompCurves.DEFAULT_INFLUENCE);
        this.influence.w(44).h(18);
        this.influence.tooltip(IKey.constant("缓动影响（手柄占段长的比例）；AE 默认 33.3%"));

        UIElement easeRow = new UIElement();

        easeRow.relative(this.toolbar).x(70).y(Math.max(2, (24 - CompUi.control()) / 2)).w(1F, -372).h(CompUi.control());
        easeRow.row(4);

        UIButton linear = this.toolButton("线性", () -> this.graph.applyEase(0));
        UIButton bezier = this.toolButton("贝塞尔", () -> this.graph.applyEase(1));
        UIButton ease = this.toolButton("缓动", () -> this.graph.applyEase(2));
        UIButton easeIn = this.toolButton("缓入", () -> this.graph.applyEase(3));
        UIButton easeOut = this.toolButton("缓出", () -> this.graph.applyEase(4));
        UIButton hold = this.toolButton("定格", () -> this.graph.applyEase(5));
        UIButton auto = this.toolButton("自动", () -> this.graph.applyEase(6));
        UIButton continuous = this.toolButton("连续", () -> this.graph.applyEase(7));

        linear.tooltip(IKey.constant("线性：直线段，无手柄"));
        bezier.tooltip(IKey.constant("贝塞尔：保留当前手柄，只切换曲线类型"));
        ease.tooltip(IKey.constant("缓动（Easy Ease）：两侧手柄水平，进出都减速"));
        easeIn.tooltip(IKey.constant("缓入（Easy Ease In）：只平滑进入本关键帧的那一段"));
        easeOut.tooltip(IKey.constant("缓出（Easy Ease Out）：只平滑离开本关键帧的那一段"));
        hold.tooltip(IKey.constant("定格：保持当前值直到下一个关键帧"));
        auto.tooltip(IKey.constant("自动贝塞尔：不存手柄，切线由相邻关键帧自动求导"));
        continuous.tooltip(IKey.constant("连续贝塞尔：按相邻关键帧的斜率生成切线，曲线穿过关键帧时方向连续"));

        easeRow.add(linear, bezier, ease, easeIn, easeOut, hold, auto, continuous);

        int control = CompUi.control();
        int barY = Math.max(2, (24 - control) / 2);
        UIButton fit = CompUi.flat("适配", "时间轴适配整条合成、数值轴适配当前通道的关键帧（F）", () -> this.graph.fitAll());
        UIButton snap = CompUi.flat("吸附", "拖动关键帧时按帧取整；关闭后可以放在两帧之间", () ->
        {
            this.graph.snapping = !this.graph.snapping;
            this.panel.setStatus(this.graph.snapping ? "曲线吸附：开（按帧）" : "曲线吸附：关");
        });
        UIButton solo = CompUi.flat("聚焦", "当前轴实线，同族轴（例如 X 选中时的 Y/Z）半透明预览；关闭后叠加所有可见通道", () ->
        {
            this.graph.soloActive = !this.graph.soloActive;
            this.graph.fitValue();
            this.panel.setStatus(this.graph.soloActive ? "曲线：当前轴实线，同族轴淡显预览" : "曲线：叠加所有可见通道");
        });

        fit.w(40).h(control);
        snap.w(40).h(control);
        solo.w(40).h(control);

        this.modeButton.relative(this.toolbar).x(4).y(barY).w(62).h(control);
        this.influence.relative(this.toolbar).x(1F, -224).y(barY).w(46).h(control);
        fit.relative(this.toolbar).x(1F, -174).y(barY).w(40).h(control);
        snap.relative(this.toolbar).x(1F, -130).y(barY).w(40).h(control);
        solo.relative(this.toolbar).x(1F, -272).y(barY).w(40).h(control);

        UIButton addKey = CompUi.flat("加键", "在播放头给当前通道加一个关键帧；也可以双击图表空白处", () -> this.graph.addKeyAtPlayhead());
        UIButton removeKey = CompUi.flat("删键", "删除选中的关键帧（Delete / Backspace）", () -> this.graph.deleteSelected());

        addKey.relative(this.toolbar).x(1F, -84).y(barY).w(40).h(control);
        removeKey.relative(this.toolbar).x(1F, -40).y(barY).w(40).h(control);

        this.toolbar.add(this.modeButton, easeRow, this.influence, fit, snap, solo, addKey, removeKey);

        this.graph.relative(this).x(0).y(24).w(1F).h(1F, -24);

        this.add(this.toolbar, this.graph);
    }

    /** Value graph <-> speed graph. Reads the field only when clicked, so it can be built inline. */
    private void toggleGraphMode()
    {
        this.graph.speedMode = !this.graph.speedMode;
        this.modeButton.label = IKey.constant(this.graph.speedMode ? "速度图表" : "值图表");
        this.graph.fitValue();
    }

    private UIButton toolButton(String label, Runnable callback)
    {
        return CompUi.flat(label, callback).h(CompUi.control());
    }

    /** Called by the panel when the selected layer, the document or the time changed. */
    public void refresh()
    {
        this.graph.rebuild();
    }

    public boolean isInteracting()
    {
        return this.graph.dragMode != Graph.DRAG_NONE;
    }

    /** Verification hook: select the first key of the active channel so handles are visible. */
    public void debugSelectFirstKey()
    {
        this.graph.debugSelectFirstKey();
    }

    /** Verification hook: expand the first key's handles as if the user picked Bezier. */
    public void debugMakeFirstBezier()
    {
        this.graph.debugMakeFirstBezier();
    }

    /**
     * Selects one property in the graph after the inspector created a key for it. This is the
     * "the AE curve appears automatically" half of effect keyframing: the group is expanded, the
     * channel becomes active and the value axis is fitted, so the artist sees a key immediately.
     */
    public void focusChannel(String path)
    {
        this.graph.focusChannel(path);
    }

    private final class Graph extends UIElement
    {
        private static final int DRAG_NONE = 0;
        private static final int DRAG_KEY = 1;
        private static final int DRAG_HANDLE = 2;
        private static final int DRAG_MARQUEE = 3;
        private static final int DRAG_PAN = 4;
        private static final int DRAG_SCRUB = 5;

        private static final int LIST_W = 150;
        private static final int RULER_H = 18;
        /** Dev switch: dumps the per-column geometry, so a screenshot can be checked against data. */
        private static final boolean DEBUG_SPANS = Boolean.getBoolean("bbsae.debugCurve");
        private static final int ROW_H = 17;
        private static final int GROUP_H = 15;

        private final Area listArea = new Area();
        private final Area rulerArea = new Area();
        private final Area curveArea = new Area();

        private List<CompCurveChannel> channels = new ArrayList<>();
        private final List<ListRow> rows = new ArrayList<>();
        private final Set<String> visible = new LinkedHashSet<>();
        private final Set<String> collapsed = new java.util.HashSet<>();
        private CompCurveChannel active;
        private CompLayer layer;

        private final Set<Keyframe<?>> selection = Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<Keyframe<?>, Float> dragStartValues = new IdentityHashMap<>();
        private final IdentityHashMap<Keyframe<?>, Float> dragStartTicks = new IdentityHashMap<>();

        private boolean speedMode;
        private boolean snapping = true;
        /**
         * Draw and hit-test only the active channel.
         *
         * <p>An AE graph with position (0…1080) and opacity (0…1) on one value axis is unreadable:
         * the opacity curve collapses onto the bottom edge and every key of every property is a
         * click target. Default is therefore one channel at a time, with the option to overlay.</p>
         */
        private boolean soloActive = true;
        private float influence = CompCurves.DEFAULT_INFLUENCE;

        private double viewStart;
        private double viewDuration = 5D;
        private float valueMin = -100F;
        private float valueMax = 100F;
        private float speedMin = -1000F;
        private float speedMax = 1000F;
        private int listScroll;

        private int dragMode = DRAG_NONE;
        private Keyframe<?> dragHandleKey;
        private boolean dragHandleOutgoing;
        private int dragStartX;
        private int dragStartY;
        private int marqueeX;
        private int marqueeY;
        private boolean dragMoved;
        private boolean actionStarted;
        private double panStartView;
        private float panStartValueMin;
        private float panStartValueMax;

        /* Snapshot of the handle at press time. Dragging must add the pointer delta to these
         * values; the old code treated the delta as the absolute handle offset, so the first
         * mouse move collapsed every handle to (0.25 frame, 0 value) and bezier editing felt
         * broken. */
        private float dragHandleStartRx;
        private float dragHandleStartRy;
        private float dragHandleStartLx;
        private float dragHandleStartLy;

        private int hoverRow = -1;
        private int lastClickX;
        private int lastClickY;
        private long lastClickTime;

        /* --- curve geometry ------------------------------------------------------
         * One reusable span array per channel, refilled every frame (see spansFor for why it is
         * not cached). See CurveSpans for why the curve is stored per column instead of as a
         * polyline: the vertex count must not depend on the curve's slope. */
        private final java.util.Map<String, CurveSpans> spanCache = new java.util.HashMap<>();
        /** keyframe -> owning channel, so hit testing is O(1) instead of O(channels x keys). */
        private final IdentityHashMap<Keyframe<?>, CompCurveChannel> owners = new IdentityHashMap<>();
        /** The curve under the last key hit; overlay mode uses it to pick the right component. */
        private CompCurveChannel hitChannel;
        private boolean rowsDirty = true;
        private String readoutText = "";
        private int readoutFrame = Integer.MIN_VALUE;
        private CompCurveChannel readoutChannel;
        private long axisLabelKey;
        private float[] axisLabelValues = new float[0];
        private String[] axisLabelTexts = new String[0];

        private final class ListRow
        {
            final String group;
            final CompCurveChannel channel;
            final int y;
            final int height;

            ListRow(String group, CompCurveChannel channel, int y, int height)
            {
                this.group = group;
                this.channel = channel;
                this.y = y;
                this.height = height;
            }
        }

        private Graph()
        {
            /* AE's graph editor has the interpolation menu on the curve itself, not only in a
             * toolbar. Right-clicking a key selects it and offers the same presets, so the hand
             * stays on the graph while tuning a curve. */
            this.context((ContextMenuManager menu) ->
            {
                UIContext context = this.getContext();

                if (context == null || !this.curveArea.isInside(context))
                {
                    return;
                }

                Keyframe<?> key = this.keyAt(context.mouseX, context.mouseY);

                if (key == null)
                {
                    menu.action(Icons.ADD, IKey.constant("在播放头加关键帧"), this::addKeyAtPlayhead);
                    menu.action(Icons.MAXIMIZE, IKey.constant("适配曲线视图"), this::fitAll);

                    return;
                }

                if (this.hitChannel != null)
                {
                    this.active = this.hitChannel;
                    this.visible.add(this.hitChannel.id);
                }

                CompCurveChannel channel = this.channelOf(key);

                if (channel != null)
                {
                    this.active = channel;
                    this.visible.add(channel.id);
                }

                if (!this.selection.contains(key))
                {
                    this.selection.clear();
                    this.selection.add(key);
                }

                menu.action(Icons.GRAPH, IKey.constant("线性"), () -> this.applyEase(0));
                menu.action(Icons.GRAPH, IKey.constant("贝塞尔"), () -> this.applyEase(1));
                menu.action(Icons.GRAPH, IKey.constant("缓动（Easy Ease）"), () -> this.applyEase(2));
                menu.action(Icons.GRAPH, IKey.constant("缓入"), () -> this.applyEase(3));
                menu.action(Icons.GRAPH, IKey.constant("缓出"), () -> this.applyEase(4));
                menu.action(Icons.GRAPH, IKey.constant("定格"), () -> this.applyEase(5));
                menu.action(Icons.GRAPH, IKey.constant("自动贝塞尔"), () -> this.applyEase(6));
                menu.action(Icons.GRAPH, IKey.constant("连续贝塞尔"), () -> this.applyEase(7));
                menu.action(Icons.TRASH, IKey.constant("删除关键帧"), this::deleteSelected);
            });
        }

        /* Data */

        private void rebuild()
        {
            CompLayer selected = UICompCurveEditor.this.panel.getSelectedLayer();
            boolean layerChanged = false;

            if (selected != this.layer)
            {
                this.layer = selected;
                this.selection.clear();
                this.active = null;
                this.visible.clear();
                this.viewStart = 0D;
                layerChanged = true;
            }

            /* The channel adapters are rebuilt on every refresh, so the active channel has to be
             * carried over by id. Comparing object identity (the old check) always failed and
             * silently jumped the user back to "位置 X" after any inspector edit. */
            String activeId = this.active == null ? null : this.active.id;

            this.channels = CompCurveChannel.of(selected);

            /* Rebuild the key -> channel index once per channel-set change, not per hit test. */
            this.owners.clear();

            for (CompCurveChannel channel : this.channels)
            {
                for (Object entry : channel.raw().getKeyframes())
                {
                    this.owners.put((Keyframe<?>) entry, channel);
                }
            }

            this.active = null;

            if (activeId != null)
            {
                for (CompCurveChannel channel : this.channels)
                {
                    if (channel.id.equals(activeId))
                    {
                        this.active = channel;
                        break;
                    }
                }
            }

            if (this.active == null)
            {
                this.active = this.channels.isEmpty() ? null : this.channels.get(0);
            }

            boolean channelChanged = this.active != null && !this.active.id.equals(activeId);

            if (this.visible.isEmpty())
            {
                for (CompCurveChannel channel : this.channels)
                {
                    if (channel.hasKeys())
                    {
                        this.visible.add(channel.id);
                    }
                }

                if (this.active != null)
                {
                    this.visible.add(this.active.id);
                }
            }

            this.rowsDirty = true;
            this.invalidateGeometry();

            /* Frame the data.
             *
             * A graph editor that opens on a fixed -100…100 axis shows nothing for a position
             * channel (values in the hundreds) or a 0…1 opacity channel: every sampled Y lands
             * outside the rectangle and the clip removes the line, leaving only the key dots. That
             * is exactly the reported symptom, and it is a *default* bug, not a drawing bug.
             *
             * Time is framed to the composition (AE's graph is time-locked to the work area) —
             * the old code divided the composition's *frames* by 24, so a 60 fps, 12-second comp
             * opened with a 30-second view. */
            if (layerChanged)
            {
                this.fitTime();
                this.fitValue();
            }
            else if (channelChanged)
            {
                this.fitValue();
            }
            else
            {
                this.fitValueIfInvisible();
            }
        }

        /**
         * Refits the value axis only when the current one cannot show any key of the active
         * channel. This keeps a manually zoomed graph while making it impossible to end up with a
         * curve that is entirely off-screen.
         */
        private void fitValueIfInvisible()
        {
            if (this.active == null || !this.active.hasKeys())
            {
                return;
            }

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;

            for (CompCurveChannel.Key key : this.active.keys())
            {
                float value = this.displayValue(this.active, key.raw, key.value, key.tick);

                min = Math.min(min, value);
                max = Math.max(max, value);
            }

            float lo = this.speedMode ? this.speedMin : this.valueMin;
            float hi = this.speedMode ? this.speedMax : this.valueMax;

            if (min == Float.MAX_VALUE || max < lo || min > hi)
            {
                this.fitValue();
            }
        }

        /** Drops the per-channel span arrays; used when the channel set changes wholesale. */
        private void invalidateGeometry()
        {
            this.spanCache.clear();
            this.readoutFrame = Integer.MIN_VALUE;
        }

        private void buildRows()
        {
            this.rowsDirty = false;
            this.rows.clear();

            int y = -this.listScroll;
            String lastGroup = null;

            for (CompCurveChannel channel : this.channels)
            {
                if (!channel.group.equals(lastGroup))
                {
                    lastGroup = channel.group;

                    if (!channel.group.isEmpty())
                    {
                        this.rows.add(new ListRow(channel.group, null, y, GROUP_H));
                        y += GROUP_H;
                    }
                }

                if (this.collapsed.contains(channel.group))
                {
                    continue;
                }

                this.rows.add(new ListRow(null, channel, y, ROW_H));
                y += ROW_H;
            }

            /* Clamp the scroll to the laid-out content: without an upper bound the wheel kept
             * pushing rows past the top until the panel was blank. */
            int content = y + this.listScroll;
            int max = Math.max(0, content - this.listArea.h);

            if (this.listScroll > max)
            {
                this.listScroll = max;
                this.buildRows();
            }
        }

        private CompDocument doc()
        {
            return UICompCurveEditor.this.panel.getDocument();
        }

        private float fps()
        {
            CompDocument doc = this.doc();

            return doc == null ? 24F : Math.max(1F, doc.fps);
        }

        private float frame()
        {
            return UICompCurveEditor.this.panel.getFrame();
        }

        /* Axis mapping */

        private float pxPerSecond()
        {
            return (float) (this.curveArea.w / Math.max(0.0001D, this.viewDuration));
        }

        private float toX(double seconds)
        {
            return (float) (this.curveArea.x + (seconds - this.viewStart) * this.pxPerSecond());
        }

        private double fromX(float x)
        {
            return this.viewStart + (x - this.curveArea.x) / Math.max(0.0001F, this.pxPerSecond());
        }

        /* Cached axis bounds: toY() runs per sampled pixel per curve per frame, so building a
         * float[] for the range there would allocate thousands of arrays every frame. */
        private float axisMin = -100F;
        private float axisMax = 100F;

        private void updateAxis()
        {
            this.axisMin = this.speedMode ? this.speedMin : this.valueMin;
            this.axisMax = this.speedMode ? this.speedMax : this.valueMax;

            if (this.axisMax - this.axisMin < 0.0001F)
            {
                this.axisMax = this.axisMin + 1F;
            }
        }

        private float toY(float value)
        {
            float span = Math.max(0.0001F, this.axisMax - this.axisMin);

            return this.curveArea.ey() - (value - this.axisMin) / span * this.curveArea.h;
        }

        private float fromY(float y)
        {
            float span = Math.max(0.0001F, this.axisMax - this.axisMin);

            return this.axisMin + (this.curveArea.ey() - y) / Math.max(1F, this.curveArea.h) * span;
        }

        /* View commands */

        private void fitTime()
        {
            CompDocument doc = this.doc();

            this.viewStart = 0D;
            this.viewDuration = doc == null ? 5D : Math.max(0.2D, doc.duration);
        }

        private void fitValue()
        {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;

            /* The active channel defines the value axis, but when an axis is selected its sibling
             * dimensions are visible as ghost curves (AE's separate-dimensions view). Fitting only
             * the active axis can clip Y/Z entirely, so the family participates in the fit. */
            List<CompCurveChannel> source;

            if (this.soloActive && this.active != null && this.active.hasKeys())
            {
                source = new ArrayList<>();
                source.add(this.active);

                if (this.active.transform)
                {
                    for (CompCurveChannel channel : this.channels)
                    {
                        if (channel != this.active && channel.transform && channel.hasKeys()
                            && this.isVisible(channel)
                            && this.sameAxisFamily(this.active.axis, channel.axis))
                        {
                            source.add(channel);
                        }
                    }
                }
            }
            else
            {
                source = this.visibleChannels();
            }

            for (CompCurveChannel channel : source)
            {
                if (!this.isVisible(channel))
                {
                    continue;
                }

                for (CompCurveChannel.Key key : channel.keys())
                {
                    float value = this.displayValue(channel, key.raw, key.value, key.tick);

                    min = Math.min(min, value);
                    max = Math.max(max, value);

                    /* A bezier can leave the key's value range: the handles are control points,
                     * and a pulled tangent is often outside the raw key values. Fitting only the
                     * keys clipped the very handles the user was trying to drag (the curve looked
                     * flat or vanished at the top/bottom edge). */
                    if (!this.speedMode && key.raw != null)
                    {
                        float handleOut = value + key.raw.ry;
                        float handleIn = value + key.raw.ly;

                        if (Float.isFinite(handleOut))
                        {
                            min = Math.min(min, handleOut);
                            max = Math.max(max, handleOut);
                        }

                        if (Float.isFinite(handleIn))
                        {
                            min = Math.min(min, handleIn);
                            max = Math.max(max, handleIn);
                        }
                    }
                }
            }

            if (min == Float.MAX_VALUE)
            {
                /* No keys anywhere: frame the active channel's current value instead of a
                 * meaningless -100…100. An opacity curve belongs around 0…1, a position curve
                 * around its own X/Y — either way the flat line must be visible. */
                float value = this.active == null ? 0F : this.active.valueAt(this.frame());
                float half = Math.max(this.speedMode ? 10F : 1F, Math.abs(value) * 0.5F);

                min = value - half;
                max = value + half;
            }

            float span = Math.max(this.speedMode ? 10F : 1F, max - min);
            float pad = span * 0.15F;

            if (this.speedMode)
            {
                this.speedMin = min - pad;
                this.speedMax = max + pad;
            }
            else
            {
                this.valueMin = min - pad;
                this.valueMax = max + pad;
            }
        }

        private void fitAll()
        {
            this.fitTime();
            this.fitValue();
        }

        void focusChannel(String path)
        {
            if (path == null || path.isEmpty())
            {
                return;
            }

            this.rebuild();

            String id = "scalar:" + path;

            for (CompCurveChannel channel : this.channels)
            {
                if (channel.id.equals(id) || channel.id.equals(path))
                {
                    this.active = channel;
                    this.visible.add(channel.id);
                    this.collapsed.remove(channel.group);
                    this.rowsDirty = true;
                    this.invalidateGeometry();
                    this.fitValue();
                    this.revealActiveRow();

                    return;
                }
            }
        }

        void debugSelectFirstKey()
        {
            if (this.active == null || !this.active.hasKeys())
            {
                return;
            }

            Keyframe<?> key = this.active.keys().get(0).raw;

            this.selection.clear();
            this.selection.add(key);
            this.fitValue();
        }

        void debugMakeFirstBezier()
        {
            if (this.active == null || !this.active.hasKeys())
            {
                return;
            }

            Keyframe<?> key = this.active.keys().get(0).raw;

            this.selection.clear();
            this.selection.add(key);
            CompCurves.ensureBezierHandles(this.active.raw(), key);
            key.getInterpolation().setInterp(Interpolations.BEZIER);
            this.fitValue();
        }

        private void ensureView()
        {
            if (this.viewDuration <= 0.0001D)
            {
                this.fitTime();
            }
        }

        private List<CompCurveChannel> visibleChannels()
        {
            return this.channels;
        }

        private boolean isVisible(CompCurveChannel channel)
        {
            return this.visible.contains(channel.id) || channel == this.active;
        }

        /* Editing */

        private void beginEdit()
        {
            UICompCurveEditor.this.panel.beginCurveAction();
        }

        private void endEdit()
        {
            UICompCurveEditor.this.panel.curveChanged(true);
        }

        /** Continuous gesture (drag): the panel must not rebuild its inspector on release. */
        private void endDragEdit()
        {
            UICompCurveEditor.this.panel.curveChanged(false);
        }

        private void applyEase(int mode)
        {
            if (this.active == null)
            {
                UICompCurveEditor.this.panel.setStatus("先在左侧选择一个属性通道");
                return;
            }

            List<Keyframe> keys = new ArrayList<>();

            for (Keyframe<?> key : this.selection)
            {
                if (this.channelOf(key) == this.active)
                {
                    keys.add(key);
                }
            }

            if (keys.isEmpty())
            {
                UICompCurveEditor.this.panel.setStatus("先在曲线上选中关键帧，再用缓动预设");
                return;
            }

            this.beginEdit();

            switch (mode)
            {
                case 0 -> CompCurves.applyLinear(this.active.raw(), keys);
                case 1 -> CompCurves.applyBezier(this.active.raw(), keys);
                case 2 -> CompCurves.applyEase(this.active.raw(), keys, CompCurves.EASE_BOTH, this.influence);
                case 3 -> CompCurves.applyEase(this.active.raw(), keys, CompCurves.EASE_IN, this.influence);
                case 4 -> CompCurves.applyEase(this.active.raw(), keys, CompCurves.EASE_OUT, this.influence);
                case 5 -> CompCurves.applyHold(this.active.raw(), keys);
                case 6 -> CompCurves.applyAuto(this.active.raw(), keys, true);
                case 7 -> CompCurves.applyContinuous(this.active.raw(), keys, (key) -> this.active.numericValue(key));
                default -> {}
            }

            this.active.touch();
            this.endEdit();
        }

        private List<Keyframe> selectedRawKeys()
        {
            List<Keyframe> keys = new ArrayList<>();

            for (Keyframe<?> key : this.selection)
            {
                keys.add(key);
            }

            return keys;
        }

        /** Adds a key for the active channel at a graph position (double-click target). */
        private void addKeyAt(int mouseX, int mouseY)
        {
            if (this.active == null)
            {
                return;
            }

            float tick = this.snapping
                ? Math.round((float) this.fromX(mouseX) * this.fps())
                : (float) this.fromX(mouseX) * this.fps();
            float graphValue = this.fromY(mouseY);

            tick = Math.max(0F, tick);

            this.beginEdit();

            /* In the value graph the click sets the value. In the speed graph the click sets the
             * key's tangent speed, so the value itself must stay on the existing curve. */
            Keyframe<?> key = this.speedMode
                ? this.active.insert(tick, this.active.valueAt(tick))
                : this.active.insert(tick, graphValue);

            if (key != null && this.speedMode)
            {
                this.applySpeed(this.active, key, graphValue);
            }

            if (key != null)
            {
                CompCurves.ensureBezierHandles(this.active.raw(), key);
                this.selection.clear();
                this.selection.add(key);
            }

            this.endEdit();
        }

        private void addKeyAtPlayhead()
        {
            if (this.active == null)
            {
                return;
            }

            this.beginEdit();

            Keyframe<?> key = this.active.insert(this.frame(), this.active.valueAt(this.frame()));

            if (key != null)
            {
                CompCurves.ensureBezierHandles(this.active.raw(), key);
                this.selection.clear();
                this.selection.add(key);
            }

            this.endEdit();
        }

        private void deleteSelected()
        {
            if (this.selection.isEmpty())
            {
                return;
            }

            this.beginEdit();

            for (Keyframe<?> key : new ArrayList<>(this.selection))
            {
                for (CompCurveChannel channel : this.channels)
                {
                    if (channel.owns(key))
                    {
                        channel.remove(key);
                        break;
                    }
                }
            }

            this.selection.clear();
            this.endEdit();
        }

        /**
         * Arrow-key nudge, AE's fine adjustment: one frame horizontally, one graph pixel
         * vertically. Shift multiplies both by ten. Time and value changes are one undo step per
         * keypress, so a mistake is one Ctrl+Z away.
         */
        private void nudgeSelected(int frames, int pixels)
        {
            if (this.selection.isEmpty())
            {
                return;
            }

            float valueStep = (this.axisMax - this.axisMin) / Math.max(1, this.curveArea.h) * pixels;

            this.beginEdit();

            java.util.Set<CompCurveChannel> edited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

            for (Keyframe<?> key : this.selection)
            {
                CompCurveChannel channel = this.channelOf(key);

                if (channel == null)
                {
                    continue;
                }

                edited.add(channel);
                key.setTick(Math.max(0F, key.getTick() + frames), false);

                if (this.speedMode)
                {
                    this.applySpeed(channel, key, this.keySpeed(channel, key, true) + valueStep);
                }
                else
                {
                    channel.setKeyValue(key, channel.numericValue(key) + valueStep);
                }
            }

            for (CompCurveChannel channel : edited)
            {
                this.sortIfNeeded(channel);
            }

            if (this.active != null)
            {
                this.markSelectionDirty(this.active);
            }

            this.endEdit();
        }

        /* Rendering */

        @Override
        public void render(UIContext context)
        {
            this.ensureView();

            if (this.layer != UICompCurveEditor.this.panel.getSelectedLayer())
            {
                this.rebuild();
            }

            this.updateAreas();
            this.updateAxis();
            this.handleDrag(context);

            this.renderSurfaces(context);
            this.renderList(context);
            this.renderGrid(context);
            this.renderCurves(context);
            this.renderKeys(context);
            this.renderHandles(context);
            this.renderPlayhead(context);
            this.renderMarquee(context);
            this.renderReadout(context);
            this.renderEmptyState(context);

            super.render(context);
            this.updateCursor(context);
        }

        private void updateAreas()
        {
            /* Never let the graph collapse to a zero or negative rectangle: a negative clip
             * silently erases every child draw, which is exactly how the panel used to go blank
             * after a page switch. The clamp is one compare per frame. */
            int width = Math.max(1, this.area.w);
            int height = Math.max(1, this.area.h);
            int listWidth = Math.max(0, Math.min(LIST_W, width / 2));

            this.listArea.set(this.area.x, this.area.y, listWidth, height);
            this.curveArea.set(this.listArea.ex(), this.area.y + RULER_H,
                Math.max(1, width - listWidth), Math.max(1, height - RULER_H));
            this.rulerArea.set(this.curveArea.x, this.area.y, this.curveArea.w, RULER_H);
        }

        private void renderSurfaces(UIContext context)
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());
            context.batcher.box(this.listArea.x, this.listArea.y, this.listArea.ex(), this.listArea.ey(), BBSSettings.baseSurface());
            context.batcher.box(this.listArea.ex() - 1, this.listArea.y, this.listArea.ex(), this.listArea.ey(), BBSSettings.dividerColor());
            context.batcher.box(this.curveArea.x, this.curveArea.y, this.curveArea.ex(), this.curveArea.ey(), 0xFF0D1015);

            CompTimelineRuler.render(context, this.rulerArea, this.viewStart, this.viewStart + this.viewDuration, (double value) -> (int) this.toX(value));
        }

        private void renderList(UIContext context)
        {
            context.batcher.clip(this.listArea, context);

            try
            {
                if (this.rowsDirty)
                {
                    this.buildRows();
                }

                for (ListRow row : this.rows)
                {
                    int y = this.listArea.y + row.y;

                    if (y + row.height < this.listArea.y || y > this.listArea.ey())
                    {
                        continue;
                    }

                    if (row.channel == null)
                    {
                        context.batcher.box(this.listArea.x, y, this.listArea.ex() - 1, y + row.height, 0xFF1B212B);
                        context.batcher.textShadow(row.group, this.listArea.x + 6, y + 3, 0xFF7C8798);
                        continue;
                    }

                    boolean isActive = row.channel == this.active;
                    boolean isVisible = this.isVisible(row.channel);

                    if (isActive)
                    {
                        context.batcher.box(this.listArea.x, y, this.listArea.ex() - 1, y + row.height, 0xFF223047);
                    }

                    int chip = isVisible ? row.channel.color : Colors.setA(row.channel.color, 0.25F);

                    context.batcher.box(this.listArea.x + 7, y + 5, this.listArea.x + 13, y + 11, chip);

                    if (!row.channel.hasKeys())
                    {
                        context.batcher.box(this.listArea.x + 7, y + 5, this.listArea.x + 13, y + 11, 0x33000000);
                    }

                    int color = isActive ? 0xFFFFFFFF : (isVisible ? 0xFFC3CCDA : 0xFF6E7787);

                    context.batcher.textShadow(this.trim(row.channel.label, 16), this.listArea.x + 19, y + 4, color);
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        private String trim(String text, int length)
        {
            if (text == null)
            {
                return "";
            }

            return text.length() <= length ? text : text.substring(0, length - 1) + "…";
        }

        private void renderGrid(UIContext context)
        {
            context.batcher.clip(this.curveArea, context);

            try
            {
                double step = CompTimelineRuler.majorStep(this.pxPerSecond());
                double first = Math.floor(this.viewStart / step) * step;
                int guard = 0;

                for (double time = first; time <= this.viewStart + this.viewDuration && guard < 400; time += step, guard++)
                {
                    if (time < 0D)
                    {
                        continue;
                    }

                    int x = (int) this.toX(time);

                    context.batcher.box(x, this.curveArea.y, x + 1, this.curveArea.ey(), 0x18FFFFFF);
                }

                float span = this.axisMax - this.axisMin;
                float valueStep = this.niceStep(span / 6F);
                float start = (float) Math.ceil(this.axisMin / valueStep) * valueStep;

                /* The labels depend only on the value range, so they are formatted once per
                 * view instead of once per line per frame. */
                this.ensureAxisLabels(valueStep, start);

                for (int i = 0; i < this.axisLabelCount; i++)
                {
                    float value = this.axisLabelValues[i];
                    int y = (int) this.toY(value);

                    context.batcher.box(this.curveArea.x, y, this.curveArea.ex(), y + 1, 0x14FFFFFF);
                    context.batcher.textShadow(this.axisLabelTexts[i], this.curveArea.x + 4, y - 8, 0x667C8798);
                }

                /* Zero line: AE marks it because overshoot reads against it. */
                if (this.axisMin < 0F && this.axisMax > 0F)
                {
                    int y = (int) this.toY(0F);

                    context.batcher.box(this.curveArea.x, y, this.curveArea.ex(), y + 1, 0x33FFFFFF);
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        private int axisLabelCount;

        private void ensureAxisLabels(float step, float start)
        {
            long key = ((long) Float.floatToIntBits(this.axisMin) << 32)
                ^ (Float.floatToIntBits(this.axisMax) * 0x9E3779B1L)
                ^ (Float.floatToIntBits(step) * 31L);

            if (key == this.axisLabelKey && this.axisLabelCount > 0)
            {
                return;
            }

            this.axisLabelKey = key;

            int count = 0;

            for (float value = start; value <= this.axisMax && count < 64; value += step)
            {
                count++;
            }

            if (this.axisLabelValues.length < count)
            {
                this.axisLabelValues = new float[count];
                this.axisLabelTexts = new String[count];
            }

            int index = 0;

            for (float value = start; value <= this.axisMax && index < count; value += step)
            {
                this.axisLabelValues[index] = value;
                this.axisLabelTexts[index] = this.formatAxis(value);
                index++;
            }

            this.axisLabelCount = index;
        }

        private float niceStep(float raw)
        {
            if (raw <= 0.00001F)
            {
                return 1F;
            }

            double exponent = Math.floor(Math.log10(raw));
            double base = Math.pow(10D, exponent);
            double normalized = raw / base;

            double step = normalized <= 1.2D ? 1D : normalized <= 2.5D ? 2D : normalized <= 5D ? 5D : 10D;

            return (float) (step * base);
        }

        private String formatAxis(float value)
        {
            float size = Math.max(Math.abs(this.axisMin), Math.abs(this.axisMax));

            if (size >= 1000F) return String.format(Locale.ROOT, "%.0f", value);
            if (size >= 100F) return String.format(Locale.ROOT, "%.0f", value);
            if (size >= 10F) return String.format(Locale.ROOT, "%.1f", value);
            if (size >= 1F) return String.format(Locale.ROOT, "%.2f", value);

            return String.format(Locale.ROOT, "%.3f", value);
        }

        private void renderCurves(UIContext context)
        {
            context.batcher.clip(this.curveArea, context);

            try
            {
                /* AE's separate-dimension view: the selected axis is solid, its sibling axes are
                 * faint previews. Before independent channels the siblings were literally the same
                 * Transform keys, so this "preview" was impossible. */
                if (this.soloActive && this.active != null && this.active.transform)
                {
                    for (CompCurveChannel channel : this.channels)
                    {
                        if (channel == this.active || !channel.transform || !channel.hasKeys())
                        {
                            continue;
                        }

                        if (!this.isVisible(channel) || channel.axis == this.active.axis)
                        {
                            continue;
                        }

                        if (!this.sameAxisFamily(this.active.axis, channel.axis))
                        {
                            continue;
                        }

                        this.drawChannel(context, channel, false, 0.24F);
                    }
                }

                for (CompCurveChannel channel : this.channels)
                {
                    if (!this.isVisible(channel))
                    {
                        continue;
                    }

                    if (this.soloActive && channel != this.active)
                    {
                        continue;
                    }

                    if (!channel.hasKeys() && channel != this.active)
                    {
                        continue;
                    }

                    this.drawChannel(context, channel, channel == this.active, channel == this.active ? 1F : 0.35F);
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        private void revealActiveRow()
        {
            this.rowsDirty = true;
            this.buildRows();

            for (ListRow row : this.rows)
            {
                if (row.channel != this.active)
                {
                    continue;
                }

                if (row.y < 0)
                {
                    this.listScroll += row.y;
                }
                else if (row.y + row.height > this.listArea.h)
                {
                    this.listScroll += row.y + row.height - this.listArea.h;
                }

                this.listScroll = Math.max(0, this.listScroll);
                this.rowsDirty = true;

                return;
            }
        }

        private boolean sameAxisFamily(int a, int b)
        {
            if ((a == CompCurves.AXIS_X || a == CompCurves.AXIS_Y || a == CompCurves.AXIS_Z)
                && (b == CompCurves.AXIS_X || b == CompCurves.AXIS_Y || b == CompCurves.AXIS_Z))
            {
                return true;
            }

            if ((a == CompCurves.AXIS_ROTATION || a == CompCurves.AXIS_ROTATION_X || a == CompCurves.AXIS_ROTATION_Y)
                && (b == CompCurves.AXIS_ROTATION || b == CompCurves.AXIS_ROTATION_X || b == CompCurves.AXIS_ROTATION_Y))
            {
                return true;
            }

            return CompCurves.isScaleAxis(a) && CompCurves.isScaleAxis(b);
        }

        private void drawChannel(UIContext context, CompCurveChannel channel)
        {
            this.drawChannel(context, channel, channel == this.active, channel == this.active ? 1F : 0.35F);
        }

        private void drawChannel(UIContext context, CompCurveChannel channel, boolean active, float opacity)
        {
            int color = channel.color;
            int thickness = active ? 2 : 1;

            if (active && DEBUG_SPANS)
            {
                StringBuilder line = new StringBuilder();

                line.append("curve spans ").append(channel.id)
                    .append(" area=").append(this.curveArea.x).append(',').append(this.curveArea.y)
                    .append(' ').append(this.curveArea.w).append('x').append(this.curveArea.h)
                    .append(" view=").append(this.viewStart).append('+').append(this.viewDuration)
                    .append(" axis=").append(this.axisMin).append("..").append(this.axisMax)
                    .append(" fps=").append(this.fps())
                    .append(" keys=").append(channel.raw().getKeyframes().size());

                dev.bbsae.BbsAeAddon.LOGGER.info(line.toString());
            }

            /* A 3–4 px translucent halo under the active curve. The old painter filled one integer
             * rectangle per column; the coverage rasterizer below gives the core a soft edge and
             * this halo makes it read as an AE graph instead of a Minecraft 1 px strip. */
            if (active && CompPrefs.get().glow)
            {
                this.drawSmoothCurve(context, channel, color, 0.10F, 4.4F);
            }

            this.drawSmoothCurve(context, channel, color, opacity, thickness);
        }

        /**
         * Anti-aliased curve rasterizer.
         *
         * <p>At every screen column this samples the real function and writes row coverage for the
         * vertical segment between the previous and current value. Top/bottom edges fade over one
         * pixel, which removes the staircase without introducing a shader, framebuffer or any GL
         * state change — the same BBS batcher and the same clip rectangle.</p>
         */
        private void drawSmoothCurve(UIContext context, CompCurveChannel channel, int color, float opacity, float thickness)
        {
            int columns = Math.max(1, this.curveArea.w);
            float top = this.curveArea.y - 2F;
            float bottom = this.curveArea.ey() + 2F;
            float half = Math.max(0.55F, thickness * 0.5F);
            float previous = Float.NaN;

            for (int column = 0; column < columns; column++)
            {
                float x = this.curveArea.x + column;
                float value = this.channelValueAt(channel, (float) this.fromX(x) * this.fps());
                float y = this.toY(value);

                if (Float.isNaN(y) || Float.isInfinite(y))
                {
                    previous = y;
                    continue;
                }

                y = Math.max(top, Math.min(bottom, y));

                float from = Float.isNaN(previous) ? y : previous;
                float lo = Math.min(y, from) - half;
                float hi = Math.max(y, from) + half;

                /* A hold/step can jump the whole graph for one column. Filling that as a single
                 * quad avoids a pathological 200-row AA loop while keeping the visible step. */
                if (hi - lo > 24F)
                {
                    context.batcher.box(x, lo, x + 1F, hi, Colors.setA(color, opacity));
                    previous = y;
                    continue;
                }

                int firstRow = (int) Math.floor(lo) - 1;
                int lastRow = (int) Math.ceil(hi) + 1;

                for (int row = firstRow; row <= lastRow; row++)
                {
                    /* Exact coverage of this pixel by the segment (x, y0) → (x+1, y1). The old
                     * vertical-span fill still produced one-pixel stair edges; distance to the
                     * segment antialiases both axes, which is what a real AE graph looks like. */
                    float distance = distanceToSegment(
                        x + 0.5F, row + 0.5F,
                        x, from, x + 1F, y
                    );
                    float coverage = Math.min(1F, Math.max(0F, half + 0.5F - distance));

                    if (coverage <= 0.01F)
                    {
                        continue;
                    }

                    context.batcher.box(x, row, x + 1F, row + 1F,
                        Colors.setA(color, opacity * coverage));
                }

                previous = y;
            }
        }

        private static float distanceToSegment(float px, float py, float x0, float y0, float x1, float y1)
        {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float length = dx * dx + dy * dy;
            float t = length < 0.000001F ? 0F : ((px - x0) * dx + (py - y0) * dy) / length;

            t = Math.max(0F, Math.min(1F, t));

            float qx = x0 + t * dx;
            float qy = y0 + t * dy;
            float ex = px - qx;
            float ey = py - qy;

            return (float) Math.sqrt(ex * ex + ey * ey);
        }

        /**
         * Per-column geometry of a channel, rebuilt every frame.
         *
         * <h2>Why the cache is gone</h2>
         * <p>This used to keep the column array across frames and rebuild only the columns touched
         * by a drag, keyed by a "view stamp" and a per-channel revision. It was fast, and it also
         * produced a curve that could be stale in ways no invariant could catch: a screenshot of a
         * freshly created channel showed the line jumping between completely unrelated values at
         * neighbouring columns, while the same data sampled headlessly was perfectly smooth. A
         * cache whose invalidation cannot be proven is a bug factory in a renderer.</p>
         *
         * <p>The cost of not caching is bounded by the graph's <b>width</b>, not by the data:
         * 800 columns × one binary search over the channel's keys is a fraction of a millisecond,
         * against one full-screen GL pass. Correctness that costs 0.2 ms is the right trade.</p>
         */
        private CurveSpans spansFor(CompCurveChannel channel)
        {
            CurveSpans spans = this.spanCache.get(channel.id);

            if (spans == null)
            {
                spans = new CurveSpans();
                this.spanCache.put(channel.id, spans);
            }

            int columns = Math.max(1, this.curveArea.w);

            spans.ensure(columns);
            spans.clear();
            spans.build(0, columns - 1, this.curveArea.y, this.sampler(channel));

            return spans;
        }

        private CurveSpans.Sampler sampler(CompCurveChannel channel)
        {
            final float fps = this.fps();
            final float originX = this.curveArea.x;
            final Transform fallback = channel.transform ? this.layer.defaultTransform() : null;
            final int top = this.curveArea.y;
            final int bottom = Math.max(top + 1, this.curveArea.ey() - 1);

            return (column) ->
            {
                double seconds = this.fromX(originX + column);
                float value = this.channelValueAt(channel, (float) seconds * fps, fallback);
                float y = this.toY(value);

                /* Absolute graph Y — the same space renderKeys() draws the dots in. CurveSpans
                 * subtracts the graph origin once (see its class doc); returning a relative Y here
                 * while the painter added the origin back is exactly the bug that hid every curve
                 * line behind the clip and left only the dots.
                 *
                 * Clamped to the graph rectangle: a value outside the current axis range (a stale
                 * axis after a channel switch, or a speed spike) used to be stored as an
                 * out-of-rectangle span and silently clipped away. AE pins such a curve to the edge
                 * of the graph, which is also the more useful information: "off the top". */
                if (Float.isNaN(y) || Float.isInfinite(y))
                {
                    return bottom;
                }

                return Math.max(top, Math.min(bottom, Math.round(y)));
            };
        }

        /** Value shown on the y axis: the property value, or its derivative in speed mode. */
        private float channelValueAt(CompCurveChannel channel, float frame)
        {
            return this.channelValueAt(channel, frame, null);
        }

        private float channelValueAt(CompCurveChannel channel, float frame, Transform fallback)
        {
            if (!this.speedMode)
            {
                return channel.valueAt(frame, fallback);
            }

            float a = channel.valueAt(frame - 0.5F, fallback);
            float b = channel.valueAt(frame + 0.5F, fallback);

            return (b - a) * this.fps();
        }

        private float displayValue(CompCurveChannel channel, Keyframe<?> key, float value, float tick)
        {
            return this.speedMode ? this.keySpeed(channel, key, true) : value;
        }

        /** Speed of the outgoing (or incoming) tangent of a key, in value units per second. */
        private float keySpeed(CompCurveChannel channel, Keyframe<?> key, boolean outgoing)
        {
            float rx = outgoing ? key.rx : key.lx;
            float ry = outgoing ? key.ry : key.ly;

            if (Math.abs(rx) < 0.00001F)
            {
                return 0F;
            }

            return (outgoing ? ry : -ry) / rx * this.fps();
        }

        private void renderKeys(UIContext context)
        {
            context.batcher.clip(this.curveArea, context);

            try
            {
                for (CompCurveChannel channel : this.channels)
                {
                    if (!this.isVisible(channel) || !channel.hasKeys())
                    {
                        continue;
                    }

                    boolean ghost = false;

                    if (this.soloActive && channel != this.active)
                    {
                        ghost = this.active != null && this.active.transform && channel.transform
                            && this.sameAxisFamily(this.active.axis, channel.axis);

                        if (!ghost)
                        {
                            continue;
                        }
                    }

                    boolean isActive = channel == this.active;

                    for (Object entry : channel.raw().getKeyframes())
                    {
                        Keyframe<?> raw = (Keyframe<?>) entry;
                        float x = this.toX(raw.getTick() / this.fps());

                        /* Cull before touching the value: a 500-key channel must not evaluate
                         * keys that are outside the viewport. */
                        if (x < this.curveArea.x - 8 || x > this.curveArea.ex() + 8)
                        {
                            continue;
                        }

                        float value = this.speedMode ? this.keySpeed(channel, raw, true) : channel.numericValue(raw);
                        float y = this.toY(value);
                        boolean selected = this.selection.contains(raw);
                        int color = selected ? 0xFFFFFFFF
                            : ghost ? Colors.setA(channel.color, 0.30F)
                            : (isActive ? channel.color : Colors.setA(channel.color, 0.6F));
                        int outline = selected ? 0xFF000000 : (ghost ? 0x33000000 : 0xAA000000);

                        this.drawKeyShape(context, x, y, raw, color, outline);
                    }
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        /** AE's keyframe glyphs: square = linear, circle = bezier, diamond = hold. */
        private void drawKeyShape(UIContext context, float x, float y, Keyframe<?> key, int color, int outline)
        {
            boolean hold = key.getInterpolation().has(Interpolations.CONST);
            boolean bezier = key.getInterpolation().has(Interpolations.BEZIER)
                || key.getInterpolation().has(Interpolations.AUTO)
                || key.getInterpolation().has(Interpolations.AUTO_CLAMPED);

            if (hold)
            {
                for (int i = -3; i <= 3; i++)
                {
                    int w = 3 - Math.abs(i);

                    context.batcher.box(x - w, y + i, x + w + 1, y + i + 1, color);
                }

                return;
            }

            if (bezier)
            {
                this.drawDisc(context, x, y, 3.4F, color, outline);

                return;
            }

            context.batcher.box(x - 3, y - 3, x + 3, y + 3, outline);
            context.batcher.box(x - 2, y - 2, x + 2, y + 2, color);
        }

        /** A rounded AE key dot. Rows are fractional-width rectangles, so it reads as a disc even
         * on the blocky Minecraft UI rather than as a 6×6 square. */
        private void drawDisc(UIContext context, float cx, float cy, float radius, int color, int outline)
        {
            if (outline != 0)
            {
                this.drawDiscRows(context, cx, cy, radius + 1.1F, outline);
            }

            this.drawDiscRows(context, cx, cy, radius, color);
        }

        private void drawDiscRows(UIContext context, float cx, float cy, float radius, int color)
        {
            int ir = (int) Math.ceil(radius);

            for (int dy = -ir; dy <= ir; dy++)
            {
                float half = (float) Math.sqrt(Math.max(0F, radius * radius - dy * dy));

                if (half <= 0.01F)
                {
                    continue;
                }

                float edge = Math.abs(dy) >= radius - 0.5F ? 0.65F : 1F;

                context.batcher.box(cx - half, cy + dy, cx + half, cy + dy + 1,
                    Colors.setA(color, (color >>> 24) / 255F * edge));
            }
        }

        private void renderHandles(UIContext context)
        {
            CompCurveChannel channel = this.active;

            if (channel == null || this.speedMode)
            {
                return;
            }

            context.batcher.clip(this.curveArea, context);

            try
            {
                float pxPerFrame = this.pxPerSecond() / this.fps();
                float pxPerValue = Math.abs(this.toY(0F) - this.toY(1F));

                for (Object entry : channel.raw().getKeyframes())
                {
                    Keyframe<?> key = (Keyframe<?>) entry;

                    if (!this.selection.contains(key))
                    {
                        continue;
                    }

                    float x = this.toX(key.getTick() / this.fps());
                    float y = this.toY(channel.numericValue(key));
                    float hx = x + key.rx * pxPerFrame;
                    float hy = y - key.ry * pxPerValue;

                    this.line(context, x, y, hx, hy, 0xAAE8C86A, 1);
                    this.drawDisc(context, hx, hy, 3.4F, 0xFFE8C86A, 0xFF0D1015);

                    hx = x - key.lx * pxPerFrame;
                    hy = y - key.ly * pxPerValue;

                    this.line(context, x, y, hx, hy, 0xAAE8C86A, 1);
                    this.drawDisc(context, hx, hy, 3.4F, 0xFFE8C86A, 0xFF0D1015);
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        private void renderPlayhead(UIContext context)
        {
            float x = this.toX(UICompCurveEditor.this.panel.getTime());

            if (x < this.curveArea.x || x > this.curveArea.ex())
            {
                return;
            }

            context.batcher.box(x, this.area.y, x + 1, this.curveArea.ey(), 0xFFFF5A5A);

            for (int i = -4; i <= 4; i++)
            {
                int w = 4 - Math.abs(i);

                context.batcher.box(x - w, this.area.y, x + w + 1, this.area.y + 4, 0xFFFF5A5A);
            }
        }

        private void renderMarquee(UIContext context)
        {
            if (this.dragMode != DRAG_MARQUEE)
            {
                return;
            }

            int x1 = Math.min(this.marqueeX, this.dragStartX);
            int y1 = Math.min(this.marqueeY, this.dragStartY);
            int x2 = Math.max(this.marqueeX, this.dragStartX);
            int y2 = Math.max(this.marqueeY, this.dragStartY);

            context.batcher.box(x1, y1, x2, y2, 0x2255AAFF);
            context.batcher.outline(x1, y1, x2, y2, 0xAA55AAFF);
        }

        private void renderReadout(UIContext context)
        {
            CompCurveChannel channel = this.active;

            if (channel == null)
            {
                return;
            }

            int frame = Math.round(this.frame());

            /* The card is a function of (channel, frame, mode); rebuilding the string every
             * display frame was pure garbage. The playhead only moves at the comp frame rate. */
            if (frame != this.readoutFrame || channel != this.readoutChannel)
            {
                this.readoutFrame = frame;
                this.readoutChannel = channel;
                this.readoutText = this.speedMode
                    ? channel.label + "  " + String.format(Locale.ROOT, "%.1f /s", this.channelValueAt(channel, this.frame()))
                    : channel.label + "  " + channel.format(channel.valueAt(this.frame()));
            }

            context.batcher.textCard(this.readoutText, this.curveArea.x + 6, this.curveArea.y + 4, 0xFFE6ECF5, 0x99000000, 3F);

            /* AE always tells you which key you are editing, not only what the playhead is at.
             * Without this, selecting a key and reading the playhead card looked like the key had
             * that value — the editor and the preview appeared to disagree. */
            if (!this.selection.isEmpty())
            {
                Keyframe<?> first = this.selection.iterator().next();
                CompCurveChannel selectedChannel = this.channelOf(first);

                if (selectedChannel != null && first != null)
                {
                    String selectedText = String.format(Locale.ROOT, "已选 %d · %s  %.2fs  %s  %s",
                        this.selection.size(),
                        this.trim(selectedChannel.label, 18),
                        first.getTick() / Math.max(1F, this.fps()),
                        selectedChannel.format(selectedChannel.numericValue(first)),
                        this.interpolationName(first));

                    context.batcher.textCard(selectedText, this.curveArea.x + 6, this.curveArea.y + 25,
                        0xFFE8C86A, 0x99000000, 3F);
                }
            }

            /* Cursor crosshair: AE's graph editor always tells you what the point under the
             * mouse is worth. Without it, placing a key by eye is guesswork. */
            int mouseX = context.mouseX;
            int mouseY = context.mouseY;

            if (mouseX >= this.curveArea.x && mouseX <= this.curveArea.ex()
                && mouseY >= this.curveArea.y && mouseY <= this.curveArea.ey()
                && this.dragMode == 0)
            {
                double seconds = this.fromX(mouseX);
                float value = this.channelValueAt(channel, (float) seconds * this.fps());
                String readout = String.format(Locale.ROOT, "%.2fs  %s", seconds, channel.format(value));

                context.batcher.box(mouseX, this.curveArea.y, mouseX + 1, this.curveArea.ey(), 0x55FFFFFF);
                context.batcher.textCard(readout, Math.min(mouseX + 8, this.curveArea.ex() - 96), this.curveArea.y + 4, 0xFFB9C6D8, 0x99000000, 3F);
            }

            String hint = this.selection.isEmpty()
                ? "双击空白加键 · 拖手柄改切线 · Alt 断开手柄 · 中键/Space 拖动平移"
                : ("已选 " + this.selection.size() + " 个关键帧 · 拖手柄改切线 · Alt 断开 · 中键/Space 平移");

            context.batcher.textShadow(hint, this.curveArea.x + 6, this.curveArea.ey() - 14, 0x887C8798);
        }

        private String interpolationName(Keyframe<?> key)
        {
            if (key.getInterpolation().has(Interpolations.CONST)) return "定格";
            if (key.getInterpolation().has(Interpolations.LINEAR)) return "线性";
            if (key.getInterpolation().has(Interpolations.AUTO_CLAMPED)) return "自动钳制";
            if (key.getInterpolation().has(Interpolations.AUTO)) return "自动贝塞尔";
            if (key.getInterpolation().has(Interpolations.BEZIER)) return "贝塞尔";

            return key.getInterpolation().getKey();
        }

        private void renderEmptyState(UIContext context)
        {
            if (this.layer != null && !this.channels.isEmpty())
            {
                return;
            }

            String message = this.layer == null ? "选择一条轨道后编辑曲线" : "该轨道还没有可动画的属性";

            context.batcher.textShadow(message, this.curveArea.x + this.curveArea.w / 2F - 80, this.curveArea.y + this.curveArea.h / 2F, 0x88FFFFFF);
        }

        /**
         * A short straight segment, drawn one rectangle per screen column.
         *
         * <p>Walking the dominant axis (the old implementation) emitted one quad per pixel of
         * <em>slope</em>, so a steep handle cost hundreds of quads. One column per pixel of
         * horizontal extent is bounded by the length the user can actually see, and looks the
         * same at 1–2 px thickness.</p>
         */
        private void line(UIContext context, float x1, float y1, float x2, float y2, int color, int thickness)
        {
            float dx = x2 - x1;
            float dy = y2 - y1;
            float half = thickness * 0.5F;

            if (Math.abs(dx) < 0.5F)
            {
                context.batcher.box(x1 - half, Math.min(y1, y2), x1 + half + 1F, Math.max(y1, y2) + 1F, color);

                return;
            }

            int steps = Math.max(1, Math.min(4096, (int) Math.abs(dx)));

            for (int i = 0; i <= steps; i++)
            {
                float t = i / (float) steps;
                float x = x1 + dx * t;
                float y = y1 + dy * t;

                context.batcher.box(x, y - half, x + 1F, y + half + 1F, color);
            }
        }

        /* Interaction */

        private void updateCursor(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return;
            }

            if (this.dragMode == DRAG_PAN)
            {
                context.requestCursor(GLFW.GLFW_HAND_CURSOR);
            }
            else if (this.dragMode == DRAG_KEY || this.dragMode == DRAG_HANDLE)
            {
                context.requestCursor(GLFW.GLFW_HRESIZE_CURSOR);
            }
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return super.subMouseClicked(context);
            }

            if (context.mouseButton == 2 || (context.mouseButton == 0 && Window.isKeyPressed(GLFW.GLFW_KEY_SPACE)))
            {
                this.dragMode = DRAG_PAN;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.panStartView = this.viewStart;
                this.panStartValueMin = this.axisMin;
                this.panStartValueMax = this.axisMax;

                return true;
            }

            if (context.mouseButton != 0)
            {
                return super.subMouseClicked(context);
            }

            if (this.listArea.isInside(context))
            {
                this.clickList(context);

                return true;
            }

            if (this.rulerArea.isInside(context))
            {
                this.dragMode = DRAG_SCRUB;
                UICompCurveEditor.this.panel.setTime((float) this.fromX(context.mouseX));

                return true;
            }

            if (!this.curveArea.isInside(context))
            {
                return super.subMouseClicked(context);
            }

            /* Handle first: handles live on top of their key. */
            HandleRef handle = this.handleAt(context.mouseX, context.mouseY);

            if (handle != null)
            {
                this.dragMode = DRAG_HANDLE;
                this.dragHandleKey = handle.key;
                this.dragHandleOutgoing = handle.outgoing;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.dragHandleStartRx = handle.key.rx;
                this.dragHandleStartRy = handle.key.ry;
                this.dragHandleStartLx = handle.key.lx;
                this.dragHandleStartLy = handle.key.ly;
                this.actionStarted = false;

                return true;
            }

            Keyframe<?> key = this.keyAt(context.mouseX, context.mouseY);

            if (key != null)
            {
                if (this.hitChannel != null)
                {
                    this.active = this.hitChannel;
                    this.visible.add(this.hitChannel.id);
                }

                if (Window.isShiftPressed())
                {
                    if (!this.selection.remove(key))
                    {
                        this.selection.add(key);
                    }
                }
                else if (!this.selection.contains(key))
                {
                    this.selection.clear();
                    this.selection.add(key);
                }

                CompCurveChannel selectedChannel = this.channelOf(key);

                if (selectedChannel != null)
                {
                    CompCurves.ensureBezierHandles(selectedChannel.raw(), key);
                }

                this.dragMode = DRAG_KEY;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.dragMoved = false;
                this.dragStartValues.clear();
                this.dragStartTicks.clear();
                this.actionStarted = false;

                for (Keyframe<?> selected : this.selection)
                {
                    CompCurveChannel channel = this.channelOf(selected);

                    if (channel != null)
                    {
                        /* Speed graph drags move the displayed speed, so the snapshot must be the
                         * tangent speed — not the property value, which is a different axis here
                         * (grabbing a 90° key used to teleport its speed to ~90 per second). */
                        this.dragStartValues.put(selected,
                            this.speedMode ? this.keySpeed(channel, selected, true) : channel.numericValue(selected));
                        this.dragStartTicks.put(selected, selected.getTick());
                    }
                }

                return true;
            }

            /* Double click on empty graph space adds a key there, like AE's "add keyframe" on the
             * curve. It is also the only way to create the first key of a property without going
             * back to the inspector's stopwatch. */
            long now = System.currentTimeMillis();
            boolean doubleClick = now - this.lastClickTime < 320L
                && Math.abs(context.mouseX - this.lastClickX) < 4
                && Math.abs(context.mouseY - this.lastClickY) < 4;

            this.lastClickX = context.mouseX;
            this.lastClickY = context.mouseY;
            this.lastClickTime = now;

            if (doubleClick && this.active != null)
            {
                this.addKeyAt(context.mouseX, context.mouseY);

                return true;
            }

            if (Window.isShiftPressed())
            {
                this.dragMode = DRAG_MARQUEE;
                this.dragStartX = context.mouseX;
                this.dragStartY = context.mouseY;
                this.marqueeX = context.mouseX;
                this.marqueeY = context.mouseY;

                return true;
            }

            this.selection.clear();
            this.dragMode = DRAG_MARQUEE;
            this.dragStartX = context.mouseX;
            this.dragStartY = context.mouseY;
            this.marqueeX = context.mouseX;
            this.marqueeY = context.mouseY;

            return true;
        }

        private void clickList(UIContext context)
        {
            ListRow row = this.rowAt(context.mouseY);

            if (row == null)
            {
                return;
            }

            if (row.channel == null)
            {
                if (!this.collapsed.remove(row.group))
                {
                    this.collapsed.add(row.group);
                }

                this.buildRows();

                return;
            }

            if (Window.isCtrlPressed())
            {
                if (!this.visible.remove(row.channel.id))
                {
                    this.visible.add(row.channel.id);
                }

                return;
            }

            this.active = row.channel;
            this.visible.add(row.channel.id);
            this.selection.clear();

            if (!row.channel.hasKeys() && this.doc() != null)
            {
                UICompCurveEditor.this.panel.setStatus("「" + row.channel.label + "」还没有关键帧；双击图表或点「加键」创建");
            }

            this.fitValue();
        }

        private ListRow rowAt(int mouseY)
        {
            for (ListRow row : this.rows)
            {
                int y = this.listArea.y + row.y;

                if (mouseY >= y && mouseY < y + row.height)
                {
                    return row;
                }
            }

            return null;
        }

        private Keyframe<?> keyAt(int mouseX, int mouseY)
        {
            Keyframe<?> best = null;
            double bestDistance = 10D;

            this.hitChannel = null;

            for (CompCurveChannel channel : this.channels)
            {
                if (!this.isVisible(channel) || !channel.hasKeys())
                {
                    continue;
                }

                /* When only the active channel is drawn, only its keys may be clicked —
                 * otherwise an invisible key on another channel steals the click. */
                if (this.soloActive && channel != this.active)
                {
                    continue;
                }

                for (Object entry : channel.raw().getKeyframes())
                {
                    Keyframe<?> raw = (Keyframe<?>) entry;
                    float x = this.toX(raw.getTick() / this.fps());

                    if (Math.abs(mouseX - x) > bestDistance)
                    {
                        continue;
                    }

                    float value = this.speedMode ? this.keySpeed(channel, raw, true) : channel.numericValue(raw);
                    double distance = Math.hypot(mouseX - x, mouseY - this.toY(value));

                    if (distance < bestDistance)
                    {
                        bestDistance = distance;
                        best = raw;
                        this.hitChannel = channel;
                    }
                }
            }

            return best;
        }

        private final class HandleRef
        {
            final Keyframe<?> key;
            final boolean outgoing;

            HandleRef(Keyframe<?> key, boolean outgoing)
            {
                this.key = key;
                this.outgoing = outgoing;
            }
        }

        private HandleRef handleAt(int mouseX, int mouseY)
        {
            CompCurveChannel channel = this.active;

            if (channel == null || this.speedMode)
            {
                return null;
            }

            float pxPerFrame = this.pxPerSecond() / this.fps();
            float pxPerValue = Math.abs(this.toY(0F) - this.toY(1F));

            for (CompCurveChannel.Key key : channel.keys())
            {
                if (!this.selection.contains(key.raw))
                {
                    continue;
                }

                float x = this.toX(key.tick / this.fps());
                float y = this.toY(key.value);
                float rx = x + key.raw.rx * pxPerFrame;
                float ry = y - key.raw.ry * pxPerValue;

                if (Math.hypot(mouseX - rx, mouseY - ry) <= 10D)
                {
                    return new HandleRef(key.raw, true);
                }

                float lx = x - key.raw.lx * pxPerFrame;
                float ly = y - key.raw.ly * pxPerValue;

                if (Math.hypot(mouseX - lx, mouseY - ly) <= 10D)
                {
                    return new HandleRef(key.raw, false);
                }
            }

            return null;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private CompCurveChannel channelOf(Keyframe<?> key)
        {
            if (key == null)
            {
                return null;
            }

            /* Each axis now owns a separate float channel and a separate key object, so the
             * identity map is unambiguous. Keeping the old Transform special case here would
             * route every axis key back to the active channel and defeat independent editing. */
            return this.owners.get(key);
        }

        private void handleDrag(UIContext context)
        {
            if (this.dragMode == DRAG_NONE)
            {
                return;
            }

            boolean held = this.dragMode == DRAG_PAN
                ? Window.isMouseButtonPressed(2) || Window.isMouseButtonPressed(0)
                : Window.isMouseButtonPressed(0);

            if (!held)
            {
                this.finishDrag();

                return;
            }

            if (this.dragMode == DRAG_SCRUB)
            {
                UICompCurveEditor.this.panel.setTime((float) Math.max(0D, this.fromX(context.mouseX)));

                return;
            }

            if (this.dragMode == DRAG_PAN)
            {
                double delta = -(context.mouseX - this.dragStartX) / Math.max(0.0001F, this.pxPerSecond());
                float span = Math.max(0.0001F, this.panStartValueMax - this.panStartValueMin);
                float shift = (context.mouseY - this.dragStartY) / (float) Math.max(1, this.curveArea.h) * span;

                this.viewStart = Math.max(0D, this.panStartView + delta);

                /* Two-axis pan: vertical dragging moves the value range. The old handler only
                 * panned in time, so a curve that left the axis could only be recovered by
                 * Ctrl+wheel zoom, one notch at a time. */
                if (this.speedMode)
                {
                    this.speedMin = this.panStartValueMin + shift;
                    this.speedMax = this.panStartValueMax + shift;
                }
                else
                {
                    this.valueMin = this.panStartValueMin + shift;
                    this.valueMax = this.panStartValueMax + shift;
                }

                return;
            }

            if (this.dragMode == DRAG_MARQUEE)
            {
                this.marqueeX = context.mouseX;
                this.marqueeY = context.mouseY;
                this.selectInMarquee();

                return;
            }

            int dx = context.mouseX - this.dragStartX;
            int dy = context.mouseY - this.dragStartY;

            if (Math.abs(dx) > 2 || Math.abs(dy) > 2)
            {
                this.dragMoved = true;
            }

            if (!this.dragMoved)
            {
                return;
            }

            /* Undo checkpoint belongs to the gesture, not to the click: a plain click that selects a
             * key must not push a no-op snapshot onto the history. */
            if (!this.actionStarted)
            {
                this.actionStarted = true;
                this.beginEdit();
                UICompCurveEditor.this.panel.setCurveInteraction(true);
            }

            /* The curve is being edited right now: the pre-render cache must stop answering for
             * this composition, or a green frame would keep showing the old shape until the mouse
             * is released. */
            UICompCurveEditor.this.panel.markCurveDirty();

            if (this.dragMode == DRAG_HANDLE)
            {
                this.dragHandle(context, dx, dy);

                return;
            }

            this.dragKeys(context, dx, dy);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void dragHandle(UIContext context, int dx, int dy)
        {
            Keyframe key = this.dragHandleKey;

            if (key == null)
            {
                return;
            }

            CompCurveChannel channel = this.active;
            float pxPerFrame = Math.max(0.0001F, this.pxPerSecond() / this.fps());
            float pxPerValue = Math.max(0.00001F, Math.abs(this.toY(0F) - this.toY(1F)));
            float frameDelta = dx / pxPerFrame;
            float valueDelta = -dy / pxPerValue;
            List<Keyframe> keys = (List<Keyframe>) (List<?>) channel.raw().getKeyframes();
            int index = CompCurves.indexOfIdentity(keys, key);

            /* The drag is relative to the handle's state at press time. Clamping the time offset
             * to the adjacent segment is the AE rule: handles may not push the next/previous key
             * past each other, and BezierUtils clamps x1/x2 to [0,1] anyway. */
            if (this.dragHandleOutgoing)
            {
                float max = index >= 0 && index + 1 < keys.size()
                    ? Math.max(0.01F, keys.get(index + 1).getTick() - key.getTick())
                    : 1_000_000F;

                key.rx = Math.max(0.01F, Math.min(max, this.dragHandleStartRx + frameDelta));
                key.ry = this.dragHandleStartRy + valueDelta;
            }
            else
            {
                float max = index > 0
                    ? Math.max(0.01F, key.getTick() - keys.get(index - 1).getTick())
                    : 1_000_000F;

                key.lx = Math.max(0.01F, Math.min(max, this.dragHandleStartLx - frameDelta));
                key.ly = this.dragHandleStartLy + valueDelta;
            }

            if (!CompInput.altDown())
            {
                CompCurves.mirrorHandles(key, this.dragHandleOutgoing);
            }

            key.getInterpolation().setInterp(Interpolations.BEZIER);

            /* Keep the previous key's type in sync: the segment into this key is owned by it. */
            if (!this.dragHandleOutgoing && index > 0)
            {
                keys.get(index - 1).getInterpolation().setInterp(Interpolations.BEZIER);
            }

            /* Report influence and speed the way AE's keyframe dialog does. */
            Keyframe neighbor = this.dragHandleOutgoing
                ? (index >= 0 && index + 1 < keys.size() ? keys.get(index + 1) : null)
                : (index > 0 ? keys.get(index - 1) : null);
            float influence = 0F;
            float speed = 0F;

            if (neighbor != null)
            {
                float length = Math.max(0.0001F, Math.abs(neighbor.getTick() - key.getTick()));

                influence = (this.dragHandleOutgoing ? key.rx : key.lx) / length;
                speed = CompCurves.handleSpeed(key, this.dragHandleOutgoing ? key.rx : key.lx,
                    this.dragHandleOutgoing ? key.ry : key.ly, this.fps());
            }

            this.markHandleDirty(channel, key);

            UICompCurveEditor.this.panel.setStatus(String.format(
                Locale.ROOT,
                "%s  影响 %.0f%%  速度 %.1f/s",
                this.dragHandleOutgoing ? "出切线" : "入切线",
                influence * 100F,
                speed
            ));
        }

        private void dragKeys(UIContext context, int dx, int dy)
        {
            float pxPerFrame = Math.max(0.0001F, this.pxPerSecond() / this.fps());
            float pxPerValue = Math.max(0.00001F, Math.abs(this.toY(0F) - this.toY(1F)));
            float startFrame = 0F;
            float currentFrame = 0F;

            if (this.active != null && !this.selection.isEmpty())
            {
                Keyframe<?> first = this.selection.iterator().next();

                startFrame = this.dragStartTicks.getOrDefault(first, first.getTick());
                currentFrame = startFrame + dx / pxPerFrame;
            }

            if (this.snapping && this.fps() > 0F)
            {
                currentFrame = Math.round(currentFrame);
            }

            float frameDelta = currentFrame - startFrame;
            float valueDelta = -dy / pxPerValue;

            for (Keyframe<?> key : this.selection)
            {
                CompCurveChannel channel = this.channelOf(key);
                Float startValue = this.dragStartValues.get(key);
                Float startTick = this.dragStartTicks.get(key);

                if (channel == null || startValue == null || startTick == null)
                {
                    continue;
                }

                key.setTick(Math.max(0F, startTick + frameDelta), false);

                if (this.speedMode)
                {
                    float speed = startValue + valueDelta;

                    this.applySpeed(channel, key, speed);
                }
                else
                {
                    channel.setKeyValue(key, startValue + valueDelta);
                }
            }

            /* Only the channels that were actually edited can go out of order. The old code
             * sorted every channel on every mouse-move frame: with 18 channels and 500 keys that
             * was ~80k comparisons per frame, more than the rendering it was protecting. */
            java.util.Set<CompCurveChannel> edited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

            for (Keyframe<?> key : this.selection)
            {
                CompCurveChannel channel = this.channelOf(key);

                if (channel != null)
                {
                    edited.add(channel);
                }
            }

            for (CompCurveChannel channel : edited)
            {
                this.sortIfNeeded(channel);
            }

            if (this.active != null)
            {
                this.markSelectionDirty(this.active);
            }

            if (this.active != null)
            {
                UICompCurveEditor.this.panel.setStatus(String.format(
                    Locale.ROOT,
                    "%s %s  帧 %s",
                    this.active.label,
                    this.active.format(this.active.valueAt(this.frame())),
                    String.format(Locale.ROOT, "%.1f", currentFrame)
                ));
            }
        }

        /** Speed-graph key drag: move the tangent speed, keeping influence. */
        /** Sorts a channel only when the tick order actually broke; O(n) scan otherwise. */
        private void sortIfNeeded(CompCurveChannel channel)
        {
            java.util.List<?> keys = channel.raw().getKeyframes();
            float previous = -Float.MAX_VALUE;

            for (Object entry : keys)
            {
                float tick = ((Keyframe<?>) entry).getTick();

                if (tick < previous)
                {
                    channel.raw().sort();

                    return;
                }

                previous = tick;
            }
        }

        private int columnOf(double seconds)
        {
            return (int) Math.floor(this.toX(seconds) - this.curveArea.x);
        }

        /** Dirty columns covering the selection plus one neighbouring segment on each side. */
        private void markSelectionDirty(CompCurveChannel channel)
        {
            java.util.List<?> keys = channel.raw().getKeyframes();
            int size = keys.size();
            int first = -1;
            int last = -1;

            for (int i = 0; i < size; i++)
            {
                if (this.selection.contains(keys.get(i)))
                {
                    if (first < 0)
                    {
                        first = i;
                    }

                    last = i;
                }
            }

            if (first < 0)
            {
                return;
            }

            float fps = this.fps();
            int from = Math.max(0, first - 1);
            int to = Math.min(size - 1, last + 1);
            double start = ((Keyframe<?>) keys.get(from)).getTick() / fps;
            double end = ((Keyframe<?>) keys.get(to)).getTick() / fps;

            UICompCurveEditor.this.panel.setCurveDirtyRange((float) start, (float) end);
        }

        /** A handle drag changes the segment on both sides of its key. */
        private void markHandleDirty(CompCurveChannel channel, Keyframe<?> key)
        {
            java.util.List<?> keys = channel.raw().getKeyframes();
            int index = CompCurves.indexOfIdentity((java.util.List) keys, key);

            if (index < 0)
            {
                return;
            }

            float fps = this.fps();
            int from = Math.max(0, index - 1);
            int to = Math.min(keys.size() - 1, index + 1);
            double start = ((Keyframe<?>) keys.get(from)).getTick() / fps;
            double end = ((Keyframe<?>) keys.get(to)).getTick() / fps;

            UICompCurveEditor.this.panel.setCurveDirtyRange((float) start, (float) end);
        }

        private void applySpeed(CompCurveChannel channel, Keyframe<?> key, float speed)
        {
            float fps = this.fps();

            if (Math.abs(key.lx) < 0.01F && Math.abs(key.rx) < 0.01F)
            {
                key.rx = 5F;
                key.lx = 5F;
            }

            key.ry = speed * Math.max(0.0001F, key.rx) / fps;
            key.ly = -speed * Math.max(0.0001F, key.lx) / fps;
            key.getInterpolation().setInterp(Interpolations.BEZIER);
        }

        private void selectInMarquee()
        {
            int x1 = Math.min(this.marqueeX, this.dragStartX);
            int x2 = Math.max(this.marqueeX, this.dragStartX);
            int y1 = Math.min(this.marqueeY, this.dragStartY);
            int y2 = Math.max(this.marqueeY, this.dragStartY);

            this.selection.clear();

            for (CompCurveChannel channel : this.channels)
            {
                if (!this.isVisible(channel))
                {
                    continue;
                }

                /* Same rule as keyAt(): while soloing the active channel, invisible keys on the
                 * other channels must not end up in the marquee — Delete would silently remove
                 * keyframes the user cannot even see. */
                if (this.soloActive && channel != this.active)
                {
                    continue;
                }

                for (Object entry : channel.raw().getKeyframes())
                {
                    Keyframe<?> raw = (Keyframe<?>) entry;
                    float x = this.toX(raw.getTick() / this.fps());

                    if (x < x1 || x > x2)
                    {
                        continue;
                    }

                    float value = this.speedMode ? this.keySpeed(channel, raw, true) : channel.numericValue(raw);
                    float y = this.toY(value);

                    if (y >= y1 && y <= y2)
                    {
                        this.selection.add(raw);
                    }
                }
            }
        }

        private void finishDrag()
        {
            int mode = this.dragMode;

            this.dragMode = DRAG_NONE;
            this.dragHandleKey = null;

            if ((mode == DRAG_KEY || mode == DRAG_HANDLE) && this.actionStarted)
            {
                this.endDragEdit();
            }

            UICompCurveEditor.this.panel.setCurveInteraction(false);
            UICompCurveEditor.this.panel.clearCurveDirtyRange();

            this.actionStarted = false;
            this.dragMoved = false;

            if (mode == DRAG_MARQUEE)
            {
                this.selectInMarquee();
            }
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            if (!this.curveArea.isInside(context) && !this.listArea.isInside(context))
            {
                return super.subMouseScrolled(context);
            }

            this.updateAxis();

            if (this.listArea.isInside(context))
            {
                this.listScroll = Math.max(0, this.listScroll - (int) Math.signum(context.mouseWheel) * 18);
                this.buildRows();

                return true;
            }

            if (CompInput.ctrlDown())
            {
                float anchor = this.fromY(context.mouseY);
                float factor = context.mouseWheel > 0 ? 0.85F : 1.18F;

                if (this.speedMode)
                {
                    this.speedMin = anchor + (this.speedMin - anchor) * factor;
                    this.speedMax = anchor + (this.speedMax - anchor) * factor;
                }
                else
                {
                    this.valueMin = anchor + (this.valueMin - anchor) * factor;
                    this.valueMax = anchor + (this.valueMax - anchor) * factor;
                }

                return true;
            }

            if (CompInput.shiftDown())
            {
                this.viewStart = Math.max(0D, this.viewStart - Math.signum(context.mouseWheel) * this.viewDuration * 0.1D);

                return true;
            }

            double anchor = this.fromX(context.mouseX);
            double factor = context.mouseWheel > 0 ? 0.85D : 1.18D;

            this.viewDuration = Math.max(0.05D, Math.min(3600D, this.viewDuration * factor));
            this.viewStart = Math.max(0D, anchor - (anchor - this.viewStart) * factor);

            return true;
        }

        @Override
        protected boolean subKeyPressed(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return super.subKeyPressed(context);
            }

            if (context.getKeyAction() != KeyAction.PRESSED)
            {
                return super.subKeyPressed(context);
            }

            switch (context.getKeyCode())
            {
                case GLFW.GLFW_KEY_DELETE:
                case GLFW.GLFW_KEY_BACKSPACE:
                    this.deleteSelected();

                    return true;

                case GLFW.GLFW_KEY_A:
                    if (Window.isCtrlPressed())
                    {
                        this.selection.clear();

                        for (CompCurveChannel channel : this.channels)
                        {
                            if (!this.isVisible(channel))
                            {
                                continue;
                            }

                            /* Select only what is drawn: solo mode hides the other channels,
                             * and Delete acts on the whole selection. */
                            if (this.soloActive && channel != this.active)
                            {
                                continue;
                            }

                            for (CompCurveChannel.Key key : channel.keys())
                            {
                                this.selection.add(key.raw);
                            }
                        }

                        return true;
                    }
                    break;

                case GLFW.GLFW_KEY_F:
                    this.fitAll();

                    return true;

                case GLFW.GLFW_KEY_LEFT:
                case GLFW.GLFW_KEY_RIGHT:
                case GLFW.GLFW_KEY_UP:
                case GLFW.GLFW_KEY_DOWN:
                    if (!this.selection.isEmpty())
                    {
                        int step = Window.isShiftPressed() ? 10 : 1;
                        int frames = context.getKeyCode() == GLFW.GLFW_KEY_LEFT ? -step
                            : context.getKeyCode() == GLFW.GLFW_KEY_RIGHT ? step : 0;
                        int pixels = context.getKeyCode() == GLFW.GLFW_KEY_UP ? step
                            : context.getKeyCode() == GLFW.GLFW_KEY_DOWN ? -step : 0;

                        this.nudgeSelected(frames, pixels);

                        return true;
                    }

                    break;

                default:
                    break;
            }

            return super.subKeyPressed(context);
        }
    }
}
