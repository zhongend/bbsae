package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** CPU-rasterized vector layers, cached by a geometry hash. */
public final class ShapeCache
{
    public static final class Raster
    {
        public final GlTexture texture;
        public final int width;
        public final int height;
        public final float originX;
        public final float originY;

        private Raster(GlTexture texture, int width, int height, float originX, float originY)
        {
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
        }
    }

    private static final Map<String, Raster> CACHE = new LinkedHashMap<>(32, 0.75F, true);
    private static final int MAX = 48;

    private ShapeCache()
    {}

    public static Raster get(CompLayer layer, float frame)
    {
        java.util.List<dev.bbsae.comp.CompShape> shapes = new java.util.ArrayList<>();

        for (dev.bbsae.comp.CompShape shape : layer.shapes)
        {
            if (shape.enabled)
            {
                shapes.add(layer.shapeAt(shape, frame));
            }
        }

        if (shapes.isEmpty())
        {
            return null;
        }

        int hash = hash(shapes);
        String key = layer.id + ":" + hash;
        Raster raster = CACHE.get(key);

        if (raster != null)
        {
            return raster;
        }

        raster = rasterize(shapes);

        if (raster != null)
        {
            CACHE.put(key, raster);

            while (CACHE.size() > MAX)
            {
                String oldest = CACHE.keySet().iterator().next();
                CACHE.remove(oldest).texture.delete();
            }
        }

        return raster;
    }

    private static int hash(java.util.List<dev.bbsae.comp.CompShape> shapes)
    {
        int hash = 17;

        for (dev.bbsae.comp.CompShape shape : shapes)
        {
            hash = hash * 31 + shape.id.hashCode();
            hash = hash * 31 + shape.type.hashCode();
            hash = hash * 31 + shape.operator;
            hash = hash * 31 + Float.floatToIntBits(shape.x);
            hash = hash * 31 + Float.floatToIntBits(shape.y);
            hash = hash * 31 + Float.floatToIntBits(shape.width);
            hash = hash * 31 + Float.floatToIntBits(shape.height);
            hash = hash * 31 + Float.floatToIntBits(shape.roundness);
            hash = hash * 31 + Float.floatToIntBits(shape.rotation);
            hash = hash * 31 + shape.sides;
            hash = hash * 31 + Float.floatToIntBits(shape.innerRadius);
            hash = hash * 31 + Float.floatToIntBits(shape.outerRadius);
            hash = hash * 31 + shape.cornerStyle;

            if (shape.corners != null)
            {
                for (Float corner : shape.corners)
                {
                    hash = hash * 31 + (corner == null ? 0 : Float.floatToIntBits(corner));
                }
            }
            hash = hash * 31 + shape.fillColor;
            hash = hash * 31 + (shape.fillGradient ? 1 : 0);
            hash = hash * 31 + shape.fillColor2;
            hash = hash * 31 + Float.floatToIntBits(shape.fillGradientAngle);
            hash = hash * 31 + shape.strokeColor;
            hash = hash * 31 + Float.floatToIntBits(shape.strokeWidth);
            hash = hash * 31 + Float.floatToIntBits(shape.trimStart);
            hash = hash * 31 + Float.floatToIntBits(shape.trimEnd);
            hash = hash * 31 + Float.floatToIntBits(shape.trimOffset);
            hash = hash * 31 + shape.repeaterCount;
            hash = hash * 31 + Float.floatToIntBits(shape.repeaterOffsetX);
            hash = hash * 31 + Float.floatToIntBits(shape.repeaterOffsetY);
            hash = hash * 31 + Float.floatToIntBits(shape.repeaterScale);
            hash = hash * 31 + Float.floatToIntBits(shape.repeaterRotation);

            for (dev.bbsae.comp.CompShape.Point point : shape.points)
            {
                hash = hash * 31 + Float.floatToIntBits(point.x);
                hash = hash * 31 + Float.floatToIntBits(point.y);
                hash = hash * 31 + Float.floatToIntBits(point.inX);
                hash = hash * 31 + Float.floatToIntBits(point.inY);
                hash = hash * 31 + Float.floatToIntBits(point.outX);
                hash = hash * 31 + Float.floatToIntBits(point.outY);
            }
        }

        return hash;
    }

    private static Raster rasterize(java.util.List<dev.bbsae.comp.CompShape> shapes)
    {
        if (shapes.isEmpty())
        {
            return null;
        }

        java.util.List<dev.bbsae.comp.CompShape> ordered = new java.util.ArrayList<>();
        java.util.List<java.awt.Shape> paths = new java.util.ArrayList<>();
        java.awt.geom.Rectangle2D bounds = null;

        for (dev.bbsae.comp.CompShape shape : shapes)
        {
            if (!shape.enabled)
            {
                continue;
            }

            java.awt.Shape path = ShapeRasterizer.buildShape(shape);

            if (path == null)
            {
                continue;
            }

            path = ShapeRasterizer.repeat(path, shape);

            ordered.add(shape);
            paths.add(path);

            java.awt.geom.Rectangle2D b = path.getBounds2D();

            bounds = bounds == null ? b : bounds.createUnion(b);
        }

        if (bounds == null || (bounds.getWidth() <= 0 && bounds.getHeight() <= 0))
        {
            return null;
        }

        float pad = 4F;

        for (dev.bbsae.comp.CompShape shape : shapes)
        {
            pad = Math.max(pad, shape.strokeWidth * 2F + 2F);
        }

        int width = Math.max(1, (int) Math.ceil(bounds.getWidth() + pad * 2F));
        int height = Math.max(1, (int) Math.ceil(bounds.getHeight() + pad * 2F));

        /* Keep pathological projects from allocating a 16k image. */
        width = Math.min(width, 4096);
        height = Math.min(height, 4096);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();

        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.translate(pad - bounds.getX(), pad - bounds.getY());

        /* Each shape paints its own fill and stroke, in list order. Before this, every shape was
         * merged into one Area and the whole layer took the last non-transparent fill colour, which
         * is exactly why "shape colour" appeared to do nothing on a multi-shape layer. Boolean
         * operators now act on the accumulated pixels (add / erase / intersect / xor), so the result
         * still reads like a shape group but keeps per-shape colour. */
        for (int i = 0; i < ordered.size(); i++)
        {
            dev.bbsae.comp.CompShape shape = ordered.get(i);
            java.awt.Shape path = paths.get(i);
            java.awt.geom.Area area = new java.awt.geom.Area(path);

            if (shape.operator == dev.bbsae.comp.CompShape.OP_SUBTRACT)
            {
                erase(g, area);
            }
            else if (shape.operator == dev.bbsae.comp.CompShape.OP_INTERSECT)
            {
                intersect(g, area, width, height);
            }
            else if (shape.operator == dev.bbsae.comp.CompShape.OP_EXCLUDE)
            {
                exclude(g, image, area, width, height, shape);
            }
            else
            {
                fill(g, area, shape);
            }

            if (shape.strokeWidth > 0F && (shape.strokeColor >>> 24) != 0)
            {
                java.awt.Shape stroked = new java.awt.BasicStroke(
                    shape.strokeWidth,
                    java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND
                ).createStrokedShape(path);

                g.setColor(new java.awt.Color(shape.strokeColor, true));
                g.fill(stroked);
            }
        }

        g.dispose();

        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        float originX = (float) bounds.getX() - pad;
        float originY = (float) bounds.getY() - pad;
        GlTexture texture = new GlTexture();

        texture.allocate(width, height);
        texture.uploadRaw(pixels);

        return new Raster(texture, width, height, originX, originY);
    }

    private static void fill(java.awt.Graphics2D g, java.awt.geom.Area area, dev.bbsae.comp.CompShape shape)
    {
        boolean filled = shape.fillGradient || (shape.fillColor >>> 24) != 0;

        if (!filled)
        {
            return;
        }

        if (shape.fillGradient)
        {
            g.setPaint(gradientPaint(shape, area.getBounds2D()));
        }
        else
        {
            g.setColor(new java.awt.Color(shape.fillColor, true));
        }

        g.fill(area);
    }

    /** Subtract: remove the shape's pixels from everything painted so far. */
    private static void erase(java.awt.Graphics2D g, java.awt.Shape area)
    {
        java.awt.Composite old = g.getComposite();

        g.setComposite(java.awt.AlphaComposite.DstOut);
        g.setColor(java.awt.Color.BLACK);
        g.fill(area);
        g.setComposite(old);
    }

    /** Intersect: keep only the accumulated pixels covered by this shape. */
    private static void intersect(java.awt.Graphics2D g, java.awt.geom.Area area, int width, int height)
    {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D mg = mask.createGraphics();

        mg.setColor(java.awt.Color.WHITE);
        mg.fill(area);
        mg.dispose();

        java.awt.Composite old = g.getComposite();

        g.setComposite(java.awt.AlphaComposite.DstIn);
        g.drawImage(mask, 0, 0, null);
        g.setComposite(old);
    }

    /** Exclude (xor): accumulated-minus-shape plus shape-minus-accumulated, drawn with own colour. */
    private static void exclude(java.awt.Graphics2D g, BufferedImage image, java.awt.geom.Area area, int width, int height, dev.bbsae.comp.CompShape shape)
    {
        BufferedImage minus = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D mg = minus.createGraphics();

        mg.drawImage(image, 0, 0, null);
        mg.setComposite(java.awt.AlphaComposite.DstOut);
        mg.setColor(java.awt.Color.BLACK);
        mg.fill(area);
        mg.dispose();

        BufferedImage only = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D og = only.createGraphics();

        og.setColor(shape.fillGradient ? java.awt.Color.WHITE : new java.awt.Color(shape.fillColor, true));
        og.fill(area);
        og.setComposite(java.awt.AlphaComposite.DstOut);
        og.drawImage(image, 0, 0, null);
        og.dispose();

        java.awt.Composite old = g.getComposite();
        java.awt.Paint oldPaint = g.getPaint();

        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(minus, 0, 0, null);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.drawImage(only, 0, 0, null);
        g.setComposite(old);
        g.setPaint(oldPaint);
    }

    /** Two-stop linear gradient across the shape bounds, in shape-local pixels. */
    private static java.awt.GradientPaint gradientPaint(dev.bbsae.comp.CompShape shape, java.awt.geom.Rectangle2D bounds)
    {
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

        return new java.awt.GradientPaint(
            (float) (cx - dx * half), (float) (cy - dy * half), new java.awt.Color(shape.fillColor, true),
            (float) (cx + dx * half), (float) (cy + dy * half), new java.awt.Color(shape.fillColor2, true)
        );
    }

    public static void clear()
    {
        for (Raster raster : CACHE.values())
        {
            raster.texture.delete();
        }

        CACHE.clear();
    }
}
