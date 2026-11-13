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
    private float drawerProgress = 1F;
    private boolean drawerOpen = true;
    private int motionFrames;

    private static final class Row
    {
        final String label;
        final UITrackpad field;
        final int y;
        /** Stable channel name; the row list is rebuilt, not rebuilt per frame. */
        final String hoverKey;

        Row(String label, UITrackpad field, int y, String hoverKey)
        {
            this.label = label;
            this.field = field;
            this.y = y;
            this.hoverKey = hoverKey;
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
        this.scaleX = field((value) -> panel.quickSetScaleX(value), "缩放 X（百分比）");
        this.scaleY = field((value) -> panel.quickSetScaleY(value), "缩放 Y（百分比）");
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

    /** Starts the right-side drawer slide instead of popping the panel in/out. */
    public void setDrawerOpen(boolean open)
    {
        if (this.drawerOpen == open && this.visible == open)
        {
            return;
        }

        this.drawerOpen = open;

        if (open)
        {
            if (!this.visible)
            {
                this.drawerProgress = 0F;
            }

            this.setVisible(true);
        }
    }

    public boolean isDrawerOpen()
    {
        return this.drawerOpen;
    }

    @Override
    public void setVisible(boolean visible)
    {
        if (!visible)
        {
            this.resizing = false;
        }

        super.setVisible(visible);
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
     * 268 px timeline. The 2D layout also uses separate scale X/Y rows; there is no UI shortcut
     * that hides the fact that they are independent curves now.</p>
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
            /* Separate dimensions, like AE: there is no implicit "uniform" channel. Editing X
             * must not silently add a Y key, and vice versa. */
            y = this.row("X", this.scaleX, y);
            y = this.row("Y", this.scaleY, y);
        }

        y += 2;
        y = this.group("不透明度", this.keyOpacity, y);
        y = this.row("", this.opacity, y);

        this.hint.relative(this).x(PAD).y(y + 2).w(1F, -PAD * 2).h(12);

        /* A different row set (2D ↔ 3D, or a fresh layer) is a new panel content: replay the
         * staggered reveal so the eye can follow the rows that appeared. */
        this.motion.replay();
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
        Row row = new Row(label, field, y, "qp-row:" + this.rows.size() + ":h");

        this.rows.add(row);

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

        /* A new selected layer is a content change: the group/row reveal replays once. Scrubbing
         * the playhead only changes numbers, so it must not re-animate the whole panel. */
        boolean layerChanged = !id.equals(this.shownLayer);

        this.shownLayer = id;
        this.shownFrame = frame;
        this.title.label = IKey.constant(this.panel.layerLabel(layer) + (threeD ? " · 3D" : ""));

        if (layerChanged)
        {
            this.motion.replay();
        }

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

        float target = this.drawerOpen ? 1F : 0F;

        this.drawerProgress = UiMotion.springStep(this.drawerProgress, target, 260F, 30F, 1F / 60F);

        if (!this.drawerOpen && this.drawerProgress < 0.02F)
        {
            this.setVisible(false);

            return;
        }

        int slideOffset = Math.round((1F - this.drawerProgress) * this.area.w);
        net.minecraft.client.util.math.MatrixStack stack = context.batcher.getContext().getMatrices();

        stack.push();

        if (slideOffset != 0)
        {
            stack.translate(slideOffset, 0F, 0F);
        }

        this.motion.tickFrame();

        if (++this.motionFrames >= UICompButton.PRUNE_EVERY)
        {
            this.motionFrames = 0;
            this.motion.prune(UICompButton.PRUNE_IDLE);
        }

        this.motion.markShown();

        /* Replayed on selection/dimensionality change, so a new layer's rows are laid out in
         * front of the user instead of appearing complete. */
        float reveal = this.motion.intro(0.04F);
        float appear = 0.35F + 0.65F * reveal;
        float intensity = this.motion.intensity();
        int accent = mchorse.bbs_mod.BBSSettings.primaryColor() | 0xFF000000;

        UiGfx.shadow(context.batcher, this.area.x + 2F, this.area.y + 2F,
            Math.max(0F, this.area.w - 4F), Math.max(0F, this.area.h - 4F), 5F,
            UiGfx.alpha(0xFF000000, 0.18F));
        UiGfx.roundedBox(context.batcher, this.area.x, this.area.y, this.area.w, this.area.h,
            4F, 0xFF11151C);
        UiGfx.gradientV(context.batcher, this.area.x, this.area.y, this.area.w, 42,
            UiGfx.alpha(accent, 0.06F * appear), 0x00FFFFFF);
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1,
            UiGfx.alpha(0xFFFFFFFF, 0.045F));
        UiGfx.gradientV(context.batcher, this.area.x, this.area.ey() - 56, this.area.w, 56,
            0x00FFFFFF, UiGfx.alpha(0xFF000000, 0.12F));
        context.batcher.box(this.area.x, this.area.y, this.area.x + 1, this.area.ey(), 0xFF2A303A);
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, 0xFF2A303A);

        if (reveal < 0.999F && CompPrefs.get().glow)
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 2,
                UiGfx.alpha(accent, 0.35F * (1F - reveal)));
        }

        FontRenderer font = context.batcher.getFont();
        int groupColor = 0xFF9FB2C8;
        int labelColor = 0xFF8A94A6;

        for (int g = 0; g < this.groups.size(); g++)
        {
            Group group = this.groups.get(g);
            float stagger = UiStagger.progress(this.motion, g, 0.02F, 0.14F);
            float alpha = 0.35F + 0.65F * stagger;
            float textY = this.area.y + group.y + (GROUP - font.getHeight()) / 2F
                + UiStagger.offsetPx(stagger, Math.round(2F * intensity));

            context.batcher.textShadow(group.label, this.area.x + PAD, textY,
                UiMotion.alpha(groupColor, alpha));
            context.batcher.box(this.area.x + PAD - 4, this.area.y + group.y + 4,
                this.area.x + PAD - 2, this.area.y + group.y + GROUP - 4,
                UiMotion.alpha(0x4C9AFF, 0.33F * alpha));
        }

        int rowBase = this.groups.size();

        for (int r = 0; r < this.rows.size(); r++)
        {
            Row row = this.rows.get(r);
            float stagger = UiStagger.progress(this.motion, rowBase + r, 0.012F, 0.18F);
            float alpha = 0.35F + 0.65F * stagger;
            /* The value widgets are BBS controls (their internals must not be touched); the row
             * label this panel draws itself can still answer the cursor. */
            boolean rowHover = !row.label.isEmpty()
                && context.mouseX >= this.area.x + PAD
                && context.mouseX < this.area.x + PAD + LABEL
                && context.mouseY >= this.area.y + row.y
                && context.mouseY < this.area.y + row.y + ROW;
            float hov = this.motion.anim(row.hoverKey, rowHover ? 1F : 0F, 24F);

            if (hov > 0.01F)
            {
                context.batcher.box(this.area.x + PAD + 2, this.area.y + row.y + 3,
                    this.area.x + PAD + 4, this.area.y + row.y + ROW - 3,
                    UiMotion.alpha(0x4C9AFF, 0.55F * hov * alpha));
            }

            if (!row.label.isEmpty())
            {
                int color = UiMotion.alpha(UiMotion.lerpColor(labelColor, 0xFFEAF3FF, hov), alpha);

                context.batcher.textShadow(row.label, this.area.x + PAD + 8, this.area.y + row.y
                    + (ROW - font.getHeight()) / 2F
                    + UiStagger.offsetPx(stagger, Math.round(2F * intensity)), color);
            }
        }

        /* Resize grip. */
        context.batcher.box(this.area.x, this.area.y + 2, this.area.x + 3, this.area.ey() - 2, 0x334A5566);

        super.render(context);

        stack.pop();
    }

    /**
     * A resize may only start from a press that landed on the 4 px left grip.
     *
     * <p>The old code started resizing from the per-frame poll: any time the left button was down
     * and the cursor happened to be within 4 px of the left edge, the panel began resizing. Dragging
     * a clip bar / track row across that edge therefore changed the quick-panel width — one of the
     * reported "dragging data also drags the panel size" bugs. Ownership now comes from the press,
     * not from where the pointer happens to be later.</p>
     */
    static boolean isGripPress(boolean visible, boolean inside, int deltaX)
    {
        return visible && inside && deltaX >= 0 && deltaX <= 4;
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0
            && isGripPress(this.visible, this.area.isInside(context), context.mouseX - this.area.x)
            && (this.panel == null || !this.panel.isPanelResizing()))
        {
            this.resizing = true;
            this.resizeStartX = context.mouseX;
            this.resizeStartWidth = this.area.w;

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        /* Explicit release ownership: a hidden/overlapped panel or a stuck cached mouse state must
         * never leave the width drag running after the user lets go. */
        if (this.resizing && context.mouseButton == 0)
        {
            this.resizing = false;

            return true;
        }

        return super.subMouseReleased(context);
    }

    private void applyResize(UIContext context)
    {
        if (!this.resizing)
        {
            return;
        }

        if (!Window.isMouseButtonPressed(0))
        {
            this.resizing = false;

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
