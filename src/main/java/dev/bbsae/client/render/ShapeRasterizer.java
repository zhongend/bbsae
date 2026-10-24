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

/**
 * Turns the data-only shape model into AWT paths, then rasterizes them through {@link ShapeCache}.
 *
 * <p>Corner control is per corner, not global. A rectangle exposes the four corners in reading
 * order; a triangle/polygon/star exposes one slot per vertex, starting at the top vertex and going
 * clockwise — the same order the vertices are generated in. A corner radius is measured in pixels
 * along both adjacent edges, which is how After Effects' "Round Corners" and Illustrator's corner
 * widgets behave, and it stays correct when the shape is rotated or non-uniformly scaled because it
 * is applied before those transforms.</p>
 */
public final class ShapeRasterizer
{
    private ShapeRasterizer()
    {}

    public static Shape buildShape(CompShape shape)
    {
        Path2D.Float path = new Path2D.Float();
        float w = Math.max(0.01F, shape.width);
        float h = Math.max(0.01F, shape.height);

        shape.ensureCorners();

        switch (shape.type)
        {
            case CompShape.ELLIPSE:
                path.append(new Ellipse2D.Float(-w / 2F, -h / 2F, w, h), false);
                break;

            case CompShape.TRIANGLE:
                polygon(path, 3, w / 2F, h / 2F, shape.outerRadius, shape);
                break;

            case CompShape.POLYGON:
                polygon(path, Math.max(3, shape.sides), w / 2F, h / 2F, shape.outerRadius, shape);
                break;

            case CompShape.STAR:
                star(path, Math.max(3, shape.sides), w / 2F, h / 2F, shape.innerRadius, shape.outerRadius, shape);
                break;

            case CompShape.PATH:
                buildPath(path, shape);
                break;

            case CompShape.RECT:
            default:
                rect(path, w, h, shape);
                break;
        }

        if (shape.rotation != 0F)
        {
            path.transform(AffineTransform.getRotateInstance(Math.toRadians(shape.rotation)));
        }

        path.transform(AffineTransform.getTranslateInstance(shape.x, shape.y));

        return trim(path, shape);
    }

    /**
     * Rectangle with four independent corner radii. Without per-corner radii this is the original
     * {@link RoundRectangle2D} path, so existing projects render exactly as before.
     */
    private static void rect(Path2D.Float path, float w, float h, CompShape shape)
    {
        if (!shape.hasCorners())
        {
            if (shape.roundness > 0F)
            {
                path.append(new RoundRectangle2D.Float(-w / 2F, -h / 2F, w, h, shape.roundness, shape.roundness), false);
            }
            else
            {
                path.append(new java.awt.geom.Rectangle2D.Float(-w / 2F, -h / 2F, w, h), false);
            }

            return;
        }

        float hw = w / 2F;
        float hh = h / 2F;
        float[] xs = {-hw, hw, hw, -hw};
        float[] ys = {-hh, -hh, hh, hh};
        float[] radii = {shape.corner(0), shape.corner(1), shape.corner(2), shape.corner(3)};

        roundedPolygon(path, xs, ys, radii, shape.cornerStyle);
    }

    private static void polygon(Path2D.Float path, int sides, float rx, float ry, float radius, CompShape shape)
    {
        int count = Math.max(3, sides);
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] radii = new float[count];

        for (int i = 0; i < count; i++)
        {
            double angle = -Math.PI / 2D + i * Math.PI * 2D / count;

            xs[i] = (float) (Math.cos(angle) * rx * radius);
            ys[i] = (float) (Math.sin(angle) * ry * radius);
            radii[i] = shape.corner(i);
        }

        roundedPolygon(path, xs, ys, radii, shape.cornerStyle);
    }

    private static void star(Path2D.Float path, int points, float rx, float ry, float inner, float outer, CompShape shape)
    {
        int count = points * 2;
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] radii = new float[count];

        for (int i = 0; i < count; i++)
        {
            double angle = -Math.PI / 2D + i * Math.PI / points;
            float radius = (i & 1) == 0 ? outer : inner;

            xs[i] = (float) (Math.cos(angle) * rx * radius);
            ys[i] = (float) (Math.sin(angle) * ry * radius);
            radii[i] = shape.corner(i);
        }

        roundedPolygon(path, xs, ys, radii, shape.cornerStyle);
    }

    /**
     * Polygon with a radius per vertex. A vertex whose radius is 0 emits a hard corner; otherwise
     * the path cuts r pixels along both edges and joins them with a quadratic (round) or a straight
     * line (bevel).
     */
    private static void roundedPolygon(Path2D.Float path, float[] xs, float[] ys, float[] radii, int style)
    {
        int count = xs.length;

        if (count < 3)
        {
            return;
        }

        for (int i = 0; i < count; i++)
        {
            int prev = (i + count - 1) % count;
            int next = (i + 1) % count;
            float px = xs[i];
            float py = ys[i];
            float ax = xs[prev];
            float ay = ys[prev];
            float bx = xs[next];
            float by = ys[next];
            float lenA = (float) Math.hypot(ax - px, ay - py);
            float lenB = (float) Math.hypot(bx - px, by - py);
            float radius = radii == null || i >= radii.length ? 0F : Math.max(0F, radii[i]);

            /* Never eat more than half of either adjacent edge: two adjacent corners would
             * otherwise overlap and produce a self-intersecting path. */
            radius = Math.min(radius, Math.min(lenA, lenB) * 0.5F);

            float inX = px;
            float inY = py;
            float outX = px;
            float outY = py;

            if (radius > 0.01F && lenA > 0.0001F && lenB > 0.0001F)
            {
                inX = px + (ax - px) / lenA * radius;
                inY = py + (ay - py) / lenA * radius;
                outX = px + (bx - px) / lenB * radius;
                outY = py + (by - py) / lenB * radius;
            }

            if (i == 0)
            {
                path.moveTo(inX, inY);
            }
            else
            {
                path.lineTo(inX, inY);
            }

            if (radius > 0.01F)
            {
                if (style == CompShape.CORNER_BEVEL)
                {
                    path.lineTo(outX, outY);
                }
                else
                {
                    path.quadTo(px, py, outX, outY);
                }
            }
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
