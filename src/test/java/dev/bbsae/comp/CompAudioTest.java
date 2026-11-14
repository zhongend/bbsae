package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audio clip semantics: speed and pitch are independent, and a clip without an explicit duration
 * is as long as the source at its current speed.
 */
public class CompAudioTest
{
    @Test
    public void newClipsPlayAtUnitySpeedAndPitch()
    {
        CompAudio clip = new CompAudio("a", "A", "audio/a.wav");

        assertEquals(1F, clip.speed, 0.0001F);
        assertEquals(0F, clip.pitch, 0.0001F);
        assertEquals(0F, clip.pan, 0.0001F);
        assertEquals(1F, clip.gain, 0.0001F);
    }

    @Test
    public void lengthUsesTheSourceWhenNoDurationWasSet()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.sourceDuration = 10F;
        clip.offset = 2F;
        clip.speed = 2F;

        assertEquals(4F, clip.length(3F), 0.0001F, "(10 - 2) / 2");
        assertEquals(4F, clip.playableLength(100F), 0.0001F);
    }

    @Test
    public void explicitDurationWinsButPlayableLengthStopsAtTheSource()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.sourceDuration = 2F;
        clip.duration = 6F;

        assertEquals(6F, clip.length(12F), 0.0001F);
        assertEquals(2F, clip.playableLength(12F), 0.0001F, "a longer bar must not invent samples");
    }

    @Test
    public void retimeKeepsAnUntrimmedClipCoveringTheWholeSource()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.sourceDuration = 8F;
        clip.duration = 8F;
        clip.retimeForSpeed(2F);

        assertEquals(2F, clip.speed, 0.0001F);
        assertEquals(4F, clip.duration, 0.0001F);
        assertEquals(4F, clip.length(12F), 0.0001F);
    }

    @Test
    public void retimeDoesNotUndoATrim()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.sourceDuration = 8F;
        clip.duration = 3F;
        clip.retimeForSpeed(2F);

        assertEquals(2F, clip.speed, 0.0001F);
        assertEquals(3F, clip.duration, 0.0001F, "a deliberate trim survives a speed change");
    }

    @Test
    public void copyCarriesTheNewAudioParameters()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.gain = 0.4F;
        clip.pitch = 7F;
        clip.speed = 1.5F;
        clip.pan = -0.3F;
        clip.sourceDuration = 9F;

        CompAudio copy = clip.copy();

        assertEquals(0.4F, copy.gain, 0.0001F);
        assertEquals(7F, copy.pitch, 0.0001F);
        assertEquals(1.5F, copy.speed, 0.0001F);
        assertEquals(-0.3F, copy.pan, 0.0001F);
        assertEquals(9F, copy.sourceDuration, 0.0001F);
        assertFalse(copy == clip);
    }

    @Test
    public void invalidSpeedFallsBackToRealTime()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.speed = 0F;
        assertEquals(1F, clip.safeSpeed(), 0.0001F);

        clip.speed = 999F;
        assertEquals(8F, clip.safeSpeed(), 0.0001F);
    }

    @Test
    public void panAndPitchAreClamped()
    {
        CompAudio clip = new CompAudio("a", "A", "a.wav");

        clip.pan = -9F;
        clip.pitch = 999F;

        assertTrue(clip.safePan() >= -1F);
        assertEquals(48F, clip.safePitch(), 0.0001F);
    }
}
