package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tracker's matching maths, on synthetic luma planes: a distinctive blob must be found after a
 * known offset, a flat frame must collapse the confidence instead of inventing a position, and the
 * sample store must stay ordered and interpolate across frames.
 */
public class TrackerMatcherTest
{
    private static final int SIZE = 96;

    /** A flat frame with one 33×33 textured tile — the "moving textured feature" case. */
    private static int[] tileScene(long tileSeed, int cx, int cy)
    {
        int[] texture = new int[SIZE * SIZE];

        for (int i = 0; i < texture.length; i++)
        {
            texture[i] = (255 << 24) | (120 << 16) | (120 << 8) | 120;
        }

        Random random = new Random(tileSeed);
        int half = 16;

        for (int y = cy - half; y <= cy + half; y++)
        {
            for (int x = cx - half; x <= cx + half; x++)
            {
                if (x < 0 || y < 0 || x >= SIZE || y >= SIZE)
                {
                    continue;
                }

                int grey = 40 + random.nextInt(120);

                texture[x + y * SIZE] = (255 << 24) | (grey << 16) | (grey << 8) | grey;
            }
        }

        return texture;
    }

    @Test
    public void findsTheFeatureAfterAKnownOffset()
    {
        /* Same textured feature, moved by (+9, -5): the whole picture content travels with it,
         * which is what a real film frame does (the camera sees the same pixels, translated). */
        int[] previous = tileScene(42L, 48, 48);
        int[] current = tileScene(42L, 48 + 9, 48 - 5);

        /* The engine windows the search plane around the LAST position (48, 48); the feature sits
         * at (57, 43), i.e. (49, 35) inside that window. */
        float[] template = TrackerMatcher.window(previous, SIZE, SIZE, 48, 48, 16);
        float[] search = TrackerMatcher.window(current, SIZE, SIZE, 48, 48, 40);

        TrackerMatcher.Result result = TrackerMatcher.match(template, 33, 33, search, 81, 81);

        assertEquals(49, result.x, 1, "tracked x must follow the feature (±1 px)");
        assertEquals(35, result.y, 1, "tracked y must follow the feature (±1 px)");
        assertTrue(result.score > 0.95F, "the same texture must score ~1, got " + result.score);
    }

    @Test
    public void flatFrameReportsZeroConfidence()
    {
        float[] template = new float[33 * 33];

        java.util.Arrays.fill(template, 90F);

        float[] flat = new float[81 * 81];

        java.util.Arrays.fill(flat, 120F);

        TrackerMatcher.Result result = TrackerMatcher.match(template, 33, 33, flat, 81, 81);

        assertTrue(result.score <= 0.001F, "a flat search plane has no information: got " + result.score);
    }

    @Test
    public void distinctContentScoresPoorly()
    {
        int[] previous = tileScene(7L, 48, 48);
        int[] other = tileScene(99L, 48, 48);

        float[] template = TrackerMatcher.window(previous, SIZE, SIZE, 48, 48, 16);
        float[] search = TrackerMatcher.window(other, SIZE, SIZE, 48, 48, 40);

        TrackerMatcher.Result result = TrackerMatcher.match(template, 33, 33, search, 81, 81);

        assertTrue(result.score < 0.6F, "unrelated content must not look tracked, got " + result.score);
    }

    @Test
    public void samplesStayOrderedAndInterpolate()
    {
        CompTracker tracker = new CompTracker("t", "跟踪器");

        tracker.putSample(10, 100F, 200F, 0.9F);
        tracker.putSample(0, 0F, 0F, 1F);
        tracker.putSample(5, 50F, 100F, 0.8F);

        assertEquals(3, tracker.samples.size(), "samples insert in frame order");
        assertEquals(0, (int) Math.round(tracker.samples.get(0)[0]));
        assertEquals(10, (int) Math.round(tracker.samples.get(2)[0]));

        double[] mid = tracker.sampleAt(7);

        assertEquals(70F, (float) mid[1], 0.01F, "x interpolates between frames 5 and 10");
        assertEquals(140F, (float) mid[2], 0.01F, "y interpolates between frames 5 and 10");

        double[] exact = tracker.sampleAt(5);

        assertEquals(50F, (float) exact[1], 0.001F, "exact frame returns the stored sample");

        /* Outside the range the anchor values hold, so a binding never silently reads 0. */
        tracker.x = 5F;

        assertEquals(5F, tracker.xAt(999), 0.001F, "out-of-range frames fall back to the anchor");
    }
}
