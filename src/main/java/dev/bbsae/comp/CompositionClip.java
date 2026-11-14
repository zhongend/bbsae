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

        int duration = exportDurationTicks(film.calculateDuration(), compDurationSeconds);

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
            ? Math.max(20, Math.round(compDurationSeconds * 20F) + 1)
            : 20;

        return Math.max(filmDuration, compDuration);
    }

    public static CompositionClip ensureForFilm(mchorse.bbs_mod.film.Film film, String projectId, String compId, float compDurationSeconds)
    {
        if (film == null)
        {
            return null;
        }

        int duration = exportDurationTicks(film.calculateDuration(), compDurationSeconds);

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            if (projectId.equals(clip.project.get()) && compId.equals(clip.comp.get()))
            {
                clip.tick.set(0);
                clip.duration.set(duration);

                if (clip.title.get() == null || clip.title.get().isBlank() || "Compositor".equals(clip.title.get()))
                {
                    clip.title.set("合成器");
                }

                dev.bbsae.BbsAeAddon.LOGGER.info(
                    "Composition clip found for film {}: project={} comp={} tick={} duration={} layer={} opacity={}",
                    film.getId(), projectId, compId, clip.tick.get(), clip.duration.get(), clip.layer.get(), clip.opacity.get()
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

        int filmDuration = Math.max(20, film.calculateDuration());
        int compDuration = compDurationSeconds > 0F
            ? Math.max(20, Math.round(compDurationSeconds * 20F) + 1)
            : 20;
        int duration = exportDurationTicks(film.calculateDuration(), compDurationSeconds);

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
            this.offset.get(),
            this.timeScale.get(),
            alpha,
            this.fit.get(),
            this.hold.get()
        ));
    }

    @Override
    protected Clip create()
    {
        return new CompositionClip();
    }
}
