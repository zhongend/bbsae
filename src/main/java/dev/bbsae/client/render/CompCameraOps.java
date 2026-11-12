package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Camera creation rules and the orbit geometry shared by the editor and its tests.
 *
 * <h2>Why creation lives here</h2>
 * <p>After Effects treats a camera as an explicit object: you create one with <i>Layer → New →
 * Camera</i>, and no amount of navigating, orbiting or dragging in the composition panel will
 * conjure one for you. The old build did the opposite — entering the 3D workspace or right-dragging
 * the preview silently called {@code addLayer("camera")} — which made "look around" create content
 * and put a camera row in the timeline as a side effect.</p>
 *
 * <p>The rule is therefore one function with an explicit {@code allowCreate} bit, so the guard can be
 * unit-tested instead of living only inside a mouse handler. Only an explicit action (the
 * <b>新建摄像机</b> button, or the developer compatibility switch) passes {@code true}.</p>
 */
public final class CompCameraOps
{
    private CompCameraOps() {}

    /** True when the composition already has a camera, visible or not (a camera is a view). */
    public static boolean hasCamera(CompDocument doc, float time)
    {
        return doc != null && doc.activeCamera(time) != null;
    }

    /**
     * Returns the camera for a view gesture, or null.
     *
     * @param allowCreate {@code true} only for an explicit "new camera" action or the developer
     *                    compatibility switch; a plain drag must pass {@code false}.
     */
    public static CompLayer ensureCamera(CompDocument doc, float time, boolean allowCreate, float fov)
    {
        if (doc == null)
        {
            return null;
        }

        CompLayer camera = doc.activeCamera(time);

        if (camera == null && allowCreate)
        {
            camera = createDefault(doc, fov);
        }

        return camera;
    }

    /** Adds a camera layer with the editor's defaults and makes it the active one. */
    public static CompLayer createDefault(CompDocument doc, float fov)
    {
        if (doc == null)
        {
            return null;
        }

        CompLayer camera = defaultCamera(doc, fov);

        doc.addLayer(camera);
        doc.activeCameraId = camera.id;

        return camera;
    }

    /**
     * The camera the editor creates. Framing is deliberately neutral: centred on the composition,
     * looking straight down -Z at the plane, at the focal distance so the picture is 1:1.
     */
    public static CompLayer defaultCamera(CompDocument doc, float fov)
    {
        float degrees = Math.max(1F, Math.min(179F, fov));
        float focal = CompCamera.focalFor(degrees, Math.max(1F, doc.height));
        CompLayer camera = new CompLayer("camera", "摄像机", "camera");

        camera.x = doc.width / 2F;
        camera.y = doc.height / 2F;
        camera.width = 100F;
        camera.height = 100F;
        camera.cameraTargetX = doc.width / 2F;
        camera.cameraTargetY = doc.height / 2F;
        camera.cameraTargetZ = 0F;
        /* Distance that produces a 1:1 framing under this FOV; the UI's defaultCameraZ() uses the
         * same relation, so an explicit and an implicit camera agree. */
        camera.cameraZ = focal;
        camera.z = focal;
        camera.cameraFov = degrees;
        camera.camera3D = true;
        camera.threeD = true;
        /* A new camera starts as a plain pan/zoom view: with look-at on and the target sitting
         * exactly on the camera, the first pan would rotate the frame 180°. */
        camera.cameraLookAt = false;
        camera.cameraStyle = CompLayer.CAMERA_AE;

        return camera;
    }

    /**
     * Orbits an eye around a fixed point of interest.
     *
     * <p>AE's camera orbit keeps the point of interest still and walks the camera on a sphere
     * around it: the world appears to rotate, the subject stays put. This is a pure function so
     * the gesture math can be tested without a window; the caller writes the result into the
     * layer (and its keyframe channels).</p>
     *
     * @param eye      current camera position (comp space, Y down, Z towards the viewer)
     * @param target   point of interest
     * @param yawDeg   horizontal drag converted to degrees (positive = orbit to the right)
     * @param pitchDeg vertical drag converted to degrees (positive = camera moves up)
     * @return the new eye position, or {@code eye} unchanged when the radius is degenerate
     */
    public static Vector3f orbit(Vector3f eye, Vector3f target, float yawDeg, float pitchDeg)
    {
        Vector3f offset = new Vector3f(eye).sub(target);
        float radius = offset.length();

        if (radius < 1F)
        {
            return new Vector3f(eye);
        }

        /* Comp space has Y down, so the world up axis is -Y. */
        offset.rotate(new Quaternionf().fromAxisAngleRad(0F, -1F, 0F, (float) Math.toRadians(yawDeg)));

        Vector3f up = new Vector3f(0F, -1F, 0F);
        Vector3f right = new Vector3f(offset).cross(up);

        if (right.lengthSquared() > 0.0001F)
        {
            right.normalize();

            float elevation = (float) Math.toDegrees(Math.asin(
                Math.max(-1F, Math.min(1F, offset.y / Math.max(0.0001F, offset.length())))));
            /* Stop short of the poles: at 90° the view direction is parallel to the up axis and the
             * camera's right vector is undefined, which makes the image spin on the spot. */
            float wanted = Math.max(-85F, Math.min(85F, elevation + pitchDeg));

            offset.rotate(new Quaternionf().fromAxisAngleRad(right, (float) Math.toRadians(wanted - elevation)));
        }

        return new Vector3f(target).add(offset);
    }

    /**
     * Converts a screen-pixel drag into a dolly factor.
     *
     * <p>AE's track-Z gesture is exponential: a fixed number of pixels multiplies the distance by a
     * fixed ratio, so the dolly feels the same whether the camera is 200 or 20 000 pixels away.
     * Dragging right moves the camera closer (the frame gets bigger).</p>
     */
    public static float dollyFactor(float horizontalPixels)
    {
        return (float) Math.exp(-horizontalPixels * 0.005D);
    }
}
