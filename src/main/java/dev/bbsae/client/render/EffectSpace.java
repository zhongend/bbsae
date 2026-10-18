package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * The coordinate system an effect runs in.
 *
 * <p>Layer content is rendered into a composition-sized FBO, so a shader's default UV covers the
 * whole comp. Generative effects (gradient, fractal noise, checkerboard, grid, circle) and
 * positional effects (vignette, bulge, wipes, …) must instead run in <b>layer/effect space</b>:
 * the rectangle the layer actually occupies. This class maps the comp UV used by the shader back
 * to that local 0..1 space and provides the inverse mapping for effects that resample the source.
 *
 * <p><b>The V axis is flipped.</b> Layer content is drawn with an ortho projection whose y axis
 * points down ({@code ortho(0, w, h, 0)}), so composition y=0 lands at texture v=1 and composition
 * y=height lands at v=0. Every fullscreen shader therefore samples the comp with
 * {@code compY = (1 - vUv.y) * height}. Forgetting that flip anchors gradients to the comp instead
 * of the layer and makes vertical motion move the effect the wrong way — the layer starts to look
 * like a window over a static gradient.
 *
 * <p>It mirrors Effectcraft's "effect space" idea: bounds are the source rectangle for footage /
 * solids / precomps, the raster bounds for shapes, and an anchor-centred box for text. Adjustment
 * layers intentionally use the whole comp because their effects see the accumulated comp below
 * them.</p>
 */
final class EffectSpace
{
    final float width;
    final float height;
    final float originX;
    final float originY;

    /** comp UV -> local UV: u = LayerU.xy · compUv + LayerU.z. */
    final float ux;
    final float uy;
    final float uz;
    final float vx;
    final float vy;
    final float vz;

    /** local pixel delta -> comp pixel: comp = LayerX * lx + LayerY * ly + LayerOrigin. */
    final float m00;
    final float m01;
    final float m10;
    final float m11;
    final float originCompX;
    final float originCompY;

    final int compWidth;
    final int compHeight;

    private EffectSpace(float width, float height, float originX, float originY,
                        float ux, float uy, float uz, float vx, float vy, float vz,
                        float m00, float m01, float m10, float m11, float originCompX, float originCompY,
                        int compWidth, int compHeight)
    {
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.ux = ux;
        this.uy = uy;
        this.uz = uz;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.m00 = m00;
        this.m01 = m01;
        this.m10 = m10;
        this.m11 = m11;
        this.originCompX = originCompX;
        this.originCompY = originCompY;
        this.compWidth = compWidth;
        this.compHeight = compHeight;
    }

    static EffectSpace of(CompLayer layer, Matrix4f world, CompRenderContext context)
    {
        if (layer == null || layer.threeD || "adjustment".equals(layer.type) || "camera".equals(layer.type))
        {
            /* A 3D layer is projected by the camera; its screen footprint is not the local
             * rectangle anymore. Running effects in composition space is the honest choice:
             * gradients/vignettes stay screen-aligned instead of being sheared by the inverse
             * of a perspective matrix that EffectSpace cannot represent. */
            return identity(context);
        }

        float bx;
        float by;
        float bw;
        float bh;

        if ("shape".equals(layer.type))
        {
            ShapeCache.Raster raster = ShapeCache.get(layer, context.frame);

            if (raster != null)
            {
                bx = raster.originX;
                by = raster.originY;
                bw = raster.width;
                bh = raster.height;
            }
            else
            {
                bw = Math.max(1F, Math.abs(layer.width));
                bh = Math.max(1F, Math.abs(layer.height));
                bx = -bw * layer.anchorX;
                by = -bh * layer.anchorY;
            }
        }
        else
        {
            bw = Math.max(1F, Math.abs(layer.width));
            bh = Math.max(1F, Math.abs(layer.height));
            bx = -bw * layer.anchorX;
            by = -bh * layer.anchorY;
        }

        return ofBounds(layer, world, context, bx, by, bw, bh);
    }

    /** Explicit bounds variant; also used by the coordinate regression test. */
    static EffectSpace ofBounds(CompLayer layer, Matrix4f world, CompRenderContext context,
                                float bx, float by, float bw, float bh)
    {
        int compW = Math.max(1, context.width);
        int compH = Math.max(1, context.height);
        bw = Math.max(0.0001F, bw);
        bh = Math.max(0.0001F, bh);

        Matrix4f matrix = world == null ? new Matrix4f() : world;
        Matrix4f inv = new Matrix4f(matrix).invert();

        /* Shader UV -> comp pixels. v=0 is the bottom row of the FBO, while composition y grows
         * downwards, hence compY = (1 - v) * compH. */
        /* compX = u * compW, compY = compH - v * compH. */
        float ux = inv.m00() * compW / bw;
        float uy = -inv.m10() * compH / bw;
        float uz = (inv.m10() * compH + inv.m30() - bx) / bw;

        float vx = inv.m01() * compW / bh;
        float vy = -inv.m11() * compH / bh;
        float vz = (inv.m11() * compH + inv.m31() - by) / bh;

        /* localPx -> compPx: p = column0 * lx + column1 * ly + translation */
        float originCompX = matrix.m00() * bx + matrix.m10() * by + matrix.m30();
        float originCompY = matrix.m01() * bx + matrix.m11() * by + matrix.m31();

        return new EffectSpace(bw, bh, bx, by, ux, uy, uz, vx, vy, vz,
            matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), originCompX, originCompY, compW, compH);
    }

    static EffectSpace identity(CompRenderContext context)
    {
        int compW = Math.max(1, context.width);
        int compH = Math.max(1, context.height);

        /* local v grows downwards like composition y, while the FBO v grows upwards: the same
         * `v = 1 - localV` rule as ofBounds(). Returning (0, 1, 0) here made every layerUv()
         * consumer on an adjustment/3D layer sample the vertically flipped row. */
        return new EffectSpace(compW, compH, 0F, 0F,
            1F, 0F, 0F, 0F, -1F, 1F,
            1F, 0F, 0F, 1F, 0F, 0F, compW, compH);
    }

    /** Shader UV (comp, v up) -> local layer pixel. Used by tests and one-off maths. */
    Vector2f localPixel(float u, float v)
    {
        float lx = ux * u + uy * v + uz;
        float ly = vx * u + vy * v + vz;

        return new Vector2f(lx * width, ly * height);
    }

    /** Convert a local effect UV (0..1 inside the layer bounds) to the comp UV used by textures. */
    Vector2f localToCompUv(float u, float v)
    {
        float lx = u * width;
        float ly = v * height;
        float px = m00 * lx + m10 * ly + originCompX;
        float py = m01 * lx + m11 * ly + originCompY;

        return new Vector2f(px / compWidth, 1F - py / compHeight);
    }

    void bind(GlslProgram program)
    {
        program.set("LayerU", ux, uy, uz);
        program.set("LayerV", vx, vy, vz);
        program.set("LayerSize", width, height);
        program.set("LayerX", m00, m01);
        program.set("LayerY", m10, m11);
        program.set("LayerOrigin", originCompX, originCompY);
    }
}
