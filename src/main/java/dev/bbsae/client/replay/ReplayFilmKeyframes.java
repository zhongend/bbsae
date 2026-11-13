package dev.bbsae.client.replay;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.function.Consumer;

/**
 * Film keyframe timeline backed by the replay page's own cursor.
 *
 * <p>The embedded replay editor borrows the film panel, but BBS may keep rewriting that panel's
 * runner (its hidden playback, rewind-on-stop and other editor paths). The replay transport is
 * authoritative here, so the dope sheet asks {@link ReplayEditSession} for its local playhead
 * instead of re-reading the runner every frame. The editor still uses BBS' original keyframe
 * widgets, ruler and channel sheets; only the clock source changes.</p>
 */
public class ReplayFilmKeyframes extends UIFilmKeyframes
{
    private final ReplayEditSession session;

    public ReplayFilmKeyframes(IUIClipsDelegate delegate, Consumer<Keyframe> callback, ReplayEditSession session)
    {
        super(delegate, callback);

        this.session = session;
    }

    @Override
    public float getTick()
    {
        return this.session == null ? super.getTick() : this.session.playhead();
    }

    @Override
    public float getPlayheadTick(UIContext context)
    {
        return this.session == null ? super.getPlayheadTick(context) : this.session.playhead();
    }

    @Override
    public Float getAutoKeyframeTick()
    {
        if (this.session != null && BBSSettings.autoKeyframe.get())
        {
            return this.session.keyframeTick();
        }

        return super.getAutoKeyframeTick();
    }

    /**
     * BBS only starts a ruler/playhead scrub when the graph reports a writable cursor. The base
     * implementation asks the borrowed camera editor, which can be absent while the film panel is
     * hidden behind this page. The replay session owns the clock and is always the right answer.
     */
    @Override
    protected boolean hasCursor()
    {
        return this.session != null || super.hasCursor();
    }

    /**
     * The dope sheet's ruler/playhead drag writes back through {@code editor.setCursor(...)} in
     * BBS' {@link UIFilmKeyframes}. That delegate is the borrowed film panel's camera editor, and
     * this page deliberately does not use its cursor: the replay transport owns the clock. The
     * inherited call therefore moved a cursor nobody reads and the needle snapped back on the next
     * frame — the "playhead cannot be dragged" bug.
     *
     * <p>Override the single scrub entry point instead: stop the replay transport and seek the
     * replay session's own playhead. Both the initial click and every drag/release event call this
     * method, so the needle, the 3D viewport and the actor pose all follow the mouse.</p>
     */
    @Override
    protected void moveNoKeyframes(UIContext context)
    {
        if (Boolean.getBoolean("bbsae.debugReplayScrub"))
        {
            dev.bbsae.BbsAeAddon.LOGGER.info("Replay scrub: moveNoKeyframes x={} graphTick={} session={}",
                context.mouseX, this.fromGraphX(context.mouseX), this.session != null);
        }

        if (this.session == null)
        {
            super.moveNoKeyframes(context);

            return;
        }

        this.session.setPlaying(false);
        this.session.seek((float) this.fromGraphX(context.mouseX));
    }

    /** Test-only: call the exact scrub handler without needing a real mouse coordinate. */
    public void debugScrub(int mouseX)
    {
        UIContext context = new UIContext(mchorse.bbs_mod.BBSModClient.getDashboard());

        context.mouseX = mouseX;
        this.moveNoKeyframes(context);
    }

    /** Test-only access to the ruler's state. */
    public boolean debugHasCursor()
    {
        return this.hasCursor();
    }

    /** Test-only access to the graph-to-tick mapping. */
    public float debugGraphTick(int mouseX)
    {
        return (float) this.fromGraphX(mouseX);
    }

    /**
     * Previous/next keyframe shortcuts in BBS also move the film cursor through the camera editor.
     * Route them to the replay session so the playhead follows the selected keyframe there too.
     */
    @Override
    protected void selectNextKeyframe(int direction)
    {
        super.selectNextKeyframe(direction);

        if (this.session != null)
        {
            Keyframe selected = this.getGraph().getSelected();

            if (selected != null)
            {
                this.session.seek(selected.getTick());
            }
        }
    }
}
