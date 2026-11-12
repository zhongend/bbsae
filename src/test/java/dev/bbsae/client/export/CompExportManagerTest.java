package dev.bbsae.client.export;

import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompRenderJob;
import dev.bbsae.comp.CompStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export start/status rules that do not need a client or a GL context.
 *
 * <p>These cover the audit findings where an export kept reading the live project (edited frames
 * mixed into one file), a missing composition/preset produced only "unknown reason", and the
 * finish/failure message disappeared from the status line after a single frame.</p>
 */
public class CompExportManagerTest
{
    @Test
    public void exportReadsADeepSnapshotNotTheLiveProject()
    {
        CompProject project = CompStore.newProject("export-snapshot", "Export Snapshot");
        CompLayer layer = new CompLayer();

        layer.id = "layer-1";
        layer.type = "solid";
        layer.x = 10F;

        project.getCompOrFirst(CompStore.DEFAULT_COMP).layers.add(layer);

        CompProject snapshot = CompExportManager.snapshotForExport(project);

        assertNotSame(project, snapshot, "export must not read the live project");
        assertNotSame(project.getCompOrFirst(CompStore.DEFAULT_COMP), snapshot.getCompOrFirst(CompStore.DEFAULT_COMP));

        /* Editing (or undo replacing the live graph) while the session runs must not leak into
         * the frames the session renders. */
        layer.x = 999F;
        snapshot.getCompOrFirst(CompStore.DEFAULT_COMP).layers.get(0).x = 20F;

        assertEquals(999F, layer.x, 0.0001F);
        assertEquals(20F, snapshot.getCompOrFirst(CompStore.DEFAULT_COMP).layers.get(0).x, 0.0001F);
    }

    @Test
    public void startReportsAMissingComposition()
    {
        CompProject project = CompStore.newProject("export-missing-comp", "Missing Comp");
        CompRenderJob job = new CompRenderJob("job-1", project.id, "does-not-exist", "review_h264");

        assertFalse(CompExportManager.start(project, job), "a job with a missing composition must not start");
        assertEquals(CompRenderJob.FAILED, job.status);
        assertTrue(job.message.contains("合成不存在"), "the user needs the real reason: " + job.message);
    }

    @Test
    public void startReportsAMissingPreset()
    {
        CompProject project = CompStore.newProject("export-missing-preset", "Missing Preset");
        CompRenderJob job = new CompRenderJob("job-2", project.id, CompStore.DEFAULT_COMP, "nope-module");

        assertFalse(CompExportManager.start(project, job), "a job with a missing preset must not start");
        assertEquals(CompRenderJob.FAILED, job.status);
        assertTrue(job.message.contains("输出模块不存在"), "the user needs the real reason: " + job.message);
    }

    @Test
    public void lastStatusSurvivesAfterTheSessionEnds()
    {
        CompExportManager.debugRememberStatus("Done: export.mp4");

        assertEquals("Done: export.mp4", CompExportManager.status(),
            "the render page reads the status once per frame; collapsing to Idle hides the result");

        CompExportManager.debugRememberStatus("");

        assertEquals("Idle", CompExportManager.status());
    }

    @Test
    public void failedStartKeepsTheLiveJobInTheQueue()
    {
        CompProject project = CompStore.newProject("export-queue", "Export Queue");
        CompRenderJob job = new CompRenderJob("job-3", project.id, "missing", "review_h264");

        project.renderQueue.add(job);

        assertFalse(CompExportManager.start(project, job));
        assertTrue(project.renderQueue.contains(job), "the queue owns the failed row");
        assertNull(CompExportManager.sessionProjectForTests(), "no session is created for a failed start");
    }
}
