package dev.bbsae.client.export;

import dev.bbsae.comp.CompOutputPreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which jobs may stream raw frames into FFmpeg.
 *
 * <p>The pipe is the difference between "export takes 36 seconds because PNG encoding is 40 ms a
 * frame" and "export runs at the speed of the composition". It is also the only path that carries
 * the composition raster untouched, so the guard has to be exact: a resized preset, a PNG sequence
 * or a keep-frames request must all stay on the file path.</p>
 */
public class CompExportPipeTest
{
    @Test
    public void fullSizeVideoStreamsRawFrames()
    {
        assertTrue(CompExportManager.usesPipe(CompOutputPreset.reviewH264(), false, 1920, 1080, 1920, 1080));
        assertTrue(CompExportManager.usesPipe(CompOutputPreset.masterProRes(), false, 2560, 1440, 2560, 1440));
        assertTrue(CompExportManager.usesPipe(CompOutputPreset.alphaProRes(), false, 1920, 1080, 1920, 1080));
    }

    @Test
    public void resizedPresetsKeepTheResamplerPath()
    {
        assertFalse(CompExportManager.usesPipe(CompOutputPreset.reviewH264(), false, 1280, 720, 1920, 1080));
    }

    @Test
    public void sequencesAndAudioNeverUseThePipe()
    {
        assertFalse(CompExportManager.usesPipe(CompOutputPreset.pngSequence(), false, 1920, 1080, 1920, 1080));
        assertFalse(CompExportManager.usesPipe(CompOutputPreset.audioWav(), true, 1920, 1080, 1920, 1080));
        assertFalse(CompExportManager.usesPipe(CompOutputPreset.audioM4a(), true, 1920, 1080, 1920, 1080));
    }

    @Test
    public void keepingFramesWinsOverThePipe()
    {
        CompOutputPreset preset = CompOutputPreset.reviewH264();

        preset.keepFrames = true;

        assertFalse(CompExportManager.usesPipe(preset, false, 1920, 1080, 1920, 1080),
            "keeping frames is a request for PNGs on disk");
    }
}
