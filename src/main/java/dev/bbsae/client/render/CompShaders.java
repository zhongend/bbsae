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
    public static GlslProgram opaque;
    public static GlslProgram mesh;
    public static GlslProgram viewport3d;
    public static GlslProgram depthPlane;

    private CompShaders()
    {}

    /**
     * Test hook: drops the cached program objects so the next {@link #init()} compiles them in the
     * current GL context. Test classes each create their own hidden context; reusing ids from a
     * destroyed one produces GL_INVALID_VALUE in whichever class runs next.
     */
    public static synchronized void reload()
    {
        initialized = false;
        composite = premultiply = unpremultiply = inner = gradient = blur = aeBlur = sharpen = radial
            = color = key = generate = distort = aeDistort = pixel = stroke = transition = depth = shatter
            = rgbSplit = grade = repeat = entityKey = opaque = mesh = viewport3d = depthPlane = null;
    }

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
        opaque = make("opaque", ShaderSources.OPAQUE);
        mesh = makeMesh();
        viewport3d = make("viewport3d", ShaderSources.VIEWPORT_3D_VERTEX, ShaderSources.VIEWPORT_3D_FRAGMENT);
        depthPlane = make("depth_plane", ShaderSources.DEPTH_PLANE);
    }

    private static GlslProgram makeMesh()
    {
        try
        {
            GlslProgram program = new GlslProgram("mesh", ShaderSources.MESH_VERTEX, ShaderSources.MESH_FRAGMENT);

            if (!program.isValid())
            {
                BbsAeAddon.LOGGER.error("Composition mesh shader is unavailable");

                return null;
            }

            return program;
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.error("Could not create the composition mesh shader", e);

            return null;
        }
    }

    private static GlslProgram make(String name, String vertex, String fragment)
    {
        try
        {
            GlslProgram program = new GlslProgram(name, vertex, fragment);

            if (!program.isValid())
            {
                BbsAeAddon.LOGGER.error("Composition shader {} is unavailable", name);

                return null;
            }

            return program;
        }
        catch (RuntimeException e)
        {
            BbsAeAddon.LOGGER.error("Could not create the composition shader " + name, e);

            return null;
        }
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
        delete(opaque);
        delete(mesh);
        delete(viewport3d);
        delete(depthPlane);

        composite = premultiply = unpremultiply = inner = gradient = blur = aeBlur = sharpen = radial = color = key = generate = distort = aeDistort = pixel = stroke = transition = depth = shatter = rgbSplit = grade = repeat = entityKey = opaque = mesh = viewport3d = depthPlane = null;
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
