package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The editor's own camera: an orbit camera for the 3D workspace.
 *
 * <h2>Why this is a second camera (and why that is not a second projection path)</h2>
 * <p>{@link CompCamera} answers "what does the composition camera see" — it is the frame that gets
 * exported. This class answers "where is the editor standing while it edits" — it never appears in
 * a rendered frame. Blender has exactly the same split (viewport camera vs. scene camera), and the
 * split is the reason a 3D viewport can be navigated at all: you cannot orbit around a camera whose
 * own pose you are trying to edit.</p>
 *
 * <p>Both cameras still produce the <b>same kind of matrix</b> in the <b>same space</b>
 * (composition pixels, X right, Y down, Z towards the viewer), built by the same steps as
 * {@link CompCamera}: a view basis {@code (right, up, -forward)} and a projection. The invariant
 * test {@code CompViewportCameraTest.lookThroughMatchesTheCompositionCamera} pins it: pointing this
 * camera at the comp centre from the focal distance with the composition's FOV must reproduce
 * {@link CompCamera}'s projection pixel for pixel. So "look through camera" is a pose, not a mode
 * switch with its own math.</p>
 *
 * <h2>Navigation model (Blender's, deliberately)</h2>
 * <pre>
 * middle drag           orbit around the target
 * shift + middle drag   pan (move the target in the view plane)
 * wheel                 dolly towards the cursor (zoom about a point, not the centre)
 * numpad 0              look through the composition camera / back to the free view
 * numpad 1/3/7          front / right / top   (ctrl = opposite side)
 * numpad 5              orthographic / perspective
 * home                  frame the whole composition
 * </pre>
 *
 * <p>The state is a target point plus spherical offset ({@code yaw}, {@code pitch}) plus
 * {@code distance}. That is four numbers the inspector can show, the project can persist and a
 * test can assert — unlike a raw matrix, which drifts and cannot be clamped.</p>
 */
public final class CompViewportCamera
{
    /** Blender's default orbit step: about 1.4 full turns across a 1080p-wide drag feels wrong at 0.4. */
    public static final float ORBIT_DEGREES_PER_PIXEL = 0.4F;
    public static final float MIN_PITCH = -89.5F;
    public static final float MAX_PITCH = 89.5F;
    public static final float MIN_DISTANCE = 1F;
    public static final float MAX_DISTANCE = 1_000_000F;

    public final Vector3f target = new Vector3f();
    public float distance = 2000F;
    /** Degrees. 0 looks down -Z (from +Z towards the composition plane). */
    public float yaw;
    /** Degrees. Positive = the camera is above the target (composition Y points down). */
    public float pitch = 20F;
    public float fov = CompCamera.DEFAULT_FOV;
    public boolean ortho;
    public float near = 1F;
    public float far = 200_000F;

    /** Viewport rectangle in screen pixels; only used for aspect and pixel↔world scale. */
    public int width = 1;
    public int height = 1;

    public final Vector3f position = new Vector3f();
    public final Vector3f forward = new Vector3f(0F, 0F, -1F);
    public final Vector3f right = new Vector3f(1F, 0F, 0F);
    public final Vector3f up = new Vector3f(0F, -1F, 0F);

    public final Matrix4f view = new Matrix4f();
    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f viewProjection = new Matrix4f();
    public final Matrix4f inverseViewProjection = new Matrix4f();

    private boolean matricesDirty = true;
    /* The last values the matrices were built from. Public fields (ortho in particular) can be
     * toggled from the UI, so "did anything change" cannot rely on every caller remembering to
     * mark the cache dirty — it is compared, not trusted. */
    private float builtYaw = Float.NaN;
    private float builtPitch = Float.NaN;
    private float builtDistance = Float.NaN;
    private float builtFov = Float.NaN;
    private boolean builtOrtho;
    private int builtWidth;
    private int builtHeight;
    private float builtTargetX = Float.NaN;
    private float builtTargetY = Float.NaN;
    private float builtTargetZ = Float.NaN;

    public CompViewportCamera setViewport(int width, int height)
    {
        int w = Math.max(1, width);
        int h = Math.max(1, height);

        if (w != this.width || h != this.height)
        {
            this.width = w;
            this.height = h;
            this.matricesDirty = true;
        }

        return this;
    }

    public CompViewportCamera setTarget(float x, float y, float z)
    {
        this.target.set(x, y, z);
        this.matricesDirty = true;

        return this;
    }

    public CompViewportCamera setPose(float yaw, float pitch, float distance)
    {
        this.yaw = yaw;
        this.pitch = clampPitch(pitch);
        this.distance = clampDistance(distance);
        this.matricesDirty = true;

        return this;
    }

    /** Recomputes the cached basis and matrices. Cheap; call once per frame, not per point. */
    public void update()
    {
        if (!this.matricesDirty
            && this.builtYaw == this.yaw && this.builtPitch == this.pitch
            && this.builtDistance == this.distance && this.builtFov == this.fov
            && this.builtOrtho == this.ortho
            && this.builtWidth == this.width && this.builtHeight == this.height
            && this.builtTargetX == this.target.x && this.builtTargetY == this.target.y
            && this.builtTargetZ == this.target.z)
        {
            return;
        }

        this.matricesDirty = false;
        this.builtYaw = this.yaw;
        this.builtPitch = this.pitch;
        this.builtDistance = this.distance;
        this.builtFov = this.fov;
        this.builtOrtho = this.ortho;
        this.builtWidth = this.width;
        this.builtHeight = this.height;
        this.builtTargetX = this.target.x;
        this.builtTargetY = this.target.y;
        this.builtTargetZ = this.target.z;

        float yawRadians = (float) Math.toRadians(this.yaw);
        float pitchRadians = (float) Math.toRadians(this.pitch);
        float cp = (float) Math.cos(pitchRadians);
        float sp = (float) Math.sin(pitchRadians);

        /* Offset from the target to the eye. Y is down in composition space, so "above" is -Y:
         * a positive pitch puts the eye above the target. */
        this.position.set(
            this.target.x + this.distance * cp * (float) Math.sin(yawRadians),
            this.target.y - this.distance * sp,
            this.target.z + this.distance * cp * (float) Math.cos(yawRadians)
        );

        Vector3f f = new Vector3f(this.target).sub(this.position);

        if (f.lengthSquared() < 0.000001F)
        {
            f.set(0F, 0F, -1F);
        }

        f.normalize();

        Vector3f worldUp = new Vector3f(0F, -1F, 0F);
        Vector3f r = new Vector3f(worldUp).cross(f);

        if (r.lengthSquared() < 0.000001F)
        {
            /* Looking straight down (pitch ≈ ±90): any right vector perpendicular to -Y works.
             * X right keeps the top view aligned with the composition, like Blender's numpad 7. */
            r.set(1F, 0F, 0F);
        }

        r.normalize();

        Vector3f u = new Vector3f(f).cross(r).normalize();

        this.forward.set(f);
        this.right.set(r);
        this.up.set(u);

        Vector3f nf = new Vector3f(f).negate();

        this.view.set(
            r.x, u.x, nf.x, 0F,
            r.y, u.y, nf.y, 0F,
            r.z, u.z, nf.z, 0F,
            -r.dot(this.position), -u.dot(this.position), -nf.dot(this.position), 1F
        );

        float aspect = Math.max(0.01F, this.width / (float) Math.max(1, this.height));

        if (this.ortho)
        {
            /* Match the perspective size at the target plane, so toggling ortho does not change
             * the framing — it only removes the foreshortening. */
            float halfHeight = Math.max(0.5F, this.distance * (float) Math.tan(Math.toRadians(this.clampedFov()) * 0.5D));
            float halfWidth = halfHeight * aspect;

            this.projection.identity().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight,
                Math.max(0.01F, this.near), Math.max(this.near + 1F, this.far));
        }
        else
        {
            this.projection.identity().perspective(
                (float) Math.toRadians(this.clampedFov()),
                aspect,
                Math.max(0.01F, this.near),
                Math.max(this.near + 1F, this.far)
            );
        }

        this.viewProjection.set(this.projection).mul(this.view);
        this.inverseViewProjection.set(this.viewProjection).invert();
    }

    /** Orthographic / perspective. A setter, so the state cache notices (see {@link #update()}). */
    public CompViewportCamera setOrtho(boolean ortho)
    {
        this.ortho = ortho;
        this.matricesDirty = true;

        return this;
    }

    public float clampedFov()
    {
        return Math.max(1F, Math.min(179F, this.fov));
    }

    /* ------------------------------------------------------------- navigation */

    /** Middle-drag: orbit around the target. */
    public void orbit(float dxPixels, float dyPixels)
    {
        this.yaw -= dxPixels * ORBIT_DEGREES_PER_PIXEL;
        this.pitch = clampPitch(this.pitch + dyPixels * ORBIT_DEGREES_PER_PIXEL);
        this.matricesDirty = true;
    }

    /** Shift + middle-drag: move the target in the view plane. Content follows the cursor. */
    public void pan(float dxPixels, float dyPixels)
    {
        this.update();

        float scale = this.pixelsPerWorldUnit();

        if (scale <= 0.000001F)
        {
            return;
        }

        float worldX = dxPixels / scale;
        float worldY = dyPixels / scale;

        this.target.fma(-worldX, this.right);
        this.target.fma(worldY, this.up);
        this.matricesDirty = true;
    }

    /** Wheel: multiplicative dolly, clamped so one tick is always visible but never a jump. */
    public void dolly(float steps)
    {
        this.distance = clampDistance(this.distance * (float) Math.pow(0.85D, steps));
        this.matricesDirty = true;
    }

    /**
     * Wheel over a point: zoom while keeping the world point under the cursor where it is.
     *
     * <p>Zooming about the viewport centre is what makes a 3D viewport feel like a slideshow: the
     * thing you are looking at slides out of frame. The fix is one unproject before and one after,
     * on the target's own Z plane — no special case for ortho, because both projections invert.</p>
     */
    public void zoomAt(float pixelX, float pixelY, float steps)
    {
        this.update();

        Vector3f before = this.unproject(pixelX, pixelY, this.target.z);

        this.distance = clampDistance(this.distance * (float) Math.pow(0.85D, steps));
        this.matricesDirty = true;
        this.update();

        Vector3f after = this.unproject(pixelX, pixelY, this.target.z);

        this.target.add(before).sub(after);
        this.matricesDirty = true;
    }

    /** Looks at the given bounds from the current orbit direction, with a 35% margin. */
    public void frame(Vector3f min, Vector3f max)
    {
        float cx = (min.x + max.x) * 0.5F;
        float cy = (min.y + max.y) * 0.5F;
        float cz = (min.z + max.z) * 0.5F;
        float rx = Math.max(1F, Math.abs(max.x - min.x) * 0.5F);
        float ry = Math.max(1F, Math.abs(max.y - min.y) * 0.5F);

        this.target.set(cx, cy, cz);

        float tanHalf = (float) Math.tan(Math.toRadians(this.clampedFov()) * 0.5D);
        float aspect = Math.max(0.01F, this.width / (float) Math.max(1, this.height));
        float vertical = ry / tanHalf;
        float horizontal = rx / Math.max(0.0001F, tanHalf * aspect);

        this.distance = clampDistance(Math.max(vertical, horizontal) * 1.35F + Math.abs(max.z - min.z) * 0.5F);
        this.matricesDirty = true;

        /* A margin is not a proof: at an oblique angle the near corners of a large plane project
         * far wider than its centre does. Measure the actual corners and push the camera back until
         * they fit — for any yaw/pitch, which is what "frame all" has to mean. */
        for (int iteration = 0; iteration < 4; iteration++)
        {
            this.update();

            float overflow = 0F;

            for (int i = 0; i < 8; i++)
            {
                Vector3f corner = new Vector3f(
                    (i & 1) == 0 ? min.x : max.x,
                    (i & 2) == 0 ? min.y : max.y,
                    (i & 4) == 0 ? min.z : max.z
                );
                Vector2f projected = this.project(corner);

                if (projected == null)
                {
                    overflow = Math.max(overflow, 4F);

                    break;
                }

                float nx = Math.abs(projected.x / this.width * 2F - 1F);
                float ny = Math.abs(projected.y / this.height * 2F - 1F);

                overflow = Math.max(overflow, Math.max(nx, ny));
            }

            if (overflow <= 0.9F)
            {
                break;
            }

            this.distance = clampDistance(this.distance * overflow / 0.9F * 1.05F);
            this.matricesDirty = true;
        }
    }

    /** 0 front, 1 back, 2 right, 3 left, 4 top, 5 bottom. Blender's numpad 1/3/7 + ctrl. */
    public void snap(int view)
    {
        switch (view)
        {
            case 0 -> this.setPose(0F, 0F, this.distance);
            case 1 -> this.setPose(180F, 0F, this.distance);
            case 2 -> this.setPose(90F, 0F, this.distance);
            case 3 -> this.setPose(-90F, 0F, this.distance);
            case 4 -> this.setPose(0F, 89.4F, this.distance);
            case 5 -> this.setPose(0F, -89.4F, this.distance);
            default -> { }
        }
    }

    /* ------------------------------------------------------------- projection */

    /** World (comp space) to viewport pixels; null when the point is behind the camera. */
    public Vector2f project(Vector3f world)
    {
        this.update();

        Vector3f cameraSpace = new Vector3f(world);

        this.view.transformPosition(cameraSpace);

        if (cameraSpace.z > -Math.max(0.01F, this.near))
        {
            return null;
        }

        Vector3f ndc = new Vector3f(world);

        this.view.transformPosition(ndc);
        this.projection.transformProject(ndc);

        return new Vector2f(
            (ndc.x * 0.5F + 0.5F) * this.width,
            (1F - (ndc.y * 0.5F + 0.5F)) * this.height
        );
    }

    /** Viewport pixels + a point on the ray → viewport pixels at another depth. */
    public float depthOf(Vector3f world)
    {
        this.update();

        Vector3f cameraSpace = new Vector3f(world);

        this.view.transformPosition(cameraSpace);

        return cameraSpace.z;
    }

    /** Viewport pixel to the point on the plane {@code z = zPlane}, in world space. */
    public Vector3f unproject(float pixelX, float pixelY, float zPlane)
    {
        this.update();

        Vector4f near = new Vector4f(
            pixelX / Math.max(1, this.width) * 2F - 1F,
            1F - pixelY / Math.max(1, this.height) * 2F,
            -1F,
            1F
        );
        Vector4f far = new Vector4f(near.x, near.y, 1F, 1F);

        this.inverseViewProjection.transform(near);
        this.inverseViewProjection.transform(far);
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

    /** Intersection of the cursor ray with an arbitrary plane; null when parallel. */
    public Vector3f unprojectPlane(float pixelX, float pixelY, Vector3f planePoint, Vector3f planeNormal)
    {
        this.update();

        Vector4f near = new Vector4f(
            pixelX / Math.max(1, this.width) * 2F - 1F,
            1F - pixelY / Math.max(1, this.height) * 2F,
            -1F,
            1F
        );
        Vector4f far = new Vector4f(near.x, near.y, 1F, 1F);

        this.inverseViewProjection.transform(near);
        this.inverseViewProjection.transform(far);
        near.div(near.w);
        far.div(far.w);

        Vector3f origin = new Vector3f(near.x, near.y, near.z);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z);
        float denominator = direction.dot(planeNormal);

        if (Math.abs(denominator) < 0.000001F)
        {
            return null;
        }

        float t = (planePoint.x - origin.x) * planeNormal.x
            + (planePoint.y - origin.y) * planeNormal.y
            + (planePoint.z - origin.z) * planeNormal.z;
        t /= denominator;

        return origin.fma(t, direction);
    }

    /** Viewport pixels per composition pixel at the target depth. */
    public float pixelsPerWorldUnit()
    {
        float halfHeight = Math.max(0.0001F, this.distance * (float) Math.tan(Math.toRadians(this.clampedFov()) * 0.5D));

        return this.height / (halfHeight * 2F);
    }

    /** World units per viewport pixel at the target depth. */
    public float worldPerPixel()
    {
        float scale = this.pixelsPerWorldUnit();

        return scale <= 0.000001F ? 1F : 1F / scale;
    }

    /**
     * Adopts the pose of a composition camera (Blender's numpad 0).
     *
     * <p>The orbit target becomes the camera's point of interest, and the eye stays exactly where
     * the composition camera is: {@code position = target + distance · offset(yaw, pitch)}. That
     * is why the look-through view and the rendered frame agree — the same eye, the same target,
     * the same FOV, the same projection construction as {@link CompCamera}.</p>
     */
    public void lookThrough(CompCamera camera)
    {
        Vector3f eye = new Vector3f(camera.cx, camera.cy, camera.cz);
        Vector3f interest = new Vector3f(camera.tx, camera.ty, camera.tz);
        float length = eye.distance(interest);

        this.target.set(interest);
        this.distance = clampDistance(length > 0.001F ? length : Math.max(MIN_DISTANCE, camera.focal));
        this.fov = camera.fov;
        this.ortho = !camera.perspective;

        /* Derive yaw/pitch from the camera's forward vector so the orbit handle agrees with what
         * the user just looked through. */
        Vector3f f = new Vector3f(camera.forward);

        this.pitch = clampPitch((float) Math.toDegrees(Math.asin(Math.max(-1F, Math.min(1F, f.y)))));
        this.yaw = (float) Math.toDegrees(Math.atan2(-f.x, -f.z));
        this.matricesDirty = true;
    }

    private static float clampPitch(float value)
    {
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, value));
    }

    private static float clampDistance(float value)
    {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, value));
    }
}
