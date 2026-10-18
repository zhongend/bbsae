package dev.bbsae.client.ui;

import dev.bbsae.client.render.GlTexture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.colors.Colors;

import java.awt.image.BufferedImage;
import java.util.function.IntConsumer;

/**
 * A lift/gamma/gain style colour wheel.
 *
 * <p>AE and Premiere's colour wheels are not sliders: the centre is neutral, the angle picks hue
 * and the distance picks saturation. The value (brightness) lives on the strip underneath, so one
 * wheel can express both a colour cast and how strongly it is applied. The neutral colour is
 * {@code 0xFF808080}, which is exactly what the grade shader expects.</p>
 *
 * <p>The disk is baked once into a texture (AWT), not drawn per frame out of hundreds of quads;
 * this keeps the weak-GPU target responsive. Tint darkens the disk to the current value so the
 * widget shows the colour that will actually be used.</p>
 */
public class UIColorWheel extends UIElement
{
    private static GlTexture disk;
    private static int diskSize;

    private final String label;
    private final IntConsumer callback;
    private Runnable commit;

    private int color = 0xFF808080;
    private boolean draggingDisk;
    private boolean draggingValue;

    public UIColorWheel(String label, int color, IntConsumer callback)
    {
        this.label = label;
        this.callback = callback;
        this.color = color;
        this.tooltip(IKey.constant("拖动色轮改变色相/饱和度；下方滑条调亮度；双击复位为中性灰"));
    }

    public UIColorWheel commit(Runnable commit)
    {
        this.commit = commit;

        return this;
    }

    public void setColor(int color)
    {
        this.color = color;
    }

    public int getColor()
    {
        return this.color;
    }

    @Override
    public void render(UIContext context)
    {
        int x = this.area.x;
        int y = this.area.y;
        int w = this.area.w;
        int diskSide = Math.min(w - 4, this.area.h - 30);

        if (diskSide < 24)
        {
            return;
        }

        this.ensureDisk(128);
        context.batcher.textShadow(this.label, x + 1, y, Colors.setA(Colors.WHITE, 0.75F));

        int cx = x + (w - diskSide) / 2;
        int cy = y + 13;
        int value = Math.max(1, Math.max((this.color >> 16) & 0xFF, Math.max((this.color >> 8) & 0xFF, this.color & 0xFF)));
        int tint = 0xFF000000 | (value << 16) | (value << 8) | value;

        if (disk != null)
        {
            context.batcher.texturedBox(disk.id, tint, cx, cy, diskSide, diskSide, 0F, 0F, disk.width, disk.height, disk.width, disk.height);
        }
        else
        {
            context.batcher.box(cx, cy, cx + diskSide, cy + diskSide, 0xFF303844);
        }

        /* Hue ring + saturation handle. */
        float[] hsv = toHsv(this.color);
        double angle = Math.toRadians(hsv[0] * 360D);
        float radius = diskSide * 0.5F - 3F;
        float hx = cx + diskSide * 0.5F + (float) Math.cos(angle) * radius * hsv[1];
        float hy = cy + diskSide * 0.5F - (float) Math.sin(angle) * radius * hsv[1];

        context.batcher.box(hx - 4, hy - 4, hx + 4, hy + 4, 0xFF101418);
        context.batcher.box(hx - 3, hy - 3, hx + 3, hy + 3, 0xFFFFFFFF);

        /* Value strip. */
        int barY = cy + diskSide + 4;
        int barH = 8;

        for (int i = 0; i < 16; i++)
        {
            int shade = Math.round(i / 15F * 255F);

            context.batcher.box(cx + (diskSide * i) / 16F, barY, cx + (diskSide * (i + 1)) / 16F + 1, barY + barH,
                0xFF000000 | (shade << 16) | (shade << 8) | shade);
        }

        float vx = cx + hsv[2] * diskSide;
        context.batcher.box(vx - 1, barY - 2, vx + 1, barY + barH + 2, 0xFFFFFFFF);
        context.batcher.outline(cx, barY, cx + diskSide, barY + barH, 0xFF11151B);

        this.drag(context, cx, cy, diskSide, barY, barH);

        super.render(context);
    }

    private void drag(UIContext context, int cx, int cy, int diskSide, int barY, int barH)
    {
        if (!this.draggingDisk && !this.draggingValue)
        {
            return;
        }

        if (!Window.isMouseButtonPressed(0))
        {
            this.finish();
            return;
        }

        float[] hsv = toHsv(this.color);

        if (this.draggingValue)
        {
            float v = (context.mouseX - cx) / (float) Math.max(1, diskSide);

            hsv[2] = Math.max(0F, Math.min(1F, v));
        }
        else
        {
            float dx = context.mouseX - (cx + diskSide * 0.5F);
            float dy = context.mouseY - (cy + diskSide * 0.5F);
            float max = Math.max(1F, diskSide * 0.5F - 3F);
            float len = (float) Math.sqrt(dx * dx + dy * dy);

            hsv[0] = (float) ((Math.atan2(-dy, dx) / (Math.PI * 2D) + 1D) % 1D);
            hsv[1] = Math.max(0F, Math.min(1F, len / max));
        }

        this.color = toArgb(hsv[0], hsv[1], hsv[2]);
        this.callback.accept(this.color);
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton != 0 || !this.area.isInside(context))
        {
            return super.subMouseClicked(context);
        }

        int diskSide = Math.min(this.area.w - 4, this.area.h - 30);
        int cx = this.area.x + (this.area.w - diskSide) / 2;
        int cy = this.area.y + 13;
        int barY = cy + diskSide + 4;

        if (context.mouseX >= cx && context.mouseX <= cx + diskSide && context.mouseY >= cy && context.mouseY <= cy + diskSide)
        {
            long now = System.currentTimeMillis();

            if (now - this.lastReset < 320L)
            {
                this.color = 0xFF808080;
                this.callback.accept(this.color);
                this.lastReset = 0L;

                return true;
            }

            this.lastReset = now;
            this.draggingDisk = true;
            this.dragStep(context, cx, cy, diskSide);

            return true;
        }

        if (context.mouseX >= cx && context.mouseX <= cx + diskSide && context.mouseY >= barY - 2 && context.mouseY <= barY + 10)
        {
            this.draggingValue = true;
            this.dragStep(context, cx, cy, diskSide);

            return true;
        }

        /* Double click anywhere on the widget restores neutral without hunting for a menu. */
        if (context.mouseY >= this.area.y && context.mouseY <= this.area.ey())
        {
            long now = System.currentTimeMillis();

            if (now - this.lastReset < 320L)
            {
                this.color = 0xFF808080;
                this.callback.accept(this.color);
                this.lastReset = 0L;

                return true;
            }

            this.lastReset = now;
        }

        return super.subMouseClicked(context);
    }

    private long lastReset;

    private void finish()
    {
        boolean wasDragging = this.draggingDisk || this.draggingValue;

        this.draggingDisk = false;
        this.draggingValue = false;

        if (wasDragging && this.commit != null)
        {
            this.commit.run();
        }
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        this.finish();

        return super.subMouseReleased(context);
    }

    private void dragStep(UIContext context, int cx, int cy, int diskSide)
    {
        float[] hsv = toHsv(this.color);

        if (this.draggingValue)
        {
            float v = (context.mouseX - cx) / (float) Math.max(1, diskSide);

            hsv[2] = Math.max(0F, Math.min(1F, v));
        }
        else
        {
            float dx = context.mouseX - (cx + diskSide * 0.5F);
            float dy = context.mouseY - (cy + diskSide * 0.5F);
            float max = Math.max(1F, diskSide * 0.5F - 3F);
            float len = (float) Math.sqrt(dx * dx + dy * dy);

            hsv[0] = (float) ((Math.atan2(-dy, dx) / (Math.PI * 2D) + 1D) % 1D);
            hsv[1] = Math.max(0F, Math.min(1F, len / max));
        }

        this.color = toArgb(hsv[0], hsv[1], hsv[2]);
        this.callback.accept(this.color);
    }

    private void ensureDisk(int size)
    {
        if (disk != null && diskSize == size)
        {
            return;
        }

        if (disk != null)
        {
            disk.delete();
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[size * size];
        float half = size * 0.5F - 1F;

        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                float dx = x - size * 0.5F;
                float dy = y - size * 0.5F;
                float len = (float) Math.sqrt(dx * dx + dy * dy);

                if (len > half)
                {
                    pixels[x + y * size] = 0;
                    continue;
                }

                float hue = (float) ((Math.atan2(-dy, dx) / (Math.PI * 2D) + 1D) % 1D);
                float sat = Math.min(1F, len / half);
                int rgb = java.awt.Color.HSBtoRGB(hue, sat, 1F);

                pixels[x + y * size] = 0xFF000000 | (rgb & 0x00FFFFFF);
            }
        }

        image.setRGB(0, 0, size, size, pixels, 0, size);

        GlTexture texture = new GlTexture();

        texture.allocate(size, size);
        texture.uploadRaw(pixels);

        disk = texture;
        diskSize = size;
    }

    private static float[] toHsv(int argb)
    {
        float r = ((argb >> 16) & 0xFF) / 255F;
        float g = ((argb >> 8) & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue = 0F;

        if (delta > 0.0001F)
        {
            if (max == r) hue = ((g - b) / delta) % 6F;
            else if (max == g) hue = (b - r) / delta + 2F;
            else hue = (r - g) / delta + 4F;

            hue /= 6F;

            if (hue < 0F) hue += 1F;
        }

        return new float[] {hue, max <= 0.0001F ? 0F : delta / max, max};
    }

    private static int toArgb(float hue, float sat, float value)
    {
        int rgb = java.awt.Color.HSBtoRGB(hue, Math.max(0F, Math.min(1F, sat)), Math.max(0F, Math.min(1F, value)));

        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }
}
