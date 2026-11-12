package dev.bbsae.client.ui;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * Five-point master tone curve, the same curve the grade shader evaluates.
 *
 * <p>Points are pinned horizontally (black / shadows / mid / highlights / white) and dragged
 * vertically, exactly like the RGB master curve in Premiere's Lumetri panel. Endpoints can move a
 * little but are clamped so the curve can never invert the whole image. The widget draws the
 * curve by evaluating the same Catmull-Rom formula as the shader, so what you see is what renders.
 * </p>
 */
public class UICurveEditor extends UIElement
{
    private static final int POINTS = 5;
    private static final float MIN = -0.5F;
    private static final float MAX = 0.5F;
    /** Hover hit box of a control point; larger than the 5 px dot, smaller than half a cell. */
    private static final int POINT_HIT_X = 6;
    private static final int POINT_HIT_Y = 8;
    /** Stable channel names: building "pt:" + i in render would allocate on every frame. */
    private static final String[] HOVER_KEYS = {"pt0:h", "pt1:h", "pt2:h", "pt3:h", "pt4:h"};

    private final float[] values = new float[POINTS];
    private final java.util.function.BiConsumer<Integer, Float> onChange;
    private final Runnable onCommit;
    private final UiMotion motion = new UiMotion();
    private int motionFrames;

    private int dragging = -1;

    public UICurveEditor(float[] values, java.util.function.BiConsumer<Integer, Float> onChange, Runnable onCommit)
    {
        this.onChange = onChange;
        this.onCommit = onCommit;

        if (values != null)
        {
            System.arraycopy(values, 0, this.values, 0, Math.min(values.length, POINTS));
        }

        this.tooltip(IKey.constant("拖动控制点调整明暗曲线；双击复位"));
    }

    @Override
    public void render(UIContext context)
    {
        int x = this.area.x;
        int y = this.area.y;
        int w = this.area.w;
        int h = this.area.h;

        this.motion.tickFrame();

        if (++this.motionFrames >= UICompButton.PRUNE_EVERY)
        {
            this.motionFrames = 0;
            this.motion.prune(UICompButton.PRUNE_IDLE);
        }

        int hover = this.area.isInside(context)
            ? pointNear(context.mouseX, context.mouseY, x, y, w, h, this.values) : -1;
        float intro = this.motion.intro(0.02F);
        /* A light fade-in only: the grade section is rebuilt when the selection changes, and a
         * full 0→1 entrance would read as a flicker on every track click. */
        float appear = 0.6F + 0.4F * intro;

        context.batcher.box(x, y, x + w, y + h, 0xFF141922);
        context.batcher.outline(x, y, x + w, y + h,
            UiMotion.lerpColor(0xFF2E3947, 0xFF4C86D8, hover >= 0 ? 0.45F : 0F));

        for (int i = 1; i < 4; i++)
        {
            int gx = x + w * i / 4;

            context.batcher.box(gx, y, gx + 1, y + h, 0x18FFFFFF);
        }

        for (int i = 1; i < 4; i++)
        {
            int gy = y + h * i / 4;

            context.batcher.box(x, gy, x + w, gy + 1, 0x18FFFFFF);
        }

        /* Identity reference. */
        for (int i = 0; i < 64; i++)
        {
            float t = i / 63F;
            int px = x + Math.round(t * (w - 1));
            int py = y + Math.round((1F - t) * (h - 1));

            context.batcher.box(px, py, px + 1, py + 1, 0x28FFFFFF);
        }

        this.drag(context);

        int steps = Math.max(32, w);
        int curveColor = UiMotion.alpha(0xFF9FD0FF, appear);

        for (int i = 1; i <= steps; i++)
        {
            float t0 = (i - 1) / (float) steps;
            float t1 = i / (float) steps;
            int x0 = x + Math.round(t0 * (w - 1));
            int y0 = y + Math.round((1F - this.evaluate(t0)) * (h - 1));
            int x1 = x + Math.round(t1 * (w - 1));
            int y1 = y + Math.round((1F - this.evaluate(t1)) * (h - 1));

            this.line(context, x0, y0, x1, y1, curveColor);
        }

        float drag = this.motion.spring("drag", this.dragging >= 0 ? 1F : 0F, 520F, 30F);

        for (int i = 0; i < POINTS; i++)
        {
            int px = x + Math.round(i / (float) (POINTS - 1) * (w - 1));
            int py = y + Math.round((1F - this.value(i)) * (h - 1));
            float hv = this.motion.anim(HOVER_KEYS[i], hover == i ? 1F : 0F, 26F);
            float lit = Math.max(hv, i == this.dragging ? Math.max(0.6F, drag) : 0F);
            int radius = 2 + Math.round(UiMotion.clamp01(lit));
            int color = UiMotion.alpha(UiMotion.lerpColor(0xFF7FC4FF, 0xFFFFFFFF, lit), appear);

            if (hv > 0.01F)
            {
                /* A soft ring marks the hovered point; the dot itself only grows by 1 px. */
                context.batcher.outline(px - radius - 2, py - radius - 2,
                    px + radius + 2, py + radius + 2, UiMotion.alpha(0xFF4C86D8, 0.35F * hv));
            }

            context.batcher.box(px - radius - 1, py - radius - 1, px + radius + 1, py + radius + 1, 0xFF0F1319);
            context.batcher.box(px - radius, py - radius, px + radius, py + radius, color);
        }

        super.render(context);
    }

    /**
     * Which control point the cursor is near, or -1.
     *
     * <p>Pure and package-private: the ends of a 5-point curve sit exactly on the widget border,
     * which is the easiest hit box to get wrong, and it cannot be checked without a GL context
     * otherwise. The vertical tolerance is larger than the horizontal one because the curve is
     * dragged up/down.</p>
     */
    static int pointNear(int mouseX, int mouseY, int x, int y, int w, int h, float[] offsets)
    {
        int best = -1;
        int bestDx = POINT_HIT_X + 1;
        int bestDy = POINT_HIT_Y + 1;
        int last = POINTS - 1;

        for (int i = 0; i <= last; i++)
        {
            int px = x + Math.round(i / (float) last * (w - 1));
            int py = y + Math.round((1F - pointValue(i, last, offsets)) * (h - 1));
            int dx = Math.abs(mouseX - px);
            int dy = Math.abs(mouseY - py);

            if (dx <= POINT_HIT_X && dy <= POINT_HIT_Y && (dx < bestDx || (dx == bestDx && dy < bestDy)))
            {
                best = i;
                bestDx = dx;
                bestDy = dy;
            }
        }

        return best;
    }

    /** Normalised value of one control point for an offset array; mirrors {@link #value(int)}. */
    static float pointValue(int index, int last, float[] offsets)
    {
        int i = Math.max(0, Math.min(last, index));
        float offset = offsets != null && i < offsets.length ? offsets[i] : 0F;

        return UiMotion.clamp01(i / (float) Math.max(1, last) + offset);
    }

    private void line(UIContext context, int x0, int y0, int x1, int y1, int color)
    {
        int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));

        for (int i = 0; i <= steps; i++)
        {
            float t = i / (float) steps;
            int px = Math.round(x0 + (x1 - x0) * t);
            int py = Math.round(y0 + (y1 - y0) * t);

            context.batcher.box(px, py, px + 1, py + 1, color);
        }
    }

    private void drag(UIContext context)
    {
        if (this.dragging < 0)
        {
            return;
        }

        if (!Window.isMouseButtonPressed(0))
        {
            this.dragging = -1;

            if (this.onCommit != null)
            {
                this.onCommit.run();
            }

            return;
        }

        float t = (context.mouseY - this.area.y) / (float) Math.max(1, this.area.h - 1);
        float base = this.dragging / (float) (POINTS - 1);
        float value = base + (0.5F - t);

        this.values[this.dragging] = Math.max(MIN, Math.min(MAX, value));

        if (this.onChange != null)
        {
            this.onChange.accept(this.dragging, this.values[this.dragging]);
        }
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton != 0 || !this.area.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        int index = Math.round((context.mouseX - this.area.x) / (float) Math.max(1, this.area.w - 1) * (POINTS - 1));

        index = Math.max(0, Math.min(POINTS - 1, index));

        long now = System.currentTimeMillis();

        if (index == this.lastPoint && now - this.lastClick < 320L)
        {
            this.values[index] = 0F;

            if (this.onChange != null)
            {
                this.onChange.accept(index, this.values[index]);
            }

            if (this.onCommit != null)
            {
                this.onCommit.run();
            }

            this.lastClick = 0L;

            return true;
        }

        this.lastPoint = index;
        this.lastClick = now;
        this.dragging = index;
        this.drag(context);

        return true;
    }

    private int lastPoint = -1;
    private long lastClick;

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        if (this.dragging >= 0 && this.onCommit != null)
        {
            this.onCommit.run();
        }

        this.dragging = -1;

        return super.subMouseReleased(context);
    }

    public float value(int index)
    {
        return pointValue(index, POINTS - 1, this.values);
    }

    private float point(int index)
    {
        if (index <= 0) return this.value(0);
        if (index >= POINTS - 1) return this.value(POINTS - 1);

        return this.value(index);
    }

    /** Mirrors {@code toneCurve()} in ShaderSources.GRADE. */
    private float evaluate(float x)
    {
        float t = Math.max(0F, Math.min(1F, x)) * (POINTS - 1);
        int i = (int) Math.floor(t);
        float f = t - i;
        float p0 = this.point(i - 1);
        float p1 = this.point(i);
        float p2 = this.point(i + 1);
        float p3 = this.point(i + 2);
        float m0 = (p2 - p0) * 0.5F;
        float m1 = (p3 - p1) * 0.5F;
        float f2 = f * f;
        float f3 = f2 * f;
        float result = (2F * f3 - 3F * f2 + 1F) * p1
            + (f3 - 2F * f2 + f) * m0
            + (-2F * f3 + 3F * f2) * p2
            + (f3 - f2) * m1;

        return Math.max(0F, Math.min(1F, result));
    }

    public float[] offsets()
    {
        return this.values.clone();
    }
}
