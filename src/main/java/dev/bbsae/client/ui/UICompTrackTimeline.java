package dev.bbsae.client.ui;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMarker;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.renderers.EmptyStateRenderer;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The composition's real track editor.
 *
 * <p>This is the bottom half of the workspace, modelled on a film editor rather than on a property
 * dope sheet: every layer is one horizontal track, the coloured bar is the layer's in/out range,
 * the body can be dragged in time and either edge can be trimmed. Selecting the track is what
 * feeds the Effect Controls panel on the right; the property keyframe editor is an optional drawer
 * underneath, because keyframes belong to the selected track, not the other way around.</p>
 *
 * <p>It deliberately uses BBS's own {@link Scale} for the time axis, BBS colours and BBS icons, and
 * draws the ruler with {@link CompTimelineRuler}. The track bars are not keyframe widgets: BBS's
 * {@code UIKeyframeEditor} remains the only keyframe editor.</p>
 */
public class UICompTrackTimeline extends UIElement
{
    private static final int RULER_H = 20;
    private static final int ROW_MIN = 18;
    private static final int ROW_MAX = 62;
    private static final int ROW_DEFAULT = 26;
    /** Trim hit zone. 7 px is the smallest target that still feels clickable with a trackpad. */
    private static final int EDGE = 7;

    private static final int MODE_NONE = 0;
    private static final int MODE_TRIM_IN = 1;
    private static final int MODE_TRIM_OUT = 2;
    private static final int MODE_MOVE = 3;
    private static final int MODE_SCRUB = 4;
    private static final int MODE_NAVIGATE = 5;
    private static final int MODE_REORDER = 6;
    private static final int MODE_WORK_IN = 7;
    private static final int MODE_WORK_OUT = 8;
    private static final int MODE_AUDIO_MOVE = 9;
    private static final int MODE_AUDIO_TRIM_IN = 10;
    private static final int MODE_AUDIO_TRIM_OUT = 11;
    /** Width of the drag grip at the left edge of a track header. */
    private static final int REORDER_GRIP = 14;

    private final UICompositorPanel panel;
    private final Scale xAxis = new Scale(new Area(), ScrollDirection.HORIZONTAL);

    private final Area rulerArea = new Area();
    private final Area headerArea = new Area();
    private final Area laneArea = new Area();

    private int scrollY;
    private int rowHeight = ROW_DEFAULT;
    private boolean viewReady;
    private boolean fitMode = true;
    private float knownDuration = -1F;

    private int dragMode = MODE_NONE;
    /** Audio clip being dragged, plus its start/offset/length at the moment the drag began. */
    private dev.bbsae.comp.CompAudio dragAudio;
    private float dragAudioStart;
    private float dragAudioOffset;
    private float dragAudioLength;
    private String dragId;
    private int dragStartX;
    private int dragStartY;
    private int dragReorderTarget = -1;
    private float dragIn;
    private float dragOut;
    private double dragShift;
    private float snapGuide = Float.NaN;

    private boolean snapping = true;
    private String contextLayerId;
    private int lastClickRow = -1;
    private long lastClickTime;
    private int lastLaneRow = -1;
    private long lastLaneTime;

    public UICompTrackTimeline(UICompositorPanel panel)
    {
        this.panel = panel;

        this.context((manager) ->
        {
            CompLayer layer = this.findLayer(this.contextLayerId);

            manager.action(Icons.ADD, IKey.constant("添加图层…"), this.panel::showAddLayerMenu);

            if (layer == null)
            {
                manager.action(Icons.SQUARE, IKey.constant("快速新建纯色"), () -> this.panel.addLayer("solid"));
                manager.action(Icons.FONT, IKey.constant("快速新建文字"), () -> this.panel.addLayer("text"));
                manager.action(Icons.SHAPES, IKey.constant("快速新建矩形"), () -> this.panel.addLayer("shape"));

                return;
            }

            int count = this.panel.getSelectionCount();

            if (count > 1)
            {
                manager.action(Icons.STRUCTURE, IKey.constant("合成预渲染（" + count + " 个图层）"), this.panel::precomposeSelection);
                manager.action(Icons.LINK, IKey.constant("编组到空对象"), this.panel::groupSelection);
                manager.action(Icons.ARROW_LEFT, IKey.constant("对齐：左"), () -> this.panel.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_LEFT));
                manager.action(Icons.ARROW_RIGHT, IKey.constant("对齐：右"), () -> this.panel.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_RIGHT));
                manager.action(Icons.ARROW_UP, IKey.constant("对齐：上"), () -> this.panel.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_TOP));
                manager.action(Icons.ARROW_DOWN, IKey.constant("对齐：下"), () -> this.panel.alignSelection(dev.bbsae.comp.CompLayerOps.ALIGN_BOTTOM));
                manager.action(Icons.SHIFT_TO, IKey.constant("水平等距分布"), () -> this.panel.distributeSelection(true));
                manager.action(Icons.SHIFT_TO, IKey.constant("垂直等距分布"), () -> this.panel.distributeSelection(false));
                manager.action(Icons.VISIBLE, IKey.constant("显示所选"), () -> this.panel.setSelectionVisible(true));
                manager.action(Icons.INVISIBLE, IKey.constant("隐藏所选"), () -> this.panel.setSelectionVisible(false));
                manager.action(Icons.LOCKED, IKey.constant("锁定所选"), () -> this.panel.setSelectionLocked(true));
                manager.action(Icons.DUPE, IKey.constant("复制所选"), this.panel::duplicateSelection);
                manager.action(Icons.TRASH, IKey.constant("删除所选"), this.panel::deleteSelection);
            }

            manager.action(Icons.EDIT, IKey.constant("重命名…"), () -> this.panel.renameLayer(layer));
            manager.action(Icons.DUPE, IKey.constant("复制轨道"), () -> this.panel.duplicateLayer(layer));
            manager.action(Icons.TRASH, IKey.constant("删除轨道"), () -> this.panel.deleteLayer(layer));

            if ("precomp".equals(layer.type))
            {
                manager.action(Icons.FILM, IKey.constant("进入预合成"), () -> this.panel.enterPrecomp(layer));
            }

            manager.action(Icons.SHIFT_TO, IKey.constant("在播放头拆分"), this.panel::splitSelectedAtPlayhead);
            manager.action(Icons.ARROW_UP, IKey.constant("上移一层"), () -> this.panel.moveLayerOrder(layer, 1));
            manager.action(Icons.ARROW_DOWN, IKey.constant("下移一层"), () -> this.panel.moveLayerOrder(layer, -1));
            manager.action(Icons.WRENCH, IKey.constant("转到效果库（双击应用）…"), () -> this.panel.focusEffectLibrary());
            manager.action(Icons.GRAPH, IKey.constant("显示属性关键帧"), this.panel::openKeyframes);
            manager.action(Icons.VISIBLE, IKey.constant(layer.visible ? "隐藏轨道" : "显示轨道"), () -> this.panel.toggleLayerVisible(layer));
            manager.action(Icons.LOCKED, IKey.constant(layer.locked ? "解锁轨道" : "锁定轨道"), () -> this.panel.toggleLayerLocked(layer));
        });
    }

    /* Geometry */

    private CompDocument doc()
    {
        return this.panel.getDocument();
    }

    private float duration()
    {
        return this.doc() == null ? 1F : Math.max(0.1F, this.doc().duration);
    }

    private float fps()
    {
        return this.doc() == null ? 20F : Math.max(1F, this.doc().fps);
    }

    private List<CompLayer> rows()
    {
        if (this.doc() == null)
        {
            return List.of();
        }

        List<CompLayer> layers = new ArrayList<>(this.doc().layers);

        Collections.reverse(layers);

        return layers;
    }

    private int headerWidth()
    {
        /* Wide enough for the four per-track switches (parent / 3D / eye / lock) plus a readable
         * name. At 146px the name column collapsed to ~39px once the switches were added. */
        return Math.max(190, Math.min(248, this.area.w / 3));
    }

    private void updateAreas()
    {
        int hw = this.headerWidth();
        int laneH = Math.max(0, this.area.h - RULER_H);

        this.rulerArea.set(this.area.x + hw, this.area.y, Math.max(0, this.area.w - hw), RULER_H);
        this.headerArea.set(this.area.x, this.area.y + RULER_H, hw, laneH);
        this.laneArea.set(this.rulerArea.x, this.rulerArea.y + RULER_H, this.rulerArea.w, laneH);

        this.xAxis.area = this.laneArea;
    }

    private int rowY(int index)
    {
        return this.laneArea.y + index * this.rowHeight - this.scrollY;
    }

    /* --------------------------------------------------------------- audio track
     *
     * The composition has an audio track (doc.audio) but the timeline only ever enumerated layers,
     * so imported music and voice-over existed in the inspector and nowhere on the screen: no bar,
     * no in/out, nothing to drag. The audio lane is therefore a <b>virtual row</b> appended after
     * the last layer: same row height, same scroll, same time axis, so it behaves like a track
     * without inventing a second timeline.
     */

    /** One extra row for the audio track whenever a composition is bound. */
    private boolean hasAudioRow()
    {
        return this.doc() != null;
    }

    private int audioRowIndex()
    {
        return this.hasAudioRow() ? this.rows().size() : -1;
    }

    /** Layers + the audio lane: the only place that knows how many rows exist. */
    private int rowCount()
    {
        return this.rows().size() + (this.hasAudioRow() ? 1 : 0);
    }

    /** Length drawn for a clip: an explicit duration, or the rest of the composition. */
    private float audioLength(dev.bbsae.comp.CompAudio clip)
    {
        return Math.max(this.frame(), clip.length(this.duration()));
    }

    private dev.bbsae.comp.CompAudio audioAt(int mouseX)
    {
        CompDocument doc = this.doc();

        if (doc == null)
        {
            return null;
        }

        dev.bbsae.comp.CompAudio hit = null;

        for (dev.bbsae.comp.CompAudio clip : doc.audio)
        {
            if (clip == null)
            {
                continue;
            }

            int x1 = this.toX(clip.start);
            int x2 = this.toX(clip.start + this.audioLength(clip));

            if (mouseX >= Math.min(x1, x2) - 2 && mouseX <= Math.max(x1, x2) + 2)
            {
                hit = clip;
            }
        }

        return hit;
    }

    private int rowAt(int mouseY)
    {
        if (mouseY < this.laneArea.y || mouseY >= this.laneArea.ey())
        {
            return -1;
        }

        int index = (mouseY - this.laneArea.y + this.scrollY) / Math.max(1, this.rowHeight);

        return index >= 0 && index < this.rowCount() ? index : -1;
    }

    /**
     * Insertion slot for a reorder drag: 0 = above the first (top) row, rows.size() = below the
     * last (bottom) row. The half-row rule is what makes dropping between two tracks predictable.
     */
    private int reorderTarget(int mouseY)
    {
        int size = this.rows().size();

        if (size <= 0)
        {
            return 0;
        }

        if (mouseY < this.laneArea.y)
        {
            return 0;
        }

        if (mouseY >= this.laneArea.ey())
        {
            return size;
        }

        int index = (mouseY - this.laneArea.y + this.scrollY) / Math.max(1, this.rowHeight);

        index = Math.max(0, Math.min(size - 1, index));

        int rowTop = this.rowY(index);

        return index + (mouseY >= rowTop + this.rowHeight / 2 ? 1 : 0);
    }

    private int toX(float seconds)
    {
        return (int) Math.round(this.xAxis.to(seconds));
    }

    private float fromX(int x)
    {
        return (float) Math.max(0D, Math.min(this.duration(), this.xAxis.from(x)));
    }

    private float frame()
    {
        return 1F / this.fps();
    }

    private float quantize(float seconds)
    {
        return Math.round(seconds * this.fps()) / this.fps();
    }

    private void clampScroll()
    {
        int total = this.rowCount() * this.rowHeight;
        int max = Math.max(0, total - this.laneArea.h);

        this.scrollY = Math.max(0, Math.min(max, this.scrollY));
    }

    /** Shift + zoom of the time axis, for restoring a comp's view when navigating back. */
    public double[] viewState()
    {
        return new double[] {this.xAxis.getShift(), this.xAxis.getZoom()};
    }

    public void restoreView(double shift, double zoom)
    {
        this.fitMode = false;
        this.viewReady = true;
        this.xAxis.set(shift, zoom);
    }

    private void ensureView()
    {
        if (this.laneArea.w <= 0)
        {
            return;
        }

        float duration = this.duration();

        if (!this.viewReady || (this.fitMode && Math.abs(duration - this.knownDuration) > 0.0001F))
        {
            this.viewReady = true;
            this.knownDuration = duration;
            this.xAxis.view(0D, duration);
            this.xAxis.anchor(0F);
        }
    }

    /* Public commands used by the panel and the toolbar */

    public void fit()
    {
        this.fitMode = true;
        this.knownDuration = this.duration();

        if (this.laneArea.w <= 0)
        {
            this.viewReady = false;

            return;
        }

        this.viewReady = true;
        this.xAxis.view(0D, this.duration());
        this.xAxis.anchor(0F);
    }

    /**
     * Time zoom, one "step" per key press or wheel notch.
     *
     * <h2>Why this multiplies instead of adding</h2>
     * <p>BBS's {@code Scale.zoom(delta, min, max)} <b>adds</b> to the zoom value, and that value is
     * <b>pixels per second</b>. The old code passed {@code +1} / {@code -1} straight through, so a
     * shortcut changed the scale by one pixel per second — at a fitted 12-second comp (~67 px/s)
     * that is 1.5 %, i.e. invisible. Every wheel notch and every key press "did nothing", which is
     * exactly the reported bug. A zoom step has to be relative: 1.35× per step, applied through the
     * same additive API BBS expects.</p>
     */
    public void zoom(double steps)
    {
        this.zoomAt(0.5F, steps);
    }

    private void zoomAt(float anchor, double steps)
    {
        if (this.laneArea.w <= 0 || Math.abs(steps) < 0.0001D)
        {
            return;
        }

        double current = this.xAxis.getZoom();
        double target = zoomTarget(current, steps);

        if (Math.abs(target - current) < 0.0001D)
        {
            return;
        }

        this.fitMode = false;
        this.xAxis.zoomAnchor(anchor, target - current, 0.05D, 200_000D);

        if (Boolean.getBoolean("bbsae.debugEncoder"))
        {
            dev.bbsae.BbsAeAddon.LOGGER.info("timeline zoom: steps={} current={} target={} after={}",
                steps, current, target, this.xAxis.getZoom());
        }

        double fit = this.laneArea.w / Math.max(0.001D, this.duration());

        this.panel.setStatus(String.format(Locale.ROOT, "时间轴 %d%%（%.0f px/s）",
            Math.round(target / Math.max(0.001D, fit) * 100D), target));
    }

    /**
     * Keyboard equivalent of Alt+wheel: the row height, i.e. "track zoom".
     *
     * <p>The reported bug was "tracks cannot be zoomed with the keyboard". Time zoom existed but
     * did nothing (it added ±1 px/s); vertical zoom only existed on Alt+wheel. Both are now on the
     * keyboard, with the same key pair and Alt as the modifier that distinguishes them.</p>
     */
    public void stepRowHeight(int step)
    {
        int next = Math.max(ROW_MIN, Math.min(ROW_MAX, this.rowHeight + step));

        if (next == this.rowHeight)
        {
            return;
        }

        this.rowHeight = next;
        this.clampScroll();
        this.panel.setStatus("轨道高度 " + this.rowHeight + "px（Alt+加号/减号）");
    }

    /**
     * Zoom value after {@code steps} multiplicative steps.
     *
     * <p>Extracted so the arithmetic can be tested without a UI: the bug this fixes was a unit
     * error (adding 1 px/s instead of multiplying), and a test is the only thing that keeps the
     * unit honest. 0.05 px/s is "a year per pixel"; 200 000 px/s is "one frame fills the lane".</p>
     */
    static double zoomTarget(double current, double steps)
    {
        double target = current * Math.pow(1.35D, steps);

        return Math.max(0.05D, Math.min(200_000D, target));
    }

    /**
     * Scrolls the audio lane into view.
     *
     * <p>A track appended below the layers is easy to miss: with ten tracks the audio row sits
     * below the fold, so importing music looked like nothing happened. Selecting a clip (from the
     * inspector or right after an import) brings the lane up.</p>
     */
    public void revealAudio()
    {
        int index = this.audioRowIndex();

        if (index < 0)
        {
            return;
        }

        int top = index * this.rowHeight;
        int bottom = top + this.rowHeight;

        if (bottom > this.scrollY + this.laneArea.h)
        {
            this.scrollY = bottom - this.laneArea.h;
        }

        if (top < this.scrollY)
        {
            this.scrollY = top;
        }

        this.clampScroll();
    }

    public boolean isSnapping()
    {
        return this.snapping;
    }

    public void toggleSnapping()
    {
        this.snapping = !this.snapping;

        this.panel.setStatus(this.snapping ? "吸附：开" : "吸附：关");
    }

    public boolean isInteracting()
    {
        return this.dragMode != MODE_NONE;
    }

    public void ensureSelectionVisible()
    {
        CompLayer selected = this.panel.getSelectedLayer();

        if (selected == null)
        {
            return;
        }

        List<CompLayer> rows = this.rows();
        int index = rows.indexOf(selected);

        if (index < 0)
        {
            return;
        }

        int y = index * this.rowHeight;
        int bottom = y + this.rowHeight;

        if (y < this.scrollY)
        {
            this.scrollY = y;
        }
        else if (bottom > this.scrollY + this.laneArea.h)
        {
            this.scrollY = bottom - this.laneArea.h;
        }

        this.clampScroll();
    }

    public void revealTime(float seconds)
    {
        if (this.laneArea.w <= 0)
        {
            return;
        }

        double span = Math.max(0.1D, this.xAxis.getMaxValue() - this.xAxis.getMinValue());

        this.xAxis.shiftInto(seconds, span * 0.12D);
    }

    public void setRowHeight(int height)
    {
        this.rowHeight = Math.max(ROW_MIN, Math.min(ROW_MAX, height));
        this.clampScroll();
    }

    public int getRowHeight()
    {
        return this.rowHeight;
    }

    /* Rendering */

    @Override
    public void render(UIContext context)
    {
        this.updateAreas();
        this.ensureView();
        this.clampScroll();

        this.snapGuide = Float.NaN;
        this.handleDrag(context);

        this.renderSurfaces(context);
        this.renderGrid(context);
        this.renderRows(context);
        this.renderMarkers(context);
        this.renderPlayhead(context);
        this.renderSnapGuide(context);
        this.renderScrollbar(context);
        this.renderDragHud(context);

        super.render(context);

        this.updateCursor(context);
    }

    private void renderSurfaces(UIContext context)
    {
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());

        /* Corner above the track headers */
        context.batcher.box(this.area.x, this.area.y, this.rulerArea.x, this.rulerArea.ey(), BBSSettings.sunkenSurface());
        context.batcher.textShadow("轨道", this.area.x + 8, this.area.y + 5, Colors.setA(Colors.WHITE, 0.55F));

        CompTimelineRuler.render(context, this.rulerArea, this.xAxis.getMinValue(), this.xAxis.getMaxValue(), (double value) -> this.toX((float) value));
        this.renderPrecacheBar(context);

        context.batcher.box(this.headerArea.x, this.headerArea.y, this.headerArea.ex(), this.headerArea.ey(), BBSSettings.baseSurface());
        context.batcher.box(this.headerArea.ex() - 1, this.headerArea.y, this.headerArea.ex(), this.headerArea.ey(), BBSSettings.dividerColor());
        context.batcher.box(this.headerArea.x, this.headerArea.y, this.headerArea.ex(), this.headerArea.y + 1, BBSSettings.dividerColor());
    }

    /**
     * The green bar.
     *
     * <p>A three-pixel strip along the bottom of the ruler, one segment per contiguous run of
     * cached frames. This is the only visual contract the pre-render cache needs: green means
     * "playback reads pixels instead of evaluating the graph", exactly like After Effects. Runs
     * are merged so a fully cached minute costs a handful of quads, not one per frame.</p>
     */
    private void renderPrecacheBar(UIContext context)
    {
        CompDocument doc = this.doc();

        if (doc == null || this.panel.getProject() == null)
        {
            return;
        }

        java.util.BitSet coverage = dev.bbsae.client.render.CompPrecache.coverage(this.panel.getProject(), doc);

        if (coverage.isEmpty())
        {
            return;
        }

        float fps = Math.max(1F, doc.fps);
        int first = Math.max(0, (int) Math.floor(this.xAxis.getMinValue() * fps));
        int last = (int) Math.ceil(this.xAxis.getMaxValue() * fps);
        int y0 = this.rulerArea.ey() - 3;
        int y1 = this.rulerArea.ey() - 1;
        int color = 0xFF3FBF6F;

        context.batcher.clip(this.rulerArea, context);

        try
        {
            int runStart = -1;

            for (int frame = first; frame <= last + 1; frame++)
            {
                boolean cached = frame <= last && coverage.get(frame);

                if (cached && runStart < 0)
                {
                    runStart = frame;
                }
                else if (!cached && runStart >= 0)
                {
                    int x1 = this.toX((float) (runStart / fps));
                    int x2 = this.toX((float) (frame / fps));

                    context.batcher.box(x1, y0, Math.max(x1 + 1, x2), y1, color);
                    runStart = -1;
                }
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void renderGrid(UIContext context)
    {
        double pixelsPerSecond = this.laneArea.w / Math.max(0.0001D, this.xAxis.getMaxValue() - this.xAxis.getMinValue());
        double step = CompTimelineRuler.majorStep(pixelsPerSecond);
        double first = Math.floor(this.xAxis.getMinValue() / step) * step;
        int color = Colors.setA(BBSSettings.dividerColor(), 0.18F);
        int guard = 0;

        context.batcher.clip(this.laneArea, context);

        try
        {
            for (double time = first; time <= this.xAxis.getMaxValue() && guard < 2000; time += step, guard++)
            {
                if (time < 0D)
                {
                    continue;
                }

                int x = this.toX((float) time);

                context.batcher.box(x, this.laneArea.y, x + 1, this.laneArea.ey(), color);
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }

        this.renderWorkArea(context);
    }

    private void renderWorkArea(UIContext context)
    {
        CompDocument doc = this.doc();

        if (doc == null)
        {
            return;
        }

        float in = Math.max(0F, Math.min(doc.duration, doc.workAreaIn));
        float out = Math.max(in, Math.min(doc.duration, doc.workAreaOut));

        if (out - in < 0.001F || (in <= 0.0001F && out >= doc.duration - 0.0001F))
        {
            return;
        }

        int x1 = Math.max(this.laneArea.x, Math.min(this.laneArea.ex(), this.toX(in)));
        int x2 = Math.max(this.laneArea.x, Math.min(this.laneArea.ex(), this.toX(out)));

        if (x1 > this.laneArea.x)
        {
            context.batcher.box(this.laneArea.x, this.laneArea.y, x1, this.laneArea.ey(), 0x40000000);
        }

        if (x2 < this.laneArea.ex())
        {
            context.batcher.box(x2, this.laneArea.y, this.laneArea.ex(), this.laneArea.ey(), 0x40000000);
        }

        int accent = Colors.setA(BBSSettings.primaryColor(), 0.85F);

        if (x1 >= this.rulerArea.x && x1 <= this.rulerArea.ex())
        {
            context.batcher.box(x1, this.rulerArea.y + 1, x1 + 2, this.rulerArea.ey(), accent);
            context.batcher.box(x1 - 3, this.rulerArea.y + 1, x1 + 3, this.rulerArea.y + 5, accent);
        }

        if (x2 >= this.rulerArea.x && x2 <= this.rulerArea.ex())
        {
            context.batcher.box(x2 - 2, this.rulerArea.y + 1, x2, this.rulerArea.ey(), accent);
            context.batcher.box(x2 - 3, this.rulerArea.y + 1, x2 + 3, this.rulerArea.y + 5, accent);
        }
    }

    private void renderRows(UIContext context)
    {
        List<CompLayer> rows = this.rows();

        if (rows.isEmpty())
        {
            /* No layers yet: the empty-state hint lives in the space above the audio lane instead
             * of replacing the whole area, otherwise the audio track would vanish exactly when a
             * project is new. */
            int audioY = this.hasAudioRow() ? this.rowY(this.audioRowIndex()) : this.laneArea.ey();
            Area hintArea = new Area(this.laneArea.x, this.laneArea.y, this.laneArea.w,
                Math.max(0, audioY - this.laneArea.y));

            if (hintArea.h > 24)
            {
                EmptyStateRenderer.renderHint(context, hintArea, IKey.constant("点击左上角＋新建图层；拖动片段移动，拖两端裁剪"));
            }
        }

        int first = Math.max(0, this.scrollY / Math.max(1, this.rowHeight));
        int last = Math.min(rows.size(), first + this.laneArea.h / Math.max(1, this.rowHeight) + 2);

        for (int i = first; i < last; i++)
        {
            int y = this.rowY(i);

            if (y + this.rowHeight <= this.laneArea.y || y >= this.laneArea.ey())
            {
                continue;
            }

            CompLayer layer = rows.get(i);
            boolean selected = layer == this.panel.getSelectedLayer();
            boolean alsoSelected = !selected && this.panel.isLayerSelected(layer);

            if (alsoSelected)
            {
                /* Secondary members of a multi-selection get a tinted band: the primary keeps the
                 * full accent so "what the inspector edits" stays obvious. */
                int bottom = Math.min(y + this.rowHeight, this.laneArea.ey());

                context.batcher.box(this.headerArea.x, y, this.laneArea.ex(), bottom, 0x1655AAFF);
            }

            this.renderHeaderRow(context, layer, y, selected, context.mouseX < this.headerArea.ex());
            this.renderLaneRow(context, layer, y, selected);
        }

        int audioIndex = this.audioRowIndex();

        if (audioIndex >= 0)
        {
            int y = this.rowY(audioIndex);

            if (y + this.rowHeight > this.laneArea.y && y < this.laneArea.ey())
            {
                this.renderAudioRow(context, y);
            }
        }

        if (this.dragMode == MODE_REORDER && this.dragReorderTarget >= 0)
        {
            int lineY = this.laneArea.y + this.dragReorderTarget * this.rowHeight - this.scrollY;

            lineY = Math.max(this.laneArea.y, Math.min(this.laneArea.ey() - 2, lineY));

            int color = BBSSettings.primaryColor();

            context.batcher.box(this.headerArea.x + 2, lineY, this.laneArea.ex(), lineY + 2, color);
            context.batcher.box(this.headerArea.x + 2, lineY - 2, this.headerArea.x + 8, lineY + 4, color);
        }
    }

    /**
     * The audio lane: a header that says how many clips there are, and one bar per clip.
     *
     * <p>Bars use the same time axis as the tracks, so dragging inside them behaves like trimming a
     * layer: body moves the in-point, edges change start/offset or length. A clip without an explicit
     * duration is drawn to the end of the composition (that is how it plays).</p>
     */
    private void renderAudioRow(UIContext context, int y)
    {
        CompDocument doc = this.doc();
        int bottom = Math.min(y + this.rowHeight, this.laneArea.ey());
        boolean hover = context.mouseX >= this.headerArea.x && context.mouseX < this.headerArea.ex()
            && context.mouseY >= y && context.mouseY < bottom;

        context.batcher.clip(this.headerArea, context);

        try
        {
            context.batcher.box(this.headerArea.x, y, this.headerArea.ex(), bottom,
                hover ? BBSSettings.deepSurface() : BBSSettings.baseSurface());
            context.batcher.box(this.headerArea.x, y, this.headerArea.ex(), y + 1, BBSSettings.dividerColor());
            context.batcher.icon(Icons.SOUND, Colors.setA(Colors.WHITE, 0.8F),
                this.headerArea.x + 15, y + (this.rowHeight - 16) / 2);

            int count = doc == null || doc.audio == null ? 0 : doc.audio.size();
            String label = count == 0 ? "音频（空）" : "音频 (" + count + ")";
            int nameX = this.headerArea.x + 34;

            context.batcher.textShadow(label, nameX, y + (this.rowHeight - 8) / 2,
                count == 0 ? Colors.setA(Colors.WHITE, 0.5F) : Colors.WHITE);
        }
        finally
        {
            context.batcher.unclip(context);
        }

        context.batcher.clip(this.laneArea, context);

        try
        {
            context.batcher.box(this.laneArea.x, y, this.laneArea.ex(), Math.min(bottom, this.laneArea.ey()), 0x0C6BE0A0);

            if (doc == null || doc.audio == null || doc.audio.isEmpty())
            {
                context.batcher.textShadow("导入音频后，片段会出现在这条轨道上（可拖动、可裁剪）",
                    this.laneArea.x + 8, y + (this.rowHeight - 8) / 2, 0x66FFFFFF);

                return;
            }

            int barH = Math.max(10, this.rowHeight - 6);
            int barY = y + (this.rowHeight - barH) / 2;

            for (dev.bbsae.comp.CompAudio clip : doc.audio)
            {
                if (clip == null)
                {
                    continue;
                }

                int x1 = this.toX(clip.start);
                int x2 = this.toX(clip.start + this.audioLength(clip));
                int left = Math.min(x1, x2);
                int right = Math.max(x1, x2 + 1);
                boolean selected = this.panel.getSelectedAudio() == clip;
                int color = clip.muted ? 0xFF3A4A44 : (selected ? 0xFF2F7D57 : 0xFF2A5C44);
                int edge = selected ? BBSSettings.primaryColor() : 0xFF16241D;

                context.batcher.box(left, barY, right, barY + barH, color);
                context.batcher.box(left, barY, right, barY + 1, edge);
                context.batcher.box(left, barY + barH - 1, right, barY + barH, edge);

                if (right - left > 26)
                {
                    context.batcher.textShadow(clip.name + (clip.muted ? "（静音）" : ""),
                        left + 4, barY + (barH - 8) / 2, selected ? 0xFFFFFFFF : 0xFFCFE9DA);
                }
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void renderHeaderRow(UIContext context, CompLayer layer, int y, boolean selected, boolean hover)
    {
        int bottom = Math.min(y + this.rowHeight, this.laneArea.ey());

        context.batcher.clip(this.headerArea, context);

        try
        {
            int background = selected
                ? Colors.setA(BBSSettings.primaryColor(), 0.20F) | 0xFF000000
                : (hover && context.mouseY >= y && context.mouseY < bottom ? BBSSettings.deepSurface() : BBSSettings.baseSurface());

            context.batcher.box(this.headerArea.x, y, this.headerArea.ex(), bottom, background);
            context.batcher.box(this.headerArea.x, y, this.headerArea.x + 3, bottom, this.barColor(layer));

            if (selected)
            {
                context.batcher.box(this.headerArea.x, y, this.headerArea.x + 2, bottom, BBSSettings.primaryColor());
            }

            context.batcher.box(this.headerArea.x, bottom - 1, this.headerArea.ex(), bottom, Colors.setA(BBSSettings.dividerColor(), 0.55F));

            /* Drag grip: the user grabs the left edge of a track header and moves it vertically.
             * Keeping it separate from the eye/lock/3D/parent icons means those still click. */
            int gripX = this.headerArea.x + 3;
            int gripColor = selected ? BBSSettings.primaryColor() : Colors.setA(Colors.WHITE, hover ? 0.55F : 0.28F);

            for (int line = 0; line < 3; line++)
            {
                context.batcher.box(gripX, y + (this.rowHeight - 10) / 2 + line * 4, gripX + 6, y + (this.rowHeight - 10) / 2 + line * 4 + 1, gripColor);
            }

            context.batcher.icon(this.typeIcon(layer), Colors.setA(Colors.WHITE, layer.visible ? 0.86F : 0.35F), this.headerArea.x + 15, y + (this.rowHeight - 16) / 2);

            int nameX = this.headerArea.x + 34;
            int nameW = this.headerArea.ex() - 92 - nameX;

            if (nameW > 8)
            {
                context.batcher.clip(nameX, y, nameW, bottom - y, context);

                try
                {
                    String header = (layer.parentId == null || layer.parentId.isEmpty() ? "" : "↳ ") + this.layerName(layer);

                    context.batcher.textShadow(header, nameX, y + (this.rowHeight - 8) / 2, layer.visible ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.45F));
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            int eyeX = this.headerArea.ex() - 36;
            int lockX = this.headerArea.ex() - 19;

            context.batcher.icon(layer.visible ? Icons.VISIBLE : Icons.INVISIBLE,
                layer.visible ? Colors.setA(Colors.WHITE, 0.8F) : Colors.setA(Colors.WHITE, 0.35F), eyeX, y + (this.rowHeight - 16) / 2);
            context.batcher.icon(layer.locked ? Icons.LOCKED : Icons.UNLOCKED,
                layer.locked ? BBSSettings.primaryColor() : Colors.setA(Colors.WHITE, 0.38F), lockX, y + (this.rowHeight - 16) / 2);

            int parentX = this.headerArea.ex() - 53;
            int threeDX = this.headerArea.ex() - 70;

            context.batcher.icon(Icons.LINK,
                layer.parentId != null && !layer.parentId.isEmpty() ? BBSSettings.primaryColor() : Colors.setA(Colors.WHITE, 0.30F),
                parentX, y + (this.rowHeight - 16) / 2);
            /* A literal "3D" badge reads better than a generic cube icon at 16px. */
            context.batcher.textShadow("3D", threeDX, y + (this.rowHeight - 8) / 2,
                layer.threeD ? BBSSettings.primaryColor() : Colors.setA(Colors.WHITE, 0.30F));
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void renderLaneRow(UIContext context, CompLayer layer, int y, boolean selected)
    {
        int bottom = Math.min(y + this.rowHeight, this.laneArea.ey());
        int barY = y + 3;
        int barH = Math.max(10, this.rowHeight - 6);

        context.batcher.clip(this.laneArea, context);

        try
        {
            int rowBackground = selected ? 0x1A55AAFF : (y / Math.max(1, this.rowHeight)) % 2 == 0 ? 0x08FFFFFF : 0x00000000;

            if (rowBackground != 0)
            {
                context.batcher.box(this.laneArea.x, y, this.laneArea.ex(), bottom, rowBackground);
            }

            context.batcher.box(this.laneArea.x, bottom - 1, this.laneArea.ex(), bottom, 0x10FFFFFF);

            int start = this.toX(layer.in);
            int finish = this.toX(layer.out);

            if (finish <= start)
            {
                finish = start + 2;
            }

            int color = this.barColor(layer);
            int alpha = layer.visible ? 0xFF : 0x66;

            color = (color & 0x00FFFFFF) | (alpha << 24);

            context.batcher.box(start, barY, finish, barY + barH, color);
            context.batcher.box(start, barY, finish, barY + 2, 0x55FFFFFF);
            context.batcher.box(start, barY + barH - 1, finish, barY + barH, 0x33000000);

            if (selected)
            {
                context.batcher.outline(start, barY, finish, barY + barH, 0xFFEAF3FF);
            }

            /* Trim grips: visible whenever the bar is wide enough, brighter when selected */
            if (finish - start >= 10)
            {
                int grip = selected ? 0xFFFFFFFF : 0x88FFFFFF;

                context.batcher.box(start, barY, start + 2, barY + barH, grip);
                context.batcher.box(finish - 2, barY, finish, barY + barH, grip);
            }

            FontRenderer font = context.batcher.getFont();
            String label = this.laneLabel(layer);
            int textX = start + 6;
            int textW = finish - textX - 6;

            if (textW > 28)
            {
                context.batcher.clip(textX, barY, textW, barH, context);

                try
                {
                    context.batcher.textShadow(label, textX, barY + (barH - font.getHeight()) / 2, 0xFFFFFFFF);
                }
                finally
                {
                    context.batcher.unclip(context);
                }
            }

            this.renderKeyframeTicks(context, layer, barY, barH, start, finish);

            if (!layer.effects.isEmpty() && finish - start > 54)
            {
                String fx = layer.effects.size() + " FX";

                context.batcher.textShadow(fx, finish - 8 - font.getWidth(fx), barY + 2, Colors.setA(Colors.WHITE, 0.8F));
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    /**
     * The keyframe whose tick is within a few pixels of the cursor on this row's tick strip,
     * or null. The strip is the 2px line drawn at the bottom of the bar.
     */
    private Keyframe keyframeAt(CompLayer layer, int row, UIContext context)
    {
        List<Keyframe> keyframes = layer.allKeyframes();

        if (keyframes.isEmpty())
        {
            return null;
        }

        int barY = this.rowY(row) + 3;
        int barH = Math.max(10, this.rowHeight - 6);
        int tickY = barY + barH - 3;

        if (Math.abs(context.mouseY - tickY) > 6)
        {
            return null;
        }

        float fps = this.fps();
        Keyframe best = null;
        int bestDistance = 5;

        for (Keyframe keyframe : keyframes)
        {
            int x = this.toX(keyframe.getTick() / fps);
            int distance = Math.abs(context.mouseX - x);

            if (distance <= bestDistance)
            {
                best = keyframe;
                bestDistance = distance;
            }
        }

        return best;
    }

    private void renderKeyframeTicks(UIContext context, CompLayer layer, int barY, int barH, int minX, int maxX)
    {        List<Keyframe> keyframes = layer.allKeyframes();

        if (keyframes.isEmpty())
        {
            return;
        }

        float fps = this.fps();
        int y = barY + barH - 4;
        int drawn = 0;

        for (Keyframe keyframe : keyframes)
        {
            int x = this.toX(keyframe.getTick() / fps);

            if (x < minX || x > maxX)
            {
                continue;
            }

            context.batcher.box(x - 1, y, x + 1, y + 2, 0xFFE9EDF3);

            if (++drawn > 220)
            {
                return;
            }
        }
    }

    private CompMarker markerAt(int mouseX)
    {
        CompDocument doc = this.doc();

        if (doc == null || doc.markers.isEmpty())
        {
            return null;
        }

        for (CompMarker marker : doc.markers)
        {
            if (Math.abs(mouseX - this.toX(marker.time)) <= 6)
            {
                return marker;
            }
        }

        return null;
    }

    private void renderMarkers(UIContext context)
    {
        CompDocument doc = this.doc();

        if (doc == null || doc.markers.isEmpty())
        {
            return;
        }

        context.batcher.clip(this.rulerArea, context);

        try
        {
            for (CompMarker marker : doc.markers)
            {
                int x = this.toX(marker.time);

                context.batcher.box(x - 3, this.rulerArea.y + 2, x + 3, this.rulerArea.y + 6, 0xFFFFC14D);
                context.batcher.box(x - 1, this.rulerArea.y + 6, x + 1, this.rulerArea.ey(), 0xFFFFC14D);
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void renderPlayhead(UIContext context)
    {
        int x = this.toX(this.panel.getTime());

        if (x < this.rulerArea.x || x > this.rulerArea.ex())
        {
            return;
        }

        int color = BBSSettings.primaryColor();

        context.batcher.box(x, this.rulerArea.y, x + 1, this.laneArea.ey(), Colors.setA(color, 0.92F));
        context.batcher.box(x - 4, this.rulerArea.y + 1, x + 5, this.rulerArea.y + 7, color);
    }

    private void renderSnapGuide(UIContext context)
    {
        if (Float.isNaN(this.snapGuide))
        {
            return;
        }

        int x = this.toX(this.snapGuide);

        if (x < this.laneArea.x || x > this.laneArea.ex())
        {
            return;
        }

        context.batcher.box(x, this.laneArea.y, x + 1, this.laneArea.ey(), 0xE6FFFFFF);
    }

    private void renderScrollbar(UIContext context)
    {
        /* Same total the scrolling uses (layers + the virtual audio row): a layers-only count
         * hid the scrollbar exactly when the audio lane was the thing below the fold. */
        int total = this.rowCount() * this.rowHeight;

        if (total <= this.laneArea.h || this.laneArea.h <= 0)
        {
            return;
        }

        int x = this.laneArea.ex() - 4;
        int h = Math.max(16, (int) (this.laneArea.h * (this.laneArea.h / (float) total)));
        int y = this.laneArea.y + (int) ((this.laneArea.h - h) * (this.scrollY / (float) Math.max(1, total - this.laneArea.h)));

        context.batcher.box(x, this.laneArea.y, x + 3, this.laneArea.ey(), 0x30000000);
        context.batcher.box(x, y, x + 3, y + h, 0x66FFFFFF);
    }

    private void renderDragHud(UIContext context)
    {
        if (this.dragMode == MODE_NONE || this.dragMode == MODE_SCRUB || this.dragId == null)
        {
            return;
        }

        CompLayer layer = this.findLayer(this.dragId);

        if (layer == null)
        {
            return;
        }

        String text = String.format(Locale.ROOT, "入 %.2fs   出 %.2fs   时长 %.2fs", layer.in, layer.out, Math.max(0F, layer.out - layer.in));

        context.batcher.textCard(text, context.mouseX + 12, context.mouseY + 14, Colors.WHITE, 0xE010141A, 3);
    }

    /* Input */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        this.updateAreas();

        if (context.mouseButton == 0)
        {
            if (context.mouseY < this.laneArea.y)
            {
                /* The toolbar sits above the ruler; only a click inside the ruler should scrub
                 * or hit a marker. */
                if (context.mouseX >= this.laneArea.x && context.mouseY >= this.rulerArea.y)
                {
                    CompMarker marker = this.markerAt(context.mouseX);

                    if (marker != null)
                    {
                        this.panel.setFrame(Math.round(marker.time * this.fps()));
                        this.panel.setStatus("标记：" + marker.name);

                        return true;
                    }

                    this.beginScrub(context.mouseX);
                }

                return true;
            }

            if (context.mouseX < this.laneArea.x)
            {
                this.clickHeader(context);

                return true;
            }

            this.clickLane(context);

            return true;
        }

        if (context.mouseButton == 1)
        {
            int row = this.rowAt(context.mouseY);
            List<CompLayer> rows = this.rows();

            this.contextLayerId = row >= 0 && row < rows.size() ? rows.get(row).id : null;

            if (row >= 0 && row < rows.size())
            {
                this.selectWithModifiers(rows.get(row));
            }

            /* Let UIElement open the context menu after this handler. */
            return false;
        }

        if (context.mouseButton == 2)
        {
            this.dragMode = MODE_NAVIGATE;
            this.dragStartX = context.mouseX;
            this.dragStartY = context.mouseY;
            this.dragShift = this.xAxis.getShift();

            return true;
        }

        return super.subMouseClicked(context);
    }

    /**
     * Click selection with the usual modifiers: plain click replaces, Ctrl/Cmd toggles, Shift
     * extends the range from the primary. This is the only selection source for the editor (the
     * inspector and the preview follow it), so multi-select lives here, not in a second list.
     */
    private void selectWithModifiers(CompLayer layer)
    {
        if (Window.isCtrlPressed())
        {
            this.panel.toggleLayerSelection(layer);
        }
        else if (Window.isShiftPressed())
        {
            this.panel.selectLayerRange(layer);
        }
        else
        {
            this.panel.selectLayer(layer);
        }
    }

    private void clickHeader(UIContext context)
    {
        int row = this.rowAt(context.mouseY);
        List<CompLayer> rows = this.rows();

        if (row < 0 || row >= rows.size())
        {
            return;
        }

        CompLayer layer = rows.get(row);
        int eyeX = this.headerArea.ex() - 36;
        int lockX = this.headerArea.ex() - 19;
        int parentX = this.headerArea.ex() - 53;
        int threeDX = this.headerArea.ex() - 70;

        if (context.mouseX < this.headerArea.x + REORDER_GRIP)
        {
            this.selectWithModifiers(layer);
            this.dragMode = MODE_REORDER;
            this.dragId = layer.id;
            this.dragStartY = context.mouseY;
            this.dragReorderTarget = row;

            return;
        }

        if (context.mouseX >= eyeX && context.mouseX < eyeX + 16)
        {
            this.panel.toggleLayerVisible(layer);

            return;
        }

        if (context.mouseX >= lockX && context.mouseX < lockX + 16)
        {
            this.panel.toggleLayerLocked(layer);

            return;
        }

        if (context.mouseX >= threeDX && context.mouseX < threeDX + 16)
        {
            this.panel.toggleLayer3D(layer);

            return;
        }

        if (context.mouseX >= parentX && context.mouseX < parentX + 16)
        {
            this.showParentMenu(layer);

            return;
        }

        long now = System.currentTimeMillis();
        boolean twice = row == this.lastClickRow && now - this.lastClickTime < 300L;

        this.lastClickRow = row;
        this.lastClickTime = now;

        this.selectWithModifiers(layer);

        if (twice)
        {
            this.panel.renameLayer(layer);
        }
    }

    private void showParentMenu(CompLayer layer)
    {
        this.panel.chooseParent(layer);
    }

    private void clickLane(UIContext context)
    {
        int row = this.rowAt(context.mouseY);
        List<CompLayer> rows = this.rows();
        float time = this.fromX(context.mouseX);

        if (row >= 0 && row == this.audioRowIndex())
        {
            this.clickAudioLane(context);

            return;
        }

        if (row < 0 || row >= rows.size())
        {
            this.panel.clearSelection();
            this.panel.setFrame(Math.round(time * this.fps()));

            return;
        }

        CompLayer layer = rows.get(row);

        this.selectWithModifiers(layer);

        int start = this.toX(layer.in);
        int finish = this.toX(layer.out);

        if (finish <= start)
        {
            finish = start + 2;
        }

        boolean onBar = context.mouseX >= start - 2 && context.mouseX <= finish + 2;

        if (!onBar)
        {
            this.panel.setFrame(Math.round(time * this.fps()));

            return;
        }

        /* Double-clicking a precomp bar opens it, which is the AE gesture people expect. */
        long now = System.currentTimeMillis();
        boolean twice = row == this.lastLaneRow && now - this.lastLaneTime < 350L;

        this.lastLaneRow = row;
        this.lastLaneTime = now;

        if (twice && "precomp".equals(layer.type))
        {
            this.panel.enterPrecomp(layer);

            return;
        }

        /* Keyframe ticks used to be decoration: clicking one either moved the playhead or
         * started a clip drag. Hit-test them before the bar drag so the visible affordance is
         * real, and let the panel select the key + align the playhead. */
        Keyframe keyframe = this.keyframeAt(layer, row, context);

        if (keyframe != null)
        {
            this.panel.selectKeyframe(layer, keyframe.getTick());

            return;
        }

        if (layer.locked)
        {
            this.panel.setStatus("轨道已锁定：先解锁再移动或裁剪");

            return;
        }

        this.dragId = layer.id;
        this.dragStartX = context.mouseX;
        this.dragStartY = context.mouseY;
        this.dragIn = layer.in;
        this.dragOut = layer.out;

        if (context.mouseX - start <= EDGE)
        {
            this.dragMode = MODE_TRIM_IN;
        }
        else if (finish - context.mouseX <= EDGE)
        {
            this.dragMode = MODE_TRIM_OUT;
        }
        else
        {
            this.dragMode = MODE_MOVE;
        }

        this.xAxis.stopZoom();
    }

    /**
     * Audio lane click: select the clip under the cursor, then arm a move or an edge trim.
     *
     * <p>Same three zones as a layer bar (8 px at either end), because a user who has learned to
     * trim a layer should not have to learn a second gesture for a music cue.</p>
     */
    private void clickAudioLane(UIContext context)
    {
        dev.bbsae.comp.CompAudio clip = this.audioAt(context.mouseX);

        if (clip == null)
        {
            this.panel.selectAudioClip(null);
            this.panel.setFrame(Math.round(this.fromX(context.mouseX) * this.fps()));

            return;
        }

        this.panel.selectAudioClip(clip);

        int start = this.toX(clip.start);
        int finish = this.toX(clip.start + this.audioLength(clip));

        this.dragAudio = clip;
        this.dragStartX = context.mouseX;
        this.dragAudioStart = clip.start;
        this.dragAudioOffset = clip.offset;
        this.dragAudioLength = this.audioLength(clip);

        if (context.mouseX - start <= EDGE)
        {
            this.dragMode = MODE_AUDIO_TRIM_IN;
        }
        else if (finish - context.mouseX <= EDGE)
        {
            this.dragMode = MODE_AUDIO_TRIM_OUT;
        }
        else
        {
            this.dragMode = MODE_AUDIO_MOVE;
        }

        this.xAxis.stopZoom();
    }

    private void dragAudioClip(UIContext context)
    {
        dev.bbsae.comp.CompAudio clip = this.dragAudio;

        if (clip == null)
        {
            this.endDrag();

            return;
        }

        float delta = this.fromX(context.mouseX) - this.fromX(this.dragStartX);
        float frame = 1F / this.fps();
        float duration = this.duration();
        float length = Math.max(frame, this.dragAudioLength);

        if (this.dragMode == MODE_AUDIO_MOVE)
        {
            clip.start = this.clamp(this.quantize(this.dragAudioStart + delta), 0F, Math.max(0F, duration - frame));
            this.panel.setStatus(String.format(Locale.ROOT, "音频 %s 入点 %.2fs", clip.name, clip.start));
        }
        else if (this.dragMode == MODE_AUDIO_TRIM_IN)
        {
            float newStart = this.clamp(this.quantize(this.dragAudioStart + delta), 0F, this.dragAudioStart + length - frame);
            float shift = newStart - this.dragAudioStart;
            float newOffset = this.dragAudioOffset + shift;

            /* Dragging the in-point left of the source start cannot invent audio: stop at 0 and
             * keep the tail where it was. */
            if (newOffset < 0F)
            {
                newStart -= newOffset;
                newOffset = 0F;
            }

            clip.start = Math.max(0F, newStart);
            clip.offset = Math.max(0F, newOffset);
            clip.duration = Math.max(frame, length - (clip.start - this.dragAudioStart));
            this.panel.setStatus(String.format(Locale.ROOT, "音频入点 %.2fs（源偏移 %.2fs）", clip.start, clip.offset));
        }
        else
        {
            clip.duration = this.clamp(this.quantize(length + delta), frame, Math.max(frame, duration - clip.start));
            this.panel.setStatus(String.format(Locale.ROOT, "音频时长 %.2fs", clip.duration));
        }
    }

    private void beginScrub(int mouseX)
    {
        CompDocument doc = this.doc();

        if (doc != null && (doc.workAreaIn > 0.0001F || doc.workAreaOut < doc.duration - 0.0001F))
        {
            if (Math.abs(mouseX - this.toX(doc.workAreaIn)) <= 6)
            {
                this.dragMode = MODE_WORK_IN;

                return;
            }

            if (Math.abs(mouseX - this.toX(doc.workAreaOut)) <= 6)
            {
                this.dragMode = MODE_WORK_OUT;

                return;
            }
        }

        this.dragMode = MODE_SCRUB;
        this.panel.setFrame(Math.round(this.fromX(mouseX) * this.fps()));
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        if (this.dragMode != MODE_NONE)
        {
            this.endDrag();

            return true;
        }

        return super.subMouseReleased(context);
    }

    private void handleDrag(UIContext context)
    {
        if (this.dragMode == MODE_NONE)
        {
            return;
        }

        if (!Window.isMouseButtonPressed(0))
        {
            this.endDrag();

            return;
        }

        if (this.dragMode == MODE_SCRUB)
        {
            this.panel.setFrame(Math.round(this.fromX(context.mouseX) * this.fps()));

            return;
        }

        if (this.dragMode == MODE_NAVIGATE)
        {
            this.fitMode = false;
            this.xAxis.setShift(this.dragShift - (context.mouseX - this.dragStartX) / Math.max(0.0001D, this.xAxis.getZoom()));

            return;
        }

        if (this.dragMode == MODE_REORDER)
        {
            this.dragReorderTarget = this.reorderTarget(context.mouseY);

            return;
        }

        if (this.dragMode == MODE_WORK_IN || this.dragMode == MODE_WORK_OUT)
        {
            CompDocument doc = this.doc();

            if (doc != null)
            {
                float frame = 1F / this.fps();
                float value = this.quantize(this.fromX(context.mouseX));

                if (this.dragMode == MODE_WORK_IN)
                {
                    doc.workAreaIn = this.clamp(value, 0F, Math.max(0F, doc.workAreaOut - frame));
                    this.panel.setStatus(String.format(Locale.ROOT, "工作区入点 %.2fs", doc.workAreaIn));
                }
                else
                {
                    doc.workAreaOut = this.clamp(value, Math.min(doc.duration, doc.workAreaIn + frame), doc.duration);
                    this.panel.setStatus(String.format(Locale.ROOT, "工作区出点 %.2fs", doc.workAreaOut));
                }
            }

            return;
        }

        if (this.dragMode == MODE_AUDIO_MOVE || this.dragMode == MODE_AUDIO_TRIM_IN || this.dragMode == MODE_AUDIO_TRIM_OUT)
        {
            this.dragAudioClip(context);

            return;
        }

        CompLayer layer = this.findLayer(this.dragId);

        if (layer == null)
        {
            this.endDrag();

            return;
        }

        float delta = this.fromX(context.mouseX) - this.fromX(this.dragStartX);
        float fps = this.fps();
        float duration = this.duration();
        float frame = 1F / fps;
        List<Float> snapPoints = this.snapPoints(layer);
        float threshold = 8F / (float) Math.max(0.0001D, this.xAxis.getZoom());
        boolean snap = this.snapping && !Window.isShiftPressed();

        if (this.dragMode == MODE_MOVE)
        {
            float length = Math.max(frame, this.dragOut - this.dragIn);
            float newIn = this.clamp(this.quantize(this.dragIn + delta), 0F, Math.max(0F, duration - length));

            if (snap)
            {
                float snappedIn = this.snapValue(newIn, snapPoints, threshold);
                float snappedOut = this.snapValue(newIn + length, snapPoints, threshold);
                float dIn = Math.abs(snappedIn - newIn);
                float dOut = Math.abs(snappedOut - (newIn + length));

                if (dIn <= dOut && dIn > 0F)
                {
                    newIn = snappedIn;
                    this.snapGuide = snappedIn;
                }
                else if (dOut > 0F)
                {
                    newIn = snappedOut - length;
                    this.snapGuide = snappedOut;
                }
            }

            newIn = this.clamp(newIn, 0F, Math.max(0F, duration - length));
            layer.moveTo(newIn, fps, duration);
        }
        else if (this.dragMode == MODE_TRIM_IN)
        {
            float newIn = this.clamp(this.quantize(this.dragIn + delta), 0F, layer.out - frame);

            if (snap)
            {
                float snapped = this.snapValue(newIn, snapPoints, threshold);

                if (snapped != newIn)
                {
                    newIn = snapped;
                    this.snapGuide = snapped;
                }
            }

            layer.trimInTo(Math.min(newIn, layer.out - frame), fps);
        }
        else if (this.dragMode == MODE_TRIM_OUT)
        {
            float newOut = this.clamp(this.quantize(this.dragOut + delta), layer.in + frame, duration);

            if (snap)
            {
                float snapped = this.snapValue(newOut, snapPoints, threshold);

                if (snapped != newOut)
                {
                    newOut = snapped;
                    this.snapGuide = snapped;
                }
            }

            layer.trimOutTo(Math.min(duration, Math.max(layer.in + frame, newOut)), fps, duration);
        }
    }

    private void endDrag()
    {
        boolean reorder = this.dragMode == MODE_REORDER;
        boolean edited = this.dragMode == MODE_MOVE || this.dragMode == MODE_TRIM_IN || this.dragMode == MODE_TRIM_OUT
            || this.dragMode == MODE_WORK_IN || this.dragMode == MODE_WORK_OUT
            || this.dragMode == MODE_AUDIO_MOVE || this.dragMode == MODE_AUDIO_TRIM_IN || this.dragMode == MODE_AUDIO_TRIM_OUT;

        if (reorder)
        {
            CompLayer layer = this.findLayer(this.dragId);

            if (layer != null)
            {
                this.panel.moveLayerToDisplayIndex(layer, this.dragReorderTarget);
            }
        }

        this.dragMode = MODE_NONE;
        this.dragId = null;
        this.dragAudio = null;
        this.dragReorderTarget = -1;
        this.snapGuide = Float.NaN;

        if (edited)
        {
            this.panel.onTrackEdited();
        }
    }

    private float clamp(float value, float min, float max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private float snapValue(float value, List<Float> points, float threshold)
    {
        float best = value;
        float bestDistance = threshold;

        for (float point : points)
        {
            float distance = Math.abs(point - value);

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = point;
            }
        }

        return best;
    }

    private List<Float> snapPoints(CompLayer moving)
    {
        List<Float> points = new ArrayList<>();
        CompDocument doc = this.doc();

        points.add(this.panel.getTime());
        points.add(0F);
        points.add(this.duration());

        if (doc != null)
        {
            if (doc.workAreaIn > 0F) points.add(doc.workAreaIn);
            if (doc.workAreaOut > 0F) points.add(doc.workAreaOut);

            for (CompMarker marker : doc.markers)
            {
                points.add(marker.time);
            }
        }

        for (CompLayer layer : this.rows())
        {
            if (layer == moving)
            {
                continue;
            }

            points.add(layer.in);
            points.add(layer.out);
        }

        return points;
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            return super.subMouseScrolled(context);
        }

        double wheel = context.mouseWheel;

        /* Some mice/drivers report Alt+wheel as a horizontal scroll. Treat either axis as the
         * gesture instead of silently doing nothing. */
        if (Math.abs(wheel) < 0.0001D)
        {
            wheel = context.mouseWheelHorizontal;
        }

        boolean alt = altDown();
        boolean ctrl = ctrlDown();
        boolean shift = shiftDown();
        float anchor = Scale.getAnchorX(context, this.laneArea);

        /* Alt+wheel resizes the tracks. This is the gesture the user asked for, and it matches
         * the documentation and every NLE: Alt/Option+wheel is vertical zoom of the track rows,
         * while the time axis is zoomed with Ctrl+wheel (or Alt+Shift+wheel). Row height moved
         * here from Alt+Shift because "Alt+wheel does nothing to my tracks" was the bug. */
        if (alt && !shift)
        {
            int step = wheel > 0 ? 2 : -2;
            int next = Math.max(ROW_MIN, Math.min(ROW_MAX, this.rowHeight + step));

            if (next != this.rowHeight)
            {
                this.rowHeight = next;
                this.clampScroll();
                this.panel.setStatus("轨道高度 " + this.rowHeight + "px（Alt+滚轮）");
            }
        }
        else if (alt)
        {
            this.zoomAt(anchor, wheel);
        }
        else if (ctrl)
        {
            this.zoomAt(anchor, wheel);
        }
        else if (shift)
        {
            this.fitMode = false;
            this.xAxis.setShift(this.xAxis.getShift() - (40D * wheel) / Math.max(0.0001D, this.xAxis.getZoom()));
        }
        else
        {
            this.scrollY -= (int) Math.round(wheel * this.rowHeight * 1.5D);
            this.clampScroll();
        }

        return true;
    }

    /**
     * Modifier state straight from GLFW. Minecraft's {@code Screen.hasAltDown()} only reflects
     * keys the vanilla screen has seen, which is not reliable inside a BBS dashboard panel; that
     * was why Alt+wheel appeared dead.
     */
    private static boolean altDown()
    {
        return CompInput.altDown();
    }

    private static boolean ctrlDown()
    {
        return CompInput.ctrlDown();
    }

    private static boolean shiftDown()
    {
        return CompInput.shiftDown();
    }

    /* Helpers */

    private CompLayer findLayer(String id)
    {
        if (id == null || this.doc() == null)
        {
            return null;
        }

        for (CompLayer layer : this.doc().layers)
        {
            if (id.equals(layer.id))
            {
                return layer;
            }
        }

        return null;
    }

    private String layerName(CompLayer layer)
    {
        return layer.name == null || layer.name.isBlank() ? layer.id : layer.name;
    }

    /** Track label; a precomp shows its child count so the container reads as a container. */
    private String laneLabel(CompLayer layer)
    {
        StringBuilder flags = new StringBuilder();

        /* Solo/matte/mask/style were only visible in the inspector, so a soloed layer made the
         * whole comp look broken with no clue on the timeline. Put the flags first so they
         * survive the label's clipping. */
        if (layer.solo)
        {
            flags.append("独显 ");
        }

        if (layer.trackMatteEnabled && layer.trackMatteId != null && !layer.trackMatteId.isEmpty())
        {
            flags.append("遮罩 ");
        }

        if (layer.masks != null && !layer.masks.isEmpty())
        {
            flags.append("蒙版 ");
        }

        if (layer.styles != null && !layer.styles.isEmpty())
        {
            flags.append("样式 ");
        }

        String label = flags + this.layerName(layer);

        if ("precomp".equals(layer.type) && layer.precompId != null && !layer.precompId.isEmpty()
            && this.panel.getProject() != null)
        {
            CompDocument sub = this.panel.getProject().getComp(layer.precompId);

            if (sub != null)
            {
                label += "  ·  " + sub.layers.size() + " 层";
            }
        }

        return label;
    }

    private int barColor(CompLayer layer)
    {
        return switch (layer.type == null ? "" : layer.type)
        {
            case "text" -> 0xFF3E6FB0;
            case "shape" -> 0xFF4E9A6B;
            case "image", "footage" -> 0xFF8A6BB8;
            case "precomp" -> 0xFFB07A3E;
            case "adjustment" -> 0xFFB04E5A;
            case "null" -> 0xFF5A6270;
            case "camera" -> 0xFF8A6A3E;
            case "bbs" -> 0xFF3E7A8A;
            case "replay" -> 0xFF3E8A5E;
            default -> 0xFF44546A;
        };
    }

    private mchorse.bbs_mod.ui.utils.icons.Icon typeIcon(CompLayer layer)
    {
        return switch (layer.type == null ? "" : layer.type)
        {
            case "text" -> Icons.FONT;
            case "shape" -> Icons.SHAPES;
            case "image" -> Icons.IMAGE;
            case "footage" -> Icons.FILM;
            case "precomp" -> Icons.STRUCTURE;
            case "null" -> Icons.OUTLINE_SPHERE;
            case "adjustment" -> Icons.SUN;
            case "camera" -> Icons.CAMERA;
            case "bbs" -> Icons.FILM;
            case "replay" -> Icons.SPHERE;
            default -> Icons.SQUARE;
        };
    }

    private void updateCursor(UIContext context)
    {
        if (this.dragMode == MODE_TRIM_IN || this.dragMode == MODE_TRIM_OUT)
        {
            context.requestCursor(GLFW.GLFW_HRESIZE_CURSOR);

            return;
        }

        if (this.dragMode == MODE_MOVE)
        {
            context.requestCursor(GLFW.GLFW_HAND_CURSOR);

            return;
        }

        if (this.dragMode == MODE_REORDER)
        {
            context.requestCursor(GLFW.GLFW_VRESIZE_CURSOR);

            return;
        }

        if (this.area.isInside(context)
            && context.mouseY >= this.laneArea.y
            && context.mouseX >= this.headerArea.x
            && context.mouseX < this.headerArea.x + REORDER_GRIP
            && this.rowAt(context.mouseY) >= 0)
        {
            context.requestCursor(GLFW.GLFW_VRESIZE_CURSOR);

            return;
        }

        if (!this.area.isInside(context) || context.mouseY < this.laneArea.y || context.mouseX < this.laneArea.x)
        {
            return;
        }

        int row = this.rowAt(context.mouseY);
        List<CompLayer> rows = this.rows();

        if (row < 0 || row >= rows.size())
        {
            return;
        }

        CompLayer layer = rows.get(row);
        int start = this.toX(layer.in);
        int finish = this.toX(layer.out);

        if (Math.abs(context.mouseX - start) <= EDGE || Math.abs(context.mouseX - finish) <= EDGE)
        {
            context.requestCursor(GLFW.GLFW_HRESIZE_CURSOR);
        }
        else if (context.mouseX > start && context.mouseX < finish)
        {
            context.requestCursor(GLFW.GLFW_HAND_CURSOR);
        }
    }

    /** Called by the panel after an undo/redo or a layer swap, because the track list changed. */
    public void refresh()
    {
        this.clampScroll();
        this.ensureSelectionVisible();
    }
}
