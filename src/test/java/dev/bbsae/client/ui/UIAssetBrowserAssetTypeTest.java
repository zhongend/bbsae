package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** External-library file typing must accept every format the audio/codec paths accept. */
class UIAssetBrowserAssetTypeTest
{
    @Test
    void recognizesAudioExtensions()
    {
        for (String extension : new String[] {"wav", "ogg", "mp3", "flac", "m4a", "aac", "opus", "wma", "aiff", "aif"})
        {
            assertTrue(UIAssetBrowser.isAudioFile("track." + extension), extension);
            assertTrue(UIAssetBrowser.isAudioFile("TRACK." + extension.toUpperCase(java.util.Locale.ROOT)), extension);
        }

        assertFalse(UIAssetBrowser.isAudioFile("image.png"));
        assertFalse(UIAssetBrowser.isAudioFile("clip.mp4"));
    }

    @Test
    void imageAndAudioTypesDoNotOverlap()
    {
        for (String extension : new String[] {"png", "jpg", "jpeg", "gif"})
        {
            assertTrue(UIAssetBrowser.isImageFile("still." + extension));
            assertFalse(UIAssetBrowser.isAudioFile("still." + extension));
        }
    }
}
