package dev.bbsae.client.ui;

import dev.bbsae.client.render.EffectRenderer;
import dev.bbsae.comp.CompEffectRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effect library must read like one catalogue, not two.
 *
 * <p>After the reference-compatible batch landed there were two entries called 高斯模糊 (the old
 * {@code blur} and {@code am_gaussian_blur}), two 万花筒, two 镜像 and so on: same control set,
 * same name, different category. The classic ids stay registered so old projects keep rendering,
 * but they are hidden from the library. This test pins both halves of that contract: the visible
 * names are unique, and every hidden id still has a renderer and an AM successor.</p>
 */
public class EffectLibraryNamingTest
{
    @Test
    public void visibleEffectNamesAreUnique()
    {
        Map<String, String> names = new LinkedHashMap<>();

        for (CompEffectRegistry.Definition definition : CompEffectRegistry.all())
        {
            if (CompEffectRegistry.isLibraryHidden(definition.id))
            {
                continue;
            }

            String previous = names.put(definition.name, definition.id);

            assertTrue(previous == null,
                "duplicate visible effect name '" + definition.name + "': " + previous + " and " + definition.id);
        }
    }

    @Test
    public void hiddenIdsStillRenderAndHaveASuccessor()
    {
        String[][] pairs =
        {
            {"blur", "am_gaussian_blur"},
            {"box_blur", "am_box_blur"},
            {"radial_blur", "am_zoom_blur"},
            {"polar_coordinates", "am_polar"},
            {"displacement", "am_displace_map"},
            {"kaleidoscope", "am_kaleidoscope"},
            {"mirror", "am_mirror_warp"},
            {"grid", "am_grid_lines"},
            {"colorama", "am_palette_map"},
            {"stroke", "am_outline"},
            {"unsharp_mask", "am_unsharp_mask"},
        };

        for (String[] pair : pairs)
        {
            assertTrue(CompEffectRegistry.isLibraryHidden(pair[0]), pair[0] + " should be hidden from the library");
            assertNotNull(CompEffectRegistry.get(pair[0]), "hidden effect must stay registered: " + pair[0]);
            assertTrue(EffectRenderer.supports(pair[0]), "hidden effect must stay renderable: " + pair[0]);
            assertNotNull(CompEffectRegistry.get(pair[1]), "missing successor: " + pair[1]);
            assertTrue(EffectRenderer.supports(pair[1]), "successor has no renderer: " + pair[1]);
        }
    }
}
