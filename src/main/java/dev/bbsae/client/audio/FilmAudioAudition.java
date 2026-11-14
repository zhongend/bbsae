package dev.bbsae.client.audio;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.BbsAeClient;
import dev.bbsae.client.ui.UICompositorPanel;
import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStore;
import dev.bbsae.comp.CompositionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Audio browsing and audition for the BBS film page.
 *
 * <p>The film editor owns the film, the compositor owns the audio files and mix. Until now a user
 * had to leave the film page for the compositor to hear either one. This helper exposes the same
 * decoders through the film page: the list contains the project audio files plus a "composition
 * mix" entry, and selecting a file auditions it with the composition mixer's decoder.</p>
 */
public final class FilmAudioAudition
{
    private static boolean installed;
    private static int debugTicks;

    private FilmAudioAudition()
    {}

    public static void install()
    {
        if (installed)
        {
            return;
        }

        installed = true;

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            /* The mix is rendered on a worker; tick() drains it on the client thread. Sync only
             * while the film runner advances, otherwise the free-running audition would be
             * yanked back to the parked cursor every tick. */
            CompAudioPlayer.tick();

            if (CompAudioPlayer.isPlaying())
            {
                UIFilmPanel panel = filmPanel();

                if (panel != null && panel.getRunner() != null && panel.getRunner().isRunning())
                {
                    CompAudioPlayer.sync(panel.getRunner().getCursor(0F) / 20F);
                }
            }

            if (Boolean.getBoolean("bbsae.debugFilmAudio") && client.player != null && client.world != null)
            {
                debugTicks += 1;

                if (debugTicks == 220)
                {
                    UIFilmPanel panel = filmPanel();

                    if (panel != null)
                    {
                        BbsAeAddon.LOGGER.info("Film audio: opening browser for film {}",
                            panel.getData() == null ? "-" : panel.getData().getId());
                        open(panel);
                    }
                }
            }
        });
    }

    /** Opens the browser for the film page (and keeps the panel as the overlay's context owner). */
    public static void open(UIFilmPanel panel)
    {
        if (panel == null || panel.getData() == null || panel.getContext() == null)
        {
            return;
        }

        List<Entry> entries = collect(panel.getData(), panel);
        List<String> labels = new ArrayList<>();
        List<Entry> byLabel = new ArrayList<>();

        for (Entry entry : entries)
        {
            labels.add(entry.label);
            byLabel.add(entry);
        }

        if (labels.isEmpty())
        {
            labels.add("没有可试听的音频（先在合成器素材箱导入音频）");
            byLabel.add(null);
        }

        UIListOverlayPanel overlay = new UIListOverlayPanel(
            IKey.constant("bbsae 音频浏览 · 试听合成器音频"),
            (value) ->
            {
                int index = labels.indexOf(value);

                if (index < 0 || index >= byLabel.size())
                {
                    return;
                }

                Entry entry = byLabel.get(index);

                if (entry == null)
                {
                    return;
                }

                if (entry.mix)
                {
                    toggleCompMix(entry.project, entry.comp, entry.startTime);
                }
                else
                {
                    CompAudioPreview.play(entry.file);
                }
            });

        overlay.addValues(labels);
        UIOverlay.addOverlay(panel.getContext(), overlay, 0.45F, 0.7F);
    }

    public static void toggleCompMix(CompProject project, CompDocument doc, float startTime)
    {
        if (CompAudioPlayer.isPlaying())
        {
            CompAudioPlayer.stop();

            return;
        }

        if (project == null || doc == null || !CompAudioPlayer.hasAudio(doc))
        {
            BbsAeAddon.LOGGER.info("Film audio: the film's composition has no audio track");

            return;
        }

        CompAudioPlayer.play(project, doc, Math.max(0F, startTime));
    }

    private static UIFilmPanel filmPanel()
    {
        if (MinecraftClient.getInstance() == null || mchorse.bbs_mod.BBSModClient.getDashboard() == null)
        {
            return null;
        }

        return mchorse.bbs_mod.BBSModClient.getDashboard().getPanel(UIFilmPanel.class);
    }

    private static List<Entry> collect(Film film, UIFilmPanel panel)
    {
        List<Entry> entries = new ArrayList<>();
        Set<Path> seenFiles = new LinkedHashSet<>();
        Set<String> seenLabels = new LinkedHashSet<>();
        CompProject firstProject = null;
        CompDocument firstDoc = null;
        float start = panel.getRunner() == null ? 0F : panel.getRunner().getCursor(0F) / 20F;

        for (CompositionClip clip : film.camera.getClips(CompositionClip.class))
        {
            CompProject project = CompStore.getForFrame(clip.project.get());

            if (project == null)
            {
                continue;
            }

            CompDocument doc = project.getComp(clip.comp.get());

            if (doc == null)
            {
                continue;
            }

            if (firstProject == null)
            {
                firstProject = project;
                firstDoc = doc;
            }

            if (CompAudioPlayer.hasAudio(doc))
            {
                entries.add(new Entry("▶ 合成器混音 · " + docLabel(project, doc) + "（播放 / 停止）",
                    null, project, doc, start));
            }

            for (CompAudio audio : doc.audio)
            {
                if (audio == null || audio.path == null || audio.path.isBlank())
                {
                    continue;
                }

                Path file = CompStore.resolveAsset(project.id, audio.path);

                addFile(entries, seenFiles, seenLabels, project.id, file);
            }

            scanFolder(entries, seenFiles, seenLabels, project.id, CompStore.assetRoot(project.id));
        }

        /* A film saved before any composition clip exists can still have a compositor project
         * bound in memory; use it rather than showing an empty browser. */
        if (firstProject == null)
        {
            UICompositorPanel compositor = BbsAeClient.compositorPanel();

            if (compositor != null)
            {
                firstProject = compositor.getProject();
                firstDoc = compositor.getDocument();
            }
        }

        /* The film's clip may point at a project whose in-memory document is the compositor's
         * current one (a just-added audio clip is not saved yet). Use the bound panel as the
         * authoritative source when it belongs to this film. */
        UICompositorPanel compositor = BbsAeClient.compositorPanel();

        if (compositor != null && compositor.film() == film && compositor.getProject() != null
            && compositor.getDocument() != null)
        {
            CompProject project = compositor.getProject();
            CompDocument doc = compositor.getDocument();

            if (firstProject == null)
            {
                firstProject = project;
                firstDoc = doc;
            }

            scanFolder(entries, seenFiles, seenLabels, project.id, CompStore.assetRoot(project.id));

            if (CompAudioPlayer.hasAudio(doc)
                && entries.stream().noneMatch((entry) -> entry.mix))
            {
                entries.add(0, new Entry("▶ 合成器混音 · " + docLabel(project, doc) + "（播放 / 停止）",
                    null, project, doc, start));
            }
        }

        if (firstProject != null && firstDoc != null && CompAudioPlayer.hasAudio(firstDoc)
            && entries.stream().noneMatch((entry) -> entry.mix))
        {
            entries.add(0, new Entry("▶ 合成器混音 · " + docLabel(firstProject, firstDoc) + "（播放 / 停止）",
                null, firstProject, firstDoc, start));
        }

        return entries;
    }

    private static String docLabel(CompProject project, CompDocument doc)
    {
        if (doc != null && doc.name != null && !doc.name.isBlank()
            && !"-".equals(doc.name.trim()) && !"Untitled".equalsIgnoreCase(doc.name.trim()))
        {
            return doc.name;
        }

        if (project != null && project.id != null && !project.id.isBlank())
        {
            return project.id;
        }

        if (doc != null && doc.id != null && !doc.id.isBlank())
        {
            return doc.id;
        }

        return "当前合成";
    }

    private static void scanFolder(List<Entry> entries, Set<Path> seenFiles, Set<String> seenLabels,
                                   String projectId, Path root)
    {
        if (root == null || !Files.isDirectory(root))
        {
            return;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(root, 6))
        {
            stream.filter(Files::isRegularFile)
                .filter(FilmAudioAudition::isAudio)
                .forEach((file) -> addFile(entries, seenFiles, seenLabels, projectId, file));
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Film audio: could not scan {}", root, e);
        }
    }

    private static void addFile(List<Entry> entries, Set<Path> seenFiles, Set<String> seenLabels,
                                String projectId, Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return;
        }

        Path absolute = file.toAbsolutePath();

        if (!seenFiles.add(absolute))
        {
            return;
        }

        String label = projectId + " · " + file.getFileName();

        while (!seenLabels.add(label))
        {
            label += " " + (seenLabels.size() + 1);
        }

        entries.add(new Entry(label, absolute, null, null, 0F));
    }

    private static boolean isAudio(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        return name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".mp3")
            || name.endsWith(".flac") || name.endsWith(".m4a") || name.endsWith(".aac");
    }

    private static final class Entry
    {
        final String label;
        final Path file;
        final CompProject project;
        final CompDocument comp;
        final float startTime;

        final boolean mix;

        Entry(String label, Path file, CompProject project, CompDocument comp, float startTime)
        {
            this.label = label;
            this.file = file;
            this.project = project;
            this.comp = comp;
            this.startTime = startTime;
            this.mix = project != null && file == null;
        }
    }
}
