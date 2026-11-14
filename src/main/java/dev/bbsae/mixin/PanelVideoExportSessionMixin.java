package dev.bbsae.mixin;

import dev.bbsae.client.audio.CompFilmAudioExport;
import mchorse.bbs_mod.ui.film.PanelVideoExportSession;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the audio renderer which film the panel export is rendering.
 *
 * <p>The film panel can be hidden while its recorder runs, so looking the film up through the
 * dashboard during {@code AudioRenderer.renderAudio()} is not reliable. Scoping the film to the
 * prepare() call keeps the composition audio attached to the exact film being exported.</p>
 */
@Mixin(PanelVideoExportSession.class)
public class PanelVideoExportSessionMixin
{
    @Shadow
    @Final
    private UIFilmPanel editor;

    @Inject(method = "prepare", at = @At("HEAD"))
    private void bbsae$beginAudio(CallbackInfoReturnable<Boolean> info)
    {
        CompFilmAudioExport.begin(this.editor == null ? null : this.editor.getData());
    }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void bbsae$endAudio(CallbackInfoReturnable<Boolean> info)
    {
        CompFilmAudioExport.end();
    }
}
