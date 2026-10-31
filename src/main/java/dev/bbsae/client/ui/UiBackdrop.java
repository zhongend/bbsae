package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * The BBS landing-screen style backdrop: a deep gradient, three drifting lights, a soft light that
 * follows the pointer and rising sparks that are pushed away by the cursor.
 *
 * <p>This mirrors BBS's own {@code LandingBackdrop} (the same visual language the welcome/tour
 * screen uses), but every amplitude is read from {@link CompPrefs} so the settings page can turn
 * the effect down or off. It is drawn with BBS batching primitives only and restores the scissor
 * state before returning.</p>
 */
public final class UiBackdrop
{
    private static final float[] LIGHTS =
    {
        0.22F, 0.78F, 0.62F, 0.12F, 0.08F, 29F, 0F, 0.10F,
        0.80F, 0.55F, 0.50F, 0.10F, 0.14F, 37F, 55F, 0.06F,
        0.55F, 1.05F, 0.42F, 0.18F, 0.06F, 23F, -45F, 0.03F
    };

    private static final int SPARKS = 48;

    private final float[] sparkLane = new float[SPARKS];
    private final float[] sparkSpeed = new float[SPARKS];
    private final float[] sparkPhase = new float[SPARKS];
    private final float[] sparkOffset = new float[SPARKS];
    private final float[] sparkSway = new float[SPARKS];
    private final int[] sparkSize = new int[SPARKS];
    private final float[] sparkTint = new float[SPARKS];

    private long lastNanos;
    private float time;
    private float cursorX;
    private float cursorY;
    private float presence;
    private boolean cursorInit;

    public UiBackdrop()
    {
        for (int i = 0; i < SPARKS; i++)
        {
            this.sparkLane[i] = hash(i, 1);
            this.sparkSpeed[i] = 5F + 8F * hash(i, 2);
            this.sparkPhase[i] = hash(i, 3);
            this.sparkOffset[i] = hash(i, 4);
            this.sparkSway[i] = hash(i, 5);
            this.sparkSize[i] = hash(i, 6) < 0.3F ? 2 : 1;
            this.sparkTint[i] = hash(i, 7);
        }
    }

    private void tick()
    {
        long now = System.nanoTime();

        this.time = this.lastNanos == 0L ? 0F : this.time + Math.min(0.1F, (now - this.lastNanos) / 1.0E9F);
        this.lastNanos = now;
    }

    public void render(UIContext context, Area area)
    {
        this.tick();
        this.followCursor(context, area);

        int base = BBSSettings.baseSurface();
        int accent = BBSSettings.primaryColor() & 0xFFFFFF;

        context.batcher.box(area.x, area.y, area.ex(), area.ey(), 0xFF000000 | base);
        context.batcher.gradientVBox(area.x, area.y, area.ex(), area.ey(),
            0xFF000000 | Colors.mulRGB(base, 1.35F), 0xFF000000 | Colors.mulRGB(base, 0.45F));

        context.batcher.clip(area, context);

        try
        {
            this.renderLights(context, area, accent);
            this.renderCursorLight(context, area, accent);
            this.renderSparks(context, area, accent);
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void followCursor(UIContext context, Area area)
    {
        float delta = Math.max(0F, Math.min(0.1F, this.time <= 0F ? 0.016F : this.time - this.lastTime));
        float ease = 1F - (float) Math.exp(-delta * 5F);
        boolean inside = area.isInside(context.mouseX, context.mouseY);

        this.lastTime = this.time;

        if (!this.cursorInit)
        {
            this.cursorInit = true;
            this.cursorX = area.mx();
            this.cursorY = area.my();
        }

        if (!CompPrefs.animating())
        {
            this.cursorX = context.mouseX;
            this.cursorY = context.mouseY;
            this.presence = inside ? 1F : 0F;

            return;
        }

        if (inside)
        {
            this.cursorX += (context.mouseX - this.cursorX) * ease;
            this.cursorY += (context.mouseY - this.cursorY) * ease;
        }

        this.presence += ((inside ? 1F : 0F) - this.presence) * ease;
    }

    private float lastTime;

    private void renderLights(UIContext context, Area area, int accent)
    {
        float strength = Math.max(0F, Math.min(1.5F, CompPrefs.get().backdropIntensity)) * CompPrefs.intensity();

        if (strength <= 0.01F)
        {
            return;
        }

        float lookX = (this.cursorX - area.mx()) / (float) area.w * this.presence;
        float lookY = (this.cursorY - area.my()) / (float) area.h * this.presence;

        for (int i = 0; i < LIGHTS.length; i += 8)
        {
            float period = LIGHTS[i + 5];
            double phase = (this.time / period) * Math.PI * 2D;
            float x = area.x + (LIGHTS[i] + (float) Math.sin(phase) * LIGHTS[i + 3] - lookX * LIGHTS[i + 7]) * area.w;
            float y = area.y + (LIGHTS[i + 1] + (float) Math.cos(phase * 0.7D) * LIGHTS[i + 4] - lookY * LIGHTS[i + 7]) * area.h;
            int radius = (int) (LIGHTS[i + 2] * area.h * (0.6F + 0.4F * strength));
            int color = Colors.lerp(accent, 0xFFFFFF, Math.max(0F, Math.min(1F, LIGHTS[i + 6] / 90F)));

            UiGfx.glow(context.batcher, (int) x, (int) y, radius,
                UiMotion.alpha(color, 0.42F * strength), 48);
        }
    }

    private void renderCursorLight(UIContext context, Area area, int accent)
    {
        if (!CompPrefs.get().glow || this.presence <= 0.01F)
        {
            return;
        }

        int radius = (int) (0.30F * area.h);
        float strength = Math.max(0F, Math.min(1.5F, CompPrefs.get().backdropIntensity)) * CompPrefs.intensity();

        UiGfx.glow(context.batcher, (int) this.cursorX, (int) this.cursorY, radius,
            UiMotion.alpha(accent, 0.23F * this.presence * strength), 48);
    }

    private void renderSparks(UIContext context, Area area, int accent)
    {
        if (!CompPrefs.get().glow || CompPrefs.get().backdropIntensity <= 0.01F)
        {
            return;
        }

        int cycle = area.h + 20;
        float strength = Math.max(0F, Math.min(1.5F, CompPrefs.get().backdropIntensity)) * CompPrefs.intensity();

        for (int i = 0; i < SPARKS; i++)
        {
            float sway = (float) Math.sin(this.time * (0.3F + 0.4F * this.sparkSway[i]) + this.sparkPhase[i] * Math.PI * 2D) * 0.02F;
            float travel = (this.time * this.sparkSpeed[i] + this.sparkOffset[i] * cycle) % cycle;
            float x = area.x + (this.sparkLane[i] + sway) * area.w;
            float dx = x - this.cursorX;
            float y = area.ey() + 10F - travel;
            float dy = y - this.cursorY;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);

            if (distance < 90F && this.presence > 0F)
            {
                float push = 1F - distance / 90F;
                float factor = push * push * 28F * this.presence / Math.max(distance, 1F);

                x += dx * factor;
                y += dy * factor;
            }

            float edge = Math.min(1F, Math.min(area.ey() - y, y - area.y) / (area.h * 0.2F));

            if (edge <= 0F)
            {
                continue;
            }

            float twinkle = 0.6F + 0.4F * (float) Math.sin(this.time * (1F + 2F * this.sparkPhase[i]) + this.sparkPhase[i] * Math.PI * 2D);
            int color = Colors.lerp(0xFFFFFF, accent, this.sparkTint[i]);
            int px = (int) x;
            int py = (int) y;

            context.batcher.box(px, py, px + this.sparkSize[i], py + this.sparkSize[i],
                UiMotion.alpha(color, Math.min(0.55F, 0.55F * edge * twinkle * strength)));
        }
    }

    private static float hash(int index, int trait)
    {
        int n = index * 374761393 + trait * 668265263;

        n = (n ^ n >>> 13) * 1274126177;
        n ^= n >>> 16;

        return (n & 0xFFFFFF) / 16777216F;
    }
}
