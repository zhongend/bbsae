package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AE-style separate dimensions.
 *
 * <p>The bug this pins down: BBS stored one Transform per key, so Position X and Y literally shared
 * a keyframe object. Deleting X deleted Y, and the two axes could never have different times. These
 * tests drive the independent float channels directly and verify that the evaluated Transform still
 * looks like one coherent pose.</p>
 */
public class AxisCurvesTest
{
    private static CompCurveChannel channel(CompLayer layer, String id)
    {
        for (CompCurveChannel channel : CompCurveChannel.of(layer))
        {
            if (channel.id.equals(id))
            {
                return channel;
            }
        }

        throw new AssertionError("missing curve " + id);
    }
    @Test
    public void deletingOneAxisKeyDoesNotDeleteTheOther()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.x = 100F;
        layer.y = 100F;

        layer.insertAxisKey(CompCurves.AXIS_X, 0F, 100F);
        layer.insertAxisKey(CompCurves.AXIS_X, 20F, 500F);
        layer.insertAxisKey(CompCurves.AXIS_Y, 0F, 100F);

        assertEquals(2, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
        assertEquals(1, layer.axisChannel(CompCurves.AXIS_Y).getKeyframes().size());

        layer.axisChannel(CompCurves.AXIS_X).remove(0);

        assertEquals(1, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
        assertEquals(1, layer.axisChannel(CompCurves.AXIS_Y).getKeyframes().size());
        assertEquals(100F, layer.axisChannel(CompCurves.AXIS_Y).getKeyframes().get(0).getValue(), 0.0001F);
    }

    @Test
    public void axesCanHaveIndependentTimesAndValues()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.insertAxisKey(CompCurves.AXIS_X, 0F, 0F);
        layer.insertAxisKey(CompCurves.AXIS_X, 10F, 100F);
        layer.insertAxisKey(CompCurves.AXIS_Y, 0F, 50F);
        layer.insertAxisKey(CompCurves.AXIS_Y, 20F, 250F);

        Transform mid = layer.transformAt(5F);

        assertEquals(50F, mid.translate.x, 0.0001F);
        assertEquals(100F, mid.translate.y, 0.0001F);
        assertFalse(layer.transformChannel().getKeyframes().size() > 0);
    }

    @Test
    public void legacyTransformChannelMigratesPreservingCurveShape()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        KeyframeChannel<Transform> legacy = layer.transformChannel();

        Transform a = layer.defaultTransform();
        Transform b = layer.defaultTransform();

        a.translate.set(10F, 20F, 0F);
        b.translate.set(110F, 220F, 0F);

        Keyframe<Transform> ka = legacy.insertInheriting(0F, a);
        ka.rx = 7F;
        ka.ry = 12F;
        legacy.insertInheriting(24F, b);

        layer.ensureIndependentAxes();

        assertTrue(legacy.getKeyframes().isEmpty(), "legacy shared channel must be retired");
        assertEquals(2, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
        assertEquals(2, layer.axisChannel(CompCurves.AXIS_Y).getKeyframes().size());
        assertEquals(7F, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().get(0).rx, 0.0001F);
        assertEquals(12F, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().get(0).ry, 0.0001F);
        assertEquals(60F, layer.transformAt(12F).translate.x, 0.0001F);
    }

    @Test
    public void curveChannelExposesThreeDimensionalAxesSeparately()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.threeD = true;
        layer.z = 55F;
        layer.rotationY = 12F;

        assertEquals(55F, channel(layer, "transform.z").valueAt(0F), 0.0001F);
        assertEquals(12F, channel(layer, "transform.rotationY").valueAt(0F), 0.0001F);

        layer.insertAxisKey(CompCurves.AXIS_Z, 0F, 30F);
        layer.insertAxisKey(CompCurves.AXIS_ROTATION_Y, 0F, 45F);
        layer.insertAxisKey(CompCurves.AXIS_SCALE_Z, 0F, 2F);

        int found = 0;

        for (CompCurveChannel channel : CompCurveChannel.of(layer))
        {
            if (channel.id.equals("transform.z")
                || channel.id.equals("transform.rotationY")
                || channel.id.equals("transform.scaleZ"))
            {
                found += 1;
                assertNotNull(channel.raw());
                assertEquals(1, channel.raw().getKeyframes().size());
            }
        }

        assertEquals(3, found);
        assertEquals(30F, layer.transformAt(0F).translate.z, 0.0001F);
        assertEquals(45F, layer.transformAt(0F).rotate.y, 0.0001F);
        assertEquals(2F, layer.transformAt(0F).scale.z, 0.0001F);
    }

    @Test
    public void independentAxesSurviveSaveAndLoad()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.insertAxisKey(CompCurves.AXIS_X, 0F, 10F);
        layer.insertAxisKey(CompCurves.AXIS_X, 24F, 20F);
        layer.insertAxisKey(CompCurves.AXIS_Y, 0F, 30F);
        layer.insertAxisKey(CompCurves.AXIS_Y, 48F, 40F);
        layer.saveTracks();

        CompLayer loaded = new CompLayer(layer.id, layer.name, layer.type);

        loaded.channels.putAll(layer.channels);
        loaded.loadTracks();

        assertEquals(2, loaded.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
        assertEquals(2, loaded.axisChannel(CompCurves.AXIS_Y).getKeyframes().size());
        assertEquals(15F, loaded.transformAt(12F).translate.x, 0.0001F);
        assertEquals(35F, loaded.transformAt(24F).translate.y, 0.0001F);
        assertFalse(loaded.transformChannel().getKeyframes().size() > 0);
    }

    @Test
    public void writingOneAxisNeverDropsAnOldSharedCurve()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        layer.threeD = true;
        Transform a = layer.defaultTransform();

        a.translate.set(100F, 200F, 0F);
        layer.transformChannel().insertInheriting(0F, a);

        layer.insertAxisKey(CompCurves.AXIS_Z, 0F, 40F);

        assertTrue(layer.transformChannel().getKeyframes().isEmpty());
        assertEquals(100F, layer.transformAt(0F).translate.x, 0.0001F);
        assertEquals(200F, layer.transformAt(0F).translate.y, 0.0001F);
        assertEquals(40F, layer.transformAt(0F).translate.z, 0.0001F);
    }

    @Test
    public void legacyMigrationDoesNotInventZKeysFromAnOldTransform()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        layer.threeD = true;
        layer.z = 300F;
        layer.rotationX = 15F;
        layer.scaleZ = 1.4F;

        assertEquals(300F, layer.defaultTransform().translate.z, 0.0001F);
        assertEquals(15F, layer.defaultTransform().rotate.x, 0.0001F);
        assertEquals(1.4F, layer.defaultTransform().scale.z, 0.0001F);

        Transform pose = layer.defaultTransform();
        pose.translate.set(100F, 200F, 0F);
        layer.transformChannel().insertInheriting(0F, pose);

        layer.ensureIndependentAxes();

        assertTrue(layer.axisChannel(CompCurves.AXIS_Z).getKeyframes().isEmpty());
        assertTrue(layer.axisChannel(CompCurves.AXIS_ROTATION_X).getKeyframes().isEmpty());
        assertTrue(layer.axisChannel(CompCurves.AXIS_SCALE_Z).getKeyframes().isEmpty());
        assertEquals(300F, layer.transformAt(0F).translate.z, 0.0001F);
        assertEquals(15F, layer.transformAt(0F).rotate.x, 0.0001F);
        assertEquals(1.4F, layer.transformAt(0F).scale.z, 0.0001F);
    }

    @Test
    public void evaluatingA3DLayerMigratesLegacyXYWhenZIsAlreadyKeyed()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");

        layer.threeD = true;
        layer.z = 250F;
        layer.scalarChannel("z").insert(0F, 250F);

        Transform pose = layer.defaultTransform();
        pose.translate.set(100F, 200F, 250F);
        layer.transformChannel().insertInheriting(0F, pose);

        Transform at = layer.transformAt(0F);

        assertEquals(100F, at.translate.x, 0.0001F);
        assertEquals(200F, at.translate.y, 0.0001F);
        assertEquals(250F, at.translate.z, 0.0001F);
        assertTrue(layer.transformChannel().getKeyframes().isEmpty());
        assertEquals(1, layer.axisChannel(CompCurves.AXIS_X).getKeyframes().size());
    }

    @Test
    public void effectCurveCanBeFoundByItsStablePath()
    {
        CompLayer layer = new CompLayer("layer", "Layer", "solid");
        String path = CompLayer.effectPath("fx1", "radius");
        CompCurveChannel found = null;

        layer.scalarChannel(path).insert(0F, 4F);

        for (CompCurveChannel channel : CompCurveChannel.of(layer))
        {
            if (channel.id.equals("scalar:" + path))
            {
                found = channel;
            }
        }

        assertNotNull(found, "effect key must appear as its own AE curve");
        assertTrue(found.hasKeys());
        assertEquals(4F, found.valueAt(0F), 0.0001F);
    }
}
