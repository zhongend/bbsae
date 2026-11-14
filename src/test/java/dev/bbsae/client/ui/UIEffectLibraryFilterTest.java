package dev.bbsae.client.ui;

import dev.bbsae.comp.CompEffectRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effect library search is a pure predicate over (name, id, category). Keeping it out of the
 * widget makes the "typed text but nothing filtered" regression testable without a Minecraft UI.
 */
public class UIEffectLibraryFilterTest
{
    private static CompEffectRegistry.Definition definition(String id, String name, String category)
    {
        return new CompEffectRegistry.Definition(id, name, category);
    }

    @Test
    public void nameIdAndCategoryAreAllSearchable()
    {
        CompEffectRegistry.Definition blur = definition("blur", "模糊", "模糊与锐化");

        assertTrue(UIEffectLibrary.matchesFilter(blur, "模糊"));
        assertTrue(UIEffectLibrary.matchesFilter(blur, "blur"));
        assertTrue(UIEffectLibrary.matchesFilter(blur, "BLUR"));
        assertTrue(UIEffectLibrary.matchesFilter(blur, "锐化"));
        assertFalse(UIEffectLibrary.matchesFilter(blur, "发光"));
    }

    @Test
    public void anEmptyOrBlankQueryMatchesEverything()
    {
        CompEffectRegistry.Definition blur = definition("blur", "模糊", "模糊与锐化");

        assertTrue(UIEffectLibrary.matchesFilter(blur, ""));
        assertTrue(UIEffectLibrary.matchesFilter(blur, "   "));
        assertTrue(UIEffectLibrary.matchesFilter(blur, null));
        assertFalse(UIEffectLibrary.matchesFilter(null, "blur"));
    }

    @Test
    public void nullMetadataDoesNotThrow()
    {
        assertTrue(UIEffectLibrary.matchesFilter(definition(null, null, null), ""));
        assertFalse(UIEffectLibrary.matchesFilter(definition(null, null, null), "x"));
    }

    @Test
    public void everyRegisteredEffectCanBeFoundByItsOwnName()
    {
        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            if (CompEffectRegistry.isLibraryHidden(definition.id) || definition.name == null)
            {
                continue;
            }

            assertTrue(UIEffectLibrary.matchesFilter(definition, definition.name),
                "search must find " + definition.id + " by its name");
        }
    }
}
