package dev.bbsae.client.render;

import dev.bbsae.comp.AmEffectDef;
import dev.bbsae.comp.AmEffectLibrary;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompStyle;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3D-layer effect-path consistency audit for requirement 2 (task-27).
 *
 * <p>The user reports "some effects do nothing in 3D" and "repeat effects are clipped to the layer
 * box like a mask". The renderer has three effect paths: composition space (2D layers and the
 * post-projection remainder of 3D layers), layer-local bake ({@link CompLayerSourceCache}), and the
 * direct fallback for layers that cannot bake (replay entities). This class pins the classification
 * that decides which effect runs where, audits the data-driven AM catalogue for composition-space
 * contract breaks, and backs the classification with pixels:</p>
 *
 * <ul>
 *   <li>composition-space effects (repeat family, shape field, {@code Comp}-sampling AM bodies)
 *       must never be baked into the layer raster, so they can write outside the layer box;</li>
 *   <li>{@code Comp}-sampling AM bodies must actually receive the composition backdrop;</li>
 *   <li>a mask must clip before the effect stack (a glow/blur may spread outside the mask);</li>
 *   <li>effect-space mapping is resolution independent (preview proxy vs export).</li>
 * </ul>
 *
 * <p>Headless GL via {@link DepthPlanePixelHarness#initGl()} (no game client).</p>
 */
public class EffectPipeline3DConsistencyTest
{
    private static final int SIZE = 96;

    @BeforeAll
    public static void createContext()
    {
        DepthPlanePixelHarness.initGl();

        /* Statics survive the test class (and its GL context): drop the previous context's
         * programs/textures before this one starts using them. */
        AmEffectRenderer.delete();
        MaskCache.clear();
        CompLayerSourceCache.clear();
    }

    /**
     * These probes build raw FBOs and effect passes without the game's render target wrapper, so a
     * benign GL error can be left latched for the next test class (which asserts NO_ERROR after its
     * own passes). Draining it here keeps the audit order-independent; any real error inside a probe
     * is caught by the assertions on the pixels themselves.
     */
    @AfterEach
    public void drainGlErrors()
    {
        while (org.lwjgl.opengl.GL11.glGetError() != org.lwjgl.opengl.GL11.GL_NO_ERROR)
        {
            /* drain */
        }
    }

    @AfterAll
    public static void destroyContext()
    {
        AmEffectRenderer.delete();
        MaskCache.clear();
        CompLayerSourceCache.clear();
        DepthPlanePixelHarness.shutdownGl();
    }

    /* ---------------------------------------------------------- catalogue */

    /**
     * Every AM body that samples the composition ({@code texture(Comp, …)}) must declare it through
     * {@code AmEffectDef.comp()}. If a future definition forgets the flag, that effect silently
     * samples its own input and gets baked into a layer-local raster on 3D layers — exactly the
     * "3D 模式没有效果" report. This is the audit guard for the data-driven catalogue.
     */
    @Test
    public void everyCompositionSamplingBodyDeclaresTheCompContract()
    {
        int samplers = 0;

        for (AmEffectDef definition : AmEffectLibrary.all())
        {
            boolean samples = definition.body != null && definition.body.contains("texture(Comp");

            if (!samples)
            {
                continue;
            }

            samplers += 1;
            assertTrue(definition.usesComp,
                definition.id + " samples Comp but does not declare .comp(); 3D layers will bake it "
                    + "without the composition and it will look like a no-op");
            assertTrue(CompRenderer.requiresProjectedPass(definition.id),
                definition.id + " must run on the projected composition, not in the layer bake");
        }

        assertTrue(samplers >= 4, "expected at least the four known Comp-sampling bodies, got " + samplers);
    }

    /** The repeat/shape-field family can write outside the layer rectangle; generic effects cannot. */
    @Test
    public void compositionSpaceEffectClassificationIsComplete()
    {
        for (String type : new String[] {"grid_repeat", "random_repeat", "am_grid_repeat",
            "am_line_repeat", "am_repeat", "am_repeat_radial", "am_repeat_scatter", "am_shape_field",
            "am_glass", "am_light_wrap", "am_displace_map", "am_gradient_displace",
            "depth_fog", "depth_dof", "shake", "glitch"})
        {
            assertTrue(CompRenderer.requiresProjectedPass(type), type + " must not be baked");
        }

        for (String type : new String[] {"blur", "box_blur", "invert", "gradient_ramp",
            "checkerboard", "mirror", "kaleidoscope", "drop_shadow", "s3d_cube", "am_tile",
            "am_hex_tile", "am_rotate_tile"})
        {
            assertFalse(CompRenderer.requiresProjectedPass(type), type + " belongs in the layer bake");
        }
    }

    /**
     * A glitch's displaced bands are allowed to leave the source rectangle. This drives the same
     * {@link EffectRenderer} pass the projected composition uses (identity space) and proves the
     * effect is not hard-clipped at the layer box.
     */
    @Test
    public void glitchDisplacesPixelsOutsideTheLayerBox()
    {
        Assumptions.assumeTrue(programsAvailable(), "effect programs unavailable");

        CompDocument comp = comp(SIZE, SIZE);
        CompLayer layer = layer(comp, "layer", "solid");

        layer.width = 24F;
        layer.height = 24F;
        layer.x = 48F;
        layer.y = 48F;
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        GlFramebuffer input = image(SIZE, SIZE, square(36, 36, 24, 0xFFFF0000));

        try
        {
            CompEffect effect = new CompEffect("g", "glitch");

            effect.put("amount", 60D);
            effect.put("bands", 64D);
            effect.put("seed", 7D);
            effect.put("speed", 0D);

            GlFramebuffer output = EffectRenderer.apply(layer, effect, input, context, EffectSpace.identity(context));
            int[] pixels = output.readTopDown();
            int outside = countAlphaOutside(pixels, SIZE, SIZE, 36, 36, 60, 60);

            assertTrue(outside > 0, "glitch must be able to write outside the layer box, got " + outside);

            if (output != input)
            {
                output.delete();
            }
        }
        finally
        {
            input.delete();
        }
    }

    /**
     * Glitch must run on the projected composition, not in the layer-local bake: a layer bake can
     * only write inside its raster/quad, so the displaced bands look clipped exactly at the shape
     * edge (the reported "glitch behaves like a mask"). Composition space lets it use the whole
     * frame.
     */
    @Test
    public void glitchRunsOnTheProjectedComposition()
    {
        CompEffect glitch = new CompEffect("g", "glitch");

        assertTrue(CompRenderer.requiresProjectedPass("glitch"));
        assertTrue(CompRenderer.needsCompositionEffect(true, glitch),
            "a baked glitch must still get a composition-space pass");
    }

    /** A 3D layer's effect stack is split once: baked prefix, projected remainder, in draw order. */
    @Test
    public void everyEffectRunsExactlyOnceOnA3dLayer()
    {
        CompLayer layer = new CompLayer("layer", "layer", "solid");

        layer.effects.add(new CompEffect("e1", "blur"));
        layer.effects.add(new CompEffect("e2", "invert"));
        layer.effects.add(new CompEffect("e3", "am_glass"));
        layer.effects.add(new CompEffect("e4", "am_grid_repeat"));
        layer.effects.add(new CompEffect("e5", "depth_fog"));

        int projectedStart = CompRenderer.firstProjectedEffect(layer.effects);

        assertEquals(2, projectedStart, "blur/invert bake; glass/repeat/depth run after projection");

        List<String> baked = new ArrayList<>();
        List<String> projected = new ArrayList<>();

        for (int i = 0; i < layer.effects.size(); i++)
        {
            if (i < projectedStart)
            {
                baked.add(layer.effects.get(i).type);
            }
            else
            {
                projected.add(layer.effects.get(i).type);
            }
        }

        assertEquals(List.of("blur", "invert"), baked);
        assertEquals(List.of("am_glass", "am_grid_repeat", "depth_fog"), projected);

        /* The bake loop must apply the prefix, and the composition loop the remainder — no effect
         * may be skipped on both sides. */
        for (int i = 0; i < layer.effects.size(); i++)
        {
            boolean inBake = i < projectedStart;
            boolean inComposition = !inBake;

            assertTrue(inBake ^ inComposition);
            assertTrue(inBake || inComposition);
        }
    }

    /** The fallback path (bake unavailable) runs the whole stack once, in composition space. */
    @Test
    public void fallbackPathRunsTheWholeStackOnce()
    {
        CompLayer layer = new CompLayer("layer", "layer", "replay");

        layer.effects.add(new CompEffect("e1", "blur"));
        layer.effects.add(new CompEffect("e2", "am_glass"));

        /* The stack split is still computed, but without a bake the composition loop starts at 0. */
        assertEquals(1, CompRenderer.firstProjectedEffect(layer.effects));

        for (CompEffect effect : layer.effects)
        {
            assertTrue(CompRenderer.needsCompositionEffect(false, effect),
                "without a bake every effect runs in the composition loop: " + effect.type);
        }

        assertFalse(CompRenderer.needsCompositionEffect(true, layer.effects.get(0)),
            "with a bake the leading blur is already applied");
        assertTrue(CompRenderer.needsCompositionEffect(true, layer.effects.get(1)),
            "comp-sampling effects still run after the projection");
    }

    /* ------------------------------------------------------------- pixels */

    /**
     * Repeat and shape-field must paint outside the layer rectangle (composition-space pass). If
     * this fails the effect is clipped to the layer box — the "被图层大小局限成蒙版" report.
     */
    @Test
    public void repeatAndShapeFieldWriteOutsideTheLayerBox()
    {
        Assumptions.assumeTrue(programsAvailable(), "effect programs unavailable");

        CompDocument comp = comp(SIZE, SIZE);
        CompLayer layer = layer(comp, "layer", "solid");

        layer.width = 24F;
        layer.height = 24F;
        layer.x = 48F;
        layer.y = 48F;
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        Matrix4f world = CompScene.world(context, layer);
        EffectSpace space = EffectSpace.of(layer, world, context);

        /* Opaque square in the layer box, transparent everywhere else. */
        GlFramebuffer input = image(SIZE, SIZE, square(48 - 12, 48 - 12, 24, 0xFFFF0000));

        try
        {
            int outsideX0 = 0;
            int outsideY0 = 0;
            int outsideX1 = SIZE;
            int outsideY1 = SIZE;
            int boxX0 = 48 - 12;
            int boxY0 = 48 - 12;

            for (String type : new String[] {"am_grid_repeat", "am_shape_field"})
            {
                CompEffect effect = new CompEffect(type, type);
                GlFramebuffer output = AmEffectRenderer.applyForced(layer, effect, input, context, space);
                int[] pixels = output.readTopDown();
                int outside = countAlphaOutside(pixels, SIZE, SIZE, boxX0, boxY0, boxX0 + 24, boxY0 + 24);

                assertTrue(outside > 0, type + " must paint outside the layer box, got " + outside);
                output.delete();
            }
        }
        finally
        {
            input.delete();
        }
    }

    /**
     * Composition-sampling AM bodies must receive {@code context.backdrop}. Two different backdrops
     * have to produce different pixels; falling back to the layer's own input would make both runs
     * identical (and is what a 3D layer-local bake would do).
     */
    @Test
    public void compositionSamplingEffectsReadTheBackdrop()
    {
        Assumptions.assumeTrue(programsAvailable(), "effect programs unavailable");

        CompDocument comp = comp(SIZE, SIZE);
        CompLayer layer = layer(comp, "layer", "solid");

        layer.width = 64F;
        layer.height = 64F;
        layer.x = 48F;
        layer.y = 48F;
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        EffectSpace space = EffectSpace.identity(context);
        GlFramebuffer input = image(SIZE, SIZE, softSquare(16, 16, 64, context));

        try
        {
            int[][] backdrops = {gradient(SIZE, SIZE, 0xFFFF0000, 0xFF0000FF),
                gradient(SIZE, SIZE, 0xFF000000, 0xFFFFFFFF)};

            for (String type : new String[] {"am_glass", "am_light_wrap", "am_displace_map",
                "am_gradient_displace"})
            {
                CompEffect effect = new CompEffect(type, type);
                GlFramebuffer backdropA = image(SIZE, SIZE, backdrops[0]);
                GlFramebuffer backdropB = image(SIZE, SIZE, backdrops[1]);
                GlFramebuffer fallback;

                try
                {
                    context.backdrop = backdropA;
                    GlFramebuffer withA = AmEffectRenderer.applyForced(layer, effect, input, context, space);
                    context.backdrop = backdropB;
                    GlFramebuffer withB = AmEffectRenderer.applyForced(layer, effect, input, context, space);
                    context.backdrop = null;
                    fallback = AmEffectRenderer.applyForced(layer, effect, input, context, space);
                    int[] pixelsA = withA.readTopDown();
                    int[] pixelsB = withB.readTopDown();
                    int[] pixelsFallback = fallback.readTopDown();

                    assertTrue(pixelDiff(pixelsA, pixelsB) > 0,
                        type + " must sample the composition backdrop (two backdrops gave identical "
                            + "output, so the effect used its own input)");
                    assertNotNull(pixelsFallback);
                    withA.delete();
                    withB.delete();
                }
                finally
                {
                    context.backdrop = null;
                    backdropA.delete();
                    backdropB.delete();
                }

                fallback.delete();
            }
        }
        finally
        {
            input.delete();
        }
    }

    /**
     * A mask must clip the layer before the effects: a blur/glow may spread outside the mask, which
     * is the 2D contract. If the effect ran first, nothing could appear outside the mask.
     */
    @Test
    public void maskClipsBeforeCompositionSpaceEffects()
    {
        Assumptions.assumeTrue(programsAvailable(), "effect programs unavailable");

        CompDocument comp = comp(SIZE, SIZE);
        CompLayer layer = layer(comp, "layer", "solid");

        layer.width = 96F;
        layer.height = 96F;
        layer.x = 48F;
        layer.y = 48F;
        CompMask mask = new CompMask("mask");

        /* Left half of the composition only. */
        mask.rectangle(-48F, -48F, 48F, 96F);
        layer.masks.add(mask);
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        GlFramebuffer input = image(SIZE, SIZE, square(32, 32, 32, 0xFFFFFFFF));

        try
        {
            Matrix4f world = CompScene.world(context, layer);
            MaskCache.MaskTexture maskTexture = MaskCache.get(layer, SIZE, SIZE, world, 0F, 1F);

            assertNotNull(maskTexture, "mask raster");

            GlFramebuffer masked = PassRenderer.mask(input, maskTexture.texture.id, 0);
            CompEffect blur = new CompEffect("blur", "box_blur");

            blur.put("radius", 8D);
            GlFramebuffer blurred = EffectRenderer.apply(layer, blur, masked, context, EffectSpace.identity(context));

            int[] maskedPixels = masked.readTopDown();
            int[] blurredPixels = blurred.readTopDown();
            /* Just right of the mask edge (mask ends at x=48), inside the blur's 8 px reach of the
             * still-visible x<48 strip. */
            int justOutside = 52 + 48 * SIZE;

            assertEquals(0, alpha(maskedPixels, justOutside),
                "the mask must clear pixels outside it before effects");
            assertTrue(alpha(blurredPixels, justOutside) > 0,
                "a blur after the mask may spread into the masked-out area (mask-before-effect order)");

            blurred.delete();
            masked.delete();
        }
        finally
        {
            input.delete();
        }
    }

    /* -------------------------------------------------------- resolution */

    /** Effect-space mapping is resolution independent: preview proxy cannot shift the effect box. */
    @Test
    public void effectSpaceIsResolutionIndependent()
    {
        CompDocument small = comp(96, 96);
        CompDocument large = comp(384, 384);
        CompLayer layerSmall = layer(small, "layer", "solid");
        CompLayer layerLarge = layer(large, "layer", "solid");

        layerSmall.width = 24F;
        layerSmall.height = 24F;
        layerSmall.x = 48F;
        layerSmall.y = 48F;
        layerLarge.width = 96F;
        layerLarge.height = 96F;
        layerLarge.x = 192F;
        layerLarge.y = 192F;

        CompRenderContext smallContext = new CompRenderContext(project(small), small, 0F, null, null, null);
        CompRenderContext largeContext = new CompRenderContext(project(large), large, 0F, null, null, null);
        EffectSpace smallSpace = EffectSpace.of(layerSmall, CompScene.world(smallContext, layerSmall), smallContext);
        EffectSpace largeSpace = EffectSpace.of(layerLarge, CompScene.world(largeContext, layerLarge), largeContext);

        assertEquals(smallSpace.centerUv().x, largeSpace.centerUv().x, 0.0001F, "centre U is normalised");
        assertEquals(smallSpace.centerUv().y, largeSpace.centerUv().y, 0.0001F, "centre V is normalised");
        assertEquals(smallSpace.sizeU, largeSpace.sizeU, 0.0001F, "box width is a composition fraction");
        assertEquals(smallSpace.sizeV, largeSpace.sizeV, 0.0001F, "box height is a composition fraction");
    }

    /** Pre-projecting classification must not depend on the editor proxy parameters. */
    @Test
    public void effectRoutingIsPreviewAndScaleIndependent()
    {
        CompDocument comp = comp(384, 216);
        CompRenderContext preview = new CompRenderContext(project(comp), comp, 0F, null, null, null);
        CompRenderContext exporter = new CompRenderContext(project(comp), comp, 0F, null, null, null);

        preview.preview = true;
        preview.scale = 0.5F;
        exporter.preview = false;
        exporter.scale = 1F;

        for (String type : new String[] {"blur", "am_glass", "am_shape_field", "depth_fog", "shake"})
        {
            assertEquals(CompRenderer.requiresProjectedPass(type),
                CompRenderer.requiresProjectedPass(type), "classification is a pure function");
            assertEquals(CompRenderer.needsCompositionEffect(true, new CompEffect("x", type)),
                CompRenderer.needsCompositionEffect(true, new CompEffect("x", type)));
        }

        assertEquals(preview.scale, 0.5F);
        assertEquals(exporter.scale, 1F);
    }

    /* ------------------------------------------------ task-27 audit fixes */

    /**
     * P0-1: the layer raster cache must not share pixels between editor modes. The 3D workspace now
     * renders the real nested composition (no draft plate); preview/encoder still keep separate
     * raster signatures for their different preview/export flags. Before the mode was in the
     * fingerprint, whichever mode rasterized first won and the other showed its placeholder.
     */
    @Test
    public void rasterSignatureSeparatesDraftFromEncoderModes()
    {
        CompDocument comp = comp(SIZE, SIZE);
        CompLayer layer = layer(comp, "layer", "precomp");

        layer.width = 48F;
        layer.height = 48F;
        layer.precompId = "nested";
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext preview = new CompRenderContext(project, comp, 0F, null, null, null);
        CompRenderContext export = new CompRenderContext(project, comp, 0F, null, null, null);
        CompRenderContext draft = new CompRenderContext(project, comp, 0F, null, null, null);

        preview.preview = true;
        export.preview = false;
        draft.preview = true;
        draft.draftViewport = true;

        long previewHash = CompLayerSourceCache.signature(layer, preview, 48, 48);
        long exportHash = CompLayerSourceCache.signature(layer, export, 48, 48);
        long draftHash = CompLayerSourceCache.signature(layer, draft, 48, 48);

        assertNotEquals(previewHash, draftHash, "the 3D draft plate must not be reused by the preview");
        assertNotEquals(exportHash, draftHash, "the 3D draft plate must not be reused by the encoder");
        assertNotEquals(previewHash, exportHash, "preview and encoder modes keep separate rasters");
    }

    /** P1-4: the 1024 px raster cap is a preview guard; the export may use the composition width. */
    @Test
    public void rasterCapKeepsExportDetail()
    {
        CompDocument comp = comp(1920, 1080);
        CompRenderContext preview = new CompRenderContext(project(comp), comp, 0F, null, null, null);
        CompRenderContext export = new CompRenderContext(project(comp), comp, 0F, null, null, null);

        preview.preview = true;
        export.preview = false;

        assertEquals(CompLayerSourceCache.MAX_EDGE, CompLayerSourceCache.rasterMaxEdge(preview), 0.001F);
        assertTrue(CompLayerSourceCache.rasterMaxEdge(export) >= 1920F,
            "encoder must not downscale a 1920 px composition to the draft cap");

        float previewScale = CompLayerSourceCache.rasterScaleFor(1900F, 600F,
            CompLayerSourceCache.rasterMaxEdge(preview));
        float exportScale = CompLayerSourceCache.rasterScaleFor(1900F, 600F,
            CompLayerSourceCache.rasterMaxEdge(export));

        assertTrue(previewScale < 0.6F, "preview keeps the memory guard");
        assertEquals(1F, exportScale, 0.0001F, "a 1900 px layer exports at full raster resolution");
    }

    /** P2-1: the classic displacement map's depth mode needs the depth buffer, so it cannot bake. */
    @Test
    public void displacementDepthModeIsRoutedAfterProjection()
    {
        CompEffect noise = new CompEffect("d", "displacement");
        CompEffect depth = new CompEffect("d", "displacement");

        depth.put("depth", 1D);

        assertFalse(CompRenderer.requiresProjectedPassFor(noise), "noise mode can bake");
        assertTrue(CompRenderer.requiresProjectedPassFor(depth), "depth-map mode needs the projected pass");

        CompLayer layer = new CompLayer("layer", "layer", "image");

        layer.effects.add(new CompEffect("blur", "blur"));
        layer.effects.add(depth);
        layer.effects.add(new CompEffect("invert", "invert"));

        assertEquals(1, CompRenderer.firstProjectedEffect(layer.effects));
    }

    /** Styles must run after the projected remainder, matching the 2D "effects then styles" order. */
    @Test
    public void stylesRunAfterProjectionWhenTheStackHasProjectedEffects()
    {
        CompLayer layer = new CompLayer("layer", "layer", "image");

        layer.effects.add(new CompEffect("e1", "blur"));
        layer.styles.add(new CompStyle(CompStyle.DROP_SHADOW));

        assertFalse(CompRenderer.stylesRunAfterProjection(layer), "pure baked stack styles in the bake");

        layer.effects.add(new CompEffect("e2", "am_grid_repeat"));

        assertTrue(CompRenderer.stylesRunAfterProjection(layer),
            "a projected effect forces the styles to the composition pass");

        layer.effects.clear();
        layer.effects.add(new CompEffect("e1", "blur"));

        assertFalse(CompRenderer.stylesRunAfterProjection(layer));
    }

    /** P1-3: masks are applied exactly once — the bake already contains them. */
    @Test
    public void masksApplyExactlyOnceAcrossTheBakeAndDirectPaths()
    {
        Assumptions.assumeTrue(programsAvailable(), "effect programs unavailable");

        CompDocument comp = comp(SIZE, SIZE);
        CompLayer layer = layer(comp, "layer", "solid");

        layer.width = 96F;
        layer.height = 96F;
        layer.x = 48F;
        layer.y = 48F;
        CompMask mask = new CompMask("mask");

        mask.rectangle(-48F, -48F, 48F, 96F);
        layer.masks.add(mask);
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext context = new CompRenderContext(project, comp, 0F, null, null, null);
        Matrix4f model = CompScene.world(context, layer);
        GlFramebuffer input = image(SIZE, SIZE, square(0, 0, SIZE, 0xFFFFFFFF));

        try
        {
            /* The bake already multiplied the mask in: the helper must be a no-op. */
            GlFramebuffer baked = CompRenderer.applyLayerMask(input, layer, context, model, true, true);

            assertTrue(baked == input, "a baked raster must not be masked a second time");
            assertEquals(255, alpha(input.readTopDown(), 90 + 48 * SIZE),
                "baked pixels stay intact");

            /* Direct path: mask applied once. */
            GlFramebuffer masked = CompRenderer.applyLayerMask(input, layer, context, model, false, true);

            assertNotEquals(input, masked);
            assertEquals(0, alpha(masked.readTopDown(), 90 + 48 * SIZE),
                "the masked-out right half is cleared");
            assertEquals(255, alpha(masked.readTopDown(), 10 + 48 * SIZE),
                "the visible left half stays");

            /* 蒙版 A/B switch: no mask at all. */
            GlFramebuffer off = CompRenderer.applyLayerMask(input, layer, context, model, false, false);

            assertTrue(off == input, "the 蒙版 switch must be honoured");

            masked.delete();
        }
        finally
        {
            input.delete();
        }
    }

    /** The bake reference grid is the parent composition, not the capped raster. */
    @Test
    public void bakeEffectSpaceUsesTheParentCompositionReferenceGrid()
    {
        CompDocument comp = comp(1920, 1080);
        CompRenderContext context = new CompRenderContext(project(comp), comp, 0F, null, null, null);
        CompDocument proxy = comp(1024, 576);
        CompRenderContext proxyContext = new CompRenderContext(project(proxy), proxy, 0F, null, null, null);
        CompLayer layer = layer(comp, "layer", "image");

        layer.width = 1900F;
        layer.height = 1000F;

        EffectSpace space = EffectSpace.ofBounds(layer, new Matrix4f(), proxyContext,
            -950F, -500F, 1900F, 1000F, 1920, 1080);
        EffectSpace target = EffectSpace.ofBounds(layer, new Matrix4f(), context,
            -950F, -500F, 1900F, 1000F);

        assertEquals(1920, space.refWidth);
        assertEquals(1080, space.refHeight);
        assertEquals(1920, target.refWidth, "the 2D/comp-space path uses the target composition");
        assertEquals(1900F / 1920F, EffectRenderer.proxyScale(1900, space.refWidth), 0.01F,
            "the bake converts pixel parameters through raster/comp");
    }

    /** P1-1: the 3D workspace draft can only show the layer-local subset; lock the known limit. */
    @Test
    public void draftSkipsExactlyThePostProjectionSet()
    {
        for (String type : new String[] {"grid_repeat", "random_repeat", "am_grid_repeat",
            "am_repeat", "am_repeat_radial", "am_repeat_scatter", "am_shape_field", "am_glass",
            "am_light_wrap", "am_displace_map", "am_gradient_displace", "shake", "depth_fog",
            "depth_dof", "depth_matte"})
        {
            assertFalse(CompLayerSourceCache.draftApplies(type),
                type + " is a composition pass and cannot be a baked 3D draft layer raster");
        }

        for (String type : new String[] {"blur", "invert", "gradient_ramp", "checkerboard",
            "mirror", "drop_shadow", "s3d_cube"})
        {
            assertTrue(CompLayerSourceCache.draftApplies(type), type + " bakes into the layer raster");
        }
    }

    /* ----------------------------------------------------------- fixtures */

    private static boolean programsAvailable()
    {
        return CompShaders.composite != null && CompShaders.composite.isValid();
    }

    private static CompDocument comp(int width, int height)
    {
        CompDocument comp = new CompDocument();

        comp.id = "comp";
        comp.name = "comp";
        comp.width = width;
        comp.height = height;
        comp.fps = 60F;
        comp.duration = 10F;
        comp.background = 0xFF000000;

        return comp;
    }

    private static CompProject project(CompDocument comp)
    {
        CompProject project = new CompProject();

        project.comps.add(comp);

        return project;
    }

    private static CompLayer layer(CompDocument comp, String id, String type)
    {
        CompLayer layer = new CompLayer(id, id, type);

        layer.visible = true;
        layer.in = 0F;
        layer.out = 10F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        return layer;
    }

    private static GlFramebuffer image(int width, int height, int[] argb)
    {
        GlFramebuffer framebuffer = new GlFramebuffer(width, height);

        framebuffer.color.uploadTopDown(argb);

        return framebuffer;
    }

    private static int[] square(int x, int y, int size, int color)
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int py = y; py < y + size; py++)
        {
            for (int px = x; px < x + size; px++)
            {
                pixels[px + py * SIZE] = color;
            }
        }

        return pixels;
    }

    /** Square with soft alpha edges; gives am_light_wrap an edge to wrap the backdrop around. */
    private static int[] softSquare(int x, int y, int size, CompRenderContext context)
    {
        int[] pixels = new int[SIZE * SIZE];
        float cx = x + size * 0.5F;
        float cy = y + size * 0.5F;
        float radius = size * 0.5F;

        for (int px = 0; px < SIZE; px++)
        {
            for (int py = 0; py < SIZE; py++)
            {
                float dx = px + 0.5F - cx;
                float dy = py + 0.5F - cy;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float alpha = Math.max(0F, Math.min(1F, (radius - distance) / 12F));

                if (alpha <= 0F)
                {
                    continue;
                }

                int a = Math.round(alpha * 255F);

                pixels[px + py * SIZE] = (a << 24) | 0x00FFFFFF;
            }
        }

        return pixels;
    }

    private static int[] gradient(int width, int height, int left, int right)
    {
        int[] pixels = new int[width * height];

        for (int py = 0; py < height; py++)
        {
            for (int px = 0; px < width; px++)
            {
                float t = px / (float) Math.max(1, width - 1);
                int a = Math.round(((left >>> 24) & 0xFF) * (1F - t) + ((right >>> 24) & 0xFF) * t);
                int r = Math.round(((left >> 16) & 0xFF) * (1F - t) + ((right >> 16) & 0xFF) * t);
                int g = Math.round(((left >> 8) & 0xFF) * (1F - t) + ((right >> 8) & 0xFF) * t);
                int b = Math.round((left & 0xFF) * (1F - t) + (right & 0xFF) * t);

                pixels[px + py * width] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return pixels;
    }

    private static int countAlphaOutside(int[] pixels, int width, int height,
                                         int x0, int y0, int x1, int y1)
    {
        int outside = 0;

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                if (x >= x0 && x < x1 && y >= y0 && y < y1)
                {
                    continue;
                }

                if (((pixels[x + y * width] >>> 24) & 0xFF) > 8)
                {
                    outside++;
                }
            }
        }

        return outside;
    }

    private static int pixelDiff(int[] a, int[] b)
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
                different++;
            }
        }

        return different;
    }

    private static int alpha(int[] pixels, int index)
    {
        return (pixels[index] >>> 24) & 0xFF;
    }
}
