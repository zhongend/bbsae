package dev.bbsae.mixin;

import dev.bbsae.client.ui.BbsClipSplit;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.IFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs bbsae's Ctrl+K split on every BBS camera timeline.
 *
 * <p>The constructor is the only place where the clip list widget exists before the panel is built.
 * Registering a key on it uses the exact same path as BBS's own Alt+C, so the two splits share the
 * same active-state checks (embedded views, text fields, disabled keybinds).</p>
 */
@Mixin(UIClips.class)
public abstract class UIClipsSplitMixin
{
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbsae$installSplit(IUIClipsDelegate delegate, IFactory<Clip, ClipFactoryData> factory, CallbackInfo info)
    {
        BbsClipSplit.install((UIClips) (Object) this, delegate::getCursor);
    }
}
