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

        add(new AmEffectDef("am_pinch_bulge", "收缩与膨胀", "AM · 变形", """
            void main()
            {
                vec2 uv = vUv;
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = uv - center;
                float radius = max(u_radius, 0.0001);
                float dist = length(d * Size) / max(Size.x, Size.y);
                float falloff = clamp(1.0 - dist / radius, 0.0, 1.0);
                float amount = clamp(u_strength, -1.0, 1.0) * falloff * falloff;
                vec2 warped = center + d * (1.0 - amount);
                vec2 inside = step(vec2(0.0), warped) * step(warped, vec2(1.0));
                vec4 c = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y);
            }
            """, 0.5F, "strength")
            .p("strength", "强度（负=收缩）", -1F, 1F, 0.4F, 0.01F)
            .p("radius", "半径", 0.01F, 1.5F, 0.5F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_circular_ripple", "圆形波纹", "AM · 变形", """
            void main()
            {
                vec2 uv = vUv;
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = uv - center;
                float dist = length(d * Size);
                float radius = max(u_radius, 0.0001) * max(Size.x, Size.y);
                float feather = clamp(u_feather, 0.0, 1.0);
                float edge = 1.0 - smoothstep(radius * (1.0 - feather), radius, dist);
                float wave = sin(dist / max(u_frequency, 0.0001) * 6.2831853 + u_phase) * u_strength * edge;
                vec2 offset = dist > 0.0001 ? (d / dist) * wave / max(Size, vec2(1.0)) : vec2(0.0);
                vec2 warped = uv + offset;
                vec4 c = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "strength")
            .p("strength", "强度", -100F, 100F, 12F, 0.5F)
            .p("frequency", "波长", 1F, 200F, 60F, 1F)
            .p("phase", "相位", -180F, 180F, 0F, 1F)
            .p("radius", "半径", 0.05F, 1.5F, 0.9F, 0.01F)
            .p("feather", "羽化", 0F, 1F, 0.3F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_kaleidoscope", "万花筒", "AM · 变形", """
            void main()
            {
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 p = vUv - center;
                float radius = length(p);
                float angle = atan(p.y, p.x) + radians(u_offset_angle);
                float segments = max(1.0, floor(u_rcount + 0.5));
                float wedge = 6.2831853 / segments;
                angle = mod(angle, wedge);
                angle = abs(angle - wedge * 0.5);
                vec2 warped = center + vec2(cos(angle), sin(angle)) * radius;
                vec4 c = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0F, null)
            .p("rcount", "重复次数", 1F, 24F, 6F, 1F)
            .p("offset_angle", "旋转", -180F, 180F, 0F, 1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_mirror_warp", "镜像", "AM · 变形", """
            void main()
            {
                vec2 uv = vUv;
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 warped = uv;
                float strength = clamp(u_strength, 0.0, 1.0);
                float alpha = clamp(u_alpha, 0.0, 1.0);

                if (u_mode < 0.5)
                {
                    warped.x = center.x + abs(uv.x - center.x);
                }
                else
                {
                    warped.y = center.y + abs(uv.y - center.y);
                }

                warped = mix(uv, warped, strength);
                vec2 inside = step(vec2(0.0), warped) * step(warped, vec2(1.0));
                vec4 c = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y * alpha + c.a * (1.0 - alpha));
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 0.5F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("mode", "方向（0 水平 / 1 垂直）", 0F, 1F, 0F, 1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_swirl", "旋转扭曲", "AM · 变形", """
            void main()
            {
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = vUv - center;
                float radius = max(u_radius, 0.0001);
                float dist = length(d * Size) / max(Size.x, Size.y);
                float falloff = clamp(1.0 - dist / radius, 0.0, 1.0);
                float angle = radians(u_angle) * falloff * falloff;
                float s = sin(angle);
                float c = cos(angle);
                vec2 warped = center + mat2(c, s, -s, c) * d;
                vec4 texel = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(texel.rgb, texel.a);
            }
            """, 0.5F, "angle")
            .p("angle", "角度", -720F, 720F, 90F, 1F)
            .p("radius", "半径", 0.01F, 1.5F, 0.5F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_chromatic_aberration", "彩色像差", "AM · 风格化", """
            void main()
            {
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle))) * u_amount / max(Size, vec2(1.0));
                vec4 g = texture(Source, vUv);
                float r = texture(Source, clamp(vUv + dir, 0.0, 1.0)).r;
                float b = texture(Source, clamp(vUv - dir, 0.0, 1.0)).b;
                fragColor = vec4(r, g.g, b, g.a);
            }
            """, 0.5F, "amount")
            .p("amount", "偏移（像素）", 0F, 40F, 4F, 0.1F)
            .p("angle", "角度", -180F, 180F, 0F, 1F));

        add(new AmEffectDef("am_tilt_shift", "移轴模糊", "AM · 模糊", """
            void main()
            {
                vec4 sharp = texture(Source, vUv);
                vec4 sum = vec4(0.0);
                vec2 stepUv = vec2(u_radius / max(Size.x, 1.0), 0.0);

                for (int i = -8; i <= 8; i++)
                {
                    sum += texture(Source, clamp(vUv + stepUv * float(i), 0.0, 1.0));
                }

                vec4 blurred = sum / 17.0;
                float band = smoothstep(clamp(u_width, 0.0, 0.6), clamp(u_width, 0.0, 0.6) + 0.12,
                    abs(vUv.y - u_center_y));
                fragColor = mix(sharp, blurred, band);
            }
            """, 0.5F, "radius")
            .p("radius", "模糊半径", 0F, 40F, 8F, 0.5F)
            .p("width", "清晰带宽度", 0F, 0.6F, 0.12F, 0.01F)
            .p("center_y", "清晰带位置", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_stretch_axis", "轴向拉伸", "AM · 变形", """
            void main()
            {
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 uv = vUv;
                float factor = 1.0 + u_amount;

                if (u_axis < 0.5)
                {
                    uv.x = center.x + (vUv.x - center.x) / max(factor, 0.01);
                }
                else
                {
                    uv.y = center.y + (vUv.y - center.y) / max(factor, 0.01);
                }

                vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
                vec4 c = texture(Source, clamp(uv, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y);
            }
            """, 0.5F, "amount")
            .p("amount", "拉伸", -0.95F, 2F, 0.3F, 0.01F)
            .p("axis", "方向（0 X / 1 Y）", 0F, 1F, 0F, 1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_slice_glitch", "切片故障", "AM · 风格化", """
            void main()
            {
                float bands = max(u_bands, 1.0);
                float line = floor(vUv.y * bands);
                float phase = floor(Time * max(u_speed, 0.0));
                float rnd = hash(vec2(line, phase + u_seed));
                float offset = (rnd - 0.5) * 2.0 * u_amount / max(Size.x, 1.0);
                vec4 c = texture(Source, clamp(vec2(vUv.x + offset, vUv.y), 0.0, 1.0));
                fragColor = c;
            }
            """, 0.5F, "amount")
            .p("amount", "位移（像素）", 0F, 300F, 24F, 1F)
            .p("bands", "切片数", 2F, 80F, 18F, 1F)
            .p("speed", "速度", 0F, 30F, 6F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_four_color_gradient", "四色渐变", "AM · 生成", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float t = clamp(dot(vUv - 0.5, dir) * max(u_scale, 0.01) + 0.5 + u_offset, 0.0, 1.0);
                float o1 = clamp(u_offset1, 0.0, 1.0);
                float o2 = clamp(u_offset2, 0.0, 1.0);
                float o3 = clamp(u_offset3, 0.0, 1.0);
                vec3 grad = u_color1.rgb;

                if (t < o2)
                {
                    grad = mix(u_color1.rgb, u_color2.rgb, clamp((t - o1) / max(o2 - o1, 0.0001), 0.0, 1.0));
                }
                else if (t < o3)
                {
                    grad = mix(u_color2.rgb, u_color3.rgb, clamp((t - o2) / max(o3 - o2, 0.0001), 0.0, 1.0));
                }
                else
                {
                    grad = mix(u_color3.rgb, u_color4.rgb, clamp((t - o3) / max(1.0 - o3, 0.0001), 0.0, 1.0));
                }

                fragColor = vec4(mix(c.rgb, grad, clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("angle", "角度", -180F, 180F, 90F, 1F)
            .p("offset", "偏移", -1F, 1F, 0F, 0.01F)
            .p("offset1", "色标 1", 0F, 1F, 0F, 0.01F)
            .p("offset2", "色标 2", 0F, 1F, 0.33F, 0.01F)
            .p("offset3", "色标 3", 0F, 1F, 0.66F, 0.01F)
            .p("scale", "缩放", 0.05F, 4F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color1", "颜色 1", 0xFF55AAFF)
            .c("color2", "颜色 2", 0xFFFFE2A8)
            .c("color3", "颜色 3", 0xFFFF7AB0)
            .c("color4", "颜色 4", 0xFF101040));

        add(new AmEffectDef("am_stripes", "条纹", "AM · 生成", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float coord = dot(vUv, dir) * max(u_frequency, 0.1) + u_phase;
                float wave = abs(fract(coord) - 0.5) * 2.0;
                float width = clamp(u_width, 0.01, 0.99);
                float softness = max(u_softness, 0.001);
                float stripe = 1.0 - smoothstep(width - softness, width + softness, wave);
                vec3 line = mix(u_color1.rgb, u_color2.rgb, stripe);
                fragColor = vec4(mix(c.rgb, line, clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("angle", "角度", -180F, 180F, 0F, 1F)
            .p("frequency", "密度", 0.1F, 40F, 10F, 0.1F)
            .p("width", "条纹宽", 0.01F, 0.99F, 0.5F, 0.01F)
            .p("softness", "边缘柔和", 0.001F, 0.5F, 0.02F, 0.001F)
            .p("phase", "相位", -1F, 1F, 0F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color1", "颜色 1", 0xFF101828)
            .c("color2", "颜色 2", 0xFFFFD070));

        add(new AmEffectDef("am_grid_lines", "网格", "AM · 生成", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                vec2 p = (vUv - 0.5) * Size;
                float angle = radians(u_angle);
                mat2 rotation = rot(angle);
                p = rotation * p;
                vec2 cell = p / max(u_cell, 1.0);
                vec2 line = abs(fract(cell) - 0.5);
                float thickness = max(u_thickness, 0.001);
                vec2 mask = 1.0 - smoothstep(vec2(0.5 - thickness), vec2(0.5), line * 2.0);
                float grid = max(mask.x, mask.y) * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, grid), c.a);
            }
            """, 0F, "alpha")
            .p("cell", "格子大小", 2F, 200F, 32F, 1F)
            .p("thickness", "线宽", 0.001F, 0.49F, 0.06F, 0.001F)
            .p("angle", "旋转", -180F, 180F, 0F, 1F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "线条颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_starfield", "星空", "AM · 生成", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                vec2 grid = vec2(12.0, 8.0) * max(u_density, 0.05);
                vec2 cell = floor(vUv * grid);
                vec2 local = fract(vUv * grid);
                vec2 rnd = hash2(cell + u_seed);
                vec2 center = vec2(0.2) + rnd * 0.6;
                float radius = max(u_size, 0.5) / 100.0;
                float dist = length(local - center);
                float star = smoothstep(radius, 0.0, dist);
                float twinkle = 0.65 + 0.35 * sin(Time * max(u_speed, 0.0) + rnd.x * 6.2831853);
                float amount = star * twinkle * clamp(u_brightness, 0.0, 3.0) * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(clamp(c.rgb + vec3(amount), 0.0, 1.0), c.a);
            }
            """, 0F, "alpha")
            .p("density", "密度", 0.1F, 4F, 1F, 0.01F)
            .p("size", "星点大小", 0.5F, 20F, 2F, 0.1F)
            .p("speed", "闪烁速度", 0F, 20F, 3F, 0.1F)
            .p("brightness", "亮度", 0F, 3F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_gaussian_blur", "高斯模糊", "AM · 模糊", """
            void main()
            {
                fragColor = texture(Source, vUv);
            }
            """, 0F, "strength")
            .blur("strength", false, null)
            .p("strength", "半径", 0F, 150F, 12F, 1F));

        add(new AmEffectDef("am_box_blur", "方框模糊", "AM · 模糊", """
            void main()
            {
                fragColor = texture(Source, vUv);
            }
            """, 0F, "strength")
            .blur("strength", true, "iter")
            .p("strength", "半径", 0F, 100F, 8F, 1F)
            .p("iter", "迭代次数", 1F, 4F, 1F, 1F));

        add(new AmEffectDef("am_motion_blur", "动感模糊", "AM · 模糊", """
            void main()
            {
                vec2 direction = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                vec2 stepUv = direction * (u_length / max(Size, vec2(1.0))) / 24.0;
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 24; i++)
                {
                    float t = float(i) / 23.0 - 0.5;
                    sum += texture(Source, clamp(vUv + stepUv * (float(i) - 11.5), 0.0, 1.0));
                }

                fragColor = sum / 24.0;
            }
            """, 0.5F, "length")
            .p("length", "长度（像素）", 0F, 300F, 30F, 1F)
            .p("angle", "角度", -180F, 180F, 0F, 1F));

        add(new AmEffectDef("am_zoom_blur", "径向模糊", "AM · 模糊", """
            void main()
            {
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 delta = vUv - center;
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 24; i++)
                {
                    float t = float(i) / 23.0;
                    float scale = 1.0 + (t - 0.5) * u_strength;
                    sum += texture(Source, clamp(center + delta * scale, 0.0, 1.0));
                }

                fragColor = sum / 24.0;
            }
            """, 0.5F, "strength")
            .p("strength", "强度", -1F, 1F, 0.25F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_lens_blur", "镜头模糊", "AM · 模糊", """
            void main()
            {
                vec2 radiusUv = vec2(u_radius) / max(Size, vec2(1.0));
                vec4 sum = vec4(0.0);
                float total = 0.0;

                for (int i = 0; i < 32; i++)
                {
                    float a = float(i) * 2.3999632;
                    float r = sqrt(float(i) / 31.0) * u_radius;
                    vec2 point = vUv + vec2(cos(a), sin(a)) * r / max(Size, vec2(1.0));
                    float weight = 1.0 - 0.5 * (r / max(u_radius, 0.001));

                    sum += texture(Source, clamp(point, 0.0, 1.0)) * weight;
                    total += weight;
                }

                fragColor = sum / max(total, 0.0001);
            }
            """, 0.5F, "radius")
            .p("radius", "半径（像素）", 0F, 80F, 12F, 1F));

        add(new AmEffectDef("am_polar", "极坐标", "AM · 变形", """
            void main()
            {
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 p = (vUv - center) * 2.0;
                float radius = length(p);
                float angle = atan(p.y, p.x) + radians(u_angle);
                vec2 polar = vec2(angle / 6.2831853 * max(u_repeat, 1.0) + 0.5,
                                  radius * max(u_scale, 0.01));
                polar = fract(polar);
                vec2 warped = mix(vUv, polar, clamp(u_amount, 0.0, 1.0));
                vec4 c = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .p("angle", "旋转", -180F, 180F, 0F, 1F)
            .p("scale", "半径缩放", 0.05F, 2F, 0.5F, 0.01F)
            .p("repeat", "角向重复", 1F, 8F, 1F, 1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_fractal_warp", "分形扭曲", "AM · 变形", """
            void main()
            {
                vec2 warped = vUv;
                vec2 noiseUv = vUv * max(u_scale, 0.01) + vec2(u_seed * 7.13);
                float n1 = fbm(noiseUv, 4) - 0.5;
                float n2 = fbm(noiseUv + 13.7, 4) - 0.5;
                warped += vec2(n1, n2) * (u_amount / max(Size, vec2(1.0)));
                vec4 c = texture(Source, clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "amount")
            .p("amount", "强度（像素）", 0F, 400F, 48F, 1F)
            .p("scale", "细节", 0.5F, 40F, 6F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_colorize", "色彩化", "AM · 调色", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float y = luma(c.rgb);
                vec3 tinted = u_color.rgb * (0.25 + y * 1.5);
                fragColor = vec4(clamp(mix(c.rgb, tinted, clamp(u_strength, 0.0, 1.0)), 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "颜色", 0xFF4A90D9));

        add(new AmEffectDef("am_replace_color", "替换颜色", "AM · 调色", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float distanceToTarget = length(c.rgb - u_target.rgb);
                float hit = 1.0 - smoothstep(u_tolerance, u_tolerance + max(u_softness, 0.001), distanceToTarget);
                vec3 rgb = mix(c.rgb, u_replacement.rgb, hit * clamp(u_strength, 0.0, 1.0));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("tolerance", "容差", 0F, 1F, 0.15F, 0.01F)
            .p("softness", "柔和度", 0.001F, 0.5F, 0.08F, 0.001F)
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .c("target", "目标颜色", 0xFF00FF00)
            .c("replacement", "替换颜色", 0xFFFF8800));

        add(new AmEffectDef("am_gradient_map", "渐变映射", "AM · 调色", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                vec3 mapped = mix(u_shadow.rgb, u_highlight.rgb, y);
                vec3 rgb = mix(c.rgb, mapped, clamp(u_amount, 0.0, 1.0));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .c("shadow", "阴影颜色", 0xFF101040)
            .c("highlight", "高光颜色", 0xFFFFD070));

        add(new AmEffectDef("am_radial_shadow", "径向阴影", "AM · 风格化", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                vec2 light = vec2(u_light_x, u_light_y);
                vec2 toPixel = vUv - light;
                float dist = length(toPixel);
                vec2 direction = dist > 0.0001 ? toPixel / dist : vec2(0.0);
                float size = u_size / max(Size.x, Size.y);
                vec2 shadowUv = clamp(vUv + direction * size, 0.0, 1.0);
                vec4 shadowSample = texture(Source, shadowUv);
                float falloff = mix(1.0, clamp(1.0 - dist, 0.0, 1.0), clamp(u_fade, 0.0, 1.0));
                float alpha = shadowSample.a * clamp(u_alpha, 0.0, 1.0) * falloff;
                vec3 rgb = mix(u_color.rgb, c.rgb, c.a);
                float outA = c.a + alpha * (1.0 - c.a);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), clamp(outA, 0.0, 1.0));
            }
            """, 0.5F, "size")
            .p("size", "距离", 0F, 300F, 30F, 1F)
            .p("alpha", "不透明度", 0F, 1F, 0.6F, 0.01F)
            .p("fade", "渐隐", 0F, 1F, 0.5F, 0.01F)
            .p("light_x", "光源 X", 0F, 1F, 0.2F, 0.01F)
            .p("light_y", "光源 Y", 0F, 1F, 0.2F, 0.01F)
            .c("color", "阴影颜色", 0xFF000000));

        add(new AmEffectDef("am_pixelate", "像素化", "AM · 变形", """
            void main()
            {
                vec2 uv = vUv;
                vec2 grid = max(vec2(u_size, u_size * max(u_stretch, 0.05)), vec2(1.0));
                vec2 offset = vec2(u_offset_x, u_offset_y);
                vec2 snapped = (floor((uv + offset) * Size / grid) + 0.5) * grid / max(Size, vec2(1.0));
                vec2 d = snapped - 0.5;
                float vignette = 1.0 - smoothstep(0.35, 0.75, length(d));
                float keep = mix(1.0, vignette, clamp(u_vignette, 0.0, 1.0));
                vec4 c = texture(Source, clamp(snapped, 0.0, 1.0));
                vec3 rgb = c.rgb;
                if (u_saturation != 0.0)
                {
                    rgb = mix(vec3(luma(c.rgb)), c.rgb, clamp(1.0 + u_saturation, 0.0, 2.0));
                }
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a * keep);
            }
            """, 0F, null)
            .p("size", "像素大小", 1F, 128F, 16F, 1F)
            .p("stretch", "纵向拉伸", 0.05F, 4F, 1F, 0.01F)
            .p("offset_x", "偏移 X", -1F, 1F, 0F, 0.01F)
            .p("offset_y", "偏移 Y", -1F, 1F, 0F, 0.01F)
            .p("vignette", "暗角", 0F, 1F, 0F, 0.01F)
            .p("saturation", "饱和度", -1F, 1F, 0F, 0.01F));
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
