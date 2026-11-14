package dev.bbsae.client.render;

import dev.bbsae.comp.CompExtrusion;
import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extrusion stack must be painted farthest-first <b>from the actual camera</b>, not only in the
 * authored direction.
 *
 * <p>The authored order is correct while the artwork plane faces the camera. Rotating the layer past
 * 90° (or orbiting behind it in the 3D workspace) puts the artwork farthest away; painting it last
 * made it cover the whole volume and the extrusion looked flat from the back.</p>
 */
public class ExtrusionCameraOrderTest
{
    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("l", "Layer", "text");

        layer.threeD = true;
        layer.extrude = true;
        layer.extrudeDepth = 60F;
        layer.extrudeSlices = 5;
        layer.extrudeShade = 0.35F;
        layer.extrudeUseLayerColor = true;
        layer.color = 0xFF3366CC;

        return layer;
    }

    private static Matrix4f perspective()
    {
        return new Matrix4f().perspective((float) Math.toRadians(50), 16F / 9F, 0.1F, 1000F);
    }

    private static Matrix4f model()
    {
        /* Comp space +Z is towards the viewer; the layer sits 300 units in front of the camera. */
        return new Matrix4f().translate(0F, 0F, -300F);
    }

    @Test
    public void fromTheFrontTheArtworkIsPaintedLast()
    {
        List<CompExtrusion.Slice> slices = new ArrayList<>(CompExtrusion.slices(layer()));

        CompRenderer.sortSlicesForCamera(slices, model(), perspective());

        CompExtrusion.Slice last = slices.get(slices.size() - 1);

        assertTrue(last.artwork(), "the near artwork face must be painted last");
        assertEquals(0F, last.z(), 0.0001F);
        assertFalse(slices.get(0).artwork(), "the far end is painted first");
        assertEquals(-60F, slices.get(0).z(), 0.0001F);
    }

    @Test
    public void fromBehindTheArtworkIsPaintedFirst()
    {
        List<CompExtrusion.Slice> slices = new ArrayList<>(CompExtrusion.slices(layer()));
        Matrix4f turned = new Matrix4f().translate(0F, 0F, -300F).rotateY((float) Math.PI);

        CompRenderer.sortSlicesForCamera(slices, turned, perspective());

        CompExtrusion.Slice first = slices.get(0);
        CompExtrusion.Slice last = slices.get(slices.size() - 1);

        assertTrue(first.artwork(), "seen from behind, the artwork plane is the farthest one");
        assertEquals(0F, first.z(), 0.0001F);
        assertEquals(-60F, last.z(), 0.0001F, "the old far end is now nearest and painted last");
    }

    @Test
    public void orthographicCamerasUseTheSameFarToNearOrder()
    {
        List<CompExtrusion.Slice> slices = new ArrayList<>(CompExtrusion.slices(layer()));
        Matrix4f ortho = new Matrix4f().ortho(-500F, 500F, 500F, -500F, 0.1F, 1000F);

        CompRenderer.sortSlicesForCamera(slices, model(), ortho);

        assertEquals(-60F, slices.get(0).z(), 0.0001F);
        assertTrue(slices.get(slices.size() - 1).artwork());
    }

    @Test
    public void anEdgeOnLayerKeepsEverySlice()
    {
        List<CompExtrusion.Slice> slices = new ArrayList<>(CompExtrusion.slices(layer()));
        Matrix4f edgeOn = new Matrix4f().translate(0F, 0F, -300F).rotateY((float) Math.PI / 2F);

        CompRenderer.sortSlicesForCamera(slices, edgeOn, perspective());

        assertEquals(5, slices.size(), "sorting must not drop or duplicate slices");
    }
}
