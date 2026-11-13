package dev.bbsae.client.ui;

import dev.bbsae.comp.CompLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AE Track Z must preserve the side of the subject the camera is on. */
public class CompViewControlsCameraZTest
{
    @Test
    public void dollyKeepsANegativeCameraOnTheSameSide()
    {
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.cameraNear = 10F;
        camera.cameraFar = 100000F;
        camera.cameraZ = -800F;
        camera.z = -800F;

        CompViewControls.dolly(camera, 0F, 0.5F);

        assertTrue(camera.cameraZ < 0F, "a camera behind the comp plane must stay behind it");
        assertEquals(-400F, camera.cameraZ, 0.001F);
        assertEquals(-400F, camera.z, 0.001F);

        CompViewControls.dolly(camera, 0F, 2F);
        assertEquals(-800F, camera.cameraZ, 0.001F);
    }

    @Test
    public void dollyStillClampsNearFar()
    {
        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.cameraNear = 10F;
        camera.cameraFar = 1000F;
        camera.cameraZ = -500F;
        camera.z = -500F;

        CompViewControls.dolly(camera, 0F, 0.000001F);
        assertEquals(-20F, camera.cameraZ, 0.001F, "near clamp applies on the negative side too");

        CompViewControls.dolly(camera, 0F, 1000F);
        assertEquals(-990F, camera.cameraZ, 0.001F, "far clamp applies on the negative side too");
    }
}
