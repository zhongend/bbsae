package dev.bbsae.comp;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The pen tool's state machine: drawing, closing, resuming, inserting, converting, selecting and
 * dragging vertices/handles.
 *
 * <p>Deliberately free of UI, GL and Minecraft types. It talks to a {@link Target} (an adapter over
 * a mask or shape path) and to a {@link Listener} (undo checkpoint, save, status line), so the
 * whole interaction can be exercised by plain JUnit with an array-backed target. The UI side
 * ({@code client.ui.UIPenTool}) owns projection and hit-test coordinates; this class owns what the
 * gesture <em>means</em>.</p>
 *
 * <p>Coordinate convention: all {@code localX/localY} values are layer-local pixels (the same space
 * {@link CompMask} stores), while {@code screenX/screenY} are only used for gesture thresholds and
 * for pixels-per-second style decisions. Screen values never enter the stored path.</p>
 */
public final class CompPenSession
{
    /** A path the pen can edit. Reads are the live base values; writes are animation aware. */
    public interface Target extends CompPathGeometry.PathView
    {
        void setPoint(int index, float x, float y);

        /** @param out true for the outgoing handle, false for the incoming one. */
        void setHandle(int index, boolean out, float dx, float dy);

        void insert(int index, float x, float y, float inX, float inY, float outX, float outY);

        void remove(int index);

        void setClosed(boolean closed);

        /**
         * True when any point channel of this path already has keyframes. Such a path needs a
         * vertex-count remap before a point can be added or removed: every key of the six
         * {@code p/<i>/*} channels of every point is addressed by index, so shifting the model list
         * without shifting the channels would animate the wrong vertices.
         */
        boolean animated();

        /**
         * Channel access for vertex-count remapping; {@code null} when this target cannot expose
         * its channels (tests, or a path whose animation is not backed by scalar channels). A
         * target that reports {@link #animated()} but returns {@code null} here is refused rather
         * than silently corrupted.
         */
        default Animation animation()
        {
            return null;
        }

        /** Short user-facing name, e.g. {@code "Mask 1"} or {@code "Path 2"}. */
        String label();
    }

    /* Point components, in the same order the mask/shape point classes store them. */
    public static final int COMPONENT_X = 0;
    public static final int COMPONENT_Y = 1;
    public static final int COMPONENT_IN_X = 2;
    public static final int COMPONENT_IN_Y = 3;
    public static final int COMPONENT_OUT_X = 4;
    public static final int COMPONENT_OUT_Y = 5;
    public static final int COMPONENT_COUNT = 6;

    /**
     * The six scalar channels of every point, as the vertex-count remap needs them.
     *
     * <p>Deliberately tiny and animation-system agnostic: the session asks "what are the key ticks
     * of this channel / what is its value at tick T", never how the project stores a keyframe. The
     * UI layer implements it over {@code CompLayer.scalarChannel(...)}; the tests implement it over
     * a linear-interpolated map.</p>
     */
    public interface Animation
    {
        boolean hasKey(int point, int component);

        /** Value of the component when the channel has no keys (the authored base). */
        float base(int point, int component);

        /** Evaluated channel value at {@code tick}, falling back to {@link #base}. */
        float valueAt(int point, int component, float tick);

        /** Ticks of the component's keyframes, ascending; empty when it has none. */
        float[] keyTicks(int point, int component);

        /** Moves all six channels of one point to another index, preserving keys exactly. */
        void moveChannels(int from, int to);

        /** Removes every key of the point's six channels. */
        void clearChannels(int point);

        /** Inserts or overwrites one key, inheriting the neighbouring key's curve style when new. */
        void setKey(int point, int component, float tick, float value);
    }

    /** Side effects the UI has to perform around a mutation. */
    public interface Listener
    {
        /** Push an undo checkpoint <b>before</b> the mutation. */
        void beginAction(String name);

        /** A mutation finished: mark the document dirty / save. */
        void changed();

        /** User-facing hint (status bar). */
        void status(String message);

        /** The closed flag changed: render caches that fingerprint paths have to be dropped. */
        void closedChanged();
    }

    public enum Phase
    {
        IDLE,
        DRAWING
    }

    private enum Drag
    {
        NONE,
        NEW_TANGENT,
        VERTEX,
        IN_HANDLE,
        OUT_HANDLE
    }

    public static final int HIT_NONE = 0;
    public static final int HIT_VERTEX = 1;
    public static final int HIT_IN = 2;
    public static final int HIT_OUT = 3;
    public static final int HIT_SEGMENT = 4;

    /** {@link #press} result: nothing was consumed. */
    public static final int PRESS_IGNORED = 0;
    /** {@link #press} result: the gesture was handled. */
    public static final int PRESS_CONSUMED = 1;
    /** {@link #press} result: the caller should create a new path and start drawing on it. */
    public static final int PRESS_REQUEST_NEW_PATH = 2;

    /** Screen-space drag threshold before a press counts as a drag (AE feels the same). */
    public static final float DRAG_THRESHOLD_PX = 2F;
    /** Screen-space radius that makes a click on the first vertex close the path. */
    public static final float CLOSE_RADIUS_PX = 8F;

    /** One hit-test result. Screen values are only valid when the vertex projected on screen. */
    public static final class Hit
    {
        public int kind = HIT_NONE;
        public int point = -1;
        public int segment = -1;
        public float t;
        public float localX;
        public float localY;
        public float screenX;
        public float screenY;
        public float screenDistance = Float.MAX_VALUE;

        public boolean valid()
        {
            return this.kind != HIT_NONE;
        }

        public static Hit none()
        {
            return new Hit();
        }
    }

    /** Local point → screen pixel. Returns {@code null} for points behind the camera. */
    public interface Projector
    {
        float[] project(float localX, float localY);
    }

    private final Listener listener;

    private Target target;
    private Phase phase = Phase.IDLE;
    private Drag drag = Drag.NONE;
    private int dragPoint = -1;
    private boolean dragMoved;
    private float dragStartLocalX;
    private float dragStartLocalY;
    private float dragStartScreenX;
    private float dragStartScreenY;
    private int[] dragIndices = new int[0];
    private float[] dragSnapshot = new float[0];

    private final Set<Integer> selection = new LinkedHashSet<>();
    private int anchorPoint = -1;
    private boolean hasMouse;
    private float mouseLocalX;
    private float mouseLocalY;
    /**
     * Current playhead tick (composition frames). Only used by the animated vertex-count remap: the
     * key at the playhead takes the user's actual click parameter, every other key is split at 0.5.
     */
    private float tick;

    public CompPenSession(Listener listener)
    {
        this.listener = listener;
    }

    /** Playhead tick; the UI sets this before every press/key. */
    public void setTick(float tick)
    {
        this.tick = CompPathGeometry.isUsable(tick) ? tick : 0F;
    }

    public float tick()
    {
        return this.tick;
    }

    /* ------------------------------------------------------------------ state queries */

    public Phase phase()
    {
        return this.phase;
    }

    public boolean isDrawing()
    {
        return this.phase == Phase.DRAWING;
    }

    public boolean isDragging()
    {
        return this.drag != Drag.NONE;
    }

    public boolean dragMoved()
    {
        return this.dragMoved;
    }

    public boolean isCreatingTangent()
    {
        return this.drag == Drag.NEW_TANGENT;
    }

    public Target target()
    {
        return this.target;
    }

    public boolean hasSelection()
    {
        return !this.selection.isEmpty();
    }

    /** Selects one vertex; {@code additive} toggles it into the current selection (Shift click). */
    public void selectVertex(int index, boolean additive)
    {
        if (this.target == null || index < 0 || index >= this.target.count())
        {
            return;
        }

        if (additive)
        {
            if (!this.selection.remove(index))
            {
                this.selection.add(index);
            }
        }
        else
        {
            this.selection.clear();
            this.selection.add(index);
        }
    }

    /** Replaces the selection (marquee). */
    public void setSelection(java.util.Collection<Integer> indices)
    {
        this.selection.clear();

        if (indices != null && this.target != null)
        {
            for (Integer index : indices)
            {
                if (index != null && index >= 0 && index < this.target.count())
                {
                    this.selection.add(index);
                }
            }
        }
    }

    public int selectionSize()
    {
        return this.selection.size();
    }

    /** Live selection set; read-only for callers. */
    public Set<Integer> selection()
    {
        return this.selection;
    }

    /** Lowest selected index, or -1. Used by the context menu ("convert this point"). */
    public int primarySelection()
    {
        int result = -1;

        for (Integer index : this.selection)
        {
            if (index != null && (result < 0 || index < result))
            {
                result = index;
            }
        }

        return result;
    }

    /** Index whose outgoing tangent forms the rubber band to the cursor. */
    public int anchorPoint()
    {
        return this.anchorPoint;
    }

    public boolean hasMouse()
    {
        return this.hasMouse;
    }

    public float mouseLocalX()
    {
        return this.mouseLocalX;
    }

    public float mouseLocalY()
    {
        return this.mouseLocalY;
    }

    public boolean closed()
    {
        return this.target != null && this.target.closed();
    }

    public int pointCount()
    {
        return this.target == null ? 0 : this.target.count();
    }

    /* ------------------------------------------------------------------ target / lifecycle */

    /** Points the session at an existing path without starting to draw on it. */
    public void setTarget(Target target)
    {
        if (this.target != target)
        {
            this.selection.clear();
            this.drag = Drag.NONE;
            this.dragPoint = -1;
        }

        this.target = target;
        this.anchorPoint = target == null ? -1 : target.count() - 1;
    }

    /** Starts drawing on {@code target}; the next empty press appends a vertex. */
    public void begin(Target target)
    {
        this.setTarget(target);
        this.phase = Phase.DRAWING;
        this.selection.clear();

        if (target != null)
        {
            this.anchorPoint = target.count() - 1;
        }
    }

    /** Ends the current path (keeping it open) and returns to idle. */
    public boolean endPath()
    {
        if (this.phase != Phase.DRAWING)
        {
            return false;
        }

        this.phase = Phase.IDLE;
        this.drag = Drag.NONE;
        this.dragPoint = -1;
        this.anchorPoint = this.target == null ? -1 : this.target.count() - 1;
        this.selection.clear();
        this.listener.status(this.target == null
            ? "结束钢笔绘制"
            : (this.target.closed() ? "路径已闭合" : "路径结束（开放路径不参与蒙版遮罩；右键可闭合）"));

        return true;
    }

    /** Aborts the current gesture (tool/layer/composition change). */
    public void cancel()
    {
        this.phase = Phase.IDLE;
        this.drag = Drag.NONE;
        this.dragPoint = -1;
        this.dragMoved = false;
        this.selection.clear();
        this.anchorPoint = this.target == null ? -1 : this.target.count() - 1;
    }

    /** Drops the target entirely (the layer's masks/shapes were replaced). */
    public void forget()
    {
        this.cancel();
        this.target = null;
        this.anchorPoint = -1;
        this.hasMouse = false;
    }

    /* ------------------------------------------------------------------ hit test */

    /**
     * Screen-space hit test over one path.
     *
     * <p>Vertices win ties against handles (After Effects keeps the vertex reachable even when a
     * long tangent passes it), and both win against segments. The nearest-point search runs in
     * local space but compares <b>projected</b> distances, so a rotated/non-uniformly scaled layer
     * selects what the user sees under the cursor rather than what is nearest in local units.</p>
     *
     * @param handlePoints only these vertices expose hit-testable handles ({@code null} = none),
     *                     which keeps invisible tangents from stealing clicks.
     */
    public static Hit hitTest(CompPathGeometry.PathView path, Projector projector, float mouseX, float mouseY,
                              float vertexRadius, float handleRadius, float segmentRadius, Set<Integer> handlePoints)
    {
        Hit hit = new Hit();
        int count = path == null ? 0 : path.count();

        if (count <= 0 || projector == null)
        {
            return hit;
        }

        float bestVertex = Float.MAX_VALUE;
        float bestHandle = Float.MAX_VALUE;
        int vertexIndex = -1;
        int handleIndex = -1;
        boolean handleOut = false;

        for (int i = 0; i < count; i++)
        {
            float[] projected = projector.project(path.x(i), path.y(i));

            if (projected != null)
            {
                float distance = CompPathGeometry.distance(mouseX, mouseY, projected[0], projected[1]);

                if (distance <= vertexRadius && distance < bestVertex)
                {
                    bestVertex = distance;
                    vertexIndex = i;
                }
            }

            if (handlePoints == null || !handlePoints.contains(i))
            {
                continue;
            }

            for (int side = 0; side < 2; side++)
            {
                boolean out = side == 1;
                float hx = path.x(i) + (out ? path.outX(i) : path.inX(i));
                float hy = path.y(i) + (out ? path.outY(i) : path.inY(i));
                float[] handleProjected = projector.project(hx, hy);

                if (handleProjected == null)
                {
                    continue;
                }

                float distance = CompPathGeometry.distance(mouseX, mouseY, handleProjected[0], handleProjected[1]);

                if (distance <= handleRadius && distance < bestHandle)
                {
                    bestHandle = distance;
                    handleIndex = i;
                    handleOut = out;
                }
            }
        }

        /* Vertex priority on ties: the handle loops run with a real radius, this comparison uses
         * "less than" so an exact tie lands on the vertex. */
        if (vertexIndex >= 0)
        {
            hit.kind = HIT_VERTEX;
            hit.point = vertexIndex;
            hit.screenDistance = bestVertex;
            hit.localX = path.x(vertexIndex);
            hit.localY = path.y(vertexIndex);
            float[] projected = projector.project(hit.localX, hit.localY);

            if (projected != null)
            {
                hit.screenX = projected[0];
                hit.screenY = projected[1];
            }

            return hit;
        }

        if (handleIndex >= 0)
        {
            hit.kind = handleOut ? HIT_OUT : HIT_IN;
            hit.point = handleIndex;
            hit.screenDistance = bestHandle;
            float hx = path.x(handleIndex) + (handleOut ? path.outX(handleIndex) : path.inX(handleIndex));
            float hy = path.y(handleIndex) + (handleOut ? path.outY(handleIndex) : path.inY(handleIndex));
            float[] projected = projector.project(hx, hy);

            if (projected != null)
            {
                hit.screenX = projected[0];
                hit.screenY = projected[1];
            }

            hit.localX = hx;
            hit.localY = hy;

            return hit;
        }

        /* Segments. Sample in local space, measure on screen, then narrow around the best sample. */
        int segments = CompPathGeometry.segmentCount(path);

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = CompPathGeometry.segment(path, segment);

            if (control == null)
            {
                continue;
            }

            float localBestT = 0F;
            float localBestDistance = Float.MAX_VALUE;

            for (int i = 0; i <= 24; i++)
            {
                float t = i / 24F;
                float[] point = CompPathGeometry.cubicAt(control, t);
                float[] projected = projector.project(point[0], point[1]);
                float distance = projected == null
                    ? Float.MAX_VALUE
                    : CompPathGeometry.distance(mouseX, mouseY, projected[0], projected[1]);

                if (distance < localBestDistance)
                {
                    localBestDistance = distance;
                    localBestT = t;
                }
            }

            float step = 0.5F / 24F;

            for (int i = 0; i < 16; i++)
            {
                step *= 0.5F;

                for (int side = 0; side < 2; side++)
                {
                    float t = CompPathGeometry.clamp01(localBestT + (side == 0 ? -step : step));
                    float[] point = CompPathGeometry.cubicAt(control, t);
                    float[] projected = projector.project(point[0], point[1]);
                    float distance = projected == null
                        ? Float.MAX_VALUE
                        : CompPathGeometry.distance(mouseX, mouseY, projected[0], projected[1]);

                    if (distance < localBestDistance)
                    {
                        localBestDistance = distance;
                        localBestT = t;
                    }
                }
            }

            if (localBestDistance <= segmentRadius && localBestDistance < hit.screenDistance)
            {
                hit.kind = HIT_SEGMENT;
                hit.segment = segment;
                hit.t = localBestT;
                hit.screenDistance = localBestDistance;

                float[] point = CompPathGeometry.pointOnPath(path, segment, localBestT);

                hit.localX = point[0];
                hit.localY = point[1];
                float[] projected = projector.project(point[0], point[1]);

                if (projected != null)
                {
                    hit.screenX = projected[0];
                    hit.screenY = projected[1];
                }
            }
        }

        if (hit.kind == HIT_NONE)
        {
            hit.screenDistance = Float.MAX_VALUE;
        }

        return hit;
    }

    /* ------------------------------------------------------------------ press / drag / release */

    /**
     * Mouse press. See the {@code PRESS_*} constants for the interpretation of the return value.
     *
     * <p>{@code hit} must have been computed against {@link #target()}. The caller may pass
     * {@link Hit#none()} when nothing was hit.</p>
     */
    public int press(float localX, float localY, float screenX, float screenY,
                     boolean shift, boolean alt, float closeTolerancePx, Hit hit)
    {
        this.hasMouse = true;
        this.mouseLocalX = localX;
        this.mouseLocalY = localY;

        if (hit == null)
        {
            hit = Hit.none();
        }

        if (this.target == null)
        {
            return PRESS_REQUEST_NEW_PATH;
        }

        if (this.phase == Phase.DRAWING)
        {
            return this.pressDrawing(localX, localY, screenX, screenY, shift, alt, closeTolerancePx, hit);
        }

        return this.pressIdle(localX, localY, screenX, screenY, shift, alt, closeTolerancePx, hit);
    }

    private int pressDrawing(float localX, float localY, float screenX, float screenY,
                             boolean shift, boolean alt, float closeTolerancePx, Hit hit)
    {
        int count = this.target.count();

        /* Click the first vertex to close (AE P-05). The smaller the tolerance the more often a
         * normal "put a point near the start" gesture accidentally closes the path, so the
         * caller's screen radius is kept exactly. */
        if (hit.kind == HIT_VERTEX && hit.point == 0 && count >= 2
            && hit.screenDistance <= closeTolerancePx)
        {
            this.closePath();

            return PRESS_CONSUMED;
        }

        if (hit.kind == HIT_SEGMENT)
        {
            this.insertOnSegment(hit.segment, hit.t);

            return PRESS_CONSUMED;
        }

        if (hit.kind == HIT_VERTEX && alt)
        {
            this.convertVertex(hit.point, null);

            return PRESS_CONSUMED;
        }

        if (hit.kind == HIT_VERTEX)
        {
            this.selectPoint(hit.point, shift);
            this.beginVertexDrag(screenX, screenY, localX, localY);

            return PRESS_CONSUMED;
        }

        /* Empty space while drawing: append the next vertex. Shift constrains it to 45 degrees
         * relative to the previous vertex (the caller has already applied the constraint in
         * screen space when it has a projector, the local fallback is still applied here). */
        float x = localX;
        float y = localY;

        if (shift && this.anchorPoint >= 0 && this.anchorPoint < count)
        {
            float[] constrained = CompPathGeometry.constrain45(
                this.target.x(this.anchorPoint), this.target.y(this.anchorPoint), x, y);

            x = constrained[0];
            y = constrained[1];
        }

        if (this.target.animated())
        {
            /* A path with keyframed point channels gets a de Casteljau vertex inserted into every
             * keyframe, so the channel indices stay aligned and the curve keeps its shape. */
            Animation animation = this.target.animation();

            if (animation == null || !this.remapAppend(animation, x, y))
            {
                if (animation == null)
                {
                    this.listener.status("路径动画通道不可用：已取消落点");
                }

                return PRESS_CONSUMED;
            }
        }
        else
        {
            this.listener.beginAction("钢笔：落点");
            this.target.insert(count, x, y, 0F, 0F, 0F, 0F);
        }

        this.selection.clear();
        this.selection.add(count);
        this.drag = Drag.NEW_TANGENT;
        this.dragPoint = count;
        this.dragMoved = false;
        this.dragStartLocalX = x;
        this.dragStartLocalY = y;
        this.dragStartScreenX = screenX;
        this.dragStartScreenY = screenY;
        this.anchorPoint = count;
        this.listener.changed();

        return PRESS_CONSUMED;
    }

    private int pressIdle(float localX, float localY, float screenX, float screenY,
                          boolean shift, boolean alt, float closeTolerancePx, Hit hit)
    {
        int count = this.target.count();

        if (hit.kind == HIT_SEGMENT)
        {
            this.insertOnSegment(hit.segment, hit.t);

            return PRESS_CONSUMED;
        }

        if (hit.kind == HIT_VERTEX)
        {
            if (alt)
            {
                this.convertVertex(hit.point, null);
                this.selectPoint(hit.point, false);

                return PRESS_CONSUMED;
            }

            /* Clicking the first vertex of an <em>open</em> path completes it. This is the gesture
             * After Effects users try when they come back to a path: with the pen tool there is no
             * "close" modifier, and leaving the path open forever would be the only alternative. */
            if (hit.point == 0 && count >= 3 && !this.target.closed()
                && hit.screenDistance <= closeTolerancePx)
            {
                this.closePath();

                return PRESS_CONSUMED;
            }

            /* Clicking the last vertex of an open path resumes drawing from it (AE P-09). */
            if (!this.target.closed() && hit.point == count - 1 && count >= 1)
            {
                this.selectPoint(hit.point, shift);
                this.phase = Phase.DRAWING;
                this.anchorPoint = hit.point;
                this.listener.status("继续绘制：从末顶点接着画");

                return PRESS_CONSUMED;
            }

            this.selectPoint(hit.point, shift);
            this.beginVertexDrag(screenX, screenY, localX, localY);

            return PRESS_CONSUMED;
        }

        if (hit.kind == HIT_IN || hit.kind == HIT_OUT)
        {
            this.selectPoint(hit.point, false);
            this.drag = hit.kind == HIT_IN ? Drag.IN_HANDLE : Drag.OUT_HANDLE;
            this.dragPoint = hit.point;
            this.dragMoved = false;
            this.dragStartLocalX = localX;
            this.dragStartLocalY = localY;
            this.dragStartScreenX = screenX;
            this.dragStartScreenY = screenY;

            return PRESS_CONSUMED;
        }

        return PRESS_REQUEST_NEW_PATH;
    }

    private void selectPoint(int index, boolean additive)
    {
        if (additive)
        {
            if (!this.selection.remove(index))
            {
                this.selection.add(index);
            }
        }
        else
        {
            this.selection.clear();
            this.selection.add(index);
        }
    }

    private void beginVertexDrag(float screenX, float screenY, float localX, float localY)
    {
        this.drag = Drag.VERTEX;
        this.dragPoint = this.primarySelection();
        this.dragMoved = false;
        this.dragStartLocalX = localX;
        this.dragStartLocalY = localY;
        this.dragStartScreenX = screenX;
        this.dragStartScreenY = screenY;
        this.dragIndices = new int[this.selection.size()];
        this.dragSnapshot = new float[this.selection.size() * 2];
        int slot = 0;

        for (Integer index : this.selection)
        {
            if (index == null || index < 0 || index >= this.target.count())
            {
                continue;
            }

            this.dragIndices[slot] = index;
            this.dragSnapshot[slot * 2] = this.target.x(index);
            this.dragSnapshot[slot * 2 + 1] = this.target.y(index);
            slot += 1;
        }
    }

    /**
     * Mouse drag. The caller resolves the current local position (including snapping and any
     * screen-space constraints) and passes it here.
     */
    public boolean drag(float localX, float localY, float screenX, float screenY, boolean shift, boolean alt)
    {
        this.hasMouse = true;
        this.mouseLocalX = localX;
        this.mouseLocalY = localY;

        if (this.drag == Drag.NONE || this.target == null)
        {
            return false;
        }

        float screenDistance = CompPathGeometry.distance(
            screenX, screenY, this.dragStartScreenX, this.dragStartScreenY);

        if (screenDistance > DRAG_THRESHOLD_PX)
        {
            this.dragMoved = true;
        }

        int count = this.target.count();

        if (this.drag == Drag.NEW_TANGENT)
        {
            if (this.dragPoint < 0 || this.dragPoint >= count || !this.dragMoved)
            {
                return true;
            }

            float dx = localX - this.dragStartLocalX;
            float dy = localY - this.dragStartLocalY;

            if (alt)
            {
                /* Alt breaks the symmetry: only the outgoing handle follows, the point stays a
                 * corner with one tangent (AE's "break handle" gesture). */
                this.target.setHandle(this.dragPoint, true, dx, dy);
            }
            else
            {
                this.target.setHandle(this.dragPoint, true, dx, dy);
                this.mirrorToIncoming(this.dragPoint, dx, dy);
            }

            this.listener.changed();

            return true;
        }

        if (this.drag == Drag.VERTEX)
        {
            float dx = localX - this.dragStartLocalX;
            float dy = localY - this.dragStartLocalY;

            if (shift)
            {
                float[] constrained = CompPathGeometry.constrain45(0F, 0F, dx, dy);
                dx = constrained[0];
                dy = constrained[1];
            }

            for (int slot = 0; slot < this.dragIndices.length; slot++)
            {
                int index = this.dragIndices[slot];

                if (index < 0 || index >= count)
                {
                    continue;
                }

                this.target.setPoint(index, this.dragSnapshot[slot * 2] + dx, this.dragSnapshot[slot * 2 + 1] + dy);
            }

            this.listener.changed();

            return true;
        }

        int index = this.dragPoint;

        if (index < 0 || index >= count)
        {
            return true;
        }

        float px = this.target.x(index);
        float py = this.target.y(index);
        float dx = localX - px;
        float dy = localY - py;

        if (this.drag == Drag.IN_HANDLE)
        {
            this.target.setHandle(index, false, dx, dy);

            if (!alt)
            {
                this.mirrorToOutgoing(index, dx, dy);
            }
        }
        else
        {
            this.target.setHandle(index, true, dx, dy);

            if (!alt)
            {
                this.mirrorToIncoming(index, dx, dy);
            }
        }

        this.listener.changed();

        return true;
    }

    /** Mirrors the outgoing handle of {@code index}; the incoming one may not pass the previous vertex. */
    private void mirrorToIncoming(int index, float dx, float dy)
    {
        float limit = this.neighbourDistance(index, false);
        float[] mirror = CompPathGeometry.mirrorClamped(dx, dy, limit);

        this.target.setHandle(index, false, mirror[0], mirror[1]);
    }

    /** Mirrors the incoming handle of {@code index}; the outgoing one may not pass the next vertex. */
    private void mirrorToOutgoing(int index, float dx, float dy)
    {
        float limit = this.neighbourDistance(index, true);
        float[] mirror = CompPathGeometry.mirrorClamped(dx, dy, limit);

        this.target.setHandle(index, true, mirror[0], mirror[1]);
    }

    /**
     * Distance from a vertex to its neighbour on one side, in local units. Handles are clamped to
     * this so mirroring cannot fold a segment back over its neighbour.
     */
    private float neighbourDistance(int index, boolean forward)
    {
        int count = this.target.count();
        int neighbour;

        if (forward)
        {
            neighbour = index + 1 < count ? index + 1 : (this.target.closed() ? 0 : -1);
        }
        else
        {
            neighbour = index > 0 ? index - 1 : (this.target.closed() ? count - 1 : -1);
        }

        if (neighbour < 0 || neighbour >= count || neighbour == index)
        {
            return Float.MAX_VALUE;
        }

        return Math.max(0.0001F, CompPathGeometry.distance(
            this.target.x(index), this.target.y(index),
            this.target.x(neighbour), this.target.y(neighbour)));
    }

    public boolean release()
    {
        if (this.drag == Drag.NONE)
        {
            return false;
        }

        this.drag = Drag.NONE;
        this.dragPoint = -1;
        this.dragMoved = false;
        this.dragIndices = new int[0];
        this.dragSnapshot = new float[0];

        return true;
    }

    /* ------------------------------------------------------------------ discrete commands */

    /** Closes the target path. Returns false when it is already closed or too short. */
    public boolean closePath()
    {
        if (this.target == null || this.target.count() < 2 || this.target.closed())
        {
            return false;
        }

        this.listener.beginAction("钢笔：闭合路径");
        this.target.setClosed(true);
        this.listener.closedChanged();
        this.listener.changed();

        if (this.phase == Phase.DRAWING)
        {
            this.endPath();
        }
        else
        {
            this.listener.status("路径已闭合");
        }

        return true;
    }

    /** Opens (breaks) the target path. */
    public boolean openPath()
    {
        if (this.target == null || !this.target.closed())
        {
            return false;
        }

        this.listener.beginAction("钢笔：断开路径");
        this.target.setClosed(false);
        this.listener.closedChanged();
        this.listener.changed();
        this.listener.status("路径已断开（开放路径不参与蒙版遮罩）");

        return true;
    }

    /** Inserts a vertex on a segment, preserving the shape through a de Casteljau split. */
    public boolean insertOnSegment(int segment, float t)
    {
        if (this.target == null)
        {
            return false;
        }

        if (this.target.animated())
        {
            Animation animation = this.target.animation();

            if (animation == null)
            {
                this.listener.status("路径动画通道不可用：不能在段上加点");

                return false;
            }

            return this.remapInsertOnSegment(animation, segment, t);
        }

        int oldCount = this.target.count();
        float[] split = CompPathGeometry.splitSegment(this.target, segment, t);

        if (split == null)
        {
            return false;
        }

        this.listener.beginAction("钢笔：加点");
        this.target.insert(segment + 1, split[0], split[1], split[2], split[3], split[4], split[5]);
        this.target.setHandle(segment, true, split[6], split[7]);

        int end = segment + 1 >= oldCount ? 0 : segment + 2;

        this.target.setHandle(end, false, split[8], split[9]);
        this.selection.clear();
        this.selection.add(segment + 1);
        this.anchorPoint = this.target.count() - 1;
        this.listener.changed();
        this.listener.status("已在路径段上加点");

        return true;
    }

    /**
     * Appends a vertex at the end of the path without entering draw mode.
     *
     * <p>Used by the inspector's "添加路径点/添加蒙版点" shortcut. Animated paths go through the same
     * remap as a pen click: the point is placed at the playhead, every other keyframe gets the last
     * segment split at {@code t = 0.5}.</p>
     */
    public boolean appendPoint(float localX, float localY)
    {
        if (this.target == null)
        {
            return false;
        }

        int index = this.target.count();

        if (this.target.animated())
        {
            Animation animation = this.target.animation();

            if (animation == null || !this.remapAppend(animation, localX, localY))
            {
                if (animation == null)
                {
                    this.listener.status("路径动画通道不可用：不能加点");
                }

                return false;
            }
        }
        else
        {
            this.listener.beginAction("钢笔：加点");
            this.target.insert(index, localX, localY, 0F, 0F, 0F, 0F);
        }

        this.selection.clear();
        this.selection.add(index);
        this.anchorPoint = this.target.count() - 1;
        this.listener.changed();
        this.listener.status("已在路径末尾加点");

        return true;
    }

    /** Removes one vertex by index (inspector shortcut); animated channels are remapped. */
    public boolean deletePoint(int index)
    {
        if (this.target == null || index < 0 || index >= this.target.count())
        {
            return false;
        }

        this.selection.clear();
        this.selection.add(index);

        return this.deleteSelection();
    }

    /**
     * Corner/smooth conversion. A corner has no tangents; a smooth vertex gets tangents along its
     * neighbours (a sixth of their distance each way) — the same rule effectcraft uses for AE's
     * Convert Vertex tool.
     *
     * @param force {@code null} toggles, {@code true} makes it smooth, {@code false} a corner.
     */
    public boolean convertVertex(int index, Boolean force)
    {
        if (this.target == null || index < 0 || index >= this.target.count())
        {
            return false;
        }

        boolean hasTangents = Math.abs(this.target.inX(index)) + Math.abs(this.target.inY(index))
            + Math.abs(this.target.outX(index)) + Math.abs(this.target.outY(index)) > 0.0001F;
        boolean smooth = force == null ? !hasTangents : force;

        this.listener.beginAction(smooth ? "钢笔：转为平滑点" : "钢笔：转为角点");

        if (smooth)
        {
            float[] tangents = CompPathGeometry.smoothTangents(this.target, index);

            this.target.setHandle(index, false, tangents[0], tangents[1]);
            this.target.setHandle(index, true, tangents[2], tangents[3]);
        }
        else
        {
            this.target.setHandle(index, false, 0F, 0F);
            this.target.setHandle(index, true, 0F, 0F);
        }

        this.selection.clear();
        this.selection.add(index);
        this.listener.changed();
        this.listener.status(smooth ? "已转为平滑点" : "已转为角点");

        return true;
    }

    /** Deletes every selected vertex. Fewer than three vertices forces the path open. */
    public boolean deleteSelection()
    {
        if (this.target == null || this.selection.isEmpty())
        {
            return false;
        }

        int[] indices = new int[this.selection.size()];
        int slot = 0;

        for (Integer index : this.selection)
        {
            if (index != null && index >= 0 && index < this.target.count())
            {
                indices[slot++] = index;
            }
        }

        if (slot == 0)
        {
            return false;
        }

        java.util.Arrays.sort(indices, 0, slot);

        if (this.target.animated())
        {
            Animation animation = this.target.animation();

            if (animation == null)
            {
                this.listener.status("路径动画通道不可用：不能删点");

                return false;
            }

            return this.remapDelete(animation, indices, slot);
        }

        this.listener.beginAction("钢笔：删除点");

        for (int i = slot - 1; i >= 0; i--)
        {
            this.target.remove(indices[i]);
        }

        this.selection.clear();

        if (this.target.count() < 3 && this.target.closed())
        {
            this.target.setClosed(false);
            this.listener.closedChanged();
        }

        this.anchorPoint = this.target.count() - 1;
        this.drag = Drag.NONE;
        this.dragPoint = -1;
        this.listener.changed();
        this.listener.status("已删除 " + slot + " 个顶点");

        return true;
    }

    /* ------------------------------------------------------------------ animated vertex remap */

    /**
     * Vertex-count remap: the pen tool's answer to AE's "Preserve Constant Vertex and Feather Point
     * Count".
     *
     * <p>A path's animation lives in six scalar channels per point, addressed by point index
     * ({@code mask/<id>/p/<i>/x} …). Inserting or removing a point without touching those channels
     * shifts every key onto the wrong vertex, which is why the tool used to refuse the edit. The
     * remap makes the edit the way AE/effectcraft do:</p>
     *
     * <ol>
     *   <li>collect the union of key ticks of the channels that define the affected segment;</li>
     *   <li>at every tick, evaluate the path and insert a vertex by a de Casteljau split — at the
     *       playhead with the user's click parameter, at every other key with {@code t = 0.5}
     *       (any split parameter keeps the curve's shape, and 0.5 can never degenerate);</li>
     *   <li>shift the untouched points' channel objects to their new indices;</li>
     *   <li>write the split values back at those ticks.</li>
     * </ol>
     *
     * <p>The path is therefore pixel-identical at every existing keyframe. Between keyframes the
     * result is an interpolation of the sampled splits — exact whenever the neighbouring channels
     * interpolate linearly, and the same approximation AE's whole-value path keys avoid. Deleting a
     * vertex is simpler: the point's channels are dropped and later indices shift down, so every
     * keyframe keeps the exact values of the surviving vertices.</p>
     */
    private boolean remapInsertOnSegment(Animation animation, int segment, float t)
    {
        int oldCount = this.target.count();
        int start = segment;
        int end = CompPathGeometry.segmentEnd(this.target, segment);

        if (start < 0 || end < 0 || end >= oldCount)
        {
            return false;
        }

        float fraction = Math.max(0.0001F, Math.min(0.9999F, CompPathGeometry.finite(t)));
        float[] ticks = this.remapTicks(animation, start, end);
        float[][] splits = new float[ticks.length][];

        /* Evaluate before any mutation: the channel indices are still the old ones here. */
        for (int i = 0; i < ticks.length; i++)
        {
            splits[i] = Math.abs(ticks[i] - this.tick) < 0.0001F
                ? this.remapSplit(animation, start, end, ticks[i], fraction)
                : this.remapSplit(animation, start, end, ticks[i], 0.5F);
        }

        float[] atPlayhead = null;

        for (int i = 0; i < ticks.length; i++)
        {
            if (Math.abs(ticks[i] - this.tick) < 0.0001F)
            {
                atPlayhead = splits[i];

                break;
            }
        }

        if (atPlayhead == null)
        {
            return false;
        }

        int at = segment + 1;

        this.listener.beginAction("钢笔：加点（重映射动画关键帧）");

        /* Highest index first: every move writes into an index that has just been vacated. */
        for (int i = oldCount - 1; i >= at; i--)
        {
            animation.moveChannels(i, i + 1);
        }

        this.target.insert(at, atPlayhead[0], atPlayhead[1], atPlayhead[2], atPlayhead[3], atPlayhead[4], atPlayhead[5]);

        int endIndex = end == 0 ? 0 : end + 1;

        for (int i = 0; i < ticks.length; i++)
        {
            float[] split = splits[i];

            if (split == null)
            {
                continue;
            }

            animation.setKey(start, COMPONENT_OUT_X, ticks[i], split[6]);
            animation.setKey(start, COMPONENT_OUT_Y, ticks[i], split[7]);
            animation.setKey(endIndex, COMPONENT_IN_X, ticks[i], split[8]);
            animation.setKey(endIndex, COMPONENT_IN_Y, ticks[i], split[9]);

            for (int component = 0; component < COMPONENT_COUNT; component++)
            {
                animation.setKey(at, component, ticks[i], split[component]);
            }
        }

        this.selection.clear();
        this.selection.add(at);
        this.anchorPoint = this.target.count() - 1;
        this.listener.changed();
        this.listener.status("已在路径段上加点，并重映射了 " + ticks.length + " 个动画关键时间");

        return true;
    }

    /**
     * Appends a vertex while drawing on an animated path. The clicked point is placed at the
     * playhead; every other keyframe gets the last segment split at {@code t = 0.5} so the path
     * keeps its shape there (effectcraft's {@code CountOp::Insert} rule).
     */
    private boolean remapAppend(Animation animation, float x, float y)
    {
        int oldCount = this.target.count();

        float[] ticks = this.remapTicks(animation);
        float[][] splits = new float[ticks.length][];
        boolean[] playhead = new boolean[ticks.length];

        for (int i = 0; i < ticks.length; i++)
        {
            if (Math.abs(ticks[i] - this.tick) < 0.0001F)
            {
                splits[i] = new float[] {x, y, 0F, 0F, 0F, 0F};
                playhead[i] = true;
            }
            else if (oldCount >= 2)
            {
                splits[i] = this.remapSplit(animation, oldCount - 2, oldCount - 1, ticks[i], 0.5F);
            }
            else if (oldCount == 1)
            {
                float[] last = this.remapPointAt(animation, oldCount - 1, ticks[i]);

                splits[i] = new float[] {last[0], last[1], 0F, 0F, 0F, 0F};
            }
        }

        this.listener.beginAction("钢笔：落点（重映射动画关键帧）");

        this.target.insert(oldCount, x, y, 0F, 0F, 0F, 0F);

        for (int i = 0; i < ticks.length; i++)
        {
            float[] split = splits[i];

            if (split == null)
            {
                continue;
            }

            if (!playhead[i] && oldCount >= 2)
            {
                animation.setKey(oldCount - 2, COMPONENT_OUT_X, ticks[i], split[6]);
                animation.setKey(oldCount - 2, COMPONENT_OUT_Y, ticks[i], split[7]);
                animation.setKey(oldCount - 1, COMPONENT_IN_X, ticks[i], split[8]);
                animation.setKey(oldCount - 1, COMPONENT_IN_Y, ticks[i], split[9]);
            }

            for (int component = 0; component < COMPONENT_COUNT; component++)
            {
                animation.setKey(oldCount, component, ticks[i], split[component]);
            }
        }

        return true;
    }

    /** Removes vertices from every keyframe by index; surviving vertices keep their exact channels. */
    private boolean remapDelete(Animation animation, int[] indices, int slot)
    {
        this.listener.beginAction("钢笔：删除点（重映射动画关键帧）");

        int current = this.target.count();

        for (int i = slot - 1; i >= 0; i--)
        {
            int index = indices[i];

            if (index < 0 || index >= current)
            {
                continue;
            }

            animation.clearChannels(index);

            for (int move = index + 1; move < current; move++)
            {
                animation.moveChannels(move, move - 1);
            }

            this.target.remove(index);
            current -= 1;
        }

        this.selection.clear();

        if (this.target.count() < 3 && this.target.closed())
        {
            this.target.setClosed(false);
            this.listener.closedChanged();
        }

        this.anchorPoint = this.target.count() - 1;
        this.drag = Drag.NONE;
        this.dragPoint = -1;
        this.listener.changed();
        this.listener.status("已删除 " + slot + " 个顶点，并重映射了动画关键帧");

        return true;
    }

    /** Union of all key ticks of the six channels of the given points, plus the playhead. */
    private float[] remapTicks(Animation animation, int... points)
    {
        java.util.TreeSet<Float> ticks = new java.util.TreeSet<>();

        ticks.add(this.tick);

        if (points == null || points.length == 0)
        {
            for (int point = 0; point < this.target.count(); point++)
            {
                this.collectTicks(animation, point, ticks);
            }
        }
        else
        {
            for (int point : points)
            {
                this.collectTicks(animation, point, ticks);
            }
        }

        float[] result = new float[ticks.size()];
        int slot = 0;

        for (Float value : ticks)
        {
            result[slot++] = value;
        }

        return result;
    }

    private void collectTicks(Animation animation, int point, java.util.TreeSet<Float> ticks)
    {
        for (int component = 0; component < COMPONENT_COUNT; component++)
        {
            float[] channelTicks = animation.keyTicks(point, component);

            if (channelTicks == null)
            {
                continue;
            }

            for (float value : channelTicks)
            {
                if (CompPathGeometry.isUsable(value))
                {
                    ticks.add(value);
                }
            }
        }
    }

    /** Evaluated point ({@code x,y,inX,inY,outX,outY}) at a tick, channel by channel. */
    private float[] remapPointAt(Animation animation, int point, float tick)
    {
        float[] values = new float[COMPONENT_COUNT];

        for (int component = 0; component < COMPONENT_COUNT; component++)
        {
            values[component] = animation.valueAt(point, component, tick);
        }

        return values;
    }

    /** de Casteljau split of one segment at an evaluated tick; same layout as splitSegment. */
    private float[] remapSplit(Animation animation, int start, int end, float tick, float fraction)
    {
        if (start < 0 || end < 0 || start >= this.target.count() || end >= this.target.count())
        {
            return null;
        }

        float[] first = this.remapPointAt(animation, start, tick);
        float[] last = this.remapPointAt(animation, end, tick);
        CompPathGeometry.ArrayPath segment = new CompPathGeometry.ArrayPath(2);

        segment.add(first[0], first[1], first[2], first[3], first[4], first[5]);
        segment.add(last[0], last[1], last[2], last[3], last[4], last[5]);
        segment.setClosed(false);

        return CompPathGeometry.splitSegment(segment, 0, Math.max(0.0001F, Math.min(0.9999F, fraction)));
    }

    /* ------------------------------------------------------------------ keyboard */

    public static final int KEY_NONE = 0;
    public static final int KEY_ENTER = 1;
    public static final int KEY_ESCAPE = 2;
    public static final int KEY_DELETE = 3;

    /** Maps a GLFW key code to the pen's own commands; {@link #KEY_NONE} for unrelated keys. */
    public static int commandForKey(int glfwKey)
    {
        switch (glfwKey)
        {
            case 257: /* GLFW_KEY_ENTER */
            case 335: /* GLFW_KEY_KP_ENTER */
                return KEY_ENTER;
            case 256: /* GLFW_KEY_ESCAPE */
                return KEY_ESCAPE;
            case 259: /* GLFW_KEY_BACKSPACE */
            case 261: /* GLFW_KEY_DELETE */
                return KEY_DELETE;
            default:
                return KEY_NONE;
        }
    }

    /**
     * Keyboard command. {@code Enter} ends the path (staying open), {@code Esc} ends and clears,
     * {@code Delete} removes the selected vertices.
     *
     * @return true when the key was consumed.
     */
    public boolean key(int command)
    {
        if (command == KEY_ENTER)
        {
            if (this.isDrawing())
            {
                this.endPath();

                return true;
            }

            return false;
        }

        if (command == KEY_ESCAPE)
        {
            if (this.isDrawing())
            {
                this.endPath();

                return true;
            }

            if (this.hasSelection())
            {
                this.selection.clear();
                this.listener.status("已取消锚点选择");

                return true;
            }

            return false;
        }

        if (command == KEY_DELETE)
        {
            if (this.hasSelection())
            {
                return this.deleteSelection();
            }
        }

        return false;
    }
}
