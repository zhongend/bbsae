package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The 3D workspace renderer: a draft view of the composition through {@link CompViewportCamera}.
 *
 * <h2>The one idea</h2>
 * <p>A camera move changes <b>where</b> layers are, never <b>what</b> they contain. So the viewport
 * draws each layer from a cached source texture (see {@link CompLayerSourceCache}) as a projected
 * quad — no per-layer framebuffer, no effect chain, no full graph. Ten layers cost ten textured
 * draws instead of ten full-composition passes; that is the difference between an orbit drag that
 * crawls and one that tracks the mouse.</p>
 *
 * <h2>Perspective without a shader</h2>
 * <p>Each layer plane is subdivided into a small mesh (1–8 cells per axis depending on its screen
 * size) and the two triangles of each cell get the cell's <b>projected</b> corners. Affine UVs
 * within a cell approximate the plane's homography; with the subdivision the error is below a
 * pixel, while the whole layer stays a single batched draw call. A per-pixel ray/plane shader would
 * be exact but would shade the entire viewport once per layer — the opposite of what a weak GPU
 * needs.</p>
 *
 * <h2>Draft contract</h2>
 * <p>Effects, masks, track mattes and blend modes are composite operations and are <b>not</b>
 * applied here; the HUD says "草稿". Looking through the composition camera renders the exact frame
 * through the normal graph, and that is the mode to judge a shot in.</p>
 */
public final class CompViewportRenderer
{
    private static final int GRID_STEP = 120;
    private static final int MAX_CELLS = 8;
    private static final float CELL_PIXELS = 110F;

    private CompViewportRenderer()
    {}

    /**
     * Draws the draft scene into the given screen rectangle.
     *
     * <h2>Why an offscreen pass instead of drawing into the UI</h2>
     * <p>The scene is rendered into its own framebuffer with the compositor's own projection and
     * model-view (exactly like {@link CompRenderer} renders a composition), then blitted once with
     * the UI batcher. Drawing raw GL into the middle of BBS's UI frame meant inheriting whatever
     * matrix/projection/state the panel system had set — the first build did that and the gizmos
     * (batcher) were correct while every quad the scene drew was invisible. An FBO gives one
     * coordinate space that cannot be argued with: 0,0 is the top-left of the viewport.
     * </p>
     *
     * <p>The blit is a single textured quad, so the UI still clips and composites it exactly like
     * the composition preview — one code path for "how a frame reaches the panel".</p>
     *
     * @param screenX left edge of the viewport in screen pixels
     * @param screenY top edge
     */
    public static void draw(Batcher2D batcher, CompProject project, CompDocument doc, CompViewportCamera camera,
                            float time, float screenX, float screenY, int screenWidth, int screenHeight)
    {
        if (batcher == null || doc == null || camera == null || screenWidth <= 0 || screenHeight <= 0)
        {
            return;
        }

        /* Flush the panel's batch BEFORE our framebuffer is bound. The scene pass shares Minecraft's
         * immediate vertex buffer; without this, UI vertices queued earlier in the same frame get
         * flushed while our FBO is current — they are then painted into the 3D texture instead of the
         * screen, which is exactly the "the UI flickers while a video plays" symptom (the flush
         * point moves every frame as content changes). */
        batcher.flush();

        GlFramebuffer scene;

        try
        {
            scene = renderScene(project, doc, camera, time, screenWidth, screenHeight);
        }
        catch (RuntimeException e)
        {
            /* A bad layer must not take the whole editor down: the preview is one panel, and the
             * old composition path catches here for the same reason. */
            dev.bbsae.BbsAeAddon.LOGGER.error("3D viewport render failed", e);

            return;
        }

        if (scene == null)
        {
            return;
        }

        /* Same UV convention as CompRenderer.blitTexture: the FBO is top-down, GL's texture origin
         * is bottom-up, so v starts at the height. The target is persistent, so it is NOT released
         * here — releasing would hand a live texture back to the pool (the scene would flicker as
         * the next layer acquired it mid-frame). */
        batcher.texturedBox(scene.color.id, 0xFFFFFFFF,
            screenX, screenY, scene.width, scene.height,
            0F, scene.height, scene.width, 0F, scene.width, scene.height);
        batcher.flush();
    }

    /* One scene target, reused across frames. Acquiring and releasing a framebuffer every frame
     * pushed the pool to its limit while a video played (each frame has new source content), which
     * showed up as stutter/flicker. */
    private static GlFramebuffer sceneTarget;
    private static int sceneTargetWidth;
    private static int sceneTargetHeight;

    private static GlFramebuffer sceneTarget(int width, int height)
    {
        if (sceneTarget != null && sceneTargetWidth == width && sceneTargetHeight == height)
        {
            return sceneTarget;
        }

        if (sceneTarget != null)
        {
            sceneTarget.delete();
            sceneTarget = null;
        }

        /* Depth attachment: the 3D workspace now resolves layer crossings per pixel instead of
         * ordering whole pictures, which is what "layers penetrate in 3D" looked like. */
        sceneTarget = new GlFramebuffer(width, height, true);
        sceneTargetWidth = width;
        sceneTargetHeight = height;

        return sceneTarget;
    }

    /** Drops the persistent target (called with the rest of the render caches). */
    public static void clearCaches()
    {
        if (sceneTarget != null)
        {
            sceneTarget.delete();
            sceneTarget = null;
        }

        Viewport3DRenderer.delete();
        sceneTargetWidth = 0;
        sceneTargetHeight = 0;
    }

    /** Renders the draft view through the editor camera into its own colour target. */
    private static GlFramebuffer renderScene(CompProject project, CompDocument doc, CompViewportCamera camera,
                                             float time, int width, int height)
    {
        camera.setViewport(width, height);
        camera.update();

        CompRenderContext context = new CompRenderContext(project, doc, time, null, null, null);

        /* Draft view: only this viewport may use the cheap precomp plate. The composition panel
         * preview and the encoder render the real nested composition. */
        context.draftViewport = true;
        GlFramebuffer target = sceneTarget(width, height);

        int previousFramebuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, previousViewport);

        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        MatrixStack modelView = RenderSystem.getModelViewStack();
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        Batcher2D sceneBatcher = new Batcher2D(new net.minecraft.client.gui.DrawContext(client, client.getBufferBuilders().getEntityVertexConsumers()));

        Matrix4f matrix = new Matrix4f();

        modelView.push();
        modelView.peek().getPositionMatrix().identity();
        RenderSystem.applyModelViewMatrix();

        try
        {
            target.begin(0xFF0E1116);
            RenderSystem.setProjectionMatrix(GlStateGuard.ortho(width, height), VertexSorter.BY_Z);

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            drawSolidPass(doc, camera, 0F, 0F, matrix);
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);

            int layers = drawLayers(sceneBatcher, context, doc, camera, 0F, 0F, matrix);

            debugStats(doc, camera, layers, context, 0F, 0F);

            sceneBatcher.flush();

            /* The 3D workspace used to ignore adjustment layers entirely: the draft path draws
             * each layer directly and never runs the composite graph, so a global grade had no
             * effect. Apply every visible adjustment layer to the finished viewport image — the
             * 3D equivalent of "an adjustment layer covers the camera". */
            applyAdjustments(doc, context, target);
        }
        finally
        {
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
            BufferRenderer.resetCurrentVertexBuffer();
            modelView.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorter.BY_Z);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFramebuffer);
            org.lwjgl.opengl.GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }

        return target;
    }

    /**
     * Runs every visible adjustment layer's effects over the finished viewport image.
     *
     * <p>The normal composition graph handles adjustment layers by intercepting the accumulation
     * buffer. The 3D workspace deliberately does not build that graph (it would cost a full
     * composition render per orbit frame), so the adjustment had no effect at all. A post-process
     * on the finished scene is the 3D equivalent: one full-screen pass per effect, and the same
     * effect shaders and parameters as the final render.</p>
     */
    private static void applyAdjustments(CompDocument doc, CompRenderContext context, GlFramebuffer target)
    {
        GlFramebuffer current = target;

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || !"adjustment".equals(layer.type) || !layer.activeAt(context.time))
            {
                continue;
            }

            if (doc.isSoloedOut(layer))
            {
                continue;
            }

            if (layer.effects == null)
            {
                continue;
            }

            for (CompEffect effect : layer.effects)
            {
                if (effect == null || !effect.enabled)
                {
                    continue;
                }

                CompEffect resolved = layer.resolveEffect(effect, context.frame);
                GlFramebuffer output = EffectRenderer.apply(layer, resolved, current, context);

                if (output != current)
                {
                    if (current != target)
                    {
                        FboPool.release(current);
                    }

                    current = output;
                }
            }
        }

        if (current != target)
        {
            /* The caller blits the persistent scene target, so the graded copy goes back into it. */
            target.begin(0);
            PassRenderer.drawInto(target, current, null, null, CompLayer.BLEND_NORMAL, 1F, 0F, 0F, 0xFFFFFF, 0);
            FboPool.release(current);
        }
    }

    /* ---------------------------------------------------------------- passes */

    private static void drawSolidPass(CompDocument doc, CompViewportCamera camera, float screenX, float screenY, Matrix4f matrix)
    {
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        float w = doc.width;
        float h = doc.height;
        int stage = doc.background == 0 ? 0xFF14181F : doc.background;

        solidQuad(buffer, matrix, camera, screenX, screenY,
            0F, 0F, w, 0F, w, h, 0F, h, withAlpha(stage, 1F));

        drawGrid(buffer, matrix, camera, screenX, screenY, doc);

        BufferRenderer.drawWithGlobalProgram(buffer.end());
        BufferRenderer.resetCurrentVertexBuffer();
    }

    private static void drawGrid(BufferBuilder buffer, Matrix4f matrix, CompViewportCamera camera,
                                 float screenX, float screenY, CompDocument doc)
    {
        float extent = Math.max(doc.width, doc.height) * 1.2F + GRID_STEP * 4F;
        int lines = Math.round(extent / GRID_STEP);
        int gridColor = 0x18FFFFFF;
        int axisColor = 0x55FF6B6B;

        for (int i = -lines; i <= lines; i++)
        {
            float value = i * GRID_STEP;
            int color = i == 0 ? axisColor : gridColor;

            line(buffer, matrix, camera, screenX, screenY, value, -extent, value, extent, color);
            line(buffer, matrix, camera, screenX, screenY, -extent, value, extent, value, color);
        }

        /* Composition border, drawn over the grid so the frame is readable. */
        float x1 = 0F;
        float y1 = 0F;
        float x2 = doc.width;
        float y2 = doc.height;
        int border = 0x88FFFFFF;

        line(buffer, matrix, camera, screenX, screenY, x1, y1, x2, y1, border);
        line(buffer, matrix, camera, screenX, screenY, x2, y1, x2, y2, border);
        line(buffer, matrix, camera, screenX, screenY, x2, y2, x1, y2, border);
        line(buffer, matrix, camera, screenX, screenY, x1, y2, x1, y1, border);
    }

    private static long lastDebugLog;

    /** `-Dbbsae.debugViewport=true` prints one line per second: the proof that the draft path runs. */
    private static void debugStats(CompDocument doc, CompViewportCamera camera, int layers, CompRenderContext context,
                                   float screenX, float screenY)
    {
        if (!Boolean.getBoolean("bbsae.debugViewport"))
        {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastDebugLog < 1000L)
        {
            return;
        }

        lastDebugLog = now;

        /* The first layer's projected rectangle: if a layer is invisible, this says whether the
         * geometry is on screen (a drawing bug) or off screen (a camera/pose bug). */
        StringBuilder quad = new StringBuilder();

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || "camera".equals(layer.type) || "null".equals(layer.type))
            {
                continue;
            }

            float[] corners = layerQuad(context, layer, camera, screenX, screenY);

            if (corners == null)
            {
                quad.append(" offscreen");
            }
            else
            {
                quad.append(String.format(java.util.Locale.ROOT, " [%.0f,%.0f %.0f,%.0f]",
                    corners[0], corners[1], corners[4], corners[5]));
            }

            break;
        }

        dev.bbsae.BbsAeAddon.LOGGER.info("viewport: {} layers, comp {}x{}, yaw {} pitch {} dist {} {}{}",
            layers, doc.width, doc.height,
            Math.round(camera.yaw), Math.round(camera.pitch), Math.round(camera.distance),
            camera.ortho ? "ortho" : "persp", quad);
    }

    /**
     * Painter order for the 3D workspace: far to near, sorted by the plane depth measured from the
     * workspace camera, with the layer stack as the tie-break for coplanar layers.
     *
     * <p>Extracted so the camera dependence is testable without a GL context: sorting against the
     * composition camera instead of the orbit camera was the "layers penetrate while orbiting"
     * defect.</p>
     */
    static void sortByView(CompRenderContext context, java.util.List<CompLayer> layers,
                           org.joml.Vector3f eye, org.joml.Vector3f forward)
    {
        final java.util.Map<CompLayer, Float> depths = new java.util.IdentityHashMap<>();
        final java.util.Map<CompLayer, Integer> stack = new java.util.IdentityHashMap<>();

        for (int i = 0; i < layers.size(); i++)
        {
            stack.put(layers.get(i), i);
            depths.put(layers.get(i), CompScene.planeDepth(CompScene.world(context, layers.get(i)), eye, forward));
        }

        layers.sort((a, b) ->
        {
            int byDepth = Float.compare(depths.get(a), depths.get(b));

            return byDepth != 0 ? byDepth : Integer.compare(stack.get(a), stack.get(b));
        });
    }

    /**
     * AE ordering for the 3D workspace: only consecutive runs of layers the camera affects are
     * depth-sorted; every other layer keeps its stack slot and breaks the run.
     *
     * <p>In AE logic a 2D layer is screen-fixed — it has no depth to sort by and must stay exactly
     * where the stack put it. Sorting it with the 3D planes is what made a 2D background jump in
     * front of a 3D layer while orbiting. Legacy logic keeps the old "every content layer sorts"
     * rule, so old projects look unchanged.</p>
     */
    static void sortByViewRuns(CompRenderContext context, java.util.List<CompLayer> layers,
                               org.joml.Vector3f eye, org.joml.Vector3f forward)
    {
        int start = 0;

        for (int i = 0; i <= layers.size(); i++)
        {
            boolean boundary = i == layers.size() || !CompScene.cameraAffects(context, layers.get(i));

            if (!boundary)
            {
                continue;
            }

            if (i - start > 1)
            {
                sortByView(context, layers.subList(start, i), eye, forward);
            }

            start = i + 1;
        }
    }

    /** Visible content layers in stack order, with AE depth runs sorted for the orbit camera. */
    static List<CompLayer> viewportOrder(CompRenderContext context, CompDocument doc,
                                         CompViewportCamera camera)
    {
        List<CompLayer> order = new ArrayList<>();

        for (CompLayer layer : doc.layers)
        {
            if (layer == null || !layer.visible || !layer.activeAt(context.time))
            {
                continue;
            }

            /* Solo is a property of the composition, not of one view: showing every layer in the
             * 3D workspace while the 2D preview/export show one made the picture flip when the user
             * switched to 看穿. */
            if (doc.isSoloedOut(layer))
            {
                continue;
            }

            if (!CompScene.hasPixels(layer))
            {
                continue;
            }

            order.add(layer);
        }

        sortByViewRuns(context, order, camera.position, camera.forward);

        return order;
    }

    /**
     * Contain-fit of the composition rectangle into the viewport, identical to the composition
     * panel's preview fit: no orbit perspective, no camera zoom/pan.
     *
     * @return {scale, offsetX, offsetY}
     */
    static float[] viewportFit(CompRenderContext context, CompViewportCamera camera)
    {
        float viewportWidth = Math.max(1F, camera.width);
        float viewportHeight = Math.max(1F, camera.height);
        float compWidth = Math.max(1F, context.width);
        float compHeight = Math.max(1F, context.height);
        float scale = Math.min(viewportWidth / compWidth, viewportHeight / compHeight);

        return new float[] {
            scale,
            (viewportWidth - compWidth * scale) * 0.5F,
            (viewportHeight - compHeight * scale) * 0.5F
        };
    }

    /**
     * A screen-fixed layer's four content corners in viewport pixels (TL, TR, BR, BL).
     *
     * <p>The composition rectangle is fitted into the viewport exactly like the preview, then the
     * layer's world matrix is applied <b>in composition space</b> — the orbit camera never enters
     * the mapping. That is what "a 2D layer does not turn in the 3D workspace" means in AE.</p>
     */
    static float[] screenFixedQuad(CompRenderContext context, CompViewportCamera camera, Matrix4f world,
                                   float x0, float y0, float x1, float y1,
                                   float screenX, float screenY)
    {
        float[] fit = viewportFit(context, camera);
        float[] corners = new float[8];

        for (int i = 0; i < 4; i++)
        {
            float lx = (i == 0 || i == 3) ? x0 : x1;
            float ly = (i == 0 || i == 1) ? y0 : y1;
            Vector3f point = world.transformPosition(new Vector3f(lx, ly, 0F));

            corners[i * 2] = screenX + fit[1] + point.x * fit[0];
            corners[i * 2 + 1] = screenY + fit[2] + point.y * fit[0];
        }

        return corners;
    }

    private static int drawLayers(Batcher2D batcher, CompRenderContext context, CompDocument doc,
                                   CompViewportCamera camera, float screenX, float screenY, Matrix4f matrix)
    {
        /* Painter's algorithm, farthest first — but only inside runs of camera-affected layers.
         * A 2D layer keeps its stack slot and never sorts with the 3D planes (see
         * {@link #sortByViewRuns}). The key is the depth of the layer's *plane*: all coplanar
         * layers tie and fall back to stack order (which is what the composition does), while a
         * layer pushed towards the viewer on z sorts in front of them from any angle. */
        List<CompLayer> order = viewportOrder(context, doc, camera);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        int drawn = 0;

        for (CompLayer layer : order)
        {
            CompLayerSourceCache.Entry source = CompLayerSourceCache.get(layer, context, batcher);

            if (source == null)
            {
                continue;
            }

            float opacity = Math.max(0F, Math.min(1F, layer.opacityFrame(context.frame)));

            if (opacity <= 0.001F)
            {
                continue;
            }

            Matrix4f world = CompScene.world(context, layer);

            /* AE: a 2D layer is screen-fixed. Draw it in composition pixels through the preview
             * fit, not through the orbit camera: it must not foreshorten, disappear behind the
             * camera, or change size while the user orbits. */
            if (!CompScene.cameraAffects(context, layer))
            {
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);

                float[] corners = screenFixedQuad(context, camera, world,
                    source.localX, source.localY,
                    source.localX + source.localWidth, source.localY + source.localHeight,
                    screenX, screenY);

                emitScreenQuad(buffer, matrix, corners, source, withAlpha(0xFFFFFF, opacity));
                drawn++;

                continue;
            }

            /* 3D layers are depth-tested quads: the GPU interpolates the plane's true depth, so a
             * layer crossing another one is resolved per pixel instead of by whole-layer order. */
            if (layer.threeD && Viewport3DRenderer.available())
            {
                int extruded = drawExtruded3D(layer, context, camera, source, world, opacity);

                if (extruded > 0)
                {
                    drawn += extruded;

                    continue;
                }

                int copies = drawReplicated3D(layer, context, camera, source, world, opacity);

                if (copies > 0)
                {
                    drawn += copies;

                    continue;
                }

                Viewport3DRenderer.draw(source.texture.color, world, camera.viewProjection,
                    source.localX, source.localY, source.localX + source.localWidth,
                    source.localY + source.localHeight, opacity);
                drawn++;

                continue;
            }

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            float[] uv = screenSize(camera, world, source, screenX, screenY);
            int cells = Math.max(1, Math.min(MAX_CELLS, Math.round(uv[0] / CELL_PIXELS)));

            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            RenderSystem.setShaderTexture(0, source.texture.color.id);
            buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
            emitLayer(buffer, matrix, camera, screenX, screenY, world, source, cells, withAlpha(0xFFFFFF, opacity));

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            BufferRenderer.resetCurrentVertexBuffer();

            drawn++;
        }

        return drawn;
    }

    /**
     * Draws a replicator as N real 3D quads in the viewport.
     *
     * <p>The normal composition path applies a repeat after projection, so its copies are
     * composition-space images. The viewport used to draw exactly one quad, which is why the 3D
     * workspace showed the effect as "limited to the layer box" (or not at all). Copy placement
     * is computed in layer-local pixels here; each copy is a separate depth-tested plane, so it
     * extends outside the original rectangle and moves correctly when the camera orbits.</p>
     *
     * @return number of copies drawn, or 0 when the layer has no leading replicator.
     */
    private static int drawReplicated3D(CompLayer layer, CompRenderContext context, CompViewportCamera camera,
                                        CompLayerSourceCache.Entry source, Matrix4f world, float opacity)
    {
        if (layer.effects == null || layer.effects.isEmpty())
        {
            return 0;
        }

        int first = CompRenderer.firstProjectedEffect(layer.effects);

        if (first < 0 || first >= layer.effects.size())
        {
            return 0;
        }

        CompEffect effect = layer.effects.get(first);

        if (effect == null || !effect.enabled || !EffectRenderer.isReplication(effect.type))
        {
            return 0;
        }

        CompEffect resolved = layer.resolveEffect(effect, context.frame);
        String type = resolved.type;
        float cx = source.localX + source.localWidth * 0.5F;
        float cy = source.localY + source.localHeight * 0.5F;
        int count = Math.max(1, Math.min(48, Math.round(resolved.valueF("count", 5F))));
        float alpha = Math.max(0F, Math.min(1F, resolved.valueF("alpha", resolved.valueF("amount", 1F))));

        if (alpha <= 0.001F)
        {
            return 0;
        }

        int drawn = 0;

        if ("am_repeat".equals(type))
        {
            float ox = resolved.valueF("offset_x", 48F);
            float oy = resolved.valueF("offset_y", 0F);
            float angle = (float) Math.toRadians(resolved.valueF("angle", 0F));
            float scale = Math.max(0.01F, resolved.valueF("scale", 1F));

            for (int i = 0; i < count; i++)
            {
                float k = i - (count - 1) * 0.5F;
                Matrix4f local = copyTransform(cx + ox * k, cy + oy * k, angle * i,
                    (float) Math.pow(scale, i), cx, cy);

                drawCopy(source, world, local, camera, opacity * alpha);
                drawn++;
            }
        }
        else if ("am_repeat_radial".equals(type))
        {
            float radius = Math.max(0F, resolved.valueF("radius", 300F));
            float start = (float) Math.toRadians(resolved.valueF("orientation", 0F)
                + resolved.valueF("start_angle", 0F));
            float sweep = (float) Math.toRadians(resolved.valueF("sweep", 360F));
            float angleStep = (float) Math.toRadians(resolved.valueF("angle", 0F));
            float base = Math.max(0.01F, resolved.valueF("base_scale", 1F));
            float scale = Math.max(0.01F, resolved.valueF("scale", 1F));
            float ox = resolved.valueF("offset_x", 0F);
            float oy = resolved.valueF("offset_y", 0F);

            for (int i = 0; i < count; i++)
            {
                float t = count <= 1 ? 0F : i / (float) (count - 1);
                float angle = start + sweep * t;

                Matrix4f local = copyTransform(cx + (float) Math.cos(angle) * radius + ox,
                    cy + (float) Math.sin(angle) * radius + oy, angleStep * i,
                    base * (float) Math.pow(scale, i), cx, cy);

                drawCopy(source, world, local, camera, opacity * alpha);
                drawn++;
            }
        }
        else if ("am_repeat_scatter".equals(type))
        {
            float radius = Math.max(0F, resolved.valueF("radius", 300F));
            float seed = resolved.valueF("scatter_seed", 0F) + resolved.valueF("evolution", 0F) * 0.37F;
            float angleStep = (float) Math.toRadians(resolved.valueF("angle", 0F));
            float scale = Math.max(0.01F, resolved.valueF("scale", 1F));
            float ox = resolved.valueF("offset_x", 0F);
            float oy = resolved.valueF("offset_y", 0F);

            for (int i = 0; i < count; i++)
            {
                float a = hash01(seed, i * 2 + 1) * 6.2831853F;
                float r = (float) Math.sqrt(hash01(seed, i * 2 + 2)) * radius;

                Matrix4f local = copyTransform(cx + (float) Math.cos(a) * r + ox,
                    cy + (float) Math.sin(a) * r + oy, angleStep * i,
                    (float) Math.pow(scale, i), cx, cy);

                drawCopy(source, world, local, camera, opacity * alpha);
                drawn++;
            }
        }
        else if ("am_grid_repeat".equals(type))
        {
            int columns = Math.max(1, Math.min(8, Math.round(resolved.valueF("columns", 2F))));
            int rows = Math.max(1, Math.min(8, Math.round(resolved.valueF("rows", 2F))));
            float gap = Math.max(0F, Math.min(0.9F, resolved.valueF("gap", 0.05F)));
            float cellW = context.width / (float) columns;
            float cellH = context.height / (float) rows;

            for (int j = 0; j < rows; j++)
            {
                for (int i = 0; i < columns; i++)
                {
                    /* The composition shader tiles across the whole frame, not across the layer
                     * rectangle. Place the workspace copies on that same composition grid so the
                     * viewport does not look like a clipped "repeat inside a box". */
                    float px = (i + 0.5F) * cellW - layer.x;
                    float py = (j + 0.5F) * cellH - layer.y;
                    Matrix4f local = copyTransform(px - cx, py - cy, 0F,
                        Math.max(0.001F, 1F - gap), cx, cy);

                    drawCopy(source, world, local, camera, opacity * alpha);
                    drawn++;
                }
            }
        }
        else if ("am_line_repeat".equals(type))
        {
            int vertical = Math.round(resolved.valueF("vertical", 1F));
            float duty = Math.max(0.01F, Math.min(1F, resolved.valueF("duty", 0.7F)));

            for (int i = 0; i < count; i++)
            {
                float t = i - (count - 1) * 0.5F;
                float dx = vertical == 0 ? t * context.width / count : 0F;
                float dy = vertical != 0 ? t * context.height / count : 0F;
                Matrix4f local = copyTransform(cx + dx, cy + dy, 0F, duty, cx, cy);

                drawCopy(source, world, local, camera, opacity * alpha);
                drawn++;
            }
        }
        else
        {
            return 0;
        }

        return drawn;
    }

    /**
     * Draws the layer raster as a depth stack for the {@code extrude3d} effect. Each slice is a
     * real 3D plane at a different local Z, so the workspace shows the volume from the side; the
     * front cap is drawn last.
     */
    private static int drawExtruded3D(CompLayer layer, CompRenderContext context, CompViewportCamera camera,
                                      CompLayerSourceCache.Entry source, Matrix4f world, float opacity)
    {
        if (layer.effects == null || layer.effects.isEmpty())
        {
            return 0;
        }

        CompEffect extrusion = null;

        for (CompEffect effect : layer.effects)
        {
            if (effect != null && effect.enabled && "extrude3d".equals(effect.type))
            {
                extrusion = effect;

                break;
            }
        }

        if (extrusion == null)
        {
            return 0;
        }

        extrusion = layer.resolveEffect(extrusion, context.frame);

        float depth = Math.max(0F, Math.min(400F, extrusion.valueF("depth", 60F)));
        int slices = Math.max(1, Math.min(48, Math.round(extrusion.valueF("slices", 16F))));

        if (depth <= 0.5F)
        {
            return 0;
        }

        for (int slice = slices; slice >= 0; slice--)
        {
            float z = -depth * (slice / (float) slices);
            Matrix4f copyWorld = new Matrix4f(world).translate(0F, 0F, z);

            Viewport3DRenderer.draw(source.texture.color, copyWorld, camera.viewProjection,
                source.localX, source.localY, source.localX + source.localWidth,
                source.localY + source.localHeight, opacity);
        }

        return slices + 1;
    }

    private static Matrix4f copyTransform(float x, float y, float rotation, float scale, float cx, float cy)
    {
        return new Matrix4f()
            .translate(cx + x, cy + y, 0F)
            .rotateZ(rotation)
            .scale(scale, scale, 1F)
            .translate(-cx, -cy, 0F);
    }

    private static void drawCopy(CompLayerSourceCache.Entry source, Matrix4f world, Matrix4f local,
                                 CompViewportCamera camera, float opacity)
    {
        Matrix4f copyWorld = new Matrix4f(world).mul(local);

        Viewport3DRenderer.draw(source.texture.color, copyWorld, camera.viewProjection,
            source.localX, source.localY, source.localX + source.localWidth,
            source.localY + source.localHeight, opacity);
    }

    /** Stable 0..1 hash for the scatter replicator (no java.util.Random allocation per frame). */
    private static float hash01(float seed, int i)
    {
        int x = Float.floatToIntBits(seed) ^ (i * 0x9E3779B9);
        x ^= x >>> 16;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        x *= 0xC2B2AE35;
        x ^= x >>> 16;

        return (x & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    /** Emits a screen-fixed layer quad; UVs match the layer-local raster (top row is v = 1). */
    private static void emitScreenQuad(BufferBuilder buffer, Matrix4f matrix, float[] quad,
                                       CompLayerSourceCache.Entry source, int color)
    {
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, source.texture.color.id);
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);

        buffer.vertex(matrix, quad[0], quad[1], 0F).texture(0F, 1F).color(color).next();
        buffer.vertex(matrix, quad[2], quad[3], 0F).texture(1F, 1F).color(color).next();
        buffer.vertex(matrix, quad[4], quad[5], 0F).texture(1F, 0F).color(color).next();

        buffer.vertex(matrix, quad[0], quad[1], 0F).texture(0F, 1F).color(color).next();
        buffer.vertex(matrix, quad[4], quad[5], 0F).texture(1F, 0F).color(color).next();
        buffer.vertex(matrix, quad[6], quad[7], 0F).texture(0F, 0F).color(color).next();

        BufferRenderer.drawWithGlobalProgram(buffer.end());
        BufferRenderer.resetCurrentVertexBuffer();
    }

    /** Approximate on-screen size of a layer's projected diagonal. */
    private static float[] screenSize(CompViewportCamera camera, Matrix4f world, CompLayerSourceCache.Entry source,
                                      float screenX, float screenY)
    {
        Vector2f a = projectWorld(camera, world, source.localX, source.localY, screenX, screenY);
        Vector2f b = projectWorld(camera, world, source.localX + source.localWidth, source.localY + source.localHeight, screenX, screenY);

        return new float[] {a == null || b == null ? 0F : a.distance(b)};
    }

    /**
     * Emits one layer as a subdivided projected mesh. Cells are sized from the layer's screen
     * extent, so a small layer is 2 triangles and a full-screen tilted plane is an 8×8 grid.
     */
    private static void emitLayer(BufferBuilder buffer, Matrix4f matrix, CompViewportCamera camera,
                                  float screenX, float screenY, Matrix4f world, CompLayerSourceCache.Entry source,
                                  int cells, int color)
    {
        float x0 = source.localX;
        float y0 = source.localY;
        float x1 = source.localX + source.localWidth;
        float y1 = source.localY + source.localHeight;
        float stepX = (x1 - x0) / cells;
        float stepY = (y1 - y0) / cells;
        float width = Math.max(0.0001F, source.localWidth);
        float height = Math.max(0.0001F, source.localHeight);

        for (int j = 0; j < cells; j++)
        {
            for (int i = 0; i < cells; i++)
            {
                float lx0 = x0 + i * stepX;
                float ly0 = y0 + j * stepY;
                float lx1 = lx0 + stepX;
                float ly1 = ly0 + stepY;

                Vector2f p00 = projectWorld(camera, world, lx0, ly0, screenX, screenY);
                Vector2f p10 = projectWorld(camera, world, lx1, ly0, screenX, screenY);
                Vector2f p11 = projectWorld(camera, world, lx1, ly1, screenX, screenY);
                Vector2f p01 = projectWorld(camera, world, lx0, ly1, screenX, screenY);

                if (p00 == null || p10 == null || p11 == null || p01 == null)
                {
                    continue;
                }

                /* V is flipped: the source was rasterized with a y-down ortho, so local y0 is the
                 * top of the texture (v = 1). */
                float u0 = (lx0 - x0) / width;
                float u1 = (lx1 - x0) / width;
                float v0 = 1F - (ly0 - y0) / height;
                float v1 = 1F - (ly1 - y0) / height;

                buffer.vertex(matrix, p00.x, p00.y, 0F).texture(u0, v0).color(color).next();
                buffer.vertex(matrix, p10.x, p10.y, 0F).texture(u1, v0).color(color).next();
                buffer.vertex(matrix, p11.x, p11.y, 0F).texture(u1, v1).color(color).next();

                buffer.vertex(matrix, p00.x, p00.y, 0F).texture(u0, v0).color(color).next();
                buffer.vertex(matrix, p11.x, p11.y, 0F).texture(u1, v1).color(color).next();
                buffer.vertex(matrix, p01.x, p01.y, 0F).texture(u0, v1).color(color).next();
            }
        }
    }

    private static void solidQuad(BufferBuilder buffer, Matrix4f matrix, CompViewportCamera camera,
                                  float screenX, float screenY,
                                  float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                                  int color)
    {
        Vector2f a = project(camera, x0, y0, 0F, screenX, screenY);
        Vector2f b = project(camera, x1, y1, 0F, screenX, screenY);
        Vector2f c = project(camera, x2, y2, 0F, screenX, screenY);
        Vector2f d = project(camera, x3, y3, 0F, screenX, screenY);

        if (a == null || b == null || c == null || d == null)
        {
            return;
        }

        buffer.vertex(matrix, a.x, a.y, 0F).color(color).next();
        buffer.vertex(matrix, b.x, b.y, 0F).color(color).next();
        buffer.vertex(matrix, c.x, c.y, 0F).color(color).next();

        buffer.vertex(matrix, a.x, a.y, 0F).color(color).next();
        buffer.vertex(matrix, c.x, c.y, 0F).color(color).next();
        buffer.vertex(matrix, d.x, d.y, 0F).color(color).next();
    }

    /** A one-pixel world-space line, emitted as a screen-space quad. */
    private static void line(BufferBuilder buffer, Matrix4f matrix, CompViewportCamera camera,
                             float screenX, float screenY, float x0, float y0, float x1, float y1, int color)
    {
        Vector2f a = project(camera, x0, y0, 0F, screenX, screenY);
        Vector2f b = project(camera, x1, y1, 0F, screenX, screenY);

        if (a == null || b == null)
        {
            return;
        }

        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 0.01F)
        {
            return;
        }

        float half = length > 300F ? 0.75F : 0.5F;
        float nx = -dy / length * half;
        float ny = dx / length * half;

        buffer.vertex(matrix, a.x + nx, a.y + ny, 0F).color(color).next();
        buffer.vertex(matrix, b.x + nx, b.y + ny, 0F).color(color).next();
        buffer.vertex(matrix, b.x - nx, b.y - ny, 0F).color(color).next();

        buffer.vertex(matrix, a.x + nx, a.y + ny, 0F).color(color).next();
        buffer.vertex(matrix, b.x - nx, b.y - ny, 0F).color(color).next();
        buffer.vertex(matrix, a.x - nx, a.y - ny, 0F).color(color).next();
    }

    /* -------------------------------------------------------------- helpers */

    /** Layer-local point → screen pixels. The camera projects to viewport pixels; the viewport's
     * screen offset is added afterwards, never to the world point. */
    private static Vector2f projectWorld(CompViewportCamera camera, Matrix4f world, float x, float y,
                                         float screenX, float screenY)
    {
        Vector3f point = new Vector3f(x, y, 0F);

        world.transformPosition(point);

        Vector2f projected = camera.project(point);

        return projected == null ? null : projected.add(screenX, screenY);
    }

    private static Vector2f project(CompViewportCamera camera, float x, float y, float z,
                                    float screenX, float screenY)
    {
        Vector2f projected = camera.project(new Vector3f(x, y, z));

        return projected == null ? null : projected.add(screenX, screenY);
    }

    public static int withAlpha(int rgb, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255F)));

        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /* ------------------------------------------------------------- geometry */

    /**
     * The layer's four content corners in screen pixels (TL, TR, BR, BL), or null when the layer is
     * behind the camera. Shared by the selection overlay and the hit test, so what is drawn and what
     * is clickable can never disagree.
     *
     * <p>AE 2D layers are screen-fixed: their quad comes from the viewport fit of the composition
     * rectangle, not from the orbit camera — exactly like {@link #drawLayers} draws them.</p>
     */
    public static float[] layerQuad(CompRenderContext context, CompLayer layer, CompViewportCamera camera,
                                    float screenX, float screenY)
    {
        float width = Math.max(1F, layer.width);
        float height = Math.max(1F, layer.height);
        float x = -width * layer.anchorX;
        float y = -height * layer.anchorY;
        Matrix4f world = CompScene.world(context, layer);

        if (!CompScene.cameraAffects(context, layer))
        {
            return screenFixedQuad(context, camera, world, x, y, x + width, y + height, screenX, screenY);
        }

        float[] corners = new float[8];

        for (int i = 0; i < 4; i++)
        {
            float lx = (i == 0 || i == 3) ? x : x + width;
            float ly = (i == 0 || i == 1) ? y : y + height;
            Vector2f point = projectWorld(camera, world, lx, ly, screenX, screenY);

            if (point == null)
            {
                return null;
            }

            corners[i * 2] = point.x;
            corners[i * 2 + 1] = point.y;
        }

        return corners;
    }

    /**
     * Topmost layer under the cursor, in the exact order the workspace paints.
     *
     * <p>The list is sorted once with the same run rules as {@link #drawLayers}, then walked from
     * the last painted layer backwards: the first hit is the one on top. Screen-fixed 2D layers are
     * tested with their fit quad, so clicking a 2D layer cannot miss because the orbit camera
     * happens to point somewhere else. {@link #pick} is kept as a compatibility alias.</p>
     */
    public static CompLayer layerAt(CompRenderContext context, CompViewportCamera camera, float screenX, float screenY,
                                    float mouseX, float mouseY)
    {
        List<CompLayer> candidates = new ArrayList<>();

        for (CompLayer layer : context.comp.layers)
        {
            if (layer == null || !layer.visible || !layer.activeAt(context.time) || "camera".equals(layer.type))
            {
                continue;
            }

            /* Picking a soloed-out layer let the user drag something they cannot see. */
            if (context.comp.isSoloedOut(layer))
            {
                continue;
            }

            candidates.add(layer);
        }

        sortByViewRuns(context, candidates, camera.position, camera.forward);

        for (int index = candidates.size() - 1; index >= 0; index--)
        {
            CompLayer layer = candidates.get(index);

            if ("null".equals(layer.type))
            {
                Vector3f origin = new Vector3f();

                CompScene.world(context, layer).transformPosition(origin);

                Vector2f point = camera.project(origin);

                if (point != null && Math.abs(point.x + screenX - mouseX) <= 10F && Math.abs(point.y + screenY - mouseY) <= 10F)
                {
                    return layer;
                }

                continue;
            }

            float[] quad = layerQuad(context, layer, camera, screenX, screenY);

            if (quad != null && insideQuad(mouseX, mouseY, quad))
            {
                return layer;
            }
        }

        return null;
    }

    /** @deprecated use {@link #layerAt} — kept for the existing editor call sites. */
    @Deprecated
    public static CompLayer pick(CompRenderContext context, CompViewportCamera camera, float screenX, float screenY,
                                 float mouseX, float mouseY)
    {
        return layerAt(context, camera, screenX, screenY, mouseX, mouseY);
    }

    private static boolean insideQuad(float x, float y, float[] corners)
    {
        boolean inside = false;

        for (int i = 0, j = 3; i < 4; j = i++)
        {
            float xi = corners[i * 2];
            float yi = corners[i * 2 + 1];
            float xj = corners[j * 2];
            float yj = corners[j * 2 + 1];

            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
            {
                inside = !inside;
            }
        }

        return inside;
    }
}
