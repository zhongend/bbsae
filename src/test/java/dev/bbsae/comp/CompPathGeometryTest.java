package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry contract of the pen tool.
 *
 * <p>No game, no GL, no AWT: every case here is arithmetic the renderer and the editor both depend
 * on. The split test is the important one — "adding a point must not move the curve" is the
 * promise AE's pen tool makes and the fastest way to notice a broken de Casteljau implementation.</p>
 */
public class CompPathGeometryTest
{
    private static CompPathGeometry.ArrayPath openPath(float[][] points)
    {
        CompPathGeometry.ArrayPath path = new CompPathGeometry.ArrayPath(points.length);

        for (float[] point : points)
        {
            path.add(point[0], point[1], point.length > 2 ? point[2] : 0F, point.length > 3 ? point[3] : 0F,
                point.length > 4 ? point[4] : 0F, point.length > 5 ? point[5] : 0F);
        }

        path.setClosed(false);

        return path;
    }

    private static CompPathGeometry.ArrayPath square(boolean closed)
    {
        CompPathGeometry.ArrayPath path = openPath(new float[][] {
            {0F, 0F},
            {100F, 0F},
            {100F, 100F},
            {0F, 100F}
        });

        path.setClosed(closed);

        return path;
    }

    /** Same points plus the split point and the four handle corrections a real edit applies. */
    private static CompPathGeometry.ArrayPath applySplit(CompPathGeometry.ArrayPath path, int segment, float t)
    {
        int count = path.count();
        int end = CompPathGeometry.segmentEnd(path, segment);
        float[] split = CompPathGeometry.splitSegment(path, segment, t);
        CompPathGeometry.ArrayPath result = new CompPathGeometry.ArrayPath(count + 1);

        assertNotNull(split);

        for (int i = 0; i < count; i++)
        {
            float[] point = path.get(i);
            float inX = point[2];
            float inY = point[3];
            float outX = point[4];
            float outY = point[5];

            if (i == segment)
            {
                outX = split[6];
                outY = split[7];
            }

            if (i == end)
            {
                inX = split[8];
                inY = split[9];
            }

            result.add(point[0], point[1], inX, inY, outX, outY);

            if (i == segment)
            {
                result.add(split[0], split[1], split[2], split[3], split[4], split[5]);
            }
        }

        result.setClosed(path.closed());

        return result;
    }

    @Test
    public void segmentCountDependsOnClosed()
    {
        assertEquals(3, CompPathGeometry.segmentCount(4, false));
        assertEquals(4, CompPathGeometry.segmentCount(4, true));
        assertEquals(0, CompPathGeometry.segmentCount(1, true));
        assertEquals(0, CompPathGeometry.segmentCount(0, false));
        assertEquals(1, CompPathGeometry.segmentCount(2, false));
    }

    @Test
    public void splitPreservesShapeOnEverySegment()
    {
        CompPathGeometry.ArrayPath path = openPath(new float[][] {
            {0F, 0F, 0F, 0F, 40F, 0F},
            {100F, 60F, -30F, 10F, 10F, 30F},
            {200F, 20F, 30F, -20F, 0F, 0F},
            {260F, 140F, -20F, 30F, 0F, 0F}
        });

        for (int segment = 0; segment < CompPathGeometry.segmentCount(path); segment++)
        {
            for (float t : new float[] {0.1F, 0.25F, 0.5F, 0.9F})
            {
                float[] control = CompPathGeometry.segment(path, segment);
                CompPathGeometry.ArrayPath split = applySplit(path, segment, t);
                float[] first = CompPathGeometry.segment(split, segment);
                float[] second = CompPathGeometry.segment(split, segment + 1);

                for (int i = 0; i <= 32; i++)
                {
                    float u = i / 32F;
                    float[] expected = CompPathGeometry.cubicAt(control, u);

                    if (u <= t)
                    {
                        float[] actual = CompPathGeometry.cubicAt(first, t <= 0F ? 0F : u / t);

                        assertEquals(expected[0], actual[0], 0.02F, "x before split point");
                        assertEquals(expected[1], actual[1], 0.02F, "y before split point");
                    }

                    if (u >= t)
                    {
                        float keep = 1F - t;
                        float[] actual = CompPathGeometry.cubicAt(second, keep <= 0F ? 1F : (u - t) / keep);

                        assertEquals(expected[0], actual[0], 0.02F, "x after split point");
                        assertEquals(expected[1], actual[1], 0.02F, "y after split point");
                    }
                }
            }
        }
    }

    @Test
    public void splitOnClosedPathWrapsToFirstVertex()
    {
        CompPathGeometry.ArrayPath path = square(true);
        float[] control = CompPathGeometry.segment(path, 3);
        CompPathGeometry.ArrayPath split = applySplit(path, 3, 0.5F);

        assertTrue(split.closed());
        assertEquals(5, split.count());

        float[] expected = CompPathGeometry.cubicAt(control, 0.5F);
        float[] actual = CompPathGeometry.cubicAt(CompPathGeometry.segment(split, 3), 1F);

        assertEquals(expected[0], actual[0], 0.02F);
        assertEquals(expected[1], actual[1], 0.02F);
        assertEquals(0F, split.x(4), 0.02F, "split point x");
        assertEquals(50F, split.y(4), 0.02F, "split point y");
    }

    @Test
    public void nearestOnPathHitsEverySegment()
    {
        CompPathGeometry.ArrayPath path = square(true);
        /* Perpendicular offsets from each segment midpoint, one per side of the square. */
        float[][] queries = {{50F, -2F}, {102F, 50F}, {50F, 102F}, {-2F, 50F}};
        float[][] midpoints = {{50F, 0F}, {100F, 50F}, {50F, 100F}, {0F, 50F}};

        for (int segment = 0; segment < midpoints.length; segment++)
        {
            float[] result = CompPathGeometry.nearestOnPath(path, queries[segment][0], queries[segment][1]);

            assertNotNull(result);
            assertEquals(segment, (int) result[0], "segment index");
            assertEquals(0.5F, result[1], 0.03F, "parameter t");
            assertEquals(2F, result[2], 0.05F, "distance");
            assertEquals(midpoints[segment][0], result[3], 0.2F, "nearest x");
            assertEquals(midpoints[segment][1], result[4], 0.2F, "nearest y");
        }
    }

    @Test
    public void nearestOnPathDegenerateInputsAreSafe()
    {
        assertNull(CompPathGeometry.nearestOnPath(openPath(new float[0][]), 10F, 10F));
        assertNull(CompPathGeometry.nearestOnPath(openPath(new float[][] {{5F, 5F}}), 10F, 10F));

        CompPathGeometry.ArrayPath zeroLength = openPath(new float[][] {{5F, 5F}, {5F, 5F}});
        float[] result = CompPathGeometry.nearestOnPath(zeroLength, 20F, 20F);

        assertNotNull(result);
        assertFalse(Float.isNaN(result[0]) || Float.isNaN(result[1]) || Float.isNaN(result[2]), "zero-length path must not produce NaN");
        assertEquals(0F, result[0], 0F);
    }

    @Test
    public void insidePathUsesEvenOddOverTheOutline()
    {
        CompPathGeometry.ArrayPath path = square(true);

        assertTrue(CompPathGeometry.insidePath(path, 50F, 50F));
        assertFalse(CompPathGeometry.insidePath(path, 150F, 50F));
        assertFalse(CompPathGeometry.insidePath(path, -5F, 50F));

        /* An open path is filled as if closed (AE and AWT both do this), so its interior counts. */
        CompPathGeometry.ArrayPath openTriangle = openPath(new float[][] {{0F, 0F}, {100F, 0F}, {100F, 100F}});

        assertTrue(CompPathGeometry.insidePath(openTriangle, 75F, 20F));
        assertFalse(CompPathGeometry.insidePath(openTriangle, 20F, 75F));
    }

    @Test
    public void smoothTangentsFollowTheOneSixthRule()
    {
        CompPathGeometry.ArrayPath path = square(true);
        float[] tangents = CompPathGeometry.smoothTangents(path, 0);

        /* prev = (0,100), next = (100,0): d = (next - prev) / 6 = (16.67, -16.67). */
        assertEquals(16.6667F, tangents[2], 0.01F, "outX");
        assertEquals(-16.6667F, tangents[3], 0.01F, "outY");
        assertEquals(-16.6667F, tangents[0], 0.01F, "inX is the mirror");
        assertEquals(16.6667F, tangents[1], 0.01F, "inY is the mirror");

        /* Open path, first vertex: a missing previous neighbour contributes the vertex itself. */
        CompPathGeometry.ArrayPath open = openPath(new float[][] {{0F, 0F}, {60F, 0F}, {60F, 60F}});
        float[] first = CompPathGeometry.smoothTangents(open, 0);

        assertEquals(10F, first[2], 0.01F);
        assertEquals(0F, first[3], 0.01F);

        /* A single-point path has no direction: AE's [10,0] stub. */
        float[] single = CompPathGeometry.smoothTangents(openPath(new float[][] {{3F, 4F}}), 0);

        assertEquals(10F, single[2], 0.01F);
        assertEquals(-10F, single[0], 0.01F);
    }

    @Test
    public void constrain45SnapsDirectionButKeepsLength()
    {
        float[] snapped = CompPathGeometry.constrain45(10F, 10F, 110F, 15F);
        float length = (float) Math.hypot(100F, 5F);

        assertEquals(10F + length, snapped[0], 0.01F, "x moves along the snapped direction by the original length");
        assertEquals(10F, snapped[1], 0.01F, "y snaps to horizontal");

        float[] diagonal = CompPathGeometry.constrain45(0F, 0F, 100F, 95F);

        assertEquals(45D, Math.toDegrees(Math.atan2(diagonal[1], diagonal[0])), 0.01D);

        float[] zero = CompPathGeometry.constrain45(4F, 5F, 4F, 5F);

        assertEquals(4F, zero[0], 0F);
        assertEquals(5F, zero[1], 0F);
    }

    @Test
    public void mirrorAndClamp()
    {
        float[] mirror = CompPathGeometry.mirror(12F, -7F);

        assertEquals(-12F, mirror[0], 0F);
        assertEquals(7F, mirror[1], 0F);

        float[] clamped = CompPathGeometry.mirrorClamped(200F, 0F, 50F);

        assertEquals(-50F, clamped[0], 0.01F);
        assertEquals(0F, clamped[1], 0.01F);

        float[] noDirection = CompPathGeometry.mirrorClamped(0F, 0F, 50F);

        assertEquals(0F, noDirection[0], 0F);
        assertEquals(0F, noDirection[1], 0F);
    }

    @Test
    public void boundsCoverTheCurve()
    {
        float[] bounds = CompPathGeometry.bounds(square(true));

        assertNotNull(bounds);
        assertEquals(0F, bounds[0], 0.01F);
        assertEquals(0F, bounds[1], 0.01F);
        assertEquals(100F, bounds[2], 0.01F);
        assertEquals(100F, bounds[3], 0.01F);

        assertNull(CompPathGeometry.bounds(openPath(new float[0][])));
    }

    @Test
    public void degenerateNumbersNeverEscape()
    {
        CompPathGeometry.ArrayPath path = openPath(new float[][] {
            {Float.NaN, 0F, 0F, 0F, 0F, 0F},
            {10F, Float.POSITIVE_INFINITY, 0F, 0F, 0F, 0F},
            {20F, 20F, Float.NaN, 0F, 0F, 0F}
        });

        float[] nearest = CompPathGeometry.nearestOnPath(path, 5F, 5F);

        assertNotNull(nearest);
        assertFalse(Float.isNaN(nearest[2]) || Float.isInfinite(nearest[2]), "distance must stay finite");
        assertNotNull(CompPathGeometry.bounds(path));
        CompPathGeometry.insidePath(path, 5F, 5F);
    }
}
