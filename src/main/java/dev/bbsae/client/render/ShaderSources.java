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
            /* A blend mode only applies where the backdrop actually has coverage. Without this
             * mix, multiply/screen/overlay over the transparent part of the comp produce black
             * or white, which reads as "blend modes do nothing / destroy the layer". */
            vec3 blended = mix(srcRgb, blendColor(b.rgb, srcRgb), b.a);
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
        uniform vec3 LayerU;
        uniform vec3 LayerV;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

        void main()
        {
            vec4 c = texture(Source, vUv);
            float angle = radians(Angle);
            vec2 dir = vec2(cos(angle), sin(angle));
            float t = clamp(dot(layerUv(vUv) - 0.5, dir) + 0.5, 0.0, 1.0);
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
        uniform vec3 LayerU;
        uniform vec3 LayerV;
        uniform vec2 LayerSize;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

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
                float d = distance(layerUv(vUv), vec2(0.5));
                float edge = smoothstep(VignetteRadius, VignetteRadius + VignetteSoftness, d);
                c.rgb = mix(c.rgb, VignetteColor.rgb, edge * Vignette);
            }

            if (NoiseAmount > 0.0)
            {
                float n = hash(floor(layerUv(vUv) * LayerSize / max(NoiseScale, 1.0))) - 0.5;
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
        uniform float Seed;
        uniform float Brightness;
        uniform vec3 LayerU;
        uniform vec3 LayerV;
        uniform vec2 LayerSize;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

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
            vec2 uv = layerUv(vUv);
            vec4 color = vec4(0.0);

            if (Type == 0)
            {
                vec2 dir = vec2(cos(radians(Angle)), sin(radians(Angle)));
                float t = clamp(dot(uv - 0.5, dir) + 0.5, 0.0, 1.0);
                /* Softness is the width of the transition around the middle. 1 (the registry
                 * default) is a full smooth ramp; small values give a two-tone split. The Java
                 * fallback used to be 0.05, which turned migrated projects into a hard split. */
                t = smoothstep(0.5 - clamp(Softness, 0.001, 1.0) * 0.5, 0.5 + clamp(Softness, 0.001, 1.0) * 0.5, t);
                /* Gradient ramp replaces colour and keeps the layer's alpha. Taking ColorB's
                 * alpha (registry default 0x00000000) made the far half of every shape vanish. */
                color = vec4(mix(ColorA.rgb, ColorB.rgb, t), 1.0);
            }
            else if (Type == 1)
            {
                float n = fbm(uv * Scale + Evolution + Seed);
                n = clamp((n - 0.5) * Contrast + 0.5, 0.0, 1.0);
                color = vec4(mix(ColorA.rgb, ColorB.rgb, n), 1.0);
            }
            else if (Type == 2)
            {
                vec2 p = rot(radians(Angle)) * (uv - 0.5) * LayerSize / max(Scale, 1.0);
                float checker = mod(floor(p.x) + floor(p.y), 2.0);
                color = mix(ColorA, ColorB, checker);
            }
            else if (Type == 3)
            {
                vec2 p = (uv - 0.5) * LayerSize / max(Scale, 1.0);
                vec2 g = abs(fract(p) - 0.5);
                float line = 1.0 - smoothstep(0.0, max(Softness, 0.01), min(g.x, g.y));
                color = vec4(ColorA.rgb, ColorA.a * line);
            }
            else
            {
                /* UV is normalised per axis, so a plain distance() draws an ellipse on any
                 * non-square layer. Measure in pixels and normalise by the shorter side. */
                vec2 p = (uv - Center) * LayerSize;
                float d = length(p) / max(min(LayerSize.x, LayerSize.y), 1.0);
                float m = 1.0 - smoothstep(Scale - Softness, Scale + Softness, d);
                color = vec4(ColorA.rgb, ColorA.a * m);
            }

            color.rgb += Brightness;
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
        uniform vec3 LayerU;
        uniform vec3 LayerV;
        uniform vec2 LayerSize;
        uniform vec2 LayerX;
        uniform vec2 LayerY;
        uniform vec2 LayerOrigin;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

        /* The comp FBO is drawn with a y-down ortho, so texture V runs opposite to composition
         * y. Every local->comp conversion used for resampling has to flip V, otherwise bulge,
         * pinch, twist and displacement mirror their result vertically. */
        vec2 localToCompUv(vec2 luv)
        {
            vec2 lp = vec2(luv.x * LayerSize.x, luv.y * LayerSize.y);
            vec2 px = LayerX * lp.x + LayerY * lp.y + LayerOrigin;

            return vec2(px.x / Size.x, 1.0 - px.y / Size.y);
        }

        vec2 localDeltaToComp(vec2 d)
        {
            vec2 px = LayerX * d.x + LayerY * d.y;

            return vec2(px.x / Size.x, -px.y / Size.y);
        }

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
            vec2 luv = layerUv(vUv);
            vec2 ldir = luv - Center;
            vec2 uv = vUv;

            /* Radius is a fraction of the layer's half-diagonal: 1.0 reaches the corners of any
             * aspect ratio. Measuring in pixels (not UV) is what keeps a circular bulge circular
             * on a 16:9 layer, and what made small layers look like the effect did nothing. */
            float halfDiagonal = max(length(LayerSize) * 0.5, 1.0);
            float dist = length(ldir * LayerSize) / halfDiagonal;

            if (Mode == 0)
            {
                vec2 offset;

                if (UseMap == 1)
                {
                    float d = texture(Map, vUv).r;
                    offset = vec2(d - 0.5, d - 0.5) * Amount;
                }
                else
                {
                    vec2 n = vec2(noise(luv * 80.0 + Evolution), noise(luv * 80.0 + Evolution + 13.1)) - 0.5;
                    offset = n * Amount;
                }

                /* Amount is in pixels; convert through the layer basis so displacement follows
                 * rotation and scale instead of always pushing along the comp axes. */
                vec2 localPx = vec2(offset.x / max(LayerSize.x, 1.0), offset.y / max(LayerSize.y, 1.0));
                uv += localDeltaToComp(localPx);
            }
            else if (Mode == 1 || Mode == 2)
            {
                /* Outside the effect radius the layer must stay untouched. Without this guard a
                 * large radius folded the whole layer inside out. */
                if (dist >= Radius)
                {
                    fragColor = texture(Source, clamp(vUv, vec2(0.0), vec2(1.0)));
                    return;
                }

                float d = dist / max(Radius, 0.0001);
                float falloff = 1.0 - smoothstep(0.0, 1.0, d);
                float f = Mode == 1 ? (1.0 + Amount * falloff) : (1.0 - Amount * falloff);
                f = clamp(f, 0.08, 3.0);
                uv = localToCompUv(Center + ldir / f);
            }
            else if (Mode == 3)
            {
                if (dist >= Radius)
                {
                    fragColor = texture(Source, clamp(vUv, vec2(0.0), vec2(1.0)));
                    return;
                }

                float d = dist / max(Radius, 0.0001);
                float a = radians(Angle) * pow(1.0 - clamp(d, 0.0, 1.0), 1.5);
                float s = sin(a);
                float c = cos(a);
                uv = localToCompUv(Center + mat2(c, -s, s, c) * ldir);
            }
            else
            {
                /* Frequency is radians per pixel (2π / wavelength), so the wavelength parameter
                 * means the same thing on a 200 px title and a 1920 px background. */
                float travel = Vertical == 1 ? luv.y * LayerSize.y : luv.x * LayerSize.x;
                float wave = sin(travel * Frequency + Phase) * Amount;
                vec2 localPx = Vertical == 1
                    ? vec2(0.0, wave / max(LayerSize.y, 1.0))
                    : vec2(wave / max(LayerSize.x, 1.0), 0.0);

                uv += localDeltaToComp(localPx);
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
        uniform float Phase;
        uniform vec4 Param;
        uniform vec4 DotColor;
        uniform vec4 PaperColor;
        uniform vec3 LayerU;
        uniform vec3 LayerV;
        uniform vec2 LayerSize;
        uniform vec2 LayerX;
        uniform vec2 LayerY;
        uniform vec2 LayerOrigin;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

        /* Local layer pixel -> comp texture UV. Effects are evaluated before the layer transform,
         * so every resample has to go through this mapping; sampling vUv directly would keep a
         * mirror seam or a halftone screen glued to the composition while the layer moves. */
        vec2 localToSource(vec2 luv)
        {
            vec2 lp = luv * LayerSize;
            vec2 comp = LayerX * lp.x + LayerY * lp.y + LayerOrigin;

            return vec2(comp.x / max(Size.x, 1.0), 1.0 - comp.y / max(Size.y, 1.0));
        }

        vec4 sampleLocal(vec2 luv)
        {
            /* Outside the layer rect there is no layer content, even when that local position
             * still falls inside the composition FBO. Returning transparent prevents shifted
             * samples (glitch, mirror, kaleidoscope) from smearing the layer edge. */
            if (any(lessThan(luv, vec2(0.0))) || any(greaterThan(luv, vec2(1.0))))
            {
                return vec4(0.0);
            }

            return texture(Source, clamp(localToSource(luv), vec2(0.0), vec2(1.0)));
        }

        float hash21(vec2 p)
        {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float luma(vec3 c)
        {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        void main()
        {
            vec4 source = texture(Source, vUv);
            vec3 rgb = source.rgb;
            float alpha = source.a;
            vec2 luv = layerUv(vUv);

            if (Mode == 0)
            {
                /* Mosaic / pixelate. */
                vec2 cell = 1.0 / (LayerSize / max(Amount, 1.0));
                vec2 snapped = floor(luv / cell) * cell + cell * 0.5;
                vec4 c = sampleLocal(snapped);

                rgb = c.rgb;
                alpha = c.a;
            }
            else if (Mode == 1)
            {
                /* Find edges in layer space, so the outline follows a scaled/rotated layer. */
                vec2 t = 1.0 / LayerSize;
                vec3 sx = sampleLocal(luv + vec2(t.x, 0.0)).rgb - sampleLocal(luv - vec2(t.x, 0.0)).rgb;
                vec3 sy = sampleLocal(luv + vec2(0.0, t.y)).rgb - sampleLocal(luv - vec2(0.0, t.y)).rgb;
                float edge = clamp(length(sx) + length(sy), 0.0, 1.0) * Amount;

                if (Invert > 0.5)
                {
                    edge = 1.0 - edge;
                }

                rgb = vec3(edge);
            }
            else if (Mode == 2)
            {
                /* Scanlines: local row coordinate + a scroll offset. The sine band is smooth
                 * because a hard 1-pixel fract() window can miss every row at non-integer scales. */
                float spacing = max(1.0, Param.x);
                float row = luv.y * LayerSize.y + Phase;
                float wave = 0.5 + 0.5 * sin(row * 6.2831853 / spacing);
                float amount = clamp(Amount, 0.0, 1.0);

                rgb *= 1.0 - amount * 0.55 * wave;
            }
            else if (Mode == 3)
            {
                /* Digital glitch: local horizontal bands jump sideways and split RGB. */
                float bands = max(2.0, Param.x);
                float band = floor(clamp(luv.y, 0.0, 1.0) * bands);
                float tick = floor(Phase * 14.0) + Param.y;
                float n = hash21(vec2(band + Param.y * 0.137, tick));
                float gate = step(0.72, n);
                float offset = (n - 0.5) * Amount / max(LayerSize.x, 1.0) * gate;
                float split = Amount * 0.35 / max(LayerSize.x, 1.0);
                vec2 uv = luv + vec2(offset, 0.0);
                vec4 cr = sampleLocal(uv + vec2(split, 0.0));
                vec4 cg = sampleLocal(uv);
                vec4 cb = sampleLocal(uv - vec2(split, 0.0));

                rgb = vec3(cr.r, cg.g, cb.b);
                alpha = cg.a;
            }
            else if (Mode == 4)
            {
                /* Halftone dots in the style of Alight Motion's Halftone Dots: the number of
                 * dots is measured across the project width, the luminance is sampled at the
                 * dot centre (not at the current pixel), alternate rows can be staggered, and
                 * ink/paper colours are independent so the effect can also be used as a stencil
                 * (opaque dots over a transparent paper). */
                float dots = max(2.0, Param.x);
                float cell = max(1.0, Size.x / dots);
                float angle = radians(Param.z);
                float ca = cos(angle);
                float sa = sin(angle);
                mat2 rot = mat2(ca, -sa, sa, ca);
                mat2 invRot = mat2(ca, sa, -sa, ca);
                vec2 px = luv * LayerSize;
                vec2 rp = rot * px;
                float row = floor(rp.y / cell);

                rp.x += mod(row, 2.0) * Param.y * cell;

                vec2 idx = floor(rp / cell);
                vec2 grid = fract(rp / cell) - 0.5;
                float d = length(grid);
                vec2 centerPx = invRot * ((idx + 0.5) * cell);
                vec4 centerSample = sampleLocal(centerPx / LayerSize);
                float darkness = clamp(1.0 - luma(centerSample.rgb), 0.0, 1.0)
                    * clamp(centerSample.a, 0.0, 1.0);
                float strength = clamp(Amount, 0.0, 1.0);
                float radius = max(0.001, 0.62 * sqrt(darkness) * mix(0.35, 1.0, strength));
                float dotMask = 1.0 - smoothstep(radius - 0.05, radius + 0.01, d);
                float ink = smoothstep(0.0, 0.35, strength);
                vec3 inkColor = mix(PaperColor.rgb, DotColor.rgb, dotMask);
                float inkAlpha = source.a * mix(PaperColor.a, DotColor.a, dotMask);

                rgb = mix(rgb, inkColor, ink);
                alpha = mix(alpha, inkAlpha, ink);
            }
            else if (Mode == 5)
            {
                /* Classic grey relief: the directional luminance derivative is added to a 0.5
                 * base. Amount 0 is identity; Amount 1 is a full grey emboss; larger values
                 * increase the relief contrast without changing the colour. */
                float angle = radians(Param.z);
                vec2 dir = vec2(cos(angle), sin(angle)) / max(LayerSize, vec2(1.0));
                float g = luma(sampleLocal(luv + dir).rgb) - luma(sampleLocal(luv - dir).rgb);
                float shade = clamp(0.5 + g * clamp(Amount, 0.0, 3.0) * 2.0, 0.0, 1.0);

                rgb = mix(rgb, vec3(shade), clamp(Amount, 0.0, 1.0));
            }
            else if (Mode == 6)
            {
                /* Mirror: overlay a mirrored copy of the layer, like Alight Motion's Mirror.
                 * Amount is the copy opacity. With opacity 1 the copy hides the original half,
                 * which is the familiar fold; with a lower value both halves are visible. */
                vec2 muv = luv;

                if (Param.x < 0.5)
                {
                    muv.x = abs(muv.x * 2.0 - 1.0);
                }
                else
                {
                    muv.y = abs(muv.y * 2.0 - 1.0);
                }

                vec4 orig = sampleLocal(luv);
                vec4 mir = sampleLocal(muv);
                float k = clamp(Amount, 0.0, 1.0) * clamp(mir.a, 0.0, 1.0);
                float outA = k + orig.a * (1.0 - k);

                rgb = outA > 0.0001
                    ? (mir.rgb * k + orig.rgb * orig.a * (1.0 - k)) / outA
                    : vec3(0.0);
                alpha = outA;
            }
            else if (Mode == 7)
            {
                /* Kaleidoscope: fold layer-local polar coordinates into N mirrored sectors. */
                float segments = max(2.0, Param.x);
                float aspect = LayerSize.x / max(LayerSize.y, 1.0);
                vec2 p = (luv - 0.5) * vec2(aspect, 1.0);
                float r = length(p);
                float a = atan(p.y, p.x) + radians(Param.z);
                float sector = 6.2831853 / segments;

                a = mod(a, sector);
                a = abs(a - sector * 0.5);

                vec2 q = vec2(cos(a), sin(a)) * r;
                vec4 c = sampleLocal(vec2(q.x / aspect, q.y) + 0.5);

                rgb = c.rgb;
                alpha = c.a;
            }
            else
            {
                /* VHS: tracking wobble + chroma bleed + local scanlines + tape noise.
                 * Amount 0 is identity (all terms scale with it), so the effect can be keyframed
                 * in and out instead of popping. */
                float amount = clamp(Amount, 0.0, 1.0);
                float noise = clamp(Param.z, 0.0, 1.0);
                float wobble = (sin(luv.y * LayerSize.y * 0.9 + Phase * 6.0)
                    + 0.5 * sin(luv.y * LayerSize.y * 2.3 - Phase * 3.0))
                    * amount * 1.5 / max(LayerSize.x, 1.0);
                vec2 uv = luv + vec2(wobble, 0.0);
                float split = amount * 3.0 / max(LayerSize.x, 1.0);
                vec4 cr = sampleLocal(uv + vec2(split, 0.0));
                vec4 cg = sampleLocal(uv);
                vec4 cb = sampleLocal(uv - vec2(split, 0.0));

                rgb = vec3(cr.r, cg.g, cb.b);
                alpha = cg.a;

                float scan = 1.0 - amount * 0.14
                    * (0.5 + 0.5 * sin(luv.y * LayerSize.y * 1.4 + Phase * 3.0));
                vec2 grainSeed = luv * LayerSize + vec2(floor(Phase * 55.0), floor(Phase * 31.0));
                float grain = (hash21(grainSeed) - 0.5) * amount * noise * 0.35;

                rgb = rgb * scan + grain;
            }

            fragColor = vec4(clamp(rgb, vec3(0.0), vec3(1.0)), clamp(alpha, 0.0, 1.0));
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
        uniform vec3 LayerU;
        uniform vec3 LayerV;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

        float hash(vec2 p)
        {
            return fract(sin(dot(p, vec2(12.9898, 78.233)) + Seed) * 43758.5453);
        }

        void main()
        {
            vec4 color = texture(Source, vUv);
            vec2 luv = layerUv(vUv);
            float p = clamp(Progress, 0.0, 1.0);
            float mask = 1.0;

            if (Mode == 0)
            {
                vec2 dir = vec2(cos(radians(Angle)), sin(radians(Angle)));
                float coord = clamp(dot(luv - 0.5, dir) + 0.5, 0.0, 1.0);
                mask = smoothstep(p + Softness, p - Softness, coord);
            }
            else if (Mode == 1)
            {
                float d = distance(luv, Center) / 0.7071;
                mask = smoothstep(p + Softness, p - Softness, d);
            }
            else
            {
                mask = step(hash(floor(luv * Scale)), p);
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

    /**
     * 2D block shatter: the layer is cut into a grid, each cell flies outward along a
     * deterministic random direction and disappears in a random order. Sampling is done at the
     * shifted position (a true scatter would need a geometry pass); at sane spread values this
     * reads as broken blocks with transparent cracks.
     */
    public static final String RGB_SPLIT = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec2 Texel;
        uniform vec2 Offset;
        uniform float Amount;

        in vec2 vUv;
        out vec4 fragColor;

        void main()
        {
            vec4 c = texture(Source, vUv);
            vec2 d = Offset * Texel;
            float r = texture(Source, clamp(vUv + d, vec2(0.0), vec2(1.0))).r;
            float b = texture(Source, clamp(vUv - d, vec2(0.0), vec2(1.0))).b;
            vec3 split = vec3(r, c.g, b);

            fragColor = vec4(mix(c.rgb, split, clamp(Amount, 0.0, 1.0)), c.a);
        }
        """;

    public static final String SHATTER = """
        #version 150 core

        uniform sampler2D Source;
        uniform float Progress;
        uniform float Blocks;
        uniform float Spread;
        uniform float Rotation;
        uniform float Gravity;
        uniform float Seed;
        uniform vec3 LayerU;
        uniform vec3 LayerV;
        uniform vec2 LayerSize;
        uniform vec2 LayerX;
        uniform vec2 LayerY;
        uniform vec2 LayerOrigin;
        uniform vec2 Size;

        in vec2 vUv;
        out vec4 fragColor;

        vec2 layerUv(vec2 uv)
        {
            return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
        }

        vec2 localToCompUv(vec2 luv)
        {
            vec2 lp = vec2(luv.x * LayerSize.x, luv.y * LayerSize.y);
            vec2 px = LayerX * lp.x + LayerY * lp.y + LayerOrigin;

            return vec2(px.x / Size.x, 1.0 - px.y / Size.y);
        }

        vec2 hash2(vec2 p)
        {
            p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
            return fract(sin(p) * 43758.5453);
        }

        void main()
        {
            vec2 luv = layerUv(vUv);

            if (luv.x < -0.001 || luv.y < -0.001 || luv.x > 1.001 || luv.y > 1.001)
            {
                fragColor = vec4(0.0);
                return;
            }

            vec2 grid = vec2(max(2.0, floor(Blocks + 0.5)));
            vec2 cell = floor(luv * grid);
            vec2 local = fract(luv * grid);
            vec2 rnd = hash2(cell + Seed);
            vec2 dir = normalize(rnd - 0.5 + vec2(0.0001, 0.0001));
            float p = clamp(Progress, 0.0, 1.0);
            float order = hash2(cell + Seed + 17.0).x;
            float start = order * 0.6;
            float k = clamp((p - start) / 0.4, 0.0, 1.0);

            /* Inverse mapping: this pixel belongs to cell's block, which has flown away by
             * `fly` cells. Outside the moved block we output transparent -> real cracks and
             * gaps between shards, not a smear of the neighbour's pixels. */
            vec2 fly = dir * Spread * k + vec2(0.0, Gravity * k * k * 0.5);
            vec2 rel = local - fly - 0.5;

            if (abs(rel.x) > 0.5 || abs(rel.y) > 0.5)
            {
                fragColor = vec4(0.0);
                return;
            }

            float angle = (rnd.y - 0.5) * Rotation * k * 2.4;
            float s = sin(-angle);
            float c = cos(-angle);
            vec2 srcRel = mat2(c, -s, s, c) * rel;
            srcRel /= max(0.55, 1.0 - k * 0.18);

            vec2 src = (cell + 0.5 + srcRel) / grid;
            vec4 color = texture(Source, localToCompUv(clamp(src, vec2(0.0), vec2(1.0))));

            /* Slight inner border so shards read as solid blocks rather than a UV warp. */
            float edge = min(min(0.5 - abs(rel.x), 0.5 - abs(rel.y)), 0.5);
            float border = smoothstep(0.0, 0.035, edge);
            float alpha = color.a * mix(0.35, 1.0, border) * (1.0 - smoothstep(0.72, 1.0, k));
            vec3 rgb = color.rgb * mix(0.55, 1.0, border);

            fragColor = vec4(rgb, alpha);
        }
        """;

    /**
     * Three-way colour grade (lift / gamma / gain) plus exposure, contrast, saturation,
     * temperature and tint. Neutral wheel colours are 0x808080; the shader maps that to identity.
     */
    public static final String GRADE = """
        #version 150 core

        uniform sampler2D Source;
        uniform vec4 Lift;
        uniform vec4 Gamma;
        uniform vec4 Gain;
        uniform float Exposure;
        uniform float Contrast;
        uniform float Saturation;
        uniform float Vibrance;
        uniform float Temperature;
        uniform float Tint;
        uniform float Hue;
        uniform float Fade;
        uniform float CurveBlack;
        uniform float CurveShadow;
        uniform float CurveMid;
        uniform float CurveHighlight;
        uniform float CurveWhite;

        in vec2 vUv;
        out vec4 fragColor;

        /* Master tone curve through five control points (black, shadows, mid, highlights,
         * white). Catmull-Rom keeps the curve smooth while the UI drags a single point; the
         * endpoints stay pinned to 0 and 1 so grading never lifts or clips the whole frame. */
        float curvePoint(int i)
        {
            if (i <= 0) return 0.0 + CurveBlack;
            if (i == 1) return 0.25 + CurveShadow;
            if (i == 2) return 0.5 + CurveMid;
            if (i == 3) return 0.75 + CurveHighlight;

            return 1.0 + CurveWhite;
        }

        float toneCurve(float x)
        {
            float t = clamp(x, 0.0, 1.0) * 4.0;
            int i = int(floor(t));
            float f = fract(t);
            float p0 = curvePoint(i - 1);
            float p1 = curvePoint(i);
            float p2 = curvePoint(i + 1);
            float p3 = curvePoint(i + 2);
            float m0 = (p2 - p0) * 0.5;
            float m1 = (p3 - p1) * 0.5;
            float f2 = f * f;
            float f3 = f2 * f;

            return clamp((2.0 * f3 - 3.0 * f2 + 1.0) * p1
                + (f3 - 2.0 * f2 + f) * m0
                + (-2.0 * f3 + 3.0 * f2) * p2
                + (f3 - f2) * m1, 0.0, 1.0);
        }

        /* Hue rotation around the neutral axis. Cheap, luminance-neutral and continuous, unlike
         * converting to HSV and back (which clips saturated colours and shifts brightness). */
        vec3 hueRotate(vec3 c, float degrees)
        {
            if (abs(degrees) < 0.001)
            {
                return c;
            }

            float a = radians(degrees);
            float s = sin(a);
            float co = cos(a);
            const vec3 k = vec3(0.57735026919);

            return c * co + cross(k, c) * s + k * dot(k, c) * (1.0 - co);
        }

        void main()
        {
            vec4 c = texture(Source, vUv);
            vec3 rgb = c.rgb * pow(2.0, Exposure);

            /* White balance first: temperature moves red against blue, tint moves green against
             * magenta. Doing it before lift/gain keeps the wheels intuitive. */
            rgb.r *= 1.0 + Temperature * 0.18;
            rgb.b *= 1.0 - Temperature * 0.18;
            rgb.g *= 1.0 + Tint * 0.16;
            rgb.r *= 1.0 - Tint * 0.04;
            rgb.b *= 1.0 - Tint * 0.04;

            vec3 lift = (Lift.rgb - 0.5) * 0.5;
            vec3 gain = 1.0 + (Gain.rgb - 0.5);
            vec3 gamma = 1.0 + (Gamma.rgb - 0.5);

            rgb = rgb * gain + lift;
            rgb = pow(max(rgb, vec3(0.0)), 1.0 / max(gamma, vec3(0.05)));
            rgb = (rgb - 0.5) * (1.0 + Contrast) + 0.5;

            rgb = vec3(toneCurve(rgb.r), toneCurve(rgb.g), toneCurve(rgb.b));
            rgb = hueRotate(rgb, Hue);

            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(luma), rgb, max(0.0, Saturation));

            float maxc = max(rgb.r, max(rgb.g, rgb.b));
            float minc = min(rgb.r, min(rgb.g, rgb.b));
            float sat = clamp(maxc - minc, 0.0, 1.0);
            float vib = 1.0 + Vibrance * (1.0 - sat);
            luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(luma), rgb, max(0.0, vib));

            /* Matte / film fade: lift the blacks and pull a little saturation. */
            if (Fade > 0.0001)
            {
                float fade = clamp(Fade, 0.0, 1.0);
                luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                rgb = mix(vec3(luma), rgb, 1.0 - fade * 0.35);
                rgb = rgb * (1.0 - fade * 0.22) + 0.10 * fade;
            }

            fragColor = vec4(clamp(rgb, vec3(0.0), vec3(1.0)), c.a);
        }
        """;

}
