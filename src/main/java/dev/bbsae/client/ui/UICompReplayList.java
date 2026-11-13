package dev.bbsae.client.ui;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UI;

/**
 * bbsae's own replay list.
 *
 * <p>This is deliberately not BBS's film replay list. It lists only the composition's independent
 * replay layers — each one owns a BBS form and is keyed on the normal bbsae timeline. Selecting a
 * row selects the layer, so the existing timeline/quick-panel/keyframe editor edits that model. The
 * actor never exists in the real world.</p>
 */
public class UICompReplayList extends UIElement
{
    private final UICompositorPanel panel;
    private final UIReplayScrollView view;
    private final UiMotion motion = new UiMotion();
    private int motionFrames;

    public UICompReplayList(UICompositorPanel panel)
    {
        this.panel = panel;

        this.view = new UIReplayScrollView();
        this.view.relative(this).x(0).y(0).w(1F).h(1F);
        this.view.column(4).vertical().stretch().scroll().padding(4);
        this.add(this.view);
        this.refresh();
    }

    @Override
    public void render(UIContext context)
    {
        this.motion.tickFrame();

        if (++this.motionFrames >= UICompButton.PRUNE_EVERY)
        {
            this.motionFrames = 0;
            this.motion.prune(UICompButton.PRUNE_IDLE);
        }

        super.render(context);
    }

    public void refresh()
    {
        this.view.removeAll();
        this.motion.replay();

        this.view.add(UI.label(IKey.constant("bbsae 回放 · 仅虚拟相机"), 15, 0xFFE6EAF0));
        this.view.add(UI.label(IKey.constant("独立于 BBS 影片回放；真实世界不可见"), 13, 0xFF8A94A6));

        UIElement actions = UI.row(
            CompUi.flat("＋模型", "新建独立 bbsae 3D 回放模型", this.panel::addIndependentReplay).h(20),
            CompUi.flat("＋抠像", "新建真实影片回放抠像层（平面）", this.panel::addMatteReplay).h(20),
            CompUi.flat("模型库", "给当前选中的 3D 回放层换模型", this.panel::chooseModelForSelectedReplay).h(20),
            CompUi.flat("删除", "删除当前选中回放层", this.panel::deleteSelectedReplay).h(20)
        );
        this.view.add(actions);

        CompDocument doc = this.panel.getDocument();
        int independent = 0;
        int legacy = 0;
        boolean modelHeader = false;
        boolean matteHeader = false;

        if (doc != null)
        {
            for (CompLayer layer : doc.layers)
            {
                if (layer == null || !layer.isEntityLayer())
                {
                    continue;
                }

                if (layer.independent)
                {
                    if (!modelHeader)
                    {
                        modelHeader = true;
                        this.view.add(UI.label(IKey.constant("bbsae 3D 模型（虚拟相机）"), 13, 0xFFB8C4D6));
                    }

                    independent++;
                    this.view.add(this.row(layer));
                }
                else
                {
                    if (!matteHeader)
                    {
                        matteHeader = true;
                        this.view.add(UI.label(IKey.constant("回放抠像（真实影片平面）"), 13, 0xFFB8C4D6));
                    }

                    legacy++;
                    this.view.add(this.row(layer));
                }
            }
        }

        if (independent == 0)
        {
            this.view.add(UI.label(IKey.constant("还没有 bbsae 3D 回放模型。点「＋模型」导入一个 BBS 模型。"), 13, 0xFF8A94A6));
        }

        if (legacy == 0)
        {
            this.view.add(UI.label(IKey.constant("还没有回放抠像层。点「＋抠像」从真实影片抠出平面动画。"), 12, 0xFF667085));
        }
    }

    private UICompButton row(CompLayer layer)
    {
        String form = layer.independent
            ? (layer.hasModelForm() && layer.modelForm() != null ? layer.modelForm().name.get() : "未选择模型")
            : "真实影片抠像";
        String label = (layer.name == null || layer.name.isBlank() ? "回放模型" : layer.name)
            + "  ·  " + form;

        UICompButton button = new UICompButton(IKey.constant(label), (b) ->
        {
            this.panel.selectLayer(layer);
            this.panel.setStatus("已选择 bbsae 回放模型：" + label);
        });

        if (this.panel.getSelectedLayer() == layer)
        {
            button.style(UICompButton.Style.SELECTED);
        }

        return button;
    }

    private static final class UIReplayScrollView extends UIScrollView
    {
        UIReplayScrollView()
        {
            super(ScrollDirection.VERTICAL);
        }

        @Override
        public void render(UIContext context)
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF151A22);
            super.render(context);
        }
    }
}
