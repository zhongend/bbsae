package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pen state machine, exercised exactly like a user would: press, drag, release, key.
 *
 * <p>The fake target is a plain list of six-float vertices, so every assertion is about behaviour
 * (adding/closing/inserting/mirroring) rather than about BBS or the renderer. Undo granularity is
 * asserted too: AE's pen produces one undo step per point, and the listener is the only place that
 * can prove it.</p>
 */
public class CompPenSessionTest
{
    private static final float CLOSE = CompPenSession.CLOSE_RADIUS_PX;

    /** Array-backed mask/shape path. Values live where the real adapter reads them from. */
    private static final class FakeTarget implements CompPenSession.Target
    {
        final List<float[]> points = new ArrayList<>();
        boolean closed;
        boolean animated;

        FakeTarget add(float x, float y)
        {
            return this.add(x, y, 0F, 0F, 0F, 0F);
        }

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
        public void setClosed(boolean closed)
        {
            this.closed = closed;
        }

        @Override
        public boolean animated()
        {
            return this.animated;
        }

        @Override
        public String label()
        {
            return "test path";
        }
    }

    private static final class Recorder implements CompPenSession.Listener
    {
        int actions;
        int changes;
        int closedChanges;
        int structureChanges;
        String status = "";

        @Override
        public void beginAction(String name)
        {
            this.actions += 1;
        }

        @Override
        public void changed()
        {
            this.changes += 1;
        }

        @Override
        public void status(String message)
        {
            this.status = message;
        }

        @Override
        public void closedChanged()
        {
            this.closedChanges += 1;
        }

        @Override
        public void structureChanged()
        {
            this.structureChanges += 1;
        }
    }

    private static CompPenSession.Hit vertexHit(int index)
    {
        return vertexHit(index, 2F);
    }

    private static CompPenSession.Hit vertexHit(int index, float screenDistance)
    {
        CompPenSession.Hit hit = new CompPenSession.Hit();

        hit.kind = CompPenSession.HIT_VERTEX;
        hit.point = index;
        hit.screenDistance = screenDistance;

        return hit;
    }

    private static CompPenSession.Hit segmentHit(int segment, float t)
    {
        CompPenSession.Hit hit = new CompPenSession.Hit();

        hit.kind = CompPenSession.HIT_SEGMENT;
        hit.segment = segment;
        hit.t = t;
        hit.screenDistance = 2F;

        return hit;
    }

    private static FakeTarget square(boolean closed)
    {
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(100F, 0F);
        target.add(100F, 100F);
        target.add(0F, 100F);
        target.closed = closed;

        return target;
    }

    @Test
    public void clickClickDragClickFirstCloses()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = new FakeTarget();

        session.begin(target);
        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(0F, 0F, 10F, 10F, false, false, CLOSE, CompPenSession.Hit.none()));
        session.release();
        assertEquals(1, target.count());

        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(100F, 0F, 110F, 10F, false, false, CLOSE, CompPenSession.Hit.none()));
        session.release();
        assertEquals(2, target.count());

        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(200F, 100F, 210F, 110F, false, false, CLOSE, CompPenSession.Hit.none()));
        assertTrue(session.drag(220F, 120F, 230F, 130F, false, false), "tangent drag consumed");
        session.release();

        assertEquals(20F, target.outX(2), 0.001F, "out handle = cursor - vertex");
        assertEquals(20F, target.outY(2), 0.001F);
        assertEquals(-20F, target.inX(2), 0.001F, "in handle mirrors by default");
        assertEquals(-20F, target.inY(2), 0.001F);

        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(0F, 0F, 3F, 3F, false, false, CLOSE, vertexHit(0)));

        assertTrue(target.closed, "clicking the start vertex closes the path");
        assertFalse(session.isDrawing());
        assertEquals(3, target.count());
        assertEquals(1, recorder.closedChanges);
        assertEquals(4, recorder.actions, "three points + one close = four undo checkpoints");
    }

    @Test
    public void enterAndEscapeEndThePathOpen()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = new FakeTarget();

        session.begin(target);
        session.press(0F, 0F, 0F, 0F, false, false, CLOSE, CompPenSession.Hit.none());
        assertTrue(session.isDrawing());
        assertTrue(session.key(CompPenSession.KEY_ENTER));
        assertFalse(session.isDrawing());
        assertFalse(target.closed, "Enter keeps the path open");

        session.begin(target);
        session.press(50F, 50F, 50F, 50F, false, false, CLOSE, CompPenSession.Hit.none());
        assertTrue(session.key(CompPenSession.KEY_ESCAPE));
        assertFalse(session.isDrawing());
        assertFalse(target.closed);
    }

    @Test
    public void shiftConstrainsTheNextPointTo45Degrees()
    {
        CompPenSession session = new CompPenSession(new Recorder());
        FakeTarget target = new FakeTarget();

        session.begin(target);
        session.press(0F, 0F, 0F, 0F, false, false, CLOSE, CompPenSession.Hit.none());
        session.release();
        session.press(100F, 10F, 100F, 10F, true, false, CLOSE, CompPenSession.Hit.none());

        assertEquals(100.4988F, target.x(1), 0.01F, "length is kept");
        assertEquals(0F, target.y(1), 0.001F, "direction snaps to horizontal");
    }

    @Test
    public void penOnSegmentInsertsAVertexWithoutMovingTheShape()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = square(true);

        session.setTarget(target);
        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(50F, 0F, 50F, 0F, false, false, CLOSE, segmentHit(0, 0.5F)));

        assertEquals(5, target.count());
        assertEquals(1, recorder.actions);
        assertTrue(target.closed);
        assertEquals(50F, target.x(1), 0.01F);
        assertEquals(0F, target.y(1), 0.01F);
        assertTrue(session.selection().contains(1), "the new vertex is selected");

        /* The split keeps the square's corners where they were. */
        assertEquals(100F, target.x(2), 0.01F);
        assertEquals(0F, target.y(2), 0.01F);
    }

    @Test
    public void penOnLastVertexResumesAnOpenPath()
    {
        CompPenSession session = new CompPenSession(new Recorder());
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(100F, 0F);
        target.add(100F, 100F);
        session.setTarget(target);

        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(100F, 100F, 100F, 100F, false, false, CLOSE, vertexHit(2)));
        assertTrue(session.isDrawing(), "clicking the open end re-arms drawing");

        session.press(200F, 100F, 200F, 100F, false, false, CLOSE, CompPenSession.Hit.none());
        assertEquals(4, target.count());
    }

    @Test
    public void deleteVertexClearsClosedWhenTooFewRemain()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(100F, 0F);
        target.add(100F, 100F);
        target.closed = true;
        session.setTarget(target);
        session.selectVertex(1, false);

        assertTrue(session.deleteSelection());
        assertEquals(2, target.count());
        assertFalse(target.closed, "a two-point path cannot stay closed");
        assertEquals(1, recorder.closedChanges);
        assertFalse(session.hasSelection());
    }

    @Test
    public void animatedPathsRefuseInsertAndDelete()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = square(true);

        target.animated = true;
        session.setTarget(target);

        session.press(50F, 0F, 50F, 0F, false, false, CLOSE, segmentHit(0, 0.5F));
        assertEquals(4, target.count(), "no vertex inserted on an animated path");
        assertTrue(recorder.status.contains("动画"), "the user is told why: " + recorder.status);

        session.selectVertex(0, false);
        assertFalse(session.deleteSelection());
        assertEquals(4, target.count());

        assertTrue(session.convertVertex(0, true), "converting handles is still allowed");
    }

    @Test
    public void handleDragMirrorsButAltBreaksTheLink()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(100F, 0F);
        session.setTarget(target);

        CompPenSession.Hit outHit = new CompPenSession.Hit();

        outHit.kind = CompPenSession.HIT_OUT;
        outHit.point = 1;
        outHit.screenDistance = 2F;

        session.press(100F, 0F, 100F, 0F, false, false, CLOSE, outHit);
        session.drag(130F, 20F, 130F, 20F, false, false);

        assertEquals(30F, target.outX(1), 0.001F);
        assertEquals(20F, target.outY(1), 0.001F);
        assertEquals(-30F, target.inX(1), 0.001F, "mirrored by default");
        assertEquals(-20F, target.inY(1), 0.001F);
        session.release();

        /* Alt: the incoming handle must not follow. */
        session.press(100F, 0F, 100F, 0F, false, false, CLOSE, outHit);
        session.drag(150F, 0F, 150F, 0F, false, true);

        assertEquals(50F, target.outX(1), 0.001F);
        assertEquals(-30F, target.inX(1), 0.001F, "Alt breaks symmetry and leaves the in handle alone");
        session.release();
    }

    @Test
    public void mirroredHandleCannotPassTheNeighbour()
    {
        CompPenSession session = new CompPenSession(new Recorder());
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(50F, 0F);
        target.add(150F, 0F);
        session.setTarget(target);

        CompPenSession.Hit outHit = new CompPenSession.Hit();

        outHit.kind = CompPenSession.HIT_OUT;
        outHit.point = 1;
        outHit.screenDistance = 2F;

        session.press(50F, 0F, 50F, 0F, false, false, CLOSE, outHit);
        session.drag(200F, 0F, 200F, 0F, false, false);

        assertEquals(150F, target.outX(1), 0.001F, "dragged side is never clamped");
        assertEquals(-50F, target.inX(1), 0.001F, "mirror is clamped to the distance to the previous vertex");
    }

    @Test
    public void convertVertexTogglesCornerAndSmooth()
    {
        CompPenSession session = new CompPenSession(new Recorder());
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(50F, 50F);
        target.add(100F, 0F);
        session.setTarget(target);

        assertTrue(session.convertVertex(1, null));
        assertEquals(16.6667F, target.outX(1), 0.01F);
        assertEquals(0F, target.outY(1), 0.01F);
        assertEquals(-16.6667F, target.inX(1), 0.01F);

        assertTrue(session.convertVertex(1, null));
        assertEquals(0F, target.outX(1), 0.001F, "second conversion returns to a corner");
        assertEquals(0F, target.inX(1), 0.001F);
    }

    @Test
    public void closingAndOpeningThePathIsOneUndoStepEach()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = square(true);

        session.setTarget(target);
        assertTrue(session.openPath());
        assertEquals(1, recorder.actions);
        assertFalse(target.closed);

        assertTrue(session.closePath());
        assertEquals(2, recorder.actions);
        assertTrue(target.closed);
        assertEquals(2, recorder.closedChanges);
    }

    @Test
    public void emptyPressAsksTheUiForANewPath()
    {
        CompPenSession session = new CompPenSession(new Recorder());

        assertEquals(CompPenSession.PRESS_REQUEST_NEW_PATH,
            session.press(10F, 10F, 10F, 10F, false, false, CLOSE, CompPenSession.Hit.none()));

        FakeTarget target = new FakeTarget();

        session.begin(target);
        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(10F, 10F, 10F, 10F, false, false, CLOSE, CompPenSession.Hit.none()));
        assertEquals(1, target.count());
    }

    @Test
    public void hitTestPrefersVerticesThenSegments()
    {
        CompPathGeometry.ArrayPath path = new CompPathGeometry.ArrayPath(4);

        path.add(0F, 0F);
        path.add(100F, 0F);
        path.add(100F, 100F);
        path.add(0F, 100F);
        path.setClosed(true);
        CompPenSession.Projector projector = (x, y) -> new float[] {x, y};

        CompPenSession.Hit vertex = CompPenSession.hitTest(path, projector, 2F, 2F, 9F, 9F, 6F, null);

        assertEquals(CompPenSession.HIT_VERTEX, vertex.kind);
        assertEquals(0, vertex.point);

        CompPenSession.Hit segment = CompPenSession.hitTest(path, projector, 50F, 2F, 9F, 9F, 6F, null);

        assertEquals(CompPenSession.HIT_SEGMENT, segment.kind);
        assertEquals(0, segment.segment);
        assertEquals(0.5F, segment.t, 0.03F);

        CompPenSession.Hit none = CompPenSession.hitTest(path, projector, 500F, 500F, 9F, 9F, 6F, null);

        assertEquals(CompPenSession.HIT_NONE, none.kind);
        assertFalse(none.valid());
    }

    @Test
    public void escapeClearsSelectionWhenIdle()
    {
        CompPenSession session = new CompPenSession(new Recorder());
        FakeTarget target = square(true);

        session.setTarget(target);
        session.selectVertex(2, false);
        assertTrue(session.hasSelection());
        assertTrue(session.key(CompPenSession.KEY_ESCAPE));
        assertFalse(session.hasSelection());
    }

    @Test
    public void dragWithoutPressIsIgnored()
    {
        CompPenSession session = new CompPenSession(new Recorder());

        assertFalse(session.drag(0F, 0F, 0F, 0F, false, false));
        assertFalse(session.release());
        assertNotNull(session.selection());
    }

    @Test
    public void hitTestHandlesZeroOneAndTwoPointPaths()
    {
        CompPenSession.Projector projector = (x, y) -> new float[] {x, y};

        assertHit(CompPenSession.HIT_NONE, CompPenSession.hitTest(
            new CompPathGeometry.ArrayPath(0), projector, 0F, 0F, 9F, 9F, 6F, null));

        CompPathGeometry.ArrayPath single = new CompPathGeometry.ArrayPath(1);

        single.add(10F, 10F);
        assertHit(CompPenSession.HIT_VERTEX, CompPenSession.hitTest(single, projector, 11F, 11F, 9F, 9F, 6F, null));
        assertHit(CompPenSession.HIT_NONE, CompPenSession.hitTest(single, projector, 500F, 500F, 9F, 9F, 6F, null));

        CompPathGeometry.ArrayPath two = new CompPathGeometry.ArrayPath(2);

        two.add(0F, 0F);
        two.add(100F, 0F);

        CompPenSession.Hit segment = CompPenSession.hitTest(two, projector, 50F, 2F, 9F, 9F, 6F, null);

        assertHit(CompPenSession.HIT_SEGMENT, segment);
        assertEquals(0, segment.segment);
        assertEquals(0.5F, segment.t, 0.03F);

        /* A closed two-point path is a degenerate sliver, but the hit test must still answer
         * instead of throwing or returning a nonsense segment index. */
        two.setClosed(true);
        assertHit(CompPenSession.HIT_SEGMENT, CompPenSession.hitTest(two, projector, 50F, 2F, 9F, 9F, 6F, null));
    }

    @Test
    public void segmentHitParameterIsUsableForSplitting()
    {
        CompPathGeometry.ArrayPath path = new CompPathGeometry.ArrayPath(4);

        path.add(0F, 0F, 0F, 0F, 20F, 20F);
        path.add(200F, 0F, -30F, 20F, 30F, 0F);
        path.add(200F, 200F, 0F, -30F, 0F, 0F);
        path.add(0F, 200F, 0F, 30F, 0F, 0F);
        path.setClosed(true);

        float[] expected = CompPathGeometry.pointOnPath(path, 1, 0.25F);
        CompPenSession.Projector projector = (x, y) -> new float[] {x, y};
        CompPenSession.Hit hit = CompPenSession.hitTest(path, projector, expected[0], expected[1], 9F, 9F, 6F, null);

        assertHit(CompPenSession.HIT_SEGMENT, hit);
        assertEquals(1, hit.segment, "segment index must survive projection");
        assertEquals(0.25F, hit.t, 0.03F, "t must be precise enough to split without moving the curve");
    }

    @Test
    public void closingRequiresTheCursorToBeWithinTheCloseRadius()
    {
        CompPenSession session = new CompPenSession(new Recorder());
        FakeTarget target = new FakeTarget();

        session.begin(target);
        session.press(0F, 0F, 0F, 0F, false, false, CLOSE, CompPenSession.Hit.none());
        session.release();
        session.press(100F, 0F, 100F, 0F, false, false, CLOSE, CompPenSession.Hit.none());
        session.release();

        /* Just outside the 8 px closing radius: the click must not close the path. */
        session.press(0F, 0F, 9F, 0F, false, false, CLOSE, vertexHit(0, 9F));
        assertFalse(target.closed, "a click 9 px away from the start is not a closing gesture");
        assertTrue(session.isDrawing(), "the path keeps being drawn");
        session.release();

        /* Inside the radius it closes. */
        session.press(0F, 0F, 3F, 0F, false, false, CLOSE, vertexHit(0, 3F));
        assertTrue(target.closed);
        assertFalse(session.isDrawing());
    }

    @Test
    public void structuralEditsNotifyTheHost()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        FakeTarget target = new FakeTarget();

        target.add(0F, 0F);
        target.add(100F, 0F);
        target.add(100F, 100F);
        target.closed = true;
        session.setTarget(target);

        /* Insert on a segment = vertex count changed. */
        session.press(50F, 0F, 50F, 0F, false, false, CLOSE, segmentHit(0, 0.5F));
        assertEquals(1, recorder.structureChanges, "segment insert must tell the host to rebuild sheets");

        /* Append = vertex count changed. */
        session.setTick(0F);
        assertTrue(session.appendPoint(200F, 200F));
        assertEquals(2, recorder.structureChanges);

        /* Delete = vertex count changed. */
        session.selectVertex(0, false);
        assertTrue(session.deleteSelection());
        assertEquals(3, recorder.structureChanges);

        /* Pure vertex moves / handle edits must NOT rebuild the sheet structure. */
        session.selectVertex(0, false);
        session.press(target.x(0), target.y(0), target.x(0), target.y(0), false, false, CLOSE, vertexHit(0));
        session.drag(target.x(0) + 10F, target.y(0) + 10F, target.x(0) + 10F, target.y(0) + 10F, false, false);
        session.release();
        assertEquals(3, recorder.structureChanges, "a drag keeps the point count, no sheet rebuild needed");
    }

    private static void assertHit(int expected, CompPenSession.Hit hit)
    {
        assertEquals(expected, hit.kind, "hit kind");
    }
}
