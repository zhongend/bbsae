package dev.bbsae.comp;

import com.google.gson.Gson;
import mchorse.bbs_mod.utils.pose.Transform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless checks for the parts of the model that do not need Minecraft or a GL context.
 * These catch the timing, keyframe and serialization mistakes that are hard to see in a frame.
 */
public class CompModelTest
{
    @Test
    public void compInstanceTimeUsesTicksAndTimeScale()
    {
        CompInstance instance = new CompInstance("demo", "main", 20F, 0, 1F, 1F, 0, true);

        assertEquals(1F, instance.time(), 0.0001F);

        CompInstance offset = new CompInstance("demo", "main", 0F, 20, 2F, 1F, 0, true);

        assertEquals(1F, offset.time(), 0.0001F);
    }

    @Test
    public void compositionExportDurationExtendsPastTheTwelveSecondFilmDefault()
    {
        /* BBS cameras used to default to 12s (240 ticks). A 20s compositor must extend the
         * composition clip, or the recorder stops at 12s and drops the rest. */
        assertEquals(401, CompositionClip.exportDurationTicks(240, 20F));
        assertEquals(240, CompositionClip.exportDurationTicks(240, 8F));
        assertEquals(600, CompositionClip.exportDurationTicks(600, 8F));
        assertEquals(20, CompositionClip.exportDurationTicks(0, 0F));
    }

    @Test
    public void layerReorderUsesVisualRowsInsteadOfDocumentOrder()
    {
        CompDocument doc = new CompDocument();
        CompLayer a = doc.addLayer(new CompLayer("a", "A", "solid"));
        CompLayer b = doc.addLayer(new CompLayer("b", "B", "solid"));
        CompLayer c = doc.addLayer(new CompLayer("c", "C", "solid"));

        /* doc order is bottom-to-top: [A, B, C]. Visual rows are [C, B, A]. */
        assertTrue(doc.moveLayerToDisplayIndex(c, 2));
        assertEquals(List.of("a", "c", "b"), doc.layers.stream().map((l) -> l.id).toList());

        assertTrue(doc.moveLayerToDisplayIndex(a, 0));
        assertEquals(List.of("c", "b", "a"), doc.layers.stream().map((l) -> l.id).toList());

        assertFalse(doc.moveLayerToDisplayIndex(a, 0));
    }

    @Test
    public void layerCanMovePastTwentySecondsWhenCompositionIsLonger()
    {
        CompLayer layer = new CompLayer("long", "Long", "solid");

        layer.in = 0F;
        layer.out = 20F;

        layer.moveTo(20F, 20F, 40F);

        assertEquals(20F, layer.in, 0.0001F);
        assertEquals(40F, layer.out, 0.0001F);

        layer.trimOutTo(40F, 20F, 40F);

        assertEquals(40F, layer.out, 0.0001F);
    }

    @Test
    public void demoProjectHasLayersAndBbsTracks()
    {
        CompProject project = CompStore.demo("unit-test");
        CompDocument comp = project.getComp(CompStore.DEFAULT_COMP);

        assertNotNull(comp);
        assertEquals(1920, comp.width);
        assertEquals(1080, comp.height);
        assertTrue(comp.layers.size() >= 4);
        assertEquals("panel", comp.layers.get(0).type);
        assertEquals("BBS COMPOSITOR", comp.getLayer("title").text);
        assertEquals(2, comp.getLayer("title").transformChannel().getKeyframes().size());
        assertEquals(3, comp.getLayer("title").opacityChannel().getKeyframes().size());
        assertTrue(comp.getLayer("panel").masks.size() > 0);
        assertTrue(comp.getLayer("badge").shapes.size() > 0);
    }

    @Test
    public void nativeBbsChannelsInterpolate()
    {
        CompLayer layer = new CompLayer("native", "Native", "rect");
        Transform a = layer.defaultTransform();
        Transform b = layer.defaultTransform();

        a.translate.set(0F, 0F, 0F);
        b.translate.set(60F, 100F, 0F);
        layer.transformChannel().insert(0F, a);
        layer.transformChannel().insert(60F, b);
        layer.opacityChannel().insert(0F, 0F);
        layer.opacityChannel().insert(30F, 1F);

        Transform mid = layer.transformAt(30F);

        assertEquals(30F, mid.translate.x, 0.0001F);
        assertEquals(50F, mid.translate.y, 0.0001F);
        assertEquals(0.5F, layer.opacityFrame(15F), 0.0001F);
    }

    @Test
    public void nativeTracksRoundTripThroughBbsData()
    {
        CompLayer layer = new CompLayer("native", "Native", "rect");
        Transform a = layer.defaultTransform();

        a.translate.set(12F, 34F, 0F);
        layer.transformChannel().insert(0F, a);
        layer.opacityChannel().insert(0F, 0.25F);
        layer.scalarChannel(CompLayer.effectPath("fx1", "radius")).insert(0F, 12F);
        layer.saveTracks();

        CompLayer copy = new CompLayer("native", "Native", "rect");

        copy.transformData = layer.transformData;
        copy.opacityData = layer.opacityData;
        copy.channels.putAll(layer.channels);
        copy.loadTracks();

        assertEquals(12F, copy.transformAt(0F).translate.x, 0.0001F);
        assertEquals(34F, copy.transformAt(0F).translate.y, 0.0001F);
        assertEquals(0.25F, copy.opacityFrame(0F), 0.0001F);
        assertEquals(12F, copy.scalarAt(CompLayer.effectPath("fx1", "radius"), 0F, 0F), 0.0001F);
    }

    @Test
    public void projectJsonRoundTripsMasksEffectsAndQueue()
    {
        CompProject project = CompStore.demo("unit-test");
        CompDocument comp = project.getComp(CompStore.DEFAULT_COMP);

        comp.addLayer(new CompLayer("fx", "FX", "adjustment"));
        comp.getLayer("fx").effects.add(CompEffectRegistry.create("glow", 1));
        comp.markers.add(new CompMarker("m", "Marker", 1F));
        comp.parameters.add(new CompParameter("speed", 2F));
        project.renderQueue.add(new CompRenderJob("q", "unit-test", "main", "review_h264"));

        for (CompLayer layer : comp.layers)
        {
            layer.saveTracks();
        }

        Gson gson = new Gson();
        CompProject parsed = gson.fromJson(gson.toJson(project), CompProject.class);

        assertNotNull(parsed);
        assertEquals("bbsae", parsed.format);
        assertEquals(project.comps.size(), parsed.comps.size());
        assertEquals(1, parsed.renderQueue.size());
        assertEquals("glow", parsed.getComp("main").getLayer("fx").effects.get(0).type);
        assertEquals(4, parsed.getComp("main").markers.size());
        assertEquals(1F, parsed.getComp("main").getParameter("speed").value, 0.0001F);
    }

    @Test
    public void expressionsAreSafeAndDeterministic()
    {
        CompExpression.Context context = new CompExpression.Context()
        {
            @Override public float time() { return 2F; }
            @Override public float frame() { return 120F; }
            @Override public float fps() { return 60F; }
            @Override public float value() { return 10F; }
            @Override public float variable(String path) { return path.equals("comp.speed") ? 3F : 0F; }
        };

        assertEquals(16F, CompExpression.eval("value + comp.speed * 2", context, 0F), 0.0001F);
        assertEquals(1F, CompExpression.eval("clamp(value, 0, 1)", context, 0F), 0.0001F);
        assertEquals(7F, CompExpression.eval("nonsense +", context, 7F), 0.0001F);
    }

    @Test
    public void copyPreservesLayerData()
    {
        CompProject project = CompStore.demo("unit-test");
        CompDocument copy = project.getComp("main").copy();

        assertNotNull(copy);
        assertFalse(copy.layers.isEmpty());
        assertEquals("BBS COMPOSITOR", copy.getLayer("title").text);
        assertTrue(copy.getLayer("panel").masks.size() > 0);
    }

    @Test
    public void expressionReadsAnimatedLayerPropertiesAtFrame()
    {
        CompProject project = CompStore.demo("unit-test");
        CompDocument comp = project.getComp("main");
        CompLayer layer = new CompLayer("a", "A", "rect");
        Transform start = layer.defaultTransform();
        Transform end = layer.defaultTransform();

        start.translate.set(0F, 0F, 0F);
        end.translate.set(100F, 0F, 0F);
        layer.transformChannel().insert(0F, start);
        layer.transformChannel().insert(60F, end);
        comp.layers.add(layer);

        assertEquals(50F, CompExpression.variable(project, comp, "layer.a.x", 0F, 30F), 0.0001F);
        assertEquals(50F, CompExpression.variable(project, comp, "param.a.x", 0F, 30F), 0.0001F);
    }

    @Test
    public void trackMoveCarriesKeyframesAndMarkers()
    {
        CompLayer layer = new CompLayer("track", "Track", "rect");
        Transform first = layer.defaultTransform();

        first.translate.set(10F, 20F, 0F);
        layer.transformChannel().insert(0F, first);
        layer.opacityChannel().insert(60F, 0.5F);
        layer.markers.add(new CompMarker("m", "Cue", 2F));
        layer.in = 0F;
        layer.out = 10F;

        layer.moveTo(2F, 20F, 12F);

        assertEquals(2F, layer.in, 0.0001F);
        assertEquals(12F, layer.out, 0.0001F);
        assertEquals(40F, layer.transformChannel().getKeyframes().get(0).getTick(), 0.0001F);
        assertEquals(100F, layer.opacityChannel().getKeyframes().get(0).getTick(), 0.0001F);
        assertEquals(4F, layer.markers.get(0).time, 0.0001F);
    }

    @Test
    public void trimInKeepsFootageInPlaceAndLeavesKeysAbsolute()
    {
        CompLayer layer = new CompLayer("footage", "Footage", "image");

        layer.in = 0F;
        layer.out = 10F;
        layer.startTime = 1F;
        layer.timeScale = 1F;
        layer.transformChannel().insert(60F, layer.defaultTransform());

        assertEquals(4F, layer.sourceTime(3F), 0.0001F);

        layer.trimInTo(2F, 20F);

        assertEquals(2F, layer.in, 0.0001F);
        assertEquals(3F, layer.startTime, 0.0001F);
        assertEquals(4F, layer.sourceTime(3F), 0.0001F);
        assertEquals(60F, layer.transformChannel().getKeyframes().get(0).getTick(), 0.0001F);
    }

    @Test
    public void splitAtPlayheadKeepsSourceContinuous()
    {
        CompLayer layer = new CompLayer("split", "Split", "image");

        layer.in = 0F;
        layer.out = 10F;
        layer.startTime = 1F;
        layer.timeScale = 1F;

        float before = layer.sourceTime(4F);
        CompLayer right = layer.splitAt(4F, 20F, "split_b", "Split · B");

        assertNotNull(right);
        assertEquals(4F, layer.out, 0.0001F);
        assertEquals(4F, right.in, 0.0001F);
        assertEquals(10F, right.out, 0.0001F);
        assertEquals(5F, right.startTime, 0.0001F);
        assertEquals(before, right.sourceTime(4F), 0.0001F);
        assertEquals("split_b", right.id);
        assertEquals("Split · B", right.name);
    }

    @Test
    public void timecodeUsesFrames()
    {
        assertEquals("00:00:01:00", CompTime.timecode(1F, 60F));
        assertEquals("00:00:01:30", CompTime.timecode(1.5F, 60F));
        assertEquals("00:00:00:01", CompTime.timecode(1F / 60F, 60F));
    }

    @Test
    public void maskAndShapeValuesResolveAtFrame()
    {
        CompLayer layer = new CompLayer("animated", "Animated", "shape");
        CompMask mask = new CompMask("m1").rectangle(0F, 0F, 100F, 100F);
        CompShape shape = CompShape.rect("s1", 0F, 0F, 100F, 100F, 0xFFFFFFFF);

        mask.feather = 5F;
        shape.trimEnd = 0F;
        layer.masks.add(mask);
        layer.shapes.add(shape);
        layer.scalarChannel(CompLayer.maskPath("m1", "feather")).insert(0F, 5F);
        layer.scalarChannel(CompLayer.maskPath("m1", "feather")).insert(60F, 25F);
        layer.scalarChannel("mask/m1/p/0/x").insert(0F, 0F);
        layer.scalarChannel("mask/m1/p/0/x").insert(60F, 60F);
        layer.scalarChannel(CompLayer.shapePath("s1", "trimEnd")).insert(0F, 0F);
        layer.scalarChannel(CompLayer.shapePath("s1", "trimEnd")).insert(60F, 1F);

        CompMask resolvedMask = layer.maskAt(mask, 30F);
        CompShape resolvedShape = layer.shapeAt(shape, 30F);

        assertEquals(15F, resolvedMask.feather, 0.0001F);
        assertEquals(30F, resolvedMask.points.get(0).x, 0.0001F);
        assertEquals(0.5F, resolvedShape.trimEnd, 0.0001F);
        assertEquals(5F, mask.feather, 0.0001F);
        assertEquals(0F, shape.trimEnd, 0.0001F);
    }

    @Test
    public void maskHandlesAndShapePathPointsAreAnimatable()
    {
        CompLayer layer = new CompLayer("path", "Path", "shape");
        CompMask mask = new CompMask("m1").rectangle(0F, 0F, 100F, 100F);
        CompShape shape = new CompShape("s1", CompShape.PATH);

        shape.add(0F, 0F);
        shape.add(100F, 100F);
        layer.masks.add(mask);
        layer.shapes.add(shape);
        layer.scalarChannel("mask/m1/p/0/inX").insert(0F, 1F);
        layer.scalarChannel("mask/m1/p/0/inX").insert(60F, 21F);
        layer.scalarChannel("shape/s1/p/1/x").insert(0F, 100F);
        layer.scalarChannel("shape/s1/p/1/x").insert(60F, 160F);

        assertEquals(11F, layer.maskAt(mask, 30F).points.get(0).inX, 0.0001F);
        assertEquals(130F, layer.shapeAt(shape, 30F).points.get(1).x, 0.0001F);
        assertEquals(0F, mask.points.get(0).inX, 0.0001F);
    }

    @Test
    public void timeRemapAndTextAnimationUseScalarChannels()
    {
        CompLayer layer = new CompLayer("text", "Text", "text");

        layer.startTime = 1F;
        layer.timeScale = 2F;
        layer.timeRemap = true;
        layer.scalarChannel("timeRemap").insert(0F, 0F);
        layer.scalarChannel("timeRemap").insert(60F, 10F);
        layer.scalarChannel("text/fontSize").insert(0F, 48F);
        layer.scalarChannel("text/fontSize").insert(60F, 96F);
        layer.scalarChannel("text/tracking").insert(0F, -10F);
        layer.scalarChannel("text/tracking").insert(60F, 10F);

        assertEquals(5F, layer.sourceTimeAt(2F, 30F), 0.0001F);
        assertEquals(72F, layer.fontSizeAt(30F), 0.0001F);
        assertEquals(0F, layer.trackingAt(30F), 0.0001F);
        assertEquals(6F, layer.sourceTime(2F), 0.0001F);
    }

    @Test
    public void animationPresetAppliesOnlyAnimation()
    {
        CompLayer source = new CompLayer("source", "Source", "rect");
        Transform a = source.defaultTransform();

        a.translate.set(12F, 34F, 0F);
        source.transformChannel().insert(0F, a);
        source.opacityChannel().insert(0F, 0.4F);
        source.saveTracks();

        CompAnimationPreset preset = CompAnimationPreset.capture("p1", "Motion", source);

        assertNotNull(preset);
        assertTrue(preset.data.contains("transform"));

        CompLayer target = new CompLayer("target", "Target", "text");

        target.text = "Keep my content";
        assertTrue(preset.applyTo(target));
        assertEquals(12F, target.transformAt(0F).translate.x, 0.0001F);
        assertEquals(0.4F, target.opacityFrame(0F), 0.0001F);
        assertEquals("Keep my content", target.text);
    }

    @Test
    public void layerMarkersAndAnimatedValuesSurviveJson()
    {
        CompProject project = CompStore.demo("unit-test");
        CompLayer layer = project.getComp("main").getLayer("badge");

        layer.timeRemap = true;
        layer.markers.add(new CompMarker("m1", "Cue", 2.5F));
        layer.scalarChannel("timeRemap").insert(0F, 1F);
        layer.scalarChannel(CompLayer.shapePath(layer.shapes.get(0).id, "rotation")).insert(0F, 45F);
        layer.saveTracks();

        Gson gson = new Gson();
        CompProject parsed = gson.fromJson(gson.toJson(project), CompProject.class);
        CompLayer copy = parsed.getComp("main").getLayer("badge");

        copy.loadTracks();
        assertTrue(copy.timeRemap);
        assertEquals(2, copy.markers.size());
        assertEquals("Cue", copy.markers.get(1).name);
        assertEquals(2.5F, copy.markers.get(1).time, 0.0001F);
        assertEquals(45F, copy.shapeAt(copy.shapes.get(0), 0F).rotation, 0.0001F);
    }

    @Test
    public void shapeGradientFillSurvivesCopyAndKeyframes()
    {
        CompLayer layer = new CompLayer("shape", "Shape", "shape");
        CompShape shape = CompShape.rect("s1", 0F, 0F, 100F, 80F, 0xFFFF0000);

        shape.fillGradient = true;
        shape.fillColor2 = 0xFF0000FF;
        shape.fillGradientAngle = 90F;
        layer.shapes.add(shape);

        CompShape copied = shape.copy();

        assertTrue(copied.fillGradient);
        assertEquals(0xFF0000FF, copied.fillColor2);
        assertEquals(90F, copied.fillGradientAngle, 0.0001F);

        layer.scalarChannel(CompLayer.shapePath("s1", "fillGradientAngle")).insert(0F, 180F);

        assertEquals(180F, layer.shapeAt(shape, 0F).fillGradientAngle, 0.0001F);
    }

    @Test
    public void precomposeRebasesInnerTimelineAndKeepsOrderAndRelations()
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.name = "Main";
        doc.fps = 20F;
        doc.duration = 10F;
        doc.workAreaOut = 10F;
        project.comps.add(doc);

        CompLayer below = doc.addLayer(new CompLayer("below", "Below", "solid"));
        CompLayer source = new CompLayer("source", "Source", "solid");
        CompLayer above = doc.addLayer(new CompLayer("above", "Above", "solid"));
        CompLayer child = doc.addLayer(new CompLayer("child", "Child", "null"));
        CompLayer matte = doc.addLayer(new CompLayer("matte", "Matte", "solid"));

        source.in = 5F;
        source.out = 8F;

        Transform transform = new Transform();
        transform.translate.x = 123F;
        source.transformChannel().insert(6F * 20F, transform);

        doc.layers.add(1, source);
        child.parentId = source.id;
        matte.trackMatteId = source.id;

        CompLayer precomp = CompStore.precompose(project, doc, source, "预合成");

        assertNotNull(precomp);
        assertEquals(1, doc.layers.indexOf(precomp));
        assertEquals(List.of("below", "precomp", "above", "child", "matte"),
            doc.layers.stream().map((layer) -> layer.id).toList());
        assertEquals(5F, precomp.in, 0.0001F);
        assertEquals(8F, precomp.out, 0.0001F);
        assertEquals(0F, precomp.startTime, 0.0001F);
        assertEquals(precomp.id, child.parentId);
        assertEquals(precomp.id, matte.trackMatteId);

        CompDocument sub = project.getComp(precomp.precompId);

        assertNotNull(sub);
        assertEquals(1, sub.layers.size());

        CompLayer copy = sub.layers.get(0);

        assertEquals(0F, copy.in, 0.0001F);
        assertEquals(3F, copy.out, 0.0001F);
        assertEquals(20F, copy.transformChannel().getKeyframes().get(0).getTick(), 0.0001F);

        /* Parent 6s maps to inner 1s; the inner source frame must match the pre-precompose
         * source frame at 6s (1s after the 5s in point). */
        assertEquals(1F, precomp.sourceTimeAt(6F, 120F), 0.0001F);
        assertEquals(1F, copy.sourceTimeAt(1F, 20F), 0.0001F);
    }

}
