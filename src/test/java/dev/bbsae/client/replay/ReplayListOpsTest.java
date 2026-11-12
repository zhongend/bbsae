package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selection bookkeeping across replay add/remove/reorder.
 *
 * <p>Uses values whose {@code equals} deliberately ignores identity ("twin" boxes) to pin the
 * reason this class exists: BBS' {@code Replay} extends {@code ValueGroup} and compares contents,
 * so an {@code indexOf}/{@code contains} lookup can address a replay the user never selected.</p>
 */
public class ReplayListOpsTest
{
    /** Equal by value, distinct by identity — the same trap {@code ValueGroup.equals} creates. */
    private static final class Box
    {
        final String value;

        Box(String value)
        {
            this.value = value;
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof Box box && this.value.equals(box.value);
        }

        @Override
        public int hashCode()
        {
            return this.value.hashCode();
        }
    }

    @Test
    public void identicalIndexIgnoresAnEqualButDifferentElement()
    {
        Box selected = new Box("same");
        Box twin = new Box("same");
        List<Box> list = Arrays.asList(twin, selected);

        assertEquals(-1, ReplayListOps.indexOfIdentical(List.of(twin), selected), "twin is not the selection");
        assertEquals(1, ReplayListOps.indexOfIdentical(list, selected));
        assertTrue(ReplayListOps.containsIdentical(list, selected));
        assertFalse(ReplayListOps.containsIdentical(List.of(twin), selected));
    }

    @Test
    public void identicalIndexHandlesNullAndEmpty()
    {
        assertEquals(-1, ReplayListOps.indexOfIdentical(null, new Box("x")));
        assertEquals(-1, ReplayListOps.indexOfIdentical(List.of(), new Box("x")));
        assertEquals(-1, ReplayListOps.indexOfIdentical(List.of(new Box("x")), null));
    }

    @Test
    public void elementAtClampsAndRejectsOutOfRange()
    {
        List<String> list = List.of("a", "b");

        assertEquals("a", ReplayListOps.elementAt(list, 0));
        assertEquals("b", ReplayListOps.elementAt(list, 1));
        assertNull(ReplayListOps.elementAt(list, 2));
        assertNull(ReplayListOps.elementAt(list, -1));
        assertNull(ReplayListOps.elementAt(null, 0));
    }

    @Test
    public void clampIndexCoversEmptyAndOutOfRange()
    {
        assertEquals(-1, ReplayListOps.clampIndex(0, 0));
        assertEquals(-1, ReplayListOps.clampIndex(-5, 0));
        assertEquals(0, ReplayListOps.clampIndex(-5, 3));
        assertEquals(1, ReplayListOps.clampIndex(1, 3));
        assertEquals(2, ReplayListOps.clampIndex(99, 3));
    }

    @Test
    public void sameOrderComparesIdentityAndOrder()
    {
        String a = "a";
        String b = "b";

        assertTrue(ReplayListOps.sameOrder(List.of(a, b), List.of(a, b)));
        assertFalse(ReplayListOps.sameOrder(List.of(a, b), List.of(b, a)));
        assertFalse(ReplayListOps.sameOrder(List.of(a), List.of(a, b)));
        assertFalse(ReplayListOps.sameOrder(null, List.of(a)));
        assertTrue(ReplayListOps.sameOrder(null, null));
    }

    @Test
    public void aReorderKeepsTheSelectedObject()
    {
        Box a = new Box("a");
        Box b = new Box("b");
        Box c = new Box("c");
        List<Box> previous = new ArrayList<>(List.of(a, b, c));

        Box resolved = ReplayListOps.selectionAfterChange(previous, List.of(c, b, a), b);

        assertSame(b, resolved, "the selected replay stays selected when reordered");
    }

    @Test
    public void removingAnElementSelectsItsNeighbourNotAnEqualTwin()
    {
        Box first = new Box("first");
        Box selected = new Box("selected");
        Box twin = new Box("selected");
        Box last = new Box("last");
        List<Box> previous = new ArrayList<>(List.of(first, selected, last));
        List<Box> current = new ArrayList<>(List.of(first, twin, last));

        Box resolved = ReplayListOps.selectionAfterChange(previous, current, selected);

        assertSame(twin, resolved, "the neighbour at the old index is the honest fallback");
        assertFalse(ReplayListOps.containsIdentical(current, selected));
    }

    @Test
    public void addingAnElementKeepsTheSelectedObject()
    {
        Box a = new Box("a");
        Box b = new Box("b");
        Box added = new Box("added");
        List<Box> previous = new ArrayList<>(List.of(a, b));

        assertSame(b, ReplayListOps.selectionAfterChange(previous, List.of(added, a, b), b));
    }

    @Test
    public void anEmptyResultClearsTheSelection()
    {
        Box a = new Box("a");

        assertNull(ReplayListOps.selectionAfterChange(List.of(a), Collections.emptyList(), a));
        assertNull(ReplayListOps.selectionAfterChange(null, null, null));
    }

    @Test
    public void aNullSelectionFallsBackToTheFirstReplay()
    {
        Box a = new Box("a");
        Box b = new Box("b");

        assertSame(a, ReplayListOps.selectionAfterChange(List.of(a, b), List.of(a, b), null));
    }
}
