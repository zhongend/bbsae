package dev.bbsae.client.ui;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preferences are application state, not a document: they must survive a crash in the middle of a
 * write, fall back to the last good backup, and never leave a half-written JSON file behind.
 */
class CompPrefsAtomicWriteTest
{
    private static final Gson GSON = new Gson();

    @TempDir
    Path temp;

    private Path prefs;

    @BeforeEach
    void redirectPreferences()
    {
        this.prefs = this.temp.resolve("bbsae").resolve("prefs.json");
        CompPrefs.usePathForTests(this.prefs);
    }

    @AfterEach
    void restorePreferences()
    {
        CompPrefs.clearPathForTests();
    }

    private Path backup()
    {
        return this.prefs.resolveSibling(this.prefs.getFileName() + ".bak");
    }

    private Path temporary()
    {
        return this.prefs.resolveSibling(this.prefs.getFileName() + ".tmp");
    }

    private CompPrefs.Data read(Path path) throws IOException
    {
        return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), CompPrefs.Data.class);
    }

    @Test
    void saveWritesUtf8WithoutBomAndLeavesNoTemporaryFile() throws IOException
    {
        CompPrefs.Data data = CompPrefs.get();

        data.motionIntensity = 0.42F;
        data.keybinds.put("test", new CompPrefs.KeyData(65, true, false, false));
        CompPrefs.markDirty();
        CompPrefs.flush();

        assertTrue(Files.isRegularFile(this.prefs));
        assertFalse(Files.exists(this.temporary()), "a successful save must not leave the temp file behind");

        byte[] bytes = Files.readAllBytes(this.prefs);

        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF,
            "preferences must be written as UTF-8 without a BOM");

        CompPrefs.Data parsed = this.read(this.prefs);

        assertEquals(0.42F, parsed.motionIntensity, 0.0001F);
        assertNotNull(parsed.keybinds);
        assertTrue(parsed.keybinds.containsKey("test"));
    }

    @Test
    void secondSaveRotatesThePreviousContentIntoTheBackup() throws IOException
    {
        CompPrefs.get().motionIntensity = 0.3F;
        CompPrefs.save();

        CompPrefs.get().motionIntensity = 0.8F;
        CompPrefs.save();

        assertTrue(Files.isRegularFile(this.backup()));
        assertEquals(0.3F, this.read(this.backup()).motionIntensity, 0.0001F,
            "the backup must keep the previous revision, not the new one");
        assertEquals(0.8F, this.read(this.prefs).motionIntensity, 0.0001F);
    }

    @Test
    void corruptMainFallsBackToTheBackup() throws IOException
    {
        Files.createDirectories(this.prefs.getParent());
        Files.writeString(this.prefs, "{ definitely not json", StandardCharsets.UTF_8);

        CompPrefs.Data backup = new CompPrefs.Data();

        backup.motionIntensity = 0.66F;
        Files.writeString(this.backup(), GSON.toJson(backup), StandardCharsets.UTF_8);

        CompPrefs.resetForTests();

        assertEquals(0.66F, CompPrefs.get().motionIntensity, 0.0001F,
            "a corrupt prefs file must recover the last backup instead of resetting everything");
    }

    @Test
    void missingMainIsRecoveredFromTheBackup() throws IOException
    {
        CompPrefs.Data backup = new CompPrefs.Data();

        backup.motionIntensity = 0.55F;
        Files.createDirectories(this.prefs.getParent());
        Files.writeString(this.backup(), GSON.toJson(backup), StandardCharsets.UTF_8);

        assertEquals(0.55F, CompPrefs.get().motionIntensity, 0.0001F);
    }

    @Test
    void recoveryFromBackupKeepsTheGoodBackupWhenTheCorruptMainIsRewritten() throws IOException
    {
        Files.createDirectories(this.prefs.getParent());
        Files.writeString(this.prefs, "{ corrupt", StandardCharsets.UTF_8);

        CompPrefs.Data backup = new CompPrefs.Data();

        backup.motionIntensity = 0.66F;
        Files.writeString(this.backup(), GSON.toJson(backup), StandardCharsets.UTF_8);

        CompPrefs.resetForTests();

        CompPrefs.Data recovered = CompPrefs.get();

        assertEquals(0.66F, recovered.motionIntensity, 0.0001F);

        recovered.motionIntensity = 0.9F;
        CompPrefs.save();

        assertEquals(0.9F, this.read(this.prefs).motionIntensity, 0.0001F);
        assertEquals(0.66F, this.read(this.backup()).motionIntensity, 0.0001F,
            "a corrupt main file must not replace the last good .bak during recovery");
    }

    @Test
    void corruptMainAndBackupFallBackToDefaultsWithoutThrowing() throws IOException
    {
        Files.createDirectories(this.prefs.getParent());
        Files.writeString(this.prefs, "{ broken", StandardCharsets.UTF_8);
        Files.writeString(this.backup(), "also broken", StandardCharsets.UTF_8);

        CompPrefs.resetForTests();

        CompPrefs.Data data = CompPrefs.get();

        assertEquals(1F, data.motionIntensity, 0.0001F, "both files broken must fall back to defaults");
    }

    @Test
    void failedSaveLeavesThePreviousFileIntactAndCleansUp() throws IOException
    {
        CompPrefs.get().motionIntensity = 0.25F;
        CompPrefs.save();

        /* A directory at the temp path makes the write fail deterministically. */
        Files.createDirectories(this.temporary());

        CompPrefs.get().motionIntensity = 0.9F;
        CompPrefs.save();

        assertFalse(Files.exists(this.temporary()), "a failed save must clean its temporary path");
        assertEquals(0.25F, this.read(this.prefs).motionIntensity, 0.0001F,
            "a failed write must not truncate the previous preferences");
    }

    @Test
    void backupRotationFailureDoesNotLoseTheNewSettings() throws IOException
    {
        CompPrefs.get().motionIntensity = 0.2F;
        CompPrefs.save();

        /* A directory at the backup path makes only the rotation fail. */
        Files.createDirectories(this.backup());

        CompPrefs.get().motionIntensity = 0.7F;
        CompPrefs.save();

        assertEquals(0.7F, this.read(this.prefs).motionIntensity, 0.0001F,
            "the main file is authoritative even when the backup cannot be rotated");
    }
}
