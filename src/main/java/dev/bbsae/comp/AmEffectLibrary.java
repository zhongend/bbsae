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

        /* ---- AM repeat family (layer replicators) -------------------------------------------------
         * The reference repeat entries carry no shader: they draw the layer N times with an
         * incremental transform. The pixel bodies below are therefore never executed — the effects
         * are intercepted by EffectRenderer's composition-space REPLICATE pass — but every
         * definition still needs a valid body so the catalogue can compile and probe it like any
         * other data-driven entry. Neutral = a transform that leaves the single copy in place. */
        add(new AmEffectDef("am_repeat", "重复", "AM · 重复", """
            void main()
            {
                fragColor = sampleLayer(layerUv(vUv));
            }
            """, 0F, null)
            .neutralAt("count", 1)
            .neutralAt("offset_x", 0)
            .neutralAt("offset_y", 0)
            .neutralAt("angle", 0)
            .neutralAt("scale", 1)
            .neutralAt("alpha", 1)
            .p("count", "数量", 1F, 32F, 5F, 1F)
            .p("offset_x", "偏移 X（像素）", -2000F, 2000F, 48F, 1F)
            .p("offset_y", "偏移 Y（像素）", -2000F, 2000F, 0F, 1F)
            .p("angle", "角度（每个复制体递增）", -1800F, 1800F, 0F, 0.5F)
            .p("scale", "缩放（每个复制体递增）", 0.01F, 10F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_repeat_radial", "环形重复", "AM · 重复", """
            void main()
            {
                fragColor = sampleLayer(layerUv(vUv));
            }
            """, 0F, null)
            .neutralAt("count", 1)
            .neutralAt("radius", 0)
            .neutralAt("angle", 0)
            .neutralAt("base_scale", 1)
            .neutralAt("scale", 1)
            .neutralAt("offset_x", 0)
            .neutralAt("offset_y", 0)
            .neutralAt("alpha", 1)
            .p("count", "数量", 1F, 32F, 8F, 1F)
            .p("radius", "半径（像素）", 0F, 2000F, 300F, 1F)
            .p("orientation", "起始朝向", -1800F, 1800F, 0F, 0.5F)
            .p("start_angle", "起始角度", -3600F, 3600F, 0F, 0.5F)
            .p("sweep", "扫过角度", 0F, 360F, 360F, 0.5F)
            .p("base_scale", "基础缩放", 0.01F, 10F, 1F, 0.01F)
            .p("offset_x", "内容偏移 X（像素）", -2000F, 2000F, 0F, 1F)
            .p("offset_y", "内容偏移 Y（像素）", -2000F, 2000F, 0F, 1F)
            .p("angle", "角度（每个复制体递增）", -1800F, 1800F, 0F, 0.5F)
            .p("scale", "缩放（每个复制体递增）", 0.01F, 10F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_repeat_scatter", "散布重复", "AM · 重复", """
            void main()
            {
                fragColor = sampleLayer(layerUv(vUv));
            }
            """, 0F, null)
            .neutralAt("count", 1)
            .neutralAt("radius", 0)
            .neutralAt("angle", 0)
            .neutralAt("scale", 1)
            .neutralAt("offset_x", 0)
            .neutralAt("offset_y", 0)
            .neutralAt("alpha", 1)
            .p("count", "数量", 1F, 32F, 12F, 1F)
            .p("radius", "散布半径（像素）", 0F, 2000F, 300F, 1F)
            .p("evolution", "演化", -999F, 999F, 0F, 0.01F)
            .p("scatter_seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("offset_x", "内容偏移 X（像素）", -2000F, 2000F, 0F, 1F)
            .p("offset_y", "内容偏移 Y（像素）", -2000F, 2000F, 0F, 1F)
            .p("angle", "角度（每个复制体递增）", -1800F, 1800F, 0F, 0.5F)
            .p("scale", "缩放（每个复制体递增）", 0.01F, 10F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F));

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

        /* AM "RGB Mixer": each output channel is a mix of the three input channels. Identity is the
         * "red→red, green→green, blue→blue" triple (the same convention as the channel map), so the
         * defaults follow the reference and the zero contract is declared on that triple. */
        add(new AmEffectDef("am_rgb_mixer", "RGB 混合", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 mixed = c.r * u_rmix.rgb + c.g * u_gmix.rgb + c.b * u_bmix.rgb;
                mixed = pow(max(mixed, vec3(0.0)), vec3(1.0 / max(u_gamma, 0.01)));
                mixed += vec3(u_offset);
                fragColor = vec4(clamp(mixed, 0.0, 1.0), c.a);
            }
            """, 0F, null)
            .neutralWhen((effect) ->
                effect.color("rmix", 0xFFFF0000) == 0xFFFF0000
                    && effect.color("gmix", 0xFF00FF00) == 0xFF00FF00
                    && effect.color("bmix", 0xFF0000FF) == 0xFF0000FF
                    && Math.abs(effect.valueF("gamma", 1F) - 1F) <= 0.0001F
                    && Math.abs(effect.valueF("offset", 0F)) <= 0.0001F)
            .p("gamma", "伽马", 0.01F, 9.99F, 1F, 0.005F)
            .p("offset", "亮度偏移", -0.9F, 0.9F, 0F, 0.01F)
            .c("rmix", "红色混合", 0xFFFF0000)
            .c("gmix", "绿色混合", 0xFF00FF00)
            .c("bmix", "蓝色混合", 0xFF0000FF));

        /* AM solid colour fill: replaces the layer colour with one colour, optionally multiplied
         * into or screened over the content. Alpha 0 is the identity, so it has a real zero. */
        add(new AmEffectDef("am_solid_color", "纯色", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                vec3 color = u_color.rgb;
                vec3 blended = u_blend < 0.5 ? color
                    : u_blend < 1.5 ? c.rgb * color
                    : 1.0 - (1.0 - c.rgb) * (1.0 - color);
                float amount = clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, blended, amount), c.a);
            }
            """, 0F, "alpha")
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("blend", "混合（0 正常 / 1 相乘 / 2 滤色）", 0F, 2F, 0F, 1F)
            .c("color", "颜色", 0xFF2D1EF6));

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
                vec4 c = sampleLayer(luv);

                if (abs(u_angle) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

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
            .p("angle", "角度", 0F, 60F, 5F, 0.1F)
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

        /* ---- P1 batch: reference blur / colour / distort entries (0.94) --------------------------
         * Same data-driven path as every other entry. Bodies are single-pass approximations of the
         * reference fragment programs: the reference feeds pre-blurred buffers (buffer textures) to
         * the glow/unsharp families; here the body samples its own source with a small tap loop,
         * which keeps the effect inside the ordinary AmEffectDef contract. Every body starts with an
         * explicit identity branch for its declared neutral value so the shader-level identity audit
         * is exact (not just "close enough"). */

        add(new AmEffectDef("am_unsharp_mask", "USM 锐化", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_amount <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float radius = max(u_strength, 0.0) * 60.0;
                vec2 step = radius / max(LayerSize, vec2(1.0));
                vec4 sum = vec4(0.0);
                float weight = 0.0;

                for (int i = 0; i < 9; i++)
                {
                    float t = float(i) / 8.0 - 0.5;
                    float w = 1.0 - abs(t) * 2.0;

                    sum += sampleLayer(luv + vec2(t) * step) * w;
                    weight += w;
                }

                vec4 blurred = sum / max(weight, 0.0001);
                float detail = dot(c.rgb - blurred.rgb, vec3(0.3333));
                float gate = abs(detail) >= u_threshold * 0.02 ? 1.0 : 0.0;
                vec3 rgb = c.rgb + (c.rgb - blurred.rgb) * u_amount * 2.0 * gate;
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "amount")
            .p("strength", "模糊半径", 0F, 2F, 0.02F, 0.01F)
            .p("amount", "锐化量", 0F, 2F, 0.5F, 0.01F)
            .p("threshold", "阈值", -0.5F, 1F, 0.5F, 0.001F));

        add(new AmEffectDef("am_lens_blur_2", "镜头模糊 2（对焦）", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 center = vec2(u_cx, u_cy);
                vec2 d = luv - center;
                float dist = length(d);
                float edge = max(min(1.0, u_radius + 0.25), u_radius + 0.001);
                float focus = smoothstep(u_radius, edge, dist);
                float amount = clamp(u_strength, 0.0, 1.0) * focus;
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 10; i++)
                {
                    float t = float(i) / 9.0;

                    sum += sampleLayer(center + d * (1.0 - t * amount));
                }

                fragColor = sum / 10.0;
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 0.15F, 0.005F)
            .p("radius", "对焦半径", 0F, 1F, 0.15F, 0.005F)
            .p("cx", "中心 X", 0F, 1F, 0.5F, 0.005F)
            .p("cy", "中心 Y", 0F, 1F, 0.5F, 0.005F));



        add(new AmEffectDef("am_streak_strips", "条纹拖影", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 dir = vec2(cos(radians(u_angle)), sin(radians(u_angle)));
                vec2 step = dir * (max(u_amount, 0.0) / max(LayerSize, vec2(1.0))) / 20.0;
                vec4 sum = vec4(0.0);
                float weight = 0.0;

                for (int i = 0; i < 20; i++)
                {
                    float t = float(i) / 19.0 - 0.5;
                    float fade = 1.0 - abs(t) * 2.0;
                    float strip = 0.5 + 0.5 * sin(float(i) * max(u_phase, 0.0) * 0.05 + Time * 0.5);

                    sum += sampleLayer(luv + step * t) * fade * mix(1.0, strip, clamp(u_phase * 0.01, 0.0, 1.0));
                    weight += fade;
                }

                vec4 streak = sum / max(weight, 0.0001);
                fragColor = vec4(mix(c.rgb, streak.rgb, u_alpha), c.a);
            }
            """, 0F, "alpha")
            .p("amount", "拖影长度（像素）", 0.5F, 500F, 10F, 0.1F)
            .p("angle", "角度", 0F, 3600F, 45F, 0.5F)
            .p("phase", "相位", 0F, 1000F, 0F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F));

        add(new AmEffectDef("am_warp_blur", "湍流模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_intensity <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = luv * max(u_scale, 0.01) + vec2(u_seed, u_evolution * 0.1);
                float n = fbm(p * 2.0 + Time * 0.1, 3) - 0.5;
                vec2 dir;

                if (u_direction < 0.5)
                {
                    dir = vec2(1.0, 0.0);
                }
                else if (u_direction < 1.5)
                {
                    dir = vec2(0.0, 1.0);
                }
                else if (u_direction < 2.5)
                {
                    dir = normalize(luv - 0.5 + vec2(0.0001));
                }
                else
                {
                    vec2 d = luv - 0.5;

                    dir = normalize(vec2(-d.y, d.x) + vec2(0.0001));
                }

                vec2 step = dir * (u_intensity * 40.0) / max(LayerSize, vec2(1.0));
                step *= vec2(max(u_stretch_x, 0.01), max(u_stretch_y, 0.01));
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 16; i++)
                {
                    float t = float(i) / 15.0 - 0.5;

                    sum += sampleLayer(clamp(luv + step * (t + n * u_offs), 0.0, 1.0));
                }

                fragColor = sum / 16.0;
            }
            """, 0F, "intensity")
            .p("intensity", "强度", 0F, 2.5F, 0.25F, 0.001F)
            .p("evolution", "演化", -50F, 50F, 0F, 0.01F)
            .p("direction", "方向（0 水平/1 垂直/2 径向/3 切向）", 0F, 3F, 0F, 1F)
            .p("scale", "噪声缩放", 0.01F, 50F, 1F, 0.01F)
            .p("stretch_x", "拉伸 X", 0.01F, 10F, 1F, 0.01F)
            .p("stretch_y", "拉伸 Y", 0.01F, 10F, 1F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("offs", "偏移强度", -1F, 1F, 0F, 0.001F));

        add(new AmEffectDef("am_chromatic_zoom_blur", "彩色径向模糊", "AM · 模糊", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                vec2 dir = d / max(length(d), 0.0001);
                vec2 step = dir * u_strength * 0.05;
                vec3 sum = vec3(0.0);

                for (int i = 0; i < 12; i++)
                {
                    float t = float(i) / 11.0 - 0.5;
                    vec4 s = sampleLayer(luv - step * t);
                    vec3 chroma = t < 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);

                    sum += mix(s.rgb, s.rgb * (chroma * 2.0 + vec3(0.2)), 0.35);
                }

                fragColor = vec4(clamp(sum / 12.0, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 2F, 0.15F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_spectral_map", "光谱映射", "AM · 调色", """
            float mapChannel(float p, float f)
            {
                float t = mod(p + 10.0, 1.0);

                return clamp(abs(t - 0.5) * 4.0 - f, 0.0, 1.0);
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float lum = luma(c.rgb);
                float phase = lum * max(u_repeat, 0.1) + u_phase * 0.01;
                float squeeze = clamp(u_squeeze, 0.0, 0.99);
                vec3 spectral = mapChannel(phase, squeeze) * u_redColor.rgb
                    + mapChannel(phase + 0.333, squeeze) * u_greenColor.rgb
                    + mapChannel(phase + 0.666, squeeze) * u_blueColor.rgb;
                vec3 rgb = mix(c.rgb, clamp(spectral, 0.0, 1.0), clamp(u_alpha, 0.0, 1.0));
                float grey = luma(rgb);

                rgb = mix(vec3(grey), rgb, 1.0 + clamp(u_saturation, -0.99, 1.0));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "alpha")
            .p("phase", "相位", -99F, 99F, 0F, 0.005F)
            .p("repeat", "重复", 0.1F, 10F, 1F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("squeeze", "色带收缩", 0F, 1F, 0F, 0.01F)
            .p("saturation", "饱和度", -1F, 1F, 0F, 0.005F)
            .c("redColor", "红映射色", 0xFFFF0000)
            .c("greenColor", "绿映射色", 0xFF00FF00)
            .c("blueColor", "蓝映射色", 0xFF0000FF));

        add(new AmEffectDef("am_soft_glow", "柔光", "AM · 调色", """
            vec4 glowSample(vec2 luv, float radiusPx)
            {
                vec2 texel = radiusPx / max(LayerSize, vec2(1.0));
                vec4 sum = vec4(0.0);
                float weight = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = 6.2831853 * float(i) / 12.0;
                    float r = 0.5 + 0.5 * mod(float(i), 2.0);
                    float w = 1.0 - 0.4 * r;

                    sum += sampleLayer(clamp(luv + vec2(cos(a), sin(a)) * texel * r, 0.0, 1.0)) * w;
                    weight += w;
                }

                return sum / max(weight, 0.0001);
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec4 blur = glowSample(luv, clamp(u_strength, 0.0, 2.0) * 60.0);
                vec3 adj = min(blur.rgb * max(u_brightness, 0.0), vec3(1.0));
                float lum = min(1.0, luma(adj) * max(u_brightness, 0.0));
                vec3 blendColor = mix(adj, lum * u_color.rgb, clamp(u_blend, 0.0, 1.0));

                blendColor = mix(blendColor, vec3(lum), lum * clamp(u_highlights, 0.0, 1.0));
                blendColor *= mix(vec3(1.0), blendColor, clamp(u_contrast, 0.0, 1.0));

                vec3 inside = clamp(blendColor, 0.0, 1.0) * clamp(u_alpha, 0.0, 1.0);
                vec3 rgb = 1.0 - (1.0 - c.rgb) * (1.0 - inside);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 2F, 0.1F, 0.01F)
            .p("brightness", "亮度", 0F, 2F, 1F, 0.01F)
            .p("contrast", "对比度", 0F, 1F, 0.2F, 0.01F)
            .p("highlights", "高光", 0F, 1F, 0.5F, 0.01F)
            .p("blend", "着色混合", 0F, 1F, 0.25F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "光晕颜色", 0xFFFF5566));

        add(new AmEffectDef("am_dark_glow", "暗部发光", "AM · 调色", """
            vec4 glowSample(vec2 luv, float radiusPx)
            {
                vec2 texel = radiusPx / max(LayerSize, vec2(1.0));
                vec4 sum = vec4(0.0);
                float weight = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = 6.2831853 * float(i) / 12.0;
                    float r = 0.5 + 0.5 * mod(float(i), 2.0);
                    float w = 1.0 - 0.4 * r;

                    sum += sampleLayer(clamp(luv + vec2(cos(a), sin(a)) * texel * r, 0.0, 1.0)) * w;
                    weight += w;
                }

                return sum / max(weight, 0.0001);
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec4 blur = glowSample(luv, clamp(u_strength, 0.0, 2.0) * 80.0);
                float lum = luma(blur.rgb);
                float mask = 1.0 - smoothstep(u_threshold, u_threshold + 0.15, lum);
                vec3 tinted = blur.rgb * mix(vec3(1.0), u_color.rgb, clamp(u_blend, 0.0, 1.0));
                float amount = mask * clamp(u_strength, 0.0, 2.0) * 0.5 * clamp(u_alpha, 0.0, 1.0);
                fragColor = vec4(clamp(mix(c.rgb, tinted, clamp(amount, 0.0, 1.0)), 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 2F, 0.25F, 0.005F)
            .p("threshold", "暗部阈值", 0F, 1F, 0.3F, 0.001F)
            .p("blend", "着色混合", 0F, 1F, 0.25F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 0.75F, 0.01F)
            .c("color", "光晕颜色", 0xFFFF5566));

        add(new AmEffectDef("am_light_glow", "亮部发光", "AM · 调色", """
            vec4 glowSample(vec2 luv, float radiusPx)
            {
                vec2 texel = radiusPx / max(LayerSize, vec2(1.0));
                vec4 sum = vec4(0.0);
                float weight = 0.0;

                for (int i = 0; i < 12; i++)
                {
                    float a = 6.2831853 * float(i) / 12.0;
                    float r = 0.5 + 0.5 * mod(float(i), 2.0);
                    float w = 1.0 - 0.4 * r;

                    sum += sampleLayer(clamp(luv + vec2(cos(a), sin(a)) * texel * r, 0.0, 1.0)) * w;
                    weight += w;
                }

                return sum / max(weight, 0.0001);
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec4 blur = glowSample(luv, clamp(u_strength, 0.0, 2.0) * 70.0);
                float lum = luma(blur.rgb);
                float mask = smoothstep(u_threshold, min(1.0, u_threshold + 0.2), lum);
                vec3 tinted = blur.rgb * mix(vec3(1.0), u_color.rgb, clamp(u_blend, 0.0, 1.0));
                float amount = mask * clamp(u_intensity, 0.0, 5.0) * 0.5 * clamp(u_alpha, 0.0, 1.0);
                vec3 halo = tinted * mask * clamp(u_halo, 0.0, 5.0) * 0.3;
                vec3 rgb = mix(c.rgb, tinted, clamp(amount, 0.0, 1.0));

                rgb = 1.0 - (1.0 - rgb) * (1.0 - clamp(halo, 0.0, 1.0));
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 2F, 0.25F, 0.005F)
            .p("threshold", "亮部阈值", 0F, 1F, 0.7F, 0.001F)
            .p("intensity", "亮度", 0F, 5F, 1F, 0.01F)
            .p("blend", "着色混合", 0F, 1F, 0.25F, 0.01F)
            .p("halo", "光晕", 0F, 5F, 0F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 0.75F, 0.01F)
            .c("color", "光晕颜色", 0xFFFF5566));

        add(new AmEffectDef("am_color_hot", "热色", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_intensity <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float lum = pow(clamp(luma(c.rgb), 0.0, 1.0), 1.0 / max(u_gamma, 0.01));
                vec3 warm = mix(vec3(lum), u_color.rgb * (0.4 + lum * 0.9), clamp(u_intensity, 0.0, 2.0) * 0.5);
                float hot = clamp(lum * 1.4, 0.0, 1.0);
                vec3 rgb = mix(c.rgb, warm + vec3(lum * lum * 0.4), clamp(u_intensity, 0.0, 1.0) * hot);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "intensity")
            .p("intensity", "强度", 0F, 2F, 1F, 0.01F)
            .p("gamma", "亮度伽马", 0.01F, 9.99F, 1F, 0.005F)
            .c("color", "暖色", 0xFFFF5522));

        add(new AmEffectDef("am_pinch_bulge_2", "收缩与膨胀 2", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (abs(u_strength) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 center = vec2(u_center_x, u_center_y);
                float aspect = max(LayerSize.x, 1.0) / max(LayerSize.y, 1.0);
                vec2 uv = luv - center;

                uv.x *= aspect;
                float dist = length(uv);
                float radius = max(u_radius, 0.0001);

                if (dist < radius)
                {
                    float p = dist / radius;

                    if (u_strength > 0.0)
                    {
                        uv *= mix(1.0, smoothstep(0.0, radius / max(dist, 0.0001), p), u_strength * 0.75);
                    }
                    else
                    {
                        uv *= mix(1.0, pow(p, 1.0 + u_strength * 0.75) * radius / max(dist, 0.0001), 1.0 - p);
                    }
                }

                uv.x /= aspect;
                fragColor = sampleLayer(center + uv);
            }
            """, 0F, "strength")
            .p("strength", "强度", -1F, 1F, -0.9F, 0.01F)
            .p("radius", "半径", 0F, 2.5F, 0.3F, 0.005F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_swirl_4", "旋转扭曲 2", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (abs(u_strength) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 center = vec2(u_center_x, u_center_y);
                vec2 d = luv - center;
                float dist = length(d);
                float falloff = 1.0 - smoothstep(0.0, max(u_radius, 0.0001), dist);
                float angle = u_strength * 6.2831853 * falloff;
                fragColor = sampleLayer(center + rot(angle) * d);
            }
            """, 0F, "strength")
            .p("strength", "强度", -0.5F, 0.5F, 0.1F, 0.001F)
            .p("radius", "半径", 0F, 0.8F, 0.3F, 0.01F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_hex_rotate", "六边形旋转", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (abs(u_rotate) + abs(u_warp_angle) + abs(u_angle) + abs(u_twirl) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 spacing = vec2(1.0, 1.7320508) * max(u_size, 0.5);
                vec2 p = luv * max(LayerSize, vec2(1.0)) / spacing;
                vec2 a = mod(p, vec2(1.0, 1.7320508)) - vec2(0.5, 0.8660254);
                vec2 b = mod(p + vec2(0.5, 0.0), vec2(1.0, 1.7320508)) - vec2(0.5, 0.8660254);
                vec2 local = dot(a, a) < dot(b, b) ? a : b;
                vec2 center = p - local;
                vec2 norm = local / vec2(0.5, 0.8660254);
                float angle = radians(u_rotate)
                    + radians(u_warp_angle) * (hash(center + u_seed) - 0.5) * 2.0
                    + radians(u_angle) * (mod(center.x + center.y, 2.0) - 1.0)
                    + radians(u_twirl) * length(norm);
                vec2 rotated = rot(angle) * (norm * vec2(max(u_stretch_x, 0.01), max(u_stretch_y, 0.01)));
                vec2 sourceLocal = rotated * vec2(0.5, 0.8660254) + center;
                vec2 sourceUv = sourceLocal * spacing / max(LayerSize, vec2(1.0)) + vec2(u_position_x, u_position_y);
                fragColor = sampleLayer(sourceUv);
            }
            """, 0F, null)
            .neutralAt("rotate", 0)
            .neutralAt("warp_angle", 0)
            .neutralAt("angle", 0)
            .neutralAt("twirl", 0)
            .neutralAt("position_x", 0)
            .neutralAt("position_y", 0)
            .p("rotate", "整体旋转", 0F, 3600F, 45F, 0.5F)
            .p("warp_angle", "随机旋转幅度", -3600F, 3600F, 0F, 1F)
            .p("position_x", "位置 X", -1F, 1F, 0F, 0.01F)
            .p("position_y", "位置 Y", -1F, 1F, 0F, 0.01F)
            .p("size", "六边形大小（像素）", 0.5F, 200F, 10F, 0.1F)
            .p("stretch_x", "拉伸 X", 0.01F, 10F, 1F, 0.01F)
            .p("stretch_y", "拉伸 Y", 0.01F, 10F, 1F, 0.01F)
            .p("angle", "交替旋转", 0F, 3600F, 0F, 0.5F)
            .p("twirl", "扭转", 0F, 3600F, 0F, 1F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F));

        /* ---- 0.95 batch: remaining reference distort family ------------------------------------
         * All data-driven. `omino_glass` is a simplified version of the reference glass shader: the
         * reference refracts the composition below the layer through a buffer/noise pipeline; here
         * the same refraction/noise/lighting math runs on the layer's own pixels (no extra texture
         * inputs), so it works identically in 2D, in the 3D bake and in the export. */

        add(new AmEffectDef("am_pinch_bulge_inside", "内部收缩与膨胀", "AM · 变形", """
            float gsmooth(float low, float high, float value)
            {
                float p = clamp((value - low) / max(high - low, 0.0001), 0.0, 1.0);

                p -= 1.0;

                return pow(2.71828, -((p * p) / 0.125));
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (abs(u_strength) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float aspect = max(LayerSize.x, 1.0) / max(LayerSize.y, 1.0);
                vec2 center = vec2(u_center_x - 0.5, u_center_y - 0.5) * vec2(aspect, 1.0);
                vec2 st = luv - 0.5;

                st.x *= aspect;
                st -= center;

                float dist = length(st);
                float radius = max(u_radius, 0.0001);

                if (dist < radius)
                {
                    float p = dist / radius;
                    vec2 previous = st;

                    if (u_strength > 0.0)
                    {
                        st *= mix(1.0, gsmooth(0.0, radius / max(dist, 0.0001), p), u_strength * 0.75);
                    }
                    else
                    {
                        st *= mix(1.0, pow(p, 1.0 + u_strength * 0.75) * radius / max(dist, 0.0001), 1.0 - p);
                    }

                    float feather = clamp(u_feather, 0.0001, 0.49);
                    float damping = smoothstep(0.0, feather, luv.x) * smoothstep(0.0, feather, luv.y)
                        * (1.0 - smoothstep(1.0 - feather, 1.0, luv.x))
                        * (1.0 - smoothstep(1.0 - feather, 1.0, luv.y));

                    st = mix(previous, st, damping);
                }

                st += center;
                st.x /= aspect;
                fragColor = sampleLayer(clamp(st + 0.5, 0.0, 1.0));
            }
            """, 0F, "strength")
            .p("strength", "强度", -1F, 1F, 0.5F, 0.01F)
            .p("radius", "半径", 0F, 2.5F, 0.3F, 0.005F)
            .p("feather", "羽化", 0F, 1F, 0.1F, 0.005F)
            .p("center_x", "中心 X", 0F, 1F, 0.5F, 0.01F)
            .p("center_y", "中心 Y", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_wave_warp", "波浪扭曲", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (abs(u_m2) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float a1 = radians(u_a1d);
                float a2 = radians(u_a1d + u_a2d);
                vec2 rawv = vec2(cos(a1), -sin(a1));
                float rawp = dot(luv, rawv);
                float spaceDamp = 1.0;

                if (abs(u_damping_space) > 0.0001)
                {
                    float d = clamp(abs(rawp - u_damping_origin), 0.0, 1.0);

                    spaceDamp = u_damping_space > 0.0
                        ? 1.0 - (1.0 - d) * u_damping_space
                        : 1.0 - d * (0.0 - u_damping_space);
                }

                float space = max(u_m1, 0.0) * max(spaceDamp, 0.0);
                vec2 v = vec2(cos(a1), -sin(a1)) * space;
                float p = dot(luv, v);
                float ddist = abs(p / max(space, 0.0001));
                float damp = 1.0;

                if (abs(u_damping) > 0.0001)
                {
                    float d = clamp(abs(ddist - u_damping_origin), 0.0, 1.0);

                    damp = u_damping > 0.0
                        ? 1.0 - (1.0 - d) * u_damping
                        : 1.0 - d * (0.0 - u_damping);
                }

                vec2 offset = vec2(cos(a2), -sin(a2)) * (u_m2 * max(damp, 0.0)) / 100.0;

                offset *= sin(p + u_phase * 6.28318);
                fragColor = sampleLayer(clamp(luv + offset, 0.0, 1.0));
            }
            """, 0F, "m2")
            .p("phase", "相位", 0F, 500F, 0F, 0.01F)
            .p("a1d", "波纹方向角", -1800F, 1800F, 0F, 0.5F)
            .p("m1", "波长", 0F, 500F, 20F, 0.1F)
            .p("m2", "波幅", 0F, 30F, 4F, 0.1F)
            .p("a2d", "偏移方向角", -180F, 180F, 90F, 0.5F)
            .p("damping", "距离阻尼", -1F, 1F, 0F, 0.01F)
            .p("damping_space", "空间阻尼", -1F, 1F, 0F, 0.01F)
            .p("damping_origin", "阻尼原点", 0F, 1F, 0.5F, 0.01F));

        add(new AmEffectDef("am_turbulent_displace_3", "湍流置换 3", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_intensity <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = (luv + vec2(u_position_x, u_position_y) * 0.01) * max(u_scale, 0.01)
                    + vec2(u_seed * 3.17, u_evolution * 0.1);
                vec2 dir;

                if (u_direction < 0.5)
                {
                    dir = vec2(1.0, 0.0);
                }
                else if (u_direction < 1.5)
                {
                    dir = vec2(0.0, 1.0);
                }
                else if (u_direction < 2.5)
                {
                    dir = normalize(luv - 0.5 + vec2(0.0001));
                }
                else
                {
                    vec2 d = luv - 0.5;

                    dir = normalize(vec2(-d.y, d.x) + vec2(0.0001));
                }

                int octaves = int(clamp(u_octaves, 1.0, 9.0));
                float n = fbm(p + vec2(u_offs), octaves) - 0.5;
                vec2 step = dir * (u_intensity * 40.0 * n) / max(LayerSize, vec2(1.0));

                step *= vec2(max(u_stretch_x, 0.01), max(u_stretch_y, 0.01));
                fragColor = sampleLayer(clamp(luv + step, 0.0, 1.0));
            }
            """, 0F, "intensity")
            .p("intensity", "强度", 0F, 2.5F, 0.25F, 0.001F)
            .p("evolution", "演化", -50F, 50F, 0F, 0.01F)
            .p("direction", "方向（0 水平/1 垂直/2 径向/3 切向）", 0F, 3F, 0F, 1F)
            .p("scale", "噪声缩放", 0.01F, 50F, 1F, 0.01F)
            .p("stretch_x", "拉伸 X", 0.01F, 10F, 1F, 0.01F)
            .p("stretch_y", "拉伸 Y", 0.01F, 10F, 1F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("octaves", "细节层数", 1F, 9F, 6F, 1F)
            .p("offs", "相位偏移", -1F, 1F, 0F, 0.001F)
            .p("position_x", "位置 X", -1F, 1F, 0F, 0.01F)
            .p("position_y", "位置 Y", -1F, 1F, 0F, 0.01F));

        add(new AmEffectDef("am_tileshift_2", "平铺错位 2", "AM · 变形", """
            float rand21(vec2 co)
            {
                return fract(sin(dot(co, vec2(719.31, 178.233))) * 33758.5453);
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 st = luv - 0.5 + vec2(u_position_x, u_position_y);
                float a = radians(u_angle);

                st = rot(a) * st;

                float amount = max(u_amount, 0.5);
                float px = floor(st.x * amount / max(u_stretch_y, 0.01));
                float py = floor(st.y * amount / max(u_stretch_x, 0.01));
                float dx = rand21(vec2(px, py + 3937.7249 * u_seed));
                float dy = rand21(vec2(px + 328.7319 + 432.7249 * u_seed, py - 971.382));
                vec2 dv = (vec2(dx, dy) - 0.5) * 2.0;
                vec4 shifted = sampleLayer(clamp(luv + dv * clamp(u_strength, 0.0, 1.0), 0.0, 1.0));
                fragColor = vec4(shifted.rgb, shifted.a * c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 1F, 0.1F, 0.001F)
            .p("amount", "格子数量", 0.5F, 200F, 10F, 0.1F)
            .p("stretch_x", "拉伸 X", 0.01F, 10F, 1F, 0.01F)
            .p("stretch_y", "拉伸 Y", 0.01F, 10F, 1F, 0.01F)
            .p("angle", "角度", 0F, 3600F, 0F, 0.5F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("position_x", "位置 X", -1F, 1F, 0F, 0.01F)
            .p("position_y", "位置 Y", -1F, 1F, 0F, 0.01F));

        add(new AmEffectDef("am_stretch_segment", "拉伸段", "AM · 变形", """
            float sminCubic(float a, float b, float k)
            {
                float kk = max(k, 0.00001);
                float h = max(kk - abs(a - b), 0.0) / kk;

                return min(a, b) - h * h * h * kk * (1.0 / 6.0);
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_stretch <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float a = radians(u_angle);
                vec2 v = vec2(cos(a), -sin(a));
                float dist = dot(luv - 0.5, v) + u_offset / 1000.0;
                float adjStretch = u_stretch / 500.0;
                float d = sminCubic(adjStretch, abs(dist), max(0.00001, u_smooth * adjStretch));
                fragColor = sampleLayer(clamp(luv + v * d * -sign(dist), 0.0, 1.0));
            }
            """, 0F, "stretch")
            .p("angle", "角度", 0F, 3600F, 0F, 0.5F)
            .p("stretch", "拉伸长度", 0F, 900F, 10F, 1F)
            .p("offset", "偏移", -2000F, 2000F, 0F, 1F)
            .p("smooth", "平滑", 0F, 1F, 0F, 0.01F));

        add(new AmEffectDef("am_omino_glass", "Omino 玻璃", "AM · 变形", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float feature = max(u_feature_size, 5.0) / max(LayerSize.x, 1.0);
                vec2 p = luv / max(feature, 0.0001) + vec2(u_seed * 7.13, u_evolution * 0.1);
                int octaves = int(clamp(u_samples, 1.0, 6.0));
                float n1 = fbm(p, octaves) - 0.5;
                float n2 = fbm(p + 3.7, octaves) - 0.5;
                vec2 normal = vec2(n1, n2) * max(u_roughness_scale, 0.01);

                normal += vec2(fbm(p * 3.0, 2) - 0.5) * clamp(u_roughness, -1.0, 1.0);

                float refraction = clamp(u_strength, 0.0, 2.0) * u_refraction / 100.0 * 0.02;
                vec2 offset = normal * refraction;
                float aberration = clamp(u_strength, 0.0, 2.0) * u_aberration / 100.0 * 0.01;
                vec3 refracted = vec3(
                    sampleLayer(clamp(luv + offset * (1.0 + aberration), 0.0, 1.0)).r,
                    sampleLayer(clamp(luv + offset, 0.0, 1.0)).g,
                    sampleLayer(clamp(luv + offset * (1.0 - aberration), 0.0, 1.0)).b);

                vec2 lightDir = normalize(vec2(u_light_dir_x, u_light_dir_y) + vec2(0.0001));
                vec3 lightVector = normalize(vec3(lightDir, clamp(u_light_depth / 100.0, -1.0, 8.0)));
                vec3 surface = normalize(vec3(normal, 0.75));
                float diffuse = clamp(dot(surface, lightVector) * 0.5 + 0.5, 0.0, 1.0);
                float specular = pow(clamp(dot(reflect(-lightVector, surface), vec3(0.0, 0.0, 1.0)), 0.0, 1.0),
                    max(u_light_spec, 1.0)) * clamp(u_light_shine, 0.0, 1.0);
                vec3 lit = refracted * mix(u_light_ambient, u_light_intensity, diffuse) * u_light_color.rgb
                    + u_light_color.rgb * specular;
                vec3 tinted = mix(lit, lit * u_tintcolor.rgb * 2.0, clamp(u_blend, 0.0, 1.0));
                int shading = int(clamp(u_shading, 0.0, 3.0));

                if (shading == 0)
                {
                    tinted = mix(refracted, refracted * u_tintcolor.rgb * 2.0, clamp(u_blend, 0.0, 1.0));
                }
                else if (shading == 2)
                {
                    tinted = refracted * u_light_color.rgb + u_light_color.rgb * specular;
                }
                else if (shading == 3)
                {
                    tinted = refracted * u_light_ambient;
                }

                fragColor = vec4(clamp(tinted, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 2F, 0.1F, 0.01F)
            .p("refraction", "折射", -100F, 100F, 5F, 0.1F)
            .p("aberration", "色差", -100F, 100F, 5F, 0.1F)
            .p("feature_size", "特征尺寸（像素）", 5F, 500F, 100F, 1F)
            .p("samples", "细节层数", 1F, 20F, 2F, 1F)
            .p("blend", "着色混合", 0F, 1F, 0F, 0.01F)
            .p("shading", "着色模式（0 无/1 完整/2 高光/3 环境光）", 0F, 3F, 1F, 1F)
            .p("light_intensity", "光强", 0F, 2F, 1F, 0.001F)
            .p("light_shine", "高光强度", 0F, 1F, 0.8F, 0.01F)
            .p("light_spec", "高光锐度", 1F, 250F, 30F, 0.5F)
            .p("light_ambient", "环境光", 0F, 1F, 0.1F, 0.01F)
            .p("light_dir_x", "光照方向 X", -1F, 1F, 0.75F, 0.01F)
            .p("light_dir_y", "光照方向 Y", -1F, 1F, -0.75F, 0.01F)
            .p("light_depth", "光照深度", -100F, 800F, 100F, 0.5F)
            .p("roughness", "粗糙度", -1F, 1F, 0F, 0.01F)
            .p("roughness_scale", "粗糙缩放", 0.01F, 50F, 1F, 0.01F)
            .p("evolution", "演化", -50F, 50F, 0F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .c("tintcolor", "着色", 0xFFFF5566)
            .c("light_color", "光照颜色", 0xFFFFFFFF));

        /* ---- 0.96 batch: remaining reference executable-shader family -------------------------
         * The reference implementations of the drawing/procedural family feed precomputed buffer
         * textures (blur passes, masks) into the shader; the bodies below compute the same shapes
         * from the layer's own pixels with small tap/noise loops, which keeps them inside the
         * ordinary AmEffectDef contract. Every body starts with an explicit identity branch for its
         * declared neutral value. Generators that the reference defines without an alpha/amount
         * control get one extra `alpha` parameter so they have a real zero state (documented in
         * docs/EFFECTS-AM-GAP.zh.md). */

        add(new AmEffectDef("am_shape_field", "形状场", "AM · 重复", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float alpha = clamp(u_alpha, 0.0, 1.0);

                if (alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float density = clamp(u_density, 0.0, 1.0);
                float cell = mix(48.0, 14.0, density) / max(u_scale, 0.01)
                    * sqrt(10.0 / max(u_layer_count, 1.0));
                vec2 p = luv * max(LayerSize, vec2(1.0)) / max(cell, 0.001)
                    + vec2(u_seed * 7.13, u_evolution * 0.1);
                vec2 id = floor(p);
                vec2 f = fract(p) - 0.5;
                float dist = 1e9;
                float which = 0.0;
                vec2 nearest = vec2(0.0);

                for (int j = -1; j <= 1; j++)
                {
                    for (int i = -1; i <= 1; i++)
                    {
                        vec2 g = vec2(float(i), float(j));
                        vec2 o = vec2(hash(id + g + u_seed), hash(id + g + 7.7 + u_seed)) - 0.5;
                        vec2 q = g + o * clamp(u_slack, 0.0, 1.0);
                        float d = length(f - q);

                        if (d < dist)
                        {
                            dist = d;
                            which = hash(id + g + 3.3 + u_seed);
                            nearest = q;
                        }
                    }
                }

                float variance = 0.5 + (which - 0.5) * clamp(u_size_variance, 0.0, 1.0);
                float radius = max(u_shape_size, 0.02) * variance;
                float shapeAngle = radians(u_shape_angle)
                    + which * radians(u_angle_variance * 360.0);
                vec2 delta = rot(shapeAngle) * (f - nearest);
                float box = max(abs(delta.x), abs(delta.y)) * 0.8;
                float shape = 1.0 - smoothstep(radius, radius + 0.02, mix(dist, box, 0.4));
                float layerFade = clamp(1.0 - dist * 0.6, 0.0, 1.0) * clamp(u_fade, 0.0, 1.0);
                vec3 color = mix(u_color1.rgb, mix(u_color2.rgb, u_color3.rgb, which), which);
                float amount = shape * layerFade * alpha * mix(0.25, 1.0, density);
                fragColor = vec4(mix(c.rgb, color, clamp(amount, 0.0, 1.0)), max(c.a, amount));
            }
            """, 0F, "alpha")
            .p("density", "密度", 0F, 1F, 0.3F, 0.001F)
            .p("evolution", "演化", -50F, 50F, 0F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("scale", "缩放", 0.01F, 50F, 1F, 0.01F)
            .p("shape_size", "形状大小", 0F, 1F, 0.5F, 0.001F)
            .p("shape_angle", "形状角度", -3600F, 3600F, 0F, 0.5F)
            .p("size_variance", "大小随机", 0F, 1F, 0.5F, 0.01F)
            .p("angle_variance", "角度随机", 0F, 1F, 0F, 0.01F)
            .p("layer_count", "层数", 1F, 20F, 15F, 1F)
            .p("fade", "衰减", 0F, 1F, 1F, 0.001F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("slack", "松弛", 0F, 1F, 1F, 0.01F)
            .c("color1", "颜色 1", 0xFFFF55AA)
            .c("color2", "颜色 2", 0xFF55AAFF)
            .c("color3", "颜色 3", 0xFFFFAA55));

        add(new AmEffectDef("am_electric_edges", "电弧边缘", "AM · 绘制", """
            vec4 edgeSample(vec2 luv, float radiusPx)
            {
                vec2 texel = radiusPx / max(LayerSize, vec2(1.0));
                vec4 sum = vec4(0.0);

                for (int i = 0; i < 8; i++)
                {
                    float a = 6.2831853 * float(i) / 8.0;

                    sum += sampleLayer(clamp(luv + vec2(cos(a), sin(a)) * texel, 0.0, 1.0));
                }

                return sum / 8.0;
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float alpha = clamp(u_alpha, 0.0, 1.0) * clamp(u_strength, 0.0, 2.0);

                if (alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float radius = mix(2.0, 24.0, clamp(u_distance, 0.0, 1.0));
                vec4 blurred = edgeSample(luv, radius);
                float edge = clamp(length(c.rgb - blurred.rgb) * 3.0, 0.0, 1.0)
                    + clamp(abs(c.a - blurred.a) * 3.0, 0.0, 1.0);

                vec2 p = luv * max(u_segments, 1.0) * 0.6
                    + vec2(u_seed * 5.1 + u_dist_offs, u_evolution * 0.1 + Time * 0.4);
                float n = fbm(p, int(clamp(u_complexity, 0.0, 8.0)) + 1) - 0.5;
                float spark = smoothstep(0.55, 0.95,
                    edge + n * clamp(u_disort, -1.0, 1.0) * clamp(u_thickness, 0.0, 1.0));
                float glow = spark * clamp(u_glow_alpha, 0.0, 1.0) * 0.4;
                vec3 color = mix(u_color2.rgb, u_color1.rgb, spark);
                vec3 rgb = mix(c.rgb, color, clamp(spark, 0.0, 1.0) * alpha);
                rgb = 1.0 - (1.0 - rgb) * (1.0 - color * glow);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), max(c.a, spark * alpha * clamp(u_fill, 0.0, 1.0)));
            }
            """, 0F, "alpha")
            .p("strength", "强度", 0F, 2F, 0.1F, 0.01F)
            .p("distance", "边缘距离", 0F, 1F, 0.3F, 0.001F)
            .p("thickness", "粗细", 0F, 1F, 0.8F, 0.001F)
            .p("complexity", "复杂度", 0F, 20F, 2F, 0.01F)
            .p("segments", "分段", 1F, 360F, 30F, 0.1F)
            .p("dist_offs", "距离偏移", -1F, 1F, 0F, 0.01F)
            .p("disort", "扰动", -1F, 1F, 0.5F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("glow_alpha", "辉光", 0F, 1F, 0.5F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("evolution", "演化", -999F, 999F, 0F, 0.01F)
            .p("fill", "填充", 0F, 1F, 1F, 0.01F)
            .c("color1", "颜色 1", 0xFFFF77FF)
            .c("color2", "颜色 2", 0xFFDDBBAA));

        add(new AmEffectDef("am_roughen_edges", "粗糙边缘", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float radius = 3.0 / max(LayerSize.x, 1.0);
                float left = sampleLayer(clamp(luv - vec2(radius, 0.0), 0.0, 1.0)).a;
                float right = sampleLayer(clamp(luv + vec2(radius, 0.0), 0.0, 1.0)).a;
                float down = sampleLayer(clamp(luv - vec2(0.0, radius), 0.0, 1.0)).a;
                float up = sampleLayer(clamp(luv + vec2(0.0, radius), 0.0, 1.0)).a;
                vec2 edge = vec2(right - left, up - down);
                vec2 p = luv * max(u_detail, 0.01) * max(u_scale, 0.1)
                    + vec2(u_seed * 3.7, u_evolution * 0.05);
                int octaves = int(clamp(u_octaves, 1.0, 9.0));
                float n = fbm(p + vec2(u_offs), octaves) - 0.5;
                vec2 direction;

                if (u_direction < 0.5)
                {
                    direction = vec2(1.0, 0.0);
                }
                else if (u_direction < 1.5)
                {
                    direction = vec2(0.0, 1.0);
                }
                else if (u_direction < 2.5)
                {
                    direction = normalize(edge + vec2(0.0001));
                }
                else
                {
                    direction = normalize(vec2(-edge.y, edge.x) + vec2(0.0001));
                }

                vec2 offset = direction * n * u_strength * 6.0 / max(LayerSize, vec2(1.0));
                offset.y *= mix(1.0, 1.0 + clamp(u_stretch, -50.0, 50.0) * 0.02, 1.0);
                fragColor = sampleLayer(clamp(luv + offset, 0.0, 1.0));
            }
            """, 0F, "strength")
            .p("strength", "强度", 0F, 2.5F, 0.2F, 0.001F)
            .p("evolution", "演化", -50F, 50F, 0F, 0.01F)
            .p("direction", "方向（0 水平/1 垂直/2 法线/3 切线）", 0F, 3F, 3F, 1F)
            .p("detail", "细节", 0.01F, 50F, 1F, 0.01F)
            .p("stretch", "拉伸", -50F, 50F, 0F, 0.01F)
            .p("scale", "缩放", 0.1F, 50F, 1F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("octaves", "细节层数", 1F, 9F, 6F, 1F)
            .p("offs", "相位偏移", -1F, 1F, 0F, 0.001F));

        add(new AmEffectDef("am_smooth_bevel", "平滑斜面 2", "AM · 绘制", """
            float bevelHeight(vec2 luv, float radiusPx)
            {
                vec2 texel = radiusPx / max(LayerSize, vec2(1.0));
                float sum = 0.0;

                for (int i = 0; i < 8; i++)
                {
                    float a = 6.2831853 * float(i) / 8.0;

                    sum += sampleLayer(clamp(luv + vec2(cos(a), sin(a)) * texel, 0.0, 1.0)).a;
                }

                return sum / 8.0;
            }

            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (u_radius <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float radiusX = u_radius / max(LayerSize.x, 1.0);
                float radiusY = u_radius / max(LayerSize.y, 1.0);
                float left = bevelHeight(clamp(luv - vec2(radiusX, 0.0), 0.0, 1.0), u_radius);
                float right = bevelHeight(clamp(luv + vec2(radiusX, 0.0), 0.0, 1.0), u_radius);
                float down = bevelHeight(clamp(luv - vec2(0.0, radiusY), 0.0, 1.0), u_radius);
                float up = bevelHeight(clamp(luv + vec2(0.0, radiusY), 0.0, 1.0), u_radius);
                vec3 normal = normalize(vec3((right - left) * 2.0, (up - down) * 2.0, 0.35));
                vec3 lightD = normalize(vec3(cos(radians(u_angle_d)), sin(radians(u_angle_d)),
                    max(u_elevation_d, 0.1)));
                vec3 lightS = normalize(vec3(cos(radians(u_angle_s)), sin(radians(u_angle_s)),
                    max(u_elevation_s, 0.1)));
                float diffuse = clamp(dot(normal, lightD) * 0.5 + 0.5, 0.0, 1.0)
                    * clamp(u_intensity_d, 0.0, 1.0);
                float specular = pow(clamp(dot(normal, lightS), 0.0, 1.0), max(u_spec, 1.0))
                    * clamp(u_intensity_s, 0.0, 1.0);
                vec3 rgb = c.rgb;
                rgb += u_color_d.rgb * diffuse * 0.8;
                rgb += u_color_s.rgb * specular * 0.8;
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "radius")
            .p("radius", "倒角半径（像素）", 0F, 200F, 44F, 1F)
            .p("angle_d", "漫反射角度", 0F, 360F, 45F, 1F)
            .p("angle_s", "高光角度", 0F, 360F, 45F, 1F)
            .p("intensity_d", "漫反射强度", 0F, 1F, 0.5F, 0.01F)
            .p("intensity_s", "高光强度", 0F, 1F, 1F, 0.01F)
            .p("elevation_d", "漫反射高度", 0F, 5F, 0F, 0.01F)
            .p("elevation_s", "高光高度", 0F, 5F, 2.2F, 0.01F)
            .p("spec", "高光锐度", 1F, 50F, 35F, 1F)
            .c("color_d", "漫反射颜色", 0xFFFFFFFF)
            .c("color_s", "高光颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_voronoi_2", "Voronoi 2", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float alpha = clamp(u_alpha, 0.0, 1.0);

                if (alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = luv * max(LayerSize, vec2(1.0)) / max(50.0 / max(u_scale, 0.01), 0.001)
                    / max(u_density, 1.0) + vec2(u_seed * 9.7, u_phase);
                vec2 id = floor(p);
                vec2 f = fract(p) - 0.5;
                float first = 1e9;
                float second = 1e9;

                for (int j = -1; j <= 1; j++)
                {
                    for (int i = -1; i <= 1; i++)
                    {
                        vec2 g = vec2(float(i), float(j));
                        vec2 o = vec2(hash(id + g + u_seed), hash(id + g + 4.7 + u_seed)) - 0.5;
                        float d = length(g + o - f);

                        if (d < first)
                        {
                            second = first;
                            first = d;
                        }
                        else if (d < second)
                        {
                            second = d;
                        }
                    }
                }

                float edge = clamp(second - first, 0.0, 1.0);
                float line = 1.0 - smoothstep(0.0, max(u_line_width, 0.001) * 0.5, second - first);
                vec3 fill = mix(u_color.rgb, u_color2.rgb, clamp(edge * 2.0, 0.0, 1.0));
                vec3 rgb = mix(fill, u_line_color.rgb, line * step(0.0001, u_line_width));
                fragColor = vec4(mix(c.rgb, rgb, alpha), c.a);
            }
            """, 0F, "alpha")
            .p("scale", "缩放", 0.01F, 100F, 1F, 0.01F)
            .p("phase", "相位", -99F, 99F, 0F, 0.005F)
            .p("line_width", "线条宽度", 0F, 1F, 0F, 0.005F)
            .p("density", "密度", 1F, 3F, 1F, 1F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color", "填充色", 0xFFFFFFFF)
            .c("color2", "填充色 2", 0xFF000000)
            .c("line_color", "线条颜色", 0xFF0000FF));

        add(new AmEffectDef("am_ridges_2", "山脊 2", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float alpha = clamp(u_alpha, 0.0, 1.0);

                if (alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = (luv + vec2(u_position_x, u_position_y) * 0.01) * max(u_scale, 0.01)
                    * vec2(max(u_stretch_x, 0.01), max(u_stretch_y, 0.01))
                    + vec2(u_seed * 5.3, u_evolution * 0.1);
                int octaves = int(clamp(u_octaves, 1.0, 9.0));
                float n = fbm(p, octaves);
                float ridge = 1.0 - abs(n * 2.0 - 1.0);

                ridge = pow(clamp(ridge, 0.0, 1.0), max(u_intensity, 0.1));
                vec3 rgb = mix(u_color1.rgb, u_color2.rgb, clamp(ridge, 0.0, 1.0));
                fragColor = vec4(mix(c.rgb, rgb, alpha), c.a);
            }
            """, 0F, "alpha")
            .p("evolution", "演化", -50F, 50F, 0F, 0.01F)
            .p("scale", "缩放", 0.1F, 50F, 1F, 0.01F)
            .p("stretch_x", "拉伸 X", 0.01F, 10F, 1F, 0.01F)
            .p("stretch_y", "拉伸 Y", 0.01F, 10F, 1F, 0.01F)
            .p("intensity", "锐度", 0.1F, 1.5F, 1F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("octaves", "细节层数", 1F, 9F, 6F, 1F)
            .p("position_x", "位置 X", -1F, 1F, 0F, 0.01F)
            .p("position_y", "位置 Y", -1F, 1F, 0F, 0.01F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("color1", "颜色 1", 0xFF000000)
            .c("color2", "颜色 2", 0xFFFFFFFF));

        add(new AmEffectDef("am_noise_3", "噪声 3", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float amount = clamp(u_amount, 0.0, 1.0);

                if (amount <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = (luv + vec2(u_position_x, u_position_y) * 0.01) * 12.0;
                float n = noise(p) - 0.5;
                float value = clamp(0.5 + n * u_overshoot, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, vec3(value), amount), c.a);
            }
            """, 0F, "amount")
            .p("amount", "混合量", 0F, 1F, 0.25F, 0.005F)
            .p("position_x", "位置 X", -1F, 1F, 0F, 0.01F)
            .p("position_y", "位置 Y", -1F, 1F, 0F, 0.01F)
            .p("overshoot", "对比", 0.5F, 2F, 2F, 0.01F));

        add(new AmEffectDef("am_block_noise", "块状噪波", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float amount = clamp(u_amount, 0.0, 1.0);

                if (amount <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = (luv + vec2(u_position_x, u_position_y) * 0.01) * max(LayerSize, vec2(1.0))
                    / max(u_size, 1.0);
                p *= vec2(max(u_stretch_x, 0.01), max(u_stretch_y, 0.01));
                vec2 block = floor(p);
                float n = hash(block + vec2(u_seed * 17.3));
                float value = clamp(0.5 + (n - 0.5) * u_overshoot, 0.0, 1.0);
                fragColor = vec4(mix(c.rgb, vec3(value), amount), c.a);
            }
            """, 0F, "amount")
            .p("size", "块大小（像素）", 1F, 999F, 20F, 0.1F)
            .p("stretch_x", "拉伸 X", 0.01F, 10F, 1F, 0.01F)
            .p("stretch_y", "拉伸 Y", 0.01F, 10F, 1F, 0.01F)
            .p("amount", "混合量", 0F, 1F, 0.25F, 0.005F)
            .p("position_x", "位置 X", -1F, 1F, 0F, 0.01F)
            .p("position_y", "位置 Y", -1F, 1F, 0F, 0.01F)
            .p("overshoot", "对比", 0.5F, 2F, 2F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F));

        add(new AmEffectDef("am_halftone_cmyk", "CMYK 半调", "AM · 绘制", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float strength = clamp(u_strength, 0.0, 1.0);

                if (strength <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float size = max(u_amount, 1.0) * 0.02;
                float a = radians(u_angle);
                vec2 p = rot(a) * luv * max(LayerSize, vec2(1.0)) / size + u_phase;
                vec2 cell = fract(p) - 0.5;
                float dotMask = 1.0 - smoothstep(0.25, 0.5, length(cell));
                vec3 k = vec3(1.0 - c.rgb);
                float black = dot(k, vec3(0.3, 0.59, 0.11)) * clamp(u_black_weight, 0.0, 1.0);
                vec3 ink = 1.0 - mix(k, vec3(black), clamp(u_black_weight, 0.0, 1.0));
                vec3 rgb = mix(c.rgb, ink * dotMask, strength);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("amount", "网点大小", 0F, 500F, 100F, 0.1F)
            .p("strength", "强度", 0F, 1F, 0.5F, 0.001F)
            .p("black_weight", "黑版权重", 0F, 1F, 0F, 0.001F)
            .p("angle", "角度", 0F, 3600F, 45F, 0.5F)
            .p("phase", "相位", -1000F, 1000F, 0.5F, 0.01F));

        add(new AmEffectDef("am_rgb_sep", "RGB 分离 2", "AM · 调色", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);

                if (abs(u_strength) <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float a = radians(u_angle);
                float amount = abs(u_strength) * 20.0 / max(LayerSize.x, 1.0);
                vec2 dir = vec2(cos(a), sin(a)) * amount;
                int center = int(clamp(u_center_channel, 0.0, 2.0));
                int mode = int(clamp(u_mode, 0.0, 2.0));
                vec3 rgb;

                if (mode == 0)
                {
                    rgb = vec3(sampleLayer(clamp(luv + dir, 0.0, 1.0)).r, c.rgb.g,
                        sampleLayer(clamp(luv - dir, 0.0, 1.0)).b);
                }
                else if (mode == 1)
                {
                    rgb = vec3(sampleLayer(clamp(luv - dir, 0.0, 1.0)).r, c.rgb.g,
                        sampleLayer(clamp(luv + dir, 0.0, 1.0)).b);
                }
                else
                {
                    rgb = vec3(sampleLayer(clamp(luv + dir, 0.0, 1.0)).r,
                        sampleLayer(clamp(luv + dir * 0.5, 0.0, 1.0)).g,
                        sampleLayer(clamp(luv - dir * 0.5, 0.0, 1.0)).b);
                }

                if (center == 0)
                {
                    rgb.r = c.rgb.r;
                }
                else if (center == 1)
                {
                    rgb.g = c.rgb.g;
                }
                else
                {
                    rgb.b = c.rgb.b;
                }

                fragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
            """, 0F, "strength")
            .p("strength", "强度", -8F, 8F, 0.15F, 0.01F)
            .p("angle", "角度", 0F, 3600F, 0F, 0.5F)
            .p("center_channel", "中心通道（0 R/1 G/2 B）", 0F, 2F, 1F, 1F)
            .p("mode", "模式（0 标准/1 反向/2 三段）", 0F, 2F, 2F, 1F));

        add(new AmEffectDef("am_simplestars_2", "简易星点 2", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float alpha = clamp(u_alpha, 0.0, 1.0) * clamp(u_brightness, 0.0, 5.0);

                if (alpha <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                float cell = mix(20.0, 8.0, clamp(u_star_size, 0.0, 5.0) / 5.0) / max(u_scale, 0.01);
                vec2 p = luv * max(LayerSize, vec2(1.0)) / max(cell, 0.001);
                vec2 id = floor(p);
                vec2 f = fract(p) - 0.5;
                float rnd = hash(id);

                if (rnd > clamp(u_star_count / 50.0, 0.02, 1.0))
                {
                    fragColor = c;

                    return;
                }

                vec2 center = (vec2(hash(id + 3.7), hash(id + 9.1)) - 0.5) * 0.6;
                float radius = 0.12 + clamp(u_star_size, 0.0, 5.0) * 2.0;
                float dist = length(f - center);
                float star = 1.0 - smoothstep(radius * 0.5, radius * 0.5 + u_star_feather * 0.01, dist);
                float amount = star * alpha;
                fragColor = vec4(mix(c.rgb, u_color.rgb * (0.5 + rnd), clamp(amount, 0.0, 1.0)), max(c.a, amount));
            }
            """, 0F, "alpha")
            .p("scale", "缩放", 0.01F, 50F, 1F, 0.01F)
            .p("star_size", "星点大小", 0F, 5F, 0.1F, 0.001F)
            .p("star_feather", "柔边", 0F, 20F, 1F, 0.1F)
            .p("alpha", "不透明度", 0F, 1F, 1F, 0.01F)
            .p("star_count", "数量", 1F, 50F, 25F, 1F)
            .p("brightness", "亮度", 0F, 5F, 1F, 0.005F)
            .c("color", "颜色", 0xFFFFFFFF));

        add(new AmEffectDef("am_volumetric_clouds", "体积云", "AM · 生成", """
            void main()
            {
                vec2 luv = layerUv(vUv);
                vec4 c = sampleLayer(luv);
                float amount = clamp(u_amount, 0.0, 1.0);

                if (amount <= 0.0001)
                {
                    fragColor = c;

                    return;
                }

                vec2 p = luv * 3.0 + vec2(u_seed * 11.3);
                int octaves = int(clamp(u_octaves, 1.0, 9.0));
                float n = fbm(p, octaves);
                float density = clamp((n - u_density) * 2.0, 0.0, 1.0);
                density = pow(density, 1.0 + clamp(u_fill, 0.0, 1.0) * 2.0);
                vec3 rgb = mix(u_shadow_color.rgb, u_light_color.rgb, clamp(density, 0.0, 1.0));
                fragColor = vec4(mix(c.rgb, rgb, clamp(density * amount, 0.0, 1.0)), c.a);
            }
            """, 0F, "amount")
            .p("density", "密度阈值", 0F, 1F, 0F, 0.01F)
            .p("seed", "随机种子", 0F, 5F, 0F, 0.01F)
            .p("fill", "填充", 0F, 1F, 0F, 0.01F)
            .p("octaves", "细节层数", 1F, 9F, 6F, 1F)
            .p("amount", "不透明度", 0F, 1F, 1F, 0.01F)
            .c("shadow_color", "阴影颜色", 0xFF000000)
            .c("light_color", "受光颜色", 0xFFFFFFFF));
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
            /* Missing parameter = the definition's default, not zero. Old projects (or hand-edited
             * files) that predate a parameter used to be judged "neutral" and the whole effect was
             * skipped in the pipeline — "I added this effect and nothing happened". */
            if (Math.abs(effect.valueF(key, paramDefault(definition, key))) > 0.0001F)
            {
                return false;
            }
        }

        return true;
    }

    private static float paramDefault(AmEffectDef definition, String key)
    {
        for (AmEffectDef.Param param : definition.params)
        {
            if (param != null && key.equals(param.key))
            {
                return param.defaultValue;
            }
        }

        return 0F;
    }
}
