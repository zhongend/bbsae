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
}
