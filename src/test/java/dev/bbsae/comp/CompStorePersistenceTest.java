package dev.bbsae.comp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence contract of {@link CompStore}: a broken project must never be replaced by demo data,
 * a failed save must be reported instead of swallowed, and backups must only be rotated after the
 * new file is safely in place.
 */
class CompStorePersistenceTest
{
    @TempDir
    Path temp;

    @BeforeEach
    void redirectStorage()
    {
        CompStore.useRootForTests(this.temp.resolve("projects"));
    }

    @AfterEach
    void restoreStorage()
    {
        CompStore.clearRootForTests();
    }

    private static String json(CompProject project)
    {
        return CompStore.gson().toJson(project);
    }

    private static void write(Path path, String text) throws IOException
    {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static CompProject parse(Path path) throws IOException
    {
        return CompStore.gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), CompProject.class);
    }

    private static List<Path> quarantined(String id) throws IOException
    {
        String prefix = CompStore.safe(id) + ".json.corrupt-";

        try (Stream<Path> stream = Files.list(CompStore.root()))
        {
            return stream.filter((path) -> path.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    @Test
    void strictLoadOfAMissingProjectCreatesNothing() throws IOException
    {
        assertNull(CompStore.loadOrNull("missing_strict", false));
        assertFalse(Files.exists(CompStore.file("missing_strict")),
            "the render/overlay load path must not create a demo file");
    }

    @Test
    void createIfMissingStillCreatesTheDemoProject() throws IOException
    {
        CompProject created = CompStore.loadOrNull("created", true);

        assertNotNull(created);
        assertEquals("created", created.id);
        assertTrue(Files.isRegularFile(CompStore.file("created")));

        /* A second strict load must read the file, not re-create it. */
        CompProject loaded = CompStore.loadOrNull("created", true);

        assertNotNull(loaded);
        assertEquals("created", loaded.id);
    }

    @Test
    void historicalLoadStillReturnsADemoForMissingFiles() throws IOException
    {
        CompProject demo = CompStore.load("legacy_missing");

        assertNotNull(demo);
        assertEquals("legacy_missing", demo.id);
        assertTrue(Files.isRegularFile(CompStore.file("legacy_missing")));
    }

    @Test
    void corruptFileIsQuarantinedAndNeverOverwrittenWithDemoData() throws IOException
    {
        String id = "corrupt";
        String original = "{ this is not a composition project";
        Path file = CompStore.file(id);

        write(file, original);

        assertNull(CompStore.loadOrNull(id, false));

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8),
            "a corrupt project must stay on disk untouched");

        List<Path> copies = quarantined(id);

        assertEquals(1, copies.size(), "the unreadable file must be quarantined exactly once");
        assertEquals(original, Files.readString(copies.get(0), StandardCharsets.UTF_8));

        /* The legacy accessor still has to return a usable project for old callers, but it must not
         * silently save that demo over the user's corrupt file. */
        assertNotNull(CompStore.load(id));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void invalidProjectShapeIsQuarantinedToo() throws IOException
    {
        String id = "invalid_shape";
        Path file = CompStore.file(id);

        write(file, "{\"format\":\"bbsae\",\"version\":5,\"comps\":[]}");

        assertNull(CompStore.loadOrNull(id, false));
        assertEquals(1, quarantined(id).size());
        assertEquals("{\"format\":\"bbsae\",\"version\":5,\"comps\":[]}",
            Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aFileFromANewerBuildIsReadOnly() throws IOException
    {
        String id = "future";
        CompProject future = CompStore.newProject(id, "Future");

        future.version = CompProject.VERSION + 1;

        Path file = CompStore.file(id);

        write(file, json(future));

        assertNull(CompStore.loadOrNull(id, false), "a newer format must not be loaded by an older build");
        assertTrue(quarantined(id).isEmpty(), "a newer file is not corrupt, so it must not be quarantined");
        assertEquals(CompProject.VERSION + 1, parse(file).version);

        /* Even if some code path constructed a demo in memory, saving it back must be refused. */
        assertFalse(CompStore.saveChecked(CompStore.newProject(id, "Downgrade")));
        assertNotNull(CompStore.lastSaveError());
        assertEquals(CompProject.VERSION + 1, parse(file).version,
            "the newer file must survive a downgrade attempt byte-for-byte in its version field");
    }

    @Test
    void saveCheckedWritesAndRotatesTheBackupOnlyAfterTheMainWrite() throws IOException
    {
        String id = "saved";
        CompProject project = CompStore.newProject(id, "保存测试");

        project.comps.get(0).layers.add(new CompLayer("layer", "Layer", "solid"));

        assertTrue(CompStore.saveChecked(project));
        assertNull(CompStore.lastSaveError());

        Path file = CompStore.file(id);
        Path backup = file.resolveSibling(file.getFileName() + ".bak");

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(backup), "the first save has no previous file to rotate");

        /* The file must be valid UTF-8 (no BOM) and round-trip the Chinese name. */
        byte[] bytes = Files.readAllBytes(file);

        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF,
            "projects must be written as UTF-8 without a BOM");
        assertEquals("保存测试", parse(file).name);

        project.comps.get(0).width = 1234;

        assertTrue(CompStore.saveChecked(project));
        assertTrue(Files.isRegularFile(backup), "the second save must rotate the previous file into .bak");
        assertEquals(1920, parse(backup).comps.get(0).width, "the backup must hold the previous revision");
        assertEquals(1234, parse(file).comps.get(0).width);
    }

    @Test
    void failedSaveReportsAnErrorAndCleansItsTemporaryFile() throws IOException
    {
        String id = "failing";
        CompProject project = CompStore.newProject(id, "Fail");
        Path file = CompStore.file(id);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        /* A directory at the temp path makes the write fail deterministically. */
        Files.createDirectories(temporary);

        assertFalse(CompStore.saveChecked(project));
        assertNotNull(CompStore.lastSaveError());
        assertFalse(Files.exists(temporary), "a failed save must not leave its temporary file behind");
        assertFalse(Files.exists(file));
    }

    @Test
    void backupRotationFailureDoesNotFailTheMainSave() throws IOException
    {
        String id = "backup_failure";
        CompProject project = CompStore.newProject(id, "Backup");
        Path file = CompStore.file(id);

        assertTrue(CompStore.saveChecked(project));

        Path backup = file.resolveSibling(file.getFileName() + ".bak");

        /* A directory at the backup path makes the rotation fail after the main move succeeded. */
        Files.createDirectories(backup);

        project.comps.get(0).width = 640;

        assertTrue(CompStore.saveChecked(project), "a .bak failure must not turn a successful save into a failure");
        assertNull(CompStore.lastSaveError());
        assertEquals(640, parse(file).comps.get(0).width);
    }

    @Test
    void nullCompAndLayerElementsAreSkippedInsteadOfThrowing() throws IOException
    {
        String id = "nulls";
        CompProject project = CompStore.newProject(id, "Nulls");
        CompDocument comp = project.comps.get(0);

        project.comps.add(null);
        comp.layers.add(null);
        comp.layers.add(new CompLayer("ok", "OK", "solid"));

        assertTrue(CompStore.saveChecked(project));

        CompStore.reload();

        CompProject loaded = CompStore.loadOrNull(id, false);

        assertNotNull(loaded);
        assertEquals(1, loaded.comps.size(), "null comps are not serialized as usable compositions");
        assertEquals(1, loaded.comps.get(0).layers.size(), "null layers are skipped");
        assertEquals("ok", loaded.comps.get(0).layers.get(0).id);
    }

    @Test
    void aProjectWithoutCompsGainsADefaultOneOnSave() throws IOException
    {
        String id = "no_comps";
        CompProject project = CompStore.newProject(id, "No comps");

        project.comps = null;

        assertTrue(CompStore.saveChecked(project));
        assertEquals(1, parse(CompStore.file(id)).comps.size());
    }

    @Test
    void versionOfTheInMemoryProjectCannotBeAboveTheSupportedOne()
    {
        CompProject project = CompStore.newProject("too_new", "Too new");

        project.version = CompProject.VERSION + 1;

        assertFalse(CompStore.saveChecked(project));
        assertNotNull(CompStore.lastSaveError());
        assertFalse(Files.exists(CompStore.file("too_new")));
    }

    @Test
    void deleteRemovesBackupTemporaryQuarantineAndAssets() throws IOException
    {
        String id = "delete_me";
        CompProject project = CompStore.newProject(id, "Delete");

        assertTrue(CompStore.saveChecked(project));

        Path file = CompStore.file(id);
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Path quarantine = file.resolveSibling(file.getFileName().toString() + ".corrupt-123");
        Path assets = CompStore.assetRoot(id);

        write(backup, "{}");
        write(temporary, "{}");
        write(quarantine, "broken");
        Files.createDirectories(assets.resolve("fonts"));
        write(assets.resolve("fonts").resolve("font.txt"), "font");

        assertTrue(CompStore.deleteProject(id));
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(backup), "deleteProject must clean the backup");
        assertFalse(Files.exists(temporary), "deleteProject must clean a leftover temp file");
        assertFalse(Files.exists(quarantine), "deleteProject must clean quarantined copies");
        assertFalse(Files.exists(assets), "deleteProject must delete the asset directory");
        assertFalse(CompStore.deleteProject(CompStore.DEFAULT_PROJECT), "the built-in demo cannot be deleted");
    }

    @Test
    void savingOverACorruptFileKeepsTheLastGoodBackup() throws IOException
    {
        String id = "corrupt_then_saved";
        CompProject first = CompStore.newProject(id, "Good");

        first.comps.get(0).width = 1111;
        assertTrue(CompStore.saveChecked(first));

        Path file = CompStore.file(id);
        Path backup = file.resolveSibling(file.getFileName() + ".bak");

        /* A second save creates the good backup of width 1111. */
        first.comps.get(0).width = 2222;
        assertTrue(CompStore.saveChecked(first));
        assertTrue(Files.isRegularFile(backup));
        assertEquals(1111, parse(backup).comps.get(0).width);

        /* The main file is now corrupt; a user-driven save must replace it but not destroy the
         * last readable backup with the corrupt bytes. */
        write(file, "{ corrupt on disk");

        CompProject replacement = CompStore.newProject(id, "Replacement");

        replacement.comps.get(0).width = 3333;
        assertTrue(CompStore.saveChecked(replacement));

        assertEquals(3333, parse(file).comps.get(0).width);
        assertEquals(1111, parse(backup).comps.get(0).width,
            "a corrupt main file must not be rotated over the last good .bak");
    }

    @Test
    void loadOrNullRecoversFromAnExternalEditAfterReload() throws IOException
    {
        String id = "external";
        CompProject project = CompStore.newProject(id, "First");

        assertTrue(CompStore.saveChecked(project));

        CompProject edited = parse(CompStore.file(id));

        edited.name = "Second";

        /* Bump the timestamp explicitly: some file systems have a coarse modification clock and the
         * cache would otherwise return the in-memory project. */
        Files.writeString(CompStore.file(id), json(edited), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(CompStore.file(id),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000L));

        CompStore.reload();

        CompProject loaded = CompStore.loadOrNull(id, false);

        assertNotNull(loaded);
        assertEquals("Second", loaded.name);
    }
}
