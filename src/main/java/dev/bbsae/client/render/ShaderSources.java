package dev.bbsae.client.render;

/**
 * Runtime GLSL for the compositor. Kept in Java rather than shader assets so the effect registry and
 * the shader family stay in one place and an effect cannot be shipped without its program.
 */
public final class ShaderSources
{
    private ShaderSources()
    {}

    public static final String VERTEX = """
        #version 150 core
        in vec2 Position;
        out vec2 vUv;
        void main()
        {
            vUv = Position * 0.5 + 0.5;
            gl_Position = vec4(Position, 0.0, 1.0);
        }
        """;

    public static final String COMPOSITE = """
        #version 150 core

        uniform sampler2D Backdrop;
        uniform sampler2D Source;
        uniform sampler2D Mask;
        uniform int UseBackdrop;
        uniform int UseMask;
        uniform int MaskChannel;
        uniform int Blend;
        uniform float Opacity;
        uniform vec2 Offset;
        uniform vec4 Tint;

        in vec2 vUv;
        out vec4 fragColor;

        vec3 blendColor(vec3 b, vec3 s)
        {
            if (Blend == 1) return min(b + s, vec3(1.0));
            if (Blend == 2) return b * s;
            if (Blend == 3) return 1.0 - (1.0 - b) * (1.0 - s);
            if (Blend == 4)
            {
                return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b));
            }
            if (Blend == 5)
            {
                vec3 low = ((16.0 * b - 12.0) * b + 4.0) * b;
                vec3 d = mix(low, sqrt(b), step(vec3(0.25), b));
                return mix(b - (1.0 - 2.0 * s) * b * (1.0 - b), b + (2.0 * s - 1.0) * (d - b), step(0.5, s));
            }
            if (Blend == 6)
            {
                return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, s));
            }
            if (Blend == 7) return abs(b - s);
            if (Blend == 8) return b + s - 2.0 * b * s;
            if (Blend == 9) return min(b, s);
            if (Blend == 10) return max(b, s);
            if (Blend == 11) return clamp(b / max(1.0 - s, vec3(0.0001)), 0.0, 1.0);
            if (Blend == 12) return 1.0 - clamp((1.0 - b) / max(s, vec3(0.0001)), 0.0, 1.0);

            return s;
        }

        float maskValue(vec2 uv)
        {
            if (UseMask == 0) return 1.0;

            vec4 m = texture(Mask, uv);
            float value = MaskChannel == 1 || MaskChannel == 3 ? dot(m.rgb, vec3(0.299, 0.587, 0.114)) : m.a;

            return MaskChannel >= 2 ? 1.0 - value : value;
        }

        void main()
        {
            vec2 uv = clamp(vUv - Offset, vec2(0.0), vec2(1.0));
            vec4 s = texture(Source, uv);
            float m = maskValue(vUv);
            float a = clamp(s.a * Opacity * m, 0.0, 1.0);

            /* Layer content is stored with straight alpha. Effect passes and the compositor
             * therefore never divide by alpha here; only the content/display boundaries
             * convert between BBS's premultiplied drawing and straight-alpha compositing. */
            vec3 srcRgb = s.rgb;

            srcRgb *= Tint.rgb;

            if (UseBackdrop == 0)
            {
                fragColor = vec4(srcRgb, a);
                return;
            }

            vec4 b = texture(Backdrop, vUv);
            vec3 blended = blendColor(b.rgb, srcRgb);
            float outA = a + b.a * (1.0 - a);
            vec3 outRgb = outA > 0.0001 ? (blended * a + b.rgb * b.a * (1.0 - a)) / outA : vec3(0.0);

            fragColor = vec4(outRgb, outA);
        }
        """;

    public static final String BLUR = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Texel;
        uniform vec2 Direction;
        uniform float Radius;
        uniform int Mode;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 sum = vec4(0.0);
            float total = 0.0;

            for (int i = -8; i <= 8; i++)
            {
                float fi = float(i);
                float weight = Mode == 1 ? 1.0 : exp(-fi * fi / 30.0);
                vec2 uv = vUv + Direction * Texel * fi * max(Radius, 0.0);
                sum += texture(Source, clamp(uv, vec2(0.0), vec2(1.0))) * weight;
                total += weight;
            }

            fragColor = sum / max(total, 0.0001);
        }
        """;

    public static final String PREMULTIPLY = """
        #version 150 core

        uniform sampler2D Source;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);
            fragColor = vec4(c.rgb * c.a, c.a);
        }
        """;

    public static final String UNPREMULTIPLY = """
        #version 150 core

        uniform sampler2D Source;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);

            if (c.a <= 0.0001)
            {
                fragColor = vec4(0.0);
                return;
            }

            fragColor = vec4(clamp(c.rgb / c.a, vec3(0.0), vec3(1.0)), c.a);
        }
        """;

    public static final String INNER = """
        #version 150 core

        uniform sampler2D Source;
        uniform sampler2D Blurred;
        uniform vec4 Color;
        uniform float Opacity;
        uniform vec2 Offset;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);
            vec4 b = texture(Blurred, clamp(vUv + Offset, vec2(0.0), vec2(1.0)));
            float edge = clamp(1.0 - b.a, 0.0, 1.0);
            float amount = edge * c.a * clamp(Opacity, 0.0, 1.0);

            fragColor = vec4(mix(c.rgb, Color.rgb, amount), c.a);
        }
        """;

    public static final String GRADIENT = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec4 ColorA;
        uniform vec4 ColorB;
        uniform float Angle;
        uniform float Opacity;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);
            float angle = radians(Angle);
            vec2 dir = vec2(cos(angle), sin(angle));
            float t = clamp(dot(vUv - 0.5, dir) + 0.5, 0.0, 1.0);
            vec3 gradient = mix(ColorA.rgb, ColorB.rgb, t);

            fragColor = vec4(mix(c.rgb, gradient, clamp(Opacity, 0.0, 1.0)), c.a);
        }
        """;

    public static final String SHARPEN = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Texel;
        uniform float Amount;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 center = texture(Source, vUv);
            vec3 neighbours =
                texture(Source, clamp(vUv + vec2(Texel.x, 0.0), vec2(0.0), vec2(1.0))).rgb +
                texture(Source, clamp(vUv - vec2(Texel.x, 0.0), vec2(0.0), vec2(1.0))).rgb +
                texture(Source, clamp(vUv + vec2(0.0, Texel.y), vec2(0.0), vec2(1.0))).rgb +
                texture(Source, clamp(vUv - vec2(0.0, Texel.y), vec2(0.0), vec2(1.0))).rgb;
            vec3 sharpened = center.rgb + (center.rgb * 4.0 - neighbours) * Amount;

            fragColor = vec4(clamp(sharpened, vec3(0.0), vec3(1.0)), center.a);
        }
        """;

    public static final String RADIAL = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Center;
        uniform float Amount;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 sum = vec4(0.0);

            for (int i = 0; i < 12; i++)
            {
                float t = float(i) / 11.0;
                vec2 uv = mix(vUv, Center, Amount * t);
                sum += texture(Source, clamp(uv, vec2(0.0), vec2(1.0)));
            }

            fragColor = sum / 12.0;
        }
        """;

    public static final String COLOR = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Size;
        uniform float Brightness;
        uniform float Contrast;
        uniform float Saturation;
        uniform float Hue;
        uniform float Exposure;
        uniform float Gamma;
        uniform float BlackPoint;
        uniform float WhitePoint;
        uniform float Lightness;
        uniform float Shadow;
        uniform float Midtone;
        uniform float Highlight;
        uniform vec4 Tint;
        uniform float TintAmount;
        uniform float Invert;
        uniform float Posterize;
        uniform float Threshold;
        uniform float Vignette;
        uniform float VignetteRadius;
        uniform float VignetteSoftness;
        uniform vec4 VignetteColor;
        uniform float NoiseAmount;
        uniform float NoiseScale;
        uniform float Seed;

        in vec2 vUv;
        out vec4 fragColor;

        vec3 rgb2hsv(vec3 c)
        {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        vec3 hsv2rgb(vec3 c)
        {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        float hash(vec2 p)
        {
            return fract(sin(dot(p, vec2(12.9898, 78.233)) + Seed) * 43758.5453);
        }

        void main()
        {
            vec4 c = texture(Source, vUv);

            c.rgb = clamp((c.rgb - BlackPoint) / max(WhitePoint - BlackPoint, 0.0001), 0.0, 1.0);
            c.rgb = pow(max(c.rgb, vec3(0.0)) * pow(2.0, Exposure), vec3(max(Gamma, 0.01)));
            c.rgb = (c.rgb - 0.5) * (1.0 + Contrast) + 0.5 + Brightness + Lightness * 0.5;

            float luma = dot(clamp(c.rgb, 0.0, 1.0), vec3(0.299, 0.587, 0.114));
            float shadowWeight = 1.0 - smoothstep(0.0, 0.5, luma);
            float midWeight = 1.0 - abs(luma - 0.5) * 2.0;
            float highlightWeight = smoothstep(0.5, 1.0, luma);
            float curve = Shadow * shadowWeight + Midtone * midWeight + Highlight * highlightWeight;
            c.rgb += curve;

            vec3 hsv = rgb2hsv(clamp(c.rgb, 0.0, 1.0));
            hsv.x = fract(hsv.x + Hue / 360.0);
            hsv.y = clamp(hsv.y * (1.0 + Saturation), 0.0, 1.0);
            c.rgb = hsv2rgb(hsv);

            c.rgb = mix(c.rgb, Tint.rgb, clamp(TintAmount, 0.0, 1.0));
            c.rgb = mix(c.rgb, 1.0 - c.rgb, clamp(Invert, 0.0, 1.0));

            if (Posterize >= 2.0)
            {
                c.rgb = floor(clamp(c.rgb, 0.0, 1.0) * Posterize + 0.5) / Posterize;
            }
            if (Threshold >= 0.0)
            {
                c.rgb = vec3(step(Threshold, dot(c.rgb, vec3(0.299, 0.587, 0.114))));
            }

            if (Vignette > 0.0)
            {
                float d = distance(vUv, vec2(0.5));
                float edge = smoothstep(VignetteRadius, VignetteRadius + VignetteSoftness, d);
                c.rgb = mix(c.rgb, VignetteColor.rgb, edge * Vignette);
            }

            if (NoiseAmount > 0.0)
            {
                float n = hash(floor(vUv * Size / max(NoiseScale, 1.0))) - 0.5;
                c.rgb += n * NoiseAmount;
            }

            fragColor = c;
        }
        """;

    public static final String KEY = """
        #version 150 core

        uniform sampler2D Source;
        uniform int Mode;
        uniform vec4 KeyColor;
        uniform float Tolerance;
        uniform float Softness;
        uniform float Spill;
        uniform float Threshold;
        uniform float Invert;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);
            float alpha = c.a;

            if (Mode == 0)
            {
                float d = distance(c.rgb, KeyColor.rgb);
                alpha *= smoothstep(Tolerance, Tolerance + Softness, d);
                float spillAmount = max(0.0, c.g - max(c.r, c.b)) * Spill;
                c.g -= spillAmount;
            }
            else if (Mode == 1)
            {
                float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                float m = smoothstep(Threshold - Softness, Threshold + Softness, luma);
                alpha *= mix(m, 1.0 - m, Invert);
            }
            else
            {
                alpha = smoothstep(Threshold, Threshold + max(Softness, 0.001), alpha);
            }

            fragColor = vec4(c.rgb, c.a * alpha);
        }
        """;

    public static final String GENERATE = """
        #version 150 core

        uniform sampler2D Source;
        uniform int Type;
        uniform vec2 Size;
        uniform vec4 ColorA;
        uniform vec4 ColorB;
        uniform float Angle;
        uniform float Scale;
        uniform float Softness;
        uniform vec2 Center;
        uniform float Evolution;
        uniform int Octaves;
        uniform float Contrast;

        in vec2 vUv;
        out vec4 fragColor;

        float hash(vec2 p)
        {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float noise(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        float fbm(vec2 p)
        {
            float value = 0.0;
            float amplitude = 0.5;
            for (int i = 0; i < 8; i++)
            {
                if (i >= Octaves)
                {
                    break;
                }

                value += noise(p) * amplitude;
                p *= 2.03;
                amplitude *= 0.5;
            }
            return value;
        }

        mat2 rot(float a)
        {
            float s = sin(a);
            float c = cos(a);
            return mat2(c, -s, s, c);
        }

        void main()
        {
            vec2 uv = vUv;
            vec4 color = vec4(0.0);

            if (Type == 0)
            {
                vec2 dir = vec2(cos(radians(Angle)), sin(radians(Angle)));
                float t = clamp(dot(uv - 0.5, dir) + 0.5, 0.0, 1.0);
                t = smoothstep(0.5 - Softness * 0.5, 0.5 + Softness * 0.5, t);
                color = mix(ColorA, ColorB, t);
            }
            else if (Type == 1)
            {
                float n = fbm(uv * Scale + Evolution);
                n = clamp((n - 0.5) * Contrast + 0.5, 0.0, 1.0);
                color = mix(ColorA, ColorB, n);
            }
            else if (Type == 2)
            {
                vec2 p = rot(radians(Angle)) * (uv - 0.5) * Size / max(Scale, 1.0);
                float checker = mod(floor(p.x) + floor(p.y), 2.0);
                color = mix(ColorA, ColorB, checker);
            }
            else if (Type == 3)
            {
                vec2 p = (uv - 0.5) * Size / max(Scale, 1.0);
                vec2 g = abs(fract(p) - 0.5);
                float line = 1.0 - smoothstep(0.0, max(Softness, 0.01), min(g.x, g.y));
                color = vec4(ColorA.rgb, ColorA.a * line);
            }
            else
            {
                float d = distance(uv, Center);
                float m = 1.0 - smoothstep(Scale - Softness, Scale + Softness, d);
                color = vec4(ColorA.rgb, ColorA.a * m);
            }

            fragColor = vec4(color.rgb, color.a * texture(Source, vUv).a);
        }
        """;

    public static final String DISTORT = """
        #version 150 core

        uniform sampler2D Source;
        uniform sampler2D Map;
        uniform int Mode;
        uniform int UseMap;
        uniform vec2 Size;
        uniform float Amount;
        uniform float Angle;
        uniform vec2 Center;
        uniform float Radius;
        uniform float Frequency;
        uniform float Phase;
        uniform float Evolution;
        uniform int Vertical;

        in vec2 vUv;
        out vec4 fragColor;

        float hash(vec2 p)
        {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float noise(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        void main()
        {
            vec2 uv = vUv;
            vec2 texel = 1.0 / max(Size, vec2(1.0));
            vec2 dir = uv - Center;

            if (Mode == 0)
            {
                vec2 offset;

                if (UseMap == 1)
                {
                    float d = texture(Map, uv).r;
                    offset = vec2(d - 0.5, d - 0.5) * Amount * texel;
                }
                else
                {
                    vec2 n = vec2(noise(uv * 80.0 + Evolution), noise(uv * 80.0 + Evolution + 13.1)) - 0.5;
                    offset = n * Amount * texel;
                }

                uv += offset;
            }
            else if (Mode == 1 || Mode == 2)
            {
                float d = length(dir) / max(Radius, 0.0001);
                float f = Mode == 1 ? (1.0 + Amount * (1.0 - d)) : (1.0 - Amount * (1.0 - d));
                f = clamp(f, 0.0, 2.0);
                uv = Center + dir / max(f, 0.0001);
            }
            else if (Mode == 3)
            {
                float d = length(dir) / max(Radius, 0.0001);
                float a = radians(Angle) * (1.0 - clamp(d, 0.0, 1.0));
                float s = sin(a);
                float c = cos(a);
                uv = Center + mat2(c, -s, s, c) * dir;
            }
            else
            {
                float wave = sin((uv.x + uv.y) * Frequency + Phase) * Amount * 0.001;
                uv += Vertical == 1 ? vec2(0.0, wave) : vec2(wave, 0.0);
            }

            fragColor = texture(Source, clamp(uv, vec2(0.0), vec2(1.0)));
        }
        """;

    public static final String PIXEL = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Size;
        uniform int Mode;
        uniform float Amount;
        uniform float Invert;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            if (Mode == 0)
            {
                vec2 cell = 1.0 / (Size / max(Amount, 1.0));
                vec2 uv = floor(vUv / cell) * cell + cell * 0.5;
                fragColor = texture(Source, uv);
            }
            else
            {
                vec2 t = 1.0 / Size;
                vec3 c = texture(Source, vUv).rgb;
                vec3 sx = texture(Source, vUv + vec2(t.x, 0.0)).rgb - texture(Source, vUv - vec2(t.x, 0.0)).rgb;
                vec3 sy = texture(Source, vUv + vec2(0.0, t.y)).rgb - texture(Source, vUv - vec2(0.0, t.y)).rgb;
                float edge = clamp(length(sx) + length(sy), 0.0, 1.0) * Amount;

                if (Invert > 0.5)
                {
                    edge = 1.0 - edge;
                }

                fragColor = vec4(vec3(edge), texture(Source, vUv).a);
            }
        }
        """;

    public static final String STROKE = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Texel;
        uniform float Width;
        uniform vec4 Color;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            float a = 0.0;

            for (int i = 0; i < 16; i++)
            {
                float angle = 6.2831853 * float(i) / 16.0;
                vec2 offset = vec2(cos(angle), sin(angle)) * Width * Texel;
                a = max(a, texture(Source, clamp(vUv + offset, vec2(0.0), vec2(1.0))).a);
            }

            fragColor = vec4(Color.rgb, Color.a * a);
        }
        """;

    public static final String TRANSITION = """
        #version 150 core

        uniform sampler2D Source;
        uniform int Mode;
        uniform float Progress;
        uniform float Softness;
        uniform float Angle;
        uniform vec2 Center;
        uniform float Scale;
        uniform float Seed;

        in vec2 vUv;
        out vec4 fragColor;

        float hash(vec2 p)
        {
            return fract(sin(dot(p, vec2(12.9898, 78.233)) + Seed) * 43758.5453);
        }

        void main()
        {
            vec4 color = texture(Source, vUv);
            float p = clamp(Progress, 0.0, 1.0);
            float mask = 1.0;

            if (Mode == 0)
            {
                vec2 dir = vec2(cos(radians(Angle)), sin(radians(Angle)));
                float coord = clamp(dot(vUv - 0.5, dir) + 0.5, 0.0, 1.0);
                mask = smoothstep(p + Softness, p - Softness, coord);
            }
            else if (Mode == 1)
            {
                float d = distance(vUv, Center) / 0.7071;
                mask = smoothstep(p + Softness, p - Softness, d);
            }
            else
            {
                mask = step(hash(floor(vUv * Scale)), p);
            }

            fragColor = vec4(color.rgb, color.a * mask);
        }
        """;

    public static final String DEPTH = """
        #version 150 core

        uniform sampler2D Source;
        uniform sampler2D Depth;
        uniform int Mode;
        uniform float Near;
        uniform float Far;
        uniform float Softness;
        uniform float Amount;
        uniform float Radius;
        uniform vec2 Texel;
        uniform vec4 Color;
        uniform int DepthAvailable;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);
            float d = DepthAvailable == 1 ? texture(Depth, vUv).r : 0.0;

            if (Mode == 0)
            {
                float m = smoothstep(Near, Near + max(Softness, 0.0001), d)
                    * (1.0 - smoothstep(Far - max(Softness, 0.0001), Far, d));
                c.a *= m;
            }
            else if (Mode == 1)
            {
                float f = clamp((d - Near) / max(Far - Near, 0.0001), 0.0, 1.0) * Amount;
                c.rgb = mix(c.rgb, Color.rgb, f);
            }
            else
            {
                float coc = clamp(abs(d - Near) / max(Softness, 0.0001), 0.0, 1.0) * Radius;
                vec4 sum = c;
                float total = 1.0;
                for (int i = -3; i <= 3; i++)
                {
                    for (int j = -3; j <= 3; j++)
                    {
                        float w = 1.0 - (abs(float(i)) + abs(float(j))) / 8.0;
                        sum += texture(Source, clamp(vUv + vec2(float(i), float(j)) * Texel * coc, vec2(0.0), vec2(1.0))) * w;
                        total += w;
                    }
                }
                c = sum / total;
            }

            fragColor = c;
        }
        """;
}
