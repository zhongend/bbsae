package dev.bbsae.comp;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera-timeline clip that puts a composition over the finished frame.
 *
 * <p>The clip deliberately does not touch the camera {@link Position}. It only contributes a
 * {@link CompInstance} to {@code ClipContext.clipData}, exactly like BBS's {@code ImageClip}
 * contributes its images. The actual drawing happens in the frame overlay, after the world has
 * been rendered and before BBS blits the frame into the export texture.</p>
 */
public class CompositionClip extends CameraClip
{
    public static final String KEY = "bbsae:instances";

    public final ValueString project = new ValueString("project", "demo");
    public final ValueString comp = new ValueString("comp", "main");
    public final ValueInt offset = new ValueInt("offset", 0, -100000, 100000);
    public final ValueFloat timeScale = new ValueFloat("time_scale", 1F, -10F, 10F);
    public final ValueFloat opacity = new ValueFloat("opacity", 1F, 0F, 1F);
    public final ValueInt fit = new ValueInt("fit", 0, 0, 2);
    public final ValueBoolean hold = new ValueBoolean("hold", true);

    /**
     * Set when the user split this clip (or otherwise placed it by hand).
     *
     * <p>The compositor keeps a single composition clip covering the whole film by default, and
     * every duration edit rewrites its tick/duration. That convenience would silently undo a
     * split: the first half would be stretched over the second, or its source offset reset. A
     * manual clip therefore opts out of the automatic rewrite until the user explicitly restores
     * automatic timing.</p>
     */
    public final ValueBoolean manualTiming = new ValueBoolean("manual_timing", false);

    /** Number of clips that point at the same project/composition. */
    public static int countForFilm(mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        if (film == null || projectId == null || compId == null)
        {
            return 0;
        }

        int count = 0;

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            if (projectId.equals(clip.project.get()) && compId.equals(clip.comp.get()))
            {
                count += 1;
            }
        }

        return count;
    }

    /**
     * Film length that does not include composition clips.
     *
     * <p>{@code film.calculateDuration()} includes this clip's own duration, and the clip is kept
     * as long as the film. Feeding that back into {@code updateForFilm} made the pair a ratchet:
     * the composition grew, the clip grew, the film grew, and the next open inherited the larger
     * film length. Measuring only the other camera clips breaks the loop, so a composition can be
     * shortened again.</p>
     */
    public static int editorialDurationTicks(mchorse.bbs_mod.film.Film film)
    {
        if (film == null)
        {
            return 0;
        }

        int max = 0;

        for (Clip clip : film.camera.get())
        {
            if (clip instanceof CompositionClip)
            {
                continue;
            }

            max = Math.max(max, clip.tick.get() + clip.duration.get());
        }

        return max;
    }

    /**
     * Finds the composition clip for one project/comp without creating or modifying anything.
     *
     * <p>Opening the compositor is a view action; it must not add a clip to the film timeline. A
     * clip is document content (it is what makes BBS sample the composition into the final frame),
     * so it is only added by an explicit action or by an export that needs it.</p>
     */
    public static CompositionClip findForFilm(mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        if (film == null || projectId == null || compId == null)
        {
            return null;
        }

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            if (projectId.equals(clip.project.get()) && compId.equals(clip.comp.get()))
            {
                return clip;
            }
        }

        return null;
    }

    /**
     * Updates an existing composition clip's timing/title, and never creates one.
     *
     * <p>Used by every duration/edit path in the compositor: the film timeline is only touched when
     * the composition is already on it. {@link #ensureForFilm} stays for the explicit "add to film
     * timeline" action and for export preparation.</p>
     */
    public static CompositionClip updateForFilm(mchorse.bbs_mod.film.Film film, String projectId,
                                                String compId, float compDurationSeconds)
    {
        CompositionClip clip = findForFilm(film, projectId, compId);

        if (clip == null)
        {
            return null;
        }

        /* A split/manual clip is intentional timing. Rewriting it here is what would merge the
         * two halves back into one full-length clip after any duration edit. */
        if (clip.manualTiming.get() || countForFilm(film, projectId, compId) > 1)
        {
            return clip;
        }

        int duration = exportDurationTicks(editorialDurationTicks(film), compDurationSeconds);

        clip.tick.set(0);
        clip.duration.set(duration);

        if (clip.title.get() == null || clip.title.get().isBlank() || "Compositor".equals(clip.title.get()))
        {
            clip.title.set("合成器");
        }

        return clip;
    }

    /**
     * Adds the composition instance clip if it is missing (explicit "add to film timeline" and
     * export preparation). Never called by merely opening the compositor; see
     * {@link #updateForFilm}.
     */
    public static CompositionClip ensureForFilm(mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        return ensureForFilm(film, projectId, compId, 0F);
    }

    /**
     * @param compDurationSeconds the composition's duration, or 0 when unknown. The clip is kept
     *                            at tick 0 and made as long as the longer of the film and the
     *                            composition, so neither BBS's 12-second default nor an earlier
     *                            trim can cut the composition short.
     */
    /**
     * BBS camera clips are measured in ticks (20 per second). A composition clip must cover the
     * longer of the film and the composition, otherwise BBS's recorder stops at the film's old
     * length (the 12-second camera default) and the rest of the composition is never recorded.
     */
    public static int exportDurationTicks(int filmTicks, float compDurationSeconds)
    {
        int filmDuration = Math.max(20, filmTicks);
        int compDuration = compDurationSeconds > 0F
            ? Math.max(20, Math.round(Math.min(CompDocument.MAX_CANVAS, compDurationSeconds) * 20F) + 1)
            : 20;
        int duration = Math.max(filmDuration, compDuration);

        /* A composition clip is a camera-timeline window, not a second film. Ten-hour clips came
         * from copying a film tick count or a damaged composition duration; half an hour is the
         * same generous ceiling the composition itself uses. */
        return Math.max(20, Math.min((int) (CompDocument.MAX_CANVAS * 20F) + 1, duration));
    }

    public static CompositionClip ensureForFilm(mchorse.bbs_mod.film.Film film, String projectId, String compId, float compDurationSeconds)
    {
        if (film == null)
        {
            return null;
        }

        int duration = exportDurationTicks(editorialDurationTicks(film), compDurationSeconds);

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            if (projectId.equals(clip.project.get()) && compId.equals(clip.comp.get()))
            {
                /* A split clip already covers its own window; forcing tick 0 / full duration here
                 * would silently undo the cut. */
                if (!clip.manualTiming.get() && countForFilm(film, projectId, compId) == 1)
                {
                    clip.tick.set(0);
                    clip.duration.set(duration);
                }

                if (clip.title.get() == null || clip.title.get().isBlank() || "Compositor".equals(clip.title.get()))
                {
                    clip.title.set("合成器");
                }

                dev.bbsae.BbsAeAddon.LOGGER.info(
                    "Composition clip found for film {}: project={} comp={} tick={} duration={} layer={} opacity={} manual={}",
                    film.getId(), projectId, compId, clip.tick.get(), clip.duration.get(), clip.layer.get(), clip.opacity.get(),
                    clip.manualTiming.get()
                );

                return clip;
            }
        }

        CompositionClip clip = new CompositionClip();

        clip.project.set(projectId);
        clip.comp.set(compId);
        clip.title.set("合成器");
        clip.tick.set(0);
        clip.duration.set(duration);
        clip.layer.set(0);
        film.camera.addClip(clip);

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "Composition clip created for film {}: project={} comp={} duration={}",
            film.getId(), projectId, compId, duration
        );

        return clip;
    }

    /**
     * Export must not depend on where the composition clip happened to be trimmed on the camera
     * timeline. The compositor preview always shows the whole film; if the clip only covered the
     * first two seconds, the recorder would show BBS's raw frame for the rest of the video. This
     * normalizes the current film's matching clip to cover the entire film before an export.
     */
    public static CompositionClip prepareForExport(mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        return prepareForExport(film, projectId, compId, 0F);
    }

    /**
     * @param compDurationSeconds the composition's own duration. When it is longer than the film's
     *                            camera timeline, the clip is extended so BBS's recorder does not
     *                            stop at the old film length (the default 12 seconds).
     */
    public static CompositionClip prepareForExport(mchorse.bbs_mod.film.Film film, String projectId, String compId, float compDurationSeconds)
    {
        CompositionClip clip = ensureForFilm(film, projectId, compId);

        if (clip == null)
        {
            return null;
        }

        /* A split composition is two (or more) windows of the same comp. Flattening them into one
         * full-length clip here would draw the composition twice and destroy the user's cut. The
         * export recorder's length is supplied separately through the loop range, so leaving the
         * windows alone is safe. */
        if (clip.manualTiming.get() || countForFilm(film, projectId, compId) > 1)
        {
            return clip;
        }

        int filmDuration = Math.max(20, editorialDurationTicks(film));
        int compDuration = compDurationSeconds > 0F
            ? Math.max(20, Math.round(Math.min(CompDocument.MAX_CANVAS, compDurationSeconds) * 20F) + 1)
            : 20;
        int duration = exportDurationTicks(filmDuration, compDurationSeconds);

        clip.tick.set(0);
        clip.duration.set(duration);
        clip.layer.set(0);
        clip.opacity.set(1F);
        clip.timeScale.set(1F);
        clip.offset.set(0);
        clip.hold.set(true);

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "Composition export clip prepared: compDuration={}s ({} ticks), filmDuration={} ticks, clipDuration={} ticks",
            compDurationSeconds, compDuration, filmDuration, duration
        );

        return clip;
    }

    public CompositionClip()
    {
        super();

        /* The clip's default label used to be the raw link path ("composition"), which is what
         * showed up on the camera timeline. Name it like the panel it opens. */
        this.title.set("合成器");

        this.add(this.project);
        this.add(this.comp);
        this.add(this.offset);
        this.add(this.timeScale);
        this.add(this.opacity);
        this.add(this.fit);
        this.add(this.hold);
        this.add(this.manualTiming);
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        float factor = this.envelope.factorEnabled(this.duration.get(), context.relativeTick + context.transition);
        float alpha = this.opacity.get() * factor;

        if (alpha <= 0F)
        {
            return;
        }

        List<CompInstance> instances = context.clipData.get(KEY, () -> new ArrayList<CompInstance>());

        instances.add(new CompInstance(
            this.project.get(),
            this.comp.get(),
            context.relativeTick + context.transition,
            context.ticks + context.transition,
            this.offset.get(),
            this.timeScale.get(),
            alpha,
            this.fit.get(),
            this.hold.get()
        ));
    }

    @Override
    protected void breakDownClip(Clip original, int offset)
    {
        super.breakDownClip(original, offset);

        if (original instanceof CompositionClip source)
        {
            /* The right half restarts at relative tick 0 while the composition must continue at
             * the cut. Composition time is (ticks * timeScale + offset) / 20, so advancing the
             * offset by the cut distance times the clip's time scale keeps the picture continuous
             * for every time scale, including negative ones. */
            long shifted = (long) source.offset.get() + Math.round(offset * (double) source.timeScale.get());

            this.offset.set((int) Math.max(-100000L, Math.min(100000L, shifted)));
            this.manualTiming.set(true);
            source.manualTiming.set(true);
        }
    }

    @Override
    protected Clip create()
    {
        return new CompositionClip();
    }
}
