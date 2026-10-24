package dev.bbsae.client.ui;

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
        this.list.relative(this).x(PAD).y(42).w(1F, -PAD * 2).h(1F, -80);
        this.status.relative(this).x(PAD).y(1F, -36).w(1F, -PAD * 2).h(14);
        this.apply.relative(this).x(PAD).y(1F, -22).w(1F, -PAD * 2).h(18);

        this.expanded.add("最近使用");

        if (!CATEGORIES.isEmpty())
        {
            this.expanded.add(CATEGORIES.keySet().iterator().next());
        }

        this.add(this.searchLabel, this.search, this.clear, this.list, this.status, this.apply);

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
            return element.definition == null ? 0 : 10;
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
