package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;

/** Lazily compiled composition programs. Initialized on the render thread on first use. */
public final class CompShaders
{
    private static boolean initialized;
    public static GlslProgram composite;
    public static GlslProgram premultiply;
    public static GlslProgram unpremultiply;
    public static GlslProgram inner;
    public static GlslProgram gradient;
    public static GlslProgram blur;
    public static GlslProgram aeBlur;
    public static GlslProgram sharpen;
    public static GlslProgram radial;
    public static GlslProgram color;
    public static GlslProgram key;
    public static GlslProgram generate;
    public static GlslProgram distort;
    public static GlslProgram aeDistort;
    public static GlslProgram pixel;
    public static GlslProgram stroke;
    public static GlslProgram transition;
    public static GlslProgram depth;
    public static GlslProgram shatter;
    public static GlslProgram rgbSplit;
    public static GlslProgram grade;
    public static GlslProgram repeat;
    public static GlslProgram entityKey;

    private CompShaders()
    {}

    public static synchronized void init()
    {
        if (initialized)
        {
            return;
        }

        initialized = true;

        composite = make("composite", ShaderSources.COMPOSITE);
        premultiply = make("premultiply", ShaderSources.PREMULTIPLY);
        unpremultiply = make("unpremultiply", ShaderSources.UNPREMULTIPLY);
        inner = make("inner", ShaderSources.INNER);
        gradient = make("gradient", ShaderSources.GRADIENT);
        blur = make("blur", ShaderSources.BLUR);
        aeBlur = make("ae_blur", ShaderSources.AE_BLUR);
        sharpen = make("sharpen", ShaderSources.SHARPEN);
        radial = make("radial", ShaderSources.RADIAL);
        color = make("color", ShaderSources.COLOR);
        key = make("key", ShaderSources.KEY);
        generate = make("generate", ShaderSources.GENERATE);
        distort = make("distort", ShaderSources.DISTORT);
        aeDistort = make("ae_distort", ShaderSources.AE_DISTORT);
        pixel = make("pixel", ShaderSources.PIXEL);
        stroke = make("stroke", ShaderSources.STROKE);
        transition = make("transition", ShaderSources.TRANSITION);
        depth = make("depth", ShaderSources.DEPTH);
        shatter = make("shatter", ShaderSources.SHATTER);
        rgbSplit = make("rgb_split", ShaderSources.RGB_SPLIT);
        grade = make("grade", ShaderSources.GRADE);
        repeat = make("repeat", ShaderSources.REPEAT);
        entityKey = make("entity_key", ShaderSources.ENTITY_KEY);
    }

    private static GlslProgram make(String name, String fragment)
    {
        try
        {
            GlslProgram program = new GlslProgram(name, ShaderSources.VERTEX, fragment);

            if (!program.isValid())
            {
                BbsAeAddon.LOGGER.error("Composition shader {} is unavailable", name);
                return null;
            }

            return program;
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.error("Could not create composition shader {}", name, e);
            return null;
        }
    }

    public static void delete()
    {
        delete(composite);
        delete(premultiply);
        delete(unpremultiply);
        delete(inner);
        delete(gradient);
        delete(blur);
        delete(aeBlur);
        delete(sharpen);
        delete(radial);
        delete(color);
        delete(key);
        delete(generate);
        delete(distort);
        delete(aeDistort);
        delete(pixel);
        delete(stroke);
        delete(transition);
        delete(depth);
        delete(shatter);
        delete(rgbSplit);
        delete(grade);
        delete(repeat);
        delete(entityKey);

        composite = premultiply = unpremultiply = inner = gradient = blur = aeBlur = sharpen = radial = color = key = generate = distort = aeDistort = pixel = stroke = transition = depth = shatter = rgbSplit = grade = repeat = entityKey = null;
        initialized = false;
    }

    private static void delete(GlslProgram program)
    {
        if (program != null)
        {
            program.delete();
        }
    }
}
