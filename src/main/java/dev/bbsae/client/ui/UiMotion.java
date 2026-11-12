package dev.bbsae.client.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-element motion state for the compositor's UI layer.
 *
 * <p>Motion is a <b>presentation</b> concern: no UI animation may touch document data, render
 * targets, the frame server or the export path. Every channel here is either an exponential
 * approach (state changes), a damped spring (layout/feedback), a one-shot tween (page transitions)
 * or a one-shot envelope (entrance, flash), which keeps the editor responsive without a timeline
 * system.</p>
 *
 * <p>The settings page exposes what this class reads: the global on/off, an intensity multiplier,
 * a speed multiplier and the glow/shadow switches. If motion is disabled all channels snap to their
 * target, so the UI remains completely usable for users who prefer static interfaces.</p>
 */
public class UiMotion
{
    public static final float ENTRANCE = 0.30F;
    private static final float SNAP_EPSILON = 0.001F;
    /** Hard cap for a single frame's delta: an alt-tab pause must not teleport an animation. */
    private static final float MAX_DT = 0.05F;

    /** Easing curves for one-shot tweens. Each one maps 0..1 to 0..1 (OUT_BACK overshoots). */
    public enum Easing
    {
        LINEAR
        {
            @Override
            public float apply(float x)
            {
                return clamp01(x);
            }
        },
        EASE_OUT
        {
            @Override
            public float apply(float x)
            {
                return easeOut(x);
            }
        },
        EASE_IN_OUT
        {
            @Override
            public float apply(float x)
            {
                return easeInOut(x);
            }
        },
        OUT_BACK
        {
            @Override
            public float apply(float x)
            {
                return easeOutBack(x);
            }
        },
        OUT_ELASTIC
        {
            @Override
            public float apply(float x)
            {
                return easeOutElastic(x);
            }
        },
        OUT_EXPO
        {
            @Override
            public float apply(float x)
            {
                return easeOutExpo(x);
            }
        };

        public abstract float apply(float x);
    }

    private static final class Tween
    {
        float from;
        float to;
        float elapsed;
        float duration;
        Easing easing;
    }

    private long lastFrame;
    private long nowNanos = System.nanoTime();
    private long shownAt;
    private boolean shown;
    private final Map<String, Float> channels = new HashMap<>();
    private final Map<String, Float> targets = new HashMap<>();
    private final Map<String, Float> velocities = new HashMap<>();
    private final Map<String, Tween> tweens = new HashMap<>();
    private final Map<String, Long> lastUsed = new HashMap<>();
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

        return Math.abs(result - target) < SNAP_EPSILON ? target : result;
    }

    /**
     * One step of a damped spring. Exposed as a pure function so the curve itself can be tested;
     * the stateful version is {@link #spring(String, float, float, float)}.
     *
     * <p>Critical damping is {@code damping = 2 * sqrt(stiffness)}. The step is clamped/snapped so
     * an animation always comes to rest instead of oscillating forever at 1e-6 amplitude.</p>
     */
    public static float springStep(float value, float target, float stiffness, float damping, float dt)
    {
        float step = Math.max(0F, Math.min(MAX_DT, dt));
        float s = Math.max(0.01F, stiffness);
        float d = Math.max(0F, damping);
        float acceleration = (target - value) * s;
        float velocity = acceleration * step;
        float next = value + velocity * step;

        velocity *= Math.max(0F, 1F - d * step);

        if (Math.abs(target - next) < SNAP_EPSILON)
        {
            return target;
        }

        return next;
    }

    /** Advances the frame clock exactly once per host render. */
    public void tickFrame()
    {
        this.tickFrame(System.nanoTime());
    }

    /** Testable clock: pass a synthetic nanosecond time to make animation steps deterministic. */
    public void tickFrame(long nowNanos)
    {
        this.nowNanos = nowNanos;

        long previous = this.lastFrame == 0L ? nowNanos - 16_000_000L : this.lastFrame;

        this.dt = Math.max(0F, Math.min(MAX_DT, (nowNanos - previous) / 1.0E9F));
        this.lastFrame = nowNanos;
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
        this.targets.clear();
        this.velocities.clear();
        this.tweens.clear();
        this.lastUsed.clear();
        this.flashes.clear();
        this.lastFrame = 0L;
        this.time = 0F;
        this.shown = false;
    }

    /** Exponential channel at the global speed. */
    public float anim(String key, float target, float speed)
    {
        this.lastUsed.put(key, this.nowNanos);
        this.targets.put(key, target);

        if (!this.animationsEnabled())
        {
            this.channels.put(key, target);
            this.velocities.remove(key);

            return target;
        }

        float value = approach(this.channels.getOrDefault(key, 0F), target,
            Math.max(0.001F, speed * this.speedFactor()), this.dt);

        this.channels.put(key, value);

        return value;
    }

    /**
     * Damped spring channel, for feedback that should physically settle (buttons, panels).
     *
     * <p>Unlike {@link #anim}, the velocity is remembered, so the first step does not fight the
     * previous one and the overshoot is genuine (a critically damped spring does not overshoot;
     * a softer one does).</p>
     */
    public float spring(String key, float target, float stiffness, float damping)
    {
        this.lastUsed.put(key, this.nowNanos);
        this.targets.put(key, target);

        if (!this.animationsEnabled())
        {
            this.channels.put(key, target);
            this.velocities.remove(key);

            return target;
        }

        float value = this.channels.getOrDefault(key, target);
        float velocity = this.velocities.getOrDefault(key, 0F);
        float step = Math.max(0F, Math.min(MAX_DT, this.dt));
        float s = Math.max(0.01F, stiffness);
        float d = Math.max(0F, damping);

        velocity += (target - value) * s * step;
        velocity *= Math.max(0F, 1F - d * step);
        value += velocity * step;

        if (Math.abs(target - value) < SNAP_EPSILON && Math.abs(velocity) < SNAP_EPSILON)
        {
            value = target;
            velocity = 0F;
        }

        this.channels.put(key, value);
        this.velocities.put(key, velocity);

        return value;
    }

    /**
     * One-shot tween towards a target. The first call returns the current value and starts the
     * clock; subsequent calls (one per frame) advance it by {@link #dt}.
     *
     * <p>Ends exactly on the target and removes its state, so it cannot leak channels the way the
     * old flash map could.</p>
     */
    public float tween(String key, float target, float durationSeconds, Easing easing)
    {
        this.lastUsed.put(key, this.nowNanos);

        if (!this.animationsEnabled())
        {
            this.channels.put(key, target);
            this.tweens.remove(key);

            return target;
        }

        Tween tween = this.tweens.get(key);

        if (tween == null || Math.abs(tween.to - target) > 0.0001F)
        {
            tween = new Tween();
            tween.from = this.channels.getOrDefault(key, target);
            tween.to = target;
            tween.elapsed = 0F;
            tween.duration = Math.max(0.001F, durationSeconds / Math.max(0.25F, this.speedFactor()));
            tween.easing = easing == null ? Easing.EASE_OUT : easing;

            this.tweens.put(key, tween);

            return tween.from;
        }

        tween.elapsed += Math.max(0F, Math.min(MAX_DT, this.dt));

        float t = clamp01(tween.elapsed / tween.duration);
        float value = tween.from + (tween.to - tween.from) * tween.easing.apply(t);

        if (t >= 1F)
        {
            value = tween.to;
            this.tweens.remove(key);
        }

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

        if (!this.animationsEnabled())
        {
            return 1F;
        }

        float elapsed = (System.nanoTime() - this.shownAt) / 1.0E9F;
        float duration = ENTRANCE / Math.max(0.25F, this.speedFactor());

        return easeOut((elapsed - delay) / duration);
    }

    /** Entrance with overshoot, for emphasis (settings card, onboarding page). */
    public float introBack(float delay)
    {
        if (!this.animationsEnabled())
        {
            return 1F;
        }

        float elapsed = (System.nanoTime() - this.shownAt) / 1.0E9F;
        float duration = ENTRANCE / Math.max(0.25F, this.speedFactor());

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
        if (!this.animationsEnabled())
        {
            return 0.5F;
        }

        return 0.5F + 0.5F * (float) Math.sin(this.time / Math.max(0.1F, period) * Math.PI * 2D);
    }

    public float intensity()
    {
        return this.intensityValue();
    }

    /* ----------------------------------------------------- preference indirection */

    /**
     * Whether motion runs at all. Overridable so motion tests can pin behaviour without touching
     * the game's preference file (which needs a Fabric runtime).
     */
    protected boolean animationsEnabled()
    {
        return CompPrefs.animating() && CompPrefs.intensity() > 0.001F;
    }

    protected float speedFactor()
    {
        return CompPrefs.speed();
    }

    protected float intensityValue()
    {
        return CompPrefs.intensity();
    }

    /** True while at least one channel is still moving / a tween is pending. */
    public boolean isActive()
    {
        if (!this.tweens.isEmpty())
        {
            return true;
        }

        for (Map.Entry<String, Float> entry : this.channels.entrySet())
        {
            Float target = this.targets.get(entry.getKey());

            if (target != null && Math.abs(target - entry.getValue()) > SNAP_EPSILON)
            {
                return true;
            }
        }

        return false;
    }

    /** Number of live channels; diagnostics/tests only. */
    public int channelCount()
    {
        return this.channels.size();
    }

    /**
     * Drops channels that have not been touched for {@code idleSeconds}. List rows come and go
     * (hundreds of effect rows, tracks, assets), so without pruning the channel maps only grow for
     * a long editing session.
     */
    public void prune(float idleSeconds)
    {
        long cutoff = this.nowNanos - (long) (Math.max(0F, idleSeconds) * 1.0E9D);
        List<String> dead = new ArrayList<>();

        for (Map.Entry<String, Long> entry : this.lastUsed.entrySet())
        {
            if (entry.getValue() < cutoff && !this.tweens.containsKey(entry.getKey()))
            {
                dead.add(entry.getKey());
            }
        }

        for (String key : dead)
        {
            this.channels.remove(key);
            this.targets.remove(key);
            this.velocities.remove(key);
            this.lastUsed.remove(key);
        }
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
