package dev.bbsae.mixin;

import dev.bbsae.client.audio.FilmAudioAudition;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the bbsae audio browser entry to the original BBS film page.
 *
 * <p>The browser is opened as a BBS overlay from the existing film panel, so it shares the film
 * panel's context and UI lifecycle instead of becoming a second dashboard page. The icon sits at
 * the top-right of the preview, away from the recorder controls at the bottom.</p>
 */
@Mixin(UIFilmPanel.class)
public class UIFilmPanelAudioMixin
{
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsae$addAudioBrowserButton(CallbackInfo ci)
    {
        UIFilmPanel self = (UIFilmPanel) (Object) this;

        if (self.preview == null)
        {
            return;
        }

        UIIcon audio = new UIIcon(Icons.SOUND, (button) -> FilmAudioAudition.open(self));

        audio.tooltip(IKey.constant("bbsae 音频浏览：试听合成器音频文件与合成器混音"));
        audio.relative(self.preview).x(1F, -28).y(6).w(20).h(20);
        self.preview.add(audio);
    }
}
