package dev.bbsae.client.render;

import dev.bbsae.comp.CompShape;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/** Turns the data-only shape model into AWT paths, then rasterizes them through {@link ShapeCache}. */
public final class ShapeRasterizer
{
    private ShapeRasterizer()
    {}

    public static Shape buildShape(CompShape shape)
    {
        Path2D.Float path = new Path2D.Float();
        float w = Math.max(0.01F, shape.width);
        float h = Math.max(0.01F, shape.height);

        switch (shape.type)
        {
            case CompShape.ELLIPSE:
                path.append(new Ellipse2D.Float(-w / 2F, -h / 2F, w, h), false);
                break;

            case CompShape.POLYGON:
                polygon(path, Math.max(3, shape.sides), w / 2F, h / 2F, shape.outerRadius);
                break;

            case CompShape.STAR:
                star(path, Math.max(3, shape.sides), w / 2F, h / 2F, shape.innerRadius, shape.outerRadius);
                break;

            case CompShape.PATH:
                buildPath(path, shape);
                break;

            case CompShape.RECT:
            default:
                if (shape.roundness > 0F)
                {
                    path.append(new RoundRectangle2D.Float(-w / 2F, -h / 2F, w, h, shape.roundness, shape.roundness), false);
                }
                else
                {
                    path.append(new java.awt.geom.Rectangle2D.Float(-w / 2F, -h / 2F, w, h), false);
                }
                break;
        }

        if (shape.rotation != 0F)
        {
            path.transform(AffineTransform.getRotateInstance(Math.toRadians(shape.rotation)));
        }

        path.transform(AffineTransform.getTranslateInstance(shape.x, shape.y));

        return trim(path, shape);
    }

    private static void polygon(Path2D.Float path, int sides, float rx, float ry, float radius)
    {
        for (int i = 0; i < sides; i++)
        {
            double angle = -Math.PI / 2D + i * Math.PI * 2D / sides;
            float x = (float) (Math.cos(angle) * rx * radius);
            float y = (float) (Math.sin(angle) * ry * radius);

            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }

        path.closePath();
    }

    private static void star(Path2D.Float path, int points, float rx, float ry, float inner, float outer)
    {
        for (int i = 0; i < points * 2; i++)
        {
            double angle = -Math.PI / 2D + i * Math.PI / points;
            float radius = (i & 1) == 0 ? outer : inner;
            float x = (float) (Math.cos(angle) * rx * radius);
            float y = (float) (Math.sin(angle) * ry * radius);

            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }

        path.closePath();
    }

    private static void buildPath(Path2D.Float path, CompShape shape)
    {
        if (shape.points.isEmpty())
        {
            return;
        }

        CompShape.Point first = shape.points.get(0);

        path.moveTo(first.x, first.y);

        for (int i = 1; i < shape.points.size(); i++)
        {
            CompShape.Point current = shape.points.get(i);
            CompShape.Point previous = shape.points.get(i - 1);

            path.curveTo(previous.x + previous.outX, previous.y + previous.outY, current.x + current.inX, current.y + current.inY, current.x, current.y);
        }

        path.closePath();
    }

    public static Shape repeat(Shape shape, CompShape data)
    {
        if (data.repeaterCount <= 0)
        {
            return shape;
        }

        Area area = new Area(shape);
        AffineTransform transform = new AffineTransform();

        for (int i = 1; i <= data.repeaterCount; i++)
        {
            AffineTransform step = new AffineTransform();

            step.translate(data.x, data.y);
            step.rotate(Math.toRadians(data.repeaterRotation * i));
            step.scale(Math.pow(Math.max(0.01F, data.repeaterScale), i), Math.pow(Math.max(0.01F, data.repeaterScale), i));
            step.translate(-data.x, -data.y);
            step.translate(data.repeaterOffsetX * i, data.repeaterOffsetY * i);

            Shape copy = step.createTransformedShape(shape);

            area.add(new Area(copy));
        }

        return area;
    }

    private static Shape trim(Shape shape, CompShape data)
    {
        float start = clamp01(data.trimStart + data.trimOffset);
        float end = clamp01(data.trimEnd + data.trimOffset);

        if (start <= 0.0001F && end >= 0.9999F)
        {
            return shape;
        }

        List<float[]> points = flatten(shape);

        if (points.size() < 2)
        {
            return shape;
        }

        float total = 0F;

        for (int i = 1; i < points.size(); i++)
        {
            total += distance(points.get(i - 1), points.get(i));
        }

        if (total <= 0.0001F)
        {
            return shape;
        }

        float from = Math.min(start, end) * total;
        float to = Math.max(start, end) * total;
        Path2D.Float path = new Path2D.Float();
        float walked = 0F;
        boolean started = false;

        for (int i = 1; i < points.size(); i++)
        {
            float[] a = points.get(i - 1);
            float[] b = points.get(i);
            float segment = distance(a, b);
            float segmentStart = walked;
            float segmentEnd = walked + segment;

            walked = segmentEnd;

            if (segmentEnd < from || segment <= 0.0001F)
            {
                continue;
            }

            if (segmentStart > to)
            {
                break;
            }

            float localFrom = Math.max(0F, (from - segmentStart) / segment);
            float localTo = Math.min(1F, (to - segmentStart) / segment);
            float x1 = a[0] + (b[0] - a[0]) * localFrom;
            float y1 = a[1] + (b[1] - a[1]) * localFrom;
            float x2 = a[0] + (b[0] - a[0]) * localTo;
            float y2 = a[1] + (b[1] - a[1]) * localTo;

            if (!started)
            {
                path.moveTo(x1, y1);
                started = true;
            }

            path.lineTo(x2, y2);
        }

        return path;
    }

    private static List<float[]> flatten(Shape shape)
    {
        List<float[]> points = new ArrayList<>();
        FlatteningPathIterator iterator = new FlatteningPathIterator(shape.getPathIterator(null), 0.5D);
        float[] coords = new float[6];

        while (!iterator.isDone())
        {
            int type = iterator.currentSegment(coords);

            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO)
            {
                points.add(new float[]{coords[0], coords[1]});
            }

            iterator.next();
        }

        return points;
    }

    private static float distance(float[] a, float[] b)
    {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];

        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp01(float value)
    {
        return Math.max(0F, Math.min(1F, value));
    }
}
