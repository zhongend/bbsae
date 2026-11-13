package dev.bbsae.client.ui;

import dev.bbsae.comp.CompLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AE look-at cameras must stay look-at cameras through an orbit. */
public class CompViewControlsOrbitTest
{
    @Test
    public void orbitPreservesLookAtAndPivot()
    {
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.cameraLookAt = true;
        camera.x = 960F;
        camera.y = 540F;
        camera.cameraZ = 900F;
        camera.z = 900F;
        camera.cameraTargetX = 960F;
        camera.cameraTargetY = 540F;
        camera.cameraTargetZ = 0F;

        CompViewControls.orbit(camera, 0F, 25F, 10F);

        assertTrue(camera.cameraLookAt, "orbit must not turn a look-at camera into a free camera");
        assertEquals(960F, camera.cameraTargetX, 0.001F);
        assertEquals(540F, camera.cameraTargetY, 0.001F);
        assertEquals(0F, camera.cameraTargetZ, 0.001F);
        assertTrue(camera.cameraZ > 0F, "the eye must stay on the front side for this framing");
    }
}
