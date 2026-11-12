package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AE's "Preserve Constant Vertex and Feather Point Count" preference as wired into the pen.
 *
 * <p>The model has one global vertex count (six scalar channels per point), so a per-keyframe count
 * cannot be represented. With the preference ON (default) a structural edit remaps every keyframe;
 * with it OFF the session refuses structural edits on <b>animated</b> paths instead of silently
 * desynchronising the channel indices. Unanimated paths are unaffected.</p>
 */
public class KeepVertexCountTest
{
    /** Minimal animated path: linear value interpolation, no loops/rotation (covered elsewhere). */
    private static final class AnimatedPath implements CompPenSession.Target, CompPenSession.Animation
    {
        final List<float[]> points = new ArrayList<>();
        final List<Map<Integer, List<float[]>>> keys = new ArrayList<>();
        boolean closed;
        boolean animated = true;
        boolean roto;

        AnimatedPath add(float x, float y)
        {
            return this.add(x, y, 0F, 0F, 0F, 0F);
        }

        AnimatedPath add(float x, float y, float inX, float inY, float outX, float outY)
        {
            this.points.add(new float[] {x, y, inX, inY, outX, outY});
            this.keys.add(new HashMap<>());

            return this;
        }

        AnimatedPath key(int point, int component, float tick, float value)
        {
            this.ensure(point + 1);
            List<float[]> list = this.keys.get(point).computeIfAbsent(component, (unused) -> new ArrayList<>());

            list.add(new float[] {tick, value});
            list.sort((a, b) -> Float.compare(a[0], b[0]));

            return this;
        }

        AnimatedPath animate(float from, float to)
        {
            for (int i = 0; i < this.points.size(); i++)
            {
                for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
                {
                    float base = this.points.get(i)[component];

                    this.key(i, component, from, base);
                    this.key(i, component, to, base + (component == CompPenSession.COMPONENT_X ? 10F * (i + 1) : 0F));
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

        /* --- Target --- */

        @Override
        public int count() { return this.points.size(); }

        @Override
        public float x(int index) { return this.valueAt(index, CompPenSession.COMPONENT_X); }

        @Override
        public float y(int index) { return this.valueAt(index, CompPenSession.COMPONENT_Y); }

        @Override
        public float inX(int index) { return this.valueAt(index, CompPenSession.COMPONENT_IN_X); }

        @Override
        public float inY(int index) { return this.valueAt(index, CompPenSession.COMPONENT_IN_Y); }

        @Override
        public float outX(int index) { return this.valueAt(index, CompPenSession.COMPONENT_OUT_X); }

        @Override
        public float outY(int index) { return this.valueAt(index, CompPenSession.COMPONENT_OUT_Y); }

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
        public String label() { return "keep count path"; }

        @Override
        public CompPenSession.Animation animation() { return this; }

        @Override
        public boolean rotoBezier() { return this.roto; }

        @Override
        public void setRotoBezier(boolean rotoBezier) { this.roto = rotoBezier; }

        @Override
        public boolean rotatePoints(int shift)
        {
            java.util.Collections.rotate(this.points, shift);

            return true;
        }

        @Override
        public boolean canRotatePoints() { return true; }

        /* --- Animation --- */

        @Override
        public boolean hasKey(int point, int component)
        {
            List<float[]> list = this.keys.size() > point ? this.keys.get(point).get(component) : null;

            return list != null && !list.isEmpty();
        }

        @Override
        public float base(int point, int component) { return this.points.get(point)[component]; }

        /** Target reads (x/y/handles) use tick 0; the remap uses the 3-arg override below. */
        public float valueAt(int point, int component)
        {
            return this.valueAt(point, component, 0F);
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
            this.keys.set(to, new HashMap<>(this.keys.get(from)));
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

            List<float[]> list = this.keys.get(point).computeIfAbsent(component, (unused) -> new ArrayList<>());

            for (float[] key : list)
            {
                if (Math.abs(key[0] - tick) < 0.0001F)
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
        }

        @Override
        public void rotateChannels(int count, int shift)
        {
        }
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

    private static AnimatedPath quad()
    {
        AnimatedPath path = new AnimatedPath();

        path.add(0F, 0F);
        path.add(100F, 0F);
        path.add(100F, 100F);
        path.add(0F, 100F);
        path.closed = true;

        return path;
    }

    @Test
    public void defaultModeRemapsAnimatedCountChanges()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        AnimatedPath path = quad().animate(0F, 24F);

        session.setTarget(path);
        session.setTick(12F);

        assertTrue(session.keepVertexCount(), "default is AE's 'preserve constant vertex count'");
        assertTrue(session.insertOnSegment(0, 0.5F));
        assertEquals(5, path.count());
        assertEquals(1, recorder.actions);
    }

    @Test
    public void withoutPreserveAnimatedStructuralEditsAreRefused()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        AnimatedPath path = quad().animate(0F, 24F);

        session.setTarget(path);
        session.setTick(12F);
        session.setKeepVertexCount(false);

        assertFalse(session.insertOnSegment(0, 0.5F));
        assertEquals(4, path.count());
        assertEquals(0, recorder.actions);
        assertTrue(recorder.status.contains("保持顶点数"), recorder.status);

        assertFalse(session.appendPoint(200F, 200F));
        assertEquals(4, path.count());

        session.selectVertex(1, false);
        assertFalse(session.deleteSelection());
        assertEquals(4, path.count());
        assertEquals(0, recorder.actions, "a refused edit must not push undo checkpoints");

        /* Set First Vertex changes no vertex count and stays available. */
        assertTrue(session.setFirstVertex(2));
        assertEquals(1, recorder.actions);
    }

    @Test
    public void withoutPreserveUnanimatedPathsBehaveNormally()
    {
        Recorder recorder = new Recorder();
        CompPenSession session = new CompPenSession(recorder);
        AnimatedPath path = quad();

        path.animated = false;
        session.setTarget(path);
        session.setKeepVertexCount(false);

        assertTrue(session.insertOnSegment(0, 0.5F));
        assertEquals(5, path.count());

        session.selectVertex(0, false);
        assertTrue(session.deleteSelection());
        assertEquals(4, path.count());
        assertEquals(2, recorder.actions);
    }
}
