package dev.bbsae.client.ui;

import dev.bbsae.comp.LayerCatalog;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The layer library: a browsable grid of layer types with a search box and categories.
 *
 * <h2>Why a library and not a context menu</h2>
 * <p>The old chooser was a fixed 440×300 modal with three columns of identical grey buttons. With
 * twenty types it needed scrolling it did not have, the last row collided with the Cancel button,
 * and every entry looked the same — there was nothing to choose <em>by</em> except the label.</p>
 *
 * <p>A library answers the question the user actually has ("what can I add, and what does it do?"):
 * each card carries an icon, a name and one sentence, the categories are on the left where a file
 * manager would put them, and typing filters everything at once. Selection is explicit, so a
 * double click and the Add button do the same thing.</p>
 */
public class UILayerLibrary extends UIElement
{
    private static final int PAD = 16;
    private static final int CARD_W = 178;
    private static final int CARD_H = 62;
    private static final int GAP = 10;
    private static final int SIDEBAR = 132;
    private static final int HEADER = 104;
    private static final int FOOTER = 44;

    private final Consumer<String> onPick;
    private final Runnable onClose;

    private final UIElement box;
    private final UILabel title;
    private final UILabel hint;
    private final UITextbox search;
    private final UIElement categories;
    private final List<UIButton> categoryButtons = new ArrayList<>();
    private final Grid grid;
    private final UICompButton addButton;
    private final UICompButton cancelButton;

    private String category = LayerCatalog.COMMON;
    private boolean searchMode;

    private int laidOutWidth = -1;
    private int laidOutHeight = -1;

    public UILayerLibrary(Consumer<String> onPick, Runnable onClose)
    {
        this.onPick = onPick;
        this.onClose = onClose;

        this.box = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF141A22);
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF3A4656);
                super.render(context);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }
        };
        this.box.relative(this).x(0).y(0).w(1F).h(1F);

        this.title = new UILabel(IKey.constant("图层库"));
        this.title.relative(this.box).x(PAD).y(12).w(300).h(18);

        this.hint = new UILabel(IKey.constant("选一个类型，双击或按“添加”即可创建轨道"));
        this.hint.color(0xFF8A94A6, false);
        this.hint.relative(this.box).x(PAD).y(32).w(1F, -PAD * 2).h(14);

        this.search = new UITextbox(64, (text) -> this.refresh());
        this.search.relative(this.box).x(PAD).y(54).w(260).h(20);
        this.search.setText("");

        this.categories = new UIElement();
        this.categories.relative(this.box).x(PAD).y(HEADER - 12).w(SIDEBAR).h(1F, -HEADER - FOOTER + 12);

        this.grid = new Grid();
        this.grid.relative(this.box).x(PAD + SIDEBAR + 12).y(HEADER - 12).w(1F, -(PAD * 2 + SIDEBAR + 12)).h(1F, -HEADER - FOOTER + 12);

        this.addButton = new UICompButton(IKey.constant("添加图层"), (button) -> this.confirm());
        this.addButton.style(UICompButton.Style.PRIMARY);
        this.addButton.relative(this.box).x(1F, -(PAD + 110)).y(1F, -(PAD + 26)).w(110).h(24);

        this.cancelButton = new UICompButton(IKey.constant("取消"), (button) -> this.onClose.run());
        this.cancelButton.relative(this.box).x(1F, -(PAD + 110 + 8 + 72)).y(1F, -(PAD + 26)).w(72).h(24);

        this.box.add(this.title, this.hint, this.search, this.categories, this.grid, this.addButton, this.cancelButton);
        this.add(this.box);

        this.buildCategories();
        this.refresh();
    }

    /* ------------------------------------------------------------------ categories */

    private void buildCategories()
    {
        this.categories.removeAll();
        this.categoryButtons.clear();

        int y = 0;

        for (String name : LayerCatalog.CATEGORIES)
        {
            UIButton button = new UICompButton(IKey.constant(name), (b) -> this.selectCategory(name));

            button.relative(this.categories).x(0).y(y).w(1F).h(22);
            this.categories.add(button);
            this.categoryButtons.add(button);
            y += 26;
        }
    }

    private void selectCategory(String name)
    {
        this.category = name;
        this.searchMode = false;
        this.search.setText("");
        this.refresh();
    }

    /* ------------------------------------------------------------------ content */

    private void refresh()
    {
        String query = this.search.getText();

        this.searchMode = query != null && !query.isBlank();

        List<LayerCatalog.Entry> entries = this.searchMode
            ? LayerCatalog.search(query)
            : LayerCatalog.byCategory(this.category);

        this.grid.setEntries(entries);
        this.updateCategoryLabels();

        this.hint.label = IKey.constant(this.searchMode
            ? "搜索：" + (query == null ? "" : query.trim()) + " · " + entries.size() + " 个结果"
            : this.category + " · " + entries.size() + " 个类型");
    }

    private void updateCategoryLabels()
    {
        for (int i = 0; i < this.categoryButtons.size(); i++)
        {
            UIButton button = this.categoryButtons.get(i);
            boolean active = !this.searchMode && LayerCatalog.CATEGORIES[i].equals(this.category);

            button.label = IKey.constant((active ? "▸ " : "  ") + LayerCatalog.CATEGORIES[i]);
        }
    }

    private void confirm()
    {
        LayerCatalog.Entry entry = this.grid.selected();

        if (entry == null)
        {
            this.hint.label = IKey.constant("先在右侧选一个类型");

            return;
        }

        this.onPick.accept(entry.id);
    }

    /* ------------------------------------------------------------------ layout */

    @Override
    public void render(UIContext context)
    {
        this.layout();

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0x88000000);
        super.render(context);
    }

    private void layout()
    {
        int width = this.area.w;
        int height = this.area.h;

        if (width == this.laidOutWidth && height == this.laidOutHeight)
        {
            return;
        }

        this.laidOutWidth = width;
        this.laidOutHeight = height;

        int boxW = Math.min(Math.max(560, width - 80), 780);
        int boxH = Math.min(Math.max(360, height - 80), 520);
        int x = (width - boxW) / 2;
        int y = (height - boxH) / 2;

        this.box.relative(this).x(x).y(y).w(boxW).h(boxH);
        this.box.resize();

        int inner = this.box.area.w;

        this.search.relative(this.box).x(PAD).y(54).w(Math.min(300, inner - PAD * 2)).h(20);
        this.categories.relative(this.box).x(PAD).y(HEADER).w(SIDEBAR).h(Math.max(60, this.box.area.h - HEADER - FOOTER));
        this.grid.relative(this.box).x(PAD + SIDEBAR + 12).y(HEADER)
            .w(Math.max(160, inner - (PAD * 2 + SIDEBAR + 12)))
            .h(Math.max(60, this.box.area.h - HEADER - FOOTER));

        this.categories.resize();
        this.grid.resize();

        int footerY = this.box.area.h - PAD - 24;

        this.cancelButton.relative(this.box).x(inner - PAD - 72).y(footerY).w(72).h(24);
        this.addButton.relative(this.box).x(inner - PAD - 72 - 8 - 110).y(footerY).w(110).h(24);

        this.box.resize();
        this.grid.clamp();
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (context.isPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE))
        {
            this.onClose.run();

            return true;
        }

        if (context.isPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)
            || context.isPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER))
        {
            this.confirm();

            return true;
        }

        return super.subKeyPressed(context);
    }

    /* ------------------------------------------------------------------ grid */

    private final class Grid extends UIElement
    {
        private final List<LayerCatalog.Entry> entries = new ArrayList<>();
        private int scrollY;
        private float smoothScroll;
        private boolean smoothInit;
        private final UiMotion motion = new UiMotion();
        private int hot = -1;
        private int selected = -1;
        private int pressY = Integer.MIN_VALUE;
        private int pressScroll;
        private boolean dragged;
        private long lastClick;
        private int lastIndex = -1;

        void setEntries(List<LayerCatalog.Entry> list)
        {
            this.entries.clear();
            this.entries.addAll(list);
            this.selected = list.isEmpty() ? -1 : 0;
            this.scrollY = 0;
        }

        LayerCatalog.Entry selected()
        {
            return this.selected >= 0 && this.selected < this.entries.size() ? this.entries.get(this.selected) : null;
        }

        private int columns()
        {
            return Math.max(1, (this.area.w + GAP) / (CARD_W + GAP));
        }

        private int contentHeight()
        {
            int rows = (this.entries.size() + this.columns() - 1) / this.columns();

            return Math.max(0, rows * (CARD_H + GAP) - GAP);
        }

        void clamp()
        {
            int max = Math.max(0, this.contentHeight() - this.area.h);

            this.scrollY = Math.max(0, Math.min(max, this.scrollY));
        }

        @Override
        public void render(UIContext context)
        {
            this.motion.tickFrame();

            if (!this.smoothInit)
            {
                this.smoothScroll = this.scrollY;
                this.smoothInit = true;
            }

            this.smoothScroll = CompPrefs.animating()
                ? UiMotion.approach(this.smoothScroll, this.scrollY, 20F * CompPrefs.speed(), this.motion.dt)
                : this.scrollY;

            if (this.pressY != Integer.MIN_VALUE)
            {
                int delta = this.pressY - context.mouseY;

                if (Math.abs(delta) > 4)
                {
                    this.dragged = true;
                    this.scrollY = this.pressScroll + delta;
                    this.clamp();
                }
            }

            this.hot = this.indexAt(context.mouseX, context.mouseY);
            context.batcher.clip(this.area, context);

            try
            {
                int columns = this.columns();
                int scroll = Math.round(this.smoothScroll);

                for (int i = 0; i < this.entries.size(); i++)
                {
                    int column = i % columns;
                    int row = i / columns;
                    int x = this.area.x + column * (CARD_W + GAP);
                    int y = this.area.y + row * (CARD_H + GAP) - scroll;

                    if (y + CARD_H < this.area.y || y > this.area.ey())
                    {
                        continue;
                    }

                    this.renderCard(context, this.entries.get(i), i, x, y);
                }

                if (this.entries.isEmpty())
                {
                    context.batcher.text("没有匹配的图层类型", this.area.x + 4, this.area.y + 4, 0xFF7C8798);
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }

            this.renderScrollbar(context);
            super.render(context);
        }

        private int indexAt(int mouseX, int mouseY)
        {
            if (!this.area.isInside(mouseX, mouseY))
            {
                return -1;
            }

            int columns = this.columns();
            int row = (mouseY - this.area.y + Math.round(this.smoothScroll)) / (CARD_H + GAP);
            int column = (mouseX - this.area.x) / (CARD_W + GAP);

            if (column < 0 || column >= columns || row < 0)
            {
                return -1;
            }

            int index = row * columns + column;

            return index >= 0 && index < this.entries.size() ? index : -1;
        }

        private void renderCard(UIContext context, LayerCatalog.Entry entry, int index, int x, int y)
        {
            boolean active = index == this.selected;
            float hover = this.motion.hover("layer:" + index, this.hot == index ? "layer:" + index : null);
            float sel = this.motion.select("layer:" + index, active);
            float intro = this.motion.intro(Math.min(0.32F, index * 0.008F));
            int drawY = y + Math.round((1F - intro) * 5F * this.motion.intensity() * 2F);
            int fill = UiMotion.lerpColor(0xFF1E2530, 0xFF26313F, hover);

            fill = UiMotion.lerpColor(fill, 0xFF2F5C9E, sel);

            if (hover > 0.01F && CompPrefs.get().shadows)
            {
                UiGfx.shadow(context.batcher, x, drawY, CARD_W, CARD_H, 3F,
                    UiGfx.alpha(0xFF000000, 0.14F * hover));
            }

            context.batcher.box(x, drawY, x + CARD_W, drawY + CARD_H, UiGfx.mulA(fill, intro));
            context.batcher.outline(x, drawY, x + CARD_W, drawY + CARD_H,
                UiMotion.lerpColor(0xFF313B49, 0xFF7FB4FF, sel));

            int iconColor = UiMotion.lerpColor(0xFF9FB2C8, 0xFFFFFFFF, sel);

            context.batcher.icon(UILayerLibrary.icon(entry.icon), UiGfx.mulA(iconColor, intro), x + 10, drawY + 10);
            context.batcher.text(entry.name, x + 32, drawY + 8,
                UiGfx.mulA(UiMotion.lerpColor(0xFFD6DEEB, 0xFFFFFFFF, hover), intro));
            context.batcher.text(entry.category, x + CARD_W - 8 - context.batcher.getFont().getWidth(entry.category), drawY + 9,
                UiGfx.mulA(0xFF6F7A8A, intro));
            context.batcher.text(this.wrap(entry.description, CARD_W - 40), x + 32, drawY + 26,
                UiGfx.mulA(0xFF8A94A6, intro));
        }

        /** Two-line clamp: the card has room for two rows at 13 px. */
        private String wrap(String text, int width)
        {
            if (text == null)
            {
                return "";
            }

            int perLine = Math.max(8, width / 6);
            String first = text.length() <= perLine ? text : text.substring(0, perLine);

            if (text.length() <= perLine)
            {
                return first;
            }

            String rest = text.substring(perLine);

            return first + "\n" + (rest.length() <= perLine ? rest : rest.substring(0, Math.max(0, perLine - 1)) + "…");
        }

        private void renderScrollbar(UIContext context)
        {
            int content = this.contentHeight();

            if (content <= this.area.h || this.area.h <= 0)
            {
                return;
            }

            int barH = Math.max(18, Math.round(this.area.h * (this.area.h / (float) content)));
            int barY = this.area.y + Math.round((this.area.h - barH) * (Math.max(0F, this.smoothScroll) / (float) Math.max(1, content - this.area.h)));

            context.batcher.box(this.area.ex() - 4, this.area.y, this.area.ex() - 1, this.area.ey(), 0x22FFFFFF);
            context.batcher.box(this.area.ex() - 4, barY, this.area.ex() - 1, barY + barH, 0x99FFFFFF);
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton != 0 || !this.area.isInside(context))
            {
                return super.subMouseClicked(context);
            }

            this.pressY = context.mouseY;
            this.pressScroll = this.scrollY;
            this.dragged = false;

            int columns = this.columns();
            int localX = context.mouseX - this.area.x;
            int localY = context.mouseY - this.area.y + Math.round(this.smoothScroll);
            int column = localX / (CARD_W + GAP);
            int row = localY / (CARD_H + GAP);

            if (column >= columns || localX - column * (CARD_W + GAP) > CARD_W || localY - row * (CARD_H + GAP) > CARD_H)
            {
                return true;
            }

            int index = row * columns + column;

            if (index < 0 || index >= this.entries.size())
            {
                return true;
            }

            long now = System.currentTimeMillis();

            this.selected = index;

            if (index == this.lastIndex && now - this.lastClick < 350)
            {
                this.lastClick = now;

                UILayerLibrary.this.confirm();

                return true;
            }

            this.lastIndex = index;
            this.lastClick = now;

            return true;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            this.pressY = Integer.MIN_VALUE;
            this.dragged = false;

            return super.subMouseReleased(context);
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return super.subMouseScrolled(context);
            }

            this.scrollY -= (int) Math.round(context.mouseWheel * 34);
            this.clamp();

            return true;
        }
    }

    /* ------------------------------------------------------------------ icons */

    static Icon icon(String key)
    {
        return switch (key == null ? "" : key)
        {
            case "font" -> Icons.FONT;
            case "image" -> Icons.IMAGE;
            case "film" -> Icons.FILM;
            case "shapes" -> Icons.SHAPES;
            case "sphere" -> Icons.SPHERE;
            case "camera" -> Icons.CAMERA;
            case "sun" -> Icons.SUN;
            case "structure" -> Icons.STRUCTURE;
            case "replay" -> Icons.SPHERE;
            default -> Icons.SQUARE;
        };
    }
}
