package dev.bbsae.comp;

/**
 * The pure-numbers half of the point tracker: normalized cross-correlation template matching on
 * luma planes.
 *
 * <p>Deliberately free of GL/UI/document types so it is unit-testable in milliseconds: a synthetic
 * texture with a distinctive blob proves the matcher finds the blob after a known offset, and a
 * uniform texture proves the confidence collapses instead of returning a random point. The engine
 * feeds it luma extracted from rendered frames; the inspector only reads back positions.</p>
 */
public final class TrackerMatcher
{
    /** Match result: best centre in search-plane pixels, {@code [x, y, score 0..1]}. */
    public static final class Result
    {
        public final int x;
        public final int y;
        public final float score;

        Result(int x, int y, float score)
        {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    private TrackerMatcher()
    {}

    /** Rec.709-ish luma of one ARGB pixel, 0..255. */
    public static float luma(int argb)
    {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        return 0.2126F * r + 0.7152F * g + 0.0722F * b;
    }

    /**
     * Extracts a luma window from an ARGB buffer.
     *
     * @param buffer row-major ARGB, {@code bufferW}×{@code bufferH}
     * @param cx     window centre x
     * @param cy     window centre y (row 0 is the top row, like composition space)
     * @param half   window half-size; the output is {@code (half*2+1)}²
     */
    public static float[] window(int[] buffer, int bufferW, int bufferH, int cx, int cy, int half)
    {
        int size = half * 2 + 1;
        float[] out = new float[size * size];

        for (int j = 0; j < size; j++)
        {
            int y = Math.max(0, Math.min(bufferH - 1, cy - half + j));

            for (int i = 0; i < size; i++)
            {
                int x = Math.max(0, Math.min(bufferW - 1, cx - half + i));

                out[i + j * size] = luma(buffer[x + y * bufferW]);
            }
        }

        return out;
    }

    /**
     * Finds the template inside the search plane.
     *
     * @param template row-major luma, {@code tw}×{@code th}
     * @param search   row-major luma, {@code sw}×{@code sh}, at least as large as the template
     * @return the best template <b>centre</b> in search-plane coordinates plus the NCC score;
     *         score 1 = identical, 0 = uncorrelated, negative = inverted. A flat template/search
     *         returns score 0 at the search centre.
     */
    public static Result match(float[] template, int tw, int th, float[] search, int sw, int sh)
    {
        int cols = sw - tw + 1;
        int rows = sh - th + 1;

        if (cols <= 0 || rows <= 0)
        {
            return new Result(sw / 2, sh / 2, 0F);
        }

        float templateMean = mean(template);
        float templateDev = deviation(template, templateMean);
        int centreX = sw / 2;
        int centreY = sh / 2;

        /* A flat template carries no information: any position is equally (un)likely. */
        if (templateDev <= 0.0001F)
        {
            return new Result(centreX, centreY, 0F);
        }

        float bestScore = -2F;
        int bestX = centreX;
        int bestY = centreY;

        for (int row = 0; row < rows; row++)
        {
            for (int col = 0; col < cols; col++)
            {
                float sum = 0F;

                for (int j = 0; j < th; j++)
                {
                    int searchRow = (row + j) * sw + col;
                    int templateRow = j * tw;

                    for (int i = 0; i < tw; i++)
                    {
                        sum += search[searchRow + i] * template[templateRow + i];
                    }
                }

                /* NCC with the template mean/variance precomputed; the search window's own mean and
                 * deviation are folded into the running sums. */
                float n = tw * th;
                float searchSum = windowSum(search, sw, col, row, tw, th);
                float searchMean = searchSum / n;
                float searchDev = windowDeviation(search, sw, col, row, tw, th, searchMean);

                if (searchDev <= 0.0001F)
                {
                    continue;
                }

                float score = (sum - n * templateMean * searchMean) / (n * templateDev * searchDev);

                if (score > bestScore)
                {
                    bestScore = score;
                    bestX = col + tw / 2;
                    bestY = row + th / 2;
                }
            }
        }

        return new Result(bestX, bestY, Math.max(-1F, Math.min(1F, bestScore)));
    }

    private static float mean(float[] data)
    {
        double sum = 0D;

        for (float value : data)
        {
            sum += value;
        }

        return (float) (sum / Math.max(1, data.length));
    }

    private static float deviation(float[] data, float mean)
    {
        double sum = 0D;

        for (float value : data)
        {
            double d = value - mean;

            sum += d * d;
        }

        return (float) Math.sqrt(sum / Math.max(1, data.length));
    }

    private static float windowSum(float[] search, int sw, int col, int row, int tw, int th)
    {
        float sum = 0F;

        for (int j = 0; j < th; j++)
        {
            int at = (row + j) * sw + col;

            for (int i = 0; i < tw; i++)
            {
                sum += search[at + i];
            }
        }

        return sum;
    }

    private static float windowDeviation(float[] search, int sw, int col, int row, int tw, int th, float mean)
    {
        double sum = 0D;

        for (int j = 0; j < th; j++)
        {
            int at = (row + j) * sw + col;

            for (int i = 0; i < tw; i++)
            {
                double d = search[at + i] - mean;

                sum += d * d;
            }
        }

        return (float) Math.sqrt(sum / Math.max(1D, (double) tw * th));
    }
}
