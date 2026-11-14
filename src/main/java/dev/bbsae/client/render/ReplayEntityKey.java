package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.client.overlay.BbsPreviewBridge;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws the coverage-keyed replay entity matte into the current layer target.
 *
 * <p>The pass is a single full-screen triangle over the layer's own render target. For every
 * fragment it reconstructs the layer-local composition coordinate from the inverse of
 * {@code projection · model}, maps that to the world frame's UV, copies the world colour where
 * {@link ReplayEntityMask} says a selected actor is, and leaves everything else transparent.
 * Doing the mapping in the shader (instead of drawing a textured quad) means the matte follows the
 * layer's position, scale, rotation and anchor exactly like a picture would, without a second
 * geometry path.</p>
 *
 * <p>The mask is an ordinary RGBA coverage texture. The old depth-only target and its per-fragment
 * depth comparison are gone; see {@link ReplayEntityMask} for why. World-depth occlusion is applied
 * by hardware when the coverage pass is rendered, so it is already in the alpha channel here.</p>
 */
public final class ReplayEntityKey
{
    private ReplayEntityKey()
    {}

    /**
     * Draws the matte with two ordinary textured quads instead of a custom sampler program.
     *
     * <p>The custom shader is the elegant path, and the pixel tests prove it on a clean context,
     * but inside BBS + Iris the mask sampler kept reading zero while the same coverage texture
     * read back correctly. The batcher path is the one every other image layer already uses: draw
     * the world colour, then multiply the destination by the coverage alpha with
     * {@code ZERO / SRC_ALPHA}. Two draws, no custom uniforms, no sampler-unit assumptions.</p>
     *
     * <p>The result is premultiplied (rgb·coverage, alpha=coverage), which is exactly what
     * {@code renderLayerContent} expects before its unpremultiply pass.</p>
     */
    public static boolean drawTextured(CompLayer layer, CompRenderContext context, mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D batcher)
    {
        return drawTextured(layer, context, batcher, layer.entityOcclusion);
    }

    /**
     * Complete-actor cutout: copy the world frame's own actor pixels and cut them out with the
     * coverage alpha.
     *
     * <p>The actor-only target is re-rendered under the matte camera, so with a multi-part form
     * its interior can show faces in draw order (back faces over front faces) — the "the subject
     * looks like it is clipping through itself" report. The world frame already contains the actor
     * exactly where the film drew it, with correct self-occlusion and translucency; multiplying it
     * by the coverage alpha gives the same complete silhouette without inventing a second image.
     * The unoccluded coverage is used on purpose so no front face is carved away by a depth texel.</p>
     */
    public static boolean drawCutout(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        return drawTextured(layer, context, batcher, false);
    }

    /**
     * Complete-actor cutout with the world depth test.
     *
     * <p>The world frame already contains the actor's correct self-occlusion and lighting, but the
     * <b>unoccluded</b> coverage knows nothing about blocks between the camera and the actor. Using
     * it to cut the world frame painted the wall/ground behind the actor into the silhouette — a
     * block-shaped "actor" as soon as the actor stood behind anything. The occluded coverage is
     * the same union alpha with the world depth test already applied, so the cutout shows the
     * visible part of the actor and stays transparent where the world hides it.</p>
     */
    public static boolean drawCutoutOccluded(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f projection)
    {
        return draw(layer, context, model, projection, true);
    }

    private static boolean drawTextured(CompLayer layer, CompRenderContext context, Batcher2D batcher, boolean occluded)
    {
        GlTexture world = BbsPreviewBridge.texture();
        int mask = ReplayEntityMask.texture(occluded);

        if (world == null || mask == 0)
        {
            return false;
        }

        /* The batcher variant must obey the same frame pairing as the shader path. It used to
         * accept any existing mask, which re-introduced the "background follows the previous
         * actor pose" bug whenever the A/B flag was enabled. */
        if (!ReplayEntityMask.isCurrentFrame()
            || SceneColor.frameSerial() != ReplayEntityMask.frameSerial())
        {
            return false;
        }

        /* World color and matte must describe the same rectangle; otherwise the two quads are
         * sampled at different scales and the actor looks doubled/offset. */
        int frameMaskW = Math.max(1, ReplayEntityMask.width());
        int frameMaskH = Math.max(1, ReplayEntityMask.height());

        if (Math.abs(frameMaskW - world.width) > 1 || Math.abs(frameMaskH - world.height) > 1)
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

        batcher.texturedBox(world.id, layer.color | 0xFF000000,
            x, y, width, height, 0F, world.height, world.width, 0F, world.width, world.height);
        batcher.flush();

        /* dst.rgb *= coverage.a and dst.a *= coverage.a. Raw GL as well as the cached call: a
         * skipped blend-func change leaves the default alpha blend, which paints the coverage
         * colours over the world instead of cutting the world out with them. */
        com.mojang.blaze3d.platform.GlStateManager._enableBlend();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(
            org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
            org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_SRC_ALPHA);
        org.lwjgl.opengl.GL14.glBlendFuncSeparate(
            org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
            org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_SRC_ALPHA);

        int maskWidth = Math.max(1, ReplayEntityMask.width());
        int maskHeight = Math.max(1, ReplayEntityMask.height());

        batcher.texturedBox(mask, 0xFFFFFFFF,
            x, y, width, height, 0F, maskHeight, maskWidth, 0F, maskWidth, maskHeight);
        batcher.flush();

        com.mojang.blaze3d.platform.GlStateManager._blendFunc(
            org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL14.glBlendFuncSeparate(
            org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
            org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);

        return true;
    }

    /**
     * Draws the actor-only color image captured by the coverage pass.
     *
     * <p>This is the replacement for the world-plate matte: the coverage target already contains
     * only the selected actors (transparent elsewhere, world-occluded where a block is in front).
     * Drawing it directly removes the background entirely — there is no world color to leak and no
     * mask multiplication to get out of sync with the actor's motion.</p>
     */
    public static boolean drawActors(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        /* The flat cutout must be complete. World-depth occlusion is a film-plate feature and it
         * was rejecting some front faces when the actor re-render and the world depth did not
         * agree by a texel; the actor-only layer therefore always uses the unoccluded target. */
        int mask = ReplayEntityMask.texture(false);

        if (mask == 0 || !ReplayEntityMask.isCurrentFrame()
            || SceneColor.frameSerial() != ReplayEntityMask.frameSerial())
        {
            return false;
        }

        int maskWidth = Math.max(1, ReplayEntityMask.width());
        int maskHeight = Math.max(1, ReplayEntityMask.height());
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
                ? Math.max(layer.width / maskWidth, layer.height / maskHeight)
                : Math.min(layer.width / maskWidth, layer.height / maskHeight);

            width = maskWidth * scale;
            height = maskHeight * scale;
        }

        float x = -width * layer.anchorX;
        float y = -height * layer.anchorY;

        batcher.texturedBox(mask, layer.color | 0xFFFFFFFF,
            x, y, width, height,
            0F, maskHeight, maskWidth, 0F,
            maskWidth, maskHeight);
        batcher.flush();

        return true;
    }

    /**
     * @return false when there is nothing to key (no world frame, no mask, no shader); the caller
     *         then draws its "waiting for the world" placeholder instead of a transparent rect.
     */
    /**
     * Complete-actor cutout through the proven fullscreen shader.
     *
     * <p>World colour + coverage alpha, using the unoccluded target. The actor-only target is
     * re-rendered under the matte camera, so its draw order can show back faces over front faces
     * (the "subject clips through itself" report). The world frame already contains the actor with
     * correct self-occlusion and translucency; the shader only has to cut it out.</p>
     */
    public static boolean drawCutout(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f projection)
    {
        return draw(layer, context, model, projection, false);
    }

    public static boolean draw(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f projection)
    {
        return draw(layer, context, model, projection, layer.entityOcclusion);
    }

    private static boolean draw(CompLayer layer, CompRenderContext context, Matrix4f model, Matrix4f projection, boolean occluded)
    {
        GlTexture world = BbsPreviewBridge.texture();
        int mask = ReplayEntityMask.texture(occluded);

        if (world == null || mask == 0 || projection == null)
        {
            return false;
        }

        /* A mask that was not produced for this world frame is the "background leaks while the
         * actor moves" bug: the old silhouette keys pixels where the actor no longer is. Draw the
         * waiting placeholder instead of a mismatched matte. */
        if (!ReplayEntityMask.isCurrentFrame()
            || SceneColor.frameSerial() != ReplayEntityMask.frameSerial())
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

        /* Force the state both through the cache and with raw GL. BBS/Iris issue raw state calls
         * too, so a cached "already disabled" can be wrong; a leftover scissor or blend equation
         * is enough to turn the whole layer into transparent pixels. */
        GlStateManager._disableBlend();
        GL11.glDisable(GL11.GL_BLEND);
        GlStateManager._disableDepthTest();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GlStateManager._disableCull();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlStateManager._disableScissorTest();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager._colorMask(true, true, true, true);
        GL11.glColorMask(true, true, true, true);

        program.use();

        PassRenderer.bindRaw(program, "WorldColor", 0, world.id);
        PassRenderer.bindRaw(program, "EntityMask", 1, mask);

        program.set("InvMvp", inverse);
        program.set("Rect", x, y, Math.max(1F, width), Math.max(1F, height));
        program.set("DepthTexel", 1F / Math.max(1, ReplayEntityMask.width()), 1F / Math.max(1, ReplayEntityMask.height()));
        program.set("Erode", Math.max(0F, Math.min(32F, layer.entityErode)));
        program.set("Feather", Math.max(0F, Math.min(32F, layer.entityFeather)));
        program.set("Tint", ((layer.color >> 16) & 0xFF) / 255F, ((layer.color >> 8) & 0xFF) / 255F, (layer.color & 0xFF) / 255F, 1F);
        program.set("DebugView", debugView());

        /* The coverage target must have the world frame's size: a different size stretches the
         * 0..1 mask over the plate and shows up as an offset key. Log it once a second instead of
         * failing silently, so a future regression in that sizing is visible in the client log. */
        long now = System.currentTimeMillis();
        int maskWidth = ReplayEntityMask.width();
        int maskHeight = ReplayEntityMask.height();

        if (Math.abs(maskWidth - world.width) > 1 || Math.abs(maskHeight - world.height) > 1)
        {
            if (now - lastSizeWarning > 2000L)
            {
                lastSizeWarning = now;
                dev.bbsae.BbsAeAddon.LOGGER.warn(
                    "Entity key target {}x{} does not match the world frame {}x{}; the matte may be offset",
                    maskWidth, maskHeight, world.width, world.height);
            }

            /* A differently-sized mask samples a different rectangle of the actor. Drawing it
             * anyway is the "motion reveals background" leak at the edges; leave the layer's
             * waiting placeholder until the coverage target catches up. */
            return false;
        }

        if (Boolean.getBoolean("bbsae.debugEntityKeyLog"))
        {
            logDraw(world, mask, layer, inverse, width, height);
        }

        program.drawFullscreen();

        if (Boolean.getBoolean("bbsae.debugEntityKeyLog"))
        {
            logBindings(program, world, mask);
        }

        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(0);

        return true;
    }

    /** One line every two seconds: proves the draw runs and shows the mapping it uses. */
    private static void logDraw(GlTexture world, int mask, CompLayer layer, Matrix4f inverse, float width, float height)
    {
        long now = System.currentTimeMillis();

        if (now - lastLog < 2000L)
        {
            return;
        }

        lastLog = now;

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "Entity key draw: world={}x{} mask={} layer={} threeD={} anchor=({},{}) rect={}x{} debugView={} loc={} value={} invMvp=[{},{},{},{}]",
            world.width, world.height, mask, layer.id, layer.threeD, layer.anchorX, layer.anchorY, width, height,
            debugView(), CompShaders.entityKey == null ? -1 : CompShaders.entityKey.uniform("DebugView"),
            CompShaders.entityKey == null ? -1 : CompShaders.entityKey.getInt("DebugView"),
            inverse.m00(), inverse.m11(), inverse.m22(), inverse.m33());
    }

    private static long lastLog;
    private static long lastSizeWarning;

    /**
     * Reads back what GL actually has bound after the draw. The unit-1 result is the decisive
     * fact: if it is not the mask texture id, the sampler is reading a different image no matter
     * what the uniform says.
     */
    private static void logBindings(GlslProgram program, GlTexture world, int mask)
    {
        long now = System.currentTimeMillis();

        if (now - lastBindingLog < 2000L)
        {
            return;
        }

        lastBindingLog = now;

        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        int bound1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        int bound0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "Entity key bindings: unit0={} (world={}) unit1={} (mask={}) uniform EntityMask={} WorldColor={} unit1IsTexture={}",
            bound0, world.id, bound1, mask,
            program.getInt("EntityMask"), program.getInt("WorldColor"), GL11.glIsTexture(bound1));
    }

    private static long lastBindingLog;

    /**
     * Verification-only view selector. {@code -Dbbsae.debugEntityKeyView=1} draws the coverage
     * texture as opaque grey; {@code 2} draws the world-frame UV. Both are inert by default.
     */
    private static int debugView()
    {
        if (!debugViewResolved)
        {
            debugViewResolved = true;
            debugViewValue = Math.max(0, Math.min(3, Integer.getInteger("bbsae.debugEntityKeyView", 0)));
        }

        return debugViewValue;
    }

    private static boolean debugViewResolved;
    private static int debugViewValue;
}
