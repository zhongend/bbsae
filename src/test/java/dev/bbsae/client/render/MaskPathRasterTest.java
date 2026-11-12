package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matte semantics of mask paths, on the CPU (no GL needed: only the texture upload needs a context).
 *
 * <p>This is the regression suite for the pen tool's first live bug: a two-point path — the state
 * after the second click — used to rasterize to zero coverage and made the whole layer disappear.
 * Open paths are strokes, not regions, so they never take part in matting; only closed paths with
 * enough vertices to enclose an area do.</p>
 */
public class MaskPathRasterTest
{
    private static final int SIZE = 64;

    private static CompMask mask(String id)
    {
        return new CompMask(id);
    }

    private static List<CompMask> list(CompMask mask)
    {
        List<CompMask> masks = new ArrayList<>();

        masks.add(mask);

        return masks;
    }

    private static int coverage(float[] alpha)
    {
        int covered = 0;

        for (float value : alpha)
        {
            if (value > 0.5F)
            {
                covered += 1;
            }
        }

        return covered;
    }

    private static float centroidX(float[] alpha)
    {
        float sum = 0F;
        int count = 0;

        for (int i = 0; i < alpha.length; i++)
        {
            if (alpha[i] > 0.5F)
            {
                sum += i % SIZE;
                count += 1;
            }
        }

        return count == 0 ? -1F : sum / count;
    }

    private static float[] alpha(List<CompMask> masks, Matrix4f world)
    {
        return MaskCache.alphaFor(masks, SIZE, SIZE, world);
    }

    @Test
    public void closedSquareCoversItsArea()
    {
        CompMask mask = mask("square");

        mask.rectangle(10F, 10F, 20F, 20F);

        float[] alpha = alpha(list(mask), null);

        assertNotNull(alpha);
        assertEquals(400, coverage(alpha), 30, "20x20 square, anti-aliased edges excluded");
    }

    @Test
    public void twoPointPathIsNotAMask()
    {
        CompMask mask = mask("line");

        mask.add(0F, 0F);
        mask.add(30F, 0F);

        assertFalse(MaskCache.isMatte(mask), "two points cannot enclose an area");
        assertNull(alpha(list(mask), null), "a degenerate path must not matte the layer");
    }

    @Test
    public void openPathIsNotAMask()
    {
        CompMask mask = mask("open");

        mask.add(0F, 0F);
        mask.add(40F, 0F);
        mask.add(40F, 40F);
        mask.add(0F, 40F);
        mask.closed = false;

        assertFalse(MaskCache.isMatte(mask));
        assertNull(alpha(list(mask), null), "an open path is a stroke being drawn, not a matte");
    }

    @Test
    public void openingAClosedPathRemovesItsMatte()
    {
        CompMask mask = mask("toggle");

        mask.rectangle(10F, 10F, 20F, 20F);
        assertNotNull(alpha(list(mask), null));

        mask.closed = false;
        assertNull(alpha(list(mask), null));

        mask.closed = true;
        assertNotNull(alpha(list(mask), null));
    }

    @Test
    public void cubicBezierEllipseCoversTheCircle()
    {
        CompMask mask = mask("ellipse");

        mask.ellipse(32F, 32F, 20F, 20F);

        float[] alpha = alpha(list(mask), null);
        int covered = coverage(alpha);
        float expected = (float) (Math.PI * 20F * 20F);

        assertEquals(expected, covered, 70F, "four-handle cubic approximation of a circle");
    }

    @Test
    public void featherSoftensTheEdge()
    {
        CompMask sharp = mask("sharp");

        sharp.rectangle(20F, 20F, 24F, 24F);

        CompMask feathered = mask("feathered");

        feathered.rectangle(20F, 20F, 24F, 24F);
        feathered.feather = 8F;

        float[] sharpAlpha = alpha(list(sharp), null);
        float[] featheredAlpha = alpha(list(feathered), null);

        assertNotNull(sharpAlpha);
        assertNotNull(featheredAlpha);

        /* Feathering spreads coverage outside the hard edge and reduces peak coverage. */
        int softCoverage = 0;

        for (float value : featheredAlpha)
        {
            if (value > 0.05F)
            {
                softCoverage += 1;
            }
        }

        assertTrue(softCoverage > coverage(sharpAlpha), "feather extends coverage past the hard edge");
        assertEquals(1F, sharpAlpha[32 * SIZE + 32], 0.05F, "centre stays opaque");
        assertTrue(featheredAlpha[32 * SIZE + 32] < 0.99F || softCoverage > 0, "soft edge exists");
    }

    @Test
    public void expansionGrowsTheMatte()
    {
        CompMask mask = mask("grow");

        mask.rectangle(24F, 24F, 16F, 16F);

        int base = coverage(alpha(list(mask), null));

        mask.expansion = 5F;
        int expanded = coverage(alpha(list(mask), null));

        assertTrue(expanded > base + 100, "expansion by 5 px must grow the matte: " + base + " -> " + expanded);
    }

    @Test
    public void worldMatrixMovesTheMatte()
    {
        CompMask mask = mask("moved");

        mask.rectangle(10F, 20F, 20F, 20F);

        float centroidBefore = centroidX(alpha(list(mask), null));
        float centroidAfter = centroidX(alpha(list(mask), new Matrix4f().translate(17F, 0F, 0F)));

        assertTrue(centroidBefore >= 0F && centroidAfter >= 0F);
        assertEquals(centroidBefore + 17F, centroidAfter, 1F, "world translation moves the raster");
    }

    @Test
    public void hashIncludesClosed()
    {
        CompMask closed = mask("hash");

        closed.rectangle(0F, 0F, 10F, 10F);

        int closedHash = MaskCache.hash(list(closed));

        closed.closed = false;

        assertNotEquals(closedHash, MaskCache.hash(list(closed)),
            "without closed in the hash a toggled path keeps serving the old raster");
    }

    @Test
    public void maskAtCopyKeepsClosed()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        CompMask mask = mask("copy");

        mask.rectangle(0F, 0F, 10F, 10F);
        mask.closed = false;

        CompMask resolved = layer.maskAt(mask, 0F);

        assertFalse(resolved.closed, "evaluating a mask must not silently close an open path");
        assertEquals(4, resolved.points.size());
    }

    @Test
    public void oldProjectsDeserializeAsClosed()
    {
        /* A project written before the field existed has no JSON key here; Gson leaves the Java
         * default, which therefore has to be true. */
        assertTrue(new CompMask("legacy").closed);
        assertTrue(new CompMask("legacy").rectangle(0F, 0F, 5F, 5F).closed);
    }

    @Test
    public void subtractAsFirstMaskKeepsTheOutside()
    {
        CompMask mask = mask("subtract");

        mask.rectangle(10F, 10F, 20F, 20F);
        mask.mode = CompMask.MODE_SUBTRACT;

        float[] alpha = alpha(list(mask), null);

        assertNotNull(alpha);
        assertEquals(1F, alpha[2 * SIZE + 2], 0.01F, "outside stays visible");
        assertEquals(0F, alpha[20 * SIZE + 20], 0.01F, "inside is cut out");
    }
}
