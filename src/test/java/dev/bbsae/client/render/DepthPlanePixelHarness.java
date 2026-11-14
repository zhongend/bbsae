package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompShape;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assumptions;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only pixel harness for the main-composition per-pixel depth path.
 *
 * <p>{@code CompRenderer} cannot be instantiated headless (it needs {@code MinecraftClient},
 * {@code Batcher2D}, the game render thread), so these tests drive the same primitives the renderer
 * calls — {@link DepthPlaneCompositor#composite} for depth, {@link PassRenderer#draw} for painter
 * order — with the same camera matrices, the same composition-space content pass
 * ({@code projection · model}, un-premultiplied straight alpha) and the same final composite.
 * {@link #routed} mirrors the renderer's own branch ({@link CompRenderer#perPixelDepthFrame} /
 * {@link CompRenderer#perPixelDepthLayer}): one pooled compositor per frame, per-pixel depth only
 * for {@link CompScene#usePerspective} layers, whole-layer order for the rest.</p>
 *
 * <p>The user project {@code ____bug} camera (eye 1968.67/596.55/1370.67, yaw -25.74, pitch -5.26,
 * fov 50) and its two shape layers (hexagon z=198.36, star z=736.85) are reproduced verbatim from
 * {@code run/config/bbsae/projects/____bug.json}.</p>
 *
 * <p>{@link #setPlate} lets a test give a layer a pre-rendered composition plate (the nested comp
 * of a precomp layer) instead of a shape raster, so precomp combinations can be composed through
 * both paths too.</p>
 */
final class DepthPlanePixelHarness
{
    static final int W = 384;
    static final int H = 216;
    /** Opaque stage colour the renderer clears the accumulation with. */
    static final int STAGE = 0xFF000000;
    /** Transparent stage colour used by alpha exports (and nested comps). */
    static final int TRANSPARENT_STAGE = 0x00000000;

    private static final String CONTENT_VERTEX = """
        #version 150 core
        in vec2 Position;
        in vec2 UV;
        uniform mat4 Mvp;
        out vec2 vUv;
        void main()
        {
            vUv = UV;
            gl_Position = Mvp * vec4(Position, 0.0, 1.0);
        }
        """;

    private static final String CONTENT_FRAGMENT = """
        #version 150 core
        uniform sampler2D Source;
        uniform vec4 Tint;
        in vec2 vUv;
        out vec4 fragColor;
        void main()
        {
            vec4 texel = texture(Source, vUv);
            fragColor = vec4(texel.rgb * Tint.rgb, texel.a * Tint.a);
        }
        """;

    private static long window;
    private static GlslProgram contentProgram;
    private static int quadVao;
    private static int quadVbo;

    /** Pre-rendered composition plates keyed by the layer they replace (precomp layers). */
    private static Map<CompLayer, GlFramebuffer> plates = new IdentityHashMap<>();

    private DepthPlanePixelHarness()
    {}

    /* ------------------------------------------------------------- lifecycle */

    static void initGl()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "bbsae depth pixel test", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        try
        {
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        }
        catch (IllegalStateException ignored)
        {
            /* Another GL test class in this JVM already owns the global render thread. */
        }

        /* Targets created by a previous test class belong to a dead context. */
        FboPool.clear();
        DepthPlaneCompositor.deleteAll();
        ShapeCache.clear();
        CompShaders.reload();
        CompShaders.init();

        contentProgram = new GlslProgram("test_layer_content", CONTENT_VERTEX, CONTENT_FRAGMENT);
        assertTrue(contentProgram.isValid(), "test content program failed to link");

        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL30.glGenBuffers();
        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * 4, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * 4, 2L * 4L);
        GL30.glBindVertexArray(0);
    }

    static void shutdownGl()
    {
        if (contentProgram != null)
        {
            contentProgram.delete();
            contentProgram = null;
        }

        if (quadVbo != 0)
        {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }

        if (quadVao != 0)
        {
            GL30.glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }

        System.clearProperty("bbsae.debugDepthPlaneThrow");
        plates.clear();
        ShapeCache.clear();
        DepthPlaneCompositor.deleteAll();
        FboPool.clear();

        if (window != 0L)
        {
            GLFW.glfwDestroyWindow(window);
            window = 0L;
        }

        if (GLFW.glfwInit())
        {
            GLFW.glfwTerminate();
        }
    }

    /* ---------------------------------------------------------------- scenes */

    /**
     * The camera of the user's "我的合成BUG" project. Numbers are copied verbatim from
     * {@code run/config/bbsae/projects/____bug.json} so the regression always uses the pose that
     * reproduced the ghost (a free BBS camera, tilted 25.7° yaw / 5.3° pitch).
     */
    static CompDocument bugScene()
    {
        CompDocument comp = new CompDocument();

        comp.id = "main";
        comp.name = "Main";
        comp.width = 1920;
        comp.height = 1080;
        comp.fps = 60F;
        comp.duration = 12F;
        comp.background = STAGE;
        comp.activeCameraId = "camera";

        CompLayer camera = new CompLayer("camera", "Camera", "camera");

        camera.visible = true;
        camera.in = 0F;
        camera.out = 12F;
        camera.threeD = true;
        camera.x = 1968.6704F;
        camera.y = 596.5454F;
        camera.z = 1370.6732F;
        camera.scaleX = 8.508251F;
        camera.scaleY = 8.508251F;
        camera.cameraZ = 1370.6732F;
        camera.cameraFov = 50F;
        camera.cameraNear = 10F;
        camera.cameraFar = 100000F;
        camera.camera3D = true;
        camera.cameraStyle = CompLayer.CAMERA_BBS;
        camera.cameraLookAt = false;
        camera.rotationX = -5.259262F;
        camera.rotationY = -25.73563F;
        comp.addLayer(camera);

        comp.addLayer(shape("polygon", 1536.6382F, 592.3063F, 198.3579F, 2.2615314F,
            -11151734, CompShape.POLYGON, 6));
        comp.addLayer(shape("star", 1310.6769F, 615.4269F, 736.846F, 1F,
            -16051, CompShape.STAR, 5));

        return comp;
    }

    /**
     * A composition with one plain 2D layer and one 3D layer under a tilted camera. Under the AE
     * per-layer logic only the 3D layer is perspective; under {@code legacy3DLogic} every layer is
     * projected and must go through the depth path.
     */
    static CompDocument twoDPlusThreeDScene()
    {
        CompDocument comp = bugScene();
        CompLayer flat = shape("flat2d", 700F, 350F, 0F, 1.4F, -65536, CompShape.ELLIPSE, 6);

        flat.threeD = false;
        comp.addLayer(flat);

        return comp;
    }

    /**
     * N opaque 3D shape layers spread over the composition at different z. Parallel-plane scenes:
     * whole-layer painter order is exact here, so the depth path must reproduce it exactly. Used by
     * the cost benchmark; contents are small enough for a 1080p target.
     */
    static CompDocument multiLayerScene(int layers)
    {
        CompDocument comp = bugScene();
        int[] fills = {0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835, 0xFF8E24AA, 0xFF00ACC1, 0xFFFB8C00, 0xFF6D4C41};

        for (int i = 0; i < layers; i++)
        {
            float x = 400F + (i % 4) * 420F;
            float y = 300F + (i / 4) * 380F;
            CompLayer layer = shape("bench" + i, x, y, 40F * i, 1F, fills[i % fills.length],
                CompShape.POLYGON, 5 + (i % 3));

            comp.addLayer(layer);
        }

        return comp;
    }

    /** Project whose developer switch selects legacy "every layer follows the camera" logic. */
    static CompProject project(boolean legacy3DLogic)
    {
        CompProject project = new CompProject();

        project.settings.legacy3DLogic = legacy3DLogic;

        return project;
    }

    /** All pixel layers of a scene, in stack order (cameras have no pixels). */
    static List<CompLayer> layersOf(CompDocument comp)
    {
        List<CompLayer> layers = new ArrayList<>();

        for (CompLayer layer : comp.layers)
        {
            if (!"camera".equals(layer.type))
            {
                layers.add(layer);
            }
        }

        return layers;
    }

    static List<CompLayer> farFirst(CompRenderContext context, List<CompLayer> layers)
    {
        /* Mirror CompRenderer.orderLayers: only runs of camera-affected 3D layers are sorted.
         * A 2D layer keeps its stack slot (AE) and breaks the run. */
        List<CompLayer> ordered = new ArrayList<>(layers);
        int start = 0;

        for (int i = 0; i <= ordered.size(); i++)
        {
            boolean boundary = i == ordered.size() || ordered.get(i).independent
                || !CompScene.sortsIn3D(context, ordered.get(i));

            if (!boundary)
            {
                continue;
            }

            if (i - start > 1)
            {
                List<CompLayer> run = ordered.subList(start, i);

                run.sort(Comparator.comparingDouble((CompLayer layer) -> CompScene.planeDepth(context, layer)));
            }

            start = i + 1;
        }

        return ordered;
    }

    static CompRenderContext context(CompProject project, CompDocument comp)
    {
        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    static float opacity(CompLayer layer)
    {
        return Math.max(0F, Math.min(1F, layer.opacity));
    }

    /* -------------------------------------------------------- shape / plate */

    private static CompLayer shape(String id, float x, float y, float z, float scale,
                                   int fillColor, String shapeType, int sides)
    {
        CompLayer layer = new CompLayer(id, id, "shape");

        layer.visible = true;
        layer.in = 0F;
        layer.out = 12F;
        layer.threeD = true;
        layer.x = x;
        layer.y = y;
        layer.z = z;
        layer.width = 320F;
        layer.height = 320F;
        layer.scaleX = scale;
        layer.scaleY = scale;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;
        layer.opacity = 1F;
        layer.blendMode = CompLayer.BLEND_NORMAL;

        CompShape shape = new CompShape();

        shape.id = "shape_1";
        shape.type = shapeType;
        shape.enabled = true;
        shape.width = 320F;
        shape.height = 320F;
        shape.sides = sides;
        shape.innerRadius = 0.45F;
        shape.outerRadius = 1F;
        shape.fillColor = fillColor;
        layer.shapes.add(shape);

        return layer;
    }

    /** Adds a plain solid rectangle layer (used by the translucent-plate combination test). */
    static CompLayer addRect(CompDocument comp, String id, float x, float y, float z,
                             float width, float height, int color, float opacity, boolean threeD)
    {
        CompLayer layer = new CompLayer(id, id, "shape");
        CompShape shape = new CompShape();

        layer.visible = true;
        layer.in = 0F;
        layer.out = 12F;
        layer.threeD = threeD;
        layer.x = x;
        layer.y = y;
        layer.z = z;
        layer.width = width;
        layer.height = height;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;
        layer.opacity = opacity;
        layer.blendMode = CompLayer.BLEND_NORMAL;

        shape.id = "shape_1";
        shape.type = CompShape.RECT;
        shape.enabled = true;
        shape.width = width;
        shape.height = height;
        shape.fillColor = color;
        layer.shapes.add(shape);
        comp.addLayer(layer);

        return layer;
    }

    /** Lets a layer (normally a precomp) composite a pre-rendered composition plate. */
    static void setPlate(CompLayer layer, GlFramebuffer plate)
    {
        plates.put(layer, plate);
    }

    static void clearPlates()
    {
        plates.clear();
    }

    /* ------------------------------------------------------------ primitives */

    static GlFramebuffer solid(int argb)
    {
        return solid(argb, W, H);
    }

    static GlFramebuffer solid(int argb, int width, int height)
    {
        GlFramebuffer framebuffer = new GlFramebuffer(width, height);
        int[] pixels = new int[width * height];

        for (int i = 0; i < pixels.length; i++)
        {
            pixels[i] = argb;
        }

        framebuffer.color.uploadTopDown(pixels);

        return framebuffer;
    }

    static GlFramebuffer rectangle(int argb, int rx0, int ry0, int rx1, int ry1)
    {
        GlFramebuffer framebuffer = new GlFramebuffer(W, H);
        int[] pixels = new int[W * H];

        for (int y = ry0; y < ry1; y++)
        {
            for (int x = rx0; x < rx1; x++)
            {
                pixels[x + y * W] = argb;
            }
        }

        framebuffer.color.uploadTopDown(pixels);

        return framebuffer;
    }

    /**
     * Renders one layer's content the way {@code LayerContentRenderer} does: the layer's
     * layer-space raster (or a precomp plate) is drawn with {@code layerProjection · model} into a
     * composition-sized target, blended SRC_ALPHA, then un-premultiplied. Both matrices come from
     * the same {@link CompScene} calls the renderer uses, so perspective 3D layers get the camera
     * projection and 2D layers the affine {@code view2D}.
     */
    static GlFramebuffer content(CompRenderContext context, CompCamera camera, CompLayer layer)
    {
        return content(context, camera, layer, W, H);
    }

    static GlFramebuffer content(CompRenderContext context, CompCamera camera, CompLayer layer,
                                 int width, int height)
    {
        GlFramebuffer target = new GlFramebuffer(width, height);

        target.begin(0);

        Matrix4f mvp = new Matrix4f(CompScene.projection(context, layer)).mul(CompScene.model(context, layer));

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColorMask(true, true, true, true);
        contentProgram.use();
        contentProgram.set("Tint", 1F, 1F, 1F, 1F);

        GlFramebuffer plate = plates.get(layer);

        if (plate != null)
        {
            /* Same placement as LayerContentRenderer.drawPrecomp: the nested plate is fitted into
             * the layer rectangle (imageFit 0 = contain) and drawn as the layer-local image. The
             * plate is a top-down FBO, so the V axis is flipped like every composition blit. */
            float sx = layer.width / Math.max(1F, plate.width);
            float sy = layer.height / Math.max(1F, plate.height);
            float fit = layer.imageFit == 1 ? Math.max(sx, sy) : layer.imageFit == 2 ? 0F : Math.min(sx, sy);
            float qw = fit == 0F ? layer.width : plate.width * fit;
            float qh = fit == 0F ? layer.height : plate.height * fit;
            float qx = -qw * layer.anchorX;
            float qy = -qh * layer.anchorY;

            drawTexturedQuad(mvp, plate.color.id, qx, qy, qx + qw, qy + qh,
                0F, 1F, 1F, 0F);
        }
        else
        {
            ShapeCache.Raster raster = ShapeCache.get(layer, 0F);

            assertNotNull(raster, "shape raster for layer " + layer.id);

            float x0 = raster.originX;
            float y0 = raster.originY;
            float x1 = raster.originX + raster.width;
            float y1 = raster.originY + raster.height;

            drawTexturedQuad(mvp, raster.texture.id, x0, y0, x1, y1, 0F, 0F, 1F, 1F);
        }

        GlFramebuffer straight = PassRenderer.unpremultiply(target);

        if (straight != target)
        {
            target.delete();
        }

        return straight;
    }

    /** One textured quad; UVs are (top-left, bottom-right) in the same layout texturedBox uses. */
    private static void drawTexturedQuad(Matrix4f mvp, int texture, float x0, float y0, float x1, float y1,
                                         float uTopLeft, float vTopLeft, float uBottomRight, float vBottomRight)
    {
        /* Same vertex layout as Batcher2D.fillTexturedBox: (top-left, bottom-right) UVs. */
        float[] data =
        {
            x0, y1, uTopLeft, vBottomRight,
            x1, y1, uBottomRight, vBottomRight,
            x1, y0, uBottomRight, vTopLeft,
            x0, y1, uTopLeft, vBottomRight,
            x1, y0, uBottomRight, vTopLeft,
            x0, y0, uTopLeft, vTopLeft,
        };

        PassRenderer.bindRaw(contentProgram, "Source", 0, texture);

        FloatBuffer buffer = BufferUtils.createFloatBuffer(data.length);

        buffer.put(data).flip();
        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        contentProgram.set("Mvp", mvp);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders a whole composition through the painter path into a transparent-stage target, which
     * is exactly what a precomp layer nests (see {@code LayerContentRenderer.drawPrecomp}).
     */
    static GlFramebuffer renderNestedPlate(CompProject project, CompDocument comp, CompCamera camera,
                                           int width, int height)
    {
        CompRenderContext context = context(project, comp);
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(width, height);

        owned.add(accumulation);
        accumulation.begin(TRANSPARENT_STAGE);

        for (CompLayer layer : farFirst(context, layersOf(comp)))
        {
            GlFramebuffer content = content(context, camera, layer, width, height);

            owned.add(content);
            accumulation = PassRenderer.draw(content, accumulation, null, layer.blendMode,
                opacity(layer), 0F, 0F, 0xFFFFFF);
            owned.add(accumulation);
        }

        /* Keep the result; every other target in the chain is dead once it is drawn. */
        for (GlFramebuffer framebuffer : owned)
        {
            if (framebuffer != accumulation)
            {
                framebuffer.delete();
            }
        }

        return accumulation;
    }

    /* -------------------------------------------------------------- rendering */

    /** Whole-layer painter path: exactly what the composition renders with depth-off. */
    static int[] paint(CompProject project, CompDocument comp, CompCamera camera)
    {
        return paint(project, comp, camera, W, H, STAGE);
    }

    static int[] paint(CompProject project, CompDocument comp, CompCamera camera,
                       int width, int height, int stage)
    {
        CompRenderContext context = context(project, comp);
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(width, height);

        owned.add(accumulation);
        accumulation.begin(stage);

        for (CompLayer layer : farFirst(context, layersOf(comp)))
        {
            GlFramebuffer content = content(context, camera, layer, width, height);

            owned.add(content);
            accumulation = PassRenderer.draw(content, accumulation, null, layer.blendMode,
                opacity(layer), 0F, 0F, 0xFFFFFF);
            owned.add(accumulation);
        }

        int[] pixels = accumulation.readTopDown();

        deleteAll(owned);

        return pixels;
    }

    /** Every layer through the per-pixel depth compositor (direct path comparison). */
    static int[] depthAll(CompProject project, CompDocument comp, CompCamera camera,
                          DepthPlaneCompositor compositor)
    {
        return depthAll(project, comp, camera, compositor, W, H, STAGE);
    }

    static int[] depthAll(CompProject project, CompDocument comp, CompCamera camera,
                          DepthPlaneCompositor compositor, int width, int height, int stage)
    {
        CompRenderContext context = context(project, comp);
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(width, height);

        owned.add(accumulation);
        accumulation.begin(stage);

        for (CompLayer layer : farFirst(context, layersOf(comp)))
        {
            GlFramebuffer content = content(context, camera, layer, width, height);

            owned.add(content);
            accumulation = compositor.composite(content, CompScene.world(context, layer), camera,
                accumulation, layer.blendMode, opacity(layer));
            owned.add(accumulation);
        }

        int[] pixels = accumulation.readTopDown();

        deleteAll(owned);

        return pixels;
    }

    /**
     * Mirrors {@code CompRenderer.renderCompToFbo}'s branch: when the composition is spatial and
     * the per-pixel-depth switch is on, one compositor is shared by the frame and only the
     * perspective layers go through it; every other layer keeps the painter path. The switch is
     * passed explicitly because {@code CompPrefs} needs FabricLoader; the renderer reads the same
     * boolean from the preference (and both use the same gate helpers, see the tests).
     *
     * @param pooled optional pre-acquired compositor to reuse across calls (pooled-target test);
     *               ignored when the switch is off.
     */
    static int[] routed(CompProject project, CompDocument comp, CompCamera camera,
                        boolean perPixelDepth, DepthPlaneCompositor pooled)
    {
        return routed(project, comp, camera, perPixelDepth, pooled, W, H, STAGE);
    }

    static int[] routed(CompProject project, CompDocument comp, CompCamera camera,
                        boolean perPixelDepth, DepthPlaneCompositor pooled,
                        int width, int height, int stage)
    {
        CompRenderContext context = context(project, comp);
        boolean useDepth = CompScene.spatial(context) && perPixelDepth;
        DepthPlaneCompositor compositor = useDepth
            ? (pooled != null ? pooled : new DepthPlaneCompositor(width, height))
            : null;
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(width, height);

        owned.add(accumulation);
        accumulation.begin(stage);

        try
        {
            for (CompLayer layer : farFirst(context, layersOf(comp)))
            {
                GlFramebuffer content = content(context, camera, layer, width, height);

                owned.add(content);

                if (useDepth && CompScene.usePerspective(context, layer))
                {
                    accumulation = compositor.composite(content, CompScene.world(context, layer), camera,
                        accumulation, layer.blendMode, opacity(layer));
                }
                else
                {
                    accumulation = PassRenderer.draw(content, accumulation, null, layer.blendMode,
                        opacity(layer), 0F, 0F, 0xFFFFFF);
                }

                owned.add(accumulation);
            }
        }
        finally
        {
            /* A direct-path compositor created here is not pooled by the caller. */
            if (useDepth && pooled == null)
            {
                compositor.delete();
            }
        }

        int[] pixels = accumulation.readTopDown();

        deleteAll(owned);

        return pixels;
    }

    /**
     * Renders one frame into a returned target without reading it back (the caller must delete the
     * returned framebuffer). Used by the cost benchmark, where {@code glReadPixels} would otherwise
     * dominate the measurement.
     */
    static GlFramebuffer renderFrameFbo(CompProject project, CompDocument comp, CompCamera camera,
                                        boolean depth, DepthPlaneCompositor compositor,
                                        int width, int height, int stage)
    {
        CompRenderContext context = context(project, comp);
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(width, height);

        owned.add(accumulation);
        accumulation.begin(stage);

        boolean useDepth = depth && CompScene.spatial(context);

        for (CompLayer layer : farFirst(context, layersOf(comp)))
        {
            GlFramebuffer content = content(context, camera, layer, width, height);

            owned.add(content);

            if (useDepth && CompScene.usePerspective(context, layer))
            {
                accumulation = compositor.composite(content, CompScene.world(context, layer), camera,
                    accumulation, layer.blendMode, opacity(layer));
            }
            else
            {
                accumulation = PassRenderer.draw(content, accumulation, null, layer.blendMode,
                    opacity(layer), 0F, 0F, 0xFFFFFF);
            }

            owned.add(accumulation);
        }

        for (GlFramebuffer framebuffer : owned)
        {
            if (framebuffer != accumulation)
            {
                framebuffer.delete();
            }
        }

        return accumulation;
    }

    static DepthPlaneCompositor acquire(int width, int height)
    {
        return DepthPlaneCompositor.acquire(width, height);
    }

    static void release(DepthPlaneCompositor compositor)
    {
        DepthPlaneCompositor.release(compositor);
    }

    /* --------------------------------------------------------------- asserts */

    static void deleteAll(List<GlFramebuffer> owned)
    {
        Map<GlFramebuffer, Boolean> seen = new IdentityHashMap<>();

        for (GlFramebuffer framebuffer : owned)
        {
            if (framebuffer != null && seen.put(framebuffer, Boolean.TRUE) == null)
            {
                framebuffer.delete();
            }
        }
    }

    static void assertFramesEqual(int[] expected, int[] actual, int tolerance, String what)
    {
        assertEquals(expected.length, actual.length, what + ": size");

        int differing = 0;
        int worst = 0;

        for (int i = 0; i < expected.length; i++)
        {
            int a = expected[i];
            int b = actual[i];

            for (int shift = 0; shift < 32; shift += 8)
            {
                int delta = Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF));

                if (delta > 0)
                {
                    differing += delta > tolerance ? 1 : 0;
                    worst = Math.max(worst, delta);
                }
            }
        }

        if (differing > 0 || worst > tolerance)
        {
            writeEvidence(what, expected, actual);
        }

        assertTrue(differing == 0, what + ": " + differing + " channel values differ by more than "
            + tolerance + " (worst " + worst + "); see build/depth-ghost-evidence/");
    }

    /** Writes painter/depth evidence; the frame size is inferred from the pixel count (16:9). */
    private static void writeEvidence(String what, int[] painter, int[] depth)
    {
        try
        {
            File directory = new File("build/depth-ghost-evidence");
            directory.mkdirs();

            int width = frameWidth(painter.length);
            int height = Math.max(1, painter.length / width);

            writePng(new File(directory, what + "-painter.png"), painter, width, height);
            writePng(new File(directory, what + "-depth.png"), depth, width, height);
        }
        catch (RuntimeException ignored)
        {
            /* Evidence is a debugging aid; never fail the regression because of it. */
        }
    }

    private static int frameWidth(int pixels)
    {
        int width = Math.max(1, (int) Math.round(Math.sqrt(pixels * 16.0 / 9.0)));

        while (width > 1 && pixels % width != 0)
        {
            width -= 1;
        }

        return width;
    }

    /** Pixels per channel value that differ (0 = identical frames). */
    static int pixelDiff(int[] a, int[] b)
    {
        if (a.length != b.length)
        {
            return Integer.MAX_VALUE;
        }

        int different = 0;

        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
            {
                different += 1;
            }
        }

        return different;
    }

    private static void writePng(File file, int[] pixels, int width, int height)
    {
        try
        {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            image.setRGB(0, 0, width, height, pixels, 0, width);
            ImageIO.write(image, "png", file);
        }
        catch (Exception e)
        {
            /* Evidence is a debugging aid; never fail the regression because of it. */
        }
    }
}
