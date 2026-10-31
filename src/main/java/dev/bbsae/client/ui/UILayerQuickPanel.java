package dev.bbsae.client.ui;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.pose.Transform;
import mchorse.bbs_mod.graphics.window.Window;

/**
 * The layer quick panel: X / Y / Z / rotation / scale / opacity, docked to the right of the
 * timeline, with a keyframe button on every row.
 *
 * <h2>Why it exists</h2>
 * <p>Keyframing a move meant scrolling the inspector all the way down to the transform rows,
 * typing a number, then coming back to the timeline to add the key — for every key. That is three
 * context switches per keyframe. The values live next to the tracks here, so "set X, key it" is one
 * glance away from the playhead.</p>
 *
 * <p>It reads the same model the inspector does ({@code transformAt(frame)} and
 * {@code scalarAt}), and writes through the same commands ({@code setTransformComponent},
 * {@code addTransformKey}), so the two panels can never disagree.</p>
 */
public class UILayerQuickPanel extends UIElement
{
    private static final int ROW = 20;
    private static final int PAD = 6;

    private final UICompositorPanel panel;
    private final UILabel title;
    private final UICompButton keyTransform;
    private final UICompButton keyZ;
    private final UICompButton keyOpacity;
    private final UITrackpad x;
    private final UITrackpad y;
    private final UITrackpad z;
    private final UITrackpad rotation;
    private final UITrackpad scale;
    private final UITrackpad opacity;
    private final UILabel hint;

    /** Last (layer, frame) the fields were filled from, so typing is never overwritten. */
    private String shownLayer;
    private float shownFrame = Float.NaN;

    private boolean resizing;
    private int resizeStartX;
    private int resizeStartWidth;

    public UILayerQuickPanel(UICompositorPanel panel)
    {
        this.panel = panel;

        this.title = new UILabel(IKey.constant("图层"));
        this.title.color(0xFFFFC14D, false);

        this.keyTransform = smallKey("在当前帧为位置/旋转/缩放打一个变换关键帧", () -> panel.quickKeyTransform());
        this.keyZ = smallKey("在当前帧为 Z 打一个关键帧（3D 图层）", () -> panel.quickKeyZ());
        this.keyOpacity = smallKey("在当前帧为不透明度打一个关键帧", () -> panel.quickKeyOpacity());

        this.x = field((value) -> panel.quickSetX(value), "X 位置（合成像素）");
        this.y = field((value) -> panel.quickSetY(value), "Y 位置（合成像素）");
        this.z = field((value) -> panel.quickSetZ(value), "Z 深度（正数朝向观众；仅 3D 图层参与透视）");
        this.rotation = field((value) -> panel.quickSetRotation(value), "旋转（度）");
        this.scale = field((value) -> panel.quickSetScale(value), "缩放（百分比；同时作用于 X/Y）");
        this.opacity = field((value) -> panel.quickSetOpacity(value), "不透明度（0–100）");

        this.hint = new UILabel(IKey.constant("◆ = 打关键帧"));
        this.hint.color(0xFF6F7B8D, false);

        this.add(this.title, this.keyTransform, this.keyZ, this.keyOpacity,
            this.x, this.y, this.z, this.rotation, this.scale, this.opacity, this.hint);

        this.layoutRows();
    }

    private UICompButton smallKey(String tooltip, Runnable action)
    {
        UICompButton button = new UICompButton(IKey.constant("◆"), (b) -> action.run());

        button.tooltip(IKey.constant(tooltip));
        button.w(16).h(16);

        return button;
    }

    private UITrackpad field(java.util.function.DoubleConsumer consumer, String tooltip)
    {
        UITrackpad trackpad = new UITrackpad((value) -> consumer.accept(value));

        trackpad.tooltip(IKey.constant(tooltip));

        return trackpad;
    }

    /** Absolute rows: label + field + key, one per property. */
    private void layoutRows()
    {
        int y = PAD;

        this.title.relative(this).x(PAD).y(y).w(1F, -PAD * 2).h(14);
        y += 16;

        this.x.relative(this).x(PAD + 18).y(y).w(1F, -(PAD * 2 + 18 + 22)).h(16);
        y += ROW;
        this.y.relative(this).x(PAD + 18).y(y).w(1F, -(PAD * 2 + 18 + 22)).h(16);
        y += ROW;
        this.z.relative(this).x(PAD + 18).y(y).w(1F, -(PAD * 2 + 18 + 22)).h(16);
        y += ROW;
        this.rotation.relative(this).x(PAD + 18).y(y).w(1F, -(PAD * 2 + 18 + 22)).h(16);
        y += ROW;
        this.scale.relative(this).x(PAD + 18).y(y).w(1F, -(PAD * 2 + 18 + 22)).h(16);
        y += ROW;
        this.opacity.relative(this).x(PAD + 18).y(y).w(1F, -(PAD * 2 + 18 + 22)).h(16);
        y += ROW;

        /* Keys sit at the right edge of their row. */
        int keyX = -22;

        this.keyTransform.relative(this).x(1F, keyX).y(PAD + 16).w(16).h(16);
        this.keyZ.relative(this).x(1F, keyX).y(PAD + 16 + ROW * 2).w(16).h(16);
        this.keyOpacity.relative(this).x(1F, keyX).y(PAD + 16 + ROW * 5).w(16).h(16);

        this.hint.relative(this).x(PAD).y(y + 2).w(1F, -PAD * 2).h(12);
    }

    /** Fills the fields from the model when the selection or the playhead changed. */
    private void syncFields()
    {
        CompLayer layer = this.panel.getSelectedLayer();
        float frame = this.panel.getFrame();

        String id = layer == null ? null : layer.id;

        if (id == null ? this.shownLayer == null && Float.compare(this.shownFrame, frame) == 0
            : id.equals(this.shownLayer) && Float.compare(this.shownFrame, frame) == 0)
        {
            return;
        }

        this.shownLayer = id;
        this.shownFrame = frame;

        if (layer == null)
        {
            this.title.label = IKey.constant("图层：未选择");

            return;
        }

        Transform transform = layer.transformAt(frame);

        this.title.label = IKey.constant(this.panel.layerLabel(layer));
        this.x.setValue(round(transform.translate.x));
        this.y.setValue(round(transform.translate.y));
        this.z.setValue(round(layer.scalarAt("z", layer.z, frame)));
        this.rotation.setValue(round(transform.rotate.z));
        this.scale.setValue(round(transform.scale.x * 100F));
        this.opacity.setValue(round(layer.opacityFrame(frame) * 100F));
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

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF11151C);
        context.batcher.box(this.area.x, this.area.y, this.area.x + 1, this.area.ey(), 0xFF2A303A);
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + 1, 0xFF2A303A);

        /* Row labels are painted here (cheap, and they never need their own element). */
        String[] labels = {"X", "Y", "Z", "旋转", "缩放", "不透明度"};
        int y = this.area.y + PAD + 16;

        for (int i = 0; i < labels.length; i++)
        {
            context.batcher.textShadow(labels[i], this.area.x + PAD, y + (16 - context.batcher.getFont().getHeight()) / 2F + 1, 0xFF8A94A6);
            y += ROW;
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
