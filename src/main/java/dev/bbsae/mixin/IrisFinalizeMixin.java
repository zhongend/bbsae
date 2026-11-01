package dev.bbsae.mixin;

import dev.bbsae.client.render.SceneColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Iris hook: copy the frame <b>after</b> the shader pack's final pass.
 *
 * <p>The compositor's normal {@code WorldRenderEvents.AFTER_TRANSLUCENT} copy happens before Iris
 * runs its composite/final passes. In a shader pack that means it captures the raw G-buffer scene,
 * not the image the player actually sees. {@code finalizeLevelRendering()} is the point where Iris
 * has finished the final pass; copying there gives the preview the same graded image as the game.</p>
 *
 * <p>Iris is optional and not a compile dependency of this mod. The mixin config has
 * {@code defaultRequire: 0}; when Iris is absent the target class is simply not found and the game
 * continues without this hook. When Iris is present this method is called once per world frame.</p>
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public class IrisFinalizeMixin
{
    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"), remap = false)
    private void bbsae$captureIrisFinalFrame(CallbackInfo ci)
    {
        SceneColor.captureFinal();
    }
}
