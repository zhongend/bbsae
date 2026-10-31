package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;

/**
 * Small drawing helpers built on BBS's {@link Batcher2D}.
 *
 * <p>Everything is expressed with rectangles/gradients and BBS's own shadow primitive. No custom
 * shader, framebuffer or GL state is introduced: motion must stay a paint-time concern, and the
 * export path must remain untouched. The helpers are deliberately few — a gradient fill, a
 * hairline, a soft layered shadow and an accent rail.</p>
 */
public final class UiGfx
{
    private UiGfx()
    {}

    public static int alpha(int rgb, float alpha)
    {
        int a = Math.round(UiMotion.clamp01(alpha) * 255F);

        return a << 24 | rgb & 0xFFFFFF;
    }

    public static int mulA(int argb, float factor)
    {
        int a = Math.round((argb >>> 24) * UiMotion.clamp01(factor));

        return a << 24 | argb & 0xFFFFFF;
    }

    public static int lerp(int a, int b, float t)
    {
        return UiMotion.lerpColor(a, b, t);
    }

    /** Horizontal/vertical two-colour gradient (BBS batches this as one quad). */
    public static void gradientV(Batcher2D batcher, float x, float y, float w, float h, int top, int bottom)
    {
        if (w <= 0F || h <= 0F || ((top | bottom) >>> 24) == 0)
        {
            return;
        }

        batcher.box(x, y, w, h, top, top, bottom, bottom);
    }

    public static void gradientH(Batcher2D batcher, float x, float y, float w, float h, int left, int right)
    {
        if (w <= 0F || h <= 0F || ((left | right) >>> 24) == 0)
        {
            return;
        }

        batcher.box(x, y, w, h, left, right, left, right);
    }

    /** Four-corner gradient for a selected row / hovered card. */
    public static void gradient(Batcher2D batcher, float x, float y, float w, float h, int tl, int tr, int bl, int br)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        batcher.box(x, y, w, h, tl, tr, bl, br);
    }

    public static void outline(Batcher2D batcher, float x, float y, float w, float h, int color)
    {
        if (w <= 0F || h <= 0F || (color >>> 24) == 0)
        {
            return;
        }

        batcher.outline(x, y, x + w, y + h, color);
    }

    /**
     * Layered soft shadow. Three translucent bands are enough at UI density; this avoids a blur
     * pass and keeps the per-frame cost proportional to the card perimeter.
     */
    public static void shadow(Batcher2D batcher, float x, float y, float w, float h, float spread, int color)
    {
        if (!CompPrefs.get().shadows || w <= 0F || h <= 0F || (color >>> 24) == 0)
        {
            return;
        }

        float s = Math.max(1F, spread);

        for (int i = 1; i <= 3; i++)
        {
            float t = s * i / 3F;
            int a = mulA(color, (4 - i) * 0.25F);

            batcher.box(x - t, y - t + 1, x + w + t, y + h + t + 1, a);
        }
    }

    /** A circular-ish glow for the onboarding backdrop / panel accents. */
    public static void glow(Batcher2D batcher, int x, int y, int radius, int color, int segments)
    {
        if (!CompPrefs.get().glow || radius <= 0 || (color >>> 24) == 0)
        {
            return;
        }

        batcher.dropCircleShadow(x, y, radius, 0, Math.max(8, segments), color, color & 0x00FFFFFF);
    }

    /** 2 px accent rail whose alpha/width follows a 0..1 selection channel. */
    public static void accentRail(Batcher2D batcher, float x, float y, float h, float progress, int color)
    {
        float p = UiMotion.clamp01(progress);

        if (p <= 0.01F)
        {
            return;
        }

        batcher.box(x, y, x + 2F, y + h, mulA(color, p));
    }
}
