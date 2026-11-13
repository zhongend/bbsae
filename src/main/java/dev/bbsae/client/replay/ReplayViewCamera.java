package dev.bbsae.client.replay;

import dev.bbsae.client.render.CompViewportCamera;
import mchorse.bbs_mod.camera.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Builds the two cameras a replay actor pass needs from bbsae's own viewport camera.
 *
 * <p>BBS has two camera types and they are not interchangeable:</p>
 * <ul>
 *   <li>{@link Camera} (BBS) carries the projection, the view matrix and the eye as a
 *       {@code Vector3d}; the gizmo drag math and the gizmo's own frame work in that camera.</li>
 *   <li>{@code net.minecraft.client.render.Camera} is what {@code FilmEntityRenderer} and the form
 *       renderers read (actor origin, billboard orientation); its eye is a {@code Vec3d} and its
 *       rotation is Minecraft's yaw/pitch convention.</li>
 * </ul>
 *
 * <p>Both are built from the same {@link CompViewportCamera}: the rotation is {@code
 * viewport.view · linearFromWorld} (translation stripped, because the actor pass receives
 * camera-relative geometry and BBS' {@code Camera.view} is rotation-only) and the eye is mapped
 * back to world coordinates, so the actor pass and the gizmo cannot disagree about where the
 * camera is.</p>
 */
public final class ReplayViewCamera
{
    private ReplayViewCamera()
    {}

    /** The BBS camera: what gizmo dragging and the gizmo frame read. */
    public static Camera create(CompViewportCamera viewport, ReplaySceneSpace space)
    {
        viewport.update();

        Camera camera = new Camera();

        camera.projection.set(viewport.projection);

        /* BBS' Camera.view is a rotation-only matrix; Camera.position carries the eye. The UI gizmo
         * pipeline depends on that split: `renderInterface` projects a captured view-space matrix
         * with the projection alone, and `computeWorldOrigin` undoes the rotation and adds the
         * position. The actors themselves are rendered from camera-relative geometry, so the
         * rotation must be the block→view rotation only — no anchor translation. */
        camera.view.set(rotationOnlyView(viewport, space));

        Vector3f eye = space.toWorld(viewport.position);

        camera.position.set(eye.x, eye.y, eye.z);

        float blocksPerPixel = space.blocksPerPixel();

        camera.fov = (float) Math.toRadians(viewport.clampedFov());
        camera.near = Math.max(0.001F, viewport.near * blocksPerPixel);
        camera.far = Math.max(camera.near + 1F, viewport.far * blocksPerPixel);

        camera.rotation.set((float) Math.toRadians(viewport.pitch + ReplayCalibration.pitchOffset()),
            (float) Math.toRadians(viewport.yaw + ReplayCalibration.yawOffset()),
            (float) Math.toRadians(viewport.roll));

        return camera;
    }

    /**
     * Block-relative world → view rotation for the actor pass and the gizmo stack: the viewport's
     * rotation composed with the scene's linear mapping, translation stripped.
     */
    public static Matrix4f rotationOnlyView(CompViewportCamera viewport, ReplaySceneSpace space)
    {
        viewport.update();

        Matrix4f view = new Matrix4f(viewport.view);

        view.m30(0F).m31(0F).m32(0F);

        return view.mul(space.linearFromWorld());
    }

    /**
     * The Minecraft camera: what {@code FilmEntityRenderer} reads for the actor origin and the
     * form renderers read for billboards. Yaw is converted from our convention (0 looks down -Z)
     * to Minecraft's (0 looks down +Z), pitch has the same "positive looks down" sign.
     *
     * <p>{@code Camera.setPos}/{@code setRotation} are protected, so the placement goes through a
     * tiny subclass instead of reflection.</p>
     */
    public static net.minecraft.client.render.Camera actorCamera(CompViewportCamera viewport, ReplaySceneSpace space)
    {
        viewport.update();

        ActorCamera camera = new ActorCamera();
        Vector3f eye = space.toWorld(viewport.position);

        camera.place(eye.x, eye.y, eye.z, viewport.yaw + ReplayCalibration.yawOffset(), viewport.pitch + ReplayCalibration.pitchOffset());

        return camera;
    }

    private static final class ActorCamera extends net.minecraft.client.render.Camera
    {
        void place(double x, double y, double z, float yaw, float pitch)
        {
            this.setPos(x, y, z);
            this.setRotation(yaw, pitch);
        }
    }

    /** The camera-relative block→view rotation a film actor pass renders with. */
    public static Matrix4f view(CompViewportCamera viewport, ReplaySceneSpace space)
    {
        return rotationOnlyView(viewport, space);
    }
}
