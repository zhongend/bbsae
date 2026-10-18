package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math checks for the 3D camera. A 50° camera placed at the focal distance must map the z=0
 * plane 1:1 onto the composition; moving a layer towards the camera must magnify it around the
 * camera target, and a 2D layer must not be affected by the 3D camera at all.
 */
public class Camera3DTest
{
    private static final float EPS = 0.01F;

    private static CompProject project(CompDocument doc)
    {
        CompProject project = new CompProject();

        project.comps.add(doc);

        return project;
    }

    private static CompDocument document()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 60F;
        doc.duration = 10F;

        return doc;
    }

    private static CompLayer camera(CompDocument doc)
    {
        CompLayer camera = new CompLayer("camera3d", "Camera", "camera");

        camera.threeD = true;
        camera.camera3D = true;
        camera.x = doc.width * 0.5F;
        camera.y = doc.height * 0.5F;
        camera.z = (doc.height * 0.5F) / (float) Math.tan(Math.toRadians(25D));
        camera.cameraZ = camera.z;
        camera.cameraTargetX = doc.width * 0.5F;
        camera.cameraTargetY = doc.height * 0.5F;
        camera.cameraTargetZ = 0F;
        camera.cameraFov = 50F;

        return camera;
    }

    private static CompLayer card(CompDocument doc)
    {
        CompLayer layer = new CompLayer("card", "Card", "solid");

        layer.threeD = true;
        layer.x = doc.width * 0.5F;
        layer.y = doc.height * 0.5F;
        layer.z = 0F;
        layer.width = 400F;
        layer.height = 200F;

        return layer;
    }

    private static Vector2f project(CompProject project, CompDocument doc, CompLayer layer, float x, float y)
    {
        CompRenderContext context = new CompRenderContext(project, doc, 0F, null, null, null);

        CompRenderer.prepare3D(context, true);

        Matrix4f world = CompRenderer.world3D(doc, layer, context);
        Vector2f point = CompRenderer.project3D(context, world, x, y);

        assertNotNull(point);

        return point;
    }

    @Test
    public void zPlaneMapsOneToOneAtDefaultCameraDistance()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer card = card(doc);
        Vector2f topLeft = project(project, doc, card, -200F, -100F);
        Vector2f bottomRight = project(project, doc, card, 200F, 100F);

        assertEquals(760F, topLeft.x, EPS, "top-left x");
        assertEquals(440F, topLeft.y, EPS, "top-left y");
        assertEquals(1160F, bottomRight.x, EPS, "bottom-right x");
        assertEquals(640F, bottomRight.y, EPS, "bottom-right y");
    }

    @Test
    public void movingTowardsTheCameraMagnifies()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer card = card(doc);

        card.z = 0F;
        Vector2f nearZero = project(project, doc, card, 200F, 100F);
        card.z = 300F;
        Vector2f nearer = project(project, doc, card, 200F, 100F);

        float distance0 = nearZero.x - doc.width * 0.5F;
        float distance1 = nearer.x - doc.width * 0.5F;

        assertTrue(distance1 > distance0 + 1F, "positive z must move towards the camera and magnify");
    }

    @Test
    public void twoDLayerIgnoresThe3DCamera()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer flat = new CompLayer("flat", "Flat", "solid");

        flat.x = 960F;
        flat.y = 540F;
        flat.width = 200F;
        flat.height = 100F;

        CompRenderContext context = new CompRenderContext(project, doc, 0F, null, null, null);

        CompRenderer.prepare3D(context, true);

        Matrix4f world = CompRenderer.worldMatrix(doc, flat, context, new HashSet<>());
        org.joml.Vector3f point = new org.joml.Vector3f(0F, 0F, 0F);

        world.transformPosition(point);

        assertEquals(960F, point.x, EPS);
        assertEquals(540F, point.y, EPS);
    }

    /**
     * A free camera (look-at off) must actually use its own X/Y rotation. Before this was wired
     * up, rotating a camera in the inspector changed nothing because setup3D always looked at
     * the point of interest.
     */
    @Test
    public void freeCameraYawRotatesTheView()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.cameraLookAt = false;
        camera.rotationY = 0F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer card = card(doc);
        Vector2f straight = project(project, doc, card, 0F, 0F);

        camera.rotationY = 30F;

        Vector2f turned = project(project, doc, card, 0F, 0F);

        assertTrue(Math.abs(turned.x - straight.x) > 10F, "yaw must move the projected centre");
        assertTrue(Math.abs(turned.y - straight.y) < 1F, "pure yaw must not move the centre vertically");
    }

    @Test
    public void freeCameraPitchRotatesTheView()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.cameraLookAt = false;
        camera.rotationX = 0F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer card = card(doc);
        Vector2f straight = project(project, doc, card, 0F, 0F);

        camera.rotationX = 25F;

        Vector2f turned = project(project, doc, card, 0F, 0F);

        assertTrue(Math.abs(turned.y - straight.y) > 10F, "pitch must move the projected centre vertically");
    }
}
