package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version migrations that change what the user sees.
 *
 * <p>Both of these were reported as bugs rather than as migrations, which is exactly why they are
 * pinned here: "the compositor background is transparent" and "exporting audio renders a video" are
 * consequences of a default, and a default only matters when an old file is opened.</p>
 */
public class CompStoreMigrationTest
{
    private static CompProject project(int version)
    {
        CompDocument comp = new CompDocument();

        comp.id = "main";
        comp.background = 0x00000000;

        CompProject project = new CompProject();

        project.version = version;
        project.comps.add(comp);
        project.settings.previewBackground = 1;
        project.settings.previewBackgroundChosen = true;
        project.outputPresets.add(CompOutputPreset.reviewH264());

        return project;
    }

    @Test
    public void transparentStagesBecomeOpaqueOnce()
    {
        CompProject project = project(3);

        CompStore.migrate(project);

        assertEquals(CompProject.VERSION, project.version);
        assertEquals(0xFF000000, project.comps.get(0).background, "a pre-v4 transparent stage must become opaque");
    }

    @Test
    public void aDeliberateTransparentStageIsKept()
    {
        /* v4 already had the opaque default, so a v4 file with alpha 0 is a choice, not a bug. */
        CompProject project = project(4);

        CompStore.migrate(project);

        assertEquals(0x00000000, project.comps.get(0).background);
    }

    @Test
    public void theWorldBackdropIsTurnedOffOnce()
    {
        CompProject project = project(4);

        CompStore.migrate(project);

        assertEquals(0, project.settings.previewBackground, "the stage must default to a solid colour");
    }

    @Test
    public void aChosenBackdropSurvivesLaterLoads()
    {
        CompProject project = project(4);

        CompStore.migrate(project);

        /* The user turns the world backdrop back on and saves. The next load must keep it. */
        project.settings.previewBackground = 1;

        CompStore.migrate(project);

        assertEquals(1, project.settings.previewBackground, "a deliberate choice must not be migrated away");
    }

    @Test
    public void oldPresetListsGainAudioModules()
    {
        CompProject project = project(4);

        assertEquals(1, project.outputPresets.size());

        CompStore.migrate(project);

        boolean wav = false;
        boolean m4a = false;

        for (CompOutputPreset preset : project.outputPresets)
        {
            assertNotNull(preset);

            if ("audio_wav".equals(preset.id)) wav = true;
            if ("audio_m4a".equals(preset.id)) m4a = true;
        }

        assertTrue(wav, "the WAV output module must be added");
        assertTrue(m4a, "the M4A output module must be added");

        /* Migrating twice must not duplicate them: audio jobs resolve by id, and a growing preset
         * list would also grow the inspector on every load. */
        int size = project.outputPresets.size();

        CompStore.migrate(project);

        assertEquals(size, project.outputPresets.size());
    }

    @Test
    public void audioModulesAreMarkedAsAudio()
    {
        assertEquals("audio", CompOutputPreset.audioWav().kind);
        assertFalse(CompOutputPreset.audioWav().alpha);
        assertEquals("wav", CompOutputPreset.audioWav().container);
        assertEquals("m4a", CompOutputPreset.audioM4a().container);
        assertEquals("video", CompOutputPreset.reviewH264().kind);
    }
    @Test
    public void threeDLogicAnchorIsRecordedForOldFiles()
    {
        /* v6 records which 3D logic a file was authored with. A pre-v6 file never had the field;
         * the migration derives it from the legacy toggle so a future logic change can migrate
         * explicitly instead of reinterpreting every project. */
        CompProject aeProject = project(5);

        aeProject.settings.legacy3DLogic = false;

        assertNull(aeProject.settings.logicVersion);

        CompStore.migrate(aeProject);

        assertEquals(1, aeProject.settings.logicVersion, "AE project must anchor to logic 1");
        assertEquals(CompProject.VERSION, aeProject.version);

        CompProject legacyProject = project(5);

        legacyProject.settings.legacy3DLogic = true;

        CompStore.migrate(legacyProject);

        assertEquals(2, legacyProject.settings.logicVersion, "legacy project must anchor to logic 2");
        assertTrue(legacyProject.settings.legacy3DLogic, "the migration must not flip the toggle");

        /* Migrating twice keeps the recorded anchor. */
        CompStore.migrate(legacyProject);

        assertEquals(2, legacyProject.settings.logicVersion);
    }

    @Test
    public void newProjectsRecordTheAeLogicAnchor()
    {
        CompProject project = CompStore.newProject("anchor-test", "Anchor");

        assertEquals(1, project.settings.logicVersion);
        assertFalse(project.settings.legacy3DLogic);
        assertEquals(CompProject.VERSION, project.version);
    }

    @Test
    public void threeDLogicAnchorSurvivesASnapshotRoundTrip()
    {
        CompProject project = project(6);

        project.settings.logicVersion = 2;
        project.settings.legacy3DLogic = true;

        CompProject restored = CompStore.restore(CompStore.snapshot(project));

        assertNotNull(restored);
        assertEquals(2, restored.settings.logicVersion);
        assertTrue(restored.settings.legacy3DLogic);
    }
}