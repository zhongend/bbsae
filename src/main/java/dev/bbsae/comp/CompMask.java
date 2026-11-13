package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * A compositing mask. Points are in <b>layer-local</b> pixels; in/out handles are relative to the
 * point. The renderer applies the layer's world matrix when it rasterizes the mask.
 * Mask rasterization happens on the CPU into a cached alpha texture, which keeps the GL side
 * simple and works identically in the editor and during export.
 */
public class CompMask
{
    public static final int MODE_ADD = 0;
    public static final int MODE_SUBTRACT = 1;
    public static final int MODE_INTERSECT = 2;
    public static final int MODE_EXCLUDE = 3;
    /* EffectCraft's extra boolean modes: max, min, absolute difference and "keep the base". */
    public static final int MODE_LIGHTEN = 4;
    public static final int MODE_DARKEN = 5;
    public static final int MODE_DIFFERENCE = 6;
    public static final int MODE_NONE = 7;

    public String id = "mask";
    public String name = "Mask";
    public boolean enabled = true;
    public int mode = MODE_ADD;
    public boolean inverted = false;
    public float opacity = 1F;
    public float feather = 0F;
    public float expansion = 0F;

    public List<CompMaskPoint> points = new ArrayList<>();

    /**
     * Whether the path is closed. <b>Old project files have no such field</b>, so the default has
     * to be {@code true}: every mask that existed before the pen tool was introduced was rendered
     * closed, and loading it must not change a single pixel.
     *
     * <p>Only closed mask paths take part in matting (see {@code MaskCache}): an open path is a
     * stroke the user is still drawing, not a region, and letting it matte made the whole layer
     * vanish while the second vertex was being placed.</p>
     */
    public boolean closed = true;

    /**
     * AE's RotoBezier: when true the point handles are <b>derived</b> from the neighbouring vertices
     * (the 1/6 rule) instead of being authored, so moving a vertex keeps the curve smooth. This is
     * an editing mode, not a renderer feature: the editor writes the derived tangents back into the
     * regular in/out fields, so the rasterizers and every existing signature keep working
     * unchanged. Defaults to false — old projects keep their authored handles.
     */
    public boolean rotoBezier = false;

    public CompMask()
    {}

    public CompMask(String id)
    {
        this.id = id;
        this.name = id;
    }

    public CompMask add(float x, float y)
    {
        this.points.add(new CompMaskPoint(x, y));
        return this;
    }

    public CompMask add(float x, float y, float inX, float inY, float outX, float outY)
    {
        this.points.add(new CompMaskPoint(x, y, inX, inY, outX, outY));
        return this;
    }

    public CompMask rectangle(float x, float y, float w, float h)
    {
        this.points.clear();
        this.add(x, y);
        this.add(x + w, y);
        this.add(x + w, y + h);
        this.add(x, y + h);

        return this;
    }

    public CompMask ellipse(float cx, float cy, float rx, float ry)
    {
        this.points.clear();

        /* Four cubic-ish segments approximated with two handles each. */
        float k = 0.5522848F;

        this.add(cx, cy - ry, -rx * k, 0, rx * k, 0);
        this.add(cx + rx, cy, 0, -ry * k, 0, ry * k);
        this.add(cx, cy + ry, rx * k, 0, -rx * k, 0);
        this.add(cx - rx, cy, 0, ry * k, 0, -ry * k);

        return this;
    }

    public CompMask copy()
    {
        CompMask copy = new CompMask(this.id);

        copy.name = this.name;
        copy.enabled = this.enabled;
        copy.mode = this.mode;
        copy.inverted = this.inverted;
        copy.opacity = this.opacity;
        copy.feather = this.feather;
        copy.expansion = this.expansion;
        copy.closed = this.closed;
        copy.rotoBezier = this.rotoBezier;
        copy.points = new ArrayList<>();

        if (this.points == null)
        {
            return copy;
        }

        for (CompMaskPoint point : this.points)
        {
            if (point == null)
            {
                continue;
            }

            copy.points.add(new CompMaskPoint(point.x, point.y, point.inX, point.inY, point.outX, point.outY));
        }

        return copy;
    }

    public static final class CompMaskPoint
    {
        public float x;
        public float y;
        public float inX;
        public float inY;
        public float outX;
        public float outY;
        public boolean smooth = true;

        public CompMaskPoint()
        {}

        public CompMaskPoint(float x, float y)
        {
            this.x = x;
            this.y = y;
        }

        public CompMaskPoint(float x, float y, float inX, float inY, float outX, float outY)
        {
            this.x = x;
            this.y = y;
            this.inX = inX;
            this.inY = inY;
            this.outX = outX;
            this.outY = outY;
        }
    }
}
