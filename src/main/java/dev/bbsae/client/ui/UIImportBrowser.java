package dev.bbsae.client.ui;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.InterfaceBlur;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * In-game file browser used to import external assets.
 *
 * <h2>Why not the OS dialog</h2>
 * <p>BBS offers {@code UIFileDialogs}, which calls LWJGL's tinyfd on a background thread. That is a
 * <b>native window</b>: on a fullscreen/borderless Minecraft window it can open behind the game, and
 * where tinyfd cannot present a dialog it simply returns nothing — the callback never fires and the
 * import button looks dead. There is no error to report, because from the game's point of view
 * nothing failed.</p>
 *
 * <p>An import path that the game itself draws cannot fail that way. This browser lists drives,
 * folders and matching files with BBS UI elements, so it works in any window mode and on any
 * desktop environment. It also doubles as the audio/sequence importer, which is why the extension
 * filter is a parameter.</p>
 */
public class UIImportBrowser extends UIElement
{
    private static final int PAD = 10;
    private static final int ROW_H = 16;

    private final String[] extensions;
    private final Consumer<File> onPick;
    private final Runnable onClose;

    private final UIElement box;
    private final UILabel title;
    private final UILabel pathLabel;
    private final UITextbox pathBox;
    private final UIButton upButton;
    private final UIButton goButton;
    private final UIButton importButton;
    private final UIButton systemButton;
    private final UIButton cancelButton;

    /** Last folder the user browsed; shared by every import in the session. */
    private static Path lastFolder;
    private final ListView list;

    private Path current;

    private int laidOutWidth = -1;
    private int laidOutHeight = -1;

    public UIImportBrowser(String title, String[] extensions, Consumer<File> onPick, Runnable onClose)
    {
        this.extensions = extensions == null ? new String[0] : extensions;
        this.onPick = onPick;
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
        this.box.relative(this).x(0).y(0).w(1F).h(1F);

        this.title = new UILabel(IKey.constant(title));
        this.title.relative(this.box).x(PAD).y(9).w(200).h(16);

        this.pathLabel = new UILabel(IKey.constant("路径"));
        this.pathLabel.color(0xFF8A94A6, false);
        this.pathLabel.relative(this.box).x(PAD).y(32).w(200).h(12);

        this.pathBox = new UITextbox(1024, (text) -> {});
        this.pathBox.relative(this.box).x(PAD).y(46).w(200).h(18);

        this.upButton = new UICompButton(IKey.constant("上一级"), (button) -> this.goUp());
        this.upButton.relative(this.box).x(0).y(46).w(56).h(18);
        this.upButton.tooltip(IKey.constant("返回上一级目录"));

        this.goButton = new UICompButton(IKey.constant("转到"), (button) -> this.goTo(this.pathBox.getText()));
        this.goButton.relative(this.box).x(0).y(46).w(48).h(18);
        this.goButton.tooltip(IKey.constant("打开路径框里的目录"));

        this.list = new ListView();
        this.list.relative(this.box).x(PAD).y(70).w(1F, -PAD * 2).h(100);

        /* The native picker as an option, not as the only path.
         *
         * The in-game browser exists because tinyfd can return nothing inside a fullscreen game;
         * on machines where it does work, users still prefer the real folder dialog (bookmarks,
         * recent places, typing a path). Both are one click apart. */
        this.systemButton = new UICompButton(IKey.constant("系统对话框…"), (button) -> this.openSystemDialog());
        this.systemButton.relative(this.box).x(0).y(0).w(88).h(20);
        this.systemButton.tooltip(IKey.constant("调用操作系统的文件对话框；如果不弹窗，用下面的列表选择"));

        this.importButton = new UICompButton(IKey.constant("导入所选"), (button) -> this.confirm());
        this.importButton.relative(this.box).x(0).y(0).w(88).h(20);
        this.importButton.tooltip(IKey.constant("把选中的文件复制到工程素材目录"));

        this.cancelButton = new UICompButton(IKey.constant("取消"), (button) -> this.onClose.run());
        this.cancelButton.relative(this.box).x(0).y(0).w(48).h(20);

        this.box.add(this.title, this.pathLabel, this.pathBox, this.upButton, this.goButton, this.list,
            this.importButton, this.systemButton, this.cancelButton);

        this.add(this.box);

        this.current = defaultFolder();
        this.reload();
    }

    /**
     * The OS picker, on the same worker-thread path every other importer uses.
     *
     * <p>It used to call BBS's {@code UIFileDialogs}, whose tinyfd window has no owner and can be
     * created behind a borderless game window — the dialog never appears, the callback never
     * fires, and the button reads as dead. {@link NativeFileDialog} owns a real AWT owner frame
     * and reports an unavailable toolkit instead of failing silently.</p>
     */
    private void openSystemDialog()
    {
        NativeFileDialog.pickFile(
            "选择文件",
            this.extensions,
            (file) ->
            {
                if (file != null && file.isFile())
                {
                    this.onPick.accept(file);
                }
            },
            () -> this.title.label = IKey.constant("系统对话框不可用 · 用下面的列表选择"));
    }

    private static Path defaultFolder()
    {
        if (lastFolder != null && Files.isDirectory(lastFolder))
        {
            return lastFolder;
        }

        String home = System.getProperty("user.home");
        String[] candidates = {"Pictures", "Desktop", "Downloads", ""};

        for (String candidate : candidates)
        {
            try
            {
                Path path = candidate.isEmpty() ? Path.of(home) : Path.of(home, candidate);

                if (Files.isDirectory(path))
                {
                    return path;
                }
            }
            catch (Exception ignored)
            {}
        }

        return Path.of(".");
    }

    @Override
    public void render(UIContext context)
    {
        this.layout();

        InterfaceBlur.applyUnder();
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0x66000000);
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

        this.box.set(0, 0, Math.max(1, width), Math.max(1, height));
        this.box.resize();

        int innerW = this.box.area.w;
        int innerH = this.box.area.h;
        int footerY = innerH - PAD - 20;

        this.pathBox.relative(this.box).x(PAD).y(46).w(Math.max(80, innerW - PAD * 2 - 56 - 48 - 12)).h(18);
        this.upButton.relative(this.box).x(1F, -(48 + 6 + 56 + PAD)).y(46).w(56).h(18);
        this.goButton.relative(this.box).x(1F, -(48 + PAD)).y(46).w(48).h(18);

        this.list.relative(this.box).x(PAD).y(70).w(1F, -PAD * 2 - 4).h(Math.max(30, footerY - 70 - 8));

        this.importButton.relative(this.box).x(PAD).y(footerY).w(88).h(20);
        this.systemButton.relative(this.box).x(PAD + 92).y(footerY).w(88).h(20);
        this.cancelButton.relative(this.box).x(1F, -(48 + PAD)).y(footerY).w(48).h(20);

        this.box.resize();
        this.list.clampScroll();
    }

    private void goUp()
    {
        Path parent = this.current == null ? null : this.current.getParent();

        if (parent != null)
        {
            this.current = parent;
            this.reload();
        }
        else
        {
            /* At a drive root: show the drive list. */
            this.current = null;
            this.reload();
        }
    }

    private void goTo(String text)
    {
        if (text == null || text.isBlank())
        {
            return;
        }

        try
        {
            Path path = Path.of(text.trim());

            if (Files.isDirectory(path))
            {
                this.current = path;
                this.reload();
            }
            else if (Files.isRegularFile(path))
            {
                this.current = path.getParent();
                this.reload();
                this.list.selectByName(path.getFileName().toString());
            }
        }
        catch (Exception ignored)
        {}
    }

    private void reload()
    {
        if (this.current != null && Files.isDirectory(this.current))
        {
            lastFolder = this.current;
        }

        this.list.rows.clear();

        if (this.current == null)
        {
            this.title.label = IKey.constant("选择文件 · 驱动器");
            this.pathBox.setText("");

            for (File root : File.listRoots())
            {
                this.list.rows.add(Entry.folder(root.getName(), root.toPath()));
            }
        }
        else
        {
            this.title.label = IKey.constant("选择文件 · " + this.current.getFileName());
            this.pathBox.setText(this.current.toAbsolutePath().toString());

            List<Entry> folders = new ArrayList<>();
            List<Entry> files = new ArrayList<>();

            try (var stream = Files.list(this.current))
            {
                stream.forEach((path) ->
                {
                    try
                    {
                        String name = path.getFileName().toString();

                        if (Files.isDirectory(path))
                        {
                            folders.add(Entry.folder(name, path));
                        }
                        else if (this.accepts(name))
                        {
                            files.add(Entry.file(name, path, sizeOf(path)));
                        }
                    }
                    catch (Exception ignored)
                    {}
                });
            }
            catch (Exception ignored)
            {}

            folders.sort(Comparator.comparing((entry) -> entry.name.toLowerCase(Locale.ROOT)));
            files.sort(Comparator.comparing((entry) -> entry.name.toLowerCase(Locale.ROOT)));

            this.list.rows.addAll(folders);
            this.list.rows.addAll(files);
        }

        this.list.scrollY = 0;
        this.list.selected = -1;
        this.list.clampScroll();
    }

    private static String sizeOf(Path path)
    {
        try
        {
            long bytes = Files.size(path);

            if (bytes >= 1024 * 1024)
            {
                return String.format(Locale.ROOT, "%.1f MB", bytes / (1024F * 1024F));
            }

            return Math.max(1, bytes / 1024) + " KB";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private boolean accepts(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);

        for (String extension : this.extensions)
        {
            String suffix = extension.startsWith(".") ? extension.substring(1) : extension;

            if (lower.endsWith("." + suffix.toLowerCase(Locale.ROOT)))
            {
                return true;
            }
        }

        return false;
    }

    private void confirm()
    {
        Entry entry = this.list.selected();

        if (entry == null)
        {
            this.title.label = IKey.constant("先在列表里选择一个文件");

            return;
        }

        if (entry.folder)
        {
            this.current = entry.path;
            this.reload();

            return;
        }

        this.onPick.accept(entry.path.toFile());
    }

    private static final class Entry
    {
        final String name;
        final Path path;
        final boolean folder;
        final String detail;

        private Entry(String name, Path path, boolean folder, String detail)
        {
            this.name = name;
            this.path = path;
            this.folder = folder;
            this.detail = detail;
        }

        static Entry folder(String name, Path path)
        {
            return new Entry(name, path, true, "目录");
        }

        static Entry file(String name, Path path, String detail)
        {
            return new Entry(name, path, false, detail);
        }
    }

    /** Scrollable rows; folders first, files below, both alphabetical. */
    private final class ListView extends UIElement
    {
        private final List<Entry> rows = new ArrayList<>();
        private int scrollY;
        private int selected = -1;
        private int pressY = Integer.MIN_VALUE;
        private int pressScroll;
        private boolean dragged;
        private long lastClick;
        private int lastIndex = -1;

        int contentHeight()
        {
            return Math.max(0, this.rows.size() * ROW_H);
        }

        void clampScroll()
        {
            int max = Math.max(0, this.contentHeight() - this.area.h);

            this.scrollY = Math.max(0, Math.min(max, this.scrollY));
        }

        Entry selected()
        {
            return this.selected >= 0 && this.selected < this.rows.size() ? this.rows.get(this.selected) : null;
        }

        void selectByName(String name)
        {
            for (int i = 0; i < this.rows.size(); i++)
            {
                if (this.rows.get(i).name.equals(name))
                {
                    this.selected = i;
                    this.reveal(i);

                    return;
                }
            }
        }

        void reveal(int index)
        {
            int y = index * ROW_H;

            if (y < this.scrollY)
            {
                this.scrollY = y;
            }
            else if (y + ROW_H > this.scrollY + this.area.h)
            {
                this.scrollY = y + ROW_H - this.area.h;
            }

            this.clampScroll();
        }

        @Override
        public void render(UIContext context)
        {
            /* The press can be released outside this list, where BBS's release walk stops at the
             * first consuming sibling and this element never hears about it — the scroll then
             * stayed glued to the cursor. Poll the physical button, like the viewport does. */
            if (this.pressY != Integer.MIN_VALUE && !mchorse.bbs_mod.graphics.window.Window.isMouseButtonPressed(0))
            {
                this.pressY = Integer.MIN_VALUE;
                this.dragged = false;
            }

            if (this.pressY != Integer.MIN_VALUE)
            {
                int delta = this.pressY - context.mouseY;

                if (Math.abs(delta) > 4)
                {
                    this.dragged = true;
                    this.scrollY = this.pressScroll + delta;
                    this.clampScroll();
                }
            }

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 0xFF141920);
            context.batcher.clip(this.area, context);

            try
            {
                int first = Math.max(0, this.scrollY / ROW_H);
                int last = (this.scrollY + this.area.h) / ROW_H;

                for (int i = first; i <= last && i < this.rows.size(); i++)
                {
                    Entry entry = this.rows.get(i);
                    int y = this.area.y + i * ROW_H - this.scrollY;
                    boolean active = i == this.selected;

                    if (active)
                    {
                        context.batcher.box(this.area.x, y, this.area.ex(), y + ROW_H, 0xFF2F5C9E);
                    }

                    if (entry.folder)
                    {
                        context.batcher.icon(Icons.FOLDER, 0xFFD6B36A, this.area.x + 4, y + 3);
                        context.batcher.text(trim(entry.name, 44), this.area.x + 22, y + 4, active ? 0xFFFFFFFF : 0xFFD6DEEB);
                    }
                    else
                    {
                        context.batcher.icon(Icons.IMAGE, 0xFF7FA8D8, this.area.x + 4, y + 3);
                        context.batcher.text(trim(entry.name, 44), this.area.x + 22, y + 4, active ? 0xFFFFFFFF : 0xFFC3CCDA);
                        context.batcher.text(entry.detail, this.area.ex() - 52, y + 4, 0xFF6F7A8A);
                    }
                }

                if (this.rows.isEmpty())
                {
                    context.batcher.text("这个目录没有可导入的文件", this.area.x + 6, this.area.y + 6, 0xFF7C8798);
                }
            }
            finally
            {
                context.batcher.unclip(context);
            }

            this.renderScrollbar(context);
            super.render(context);
        }

        private String trim(String text, int length)
        {
            return text.length() <= length ? text : text.substring(0, length - 1) + "…";
        }

        private void renderScrollbar(UIContext context)
        {
            int content = this.contentHeight();

            if (content <= this.area.h || this.area.h <= 0)
            {
                return;
            }

            int barH = Math.max(18, Math.round(this.area.h * (this.area.h / (float) content)));
            int barY = this.area.y + Math.round((this.area.h - barH) * (this.scrollY / (float) Math.max(1, content - this.area.h)));

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

            int index = (context.mouseY - this.area.y + this.scrollY) / ROW_H;

            if (index >= 0 && index < this.rows.size())
            {
                long now = System.currentTimeMillis();

                this.selected = index;

                if (index == this.lastIndex && now - this.lastClick < 350 && !this.rows.get(index).folder)
                {
                    this.lastClick = now;

                    UIImportBrowser.this.confirm();

                    return true;
                }

                this.lastIndex = index;
                this.lastClick = now;
            }

            return true;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            if (this.pressY == Integer.MIN_VALUE)
            {
                return super.subMouseReleased(context);
            }

            boolean dragged = this.dragged;

            this.pressY = Integer.MIN_VALUE;
            this.dragged = false;

            /* A click (not a drag) on a folder enters it, which is what a file manager does. */
            if (!dragged && this.area.isInside(context))
            {
                Entry entry = this.selected();

                if (entry != null && entry.folder)
                {
                    UIImportBrowser.this.current = entry.path;
                    UIImportBrowser.this.reload();
                }
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

            this.scrollY -= (int) Math.round(context.mouseWheel * (ROW_H * 3));
            this.clampScroll();

            return true;
        }
    }
}
