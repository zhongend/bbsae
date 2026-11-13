package dev.bbsae.client.replay;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;

import java.util.List;

/**
 * Connects bbsae composition replay layers to the BBS film replays the replay editor edits.
 *
 * <h2>The duplicated state this removes</h2>
 * <p>A bbsae replay layer used to be a self-contained comp object: its model lived in
 * {@code CompLayer.formData} and its animation lived in the layer transform channels. The BBS
 * replay editor, on the other hand, edits {@link Replay} objects in {@code Film.replays}. Adding a
 * model in the compositor therefore created nothing the replay editor could see, and the editor
 * correctly reported "no replay".</p>
 *
 * <p>The bridge gives every independent bbsae replay layer its own stable virtual
 * {@link Replay} record. The layer's form is copied once into the replay, and the compositor
 * renders that replay through its camera (see {@code ReplayActorPass}). The replay editor lists
 * only these virtual records — not the film's recorded real-world actors — and their K-frames are
 * immediately visible in the composition without a second sync path.</p>
 *
 * <p>The layer transform remains a whole-actor offset (position/rotation/scale of the actor in
 * the composition); the replay's own keyframes animate the actor inside that offset. This is why
 * a layer can be moved like any other bbsae layer while its gait/pose still comes from BBS.</p>
 */
public final class CompReplayBridge
{
    /**
     * Invisible prefix on a virtual replay's label.
     *
     * <p>The BBS replay list is a text filter over the film's replay list. This marker lets the
     * embedded replay editor show only replays created from bbsae composition models and hide
     * the film's real-world replays, without changing BBS' list class. It is zero-width, so the
     * row still reads as the user's model name.</p>
     */
    public static final String VIRTUAL_MARKER = "\u200B";

    /** Ids known to be virtual this session, so renaming a replay cannot unmark it. */
    private static final java.util.Set<String> VIRTUAL_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CompReplayBridge()
    {}

    /** True when this replay belongs to the bbsae virtual world rather than the live film. */
    public static boolean isVirtual(Replay replay)
    {
        if (replay == null)
        {
            return false;
        }

        if (replay.label.get() != null && replay.label.get().contains(VIRTUAL_MARKER))
        {
            return true;
        }

        return replay.getId() != null && VIRTUAL_IDS.contains(replay.getId());
    }

    /** Ensure the marker is present (and at the front) on a virtual replay's label. */
    public static void markVirtual(Replay replay)
    {
        if (replay == null)
        {
            return;
        }

        if (replay.getId() != null)
        {
            VIRTUAL_IDS.add(replay.getId());
        }

        String label = replay.label.get() == null ? "" : replay.label.get();

        if (!label.contains(VIRTUAL_MARKER))
        {
            replay.label.set(VIRTUAL_MARKER + label);
        }
    }

    /** The user-facing name, without the internal virtual-world marker. */
    public static String displayName(Replay replay)
    {
        String label = replay == null || replay.label.get() == null ? "" : replay.label.get();

        return label.replace(VIRTUAL_MARKER, "");
    }

    /** The replay this layer points at, or null when it is unlinked/missing. */
    public static Replay find(Film film, CompLayer layer)
    {
        if (film == null || layer == null || !layer.hasLinkedReplay())
        {
            return null;
        }

        return film.replays.getById(layer.replayId);
    }

    /**
     * Finds or creates the BBS replay for an independent bbsae replay layer.
     *
     * <p>Creation is deliberately conservative: the replay starts with the layer's form, name and
     * an enabled state, and its world position stays at the origin. The layer's
     * {@code replayAnchor*} is zero for a fresh link, which means "the actor starts exactly at the
     * layer's position"; moving the replay in the editor then offsets it in composition pixels.</p>
     */
    public static Replay ensure(Film film, CompDocument doc, CompLayer layer)
    {
        if (film == null || layer == null || !layer.isEntityLayer() || !layer.independent)
        {
            return null;
        }

        Replay replay = find(film, layer);

        if (replay == null)
        {
            replay = film.replays.addReplay();
            layer.replayId = replay.getId();
            layer.replayAnchorX = 0F;
            layer.replayAnchorY = 0F;
            layer.replayAnchorZ = 0F;

            replay.label.set(layer.name == null ? "" : layer.name);
            markVirtual(replay);
            replay.enabled.set(false);
            replay.relative.set(false);
            replay.shadow.set(true);

            syncFormToReplay(layer, replay);
        }
        else
        {
            /* An older project can carry a layer form while its linked replay was saved without
             * one (or vice versa). Fill only the empty side: overwriting a valid replay form here
             * would silently undo a form chosen in the replay editor. */
            if (replay.form.get() == null)
            {
                syncFormToReplay(layer, replay);
            }

            if (replay.label.get() == null || replay.label.get().isBlank())
            {
                replay.label.set(layer.name == null ? "" : layer.name);
                markVirtual(replay);
            }
            else
            {
                markVirtual(replay);
            }
        }

        return replay;
    }

    /** Toggles the virtual replays' enabled flag without touching any real film replay. */
    public static int setVirtualEnabled(Film film, boolean enabled)
    {
        if (film == null)
        {
            return 0;
        }

        int changed = 0;

        for (Replay replay : film.replays.getList())
        {
            if (isVirtual(replay) && Boolean.TRUE.equals(replay.enabled.get()) != enabled)
            {
                replay.enabled.set(enabled);
                changed += 1;
            }
        }

        return changed;
    }

    /** Ensures every independent bbsae replay layer has a BBS replay; returns how many are linked. */
    public static int ensureAll(Film film, CompDocument doc)
    {
        if (film == null || doc == null)
        {
            return 0;
        }

        int linked = 0;

        for (CompLayer layer : doc.layers)
        {
            if (layer != null && layer.isEntityLayer() && layer.independent)
            {
                if (ensure(film, doc, layer) != null)
                {
                    linked += 1;
                }
            }
        }

        return linked;
    }

    /** Copies the layer's BBS form into the replay, leaving an existing form untouched when null. */
    public static void syncFormToReplay(CompLayer layer, Replay replay)
    {
        if (layer == null || replay == null || !layer.hasModelForm())
        {
            return;
        }

        Form form = layer.modelForm();

        if (form != null)
        {
            replay.form.set(FormUtils.copy(form));
        }
    }

    /**
     * Pulls a form chosen/edited in the replay editor back into the layer.
     *
     * <p>Animation does not need this: the compositor renders the replay itself, so its keyframes
     * are already live. The form is copied because the layer's model library, thumbnails and the
     * unlinked fallback all read {@code CompLayer.formData}.</p>
     */
    public static void syncLayerFromReplay(CompLayer layer, Replay replay)
    {
        if (layer == null || replay == null)
        {
            return;
        }

        Form form = replay.form.get();

        if (form != null)
        {
            layer.setModelForm(FormUtils.copy(form));
        }

        String label = displayName(replay);

        if (label != null && !label.isBlank()
            && (layer.name == null || layer.name.isBlank() || "回放模型".equals(layer.name)))
        {
            layer.name = label;
        }
    }

    /** Pulls every linked replay's form back into its layer (call when returning to the compositor). */
    public static int syncLayersFromReplays(Film film, CompDocument doc)
    {
        if (film == null || doc == null)
        {
            return 0;
        }

        int synced = 0;

        for (CompLayer layer : doc.layers)
        {
            Replay replay = find(film, layer);

            if (replay != null)
            {
                syncLayerFromReplay(layer, replay);
                synced += 1;
            }
        }

        return synced;
    }

    /** Every linked replay for a document, in layer order, for diagnostics. */
    public static List<Replay> linkedReplays(Film film, CompDocument doc)
    {
        List<Replay> replays = new java.util.ArrayList<>();

        if (film != null && doc != null)
        {
            for (CompLayer layer : doc.layers)
            {
                Replay replay = find(film, layer);

                if (replay != null)
                {
                    replays.add(replay);
                }
            }
        }

        return replays;
    }
}
