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
     */
    public static final int VERSION = 5;

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
        if (compId == null || compId.isEmpty())
        {
            return this.comps.isEmpty() ? null : this.comps.get(0);
        }

        for (CompDocument comp : this.comps)
        {
            if (compId.equals(comp.id))
            {
                return comp;
            }
        }

        return null;
    }

    public CompDocument getCompOrFirst(String compId)
    {
        CompDocument comp = this.getComp(compId);

        return comp != null ? comp : (this.comps.isEmpty() ? null : this.comps.get(0));
    }

    public CompDocument addComp(String baseName, int width, int height, float fps, float duration)
    {
        String id = CompStore.uniqueId(baseName, this.comps, (comp) -> comp.id);
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
        for (CompOutputPreset preset : this.outputPresets)
        {
            if (preset.id.equals(presetId))
            {
                return preset;
            }
        }

        return null;
    }

    public CompOutputPreset getPresetOrFirst(String presetId)
    {
        CompOutputPreset preset = this.getPreset(presetId);

        return preset != null ? preset : (this.outputPresets.isEmpty() ? null : this.outputPresets.get(0));
    }

    public static final class CompSettings
    {
        public boolean autoSave = true;
        public boolean showSafeArea = true;
        public boolean showGrid = false;
        public int gridSize = 80;
        public boolean depthEnabled = true;

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
    }
}
