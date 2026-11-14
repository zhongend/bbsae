package dev.bbsae.mixin;

import dev.bbsae.client.audio.CompFilmAudio;
import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds bbsae composition audio into BBS' own per-frame playback.
 *
 * <p>BBS calls {@code manageSounds()} after applying the camera timeline for the frame; that is the
 * one place where the film's audio clock (the {@link ClipContext}) and the "playing" state are
 * known. Running right after it means the composition uses exactly the same clock as BBS' audio
 * clips, so both stay in sync while playing, scrubbing and looping.</p>
 */
@Mixin(AudioClientClip.class)
public class AudioClientClipMixin
{
    @Inject(method = "manageSounds", at = @At("TAIL"))
    private static void bbsae$manageCompositionAudio(ClipContext context, CallbackInfo info)
    {
        CompFilmAudio.manage(context);
    }
}
