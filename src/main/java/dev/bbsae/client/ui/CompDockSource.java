package dev.bbsae.client.ui;

import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.framework.elements.layout.ILayoutSource;

import java.util.LinkedHashSet;
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

    private final Supplier<CompProject.CompSettings> settingsSupplier;
    private final Supplier<EditorLayoutNode> defaults;
    private final Runnable onChange;

    private CompProject.CompSettings settings;
    private EditorLayoutNode cachedRoot;
    private boolean rootLoaded;
    private Set<String> cachedHidden;
    private boolean hiddenLoaded;
    private boolean dirty;
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
            this.cachedRoot = parse(settings == null ? "" : settings.dockLayout);
        }

        return this.cachedRoot != null ? this.cachedRoot : this.defaults.get();
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

        boolean changed = !root.equals(settings.dockLayout == null ? "" : settings.dockLayout)
            || !hidden.equals(settings.dockHiddenPanels == null ? "" : settings.dockHiddenPanels);

        settings.dockLayout = root;
        settings.dockHiddenPanels = hidden;

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
     * The default compositor tree: effects column on the left, preview over the timeline in the
     * middle, inspector on the right — the layout the fixed splitter system had, expressed as a
     * dock tree so the user can rearrange it.
     *
     * <p>BBS's {@code SplitterNode(horizontal, …)} splits along the horizontal axis when the flag is
     * true, i.e. the two children are stacked <b>vertically</b> (first above second). The outer split
     * therefore stacks the editor row above the timeline; the inner ones are side-by-side.</p>
     */
    public static EditorLayoutNode defaultLayout()
    {
        return new EditorLayoutNode.SplitterNode(true, 0.72F,
            new EditorLayoutNode.SplitterNode(false, 0.13F,
                new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                new EditorLayoutNode.SplitterNode(false, 0.80F,
                    new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                    new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
            new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
    }

    /** Focus mode: preview over the timeline, side panels hidden (transient, not persisted). */
    public static EditorLayoutNode focusLayout()
    {
        return new EditorLayoutNode.SplitterNode(true, 0.84F,
            new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
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
                new EditorLayoutNode.SplitterNode(false, 0.11F,
                    new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                    new EditorLayoutNode.SplitterNode(false, 0.78F,
                        new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                        new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
                new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
            case 3 -> new EditorLayoutNode.SplitterNode(true, 0.74F,
                new EditorLayoutNode.SplitterNode(false, 0.15F,
                    new EditorLayoutNode.PanelNode(PANEL_EFFECTS),
                    new EditorLayoutNode.SplitterNode(false, 0.82F,
                        new EditorLayoutNode.PanelNode(PANEL_PREVIEW),
                        new EditorLayoutNode.PanelNode(PANEL_INSPECTOR))),
                new EditorLayoutNode.PanelNode(PANEL_TIMELINE));
            default -> defaultLayout();
        };
    }
}
