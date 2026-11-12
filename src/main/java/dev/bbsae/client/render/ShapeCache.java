package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompShape;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * CPU-rasterized vector layers.
 *
 * <p>Three caches, each with its own invalidation domain, so a keyframed transform does not throw
 * away everything:</p>
 * <ul>
 *   <li><b>geometry</b> — the outline of one shape without rotation/translation/trim, keyed per
 *       pooled shape object. Rotating or moving a rect no longer rebuilds its AWT path.</li>
 *   <li><b>raster</b> — the painted pixels, keyed <b>without absolute translation</b>: moving all
 *       shapes of a layer by the same delta shifts only {@link Raster#originX}/{@code originY}, so
 *       a position-keyframed layer reuses the same texture and simply returns a new origin.</li>
 *   <li><b>texture</b> — when the pixels really do change (rotation, trim, colour) the layer's
 *       previous GL texture is re-uploaded instead of allocating+deleting one per frame.</li>
 * </ul>
 *
 * <p>The paint order and AWT operations are deliberately unchanged (per-shape fill, stroke,
 * add/subtract/intersect/exclude in list order, gradient in shape-local pixels), so the cached
 * pixels match the previous rasterizer.</p>
 */
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

    private static final Map<String, CachedRaster> CACHE = new LinkedHashMap<>(32, 0.75F, true);
    private static final int MAX = 48;

    /** Most recent raster per layer, the texture that a new upload of that layer can reuse. */
    private static final Map<String, CachedRaster> RECENT = new LinkedHashMap<>(16, 0.75F, true);
    private static final int MAX_RECENT = 32;

    /** Primitive outline per pooled shape object; see {@link #geometry(CompShape)}. */
    private static final Map<CompShape, GeometryEntry> GEOMETRY =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Resolved-shape scratch per layer, safe against nested resolves (precomp recursion). */
    private static final Map<CompLayer, LayerScratch> SCRATCH =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final int MAX_DEPTH = 8;

    /** Per-size AWT image buffers, reused across frames. */
    private static final List<ShapeWork> WORK = new ArrayList<>();
    private static final int MAX_WORK = 2;

    private ShapeCache()
    {}

    public static synchronized Raster get(CompLayer layer, float frame)
    {
        if (layer == null || layer.shapes == null || layer.shapes.isEmpty())
        {
            return null;
        }

        LayerScratch scratch = layerScratch(layer);
        int depth = scratch.enter();
        StackScratch stack = depth < 0 ? new StackScratch() : scratch.slot(depth);

        try
        {
            resolveShapes(layer, frame, stack);

            if (stack.count == 0)
            {
                return null;
            }

            long content = contentHash(stack);
            String key = layer.id + ":" + Long.toHexString(content);
            CachedRaster cached = CACHE.get(key);

            if (cached != null && cached.width > 0)
            {
                float dx = stack.anchorX - cached.anchorX;
                float dy = stack.anchorY - cached.anchorY;

                if (dx == 0F && dy == 0F && cached.raster != null)
                {
                    return cached.raster;
                }

                return new Raster(cached.texture, cached.width, cached.height,
                    cached.originX + dx, cached.originY + dy);
            }

            /* Cache miss: build the outline list (geometry cache + per-frame transform), measure
             * the union, then paint into a reused image. */
            Rectangle2D bounds = buildPaths(stack);

            if (bounds == null || (bounds.getWidth() <= 0 && bounds.getHeight() <= 0))
            {
                return null;
            }

            float pad = pad(layer, stack);

            int width = Math.max(1, (int) Math.ceil(bounds.getWidth() + pad * 2F));
            int height = Math.max(1, (int) Math.ceil(bounds.getHeight() + pad * 2F));

            /* Keep pathological projects from allocating a 16k image. */
            width = Math.min(width, 4096);
            height = Math.min(height, 4096);

            ShapeWork work = workFor(width, height);

            paint(stack, work, bounds, pad);

            float originX = (float) bounds.getX() - pad;
            float originY = (float) bounds.getY() - pad;
            GlTexture texture = upload(layer, key, width, height, work);

            CachedRaster entry = new CachedRaster(key, layer.id, texture, width, height,
                originX, originY, stack.anchorX, stack.anchorY);

            entry.raster = new Raster(texture, width, height, originX, originY);
            CACHE.put(key, entry);
            RECENT.put(layer.id, entry);
            trimRecent();

            return entry.raster;
        }
        finally
        {
            if (depth >= 0)
            {
                scratch.leave();
            }
        }
    }

    /**
     * Package-private so the open/closed regression test can prove the flag invalidates the cache.
     *
     * <p>Kept as the "old" 32-bit signature for the existing test; production uses
     * {@link #contentHash(StackScratch)}, which is 64-bit and translation-normalised.</p>
     */
    static int hash(java.util.List<CompShape> shapes)
    {
        int hash = 17;

        for (CompShape shape : shapes)
        {
            hash = hash * 31 + shape.id.hashCode();
            hash = hash * 31 + shape.type.hashCode();
            hash = hash * 31 + shape.operator;
            /* Open vs closed changes the raster's stroke seam, so it must invalidate the geometry
             * cache; without it toggling the flag on a selected path kept serving the old texture. */
            hash = hash * 31 + (shape.closed ? 1 : 0);
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

    /* ---------------------------------------------------------------- resolution */

    private static void resolveShapes(CompLayer layer, float frame, StackScratch stack)
    {
        int count = 0;

        stack.absolute = false;
        stack.anchorX = 0F;
        stack.anchorY = 0F;

        if (layer.shapes != null)
        for (CompShape shape : layer.shapes)
        {
            if (shape == null || !shape.enabled)
            {
                continue;
            }

            CompShape resolved;

            if (count < stack.shapes.size())
            {
                resolved = stack.shapes.get(count);
            }
            else
            {
                resolved = new CompShape();
                stack.shapes.add(resolved);
            }

            layer.shapeAtInto(shape, frame, resolved);
            count += 1;

            /* The first enabled shape is the translation anchor: a position keyframe moves the
             * whole layer, so relative positions (and therefore the pixels) stay the same. */
            if (count == 1)
            {
                stack.anchorX = resolved.x;
                stack.anchorY = resolved.y;
            }

            /* A repeater rotates around the shape's own x/y, so absolute positions change the
             * pixels; fall back to an absolute key for those layers. */
            if (resolved.repeaterCount > 0)
            {
                stack.absolute = true;
            }
        }

        stack.count = count;

        if (stack.absolute)
        {
            stack.anchorX = 0F;
            stack.anchorY = 0F;
        }
    }

    private static long contentHash(StackScratch stack)
    {
        long hash = 17L;

        hash = mix(hash, stack.count);

        for (int i = 0; i < stack.count; i++)
        {
            CompShape shape = stack.shapes.get(i);
            float x = shape.x - stack.anchorX;
            float y = shape.y - stack.anchorY;

            hash = mix(hash, shape.type == null ? 0 : shape.type.hashCode());
            hash = mix(hash, shape.operator);
            hash = mix(hash, shape.closed);
            hash = mix(hash, x);
            hash = mix(hash, y);
            hash = mix(hash, shape.width);
            hash = mix(hash, shape.height);
            hash = mix(hash, shape.roundness);
            hash = mix(hash, shape.rotation);
            hash = mix(hash, shape.sides);
            hash = mix(hash, shape.innerRadius);
            hash = mix(hash, shape.outerRadius);
            hash = mix(hash, shape.cornerStyle);
            hash = mix(hash, shape.fillColor);
            hash = mix(hash, shape.fillGradient);
            hash = mix(hash, shape.fillColor2);
            hash = mix(hash, shape.fillGradientAngle);
            hash = mix(hash, shape.strokeColor);
            hash = mix(hash, shape.strokeWidth);
            hash = mix(hash, shape.trimStart);
            hash = mix(hash, shape.trimEnd);
            hash = mix(hash, shape.trimOffset);
            hash = mix(hash, shape.repeaterCount);
            hash = mix(hash, shape.repeaterOffsetX);
            hash = mix(hash, shape.repeaterOffsetY);
            hash = mix(hash, shape.repeaterScale);
            hash = mix(hash, shape.repeaterRotation);
            hash = mix(hash, shape.repeaterOpacity);

            int corners = shape.corners == null ? 0 : shape.corners.size();

            hash = mix(hash, corners);

            for (int c = 0; c < corners; c++)
            {
                Float corner = shape.corners.get(c);

                hash = mix(hash, corner == null ? 0F : corner);
            }

            for (CompShape.Point point : shape.points)
            {
                hash = mix(hash, point.x);
                hash = mix(hash, point.y);
                hash = mix(hash, point.inX);
                hash = mix(hash, point.inY);
                hash = mix(hash, point.outX);
                hash = mix(hash, point.outY);
            }
        }

        return hash;
    }

    private static long mix(long hash, long value)
    {
        hash ^= value;
        hash *= 0x100000001B3L;

        return hash;
    }

    private static long mix(long hash, int value)
    {
        return mix(hash, value & 0xFFFFFFFFL);
    }

    private static long mix(long hash, float value)
    {
        return mix(hash, Float.floatToIntBits(value) & 0xFFFFFFFFL);
    }

    private static long mix(long hash, boolean value)
    {
        return mix(hash, value ? 1L : 0L);
    }

    /* ---------------------------------------------------------------- outline building */

    /**
     * Builds every drawable shape's path for this frame and returns the union of their bounds.
     *
     * <p>When no trim is active the outline is assembled from the cached primitive geometry plus
     * the per-frame rotate/translate — exactly the transform order of
     * {@link ShapeRasterizer#buildShape} (primitive, rotate, translate). With an active trim the
     * original builder is used, because trimming measures the flattened path and lives there; that
     * keeps the trim maths bit-identical while still caching the pixels of a moving layer.</p>
     */
    private static Rectangle2D buildPaths(StackScratch stack)
    {
        stack.drawable.clear();
        stack.paths.clear();

        Rectangle2D bounds = null;

        for (int i = 0; i < stack.count; i++)
        {
            CompShape shape = stack.shapes.get(i);
            Shape path = buildPath(shape, stack, i);

            if (path == null)
            {
                continue;
            }

            path = ShapeRasterizer.repeat(path, shape);

            stack.drawable.add(shape);
            stack.paths.add(path);

            Rectangle2D box = path.getBounds2D();

            bounds = bounds == null ? box : bounds.createUnion(box);
        }

        return bounds;
    }

    private static Shape buildPath(CompShape shape, StackScratch stack, int index)
    {
        if (!trimNeutral(shape))
        {
            return ShapeRasterizer.buildShape(shape);
        }

        Shape base = geometry(shape);

        if (base == null)
        {
            return null;
        }

        Path2D.Float path = stack.path(index);

        path.reset();
        path.append(base, false);

        if (shape.rotation != 0F)
        {
            stack.rotate.setToRotation(Math.toRadians(shape.rotation));
            path.transform(stack.rotate);
        }

        stack.translate.setToTranslation(shape.x, shape.y);
        path.transform(stack.translate);

        return path;
    }

    /** The same neutral-trim test {@link ShapeRasterizer} uses before it flattens the path. */
    private static boolean trimNeutral(CompShape shape)
    {
        float start = clamp01(shape.trimStart + shape.trimOffset);
        float end = clamp01(shape.trimEnd + shape.trimOffset);

        return start <= 0.0001F && end >= 0.9999F;
    }

    private static float clamp01(float value)
    {
        return Math.max(0F, Math.min(1F, value));
    }

    /**
     * The shape's primitive outline, without rotation, translation or trim.
     *
     * <p>Keyed by the pooled shape object that {@link #resolveShapes} fills, so the hash comparison
     * is one long compare per shape per frame instead of an AWT path rebuild. The value is a
     * {@link Path2D} in shape-local coordinates; callers append it into their own work path.</p>
     */
    private static Shape geometry(CompShape shape)
    {
        long hash = geometryHash(shape);
        GeometryEntry entry = GEOMETRY.get(shape);

        if (entry != null && entry.hash == hash)
        {
            return entry.path;
        }

        /* Build from a localised copy: buildShape() applies rotation/translation/trim itself, and
         * those are exactly the parts we want to keep out of the geometry cache. */
        CompShape local = shape.copy();

        local.x = 0F;
        local.y = 0F;
        local.rotation = 0F;
        local.trimStart = 0F;
        local.trimEnd = 1F;
        local.trimOffset = 0F;
        local.repeaterCount = 0;

        Shape path = ShapeRasterizer.buildShape(local);

        if (path == null)
        {
            return null;
        }

        GEOMETRY.put(shape, new GeometryEntry(hash, path));

        while (GEOMETRY.size() > 64)
        {
            java.util.Iterator<CompShape> iterator = GEOMETRY.keySet().iterator();

            if (iterator.hasNext())
            {
                iterator.next();
                iterator.remove();
            }
            else
            {
                break;
            }
        }

        return path;
    }

    private static long geometryHash(CompShape shape)
    {
        long hash = 17L;

        hash = mix(hash, shape.type == null ? 0 : shape.type.hashCode());
        hash = mix(hash, shape.width);
        hash = mix(hash, shape.height);
        hash = mix(hash, shape.roundness);
        hash = mix(hash, shape.sides);
        hash = mix(hash, shape.innerRadius);
        hash = mix(hash, shape.outerRadius);
        hash = mix(hash, shape.cornerStyle);
        hash = mix(hash, shape.closed);

        int corners = shape.corners == null ? 0 : shape.corners.size();

        hash = mix(hash, corners);

        for (int i = 0; i < corners; i++)
        {
            Float corner = shape.corners.get(i);

            hash = mix(hash, corner == null ? 0F : corner);
        }

        for (CompShape.Point point : shape.points)
        {
            hash = mix(hash, point.x);
            hash = mix(hash, point.y);
            hash = mix(hash, point.inX);
            hash = mix(hash, point.inY);
            hash = mix(hash, point.outX);
            hash = mix(hash, point.outY);
        }

        return hash;
    }

    private static float pad(CompLayer layer, StackScratch stack)
    {
        float pad = 4F;

        for (int i = 0; i < stack.count; i++)
        {
            pad = Math.max(pad, stack.shapes.get(i).strokeWidth * 2F + 2F);
        }

        return pad;
    }

    /* ---------------------------------------------------------------- painting */

    private static void paint(StackScratch stack, ShapeWork work, Rectangle2D bounds, float pad)
    {
        BufferedImage image = work.image;
        Graphics2D g = work.graphics;

        /* Reset the reusable graphics state: clear the previous frame's pixels, drop the old
         * translation and restore the default SrcOver composite. A fresh identity transform is
         * passed because Graphics2D may mutate the instance it is given. */
        g.setTransform(new AffineTransform());
        g.setComposite(AlphaComposite.Clear);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, work.width, work.height);
        g.setComposite(AlphaComposite.SrcOver);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.translate(pad - bounds.getX(), pad - bounds.getY());

        /* Each shape paints its own fill and stroke, in list order. Boolean operators act on the
         * accumulated pixels (add / erase / intersect / xor), so the result still reads like a
         * shape group but keeps per-shape colour. */
        for (int i = 0; i < stack.drawable.size(); i++)
        {
            CompShape shape = stack.drawable.get(i);
            Shape path = stack.paths.get(i);
            Area area = new Area(path);

            if (shape.operator == CompShape.OP_SUBTRACT)
            {
                erase(g, area);
            }
            else if (shape.operator == CompShape.OP_INTERSECT)
            {
                intersect(g, area, work.width, work.height);
            }
            else if (shape.operator == CompShape.OP_EXCLUDE)
            {
                exclude(g, image, area, work.width, work.height, shape);
            }
            else
            {
                fill(g, area, shape);
            }

            if (shape.strokeWidth > 0F && (shape.strokeColor >>> 24) != 0)
            {
                Shape stroked = new BasicStroke(
                    shape.strokeWidth,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
                ).createStrokedShape(path);

                g.setColor(new Color(shape.strokeColor, true));
                g.fill(stroked);
            }
        }

        g.setTransform(new AffineTransform());
    }

    private static void fill(Graphics2D g, Area area, CompShape shape)
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
            g.setColor(new Color(shape.fillColor, true));
        }

        g.fill(area);
    }

    /** Subtract: remove the shape's pixels from everything painted so far. */
    private static void erase(Graphics2D g, Shape area)
    {
        Composite old = g.getComposite();

        g.setComposite(AlphaComposite.DstOut);
        g.setColor(Color.BLACK);
        g.fill(area);
        g.setComposite(old);
    }

    /** Intersect: keep only the accumulated pixels covered by this shape. */
    private static void intersect(Graphics2D g, Area area, int width, int height)
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

    /** Exclude (xor): accumulated-minus-shape plus shape-minus-accumulated, drawn with own colour. */
    private static void exclude(Graphics2D g, BufferedImage image, Area area, int width, int height, CompShape shape)
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

    /** Two-stop linear gradient across the shape bounds, in shape-local pixels. */
    private static GradientPaint gradientPaint(CompShape shape, Rectangle2D bounds)
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

        return new GradientPaint(
            (float) (cx - dx * half), (float) (cy - dy * half), new Color(shape.fillColor, true),
            (float) (cx + dx * half), (float) (cy + dy * half), new Color(shape.fillColor2, true)
        );
    }

    /* ---------------------------------------------------------------- texture upload */

    /**
     * Uploads the painted pixels into the layer's previous texture when the size still matches.
     *
     * <p>The texture belongs to exactly one cache entry at a time: a new upload removes the old
     * entry (its pixels are about to be overwritten), so a cache hit can never return a texture
     * that was silently re-used for a different state. This is what stops a keyframed rotation or
     * trim animation from allocating and deleting a GL texture every frame.</p>
     */
    private static GlTexture upload(CompLayer layer, String key, int width, int height, ShapeWork work)
    {
        CachedRaster recent = RECENT.get(layer.id);
        GlTexture texture;

        if (recent != null && recent.width == width && recent.height == height && recent.texture.id > 0)
        {
            texture = recent.texture;
            CACHE.remove(recent.key);
        }
        else
        {
            texture = new GlTexture();
            texture.allocate(width, height);
        }

        texture.uploadRaw(work.pixels);

        return texture;
    }

    private static void trimRecent()
    {
        while (CACHE.size() > MAX)
        {
            String oldest = CACHE.keySet().iterator().next();
            CachedRaster removed = CACHE.remove(oldest);

            if (removed != null)
            {
                CachedRaster recent = RECENT.get(removed.layerId);

                if (recent == removed)
                {
                    RECENT.remove(removed.layerId);
                }

                removed.texture.delete();
            }
        }

        while (RECENT.size() > MAX_RECENT)
        {
            String oldest = RECENT.keySet().iterator().next();

            RECENT.remove(oldest);
        }
    }

    /* ---------------------------------------------------------------- scratch */

    private static LayerScratch layerScratch(CompLayer layer)
    {
        synchronized (SCRATCH)
        {
            LayerScratch scratch = SCRATCH.get(layer);

            if (scratch == null)
            {
                scratch = new LayerScratch();
                SCRATCH.put(layer, scratch);
            }

            return scratch;
        }
    }

    private static ShapeWork workFor(int width, int height)
    {
        for (int i = 0; i < WORK.size(); i++)
        {
            ShapeWork work = WORK.get(i);

            if (work.width == width && work.height == height)
            {
                WORK.remove(i);
                WORK.add(work);

                return work;
            }
        }

        ShapeWork work = new ShapeWork(width, height);

        WORK.add(work);

        while (WORK.size() > MAX_WORK)
        {
            WORK.remove(0);
        }

        return work;
    }

    public static synchronized void clear()
    {
        for (CachedRaster raster : CACHE.values())
        {
            raster.texture.delete();
        }

        CACHE.clear();
        RECENT.clear();
        GEOMETRY.clear();
        SCRATCH.clear();
        WORK.clear();
    }

    /* ---------------------------------------------------------------- test hooks */

    /** World/translation-normalised content identity used as the pixel-cache key. */
    static synchronized long contentKey(CompLayer layer, float frame)
    {
        if (layer == null)
        {
            return 0L;
        }

        StackScratch stack = new StackScratch();

        resolveShapes(layer, frame, stack);

        return contentHash(stack);
    }

    /**
     * GL-free rasterization for pixel parity and cache tests: returns the painted ARGB pixels,
     * their size and the origin, without uploading a texture.
     */
    static synchronized PixelRaster pixelsForTest(CompLayer layer, float frame)
    {
        if (layer == null || layer.shapes == null || layer.shapes.isEmpty())
        {
            return null;
        }

        StackScratch stack = new StackScratch();

        resolveShapes(layer, frame, stack);

        if (stack.count == 0)
        {
            return null;
        }

        Rectangle2D bounds = buildPaths(stack);

        if (bounds == null || (bounds.getWidth() <= 0 && bounds.getHeight() <= 0))
        {
            return null;
        }

        float pad = pad(layer, stack);
        int width = Math.min(4096, Math.max(1, (int) Math.ceil(bounds.getWidth() + pad * 2F)));
        int height = Math.min(4096, Math.max(1, (int) Math.ceil(bounds.getHeight() + pad * 2F)));
        ShapeWork work = new ShapeWork(width, height);

        paint(stack, work, bounds, pad);

        return new PixelRaster(Arrays.copyOf(work.pixels, work.pixels.length), width, height,
            (float) bounds.getX() - pad, (float) bounds.getY() - pad);
    }

    static final class PixelRaster
    {
        final int[] pixels;
        final int width;
        final int height;
        final float originX;
        final float originY;

        PixelRaster(int[] pixels, int width, int height, float originX, float originY)
        {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
        }
    }

    /* ---------------------------------------------------------------- types */

    private static final class CachedRaster
    {
        final String key;
        final String layerId;
        final GlTexture texture;
        final int width;
        final int height;
        final float originX;
        final float originY;
        final float anchorX;
        final float anchorY;
        Raster raster;

        CachedRaster(String key, String layerId, GlTexture texture, int width, int height,
                     float originX, float originY, float anchorX, float anchorY)
        {
            this.key = key;
            this.layerId = layerId;
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }
    }

    private static final class GeometryEntry
    {
        final long hash;
        final Shape path;

        GeometryEntry(long hash, Shape path)
        {
            this.hash = hash;
            this.path = path;
        }
    }

    private static final class StackScratch
    {
        final ArrayList<CompShape> shapes = new ArrayList<>();
        final ArrayList<CompShape> drawable = new ArrayList<>();
        final ArrayList<Shape> paths = new ArrayList<>();
        final AffineTransform rotate = new AffineTransform();
        final AffineTransform translate = new AffineTransform();
        final ArrayList<Path2D.Float> workPaths = new ArrayList<>();
        int count;
        float anchorX;
        float anchorY;
        boolean absolute;

        Path2D.Float path(int index)
        {
            while (this.workPaths.size() <= index)
            {
                this.workPaths.add(new Path2D.Float());
            }

            return this.workPaths.get(index);
        }
    }

    private static final class LayerScratch
    {
        private final StackScratch[] slots = new StackScratch[MAX_DEPTH];
        private int depth;

        synchronized int enter()
        {
            if (this.depth >= MAX_DEPTH)
            {
                return -1;
            }

            return this.depth++;
        }

        synchronized void leave()
        {
            if (this.depth > 0)
            {
                this.depth -= 1;
            }
        }

        StackScratch slot(int index)
        {
            StackScratch slot = this.slots[index];

            if (slot == null)
            {
                slot = new StackScratch();
                this.slots[index] = slot;
            }

            return slot;
        }
    }

    private static final class ShapeWork
    {
        final int width;
        final int height;
        final BufferedImage image;
        final Graphics2D graphics;
        final int[] pixels;

        ShapeWork(int width, int height)
        {
            this.width = width;
            this.height = height;
            this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            this.graphics = this.image.createGraphics();

            DataBuffer buffer = this.image.getRaster().getDataBuffer();

            this.pixels = buffer instanceof DataBufferInt
                ? ((DataBufferInt) buffer).getData()
                : new int[width * height];
        }
    }
}
