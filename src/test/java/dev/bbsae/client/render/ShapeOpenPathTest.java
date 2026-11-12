package dev.bbsae.client.render;

import dev.bbsae.comp.CompShape;
import org.junit.jupiter.api.Test;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open vs closed shape paths.
 *
 * <p>A closed path emits a {@code SEG_CLOSE}; an open one must not, or its stroke would show a
 * seam where the pen tool never put an edge. The fill is unchanged on purpose: AWT (like After
 * Effects) fills an open outline as if it were closed, so Esc-ing out of a path does not make its
 * fill vanish.</p>
 */
public class ShapeOpenPathTest
{
    private static CompShape path(boolean closed)
    {
        CompShape shape = new CompShape("path_1", CompShape.PATH);

        shape.closed = closed;
        shape.add(0F, 0F);
        shape.add(100F, 0F);
        shape.add(100F, 100F);

        return shape;
    }

    private static int closeSegments(Shape shape)
    {
        PathIterator iterator = shape.getPathIterator(null);
        float[] coords = new float[6];
        int closes = 0;

        while (!iterator.isDone())
        {
            if (iterator.currentSegment(coords) == PathIterator.SEG_CLOSE)
            {
                closes += 1;
            }

            iterator.next();
        }

        return closes;
    }

    @Test
    public void closedPathClosesItsOutline()
    {
        Shape shape = ShapeRasterizer.buildShape(path(true));

        assertNotNull(shape);
        assertEquals(1, closeSegments(shape));
    }

    @Test
    public void openPathHasNoClosingSegment()
    {
        Shape shape = ShapeRasterizer.buildShape(path(false));

        assertNotNull(shape);
        assertEquals(0, closeSegments(shape), "staying open must not emit SEG_CLOSE");
    }

    @Test
    public void openPathStillFillsImplicitly()
    {
        /* AWT's Area closes an open outline when it fills it. AE behaves the same way: an open
         * shape path is still a filled region while its stroke stops at the last vertex. */
        Area area = new Area(ShapeRasterizer.buildShape(path(false)));

        assertFalse(area.isEmpty());
        assertTrue(area.contains(75D, 25D), "interior of the implicitly closed triangle");
        assertFalse(area.contains(25D, 75D), "outside the implicitly closed triangle");
        assertEquals(10000D, area.getBounds2D().getWidth() * area.getBounds2D().getHeight(), 1D);
    }

    @Test
    public void defaultShapeIsClosedForOldProjects()
    {
        assertTrue(new CompShape().closed);
        assertTrue(CompShape.rect("r", 0F, 0F, 10F, 10F, 0xFFFFFFFF).closed);
    }

    @Test
    public void copyKeepsClosed()
    {
        CompShape open = path(false);
        CompShape copy = open.copy();

        assertFalse(copy.closed);

        CompShape closed = path(true);

        assertTrue(closed.copy().closed);
        assertEquals(open.points.size(), copy.points.size());
    }

    @Test
    public void geometryHashIncludesClosed()
    {
        List<CompShape> open = new ArrayList<>();
        List<CompShape> closed = new ArrayList<>();

        open.add(path(false));
        closed.add(path(true));

        int openHash = ShapeCache.hash(open);

        assertNotEquals(openHash, ShapeCache.hash(closed),
            "toggling open/closed must invalidate the cached shape raster");
    }
}
