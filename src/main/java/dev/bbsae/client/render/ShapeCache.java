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
            hash = hash * 31 + shape.fillColor;
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

        java.awt.geom.Area combined = new java.awt.geom.Area();
        java.util.List<java.awt.Shape> strokes = new java.util.ArrayList<>();
        java.util.List<Integer> strokeColors = new java.util.ArrayList<>();
        java.awt.geom.Rectangle2D bounds = null;
        int fillColor = 0;

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

            java.awt.geom.Area area = new java.awt.geom.Area(path);

            if (shape.operator == dev.bbsae.comp.CompShape.OP_SUBTRACT)
            {
                combined.subtract(area);
            }
            else if (shape.operator == dev.bbsae.comp.CompShape.OP_INTERSECT)
            {
                combined.intersect(area);
            }
            else if (shape.operator == dev.bbsae.comp.CompShape.OP_EXCLUDE)
            {
                combined.exclusiveOr(area);
            }
            else
            {
                combined.add(area);
            }

            if (shape.strokeWidth > 0F && (shape.strokeColor >>> 24) != 0)
            {
                strokes.add(new java.awt.BasicStroke(shape.strokeWidth, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND).createStrokedShape(path));
                strokeColors.add(shape.strokeColor);
            }

            if ((shape.fillColor >>> 24) != 0)
            {
                fillColor = shape.fillColor;
            }

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

        if ((fillColor >>> 24) != 0)
        {
            g.setColor(new java.awt.Color(fillColor, true));
            g.fill(combined);
        }

        for (int i = 0; i < strokes.size(); i++)
        {
            g.setColor(new java.awt.Color(strokeColors.get(i), true));
            g.fill(strokes.get(i));
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

    public static void clear()
    {
        for (Raster raster : CACHE.values())
        {
            raster.texture.delete();
        }

        CACHE.clear();
    }
}
