package dev.bbsae.comp;

/**
 * Pure decision logic for repairing composition durations that were stretched by the old
 * "composition duration = film duration" behaviour.
 *
 * <p>Duration is editorial content, not a property of the film it is cut into. Older builds raised
 * every composition to {@code film.calculateDuration() / 20} on open; BBS camera timelines commonly
 * carry a 30-minute placeholder, so a 12-second composition became a 30-minute empty canvas and the
 * timeline fitted to the tail. The mutation itself lives in the panel (it needs status text and a
 * save), but the "is this damage?" rule is here so it can be tested without a client.</p>
 *
 * <p>Both checks are deliberately narrow: they only fire on a duration that still equals the film
 * length (or an unmistakable whole-minute empty tail) while real content ends far earlier. A
 * composition that uses its length — layers, audio, work area — is never touched.</p>
 */
public final class CompDurationRepair
{
    /** Shortest repair target; a composition should still be usable as a canvas. */
    public static final float MIN_REPAIRED = 12F;

    private CompDurationRepair()
    {}

    /**
     * Repair value for a composition that was inflated from a known film length.
     *
     * @return the corrected duration, or {@link Float#NaN} when the composition does not carry that
     *         damage fingerprint
     */
    public static float fromFilm(CompDocument doc, float filmDuration)
    {
        if (doc == null || filmDuration <= 0F || Float.isNaN(filmDuration))
        {
            return Float.NaN;
        }

        float content = doc.contentEnd();

        if (doc.duration <= 60F
            || Math.abs(doc.duration - filmDuration) > 0.05F
            || content >= doc.duration * 0.5F)
        {
            return Float.NaN;
        }

        return Math.max(MIN_REPAIRED, Math.min(3600F, content));
    }

    /**
     * Repair value when no film is bound (standalone project open).
     *
     * <p>Requires a whole-minute duration of at least ten minutes, a full-length work area and less
     * than a minute of real content. That is the exact shape of the 30-minute placeholder damage; a
     * long composition with actual layers/audio is left alone.</p>
     */
    public static float implausibleLong(CompDocument doc)
    {
        if (doc == null)
        {
            return Float.NaN;
        }

        float content = doc.contentEnd();
        boolean wholeMinute = Math.abs(doc.duration - Math.round(doc.duration / 60F) * 60F) <= 0.05F;

        if (doc.duration < 600F || content >= 60F || !wholeMinute
            || doc.workAreaOut < doc.duration - 0.1F)
        {
            return Float.NaN;
        }

        return Math.max(MIN_REPAIRED, content);
    }
}
