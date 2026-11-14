package dev.bbsae.client.audio;

import dev.bbsae.comp.CompositionClip;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Film time → composition time is the same mapping the picture uses. Getting it wrong shifts or
 * stretches a composition's audio against its frames (a split window is the case that exposes it).
 */
public class CompFilmAudioMappingTest
{
    @Test
    public void oneToOneClip()
    {
        CompositionClip clip = clip(40, 1F, 0);

        assertEquals(0F, CompFilmAudioExport.compositionTimeAt(clip, 2F), 0.0001F);
        assertEquals(3F, CompFilmAudioExport.compositionTimeAt(clip, 5F), 0.0001F);
    }

    @Test
    public void offsetIsAppliedInCompositionTicks()
    {
        CompositionClip clip = clip(40, 1F, 20);

        /* 20 ticks = one second into the composition. */
        assertEquals(1F, CompFilmAudioExport.compositionTimeAt(clip, 2F), 0.0001F);
        assertEquals(4F, CompFilmAudioExport.compositionTimeAt(clip, 5F), 0.0001F);
    }

    @Test
    public void timeScaleWarpsTheClip()
    {
        CompositionClip clip = clip(40, 2F, 0);

        /* Two composition seconds per film second: at film 2s the window is at comp 0, at film 5s
         * it has advanced three film seconds → six composition seconds. */
        assertEquals(0F, CompFilmAudioExport.compositionTimeAt(clip, 2F), 0.0001F);
        assertEquals(6F, CompFilmAudioExport.compositionTimeAt(clip, 5F), 0.0001F);
    }

    @Test
    public void splitWindowKeepsItsOwnClipOrigin()
    {
        CompositionClip first = clip(0, 1F, 0);
        CompositionClip second = clip(100, 1F, 0);

        assertEquals(2F, CompFilmAudioExport.compositionTimeAt(first, 2F), 0.0001F);
        assertEquals(0F, CompFilmAudioExport.compositionTimeAt(second, 5F), 0.0001F);
        assertEquals(2F, CompFilmAudioExport.compositionTimeAt(second, 7F), 0.0001F);
    }

    private static CompositionClip clip(int tick, float timeScale, int offset)
    {
        CompositionClip clip = new CompositionClip();

        clip.tick.set(tick);
        clip.duration.set(200);
        clip.timeScale.set(timeScale);
        clip.offset.set(offset);

        return clip;
    }
}
