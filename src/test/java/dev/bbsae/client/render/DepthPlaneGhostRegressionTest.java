package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompShape;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * Pixel regression for the main-composition per-pixel depth path (user requirement 3).
 *
 * <p>The depth-plane compositor keeps one colour + depth target per composition render. The
 * depth buffer has to survive from layer to layer, but the colour attachment must be reset before
 * every layer: the target is reused across layers, frames and pooled renders, so any stale colour
 * is composited onto the accumulation a second (or nth) time. That is what the "camera preview
 * duplicated star/polygon layers and stripes" ghost was: the colour attachment was never actually
 * cleared, so every frame left its picture in the reused target and each new frame composited all
 * older pictures plus itself.</p>
 *
 * <p>The tests compose the real user project {@code ____bug} camera (position/rotation copied from
 * {@code run/config/bbsae/projects/____bug.json}: 1920x1080, fov 50, eye at 1968.67/596.55/1370.67,
 * yaw -25.74, pitch -5.26) with the same two shape layers (hexagon at z=198.36, star at z=736.85)
 * and compare the depth path against the whole-layer painter path the renderer uses with the switch
 * off. Both paths must produce the same frame — per-pixel depth only resolves crossings, it may not
 * change what a flat layer looks like.</p>
 */
public class DepthPlaneGhostRegressionTest
{
    private static final int W = 384;
    private static final int H = 216;
    /** Opaque stage, the same colour the renderer clears the accumulation with. */
    private static final int STAGE = 0xFF000000;

    private static long window;
    private static GlslProgram contentProgram;
    private static int quadVao;
    private static int quadVbo;

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

    @BeforeAll
    public static void createContext()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "bbsae depth ghost test", 0L, 0L);

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

    @AfterAll
    public static void destroyContext()
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

    /* --------------------------------------------------------------- scenes */

    /**
     * The camera of the user's "我的合成BUG" project. Numbers are copied verbatim from
     * {@code run/config/bbsae/projects/____bug.json} so the regression always uses the pose that
     * reproduced the ghost (a free BBS camera, tilted 25.7° yaw / 5.3° pitch).
     */
    private static CompDocument bugScene()
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

    /** Marker seen by the tests as "the user's frame" (same shape bounds as the bug project). */
    private static List<CompLayer> bugLayers(CompDocument comp)
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

    /* -------------------------------------------------------------- helpers */

    private static GlFramebuffer solid(int argb)
    {
        GlFramebuffer framebuffer = new GlFramebuffer(W, H);
        int[] pixels = new int[W * H];

        for (int i = 0; i < pixels.length; i++)
        {
            pixels[i] = argb;
        }

        framebuffer.color.uploadTopDown(pixels);

        return framebuffer;
    }

    private static GlFramebuffer rectangle(int argb, int rx0, int ry0, int rx1, int ry1)
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
     * Renders one layer's content the way {@code LayerContentRenderer} does for a perspective 3D
     * layer: the layer's layer-space raster is drawn with {@code projection · view · world} into a
     * composition-sized target, blended SRC_ALPHA, then un-premultiplied.
     */
    private static GlFramebuffer content(CompRenderContext context, CompCamera camera, CompLayer layer)
    {
        ShapeCache.Raster raster = ShapeCache.get(layer, 0F);

        assertNotNull(raster, "shape raster for layer " + layer.id);

        GlFramebuffer target = new GlFramebuffer(W, H);

        target.begin(0);

        Matrix4f model = new Matrix4f(camera.view).mul(CompScene.world(context, layer));
        Matrix4f mvp = new Matrix4f(camera.projection).mul(model);
        float x0 = raster.originX;
        float y0 = raster.originY;
        float x1 = raster.originX + raster.width;
        float y1 = raster.originY + raster.height;

        /* Same vertex layout as Batcher2D.fillTexturedBox: (top-left, bottom-right) UVs, v down. */
        float[] data =
        {
            x0, y1, 0F, 1F,
            x1, y1, 1F, 1F,
            x1, y0, 1F, 0F,
            x0, y1, 0F, 1F,
            x1, y0, 1F, 0F,
            x0, y0, 0F, 0F,
        };

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColorMask(true, true, true, true);

        contentProgram.use();
        PassRenderer.bindRaw(contentProgram, "Source", 0, raster.texture.id);
        contentProgram.set("Mvp", mvp);
        contentProgram.set("Tint", 1F, 1F, 1F, 1F);

        FloatBuffer buffer = BufferUtils.createFloatBuffer(data.length);

        buffer.put(data).flip();
        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        GlFramebuffer straight = PassRenderer.unpremultiply(target);

        if (straight != target)
        {
            target.delete();
        }

        return straight;
    }

    /** Painter path: whole-layer far-to-near compositing, exactly what depth-off renders. */
    private static int[] paint(CompDocument comp, CompCamera camera)
    {
        CompRenderContext context = new CompRenderContext(new CompProject(), comp, 0F, null, null, null);
        List<CompLayer> ordered = farFirst(context, bugLayers(comp));
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(W, H);

        owned.add(accumulation);
        accumulation.begin(STAGE);

        for (CompLayer layer : ordered)
        {
            GlFramebuffer content = content(context, camera, layer);

            owned.add(content);
            accumulation = PassRenderer.draw(content, accumulation, null,
                CompLayer.BLEND_NORMAL, opacity(layer), 0F, 0F, 0xFFFFFF);
            owned.add(accumulation);
        }

        int[] pixels = accumulation.readTopDown();

        deleteAll(owned);

        return pixels;
    }

    /** Depth path: same layers through {@link DepthPlaneCompositor}, reusing the pooled target. */
    private static int[] depth(CompDocument comp, CompCamera camera, DepthPlaneCompositor compositor)
    {
        CompRenderContext context = new CompRenderContext(new CompProject(), comp, 0F, null, null, null);
        List<CompLayer> ordered = farFirst(context, bugLayers(comp));
        List<GlFramebuffer> owned = new ArrayList<>();
        GlFramebuffer accumulation = new GlFramebuffer(W, H);

        owned.add(accumulation);
        accumulation.begin(STAGE);

        for (CompLayer layer : ordered)
        {
            GlFramebuffer content = content(context, camera, layer);

            owned.add(content);
            accumulation = compositor.composite(content, CompScene.world(context, layer), camera,
                accumulation, CompLayer.BLEND_NORMAL, opacity(layer));
            owned.add(accumulation);
        }

        int[] pixels = accumulation.readTopDown();

        deleteAll(owned);

        return pixels;
    }

    private static void deleteAll(List<GlFramebuffer> owned)
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

    private static float opacity(CompLayer layer)
    {
        return Math.max(0F, Math.min(1F, layer.opacity));
    }

    /** Production order: ascending plane depth = far first (ties keep stack order). */
    private static List<CompLayer> farFirst(CompRenderContext context, List<CompLayer> layers)
    {
        List<CompLayer> ordered = new ArrayList<>(layers);

        ordered.sort(Comparator.comparingDouble((CompLayer layer) -> CompScene.planeDepth(context, layer)));

        return ordered;
    }

    private static void assertFramesEqual(int[] expected, int[] actual, int tolerance, String what)
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
            File directory = new File("build/depth-ghost-evidence");
            directory.mkdirs();
            writePng(new File(directory, what + "-painter.png"), expected);
            writePng(new File(directory, what + "-depth.png"), actual);
        }

        assertTrue(differing == 0, what + ": " + differing + " channel values differ by more than "
            + tolerance + " (worst " + worst + "); see build/depth-ghost-evidence/");
    }

    private static void writePng(File file, int[] pixels)
    {
        try
        {
            BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);

            image.setRGB(0, 0, W, H, pixels, 0, W);
            ImageIO.write(image, "png", file);
        }
        catch (Exception e)
        {
            /* Evidence is a debugging aid; never fail the regression because of it. */
        }
    }

    /* ---------------------------------------------------------------- tests */

    /**
     * The exact repro: the same project composed twice, once through the painter path and once
     * through the per-pixel depth path, at the ghost-reproduction camera pose. A static frame must
     * be the same frame; per-pixel depth may only resolve layer crossings.
     *
     * <p>Tolerance 1/255: {@code DEPTH_PLANE} drops fragments with alpha &lt; 0.004 (that shader
     * file belongs to the effects workstream this round), which is exactly the alpha = 1/255
     * antialias step of the shape raster. The whole-layer path keeps those near-invisible pixels,
     * the depth path drops them: a handful of edge pixels differ by one RGB step. The ghost being
     * pinned here is orders of magnitude larger (see the stale-target and translucent tests, which
     * assert exactly).</p>
     */
    @Test
    public void theBugProjectFrameMatchesTheDepthOffFrame()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompDocument comp = bugScene();
        CompCamera camera = CompCamera.of(comp, 0F, 0F);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(W, H);

        try
        {
            int[] painter = paint(comp, camera);
            int[] depth = depth(comp, camera, compositor);

            assertFramesEqual(painter, depth, 1, "bug-project-single-frame");
        }
        finally
        {
            compositor.delete();
        }
    }

    /**
     * The ghost was temporal: the target is pooled across composition renders, and the camera is
     * moved every frame while the user works. Every frame must still be exactly its own picture.
     * Before the fix the colour attachment was never cleared, so frame N contained the silhouettes
     * of frames N-1..0 as well (the "duplicated star/polygon + stripes" screenshot).
     */
    @Test
    public void movingCameraFramesDoNotAccumulateGhosts()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompDocument comp = bugScene();
        CompLayer cameraLayer = comp.getLayer("camera");
        float[] yaws = {-25.73563F, -18F, -10F};
        DepthPlaneCompositor compositor = null;

        try
        {
            for (int frame = 0; frame < yaws.length; frame++)
            {
                cameraLayer.rotationY = yaws[frame];

                CompCamera camera = CompCamera.of(comp, 0F, 0F);

                /* Pooled across renders, exactly like the main composition path. */
                compositor = DepthPlaneCompositor.acquire(W, H);

                int[] painter = paint(comp, camera);
                int[] depth = depth(comp, camera, compositor);

                /* Same 1/255 near-transparent-antialias tolerance as the static frame test. */
                assertFramesEqual(painter, depth, 1, "bug-project-moving-frame-" + frame);

                DepthPlaneCompositor.release(compositor);
                compositor = null;
            }
        }
        finally
        {
            if (compositor != null)
            {
                DepthPlaneCompositor.release(compositor);
            }

            DepthPlaneCompositor.deleteAll();
        }
    }

    /**
     * Minimal root-cause pin, no shape rasterizer involved: a full red frame is composed, the
     * compositor goes back to the pool, and the next composition (a small blue square on a
     * transparent frame) must not contain a single red pixel. Before the fix the reused colour
     * attachment still held the red frame and it was composited again under the blue square.
     */
    @Test
    public void aReusedTargetDoesNotKeepThePreviousCompositionsPixels()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompCamera camera = CompCamera.identity(W, H);
        Matrix4f world = new Matrix4f();
        GlFramebuffer first = solid(0xFFFF0000);
        DepthPlaneCompositor compositor = DepthPlaneCompositor.acquire(W, H);

        try
        {
            GlFramebuffer accumulation = new GlFramebuffer(W, H);

            accumulation.begin(STAGE);
            accumulation = compositor.composite(first, world, camera, accumulation,
                CompLayer.BLEND_NORMAL, 1F);

            int centerRed = accumulation.readTopDown()[W / 2 + H / 2 * W];

            assertEquals(0xFFFF0000, centerRed, "the first composition must render its frame");
            accumulation.delete();
        }
        finally
        {
            first.delete();
            DepthPlaneCompositor.release(compositor);
        }

        GlFramebuffer square = rectangle(0xFF0000FF, W / 2 - 20, H / 2 - 20, W / 2 + 20, H / 2 + 20);

        try
        {
            GlFramebuffer accumulator = new GlFramebuffer(W, H);

            accumulator.begin(STAGE);
            accumulator = compositor.composite(square, world, camera, accumulator,
                CompLayer.BLEND_NORMAL, 1F);

            int[] pixels = accumulator.readTopDown();

            /* Everything outside the small blue square must be the fresh (black) stage. */
            assertEquals(STAGE, pixels[0], "left edge must not retain the previous frame");
            assertEquals(STAGE, pixels[W - 1], "right edge must not retain the previous frame");
            assertEquals(0xFF0000FF, pixels[W / 2 + H / 2 * W], "the square itself must be drawn");
            accumulator.delete();
        }
        finally
        {
            square.delete();
        }
    }

    /**
     * Two non-overlapping translucent layers: per-pixel depth must composite each visible pixel
     * exactly once, matching whole-layer order. Before the fix the second layer's pass re-composited
     * the first layer's stale pixels from the target, doubling their opacity (edge "stripes").
     */
    @Test
    public void translucentLayersAreCompositedExactlyOnce()
    {
        Assumptions.assumeTrue(CompShaders.depthPlane != null && CompShaders.depthPlane.isValid(),
            "depth plane program unavailable");

        CompCamera camera = CompCamera.identity(W, H);
        /* alpha 0x60 < 0.5: translucent, so it must blend instead of writing depth. */
        GlFramebuffer far = rectangle(0x60FF0000, 0, 0, W / 2, H);
        GlFramebuffer near = rectangle(0x6000FF00, W / 2, 0, W, H);
        Matrix4f farWorld = new Matrix4f().translate(0F, 0F, 0F);
        Matrix4f nearWorld = new Matrix4f().translate(0F, 0F, 80F);
        DepthPlaneCompositor compositor = new DepthPlaneCompositor(W, H);

        try
        {
            /* Painter path: far, then near (their painted order here is also far to near). */
            GlFramebuffer painter = new GlFramebuffer(W, H);

            painter.begin(STAGE);
            painter = PassRenderer.draw(far, painter, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);
            painter = PassRenderer.draw(near, painter, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF);

            /* Depth path: same order, one shared compositor. */
            GlFramebuffer depth = new GlFramebuffer(W, H);

            depth.begin(STAGE);
            depth = compositor.composite(far, farWorld, camera, depth, CompLayer.BLEND_NORMAL, 1F);
            depth = compositor.composite(near, nearWorld, camera, depth, CompLayer.BLEND_NORMAL, 1F);

            int[] expected = painter.readTopDown();
            int[] actual = depth.readTopDown();

            assertFramesEqual(expected, actual, 2, "translucent-halves");

            painter.delete();
            depth.delete();
        }
        finally
        {
            far.delete();
            near.delete();
            compositor.delete();
        }
    }
}
