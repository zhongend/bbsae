package dev.bbsae.client.ui;

import dev.bbsae.client.render.ImageTextureCache;
import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.InterfaceBlur;
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
 * <h2>Layout: one sliding page, not a form</h2>
 * <p>The first version pinned every control to a 620×400 rectangle. That rectangle ran off the
 * bottom of a 432-px logical screen (GUI scale 2 on a 1536×864 window), so 关闭/刷新 and the whole
 * action row were <b>outside the visible area</b> — "the import button does nothing" was really
 * "the button is not reachable". Paging with ‹ › made it worse: the two page buttons were the first
 * things to fall off.</p>
 *
 * <p>So the browser is now a single page whose parts are laid out from the panel's real size:</p>
 * <ul>
 *   <li>header (title + search) and footer (actions) are anchored to the panel edges;</li>
 *   <li>the category strip <b>slides horizontally</b> (wheel, drag or the ‹ › zones) and never wraps
 *       into a second row that would push the grid down;</li>
 *   <li>the thumbnail grid <b>slides vertically</b> with the wheel or by dragging empty space, and
 *       uses whatever height is left;</li>
 *   <li>every label shortens as the panel narrows, so no two controls can overlap.</li>
 * </ul>
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

    private static final int TILE_W = 96;
    private static final int TILE_H = 60;
    private static final int TILE_GAP = 6;

    private static final int PAD = 10;
    private static final int HEADER = 40;
    private static final int STRIP_H = 22;
    private static final int FOOTER_H = 24;

    private final Supplier<String> projectId;
    private final BiConsumer<String, Boolean> onUse;
    private final boolean replaceMode;
    private final Runnable onImport;
    private final java.util.function.Consumer<String> onImportPath;
    private final Runnable onClose;

    private final UIElement box;
    private final UILabel title;
    private final UILabel hint;
    private final UITextbox search;
    private final Strip strip;
    private final Grid grid;
    private final UILabel countLabel;
    private final UIButton importButton;
    private final UIButton pathButton;
    private final UIButton insertButton;
    private final UIButton replaceButton;
    private final UIButton refreshButton;
    private final UIButton closeButton;

    private int category;
    /** Last laid-out size; the modal re-lays itself when the panel resizes. */
    private int laidOutWidth = -1;
    private int laidOutHeight = -1;
    /** Verification only: scroll to the bottom after the first real layout. */
    private boolean debugScrollBottomPending;

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

    /** Sound ids are imported into the composition's audio track; null = clipboard only. */
    private final java.util.function.Consumer<String> onSound;

    public UIAssetBrowser(Supplier<String> projectId, BiConsumer<String, Boolean> onUse, Runnable onImport, java.util.function.Consumer<String> onImportPath, java.util.function.Consumer<String> onSound, Runnable onClose, boolean replaceMode)
    {
        this.projectId = projectId;
        this.onUse = onUse;
        this.onImport = onImport;
        this.onImportPath = onImportPath;
        this.onSound = onSound;
        this.onClose = onClose;
        this.replaceMode = replaceMode;

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

        this.box.relative(this).x(0).y(0).w(1F).h(1F);

        this.title = new UILabel(IKey.constant("素材库"));
        this.title.relative(this.box).x(PAD).y(9).w(120).h(16);

        this.hint = new UILabel(IKey.constant(replaceMode ? "单击选择，双击替换；滚轮或右侧滑块滑动" : "单击选择，双击插入；滚轮或右侧滑块滑动"));
        this.hint.color(0xFF8A94A6, false);
        this.hint.relative(this.box).x(PAD).y(26).w(200).h(12);

        /* Built before anything that touches it: the old constructor registered the search callback
         * (which calls grid.scrollToTop()) before the grid existed, which did not compile. */
        this.grid = new Grid();
        this.strip = new Strip();

        this.search = new UITextbox();
        this.search.placeholder(IKey.constant("搜索…"));
        this.search.callback = (text) ->
        {
            this.grid.scrollToTop();
            this.rebuild();
        };
        this.search.tooltip(IKey.constant("按名称或路径搜索；无匹配时自动跨分类"));
        this.search.relative(this.box).x(200).y(8).w(200).h(18);

        this.countLabel = new UILabel(IKey.constant(""));
        this.countLabel.color(0xFF9AA6B8, false);
        this.countLabel.relative(this.box).x(PAD).y(100).w(200).h(12);

        this.importButton = this.button("导入图片…", this::importAction);
        this.pathButton = this.button("按路径…", this::importByPath);
        this.insertButton = this.button("插入为新图层", () -> this.useSelected(false));
        this.replaceButton = this.button("替换当前图层", () -> this.useSelected(true));
        this.refreshButton = this.button("刷新", this::refresh);
        this.closeButton = this.button("关闭", this.onClose);

        this.insertButton.tooltip(IKey.constant("把所选素材作为新图层插入"));
        this.replaceButton.tooltip(IKey.constant("替换所选图片图层的素材"));
        this.importButton.tooltip(IKey.constant("打开导入浏览器（游戏内选择文件）"));
        this.pathButton.tooltip(IKey.constant("直接粘贴完整路径导入"));
        this.refreshButton.tooltip(IKey.constant("重新扫描项目素材与资源包"));
        this.closeButton.tooltip(IKey.constant("关闭素材库"));

        this.box.add(this.title, this.hint, this.search, this.strip, this.grid, this.countLabel,
            this.importButton, this.pathButton, this.insertButton, this.replaceButton,
            this.refreshButton, this.closeButton);

        this.add(this.box);
        this.rebuild();
    }

    @Override
    public void render(UIContext context)
    {
        this.layout();

        if (this.debugScrollBottomPending && this.grid.maxScroll() > 0)
        {
            this.debugScrollBottomPending = false;
            this.grid.scrollY = this.grid.maxScroll();
        }

        /* Dim/blur the editor behind the library so the panel reads as a modal. The blur follows
         * BBS's interface-blur setting; the dark scrim is always present. */
        InterfaceBlur.applyUnder();
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0x66000000);
        super.render(context);
    }

    /**
     * Lays every control out from the panel's real size.
     *
     * <p>All positions are absolute (see HANDOFF 24.2: {@code UIElement.row()} ignores child
     * {@code w()}), and every size shrinks with the panel, so a narrow window stacks/shortens
     * instead of pushing the footer off-screen.</p>
     */
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

        this.box.set(0, 0, Math.max(1, width), Math.max(1, height));
        this.box.resize();

        int innerW = this.box.area.w;
        int innerH = this.box.area.h;
        boolean narrow = innerW < 560;

        int closeW = narrow ? 40 : 48;
        int refreshW = narrow ? 40 : 48;
        int searchRight = PAD + closeW + 6 + refreshW + 6;
        int searchX = narrow ? 96 : 132;
        int searchW = Math.max(60, innerW - searchRight - searchX);

        this.title.relative(this.box).x(PAD).y(9).w(Math.max(60, searchX - PAD - 6)).h(16);
        this.search.relative(this.box).x(searchX).y(8).w(searchW).h(18);
        this.hint.relative(this.box).x(PAD).y(26).w(1F, -PAD * 2).h(12);

        this.refreshButton.relative(this.box).x(1F, -(closeW + 6 + refreshW + PAD)).y(7).w(refreshW).h(18);
        this.closeButton.relative(this.box).x(1F, -(closeW + PAD)).y(7).w(closeW).h(18);

        int stripY = HEADER + 4;
        this.strip.relative(this.box).x(PAD).y(stripY).w(1F, -PAD * 2).h(STRIP_H);

        int footerY = innerH - PAD - 20;
        int countY = footerY - 16;

        this.grid.relative(this.box).x(PAD).y(stripY + STRIP_H + 6)
            .w(1F, -PAD * 2 - 12).h(Math.max(40, countY - stripY - STRIP_H - 12));
        this.countLabel.relative(this.box).x(PAD).y(countY).w(Math.max(100, innerW - PAD * 2)).h(12);

        int x = PAD;
        int h = 20;

        if (narrow)
        {
            this.importButton.label = IKey.constant("导入");
            this.pathButton.label = IKey.constant("路径");
            this.insertButton.label = IKey.constant("插入");
            this.replaceButton.label = IKey.constant("替换");
            this.importButton.relative(this.box).x(x).y(footerY).w(44).h(h); x += 48;
            this.pathButton.relative(this.box).x(x).y(footerY).w(44).h(h); x += 48;
            this.insertButton.relative(this.box).x(x).y(footerY).w(44).h(h); x += 48;
            this.replaceButton.relative(this.box).x(x).y(footerY).w(44).h(h);
        }
        else
        {
            this.importButton.label = IKey.constant("导入图片…");
            this.pathButton.label = IKey.constant("按路径…");
            this.insertButton.label = IKey.constant("插入为新图层");
            this.replaceButton.label = IKey.constant("替换当前图层");
            this.importButton.relative(this.box).x(x).y(footerY).w(92).h(h); x += 96;
            this.pathButton.relative(this.box).x(x).y(footerY).w(72).h(h); x += 76;
            this.insertButton.relative(this.box).x(x).y(footerY).w(106).h(h); x += 110;
            this.replaceButton.relative(this.box).x(x).y(footerY).w(106).h(h);
        }

        this.box.resize();
        this.grid.clampScroll();
    }

    private UIButton button(String label, Runnable action)
    {
        UIButton button = new UICompButton(IKey.constant(label), (b) -> action.run());

        button.relative(this.box).x(0).y(0).w(60).h(20);

        return button;
    }

    /** Called after an import; drops the cached project listing and re-indexes it. */
    public void refresh()
    {
        this.projectCache = "";
        RESOURCE_CACHE.remove(100);
        this.rebuild();
    }

    /** Verification hook: put the grid at the bottom so the scrollbar thumb is visible. */
    public void debugScrollToBottom()
    {
        this.debugScrollBottomPending = true;
    }

    /** Verification hook: open a populated texture category. */
    public void debugShowCategory(int index)
    {
        this.selectCategory(Math.max(0, Math.min(CATEGORY_LABELS.length - 1, index)));
    }

    /**
     * Selects the asset whose path ends with {@code path} and scrolls it into view.
     *
     * <p>Used right after an import: the user picked a file in the import browser, so the library
     * must show <em>that</em> file selected (and its thumbnail loaded) instead of leaving them to
     * hunt for it in a grid of hundreds of icons. A mismatch is a no-op, not an error.</p>
     */
    public void selectPath(String path)
    {
        if (path == null || path.isBlank())
        {
            return;
        }

        if (this.selectPathInCurrent(path))
        {
            return;
        }

        /* Freshly copied files live in the project folder, so an import lands there even when the
         * library happened to be showing "方块". Switch, rebuild once, try again. */
        if (this.category != PROJECT_CATEGORY)
        {
            this.category = PROJECT_CATEGORY;
            this.strip.reveal(PROJECT_CATEGORY);
            this.rebuild();
            this.selectPathInCurrent(path);
        }
    }

    private boolean selectPathInCurrent(String path)
    {
        String needle = path.replace('\\', '/');

        for (int i = 0; i < this.filtered.size(); i++)
        {
            String candidate = this.filtered.get(i).path.replace('\\', '/');

            if (candidate.equals(needle) || candidate.endsWith("/" + needle) || needle.endsWith("/" + candidate))
            {
                this.grid.selected = i;
                this.grid.reveal(i);

                Asset asset = this.filtered.get(i);

                this.hint.label = IKey.constant(asset.path);
                this.hint.color(0xFF8A94A6, false);

                return true;
            }
        }

        return false;
    }

    private void selectCategory(int index)
    {
        this.category = index;
        this.grid.scrollToTop();
        this.strip.reveal(index);
        this.rebuild();
    }

    /** Imports from a typed/pasted path; the fallback when the OS dialog is unavailable. */
    private void importByPath()
    {
        if (this.category == SOUND_CATEGORY)
        {
            this.openBbsSoundLibrary();

            return;
        }

        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
            IKey.constant("按路径导入"),
            IKey.constant("粘贴图片的完整路径（例如 C:/Pictures/a.png）"),
            (path) ->
            {
                if (path != null && !path.isBlank())
                {
                    this.onImportPath.accept(path.trim());
                }
            });

        UIOverlay.addOverlay(this.getContext(), panel, 0.4F, 0.5F);
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

        this.grid.repaint();
        this.updateCountLabel();
        this.updateButtons();
    }

    private void updateCountLabel()
    {
        this.countLabel.label = IKey.constant(this.filtered.size() + " 项 · " + CATEGORY_LABELS[this.category]
            + (this.filtered.size() > 0 ? " · 滚轮/滑块滑动" : " · 该分类没有素材"));
    }

    private void updateButtons()
    {
        boolean sounds = this.category == SOUND_CATEGORY;

        this.importButton.label = IKey.constant(sounds ? "BBS 音效库…" : (this.laidOutWidth < 560 ? "导入" : "导入图片…"));
        this.insertButton.label = IKey.constant(sounds ? "试听" : (this.laidOutWidth < 560 ? "插入" : "插入为新图层"));
        this.replaceButton.label = IKey.constant(sounds ? "复制 ID" : (this.laidOutWidth < 560 ? "替换" : "替换当前图层"));
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

            MinecraftClient.getInstance().keyboard.setClipboard(link.toString());
            this.hint.label = IKey.constant("已选择音效：" + link);

            if (this.onSound != null)
            {
                this.onSound.accept(link.toString());
            }
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
            else if (this.onSound != null)
            {
                /* Double-click / "insert" on a sound adds it to the audio track. Previewing is
                 * the explicit "试听" button, so the two actions stay separate. */
                this.onSound.accept(asset.path);
            }
            else
            {
                playSound(asset);
            }

            return;
        }

        this.onUse.accept(asset.path, replace);
    }

    /* ------------------------------------------------------------- category strip */

    /**
     * The horizontally sliding category strip.
     *
     * <p>Chips are drawn and hit-tested by hand for one reason: a child {@link UIButton} scrolled
     * outside the strip would still accept clicks in the area it occupies on screen, so the
     * invisible part of the strip would swallow clicks meant for the grid. Drawing the chips means
     * clipping and hit testing share one coordinate space.</p>
     */
    private class Strip extends UIElement
    {
        private float scroll;
        private int dragStartX = Integer.MIN_VALUE;
        private int dragStartScroll;
        private boolean dragged;

        int chipWidth(int index)
        {
            String label = CATEGORY_LABELS[index];

            return 20 + label.length() * 12;
        }

        int contentWidth()
        {
            int total = 0;

            for (int i = 0; i < CATEGORY_LABELS.length; i++)
            {
                total += this.chipWidth(i) + 4;
            }

            return Math.max(0, total - 4);
        }

        int maxScroll()
        {
            return Math.max(0, this.contentWidth() - this.area.w);
        }

        void clamp()
        {
            this.scroll = Math.max(0F, Math.min(this.maxScroll(), this.scroll));
        }

        /** Scrolls the strip so the selected chip is fully visible. */
        void reveal(int index)
        {
            int x = 0;

            for (int i = 0; i < index; i++)
            {
                x += this.chipWidth(i) + 4;
            }

            int right = x + this.chipWidth(index);

            if (x < this.scroll)
            {
                this.scroll = x;
            }
            else if (right > this.scroll + this.area.w)
            {
                this.scroll = right - this.area.w;
            }

            this.clamp();
        }

        @Override
        public void render(UIContext context)
        {
            /* Live drag-to-slide: the strip is 13 chips wide, and on a trackpad the arrows are a
             * poor substitute for pushing the row. Click/release are the only events BBS sends, so
             * the offset is followed here while the pointer is down. */
            if (this.dragStartX != Integer.MIN_VALUE)
            {
                int delta = this.dragStartX - context.mouseX;

                this.scroll = this.dragStartScroll + delta;
                this.clamp();
            }

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF11161D);
            context.batcher.clip(this.area, context);

            try
            {
                int x = this.area.x - Math.round(this.scroll);
                int y = this.area.y + 1;
                int h = this.area.h - 2;

                for (int i = 0; i < CATEGORY_LABELS.length; i++)
                {
                    int w = this.chipWidth(i);
                    boolean active = i == UIAssetBrowser.this.category;

                    if (x + w >= this.area.x && x <= this.area.ex())
                    {
                        context.batcher.box(x, y, x + w, y + h, active ? 0xFF2F5C9E : 0xFF1E242D);
                        context.batcher.box(x, y + h - 1, x + w, y + h, active ? 0xFF4C8BE0 : 0xFF2A313B);
                        context.batcher.text(CATEGORY_LABELS[i], x + 6, y + (h - 8) / 2, active ? 0xFFFFFFFF : 0xFFB8C4D6);
                    }

                    x += w + 4;
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }

            this.renderArrows(context);

            super.render(context);
        }

        /** ‹ › zones that slide one page, so the strip is usable without a wheel. */
        private void renderArrows(UIContext context)
        {
            if (this.maxScroll() <= 0)
            {
                return;
            }

            int mid = this.area.y + this.area.h / 2;

            if (this.scroll > 0)
            {
                context.batcher.box(this.area.x, this.area.y, this.area.x + 14, this.area.ey(), 0xCC141A22);
                context.batcher.text("‹", this.area.x + 5, mid - 4, 0xFFD6DEEB);
            }

            if (this.scroll < this.maxScroll())
            {
                context.batcher.box(this.area.ex() - 14, this.area.y, this.area.ex(), this.area.ey(), 0xCC141A22);
                context.batcher.text("›", this.area.ex() - 10, mid - 4, 0xFFD6DEEB);
            }
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton != 0 || !this.area.isInside(context))
            {
                return super.subMouseClicked(context);
            }

            if (this.scroll > 0 && context.mouseX < this.area.x + 14)
            {
                this.scroll -= Math.max(40, this.area.w - 40);
                this.clamp();

                return true;
            }

            if (this.scroll < this.maxScroll() && context.mouseX > this.area.ex() - 14)
            {
                this.scroll += Math.max(40, this.area.w - 40);
                this.clamp();

                return true;
            }

            int x = this.area.x - Math.round(this.scroll);

            for (int i = 0; i < CATEGORY_LABELS.length; i++)
            {
                int w = this.chipWidth(i);

                if (context.mouseX >= x && context.mouseX <= x + w)
                {
                    UIAssetBrowser.this.selectCategory(i);

                    return true;
                }

                x += w + 4;
            }

            this.dragStartX = context.mouseX;
            this.dragStartScroll = Math.round(this.scroll);
            this.dragged = false;

            return true;
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return super.subMouseScrolled(context);
            }

            this.scroll -= (int) Math.round(context.mouseWheel * 42D);
            this.clamp();

            return true;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            this.dragStartX = Integer.MIN_VALUE;

            return super.subMouseReleased(context);
        }
    }

    /* ---------------------------------------------------------------------- grid */

    /**
     * Thumbnail grid with a scroll offset.
     *
     * <p>Paging was the original design (three rows, ‹ › buttons). It wasted the panel on a
     * 1080p screen and broke entirely on a smaller one: the page buttons and the action row sat
     * below the visible area. A scroll view uses whatever height the panel has and never needs a
     * second control to reach the content.</p>
     */
    private class Grid extends UIElement
    {
        private static final int SCROLLBAR_W = 8;

        private int selected = -1;
        private int scrollY;
        private long lastClick;
        private int lastIndex = -1;
        private int pressX = Integer.MIN_VALUE;
        private int pressY;
        private int pressScroll;
        private boolean dragMoved;
        /** True while the scrollbar thumb itself is being dragged/jumped. */
        private boolean scrollbarDrag;
        /** Pointer offset inside the thumb at grab time, so it does not jump under the cursor. */
        private int scrollbarGrabY;

        void scrollToTop()
        {
            this.scrollY = 0;
        }

        /** Scrolls the tile at {@code index} into view, one row of margin. */
        void reveal(int index)
        {
            int columns = this.columns();

            if (columns <= 0 || index < 0)
            {
                return;
            }

            int row = index / columns;
            int top = row * (TILE_H + TILE_GAP);
            int bottom = top + TILE_H;

            if (top < this.scrollY)
            {
                this.scrollY = top;
            }
            else if (bottom > this.scrollY + this.area.h)
            {
                this.scrollY = bottom - this.area.h;
            }

            this.clampScroll();
        }

        void repaint()
        {
            this.clampScroll();
            this.selected = Math.min(this.selected, UIAssetBrowser.this.filtered.size() - 1);
        }

        int columns()
        {
            return Math.max(1, (this.area.w + TILE_GAP) / (TILE_W + TILE_GAP));
        }

        int contentHeight()
        {
            int rows = (UIAssetBrowser.this.filtered.size() + this.columns() - 1) / this.columns();

            return Math.max(0, rows * (TILE_H + TILE_GAP) - TILE_GAP);
        }

        void clampScroll()
        {
            int max = this.maxScroll();

            this.scrollY = Math.max(0, Math.min(max, this.scrollY));
        }

        private int maxScroll()
        {
            return Math.max(0, this.contentHeight() - this.area.h);
        }

        private int scrollbarX()
        {
            return this.area.ex() - SCROLLBAR_W;
        }

        private boolean inScrollbar(UIContext context)
        {
            return context.mouseX >= this.scrollbarX()
                && context.mouseX <= this.area.ex()
                && this.area.isInside(context);
        }

        private int thumbHeight()
        {
            int content = this.contentHeight();

            if (content <= this.area.h || this.area.h <= 0)
            {
                return this.area.h;
            }

            return Math.max(24, Math.round(this.area.h * (this.area.h / (float) content)));
        }

        private int thumbY()
        {
            int max = this.maxScroll();

            if (max <= 0)
            {
                return this.area.y;
            }

            return this.area.y + Math.round((this.area.h - this.thumbHeight())
                * (this.scrollY / (float) max));
        }

        /**
         * Maps a pointer Y into a scroll offset. The thumb itself is the handle: grabbing its
         * middle keeps it under the cursor, while a track click centres it (the behaviour of
         * every native scrollbar).
         */
        private void scrollToPointer(int mouseY, int grabY)
        {
            int max = this.maxScroll();
            int travel = this.area.h - this.thumbHeight();

            if (max <= 0 || travel <= 0)
            {
                this.scrollY = 0;

                return;
            }

            float ratio = (mouseY - grabY - this.area.y) / (float) travel;

            this.scrollY = Math.round(Math.max(0F, Math.min(1F, ratio)) * max);
        }

        Asset selected()
        {
            return this.selected >= 0 && this.selected < UIAssetBrowser.this.filtered.size()
                ? UIAssetBrowser.this.filtered.get(this.selected)
                : null;
        }

        @Override
        public void render(UIContext context)
        {
            /* Scrollbar drags are polled like the tile drag: BBS only dispatches click/release,
             * and a fast drag can release over a sibling that consumes the release. Without the
             * physical-button check the thumb would stay glued to the cursor. */
            if (this.scrollbarDrag)
            {
                if (!Window.isMouseButtonPressed(0))
                {
                    this.scrollbarDrag = false;
                }
                else
                {
                    this.scrollToPointer(context.mouseY, this.scrollbarGrabY);
                }
            }

            /* Drag-to-scroll has to be polled: BBS dispatches click/release only, so a drag is
             * "press position in, cursor far away now" and the offset follows the cursor. */
            if (this.pressX != Integer.MIN_VALUE)
            {
                int delta = this.pressY - context.mouseY;

                if (Math.abs(delta) > 4)
                {
                    this.dragMoved = true;
                    this.scrollY = this.pressScroll + delta;
                    this.clampScroll();
                }
            }

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF141920);
            context.batcher.clip(this.area, context);

            try
            {
                int columns = this.columns();
                int colW = TILE_W + TILE_GAP;
                int rowH = TILE_H + TILE_GAP;
                int firstRow = Math.max(0, this.scrollY / rowH);
                int lastRow = (this.scrollY + this.area.h) / rowH;

                for (int row = firstRow; row <= lastRow; row++)
                {
                    for (int column = 0; column < columns; column++)
                    {
                        int index = row * columns + column;

                        if (index >= UIAssetBrowser.this.filtered.size())
                        {
                            break;
                        }

                        int x = this.area.x + column * colW;
                        int y = this.area.y + row * rowH - this.scrollY;

                        this.renderTile(context, UIAssetBrowser.this.filtered.get(index), index, x, y);
                    }
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }

            this.renderScrollbar(context);
            super.render(context);
        }

        private void renderTile(UIContext context, Asset asset, int index, int x, int y)
        {
            boolean isSelected = index == this.selected;

            context.batcher.box(x, y, x + TILE_W, y + TILE_H, isSelected ? 0xFF2F5C9E : 0xFF20262F);

            if (asset.sound)
            {
                context.batcher.box(x + (TILE_W - 32) / 2, y + 6, x + (TILE_W + 32) / 2, y + 38, 0xFF2A3442);
                context.batcher.icon(Icons.SOUND, 0xFFFFFFFF, x + TILE_W / 2 - 8, y + 12);
            }
            else
            {
                ImageTextureCache.Entry texture = ImageTextureCache.get(UIAssetBrowser.this.projectId.get(), asset.path);

                if (texture != null)
                {
                    int size = 38;
                    int tw = texture.width;
                    int th = texture.height;
                    float fit = Math.min(size / (float) tw, size / (float) th);
                    int dw = Math.max(1, Math.round(tw * fit));
                    int dh = Math.max(1, Math.round(th * fit));

                    context.batcher.texturedBox(texture.texture.id, 0xFFFFFFFF,
                        x + (TILE_W - dw) / 2, y + 3 + (size - dh) / 2, dw, dh,
                        0F, 0F, tw, th, tw, th);
                }
                else
                {
                    context.batcher.icon(Icons.IMAGE, 0xFF66707E, x + TILE_W / 2 - 8, y + 12);
                }
            }

            String label = asset.label;

            if (label.length() > 13)
            {
                label = label.substring(0, 12) + "…";
            }

            context.batcher.text(label, x + 4, y + TILE_H - 21, 0xFFB8C4D6);
            context.batcher.text(asset.category, x + 4, y + TILE_H - 10, 0xFF6F7A8A);
        }

        private void renderScrollbar(UIContext context)
        {
            int max = this.maxScroll();

            if (max <= 0 || this.area.h <= 0)
            {
                return;
            }

            int x = this.scrollbarX();
            int barH = this.thumbHeight();
            int barY = this.thumbY();
            boolean hot = this.scrollbarDrag
                || (this.inScrollbar(context) && context.mouseY >= barY && context.mouseY <= barY + barH);

            context.batcher.box(x, this.area.y, this.area.ex(), this.area.ey(), 0x30101822);
            context.batcher.box(x + 1, barY, this.area.ex() - 1, barY + barH,
                hot ? 0xFFB9C6D8 : 0x99C3CCDA);
            /* Grip lines make the thumb read as a control, not as a progress bar. */
            context.batcher.box(x + 3, barY + barH / 2 - 3, this.area.ex() - 3, barY + barH / 2 - 2, 0x66000000);
            context.batcher.box(x + 3, barY + barH / 2 + 1, this.area.ex() - 3, barY + barH / 2 + 2, 0x66000000);
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton != 0 || !this.area.isInside(context))
            {
                return super.subMouseClicked(context);
            }

            /* The scrollbar is a real control: grabbing the thumb, or clicking the track to page
             * to that position. It used to be a painted-only 3 px line, which is exactly the
             * "滑块不能滑动" report. */
            if (this.maxScroll() > 0 && this.inScrollbar(context))
            {
                int barY = this.thumbY();
                int barH = this.thumbHeight();

                this.scrollbarDrag = true;
                this.scrollbarGrabY = context.mouseY >= barY && context.mouseY <= barY + barH
                    ? context.mouseY - barY
                    : barH / 2;
                this.scrollToPointer(context.mouseY, this.scrollbarGrabY);

                return true;
            }

            this.pressX = context.mouseX;
            this.pressY = context.mouseY;
            this.pressScroll = this.scrollY;
            this.dragMoved = false;

            return true;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            if (this.scrollbarDrag)
            {
                this.scrollbarDrag = false;

                return true;
            }

            if (this.pressX == Integer.MIN_VALUE)
            {
                return super.subMouseReleased(context);
            }

            boolean dragged = this.dragMoved;
            int pressX = this.pressX;

            this.pressX = Integer.MIN_VALUE;
            this.dragMoved = false;

            if (dragged || !this.area.isInside(context))
            {
                return true;
            }

            int columns = this.columns();
            int column = (pressX - this.area.x) / (TILE_W + TILE_GAP);
            int row = (this.pressY - this.area.y + this.scrollY) / (TILE_H + TILE_GAP);

            if (column < 0 || column >= columns || row < 0)
            {
                return true;
            }

            int index = row * columns + column;

            if (index >= UIAssetBrowser.this.filtered.size())
            {
                return true;
            }

            long now = System.currentTimeMillis();

            if (index == this.lastIndex && now - this.lastClick < 350)
            {
                this.selected = index;

                UIAssetBrowser.this.useSelected(UIAssetBrowser.this.replaceMode);

                return true;
            }

            this.selected = index;
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

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return super.subMouseScrolled(context);
            }

            this.scrollY -= (int) Math.round(context.mouseWheel * 46D);
            this.clampScroll();

            return true;
        }
    }
}
