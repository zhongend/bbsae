package dev.bbsae.client.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bbsae.BbsAeAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global (cross-project) compositor preferences.
 *
 * <p>Project settings contain editorial choices. These are <b>application</b> choices: motion
 * intensity, onboarding state, the layout a new user picked and their key bindings. They must be
 * available before a project exists (the onboarding screen opens first), so they cannot live in
 * {@code CompProject}. A small JSON file under {@code config/bbsae/prefs.json} is enough.</p>
 *
 * <p>The class is deliberately forgiving: a missing/corrupt file becomes defaults, and every save
 * is best-effort. Preferences must never prevent the editor from opening.</p>
 */
public final class CompPrefs
{
    public static final int ONBOARDING_VERSION = 1;
    public static final int LAYOUT_STANDARD = 0;
    public static final int LAYOUT_FOCUS = 1;
    public static final int LAYOUT_COMPACT = 2;
    public static final int LAYOUT_WIDE = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Data data;
    private static boolean dirty;
    /** Redirects {@link #path()} in tests (Fabric's config dir does not exist headless). */
    private static volatile Path pathOverride;

    private CompPrefs()
    {}

    public static final class KeyData
    {
        public int key;
        public boolean ctrl;
        public boolean shift;
        public boolean alt;
        /** True when the user explicitly removed the default binding. */
        public boolean cleared;

        public KeyData()
        {}

        public KeyData(int key, boolean ctrl, boolean shift, boolean alt)
        {
            this.key = key;
            this.ctrl = ctrl;
            this.shift = shift;
            this.alt = alt;
        }
    }

    public static final class Data
    {
        public int onboardingVersion = 0;
        public boolean onboardingSeen = false;

        /* Motion. All values are user-tunable in Settings → 动效与光效. */
        public boolean animations = true;
        public float motionIntensity = 1F;
        public float motionSpeed = 1F;
        public boolean glow = true;
        public boolean shadows = true;
        public float backdropIntensity = 1F;

        /* Overlay wireframes: gizmos, selection boxes, guides and camera frustums. Kept together so
         * "the handles are in the way" has one switch instead of a hunt through the UI. */
        public boolean gizmos = true;
        public float gizmoOpacity = 1F;
        public float gizmoThickness = 1F;
        public boolean selectionBoxes = true;
        public boolean cameraGizmos = true;
        public boolean guides = true;
        /** Preview HUD text (mode/pose/camera readouts and the resolution strip). */
        public boolean previewHints = true;
        /** Contextual tips for multi-selection / precomp / empty states. */
        public boolean contextHints = true;
        /** When selecting an independent 3D model, temporarily hide decorative layers. */
        public boolean modelFocus = false;
        /** When entering replay mode, temporarily show only models/text/camera. */
        public boolean replayIsolation = false;
        /**
         * Composition preview inside the replay editor:
         * 0 = off, 1 = subwindow (picture-in-picture), 2 = two windows (3D left, comp right).
         */
        public int replayCompPreview = 0;
        /** Link WAV/OGG audio from its original path instead of copying it into the project. */
        public boolean linkExternalAssets = true;
        /** Keep BBS' editor onion skins off while the compositor is open: their ghost frames can
         * bleed into the camera preview and read as duplicated shapes/text. */
        public boolean suppressOnionSkin = true;
        /** Optional per-pixel depth in the main composition; off because it duplicated layer
         * content in camera preview (free 3D viewport depth is unaffected). */
        public boolean perPixelDepth = false;

        /**
         * Per-pixel depth mode: 0 = off, 1 = on, 2 = auto (only when depth actually matters:
         * ≥2 perspective 3D layers whose projected bounds overlap and whose planes are not
         * parallel). Auto is the default: it is pixel-identical to off for the common cases
         * (parallel planes / single 3D layer / 2D comps) and fixes the ghosting only when the
         * painter sort is provably wrong.
         */
        public int perPixelDepthMode = 2;

        /**
         * Pen tool "keep vertex count" (AE option): inserting/deleting an anchor keeps the same
         * number of vertices by remapping the other points instead of adding a new one.
         */
        public boolean penKeepVertexCount = true;

        /**
         * BBS-native dockable panel layout in the compositor (splitter drag, drag-to-dock, tab
         * stacks). Read when the compositor panel is constructed: toggling it takes effect the next
         * time the panel is created. Off falls back to the fixed three-column splitter layout.
         */
        public boolean dockLayout = true;

        /** Saved vertical zoom of the two track editors; 0 = use the editor's default. */
        public int timelineRowHeight = 0;
        public int keyframeRowHeight = 0;

        /** 0 standard, 1 focus, 2 compact, 3 wide. */
        public int layoutPreset = LAYOUT_STANDARD;
        /** UI density preference used before a project exists. */
        public int uiDensity = 0;
        /** Reveal/focus the AE curve when an effect parameter gets a keyframe. */
        public boolean autoEffectCurve = true;
        /** 0 Premiere, 1 BBS. The active project still stores its own scheme. */
        public int shortcutScheme = 0;
        /** action id -> user override */
        public Map<String, KeyData> keybinds = new LinkedHashMap<>();

        public boolean needsOnboarding()
        {
            return !this.onboardingSeen || this.onboardingVersion < ONBOARDING_VERSION;
        }
    }

    public static Path path()
    {
        Path override = pathOverride;

        if (override != null)
        {
            return override;
        }

        try
        {
            Path config = FabricLoader.getInstance().getConfigDir();

            if (config != null)
            {
                return config.resolve("bbsae").resolve("prefs.json");
            }
        }
        catch (Throwable ignored)
        {
            /* Headless unit tests (and the very first moments before the game directory exists)
             * have no Fabric config directory; the loader can even throw while probing it. Falling
             * back to the OS temp directory keeps "preferences never block the editor" true. */
        }

        return Path.of(System.getProperty("java.io.tmpdir", "."), "bbsae-headless",
            Long.toHexString(ProcessHandle.current().pid()), "prefs.json");
    }

    /**
     * Test seam: redirects the preferences file to a temporary path. Package-private because the
     * game must always use Fabric's config directory.
     */
    static void usePathForTests(Path path)
    {
        pathOverride = path;
        resetForTests();
    }

    static void clearPathForTests()
    {
        pathOverride = null;
        resetForTests();
    }

    /** Forgets the in-memory copy and dirty flag so the next {@link #get()} re-reads the file. */
    static synchronized void resetForTests()
    {
        data = null;
        dirty = false;
    }

    /**
     * Preferences are read dozens of times per UI frame (density/animation/grid checks); the
     * synchronized getter serialised the whole render thread against any background save. The
     * first call still synchronises, later calls are a volatile read of the built instance.
     */
    public static Data get()
    {
        Data current = data;

        if (current != null)
        {
            return current;
        }

        return loadInitial();
    }

    private static synchronized Data loadInitial()
    {
        if (data == null)
        {
            data = load();
        }

        return data;
    }

    private static Data load()
    {
        Path path = path();
        Data loaded = read(path);

        if (loaded == null)
        {
            Path backup = backup(path);

            loaded = read(backup);

            if (loaded != null)
            {
                BbsAeAddon.LOGGER.warn("Recovered compositor preferences from backup {}", backup);

                /* Rewrite the main file on the next flush so a one-off corrupt read does not keep
                 * forcing the slower backup path. */
                dirty = true;
            }
        }

        if (loaded == null)
        {
            return new Data();
        }

        if (loaded.keybinds == null)
        {
            loaded.keybinds = new LinkedHashMap<>();
        }

        return loaded;
    }

    private static Data read(Path path)
    {
        if (path == null || !Files.isRegularFile(path))
        {
            return null;
        }

        try
        {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not load compositor preferences {}", path, e);

            return null;
        }
    }

    private static Path backup(Path path)
    {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    /**
     * Saves preferences through {@code .tmp} + atomic move, then rotates the previous bytes into
     * {@code .bak}. The backup is written only after the main file was replaced; a backup failure
     * must not cost the user the settings that were just saved.
     */
    public static synchronized void save()
    {
        if (data == null)
        {
            return;
        }

        dirty = false;

        Path path = path();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(path.getParent());

            byte[] previous = null;

            if (Files.isRegularFile(path))
            {
                try
                {
                    String oldJson = Files.readString(path, StandardCharsets.UTF_8);

                    /* Only rotate a readable revision into .bak. After recovering from .bak, the
                     * main file is the corrupt one; rotating it would overwrite the good backup. */
                    if (GSON.fromJson(oldJson, Data.class) != null)
                    {
                        previous = oldJson.getBytes(StandardCharsets.UTF_8);
                    }
                }
                catch (Exception e)
                {
                    BbsAeAddon.LOGGER.warn("Could not read previous compositor preferences for backup {}", path, e);
                }
            }

            Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);

            try
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }

            if (previous != null && previous.length > 0)
            {
                try
                {
                    Files.write(backup(path), previous);
                }
                catch (IOException e)
                {
                    BbsAeAddon.LOGGER.warn("Could not update compositor preferences backup {}", backup(path), e);
                }
            }
        }
        catch (Exception e)
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException ignored)
            {}

            BbsAeAddon.LOGGER.warn("Could not save compositor preferences {}", path, e);
        }
    }

    public static synchronized void markDirty()
    {
        dirty = true;
    }

    public static synchronized void flush()
    {
        if (dirty)
        {
            save();
        }
    }

    public static void setOnboardingDone()
    {
        Data value = get();

        value.onboardingSeen = true;
        value.onboardingVersion = ONBOARDING_VERSION;
        save();
    }

    public static void replayOnboarding()
    {
        Data value = get();

        value.onboardingSeen = false;
        value.onboardingVersion = 0;
        save();
    }

    public static float intensity()
    {
        Data value = get();

        return Math.max(0F, Math.min(1.5F, value.motionIntensity));
    }

    public static float speed()
    {
        Data value = get();

        return Math.max(0.25F, Math.min(3F, value.motionSpeed));
    }

    public static boolean animating()
    {
        return get().animations;
    }

    /** 0 = off, 1 = subwindow, 2 = two windows. Unknown values fall back to the subwindow. */
    public static int replayCompPreview()
    {
        int mode = get().replayCompPreview;

        return mode < 0 || mode > 2 ? 0 : mode;
    }
}
