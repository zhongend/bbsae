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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 s = sampleLayer(luv + vec2(LayerTexel.x, 0.0)).rgb;
                vec3 d = sampleLayer(luv - vec2(LayerTexel.x, 0.0)).rgb;
                vec3 s2 = sampleLayer(luv + vec2(0.0, LayerTexel.y)).rgb;
                vec3 d2 = sampleLayer(luv - vec2(0.0, LayerTexel.y)).rgb;
                float edge = length(s - d) + length(s2 - d2);
                float strength = clamp(u_strength, 0.0, 1.0);
                float levels = mix(16.0, 4.0, strength);
                vec3 quantized = floor(c.rgb * levels + 0.5) / levels;
                vec3 flatColor = mix(c.rgb, quantized, strength);
                float line = smoothstep(0.18, 0.5, edge);
                vec4 outline = u_outline;
                vec3 rgb = mix(flatColor, outline.rgb, line * strength);
                fragColor = vec4(rgb, c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .c("outline", "描边颜色", 0xFF000000));

        add(new AmEffectDef("am_long_shadow", "长阴影", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float size = max(u_size, 0.0);
                float shade = 0.0;
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));

                for (int i = 1; i <= 48; i++)
                {
                    float t = float(i) / 48.0;
                    vec2 uv = luv - dir * (size * t) / max(LayerSize, vec2(1.0));
                    float inside = uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0 ? 1.0 : 0.0;
                    float a = sampleLayer(clamp(uv, 0.0, 1.0)).a * inside;
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
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
                float amount = clamp(u_amount, -1.0, 1.0);
                vec2 local = uv - vec2(u_center_x, u_center_y);
                float bend = amount * local.x * local.x * 2.0;
                local.y += bend;
                uv = local + vec2(u_center_x, u_center_y);
                vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
                vec4 c = sampleLayer(clamp(uv, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y);
            }
            """, 0.25F, "amount")
            .p("amount", "弯曲度", -1F, 1F, 0.25F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_outline", "描边", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float a = 0.0;
                a = max(a, sampleLayer(luv + vec2(LayerTexel.x * u_width, 0.0)).a);
                a = max(a, sampleLayer(luv - vec2(LayerTexel.x * u_width, 0.0)).a);
                a = max(a, sampleLayer(luv + vec2(0.0, LayerTexel.y * u_width)).a);
                a = max(a, sampleLayer(luv - vec2(0.0, LayerTexel.y * u_width)).a);
                a = max(a, sampleLayer(luv + LayerTexel * u_width).a);
                a = max(a, sampleLayer(luv - LayerTexel * u_width).a);
                a = max(a, sampleLayer(luv + vec2(LayerTexel.x, -LayerTexel.y) * u_width).a);
                a = max(a, sampleLayer(luv + vec2(-LayerTexel.x, LayerTexel.y) * u_width).a);
                float ring = clamp(a - c.a, 0.0, 1.0) * clamp(u_alpha, 0.0, 1.0);
                vec3 rgb = mix(c.rgb, u_color.rgb, ring);
                fragColor = vec4(rgb, clamp(c.a + ring, 0.0, 1.0));
            }
            """, 0.25F, "width")
            .p("width", "宽度", 0F, 8F, 2F, 0.1F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "颜色", 0xFF000000));

        add(new AmEffectDef("am_pinch_bulge", "收缩与膨胀", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = uv - center;
                float radius = max(u_radius, 0.0001);
                float dist = length(d * LayerSize) / max(LayerSize.x, LayerSize.y);
                float falloff = clamp(1.0 - dist / radius, 0.0, 1.0);
                float amount = clamp(u_strength, -1.0, 1.0) * falloff * falloff;
                vec2 warped = center + d * (1.0 - amount);
                vec2 inside = step(vec2(0.0), warped) * step(warped, vec2(1.0));
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = uv - center;
                float dist = length(d * LayerSize);
                float radius = max(u_radius, 0.0001) * max(LayerSize.x, LayerSize.y);
                float feather = clamp(u_feather, 0.0, 1.0);
                float edge = 1.0 - smoothstep(radius * (1.0 - feather), radius, dist);
                float wave = sin(dist / max(u_frequency, 0.0001) * 6.2831853 + u_phase) * u_strength * edge;
                vec2 offset = dist > 0.0001 ? (d / dist) * wave / max(LayerSize, vec2(1.0)) : vec2(0.0);
                vec2 warped = uv + offset;
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 p = luv - center;
                float radius = length(p);
                float angle = atan(p.y, p.x) + radians(u_offset_angle);
                float segments = max(1.0, floor(u_rcount + 0.5));
                float wedge = 6.2831853 / segments;
                angle = mod(angle, wedge);
                angle = abs(angle - wedge * 0.5);
                vec2 warped = center + vec2(cos(angle), sin(angle)) * radius;
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
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
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                float radius = max(u_radius, 0.0001);
                float dist = length(d * LayerSize) / max(LayerSize.x, LayerSize.y);
                float falloff = clamp(1.0 - dist / radius, 0.0, 1.0);
                float angle = radians(u_angle) * falloff * falloff;
                float s = sin(angle);
                float c = cos(angle);
                vec2 warped = center + mat2(c, s, -s, c) * d;
                vec4 texel = sampleLayer(clamp(warped, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle))) * u_amount / max(LayerSize, vec2(1.0));
                vec4 g = sampleLayer(luv);
                float r = sampleLayer(clamp(luv + dir, 0.0, 1.0)).r;
                float b = sampleLayer(clamp(luv - dir, 0.0, 1.0)).b;
                fragColor = vec4(r, g.g, b, g.a);
            }
            """, 0.5F, "amount")
            .p("amount", "偏移（像素）", 0F, 40F, 4F, 0.1F)
            .p("angle", "角度", -180F, 180F, 0F, 1F));

        add(new AmEffectDef("am_tilt_shift", "移轴模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 sharp = sampleLayer(luv);
                vec4 sum = vec4(0.0);
                vec2 stepUv = vec2(u_radius / max(LayerSize.x, 1.0), 0.0);

                for (int i = -8; i <= 8; i++)
                {
                    sum += sampleLayer(clamp(luv + stepUv * float(i), 0.0, 1.0));
                }

                vec4 blurred = sum / 17.0;
                float band = smoothstep(clamp(u_width, 0.0, 0.6), clamp(u_width, 0.0, 0.6) + 0.12,
                    abs(luv.y - u_center_y));
                fragColor = mix(sharp, blurred, band);
            }
            """, 0.5F, "radius")
            .p("radius", "模糊半径", 0F, 40F, 8F, 0.5F)
            .p("width", "清晰带宽度", 0F, 0.6F, 0.12F, 0.01F)
            .p("center_y", "清晰带位置", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_stretch_axis", "轴向拉伸", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 uv = luv;
                float factor = 1.0 + u_amount;

                if (u_axis < 0.5)
                {
                    uv.x = center.x + (luv.x - center.x) / max(factor, 0.01);
                }
                else
                {
                    uv.y = center.y + (luv.y - center.y) / max(factor, 0.01);
                }

                vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
                vec4 c = sampleLayer(clamp(uv, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                float bands = max(u_bands, 1.0);
                float line = floor(luv.y * bands);
                float phase = floor(Time * max(u_speed, 0.0));
                float rnd = hash(vec2(line, phase + u_seed));
                float offset = (rnd - 0.5) * 2.0 * u_amount / max(LayerSize.x, 1.0);
                vec4 c = sampleLayer(clamp(vec2(luv.x + offset, luv.y), 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float t = clamp(dot(luv - 0.5, dir) * max(u_scale, 0.01) + 0.5 + u_offset, 0.0, 1.0);
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
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.001F)
            .c("color1", "颜色 1", 0xFF55AAFF)
            .c("color2", "颜色 2", 0xFFFFE2A8)
            .c("color3", "颜色 3", 0xFFFF7AB0)
            .c("color4", "颜色 4", 0xFF101040));

        add(new AmEffectDef("am_stripes", "条纹", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float coord = dot(luv, dir) * max(u_frequency, 0.1) + u_phase;
                float wave = abs(fract(coord) - 0.5) * 2.0;
                float width = clamp(u_width, 0.01, 0.99);
                float softness = max(u_softness, 0.001);
                float stripe = 1.0 - smoothstep(width - softness, width + softness, wave);
                vec3 line = mix(u_color1.rgb, u_color2.rgb, stripe);
                fragColor = vec4(mix(c.rgb, line, clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("angle", "角度", -180F, 180F, 0F, 0.5F)
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 p = (luv - 0.5) * LayerSize;
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
            .p("angle", "旋转", -180F, 180F, 0F, 0.5F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "线条颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_starfield", "星空", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 grid = vec2(12.0, 8.0) * max(u_density, 0.05);
                vec2 cell = floor(luv * grid);
                vec2 local = fract(luv * grid);
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

        add(new AmEffectDef("am_flip", "翻转", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                float a = radians(u_angle);
                float fade = abs(cos(a));
                vec2 uv = luv;

                if (u_axis < 0.5)
                {
                    uv.x = 0.5 + (luv.x - 0.5) / max(fade, 0.0001);

                    if (cos(a) < 0.0)
                    {
                        uv.x = 1.0 - uv.x;
                    }
                }
                else
                {
                    uv.y = 0.5 + (luv.y - 0.5) / max(fade, 0.0001);

                    if (cos(a) < 0.0)
                    {
                        uv.y = 1.0 - uv.y;
                    }
                }

                vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
                vec4 c = sampleLayer(clamp(uv, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y);
            }
            """, 0.5F, "angle")
            .p("angle", "角度", 0F, 360F, 0F, 1F)
            .p("axis", "轴（0 X / 1 Y）", 0F, 1F, 0F, 1F));

        add(new AmEffectDef("am_skew", "倾斜", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
                uv.x += u_amount * (luv.y - u_center_y);
                uv.y += u_vertical * (luv.x - u_center_x);
                fragColor = sampleLayer(uv);
            }
            """, 0.5F, "amount")
            .p("amount", "水平倾斜", -2F, 2F, 0.3F, 0.01F)
            .p("vertical", "垂直倾斜", -2F, 2F, 0F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_squeeze", "挤压", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
                float factor = max(1.0 + u_amount, 0.01);

                if (u_axis < 0.5)
                {
                    uv.x = u_center_x + (luv.x - u_center_x) / factor;
                }
                else
                {
                    uv.y = u_center_y + (luv.y - u_center_y) / factor;
                }

                fragColor = sampleLayer(uv);
            }
            """, 0.5F, "amount")
            .p("amount", "挤压量", -0.95F, 2F, 0.3F, 0.01F)
            .p("axis", "轴（0 X / 1 Y）", 0F, 1F, 0F, 1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_mask_blur", "遮罩模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 16; i++)
                {
                    float a = float(i) * 0.3926991;
                    float r = u_radius / max(LayerSize.x, 1.0);
                    sum += sampleLayer(luv + vec2(cos(a), sin(a)) * r);
                }

                vec4 blurred = sum / 16.0;
                float edge = clamp(4.0 * c.a * (1.0 - c.a), 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, blurred.rgb, edge * clamp(u_strength, 0.0, 1.0)), c.a);
            }
            """, 0F, "radius")
            .p("radius", "半径（像素）", 0F, 60F, 6F, 0.5F)
            .p("strength", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_color_gain", "色调", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 gained = c.rgb * vec3(max(u_gain_r, 0.0), max(u_gain_g, 0.0), max(u_gain_b, 0.0));
                gained += vec3(u_lift);
                fragColor = vec4(clamp(mix(c.rgb, gained, clamp(u_amount, 0.0, 1.0)), 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("gain_r", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("gain_g", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("gain_b", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("lift", 0F)) <= 0.0001F)
            .p("gain_r", "红增益", 0F, 3F, 1F, 0.01F)
            .p("gain_g", "绿增益", 0F, 3F, 1F, 0.01F)
            .p("gain_b", "蓝增益", 0F, 3F, 1F, 0.01F)
            .p("lift", "提升", -0.5F, 0.5F, 0F, 0.01F)
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_block_dissolve", "块状溶解", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 grid = vec2(max(u_blocks, 1.0));
                vec2 cell = floor(luv * grid);
                float rnd = hash(cell + u_seed);
                float visible = rnd < clamp(u_progress, 0.0, 1.0) ? 1.0 : 0.0;
                fragColor = vec4(c.rgb, c.a * visible);
            }
            """, 0F, null)
            .neutralWhen((effect) -> effect.valueF("progress", 1F) >= 0.999F)
            .p("progress", "进度", 0F, 1F, 0.5F, 0.01F)
            .p("blocks", "方块数", 2F, 120F, 20F, 1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_checker_dissolve", "棋盘溶解", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 cell = floor(luv * max(u_blocks, 1.0));
                float parity = mod(cell.x + cell.y, 2.0);
                float threshold = mix(0.0, 1.0, parity * 0.5 + 0.25);
                float visible = u_progress >= threshold ? 1.0 : 0.0;
                fragColor = vec4(c.rgb, c.a * visible);
            }
            """, 0F, null)
            .neutralWhen((effect) -> effect.valueF("progress", 0.5F) >= 0.999F)
            .p("progress", "进度", 0F, 1F, 0.5F, 0.01F)
            .p("blocks", "棋盘数", 2F, 80F, 10F, 1F));

        add(new AmEffectDef("am_pixel_dissolve", "像素溶解", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 cell = floor(luv * max(u_blocks, 1.0));
                float rnd = hash(cell + u_seed);
                float visible = u_progress >= rnd ? 1.0 : 0.0;
                fragColor = vec4(c.rgb, c.a * visible);
            }
            """, 0F, null)
            .neutralWhen((effect) -> effect.valueF("progress", 0.5F) >= 0.999F)
            .p("progress", "进度", 0F, 1F, 0.5F, 0.01F)
            .p("blocks", "像素块数", 4F, 200F, 40F, 1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_radial_wipe", "径向擦除（角度）", "AM · 转场", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = (luv - center) * vec2(LayerSize.x / max(LayerSize.y, 1.0), 1.0);
                float angle = atan(d.y, d.x) + radians(u_angle);
                float t = fract(angle / 6.2831853);
                float edge = clamp(u_softness, 0.001, 0.5);
                float progress = clamp(u_progress, 0.0, 1.0);
                float reveal = progress >= 0.999
                    ? 1.0
                    : smoothstep(t - edge, t + edge, progress);
                fragColor = vec4(c.rgb, c.a * reveal);
            }
            """, 0F, null)
            .neutralWhen((effect) -> effect.valueF("progress", 0.5F) >= 0.999F)
            .p("progress", "进度", 0F, 1F, 0.5F, 0.01F)
            .p("angle", "起始角度", -180F, 180F, 0F, 1F)
            .p("softness", "边缘柔和", 0.001F, 0.5F, 0.15F, 0.001F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_gradient_mask", "渐变遮罩", "AM · 转场", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float t = clamp(dot(luv - 0.5, dir) + 0.5, 0.0, 1.0);
                float edge = max(u_softness, 0.001);
                float progress = clamp(u_progress, 0.0, 1.0);
                float reveal;

                if (progress >= 0.999)
                {
                    reveal = 1.0;
                }
                else if (progress <= 0.001)
                {
                    reveal = 0.0;
                }
                else
                {
                    reveal = 1.0 - smoothstep(progress - edge, progress + edge, t);
                }
                fragColor = vec4(c.rgb, c.a * clamp(reveal, 0.0, 1.0));
            }
            """, 0F, null)
            .neutralWhen((effect) -> effect.valueF("progress", 0.5F) >= 0.999F)
            .p("progress", "进度", 0F, 1F, 0.5F, 0.01F)
            .p("angle", "方向", -180F, 180F, 0F, 1F)
            .p("softness", "边缘柔和", 0.001F, 0.5F, 0.1F, 0.001F));

        add(new AmEffectDef("am_speed_lines", "速度线", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = (luv - center) * vec2(LayerSize.x / max(LayerSize.y, 1.0), 1.0);
                float radius = length(d);
                float angle = atan(d.y, d.x);
                float lines = pow(abs(sin(angle * max(u_count, 1.0))), max(u_sharpness, 0.5));
                float falloff = smoothstep(u_inner, u_outer, radius);
                float alpha = lines * falloff * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, alpha), c.a);
            }
            """, 0F, "alpha")
            .p("count", "线条数", 2F, 64F, 24F, 1F)
            .p("sharpness", "锐度", 0.5F, 12F, 4F, 0.1F)
            .p("inner", "内半径", 0F, 1F, 0.2F, 0.01F)
            .p("outer", "外半径", 0.01F, 2F, 0.8F, 0.01F)
            .p("alpha", "强度", 0F, 1F, 0.7F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F)
            .c("color", "线条颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_reflection", "倒影", "AM · 合成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float offset = u_distance / max(LayerSize.y, 1.0);
                vec2 mirrored = vec2(luv.x, 2.0 - luv.y + offset);
                vec4 m = sampleLayer(mirrored);
                float fade = 1.0 - clamp((mirrored.y - 1.0) / max(u_fade, 0.001), 0.0, 1.0);
                float amount = m.a * fade * clamp(u_strength, 0.0, 1.0) * (1.0 - c.a);
                vec3 rgb = c.rgb + m.rgb * amount;
                fragColor = vec4(clamp(rgb, 0.0, 1.0), clamp(c.a + amount, 0.0, 1.0));
            }
            """, 0F, "strength")
            .p("distance", "距离（像素）", 0F, 200F, 0F, 1F)
            .p("fade", "渐隐长度", 0.01F, 1F, 0.4F, 0.01F)
            .p("strength", "强度", 0F, 1F, 0.6F, 0.01F));

        add(new AmEffectDef("am_inner_shadow", "内阴影", "AM · 合成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle))) * (u_distance / max(LayerSize, vec2(1.0)));
                float shadow = 0.0;

                for (int i = 0; i < 8; i++)
                {
                    float t = float(i) / 7.0;
                    float a = sampleLayer(luv + dir * t).a;
                    shadow = max(shadow, clamp(c.a - a, 0.0, 1.0));
                }

                fragColor = vec4(mix(c.rgb, u_color.rgb, shadow * clamp(u_opacity, 0.0, 1.0)), c.a);
            }
            """, 0.5F, "opacity")
            .p("distance", "距离（像素）", 0F, 60F, 8F, 0.5F)
            .p("angle", "角度", -180F, 180F, 135F, 1F)
            .p("opacity", "不透明度", 0F, 1F, 0.6F, 0.01F)
            .c("color", "阴影颜色", 0xFF000000));

        add(new AmEffectDef("am_gloss", "高光", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float band = exp(-pow((luv.y - u_position) / max(u_width, 0.001), 2.0));
                float sheen = band * (0.6 + 0.4 * sin(luv.x * 6.2831853 + u_phase));
                vec3 rgb = clamp(c.rgb + u_color.rgb * sheen * clamp(u_intensity, 0.0, 3.0), 0.0, 1.0);
                fragColor = vec4(rgb, c.a);
            }
            """, 0.5F, "intensity")
            .p("position", "位置", 0F, 1F, 0.3F, 0.01F)
            .p("width", "宽度", 0.001F, 0.5F, 0.12F, 0.001F)
            .p("phase", "相位", 0F, 6.2831853F, 0F, 0.01F)
            .p("intensity", "强度", 0F, 3F, 0.8F, 0.01F)
            .c("color", "高光颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_feather", "羽化", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float sum = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = float(i) * 0.5235988;
                    vec2 offset = vec2(cos(a), sin(a)) * (u_radius / max(LayerSize, vec2(1.0)));
                    sum += sampleLayer(luv + offset).a;
                }

                float averaged = sum / 12.0;
                fragColor = vec4(c.rgb, mix(c.a, clamp(averaged * 1.5, 0.0, 1.0), clamp(u_strength, 0.0, 1.0)));
            }
            """, 0.5F, "radius")
            .p("radius", "范围（像素）", 0F, 40F, 4F, 0.5F)
            .p("strength", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_fade", "淡入淡出", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                fragColor = vec4(c.rgb, c.a * clamp(u_progress, 0.0, 1.0));
            }
            """, 0F, null)
            .neutralWhen((effect) -> effect.valueF("progress", 1F) >= 0.999F)
            .p("progress", "进度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_blink", "眨眼", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float phase = fract(Time * max(u_speed, 0.0));
                float closed = step(0.5, phase) * clamp(u_amount, 0.0, 1.0);
                fragColor = vec4(c.rgb, c.a * (1.0 - closed));
            }
            """, 0F, "amount")
            .p("amount", "幅度", 0F, 1F, 1F, 0.01F)
            .p("speed", "速度（次/秒）", 0F, 10F, 2F, 0.01F));

        add(new AmEffectDef("am_halftone_dots", "半调点", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                vec2 p = luv * LayerSize / max(u_spacing, 1.0);
                vec2 cell = floor(p) + 0.5;
                vec2 local = fract(p) - 0.5;
                vec2 cellUv = cell * max(u_spacing, 1.0) / max(LayerSize, vec2(1.0));
                float cellY = clamp(luma(sampleLayer(cellUv).rgb), 0.0, 1.0);
                float radius = 0.55 * sqrt(cellY) * clamp(u_size, 0.05, 1.0);
                float dotMask = smoothstep(radius, radius - 0.08, length(local));
                fragColor = vec4(mix(c.rgb, u_color.rgb, dotMask * clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("spacing", "间距", 4F, 120F, 12F, 1F)
            .p("size", "点大小", 0.05F, 1F, 0.7F, 0.01F)
            .p("alpha", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "点颜色", 0xFF101010));

        add(new AmEffectDef("am_contour_grad", "等高线渐变", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                float wave = sin((y + u_phase) * max(u_frequency, 0.1) * 6.2831853);
                float line = 1.0 - smoothstep(0.0, max(u_thickness, 0.01), abs(wave));
                vec3 grad = mix(u_color1.rgb, u_color2.rgb, y);
                fragColor = vec4(mix(c.rgb, grad, line * clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("frequency", "密度", 0.1F, 30F, 8F, 0.1F)
            .p("thickness", "线宽", 0.01F, 1F, 0.16F, 0.01F)
            .p("phase", "相位", -1F, 1F, 0F, 0.01F)
            .p("alpha", "强度", 0F, 1F, 0.9F, 0.01F)
            .c("color1", "低亮度颜色", 0xFF102030)
            .c("color2", "高亮度颜色", 0xFFFFD070));

        add(new AmEffectDef("am_glow_scan", "光扫", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float position = fract(Time * max(u_speed, 0.0)) * 1.4 - 0.2;
                float coord = (luv.y - position) / max(u_width, 0.001);
                float band = exp(-coord * coord);
                float intensity = band * clamp(u_intensity, 0.0, 4.0);
                fragColor = vec4(clamp(c.rgb + u_color.rgb * intensity, 0.0, 1.0), c.a);
            }
            """, 0F, "intensity")
            .p("width", "宽度", 0.001F, 0.5F, 0.06F, 0.001F)
            .p("speed", "速度（次/秒）", 0F, 5F, 0.4F, 0.01F)
            .p("intensity", "强度", 0F, 4F, 1.2F, 0.01F)
            .c("color", "光带颜色", 0xFFB0E4FF));

        add(new AmEffectDef("am_glow", "发光", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float halo = 0.0;

                for (int i = 0; i < 16; i++)
                {
                    float a = float(i) * 0.3926991;
                    float r = u_radius / max(LayerSize.x, 1.0);
                    halo += sampleLayer(luv + vec2(cos(a), sin(a)) * r).a;
                }

                halo = clamp(halo / 16.0 * 1.8, 0.0, 1.0);
                float intensity = clamp(u_intensity, 0.0, 3.0);
                vec3 rgb = clamp(c.rgb + u_color.rgb * halo * intensity, 0.0, 1.0);
                float alpha = clamp(max(c.a, halo * intensity * 0.7), 0.0, 1.0);
                fragColor = vec4(rgb, alpha);
            }
            """, 0.5F, "intensity")
            .p("radius", "半径（像素）", 0F, 400F, 12F, 0.5F)
            .p("intensity", "强度", 0F, 3F, 0.9F, 0.01F)
            .c("color", "光晕颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_bevel", "平滑斜面", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float left = luma(sampleLayer(luv - vec2(LayerTexel.x, 0.0)).rgb);
                float right = luma(sampleLayer(luv + vec2(LayerTexel.x, 0.0)).rgb);
                float up = luma(sampleLayer(luv - vec2(0.0, LayerTexel.y)).rgb);
                float down = luma(sampleLayer(luv + vec2(0.0, LayerTexel.y)).rgb);
                vec3 normal = normalize(vec3(left - right, up - down, 0.6));
                float shade = clamp(dot(normal, normalize(vec3(-0.4, -0.6, 0.7))), 0.0, 1.0);
                float strength = clamp(u_strength, 0.0, 2.0);
                vec3 rgb = clamp(c.rgb * mix(1.0, shade * 1.6 + 0.2, strength), 0.0, 1.0);
                fragColor = vec4(rgb, c.a);
            }
            """, 0.25F, "strength")
            .p("strength", "强度", 0F, 2F, 0.8F, 0.01F));

        add(new AmEffectDef("am_contour", "轮廓线", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float centre = luma(c.rgb);
                float left = luma(sampleLayer(luv - vec2(LayerTexel.x, 0.0)).rgb);
                float right = luma(sampleLayer(luv + vec2(LayerTexel.x, 0.0)).rgb);
                float up = luma(sampleLayer(luv - vec2(0.0, LayerTexel.y)).rgb);
                float down = luma(sampleLayer(luv + vec2(0.0, LayerTexel.y)).rgb);
                float edge = length(vec2(right - left, down - up));
                float line = smoothstep(u_threshold, u_threshold + max(u_softness, 0.001), edge);
                float alpha = line * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, alpha), c.a);
            }
            """, 0.1F, "alpha")
            .p("threshold", "阈值", 0F, 1F, 0.08F, 0.01F)
            .p("softness", "柔和", 0.001F, 0.5F, 0.08F, 0.001F)
            .p("alpha", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "线条颜色", 0xFF101010));

        add(new AmEffectDef("am_chroma_key", "色度键", "AM · 遮罩", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float distance = length(c.rgb - u_key.rgb);
                float keep = smoothstep(u_range, u_range + max(u_softness, 0.001), distance);
                float alpha = mix(1.0, keep, clamp(u_strength, 0.0, 1.0));
                float spill = clamp(u_spill, 0.0, 1.0);
                vec3 rgb = c.rgb;

                if (spill > 0.0001)
                {
                    float keyDominance = max(u_key.r, max(u_key.g, u_key.b));
                    vec3 neutral = vec3((c.rgb.r + c.rgb.g + c.rgb.b) / 3.0);
                    float amount = (1.0 - keep) * spill * keyDominance * clamp(u_strength, 0.0, 1.0);
                    rgb = mix(c.rgb, neutral, amount);
                }

                fragColor = vec4(rgb, c.a * alpha);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .p("range", "容差", 0F, 1F, 0.25F, 0.01F)
            .p("softness", "边缘柔和", 0.001F, 0.5F, 0.08F, 0.001F)
            .p("spill", "溢色抑制", 0F, 1F, 0.4F, 0.01F)
            .c("key", "键控颜色", 0xFF00FF00));

        add(new AmEffectDef("am_luma_key", "亮度键", "AM · 遮罩", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = luma(c.rgb);
                float keep = smoothstep(u_threshold, u_threshold + max(u_softness, 0.001), y);

                if (u_invert > 0.5)
                {
                    keep = 1.0 - keep;
                }

                float alpha = mix(1.0, keep, clamp(u_strength, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * alpha);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .p("threshold", "阈值", 0F, 1F, 0.5F, 0.01F)
            .p("softness", "边缘柔和", 0.001F, 0.5F, 0.1F, 0.001F)
            .p("invert", "反转", 0F, 1F, 0F, 1F));

        add(new AmEffectDef("am_grid_repeat", "网格平铺", "AM · 重复", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 grid = vec2(max(u_columns, 1.0), max(u_rows, 1.0));
                vec2 local = fract(luv * grid);
                vec2 cell = floor(luv * grid);
                float gap = clamp(u_gap, 0.0, 0.45);

                if (local.x < gap || local.x > 1.0 - gap || local.y < gap || local.y > 1.0 - gap)
                {
                    float alpha = mix(c.a, 0.0, clamp(u_amount, 0.0, 1.0));
                    fragColor = vec4(c.rgb, alpha);

                    return;
                }

                vec2 uv = (local - gap) / max(1.0 - gap * 2.0, 0.001);
                vec4 tiled = sampleLayer(uv);
                fragColor = vec4(mix(c.rgb, tiled.rgb, clamp(u_amount, 0.0, 1.0)),
                    mix(c.a, tiled.a, clamp(u_amount, 0.0, 1.0)));
            }
            """, 0F, "amount")
            .p("columns", "列数", 1F, 16F, 2F, 1F)
            .p("rows", "行数", 1F, 16F, 2F, 1F)
            .p("gap", "间距", 0F, 0.45F, 0.05F, 0.01F)
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_line_repeat", "线条重复", "AM · 重复", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float count = max(u_count, 1.0);
                bool vertical = u_vertical > 0.5;
                float axis = vertical ? luv.y : luv.x;
                float other = vertical ? luv.x : luv.y;
                float coord = fract(axis * count);
                float line = step(coord, clamp(u_duty, 0.01, 1.0));
                vec2 uv = vertical ? vec2(fract(other * count), luv.y) : vec2(luv.x, fract(other * count));
                vec4 repeated = sampleLayer(uv);
                float amount = clamp(u_amount, 0.0, 1.0) * line;
                fragColor = vec4(mix(c.rgb, repeated.rgb, amount), mix(c.a, repeated.a, amount));
            }
            """, 0F, "amount")
            .p("count", "重复数", 1F, 32F, 4F, 1F)
            .p("duty", "占空比", 0.01F, 1F, 0.7F, 0.01F)
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .p("vertical", "垂直方向", 0F, 1F, 1F, 1F));

        add(new AmEffectDef("am_channel_map", "通道映射", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float channels[3];
                channels[0] = c.r;
                channels[1] = c.g;
                channels[2] = c.b;
                int r = int(clamp(u_map_r, 0.0, 2.0));
                int g = int(clamp(u_map_g, 0.0, 2.0));
                int b = int(clamp(u_map_b, 0.0, 2.0));
                fragColor = vec4(channels[r], channels[g], channels[b], c.a);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("map_r", 0F) - 0F) <= 0.0001F
                    && Math.abs(effect.valueF("map_g", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("map_b", 2F) - 2F) <= 0.0001F)
            .p("map_r", "红输出（0 R / 1 G / 2 B）", 0F, 2F, 0F, 1F)
            .p("map_g", "绿输出（0 R / 1 G / 2 B）", 0F, 2F, 1F, 1F)
            .p("map_b", "蓝输出（0 R / 1 G / 2 B）", 0F, 2F, 2F, 1F));

        add(new AmEffectDef("am_channel_map_hsv", "HSV 通道映射", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 hsv = rgb2hsv(c.rgb);
                hsv.x = fract(hsv.x + u_hue_shift / 360.0);
                hsv.y = clamp(hsv.y * max(u_saturation_gain, 0.0), 0.0, 1.0);
                hsv.z = clamp(hsv.z * max(u_value_gain, 0.0), 0.0, 1.0);
                fragColor = vec4(clamp(hsv2rgb(hsv), 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("hue_shift", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("saturation_gain", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("value_gain", 1F) - 1F) <= 0.0001F)
            .p("hue_shift", "色相偏移", -180F, 180F, 0F, 1F)
            .p("saturation_gain", "饱和度倍率", 0F, 3F, 1F, 0.01F)
            .p("value_gain", "明度倍率", 0F, 3F, 1F, 0.01F));

        add(new AmEffectDef("am_fill_behind", "填充背后", "AM · 合成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float fill = (1.0 - c.a) * clamp(u_amount, 0.0, 1.0);
                vec3 rgb = mix(c.rgb, u_color.rgb, fill);
                float alpha = c.a + fill * (1.0 - c.a);
                fragColor = vec4(rgb, clamp(alpha, 0.0, 1.0));
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "填充颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_solid_matte", "实色遮罩", "AM · 合成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                fragColor = vec4(mix(c.rgb, u_color.rgb, clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "实色", 0xFF1A1A1A));

        add(new AmEffectDef("am_matte_fringe", "遮罩边缘", "AM · 合成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float ring = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = float(i) * 0.5235988;
                    vec2 offset = vec2(cos(a), sin(a)) * (u_width / max(LayerSize, vec2(1.0)));
                    ring = max(ring, sampleLayer(luv + offset).a);
                }

                float fringe = clamp(ring - c.a, 0.0, 1.0) * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, fringe), clamp(c.a + fringe, 0.0, 1.0));
            }
            """, 0.25F, "width")
            .p("width", "宽度（像素）", 0F, 20F, 2F, 0.5F)
            .p("alpha", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "边缘颜色", 0xFFFF5533));

        add(new AmEffectDef("am_flicker", "闪烁", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float tick = floor(Time * max(u_speed, 0.0) * 30.0);
                float rnd = hash(vec2(tick, u_seed));
                float alpha = clamp(c.a * (1.0 - u_amount * rnd), 0.0, 1.0);
                fragColor = vec4(c.rgb, alpha);
            }
            """, 0F, "amount")
            .p("amount", "幅度", 0F, 1F, 0.5F, 0.01F)
            .p("speed", "速度", 0F, 30F, 8F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_border", "边框", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 px = luv * LayerSize;
                float distX = min(px.x, LayerSize.x - px.x);
                float distY = min(px.y, LayerSize.y - px.y);
                float d = min(distX, distY);
                float width = max(u_width, 0.0);
                float feather = max(u_feather, 0.001);
                float band = 1.0 - smoothstep(width - feather, width, d);
                fragColor = vec4(mix(c.rgb, u_color.rgb, band * clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0.25F, "width")
            .p("width", "边框宽度", 0F, 200F, 12F, 0.5F)
            .p("feather", "羽化", 0.001F, 50F, 1F, 0.1F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "边框颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_energy_beam", "能量束", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 d = (luv - 0.5) * LayerSize;
                float a = radians(u_angle);
                float s = sin(a);
                float co = cos(a);
                vec2 rotated = vec2(co * d.x - s * d.y, s * d.x + co * d.y);
                float offset = u_offset * LayerSize.y;
                float width = max(u_width, 0.5);
                float beam = exp(-pow((rotated.y - offset) / width, 2.0));
                float ends = smoothstep(0.0, 0.15, abs(rotated.x) / max(LayerSize.x, 1.0));
                float intensity = beam * (1.0 - ends) * clamp(u_intensity, 0.0, 4.0);
                fragColor = vec4(clamp(c.rgb + u_color.rgb * intensity, 0.0, 1.0), c.a);
            }
            """, 0F, "intensity")
            .p("angle", "角度", -180F, 180F, -25F, 1F)
            .p("offset", "偏移", -1F, 1F, 0F, 0.01F)
            .p("width", "宽度", 0.5F, 100F, 14F, 0.5F)
            .p("intensity", "强度", 0F, 4F, 1.2F, 0.01F)
            .c("color", "光束颜色", 0xFF7FD4FF));

        add(new AmEffectDef("am_hex_array", "六边形阵列", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 spacing = vec2(1.0, 1.7320508) * max(u_size, 0.5);
                vec2 p = luv * LayerSize / spacing;
                vec2 a = mod(p, vec2(1.0, 1.7320508)) - vec2(0.5, 0.8660254);
                vec2 b = mod(p + vec2(0.5, 0.0), vec2(1.0, 1.7320508)) - vec2(0.5, 0.8660254);
                vec2 local = dot(a, a) < dot(b, b) ? a : b;
                float edge = max(abs(local.x) / 0.5, abs(local.y) / 0.8660254);
                float glow = smoothstep(1.0 - clamp(u_glow, 0.01, 1.0), 1.0, edge);
                float intensity = glow * clamp(u_intensity, 0.0, 3.0);
                fragColor = vec4(clamp(c.rgb + u_color.rgb * intensity, 0.0, 1.0), c.a);
            }
            """, 0F, "intensity")
            .p("size", "六边形大小", 0.5F, 200F, 36F, 0.1F)
            .p("glow", "线条宽度", 0.01F, 1F, 0.12F, 0.01F)
            .p("intensity", "强度", 0F, 3F, 0.8F, 0.01F)
            .c("color", "线条颜色", 0xFF7FE0FF));

        add(new AmEffectDef("am_color_separate", "彩色分离", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                float dist = length(d);
                vec2 dir = dist > 0.0001 ? d / dist : vec2(0.0);
                vec2 offset = dir * (u_amount / max(LayerSize, vec2(1.0)));
                vec4 c = sampleLayer(luv);
                float r = sampleLayer(luv + offset).r;
                float b = sampleLayer(luv - offset).b;
                fragColor = vec4(r, c.g, b, c.a);
            }
            """, 0.5F, "amount")
            .p("amount", "偏移（像素）", 0F, 60F, 6F, 0.1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_stroke_taper", "描边锥度", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float widthPx = max(u_width * mix(1.0, 1.0 - clamp(u_taper, 0.0, 0.95), clamp(luv.y, 0.0, 1.0)), 0.0);
                float ring = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = float(i) * 0.5235988;
                    vec2 offset = vec2(cos(a), sin(a)) * (widthPx / max(LayerSize, vec2(1.0)));
                    ring = max(ring, sampleLayer(luv + offset).a);
                }

                float outline = clamp(ring - c.a, 0.0, 1.0) * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, outline), clamp(c.a + outline, 0.0, 1.0));
            }
            """, 0.25F, "width")
            .p("width", "宽度", 0F, 40F, 4F, 0.5F)
            .p("taper", "锥度", 0F, 0.95F, 0.6F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "描边颜色", 0xFF222222));

        add(new AmEffectDef("am_radial_rays", "径向光线", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = (luv - center) * vec2(LayerSize.x / max(LayerSize.y, 1.0), 1.0);
                float angle = atan(d.y, d.x);
                float rays = pow(abs(sin(angle * max(u_count, 1.0) + Time * u_speed)), max(u_sharpness, 0.1));
                float falloff = 1.0 - smoothstep(0.0, max(u_radius, 0.001), length(d));
                float alpha = rays * falloff * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, alpha), c.a);
            }
            """, 0F, "alpha")
            .p("count", "光线数", 1F, 48F, 12F, 1F)
            .p("sharpness", "锐度", 0.1F, 12F, 4F, 0.1F)
            .p("radius", "半径", 0.01F, 2F, 1F, 0.01F)
            .p("speed", "旋转速度", 0F, 10F, 0.2F, 0.01F)
            .p("alpha", "强度", 0F, 1F, 0.8F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F)
            .c("color", "光线颜色", 0xFFFFE9B0));

        add(new AmEffectDef("am_dots", "点阵", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 p = luv * max(u_spacing, 1.0) + vec2(u_offset_x, u_offset_y);
                vec2 local = fract(p) - 0.5;
                float radius = clamp(u_size, 0.01, 0.9);
                float dotMask = smoothstep(radius, radius * 0.6, length(local));
                fragColor = vec4(mix(c.rgb, u_color.rgb, dotMask * clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("spacing", "间距", 2F, 200F, 24F, 1F)
            .p("size", "点大小", 0.01F, 0.9F, 0.35F, 0.01F)
            .p("offset_x", "偏移 X", -1F, 1F, 0F, 0.01F)
            .p("offset_y", "偏移 Y", -1F, 1F, 0F, 0.01F)
            .p("alpha", "强度", 0F, 1F, 0.9F, 0.01F)
            .c("color", "点颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_star_shape", "星形", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = (luv - center) * vec2(LayerSize.x / max(LayerSize.y, 1.0), 1.0);
                float angle = atan(d.y, d.x);
                float radius = length(d);
                float points = max(u_points, 3.0);
                float starRadius = max(u_outer, 0.001) * (0.55 + 0.45 * abs(cos(angle * points * 0.5)));
                float inside = 1.0 - smoothstep(starRadius - u_softness, starRadius, radius);
                float shape = inside * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, shape), c.a);
            }
            """, 0F, "alpha")
            .p("points", "角数", 3F, 24F, 5F, 1F)
            .p("outer", "半径", 0.01F, 1F, 0.4F, 0.01F)
            .p("softness", "边缘柔和", 0.001F, 0.2F, 0.01F, 0.001F)
            .p("alpha", "强度", 0F, 1F, 0.9F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F)
            .c("color", "星形颜色", 0xFFFFD35A));

        add(new AmEffectDef("am_smooth_edges", "平滑边缘", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float sum = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = float(i) * 0.5235988;
                    vec2 offset = vec2(cos(a), sin(a)) * (u_radius / max(LayerSize, vec2(1.0)));
                    sum += sampleLayer(luv + offset).a;
                }

                float averaged = sum / 12.0;
                float alpha = mix(c.a, clamp(averaged * 1.6, 0.0, 1.0), clamp(u_strength, 0.0, 1.0));
                fragColor = vec4(c.rgb, alpha);
            }
            """, 0.25F, "radius")
            .p("radius", "范围（像素）", 0F, 40F, 2F, 0.5F)
            .p("strength", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_halftone_lines", "半调线", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = luma(c.rgb);
                float coord = luv.y * max(u_spacing, 1.0) + y * u_modulation;
                float wave = abs(fract(coord) - 0.5) * 2.0;
                float line = 1.0 - smoothstep(u_width, u_width + max(u_softness, 0.001), wave);
                fragColor = vec4(mix(c.rgb, u_color.rgb, line * clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("spacing", "线密度", 2F, 200F, 40F, 1F)
            .p("modulation", "亮度调制", 0F, 40F, 12F, 0.1F)
            .p("width", "线宽", 0.01F, 0.99F, 0.4F, 0.01F)
            .p("softness", "柔和", 0.001F, 0.5F, 0.05F, 0.001F)
            .p("alpha", "强度", 0F, 1F, 0.8F, 0.01F)
            .c("color", "线条颜色", 0xFF101820));

        add(new AmEffectDef("am_rgb_gamma", "RGB 伽马", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 gamma = vec3(max(u_gamma_r, 0.01), max(u_gamma_g, 0.01), max(u_gamma_b, 0.01));
                vec3 graded = pow(max(c.rgb, vec3(0.0)), vec3(1.0) / gamma);
                fragColor = vec4(clamp(mix(c.rgb, graded, clamp(u_amount, 0.0, 1.0)), 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("gamma_r", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("gamma_g", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("gamma_b", 1F) - 1F) <= 0.0001F)
            .p("gamma_r", "红通道伽马", 0.1F, 4F, 1F, 0.01F)
            .p("gamma_g", "绿通道伽马", 0.1F, 4F, 1F, 0.01F)
            .p("gamma_b", "蓝通道伽马", 0.1F, 4F, 1F, 0.01F)
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_sat_vib", "饱和度与自然饱和度", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = luma(c.rgb);
                float saturation = 1.0 + u_saturation;
                vec3 saturated = mix(vec3(y), c.rgb, max(saturation, 0.0));
                float natural = length(c.rgb - vec3(y));
                float vibrance = 1.0 + u_vibrance * (1.0 - clamp(natural * 2.0, 0.0, 1.0));
                vec3 rgb = mix(vec3(y), saturated, max(vibrance, 0.0));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("saturation", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("vibrance", 0F)) <= 0.0001F)
            .p("saturation", "饱和度", -1F, 2F, 0F, 0.01F)
            .p("vibrance", "自然饱和度", -1F, 2F, 0F, 0.01F));

        add(new AmEffectDef("am_spot_color", "单色强调", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 target = rgb2hsv(u_color.rgb);
                vec3 hsv = rgb2hsv(c.rgb);
                float dh = abs(hsv.x - target.x);
                dh = min(dh, 1.0 - dh);
                float keep = 1.0 - smoothstep(u_range * 0.5, u_range * 0.5 + u_softness, dh);
                float y = luma(c.rgb);
                vec3 desaturated = vec3(y);
                vec3 rgb = mix(desaturated, c.rgb, mix(1.0, keep, clamp(u_amount, 0.0, 1.0)));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .p("range", "保留范围", 0.01F, 1F, 0.18F, 0.01F)
            .p("softness", "边缘柔和", 0.001F, 0.5F, 0.08F, 0.001F)
            .c("color", "保留颜色", 0xFFE23A3A));

        add(new AmEffectDef("am_edge_glow", "发光边缘", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float centre = luma(c.rgb);
                float left = luma(sampleLayer(luv - vec2(LayerTexel.x, 0.0)).rgb);
                float right = luma(sampleLayer(luv + vec2(LayerTexel.x, 0.0)).rgb);
                float up = luma(sampleLayer(luv - vec2(0.0, LayerTexel.y)).rgb);
                float down = luma(sampleLayer(luv + vec2(0.0, LayerTexel.y)).rgb);
                float edge = clamp(length(vec2(right - left, down - up)) * 4.0, 0.0, 1.0);
                vec3 rgb = clamp(c.rgb + u_color.rgb * edge * clamp(u_strength, 0.0, 3.0), 0.0, 1.0);
                fragColor = vec4(rgb, c.a);
            }
            """, 0.5F, "strength")
            .p("strength", "强度", 0F, 3F, 1F, 0.01F)
            .c("color", "发光颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_vortex_blur", "漩涡模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 20; i++)
                {
                    float t = (float(i) / 19.0 - 0.5) * radians(u_angle);
                    float c = cos(t);
                    float s = sin(t);
                    vec2 rotated = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
                    sum += sampleLayer(center + rotated);
                }

                fragColor = sum / 20.0;
            }
            """, 0.5F, "angle")
            .p("angle", "角度", -180F, 180F, 30F, 0.5F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_inner_blur", "内模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 16; i++)
                {
                    float a = float(i) * 0.3926991;
                    float r = u_radius / max(LayerSize.x, 1.0);
                    sum += sampleLayer(luv + vec2(cos(a), sin(a)) * r);
                }

                vec4 blurred = sum / 16.0;
                float inside = clamp(c.a, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, blurred.rgb, inside * clamp(u_strength, 0.0, 1.0)), c.a);
            }
            """, 0.5F, "radius")
            .p("radius", "半径（像素）", 0F, 80F, 10F, 1F)
            .p("strength", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_jitter", "抖动", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                float tick = floor(Time * max(u_speed, 0.0));
                vec2 rnd = hash2(vec2(tick, u_seed)) - 0.5;
                vec2 offset = rnd * 2.0 * u_amount / max(LayerSize, vec2(1.0));
                fragColor = sampleLayer(luv + offset);
            }
            """, 0.5F, "amount")
            .p("amount", "幅度（像素）", 0F, 200F, 12F, 0.5F)
            .p("speed", "变化速度", 0F, 60F, 12F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_swing", "摇摆", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                float angle = radians(u_angle) * sin(Time * max(u_speed, 0.0) * 6.2831853 + u_phase);
                vec2 d = luv - center;
                float s = sin(angle);
                float c = cos(angle);
                vec2 rotated = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
                fragColor = sampleLayer(center + rotated);
            }
            """, 0.5F, "angle")
            .p("angle", "角度", -180F, 180F, 12F, 0.5F)
            .p("speed", "速度（次/秒）", 0F, 10F, 1F, 0.01F)
            .p("phase", "相位", 0F, 6.2831853F, 0F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_pulsate", "脉动缩放", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                float scale = 1.0 + u_amount * 0.5 * sin(Time * max(u_speed, 0.0) * 6.2831853 + u_phase);
                vec2 uv = center + (luv - center) / max(scale, 0.01);
                fragColor = sampleLayer(uv);
            }
            """, 0.5F, "amount")
            .p("amount", "幅度", 0F, 2F, 0.2F, 0.01F)
            .p("speed", "速度（次/秒）", 0F, 10F, 1F, 0.01F)
            .p("phase", "相位", 0F, 6.2831853F, 0F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_spin", "旋转", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                float angle = Time * max(u_speed, 0.0) * 6.2831853 + radians(u_phase);
                vec2 d = luv - center;
                float s = sin(angle);
                float c = cos(angle);
                vec2 rotated = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
                fragColor = sampleLayer(center + rotated);
            }
            """, 0.5F, "speed")
            .p("speed", "转速（圈/秒）", -2F, 2F, 0.15F, 0.01F)
            .p("phase", "初始角度", -180F, 180F, 0F, 1F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_random_displace", "随机位移", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 rnd = hash2(vec2(u_seed, u_seed * 1.37)) - 0.5;
                vec2 offset = rnd * 2.0 * u_amount / max(LayerSize, vec2(1.0));
                fragColor = sampleLayer(luv + offset);
            }
            """, 0.5F, "amount")
            .p("amount", "幅度（像素）", 0F, 300F, 20F, 0.5F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_scale_assist", "缩放辅助", "AM · 变换", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 uv = center + (luv - center) / vec2(max(u_scale_x, 0.01), max(u_scale_y, 0.01));
                fragColor = sampleLayer(uv);
            }
            """, 0.5F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("scale_x", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("scale_y", 1F) - 1F) <= 0.0001F)
            .p("scale_x", "缩放 X", 0.01F, 8F, 1F, 0.01F)
            .p("scale_y", "缩放 Y", 0.01F, 8F, 1F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_spin_blur", "旋转模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                float angle = radians(u_angle);
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 24; i++)
                {
                    float t = (float(i) / 23.0 - 0.5) * angle;
                    float c = cos(t);
                    float s = sin(t);
                    vec2 rotated = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
                    sum += sampleLayer(center + rotated);
                }

                fragColor = sum / 24.0;
            }
            """, 0.5F, "angle")
            .p("angle", "角度", -180F, 180F, 20F, 0.5F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_liquify", "液化", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 p = luv * max(u_scale, 0.01) + vec2(u_seed * 5.17);
                float n1 = fbm(p, 4) - 0.5;
                float n2 = fbm(p + 13.7, 4) - 0.5;
                vec2 offset = vec2(n1, n2) * (2.0 * u_strength / max(LayerSize, vec2(1.0)));
                vec4 c = sampleLayer(luv + offset);
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "strength")
            .p("strength", "强度（像素）", 0F, 400F, 40F, 1F)
            .p("scale", "细节", 0.5F, 40F, 6F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_sketch", "素描", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float left = luma(sampleLayer(luv - vec2(LayerTexel.x, 0.0)).rgb);
                float right = luma(sampleLayer(luv + vec2(LayerTexel.x, 0.0)).rgb);
                float up = luma(sampleLayer(luv - vec2(0.0, LayerTexel.y)).rgb);
                float down = luma(sampleLayer(luv + vec2(0.0, LayerTexel.y)).rgb);
                float edge = clamp(length(vec2(right - left, down - up)) * 6.0, 0.0, 1.0);
                float y = luma(c.rgb);
                float ink = clamp((1.0 - y) * 0.8 + edge, 0.0, 1.0);
                vec3 paper = u_paper.rgb;
                vec3 sketch = mix(paper, u_ink.rgb, ink);
                fragColor = vec4(mix(c.rgb, sketch, clamp(u_strength, 0.0, 1.0)), c.a);
            }
            """, 0.25F, "strength")
            .p("strength", "强度", 0F, 1F, 1F, 0.01F)
            .c("paper", "纸张颜色", 0xFFF3ECD8)
            .c("ink", "墨色", 0xFF1A1A1A));

        add(new AmEffectDef("am_chromatic_blur", "彩色模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle))) * (u_strength / max(LayerSize, vec2(1.0)));
                vec3 sum = vec3(0.0);

                for (int i = 0; i < 12; i++)
                {
                    float t = float(i) / 11.0 - 0.5;
                    sum.r += sampleLayer(luv + dir * (t * 1.0)).r;
                    sum.g += sampleLayer(luv + dir * (t * 0.5)).g;
                    sum.b += sampleLayer(luv + dir * (t * 0.0)).b;
                }

                vec4 c = sampleLayer(luv);
                fragColor = vec4(clamp(sum / 12.0, 0.0, 1.0), c.a);
            }
            """, 0.5F, "strength")
            .p("strength", "强度（像素）", 0F, 80F, 12F, 0.5F)
            .p("angle", "角度", -180F, 180F, 0F, 1F));

        add(new AmEffectDef("am_bokeh", "散景", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 bright = vec3(0.0);

                for (int i = 0; i < 24; i++)
                {
                    float a = float(i) * 2.3999632;
                    float r = sqrt(float(i) / 23.0) * u_radius / max(LayerSize.x, 1.0);
                    vec4 sample = sampleLayer(luv + vec2(cos(a), sin(a)) * r);
                    float y = luma(sample.rgb);
                    float weight = smoothstep(u_threshold, u_threshold + 0.15, y) * sample.a;

                    bright += sample.rgb * weight;
                }

                bright /= 24.0;
                fragColor = vec4(clamp(c.rgb + bright * u_intensity, 0.0, 1.0), c.a);
            }
            """, 0.5F, "intensity")
            .p("radius", "半径（像素）", 0F, 80F, 16F, 1F)
            .p("threshold", "亮度阈值", 0F, 1F, 0.65F, 0.01F)
            .p("intensity", "强度", 0F, 3F, 0.8F, 0.01F));

        add(new AmEffectDef("am_lens_flare", "镜头光晕", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 light = vec2(u_light_x, u_light_y);
                vec2 d = (luv - light) * vec2(LayerSize.x / max(LayerSize.y, 1.0), 1.0);
                float dist = length(d);
                float angle = atan(d.y, d.x);
                float rays = pow(abs(sin(angle * max(u_rays, 1.0) + Time * u_speed)), 8.0) * exp(-dist * 6.0);
                float core = smoothstep(max(u_size, 0.001), 0.0, dist);
                float flare = (rays * 0.7 + core) * clamp(u_intensity, 0.0, 4.0);
                vec3 rgb = clamp(c.rgb + u_color.rgb * flare, 0.0, 1.0);
                fragColor = vec4(rgb, c.a);
            }
            """, 0.5F, "intensity")
            .p("light_x", "光源 X", 0F, 1F, 0.7F, 0.01F)
            .p("light_y", "光源 Y", 0F, 1F, 0.3F, 0.01F)
            .p("size", "核心大小", 0.001F, 0.5F, 0.08F, 0.001F)
            .p("rays", "光芒数", 1F, 24F, 6F, 1F)
            .p("speed", "旋转速度", 0F, 10F, 0.4F, 0.01F)
            .p("intensity", "强度", 0F, 4F, 1F, 0.01F)
            .c("color", "光晕颜色", 0xFFFFE6B0));

        add(new AmEffectDef("am_edge_light", "边缘光", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float ring = 0.0;

                for (int i = 0; i < 16; i++)
                {
                    float a = float(i) * 0.3926991;
                    vec2 offset = vec2(cos(a), sin(a)) * (u_radius / max(LayerSize, vec2(1.0)));
                    ring = max(ring, sampleLayer(clamp(luv + offset, 0.0, 1.0)).a);
                }

                float edge = clamp(ring - c.a, 0.0, 1.0) * clamp(u_strength, 0.0, 2.0);
                fragColor = vec4(mix(c.rgb, u_color.rgb, edge), c.a);
            }
            """, 0F, "radius")
            .p("radius", "范围", 0F, 60F, 8F, 1F)
            .p("strength", "强度", 0F, 2F, 1F, 0.01F)
            .c("color", "边缘颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_desaturate", "去色", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = luma(c.rgb);
                fragColor = vec4(mix(c.rgb, vec3(y), clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_monochrome", "单色", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = luma(c.rgb);
                vec3 tinted = u_color.rgb * (0.15 + y * 1.6);
                fragColor = vec4(mix(c.rgb, clamp(tinted, 0.0, 1.0), clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .c("color", "颜色", 0xFFFF7A45));

        add(new AmEffectDef("am_gradient_overlay", "渐变叠加", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float t = clamp(dot(luv - 0.5, dir) * max(u_scale, 0.01) + 0.5 + u_offset, 0.0, 1.0);
                vec3 overlay = mix(u_start.rgb, u_end.rgb, t);
                vec3 base = mix(u_start.rgb, u_end.rgb, t);
                vec3 blended;
                if (u_blend < 0.5) blended = base;
                else if (u_blend < 1.5) blended = 1.0 - (1.0 - c.rgb) * (1.0 - base);
                else if (u_blend < 2.5) blended = c.rgb * base;
                else blended = min(c.rgb + base, vec3(1.0));
                fragColor = vec4(mix(c.rgb, blended, clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("angle", "角度", -180F, 180F, 90F, 1F)
            .p("offset", "偏移", -1F, 1F, 0F, 0.01F)
            .p("scale", "缩放", 0.05F, 4F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("blend", "混合（0 正常 / 1 滤色 / 2 正片 / 3 相加）", 0F, 3F, 0F, 1F)
            .c("start", "起始色", 0xFF1A2B6D)
            .c("end", "结束色", 0xFFFFB86B));

        add(new AmEffectDef("am_radial_gradient", "径向渐变", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 center = vec2(u_center_x, u_center_y);
                float dist = length((luv - center) * vec2(LayerSize.x / max(LayerSize.y, 1.0), 1.0));
                float t = 1.0 - smoothstep(max(u_radius, 0.001) * 0.5, max(u_radius, 0.001), dist);
                vec3 base = mix(u_end.rgb, u_start.rgb, clamp(t, 0.0, 1.0));
                vec3 blended;
                if (u_blend < 0.5) blended = base;
                else if (u_blend < 1.5) blended = 1.0 - (1.0 - c.rgb) * (1.0 - base);
                else if (u_blend < 2.5) blended = c.rgb * base;
                else blended = min(c.rgb + base, vec3(1.0));
                fragColor = vec4(mix(c.rgb, blended, clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F)
            .p("radius", "半径", 0.01F, 2F, 0.7F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("blend", "混合（0 正常 / 1 滤色 / 2 正片 / 3 相加）", 0F, 3F, 0F, 1F)
            .c("start", "中心颜色", 0xFFFFE2A8)
            .c("end", "边缘颜色", 0x00101828));

        add(new AmEffectDef("am_hex_shift", "六边形位移", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 spacing = vec2(1.0, 1.7320508);
                vec2 p = luv * LayerSize / max(u_size, 0.5) / spacing;
                vec2 a = mod(p, spacing) - spacing * 0.5;
                vec2 b = mod(p + vec2(0.5, 0.0), spacing) - spacing * 0.5;
                vec2 local = dot(a, a) < dot(b, b) ? a : b;
                vec2 center = p - local;
                float h = hash(center + u_seed) * 6.2831853;
                vec2 offset = vec2(cos(h), sin(h)) * u_amount / max(LayerSize, vec2(1.0));
                vec4 c = sampleLayer(clamp(luv + offset, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "amount")
            .p("amount", "位移（像素）", 0F, 200F, 20F, 1F)
            .p("size", "六边形大小", 0.5F, 200F, 40F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_ripple_pulse", "波纹扩散", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                float dist = length(d * LayerSize);
                float phase = dist / max(u_wavelength, 1.0) - Time * max(u_speed, 0.0) * 6.2831853;
                float wave = sin(phase) * u_strength;
                float falloff = 1.0 - smoothstep(u_radius * 0.5, u_radius, dist);
                vec2 offset = dist > 0.0001 ? (d / dist) * wave * falloff / max(LayerSize, vec2(1.0)) : vec2(0.0);
                vec4 c = sampleLayer(clamp(luv + offset, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "strength")
            .p("strength", "强度（像素）", 0F, 80F, 10F, 0.5F)
            .p("wavelength", "波长", 4F, 400F, 80F, 1F)
            .p("speed", "速度", 0F, 10F, 0.6F, 0.01F)
            .p("radius", "范围", 0.05F, 1.5F, 1F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_inner_glow", "内发光", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float ring = 0.0;

                for (int i = 0; i < 16; i++)
                {
                    float a = float(i) * 0.3926991;
                    vec2 offset = vec2(cos(a), sin(a)) * (u_radius / max(LayerSize, vec2(1.0)));
                    ring = max(ring, sampleLayer(clamp(luv + offset, 0.0, 1.0)).a);
                }

                float inner = smoothstep(0.0, 1.0, ring) * (1.0 - c.a) * clamp(u_strength, 0.0, 2.0);
                vec3 rgb = clamp(c.rgb + u_color.rgb * inner, 0.0, 1.0);
                fragColor = vec4(rgb, c.a);
            }
            """, 0.5F, "radius")
            .p("radius", "范围", 0F, 400F, 12F, 0.5F)
            .p("strength", "强度", 0F, 2F, 1F, 0.01F)
            .c("color", "发光颜色", 0xFFFFE08A));

        add(new AmEffectDef("am_heat_map", "热力图", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                vec4 stops[5];
                stops[0] = vec4(0.02, 0.0, 0.1, 1.0);
                stops[1] = vec4(0.6, 0.05, 0.2, 1.0);
                stops[2] = vec4(1.0, 0.45, 0.0, 1.0);
                stops[3] = vec4(1.0, 0.9, 0.2, 1.0);
                stops[4] = vec4(1.0, 1.0, 1.0, 1.0);
                float scaled = y * 4.0;
                int index = int(floor(scaled));
                int next = min(index + 1, 4);
                vec3 mapped = mix(stops[index].rgb, stops[next].rgb, fract(scaled));
                fragColor = vec4(mix(c.rgb, mapped, clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_color_tune", "色偏", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 shift = vec3(u_cr, u_mg, u_yb) * 0.5;
                vec3 tinted = clamp(c.rgb + shift, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, tinted, clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .p("cr", "青—红", -1F, 1F, 0F, 0.01F)
            .p("mg", "洋红—绿", -1F, 1F, 0F, 0.01F)
            .p("yb", "黄—蓝", -1F, 1F, 0F, 0.01F));

        add(new AmEffectDef("am_clouds", "云雾", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 p = luv * max(u_scale, 0.1) + vec2(u_seed * 3.7 + Time * u_speed, u_seed * 1.9);
                float n = fbm(p, 5);
                n = clamp((n - 0.5) * max(u_contrast, 0.01) + 0.5, 0.0, 1.0);
                vec3 cloud = mix(u_shadow.rgb, u_highlight.rgb, n);
                fragColor = vec4(mix(c.rgb, cloud, clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0F, "alpha")
            .p("scale", "缩放", 0.2F, 20F, 3F, 0.1F)
            .p("speed", "流动速度", 0F, 4F, 0.3F, 0.01F)
            .p("contrast", "对比度", 0.1F, 4F, 1.4F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 0.8F, 0.01F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F)
            .c("shadow", "暗部颜色", 0xFF1B2436)
            .c("highlight", "亮部颜色", 0xFFF5EEDC));

        add(new AmEffectDef("am_contour_lines", "等高线", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                float wave = sin((y + u_phase) * max(u_frequency, 0.1) * 6.2831853);
                float line = 1.0 - smoothstep(0.0, max(u_thickness, 0.01), abs(wave));
                fragColor = vec4(mix(c.rgb, u_color.rgb, line * clamp(u_alpha, 0.0, 1.0)), c.a);
            }
            """, 0.1F, "alpha")
            .p("frequency", "密度", 0.1F, 30F, 8F, 0.1F)
            .p("thickness", "线宽", 0.01F, 1F, 0.18F, 0.01F)
            .p("phase", "相位", -1F, 1F, 0F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 0.9F, 0.01F)
            .c("color", "线条颜色", 0xFF102030));

        add(new AmEffectDef("am_hex_tile", "六边形平铺", "AM · 重复", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 spacing = vec2(1.0, 1.7320508) * max(u_size, 0.5);
                vec2 p = luv * LayerSize / spacing;
                vec2 a = mod(p, vec2(1.0, 1.7320508)) - vec2(0.5, 0.8660254);
                vec2 b = mod(p + vec2(0.5, 0.0), vec2(1.0, 1.7320508)) - vec2(0.5, 0.8660254);
                vec2 local = dot(a, a) < dot(b, b) ? a : b;
                vec2 center = p - local;
                vec2 sourceUv = center * spacing / max(LayerSize, vec2(1.0));
                vec4 c = sampleLayer(clamp(sourceUv, 0.0, 1.0));
                float edge = max(abs(local.x) / 0.5, abs(local.y) / 0.8660254);
                float gap = smoothstep(1.0 - clamp(u_gap, 0.0, 0.9), 1.0, edge);
                vec3 rgb = mix(c.rgb, u_line.rgb, gap * clamp(u_line_alpha, 0.0, 1.0));
                fragColor = vec4(rgb, c.a);
            }
            """, 0F, null)
            .p("size", "六边形大小", 0.5F, 200F, 40F, 0.1F)
            .p("gap", "缝隙", 0F, 0.9F, 0.1F, 0.01F)
            .p("line_alpha", "缝隙颜色强度", 0F, 1F, 0.6F, 0.01F)
            .c("line", "缝隙颜色", 0xFF0A0E14));

        add(new AmEffectDef("am_curl", "卷曲", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                float radius = max(u_radius, 0.0001);
                float dist = length(d * LayerSize) / max(LayerSize.x, LayerSize.y);
                float falloff = clamp(1.0 - dist / radius, 0.0, 1.0);
                float angle = radians(u_angle) * falloff;
                float s = sin(angle);
                float c = cos(angle);
                vec2 warped = center + mat2(c, s, -s, c) * d;
                vec2 inside = step(vec2(0.0), warped) * step(warped, vec2(1.0));
                vec4 texel = sampleLayer(clamp(warped, 0.0, 1.0));
                fragColor = vec4(texel.rgb, texel.a * inside.x * inside.y);
            }
            """, 0.5F, "angle")
            .p("angle", "卷曲角度", -720F, 720F, 120F, 1F)
            .p("radius", "半径", 0.01F, 1.5F, 0.6F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_tile", "平铺", "AM · 重复", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 uv = (luv - vec2(u_offset_x, u_offset_y)) * max(u_scale, 0.01);
                vec2 cell = floor(uv);
                vec2 local = fract(uv);

                if (u_mirror > 0.5)
                {
                    local.x = mix(local.x, 1.0 - local.x, mod(cell.y, 2.0));
                    local.y = mix(local.y, 1.0 - local.y, mod(cell.x, 2.0));
                }

                vec2 inside = step(vec2(u_gap), local) * step(local, vec2(1.0 - u_gap));
                vec4 c = sampleLayer(clamp(local, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a * inside.x * inside.y);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                Math.abs(effect.valueF("scale", 2F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("offset_x", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("offset_y", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("gap", 0F)) <= 0.0001F
                    && effect.valueF("mirror", 0F) < 0.5F)
            .p("scale", "平铺数", 0.005F, 20F, 2F, 0.005F)
            .p("offset_x", "偏移 X", -1F, 1F, 0F, 0.01F)
            .p("offset_y", "偏移 Y", -1F, 1F, 0F, 0.01F)
            .p("gap", "缝隙", 0F, 0.45F, 0F, 0.005F)
            .p("mirror", "镜像（0/1）", 0F, 1F, 0F, 1F));

        add(new AmEffectDef("am_rotate_tile", "旋转平铺", "AM · 重复", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 p = luv - center;
                float radius = length(p);
                float angle = atan(p.y, p.x) + radians(u_angle);
                float sectors = max(u_count, 1.0);
                float wedge = 6.2831853 / sectors;
                angle = mod(angle, wedge);
                angle = abs(angle - wedge * 0.5);
                vec2 warped = center + vec2(cos(angle), sin(angle)) * radius * max(u_scale, 0.01);
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0F, null)
            .p("count", "扇区数", 1F, 24F, 8F, 1F)
            .p("angle", "旋转", -180F, 180F, 0F, 1F)
            .p("scale", "半径缩放", 0.1F, 4F, 1F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_pulse_opacity", "脉动不透明度", "AM · 透明度", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float pulse = 0.5 + 0.5 * sin(Time * max(u_speed, 0.0) * 6.2831853 + u_phase);
                float alpha = clamp(c.a * (1.0 - clamp(u_amount, 0.0, 1.0) * pulse), 0.0, 1.0);
                fragColor = vec4(c.rgb, alpha);
            }
            """, 0F, "amount")
            .p("amount", "脉动幅度", 0F, 1F, 0.5F, 0.01F)
            .p("speed", "速度（次/秒）", 0F, 10F, 1F, 0.01F)
            .p("phase", "相位", 0F, 6.2831853F, 0F, 0.01F));

        add(new AmEffectDef("am_sepia", "棕褐色", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = luma(c.rgb);
                vec3 sepia = vec3(y * 1.07 + 0.1, y * 0.95 + 0.02, y * 0.72);
                fragColor = vec4(mix(c.rgb, clamp(sepia, 0.0, 1.0), clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_color_bands", "色带", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float levels = max(u_levels, 2.0);
                vec3 quantised = floor(c.rgb * levels + 0.5) / levels;
                fragColor = vec4(clamp(mix(c.rgb, quantised, clamp(u_amount, 0.0, 1.0)), 0.0, 1.0), c.a);
            }
            """, 0F, "amount")
            .p("levels", "色阶数", 2F, 32F, 6F, 1F)
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_oil_paint", "油画", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float radius = max(u_radius, 0.0);
                vec2 stepUv = radius / max(LayerSize, vec2(1.0));
                float bestVariance = 1.0e9;
                vec3 bestColor = c.rgb;

                for (int quadrant = 0; quadrant < 4; quadrant++)
                {
                    vec2 direction = vec2(quadrant == 0 || quadrant == 3 ? -1.0 : 1.0,
                                           quadrant < 2 ? -1.0 : 1.0);
                    vec3 mean = vec3(0.0);
                    vec3 meanSquare = vec3(0.0);

                    for (int i = 1; i <= 4; i++)
                    {
                        vec3 sample = sampleLayer(clamp(luv + direction * stepUv * (float(i) * 0.5), 0.0, 1.0)).rgb;
                        mean += sample;
                        meanSquare += sample * sample;
                    }

                    mean /= 4.0;
                    meanSquare /= 4.0;
                    float variance = dot(meanSquare - mean * mean, vec3(1.0));

                    if (variance < bestVariance)
                    {
                        bestVariance = variance;
                        bestColor = mean;
                    }
                }

                fragColor = vec4(clamp(bestColor, 0.0, 1.0), c.a);
            }
            """, 0.5F, "radius")
            .p("radius", "笔触半径", 0F, 20F, 3F, 0.1F));

        add(new AmEffectDef("am_split_tone", "分离色调", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb) + u_balance * 0.35, 0.0, 1.0);
                vec3 tinted = mix(u_shadow.rgb, u_highlight.rgb, smoothstep(0.0, 1.0, y));
                vec3 rgb = mix(c.rgb, tinted * (0.35 + y * 1.3), clamp(u_amount, 0.0, 1.0));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 0.6F, 0.01F)
            .p("balance", "平衡", -1F, 1F, 0F, 0.01F)
            .c("shadow", "阴影色调", 0xFF1B2A55)
            .c("highlight", "高光色调", 0xFFFFD9A0));

        add(new AmEffectDef("am_duotone", "双色调", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                vec3 mapped = mix(u_dark.rgb, u_light.rgb, y);
                fragColor = vec4(mix(c.rgb, mapped, clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .c("dark", "暗部颜色", 0xFF20143A)
            .c("light", "亮部颜色", 0xFFFFE9B0));

        add(new AmEffectDef("am_palette_map", "色彩映射", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float y = clamp(luma(c.rgb), 0.0, 1.0);
                vec4 palette[8];
                palette[0] = u_color1;
                palette[1] = u_color2;
                palette[2] = u_color3;
                palette[3] = u_color4;
                palette[4] = u_color5;
                palette[5] = u_color6;
                palette[6] = u_color7;
                palette[7] = u_color8;
                float scaled = y * 7.0;
                int index = int(floor(scaled));
                int next = min(index + 1, 7);
                vec3 mapped = mix(palette[index].rgb, palette[next].rgb, fract(scaled));
                fragColor = vec4(mix(c.rgb, mapped, clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("amount", "强度", 0F, 1F, 1F, 0.01F)
            .c("color1", "色标 1", 0xFF050914)
            .c("color2", "色标 2", 0xFF12275A)
            .c("color3", "色标 3", 0xFF2F6FA8)
            .c("color4", "色标 4", 0xFF74C7C1)
            .c("color5", "色标 5", 0xFFF5E29B)
            .c("color6", "色标 6", 0xFFF2A34E)
            .c("color7", "色标 7", 0xFFE2603F)
            .c("color8", "色标 8", 0xFF7C1E2E));

        add(new AmEffectDef("am_hue_shift", "色相偏移", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 hsv = rgb2hsv(c.rgb);
                hsv.x = fract(hsv.x + u_hue / 360.0);
                vec3 shifted = hsv2rgb(hsv);
                fragColor = vec4(mix(c.rgb, shifted, clamp(u_amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("hue", "色相", 0F, 360F, 30F, 0.5F)
            .p("amount", "强度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_light_leak", "漏光", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                float band = dot(luv - 0.5, dir) + 0.5 + u_position;
                float wave = sin(Time * max(u_speed, 0.0) + u_phase) * 0.08;
                float leak = 1.0 - smoothstep(0.0, max(u_width, 0.01), abs(fract(band + wave) - 0.5));
                leak = pow(clamp(leak, 0.0, 1.0), max(u_falloff, 0.01));
                fragColor = vec4(clamp(c.rgb + u_color.rgb * leak * clamp(u_alpha, 0.0, 2.0), 0.0, 1.0), c.a);
            }
            """, 0F, "alpha")
            .p("angle", "角度", -180F, 180F, 45F, 1F)
            .p("position", "位置", -1F, 1F, 0F, 0.01F)
            .p("width", "宽度", 0.01F, 1F, 0.35F, 0.01F)
            .p("falloff", "衰减", 0.1F, 4F, 1.2F, 0.01F)
            .p("speed", "流动速度", 0F, 10F, 0.6F, 0.01F)
            .p("phase", "相位", -6.28F, 6.28F, 0F, 0.01F)
            .p("alpha", "强度", 0F, 2F, 0.8F, 0.01F)
            .c("color", "漏光颜色", 0xFFFFB45A));

        add(new AmEffectDef("am_light_wrap", "光包裹", "AM · 风格化", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float edge = 0.0;
                vec3 spill = vec3(0.0);

                for (int i = 0; i < 12; i++)
                {
                    float a = float(i) * 0.5235988;
                    vec2 offset = vec2(cos(a), sin(a)) * (u_radius / max(LayerSize, vec2(1.0)));
                    edge = max(edge, sampleLayer(luv + offset).a);
                    spill += texture(Comp, clamp(vUv + offset * 0.5, 0.0, 1.0)).rgb;
                }

                spill /= 12.0;
                float wrap = clamp((edge - c.a) * u_strength, 0.0, 1.0);
                vec3 rgb = mix(c.rgb, spill, wrap) + u_color.rgb * wrap * 0.15;
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0.5F, "strength")
            .comp()
            .p("radius", "范围", 0F, 80F, 18F, 1F)
            .p("strength", "强度", 0F, 2F, 1F, 0.01F)
            .c("color", "包裹颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_displace_map", "置换图", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 map = texture(Comp, vUv);
                vec2 offset;

                if (u_mode < 0.5)
                {
                    offset = (map.rg - 0.5) * 2.0;
                }
                else if (u_mode < 1.5)
                {
                    offset = (map.gb - 0.5) * 2.0;
                }
                else
                {
                    float y = luma(map.rgb);
                    offset = vec2(y - 0.5, y - 0.5) * 2.0;
                }

                vec2 uv = luv + offset * (u_amount / max(LayerSize, vec2(1.0)));
                vec4 c = sampleLayer(uv);
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "amount")
            .comp()
            .p("amount", "强度（像素）", 0F, 300F, 40F, 1F)
            .p("mode", "通道（0 RG / 1 GB / 2 亮度）", 0F, 2F, 0F, 1F));

        add(new AmEffectDef("am_gradient_displace", "渐变置换", "AM · 变形", """
            void main()
            {
                float left = luma(texture(Comp, clamp(vUv - vec2(Texel.x, 0.0), 0.0, 1.0)).rgb);
                float right = luma(texture(Comp, clamp(vUv + vec2(Texel.x, 0.0), 0.0, 1.0)).rgb);
                float down = luma(texture(Comp, clamp(vUv - vec2(0.0, Texel.y), 0.0, 1.0)).rgb);
                float up = luma(texture(Comp, clamp(vUv + vec2(0.0, Texel.y), 0.0, 1.0)).rgb);
                vec2 gradient = vec2(right - left, up - down);
                vec2 luv = layerUv(vUv);
                vec2 uv = luv + gradient * (u_strength / max(LayerSize, vec2(1.0)));
                vec4 c = sampleLayer(uv);
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "strength")
            .comp()
            .p("strength", "强度（像素）", 0F, 400F, 60F, 1F));

        add(new AmEffectDef("am_glass", "玻璃", "AM · 风格化", """
            void main()
            {
                vec4 c = texture(Source, vUv);
                float left = luma(texture(Comp, clamp(vUv - vec2(Texel.x, 0.0), 0.0, 1.0)).rgb);
                float right = luma(texture(Comp, clamp(vUv + vec2(Texel.x, 0.0), 0.0, 1.0)).rgb);
                float down = luma(texture(Comp, clamp(vUv - vec2(0.0, Texel.y), 0.0, 1.0)).rgb);
                float up = luma(texture(Comp, clamp(vUv + vec2(0.0, Texel.y), 0.0, 1.0)).rgb);
                vec2 gradient = vec2(right - left, up - down);
                vec2 luv = layerUv(vUv);
                vec2 offset = gradient * (u_strength / max(LayerSize, vec2(1.0)));
                vec3 refracted = sampleLayer(luv + offset).rgb;
                float brightness = dot(refracted, vec3(0.299, 0.587, 0.114));
                /* Both the tint and the highlight must fade with the refraction strength, otherwise
                 * a zero strength still graded the layer (caught by the shader-level zero audit). */
                float weight = clamp(u_strength / 24.0, 0.0, 1.0);
                vec3 tinted = mix(refracted, vec3(brightness) * u_tint.rgb * 2.0,
                    clamp(u_tint_amount, 0.0, 1.0) * weight);
                float shine = clamp(length(gradient) * u_shine, 0.0, 1.0) * weight;
                fragColor = vec4(clamp(tinted + shine, 0.0, 1.0), c.a);
            }
            """, 0.5F, "strength")
            .comp()
            .p("strength", "折射强度", 0F, 200F, 24F, 1F)
            .p("tint_amount", "着色", 0F, 1F, 0.15F, 0.01F)
            .p("shine", "高光", 0F, 4F, 1.2F, 0.01F)
            .c("tint", "玻璃颜色", 0xFF9FD8FF));

        add(new AmEffectDef("am_gaussian_blur", "高斯模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                fragColor = sampleLayer(luv);
            }
            """, 0F, "strength")
            .blur("strength", false, null)
            .p("strength", "半径", 0F, 150F, 12F, 1F));

        add(new AmEffectDef("am_box_blur", "方框模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                fragColor = sampleLayer(luv);
            }
            """, 0F, "strength")
            .blur("strength", true, "iter")
            .p("strength", "半径", 0F, 100F, 8F, 1F)
            .p("iter", "迭代次数", 1F, 4F, 1F, 1F));

        add(new AmEffectDef("am_motion_blur", "动感模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 direction = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                vec2 stepUv = direction * (u_length / max(LayerSize, vec2(1.0))) / 24.0;
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 24; i++)
                {
                    float t = float(i) / 23.0 - 0.5;
                    sum += sampleLayer(clamp(luv + stepUv * (float(i) - 11.5), 0.0, 1.0));
                }

                fragColor = sum / 24.0;
            }
            """, 0.5F, "length")
            .p("length", "长度（像素）", 0F, 300F, 30F, 1F)
            .p("angle", "角度", -180F, 180F, 0F, 1F));

        add(new AmEffectDef("am_zoom_blur", "径向模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 delta = luv - center;
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 24; i++)
                {
                    float t = float(i) / 23.0;
                    float scale = 1.0 + (t - 0.5) * u_strength;
                    sum += sampleLayer(clamp(center + delta * scale, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 radiusUv = vec2(u_radius) / max(LayerSize, vec2(1.0));
                vec4 sum = vec4(0.0);
                float total = 0.0;

                for (int i = 0; i < 32; i++)
                {
                    float a = float(i) * 2.3999632;
                    float r = sqrt(float(i) / 31.0) * u_radius;
                    vec2 point = luv + vec2(cos(a), sin(a)) * r / max(LayerSize, vec2(1.0));
                    float weight = 1.0 - 0.5 * (r / max(u_radius, 0.001));

                    sum += sampleLayer(clamp(point, 0.0, 1.0)) * weight;
                    total += weight;
                }

                fragColor = sum / max(total, 0.0001);
            }
            """, 0.5F, "radius")
            .p("radius", "半径（像素）", 0F, 80F, 12F, 1F));

        add(new AmEffectDef("am_polar", "极坐标", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec2 center = vec2(u_center_x, u_center_y);
                vec2 p = (luv - center) * 2.0;
                float radius = length(p);
                float angle = atan(p.y, p.x) + radians(u_angle);
                vec2 polar = vec2(angle / 6.2831853 * max(u_repeat, 1.0) + 0.5,
                                  radius * max(u_scale, 0.01));
                polar = fract(polar);
                vec2 warped = mix(luv, polar, clamp(u_amount, 0.0, 1.0));
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
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
                vec2 luv = layerUv(vUv);
                vec2 warped = luv;
                vec2 noiseUv = luv * max(u_scale, 0.01) + vec2(u_seed * 7.13);
                float n1 = fbm(noiseUv, 4) - 0.5;
                float n2 = fbm(noiseUv + 13.7, 4) - 0.5;
                warped += vec2(n1, n2) * (u_amount / max(LayerSize, vec2(1.0)));
                vec4 c = sampleLayer(clamp(warped, 0.0, 1.0));
                fragColor = vec4(c.rgb, c.a);
            }
            """, 0.5F, "amount")
            .p("amount", "强度（像素）", 0F, 400F, 48F, 1F)
            .p("scale", "细节", 0.5F, 40F, 6F, 0.1F)
            .p("seed", "随机种子", 0F, 9999F, 0F, 1F));

        add(new AmEffectDef("am_colorize", "色彩化", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
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
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec2 light = vec2(u_light_x, u_light_y);
                vec2 toPixel = luv - light;
                float dist = length(toPixel);
                vec2 direction = dist > 0.0001 ? toPixel / dist : vec2(0.0);
                float size = u_size / max(LayerSize.x, LayerSize.y);
                vec2 shadowUv = clamp(luv + direction * size, 0.0, 1.0);
                vec4 shadowSample = sampleLayer(shadowUv);
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
                vec2 luv = layerUv(vUv);
                vec2 uv = luv;
                vec2 grid = max(vec2(u_size, u_size * max(u_stretch, 0.05)), vec2(1.0));
                vec2 offset = vec2(u_offset_x, u_offset_y);
                vec2 snapped = (floor((uv + offset) * LayerSize / grid) + 0.5) * grid / max(LayerSize, vec2(1.0));
                vec2 d = snapped - 0.5;
                float vignette = 1.0 - smoothstep(0.35, 0.75, length(d));
                float keep = mix(1.0, vignette, clamp(u_vignette, 0.0, 1.0));
                vec4 c = sampleLayer(clamp(snapped, 0.0, 1.0));
                vec3 rgb = c.rgb;
                if (u_saturation != 0.0)
                {
                    rgb = mix(vec3(luma(c.rgb)), c.rgb, clamp(1.0 + u_saturation, 0.0, 2.0));
                }
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a * keep);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                effect.valueF("size", 16F) <= 1.01F
                    && Math.abs(effect.valueF("offset_x", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("offset_y", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("vignette", 0F)) <= 0.0001F
                    && Math.abs(effect.valueF("saturation", 0F)) <= 0.0001F)
            .p("size", "像素大小", 1F, 500F, 16F, 1F)
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

    /** True when this effect samples the composition below its layer. */
    public static boolean usesComp(String id)
    {
        AmEffectDef definition = get(id);

        return definition != null && definition.usesComp;
    }

    /** True when the effect cannot change the input at the current parameter values. */
    public static boolean isNeutral(CompEffect effect)
    {
        AmEffectDef definition = effect == null ? null : get(effect.type);

        if (definition == null)
        {
            return false;
        }

        if (definition.neutralPredicate != null && definition.neutralPredicate.test(effect))
        {
            return true;
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
