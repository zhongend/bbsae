package dev.bbsae.client.ui;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;

/**
 * A short non-linear transition for page switches inside the compositor (tab pages, stacked
 * panels, mode switches).
 *
 * <p>Why a slide plus a dark veil instead of a real cross-fade: BBS's {@code Batcher2D} has no way
 * to multiply the alpha of a whole subtree, and patching every native control to fade would invade
 * code the compositor does not own. A quick vertical slide (the new content starts a few pixels
 * below its final place) plus a veil that fades out over the first frames hides the hard cut at the
 * cost of one quad. It never touches the layout: hit testing keeps using the final rectangle.</p>
 *
 * <p>Call {@link #update()} exactly once per frame; it drives the transition clock. When the user
 * turned motion off, callers simply never {@link #begin(String)} and draw nothing.</p>
 */
public final class UiPageTransition
{
    private final UiMotion motion = new UiMotion();
    private final float duration;
    private final float slidePx;

    private String page = "";
    private float elapsed;

    public UiPageTransition(float durationSeconds, float slidePx)
    {
        this.duration = Math.max(0.01F, durationSeconds);
        this.slidePx = slidePx;
    }

    /** Advances the clock. Call once per frame, before reading {@link #progress()}. */
    public void update()
    {
        this.update(System.nanoTime());
    }

    /** Testable clock: pass a synthetic nanosecond time to make transitions deterministic. */
    public void update(long nowNanos)
    {
        this.motion.tickFrame(nowNanos);

        if (this.elapsed < this.duration)
        {
            this.elapsed = Math.min(this.duration, this.elapsed + this.motion.dt);
        }
    }

    /** Restarts the transition for a new page id (or the same page again). */
    public void begin(String pageId)
    {
        this.page = pageId == null ? "" : pageId;
        this.elapsed = 0F;
    }

    public String page()
    {
        return this.page;
    }

    public boolean isActive()
    {
        return this.elapsed < this.duration;
    }

    /** Eased 0→1 progress of the current transition. */
    public float progress()
    {
        return UiMotion.easeOut(this.elapsed / this.duration);
    }

    /** Vertical offset of the incoming content, in pixels. */
    public float slideY()
    {
        return (1F - this.progress()) * this.slidePx;
    }

    /** Alpha of the veil that hides the hard cut; 0 once the transition is done. */
    public float veilAlpha()
    {
        return (1F - this.progress()) * 0.28F;
    }

    /** Draws the veil over the given rectangle. Must run after the content (and its children). */
    public void renderVeil(UIContext context, Area area)
    {
        float alpha = this.veilAlpha();

        if (alpha <= 0.001F || area == null || context == null)
        {
            return;
        }

        context.batcher.box(area.x, area.y, area.ex(), area.ey(),
            UiMotion.alpha(0xFF06080C, alpha));
    }
}
