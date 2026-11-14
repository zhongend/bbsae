package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Feather/expansion compensation is layer scale × preview raster scale, exactly once. */
public class MaskLayerScaleTest
{
    @Test
    public void layerScaleAndRasterScaleMultiplyOnce()
    {
        Matrix4f model = new Matrix4f().scale(2F, 3F, 1F);

        assertEquals(3F, CompRenderer.maskLayerScale(model, 1F), 0.0001F);
        assertEquals(1.5F, CompRenderer.maskLayerScale(model, 0.5F), 0.0001F);
        assertEquals(6F, CompRenderer.maskLayerScale(model, 2F), 0.0001F);
    }
}
