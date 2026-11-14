package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The master transform curve is a synchronized authoring layer over the independent XYZ curves.
 * These tests pin the contract: keying the master writes every axis, sub-axis edits are detected,
 * and a resync replaces the sub-curves from the master instead of merging them.
 */
public class CompLayerMasterTransformTest
{
    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.width = 200F;
        layer.height = 100F;
        layer.x = 100F;
        layer.y = 50F;
        layer.scaleX = 1F;
        layer.scaleY = 1F;
        layer.rotation = 0F;

        return layer;
    }

    @Test
    public void seedingTheMasterUsesTheUnionOfAxisTicks()
    {
        CompLayer layer = layer();

        layer.insertAxisKey(CompCurves.AXIS_X, 0F, 10F);
        layer.insertAxisKey(CompCurves.AXIS_X, 10F, 110F);
        layer.insertAxisKey(CompCurves.AXIS_Y, 10F, 80F);

        layer.ensureMasterFromAxes();

        List<Keyframe<mchorse.bbs_mod.utils.pose.Transform>> master = layer.masterTransformChannel().getKeyframes();

        assertEquals(2, master.size());
        assertEquals(0F, master.get(0).getTick(), 0.001F);
        assertEquals(10F, master.get(1).getTick(), 0.001F);
        assertEquals(110F, master.get(1).getValue().translate.x, 0.001F);
        assertEquals(80F, master.get(1).getValue().translate.y, 0.001F);
    }

    @Test
    public void keyingTheMasterWritesEveryAxisAtTheSameFrame()
    {
        CompLayer layer = layer();

        layer.insertAxisKey(CompCurves.AXIS_X, 0F, 10F);
        layer.insertMasterTransformKey(10F);
        layer.syncAxesFromMaster();

        assertEquals(2, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
        assertEquals(2, layer.axisChannel(CompCurves.AXIS_Y).getKeyframes().size());
        assertEquals(2, layer.axisChannel(CompCurves.AXIS_SCALE_X).getKeyframes().size());
        assertTrue(layer.masterMatchesAxes());
    }

    @Test
    public void editingOneAxisMarksTheMasterDiverged()
    {
        CompLayer layer = layer();

        layer.insertMasterTransformKey(0F);
        layer.insertMasterTransformKey(10F);
        layer.syncAxesFromMaster();

        assertTrue(layer.masterMatchesAxes());

        layer.axisChannel(CompCurves.AXIS_X).insert(5F, 999F);

        assertFalse(layer.masterMatchesAxes(), "an extra X key is independent editing");
    }

    @Test
    public void resyncingReplacesTheAxisCurvesWithTheMaster()
    {
        CompLayer layer = layer();

        layer.insertMasterTransformKey(0F);
        layer.insertMasterTransformKey(10F);
        layer.syncAxesFromMaster();
        layer.axisChannel(CompCurves.AXIS_X).insert(5F, 999F);
        layer.axisChannel(CompCurves.AXIS_Y).insert(7F, -999F);

        assertFalse(layer.masterMatchesAxes());

        layer.syncAxesFromMaster();

        assertTrue(layer.masterMatchesAxes());
        assertEquals(2, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
        assertEquals(2, layer.axisChannel(CompCurves.AXIS_Y).getKeyframes().size());
    }

    @Test
    public void theMasterCurveSurvivesSnapshots()
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.id = "main";
        doc.width = 1920;
        doc.height = 1080;
        doc.duration = 10F;
        doc.fps = 30F;
        project.comps.add(doc);

        CompLayer layer = layer();

        doc.layers.add(layer);
        layer.insertMasterTransformKey(0F);
        layer.insertMasterTransformKey(20F);
        layer.syncAxesFromMaster();
        layer.saveTracks();

        CompProject restored = CompStore.restore(CompStore.snapshot(project));
        CompLayer copy = restored.getCompOrFirst("main").getLayer(layer.id);

        assertNotNull(copy);
        assertTrue(copy.hasMasterTransformKeys(), "the master curve must survive a project save");
        assertTrue(copy.masterMatchesAxes());
    }

    @Test
    public void resettingTheTransformClearsTheMasterToo()
    {
        CompLayer layer = layer();

        layer.insertMasterTransformKey(0F);
        layer.syncAxesFromMaster();

        assertTrue(layer.hasMasterTransformKeys());

        layer.clearAllTransformAxes();

        assertFalse(layer.hasMasterTransformKeys());
        assertFalse(layer.hasAnyTransformKey());
    }
}
