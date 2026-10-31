package dev.bbsae.comp;

import com.google.gson.Gson;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One layer in a composition.
 *
 * <p>The common transform/opacity channels are the same BBS {@link KeyframeChannel}s the rest of
 * BBS uses, which is what lets the native keyframe editor, interpolation menu, graph editor and
 * copy/paste act on composition data without a second timeline implementation. Additional scalar
 * properties (effect parameters, mask feather, text size, shape trim, ...) are stored in
 * {@link #channels} under stable paths; those also become native keyframe rows.</p>
 *
 * <p>Types are intentionally data-driven rather than a class per layer:
 * {@code solid, panel, rect, bar, text, image, shape, null, adjustment, precomp, footage}.
 * That keeps old project files readable and makes a missing layer type degrade to a placeholder
 * instead of failing to deserialize.</p>
 */
public class CompLayer
{
    public static final int BLEND_NORMAL = 0;
    public static final int BLEND_ADD = 1;
    public static final int BLEND_MULTIPLY = 2;
    public static final int BLEND_SCREEN = 3;
    public static final int BLEND_OVERLAY = 4;
    public static final int BLEND_SOFT_LIGHT = 5;
    public static final int BLEND_HARD_LIGHT = 6;
    public static final int BLEND_DIFFERENCE = 7;
    public static final int BLEND_EXCLUSION = 8;
    public static final int BLEND_DARKEN = 9;
    public static final int BLEND_LIGHTEN = 10;
    public static final int BLEND_COLOR_DODGE = 11;
    public static final int BLEND_COLOR_BURN = 12;

    public static final int MATTE_ALPHA = 0;
    public static final int MATTE_LUMA = 1;
    public static final int MATTE_ALPHA_INVERTED = 2;
    public static final int MATTE_LUMA_INVERTED = 3;

    public String id = "";
    public String name = "";
    public String type = "solid";

    public boolean visible = true;
    public boolean locked = false;
    public boolean solo = false;
    public boolean guide = false;

    /** Composition time, seconds. */
    public float in = 0F;
    public float out = 12F;
    /** Source time offset, seconds. */
    public float startTime = 0F;
    public float timeScale = 1F;
    public boolean freeze = false;
    public float freezeTime = 0F;
    /** When true, the {@code timeRemap} scalar channel (seconds) replaces the source-time formula. */
    public boolean timeRemap = false;

    public float x = 960F;
    public float y = 540F;
    public float width = 400F;
    public float height = 200F;
    public float rotation = 0F;
    public float scaleX = 1F;
    public float scaleY = 1F;

    /* --- 3D layer / camera ------------------------------------------------ */
    /** Only 3D layers are projected through the camera; 2D layers stay screen-attached. */
    public boolean threeD = false;
    public float z = 0F;
    public float rotationX = 0F;
    public float rotationY = 0F;
    public float scaleZ = 1F;
    /** Squash & stretch: scaleX *= 1 + squash, scaleY *= 1 - squash. */
    public float squash = 0F;
    /** Vertical field of view for a 3D camera, degrees. */
    public float cameraFov = 50F;
    /** Near/far in composition pixels for the perspective camera. */
    public float cameraNear = 10F;
    public float cameraFar = 100000F;
    /** True when the camera should be evaluated with the 3D projection. */
    public boolean camera3D = false;
    /** Anchor as a fraction of the layer bounds (0.5 = centre). */
    public float anchorX = 0.5F;
    public float anchorY = 0.5F;
    public float opacity = 1F;
    public int blendMode = BLEND_NORMAL;

    /** ARGB. */
    public int color = 0xFFFFFFFF;
    public int strokeColor = 0x00000000;
    public float strokeWidth = 0F;

    public String text = "";
    public String fontName = "";
    public float fontSize = 48F;
    public float tracking = 0F;
    public float lineHeight = 1.15F;
    public int align = 1;
    public boolean wrap = false;
    public float textBoxWidth = 800F;
    public float textBoxHeight = 300F;

    public String imagePath = "";
    public int imageFit = 0;
    /** Precomp target. */
    public String precompId = "";
    public float precompTimeOffset = 0F;
    public float precompTimeScale = 1F;

    /** AE-style two-node camera: position comes from the normal transform, this is the look-at point. */
    public float cameraTargetX = 960F;
    public float cameraTargetY = 540F;
    public float cameraTargetZ = 0F;
    public float cameraZ = 0F;
    /** When false the camera keeps the transform's rotation instead of looking at the target. */
    public boolean cameraLookAt = true;

    /* --- Replay entity matte --------------------------------------------- */

    /**
     * Which replay entities the matte keeps.
     *
     * <p>{@link #ENTITY_SCOPE_ALL} keys out every enabled replay in the film;
     * {@link #ENTITY_SCOPE_SELECTED} keys out only the ids listed in {@link #entityIds}. The scope
     * is a mode, not a second list, so the checkbox table and the "all entities" switch can never
     * disagree about what is shown.</p>
     */
    public static final int ENTITY_SCOPE_ALL = 0;
    public static final int ENTITY_SCOPE_SELECTED = 1;

    public int entityScope = ENTITY_SCOPE_ALL;
    /** Replay ids kept by {@link #ENTITY_SCOPE_SELECTED}; comma separated, order independent. */
    public String entityIds = "";
    /** Occlude entities with the world depth buffer (a block in front hides the entity). */
    public boolean entityOcclusion = true;
    /** Shrink the matte by this many world pixels; removes the background fringe at the edge. */
    public float entityErode = 0F;
    /** Soften the matte edge by this many world pixels. */
    public float entityFeather = 1.5F;
    /** Extra depth tolerance, in blocks, for the "this pixel is the entity" test. */
    public float entityTolerance = 0.08F;

    /** Parenting and matte references (stable layer ids). */
    public String parentId = "";
    public String trackMatteId = "";
    public int trackMatteMode = MATTE_ALPHA;
    public boolean trackMatteEnabled = false;

    public String expressionX = "";
    public String expressionY = "";
    public String expressionRotation = "";
    public String expressionOpacity = "";

    public CompBinding binding = new CompBinding();
    public List<CompMask> masks = new ArrayList<>();
    public List<CompEffect> effects = new ArrayList<>();
    public List<CompStyle> styles = new ArrayList<>();
    public List<CompShape> shapes = new ArrayList<>();
    public List<CompTextAnimator> textAnimators = new ArrayList<>();
    public List<CompMarker> markers = new ArrayList<>();

    /**
     * BBS-data-serialized channels. Keys are stable property paths; values are BBS data strings.
     * The two legacy fields are kept so projects written by the first slice still load.
     */
    public Map<String, String> channels = new LinkedHashMap<>();
    public String transformData = "";
    public String opacityData = "";

    /* First-slice demo helpers; harmless for new documents. */
    public boolean motion = false;
    public float motionAmount = 40F;
    public float fill = -1F;

    private transient KeyframeChannel<Transform> transformChannel;
    private transient KeyframeChannel<Float> opacityChannel;
    private transient Map<String, KeyframeChannel<Float>> scalarChannels = new LinkedHashMap<>();

    public CompLayer()
    {}

    public CompLayer(String id, String name, String type)
    {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    /* Keyframe channels */

    public KeyframeChannel<Transform> transformChannel()
    {
        if (this.transformChannel == null)
        {
            this.transformChannel = new KeyframeChannel<>("transform", KeyframeFactories.TRANSFORM);
        }

        return this.transformChannel;
    }

    public KeyframeChannel<Float> opacityChannel()
    {
        if (this.opacityChannel == null)
        {
            this.opacityChannel = new KeyframeChannel<>("opacity", KeyframeFactories.FLOAT);
        }

        return this.opacityChannel;
    }

    /* Independent transform axes --------------------------------------------
     *
     * BBS stores one Transform per transform keyframe. That is a convenient render representation
     * but a terrible animation representation: Position X and Y share a keyframe object, so
     * deleting X deletes Y, and each axis cannot have its own timing. AE keeps one curve per
     * component, and so does the compositor now: x/y/rotation/scaleX/scaleY (plus z/rotationX/
     * rotationY/scaleZ for 3D) live in independent scalar channels. The legacy Transform channel
     * is still read as a migration fallback, but it is no longer the source of truth once any axis
     * channel exists.
     */

    public KeyframeChannel<Float> axisChannel(int axis)
    {
        return this.scalarChannel(CompCurves.axisPath(axis));
    }

    public boolean hasAxisKey(int axis)
    {
        return this.axisChannel(axis).getKeyframes().size() > 0;
    }

    public boolean hasAnyTransformKey()
    {
        for (int axis : CompCurves.axes(this.threeD))
        {
            if (this.hasAxisKey(axis))
            {
                return true;
            }
        }

        return false;
    }

    public float axisAt(int axis, float frame, float fallback)
    {
        return this.scalarAt(CompCurves.axisPath(axis), fallback, frame);
    }

    public Keyframe<Float> insertAxisKey(int axis, float frame, float value)
    {
        this.putScalar(CompCurves.axisPath(axis), value);

        KeyframeChannel<Float> channel = this.axisChannel(axis);

        return channel.insertInheriting(frame, value);
    }

    public void insertAxisKeyAt(int axis, float frame)
    {
        this.insertAxisKey(axis, frame, this.axisAt(axis, frame, this.scalar(CompCurves.axisPath(axis), 0F)));
    }

    /** Adds independent keys for every transform component at one frame. */
    public void insertTransformKeys(float frame)
    {
        for (int axis : CompCurves.axes(this.threeD))
        {
            this.insertAxisKeyAt(axis, frame);
        }
    }

    /** Adds one independent key per axis from a fully evaluated pose. */
    public void insertTransformPose(float frame, Transform pose)
    {
        if (pose == null)
        {
            return;
        }

        for (int axis : CompCurves.axes(this.threeD))
        {
            this.insertAxisKey(axis, frame, CompCurves.component(pose, axis));
        }
    }

    public void clearTransformAxes()
    {
        for (int axis : CompCurves.axes(this.threeD))
        {
            this.axisChannel(axis).removeAll();
        }
    }

    /**
     * Converts a project written before independent axes into the new representation.
     *
     * <p>Only migrates when no axis channel has keys, so editing one axis can never re-run the
     * migration and duplicate the other axes. Handles/style/interpolation are copied with
     * {@code copyOverExtra}; the curve shape is therefore preserved even for eased old projects.</p>
     */
    public void ensureIndependentAxes()
    {
        if (this.scalarChannels == null)
        {
            this.scalarChannels = new LinkedHashMap<>();
        }

        boolean anyAxis = this.hasAnyTransformKey();

        if (this.transformChannel == null || this.transformChannel.getKeyframes().isEmpty())
        {
            if (anyAxis)
            {
                this.transformData = "";
            }

            return;
        }

        if (!anyAxis)
        {
            int[] axes = CompCurves.axes(this.threeD);

            for (Object entry : this.transformChannel.getKeyframes())
            {
                @SuppressWarnings("unchecked")
                Keyframe<Transform> source = (Keyframe<Transform>) entry;

                for (int axis : axes)
                {
                    Keyframe<Float> target = this.axisChannel(axis).insertInheriting(
                        source.getTick(),
                        CompCurves.component(source.getValue(), axis)
                    );

                    target.copyOverExtra(source);
                }
            }
        }
        else
        {
            /* Mixed legacy/axis data: only fill axes that have no keys yet. An axis the artist has
             * already edited must never be overwritten by the old shared keys. */
            for (int axis : CompCurves.axes(this.threeD))
            {
                if (this.hasAxisKey(axis))
                {
                    continue;
                }

                for (Object entry : this.transformChannel.getKeyframes())
                {
                    @SuppressWarnings("unchecked")
                    Keyframe<Transform> source = (Keyframe<Transform>) entry;
                    Keyframe<Float> target = this.axisChannel(axis).insertInheriting(
                        source.getTick(),
                        CompCurves.component(source.getValue(), axis)
                    );

                    target.copyOverExtra(source);
                }
            }
        }

        /* The legacy channel is never rendered again. Keeping it would make the native keyframe
         * drawer show a second, shared "transform" row whose deletion still removes every axis. */
        this.transformChannel.removeAll();
        this.transformData = "";
    }

    public KeyframeChannel<Float> scalarChannel(String path)
    {
        if (path == null || path.isEmpty())
        {
            path = "value";
        }

        if (this.scalarChannels == null)
        {
            this.scalarChannels = new LinkedHashMap<>();
        }

        if ("opacity".equals(path))
        {
            return this.opacityChannel();
        }

        KeyframeChannel<Float> channel = this.scalarChannels.get(path);

        if (channel == null)
        {
            channel = new KeyframeChannel<>(path, KeyframeFactories.FLOAT);
            this.scalarChannels.put(path, channel);
        }

        return channel;
    }

    public boolean hasScalarChannel(String path)
    {
        KeyframeChannel<Float> channel = this.scalarChannels == null ? null : this.scalarChannels.get(path);

        return channel != null && !channel.getKeyframes().isEmpty();
    }

    /** Every scalar path that currently has a channel, for the native keyframe timeline. */
    public List<String> animatedPaths()
    {
        if (this.scalarChannels == null)
        {
            this.scalarChannels = new LinkedHashMap<>();
        }

        return new ArrayList<>(this.scalarChannels.keySet());
    }

    public float scalarAt(String path, float fallback, float frame)
    {
        if (path == null)
        {
            return fallback;
        }

        KeyframeChannel<Float> channel = this.scalarChannels == null ? null : this.scalarChannels.get(path);

        if (channel == null || channel.getKeyframes().isEmpty())
        {
            return fallback;
        }

        Float value = channel.interpolate(frame, fallback);

        return value == null ? fallback : value;
    }

    public void putScalar(String path, float value)
    {
        if (path == null)
        {
            return;
        }

        if ("opacity".equals(path))
        {
            this.opacity = clamp01(value);
            return;
        }

        switch (path)
        {
            case "x": this.x = value; break;
            case "y": this.y = value; break;
            case "width": this.width = value; break;
            case "height": this.height = value; break;
            case "rotation": this.rotation = value; break;
            case "scaleX": this.scaleX = value; break;
            case "scaleY": this.scaleY = value; break;
            case "z": this.z = value; break;
            case "rotationX": this.rotationX = value; break;
            case "rotationY": this.rotationY = value; break;
            case "scaleZ": this.scaleZ = Math.max(0.0001F, value); break;
            case "squash": this.squash = Math.max(-0.9F, Math.min(0.9F, value)); break;
            case "cameraZ": this.cameraZ = value; break;
            case "cameraTargetX": this.cameraTargetX = value; break;
            case "cameraTargetY": this.cameraTargetY = value; break;
            case "cameraTargetZ": this.cameraTargetZ = value; break;
            case "cameraFov": this.cameraFov = Math.max(1F, Math.min(179F, value)); break;
            case "fontSize": this.fontSize = value; break;
            case "tracking": this.tracking = value; break;
            case "lineHeight": this.lineHeight = value; break;
            case "strokeWidth": this.strokeWidth = value; break;
            case "precompTimeOffset": this.precompTimeOffset = value; break;
            case "precompTimeScale": this.precompTimeScale = value; break;
            case "text/fontSize": this.fontSize = Math.max(1F, value); break;
            case "text/tracking": this.tracking = value; break;
            case "text/lineHeight": this.lineHeight = Math.max(0.1F, value); break;
            case "text/strokeWidth": this.strokeWidth = Math.max(0F, value); break;
            default:
                if (!this.putNestedScalar(path, value))
                {
                    break;
                }
        }
    }

    private boolean putNestedScalar(String path, float value)
    {
        if (path.startsWith("mask/"))
        {
            String[] parts = path.split("/");

            if (parts.length >= 3)
            {
                CompMask mask = this.getMask(parts[1]);

                if (mask == null)
                {
                    return true;
                }

                if (parts.length == 3)
                {
                    switch (parts[2])
                    {
                        case "feather": mask.feather = Math.max(0F, value); return true;
                        case "opacity": mask.opacity = clamp01(value); return true;
                        case "expansion": mask.expansion = value; return true;
                        default: return true;
                    }
                }

                if (parts.length >= 5 && "p".equals(parts[2]))
                {
                    int index = parseInt(parts[3], -1);

                    if (index < 0 || index >= mask.points.size())
                    {
                        return true;
                    }

                    CompMask.CompMaskPoint point = mask.points.get(index);

                    switch (parts[4])
                    {
                        case "x": point.x = value; return true;
                        case "y": point.y = value; return true;
                        case "inX": point.inX = value; return true;
                        case "inY": point.inY = value; return true;
                        case "outX": point.outX = value; return true;
                        case "outY": point.outY = value; return true;
                        default: return true;
                    }
                }
            }
        }

        if (path.startsWith("shape/"))
        {
            String[] parts = path.split("/");

            /* Per-corner radius: shape/<id>/c/<index>. Checked before the property switch because
             * that switch returns on every unknown key, which would swallow the corner path. */
            if (parts.length >= 4 && "c".equals(parts[2]))
            {
                CompShape shape = this.getShape(parts[1]);
                int index = parseInt(parts[3], -1);

                if (shape == null || index < 0)
                {
                    return true;
                }

                shape.ensureCorners();
                shape.corners.set(index, Math.max(0F, value));

                return true;
            }

            if (parts.length >= 3 && !"p".equals(parts[2]))
            {
                CompShape shape = this.getShape(parts[1]);

                if (shape == null)
                {
                    return true;
                }

                switch (parts[2])
                {
                    case "x": shape.x = value; return true;
                    case "y": shape.y = value; return true;
                    case "width": shape.width = value; return true;
                    case "height": shape.height = value; return true;
                    case "roundness": shape.roundness = clamp01(value); return true;
                    case "rotation": shape.rotation = value; return true;
                    case "trimStart": shape.trimStart = value; return true;
                    case "trimEnd": shape.trimEnd = value; return true;
                    case "trimOffset": shape.trimOffset = value; return true;
                    case "repeaterCount": shape.repeaterCount = Math.max(0, Math.round(value)); return true;
                    case "repeaterOffsetX": shape.repeaterOffsetX = value; return true;
                    case "repeaterOffsetY": shape.repeaterOffsetY = value; return true;
                    case "repeaterScale": shape.repeaterScale = value; return true;
                    case "repeaterRotation": shape.repeaterRotation = value; return true;
                    case "repeaterOpacity": shape.repeaterOpacity = clamp01(value); return true;
                    default: return true;
                }
            }


            if (parts.length >= 5 && "p".equals(parts[2]))
            {
                CompShape shape = this.getShape(parts[1]);
                int index = parseInt(parts[3], -1);

                if (shape == null || index < 0 || index >= shape.points.size())
                {
                    return true;
                }

                CompShape.Point point = shape.points.get(index);

                switch (parts[4])
                {
                    case "x": point.x = value; return true;
                    case "y": point.y = value; return true;
                    case "inX": point.inX = value; return true;
                    case "inY": point.inY = value; return true;
                    case "outX": point.outX = value; return true;
                    case "outY": point.outY = value; return true;
                    default: return true;
                }
            }
        }

        return false;
    }

    private static int parseInt(String value, int fallback)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    public float scalar(String path, float fallback)
    {
        switch (path)
        {
            case "opacity": return this.opacity;
            case "x": return this.x;
            case "y": return this.y;
            case "z": return this.z;
            case "rotation": return this.rotation;
            case "rotationX": return this.rotationX;
            case "rotationY": return this.rotationY;
            case "scaleX": return this.scaleX;
            case "scaleY": return this.scaleY;
            case "scaleZ": return this.scaleZ;
            case "squash": return this.squash;
            case "cameraZ": return this.cameraZ;
            case "cameraTargetX": return this.cameraTargetX;
            case "cameraTargetY": return this.cameraTargetY;
            case "cameraTargetZ": return this.cameraTargetZ;
            case "cameraFov": return this.cameraFov;
            case "width": return this.width;
            case "height": return this.height;
            case "fontSize": return this.fontSize;
            case "tracking": return this.tracking;
            case "lineHeight": return this.lineHeight;
            case "strokeWidth": return this.strokeWidth;
            case "precompTimeOffset": return this.precompTimeOffset;
            case "precompTimeScale": return this.precompTimeScale;
            case "text/fontSize": return this.fontSize;
            case "text/tracking": return this.tracking;
            case "text/lineHeight": return this.lineHeight;
            case "text/strokeWidth": return this.strokeWidth;
            default: return this.nestedScalar(path, fallback);
        }
    }

    private float nestedScalar(String path, float fallback)
    {
        if (path.startsWith("mask/"))
        {
            String[] parts = path.split("/");

            if (parts.length >= 3)
            {
                CompMask mask = this.getMask(parts[1]);

                if (mask == null)
                {
                    return fallback;
                }

                if (parts.length == 3)
                {
                    switch (parts[2])
                    {
                        case "feather": return mask.feather;
                        case "opacity": return mask.opacity;
                        case "expansion": return mask.expansion;
                        default: return fallback;
                    }
                }

                if (parts.length >= 5 && "p".equals(parts[2]))
                {
                    int index = parseInt(parts[3], -1);

                    if (index < 0 || index >= mask.points.size())
                    {
                        return fallback;
                    }

                    CompMask.CompMaskPoint point = mask.points.get(index);

                    switch (parts[4])
                    {
                        case "x": return point.x;
                        case "y": return point.y;
                        case "inX": return point.inX;
                        case "inY": return point.inY;
                        case "outX": return point.outX;
                        case "outY": return point.outY;
                        default: return fallback;
                    }
                }
            }
        }

        if (path.startsWith("shape/"))
        {
            String[] parts = path.split("/");

            if (parts.length >= 4 && "c".equals(parts[2]))
            {
                CompShape shape = this.getShape(parts[1]);
                int index = parseInt(parts[3], -1);

                if (shape == null || index < 0)
                {
                    return fallback;
                }

                return shape.corner(index);
            }

            if (parts.length >= 3 && !"p".equals(parts[2]))
            {
                CompShape shape = this.getShape(parts[1]);

                if (shape == null)
                {
                    return fallback;
                }

                switch (parts[2])
                {
                    case "x": return shape.x;
                    case "y": return shape.y;
                    case "width": return shape.width;
                    case "height": return shape.height;
                    case "roundness": return shape.roundness;
                    case "rotation": return shape.rotation;
                    case "trimStart": return shape.trimStart;
                    case "trimEnd": return shape.trimEnd;
                    case "trimOffset": return shape.trimOffset;
                    case "repeaterCount": return shape.repeaterCount;
                    case "repeaterOffsetX": return shape.repeaterOffsetX;
                    case "repeaterOffsetY": return shape.repeaterOffsetY;
                    case "repeaterScale": return shape.repeaterScale;
                    case "repeaterRotation": return shape.repeaterRotation;
                    case "repeaterOpacity": return shape.repeaterOpacity;
                    default: return fallback;
                }
            }

            if (parts.length >= 5 && "p".equals(parts[2]))
            {
                CompShape shape = this.getShape(parts[1]);
                int index = parseInt(parts[3], -1);

                if (shape == null || index < 0 || index >= shape.points.size())
                {
                    return fallback;
                }

                CompShape.Point point = shape.points.get(index);

                switch (parts[4])
                {
                    case "x": return point.x;
                    case "y": return point.y;
                    case "inX": return point.inX;
                    case "inY": return point.inY;
                    case "outX": return point.outX;
                    case "outY": return point.outY;
                    default: return fallback;
                }
            }
        }

        return fallback;
    }

    public void setKeyframe(String path, float frame, float value)
    {
        KeyframeChannel<Float> channel = this.scalarChannel(path);

        channel.insert(frame, value);
        this.putScalar(path, value);
    }

    public void removeChannel(String path)
    {
        if ("opacity".equals(path))
        {
            clearChannel(this.opacityChannel());
            return;
        }

        KeyframeChannel<Float> channel = this.scalarChannels == null ? null : this.scalarChannels.remove(path);

        if (channel != null)
        {
            clearChannel(channel);
        }
    }

    public Transform defaultTransform()
    {
        Transform transform = new Transform();

        transform.translate.set(this.x, this.y, 0F);
        transform.scale.set(this.scaleX, this.scaleY, 1F);
        transform.rotate.set(0F, 0F, this.rotation);

        return transform;
    }

    public Transform transformAt(float frame)
    {
        /* Independent axes are the source of truth. The old shared Transform channel is only a
         * fallback for projects that have not been opened by the curve editor yet; migration
         * copies its handles and interpolation into the per-axis channels. */
        if (this.hasAnyTransformKey())
        {
            Transform transform = new Transform();

            transform.translate.set(
                this.axisAt(CompCurves.AXIS_X, frame, this.x),
                this.axisAt(CompCurves.AXIS_Y, frame, this.y),
                this.threeD ? this.axisAt(CompCurves.AXIS_Z, frame, this.z) : 0F
            );
            transform.rotate.set(
                this.threeD ? this.axisAt(CompCurves.AXIS_ROTATION_X, frame, this.rotationX) : 0F,
                this.threeD ? this.axisAt(CompCurves.AXIS_ROTATION_Y, frame, this.rotationY) : 0F,
                this.axisAt(CompCurves.AXIS_ROTATION, frame, this.rotation)
            );
            transform.scale.set(
                this.axisAt(CompCurves.AXIS_SCALE_X, frame, this.scaleX),
                this.axisAt(CompCurves.AXIS_SCALE_Y, frame, this.scaleY),
                this.threeD ? this.axisAt(CompCurves.AXIS_SCALE_Z, frame, this.scaleZ) : 1F
            );

            return transform;
        }

        KeyframeChannel<Transform> channel = this.transformChannel();

        if (channel.getKeyframes().isEmpty())
        {
            return this.defaultTransform();
        }

        /* Each component is evaluated on its own so per-keyframe bezier handles (the AE value
         * graph) actually shape position/scale/rotation. BBS's Transform.lerp applies one
         * interpolation to the whole vector and ignores the handles, which is why the compositor
         * evaluates the five components here instead of calling channel.interpolate().
         *
         * A fresh Transform is returned on purpose: TransformKeyframeFactory reuses one internal
         * instance for every interpolate() call, and mutating that would leak into other layers. */
        Transform transform = new Transform();

        transform.translate.set(
            CompCurves.transformValue(channel, CompCurves.AXIS_X, frame, this.x),
            CompCurves.transformValue(channel, CompCurves.AXIS_Y, frame, this.y),
            0F
        );
        transform.scale.set(
            CompCurves.transformValue(channel, CompCurves.AXIS_SCALE_X, frame, this.scaleX),
            CompCurves.transformValue(channel, CompCurves.AXIS_SCALE_Y, frame, this.scaleY),
            1F
        );
        transform.rotate.set(0F, 0F, CompCurves.transformValue(channel, CompCurves.AXIS_ROTATION, frame, this.rotation));

        return transform;
    }

    public float opacityFrame(float frame)
    {
        KeyframeChannel<Float> channel = this.opacityChannel();

        if (channel.getKeyframes().isEmpty())
        {
            return this.opacity;
        }

        Float value = channel.interpolate(frame, this.opacity);

        return value == null ? this.opacity : value;
    }

    /** Source time of a footage layer, in seconds. */
    public float sourceTime(float compTime)
    {
        if (this.freeze)
        {
            return this.freezeTime;
        }

        return (compTime - this.in + this.startTime) * this.timeScale;
    }

    /** Source time at an explicitly supplied frame; used by the renderer. */
    public float sourceTimeAt(float compTime, float frame)
    {
        if (this.timeRemap)
        {
            float base = this.freeze ? this.freezeTime : (compTime - this.in + this.startTime) * this.timeScale;
            return this.scalarAt("timeRemap", base, frame);
        }

        return this.freeze ? this.freezeTime : (compTime - this.in + this.startTime) * this.timeScale;
    }

    /* Track editing (timeline bars) */

    /**
     * Every keyframe that lives on this layer, across the transform, opacity and scalar channels.
     * The timeline needs this as one list so a bar move can carry the whole animation with it.
     */
    public List<Keyframe> allKeyframes()
    {
        List<Keyframe> keyframes = new ArrayList<>();

        if (this.transformChannel != null)
        {
            keyframes.addAll(this.transformChannel.getKeyframes());
        }
        if (this.opacityChannel != null)
        {
            keyframes.addAll(this.opacityChannel.getKeyframes());
        }
        if (this.scalarChannels != null)
        {
            for (KeyframeChannel<Float> channel : this.scalarChannels.values())
            {
                keyframes.addAll(channel.getKeyframes());
            }
        }

        return keyframes;
    }

    /** Shift every keyframe by a number of composition frames. Used when a track bar is moved. */
    public void shiftKeyframes(float deltaFrames)
    {
        if (deltaFrames == 0F)
        {
            return;
        }

        for (Keyframe keyframe : this.allKeyframes())
        {
            keyframe.setTick(keyframe.getTick() + deltaFrames, false);
        }

        if (this.transformChannel != null)
        {
            this.transformChannel.sort();
        }
        if (this.opacityChannel != null)
        {
            this.opacityChannel.sort();
        }
        if (this.scalarChannels != null)
        {
            for (KeyframeChannel<Float> channel : this.scalarChannels.values())
            {
                channel.sort();
            }
        }
    }

    /** Shift layer markers in composition seconds. */
    public void shiftMarkers(float deltaSeconds)
    {
        if (deltaSeconds == 0F)
        {
            return;
        }

        if (this.markers == null)
        {
            this.markers = new ArrayList<>();
        }

        for (CompMarker marker : this.markers)
        {
            marker.time = Math.max(0F, marker.time + deltaSeconds);
        }
    }

    /**
     * Move the whole bar to a new in point. The duration is preserved, keyframes and markers
     * travel with the bar, and the source offset stays put so the same frame of footage is shown.
     */
    public void moveTo(float newIn, float fps, float compDuration)
    {
        float frame = 1F / Math.max(1F, fps);
        float length = Math.max(frame, this.out - this.in);
        float maxIn = Math.max(0F, compDuration - length);

        newIn = Math.max(0F, Math.min(maxIn, newIn));

        float delta = newIn - this.in;

        if (Math.abs(delta) < 0.00001F)
        {
            return;
        }

        this.in = newIn;
        this.out = newIn + length;
        this.shiftKeyframes(delta * Math.max(1F, fps));
        this.shiftMarkers(delta);
    }

    /**
     * Trim the in point without sliding the footage. The source time at any given composition
     * time stays where it was, which is what makes trimming feel like Premiere/AE: you reveal or
     * hide frames, you do not slip the clip.
     */
    public void trimInTo(float newIn, float fps)
    {
        float frame = 1F / Math.max(1F, fps);
        float maxIn = this.out - frame;

        newIn = Math.max(0F, Math.min(maxIn, newIn));

        float delta = newIn - this.in;

        this.in = newIn;
        this.startTime += delta;
    }

    /** Trim the out point. The in point and the source timing are untouched. */
    public void trimOutTo(float newOut, float fps, float compDuration)
    {
        float frame = 1F / Math.max(1F, fps);

        this.out = Math.max(this.in + frame, Math.min(Math.max(compDuration, this.in + frame), newOut));
    }

    /**
     * Split the bar at a composition time. The right half is a full copy (effects, masks, keys)
     * whose source offset is advanced by the split distance, so footage continues seamlessly.
     * Returns null when the split point is outside the bar.
     */
    public CompLayer splitAt(float time, float fps, String newId, String newName)
    {
        float frame = 1F / Math.max(1F, fps);

        if (time <= this.in + frame || time >= this.out - frame)
        {
            return null;
        }

        CompLayer right = this.duplicate();

        right.id = newId;
        right.name = newName;
        right.in = time;
        right.startTime = this.startTime + (time - this.in);
        this.out = time;

        return right;
    }

    /** Evaluated text metrics for one frame. */
    public float fontSizeAt(float frame) { return Math.max(1F, this.scalarAt("text/fontSize", this.fontSize, frame)); }
    public float trackingAt(float frame) { return this.scalarAt("text/tracking", this.tracking, frame); }
    public float lineHeightAt(float frame) { return Math.max(0.1F, this.scalarAt("text/lineHeight", this.lineHeight, frame)); }
    public float textStrokeAt(float frame) { return Math.max(0F, this.scalarAt("text/strokeWidth", this.strokeWidth, frame)); }

    /** Returns a copy of a mask with every animatable mask value resolved at {@code frame}. */
    public CompMask maskAt(CompMask mask, float frame)
    {
        CompMask resolved = mask.copy();

        resolved.opacity = clamp01(this.scalarAt(maskPath(mask.id, "opacity"), mask.opacity, frame));
        resolved.feather = Math.max(0F, this.scalarAt(maskPath(mask.id, "feather"), mask.feather, frame));
        resolved.expansion = this.scalarAt(maskPath(mask.id, "expansion"), mask.expansion, frame);

        for (int i = 0; i < resolved.points.size(); i++)
        {
            CompMask.CompMaskPoint point = resolved.points.get(i);
            String base = "mask/" + mask.id + "/p/" + i + "/";

            point.x = this.scalarAt(base + "x", point.x, frame);
            point.y = this.scalarAt(base + "y", point.y, frame);
            point.inX = this.scalarAt(base + "inX", point.inX, frame);
            point.inY = this.scalarAt(base + "inY", point.inY, frame);
            point.outX = this.scalarAt(base + "outX", point.outX, frame);
            point.outY = this.scalarAt(base + "outY", point.outY, frame);
        }

        return resolved;
    }

    /** Returns a copy of a shape with every animatable shape value resolved at {@code frame}. */
    public CompShape shapeAt(CompShape shape, float frame)
    {
        CompShape resolved = shape.copy();
        String base = "shape/" + shape.id + "/";

        resolved.x = this.scalarAt(base + "x", shape.x, frame);
        resolved.y = this.scalarAt(base + "y", shape.y, frame);
        resolved.width = this.scalarAt(base + "width", shape.width, frame);
        resolved.height = this.scalarAt(base + "height", shape.height, frame);
        resolved.roundness = clamp01(this.scalarAt(base + "roundness", shape.roundness, frame));
        resolved.rotation = this.scalarAt(base + "rotation", shape.rotation, frame);
        resolved.fillGradientAngle = this.scalarAt(base + "fillGradientAngle", shape.fillGradientAngle, frame);
        resolved.trimStart = this.scalarAt(base + "trimStart", shape.trimStart, frame);
        resolved.trimEnd = this.scalarAt(base + "trimEnd", shape.trimEnd, frame);
        resolved.trimOffset = this.scalarAt(base + "trimOffset", shape.trimOffset, frame);
        resolved.repeaterCount = Math.max(0, Math.round(this.scalarAt(base + "repeaterCount", shape.repeaterCount, frame)));
        resolved.repeaterOffsetX = this.scalarAt(base + "repeaterOffsetX", shape.repeaterOffsetX, frame);
        resolved.repeaterOffsetY = this.scalarAt(base + "repeaterOffsetY", shape.repeaterOffsetY, frame);
        resolved.repeaterScale = this.scalarAt(base + "repeaterScale", shape.repeaterScale, frame);
        resolved.repeaterRotation = this.scalarAt(base + "repeaterRotation", shape.repeaterRotation, frame);
        resolved.repeaterOpacity = clamp01(this.scalarAt(base + "repeaterOpacity", shape.repeaterOpacity, frame));

        /* Always resolve corners: a fresh shape has no corner list yet, and a keyframed corner
         * radius must land there once the channel exists. */
        resolved.ensureCorners();

        for (int i = 0; i < resolved.corners.size(); i++)
        {
            String cornerPath = base + "c/" + i;
            float fallbackCorner = shape.corner(i);

            resolved.corners.set(i, Math.max(0F, this.scalarAt(cornerPath, fallbackCorner, frame)));
        }

        for (int i = 0; i < resolved.points.size(); i++)
        {
            CompShape.Point point = resolved.points.get(i);
            String pointBase = base + "p/" + i + "/";

            point.x = this.scalarAt(pointBase + "x", point.x, frame);
            point.y = this.scalarAt(pointBase + "y", point.y, frame);
            point.inX = this.scalarAt(pointBase + "inX", point.inX, frame);
            point.inY = this.scalarAt(pointBase + "inY", point.inY, frame);
            point.outX = this.scalarAt(pointBase + "outX", point.outX, frame);
            point.outY = this.scalarAt(pointBase + "outY", point.outY, frame);
        }

        return resolved;
    }

    public static String maskPath(String maskId, String property)
    {
        return "mask/" + maskId + "/" + property;
    }

    public static String shapePath(String shapeId, String property)
    {
        return "shape/" + shapeId + "/" + property;
    }

    public boolean activeAt(float compTime)
    {
        return this.visible && compTime >= this.in && compTime < this.out;
    }

    /** True for a camera layer. Cameras are views: they never produce pixels. */
    public boolean isCamera()
    {
        return "camera".equals(this.type);
    }

    /**
     * Eye position of a camera in composition pixels, at {@code frame}.
     *
     * <p>The single source of truth for "where is the camera". The editor's gizmo, its hit test and
     * its drag all read this, and {@code CompCamera.of()} derives the view from the very same
     * transform, so the icon, the drag and the picture can never disagree.</p>
     *
     * <p>It exists because the previous call sites used {@code scalarAt("x", layer.x, frame)} — a
     * <em>scalar channel</em> that nothing writes for position — so an animated camera left its
     * gizmo parked at the base value while the render moved. A camera's position lives in the
     * transform channel, like every other layer's.</p>
     */
    public org.joml.Vector2f cameraEye(float frame)
    {
        Transform transform = this.transformAt(frame);

        return new org.joml.Vector2f(transform.translate.x, transform.translate.y);
    }

    /** Point of interest of a camera, honouring its scalar channels. */
    public org.joml.Vector3f cameraTarget(float frame)
    {
        return new org.joml.Vector3f(
            this.scalarAt("cameraTargetX", this.cameraTargetX, frame),
            this.scalarAt("cameraTargetY", this.cameraTargetY, frame),
            this.scalarAt("cameraTargetZ", this.cameraTargetZ, frame)
        );
    }

    public CompMask getMask(String maskId)
    {
        for (CompMask mask : this.masks)
        {
            if (mask.id.equals(maskId))
            {
                return mask;
            }
        }

        return null;
    }

    public CompShape getShape(String shapeId)
    {
        for (CompShape shape : this.shapes)
        {
            if (shape.id.equals(shapeId))
            {
                return shape;
            }
        }

        return null;
    }

    public CompEffect getEffect(String effectId)
    {
        for (CompEffect effect : this.effects)
        {
            if (effect.id.equals(effectId))
            {
                return effect;
            }
        }

        return null;
    }

    public float effectParam(String effectId, String parameter, float fallback, float frame)
    {
        return this.scalarAt(effectPath(effectId, parameter), fallback, frame);
    }

    /** Resolves animated effect parameters into a temporary effect for this frame. */
    public CompEffect resolveEffect(CompEffect effect, float frame)
    {
        if (effect == null)
        {
            return null;
        }

        CompEffect resolved = effect.copy();

        for (Map.Entry<String, Double> entry : resolved.params.entrySet())
        {
            float fallback = entry.getValue() == null ? 0F : entry.getValue().floatValue();

            entry.setValue((double) this.scalarAt(effectPath(effect.id, entry.getKey()), fallback, frame));
        }

        return resolved;
    }

    public static String effectPath(String effectId, String parameter)
    {
        return "fx/" + effectId + "/" + parameter;
    }

    private static void clearChannel(KeyframeChannel<?> channel)
    {
        while (!channel.getKeyframes().isEmpty())
        {
            channel.remove(channel.getKeyframes().size() - 1);
        }
    }

    public CompLayer duplicate()
    {
        this.saveTracks();

        Gson gson = CompStore.gson();
        CompLayer copy = gson.fromJson(gson.toJson(this), CompLayer.class);

        if (copy == null)
        {
            copy = new CompLayer();
        }

        copy.loadTracks();

        return copy;
    }

    /* Replay entity matte helpers */

    /** True for the depth-keyed replay entity layer. */
    public boolean isEntityLayer()
    {
        return "replay".equals(this.type);
    }

    /**
     * The replay ids kept in selected scope, as an ordered set.
     *
     * <p>Parsing on every call keeps the stored form a plain string: a project file stays diffable
     * and a hand-edited id list cannot desynchronise a cached set from the JSON.</p>
     */
    public java.util.LinkedHashSet<String> entityIdSet()
    {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();

        if (this.entityIds == null || this.entityIds.isBlank())
        {
            return ids;
        }

        for (String part : this.entityIds.split(","))
        {
            String id = part.trim();

            if (!id.isEmpty())
            {
                ids.add(id);
            }
        }

        return ids;
    }

    public boolean entityKeeps(String replayId)
    {
        if (replayId == null || replayId.isEmpty())
        {
            return false;
        }

        return this.entityScope == ENTITY_SCOPE_ALL || this.entityIdSet().contains(replayId);
    }

    /** Adds or removes one replay id. Keeps the stored order stable for untouched ids. */
    public void entitySetKept(String replayId, boolean kept)
    {
        if (replayId == null || replayId.isEmpty())
        {
            return;
        }

        java.util.LinkedHashSet<String> ids = this.entityIdSet();

        if (kept)
        {
            ids.add(replayId);
        }
        else
        {
            ids.remove(replayId);
        }

        this.entityIds = String.join(",", ids);
    }

    public void entitySelectAll(java.util.Collection<String> ids)
    {
        this.entityIds = ids == null ? "" : String.join(",", ids);
    }

    /* BBS-data serialization */

    private static void ensureFactories()
    {
        if (!KeyframeFactories.FACTORIES.containsKey("transform") || !KeyframeFactories.FACTORIES.containsKey("float"))
        {
            KeyframeFactories.setup();
        }
    }

    public void saveTracks()
    {
        ensureFactories();
        this.ensureIndependentAxes();

        if (this.hasAnyTransformKey())
        {
            this.transformData = "";
        }

        this.transformData = this.transformChannel == null || this.transformChannel.getKeyframes().isEmpty()
            ? ""
            : DataToString.toString(this.transformChannel.toData(), false);
        this.opacityData = this.opacityChannel == null || this.opacityChannel.getKeyframes().isEmpty()
            ? ""
            : DataToString.toString(this.opacityChannel.toData(), false);

        if (this.channels == null)
        {
            this.channels = new LinkedHashMap<>();
        }

        this.channels.clear();

        if (this.scalarChannels != null)
        {
            for (Map.Entry<String, KeyframeChannel<Float>> entry : this.scalarChannels.entrySet())
            {
                if (!entry.getValue().getKeyframes().isEmpty() && !"opacity".equals(entry.getKey()))
                {
                    this.channels.put(entry.getKey(), DataToString.toString(entry.getValue().toData(), false));
                }
            }
        }
    }

    public void loadTracks()
    {
        ensureFactories();

        this.transformChannel = new KeyframeChannel<>("transform", KeyframeFactories.TRANSFORM);
        this.opacityChannel = new KeyframeChannel<>("opacity", KeyframeFactories.FLOAT);
        this.scalarChannels = new LinkedHashMap<>();

        if (this.transformData != null && !this.transformData.isBlank())
        {
            this.transformChannel.fromData(DataToString.mapFromString(this.transformData));
        }

        if (this.opacityData != null && !this.opacityData.isBlank())
        {
            this.opacityChannel.fromData(DataToString.mapFromString(this.opacityData));
        }

        if (this.channels != null)
        {
            for (Map.Entry<String, String> entry : this.channels.entrySet())
            {
                String path = entry.getKey();

                if (path == null || path.isBlank() || entry.getValue() == null || entry.getValue().isBlank())
                {
                    continue;
                }

                KeyframeChannel<Float> channel = new KeyframeChannel<>(path, KeyframeFactories.FLOAT);

                channel.fromData(DataToString.mapFromString(entry.getValue()));
                this.scalarChannels.put(path, channel);
            }
        }

        if (this.masks == null) this.masks = new ArrayList<>();
        if (this.effects == null) this.effects = new ArrayList<>();
        if (this.styles == null) this.styles = new ArrayList<>();
        if (this.shapes == null) this.shapes = new ArrayList<>();
        if (this.textAnimators == null) this.textAnimators = new ArrayList<>();
        if (this.markers == null) this.markers = new ArrayList<>();
        if (this.binding == null) this.binding = new CompBinding();

        this.ensureIndependentAxes();
    }

    public void ensureInitialized()
    {
        if (this.channels == null) this.channels = new LinkedHashMap<>();
        if (this.masks == null) this.masks = new ArrayList<>();
        if (this.effects == null) this.effects = new ArrayList<>();
        if (this.styles == null) this.styles = new ArrayList<>();
        if (this.shapes == null) this.shapes = new ArrayList<>();
        if (this.textAnimators == null) this.textAnimators = new ArrayList<>();
        if (this.markers == null) this.markers = new ArrayList<>();
        if (this.binding == null) this.binding = new CompBinding();

        /* A hand-edited or older project can contain nulls/partial objects; normalise them here so
         * the renderer and the inspector never have to null-check every nested field. */
        this.effects.removeIf(java.util.Objects::isNull);
        this.styles.removeIf(java.util.Objects::isNull);
        this.textAnimators.removeIf(java.util.Objects::isNull);
        this.markers.removeIf(java.util.Objects::isNull);

        for (CompEffect effect : this.effects)
        {
            if (effect.params == null) effect.params = new LinkedHashMap<>();
            if (effect.colors == null) effect.colors = new LinkedHashMap<>();
        }

        for (CompMask mask : this.masks)
        {
            if (mask != null && mask.points == null)
            {
                mask.points = new ArrayList<>();
            }
        }

        this.masks.removeIf(java.util.Objects::isNull);

        for (CompShape shape : this.shapes)
        {
            if (shape != null && shape.points == null)
            {
                shape.points = new ArrayList<>();
            }
        }

        this.shapes.removeIf(java.util.Objects::isNull);

        if (this.transformChannel == null)
        {
            this.transformChannel = new KeyframeChannel<>("transform", KeyframeFactories.TRANSFORM);

            if (this.transformData != null && !this.transformData.isBlank())
            {
                this.transformChannel.fromData(DataToString.mapFromString(this.transformData));
            }
        }

        if (this.opacityChannel == null)
        {
            this.opacityChannel = new KeyframeChannel<>("opacity", KeyframeFactories.FLOAT);

            if (this.opacityData != null && !this.opacityData.isBlank())
            {
                this.opacityChannel.fromData(DataToString.mapFromString(this.opacityData));
            }
        }

        if (this.scalarChannels == null)
        {
            this.scalarChannels = new LinkedHashMap<>();
        }

        if (this.scalarChannels.isEmpty() && this.channels != null && !this.channels.isEmpty())
        {
            for (Map.Entry<String, String> entry : this.channels.entrySet())
            {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank())
                {
                    continue;
                }

                KeyframeChannel<Float> channel = new KeyframeChannel<>(entry.getKey(), KeyframeFactories.FLOAT);

                channel.fromData(DataToString.mapFromString(entry.getValue()));
                this.scalarChannels.put(entry.getKey(), channel);
            }
        }

        this.ensureIndependentAxes();
    }

    public static float clamp01(float value)
    {
        return Math.max(0F, Math.min(1F, value));
    }
}
