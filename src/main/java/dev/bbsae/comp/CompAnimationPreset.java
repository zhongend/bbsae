package dev.bbsae.comp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reusable property-animation preset.
 *
 * <p>Presets store the same BBS keyframe data a layer uses, but they intentionally apply only
 * transform/opacity/scalar channels. Content (text, shapes, masks, effects) stays with the target
 * layer, so applying a preset cannot silently replace a shot's artwork. This is the useful part of
 * an AE animation preset without introducing an asset dependency graph.</p>
 *
 * <p>See {@link #isPresetChannel(String)} for the exact split: {@code fx/}, {@code mask/},
 * {@code shape/} and {@code text/} channels are content and are neither captured nor applied.</p>
 */
public class CompAnimationPreset
{
    public String id = "preset";
    public String name = "Preset";
    public String data = "";

    public CompAnimationPreset()
    {}

    public CompAnimationPreset(String id, String name, String data)
    {
        this.id = id;
        this.name = name;
        this.data = data;
    }

    /**
     * True for the channels a preset is allowed to carry: property animation only.
     *
     * <p>{@code fx/}, {@code mask/}, {@code shape/} and {@code text/} are content animation — an
     * effect parameter, a mask feather, a shape path or a text size belongs to the artwork of one
     * layer, not to a reusable motion. Capturing them made "save layer animation" quietly copy the
     * source layer's effects/masks/shapes, and applying it cleared the target's own ones (the old
     * {@code channels.clear()} + {@code putAll}) and injected the source's effect curves, whose
     * {@code fx/<id>} ids then pointed at effects that did not exist on the target.</p>
     */
    public static boolean isPresetChannel(String path)
    {
        if (path == null || path.isBlank())
        {
            return false;
        }

        return !(path.startsWith("fx/")
            || path.startsWith("mask/")
            || path.startsWith("shape/")
            || path.startsWith("text/"));
    }

    public static CompAnimationPreset capture(String id, String name, CompLayer layer)
    {
        if (layer == null)
        {
            return null;
        }

        layer.saveTracks();

        CompLayer copy = new CompLayer(layer.id, layer.name, layer.type);

        copy.transformData = layer.transformData;
        copy.opacityData = layer.opacityData;

        if (layer.channels != null)
        {
            for (Map.Entry<String, String> entry : layer.channels.entrySet())
            {
                if (isPresetChannel(entry.getKey()))
                {
                    copy.channels.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return new CompAnimationPreset(id, name, CompStore.gson().toJson(copy));
    }

    public boolean applyTo(CompLayer target)
    {
        if (target == null || this.data == null || this.data.isBlank())
        {
            return false;
        }

        CompLayer source = CompStore.gson().fromJson(this.data, CompLayer.class);

        if (source == null)
        {
            return false;
        }

        /* Flush the live channels into the serialized map first: loadTracks() rebuilds the runtime
         * channels from it, so a key that was added since the last save would otherwise be
         * dropped by the merge below. This also rewrites target.transformData/opacityData, which
         * is why the source data is copied afterwards. */
        target.saveTracks();

        if (target.channels == null)
        {
            target.channels = new LinkedHashMap<>();
        }

        /* Only property channels are replaced. Everything the preset does not own — masks, shapes,
         * effects, text animation — is carried over untouched instead of being wiped by a
         * whole-map clear. */
        Map<String, String> preserved = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : target.channels.entrySet())
        {
            if (!isPresetChannel(entry.getKey()))
            {
                preserved.put(entry.getKey(), entry.getValue());
            }
        }

        target.channels.clear();

        if (source.channels != null)
        {
            for (Map.Entry<String, String> entry : source.channels.entrySet())
            {
                if (isPresetChannel(entry.getKey()))
                {
                    target.channels.put(entry.getKey(), entry.getValue());
                }
            }
        }

        target.channels.putAll(preserved);

        /* The preset owns the transform/opacity animation, so those two legacy blobs come from the
         * source. The preserved content channels were already captured above and are re-read by
         * loadTracks() together with the preset's property channels. */
        target.transformData = source.transformData;
        target.opacityData = source.opacityData;

        target.loadTracks();

        return true;
    }
}
