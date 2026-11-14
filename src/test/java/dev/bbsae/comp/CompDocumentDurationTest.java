package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition is the union of its timeline content: layers, audio clips and explicit user
 * duration. The old hard 12 s default silently capped both drags and audio imports.
 */
public class CompDocumentDurationTest
{
    @Test
    public void theLastLayerExtendsTheComposition()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = doc.addLayer(new CompLayer("layer", "Layer", "solid"));

        layer.in = 5F;
        layer.out = 30F;

        assertTrue(doc.extendDurationToContent());
        assertEquals(30F, doc.duration, 0.001F);
        assertEquals(30F, doc.workAreaOut, 0.001F);
    }

    @Test
    public void theLastAudioClipExtendsTheComposition()
    {
        CompDocument doc = new CompDocument();
        CompAudio audio = new CompAudio("audio", "Music", "audio/music.wav");

        audio.start = 20F;
        audio.duration = 15F;
        doc.audio.add(audio);

        assertTrue(doc.extendDurationToContent());
        assertEquals(35F, doc.duration, 0.001F);
    }

    @Test
    public void draggingALayerPastTheOldEndIsNotClamped()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = doc.addLayer(new CompLayer("layer", "Layer", "solid"));

        layer.in = 0F;
        layer.out = 10F;
        layer.moveTo(40F, 20F, doc.duration);

        assertEquals(40F, layer.in, 0.001F);
        assertEquals(50F, layer.out, 0.001F);

        layer.trimOutTo(90F, 20F, doc.duration);
        assertEquals(90F, layer.out, 0.001F);

        doc.extendDurationToContent();
        assertEquals(90F, doc.duration, 0.001F);
    }

    @Test
    public void durationNeverShrinksAutomatically()
    {
        CompDocument doc = new CompDocument();

        doc.duration = 120F;
        doc.workAreaOut = 120F;
        doc.addLayer(new CompLayer("short", "Short", "solid")).out = 4F;

        assertFalse(doc.extendDurationToContent());
        assertEquals(120F, doc.duration, 0.001F);
    }

    @Test
    public void addingALayerAlreadyExtendsTheDocument()
    {
        CompDocument doc = new CompDocument();
        CompLayer layer = new CompLayer("long", "Long", "solid");

        layer.out = 40F;
        doc.addLayer(layer);

        assertEquals(40F, doc.duration, 0.001F);
    }

    @Test
    public void aNewFilmCompositionGetsAShortAdaptiveCanvas()
    {
        assertEquals(12F, CompDocument.adaptiveInitialDuration(0F), 0.001F);
        assertEquals(12F, CompDocument.adaptiveInitialDuration(Float.NaN), 0.001F);
        assertEquals(30F, CompDocument.adaptiveInitialDuration(30F), 0.001F);
        assertEquals(60F, CompDocument.adaptiveInitialDuration(1800F), 0.001F,
            "a 30-minute camera placeholder must not become the composition's initial length");
        assertEquals(60F, CompDocument.adaptiveInitialDuration(36000F), 0.001F);
    }

    @Test
    public void durationIsHardCappedAtTheCanvasCeiling()
    {
        assertEquals(12F, CompDocument.clampDuration(0F), 0.001F);
        assertEquals(1800F, CompDocument.clampDuration(36000F), 0.001F);
        assertEquals(1800F, CompDocument.clampDuration(Float.POSITIVE_INFINITY), 0.001F);
        assertEquals(45F, CompDocument.clampDuration(45F), 0.001F);
    }

    @Test
    public void clampingAHugeCanvasAlsoPullsItsContentBack()
    {
        CompDocument doc = new CompDocument();

        doc.duration = 36000F;
        doc.workAreaOut = 36000F;

        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.out = 36000F;
        doc.layers.add(layer);

        CompAudio audio = new CompAudio("a", "A", "audio/a.wav");

        audio.start = 0F;
        audio.duration = 36000F;
        doc.audio.add(audio);

        assertTrue(doc.clampToMaxDuration());
        assertEquals(CompDocument.MAX_CANVAS, doc.duration, 0.001F);
        assertEquals(CompDocument.MAX_CANVAS, layer.out, 0.001F);
        assertEquals(CompDocument.MAX_CANVAS, audio.duration, 0.001F);
        assertFalse(doc.extendDurationToContent(), "clamped content must not re-inflate the canvas");
    }

    @Test
    public void aReasonableCanvasIsNotClamped()
    {
        CompDocument doc = new CompDocument();

        doc.duration = 90F;
        doc.workAreaOut = 90F;
        CompLayer layer = new CompLayer("l", "L", "solid");

        layer.out = 60F;
        doc.layers.add(layer);

        assertFalse(doc.clampToMaxDuration());
        assertEquals(90F, doc.duration, 0.001F);
    }

    @Test
    public void fitEndUsesTheLastVisualLayerNotTheEmptyTail()
    {
        CompDocument doc = new CompDocument();

        doc.duration = 90F;
        doc.workAreaOut = 90F;

        CompLayer content = new CompLayer("content", "Content", "solid");

        content.in = 2F;
        content.out = 12F;
        doc.layers.add(content);

        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.out = 90F;
        doc.layers.add(camera);

        assertEquals(12F, doc.fitEnd(), 0.001F,
            "a canvas-long camera must not stretch the fit past the visual edit");
        assertEquals(90F, doc.duration, 0.001F, "fit must not mutate editorial duration");
    }

    @Test
    public void fitEndIncludesTheLastAudioClip()
    {
        CompDocument doc = new CompDocument();

        doc.duration = 90F;

        CompAudio audio = new CompAudio("a", "Music", "audio/music.wav");

        audio.start = 20F;
        audio.duration = 5F;
        doc.audio.add(audio);

        assertEquals(25F, doc.fitEnd(), 0.001F);
    }

    @Test
    public void anEmptyCompositionFitsItsCanvas()
    {
        CompDocument doc = new CompDocument();

        doc.duration = 30F;

        assertEquals(30F, doc.fitEnd(), 0.001F,
            "with no layers/audio there is no content end; keep the canvas usable");
    }
}
