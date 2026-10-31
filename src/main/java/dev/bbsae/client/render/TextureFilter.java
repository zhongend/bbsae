package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;

/**
 * Texture sampling policy for image and footage layers.
 *
 * <h2>Why this is not "always linear"</h2>
 * <p>Minecraft's item, block and GUI textures are pixel art. Sampling a 16×16 icon with linear
 * filtering turns crisp texels into grey mush, while sampling a 4K photo with nearest-neighbour
 * produces obvious stair-steps when it is scaled down. The correct filter is therefore a property
 * of the <em>source</em> and of the <em>display size</em>, not a global renderer preference.</p>
 *
 * <p>The auto policy is deliberately simple and explainable:</p>
 * <ul>
 *   <li>a texture whose longest side is 64 px or less is pixel art → nearest;</li>
 *   <li>a source at least 512 px on its longest side, or at least 1 MP, is high resolution → linear;</li>
 *   <li>a source downscaled by 2× or more benefits from linear minification → linear;</li>
 *   <li>everything else stays nearest, which keeps crisp edges at native size.</li>
 * </ul>
 *
 * <p>This is a render-time decision, so changing a layer's display size immediately changes the
 * filter without rebuilding the texture cache.</p>
 */
public final class TextureFilter
{
    public static final int AUTO = 0;
    public static final int LINEAR = 1;
    public static final int NEAREST = 2;

    public static final int SOURCE_INHERIT = -1;

    private TextureFilter()
    {}

    /**
     * Resolves the filter for one draw.
     *
     * @param mode     {@link #AUTO}, {@link #LINEAR} or {@link #NEAREST}
     * @param sourceW  source width in pixels
     * @param sourceH  source height in pixels
     * @param targetW  drawn width in composition pixels
     * @param targetH  drawn height in composition pixels
     */
    public static boolean isLinear(int mode, int sourceW, int sourceH, float targetW, float targetH)
    {
        if (mode == LINEAR)
        {
            return true;
        }

        if (mode == NEAREST)
        {
            return false;
        }

        return auto(sourceW, sourceH, targetW, targetH);
    }

    /** True when the auto policy would enable linear filtering for this source/draw pair. */
    public static boolean auto(int sourceW, int sourceH, float targetW, float targetH)
    {
        int sw = Math.max(1, sourceW);
        int sh = Math.max(1, sourceH);
        int longest = Math.max(sw, sh);

        if (longest <= 64)
        {
            return false;
        }

        long pixels = (long) sw * sh;

        if (longest >= 512 || pixels >= 512L * 512L)
        {
            return true;
        }

        float tw = Math.max(1F, targetW);
        float th = Math.max(1F, targetH);

        /* Downscaling by 2× or more is exactly where a nearest sample throws away detail. */
        return tw <= sw / 2F || th <= sh / 2F;
    }

    /** True when this source is a high-resolution asset by the auto policy. */
    public static boolean isHighResolution(int sourceW, int sourceH)
    {
        int longest = Math.max(Math.max(1, sourceW), Math.max(1, sourceH));

        return longest >= 512 || (long) Math.max(1, sourceW) * Math.max(1, sourceH) >= 512L * 512L;
    }

    public static int layerMode(CompLayer layer)
    {
        return layer == null ? SOURCE_INHERIT : Math.max(SOURCE_INHERIT, Math.min(NEAREST, layer.textureFilter));
    }

    public static int projectMode(CompProject project)
    {
        return project == null || project.settings == null
            ? AUTO
            : Math.max(AUTO, Math.min(NEAREST, project.settings.textureFilter));
    }

    /** Layer override when set, otherwise the project setting. */
    public static int resolveMode(CompLayer layer, CompProject project)
    {
        int layerMode = layerMode(layer);

        return layerMode == SOURCE_INHERIT ? projectMode(project) : layerMode;
    }

    public static String name(int mode)
    {
        return switch (mode)
        {
            case LINEAR -> "线性";
            case NEAREST -> "最近邻";
            default -> "自动";
        };
    }

    /** One-line explanation used by settings and tooltips. */
    public static String describe(int mode)
    {
        return switch (mode)
        {
            case LINEAR -> "强制线性过滤：适合高分辨率照片和视频，低分辨率像素素材会变糊";
            case NEAREST -> "强制最近邻：保留像素边缘，缩放照片时会出现锯齿";
            default -> "自动：高分辨率素材开线性，像素素材保持最近邻（按源尺寸与显示尺寸判断）";
        };
    }
}
