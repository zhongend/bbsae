package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * One composition. Compositions are the unit of rendering and the unit of nesting: a precomp
 * layer points at another {@link CompDocument} by id and renders it with an independent time.
 */
public class CompDocument
{
    public String id = "main";
    public String name = "Main";
    public int width = 1920;
    public int height = 1080;
    public float fps = 60F;
    /** Duration in seconds. */
    public float duration = 12F;
    /**
     * ARGB stage colour. Opaque black by default.
     *
     * <p>A transparent stage made the editor preview show the panel's own dark surface through the
     * composition, which read as "the compositor is empty / the background is broken". Transparency
     * is still a valid choice (alpha output modules clear the stage regardless), but it is now an
     * explicit one instead of the default.</p>
     */
    public int background = 0xFF000000;

    /** Work area, seconds. */
    public float workAreaIn = 0F;
    public float workAreaOut = 12F;
    public float currentTime = 0F;
    /**
     * Empty means "use the topmost usable camera layer"; otherwise a camera layer id. The id only
     * wins while that camera is visible and inside its in/out range at the current frame (see
     * {@link #activeCamera(float)}).
     */
    public String activeCameraId = "";

    public List<CompLayer> layers = new ArrayList<>();
    public List<CompMarker> markers = new ArrayList<>();
    public List<CompParameter> parameters = new ArrayList<>();
    /** Audio track: music, voice-over and effects mixed into every export. */
    public List<CompAudio> audio = new ArrayList<>();
    /** Point trackers; results feed layer baking and {@code tracker.<id>.x/y} expressions. */
    public List<CompTracker> trackers = new ArrayList<>();

    public CompLayer getLayer(String layerId)
    {
        if (layerId == null)
        {
            return null;
        }

        for (CompLayer layer : this.layers)
        {
            if (layerId.equals(layer.id))
            {
                return layer;
            }
        }

        return null;
    }

    /**
     * The camera that should drive this comp at {@code time}, or null for a flat comp.
     *
     * <h2>Selection rules</h2>
     * <ol>
     *   <li>{@link #activeCameraId} wins <b>only while it still names a usable camera this
     *       frame</b>: it must be a camera layer, visible, and inside its in/out range.</li>
     *   <li>Otherwise the topmost usable camera wins (the same "front camera" rule AE uses).</li>
     *   <li>Otherwise there is no camera and the renderer falls back to its default 1:1 view.</li>
     * </ol>
     *
     * <p>The previous rule let a hidden or trimmed-out camera keep driving the frame forever,
     * because a camera was treated as pure view state. That made the active-camera picker lie:
     * clicking a camera that was hidden or outside its trim did nothing visible, while the old
     * camera went on rendering. Making visibility and trim part of "usable" means the picker,
     * the gizmo and the picture always agree; the fallback keeps the frame renderable instead of
     * dropping the camera entirely.</p>
     */
    public CompLayer activeCamera(float time)
    {
        CompLayer chosen = this.getLayer(this.activeCameraId);

        if (isUsableCamera(chosen, time))
        {
            return chosen;
        }

        for (int i = this.layers.size() - 1; i >= 0; i--)
        {
            CompLayer layer = this.layers.get(i);

            if (isUsableCamera(layer, time))
            {
                return layer;
            }
        }

        return null;
    }

    /** A camera is usable when it is a camera layer that is visible at {@code time}. */
    public static boolean isUsableCamera(CompLayer layer, float time)
    {
        return layer != null && layer.isCamera() && layer.visible && layer.activeAt(time);
    }

    public int indexOfLayer(String layerId)
    {
        for (int i = 0; i < this.layers.size(); i++)
        {
            if (this.layers.get(i).id.equals(layerId))
            {
                return i;
            }
        }

        return -1;
    }

    public CompLayer addLayer(CompLayer layer)
    {
        if (layer == null)
        {
            return null;
        }

        layer.id = CompStore.uniqueId(layer.id == null || layer.id.isEmpty() ? layer.type : layer.id, this.layers, (l) -> l.id);
        this.layers.add(layer);
        this.extendDurationToContent();

        return layer;
    }

    /**
     * End of the last piece of timeline content, in seconds. Includes every layer (visual,
     * camera, audio on the layer track) and every audio clip, visible or not: trimming a layer
     * out of view must not silently shorten the project either.
     */
    public float contentEnd()
    {
        float end = 0F;

        for (CompLayer layer : this.layers == null ? java.util.Collections.<CompLayer>emptyList() : this.layers)
        {
            if (layer != null)
            {
                end = Math.max(end, Math.max(layer.in, layer.out));
            }
        }

        for (CompAudio clip : this.audio == null ? java.util.Collections.<CompAudio>emptyList() : this.audio)
        {
            if (clip != null)
            {
                end = Math.max(end, clip.start + Math.max(0F, clip.length(this.duration)));
            }
        }

        return Math.max(0.1F, end);
    }

    /**
     * Grows the composition so its last layer/audio clip is fully reachable. Never shrinks: a
     * deliberately longer canvas (markers, empty tail, export padding) is editorial state, while
     * the old 12s clamp silently cut real content.
     */
    public boolean extendDurationToContent()
    {
        return this.setDurationAtLeast(this.contentEnd());
    }

    /** Grows duration/work area to at least {@code minimum}; returns true when it changed. */
    public boolean setDurationAtLeast(float minimum)
    {
        float wanted = Math.max(0.1F, minimum);

        if (wanted <= this.duration + 0.0001F)
        {
            return false;
        }

        float old = this.duration;

        this.duration = wanted;

        /* Follow the end only when the work area was the untouched default (or already invalid).
         * A deliberately shorter loop must survive this; only the old 12s barrier is removed. */
        if (this.workAreaOut <= 0F || Math.abs(this.workAreaOut - old) <= 0.0001F
            || this.workAreaOut > this.duration)
        {
            this.workAreaOut = this.duration;
        }

        return true;
    }

    public boolean removeLayer(String layerId)
    {
        int index = this.indexOfLayer(layerId);

        if (index < 0)
        {
            return false;
        }

        this.layers.remove(index);

        for (CompLayer layer : this.layers)
        {
            if (layerId.equals(layer.parentId))
            {
                layer.parentId = "";
            }
            if (layerId.equals(layer.trackMatteId))
            {
                layer.trackMatteId = "";
            }

            /* A tracked binding is the third reference that names a layer id. Leaving it pointing
             * at a deleted layer made the binding silently resolve to nothing — the HUD layer that
             * followed a null simply stopped moving while the inspector still printed the dead id. */
            if (layer.binding != null && CompBinding.LAYER.equals(layer.binding.type)
                && layerId.equals(layer.binding.target))
            {
                layer.binding.target = "";
                layer.binding.enabled = false;
            }
        }

        if (layerId.equals(this.activeCameraId))
        {
            this.activeCameraId = "";
        }

        return true;
    }

    /**
     * Move a layer in the timeline's visual row space (row 0 is the top-most layer, i.e. the last
     * entry in {@link #layers}). Returns true when the order actually changed.
     */
    public boolean moveLayerToDisplayIndex(CompLayer layer, int displayIndex)
    {
        return this.moveLayerToDisplayIndex(layer, displayIndex, true);
    }

    /**
     * Whether {@link #moveLayerToDisplayIndex} would change anything.
     *
     * <p>The editor pushes an undo checkpoint before a reorder and that checkpoint clears the redo
     * stack. A plain click on a track's drag grip asks to move the layer onto its own row; without
     * this test that no-op wiped the user's redo history.</p>
     */
    public boolean wouldMoveLayerToDisplayIndex(CompLayer layer, int displayIndex)
    {
        return this.moveLayerToDisplayIndex(layer, displayIndex, false);
    }

    private boolean moveLayerToDisplayIndex(CompLayer layer, int displayIndex, boolean apply)
    {
        if (layer == null)
        {
            return false;
        }

        List<CompLayer> display = new ArrayList<>(this.layers);

        java.util.Collections.reverse(display);

        int from = display.indexOf(layer);

        if (from < 0)
        {
            return false;
        }

        int to = Math.max(0, Math.min(display.size(), displayIndex));

        display.remove(from);

        if (to > from)
        {
            to--;
        }

        if (to == from)
        {
            return false;
        }

        if (!apply)
        {
            return true;
        }

        display.add(to, layer);

        this.layers.clear();

        for (int i = display.size() - 1; i >= 0; i--)
        {
            this.layers.add(display.get(i));
        }

        return true;
    }

    public CompMarker markerAt(float seconds)    {
        for (CompMarker marker : this.markers)
        {
            if (Math.abs(marker.time - seconds) < 0.0001F)
            {
                return marker;
            }
        }

        return null;
    }

    /**
     * True when at least one visible, non-camera layer is soloed.
     *
     * <p>Solo lives here because four different places need the same answer: the composition graph,
     * the 3D workspace, the preview hit test and the track header. Each of them re-implementing it
     * is how soloing a layer hid everything in the export while the 3D workspace still drew it.</p>
     */
    public boolean hasSolo()
    {
        for (CompLayer layer : this.layers)
        {
            if (layer != null && layer.visible && layer.solo && !"camera".equals(layer.type))
            {
                return true;
            }
        }

        return false;
    }

    /** Solo semantics: when the comp has a solo layer, every non-soloed layer is hidden. */
    public boolean isSoloedOut(CompLayer layer)
    {
        return (layer == null || !layer.solo) && this.hasSolo();
    }

    public CompParameter getParameter(String name)
    {        for (CompParameter parameter : this.parameters)
        {
            if (parameter.name.equals(name))
            {
                return parameter;
            }
        }

        return null;
    }

    public CompTracker getTracker(String trackerId)
    {
        if (trackerId == null)
        {
            return null;
        }

        for (CompTracker tracker : this.trackers)
        {
            if (trackerId.equals(tracker.id))
            {
                return tracker;
            }
        }

        return null;
    }

    public void removeTracker(String trackerId)
    {
        for (int i = 0; i < this.trackers.size(); i++)
        {
            if (trackerId.equals(this.trackers.get(i).id))
            {
                this.trackers.remove(i);

                return;
            }
        }
    }

    public int getFrameCount()
    {
        return Math.max(1, Math.round(this.duration * this.fps));
    }

    /** A defensive, serialization-safe copy used by precompose/duplicate. */
    public CompDocument copy()
    {
        for (CompLayer layer : this.layers)
        {
            layer.saveTracks();
        }

        CompDocument copy = CompStore.gson().fromJson(CompStore.gson().toJson(this), CompDocument.class);

        if (copy == null)
        {
            copy = new CompDocument();
        }

        for (CompLayer layer : copy.layers)
        {
            layer.loadTracks();
        }

        return copy;
    }
}
