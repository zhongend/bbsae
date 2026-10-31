package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor viewport camera contract.
 *
 * <p>These assertions are what makes "3D space" trustworthy: the free view must be a real
 * projection (not a hand-rolled screen offset), it must agree with the composition camera when it
 * looks through it, and every navigation gesture must be reversible and cursor-anchored.</p>
 */
public class CompViewportCameraTest
{
    private static final float EPS = 0.05F;

    private static CompViewportCamera camera()
    {
        CompViewportCamera camera = new CompViewportCamera();

        camera.setViewport(1920, 1080);
        camera.setTarget(960F, 540F, 0F);
        camera.setPose(0F, 0F, 1000F);
        camera.update();

        return camera;
    }

    @Test
    public void eyeSitsInFrontOfTheTargetLookingDownNegativeZ()
    {
        CompViewportCamera camera = camera();

        assertEquals(960F, camera.position.x, EPS);
        assertEquals(540F, camera.position.y, EPS);
        assertEquals(1000F, camera.position.z, EPS);

        assertEquals(0F, camera.forward.x, EPS);
        assertEquals(0F, camera.forward.y, EPS);
        assertEquals(-1F, camera.forward.z, EPS);

        /* Screen axes in composition space: right is +X, screen-up is -Y. */
        assertEquals(1F, camera.right.x, EPS);
        assertEquals(-1F, camera.up.y, EPS);
    }

    @Test
    public void orbitMovesTheEyeOnASphereAroundTheTarget()
    {
        CompViewportCamera camera = camera();
        float before = new Vector3f(camera.target).distance(camera.position);

        camera.orbit(120F, 60F);
        camera.update();

        float after = new Vector3f(camera.target).distance(camera.position);

        assertEquals(before, after, 0.01F, "orbit must preserve the distance to the target");
        /* Grab-the-scene convention (Blender): dragging right pulls the scene right, which means
         * the eye travels to -X. */
        assertTrue(camera.position.x < 960F, "dragging right moves the eye to -X");
        assertTrue(camera.position.y < 540F, "dragging down raises the eye (comp Y is down)");
    }

    @Test
    public void pitchIsClampedSoTheViewNeverFlips()
    {
        CompViewportCamera camera = camera();

        camera.orbit(0F, 100000F);
        assertTrue(camera.pitch <= CompViewportCamera.MAX_PITCH);

        camera.orbit(0F, -200000F);
        assertTrue(camera.pitch >= CompViewportCamera.MIN_PITCH);

        camera.update();

        /* At the pole the basis must still be orthonormal and the forward vector must not be NaN. */
        assertTrue(Float.isFinite(camera.forward.x) && Float.isFinite(camera.forward.y) && Float.isFinite(camera.forward.z));
        assertEquals(1F, camera.right.length(), 0.01F);
        assertEquals(1F, camera.up.length(), 0.01F);
        assertEquals(0F, camera.right.dot(camera.forward), 0.01F);
    }

    @Test
    public void panKeepsContentUnderTheCursor()
    {
        CompViewportCamera camera = camera();
        Vector2f center = camera.project(new Vector3f(960F, 540F, 0F));

        camera.pan(100F, -50F);
        camera.update();

        Vector2f moved = camera.project(new Vector3f(960F, 540F, 0F));

        assertNotNull(moved);
        assertEquals(center.x + 100F, moved.x, 0.5F);
        assertEquals(center.y - 50F, moved.y, 0.5F);
    }

    @Test
    public void zoomAtKeepsThePointUnderTheCursor()
    {
        CompViewportCamera camera = camera();
        float px = 700F;
        float py = 300F;
        Vector3f before = camera.unproject(px, py, 0F);

        camera.zoomAt(px, py, 3F);
        camera.update();

        Vector3f after = camera.unproject(px, py, 0F);

        assertEquals(before.x, after.x, 0.5F);
        assertEquals(before.y, after.y, 0.5F);
        assertTrue(camera.distance < 1000F, "wheel forward must dolly in");
    }

    @Test
    public void orthographicKeepsTheScaleAtTheTargetPlane()
    {
        CompViewportCamera perspective = camera();
        CompViewportCamera orthographic = camera();

        orthographic.ortho = true;
        orthographic.update();

        Vector3f point = new Vector3f(600F, 200F, 0F);
        Vector2f a = perspective.project(point);
        Vector2f b = orthographic.project(point);

        assertEquals(a.x, b.x, 0.1F);
        assertEquals(a.y, b.y, 0.1F);

        /* Ortho: depth must not change the projected position any more. */
        Vector2f near = orthographic.project(new Vector3f(600F, 200F, -800F));

        assertEquals(b.x, near.x, 0.1F);
        assertEquals(b.y, near.y, 0.1F);
    }

    @Test
    public void unprojectIsTheInverseOfProject()
    {
        CompViewportCamera camera = camera();

        camera.orbit(37F, 21F);
        camera.dolly(2F);
        camera.update();

        Vector3f point = new Vector3f(300F, 800F, -120F);
        Vector2f screen = camera.project(point);

        assertNotNull(screen);

        Vector3f back = camera.unproject(screen.x, screen.y, point.z);

        assertEquals(point.x, back.x, 0.05F);
        assertEquals(point.y, back.y, 0.05F);
        assertEquals(point.z, back.z, 0.001F);
    }

    @Test
    public void frameFitsTheWholeComposition()
    {
        CompViewportCamera camera = camera();

        camera.setPose(35F, 22F, 100F);
        camera.frame(new Vector3f(0F, 0F, 0F), new Vector3f(1920F, 1080F, 0F));
        camera.update();

        for (int i = 0; i < 4; i++)
        {
            float x = (i == 1 || i == 2) ? 1920F : 0F;
            float y = i >= 2 ? 1080F : 0F;
            Vector2f p = camera.project(new Vector3f(x, y, 0F));

            assertNotNull(p, "framed corners must be in front of the camera");
            assertTrue(p.x > 0F && p.x < camera.width, "framed corner x out of view: " + p.x);
            assertTrue(p.y > 0F && p.y < camera.height, "framed corner y out of view: " + p.y);
        }
    }

    @Test
    public void numpadViewsLookAlongTheCompositionAxes()
    {
        CompViewportCamera camera = camera();

        camera.snap(0);
        camera.update();
        assertEquals(0F, camera.forward.x, EPS);
        assertEquals(0F, camera.forward.y, EPS);
        assertEquals(-1F, camera.forward.z, EPS);

        camera.snap(2);
        camera.update();
        assertEquals(-1F, camera.forward.x, EPS);

        camera.snap(4);
        camera.update();
        assertEquals(1F, camera.forward.y, 0.02F, "top view looks down (+Y in comp space)");
    }

    @Test
    public void lookThroughMatchesTheCompositionCamera()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 60F;
        doc.duration = 10F;

        CompLayer layer = new CompLayer("cam", "Camera", "camera");

        layer.threeD = true;
        layer.camera3D = true;
        layer.x = 960F;
        layer.y = 540F;
        layer.cameraZ = CompCamera.focalFor(50F, doc.height);
        layer.z = layer.cameraZ;
        layer.cameraTargetX = 960F;
        layer.cameraTargetY = 540F;
        layer.cameraTargetZ = 0F;
        layer.cameraFov = 50F;
        layer.out = doc.duration;
        doc.layers.add(layer);
        doc.activeCameraId = layer.id;

        CompProject project = new CompProject();

        project.comps.add(doc);

        CompCamera composition = CompCamera.of(doc, 0F, 0F);
        CompViewportCamera viewport = new CompViewportCamera();

        viewport.setViewport(1920, 1080);
        viewport.lookThrough(composition);
        viewport.update();

        Vector3f[] points = {
            new Vector3f(960F, 540F, 0F),
            new Vector3f(0F, 0F, 0F),
            new Vector3f(1920F, 1080F, 0F),
            new Vector3f(400F, 900F, -300F)
        };

        for (Vector3f point : points)
        {
            Vector2f expected = composition.project(point, doc.width, doc.height);
            Vector2f actual = viewport.project(point);

            assertNotNull(expected);
            assertNotNull(actual);
            assertEquals(expected.x, actual.x, 0.1F, "look-through x must equal the rendered frame");
            assertEquals(expected.y, actual.y, 0.1F, "look-through y must equal the rendered frame");
        }
    }
}
