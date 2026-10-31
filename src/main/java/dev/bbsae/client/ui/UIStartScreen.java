package dev.bbsae.client.ui;

import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The compositor's landing screen.
 *
 * <p>Modelled on BBS's own landing screen: a centred card, a create/open menu on the left and the
 * recent projects on the right. The editor is not usable until a project is chosen — that rule is
 * enforced by this screen covering the entire panel and by {@code UICompositorPanel} refusing to
 * edit while it is visible.</p>
 *
 * <p>The shortcut scheme lives here too, because it is the first decision a Premiere user makes.
 * The choice is written into the project settings (so different projects can use different
 * schemes).</p>
 */
public class UIStartScreen extends UIElement
{
    private static final int CARD_W = 820;
    private static final int CARD_H = 460;

    private final Consumer<CompStore.ProjectInfo> onOpen;
    private final CreateHandler onCreate;
    private final IntConsumer onScheme;
    private final Runnable onBack;

    private final UIElement card;
    private final UILabel title;
    private final UILabel subtitle;
    private final UILabel listTitle;
    private final ProjectList list;
    private final UILabel summary;
    private final UILabel empty;
    private final UIButton enter;
    private final UIButton newProject;
    private final UIButton refresh;
    private final UIButton scheme;
    private final UIButton back;

    private final UIElement form;
    private final UITextbox name;
    private final UITrackpad width;
    private final UITrackpad height;
    private final UITrackpad fps;
    private final UITrackpad duration;
    private final UILabel formHint;

    private int schemeValue;
    private boolean creating;
    private String currentProject = "";

    public interface CreateHandler
    {
        void create(String name, int width, int height, float fps, float duration);
    }

    public UIStartScreen(Consumer<CompStore.ProjectInfo> onOpen, CreateHandler onCreate, IntConsumer onScheme, Runnable onBack)
    {
        this.onOpen = onOpen;
        this.onCreate = onCreate;
        this.onScheme = onScheme;
        this.onBack = onBack;

        this.card = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF151A21);
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF36414F);
                super.render(context);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }
        };
        this.card.relative(this).x(0.5F, -CARD_W / 2).y(0.5F, -CARD_H / 2).w(CARD_W).h(CARD_H);

        this.title = new UILabel(IKey.constant("BBS 合成器"));
        this.title.relative(this.card).x(24).y(18).w(300).h(24);

        this.subtitle = new UILabel(IKey.constant("选择或新建一个工程，然后进入合成器 · 作者 终end"));
        this.subtitle.color(0xFF8A94A6, false);
        this.subtitle.relative(this.card).x(24).y(44).w(1F, -48).h(16);

        UILabel menuTitle = new UILabel(IKey.constant("开始"));
        menuTitle.color(0xFF8A94A6, false);
        menuTitle.relative(this.card).x(24).y(78).w(200).h(14);

        this.newProject = this.menuButton("＋ 新建工程", 96, Icons.ADD, this::showForm);
        this.refresh = this.menuButton("刷新工程列表", 124, Icons.REFRESH, this::reload);
        this.scheme = this.menuButton("快捷键：Premiere", 166, Icons.GEAR, this::toggleScheme);
        this.back = this.menuButton("返回影片编辑器", 208, Icons.FILM, this.onBack);

        this.back.tooltip(IKey.constant("不进入合成器，回到 BBS 影片编辑器"));

        UILabel help = new UILabel(IKey.constant("Alt+滚轮缩放时间轴；空格播放；双击列表项直接进入。"));
        help.color(0xFF6F7A8A, false);
        help.relative(this.card).x(24).y(1F, -42).w(250).h(28);

        this.listTitle = new UILabel(IKey.constant("工程列表"));
        this.listTitle.color(0xFF8A94A6, false);
        this.listTitle.relative(this.card).x(306).y(78).w(1F, -330).h(14);

        this.list = new ProjectList((selection) -> this.refreshSelection());
        this.list.relative(this.card).x(306).y(96).w(1F, -330).h(1F, -178);
        this.list.background();
        this.list.scroll.scrollItemSize = 22;
        this.list.emptyState(IKey.constant("还没有工程，点左侧“新建工程”"));

        this.empty = new UILabel(IKey.constant(""));
        this.empty.color(0xFF6F7A8A, false);
        this.empty.relative(this.card).x(306).y(1F, -76).w(1F, -330).h(14);

        this.summary = new UILabel(IKey.constant(""));
        this.summary.color(0xFFB8C4D6, false);
        this.summary.relative(this.card).x(306).y(1F, -60).w(1F, -330).h(16);

        UICompButton enter = new UICompButton(IKey.constant("进入合成器"), (button) -> this.openSelected());

        enter.style(UICompButton.Style.PRIMARY);
        enter.hint("用所选工程打开合成器；也可以直接双击工程卡片");
        enter.relative(this.card).x(1F, -178).y(1F, -42).w(154).h(24);
        this.enter = enter;

        /* New-project form (same right-hand area, toggled). */
        this.form = new UIElement();
        this.form.relative(this.card).x(306).y(78).w(1F, -330).h(1F, -100);
        this.form.setVisible(false);

        UILabel formTitle = new UILabel(IKey.constant("新建工程"));
        formTitle.relative(this.form).x(0).y(2).w(1F).h(16);

        UILabel nameLabel = new UILabel(IKey.constant("名称"));
        nameLabel.relative(this.form).x(0).y(28).w(52).h(18);

        this.name = new UITextbox(96, (text) -> {});
        this.name.setText("我的合成");
        this.name.relative(this.form).x(58).y(26).w(1F, -58).h(20);

        UILabel canvasLabel = new UILabel(IKey.constant("画布"));
        canvasLabel.relative(this.form).x(0).y(58).w(52).h(18);

        UILabel sizeLabel = new UILabel(IKey.constant("宽 × 高"));
        sizeLabel.relative(this.form).x(0).y(112).w(52).h(18);

        this.width = new UITrackpad();
        this.width.limit(16, 8192).integer().setValue(1920);
        this.width.relative(this.form).x(58).y(110).w(84).h(20);

        this.height = new UITrackpad();
        this.height.limit(16, 8192).integer().setValue(1080);
        this.height.relative(this.form).x(150).y(110).w(84).h(20);

        String[] presets = {"1920×1080", "1080×1920", "1080×1080", "3840×2160"};
        int[][] sizes = {{1920, 1080}, {1080, 1920}, {1080, 1080}, {3840, 2160}};

        for (int i = 0; i < presets.length; i++)
        {
            final int w = sizes[i][0];
            final int h = sizes[i][1];
            UIButton button = new UICompButton(IKey.constant(presets[i]), (b) ->
            {
                this.width.setValue(w);
                this.height.setValue(h);
            });

            button.relative(this.form).x(58 + (i % 2) * 116).y(56 + (i / 2) * 24).w(108).h(20);
            this.form.add(button);
        }

        UILabel fpsLabel = new UILabel(IKey.constant("帧率"));
        fpsLabel.relative(this.form).x(0).y(142).w(52).h(18);

        this.fps = new UITrackpad();
        this.fps.limit(1, 240).setValue(60);
        this.fps.relative(this.form).x(58).y(140).w(84).h(20);

        UILabel durationLabel = new UILabel(IKey.constant("时长（秒）"));
        durationLabel.relative(this.form).x(0).y(172).w(84).h(18);

        this.duration = new UITrackpad();
        this.duration.limit(0.1, 3600).setValue(12);
        this.duration.relative(this.form).x(88).y(170).w(84).h(20);

        this.formHint = new UILabel(IKey.constant("工程会保存到 config/bbsae/projects"));
        this.formHint.color(0xFF6F7A8A, false);
        this.formHint.relative(this.form).x(0).y(202).w(1F).h(16);

        UICompButton create = new UICompButton(IKey.constant("创建并进入"), (button) -> this.create());
        create.style(UICompButton.Style.PRIMARY);
        create.hint("按上面的名称、画布尺寸、帧率和时长新建工程，并立即进入合成器");
        create.relative(this.form).x(0).y(232).w(120).h(22);
        create.tooltip(IKey.constant("创建 .bbscomp 工程并立即打开"));

        UIButton cancel = new UICompButton(IKey.constant("取消"), (button) -> this.hideForm());
        cancel.relative(this.form).x(128).y(232).w(76).h(22);

        this.form.add(formTitle, nameLabel, this.name, canvasLabel, sizeLabel, this.width, this.height,
            fpsLabel, this.fps, durationLabel, this.duration, this.formHint, create, cancel);

        this.card.add(this.title, this.subtitle, menuTitle, this.newProject, this.refresh, this.scheme, this.back,
            help, this.listTitle, this.list, this.empty, this.summary, this.enter, this.form);

        this.add(this.card);

        /* Listing projects touches the disk; it is deferred to the first time the screen is
         * shown so dashboard construction stays cheap. */
    }

    private UIButton menuButton(String label, int y, mchorse.bbs_mod.ui.utils.icons.Icon icon, Runnable action)
    {
        UIButton button = new UICompButton(IKey.constant(label), (b) -> action.run());

        button.relative(this.card).x(24).y(y).w(256).h(22);

        return button;
    }

    public void setScheme(int scheme)
    {
        this.schemeValue = scheme == 1 ? 1 : 0;
        this.scheme.label = IKey.constant(this.schemeValue == 1 ? "快捷键：BBS 原版" : "快捷键：Premiere");
    }

    public void setCurrentProject(String projectId)
    {
        this.currentProject = projectId == null ? "" : projectId;
        this.reload();

        if (this.list != null && !this.currentProject.isEmpty())
        {
            for (CompStore.ProjectInfo info : this.list.getList())
            {
                if (info.id.equals(this.currentProject))
                {
                    this.list.setCurrent(info);
                    this.list.setCurrentScroll(info);
                    break;
                }
            }
        }

        this.refreshSelection();
    }

    public void reload()
    {
        List<CompStore.ProjectInfo> projects = CompStore.listProjects();

        this.list.setList(projects);
        this.list.resize();

        this.empty.label = IKey.constant(projects.isEmpty() ? "还没有工程。点左侧“新建工程”创建一个。" : "");
        this.refreshSelection();
    }

    private void toggleScheme()
    {
        this.schemeValue = this.schemeValue == 1 ? 0 : 1;
        this.setScheme(this.schemeValue);

        if (this.onScheme != null)
        {
            this.onScheme.accept(this.schemeValue);
        }
    }

    private void openSelected()
    {
        CompStore.ProjectInfo info = this.list.getCurrentFirst();

        if (info != null)
        {
            this.onOpen.accept(info);
        }
    }

    private void refreshSelection()
    {
        CompStore.ProjectInfo info = this.list.getCurrentFirst();

        if (info == null)
        {
            this.summary.label = IKey.constant("");
            this.enter.setEnabled(false);

            return;
        }

        String current = info.id.equals(this.currentProject) ? "  ·  当前影片工程" : "";

        this.summary.label = IKey.constant(info.name + current + "    " + info.summary());
        this.enter.setEnabled(true);
    }

    private void showForm()
    {
        this.creating = true;
        this.form.setVisible(true);
        this.list.setVisible(false);
        this.listTitle.label = IKey.constant("新建工程");
        this.summary.setVisible(false);
        this.empty.setVisible(false);
        this.enter.setVisible(false);
    }

    private void hideForm()
    {
        this.creating = false;
        this.form.setVisible(false);
        this.list.setVisible(true);
        this.listTitle.label = IKey.constant("工程列表");
        this.summary.setVisible(true);
        this.empty.setVisible(true);
        this.enter.setVisible(true);
    }

    private void create()
    {
        String projectName = this.name.getText() == null ? "" : this.name.getText().trim();

        if (projectName.isEmpty())
        {
            this.formHint.label = IKey.constant("请先输入工程名称");
            this.formHint.color(0xFFB06A6A, false);

            return;
        }

        int w = (int) Math.max(16, this.width.getValue());
        int h = (int) Math.max(16, this.height.getValue());
        float fps = (float) Math.max(1D, this.fps.getValue());
        float duration = (float) Math.max(0.1D, this.duration.getValue());

        this.onCreate.create(projectName, w, h, fps, duration);
    }

    @Override
    public void render(UIContext context)
    {
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xD80B0E13);
        super.render(context);
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        /* Swallow clicks: the editor under the landing screen must not react while no project
         * has been chosen yet. */
        return this.area.isInside(context) || super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        return this.area.isInside(context) || super.subMouseScrolled(context);
    }

    /**
     * Keep the card inside the panel. It used to be a fixed 820x460, which ran off-screen at
     * large GUI scales or on small windows and made "进入合成器" unreachable.
     */
    @Override
    public void resize()
    {
        super.resize();

        int w = Math.max(340, Math.min(CARD_W, this.area.w - 16));
        int h = Math.max(300, Math.min(CARD_H, this.area.h - 16));

        this.card.relative(this).x(0.5F, -w / 2).y(0.5F, -h / 2).w(w).h(h);
        this.card.resize();
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (!this.creating && context.getKeyCode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)
        {
            this.openSelected();

            return true;
        }

        return super.subKeyPressed(context);
    }

    private final class ProjectList extends UIList<CompStore.ProjectInfo>
    {
        ProjectList(Consumer<List<CompStore.ProjectInfo>> callback)
        {
            super(callback);
        }

        @Override
        protected String elementToString(UIContext context, int i, CompStore.ProjectInfo element)
        {
            /* Names are not unique (the demo project is copied by every test run), so the id is
             * shown as well. Without it the list looks like ten identical rows. */
            return element.name + "  [" + element.id + "]   " + element.width + "×" + element.height
                + "  " + String.format(Locale.ROOT, "%.0f fps", element.fps)
                + (element.id.equals(UIStartScreen.this.currentProject) ? "   ● 当前" : "");
        }

        @Override
        protected boolean onOpen(CompStore.ProjectInfo item)
        {
            UIStartScreen.this.onOpen.accept(item);

            return true;
        }
    }
}
