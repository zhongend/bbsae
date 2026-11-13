package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * A copy of the finished world frame, owned by the compositor.
 *
 * <p>This exists so the compositor never has to enable {@code BBSRendering.setCustomSize}. That
 * mode makes BBS render the world into its own framebuffer and blit it over the screen; while a
 * non-film dashboard panel is on screen the blit wins over the panel's UI, which is exactly the
 * "compositor page is blank, every other BBS page is fine" bug.</p>
 *
 * <p>Instead, the world renders normally into Minecraft's main framebuffer, the film camera and
 * replay actors are driven by {@link dev.bbsae.client.overlay.BbsPreviewBridge}, and this class
 * copies the finished color once per frame with {@code glCopyTexSubImage2D}. The copy is bottom-up
 * like every GL framebuffer, so consumers keep swapping V when drawing it.</p>
 *
 * <h2>Alpha is normalised to 1 before anything samples the copy</h2>
 *
 * <p>{@code glCopyTexSubImage2D} copies alpha verbatim, and shader packs leave the final pass alpha
 * at all kinds of values — iterationRP's final fragment literally writes {@code vec4(color, 0.0)}.
 * Every consumer (BBS batcher, layer shaders) blends with the texture alpha, so a verbatim copy is
 * <b>invisible</b>: under such a pack the BBS 预览 layer, the backdrop and the whole preview go
 * black. Each capture therefore ends with the tiny {@link ShaderSources#OPAQUE} pass, which also
 * drops any other pack-side garbage in the alpha channel.</p>
 *
 * <h2>The shader-pack final frame, with a fallback</h2>
 *
 * <p>The primary final-frame source is {@code IrisFinalizeMixin} at the tail of
 * {@code finalizeLevelRendering()}. If that hook cannot bind on some Iris build, the early copy
 * under Iris is the pre-composite main framebuffer — solid black. To never serve black, capture
 * also tries Iris' own colortex0 through {@link IrisColor}; the image is not tonemapped, but it is
 * the real world picture, and the preview readout says so via {@link #status()}.</p>
 */
public final class SceneColor
{
    /** Raw bottom-up copy of the main framebuffer at the WorldRenderEvents.LAST point. */
    private static GlTexture texture;
    /** The same image with alpha forced to 1 — this is what {@link #texture()} hands out. */
    private static GlTexture opaqueTexture;
    private static int opaqueFbo;
    private static int width;
    private static int height;

    /**
     * The copy taken after the shader pack's final pass. This is the image that includes the
     * pack's exposure, bloom, tonemap and colour grading; the early copy is only the
     * pre-composite scene and is kept as a fallback for non-Iris setups.
     */
    private static GlTexture finalTexture;
    private static GlTexture finalOpaque;
    private static int finalFbo;
    private static int finalWidth;
    private static int finalHeight;
    private static boolean finalValid;
    /** True when the final image came from Iris' colortex0 fallback copy instead of the mixin. */
    private static boolean finalFallback;
    private static boolean finalRequested;
    private static boolean finalLogged;
    private static int frameSerial = -1;
    /** Read framebuffer used only by the colortex0 fallback copy. */
    private static int copyFbo;

    private SceneColor()
    {}

    /** Called from {@code WorldRenderEvents.LAST}, after actors/particles but before the UI. */
    public static void capture()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null)
        {
            return;
        }

        /* Iris renders a shadow pass with only depth bound. Copying that pass would replace the
         * preview with a black/depth image, so it must be skipped until the colour pass arrives. */
        if (!ShaderCompat.canCaptureWorld())
        {
            return;
        }

        Framebuffer framebuffer = mc.getFramebuffer();

        /* BBS may point its own world framebuffer at a custom target. In the editor (custom size
         * off) the main framebuffer is authoritative; this fallback only covers the case where
         * Minecraft's reference is null but BBS still has a valid colour target. */
        if (framebuffer == null)
        {
            try
            {
                framebuffer = mchorse.bbs_mod.client.BBSRendering.getFramebuffer();
            }
            catch (RuntimeException | LinkageError ignored)
            {
                framebuffer = null;
            }
        }

        if (framebuffer == null)
        {
            return;
        }

        int w = framebuffer.textureWidth;
        int h = framebuffer.textureHeight;

        if (w <= 0 || h <= 0)
        {
            return;
        }

        ensurePair(w, h);

        int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer.fbo);
            bindForCopy(texture.id);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            bindForCopy(0);
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        }

        normalize(texture, opaqueTexture, opaqueFbo, width, height);
        GlTexture.unbind(0);
        frameSerial = ReplayEntityMask.frameSerial();

        /* Iris will call captureFinal() at the tail of finalizeLevelRendering(). The early copy
         * remains the fallback for vanilla/no-shader and for the very first frame while the
         * pipeline is still compiling. While the final hook has never produced a frame, also copy
         * Iris' own colortex0 so a missing mixin still shows the world instead of the black
         * pre-composite main framebuffer. */
        finalRequested = ShaderCompat.irisLoaded();

        /* The fallback must also refresh when it produced the previous frame's final image: the
         * mixin path overwrites the final pair every frame, but when the hook is absent nothing
         * else keeps the colortex0 copy current. */
        if (finalRequested && (!finalValid || finalFallback))
        {
            captureIrisFallback();
        }
    }

    /**
     * Copies the framebuffer that Iris has just finished compositing.
     *
     * <p>Called from an optional mixin at the tail of {@code IrisRenderingPipeline.finalizeLevelRendering}.
     * The draw framebuffer at that point is the final target (main framebuffer or BBS' target),
     * so reading it back with {@code glCopyTexSubImage2D} gives the exact graded frame. The copy is
     * bottom-up, the same convention as {@link #capture()}.</p>
     */
    public static void captureFinal()
    {
        if (!finalRequested || !ShaderCompat.irisLoaded())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.world == null)
        {
            return;
        }

        int drawFramebuffer = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        int w = viewport[2];
        int h = viewport[3];

        /* At the tail of the final pass the viewport may already have been restored to the window.
         * Prefer the known main-framebuffer size when the draw target is that framebuffer. */
        Framebuffer main = mc.getFramebuffer();
        int mainFbo = main == null ? 0 : main.fbo;

        if (drawFramebuffer == mainFbo && main != null)
        {
            w = main.textureWidth;
            h = main.textureHeight;
        }

        if (w <= 0 || h <= 0)
        {
            return;
        }

        ensureFinalPair(w, h);

        int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            bindForCopy(finalTexture.id);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            bindForCopy(0);
            finalValid = true;
            finalFallback = false;
        }
        catch (RuntimeException e)
        {
            finalValid = false;
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        }

        if (finalValid)
        {
            normalize(finalTexture, finalOpaque, finalFbo, finalWidth, finalHeight);
            GlTexture.unbind(0);
            frameSerial = ReplayEntityMask.frameSerial();

            if (!finalLogged)
            {
                finalLogged = true;
                dev.bbsae.BbsAeAddon.LOGGER.info("Iris final-pass capture enabled: {}x{} from framebuffer {}",
                    w, h, drawFramebuffer);
            }
        }
    }

    /**
     * Copies Iris' colortex0 into the final pair. A temporary framebuffer attaches the target so
     * {@code glCopyTexSubImage2D} can read from it; sizes may differ from the window (packs with
     * a render scale), and the copy simply takes the common region.
     */
    private static void captureIrisFallback()
    {
        int[] target = IrisColor.mainColor();
        int textureId = target[0];

        if (textureId == 0)
        {
            return;
        }

        int w = target[1];
        int h = target[2];

        if (w <= 0 || h <= 0)
        {
            return;
        }

        try
        {
            ensureFinalPair(w, h);

            if (copyFbo == 0)
            {
                copyFbo = GL30.glGenFramebuffers();
            }

            int previousRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int previousDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

            try
            {
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, copyFbo);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0);

                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE)
                {
                    bindForCopy(finalTexture.id);
                    GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
                    bindForCopy(0);
                    finalValid = true;
                    finalFallback = true;
                }
                else
                {
                    GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                }
            }
            finally
            {
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            }

            if (finalValid)
            {
                normalize(finalTexture, finalOpaque, finalFbo, finalWidth, finalHeight);
                GlTexture.unbind(0);
                frameSerial = ReplayEntityMask.frameSerial();

                if (!finalLogged)
                {
                    finalLogged = true;
                    dev.bbsae.BbsAeAddon.LOGGER.info(
                        "Iris final-pass hook unavailable; using the colortex0 fallback capture: {}x{}", w, h);
                }
            }
        }
        catch (RuntimeException e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.debug("Iris fallback capture failed", e);
        }
    }

    /** Allocates the raw + opaque pair for the early copy when the size changed. */
    private static void ensurePair(int w, int h)
    {
        if (texture == null || width != w || height != h)
        {
            releasePair();

            texture = new GlTexture();
            texture.allocate(w, h);
            opaqueTexture = new GlTexture();
            opaqueTexture.allocate(w, h);
            opaqueFbo = attach(opaqueTexture);
            width = w;
            height = h;
        }
    }

    /** Allocates the raw + opaque pair for the final copy when the size changed. */
    private static void ensureFinalPair(int w, int h)
    {
        if (finalTexture == null || finalWidth != w || finalHeight != h)
        {
            releaseFinalPair();

            finalTexture = new GlTexture();
            finalTexture.allocate(w, h);
            finalOpaque = new GlTexture();
            finalOpaque.allocate(w, h);
            finalFbo = attach(finalOpaque);
            finalWidth = w;
            finalHeight = h;
        }
    }

    /** A bare framebuffer object with {@code color} as its only attachment. */
    private static int attach(GlTexture color)
    {
        int fbo = GL30.glGenFramebuffers();

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.id, 0);

        return fbo;
    }

    /**
     * Draws {@code from} into {@code into} with alpha forced to 1.
     *
     * <p>Runs on the render thread mid-world-render, so the pass touches as little GL state as
     * possible and restores every bit of it: framebuffer, viewport, blend, depth/scissor/cull,
     * colour mask, program and texture binding. The fullscreen triangle overwrites every pixel,
     * so no clear is needed.</p>
     */
    private static void normalize(GlTexture from, GlTexture into, int targetFbo, int targetWidth, int targetHeight)
    {
        if (from == null || into == null || targetFbo == 0 || from.width != into.width || from.height != into.height)
        {
            return;
        }

        CompShaders.init();

        GlslProgram program = CompShaders.opaque;

        if (program == null)
        {
            return;
        }

        int previousFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        java.nio.ByteBuffer maskBuffer = org.lwjgl.BufferUtils.createByteBuffer(4);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, maskBuffer);

        try
        {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GlStateManager._viewport(0, 0, targetWidth, targetHeight);
            GlStateManager._disableScissorTest();
            GlStateManager._disableDepthTest();
            GlStateManager._disableCull();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._disableBlend();
            program.use();
            PassRenderer.bindRaw(program, "Source", 0, from.id);
            program.drawFullscreen();
        }
        finally
        {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GlStateManager._glUseProgram(previousProgram);
            GL20.glUseProgram(previousProgram);
            GlTexture.unbind(0);

            if (blend)
            {
                GlStateManager._enableBlend();
            }
            else
            {
                GlStateManager._disableBlend();
            }

            if (depth)
            {
                GlStateManager._enableDepthTest();
            }

            if (scissor)
            {
                GlStateManager._enableScissorTest();
            }

            if (cull)
            {
                GlStateManager._enableCull();
            }

            GlStateManager._colorMask(maskBuffer.get(0) != 0, maskBuffer.get(1) != 0, maskBuffer.get(2) != 0, maskBuffer.get(3) != 0);
        }
    }

    /** Binds a texture for a {@code glCopyTexSubImage2D} destination, keeping the GL cache honest. */
    private static void bindForCopy(int texture)
    {
        GlStateManager._bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void releasePair()
    {
        if (opaqueFbo != 0)
        {
            GL30.glDeleteFramebuffers(opaqueFbo);
            opaqueFbo = 0;
        }

        if (opaqueTexture != null)
        {
            opaqueTexture.delete();
            opaqueTexture = null;
        }

        if (texture != null)
        {
            texture.delete();
            texture = null;
        }

        width = 0;
        height = 0;
    }

    private static void releaseFinalPair()
    {
        if (finalFbo != 0)
        {
            GL30.glDeleteFramebuffers(finalFbo);
            finalFbo = 0;
        }

        if (finalOpaque != null)
        {
            finalOpaque.delete();
            finalOpaque = null;
        }

        if (finalTexture != null)
        {
            finalTexture.delete();
            finalTexture = null;
        }

        finalWidth = 0;
        finalHeight = 0;
    }

    /** The shader-final copy when Iris is active, otherwise the early world copy. Alpha is always 1. */
    public static GlTexture texture()
    {
        if (ShaderCompat.irisLoaded() && finalValid && finalOpaque != null)
        {
            return finalOpaque;
        }

        return opaqueTexture != null ? opaqueTexture : texture;
    }

    /** True when a shader-pack final frame has actually been captured. */
    public static boolean hasFinal()
    {
        return finalValid && finalOpaque != null;
    }

    /** World-frame serial the current colour copy was produced for (-1 before the first copy). */
    public static int frameSerial()
    {
        return frameSerial;
    }

    /** True when the current final frame came from the colortex0 fallback instead of the final pass. */
    public static boolean isFallbackFinal()
    {
        return finalFallback;
    }

    /**
     * Human-readable capture state for the preview readout, or null when nothing was captured yet.
     */
    public static String status()
    {
        if (opaqueTexture == null && finalOpaque == null)
        {
            return null;
        }

        if (ShaderCompat.irisLoaded())
        {
            if (finalValid)
            {
                return finalFallback ? "光影帧（回退 · 合成前颜色）" : "光影最终帧";
            }

            return "光影帧未捕获";
        }

        return "世界帧";
    }

    public static void delete()
    {
        releasePair();
        releaseFinalPair();

        if (copyFbo != 0)
        {
            GL30.glDeleteFramebuffers(copyFbo);
            copyFbo = 0;
        }

        finalValid = false;
        finalFallback = false;
        finalRequested = false;
        finalLogged = false;
        frameSerial = -1;
    }
}
