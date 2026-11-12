package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Animated vertex-count remap ("Preserve Constant Vertex and Feather Point Count").
 *
 * <p>The model stores path animation as six scalar channels per point, addressed by point index.
 * Adding or removing a point therefore has to move those channels as well, and — for an inserted
 * point — reconstruct its values at every keyframe by splitting the segment there. These tests use
 * a fake channel store with linear interpolation (the semantics {@code CompLayer.scalarAt} has
 * between two keys) and assert the two promises the remap makes:</p>
 *
 * <ol>
 *   <li>the curve is unchanged at every existing keyframe (shape preservation),</li>
 *   <li>surviving vertices keep their exact channel keys and ticks (index remap).</li>
 * </ol>
 */
public class AnimatedPathRemapTest
{
    private static final float EPS = 0.05F;
    private static final float TICK_EPS = 0.0001F;

    /** Array-backed path with per-point/component key lists; valueAt interpolates linearly. */
    private static final class FakePath implements CompPenSession.Target, CompPenSession.Animation
    {
        final List<float[]> points = new ArrayList<>();
        /** Per point: component → ascending list of {tick, value}. */
        final List<Map<Integer, List<float[]>>> keys = new ArrayList<>();
        boolean closed;
        boolean animated = true;
        boolean supportsAnimation = true;

        FakePath add(float x, float y)
        {
            return this.add(x, y, 0F, 0F, 0F, 0F);
        }

        FakePath add(float x, float y, float inX, float inY, float outX, float outY)
        {
            this.points.add(new float[] {x, y, inX, inY, outX, outY});
            this.keys.add(new HashMap<>());

            return this;
        }

        FakePath key(int point, int component, float tick, float value)
        {
            this.ensure(point + 1);
            this.keyList(point, component).add(new float[] {tick, value});
            this.keyList(point, component).sort((a, b) -> Float.compare(a[0], b[0]));

            return this;
        }

        /** Animated keyframe at both ends of the timeline for every component of every point. */
        FakePath animateAll(float fromTick, float toTick, float dx, float dy)
        {
            for (int i = 0; i < this.points.size(); i++)
            {
                for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    float base = this.base(i, component);

                    this.key(i, component, fromTick, base);
                    this.key(i, component, toTick, base + (component == CompPenSession.COMPONENT_X ? dx * (i + 1) : 0F)
                        + (component == CompPenSession.COMPONENT_Y ? dy * (i + 1) : 0F));
                }
            }

            return this;
        }

        private void ensure(int size)
        {
            while (this.keys.size() < size)
            {
                this.keys.add(new HashMap<>());
            }
        }

        private List<float[]> keyList(int point, int component)
        {
            return this.keys.get(point).computeIfAbsent(component, (unused) -> new ArrayList<>());
        }

        /* --- Target --- */

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

            /* The channel remap already created the new slot when it shifted the array; for an
             * unanimated path no shift happened, so make room here. */
            if (this.keys.size() < this.points.size())
            {
                this.keys.add(index, new HashMap<>());
            }
        }

        @Override
        public void remove(int index)
        {
            this.points.remove(index);

            while (this.keys.size() > this.points.size())
            {
                this.keys.remove(this.keys.size() - 1);
            }
        }

        @Override
        public void setClosed(boolean closed) { this.closed = closed; }

        @Override
        public boolean animated() { return this.animated; }

        @Override
        public String label() { return "fake path"; }

        @Override
        public CompPenSession.Animation animation() { return this.supportsAnimation ? this : null; }

        /* --- Animation --- */

        @Override
        public boolean hasKey(int point, int component)
        {
            List<float[]> list = this.keys.size() > point ? this.keys.get(point).get(component) : null;

            return list != null && !list.isEmpty();
        }

        @Override
        public float base(int point, int component)
        {
            return this.points.get(point)[component];
        }

        @Override
        public float valueAt(int point, int component, float tick)
        {
            List<float[]> list = this.keys.size() > point ? this.keys.get(point).get(component) : null;

            if (list == null || list.isEmpty())
            {
                return this.base(point, component);
            }

            if (tick <= list.get(0)[0])
            {
                return list.get(0)[1];
            }

            float[] last = list.get(list.size() - 1);

            if (tick >= last[0])
            {
                return last[1];
            }

            for (int i = 1; i < list.size(); i++)
            {
                float[] after = list.get(i);

                if (tick <= after[0])
                {
                    float[] before = list.get(i - 1);
                    float span = after[0] - before[0];
                    float factor = span <= 0F ? 0F : (tick - before[0]) / span;

                    return before[1] + (after[1] - before[1]) * factor;
                }
            }

            return last[1];
        }

        @Override
        public float[] keyTicks(int point, int component)
        {
            List<float[]> list = this.keys.size() > point ? this.keys.get(point).get(component) : null;

            if (list == null || list.isEmpty())
            {
                return new float[0];
            }

            float[] ticks = new float[list.size()];

            for (int i = 0; i < list.size(); i++)
            {
                ticks[i] = list.get(i)[0];
            }

            return ticks;
        }

        @Override
        public void moveChannels(int from, int to)
        {
            this.ensure(Math.max(from, to) + 1);

            Map<Integer, List<float[]>> moved = new HashMap<>();

            for (Map.Entry<Integer, List<float[]>> entry : this.keys.get(from).entrySet())
            {
                moved.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }

            this.keys.set(to, moved);
            this.keys.set(from, new HashMap<>());
        }

        @Override
        public void clearChannels(int point)
        {
            this.ensure(point + 1);
            this.keys.get(point).clear();
        }

        @Override
        public void setKey(int point, int component, float tick, float value)
        {
            this.ensure(point + 1);

            List<float[]> list = this.keyList(point, component);

            for (float[] key : list)
            {
                if (Math.abs(key[0] - tick) < TICK_EPS)
                {
                    key[1] = value;

                    return;
                }
            }

            list.add(new float[] {tick, value});
            list.sort((a, b) -> Float.compare(a[0], b[0]));
        }

        @Override
        public void copyLoops(int fromPoint, int fromComponent, int toPoint, int toComponent)
        {
            /* Loops are covered by PathLoopSetFirstVertexTest; this fake keeps no loop state. */
        }

        @Override
        public void rotateChannels(int count, int shift)
        {
            /* Channels rotation is covered by PathLoopSetFirstVertexTest. */
        }

        @Override
        public boolean rotatePoints(int shift)
        {
            java.util.Collections.rotate(this.points, shift);

            return true;
        }
    }

    private static final class Recorder implements CompPenSession.Listener
    {
        int actions;
        int changes;
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
        }
    }

    /** Round-ish closed quad with real handles, so the split tests exercise curves, not lines. */
    private static FakePath curvedQuad(boolean closed)
    {
        FakePath path = new FakePath();

        path.add(0F, 0F, 0F, 0F, 40F, 10F);
        path.add(120F, 10F, -40F, -20F, 0F, 30F);
        path.add(120F, 120F, 0F, -30F, -40F, 0F);
        path.add(0F, 120F, 40F, 0F, 0F, 0F);
        path.closed = closed;

        return path;
    }

    /** Evaluated path as {x,y,inX,inY,outX,outY} per point at a tick. */
    private static float[] evaluated(CompPenSession.Target target, CompPenSession.Animation animation, float tick)
    {
        float[] values = new float[target.count() * CompPenSession.COMPONENT_COUNT];

        for (int point = 0; point < target.count(); point++)
        {
            for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
            {
                values[point * CompPenSession.COMPONENT_COUNT + component] = animation.valueAt(point, component, tick);
            }
        }

        return values;
    }

    private static CompPathGeometry.ArrayPath asPath(float[] values)
    {
        int count = values.length / CompPenSession.COMPONENT_COUNT;
        CompPathGeometry.ArrayPath path = new CompPathGeometry.ArrayPath(count);

        for (int i = 0; i < count; i++)
        {
            int at = i * CompPenSession.COMPONENT_COUNT;

            path.add(values[at], values[at + 1], values[at + 2], values[at + 3], values[at + 4], values[at + 5]);
        }

        return path;
    }

    /** Dense samples of the evaluated curve at a tick, as a polyline. */
    private static List<float[]> samples(CompPenSession.Target target, CompPenSession.Animation animation, float tick, boolean closed)
    {
        float[] values = evaluated(target, animation, tick);
        CompPathGeometry.ArrayPath path = asPath(values);

        path.setClosed(closed);

        int steps = 96;
        int segments = CompPathGeometry.segmentCount(path);
        List<float[]> result = new ArrayList<>();

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = CompPathGeometry.segment(path, segment);

            for (int i = 0; i <= steps; i++)
            {
                result.add(CompPathGeometry.cubicAt(control, i / (float) steps));
            }
        }

        return result;
    }

    /**
     * Largest distance from any sample of {@code from} to <b>the polyline</b> {@code to}.
     *
     * <p>Point-to-point nearest would report the sampling grid's half-spacing as error whenever the
     * two paths are sampled at different parameter positions (which a split at t != 0.5 necessarily
     * does), so the comparison has to be point-to-segment.</p>
     */
    private static float deviation(List<float[]> from, List<float[]> to)
    {
        float worst = 0F;

        for (float[] point : from)
        {
            float best = Float.MAX_VALUE;

            for (int i = 1; i < to.size(); i++)
            {
                best = Math.min(best, distanceToSegment(point, to.get(i - 1), to.get(i)));
            }

            worst = Math.max(worst, best);
        }

        return worst;
    }

    private static float distanceToSegment(float[] point, float[] a, float[] b)
    {
        float abx = b[0] - a[0];
        float aby = b[1] - a[1];
        float lengthSquared = abx * abx + aby * aby;

        if (lengthSquared < 1.0E-8F)
        {
            return CompPathGeometry.distance(point[0], point[1], a[0], a[1]);
        }

        float t = ((point[0] - a[0]) * abx + (point[1] - a[1]) * aby) / lengthSquared;

        t = Math.max(0F, Math.min(1F, t));

        return CompPathGeometry.distance(point[0], point[1], a[0] + abx * t, a[1] + aby * t);
    }

    private static CompPenSession session(Recorder recorder)
    {
        return new CompPenSession(recorder);
    }

    @Test
    public void animatedInsertKeepsTheCurveShapeAtEveryKeyframe()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true).animateAll(0F, 24F, 30F, -12F);
        float[] ticks = {0F, 6F, 12F, 18F, 24F};
        List<List<float[]>> before = new ArrayList<>();

        for (float tick : ticks)
        {
            before.add(samples(path, path, tick, true));
        }

        session.setTarget(path);
        session.setTick(12F);

        float[][] beforeValues = new float[ticks.length][];

        for (int i = 0; i < ticks.length; i++)
        {
            beforeValues[i] = evaluated(path, path, ticks[i]);
        }

        assertTrue(session.insertOnSegment(0, 0.5F), "animated insert must be accepted");
        assertEquals(5, path.count());
        assertEquals(1, recorder.actions, "one vertex insert = one undo checkpoint");
        assertTrue(recorder.status.contains("重映射"), recorder.status);

        for (int i = 0; i < ticks.length; i++)
        {
            float deviation = deviation(before.get(i), samples(path, path, ticks[i], true));

            assertTrue(deviation < EPS,
                "curve at tick " + ticks[i] + " moved by " + deviation + " px");
        }

        /* Old points 0 and 2..3 moved to indices 0 and 3..4; their positions must be untouched.
         * (The two split endpoints of the edited segment legitimately get new tangent handles.) */
        int[] newIndex = {0, 2, 3, 4};

        for (int i = 0; i < ticks.length; i++)
        {
            for (int old = 0; old < 4; old++)
            {
                for (int component = 0; component < 2; component++)
                {
                    assertEquals(beforeValues[i][old * CompPenSession.COMPONENT_COUNT + component],
                        path.valueAt(newIndex[old], component, ticks[i]), 0.0001F,
                        "tick " + ticks[i] + " old point " + old + " component " + component);
                }
            }

            for (int old = 2; old < 4; old++)
            {
                for (int component = CompPenSession.COMPONENT_IN_X; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    assertEquals(beforeValues[i][old * CompPenSession.COMPONENT_COUNT + component],
                        path.valueAt(newIndex[old], component, ticks[i]), 0.0001F,
                        "surviving handle of old point " + old);
                }
            }
        }

        /* The inserted point exists at every existing key time. */
        for (float tick : new float[] {0F, 24F})
        {
            assertTrue(path.hasKey(1, CompPenSession.COMPONENT_X));
            assertTrue(path.valueAt(1, CompPenSession.COMPONENT_X, tick) > 0F);
        }
    }

    @Test
    public void animatedInsertOnTheWrappedSegmentAlsoWorks()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true).animateAll(0F, 30F, 20F, 15F);
        float[] ticks = {0F, 15F, 30F};
        List<List<float[]>> before = new ArrayList<>();

        for (float tick : ticks)
        {
            before.add(samples(path, path, tick, true));
        }

        session.setTarget(path);
        session.setTick(15F);

        /* The last segment of a closed path wraps from the last vertex to vertex 0. */
        assertTrue(session.insertOnSegment(3, 0.4F));
        assertEquals(5, path.count());

        for (int i = 0; i < ticks.length; i++)
        {
            float deviation = deviation(before.get(i), samples(path, path, ticks[i], true));

            assertTrue(deviation < EPS, "wrapped-segment insert changed tick " + ticks[i] + " by " + deviation);
        }

        /* Index 0 must still be the original first vertex (only its in-handle was split). */
        assertEquals(0F, path.valueAt(0, CompPenSession.COMPONENT_X, 0F), 0.001F);
    }

    @Test
    public void animatedInsertAtAPlayheadBetweenKeysIsExactThere()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true).animateAll(0F, 24F, 24F, -8F);

        List<float[]> before = samples(path, path, 7F, true);

        session.setTarget(path);
        session.setTick(7F);

        assertTrue(session.insertOnSegment(2, 0.3F));

        float deviation = deviation(before, samples(path, path, 7F, true));

        assertTrue(deviation < EPS, "the playhead keeps the user's split parameter; deviation " + deviation);
    }

    @Test
    public void animatedDeleteRemapsIndicesAndKeepsSurvivingKeys()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true).animateAll(0F, 24F, 10F, 10F);
        float[] ticks = {0F, 12F, 24F};
        float[][] oldValues = new float[ticks.length][];

        for (int i = 0; i < ticks.length; i++)
        {
            oldValues[i] = evaluated(path, path, ticks[i]);
        }

        session.setTarget(path);
        session.setTick(12F);
        session.selectVertex(1, false);

        assertTrue(session.deleteSelection());
        assertEquals(3, path.count());
        assertEquals(1, recorder.actions);
        assertTrue(recorder.status.contains("重映射"), recorder.status);

        /* Every surviving point keeps the exact values it had at its old index. */
        int[][] oldIndex = {{0, 0}, {2, 1}, {3, 2}};

        for (int i = 0; i < ticks.length; i++)
        {
            for (int[] mapping : oldIndex)
            {
                for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    assertEquals(oldValues[i][mapping[0] * CompPenSession.COMPONENT_COUNT + component],
                        path.valueAt(mapping[1], component, ticks[i]), 0.0001F,
                        "tick " + ticks[i] + " component " + component);
                }
            }
        }
    }

    @Test
    public void animatedMultiDeleteRemapsAllIndices()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true);

        path.add(60F, -40F);
        path.animateAll(0F, 10F, 5F, 5F);
        session.setTarget(path);
        session.selectVertex(1, false);
        session.selectVertex(3, true);

        assertTrue(session.deleteSelection());
        assertEquals(3, path.count());
        assertEquals(1, recorder.actions);

        /* Original point 2 is now index 1 (point 1 removed), original point 4 is now index 2. */
        assertEquals(120F, path.valueAt(1, CompPenSession.COMPONENT_X, 0F), 0.001F);
        assertEquals(60F, path.valueAt(2, CompPenSession.COMPONENT_X, 0F), 0.001F);
    }

    @Test
    public void animatedAppendSplitsTheLastSegmentOnOtherKeyframes()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = new FakePath();

        path.add(0F, 0F);
        path.add(100F, 0F);
        path.add(100F, 100F);
        path.animateAll(0F, 24F, 12F, 6F);
        session.setTick(12F);
        session.begin(path);

        /* Append a vertex by clicking empty canvas while drawing. */
        assertEquals(CompPenSession.PRESS_CONSUMED,
            session.press(200F, 200F, 200F, 200F, false, false, CompPenSession.CLOSE_RADIUS_PX,
                CompPenSession.Hit.none()));
        session.release();

        assertEquals(4, path.count());
        assertEquals(1, recorder.actions, "the append must be one undo step");
        assertEquals(200F, path.valueAt(3, CompPenSession.COMPONENT_X, 12F), 0.001F, "clicked position at the playhead");
        assertEquals(200F, path.valueAt(3, CompPenSession.COMPONENT_Y, 12F), 0.001F);

        /* At other keyframes the new vertex sits on the old curve: the last segment split at 0.5.
         * Tick 0: (100,0)..(100,100) → (100,50); tick 24: (124,12)..(136,118) → (130,65). */
        assertEquals(100F, path.valueAt(3, CompPenSession.COMPONENT_X, 0F), 0.001F);
        assertEquals(50F, path.valueAt(3, CompPenSession.COMPONENT_Y, 0F), 0.001F);
        assertEquals(130F, path.valueAt(3, CompPenSession.COMPONENT_X, 24F), 0.001F);
        assertEquals(65F, path.valueAt(3, CompPenSession.COMPONENT_Y, 24F), 0.001F);
        assertTrue(deviation(samples(path, path, 0F, false), samples(path, path, 24F, false)) > 0F,
            "the animated path really moves between keyframes");
    }

    @Test
    public void unanimatedPathsKeepTheirExactOldBehaviour()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(false);

        path.animated = false;
        session.setTarget(path);

        assertTrue(session.insertOnSegment(0, 0.5F));
        assertEquals(5, path.count());
        assertEquals(1, recorder.actions);

        for (Map<Integer, List<float[]>> point : path.keys)
        {
            assertTrue(point.isEmpty(), "an unanimated path must not grow animation channels");
        }

        session.selectVertex(0, false);
        assertTrue(session.deleteSelection());
        assertEquals(4, path.count());
        assertEquals(2, recorder.actions);
    }

    @Test
    public void animatedPathWithoutChannelAccessIsRefusedNotCorrupted()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true);

        path.supportsAnimation = false;
        session.setTarget(path);

        assertFalse(session.insertOnSegment(0, 0.5F));
        assertEquals(4, path.count(), "no point was added");
        assertEquals(0, recorder.actions, "no undo checkpoint for a refused edit");
        assertTrue(recorder.status.contains("不可用"), recorder.status);

        session.selectVertex(1, false);
        assertFalse(session.deleteSelection());
        assertEquals(4, path.count());
    }

    @Test
    public void remapKeepsEveryKeyTick()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true);
        float[] ticks = {0F, 5F, 17F, 24F};

        for (float tick : ticks)
        {
            path.key(0, CompPenSession.COMPONENT_X, tick, tick * 2F + 1F);
            path.key(1, CompPenSession.COMPONENT_Y, tick, tick * 3F - 2F);
        }

        session.setTarget(path);
        session.setTick(17F);
        assertTrue(session.insertOnSegment(0, 0.5F));

        float[] moved = path.keyTicks(2, CompPenSession.COMPONENT_Y);

        assertNotNull(moved);
        assertEquals(ticks.length, moved.length, "moving a channel must not lose or add key ticks");

        for (int i = 0; i < ticks.length; i++)
        {
            assertEquals(ticks[i], moved[i], 0.0001F);
        }
    }

    @Test
    public void remapSamplesUseKeyTicksPlusThePlayhead()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true);

        path.key(0, CompPenSession.COMPONENT_X, 0F, 0F);
        path.key(0, CompPenSession.COMPONENT_X, 24F, 100F);
        path.key(1, CompPenSession.COMPONENT_X, 0F, 10F);
        path.key(1, CompPenSession.COMPONENT_X, 24F, 90F);

        session.setTarget(path);
        session.setTick(7F);
        assertTrue(session.insertOnSegment(1, 0.5F));

        float[] inserted = path.keyTicks(2, CompPenSession.COMPONENT_X);

        /* 0 and 24 come from the animated channels; 7 is the playhead the user clicked at. */
        assertEquals(3, inserted.length, "expected keys at 0, 7 and 24: " + java.util.Arrays.toString(inserted));
        assertEquals(0F, inserted[0], 0.0001F);
        assertEquals(7F, inserted[1], 0.0001F);
        assertEquals(24F, inserted[2], 0.0001F);
    }

    @Test
    public void inspectorAppendApiIsAnimationSafe()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = new FakePath();

        path.add(0F, 0F);
        path.add(100F, 0F);
        path.animateAll(0F, 24F, 0F, 0F);
        session.setTarget(path);
        session.setTick(12F);

        assertTrue(session.appendPoint(300F, 300F));
        assertEquals(3, path.count());
        assertEquals(1, recorder.actions);
        assertEquals(300F, path.valueAt(2, CompPenSession.COMPONENT_X, 12F), 0.001F);
        assertEquals(50F, path.valueAt(2, CompPenSession.COMPONENT_X, 0F), 0.001F,
            "other keyframes get the last segment split at 0.5");
    }

    @Test
    public void inspectorDeleteApiRemapsChannels()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = session(recorder);
        FakePath path = curvedQuad(true).animateAll(0F, 20F, 8F, 8F);

        session.setTarget(path);
        session.setTick(20F);

        assertTrue(session.deletePoint(0));
        assertEquals(3, path.count());
        assertEquals(1, recorder.actions);
        assertEquals(120F, path.valueAt(0, CompPenSession.COMPONENT_X, 0F), 0.001F,
            "old point 1 moved to index 0");
    }
}
