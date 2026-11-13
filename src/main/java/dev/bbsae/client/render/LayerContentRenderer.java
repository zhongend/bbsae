package dev.bbsae.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompShape;
import dev.bbsae.comp.CompTextAnimator;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Draws one layer's content into a transparent composition-sized target. */
final class LayerContentRenderer
{
    private LayerContentRenderer()
    {}

    static void draw(CompLayer layer, CompRenderContext context, Batcher2D batcher, Matrix4f world)
    {
        draw(layer, context, batcher, world, null);
    }

    /**
     * @param projection null for the normal 2D ortho; a perspective matrix for a 3D layer.
     */
    static void draw(CompLayer layer, CompRenderContext context, Batcher2D batcher, Matrix4f world, Matrix4f projection)
    {
        MatrixStack stack = batcher.getContext().getMatrices();

        stack.push();
        stack.peek().getPositionMatrix().set(world);
        RenderSystem.setProjectionMatrix(projection == null
            ? GlStateGuard.ortho(context.width, context.height)
            : projection, com.mojang.blaze3d.systems.VertexSorter.BY_Z);

        String type = layer.type == null ? "solid" : layer.type;

        switch (type)
        {
            case "text":
                drawText(layer, context, batcher);
                break;
            case "image":
            case "footage":
                drawImage(layer, context, batcher);
                break;
            case "shape":
                drawShapes(layer, context, batcher);
                break;
            case "bbs":
                drawBbsPreview(layer, context, batcher);
                break;
            case "replay":
                drawReplayEntity(layer, context, batcher, world, projection);
                break;
            case "precomp":
                drawPrecomp(layer, context, batcher);
                break;
            case "bar":
                drawBar(layer, context, batcher);
                break;
            case "panel":
                drawSolid(layer, batcher, true);
                break;
            case "null":
            case "adjustment":
            case "camera":
                break;
            case "rect":
            case "solid":
            default:
                drawSolid(layer, batcher, false);
                break;
        }

        stack.pop();
    }

    private static void drawSolid(CompLayer layer, Batcher2D batcher, boolean shadow)
    {
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;

        if (shadow)
        {
            batcher.box(x - 2F, y - 2F, x + layer.width + 2F, y + layer.height + 2F, withAlpha(0xFF000000, 0.4F));
        }

        batcher.box(x, y, x + layer.width, y + layer.height, layer.color);
    }

    private static void drawBar(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;
        float fill = layer.fill >= 0F ? layer.fill : (float) ((Math.sin(context.time * 1.6F) + 1F) * 0.5F);

        fill = Math.max(0F, Math.min(1F, fill));

        batcher.box(x, y, x + layer.width, y + layer.height, withAlpha(0xFF0A0C10, 0.72F));
        batcher.box(x, y, x + layer.width * fill, y + layer.height, layer.color);
        batcher.box(x, y, x + layer.width, y + 1F, withAlpha(0xFFFFFFFF, 0.25F));
    }

    private static void drawImage(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        String path = layer.imagePath;

        if ("footage".equals(layer.type) && path != null && path.matches(".*%0?\\d*d.*"))
        {
            float sourceTime = layer.sourceTimeAt(context.time, context.frame);
            int index = Math.max(0, Math.round(sourceTime * Math.max(1F, context.comp.fps)));

            try
            {
                path = String.format(path, index);
            }
            catch (IllegalArgumentException ignored)
            {
                path = layer.imagePath;
            }
        }

        ImageTextureCache.Entry image = ImageTextureCache.get(context.project.id, path);

        if (image == null)
        {
            /* Missing assets are visible, not silent: a dashed magenta placeholder would be
             * harder to miss than an absent layer. */
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.box(x, y, x + layer.width, y + layer.height, 0x33FF00FF);
            batcher.box(x, y, x + layer.width, y + 2F, 0xFFFF00FF);
            return;
        }

        float scale;

        if (layer.imageFit == 2)
        {
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            applyTextureFilter(layer, context, image, layer.width, layer.height);
            batcher.texturedBox(image.texture.id, layer.color, x, y, layer.width, layer.height, 0F, 0F, image.width, image.height, image.width, image.height);

            return;
        }

        if (layer.imageFit == 1)
        {
            scale = Math.max(layer.width / image.width, layer.height / image.height);
        }
        else
        {
            scale = Math.min(layer.width / image.width, layer.height / image.height);
        }

        float w = image.width * scale;
        float h = image.height * scale;
        float x = -w * layer.anchorX;
        float y = -h * layer.anchorY;

        applyTextureFilter(layer, context, image, w, h);
        batcher.texturedBox(image.texture.id, layer.color, x, y, w, h, 0F, 0F, image.width, image.height, image.width, image.height);
    }

    /**
     * Applies the layer/project texture filter immediately before its draw.
     *
     * <p>OpenGL filtering is texture state, not draw state, and the cache shares textures between
     * layers. Setting it here means a low-resolution icon and a 4K photo can use different filters
     * in the same frame without cloning either texture.</p>
     */
    private static void applyTextureFilter(CompLayer layer, CompRenderContext context, ImageTextureCache.Entry image,
                                           float targetWidth, float targetHeight)
    {
        int mode = TextureFilter.resolveMode(layer, context == null ? null : context.project);
        boolean linear = TextureFilter.isLinear(mode, image.width, image.height, targetWidth, targetHeight);

        image.texture.setLinear(linear);
    }

    private static void drawShapes(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        ShapeCache.Raster raster = ShapeCache.get(layer, context.frame,
            MaskCache.cacheScope(context.project, context.comp));

        if (raster == null)
        {
            return;
        }

        batcher.texturedBox(raster.texture.id, 0xFFFFFFFF, raster.originX, raster.originY, raster.width, raster.height, 0F, 0F, raster.width, raster.height, raster.width, raster.height);
    }

    /**
     * The original BBS film/world frame, as a first-class layer.
     *
     * <p>BBS keeps the last rendered film frame in {@link BBSRendering#getTexture()}; the film
     * editor's own preview samples exactly that texture. A compositor "BBS preview" layer can
     * therefore show the world under (or above) the composition without re-rendering the world.
     * Because it is an ordinary layer it can be moved, trimmed, masked, blended and animated like
     * any other source.</p>
     */
    private static void drawBbsPreview(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        /* The BBS preview layer is a real layer, not an editor-only decoration. It used to be
         * skipped outside the compositor panel ("the world is already under the composition"),
         * which made it vanish from the film-editor preview and from the F6 export while the
         * compositor still showed it. Instead, the overlay captures the clean world frame at
         * WorldRenderEvents.LAST into SceneColor and this layer samples that copy — exactly the
         * same source the compositor preview uses. */
        boolean active = dev.bbsae.client.overlay.BbsPreviewBridge.isActive();
        boolean captured = dev.bbsae.client.overlay.BbsPreviewBridge.hasCapturedWorld();

        if (!active && !captured)
        {
            return;
        }

        dev.bbsae.client.render.GlTexture texture = dev.bbsae.client.overlay.BbsPreviewBridge.texture();

        if (texture == null)
        {
            /* Active but no frame yet: draw an unmistakable placeholder instead of a transparent
             * rectangle that reads as "the layer does nothing". The placeholder is editor chrome:
             * the encoder must never bake the blue plate or the waiting text into the video, and an
             * AE preview of a frame the world never captured renders it transparent instead. */
            if (!active || !context.preview)
            {
                return;
            }

            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.box(x, y, x + layer.width, y + layer.height, 0x3300AAFF);
            batcher.box(x, y, x + layer.width, y + 2F, 0xFF55AAFF);
            batcher.box(x, y + layer.height - 2F, x + layer.width, y + layer.height, 0xFF55AAFF);
            batcher.textShadow("BBS 预览：等待世界帧…", x + 10F, y + 10F, 0xFFFFD27A);
            return;
        }

        drawWorldPlate(layer, batcher, texture);
    }

    /**
     * The depth-keyed replay entity layer.
     *
     * <p>Two things happen here and nowhere else: the world frame is sampled through a matte that
     * was produced by a depth-only pass of the selected replay entities, and everything the matte
     * rejects becomes transparent. The layer then behaves like any other source — it can be moved,
     * masked, feathered, graded, blended and keyframed, and it exports with alpha.</p>
     *
     * <p>A 3D (perspective-projected) entity layer cannot be keyed: the matte is a screen-space
     * property of the world camera, not of an arbitrary plane in the composition. Rather than
     * silently drawing a wrong matte, a 3D entity layer degrades to the un-keyed world plate.</p>
     */
    private static void drawReplayEntity(CompLayer layer, CompRenderContext context, Batcher2D batcher, Matrix4f world, Matrix4f projection)
    {
        /* New bbsae replay layers are independent actors. Until a model is picked there is nothing
         * to show; they must not fall back to the real-world film plate. */
        if (layer.independent && !layer.hasModelForm())
        {
            if (context.preview)
            {
                float x = -layer.width * layer.anchorX;
                float y = -layer.height * layer.anchorY;

                batcher.outline(x, y, x + layer.width, y + layer.height, 0x6655AAFF);
                batcher.textShadow("回放模型：请在图层页选择模型", x + 10F, y + 10F, 0xFFFFD27A);
            }

            return;
        }

        /* Independent bbsae model mode: the form is drawn from the composition camera into a
         * transparent depth target. There is no film plate and no matte, so moving the model cannot
         * reveal a background and self-occlusion is resolved by the depth buffer. */
        if (layer.hasModelForm())
        {
            GlFramebuffer model = ReplayActorPass.renderModel(layer, context, batcher);

            if (model != null)
            {
                drawCompositionActors(context, batcher, model);

                return;
            }
        }

        /* A 3D entity layer is a real model in the composition camera, not a crop of the world
         * frame. The pass maps blocks to comp pixels and applies the comp camera; drawing its
         * result with the normal comp ortho is what makes orbiting the bbsae camera actually orbit
         * the actor. */
        if (layer.threeD)
        {
            GlFramebuffer actors = ReplayActorPass.render(layer, context, batcher);

            if (actors != null)
            {
                drawCompositionActors(context, batcher, actors);

                return;
            }
        }

        dev.bbsae.client.render.GlTexture texture = dev.bbsae.client.overlay.BbsPreviewBridge.texture();
        boolean active = dev.bbsae.client.overlay.BbsPreviewBridge.isActive();

        /* 2D entity layers normally key the live world plate. If the plate or its matte is not
         * available yet (first frame, film switch, shader-pack capture failure), falling straight
         * to the blue placeholder made the preview look broken. Render the same actors screen-fixed
         * with the composition ortho as a fallback; the keyed path takes over again as soon as the
         * world frame is valid. */
        if (!layer.threeD && (texture == null || !ReplayEntityMask.isCurrentFrame()))
        {
            GlFramebuffer flatActors = ReplayActorPass.renderFlat(layer, context, batcher);

            if (flatActors != null)
            {
                drawCompositionActors(context, batcher, flatActors);

                return;
            }
        }

        if (texture == null)
        {
            if (active && context.preview)
            {
                float x = -layer.width * layer.anchorX;
                float y = -layer.height * layer.anchorY;

                batcher.box(x, y, x + layer.width, y + layer.height, 0x3300AAFF);
                batcher.outline(x, y, x + layer.width, y + layer.height, 0x8855AAFF);
                batcher.textShadow("回放实体：等待世界帧…", x + 10F, y + 10F, 0xFFFFD27A);
            }

            return;
        }

        /* Flush first: the matte is drawn with its own program into the target, and any quad
         * still queued in the batcher would be flushed after it and paint over the result. */
        batcher.flush();

        /* The matte is a screen-space key of the world camera. A 3D entity layer used to fall
         * back to the un-keyed world plate here, which is exactly why its background was the
         * whole frame instead of transparent. Keep the key for 3D too, but project it with the
         * composition's 2D ortho matrix: the actor's screen position already comes from the world
         * camera, so baking the layer's z/rotationX/Y on top of it would move the matte away from
         * the actor it was extracted from. */
        Matrix4f matteWorld = layer.threeD ? CompScene.world(context, layer) : world;
        Matrix4f matteProjection = layer.threeD ? GlStateGuard.ortho(context.width, context.height) : projection;

        /* Production path: the full-screen coverage shader. The batcher variant is kept
         * behind a flag for A/B verification of sampler/state issues. */
        boolean keyed = Boolean.getBoolean("bbsae.entityKeyTextured")
            ? dev.bbsae.client.render.ReplayEntityKey.drawTextured(layer, context, batcher)
            : dev.bbsae.client.render.ReplayEntityKey.draw(layer, context, matteWorld, matteProjection);

        if (keyed)
        {
            return;
        }

        /* No mask yet: stay transparent. Falling back to the un-keyed world plate here would
         * silently turn "nothing is keyed" into "the whole background is keyed", which is the
         * one result a keying layer must never produce. */
        if (context.preview)
        {
            float x = -layer.width * layer.anchorX;
            float y = -layer.height * layer.anchorY;

            batcher.outline(x, y, x + layer.width, y + layer.height, 0x4455AAFF);
        }
    }

    /** Draws the comp-camera actor pass as a full-composition image (already camera-projected). */
    private static void drawCompositionActors(CompRenderContext context, Batcher2D batcher, GlFramebuffer actors)
    {
        MatrixStack stack = batcher.getContext().getMatrices();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());

        stack.push();

        try
        {
            stack.peek().getPositionMatrix().identity();
            RenderSystem.setProjectionMatrix(GlStateGuard.ortho(context.width, context.height),
                com.mojang.blaze3d.systems.VertexSorter.BY_Z);
            batcher.texturedBox(actors.color.id, 0xFFFFFFFF,
                0F, 0F, context.width, context.height,
                0F, context.height, context.width, 0F,
                context.width, context.height);
            batcher.flush();
        }
        finally
        {
            stack.pop();
            RenderSystem.setProjectionMatrix(previousProjection, com.mojang.blaze3d.systems.VertexSorter.BY_Z);
        }
    }

    /** Draws the world frame as an opaque picture, honouring the layer's fit and anchor. */
    private static void drawWorldPlate(CompLayer layer, Batcher2D batcher, dev.bbsae.client.render.GlTexture texture)
    {
        float w;
        float h;

        if (layer.imageFit == 2)
        {
            w = Math.max(1F, layer.width);
            h = Math.max(1F, layer.height);
        }
        else
        {
            float scale = layer.imageFit == 1
                ? Math.max(layer.width / texture.width, layer.height / texture.height)
                : Math.min(layer.width / texture.width, layer.height / texture.height);

            w = texture.width * scale;
            h = texture.height * scale;
        }

        batcher.texturedBox(texture.id, layer.color | 0xFF000000,
            -w * layer.anchorX, -h * layer.anchorY, w, h,
            0F, texture.height, texture.width, 0F, texture.width, texture.height);
        dev.bbsae.client.overlay.BbsPreviewBridge.noteFrameDrawn(texture.width, texture.height);
    }

    private static void drawPrecomp(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        CompDocument sub = layer.precompId == null || layer.precompId.isEmpty()
            ? null
            : context.project.getComp(layer.precompId);

        if (sub == null || context.stack.contains(sub.id))
        {
            /* Empty precompId used to resolve to the *first* composition (the current one), and
             * the cycle guard then swallowed the draw entirely. Say so instead of drawing air.
             * The message is editor-only: baking it into the export would put UI text in the film. */
            if (context.preview)
            {
                drawPrecompPlaceholder(layer, batcher, sub == null ? "未指定预合成 · 在图层检查器选择目标" : "循环预合成");
            }

            return;
        }

        /* An empty precomp used to render nothing at all, so the layer looked broken. Draw a
         * placeholder that is still selectable in the preview. */
        if (sub.layers == null || sub.layers.isEmpty())
        {
            if (context.preview)
            {
                drawPrecompPlaceholder(layer, batcher, "空预合成 · 双击轨道进入编辑");
            }

            return;
        }

        /* A precomp is a clip like any other: it must obey startTime, timeScale, freeze and time
         * remap. Using raw comp time here made trimming/moving the precomp layer appear to do
         * nothing, which is why the layer felt like a name-only placeholder. */
        float base = layer.sourceTimeAt(context.time, context.frame);
        float time = (base + layer.precompTimeOffset) * layer.precompTimeScale;

        /* Flush the outer batch first: the nested composition binds its own framebuffers, and any
         * queued quad would otherwise be flushed into the nested target instead of this layer. */
        batcher.flush();

        /* The child inherits the parent pass: a depth/guide pass that disabled effects must not
         * get them back just because the layer happens to be a precomp. The stage is transparent:
         * a nested comp is not the film canvas, so its background colour must not paint an opaque
         * rectangle over every layer below (that is what turned "预合成" into a black box). */
        GlFramebuffer framebuffer = CompRenderer.renderCompToFbo(context.project, sub, time, context.depth, context.projection, context.camera, context.stack, batcher, 0,
            context.renderEffects, context.renderMasks, context.preview, context.scale, true);

        if (framebuffer == null)
        {
            return;
        }

        /* The nested comp used custom VAOs and left blending disabled. Rebind the state
         * Minecraft's Batcher expects before compositing its result into this layer. */
        BufferRenderer.resetCurrentVertexBuffer();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        float sx = layer.width / sub.width;
        float sy = layer.height / sub.height;
        float scale = layer.imageFit == 1 ? Math.max(sx, sy) : layer.imageFit == 2 ? 0F : Math.min(sx, sy);

        /* Only the RGB is a tint; the alpha must stay 255 or the nested comp is multiplied by the
         * colour's alpha AND by the layer opacity (double transparency). */
        int tint = layer.color | 0xFF000000;

        if (scale == 0F)
        {
            batcher.texturedBox(framebuffer.color.id, tint, -layer.width * layer.anchorX, -layer.height * layer.anchorY, layer.width, layer.height, 0F, sub.height, sub.width, 0F, sub.width, sub.height);
        }
        else
        {
            float w = sub.width * scale;
            float h = sub.height * scale;

            batcher.texturedBox(framebuffer.color.id, tint, -w * layer.anchorX, -h * layer.anchorY, w, h, 0F, sub.height, sub.width, 0F, sub.width, sub.height);
        }

        /* The Batcher only queues the textured quad. Flush before returning the FBO to the pool,
         * otherwise a later layer can acquire and overwrite the texture still referenced by the
         * queued draw. */
        batcher.flush();
        BufferRenderer.resetCurrentVertexBuffer();
        FboPool.release(framebuffer);
    }

    private static void drawPrecompPlaceholder(CompLayer layer, Batcher2D batcher, String hint)
    {
        float x = -layer.width * layer.anchorX;
        float y = -layer.height * layer.anchorY;

        batcher.box(x, y, x + layer.width, y + layer.height, 0x221E88E5);
        batcher.outline(x, y, x + layer.width, y + layer.height, 0x8855AAFF);
        batcher.textShadow(hint, x + 12F, y + 12F, 0xFF9CC7FF);
    }

    private static void drawText(CompLayer layer, CompRenderContext context, Batcher2D batcher)
    {
        String text = dev.bbsae.comp.TextContent.resolve(layer,
            context == null ? 0F : context.time,
            context == null || context.comp == null ? 60 : Math.max(1, Math.round(context.comp.fps)),
            context == null ? 0 : Math.round(context.frame));

        if (text.isEmpty())
        {
            return;
        }

        float fontSize = layer.fontSizeAt(context.frame);
        FontRenderer fallback = batcher.getFont() == null
            ? Batcher2D.getDefaultTextRenderer()
            : batcher.getFont();
        LayerFont chosen = fontFor(layer, batcher, fontSize);
        FontRenderer font = chosen.renderer;
        int fontHeight = Math.max(1, font.getHeight());
        float tracking = layer.trackingAt(context.frame);
        float lineHeightFactor = layer.lineHeightAt(context.frame);
        float textStroke = layer.textStrokeAt(context.frame);
        float scale = Math.max(0.001F, fontSize / fontHeight);
        float maxWidth = layer.textBoxWidth / scale;

        /* Coverage is resolved once per layer per frame. Everything below (layout build, run
         * planning, drawing) only ever consults the bitmap, never the file system. */
        FontCoverage.Coverage coverage = chosen.custom() ? FontCoverage.coverage(chosen.file) : null;
        float fallbackScale = 1F;

        if (chosen.custom())
        {
            fallbackScale = Math.max(0.05F, chosen.renderer.getHeight() / (float) Math.max(1, fallback.getHeight()));
        }

        long layoutStart = DEBUG_TEXT_PERF ? System.nanoTime() : 0L;
        LayoutKey layoutKey = new LayoutKey(text, chosen.name,
            chosen.custom() ? System.identityHashCode(chosen.renderer) : 0L,
            System.identityHashCode(fallback), fontSize, tracking, maxWidth, layer.wrap, layer.align, 0);
        TextLayout layout = LAYOUTS.get(layoutKey);
        boolean layoutHit = layout != null;

        if (layout == null)
        {
            layout = buildLayout(text, layer.wrap, maxWidth, tracking,
                new LayerGlyphSource(chosen, fallback, coverage, fallbackScale));
            LAYOUTS.put(layoutKey, layout);
        }

        long layoutEnd = DEBUG_TEXT_PERF ? System.nanoTime() : 0L;

        if (Boolean.getBoolean("bbsae.debugFonts") && FONT_WARNINGS.add("glyphs:" + layer.id + ":" + text.hashCode()))
        {
            StringBuilder detail = new StringBuilder();

            for (int i = 0; i < text.length() && detail.length() < 400; )
            {
                int codePoint = text.codePointAt(i);
                String glyph = glyphString(codePoint);
                FontRenderer glyphFont = glyphFontFor(chosen, fallback, coverage, codePoint);
                float glyphScale = glyphScaleFor(chosen, fallback, glyphFont, fallbackScale);

                detail.append(String.format(java.util.Locale.ROOT, " U+%04X[%s w=%.1f x%.1f]",
                    codePoint,
                    chosen.custom() && glyphFont == chosen.renderer ? "custom" : "default",
                    glyphFont.getWidth(glyph) * glyphScale,
                    glyphScale));

                i += Character.charCount(codePoint);
            }

            dev.bbsae.BbsAeAddon.LOGGER.info("glyphs {} '{}'{}", layer.id, text, detail);
        }

        float width = layout.width;
        float lineHeight = Math.max(1F, fontHeight * lineHeightFactor);
        float totalHeight = layout.lines.length * lineHeight;
        float baseX = -width * layer.anchorX;
        float baseY = -totalHeight * layer.anchorY;
        MatrixStack stack = batcher.getContext().getMatrices();
        GlyphBatch batch = new GlyphBatch(batcher, stack);
        long drawStart = DEBUG_TEXT_PERF ? System.nanoTime() : 0L;

        stack.push();

        try
        {
            stack.scale(scale, scale, 1F);

            if (hasAnimator(layer))
            {
                drawAnimatedText(batch, layout, layer, baseX, baseY, lineHeight, width, tracking, fontHeight, textStroke);
            }
            else
            {
                drawPlainText(batch, layout, layer, baseX, baseY, lineHeight, width, textStroke);
            }
        }
        finally
        {
            stack.pop();
            batch.end();
        }

        long drawEnd = DEBUG_TEXT_PERF ? System.nanoTime() : 0L;

        noteTextPerf(layoutHit, layoutEnd - layoutStart, drawEnd - drawStart, layout.lines.length,
            layout.glyphCount, batch.textDraws, batch.batches, batch.mergedRuns, batch.glyphRuns);
    }

    /**
     * Non-animated text: one {@code TextRenderer.draw()} per run instead of one per glyph.
     *
     * <p>Every call appends its quads to the same immediate vertex buffer, so the whole layer is
     * flushed by a single {@code DrawContext.draw()} — one GPU draw call per font atlas instead of
     * 500-1000 per 200 glyphs.</p>
     */
    private static void drawPlainText(GlyphBatch batch, TextLayout layout, CompLayer layer,
                                      float baseX, float baseY, float lineHeight, float width, float textStroke)
    {
        for (int lineIndex = 0; lineIndex < layout.lines.length; lineIndex++)
        {
            TextLine line = layout.lines[lineIndex];
            float x = alignedX(layer.align, baseX, width, line.width);
            float y = baseY + lineIndex * lineHeight;

            for (GlyphRun run : line.runs)
            {
                if (run.mergeable)
                {
                    batch.mergedRuns++;
                    drawTextRun(batch, run.font, run.text, x + run.x, y, layer.color, layer.strokeColor, textStroke);
                }
                else
                {
                    batch.glyphRuns++;

                    for (Glyph glyph : run.glyphs)
                    {
                        if (Character.isWhitespace(glyph.codePoint))
                        {
                            continue;
                        }

                        drawGlyph(batch, glyph, x + glyph.x, y, layer.color, layer.strokeColor, textStroke);
                    }
                }
            }
        }
    }

    /**
     * Animated text keeps the old per-glyph transform order (each glyph's four stroke offsets and
     * its fill stay adjacent), but all of it is appended to one vertex buffer and flushed once.
     */
    private static void drawAnimatedText(GlyphBatch batch, TextLayout layout, CompLayer layer,
                                         float baseX, float baseY, float lineHeight, float width,
                                         float tracking, float fontHeight, float textStroke)
    {
        int total = layout.glyphCount;
        int visible = 0;

        for (int lineIndex = 0; lineIndex < layout.lines.length; lineIndex++)
        {
            TextLine line = layout.lines[lineIndex];
            float x = alignedX(layer.align, baseX, width, line.width);
            float y = baseY + lineIndex * lineHeight;

            for (Glyph glyph : line.glyphs)
            {
                if (Character.isWhitespace(glyph.codePoint))
                {
                    continue;
                }

                float advance = glyph.advance + tracking;
                float charX = x + glyph.x;
                float progress = total <= 1 ? 0F : visible / (float) (total - 1);
                CompTextAnimator animator = selectAnimator(layer, progress, visible);
                MatrixStack stack = batch.matrices;

                stack.push();

                try
                {
                    if (animator != null)
                    {
                        float wx = animator.positionX;
                        float wy = animator.positionY;
                        float animScale = animator.scale;
                        float rotation = animator.rotation;

                        if (animator.wiggle)
                        {
                            wx += (CompExpressionNoise.noise(visible * 3.17F, animator.seed) * 2F - 1F) * animator.randomness;
                            wy += (CompExpressionNoise.noise(visible * 5.13F, animator.seed + 11F) * 2F - 1F) * animator.randomness;
                        }

                        stack.translate(charX + advance / 2F + wx, y + fontHeight / 2F + wy, 0F);
                        stack.multiply(ROTATION.rotationZ((float) Math.toRadians(rotation)));
                        stack.scale(animScale, animScale, 1F);
                        stack.translate(-charX - advance / 2F, -y - fontHeight / 2F, 0F);
                    }

                    int color;

                    if (animator != null)
                    {
                        color = animator.useColor ? animator.color : layer.color;
                        color = withAlpha(color, Colors.getA(color) * animator.opacity);
                    }
                    else
                    {
                        color = layer.color;
                    }

                    drawGlyph(batch, glyph, charX, y, color, layer.strokeColor, textStroke);
                }
                finally
                {
                    stack.pop();
                }

                visible += 1;
            }
        }
    }

    private static float alignedX(int align, float baseX, float width, float lineWidth)
    {
        if (align == 0)
        {
            return baseX;
        }

        if (align == 2)
        {
            return baseX + width - lineWidth;
        }

        return baseX + (width - lineWidth) / 2F;
    }

    /** Four stroke offsets plus the fill, drawn as one string per run and per offset. */
    private static void drawTextRun(GlyphBatch batch, FontRenderer font, String text, float x, float y,
                                    int color, int strokeColor, float strokeWidth)
    {
        if (Colors.getA(color) <= 0F)
        {
            return;
        }

        if (Colors.getA(strokeColor) > 0F && strokeWidth > 0F)
        {
            float offset = Math.max(1F, strokeWidth * 0.5F);

            batch.text(font, text, x - offset, y, strokeColor);
            batch.text(font, text, x + offset, y, strokeColor);
            batch.text(font, text, x, y - offset, strokeColor);
            batch.text(font, text, x, y + offset, strokeColor);
        }

        batch.text(font, text, x, y, color);
    }

    /** One glyph, including the fallback-font size correction the old per-glyph path applied. */
    private static void drawGlyph(GlyphBatch batch, Glyph glyph, float x, float y,
                                  int color, int strokeColor, float strokeWidth)
    {
        MatrixStack stack = batch.matrices;

        if (glyph.scale == 1F)
        {
            drawTextRun(batch, glyph.font, glyph.text, x, y, color, strokeColor, strokeWidth);

            return;
        }

        /* Scale about the glyph origin so the baseline stays where the layout put it. */
        stack.push();
        stack.translate(x, y, 0F);
        stack.scale(glyph.scale, glyph.scale, 1F);
        stack.translate(-x, -y, 0F);

        try
        {
            drawTextRun(batch, glyph.font, glyph.text, x, y, color, strokeColor, strokeWidth);
        }
        finally
        {
            stack.pop();
        }
    }

    private static boolean hasAnimator(CompLayer layer)
    {
        if (layer.textAnimators == null)
        {
            return false;
        }

        for (CompTextAnimator animator : layer.textAnimators)
        {
            if (animator != null && animator.enabled)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Text drawing into the batcher's immediate vertex buffer.
     *
     * <p>{@code Batcher2D.text()} always flushes the batch and calls {@code DrawContext.draw()}
     * itself, which is exactly why 200 glyphs with a stroke became 1000 draw calls. Drawing through
     * the same {@code TextRenderer} and {@code VertexConsumerProvider} that {@code DrawContext}
     * uses, then flushing once, keeps the identical pixels and collapses the draw calls.</p>
     */
    private static final class GlyphBatch
    {
        final MatrixStack matrices;
        private final Batcher2D batcher;
        private final VertexConsumerProvider.Immediate consumers;
        private final java.util.Set<TextRenderer> renderers =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean started;
        int textDraws;
        int batches;
        int mergedRuns;
        int glyphRuns;

        GlyphBatch(Batcher2D batcher, MatrixStack matrices)
        {
            this.batcher = batcher;
            this.matrices = matrices;
            this.consumers = batcher.getContext().getVertexConsumers();
        }

        void text(FontRenderer font, String text, float x, float y, int color)
        {
            if (font == null || text.isEmpty() || Colors.getA(color) <= 0F)
            {
                return;
            }

            TextRenderer renderer = font.getRenderer();

            if (renderer == null)
            {
                return;
            }

            if (!this.started)
            {
                /* The old path flushed before every glyph; doing it once keeps the queued UI
                 * quads in front of the text with a single flush. */
                this.batcher.flush();
                this.started = true;
            }

            renderer.draw(text, (int) x, (int) y, textColor(color), false,
                this.matrices.peek().getPositionMatrix(), this.consumers,
                TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
            this.renderers.add(renderer);
            this.textDraws++;
        }

        void end()
        {
            if (this.textDraws <= 0)
            {
                return;
            }

            /* Batcher2D counts one draw call per text() call; the real count is the number of
             * font atlas render layers flushed by DrawContext.draw(). */
            BBSProfiler.count(BBSProfiler.Section.UI_DRAW_CALLS, this.renderers.size());
            this.batcher.getContext().draw();

            /* Batcher2D.text() leaves the depth function at GL_LEQUAL; the next layer expects
             * the same state. */
            RenderSystem.depthFunc(519);
            this.batches++;
        }
    }

    /** The colour adjustment {@code Batcher2D.text()} applies on light surfaces. */
    private static int textColor(int color)
    {
        if (!BBSSettings.lightSurfaces())
        {
            return color;
        }

        return (color & 0xFFFFFF) == 0xFFFFFF ? color & 0xFF000000 : color;
    }

    /**
     * The layer's font file plus BBS's renderer for it.
     *
     * <p>{@code renderer} is never null: when the project names a font that is missing, unreadable
     * or that BBS cannot parse, it is the default renderer and {@code file} is null. The renderer
     * still chooses per character (see {@link #glyphFontFor}) because a font is a partial map.</p>
     */
    private static final class LayerFont
    {
        final FontRenderer renderer;
        final File file;
        final String name;

        LayerFont(FontRenderer renderer, File file, String name)
        {
            this.renderer = renderer;
            this.file = file;
            this.name = name;
        }

        boolean custom()
        {
            return this.file != null;
        }
    }

    private static final class ResolvedFont
    {
        final Link link;
        final File file;
        /** Last BBS renderer for this file; a change means BBS reloaded the font. */
        FontRenderer lastRenderer;

        ResolvedFont(Link link, File file)
        {
            this.link = link;
            this.file = file;
        }
    }

    /** fontName → link/file, so the per-frame path does not rescan the folder. */
    private static final java.util.Map<String, ResolvedFont> FONT_FILES = new java.util.HashMap<>();
    private static final java.util.Set<String> FONT_WARNINGS = new java.util.HashSet<>();

    /** Line layout + glyph runs; 64 distinct text blocks is more than a composition has. */
    private static final LruCache<LayoutKey, TextLayout> LAYOUTS = new LruCache<>(64);

    /** Reused by the per-glyph animator path instead of allocating a Quaternionf per glyph/frame. */
    private static final Quaternionf ROTATION = new Quaternionf();

    /** Drops the resolved names (after an import or a font folder change). */
    static void clearFonts()
    {
        FONT_FILES.clear();
        FONT_WARNINGS.clear();
        LAYOUTS.clear();
        FontCoverage.clear();
    }

    /** Custom fonts come from BBS's own TTF loader; an empty name keeps the default font. */
    private static LayerFont fontFor(CompLayer layer, Batcher2D batcher, float fontSize)
    {
        FontRenderer fallback = batcher.getFont() == null
            ? Batcher2D.getDefaultTextRenderer()
            : batcher.getFont();
        String name = layer.fontName == null ? "" : layer.fontName.trim();

        if (name.isEmpty())
        {
            return new LayerFont(fallback, null, "");
        }

        int size = Math.max(4, Math.min(96, Math.round(fontSize)));

        /* The atlas is rasterized at `size`; a 200 px text layer must not be a 96 px atlas blown
         * up 2x. BBS' FontManager turns this ratio into its oversampling factor. */
        float rasterScale = Math.max(1F, fontSize / Math.max(1, size));
        ResolvedFont resolved = resolve(name);

        if (resolved == null)
        {
            warn(name, "the font file is not in BBS' fonts folder: " + name);

            return new LayerFont(fallback, null, name);
        }

        FontRenderer custom;

        try
        {
            /* Deliberately not cached: BBS evicts idle fonts after 30 s and invalidates entries on
             * a font file change. get() is a hash lookup and returns the same FontRenderer until
             * then, so the layout cache key stays stable and only staleness is impossible. */
            custom = BBSModClient.getFonts().get(resolved.link, size, rasterScale);
        }
        catch (RuntimeException e)
        {
            warn(name, "BBS could not load the font: " + e);
            custom = null;
        }

        if (custom == null || custom.getRenderer() == null)
        {
            warn(name, "BBS could not parse the font (OTF/CFF or corrupt): " + resolved.file);

            return new LayerFont(fallback, null, name);
        }

        /* BBS evicts idle fonts and invalidates entries when a font file changes. A new renderer
         * identity for the same file therefore means the cmap may have changed too. */
        if (resolved.lastRenderer != null && resolved.lastRenderer != custom)
        {
            FontCoverage.invalidate(resolved.file);
        }

        resolved.lastRenderer = custom;

        if (Boolean.getBoolean("bbsae.debugFonts") && FONT_WARNINGS.add("debug:" + name + ":" + size))
        {
            dev.bbsae.BbsAeAddon.LOGGER.info(
                "font: name={} file={} size={} oversample={} height={} width(H)={} width(新)={} coversH={} coversHan={}",
                name, resolved.file, size, rasterScale, custom.getHeight(),
                custom.getWidth("H"), custom.getWidth("新"),
                FontCoverage.covers(resolved.file, 'H'), FontCoverage.covers(resolved.file, '新'));
        }

        return new LayerFont(custom, resolved.file, name);
    }

    /**
     * Resolves a stored {@code fontName} to a link and a file.
     *
     * <p>Accepts what older projects and the picker actually store: a plain file name
     * ({@code timesbd.ttf}), a name without an extension, a sub-folder path under {@code fonts/},
     * and a name whose case differs from the file on disk.</p>
     *
     * <p>Hits are answered without an {@code isFile()} probe: the font list is invalidated by
     * {@link #clearFonts()} after an import, which is cheaper than a stat per frame per layer.</p>
     */
    private static ResolvedFont resolve(String name)
    {
        ResolvedFont cached = FONT_FILES.get(name);

        if (cached != null)
        {
            return cached;
        }

        File file = FontCoverage.resolve(name);

        if (file == null)
        {
            return null;
        }

        ResolvedFont resolved = new ResolvedFont(
            Link.assets("fonts/" + FontCoverage.relativeName(file)), file);

        FONT_FILES.put(name, resolved);

        return resolved;
    }

    /** Logs one line per unloadable font instead of spamming the console every frame. */
    private static void warn(String name, String message)
    {
        if (FONT_WARNINGS.add(name))
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Text layer font '{}': {}", name, message);
        }
    }

    /* ---------------------------------------------------------------- text layout engine */

    /**
     * Advance of one code point in the font that will actually draw it (fallback scaling included).
     *
     * <p>Pulled out as an interface so the wrap algorithm can be tested without a client, a
     * {@link FontRenderer} instance or a GL context.</p>
     */
    interface GlyphAdvance
    {
        float advance(int codePoint);
    }

    /** How one code point is drawn; {@code font} is null in layout-only unit tests. */
    static final class GlyphSpec
    {
        final FontRenderer font;
        final float scale;
        final float advance;

        GlyphSpec(FontRenderer font, float scale, float advance)
        {
            this.font = font;
            this.scale = scale;
            this.advance = advance;
        }
    }

    /** Supplies the font/scale/advance for a code point while a layout is being built. */
    interface GlyphSource extends GlyphAdvance
    {
        GlyphSpec glyph(int codePoint);
    }

    /** One code point placed at {@code x} relative to the start of its line. */
    static final class Glyph
    {
        final int codePoint;
        final String text;
        final FontRenderer font;
        final float scale;
        final float advance;
        final float x;

        Glyph(int codePoint, String text, FontRenderer font, float scale, float advance, float x)
        {
            this.codePoint = codePoint;
            this.text = text;
            this.font = font;
            this.scale = scale;
            this.advance = advance;
            this.x = x;
        }
    }

    /** Consecutive glyphs of one font that can be drawn by a single {@code TextRenderer.draw()}. */
    static final class GlyphRun
    {
        final FontRenderer font;
        final String text;
        final float x;
        final boolean mergeable;
        final List<Glyph> glyphs;

        GlyphRun(FontRenderer font, String text, float x, boolean mergeable, List<Glyph> glyphs)
        {
            this.font = font;
            this.text = text;
            this.x = x;
            this.mergeable = mergeable;
            this.glyphs = glyphs;
        }
    }

    /** One laid-out line: its text, width, and (for the render path) glyphs plus draw runs. */
    static final class TextLine
    {
        final String text;
        final float width;
        final int glyphCount;
        final List<Glyph> glyphs;
        final List<GlyphRun> runs;

        TextLine(String text, float width, int glyphCount, List<Glyph> glyphs, List<GlyphRun> runs)
        {
            this.text = text;
            this.width = width;
            this.glyphCount = glyphCount;
            this.glyphs = glyphs;
            this.runs = runs;
        }
    }

    /** A measured, cacheable text block. */
    static final class TextLayout
    {
        final String text;
        final TextLine[] lines;
        final float width;
        final int glyphCount;

        TextLayout(String text, TextLine[] lines, float width, int glyphCount)
        {
            this.text = text;
            this.lines = lines;
            this.width = width;
            this.glyphCount = glyphCount;
        }
    }

    /**
     * Layout cache key: text, font identity and every parameter that changes a line break or a
     * glyph advance. {@code segments} is reserved for styled text segments (this renderer has
     * none yet, so it is always 0) and exists so adding them later invalidates cached layouts.
     */
    static final class LayoutKey
    {
        final String text;
        final String fontName;
        final long fontIdentity;
        final long fallbackIdentity;
        final float fontSize;
        final float tracking;
        final float wrapWidth;
        final boolean wrap;
        final int align;
        final int segments;
        private final int hash;

        LayoutKey(String text, String fontName, long fontIdentity, long fallbackIdentity, float fontSize,
                  float tracking, float wrapWidth, boolean wrap, int align, int segments)
        {
            this.text = text;
            this.fontName = fontName;
            this.fontIdentity = fontIdentity;
            this.fallbackIdentity = fallbackIdentity;
            this.fontSize = fontSize;
            this.tracking = tracking;
            this.wrapWidth = wrapWidth;
            this.wrap = wrap;
            this.align = align;
            this.segments = segments;

            int value = text.hashCode();

            value = value * 31 + fontName.hashCode();
            value = value * 31 + Long.hashCode(fontIdentity);
            value = value * 31 + Long.hashCode(fallbackIdentity);
            value = value * 31 + Float.floatToIntBits(fontSize);
            value = value * 31 + Float.floatToIntBits(tracking);
            value = value * 31 + Float.floatToIntBits(wrapWidth);
            value = value * 31 + (wrap ? 1 : 0);
            value = value * 31 + align;
            value = value * 31 + segments;

            this.hash = value;
        }

        @Override
        public int hashCode()
        {
            return this.hash;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof LayoutKey))
            {
                return false;
            }

            LayoutKey other = (LayoutKey) object;

            return this.wrap == other.wrap
                && this.align == other.align
                && this.segments == other.segments
                && this.fontIdentity == other.fontIdentity
                && this.fallbackIdentity == other.fallbackIdentity
                && Float.compare(this.fontSize, other.fontSize) == 0
                && Float.compare(this.tracking, other.tracking) == 0
                && Float.compare(this.wrapWidth, other.wrapWidth) == 0
                && this.fontName.equals(other.fontName)
                && this.text.equals(other.text);
        }
    }

    /** Small access-ordered LRU; also the seam the layout-cache unit test drives. */
    static final class LruCache<K, V>
    {
        private final int capacity;
        private final LinkedHashMap<K, V> entries;
        private long hits;
        private long misses;

        LruCache(int capacity)
        {
            this.capacity = capacity;
            this.entries = new LinkedHashMap<K, V>(Math.max(4, capacity), 0.75F, true)
            {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
                {
                    return size() > LruCache.this.capacity;
                }
            };
        }

        V get(K key)
        {
            V value = this.entries.get(key);

            if (value == null)
            {
                this.misses++;

                return null;
            }

            this.hits++;

            return value;
        }

        void put(K key, V value)
        {
            this.entries.put(key, value);
        }

        int size()
        {
            return this.entries.size();
        }

        long hits()
        {
            return this.hits;
        }

        long misses()
        {
            return this.misses;
        }

        void clear()
        {
            this.entries.clear();
        }
    }

    /** Measures a run in one font; production is {@code FontRenderer.getWidth}, tests fake it. */
    interface WidthProbe
    {
        float width(FontRenderer font, String text);
    }

    private static final WidthProbe FONT_WIDTH = (font, text) -> font.getWidth(text);

    /** Decides, per code point, whether the chosen font or BBS' default font draws it. */
    private static final class LayerGlyphSource implements GlyphSource
    {
        private final LayerFont font;
        private final FontRenderer fallback;
        private final FontCoverage.Coverage coverage;
        private final float fallbackScale;

        LayerGlyphSource(LayerFont font, FontRenderer fallback, FontCoverage.Coverage coverage, float fallbackScale)
        {
            this.font = font;
            this.fallback = fallback;
            this.coverage = coverage;
            this.fallbackScale = fallbackScale;
        }

        @Override
        public GlyphSpec glyph(int codePoint)
        {
            boolean custom = this.font != null && this.font.custom();
            FontRenderer renderer = this.fontFor(codePoint);
            float scale = custom && renderer != this.font.renderer ? this.fallbackScale : 1F;
            String glyph = glyphString(codePoint);

            return new GlyphSpec(renderer, scale, renderer.getWidth(glyph) * scale);
        }

        @Override
        public float advance(int codePoint)
        {
            boolean custom = this.font != null && this.font.custom();
            FontRenderer renderer = this.fontFor(codePoint);
            float scale = custom && renderer != this.font.renderer ? this.fallbackScale : 1F;

            return renderer.getWidth(glyphString(codePoint)) * scale;
        }

        private FontRenderer fontFor(int codePoint)
        {
            if (this.font == null || !this.font.custom())
            {
                return this.fallback;
            }

            return FontCoverage.covers(this.coverage, codePoint) ? this.font.renderer : this.fallback;
        }
    }

    private static FontRenderer glyphFontFor(LayerFont font, FontRenderer fallback,
                                             FontCoverage.Coverage coverage, int codePoint)
    {
        if (font == null || !font.custom())
        {
            return fallback;
        }

        return FontCoverage.covers(coverage, codePoint) ? font.renderer : fallback;
    }

    private static float glyphScaleFor(LayerFont font, FontRenderer fallback, FontRenderer glyphFont, float fallbackScale)
    {
        if (font == null || !font.custom() || glyphFont != fallback)
        {
            return 1F;
        }

        return fallbackScale;
    }

    /* ---------------------------------------------------------------- pure layout (no GL) */

    /** Equivalent of {@code text.split("\n", -1)}: trailing and repeated empty lines are kept. */
    static List<String> splitNewlines(String text)
    {
        List<String> lines = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == '\n')
            {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }

        lines.add(text.substring(start));

        return lines;
    }

    /** Equivalent of {@code raw.split(" ")} (trailing empty strings removed, empty stays empty). */
    static String[] splitSpaces(String text)
    {
        if (text.isEmpty())
        {
            return new String[] { "" };
        }

        List<String> parts = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == ' ')
            {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }

        parts.add(text.substring(start));

        int size = parts.size();

        while (size > 0 && parts.get(size - 1).isEmpty())
        {
            size--;
        }

        String[] words = new String[size];

        for (int i = 0; i < size; i++)
        {
            words[i] = parts.get(i);
        }

        return words;
    }

    static float stringWidth(String text, float tracking, GlyphAdvance advance)
    {
        if (text.isEmpty())
        {
            return 0F;
        }

        float width = 0F;
        int count = 0;

        for (int i = 0; i < text.length(); )
        {
            int codePoint = text.codePointAt(i);

            width += advance.advance(codePoint);
            count += 1;
            i += Character.charCount(codePoint);
        }

        return width + tracking * Math.max(0, count - 1);
    }

    /**
     * Word wrap that matches the old algorithm exactly but measures incrementally.
     *
     * <p>The old code rebuilt {@code line + " " + word} and re-measured the whole line for every
     * word, then re-measured growing prefixes for every overflow character — O(n²) in string
     * length and in {@code FontRenderer.getWidth()} calls. This version keeps the running float
     * sum and code-point count, adds tracking only at the boundary it inserts, and measures each
     * character once. The additions happen in the same order as the old {@code lineWidth()}, so
     * the float rounding (and therefore every {@code > maxWidth} decision) is bit-identical.</p>
     */
    static List<String> wrapLine(String raw, float maxWidth, float tracking, GlyphAdvance advance)
    {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        float lineSum = 0F;
        int lineCount = 0;

        for (String word : splitSpaces(raw))
        {
            boolean lineEmpty = line.length() == 0;
            float wordSum = 0F;
            int wordCount = 0;

            for (int i = 0; i < word.length(); )
            {
                int codePoint = word.codePointAt(i);

                wordSum += advance.advance(codePoint);
                wordCount += 1;
                i += Character.charCount(codePoint);
            }

            float candidateSum;
            int candidateCount;

            if (lineEmpty)
            {
                candidateSum = wordSum;
                candidateCount = wordCount;
            }
            else
            {
                /* Same order as re-measuring line + " " + word from scratch. */
                candidateSum = lineSum;
                candidateSum += advance.advance(' ');

                for (int i = 0; i < word.length(); )
                {
                    int codePoint = word.codePointAt(i);

                    candidateSum += advance.advance(codePoint);
                    i += Character.charCount(codePoint);
                }

                candidateCount = lineCount + 1 + wordCount;
            }

            float candidateWidth = candidateSum + tracking * Math.max(0, candidateCount - 1);

            if (!lineEmpty && candidateWidth > maxWidth)
            {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
                lineSum = wordSum;
                lineCount = wordCount;
            }
            else
            {
                if (lineEmpty)
                {
                    line.append(word);
                }
                else
                {
                    line.append(' ').append(word);
                }

                lineSum = candidateSum;
                lineCount = candidateCount;
            }

            /* CJK has no spaces and a long word can exceed the box on its own. Break it by
             * UTF-16 characters exactly like the old code, but with a running prefix sum. */
            if (lineSum + tracking * Math.max(0, lineCount - 1) > maxWidth)
            {
                String overflow = line.toString();
                int chunkStart = 0;
                float chunkSum = 0F;
                int chunkCount = 0;
                char lastChar = 0;

                line.setLength(0);

                for (int i = 0; i < overflow.length(); i++)
                {
                    char c = overflow.charAt(i);
                    float candidateChunkSum;
                    int candidateChunkCount;

                    if (i == chunkStart)
                    {
                        candidateChunkSum = chunkSum + advance.advance(c);
                        candidateChunkCount = chunkCount + 1;
                    }
                    else if (Character.isHighSurrogate(lastChar) && Character.isLowSurrogate(c))
                    {
                        /* The previous high surrogate was measured as its own code point; the
                         * pair is one code point, so the code point count does not change. */
                        candidateChunkSum = chunkSum - advance.advance(lastChar)
                            + advance.advance(Character.toCodePoint(lastChar, c));
                        candidateChunkCount = chunkCount;
                    }
                    else
                    {
                        candidateChunkSum = chunkSum + advance.advance(c);
                        candidateChunkCount = chunkCount + 1;
                    }

                    float candidateChunkWidth = candidateChunkSum + tracking * Math.max(0, candidateChunkCount - 1);

                    if (i > chunkStart && candidateChunkWidth > maxWidth)
                    {
                        lines.add(overflow.substring(chunkStart, i));
                        chunkStart = i;
                        chunkSum = advance.advance(c);
                        chunkCount = 1;
                    }
                    else
                    {
                        chunkSum = candidateChunkSum;
                        chunkCount = candidateChunkCount;
                    }

                    lastChar = c;
                }

                line.append(overflow, chunkStart, overflow.length());
                lineSum = chunkSum;
                lineCount = chunkCount;
            }
        }

        lines.add(line.toString());

        return lines;
    }

    /** All line strings of a text block, before glyphs are attached. */
    static List<String> lineStrings(String text, boolean wrap, float maxWidth, float tracking, GlyphAdvance advance)
    {
        List<String> lines = new ArrayList<>();

        for (String raw : splitNewlines(text))
        {
            if (!wrap)
            {
                lines.add(raw);
            }
            else
            {
                lines.addAll(wrapLine(raw, maxWidth, tracking, advance));
            }
        }

        return lines;
    }

    static int nonWhitespaceCount(String line)
    {
        int count = 0;

        for (int i = 0; i < line.length(); )
        {
            int codePoint = line.codePointAt(i);

            if (!Character.isWhitespace(codePoint))
            {
                count += 1;
            }

            i += Character.charCount(codePoint);
        }

        return count;
    }

    private static int nonWhitespaceCount(List<Glyph> glyphs)
    {
        int count = 0;

        for (Glyph glyph : glyphs)
        {
            if (!Character.isWhitespace(glyph.codePoint))
            {
                count += 1;
            }
        }

        return count;
    }

    /**
     * Layout without glyph visuals; used by {@code TextLayoutTest} to pin the wrap algorithm and by
     * the tests as an oracle for line/glyph counts.
     */
    static TextLayout layoutText(String text, boolean wrap, float maxWidth, float tracking, GlyphAdvance advance)
    {
        List<String> strings = lineStrings(text, wrap, maxWidth, tracking, advance);
        TextLine[] lines = new TextLine[strings.size()];
        float width = 0F;
        int glyphCount = 0;

        for (int i = 0; i < strings.size(); i++)
        {
            String line = strings.get(i);
            float lineWidth = stringWidth(line, tracking, advance);
            int count = nonWhitespaceCount(line);

            lines[i] = new TextLine(line, lineWidth, count, null, null);
            width = Math.max(width, lineWidth);
            glyphCount += count;
        }

        return new TextLayout(text, lines, width, glyphCount);
    }

    /** Production layout: line strings plus the glyph/runs the draw path consumes. */
    private static TextLayout buildLayout(String text, boolean wrap, float maxWidth, float tracking, GlyphSource source)
    {
        List<String> strings = lineStrings(text, wrap, maxWidth, tracking, source);
        TextLine[] lines = new TextLine[strings.size()];
        float width = 0F;
        int glyphCount = 0;

        for (int i = 0; i < strings.size(); i++)
        {
            String line = strings.get(i);
            List<Glyph> glyphs = buildGlyphs(line, tracking, source);
            float lineWidth = 0F;

            if (!glyphs.isEmpty())
            {
                float sum = 0F;

                for (Glyph glyph : glyphs)
                {
                    sum += glyph.advance;
                }

                /* Same addition order as the old lineWidth(): sum the per-glyph advances in code
                 * point order, then add tracking * (count - 1) once. Keeping the float rounding
                 * identical matters because the alignment result is truncated to int per glyph. */
                lineWidth = sum + tracking * Math.max(0, glyphs.size() - 1);
            }

            int count = nonWhitespaceCount(glyphs);
            List<GlyphRun> runs = planRuns(glyphs, tracking, FONT_WIDTH, true);

            lines[i] = new TextLine(line, lineWidth, count, glyphs, runs);
            width = Math.max(width, lineWidth);
            glyphCount += count;
        }

        return new TextLayout(text, lines, width, glyphCount);
    }

    /** Places every code point of one line, including whitespace, so cursor positions are exact. */
    static List<Glyph> buildGlyphs(String line, float tracking, GlyphSource source)
    {
        List<Glyph> glyphs = new ArrayList<>();
        float cursor = 0F;

        for (int i = 0; i < line.length(); )
        {
            int codePoint = line.codePointAt(i);
            GlyphSpec spec = source.glyph(codePoint);

            glyphs.add(new Glyph(codePoint, glyphString(codePoint), spec.font, spec.scale, spec.advance, cursor));
            cursor += spec.advance + tracking;
            i += Character.charCount(codePoint);
        }

        return glyphs;
    }

    /**
     * Groups consecutive non-whitespace glyphs of the same font into runs.
     *
     * <p>A run is only mergeable when drawing it as one string places every glyph on the same
     * pixel as the old per-glyph loop: no tracking, no fallback scale, and the measured sum of
     * per-glyph widths equals the font's width for the whole string. When that does not hold the
     * run is drawn glyph by glyph (still into the same single vertex buffer).</p>
     */
    static List<GlyphRun> planRuns(List<Glyph> glyphs, float tracking, WidthProbe probe, boolean allowMerge)
    {
        List<GlyphRun> runs = new ArrayList<>();
        int index = 0;

        while (index < glyphs.size())
        {
            Glyph glyph = glyphs.get(index);

            if (Character.isWhitespace(glyph.codePoint))
            {
                /* Whitespace has no pixels; a space at the start of a run would only waste a
                 * draw call. Spaces *inside* a run are appended below so the renderer advances
                 * over them. */
                index++;
                continue;
            }

            if (glyph.codePoint == '\u00a7')
            {
                /* A section sign must be drawn as its own single-character string: merged with
                 * the next character it would be parsed as a formatting code instead of the two
                 * separate glyphs the old per-glyph loop drew. */
                runs.add(new GlyphRun(glyph.font, glyph.text, glyph.x, false, glyphs.subList(index, index + 1)));
                index++;
                continue;
            }

            FontRenderer font = glyph.font;
            boolean merge = allowMerge && tracking == 0F && glyph.scale == 1F;
            StringBuilder text = new StringBuilder(glyph.text);
            float measured = probe.width(font, glyph.text);
            int end = index + 1;

            while (merge && end < glyphs.size())
            {
                Glyph next = glyphs.get(end);

                if (next.font != font || next.scale != 1F
                    || isSkippedWhitespace(next.codePoint) || next.codePoint == '\u00a7')
                {
                    break;
                }

                text.append(next.text);
                measured += probe.width(font, next.text);
                end++;
            }

            String runText = text.toString();
            boolean mergeable = merge && probe.width(font, runText) == measured;

            runs.add(new GlyphRun(font, runText, glyph.x, mergeable, glyphs.subList(index, end)));
            index = end;
        }

        return runs;
    }

    /** Whitespace the old renderer never drew; a plain space is safe to include in a merged run. */
    static boolean isSkippedWhitespace(int codePoint)
    {
        return Character.isWhitespace(codePoint) && codePoint != ' ';
    }

    /**
     * Code point → one-character string.
     *
     * <p>The old loop allocated a new String for every glyph on every frame. Layouts cache the
     * strings, and the BMP lookup table makes the remaining one-off call sites allocation-free.</p>
     */
    static String glyphString(int codePoint)
    {
        if (codePoint >= 0 && codePoint < GLYPH_STRINGS.length)
        {
            String glyph = GLYPH_STRINGS[codePoint];

            if (glyph == null)
            {
                glyph = new String(Character.toChars(codePoint));
                GLYPH_STRINGS[codePoint] = glyph;
            }

            return glyph;
        }

        return new String(Character.toChars(codePoint));
    }

    private static final String[] GLYPH_STRINGS = new String[0x10000];

    /* ---------------------------------------------------------------- text perf counters */

    private static final boolean DEBUG_TEXT_PERF = Boolean.getBoolean("bbsae.debugTextPerf");
    private static final long TEXT_PERF_LOG_INTERVAL = 5_000_000_000L;

    private static long perfTextFrames;
    private static long perfLayoutHits;
    private static long perfLayoutMisses;
    private static long perfLayoutNanos;
    private static long perfDrawNanos;
    private static long perfLines;
    private static long perfGlyphs;
    private static long perfTextDraws;
    private static long perfBatches;
    private static long perfMergedRuns;
    private static long perfGlyphRuns;
    private static long perfLastLog = System.nanoTime();

    private static void noteTextPerf(boolean layoutHit, long layoutNanos, long drawNanos, int lines,
                                     int glyphs, int textDraws, int batches, int mergedRuns, int glyphRuns)
    {
        if (!DEBUG_TEXT_PERF)
        {
            return;
        }

        perfTextFrames++;

        if (layoutHit)
        {
            perfLayoutHits++;
        }
        else
        {
            perfLayoutMisses++;
        }

        perfLayoutNanos += layoutNanos;
        perfDrawNanos += drawNanos;
        perfLines += lines;
        perfGlyphs += glyphs;
        perfTextDraws += textDraws;
        perfBatches += batches;
        perfMergedRuns += mergedRuns;
        perfGlyphRuns += glyphRuns;

        FontCoverage.noteTextFrame();

        long now = System.nanoTime();

        if (now - perfLastLog < TEXT_PERF_LOG_INTERVAL)
        {
            return;
        }

        double frames = Math.max(1L, perfTextFrames);
        double seconds = Math.max(0.001D, (now - perfLastLog) / 1_000_000_000D);

        dev.bbsae.BbsAeAddon.LOGGER.info(
            "text layout: layers={} ({}/s) layoutHit={} layoutMiss={} layout={}µs/layer draw={}µs/layer "
                + "lines={} glyphs={} textDraws={} batches={} mergedRuns={} glyphRuns={}",
            perfTextFrames, String.format(java.util.Locale.ROOT, "%.1f", perfTextFrames / seconds),
            perfLayoutHits, perfLayoutMisses,
            String.format(java.util.Locale.ROOT, "%.1f", perfLayoutNanos / frames / 1000D),
            String.format(java.util.Locale.ROOT, "%.1f", perfDrawNanos / frames / 1000D),
            perfLines, perfGlyphs, perfTextDraws, perfBatches, perfMergedRuns, perfGlyphRuns);

        perfTextFrames = 0;
        perfLayoutHits = 0;
        perfLayoutMisses = 0;
        perfLayoutNanos = 0;
        perfDrawNanos = 0;
        perfLines = 0;
        perfGlyphs = 0;
        perfTextDraws = 0;
        perfBatches = 0;
        perfMergedRuns = 0;
        perfGlyphRuns = 0;
        perfLastLog = now;
    }

    private static CompTextAnimator selectAnimator(CompLayer layer, float progress, int index)
    {
        for (CompTextAnimator animator : layer.textAnimators)
        {
            if (!animator.enabled)
            {
                continue;
            }

            float p = progress;

            if (animator.randomOrder)
            {
                p = CompExpressionNoise.noise(index * 2.31F, animator.seed);
            }

            float start = animator.start + animator.offset;
            float end = animator.end + animator.offset;

            if (p >= start && p <= end)
            {
                return animator;
            }
        }

        return null;
    }

    private static int withAlpha(int color, float alpha)
    {
        return Colors.setA(color, Colors.getA(color) * Math.max(0F, Math.min(1F, alpha)));
    }

    /** Small local noise function so text animation does not depend on the expression parser. */
    static final class CompExpressionNoise
    {
        static float noise(float x, float seed)
        {
            double value = Math.sin(x * 12.9898D + seed * 78.233D) * 43758.5453D;

            return (float) (value - Math.floor(value));
        }
    }
}
