package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * A compositing mask. Points are in composition pixels; in/out handles are relative to the point.
 * Mask rasterization happens on the CPU into a cached alpha texture, which keeps the GL side
 * simple and works identically in the editor and during export.
 */
public class CompMask
{
    public static final int MODE_ADD = 0;
    public static final int MODE_SUBTRACT = 1;
    public static final int MODE_INTERSECT = 2;
    public static final int MODE_EXCLUDE = 3;

    public String id = "mask";
    public String name = "Mask";
    public boolean enabled = true;
    public int mode = MODE_ADD;
    public boolean inverted = false;
    public float opacity = 1F;
    public float feather = 0F;
    public float expansion = 0F;

    public List<CompMaskPoint> points = new ArrayList<>();

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
        copy.points = new ArrayList<>();

        for (CompMaskPoint point : this.points)
        {
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
