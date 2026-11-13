package dev.bbsae.mixin;

import dev.bbsae.BbsAeAddon;
import dev.bbsae.client.render.ReplayRenderLight;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes bbsae's virtual replay actors independent from the live world's block/sky light.
 *
 * <p>{@code FilmEntityRenderer.renderEntity} computes
 * {@code LightmapTextureManager.pack(world light at actor)}. A virtual replay's position is only a
 * composition-space anchor, so a dark world position produced a black model in the replay editor
 * and in the compositor's linked pass. While {@link ReplayRenderLight} is active, the pack call is
 * redirected to the full-bright coordinate. BBS real-world playback never sets that flag.</p>
 */
@Mixin(FilmEntityRenderer.class)
public class FilmEntityRendererLightMixin
{
    private static boolean logged;

    @Redirect(
        method = "renderEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/LightmapTextureManager;pack(II)I"
        ),
        require = 0
    )
    private static int bbsae$fullbrightVirtualReplay(int block, int sky)
    {
        if (ReplayRenderLight.fullbright())
        {
            if (!logged)
            {
                logged = true;
                BbsAeAddon.LOGGER.info("Virtual replay full-bright light override active");
            }

            return LightmapTextureManager.MAX_LIGHT_COORDINATE;
        }

        return LightmapTextureManager.pack(block, sky);
    }
}
