package dev.bbsae.client.ui;

import java.util.Arrays;

/**
 * Cached geometry of one curve, one entry per screen column.
 *
 * <h2>Why not a polyline</h2>
 * <p>The first version drew a curve by walking every pixel between two samples and emitting a
 * quad per step ({@code line()} in the editor). That makes the vertex count proportional to the
 * curve's <em>slope</em>: a segment that rises 200 px in 2 px of width emitted 200 quads, so a
 * steep curve cost thousands of quads per frame and got slower the more the user dragged a handle.
 * A graph editor must cost the same for a steep curve as for a flat one.</p>
 *
 * <p>So the curve is rasterised into a column array instead: for each screen column we keep the
 * vertical span {@code [lo, hi]} that the curve covers. Drawing is then one rectangle per run of
 * similar columns — the vertex count is bounded by the width of the graph, not by the data.</p>
 *
 * <p>The array is kept across frames and only rebuilt when the view or the data changes. A handle
 * drag rebuilds the columns of the affected segment only.</p>
 */
final class CurveSpans
{
    /** Maps a screen column to the curve's pixel Y in the graph's coordinate space. */
    interface Sampler
    {
        int yAt(int column);
    }

    private int columns;
    private short[] lo = new short[0];
    private short[] hi = new short[0];
    private byte[] flags = new byte[0];

    /** Data stamp this array was built from; {@link Long#MIN_VALUE} means "never built". */
    long stamp = Long.MIN_VALUE;
    /** Packed view description (time range, value range, size, graph mode). */
    long viewKey;

    int columns()
    {
        return this.columns;
    }

    short lo(int column)
    {
        return this.lo[column];
    }

    short hi(int column)
    {
        return this.hi[column];
    }

    boolean has(int column)
    {
        return this.flags[column] != 0;
    }

    /** Resizes the arrays when the graph width changes; the content is invalidated. */
    void ensure(int columns)
    {
        if (this.columns == columns && this.lo.length == columns)
        {
            return;
        }

        this.columns = Math.max(1, columns);
        this.lo = new short[this.columns];
        this.hi = new short[this.columns];
        this.flags = new byte[this.columns];
        this.stamp = Long.MIN_VALUE;
        this.viewKey = 0L;
    }

    void clear()
    {
        Arrays.fill(this.flags, (byte) 0);
        this.stamp = Long.MIN_VALUE;
    }

    /**
     * Rebuilds columns {@code [from, to]} inclusive.
     *
     * <p>Each column stores the span between its own sample and the next one, so a near-vertical
     * segment is still continuous (no gaps between adjacent columns) without ever emitting more
     * than one rectangle per column.</p>
     */
    void build(int from, int to, Sampler sampler)
    {
        if (this.columns <= 0)
        {
            return;
        }

        int first = Math.max(0, Math.min(this.columns - 1, from));
        int last = Math.max(0, Math.min(this.columns - 1, to));

        for (int column = first; column <= last; column++)
        {
            int a = sampler.yAt(column);
            int b = sampler.yAt(Math.min(this.columns - 1, column + 1));
            int top = Math.min(a, b);
            int bottom = Math.max(a, b);

            if (bottom - top < 1)
            {
                bottom = top + 1;
            }

            this.lo[column] = clamp(top);
            this.hi[column] = clamp(bottom);
            this.flags[column] = 1;
        }
    }

    private static short clamp(int value)
    {
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;

        return (short) value;
    }
}
