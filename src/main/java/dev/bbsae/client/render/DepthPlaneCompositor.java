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
    private final GlFramebuffer target;
    private final int width;
    private final int height;

    DepthPlaneCompositor(int width, int height)
    {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.target = new GlFramebuffer(this.width, this.height, true);
        this.clearDepth();
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

        /* 1) Depth-only pass: writes the plane's depth where the layer has coverage. */
        this.target.bindOnly();
        org.lwjgl.opengl.GL11.glColorMask(false, false, false, false);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_LESS);
        org.lwjgl.opengl.GL11.glDepthMask(true);
        program.set("WriteColor", 0);
        program.drawFullscreen();

        /* 2) Colour pass at equality: only the fragments the depth pass accepted survive. */
        this.target.clearColorOnly(0);
        org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
        org.lwjgl.opengl.GL11.glDepthFunc(org.lwjgl.opengl.GL11.GL_EQUAL);
        org.lwjgl.opengl.GL11.glDepthMask(false);
        program.set("WriteColor", 1);
        program.drawFullscreen();

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDepthMask(true);

        return PassRenderer.draw(this.target, accumulation, null, blendMode, opacity, 0F, 0F, 0xFFFFFF);
    }

    void delete()
    {
        this.target.delete();
    }
}
