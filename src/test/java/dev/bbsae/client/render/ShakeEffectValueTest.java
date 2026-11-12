package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effect parameters are authored with the inspector sliders, and a slider writes
 * {@link CompEffect#params} — it does not create a keyframe channel.
 *
 * <p>{@code CompScene.shakeOf()} used to read every parameter through a channel-only helper, so
 * all sliders (amplitude, frequency, rotation, seed) and 效果强度 fell back to their defaults in
 * the render; only a keyframed parameter changed the picture. These tests pin the render-visible
 * behaviour: base parameter first, channel overrides it, and a channel with no base still
 * animates (old projects and "key only one value" authoring keep working).</p>
 */
public class ShakeEffectValueTest
{
    private static final float BASE_X = 960F;
    private static final float BASE_Y = 540F;

    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("shake_layer", "Shake", "solid");

        layer.x = BASE_X;
        layer.y = BASE_Y;
        layer.width = 400F;
        layer.height = 200F;

        return layer;
    }

    private static CompRenderContext context(float time)
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        project.comps.add(comp);

        return new CompRenderContext(project, comp, time, null, null, null);
    }

    private static CompEffect addShake(CompLayer layer)
    {
        CompEffect effect = new CompEffect("fx1", "shake");

        layer.effects.add(effect);

        return effect;
    }

    /**
     * A time where both sine components of the shake are clearly non-zero.
     *
     * <p>The test asserts a displacement larger than 50 px, not merely "non-zero": the old
     * channel-only read fell back to the parameter's default amplitude (18 px), which is also
     * non-zero, so a {@code != 0} assertion would not have caught the bug. The sine blend is
     * bounded by ±amplitude, so 200 px on the slider is the only way to exceed 50 px here.</p>
     */
    private static final float TIME = 0.42F;

    private static final float MIN_SLIDER_DISPLACEMENT = 50F;

    @Test
    public void sliderValuesDriveTheShakeWithoutAnyChannel()
    {
        CompLayer layer = layer();
        CompEffect shake = addShake(layer);

        shake.put("amplitude", 200D);
        shake.put("frequency", 6D);
        shake.put("seed", 0D);

        Matrix4f local = CompScene.local(context(TIME), layer);

        assertTrue(Math.abs(local.m30() - BASE_X) > MIN_SLIDER_DISPLACEMENT,
            "amplitude slider must move the layer; got x=" + local.m30() + " (base " + BASE_X + ")");
        assertTrue(Math.abs(local.m31() - BASE_Y) > MIN_SLIDER_DISPLACEMENT,
            "the shake must move on Y too; got y=" + local.m31() + " (base " + BASE_Y + ")");
    }

    @Test
    public void aZeroStrengthSliderDisablesTheShake()
    {
        CompLayer layer = layer();
        CompEffect shake = addShake(layer);

        shake.put(CompEffect.STRENGTH, 0D);
        shake.put("amplitude", 200D);
        shake.put("frequency", 6D);

        Matrix4f local = CompScene.local(context(TIME), layer);

        assertEquals(BASE_X, local.m30(), 0.0001F, "效果强度 0 must freeze the layer");
        assertEquals(BASE_Y, local.m31(), 0.0001F);
    }

    @Test
    public void keyframedStrengthOverridesTheSlider()
    {
        CompLayer layer = layer();
        CompEffect shake = addShake(layer);

        shake.put(CompEffect.STRENGTH, 1D);
        shake.put("amplitude", 200D);
        shake.put("frequency", 6D);

        layer.scalarChannel(CompLayer.effectPath("fx1", CompEffect.STRENGTH)).insertInheriting(0F, 0F);

        Matrix4f local = CompScene.local(context(TIME), layer);

        assertEquals(BASE_X, local.m30(), 0.0001F, "the channel must win over the stored base value");
        assertEquals(BASE_Y, local.m31(), 0.0001F);
    }

    @Test
    public void keyframesWithoutABaseValueStillAnimate()
    {
        CompLayer layer = layer();

        addShake(layer);

        layer.scalarChannel(CompLayer.effectPath("fx1", "amplitude")).insertInheriting(0F, 200F);
        layer.scalarChannel(CompLayer.effectPath("fx1", "frequency")).insertInheriting(0F, 6F);

        Matrix4f local = CompScene.local(context(TIME), layer);

        assertTrue(Math.abs(local.m30() - BASE_X) > MIN_SLIDER_DISPLACEMENT,
            "a channel is the base when the parameter map has no entry");
    }

    @Test
    public void effectValueReadsTheBaseValueAndLetsTheChannelOverrideIt()
    {
        CompLayer layer = layer();
        CompEffect effect = new CompEffect("fx1", "blur");

        effect.put("amount", 12D);
        layer.effects.add(effect);

        assertEquals(12F, layer.effectValue(effect, "amount", 5F, 0F), 1e-5F,
            "without a channel the stored parameter is the value, not the fallback");
        assertEquals(5F, layer.effectValue(null, "amount", 5F, 0F), 1e-5F,
            "a missing effect keeps the caller's fallback");

        layer.scalarChannel(CompLayer.effectPath("fx1", "amount")).insertInheriting(0F, 40F);

        assertEquals(40F, layer.effectValue(effect, "amount", 5F, 0F), 1e-5F,
            "an existing channel overrides the stored parameter");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void theDeprecatedByIdHelperAlsoSeesTheBaseValue()
    {
        CompLayer layer = layer();
        CompEffect effect = new CompEffect("fx1", "blur");

        effect.put("amount", 12D);
        layer.effects.add(effect);

        /* Kept as a delegate so a stale render call site cannot fall back to the old
         * channel-only read. */
        assertEquals(12F, layer.effectParam("fx1", "amount", 5F, 0F), 1e-5F);
        assertEquals(5F, layer.effectParam("nope", "amount", 5F, 0F), 1e-5F,
            "an unknown effect id still returns the fallback");
    }
}
