package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AE's RotoBezier editing mode.
 *
 * <p>RotoBezier is implemented as an editing behavior: enabling it derives every point's handles
 * from its neighbours (the same 1/6 rule Convert Vertex uses) and the session re-derives them after
 * vertex moves, inserts and deletes. The derived values are written into the ordinary handle
 * fields, so the renderer, cache signatures and file format need no special case.</p>
 */
public class RotoBezierTest
{
    private static final class FakeTarget implements CompPenSession.Target
    {
        final List<float[]> points = new ArrayList<>();
        boolean closed;
        boolean roto;

        FakeTarget add(float x, float y, float inX, float inY, float outX, float outY)
        {
            this.points.add(new float[] {x, y, inX, inY, outX, outY});

            return this;
        }

        @Override
        public int count() { return this.points.size(); }

        @Override
        public float x(int index) { return this.points.get(index)[0]; }

        @Override
        public float y(int index) { return this.points.get(index)[1]; }

        @Override
        public float inX(int index) { return this.points.get(index)[2]; }

        @Override
        public float inY(int index) { return this.points.get(index)[3]; }

        @Override
        public float outX(int index) { return this.points.get(index)[4]; }

        @Override
        public float outY(int index) { return this.points.get(index)[5]; }

        @Override
        public boolean closed() { return this.closed; }

        @Override
        public void setPoint(int index, float x, float y)
        {
            this.points.get(index)[0] = x;
            this.points.get(index)[1] = y;
        }

        @Override
        public void setHandle(int index, boolean out, float dx, float dy)
        {
            this.points.get(index)[out ? 4 : 2] = dx;
            this.points.get(index)[out ? 5 : 3] = dy;
        }

        @Override
        public void insert(int index, float x, float y, float inX, float inY, float outX, float outY)
        {
            this.points.add(index, new float[] {x, y, inX, inY, outX, outY});
        }

        @Override
        public void remove(int index)
        {
            this.points.remove(index);
        }

        @Override
        public void setClosed(boolean closed) { this.closed = closed; }

        @Override
        public boolean animated() { return false; }

        @Override
        public String label() { return "roto path"; }

        @Override
        public boolean rotoBezier() { return this.roto; }

        @Override
        public void setRotoBezier(boolean rotoBezier) { this.roto = rotoBezier; }
    }

    private static final class Recorder implements CompPenSession.Listener
    {
        int actions;
        String status = "";

        @Override
        public void beginAction(String name) { this.actions += 1; }

        @Override
        public void changed() {}

        @Override
        public void status(String message) { this.status = message; }

        @Override
        public void closedChanged() {}
    }

    private static FakeTarget quad()
    {
        FakeTarget path = new FakeTarget();

        path.add(0F, 0F, 0F, 0F, 30F, 0F);
        path.add(100F, 0F, -30F, 0F, 0F, 30F);
        path.add(100F, 100F, 0F, -30F, -30F, 0F);
        path.add(0F, 100F, 30F, 0F, 0F, -30F);
        path.closed = true;

        return path;
    }

    private static void assertDerivedLikeNeighbours(FakeTarget path)
    {
        for (int i = 0; i < path.count(); i++)
        {
            float[] expected = CompPathGeometry.smoothTangents(path, i);

            assertEquals(expected[0], path.inX(i), 0.001F, "point " + i + " inX");
            assertEquals(expected[1], path.inY(i), 0.001F, "point " + i + " inY");
            assertEquals(expected[2], path.outX(i), 0.001F, "point " + i + " outX");
            assertEquals(expected[3], path.outY(i), 0.001F, "point " + i + " outY");
            assertEquals(-path.outX(i), path.inX(i), 0.001F, "mirrored handles");
        }
    }

    @Test
    public void enablingRotoBezierDerivesEveryHandle()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget path = quad();

        session.setTarget(path);
        assertFalse(session.rotoBezier());

        assertTrue(session.setRotoBezier(true));
        assertTrue(session.rotoBezier());
        assertTrue(path.roto);
        assertEquals(1, recorder.actions, "one undo checkpoint");
        assertTrue(recorder.status.contains("RotoBezier"), recorder.status);

        assertDerivedLikeNeighbours(path);
    }

    @Test
    public void movingAVertexReDerivesTheHandlesOnRelease()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget path = quad();

        session.setTarget(path);
        session.setRotoBezier(true);

        /* Press on vertex 1 then drag it; a handle drag is not involved. */
        session.press(path.x(1), path.y(1), path.x(1), path.y(1), false, false,
            CompPenSession.CLOSE_RADIUS_PX, hitVertex(1));
        session.drag(path.x(1) + 25F, path.y(1) + 10F, path.x(1) + 25F, path.y(1) + 10F, false, false);
        session.release();

        assertEquals(125F, path.x(1), 0.001F);
        assertDerivedLikeNeighbours(path);
    }

    @Test
    public void handleDragsAreIgnoredWhileRotoBezierIsOn()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget path = quad();

        session.setTarget(path);
        session.setRotoBezier(true);

        float beforeInX = path.inX(0);

        session.press(path.x(0), path.y(0), path.x(0), path.y(0), false, false,
            CompPenSession.CLOSE_RADIUS_PX, hitHandle(0, CompPenSession.HIT_OUT));
        session.drag(path.x(0) + 60F, path.y(0) - 40F, path.x(0) + 60F, path.y(0) - 40F, false, false);
        session.release();

        assertEquals(beforeInX, path.inX(0), 0.001F, "handles are owned by the neighbours now");
        assertDerivedLikeNeighbours(path);
    }

    @Test
    public void insertDeleteAndAppendReDeriveTheHandles()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget path = quad();

        session.setTarget(path);
        session.setRotoBezier(true);

        assertTrue(session.insertOnSegment(0, 0.5F));
        assertEquals(5, path.count());
        assertDerivedLikeNeighbours(path);

        session.selectVertex(0, false);
        assertTrue(session.deleteSelection());
        assertEquals(4, path.count());
        assertDerivedLikeNeighbours(path);

        assertTrue(session.appendPoint(200F, 200F));
        assertEquals(5, path.count());
        assertDerivedLikeNeighbours(path);
    }

    @Test
    public void disablingRotoBezierLeavesTheDerivedHandlesAsAuthoredValues()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget path = quad();

        session.setTarget(path);
        session.setRotoBezier(true);

        float outX = path.outX(1);

        assertTrue(session.setRotoBezier(false));
        assertFalse(session.rotoBezier());
        assertEquals(outX, path.outX(1), 0.001F, "turning the mode off does not snap the handles back");

        /* Manual handle editing works again. */
        session.press(path.x(1), path.y(1), path.x(1), path.y(1), false, false,
            CompPenSession.CLOSE_RADIUS_PX, hitHandle(1, CompPenSession.HIT_OUT));
        session.drag(path.x(1) + 40F, path.y(1) - 20F, path.x(1) + 40F, path.y(1) - 20F, false, false);
        session.release();

        assertEquals(40F, path.outX(1), 0.001F);
    }

    @Test
    public void openPathEndpointsUseTheSingleNeighbourTangent()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget path = new FakeTarget();

        path.add(0F, 0F, 0F, 0F, 0F, 0F);
        path.add(60F, 0F, 0F, 0F, 0F, 0F);
        path.add(60F, 60F, 0F, 0F, 0F, 0F);

        session.setTarget(path);
        session.setRotoBezier(true);

        /* effectcraft's rule: missing neighbour contributes the vertex itself, so the first point's
         * tangent is (next - current) / 6 = (10, 0). */
        assertEquals(10F, path.outX(0), 0.001F);
        assertEquals(-10F, path.inX(0), 0.001F);
        assertDerivedLikeNeighbours(path);
    }

    private static CompPenSession.Hit hitVertex(int index)
    {
        CompPenSession.Hit hit = new CompPenSession.Hit();

        hit.kind = CompPenSession.HIT_VERTEX;
        hit.point = index;
        hit.screenDistance = 1F;

        return hit;
    }

    private static CompPenSession.Hit hitHandle(int index, int kind)
    {
        CompPenSession.Hit hit = new CompPenSession.Hit();

        hit.kind = kind;
        hit.point = index;
        hit.screenDistance = 1F;

        return hit;
    }
}
