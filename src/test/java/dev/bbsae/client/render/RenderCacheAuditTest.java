package dev.bbsae.client.render;

import dev.bbsae.client.export.CompExportManager;
import dev.bbsae.comp.CompCurves;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.TextContent;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the render-cache audit fix batch (task-21).
 *
 * <p>Pure-logic tests only (no GL): precomp content in the render fingerprint (P1-A), draft-plate
 * gating (P1-B), direct-path mask projection math (P1-C), depth-effect degradation detection
 * (P1-D), and the P2 items (frame cleanup, fps mapping, precache raster size, text-content
 * fingerprint, keyframed z in {@code CompScene.hasDepth}).</p>
 */
public class RenderCacheAuditTest
{
    /* ------------------------------------------------------------ fixtures */

    private static CompDocument comp(String id)
    {
        CompDocument comp = new CompDocument();

        comp.id = id;
        comp.name = id;
        comp.width = 1920;
        comp.height = 1080;
        comp.fps = 60F;
        comp.duration = 10F;

        return comp;
    }

    private static CompLayer solid(String id)
    {
        CompLayer layer = new CompLayer(id, id, "solid");

        layer.visible = true;
        layer.in = 0F;
        layer.out = 10F;
        layer.width = 320F;
        layer.height = 180F;

        return layer;
    }

    private static CompLayer precomp(String id, String target)
    {
        CompLayer layer = new CompLayer(id, id, "precomp");

        layer.visible = true;
        layer.in = 0F;
        layer.out = 10F;
        layer.width = 800F;
        layer.height = 450F;
        layer.precompId = target;

        return layer;
    }

    private static CompProject project(CompDocument... comps)
    {
        CompProject project = new CompProject();

        for (CompDocument comp : comps)
        {
            project.comps.add(comp);
        }

        return project;
    }

    private static long signature(CompProject project, CompDocument comp)
    {
        return CompRenderCache.signature(project, comp, 0F, true, true, 0, comp.width, comp.height, false);
    }

    private static CompRenderContext context(CompProject project, CompDocument comp, float time)
    {
        return new CompRenderContext(project, comp, time, null, null, null);
    }

    /* -------------------------------------------- P1-A: precomp in fingerprint */

    @Test
    public void editingAChildCompositionInvalidatesTheParentFrame()
    {
        CompDocument parent = comp("main");
        CompDocument child = comp("child");
        CompLayer childLayer = solid("c1");

        child.addLayer(childLayer);
        parent.addLayer(precomp("pc", "child"));

        CompProject project = project(parent, child);
        long before = signature(project, parent);

        childLayer.x += 25F;

        assertNotEquals(before, signature(project, parent),
            "editing a precomp layer must invalidate the parent composition's cached frame");
    }

    @Test
    public void editingAChildKeyframeInvalidatesTheParentFrame()
    {
        CompDocument parent = comp("main");
        CompDocument child = comp("child");
        CompLayer childLayer = solid("c1");

        child.addLayer(childLayer);
        parent.addLayer(precomp("pc", "child"));

        CompProject project = project(parent, child);
        long before = signature(project, parent);

        childLayer.insertAxisKey(CompCurves.AXIS_X, 0F, 123F);

        assertNotEquals(before, signature(project, parent),
            "a keyframe added inside a precomp must invalidate the parent frame");
    }

    @Test
    public void nestedPrecompEditsPropagateToTheTopLevel()
    {
        CompDocument top = comp("top");
        CompDocument middle = comp("middle");
        CompDocument leaf = comp("leaf");
        CompLayer leafLayer = solid("leaf-layer");

        leaf.addLayer(leafLayer);
        middle.addLayer(precomp("to-leaf", "leaf"));
        top.addLayer(precomp("to-middle", "middle"));

        CompProject project = project(top, middle, leaf);
        long before = signature(project, top);

        leafLayer.color = 0xFF00FF00;

        assertNotEquals(before, signature(project, top),
            "an edit two precomp levels down must still invalidate the top composition");
    }

    @Test
    public void cyclicPrecompGraphsTerminateAndStaySensitive()
    {
        CompDocument a = comp("a");
        CompDocument b = comp("b");
        CompLayer bInA = precomp("b-in-a", "b");
        CompLayer aInB = precomp("a-in-b", "a");
        CompLayer bLayer = solid("b-layer");

        a.addLayer(bInA);
        a.addLayer(bLayer);
        b.addLayer(aInB);

        CompProject project = project(a, b);
        long first = signature(project, a);
        long second = signature(project, a);

        assertEquals(first, second, "a cyclic precomp graph must produce a stable signature");

        bLayer.y += 10F;

        assertNotEquals(first, signature(project, a),
            "edits inside the cycle must still be visible to the fingerprint");
    }

    @Test
    public void unrelatedCompositionsDoNotInvalidateTheFrame()
    {
        CompDocument parent = comp("main");
        CompDocument child = comp("child");
        CompDocument unrelated = comp("unrelated");
        CompLayer unrelatedLayer = solid("u1");

        unrelated.addLayer(unrelatedLayer);
        child.addLayer(solid("c1"));
        parent.addLayer(precomp("pc", "child"));

        CompProject project = project(parent, child, unrelated);
        long before = signature(project, parent);

        unrelatedLayer.x += 500F;

        assertEquals(before, signature(project, parent),
            "a composition the parent does not reference must not invalidate its frame");
    }

    @Test
    public void deepPrecompChainsTerminate()
    {
        CompProject project = new CompProject();
        CompDocument previous = comp("c0");

        project.comps.add(previous);

        for (int i = 1; i <= 12; i++)
        {
            CompDocument current = comp("c" + i);

            current.addLayer(precomp("pc", previous.id));
            project.comps.add(current);
            previous = current;
        }

        long first = signature(project, previous);
        long second = signature(project, previous);

        assertEquals(first, second, "more than 8 precomp levels must terminate deterministically");
    }

    /* ------------------------------------- P2-5: text content in fingerprints */

    @Test
    public void textContentFieldsInvalidateTheFrameFingerprint()
    {
        CompDocument comp = comp("main");
        CompLayer text = new CompLayer("text", "text", "text");

        text.visible = true;
        text.in = 0F;
        text.out = 10F;
        text.text = "HELLO";
        comp.addLayer(text);

        CompProject project = project(comp);
        long before = signature(project, comp);

        text.textProgress = 0.4F;

        assertNotEquals(before, signature(project, comp),
            "typewriter progress must invalidate the parked frame");

        long progressFrame = signature(project, comp);

        text.textRandom = 0.5F;
        text.textRandomSeed = 7;

        assertNotEquals(progressFrame, signature(project, comp),
            "glyph scramble settings must invalidate the parked frame");
    }

    @Test
    public void textContentTimeDependenceIsDetected()
    {
        CompLayer text = new CompLayer("text", "text", "text");

        assertFalse(TextContent.isTimeDependent(text), "plain text is static");

        text.textMode = 1;
        assertTrue(TextContent.isTimeDependent(text), "timecode changes every frame");

        text.textMode = 2;
        assertTrue(TextContent.isTimeDependent(text), "counter changes every frame");

        text.textMode = 0;
        text.textRandom = 0.5F;
        assertTrue(TextContent.isTimeDependent(text), "glyph scramble changes every frame");

        text.textRandom = 0F;
        text.textProgress = 0.5F;
        assertTrue(TextContent.isTimeDependent(text), "a typewriter reveal is frame dependent");
    }

    @Test
    public void layerSourceFingerprintSeesTextContent()
    {
        CompDocument comp = comp("main");
        CompLayer text = new CompLayer("text", "text", "text");

        text.visible = true;
        text.text = "HELLO";
        comp.addLayer(text);

        CompProject project = project(comp);
        CompRenderContext context = context(project, comp, 0F);
        long before = CompLayerSourceCache.signature(text, context, 512, 288);

        text.textProgress = 0.3F;

        assertNotEquals(before, CompLayerSourceCache.signature(text, context, 512, 288),
            "the layer raster fingerprint must include content-level text fields");
    }

    @Test
    public void layerSourceFingerprintSeesPrecompContent()
    {
        CompDocument parent = comp("main");
        CompDocument child = comp("child");
        CompLayer childLayer = solid("c1");
        CompLayer precompLayer = precomp("pc", "child");

        child.addLayer(childLayer);
        parent.addLayer(precompLayer);

        CompProject project = project(parent, child);
        CompRenderContext context = context(project, parent, 0F);
        long before = CompLayerSourceCache.signature(precompLayer, context, 512, 288);

        childLayer.width += 60F;

        assertNotEquals(before, CompLayerSourceCache.signature(precompLayer, context, 512, 288),
            "a precomp layer's raster fingerprint must include the nested composition's content");
    }

    @Test
    public void cyclicPrecompLayerSourceFingerprintTerminates()
    {
        CompDocument a = comp("a");
        CompDocument b = comp("b");
        CompLayer bInA = precomp("b-in-a", "b");
        CompLayer aInB = precomp("a-in-b", "a");

        a.addLayer(bInA);
        b.addLayer(aInB);

        CompProject project = project(a, b);
        CompRenderContext context = context(project, a, 0F);
        long first = CompLayerSourceCache.signature(bInA, context, 512, 288);

        assertEquals(first, CompLayerSourceCache.signature(bInA, context, 512, 288),
            "a cyclic precomp must produce a stable layer fingerprint");
    }

    /* ------------------------------------------- P1-B: draft plate gating */

    @Test
    public void precompPlateIsOnlyAllowedInTheDraftViewport()
    {
        CompDocument comp = comp("main");
        CompLayer precompLayer = precomp("pc", "child");

        comp.addLayer(precompLayer);

        CompProject project = project(comp, comp("child"));
        CompRenderContext editorial = context(project, comp, 0F);

        editorial.preview = true;
        editorial.draftViewport = false;

        assertFalse(CompLayerSourceCache.wantsPrecompPlate(precompLayer, editorial),
            "the composition panel preview must render the real nested composition");

        editorial.draftViewport = true;
        assertTrue(CompLayerSourceCache.wantsPrecompPlate(precompLayer, editorial),
            "the 3D workspace draft may use the cheap plate");

        editorial.preview = false;
        assertFalse(CompLayerSourceCache.wantsPrecompPlate(precompLayer, editorial),
            "the encoder must never bake the draft plate");

        assertFalse(CompLayerSourceCache.wantsPrecompPlate(solid("shape"), editorial),
            "only precomp layers have a plate");
    }

    @Test
    public void renderContextDefaultsAreEditorFriendly()
    {
        CompDocument comp = comp("main");

        CompRenderContext context = context(project(comp), comp, 0F);

        assertTrue(context.preview, "a context defaults to preview mode");
        assertFalse(context.draftViewport, "a context defaults to the real render, not the draft plate");
    }

    /* ------------------------------------------------- P1-C: mask projection */

    @Test
    public void projectedMaskFollowsThePerspectiveCamera()
    {
        CompCamera camera = CompCamera.identity(1920F, 1080F);
        Matrix4f world = new Matrix4f().translate(960F, 540F, 300F);
        Matrix4f viewProjection = new Matrix4f(camera.projection).mul(camera.view).mul(world);
        CompMask mask = new CompMask("mask");

        mask.rectangle(-100F, -100F, 200F, 200F);

        List<CompMask> masks = List.of(mask);
        float[] alpha = MaskCache.alphaForProjected(masks, 96, 54, viewProjection);

        assertNotNull(alpha, "the projected mask must rasterize");

        int center = (54 / 2) * 96 + 96 / 2;
        int corner = 1 * 96 + 1;

        assertTrue(alpha[center] > 0.9F, "a point inside the projected mask must be covered");
        assertTrue(alpha[corner] < 0.1F, "a point outside the projected mask must not be covered");
    }

    @Test
    public void projectedMaskFollowsARotatedLayer()
    {
        CompCamera camera = CompCamera.identity(1920F, 1080F);
        Matrix4f world = new Matrix4f()
            .translate(960F, 540F, 300F)
            .rotateY((float) Math.toRadians(35F));
        Matrix4f viewProjection = new Matrix4f(camera.projection).mul(camera.view).mul(world);
        CompMask mask = new CompMask("mask");

        mask.rectangle(-100F, -100F, 200F, 200F);

        float[] alpha = MaskCache.alphaForProjected(List.of(mask), 96, 54, viewProjection);

        assertNotNull(alpha, "the rotated 3D mask must rasterize");
        assertTrue(alpha[(54 / 2) * 96 + 96 / 2] > 0.9F,
            "the mask centre must stay on the layer centre under rotation");
    }

    /* ------------------------------------------- P1-D: depth degradation */

    @Test
    public void depthEffectsAreDetectedForTheDegradationNotice()
    {
        CompDocument comp = comp("main");
        CompLayer layer = solid("layer");

        comp.addLayer(layer);

        CompProject project = project(comp);

        assertFalse(CompRenderer.needsWorldDepth(project, comp), "no depth effect, no world depth need");

        CompEffect depthFog = new CompEffect("depth_fog", "depth_fog");

        depthFog.enabled = true;
        layer.effects.add(depthFog);

        assertTrue(CompRenderer.needsWorldDepth(project, comp), "depth_* effects need the world depth");

        project.settings.depthEnabled = false;

        assertFalse(CompRenderer.needsWorldDepth(project, comp),
            "the project depth switch must win over the effect list");

        CompRenderer.resetDepthEffectsStatus();
        assertFalse(CompRenderer.isDepthEffectsDegraded(), "status starts clean");
        assertNull(CompRenderer.depthEffectsDegradedMessage(), "no message while depth is available");
    }

    /* ------------------------------------ P2-2/3/4: size, fps, stage helpers */

    @Test
    public void rendererAndPrecacheAgreeOnTheRasterSize()
    {
        int[][] sizes = {{1920, 1080}, {1917, 1079}, {1281, 721}, {333, 187}};

        for (int[] size : sizes)
        {
            CompDocument comp = comp("comp");

            comp.width = size[0];
            comp.height = size[1];

            float scale = CompPrecache.cacheScale(comp);
            int[] expected = CompRenderer.renderSizeFor(comp, scale);
            int[] actual = CompPrecache.cacheSize(comp);

            assertEquals(expected[0], actual[0], "precache width for " + size[0] + "x" + size[1]);
            assertEquals(expected[1], actual[1], "precache height for " + size[0] + "x" + size[1]);
            assertEquals(expected[0], CompFrameServer.widthOf(comp, scale), "frame server width");
            assertEquals(expected[1], CompFrameServer.heightOf(comp, scale), "frame server height");
        }
    }

    @Test
    public void exportFrameMappingIsDeterministicWhenPresetFpsDiffers()
    {
        CompDocument comp = comp("main");

        /* 24 fps output over a 60 fps composition: comp frame = round(N / 24 * 60). */
        int[] expected = {0, 3, 5, 8, 10, 13};

        for (int i = 0; i < expected.length; i++)
        {
            assertEquals(expected[i], CompExportManager.sourceFrame(comp, 0F, i, 24F),
                "output frame " + i + " at 24 fps");
        }

        /* Upsampling must repeat frames, not interpolate: 120 fps output holds each comp frame twice. */
        assertEquals(0, CompExportManager.sourceFrame(comp, 0F, 0, 120F));
        assertEquals(1, CompExportManager.sourceFrame(comp, 0F, 1, 120F));
        assertEquals(1, CompExportManager.sourceFrame(comp, 0F, 2, 120F));
        assertEquals(2, CompExportManager.sourceFrame(comp, 0F, 3, 120F));
        assertEquals(2, CompExportManager.sourceFrame(comp, 0F, 4, 120F));

        /* A non-zero range start offsets the composition frame grid. */
        assertEquals(90, CompExportManager.sourceFrame(comp, 1.5F, 0, 60F));
        assertEquals(91, CompExportManager.sourceFrame(comp, 1.5F, 1, 60F));
    }

    @Test
    public void previewStageTransparencyFollowsTheEncoderMode()
    {
        CompProject project = project(comp("main"));

        project.settings.previewBackground = 0;
        assertFalse(CompRenderer.previewStageTransparent(project), "opaque preview by default");

        project.settings.previewBackground = 1;
        project.settings.encoder = CompProject.ENCODER_BBS;
        assertTrue(CompRenderer.previewStageTransparent(project),
            "BBS-mode preview can show the world through a transparent stage");

        project.settings.encoder = CompProject.ENCODER_AE;
        assertFalse(CompRenderer.previewStageTransparent(project),
            "the AE encoder's frame must keep its exact stage");

        project.settings.previewBackground = 0;
        project.settings.encoder = CompProject.ENCODER_BBS;
        assertFalse(CompRenderer.previewStageTransparent(project));
    }

    /* ---------------------------------------- P2-1: stale export frames */

    @Test
    public void staleExportFramesAreRemovedButOtherFilesSurvive() throws Exception
    {
        Path folder = Files.createTempDirectory("bbsae-export-frames");

        try
        {
            Files.writeString(folder.resolve("frame_000001.png"), "old", StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("frame_000999.png"), "old", StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("keep.png"), "keep", StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("audio.wav"), "audio", StandardCharsets.UTF_8);

            CompExportManager.clearStaleFrames(folder);

            assertFalse(Files.exists(folder.resolve("frame_000001.png")), "stale frame 1 removed");
            assertFalse(Files.exists(folder.resolve("frame_000999.png")), "stale frame 999 removed");
            assertTrue(Files.exists(folder.resolve("keep.png")), "unrelated png kept");
            assertTrue(Files.exists(folder.resolve("audio.wav")), "audio mix kept");
        }
        finally
        {
            try (java.util.stream.Stream<Path> stream = Files.list(folder))
            {
                stream.forEach((path) ->
                {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (Exception ignored)
                    {
                    }
                });
            }

            Files.deleteIfExists(folder);
        }
    }

    /* ----------------------------------- P2-6: keyframed z in hasDepth */

    @Test
    public void keyframedZPutsTheFrameIntoPerspectiveMode()
    {
        CompDocument comp = comp("main");
        CompLayer camera = new CompLayer("camera", "camera", "camera");

        camera.visible = true;
        camera.in = 0F;
        camera.out = 10F;
        camera.threeD = true;
        camera.camera3D = true;
        camera.cameraZ = 1370F;
        camera.cameraFov = 50F;
        camera.cameraStyle = CompLayer.CAMERA_BBS;
        camera.cameraLookAt = false;
        camera.x = 960F;
        camera.y = 540F;
        comp.addLayer(camera);
        comp.activeCameraId = "camera";

        CompLayer layer = solid("layer3d");

        layer.threeD = true;
        layer.z = 0F;
        layer.width = 320F;
        layer.height = 180F;
        layer.insertAxisKey(CompCurves.AXIS_Z, 0F, 0F);
        layer.insertAxisKey(CompCurves.AXIS_Z, 100F, 60F);
        comp.addLayer(layer);

        CompProject project = project(comp);
        CompRenderContext atZero = context(project, comp, 0F);
        CompRenderContext atDepth = context(project, comp, 2F);

        assertFalse(CompScene.spatial(atZero),
            "z = 0 at this frame: the scene is flat and must not switch to perspective");
        assertTrue(CompScene.spatial(atDepth),
            "the animated z channel leaves the comp plane at this frame: the scene is spatial");
    }
}
