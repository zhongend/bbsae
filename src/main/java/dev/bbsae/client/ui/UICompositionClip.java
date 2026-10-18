package dev.bbsae.client.ui;

import dev.bbsae.client.BbsAeClient;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;

/**
 * Inspector for a composition clip on the camera timeline. It edits the project/comp reference
 * and the timing/opacity values; the full compositor workspace is a separate dashboard panel.
 */
public class UICompositionClip extends UIClip<CompositionClip>
{
    public UITextbox project;
    public UITextbox comp;
    public UITrackpad offset;
    public UITrackpad timeScale;
    public UITrackpad opacity;
    public UITrackpad fit;
    public UIToggle hold;
    public UIButton edit;
    public UIButton useCurrent;

    public UICompositionClip(CompositionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.project = this.textbox(64, this.clip.project);
        this.comp = this.textbox(64, this.clip.comp);
        this.offset = this.trackpad(this.clip.offset);
        this.timeScale = this.trackpad(this.clip.timeScale);
        this.opacity = this.trackpad(this.clip.opacity);
        this.fit = this.trackpad(this.clip.fit);
        this.fit.limit(0, 2, true);
        this.hold = this.toggle(IKey.constant("Hold Last Frame"), this.clip.hold);
        this.useCurrent = new UIButton(IKey.constant("Bind to Current Replay Project"), (button) ->
        {
            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            if (panel == null || panel.getData() == null)
            {
                return;
            }

            Film film = panel.getData();
            String projectId = "film_" + dev.bbsae.comp.CompStore.safe(film.getId());
            mchorse.bbs_mod.film.replays.Replay replay = panel.replayEditor == null ? null : panel.replayEditor.getReplay();
            String compId = replay == null ? "main" : dev.bbsae.comp.CompStore.replayCompId(replay.getId(), replay.getName());

            this.clip.project.set(projectId);
            this.clip.comp.set(compId);
            this.project.setText(projectId);
            this.comp.setText(compId);
        });
        this.edit = new UIButton(IKey.constant("Edit Composition"), (button) ->
        {
            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            if (panel != null && panel.getData() != null)
            {
                BbsAeClient.openCompositorForClip(panel, panel.getData(), this.clip);
            }
            else
            {
                BbsAeClient.openCompositor();
            }
        });
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(IKey.constant("Composition"), this.project, this.comp, this.useCurrent, this.edit));
        this.panels.add(this.section(IKey.constant("Timing"), this.offset, this.timeScale, this.opacity));
        this.panels.add(this.section(IKey.constant("Fit"), this.fit, this.hold));
    }
}
