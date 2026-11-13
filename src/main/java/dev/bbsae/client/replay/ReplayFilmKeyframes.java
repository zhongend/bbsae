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
}
