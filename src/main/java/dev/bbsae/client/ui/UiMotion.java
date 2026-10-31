package dev.bbsae.client.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-element motion state for the compositor's UI layer.
 *
 * <p>Motion is a <b>presentation</b> concern: no UI animation may touch document data, render
 * targets, the frame server or the export path. Every channel here is either an exponential
 * approach (state changes) or a one-shot envelope (entrance, flash), which keeps the editor
 * responsive without a timeline system.</p>
 *
 * <p>The settings page exposes what this class reads: the global on/off, an intensity multiplier
 * (distance/alpha), a speed multiplier and the glow/shadow switches. If motion is disabled all
 * channels snap to their target, so the UI remains completely usable for users who prefer static
 * interfaces.</p>
 */
public class UiMotion
{
    public static final float ENTRANCE = 0.30F;

    private long lastFrame;
    private long shownAt;
    private boolean shown;
    private final Map<String, Float> channels = new HashMap<>();
    private final Map<String, Long> flashes = new HashMap<>();

    /** Seconds since the previous {@link #tickFrame()} (clamped, 16 ms first frame). */
    public float dt = 0.016F;
    /** Seconds since this owner first rendered. */
    public float time;

    public static float clamp01(float value)
    {
        return value < 0F ? 0F : (value > 1F ? 1F : value);
    }

    public static float lerp(float a, float b, float t)
    {
        return a + (b - a) * clamp01(t);
    }

    public static float easeOut(float x)
    {
        x = clamp01(x);
        float inv = 1F - x;

        return 1F - inv * inv * inv;
    }

    public static float easeInOut(float x)
    {
        x = clamp01(x);

        return x < 0.5F
            ? 4F * x * x * x
            : 1F - (float) Math.pow(-2F * x + 2F, 3D) / 2F;
    }

    public static float easeOutBack(float x)
    {
        x = clamp01(x);

        float c1 = 1.70158F;
        float c3 = c1 + 1F;

        return 1F + c3 * (float) Math.pow(x - 1F, 3D) + c1 * (float) Math.pow(x - 1F, 2D);
    }

    public static float easeOutElastic(float x)
    {
        x = clamp01(x);

        if (x == 0F || x == 1F)
        {
            return x;
        }

        float c4 = (float) (2D * Math.PI) / 3F;

        return (float) Math.pow(2D, -10D * x) * (float) Math.sin((x * 10F - 0.75F) * c4) + 1F;
    }

    public static float easeOutExpo(float x)
    {
        x = clamp01(x);

        return x >= 1F ? 1F : 1F - (float) Math.pow(2D, -10D * x);
    }

    public static float approach(float value, float target, float speed, float dt)
    {
        if (dt <= 0F || speed <= 0F)
        {
            return value;
        }

        float k = 1F - (float) Math.exp(-speed * dt);
        float result = value + (target - value) * k;

        return Math.abs(result - target) < 0.001F ? target : result;
    }

    /** Advances the frame clock exactly once per host render. */
    public void tickFrame()
    {
        long now = System.nanoTime();

        this.dt = this.lastFrame == 0L ? 0.016F : Math.min(0.1F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        this.time += this.dt;
    }

    public void markShown()
    {
        if (!this.shown)
        {
            this.shown = true;
            this.shownAt = System.nanoTime();
        }
    }

    public void replay()
    {
        this.shown = true;
        this.shownAt = System.nanoTime();
    }

    public void reset()
    {
        this.channels.clear();
        this.flashes.clear();
        this.lastFrame = 0L;
        this.time = 0F;
        this.shown = false;
    }

    /** Exponential channel at the global speed. */
    public float anim(String key, float target, float speed)
    {
        if (!CompPrefs.animating() || CompPrefs.intensity() <= 0.001F)
        {
            this.channels.put(key, target);

            return target;
        }

        float value = approach(this.channels.getOrDefault(key, 0F), target,
            Math.max(0.001F, speed * CompPrefs.speed()), this.dt);

        this.channels.put(key, value);

        return value;
    }

    public float hover(String key, String hotKey)
    {
        return this.anim("h:" + key, key.equals(hotKey) ? 1F : 0F, 22F);
    }

    public float select(String key, boolean selected)
    {
        return this.anim("s:" + key, selected ? 1F : 0F, 18F);
    }

    /** Entrance progress, 0→1, with an optional delay and the global speed multiplier. */
    public float intro(float delay)
    {
        if (!this.shown)
        {
            this.markShown();
        }

        if (!CompPrefs.animating())
        {
            return 1F;
        }

        float elapsed = (System.nanoTime() - this.shownAt) / 1.0E9F;
        float duration = ENTRANCE / Math.max(0.25F, CompPrefs.speed());

        return easeOut((elapsed - delay) / duration);
    }

    /** Entrance with overshoot, for emphasis (settings card, onboarding page). */
    public float introBack(float delay)
    {
        if (!CompPrefs.animating())
        {
            return 1F;
        }

        float elapsed = (System.nanoTime() - this.shownAt) / 1.0E9F;
        float duration = ENTRANCE / Math.max(0.25F, CompPrefs.speed());

        return easeOutBack((elapsed - delay) / duration);
    }

    public void flash(String key)
    {
        this.flashes.put(key, System.nanoTime());
    }

    /** One-shot flash progress 0→1; returns 1 when absent/finished. */
    public float flash(String key, float duration)
    {
        Long at = this.flashes.get(key);

        if (at == null)
        {
            return 1F;
        }

        float t = (System.nanoTime() - at) / (Math.max(0.01F, duration) * 1.0E9F);

        if (t >= 1F)
        {
            this.flashes.remove(key);

            return 1F;
        }

        return t;
    }

    /** Breathing value 0..1 with a period in seconds. */
    public float breathe(float period)
    {
        if (!CompPrefs.animating())
        {
            return 0.5F;
        }

        return 0.5F + 0.5F * (float) Math.sin(this.time / Math.max(0.1F, period) * Math.PI * 2D);
    }

    public float intensity()
    {
        return CompPrefs.intensity();
    }

    public static int alpha(int argb, float alpha)
    {
        int a = Math.round(clamp01(alpha) * (argb >>> 24));

        return a << 24 | argb & 0xFFFFFF;
    }

    public static int lerpColor(int a, int b, float t)
    {
        t = clamp01(t);

        int aa = a >>> 24;
        int ar = a >> 16 & 0xFF;
        int ag = a >> 8 & 0xFF;
        int ab = a & 0xFF;
        int ba = b >>> 24;
        int br = b >> 16 & 0xFF;
        int bg = b >> 8 & 0xFF;
        int bb = b & 0xFF;

        return Math.round(aa + (ba - aa) * t) << 24
            | Math.round(ar + (br - ar) * t) << 16
            | Math.round(ag + (bg - ag) * t) << 8
            | Math.round(ab + (bb - ab) * t);
    }
}
