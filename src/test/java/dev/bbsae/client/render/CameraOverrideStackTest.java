package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit camera overrides are nested (outer replay camera + collapsed precomp camera). */
public class CameraOverrideStackTest
{
    @Test
    public void nestedOverridesRestoreTheOuterCamera()
    {
        CompCamera outer = CompCamera.identity(100F, 100F);
        CompCamera inner = CompCamera.identity(100F, 100F);

        assertFalse(CompScene.hasCameraOverride());
        CompScene.pushCamera(outer);
        assertTrue(CompScene.hasCameraOverride());
        CompScene.pushCamera(inner);
        assertTrue(CompScene.hasCameraOverride());
        CompScene.popCamera();
        assertTrue(CompScene.hasCameraOverride(), "popping the inner camera must restore the outer");
        CompScene.popCamera();
        assertFalse(CompScene.hasCameraOverride(), "popping the outer camera must clear the stack");
    }
}
