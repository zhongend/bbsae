package dev.bbsae.mixin;

import dev.bbsae.client.audio.CompFilmAudioExport;
import mchorse.bbs_mod.audio.AudioRenderer;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.film.Film;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.List;

/**
 * Adds bbsae composition audio to the WAV BBS renders for export/audio-only output.
 *
 * <p>{@code AudioRenderer.renderAudio()} is the single boundary where BBS turns the film's audio
 * clips into the track that is muxed into the video. Compositions are not audio clips, so they are
 * added here, at their camera-timeline positions. The method also becomes true when only the
 * composition has audio, which is what lets the export session pick up the generated file.</p>
 */
@Mixin(AudioRenderer.class)
public class AudioRendererMixin
{
    @Inject(method = "renderAudio", at = @At("RETURN"), cancellable = true)
    private static void bbsae$mixCompositionAudio(File file, List<AudioClip> clips, int totalDuration,
                                                 int sampleRate, float from, float to,
                                                 CallbackInfoReturnable<Boolean> info)
    {
        Film film = CompFilmAudioExport.currentFilm();

        if (film == null)
        {
            return;
        }

        if (CompFilmAudioExport.mixInto(film, file, totalDuration, sampleRate, from, to, info.getReturnValueZ()))
        {
            info.setReturnValue(true);
        }
    }
}
