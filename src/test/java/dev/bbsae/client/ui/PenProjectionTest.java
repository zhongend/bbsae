package dev.bbsae.client.ui;

import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompRenderContext;
import dev.bbsae.client.render.CompScene;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen ↔ layer-local mapping of the pen tool, on the real scene graph.
 *
 * <p>The promise the pen has to keep is "the vertex lands where the cursor is". That is exactly a
 * forward/inverse round trip through the same projection the renderer uses
 * ({@link CompScene#project}), so these tests assert it for the three cases that exist in the
 * editor: a flat 2D layer (affine), a tilted 3D layer under a perspective camera (ray/plane), and a
 * 3D layer under an orthographic camera — where {@code layer.threeD} is true but the projection is
 * still affine, the case the old drag code got wrong.</p>
 */
public class PenProjectionTest
{
    private static final float EPS = 0.05F;

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

    private static CompLayer camera(CompDocument doc, boolean perspective)
    {
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.threeD = true;
        camera.camera3D = perspective;
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

    private static CompRenderContext context(CompProject project, CompDocument doc, CompLayer camera)
    {
        doc.layers.add(camera);
        doc.activeCameraId = camera.id;

        return new CompRenderContext(project, doc, 0F, null, null, null);
    }

    /** Fit-only preview geometry, exactly like {@code UICompositorPanel.penView()}. */
    private static UIPenTool.View view(CompRenderContext rc, CompDocument doc)
    {
        UIPenTool.View view = new UIPenTool.View();

        view.areaW = 800F;
        view.areaH = 600F;
        view.compWidth = doc.width;
        view.compHeight = doc.height;
        view.scale = Math.min(800F / doc.width, 600F / doc.height);
        view.ox = (800F - doc.width * view.scale) / 2F;
        view.oy = (600F - doc.height * view.scale) / 2F;
        view.rc = rc;

        return view;
    }

    private static void assertRoundTrip(CompLayer layer, UIPenTool.View view, float localX, float localY)
    {
        float[] screen = UIPenTool.localToScreen(layer, view, localX, localY);

        assertNotNull(screen, "point must project on screen");

        float[] back = UIPenTool.screenToLocal(layer, view, screen[0], screen[1]);

        assertNotNull(back, "screen point must map back to the layer plane");
        assertEquals(localX, back[0], EPS, "local x round trip");
        assertEquals(localY, back[1], EPS, "local y round trip");
    }

    @Test
    public void flatLayerRoundTripSurvivesRotationAndNonUniformScale()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext rc = context(project, doc, camera(doc, true));
        CompLayer layer = card(doc, false);

        layer.rotation = 30F;
        layer.scaleX = 1.4F;
        layer.scaleY = 0.7F;

        UIPenTool.View view = view(rc, doc);

        assertRoundTrip(layer, view, -100F, -50F);
        assertRoundTrip(layer, view, 0F, 0F);
        assertRoundTrip(layer, view, 150F, 80F);
    }

    @Test
    public void flatLayerForwardProjectionMatchesTheScene()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext rc = context(project, doc, camera(doc, true));
        CompLayer layer = card(doc, false);

        layer.rotation = 17F;
        layer.scaleX = 1.2F;

        UIPenTool.View view = view(rc, doc);
        Vector2f expected = CompScene.project(rc, layer, 123F, -45F, 0F);
        float[] actual = UIPenTool.localToScreen(layer, view, 123F, -45F);

        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(view.areaX + view.ox + expected.x * view.scale, actual[0], 0.001F);
        assertEquals(view.areaY + view.oy + expected.y * view.scale, actual[1], 0.001F);
    }

    @Test
    public void tilted3DLayerUsesRayPlaneInverse()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext rc = context(project, doc, camera(doc, true));
        CompLayer layer = card(doc, true);

        layer.rotationY = 30F;

        assertTrue(CompScene.usePerspective(rc, layer), "perspective camera + 3D layer");

        UIPenTool.View view = view(rc, doc);

        assertRoundTrip(layer, view, -200F, -100F);
        assertRoundTrip(layer, view, 0F, 0F);
        assertRoundTrip(layer, view, 200F, 100F);
        assertRoundTrip(layer, view, 60F, -80F);
    }

    @Test
    public void threeDLayerUnderOrthoCameraUsesAffineInverse()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext rc = context(project, doc, camera(doc, false));
        CompLayer layer = card(doc, true);

        layer.rotationY = 25F;

        /* layer.threeD is true but the camera is orthographic: keying the inverse on threeD (the
         * old bug) would ray-cast into a plane that the renderer projects affinely. */
        assertFalse(CompScene.usePerspective(rc, layer), "orthographic camera must project affinely");

        UIPenTool.View view = view(rc, doc);

        assertRoundTrip(layer, view, -180F, 90F);
        assertRoundTrip(layer, view, 40F, -40F);
    }

    @Test
    public void tiltedCardProjectsAsAPerspectiveTrapezoid()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext rc = context(project, doc, camera(doc, true));
        CompLayer layer = card(doc, true);

        layer.rotationY = 30F;

        UIPenTool.View view = view(rc, doc);
        float[] topLeft = UIPenTool.localToScreen(layer, view, -200F, -100F);
        float[] topRight = UIPenTool.localToScreen(layer, view, 200F, -100F);
        float[] bottomLeft = UIPenTool.localToScreen(layer, view, -200F, 100F);
        float[] bottomRight = UIPenTool.localToScreen(layer, view, 200F, 100F);

        assertNotNull(topLeft);
        assertNotNull(topRight);
        assertNotNull(bottomLeft);
        assertNotNull(bottomRight);

        /* A convex, order-preserving quad: left edge above-right of the right edge, top above
         * bottom. This is what "the path shape is correct after projection" means visually. */
        assertTrue(topLeft[0] < topRight[0], "top-left is left of top-right");
        assertTrue(bottomLeft[0] < bottomRight[0], "bottom-left is left of bottom-right");
        assertTrue(topLeft[1] < bottomLeft[1], "top-left is above bottom-left");
        assertTrue(topRight[1] < bottomRight[1], "top-right is above bottom-right");

        float leftHeight = Math.abs(bottomLeft[1] - topLeft[1]);
        float rightHeight = Math.abs(bottomRight[1] - topRight[1]);

        assertTrue(leftHeight > rightHeight * 1.05F,
            "with yaw 30 the left edge is nearer and must project taller: " + leftHeight + " vs " + rightHeight);
    }

    @Test
    public void behindCameraMapsToNullInsteadOfGarbage()
    {
        CompDocument doc = document();
        CompProject project = project(doc);
        CompRenderContext rc = context(project, doc, camera(doc, true));
        CompLayer layer = card(doc, true);

        layer.rotationY = 90F;
        layer.z = 4000F;

        UIPenTool.View view = view(rc, doc);
        float[] screen = UIPenTool.localToScreen(layer, view, 0F, 0F);

        /* Either visible at a sane position or null; never NaN/Infinity. */
        if (screen != null)
        {
            assertFalse(Float.isNaN(screen[0]) || Float.isInfinite(screen[0]));
            assertFalse(Float.isNaN(screen[1]) || Float.isInfinite(screen[1]));
        }
    }
}
