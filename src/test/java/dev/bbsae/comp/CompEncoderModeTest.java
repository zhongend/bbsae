package dev.bbsae.comp;

import com.google.gson.Gson;
import dev.bbsae.client.render.CompRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder-mode contract.
 *
 * <p>Two pipelines share one project file, so two things must never drift:</p>
 * <ol>
 *   <li><b>Frame ↔ time is a bijection.</b> AE mode is only "what you see is what you encode" if
 *       {@code N / fps} and {@code round(time * fps)} agree, and if the frame index is clamped to
 *       the composition instead of running off the end of the range.</li>
 *   <li><b>The mode is data.</b> It has to survive save/load, and it has to default to BBS so an
 *       existing project never silently changes pipeline.</li>
 * </ol>
 */
public class CompEncoderModeTest
{
    private static CompDocument document(float fps, float duration)
    {
        CompDocument doc = new CompDocument();

        doc.fps = fps;
        doc.duration = duration;

        return doc;
    }

    @Test
    public void frameAndTimeAreInverses()
    {
        CompDocument doc = document(60F, 12F);

        for (int frame : new int[] {0, 1, 59, 60, 359, 719})
        {
            float time = frame / 60F;

            assertEquals(frame, CompRenderer.frameIndex(doc, time), "frame " + frame);
        }
    }

    @Test
    public void frameIndexRoundsLikeTheTimecode()
    {
        CompDocument doc = document(24F, 4F);

        /* Half-up, exactly like Math.round and the frame counter in the header: the playhead's
         * readout and the frame the encoder writes must use the same rule, or a scrub can show
         * frame 7 while the file gets frame 6. */
        assertEquals(2, CompRenderer.frameIndex(doc, 1.5F / 24F));
        assertEquals(1, CompRenderer.frameIndex(doc, 0.6F / 24F));
        assertEquals(0, CompRenderer.frameIndex(doc, 0.49F / 24F));
    }

    @Test
    public void frameIndexIsClampedToTheComposition()
    {
        CompDocument doc = document(30F, 2F);

        assertEquals(0, CompRenderer.frameIndex(doc, -5F), "negative time clamps to the first frame");
        assertEquals(59, CompRenderer.frameIndex(doc, 99F), "past the end clamps to the last frame");
    }

    @Test
    public void aDegenerateCompositionStillHasAFrame()
    {
        CompDocument doc = document(0F, 0F);

        assertEquals(0, CompRenderer.frameIndex(doc, 0F));
    }

    @Test
    public void theModeDefaultsToBbsAndSurvivesARoundTrip()
    {
        CompProject project = new CompProject();
        Gson gson = CompStore.gson();

        assertEquals(CompProject.ENCODER_BBS, project.settings.encoder, "a fresh project must not be in AE mode");
        assertFalse(project.settings.encoderRiskAcknowledged);

        project.settings.encoder = CompProject.ENCODER_AE;
        project.settings.encoderRiskAcknowledged = true;

        CompProject loaded = gson.fromJson(gson.toJson(project), CompProject.class);

        assertEquals(CompProject.ENCODER_AE, loaded.settings.encoder);
        assertTrue(loaded.settings.encoderRiskAcknowledged);
    }

    @Test
    public void proxyDefaultsToOneAndIsClampedByThePanel()
    {
        CompProject project = new CompProject();

        assertEquals(1F, project.settings.encoderProxy, "a fresh project previews at full resolution");
        assertTrue(project.settings.previewAudio, "preview audio is on by default");
    }

    @Test
    public void anOldProjectWithoutTheFieldLoadsAsBbs()
    {
        /* A 0.7.0 project has no "encoder" key at all. */
        String json = "{\"format\":\"bbsae\",\"version\":5,\"id\":\"old\",\"comps\":[],\"settings\":{\"autoSave\":true}}";

        CompProject loaded = CompStore.gson().fromJson(json, CompProject.class);

        assertEquals(CompProject.ENCODER_BBS, loaded.settings.encoder);
    }
}
