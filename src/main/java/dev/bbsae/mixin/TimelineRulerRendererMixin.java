package dev.bbsae.mixin;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.ui.CompKeyframeRuler;
import dev.bbsae.client.ui.CompTimelineUnits;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.renderers.TimelineRulerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Routes the compositor's keyframe ruler through {@link CompKeyframeRuler} instead of painting a
 * second ruler from the decorator.
 *
 * <p>{@code UIKeyframes.rulerRenderer} is called <b>inside</b> {@code TimelineRulerRenderer.render}
 * (after the background, before the ticks). The compositor's decorator therefore cannot replace
 * the tick pass by drawing another ruler: the default pass runs after it and paints its frame-unit
 * labels on top. This injection replaces that one pass when the decorator marked the frame with
 * {@link CompTimelineUnits}; every other editor keeps BBS' original tick/label behaviour.</p>
 */
@Mixin(TimelineRulerRenderer.class)
public class TimelineRulerRendererMixin
{
    @Inject(method = "renderTicks", at = @At("HEAD"), cancellable = true)
    private static void bbsae$renderFrameAwareTicks(UIContext context, Area area, int startTick,
                                                    int timelineBottom, int visibleEx,
                                                    IntUnaryOperator toGraphX,
                                                    IntFunction<String> labelFormatter,
                                                    CallbackInfo ci)
    {
        float fps = CompTimelineUnits.framesPerSecond();

        if (fps <= 0F)
        {
            return;
        }

        try
        {
            CompKeyframeRuler.renderTicks(context, area, startTick, timelineBottom, visibleEx,
                toGraphX, fps, CompTimelineUnits.seconds());
            ci.cancel();
        }
        catch (RuntimeException e)
        {
            /* A ruler must never take the editor down. Fall through to BBS' own tick pass; the
             * labels may be in frame units for this frame, but the drawer stays usable. */
            CompTimelineUnits.clear();
            BbsAeAddon.LOGGER.warn("Compositor keyframe ruler failed; using BBS' default ruler", e);
        }
    }
}
