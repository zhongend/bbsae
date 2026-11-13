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
        /* Start settled: a freshly constructed panel must not paint the veil on its first frame
         * (isActive() would be true until the first update() advanced the clock). */
        this.elapsed = this.duration;
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
        /* Quintic ease-out: a fast start and a long soft landing. Cubic ease-out reads as a
         * mechanical slide on a 300 px surface; this is the curve AE/iOS use for page moves. */
        return UiMotion.easeOutQuint(this.elapsed / this.duration);
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

    /**
     * A short close animation for modal overlays whose owner removes them from the tree.
     *
     * <p>BBS has no "about to be removed" render pass: an overlay is visible until its owner calls
     * {@code removeFromParent()}, at which point it vanishes in one frame. This helper owns the
     * wait instead. The overlay calls {@link #begin()} when the user asks to close it, keeps being
     * rendered, calls {@link #tick(long)} once per frame, and the single call that returns
     * {@code true} is the moment the owner may run its removal callback. Because it is driven by
     * that one boolean, the callback can only fire once even when Esc and a button land together —
     * a host no longer needs its own "already removed" flag.</p>
     */
    public static final class CloseFade
    {
        /** 120–160 ms reads as a transition and is short enough not to feel like lag. */
        public static final float DURATION = 0.14F;
        private static final String KEY = "close-fade";

        private final UiMotion motion;
        private final float duration;
        private boolean closing;
        private boolean finished;
        private float progress;

        public CloseFade()
        {
            this(new UiMotion(), DURATION);
        }

        /** Injected clock/motion so the transition can be pinned headlessly. */
        public CloseFade(UiMotion motion, float durationSeconds)
        {
            this.motion = motion == null ? new UiMotion() : motion;
            this.duration = Math.max(0.01F, durationSeconds);
        }

        public void begin()
        {
            this.begin(System.nanoTime());
        }

        /** Starts the fade. Repeated calls (Esc + click) are ignored so the owner is asked once. */
        public void begin(long nowNanos)
        {
            if (this.closing)
            {
                return;
            }

            this.closing = true;
            this.finished = false;
            this.progress = 0F;
            this.motion.tickFrame(nowNanos);
            /* Prime the channel at 0: an absent channel would otherwise make the tween start at
             * its target and finish instantly. */
            this.motion.anim(KEY, 0F, 1F);
        }

        public boolean isClosing()
        {
            return this.closing;
        }

        /** 0 before the fade starts, 1 when the content is fully faded. */
        public float progress()
        {
            return this.progress;
        }

        /** Remaining visibility factor (1 → 0) for surfaces that are blended by hand. */
        public float remaining()
        {
            return 1F - this.progress;
        }

        /**
         * Advances the fade. Returns {@code true} exactly once — on the frame the fade finished —
         * so the host can run its removal callback without a second latch.
         */
        public boolean tick(long nowNanos)
        {
            if (!this.closing || this.finished)
            {
                return false;
            }

            this.motion.tickFrame(nowNanos);
            this.progress = this.motion.tween(KEY, 1F, this.duration, UiMotion.Easing.EASE_OUT);

            if (this.progress >= 1F)
            {
                this.progress = 1F;
                this.finished = true;

                return true;
            }

            return false;
        }
    }
}
