package dev.bbsae.client.ui;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompPreset;
import dev.bbsae.comp.CompPresetLibrary;
import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.UI;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The preset browser in the left column.
 *
 * <p>Presets are "a named bundle of property values", so the browser only needs three actions:
 * apply one, save the selected layer as one, and remove a user one. Built-ins are read-only and
 * always come from {@link CompPresetLibrary}; user presets live in the project file next to the
 * compositions, which is what makes them travel with the project instead of with the machine.</p>
 */
public class UICompPresetBrowser extends UIElement
{
    private final UICompositorPanel panel;
    private final UIScrollViewView view;
    private String search = "";

    private final UiMotion motion = new UiMotion();
    private int motionFrames;
    /** Index of the next card in the current rebuild; drives the entrance stagger. */
    private int cardIndex;

    public UICompPresetBrowser(UICompositorPanel panel)
    {
        this.panel = panel;

        this.view = new UIScrollViewView();
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
        this.cardIndex = 0;
        /* A rebuild is a new card list (search, apply, save): replay the staggered entrance. */
        this.motion.replay();

        UITextbox search = new UITextbox(64, (text) ->
        {
            this.search = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            this.refresh();
        });

        search.setText(this.search);
        this.view.add(search);

        CompLayer layer = this.panel.getSelectedLayer();

        this.view.add(UI.label(IKey.constant(layer == null ? "先选中一条轨道，再保存预设" : "保存所选轨道：「" + this.panel.layerLabel(layer) + "」"), 14, 0xFF8A94A6));

        UIElement saveRows = new UIElement();

        saveRows.w(1F).h(58);

        UIButton saveMotion = this.saveButton("动画", CompPreset.MOTION);
        UIButton saveEffect = this.saveButton("效果", CompPreset.EFFECT);
        UIButton saveStyle = this.saveButton("样式", CompPreset.STYLE);
        UIButton saveShape = this.saveButton("形状", CompPreset.SHAPE);
        UIButton saveLayer = this.saveButton("整套", CompPreset.LAYER);

        saveMotion.relative(saveRows).x(0).y(0).w(0.5F, -2).h(18);
        saveEffect.relative(saveRows).x(0.5F, 2).y(0).w(0.5F, -2).h(18);
        saveStyle.relative(saveRows).x(0).y(19).w(0.5F, -2).h(18);
        saveShape.relative(saveRows).x(0.5F, 2).y(19).w(0.5F, -2).h(18);
        saveLayer.relative(saveRows).x(0).y(38).w(1F).h(18);

        saveRows.add(saveMotion, saveEffect, saveStyle, saveShape, saveLayer);
        this.view.add(saveRows);

        List<CompPreset> builtIns = CompPresetLibrary.builtIns();
        List<CompPreset> mine = this.panel.getProject() == null
            ? new ArrayList<>()
            : this.panel.getProject().presets;

        this.addSection("内置预设", builtIns, true);
        this.addSection("工程预设", mine, false);
    }

    private UIButton saveButton(String label, String kind)
    {
        return CompUi.flat("存为" + label, "存为" + label, () -> this.savePreset(kind));
    }

    private void savePreset(String kind)
    {
        CompLayer layer = this.panel.getSelectedLayer();

        if (layer == null || this.panel.getProject() == null)
        {
            this.panel.setStatus("先选中一条轨道");
            return;
        }

        String base = kind + "_" + (layer.name == null || layer.name.isBlank() ? "layer" : layer.name);
        String id = CompStore.uniqueId(base, this.panel.getProject().presets, (preset) -> preset.id);
        CompPreset preset = CompPreset.capture(id, this.panel.layerLabel(layer) + " · " + kindLabel(kind), kind, layer, this.panel.getDocument() == null ? 24F : this.panel.getDocument().fps);

        if (preset == null)
        {
            this.panel.setStatus("这条轨道没有可保存的" + kindLabel(kind));
            return;
        }

        this.panel.getProject().presets.add(preset);
        this.panel.afterPresetChange();
        this.panel.setStatus("已保存预设：" + preset.name);
    }

    private static String kindLabel(String kind)
    {
        return switch (kind)
        {
            case CompPreset.EFFECT -> "效果";
            case CompPreset.STYLE -> "样式";
            case CompPreset.SHAPE -> "形状";
            case CompPreset.LAYER -> "整套";
            default -> "动画";
        };
    }

    private void addSection(String title, List<CompPreset> presets, boolean builtIn)
    {
        List<CompPreset> filtered = new ArrayList<>();

        for (CompPreset preset : presets)
        {
            if (this.search.isEmpty()
                || preset.name.toLowerCase(Locale.ROOT).contains(this.search)
                || preset.description.toLowerCase(Locale.ROOT).contains(this.search))
            {
                filtered.add(preset);
            }
        }

        if (filtered.isEmpty())
        {
            return;
        }

        this.view.add(UI.label(IKey.constant(title + "（" + filtered.size() + "）"), 15, 0xFFB8C4D6));

        for (CompPreset preset : filtered)
        {
            this.view.add(this.presetRow(preset));
        }
    }

    private UIElement presetRow(CompPreset preset)
    {
        PresetCard row = new PresetCard(this.cardIndex++);

        row.w(1F).h(36);

        UICompButton apply = CompUi.flat(preset.name, "应用预设「" + preset.name + "」\n" + preset.kindLabel() + " · " + preset.description, () -> this.apply(preset));
        UILabel info = UI.label(IKey.constant(preset.kindLabel() + " · " + preset.description), 12, 0xFF7C8798);
        int control = CompUi.control();

        apply.relative(row).x(0).y(0).w(1F, preset.builtin ? 0 : -control).h(control);
        info.relative(row).x(4).y(control + 1).w(1F, -8).h(13);
        row.add(apply, info);

        if (!preset.builtin)
        {
            UICompIconButton remove = CompUi.iconButton(Icons.CLOSE, "删除这个用户预设（内置预设不可删）", () ->
            {
                this.panel.getProject().presets.remove(preset);
                this.panel.afterPresetChange();
            });

            remove.relative(row).x(1F, -control).y(0).w(control).h(control);
            row.add(remove);
        }

        return row;
    }

    /**
     * One preset card.
     *
     * <p>The plate is drawn by the card itself so the hover wash and the staggered entrance can be
     * interpolated; BBS's scroll view clips the card, and only the painting moves — the button
     * children keep their original hit rectangles, so a fast click during the entrance still
     * lands on the right control.</p>
     */
    private final class PresetCard extends UIElement
    {
        private final int index;
        private final String hoverKey;

        PresetCard(int index)
        {
            this.index = index;
            /* Stable channel name, built once per card (cards are rebuilt, not drawn from a pool). */
            this.hoverKey = "preset:" + index + ":h";
        }

        @Override
        public void render(UIContext context)
        {
            UiMotion motion = UICompPresetBrowser.this.motion;
            float intro = UiStagger.progress(motion, this.index, 0.016F, 0.24F);
            float appear = UiStagger.alpha(intro);
            float hover = motion.anim(this.hoverKey, this.area.isInside(context) ? 1F : 0F, 24F);
            int shift = UiStagger.offsetPx(intro, Math.round(3F * motion.intensity()));

            if (hover > 0.01F || appear < 0.999F)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(),
                    UiGfx.mulA(0xFFFFFFFF, (0.02F + 0.05F * hover) * appear));
            }

            if (hover > 0.01F)
            {
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(),
                    UiGfx.mulA(0xFF4C86D8, 0.35F * hover));
                UiGfx.accentRail(context.batcher, this.area.x, this.area.y + 1,
                    this.area.ey() - this.area.y - 2, hover, 0xFF4C86D8);
            }

            MatrixStack stack = shift > 0 ? context.batcher.getContext().getMatrices() : null;

            if (stack != null)
            {
                stack.push();
                stack.translate(0F, shift, 0F);
            }

            try
            {
                super.render(context);
            }
            finally
            {
                if (stack != null)
                {
                    stack.pop();
                }
            }
        }
    }

    private void apply(CompPreset preset)
    {
        CompLayer layer = this.panel.getSelectedLayer();

        if (layer == null)
        {
            this.panel.setStatus("先选中要应用预设的轨道");
            return;
        }

        if (preset.applyTo(layer, this.panel.getDocument() == null ? 24F : this.panel.getDocument().fps))
        {
            this.panel.afterPresetChange();
            this.panel.setStatus("已应用预设：" + preset.name);
        }
    }

    /** Thin UIScrollView so the browser can be rebuilt without losing its scroll behaviour. */
    private static final class UIScrollViewView extends mchorse.bbs_mod.ui.framework.elements.UIScrollView
    {
        UIScrollViewView()
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
