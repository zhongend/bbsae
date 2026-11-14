package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duration-repair thresholds.
 *
 * <p>These mutations shrink a project's canvas, so the rule must be provable: only the exact
 * "film/placeholder duration with an empty tail" fingerprint is repaired, and real content is never
 * cut.</p>
 */
public class CompDurationRepairTest
{
    private static CompDocument doc(float duration)
    {
        CompDocument doc = new CompDocument();

        doc.duration = duration;
        doc.workAreaOut = duration;

        return doc;
    }

    private static void layer(CompDocument doc, float out)
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.out = out;
        doc.layers.add(layer);
    }

    private static void audio(CompDocument doc, float start, float length, float sourceLength)
    {
        CompAudio clip = new CompAudio("a", "A", "audio/a.wav");

        clip.start = start;
        clip.duration = length;
        clip.sourceDuration = sourceLength;
        doc.audio.add(clip);
    }

    @Test
    public void aFilmSizedEmptyTailIsRepaired()
    {
        CompDocument doc = doc(1800F);

        layer(doc, 6F);

        assertEquals(12F, CompDurationRepair.fromFilm(doc, 1800F), 0.001F,
            "the 30-minute placeholder tail becomes the usable minimum");
    }

    @Test
    public void realContentKeepsItsLongComposition()
    {
        CompDocument doc = doc(1800F);

        layer(doc, 1500F);

        assertTrue(Float.isNaN(CompDurationRepair.fromFilm(doc, 1800F)),
            "content that reaches half the duration means the length is intentional");
    }

    @Test
    public void aShortCompositionIsNeverTouched()
    {
        assertTrue(Float.isNaN(CompDurationRepair.fromFilm(doc(30F), 1800F)));
    }

    @Test
    public void aDifferentFilmLengthIsNotTheInflatedState()
    {
        CompDocument doc = doc(1800F);

        layer(doc, 6F);

        assertTrue(Float.isNaN(CompDurationRepair.fromFilm(doc, 600F)),
            "only a duration equal to the film length carries the old bug's fingerprint");
    }

    @Test
    public void audioCountsAsContent()
    {
        CompDocument doc = doc(1800F);

        audio(doc, 0F, -1F, 800F);

        assertEquals(800F, CompDurationRepair.fromFilm(doc, 1800F), 0.001F,
            "the repair must end at the audio, not cut it");
    }

    @Test
    public void aStandaloneWholeMinuteEmptyCanvasIsRepaired()
    {
        assertEquals(12F, CompDurationRepair.implausibleLong(doc(1800F)), 0.001F);
        assertEquals(12F, CompDurationRepair.implausibleLong(doc(600F)), 0.001F);
    }

    @Test
    public void aStandaloneRepairRequiresAFullWorkArea()
    {
        CompDocument doc = doc(1800F);

        doc.workAreaOut = 100F;

        assertTrue(Float.isNaN(CompDurationRepair.implausibleLong(doc)),
            "a deliberately short loop is editorial state");
    }

    @Test
    public void aStandaloneLongCompositionWithContentIsNotTouched()
    {
        CompDocument doc = doc(1800F);

        layer(doc, 400F);

        assertTrue(Float.isNaN(CompDurationRepair.implausibleLong(doc)));
    }

    @Test
    public void aNonWholeMinuteIsNotTouched()
    {
        assertTrue(Float.isNaN(CompDurationRepair.implausibleLong(doc(601F))),
            "only the round placeholder values are recognisable without a film");
    }
}
