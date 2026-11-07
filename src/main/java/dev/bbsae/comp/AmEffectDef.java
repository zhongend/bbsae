package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * One effect in the reference-compatible catalogue.
 *
 * <p>The definition owns the parameter schema and one fragment body. The body is ordinary GLSL
 * that reads {@code vUv}, samples {@code Source} and writes {@code fragColor}; the renderer wraps it
 * with the standard uniforms ({@code Size}, {@code Texel}, {@code LayerU/V}, texture bindings) and
 * the declared parameter uniforms, so an effect is added by describing it rather than by adding a
 * branch to a switch.</p>
 *
 * <p>Zero-value contract: an effect that has a natural identity must declare it through
 * {@link #zeroKey} (the parameter that turns the effect off) — the renderer then skips the pass
 * entirely at zero instead of relying on the shader to produce an exact copy.</p>
 */
public final class AmEffectDef
{
    public final String id;
    public final String name;
    public final String category;
    /** GLSL body of {@code void main()}. */
    public final String body;
    /** Extra padding as a fraction of the layer size (distortion pulls pixels from outside). */
    public final float padding;
    /** Parameter that makes the effect an identity at zero, or null when there is none. */
    public final String zeroKey;
    /** When set, the renderer runs its separable blur at this parameter's radius instead of the body. */
    public String blurKey;
    public boolean boxBlur;
    public String iterationsKey;
    /** True when the body samples the composition below the layer ({@code Comp}). */
    public boolean usesComp;

    public final List<Param> params = new ArrayList<>();
    public final List<ColorParam> colors = new ArrayList<>();
    /** Parameters that must all be zero for the effect to be an identity. */
    final List<String> zeroKeys = new ArrayList<>();

    public AmEffectDef(String id, String name, String category, String body, float padding, String zeroKey)
    {
        this.id = id;
        this.name = name;
        this.category = category;
        this.body = body;
        this.padding = padding;
        this.zeroKey = zeroKey;
    }

    public AmEffectDef p(String key, String label, float min, float max, float def, float step)
    {
        this.params.add(new Param(key, label, min, max, def, step));

        return this;
    }

    /** Declares that the effect is an identity when every one of these parameters is zero. */
    public AmEffectDef z(String... keys)
    {
        for (String key : keys)
        {
            this.zeroKeys.add(key);
        }

        return this;
    }

    /** Declares that the body samples the composition below the layer through {@code Comp}. */
    public AmEffectDef comp()
    {
        this.usesComp = true;

        return this;
    }

    /** Declares a pure blur effect: radius parameter, kernel and optional iteration parameter. */
    public AmEffectDef blur(String key, boolean box, String iterationsKey)
    {
        this.blurKey = key;
        this.boxBlur = box;
        this.iterationsKey = iterationsKey;

        return this;
    }

    public AmEffectDef c(String key, String label, int def)
    {
        this.colors.add(new ColorParam(key, label, def));

        return this;
    }

    public static final class Param
    {
        public final String key;
        public final String label;
        public final float min;
        public final float max;
        public final float defaultValue;
        public final float step;

        Param(String key, String label, float min, float max, float defaultValue, float step)
        {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.step = step;
        }
    }

    public static final class ColorParam
    {
        public final String key;
        public final String label;
        public final int defaultValue;

        ColorParam(String key, String label, int defaultValue)
        {
            this.key = key;
            this.label = label;
            this.defaultValue = defaultValue;
        }
    }
}
