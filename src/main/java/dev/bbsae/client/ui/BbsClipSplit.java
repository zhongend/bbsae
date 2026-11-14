package dev.bbsae.client.ui;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Premiere-style split for BBS camera-timeline clips.
 *
 * <p>BBS already implements the hard half: {@link Clip#breakDown(int)} copies a clip and lets each
 * clip type advance its own source offset (audio and video do this correctly). What was missing is
 * the PR gesture — Ctrl+K at the playhead — and a menu entry next to the existing Alt+C. The
 * compositor's {@code CompositionClip} additionally needed a {@code breakDownClip} override, which
 * lives on the clip itself; this class only drives the timeline.</p>
 *
 * <p>Semantics match BBS's own cut and the compositor track editor: selected clips win, otherwise
 * every clip crossing the playhead is split; a cut exactly on a clip edge is a no-op so the undo
 * stack does not get an empty step.</p>
 */
public final class BbsClipSplit
{
    public static final String ACTION_ID = "bbsae_clip_split";
    /**
     * One combo instance shared by the key handler and BBS's keybind settings, so editing the
     * shortcut in the settings screen changes the actual razor key.
     */
    public static final KeyCombo CLIP_SPLIT = new KeyCombo(ACTION_ID, IKey.constant("在播放头切分"),
        GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_LEFT_CONTROL).categoryKey("camera");

    private BbsClipSplit()
    {}

    /** Registers the shortcut and the context-menu action on one BBS clip timeline. */
    public static void install(UIClips ui, IntSupplier cursor)
    {
        ui.keys().register(CLIP_SPLIT, () -> split(ui, cursor.getAsInt()))
            .inside()
            .category(UIClips.KEYS_CATEGORY);

        /* UIElement.context(Consumer) appends to the existing options, so BBS's own menu stays. */
        ui.context((menu) -> menu.action(Icons.CUT, IKey.constant("在播放头切分（Ctrl+K）"),
            () -> split(ui, cursor.getAsInt())));
    }

    /**
     * Splits clips crossing {@code tick}.
     *
     * @return the number of new right halves; 0 means nothing was touched
     */
    public static int split(UIClips ui, int tick)
    {
        if (ui == null)
        {
            return 0;
        }

        Clips clips = ui.getClips();

        if (clips == null)
        {
            return 0;
        }

        List<Clip> chosen = ui.getClipsFromSelection();

        if (chosen == null || chosen.isEmpty())
        {
            chosen = new ArrayList<>(clips.get());
        }

        /* Collect first: mutating the clip list while iterating it, or asking for an undo
         * checkpoint before knowing that at least one clip is really split, are both avoidable. */
        List<Clip> targets = new ArrayList<>();

        for (Clip clip : chosen)
        {
            if (clip == null)
            {
                continue;
            }

            int start = clip.tick.get();
            int duration = clip.duration.get();

            if (tick > start && tick < start + duration)
            {
                targets.add(clip);
            }
        }

        if (targets.isEmpty())
        {
            return 0;
        }

        List<Clip> rights = new ArrayList<>();
        Clip original = null;

        clips.preNotify();

        try
        {
            for (Clip clip : targets)
            {
                Clip right = clip.breakDown(tick - clip.tick.get());

                if (right == null)
                {
                    continue;
                }

                /* breakDown() returns the right half with the old tick; the left half loses the
                 * right half's duration, so this advances the copy exactly to the playhead. */
                clip.duration.set(clip.duration.get() - right.duration.get());
                right.tick.set(right.tick.get() + clip.duration.get());
                clips.addClip(right);
                rights.add(right);
                original = clip;
            }
        }
        finally
        {
            clips.postNotify();
        }

        for (Clip right : rights)
        {
            ui.addSelected(right);
        }

        if (original != null)
        {
            ui.addSelected(original);
        }

        return rights.size();
    }
}
