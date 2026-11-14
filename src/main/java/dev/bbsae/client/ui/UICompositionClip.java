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
    public UIButton chooseComp;
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

        this.chooseProject = new UICompButton(IKey.constant("选择工程…"), (button) -> this.openProjectPicker());
        this.chooseComp = new UICompButton(IKey.constant("选择合成…"), (button) -> this.openCompPicker());

        this.useCurrent = new UICompButton(IKey.constant("绑定到当前回放工程"), (button) ->
        {
            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);

            if (panel == null || panel.getData() == null)
            {
                this.info.label = IKey.constant("没有打开的影片：先在影片编辑器打开一个影片");

                return;
            }

            Film film = panel.getData();
            int width = Math.max(1, mchorse.bbs_mod.BBSSettings.videoWidth.get());
            int height = Math.max(1, mchorse.bbs_mod.BBSSettings.videoHeight.get());
            float fps = Math.max(1F, mchorse.bbs_mod.BBSSettings.videoFrameRate.get());
            float duration = Math.max(0.1F, film.calculateDuration() / 20F);

            /* Make the binding actually resolvable instead of pointing at ids that may not exist
             * yet: a film project is created lazily, and a replay composition only appears the
             * first time that replay is opened. The old button computed the ids and left the clip
             * pointing at a missing project/comp, so "绑定" appeared to do nothing. */
            String projectId = "film_" + CompStore.safe(film.getId());
            CompProject project = CompStore.loadFilmProject(film.getId(), film.getId(), width, height, fps, duration);

            mchorse.bbs_mod.film.replays.Replay replay = panel.replayEditor == null ? null : panel.replayEditor.getReplay();
            CompDocument comp = replay == null
                ? project.getCompOrFirst(CompStore.DEFAULT_COMP)
                : CompStore.ensureReplayComp(project, replay.getId(), replay.getName(), width, height, fps, duration);

            CompStore.save(project);

            this.clip.project.set(projectId);
            this.project.setText(projectId);

            if (comp != null)
            {
                this.clip.comp.set(comp.id);
                this.comp.setText(comp.id);
            }

            this.refreshInfo();
            panel.forceSave();

            if (comp != null)
            {
                this.info.label = IKey.constant("已绑定到 " + projectId + " / " + comp.id);
            }
            else if (this.info != null)
            {
                this.info.label = IKey.constant("工程已建立，但没有合成可绑定：打开合成器新建一个合成");
            }
        });
        this.useCurrent.tooltip(IKey.constant("把片段指向当前影片/回放的工程"));

        this.edit = new UICompButton(IKey.constant("打开合成器…"), (button) ->
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

    private void openCompPicker()
    {
        CompProject project = CompStore.get(this.clip.project.get());

        if (project == null || project.comps == null || project.comps.isEmpty())
        {
            this.info.label = IKey.constant("这个工程没有合成：先在合成器里新建一个合成");

            return;
        }

        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (CompDocument comp : project.comps)
        {
            if (comp == null)
            {
                continue;
            }

            labels.add(comp.name + "  ·  " + comp.id + "  ·  " + comp.width + "×" + comp.height
                + "  ·  " + String.format(java.util.Locale.ROOT, "%.1fs", comp.duration));
            ids.add(comp.id);
        }

        if (labels.isEmpty())
        {
            return;
        }

        UIListOverlayPanel panel = new UIListOverlayPanel(IKey.constant("选择合成"), (value) ->
        {
            int index = labels.indexOf(value);

            if (index < 0)
            {
                return;
            }

            this.clip.comp.set(ids.get(index));
            this.comp.setText(ids.get(index));
            this.refreshInfo();
        });

        panel.addValues(labels);
        UIOverlay.addOverlay(this.getContext(), panel, 0.45F, 0.7F);
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
            this.chooseComp, this.useCurrent, this.edit, this.info));
        this.panels.add(this.section(IKey.constant("时间"), this.offset, this.timeScale, this.opacity));
        this.panels.add(this.section(IKey.constant("适配"), this.fit, this.hold));
    }
}
