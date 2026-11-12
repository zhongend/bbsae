package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    public void bbsStyleCameraIgnoresLookAtAndFliesWithYawPitch()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.cameraStyle = CompLayer.CAMERA_BBS;
        camera.cameraLookAt = true;
        camera.rotationY = 90F;
        camera.rotationX = 0F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompCamera view = CompCamera.of(doc, 0F, 0F);

        assertTrue(view.hasCamera);
        assertTrue(!view.lookAt, "BBS camera must be free, never target-locked");
        assertTrue(view.forward.x > 0.99F, "yaw 90 must look along +X, got " + view.forward);
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

    /**
     * AE logic: a 2D layer is attached to the composition rectangle, not to the camera. A tilted
     * camera must not move it, and its model matrix must be its plain world matrix (no view2D).
     */
    @Test
    public void a2DLayerIgnoresTheTiltedCameraInAeLogic()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        /* A free camera: rotationX/Y must actually tilt the view. */
        camera.cameraLookAt = false;
        camera.rotationX = 25F;
        camera.rotationY = 20F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer flat = card(doc, false);

        assertTrue(CompScene.spatial(ctx), "tilt must switch the composition into spatial mode");
        assertTrue(!CompScene.usePerspective(ctx, flat), "a 2D layer never uses perspective");
        assertTrue(!CompScene.cameraAffects(ctx, flat), "AE: the camera does not affect a 2D layer");

        org.joml.Vector3f point = new org.joml.Vector3f(100F, 50F, 0F);
        org.joml.Vector3f modelPoint = CompScene.model(ctx, flat).transformPosition(new org.joml.Vector3f(point));
        org.joml.Vector3f worldPoint = CompScene.world(ctx, flat).transformPosition(new org.joml.Vector3f(point));

        assertEquals(worldPoint.x, modelPoint.x, 0.01F, "2D model must be the world matrix");
        assertEquals(worldPoint.y, modelPoint.y, 0.01F, "2D model must be the world matrix");

        Vector2f projected = CompScene.project(ctx, flat, 100F, 50F, 0F);

        assertEquals(960F + 100F, projected.x, 0.01F, "tilt/pan/zoom must not move a 2D layer");
        assertEquals(540F + 50F, projected.y, 0.01F, "tilt/pan/zoom must not move a 2D layer");
    }

    /** The legacy switch restores the old look: a 2D layer is dragged along by the camera view. */
    @Test
    public void legacyLogicStillAppliesTheCameraViewToA2DLayer()
    {
        CompDocument doc = document();
        CompProject project = project(doc);

        project.settings.legacy3DLogic = true;

        CompLayer camera = camera(doc);

        camera.cameraLookAt = false;
        camera.rotationX = 25F;
        camera.rotationY = 20F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer flat = card(doc, false);

        assertTrue(CompScene.legacy3D(ctx));
        assertTrue(CompScene.cameraAffects(ctx, flat), "legacy: every content layer follows the camera");
        assertTrue(CompScene.usePerspective(ctx, flat),
            "legacy: a spatial frame projects the 2D layer through the perspective camera");

        org.joml.Vector3f point = new org.joml.Vector3f(100F, 50F, 0F);
        org.joml.Vector3f modelPoint = CompScene.model(ctx, flat).transformPosition(new org.joml.Vector3f(point));
        org.joml.Vector3f viewPoint = new org.joml.Matrix4f(CompScene.camera(ctx).view)
            .mul(CompScene.world(ctx, flat)).transformPosition(new org.joml.Vector3f(point));

        assertEquals(viewPoint.x, modelPoint.x, 0.01F);
        assertEquals(viewPoint.y, modelPoint.y, 0.01F);
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

    /**
     * AE semantics: pan/zoom move the 3D layers, not a 2D layer. The 2D layer's composition pixel
     * is its world pixel, so it stays where the stack put it.
     */
    @Test
    public void panAndZoomDriveThe3DViewButNotA2DLayer()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.x = doc.width * 0.5F + 100F;
        camera.cameraZ /= 2F;
        /* Free camera: the view stays straight down -Z, so pan/zoom are pure 2D-view changes and
         * a point on the z = 0 plane must land on the same pixel in both projections. */
        camera.cameraLookAt = false;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompCamera view = CompScene.camera(ctx);

        assertEquals(2F, view.zoom, 0.001F, "half the distance doubles the zoom");

        CompLayer deep = card(doc, true);
        Vector2f deepCentre = CompScene.project(ctx, deep, 0F, 0F, 0F);

        /* Camera moved right by 100 at 2x zoom: the 3D layer centre must land 200px left of centre. */
        assertEquals(doc.width * 0.5F - 200F, deepCentre.x, EPS);
        assertEquals(doc.height * 0.5F, deepCentre.y, EPS);

        CompLayer flat = card(doc, false);
        Vector2f flatCentre = CompScene.project(ctx, flat, 0F, 0F, 0F);

        assertEquals(doc.width * 0.5F, flatCentre.x, 0.01F, "a 2D layer ignores camera pan");
        assertEquals(doc.height * 0.5F, flatCentre.y, 0.01F, "a 2D layer ignores camera zoom");
    }

    @Test
    public void rollTurnsThe3DWorldButNotA2DLayer()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.rotation = 90F;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext ctx = context(project, doc);
        CompLayer deep = card(doc, true);

        /* Roll the camera 90° clockwise: the world appears to turn counter-clockwise, so a point
         * that was at 3 o'clock moves to 12 o'clock — above the frame centre. */
        Vector2f right = CompScene.project(ctx, deep, 100F, 0F, 0F);

        assertEquals(doc.width * 0.5F, right.x, 0.1F);
        assertEquals(doc.height * 0.5F - 100F, right.y, 0.1F);

        CompLayer flat = card(doc, false);
        Vector2f flatRight = CompScene.project(ctx, flat, 100F, 0F, 0F);

        assertEquals(doc.width * 0.5F + 100F, flatRight.x, 0.01F, "roll must not rotate a 2D layer");
        assertEquals(doc.height * 0.5F, flatRight.y, 0.01F, "roll must not rotate a 2D layer");
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
     * AE logic: a 2D layer sits on the composition rectangle. Neither a tilt nor a pan/zoom may
     * move it; the same camera moves a 3D layer (covered by the tests above).
     */
    @Test
    public void twoDLayerIgnoresPanZoomAndTilt()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.rotationX = 25F;
        camera.rotationY = 20F;
        camera.cameraLookAt = false;
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompRenderContext tiltedCtx = context(project, doc);
        CompLayer flat = card(doc, false);
        Vector2f tilted = CompScene.project(tiltedCtx, flat, 0F, 0F, 0F);

        assertTrue(CompScene.spatial(tiltedCtx), "the pose must really be tilted");

        assertEquals(doc.width * 0.5F, tilted.x, 0.01F, "tilt must not move a 2D layer");
        assertEquals(doc.height * 0.5F, tilted.y, 0.01F, "tilt must not move a 2D layer");

        camera.rotationX = 0F;
        camera.rotationY = 0F;
        camera.x += 200F;

        CompRenderContext pannedCtx = context(project, doc);
        Vector2f panned = CompScene.project(pannedCtx, flat, 0F, 0F, 0F);

        assertEquals(doc.width * 0.5F, panned.x, 0.01F, "pan must not move a 2D layer");
        assertEquals(doc.height * 0.5F, panned.y, 0.01F, "pan must not move a 2D layer");
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

    /**
     * The active-camera choice is frame-local: a camera only overrides while it is a visible
     * camera inside its in/out range. Hiding or trimming it must stop it from driving the view
     * (otherwise the camera picker would lie about what is rendering).
     */
    @Test
    public void activeCameraMustBeUsableAtThisFrame()
    {
        CompDocument doc = document();
        CompLayer camera = camera(doc);

        doc.layers.add(camera);

        assertSame(camera, doc.activeCamera(0F), "visible and inside the trim");

        camera.visible = false;

        assertNull(doc.activeCamera(0F), "hidden camera cannot drive the frame");

        camera.visible = true;
        camera.in = 4F;
        camera.out = 6F;

        assertNull(doc.activeCamera(0F), "trimmed out at 0s");
        assertSame(camera, doc.activeCamera(5F), "inside the trim");
        assertNull(doc.activeCamera(9F), "trimmed out at 9s");
    }

    /** An invalid activeCameraId falls through to the topmost usable camera. */
    @Test
    public void invalidActiveCameraFallsBackToTheTopmostUsableCamera()
    {
        CompDocument doc = document();
        CompLayer bottom = camera(doc);
        CompLayer top = new CompLayer("camera-top", "Camera Top", "camera");

        top.threeD = true;
        top.camera3D = true;
        top.x = 500F;
        top.y = 400F;
        top.cameraZ = CompCamera.focalFor(50F, doc.height);
        top.z = top.cameraZ;
        top.cameraTargetX = 500F;
        top.cameraTargetY = 400F;
        top.cameraTargetZ = 0F;
        top.cameraFov = 50F;
        top.out = doc.duration;
        doc.layers.add(bottom);
        doc.layers.add(top);

        assertSame(top, doc.activeCamera(0F), "no explicit choice: the topmost visible camera wins");

        doc.activeCameraId = bottom.id;
        assertSame(bottom, doc.activeCamera(0F), "a valid explicit choice wins over document order");

        bottom.visible = false;
        assertSame(top, doc.activeCamera(0F), "the explicit choice became unusable this frame");

        top.visible = false;
        assertNull(doc.activeCamera(0F), "no usable camera left");
    }

    @Test
    public void noUsableCameraFallsBackToTheDefaultView()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompLayer camera = camera(doc);

        camera.visible = false;
        doc.layers.add(camera);

        CompRenderContext ctx = context(project, doc);
        CompCamera view = CompScene.camera(ctx);

        assertTrue(!view.hasCamera, "a hidden camera must not count as the frame's camera");
        assertEquals(1F, view.zoom, 0.001F, "the fallback view is the default 1:1 framing");

        CompLayer deep = card(doc, true);
        Vector2f centre = CompScene.project(ctx, deep, 0F, 0F, 0F);

        assertEquals(doc.width * 0.5F, centre.x, EPS);
        assertEquals(doc.height * 0.5F, centre.y, EPS);
    }

    /**
     * An animated camera must move its editor gizmo with the picture. The eye position therefore
     * comes from the transform channel ({@link CompLayer#cameraEye}); reading the base {@code x}
     * field left the icon parked at the start position while the frame panned away.
     */
    @Test
    public void cameraEyeFollowsTheTransformChannel()
    {
        CompDocument doc = document();
        CompLayer camera = camera(doc);
        mchorse.bbs_mod.utils.pose.Transform keyed = camera.defaultTransform().copy();

        keyed.translate.x = 400F;
        keyed.translate.y = 300F;

        camera.transformChannel().insert(0F, keyed.copy());
        keyed.translate.x = 1400F;
        keyed.translate.y = 800F;
        camera.transformChannel().insert(60F, keyed.copy());

        assertEquals(400F, camera.cameraEye(0F).x, 0.01F);
        assertEquals(1400F, camera.cameraEye(60F).x, 0.01F);
        assertEquals(800F, camera.cameraEye(60F).y, 0.01F);

        /* And the view follows the same numbers. */
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompCamera view = CompCamera.of(doc, 0F, 60F);

        assertEquals(1400F, view.cx, 0.01F);
        assertEquals(800F, view.cy, 0.01F);
    }

    /**
     * Stacking must be decided by the layer's <b>plane</b>, not by its origin or its corners.
     *
     * <p>Coplanar layers have to tie so the stack order decides; otherwise a big background plane
     * whose bottom edge is nearer to a tilted camera sorts in front of the small layer above it.
     * A layer pushed towards the viewer on z must sort in front regardless of where it sits in the
     * frame.</p>
     */
    @Test
    public void planeDepthTiesCoplanarLayersAndOrdersByZ()
    {
        CompDocument doc = document();
        CompLayer camera = camera(doc);

        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        CompProject project = project(doc);
        CompRenderContext context = new CompRenderContext(project, doc, 0F, null, null, null);

        CompLayer top = card(doc, true);
        CompLayer bottom = card(doc, true);

        top.x = doc.width * 0.5F;
        top.y = doc.height * 0.15F;
        bottom.x = doc.width * 0.5F;
        bottom.y = doc.height * 0.85F;

        float a = CompScene.planeDepth(context, top);
        float b = CompScene.planeDepth(context, bottom);

        assertEquals(a, b, 0.01F, "coplanar layers must tie regardless of where they sit");

        CompLayer front = card(doc, true);
        CompLayer back = card(doc, true);

        front.z = 300F;
        back.z = -300F;

        assertTrue(CompScene.planeDepth(context, front) > CompScene.planeDepth(context, top),
            "a layer at +z is in front of the composition plane");
        assertTrue(CompScene.planeDepth(context, back) < CompScene.planeDepth(context, top),
            "a layer at -z is behind the composition plane");

        /* Same ordering seen from another orbit angle: the plane test cannot depend on yaw. */
        camera.transformChannel().insert(0F, camera.defaultTransform().copy());
        camera.rotationY = 40F;
        camera.rotationX = 15F;

        CompRenderContext turned = new CompRenderContext(project, doc, 0F, null, null, null);

        assertTrue(CompScene.planeDepth(turned, front) > CompScene.planeDepth(turned, top));
        assertTrue(CompScene.planeDepth(turned, back) < CompScene.planeDepth(turned, top));
    }
}
