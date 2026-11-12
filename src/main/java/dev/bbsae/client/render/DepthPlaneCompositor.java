package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-pixel depth compositing for flat 3D layers.
 *
 * <p>A 3D layer is a plane in camera space. For every fragment of its projected image the view ray
 * is intersected with that plane and {@code gl_FragDepth} is written, so two layers that cross in 3D
 * are resolved per pixel instead of by whole-layer painter order. The depth buffer is shared across
 * the 3D layers of one composition; 2D layers never touch it.</p>
 *
 * <p>Blending stays in {@link PassRenderer#draw}, so blend modes and opacity behave exactly as
 * before: after a layer's visible pixels are selected the colour target is composited normally.</p>
 */
final class DepthPlaneCompositor
{
    /* Reused across frames: allocating a colour+depth target every composition render churned GPU
     * memory while scrubbing/playing. Nested comps can hold several, so this is a small free list
     * per size. */
    private static final java.util.Map<Long, java.util.ArrayDeque<DepthPlaneCompositor>> POOL =
        new java.util.HashMap<>();
    private static final int MAX_PER_SIZE = 4;

    private final GlFramebuffer target;
    private final int width;
    private final int height;

    DepthPlaneCompositor(int width, int height)
    {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.target = new GlFramebuffer(this.width, this.height, true);
        this.clearDepth();
        this.target.clearColorOnly(0);
    }

    void clearDepth()
    {
        this.target.bindOnly();
        org.lwjgl.opengl.GL11.glDepthMask(true);
        org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Draws one 3D layer with a depth test against the layers already drawn, then blends the
     * surviving pixels onto {@code accumulation}.
     *
     * <p>The colour attachment is cleared at the start of every call. It used to be cleared only
     * between the depth pre-write and the colour passes, through a {@code glColorMask(false)} that
     * desynchronised {@code GlStateManager}'s cached mask, so the clear silently did nothing. The
     * target is pooled and reused across layers, frames and edits while the camera moves, which
     * turned it into a palimpsest: every frame re-composited all older frames' layer silhouettes
     * together with itself. That was the "camera preview duplicates the star/polygon layers and
     * shows stripes" ghost.</p>
     *
     * <p>All target writes run with blending off: the target holds the current layer's straight
     * alpha pixels, and {@link PassRenderer#draw} is the one place that blends them onto the
     * accumulation (blend modes and opacity unchanged). The depth buffer deliberately survives
     * across calls — that is the shared depth of the composition.</p>
     */
    GlFramebuffer composite(GlFramebuffer layer, Matrix4f world, CompCamera camera,
                            GlFramebuffer accumulation, int blendMode, float opacity)
    {
        GlslProgram program = CompShaders.depthPlane;

        if (program == null || layer == null || camera == null || accumulation == null)
        {
            return PassRenderer.draw(layer, accumulation, null, blendMode, opacity, 0F, 0F, 0xFFFFFF);
        }

        Vector3f normal = new Vector3f(0F, 0F, 1F);
        Vector3f origin = new Vector3f();

        world.transformDirection(normal);
        world.transformPosition(origin);

        Vector3f viewNormal = new Vector3f(normal);
        Vector3f viewOrigin = new Vector3f(origin);
        Matrix4f view = camera.view;

        view.transformDirection(viewNormal);
        view.transformPosition(viewOrigin);

        float planeD = viewNormal.dot(viewOrigin);
        Matrix4f invProjection = new Matrix4f(camera.projection).invert();

        program.use();
        PassRenderer.bindRaw(program, "Layer", 0, layer.color.id);
        program.set("InvProjection", invProjection);
        program.set("Projection", camera.projection);
        program.set("PlaneN", viewNormal.x, viewNormal.y, viewNormal.z);
        program.set("PlaneD", planeD);

        /* Snapshot the pipeline state this method changes. The target is not the screen, so its
         * writes are raw, but the next layer/effect/UI pass must see the state it had before. */
        boolean previousDepthTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        int previousDepthFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC);
        boolean previousDepthMask = org.lwjgl.opengl.GL11.glGetBoolean(org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK);
        boolean previousBlend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);

        try
        {
            /* Fresh colour for this layer only; the shared depth buffer is left untouched. */
            this.target.clearColorOnly(0);
            this.target.bindOnly();
            com.mojang.blaze3d.platform.GlStateManager._disableBlend();

            /* 1) Depth-only pass for the opaque part: a translucent layer must not occlude what is
             * behind it, so only alpha >= 0.5 writes depth. */
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LESS);
            org.lwjgl.opengl.GL11.glDepthMask(true);
            com.mojang.blaze3d.platform.GlStateManager._colorMask(false, false, false, false);
            program.set("Mode", 0);
            program.drawFullscreen();

            /* 2) Opaque colour at equality: only the fragments the depth pass accepted survive. */
            com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);
            org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_EQUAL);
            org.lwjgl.opengl.GL11.glDepthMask(false);
            program.set("Mode", 1);
            program.drawFullscreen();

            /* 3) Translucent colour, less-or-equal against the depth of the layers behind it and no
             * depth writes: a semitransparent layer blends over nearer opaque pixels only when it is
             * actually in front of them. */
            org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            org.lwjgl.opengl.GL11.glDepthMask(false);
            program.set("Mode", 2);
            program.drawFullscreen();
        }
        finally
        {
            com.mojang.blaze3d.platform.GlStateManager._colorMask(true, true, true, true);
            org.lwjgl.opengl.GL11.glDepthMask(previousDepthMask);
            org.lwjgl.opengl.GL11.glDepthFunc(previousDepthFunc);

            if (previousDepthTest)
            {
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            }
            else
            {
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            }

            if (previousBlend)
            {
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
            }
            else
            {
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
            }
        }

        return PassRenderer.draw(this.target, accumulation, null, blendMode, opacity, 0F, 0F, 0xFFFFFF);
    }

    void delete()
    {
        this.target.delete();
    }

    static synchronized DepthPlaneCompositor acquire(int width, int height)
    {
        long key = ((long) Math.max(1, width) << 32) | (Math.max(1, height) & 0xFFFFFFFFL);
        java.util.ArrayDeque<DepthPlaneCompositor> free = POOL.get(key);

        if (free == null)
        {
            free = new java.util.ArrayDeque<>();
            POOL.put(key, free);
        }

        DepthPlaneCompositor compositor = free.pollFirst();

        if (compositor == null)
        {
            compositor = new DepthPlaneCompositor(width, height);
        }
        else
        {
            /* Both attachments must be neutral on hand-out: what the previous render left in the
             * colour buffer used to be composited into this frame (the ghost), and a stale depth
             * buffer would reject layers outright. */
            compositor.clearDepth();
            compositor.target.clearColorOnly(0);
        }

        return compositor;
    }

    static synchronized void release(DepthPlaneCompositor compositor)
    {
        if (compositor == null)
        {
            return;
        }

        long key = ((long) compositor.width << 32) | (compositor.height & 0xFFFFFFFFL);
        java.util.ArrayDeque<DepthPlaneCompositor> free = POOL.computeIfAbsent(key,
            k -> new java.util.ArrayDeque<>());

        if (free.size() >= MAX_PER_SIZE)
        {
            compositor.delete();

            return;
        }

        compositor.clearDepth();
        compositor.target.clearColorOnly(0);
        free.addFirst(compositor);
    }

    /** Frees every pooled target; call with the rest of the render caches. */
    public static synchronized void deleteAll()
    {
        for (java.util.ArrayDeque<DepthPlaneCompositor> free : POOL.values())
        {
            for (DepthPlaneCompositor compositor : free)
            {
                compositor.delete();
            }
        }

        POOL.clear();
    }
}
