package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Matrix4f;

/**
 * Rasterizes a layer's mask stack into one alpha texture.
 *
 * <p>This is CPU work, but masks are static most of the time and the result is cached by a hash of
 * every mask parameter. It deliberately supports feathered, inverted and boolean-combined masks
 * from day one, which is easier to keep consistent in a small renderer than a stack of stencil
 * passes. A mask is regenerated only when its geometry or feather changes.</p>
 */
public final class MaskCache
{
    public static final class MaskTexture
    {
        public final GlTexture texture;
        public final int width;
        public final int height;

        private MaskTexture(GlTexture texture, int width, int height)
        {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    private static final Map<String, MaskTexture> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final int MAX = 24;

    private MaskCache()
    {}

    public static MaskTexture get(CompLayer layer, int width, int height, Matrix4f world, float frame)
    {
        if (layer.masks == null || layer.masks.isEmpty())
        {
            return null;
        }

        java.util.List<CompMask> masks = new java.util.ArrayList<>();

        for (CompMask mask : layer.masks)
        {
            if (mask.enabled && mask.points.size() >= 2)
            {
                masks.add(layer.maskAt(mask, frame));
            }
        }

        if (masks.isEmpty())
        {
            return null;
        }

        String key = layer.id + ":" + width + "x" + height + ":" + hash(masks) + ":" + hash(world);
        MaskTexture texture = CACHE.get(key);

        if (texture != null)
        {
            return texture;
        }

        texture = rasterize(masks, width, height, world);

        if (texture != null)
        {
            CACHE.put(key, texture);

            while (CACHE.size() > MAX)
            {
                String oldest = CACHE.keySet().iterator().next();
                CACHE.remove(oldest).texture.delete();
            }
        }

        return texture;
    }

    private static int hash(Matrix4f matrix)
    {
        if (matrix == null)
        {
            return 0;
        }

        int hash = 17;

        /* JOML is column-major: x/y translation lives in m30/m31. Hashing m03/m13 (which are
         * always zero for a 2D layer) made every layer share one cached mask. */
        hash = hash * 31 + Float.floatToIntBits(matrix.m00());
        hash = hash * 31 + Float.floatToIntBits(matrix.m01());
        hash = hash * 31 + Float.floatToIntBits(matrix.m10());
        hash = hash * 31 + Float.floatToIntBits(matrix.m11());
        hash = hash * 31 + Float.floatToIntBits(matrix.m30());
        hash = hash * 31 + Float.floatToIntBits(matrix.m31());

        return hash;
    }

    private static int hash(java.util.List<CompMask> masks)
    {
        int hash = 17;

        for (CompMask mask : masks)
        {
            hash = hash * 31 + mask.id.hashCode();
            hash = hash * 31 + mask.mode;
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

    private static MaskTexture rasterize(java.util.List<CompMask> masks, int width, int height, Matrix4f world)
    {
        float[] combined = new float[width * height];
        boolean any = false;
        AffineTransform transform = world == null ? new AffineTransform() : new AffineTransform(
            world.m00(), world.m10(), world.m01(), world.m11(), world.m30(), world.m31()
        );

        for (CompMask mask : masks)
        {
            if (!mask.enabled || mask.points.size() < 2)
            {
                continue;
            }

            Path2D.Float path = new Path2D.Float();
            CompMask.CompMaskPoint first = mask.points.get(0);

            path.moveTo(first.x, first.y);

            for (int i = 1; i < mask.points.size(); i++)
            {
                CompMask.CompMaskPoint previous = mask.points.get(i - 1);
                CompMask.CompMaskPoint point = mask.points.get(i);

                path.curveTo(previous.x + previous.outX, previous.y + previous.outY, point.x + point.inX, point.y + point.inY, point.x, point.y);
            }

            path.closePath();
            path.transform(transform);

            Area area = new Area(path);

            if (mask.expansion > 0F)
            {
                area.add(new Area(new BasicStroke(mask.expansion * 2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)));
            }
            else if (mask.expansion < 0F)
            {
                area.subtract(new Area(new BasicStroke(-mask.expansion * 2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)));
            }

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.Src);
            g.setColor(java.awt.Color.WHITE);
            g.fill(area);
            g.dispose();

            float[] alpha = new float[width * height];
            int[] raster = image.getRGB(0, 0, width, height, null, 0, width);

            for (int i = 0; i < alpha.length; i++)
            {
                alpha[i] = ((raster[i] >>> 24) & 0xFF) / 255F;
            }

            if (mask.feather > 0.1F)
            {
                boxBlur(alpha, width, height, Math.min(128, Math.round(mask.feather)));
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

                if (!any)
                {
                    combined[i] = a;
                }
                else if (mask.mode == CompMask.MODE_SUBTRACT)
                {
                    combined[i] *= 1F - a;
                }
                else if (mask.mode == CompMask.MODE_INTERSECT)
                {
                    combined[i] *= a;
                }
                else if (mask.mode == CompMask.MODE_EXCLUDE)
                {
                    combined[i] = combined[i] + a - 2F * combined[i] * a;
                }
                else
                {
                    combined[i] = combined[i] + a - combined[i] * a;
                }
            }

            any = true;
        }

        if (!any)
        {
            return null;
        }

        int[] pixels = new int[combined.length];

        for (int i = 0; i < pixels.length; i++)
        {
            int a = Math.round(Math.max(0F, Math.min(1F, combined[i])) * 255F);

            pixels[i] = (a << 24) | 0xFFFFFF;
        }

        GlTexture texture = new GlTexture();

        texture.allocate(width, height);
        texture.uploadTopDown(pixels);

        return new MaskTexture(texture, width, height);
    }

    private static void boxBlur(float[] data, int width, int height, int radius)
    {
        if (radius <= 0)
        {
            return;
        }

        float[] temp = new float[data.length];
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

    public static void clear()
    {
        for (MaskTexture texture : CACHE.values())
        {
            texture.texture.delete();
        }

        CACHE.clear();
    }
}
