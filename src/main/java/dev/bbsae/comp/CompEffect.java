package dev.bbsae.comp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One effect instance on a layer. Parameters are plain values so an unknown/removed effect still
 * round-trips through the file, and every scalar parameter can additionally be animated through
 * the layer's native float channel {@code fx/<effectId>/<param>}.
 */
public class CompEffect
{
    public String id = "";
    public String type = "brightness_contrast";
    public boolean enabled = true;

    public Map<String, Double> params = new LinkedHashMap<>();
    public Map<String, Integer> colors = new LinkedHashMap<>();

    public CompEffect()
    {}

    public CompEffect(String id, String type)
    {
        this.id = id;
        this.type = type;
    }

    public double value(String name, double fallback)
    {
        Double value = this.params.get(name);

        return value == null ? fallback : value;
    }

    public float valueF(String name, float fallback)
    {
        return (float) this.value(name, fallback);
    }

    public int valueI(String name, int fallback)
    {
        return (int) Math.round(this.value(name, fallback));
    }

    public int color(String name, int fallback)
    {
        Integer value = this.colors.get(name);

        return value == null ? fallback : value;
    }

    public CompEffect put(String name, double value)
    {
        this.params.put(name, value);

        return this;
    }

    public CompEffect putColor(String name, int color)
    {
        this.colors.put(name, color);

        return this;
    }

    public CompEffect copy()
    {
        CompEffect copy = new CompEffect(this.id, this.type);

        copy.enabled = this.enabled;
        copy.params.putAll(this.params);
        copy.colors.putAll(this.colors);

        return copy;
    }
}
