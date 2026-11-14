package dev.bbsae.mixin;

import dev.bbsae.client.replay.VirtualReplayVisibility;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.Replays;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Keeps bbsae's virtual replays out of the original BBS replay list.
 *
 * <p>BBS builds its rows from {@code Film.replays.getList()}. The list also drives folder counts
 * and the category tree, so all three reads are filtered here; filtering only the row loop would
 * leave empty folders with numbers, which reads as a bug of its own. The identity guard means a
 * future BBS change to the local variable order makes this mixin a no-op instead of dropping rows
 * from an unrelated list.</p>
 */
@Mixin(UIReplayList.class)
public abstract class UIReplayListMixin
{
    @ModifyVariable(method = "refreshReplayList", at = @At(value = "STORE"), ordinal = 0)
    private List<Replay> bbsae$hideVirtualReplayRows(List<Replay> all)
    {
        UIReplayList self = (UIReplayList) (Object) this;
        Film film = self.panel == null ? null : self.panel.getData();

        if (film == null || all != film.replays.getList())
        {
            return all;
        }

        return VirtualReplayVisibility.visible(all, film);
    }

    @Redirect(method = {"collectCategoryPaths", "countReplays"},
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/film/replays/Replays;getList()Ljava/util/List;"))
    private List<Replay> bbsae$hideVirtualReplayFolderRows(Replays replays)
    {
        UIReplayList self = (UIReplayList) (Object) this;
        Film film = self.panel == null ? null : self.panel.getData();

        return VirtualReplayVisibility.visible(replays.getList(), film);
    }
}
