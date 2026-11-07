package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads Iris' own colour target (colortex0) without compiling against Iris.
 *
 * <p>This is the fallback path for the world capture. The primary capture hooks the shader pack's
 * final pass through {@code IrisFinalizeMixin}; if that mixin cannot bind (Iris internal renamed on
 * some version) the compositor would otherwise keep serving the early copy, which under Iris is the
 * pre-composite main framebuffer — solid black, i.e. exactly the "itrp 下画面直接黑屏" report.
 * Copying colortex0 at the {@code WorldRenderEvents.LAST} point is not tonemapped, but it is the
 * real world picture, which beats a black frame by a wide margin.</p>
 *
 * <p>Reflection keeps Iris optional: when it is absent, or its internal layout changed, this class
 * returns texture id 0 and the caller simply keeps whatever it already has.</p>
 */
public final class IrisColor
{
    private static boolean resolved;
    private static Method getPipelineManager;
    private static Method getPipeline;
    private static Field renderTargets;
    private static Method getColorTexture;
    private static Method getCurrentWidth;
    private static Method getCurrentHeight;
    private static boolean loggedFailure;

    private IrisColor()
    {}

    /** {@code {textureId, width, height}} of colortex0, or {@code {0, 0, 0}} when unavailable. */
    public static int[] mainColor()
    {
        if (!ShaderCompat.irisLoaded())
        {
            return new int[] {0, 0, 0};
        }

        try
        {
            resolve();

            if (getPipelineManager == null || getPipeline == null || getColorTexture == null)
            {
                return new int[] {0, 0, 0};
            }

            Object manager = getPipelineManager.invoke(null);
            Object pipeline = manager == null ? null : getPipeline.invoke(manager);

            if (pipeline == null || renderTargets == null)
            {
                return new int[] {0, 0, 0};
            }

            Object targets = renderTargets.get(pipeline);

            if (targets == null)
            {
                return new int[] {0, 0, 0};
            }

            int texture = (Integer) getColorTexture.invoke(targets, 0);

            if (texture == 0)
            {
                return new int[] {0, 0, 0};
            }

            return new int[]
            {
                texture,
                getCurrentWidth == null ? 0 : (Integer) getCurrentWidth.invoke(targets),
                getCurrentHeight == null ? 0 : (Integer) getCurrentHeight.invoke(targets)
            };
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                BbsAeAddon.LOGGER.info("Iris colour target unavailable for the fallback capture: {}", e.toString());
            }

            return new int[] {0, 0, 0};
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
        getColorTexture = targets.getMethod("getColorTexture", int.class);
        getCurrentWidth = targets.getMethod("getCurrentWidth");
        getCurrentHeight = targets.getMethod("getCurrentHeight");
    }
}
