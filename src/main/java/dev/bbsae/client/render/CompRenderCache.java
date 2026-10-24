package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompShape;
import dev.bbsae.comp.CompStyle;
import dev.bbsae.comp.CompTextAnimator;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.Map;

/**
 * A cheap content fingerprint of a composition frame.
 *
 * <p>Why: the editor used to re-render the whole graph (10–30 full-screen passes at 1080p) every
 * display frame, even while the playhead was parked and nothing was being edited. That is the
 * single largest cost in the compositor and it is pure waste when nothing changed.</p>
 *
 * <p>The signature covers everything that can change a pixel: layer fields, effect/shape/mask/style
 * lists, and the <b>keyframe content</b> of every channel (not just the sampled value, so editing a
 * key elsewhere still invalidates). It is integer math over a handful of fields per layer — orders
 * of magnitude cheaper than one full-screen pass.</p>
 */
public final class CompRenderCache
{
    private static GlFramebuffer cached;
    private static long cachedSignature;
    private static int cachedWidth;
    private static int cachedHeight;
    private static Object cachedDoc;
    /** Composition frame the cached pixels belong to. */
    private static int cachedFrame = Integer.MIN_VALUE;

    /* --- time guard -------------------------------------------------------
     * While a curve segment is being dragged, only frames inside that segment can change.
     * A parked playhead outside the segment can keep the frame it already has, even though the
     * document fingerprint changed — that is the difference between "editing a curve re-renders
     * the whole composition every mouse move" and "it only re-renders when the playhead can see
     * the edit". */
    private static boolean guardActive;
    private static float guardFrom;
    private static float guardTo;

    private CompRenderCache()
    {}

    public static boolean matches(long signature, CompDocument doc, int width, int height)
    {
        return cached != null
            && signature == cachedSignature
            && doc == cachedDoc
            && width == cachedWidth
            && height == cachedHeight;
    }

    public static GlFramebuffer get()
    {
        return cached;
    }

    public static void put(GlFramebuffer framebuffer, long signature, CompDocument doc, int width, int height, int frame)
    {
        if (cached != null && cached != framebuffer)
        {
            FboPool.release(cached);
        }

        cached = framebuffer;
        cachedSignature = signature;
        cachedDoc = doc;
        cachedWidth = width;
        cachedHeight = height;
        cachedFrame = frame;
    }

    /** Restricts re-rendering to a time range; see the field comment above. */
    public static void setTimeGuard(float from, float to)
    {
        guardActive = true;
        guardFrom = Math.min(from, to);
        guardTo = Math.max(from, to);
    }

    public static void clearTimeGuard()
    {
        guardActive = false;
    }

    /**
     * True when the cached frame is still pixel-identical for this time.
     *
     * <p>Requires the same composition frame index: a guard only excuses a changed
     * <em>fingerprint</em>, never a different point in time.</p>
     */
    public static boolean reuseForTime(float time, int frame)
    {
        return guardActive
            && cached != null
            && cachedFrame == frame
            && (time < guardFrom || time > guardTo);
    }

    public static void clear()
    {
        if (cached != null)
        {
            FboPool.release(cached);
            cached = null;
        }

        cachedDoc = null;
        cachedSignature = 0L;
        cachedFrame = Integer.MIN_VALUE;
        guardActive = false;
    }

    /* ---------------------------------------------------------------- signature */

    public static long signature(CompProject project, CompDocument doc, float time, boolean effects, boolean masks, int fit, int frameWidth, int frameHeight, boolean cameraOverlays)
    {
        long h = 0x9E3779B97F4A7C15L;

        float fps = Math.max(1F, doc.fps);
        /* Quantise to the composition frame rate: playing a 30 fps comp on a 120 Hz display must
         * not render four identical frames. */
        long frameIndex = Math.round(time * fps);
        float frame = frameIndex;

        h = mix(h, frameIndex);
        h = mix(h, doc.width);
        h = mix(h, doc.height);
        h = mix(h, doc.background);
        h = mix(h, doc.layers == null ? 0 : doc.layers.size());
        h = mix(h, effects ? 1 : 2);
        h = mix(h, masks ? 3 : 4);
        h = mix(h, fit);
        h = mix(h, frameWidth);
        h = mix(h, frameHeight);
        h = mix(h, cameraOverlays ? 5 : 6);
        h = mix(h, doc.activeCameraId == null ? 0 : doc.activeCameraId.hashCode());

        if (project != null && project.settings != null)
        {
            h = mix(h, project.settings.previewBackground);
        }

        if (doc.layers == null)
        {
            return h;
        }

        for (CompLayer layer : doc.layers)
        {
            h = layer(h, layer, frame);
        }

        return h;
    }

    private static long layer(long h, CompLayer layer, float frame)
    {
        if (layer == null)
        {
            return mix(h, 0);
        }

        h = mix(h, layer.id.hashCode());
        h = mix(h, layer.type == null ? 0 : layer.type.hashCode());
        h = mix(h, layer.visible ? 1 : 0);
        h = mix(h, layer.guide ? 1 : 0);
        h = mix(h, layer.solo ? 1 : 0);
        h = mix(h, layer.threeD ? 1 : 0);
        h = mix(h, layer.in);
        h = mix(h, layer.out);
        h = mix(h, layer.startTime);
        h = mix(h, layer.timeScale);
        h = mix(h, layer.freeze ? layer.freezeTime : 0F);
        h = mix(h, layer.timeRemap ? 1 : 0);

        h = mix(h, layer.x);
        h = mix(h, layer.y);
        h = mix(h, layer.z);
        h = mix(h, layer.width);
        h = mix(h, layer.height);
        h = mix(h, layer.rotation);
        h = mix(h, layer.rotationX);
        h = mix(h, layer.rotationY);
        h = mix(h, layer.scaleX);
        h = mix(h, layer.scaleY);
        h = mix(h, layer.scaleZ);
        h = mix(h, layer.squash);
        h = mix(h, layer.anchorX);
        h = mix(h, layer.anchorY);
        h = mix(h, layer.opacity);
        h = mix(h, layer.blendMode);

        h = mix(h, layer.color);
        h = mix(h, layer.strokeColor);
        h = mix(h, layer.strokeWidth);
        h = mix(h, layer.text == null ? 0 : layer.text.hashCode());
        h = mix(h, layer.fontName == null ? 0 : layer.fontName.hashCode());
        h = mix(h, layer.fontSize);
        h = mix(h, layer.tracking);
        h = mix(h, layer.lineHeight);
        h = mix(h, layer.align);
        h = mix(h, layer.wrap ? 1 : 0);
        h = mix(h, layer.imagePath == null ? 0 : layer.imagePath.hashCode());
        h = mix(h, layer.imageFit);
        h = mix(h, layer.precompId == null ? 0 : layer.precompId.hashCode());
        h = mix(h, layer.precompTimeOffset);
        h = mix(h, layer.precompTimeScale);
        h = mix(h, layer.parentId == null ? 0 : layer.parentId.hashCode());
        h = mix(h, layer.trackMatteEnabled ? 1 : 0);
        h = mix(h, layer.trackMatteId == null ? 0 : layer.trackMatteId.hashCode());
        h = mix(h, layer.trackMatteMode);

        h = mix(h, layer.cameraZ);
        h = mix(h, layer.cameraTargetX);
        h = mix(h, layer.cameraTargetY);
        h = mix(h, layer.cameraTargetZ);
        h = mix(h, layer.cameraFov);
        h = mix(h, layer.cameraNear);
        h = mix(h, layer.cameraFar);
        h = mix(h, layer.cameraLookAt ? 1 : 0);
        h = mix(h, layer.camera3D ? 1 : 0);

        h = mix(h, layer.expressionX == null ? 0 : layer.expressionX.hashCode());
        h = mix(h, layer.expressionY == null ? 0 : layer.expressionY.hashCode());
        h = mix(h, layer.expressionRotation == null ? 0 : layer.expressionRotation.hashCode());
        h = mix(h, layer.expressionOpacity == null ? 0 : layer.expressionOpacity.hashCode());
        h = mix(h, layer.motion ? 1 : 0);
        h = mix(h, layer.motionAmount);
        h = mix(h, layer.fill);

        h = channels(h, layer.transformChannel());
        h = channels(h, layer.opacityChannel());

        if (layer.animatedPaths() != null)
        {
            for (String path : layer.animatedPaths())
            {
                h = mix(h, path.hashCode());
                h = channels(h, layer.scalarChannel(path));
            }
        }

        for (CompEffect effect : layer.effects)
        {
            if (effect == null) continue;

            h = mix(h, effect.id.hashCode());
            h = mix(h, effect.type == null ? 0 : effect.type.hashCode());
            h = mix(h, effect.enabled ? 1 : 0);

            for (Map.Entry<String, Double> entry : effect.params.entrySet())
            {
                h = mix(h, entry.getKey().hashCode());
                h = mix(h, entry.getValue() == null ? 0F : entry.getValue().floatValue());
            }

            for (Map.Entry<String, Integer> entry : effect.colors.entrySet())
            {
                h = mix(h, entry.getKey().hashCode());
                h = mix(h, entry.getValue() == null ? 0 : entry.getValue());
            }
        }

        for (CompStyle style : layer.styles)
        {
            if (style == null) continue;

            h = mix(h, style.type == null ? 0 : style.type.hashCode());
            h = mix(h, style.enabled ? 1 : 0);
            h = mix(h, style.color);
            h = mix(h, style.color2);
            h = mix(h, style.opacity);
            h = mix(h, style.size);
            h = mix(h, style.distance);
            h = mix(h, style.angle);
            h = mix(h, style.blur);
            h = mix(h, style.blendMode);
        }

        for (CompMask mask : layer.masks)
        {
            if (mask == null) continue;

            h = mix(h, mask.id.hashCode());
            h = mix(h, mask.enabled ? 1 : 0);
            h = mix(h, mask.inverted ? 1 : 0);
            h = mix(h, mask.mode);
            h = mix(h, mask.opacity);
            h = mix(h, mask.feather);
            h = mix(h, mask.expansion);

            for (CompMask.CompMaskPoint point : mask.points)
            {
                h = mix(h, point.x);
                h = mix(h, point.y);
                h = mix(h, point.inX);
                h = mix(h, point.inY);
                h = mix(h, point.outX);
                h = mix(h, point.outY);
            }
        }

        for (CompShape shape : layer.shapes)
        {
            if (shape == null) continue;

            h = mix(h, shape.id.hashCode());
            h = mix(h, shape.type == null ? 0 : shape.type.hashCode());
            h = mix(h, shape.enabled ? 1 : 0);
            h = mix(h, shape.operator);
            h = mix(h, shape.x);
            h = mix(h, shape.y);
            h = mix(h, shape.width);
            h = mix(h, shape.height);
            h = mix(h, shape.roundness);
            h = mix(h, shape.rotation);
            h = mix(h, shape.sides);
            h = mix(h, shape.innerRadius);
            h = mix(h, shape.outerRadius);
            h = mix(h, shape.fillColor);
            h = mix(h, shape.fillColor2);
            h = mix(h, shape.fillGradient ? 1 : 0);
            h = mix(h, shape.fillGradientAngle);
            h = mix(h, shape.strokeColor);
            h = mix(h, shape.strokeWidth);
            h = mix(h, shape.trimStart);
            h = mix(h, shape.trimEnd);
            h = mix(h, shape.trimOffset);
            h = mix(h, shape.cornerStyle);
            h = mix(h, shape.repeaterCount);
            h = mix(h, shape.repeaterOffsetX);
            h = mix(h, shape.repeaterOffsetY);
            h = mix(h, shape.repeaterScale);
            h = mix(h, shape.repeaterRotation);
            h = mix(h, shape.repeaterOpacity);

            if (shape.corners != null)
            {
                for (Float corner : shape.corners)
                {
                    h = mix(h, corner == null ? 0F : corner);
                }
            }

            for (CompShape.Point point : shape.points)
            {
                h = mix(h, point.x);
                h = mix(h, point.y);
                h = mix(h, point.inX);
                h = mix(h, point.inY);
                h = mix(h, point.outX);
                h = mix(h, point.outY);
            }
        }

        for (CompTextAnimator animator : layer.textAnimators)
        {
            if (animator == null) continue;

            h = mix(h, animator.enabled ? 1 : 0);
            h = mix(h, animator.start);
            h = mix(h, animator.end);
            h = mix(h, animator.offset);
            h = mix(h, animator.positionX);
            h = mix(h, animator.positionY);
            h = mix(h, animator.scale);
            h = mix(h, animator.rotation);
            h = mix(h, animator.opacity);
            h = mix(h, animator.useColor ? animator.color : 0);
            h = mix(h, animator.wiggle ? 1 : 0);
            h = mix(h, animator.randomness);
            h = mix(h, animator.seed);
            h = mix(h, animator.randomOrder ? 1 : 0);
        }

        if (layer.binding != null)
        {
            h = mix(h, layer.binding.enabled ? 1 : 0);
            h = mix(h, layer.binding.type == null ? 0 : layer.binding.type.hashCode());
            h = mix(h, layer.binding.target == null ? 0 : layer.binding.target.hashCode());
            h = mix(h, layer.binding.offsetX);
            h = mix(h, layer.binding.offsetY);
            h = mix(h, layer.binding.worldOffsetY);
            h = mix(h, layer.binding.smoothing);
        }

        return h;
    }

    private static long channels(long h, KeyframeChannel<?> channel)
    {
        if (channel == null)
        {
            return mix(h, 0);
        }

        int size = channel.getKeyframes().size();

        h = mix(h, size);

        for (Object entry : channel.getKeyframes())
        {
            Keyframe<?> key = (Keyframe<?>) entry;

            h = mix(h, key.getTick());
            h = mix(h, key.contentHash());
            h = mix(h, key.lx);
            h = mix(h, key.ly);
            h = mix(h, key.rx);
            h = mix(h, key.ry);
        }

        return h;
    }

    private static long mix(long hash, long value)
    {
        long h = hash ^ value;

        h *= 0x100000001B3L;
        h ^= h >>> 29;

        return h;
    }

    private static long mix(long hash, float value)
    {
        return mix(hash, Float.floatToIntBits(value));
    }

    private static long mix(long hash, int value)
    {
        return mix(hash, (long) value);
    }

    private static long mix(long hash, boolean value)
    {
        return mix(hash, value ? 1L : 0L);
    }
}
