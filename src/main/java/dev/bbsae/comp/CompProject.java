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
    public static final int VERSION = 2;

    public String format = FORMAT;
    public int version = VERSION;
    public String id = "demo";
    public String name = "Demo Project";

    public List<CompDocument> comps = new ArrayList<>();
    public List<CompOutputPreset> outputPresets = new ArrayList<>();
    public List<CompAnimationPreset> animationPresets = new ArrayList<>();
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

        /** Preview overlay (FX/Mask/BBS) folded into a single arrow button. */
        public boolean previewControlsCollapsed = false;

        /** Persisted editor layout (pixels; dragged by the split handles). */
        public int leftPanelWidth = 190;
        public int rightPanelWidth = 300;
        public int timelineHeight = 250;
        public int keyframeDrawerHeight = 154;
        public boolean cameraPreviewMode = false;
        public boolean show3DGrid = true;

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

        public String defaultOutputPreset = "review_h264";
    }
}
