package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer library's catalogue.
 *
 * <p>The library is the only way a user adds a layer now, so a missing or duplicated entry is a
 * feature that cannot be reached. These checks are cheap and they fail before a build ships a
 * catalogue that disagrees with itself.</p>
 */
public class LayerCatalogTest
{
    @Test
    public void everyIdIsUniqueAndEveryCategoryHasEntries()
    {
        Set<String> ids = new HashSet<>();

        for (LayerCatalog.Entry entry : LayerCatalog.all())
        {
            assertTrue(ids.add(entry.id), "duplicate layer id: " + entry.id);
            assertNotNull(entry.name);
            assertFalse(entry.name.isBlank(), "layer " + entry.id + " has no name");
            assertFalse(entry.description.isBlank(), "layer " + entry.id + " has no description");
        }

        for (String category : LayerCatalog.CATEGORIES)
        {
            assertFalse(LayerCatalog.byCategory(category).isEmpty(), "empty category: " + category);
        }
    }

    @Test
    public void lookupAndSearchAgreeWithTheList()
    {
        for (LayerCatalog.Entry entry : LayerCatalog.all())
        {
            assertEquals(entry, LayerCatalog.get(entry.id));
        }

        /* Search is case-insensitive and matches the id, the name and the description. */
        assertFalse(LayerCatalog.search("TEXT").isEmpty(), "search must be case insensitive");
        assertFalse(LayerCatalog.search("蒙版").isEmpty(), "search must look inside descriptions");
        assertTrue(LayerCatalog.search("zzzz-no-such-layer").isEmpty());

        assertEquals(LayerCatalog.all().size(), LayerCatalog.search("").size());
    }

    @Test
    public void theReplayEntityLayerIsReachable()
    {
        /* Round 25's depth-keyed layer must be in the library, not only in the renderer. */
        assertNotNull(LayerCatalog.get("replay"));
        assertEquals(LayerCatalog.MEDIA, LayerCatalog.get("replay").category);
    }
}
