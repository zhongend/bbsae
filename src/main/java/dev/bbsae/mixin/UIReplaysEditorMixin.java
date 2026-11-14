package dev.bbsae.mixin;

import dev.bbsae.client.replay.CompReplayBridge;
import dev.bbsae.client.replay.VirtualReplayVisibility;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears an initial virtual-replay selection in the original film editor.
 *
 * <p>{@code setFilm} remembers the last replay index per film and selects it blindly. The virtual
 * rows are hidden from the list, so that selection would make the properties panel describe a row
 * the user cannot see. Inside bbsae's replay mode virtual rows are allowed and the selection is
 * left alone.</p>
 */
@Mixin(UIReplaysEditor.class)
public abstract class UIReplaysEditorMixin
{
    @Inject(method = "setFilm", at = @At("TAIL"))
    private void bbsae$clearHiddenVirtualSelection(Film film, CallbackInfo info)
    {
        UIReplaysEditor self = (UIReplaysEditor) (Object) this;

        if (film == null || VirtualReplayVisibility.allowsVirtual(film))
        {
            return;
        }

        Replay selected = self.getReplay();

        if (selected != null && CompReplayBridge.isVirtual(selected))
        {
            self.setReplay(null);
        }
    }
}
