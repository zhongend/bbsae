package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompShape;
import org.junit.jupiter.api.Test;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel parity and cache-identity tests for {@link ShapeCache}.
 *
 * <p>The rasterizer was reorganised into a cached primitive outline plus a per-frame transform and
 * a translation-invariant pixel cache. These tests rebuild the previous implementation inline and
 * compare its ARGB output with the new one shape type by shape type, so the refactor can only make
 * the cache smarter, never change what is drawn. The key tests below document the other half:
 * moving a shape must hit the pixel cache (only the origin changes), while rotation/trim/colour and
 * relative layout changes must miss.</p>
 */
public class ShapeCacheTest
{
    private static CompLayer layer(CompShape... shapes)
    {
        CompLayer layer = new CompLayer("shape_layer", "Shape Layer", "shape");

        for (CompShape shape : shapes)
        {
            layer.shapes.add(shape);
        }

        return layer;
    }

    private static CompShape rect(String id)
    {
        return CompShape.rect(id, 100F, 80F, 120F, 90F, 0xFF20C0E0);
    }

    /* ------------------------------------------------------------------ legacy reference */

    private static final class Legacy
    {
        final int[] pixels;
        final int width;
        final int height;
        final float originX;
        final float originY;

        Legacy(int[] pixels, int width, int height, float originX, float originY)
        {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
        }
    }

    /** The pre-refactor rasterizer, copied verbatim from the old {@code ShapeCache.rasterize}. */
    private static Legacy legacyRasterize(List<CompShape> shapes)
    {
        List<CompShape> ordered = new ArrayList<>();
        List<Shape> paths = new ArrayList<>();
        Rectangle2D bounds = null;

        for (CompShape shape : shapes)
        {
            Shape path = ShapeRasterizer.buildShape(shape);

            if (path == null)
            {
                continue;
            }

            path = ShapeRasterizer.repeat(path, shape);

            ordered.add(shape);
            paths.add(path);

            Rectangle2D box = path.getBounds2D();

            bounds = bounds == null ? box : bounds.createUnion(box);
        }

        if (bounds == null || (bounds.getWidth() <= 0 && bounds.getHeight() <= 0))
        {
            return null;
        }

        float pad = 4F;

        for (CompShape shape : shapes)
        {
            pad = Math.max(pad, shape.strokeWidth * 2F + 2F);
        }

        int width = Math.min(4096, Math.max(1, (int) Math.ceil(bounds.getWidth() + pad * 2F)));
        int height = Math.min(4096, Math.max(1, (int) Math.ceil(bounds.getHeight() + pad * 2F)));

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.translate(pad - bounds.getX(), pad - bounds.getY());

        for (int i = 0; i < ordered.size(); i++)
        {
            CompShape shape = ordered.get(i);
            Shape path = paths.get(i);
            Area area = new Area(path);

            if (shape.operator == CompShape.OP_SUBTRACT)
            {
                legacyErase(g, area);
            }
            else if (shape.operator == CompShape.OP_INTERSECT)
            {
                legacyIntersect(g, area, width, height);
            }
            else if (shape.operator == CompShape.OP_EXCLUDE)
            {
                legacyExclude(g, image, area, width, height, shape);
            }
            else
            {
                legacyFill(g, area, shape);
            }

            if (shape.strokeWidth > 0F && (shape.strokeColor >>> 24) != 0)
            {
                Shape stroked = new BasicStroke(shape.strokeWidth, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND).createStrokedShape(path);

                g.setColor(new Color(shape.strokeColor, true));
                g.fill(stroked);
            }
        }

        g.dispose();

        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

        return new Legacy(pixels, width, height, (float) bounds.getX() - pad, (float) bounds.getY() - pad);
    }

    private static void legacyFill(Graphics2D g, Area area, CompShape shape)
    {
        boolean filled = shape.fillGradient || (shape.fillColor >>> 24) != 0;

        if (!filled)
        {
            return;
        }

        if (shape.fillGradient)
        {
            Rectangle2D bounds = area.getBounds2D();
            double angle = Math.toRadians(shape.fillGradientAngle);
            double dx = Math.cos(angle);
            double dy = Math.sin(angle);
            double cx = bounds.getCenterX();
            double cy = bounds.getCenterY();
            double half = (Math.abs(bounds.getWidth() * dx) + Math.abs(bounds.getHeight() * dy)) * 0.5D;

            if (half < 0.0001D)
            {
                half = 0.5D;
            }

            g.setPaint(new GradientPaint(
                (float) (cx - dx * half), (float) (cy - dy * half), new Color(shape.fillColor, true),
                (float) (cx + dx * half), (float) (cy + dy * half), new Color(shape.fillColor2, true)
            ));
        }
        else
        {
            g.setColor(new Color(shape.fillColor, true));
        }

        g.fill(area);
    }

    private static void legacyErase(Graphics2D g, Shape area)
    {
        Composite old = g.getComposite();

        g.setComposite(AlphaComposite.DstOut);
        g.setColor(Color.BLACK);
        g.fill(area);
        g.setComposite(old);
    }

    private static void legacyIntersect(Graphics2D g, Area area, int width, int height)
    {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();

        mg.setColor(Color.WHITE);
        mg.fill(area);
        mg.dispose();

        Composite old = g.getComposite();

        g.setComposite(AlphaComposite.DstIn);
        g.drawImage(mask, 0, 0, null);
        g.setComposite(old);
    }

    private static void legacyExclude(Graphics2D g, BufferedImage image, Area area, int width, int height, CompShape shape)
    {
        BufferedImage minus = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = minus.createGraphics();

        mg.drawImage(image, 0, 0, null);
        mg.setComposite(AlphaComposite.DstOut);
        mg.setColor(Color.BLACK);
        mg.fill(area);
        mg.dispose();

        BufferedImage only = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D og = only.createGraphics();

        og.setColor(shape.fillGradient ? Color.WHITE : new Color(shape.fillColor, true));
        og.fill(area);
        og.setComposite(AlphaComposite.DstOut);
        og.drawImage(image, 0, 0, null);
        og.dispose();

        Composite old = g.getComposite();
        Paint oldPaint = g.getPaint();

        g.setComposite(AlphaComposite.Src);
        g.drawImage(minus, 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(only, 0, 0, null);
        g.setComposite(old);
        g.setPaint(oldPaint);
    }

    /* ------------------------------------------------------------------ parity tests */

    private static void assertSameRaster(CompLayer layer, float frame, String message)
    {
        List<CompShape> resolved = new ArrayList<>();

        for (CompShape shape : layer.shapes)
        {
            if (shape.enabled)
            {
                resolved.add(layer.shapeAt(shape, frame));
            }
        }

        Legacy legacy = legacyRasterize(resolved);
        ShapeCache.PixelRaster optimized = ShapeCache.pixelsForTest(layer, frame);

        assertNotNull(legacy, message);
        assertNotNull(optimized, message);
        assertEquals(legacy.width, optimized.width, message + " width");
        assertEquals(legacy.height, optimized.height, message + " height");
        assertEquals(legacy.originX, optimized.originX, 0.001F, message + " originX");
        assertEquals(legacy.originY, optimized.originY, 0.001F, message + " originY");

        int differing = 0;

        for (int i = 0; i < legacy.pixels.length; i++)
        {
            if (!sameArgb(legacy.pixels[i], optimized.pixels[i]))
            {
                differing += 1;

                if (differing <= 8)
                {
                    int x = i % legacy.width;
                    int y = i / legacy.width;

                    throw new AssertionError(message + ": pixel " + x + "," + y + " legacy "
                        + Integer.toHexString(legacy.pixels[i]) + " vs optimized "
                        + Integer.toHexString(optimized.pixels[i]));
                }
            }
        }

        assertEquals(0, differing, message + ": differing pixels");
    }

    private static boolean sameArgb(int a, int b)
    {
        if (a == b)
        {
            return true;
        }

        for (int shift = 0; shift <= 24; shift += 8)
        {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;

            if (Math.abs(ca - cb) > 1)
            {
                return false;
            }
        }

        return true;
    }

    @Test
    public void filledRectangleMatchesTheLegacyRaster()
    {
        assertSameRaster(layer(rect("r")), 0F, "filled rectangle");
    }

    @Test
    public void gradientAndStrokeMatchTheLegacyRaster()
    {
        CompShape shape = rect("gradient");

        shape.fillGradient = true;
        shape.fillColor = 0xFFFF4040;
        shape.fillColor2 = 0xFF2040FF;
        shape.fillGradientAngle = 35F;
        shape.strokeColor = 0xFF101010;
        shape.strokeWidth = 6F;

        assertSameRaster(layer(shape), 0F, "gradient + stroke");
    }

    @Test
    public void everyBooleanOperatorMatchesTheLegacyRaster()
    {
        CompShape add = rect("add");
        CompShape subtract = rect("subtract");

        subtract.x = 140F;
        subtract.y = 100F;
        subtract.operator = CompShape.OP_SUBTRACT;

        CompShape intersect = CompShape.ellipse("intersect", 110F, 90F, 90F, 70F, 0x80FFFFFF);

        intersect.operator = CompShape.OP_INTERSECT;

        CompShape exclude = CompShape.ellipse("exclude", 150F, 110F, 60F, 60F, 0xFFFFAA00);

        exclude.operator = CompShape.OP_EXCLUDE;

        assertSameRaster(layer(add, subtract, intersect, exclude), 0F,
            "add/subtract/intersect/exclude");
    }

    @Test
    public void rotatedAndScaledShapesMatchTheLegacyRaster()
    {
        CompShape shape = rect("rotated");

        shape.rotation = 33F;
        shape.x = 150F;
        shape.y = 120F;

        CompShape path = new CompShape("curve", CompShape.PATH);

        path.closed = false;
        path.add(40F, 30F);
        path.add(120F, 10F);
        path.add(160F, 90F);
        path.strokeColor = 0xFFFFFFFF;
        path.strokeWidth = 4F;

        assertSameRaster(layer(shape, path), 0F, "rotated rect + open bezier path");
    }

    @Test
    public void trimmedAndRepeatedShapesMatchTheLegacyRaster()
    {
        CompShape trimmed = rect("trimmed");

        trimmed.trimStart = 0.2F;
        trimmed.trimEnd = 0.8F;
        trimmed.strokeColor = 0xFFFFFFFF;
        trimmed.strokeWidth = 3F;

        CompShape repeated = rect("repeated");

        repeated.repeaterCount = 3;
        repeated.repeaterOffsetX = 24F;
        repeated.repeaterRotation = 12F;
        repeated.repeaterScale = 0.85F;

        assertSameRaster(layer(trimmed, repeated), 0F, "trim + repeater");
    }

    /* ------------------------------------------------------------------ key tests */

    @Test
    public void staticShapeKeepsOneKeyAcross120Frames()
    {
        CompLayer layer = layer(rect("r"));
        long key = ShapeCache.contentKey(layer, 0F);

        for (int frame = 1; frame <= 120; frame++)
        {
            assertEquals(key, ShapeCache.contentKey(layer, frame / 30F),
                "a static shape must keep its pixel-cache key at frame " + frame);
        }
    }

    @Test
    public void translationOnlyAnimationHitsThePixelCache()
    {
        CompLayer layer = layer(rect("r"));
        String path = CompLayer.shapePath("r", "x");

        layer.scalarChannel(path).insert(0F, 100F);
        layer.scalarChannel(path).insert(10F, 150F);

        long start = ShapeCache.contentKey(layer, 0F);
        long end = ShapeCache.contentKey(layer, 10F);

        assertEquals(start, end, "pure translation must not change the raster identity");

        ShapeCache.PixelRaster first = ShapeCache.pixelsForTest(layer, 0F);
        ShapeCache.PixelRaster moved = ShapeCache.pixelsForTest(layer, 10F);

        assertNotNull(first);
        assertNotNull(moved);
        assertEquals(first.width, moved.width);
        assertEquals(first.height, moved.height);
        assertEquals(first.originX + 50F, moved.originX, 0.01F, "only the origin moves");
        assertEquals(first.originY, moved.originY, 0.01F);

        int differing = 0;

        for (int i = 0; i < first.pixels.length; i++)
        {
            if (!sameArgb(first.pixels[i], moved.pixels[i]))
            {
                differing += 1;
            }
        }

        assertEquals(0, differing, "translated pixels must be identical in the layer-local raster");
    }

    @Test
    public void movingOneShapeOfTwoChangesTheKey()
    {
        CompShape first = rect("a");
        CompShape second = rect("b");

        second.x = 260F;

        CompLayer layer = layer(first, second);
        long key = ShapeCache.contentKey(layer, 0F);

        second.x = 300F;

        assertNotEquals(key, ShapeCache.contentKey(layer, 0F),
            "relative layout is part of the pixels, so it must invalidate the raster");
    }

    @Test
    public void rotationTrimAndColourInvalidateTheRaster()
    {
        CompShape shape = rect("r");
        CompLayer layer = layer(shape);
        long key = ShapeCache.contentKey(layer, 0F);

        shape.rotation = 15F;
        long rotated = ShapeCache.contentKey(layer, 0F);

        assertNotEquals(key, rotated, "rotation changes pixels");

        shape.trimEnd = 0.5F;
        long trimmed = ShapeCache.contentKey(layer, 0F);

        assertNotEquals(rotated, trimmed, "trim changes the outline");

        shape.fillColor = 0xFF00FF00;
        assertNotEquals(trimmed, ShapeCache.contentKey(layer, 0F), "fill colour changes pixels");
    }

    @Test
    public void repeatedShapeLayerUsesAbsolutePositions()
    {
        CompShape shape = rect("repeat");

        shape.repeaterCount = 2;

        CompLayer layer = layer(shape);
        long key = ShapeCache.contentKey(layer, 0F);

        shape.x += 40F;

        assertNotEquals(key, ShapeCache.contentKey(layer, 0F),
            "a repeater rotates about the shape's own position, so absolute x matters");
        assertTrue(ShapeCache.pixelsForTest(layer, 0F) != null);
    }
}
