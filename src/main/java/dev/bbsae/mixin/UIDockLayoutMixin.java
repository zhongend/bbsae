package dev.bbsae.mixin;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes BBS' dock layout lock cover the <b>splitter handles</b> too.
 *
 * <p>{@code UIDockLayout.toggleLock()} hides panel title drag handles, but the splitter handles keep
 * their {@code UIDraggable} and their anonymous {@code subMouseClicked} calls
 * {@code beginSplitterDrag} directly — it never consults {@code layoutLocked}. The result looked
 * like "the layout cannot be locked": splitting/resizing still worked after pressing the lock
 * button. This mixin disables the handle, refuses to begin a drag and refuses to apply one while
 * the dock is locked.</p>
 */
@Mixin(UIDockLayout.class)
public class UIDockLayoutMixin
{
    @Inject(method = "createSplitterHandle", at = @At("RETURN"))
    private void bbsae$lockSplitterHandle(int index, CallbackInfoReturnable<UIDraggable> cir)
    {
        UIDraggable handle = cir.getReturnValue();

        if (handle != null)
        {
            UIDockLayout dock = (UIDockLayout) (Object) this;

            handle.enabled(() -> BBSSettings.editorResizablePanels.get() && !dock.isLocked());
        }
    }

    @Inject(method = "beginSplitterDrag", at = @At("HEAD"), cancellable = true)
    private void bbsae$blockSplitterBegin(int index, int mouseX, int mouseY, CallbackInfo ci)
    {
        if (((UIDockLayout) (Object) this).isLocked())
        {
            ci.cancel();
        }
    }

    @Inject(method = "applySplitterDrag", at = @At("HEAD"), cancellable = true)
    private void bbsae$blockSplitterApply(int mouseX, int mouseY, CallbackInfo ci)
    {
        if (((UIDockLayout) (Object) this).isLocked())
        {
            ci.cancel();
        }
    }
}
