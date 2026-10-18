package dev.bbsae.comp;

/**
 * A reusable property-animation preset.
 *
 * <p>Presets store the same BBS keyframe data a layer uses, but they intentionally apply only
 * transform/opacity/scalar channels. Content (text, shapes, masks, effects) stays with the target
 * layer, so applying a preset cannot silently replace a shot's artwork. This is the useful part of
 * an AE animation preset without introducing an asset dependency graph.</p>
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
        copy.channels.putAll(layer.channels);

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

        target.transformData = source.transformData;
        target.opacityData = source.opacityData;

        if (target.channels == null)
        {
            target.channels = new java.util.LinkedHashMap<>();
        }

        target.channels.clear();

        if (source.channels != null)
        {
            target.channels.putAll(source.channels);
        }

        target.loadTracks();

        return true;
    }
}
