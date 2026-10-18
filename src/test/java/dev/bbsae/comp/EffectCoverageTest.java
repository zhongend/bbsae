package dev.bbsae.comp;

import dev.bbsae.client.render.EffectRenderer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the catalogue/renderer contract. This was a real class of bug in this project: a
 * definition could be added to {@link CompEffectRegistry} and appear in the effect library while
 * {@code EffectRenderer.apply} silently returned the input unchanged.
 */
public class EffectCoverageTest
{
    @Test
    public void everyRegisteredEffectHasARenderer()
    {
        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            assertTrue(EffectRenderer.supports(definition.id),
                "effect '" + definition.id + "' (" + definition.name + ") has no renderer branch");
        }
    }

    @Test
    public void everyRendererTypeIsRegistered()
    {
        for (String type : EffectRenderer.supportedTypes())
        {
            assertNotNull(CompEffectRegistry.get(type),
                "renderer supports '" + type + "' but the registry has no definition for it");
        }
    }

    @Test
    public void everyDefinitionHasAtLeastOneParameter()
    {
        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            assertTrue(!definition.params.isEmpty() || !definition.colorParams.isEmpty(),
                "effect '" + definition.id + "' has no editable parameter");
        }
    }

    @Test
    public void registryDefinitionsAreSelfConsistent()
    {
        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();

        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            assertTrue(ids.add(definition.id), "duplicate effect id: " + definition.id);
            assertTrue(definition.name != null && !definition.name.isBlank(), "blank name: " + definition.id);
            assertTrue(definition.category != null && !definition.category.isBlank(), "blank category: " + definition.id);
            categories.add(definition.category);

            Set<String> keys = new HashSet<>();

            for (CompEffectRegistry.Param param : definition.params)
            {
                assertTrue(keys.add(param.key), definition.id + " has duplicate parameter key " + param.key);
                assertTrue(param.min <= param.max, definition.id + "/" + param.key + " has min > max");
                assertTrue(param.defaultValue >= param.min && param.defaultValue <= param.max,
                    definition.id + "/" + param.key + " default " + param.defaultValue + " is outside ["
                        + param.min + ", " + param.max + "]");
                assertTrue(param.step > 0F, definition.id + "/" + param.key + " has non-positive step");
                assertTrue(param.label != null && !param.label.isBlank(), definition.id + "/" + param.key + " has no label");
            }

            for (CompEffectRegistry.ColorParam param : definition.colorParams)
            {
                assertTrue(keys.add(param.key), definition.id + " has duplicate parameter key " + param.key);
                assertTrue(param.label != null && !param.label.isBlank(), definition.id + "/" + param.key + " has no label");
            }
        }

        assertTrue(categories.size() >= 8, "unexpectedly few effect categories: " + categories);
    }

    @Test
    public void alightMotionEffectsRoundTripThroughJson()
    {
        for (String id : new String[] {
            "scanlines", "glitch", "vhs", "halftone", "emboss", "mirror", "kaleidoscope"
        })
        {
            CompEffect effect = CompEffectRegistry.create(id, 3);

            assertNotNull(CompEffectRegistry.get(id), "missing definition " + id);

            String json = CompStore.gson().toJson(effect);
            CompEffect copy = CompStore.gson().fromJson(json, CompEffect.class);

            assertNotNull(copy, "could not deserialize " + id);
            assertEquals(effect.type, copy.type, id + " type");
            assertEquals(effect.enabled, copy.enabled, id + " enabled");
            assertEquals(effect.params, copy.params, id + " params");
            assertEquals(effect.colors, copy.colors, id + " colors");
        }
    }
}
