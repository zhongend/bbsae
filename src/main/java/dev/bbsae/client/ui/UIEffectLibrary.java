package dev.bbsae.client.ui;

import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The effect library, modelled on Premiere's Effects panel: a searchable, categorised list that
 * applies an effect to the <b>selected timeline track</b>. Double-click (BBS's native list open
 * gesture) applies; the footer button does the same for the current selection.
 *
 * <p>It intentionally does not own effect parameters. Once applied, an effect shows up in the
 * right-hand Effect Controls panel, exactly like Premiere's Effects / Effect Controls split.</p>
 */
public class UIEffectLibrary extends UIElement
{
    private static final int PAD = 5;

    private final UICompositorPanel panel;
    private final UILabel searchLabel;
    private final UITextbox search;
    private final UIEffectList list;
    private final UILabel status;
    private final UIButton apply;
    private final UIIcon clear;
    private final EffectPreview preview = new EffectPreview();

    private String filter = "";
    private final Set<String> expanded = new LinkedHashSet<>();

    public UIEffectLibrary(UICompositorPanel panel)
    {
        this.panel = panel;

        this.searchLabel = new UILabel(IKey.constant("搜索效果"));
        this.searchLabel.color(0xFF8A94A6, false);

        this.search = new UITextbox(64, (text) ->
        {
            this.filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            this.rebuild();
        });

        this.clear = new UIIcon(Icons.CLOSE, (icon) ->
        {
            this.search.setText("");
            this.filter = "";
            this.rebuild();
        });

        this.list = new UIEffectList((selection) -> this.refreshStatus());
        this.list.background();
        this.list.scroll.scrollItemSize = 14;

        this.status = new UILabel(IKey.constant("未选择轨道"));
        this.status.color(0xFF9AA6B8, false);

        UICompButton apply = new UICompButton(IKey.constant("应用效果"), (button) -> this.applySelected());

        apply.style(UICompButton.Style.PRIMARY);
        apply.hint("把选中的效果加到当前轨道末尾；也可以直接双击效果");
        this.apply = apply;

        this.searchLabel.relative(this).x(PAD).y(1).w(1F, -PAD * 2).h(16);
        this.search.relative(this).x(PAD).y(18).w(1F, -PAD * 2 - 21).h(18);
        this.clear.relative(this).x(1F, -PAD - 18).y(18).w(18).h(18);
        /* The list gives up 62 px so the selected effect can show what it does. Browsing a wall of
         * effect names without seeing their output is guessing; one visible preview is enough. */
        this.list.relative(this).x(PAD).y(42).w(1F, -PAD * 2).h(1F, -142);
        this.preview.relative(this).x(PAD).y(1F, -104).w(1F, -PAD * 2).h(56);
        this.status.relative(this).x(PAD).y(1F, -42).w(1F, -PAD * 2).h(14);
        this.apply.relative(this).x(PAD).y(1F, -22).w(1F, -PAD * 2).h(18);

        this.expanded.add("最近使用");

        if (!CATEGORIES.isEmpty())
        {
            this.expanded.add(CATEGORIES.keySet().iterator().next());
        }

        this.add(this.searchLabel, this.search, this.clear, this.list, this.preview, this.status, this.apply);

        this.refreshStatus();
        this.rebuild();
    }

    private static final Map<String, List<CompEffectRegistry.Definition>> CATEGORIES = new LinkedHashMap<>();

    static
    {
        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            CATEGORIES.computeIfAbsent(definition.category, (key) -> new ArrayList<>()).add(definition);
        }
    }

    /** The row the user last clicked, or null. */
    private CompEffectRegistry.Definition selectedDefinition()
    {
        EffectRow row = this.list.getCurrentFirst();

        return row == null ? null : row.definition;
    }

    /**
     * The effect instance to render: the one already on the selected layer (so the thumbnail shows
     * the user's own values), or a default instance of that type.
     */
    private CompEffect previewEffect(CompEffectRegistry.Definition definition)
    {
        CompLayer layer = this.panel.getSelectedLayer();

        if (layer != null)
        {
            for (CompEffect effect : layer.effects)
            {
                if (effect != null && definition.id.equals(effect.type))
                {
                    return effect;
                }
            }
        }

        return CompEffectRegistry.create(definition.id, 1);
    }

    /**
     * The thumbnail box. Reads the selection every frame — the list owns the state, this only
     * draws — and falls back to a hint when nothing is selected.
     */
    private final class EffectPreview extends UIElement
    {
        @Override
        public void render(UIContext context)
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF141920);
            context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF2A303A);

            CompEffectRegistry.Definition definition = UIEffectLibrary.this.selectedDefinition();

            if (definition == null)
            {
                context.batcher.textShadow("单击上面的效果名，这里显示它的实际画面",
                    this.area.x + 6, this.area.y + 8, 0xFF7C8798);

                return;
            }

            CompEffect effect = UIEffectLibrary.this.previewEffect(definition);
            dev.bbsae.client.render.GlFramebuffer frame = effect == null
                ? null
                : dev.bbsae.client.render.CompEffectPreview.frame(effect);

            int pad = 3;
            int boxW = this.area.w - pad * 2;
            int boxH = this.area.h - 16 - pad;

            if (frame != null)
            {
                float fit = Math.min(boxW / (float) frame.width, boxH / (float) frame.height);
                int w = Math.max(1, Math.round(frame.width * fit));
                int h = Math.max(1, Math.round(frame.height * fit));
                int x = this.area.x + (this.area.w - w) / 2;
                int y = this.area.y + pad + (boxH - h) / 2;

                /* The composition FBO is bottom-up, so v is flipped — same convention as the
                 * preview blit in CompRenderer. */
                context.batcher.texturedBox(frame.color.id, 0xFFFFFFFF, x, y, w, h,
                    0F, frame.height, frame.width, 0F, frame.width, frame.height);
            }

            String label = definition.name;

            if (label.length() > 18)
            {
                label = label.substring(0, 17) + "…";
            }

            context.batcher.textShadow(label, this.area.x + 5, this.area.ey() - 13, 0xFFB8C4D6);
        }
    }

    public void refreshStatus()
    {
        CompLayer layer = this.panel.getSelectedLayer();
        String target = layer == null ? "未选择轨道" : "目标：" + (layer.name == null || layer.name.isBlank() ? layer.id : layer.name);

        this.status.label = IKey.constant(target);
        this.apply.setEnabled(layer != null);
        this.status.color(layer == null ? 0xFFB06A6A : 0xFF9AA6B8, false);
    }

    public void focusSearch()
    {
        this.search.setVisible(true);
    }

    public void clearFilter()
    {
        this.search.setText("");
        this.filter = "";
        this.rebuild();
    }

    private void applySelected()
    {
        EffectRow row = this.list.getCurrentFirst();

        if (row == null || row.definition == null)
        {
            this.panel.setStatus("先在左侧列表里选择一个效果");

            return;
        }

        this.panel.applyEffect(row.definition.id);
        this.rebuild();
        this.refreshStatus();
    }

    private void rebuild()
    {
        List<EffectRow> rows = new ArrayList<>();
        boolean filtering = !this.filter.isEmpty();

        if (!filtering)
        {
            List<String> recent = this.panel.getRecentEffects();

            if (!recent.isEmpty())
            {
                rows.add(EffectRow.category("最近使用"));

                if (this.expanded.contains("最近使用"))
                {
                    rows.addAll(this.effectsFor(recent, Integer.MAX_VALUE));
                }
            }

            for (Map.Entry<String, List<CompEffectRegistry.Definition>> entry : CATEGORIES.entrySet())
            {
                rows.add(EffectRow.category(entry.getKey()));

                if (this.expanded.contains(entry.getKey()))
                {
                    for (CompEffectRegistry.Definition definition : entry.getValue())
                    {
                        rows.add(EffectRow.effect(definition));
                    }
                }
            }
        }
        else
        {
            for (Map.Entry<String, List<CompEffectRegistry.Definition>> entry : CATEGORIES.entrySet())
            {
                List<CompEffectRegistry.Definition> matches = new ArrayList<>();

                for (CompEffectRegistry.Definition definition : entry.getValue())
                {
                    if (this.matches(definition))
                    {
                        matches.add(definition);
                    }
                }

                if (!matches.isEmpty())
                {
                    rows.add(EffectRow.category(entry.getKey()));

                    for (CompEffectRegistry.Definition definition : matches)
                    {
                        rows.add(EffectRow.effect(definition));
                    }
                }
            }
        }

        EffectRow current = this.list.getCurrentFirst();

        this.list.setList(rows);

        if (current != null && current.definition != null)
        {
            for (EffectRow row : rows)
            {
                if (row.definition == current.definition)
                {
                    this.list.setCurrent(row);
                    break;
                }
            }
        }

        this.list.resize();
        this.refreshStatus();
    }

    private List<EffectRow> effectsFor(List<String> ids, int limit)
    {
        List<EffectRow> rows = new ArrayList<>();

        for (String id : ids)
        {
            if (rows.size() >= limit)
            {
                break;
            }

            CompEffectRegistry.Definition definition = CompEffectRegistry.get(id);

            if (definition != null)
            {
                rows.add(EffectRow.effect(definition));
            }
        }

        return rows;
    }

    private boolean matches(CompEffectRegistry.Definition definition)
    {
        return definition.name.toLowerCase(Locale.ROOT).contains(this.filter)
            || definition.id.toLowerCase(Locale.ROOT).contains(this.filter)
            || definition.category.toLowerCase(Locale.ROOT).contains(this.filter);
    }

    private static final class EffectRow
    {
        final String category;
        final CompEffectRegistry.Definition definition;

        private EffectRow(String category, CompEffectRegistry.Definition definition)
        {
            this.category = category;
            this.definition = definition;
        }

        static EffectRow category(String name)
        {
            return new EffectRow(name, null);
        }

        static EffectRow effect(CompEffectRegistry.Definition definition)
        {
            return new EffectRow(null, definition);
        }
    }

    private final class UIEffectList extends UIList<EffectRow>
    {
        private UIEffectList(java.util.function.Consumer<List<EffectRow>> callback)
        {
            super(callback);
        }

        @Override
        protected boolean sortElements()
        {
            return false;
        }

        @Override
        protected String elementToString(UIContext context, int i, EffectRow element)
        {
            if (element.category != null)
            {
                if ("最近使用".equals(element.category))
                {
                    return element.category;
                }

                return element.category + "  (" + CATEGORIES.getOrDefault(element.category, List.of()).size() + ")";
            }

            return element.definition.name;
        }

        @Override
        protected int indent(EffectRow element)
        {
            /* Room for the per-effect thumbnail on the left of every row (Alight Motion shows the
             * effect itself, not an icon). */
            return element.definition == null ? 0 : 26;
        }

        /**
         * Per-row effect thumbnails, like Alight Motion's effect browser.
         *
         * <p>Rows are drawn by BBS's list; the thumbnails are painted afterwards using the list's
         * own {@link #visible()} / {@link #areaOf(int, mchorse.bbs_mod.ui.utils.Area)} geometry, so
         * they follow filtering, folding and scrolling exactly (a hand-rolled row index would drift
         * the moment a category collapses).</p>
         *
         * <p>{@code CompEffectPreview.frame(type)} already caches one rendered frame per effect
         * type, which is why this is affordable at 20+ rows.</p>
         */
        @Override
        public void render(UIContext context)
        {
            super.render(context);

            java.util.List<EffectRow> rows = this.visible();
            mchorse.bbs_mod.ui.utils.Area cell = new mchorse.bbs_mod.ui.utils.Area();

            for (int i = 0; i < rows.size(); i++)
            {
                EffectRow row = rows.get(i);

                if (row == null || row.definition == null)
                {
                    continue;
                }

                this.areaOf(i, cell);

                /* areaOf() reports the row in *content* coordinates (x from 0, y from the top of
                 * the scrolled content), so the screen position is area + cell - scroll. Using the
                 * raw cell put every thumbnail outside the panel — the first build drew nothing. */
                int rowY = this.area.y + cell.y - (int) this.scroll.getScroll();
                int rowH = cell.h;

                if (rowY + rowH < this.area.y || rowY > this.area.ey())
                {
                    continue;
                }

                dev.bbsae.client.render.GlFramebuffer frame = dev.bbsae.client.render.CompEffectPreview.frame(row.definition.id);

                int size = Math.max(10, Math.min(16, rowH - 2));
                int x = this.area.x + 6;
                int y = rowY + (rowH - size) / 2;

                if (frame != null)
                {
                    context.batcher.texturedBox(frame.color.id, 0xFFFFFFFF, x, y, size, size,
                        0F, frame.height, frame.width, 0F, frame.width, frame.height);
                    context.batcher.outline(x - 1, y - 1, x + size + 1, y + size + 1, 0x552A303A);
                }
            }
        }

        @Override
        protected Boolean branch(EffectRow element)
        {
            if (element.category == null)
            {
                return null;
            }

            UIEffectLibrary library = UIEffectLibrary.this;

            return library.filter.isEmpty()
                ? library.expanded.contains(element.category)
                : null;
        }

        @Override
        protected void toggle(EffectRow element)
        {
            if (element.category == null || !UIEffectLibrary.this.filter.isEmpty())
            {
                return;
            }

            UIEffectLibrary library = UIEffectLibrary.this;

            if (!library.expanded.remove(element.category))
            {
                library.expanded.add(element.category);
            }

            library.rebuild();
        }

        @Override
        protected boolean onOpen(EffectRow item)
        {
            if (item.definition == null)
            {
                this.toggle(item);

                return true;
            }

            UIEffectLibrary.this.panel.applyEffect(item.definition.id);
            UIEffectLibrary.this.rebuild();

            return true;
        }


    }
}
