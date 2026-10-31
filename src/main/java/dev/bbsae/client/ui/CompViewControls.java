package dev.bbsae.client.ui;

import dev.bbsae.client.render.CompCamera;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;

/**
 * The 3D viewport's camera gestures and its readout.
 *
 * <h2>One space for every gesture</h2>
 * <p>A camera has a position (eye) and a point it looks at (target). Every viewport gesture is one
 * of three edits to those two points, and naming them is what keeps the view honest:</p>
 * <ul>
 *   <li><b>pan</b> — move eye and target by the same delta. The view direction is unchanged, the
 *       framing simply slides. That is what Blender's Shift+MMB does.</li>
 *   <li><b>orbit</b> — keep the target still and move the eye on a sphere around it. Yaw/pitch are
 *       the free-camera fields, so the direction and the position are recomputed together instead of
 *       disagreeing (the old code rotated the eye without moving it, which is a turn, not an orbit).</li>
 *   <li><b>dolly</b> — change the distance only. In this compositor that is {@code cameraZ}, and the
 *       screen zoom is {@code focal / distance}, so a smaller distance is a closer view.</li>
 * </ul>
 *
 * <p>All three write through the layer's ordinary fields/transform, which is what the renderer and
 * the inspector already read — there is no second "view camera" to keep in sync.</p>
 */
public final class CompViewControls
{
    private CompViewControls()
    {}

    /**
     * Orbits the eye around the current target by {@code (yawDelta, pitchDelta)} degrees.
     *
     * @param yaw   yaw delta in degrees (positive = turn right)
     * @param pitch pitch delta in degrees (positive = look up, comp Y is down)
     */
    public static void orbit(CompLayer camera, float frame, float yaw, float pitch)
    {
        if (camera == null)
        {
            return;
        }

        org.joml.Vector3f target = camera.cameraTarget(frame);
        float distance = distance(camera, frame);
        float newYaw = camera.scalarAt("rotationY", camera.rotationY, frame) + yaw;
        float newPitch = Math.max(-89F, Math.min(89F, camera.scalarAt("rotationX", camera.rotationX, frame) + pitch));

        camera.cameraLookAt = false;
        camera.rotationY = newYaw;
        camera.rotationX = newPitch;

        float yawRad = (float) Math.toRadians(newYaw);
        float pitchRad = (float) Math.toRadians(newPitch);
        float dirX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dirY = (float) -Math.sin(pitchRad);
        float dirZ = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));

        /* Eye = target - forward * distance, so the point of interest stays put while the eye moves
         * on a sphere. Writing only the angles would leave the eye nailed in place. */
        float eyeX = target.x - dirX * distance;
        float eyeY = target.y - dirY * distance;
        float eyeZ = target.z - dirZ * distance;

        writePosition(camera, frame, eyeX, eyeY);
        camera.cameraZ = eyeZ;
        camera.z = eyeZ;
    }

    /** Pans eye and target together, in composition pixels. */
    public static void pan(CompLayer camera, float frame, float dx, float dy)
    {
        if (camera == null)
        {
            return;
        }

        org.joml.Vector2f eye = camera.cameraEye(frame);
        org.joml.Vector3f target = camera.cameraTarget(frame);

        writePosition(camera, frame, eye.x + dx, eye.y + dy);
        camera.cameraTargetX = target.x + dx;
        camera.cameraTargetY = target.y + dy;
    }

    /** Changes the dolly distance, clamped so the camera cannot cross the near plane. */
    public static void dolly(CompLayer camera, float frame, float factor)
    {
        if (camera == null || factor <= 0F)
        {
            return;
        }

        float distance = Math.max(1F, distance(camera, frame) * factor);
        float clamped = Math.max(camera.cameraNear + 10F, Math.min(camera.cameraFar - 10F, distance));

        camera.cameraZ = clamped;
        camera.z = clamped;
    }

    /** Restores the default framing: centred, 1:1 at the focal distance, looking straight ahead. */
    public static void reset(CompDocument doc, CompLayer camera)
    {
        if (doc == null || camera == null)
        {
            return;
        }

        float focal = CompCamera.focalFor(camera.cameraFov, doc.height);

        camera.x = doc.width / 2F;
        camera.y = doc.height / 2F;
        camera.cameraZ = focal;
        camera.z = focal;
        camera.cameraTargetX = doc.width / 2F;
        camera.cameraTargetY = doc.height / 2F;
        camera.cameraTargetZ = 0F;
        camera.rotation = 0F;
        camera.rotationX = 0F;
        camera.rotationY = 0F;
        camera.cameraLookAt = false;
        camera.camera3D = true;
        camera.threeD = true;
        camera.scaleX = 1F;
        camera.scaleY = 1F;
    }

    /** Frames a layer: aim at it and pick a distance that keeps it comfortably in view. */
    public static void frameLayer(CompDocument doc, CompLayer camera, CompLayer layer, float frame)
    {
        if (doc == null || camera == null || layer == null)
        {
            return;
        }

        camera.cameraLookAt = true;
        camera.cameraTargetX = layer.x;
        camera.cameraTargetY = layer.y;
        camera.cameraTargetZ = layer.z;
        camera.x = layer.x;
        camera.y = layer.y;

        float extent = Math.max(layer.width, layer.height);
        float focal = CompCamera.focalFor(camera.cameraFov, doc.height);
        float distance = Math.max(200F, Math.max(extent * 1.4F, focal));

        camera.cameraZ = distance;
        camera.z = distance;
    }

    /** Distance from the comp plane, honouring the same fallbacks as {@code CompCamera.of}. */
    public static float distance(CompLayer camera, float frame)
    {
        float z = camera.scalarAt("cameraZ", camera.cameraZ, frame);

        if (Math.abs(z) < 0.001F)
        {
            z = camera.z;
        }

        return Math.max(1F, Math.abs(z));
    }

    private static void writePosition(CompLayer camera, float frame, float x, float y)
    {
        if (camera.hasAnyTransformKey())
        {
            camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_X, frame, x);
            camera.insertAxisKey(dev.bbsae.comp.CompCurves.AXIS_Y, frame, y);
        }
        else
        {
            camera.x = x;
            camera.y = y;
        }
    }

    /** One-line HUD: where the camera is, what it looks at, and how much of the frame it sees. */
    public static String describe(CompDocument doc, float time, float frame)
    {
        CompCamera camera = CompCamera.of(doc, time, frame);

        if (!camera.hasCamera)
        {
            return "无摄像机（默认 1:1 视图）";
        }

        return String.format(java.util.Locale.ROOT,
            "位置 %.0f, %.0f, %.0f · 目标 %.0f, %.0f · FOV %.0f° · 距离 %.0f · 缩放 %.0f%%",
            camera.cx, camera.cy, camera.cz, camera.tx, camera.ty, camera.fov,
            camera.distance, camera.zoom * 100F);
    }
}
