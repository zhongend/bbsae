package dev.bbsae.client.audio;

import dev.bbsae.comp.CompAudio;
import dev.bbsae.comp.CompDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The shared film mix must be invalidated by audio edits and only by audio edits. */
public class CompMixCacheKeyTest
{
    private static CompDocument document()
    {
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.duration = 12F;

        CompAudio clip = new CompAudio("a1", "Music", "audio/bgm.wav");

        clip.start = 1F;
        clip.offset = 2F;
        clip.duration = 8F;
        doc.audio.add(clip);

        return doc;
    }

    @Test
    public void keyIsStableWhileNothingChanges()
    {
        CompDocument a = document();
        CompDocument b = document();

        assertEquals(CompMixCache.keyOf(a), CompMixCache.keyOf(b));
        assertEquals(CompMixCache.keyOf(a), CompMixCache.keyOf(a));
    }

    @Test
    public void keyChangesForEveryAudibleEdit()
    {
        String base = CompMixCache.keyOf(document());

        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.path = "audio/other.wav")));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.start = 3F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.offset = 4F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.gain = 0.5F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.pitch = 3F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.speed = 2F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.pan = -1F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.muted = true)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.loop = true)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.fadeIn = 1F)));
        assertNotEquals(base, CompMixCache.keyOf(mutated((clip) -> clip.fadeOut = 1F)));
    }

    @Test
    public void playheadAndSelectionAreNotPartOfTheKey()
    {
        /* Nothing but the document's audio list and length may change the key; the film playhead
         * is a player position, not content. */
        CompDocument doc = document();
        String key = CompMixCache.keyOf(doc);

        doc.name = "renamed";
        assertEquals(key, CompMixCache.keyOf(doc));
    }

    @Test
    public void emptyDocumentHasNoMix()
    {
        CompDocument doc = new CompDocument();

        doc.audio.clear();

        assertEquals(null, CompMixCache.get(null, doc));
    }

    private static CompDocument mutated(java.util.function.Consumer<CompAudio> mutation)
    {
        CompDocument doc = document();

        mutation.accept(doc.audio.get(0));

        return doc;
    }
}
