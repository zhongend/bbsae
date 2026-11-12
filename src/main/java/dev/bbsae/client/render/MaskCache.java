package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompMask;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
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
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Rasterizes a layer's mask stack into one alpha texture.
 *
 * <p>This is CPU work, but masks are static most of the time. Two separate identities are cached:
 * the <b>content</b> of the mask stack at a frame (resolved values, no world matrix) and the
 * <b>texture slot</b> of a layer at a resolution. The slot owns one GL texture and remembers which
 * content+world was uploaded into it, so a camera that moves every frame no longer allocates a new
 * {@link GlTexture}, evicts an old one and deletes it; it re-rasterizes into the same texture
 * instead. Static masks (and any repeat of an already rendered state) return the slot without
 * touching the pixels at all.</p>
 *
 * <p>The raster buffer itself is render-space, not layer-space, because the mask pass samples the
 * texture with the content FBO's UVs and has no model-matrix uniform. Removing the world matrix
 * from the <em>texture cache key</em> is only possible together with that sampling change (it lives
 * in CompRenderer / the mask pass); until then the world stays a part of the upload state, not of
 * the slot identity. What the slot split buys today: no per-frame GL texture churn, no per-frame
 * mask copies or path-string building, and no per-frame 1080p AWT buffer allocation.</p>
 *
 * <p>Pixel semantics are unchanged: the same AWT path transform, boolean combine, feather box blur,
 * expansion stroke and invert order as before. {@link #alphaFor} and {@link #alphaForProjected} are
 * the allocation-friendly reference entry points used by the CPU tests.</p>
 */
public final class MaskCache
{
    public static final class MaskTexture
    {
        public final GlTexture texture;
        public final int width;
        public final int height;

        /** Fingerprint of the content+world currently uploaded into {@link #texture}. */
        private long state;
        private boolean valid;

        private MaskTexture(GlTexture texture, int width, int height)
        {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * The largest feather the box blur is allowed to run, expressed in <b>layer pixels</b> before the
     * raster scale is applied.
     *
     * <p>The cap used to be applied after scaling, in raster pixels, so at 1:1 export a 200 px
     * feather became 128 while the 0.5x preview blurred 100 raster px (= 200 layer px) — the same
     * slider had a different feather in preview and export. Clamping the authored value first makes
     * the cap resolution independent.</p>
     */
    private static final float MAX_FEATHER = 128F;

    /** Texture slots, keyed by layer id + raster size. Access order = LRU. */
    private static final Map<String, MaskTexture> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final int MAX = 24;

    /** Resolved-mask scratch, keyed by layer identity and safe against nested resolves. */
    private static final Map<CompLayer, LayerScratch> SCRATCH =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final int MAX_DEPTH = 8;

    /** Per-size AWT buffers (image + alpha + combine + blur temp), reused across masks/frames. */
    private static final List<RasterWork> WORK = new ArrayList<>();
    private static final int MAX_WORK = 2;

    private MaskCache()
    {}

    public static MaskTexture get(CompLayer layer, int width, int height, Matrix4f world, float frame)
    {
        return get(layer, width, height, world, frame, 1F, "");
    }

    /**
     * Scoped variant: the slot key includes project + composition id.
     *
     * <p>A parent composition and a nested precomp can both contain a layer called {@code mask}; the
     * old key was {@code layer.id:WxH}, so the first one's mask texture was served to the second and
     * the per-frame state comparison forced a re-rasterization every frame.</p>
     *
     * @param scale raster pixels per layer pixel. The point transform already includes it; feather
     *              and expansion are expressed in layer pixels and are applied in raster space, so
     *              they have to be converted too — otherwise a 3D mask's feather shrinks with the
     *              proxy resolution.
     */
    public static MaskTexture get(CompLayer layer, int width, int height, Matrix4f world, float frame,
                                  float scale, String scope)
    {
        return getScoped(layer, width, height, world, frame, scale, scope);
    }

    public static MaskTexture get(CompLayer layer, int width, int height, Matrix4f world,
                                  float frame, float scale)
    {
        return getScoped(layer, width, height, world, frame, scale, "");
    }

    private static synchronized MaskTexture getScoped(CompLayer layer, int width, int height,
                                                      Matrix4f world, float frame, float scale,
                                                      String scope)
    {
        if (layer == null || layer.masks == null || layer.masks.isEmpty() || width <= 0 || height <= 0)
        {
            return null;
        }

        float safeScale = Math.max(0.0001F, scale);
        LayerScratch scratch = layerScratch(layer);
        int depth = scratch.enter();
        StackScratch stack = depth < 0 ? new StackScratch() : scratch.slot(depth);

        try
        {
            resolveStack(layer, frame, safeScale, stack);

            if (stack.count == 0)
            {
                return null;
            }

            String slotKey = textureSlot(scope, layer, width, height);
            long state = stateOf(stack.contentHash, world, false);
            MaskTexture texture = CACHE.get(slotKey);

            if (texture != null && texture.valid && texture.state == state)
            {
                return texture;
            }

            RasterWork work = workFor(width, height);
            float[] combined = cover(stack.masks, stack.count, stack.basePaths,
                width, height, world, false, work);

            if (combined == null)
            {
                return null;
            }

            if (texture == null)
            {
                GlTexture gl = new GlTexture();

                gl.allocate(width, height);
                texture = new MaskTexture(gl, width, height);
                CACHE.put(slotKey, texture);
                trim();
            }

            upload(texture, combined, work, state);

            return texture;
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
     * Whether a mask contributes to the alpha matte at all.
     *
     * <p>Three points is the smallest path with an area, and an open path is a stroke being drawn
     * rather than a region. Before this rule a two-point mask (the state after the pen tool's second
     * click) rasterized to zero coverage and made the entire layer disappear — which looked like
     * "the pen tool broke the layer" while the path was still being drawn.</p>
     */
    static boolean isMatte(CompMask mask)
    {
        return mask != null && mask.enabled && mask.closed && mask.points != null && mask.points.size() >= 3;
    }

    /** Package-private so the pen tool's regression tests can prove that {@code closed} invalidates. */
    static int hash(java.util.List<CompMask> masks)
    {
        int hash = 17;

        for (CompMask mask : masks)
        {
            hash = hash * 31 + mask.id.hashCode();
            hash = hash * 31 + mask.mode;
            hash = hash * 31 + (mask.closed ? 1 : 0);
            hash = hash * 31 + Float.floatToIntBits(mask.opacity);
            hash = hash * 31 + Float.floatToIntBits(mask.feather);
            hash = hash * 31 + Float.floatToIntBits(mask.expansion);
            hash = hash * 31 + (mask.inverted ? 1 : 0);

            for (CompMask.CompMaskPoint point : mask.points)
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

    /**
     * Pure-CPU half of {@link #get}: boolean-combines every mask into one coverage buffer.
     *
     * @return per-pixel coverage in {@code [0,1]}, or {@code null} when no mask can matte.
     */
    static float[] alphaFor(java.util.List<CompMask> masks, int width, int height, Matrix4f world)
    {
        if (masks == null || masks.isEmpty() || width <= 0 || height <= 0)
        {
            return null;
        }

        RasterWork work = new RasterWork(width, height);

        return copyOf(cover(masks, masks.size(), null, width, height, world, false, work));
    }

    /**
     * Comp-space mask for a perspective-projected 3D layer.
     *
     * <p>The direct (non-bake) render path draws a 3D layer's content already camera-projected in
     * composition space, so the affine layer-local mask used by 2D cannot address it. Here the mask
     * path is flattened in layer space and every vertex is projected through
     * {@code viewProjection = camera.projection · camera.view · world} into render pixels
     * (top-down image coordinates, the same convention the affine mask texture uses); then the
     * regular boolean combine / feather / expansion / invert pipeline runs on that raster.</p>
     *
     * <p>{@code scale} is projected render pixels per layer pixel; feather and expansion are
     * authored in layer pixels and multiplied by it, exactly like the affine proxy path. Masks
     * partially behind the camera are approximated by dropping the off-screen vertices.</p>
     */
    public static MaskTexture getProjected(CompLayer layer, int width, int height,
                                           Matrix4f viewProjection, float frame, float scale)
    {
        return getProjected(layer, width, height, viewProjection, frame, scale, "");
    }

    /** Scoped variant of {@link #getProjected(CompLayer, int, int, Matrix4f, float, float)}. */
    public static MaskTexture getProjected(CompLayer layer, int width, int height,
                                           Matrix4f viewProjection, float frame, float scale,
                                           String scope)
    {
        return getProjectedScoped(layer, width, height, viewProjection, frame, scale, scope);
    }

    private static synchronized MaskTexture getProjectedScoped(CompLayer layer, int width, int height,
                                                               Matrix4f viewProjection, float frame,
                                                               float scale, String scope)
    {
        if (layer == null || layer.masks == null || layer.masks.isEmpty()
            || viewProjection == null || width <= 0 || height <= 0)
        {
            return null;
        }

        float safeScale = Math.max(0.0001F, scale);
        LayerScratch scratch = layerScratch(layer);
        int depth = scratch.enter();
        StackScratch stack = depth < 0 ? new StackScratch() : scratch.slot(depth);

        try
        {
            resolveStack(layer, frame, safeScale, stack);

            if (stack.count == 0)
            {
                return null;
            }

            /* The slot is shared with the affine path: the state bit says which projection the
             * pixels in the texture belong to. A 3D layer can use either path depending on whether
             * its bake was available, but never both at the same time. */
            String slotKey = textureSlot(scope, layer, width, height);
            long state = stateOf(stack.contentHash, viewProjection, true);
            MaskTexture texture = CACHE.get(slotKey);

            if (texture != null && texture.valid && texture.state == state)
            {
                return texture;
            }

            RasterWork work = workFor(width, height);
            float[] combined = cover(stack.masks, stack.count, stack.basePaths,
                width, height, viewProjection, true, work);

            if (combined == null)
            {
                return null;
            }

            if (texture == null)
            {
                GlTexture gl = new GlTexture();

                gl.allocate(width, height);
                texture = new MaskTexture(gl, width, height);
                CACHE.put(slotKey, texture);
                trim();
            }

            upload(texture, combined, work, state);

            return texture;
        }
        finally
        {
            if (depth >= 0)
            {
                scratch.leave();
            }
        }
    }

    /** Pure-CPU half of {@link #getProjected}: projected, boolean-combined coverage. */
    static float[] alphaForProjected(java.util.List<CompMask> masks, int width, int height,
                                     Matrix4f viewProjection)
    {
        if (masks == null || masks.isEmpty() || width <= 0 || height <= 0 || viewProjection == null)
        {
            return null;
        }

        RasterWork work = new RasterWork(width, height);

        return copyOf(cover(masks, masks.size(), null, width, height, viewProjection, true, work));
    }

    /* ---------------------------------------------------------------- raster core */

    /**
     * The single rasterization path: fills each mask into the reused image, extracts coverage,
     * applies feather / invert / opacity, then boolean-combines the masks in list order.
     *
     * @param basePaths cached layer-space bezier per mask when the geometry is unchanged; null
     *                  builds them per call (the public CPU entry points)
     */
    private static float[] cover(List<CompMask> masks, int count, Path2D.Float[] basePaths,
                                 int width, int height, Matrix4f matrix, boolean projected,
                                 RasterWork work)
    {
        float[] combined = work.combined;

        Arrays.fill(combined, 0F);

        boolean any = false;
        AffineTransform transform = projected || matrix == null ? null : new AffineTransform(
            matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m30(), matrix.m31()
        );

        for (int index = 0; index < count; index++)
        {
            CompMask mask = masks.get(index);

            if (!isMatte(mask))
            {
                continue;
            }

            Path2D.Float path;

            if (projected)
            {
                Path2D.Float base = basePaths == null ? bezierPath(mask) : basePaths[index];

                path = projectedPath(work, base, matrix, width, height);
            }
            else
            {
                Path2D.Float base = basePaths == null ? bezierPath(mask) : basePaths[index];
                Path2D.Float target = work.path;

                target.reset();
                target.append(base, false);

                if (transform != null)
                {
                    target.transform(transform);
                }

                path = target;
            }

            if (path == null)
            {
                continue;
            }

            Area area = new Area(path);

            if (mask.expansion > 0F)
            {
                area.add(new Area(new BasicStroke(mask.expansion * 2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)));
            }
            else if (mask.expansion < 0F)
            {
                area.subtract(new Area(new BasicStroke(-mask.expansion * 2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)));
            }

            Graphics2D g = work.graphics;

            /* Reuse the image: clear it to fully transparent, then paint the mask white. Clear is
             * the AWT-defined "set the destination to transparent" composite, so the result is the
             * same as a freshly allocated TYPE_INT_ARGB image. */
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, width, height);
            g.setComposite(AlphaComposite.Src);
            g.setColor(java.awt.Color.WHITE);
            g.fill(area);

            int[] pixels = work.pixels;
            float[] alpha = work.alpha;

            for (int i = 0; i < alpha.length; i++)
            {
                alpha[i] = ((pixels[i] >>> 24) & 0xFF) / 255F;
            }

            if (mask.feather > 0.1F)
            {
                boxBlur(alpha, width, height, Math.min(128, Math.round(mask.feather)), work);
            }

            if (mask.inverted)
            {
                for (int i = 0; i < alpha.length; i++)
                {
                    alpha[i] = 1F - alpha[i];
                }
            }

            float opacity = Math.max(0F, Math.min(1F, mask.opacity));

            for (int i = 0; i < alpha.length; i++)
            {
                float a = alpha[i] * opacity;

                combined[i] = any
                    ? combine(combined[i], a, mask.mode)
                    : combineFirst(mask.mode, a);
            }

            any = true;
        }

        return any ? combined : null;
    }

    /** Flatten the layer-local bezier and project every vertex into top-down render pixels. */
    private static Path2D.Float projectedPath(RasterWork work, Path2D.Float source, Matrix4f viewProjection,
                                              int width, int height)
    {
        Path2D.Float projected = work.path;
        PathIterator iterator = source.getPathIterator(null, 0.5D);
        boolean started = false;
        boolean any = false;

        projected.reset();

        while (!iterator.isDone())
        {
            int type = iterator.currentSegment(work.coords);

            iterator.next();

            if (type == PathIterator.SEG_CLOSE)
            {
                if (started)
                {
                    projected.closePath();
                }

                continue;
            }

            Vector4f point = work.vec.set((float) work.coords[0], (float) work.coords[1], 0F, 1F);

            viewProjection.transform(point);

            if (point.w <= 1.0e-6F)
            {
                /* Behind the camera: the projection of this vertex is meaningless. Drop it; a mask
                 * straddling the camera plane is approximated by its visible part. */
                continue;
            }

            float ndcX = point.x / point.w;
            float ndcY = point.y / point.w;
            float px = (ndcX * 0.5F + 0.5F) * width;
            float py = (1F - (ndcY * 0.5F + 0.5F)) * height;

            if (type == PathIterator.SEG_MOVETO || !started)
            {
                projected.moveTo(px, py);
                started = true;
            }
            else
            {
                projected.lineTo(px, py);
            }

            any = true;
        }

        return any ? projected : null;
    }

    /**
     * Builds the mask path in layer-local coordinates: anchor points plus both handles, closed with
     * the last segment's bezier (a bare closePath would flatten pen-drawn smooth closing segments).
     */
    private static Path2D.Float bezierPath(CompMask mask)
    {
        Path2D.Float path = new Path2D.Float();

        bezierPathInto(mask, path);

        return path;
    }

    private static void bezierPathInto(CompMask mask, Path2D.Float path)
    {
        path.reset();

        CompMask.CompMaskPoint first = mask.points.get(0);

        path.moveTo(first.x, first.y);

        for (int i = 1; i < mask.points.size(); i++)
        {
            CompMask.CompMaskPoint previous = mask.points.get(i - 1);
            CompMask.CompMaskPoint point = mask.points.get(i);

            path.curveTo(previous.x + previous.outX, previous.y + previous.outY,
                point.x + point.inX, point.y + point.inY, point.x, point.y);
        }

        CompMask.CompMaskPoint last = mask.points.get(mask.points.size() - 1);

        path.curveTo(last.x + last.outX, last.y + last.outY,
            first.x + first.inX, first.y + first.inY, first.x, first.y);
        path.closePath();
    }

    /* ---------------------------------------------------------------- slot bookkeeping */

    private static String slotKey(CompLayer layer, int width, int height)
    {
        return layer.id + ":" + width + "x" + height;
    }

    /** Project/comp scope for cache keys: nested precomps may reuse layer ids. */
    public static String cacheScope(CompProject project, CompDocument comp)
    {
        return (project == null || project.id == null ? "" : project.id) + "/"
            + (comp == null || comp.id == null ? "" : comp.id);
    }


    private static long stateOf(long contentHash, Matrix4f matrix, boolean projected)
    {
        long state = contentHash;

        state = mix(state, matrixHash(matrix, projected));
        state = mix(state, projected ? 0x9E3779B97F4A7C15L : 0x51ED270B4F3A5C69L);

        return state;
    }

    private static long mix(long hash, long value)
    {
        hash ^= value;
        hash *= 0x100000001B3L;

        return hash;
    }

    private static long mix(long hash, float value)
    {
        return mix(hash, Float.floatToIntBits(value) & 0xFFFFFFFFL);
    }

    private static long mix(long hash, int value)
    {
        return mix(hash, value & 0xFFFFFFFFL);
    }

    private static long mix(long hash, boolean value)
    {
        return mix(hash, value ? 1L : 0L);
    }

    /**
     * The affine path only reads the six 2D components; the projected path reads the whole
     * perspective matrix (the old key hashed only the affine terms even for {@code getProjected},
     * so a camera move that changed only the depth row could serve a stale raster).
     */
    private static long matrixHash(Matrix4f matrix, boolean full)
    {
        if (matrix == null)
        {
            return 0L;
        }

        if (full)
        {
            return fullMatrixHash(matrix);
        }

        long hash = 17L;

        hash = mix(hash, matrix.m00());
        hash = mix(hash, matrix.m01());
        hash = mix(hash, matrix.m10());
        hash = mix(hash, matrix.m11());
        hash = mix(hash, matrix.m30());
        hash = mix(hash, matrix.m31());

        return hash;
    }

    private static long fullMatrixHash(Matrix4f matrix)
    {
        long hash = 17L;

        hash = mix(hash, matrix.m00());
        hash = mix(hash, matrix.m01());
        hash = mix(hash, matrix.m02());
        hash = mix(hash, matrix.m03());
        hash = mix(hash, matrix.m10());
        hash = mix(hash, matrix.m11());
        hash = mix(hash, matrix.m12());
        hash = mix(hash, matrix.m13());
        hash = mix(hash, matrix.m20());
        hash = mix(hash, matrix.m21());
        hash = mix(hash, matrix.m22());
        hash = mix(hash, matrix.m23());
        hash = mix(hash, matrix.m30());
        hash = mix(hash, matrix.m31());
        hash = mix(hash, matrix.m32());
        hash = mix(hash, matrix.m33());

        return hash;
    }

    private static MaskTexture upload(MaskTexture texture, float[] combined, RasterWork work, long state)
    {
        int[] pixels = work.pixels;

        for (int i = 0; i < pixels.length; i++)
        {
            int a = Math.round(Math.max(0F, Math.min(1F, combined[i])) * 255F);

            pixels[i] = (a << 24) | 0xFFFFFF;
        }

        texture.texture.uploadTopDown(pixels);
        texture.state = state;
        texture.valid = true;

        return texture;
    }

    private static void trim()
    {
        while (CACHE.size() > MAX)
        {
            String oldest = CACHE.keySet().iterator().next();

            CACHE.remove(oldest).texture.delete();
        }
    }

    /* ---------------------------------------------------------------- per-layer scratch */

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

    /**
     * Resolves every matte mask into pooled objects and fingerprints the result.
     *
     * <p>The fingerprint is the cache identity of the content: it covers the resolved points and
     * the resolved style fields after feather clamping and raster-scale compensation, so an
     * animated mask (or a changed feather/expansion) produces a new key while a static one keeps
     * its key across frames — independent of the world matrix. The layer-space bezier paths are
     * rebuilt only when the geometry (points + closed) changes.</p>
     */
    private static void resolveStack(CompLayer layer, float frame, float safeScale, StackScratch stack)
    {
        int count = 0;
        long content = 17L;
        long geometry = 17L;

        if (layer.masks != null)
        for (CompMask mask : layer.masks)
        {
            if (!isMatte(mask))
            {
                continue;
            }

            CompMask resolved;

            if (count < stack.masks.size())
            {
                resolved = stack.masks.get(count);
            }
            else
            {
                resolved = new CompMask();
                stack.masks.add(resolved);
            }

            layer.maskAtInto(mask, frame, resolved);

            resolved.feather = Math.min(MAX_FEATHER, Math.max(0F, resolved.feather));
            resolved.expansion *= safeScale;

            if (Math.abs(safeScale - 1F) > 0.0001F)
            {
                resolved.feather *= safeScale;
            }

            content = mix(content, resolved.mode);
            content = mix(content, resolved.inverted);
            content = mix(content, resolved.opacity);
            content = mix(content, resolved.feather);
            content = mix(content, resolved.expansion);
            content = mix(content, resolved.closed);

            geometry = mix(geometry, resolved.closed);

            for (CompMask.CompMaskPoint point : resolved.points)
            {
                content = mix(content, point.x);
                content = mix(content, point.y);
                content = mix(content, point.inX);
                content = mix(content, point.inY);
                content = mix(content, point.outX);
                content = mix(content, point.outY);
                geometry = mix(geometry, point.x);
                geometry = mix(geometry, point.y);
                geometry = mix(geometry, point.inX);
                geometry = mix(geometry, point.inY);
                geometry = mix(geometry, point.outX);
                geometry = mix(geometry, point.outY);
            }

            count += 1;
        }

        stack.count = count;
        stack.contentHash = mix(mix(content, geometry), count);

        if (stack.geometryHash != geometry || stack.basePaths.length < count)
        {
            if (stack.basePaths.length < count)
            {
                Path2D.Float[] grown = new Path2D.Float[Math.max(count, stack.basePaths.length * 2 + 4)];

                System.arraycopy(stack.basePaths, 0, grown, 0, stack.basePaths.length);
                stack.basePaths = grown;
            }

            for (int i = 0; i < count; i++)
            {
                if (stack.basePaths[i] == null)
                {
                    stack.basePaths[i] = new Path2D.Float();
                }

                bezierPathInto(stack.masks.get(i), stack.basePaths[i]);
            }

            stack.geometryHash = geometry;
        }
    }

    private static RasterWork workFor(int width, int height)
    {
        for (int i = 0; i < WORK.size(); i++)
        {
            RasterWork work = WORK.get(i);

            if (work.width == width && work.height == height)
            {
                WORK.remove(i);
                WORK.add(work);

                return work;
            }
        }

        RasterWork work = new RasterWork(width, height);

        WORK.add(work);

        while (WORK.size() > MAX_WORK)
        {
            WORK.remove(0);
        }

        return work;
    }

    private static float[] copyOf(float[] values)
    {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    /* ---------------------------------------------------------------- combine / blur */

    /**
     * The first mask is applied to the layer's full alpha, not to an empty base.
     *
     * <p>AE/Alight Motion semantics: a single "Add"/"Intersect" mask defines the visible area,
     * a single "Subtract"/"Exclude" mask cuts the layer out and leaves the outside visible. The
     * old code always used the first mask as {@code combined = a}, so the first Subtract/Exclude
     * showed the mask instead of the outside — the "排除反了" bug.</p>
     */
    static float combineFirst(int mode, float a)
    {
        return switch (mode)
        {
            case CompMask.MODE_SUBTRACT, CompMask.MODE_EXCLUDE -> 1F - a;
            default -> a;
        };
    }

    /** Subsequent masks combine against the current coverage. */
    static float combine(float base, float a, int mode)
    {
        float result = switch (mode)
        {
            case CompMask.MODE_SUBTRACT -> base * (1F - a);
            case CompMask.MODE_INTERSECT -> base * a;
            case CompMask.MODE_EXCLUDE -> base + a - 2F * base * a;
            default -> base + a - base * a;
        };

        return Math.max(0F, Math.min(1F, result));
    }

    private static void boxBlur(float[] data, int width, int height, int radius, RasterWork work)
    {
        if (radius <= 0)
        {
            return;
        }

        if (work.blur == null || work.blur.length < data.length)
        {
            work.blur = new float[data.length];
        }

        float[] temp = work.blur;
        int window = radius * 2 + 1;

        for (int y = 0; y < height; y++)
        {
            float sum = 0F;

            for (int x = -radius; x <= radius; x++)
            {
                sum += data[y * width + Math.max(0, Math.min(width - 1, x))];
            }

            for (int x = 0; x < width; x++)
            {
                temp[y * width + x] = sum / window;
                sum -= data[y * width + Math.max(0, x - radius)];
                sum += data[y * width + Math.min(width - 1, x + radius + 1)];
            }
        }

        for (int x = 0; x < width; x++)
        {
            float sum = 0F;

            for (int y = -radius; y <= radius; y++)
            {
                sum += temp[Math.max(0, Math.min(height - 1, y)) * width + x];
            }

            for (int y = 0; y < height; y++)
            {
                data[y * width + x] = sum / window;
                sum -= temp[Math.max(0, y - radius) * width + x];
                sum += temp[Math.min(height - 1, y + radius + 1) * width + x];
            }
        }
    }

    public static synchronized void clear()
    {
        for (MaskTexture texture : CACHE.values())
        {
            texture.texture.delete();
        }

        CACHE.clear();
        SCRATCH.clear();
        WORK.clear();
    }

    /* ---------------------------------------------------------------- test hooks */

    /** World-free cache identity of the layer's resolved mask stack. */
    static synchronized long contentKey(CompLayer layer, float frame, float scale)
    {
        if (layer == null)
        {
            return 0L;
        }

        float safeScale = Math.max(0.0001F, scale);
        LayerScratch scratch = layerScratch(layer);
        int depth = scratch.enter();
        StackScratch stack = depth < 0 ? new StackScratch() : scratch.slot(depth);

        try
        {
            resolveStack(layer, frame, safeScale, stack);

            return stack.contentHash;
        }
        finally
        {
            if (depth >= 0)
            {
                scratch.leave();
            }
        }
    }

    /** Identity of the texture slot a layer+size maps to; it deliberately has no world term. */
    static synchronized String textureSlot(CompLayer layer, int width, int height)
    {
        return textureSlot("", layer, width, height);
    }

    static synchronized String textureSlot(String scope, CompLayer layer, int width, int height)
    {
        String suffix = layer == null ? "" : slotKey(layer, width, height);

        return scope == null || scope.isEmpty() ? suffix : scope + "/" + suffix;
    }

    /** Full upload fingerprint (content + world + projection). */
    static synchronized long rasterState(CompLayer layer, int width, int height, Matrix4f world,
                                         float frame, float scale, boolean projected)
    {
        if (layer == null)
        {
            return 0L;
        }

        float safeScale = Math.max(0.0001F, scale);
        LayerScratch scratch = layerScratch(layer);
        int depth = scratch.enter();
        StackScratch stack = depth < 0 ? new StackScratch() : scratch.slot(depth);

        try
        {
            resolveStack(layer, frame, safeScale, stack);

            return stateOf(stack.contentHash, world, projected);
        }
        finally
        {
            if (depth >= 0)
            {
                scratch.leave();
            }
        }
    }

    /** GL-free rasterization used by the pixel-parity tests. */
    static synchronized float[] coverageForTest(CompLayer layer, int width, int height, Matrix4f world,
                                               float frame, float scale, boolean projected)
    {
        if (layer == null || layer.masks == null || layer.masks.isEmpty())
        {
            return null;
        }

        float safeScale = Math.max(0.0001F, scale);
        StackScratch stack = new StackScratch();

        resolveStack(layer, frame, safeScale, stack);

        if (stack.count == 0)
        {
            return null;
        }

        RasterWork work = new RasterWork(width, height);

        return copyOf(cover(stack.masks, stack.count, stack.basePaths,
            width, height, world, projected, work));
    }

    /* ---------------------------------------------------------------- scratch types */

    private static final class StackScratch
    {
        final ArrayList<CompMask> masks = new ArrayList<>();
        Path2D.Float[] basePaths = new Path2D.Float[0];
        int count;
        long contentHash = 17L;
        long geometryHash = 17L;
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

    /**
     * Reused AWT buffers for one raster size. A fresh {@code TYPE_INT_ARGB} image, its int[] raster,
     * the float coverage array and the box-blur temporary are the 25 MB per mask per frame the audit
     * found at 1080p; they are now allocated once per size and cleared in place. The pixels are read
     * from the image's {@link DataBufferInt} directly, so {@code getRGB} does not copy the raster a
     * second time.
     */
    private static final class RasterWork
    {
        final int width;
        final int height;
        final BufferedImage image;
        final Graphics2D graphics;
        final int[] pixels;
        final float[] combined;
        final float[] alpha;
        final double[] coords = new double[6];
        final Vector4f vec = new Vector4f();
        final Path2D.Float path = new Path2D.Float();
        float[] blur;

        RasterWork(int width, int height)
        {
            this.width = width;
            this.height = height;
            this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            this.graphics = this.image.createGraphics();
            this.graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            DataBuffer buffer = this.image.getRaster().getDataBuffer();

            this.pixels = buffer instanceof DataBufferInt
                ? ((DataBufferInt) buffer).getData()
                : new int[width * height];
            this.combined = new float[width * height];
            this.alpha = new float[width * height];
        }
    }
}
