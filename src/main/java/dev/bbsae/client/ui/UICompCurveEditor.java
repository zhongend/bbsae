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
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
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
        UIButton solo = CompUi.flat("聚焦", "只显示当前通道的曲线和关键帧；关掉后叠加显示所有可见通道（叠加时数值轴按当前通道缩放）", () ->
        {
            this.graph.soloActive = !this.graph.soloActive;
            this.graph.fitValue();
            this.panel.setStatus(this.graph.soloActive ? "曲线：只看当前通道" : "曲线：叠加所有可见通道");
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

        private int hoverRow = -1;
        private int lastClickX;
        private int lastClickY;
        private long lastClickTime;

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

        /* Data */

        private void rebuild()
        {
            CompLayer selected = UICompCurveEditor.this.panel.getSelectedLayer();

            if (selected != this.layer)
            {
                this.layer = selected;
                this.selection.clear();
                this.active = null;
                this.visible.clear();
                this.viewStart = 0D;
                this.viewDuration = Math.max(1D, UICompCurveEditor.this.panel.getDurationTicks() / 24D);
            }

            this.channels = CompCurveChannel.of(selected);

            if (this.active == null || !this.channels.contains(this.active))
            {
                this.active = this.channels.isEmpty() ? null : this.channels.get(0);
            }

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

            this.buildRows();
        }

        private void buildRows()
        {
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

            /* The active channel defines the value axis. Fitting to every visible channel made the
             * curve of interest a flat line whenever two properties had different magnitudes. */
            List<CompCurveChannel> source = this.soloActive && this.active != null && this.active.hasKeys()
                ? Collections.singletonList(this.active)
                : this.visibleChannels();

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
                }
            }

            if (min == Float.MAX_VALUE)
            {
                min = this.speedMode ? -100F : -100F;
                max = this.speedMode ? 100F : 100F;
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
            UICompCurveEditor.this.panel.curveChanged();
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
            this.listArea.set(this.area.x, this.area.y, Math.min(LIST_W, this.area.w / 2), this.area.h);
            this.curveArea.set(this.listArea.ex(), this.area.y + RULER_H, this.area.w - this.listArea.w, this.area.h - RULER_H);
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
                this.buildRows();

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

                for (float value = start; value <= this.axisMax; value += valueStep)
                {
                    int y = (int) this.toY(value);

                    context.batcher.box(this.curveArea.x, y, this.curveArea.ex(), y + 1, 0x14FFFFFF);
                    context.batcher.textShadow(this.formatAxis(value), this.curveArea.x + 4, y - 8, 0x667C8798);
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
                int step = 2;

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

                    boolean isActive = channel == this.active;
                    int color = isActive ? channel.color : Colors.setA(channel.color, 0.45F);
                    int lastX = Integer.MIN_VALUE;
                    int lastY = 0;
                    boolean started = false;

                    for (int x = this.curveArea.x; x <= this.curveArea.ex(); x += step)
                    {
                        double time = this.fromX(x);
                        float value = this.channelValueAt(channel, (float) time * this.fps());
                        int y = (int) this.toY(value);

                        if (started)
                        {
                            this.line(context, lastX, lastY, x, y, color, isActive ? 2 : 1);
                        }

                        lastX = x;
                        lastY = y;
                        started = true;
                    }
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        /** Value shown on the y axis: the property value, or its derivative in speed mode. */
        private float channelValueAt(CompCurveChannel channel, float frame)
        {
            if (!this.speedMode)
            {
                return channel.valueAt(frame);
            }

            float a = channel.valueAt(frame - 0.5F);
            float b = channel.valueAt(frame + 0.5F);

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

                    if (this.soloActive && channel != this.active)
                    {
                        continue;
                    }

                    boolean isActive = channel == this.active;

                    for (CompCurveChannel.Key key : channel.keys())
                    {
                        float value = this.speedMode ? this.keySpeed(channel, key.raw, true) : key.value;
                        float x = this.toX(key.tick / this.fps());
                        float y = this.toY(value);

                        if (x < this.curveArea.x - 8 || x > this.curveArea.ex() + 8)
                        {
                            continue;
                        }

                        boolean selected = this.selection.contains(key.raw);
                        int color = selected ? 0xFFFFFFFF : (isActive ? channel.color : Colors.setA(channel.color, 0.6F));
                        int outline = selected ? 0xFF000000 : 0xAA000000;

                        this.drawKeyShape(context, x, y, key.raw, color, outline);
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
                context.batcher.box(x - 3, y - 3, x + 3, y + 3, outline);
                context.batcher.box(x - 2, y - 2, x + 2, y + 2, color);

                return;
            }

            context.batcher.box(x - 3, y - 3, x + 3, y + 3, outline);
            context.batcher.box(x - 2, y - 2, x + 2, y + 2, color);
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
                for (CompCurveChannel.Key key : channel.keys())
                {
                    if (!this.selection.contains(key.raw))
                    {
                        continue;
                    }

                    float x = this.toX(key.tick / this.fps());
                    float y = this.toY(key.value);
                    float pxPerFrame = this.pxPerSecond() / this.fps();
                    float pxPerValue = Math.abs(this.toY(0F) - this.toY(1F));

                    float hx = x + key.raw.rx * pxPerFrame;
                    float hy = y - key.raw.ry * pxPerValue;

                    this.line(context, x, y, hx, hy, 0xAAE8C86A, 1);
                    context.batcher.box(hx - 3, hy - 3, hx + 3, hy + 3, 0xFF0D1015);
                    context.batcher.box(hx - 2, hy - 2, hx + 2, hy + 2, 0xFFE8C86A);

                    hx = x - key.raw.lx * pxPerFrame;
                    hy = y - key.raw.ly * pxPerValue;

                    this.line(context, x, y, hx, hy, 0xAAE8C86A, 1);
                    context.batcher.box(hx - 3, hy - 3, hx + 3, hy + 3, 0xFF0D1015);
                    context.batcher.box(hx - 2, hy - 2, hx + 2, hy + 2, 0xFFE8C86A);
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

            String label = channel.label + "  " + channel.format(channel.valueAt(this.frame()));

            if (this.speedMode)
            {
                label = channel.label + "  " + String.format(Locale.ROOT, "%.1f /s", this.channelValueAt(channel, this.frame()));
            }

            context.batcher.textCard(label, this.curveArea.x + 6, this.curveArea.y + 4, 0xFFE6ECF5, 0x99000000, 3F);

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

            String hint = this.selection.isEmpty() ? "双击空白加键 · 拖手柄改切线 · Alt 断开手柄" : ("已选 " + this.selection.size() + " 个关键帧");

            context.batcher.textShadow(hint, this.curveArea.x + 6, this.curveArea.ey() - 14, 0x887C8798);
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

        private void line(UIContext context, float x1, float y1, float x2, float y2, int color, int thickness)
        {
            float length = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
            int steps = Math.max(1, (int) length);

            for (int i = 0; i <= steps; i++)
            {
                float t = i / (float) steps;
                float x = x1 + (x2 - x1) * t;
                float y = y1 + (y2 - y1) * t;

                context.batcher.box(x, y, x + thickness, y + thickness, color);
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
                this.actionStarted = false;

                return true;
            }

            Keyframe<?> key = this.keyAt(context.mouseX, context.mouseY);

            if (key != null)
            {
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
                        this.dragStartValues.put(selected, channel.numericValue(selected));
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

                for (CompCurveChannel.Key key : channel.keys())
                {
                    float value = this.speedMode ? this.keySpeed(channel, key.raw, true) : key.value;
                    float x = this.toX(key.tick / this.fps());
                    float y = this.toY(value);
                    double distance = Math.hypot(mouseX - x, mouseY - y);

                    if (distance < bestDistance)
                    {
                        bestDistance = distance;
                        best = key.raw;
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

                if (Math.hypot(mouseX - rx, mouseY - ry) <= 8D)
                {
                    return new HandleRef(key.raw, true);
                }

                float lx = x - key.raw.lx * pxPerFrame;
                float ly = y - key.raw.ly * pxPerValue;

                if (Math.hypot(mouseX - lx, mouseY - ly) <= 8D)
                {
                    return new HandleRef(key.raw, false);
                }
            }

            return null;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private CompCurveChannel channelOf(Keyframe<?> key)
        {
            for (CompCurveChannel channel : this.channels)
            {
                if (channel.owns(key))
                {
                    return channel;
                }
            }

            return null;
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

                this.viewStart = Math.max(0D, this.panStartView + delta);

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
            }

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
            float offset = dx / pxPerFrame;

            if (this.dragHandleOutgoing)
            {
                key.rx = Math.max(0.25F, offset);
                key.ry = -dy / pxPerValue;
            }
            else
            {
                key.lx = Math.max(0.25F, -offset);
                key.ly = -dy / pxPerValue;
            }

            if (!Window.isAltPressed())
            {
                CompCurves.mirrorHandles(key, this.dragHandleOutgoing);
            }

            key.getInterpolation().setInterp(Interpolations.BEZIER);

            /* Keep the previous key's type in sync: the segment into this key is owned by it. */
            int index = -1;

            if (!this.dragHandleOutgoing)
            {
                List<Keyframe> keys = (List<Keyframe>) (List<?>) channel.raw().getKeyframes();
                index = keys.indexOf(key);

                if (index > 0)
                {
                    keys.get(index - 1).getInterpolation().setInterp(Interpolations.BEZIER);
                }
            }
            else
            {
                List<Keyframe> keys = (List<Keyframe>) (List<?>) channel.raw().getKeyframes();
                index = keys.indexOf(key);
            }

            /* Report influence and speed the way AE's keyframe dialog does. */
            List<Keyframe> keys = (List<Keyframe>) (List<?>) channel.raw().getKeyframes();
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

            for (CompCurveChannel channel : this.channels)
            {
                channel.raw().sort();
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

                for (CompCurveChannel.Key key : channel.keys())
                {
                    float value = this.speedMode ? this.keySpeed(channel, key.raw, true) : key.value;
                    float x = this.toX(key.tick / this.fps());
                    float y = this.toY(value);

                    if (x >= x1 && x <= x2 && y >= y1 && y <= y2)
                    {
                        this.selection.add(key.raw);
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
                this.endEdit();
            }

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

            if (Window.isCtrlPressed())
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

            if (Window.isShiftPressed())
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

                default:
                    break;
            }

            return super.subKeyPressed(context);
        }
    }
}
