package dev.bbsae.client.render;

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
 * <p>One space, one matrix per layer, one camera. World space is composition pixels: <b>X right,
 * Y down, Z towards the viewer</b>. The transform composition, the parent chain and the drag
 * compensation are identical for 2D and 3D layers.</p>
 *
 * <h2>AE camera semantics ({@link #cameraAffects})</h2>
 * <p>A layer follows the composition camera <b>only when its own 3D switch is on</b>. A 2D layer
 * is attached to the composition rectangle: pan, zoom, roll, dolly and tilt move the 3D layers
 * around it, never the layer itself. That is the After Effects "2D layers are screen-space"
 * rule, and it is why a 2D layer can no longer grow when the camera dollies. The legacy
 * {@code legacy3DLogic} switch restores the pre-AE behaviour for old projects, where every
 * content layer went through the camera view once the frame became spatial.</p>
 *
 * <p>World matrices are cached per {@link CompRenderContext}, i.e. per composition per frame:
 * the render loop, the depth sort, the gizmos and the hit test all read the same matrix instead
 * of rebuilding it three or four times.</p>
 */
public final class CompScene
{
    private CompScene()
    {}

    /**
     * A scale that is safe to put into a matrix without losing its sign.
     *
     * <p>Zero would make the matrix singular, so it is nudged to the smallest representable
     * magnitude — keeping the sign, because a negative scale is a mirror and must keep rendering a
     * mirrored layer instead of a 0.0001 px sliver.</p>
     */
    static float safeScale(float scale)
    {
        if (Float.isNaN(scale))
        {
            return 1F;
        }

        if (Math.abs(scale) >= 0.0001F)
        {
            return scale;
        }

        return scale < 0F ? -0.0001F : 0.0001F;
    }

    /** The camera of this frame (cached on the context). */
    /**
     * Optional camera for one render call. The replay editor's orbit/free views render the same
     * composition graph from the free camera instead of the shot camera; a thread-local keeps that
     * override out of every layer call without changing the graph signatures.
     */
    /**
     * Stack, not a single slot: a collapsed precomp pushes the parent camera while an outer
     * explicit-camera render (the replay free/orbit view) is already active. Setting one slot and
     * removing it on pop dropped the outer camera for every layer after the precomp.
     *
     * <p>{@link java.util.LinkedList} is used instead of {@code ArrayDeque} because it tolerates
     * the null entry used by "temporarily suppress the override" callers.</p>
     */
    private static final ThreadLocal<java.util.Deque<CompCamera>> CAMERA_OVERRIDES =
        ThreadLocal.withInitial(java.util.LinkedList::new);

    public static void pushCamera(CompCamera camera)
    {
        CAMERA_OVERRIDES.get().push(camera);
    }

    public static void popCamera()
    {
        java.util.Deque<CompCamera> stack = CAMERA_OVERRIDES.get();

        if (!stack.isEmpty())
        {
            stack.pop();
        }

        if (stack.isEmpty())
        {
            CAMERA_OVERRIDES.remove();
        }
    }

    /** True while a caller has an explicit camera override (including a suppression null). */
    public static boolean hasCameraOverride()
    {
        java.util.Deque<CompCamera> stack = CAMERA_OVERRIDES.get();

        return !stack.isEmpty();
    }

    /**
     * Temporarily removes the active override so a nested comp can use its own camera. Pair with
     * {@link #restoreCameraOverride(CompCamera)} in a finally block.
     */
    public static CompCamera suspendCameraOverride()
    {
        java.util.Deque<CompCamera> stack = CAMERA_OVERRIDES.get();

        if (stack.isEmpty())
        {
            return null;
        }

        CompCamera top = stack.pop();

        if (stack.isEmpty())
        {
            CAMERA_OVERRIDES.remove();
        }

        return top;
    }

    public static void restoreCameraOverride(CompCamera camera)
    {
        if (camera != null)
        {
            pushCamera(camera);
        }
    }

    public static CompCamera camera(CompRenderContext ctx)
    {
        java.util.Deque<CompCamera> stack = CAMERA_OVERRIDES.get();
        CompCamera override = stack.isEmpty() ? null : stack.peek();

        if (override != null)
        {
            ctx.cameraView = override;

            return override;
        }

        if (ctx.cameraView == null)
        {
            ctx.cameraView = CompCamera.of(ctx.comp, ctx.time, ctx.frame);
        }

        return ctx.cameraView;
    }

    /**
     * Binding point of one layer this frame, resolved exactly once.
     *
     * <p>{@link BindingResolver} applies temporal smoothing, so resolving twice per frame (once for
     * the local translation, once for the parent correction) would advance the filter twice and make
     * a smoothed binding lag differently from the preview.</p>
     */
    static Vector2f boundPoint(CompRenderContext ctx, CompLayer layer)
    {
        if (ctx.bindingCache.containsKey(layer))
        {
            return ctx.bindingCache.get(layer);
        }

        Vector2f point = BindingResolver.resolve(layer, ctx);

        ctx.bindingCache.put(layer, point);

        return point;
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
            x = CompExpression.eval(layer.expressionX, expressionContext(layer, ctx, x, "x"), x);
        }
        if (layer.expressionY != null && !layer.expressionY.isBlank())
        {
            y = CompExpression.eval(layer.expressionY, expressionContext(layer, ctx, y, "y"), y);
        }
        if (layer.expressionRotation != null && !layer.expressionRotation.isBlank())
        {
            rotation = CompExpression.eval(layer.expressionRotation, expressionContext(layer, ctx, rotation, "rotation"), rotation);
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

        Vector2f bound = boundPoint(ctx, layer);

        if (bound != null)
        {
            x = bound.x;
            y = bound.y;
        }

        /* A scale of exactly 0 makes the matrix singular (nothing is drawable and the selection box
         * collapses), so the guard stays. It must not clamp the MAGNITUDE, though: -1 is a mirror,
         * not an error, and the old positive clamp turned every flip into a 0.0001 px sliver. */
        float rawSx = transform.scale.x * (1F + layer.squash);
        float rawSy = transform.scale.y * (1F - layer.squash);
        float sx = safeScale(rawSx);
        float sy = safeScale(rawSy);
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

        Matrix4f parentWorld = world(ctx, parent, seen);
        Vector2f bound = boundPoint(ctx, layer);

        if (bound != null)
        {
            /* The binding point is in composition space, but the layer is a child: its translation
             * lives in parent space. Solve for the local translation whose parent-transformed
             * position is exactly the bound point. Without this a parented tracked layer was offset
             * by the parent's transform (the binding looked wrong/duplicated). */
            org.joml.Vector3f target = new org.joml.Vector3f(bound.x, bound.y, local.m32());

            new Matrix4f(parentWorld).invert().transformPosition(target);
            local.m30(target.x);
            local.m31(target.y);
            local.m32(target.z);
        }

        return parentWorld.mul(local);
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
     * Model matrix handed to the content renderer.
     *
     * <p>For a layer the camera affects this is its world matrix seen through the camera: the
     * perspective {@code view} for a projected 3D layer, the affine {@code view2D} for a 3D layer
     * under an orthographic camera. A layer the camera does not affect (an AE 2D layer) is handed
     * its raw world matrix: it lives in composition pixels, so the caller pairs it with the
     * orthographic composition projection and the camera cannot move it.</p>
     */
    public static Matrix4f model(CompRenderContext ctx, CompLayer layer)
    {
        Matrix4f cached = ctx.modelCache.get(layer);

        if (cached != null)
        {
            return cached;
        }

        Matrix4f world = world(ctx, layer);
        Matrix4f model;

        if (cameraAffects(ctx, layer))
        {
            CompCamera camera = camera(ctx);

            model = usePerspective(ctx, layer)
                ? new Matrix4f(camera.view).mul(world)
                : new Matrix4f(camera.view2D).mul(world);
        }
        else
        {
            /* Screen-space layer: composition pixels in, composition pixels out. The whole
             * reason it cannot be camera-dollied or rolled. */
            model = new Matrix4f(world);
        }

        ctx.modelCache.put(layer, model);

        return model;
    }

    /**
     * True when the frame contains real 3D geometry to resolve: the camera looks at the comp at an
     * angle, or a real 3D pixel layer sits off the comp plane.
     *
     * <p>AE projection no longer depends on this — each layer decides by its own 3D switch — but
     * legacy projection and the AUTO per-pixel-depth gate still need the frame-level answer, so it
     * is cached on the context.</p>
     */
    public static boolean spatial(CompRenderContext ctx)
    {
        if (ctx.spatial != null)
        {
            return ctx.spatial;
        }

        CompCamera camera = camera(ctx);
        boolean spatial = camera.perspective && (camera.tilted || hasDepth(ctx));

        ctx.spatial = spatial;

        return spatial;
    }

    /**
     * True when any <b>real 3D pixel layer</b> sits off the composition plane at this frame.
     *
     * <p>Only layers that can actually be projected count: a layer that is not 3D, has no pixels
     * (camera / null / adjustment), is hidden, trimmed out, or soloed out cannot put anything out
     * of the comp plane. Before this filter a leftover {@code z} on a hidden 2D layer, a camera's
     * dolly distance, or a trimmed-out 3D layer switched the whole frame into spatial mode (and
     * could trigger the per-pixel depth path) with no visible pixel behind it.</p>
     *
     * <p>An independent replay model is the one exception to the {@code threeD} test: its target
     * carries real per-fragment depth even when an old project never set the switch, so it is
     * depth content at z = 0.</p>
     *
     * <p>The animated {@code z} channel is sampled, not the static field: a keyframed 3D layer
     * that only leaves z=0 in the middle of the shot used to keep the whole frame non-spatial (and
     * make the 3D layers jump when it finally moved). The static field remains the fallback for
     * layers without a channel, exactly like {@link CompLayer#scalarAt}.</p>
     */
    public static boolean hasDepth(CompRenderContext ctx)
    {
        if (ctx == null || ctx.comp == null || ctx.comp.layers == null)
        {
            return false;
        }

        boolean solo = ctx.comp.hasSolo();

        for (CompLayer layer : ctx.comp.layers)
        {
            boolean model = isModelVolume(layer);

            if (layer == null || (!layer.threeD && !model)
                || !layer.activeAt(ctx.time) || !hasPixels(layer))
            {
                continue;
            }

            if (solo && !layer.solo)
            {
                continue;
            }

            /* An independent replay model is a depth volume even when its layer origin is on
             * z = 0: the actor has thickness, so a 3D text/shape plane can cut through it. Treating
             * it as a flat plane is what made the actor "penetrate" those layers. */
            if (model)
            {
                return true;
            }

            if (Math.abs(layer.scalarAt("z", layer.z, ctx.frame)) > 0.01F)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * True when this layer renders a real BBS form with its own per-fragment depth (an independent
     * replay entity), rather than a textured plane. Such a layer must take part in the 3D sort and
     * in per-pixel depth compositing even when it was authored before the layer's {@code threeD}
     * switch existed.
     */
    public static boolean isModelVolume(CompLayer layer)
    {
        return layer != null && layer.independent && layer.isEntityLayer()
            && (layer.hasModelForm() || layer.hasLinkedReplay());
    }

    /**
     * Orthographic (comp pixels) for the layers the camera does not project, the camera
     * perspective for 3D layers in AE logic (and for spatial frames in legacy logic).
     */
    public static Matrix4f projection(CompRenderContext ctx, CompLayer layer)
    {
        return usePerspective(ctx, layer)
            ? camera(ctx).projection
            : GlStateGuard.ortho(ctx.width, ctx.height);
    }

    /**
     * True when the composition camera moves this layer's pixels.
     *
     * <p><b>AE logic (default):</b> only a layer with its own 3D switch on follows the camera.
     * A 2D layer is part of the composition rectangle, not of the 3D scene, so pan, zoom, roll and
     * tilt must leave it untouched — that is the whole point of the 2D/3D switch. Cameras and
     * adjustment layers never follow themselves (they have no projected pixels).</p>
     *
     * <p><b>{@code legacy3DLogic}:</b> every content layer follows the camera, exactly like the
     * pre-AE build, so old projects keep their look.</p>
     */
    public static boolean cameraAffects(CompRenderContext ctx, CompLayer layer)
    {
        if (layer == null || "camera".equals(layer.type) || "adjustment".equals(layer.type))
        {
            return false;
        }

        /* A bound layer is pinned to a projected BBS point in composition space. Letting the camera
         * affect it would project that point a second time, so bindings are screen-space by design
         * (HUD labels, actor tags). The test is the resolved point, not the "enabled" switch: an
         * unresolved target (no film/controller/replay in this render path) must behave exactly
         * like an unbound layer instead of freezing a 3D layer out of the camera. */
        if (boundPoint(ctx, layer) != null)
        {
            return false;
        }

        if (legacy3D(ctx))
        {
            return true;
        }

        if ("bbs".equals(layer.type) && hasCameraOverride())
        {
            return true;
        }

        return layer.threeD || isModelVolume(layer);
    }

    /**
     * Camera / null / adjustment layers produce no pixels of their own.
     *
     * <p>Shared by the renderer, the depth/spatial gates and the UI: every "does this layer own a
     * picture" decision must use this one predicate. A track matte or a depth sort that counted a
     * control layer was either a silent no-op or a fully transparent raster.</p>
     */
    public static boolean hasPixels(CompLayer layer)
    {
        return layer != null
            && !"camera".equals(layer.type)
            && !"null".equals(layer.type)
            && !"adjustment".equals(layer.type);
    }

    /**
     * True when a layer's own 3D switch makes it part of the spatial layer stack.
     *
     * <p>AE semantics: only 3D layers take part in depth sorting. A 2D layer stays at its stack
     * slot and breaks a 3D run, because its pixels are composited in stack order no matter where
     * the 3D planes are. Legacy logic keeps the old behaviour (every content layer sorts once the
     * frame becomes spatial).</p>
     */
    public static boolean sortsIn3D(CompRenderContext ctx, CompLayer layer)
    {
        if (!hasPixels(layer))
        {
            return false;
        }

        /* A binding pins the layer to a screen point: it is a flat overlay at that point, not a
         * plane at its old local Z. Sorting it as a 3D plane would let it disappear behind geometry
         * it is supposed to be drawn over. Resolved only: an unavailable target must not change the
         * layer's 3D behaviour. */
        if (boundPoint(ctx, layer) != null)
        {
            return false;
        }

        return layer.threeD || isModelVolume(layer)
            || "bbs".equals(layer.type) && hasCameraOverride()
            || (legacy3D(ctx) && spatial(ctx));
    }

    public static boolean usePerspective(CompRenderContext ctx, CompLayer layer)
    {
        if (layer == null || "camera".equals(layer.type) || "adjustment".equals(layer.type))
        {
            return false;
        }

        /* Keep the projection consistent with {@link #cameraAffects}: a bound layer's world matrix
         * already is the composition-space anchor, so a perspective projection would transform it a
         * second time. Only a resolved binding gets this treatment — see cameraAffects. */
        if (boundPoint(ctx, layer) != null)
        {
            return false;
        }

        if (legacy3D(ctx))
        {
            /* Compatibility mode (Settings → 开发者 → 3D 逻辑): every layer used to be projected
             * through the camera as soon as the frame became spatial, whether or not the layer's
             * own 3D switch was on. Old projects can still be opened with exactly that look. */
            return spatial(ctx) || (layer.threeD && camera(ctx).perspective);
        }

        /* The BBS preview layer is a composition-sized world capture. In the shot camera it is
         * normally a screen-fixed backdrop; in the free/orbit composition render it must be a
         * projected plane, otherwise the whole workspace background is that layer again. */
        if ("bbs".equals(layer.type) && hasCameraOverride())
        {
            return camera(ctx).perspective;
        }

        /* A model target is always rendered through the camera projection, whatever the legacy
         * threeD flag says: it is geometry, not a screen-fixed plate. */
        if (isModelVolume(layer))
        {
            return camera(ctx).perspective;
        }

        /* AE mode: the 3D switch is the only thing that opts a layer into perspective. */
        return layer.threeD && camera(ctx).perspective;
    }

    /**
     * True when this project asks for the legacy (pre-AE) 3D layer logic. Off for every new
     * project; see {@link dev.bbsae.comp.CompProject.CompSettings#legacy3DLogic}.
     */
    public static boolean legacy3D(CompRenderContext ctx)
    {
        return ctx != null
            && ctx.project != null
            && ctx.project.settings != null
            && ctx.project.settings.legacy3DLogic;
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

    /**
     * Plane depth against an explicit eye/forward pair. The 3D workspace draws with its own orbit
     * camera, which is not the composition camera {@link #camera(CompRenderContext)} resolves; using
     * the composition camera there sorted layers by the wrong viewpoint, so orbiting made them
     * interpenetrate.
     */
    public static float planeDepth(Matrix4f world, Vector3f eye, Vector3f forward)
    {
        Vector3f origin = new Vector3f();
        Vector3f normal = new Vector3f(0F, 0F, 1F);

        world.transformPosition(origin);
        world.transformDirection(normal);

        float view = -(origin.x - eye.x) * forward.x - (origin.y - eye.y) * forward.y
            - (origin.z - eye.z) * forward.z;

        if (normal.lengthSquared() < 0.000001F)
        {
            return view;
        }

        normal.normalize();

        float denominator = normal.dot(forward);

        if (Math.abs(denominator) < 0.02F)
        {
            return view;
        }

        float alongNormal = (origin.x - eye.x) * normal.x + (origin.y - eye.y) * normal.y
            + (origin.z - eye.z) * normal.z;

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
     *
     * <p>A 2D layer (AE logic) is screen-fixed: its world position <b>is</b> the composition
     * pixel, no camera view is applied. A 3D layer goes through the perspective camera, or through
     * {@code view2D} for an orthographic camera — exactly matching {@link #model}.</p>
     */
    public static Vector2f project(CompRenderContext ctx, CompLayer layer, float x, float y, float z)
    {
        Vector3f point = new Vector3f(x, y, z);

        world(ctx, layer).transformPosition(point);

        if (!cameraAffects(ctx, layer))
        {
            return new Vector2f(point.x, point.y);
        }

        CompCamera camera = camera(ctx);

        if (usePerspective(ctx, layer))
        {
            return camera.project(point, ctx.width, ctx.height);
        }

        camera.view2D.transformPosition(point);

        return new Vector2f(point.x, point.y);
    }

    /**
     * Composition pixel → layer-local point on the layer's own plane, or null when the layer
     * plane is edge-on / behind the camera. The exact inverse of
     * {@link #project(CompRenderContext, CompLayer, float, float, float)} for z = 0.
     *
     * <p>This is the shared geometry the editor's mask drag, layer drag and pen tool need. A 2D
     * layer (AE logic) is inverted through its world matrix because its composition pixel is its
     * world pixel; a 3D layer is intersected with its plane by the camera ray. Callers that work
     * in screen pixels must first map them through their panel's composition fit.</p>
     */
    public static float[] compToLayerLocal(CompRenderContext ctx, CompLayer layer, float compX, float compY)
    {
        if (ctx == null || layer == null)
        {
            return null;
        }

        Matrix4f world = world(ctx, layer);

        if (!cameraAffects(ctx, layer) || !usePerspective(ctx, layer))
        {
            /* Affine path: the pixel is model · local with local z = 0. Invert the model, then
             * walk the (comp-space) screen ray back onto the local z = 0 plane. A plain 2D layer
             * has no tilt and the ray is degenerate, so the inverse point is returned directly. */
            Matrix4f inverse = new Matrix4f(model(ctx, layer));

            try
            {
                inverse.invert();
            }
            catch (RuntimeException e)
            {
                return null;
            }

            Vector3f origin = new Vector3f(compX, compY, 0F);
            Vector3f direction = new Vector3f(0F, 0F, 1F);

            inverse.transformPosition(origin);
            inverse.transformDirection(direction);

            if (Math.abs(direction.z) < 0.000001F)
            {
                return new float[] {origin.x, origin.y};
            }

            float t = -origin.z / direction.z;

            return new float[] {origin.x + direction.x * t, origin.y + direction.y * t};
        }

        CompCamera camera = camera(ctx);
        Vector3f rayA = camera.unproject(compX, compY, ctx.width, ctx.height, 0F);
        Vector3f rayB = camera.unproject(compX, compY, ctx.width, ctx.height, 1000F);

        if (rayA == null || rayB == null)
        {
            return null;
        }

        Vector3f origin = world.transformPosition(new Vector3f(0F, 0F, 0F));
        Vector3f normal = world.transformDirection(new Vector3f(0F, 0F, 1F));

        if (normal.lengthSquared() < 0.000001F)
        {
            return null;
        }

        normal.normalize();

        Vector3f direction = new Vector3f(rayB).sub(rayA);
        float denominator = direction.dot(normal);

        if (Math.abs(denominator) < 0.00001F)
        {
            return null;
        }

        float t = new Vector3f(origin).sub(rayA).dot(normal) / denominator;
        Vector3f hit = new Vector3f(rayA).fma(t, direction);
        Vector3f local = new Vector3f(hit);

        try
        {
            new Matrix4f(world).invert().transformPosition(local);
        }
        catch (RuntimeException e)
        {
            return null;
        }

        return new float[] {local.x, local.y};
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
                /* Shake is a transform, so the pixel pass is a no-op; the 效果强度 cross-fade has to
                 * be applied here instead of being silently ignored.
                 *
                 * Every value goes through effectValue(), not effectParam(): shake is authored with
                 * the inspector sliders, which write CompEffect.params and create no channel at
                 * all. A channel-only read returned the fallback for every one of them, so the
                 * sliders (and 效果强度) had no effect on the render until a keyframe existed. */
                float strength = Math.max(0F, Math.min(1F,
                    layer.effectValue(effect, dev.bbsae.comp.CompEffect.STRENGTH, 1F, ctx.frame)));

                if (strength <= 0.0001F)
                {
                    continue;
                }

                float amount = Math.max(0F, layer.effectValue(effect, "amplitude", 18F, ctx.frame)) * strength;
                float frequency = Math.max(0.01F, layer.effectValue(effect, "frequency", 6F, ctx.frame));
                float rotation = Math.max(0F, layer.effectValue(effect, "rotation", 2F, ctx.frame)) * strength;
                float seed = layer.effectValue(effect, "seed", 0F, ctx.frame);
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

    private static CompExpression.Context expressionContext(CompLayer layer, CompRenderContext ctx,
                                                            float value, String property)
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

            @Override
            public int seed()
            {
                return property == null ? 0 : (layer.id + "/" + property).hashCode();
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
