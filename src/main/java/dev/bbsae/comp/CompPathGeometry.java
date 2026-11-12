package dev.bbsae.comp;

/**
 * Pure float geometry for bezier path editing (the math behind the pen tool).
 *
 * <p>Why it lives apart from the model: a mask path ({@link CompMask.CompMaskPoint}) and a shape
 * path ({@link CompShape.Point}) store the same six values per vertex but have no common
 * superclass, and the renderer and the editor must agree exactly on where a cubic segment is. One
 * shared implementation also means the whole feature can be tested with plain JUnit, with no
 * Minecraft, GL or AWT — path editing bugs are usually arithmetic, and this is where they are
 * cheapest to catch.</p>
 *
 * <p>Defensive by construction: editor input is hostile. A project may contain a path with 0 or 1
 * points, zero-length handles, NaN coefficients written by an old build, or a parent that animates
 * a scale to 0 while a drag is in flight. Every method below returns a safe value for degenerate
 * input instead of throwing, and no method allocates in a loop that a 60 fps overlay cannot
 * afford.</p>
 */
public final class CompPathGeometry
{
    /** Samples per segment for the nearest-point search (effectcraft uses the same count). */
    public static final int NEAREST_SAMPLES = 64;
    /** Bisection refinements after the coarse nearest-point scan. */
    public static final int NEAREST_REFINE = 20;
    /** Flattening steps per segment for containment / bounds. */
    public static final int FLATTEN_STEPS = 16;

    private CompPathGeometry()
    {}

    /**
     * Read-only view over a path. Both the pen session's mutable target and the two model point
     * lists can be adapted to it without copying.
     */
    public interface PathView
    {
        int count();

        float x(int index);

        float y(int index);

        float inX(int index);

        float inY(int index);

        float outX(int index);

        float outY(int index);

        boolean closed();
    }

    /** Array backed path, used by tests and by transient drag math. */
    public static final class ArrayPath implements PathView
    {
        private final float[][] points;
        private int count;
        private boolean closed;

        public ArrayPath(int capacity)
        {
            this.points = new float[Math.max(0, capacity)][];
        }

        public ArrayPath(float[][] points, boolean closed)
        {
            this.points = points == null ? new float[0][] : points;
            this.count = this.points.length;
            this.closed = closed;
        }

        public ArrayPath add(float x, float y)
        {
            return this.add(x, y, 0F, 0F, 0F, 0F);
        }

        public ArrayPath add(float x, float y, float inX, float inY, float outX, float outY)
        {
            if (this.count >= this.points.length)
            {
                throw new IndexOutOfBoundsException("ArrayPath capacity is " + this.points.length);
            }

            this.points[this.count++] = new float[] {x, y, inX, inY, outX, outY};

            return this;
        }

        public ArrayPath setClosed(boolean closed)
        {
            this.closed = closed;

            return this;
        }

        public float[] get(int index)
        {
            return this.points[index];
        }

        @Override
        public int count()
        {
            return this.count;
        }

        @Override
        public float x(int index) { return this.points[index][0]; }

        @Override
        public float y(int index) { return this.points[index][1]; }

        @Override
        public float inX(int index) { return this.points[index][2]; }

        @Override
        public float inY(int index) { return this.points[index][3]; }

        @Override
        public float outX(int index) { return this.points[index][4]; }

        @Override
        public float outY(int index) { return this.points[index][5]; }

        @Override
        public boolean closed() { return this.closed; }
    }

    /* ------------------------------------------------------------------ segments */

    /**
     * Number of cubic segments: {@code n} when closed (the last one wraps to vertex 0), {@code n-1}
     * when open. Fewer than two vertices is never a path.
     */
    public static int segmentCount(int count, boolean closed)
    {
        if (count < 2)
        {
            return 0;
        }

        return closed ? count : count - 1;
    }

    public static int segmentCount(PathView path)
    {
        return path == null ? 0 : segmentCount(path.count(), path.closed());
    }

    /** Index of the vertex that ends segment {@code segment}; -1 when the segment does not exist. */
    public static int segmentEnd(PathView path, int segment)
    {
        int count = path.count();

        if (segment < 0 || segment >= segmentCount(count, path.closed()))
        {
            return -1;
        }

        return segment + 1 >= count ? 0 : segment + 1;
    }

    /**
     * The four control points of one segment as {@code {p0x,p0y, p1x,p1y, p2x,p2y, p3x,p3y}}.
     * Returns {@code null} when the segment does not exist.
     */
    public static float[] segment(PathView path, int segment)
    {
        int end = segmentEnd(path, segment);

        if (end < 0 || segment < 0)
        {
            return null;
        }

        float x0 = finite(path.x(segment));
        float y0 = finite(path.y(segment));
        float x3 = finite(path.x(end));
        float y3 = finite(path.y(end));

        return new float[] {
            x0, y0,
            x0 + finite(path.outX(segment)), y0 + finite(path.outY(segment)),
            x3 + finite(path.inX(end)), y3 + finite(path.inY(end)),
            x3, y3
        };
    }

    /** Cubic evaluation; {@code control} is the eight-float array {@link #segment} returns. */
    public static float[] cubicAt(float[] control, float t)
    {
        if (control == null || control.length < 8)
        {
            return new float[] {0F, 0F};
        }

        float u = 1F - t;
        float a = u * u * u;
        float b = 3F * u * u * t;
        float c = 3F * u * t * t;
        float d = t * t * t;

        return new float[] {
            a * control[0] + b * control[2] + c * control[4] + d * control[6],
            a * control[1] + b * control[3] + c * control[5] + d * control[7]
        };
    }

    /**
     * Normalised tangent of a cubic at {@code t}, pointing in the direction of increasing
     * {@code t}. Falls back to the chord direction when the derivative degenerates (coincident
     * control points at an endpoint are legal and common on corner vertices).
     */
    public static float[] cubicTangent(float[] control, float t)
    {
        if (control == null || control.length < 8)
        {
            return new float[] {1F, 0F};
        }

        float u = 1F - t;
        float dx = 3F * u * u * (control[2] - control[0])
            + 6F * u * t * (control[4] - control[2])
            + 3F * t * t * (control[6] - control[4]);
        float dy = 3F * u * u * (control[3] - control[1])
            + 6F * u * t * (control[5] - control[3])
            + 3F * t * t * (control[7] - control[5]);

        if (!isUsable(dx) || !isUsable(dy) || (dx * dx + dy * dy) < 1.0E-8F)
        {
            dx = control[6] - control[0];
            dy = control[7] - control[1];
        }

        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 1.0E-6F)
        {
            return new float[] {1F, 0F};
        }

        return new float[] {dx / length, dy / length};
    }

    /** Point on segment {@code segment} at parameter {@code t}. */
    public static float[] pointOnPath(PathView path, int segment, float t)
    {
        return cubicAt(segment(path, segment), t);
    }

    /* ------------------------------------------------------------------ nearest point */

    /**
     * Nearest point on the whole path to {@code (qx,qy)}, in local units.
     *
     * @return {@code {segment, t, distance, px, py}} or {@code null} for fewer than two points.
     */
    public static float[] nearestOnPath(PathView path, float qx, float qy)
    {
        int segments = segmentCount(path);

        if (segments <= 0)
        {
            return null;
        }

        float bestDistance = Float.MAX_VALUE;
        float bestT = 0F;
        int bestSegment = -1;

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = segment(path, segment);

            if (control == null)
            {
                continue;
            }

            float localBestT = 0F;
            float localBestDistance = Float.MAX_VALUE;

            for (int i = 0; i <= NEAREST_SAMPLES; i++)
            {
                float t = i / (float) NEAREST_SAMPLES;
                float[] point = cubicAt(control, t);
                float distance = distance(point[0], point[1], qx, qy);

                if (distance < localBestDistance)
                {
                    localBestDistance = distance;
                    localBestT = t;
                }
            }

            /* Bisection-style narrowing around the best sample. A coarse scan alone leaves the
             * click point visibly off the curve on long segments (4% of the length). */
            float step = 0.5F / NEAREST_SAMPLES;

            for (int i = 0; i < NEAREST_REFINE; i++)
            {
                float before = clamp01(localBestT - step);
                float after = clamp01(localBestT + step);

                step *= 0.5F;

                float[] beforePoint = cubicAt(control, before);
                float[] afterPoint = cubicAt(control, after);
                float beforeDistance = distance(beforePoint[0], beforePoint[1], qx, qy);
                float afterDistance = distance(afterPoint[0], afterPoint[1], qx, qy);

                if (beforeDistance < localBestDistance)
                {
                    localBestDistance = beforeDistance;
                    localBestT = before;
                }

                if (afterDistance < localBestDistance)
                {
                    localBestDistance = afterDistance;
                    localBestT = after;
                }
            }

            if (localBestDistance < bestDistance)
            {
                bestDistance = localBestDistance;
                bestT = localBestT;
                bestSegment = segment;
            }
        }

        if (bestSegment < 0)
        {
            return null;
        }

        float[] point = pointOnPath(path, bestSegment, bestT);

        return new float[] {bestSegment, bestT, bestDistance, point[0], point[1]};
    }

    /* ------------------------------------------------------------------ split */

    /**
     * De Casteljau split of one segment at {@code t}, preserving the shape exactly.
     *
     * @return {@code {newX, newY, newInX, newInY, newOutX, newOutY,
     *                  startOutX, startOutY, endInX, endInY}} — the caller inserts the new vertex
     *         at {@code segment + 1} and writes the four handle corrections onto the two existing
     *         endpoints. Returns {@code null} for a missing segment.
     */
    public static float[] splitSegment(PathView path, int segment, float t)
    {
        int end = segmentEnd(path, segment);

        if (end < 0 || segment < 0)
        {
            return null;
        }

        float clamped = clamp01(finite(t));
        float x0 = finite(path.x(segment));
        float y0 = finite(path.y(segment));
        float x3 = finite(path.x(end));
        float y3 = finite(path.y(end));
        float p1x = x0 + finite(path.outX(segment));
        float p1y = y0 + finite(path.outY(segment));
        float p2x = x3 + finite(path.inX(end));
        float p2y = y3 + finite(path.inY(end));

        float ax = lerp(x0, p1x, clamped);
        float ay = lerp(y0, p1y, clamped);
        float bx = lerp(p1x, p2x, clamped);
        float by = lerp(p1y, p2y, clamped);
        float cx = lerp(p2x, x3, clamped);
        float cy = lerp(p2y, y3, clamped);
        float dx = lerp(ax, bx, clamped);
        float dy = lerp(ay, by, clamped);
        float ex = lerp(bx, cx, clamped);
        float ey = lerp(by, cy, clamped);
        float mx = lerp(dx, ex, clamped);
        float my = lerp(dy, ey, clamped);

        return new float[] {
            mx, my,
            dx - mx, dy - my,
            ex - mx, ey - my,
            ax - x0, ay - y0,
            cx - x3, cy - y3
        };
    }

    /* ------------------------------------------------------------------ containment / bounds */

    /**
     * Even-odd containment test over the flattened path.
     *
     * <p>An open path is treated as implicitly closed: that is how both After Effects and AWT
     * fill an open shape path, and it keeps "is this point inside the mask" consistent with the
     * pixel result.</p>
     */
    public static boolean insidePath(PathView path, float qx, float qy)
    {
        int count = path == null ? 0 : path.count();

        if (count < 3 || !isUsable(qx) || !isUsable(qy))
        {
            return false;
        }

        float[] polygon = flatten(path, 0, Math.min(FLATTEN_STEPS, 24));

        if (polygon == null || polygon.length < 6)
        {
            return false;
        }

        boolean inside = false;
        int length = polygon.length;

        for (int i = 0, j = length - 2; i < length; j = i, i += 2)
        {
            float xi = polygon[i];
            float yi = polygon[i + 1];
            float xj = polygon[j];
            float yj = polygon[j + 1];

            if ((yi > qy) != (yj > qy))
            {
                float cross = (xj - xi) * (qy - yi) / (yj - yi) + xi;

                if (qx < cross)
                {
                    inside = !inside;
                }
            }
        }

        return inside;
    }

    /**
     * Flattened outline of the path as {@code {x0,y0,x1,y1,...}}. The polygon is not closed
     * explicitly when the path is open — callers that need a closed polygon append the first
     * vertex themselves.
     */
    public static float[] flatten(PathView path, int stepsPerSegment, int maxPoints)
    {
        int segments = segmentCount(path);

        if (segments <= 0)
        {
            return null;
        }

        int steps = Math.max(1, stepsPerSegment);
        int estimate = Math.min(maxPoints <= 0 ? Integer.MAX_VALUE : maxPoints, segments * steps + 1);

        float[] points = new float[estimate * 2];
        int written = 0;

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = segment(path, segment);

            if (control == null)
            {
                continue;
            }

            int start = segment == 0 ? 0 : 1;

            for (int i = start; i <= steps && written < estimate; i++)
            {
                float[] point = cubicAt(control, i / (float) steps);

                points[written * 2] = point[0];
                points[written * 2 + 1] = point[1];
                written += 1;
            }
        }

        if (written == 0)
        {
            return null;
        }

        float[] result = new float[written * 2];

        System.arraycopy(points, 0, result, 0, written * 2);

        return result;
    }

    /** Tight bounds of the curve as {@code {minX,minY,maxX,maxY}}; {@code null} for empty paths. */
    public static float[] bounds(PathView path)
    {
        if (path == null || path.count() < 1)
        {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        int segments = segmentCount(path);

        if (segments <= 0)
        {
            minX = maxX = path.x(0);
            minY = maxY = path.y(0);

            return new float[] {minX, minY, maxX, maxY};
        }

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = segment(path, segment);

            if (control == null)
            {
                continue;
            }

            for (int i = 0; i <= FLATTEN_STEPS; i++)
            {
                float[] point = cubicAt(control, i / (float) FLATTEN_STEPS);

                if (!isUsable(point[0]) || !isUsable(point[1]))
                {
                    continue;
                }

                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }
        }

        if (minX > maxX)
        {
            return null;
        }

        return new float[] {minX, minY, maxX, maxY};
    }

    /* ------------------------------------------------------------------ handles / conversion */

    /**
     * Smooth tangents for one vertex, following effectcraft's Convert Vertex rule: the direction
     * is {@code next - prev} and each handle is a sixth of that distance. The returning array is
     * {@code {inX,inY, outX,outY}} (the incoming handle points backwards through the vertex).
     *
     * <p>Endpoints of an open path use their single neighbour reflected through the vertex, which
     * is what makes "convert to smooth" on the first/last point of an open path produce a tangent
     * along the path instead of a horizontal stub. A path with fewer than two points has no
     * direction at all and falls back to After Effects' {@code [10,0]} stub.</p>
     */
    public static float[] smoothTangents(PathView path, int index)
    {
        int count = path == null ? 0 : path.count();

        if (count <= 0 || index < 0 || index >= count)
        {
            return new float[] {0F, 0F, 0F, 0F};
        }

        int prev = index > 0 ? index - 1 : (path.closed() ? count - 1 : -1);
        int next = index + 1 < count ? index + 1 : (path.closed() ? 0 : -1);

        float px = finite(path.x(index));
        float py = finite(path.y(index));

        /* effectcraft's exact rule: a missing neighbour contributes the vertex itself, so an open
         * path's first/last point gets a half-tangent (next - current) / 6 instead of a stub. */
        float ax = prev >= 0 ? finite(path.x(prev)) : px;
        float ay = prev >= 0 ? finite(path.y(prev)) : py;
        float bx = next >= 0 ? finite(path.x(next)) : px;
        float by = next >= 0 ? finite(path.y(next)) : py;

        float dx = (bx - ax) / 6F;
        float dy = (by - ay) / 6F;

        if (!isUsable(dx) || !isUsable(dy) || (dx * dx + dy * dy) < 1.0E-8F)
        {
            dx = 10F;
            dy = 0F;
        }

        return new float[] {-dx, -dy, dx, dy};
    }

    /**
     * 45-degree constraint: the displacement from {@code from} to {@code to} is snapped to the
     * nearest multiple of {@code PI/4} while keeping its length. Used by Shift while drawing and
     * while dragging vertices.
     */
    public static float[] constrain45(float fromX, float fromY, float toX, float toY)
    {
        float dx = toX - fromX;
        float dy = toY - fromY;

        if (!isUsable(dx) || !isUsable(dy))
        {
            return new float[] {fromX, fromY};
        }

        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 0.0001F)
        {
            return new float[] {toX, toY};
        }

        double angle = Math.atan2(dy, dx);
        double step = Math.PI / 4D;
        double snapped = Math.round(angle / step) * step;
        float cos = (float) Math.cos(snapped);
        float sin = (float) Math.sin(snapped);

        return new float[] {fromX + cos * length, fromY + sin * length};
    }

    /** Mirror of a handle vector. Kept here so the mirror rule has exactly one implementation. */
    public static float[] mirror(float dx, float dy)
    {
        return new float[] {-dx, -dy};
    }

    /**
     * Mirrored handle clamped to {@code maxLength} (the distance to the neighbouring vertex).
     *
     * <p>The curve editor has the same rule for its tangent handles: a control point past its
     * neighbour folds the segment back on itself, which reads as a kink and cannot be edited
     * reliably. The dragged side is never clamped — only the mirror follows the neighbour.</p>
     */
    public static float[] mirrorClamped(float dx, float dy, float maxLength)
    {
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (!isUsable(length) || length < 1.0E-6F)
        {
            return new float[] {0F, 0F};
        }

        float limit = Math.max(0.0001F, maxLength);
        float scale = Math.min(1F, limit / length);

        return new float[] {-dx * scale, -dy * scale};
    }

    /* ------------------------------------------------------------------ small helpers */

    public static float distance(float ax, float ay, float bx, float by)
    {
        float dx = bx - ax;
        float dy = by - ay;

        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** True for values a path can actually be evaluated with. */
    public static boolean isUsable(float value)
    {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static float finite(float value)
    {
        return isUsable(value) ? value : 0F;
    }

    public static float clamp01(float value)
    {
        return value < 0F ? 0F : value > 1F ? 1F : value;
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }
}
