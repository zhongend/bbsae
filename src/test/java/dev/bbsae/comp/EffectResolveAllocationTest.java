package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Allocation behaviour of effect resolution.
 *
 * <p>{@link CompLayer#resolveEffect} used to copy the effect (new object, two {@link java.util.LinkedHashMap}s,
 * two {@code putAll}s) and collect {@code animatedPaths()} twice per call. A 300-effect stack rendered
 * through the bake, the composition pass and EffectPadding produced up to 1800 copies per frame.
 * These tests pin the two properties that make the pooled version safe: repeated resolves reuse the
 * same scratch object, and the scratch is never the live effect (so resolving can never write back
 * into the project). The direct {@link CompLayer#effectValue} path has to agree with the resolved
 * value for stored, animated and unstored parameters alike.</p>
 */
public class EffectResolveAllocationTest
{
    private static CompLayer layer()
    {
        return new CompLayer("fx_layer", "FX Layer", "solid");
    }

    private static CompEffect blur()
    {
        CompEffect effect = new CompEffect("fx1", "blur");

        effect.put("radius", 10D);
        effect.put("opacity", 0.8D);

        return effect;
    }

    @Test
    public void repeatedResolveReusesTheScratchAndNeverTouchesTheLiveEffect()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();

        layer.effects.add(effect);

        CompEffect first = layer.resolveEffect(effect, 0F);
        CompEffect second = layer.resolveEffect(effect, 0F);

        assertSame(first, second, "the per-effect scratch object is reused instead of copied");
        assertNotSame(effect, first, "the live effect must never be handed out for mutation");
        assertEquals(10F, first.valueF("radius", -1F), 1e-5F);

        /* A caller that writes into the resolved effect must not corrupt the project data, and the
         * next resolve starts from the stored values again. */
        first.put("radius", 99D);
        first.put("temporary", 1D);

        CompEffect third = layer.resolveEffect(effect, 0F);

        assertSame(first, third);
        assertEquals(10D, effect.value("radius", 0D), 1e-9D, "scratch writes must not write back");
        assertEquals(10F, third.valueF("radius", -1F), 1e-5F, "every resolve resets the scratch map");
        assertNull(third.params.get("temporary"));
    }

    @Test
    public void differentEffectsGetDifferentScratches()
    {
        CompLayer layer = layer();
        CompEffect first = blur();
        CompEffect second = new CompEffect("fx2", "glow").put("radius", 30D);

        assertNotSame(layer.resolveEffect(first, 0F), layer.resolveEffect(second, 0F),
            "two effects of the same layer must not alias one scratch");
    }

    @Test
    public void resolveAtDifferentFramesRecomputesKeyedParameters()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();
        String path = CompLayer.effectPath("fx1", "radius");

        layer.scalarChannel(path).insert(0F, 4F);
        layer.scalarChannel(path).insert(10F, 40F);

        CompEffect middle = layer.resolveEffect(effect, 5F);

        assertEquals(22F, middle.valueF("radius", -1F), 0.05F, "linear interpolation at the midpoint");
        assertEquals(10D, effect.value("radius", 0D), 1e-9D, "the stored base value is untouched");

        CompEffect start = layer.resolveEffect(effect, 0F);

        assertSame(middle, start, "the same frame slot is reused");
        assertEquals(4F, start.valueF("radius", -1F), 1e-4F, "the reused scratch is re-evaluated");
    }

    @Test
    public void resolveEffectIntoUsesTheSuppliedTarget()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();
        CompEffect target = new CompEffect();

        target.put("stale", 5D);

        CompEffect result = layer.resolveEffectInto(effect, 0F, target);

        assertSame(target, result);
        assertEquals(10F, result.valueF("radius", -1F), 1e-5F);
        assertNull(result.params.get("stale"), "the target is cleared before it is filled");

        CompEffect other = new CompEffect("fx2", "glow").put("radius", 30D);

        layer.resolveEffectInto(other, 0F, target);
        assertEquals(30F, target.valueF("radius", -1F), 1e-5F);
        assertNull(target.params.get("opacity"), "parameters of the previous effect do not leak");
    }

    @Test
    public void effectValueReadsTheStoredValueWhenNothingAnimates()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();

        assertEquals(10F, layer.effectValue(effect, "radius", 7F, 3F), 1e-6F);
        assertEquals(7F, layer.effectValue(effect, "missing", 7F, 3F), 1e-6F);
        assertEquals(7F, layer.effectValue(null, "radius", 7F, 3F), 1e-6F);
    }

    @Test
    public void effectValueAgreesWithResolveEffectForStoredAndUnstoredParameters()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();
        String radius = CompLayer.effectPath("fx1", "radius");
        String amount = CompLayer.effectPath("fx1", "amount");

        layer.scalarChannel(radius).insert(0F, 4F);
        layer.scalarChannel(radius).insert(10F, 40F);
        layer.scalarChannel(amount).insert(0F, 1F);
        layer.scalarChannel(amount).insert(10F, 3F);

        for (float frame : new float[] {0F, 2.5F, 5F, 10F})
        {
            CompEffect resolved = layer.resolveEffect(effect, frame);

            assertEquals(resolved.valueF("radius", -1F), layer.effectValue(effect, "radius", -1F, frame),
                1e-5F, "stored, animated parameter at " + frame);
            assertEquals(resolved.valueF("amount", 0F), layer.effectValue(effect, "amount", 0F, frame),
                1e-5F, "channel-only parameter at " + frame);
        }

        assertEquals(2F, layer.effectValue(effect, "amount", 0F, 5F), 0.05F);
    }

    @Test
    public void keyedStrengthWithoutAStoredValueDefaultsToFullStrength()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();
        String strength = CompLayer.effectPath("fx1", CompEffect.STRENGTH);

        layer.scalarChannel(strength).insertInheriting(0F, 0.25F);

        assertEquals(0.25F, layer.resolveEffect(effect, 0F).valueF(CompEffect.STRENGTH, 1F), 1e-5F);
        assertEquals(0.25F, layer.effectValue(effect, CompEffect.STRENGTH, 1F, 0F), 1e-5F);
    }

    @Test
    public void anEmptyChannelKeepsTheResolvedNeutralDefault()
    {
        CompLayer layer = layer();
        CompEffect effect = blur();

        /* A channel can exist with no keys (a keyframe was added and then deleted). The resolved
         * effect still materializes the parameter with its neutral default, so the direct read has
         * to agree with it instead of falling back to the caller's default. */
        layer.scalarChannel(CompLayer.effectPath("fx1", "amount"));

        assertEquals(layer.resolveEffect(effect, 0F).valueF("amount", 0F),
            layer.effectValue(effect, "amount", 18F, 0F), 1e-6F);
        assertEquals(0F, layer.effectValue(effect, "amount", 18F, 0F), 1e-6F,
            "an unknown parameter's neutral default is zero");
        assertEquals(10F, layer.effectValue(effect, "radius", 7F, 0F), 1e-6F,
            "a stored parameter keeps its stored value when the channel is empty");
    }
}
