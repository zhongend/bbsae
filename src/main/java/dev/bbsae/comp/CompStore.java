package dev.bbsae.comp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.utils.pose.Transform;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Loads and saves {@code .bbscomp} projects under {@code config/bbsae/projects/}.
 *
 * <p>The film never contains the project. It contains a CompositionClip with a project id and a
 * comp id, and BBS serializes that clip itself. This class is the other half of that link: the
 * editor and the frame overlay both resolve a project id here. A missing/broken project never
 * stops a film from playing; the clip is skipped and the renderer reports it in the log.</p>
 */
public final class CompStore
{
    public static final String DEFAULT_PROJECT = "demo";
    public static final String DEFAULT_COMP = "main";
    public static final String EXTENSION = ".json";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final Map<String, CachedProject> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedDocument> DOCUMENT_CACHE = new ConcurrentHashMap<>();

    private static volatile int revision;

    private CompStore()
    {}

    public static Gson gson()
    {
        return GSON;
    }

    public static Path root()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("bbsae").resolve("projects");
    }

    public static Path file(String projectId)
    {
        return root().resolve(safe(projectId) + EXTENSION);
    }

    public static Path assetRoot(String projectId)
    {
        return root().resolve(safe(projectId)).resolve("assets");
    }

    public static Path exportRoot(String projectId)
    {
        return FabricLoader.getInstance().getConfigDir().resolve("bbsae").resolve("exports").resolve(safe(projectId));
    }

    public static String safe(String id)
    {
        if (id == null || id.isBlank())
        {
            return DEFAULT_PROJECT;
        }

        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static CompProject get(String projectId)
    {
        return load(projectId);
    }

    public static CompDocument getComp(String projectId, String compId)
    {
        CompProject project = load(projectId);

        return project.getComp(compId);
    }

    /**
     * Frame-path lookup: returns the cached document until the store is saved or reloaded.
     * The overlay runs every frame, so it must not stat or parse a file there.
     */
    public static CompDocument getCompCached(String projectId, String compId)
    {
        int current = revision;
        String key = safe(projectId) + "/" + (compId == null ? "" : compId);
        CachedDocument cached = DOCUMENT_CACHE.get(key);

        if (cached != null && cached.revision == current)
        {
            return cached.document;
        }

        CompDocument document = getComp(projectId, compId);

        DOCUMENT_CACHE.put(key, new CachedDocument(current, document));

        return document;
    }

    public static synchronized CompProject load(String projectId)
    {
        String id = safe(projectId);
        Path path = file(id);

        try
        {
            if (!Files.isRegularFile(path))
            {
                CompProject project = demo(id);

                save(project);

                return project;
            }

            long modified = Files.getLastModifiedTime(path).toMillis();
            CachedProject cached = CACHE.get(id);

            if (cached != null && cached.modified == modified)
            {
                return cached.project;
            }

            String json = Files.readString(path, StandardCharsets.UTF_8);
            CompProject project = GSON.fromJson(json, CompProject.class);

            if (!isValid(project))
            {
                BbsAeAddon.LOGGER.warn("Invalid composition project {}, using demo data", path);

                return demo(id);
            }

            project.id = id;
            migrate(project);

            for (CompDocument comp : project.comps)
            {
                for (CompLayer layer : comp.layers)
                {
                    layer.ensureInitialized();
                    layer.loadTracks();
                }
            }

            CACHE.put(id, new CachedProject(modified, project));

            return project;
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.error("Could not load composition project {}", path, e);

            return demo(id);
        }
    }

    /**
     * Serializes a project for the undo stack.
     *
     * <p>Keyframe channels are transient and only exist in memory; they reach JSON through
     * {@link CompLayer#saveTracks()}. Snapshotting without this call silently produced undo
     * states with no animation at all, so every undo wiped transform/effect keyframes.</p>
     */
    public static synchronized String snapshot(CompProject project)
    {
        if (project == null)
        {
            return "";
        }

        if (project.comps != null)
        {
            for (CompDocument comp : project.comps)
            {
                if (comp == null || comp.layers == null)
                {
                    continue;
                }

                for (CompLayer layer : comp.layers)
                {
                    if (layer != null)
                    {
                        layer.ensureInitialized();
                        layer.saveTracks();
                    }
                }
            }
        }

        return GSON.toJson(project);
    }

    /** Rebuilds a project from an undo snapshot (same migration and track hydration as load). */
    public static synchronized CompProject restore(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }

        try
        {
            CompProject project = GSON.fromJson(json, CompProject.class);

            if (project == null || project.comps == null || project.comps.isEmpty())
            {
                return null;
            }

            migrate(project);

            for (CompDocument comp : project.comps)
            {
                for (CompLayer layer : comp.layers)
                {
                    layer.ensureInitialized();
                    layer.loadTracks();
                }
            }

            return project;
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not restore composition snapshot", e);

            return null;
        }
    }

    public static synchronized void save(CompProject project)
    {
        if (project == null)
        {
            return;
        }

        String id = safe(project.id);
        project.id = id;
        project.format = CompProject.FORMAT;
        project.version = CompProject.VERSION;

        if (project.comps == null || project.comps.isEmpty())
        {
            project.comps = new java.util.ArrayList<>();
            project.comps.add(defaultComp("Main"));
        }

        if (project.outputPresets == null || project.outputPresets.isEmpty())
        {
            project.outputPresets = defaultPresets();
        }

        if (project.animationPresets == null)
        {
            project.animationPresets = new java.util.ArrayList<>();
        }

        if (project.presets == null)
        {
            project.presets = new java.util.ArrayList<>();
        }

        if (project.renderQueue == null)
        {
            project.renderQueue = new java.util.ArrayList<>();
        }

        if (project.settings == null)
        {
            project.settings = new CompProject.CompSettings();
        }

        for (CompDocument comp : project.comps)
        {
            if (comp.layers == null) comp.layers = new java.util.ArrayList<>();
            if (comp.markers == null) comp.markers = new java.util.ArrayList<>();
            if (comp.parameters == null) comp.parameters = new java.util.ArrayList<>();
            if (comp.audio == null) comp.audio = new java.util.ArrayList<>();

            for (CompLayer layer : comp.layers)
            {
                layer.ensureInitialized();
                layer.saveTracks();
            }
        }

        try
        {
            Path path = file(id);
            Path parent = path.getParent();

            Files.createDirectories(parent);

            if (Files.isRegularFile(path))
            {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }

            /* Write through a temporary file and move it into place: a crash or a full disk in
             * the middle of a save must not truncate the only copy of the project. */
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

            Files.writeString(temporary, GSON.toJson(project), StandardCharsets.UTF_8);

            try
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e)
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }

            CACHE.put(id, new CachedProject(Files.getLastModifiedTime(path).toMillis(), project));
            DOCUMENT_CACHE.clear();
            revision += 1;
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.error("Could not save composition project {}", id, e);
        }
    }

    public static synchronized void reload()
    {
        CACHE.clear();
        DOCUMENT_CACHE.clear();
        revision += 1;
    }

    public static void ensureDemo()
    {
        load(DEFAULT_PROJECT);
        getCompCached(DEFAULT_PROJECT, DEFAULT_COMP);
    }

    public static CompProject newProject(String projectId, String name)
    {
        return newProject(projectId, name, 1920, 1080, 60F, 12F);
    }

    /**
     * Creates a project with an explicit first composition. The start screen asks for these
     * values up front, so a new project never opens on a resolution that does not match the film
     * it is about to be used with.
     */
    public static CompProject newProject(String projectId, String name, int width, int height, float fps, float duration)
    {
        CompProject project = new CompProject();

        project.id = safe(projectId);
        project.name = name == null || name.isBlank() ? project.id : name;
        project.comps.add(defaultComp("Main"));
        project.outputPresets = defaultPresets();
        project.settings = new CompProject.CompSettings();

        CompDocument comp = project.getCompOrFirst(DEFAULT_COMP);

        if (comp != null)
        {
            comp.width = Math.max(16, width);
            comp.height = Math.max(16, height);
            comp.fps = Math.max(1F, fps);
            comp.duration = Math.max(0.1F, duration);
            comp.workAreaOut = comp.duration;
        }

        return project;
    }

    /** Metadata for the start screen; never returns a project that failed to parse. */
    public static final class ProjectInfo
    {
        public final String id;
        public final String name;
        public final int comps;
        public final int width;
        public final int height;
        public final float fps;
        public final float duration;
        public final long modified;
        public final boolean film;

        public ProjectInfo(String id, String name, int comps, int width, int height, float fps, float duration, long modified)
        {
            this.id = id;
            this.name = name;
            this.comps = comps;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.duration = duration;
            this.modified = modified;
            this.film = id.startsWith("film_");
        }

        public String summary()
        {
            return this.width + "×" + this.height + "  ·  " + String.format(Locale.ROOT, "%.0f fps", this.fps)
                + "  ·  " + String.format(Locale.ROOT, "%.1fs", this.duration)
                + "  ·  " + this.comps + (this.comps == 1 ? " 个合成" : " 个合成");
        }
    }

    /** Lists every .bbscomp in the projects folder, newest first. */
    public static List<ProjectInfo> listProjects()
    {
        List<ProjectInfo> list = new ArrayList<>();
        Path root = root();

        if (!Files.isDirectory(root))
        {
            return list;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(root))
        {
            stream.filter((path) -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(EXTENSION))
                .forEach((path) ->
                {
                    try
                    {
                        String json = Files.readString(path, StandardCharsets.UTF_8);
                        CompProject project = GSON.fromJson(json, CompProject.class);

                        if (!isValid(project) || project.comps.isEmpty())
                        {
                            return;
                        }

                        CompDocument comp = project.comps.get(0);
                        String id = path.getFileName().toString();

                        id = id.substring(0, id.length() - EXTENSION.length());
                        list.add(new ProjectInfo(id,
                            project.name == null || project.name.isBlank() ? id : project.name,
                            project.comps.size(), comp.width, comp.height, comp.fps, comp.duration,
                            Files.getLastModifiedTime(path).toMillis()));
                    }
                    catch (Exception ignored)
                    {}
                });
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not list composition projects", e);
        }

        list.sort((a, b) -> Long.compare(b.modified, a.modified));

        return list;
    }

    public static boolean exists(String projectId)
    {
        return Files.isRegularFile(file(projectId));
    }

    /** Deletes a project and its asset folder; only used from the start screen's context menu. */
    public static synchronized boolean deleteProject(String projectId)
    {
        String id = safe(projectId);

        if (DEFAULT_PROJECT.equals(id))
        {
            return false;
        }

        try
        {
            boolean removed = Files.deleteIfExists(file(id));

            CACHE.remove(id);
            DOCUMENT_CACHE.clear();
            revision += 1;

            return removed;
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not delete composition project {}", id, e);

            return false;
        }
    }

    /**
     * A compositor project is scoped to a BBS film (a replay project): every film gets its own
     * project, so two films can never share layers, keyframes or output settings. The project is
     * created lazily the first time its replay properties action is used.
     */
    public static synchronized CompProject loadFilmProject(String filmId, String filmName, int width, int height, float fps, float duration)
    {
        String id = "film_" + safe(filmId);
        Path path = file(id);
        CompProject project;

        if (Files.isRegularFile(path))
        {
            project = load(id);
        }
        else
        {
            project = newProject(id, filmName);
            CompDocument comp = project.getCompOrFirst(CompStore.DEFAULT_COMP);

            if (comp != null)
            {
                comp.width = Math.max(1, width);
                comp.height = Math.max(1, height);
                comp.fps = Math.max(1F, fps);
                comp.duration = Math.max(0.1F, duration);
                comp.workAreaIn = 0F;
                comp.workAreaOut = comp.duration;
            }

            save(project);
        }

        project.name = filmName == null || filmName.isBlank() ? project.name : filmName;
        project.id = id;

        if (project.getComp(DEFAULT_COMP) == null)
        {
            CompDocument comp = defaultComp("Main");

            comp.width = Math.max(1, width);
            comp.height = Math.max(1, height);
            comp.fps = Math.max(1F, fps);
            comp.duration = Math.max(0.1F, duration);
            comp.workAreaOut = comp.duration;
            project.comps.add(comp);
        }

        return project;
    }

    /** One composition per replay: replays in the same film never share layers or keyframes. */
    public static String replayCompId(String replayId, String replayName)
    {
        String key = replayId == null || replayId.isBlank() ? replayName : replayId;

        return "replay_" + safe(key == null || key.isBlank() ? "default" : key);
    }

    public static CompDocument ensureReplayComp(CompProject project, String replayId, String replayName, int width, int height, float fps, float duration)
    {
        String id = replayCompId(replayId, replayName);
        CompDocument comp = project.getComp(id);

        if (comp == null)
        {
            comp = defaultComp(replayName == null || replayName.isBlank() ? "Replay" : replayName);
            comp.id = id;
            comp.name = replayName == null || replayName.isBlank() ? "Replay" : replayName;
            comp.width = Math.max(1, width);
            comp.height = Math.max(1, height);
            comp.fps = Math.max(1F, fps);
            comp.duration = Math.max(0.1F, duration);
            comp.workAreaOut = comp.duration;
            project.comps.add(comp);
        }

        return comp;
    }

    public static CompDocument defaultComp(String name)
    {
        CompDocument comp = new CompDocument();

        comp.id = "main";
        comp.name = name == null || name.isBlank() ? "Main" : name;
        comp.width = 1920;
        comp.height = 1080;
        comp.fps = 60F;
        comp.duration = 12F;
        comp.workAreaOut = comp.duration;
        comp.background = 0xFF000000;

        return comp;
    }

    public static java.util.List<CompOutputPreset> defaultPresets()
    {
        java.util.List<CompOutputPreset> presets = new java.util.ArrayList<>();

        presets.add(CompOutputPreset.reviewH264());
        presets.add(CompOutputPreset.masterProRes());
        presets.add(CompOutputPreset.alphaProRes());
        presets.add(CompOutputPreset.pngSequence());
        presets.add(CompOutputPreset.audioWav());
        presets.add(CompOutputPreset.audioM4a());

        return presets;
    }

    public static <T> String uniqueId(String base, List<T> list, Function<T, String> id)
    {
        String root = safe(base).toLowerCase(Locale.ROOT).replace('.', '_');
        String candidate = root;
        int serial = 2;

        while (containsId(list, id, candidate))
        {
            candidate = root + "_" + serial;
            serial += 1;
        }

        return candidate;
    }

    private static <T> boolean containsId(List<T> list, Function<T, String> id, String candidate)
    {
        for (T element : list)
        {
            if (candidate.equals(id.apply(element)))
            {
                return true;
            }
        }

        return false;
    }

    public static String uniqueEffectId(CompLayer layer)
    {
        int serial = 1;

        while (layer.getEffect("fx" + serial) != null)
        {
            serial += 1;
        }

        return "fx" + serial;
    }

    public static String uniquePresetId(CompProject project, String base)
    {
        return uniqueId(base, project.outputPresets, (preset) -> preset.id);
    }

    /**
     * Brings a loaded project up to {@link CompProject#VERSION}.
     *
     * <p>Package-private on purpose: this is the one place where a version bump can silently break
     * opening an old file, so it is worth testing without a game running (see
     * {@code CompStoreMigrationTest}).</p>
     */
    static void migrate(CompProject project)
    {
        /* The version the file was written with. Any one-time conversion below keys off it, so a
         * project saved after the conversion is never converted twice. */
        int previousVersion = project.version;

        project.format = CompProject.FORMAT;
        project.version = CompProject.VERSION;

        if (project.outputPresets == null || project.outputPresets.isEmpty())
        {
            project.outputPresets = defaultPresets();
        }
        else
        {
            /* v5: audio output modules. A project written before they existed has a non-empty
             * preset list, so the branch above never ran for it — and asking for "audio_wav" then
             * fell back to the first video preset, i.e. "export audio" silently rendered a video. */
            boolean hasAudio = false;

            for (CompOutputPreset preset : project.outputPresets)
            {
                if (preset != null && "audio".equals(preset.kind))
                {
                    hasAudio = true;
                    break;
                }
            }

            if (!hasAudio)
            {
                project.outputPresets.add(CompOutputPreset.audioWav());
                project.outputPresets.add(CompOutputPreset.audioM4a());
            }
        }

        if (project.animationPresets == null)
        {
            project.animationPresets = new java.util.ArrayList<>();
        }

        if (project.presets == null)
        {
            project.presets = new java.util.ArrayList<>();
        }

        if (project.settings == null)
        {
            project.settings = new CompProject.CompSettings();
        }

        if (project.renderQueue == null)
        {
            project.renderQueue = new java.util.ArrayList<>();
        }

        /* v5: the preview stage stopped being the live BBS world. Version 4 files were migrated to
         * previewBackground = 1 (world behind a transparent stage), so without this they would keep
         * looking transparent forever even though the default changed. */
        if (previousVersion < 5 && project.settings != null)
        {
            project.settings.previewBackground = 0;
            project.settings.previewBackgroundChosen = true;
        }

        if (project.comps == null)
        {
            project.comps = new java.util.ArrayList<>();
        }

        for (CompDocument comp : project.comps)
        {
            if (comp.layers == null) comp.layers = new java.util.ArrayList<>();
            if (comp.markers == null) comp.markers = new java.util.ArrayList<>();
            if (comp.parameters == null) comp.parameters = new java.util.ArrayList<>();
            if (comp.audio == null) comp.audio = new java.util.ArrayList<>();
            if (comp.workAreaOut <= 0F) comp.workAreaOut = comp.duration;

            /* v4: the stage stopped being transparent. Only files written before v4 are touched;
             * a project saved with a deliberately transparent stage keeps it. */
            if (previousVersion < 4 && (comp.background >>> 24) == 0)
            {
                comp.background = 0xFF000000;
            }

            for (CompLayer layer : comp.layers)
            {
                layer.ensureInitialized();
            }
        }
    }

    private static boolean isValid(CompProject project)
    {
        return project != null
            && (CompProject.FORMAT.equals(project.format) || project.version >= 1)
            && project.version >= 1
            && project.comps != null
            && !project.comps.isEmpty()
            && project.comps.get(0).layers != null;
    }

    private static Transform transform(float x, float y)
    {
        Transform transform = new Transform();

        transform.translate.set(x, y, 0F);

        return transform;
    }

    /**
     * The built-in project is both documentation and a smoke test: title text, a HUD, a shape,
     * a feathered mask, a native transform/opacity animation and an adjustment layer.
     */
    public static CompProject demo(String id)
    {
        CompProject project = new CompProject();

        project.id = safe(id);
        project.name = "BBS Compositor Demo";
        project.outputPresets = defaultPresets();

        CompDocument comp = defaultComp("Demo");

        comp.id = DEFAULT_COMP;
        comp.width = 1920;
        comp.height = 1080;
        comp.duration = 12F;
        comp.workAreaOut = 12F;
        comp.background = 0xFF000000;

        CompLayer panel = new CompLayer("panel", "Backdrop", "panel");

        panel.x = 960F;
        panel.y = 540F;
        panel.width = 1280F;
        panel.height = 600F;
        panel.color = 0xD0101018;
        panel.out = 12F;
        panel.styles.add(style(CompStyle.DROP_SHADOW, 0xC0000000, 18F, 12F, 0F, 26F));

        CompMask panelMask = new CompMask("panel_round");

        panelMask.mode = CompMask.MODE_ADD;
        panelMask.feather = 18F;
        panelMask.rectangle(-640F, -300F, 1280F, 600F);
        panel.masks.add(panelMask);

        CompLayer barBack = new CompLayer("bar_back", "HUD Bar Back", "rect");

        barBack.x = 960F;
        barBack.y = 790F;
        barBack.width = 680F;
        barBack.height = 30F;
        barBack.color = 0xFF242A33;
        barBack.in = 0.6F;
        barBack.out = 11.4F;

        CompLayer bar = new CompLayer("bar", "HUD Bar", "bar");

        bar.x = 960F;
        bar.y = 790F;
        bar.width = 680F;
        bar.height = 22F;
        bar.color = 0xFF55D68A;
        bar.fill = -1F;
        bar.in = 0.6F;
        bar.out = 11.4F;

        CompLayer accent = new CompLayer("accent", "Accent", "rect");

        accent.x = 960F;
        accent.y = 300F;
        accent.width = 520F;
        accent.height = 8F;
        accent.color = 0xFF55AAFF;
        accent.motion = true;
        accent.motionAmount = 220F;
        accent.in = 0.4F;
        accent.out = 9.2F;

        CompLayer title = new CompLayer("title", "Title", "text");

        title.text = "BBS COMPOSITOR";
        title.x = 960F;
        title.y = 500F;
        title.fontSize = 92F;
        title.color = 0xFFFFFFFF;
        title.align = 1;
        title.tracking = 2F;
        title.out = 7.5F;
        title.styles.add(style(CompStyle.DROP_SHADOW, 0xCC000000, 6F, 3F, 45F, 8F));

        CompLayer subtitle = new CompLayer("subtitle", "Subtitle", "text");

        subtitle.text = "FrameOverlays -> BBS -> ffmpeg";
        subtitle.x = 960F;
        subtitle.y = 585F;
        subtitle.fontSize = 32F;
        subtitle.color = 0xFF9AA4B2;
        subtitle.align = 1;
        subtitle.in = 0.6F;
        subtitle.out = 8.2F;

        CompLayer badge = new CompLayer("badge", "Badge Shape", "shape");

        badge.x = 1540F;
        badge.y = 180F;
        badge.shapes.add(star("badge_star", 0F, 0F, 52F, 0xFF55D68A));
        badge.in = 1.4F;
        badge.out = 6.5F;

        CompLayer nullLayer = new CompLayer("null_ctrl", "Null Controller", "null");

        nullLayer.x = 0F;
        nullLayer.y = 0F;

        /* A guide/null is intentionally not rendered, but still feeds the parent transform. */
        badge.parentId = "null_ctrl";
        nullLayer.rotation = -8F;

        CompLayer adjustment = new CompLayer("grade", "Grade", "adjustment");

        adjustment.effects.add(CompEffectRegistry.create("vignette", 1).put("amount", 0.35D));
        adjustment.out = 12F;

        comp.layers.add(panel);
        comp.layers.add(barBack);
        comp.layers.add(bar);
        comp.layers.add(accent);
        comp.layers.add(title);
        comp.layers.add(subtitle);
        comp.layers.add(badge);
        comp.layers.add(nullLayer);
        comp.layers.add(adjustment);

        title.transformChannel().insert(0F, transform(960F, 520F));
        title.transformChannel().insert(72F, transform(960F, 460F));
        title.opacityChannel().insert(0F, 0F);
        title.opacityChannel().insert(18F, 1F);
        title.opacityChannel().insert(48F, 1F);
        subtitle.opacityChannel().insert(0F, 0F);
        subtitle.opacityChannel().insert(36F, 1F);
        subtitle.opacityChannel().insert(84F, 1F);
        badge.opacityChannel().insert(0F, 0F);
        badge.opacityChannel().insert(42F, 1F);
        badge.markers.add(new CompMarker("badge_hit", "Badge Hit", 1.4F));
        badge.scalarChannel(CompLayer.shapePath("badge_star", "trimEnd")).insert(0F, 0F);
        badge.scalarChannel(CompLayer.shapePath("badge_star", "trimEnd")).insert(48F, 1F);

        CompParameter speed = new CompParameter("speed", 1F);

        speed.label = "HUD Speed";
        speed.min = 0F;
        speed.max = 4F;
        comp.parameters.add(speed);

        comp.markers.add(new CompMarker("intro", "Intro", 0F));
        comp.markers.add(new CompMarker("hud", "HUD", 2.4F));
        comp.markers.add(new CompMarker("outro", "Outro", 9.5F));

        project.comps.add(comp);

        return project;
    }

    private static CompStyle style(String type, int color, float size, float distance, float angle, float blur)
    {
        CompStyle style = new CompStyle(type);

        style.color = color;
        style.size = size;
        style.distance = distance;
        style.angle = angle;
        style.blur = blur;

        return style;
    }

    private static CompShape star(String id, float x, float y, float radius, int color)
    {
        CompShape shape = new CompShape(id, CompShape.STAR);

        shape.x = x;
        shape.y = y;
        shape.width = radius * 2F;
        shape.height = radius * 2F;
        shape.sides = 5;
        shape.innerRadius = 0.45F;
        shape.outerRadius = 1F;
        shape.fillColor = color;

        return shape;
    }

    /**
     * Move one layer into a new composition and replace it in place. This is the model half of
     * "Pre-compose": the new precomp layer keeps the source's stack index and parent/matte
     * relationships, and the inner copy's timeline is rebased to 0 (in/out, keyframes, markers)
     * so the content does not start twice as late.
     *
     * @return the new precomp layer, or null when the source is not in the document
     */
    public static CompLayer precompose(CompProject project, CompDocument doc, CompLayer source, String baseName)
    {
        if (project == null || doc == null || source == null || !doc.layers.contains(source))
        {
            return null;
        }

        int sourceIndex = doc.layers.indexOf(source);
        float fps = Math.max(1F, doc.fps);
        float frame = 1F / fps;
        float in = source.in;
        float out = source.out > source.in ? source.out : Math.min(doc.duration, source.in + frame);

        List<CompLayer> children = new ArrayList<>();
        List<CompLayer> matteUsers = new ArrayList<>();

        for (CompLayer candidate : doc.layers)
        {
            if (candidate == source)
            {
                continue;
            }

            if (source.id.equals(candidate.parentId))
            {
                children.add(candidate);
            }

            if (source.id.equals(candidate.trackMatteId))
            {
                matteUsers.add(candidate);
            }
        }

        int subIndex = project.comps.size() + 1;
        String name = (baseName == null || baseName.isBlank() ? "预合成" : baseName) + " " + subIndex;
        CompDocument sub = project.addComp("precomp_" + subIndex, doc.width, doc.height, doc.fps, doc.duration);

        sub.name = name;
        sub.duration = doc.duration;
        sub.workAreaOut = doc.duration;

        CompLayer copy = source.duplicate();

        copy.in = 0F;
        copy.out = Math.max(frame, out - in);
        copy.shiftKeyframes(-in * fps);
        copy.shiftMarkers(-in);
        copy.parentId = "";
        copy.trackMatteId = "";
        copy.trackMatteEnabled = false;
        sub.layers.add(copy);

        doc.removeLayer(source.id);

        CompLayer precomp = new CompLayer("precomp", name, "precomp");

        precomp.id = uniqueId("precomp", doc.layers, (candidate) -> candidate.id);
        precomp.precompId = sub.id;
        precomp.x = doc.width / 2F;
        precomp.y = doc.height / 2F;
        precomp.width = doc.width;
        precomp.height = doc.height;
        precomp.in = in;
        precomp.out = out;
        precomp.startTime = 0F;

        precomp.parentId = source.id.equals(source.parentId) ? "" : source.parentId;
        precomp.trackMatteId = source.id.equals(source.trackMatteId) ? "" : source.trackMatteId;
        precomp.trackMatteEnabled = !precomp.trackMatteId.isEmpty() && source.trackMatteEnabled;
        precomp.trackMatteMode = source.trackMatteMode;

        doc.layers.add(Math.max(0, Math.min(sourceIndex, doc.layers.size())), precomp);

        for (CompLayer child : children)
        {
            child.parentId = precomp.id;
        }

        for (CompLayer user : matteUsers)
        {
            user.trackMatteId = precomp.id;
        }

        return precomp;
    }

    private static final class CachedProject
    {
        private final long modified;
        private final CompProject project;

        private CachedProject(long modified, CompProject project)
        {
            this.modified = modified;
            this.project = project;
        }
    }

    private static final class CachedDocument
    {
        private final int revision;
        private final CompDocument document;

        private CachedDocument(int revision, CompDocument document)
        {
            this.revision = revision;
            this.document = document;
        }
    }
}
