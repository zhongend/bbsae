package dev.bbsae.client.ui;

import dev.bbsae.client.render.ImageTextureCache;
import dev.bbsae.comp.CompMediaBin;
import dev.bbsae.comp.CompMediaLibrary;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Premiere-style project panel: bins on top, media thumbnails below, drag to the timeline.
 *
 * <h2>The three Premiere behaviours this implements</h2>
 * <ol>
 *   <li><b>Import once, use many.</b> Media lives in the project panel; the timeline only holds a
 *       reference. A clip dropped on the timeline never copies the underlying file again.</li>
 *   <li><b>Drag is an edit.</b> Dropping on an empty track creates a clip at the pointer's time and
 *       row; dropping on an existing clip replaces its source (overwrite edit) while keeping its
 *       transform, effects and keyframes.</li>
 *   <li><b>Bins organise, never delete.</b> Deleting a bin moves its items to the default bin; the
 *       files are untouched.</li>
 * </ol>
 *
 * <p>All of it is drawn with BBS' own batcher (no HTML, no MCEF). The panel owns only presentation
 * and hit testing; the actual insert/replace operation lives in {@link UICompositorPanel} so the
 * timeline, the OS drop path and this panel share one implementation.</p>
 */
public class UIProjectBin extends UIElement
{
    private static final int HEADER = 22;
    private static final int SEARCH_H = 16;
    private static final int ROW_H = 15;
    private static final int TILE_MIN_W = 70;
    private static final int TILE_H = 58;
    private static final int TILE_GAP = 5;
    private static final int PAD = 4;

    private final UICompositorPanel panel;
    private final UITextbox search;

    private final List<CompMediaLibrary.Item> items = new ArrayList<>();
    private final List<CompMediaLibrary.Item> filtered = new ArrayList<>();

    private CompProject scannedProject;
    private int scannedRevision = -1;
    private String selectedPath = "";
    private String selectedBinId = "";
    private int binScroll;
    private int gridScroll;
    private int hoverIndex = -1;
    private int pressIndex = -1;
    private int pressX;
    private int pressY;
    private boolean pressedBin;
    private boolean dragging;
    private long lastClick;
    private int lastIndex = -1;
    private int lastBinClick = -1;
    private long lastBinClickTime;

    public UIProjectBin(UICompositorPanel panel)
    {
        this.panel = panel;

        this.search = new UITextbox(256, (text) -> this.applyFilter());
        this.search.placeholder(IKey.constant("搜索素材…"));
        this.search.relative(this).x(PAD).y(HEADER + 2).w(1F, -PAD * 2).h(SEARCH_H);

        this.add(this.search);
    }

    /* --------------------------------------------------------------- data */

    public void refresh()
    {
        CompProject project = this.panel.getProject();

        this.scannedProject = project;
        this.items.clear();

        if (project != null)
        {
            this.items.addAll(CompMediaLibrary.scan(project));

            if (this.selectedBinId.isBlank() || CompMediaLibrary.findBin(project, this.selectedBinId) == null)
            {
                this.selectedBinId = CompMediaLibrary.defaultBinId(project);
            }
        }

        this.applyFilter();
    }

    private void applyFilter()
    {
        this.filtered.clear();

        CompProject project = this.panel.getProject();

        if (project == null)
        {
            return;
        }

        String query = this.search.getText() == null ? "" : this.search.getText().trim();
        List<CompMediaLibrary.Item> pool = query.isEmpty()
            ? CompMediaLibrary.inBin(this.items, this.selectedBinId)
            : CompMediaLibrary.search(this.items, query);

        this.filtered.addAll(pool);

        if (!this.selectedPath.isBlank())
        {
            boolean found = false;

            for (CompMediaLibrary.Item item : this.filtered)
            {
                if (item.path.equals(this.selectedPath))
                {
                    found = true;
                    break;
                }
            }

            if (!found)
            {
                this.selectedPath = "";
            }
        }

        this.gridScroll = 0;
    }

    public CompMediaLibrary.Item selected()
    {
        for (CompMediaLibrary.Item item : this.filtered)
        {
            if (item.path.equals(this.selectedPath))
            {
                return item;
            }
        }

        return null;
    }

    /** Called by the panel after an import so the new asset is visible, not "somewhere". */
    public void selectPath(String token)
    {
        if (token == null || token.isBlank())
        {
            return;
        }

        CompProject project = this.panel.getProject();

        if (project == null)
        {
            return;
        }

        for (CompMediaLibrary.Item item : this.items)
        {
            if (item.path.equals(token) || item.path.endsWith("/" + token))
            {
                this.selectedPath = item.path;
                this.selectedBinId = item.binId;
                this.search.setText("");
                this.applyFilter();
                this.reveal(item);

                return;
            }
        }
    }

    public void markChanged()
    {
        this.scannedRevision++;
    }

    /* --------------------------------------------------------------- layout */

    private int binsHeight()
    {
        CompProject project = this.panel.getProject();
        int count = project == null ? 0 : CompMediaLibrary.bins(project).size();

        return Math.min(5, Math.max(1, count)) * ROW_H;
    }

    private int gridTop()
    {
        return HEADER + SEARCH_H + 4 + this.binsHeight() + 6;
    }

    private int columns()
    {
        int inner = Math.max(1, this.area.w - PAD * 2);
        int columns = Math.max(1, (inner + TILE_GAP) / (TILE_MIN_W + TILE_GAP));

        return Math.min(4, columns);
    }

    private int tileWidth()
    {
        int inner = Math.max(1, this.area.w - PAD * 2);
        int columns = this.columns();

        return Math.max(24, (inner - TILE_GAP * (columns - 1)) / columns);
    }

    private int contentHeight()
    {
        int columns = this.columns();
        int rows = (this.filtered.size() + columns - 1) / columns;

        return rows * (TILE_H + TILE_GAP);
    }

    private int maxScroll()
    {
        return Math.max(0, this.contentHeight() - Math.max(1, this.area.ey() - this.gridTop() - 14));
    }

    @Override
    public void render(UIContext context)
    {
        CompProject project = this.panel.getProject();

        if (project != null && project != this.scannedProject)
        {
            this.refresh();
        }

        this.layout();
        this.pollDrag(context);
        this.hoverIndex = this.itemAt(context.mouseX, context.mouseY);

        this.drawBase(context, project);
        this.drawHeader(context, project);
        this.drawBins(context, project);
        this.drawGrid(context, project);
        this.drawFooter(context);

        super.render(context);
    }

    private void layout()
    {
        this.search.relative(this).x(PAD).y(HEADER + 2).w(1F, -PAD * 2).h(SEARCH_H);
        this.search.resize();

        CompProject project = this.panel.getProject();
        int count = project == null ? 0 : CompMediaLibrary.bins(project).size();

        this.binScroll = Math.max(0, Math.min(Math.max(0, count - 5), this.binScroll));
        this.gridScroll = Math.max(0, Math.min(this.gridScroll, this.maxScroll()));
    }

    private void drawBase(UIContext context, CompProject project)
    {
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.baseSurface());
        context.batcher.box(this.area.x, this.area.y, this.area.x + 2, this.area.ey(), BBSSettings.primaryColor());
    }

    private void drawHeader(UIContext context, CompProject project)
    {
        int y = this.area.y + 3;
        int right = this.area.ex() - PAD;

        context.batcher.text("素材箱", this.area.x + 6, y + 5, 0xFFE8EEF8);

        /* Four small icon buttons, right-aligned: import, new bin, library, refresh. */
        int[] xs = this.headerButtonXs();

        context.batcher.icon(Icons.DOWNLOAD, 0xFF8FD0FF, xs[0], y + 4);
        context.batcher.icon(Icons.FOLDER, 0xFFD6B36A, xs[1], y + 4);
        context.batcher.icon(Icons.GALLERY, 0xFFB8C4D6, xs[2], y + 4);
        context.batcher.icon(Icons.REFRESH, 0xFF8A94A6, xs[3], y + 4);
    }

    private int[] headerButtonXs()
    {
        int right = this.area.ex() - PAD - 14;

        return new int[] {right - 42, right - 28, right - 14, right};
    }

    private void drawBins(UIContext context, CompProject project)
    {
        int top = this.area.y + HEADER + SEARCH_H + 4;
        int left = this.area.x + PAD;
        int right = this.area.ex() - PAD;

        context.batcher.box(left, top, right, top + this.binsHeight(), 0x22000000);

        if (project == null)
        {
            return;
        }

        List<CompMediaBin> bins = CompMediaLibrary.bins(project);

        for (int i = 0; i < bins.size(); i++)
        {
            CompMediaBin bin = bins.get(i);
            int row = i - this.binScroll;

            if (row < 0)
            {
                continue;
            }

            int y = top + row * ROW_H;

            if (y + ROW_H > top + this.binsHeight())
            {
                break;
            }

            boolean selected = bin.id.equals(this.selectedBinId);
            int count = 0;

            for (CompMediaLibrary.Item item : this.items)
            {
                if (bin.id.equals(item.binId))
                {
                    count++;
                }
            }

            if (selected)
            {
                context.batcher.box(left, y, right, y + ROW_H, BBSSettings.primaryColor() & 0x66FFFFFF);
            }

            context.batcher.icon(bin.id.equals(CompMediaLibrary.DEFAULT_BIN_ID) ? Icons.FOLDER : Icons.BOOKMARK,
                selected ? Colors.WHITE : 0xFFD6B36A, left + 2, y + 3);
            context.batcher.text(bin.name, left + 18, y + 4, selected ? Colors.WHITE : 0xFFD6DEEB);
            context.batcher.text(String.valueOf(count), right - 12, y + 4, 0xFF8A94A6);
        }
    }

    private void drawGrid(UIContext context, CompProject project)
    {
        int top = this.area.y + this.gridTop();
        int bottom = this.area.ey() - 14;
        int left = this.area.x + PAD;
        int columns = this.columns();
        int tileW = this.tileWidth();

        if (top >= bottom)
        {
            return;
        }

        if (project == null)
        {
            context.batcher.text("打开工程后显示素材", left, top + 6, 0xFF7C8798);

            return;
        }

        context.batcher.clip(this.area.x, top, this.area.ex(), bottom, context);

        try
        {
            if (this.filtered.isEmpty())
            {
                context.batcher.text(project == null ? "打开工程后显示素材" : "这个素材箱是空的",
                    left, top + 6, 0xFF7C8798);
                context.batcher.text("点右上角 ↓ 导入图片 / 视频 / 音频", left, top + 20, 0xFF6F7A8A);
            }

            for (int index = 0; index < this.filtered.size(); index++)
            {
                CompMediaLibrary.Item item = this.filtered.get(index);
                int column = index % columns;
                int row = index / columns;
                int x = left + column * (tileW + TILE_GAP);
                int y = top + row * (TILE_H + TILE_GAP) - this.gridScroll;

                if (y + TILE_H < top || y > bottom)
                {
                    continue;
                }

                this.drawTile(context, project, item, index, x, y, tileW);
            }
        }
        finally
        {
            context.batcher.unclip(context);
        }

        this.drawScrollbar(context, top, bottom);
    }

    private void drawTile(UIContext context, CompProject project, CompMediaLibrary.Item item,
                          int index, int x, int y, int tileW)
    {
        boolean selected = item.path.equals(this.selectedPath);
        boolean hover = index == this.hoverIndex;
        int border = selected ? BBSSettings.primaryColor() : hover ? 0xFF4A5A70 : 0xFF2A3340;

        context.batcher.box(x, y, x + tileW, y + TILE_H, selected ? 0xFF243447 : 0xFF1B2230);
        context.batcher.outline(x, y, x + tileW, y + TILE_H, border);

        int thumbH = 30;
        int thumbX = x + 3;
        int thumbY = y + 3;
        int thumbW = tileW - 6;
        boolean drawn = false;

        if (item.kind == CompMediaLibrary.Kind.IMAGE || item.kind == CompMediaLibrary.Kind.SEQUENCE)
        {
            String path = item.path;

            if (item.kind == CompMediaLibrary.Kind.SEQUENCE)
            {
                path = CompMediaLibrary.sequenceFrame(item, 0);
            }

            ImageTextureCache.Entry texture = ImageTextureCache.get(project.id, path);

            if (texture != null)
            {
                context.batcher.clip(x + 3, y + 3, x + tileW - 3, y + 3 + thumbH, context);

                try
                {
                    context.batcher.texturedBox(texture.texture.id, 0xFFFFFFFF, thumbX, thumbY,
                        thumbW, thumbH, 0F, 0F, texture.width, texture.height, texture.width, texture.height);
                }
                finally
                {
                    context.batcher.unclip(context);
                }

                drawn = true;
            }
        }

        if (!drawn)
        {
            var icon = item.kind == CompMediaLibrary.Kind.AUDIO ? Icons.SOUND
                : item.kind == CompMediaLibrary.Kind.VIDEO ? Icons.FILM
                : item.kind == CompMediaLibrary.Kind.SEQUENCE ? Icons.FILM
                : Icons.IMAGE;

            context.batcher.icon(icon, item.offline ? 0xFF806060 : 0xFF8FD0FF,
                x + tileW / 2F - 8, y + 3 + thumbH / 2F - 8);
        }

        if (item.offline)
        {
            context.batcher.box(x + 3, y + 3, x + tileW - 3, y + 3 + thumbH, 0x44FF0000);
            context.batcher.icon(Icons.EXCLAMATION, 0xFFFF8080, x + tileW - 18, y + 3 + thumbH - 18);
        }

        String label = item.name;

        if (label.length() > Math.max(6, tileW / 6))
        {
            label = label.substring(0, Math.max(5, tileW / 6 - 1)) + "…";
        }

        context.batcher.text(label, x + 4, y + TILE_H - 20, item.offline ? 0xFFB08A8A : 0xFFD6DEEB);
        context.batcher.text(item.kind == CompMediaLibrary.Kind.SEQUENCE ? "序列"
            : CompMediaLibrary.kindLabel(item.kind) + (item.external ? " · 外链" : ""),
            x + 4, y + TILE_H - 10, 0xFF7C8798);
    }

    private void drawFooter(UIContext context)
    {
        int y = this.area.ey() - 11;

        context.batcher.box(this.area.x, this.area.ey() - 13, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());
        context.batcher.text("拖动 / 双击 · 拖到轨道替换", this.area.x + 4, y - 3, 0xFF7C8798);
    }

    private void drawScrollbar(UIContext context, int top, int bottom)
    {
        int content = this.contentHeight();
        int view = bottom - top;

        if (content <= view || view <= 0)
        {
            return;
        }

        int x = this.area.ex() - 5;
        int height = Math.max(14, Math.round(view * (view / (float) content)));
        int y = top + Math.round((view - height) * (this.gridScroll / (float) Math.max(1, content - view)));

        context.batcher.box(x, top, x + 3, bottom, 0x22000000);
        context.batcher.box(x, y, x + 3, y + height, 0x99FFFFFF);    }

    /* --------------------------------------------------------------- hit test */

    private int itemAt(int mouseX, int mouseY)
    {
        if (mouseX < this.area.x + PAD || mouseX >= this.area.ex() - PAD)
        {
            return -1;
        }

        int top = this.area.y + this.gridTop();
        int bottom = this.area.ey() - 14;

        if (mouseY < top || mouseY >= bottom)
        {
            return -1;
        }

        int columns = this.columns();
        int tileW = this.tileWidth();
        int localX = mouseX - (this.area.x + PAD);
        int localY = mouseY - top + this.gridScroll;
        int column = localX / Math.max(1, tileW + TILE_GAP);
        int row = localY / (TILE_H + TILE_GAP);

        if (column < 0 || column >= columns || localX - column * (tileW + TILE_GAP) >= tileW)
        {
            return -1;
        }

        int index = row * columns + column;

        return index >= 0 && index < this.filtered.size() ? index : -1;
    }

    /** Bin row under a screen point, or -1. Public for the panel's drag-to-bin drop. */
    public int binAtScreen(int mouseX, int mouseY)
    {
        return this.binAt(mouseX, mouseY);
    }

    private int binAt(int mouseX, int mouseY)
    {        CompProject project = this.panel.getProject();

        if (project == null)
        {
            return -1;
        }

        int top = this.area.y + HEADER + SEARCH_H + 4;

        if (mouseX < this.area.x + PAD || mouseX >= this.area.ex() - PAD
            || mouseY < top || mouseY >= top + this.binsHeight())
        {
            return -1;
        }

        int index = (mouseY - top) / ROW_H + this.binScroll;

        return index >= 0 && index < CompMediaLibrary.bins(project).size() ? index : -1;
    }

    private int headerAt(int mouseX, int mouseY)
    {
        if (mouseY < this.area.y || mouseY >= this.area.y + HEADER)
        {
            return -1;
        }

        int[] xs = this.headerButtonXs();

        for (int i = 0; i < xs.length; i++)
        {
            if (mouseX >= xs[i] - 2 && mouseX < xs[i] + 16)
            {
                return i;
            }
        }

        return -1;
    }

    private void reveal(CompMediaLibrary.Item item)
    {
        int index = this.filtered.indexOf(item);

        if (index < 0)
        {
            return;
        }

        int row = index / Math.max(1, this.columns());
        int y = row * (TILE_H + TILE_GAP);
        int view = Math.max(1, this.area.ey() - this.gridTop() - 14);

        if (y < this.gridScroll)
        {
            this.gridScroll = y;
        }
        else if (y + TILE_H > this.gridScroll + view)
        {
            this.gridScroll = y + TILE_H - view;
        }

        this.gridScroll = Math.max(0, Math.min(this.gridScroll, this.maxScroll()));
    }

    /* --------------------------------------------------------------- input */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton != 0 && context.mouseButton != 1)
        {
            return super.subMouseClicked(context);
        }

        int header = this.headerAt(context.mouseX, context.mouseY);

        if (context.mouseButton == 0 && header >= 0)
        {
            switch (header)
            {
                case 0 -> this.panel.importMediaDialog();
                case 1 -> this.promptNewBin();
                case 2 -> this.panel.openMediaLibrary();
                case 3 -> this.refresh();
            }

            return true;
        }

        int bin = this.binAt(context.mouseX, context.mouseY);

        if (bin >= 0)
        {
            CompProject project = this.panel.getProject();

            if (project == null)
            {
                return true;
            }

            List<CompMediaBin> bins = CompMediaLibrary.bins(project);
            CompMediaBin hit = bins.get(bin);
            long now = System.currentTimeMillis();

            if (context.mouseButton == 1)
            {
                this.openBinMenu(hit);

                return true;
            }

            this.selectedBinId = hit.id;
            this.applyFilter();

            if (bin == this.lastBinClick && now - this.lastBinClickTime < 350)
            {
                this.promptRenameBin(hit);
            }

            this.lastBinClick = bin;
            this.lastBinClickTime = now;

            return true;
        }

        int index = this.itemAt(context.mouseX, context.mouseY);

        if (index >= 0)
        {
            CompMediaLibrary.Item item = this.filtered.get(index);
            long now = System.currentTimeMillis();

            this.selectedPath = item.path;
            this.pressIndex = index;
            this.pressX = context.mouseX;
            this.pressY = context.mouseY;
            this.dragging = false;
            this.pressedBin = false;

            if (context.mouseButton == 1)
            {
                this.panel.openMediaMenu(item);
                this.pressIndex = -1;

                return true;
            }

            if (index == this.lastIndex && now - this.lastClick < 350)
            {
                this.panel.insertMediaAt(item, this.panel.getTime(), -1);
                this.pressIndex = -1;
            }

            this.lastIndex = index;
            this.lastClick = now;

            return true;
        }

        this.pressIndex = -1;

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        boolean wasDragging = this.dragging || this.panel.isMediaDragging();

        this.pressIndex = -1;
        this.dragging = false;

        return wasDragging || super.subMouseReleased(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            return super.subMouseScrolled(context);
        }

        if (context.mouseY < this.area.y + this.gridTop())
        {
            CompProject project = this.panel.getProject();
            int count = project == null ? 0 : CompMediaLibrary.bins(project).size();
            int max = Math.max(0, count - 5);

            this.binScroll = Math.max(0, Math.min(max, this.binScroll - (int) Math.signum(context.mouseWheel)));
        }
        else
        {
            this.gridScroll = Math.max(0, Math.min(this.maxScroll(),
                this.gridScroll - (int) Math.round(context.mouseWheel * (TILE_H * 2))));
        }

        return true;
    }

    /**
     * Once the pointer leaves the tile, the panel owns the drag.
     *
     * <p>A press is not a drag: double-click insertion must win until the pointer actually moves.
     * The panel polls the physical button so releasing over the timeline still resolves the drop,
     * even though the mouse release event is delivered to whichever element is under the pointer.</p>
     */
    private void pollDrag(UIContext context)
    {
        if (this.pressIndex < 0 || this.pressIndex >= this.filtered.size())
        {
            return;
        }

        if (!Window.isMouseButtonPressed(0))
        {
            this.pressIndex = -1;

            return;
        }

        if (this.dragging)
        {
            return;
        }

        if (Math.abs(context.mouseX - this.pressX) > 4 || Math.abs(context.mouseY - this.pressY) > 4)
        {
            this.dragging = true;
            this.panel.beginMediaDrag(this.filtered.get(this.pressIndex), context.mouseX, context.mouseY);
        }
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (this.selectedPath.isBlank())
        {
            return super.subKeyPressed(context);
        }

        CompMediaLibrary.Item item = this.selected();

        if (item != null && context.getKeyAction() == mchorse.bbs_mod.ui.utils.keys.KeyAction.PRESSED
            && (context.getKeyCode() == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                || context.getKeyCode() == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE))
        {
            this.panel.removeMedia(item);
            this.refresh();

            return true;
        }

        return super.subKeyPressed(context);
    }

    /* --------------------------------------------------------------- bin actions */

    private void promptNewBin()
    {
        CompProject project = this.panel.getProject();

        if (project == null)
        {
            return;
        }

        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
            IKey.constant("新建素材箱"),
            IKey.constant("输入素材箱名称"),
            (name) ->
            {
                if (name == null || name.isBlank())
                {
                    return;
                }

                CompMediaBin bin = CompMediaLibrary.addBin(project, name);

                this.selectedBinId = bin.id;
                this.refresh();
                this.panel.save();
            });

        UIOverlay.addOverlay(this.getContext(), panel, 0.4F, 0.5F);
    }

    private void promptRenameBin(CompMediaBin bin)
    {
        CompProject project = this.panel.getProject();

        if (project == null || bin == null)
        {
            return;
        }

        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
            IKey.constant("重命名素材箱"),
            IKey.constant("素材箱名称"),
            (name) ->
            {
                if (CompMediaLibrary.renameBin(project, bin.id, name))
                {
                    this.refresh();
                    this.panel.save();
                }
            });

        panel.text.setText(bin.name);
        UIOverlay.addOverlay(this.getContext(), panel, 0.4F, 0.5F);
    }

    private void openBinMenu(CompMediaBin bin)
    {
        CompProject project = this.panel.getProject();

        if (project == null || bin == null)
        {
            return;
        }

        this.getContext().replaceContextMenu((manager) ->
        {
            manager.action(IKey.constant("重命名…"), () -> this.promptRenameBin(bin));

            if (!CompMediaLibrary.DEFAULT_BIN_ID.equals(bin.id))
            {
                manager.action(IKey.constant("删除素材箱（素材保留）"), () ->
                {
                    CompMediaLibrary.removeBin(project, bin.id);
                    this.selectedBinId = CompMediaLibrary.defaultBinId(project);
                    this.refresh();
                    this.panel.save();
                });
            }

            manager.action(IKey.constant("新建素材箱…"), this::promptNewBin);
        });
    }
}
