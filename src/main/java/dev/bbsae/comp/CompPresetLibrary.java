package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in presets.
 *
 * <p>They are constructed from the same {@link CompPresetPayload} a user preset uses, then
 * serialized once. That guarantees a built-in and a user preset go through exactly the same apply
 * path — there is no second, "special" code path that can rot.</p>
 */
public final class CompPresetLibrary
{
    private CompPresetLibrary()
    {}

    public static List<CompPreset> builtIns()
    {
        List<CompPreset> presets = new ArrayList<>();

        presets.add(cine());
        presets.add(softPortrait());
        presets.add(neon());
        presets.add(retroVhs());
        presets.add(glitch());
        presets.add(cardShadow());
        presets.add(glowStyle());
        presets.add(cardShape());
        presets.add(badgeShape());
        presets.add(ringShape());

        return presets;
    }

    private static CompEffect effect(String type, int serial, Object... params)
    {
        CompEffect effect = CompEffectRegistry.create(type, serial);

        for (int i = 0; i + 1 < params.length; i += 2)
        {
            String key = String.valueOf(params[i]);
            Object value = params[i + 1];

            if (value instanceof Integer color && key.startsWith("color"))
            {
                effect.putColor(key, color);
            }
            else if (value instanceof Number number)
            {
                effect.put(key, number.doubleValue());
            }
        }

        return effect;
    }

    private static CompPreset of(String id, String name, String kind, String description, int accent, CompPresetPayload payload)
    {
        return CompPreset.of("builtin." + id, name, kind, description, accent, payload, true);
    }

    private static CompPreset cine()
    {
        CompPresetPayload payload = new CompPresetPayload();

        payload.effects.add(effect("color_grade", 1,
            "exposure", 0.05D, "contrast", 0.12D, "saturation", 1.04D, "temperature", -0.1D,
            "tint", 0.04D, "fade", 0.06D, "curve_shadow", -0.02D, "curve_highlight", 0.03D));
        payload.effects.add(effect("curves", 2, "amount", 0.7D, "shadow", -0.04D, "midtone", 0.01D, "highlight", 0.05D));
        payload.effects.add(effect("vignette", 3, "amount", 0.3D, "radius", 0.8D, "softness", 0.5D));
        payload.effects.add(effect("grain", 4, "amount", 0.05D, "size", 2D));

        return of("cine", "电影青橙", CompPreset.EFFECT, "冷阴影 + 暖高光，轻微暗角与颗粒", 0xFF7FC4FF, payload);
    }

    private static CompPreset softPortrait()
    {
        CompPresetPayload payload = new CompPresetPayload();

        payload.effects.add(effect("glow", 1, "radius", 34D, "intensity", 0.75D));
        payload.effects.add(effect("brightness_contrast", 2, "brightness", 0.04D, "contrast", -0.06D));
        payload.effects.add(effect("hue_saturation", 3, "saturation", -0.06D, "lightness", 0.03D));

        return of("soft", "柔光人像", CompPreset.EFFECT, "低对比 + 柔和辉光，适合人物或标题", 0xFFFFB86B, payload);
    }

    private static CompPreset neon()
    {
        CompPresetPayload payload = new CompPresetPayload();

        payload.effects.add(effect("glow", 1, "radius", 60D, "intensity", 1.7D, "color", 0xFF55D6FF));
        payload.effects.add(effect("outer_glow", 2, "radius", 30D, "intensity", 1.1D, "opacity", 0.85D, "color", 0xFF55AAFF));
        payload.effects.add(effect("color_grade", 3, "saturation", 1.25D, "contrast", 0.1D, "vibrance", 0.15D));

        return of("neon", "霓虹发光", CompPreset.EFFECT, "强辉光 + 高饱和，赛博/夜景质感", 0xFFD9A0FF, payload);
    }

    private static CompPreset retroVhs()
    {
        CompPresetPayload payload = new CompPresetPayload();

        payload.effects.add(effect("vhs", 1, "amount", 0.45D, "speed", 7D, "noise", 0.18D));
        payload.effects.add(effect("scanlines", 2, "opacity", 0.35D, "spacing", 4D));
        payload.effects.add(effect("color_grade", 3, "fade", 0.12D, "contrast", -0.05D, "temperature", 0.12D));

        return of("vhs", "复古 VHS", CompPreset.EFFECT, "录像带抖动 + 扫描线 + 褪色", 0xFFFF9DB0, payload);
    }

    private static CompPreset glitch()
    {
        CompPresetPayload payload = new CompPresetPayload();

        payload.effects.add(effect("glitch", 1, "amount", 26D, "bands", 30D, "speed", 9D));
        payload.effects.add(effect("scanlines", 2, "opacity", 0.2D, "spacing", 3D));

        return of("glitch", "故障闪断", CompPreset.EFFECT, "条带位移 + 细扫描线，适合转场", 0xFF6BE0D6, payload);
    }

    private static CompPreset cardShadow()
    {
        CompPresetPayload payload = new CompPresetPayload();

        payload.effects.add(effect("drop_shadow", 1, "distance", 18D, "angle", 90D, "softness", 22D, "opacity", 0.5D));
        payload.effects.add(effect("stroke", 2, "width", 1.5D, "opacity", 0.5D, "color", 0x33FFFFFF));

        return of("card_shadow", "卡片投影", CompPreset.EFFECT, "柔和投影 + 1px 高光描边", 0xFF8CE0A6, payload);
    }

    private static CompPreset glowStyle()
    {
        CompPresetPayload payload = new CompPresetPayload();
        CompStyle glow = new CompStyle(CompStyle.OUTER_GLOW);

        glow.size = 18F;
        glow.opacity = 0.7F;
        glow.color = 0xFF55AAFF;
        glow.blur = 12F;

        CompStyle stroke = new CompStyle(CompStyle.STROKE);

        stroke.size = 2F;
        stroke.opacity = 1F;
        stroke.color = 0xFFFFFFFF;

        payload.styles.add(glow);
        payload.styles.add(stroke);

        return of("glow_style", "外发光 + 白描边", CompPreset.STYLE, "图层样式：外发光与描边", 0xFFFFB86B, payload);
    }

    private static CompPreset cardShape()
    {
        CompPresetPayload payload = new CompPresetPayload();
        CompShape shape = new CompShape("card", CompShape.RECT);

        shape.width = 480F;
        shape.height = 280F;
        shape.fillColor = 0xFF1E2633;
        shape.strokeColor = 0x3355AAFF;
        shape.strokeWidth = 1.5F;
        shape.corners = new ArrayList<>(List.of(28F, 28F, 28F, 28F));
        payload.shapes.add(shape);

        return of("card_shape", "圆角卡片", CompPreset.SHAPE, "480×280，四角 28px 圆角", 0xFF8CE0A6, payload);
    }

    private static CompPreset badgeShape()
    {
        CompPresetPayload payload = new CompPresetPayload();
        CompShape shape = new CompShape("badge", CompShape.TRIANGLE);

        shape.width = 220F;
        shape.height = 190F;
        shape.fillColor = 0xFFFFB86B;
        shape.corners = new ArrayList<>(List.of(10F, 10F, 10F));
        payload.shapes.add(shape);

        return of("badge_shape", "三角徽章", CompPreset.SHAPE, "三角形 + 三边 10px 圆角", 0xFFFF9DB0, payload);
    }

    private static CompPreset ringShape()
    {
        CompPresetPayload payload = new CompPresetPayload();
        CompShape outer = new CompShape("ring_outer", CompShape.ELLIPSE);
        CompShape inner = new CompShape("ring_inner", CompShape.ELLIPSE);

        outer.width = 260F;
        outer.height = 260F;
        outer.fillColor = 0xFF55AAFF;

        inner.width = 180F;
        inner.height = 180F;
        inner.operator = CompShape.OP_SUBTRACT;
        inner.fillColor = 0xFFFFFFFF;

        payload.shapes.add(outer);
        payload.shapes.add(inner);

        return of("ring_shape", "圆环", CompPreset.SHAPE, "两个椭圆相减得到圆环", 0xFF7FC4FF, payload);
    }
}
