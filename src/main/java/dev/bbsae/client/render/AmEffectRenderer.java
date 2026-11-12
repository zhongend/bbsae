package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.BbsAeAddon;
import dev.bbsae.comp.AmEffectDef;
import dev.bbsae.comp.AmEffectLibrary;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the reference-compatible catalogue from {@link AmEffectLibrary}.
 *
 * <p>One program per definition, compiled on first use and cached until the caches are cleared.
 * The wrapper injects the standard uniform set ({@code Size/Texel/Layer*}) and one uniform per
 * declared parameter, so a definition only supplies its {@code main()} body.</p>
 */
public final class AmEffectRenderer
{
    private static final Map<String, GlslProgram> PROGRAMS = new HashMap<>();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();

    private AmEffectRenderer()
    {}

    public static GlFramebuffer apply(CompLayer layer, CompEffect effect, GlFramebuffer input,
                                      CompRenderContext context, EffectSpace space)
    {
        return applyInternal(layer, effect, input, context, space, false);
    }

    /**
     * Test hook: renders even when the neutral predicate says the effect is the identity.
     *
     * <p>The zero-value contract is only meaningful if the shader body itself is the identity; the
     * public path returns early for neutral effects, which would make a pixel audit pass vacuously.
     * Package-private so only the render tests can bypass the shortcut.</p>
     */
    static GlFramebuffer applyForced(CompLayer layer, CompEffect effect, GlFramebuffer input,
                                     CompRenderContext context, EffectSpace space)
    {
        return applyInternal(layer, effect, input, context, space, true);
    }

    private static GlFramebuffer applyInternal(CompLayer layer, CompEffect effect, GlFramebuffer input,
                                               CompRenderContext context, EffectSpace space, boolean force)
    {
        AmEffectDef definition = effect == null ? null : AmEffectLibrary.get(effect.type);

        if (definition == null || input == null)
        {
            return input;
        }

        if (!force && AmEffectLibrary.isNeutral(effect))
        {
            return input;
        }

        /* Pure blur effects are two separable passes over premultiplied content; running them
         * through a fragment body would either be a huge tap loop or unpremultiplied bleeding. */
        if (definition.blurKey != null)
        {
            float radius = effect.valueF(definition.blurKey, 0F);

            if (radius <= 0.5F)
            {
                return input;
            }

            int iterations = definition.iterationsKey == null ? 1
                : Math.max(1, Math.min(4, Math.round(effect.valueF(definition.iterationsKey, 1F))));
            GlFramebuffer blurred = input;

            float texelX = context == null ? 1F / Math.max(1, input.width) : 1F / Math.max(1, context.width);
            float texelY = context == null ? 1F / Math.max(1, input.height) : 1F / Math.max(1, context.height);

            for (int i = 0; i < iterations; i++)
            {
                GlFramebuffer next = PassRenderer.blur(blurred, radius, definition.boxBlur, texelX, texelY);

                if (blurred != input)
                {
                    FboPool.release(blurred);
                }

                blurred = next;
            }

            return blurred;
        }

        GlslProgram program = program(definition);

        if (program == null)
        {
            return input;
        }

        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        output.begin();
        GlStateManager._disableBlend();
        program.use();
        PassRenderer.bind(program, "Source", 0, input);

        if (definition.usesComp)
        {
            /* The composition below the layer when the pipeline provides it; the effect's own
             * input is the documented fallback for baked 3D layers and isolated previews. */
            GlFramebuffer backdrop = context == null ? null : context.backdrop;

            PassRenderer.bind(program, "Comp", 1, backdrop == null ? input : backdrop);
        }

        space.bind(program);
        /* Pixel parameters are authored in composition pixels, not framebuffer pixels. The proxy
         * preview renders into a smaller FBO, so using its size here made every radius (blur,
         * ripple, glass, shadows) shrink by the proxy scale - the effect looked weaker in preview
         * than in the full-resolution export. FBO UVs still cover the whole composition, so the
         * pixel -> UV conversion only needs the composition size. */
        int pixelWidth = context == null ? input.width : Math.max(1, context.width);
        int pixelHeight = context == null ? input.height : Math.max(1, context.height);

        program.set("Size", pixelWidth, pixelHeight);
        program.set("Texel", 1F / pixelWidth, 1F / pixelHeight);
        program.set("LayerTexel", 1F / Math.max(1F, space.width), 1F / Math.max(1F, space.height));
        program.set("Time", context == null ? 0F : context.time);

        for (AmEffectDef.Param param : definition.params)
        {
            program.set("u_" + param.key, effect.valueF(param.key, param.defaultValue));
        }

        for (AmEffectDef.ColorParam color : definition.colors)
        {
            setColor(program, "u_" + color.key, effect.color(color.key, color.defaultValue));
        }

        program.drawFullscreen();

        return output;
    }

    static GlslProgram program(AmEffectDef definition)
    {
        GlslProgram cached = PROGRAMS.get(definition.id);

        if (cached != null)
        {
            return cached;
        }

        if (FAILED.contains(definition.id))
        {
            return null;
        }

        String name = "am_" + definition.id;

        try
        {
            GlslProgram program = new GlslProgram(name, ShaderSources.VERTEX, source(definition));

            if (!program.isValid())
            {
                FAILED.add(definition.id);
                BbsAeAddon.LOGGER.error("Reference effect {} could not be compiled", definition.id);

                return null;
            }

            PROGRAMS.put(definition.id, program);

            return program;
        }
        catch (RuntimeException e)
        {
            FAILED.add(definition.id);
            BbsAeAddon.LOGGER.error("Reference effect {} failed to build", definition.id, e);

            return null;
        }
    }

    /** Fragment source for one definition, with the shared helpers and uniforms prepended. */
    static String source(AmEffectDef definition)
    {
        StringBuilder code = new StringBuilder(4096);

        code.append("""
            #version 150 core

            uniform sampler2D Source;
            uniform sampler2D Comp;
            uniform vec2 Size;
            uniform vec2 Texel;
            uniform vec2 LayerSize;
            uniform vec3 LayerU;
            uniform vec3 LayerV;
            uniform vec2 LayerTexel;
            uniform vec2 LayerX;
            uniform vec2 LayerY;
            uniform vec2 LayerOrigin;
            uniform float Time;

            in vec2 vUv;
            out vec4 fragColor;

            float luma(vec3 c)
            {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            vec3 rgb2hsv(vec3 c)
            {
                vec4 k = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
                vec4 p = mix(vec4(c.bg, k.wz), vec4(c.gb, k.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;

                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }

            vec3 hsv2rgb(vec3 c)
            {
                vec4 k = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + k.xyz) * 6.0 - k.www);

                return c.z * mix(k.xxx, clamp(p - k.xxx, 0.0, 1.0), c.y);
            }

            float hash(vec2 p)
            {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            vec2 hash2(vec2 p)
            {
                p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));

                return fract(sin(p) * 43758.5453);
            }

            float noise(vec2 p)
            {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);

                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
            }

            float fbm(vec2 p, int octaves)
            {
                float value = 0.0;
                float amplitude = 0.5;

                for (int i = 0; i < 6; i++)
                {
                    if (i >= octaves)
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

                return mat2(c, s, -s, c);
            }

            vec2 layerUv(vec2 uv)
            {
                return vec2(dot(LayerU.xy, uv) + LayerU.z, dot(LayerV.xy, uv) + LayerV.z);
            }

            /* Layer-local pixel -> composition UV. UVs cover the whole composition, so this is
             * the bridge a layer-space effect needs when it resamples its own content. */
            vec2 localToCompUv(vec2 luv)
            {
                vec2 lp = vec2(luv.x * LayerSize.x, luv.y * LayerSize.y);
                vec2 px = LayerX * lp.x + LayerY * lp.y + LayerOrigin;

                return vec2(px.x / Size.x, 1.0 - px.y / Size.y);
            }

            /* The layer raster is drawn with a y-down ortho projection, so its texture V axis
             * points the other way: local y=0 (the top of the layer) is v=1. Sampling it with the
             * layer UV directly ("LayerLocal" shortcut) read the raster mirrored — a 3D layer with
             * any content-sampling effect came out upside down (text layers made it obvious). The
             * composition mapping is the one place that owns the flip, and in the layer-local bake
             * the proxy composition *is* the raster, so it is exact there too. */
            vec4 sampleLayer(vec2 luv)
            {
                return texture(Source, clamp(localToCompUv(luv), vec2(0.0), vec2(1.0)));
            }

            """);

        for (AmEffectDef.Param param : definition.params)
        {
            code.append("uniform float u_").append(param.key).append(";\n");
        }

        for (AmEffectDef.ColorParam color : definition.colors)
        {
            code.append("uniform vec4 u_").append(color.key).append(";\n");
        }

        code.append('\n').append(definition.body);

        return code.toString();
    }

    /** Name/source pairs for the headless compile test. */
    public static List<String[]> sources()
    {
        List<String[]> list = new ArrayList<>();

        for (AmEffectDef definition : AmEffectLibrary.all())
        {
            list.add(new String[] {"am_" + definition.id, source(definition)});
        }

        return list;
    }

    public static void delete()
    {
        for (GlslProgram program : PROGRAMS.values())
        {
            program.delete();
        }

        PROGRAMS.clear();
        FAILED.clear();
    }

    private static void setColor(GlslProgram program, String uniform, int argb)
    {
        program.set(uniform,
            ((argb >> 16) & 0xFF) / 255F,
            ((argb >> 8) & 0xFF) / 255F,
            (argb & 0xFF) / 255F,
            ((argb >> 24) & 0xFF) / 255F);
    }
}
