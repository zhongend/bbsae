package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference-compatible effect catalogue.
 *
 * <p>Each entry is a parameter schema plus one fragment body; the renderer derives the program.
 * The bodies are written here, not generated from the reference app — only the parameter names,
 * ranges and the visual contract are aligned so that a project authored with those values behaves
 * the same way.</p>
 *
 * <p>The first batch covers colour work and two distort effects. Effects are added in batches; the
 * shared helpers ({@code luma}, {@code rgb2hsv}, {@code hsv2rgb}, {@code hash}) are injected by
 * {@link AmEffectRenderer}, so a body only contains {@code void main()}.</p>
 */
public final class AmEffectLibrary
{
    private static final Map<String, AmEffectDef> DEFS = new LinkedHashMap<>();
    private static final List<AmEffectDef> LIST = new ArrayList<>();

    static
    {
        add(new AmEffectDef("am_color_balance", "色彩平衡", "AM · 调色", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float y = luma(c.rgb);
                float wLow = 1.0 - smoothstep(0.0, 0.5, y);
                float wHigh = smoothstep(0.5, 1.0, y);
                float wMid = max(0.0, 1.0 - wLow - wHigh);
                float cr = u_cr_low * wLow + u_cr_mid * wMid + u_cr_high * wHigh;
                float mg = u_mg_low * wLow + u_mg_mid * wMid + u_mg_high * wHigh;
                float yb = u_yb_low * wLow + u_yb_mid * wMid + u_yb_high * wHigh;
                vec3 shifted = c.rgb + vec3(cr, mg, yb) * 0.5;
                fragColor = vec4(clamp(shifted, 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .z("cr_low", "mg_low", "yb_low", "cr_mid", "mg_mid", "yb_mid", "cr_high", "mg_high", "yb_high")
            .p("cr_low", "阴影：青—红", -1F, 1F, 0F, 0.01F)
            .p("mg_low", "阴影：洋红—绿", -1F, 1F, 0F, 0.01F)
            .p("yb_low", "阴影：黄—蓝", -1F, 1F, 0F, 0.01F)
            .p("cr_mid", "中间调：青—红", -1F, 1F, 0F, 0.01F)
            .p("mg_mid", "中间调：洋红—绿", -1F, 1F, 0F, 0.01F)
            .p("yb_mid", "中间调：黄—蓝", -1F, 1F, 0F, 0.01F)
            .p("cr_high", "高光：青—红", -1F, 1F, 0F, 0.01F)
            .p("mg_high", "高光：洋红—绿", -1F, 1F, 0F, 0.01F)
            .p("yb_high", "高光：黄—蓝", -1F, 1F, 0F, 0.01F));

        add(new AmEffectDef("am_temperature", "色温", "AM · 调色", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float t = clamp(u_temperature, -1.0, 1.0);
                vec3 tint = vec3(1.0 + 0.28 * t, 1.0 - 0.04 * abs(t), 1.0 - 0.28 * t);
                vec3 graded = c.rgb * tint;
                graded *= dot(c.rgb, vec3(1.0)) / max(dot(graded, vec3(1.0)), 0.0001);
                fragColor = vec4(clamp(mix(c.rgb, graded, clamp(u_strength, 0.0, 1.0)), 0.0, 1.0), c.a);
            }
            """, 0F, "temperature")
            .p("temperature", "色温", -1F, 1F, 0F, 0.01F)
            .p("strength", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_highlight_shadow", "高光与阴影", "AM · 调色", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float y = luma(c.rgb);
                float wLow = 1.0 - smoothstep(0.0, 0.5, y);
                float wHigh = smoothstep(0.5, 1.0, y);
                float lift = u_highlights * 0.5 * wHigh + u_shadows * 0.5 * wLow;
                fragColor = vec4(clamp(c.rgb + lift, 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .z("highlights", "shadows")
            .p("highlights", "高光", -1F, 1F, 0F, 0.01F)
            .p("shadows", "阴影", -1F, 1F, 0F, 0.01F));

        add(new AmEffectDef("am_cartoon", "卡通", "AM · 风格化", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                vec3 s = texture(Source, vUv + vec2(Texel.x, 0.0)).rgb;
                vec3 d = texture(Source, vUv - vec2(Texel.x, 0.0)).rgb;
                vec3 s2 = texture(Source, vUv + vec2(0.0, Texel.y)).rgb;
                vec3 d2 = texture(Source, vUv - vec2(0.0, Texel.y)).rgb;
                float edge = length(s - d) + length(s2 - d2);
                float levels = mix(16.0, 4.0, clamp(u_strength, 0.0, 1.0));
                vec3 flatColor = floor(c.rgb * levels + 0.5) / levels;
                float line = smoothstep(0.18, 0.5, edge);
                vec4 outline = u_outline;
                vec3 rgb = mix(flatColor, outline.rgb, line * clamp(u_strength, 0.0, 1.0));
                fragColor = vec4(rgb, c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .c("outline", "描边颜色", 0xFF000000));

        add(new AmEffectDef("am_long_shadow", "长阴影", "AM · 风格化", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float size = max(u_size, 0.0);
                float shade = 0.0;
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));

                for (int i = 1; i <= 48; i++)
                {
                    float t = float(i) / 48.0;
                    vec2 uv = vUv - dir * (size * t) / max(Size, vec2(1.0));
                    float inside = uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0 ? 1.0 : 0.0;
                    float a = texture(Source, clamp(uv, 0.0, 1.0)).a * inside;
                    float falloff = mix(1.0, 1.0 - t, clamp(u_fade, 0.0, 1.0));
                    shade = max(shade, a * falloff);
                }

                float amount = clamp(u_alpha, 0.0, 1.0) * clamp(u_solidity, 0.0, 1.0) * shade;
                vec4 shadow = vec4(u_color.rgb, amount);
                vec3 rgb = mix(shadow.rgb, c.rgb, c.a * (1.0 - shadow.a) + c.a * shadow.a);
                float a = c.a + shadow.a * (1.0 - c.a);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), clamp(a, 0.0, 1.0));
            }
            """, 0.5F, "size")
            .p("size", "长度", 0F, 600F, 60F, 1F)
            .p("angle", "角度", -180F, 180F, 45F, 1F)
            .p("alpha", "不透明度", 0F, 1F, 0.7F, 0.01F)
            .p("solidity", "填充度", 0F, 1F, 0.6F, 0.01F)
            .p("fade", "渐隐", 0F, 1F, 1F, 0.01F)
            .c("color", "阴影颜色", 0xFF000000));

        add(new AmEffectDef("am_bend", "弯曲", "AM · 变形", """
            void main()
            {
                vec2 uv = vUv;
                float amount = clamp(u_amount, -1.0, 1.0);
                vec2 local = uv - vec2(u_center_x, u_center_y);
                float bend = amount * local.x * local.x * 2.0;
                local.y += bend;
                uv = local + vec2(u_center_x, u_center_y);
                vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
                vec4 c = texture(Source, clamp(uv, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y);
            }
            """, 0.25F, "amount")
            .p("amount", "弯曲度", -1F, 1F, 0.25F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_outline", "描边", "AM · 风格化", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float a = 0.0;
                a = max(a, texture(Source, vUv + vec2(Texel.x * u_width, 0.0)).a);
                a = max(a, texture(Source, vUv - vec2(Texel.x * u_width, 0.0)).a);
                a = max(a, texture(Source, vUv + vec2(0.0, Texel.y * u_width)).a);
                a = max(a, texture(Source, vUv - vec2(0.0, Texel.y * u_width)).a);
                a = max(a, texture(Source, vUv + Texel * u_width).a);
                a = max(a, texture(Source, vUv - Texel * u_width).a);
                a = max(a, texture(Source, vUv + vec2(Texel.x, -Texel.y) * u_width).a);
                a = max(a, texture(Source, vUv + vec2(-Texel.x, Texel.y) * u_width).a);
                float ring = clamp(a - c.a, 0.0, 1.0) * clamp(u_alpha, 0.0, 1.0);
                vec3 rgb = mix(c.rgb, u_color.rgb, ring);
                fragColor = vec4(rgb, clamp(c.a + ring, 0.0, 1.0));
            }
            """, 0F, "width")
            .p("width", "宽度", 0F, 8F, 2F, 0.1F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "颜色", 0xFF000000));
    }

    private AmEffectLibrary()
    {}

    private static void add(AmEffectDef definition)
    {
        DEFS.put(definition.id, definition);
        LIST.add(definition);
    }

    public static List<AmEffectDef> all()
    {
        return LIST;
    }

    public static AmEffectDef get(String id)
    {
        return id == null ? null : DEFS.get(id);
    }

    public static boolean supports(String id)
    {
        return DEFS.containsKey(id);
    }

    /** True when the effect cannot change the input at the current parameter values. */
    public static boolean isNeutral(CompEffect effect)
    {
        AmEffectDef definition = effect == null ? null : get(effect.type);

        if (definition == null)
        {
            return false;
        }

        List<String> keys = new ArrayList<>();

        if (definition.zeroKey != null)
        {
            keys.add(definition.zeroKey);
        }

        keys.addAll(definition.zeroKeys);

        if (keys.isEmpty())
        {
            return false;
        }

        for (String key : keys)
        {
            if (Math.abs(effect.valueF(key, 0F)) > 0.0001F)
            {
                return false;
            }
        }

        return true;
    }
}
