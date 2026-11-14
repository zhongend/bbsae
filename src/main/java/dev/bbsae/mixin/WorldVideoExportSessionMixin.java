package dev.bbsae.mixin;

import dev.bbsae.client.audio.CompFilmAudioExport;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.WorldVideoExportSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** F6 export counterpart of {@link PanelVideoExportSessionMixin}. */
@Mixin(WorldVideoExportSession.class)
public class WorldVideoExportSessionMixin
{
    @Shadow
    private Film film;

    @Inject(method = "prepare", at = @At("HEAD"))
    private void bbsae$beginAudio(CallbackInfoReturnable<Boolean> info)
    {
        CompFilmAudioExport.begin(this.film);
    }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void bbsae$endAudio(CallbackInfoReturnable<Boolean> info)
    {
        CompFilmAudioExport.end();
    }
}
