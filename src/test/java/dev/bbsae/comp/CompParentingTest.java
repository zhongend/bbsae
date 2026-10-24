package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parenting must be a change of basis, not a teleport.
 *
 * <p>The old code only wrote {@code parentId} and let the parent's full matrix multiply the child,
 * so a child at (900, 500) bound to a parent at (200, 100) jumped by the parent's whole transform.
 * These tests sample the child's world matrix before and after binding — the numbers must be equal.
 * </p>
 */
public class CompParentingTest
{
    private static CompDocument doc()
    {
        CompDocument doc = new CompDocument();

        doc.width = 1920;
        doc.height = 1080;
        doc.fps = 30F;
        doc.duration = 10F;

        return doc;
    }

    private static CompLayer layer(CompDocument doc, String id, float x, float y)
    {
        CompLayer layer = new CompLayer(id, id, "solid");

        layer.x = x;
        layer.y = y;
        layer.width = 200F;
        layer.height = 100F;
        doc.addLayer(layer);

        return layer;
    }

    private static float[] world(CompDocument doc, CompLayer layer, float frame)
    {
        Matrix4f matrix = CompParenting.worldMatrix(doc, layer, frame);
        Vector3f origin = new Vector3f();

        matrix.transformPosition(origin);

        return new float[] {origin.x, origin.y, CompParenting.decompose(matrix)[3]};
    }

    private static void assertSameWorld(CompDocument doc, CompLayer child, float frame, float[] expected)
    {
        float[] actual = world(doc, child, frame);

        assertEquals(expected[0], actual[0], 0.05F, "world x");
        assertEquals(expected[1], actual[1], 0.05F, "world y");
        assertEquals(expected[2], actual[2], 0.05F, "world rotation");
    }

    @Test
    public void bindingToAnOffsetParentKeepsTheChildInPlace()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 200F, 100F);
        CompLayer child = layer(doc, "child", 900F, 500F);
        float[] before = world(doc, child, 0F);

        assertTrue(CompParenting.bind(doc, child, parent.id, 0F));

        assertEquals(parent.id, child.parentId);
        assertSameWorld(doc, child, 0F, before);
        assertEquals(700F, child.x, 0.05F);
        assertEquals(400F, child.y, 0.05F);
    }

    @Test
    public void bindingToARotatedScaledParentKeepsTheChildInPlace()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 400F, 300F);
        CompLayer child = layer(doc, "child", 800F, 600F);

        parent.rotation = 37F;
        parent.scaleX = 1.8F;
        parent.scaleY = 1.8F;

        float[] before = world(doc, child, 0F);

        assertTrue(CompParenting.bind(doc, child, parent.id, 0F));
        assertSameWorld(doc, child, 0F, before);
    }

    /**
     * A non-uniform scale plus a rotation produces shear in the child's world matrix. The layer
     * transform has no shear channel, so rotation/scale can only be approximated - but the anchor
     * point (the thing the artist sees as "position") is exact. That is the contract.
     */
    @Test
    public void nonUniformlyScaledParentKeepsAtLeastThePositionExact()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 400F, 300F);
        CompLayer child = layer(doc, "child", 800F, 600F);

        parent.rotation = 37F;
        parent.scaleX = 1.8F;
        parent.scaleY = 0.6F;

        float[] before = world(doc, child, 0F);

        assertTrue(CompParenting.bind(doc, child, parent.id, 0F));

        float[] after = world(doc, child, 0F);

        assertEquals(before[0], after[0], 0.05F, "world x");
        assertEquals(before[1], after[1], 0.05F, "world y");
    }

    @Test
    public void bindingThroughAParentChainKeepsTheChildInPlace()
    {
        CompDocument doc = doc();
        CompLayer root = layer(doc, "root", 300F, 200F);
        CompLayer middle = layer(doc, "middle", 500F, 400F);
        CompLayer child = layer(doc, "child", 900F, 700F);

        root.rotation = 15F;
        middle.rotation = -25F;
        middle.scaleX = middle.scaleY = 1.4F;
        CompParenting.bind(doc, middle, root.id, 0F);

        float[] before = world(doc, child, 0F);

        assertTrue(CompParenting.bind(doc, child, middle.id, 0F));
        assertSameWorld(doc, child, 0F, before);
    }

    @Test
    public void unbindingKeepsTheChildInPlaceToo()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 640F, 360F);
        CompLayer child = layer(doc, "child", 900F, 500F);

        parent.rotation = 90F;
        CompParenting.bind(doc, child, parent.id, 0F);

        float[] before = world(doc, child, 0F);

        assertTrue(CompParenting.bind(doc, child, "", 0F));

        assertTrue(child.parentId.isEmpty());
        assertSameWorld(doc, child, 0F, before);
    }

    @Test
    public void animatedChildKeepsItsCurveAfterBinding()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 300F, 300F);
        CompLayer child = layer(doc, "child", 900F, 500F);
        KeyframeChannel<Transform> channel = child.transformChannel();

        Transform a = new Transform();
        Transform b = new Transform();

        a.translate.set(900F, 500F, 0F);
        b.translate.set(1200F, 500F, 0F);
        channel.insert(0F, a);
        channel.insert(30F, b);

        float[] before = world(doc, child, 0F);
        float[] beforeLater = world(doc, child, 30F);

        assertTrue(CompParenting.bind(doc, child, parent.id, 0F));

        assertSameWorld(doc, child, 0F, before);
        assertSameWorld(doc, child, 30F, beforeLater);

        /* The whole curve moved by the same local delta, so the animation is intact. */
        assertEquals(600F, channel.getKeyframes().get(0).getValue().translate.x, 0.05F);
        assertEquals(900F, channel.getKeyframes().get(1).getValue().translate.x, 0.05F);
    }

    @Test
    public void cyclesAndSelfParentingAreRefused()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 0F, 0F);
        CompLayer child = layer(doc, "child", 0F, 0F);

        assertFalse(CompParenting.bind(doc, parent, parent.id, 0F));
        assertTrue(CompParenting.bind(doc, child, parent.id, 0F));
        assertFalse(CompParenting.bind(doc, parent, child.id, 0F));
    }

    /**
     * 3D parenting is the same change of basis as 2D; the old build had a second matrix path with
     * a Y flip that could not agree with the 2D one, so a 3D child teleported when bound.
     */
    @Test
    public void bindingToATilted3DParentKeepsTheWholeTransform()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 500F, 400F);
        CompLayer child = layer(doc, "child", 900F, 500F);

        parent.threeD = true;
        parent.z = 220F;
        parent.rotation = 18F;
        parent.rotationX = 24F;
        parent.rotationY = -32F;
        parent.scaleX = parent.scaleY = parent.scaleZ = 1.35F;

        child.threeD = true;
        child.z = -140F;
        child.rotationX = 12F;
        child.rotationY = 8F;

        float[] before = flatten(CompParenting.worldMatrix(doc, child, 0F));

        assertTrue(CompParenting.bind(doc, child, parent.id, 0F));

        float[] after = flatten(CompParenting.worldMatrix(doc, child, 0F));

        for (int i = 0; i < before.length; i++)
        {
            assertEquals(before[i], after[i], 0.05F, "element " + i);
        }
    }

    @Test
    public void bindingA3DChildToA3DChainKeepsItInPlace()
    {
        CompDocument doc = doc();
        CompLayer root = layer(doc, "root", 300F, 200F);
        CompLayer middle = layer(doc, "middle", 500F, 400F);
        CompLayer child = layer(doc, "child", 900F, 700F);

        root.threeD = true;
        root.rotationX = 15F;
        root.scaleX = root.scaleY = root.scaleZ = 1.2F;

        middle.threeD = true;
        middle.z = 300F;
        middle.rotation = -20F;

        child.threeD = true;
        child.z = -80F;

        CompParenting.bind(doc, middle, root.id, 0F);

        float[] before = flatten(CompParenting.worldMatrix(doc, child, 0F));

        assertTrue(CompParenting.bind(doc, child, middle.id, 0F));

        float[] after = flatten(CompParenting.worldMatrix(doc, child, 0F));

        for (int i = 0; i < before.length; i++)
        {
            assertEquals(before[i], after[i], 0.08F, "element " + i);
        }
    }

    private static float[] flatten(Matrix4f matrix)
    {
        return new float[] {
            matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
            matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
            matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
            matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33()
        };
    }

    @Test
    public void dragDeltaIsConvertedIntoParentSpace()
    {
        CompDocument doc = doc();
        CompLayer parent = layer(doc, "parent", 0F, 0F);
        CompLayer child = layer(doc, "child", 0F, 0F);

        parent.scaleX = 2F;
        parent.scaleY = 2F;

        CompParenting.bind(doc, child, parent.id, 0F);

        org.joml.Vector2f delta = CompParenting.toLocalDelta(doc, child, 0F, 40F, 20F);

        /* A 2x parent means the child only needs half the local delta to travel 40 comp pixels. */
        assertEquals(20F, delta.x, 0.001F);
        assertEquals(10F, delta.y, 0.001F);
    }
}
