package dev.bbsae.client.render;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Copy transforms for the Alight Motion replicators, in layer-local pixels.
 *
 * <p>The composition path uses these to draw real 3D quads instead of resampling the already
 * projected image. The viewport uses the same math, so a repeat looks identical in the workspace
 * and in the final frame.</p>
 */
final class Replicate3D
{
    private Replicate3D() {}

    /**
     * The first replicator in the projected part of the stack, or null.
     *
     * <p>The old implementation required the replication to be the <b>first</b> projected effect.
     * A stack such as {@code shake → repeat} therefore fell back to the composition-space
     * resampler and the copies became flat screen-space cards again. A transform-only or depth
     * effect before the replicator can still run after the 3D copies (the replication is consumed
     * here); the copy geometry itself must be decided before that pass.</p>
     */
    static CompEffect leading(CompLayer layer, CompRenderContext context)
    {
        if (layer == null || layer.effects == null)
        {
            return null;
        }

        int first = CompRenderer.firstProjectedEffect(layer.effects);

        if (first < 0 || first >= layer.effects.size())
        {
            return null;
        }

        for (int i = first; i < layer.effects.size(); i++)
        {
            CompEffect effect = layer.effects.get(i);

            if (effect != null && effect.enabled && EffectRenderer.isReplication(effect.type))
            {
                return layer.resolveEffect(effect, context.frame);
            }
        }

        return null;
    }

    /**
     * Any enabled replicator in the layer's stack. Independent replay models are not routed through
     * {@code CompRenderer.renderBaked3D}, so their repeat has to be found without the projected-effect
     * split used by flat 3D layers.
     */
    static CompEffect find(CompLayer layer, CompRenderContext context)
    {
        if (layer == null || layer.effects == null)
        {
            return null;
        }

        for (CompEffect effect : layer.effects)
        {
            if (effect != null && effect.enabled && EffectRenderer.isReplication(effect.type))
            {
                return layer.resolveEffect(effect, context.frame);
            }
        }

        return null;
    }

    /**
     * Local transforms for every copy. The caller multiplies them by the layer world matrix.
     * Empty means "draw the single original".
     */
    static List<Matrix4f> transforms(CompEffect resolved, CompLayer layer, CompRenderContext context,
                                     CompLayerSourceCache.Entry source)
    {
        if (source == null)
        {
            return Collections.emptyList();
        }

        return transforms(resolved, layer, context,
            source.localX + source.localWidth * 0.5F,
            source.localY + source.localHeight * 0.5F);
    }

    /**
     * Same copy math with an explicit layer-local centre. Used by independent replay models: their
     * pivot is the layer origin, not a cached 2D raster rectangle.
     */
    static List<Matrix4f> transforms(CompEffect resolved, CompLayer layer, CompRenderContext context,
                                     float cx, float cy)
    {
        if (resolved == null || layer == null || context == null)
        {
            return Collections.emptyList();
        }

        String type = resolved.type;
        int count = Math.max(1, Math.min(48, Math.round(resolved.valueF("count", 5F))));
        float alpha = Math.max(0F, Math.min(1F, resolved.valueF("alpha", resolved.valueF("amount", 1F))));

        if (alpha <= 0.001F)
        {
            return Collections.emptyList();
        }

        List<Matrix4f> out = new ArrayList<>();

        if ("am_repeat".equals(type))
        {
            float ox = resolved.valueF("offset_x", 48F);
            float oy = resolved.valueF("offset_y", 0F);
            float angle = (float) Math.toRadians(resolved.valueF("angle", 0F));
            float scale = Math.max(0.01F, resolved.valueF("scale", 1F));

            for (int i = 0; i < count; i++)
            {
                float k = i - (count - 1) * 0.5F;

                out.add(copy(cx + ox * k, cy + oy * k, angle * i,
                    (float) Math.pow(scale, i), cx, cy));
            }
        }
        else if ("am_repeat_radial".equals(type))
        {
            float radius = Math.max(0F, resolved.valueF("radius", 300F));
            float start = (float) Math.toRadians(resolved.valueF("orientation", 0F)
                + resolved.valueF("start_angle", 0F));
            float sweep = (float) Math.toRadians(resolved.valueF("sweep", 360F));
            float angleStep = (float) Math.toRadians(resolved.valueF("angle", 0F));
            float base = Math.max(0.01F, resolved.valueF("base_scale", 1F));
            float scale = Math.max(0.01F, resolved.valueF("scale", 1F));
            float ox = resolved.valueF("offset_x", 0F);
            float oy = resolved.valueF("offset_y", 0F);

            for (int i = 0; i < count; i++)
            {
                float t = count <= 1 ? 0F : i / (float) (count - 1);
                float angle = start + sweep * t;

                out.add(copy(cx + (float) Math.cos(angle) * radius + ox,
                    cy + (float) Math.sin(angle) * radius + oy, angleStep * i,
                    base * (float) Math.pow(scale, i), cx, cy));
            }
        }
        else if ("am_repeat_scatter".equals(type))
        {
            float radius = Math.max(0F, resolved.valueF("radius", 300F));
            float seed = resolved.valueF("scatter_seed", 0F) + resolved.valueF("evolution", 0F) * 0.37F;
            float angleStep = (float) Math.toRadians(resolved.valueF("angle", 0F));
            float scale = Math.max(0.01F, resolved.valueF("scale", 1F));
            float ox = resolved.valueF("offset_x", 0F);
            float oy = resolved.valueF("offset_y", 0F);

            for (int i = 0; i < count; i++)
            {
                float a = hash01(seed, i * 2 + 1) * 6.2831853F;
                float r = (float) Math.sqrt(hash01(seed, i * 2 + 2)) * radius;

                out.add(copy(cx + (float) Math.cos(a) * r + ox,
                    cy + (float) Math.sin(a) * r + oy, angleStep * i,
                    (float) Math.pow(scale, i), cx, cy));
            }
        }
        else if ("am_grid_repeat".equals(type))
        {
            int columns = Math.max(1, Math.min(8, Math.round(resolved.valueF("columns", 2F))));
            int rows = Math.max(1, Math.min(8, Math.round(resolved.valueF("rows", 2F))));
            float gap = Math.max(0F, Math.min(0.9F, resolved.valueF("gap", 0.05F)));
            float cellW = context.width / (float) columns;
            float cellH = context.height / (float) rows;

            for (int j = 0; j < rows; j++)
            {
                for (int i = 0; i < columns; i++)
                {
                    float px = (i + 0.5F) * cellW - layer.x;
                    float py = (j + 0.5F) * cellH - layer.y;

                    out.add(copy(px - cx, py - cy, 0F, Math.max(0.001F, 1F - gap), cx, cy));
                }
            }
        }
        else if ("am_line_repeat".equals(type))
        {
            int vertical = Math.round(resolved.valueF("vertical", 1F));
            float duty = Math.max(0.01F, Math.min(1F, resolved.valueF("duty", 0.7F)));

            for (int i = 0; i < count; i++)
            {
                float t = i - (count - 1) * 0.5F;
                float dx = vertical == 0 ? t * context.width / count : 0F;
                float dy = vertical != 0 ? t * context.height / count : 0F;

                out.add(copy(cx + dx, cy + dy, 0F, duty, cx, cy));
            }
        }

        return out;
    }

    private static Matrix4f copy(float x, float y, float rotation, float scale, float cx, float cy)
    {
        return new Matrix4f()
            .translate(cx + x, cy + y, 0F)
            .rotateZ(rotation)
            .scale(scale, scale, 1F)
            .translate(-cx, -cy, 0F);
    }

    private static float hash01(float seed, int i)
    {
        int x = Float.floatToIntBits(seed) ^ (i * 0x9E3779B9);
        x ^= x >>> 16;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        x *= 0xC2B2AE35;
        x ^= x >>> 16;

        return (x & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }
}
