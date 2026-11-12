package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-restoration robustness of the per-pixel depth compositor.
 *
 * <p>{@link DepthPlaneCompositor#composite} changes depth-test mask/func/enable, blend enable,
 * colour mask, framebuffer binding and viewport. A pass that throws (driver error, deleted
 * program) must not leak any of that into the caller: the exception unwinds past code that has no
 * idea a composition target was bound. The pooled free list has the mirror-image duty: a double
 * release or a stale reference to a deleted target must never put one texture into the pool twice
 * (two later consumers would render into the same target).</p>
 */
public class DepthPlaneStateRobustnessTest
{
    private static final int W = DepthPlanePixelHarness.W;
    private static final int H = DepthPlanePixelHarness.H;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();
    }

    @AfterAll
    public static void destroyContext()
    {
        DepthPlanePixelHarness.shutdownGl();
    }

    @BeforeEach
    public void resetGlState()
    {
        System.clearProperty("bbsae.debugDepthPlaneThrow");
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        com.mojang.blaze3d.platform.GlStateManager._viewport(0, 0, W, H);
        com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);
        com.mojang.blaze3d.platform.GlStateManager._enableDepthTest();
        com.mojang.blaze3d.platform.GlStateManager._depthFunc(GL11.GL_LEQUAL);
        com.mojang.blaze3d.platform.GlStateManager._depthMask(true);
        com.mojang.blaze3d.platform.GlStateManager._disableBlend();
    }

    @AfterEach
    public void clearGlState()
    {
        System.clearProperty("bbsae.debugDepthPlaneThrow");
        DepthPlaneCompositor.deleteAll();
    }

    /** Normal composite must round-trip every piece of GL state it touches. */
    @Test
    public void glStateIsRestoredAfterAComposite()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        DepthPlaneCompositor compositor = new DepthPlaneCompositor(W, H);
        GlFramebuffer layer = DepthPlanePixelHarness.solid(0xFFFF0000);
        GlFramebuffer accumulation = new GlFramebuffer(W, H);

        try
        {
            accumulation.begin(DepthPlanePixelHarness.STAGE);

            /* Distinctive "not the pipeline default" state, set after begin() so it survives:
             * an un-restored write would be visible here. */
            com.mojang.blaze3d.platform.GlStateManager._enableDepthTest();
            com.mojang.blaze3d.platform.GlStateManager._depthFunc(GL11.GL_GREATER);
            com.mojang.blaze3d.platform.GlStateManager._depthMask(false);
            com.mojang.blaze3d.platform.GlStateManager._enableBlend();
            com.mojang.blaze3d.platform.GlStateManager._colorMask(false, false, false, false);

            accumulation = compositor.composite(layer, new Matrix4f(),
                CompCamera.identity(W, H), accumulation, CompLayer.BLEND_NORMAL, 1F);

            /* The target passes round-trip func/mask; the trailing PassRenderer.draw (like every
             * composite pass in the graph) leaves depth test and blend off. Those two are the
             * pipeline's normal post-composite state and the next begin() sets them explicitly. */
            assertTrue(!GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                "depth test is off after the final composite pass, as elsewhere in the graph");
            assertEquals(GL11.GL_GREATER, GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
                "depth func must be restored");
            assertTrue(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "depth mask must be restored");
            assertTrue(colorMaskAllEnabled(), "colour writes must be left enabled");
            assertTrue(!GL11.glIsEnabled(GL11.GL_BLEND),
                "blend is left off by the final composite pass, as elsewhere in the graph");
        }
        finally
        {
            layer.delete();
            accumulation.delete();
            compositor.delete();
            com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);
        }
    }

    /**
     * A throwing pass must restore state and rebind the caller's framebuffer/viewport before the
     * exception propagates, and the compositor must stay usable afterwards.
     */
    @Test
    public void stateAndFramebufferAreRestoredWhenAPassThrows()
    {
        Assumptions.assumeTrue(depthProgramAvailable(), "depth plane program unavailable");

        int[] callerViewport = {32, 16, 64, 48};
        GlFramebuffer callerFramebuffer = new GlFramebuffer(64, 48);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(W, H);
        GlFramebuffer layer = DepthPlanePixelHarness.solid(0xFFFF0000);
        GlFramebuffer accumulation = new GlFramebuffer(W, H);

        accumulation.begin(DepthPlanePixelHarness.STAGE);

        /* Stand-in for whatever target/env the caller had; the compositor must give it back.
         * Bound after begin() so it is the state the composite snapshots. */
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER,
            callerFramebuffer.id);
        com.mojang.blaze3d.platform.GlStateManager._viewport(32, 16, 64, 48);

        com.mojang.blaze3d.platform.GlStateManager._enableDepthTest();
        com.mojang.blaze3d.platform.GlStateManager._depthFunc(GL11.GL_GREATER);
        com.mojang.blaze3d.platform.GlStateManager._depthMask(false);
        com.mojang.blaze3d.platform.GlStateManager._enableBlend();
        com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);

        try
        {
            System.setProperty("bbsae.debugDepthPlaneThrow", "true");

            RuntimeException thrown = assertThrows(RuntimeException.class, () -> compositor.composite(
                layer, new Matrix4f(), CompCamera.identity(W, H), accumulation,
                CompLayer.BLEND_NORMAL, 1F));

            assertTrue(thrown.getMessage() != null, "the injected failure must surface");

            assertEquals(callerFramebuffer.id,
                GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING),
                "the caller's framebuffer must be rebound after a failed pass");

            int[] actualViewport = new int[4];

            GL11.glGetIntegerv(GL11.GL_VIEWPORT, actualViewport);
            assertArrayEquals(callerViewport, actualViewport,
                "the caller's viewport must be restored after a failed pass");
            assertTrue(GL11.glIsEnabled(GL11.GL_DEPTH_TEST), "depth test must be restored");
            assertEquals(GL11.GL_GREATER, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            assertTrue(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
        }
        finally
        {
            System.clearProperty("bbsae.debugDepthPlaneThrow");
        }

        /* The same instance must still compose a correct frame after the failed pass. */
        GlFramebuffer fresh = new GlFramebuffer(W, H);

        fresh.begin(DepthPlanePixelHarness.STAGE);
        fresh = compositor.composite(layer, new Matrix4f(), CompCamera.identity(W, H), fresh,
            CompLayer.BLEND_NORMAL, 1F);

        int center = fresh.readTopDown()[W / 2 + H / 2 * W];

        assertEquals(0xFFFF0000, center, "a composite after the injected failure must work");

        fresh.delete();
        layer.delete();
        accumulation.delete();
        callerFramebuffer.delete();
        compositor.delete();
    }

    /** A double release must not put the same target in the free list twice. */
    @Test
    public void doubleReleaseNeverDuplicatesAPooledTarget()
    {
        DepthPlaneCompositor first = DepthPlaneCompositor.acquire(W, H);

        DepthPlaneCompositor.release(first);
        DepthPlaneCompositor.release(first);
        DepthPlaneCompositor.release(null);

        DepthPlaneCompositor a = DepthPlaneCompositor.acquire(W, H);
        DepthPlaneCompositor b = DepthPlaneCompositor.acquire(W, H);

        assertNotSame(a, b, "the duplicate release would have handed the same target twice");
        assertEquals(first, a, "the single pooled entry must be reused first");

        DepthPlaneCompositor.release(a);
        DepthPlaneCompositor.release(b);
        DepthPlaneCompositor.deleteAll();
    }

    /** A stale reference to a deleted target must never re-enter the pool. */
    @Test
    public void aDeletedTargetCannotReenterThePool()
    {
        DepthPlaneCompositor compositor = DepthPlaneCompositor.acquire(W, H);

        compositor.delete();
        DepthPlaneCompositor.release(compositor);

        DepthPlaneCompositor fresh = DepthPlaneCompositor.acquire(W, H);

        assertNotSame(compositor, fresh, "a deleted target must not be handed out again");

        DepthPlaneCompositor.release(fresh);
        DepthPlaneCompositor.deleteAll();
    }

    private static boolean colorMaskAllEnabled()
    {
        java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(4);

        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);

        return mask.get(0) != 0 && mask.get(1) != 0 && mask.get(2) != 0 && mask.get(3) != 0;
    }

    private static boolean depthProgramAvailable()
    {
        return CompShaders.depthPlane != null && CompShaders.depthPlane.isValid();
    }
}
