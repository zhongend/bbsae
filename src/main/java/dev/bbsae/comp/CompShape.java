package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * One vector shape item. A shape layer owns a list of these, so boolean operators can be chained
 * in the same order as a real shape group (add/subtract/intersect/exclude).
 */
public class CompShape
{
    public static final String RECT = "rect";
    public static final String ELLIPSE = "ellipse";
    public static final String POLYGON = "polygon";
    public static final String STAR = "star";
    public static final String PATH = "path";

    public static final int OP_ADD = 0;
    public static final int OP_SUBTRACT = 1;
    public static final int OP_INTERSECT = 2;
    public static final int OP_EXCLUDE = 3;

    public String id = "shape";
    public String type = RECT;
    public int operator = OP_ADD;
    public boolean enabled = true;

    public float x = 0F;
    public float y = 0F;
    public float width = 300F;
    public float height = 200F;
    public float roundness = 0F;
    public float rotation = 0F;

    public int sides = 6;
    public float innerRadius = 0.45F;
    public float outerRadius = 1F;

    public int fillColor = 0xFFFFFFFF;
    public int strokeColor = 0x00000000;
    public float strokeWidth = 0F;

    /**
     * Shape fill gradient (EffectCraft's gradient ramp, but in the shape's own local space).
     * Rasterized by {@link dev.bbsae.client.render.ShapeCache} with an AWT {@code GradientPaint},
     * so the gradient is part of the shape texture and follows every transform by construction.
     */
    public boolean fillGradient = false;
    public int fillColor2 = 0x00000000;
    public float fillGradientAngle = 90F;

    /** Trim path, as fractions of the path length. */
    public float trimStart = 0F;
    public float trimEnd = 1F;
    public float trimOffset = 0F;

    /** Repeater. */
    public int repeaterCount = 0;
    public float repeaterOffsetX = 40F;
    public float repeaterOffsetY = 0F;
    public float repeaterScale = 0.92F;
    public float repeaterRotation = 8F;
    public float repeaterOpacity = 1F;

    public List<Point> points = new ArrayList<>();

    public CompShape()
    {}

    public CompShape(String id, String type)
    {
        this.id = id;
        this.type = type;
    }

    public static CompShape rect(String id, float x, float y, float w, float h, int color)
    {
        CompShape shape = new CompShape(id, RECT);
        shape.x = x;
        shape.y = y;
        shape.width = w;
        shape.height = h;
        shape.fillColor = color;
        return shape;
    }

    public static CompShape ellipse(String id, float x, float y, float w, float h, int color)
    {
        CompShape shape = new CompShape(id, ELLIPSE);
        shape.x = x;
        shape.y = y;
        shape.width = w;
        shape.height = h;
        shape.fillColor = color;
        return shape;
    }

    public CompShape add(float px, float py)
    {
        this.points.add(new Point(px, py));
        return this;
    }

    public CompShape copy()
    {
        CompShape copy = new CompShape(this.id, this.type);

        copy.operator = this.operator;
        copy.enabled = this.enabled;
        copy.x = this.x;
        copy.y = this.y;
        copy.width = this.width;
        copy.height = this.height;
        copy.roundness = this.roundness;
        copy.rotation = this.rotation;
        copy.sides = this.sides;
        copy.innerRadius = this.innerRadius;
        copy.outerRadius = this.outerRadius;
        copy.fillColor = this.fillColor;
        copy.strokeColor = this.strokeColor;
        copy.strokeWidth = this.strokeWidth;
        copy.fillGradient = this.fillGradient;
        copy.fillColor2 = this.fillColor2;
        copy.fillGradientAngle = this.fillGradientAngle;
        copy.trimStart = this.trimStart;
        copy.trimEnd = this.trimEnd;
        copy.trimOffset = this.trimOffset;
        copy.repeaterCount = this.repeaterCount;
        copy.repeaterOffsetX = this.repeaterOffsetX;
        copy.repeaterOffsetY = this.repeaterOffsetY;
        copy.repeaterScale = this.repeaterScale;
        copy.repeaterRotation = this.repeaterRotation;
        copy.repeaterOpacity = this.repeaterOpacity;
        copy.points = new ArrayList<>();

        for (Point point : this.points)
        {
            copy.points.add(new Point(point.x, point.y, point.inX, point.inY, point.outX, point.outY));
        }

        return copy;
    }

    public static final class Point
    {
        public float x;
        public float y;
        public float inX;
        public float inY;
        public float outX;
        public float outY;

        public Point()
        {}

        public Point(float x, float y)
        {
            this.x = x;
            this.y = y;
        }

        public Point(float x, float y, float inX, float inY, float outX, float outY)
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
