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

    @Test
    public void absoluteOrbitIsIdempotent()
    {
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.cameraLookAt = false;
        camera.rotationY = 10F;
        camera.rotationX = 5F;
        camera.x = 960F;
        camera.y = 540F;
        camera.cameraZ = 900F;
        camera.z = 900F;

        CompViewControls.orbitFromStart(camera, 0F, 10F, 5F, 960F, 540F, 900F, 30F, 15F);

        float yaw = camera.rotationY;
        float pitch = camera.rotationX;
        float z = camera.cameraZ;

        /* The drag handler calls this every frame with the same total delta. Repeating it must not
         * keep adding 30°/15° (the old compounding camera-spin bug). */
        CompViewControls.orbitFromStart(camera, 0F, 10F, 5F, 960F, 540F, 900F, 30F, 15F);

        assertEquals(yaw, camera.rotationY, 0.001F);
        assertEquals(pitch, camera.rotationX, 0.001F);
        assertEquals(z, camera.cameraZ, 0.001F);
    }
}
