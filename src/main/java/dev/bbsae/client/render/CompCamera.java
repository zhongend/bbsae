package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * The one camera model of the compositor.
 *
 * <h2>Why this class exists</h2>
 * <p>The previous build had two unrelated camera implementations: a 2D pan/zoom matrix built from
 * {@code scale.x}, and a perspective camera built from {@code cameraZ} with a Y-flipped world space.
 * They disagreed about zoom, they disagreed about what a 2D layer does, and the 3D path only ran
 * when {@code camera3D} happened to be on — which is why a fresh camera layer appeared to do
 * nothing at all.</p>
 *
 * <p>This class derives <b>both</b> views from one camera pose. Composition space is
 * <b>X right, Y down, Z towards the viewer</b> — the same space the UI draws in, so a layer at
 * z = 0 with a camera at distance {@code focal} projects exactly 1:1 and switching a layer to 3D
 * cannot change its size or position.</p>
 *
 * <h2>The math</h2>
 * <pre>
 * focal       = (height / 2) / tan(fov / 2)
 * distance    = camera Z (fallback: focal / scaleX, for projects from the old model)
 * zoom        = focal / distance
 * forward f   = normalize(target - position)
 * right   r   = normalize(cross((0,-1,0), f))     // comp-up is -Y
 * up      u   = cross(f, r)                       // screen-up
 * view        = [ r ; u ; -f ] with translation -basis · position
 * projection  = perspective(fov, aspect, near, far)
 * view2D      = translate(w/2,h/2) · scale(zoom) · rotateZ(-roll) · translate(-cx,-cy)
 * </pre>
 *
 * <p>At z = 0 those two views agree pixel for pixel (see {@code CompCameraTest}), which is the
 * invariant the whole renderer relies on: 2D layers use {@code view2D}, 3D layers use
 * {@code projection · view}, and both land on the same comp rectangle.</p>
 */
public final class CompCamera
{
    public static final float DEFAULT_FOV = 50F;

    /** True when the composition actually has a usable camera layer. */
    public final boolean hasCamera;
    /** Vertical field of view in degrees. */
    public final float fov;
    public final float near;
    public final float far;

    /** Pinhole focal length in comp pixels: the camera distance that gives 1:1 scale. */
    public final float focal;
    /** Camera distance from the comp plane (always &gt; 0). */
    public final float distance;
    /** Comp-to-screen scale for the z = 0 plane. */
    public final float zoom;
    /** Camera roll in degrees (positive = clockwise on screen). */
    public final float roll;

    public final float cx;
    public final float cy;
    public final float cz;
    public final float tx;
    public final float ty;
    public final float tz;

    public final boolean lookAt;
    /** False = orthographic ("flat") camera: 3D layers are placed with the 2D view. */
    public final boolean perspective;
    /**
     * True when the camera looks away from the comp plane (yaw/pitch), which is the only pose in
     * which a 2D layer's affine {@link #view2D} is no longer a valid approximation of the
     * perspective view. The renderer uses this to switch flat layers onto the real 3D path.
     */
    public final boolean tilted;

    public final Matrix4f view = new Matrix4f();
    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f view2D = new Matrix4f();

    public final Vector3f position = new Vector3f();
    public final Vector3f forward = new Vector3f(0F, 0F, -1F);
    public final Vector3f right = new Vector3f(1F, 0F, 0F);
    public final Vector3f up = new Vector3f(0F, -1F, 0F);

    private CompCamera(boolean hasCamera, float fov, float near, float far, float focal, float distance,
                       float cx, float cy, float cz, float tx, float ty, float tz,
                       float roll, boolean lookAt, boolean perspective, float compWidth, float compHeight)
    {
        this.hasCamera = hasCamera;
        this.fov = fov;
        this.near = near;
        this.far = far;
        this.focal = focal;
        /* Distance is a magnitude: a camera may sit behind the comp plane (negative Z), and that
         * must flip the view direction, not produce a negative zoom. One pixel is the floor —
         * a camera exactly on the plane has infinite zoom, which is not a useful frame. */
        this.distance = Math.max(1F, Math.abs(distance));
        this.zoom = focal / this.distance;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.roll = roll;
        this.lookAt = lookAt;
        this.perspective = perspective;

        this.position.set(cx, cy, cz);

        /* Forward: either the point of interest, or the free-camera yaw/pitch. Both are expressed
         * in comp space (Y down), so "pitch up" is -Y. */
        Vector3f f = new Vector3f(tx - cx, ty - cy, tz - cz);

        if (f.lengthSquared() < 0.000001F)
        {
            f.set(0F, 0F, -1F);
        }

        f.normalize();

        Vector3f worldUp = new Vector3f(0F, -1F, 0F);
        Vector3f r = new Vector3f(worldUp).cross(f);

        if (r.lengthSquared() < 0.000001F)
        {
            r.set(1F, 0F, 0F);
        }

        r.normalize();

        Vector3f u = new Vector3f(f).cross(r).normalize();

        if (Math.abs(roll) > 0.0001F)
        {
            /* Roll around the view direction: the world appears to rotate the other way. */
            float radians = (float) Math.toRadians(roll);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            Vector3f rolled = new Vector3f(r).mul(cos).add(new Vector3f(u).mul(-sin));

            r.set(rolled).normalize();
            u.set(new Vector3f(f).cross(r)).normalize();
        }

        this.forward.set(f);
        this.right.set(r);
        this.up.set(u);
        this.tilted = Math.abs(f.x) > 0.0005F || Math.abs(f.y) > 0.0005F;

        /* Row-major math rows [r; u; -f]; JOML's constructor is column-major. */
        Vector3f nf = new Vector3f(f).negate();

        this.view.set(
            r.x, u.x, nf.x, 0F,
            r.y, u.y, nf.y, 0F,
            r.z, u.z, nf.z, 0F,
            -r.dot(this.position), -u.dot(this.position), -nf.dot(this.position), 1F
        );

        this.projection.identity().perspective(
            (float) Math.toRadians(Math.max(1F, Math.min(179F, fov))),
            Math.max(0.01F, compWidth / Math.max(1F, compHeight)),
            Math.max(0.1F, near),
            Math.max(near + 1F, far)
        );

        float rollRadians = (float) Math.toRadians(roll);

        this.view2D
            .translate(compWidth / 2F, compHeight / 2F, 0F)
            .scale(this.zoom, this.zoom, 1F)
            .rotateZ(-rollRadians)
            .translate(-cx, -cy, 0F);
    }

    /**
     * Resolves the camera for this frame. A missing camera layer yields the identity view: comp
     * centre at screen centre, 1:1 scale, no roll — the framing every 2D layer expects.
     */
    public static CompCamera of(CompDocument comp, float time, float frame)
    {
        float width = Math.max(1F, comp.width);
        float height = Math.max(1F, comp.height);
        CompLayer layer = comp.activeCamera(time);

        if (layer == null)
        {
            return identity(width, height);
        }

        Transform transform = layer.transformAt(frame);
        float fov = Math.max(1F, Math.min(179F, layer.scalarAt("cameraFov", layer.cameraFov, frame)));
        float near = Math.max(0.1F, layer.scalarAt("cameraNear", layer.cameraNear, frame));
        float far = Math.max(near + 1F, layer.scalarAt("cameraFar", layer.cameraFar, frame));
        float focal = focalFor(fov, height);
        float z = distanceOf(layer, transform, frame, height);

        float cx = transform.translate.x;
        float cy = transform.translate.y;
        float roll = transform.rotate.z;
        boolean lookAt = layer.cameraLookAt;
        float tx = layer.scalarAt("cameraTargetX", layer.cameraTargetX, frame);
        float ty = layer.scalarAt("cameraTargetY", layer.cameraTargetY, frame);
        float tz = layer.scalarAt("cameraTargetZ", layer.cameraTargetZ, frame);

        if (!lookAt)
        {
            /* Free camera: derive the point of interest from yaw/pitch so both modes share one
             * matrix builder. yaw>0 looks right, pitch>0 looks up (comp Y is down).
             *
             * The target is placed relative to the *signed* eye position. The old code paired an
             * absolute eye ({@code Math.abs(z)}) with a signed target ({@code z - 1000}): with a
             * negative camera Z the two disagreed and the camera looked exactly the wrong way. */
            float yaw = (float) Math.toRadians(layer.scalarAt("rotationY", layer.rotationY, frame));
            float pitch = (float) Math.toRadians(layer.scalarAt("rotationX", layer.rotationX, frame));
            float dirX = (float) (Math.sin(yaw) * Math.cos(pitch));
            float dirY = (float) (-Math.sin(pitch));
            float dirZ = (float) (-Math.cos(yaw) * Math.cos(pitch));

            tx = cx + dirX * 1000F;
            ty = cy + dirY * 1000F;
            tz = z + dirZ * 1000F;
        }

        return new CompCamera(true, fov, near, far, focal, z,
            cx, cy, z, tx, ty, tz, roll, lookAt, layer.camera3D, width, height);
    }

    /**
     * Eye position of a camera layer in composition space (X, Y, Z).
     *
     * <p>This is the single definition of "where the camera is": the renderer, the viewport gizmo
     * and the camera drag all call it, so a dragged eye cannot drift from the rendered view.</p>
     */
    public static Vector3f eyeOf(CompLayer layer, float frame, float compHeight)
    {
        Transform transform = layer.transformAt(frame);

        return new Vector3f(transform.translate.x, transform.translate.y, distanceOf(layer, transform, frame, compHeight));
    }

    /**
     * Z of the camera, in priority order:
     * <ol>
     *   <li>the {@code cameraZ} channel — it exists, so its value is the answer even when it is 0;</li>
     *   <li>the {@code cameraZ} field (a camera created by the inspector always fills it);</li>
     *   <li>the generic 3D {@code z} (files authored before the camera rewrite stored it there);</li>
     *   <li>{@code focal / scaleX} (the original 2D camera expressed zoom through scale).</li>
     * </ol>
     *
     * <p>The old code tested "is cameraZ non-zero" instead of "does cameraZ exist", so a keyframed
     * dolly that passed through 0 snapped to a completely different distance for one frame.</p>
     */
    public static float distanceOf(CompLayer layer, Transform transform, float frame, float compHeight)
    {
        float fov = Math.max(1F, Math.min(179F, layer.scalarAt("cameraFov", layer.cameraFov, frame)));
        float focal = focalFor(fov, compHeight);
        float z = layer.cameraZ;

        if (layer.hasScalarChannel("cameraZ"))
        {
            z = layer.scalarAt("cameraZ", z, frame);
        }
        else if (Math.abs(z) < 0.001F)
        {
            z = layer.scalarAt("z", layer.z, frame);
        }

        if (Math.abs(z) < 0.001F)
        {
            z = focal / Math.max(0.01F, Math.abs(transform.scale.x));
        }

        return z;
    }

    /** Framing used when the composition has no camera: centre, 1:1, no roll. */
    public static CompCamera identity(float width, float height)
    {
        float focal = focalFor(DEFAULT_FOV, height);

        return new CompCamera(false, DEFAULT_FOV, 10F, 100000F, focal, focal,
            width / 2F, height / 2F, focal,
            width / 2F, height / 2F, 0F,
            0F, true, true, width, height);
    }

    public static float focalFor(float fovDegrees, float compHeight)
    {
        return (compHeight * 0.5F) / (float) Math.tan(Math.toRadians(Math.max(1F, Math.min(179F, fovDegrees))) * 0.5D);
    }

    /**
     * Projects a world-space point (comp space, Y down, Z towards the viewer) to composition
     * pixels. Returns null when the point is behind the camera.
     */
    public Vector2f project(Vector3f world, float compWidth, float compHeight)
    {
        Vector3f point = new Vector3f(world);

        if (this.perspective)
        {
            Vector3f cameraSpace = new Vector3f(point);
            this.view.transformPosition(cameraSpace);

            if (cameraSpace.z > -Math.max(0.01F, this.near))
            {
                return null;
            }

            Vector3f ndc = new Vector3f(point);

            this.view.transformPosition(ndc);
            /* transformProject, not transformPosition: the projection row w = -z, and JOML's
             * transformPosition deliberately ignores the perspective divide. */
            this.projection.transformProject(ndc);

            return new Vector2f(
                (ndc.x * 0.5F + 0.5F) * compWidth,
                (1F - (ndc.y * 0.5F + 0.5F)) * compHeight
            );
        }

        this.view2D.transformPosition(point);

        return new Vector2f(point.x, point.y);
    }

    /**
     * Screen pixel -> world point on the horizontal plane {@code z = zPlane}. This is what the
     * camera target handle drags along, and what a 3D layer drag would need; the old build
     * approximated it by scaling the screen delta, which drifted as soon as the camera turned.
     */
    public Vector3f unproject(float px, float py, float compWidth, float compHeight, float zPlane)
    {
        if (!this.perspective)
        {
            Vector3f point = new Vector3f(px, py, 0F);
            Matrix4f inverse = new Matrix4f(this.view2D).invert();

            inverse.transformPosition(point);
            point.z = zPlane;

            return point;
        }

        float ndcX = px / Math.max(1F, compWidth) * 2F - 1F;
        float ndcY = 1F - py / Math.max(1F, compHeight) * 2F;

        Matrix4f inverse = new Matrix4f(this.projection).mul(this.view).invert();
        org.joml.Vector4f near = new org.joml.Vector4f(ndcX, ndcY, -1F, 1F);
        org.joml.Vector4f far = new org.joml.Vector4f(ndcX, ndcY, 1F, 1F);

        inverse.transform(near);
        inverse.transform(far);
        near.div(near.w);
        far.div(far.w);

        float dz = far.z - near.z;
        float t = Math.abs(dz) < 0.000001F ? 0F : (zPlane - near.z) / dz;

        return new Vector3f(
            near.x + (far.x - near.x) * t,
            near.y + (far.y - near.y) * t,
            zPlane
        );
    }

    /**
     * Painter-sort key: larger means closer to the viewer. Perspective uses camera-space Z, the
     * flat camera uses world Z; both sort ascending so the far layer is drawn first.
     */
    public float depthOf(Vector3f world)
    {
        if (!this.perspective)
        {
            return world.z;
        }

        Vector3f cameraSpace = new Vector3f(world);
        this.view.transformPosition(cameraSpace);

        return cameraSpace.z;
    }

    /** Screen-space rectangle of the comp at z = 0 after the 2D view (camera or identity). */
    public void compQuad(float width, float height, float[] out)
    {
        Vector3f point = new Vector3f();

        for (int i = 0; i < 4; i++)
        {
            point.set(i == 1 || i == 2 ? width : 0F, i >= 2 ? height : 0F, 0F);
            this.view2D.transformPosition(point);
            out[i * 2] = point.x;
            out[i * 2 + 1] = point.y;
        }
    }
}
