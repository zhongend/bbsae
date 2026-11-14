package dev.bbsae.client.ui;

import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icon;
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
    /** Last text read from the box; the authoritative query, not a callback side effect. */
    private String lastQuery = "";
    private final Set<String> expanded = new LinkedHashSet<>();
    /** One clock for the whole panel: the list only reads channels, it never ticks its own. */
    private final UiMotion motion = new UiMotion();
    private int motionFrames;

    @Override
    public void render(UIContext context)
    {
        /* Derive the filter from the text box itself. The callback below is the normal path, but
         * a swallowed/never-fired text callback used to leave the visible query and the list out
         * of sync — the field looked editable while nothing was filtered. Reading the authoritative
         * text once per frame makes search work even if the callback is missed. */
        String query = this.search.getText();

        if (query == null)
        {
            query = "";
        }

        if (!query.equals(this.lastQuery))
        {
            this.lastQuery = query;
            this.filter = query.trim().toLowerCase(Locale.ROOT);
            this.rebuild();
        }

        if (this.area.w > 0 && this.search.area.w <= 0)
        {
            /* A host that laid the library out before its own width was known can leave the child
             * at a zero-width area; a zero-area UITextbox can never receive a click, so search
             * looked visually present but dead. Re-resolve the child once a real width exists. */
            this.search.relative(this).x(PAD).y(18).w(1F, -PAD * 2 - 21).h(18);
            this.search.resize();
        }

        this.tickMotion();
        super.render(context);
    }

    /**
     * Focus fallback for the search box.
     *
     * <p>The child {@link UITextbox} normally handles its own press, but a layout pass that ran
     * before the library had a real width can leave its hit area stale; a click inside the visible
     * box still means "start searching". Keeping this fallback makes the field usable even when the
     * child's cached rectangle is wrong.</p>
     */
    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (this.search.area.isInside(context))
        {
            context.focus(this.search);

            return true;
        }

        return super.subMouseClicked(context);
    }

    private void tickMotion()
    {
        this.motion.tickFrame();

        if (++this.motionFrames >= UICompButton.PRUNE_EVERY)
        {
            this.motionFrames = 0;
            this.motion.prune(UICompButton.PRUNE_IDLE);
        }
    }

    public UIEffectLibrary(UICompositorPanel panel)
    {
        this.panel = panel;

        this.searchLabel = new UILabel(IKey.constant("搜索效果"));
        this.searchLabel.color(0xFF8A94A6, false);

        this.search = new UITextbox(64, (text) ->
        {
            String next = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);

            this.lastQuery = text == null ? "" : text;
            this.filter = next;
            this.rebuild();
        });

        this.clear = new UIIcon(Icons.CLOSE, (icon) ->
        {
            this.search.setText("");
            this.lastQuery = "";
            this.filter = "";
            this.rebuild();
        });

        /* Verification hook: the screenshot harness needs every category open to show the row
         * icons; it is a single property read and never set in normal play. */
        if (Boolean.getBoolean("bbsae.debugExpandEffects"))
        {
            this.expanded.addAll(CATEGORIES.keySet());
        }

        this.list = new UIEffectList((selection) -> this.refreshStatus());
        this.list.background();
        /* A little more air per row. The old 14 px rows plus a 16 px thumbnail read as a dense
         * grid; effect names are a reading task, not a texture browser. */
        this.list.scroll.scrollItemSize = 17;

        this.status = new UILabel(IKey.constant("未选择轨道"));
        this.status.color(0xFF9AA6B8, false);

        UICompButton apply = new UICompButton(IKey.constant("应用效果"), (button) -> this.applySelected());

        apply.style(UICompButton.Style.PRIMARY);
        apply.hint("把选中的效果加到当前轨道末尾；也可以直接双击效果");
        this.apply = apply;

        this.searchLabel.relative(this).x(PAD).y(1).w(1F, -PAD * 2).h(16);
        this.search.relative(this).x(PAD).y(18).w(1F, -PAD * 2 - 21).h(18);
        this.clear.relative(this).x(1F, -PAD - 18).y(18).w(18).h(18);
        /* No thumbnail preview: a 16 px effect card is not readable, and it stole 56 px plus a
         * 26 px indent from every row. The list now owns the space; double-click applies. */
        this.list.relative(this).x(PAD).y(42).w(1F, -PAD * 2).h(1F, -88);
        this.status.relative(this).x(PAD).y(1F, -42).w(1F, -PAD * 2).h(14);
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
            if (CompEffectRegistry.isLibraryHidden(definition.id))
            {
                continue;
            }

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
        /* Search results / collapsed categories are a new list: replay the staggered entrance so
         * the content reads as being laid out rather than pasted in. */
        this.motion.replay();

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

            if (definition != null && !CompEffectRegistry.isLibraryHidden(id))
            {
                rows.add(EffectRow.effect(definition));
            }
        }

        return rows;
    }

    private boolean matches(CompEffectRegistry.Definition definition)
    {
        return matchesFilter(definition, this.filter);
    }

    /** Pure search predicate: effect name, id and category are all searchable. */
    static boolean matchesFilter(CompEffectRegistry.Definition definition, String filter)
    {
        if (definition == null)
        {
            return false;
        }

        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        if (needle.isEmpty())
        {
            return true;
        }

        String name = definition.name == null ? "" : definition.name.toLowerCase(Locale.ROOT);
        String id = definition.id == null ? "" : definition.id.toLowerCase(Locale.ROOT);
        String category = definition.category == null ? "" : definition.category.toLowerCase(Locale.ROOT);

        return name.contains(needle) || id.contains(needle) || category.contains(needle);
    }

    private static final class EffectRow
    {
        final String category;
        final CompEffectRegistry.Definition definition;
        /** Motion channels are keyed by the row's stable id, built once with the row. */
        final String hoverKey;
        final String selectKey;

        private EffectRow(String category, CompEffectRegistry.Definition definition)
        {
            this.category = category;
            this.definition = definition;

            String base = definition != null ? "fx:" + definition.id : "cat:" + category;

            this.hoverKey = base + ":h";
            this.selectKey = base + ":s";
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
        protected int rowContentX(EffectRow element)
        {
            /* Reserve a 16 px gutter for the category icon; only real effects get one, category
             * headers keep their tree arrow and full-width label. */
            return super.rowContentX(element) + (element.definition == null ? 0 : 18);
        }

        /**
         * Draws the row itself instead of BBS's boolean {@code RowStyle} so hover, pick and the
         * staggered entrance can all be interpolated. The wash is the same vocabulary RowStyle
         * uses (accent gradient from the left, 2 px bar for the pick), only approached over time.
         */
        @Override
        public void renderListElement(UIContext context, EffectRow element, int index, int x, int y,
                                      boolean hover, boolean selected)
        {
            UIEffectLibrary library = UIEffectLibrary.this;
            UiMotion motion = library.motion;
            int height = this.rowHeight();
            float intro = UiStagger.progress(motion, index, 0.012F, 0.2F);
            float appear = UiStagger.alpha(intro);
            float hov = motion.anim(element.hoverKey, hover ? 1F : 0F, 26F);
            float sel = motion.anim(element.selectKey, selected ? 1F : 0F, 22F);
            int drawY = y + UiStagger.offsetPx(intro, Math.round(2F * motion.intensity()));
            float lit = Math.max(hov, sel);
            int accent = BBSSettings.primaryColor() & 0xFFFFFF;

            if (lit > 0.004F)
            {
                float near = Math.max(hov * 0.22F, sel * 0.50F) * appear;
                float far = Math.max(hov * 0.03F, sel * 0.08F) * appear;

                if (near > 0.001F || far > 0.001F)
                {
                    context.batcher.gradientHBox(x, drawY, x + this.area.w, drawY + height,
                        UiMotion.alpha(accent, near), UiMotion.alpha(accent, far));
                }
            }

            if (sel > 0.01F)
            {
                context.batcher.box(x, drawY, x + 2, drawY + height,
                    UiMotion.alpha(accent, sel * appear));
            }

            this.renderRowContent(context, element, index, x, drawY, lit, appear, height);
        }

        private void renderRowContent(UIContext context, EffectRow element, int index, int x, int y,
                                      float lit, float appear, int height)
        {
            FontRenderer font = context.batcher.getFont();
            int textX = x + this.rowContentX(element) + (this.branch(element) != null ? ARROW_SLOT : 0);

            this.renderArrow(context, element, x, y, lit > 0.5F);

            if (element.definition != null)
            {
                Icon icon = EffectIcons.forDefinition(element.definition);

                if (icon != null && icon != Icons.NONE)
                {
                    int top = y + Math.max(0, (height - 16) / 2);

                    context.batcher.icon(icon, UiMotion.alpha(0xFFFFFFFF, appear), x + 3, top);
                }
            }

            /* RowStyle's rest text is 85% white; hovering/selecting lifts it to white. */
            int color = UiMotion.lerpColor(UiMotion.alpha(0xFFFFFFFF, 0.85F), 0xFFFFFFFF, lit);

            color = UiMotion.alpha(color, appear);

            context.batcher.textShadow(this.elementToString(context, index, element), textX,
                y + (height - font.getHeight()) / 2, color);
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
