package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import mchorse.bbs_mod.ui.utils.Gizmo;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * One-shot verification for the replay page's gizmo, body-part pick and layout splitters.
 *
 * <p>Enabled by {@code -Dbbsae.replayGizmoScript=true}. It drives the production paths (not a
 * reimplementation): the same {@code startGizmo/pump/accept} sequence a mouse drag uses, the same
 * part-click handler, and the same internal layout splitters. The log is the primary evidence;
 * screenshots are secondary because a gizmo drag can move an actor by only a few pixels.</p>
 */
public final class ReplayGizmoScript
{
    private static boolean started;

    private final UIReplayModePanel panel;
    private int tick;
    private String beforeDrag = "-";
    private boolean dragStarted;
    private String afterDrag = "-";

    private ReplayGizmoScript(UIReplayModePanel panel)
    {
        this.panel = panel;
    }

    public static void start(UIReplayModePanel panel)
    {
        if (started || panel == null)
        {
            return;
        }

        started = true;

        ReplayGizmoScript script = new ReplayGizmoScript(panel);

        BbsAeAddon.LOGGER.info("Replay gizmo script: armed");

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            if (client.player != null && client.world != null)
            {
                script.tick(client);
            }
        });
    }

    private void tick(MinecraftClient client)
    {
        this.tick += 1;

        if (this.tick < 60)
        {
            return;
        }

        switch (this.tick)
        {
            case 60:
                this.panel.debugSelectPlayableReplay();
                BbsAeAddon.LOGGER.info("Replay gizmo script: idle pick passes before edit = {}",
                    this.panel.debugPickRenders());
                this.panel.session().seek(30F);
                this.panel.session().applyPose(this.panel.session().entity(this.panel.replay()), this.panel.replay());
                this.beforeDrag = this.panel.debugChannelSummary();
                BbsAeClient.debugScreenshot("gizmo-before", false);
                BbsAeAddon.LOGGER.info("Replay gizmo script: before=[{}]", this.beforeDrag);
                break;

            case 62:
                /* STENCIL_X is the red move arrow of the replay-root placement gizmo. Dragging it
                 * must write x/y/z at the playhead; before the context fix this returned started
                 * but no gesture existed, so the channels stayed identical. */
                this.dragStarted = this.panel.debugGizmoDrag(Gizmo.STENCIL_X, 48, -24);
                this.afterDrag = this.panel.debugChannelSummary();
                BbsAeAddon.LOGGER.info("Replay gizmo script: drag started={} after=[{}]", this.dragStarted, this.afterDrag);
                break;

            case 70:
                /* The saved test film contains MobForm chickens, which have no bone tracks. Swap
                 * the selected replay to one of BBS' built-in posed models so the part/bone path
                 * can be exercised without depending on user assets. */
                mchorse.bbs_mod.forms.forms.ModelForm steve = new mchorse.bbs_mod.forms.forms.ModelForm();

                steve.model.set("player/steve_bends");
                BbsAeAddon.LOGGER.info("Replay gizmo script: model swap applied={}",
                    this.panel.debugApplyForm(steve));
                break;

            case 75:
                /* Real stencil pick at the projected bone: proves the id map contains the
                 * selected actor's parts, not only whole-replay ids. */
                BbsAeAddon.LOGGER.info("Replay gizmo script: click head [{}] part=[{}]",
                    this.panel.debugClickBone("head"), this.panel.debugPartSummary());
                break;

            case 80:
                String modelBone = this.panel.debugSelectModelBone();

                if (modelBone.isEmpty())
                {
                    this.panel.debugSelectPart("head");
                }

                BbsAeAddon.LOGGER.info("Replay gizmo script: part after click bone='{}' [{}]",
                    modelBone, this.panel.debugPartSummary());
                break;

            case 85:
                /* Real stencil click on a bone after the model/entity has settled. */
                BbsAeAddon.LOGGER.info("Replay gizmo script: stencil click anchor [{}] part=[{}]",
                    this.panel.debugClickBone("anchor"), this.panel.debugPartSummary());
                break;

            case 90:
                /* The first bone key: selects the part's pose track and opens the pose editor, so
                 * the gizmo can move to the bone. */
                this.panel.insertKeyframeFromHost(false);
                BbsAeAddon.LOGGER.info("Replay gizmo script: bone key [{}]", this.panel.debugPartSummary());
                break;

            case 100:
                BbsAeAddon.LOGGER.info("Replay gizmo script: part settled [{}]", this.panel.debugPartSummary());
                BbsAeClient.debugScreenshot("gizmo-part", false);
                break;

            case 112:
                this.panel.debugResizeColumns(260, 300, 220);
                BbsAeAddon.LOGGER.info("Replay gizmo script: layout [{}]", this.panel.debugLayoutSummary());
                BbsAeClient.debugScreenshot("gizmo-layout", false);
                break;

            case 130:
                boolean moved = !this.beforeDrag.equals(this.afterDrag);
                boolean hasPart = this.panel.session() != null
                    && this.panel.session().selectedPartForm() != null;
                String layout = this.panel.debugLayoutSummary();

                BbsAeAddon.LOGGER.info(
                    "Replay gizmo script: RESULT gizmoStarted={} channelsMoved={} partSelected={} layoutSplit={} layout=[{}]",
                    this.dragStarted, moved, hasPart,
                    layout.contains("listSplit=6x") && layout.contains("propsSplit=6x")
                        && layout.contains("timelineSplit="),
                    layout);

                if (Boolean.getBoolean("bbsae.debugQuitAfterShot"))
                {
                    client.execute(client::scheduleStop);
                }
                break;

            default:
                break;
        }
    }
}
