package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pick-id arithmetic: the stencil pass writes {@code offset + replayIndex}, the click handler must
 * read back exactly that index, and a gizmo handle must never be mistaken for a replay.
 */
public class ReplayPickIdsTest
{
    private static final int GIZMO_MAX = 96;
    private static final int GIZMO_MIN = 64;

    @Test
    public void replaysStartRightAfterTheGizmoRange()
    {
        assertEquals(GIZMO_MAX + 1, ReplayPickIds.offsetFor(GIZMO_MAX));
        assertEquals(1, ReplayPickIds.offsetFor(-5),
            "id 0 is 'nothing picked', so the first usable replay id is at least 1");
    }

    @Test
    public void idsAndIndicesRoundTrip()
    {
        for (int index = 0; index < 5; index++)
        {
            int id = ReplayPickIds.idFor(index, GIZMO_MAX);

            assertEquals(index, ReplayPickIds.indexFor(id, GIZMO_MAX, 5));
        }
    }

    @Test
    public void gizmoAndEmptyPixelsAreNotReplays()
    {
        assertEquals(-1, ReplayPickIds.indexFor(0, GIZMO_MAX, 5), "no pick");
        assertEquals(-1, ReplayPickIds.indexFor(GIZMO_MAX, GIZMO_MAX, 5), "top gizmo handle");
        assertEquals(-1, ReplayPickIds.indexFor(GIZMO_MAX + 1 - 1, GIZMO_MAX, 5), "one below the replay range");
    }

    @Test
    public void idsPastTheListAreRejected()
    {
        assertEquals(-1, ReplayPickIds.indexFor(ReplayPickIds.idFor(5, GIZMO_MAX), GIZMO_MAX, 5));
        assertEquals(-1, ReplayPickIds.indexFor(ReplayPickIds.idFor(0, GIZMO_MAX), GIZMO_MAX, 0));
    }

    @Test
    public void gizmoHandleRangeIsInclusive()
    {
        assertFalse(ReplayPickIds.isGizmoHandle(GIZMO_MIN - 1, GIZMO_MIN, GIZMO_MAX));
        assertTrue(ReplayPickIds.isGizmoHandle(GIZMO_MIN, GIZMO_MIN, GIZMO_MAX));
        assertTrue(ReplayPickIds.isGizmoHandle(GIZMO_MAX, GIZMO_MIN, GIZMO_MAX));
        assertFalse(ReplayPickIds.isGizmoHandle(GIZMO_MAX + 1, GIZMO_MIN, GIZMO_MAX));
        assertFalse(ReplayPickIds.isGizmoHandle(0, GIZMO_MIN, GIZMO_MAX));
    }
}
