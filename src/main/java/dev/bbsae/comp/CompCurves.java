package dev.bbsae.comp;

import mchorse.bbs_mod.utils.interps.AutoBezier;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.keyframes.BezierUtils;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.List;

/**
 * After Effects curve math on top of BBS keyframes.
 *
 * <p>Why this class exists: BBS stores one {@link Transform} per transform keyframe and evaluates it
 * through {@code Transform.lerp}, which applies a single {@link IInterp} per component. That path
 * cannot honour per-keyframe bezier handles, so a real AE value graph could not exist for position,
 * scale or rotation. This class evaluates each component itself, using the <b>same</b> handle math
 * BBS already uses for float channels ({@link BezierUtils}), so a curve drawn in the editor is
 * exactly the curve that renders.</p>
 *
 * <p>Handle convention (identical to BBS and to AE's value graph):</p>
 * <ul>
 *   <li>{@code rx, ry} — outgoing handle of a key, offset from the key in (frames, value units).</li>
 *   <li>{@code lx, ly} — incoming handle of a key, offset to the left in frames and in value units.</li>
 *   <li>The unit bezier for a segment is P0=(0,0), P1=(rx/w, ry/h), P2=(1-lx/w, 1+ly/h), P3=(1,1).</li>
 * </ul>
 * <p>That is literally AE's "value graph with bezier handles", which is why the editor can show the
 * exact same tangents.</p>
 */
public final class CompCurves
{
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_ROTATION = 2;
    public static final int AXIS_SCALE_X = 3;
    public static final int AXIS_SCALE_Y = 4;
    /** 3D axes. They use the same independent scalar-channel paths as the 2D axes. */
    public static final int AXIS_Z = 5;
    public static final int AXIS_ROTATION_X = 6;
    public static final int AXIS_ROTATION_Y = 7;
    public static final int AXIS_SCALE_Z = 8;
    public static final int AXIS_COUNT = 9;

    private static final int[] AXES_2D = {AXIS_X, AXIS_Y, AXIS_ROTATION, AXIS_SCALE_X, AXIS_SCALE_Y};
    private static final int[] AXES_3D = {
        AXIS_X, AXIS_Y, AXIS_Z,
        AXIS_ROTATION, AXIS_ROTATION_X, AXIS_ROTATION_Y,
        AXIS_SCALE_X, AXIS_SCALE_Y, AXIS_SCALE_Z
    };

    private static final String[] AXIS_PATHS = {
        "x", "y", "rotation", "scaleX", "scaleY", "z", "rotationX", "rotationY", "scaleZ"
    };
    private static final String[] AXIS_LABELS = {
        "位置 X", "位置 Y", "旋转 Z", "缩放 X", "缩放 Y",
        "位置 Z", "旋转 X", "旋转 Y", "缩放 Z"
    };
    private static final int[] AXIS_COLORS = {
        0xFFE05C5C, 0xFF67C46B, 0xFF5B9BD5, 0xFFE0A93B, 0xFFC77DD0,
        0xFF5FD0C8, 0xFFFF8D6B, 0xFF8DA8FF, 0xFFFFC45C
    };

    public static int[] axes(boolean threeD)
    {
        return threeD ? AXES_3D.clone() : AXES_2D.clone();
    }

    public static String axisPath(int axis)
    {
        return axis >= 0 && axis < AXIS_PATHS.length ? AXIS_PATHS[axis] : "x";
    }

    public static String axisLabel(int axis)
    {
        return axis >= 0 && axis < AXIS_LABELS.length ? AXIS_LABELS[axis] : "值";
    }

    public static int axisColor(int axis)
    {
        return axis >= 0 && axis < AXIS_COLORS.length ? AXIS_COLORS[axis] : 0xFF9AA6B8;
    }

    public static boolean isTransformAxisPath(String path)
    {
        return axisForPath(path) >= 0;
    }

    public static int axisForPath(String path)
    {
        if (path == null)
        {
            return -1;
        }

        for (int i = 0; i < AXIS_PATHS.length; i++)
        {
            if (AXIS_PATHS[i].equals(path))
            {
                return i;
            }
        }

        return -1;
    }

    public static boolean isScaleAxis(int axis)
    {
        return axis == AXIS_SCALE_X || axis == AXIS_SCALE_Y || axis == AXIS_SCALE_Z;
    }

    /** AE's Easy Ease default: the handle covers a third of the segment, horizontally. */
    public static final float DEFAULT_INFLUENCE = 1F / 3F;

    public static final int EASE_BOTH = 0;
    public static final int EASE_IN = 1;
    public static final int EASE_OUT = 2;

    private CompCurves()
    {}

    /* Component access */

    public static float component(Transform transform, int axis)
    {
        if (transform == null)
        {
            return axis == AXIS_SCALE_X || axis == AXIS_SCALE_Y || axis == AXIS_SCALE_Z ? 1F : 0F;
        }

        switch (axis)
        {
            case AXIS_Y: return transform.translate.y;
            case AXIS_Z: return transform.translate.z;
            case AXIS_ROTATION: return transform.rotate.z;
            case AXIS_ROTATION_X: return transform.rotate.x;
            case AXIS_ROTATION_Y: return transform.rotate.y;
            case AXIS_SCALE_X: return transform.scale.x;
            case AXIS_SCALE_Y: return transform.scale.y;
            case AXIS_SCALE_Z: return transform.scale.z;
            case AXIS_X:
            default: return transform.translate.x;
        }
    }

    public static void setComponent(Transform transform, int axis, float value)
    {
        if (transform == null)
        {
            return;
        }

        switch (axis)
        {
            case AXIS_Y: transform.translate.y = value; break;
            case AXIS_Z: transform.translate.z = value; break;
            case AXIS_ROTATION: transform.rotate.z = value; break;
            case AXIS_ROTATION_X: transform.rotate.x = value; break;
            case AXIS_ROTATION_Y: transform.rotate.y = value; break;
            case AXIS_SCALE_X: transform.scale.x = value; break;
            case AXIS_SCALE_Y: transform.scale.y = value; break;
            case AXIS_SCALE_Z: transform.scale.z = value; break;
            case AXIS_X:
            default: transform.translate.x = value; break;
        }
    }

    /* Evaluation */

    /**
     * Value of one transform component at {@code frame}. Replicates BBS's segment rules
     * (forced duration, hold before the first key and after the last key) so switching the curve
     * editor on cannot change what an existing project plays back.
     */
    public static float transformValue(KeyframeChannel<Transform> channel, int axis, float frame, float fallback)
    {
        if (channel == null || channel.getKeyframes().isEmpty())
        {
            return fallback;
        }

        List<Keyframe<Transform>> keys = channel.getKeyframes();
        int size = keys.size();

        if (size == 1 || frame <= keys.get(0).getTick())
        {
            return component(keys.get(0).getValue(), axis);
        }

        if (frame >= keys.get(size - 1).getTick())
        {
            return component(keys.get(size - 1).getValue(), axis);
        }

        /* Binary search for the last key at or before the frame. transformAt() runs several times
         * per layer per frame, so a linear scan made long channels quadratic in cost. */
        int low = 0;
        int high = size - 1;

        while (low < high)
        {
            int mid = (low + high + 1) >>> 1;

            if (keys.get(mid).getTick() <= frame)
            {
                low = mid;
            }
            else
            {
                high = mid - 1;
            }
        }

        int index = Math.min(low, size - 2);

        Keyframe<Transform> a = keys.get(index);
        Keyframe<Transform> b = keys.get(index + 1);
        Keyframe<Transform> preA = index > 0 ? keys.get(index - 1) : a;
        Keyframe<Transform> postB = index + 2 < size ? keys.get(index + 2) : b;
        float gap = b.getTick() - a.getTick();

        if (gap <= 0.00001F)
        {
            return component(b.getValue(), axis);
        }

        float duration = a.getDuration() > 0F ? a.getDuration() : gap;
        float x = Math.max(0F, Math.min(1F, (frame - a.getTick()) / Math.max(0.00001F, duration)));

        return evaluate(a, b, preA, postB, axis, x);
    }

    /**
     * Interpolates between two transform keys for one component. Kept separate from the channel
     * lookup so both the renderer and the curve editor use one implementation.
     */
    public static float evaluate(Keyframe<Transform> a, Keyframe<Transform> b, Keyframe<Transform> preA, Keyframe<Transform> postB, int axis, float x)
    {
        Interpolation interp = a.getInterpolation();

        if (interp.has(Interpolations.CONST))
        {
            return component(a.getValue(), axis);
        }

        if (x <= 0F) return component(a.getValue(), axis);
        if (x >= 1F) return component(b.getValue(), axis);

        float av = component(a.getValue(), axis);
        float bv = component(b.getValue(), axis);

        if (interp.has(Interpolations.BEZIER))
        {
            return (float) BezierUtils.get(av, bv, a.getTick(), b.getTick(), a.rx, a.ry, b.lx, b.ly, x);
        }

        if (interp.has(Interpolations.AUTO) || interp.has(Interpolations.AUTO_CLAMPED))
        {
            return (float) AutoBezier.get(
                component(preA.getValue(), axis), av, bv, component(postB.getValue(), axis),
                preA.getTick(), a.getTick(), b.getTick(), postB.getTick(),
                interp.has(Interpolations.AUTO_CLAMPED), x
            );
        }

        if (interp.has(Interpolations.CUBIC))
        {
            return Lerps.cubic(
                component(preA.getValue(), axis) + (float) interp.getV1(), av, bv,
                component(postB.getValue(), axis) + (float) interp.getV2(), x
            );
        }

        return (float) interp.interpolate(IInterp.context.set(
            component(preA.getValue(), axis), av, bv, component(postB.getValue(), axis), x
        ));
    }

    /**
     * Numeric slope of a segment, in value units per second. The speed graph reads this, and it is
     * also what the handle's "speed" number means in AE.
     */
    public static float speedAt(KeyframeChannel<Transform> channel, int axis, float frame, float fps)
    {
        float step = 0.5F;
        float a = transformValue(channel, axis, frame - step, 0F);
        float b = transformValue(channel, axis, frame + step, 0F);

        return (b - a) / (2F * step / Math.max(1F, fps));
    }

    /* Handles and easing */

    public static float segmentLength(Keyframe<?> a, Keyframe<?> b)
    {
        return Math.max(0.0001F, b.getTick() - a.getTick());
    }

    /** Influence of a handle: how much of the segment its time offset covers, 0..1. */
    public static float influenceOf(Keyframe<?> a, Keyframe<?> b, boolean outgoing)
    {
        float length = segmentLength(a, b);

        return Math.max(0F, Math.min(1F, (outgoing ? a.rx : b.lx) / length));
    }

    /** Speed of a handle in value units per second (the y of the tangent). */
    public static float handleSpeed(Keyframe<?> key, float rx, float ry, float fps)
    {
        if (Math.abs(rx) < 0.00001F)
        {
            return 0F;
        }

        return ry / rx * Math.max(1F, fps);
    }

    /**
     * AE's Easy Ease, expressed with BBS handles.
     *
     * @param side {@link #EASE_BOTH}, {@link #EASE_IN} (incoming segment) or {@link #EASE_OUT}.
     * @param influence how far the handles reach into the segment, 0.01..1 (AE default 0.3333).
     */
    public static void applyEase(KeyframeChannel<?> channel, List<Keyframe> selection, int side, float influence)
    {
        if (channel == null || selection == null || selection.isEmpty())
        {
            return;
        }

        List<Keyframe> keys = castKeys(channel);
        influence = Math.max(0.01F, Math.min(1F, influence));

        for (Keyframe key : selection)
        {
            int index = indexOfIdentity(keys, key);

            if (index < 0)
            {
                continue;
            }

            if (side == EASE_BOTH || side == EASE_OUT)
            {
                Keyframe next = index + 1 < keys.size() ? keys.get(index + 1) : null;

                if (next != null)
                {
                    key.rx = segmentLength(key, next) * influence;
                    key.ry = 0F;
                    key.getInterpolation().setInterp(Interpolations.BEZIER);
                }
            }

            if (side == EASE_BOTH || side == EASE_IN)
            {
                Keyframe prev = index > 0 ? keys.get(index - 1) : null;

                if (prev != null)
                {
                    key.lx = segmentLength(prev, key) * influence;
                    key.ly = 0F;
                    prev.getInterpolation().setInterp(Interpolations.BEZIER);
                }
            }

            /* A single key with no neighbours still needs a type that can hold handles. */
            if (side == EASE_BOTH && index == 0 && keys.size() == 1)
            {
                key.getInterpolation().setInterp(Interpolations.BEZIER);
            }
        }
    }

    /** Linear: no handles, straight segments. AE's "Linear" keyframe type. */
    public static void applyLinear(KeyframeChannel<?> channel, List<Keyframe> selection)
    {
        for (Keyframe key : selection)
        {
            key.rx = 5F;
            key.ry = 0F;
            key.lx = 5F;
            key.ly = 0F;
            key.getInterpolation().setInterp(Interpolations.LINEAR);
        }
    }

    /** Hold: the value jumps at the next key. AE's stopwatch-with-hold keyframe. */
    public static void applyHold(KeyframeChannel<?> channel, List<Keyframe> selection)
    {
        for (Keyframe key : selection)
        {
            key.getInterpolation().setInterp(Interpolations.CONST);
        }
    }

    /**
     * "Bezier" without discarding custom handles: it only switches the type, keeping whatever
     * tangents the key already has. A freshly created BBS key has the generic ±5-frame/zero-value
     * handles, which are invisible at normal graph zoom — the editor would claim Bezier was on
     * while the user had nothing to grab. Default handles are therefore expanded to AE's 33 %
     * influence at each adjacent segment, exactly like AE's Convert to Bezier.
     */
    public static void applyBezier(KeyframeChannel<?> channel, List<Keyframe> selection)
    {
        List<Keyframe> keys = castKeys(channel);

        for (Keyframe key : selection)
        {
            int index = indexOfIdentity(keys, key);

            if (index >= 0 && isDefaultHandles(key))
            {
                Keyframe prev = index > 0 ? keys.get(index - 1) : null;
                Keyframe next = index + 1 < keys.size() ? keys.get(index + 1) : null;

                key.rx = next == null ? 5F : segmentLength(key, next) * DEFAULT_INFLUENCE;
                key.lx = prev == null ? 5F : segmentLength(prev, key) * DEFAULT_INFLUENCE;
                key.ry = 0F;
                key.ly = 0F;
            }

            key.getInterpolation().setInterp(Interpolations.BEZIER);
        }
    }

    /** True for BBS's untouched default handle pair (±5 frames, zero slope). */
    public static boolean isDefaultHandles(Keyframe<?> key)
    {
        return Math.abs(key.rx - 5F) < 0.001F
            && Math.abs(key.lx - 5F) < 0.001F
            && Math.abs(key.ry) < 0.001F
            && Math.abs(key.ly) < 0.001F;
    }

    /**
     * Expands a bezier key's untouched default handles to AE's one-third influence.
     *
     * <p>Called when a bezier key is created or re-selected: a handle five frames from its key is
     * a couple of pixels at normal graph zoom, which is indistinguishable from "no handles" and
     * impossible to grab. This never touches a handle the user has moved.</p>
     */
    public static void ensureBezierHandles(KeyframeChannel<?> channel, Keyframe<?> key)
    {
        if (channel == null || key == null || isDefaultHandles(key) == false)
        {
            return;
        }

        if (!key.getInterpolation().has(Interpolations.BEZIER))
        {
            return;
        }

        List<Keyframe> keys = castKeys(channel);
        int index = indexOfIdentity(keys, key);

        if (index < 0)
        {
            return;
        }

        Keyframe prev = index > 0 ? keys.get(index - 1) : null;
        Keyframe next = index + 1 < keys.size() ? keys.get(index + 1) : null;

        key.rx = next == null ? 5F : segmentLength(key, next) * DEFAULT_INFLUENCE;
        key.lx = prev == null ? 5F : segmentLength(prev, key) * DEFAULT_INFLUENCE;
        key.ry = 0F;
        key.ly = 0F;
    }

    public static void applyAuto(KeyframeChannel<?> channel, List<Keyframe> selection, boolean clamped)
    {
        for (Keyframe key : selection)
        {
            key.getInterpolation().setInterp(clamped ? Interpolations.AUTO_CLAMPED : Interpolations.AUTO);
        }
    }

    /**
     * Converts a key's handles so its tangent matches the current neighbours' slope — AE's
     * "Continuous Bezier". With one neighbour the tangent is the straight line to it.
     *
     * @param valueOf extracts the numeric value of a key for the edited component; a Transform
     *                channel has no single number, so the caller supplies the axis.
     */
    public static void applyContinuous(KeyframeChannel<?> channel, List<Keyframe> selection, java.util.function.ToDoubleFunction<Keyframe> valueOf)
    {
        List<Keyframe> keys = castKeys(channel);

        for (Keyframe key : selection)
        {
            int index = indexOfIdentity(keys, key);

            if (index < 0)
            {
                continue;
            }

            Keyframe prev = index > 0 ? keys.get(index - 1) : null;
            Keyframe next = index + 1 < keys.size() ? keys.get(index + 1) : null;

            if (prev == null && next == null)
            {
                continue;
            }

            float slope;

            if (prev != null && next != null)
            {
                slope = (float) ((valueOf.applyAsDouble(next) - valueOf.applyAsDouble(prev)) / Math.max(0.0001F, next.getTick() - prev.getTick()));
            }
            else if (next != null)
            {
                slope = (float) ((valueOf.applyAsDouble(next) - valueOf.applyAsDouble(key)) / Math.max(0.0001F, next.getTick() - key.getTick()));
            }
            else
            {
                slope = (float) ((valueOf.applyAsDouble(key) - valueOf.applyAsDouble(prev)) / Math.max(0.0001F, key.getTick() - prev.getTick()));
            }

            if (next != null)
            {
                key.rx = segmentLength(key, next) * DEFAULT_INFLUENCE;
                key.ry = slope * key.rx;
                key.getInterpolation().setInterp(Interpolations.BEZIER);
            }

            if (prev != null)
            {
                key.lx = segmentLength(prev, key) * DEFAULT_INFLUENCE;
                key.ly = -slope * key.lx;
                prev.getInterpolation().setInterp(Interpolations.BEZIER);
            }
        }
    }

    /**
     * Links a key's handles so the tangent is continuous through it — AE's default when dragging a
     * handle without Alt.
     *
     * <p>AE keeps each side's <b>length</b> and mirrors the <b>slope</b>, it does not copy the time
     * offset: a handle that reaches 30% into a long segment should not be forced to reach 30% into
     * a short one. With (rx, ry) and (lx, ly) as offsets, equal slope means
     * {@code ly / lx = -ry / rx}.</p>
     *
     * @param fromRight true when the right handle was dragged (so the left one follows).
     */
    public static void mirrorHandles(Keyframe<?> key, boolean fromRight)
    {
        if (fromRight)
        {
            if (key.rx > 0.0001F)
            {
                key.ly = -key.ry * (key.lx / key.rx);
            }
        }
        else if (key.lx > 0.0001F)
        {
            key.ry = -key.ly * (key.rx / key.lx);
        }
    }

    /** Identity index: two keyframes can compare equal (same tick/value/id) and still be different
     * objects, so indexOf() would edit the wrong one after a duplicate. */
    public static int indexOfIdentity(List<Keyframe> keys, Keyframe<?> key)
    {
        for (int i = 0; i < keys.size(); i++)
        {
            if (keys.get(i) == key)
            {
                return i;
            }
        }

        return -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Keyframe> castKeys(KeyframeChannel<?> channel)
    {
        return (List<Keyframe>) (List<?>) channel.getKeyframes();
    }
}
