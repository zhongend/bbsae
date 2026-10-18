package dev.bbsae.client.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.Locale;
import java.util.function.DoubleToIntFunction;

/**
 * The ruler above the composition's track lanes.
 *
 * <p>BBS's {@code TimelineRulerRenderer} is built around the film editor's fixed 20 ticks per
 * second. A composition has its own frame rate, so the track timeline needs a ruler that speaks
 * <b>seconds</b> and chooses its steps from the actual pixels-per-second. Same visual language as
 * BBS (sunken strip, divider-coloured notches, small labels), correct unit.</p>
 */
public final class CompTimelineRuler
{
    /** Human-friendly steps, in seconds. */
    private static final double[] STEPS =
    {
        1D / 60D, 1D / 30D, 1D / 20D, 1D / 10D, 0.2D, 0.25D, 0.5D,
        1D, 2D, 5D, 10D, 15D, 30D, 60D, 120D, 300D, 600D, 1200D, 3600D
    };

    private CompTimelineRuler()
    {}

    /** Smallest "nice" step whose label still has room to breathe. */
    public static double majorStep(double pixelsPerSecond)
    {
        for (double step : STEPS)
        {
            if (step * pixelsPerSecond >= 62D)
            {
                return step;
            }
        }

        return STEPS[STEPS.length - 1];
    }

    public static void render(UIContext context, Area area, double min, double max, DoubleToIntFunction toX)
    {
        if (area.w <= 0 || area.h <= 0 || max <= min)
        {
            return;
        }

        context.batcher.clip(area, context);

        try
        {
            context.batcher.box(area.x, area.y, area.ex(), area.ey(), BBSSettings.sunkenSurface());

            double pixelsPerSecond = area.w / (max - min);
            double step = majorStep(pixelsPerSecond);
            double minor = step / 5D;
            int divider = BBSSettings.dividerColor();

            int majorColor = Colors.setA(divider, 0.72F);
            int minorColor = Colors.setA(divider, 0.32F);
            int labelColor = Colors.setA(Colors.WHITE, 0.78F);

            double first = Math.floor(min / minor) * minor;
            int guard = 0;

            for (double time = first; time <= max + minor * 0.5D && guard < 4000; time += minor, guard++)
            {
                if (time < 0D)
                {
                    continue;
                }

                int x = toX.applyAsInt(time);
                boolean major = Math.abs(time / step - Math.round(time / step)) < 0.0001D;

                context.batcher.box(x, major ? area.y + 11 : area.y + 15, x + 1, area.ey(), major ? majorColor : minorColor);

                if (major)
                {
                    context.batcher.textShadow(format(time, step), x + 3, area.y + 2, labelColor);
                }
            }

            context.batcher.box(area.x, area.ey() - 1, area.ex(), area.ey(), BBSSettings.dividerColor());
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    public static String format(double seconds, double step)
    {
        if (step >= 1D)
        {
            int total = (int) Math.round(seconds);
            int minutes = total / 60;
            int rest = total % 60;

            return minutes > 0
                ? minutes + ":" + (rest < 10 ? "0" : "") + rest
                : rest + "s";
        }

        if (step >= 0.1D)
        {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }

        return String.format(Locale.ROOT, "%.2fs", seconds);
    }
}
