package dev.bbsae.client.replay;

import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.ui.utils.Area;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The replay guide overlay's coordinate contract.
 *
 * <p>The free/orbit picture is a finished composition raster letterboxed into a plate: 2D layers
 * are screen-fixed inside that raster, 3D layers were projected by the free camera, and the whole
 * thing was then blitted. Guides must use that same composition-space projection and the same
 * plate mapping. Projecting a 2D text label through the 3D camera is what made labels drift from
 * the text they name once the camera orbited.</p>
 */
public class ReplayGuideSpaceTest
{
    private static final int DOC_W = 320;
    private static final int DOC_H = 180;

    private static CompDocument doc()
    {
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.name = "Main";
        doc.width = DOC_W;
        doc.height = DOC_H;
        doc.fps = 20F;
        doc.duration = 10F;

        return doc;
    }

    private static CompViewportCamera camera(float yaw, float pitch)
    {
        CompViewportCamera camera = new CompViewportCamera();

        camera.setViewport(1280, 720);
        camera.target.set(160F, 90F, 0F);
        camera.setPose(yaw, pitch, 500F);
        camera.update();

        return camera;
    }

    private static Area plate()
    {
        return new Area(0, 0, 1280, 720);
    }

    @Test
    public void aTwoDimensionalLayerMapsThroughCompositionPixelsNotTheCamera()
    {
        CompLayer layer = new CompLayer("text", "Text", "text");

        layer.visible = true;
        layer.threeD = false;
        layer.x = 160F;
        layer.y = 90F;
        layer.width = 100F;
        layer.height = 50F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        ReplayGuideSpace front = new ReplayGuideSpace(new CompProject(), doc(), camera(0F, 0F), 0F, plate());
        ReplayGuideSpace orbit = new ReplayGuideSpace(new CompProject(), doc(), camera(55F, 35F), 0F, plate());

        Vector2f a = front.projectLayer(layer, 0F, 0F, 0F);
        Vector2f b = orbit.projectLayer(layer, 0F, 0F, 0F);

        assertNotNull(a);
        assertNotNull(b);
        /* comp (160,90) at 320x180 -> plate centre. The orbit camera must not move a 2D layer. */
        assertEquals(640F, a.x, 0.001F);
        assertEquals(360F, a.y, 0.001F);
        assertEquals(a.x, b.x, 0.001F, "a 2D layer is screen-fixed in the composition raster");
        assertEquals(a.y, b.y, 0.001F, "a 2D layer is screen-fixed in the composition raster");
    }

    @Test
    public void aThreeDimensionalLayerUsesTheSameProjectionAsTheCompositionCamera()
    {
        CompLayer layer = new CompLayer("card", "Card", "shape");

        layer.visible = true;
        layer.threeD = true;
        layer.x = 160F;
        layer.y = 90F;
        layer.z = 30F;
        layer.width = 80F;
        layer.height = 40F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        ReplayGuideSpace guides = new ReplayGuideSpace(new CompProject(), doc(), camera(35F, 20F), 0F, plate());
        Vector2f guide = guides.projectLayer(layer, 0F, 0F, 0F);
        Vector2f direct = guides.camera().project(new Vector3f(160F, 90F, 30F), DOC_W, DOC_H);

        assertNotNull(guide);
        assertNotNull(direct);
        assertEquals(plate().x + direct.x * plate().w / DOC_W, guide.x, 0.001F);
        assertEquals(plate().y + direct.y * plate().h / DOC_H, guide.y, 0.001F);
    }

    @Test
    public void rotationAndScaleAreAppliedInCompositionSpace()
    {
        CompLayer layer = new CompLayer("card", "Card", "shape");

        layer.visible = true;
        layer.threeD = false;
        layer.x = 160F;
        layer.y = 90F;
        layer.width = 100F;
        layer.height = 20F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;
        layer.scaleX = 2F;
        layer.scaleY = 2F;

        ReplayGuideSpace guides = new ReplayGuideSpace(new CompProject(), doc(), camera(0F, 0F), 0F, plate());
        Vector2f corner = guides.projectLayer(layer, 50F, 0F, 0F);

        assertNotNull(corner);
        /* Local x=50, scaled 2x -> comp x=160+100, y=90 -> plate x=1040, y=360. */
        assertEquals(1040F, corner.x, 0.001F);
        assertEquals(360F, corner.y, 0.001F);
    }

    @Test
    public void aCompositionPointMapsThroughThePlateNotTheRawCameraViewport()
    {
        ReplayGuideSpace guides = new ReplayGuideSpace(new CompProject(), doc(), camera(0F, 0F), 0F,
            new Area(100, 50, 640, 360));
        Vector2f screen = guides.toScreen(160F, 90F);

        assertEquals(420F, screen.x, 0.001F);
        assertEquals(230F, screen.y, 0.001F);
    }

    @Test
    public void theCompositionCameraMatchesTheFreePose()
    {
        CompViewportCamera free = camera(42F, 17F);
        CompCamera camera = CompCamera.ofFree(free, DOC_W, DOC_H);

        assertEquals(free.position.x, camera.cx, 0.001F);
        assertEquals(free.position.y, camera.cy, 0.001F);
        assertEquals(free.position.z, camera.cz, 0.001F);
        assertEquals(free.target.x, camera.tx, 0.001F);
    }
}
