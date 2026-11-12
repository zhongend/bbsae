package dev.bbsae.client.replay;

/**
 * Rate limiter for the replay render passes' failure logs.
 *
 * <p>A model pass runs once per frame; a form that fails every frame would otherwise fill the
 * client log with thousands of identical stack traces and drown the one that matters. The rule is
 * "first failure immediately, then at most one line per interval, with a suppressed count" — the
 * same shape as the rest of bbsae's throttled diagnostics.</p>
 *
 * <p>Pure, clock-injected logic so it is testable without sleeping.</p>
 */
public final class ReplayLogLimiter
{
    public static final long DEFAULT_INTERVAL_MS = 5000L;

    private final long intervalMs;
    private long lastMs = Long.MIN_VALUE;
    private int suppressed;

    public ReplayLogLimiter()
    {
        this(DEFAULT_INTERVAL_MS);
    }

    public ReplayLogLimiter(long intervalMs)
    {
        this.intervalMs = Math.max(0L, intervalMs);
    }

    /**
     * @return true when a line should be written now
     */
    public boolean allow(long nowMs)
    {
        if (this.lastMs == Long.MIN_VALUE || nowMs - this.lastMs >= this.intervalMs)
        {
            this.lastMs = nowMs;

            return true;
        }

        this.suppressed += 1;

        return false;
    }

    /** How many events were swallowed since the last allowed line; resets the counter. */
    public int takeSuppressed()
    {
        int value = this.suppressed;

        this.suppressed = 0;

        return value;
    }

    /** The current suppressed count without resetting it (diagnostics/readouts). */
    public int suppressed()
    {
        return this.suppressed;
    }

    /** Human suffix for the log line: empty on the first failure, " (+N suppressed)" later. */
    public String describeSuppressed()
    {
        int value = this.takeSuppressed();

        return value <= 0 ? "" : " (+" + value + " suppressed)";
    }

    public void reset()
    {
        this.lastMs = Long.MIN_VALUE;
        this.suppressed = 0;
    }
}
