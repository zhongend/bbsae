package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.client.overlay.BbsPreviewBridge;
import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;

/**
 * Draws the depth-keyed replay entity matte into the current layer target.
 *
 * <p>The pass is a single full-screen triangle over the layer's own render target. For every
 * fragment it reconstructs the layer-local composition coordinate from the inverse of
 * {@code projection · model}, maps that to the world frame's UV, and keeps the world colour only
 * where {@link ReplayEntityMask} says the pixel belongs to a selected entity. Doing the mapping in
 * the shader (instead of drawing a textured quad) means the matte follows the layer's position,
 * scale, rotation and anchor exactly like a picture would, without a second geometry path.</p>
 */
public final class ReplayEntityKey
{
    private ReplayEntityKey()
    {}

    /**
     * @return false when there is nothing to key (no world frame, no mask, no shader); the caller
     *         then draws its "waiting for the world" placeholder instead of a transparent rect.
     */
    public static boolean draw(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f projection)
    {
        GlTexture world = BbsPreviewBridge.texture();
        int mask = ReplayEntityMask.texture();

        if (world == null || mask == 0 || projection == null)
        {
            return false;
        }

        GlslProgram program = CompShaders.entityKey;

        if (program == null)
        {
            return false;
        }

        float width;
        float height;

        if (layer.imageFit == 2)
        {
            width = Math.max(1F, layer.width);
            height = Math.max(1F, layer.height);
        }
        else
        {
            float scale = layer.imageFit == 1
                ? Math.max(layer.width / world.width, layer.height / world.height)
                : Math.min(layer.width / world.width, layer.height / world.height);

            width = world.width * scale;
            height = world.height * scale;
        }

        float x = -width * layer.anchorX;
        float y = -height * layer.anchorY;

        Matrix4f inverse = new Matrix4f(projection).mul(model).invert();

        SceneDepth depth = BbsPreviewBridge.depth();
        boolean occluded = layer.entityOcclusion && depth != null && depth.isAvailable();

        GlStateManager._disableBlend();
        GlStateManager._disableDepthTest();
        GlStateManager._disableCull();
        GlStateManager._disableScissorTest();

        program.use();

        PassRenderer.bindRaw(program, "WorldColor", 0, world.id);
        PassRenderer.bindRaw(program, "WorldDepth", 1, occluded ? depth.texture() : 0);
        PassRenderer.bindRaw(program, "EntityDepth", 2, mask);

        program.set("InvMvp", inverse);
        program.set("Rect", x, y, Math.max(1F, width), Math.max(1F, height));
        program.set("DepthTexel", 1F / Math.max(1, ReplayEntityMask.width()), 1F / Math.max(1, ReplayEntityMask.height()));
        program.set("Near", ReplayEntityMask.nearPlane());
        program.set("Far", ReplayEntityMask.farPlane());
        program.set("Tolerance", Math.max(0.001F, layer.entityTolerance));
        program.set("Erode", Math.max(0F, Math.min(32F, layer.entityErode)));
        program.set("Feather", Math.max(0F, Math.min(32F, layer.entityFeather)));
        program.set("UseOcclusion", occluded ? 1 : 0);
        program.set("Tint", ((layer.color >> 16) & 0xFF) / 255F, ((layer.color >> 8) & 0xFF) / 255F, (layer.color & 0xFF) / 255F, 1F);

        program.drawFullscreen();

        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(0);

        return true;
    }
}
