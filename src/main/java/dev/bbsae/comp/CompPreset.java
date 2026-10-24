package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable preset: animation, effect stack, layer styles, a shape group, or a whole layer look.
 *
 * <p>AE calls the first kind an "Animation Preset" and the rest "Effects &amp; Presets". Both are the
 * same idea — a named, reusable bundle of property values — so the compositor stores them in one
 * list with a kind tag. The payload is JSON on purpose: presets survive project reloads, can be
 * copied between projects, and a preset saved by an older build still applies.</p>
 */
public class CompPreset
{
    public static final String MOTION = "motion";
    public static final String EFFECT = "effect";
    public static final String STYLE = "style";
    public static final String SHAPE = "shape";
    public static final String LAYER = "layer";

    public String id = "preset";
    public String name = "Preset";
    public String kind = MOTION;
    public String description = "";
    public boolean builtin = false;
    public int accent = 0xFF55AAFF;
    public String data = "";

    public CompPreset()
    {}

    public CompPreset(String id, String name, String kind, String description, int accent, String data)
    {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.description = description;
        this.accent = accent;
        this.data = data;
    }

    public static CompPreset of(String id, String name, String kind, String description, int accent, CompPresetPayload payload, boolean builtin)
    {
        CompPreset preset = new CompPreset(id, name, kind, description, accent, CompStore.gson().toJson(payload));

        preset.builtin = builtin;

        return preset;
    }

    public CompPresetPayload payload()
    {
        if (this.data == null || this.data.isBlank())
        {
            return null;
        }

        try
        {
            return CompStore.gson().fromJson(this.data, CompPresetPayload.class);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Captures a layer into a preset of the given kind. Returns null when the layer is empty. */
    public static CompPreset capture(String id, String name, String kind, CompLayer layer, float fps)
    {
        if (layer == null)
        {
            return null;
        }

        CompPresetPayload payload = new CompPresetPayload();

        payload.sourceIn = layer.in;
        payload.sourceFps = Math.max(1F, fps);
        payload.layerType = layer.type;
        payload.color = layer.color;
        payload.strokeColor = layer.strokeColor;
        payload.strokeWidth = layer.strokeWidth;

        layer.saveTracks();

        boolean motion = MOTION.equals(kind) || LAYER.equals(kind);
        boolean effects = EFFECT.equals(kind) || LAYER.equals(kind);
        boolean styles = STYLE.equals(kind) || LAYER.equals(kind);
        boolean shapes = SHAPE.equals(kind) || LAYER.equals(kind);

        if (motion)
        {
            payload.transformData = layer.transformData;
            payload.opacityData = layer.opacityData;

            if (layer.channels != null)
            {
                payload.channels.putAll(layer.channels);
            }
        }

        if (effects)
        {
            for (CompEffect effect : layer.effects)
            {
                payload.effects.add(effect.copy());
            }
        }

        if (styles)
        {
            for (CompStyle style : layer.styles)
            {
                payload.styles.add(style);
            }
        }

        if (shapes)
        {
            for (CompShape shape : layer.shapes)
            {
                payload.shapes.add(shape);
            }
        }

        if (!motion && payload.effects.isEmpty() && payload.styles.isEmpty() && payload.shapes.isEmpty())
        {
            return null;
        }

        int accent = switch (kind)
        {
            case EFFECT -> 0xFF7FC4FF;
            case STYLE -> 0xFFFFB86B;
            case SHAPE -> 0xFF8CE0A6;
            case LAYER -> 0xFFFF9DB0;
            default -> 0xFFD9A0FF;
        };

        return of(id, name, kind, describe(kind, payload), accent, payload, false);
    }

    private static String describe(String kind, CompPresetPayload payload)
    {
        List<String> parts = new ArrayList<>();

        if (!payload.effects.isEmpty()) parts.add(payload.effects.size() + " 个效果");
        if (!payload.styles.isEmpty()) parts.add(payload.styles.size() + " 个样式");
        if (!payload.shapes.isEmpty()) parts.add(payload.shapes.size() + " 个形状");

        if (!payload.transformData.isBlank() || !payload.opacityData.isBlank() || !payload.channels.isEmpty())
        {
            parts.add("关键帧动画");
        }

        return parts.isEmpty() ? kind : String.join(" · ", parts);
    }

    public boolean applyTo(CompLayer target, float fps)
    {
        CompPresetPayload payload = this.payload();

        if (payload == null || target == null)
        {
            return false;
        }

        boolean motion = MOTION.equals(this.kind) || LAYER.equals(this.kind);
        boolean effects = EFFECT.equals(this.kind) || LAYER.equals(this.kind);
        boolean styles = STYLE.equals(this.kind) || LAYER.equals(this.kind);
        boolean shapes = SHAPE.equals(this.kind) || LAYER.equals(this.kind);

        payload.applyTo(target, motion, effects, styles, shapes, LAYER.equals(this.kind), fps);

        return true;
    }

    public String kindLabel()
    {
        return switch (this.kind)
        {
            case EFFECT -> "效果";
            case STYLE -> "样式";
            case SHAPE -> "形状";
            case LAYER -> "整套";
            default -> "动画";
        };
    }
}
