package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.utils.Axis;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.Locale;

/**
 * The scripted interaction run for the replay editor: one client launch performs the four
 * interactions the headless tests cannot cover, screenshots each state and logs the keyframe
 * channel values so the run is auditable from the log even when a screenshot is ambiguous.
 *
 * <h2>Why a script instead of hand-driven screenshots</h2>
 * <p>The replay editor's interactions all go through BBS UI objects that cannot be constructed on
 * the JUnit classpath (their static initialisers need the game bootstrap), so the only way to
 * prove "a gizmo drag writes the right keyframes and undo restores them" is to perform the same
 * writes a drag would, in a real client, at known ticks, and capture the result. The write path is
 * deliberately the <b>same code</b> the gizmo uses — {@code UIReplayPropTransform.setT/setR} — not a
 * reimplementation: whatever is wrong with the gizmo is then wrong here too, and the screenshot
 * shows it.</p>
 *
 * <h2>Schedule</h2>
 * <p>{@link ReplayScriptTimeline} holds every tick; each action is followed by twenty ticks of
 * settling before its screenshot. The four states are: moved (non-zero playhead), undone, model
 * swapped, replay duplicated. The log lines are prefixed {@code Replay script:} for grep.</p>
 *
 * <h2>Enablement</h2>
 * <p>On by default for the replay verification run ({@code -Dbbsae.debugView=replay}); explicitly
 * forced by {@code -Dbbsae.replayInteractionScript=true}; disabled by
 * {@code -Dbbsae.replayInteractionScript.disable=true}. The generic {@code -PbbsaeShot} hook is
 * suppressed while this script owns the screenshots (see {@code BbsAeClient}).</p>
 */
public final class ReplayInteractionScript
{
    /** A non-zero playhead so the write lands on a real film tick, not the origin. */
    public static final int WRITE_TICK = 30;

    private static boolean started;

    private final UIReplayModePanel panel;
    private int tick;
    private int replayIndex;
    private Replay duplicate;
    private boolean failed;
    private String beforeMove = "-";
    private boolean undoRestored;

    private ReplayInteractionScript(UIReplayModePanel panel)
    {
        this.panel = panel;
    }

    public static boolean isEnabled()
    {
        if (Boolean.getBoolean("bbsae.replayInteractionScript.disable"))
        {
            return false;
        }

        if (Boolean.getBoolean("bbsae.replayInteractionScript"))
        {
            return true;
        }

        return System.getProperty("bbsae.debugView", "").toLowerCase(Locale.ROOT).contains("replay");
    }

    public static void start(UIReplayModePanel panel)
    {
        if (started || panel == null)
        {
            return;
        }

        started = true;

        ReplayInteractionScript script = new ReplayInteractionScript(panel);

        BbsAeAddon.LOGGER.info("Replay script: armed; move@{} undo@{} redo@{} form@{} list@{} quit@{}",
            ReplayScriptTimeline.MOVE_ACTION, ReplayScriptTimeline.UNDO_ACTION, ReplayScriptTimeline.REDO_ACTION,
            ReplayScriptTimeline.FORM_ACTION, ReplayScriptTimeline.LIST_ACTION, ReplayScriptTimeline.QUIT_TICK);

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            if (client.player == null || client.world == null)
            {
                return;
            }

            script.tick(client);
        });
    }

    private void tick(MinecraftClient client)
    {
        this.tick += 1;

        if (this.tick > ReplayScriptTimeline.QUIT_TICK)
        {
            return;
        }

        this.run("move", this.tick == ReplayScriptTimeline.MOVE_ACTION, this::actMove);
        this.shot("move", this.tick == ReplayScriptTimeline.MOVE_SHOT, 0);
        this.run("undo", this.tick == ReplayScriptTimeline.UNDO_ACTION, this::actUndo);
        this.shot("undo", this.tick == ReplayScriptTimeline.UNDO_SHOT, 1);
        this.run("redo", this.tick == ReplayScriptTimeline.REDO_ACTION, this::actRedo);
        this.run("form", this.tick == ReplayScriptTimeline.FORM_ACTION, this::actForm);
        this.shot("form", this.tick == ReplayScriptTimeline.FORM_SHOT, 2);
        this.run("list", this.tick == ReplayScriptTimeline.LIST_ACTION, this::actList);
        this.shot("list", this.tick == ReplayScriptTimeline.LIST_SHOT, 3);
        this.run("cleanup", this.tick == ReplayScriptTimeline.CLEANUP_ACTION, this::actCleanup);

        if (this.tick == ReplayScriptTimeline.QUIT_TICK)
        {
            this.finish(client);
        }
    }

    /* ------------------------------------------------------------ steps */

    /**
     * The gizmo write: seek to a non-zero tick, sample the pose there, then write a translation and
     * a rotation through {@code UIReplayPropTransform} — the exact calls the gizmo gesture makes.
     */
    private void actMove()
    {
        UIReplayModePanel modePanel = this.panel;

        if (modePanel.session() == null || modePanel.filmPanel() == null || modePanel.filmPanel().getData() == null)
        {
            this.fail("move", "no bound film/session");
            return;
        }

        this.replayIndex = modePanel.debugSelectPlayableReplay();
        Replay replay = modePanel.replay();
        IEntity entity = modePanel.session().entity(replay);

        modePanel.session().seek(WRITE_TICK);

        if (entity == null || replay == null)
        {
            this.fail("move", "selected replay has no entity");
            return;
        }

        modePanel.session().applyPose(entity, replay);
        this.beforeMove = modePanel.debugChannelSummary();

        UIReplayPropTransform transform = modePanel.session().gizmoTransform();

        double x = entity.getX() + 2D;
        double y = entity.getY();
        double z = entity.getZ() + 1D;

        /* setR takes euler degrees in the gizmo frame: x = pitch, y = -yaw (the channel yaw is the
         * negated value), z = roll (dropped by the replay transform). */
        double pitch = entity.getPitch() + 15D;
        double yaw = -entity.getYaw() + 40D;

        transform.setT(Axis.X, x, y, z);
        transform.setR(Axis.Y, pitch, yaw, 0D);

        BbsAeAddon.LOGGER.info("Replay script: MOVE write tick={} target=({},{},{}) pitch={} gizmoYaw={} before=[{}] after=[{}]",
            modePanel.session().keyframeTick(), format(x), format(y), format(z), format(pitch), format(yaw),
            this.beforeMove, modePanel.debugChannelSummary());
    }

    private void actUndo()
    {
        UIReplayModePanel modePanel = this.panel;

        if (modePanel.filmPanel() == null)
        {
            this.fail("undo", "no film panel");
            return;
        }

        String before = modePanel.debugChannelSummary();

        try
        {
            /* Same wrapper the Ctrl+Z path uses: it attaches the borrowed film panel for the
             * synchronous undo call (BBS applies undo through UIElement.getRoot(), which is null
             * for the detached film panel). */
            modePanel.undoReplay();
        }
        catch (RuntimeException e)
        {
            this.fail("undo", "UIReplayModePanel.undoReplay threw " + e);
            return;
        }

        String after = modePanel.debugChannelSummary();

        this.undoRestored = after.equals(this.beforeMove);

        BbsAeAddon.LOGGER.info("Replay script: UNDO before=[{}] after=[{}] expectedPreMove=[{}] restored={}",
            before, after, this.beforeMove, this.undoRestored);
    }

    private void actRedo()
    {
        UIReplayModePanel modePanel = this.panel;

        if (modePanel.filmPanel() == null)
        {
            return;
        }

        try
        {
            modePanel.redoReplay();
        }
        catch (RuntimeException e)
        {
            this.fail("redo", "UIReplayModePanel.redoReplay threw " + e);
            return;
        }

        BbsAeAddon.LOGGER.info("Replay script: REDO after=[{}]", modePanel.debugChannelSummary());
    }

    /** Model-library replacement through the same copy + form-slot path the palette callback uses. */
    private void actForm()
    {
        UIReplayModePanel modePanel = this.panel;
        Replay replay = modePanel.replay();

        if (replay == null)
        {
            this.fail("form", "no selected replay");
            return;
        }

        String before = describeForm(replay);

        MobForm zombie = new MobForm();

        zombie.mobID.set("minecraft:zombie");

        boolean applied = modePanel.debugApplyForm(zombie);

        modePanel.debugAfterExternalEdit(this.replayIndex);

        BbsAeAddon.LOGGER.info("Replay script: FORM applied={} before={} after={} placeable={}",
            applied, before, describeForm(modePanel.replay()), modePanel.session() == null ? -1 : modePanel.session().placeableCount());
    }

    /** New replay through the original list's duplicate action, then the count/selection check. */
    private void actList()
    {
        UIReplayModePanel modePanel = this.panel;
        int before = modePanel.debugReplayCount();

        this.duplicate = modePanel.debugDuplicateReplay();

        int after = modePanel.debugReplayCount();

        BbsAeAddon.LOGGER.info("Replay script: LIST duplicate before={} after={} newReplay={} selectedIndex={}",
            before, after, this.duplicate == null ? "-" : this.duplicate.getId(),
            modePanel.session() == null ? -1 : modePanel.session().replayIndex(modePanel.replay()));
    }

    /** Restore the film's replay list (the run is expected to be thrown away, but leave it clean). */
    private void actCleanup()
    {
        if (this.duplicate == null)
        {
            return;
        }

        UIReplayModePanel modePanel = this.panel;

        modePanel.debugRemoveReplay(this.duplicate);

        BbsAeAddon.LOGGER.info("Replay script: CLEANUP removed duplicated replay; count={} selectedIndex={}",
            modePanel.debugReplayCount(),
            modePanel.session() == null ? -1 : modePanel.session().replayIndex(modePanel.replay()));

        this.duplicate = null;
    }

    /* ------------------------------------------------------------ plumbing */

    private void shot(String stage, boolean when, int index)
    {
        if (!when)
        {
            return;
        }

        if (this.failed && !"move".equals(stage))
        {
            /* Still capture the later states: a broken step earlier in the script is evidence
             * too, and the run only happens once. */
            BbsAeAddon.LOGGER.warn("Replay script: capturing '{}' after an earlier failure", stage);
        }

        BbsAeClient.debugScreenshot(ReplayScriptTimeline.shotName(index), false);

        BbsAeAddon.LOGGER.info("Replay script: SHOT {} ({})", stage, ReplayScriptTimeline.shotName(index));
    }

    private void run(String stage, boolean when, Runnable action)
    {
        if (!when)
        {
            return;
        }

        try
        {
            action.run();
        }
        catch (RuntimeException e)
        {
            this.fail(stage, e.toString());
        }
    }

    private void fail(String stage, String message)
    {
        this.failed = true;

        BbsAeAddon.LOGGER.error("Replay script: step '{}' failed: {}", stage, message);
    }

    private void finish(MinecraftClient client)
    {
        boolean quit = Boolean.getBoolean("bbsae.debugQuitAfterShot")
            || !Boolean.getBoolean("bbsae.replayInteractionStay");

        BbsAeAddon.LOGGER.info("Replay script: done failed={} undoRestored={} replayCount={} quitting={}",
            this.failed, this.undoRestored, this.panel.debugReplayCount(), quit);

        if (quit)
        {
            client.execute(client::scheduleStop);
        }
    }

    private static String describeForm(Replay replay)
    {
        if (replay == null || replay.form.get() == null)
        {
            return "none";
        }

        Form form = replay.form.get();

        if (form instanceof MobForm mob)
        {
            return "MobForm(" + mob.mobID.get() + ")";
        }

        return form.getFormIdOrName();
    }

    private static String format(double value)
    {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
