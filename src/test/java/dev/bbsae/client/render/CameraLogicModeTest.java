package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two 3D layer logics, and the rule that a camera is never created by accident.
 *
 * <h2>AE logic</h2>
 * <p>A layer follows the camera only when its own 3D switch is on. A flat 2D layer is attached to
 * the composition rectangle: tilting or dollying the camera must not move it, exactly like After
 * Effects. The legacy switch restores the pre-0.91 behaviour for old files, where any spatial frame
 * projected every layer.</p>
 *
 * <h2>Camera creation</h2>
 * <p>Entering a view, orbiting, or dragging the preview must never add a camera layer; only an
 * explicit create action may. {@link CompCameraOps#ensureCamera} is the single guard for that.</p>
 */
class CameraLogicModeTest
{
    private static CompProject project(boolean legacy)
    {
        CompProject project = new CompProject();

        project.settings = new CompProject.CompSettings();
        project.settings.legacy3DLogic = legacy;

        return project;
    }

    private static CompDocument comp()
    {
        CompDocument comp = new CompDocument();

        comp.width = 1920;
        comp.height = 1080;
        comp.fps = 60F;
        comp.duration = 10F;

        return comp;
    }

    /** A camera that is genuinely tilted, so {@code spatial()} is true for the frame. */
    private static CompLayer tiltedCamera()
    {
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.threeD = true;
        camera.camera3D = true;
        camera.x = 960F;
        camera.y = 540F;
        camera.cameraZ = 2000F;
        camera.z = 2000F;
        camera.cameraTargetX = 1400F;
        camera.cameraTargetY = 700F;
        camera.cameraTargetZ = 0F;
        camera.cameraLookAt = true;

        return camera;
    }

    private static CompLayer flatLayer()
    {
        CompLayer layer = new CompLayer("flat", "Flat", "solid");

        layer.x = 960F;
        layer.y = 540F;
        layer.width = 400F;
        layer.height = 200F;

        return layer;
    }

    private static CompLayer threeDLayer()
    {
        CompLayer layer = new CompLayer("three", "Three", "image");

        layer.threeD = true;
        layer.x = 960F;
        layer.y = 540F;
        layer.z = 200F;
        layer.width = 400F;
        layer.height = 200F;

        return layer;
    }

    private static CompRenderContext context(CompProject project, CompDocument comp)
    {
        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    @Test
    void aeLogicKeepsFlatLayersScreenAttachedUnderATiltedCamera()
    {
        CompProject project = project(false);
        CompDocument comp = comp();
        CompLayer camera = tiltedCamera();
        CompLayer flat = flatLayer();

        comp.layers.add(camera);
        comp.layers.add(flat);
        comp.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, comp);

        /* The frame is spatial (the camera is tilted), but the flat layer must not care. */
        assertTrue(CompScene.spatial(ctx), "baseline: a tilted camera makes the frame spatial");
        assertFalse(CompScene.usePerspective(ctx, flat), "AE logic: a 2D layer ignores the camera");
        assertFalse(CompScene.legacy3D(ctx), "AE logic must be the default");

        /* Projection of a 2D layer is the affine 2D view: its projected position equals the world
         * matrix followed by view2D, i.e. no perspective and no camera tilt involved. */
        Vector3f expected = new Vector3f(120F, 40F, 0F);

        CompScene.world(ctx, flat).transformPosition(expected);
        CompScene.camera(ctx).view2D.transformPosition(expected);

        Vector2f actual = CompScene.project(ctx, flat, 120F, 40F, 0F);

        assertNotNull(actual);
        assertEquals(expected.x, actual.x, 0.01F);
        assertEquals(expected.y, actual.y, 0.01F);
    }

    @Test
    void legacyLogicProjectsEveryLayerUnderASpatialCamera()
    {
        CompProject project = project(true);
        CompDocument comp = comp();
        CompLayer camera = tiltedCamera();
        CompLayer flat = flatLayer();

        comp.layers.add(camera);
        comp.layers.add(flat);
        comp.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, comp);

        assertTrue(CompScene.legacy3D(ctx));
        assertTrue(CompScene.spatial(ctx));
        assertTrue(CompScene.usePerspective(ctx, flat),
            "legacy logic: any layer is projected once the frame is spatial");

        /* The projection handed to the content renderer is now the camera's perspective, not the
         * orthographic composition space. */
        assertEquals(CompScene.camera(ctx).projection, CompScene.projection(ctx, flat));
    }

    @Test
    void threeDLayersFollowTheCameraInBothLogics()
    {
        for (boolean legacy : new boolean[] {false, true})
        {
            CompProject project = project(legacy);
            CompDocument comp = comp();
            CompLayer camera = tiltedCamera();
            CompLayer layer = threeDLayer();

            comp.layers.add(camera);
            comp.layers.add(layer);
            comp.activeCameraId = camera.id;

            CompRenderContext ctx = context(project, comp);

            assertTrue(CompScene.usePerspective(ctx, layer), "3D layer must follow the camera (legacy=" + legacy + ")");
        }
    }

    @Test
    void aTwoDLayerAtZZeroDoesNotMakeTheFrameSpatialByItself()
    {
        CompProject project = project(false);
        CompDocument comp = comp();
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        /* A straight-on camera and a flat comp: the normal 2D editing case. */
        camera.threeD = true;
        camera.camera3D = true;
        camera.x = 960F;
        camera.y = 540F;
        camera.cameraZ = 1200F;
        camera.z = 1200F;
        camera.cameraTargetX = 960F;
        camera.cameraTargetY = 540F;
        camera.cameraTargetZ = 0F;
        camera.cameraLookAt = true;

        comp.layers.add(camera);
        comp.layers.add(flatLayer());
        comp.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, comp);

        assertFalse(CompScene.spatial(ctx), "no tilt and no depth: the 2D path is exact");
    }

    @Test
    void viewGesturesNeverCreateACamera()
    {
        CompDocument comp = comp();
        int before = comp.layers.size();

        assertNull(CompCameraOps.ensureCamera(comp, 0F, false, 50F),
            "a drag/gesture with allowCreate=false must not create a camera");
        assertEquals(before, comp.layers.size(), "the composition must be untouched");
        assertFalse(CompCameraOps.hasCamera(comp, 0F));
    }

    @Test
    void explicitCreateAddsExactlyOneCameraAndActivatesIt()
    {
        CompDocument comp = comp();

        CompLayer camera = CompCameraOps.ensureCamera(comp, 0F, true, 50F);

        assertNotNull(camera);
        assertEquals(1, comp.layers.size());
        assertTrue(CompCameraOps.hasCamera(comp, 0F));
        assertEquals(camera.id, comp.activeCameraId);
        assertTrue(camera.threeD && camera.camera3D);
        assertEquals("camera", camera.type);
        assertEquals(50F, camera.cameraFov, 0.001F);
        assertEquals(CompCamera.focalFor(50F, comp.height), camera.cameraZ, 0.5F);

        /* A second explicit request reuses the existing camera: the UI button must never stack
         * cameras by accident. */
        assertTrue(CompCameraOps.ensureCamera(comp, 0F, true, 50F) == camera);
        assertEquals(1, comp.layers.size());
    }

    @Test
    void orbitKeepsRadiusAndTargetFixed()
    {
        Vector3f target = new Vector3f(960F, 540F, 0F);
        Vector3f eye = new Vector3f(960F, 540F, 2000F);

        Vector3f moved = CompCameraOps.orbit(eye, target, 35F, 10F);

        assertEquals(2000F, moved.distance(target), 0.5F, "AE orbit walks the eye on a sphere");
        assertTrue(moved.distance(eye) > 1F, "the eye actually moved");
    }

    @Test
    void orbitClampsPitchShortOfThePole()
    {
        Vector3f target = new Vector3f(0F, 0F, 0F);
        Vector3f eye = new Vector3f(0F, 0F, 1000F);

        Vector3f moved = CompCameraOps.orbit(eye, target, 0F, 10000F);

        float elevation = (float) Math.toDegrees(Math.asin(moved.y / moved.length()));

        assertTrue(elevation <= 85.001F && elevation >= -85.001F,
            "elevation must stay below the pole, was " + elevation);
    }

    @Test
    void dollyFactorIsMonotonicAndOneAtZero()
    {
        assertEquals(1F, CompCameraOps.dollyFactor(0F), 0.0001F);
        assertTrue(CompCameraOps.dollyFactor(100F) < 1F, "drag right = closer");
        assertTrue(CompCameraOps.dollyFactor(-100F) > 1F, "drag left = farther");
    }
}
