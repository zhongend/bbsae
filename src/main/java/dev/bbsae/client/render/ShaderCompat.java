package dev.bbsae.client.render;

import mchorse.bbs_mod.client.BBSRendering;

/**
 * Runtime feature detection for shader packs / Sodium.
 *
 * <p>The compositor must not assume the vanilla world framebuffer layout. BBS already carries the
 * compatibility flags for Iris, OptiFine and Sodium, so we read those instead of reflecting into
 * the mods ourselves. The flags are only used for <b>diagnostics and safe fallbacks</b>: the
 * renderer keeps the same render graph, and no shader pack is required.</p>
 */
public final class ShaderCompat
{
    private ShaderCompat()
    {}

    public static boolean irisLoaded()
    {
        try
        {
            return BBSRendering.isIrisShadersEnabled();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    public static boolean irisWorldShaders()
    {
        try
        {
            return BBSRendering.isIrisWorldShadersEnabled();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    public static boolean shaderPassRunning()
    {
        try
        {
            return BBSRendering.isIrisShadowPass();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * A short label for the preview readout. "光影" is intentionally generic: Iris and OptiFine
     * both count, and the user does not need to know which hook supplied the frame.
     */
    public static String label()
    {
        if (irisWorldShaders())
        {
            return "光影";
        }

        if (irisLoaded())
        {
            return "光影已加载";
        }

        return "原版";
    }

    /** Human-readable state for the settings page. */
    public static String describe()
    {
        if (!irisLoaded())
        {
            return "原版渲染（未检测到光影包）";
        }

        if (irisWorldShaders())
        {
            return "光影渲染中：合成层通过 BBS FrameOverlays 进入世界帧；颜色抠像已禁用，实体抠像使用深度";
        }

        return "光影已加载（当前关闭或不在世界通道）";
    }

    /**
     * True when a world frame can be sampled for the current pass. Shadow passes must never be
     * captured: they contain a depth-only world and would turn the preview black.
     */
    public static boolean canCaptureWorld()
    {
        return !shaderPassRunning();
    }
}
