package dev.bbsae.comp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.utils.pose.Transform;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Compact serializer for undo snapshots. They are never shown, diffed or edited — only parsed
     * back — so the pretty printer would just add whitespace to every per-second snapshot and to
     * the undo memory budget.
     */
    private static final Gson SNAPSHOT_GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    /** {@code -Dbbsae.debugSave=true} logs save/snapshot segment timings. Off by default. */
    private static final boolean DEBUG_SAVE = Boolean.getBoolean("bbsae.debugSave");

    /* Test instrumentation (audit P0-3). Cheap counters so the tests can prove that a save never
     * builds a JSON String and never parses the project a second time; they are not read at
     * runtime. */
    private static final AtomicLong FULL_PARSES = new AtomicLong();
    private static final AtomicLong STRING_SERIALIZATIONS = new AtomicLong();
    private static final AtomicLong STREAM_WRITES = new AtomicLong();
    private static final AtomicLong STREAM_SCANS = new AtomicLong();
    private static final AtomicLong TRACK_REBUILDS = new AtomicLong();
    private static final AtomicLong TRACK_SKIPS = new AtomicLong();

    private static final Map<String, CachedProject> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedDocument> DOCUMENT_CACHE = new ConcurrentHashMap<>();

    private static volatile int revision;
    /** Redirects {@link #root()} in tests (Fabric's config dir does not exist headless). */
    private static volatile Path rootOverride;
    /** Last failure text from {@link #saveChecked(CompProject)}; null after a successful save. */
    private static volatile String lastSaveError;

    private CompStore()
    {}

    public static Gson gson()
    {
        return GSON;
    }

    public static Path root()
    {
        Path override = rootOverride;

        return override != null ? override : FabricLoader.getInstance().getConfigDir().resolve("bbsae").resolve("projects");
    }

    /**
     * Test seam: redirects storage to a temporary directory. Package-private because the game must
     * always use Fabric's config directory; only the persistence tests need a writable root.
     */
    static void useRootForTests(Path root)
    {
        rootOverride = root;
        lastSaveError = null;
        reload();
    }

    static void clearRootForTests()
    {
        rootOverride = null;
        lastSaveError = null;
        reload();
    }

    /** Snapshot of the P0-3 work counters; test-only and never read at runtime. */
    static final class SaveIoStats
    {
        final long fullParses;
        final long stringSerializations;
        final long streamWrites;
        final long streamScans;
        final long trackRebuilds;
        final long trackSkips;

        private SaveIoStats(long fullParses, long stringSerializations, long streamWrites,
            long streamScans, long trackRebuilds, long trackSkips)
        {
            this.fullParses = fullParses;
            this.stringSerializations = stringSerializations;
            this.streamWrites = streamWrites;
            this.streamScans = streamScans;
            this.trackRebuilds = trackRebuilds;
            this.trackSkips = trackSkips;
        }
    }

    static SaveIoStats saveIoStatsForTests()
    {
        return new SaveIoStats(FULL_PARSES.get(), STRING_SERIALIZATIONS.get(), STREAM_WRITES.get(),
            STREAM_SCANS.get(), TRACK_REBUILDS.get(), TRACK_SKIPS.get());
    }

    static void resetSaveIoStatsForTests()
    {
        FULL_PARSES.set(0L);
        STRING_SERIALIZATIONS.set(0L);
        STREAM_WRITES.set(0L);
        STREAM_SCANS.set(0L);
        TRACK_REBUILDS.set(0L);
        TRACK_SKIPS.set(0L);
    }

    /** Test seam for the streaming validator used to verify a freshly written temporary file. */
    static boolean isReadableProjectFileForTests(Path path)
    {
        ProjectHeader header = inspectProjectFile(path);

        return header != null && header.isProject();
    }

    /**
     * Human-readable reason for the last failed {@link #saveChecked(CompProject)}, or {@code null}
     * when the last save succeeded (or no save has run yet).
     */
    public static String lastSaveError()
    {
        return lastSaveError;
    }

    public static Path file(String projectId)
    {
        return root().resolve(safe(projectId) + EXTENSION);
    }

    public static Path assetRoot(String projectId)
    {
        return root().resolve(safe(projectId)).resolve("assets");
    }

    /**
     * Resolves any media token used by layers and audio clips to a file on disk.
     *
     * <h2>Why one resolver</h2>
     * <p>Over the project's life three spellings appeared: {@code external:C:/...},
     * project-relative {@code images/a.png}, and the older {@code assets/images/a.png}. Each
     * consumer (texture cache, audio mixer, video frames, asset panel) used to guess separately;
     * one of them guessing "external:" first while another treated a Windows drive as a namespace
     * is how a perfectly valid import becomes a blank layer. The order here is explicit:</p>
     * <ol>
     *   <li>{@code external:} -> that absolute path, untouched;</li>
     *   <li>an absolute path -> itself;</li>
     *   <li>{@code assets/...} relative to the project folder, then the assets root;</li>
     *   <li>project-relative, then the assets root.</li>
     * </ol>
     * Resource-pack ids ({@code mc:...}) are not files and intentionally return null.
     */
    public static Path resolveAsset(String projectId, String path)
    {
        if (path == null || path.isBlank())
        {
            return null;
        }

        try
        {
            if (path.startsWith("external:"))
            {
                return Path.of(path.substring("external:".length()));
            }

            if (isResourcePath(path))
            {
                return null;
            }

            Path raw = Path.of(path);

            if (raw.isAbsolute())
            {
                return raw;
            }

            Path projectRoot = file(projectId).getParent();
            Path projectRelative = projectRoot == null ? null : projectRoot.resolve(path);

            if (projectRelative != null && Files.isRegularFile(projectRelative))
            {
                return projectRelative;
            }

            String assetPath = path.startsWith("assets/") ? path.substring("assets/".length()) : path;
            Path asset = assetRoot(projectId).resolve(assetPath);

            if (Files.isRegularFile(asset))
            {
                return asset;
            }

            /* Return the most likely target even when it does not exist yet: callers use the path
             * to report "missing" and to know where a conversion is about to write. */
            return projectRelative != null ? projectRelative : asset;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** True for a resource-pack id such as {@code mc:textures/...}, not a Windows drive path. */
    public static boolean isResourcePath(String path)
    {
        if (path == null || path.isBlank())
        {
            return false;
        }

        /* An external link is a file path even when it contains a colon (Windows drive) or a
         * leading slash (POSIX). The prefix is the unambiguous marker. */
        if (path.startsWith("external:"))
        {
            return false;
        }

        /* A Windows drive ("C:/..." or "C:\...") has a colon at index 1; a resource namespace has
         * at least one character and is followed by a slash-free identifier. */
        int colon = path.indexOf(':');

        if (colon <= 0)
        {
            return false;
        }

        if (colon == 1 && path.length() > 2 && (path.charAt(2) == '/' || path.charAt(2) == '\\'))
        {
            return false;
        }

        return !path.startsWith("/") && !path.contains("\\");
    }

    /** Canonical stored token for an external file: {@code external:<absolute path>}. */
    public static String externalToken(java.io.File file)
    {
        return "external:" + file.getAbsoluteFile().toPath().normalize();
    }

    /** Path part of an {@code external:} token, or the input when it is not one. */
    public static String externalRaw(String path)
    {
        return path != null && path.startsWith("external:")
            ? path.substring("external:".length()) : path;
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

    /**
     * Frame-path lookup for callers that also need project settings.
     *
     * <p>Unlike {@link #load(String)}, a cache hit performs no file-system calls. Ordinary loads
     * retain their modification-time check; explicit reloads still pick up external edits.</p>
     */
    public static CompProject getForFrame(String projectId)
    {
        CachedProject cached = CACHE.get(safe(projectId));

        return cached == null ? load(projectId) : cached.project;
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

        CompDocument document = getForFrame(projectId).getComp(compId);

        DOCUMENT_CACHE.put(key, new CachedDocument(current, document));

        return document;
    }

    /**
     * Historical load contract: the caller always receives a project.
     *
     * <p>A missing file is created as the demo. A file that exists but cannot be read is
     * <b>quarantined</b> (copied to {@code <file>.corrupt-<timestamp>}) and the demo is returned
     * <b>without</b> overwriting the original. Render/overlay paths that must not create files and
     * editors that want to show "could not open this project" should call
     * {@link #loadOrNull(String, boolean)} instead.</p>
     */
    public static synchronized CompProject load(String projectId)
    {
        CompProject project = loadOrNull(projectId, true);

        return project != null ? project : demo(safe(projectId));
    }

    /**
     * Strict load used by the editor and the frame renderer.
     *
     * <p>Missing file: {@code null} unless {@code createIfMissing}, in which case the demo project
     * is created and saved. Existing but unreadable file: copied to
     * {@code <file>.corrupt-<timestamp>}, logged, and {@code null} is returned — the user's file is
     * never silently replaced with demo data. A file written by a newer bbsae is also left
     * untouched and returns {@code null} (the write-side guard in {@code saveChecked} prevents a
     * downgrade from overwriting it later).</p>
     */
    public static synchronized CompProject loadOrNull(String projectId, boolean createIfMissing)
    {
        String id = safe(projectId);
        Path path = file(id);

        if (!Files.isRegularFile(path))
        {
            if (!createIfMissing)
            {
                return null;
            }

            CompProject project = demo(id);

            save(project);

            return project;
        }

        try
        {
            long modified = Files.getLastModifiedTime(path).toMillis();
            CachedProject cached = CACHE.get(id);

            if (cached != null && cached.modified == modified)
            {
                return cached.project;
            }

            String json = Files.readString(path, StandardCharsets.UTF_8);

            FULL_PARSES.incrementAndGet();
            CompProject project = GSON.fromJson(json, CompProject.class);

            if (project == null)
            {
                return quarantine(path, "JSON is empty or not an object", null);
            }

            if (project.isFutureVersion())
            {
                BbsAeAddon.LOGGER.error("Composition project {} was written by a newer bbsae (file version {} > supported {}), leaving it untouched",
                    path, project.version, CompProject.VERSION);

                return null;
            }

            if (!isValid(project))
            {
                return quarantine(path, "JSON did not pass project validation", null);
            }

            sanitize(project);
            project.id = id;
            migrate(project);
            hydrate(project);

            CACHE.put(id, new CachedProject(modified, project));

            return project;
        }
        catch (Exception e)
        {
            return quarantine(path, "read/parse failed", e);
        }
    }

    /**
     * Serializes a project for the undo stack.
     *
     * <p>Keyframe channels are transient and only exist in memory; they reach JSON through
     * {@link CompLayer#saveTracks()}. Snapshotting without this call silently produced undo
     * states with no animation at all, so every undo wiped transform/effect keyframes. The
     * write-back is still performed for every layer that has keys (or stale serialized fields);
     * only the provably empty layers skip it — see {@link #prepareTracksForWrite(CompProject)}.</p>
     *
     * <p>The snapshot uses the compact serializer: it is parsed back by {@link #restore(String)}
     * and never shown to the user, so pretty-printing it only costs time and undo memory.</p>
     */
    public static synchronized String snapshot(CompProject project)
    {
        if (project == null)
        {
            return "";
        }

        long started = DEBUG_SAVE ? System.nanoTime() : 0L;
        TrackPass pass = prepareTracksForWrite(project);
        long prepared = DEBUG_SAVE ? System.nanoTime() : 0L;
        String json = SNAPSHOT_GSON.toJson(project);

        STRING_SERIALIZATIONS.incrementAndGet();

        if (DEBUG_SAVE)
        {
            long now = System.nanoTime();
            BbsAeAddon.LOGGER.info("bbsae snapshot: layers={} rebuilt={} skipped={} chars={} prepare={}ms toJson={}ms total={}ms",
                pass.layers, pass.rebuilt, pass.skipped, json.length(),
                millis(started, prepared), millis(prepared, now), millis(started, now));
        }

        return json;
    }

    /**
     * Deep-copies one composition through the same serializer as undo snapshots.
     *
     * <p>A precomp layer's {@code precompId} is a reference, so a plain {@code CompLayer.duplicate()}
     * makes two layers share one nested composition. This helper clones the document (including
     * transient keyframe channels via {@code saveTracks}) so the copy can get its own nested tree.</p>
     */
    public static synchronized CompDocument duplicateDocument(CompDocument source)
    {
        if (source == null)
        {
            return null;
        }

        CompProject temp = new CompProject();

        temp.id = "__bbsae_copy__";
        temp.comps.add(source);

        String json = snapshot(temp);

        if (json.isEmpty())
        {
            return null;
        }

        CompProject restored = restore(json);

        return restored == null ? null : restored.getComp(source.id);
    }

    /** Rebuilds a project from an undo snapshot (same migration and track hydration as load). */
    public static synchronized CompProject restore(String json)    {
        if (json == null || json.isBlank())
        {
            return null;
        }

        try
        {
            FULL_PARSES.incrementAndGet();
            CompProject project = GSON.fromJson(json, CompProject.class);

            if (project == null || project.version > CompProject.VERSION)
            {
                return null;
            }

            sanitize(project);

            if (project.comps.isEmpty())
            {
                return null;
            }

            migrate(project);
            hydrate(project);

            return project;
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not restore composition snapshot", e);

            return null;
        }
    }

    /**
     * Best-effort save kept for the many existing callers. Use {@link #saveChecked(CompProject)} in
     * flush paths that must tell the user when the file could not be written.
     */
    public static synchronized void save(CompProject project)
    {
        saveChecked(project);
    }

    /**
     * Saves a project atomically and reports whether the main file was replaced.
     *
     * <p>The save path is streaming end to end (audit P0-3): the previous file is syntax-scanned
     * with a {@link JsonReader}, the project is written straight into {@code .tmp}, and that file
     * is scanned back. No multi-megabyte JSON {@code String} is built and the project is never
     * parsed a second time — on a 300-layer project that used to cost 300-600ms per gesture on the
     * render thread.</p>
     *
     * <p>Ordering:</p>
     * <ol>
     *   <li>the previous file is scanned once (full syntax + header fields); its version is the
     *       downgrade guard and its readability decides whether it may rotate into {@code .bak};</li>
     *   <li>the project is normalized in memory and validated directly, without a JSON round-trip;</li>
     *   <li>the JSON is streamed into {@code .tmp}, then scanned back for syntax, {@code format},
     *       {@code version} and a non-empty {@code comps} array;</li>
     *   <li>the previous file is copied to {@code .bak} (bytes only, no parse) and the verified
     *       temporary file is atomically moved over the project.</li>
     * </ol>
     *
     * <p>A {@code .bak} failure is logged but never turns a successful main write into a failure.
     * A file whose on-disk version is newer than {@link CompProject#VERSION} is never overwritten
     * (no silent downgrade). If the final move fails after the backup copy, {@code .bak} holds a
     * byte copy of the unchanged main file; it is never replaced with unreadable bytes.</p>
     *
     * @return true when the project file was written; false leaves {@link #lastSaveError()} set
     */
    public static synchronized boolean saveChecked(CompProject project)
    {
        lastSaveError = null;

        if (project == null)
        {
            return saveFailure("合成工程为空，未写入");
        }

        if (project.isFutureVersion())
        {
            return saveFailure("工程版本 " + project.version + " 高于当前支持的 " + CompProject.VERSION + "，拒绝降级写回");
        }

        String id = safe(project.id);
        Path path = file(id);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        long started = DEBUG_SAVE ? System.nanoTime() : 0L;

        try
        {
            /* One streaming scan replaces the old read-into-byte[] + full parse of the previous
             * file. It is both the downgrade guard (version) and the "may this rotate into .bak?"
             * check, and it never allocates the file content on the heap. */
            ProjectHeader previous = inspectProjectFile(path);

            if (previous == null && Files.isRegularFile(path))
            {
                BbsAeAddon.LOGGER.warn("Could not read previous composition project for backup {}", path);
            }

            /* A project loaded by an older build would otherwise overwrite a newer file and lose
             * every field this build does not know about. */
            if (previous != null && previous.version > CompProject.VERSION)
            {
                return saveFailure("磁盘上的 " + path.getFileName() + " 由更新版本的 bbsae 写入，拒绝覆盖");
            }

            project.id = id;
            prepareForSave(project);

            /* prepareForSave() already normalized format/version/comps/lists, so validating the
             * object directly is equivalent to parsing the serialized copy back (which used to be
             * a second full parse and a second multi-MB allocation). */
            if (!isValid(project))
            {
                return saveFailure("序列化结果未通过校验，未写入");
            }

            Files.createDirectories(path.getParent());

            /* Pretty-printed on purpose: a project file stays diffable and hand-editable. The
             * document is streamed through a buffered writer, so it is never materialized as one
             * big String. A crash or full disk mid-write still cannot truncate the main file. */
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))
            {
                GSON.toJson(project, writer);
            }

            STREAM_WRITES.incrementAndGet();

            if (DEBUG_SAVE)
            {
                long now = System.nanoTime();
                BbsAeAddon.LOGGER.info("bbsae save {}: scan+prepare+write={}ms", path.getFileName(), millis(started, now));
            }

            ProjectHeader written = inspectProjectFile(temporary);

            if (written == null || !written.isProject() || written.version != project.version)
            {
                deleteQuietly(temporary);

                return saveFailure("序列化结果未通过校验，未写入");
            }

            if (DEBUG_SAVE)
            {
                long now = System.nanoTime();
                BbsAeAddon.LOGGER.info("bbsae save {}: verify={}ms bytes={}", path.getFileName(),
                    millis(started, now), sizeOrMinusOne(temporary));
            }

            /* Rotate by copying bytes: reading the old file into a byte[] and parsing it again was
             * the second full parse of every save. A previous file that is not a readable project
             * is left out of .bak so garbage can never replace the last good backup. */
            if (previous != null && previous.isProject())
            {
                rotateBackup(path, backup);
            }

            moveIntoPlace(temporary, path);

            long modified = 0L;

            try
            {
                modified = Files.getLastModifiedTime(path).toMillis();
            }
            catch (IOException ignored)
            {
                /* The bytes are on disk; a stat failure must not report the save as failed. */
            }

            CACHE.put(id, new CachedProject(modified, project));
            DOCUMENT_CACHE.clear();
            revision += 1;

            if (DEBUG_SAVE)
            {
                long now = System.nanoTime();
                BbsAeAddon.LOGGER.info("bbsae save {}: total={}ms", path.getFileName(), millis(started, now));
            }

            return true;
        }
        catch (Exception e)
        {
            deleteQuietly(temporary);

            BbsAeAddon.LOGGER.error("Could not save composition project {}", id, e);

            lastSaveError = "写入 " + path.getFileName() + " 失败：" + describe(e);

            return false;
        }
    }

    private static boolean saveFailure(String message)
    {
        BbsAeAddon.LOGGER.error("Could not save composition project: {}", message);

        lastSaveError = message;

        return false;
    }

    private static String describe(Exception e)
    {
        String detail = e.getMessage();

        return detail == null || detail.isBlank() ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + detail;
    }

    /** Normalizes everything the serializer touches; never throws on null elements. */
    private static void prepareForSave(CompProject project)
    {
        project.format = CompProject.FORMAT;
        project.version = CompProject.VERSION;

        sanitize(project);

        if (project.comps.isEmpty())
        {
            project.comps.add(defaultComp("Main"));
        }

        if (project.outputPresets == null || project.outputPresets.isEmpty())
        {
            project.outputPresets = defaultPresets();
        }

        if (project.animationPresets == null)
        {
            project.animationPresets = new ArrayList<>();
        }

        if (project.presets == null)
        {
            project.presets = new ArrayList<>();
        }

        if (project.renderQueue == null)
        {
            project.renderQueue = new ArrayList<>();
        }

        if (project.settings == null)
        {
            project.settings = new CompProject.CompSettings();
        }

        for (CompDocument comp : project.comps)
        {
            if (comp.layers == null) comp.layers = new ArrayList<>();
            if (comp.markers == null) comp.markers = new ArrayList<>();
            if (comp.parameters == null) comp.parameters = new ArrayList<>();
            if (comp.audio == null) comp.audio = new ArrayList<>();
        }

        prepareTracksForWrite(project);
    }

    /**
     * Writes live keyframe channels back into the serialized fields ({@code transformData},
     * {@code opacityData}, {@code channels}) before a snapshot or a save.
     *
     * <p>{@link CompLayer#saveTracks()} rebuilds those strings through BBS's {@code DataToString}
     * for every layer, even a layer that never had a keyframe. That is paid by the once-per-second
     * undo sampler and by every gesture checkpoint. A layer whose live channels and serialized
     * fields are all empty cannot produce a different result, so the rebuild is skipped only in
     * that <b>provably</b> no-op case; any layer with keys, or with stale serialized data, still
     * goes through the real method.</p>
     */
    private static TrackPass prepareTracksForWrite(CompProject project)
    {
        TrackPass pass = new TrackPass();

        if (project.comps == null)
        {
            return pass;
        }

        for (CompDocument comp : project.comps)
        {
            if (comp == null || comp.layers == null)
            {
                continue;
            }

            for (CompLayer layer : comp.layers)
            {
                if (layer == null)
                {
                    continue;
                }

                pass.layers += 1;
                layer.ensureInitialized();

                if (needsTrackWriteback(layer))
                {
                    layer.saveTracks();
                    pass.rebuilt += 1;
                    TRACK_REBUILDS.incrementAndGet();
                }
                else
                {
                    pass.skipped += 1;
                    TRACK_SKIPS.incrementAndGet();
                }
            }
        }

        return pass;
    }

    /**
     * True when {@link CompLayer#saveTracks()} could change the layer's serialized form.
     *
     * <p>Deliberately conservative: anything that is not provably empty asks for the real rebuild,
     * because a missed rebuild silently drops animation from undo/save (the bug that originally
     * made snapshots wipe every keyframe).</p>
     */
    private static boolean needsTrackWriteback(CompLayer layer)
    {
        if (!layer.transformChannel().getKeyframes().isEmpty())
        {
            return true;
        }

        if (!layer.opacityChannel().getKeyframes().isEmpty())
        {
            return true;
        }

        if (layer.transformData != null && !layer.transformData.isEmpty())
        {
            return true;
        }

        if (layer.opacityData != null && !layer.opacityData.isEmpty())
        {
            return true;
        }

        if (layer.channels != null && !layer.channels.isEmpty())
        {
            return true;
        }

        /* Axis/effect/mask channels are only reachable through this list. A channel object may
         * exist without any keys (scalarChannel() creates lazily), so each path is checked. */
        for (String path : layer.animatedPaths())
        {
            if (layer.hasScalarChannel(path))
            {
                return true;
            }
        }

        return false;
    }

    /** Counters for one track write-back pass; also used by the debug timing log. */
    private static final class TrackPass
    {
        private int layers;
        private int rebuilt;
        private int skipped;
    }

    /**
     * Cheap streaming header/syntax scan of a project JSON file.
     *
     * <p>Returns {@code null} when the file is missing, unreadable, or not a syntactically valid
     * JSON object. The whole document is consumed through {@link JsonReader#skipValue()} so a
     * truncated tail is detected without building a JSON tree, a {@link CompProject} or a
     * multi-megabyte {@code String}; only {@code version}, {@code format} and the {@code comps}
     * array are read.</p>
     */
    private static ProjectHeader inspectProjectFile(Path path)
    {
        if (!Files.isRegularFile(path))
        {
            return null;
        }

        STREAM_SCANS.incrementAndGet();

        try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)))
        {
            reader.setLenient(false);

            if (reader.peek() != JsonToken.BEGIN_OBJECT)
            {
                return null;
            }

            reader.beginObject();

            int version = -1;
            String format = null;
            boolean compsNonEmpty = false;

            while (reader.hasNext())
            {
                String name = reader.nextName();

                if ("version".equals(name))
                {
                    version = readHeaderVersion(reader);
                }
                else if ("format".equals(name))
                {
                    format = readHeaderFormat(reader);
                }
                else if ("comps".equals(name))
                {
                    compsNonEmpty = countComps(reader) > 0;
                }
                else
                {
                    reader.skipValue();
                }
            }

            reader.endObject();

            if (reader.peek() != JsonToken.END_DOCUMENT)
            {
                return null;
            }

            return new ProjectHeader(format, version, compsNonEmpty);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static int readHeaderVersion(JsonReader reader) throws IOException
    {
        JsonToken token = reader.peek();

        if (token == JsonToken.NUMBER)
        {
            return reader.nextInt();
        }

        if (token == JsonToken.STRING)
        {
            try
            {
                return Integer.parseInt(reader.nextString().trim());
            }
            catch (NumberFormatException e)
            {
                return -1;
            }
        }

        reader.skipValue();

        return -1;
    }

    private static String readHeaderFormat(JsonReader reader) throws IOException
    {
        JsonToken token = reader.peek();

        if (token == JsonToken.STRING)
        {
            return reader.nextString();
        }

        if (token == JsonToken.NULL)
        {
            reader.nextNull();

            return null;
        }

        reader.skipValue();

        return null;
    }

    /** Number of object entries in the top-level {@code comps} array; throws when malformed. */
    private static int countComps(JsonReader reader) throws IOException
    {
        if (reader.peek() != JsonToken.BEGIN_ARRAY)
        {
            throw new IOException("comps is not an array");
        }

        reader.beginArray();

        int count = 0;

        while (reader.hasNext())
        {
            JsonToken token = reader.peek();

            if (token == JsonToken.NULL)
            {
                reader.nextNull();
            }
            else if (token == JsonToken.BEGIN_OBJECT)
            {
                reader.skipValue();
                count += 1;
            }
            else
            {
                throw new IOException("comps contains a non-object entry");
            }
        }

        reader.endArray();

        return count;
    }

    /** Header fields extracted by {@link #inspectProjectFile(Path)}. */
    private static final class ProjectHeader
    {
        private final String format;
        private final int version;
        private final boolean compsNonEmpty;

        private ProjectHeader(String format, int version, boolean compsNonEmpty)
        {
            this.format = format;
            this.version = version;
            this.compsNonEmpty = compsNonEmpty;
        }

        /** A complete bbsae project document, not merely parseable JSON. */
        private boolean isProject()
        {
            return CompProject.FORMAT.equals(this.format) && this.version >= 1 && this.compsNonEmpty;
        }
    }

    /**
     * Rotates the previous revision into {@code .bak} by copying bytes. Reading it into memory and
     * parsing it just to re-write the same bytes was the second full parse of every save. A copy
     * failure (e.g. a directory occupies the backup path) is logged but never fails the save.
     */
    private static void rotateBackup(Path path, Path backup)
    {
        try
        {
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not update composition project backup {}", path, e);
        }
    }

    private static void moveIntoPlace(Path temporary, Path path) throws IOException
    {
        try
        {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (java.nio.file.AtomicMoveNotSupportedException e)
        {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {}
    }

    private static long sizeOrMinusOne(Path path)
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException e)
        {
            return -1L;
        }
    }

    private static String millis(long started, long finished)
    {
        return String.format(Locale.ROOT, "%.1f", (finished - started) / 1_000_000.0D);
    }

    /** Drops null comps/layers and makes every required list non-null. */
    private static void sanitize(CompProject project)
    {
        if (project.comps == null)
        {
            project.comps = new ArrayList<>();
        }

        project.comps.removeIf(Objects::isNull);

        for (CompDocument comp : project.comps)
        {
            if (comp.layers == null) comp.layers = new ArrayList<>();
            if (comp.markers == null) comp.markers = new ArrayList<>();
            if (comp.parameters == null) comp.parameters = new ArrayList<>();
            if (comp.audio == null) comp.audio = new ArrayList<>();

            comp.layers.removeIf(Objects::isNull);
        }
    }

    /** Runtime channels are transient: hydrate them after every load/restore. */
    private static void hydrate(CompProject project)
    {
        for (CompDocument comp : project.comps)
        {
            if (comp == null)
            {
                continue;
            }

            if (comp.layers == null)
            {
                comp.layers = new ArrayList<>();
            }

            for (CompLayer layer : comp.layers)
            {
                if (layer == null)
                {
                    continue;
                }

                layer.ensureInitialized();
                layer.loadTracks();
            }

            /* Older projects were persisted with a 12s duration while their real layers/audio
             * extended past it. Grow on load so the timeline and export immediately cover the
             * content instead of waiting for the first render tick. */
            /* The ceiling runs first: a project damaged by the old tick-as-seconds copy can carry
             * a 36 000-second canvas and layers stretched to the same end. Growing to that content
             * would re-inflate the canvas on every open. */
            comp.clampToMaxDuration();
            comp.extendDurationToContent();
        }
    }

    /**
     * Keeps an unreadable project file for inspection instead of overwriting it, then returns null
     * so the caller can show a load failure. A copy failure is reported but must not hide the
     * original error.
     */
    private static CompProject quarantine(Path path, String reason, Throwable cause)
    {
        if (cause == null)
        {
            BbsAeAddon.LOGGER.error("Composition project {} is unusable ({}); keeping the original file", path, reason);
        }
        else
        {
            BbsAeAddon.LOGGER.error("Composition project {} is unusable ({}); keeping the original file", path, reason, cause);
        }

        try
        {
            if (!Files.isRegularFile(path))
            {
                return null;
            }

            Path target = quarantinePath(path);

            Files.copy(path, target);
            BbsAeAddon.LOGGER.warn("Quarantined unreadable composition project as {}", target);
        }
        catch (Exception copyError)
        {
            BbsAeAddon.LOGGER.error("Could not quarantine composition project {}", path, copyError);
        }

        return null;
    }

    private static Path quarantinePath(Path path)
    {
        String base = path.getFileName() + ".corrupt-" + System.currentTimeMillis();
        Path target = path.resolveSibling(base);
        int suffix = 2;

        while (Files.exists(target))
        {
            target = path.resolveSibling(base + "-" + suffix);
            suffix += 1;
        }

        return target;
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
        project.settings.logicVersion = 1;
        CompMediaLibrary.ensureDefaultBin(project);

        CompDocument comp = project.getCompOrFirst(DEFAULT_COMP);

        if (comp != null)
        {
            comp.width = Math.max(16, width);
            comp.height = Math.max(16, height);
            comp.fps = Math.max(1F, fps);
            comp.duration = CompDocument.clampDuration(duration);
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

                        if (!isValid(project))
                        {
                            return;
                        }

                        CompDocument comp = firstComp(project);

                        if (comp == null)
                        {
                            return;
                        }

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

    /**
     * Deletes a project, its backups/temporary/quarantined copies and its asset folder; only used
     * from the start screen's context menu. A failed auxiliary deletion is logged but does not hide
     * the result for the main file.
     */
    public static synchronized boolean deleteProject(String projectId)
    {
        String id = safe(projectId);

        if (DEFAULT_PROJECT.equals(id))
        {
            return false;
        }

        Path path = file(id);
        boolean removed;

        try
        {
            removed = Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not delete composition project {}", id, e);

            removed = false;
        }

        deleteFile(path.resolveSibling(path.getFileName() + ".bak"));
        deleteFile(path.resolveSibling(path.getFileName() + ".tmp"));
        deleteQuarantinedCopies(path);
        deleteDirectory(assetRoot(id));

        CACHE.remove(id);
        DOCUMENT_CACHE.clear();
        revision += 1;

        return removed;
    }

    private static void deleteFile(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not delete composition project auxiliary file {}", path, e);
        }
    }

    private static void deleteQuarantinedCopies(Path path)
    {
        Path parent = path.getParent();

        if (parent == null || !Files.isDirectory(parent))
        {
            return;
        }

        String prefix = path.getFileName() + ".corrupt-";

        try (java.util.stream.Stream<Path> stream = Files.list(parent))
        {
            stream.filter((candidate) -> candidate.getFileName().toString().startsWith(prefix))
                .forEach(CompStore::deleteFile);
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not clean quarantined composition projects next to {}", path, e);
        }
    }

    private static void deleteDirectory(Path directory)
    {
        if (directory == null || !Files.isDirectory(directory))
        {
            return;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(directory))
        {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(CompStore::deleteFile);
        }
        catch (IOException e)
        {
            BbsAeAddon.LOGGER.warn("Could not delete composition project directory {}", directory, e);
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
                /* A film's camera placeholder can reserve half an hour for a twelve-second shot.
                 * Inheriting it here is what made every new replay composition start stretched. */
                comp.duration = CompDocument.adaptiveInitialDuration(duration);
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
            comp.duration = CompDocument.adaptiveInitialDuration(duration);
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
            comp.duration = CompDocument.adaptiveInitialDuration(duration);
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

    /**
     * True when the layer still owns animation channels for that effect id.
     *
     * <p>Deleting an effect intentionally keeps its {@code fx/<id>/...} channels, because level
     * undo or an accidental delete must not silently destroy an animation. The id is therefore
     * still "in use" even with no effect instance, and the allocator below must not hand it out
     * again — a reused id would adopt the old curves, i.e. a new effect would start out animated
     * with the deleted one's keys.</p>
     */
    public static boolean hasEffectChannels(CompLayer layer, String effectId)
    {
        if (layer == null || effectId == null || effectId.isEmpty())
        {
            return false;
        }

        String prefix = CompLayer.effectPath(effectId, "");

        /* The live channels are the normal case; the serialized map is checked too because a
         * layer that was just deserialized (or whose tracks were not loaded yet) only has the
         * map, and allocating an id it still uses would be the very bug this guard prevents. */
        for (String path : layer.animatedPaths())
        {
            if (path != null && path.startsWith(prefix))
            {
                return true;
            }
        }

        if (layer.channels != null)
        {
            for (String path : layer.channels.keySet())
            {
                if (path != null && path.startsWith(prefix))
                {
                    return true;
                }
            }
        }

        return false;
    }

    public static String uniqueEffectId(CompLayer layer)
    {
        int serial = 1;

        while (layer.getEffect("fx" + serial) != null || hasEffectChannels(layer, "fx" + serial))
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

        /* v6: record the 3D logic the file was authored with. Older files never recorded it, so a
         * future logic migration could not tell an AE project from a legacy one; derive the anchor
         * once from the current toggle without changing any visual default. */
        if (project.settings.logicVersion == null)
        {
            project.settings.logicVersion = project.settings.legacy3DLogic ? 2 : 1;
        }

        /* v7: the compositor's source browser moved from the right of the preview to the left.
         * Old files need CompDockSource to convert their effects-right default tree exactly once;
         * 0 here is the "not converted yet" marker, 1 means the current/user-chosen layout. */
        if (previousVersion < 7)
        {
            project.settings.uiLayoutRevision = 0;
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
            if (comp == null)
            {
                continue;
            }

            if (comp.layers == null) comp.layers = new ArrayList<>();
            if (comp.markers == null) comp.markers = new ArrayList<>();
            if (comp.parameters == null) comp.parameters = new ArrayList<>();
            if (comp.audio == null) comp.audio = new ArrayList<>();
            if (comp.workAreaOut <= 0F) comp.workAreaOut = comp.duration;

            /* v4: the stage stopped being transparent. Only files written before v4 are touched;
             * a project saved with a deliberately transparent stage keeps it. */
            if (previousVersion < 4 && (comp.background >>> 24) == 0)
            {
                comp.background = 0xFF000000;
            }

            for (CompLayer layer : comp.layers)
            {
                if (layer != null)
                {
                    layer.ensureInitialized();
                }
            }
        }
    }

    private static boolean isValid(CompProject project)
    {
        return project != null
            && (CompProject.FORMAT.equals(project.format) || project.version >= 1)
            && project.version >= 1
            && project.comps != null
            && firstComp(project) != null;
    }

    private static CompDocument firstComp(CompProject project)
    {
        for (CompDocument comp : project.comps)
        {
            if (comp != null)
            {
                return comp;
            }
        }

        return null;
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
        project.name = "BBS AE Demo";
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

        title.text = "BBS AE";
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

        title.insertTransformPose(0F, transform(960F, 520F));
        title.insertTransformPose(72F, transform(960F, 460F));
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

        /* Keep the parent's 3D view when a single layer is precomposed; the multi-layer path
         * already does this in CompLayerOps.precompose. Precomposing a camera itself is already
         * the child camera, so don't duplicate it. */
        if (!"camera".equals(source.type))
        {
            CompLayerOps.inheritActiveCamera(doc, sub, in, fps);
        }

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
        precomp.threeD = source.threeD;

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
