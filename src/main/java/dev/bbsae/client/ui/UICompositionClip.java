package dev.bbsae.client.ui;

import dev.bbsae.client.BbsAeClient;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspector for a composition clip on the camera timeline.
 *
 * <p>This is the only place where a film and a compositor project meet. The clip stores just a
 * project id + comp id, so the film file stays small and the project can be edited independently.
 * The inspector therefore offers project selection, comp selection and the framing controls,
 * plus a direct route into the full compositor.</p>
 */
public class UICompositionClip extends UIClip<CompositionClip>
{
    private static final List<String> FITS = List.of("适应", "填充", "拉伸");

    public UITextbox project;
    public UITextbox comp;
    public UITrackpad offset;
    public UITrackpad timeScale;
    public UITrackpad opacity;
    public UIChoiceButton<String> fit;
    public UIToggle hold;
    public UIButton edit;
    public UIButton useCurrent;
    public UIButton chooseProject;
    public mchorse.bbs_mod.ui.framework.elements.utils.UILabel info;

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
        this.fit = new UIChoiceButton<>(FITS, (value) -> Icons.MAXIMIZE, IKey::constant);
        this.fit.callback((value) ->
        {
            int index = FITS.indexOf(value);

            this.clip.fit.set(index < 0 ? 0 : index);
        });
        this.fit.setValue(FITS.get(Math.max(0, Math.min(2, this.clip.fit.get()))));
        this.hold = this.toggle(IKey.constant("保持末帧"), this.clip.hold);

        this.info = new mchorse.bbs_mod.ui.framework.elements.utils.UILabel(IKey.constant(""));
        this.info.color(0xFF8A94A6, false);

        this.chooseProject = new UIButton(IKey.constant("选择工程…"), (button) -> this.openProjectPicker());

        this.useCurrent = new UIButton(IKey.constant("绑定到当前回放工程"), (button) ->
        {
            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            if (panel == null || panel.getData() == null)
            {
                return;
            }

            Film film = panel.getData();
            String projectId = "film_" + CompStore.safe(film.getId());
            mchorse.bbs_mod.film.replays.Replay replay = panel.replayEditor == null ? null : panel.replayEditor.getReplay();
            String compId = replay == null ? "main" : CompStore.replayCompId(replay.getId(), replay.getName());

            this.clip.project.set(projectId);
            this.clip.comp.set(compId);
            this.project.setText(projectId);
            this.comp.setText(compId);
            this.refreshInfo();
        });
        this.useCurrent.tooltip(IKey.constant("把片段指向当前影片/回放的工程"));

        this.edit = new UIButton(IKey.constant("打开合成器…"), (button) ->
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
        this.edit.tooltip(IKey.constant("在独立面板中编辑这个合成（不会覆盖影片编辑器）"));

        this.project.callback = (text) ->
        {
            this.clip.project.set(text);
            this.refreshInfo();
        };
        this.comp.callback = (text) ->
        {
            this.clip.comp.set(text);
            this.refreshInfo();
        };

        this.refreshInfo();
    }

    private void refreshInfo()
    {
        if (this.info == null)
        {
            return;
        }

        CompProject project = CompStore.get(this.clip.project.get());

        if (project == null)
        {
            this.info.label = IKey.constant("工程不存在：" + this.clip.project.get());

            return;
        }

        CompDocument comp = project.getComp(this.clip.comp.get());

        if (comp == null)
        {
            this.info.label = IKey.constant("工程 " + project.name + " 中没有合成 " + this.clip.comp.get());

            return;
        }

        this.info.label = IKey.constant(comp.width + "×" + comp.height + "  ·  "
            + String.format(java.util.Locale.ROOT, "%.0f fps", comp.fps) + "  ·  "
            + String.format(java.util.Locale.ROOT, "%.1fs", comp.duration) + "  ·  " + comp.layers.size() + " 图层");
    }

    private void openProjectPicker()
    {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (CompStore.ProjectInfo info : CompStore.listProjects())
        {
            labels.add(info.name + "  ·  " + info.summary());
            ids.add(info.id);
        }

        if (labels.isEmpty())
        {
            return;
        }

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("选择工程"), (value) ->
        {
            int index = labels.indexOf(value);

            if (index < 0)
            {
                return;
            }

            String id = ids.get(index);

            this.clip.project.set(id);
            this.project.setText(id);

            CompProject project = CompStore.load(id);
            CompDocument first = project == null ? null : project.getCompOrFirst(null);

            if (first != null)
            {
                this.clip.comp.set(first.id);
                this.comp.setText(first.id);
            }

            this.refreshInfo();
        });

        panel.addValues(labels);
        UIOverlay.addOverlay(this.getContext(), panel, 0.45F, 0.7F);
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(IKey.constant("合成器"), this.project, this.comp, this.chooseProject,
            this.useCurrent, this.edit, this.info));
        this.panels.add(this.section(IKey.constant("时间"), this.offset, this.timeScale, this.opacity));
        this.panels.add(this.section(IKey.constant("适配"), this.fit, this.hold));
    }
}
