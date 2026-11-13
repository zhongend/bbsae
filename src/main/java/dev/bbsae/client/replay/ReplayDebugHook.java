package dev.bbsae.client.replay;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import dev.bbsae.client.ui.UICompositorPanel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Verification hook for the real-machine audit of the 3D replay editor.
 *
 * <h2>Problem it solves</h2>
 * <p>Every previous client screenshot run of the replay page showed the empty state: the panel was
 * opened without a film in the dashboard's film editor, so there was nothing to render. The 3D
 * model pass, the camera and the gizmo all need a film with replay actors, and a fresh
 * {@code run/} directory has no BBS film data at all.</p>
 *
 * <h2>What it does</h2>
 * <p>On the first client tick after the world is up ({@code bbsae.debugReplayTicks}, default 60),
 * it finds a film, fills the dashboard's <b>single</b> {@link UIFilmPanel} with it (never a second
 * panel), seeks to a tick and opens the replay panel with a deterministic camera. The film source
 * is, in order:</p>
 * <ol>
 *   <li>{@code -Dbbsae.debugReplayFilm=<id>} — an existing film from {@code BBSMod.getFilms()};</li>
 *   <li>the first film on disk that has at least one replay;</li>
 *   <li>a synthesized in-memory film (three vanilla mob replays) when the run directory has no
 *       usable film — this is what makes the hook work in a fresh checkout, and it never touches
 *       the disk.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./gradlew runClientView -PbbsaeView=replay -PbbsaeShot=replay-models
 *   ./gradlew runClientView -PbbsaeView=replay -Dbbsae.debugReplayFilm=export_test -PbbsaeShot=replay-export-test
 * </pre>
 * <p>When {@code bbsae.debugView=replay} is present the normal compositor debug view is skipped
 * (see {@code BbsAeClient.onClientReady}); {@code -PbbsaeShot} keeps working unchanged.</p>
 *
 * <p>Optional properties: {@code bbsae.debugReplaySynth=true} (force the synthesized film),
 * {@code bbsae.debugReplayTick} (default 0), {@code bbsae.debugReplaySelect} (replay index,
 * default 0), {@code bbsae.debugReplayYaw}/{@code Pitch}/{@code Distance} (override the framed
 * camera), {@code bbsae.debugReplayTicks} (tick to open at, default 60).</p>
 */
public final class ReplayDebugHook
{
    public static final String FILM_PROPERTY = "bbsae.debugReplayFilm";

    private static boolean installed;

    private ReplayDebugHook()
    {}

    /** True when the replay verification run was requested (film id or {@code debugView=replay}). */
    public static boolean isRequested()
    {
        String film = System.getProperty(FILM_PROPERTY, "").trim();

        if (!film.isEmpty())
        {
            return true;
        }

        return "replay".equalsIgnoreCase(System.getProperty("bbsae.debugView", "").trim());
    }

    public static void install()
    {
        if (installed || !isRequested())
        {
            return;
        }

        installed = true;

        final int[] ticks = {0};
        final boolean[] done = {false};
        final int openAt = Math.max(1, Integer.getInteger("bbsae.debugReplayTicks", 60));

        BbsAeAddon.LOGGER.info("Replay debug hook armed: film='{}', view='replay', opening at tick {}",
            System.getProperty(FILM_PROPERTY, ""), openAt);

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            if (done[0] || client.player == null || client.world == null)
            {
                return;
            }

            if (++ticks[0] < openAt)
            {
                return;
            }

            try
            {
                done[0] = prepare();
            }
            catch (RuntimeException e)
            {
                done[0] = true;
                BbsAeAddon.LOGGER.error("Replay debug hook failed; the panel will stay in its empty state", e);
            }
        });
    }

    /**
     * Loads a film into the existing film panel and opens the replay page.
     *
     * @return true when the attempt is final (success or a logged failure); false asks the tick
     *         handler to retry (the dashboard was not ready yet)
     */
    private static boolean prepare()
    {
        UIDashboard dashboard = BBSModClient.getDashboard();

        if (dashboard == null)
        {
            return false;
        }

        UIFilmPanel filmPanel = dashboard.getPanel(UIFilmPanel.class);

        if (filmPanel == null)
        {
            BbsAeAddon.LOGGER.warn("Replay debug hook: the dashboard has no film panel yet");

            return false;
        }

        String requested = System.getProperty(FILM_PROPERTY, "").trim();

        if (requested.isEmpty())
        {
            /* The clientView run config already forwards -PbbsaeFilmId as -Dbbsae.debugFilmId,
             * so a one-off verification run can name a real film without editing build.gradle.
             * Only use it when the id really exists — the config's default is a placeholder. */
            String forwarded = System.getProperty("bbsae.debugFilmId", "").trim();

            if (!forwarded.isEmpty() && BBSMod.getFilms() != null && BBSMod.getFilms().exists(forwarded))
            {
                requested = forwarded;

                BbsAeAddon.LOGGER.info("Replay debug hook: using the forwarded bbsae.debugFilmId '{}'", requested);
            }
        }

        boolean synth = Boolean.getBoolean("bbsae.debugReplaySynth");
        Film film = null;

        if (!synth)
        {
            if (!requested.isEmpty())
            {
                film = BBSMod.getFilms().load(requested);

                if (film == null)
                {
                    BbsAeAddon.LOGGER.warn("Replay debug hook: film '{}' could not be loaded; available: {}",
                        requested, availableFilms());
                }
            }
            else
            {
                film = firstFilmWithReplays();
            }
        }

        if (film == null || film.replays.getList().isEmpty())
        {
            if (film != null)
            {
                BbsAeAddon.LOGGER.info("Replay debug hook: film '{}' has no replays; using the synthesized film",
                    film.getId());
            }

            film = synthesizeFilm();
        }

        filmPanel.fill(film);

        if (filmPanel.getData() == null)
        {
            BbsAeAddon.LOGGER.warn("Replay debug hook: the film panel refused the film; leaving the empty state visible");
            BbsAeClient.openReplayMode(dashboard, null);

            return true;
        }

        BbsAeClient.openReplayMode(dashboard, null);

        UIReplayModePanel panel = BbsAeClient.replayModePanel();

        if (panel != null)
        {
            panel.debugPrepare(
                Integer.getInteger("bbsae.debugReplaySelect", -1),
                Math.max(0, Integer.getInteger("bbsae.debugReplayTick", 0)),
                floatProperty("bbsae.debugReplayYaw"),
                floatProperty("bbsae.debugReplayPitch"),
                floatProperty("bbsae.debugReplayDistance")
            );

            /* The scripted interaction run (P6) rides on the same verification hook: it performs
             * the gizmo-write/undo/form/library/list interactions and screenshots each state. */
            if (ReplayInteractionScript.isEnabled())
            {
                ReplayInteractionScript.start(panel);
            }

            /* Playback regression check: start the replay transport, let it run for a second of
             * wall time, then log the cursor. This catches the old "seek every frame restarts the
             * action" bug without needing a human to click play. */
            if (Boolean.getBoolean("bbsae.debugReplayPlayback") && panel != null)
            {
                final UIReplayModePanel playbackPanel = panel;
                final int[] after = {0};

                ClientTickEvents.END_CLIENT_TICK.register((client) ->
                {
                    after[0] += 1;

                    if (after[0] == 20)
                    {
                        BbsAeAddon.LOGGER.info("Replay playback check: start at {}s",
                            playbackPanel.playheadSeconds());
                        playbackPanel.togglePlaybackFromHost();
                    }
                    else if (after[0] == 80)
                    {
                        BbsAeAddon.LOGGER.info("Replay playback check: after 60 ticks at {}s playing={}",
                            playbackPanel.playheadSeconds(), playbackPanel.isPlayingFromHost());
                        playbackPanel.togglePlaybackFromHost();
                    }
                });
            }
        }

        /* Optional composition-camera audit: add a 3D replay layer to the film's comp and switch
         * back to the compositor. Proves the actor pass renders with the bbsae camera rather than
         * the world plate. Test-only; enabled by an explicit property. */
        if (Boolean.getBoolean("bbsae.debugReplayComp") && BbsAeClient.compositorPanel() != null)
        {
            UICompositorPanel host = BbsAeClient.compositorPanel();

            host.openCompositeWorkspace();
            host.debugReplayEntityLayer3D();
        }

        /* Optional comp→replay bridge audit: add a bbsae model in the composition and switch to
         * the replay editor. Test-only; enabled by an explicit property. */
        if (Boolean.getBoolean("bbsae.debugBridgeCompReplay") && BbsAeClient.compositorPanel() != null)
        {
            BbsAeClient.compositorPanel().debugBridgeCompReplay();
        }

        BbsAeAddon.LOGGER.info("Replay debug hook: opened film='{}' replays={} panel={}",
            film.getId(), film.replays.getList().size(), panel != null);

        return true;
    }

    /** First film on disk with at least one replay; falls back to the first loadable film. */
    private static Film firstFilmWithReplays()
    {
        FilmManager films = BBSMod.getFilms();

        if (films == null)
        {
            return null;
        }

        Collection<String> keys = new ArrayList<>(films.getKeys());
        List<String> sorted = new ArrayList<>(keys);

        java.util.Collections.sort(sorted);

        Film first = null;

        for (String key : sorted)
        {
            Film film = films.load(key);

            if (film == null)
            {
                continue;
            }

            if (first == null)
            {
                first = film;
            }

            if (!film.replays.getList().isEmpty())
            {
                BbsAeAddon.LOGGER.info("Replay debug hook: picked film '{}' ({}) from {} available",
                    key, film.getId(), sorted.size());

                return film;
            }
        }

        BbsAeAddon.LOGGER.info("Replay debug hook: no film with replays found among {} key(s)", sorted.size());

        return first;
    }

    /**
     * An in-memory film with three vanilla mob replays. It exists so the 3D pass, the camera and
     * the gizmo can be verified in a checkout whose {@code run/} has no BBS film data; it is never
     * saved (the panel only fills it into the editor, and the debug run is expected to be thrown
     * away).
     */
    private static Film synthesizeFilm()
    {
        Film film = new Film();

        film.setId("bbsae_debug_replay");
        film.stampCreationTimeNow();

        IdleClip camera = new IdleClip();

        camera.duration.set(400);
        camera.layer.set(0);
        camera.position.set(new Position(0F, 66F, 10F, 180F, 12F));
        film.camera.addClip(camera);

        String[] mobs = {"minecraft:zombie", "minecraft:skeleton", "minecraft:villager"};
        float[][] positions = {{0F, 64F, 0F}, {3F, 64F, 0F}, {-3F, 64F, 0F}};

        for (int i = 0; i < mobs.length; i++)
        {
            Replay replay = film.replays.addReplay();
            MobForm form = new MobForm();

            form.mobID.set(mobs[i]);

            replay.form.set(form);
            replay.label.set("debug_" + mobs[i].substring(mobs[i].indexOf(':') + 1));
            replay.enabled.set(true);
            replay.keyframes.x.insert(0F, (double) positions[i][0]);
            replay.keyframes.y.insert(0F, (double) positions[i][1]);
            replay.keyframes.z.insert(0F, (double) positions[i][2]);
            replay.keyframes.yaw.insert(0F, i == 0 ? 0D : (i == 1 ? 90D : -90D));
            replay.keyframes.headYaw.insert(0F, 0D);
            replay.keyframes.bodyYaw.insert(0F, 0D);
            replay.keyframes.pitch.insert(0F, 0D);
        }

        BbsAeAddon.LOGGER.info("Replay debug hook: synthesized film '{}' with {} mob replays (not saved)",
            film.getId(), film.replays.getList().size());

        return film;
    }

    /** Diagnostic list for the "film not found" log line. */
    private static String availableFilms()
    {
        try
        {
            Collection<String> keys = BBSMod.getFilms().getKeys();
            List<String> sorted = new ArrayList<>(keys);

            java.util.Collections.sort(sorted);

            return sorted.isEmpty() ? "(none)" : String.join(", ", sorted);
        }
        catch (RuntimeException e)
        {
            return "(unavailable: " + e.getMessage() + ")";
        }
    }

    private static float floatProperty(String key)
    {
        String raw = System.getProperty(key, "").trim();

        if (raw.isEmpty())
        {
            return Float.NaN;
        }

        try
        {
            return Float.parseFloat(raw);
        }
        catch (NumberFormatException e)
        {
            return Float.NaN;
        }
    }
}
