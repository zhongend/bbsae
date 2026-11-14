package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * A compositor project: one file that can hold many compositions and output presets.
 *
 * <p>The format is deliberately plain data so that it can be edited, diffed and migrated. The
 * editor and renderer use BBS's keyframe channels for animation, but the file itself is not tied
 * to BBS's value system. A film only stores a small reference (project id + comp id) in a camera
 * clip, never the whole project.</p>
 */
public class CompProject
{
    public static final String FORMAT = "bbsae";

    /**
     * Render/encode pipeline.
     *
     * <p>{@link #ENCODER_BBS} — the frame is produced by BBS: the composition is an overlay on the
     * film frame, the world is rendered by the game, and the film is recorded by BBS's own FFmpeg
     * recorder (F6). Frame timing, resolution and duration belong to BBS.</p>
     *
     * <p>{@link #ENCODER_AE} — the frame is produced by the compositor: a deterministic frame
     * server renders frame N at {@code N / comp.fps} at composition resolution, the preview reads
     * that same frame, and the export hands those exact frames to FFmpeg. Frame timing, resolution
     * and duration belong to the composition.</p>
     */
    /**
     * True when any comp in this project needs the live BBS world frame: a "bbs" preview layer or a
     * "replay" entity-key layer is produced by the tick-driven BBS renderer, which the AE frame
     * renderer cannot reproduce. Such projects must stay on the BBS encoder.
     */
    public boolean needsBbsWorldRender()
    {
        if (this.comps == null)
        {
            return false;
        }

        for (CompDocument comp : this.comps)
        {
            if (comp == null || comp.layers == null)
            {
                continue;
            }

            for (CompLayer layer : comp.layers)
            {
                if (layer != null && ("bbs".equals(layer.type) || layer.isEntityLayer()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** True when the file was written by a newer build, so saving it would silently downgrade it. */
    public boolean isFutureVersion()
    {
        return this.version > VERSION;
    }

    public static final String ENCODER_BBS = "bbs";
    public static final String ENCODER_AE = "ae";
    /**
     * 3 — added {@link #presets} (layer/effect/style/shape presets). Older files load unchanged:
     * the list is simply created empty by the migration.
     *
     * 4 — the composition stage colour became opaque. Files written by version 3 and older had a
     * fully transparent stage, which is why their preview looked like an empty panel; the migration
     * converts those once, then the user's own choice wins.
     *
     * 5 — the preview background became a solid colour. Version 4 files were migrated to the live
     * BBS world behind a transparent stage; that is also transparent, so version 5 turns it off
     * once. The "BBS 实时画面" toggle still exists, it just is not the default any more.
     *
     * 6 — 3D logic anchor. Per-layer 3D + the legacy toggle arrived without a version bump, so old
     * files cannot be told apart from v5 files authored with either logic. Version 6 records
     * {@link CompSettings#logicVersion} once (derived from the legacy toggle for older files); no
     * visual default changes, it only gives future 3D-logic migrations a stable anchor.
     *
     * 7 — editor layout revision. The compositor moved the source browser (effects/assets/presets)
     * from the right of the preview to the left, and split the old "effect controls" page into
     * 图层 + 效果. Version 7 lets {@link CompProject.CompSettings#uiLayoutRevision} distinguish an
     * old saved effects-right tree (one-time conversion) from a user who later chooses that layout
     * from the menu (must stay chosen).
     */
    public static final int VERSION = 7;

    public String format = FORMAT;
    public int version = VERSION;
    public String id = "demo";
    public String name = "Demo Project";

    public List<CompDocument> comps = new ArrayList<>();
    public List<CompOutputPreset> outputPresets = new ArrayList<>();
    public List<CompAnimationPreset> animationPresets = new ArrayList<>();
    public List<CompPreset> presets = new ArrayList<>();
    public List<CompRenderJob> renderQueue = new ArrayList<>();
    public CompSettings settings = new CompSettings();

    public CompDocument getComp(String compId)
    {
        if (this.comps == null)
        {
            return null;
        }

        if (compId == null || compId.isEmpty())
        {
            for (CompDocument comp : this.comps)
            {
                if (comp != null)
                {
                    return comp;
                }
            }

            return null;
        }

        for (CompDocument comp : this.comps)
        {
            if (comp != null && compId.equals(comp.id))
            {
                return comp;
            }
        }

        return null;
    }

    public CompDocument getCompOrFirst(String compId)
    {
        CompDocument comp = this.getComp(compId);

        return comp != null ? comp : this.getComp(null);
    }

    public CompDocument addComp(String baseName, int width, int height, float fps, float duration)
    {
        if (this.comps == null)
        {
            this.comps = new ArrayList<>();
        }

        String id = CompStore.uniqueId(baseName, this.comps, (comp) -> comp == null ? "" : comp.id);
        CompDocument comp = new CompDocument();

        comp.id = id;
        comp.name = baseName;
        comp.width = Math.max(1, width);
        comp.height = Math.max(1, height);
        comp.fps = Math.max(1F, fps);
        comp.duration = Math.max(0.1F, duration);
        this.comps.add(comp);

        return comp;
    }

    public CompOutputPreset getPreset(String presetId)
    {
        if (this.outputPresets == null)
        {
            return null;
        }

        for (CompOutputPreset preset : this.outputPresets)
        {
            if (preset != null && preset.id.equals(presetId))
            {
                return preset;
            }
        }

        return null;
    }

    public CompOutputPreset getPresetOrFirst(String presetId)
    {
        CompOutputPreset preset = this.getPreset(presetId);

        if (preset != null || this.outputPresets == null || this.outputPresets.isEmpty())
        {
            return preset;
        }

        for (CompOutputPreset candidate : this.outputPresets)
        {
            if (candidate != null)
            {
                return candidate;
            }
        }

        return null;
    }

    public static final class CompSettings
    {
        public boolean autoSave = true;
        /** Absolute external media links shown in the project asset browser; never copied. */
        public List<String> externalAssets = new ArrayList<>();
        /**
         * User-created project-panel bins. An empty list is shown as one implicit "项目素材" bin;
         * the first rename/add materialises it so new projects stay uncluttered.
         */
        public List<CompMediaBin> mediaBins = new ArrayList<>();
        /**
         * Stable media token ("images/a.png" or "external:C:/media/a.png") to bin id.
         *
         * <p>A map instead of a bin-owned item list keeps media that was imported by another path
         * (OS drop, "导入音频", an old project) in the panel automatically: an unassigned item is
         * simply in the default bin.</p>
         */
        public java.util.Map<String, String> mediaBinAssignments = new java.util.LinkedHashMap<>();
        public boolean showSafeArea = true;
        public boolean showGrid = false;
        public int gridSize = 80;
        public boolean depthEnabled = true;

        /**
         * Texture sampling policy for image/footage layers.
         *
         * <p>0 = automatic (high-resolution sources get linear filtering, pixel art stays nearest),
         * 1 = force linear, 2 = force nearest. The settings page shows the resolved automatic state
         * and how many sources in the current composition are considered high resolution.</p>
         */
        public int textureFilter = 0;

        /** 0 = flat editor background, 1 = live BBS frame behind the composition. */
        public int previewBackground = 0;

        /**
         * False until the user (or the migration below) has chosen a background mode.
         *
         * <p>Default 0 = the composition's own solid colour. A compositor preview has to show the
         * frame that will be exported; the live BBS world is available in the preview bar but is
         * never the default, because a transparent stage reads as "the panel is broken".</p>
         */
        public boolean previewBackgroundChosen = false;

        /** Preview overlay (FX/Mask/BBS) folded into a single arrow button. */
        public boolean previewControlsCollapsed = false;

        /**
         * The preview bar's texture-filter group is open (three rows below the chip).
         *
         * <p>Persisted so the control does not forget its state when a project is reopened. The
         * narrow-preview layout hides the menu without clearing this flag; widening the panel
         * brings the menu back exactly where the user left it.</p>
         */
        public boolean previewFilterExpanded = false;

        /** Persisted editor layout (pixels; dragged by the split handles). */
        public int leftPanelWidth = 190;
        public int rightPanelWidth = 300;
        public int timelineHeight = 250;
        public int keyframeDrawerHeight = 154;
        /** Width of the layer quick panel docked to the right of the timeline. */
        public int quickPanelWidth = 210;
        /** 0 = track timeline, 1 = curve editor. */
        public int timelinePage = 0;
        /**
         * UI density: 0 = follow BBS's GUI scale, 1 = compact, 2 = normal, 3 = comfortable.
         * Drives every row height, control height, label column and panel width (see CompUi).
         */
        public int uiDensity = 0;
        public boolean cameraPreviewMode = false;
        public boolean show3DGrid = true;

        /**
         * The 3D workspace (Blender-like free viewport) is open.
         *
         * <p>Separate from {@link #cameraPreviewMode}, which means "look through the composition
         * camera". A viewport and a camera view are different questions: the first is where the
         * editor stands, the second is what the shot sees. Conflating them is why the old build
         * could only navigate by moving the shot camera itself.</p>
         */
        public boolean viewport3D = false;
        /** False until the viewport has been framed once, so old projects open with a sane view. */
        public boolean viewportPoseValid = false;
        public float viewportYaw = 32F;
        public float viewportPitch = 20F;
        public float viewportDistance = 0F;
        public float viewportTargetX = 0F;
        public float viewportTargetY = 0F;
        public float viewportTargetZ = 0F;
        public boolean viewportOrtho = false;

        /** Default duration for newly created layers, in seconds. 0 = whole composition. */
        public float defaultLayerDuration = 0F;

        /**
         * Explicit compositor export duration in seconds. 0 means "use the composition duration".
         * The value is passed straight to BBS's native recorder, so it is independent of the BBS
         * camera timeline and the film panel's loop range.
         */
        public float exportDuration = 0F;

        /** Show composition effects when a film is played outside the BBS editors. */
        public boolean showDuringExternalPlayback = true;

        /** 0 = auto (comp height * 0.12, clamped 8..64); otherwise pixels per block. */
        public int modelPixelsPerBlock = 0;
        /** Render scale of the independent-model target (0.25..1); lower is faster/softer. */
        public float modelRenderScale = 1F;
        /** Whether independent bbsae replay models draw BBS shadows. */
        public boolean modelShadows = false;

        /** 0 = Premiere-style shortcuts, 1 = BBS-style shortcuts. */
        public int shortcutScheme = 0;

        /**
         * {@link CompProject#ENCODER_BBS} or {@link CompProject#ENCODER_AE}.
         *
         * <p>Default BBS: the composition stays an overlay inside BBS, and BBS's recorder is the
         * only encoder. AE mode takes over the frame pipeline — see the risk list in
         * {@code UICompositorPanel#showEncoderRisk} for what that costs.</p>
         */
        public String encoder = CompProject.ENCODER_BBS;

        /** True once the user has read the AE-mode risk list; the warning is shown once. */
        public boolean encoderRiskAcknowledged = false;

        /**
         * Preview proxy for AE mode: 1 = full composition resolution, 0.5 = half, …
         *
         * <p>Deliberately explicit and visible: a proxy that silently halves the resolution is how
         * "the preview looks soft but the export is sharp" becomes a bug report. The badge shows the
         * factor, and export always renders at 1.</p>
         */
        public float encoderProxy = 1F;

        /** Audition the composition's audio track while previewing in AE mode. */
        public boolean previewAudio = true;

        public String defaultOutputPreset = "review_h264";

        /* ---------------------------------------------------------- developer (compat) */

        /**
         * Developer switch for the 3D layer logic (Settings → 开发者).
         *
         * <p>{@code false} is the AE rule every new project uses: only a layer whose own
         * <b>3D 图层</b> switch is on is projected through the camera; a 2D layer stays
         * screen-attached no matter how the camera is tilted or dollied.</p>
         *
         * <p>{@code true} restores the behaviour of builds before 0.91, where any spatial frame
         * (a tilted camera or any layer at z ≠ 0) projected <b>every</b> layer. It exists only so
         * projects authored against the old logic can be reopened with their original look; new
         * work should not use it.</p>
         */
        public boolean legacy3DLogic = false;

        /**
         * Which 3D-layer logic the project was authored with: 1 = AE per-layer 3D (default),
         * 2 = legacy all-layer camera projection. Null means a pre-v6 file that never recorded
         * it (CompStore.migrate derives it from {@link #legacy3DLogic}). This is a migration
         * anchor, not a second switch: the toggle above stays the runtime decision.
         */
        public Integer logicVersion = null;

        /**
         * Developer switch for camera creation (Settings → 开发者).
         *
         * <p>AE treats a camera as an explicit object: entering the 3D workspace, orbiting the
         * view or dragging the preview must never create one. This legacy behaviour — the very
         * bug the AE camera logic replaces — is kept behind the switch, off by default. It only
         * ever applies to a drag gesture; the explicit "新建摄像机" button always works.</p>
         */
        public boolean cameraAutoCreateOnDrag = false;

        /**
         * The AE camera tool is active: dragging the composition preview manipulates the camera
         * (left = orbit around the point of interest, middle = track XY, right = track Z / dolly)
         * instead of picking and moving layers.
         */
        public boolean cameraToolActive = false;

        /**
         * Vertical field of view given to newly created cameras, in degrees.
         *
         * <p>Read at creation time only; changing it never re-frames existing cameras. Default 50°
         * matches {@link CompLayer#cameraFov}.</p>
         */
        public float defaultCameraFov = 50F;

        /* ------------------------------------------------------------ dock layout (BBS) */

        /**
         * The compositor's docked-panel tree, serialized with BBS's {@code DataToString}.
         *
         * <p>Empty means "the default tree". The tree is the whole layout (splitter ratios, tab
         * stacks and which panel is active); it is written by {@code CompDockSource} whenever BBS's
         * {@code UIDockLayout} changes it. Storing it per project keeps editor layouts with the
         * project instead of leaking one project's panel arrangement into another.</p>
         */
        public String dockLayout = "";

        /** Comma-separated ids of panels the user hid in the dock; empty = none. */
        public String dockHiddenPanels = "";

        /** True when the dock is unlocked (panels can be dragged). BBS ships locked by default. */
        public boolean dockUnlocked = false;

        /**
         * Editor layout revision. 0 = a project written before the effects-left redesign; 1 = the
         * current layout (source browser left, preview centre, inspector right). The dock source
         * converts the old default tree once and then records 1 here, so a user who later picks
         * "效果库在右侧" from the layout menu keeps that choice across loads.
         */
        public int uiLayoutRevision = 1;

        /* ------------------------------------------------------------ editor view state */

        /**
         * Per-project editor view state (not composition content, not part of undo): the drawer,
         * the selection, the inspector page and the timeline's zoom/scroll. Restoring it on open
         * makes "reopen the project and continue where I was" work like the rest of the BBS
         * editors instead of always resetting the timeline to the full composition.
         */
        public boolean keyframeDrawerOpen = false;
        /** Comma-separated layer ids of the current selection (empty = none). */
        public String selectedLayerIds = "";
        public String selectedAudioId = "";
        public int inspectorPage = 0;
        public int leftPage = 0;
        /** 0 = never touched (fit to the composition); otherwise the Scale's zoom/shift. */
        public double timelineZoom = 0D;
        public double timelineShift = 0D;
        public int timelineScrollY = 0;
        /**
         * Per-composition timeline view memory, keyed by composition id.
         *
         * <p>Values are {@code "shift,zoom,scrollY,keyShift,keyZoom"}. The single fields above can
         * only remember one composition; a project edits many (main, precomps, replay comps), and
         * switching between them used to re-fit the timeline and lose the zoom the user had set.
         * Keeping one entry per composition makes "switch page/composition and come back" preserve
         * the time-axis size, like the film editor does.</p>
         */
        public java.util.Map<String, String> timelineViews = new java.util.LinkedHashMap<>();

        /** Decoded form of one {@link #timelineViews} entry. */
        public record TimelineView(boolean fit, double shift, double zoom, int scrollY,
                                   double keyShift, double keyZoom)
        {}

        /**
         * Stores one composition's time-axis state. "Fit" is kept as a flag rather than two
         * numbers so a fitted timeline keeps following the composition after new content grows it.
         */
        public void putTimelineView(String compId, boolean fit, double shift, double zoom,
                                    int scrollY, double keyShift, double keyZoom)
        {
            if (compId == null || compId.isEmpty())
            {
                return;
            }

            this.timelineViews.put(compId, (fit ? "1" : "0") + "," + shift + "," + zoom + ","
                + scrollY + "," + keyShift + "," + keyZoom);
        }

        /**
         * Parses one entry. Accepts the six-field format and the earlier five-field format
         * ({@code shift,zoom,scroll,keyShift,keyZoom}) written before the fit flag existed.
         *
         * @return null when the string is missing, malformed or carries a non-positive zoom
         */
        public static TimelineView timelineView(String encoded)
        {
            if (encoded == null || encoded.isBlank())
            {
                return null;
            }

            String[] parts = encoded.split(",");

            if (parts.length < 2)
            {
                return null;
            }

            try
            {
                boolean fit = parts.length >= 6 && "1".equals(parts[0]);
                int base = parts.length >= 6 ? 1 : 0;
                double shift = Double.parseDouble(parts[base]);
                double zoom = Double.parseDouble(parts[base + 1]);
                int scroll = parts.length > base + 2
                    ? (int) Math.round(Double.parseDouble(parts[base + 2])) : 0;
                double keyShift = parts.length >= base + 5 ? Double.parseDouble(parts[base + 3]) : 0D;
                double keyZoom = parts.length >= base + 5 ? Double.parseDouble(parts[base + 4]) : 0D;

                if (!(zoom > 0D) || Double.isNaN(shift) || Double.isInfinite(shift)
                    || Double.isNaN(zoom) || Double.isInfinite(zoom)
                    || Double.isNaN(keyShift) || Double.isNaN(keyZoom))
                {
                    return null;
                }

                return new TimelineView(fit, shift, zoom, scroll, keyShift, keyZoom);
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
    }
}
