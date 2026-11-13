package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The free/orbit composition camera must be the exact free view, not a second camera model. */
public class CompCameraFreeTest
{
    private static final float EPS = 0.0001F;

    @Test
    public void freeCameraOverrideMatchesTheOrbitView()
    {
        CompViewportCamera orbit = new CompViewportCamera();

        orbit.setViewport(320, 180);
        orbit.setTarget(160F, 90F, 0F);
        orbit.setPose(35F, 20F, 300F);
        orbit.update();

        CompCamera camera = CompCamera.ofFree(orbit, 320F, 180F);

        assertMatrix(camera.view, orbit.view);
        assertMatrix(camera.projection, orbit.projection);
        assertEquals(orbit.fov, camera.fov, EPS);
        assertEquals(true, camera.hasCamera);

        CompRenderContext context = new CompRenderContext(
            new dev.bbsae.comp.CompProject(), new dev.bbsae.comp.CompDocument(), 0F, null, null, null);

        CompScene.pushCamera(camera);

        try
        {
            assertEquals(camera, CompScene.camera(context));
        }
        finally
        {
            CompScene.popCamera();
        }
    }

    private static void assertMatrix(org.joml.Matrix4f expected, org.joml.Matrix4f actual)
    {
        for (int column = 0; column < 4; column++)
        {
            for (int row = 0; row < 4; row++)
            {
                assertEquals(expected.get(column, row), actual.get(column, row), EPS,
                    "matrix element " + column + "," + row);
            }
        }
    }
}
