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

            for (CompLayer layer : comp.layers)
            {
                layer.ensureInitialized();
                layer.saveTracks();
            }
        }

        try
        {
            Path path = file(id);

            Files.createDirectories(path.getParent());

            if (Files.isRegularFile(path))
            {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.writeString(path, GSON.toJson(project), StandardCharsets.UTF_8);

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
        CompProject project = new CompProject();

        project.id = safe(projectId);
        project.name = name == null || name.isBlank() ? project.id : name;
        project.comps.add(defaultComp("Main"));
        project.outputPresets = defaultPresets();
        project.settings = new CompProject.CompSettings();

        return project;
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
        comp.background = 0x00000000;

        return comp;
    }

    public static java.util.List<CompOutputPreset> defaultPresets()
    {
        java.util.List<CompOutputPreset> presets = new java.util.ArrayList<>();

        presets.add(CompOutputPreset.reviewH264());
        presets.add(CompOutputPreset.masterProRes());
        presets.add(CompOutputPreset.alphaProRes());
        presets.add(CompOutputPreset.pngSequence());

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

    private static void migrate(CompProject project)
    {
        project.format = CompProject.FORMAT;
        project.version = CompProject.VERSION;

        if (project.outputPresets == null || project.outputPresets.isEmpty())
        {
            project.outputPresets = defaultPresets();
        }

        if (project.animationPresets == null)
        {
            project.animationPresets = new java.util.ArrayList<>();
        }

        if (project.settings == null)
        {
            project.settings = new CompProject.CompSettings();
        }

        if (project.renderQueue == null)
        {
            project.renderQueue = new java.util.ArrayList<>();
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
            if (comp.workAreaOut <= 0F) comp.workAreaOut = comp.duration;

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
        comp.background = 0x00000000;

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

        CompLayer bar = new CompLayer("bar", "HUD Bar", "bar");

        bar.x = 960F;
        bar.y = 790F;
        bar.width = 680F;
        bar.height = 22F;
        bar.color = 0xFF55D68A;
        bar.fill = -1F;

        CompLayer accent = new CompLayer("accent", "Accent", "rect");

        accent.x = 960F;
        accent.y = 300F;
        accent.width = 520F;
        accent.height = 8F;
        accent.color = 0xFF55AAFF;
        accent.motion = true;
        accent.motionAmount = 220F;

        CompLayer title = new CompLayer("title", "Title", "text");

        title.text = "BBS COMPOSITOR";
        title.x = 960F;
        title.y = 500F;
        title.fontSize = 92F;
        title.color = 0xFFFFFFFF;
        title.align = 1;
        title.tracking = 2F;
        title.styles.add(style(CompStyle.DROP_SHADOW, 0xCC000000, 6F, 3F, 45F, 8F));

        CompLayer subtitle = new CompLayer("subtitle", "Subtitle", "text");

        subtitle.text = "FrameOverlays -> BBS -> ffmpeg";
        subtitle.x = 960F;
        subtitle.y = 585F;
        subtitle.fontSize = 32F;
        subtitle.color = 0xFF9AA4B2;
        subtitle.align = 1;

        CompLayer badge = new CompLayer("badge", "Badge Shape", "shape");

        badge.x = 1540F;
        badge.y = 180F;
        badge.shapes.add(star("badge_star", 0F, 0F, 52F, 0xFF55D68A));

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
