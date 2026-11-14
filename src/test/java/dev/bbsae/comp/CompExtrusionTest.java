package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extrusion geometry and its persistence.
 *
 * <p>The volume is drawn as a stack of planes. These tests pin the three things a renderer can get
 * wrong without failing loudly: the order of the planes (painter order), which plane keeps the
 * artwork's colours, and whether every option actually reaches the saved project. Geometry is a
 * pure function, so it is tested without a client.</p>
 */
public class CompExtrusionTest
{
    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "text");

        layer.threeD = true;
        layer.extrude = true;
        layer.extrudeDepth = 60F;
        layer.extrudeSlices = 5;
        layer.extrudeShade = 0.4F;
        layer.extrudeUseLayerColor = true;
        layer.color = 0xFF3366CC;
        layer.extrudeColor = 0xFFFF0000;

        return layer;
    }

    @Test
    public void extrusionNeedsAThreeDLayer()
    {
        CompLayer layer = layer();

        assertTrue(CompExtrusion.isEnabled(layer));

        layer.threeD = false;
        assertFalse(CompExtrusion.isEnabled(layer), "a 2D layer is screen-attached; a Z stack collapses");
        assertTrue(CompExtrusion.slices(layer).isEmpty());

        layer.threeD = true;
        layer.extrude = false;
        assertFalse(CompExtrusion.isEnabled(layer));
        assertTrue(CompExtrusion.slices(layer).isEmpty());

        layer.extrude = true;
        layer.extrudeDepth = 0F;
        assertFalse(CompExtrusion.isEnabled(layer));
    }

    @Test
    public void camerasNullsAdjustmentAndEntityLayersCannotBeExtruded()
    {
        for (String type : new String[] {"camera", "null", "adjustment", "replay"})
        {
            CompLayer layer = layer();

            layer.type = type;
            assertFalse(CompExtrusion.isEnabled(layer), type + " has no flat pixels to extrude");
        }
    }

    @Test
    public void backVolumeIsPaintedFarToNearWithTheArtworkLast()
    {
        CompLayer layer = layer();
        List<CompExtrusion.Slice> slices = CompExtrusion.slices(layer);

        assertEquals(5, slices.size());

        CompExtrusion.Slice farthest = slices.get(0);
        CompExtrusion.Slice artwork = slices.get(slices.size() - 1);

        assertFalse(farthest.artwork());
        assertEquals(-60F, farthest.z(), 0.0001F, "the far end is painted first");

        assertTrue(artwork.artwork(), "the artwork face is painted last so the sides stay behind it");
        assertEquals(0F, artwork.z(), 0.0001F);
        assertEquals(0xFFFFFFFF, artwork.tint(), "the artwork face must keep its own colours");

        /* Painter order: each slice must be nearer than the previous one. */
        float previous = farthest.z();

        for (int i = 1; i < slices.size(); i++)
        {
            assertTrue(slices.get(i).z() >= previous, "painter order is far-to-near");
            previous = slices.get(i).z();
        }
    }

    @Test
    public void frontVolumePutsTheArtworkOnTheNearFace()
    {
        CompLayer layer = layer();

        layer.extrudeDirection = CompExtrusion.DIRECTION_FRONT;

        List<CompExtrusion.Slice> slices = CompExtrusion.slices(layer);
        CompExtrusion.Slice first = slices.get(0);
        CompExtrusion.Slice last = slices.get(slices.size() - 1);

        assertFalse(first.artwork());
        assertEquals(0F, first.z(), 0.0001F, "the far end of a forward extrusion sits at the layer plane");
        assertTrue(last.artwork());
        assertEquals(60F, last.z(), 0.0001F, "the artwork moves to the near face");
    }

    @Test
    public void centredVolumeKeepsTheArtworkInTheMiddle()
    {
        CompLayer layer = layer();

        layer.extrudeDirection = CompExtrusion.DIRECTION_BOTH;

        List<CompExtrusion.Slice> slices = CompExtrusion.slices(layer);
        int artwork = -1;

        for (int i = 0; i < slices.size(); i++)
        {
            if (slices.get(i).artwork())
            {
                artwork = i;
                break;
            }
        }

        assertTrue(artwork > 0 && artwork < slices.size() - 1, "a centred volume has sides on both sides");
        assertEquals(0F, slices.get(artwork).z(), 0.0001F);
        assertEquals(-30F, slices.get(0).z(), 0.0001F);
        assertEquals(30F, slices.get(slices.size() - 1).z(), 0.0001F);
    }

    @Test
    public void theFarCapCanBeRemoved()
    {
        CompLayer layer = layer();

        assertTrue(CompExtrusion.slices(layer).get(0).z() < -59F);

        layer.extrudeCap = false;

        List<CompExtrusion.Slice> open = CompExtrusion.slices(layer);

        assertTrue(open.size() < 5, "without the cap the far end plane is not drawn");
        assertTrue(open.get(0).z() > -60F, "the remaining planes stay inside the depth");
        assertTrue(open.get(open.size() - 1).artwork(), "the near artwork face is always drawn");
    }

    @Test
    public void sideColourUsesTheLayerOrTheThicknessColour()
    {
        CompLayer layer = layer();

        assertEquals(0xFF3366CC, CompExtrusion.sideColor(layer), "use-layer-colour picks the layer colour");

        layer.extrudeUseLayerColor = false;

        assertEquals(0xFFFF0000, CompExtrusion.sideColor(layer));
    }

    @Test
    public void sidesDarkenAwayFromTheArtwork()
    {
        CompLayer layer = layer();

        layer.extrudeShade = 1F;

        List<CompExtrusion.Slice> slices = CompExtrusion.slices(layer);
        /* Back volume: index 0 is the far end, index size-2 the slice closest to the artwork. */
        int near = slices.size() - 2;
        int nearLum = luminance(slices.get(near).tint());
        int farLum = luminance(slices.get(0).tint());

        assertTrue(farLum < nearLum, "the depth cue has to darken towards the far end");

        layer.extrudeShade = 0F;

        int flatNear = luminance(CompExtrusion.slices(layer).get(near).tint());
        int flatFar = luminance(CompExtrusion.slices(layer).get(0).tint());

        assertEquals(flatNear, flatFar, "zero shade means a flat side");
    }

    private static int luminance(int argb)
    {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }

    @Test
    public void shadingRoundsInsteadOfTruncating()
    {
        /* 128 * 0.5 = 64 exactly; 127 * 0.5 = 63.5 must round to 64, not truncate to 63. */
        assertEquals(0xFF404040, CompExtrusion.shaded(0xFF808080, 0.5F));
        assertEquals(0xFF404040, CompExtrusion.shaded(0xFF7F7F7F, 0.5F));
        assertEquals(0xFFFFFFFF, CompExtrusion.shaded(0xFFFFFFFF, 0F));
        assertEquals(0xFF000000, CompExtrusion.shaded(0xFFFFFFFF, 1F));
    }

    @Test
    public void everyExtrusionFieldSurvivesDuplicateAndProjectPersistence()
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.layers.add(layer());
        project.comps.add(doc);

        CompLayer copy = doc.layers.get(0).duplicate();

        assertTrue(copy.extrude);
        assertEquals(60F, copy.extrudeDepth, 0.0001F);
        assertEquals(5, copy.extrudeSlices);
        assertEquals(0xFF3366CC, copy.color);
        assertEquals(0xFFFF0000, copy.extrudeColor);
        assertTrue(copy.extrudeUseLayerColor);
        assertEquals(0.4F, copy.extrudeShade, 0.0001F);
        assertEquals(CompExtrusion.DIRECTION_BACK, copy.extrudeDirection);
        assertTrue(copy.extrudeCap);

        CompProject restored = CompStore.restore(CompStore.snapshot(project));

        assertNotNull(restored);

        CompLayer loaded = restored.getCompOrFirst("main").layers.get(0);

        assertTrue(loaded.extrude, "the volume switch must not reset on load");
        assertEquals(60F, loaded.extrudeDepth, 0.0001F);
        assertEquals(5, loaded.extrudeSlices);
        assertEquals(0xFFFF0000, loaded.extrudeColor);
        assertTrue(loaded.extrudeUseLayerColor);
        assertEquals(0.4F, loaded.extrudeShade, 0.0001F);
        assertEquals(CompExtrusion.DIRECTION_BACK, loaded.extrudeDirection);
        assertTrue(loaded.extrudeCap);
    }

    @Test
    public void theLegacyExtrudeEffectStillProducesAVolume()
    {
        CompLayer layer = layer();

        layer.extrude = false;

        CompEffect effect = CompEffectRegistry.create("extrude3d", 1);

        effect.put("depth", 100D);
        effect.put("slices", 4D);
        effect.put("shade", 0.5D);

        List<CompExtrusion.Slice> slices = CompExtrusion.slices(layer, effect);

        assertEquals(4, slices.size());
        assertEquals(-100F, slices.get(0).z(), 0.0001F, "legacy volumes use the same far-to-near order");
        assertEquals(0F, slices.get(slices.size() - 1).z(), 0.0001F);
        assertTrue(slices.get(slices.size() - 1).artwork());

        /* The layer switch wins when both exist: a project migrated to the new page must not be
         * extruded twice. */
        layer.extrude = true;
        layer.extrudeDepth = 30F;
        layer.extrudeSlices = 3;

        List<CompExtrusion.Slice> own = CompExtrusion.slices(layer, effect);

        assertEquals(3, own.size());
        assertEquals(-30F, own.get(0).z(), 0.0001F);

        /* The switch being on with zero thickness means "no volume"; it must not quietly fall back
         * to the legacy effect and keep drawing a volume the inspector says is off. */
        layer.extrudeDepth = 0F;

        assertTrue(CompExtrusion.slices(layer, effect).isEmpty());
    }

    @Test
    public void depthAndSlicesAreClampedToTheirUiRange()
    {
        CompLayer layer = layer();

        layer.extrudeDepth = 99999F;
        assertEquals(CompExtrusion.MAX_DEPTH, CompExtrusion.depth(layer), 0.0001F);

        layer.extrudeSlices = 9999;
        assertEquals(CompExtrusion.MAX_SLICES, CompExtrusion.sliceCount(layer));

        layer.extrudeSlices = -10;
        assertEquals(CompExtrusion.MIN_SLICES, CompExtrusion.sliceCount(layer));

        layer.extrudeShade = 5F;
        assertEquals(1F, CompExtrusion.shade(layer), 0.0001F);
    }
}
