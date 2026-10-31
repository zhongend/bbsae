package dev.bbsae.client.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static Data data;
    private static boolean dirty;

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

        /** 0 standard, 1 focus, 2 compact, 3 wide. */
        public int layoutPreset = LAYOUT_STANDARD;
        /** UI density preference used before a project exists. */
        public int uiDensity = 0;
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
        return FabricLoader.getInstance().getConfigDir().resolve("bbsae").resolve("prefs.json");
    }

    public static synchronized Data get()
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

        try
        {
            if (Files.isRegularFile(path))
            {
                Data loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);

                if (loaded != null)
                {
                    if (loaded.keybinds == null)
                    {
                        loaded.keybinds = new LinkedHashMap<>();
                    }

                    return loaded;
                }
            }
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not load compositor preferences {}", path, e);
        }

        return new Data();
    }

    public static synchronized void save()
    {
        if (data == null)
        {
            return;
        }

        dirty = false;

        try
        {
            Path path = path();

            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not save compositor preferences {}", path(), e);
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
}
