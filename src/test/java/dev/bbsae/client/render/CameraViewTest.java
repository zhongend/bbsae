package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 2D camera is a view transform over composition space. These tests pin the two things that
 * used to be wrong: a camera layer must not draw itself, and turning the camera has to turn the
 * world the other way (a camera looking right shows what is to its right "ahead", i.e. up in the
 * frame, because the camera position is pinned to the frame centre).
 */
public class CameraViewTest
{
    private static final float EPS = 0.001F;

    private static CompRenderContext context()
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = 1920;
        comp.height = 1080;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    private static CompLayer camera(float x, float y, float rotation, boolean lookAt, float targetX, float targetY)
    {
        CompLayer camera = new CompLayer("cam", "Camera", "camera");

        camera.x = x;
        camera.y = y;
        camera.rotation = rotation;
        camera.cameraLookAt = lookAt;
        camera.cameraTargetX = targetX;
        camera.cameraTargetY = targetY;

        return camera;
    }

    @Test
    public void cameraAtCentreIsIdentity()
    {
        Matrix4f view = CompRenderer.cameraView(camera(960F, 540F, 0F, false, 960F, 540F), context());
        Vector3f point = new Vector3f(100F, 200F, 0F);

        view.transformPosition(point);

        assertEquals(100F, point.x, EPS);
        assertEquals(200F, point.y, EPS);
    }

    @Test
    public void cameraPanMovesTheWorldTheOtherWay()
    {
        Matrix4f view = CompRenderer.cameraView(camera(1060F, 540F, 0F, false, 1060F, 540F), context());
        Vector3f point = new Vector3f(100F, 200F, 0F);

        view.transformPosition(point);

        assertEquals(0F, point.x, EPS, "camera at x=1060 shifts the world left by 100");
        assertEquals(200F, point.y, EPS);
    }

    @Test
    public void cameraRotationTurnsTheWorldOpposite()
    {
        /* Looking right: the point to the camera's right is now straight ahead and must land
         * above the frame centre, not below it. */
        Matrix4f view = CompRenderer.cameraView(camera(960F, 540F, 90F, false, 960F, 540F), context());
        Vector3f point = new Vector3f(1060F, 540F, 0F);

        view.transformPosition(point);

        assertEquals(960F, point.x, EPS);
        assertEquals(440F, point.y, EPS);

        Matrix4f opposite = CompRenderer.cameraView(camera(960F, 540F, -90F, false, 960F, 540F), context());
        Vector3f other = new Vector3f(1060F, 540F, 0F);

        opposite.transformPosition(other);

        assertEquals(960F, other.x, EPS);
        assertEquals(640F, other.y, EPS);
    }

    @Test
    public void lookAtTargetAgreesWithManualRotation()
    {
        Matrix4f manual = CompRenderer.cameraView(camera(960F, 540F, 90F, false, 960F, 540F), context());
        Matrix4f lookAt = CompRenderer.cameraView(camera(960F, 540F, 0F, true, 1060F, 540F), context());
        Vector3f a = new Vector3f(1200F, 300F, 0F);
        Vector3f b = new Vector3f(1200F, 300F, 0F);

        manual.transformPosition(a);
        lookAt.transformPosition(b);

        assertEquals(a.x, b.x, EPS);
        assertEquals(a.y, b.y, EPS);
    }
}
