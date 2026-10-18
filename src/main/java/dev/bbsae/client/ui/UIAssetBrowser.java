package dev.bbsae.client.ui;

import dev.bbsae.client.render.ImageTextureCache;
import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Photo-library style asset browser: categories, a thumbnail grid, insert/replace at the bottom.
 *
 * <p>It indexes three sources: the project's own {@code assets} folder, the client's resource packs
 * (item / block / entity / GUI / particle / effect / painting / environment / misc / map textures),
 * and the game's sound events. Textures are addressed as {@code mc:textures/...} and loaded by
 * {@link ImageTextureCache}, so nothing is copied into the project. Sounds are auditioned through
 * the vanilla sound manager and can also be browsed in BBS's own sound overlay.</p>
 */
public class UIAssetBrowser extends UIElement
{
    public static final String[] CATEGORY_LABELS = {
        "项目", "物品", "方块", "实体", "界面", "粒子",
        "状态", "画", "环境", "杂项", "地图", "BBS", "音效"
    };

    private static final String[] CATEGORY_ROOTS = {
        "textures/item", "textures/block", "textures/entity", "textures/gui",
        "textures/particle", "textures/mob_effect", "textures/painting",
        "textures/environment", "textures/misc", "textures/map"
    };

    private static final int PROJECT_CATEGORY = 0;
    private static final int BBS_CATEGORY = 11;
    private static final int SOUND_CATEGORY = 12;
    private static final int CATEGORY_COLUMNS = 7;

    private static final int COLUMNS = 6;
    private static final int ROWS = 4;
    private static final int TILE_W = 100;
    private static final int TILE_H = 62;

    private final Supplier<String> projectId;
    private final BiConsumer<String, Boolean> onUse;
    private final Runnable onImport;
    private final Runnable onClose;

    private final UIElement box;
    private final UILabel title;
    private final UILabel hint;
    private final UITextbox search;
    private final List<UIButton> categories = new ArrayList<>();
    private final Grid grid;
    private final UILabel pageLabel;
    private final UIButton importButton;
    private final UIButton insertButton;
    private final UIButton replaceButton;

    private int category;
    private int page;

    private final List<Asset> assets = new ArrayList<>();
    private final List<Asset> filtered = new ArrayList<>();
    private final List<Asset> projectAssets = new ArrayList<>();
    private String projectCache = "";

    private static final Map<Integer, List<Asset>> RESOURCE_CACHE = new java.util.HashMap<>();

    public static final class Asset
    {
        public final String path;
        public final String label;
        public final boolean project;
        public final boolean sound;
        public final String category;

        Asset(String path, String label, boolean project, String category)
        {
            this(path, label, project, false, category);
        }

        Asset(String path, String label, boolean project, boolean sound, String category)
        {
            this.path = path;
            this.label = label;
            this.project = project;
            this.sound = sound;
            this.category = category;
        }
    }

    public UIAssetBrowser(Supplier<String> projectId, BiConsumer<String, Boolean> onUse, Runnable onImport, Runnable onClose)
    {
        this.projectId = projectId;
        this.onUse = onUse;
        this.onImport = onImport;
        this.onClose = onClose;

        this.box = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF171C24);
                context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF36414F);
                super.render(context);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }
        };

        this.box.relative(this).x(0.5F, -350).y(0.5F, -220).w(700).h(440);

        this.title = new UILabel(IKey.constant("素材库"));
        this.title.relative(this.box).x(16).y(10).w(140).h(18);

        this.hint = new UILabel(IKey.constant("单击选择，双击插入；搜索会跨分类查找"));
        this.hint.color(0xFF8A94A6, false);
        this.hint.relative(this.box).x(16).y(28).w(400).h(14);

        this.search = new UITextbox();
        this.search.relative(this.box).x(452).y(10).w(232).h(20);
        this.search.placeholder(IKey.constant("搜索素材 / 音效…"));
        this.search.callback = (text) ->
        {
            this.page = 0;
            this.rebuild();
        };
        this.search.tooltip(IKey.constant("按名称或路径搜索；无匹配时自动跨分类"));

        for (int i = 0; i < CATEGORY_LABELS.length; i++)
        {
            final int index = i;
            UIButton button = new UIButton(IKey.constant(CATEGORY_LABELS[i]), (b) -> this.selectCategory(index));

            button.relative(this.box).x(16 + (i % CATEGORY_COLUMNS) * 96).y(48 + (i / CATEGORY_COLUMNS) * 22).w(92).h(20);
            button.tooltip(IKey.constant("浏览" + CATEGORY_LABELS[i] + "素材"));
            this.categories.add(button);
            this.box.add(button);
        }

        this.grid = new Grid();
        this.grid.relative(this.box).x(16).y(120).w(COLUMNS * TILE_W + (COLUMNS - 1) * 6).h(ROWS * TILE_H + (ROWS - 1) * 4);

        this.pageLabel = new UILabel(IKey.constant("1 / 1"));
        this.pageLabel.color(0xFF9AA6B8, false);
        this.pageLabel.relative(this.box).x(92).y(390).w(150).h(18);

        this.importButton = this.button("导入图片…", 16, 408, 100, this::importAction);
        this.insertButton = this.button("插入为新图层", 122, 408, 122, () -> this.useSelected(false));
        this.replaceButton = this.button("替换当前图层", 250, 408, 122, () -> this.useSelected(true));

        this.insertButton.tooltip(IKey.constant("把所选素材作为新图层插入"));
        this.replaceButton.tooltip(IKey.constant("替换所选图片图层的素材"));
        this.importButton.tooltip(IKey.constant("把本地图片复制到工程 assets 目录"));

        this.box.add(
            this.title, this.hint, this.search, this.grid, this.pageLabel,
            this.button("‹", 16, 390, 32, () -> this.turn(-1)),
            this.button("›", 54, 390, 32, () -> this.turn(1)),
            this.importButton, this.insertButton, this.replaceButton,
            this.button("关闭", 614, 408, 70, this.onClose),
            this.button("刷新", 614, 390, 70, this::refresh)
        );

        this.add(this.box);
        this.rebuild();
    }

    private UIButton button(String label, int x, int y, int w, Runnable action)
    {
        UIButton button = new UIButton(IKey.constant(label), (b) -> action.run());

        button.relative(this.box).x(x).y(y).w(w).h(20);

        return button;
    }

    /** Called after an import; drops the cached project listing and re-indexes it. */
    public void refresh()
    {
        this.projectCache = "";
        RESOURCE_CACHE.remove(100);
        this.rebuild();
    }

    private void selectCategory(int index)
    {
        this.category = index;
        this.page = 0;
        this.rebuild();
    }

    private void turn(int direction)
    {
        int pages = Math.max(1, (this.filtered.size() + COLUMNS * ROWS - 1) / (COLUMNS * ROWS));

        this.page = Math.max(0, Math.min(pages - 1, this.page + direction));
        this.grid.repaint();
        this.updatePageLabel();
    }

    private void rebuild()
    {
        this.assets.clear();

        String query = this.search.getText() == null ? "" : this.search.getText().trim().toLowerCase(Locale.ROOT);
        boolean filtering = !query.isEmpty();

        if (filtering)
        {
            /* A search is global: textures, sounds and the project folder at once. */
            this.assets.addAll(this.projectAssets());

            for (int i = 0; i < CATEGORY_ROOTS.length; i++)
            {
                this.assets.addAll(resourceAssets(i));
            }

            this.assets.addAll(bbsAssets());
            this.assets.addAll(soundAssets());
        }
        else if (this.category == PROJECT_CATEGORY)
        {
            this.assets.addAll(this.projectAssets());
        }
        else if (this.category == BBS_CATEGORY)
        {
            this.assets.addAll(bbsAssets());
        }
        else if (this.category == SOUND_CATEGORY)
        {
            this.assets.addAll(soundAssets());
        }
        else
        {
            this.assets.addAll(resourceAssets(this.category - 1));
        }

        this.filtered.clear();

        for (Asset asset : this.assets)
        {
            if (!filtering || asset.label.toLowerCase(Locale.ROOT).contains(query)
                || asset.path.toLowerCase(Locale.ROOT).contains(query))
            {
                this.filtered.add(asset);
            }
        }

        this.page = Math.max(0, Math.min(this.maxPage(), this.page));
        this.grid.repaint();
        this.updatePageLabel();
        this.updateButtons();
    }

    private int maxPage()
    {
        return Math.max(0, (this.filtered.size() + COLUMNS * ROWS - 1) / (COLUMNS * ROWS) - 1);
    }

    private void updatePageLabel()
    {
        int pages = this.maxPage() + 1;

        this.pageLabel.label = IKey.constant((this.page + 1) + " / " + pages + "   " + this.filtered.size() + " 项");
    }

    private void updateButtons()
    {
        boolean sounds = this.category == SOUND_CATEGORY;

        this.importButton.label = IKey.constant(sounds ? "BBS 音效库…" : "导入图片…");
        this.insertButton.label = IKey.constant(sounds ? "试听" : "插入为新图层");
        this.replaceButton.label = IKey.constant(sounds ? "复制 ID" : "替换当前图层");
    }

    private void importAction()
    {
        boolean sounds = this.category == SOUND_CATEGORY;

        if (sounds)
        {
            this.openBbsSoundLibrary();

            return;
        }

        this.onImport.run();
    }

    /**
     * Opens BBS's own sound overlay. This is literally the same list the film editor's audio
     * picker uses, so "调用原版音效" does not duplicate a single sound definition here.
     */
    private void openBbsSoundLibrary()
    {
        UISoundOverlayPanel panel = new UISoundOverlayPanel((link) ->
        {
            if (link == null)
            {
                return;
            }

            this.hint.label = IKey.constant("已选择音效：" + link);
            MinecraftClient.getInstance().keyboard.setClipboard(link.toString());
        });

        UIOverlay.addOverlay(this.getContext(), panel, 0.45F, 0.72F);
    }

    private static List<Asset> soundAssets()
    {
        List<Asset> cached = RESOURCE_CACHE.get(100);

        if (cached != null)
        {
            return cached;
        }

        List<Asset> list = new ArrayList<>();

        try
        {
            for (net.minecraft.sound.SoundEvent event : net.minecraft.registry.Registries.SOUND_EVENT)
            {
                Identifier id = net.minecraft.registry.Registries.SOUND_EVENT.getId(event);

                if (id != null)
                {
                    String label = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();

                    list.add(new Asset(id.toString(), label, false, true, "音效"));
                }
            }

            list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not index sound events", e);
        }

        RESOURCE_CACHE.put(100, list);

        return list;
    }

    private static void playSound(Asset asset)
    {
        try
        {
            Identifier id = Identifier.tryParse(asset.path);
            net.minecraft.sound.SoundEvent event = id == null ? null : net.minecraft.registry.Registries.SOUND_EVENT.get(id);

            if (event != null)
            {
                MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(event, 1F));
            }
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not play sound {}", asset.path, e);
        }
    }

    private List<Asset> projectAssets()
    {
        String id = this.projectId.get();

        if (id.equals(this.projectCache))
        {
            return this.projectAssets;
        }

        this.projectCache = id;
        this.projectAssets.clear();

        Path root = CompStore.assetRoot(id);

        if (!Files.isDirectory(root))
        {
            return this.projectAssets;
        }

        try (Stream<Path> stream = Files.walk(root, 6))
        {
            stream.filter(Files::isRegularFile)
                .filter((path) ->
                {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

                    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif");
                })
                .sorted()
                .limit(600)
                .forEach((path) ->
                {
                    String relative = root.relativize(path).toString().replace('\\', '/');

                    this.projectAssets.add(new Asset(relative, stripExtension(path.getFileName().toString()), true, "项目"));
                });
        }
        catch (Exception ignored)
        {}

        return this.projectAssets;
    }

    private static String stripExtension(String name)
    {
        int dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static List<Asset> resourceAssets(int root)
    {
        List<Asset> cached = RESOURCE_CACHE.get(root);

        if (cached != null)
        {
            return cached;
        }

        List<Asset> list = new ArrayList<>();

        try
        {
            Map<Identifier, ?> found = MinecraftClient.getInstance().getResourceManager()
                .findResources(CATEGORY_ROOTS[root], (id) -> "minecraft".equals(id.getNamespace()) && id.getPath().endsWith(".png"));

            for (Identifier id : found.keySet())
            {
                String path = id.getPath();
                String name = stripExtension(path.substring(path.lastIndexOf('/') + 1));

                list.add(new Asset("mc:" + path, name, false, CATEGORY_LABELS[root + 1]));
            }

            list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not index textures for category {}", root, e);
        }

        RESOURCE_CACHE.put(root, list);

        return list;
    }

    /** BBS's own GUI/icon textures; the resource pack namespace is {@code bbs}. */
    private static List<Asset> bbsAssets()
    {
        List<Asset> cached = RESOURCE_CACHE.get(200);

        if (cached != null)
        {
            return cached;
        }

        List<Asset> list = new ArrayList<>();

        try
        {
            Map<Identifier, ?> found = MinecraftClient.getInstance().getResourceManager()
                .findResources("textures", (id) -> "bbs".equals(id.getNamespace()) && id.getPath().endsWith(".png"));

            for (Identifier id : found.keySet())
            {
                String path = id.getPath();
                String name = stripExtension(path.substring(path.lastIndexOf('/') + 1));

                list.add(new Asset("bbs:" + path, name, false, "BBS"));
            }

            list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not index BBS textures", e);
        }

        RESOURCE_CACHE.put(200, list);

        return list;
    }

    private void useSelected(boolean replace)
    {
        Asset asset = this.grid.selected();

        if (asset == null)
        {
            this.hint.label = IKey.constant("先在网格里选择一个素材");
            this.hint.color(0xFFB06A6A, false);

            return;
        }

        this.hint.label = IKey.constant((asset.project ? "项目：" : "") + asset.path);
        this.hint.color(0xFF8A94A6, false);

        if (asset.sound)
        {
            if (replace)
            {
                MinecraftClient.getInstance().keyboard.setClipboard(asset.path);
                this.hint.label = IKey.constant("已复制音效 ID：" + asset.path);
            }
            else
            {
                playSound(asset);
            }

            return;
        }

        this.onUse.accept(asset.path, replace);
    }

    private class Grid extends UIElement
    {
        private int selectedIndex = -1;
        private long lastClick;
        private int lastIndex = -1;

        void repaint()
        {
            this.selectedIndex = Math.min(this.selectedIndex, Math.max(-1, this.visibleCount() - 1));
        }

        int visibleCount()
        {
            int start = UIAssetBrowser.this.page * COLUMNS * ROWS;

            return Math.max(0, Math.min(COLUMNS * ROWS, UIAssetBrowser.this.filtered.size() - start));
        }

        Asset selected()
        {
            int start = UIAssetBrowser.this.page * COLUMNS * ROWS;
            int index = start + this.selectedIndex;

            return this.selectedIndex >= 0 && index < UIAssetBrowser.this.filtered.size()
                ? UIAssetBrowser.this.filtered.get(index)
                : null;
        }

        @Override
        public void render(UIContext context)
        {
            Asset[] pageAssets = this.pageAssets();

            for (int i = 0; i < COLUMNS * ROWS; i++)
            {
                int x = this.area.x + (i % COLUMNS) * (TILE_W + 6);
                int y = this.area.y + (i / COLUMNS) * (TILE_H + 4);

                context.batcher.box(x, y, x + TILE_W, y + TILE_H, i == this.selectedIndex ? 0xFF2F5C9E : 0xFF20262F);

                if (i >= pageAssets.length)
                {
                    continue;
                }

                Asset asset = pageAssets[i];

                if (asset.sound)
                {
                    context.batcher.box(x + 34, y + 10, x + 66, y + 42, 0xFF2A3442);
                    context.batcher.icon(Icons.SOUND, 0xFFFFFFFF, x + TILE_W / 2 - 8, y + 16);
                }
                else
                {
                    ImageTextureCache.Entry texture = ImageTextureCache.get(UIAssetBrowser.this.projectId.get(), asset.path);

                    if (texture != null)
                    {
                        int size = 40;
                        int tw = texture.width;
                        int th = texture.height;
                        float fit = Math.min(size / (float) tw, size / (float) th);
                        int dw = Math.max(1, Math.round(tw * fit));
                        int dh = Math.max(1, Math.round(th * fit));

                        context.batcher.texturedBox(texture.texture.id, 0xFFFFFFFF,
                            x + (TILE_W - dw) / 2, y + 4 + (size - dh) / 2, dw, dh,
                            0F, 0F, tw, th, tw, th);
                    }
                    else
                    {
                        context.batcher.icon(Icons.IMAGE, 0xFF66707E, x + TILE_W / 2 - 8, y + 16);
                    }
                }

                String label = asset.label;

                if (label.length() > 15)
                {
                    label = label.substring(0, 14) + "…";
                }

                context.batcher.text(label, x + 4, y + TILE_H - 22, 0xFFB8C4D6);
                context.batcher.text(asset.category, x + 4, y + TILE_H - 11, 0xFF6F7A8A);
            }

            super.render(context);
        }

        private Asset[] pageAssets()
        {
            int start = UIAssetBrowser.this.page * COLUMNS * ROWS;
            int end = Math.min(UIAssetBrowser.this.filtered.size(), start + COLUMNS * ROWS);
            Asset[] pageAssets = new Asset[Math.max(0, end - start)];

            for (int i = start; i < end; i++)
            {
                pageAssets[i - start] = UIAssetBrowser.this.filtered.get(i);
            }

            return pageAssets;
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton != 0 || !this.area.isInside(context))
            {
                return super.subMouseClicked(context);
            }

            int localX = context.mouseX - this.area.x;
            int localY = context.mouseY - this.area.y;
            int column = localX / (TILE_W + 6);
            int row = localY / (TILE_H + 4);

            if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS)
            {
                return true;
            }

            int index = row * COLUMNS + column;
            long now = System.currentTimeMillis();

            if (index == this.lastIndex && now - this.lastClick < 350)
            {
                this.selectedIndex = index;

                if (UIAssetBrowser.this.category == SOUND_CATEGORY)
                {
                    UIAssetBrowser.this.useSelected(false);
                }
                else
                {
                    UIAssetBrowser.this.useSelected(false);
                }

                return true;
            }

            this.selectedIndex = index < this.visibleCount() ? index : -1;
            this.lastIndex = index;
            this.lastClick = now;

            Asset asset = this.selected();

            if (asset != null)
            {
                UIAssetBrowser.this.hint.label = IKey.constant(asset.path);
                UIAssetBrowser.this.hint.color(0xFF8A94A6, false);
            }

            return true;
        }
    }
}
