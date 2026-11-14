package dev.bbsae.client.replay;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Hides bbsae's virtual replays from the original BBS film editor.
 *
 * <p>A bbsae composition keeps one virtual {@link Replay} per entity layer inside the film because
 * the embedded replay editor borrows BBS's own replay list widget. Those records are an
 * implementation detail of the compositor, but BBS's film editor lists every replay in
 * {@code Film.replays}: one preview row each, selectable, and PageUp/Down may land on them. That is
 * the reported "compositor layers appear in the BBS editor, eat space and are easy to click by
 * mistake".</p>
 *
 * <p>The fix is a UI-only visibility layer. The data stays in the film (save/undo/rendering are
 * untouched); only the rows BBS builds for its editor are filtered. During bbsae's replay mode the
 * panel is registered as borrowed, so the same widget keeps showing exactly the virtual rows — the
 * compositor must still be able to edit them.</p>
 *
 * <p>The comparison is a static registry because BBS has one film panel and one client thread. A
 * stale entry would be harmless (it would only show extra rows), and it is cleared on page leave
 * and replaced on every bind.</p>
 */
public final class VirtualReplayVisibility
{
    private static UIFilmPanel borrowed;
    /** Film explicitly revealed for the original replay editor; null = none. */
    private static String revealedFilmId;

    private VirtualReplayVisibility()
    {}

    /** Marks the panel whose replay list is currently owned by bbsae's replay mode. */
    public static void borrow(UIFilmPanel panel)
    {
        borrowed = panel;
        revealedFilmId = null;
    }

    /** Gives the panel back to the original BBS film editor. */
    public static void release(UIFilmPanel panel)
    {
        if (borrowed == panel)
        {
            borrowed = null;
        }
    }

    /** Optionally reveals virtual replays for one film in the original editor. */
    public static void revealFor(Film film)
    {
        revealedFilmId = film == null ? null : film.getId();
    }

    public static boolean isBorrowed(UIFilmPanel panel)
    {
        return panel != null && borrowed == panel;
    }

    /** True while virtual replays may be listed in the original editor for this film. */
    public static boolean allowsVirtual(Film film)
    {
        if (borrowed != null)
        {
            return true;
        }

        String id = film == null ? null : film.getId();

        return id != null && id.equals(revealedFilmId);
    }

    /** The rows the BBS replay list should build for this film. Never mutates {@code all}. */
    public static List<Replay> visible(List<Replay> all, Film film)
    {
        if (all == null || allowsVirtual(film))
        {
            return all;
        }

        List<Replay> out = new ArrayList<>(all.size());

        for (Replay replay : all)
        {
            if (!CompReplayBridge.isVirtual(replay))
            {
                out.add(replay);
            }
        }

        return out;
    }

    /**
     * Pure filter seam used by the tests: filters {@code all} only when {@code allow} is false.
     * The input list is never modified.
     */
    public static <T> List<T> filter(List<T> all, Predicate<T> hidden, boolean allow)
    {
        if (all == null || allow)
        {
            return all;
        }

        List<T> out = new ArrayList<>(all.size());

        for (T value : all)
        {
            if (!hidden.test(value))
            {
                out.add(value);
            }
        }

        return out;
    }
}
