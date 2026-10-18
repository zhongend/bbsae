package dev.bbsae.comp;

/**
 * One composition instance contributed by a {@link CompositionClip} for the current frame.
 *
 * <p>The overlay reads these from {@code ClipContext.clipData}; no global state is involved, so
 * two films (and the film editor preview) cannot leak instances into each other.</p>
 */
public final class CompInstance
{
    public final String project;
    public final String comp;
    public final float ticks;
    public final int offset;
    public final float timeScale;
    public final float opacity;
    public final int fit;
    public final boolean hold;

    public CompInstance(String project, String comp, float ticks, int offset, float timeScale, float opacity, int fit, boolean hold)
    {
        this.project = project;
        this.comp = comp;
        this.ticks = ticks;
        this.offset = offset;
        this.timeScale = timeScale;
        this.opacity = opacity;
        this.fit = fit;
        this.hold = hold;
    }

    /**
     * Composition-local time in seconds.
     *
     * <p>The offset is expressed in composition ticks and is applied after the clip's time scale,
     * matching the way the clip inspector labels it ("offset into the composition"). The value is
     * based on BBS's relative tick plus the sub-tick transition, never on wall-clock time, so an
     * export and a preview sample the same frame.</p>
     */
    public float time()
    {
        return ((this.ticks * this.timeScale) + this.offset) / 20F;
    }
}
