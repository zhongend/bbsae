package dev.bbsae.comp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 save-path contract: saving streams JSON straight to disk, verifies the temporary file with
 * a cheap streaming scan, copies the previous revision into {@code .bak} and never builds/parses a
 * multi-megabyte String or project tree on the render thread. Undo snapshots still write every live
 * channel back, but skip the provably empty layers.
 */
class SavePathPerfTest
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

    private static CompProject projectWithLayers(String id, int layers, int keysPerLayer)
    {
        CompProject project = CompStore.newProject(id, "Perf " + id);
        CompDocument comp = project.comps.get(0);

        for (int index = 0; index < layers; index++)
        {
            CompLayer layer = new CompLayer("layer_" + index, "Layer " + index, "solid");

            layer.x = index;
            layer.opacity = 1F;

            if (keysPerLayer > 0)
            {
                for (int key = 0; key < keysPerLayer; key++)
                {
                    layer.opacityChannel().insert(key * 4F, Math.min(1F, key / (float) keysPerLayer));
                }
            }

            comp.layers.add(layer);
        }

        return project;
    }

    private static CompProject parse(Path path) throws IOException
    {
        return CompStore.gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), CompProject.class);
    }

    @Test
    void saveStreamsToDiskWithoutBuildingOrParsingAString() throws IOException
    {
        CompProject project = projectWithLayers("streamed", 40, 10);

        CompStore.resetSaveIoStatsForTests();

        assertTrue(CompStore.saveChecked(project));
        assertNull(CompStore.lastSaveError());

        CompStore.SaveIoStats stats = CompStore.saveIoStatsForTests();

        assertEquals(0L, stats.fullParses, "saveChecked must not parse the project (or the old file)");
        assertEquals(0L, stats.stringSerializations, "saveChecked must not build the JSON as one String");
        assertEquals(1L, stats.streamWrites, "the project must be streamed exactly once");
        assertEquals(1L, stats.streamScans, "only the temporary file is scanned on a first save");
        assertEquals(40L, stats.trackRebuilds, "every layer with keys is still written back");
        assertEquals(0L, stats.trackSkips);

        Path file = CompStore.file("streamed");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(temporary), "a successful save leaves no .tmp behind");
        assertTrue(CompStore.isReadableProjectFileForTests(file));

        CompStore.reload();

        CompProject loaded = CompStore.loadOrNull("streamed", false);

        assertNotNull(loaded);
        assertEquals(40, loaded.comps.get(0).layers.size());
        assertEquals(10, loaded.comps.get(0).layers.get(0).opacityChannel().getKeyframes().size(),
            "streaming the JSON must not lose keyframes");
    }

    @Test
    void backupIsAByteCopyOfThePreviousRevisionAndIsNotParsed() throws IOException
    {
        CompProject project = projectWithLayers("rotated", 6, 4);
        Path file = CompStore.file("rotated");
        Path backup = file.resolveSibling(file.getFileName() + ".bak");

        project.comps.get(0).width = 1111;
        assertTrue(CompStore.saveChecked(project));

        byte[] firstRevision = Files.readAllBytes(file);

        project.comps.get(0).width = 2222;
        CompStore.resetSaveIoStatsForTests();

        assertTrue(CompStore.saveChecked(project));

        CompStore.SaveIoStats stats = CompStore.saveIoStatsForTests();

        assertEquals(0L, stats.fullParses, "the previous file is scanned, never parsed");
        assertEquals(0L, stats.stringSerializations);
        assertEquals(2L, stats.streamScans, "one scan of the old file, one scan of the new .tmp");
        assertArrayEquals(firstRevision, Files.readAllBytes(backup),
            ".bak must be an exact byte copy of the previous revision");
        assertEquals(1111, parse(backup).comps.get(0).width);
        assertEquals(2222, parse(file).comps.get(0).width);
    }

    @Test
    void unreadablePreviousFileNeverRotatesOverTheLastGoodBackup() throws IOException
    {
        String id = "corrupt_tail";
        CompProject project = CompStore.newProject(id, "Tail");
        Path file = CompStore.file(id);
        Path backup = file.resolveSibling(file.getFileName() + ".bak");

        project.comps.get(0).width = 1111;
        assertTrue(CompStore.saveChecked(project));

        project.comps.get(0).width = 2222;
        assertTrue(CompStore.saveChecked(project));
        assertEquals(1111, parse(backup).comps.get(0).width);

        /* Starts like a valid project and has a valid header, but the document is truncated. Only
         * a whole-document scan notices this; a header-only read would rotate it into .bak. */
        Files.writeString(file, "{\"format\":\"bbsae\",\"version\":5,\"comps\":[{\"id\":\"main\"", StandardCharsets.UTF_8);

        CompProject replacement = CompStore.newProject(id, "Replacement");

        replacement.comps.get(0).width = 3333;

        CompStore.resetSaveIoStatsForTests();
        assertTrue(CompStore.saveChecked(replacement));
        assertEquals(0L, CompStore.saveIoStatsForTests().fullParses);

        assertEquals(3333, parse(file).comps.get(0).width);
        assertEquals(1111, parse(backup).comps.get(0).width,
            "an unreadable main file must not destroy the last readable backup");
    }

    @Test
    void streamingValidatorAcceptsOnlyCompleteProjectDocuments() throws IOException
    {
        Path valid = this.temp.resolve("valid.json");
        Path truncated = this.temp.resolve("truncated.json");
        Path emptyComps = this.temp.resolve("empty_comps.json");
        Path trailingComma = this.temp.resolve("trailing_comma.json");
        Path notAnObject = this.temp.resolve("not_an_object.json");

        Files.writeString(valid, "{\"format\":\"bbsae\",\"version\":5,\"comps\":[{\"id\":\"main\"}]}", StandardCharsets.UTF_8);
        Files.writeString(truncated, "{\"format\":\"bbsae\",\"version\":5,\"comps\":[{\"id\":\"main\"}", StandardCharsets.UTF_8);
        Files.writeString(emptyComps, "{\"format\":\"bbsae\",\"version\":5,\"comps\":[]}", StandardCharsets.UTF_8);
        Files.writeString(trailingComma, "{\"format\":\"bbsae\",\"version\":5,\"comps\":[],}", StandardCharsets.UTF_8);
        Files.writeString(notAnObject, "[]", StandardCharsets.UTF_8);

        assertTrue(CompStore.isReadableProjectFileForTests(valid));
        assertFalse(CompStore.isReadableProjectFileForTests(truncated), "a truncated document is not readable");
        assertFalse(CompStore.isReadableProjectFileForTests(emptyComps), "a project needs at least one comp");
        assertFalse(CompStore.isReadableProjectFileForTests(trailingComma), "strict JSON, no trailing comma");
        assertFalse(CompStore.isReadableProjectFileForTests(notAnObject));
        assertFalse(CompStore.isReadableProjectFileForTests(this.temp.resolve("missing.json")));
    }

    @Test
    void snapshotSkipsProvablyEmptyLayersAndKeepsAnimatedOnes()
    {
        CompProject project = CompStore.newProject("snapshot_tracks", "Snapshot");
        CompDocument comp = project.comps.get(0);
        CompLayer staticLayer = new CompLayer("static", "Static", "solid");
        CompLayer animated = new CompLayer("animated", "Animated", "solid");

        animated.opacityChannel().insert(0F, 0F);
        animated.opacityChannel().insert(12F, 1F);
        animated.scalarChannel("fx/fx1/amount").insert(0F, 0.25F);

        comp.layers.add(staticLayer);
        comp.layers.add(animated);

        CompStore.resetSaveIoStatsForTests();

        String json = CompStore.snapshot(project);

        CompStore.SaveIoStats stats = CompStore.saveIoStatsForTests();

        assertEquals(1L, stats.trackRebuilds, "only the animated layer needs a write-back");
        assertEquals(1L, stats.trackSkips, "a layer with no keys and no serialized channels is a no-op");
        assertEquals(1L, stats.stringSerializations);
        assertEquals(0L, stats.fullParses, "a snapshot must not parse the project back");

        CompProject restored = CompStore.restore(json);

        assertNotNull(restored);

        CompLayer restoredStatic = restored.comps.get(0).layers.get(0);
        CompLayer restoredAnimated = restored.comps.get(0).layers.get(1);

        assertFalse(restoredStatic.hasScalarChannel("x"));
        assertEquals(2, restoredAnimated.opacityChannel().getKeyframes().size());
        assertEquals(1, restoredAnimated.scalarChannel("fx/fx1/amount").getKeyframes().size());
        assertEquals(0.25F, restoredAnimated.scalarAt("fx/fx1/amount", 0F, 0F), 1.0E-6F);
    }

    @Test
    void aSecondSnapshotStillCapturesKeysAddedAfterTheFirst()
    {
        CompProject project = CompStore.newProject("snapshot_edit", "Edit");
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        project.comps.get(0).layers.add(layer);

        layer.opacityChannel().insert(0F, 0F);

        String first = CompStore.snapshot(project);

        layer.opacityChannel().insert(24F, 1F);
        layer.scalarChannel("x").insert(0F, 100F);

        String second = CompStore.snapshot(project);

        assertNotEquals(first, second);

        CompProject restoredFirst = CompStore.restore(first);
        CompProject restoredSecond = CompStore.restore(second);

        assertNotNull(restoredFirst);
        assertNotNull(restoredSecond);
        assertEquals(1, restoredFirst.comps.get(0).layers.get(0).opacityChannel().getKeyframes().size(),
            "an older snapshot must keep its own keyframe count");
        assertEquals(2, restoredSecond.comps.get(0).layers.get(0).opacityChannel().getKeyframes().size());
        assertEquals(100F, restoredSecond.comps.get(0).layers.get(0).scalarAt("x", 0F, 0F), 1.0E-6F,
            "scalar channels added after the first snapshot must reach the second one");
    }

    @Test
    void saveFailureCleansUpWhenTheProjectDirectoryCannotBeCreated() throws IOException
    {
        /* A regular file where the projects directory should be: createDirectories() fails before
         * any .tmp exists, and the failure must still be reported through lastSaveError. */
        Files.writeString(CompStore.root(), "not a directory", StandardCharsets.UTF_8);

        CompProject project = CompStore.newProject("no_dir", "No dir");

        CompStore.resetSaveIoStatsForTests();

        assertFalse(CompStore.saveChecked(project));
        assertNotNull(CompStore.lastSaveError());
        assertFalse(Files.exists(CompStore.file("no_dir")));
        assertTrue(Files.isRegularFile(CompStore.root()));
    }

    /**
     * Large-project behaviour + a printed A/B against the legacy String round-trip. The test only
     * asserts generous wall-clock bounds (a pathological regression, not machine noise); the
     * numbers it prints are the ones quoted in {@code docs/PERF-SAVE.zh.md}.
     *
     * <p>Every path is warmed up once before it is measured, and the reported value is the fastest
     * of five runs: the first multi-megabyte Gson/DataToString call in a JVM otherwise pays class
     * loading and adapter setup that has nothing to do with the save path, and other agents compile
     * on the same machine while this test runs. The minimum is the least noisy estimator of the
     * actual work; the deterministic character counts are load independent.</p>
     */
    @Test
    void largeProjectRoundTripsWithinBudget() throws IOException
    {
        CompProject project = projectWithLayers("large", 300, 17); /* 5,100 keyframes, audit scale (300 layers / 5,000 keys) */
        Path file = CompStore.file("large");
        Path legacyFile = this.temp.resolve("legacy.json");

        /* Warm-up: JIT, Gson adapters, DataToString/Keyframe classes. */
        assertFalse(CompStore.snapshot(project).isEmpty());
        assertTrue(CompStore.saveChecked(project));
        this.legacyStringRoundTripSave(project, legacyFile);

        long[] snapshotRuns = new long[5];
        long[] legacySnapshotRuns = new long[5];
        long[] saveRuns = new long[5];
        long[] legacyRuns = new long[5];
        int snapshotChars = 0;
        int legacySnapshotChars = 0;

        for (int run = 0; run < snapshotRuns.length; run++)
        {
            long start = System.nanoTime();
            String snapshot = CompStore.snapshot(project);
            snapshotRuns[run] = (System.nanoTime() - start) / 1_000_000L;
            snapshotChars = snapshot.length();
            assertFalse(snapshot.isEmpty());

            start = System.nanoTime();
            String legacySnapshot = this.legacyPrettySnapshot(project);
            legacySnapshotRuns[run] = (System.nanoTime() - start) / 1_000_000L;
            legacySnapshotChars = legacySnapshot.length();
            assertFalse(legacySnapshot.isEmpty());

            start = System.nanoTime();
            assertTrue(CompStore.saveChecked(project));
            saveRuns[run] = (System.nanoTime() - start) / 1_000_000L;

            start = System.nanoTime();
            this.legacyStringRoundTripSave(project, legacyFile);
            legacyRuns[run] = (System.nanoTime() - start) / 1_000_000L;
        }

        long snapshotMs = minimum(snapshotRuns);
        long legacySnapshotMs = minimum(legacySnapshotRuns);
        long saveMs = minimum(saveRuns);
        long legacyMs = minimum(legacyRuns);

        CompStore.reload();

        CompProject loaded = CompStore.loadOrNull("large", false);

        assertNotNull(loaded);
        assertEquals(300, loaded.comps.get(0).layers.size());
        assertEquals(17, loaded.comps.get(0).layers.get(0).opacityChannel().getKeyframes().size());

        System.out.println("[PERF-SAVE] layers=300 keys=5100 snapshot=" + snapshotMs + "ms chars=" + snapshotChars
            + " legacySnapshot=" + legacySnapshotMs + "ms chars=" + legacySnapshotChars
            + " saveChecked=" + saveMs + "ms file=" + Files.size(file) + "B legacyStringSave=" + legacyMs + "ms");

        assertTrue(snapshotMs < 15_000L, "snapshot must not fall off a cliff: " + snapshotMs + "ms");
        assertTrue(saveMs < 15_000L, "saveChecked must not fall off a cliff: " + saveMs + "ms");
        assertTrue(legacyMs < 30_000L, "legacy benchmark must not fall off a cliff: " + legacyMs + "ms");
    }

    /** The old snapshot path: write back every layer's tracks, then pretty-print one String. */
    private String legacyPrettySnapshot(CompProject project)
    {
        for (CompDocument comp : project.comps)
        {
            for (CompLayer layer : comp.layers)
            {
                layer.ensureInitialized();
                layer.saveTracks();
            }
        }

        return CompStore.gson().toJson(project);
    }

    private static long minimum(long[] values)
    {
        long[] sorted = values.clone();

        java.util.Arrays.sort(sorted);

        return sorted[0];
    }

    /** The old save path, reproduced only as a benchmark reference. */
    private void legacyStringRoundTripSave(CompProject project, Path file) throws IOException
    {
        for (CompDocument comp : project.comps)
        {
            for (CompLayer layer : comp.layers)
            {
                layer.ensureInitialized();
                layer.saveTracks();
            }
        }

        byte[] previous = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;

        if (previous != null)
        {
            CompStore.gson().fromJson(new String(previous, StandardCharsets.UTF_8), CompProject.class);
        }

        String json = CompStore.gson().toJson(project);

        CompStore.gson().fromJson(json, CompProject.class);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
