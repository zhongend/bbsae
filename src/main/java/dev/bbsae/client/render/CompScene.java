package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompExpression;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * The composition scene graph.
 *
 * <p>One space, one matrix per layer, one camera. 2D and 3D layers differ <b>only</b> in whether
 * the perspective projection is applied to the layer's world matrix; the transform composition,
 * the parent chain and the drag compensation are identical. That is what makes 3D parenting
 * behave exactly like 2D parenting (and why the old, separate {@code localWorldMatrix3D} with its
 * Y-flip could never agree with the 2D path).</p>
 *
 * <p>World space is composition pixels: <b>X right, Y down, Z towards the viewer</b>. Content is
 * drawn in the same orientation, so no layer needs a Y-flip any more.</p>
 *
 * <p>World matrices are cached per {@link CompRenderContext}, i.e. per composition per frame:
 * the render loop, the depth sort, the gizmos and the hit test all read the same matrix instead
 * of rebuilding it three or four times.</p>
 */
public final class CompScene
{
    private CompScene()
    {}

    /** The camera of this frame (cached on the context). */
    public static CompCamera camera(CompRenderContext ctx)
    {
        if (ctx.cameraView == null)
        {
            ctx.cameraView = CompCamera.of(ctx.comp, ctx.time, ctx.frame);
        }

        return ctx.cameraView;
    }

    /** Local matrix: translate · rotateZ · rotateX · rotateY · scale, in comp space. */
    public static Matrix4f local(CompRenderContext ctx, CompLayer layer)
    {
        Matrix4f cached = ctx.localCache.get(layer);

        if (cached != null)
        {
            return cached;
        }

        Transform transform = layer.transformAt(ctx.frame);
        float x = transform.translate.x;
        float y = transform.translate.y;
        float rotation = transform.rotate.z;

        if (layer.expressionX != null && !layer.expressionX.isBlank())
        {
            x = CompExpression.eval(layer.expressionX, expressionContext(layer, ctx, x), x);
        }
        if (layer.expressionY != null && !layer.expressionY.isBlank())
        {
            y = CompExpression.eval(layer.expressionY, expressionContext(layer, ctx, y), y);
        }
        if (layer.expressionRotation != null && !layer.expressionRotation.isBlank())
        {
            rotation = CompExpression.eval(layer.expressionRotation, expressionContext(layer, ctx, rotation), rotation);
        }

        if (layer.motion)
        {
            x += (float) Math.sin(ctx.time * 1.8F) * layer.motionAmount;
        }

        Shake shake = shakeOf(layer, ctx);

        if (shake != null)
        {
            x += shake.x;
            y += shake.y;
            rotation += shake.rotation;
        }

        Vector2f bound = BindingResolver.resolve(layer, ctx);

        if (bound != null)
        {
            x = bound.x;
            y = bound.y;
        }

        float sx = Math.max(0.0001F, transform.scale.x * (1F + layer.squash));
        float sy = Math.max(0.0001F, transform.scale.y * (1F - layer.squash));
        float z = 0F;
        float rx = 0F;
        float ry = 0F;
        float sz = 1F;

        if (layer.threeD)
        {
            z = layer.scalarAt("z", layer.z, ctx.frame);
            rx = layer.scalarAt("rotationX", layer.rotationX, ctx.frame);
            ry = layer.scalarAt("rotationY", layer.rotationY, ctx.frame);
            sz = Math.max(0.0001F, layer.scalarAt("scaleZ", layer.scaleZ, ctx.frame));
        }

        Matrix4f matrix = new Matrix4f()
            .translate(x, y, z)
            .rotateZ((float) Math.toRadians(rotation))
            .rotateX((float) Math.toRadians(rx))
            .rotateY((float) Math.toRadians(ry))
            .scale(sx, sy, sz);

        ctx.localCache.put(layer, matrix);

        return matrix;
    }

    /** World matrix of a layer, including its whole parent chain. */
    public static Matrix4f world(CompRenderContext ctx, CompLayer layer)
    {
        if (layer == null)
        {
            return new Matrix4f();
        }

        Matrix4f cached = ctx.worldCache.get(layer);

        if (cached != null)
        {
            return cached;
        }

        Matrix4f world = world(ctx, layer, new HashSet<>());

        ctx.worldCache.put(layer, world);

        return world;
    }

    private static Matrix4f world(CompRenderContext ctx, CompLayer layer, Set<String> seen)
    {
        if (layer == null || !seen.add(layer.id))
        {
            return new Matrix4f();
        }

        Matrix4f local = new Matrix4f(local(ctx, layer));
        CompLayer parent = parentOf(ctx, layer);

        if (parent == null)
        {
            return local;
        }

        return world(ctx, parent, seen).mul(local);
    }

    public static CompLayer parentOf(CompRenderContext ctx, CompLayer layer)
    {
        if (layer.parentId == null || layer.parentId.isEmpty())
        {
            return null;
        }

        CompLayer parent = ctx.comp.getLayer(layer.parentId);

        return parent == layer ? null : parent;
    }

    /**
     * Model matrix handed to the content renderer: world space for 3D layers, the 2D camera view
     * for everything else. The caller pairs it with {@link #projection(CompRenderContext, CompLayer)}.
     */
    public static Matrix4f model(CompRenderContext ctx, CompLayer layer)
    {
        Matrix4f cached = ctx.modelCache.get(layer);

        if (cached != null)
        {
            return cached;
        }

        Matrix4f world = world(ctx, layer);
        CompCamera camera = camera(ctx);
        Matrix4f model;

        if (usePerspective(ctx, layer))
        {
            model = new Matrix4f(camera.view).mul(world);
        }
        else if (spatial(ctx))
        {
            /* A flat layer under a tilted camera is still drawn flat — into comp space, with no
             * camera at all. The camera is applied afterwards, once, by projecting the finished
             * comp-space image onto the layer's z plane. Keeping the camera out of the model is
             * what lets masks and effects stay in comp space and still come out right. */
            model = new Matrix4f(world);
        }
        else
        {
            model = new Matrix4f(camera.view2D).mul(world);
        }

        ctx.modelCache.put(layer, model);

        return model;
    }

    /**
     * True when flat layers have to be projected in 3D instead of through the affine 2D view.
     *
     * <p>Two situations need it: the camera looks at the comp at an angle (its affine
     * approximation is then simply wrong), or some layer sits off the comp plane (a z offset has
     * no meaning in an affine view). Both are properties of the frame, so the answer is cached on
     * the context.</p>
     */
    public static boolean spatial(CompRenderContext ctx)
    {
        if (ctx.spatial != null)
        {
            return ctx.spatial;
        }

        CompCamera camera = camera(ctx);
        boolean spatial = camera.perspective && (camera.tilted || hasDepth(ctx.comp));

        ctx.spatial = spatial;

        return spatial;
    }

    private static boolean hasDepth(CompDocument comp)
    {
        if (comp == null || comp.layers == null)
        {
            return false;
        }

        for (CompLayer layer : comp.layers)
        {
            if (layer != null && layer.visible && Math.abs(layer.z) > 0.01F)
            {
                return true;
            }
        }

        return false;
    }

    /** Orthographic (comp pixels) for 2D/flat layers, the camera projection for 3D layers. */
    public static Matrix4f projection(CompRenderContext ctx, CompLayer layer)
    {
        return usePerspective(ctx, layer)
            ? camera(ctx).projection
            : GlStateGuard.ortho(ctx.width, ctx.height);
    }

    public static boolean usePerspective(CompRenderContext ctx, CompLayer layer)
    {
        return layer.threeD
            && !"camera".equals(layer.type)
            && !"adjustment".equals(layer.type)
            && camera(ctx).perspective;
    }

    /**
     * Camera-space Z of the <b>plane</b> the layer lies on, not of its origin.
     *
     * <p>Why: two coplanar layers (the common case — everything at z = 0) must tie, so their stack
     * order decides. Depth of the origin does not tie: with a tilted camera the bottom layer's
     * origin is a few hundred pixels nearer than the top one's, which made a large background
     * plane sort in front of the small layer drawn above it. The plane distance is the same for
     * every point of the plane, so coplanar layers tie by construction, and a layer at z = 180
     * still compares correctly from any orbit angle.</p>
     *
     * <p>Derivation: the plane through {@code origin} with normal {@code n}; the view ray through
     * the eye travels {@code t = dot(origin - eye, f) / dot(n, f)} before it hits it. For a camera
     * looking down -Z this is exactly the camera-space z (negative in front). Degenerate edge-on
     * planes fall back to the origin depth.</p>
     */
    public static float planeDepth(CompRenderContext ctx, CompLayer layer)
    {
        Matrix4f world = world(ctx, layer);
        Vector3f origin = new Vector3f();
        Vector3f normal = new Vector3f(0F, 0F, 1F);

        world.transformPosition(origin);
        world.transformDirection(normal);

        if (normal.lengthSquared() < 0.000001F)
        {
            return ctx.cameraView == null ? 0F : camera(ctx).depthOf(origin);
        }

        normal.normalize();

        CompCamera camera = camera(ctx);
        float denominator = normal.dot(camera.forward);

        if (Math.abs(denominator) < 0.02F)
        {
            return camera.depthOf(origin);
        }

        float alongNormal = (origin.x - camera.position.x) * normal.x
            + (origin.y - camera.position.y) * normal.y
            + (origin.z - camera.position.z) * normal.z;

        /* t along the view ray to the plane, negated: camera-space z is -t (negative in front). */
        return -alongNormal / denominator;
    }

    /** Camera-space depth of the layer origin; larger = closer. Cached per frame. */
    public static float depth(CompRenderContext ctx, CompLayer layer)
    {
        Float cached = ctx.depthCache.get(layer);

        if (cached != null)
        {
            return cached;
        }

        Vector3f origin = new Vector3f();
        world(ctx, layer).transformPosition(origin);

        float depth = camera(ctx).depthOf(origin);

        ctx.depthCache.put(layer, depth);

        return depth;
    }

    /**
     * Local layer point → composition pixels. Used by the selection box, the camera gizmo and the
     * hit test, so gizmos can never disagree with what is rendered.
     */
    public static Vector2f project(CompRenderContext ctx, CompLayer layer, float x, float y, float z)
    {
        Vector3f point = new Vector3f(x, y, z);

        world(ctx, layer).transformPosition(point);

        CompCamera camera = camera(ctx);

        if (usePerspective(ctx, layer))
        {
            return camera.project(point, ctx.width, ctx.height);
        }

        /* A 2D layer is screen-attached: it goes through the 2D view, exactly like its content
         * does. Projecting it with the perspective camera is what made selection boxes drift away
         * from 2D layers whenever the camera was tilted. */
        camera.view2D.transformPosition(point);

        return new Vector2f(point.x, point.y);
    }

    /** World point → composition pixels. */
    public static Vector2f projectWorld(CompRenderContext ctx, Vector3f world)
    {
        return camera(ctx).project(world, ctx.width, ctx.height);
    }

    private static final class Shake
    {
        float x;
        float y;
        float rotation;
    }

    private static Shake shakeOf(CompLayer layer, CompRenderContext ctx)
    {
        if (layer.effects == null)
        {
            return null;
        }

        for (dev.bbsae.comp.CompEffect effect : layer.effects)
        {
            if (effect != null && effect.enabled && "shake".equals(effect.type))
            {
                float amount = Math.max(0F, layer.effectParam(effect.id, "amplitude", 18F, ctx.frame));
                float frequency = Math.max(0.01F, layer.effectParam(effect.id, "frequency", 6F, ctx.frame));
                float rotation = Math.max(0F, layer.effectParam(effect.id, "rotation", 2F, ctx.frame));
                float seed = layer.effectParam(effect.id, "seed", 0F, ctx.frame);
                float t = ctx.time * frequency * 6.2831855F;
                Shake shake = new Shake();

                shake.x = (float) (Math.sin(t + seed * 1.7F) * 0.65F + Math.sin(t * 2.3F + seed * 3.1F) * 0.35F) * amount;
                shake.y = (float) (Math.sin(t * 1.37F + seed * 5.3F) * 0.65F + Math.sin(t * 2.9F + seed * 7.7F) * 0.35F) * amount;
                shake.rotation = (float) Math.sin(t * 1.9F + seed * 11.1F) * rotation;

                return shake;
            }
        }

        return null;
    }

    private static CompExpression.Context expressionContext(CompLayer layer, CompRenderContext ctx, float value)
    {
        return new CompExpression.Context()
        {
            @Override
            public float time()
            {
                return ctx.time;
            }

            @Override
            public float frame()
            {
                return ctx.frame;
            }

            @Override
            public float fps()
            {
                return ctx.comp.fps;
            }

            @Override
            public float value()
            {
                return value;
            }

            @Override
            public float variable(String path)
            {
                return CompExpression.variable(ctx.project, ctx.comp, path, value, ctx.frame);
            }
        };
    }

    /** Drops the caches; call when a document is edited outside the frame loop. */
    public static void invalidate(CompRenderContext ctx)
    {
        ctx.cameraView = null;
        ctx.localCache.clear();
        ctx.worldCache.clear();
        ctx.modelCache.clear();
        ctx.depthCache.clear();
    }
}
