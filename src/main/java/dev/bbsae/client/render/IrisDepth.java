package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads Iris' own depth attachment without compiling against Iris.
 *
 * <p>Under a shader pack the world depth lives in Iris' render targets, not in Minecraft's main
 * framebuffer. The compositor's replay matte needs the world depth of the real film geometry; the
 * main depth attachment can be empty after Iris switches targets, which makes the matte silently
 * reject every actor. Reflection keeps Iris optional: when it is absent, or its internal layout
 * changes, this class returns 0 and the caller falls back to the main framebuffer.</p>
 */
public final class IrisDepth
{
    private static boolean resolved;
    private static Method getPipelineManager;
    private static Method getPipeline;
    private static Method getDepthTexture;
    private static Method getCurrentWidth;
    private static Method getCurrentHeight;
    private static Field renderTargets;
    private static boolean logged;
    private static boolean loggedFailure;

    private IrisDepth()
    {}

    /** The Iris depth texture id, or 0 when Iris/the pipeline is unavailable. */
    public static int texture()
    {
        if (!ShaderCompat.irisLoaded())
        {
            return 0;
        }

        try
        {
            resolve();

            if (getDepthTexture == null || getPipelineManager == null || getPipeline == null)
            {
                return 0;
            }

            Object manager = getPipelineManager.invoke(null);
            Object pipeline = manager == null ? null : getPipeline.invoke(manager);

            if (pipeline == null || !"net.irisshaders.iris.pipeline.IrisRenderingPipeline".equals(pipeline.getClass().getName()))
            {
                if (!loggedFailure)
                {
                    loggedFailure = true;
                    BbsAeAddon.LOGGER.info("Iris depth unavailable: pipeline={}", pipeline == null ? "null" : pipeline.getClass().getName());
                }

                return 0;
            }

            Object targets = renderTargets.get(pipeline);

            if (targets == null)
            {
                return 0;
            }

            int texture = (Integer) getDepthTexture.invoke(targets);

            if (texture == 0 && !loggedFailure)
            {
                loggedFailure = true;
                BbsAeAddon.LOGGER.info("Iris depth texture is zero at the capture point");
            }

            if (texture != 0 && !logged)
            {
                logged = true;
                BbsAeAddon.LOGGER.info("Iris depth attachment available: texture={} {}x{}",
                    texture, width(targets), height(targets));
            }

            return texture;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                BbsAeAddon.LOGGER.warn("Iris depth reflection failed; replay matte will use the main depth attachment", e);
            }

            return 0;
        }
    }

    public static int width()
    {
        try
        {
            resolve();

            Object manager = getPipelineManager == null ? null : getPipelineManager.invoke(null);
            Object pipeline = manager == null || getPipeline == null ? null : getPipeline.invoke(manager);
            Object targets = pipeline == null || renderTargets == null ? null : renderTargets.get(pipeline);

            return targets == null ? 0 : width(targets);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                BbsAeAddon.LOGGER.warn("Iris depth reflection failed; replay matte will use the main depth attachment", e);
            }

            return 0;
        }
    }

    public static int height()
    {
        try
        {
            resolve();

            Object manager = getPipelineManager == null ? null : getPipelineManager.invoke(null);
            Object pipeline = manager == null || getPipeline == null ? null : getPipeline.invoke(manager);
            Object targets = pipeline == null || renderTargets == null ? null : renderTargets.get(pipeline);

            return targets == null ? 0 : height(targets);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                BbsAeAddon.LOGGER.warn("Iris depth reflection failed; replay matte will use the main depth attachment", e);
            }

            return 0;
        }
    }

    private static int width(Object targets)
    {
        try
        {
            return getCurrentWidth == null ? 0 : (Integer) getCurrentWidth.invoke(targets);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return 0;
        }
    }

    private static int height(Object targets)
    {
        try
        {
            return getCurrentHeight == null ? 0 : (Integer) getCurrentHeight.invoke(targets);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return 0;
        }
    }

    private static void resolve() throws ReflectiveOperationException
    {
        if (resolved)
        {
            return;
        }

        resolved = true;
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        getPipelineManager = iris.getMethod("getPipelineManager");

        Class<?> manager = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
        getPipeline = manager.getMethod("getPipelineNullable");

        Class<?> pipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
        renderTargets = pipeline.getDeclaredField("renderTargets");
        renderTargets.setAccessible(true);

        Class<?> targets = Class.forName("net.irisshaders.iris.targets.RenderTargets");
        getDepthTexture = targets.getMethod("getDepthTexture");
        getCurrentWidth = targets.getMethod("getCurrentWidth");
        getCurrentHeight = targets.getMethod("getCurrentHeight");
    }
}
