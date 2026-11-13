package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AE cameras on the negative side of the comp plane must still produce a valid view. */
public class CameraNegativeZTest
{
    @Test
    public void negativeCameraZProducesAValidView()
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.name = "Main";
        project.comps.add(doc);

        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.cameraLookAt = true;
        camera.camera3D = true;
        camera.threeD = true;
        camera.cameraFov = 50F;
        camera.cameraNear = 1F;
        camera.cameraFar = 100000F;
        camera.x = 960F;
        camera.y = 540F;
        camera.cameraZ = -500F;
        camera.z = -500F;
        camera.cameraTargetX = 960F;
        camera.cameraTargetY = 540F;
        camera.cameraTargetZ = 0F;
        doc.layers.add(camera);

        CompCamera view = CompCamera.of(doc, 0F, 0F);

        assertTrue(view.hasCamera);
        assertEquals(-500F, view.cz, 0.001F, "the signed Z must survive");
        assertEquals(500F, view.distance, 0.001F);
        assertTrue(view.zoom > 0F);
    }
}
