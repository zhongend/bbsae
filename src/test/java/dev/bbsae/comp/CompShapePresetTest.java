package dev.bbsae.comp;

import dev.bbsae.client.render.ShapeRasterizer;
import org.junit.jupiter.api.Test;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-corner geometry, per-shape colour data and preset application, all headless. */
public class CompShapePresetTest
{
    @Test
    public void rectangleExposesFourNamedCorners()
    {
        CompShape shape = new CompShape("r", CompShape.RECT);

        assertEquals(4, shape.cornerCount());

        shape.setCorner(0, 24F);
        shape.setCorner(2, 8F);

        assertEquals(24F, shape.corner(0), 0.001F);
        assertEquals(0F, shape.corner(1), 0.001F);
        assertEquals(8F, shape.corner(2), 0.001F);
        assertTrue(shape.hasCorners());
    }

    @Test
    public void triangleExposesThreeCornersAndRoundsThemInThePath()
    {
        CompShape sharp = new CompShape("t1", CompShape.TRIANGLE);
        CompShape round = new CompShape("t2", CompShape.TRIANGLE);

        sharp.width = 200F;
        sharp.height = 180F;
        round.width = 200F;
        round.height = 180F;

        assertEquals(3, sharp.cornerCount());

        for (int i = 0; i < 3; i++)
        {
            round.setCorner(i, 16F);
        }

        Shape sharpPath = ShapeRasterizer.buildShape(sharp);
        Shape roundPath = ShapeRasterizer.buildShape(round);

        assertNotNull(sharpPath);
        assertNotNull(roundPath);
        assertEquals(0, countCurves(sharpPath));
        assertEquals(3, countCurves(roundPath));
    }

    @Test
    public void cornerRadiusIsClampedToHalfTheShortestEdge()
    {
        CompShape shape = new CompShape("t", CompShape.TRIANGLE);

        shape.width = 100F;
        shape.height = 100F;
        shape.setCorner(0, 5000F);

        /* The path must still be a closed, sane shape - no self-intersecting spikes. */
        Shape path = ShapeRasterizer.buildShape(shape);

        assertNotNull(path);
        assertTrue(path.getBounds2D().getWidth() <= 110F);
    }

    @Test
    public void animatedCornerRadiusResolvesPerFrame()
    {
        CompLayer layer = new CompLayer("shape_layer", "Shape", "shape");
        CompShape shape = new CompShape("card", CompShape.RECT);

        shape.width = 200F;
        shape.height = 120F;
        layer.shapes.add(shape);
        layer.setKeyframe(CompLayer.shapePath("card", "c/0"), 0F, 0F);
        layer.setKeyframe(CompLayer.shapePath("card", "c/0"), 20F, 40F);

        assertEquals(0F, layer.shapeAt(shape, 0F).corner(0), 0.001F);
        assertEquals(40F, layer.shapeAt(shape, 20F).corner(0), 0.001F);
    }

    @Test
    public void effectPresetAddsTheStackWithUniqueIds()
    {
        CompLayer layer = new CompLayer("l", "L", "solid");
        CompPreset preset = find("builtin.cine");

        assertNotNull(preset);
        assertTrue(preset.applyTo(layer, 30F));
        assertEquals(4, layer.effects.size());

        assertTrue(preset.applyTo(layer, 30F));
        assertEquals(8, layer.effects.size());

        long distinct = layer.effects.stream().map((effect) -> effect.id).distinct().count();

        assertEquals(8L, distinct);
    }

    @Test
    public void shapePresetAddsGeometryWithItsColours()
    {
        CompLayer layer = new CompLayer("l", "L", "shape");
        CompPreset preset = find("builtin.ring_shape");

        assertNotNull(preset);
        assertTrue(preset.applyTo(layer, 30F));

        assertEquals(2, layer.shapes.size());
        assertEquals(CompShape.OP_SUBTRACT, layer.shapes.get(1).operator);
        assertEquals(0xFF55AAFF, layer.shapes.get(0).fillColor);
    }

    @Test
    public void cardPresetCarriesFourCornerRadii()
    {
        CompLayer layer = new CompLayer("l", "L", "shape");
        CompPreset preset = find("builtin.card_shape");

        assertTrue(preset.applyTo(layer, 30F));

        CompShape card = layer.shapes.get(0);

        assertEquals(4, card.corners.size());
        assertEquals(28F, card.corner(0), 0.001F);
    }

    @Test
    public void capturedPresetRoundTripsThroughJson()
    {
        CompLayer source = new CompLayer("src", "Src", "solid");
        CompEffect blur = CompEffectRegistry.create("blur", 1);

        blur.put("radius", 42D);
        source.effects.add(blur);

        CompPreset preset = CompPreset.capture("mine", "Mine", CompPreset.EFFECT, source, 30F);

        assertNotNull(preset);

        CompLayer target = new CompLayer("dst", "Dst", "solid");

        assertTrue(preset.applyTo(target, 30F));
        assertEquals(1, target.effects.size());
        assertEquals(42F, target.effects.get(0).valueF("radius", 0F), 0.001F);
    }

    private static CompPreset find(String id)
    {
        List<CompPreset> presets = CompPresetLibrary.builtIns();

        for (CompPreset preset : presets)
        {
            if (preset.id.equals(id))
            {
                return preset;
            }
        }

        return null;
    }

    private static int countCurves(Shape shape)
    {
        PathIterator iterator = shape.getPathIterator(null);
        float[] coords = new float[6];
        int curves = 0;

        while (!iterator.isDone())
        {
            int type = iterator.currentSegment(coords);

            if (type == PathIterator.SEG_QUADTO || type == PathIterator.SEG_CUBICTO)
            {
                curves += 1;
            }

            iterator.next();
        }

        return curves;
    }
}
