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
 * Round 3 fidelity work: repeat loops survive vertex index changes, and AE's Set First Vertex.
 *
 * <p>Both features share one problem: a point's animation lives in six scalar channels addressed by
 * index, and each channel can carry absolute-time repeat blocks ({@code KeyframeLoop}). Moving a
 * channel to a new point index must move its keys <b>and</b> its loops; a newly inserted point's
 * fresh channels must join the same loops, or the path would stop repeating on that vertex.
 * Rotating a closed path (Set First Vertex) is the same problem for every channel at once.</p>
 */
public class PathLoopSetFirstVertexTest
{
    private static final float TICK_EPS = 0.0001F;

    private record Loop(String id, float start, float sourceEnd, float end)
    {
    }

    /** Animated path with keys <b>and</b> repeat loops; linear value interpolation like a channel. */
    private static final class LoopPath implements CompPenSession.Target, CompPenSession.Animation
    {
        final List<float[]> points = new ArrayList<>();
        final List<Map<Integer, List<float[]>>> keys = new ArrayList<>();
        final List<Map<Integer, List<Loop>>> loops = new ArrayList<>();
        boolean closed;
        boolean animated = true;
        boolean canRotate = true;

        LoopPath add(float x, float y)
        {
            return this.add(x, y, 0F, 0F, 0F, 0F);
        }

        LoopPath add(float x, float y, float inX, float inY, float outX, float outY)
        {
            this.points.add(new float[] {x, y, inX, inY, outX, outY});
            this.keys.add(new HashMap<>());
            this.loops.add(new HashMap<>());

            return this;
        }

        LoopPath key(int point, int component, float tick, float value)
        {
            this.ensure(point + 1);
            List<float[]> list = this.keyList(point, component);

            list.add(new float[] {tick, value});
            list.sort((a, b) -> Float.compare(a[0], b[0]));

            return this;
        }

        LoopPath animate(float fromTick, float toTick, float dx, float dy)
        {
            for (int i = 0; i < this.points.size(); i++)
            {
                for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    float base = this.points.get(i)[component];

                    this.key(i, component, fromTick, base);
                    this.key(i, component, toTick, base
                        + (component == CompPenSession.COMPONENT_X ? dx * (i + 1) : 0F)
                        + (component == CompPenSession.COMPONENT_Y ? dy * (i + 1) : 0F));
                }
            }

            return this;
        }

        LoopPath loop(int point, int component, Loop loop)
        {
            this.ensure(point + 1);
            this.loopList(point, component).removeIf((existing) -> existing.id().equals(loop.id()));
            this.loopList(point, component).add(loop);

            return this;
        }

        List<Loop> loopsOf(int point, int component)
        {
            if (this.loops.size() <= point)
            {
                return List.of();
            }

            List<Loop> list = this.loops.get(point).get(component);

            return list == null ? List.of() : list;
        }

        private void ensure(int size)
        {
            while (this.keys.size() < size)
            {
                this.keys.add(new HashMap<>());
                this.loops.add(new HashMap<>());
            }
        }

        private List<float[]> keyList(int point, int component)
        {
            return this.keys.get(point).computeIfAbsent(component, (unused) -> new ArrayList<>());
        }

        private List<Loop> loopList(int point, int component)
        {
            return this.loops.get(point).computeIfAbsent(component, (unused) -> new ArrayList<>());
        }

        /* --- Target --- */

        @Override
        public int count() { return this.points.size(); }

        @Override
        public float x(int index) { return this.valueAt(index, CompPenSession.COMPONENT_X, 0F); }

        @Override
        public float y(int index) { return this.valueAt(index, CompPenSession.COMPONENT_Y, 0F); }

        @Override
        public float inX(int index) { return this.valueAt(index, CompPenSession.COMPONENT_IN_X, 0F); }

        @Override
        public float inY(int index) { return this.valueAt(index, CompPenSession.COMPONENT_IN_Y, 0F); }

        @Override
        public float outX(int index) { return this.valueAt(index, CompPenSession.COMPONENT_OUT_X, 0F); }

        @Override
        public float outY(int index) { return this.valueAt(index, CompPenSession.COMPONENT_OUT_Y, 0F); }

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

            if (this.keys.size() < this.points.size())
            {
                this.keys.add(index, new HashMap<>());
                this.loops.add(index, new HashMap<>());
            }
        }

        @Override
        public void remove(int index)
        {
            this.points.remove(index);

            while (this.keys.size() > this.points.size())
            {
                this.keys.remove(this.keys.size() - 1);
                this.loops.remove(this.loops.size() - 1);
            }
        }

        @Override
        public void setClosed(boolean closed) { this.closed = closed; }

        @Override
        public boolean animated() { return this.animated; }

        @Override
        public String label() { return "loop path"; }

        @Override
        public CompPenSession.Animation animation() { return this; }

        @Override
        public boolean rotatePoints(int shift)
        {
            java.util.Collections.rotate(this.points, shift);

            return true;
        }

        @Override
        public boolean canRotatePoints()
        {
            return this.canRotate;
        }

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

            this.keys.set(to, copyKeys(this.keys.get(from)));
            this.loops.set(to, new HashMap<>(this.loops.get(from)));
            this.keys.set(from, new HashMap<>());
            this.loops.set(from, new HashMap<>());
        }

        @Override
        public void clearChannels(int point)
        {
            this.ensure(point + 1);
            this.keys.get(point).clear();
            this.loops.get(point).clear();
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
            this.ensure(Math.max(fromPoint, toPoint) + 1);

            List<Loop> source = this.loops.get(fromPoint).get(fromComponent);

            if (source == null || source.isEmpty())
            {
                return;
            }

            List<Loop> destination = this.loopList(toPoint, toComponent);

            destination.removeIf((loop) -> source.stream().anyMatch((other) -> other.id().equals(loop.id())));
            destination.addAll(source);
        }

        @Override
        public void rotateChannels(int count, int shift)
        {
            int n = count;
            int rotated = ((shift % n) + n) % n;

            if (n <= 0 || rotated == 0)
            {
                return;
            }

            for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
            {
                List<Map<Integer, List<float[]>>> oldKeys = new ArrayList<>(n);
                List<Map<Integer, List<Loop>>> oldLoops = new ArrayList<>(n);

                for (int point = 0; point < n; point++)
                {
                    oldKeys.add(copyKeys(this.keys.get(point)));
                    oldLoops.add(new HashMap<>(this.loops.get(point)));
                }

                for (int point = 0; point < n; point++)
                {
                    this.keys.get(point).remove(component);
                    this.loops.get(point).remove(component);
                }

                for (int point = 0; point < n; point++)
                {
                    int source = (point + rotated) % n;
                    List<float[]> sourceKeys = oldKeys.get(source).get(component);

                    if (sourceKeys != null && !sourceKeys.isEmpty())
                    {
                        this.keys.get(point).put(component, new ArrayList<>(sourceKeys));
                    }

                    List<Loop> sourceLoops = oldLoops.get(source).get(component);

                    if (sourceLoops != null && !sourceLoops.isEmpty())
                    {
                        this.loops.get(point).put(component, new ArrayList<>(sourceLoops));
                    }
                }
            }
        }

        private static Map<Integer, List<float[]>> copyKeys(Map<Integer, List<float[]>> source)
        {
            Map<Integer, List<float[]>> copy = new HashMap<>();

            for (Map.Entry<Integer, List<float[]>> entry : source.entrySet())
            {
                List<float[]> list = new ArrayList<>();

                for (float[] key : entry.getValue())
                {
                    list.add(new float[] {key[0], key[1]});
                }

                copy.put(entry.getKey(), list);
            }

            return copy;
        }
    }

    private static final class Recorder implements CompPenSession.Listener
    {
        int actions;
        int changes;
        String status = "";

        @Override
        public void beginAction(String name) { this.actions += 1; }

        @Override
        public void changed() { this.changes += 1; }

        @Override
        public void status(String message) { this.status = message; }

        @Override
        public void closedChanged() {}
    }

    private static LoopPath quad(boolean closed)
    {
        LoopPath path = new LoopPath();

        path.add(0F, 0F, 0F, 0F, 40F, 10F);
        path.add(120F, 10F, -40F, -20F, 0F, 30F);
        path.add(120F, 120F, 0F, -30F, -40F, 0F);
        path.add(0F, 120F, 40F, 0F, 0F, 0F);
        path.closed = closed;

        return path;
    }

    /** Polyline samples of the closed path from a target's current values (no channel evaluation). */
    private static List<float[]> modelSamples(LoopPath path)
    {
        CompPathGeometry.ArrayPath view = new CompPathGeometry.ArrayPath(path.count());

        for (int i = 0; i < path.count(); i++)
        {
            view.add(path.points.get(i)[0], path.points.get(i)[1], path.points.get(i)[2],
                path.points.get(i)[3], path.points.get(i)[4], path.points.get(i)[5]);
        }

        view.setClosed(path.closed);

        return sample(view);
    }

    private static List<float[]> evaluatedSamples(LoopPath path, float tick)
    {
        CompPathGeometry.ArrayPath view = new CompPathGeometry.ArrayPath(path.count());

        for (int i = 0; i < path.count(); i++)
        {
            view.add(path.valueAt(i, CompPenSession.COMPONENT_X, tick),
                path.valueAt(i, CompPenSession.COMPONENT_Y, tick),
                path.valueAt(i, CompPenSession.COMPONENT_IN_X, tick),
                path.valueAt(i, CompPenSession.COMPONENT_IN_Y, tick),
                path.valueAt(i, CompPenSession.COMPONENT_OUT_X, tick),
                path.valueAt(i, CompPenSession.COMPONENT_OUT_Y, tick));
        }

        view.setClosed(path.closed);

        return sample(view);
    }

    private static List<float[]> sample(CompPathGeometry.ArrayPath path)
    {
        List<float[]> result = new ArrayList<>();
        int segments = CompPathGeometry.segmentCount(path);

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = CompPathGeometry.segment(path, segment);

            for (int i = 0; i <= 64; i++)
            {
                result.add(CompPathGeometry.cubicAt(control, i / 64F));
            }
        }

        return result;
    }

    private static float maxDeviation(List<float[]> from, List<float[]> to)
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

    /* ------------------------------------------------------------------ loops */

    @Test
    public void insertMovesLoopsWithTheShiftedChannel()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        LoopPath path = quad(true).animate(0F, 24F, 10F, 10F);

        Loop loop = new Loop("L1", 0F, 10F, 24F);

        for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
        {
            path.loop(3, component, loop);
        }

        session.setTarget(path);
        session.setTick(12F);
        assertTrue(session.insertOnSegment(0, 0.5F));

        /* Old point 3 moved to index 4; its loops must travel with the channel. */
        for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
        {
            List<Loop> loops = path.loopsOf(4, component);

            assertEquals(1, loops.size(), "component " + component + " lost its loop");
            assertEquals(loop, loops.get(0));
        }
    }

    @Test
    public void insertCopiesLoopsOntoTheNewPointChannels()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        LoopPath path = quad(true).animate(0F, 24F, 10F, 10F);

        Loop loop = new Loop("L1", 0F, 10F, 24F);

        for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
        {
            path.loop(0, component, loop);
        }

        session.setTarget(path);
        session.setTick(12F);
        assertTrue(session.insertOnSegment(0, 0.5F));

        for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
        {
            List<Loop> loops = path.loopsOf(1, component);

            assertEquals(1, loops.size(), "new point channel " + component + " must join the loop block");
            assertEquals(loop, loops.get(0));
        }
    }

    @Test
    public void deleteDropsTheRemovedPointsLoopsAndKeepsTheRest()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        LoopPath path = quad(true).animate(0F, 24F, 4F, 4F);

        Loop removed = new Loop("REMOVED", 0F, 8F, 20F);
        Loop kept = new Loop("KEPT", 0F, 12F, 24F);

        path.loop(1, CompPenSession.COMPONENT_X, removed);
        path.loop(2, CompPenSession.COMPONENT_X, kept);

        session.setTarget(path);
        session.setTick(12F);
        session.selectVertex(1, false);
        assertTrue(session.deleteSelection());

        /* Old point 2 became index 1 and keeps its loop; the removed point's loop is gone. */
        List<Loop> moved = path.loopsOf(1, CompPenSession.COMPONENT_X);

        assertEquals(1, moved.size());
        assertEquals(kept, moved.get(0));
        assertTrue(path.loopsOf(2, CompPenSession.COMPONENT_X).isEmpty());
    }

    /* ------------------------------------------------------------------ Set First Vertex */

    @Test
    public void setFirstVertexRotatesAnUnanimatedClosedPath()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        LoopPath path = quad(true);

        path.animated = false;
        session.setTarget(path);

        List<float[]> before = modelSamples(path);

        assertTrue(session.setFirstVertex(2));
        assertEquals(1, recorder.actions, "one undo checkpoint");
        assertTrue(recorder.status.contains("第一顶点"));

        /* New order: old 2, 3, 0, 1. */
        assertEquals(120F, path.points.get(0)[0], 0.001F);
        assertEquals(120F, path.points.get(0)[1], 0.001F);
        assertEquals(0F, path.points.get(1)[0], 0.001F);
        assertEquals(0F, path.points.get(2)[0], 0.001F);
        assertEquals(120F, path.points.get(3)[0], 0.001F);

        /* The closed shape is unchanged, only its start vertex moved. */
        assertEquals(0F, maxDeviation(before, modelSamples(path)), 0.01F);
        assertEquals(0F, maxDeviation(modelSamples(path), before), 0.01F);
        assertTrue(session.selection().contains(0));
    }

    @Test
    public void setFirstVertexRotatesAnimatedChannelsAndLoops()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        LoopPath path = quad(true).animate(0F, 24F, 12F, -6F);
        float[] ticks = {0F, 12F, 24F};

        Loop loop = new Loop("L", 0F, 10F, 24F);

        for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
        {
            path.loop(2, component, loop);
        }

        List<List<float[]>> before = new ArrayList<>();
        float[][][] beforeValues = new float[ticks.length][path.count() * CompPenSession.COMPONENT_COUNT][];

        for (int i = 0; i < ticks.length; i++)
        {
            before.add(evaluatedSamples(path, ticks[i]));

            for (int point = 0; point < path.count(); point++)
            {
                for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    beforeValues[i][point * CompPenSession.COMPONENT_COUNT + component] =
                        new float[] {path.valueAt(point, component, ticks[i])};
                }
            }
        }

        session.setTarget(path);
        assertTrue(session.setFirstVertex(2));
        assertEquals(1, recorder.actions);

        /* Channel i now holds what channel (i+2)%4 held: every keyframe keeps its geometry. */
        for (int i = 0; i < ticks.length; i++)
        {
            for (int point = 0; point < path.count(); point++)
            {
                int oldPoint = (point + 2) % path.count();

                for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    assertEquals(beforeValues[i][oldPoint * CompPenSession.COMPONENT_COUNT + component][0],
                        path.valueAt(point, component, ticks[i]), 0.0001F,
                        "tick " + ticks[i] + " new point " + point + " component " + component);
                }
            }

            assertEquals(0F, maxDeviation(before.get(i), evaluatedSamples(path, ticks[i])), 0.02F,
                "set first vertex changed the animated shape at tick " + ticks[i]);
        }

        for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
        {
            List<Loop> loops = path.loopsOf(0, component);

            assertEquals(1, loops.size(), "the loop must rotate with the channel");
            assertEquals(loop, loops.get(0));
        }
    }

    @Test
    public void setFirstVertexRefusesWhenTheTargetCannotReorder()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        LoopPath path = quad(true).animate(0F, 24F, 5F, 5F);

        path.canRotate = false;

        float beforeValue = path.valueAt(0, CompPenSession.COMPONENT_X, 0F);
        session.setTarget(path);

        assertFalse(session.setFirstVertex(2));
        assertEquals(0, recorder.actions, "a refused command must not push an undo checkpoint");
        assertTrue(recorder.status.contains("不支持"), recorder.status);
        assertEquals(beforeValue, path.valueAt(0, CompPenSession.COMPONENT_X, 0F), 0F,
            "channels must not be rotated when the model cannot follow");
    }

    @Test
    public void setFirstVertexRefusesOpenShortAndZeroIndexPaths()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);

        LoopPath open = quad(false);

        open.animated = false;
        session.setTarget(open);
        assertFalse(session.setFirstVertex(1));
        assertTrue(recorder.status.contains("闭合"), recorder.status);

        LoopPath shortPath = quad(true);

        shortPath.animated = false;
        shortPath.points.remove(3);
        shortPath.points.remove(2);
        session.setTarget(shortPath);
        assertFalse(session.setFirstVertex(1));

        LoopPath normal = quad(true);

        normal.animated = false;
        session.setTarget(normal);
        assertFalse(session.setFirstVertex(0), "index 0 is already the first vertex");
        assertEquals(0, recorder.actions);
        assertNotNull(normal.points);
    }
}
