package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * A two-dimensional point tracker, the small AE "tracker" that answers "where did this feature go".
 *
 * <p>The user picks an anchor point (composition pixels), a frame range and two sizes — the
 * <b>pattern</b> (the template tracked from frame to frame) and the <b>search</b> radius (how far
 * the feature may move per frame). {@code TrackerEngine} then walks the composition frames one at a
 * time and stores one sample per frame. Two consumers read the result:</p>
 *
 * <ul>
 *   <li><b>Bake to position</b> — the inspector writes the samples into a layer's x/y channels as
 *       ordinary keyframes, which is what most motion-graphic shots need.</li>
 *   <li><b>Expressions</b> — {@code tracker.&lt;id&gt;.x} / {@code tracker.&lt;id&gt;.y} resolve to
 *       the tracked position of the current frame, so an effect parameter or a mask point can
 *       follow the feature without baking.</li>
 * </ul>
 *
 * <p>Samples are stored as {@code double[4]} rows {@code [frame, x, y, confidence]} — plain Gson
 * food, ordered by frame.</p>
 */
public class CompTracker
{
    /** Runtime states (not serialized): idle → tracking → done / failed. */
    public static final transient int STATE_IDLE = 0;
    public static final transient int STATE_TRACKING = 1;
    public static final transient int STATE_DONE = 2;
    public static final transient int STATE_FAILED = 3;

    public String id = "tracker";
    public String name = "跟踪器";
    public boolean enabled = true;

    /** Analysis range, composition frames (inclusive). */
    public int startFrame;
    public int endFrame;

    /** Anchor point, composition pixels (y down, like the preview). */
    public float x;
    public float y;

    /** Half-size of the tracked template, composition pixels. */
    public int pattern = 20;
    /** Per-frame movement radius, composition pixels. */
    public int search = 40;
    /** NCC score (0..1) below which tracking stops and reports a loss. */
    public float confidence = 0.35F;

    /** One row per analyzed frame: {@code [frame, x, y, confidence]}. */
    public List<double[]> samples = new ArrayList<>();

    /* ---- runtime (transient: Gson skips these, the engine owns them) ---- */
    public transient int state = STATE_IDLE;
    public transient int cursorFrame;
    public transient String message = "";

    public CompTracker()
    {}

    public CompTracker(String id, String name)
    {
        this.id = id;
        this.name = name;
    }

    public CompTracker copy()
    {
        CompTracker copy = new CompTracker(this.id, this.name);

        copy.enabled = this.enabled;
        copy.startFrame = this.startFrame;
        copy.endFrame = this.endFrame;
        copy.x = this.x;
        copy.y = this.y;
        copy.pattern = this.pattern;
        copy.search = this.search;
        copy.confidence = this.confidence;

        for (double[] sample : this.samples)
        {
            copy.samples.add(sample == null ? null : sample.clone());
        }

        return copy;
    }

    /** The recorded position at {@code frame}, interpolated between neighbouring samples. */
    public float xAt(int frame)
    {
        double[] hit = sampleAt(frame);

        return hit == null ? this.x : (float) hit[1];
    }

    public float yAt(int frame)
    {
        double[] hit = sampleAt(frame);

        return hit == null ? this.y : (float) hit[2];
    }

    /** Interpolated sample row, or null when {@code frame} is outside the analyzed range. */
    public double[] sampleAt(int frame)
    {
        if (this.samples.isEmpty())
        {
            return null;
        }

        /* Outside the analyzed range there is no measurement to report: clamping to the first or
         * last sample would freeze the track at its end position and, through tracker.<id>.x/y,
         * silently hold the last value instead of falling back to the anchor the user placed. */
        if (frame < Math.round(this.samples.get(0)[0])
            || frame > Math.round(this.samples.get(this.samples.size() - 1)[0]))
        {
            return null;
        }

        int low = 0;
        int high = this.samples.size() - 1;

        /* Exact / bracketing lookup over an ordered list (samples always end up ordered:
         * the engine appends forward, the inspector can only clear or re-run). */
        while (low <= high)
        {
            int mid = (low + high) >>> 1;
            double[] row = this.samples.get(mid);
            int at = (int) Math.round(row[0]);

            if (at == frame)
            {
                return row;
            }

            if (at < frame)
            {
                low = mid + 1;
            }
            else
            {
                high = mid - 1;
            }
        }

        if (low <= 0)
        {
            return this.samples.get(0);
        }

        if (low >= this.samples.size())
        {
            return this.samples.get(this.samples.size() - 1);
        }

        double[] a = this.samples.get(low - 1);
        double[] b = this.samples.get(low);
        double t = (frame - a[0]) / Math.max(1D, b[0] - a[0]);

        return new double[]
        {
            frame,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t,
            Math.min(a[3], b[3])
        };
    }

    /** Ordered insertion of one analysis result. */
    public void putSample(int frame, float x, float y, float confidence)
    {
        for (int i = 0; i < this.samples.size(); i++)
        {
            double[] row = this.samples.get(i);

            if (Math.round(row[0]) == frame)
            {
                row[1] = x;
                row[2] = y;
                row[3] = confidence;

                return;
            }

            if (Math.round(row[0]) > frame)
            {
                this.samples.add(i, new double[] {frame, x, y, confidence});

                return;
            }
        }

        this.samples.add(new double[] {frame, x, y, confidence});
    }

    public void clearSamples()
    {
        this.samples.clear();
        this.state = STATE_IDLE;
        this.message = "";
    }
}
