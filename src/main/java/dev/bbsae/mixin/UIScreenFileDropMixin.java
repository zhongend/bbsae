package dev.bbsae.mixin;

import dev.bbsae.client.BbsAeClient;
import mchorse.bbs_mod.ui.framework.UIScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OS file drops enter Minecraft through {@link UIScreen#acceptFilePaths(String[])} (called by the
 * vanilla screen drop path). BBS runs its importer there; the bbsae compositor wants the raw paths
 * instead. Pass the drop to bbsae first and cancel BBS's importer only when bbsae consumed it, so
 * every other BBS screen keeps its original behaviour.
 */
@Mixin(UIScreen.class)
public class UIScreenFileDropMixin
{
    @Inject(method = "acceptFilePaths", at = @At("HEAD"), cancellable = true)
    private void bbsae$acceptFilePaths(String[] paths, CallbackInfo info)
    {
        if (BbsAeClient.acceptDroppedFilePaths(paths))
        {
            info.cancel();
        }
    }
}
