package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Hit testing on an animated path must use the path <b>as evaluated at the playhead</b>.
 *
 * <p>The pen overlay, its anchors and its handles are drawn from the same values the renderer
 * evaluates, so the hit test has to see the evaluated path too. Reading the authored base values
 * (the shape at t=0) would make every anchor unreachable as soon as the mask moves: the vertex is
 * visibly somewhere else than the invisible base copy the hit test would compare against. The UI
 * adapter implements this by reading each component through {@code CompLayer.scalarAt}; these tests
 * pin the contract with the same evaluation the adapter uses ({@code CompLayer.maskAt}).</p>
 */
public class AnimatedPenHitTest
{
    private static CompLayer layer(CompMask mask)
    {
        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");

        layer.masks.add(mask);

        return layer;
    }

    private static CompPenSession.Projector identity()
    {
        return (x, y) -> new float[] {x, y};
    }

    /** Read-only {@link CompPathGeometry.PathView} over a mask's point list. */
    private static CompPathGeometry.PathView view(CompMask mask)
    {
        return new CompPathGeometry.PathView()
        {
            @Override
            public int count() { return mask.points.size(); }

            @Override
            public float x(int index) { return mask.points.get(index).x; }

            @Override
            public float y(int index) { return mask.points.get(index).y; }

            @Override
            public float inX(int index) { return mask.points.get(index).inX; }

            @Override
            public float inY(int index) { return mask.points.get(index).inY; }

            @Override
            public float outX(int index) { return mask.points.get(index).outX; }

            @Override
            public float outY(int index) { return mask.points.get(index).outY; }

            @Override
            public boolean closed() { return mask.closed; }
        };
    }

    private static void key(CompLayer layer, String path, float tick, float value)
    {
        layer.scalarChannel(path).insert(tick, value);
    }

    @Test
    public void vertexHitUsesTheEvaluatedPosition()
    {
        CompMask mask = new CompMask("mask_1");

        mask.rectangle(0F, 0F, 100F, 100F);

        CompLayer layer = layer(mask);

        key(layer, "mask/mask_1/p/0/x", 0F, 0F);
        key(layer, "mask/mask_1/p/0/x", 24F, 300F);
        key(layer, "mask/mask_1/p/0/y", 0F, 0F);
        key(layer, "mask/mask_1/p/0/y", 24F, 300F);

        CompMask evaluated = layer.maskAt(mask, 24F);

        assertEquals(300F, evaluated.points.get(0).x, 0.01F, "evaluated at the playhead");
        assertEquals(0F, mask.points.get(0).x, 0.01F, "base is still the authored value");

        CompPenSession.Hit evaluatedHit = CompPenSession.hitTest(view(evaluated), identity(), 300F, 300F, 9F, 9F, 6F, null);

        assertEquals(CompPenSession.HIT_VERTEX, evaluatedHit.kind);
        assertEquals(0, evaluatedHit.point);

        /* Hitting the base position of the same path finds nothing near it at this frame. */
        CompPenSession.Hit baseHit = CompPenSession.hitTest(view(mask), identity(), 300F, 300F, 9F, 9F, 6F, null);

        assertNotEquals(CompPenSession.HIT_VERTEX, baseHit.kind, "the base shape is not what is on screen");

        /* Clicking where the vertex used to be (t=0) must not select it at frame 24. */
        CompPenSession.Hit staleHit = CompPenSession.hitTest(view(evaluated), identity(), 2F, 2F, 9F, 9F, 6F, null);

        assertNotEquals(0, staleHit.point);
    }

    @Test
    public void handleHitUsesTheEvaluatedTangents()
    {
        CompMask mask = new CompMask("mask_1");

        mask.rectangle(0F, 0F, 100F, 100F);

        CompLayer layer = layer(mask);

        key(layer, "mask/mask_1/p/0/x", 0F, 0F);
        key(layer, "mask/mask_1/p/0/x", 24F, 200F);
        key(layer, "mask/mask_1/p/0/outX", 0F, 10F);
        key(layer, "mask/mask_1/p/0/outX", 24F, 90F);
        key(layer, "mask/mask_1/p/0/outY", 24F, 0F);

        CompMask evaluated = layer.maskAt(mask, 24F);
        CompMask.CompMaskPoint point = evaluated.points.get(0);
        float handleX = point.x + point.outX;
        float handleY = point.y + point.outY;
        java.util.Set<Integer> handles = java.util.Set.of(0);
        CompPenSession.Hit hit = CompPenSession.hitTest(view(evaluated), identity(), handleX, handleY, 9F, 9F, 6F, handles);

        assertEquals(CompPenSession.HIT_OUT, hit.kind);
        assertEquals(0, hit.point);

        /* The authored handle position (t=0) is not where the handle is drawn now. */
        CompPenSession.Hit baseHit = CompPenSession.hitTest(view(mask), identity(), handleX, handleY, 9F, 9F, 6F, handles);

        assertNotEquals(CompPenSession.HIT_OUT, baseHit.kind);
    }
}
