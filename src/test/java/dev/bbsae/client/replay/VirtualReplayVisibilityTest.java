package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BBS film editor must not list bbsae's virtual replay rows.
 *
 * <p>The filter is UI-only: it never mutates the film's replay list, and while bbsae's replay mode
 * owns the list it must pass everything through so the compositor can still edit those records.</p>
 */
public class VirtualReplayVisibilityTest
{
    @Test
    public void virtualRowsAreRemovedWithoutChangingTheSource()
    {
        List<String> all = new ArrayList<>(List.of("real", "virtual:1", "virtual:2"));
        Predicate<String> virtual = (value) -> value.startsWith("virtual:");

        List<String> visible = VirtualReplayVisibility.filter(all, virtual, false);

        assertEquals(List.of("real"), visible);
        assertEquals(3, all.size(), "the film's own list must never be modified by a UI filter");
        assertFalse(visible == all);
    }

    @Test
    public void allowingVirtualKeepsTheOriginalList()
    {
        List<String> all = new ArrayList<>(List.of("real", "virtual:1"));

        assertSame(all, VirtualReplayVisibility.filter(all, (value) -> value.startsWith("virtual:"), true));
    }

    @Test
    public void anEmptyFilmIsSafe()
    {
        assertEquals(List.of(), VirtualReplayVisibility.filter(List.of(), (value) -> true, false));
        assertSame(null, VirtualReplayVisibility.filter(null, (value) -> true, false));
    }

    @Test
    public void realRowsAreNeverHidden()
    {
        List<String> all = new ArrayList<>(List.of("donkey", "player"));

        assertEquals(all, VirtualReplayVisibility.filter(all, (value) -> value.startsWith("virtual:"), false));
        assertTrue(all.contains("donkey"));
    }
}
