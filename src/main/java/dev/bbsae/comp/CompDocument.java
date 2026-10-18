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
    /** ARGB; 0 means transparent. */
    public int background = 0x00000000;

    /** Work area, seconds. */
    public float workAreaIn = 0F;
    public float workAreaOut = 12F;
    public float currentTime = 0F;
    /** Empty means "use the topmost visible camera layer"; otherwise a camera layer id. */
    public String activeCameraId = "";

    public List<CompLayer> layers = new ArrayList<>();
    public List<CompMarker> markers = new ArrayList<>();
    public List<CompParameter> parameters = new ArrayList<>();

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

    /** The camera that should drive this comp at the given time, or null for a flat comp. */
    public CompLayer activeCamera(float time)
    {
        if (this.activeCameraId != null && !this.activeCameraId.isEmpty())
        {
            CompLayer chosen = this.getLayer(this.activeCameraId);

            if (chosen != null && "camera".equals(chosen.type) && chosen.visible && chosen.activeAt(time))
            {
                return chosen;
            }
        }

        for (int i = this.layers.size() - 1; i >= 0; i--)
        {
            CompLayer layer = this.layers.get(i);

            if ("camera".equals(layer.type) && layer.visible && layer.activeAt(time))
            {
                return layer;
            }
        }

        return null;
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

        return layer;
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
        }

        if (layerId.equals(this.activeCameraId))
        {
            this.activeCameraId = "";
        }

        return true;
    }

    public CompMarker markerAt(float seconds)
    {
        for (CompMarker marker : this.markers)
        {
            if (Math.abs(marker.time - seconds) < 0.0001F)
            {
                return marker;
            }
        }

        return null;
    }

    public CompParameter getParameter(String name)
    {
        for (CompParameter parameter : this.parameters)
        {
            if (parameter.name.equals(name))
            {
                return parameter;
            }
        }

        return null;
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
