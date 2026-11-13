package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.framework.elements.layout.ILayoutSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * BBS dock-layout storage for the compositor.
 *
 * <p>BBS 2.7 already ships a complete dockable-panel system ({@code UIDockLayout}: splitter drag,
 * drag-to-dock, tab stacks, hidden panels, layout lock, layout undo, maximize). The compositor
 * reuses it instead of inventing a second layout toolkit; this class is the only project-specific
 * part — it maps {@link ILayoutSource} onto {@link CompProject.CompSettings}.</p>
 *
 * <p>Two details matter:</p>
 * <ul>
 *   <li><b>the settings can change under us</b> (the compositor panel exists before a project is
 *       opened), so the settings object is resolved through a supplier and the cached tree is
 *       dropped when the project changes (or {@link #reload()} is called);</li>
 *   <li><b>writes are batched</b>: {@code UIDockLayout} calls {@link #setRoot} on every splitter
 *       frame, and encoding the tree / marking the project dirty at 60 Hz would allocate a string
 *       per frame and push every mouse move into the autosave/undo path. The tree is therefore
 *       cached in memory and written back once by {@link #flush()} (the panel calls it when the
 *       drag settles / on save).</li>
 * </ul>
 */
public final class CompDockSource implements ILayoutSource
{
    public static final String PANEL_EFFECTS = "effects";
    public static final String PANEL_PREVIEW = "preview";
    public static final String PANEL_INSPECTOR = "inspector";
    public static final String PANEL_TIMELINE = "timeline";

    /** Current editor-layout revision; see {@link CompProject.CompSettings#uiLayoutRevision}. */
    public static final int UI_LAYOUT_REVISION = 1;

    private final Supplier<CompProject.CompSettings> settingsSupplier;
    private final Supplier<EditorLayoutNode> defaults;
    private final Runnable onChange;

    private CompProject.CompSettings settings;
    private EditorLayoutNode cachedRoot;
    private boolean rootLoaded;
    private Set<String> cachedHidden;
    private boolean hiddenLoaded;
    private boolean dirty;
    /** True when getRoot() converted a pre-v7 layout and the new revision still needs persisting. */
    private boolean layoutRevisionUpgraded;
    /**
     * Focus mode is a session view, not a document edit: while suspended the tree is still cached
     * in memory (so the dock can be laid out) but {@link #flush()} never writes it into the
     * project, and dirty flags raised by the temporary tree are dropped.
     */
    private boolean persistSuspended;

    public CompDockSource(Supplier<CompProject.CompSettings> settingsSupplier,
                          Supplier<EditorLayoutNode> defaults, Runnable onChange)
    {
        this.settingsSupplier = settingsSupplier;
        this.defaults = defaults;
        this.onChange = onChange;
    }

    private CompProject.CompSettings settings()
    {
        CompProject.CompSettings resolved = this.settingsSupplier == null ? null : this.settingsSupplier.get();

        if (resolved != this.settings)
        {
            /* A different project: the old tree/hidden set belong to the previous file. */
            this.settings = resolved;
            this.rootLoaded = false;
            this.hiddenLoaded = false;
            this.cachedRoot = null;
            this.cachedHidden = null;
            this.dirty = false;
        }

        return resolved;
    }

    @Override
    public EditorLayoutNode getRoot()
    {
        CompProject.CompSettings settings = this.settings();

        if (!this.rootLoaded)
        {
            this.rootLoaded = true;

            EditorLayoutNode stored = parse(settings == null ? "" : settings.dockLayout);

            /* Cache the default tree too. BBS's UIDockLayout matches splitters by object identity
             * when it applies a drag (copyWithSplitterRatios keys a Map<SplitterNode, Float>), so
             * handing out a fresh defaults.get() on every call made the handle infos point at nodes
             * that were no longer in the tree: dragging a splitter did nothing (and the layout undo
             * snapshot pushed an unrelated tree). One cached root keeps every consumer on the same
             * objects until setRoot/reload replaces it. */
            this.cachedRoot = stored != null ? stored : (this.defaults == null ? null : this.defaults.get());

            /* v7: one-time conversion of the old effects-right default tree. Custom layouts are
             * left alone, but the revision still flips to 1 so this never runs for them again. */
            if (settings != null && settings.uiLayoutRevision < UI_LAYOUT_REVISION)
            {
                EditorLayoutNode migrated = migrateLegacyEffectsRight(this.cachedRoot);

                if (migrated != null && migrated != this.cachedRoot)
                {
                    this.cachedRoot = migrated;
                    this.dirty = true;
                }

                settings.uiLayoutRevision = UI_LAYOUT_REVISION;
                this.layoutRevisionUpgraded = true;
                this.dirty = true;
            }
        }

        return this.cachedRoot;
    }

    /**
     * Converts the 0.97–0.99 default tree (preview left; effects + inspector stacked in the right
     * column) into the effects-left tree. Returns the input unchanged for any other shape, including
     * a deliberate custom layout.
     */
    private static EditorLayoutNode migrateLegacyEffectsRight(EditorLayoutNode root)
    {
        if (!(root instanceof EditorLayoutNode.SplitterNode outer) || !outer.isHorizontal()
            || !isPanel(outer.getSecond(), PANEL_TIMELINE))
        {
            return root;
        }

        if (!(outer.getFirst() instanceof EditorLayoutNode.SplitterNode row) || row.isHorizontal()
            || !isPanel(row.getFirst(), PANEL_PREVIEW))
        {
            return root;
        }

        if (!(row.getSecond() instanceof EditorLayoutNode.SplitterNode right) || !right.isHorizontal()
            || !isPanel(right.getFirst(), PANEL_EFFECTS) || !isPanel(right.getSecond(), PANEL_INSPECTOR))
        {
            return root;
        }

        EditorLayoutNode editorRow = new EditorLayoutNode.SplitterNode(false, 0.19F,
            new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
            new EditorLayoutNode.SplitterNode(false, 0.62F,
                new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                new EditorLayoutNode.PanelNode(PANEL_INSPECTOR)));

        return new EditorLayoutNode.SplitterNode(true, outer.getRatio(), editorRow, outer.getSecond());
    }

    private static boolean isPanel(EditorLayoutNode node, String panelId)
    {
        return node instanceof EditorLayoutNode.PanelNode panel && panel.getPanelId().equals(panelId);
    }

    @Override
    public void setRoot(EditorLayoutNode root)
    {
        if (root == null)
        {
            return;
        }

        this.settings();
        this.cachedRoot = root;
        this.rootLoaded = true;
        this.dirty = true;
    }

    @Override
    public EditorLayoutNode getDefault()
    {
        return this.defaults.get();
    }

    @Override
    public Set<String> getHiddenPanels()
    {
        CompProject.CompSettings settings = this.settings();

        if (!this.hiddenLoaded)
        {
            this.hiddenLoaded = true;
            this.cachedHidden = new LinkedHashSet<>();

            if (settings != null && settings.dockHiddenPanels != null)
            {
                for (String part : settings.dockHiddenPanels.split(","))
                {
                    String id = part.trim();

                    if (!id.isEmpty())
                    {
                        this.cachedHidden.add(id);
                    }
                }
            }
        }

        return new LinkedHashSet<>(this.cachedHidden);
    }

    @Override
    public void setHiddenPanels(Set<String> hidden)
    {
        this.settings();
        this.cachedHidden = new LinkedHashSet<>();

        if (hidden != null)
        {
            for (String id : hidden)
            {
                if (id != null && !id.isBlank())
                {
                    this.cachedHidden.add(id.trim());
                }
            }
        }

        this.hiddenLoaded = true;
        this.dirty = true;
    }

    /** True while the cached layout has not been written back into the project yet. */
    public boolean isDirty()
    {
        return this.dirty;
    }

    /**
     * Suspends/resumes writing the cached tree into the project. Used by the transient focus mode:
     * its tree must never end up in the saved file (the user would reopen the project with the
     * side panels hidden).
     */
    public void suspendPersist(boolean suspended)
    {
        this.persistSuspended = suspended;

        if (suspended)
        {
            this.dirty = false;
        }
    }

    public boolean isPersistSuspended()
    {
        return this.persistSuspended;
    }

    /**
     * Writes the cached tree/hidden set into the project settings (once, not per drag frame) and
     * notifies the host so it can mark the UI state dirty without touching the document undo.
     */
    public void flush()
    {
        if (this.persistSuspended)
        {
            this.dirty = false;

            return;
        }

        CompProject.CompSettings settings = this.settings();

        if (!this.dirty || settings == null)
        {
            return;
        }

        this.dirty = false;

        String root = encode(this.getRoot());
        String hidden = join(this.cachedHidden == null ? Set.of() : this.cachedHidden);
        boolean revisionChanged = this.layoutRevisionUpgraded;

        this.layoutRevisionUpgraded = false;

        boolean changed = revisionChanged
            || !root.equals(settings.dockLayout == null ? "" : settings.dockLayout)
            || !hidden.equals(settings.dockHiddenPanels == null ? "" : settings.dockHiddenPanels);

        settings.dockLayout = root;
        settings.dockHiddenPanels = hidden;
        settings.uiLayoutRevision = UI_LAYOUT_REVISION;

        if (changed && this.onChange != null)
        {
            this.onChange.run();
        }
    }

    /** Re-reads both fields, e.g. after a project switch. */
    public void reload()
    {
        this.settings = null;
        this.rootLoaded = false;
        this.hiddenLoaded = false;
        this.cachedRoot = null;
        this.cachedHidden = null;
        this.dirty = false;
        this.layoutRevisionUpgraded = false;
    }

    private static String join(Set<String> ids)
    {
        StringBuilder builder = new StringBuilder();

        for (String id : ids)
        {
            if (builder.length() > 0)
            {
                builder.append(',');
            }

            builder.append(id);
        }

        return builder.toString();
    }

    /** Parses a stored tree; {@code null} when the string is empty/corrupt (callers use the default). */
    public static EditorLayoutNode parse(String encoded)
    {
        if (encoded == null || encoded.isBlank())
        {
            return null;
        }

        try
        {
            return EditorLayoutNode.fromData(DataToString.fromString(encoded));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Serializes a tree the way BBS does; empty on failure (the default is used next time). */
    public static String encode(EditorLayoutNode root)
    {
        if (root == null)
        {
            return "";
        }

        try
        {
            return DataToString.toString(root.toData());
        }
        catch (RuntimeException e)
        {
            return "";
        }
    }

    /**
     * Grows the outermost <b>vertical stacking</b> splitter on the way to {@code panelId} until the
     * panel has {@code requiredHeight} pixels, or returns {@code null} when the tree cannot help.
     *
     * <p>This exists because a dock panel's height is a ratio the user controls. The keyframe
     * drawer lives inside the timeline panel and needs a minimum number of pixels (header + a few
     * track rows + the drawer itself). On a 960×540 UI the default 72/28 split gives the timeline
     * only ~125 px, which is less than one drawer, so opening the drawer used to silently close it
     * again. A layout must make room for a panel that has a hard minimum; refusing to open is not a
     * display.</p>
     *
     * <p>Only the outer splitter is touched: changing an ancestor's ratio scales the whole branch,
     * including any nested splits, so the panel grows by the same factor. The sibling keeps at
     * least BBS' 80 px splitter minimum. The result is a new tree (the dock's splitter handles are
     * keyed by node identity, so the caller must apply it through {@link #setRoot}).</p>
     *
     * @param root            current dock tree
     * @param panelId         panel that needs the height
     * @param currentHeight   its current pixel height
     * @param requiredHeight  wanted pixel height
     * @param bodyHeight      pixel height of the dock itself (for the 80 px sibling minimum)
     */
    public static EditorLayoutNode growPanelHeight(EditorLayoutNode root, String panelId,
                                                   int currentHeight, int requiredHeight, int bodyHeight)
    {
        if (root == null || panelId == null || bodyHeight <= 0 || currentHeight <= 0
            || requiredHeight <= currentHeight)
        {
            return null;
        }

        List<PathSplit> path = new ArrayList<>();

        if (!findPanelPath(root, panelId, path) || path.isEmpty())
        {
            return null;
        }

        /* findPanelPath() appends while unwinding, so the list runs leaf -> root. Walking it in
         * reverse gives root -> leaf; the outermost vertical stacking splitter is the first
         * horizontal entry met on that walk. */
        float height = bodyHeight;
        PathSplit target = null;
        float targetShare = 0F;
        float targetHeight = 0F;

        for (int i = path.size() - 1; i >= 0; i--)
        {
            PathSplit entry = path.get(i);
            float ratio = entry.splitter().getRatio();

            if (entry.splitter().isHorizontal())
            {
                float share = entry.second() ? 1F - ratio : ratio;

                if (target == null)
                {
                    target = entry;
                    targetShare = share;
                    targetHeight = height;
                }

                height *= share;
            }
        }

        if (target == null || targetHeight <= 0F || targetShare <= 0.001F)
        {
            return null;
        }

        /* Grow by the factor that turns the panel's current content height into the requested one.
         * The branch controlled by the ratio is a little taller than the panel (dock gutters/tab
         * strip); using the branch height instead of the content height keeps the estimate honest
         * for small timelines where those few pixels are a large fraction. */
        float branchHeight = targetHeight * targetShare;

        if (branchHeight <= 0F)
        {
            return null;
        }

        float gutter = Math.max(0F, branchHeight - currentHeight);
        float scale = (requiredHeight + gutter) / branchHeight;
        float minShare = Math.max(EditorLayoutNode.MIN_RATIO, 80F / targetHeight);
        float maxShare = 1F - minShare;
        float newShare = Math.max(minShare, Math.min(maxShare, targetShare * scale));

        if (maxShare <= minShare || newShare <= targetShare + 0.0005F)
        {
            return null;
        }

        float newRatio = target.second() ? 1F - newShare : newShare;
        newRatio = Math.max(EditorLayoutNode.MIN_RATIO, Math.min(EditorLayoutNode.MAX_RATIO, newRatio));

        Map<EditorLayoutNode.SplitterNode, Float> ratios = new HashMap<>();

        ratios.put(target.splitter(), newRatio);

        EditorLayoutNode grown = EditorLayoutNode.copyWithSplitterRatios(root, ratios);

        return grown == root ? null : grown;
    }

    /** One splitter on the root -> panel path; {@code second} is true when the path uses child 2. */
    private record PathSplit(EditorLayoutNode.SplitterNode splitter, boolean second)
    {}

    private static boolean findPanelPath(EditorLayoutNode node, String panelId, List<PathSplit> out)
    {
        if (node == null)
        {
            return false;
        }

        if (node instanceof EditorLayoutNode.PanelNode panel)
        {
            return panel.getPanelId().equals(panelId);
        }

        if (node instanceof EditorLayoutNode.StackNode stack)
        {
            return stack.getPanelIds().contains(panelId);
        }

        if (node instanceof EditorLayoutNode.SplitterNode splitter)
        {
            if (findPanelPath(splitter.getFirst(), panelId, out))
            {
                out.add(new PathSplit(splitter, false));

                return true;
            }

            if (findPanelPath(splitter.getSecond(), panelId, out))
            {
                out.add(new PathSplit(splitter, true));

                return true;
            }
        }

        return false;
    }

    /**
     * The default compositor tree: effects/assets column on the <b>left</b>, preview in the
     * middle, inspector on the right, timeline below.
     *
     * <p>This is the editing flow the UI is built around: browse sources on the left, see the
     * result in the middle, edit the selected layer's own effects on the right. The old default
     * put preview first and stacked effects + inspector into a narrow right column; that made the
     * preview look like the whole editor and forced the effect stack into a page that also held
     * transform/opacity, which is exactly the crowding the effects tab now removes.</p>
     *
     * <p>BBS's {@code SplitterNode(horizontal, …)} splits along the horizontal axis when the flag is
     * true, i.e. the two children are stacked <b>vertically</b> (first above second). The outer split
     * therefore stacks the editor row above the timeline; the inner ones are side-by-side.</p>
     */
    public static EditorLayoutNode defaultLayout()
    {
        return new EditorLayoutNode.SplitterNode(true, 0.72F,
            new EditorLayoutNode.SplitterNode(false, 0.19F,
                new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                new EditorLayoutNode.SplitterNode(false, 0.62F,
                    new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                    new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
            new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
    }

    /** Pre-dock layout: effects column on the left, preview + inspector on the right. */
    public static EditorLayoutNode classicLayout()
    {
        return defaultLayout();
    }

    /** Focus mode: preview over the timeline, side panels hidden (transient, not persisted). */
    public static EditorLayoutNode focusLayout()
    {
        return new EditorLayoutNode.SplitterNode(true, 0.84F,
            new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
            new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
    }

    /**
     * Optional layout: effect library and inspector stacked to the **right** of the preview.
     *
     * <p>This was the 0.97–0.99 default ("效果列表在预览右侧"). It stays available from the layout
     * menu for users who prefer it, but it is no longer the default: the effect library is a source
     * browser and belongs on the left of the editing flow.</p>
     */
    public static EditorLayoutNode effectsRightLayout()
    {
        return new EditorLayoutNode.SplitterNode(true, 0.72F,
            new EditorLayoutNode.SplitterNode(false, 0.72F,
                new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                new EditorLayoutNode.SplitterNode(true, 0.5F,
                    new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                    new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
            new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
    }

    /**
     * Workspace presets mapped onto dock trees so "界面布局" keeps working in dock mode.
     *
     * <p>Standard / focal / compact / wide mirror the four onboarding cards: the ratios change, the
     * panels stay the same. The density tier itself is still applied by the panel.</p>
     */
    public static EditorLayoutNode presetLayout(int preset)
    {
        return switch (preset)
        {
            case 1 -> focusLayout();
            case 2 -> new EditorLayoutNode.SplitterNode(true, 0.70F,
                new EditorLayoutNode.SplitterNode(false, 0.17F,
                    new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                    new EditorLayoutNode.SplitterNode(false, 0.64F,
                        new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                        new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
                new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
            case 3 -> new EditorLayoutNode.SplitterNode(true, 0.74F,
                new EditorLayoutNode.SplitterNode(false, 0.21F,
                    new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                    new EditorLayoutNode.SplitterNode(false, 0.64F,
                        new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                        new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
                new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
            default -> defaultLayout();
        };
    }
}
