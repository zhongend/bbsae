package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * One animatable property of a layer, seen as a curve.
 *
 * <p>The curve editor must not care whether a value lives in the BBS {@code Transform} channel, in
 * the opacity channel or in one of the layer's scalar channels. This adapter exposes all three as
 * "a channel with keys and a numeric value", which is what keeps the graph math in one place and
 * the UI free of special cases.</p>
 *
 * <p>Position, rotation and scale are five separate curves derived from the same transform channel
 * (BBS stores one Transform per key). That is deliberate: AE's value graph shows them as separate
 * curves too, and each one needs its own bezier handles.</p>
 */
public final class CompCurveChannel
{
    /** Snapshot of a keyframe for hit testing and drawing. */
    public static final class Key
    {
        public final Keyframe<?> raw;
        public final float tick;
        public final float value;

        Key(Keyframe<?> raw, float tick, float value)
        {
            this.raw = raw;
            this.tick = tick;
            this.value = value;
        }
    }

    public final String id;
    public final String label;
    public final String group;
    public final int color;
    public final boolean transform;
    public final int axis;
    public final CompLayer layer;
    public final String path;

    private CompCurveChannel(CompLayer layer, String id, String label, String group, int color, boolean transform, int axis, String path)
    {
        this.layer = layer;
        this.id = id;
        this.label = label;
        this.group = group;
        this.color = color;
        this.transform = transform;
        this.axis = axis;
        this.path = path;
    }

    /* Factories */

    /** Every curve a layer can show, in AE's property order. */
    public static List<CompCurveChannel> of(CompLayer layer)
    {
        List<CompCurveChannel> channels = new ArrayList<>();

        if (layer == null)
        {
            return channels;
        }

        String group = "变换";

        channels.add(new CompCurveChannel(layer, "transform.x", "位置 X", group, 0xFFE05C5C, true, CompCurves.AXIS_X, null));
        channels.add(new CompCurveChannel(layer, "transform.y", "位置 Y", group, 0xFF67C46B, true, CompCurves.AXIS_Y, null));
        channels.add(new CompCurveChannel(layer, "transform.rotation", "旋转", group, 0xFF5B9BD5, true, CompCurves.AXIS_ROTATION, null));
        channels.add(new CompCurveChannel(layer, "transform.scaleX", "缩放 X", group, 0xFFE0A93B, true, CompCurves.AXIS_SCALE_X, null));
        channels.add(new CompCurveChannel(layer, "transform.scaleY", "缩放 Y", group, 0xFFC77DD0, true, CompCurves.AXIS_SCALE_Y, null));
        channels.add(new CompCurveChannel(layer, "opacity", "不透明度", "属性", 0xFF9AA6B8, false, -1, "opacity"));

        int index = 0;

        for (String scalarPath : layer.animatedPaths())
        {
            if (scalarPath == null || scalarPath.isBlank())
            {
                continue;
            }

            channels.add(new CompCurveChannel(
                layer,
                "scalar:" + scalarPath,
                displayName(layer, scalarPath),
                groupOf(scalarPath),
                palette(index++),
                false,
                -1,
                scalarPath
            ));
        }

        return channels;
    }

    private static final int[] PALETTE =
    {
        0xFF7FC4FF, 0xFFFFB86B, 0xFF8CE0A6, 0xFFD9A0FF, 0xFFFF9DB0,
        0xFF6BE0D6, 0xFFF2E06B, 0xFFA9B6FF, 0xFFE0C08C, 0xFF9EE0E0
    };

    private static int palette(int index)
    {
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    private static String groupOf(String path)
    {
        if (path.startsWith("fx/")) return "效果";
        if (path.startsWith("mask/")) return "蒙版";
        if (path.startsWith("shape/")) return "形状";
        if (path.startsWith("text/")) return "文字";
        if ("timeRemap".equals(path)) return "时间";

        return "属性";
    }

    /**
     * Human label for a scalar path. The path is the stable id; the label is what the artist reads,
     * so effects and shape ids are resolved back to their type names instead of showing raw ids.
     */
    public static String displayName(CompLayer layer, String path)
    {
        try
        {
            String[] parts = path.split("/");

            if ("fx".equals(parts[0]) && parts.length >= 3)
            {
                CompEffect effect = layer.getEffect(parts[1]);
                String type = effect == null ? "fx" : effect.type;
                String effectName = CompEffectRegistry.displayName(type);
                String paramLabel = parts[parts.length - 1];
                CompEffectRegistry.Definition definition = CompEffectRegistry.get(type);

                if (definition != null)
                {
                    for (CompEffectRegistry.Param param : definition.params)
                    {
                        if (param.key.equals(paramLabel))
                        {
                            paramLabel = param.label;
                            break;
                        }
                    }
                }

                return effectName + " · " + paramLabel;
            }

            if ("mask".equals(parts[0]) && parts.length >= 3)
            {
                return "蒙版 " + parts[1] + " · " + parts[2];
            }

            if ("shape".equals(parts[0]) && parts.length >= 3)
            {
                String property = parts[2];

                if ("p".equals(property) && parts.length >= 5)
                {
                    return "路径 " + parts[1] + " · 点" + (Integer.parseInt(parts[3]) + 1) + " " + parts[4];
                }

                if ("c".equals(property) && parts.length >= 4)
                {
                    return "角 " + parts[1] + " · 角" + (Integer.parseInt(parts[3]) + 1);
                }

                return "形状 " + parts[1] + " · " + property;
            }

            if (path.startsWith("text/"))
            {
                return "文字 · " + path.substring(5);
            }

            switch (path)
            {
                case "timeRemap": return "时间重映射";
                case "strokeWidth": return "描边宽度";
                case "fontSize": return "字号";
                case "tracking": return "字距";
                case "lineHeight": return "行距";
                case "cameraZ": return "摄像机 Z";
                case "cameraTargetZ": return "目标点 Z";
                case "cameraFov": return "视场角";
                default: return path;
            }
        }
        catch (RuntimeException e)
        {
            return path;
        }
    }

    /* Data access */

    @SuppressWarnings("unchecked")
    public KeyframeChannel<?> raw()
    {
        if (this.transform)
        {
            return this.layer.transformChannel();
        }

        if ("opacity".equals(this.path))
        {
            return this.layer.opacityChannel();
        }

        return this.layer.scalarChannel(this.path);
    }

    public float valueAt(float frame)
    {
        if (this.transform)
        {
            return CompCurves.transformValue(
                this.layer.transformChannel(),
                this.axis,
                frame,
                CompCurves.component(this.layer.defaultTransform(), this.axis)
            );
        }

        if ("opacity".equals(this.path))
        {
            return this.layer.opacityFrame(frame);
        }

        return this.layer.scalarAt(this.path, this.layer.scalar(this.path, 0F), frame);
    }

    public boolean hasKeys()
    {
        return !this.raw().getKeyframes().isEmpty();
    }

    @SuppressWarnings("rawtypes")
    public List<Key> keys()
    {
        KeyframeChannel<?> channel = this.raw();
        List<Key> keys = new ArrayList<>();

        for (Object entry : channel.getKeyframes())
        {
            Keyframe key = (Keyframe) entry;

            keys.add(new Key(key, key.getTick(), this.numericValue(key)));
        }

        return keys;
    }

    @SuppressWarnings("rawtypes")
    public float numericValue(Keyframe<?> key)
    {
        if (this.transform)
        {
            return CompCurves.component((Transform) key.getValue(), this.axis);
        }

        Object value = key.getValue();

        if (value instanceof Number number)
        {
            return number.floatValue();
        }

        return 0F;
    }

    public float defaultValue()
    {
        if (this.transform)
        {
            return CompCurves.component(this.layer.defaultTransform(), this.axis);
        }

        if ("opacity".equals(this.path))
        {
            return this.layer.opacity;
        }

        return this.layer.scalar(this.path, 0F);
    }

    /** Insert (or overwrite) a key at a tick. Returns the keyframe that now holds the value. */
    @SuppressWarnings("unchecked")
    public Keyframe<?> insert(float tick, float value)
    {
        if (this.transform)
        {
            KeyframeChannel<Transform> channel = this.layer.transformChannel();
            Transform transform = channel.interpolate(tick, this.layer.defaultTransform()).copy();

            CompCurves.setComponent(transform, this.axis, value);

            /* insertInheriting copies the left key's interpolation, style and handles, which is
             * what AE does: a key dropped onto an eased curve keeps the curve's shape. */
            return channel.insertInheriting(tick, transform);
        }

        KeyframeChannel<Float> channel = (KeyframeChannel<Float>) this.raw();

        channel.insert(tick, value);
        this.layer.putScalar(this.path, value);

        return findKey(channel, tick);
    }

    @SuppressWarnings("unchecked")
    public void setKeyValue(Keyframe<?> key, float value)
    {
        if (this.transform)
        {
            CompCurves.setComponent((Transform) key.getValue(), this.axis, value);
            this.layer.transformChannel().sort();
            this.layer.putScalar(this.pathForField(), value);
            return;
        }

        ((Keyframe<Float>) key).setValue(value);
        this.layer.putScalar(this.path, value);
    }

    /** Field name used to keep the layer's base value in sync with a transform key. */
    private String pathForField()
    {
        switch (this.axis)
        {
            case CompCurves.AXIS_Y: return "y";
            case CompCurves.AXIS_ROTATION: return "rotation";
            case CompCurves.AXIS_SCALE_X: return "scaleX";
            case CompCurves.AXIS_SCALE_Y: return "scaleY";
            default: return "x";
        }
    }

    public void remove(Keyframe<?> key)
    {
        KeyframeChannel<?> channel = this.raw();
        List<?> keys = channel.getKeyframes();

        for (int i = 0; i < keys.size(); i++)
        {
            if (keys.get(i) == key)
            {
                channel.remove(i);

                return;
            }
        }
    }

    /** True when this channel owns that exact keyframe object. */
    public boolean owns(Keyframe<?> key)
    {
        for (Object entry : this.raw().getKeyframes())
        {
            if (entry == key)
            {
                return true;
            }
        }

        return false;
    }

    /** Clears every key of this curve (AE's "remove all keyframes"). */
    public void removeAll()
    {
        this.raw().removeAll();
    }

    @SuppressWarnings("rawtypes")
    private static Keyframe findKey(KeyframeChannel channel, float tick)
    {
        for (Object entry : channel.getKeyframes())
        {
            Keyframe key = (Keyframe) entry;

            if (Math.abs(key.getTick() - tick) < 0.0001F)
            {
                return key;
            }
        }

        return null;
    }

    /** Keys grouped by their label, for the channel tree in the editor. */
    public static LinkedHashMap<String, List<CompCurveChannel>> groups(List<CompCurveChannel> channels)
    {
        LinkedHashMap<String, List<CompCurveChannel>> groups = new LinkedHashMap<>();

        for (CompCurveChannel channel : channels)
        {
            groups.computeIfAbsent(channel.group, (key) -> new ArrayList<>()).add(channel);
        }

        return groups;
    }

    public String unit()
    {
        if (this.transform)
        {
            return this.axis == CompCurves.AXIS_ROTATION ? "°" : (this.axis == CompCurves.AXIS_SCALE_X || this.axis == CompCurves.AXIS_SCALE_Y ? "×" : "px");
        }

        if ("opacity".equals(this.path)) return "%";

        return "";
    }

    public String format(float value)
    {
        if (this.transform && (this.axis == CompCurves.AXIS_SCALE_X || this.axis == CompCurves.AXIS_SCALE_Y))
        {
            return String.format(Locale.ROOT, "%.1f%%", value * 100F);
        }

        if ("opacity".equals(this.path))
        {
            return String.format(Locale.ROOT, "%.0f%%", value * 100F);
        }

        return String.format(Locale.ROOT, "%.2f%s", value, this.unit());
    }
}
