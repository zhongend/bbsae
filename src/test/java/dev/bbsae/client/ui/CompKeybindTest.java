package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shortcut table is data, so it can be audited without a Minecraft client.
 *
 * <p>These tests catch the two regressions that would make the rebinding UI lie: duplicate action
 * ids (two rows writing the same override) and a command with no default in either scheme (the
 * editor would show "未绑定" for something the editor actually uses).</p>
 */
public class CompKeybindTest
{
    @Test
    public void actionIdsAreUnique()
    {
        Set<String> ids = new HashSet<>();

        for (CompKeybinds.Action action : CompKeybinds.actions())
        {
            assertNotNull(action.id);
            assertTrue(ids.add(action.id), "duplicate shortcut action: " + action.id);
        }

        assertTrue(ids.size() >= 25);
    }

    @Test
    public void everyActionHasADefaultInAtLeastOneScheme()
    {
        for (CompKeybinds.Action action : CompKeybinds.actions())
        {
            assertTrue(!action.premiere.isEmpty() || !action.bbs.isEmpty(),
                "no default shortcut for " + action.id);
        }
    }

    @Test
    public void easingIsBounded()
    {
        for (float x = 0F; x <= 1F; x += 0.05F)
        {
            assertTrue(UiMotion.easeOut(x) >= 0F && UiMotion.easeOut(x) <= 1.0001F);
            assertTrue(UiMotion.easeInOut(x) >= 0F && UiMotion.easeInOut(x) <= 1.0001F);
        }

        assertEquals(0F, UiMotion.easeOut(0F), 0.0001F);
        assertEquals(1F, UiMotion.easeOut(1F), 0.0001F);
        assertEquals(0F, UiMotion.easeInOut(0F), 0.0001F);
        assertEquals(1F, UiMotion.easeInOut(1F), 0.0001F);
    }

    @Test
    public void colorLerpPreservesChannels()
    {
        int result = UiMotion.lerpColor(0xFF102030, 0xFFA0B0C0, 0.5F);

        assertEquals(0xFF, result >>> 24);
        assertTrue((result >> 16 & 0xFF) > 0x10 && (result >> 16 & 0xFF) < 0xA0);
        assertTrue((result >> 8 & 0xFF) > 0x20 && (result >> 8 & 0xFF) < 0xB0);
    }
}
