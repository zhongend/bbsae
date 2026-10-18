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
     * Makes sure a film's camera timeline actually carries the composition instance. Opening the
     * compositor from a film is an explicit promise that this film should show it; without the
     * clip the overlay is never sampled and the exported video silently has no compositor.
     */
    public static CompositionClip ensureForFilm(mchorse.bbs_mod.film.Film film, String projectId, String compId)
    {
        if (film == null)
        {
            return null;
        }

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            if (projectId.equals(clip.project.get()) && compId.equals(clip.comp.get()))
            {
                clip.duration.set(Math.max(clip.duration.get(), Math.max(20, film.calculateDuration())));

                if (clip.title.get() == null || clip.title.get().isBlank() || "Compositor".equals(clip.title.get()))
                {
                    clip.title.set("合成器");
                }

                return clip;
            }
        }

        CompositionClip clip = new CompositionClip();
        int duration = Math.max(20, film.calculateDuration());

        clip.project.set(projectId);
        clip.comp.set(compId);
        clip.title.set("Compositor");
        clip.tick.set(0);
        clip.duration.set(duration);
        clip.layer.set(0);
        film.camera.addClip(clip);

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
