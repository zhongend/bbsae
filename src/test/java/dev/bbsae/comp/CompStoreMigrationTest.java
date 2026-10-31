package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
