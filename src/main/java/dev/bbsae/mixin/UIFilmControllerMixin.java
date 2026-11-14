package dev.bbsae.mixin;

import dev.bbsae.client.replay.CompReplayBridge;
import dev.bbsae.client.replay.VirtualReplayVisibility;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.utils.UIUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * PageUp/PageDown skip bbsae's hidden virtual replays in the original film editor.
 *
 * <p>The list rows are filtered, but BBS cycles the full replay list by index. Without this, the
 * previous/next replay shortcuts can still select a row the user cannot see and fill the properties
 * panel with it. In bbsae's replay mode the virtual rows are allowed and BBS's normal cycle is
 * preserved.</p>
 */
@Mixin(UIFilmController.class)
public abstract class UIFilmControllerMixin
{
    @Inject(method = "switchReplay", at = @At("HEAD"), cancellable = true)
    private void bbsae$skipHiddenVirtualReplays(int direction, CallbackInfo info)
    {
        UIFilmController self = (UIFilmController) (Object) this;

        if (self.panel == null || self.panel.getData() == null || self.panel.replayEditor == null)
        {
            return;
        }

        Film film = self.panel.getData();

        if (VirtualReplayVisibility.allowsVirtual(film))
        {
            return;
        }

        List<Replay> list = film.replays.getList();

        if (list.isEmpty())
        {
            info.cancel();

            return;
        }

        int index = list.indexOf(self.panel.replayEditor.getReplay());

        for (int step = 1; step < list.size(); step++)
        {
            int next = Math.floorMod(index + direction * step, list.size());
            Replay replay = list.get(next);

            if (!CompReplayBridge.isVirtual(replay))
            {
                self.panel.replayEditor.setReplay(replay);
                UIUtils.playClick();
                info.cancel();

                return;
            }
        }

        /* Every replay in this film is virtual: the original editor has nothing to cycle to. */
        info.cancel();
    }
}
