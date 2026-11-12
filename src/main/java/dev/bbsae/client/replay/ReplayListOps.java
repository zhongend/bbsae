package dev.bbsae.client.replay;

import java.util.List;

/**
 * List bookkeeping for the replay selection and the pick-id mapping.
 *
 * <h2>Why identity, not {@code equals}</h2>
 * <p>{@code Replay} extends BBS' {@code ValueGroup}, whose {@code equals} compares <b>contents</b>.
 * {@code List.indexOf(replay)} therefore reports a different replay that happens to hold identical
 * keyframes and form as "the same one" — the panel then keeps the wrong selection after a remove
 * or a reorder, and the 3D pick maps an id to the wrong actor. Every selection/index operation in
 * the replay package goes through this class so the comparison is {@code ==} (the object the user
 * actually picked), with an explicit nearest-index fallback for "the selected replay was deleted".</p>
 *
 * <p>Generic on purpose: the rules are testable with plain objects, no BBS/Minecraft class
 * loading (BBS' static initialisers require the game bootstrap, which the headless test JVM does
 * not have).</p>
 */
public final class ReplayListOps
{
    private ReplayListOps()
    {}

    /** {@code List.indexOf} by reference. Returns -1 when absent or when either side is null. */
    public static <T> int indexOfIdentical(List<T> list, T element)
    {
        if (list == null || element == null)
        {
            return -1;
        }

        for (int i = 0; i < list.size(); i++)
        {
            if (list.get(i) == element)
            {
                return i;
            }
        }

        return -1;
    }

    public static <T> boolean containsIdentical(List<T> list, T element)
    {
        return indexOfIdentical(list, element) >= 0;
    }

    public static <T> T elementAt(List<T> list, int index)
    {
        return list != null && index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** Clamps a list index into {@code [0, size)}; returns -1 for an empty list. */
    public static int clampIndex(int index, int size)
    {
        if (size <= 0)
        {
            return -1;
        }

        return Math.max(0, Math.min(size - 1, index));
    }

    /** True when both lists hold the same objects in the same order (identity comparison). */
    public static <T> boolean sameOrder(List<T> a, List<T> b)
    {
        if (a == b)
        {
            return true;
        }

        if (a == null || b == null || a.size() != b.size())
        {
            return false;
        }

        for (int i = 0; i < a.size(); i++)
        {
            if (a.get(i) != b.get(i))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Keeps the user's selection across a list mutation (add/remove/reorder).
     *
     * <p>If the selected object is still present <b>by identity</b> it stays selected even when its
     * index changed (reorder). If it was removed, the old index is clamped onto the new list — the
     * neighbour the user sees under the pointer — instead of silently jumping to the first replay
     * or, worse, to an equal-valued replay the user never picked.</p>
     */
    public static <T> T selectionAfterChange(List<T> previous, List<T> current, T selected)
    {
        if (current == null || current.isEmpty())
        {
            return null;
        }

        if (selected != null && containsIdentical(current, selected))
        {
            return selected;
        }

        int previousIndex = indexOfIdentical(previous, selected);
        T candidate = elementAt(current, clampIndex(previousIndex, current.size()));

        return candidate != null ? candidate : current.get(0);
    }
}
