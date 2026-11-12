package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effect ids are handed out by {@link CompStore#uniqueEffectId(CompLayer)}.
 *
 * <p>Deleting an effect keeps its {@code fx/<id>/...} channels on purpose (undo and "the curve may
 * come back" both need them), but the old allocator only looked at the effect list. The very next
 * added effect got the deleted id back and silently adopted the orphaned animation: a brand-new
 * effect started out animated with the removed one's keys. The allocator must treat "still has
 * channels" as "id is taken", and the deletion path can reuse the same predicate.</p>
 */
public class CompStoreEffectIdTest
{
    @Test
    public void aFreshLayerStartsAtFx1()
    {
        assertEquals("fx1", CompStore.uniqueEffectId(new CompLayer("layer", "Layer", "solid")));
    }

    @Test
    public void existingEffectIdsAreSkipped()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.effects.add(new CompEffect("fx1", "blur"));

        assertEquals("fx2", CompStore.uniqueEffectId(layer));
    }

    @Test
    public void orphanedEffectChannelsKeepTheirIdOutOfTheAllocator()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        /* The effect was deleted; its animation was deliberately kept. */
        layer.scalarChannel("fx/fx1/amount").insertInheriting(0F, 4F);
        layer.scalarChannel("fx/fx2/amount").insertInheriting(0F, 5F);

        assertNotEquals("fx1", CompStore.uniqueEffectId(layer));
        assertNotEquals("fx2", CompStore.uniqueEffectId(layer));
        assertEquals("fx3", CompStore.uniqueEffectId(layer));
    }

    @Test
    public void deletingAnEffectDoesNotFreeItsIdWhileItsCurvesExist()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        CompEffect effect = new CompEffect("fx1", "blur");

        layer.effects.add(effect);
        layer.scalarChannel("fx/fx1/amount").insertInheriting(0F, 4F);

        layer.effects.remove(effect);

        assertEquals("fx2", CompStore.uniqueEffectId(layer),
            "the old id must not be reused just because the effect instance is gone");
    }

    @Test
    public void hasEffectChannelsMatchesOnlyTheExactId()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.scalarChannel("fx/fx1/amount").insertInheriting(0F, 4F);

        assertTrue(CompStore.hasEffectChannels(layer, "fx1"));
        assertFalse(CompStore.hasEffectChannels(layer, "fx10"),
            "fx10 must not match the fx/fx1/ prefix");
        assertFalse(CompStore.hasEffectChannels(layer, "fx"));
        assertFalse(CompStore.hasEffectChannels(layer, ""));
        assertFalse(CompStore.hasEffectChannels(layer, null));
        assertFalse(CompStore.hasEffectChannels(null, "fx1"));
    }

    @Test
    public void theSerializedChannelMapCountsEvenBeforeTracksAreLoaded()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        /* A freshly deserialized layer has the data string but no runtime channel yet. */
        layer.channels.put("fx/fx1/amount", "{\"k\":[]}");

        assertTrue(CompStore.hasEffectChannels(layer, "fx1"));
        assertEquals("fx2", CompStore.uniqueEffectId(layer));
    }
}
