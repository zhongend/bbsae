package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camera contract.
 *
 * <p>The compositor has exactly one camera. These tests pin the invariants the renderer relies on,
 * in particular the one the old build could not guarantee: at z = 0 the perspective camera and the
 * 2D view must agree pixel for pixel, so a layer cannot jump when it is switched to 3D.</p>
 */
public class Camera3DTest
{
    private static final float EPS = 0.02F;

    private static CompDocument document()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 60F;
        doc.duration = 10F;

        return doc;
    }

    private static CompProject project(CompDocument doc)
    {
        CompProject project = new CompProject();

        project.comps.add(doc);

        return project;
    }

    private static CompLayer camera(CompDocument doc)
    {
        CompLayer camera = new CompLayer("camera3d", "Camera", "camera");

        camera.threeD = true;
        camera.camera3D = true;
        camera.x = doc.width * 0.5F;
        camera.y = doc.height * 0.5F;
        camera.cameraZ = CompCamera.focalFor(50F, doc.height);
        camera.z = camera.cameraZ;
        camera.cameraTargetX = doc.width * 0.5F;
        camera.cameraTargetY = doc.height * 0.5F;
        camera.cameraTargetZ = 0F;
        camera.cameraFov = 50F;
        camera.out = doc.duration;

        return camera;
    }

    private static CompRenderContext context(CompProject project, CompDocument doc)
    {
        return new CompRenderContext(project, doc, 0F, null, null, null);
    }

    private static CompLayer card(CompDocument doc, boolean threeD)
    {
        CompLayer layer = new CompLayer("card", "Card", "solid");

        layer.threeD = threeD;
        layer.x = doc.width * 0.5F;
        layer.y = doc.height * 0.5F;
        layer.width = 400F;
        layer.height = 200F;

        return layer;
    }

    @Test
    public void zPlaneMapsOneToOneAtTheDefaultCameraDistance()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer card = card(doc, true);

        Vector2f topLeft = CompScene.project(ctx, card, -200F, -100F, 0F);
        Vector2f bottomRight = CompScene.project(ctx, card, 200F, 100F, 0F);

        assertNotNull(topLeft);
        assertEquals(760F, topLeft.x, EPS, "top-left x");
        assertEquals(440F, topLeft.y, EPS, "top-left y");
        assertEquals(1160F, bottomRight.x, EPS, "bottom-right x");
        assertEquals(640F, bottomRight.y, EPS, "bottom-right y");
    }

    /** The invariant: 2D and 3D framing agree at z = 0, to the pixel. */
    @Test
    public void perspectiveAnd2DViewAgreeAtZeroDepth()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompCamera view = CompScene.camera(ctx);
        CompLayer flat = card(doc, false);
        CompLayer deep = card(doc, true);

        for (float[] point : new float[][] {{-200F, -100F}, {200F, -100F}, {200F, 100F}, {-200F, 100F}, {0F, 0F}})
        {
            Vector2f a = CompScene.project(ctx, flat, point[0], point[1], 0F);
            Vector2f b = CompScene.project(ctx, deep, point[0], point[1], 0F);

            assertEquals(a.x, b.x, 0.1F, "2D/3D x at " + point[0] + "," + point[1]);
            assertEquals(a.y, b.y, 0.1F, "2D/3D y at " + point[0] + "," + point[1]);
        }

        assertTrue(view.perspective);
        assertEquals(1F, view.zoom, 0.001F, "focal-distance camera must be 1:1");
    }

    @Test
    public void positiveZMovesTowardsTheCameraAndMagnifies()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer card = card(doc, true);

        card.z = 0F;
        Vector2f flat = CompScene.project(ctx, card, 200F, 100F, 0F);

        CompRenderContext ctx2 = context(project, doc);

        card.z = 300F;
        Vector2f near = CompScene.project(ctx2, card, 200F, 100F, 0F);

        float distanceFlat = flat.x - doc.width * 0.5F;
        float distanceNear = near.x - doc.width * 0.5F;

        assertTrue(distanceNear > distanceFlat + 1F, "positive z must magnify");
    }

    @Test
    public void panAndZoomDriveBothViews()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.x = doc.width * 0.5F + 100F;
        camera.cameraZ /= 2F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompCamera view = CompScene.camera(ctx);

        assertEquals(2F, view.zoom, 0.001F, "half the distance doubles the zoom");

        CompLayer flat = card(doc, false);
        Vector2f centre = CompScene.project(ctx, flat, 0F, 0F, 0F);

        /* Camera moved right by 100 at 2x zoom: the layer centre must land 200px left of centre. */
        assertEquals(doc.width * 0.5F - 200F, centre.x, EPS);
        assertEquals(doc.height * 0.5F, centre.y, EPS);
    }

    @Test
    public void rollTurnsTheWorldTheOtherWay()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.rotation = 90F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer flat = card(doc, false);
        Vector2f centre = CompScene.project(ctx, flat, 0F, 0F, 0F);

        assertEquals(doc.width * 0.5F, centre.x, EPS);
        assertEquals(doc.height * 0.5F, centre.y, EPS);

        /* Roll the camera 90° clockwise: the world appears to turn counter-clockwise, so a point
         * that was at 3 o'clock moves to 12 o'clock — above the frame centre. */
        Vector2f right = CompScene.project(ctx, flat, 100F, 0F, 0F);

        assertEquals(doc.width * 0.5F, right.x, 0.1F);
        assertEquals(doc.height * 0.5F - 100F, right.y, 0.1F);
    }

    @Test
    public void freeCameraYawRotatesTheView()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.cameraLookAt = false;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer card = card(doc, true);

        CompRenderContext straightCtx = context(project, doc);
        Vector2f straight = CompScene.project(straightCtx, card, 0F, 0F, 0F);

        camera.rotationY = 30F;

        CompRenderContext turnedCtx = context(project, doc);
        Vector2f turned = CompScene.project(turnedCtx, card, 0F, 0F, 0F);

        assertNotNull(turned);
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
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompLayer card = card(doc, true);

        CompRenderContext straightCtx = context(project, doc);
        Vector2f straight = CompScene.project(straightCtx, card, 0F, 0F, 0F);

        camera.rotationX = 25F;

        CompRenderContext turnedCtx = context(project, doc);
        Vector2f turned = CompScene.project(turnedCtx, card, 0F, 0F, 0F);

        assertNotNull(turned);
        assertTrue(Math.abs(turned.y - straight.y) > 10F, "pitch must move the projected centre vertically");
    }

    /**
     * A 2D layer is screen-attached: it follows pan / zoom / roll, but a tilted camera must not
     * move it (it has no depth to project).
     */
    @Test
    public void twoDLayerFollowsPanZoomButIgnoresTilt()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.rotationX = 25F;
        camera.rotationY = 20F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext tiltedCtx = context(project, doc);
        CompLayer flat = card(doc, false);
        Vector2f tilted = CompScene.project(tiltedCtx, flat, 0F, 0F, 0F);

        assertEquals(doc.width * 0.5F, tilted.x, 0.1F, "tilt must not move a 2D layer");
        assertEquals(doc.height * 0.5F, tilted.y, 0.1F, "tilt must not move a 2D layer");

        camera.rotationX = 0F;
        camera.rotationY = 0F;
        camera.x += 200F;

        CompRenderContext pannedCtx = context(project, doc);
        Vector2f panned = CompScene.project(pannedCtx, flat, 0F, 0F, 0F);

        assertEquals(doc.width * 0.5F - 200F, panned.x, 0.1F, "pan must move a 2D layer");
    }

    @Test
    public void orthographicCameraKeepsSizeAndSortsByDepth()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.camera3D = false;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer card = card(doc, true);

        card.z = 0F;
        Vector2f atZero = CompScene.project(ctx, card, 200F, 100F, 0F);

        CompRenderContext ctx2 = context(project, doc);

        card.z = 500F;
        Vector2f far = CompScene.project(ctx2, card, 200F, 100F, 0F);

        assertEquals(atZero.x, far.x, 0.1F, "orthographic camera must not scale with z");
        assertEquals(atZero.y, far.y, 0.1F);

        CompRenderContext ctx3 = context(project, doc);
        float farDepth = CompScene.depth(ctx3, card);

        card.z = 0F;
        CompRenderContext ctx4 = context(project, doc);

        assertTrue(farDepth > CompScene.depth(ctx4, card), "larger z must sort as closer");
    }

    @Test
    public void sceneHierarchyMatchesTheParentComposition()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext ctx = context(project, doc);

        CompLayer parent = new CompLayer("parent", "Parent", "null");
        CompLayer child = new CompLayer("child", "Child", "solid");

        parent.x = 300F;
        parent.y = 200F;
        parent.rotation = 40F;
        child.x = 100F;
        child.y = 0F;
        child.parentId = parent.id;

        doc.layers.add(parent);
        doc.layers.add(child);

        Vector2f projected = CompScene.project(ctx, child, 0F, 0F, 0F);
        float radians = (float) Math.toRadians(40F);
        float expectedX = 300F + 100F * (float) Math.cos(radians);
        float expectedY = 200F + 100F * (float) Math.sin(radians);

        assertEquals(expectedX, projected.x, 0.05F);
        assertEquals(expectedY, projected.y, 0.05F);
    }
}
