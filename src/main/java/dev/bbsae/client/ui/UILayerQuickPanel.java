package dev.bbsae.client.ui;

import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.ArrayList;
import java.util.List;

/**
 * The layer quick panel: transform, 3D axes, opacity and BBS's interpolation picker, docked to
 * the right of the timeline.
 *
 * <h2>Why it exists</h2>
 * <p>Keyframing a move meant scrolling the inspector all the way down to the transform rows,
 * typing a number, then coming back to the timeline to add the key — for every key. The values
 * live next to the tracks here, so "set X, key it" is one glance away from the playhead.</p>
 *
 * <h2>3D axis editing</h2>
 * <p>A 3D layer has nine transform components, but only five live in BBS's transform keyframe
 * vector. Z depth, pitch, yaw and Z scale are stored in the layer's scalar channels (the same
 * channels {@code CompScene} reads), so they get their own rows and can be keyed independently.
 * The panel switches from the compact 2D layout to the full 3D layout when the selected layer is
 * a 3D layer; nothing is hidden behind a tab.</p>
 *
 * <h2>BBS interpolation picker</h2>
 * <p>The curve button opens BBS's own {@code UIInterpolationContextMenu} for the selected
 * keyframe(s) — the same icon grid, bezier arguments and copy/paste the film editor uses. The
 * quick panel never re-implements interpolation.</p>
 */
public class UILayerQuickPanel extends UIElement
{
    private static final int PAD = 6;
    private static final int ROW = 16;
    private static final int GROUP = 13;
    private static final int KEY = 16;
    private static final int LABEL = 46;

    private final UICompositorPanel panel;
    private final UILabel title;
    private final UICompButton curve;
    private final UICompButton keyPosition;
    private final UICompButton keyRotation;
    private final UICompButton keyScale;
    private final UICompButton keyOpacity;

    private final UITrackpad x;
    private final UITrackpad y;
    private final UITrackpad z;
    private final UITrackpad rotation;
    private final UITrackpad rotationX;
    private final UITrackpad rotationY;
    private final UITrackpad scale;
    private final UITrackpad scaleX;
    private final UITrackpad scaleY;
    private final UITrackpad scaleZ;
    private final UITrackpad opacity;

    private final UILabel hint;

    private final List<Row> rows = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();

    /** Last (layer, frame) the fields were filled from, so typing is never overwritten. */
    private String shownLayer;
    private float shownFrame = Float.NaN;
    private boolean shownThreeD;

    private boolean resizing;
    private int resizeStartX;
    private int resizeStartWidth;
    private final UiMotion motion = new UiMotion();

    private static final class Row
    {
        final String label;
        final UITrackpad field;
        final int y;

        Row(String label, UITrackpad field, int y)
        {
            this.label = label;
            this.field = field;
            this.y = y;
        }
    }

    private static final class Group
    {
        final String label;
        final UICompButton key;
        final int y;

        Group(String label, UICompButton key, int y)
        {
            this.label = label;
            this.key = key;
            this.y = y;
        }
    }

    public UILayerQuickPanel(UICompositorPanel panel)
    {
        this.panel = panel;

        this.title = new UILabel(IKey.constant("图层"));
        this.title.color(0xFFC8D3E3, false);

        this.curve = new UICompButton(IKey.constant("插值曲线"), (button) -> this.panel.openCurveMenu());
        this.curve.tooltip(IKey.constant("BBS 原生插值曲线：先选中关键帧，再选曲线类型（贝塞尔/缓动/自动…）"));

        this.keyPosition = groupKey("在当前帧为位置打关键帧（含 3D 图层的 Z 深度）", this.panel::quickKeyPosition);
        this.keyRotation = groupKey("在当前帧为旋转打关键帧（含 3D 图层的 X/Y 旋转）", this.panel::quickKeyRotation);
        this.keyScale = groupKey("在当前帧为缩放打关键帧（含 3D 图层的 Z 缩放）", this.panel::quickKeyScale);
        this.keyOpacity = groupKey("在当前帧为不透明度打关键帧", this.panel::quickKeyOpacity);

        this.x = field((value) -> panel.quickSetX(value), "位置 X（合成像素）");
        this.y = field((value) -> panel.quickSetY(value), "位置 Y（合成像素）");
        this.z = field((value) -> panel.quickSetZ(value), "位置 Z / 深度（正数朝向观众；仅 3D 图层参与透视）");
        this.rotation = field((value) -> panel.quickSetRotation(value), "旋转 Z（度）");
        this.rotationX = field((value) -> panel.quickSetRotationX(value), "旋转 X / 俯仰（度；仅 3D 图层）");
        this.rotationY = field((value) -> panel.quickSetRotationY(value), "旋转 Y / 偏航（度；仅 3D 图层）");
        this.scale = field((value) -> panel.quickSetScale(value), "缩放（百分比；同时作用于 X/Y）");
        this.scaleX = field((value) -> panel.quickSetScaleX(value), "缩放 X（百分比；仅 3D 图层）");
        this.scaleY = field((value) -> panel.quickSetScaleY(value), "缩放 Y（百分比；仅 3D 图层）");
        this.scaleZ = field((value) -> panel.quickSetScaleZ(value), "缩放 Z（百分比；仅 3D 图层）");
        this.opacity = field((value) -> panel.quickSetOpacity(value), "不透明度（0–100）");

        this.hint = new UILabel(IKey.constant("◆ = 打关键帧 · 右键数值可输入"));
        this.hint.color(0xFF6F7B8D, false);

        this.add(this.title, this.curve);
        this.add(this.keyPosition, this.keyRotation, this.keyScale, this.keyOpacity);
        this.add(this.x, this.y, this.z, this.rotation, this.rotationX, this.rotationY,
            this.scale, this.scaleX, this.scaleY, this.scaleZ, this.opacity);
        this.add(this.hint);

        this.layoutRows(false);
    }

    private UICompButton groupKey(String tooltip, Runnable action)
    {
        UICompButton button = new UICompButton(IKey.constant("◆"), (b) -> action.run());

        button.tooltip(IKey.constant(tooltip));
        button.w(KEY).h(GROUP);

        return button;
    }

    private UITrackpad field(java.util.function.DoubleConsumer consumer, String tooltip)
    {
        UITrackpad trackpad = new UITrackpad((value) -> consumer.accept(value));

        trackpad.tooltip(IKey.constant(tooltip));
        trackpad.limit(-100000D, 100000D, false);

        return trackpad;
    }

    /**
     * Rebuilds the absolute row geometry for the selected layer's dimensionality.
     *
     * <p>The 3D layout only needs four group headers and ten value rows, which fits in the default
     * 268 px timeline. The 2D layout keeps the original five rows. Group keys are one button per
     * group: a transform keyframe in BBS stores the whole transform vector, so a per-component key
     * button would silently key all the other components anyway.</p>
     */
    private void layoutRows(boolean threeD)
    {
        this.shownThreeD = threeD;
        this.rows.clear();
        this.groups.clear();

        for (UITrackpad field : new UITrackpad[] {this.x, this.y, this.z, this.rotation,
            this.rotationX, this.rotationY, this.scale, this.scaleX, this.scaleY, this.scaleZ,
            this.opacity})
        {
            field.setVisible(false);
        }

        this.title.relative(this).x(PAD).y(4).w(1F, -(PAD * 2 + 76)).h(16);
        this.curve.relative(this).x(1F, -(PAD + 70)).y(3).w(70).h(18);

        int y = 26;

        y = this.group("位置", this.keyPosition, y);
        y = this.row("X", this.x, y);
        y = this.row("Y", this.y, y);

        if (threeD)
        {
            y = this.row("Z / 深度", this.z, y);
        }

        y += 2;
        y = this.group("旋转", this.keyRotation, y);
        y = this.row("Z", this.rotation, y);

        if (threeD)
        {
            y = this.row("X / 俯仰", this.rotationX, y);
            y = this.row("Y / 偏航", this.rotationY, y);
        }

        y += 2;
        y = this.group("缩放", this.keyScale, y);

        if (threeD)
        {
            y = this.row("X", this.scaleX, y);
            y = this.row("Y", this.scaleY, y);
            y = this.row("Z", this.scaleZ, y);
        }
        else
        {
            y = this.row("统一", this.scale, y);
        }

        y += 2;
        y = this.group("不透明度", this.keyOpacity, y);
        y = this.row("", this.opacity, y);

        this.hint.relative(this).x(PAD).y(y + 2).w(1F, -PAD * 2).h(12);
    }

    private int group(String label, UICompButton key, int y)
    {
        this.groups.add(new Group(label, key, y));

        key.relative(this).x(1F, -(PAD + KEY)).y(y + 1).w(KEY).h(GROUP - 2);
        key.setVisible(true);

        return y + GROUP;
    }

    private int row(String label, UITrackpad field, int y)
    {
        this.rows.add(new Row(label, field, y));

        field.relative(this).x(PAD + LABEL).y(y).w(1F, -(PAD + LABEL + KEY + PAD + 2)).h(ROW);
        field.setVisible(true);

        return y + ROW;
    }

    /** Fills the fields from the model when the selection, playhead or values changed. */
    private void syncFields()
    {
        CompLayer layer = this.panel.getSelectedLayer();
        float frame = this.panel.getFrame();
        boolean threeD = layer != null && layer.threeD;

        if (threeD != this.shownThreeD)
        {
            this.layoutRows(threeD);
            this.resize();
            /* Force a full refill: the same layer/frame now has a different set of rows and a
             * different title suffix. */
            this.shownLayer = null;
        }

        String id = layer == null ? null : layer.id;

        if (layer == null)
        {
            if (this.shownLayer == null && Float.compare(this.shownFrame, frame) == 0)
            {
                return;
            }

            this.shownLayer = null;
            this.shownFrame = frame;
            this.title.label = IKey.constant("图层：未选择");

            return;
        }

        Transform transform = layer.transformAt(frame);
        double nx = round(transform.translate.x);
        double ny = round(transform.translate.y);
        double nz = round(layer.scalarAt("z", layer.z, frame));
        double nrot = round(transform.rotate.z);
        double nrotX = round(layer.scalarAt("rotationX", layer.rotationX, frame));
        double nrotY = round(layer.scalarAt("rotationY", layer.rotationY, frame));
        double nscale = round(transform.scale.x * 100F);
        double nscaleX = round(transform.scale.x * 100F);
        double nscaleY = round(transform.scale.y * 100F);
        double nscaleZ = round(layer.scalarAt("scaleZ", layer.scaleZ, frame) * 100F);
        double nopacity = round(layer.opacityFrame(frame) * 100F);

        boolean sameTarget = id.equals(this.shownLayer) && Float.compare(this.shownFrame, frame) == 0;

        if (sameTarget)
        {
            boolean sameValues = same(this.x, nx) && same(this.y, ny) && same(this.z, nz)
                && same(this.rotation, nrot) && same(this.rotationX, nrotX) && same(this.rotationY, nrotY)
                && same(this.scale, nscale) && same(this.scaleX, nscaleX) && same(this.scaleY, nscaleY)
                && same(this.scaleZ, nscaleZ) && same(this.opacity, nopacity);

            /* Same layer at the same frame, but the model moved (gizmo drag, undo, curve edit):
             * a parked playhead used to freeze the readout on stale numbers. While the left
             * button is held the fields may be mid-edit — never fight the user's typing. */
            if (sameValues || Window.isMouseButtonPressed(0))
            {
                return;
            }
        }

        this.shownLayer = id;
        this.shownFrame = frame;
        this.title.label = IKey.constant(this.panel.layerLabel(layer) + (threeD ? " · 3D" : ""));

        this.x.setValue(nx);
        this.y.setValue(ny);
        this.z.setValue(nz);
        this.rotation.setValue(nrot);
        this.rotationX.setValue(nrotX);
        this.rotationY.setValue(nrotY);
        this.scale.setValue(nscale);
        this.scaleX.setValue(nscaleX);
        this.scaleY.setValue(nscaleY);
        this.scaleZ.setValue(nscaleZ);
        this.opacity.setValue(nopacity);
    }

    private static boolean same(UITrackpad field, double value)
    {
        return Math.abs(field.getValue() - value) < 0.05D;
    }

    private static double round(float value)
    {
        return Math.round(value * 10D) / 10D;
    }

    @Override
    public void render(UIContext context)
    {
        this.syncFields();
        this.applyResize(context);
        this.motion.tickFrame();
        this.motion.markShown();

        int accent = mchorse.bbs_mod.BBSSettings.primaryColor() | 0xFF000000;

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF11151C);
        UiGfx.gradientV(context.batcher, this.area.x, this.area.y, this.area.w, 42,
            UiGfx.alpha(accent, 0.06F), 0x00FFFFFF);
        context.batcher.box(this.area.x, this.area.y, this.area.x + 1, this.area.ey(), 0xFF2A303A);
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, 0xFF2A303A);

        if (this.motion.time < 0.55F && CompPrefs.get().glow)
        {
            float intro = this.motion.intro(0.08F);

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 2,
                UiGfx.alpha(accent, 0.35F * (1F - intro)));
        }

        FontRenderer font = context.batcher.getFont();
        int groupColor = 0xFF9FB2C8;
        int labelColor = 0xFF8A94A6;

        for (Group group : this.groups)
        {
            context.batcher.textShadow(group.label, this.area.x + PAD, this.area.y + group.y
                + (GROUP - font.getHeight()) / 2F, groupColor);
            context.batcher.box(this.area.x + PAD - 4, this.area.y + group.y + 4,
                this.area.x + PAD - 2, this.area.y + group.y + GROUP - 4, 0x554C9AFF);
        }

        for (Row row : this.rows)
        {
            if (!row.label.isEmpty())
            {
                context.batcher.textShadow(row.label, this.area.x + PAD + 8, this.area.y + row.y
                    + (ROW - font.getHeight()) / 2F, labelColor);
            }
        }

        /* Resize grip. */
        context.batcher.box(this.area.x, this.area.y + 2, this.area.x + 3, this.area.ey() - 2, 0x334A5566);

        super.render(context);
    }

    private void applyResize(UIContext context)
    {
        boolean pressed = Window.isMouseButtonPressed(0);

        if (this.resizing && !pressed)
        {
            this.resizing = false;

            return;
        }

        if (!this.resizing)
        {
            if (pressed && this.area.isInside(context) && context.mouseX - this.area.x <= 4)
            {
                this.resizing = true;
                this.resizeStartX = context.mouseX;
                this.resizeStartWidth = this.area.w;
            }

            return;
        }

        int width = Math.max(160, Math.min(340, this.resizeStartWidth - (context.mouseX - this.resizeStartX)));

        if (width != this.area.w)
        {
            this.panel.setQuickPanelWidth(width);
        }
    }

    @Override
    public boolean canBeRendered(mchorse.bbs_mod.ui.utils.Area area)
    {
        return this.visible;
    }
}
