package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved animation preset must carry <b>motion</b>, not artwork.
 *
 * <p>The previous implementation copied every channel of the source layer and, on apply,
 * cleared the target's whole channel map. One click on "应用预设" therefore deleted the target's
 * mask/shape/effect/text animation and injected the source layer's {@code fx/} curves — which then
 * addressed effect ids the target did not own. These tests pin both directions: capture filters
 * content channels, and apply only replaces the property channels it owns.</p>
 */
public class CompAnimationPresetTest
{
    private static CompLayer propertySource()
    {
        CompLayer layer = new CompLayer("source", "Source", "solid");

        layer.x = 100F;
        layer.y = 100F;
        layer.effects.add(new CompEffect("fx9", "glow"));
        layer.scalarChannel("fx/fx9/intensity").insertInheriting(0F, 2F);
        layer.scalarChannel("mask/other/feather").insertInheriting(0F, 5F);
        layer.scalarChannel("shape/other/x").insertInheriting(0F, 6F);
        layer.scalarChannel("text/fontSize").insertInheriting(0F, 90F);
        layer.scalarChannel("x").insertInheriting(0F, 300F);
        layer.scalarChannel("x").insertInheriting(20F, 400F);
        layer.scalarChannel("rotation").insertInheriting(0F, 45F);
        layer.opacityChannel().insertInheriting(0F, 0.5F);

        return layer;
    }

    private static CompLayer contentTarget()
    {
        CompLayer target = new CompLayer("target", "Target", "solid");

        target.x = 700F;
        target.y = 500F;
        target.opacity = 1F;
        target.masks.add(new CompMask("m1"));
        target.shapes.add(new CompShape("s1", "rect"));

        CompEffect blur = new CompEffect("fx1", "blur");

        blur.put("amount", 4D);
        target.effects.add(blur);

        target.scalarChannel("mask/m1/feather").insertInheriting(0F, 12F);
        target.scalarChannel("shape/s1/x").insertInheriting(0F, 33F);
        target.scalarChannel("fx/fx1/amount").insertInheriting(0F, 4F);
        target.scalarChannel("text/fontSize").insertInheriting(0F, 80F);
        target.scalarChannel("x").insertInheriting(0F, 700F);
        target.scalarChannel("x").insertInheriting(10F, 750F);
        target.opacityChannel().insertInheriting(0F, 1F);

        return target;
    }

    @Test
    public void contentChannelsAreNeverPresetChannels()
    {
        assertTrue(CompAnimationPreset.isPresetChannel("x"));
        assertTrue(CompAnimationPreset.isPresetChannel("rotation"));
        assertTrue(CompAnimationPreset.isPresetChannel("cameraFov"));
        assertTrue(CompAnimationPreset.isPresetChannel("timeRemap"));
        assertFalse(CompAnimationPreset.isPresetChannel("fx/fx1/amount"));
        assertFalse(CompAnimationPreset.isPresetChannel("mask/m1/feather"));
        assertFalse(CompAnimationPreset.isPresetChannel("shape/s1/x"));
        assertFalse(CompAnimationPreset.isPresetChannel("text/fontSize"));
        assertFalse(CompAnimationPreset.isPresetChannel(null));
    }

    @Test
    public void captureDropsContentChannels()
    {
        CompAnimationPreset preset = CompAnimationPreset.capture("p1", "P1", propertySource());

        assertNotNull(preset);

        CompLayer stored = CompStore.gson().fromJson(preset.data, CompLayer.class);

        assertNotNull(stored);
        assertTrue(stored.channels.containsKey("x"));
        assertTrue(stored.channels.containsKey("rotation"));
        assertFalse(stored.channels.containsKey("fx/fx9/intensity"),
            "effect curves must not be captured");
        assertFalse(stored.channels.containsKey("mask/other/feather"),
            "mask curves must not be captured");
        assertFalse(stored.channels.containsKey("shape/other/x"),
            "shape curves must not be captured");
        assertFalse(stored.channels.containsKey("text/fontSize"),
            "text curves must not be captured");
    }

    @Test
    public void applyReplacesMotionAndKeepsTheTargetsOwnContentAnimation()
    {
        CompLayer target = contentTarget();
        CompAnimationPreset preset = CompAnimationPreset.capture("p1", "P1", propertySource());

        assertTrue(preset.applyTo(target));

        /* The target's content animation survives untouched. */
        assertTrue(target.hasScalarChannel("mask/m1/feather"));
        assertEquals(12F, target.scalarAt("mask/m1/feather", -1F, 0F), 1e-4F);
        assertTrue(target.hasScalarChannel("shape/s1/x"));
        assertEquals(33F, target.scalarAt("shape/s1/x", -1F, 0F), 1e-4F);
        assertTrue(target.hasScalarChannel("fx/fx1/amount"));
        assertEquals(4F, target.scalarAt("fx/fx1/amount", -1F, 0F), 1e-4F);
        assertTrue(target.hasScalarChannel("text/fontSize"));
        assertEquals(80F, target.scalarAt("text/fontSize", -1F, 0F), 1e-4F);
        assertNotNull(target.getEffect("fx1"), "the target's effect instance is untouched");

        /* The source's content channels are not injected. */
        assertFalse(target.hasScalarChannel("fx/fx9/intensity"),
            "the source layer's effect curves must not follow the preset");
        assertFalse(target.hasScalarChannel("mask/other/feather"));
        assertFalse(target.hasScalarChannel("shape/other/x"));
        assertNull(target.getEffect("fx9"));

        /* The motion that a preset is for does arrive. */
        assertEquals(300F, target.scalarAt("x", 0F, 0F), 1e-4F);
        assertEquals(400F, target.scalarAt("x", 0F, 20F), 1e-4F);
        assertEquals(45F, target.scalarAt("rotation", 0F, 0F), 1e-4F);
        assertEquals(0.5F, target.opacityFrame(0F), 1e-4F);
    }

    @Test
    public void aLegacyPresetContainingContentChannelsStillCannotInjectThem()
    {
        /* A preset saved before the fix contains every channel of its source layer. Applying it
         * must filter on the way in as well, otherwise old project files keep corrupting layers. */
        CompLayer source = propertySource();

        source.saveTracks();

        CompAnimationPreset legacy = new CompAnimationPreset("legacy", "Legacy", CompStore.gson().toJson(source));
        CompLayer target = contentTarget();

        assertTrue(legacy.applyTo(target));

        assertTrue(target.hasScalarChannel("fx/fx1/amount"), "the target's own effect curve survives");
        assertEquals(4F, target.scalarAt("fx/fx1/amount", -1F, 0F), 1e-4F);
        assertTrue(target.hasScalarChannel("mask/m1/feather"));
        assertTrue(target.hasScalarChannel("shape/s1/x"));
        assertTrue(target.hasScalarChannel("text/fontSize"));
        assertFalse(target.hasScalarChannel("fx/fx9/intensity"),
            "a legacy preset's fx channel must be filtered out on apply");
        assertFalse(target.hasScalarChannel("mask/other/feather"));
        assertEquals(300F, target.scalarAt("x", 0F, 0F), 1e-4F);
    }

    @Test
    public void anEmptyPresetIsRejected()
    {
        CompLayer target = contentTarget();

        assertFalse(new CompAnimationPreset("empty", "Empty", "").applyTo(target));
        assertFalse(new CompAnimationPreset("empty", "Empty", "  ").applyTo(target));
        assertFalse(new CompAnimationPreset("empty", "Empty", "").applyTo(null));
    }
}
