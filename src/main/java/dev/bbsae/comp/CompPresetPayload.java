package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The payload of a preset: exactly the parts of a layer a preset is allowed to carry.
 *
 * <p>Separating the payload from {@link CompPreset} keeps the on-disk shape stable (one JSON string
 * per preset) while letting the apply code be explicit about <b>what</b> a preset touches. Every
 * channel path is remapped when an effect/shape gets a fresh id, because animated parameters live
 * under {@code fx/<id>/...} and {@code shape/<id>/...}: copying the effect without its channels
 * would drop the animation the preset was saved to preserve.</p>
 */
public class CompPresetPayload
{
    public String transformData = "";
    public String opacityData = "";
    public Map<String, String> channels = new LinkedHashMap<>();
    public List<CompEffect> effects = new ArrayList<>();
    public List<CompStyle> styles = new ArrayList<>();
    public List<CompShape> shapes = new ArrayList<>();

    /** Layer-level look, used by the "整套" preset. */
    public String layerType = "";
    public int color = 0xFFFFFFFF;
    public int strokeColor = 0x00000000;
    public float strokeWidth = 0F;

    /** Where and at what frame rate the data was captured; used to re-time on apply. */
    public float sourceIn = 0F;
    public float sourceFps = 24F;

    public CompPresetPayload copy()
    {
        CompPresetPayload copy = new CompPresetPayload();

        copy.transformData = this.transformData;
        copy.opacityData = this.opacityData;
        copy.channels.putAll(this.channels);
        copy.layerType = this.layerType;
        copy.color = this.color;
        copy.strokeColor = this.strokeColor;
        copy.strokeWidth = this.strokeWidth;
        copy.sourceIn = this.sourceIn;
        copy.sourceFps = this.sourceFps;

        for (CompEffect effect : this.effects)
        {
            copy.effects.add(effect.copy());
        }

        for (CompStyle style : this.styles)
        {
            CompStyle styleCopy = new CompStyle(style.type);

            styleCopy.enabled = style.enabled;
            styleCopy.color = style.color;
            styleCopy.color2 = style.color2;
            styleCopy.opacity = style.opacity;
            styleCopy.size = style.size;
            styleCopy.distance = style.distance;
            styleCopy.angle = style.angle;
            styleCopy.blur = style.blur;
            styleCopy.blendMode = style.blendMode;
            copy.styles.add(styleCopy);
        }

        for (CompShape shape : this.shapes)
        {
            copy.shapes.add(shape.copy());
        }

        return copy;
    }

    /* Channel helpers */

    private static void remap(Map<String, String> channels, String from, String to)
    {
        if (from == null || from.isEmpty() || from.equals(to))
        {
            return;
        }

        List<String> keys = new ArrayList<>(channels.keySet());

        for (String key : keys)
        {
            if (key.startsWith(from))
            {
                String value = channels.remove(key);

                channels.put(to + key.substring(from.length()), value);
            }
        }
    }

    private static String uniqueId(String base, java.util.Collection<String> taken)
    {
        String candidate = base;
        int serial = 2;

        while (taken.contains(candidate))
        {
            candidate = base + "_" + serial;
            serial += 1;
        }

        return candidate;
    }

    /* Apply */

    /**
     * Applies this payload to a layer.
     *
     * @param motion   replace/merge transform + opacity + scalar channels.
     * @param effect   append the effect stack.
     * @param style    append the style stack.
     * @param shape    append the shape group.
     * @param look     copy layer-level colour/stroke values.
     */
    public void applyTo(CompLayer target, boolean motion, boolean effect, boolean style, boolean shape, boolean look, float targetFps)
    {
        if (target == null)
        {
            return;
        }

        target.ensureInitialized();
        /* Flush live channels into the serialized map first: the apply code edits that map and
         * rebuilds the channels at the end, so anything not flushed here would be dropped. */
        target.saveTracks();

        if (motion)
        {
            if (this.transformData != null && !this.transformData.isBlank())
            {
                target.transformData = this.transformData;
            }

            if (this.opacityData != null && !this.opacityData.isBlank())
            {
                target.opacityData = this.opacityData;
            }

            if (target.channels == null)
            {
                target.channels = new LinkedHashMap<>();
            }

            target.channels.putAll(this.channels);
            target.loadTracks();

            /* Presets are authored relative to the source layer's in point, like AE's animation
             * presets: dropping one on a clip that starts later must animate that clip, not the
             * empty space before it. */
            float shift = (target.in - this.sourceIn) * Math.max(1F, targetFps);

            if (Math.abs(shift) > 0.001F)
            {
                target.shiftKeyframes(shift);
            }
        }

        if (effect)
        {
            List<String> taken = new ArrayList<>();

            for (CompEffect existing : target.effects)
            {
                taken.add(existing.id);
            }

            for (CompEffect source : this.effects)
            {
                CompEffect copy = source.copy();
                String newId = uniqueId(source.type + "_" + (taken.size() + 1), taken);

                copy.id = newId;
                taken.add(newId);
                target.effects.add(copy);
                remap(target.channels, "fx/" + source.id + "/", "fx/" + newId + "/");
            }
        }

        if (style)
        {
            for (CompStyle source : this.styles)
            {
                CompStyle copy = new CompStyle(source.type);

                copy.enabled = source.enabled;
                copy.color = source.color;
                copy.color2 = source.color2;
                copy.opacity = source.opacity;
                copy.size = source.size;
                copy.distance = source.distance;
                copy.angle = source.angle;
                copy.blur = source.blur;
                copy.blendMode = source.blendMode;
                target.styles.add(copy);
            }
        }

        if (shape)
        {
            List<String> taken = new ArrayList<>();

            for (CompShape existing : target.shapes)
            {
                taken.add(existing.id);
            }

            for (CompShape source : this.shapes)
            {
                CompShape copy = source.copy();
                String newId = uniqueId(source.id, taken);

                copy.id = newId;
                taken.add(newId);
                target.shapes.add(copy);
                remap(target.channels, "shape/" + source.id + "/", "shape/" + newId + "/");
            }
        }

        if (look)
        {
            target.color = this.color;
            target.strokeColor = this.strokeColor;
            target.strokeWidth = this.strokeWidth;
        }

        target.loadTracks();
    }
}
