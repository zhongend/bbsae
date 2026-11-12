package dev.bbsae.client.ui;

import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompFrameServer;
import dev.bbsae.client.render.CompLayerSourceCache;
import dev.bbsae.client.render.CompPrecache;
import dev.bbsae.client.render.CompRenderCache;
import dev.bbsae.client.render.CompRenderContext;
import dev.bbsae.client.render.CompScene;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompPathGeometry;
import dev.bbsae.comp.CompPenSession;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompShape;
import dev.bbsae.comp.CompStore;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The pen tool's UI layer: event routing, screen ↔ layer-local projection, overlay drawing and the
 * undo/save wiring. The gestures themselves live in {@link CompPenSession}; this class is the only
 * place that knows about pixels, cameras and BBS widgets.
 *
 * <p>Nothing here is a second source of truth: every write goes through the panel's
 * {@code setScalar} path (so animated mask/shape points keep their keyframe channels), and every
 * discrete command opens exactly one undo checkpoint through {@code beginAction}.</p>
 *
 * <p>2D and 3D use one code path. Forward projection is always {@link CompScene#project}, which is
 * also what the renderer/selection box use; inverse projection picks the affine inverse for flat
 * layers and a ray/plane intersection for perspective ones, keyed on
 * {@link CompScene#usePerspective} rather than on {@code layer.threeD} — an orthographic camera
 * makes a 3D layer project affinely, and treating that as perspective is how the old drag code made
 * points fly away.</p>
 */
public final class UIPenTool implements CompPenSession.Listener
{
    /** Everything the tool needs to know about where the preview currently is. */
    public static final class View
    {
        public Area area;
        public float areaX;
        public float areaY;
        public float areaW;
        public float areaH;
        public float scale = 1F;
        public float ox;
        public float oy;
        public int compWidth = 1;
        public int compHeight = 1;
        public CompRenderContext rc;
        /** Non-null only in the free 3D workspace. */
        public CompViewportCamera viewport;
        public boolean space3D;
    }

    private static final int KIND_NONE = 0;
    private static final int KIND_MASK = 1;
    private static final int KIND_SHAPE = 2;

    /* Hit radii, in pixels. AE's own are about 5-9 depending on the element. */
    private static final float VERTEX_RADIUS = 9F;
    private static final float HANDLE_RADIUS = 9F;
    private static final float SEGMENT_RADIUS = 6F;
    private static final float SNAP_RADIUS = 6F;

    private static final int COLOR_MASK = 0xAA55AAFF;
    private static final int COLOR_MASK_SELECTED = 0xFFFFD27A;
    private static final int COLOR_MASK_EXCLUDE = 0xAAFF8A8A;
    private static final int COLOR_SHAPE = 0xAA7AD8FF;
    private static final int COLOR_SHAPE_SELECTED = 0xFFFFD27A;
    private static final int COLOR_ANCHOR = 0xFFFFFFFF;
    private static final int COLOR_ANCHOR_SELECTED = 0xFFFFD27A;
    private static final int COLOR_ANCHOR_OUTLINE = 0xFF101418;
    private static final int COLOR_IN = 0xFF7AD8FF;
    private static final int COLOR_OUT = 0xFFFF9BD2;
    private static final int COLOR_RUBBER = 0x99FFD27A;
    private static final int COLOR_MARQUEE_FILL = 0x22FFD27A;
    private static final int COLOR_MARQUEE_LINE = 0xAAFFD27A;
    private static final int COLOR_HINT = 0xCCFFFFFF;

    private final UICompositorPanel panel;
    private final CompPenSession session = new CompPenSession(this);

    private boolean active;
    private boolean dirty;
    private boolean structureDirty;
    /** AE "preserve constant vertex count" preference, mirrored into the session. */
    private boolean keepVertexCount = true;
    /** Frame-scoped memo of evaluated path components; bumped on every pen edit. */
    private final EvaluationCache evaluations = new EvaluationCache();
    private long evaluationRevision;

    private CompLayer sessionLayer;
    private int targetKind = KIND_NONE;
    private int targetMask = -1;
    private int targetShape = -1;
    private CompPenSession.Target targetAdapter;

    private float mouseX;
    private float mouseY;
    private int hoverPoint = -1;
    private boolean hoverValid;

    private boolean marqueeActive;
    private float marqueeStartX;
    private float marqueeStartY;
    private float marqueeEndX;
    private float marqueeEndY;

    public UIPenTool(UICompositorPanel panel)
    {
        this.panel = panel;
        this.keepVertexCount = CompPrefs.get().penKeepVertexCount;
        this.session.setKeepVertexCount(this.keepVertexCount);
    }

    /* ------------------------------------------------------------------ tool state */

    /**
     * AE's "Preserve Constant Vertex and Feather Point Count". With it off, structural edits on
     * animated paths are refused by the session (the per-channel model cannot encode per-keyframe
     * vertex counts); unanimated paths behave the same either way.
     */
    public void setKeepVertexCount(boolean keep)
    {
        this.keepVertexCount = keep;
        this.session.setKeepVertexCount(keep);
    }

    public boolean keepVertexCount()
    {
        return this.keepVertexCount;
    }

    /** Pulls the preference from {@link CompPrefs}; the settings UI changes it there. */
    private void syncKeepVertexCount()
    {
        boolean keep = CompPrefs.get().penKeepVertexCount;

        if (keep != this.keepVertexCount)
        {
            this.setKeepVertexCount(keep);
        }
    }

    /**
     * One-entry memo for a whole path's evaluated point components.
     *
     * <p>Why: with animation the pen re-reads {@code layer.scalarAt} for every component of every
     * point, and the overlay/hit test/drag snapshot all need the same values several times per
     * frame (each accessor used to interpolate a channel again). The cache resolves a path once per
     * (layer identity, path identity, frame, revision) and the accessors become array reads. It is
     * static and dependency free so the hit/miss rules can be unit tested without a panel.</p>
     */
    public static final class EvaluationCache
    {
        private Object layer;
        private Object path;
        private float frame;
        private long revision;
        private float[] values;

        /**
         * @return the cached component array, or {@code null} when this (layer, path, frame,
         *         revision, length) combination is not the one in the slot.
         */
        public float[] fetch(Object layer, Object path, float frame, long revision, int length)
        {
            if (this.values != null
                && this.layer == layer
                && this.path == path
                && Float.compare(this.frame, frame) == 0
                && this.revision == revision
                && this.values.length == length)
            {
                return this.values;
            }

            return null;
        }

        public void store(Object layer, Object path, float frame, long revision, float[] values)
        {
            this.layer = layer;
            this.path = path;
            this.frame = frame;
            this.revision = revision;
            this.values = values;
        }

        public void invalidate()
        {
            this.values = null;
            this.layer = null;
            this.path = null;
        }
    }

    public boolean isActive()
    {
        return this.active;
    }

    public void toggle()
    {
        this.setActive(!this.active);
    }

    public void setActive(boolean active)
    {
        if (this.active == active)
        {
            return;
        }

        if (!active)
        {
            this.cancel();
        }
        else
        {
            /* The camera tool owns the same mouse buttons, so the two can never be armed at once. */
            if (this.panel.cameraToolActive())
            {
                this.panel.setCameraTool(false);
            }

            this.panel.setStatus("钢笔：单击加角点 · 按下拖动加平滑点 · 点击起点闭合 · Enter 结束 · Esc 取消 · Alt 转换点 · Ctrl 框选");
        }

        this.active = active;
    }

    /** Aborts the in-flight gesture but keeps the edited path selected. */
    public void cancel()
    {
        this.session.cancel();
        this.marqueeActive = false;
        this.hoverValid = false;
        this.hoverPoint = -1;
    }

    /** Drops the edited path (layer/composition/workspace changed). */
    public void forget()
    {
        this.cancel();
        this.session.forget();
        this.sessionLayer = null;
        this.targetAdapter = null;
        this.targetKind = KIND_NONE;
        this.targetMask = -1;
        this.targetShape = -1;
        this.dirty = false;
        this.structureDirty = false;
        this.invalidateEvaluations();
    }

    /** Drops the frame-scoped evaluation memo; every pen edit/state change bumps the revision. */
    private void invalidateEvaluations()
    {
        this.evaluationRevision += 1;
        this.evaluations.invalidate();
    }

    /**
     * True when the pen's session target still exists: the layer is part of {@code doc} and the
     * mask/shape slot it points at is still there. Static so the integration rules can be tested on
     * plain model objects (no panel, no GL).
     */
    public static boolean targetExists(CompDocument doc, CompLayer layer, boolean mask, int index)
    {
        if (doc == null || layer == null || doc.layers == null || !doc.layers.contains(layer))
        {
            return false;
        }

        if (mask)
        {
            return layer.masks != null && index >= 0 && index < layer.masks.size();
        }

        return layer.shapes != null && index >= 0 && index < layer.shapes.size()
            && CompShape.PATH.equals(layer.shapes.get(index).type);
    }

    /**
     * Drops a session whose layer/path was deleted or whose composition was switched behind its
     * back (delete layer, enter/exit precomp, project reload). Every gesture entry point calls this
     * first, so the pen can never write into an object that is no longer in the document.
     */
    private boolean ensureSessionValid()
    {
        if (this.sessionLayer == null)
        {
            return true;
        }

        boolean mask = this.targetKind == KIND_MASK;
        int index = mask ? this.targetMask : this.targetShape;

        if (this.targetKind != KIND_NONE
            && targetExists(this.panel.getDocument(), this.sessionLayer, mask, index))
        {
            return true;
        }

        this.forget();
        this.panel.setStatus("钢笔目标已不存在（图层/路径被删除或切换了合成），已退出编辑");

        return false;
    }

    /** The camera tool and the pen must never be armed at the same time (they share the buttons). */
    private void enforceToolExclusivity()
    {
        if (this.active && this.panel.cameraToolActive())
        {
            this.panel.setCameraTool(false);
        }
    }

    /**
     * AE's tool switch: pressing {@code V} (or {@code A}, the alternative scheme's selection
     * binding) while the pen is armed returns to plain layer selection. Modifier combinations are
     * left to the shortcut table (Ctrl+A selects all layers, Shift+A is selection-add).
     */
    public static boolean isSelectionToolSwitchKey(int glfwKey, boolean ctrl, boolean shift, boolean alt)
    {
        if (ctrl || shift || alt)
        {
            return false;
        }

        return glfwKey == GLFW.GLFW_KEY_V || glfwKey == GLFW.GLFW_KEY_A;
    }

    public boolean isDrawing()
    {
        return this.session.isDrawing();
    }

    /** Inspector button: edit this mask with the pen. */
    public void editMask(CompMask mask)
    {
        CompLayer layer = this.panel.getSelectedLayer();

        if (layer == null || mask == null)
        {
            return;
        }

        int index = layer.masks.indexOf(mask);

        if (index < 0)
        {
            return;
        }

        this.setActive(true);
        this.activate(layer, KIND_MASK, index);
        this.session.begin(this.targetAdapter);
        this.panel.setStatus("钢笔正在编辑「" + this.targetAdapter.label() + "」：单击起点闭合，Enter 结束开放路径");
    }

    /** Inspector button: edit this shape path with the pen. */
    public void editShape(CompShape shape)
    {
        CompLayer layer = this.panel.getSelectedLayer();

        if (layer == null || shape == null || !CompShape.PATH.equals(shape.type))
        {
            return;
        }

        int index = layer.shapes.indexOf(shape);

        if (index < 0)
        {
            return;
        }

        this.setActive(true);
        this.activate(layer, KIND_SHAPE, index);
        this.session.begin(this.targetAdapter);
        this.panel.setStatus("钢笔正在编辑「" + this.targetAdapter.label() + "」：单击起点闭合，Enter 结束开放路径");
    }

    /* ------------------------------------------------------------------ input */

    /**
     * Mouse press. Returns true when the pen consumed it (the preview must not also select or drag
     * a layer). Runs before the camera tool / 3D navigation branches in the preview, because while
     * the pen is armed every preview click belongs to it.
     */
    public boolean onPress(UIContext context, View view)
    {
        if (!this.active || view == null || view.rc == null)
        {
            return false;
        }

        this.syncKeepVertexCount();
        this.enforceToolExclusivity();

        if (!this.ensureSessionValid())
        {
            return false;
        }

        CompLayer layer = this.panel.getSelectedLayer();

        if (layer == null || layer.masks == null || layer.shapes == null)
        {
            return false;
        }

        int button = context.mouseButton;

        if (button != 0 && button != 1)
        {
            return false;
        }

        /* Switching layers mid-gesture ends the gesture; it must never write into a path that is no
         * longer the one the user is looking at. */
        if (this.sessionLayer != null && this.sessionLayer != layer)
        {
            this.forget();
        }

        this.mouseX = context.mouseX;
        this.mouseY = context.mouseY;
        /* The vertex-count remap splits the path differently at the playhead (user's click
         * parameter) than at every other key (0.5), so the session needs the current tick. */
        this.session.setTick(this.panel.getFrame());

        if (button == 1)
        {
            /* Right click is the pen's context menu in pen mode. Consuming it also keeps "enter the
             * 3D workspace" from firing on an accidental right click while drawing. */
            this.showMenu(context, layer);
            return true;
        }

        if (layer.locked)
        {
            this.panel.setStatus("图层已锁定：解锁后才能用钢笔画路径");

            return true;
        }

        if ("camera".equals(layer.type) || "adjustment".equals(layer.type))
        {
            this.panel.setStatus("该图层不产生像素，不能添加蒙版/形状路径");

            return false;
        }

        float[] local = this.screenToLocal(layer, view, context.mouseX, context.mouseY);
        HitResult resolved = this.resolveHit(layer, view, context.mouseX, context.mouseY);

        if (resolved != null && !this.isCurrentTarget(resolved))
        {
            if (this.session.isDrawing())
            {
                this.session.endPath();
            }

            this.activate(layer, resolved.kind, resolved.index);
        }

        /* Pen + Ctrl is After Effects' temporary Selection tool: select anchors / marquee instead
         * of adding points. */
        if (CompInput.ctrlDown())
        {
            if (resolved != null && resolved.hit.kind == CompPenSession.HIT_VERTEX)
            {
                this.session.selectVertex(resolved.hit.point, CompInput.shiftDown());
                this.panel.setStatus("已选择锚点 " + (resolved.hit.point + 1));

                return true;
            }

            if (this.targetAdapter != null && !this.session.isDrawing())
            {
                this.marqueeActive = true;
                this.marqueeStartX = this.marqueeEndX = context.mouseX;
                this.marqueeStartY = this.marqueeEndY = context.mouseY;

                return true;
            }

            return true;
        }

        CompPenSession.Hit hit = resolved == null ? CompPenSession.Hit.none() : resolved.hit;

        if (this.targetAdapter == null)
        {
            return this.createNewPath(layer, view, context, local, hit);
        }

        if (local == null)
        {
            this.panel.setStatus("无法在该位置落点：鼠标射线与图层平面不相交（相机后或平面侧对屏幕）");

            return true;
        }

        boolean shift = CompInput.shiftDown();
        boolean constrained = false;

        /* Shift constrains the new point to 45 degrees measured on screen (which is what the user
         * sees). The constrained screen point is converted to local exactly once: passing Shift on
         * to the session would constrain the already-constrained local vector a second time, which
         * bends the line on rotated or non-uniformly scaled layers. */
        if (shift && this.session.isDrawing() && hit.kind == CompPenSession.HIT_NONE
            && this.session.anchorPoint() >= 0)
        {
            int anchor = this.session.anchorPoint();
            float[] constrainedLocal = constrainTo45OnScreen(layer, view,
                targetAdapterX(this.targetAdapter, anchor), targetAdapterY(this.targetAdapter, anchor),
                context.mouseX, context.mouseY);

            if (constrainedLocal != null)
            {
                local = constrainedLocal;
                constrained = true;
            }
        }

        /* A shift-constrained point must not also be snapped to a vertex/grid; the constraint the
         * user asked for wins. */
        if (hit.kind == CompPenSession.HIT_NONE && !constrained)
        {
            local = this.snapLocal(layer, view, context.mouseX, context.mouseY, local);
        }

        /* Remember where the drag started in local space; the screen-space Shift constraint during
         * a drag is measured from this projected point. */
        this.dragOriginLocalX = local[0];
        this.dragOriginLocalY = local[1];

        int result = this.session.press(local[0], local[1], context.mouseX, context.mouseY,
            constrained ? false : shift, CompInput.altDown(), CompPenSession.CLOSE_RADIUS_PX, hit);

        if (result == CompPenSession.PRESS_REQUEST_NEW_PATH)
        {
            return this.createNewPath(layer, view, context, local, hit);
        }

        return result != CompPenSession.PRESS_IGNORED;
    }

    /**
     * Mouse drag. Called from the preview's per-frame poll while the left button is down.
     *
     * @return true when the pen owned this frame's drag (the layer move must not also run)
     */
    public boolean onDrag(UIContext context, View view)
    {
        if (!this.active)
        {
            return false;
        }

        this.syncKeepVertexCount();
        this.enforceToolExclusivity();

        if (!this.ensureSessionValid())
        {
            return false;
        }

        this.mouseX = context.mouseX;
        this.mouseY = context.mouseY;

        if (this.marqueeActive)
        {
            this.marqueeEndX = context.mouseX;
            this.marqueeEndY = context.mouseY;

            return true;
        }

        if (!this.session.isDragging())
        {
            return false;
        }

        this.mouseX = context.mouseX;
        this.mouseY = context.mouseY;

        if (!mchorse.bbs_mod.graphics.window.Window.isMouseButtonPressed(0))
        {
            this.finishDrag();

            return true;
        }

        CompLayer layer = this.sessionLayer;

        if (layer == null || view == null)
        {
            return false;
        }

        float[] local = this.screenToLocal(layer, view, context.mouseX, context.mouseY);

        if (local == null)
        {
            return true;
        }

        boolean shift = CompInput.shiftDown();
        boolean alt = CompInput.altDown();
        boolean constrained = false;

        /* Shift constrains vertex moves to 45 degrees on screen (a handle drag stays free — that is
         * what Alt is for). Converting the constrained screen point back once keeps the direction
         * correct on rotated/perspective layers; the session then must not constrain again. */
        if (shift && this.session.isDraggingVertices())
        {
            float[] constrainedLocal = constrainTo45OnScreen(layer, view,
                this.dragOriginLocalX, this.dragOriginLocalY, context.mouseX, context.mouseY);

            if (constrainedLocal != null)
            {
                local = constrainedLocal;
                constrained = true;
            }
        }

        if (this.session.drag(local[0], local[1], context.mouseX, context.mouseY, shift && !constrained, alt))
        {
            this.dirty = true;
        }

        return true;
    }

    /** Mouse release: applies the marquee or ends a vertex/handle drag. */
    public boolean onRelease(UIContext context)
    {
        if (!this.active)
        {
            return false;
        }

        if (this.marqueeActive)
        {
            this.marqueeActive = false;
            this.applyMarquee();

            return true;
        }

        if (this.session.isDragging())
        {
            this.finishDrag();

            return true;
        }

        return false;
    }

    /**
     * Key handling: Enter ends the path (open), Esc ends/cancels, Delete removes selected anchors.
     * Only called when the pen is armed and no text field has focus.
     */
    public boolean onKey(UIContext context)
    {
        if (!this.active || context.getKeyAction() != KeyAction.PRESSED)
        {
            return false;
        }

        this.syncKeepVertexCount();
        this.enforceToolExclusivity();

        if (!this.ensureSessionValid())
        {
            return false;
        }

        /* AE: V (selection) while the pen is armed returns to the selection tool. A is the same
         * command in the alternative scheme. Modifiers stay with the shortcut table. */
        if (isSelectionToolSwitchKey(context.getKeyCode(),
            CompInput.ctrlDown(), CompInput.shiftDown(), CompInput.altDown()))
        {
            this.setActive(false);
            this.panel.setStatus("已切换到选择工具（钢笔关闭）");

            return true;
        }

        int command = CompPenSession.commandForKey(context.getKeyCode());

        if (command == CompPenSession.KEY_NONE)
        {
            return false;
        }

        this.session.setTick(this.panel.getFrame());

        if (!this.session.key(command))
        {
            return false;
        }

        if (command == CompPenSession.KEY_DELETE)
        {
            this.structureDirty = true;
        }

        this.flush();
        this.panel.setStatus(this.statusFor(command));

        return true;
    }

    private String statusFor(int command)
    {
        if (command == CompPenSession.KEY_ENTER)
        {
            return "钢笔：路径结束（开放路径不参与蒙版遮罩；右键菜单可闭合）";
        }

        if (command == CompPenSession.KEY_ESCAPE)
        {
            return "钢笔：已结束/取消";
        }

        return "钢笔：已删除选中锚点";
    }

    /* ------------------------------------------------------------------ commands from menus / inspector */

    public boolean closeTargetPath()
    {
        boolean changed = this.session.closePath();

        this.flush();

        return changed;
    }

    public boolean openTargetPath()
    {
        boolean changed = this.session.openPath();

        this.flush();

        return changed;
    }

    public boolean convertSelection(boolean smooth)
    {
        int index = this.session.primarySelection();

        if (index < 0)
        {
            return false;
        }

        boolean changed = this.session.convertVertex(index, smooth);

        this.flush();

        return changed;
    }

    public boolean deleteSelection()
    {
        boolean changed = this.session.deleteSelection();

        if (changed)
        {
            this.structureDirty = true;
            this.flush();
        }

        return changed;
    }

    /**
     * AE's "Set First Vertex": rotates a closed path so the given anchor becomes vertex 0. The
     * closed shape is unchanged; animated channels rotate with the vertices, so every keyframe
     * keeps its geometry.
     */
    public boolean setFirstVertex(int index)
    {
        boolean changed = this.session.setFirstVertex(index);

        if (changed)
        {
            this.structureDirty = true;
            this.flush();
        }

        return changed;
    }

    /** Keys every point channel of the edited path at the playhead ("整条路径打关键帧"). */
    public void keyWholePath()
    {
        if (this.targetAdapter == null || this.sessionLayer == null)
        {
            this.panel.setStatus("先用钢笔选中一条路径");

            return;
        }

        CompLayer layer = this.sessionLayer;
        int count = this.targetAdapter.count();

        if (count < 2)
        {
            this.panel.setStatus("路径至少需要 2 个点才能打关键帧");

            return;
        }

        String base;

        if (this.targetKind == KIND_MASK && this.targetAdapter instanceof MaskTarget target)
        {
            base = "mask/" + target.mask.id + "/p/";
        }
        else if (this.targetKind == KIND_SHAPE && this.targetAdapter instanceof ShapeTarget target)
        {
            base = "shape/" + target.shape.id + "/p/";
        }
        else
        {
            return;
        }

        this.panel.penBeginAction("钢笔：整条路径打关键帧");

        String[] channels = {"x", "y", "inX", "inY", "outX", "outY"};
        float frame = this.panel.getFrame();

        for (int i = 0; i < count; i++)
        {
            float[] values = {
                this.targetAdapter.x(i), this.targetAdapter.y(i),
                this.targetAdapter.inX(i), this.targetAdapter.inY(i),
                this.targetAdapter.outX(i), this.targetAdapter.outY(i)
            };

            for (int c = 0; c < channels.length; c++)
            {
                layer.scalarChannel(base + i + "/" + channels[c]).insert(frame, values[c]);
            }
        }

        this.panel.penSave();
        this.panel.penRefreshInspector();
        this.panel.setStatus("已给整条路径打关键帧（" + count + " 点 × 6 通道）；此后不能再增删点");
    }

    /**
     * Inspector shortcut: append a point to a mask path without entering draw mode. Routed through
     * the session so an animated path gets its channels remapped instead of corrupted.
     */
    public boolean appendMaskPoint(CompLayer layer, CompMask mask, float localX, float localY)
    {
        int index = layer == null || mask == null ? -1 : layer.masks.indexOf(mask);

        if (index < 0)
        {
            return false;
        }

        this.activate(layer, KIND_MASK, index);
        this.session.setTick(this.panel.getFrame());

        return this.session.appendPoint(localX, localY);
    }

    /** Inspector shortcut: append a point to a shape path (animation safe). */
    public boolean appendShapePoint(CompLayer layer, CompShape shape, float localX, float localY)
    {
        int index = layer == null || shape == null ? -1 : layer.shapes.indexOf(shape);

        if (index < 0 || !CompShape.PATH.equals(shape.type))
        {
            return false;
        }

        this.activate(layer, KIND_SHAPE, index);
        this.session.setTick(this.panel.getFrame());

        return this.session.appendPoint(localX, localY);
    }

    /** Inspector shortcut: remove one mask vertex (animation safe). */
    public boolean removeMaskPoint(CompLayer layer, CompMask mask, int pointIndex)
    {
        int index = layer == null || mask == null ? -1 : layer.masks.indexOf(mask);

        if (index < 0)
        {
            return false;
        }

        this.activate(layer, KIND_MASK, index);
        this.session.setTick(this.panel.getFrame());

        return this.session.deletePoint(pointIndex);
    }

    /** Inspector shortcut: remove one shape path vertex (animation safe). */
    public boolean removeShapePoint(CompLayer layer, CompShape shape, int pointIndex)
    {
        int index = layer == null || shape == null ? -1 : layer.shapes.indexOf(shape);

        if (index < 0 || !CompShape.PATH.equals(shape.type))
        {
            return false;
        }

        this.activate(layer, KIND_SHAPE, index);
        this.session.setTick(this.panel.getFrame());

        return this.session.deletePoint(pointIndex);
    }

    /** Inspector shortcut: key a mask's whole path at the playhead without entering draw mode. */
    public boolean keyMask(CompLayer layer, CompMask mask)
    {
        int index = layer == null || mask == null ? -1 : layer.masks.indexOf(mask);

        if (index < 0)
        {
            return false;
        }

        this.activate(layer, KIND_MASK, index);

        if (this.targetAdapter == null)
        {
            return false;
        }

        this.keyWholePath();

        return true;
    }

    /** Inspector shortcut: key a shape path's whole outline at the playhead. */
    public boolean keyShape(CompLayer layer, CompShape shape)
    {
        int index = layer == null || shape == null ? -1 : layer.shapes.indexOf(shape);

        if (index < 0 || !CompShape.PATH.equals(shape.type))
        {
            return false;
        }

        this.activate(layer, KIND_SHAPE, index);

        if (this.targetAdapter == null)
        {
            return false;
        }

        this.keyWholePath();

        return true;
    }

    /* ------------------------------------------------------------------ context menu */

    private void showMenu(UIContext context, CompLayer layer)
    {
        boolean hasTarget = this.targetAdapter != null;
        boolean selection = this.session.hasSelection();
        int primary = this.session.primarySelection();
        boolean drawing = this.session.isDrawing();
        boolean closed = hasTarget && this.targetAdapter.closed();
        int count = hasTarget ? this.targetAdapter.count() : 0;

        context.replaceContextMenu((manager) ->
        {
            if (drawing)
            {
                manager.action(Icons.CHECKMARK, IKey.constant("结束路径（Enter）"), () -> this.endDrawing());
            }

            if (selection && primary >= 0)
            {
                manager.action(Icons.EDIT, IKey.constant("转为角点"), () -> this.convertSelection(false));
                manager.action(Icons.ARC, IKey.constant("转为平滑点"), () -> this.convertSelection(true));

                if (closed && count >= 3 && primary > 0)
                {
                    manager.action(Icons.CHECKMARK, IKey.constant("设为第一顶点"), () -> this.setFirstVertex(primary));
                }

                manager.action(Icons.ARC, IKey.constant("整条路径打关键帧"), this::keyWholePath);
                manager.action(Icons.TRASH, IKey.constant("删除所选顶点（Delete）"), () -> this.deleteSelection());
            }

            if (hasTarget && !closed && count >= 2)
            {
                manager.action(Icons.CHECKMARK, IKey.constant("闭合路径"), () -> this.closeTargetPath());
            }

            if (hasTarget && closed)
            {
                manager.action(Icons.LINE, IKey.constant("断开路径（开放）"), () -> this.openTargetPath());
            }

            if (hasTarget)
            {
                boolean roto = this.targetAdapter.rotoBezier();

                manager.action(Icons.ARC, IKey.constant(roto ? "关闭 RotoBezier" : "开启 RotoBezier（自动切线）"),
                    () -> this.setRotoBezier(!roto));
            }

            if (hasTarget && !selection)
            {
                manager.action(Icons.ARC, IKey.constant("整条路径打关键帧"), this::keyWholePath);
            }

            manager.action(Icons.CLOSE, IKey.constant("退出钢笔"), () -> this.setActive(false));
        });
    }

    private void endDrawing()
    {
        this.session.endPath();
        this.flush();
    }

    /**
     * Toggles AE's RotoBezier editing mode on the edited path: handles are derived from the
     * neighbours instead of being authored, and re-derived after vertex edits/deletes.
     */
    public boolean setRotoBezier(boolean rotoBezier)
    {
        boolean changed = this.session.setRotoBezier(rotoBezier);

        if (changed)
        {
            this.invalidateEvaluations();
            this.flush();
        }

        return changed;
    }

    public boolean rotoBezier()
    {
        return this.session.rotoBezier();
    }

    /* ------------------------------------------------------------------ overlay */

    public void renderOverlay(UIContext context, View view)
    {
        if (!this.active || view == null || view.rc == null || view.area == null)
        {
            return;
        }

        /* The camera tool grabs the same buttons; if it was armed after the pen (toolbar click),
         * disarm it here before it can orbit on the next press. */
        this.enforceToolExclusivity();

        if (!this.ensureSessionValid())
        {
            return;
        }

        CompLayer layer = this.panel.getSelectedLayer();

        if (layer == null || layer.masks == null || layer.shapes == null || this.panel.isPanelResizing())
        {
            return;
        }

        this.mouseX = context.mouseX;
        this.mouseY = context.mouseY;

        boolean clip = view.area.w > 0 && view.area.h > 0;

        if (clip)
        {
            context.batcher.clip(view.area, context);
        }

        try
        {
            this.updateHover(layer, view);

            for (int i = 0; i < layer.masks.size(); i++)
            {
                CompMask mask = layer.masks.get(i);

                if (mask == null || mask.points.isEmpty())
                {
                    continue;
                }

                boolean current = this.sessionLayer == layer && this.targetKind == KIND_MASK && this.targetMask == i;
                int color = current ? COLOR_MASK_SELECTED
                    : mask.mode == CompMask.MODE_EXCLUDE ? COLOR_MASK_EXCLUDE : COLOR_MASK;

                this.drawPath(context, layer, view, new MaskTarget(layer, mask), mask.closed, color);
                this.drawAnchors(context, layer, view, new MaskTarget(layer, mask), current);
            }

            for (int i = 0; i < layer.shapes.size(); i++)
            {
                CompShape shape = layer.shapes.get(i);

                if (shape == null || !CompShape.PATH.equals(shape.type) || shape.points.isEmpty())
                {
                    continue;
                }

                boolean current = this.sessionLayer == layer && this.targetKind == KIND_SHAPE && this.targetShape == i;
                int color = current ? COLOR_SHAPE_SELECTED : COLOR_SHAPE;

                this.drawPath(context, layer, view, new ShapeTarget(layer, shape), shape.closed, color);
                this.drawAnchors(context, layer, view, new ShapeTarget(layer, shape), current);
            }

            if (this.sessionLayer == layer && this.targetAdapter != null)
            {
                this.drawHandles(context, layer, view);
                this.drawRubberBand(context, layer, view);
            }

            if (this.marqueeActive)
            {
                this.drawMarquee(context);
            }

            this.drawHint(context, view);
        }
        finally
        {
            if (clip)
            {
                context.batcher.unclip(context);
            }
        }

        /* The pen has its own cursor: a crosshair reads as "draw", the default arrow reads as
         * "select". There is no pen glyph in GLFW, so this is the closest hint available. */
        if (view.area.isInside(context.mouseX, context.mouseY))
        {
            if (this.hoverValid && this.hoverPoint >= 0)
            {
                context.requestCursor(GLFW.GLFW_HAND_CURSOR);
            }
            else
            {
                context.requestCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
            }
        }
    }

    private void drawPath(UIContext context, CompLayer layer, View view,
                          CompPathGeometry.PathView path, boolean closed, int color)
    {
        int segments = CompPathGeometry.segmentCount(path);

        for (int segment = 0; segment < segments; segment++)
        {
            float[] control = CompPathGeometry.segment(path, segment);

            if (control == null)
            {
                continue;
            }

            float[] previous = null;
            int steps = 12;

            for (int i = 0; i <= steps; i++)
            {
                float[] point = CompPathGeometry.cubicAt(control, i / (float) steps);
                float[] screen = this.localToScreen(layer, view, point[0], point[1]);

                if (screen != null && previous != null)
                {
                    this.line(context, previous[0], previous[1], screen[0], screen[1], color);
                }

                previous = screen;
            }
        }

        /* Open paths get an end marker so "there is no closing segment" is visible at a glance. */
        if (!closed && path.count() >= 2)
        {
            for (int index : new int[] {0, path.count() - 1})
            {
                float[] screen = this.localToScreen(layer, view, path.x(index), path.y(index));

                if (screen != null)
                {
                    this.ring(context, screen[0], screen[1], 5.5F, color);
                }
            }
        }
    }

    private void drawAnchors(UIContext context, CompLayer layer, View view,
                             CompPathGeometry.PathView path, boolean current)
    {
        Set<Integer> selection = current && this.sessionLayer == layer
            ? this.session.selection()
            : java.util.Collections.emptySet();

        for (int i = 0; i < path.count(); i++)
        {
            float[] screen = this.localToScreen(layer, view, path.x(i), path.y(i));

            if (screen == null)
            {
                continue;
            }

            boolean selected = selection.contains(i);
            boolean hovered = current && this.hoverValid && this.hoverPoint == i;
            float half = selected ? 4.5F : hovered ? 4F : 3.5F;

            context.batcher.box(screen[0] - half - 1F, screen[1] - half - 1F,
                screen[0] + half + 1F, screen[1] + half + 1F, COLOR_ANCHOR_OUTLINE);
            context.batcher.box(screen[0] - half, screen[1] - half, screen[0] + half, screen[1] + half,
                selected ? COLOR_ANCHOR_SELECTED : COLOR_ANCHOR);

            /* Closing hint: while drawing, the start vertex gets a ring when the cursor is close
             * enough to close on the next click. */
            if (current && this.session.isDrawing() && i == 0 && path.count() >= 2
                && CompPathGeometry.distance(this.mouseX, this.mouseY, screen[0], screen[1]) <= CompPenSession.CLOSE_RADIUS_PX)
            {
                this.ring(context, screen[0], screen[1], 8F, COLOR_ANCHOR_SELECTED);
            }
        }
    }

    private void drawHandles(UIContext context, CompLayer layer, View view)
    {
        Set<Integer> selection = this.session.selection();
        CompPathGeometry.PathView path = this.targetAdapter;

        /* RotoBezier derives the tangents; they are not editable, so showing handles would invite
         * a drag that cannot do anything. */
        if (path == null || this.targetAdapter.rotoBezier())
        {
            return;
        }

        for (Integer index : selection)
        {
            if (index == null || index < 0 || index >= path.count())
            {
                continue;
            }

            float[] anchor = this.localToScreen(layer, view, path.x(index), path.y(index));

            if (anchor == null)
            {
                continue;
            }

            for (int side = 0; side < 2; side++)
            {
                boolean out = side == 1;
                float hx = path.x(index) + (out ? path.outX(index) : path.inX(index));
                float hy = path.y(index) + (out ? path.outY(index) : path.inY(index));
                float[] handle = this.localToScreen(layer, view, hx, hy);

                if (handle == null)
                {
                    continue;
                }

                this.line(context, anchor[0], anchor[1], handle[0], handle[1], out ? 0xAAFF9BD2 : 0xAA7AD8FF);
                context.batcher.box(handle[0] - 3F, handle[1] - 3F, handle[0] + 3F, handle[1] + 3F,
                    out ? COLOR_OUT : COLOR_IN);
            }
        }
    }

    private void drawRubberBand(UIContext context, CompLayer layer, View view)
    {
        if (!this.session.isDrawing() || this.session.isDragging() || this.session.anchorPoint() < 0)
        {
            return;
        }

        int anchor = this.session.anchorPoint();

        if (anchor >= this.targetAdapter.count())
        {
            return;
        }

        float[] from = this.localToScreen(layer, view, this.targetAdapter.x(anchor), this.targetAdapter.y(anchor));

        if (from == null)
        {
            return;
        }

        if (!view.area.isInside((int) this.mouseX, (int) this.mouseY))
        {
            return;
        }

        this.line(context, from[0], from[1], this.mouseX, this.mouseY, COLOR_RUBBER);
    }

    private void drawMarquee(UIContext context)
    {
        float x1 = Math.min(this.marqueeStartX, this.marqueeEndX);
        float y1 = Math.min(this.marqueeStartY, this.marqueeEndY);
        float x2 = Math.max(this.marqueeStartX, this.marqueeEndX);
        float y2 = Math.max(this.marqueeStartY, this.marqueeEndY);

        context.batcher.box(x1, y1, x2, y2, COLOR_MARQUEE_FILL);
        context.batcher.outline(x1, y1, x2, y2, COLOR_MARQUEE_LINE);
    }

    private void drawHint(UIContext context, View view)
    {
        String hint = this.session.isDrawing()
            ? "钢笔：单击加点 · 拖动拖出手柄 · 点击起点闭合 · Enter 结束 · Esc 取消"
            : "钢笔：单击空白新建路径 · 点锚点/手柄编辑 · Ctrl 框选 · Alt 转换点 · 右键菜单";

        context.batcher.textShadow(hint, (int) (view.areaX + 8), (int) (view.areaY + view.areaH - 34), COLOR_HINT);
    }

    /* ------------------------------------------------------------------ marquee / hover */

    private void applyMarquee()
    {
        if (this.targetAdapter == null)
        {
            return;
        }

        CompLayer layer = this.sessionLayer;

        if (layer == null)
        {
            return;
        }

        View view = this.panel.lastPenView;

        if (view == null)
        {
            return;
        }

        float x1 = Math.min(this.marqueeStartX, this.marqueeEndX);
        float y1 = Math.min(this.marqueeStartY, this.marqueeEndY);
        float x2 = Math.max(this.marqueeStartX, this.marqueeEndX);
        float y2 = Math.max(this.marqueeStartY, this.marqueeEndY);

        List<Integer> inside = new ArrayList<>();

        for (int i = 0; i < this.targetAdapter.count(); i++)
        {
            float[] screen = this.localToScreen(layer, view, this.targetAdapter.x(i), this.targetAdapter.y(i));

            if (screen != null && screen[0] >= x1 && screen[0] <= x2 && screen[1] >= y1 && screen[1] <= y2)
            {
                inside.add(i);
            }
        }

        this.session.setSelection(inside);
        this.panel.setStatus(inside.isEmpty() ? "框选：没有锚点在框内" : "框选：已选中 " + inside.size() + " 个锚点");
    }

    private void updateHover(CompLayer layer, View view)
    {
        this.hoverValid = false;
        this.hoverPoint = -1;

        if (this.targetAdapter == null || this.sessionLayer != layer)
        {
            return;
        }

        if (!view.area.isInside((int) this.mouseX, (int) this.mouseY))
        {
            return;
        }

        float best = VERTEX_RADIUS;

        for (int i = 0; i < this.targetAdapter.count(); i++)
        {
            float[] screen = this.localToScreen(layer, view, this.targetAdapter.x(i), this.targetAdapter.y(i));

            if (screen == null)
            {
                continue;
            }

            float distance = CompPathGeometry.distance(this.mouseX, this.mouseY, screen[0], screen[1]);

            if (distance <= best)
            {
                best = distance;
                this.hoverPoint = i;
            }
        }

        this.hoverValid = this.hoverPoint >= 0;
    }

    /* ------------------------------------------------------------------ hit resolution */

    private static final class HitResult
    {
        int kind;
        int index;
        CompPenSession.Hit hit;
    }

    private HitResult resolveHit(CompLayer layer, View view, float screenX, float screenY)
    {
        HitResult best = null;
        float bestScore = Float.MAX_VALUE;

        for (int i = 0; i < layer.masks.size(); i++)
        {
            CompMask mask = layer.masks.get(i);

            if (mask == null || mask.points.isEmpty())
            {
                continue;
            }

            boolean current = this.sessionLayer == layer && this.targetKind == KIND_MASK && this.targetMask == i;
            MaskTarget adapter = new MaskTarget(layer, mask,
                current && !mask.rotoBezier ? this.session.selection() : null);
            CompPenSession.Hit hit = CompPenSession.hitTest(adapter, this.projector(layer, view),
                screenX, screenY, VERTEX_RADIUS, HANDLE_RADIUS, SEGMENT_RADIUS, adapter.handles);

            if (hit.kind == CompPenSession.HIT_NONE)
            {
                continue;
            }

            float score = scoreOf(hit);

            if (score < bestScore)
            {
                bestScore = score;
                HitResult result = new HitResult();

                result.kind = KIND_MASK;
                result.index = i;
                result.hit = hit;
                best = result;
            }
        }

        for (int i = 0; i < layer.shapes.size(); i++)
        {
            CompShape shape = layer.shapes.get(i);

            if (shape == null || !CompShape.PATH.equals(shape.type) || shape.points.isEmpty())
            {
                continue;
            }

            boolean current = this.sessionLayer == layer && this.targetKind == KIND_SHAPE && this.targetShape == i;
            ShapeTarget adapter = new ShapeTarget(layer, shape,
                current && !shape.rotoBezier ? this.session.selection() : null);
            CompPenSession.Hit hit = CompPenSession.hitTest(adapter, this.projector(layer, view),
                screenX, screenY, VERTEX_RADIUS, HANDLE_RADIUS, SEGMENT_RADIUS, adapter.handles);

            if (hit.kind == CompPenSession.HIT_NONE)
            {
                continue;
            }

            float score = scoreOf(hit);

            if (score < bestScore)
            {
                bestScore = score;
                HitResult result = new HitResult();

                result.kind = KIND_SHAPE;
                result.index = i;
                result.hit = hit;
                best = result;
            }
        }

        return best;
    }

    private static float scoreOf(CompPenSession.Hit hit)
    {
        /* Vertex before handle before segment: a click next to both a vertex and one of its own
         * tangents must edit the vertex (AE #1042 behaviour). */
        int priority = hit.kind == CompPenSession.HIT_VERTEX ? 0
            : hit.kind == CompPenSession.HIT_SEGMENT ? 2 : 1;

        return priority * 10000F + hit.screenDistance;
    }

    private boolean isCurrentTarget(HitResult result)
    {
        return this.sessionLayer == this.panel.getSelectedLayer()
            && this.targetKind == result.kind
            && (result.kind == KIND_MASK ? this.targetMask : this.targetShape) == result.index
            && this.targetAdapter != null;
    }

    /* ------------------------------------------------------------------ projection */

    private CompPenSession.Projector projector(CompLayer layer, View view)
    {
        return (localX, localY) -> this.localToScreen(layer, view, localX, localY);
    }

    /**
     * Layer-local point → preview pixel. Static so the 2D/3D mapping can be unit-tested without a
     * panel, a GL context or a running client — it is the one piece of the pen that must agree
     * with the renderer pixel for pixel.
     */
    public static float[] localToScreen(CompLayer layer, View view, float localX, float localY)
    {
        if (layer == null || view == null || view.rc == null)
        {
            return null;
        }

        if (view.space3D)
        {
            if (view.viewport == null)
            {
                return null;
            }

            Vector3f world = CompScene.world(view.rc, layer).transformPosition(new Vector3f(localX, localY, 0F));
            Vector2f projected = view.viewport.project(world);

            if (projected == null)
            {
                return null;
            }

            return new float[] {view.areaX + projected.x, view.areaY + projected.y};
        }

        if (view.scale <= 0F)
        {
            return null;
        }

        Vector2f projected = CompScene.project(view.rc, layer, localX, localY, 0F);

        if (projected == null)
        {
            return null;
        }

        return new float[] {
            view.areaX + view.ox + projected.x * view.scale,
            view.areaY + view.oy + projected.y * view.scale
        };
    }

    /**
     * Applies the pen's Shift constraint: the displacement from the anchor to the cursor is snapped
     * to 45° multiples <b>in screen space</b> and converted back to layer-local coordinates.
     *
     * <p>Doing it once, on screen, is the whole point: 45° means a direction the user can see, and
     * the local direction under rotation/non-uniform scale/perspective is not the same angle. Static
     * so it can be unit-tested against the real projection without a panel.</p>
     *
     * @return constrained local point, or {@code null} when either point cannot be mapped.
     */
    public static float[] constrainTo45OnScreen(CompLayer layer, View view,
                                                float anchorLocalX, float anchorLocalY,
                                                float mouseX, float mouseY)
    {
        float[] anchor = localToScreen(layer, view, anchorLocalX, anchorLocalY);

        if (anchor == null)
        {
            return null;
        }

        float[] constrained = CompPathGeometry.constrain45(anchor[0], anchor[1], mouseX, mouseY);

        return screenToLocal(layer, view, constrained[0], constrained[1]);
    }

    /** Preview pixel → layer-local point; exact inverse of {@link #localToScreen} (static, testable). */
    public static float[] screenToLocal(CompLayer layer, View view, float screenX, float screenY)
    {
        if (layer == null || view == null || view.rc == null)
        {
            return null;
        }

        try
        {
            if (view.space3D)
            {
                if (view.viewport == null)
                {
                    return null;
                }

                Matrix4f world = CompScene.world(view.rc, layer);
                Vector3f origin = world.transformPosition(new Vector3f(0F, 0F, 0F));
                Vector3f normal = world.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
                Vector3f hit = view.viewport.unprojectPlane(screenX - view.areaX, screenY - view.areaY, origin, normal);

                if (hit == null || normal.lengthSquared() < 0.000001F)
                {
                    return null;
                }

                Vector3f local = new Vector3f(hit);

                new Matrix4f(world).invert().transformPosition(local);

                return new float[] {local.x, local.y};
            }

            if (view.scale <= 0F || view.compWidth <= 0 || view.compHeight <= 0)
            {
                return null;
            }

            float compX = (screenX - view.areaX - view.ox) / view.scale;
            float compY = (screenY - view.areaY - view.oy) / view.scale;
            Matrix4f world = CompScene.world(view.rc, layer);

            if (!CompScene.usePerspective(view.rc, layer))
            {
                CompCamera camera = CompScene.camera(view.rc);

                if (!layer.threeD)
                {
                    /* Screen-attached 2D layer: the renderer's model is exactly view2D · world, so
                     * its 4x4 inverse is the exact inverse of the projection. */
                    Matrix4f model = new Matrix4f(camera.view2D).mul(world);
                    Vector3f local = new Vector3f(compX, compY, 0F);

                    new Matrix4f(model).invert().transformPosition(local);

                    return new float[] {local.x, local.y};
                }

                /* A 3D layer under an orthographic camera is projected affinely too, but view2D
                 * drops world z, so the 4x4 inverse of view2D·world is ambiguous: it can return a
                 * point that is not on the layer plane. The correct inverse is the projection ray
                 * through that plane — for an orthographic camera every such ray is parallel to
                 * world Z. (The old drag code keyed this branch on layer.threeD and ray-cast
                 * with the perspective camera, which is the mirror image of the same bug.) */
                Vector3f flat = new Vector3f(compX, compY, 0F);

                new Matrix4f(camera.view2D).invert().transformPosition(flat);

                Vector3f direction = new Vector3f(0F, 0F, 1F);
                Vector3f origin = world.transformPosition(new Vector3f(0F, 0F, 0F));
                Vector3f normal = world.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();

                if (normal.lengthSquared() < 0.000001F)
                {
                    return null;
                }

                float denominator = direction.dot(normal);

                if (Math.abs(denominator) < 0.00001F)
                {
                    return null;
                }

                float t = new Vector3f(origin).sub(flat).dot(normal) / denominator;
                Vector3f hit = new Vector3f(flat).fma(t, direction);
                Vector3f local = new Vector3f(hit);

                new Matrix4f(world).invert().transformPosition(local);

                return new float[] {local.x, local.y};
            }

            /* Perspective layer: intersect the cursor ray with the layer's plane. */
            CompCamera camera = CompScene.camera(view.rc);
            Vector3f rayA = camera.unproject(compX, compY, view.compWidth, view.compHeight, 0F);
            Vector3f rayB = camera.unproject(compX, compY, view.compWidth, view.compHeight, 1000F);
            Vector3f origin = world.transformPosition(new Vector3f(0F, 0F, 0F));
            Vector3f normal = world.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
            Vector3f direction = new Vector3f(rayB).sub(rayA);
            float denominator = direction.dot(normal);

            if (Math.abs(denominator) < 0.00001F)
            {
                return null;
            }

            float t = new Vector3f(origin).sub(rayA).dot(normal) / denominator;
            Vector3f hit = new Vector3f(rayA).fma(t, direction);
            Vector3f local = new Vector3f(hit);

            new Matrix4f(world).invert().transformPosition(local);

            return new float[] {local.x, local.y};
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /* ------------------------------------------------------------------ snapping */

    private float[] snapLocal(CompLayer layer, View view, float screenX, float screenY, float[] fallback)
    {
        float[] snapped = this.snapScreen(layer, view, screenX, screenY);

        if (snapped == null)
        {
            return fallback;
        }

        float[] local = this.screenToLocal(layer, view, snapped[0], snapped[1]);

        return local == null ? fallback : local;
    }

    /**
     * Screen-space snap candidates, in priority order: vertices of other paths, then the grid (when
     * the preview grid is visible). The current path is skipped — snapping to a neighbour you are
     * about to draw over would produce coincident vertices, which cannot be separated again by
     * dragging.
     */
    private float[] snapScreen(CompLayer layer, View view, float screenX, float screenY)
    {
        float best = SNAP_RADIUS;
        float[] result = null;

        for (int i = 0; i < layer.masks.size(); i++)
        {
            if (this.sessionLayer == layer && this.targetKind == KIND_MASK && this.targetMask == i)
            {
                continue;
            }

            CompMask mask = layer.masks.get(i);

            if (mask == null)
            {
                continue;
            }

            /* Anchors are read through the target, i.e. evaluated at the playhead for an animated
             * path — snapping to the authored base position would miss what is on screen. */
            CompPenSession.Target adapter = new MaskTarget(layer, mask);

            for (int point = 0; point < adapter.count(); point++)
            {
                float[] screen = this.localToScreen(layer, view, adapter.x(point), adapter.y(point));

                if (screen == null)
                {
                    continue;
                }

                float distance = CompPathGeometry.distance(screenX, screenY, screen[0], screen[1]);

                if (distance < best)
                {
                    best = distance;
                    result = screen;
                }
            }
        }

        for (int i = 0; i < layer.shapes.size(); i++)
        {
            if (this.sessionLayer == layer && this.targetKind == KIND_SHAPE && this.targetShape == i)
            {
                continue;
            }

            CompShape shape = layer.shapes.get(i);

            if (shape == null || !CompShape.PATH.equals(shape.type))
            {
                continue;
            }

            CompPenSession.Target adapter = new ShapeTarget(layer, shape);

            for (int point = 0; point < adapter.count(); point++)
            {
                float[] screen = this.localToScreen(layer, view, adapter.x(point), adapter.y(point));

                if (screen == null)
                {
                    continue;
                }

                float distance = CompPathGeometry.distance(screenX, screenY, screen[0], screen[1]);

                if (distance < best)
                {
                    best = distance;
                    result = screen;
                }
            }
        }

        CompProject project = this.panel.getProject();

        if (!view.space3D && project != null && project.settings != null && project.settings.showGrid)
        {
            int grid = Math.max(8, project.settings.gridSize);
            float gx = view.areaX + Math.round((screenX - view.areaX) / grid) * grid;
            float gy = view.areaY + Math.round((screenY - view.areaY) / grid) * grid;
            float distance = CompPathGeometry.distance(screenX, screenY, gx, gy);

            if (distance < best)
            {
                result = new float[] {gx, gy};
            }
        }

        return result;
    }

    /* ------------------------------------------------------------------ target adapters */

    private void activate(CompLayer layer, int kind, int index)
    {
        this.sessionLayer = layer;
        this.targetKind = kind;
        this.targetMask = kind == KIND_MASK ? index : -1;
        this.targetShape = kind == KIND_SHAPE ? index : -1;

        CompPenSession.Target adapter = this.makeTarget(layer, kind, index);

        if (adapter == null)
        {
            return;
        }

        this.targetAdapter = adapter;

        if (this.session.target() != adapter)
        {
            this.session.setTarget(adapter);
        }

        this.session.setTick(this.panel.getFrame());
        this.invalidateEvaluations();
    }

    private CompPenSession.Target makeTarget(CompLayer layer, int kind, int index)
    {
        if (kind == KIND_MASK)
        {
            if (index < 0 || index >= layer.masks.size())
            {
                return null;
            }

            return new MaskTarget(layer, layer.masks.get(index), null);
        }

        if (kind == KIND_SHAPE)
        {
            if (index < 0 || index >= layer.shapes.size())
            {
                return null;
            }

            CompShape shape = layer.shapes.get(index);

            return CompShape.PATH.equals(shape.type) ? new ShapeTarget(layer, shape, null) : null;
        }

        return null;
    }

    /**
     * Creates the destination path for a pen click on empty canvas.
     *
     * <p>AE's rule (P-20): a shape layer gets a shape path, every other pixel-producing layer gets
     * a mask. The path starts <b>open</b> — closing it is an explicit gesture, and an open mask
     * never mattes (see {@code MaskCache.isMatte}), which is exactly what keeps the layer visible
     * while the first few points are being placed.</p>
     */
    private boolean createNewPath(CompLayer layer, View view, UIContext context, float[] local, CompPenSession.Hit hit)
    {
        if (local == null)
        {
            this.panel.setStatus("无法在该位置落点：鼠标射线与图层平面不相交");

            return true;
        }

        boolean shapeLayer = "shape".equals(layer.type);

        this.panel.penBeginAction(shapeLayer ? "钢笔：新建形状路径" : "钢笔：新建蒙版");

        int index;

        if (shapeLayer)
        {
            String id = CompStore.uniqueId("path", layer.shapes, (shape) -> shape.id);
            CompShape shape = new CompShape(id, CompShape.PATH);

            shape.closed = false;
            shape.fillColor = 0xFF55AAFF;
            layer.shapes.add(shape);
            index = layer.shapes.size() - 1;
            this.activate(layer, KIND_SHAPE, index);
        }
        else
        {
            String id = CompStore.uniqueId("mask", layer.masks, (mask) -> mask.id);
            CompMask mask = new CompMask(id);

            mask.name = "Mask " + (layer.masks.size() + 1);
            mask.closed = false;
            layer.masks.add(mask);
            index = layer.masks.size() - 1;
            this.activate(layer, KIND_MASK, index);
        }

        if (this.targetAdapter == null)
        {
            return true;
        }

        this.session.begin(this.targetAdapter);
        this.session.press(local[0], local[1], context.mouseX, context.mouseY,
            CompInput.shiftDown(), CompInput.altDown(), CompPenSession.CLOSE_RADIUS_PX, hit);
        this.structureDirty = true;
        this.dirty = true;
        this.panel.penSave();
        this.panel.penRefreshInspector();
        this.refreshKeyframeSheets();

        return true;
    }

    /* ------------------------------------------------------------------ drag bookkeeping */

    /** Local position of the dragged element when the drag started (for the 45-degree constraint). */
    private float dragOriginLocalX;
    private float dragOriginLocalY;

    private void finishDrag()
    {
        if (!this.session.release())
        {
            return;
        }

        if (this.dirty || this.structureDirty)
        {
            this.panel.penRefreshInspector();
            this.panel.penSave();
        }

        this.dirty = false;
        this.structureDirty = false;
    }

    private void flush()
    {
        if (this.dirty || this.structureDirty)
        {
            this.panel.penRefreshInspector();
        }

        if (this.structureDirty)
        {
            this.refreshKeyframeSheets();
        }

        this.panel.penSave();
        this.dirty = false;
        this.structureDirty = false;
    }

    /**
     * Rebuilds the panel's dope sheet / curve-editor row set after the channel structure changed.
     *
     * <p>{@code UICompositorPanel.refreshSheets()} is private and the pen may not edit that file.
     * The planned one-line package-private bridge {@code penRefreshSheets()} is looked up
     * reflectively so this class compiles now and starts working the moment the bridge lands (see
     * report §12). Without the bridge the channel data is still correct — only the sheet row list
     * refreshes on the next selection change, and a structural edit's new {@code p/<i>} rows stay
     * invisible until then.</p>
     */
    private void refreshKeyframeSheets()
    {
        try
        {
            java.lang.reflect.Method method = UICompositorPanel.class.getDeclaredMethod("penRefreshSheets");

            method.setAccessible(true);
            method.invoke(this.panel);
        }
        catch (NoSuchMethodException e)
        {
            /* Bridge not integrated yet; harmless, documented in report §12. */
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            /* A sheet refresh must never break an edit. */
        }
    }

    /* ------------------------------------------------------------------ listener */

    @Override
    public void beginAction(String name)
    {
        this.panel.penBeginAction(name);
    }

    @Override
    public void changed()
    {
        this.dirty = true;
        this.invalidateEvaluations();
        this.panel.penSave();
    }

    @Override
    public void status(String message)
    {
        this.panel.setStatus(message);
    }

    @Override
    public void closedChanged()
    {
        /* `closed` is not part of the parked-frame / layer-bake fingerprints (those files are owned
         * by other work streams right now), so toggling it has to drop the caches explicitly. This
         * is the documented stop-gap until the field is added to the signatures. */
        this.invalidateEvaluations();
        CompFrameServer.invalidate();
        CompRenderCache.clear();
        CompLayerSourceCache.clear();
        CompPrecache.markDirty();
        this.structureDirty = true;
    }

    @Override
    public void structureChanged()
    {
        /* Point count/order changed: the dope sheet rows are keyed by p/<i> paths, so the host has
         * to rebuild them (deferred to flush() for the common case, immediate for one-shot edits). */
        this.structureDirty = true;
        this.invalidateEvaluations();
        this.refreshKeyframeSheets();
    }

    /* ------------------------------------------------------------------ small helpers */

    private void line(UIContext context, float x1, float y1, float x2, float y2, int color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 0.01F)
        {
            return;
        }

        MatrixStack stack = context.batcher.getContext().getMatrices();

        stack.push();
        stack.translate(x1, y1, 0F);
        stack.multiply(new org.joml.Quaternionf().rotateZ((float) Math.atan2(dy, dx)));
        context.batcher.box(0F, -0.75F, length, 0.75F, color);
        stack.pop();
    }

    /** Four thin boxes around a point: the closest a batcher-only overlay gets to a circle. */
    private void ring(UIContext context, float x, float y, float radius, int color)
    {
        context.batcher.box(x - radius, y - 1F, x + radius, y + 1F, color);
        context.batcher.box(x - 1F, y - radius, x + 1F, y + radius, color);
    }

    private static float targetAdapterX(CompPenSession.Target target, int index)
    {
        return target == null || index < 0 || index >= target.count() ? 0F : target.x(index);
    }

    private static float targetAdapterY(CompPenSession.Target target, int index)
    {
        return target == null || index < 0 || index >= target.count() ? 0F : target.y(index);
    }

    /* ------------------------------------------------------------------ adapters */

    private static final String[] COMPONENT_NAMES = {"x", "y", "inX", "inY", "outX", "outY"};
    private static final float[] EMPTY_TICKS = new float[0];

    /**
     * Scalar-channel adapter for the animated vertex-count remap.
     *
     * <p>The session never touches {@code CompLayer} or the keyframe classes: it asks for key
     * ticks, values at a tick, and moves/clears/writes channel keys. This class is the only place
     * that knows a path point is six {@code p/<i>/(x|y|inX|inY|outX|outY)} scalar channels.</p>
     */
    private abstract class ChannelAnimation implements CompPenSession.Animation
    {
        protected abstract CompLayer channelLayer();

        /** {@code "mask/<id>/p/"} or {@code "shape/<id>/p/"}. */
        protected abstract String channelBase();

        /** The authored (non-animated) value of one component. */
        protected abstract float baseValue(int point, int component);

        private String channelPath(int point, int component)
        {
            return this.channelBase() + point + "/" + COMPONENT_NAMES[component];
        }

        @Override
        public boolean hasKey(int point, int component)
        {
            return this.channelLayer().hasScalarChannel(this.channelPath(point, component));
        }

        @Override
        public float base(int point, int component)
        {
            return this.baseValue(point, component);
        }

        @Override
        public float valueAt(int point, int component, float tick)
        {
            String path = this.channelPath(point, component);

            return this.channelLayer().scalarAt(path, this.baseValue(point, component), tick);
        }

        @Override
        public float[] keyTicks(int point, int component)
        {
            CompLayer layer = this.channelLayer();
            String path = this.channelPath(point, component);

            if (!layer.hasScalarChannel(path))
            {
                return EMPTY_TICKS;
            }

            java.util.List<Keyframe<Float>> keys = layer.scalarChannel(path).getKeyframes();
            float[] ticks = new float[keys.size()];

            for (int i = 0; i < keys.size(); i++)
            {
                ticks[i] = keys.get(i).getTick();
            }

            return ticks;
        }

        @Override
        public void moveChannels(int from, int to)
        {
            for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
            {
                this.moveChannel(this.channelPath(from, component), this.channelPath(to, component));
            }
        }

        @Override
        public void clearChannels(int point)
        {
            CompLayer layer = this.channelLayer();

            for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
            {
                String path = this.channelPath(point, component);

                if (layer.hasScalarChannel(path))
                {
                    layer.scalarChannel(path).removeAll();
                }
            }
        }

        @Override
        public void setKey(int point, int component, float tick, float value)
        {
            /* insertInheriting copies the left neighbour's interpolation/style/handles onto a new
             * key, so a remap does not flatten a hand-shaped curve onto the default interpolation. */
            this.channelLayer().scalarChannel(this.channelPath(point, component)).insertInheriting(tick, value);
        }

        @Override
        public void copyLoops(int fromPoint, int fromComponent, int toPoint, int toComponent)
        {
            CompLayer layer = this.channelLayer();
            String fromPath = this.channelPath(fromPoint, fromComponent);

            if (!layer.hasScalarChannel(fromPath))
            {
                return;
            }

            java.util.List<KeyframeLoop> loops = new java.util.ArrayList<>(layer.scalarChannel(fromPath).getLoops());

            if (loops.isEmpty())
            {
                return;
            }

            KeyframeChannel<Float> destination = layer.scalarChannel(this.channelPath(toPoint, toComponent));

            for (KeyframeLoop loop : loops)
            {
                destination.putLoop(loop);
            }
        }

        /**
         * Rotates the per-point channel groups for Set First Vertex. Every touched channel is
         * snapshotted (keys + repeat loops), cleared, and written back at the rotated index so the
         * geometry of every keyframe is unchanged.
         */
        @Override
        public void rotateChannels(int count, int shift)
        {
            int n = count;
            int rotated = ((shift % n) + n) % n;

            if (n <= 0 || rotated == 0)
            {
                return;
            }

            CompLayer layer = this.channelLayer();

            for (int component = 0; component < CompPenSession.COMPONENT_COUNT; component++)
            {
                java.util.List<java.util.List<Keyframe<Float>>> keys = new java.util.ArrayList<>(n);
                java.util.List<java.util.List<KeyframeLoop>> loops = new java.util.ArrayList<>(n);

                for (int point = 0; point < n; point++)
                {
                    String path = this.channelPath(point, component);

                    if (layer.hasScalarChannel(path))
                    {
                        KeyframeChannel<Float> channel = layer.scalarChannel(path);

                        keys.add(new java.util.ArrayList<>(channel.getKeyframes()));
                        loops.add(new java.util.ArrayList<>(channel.getLoops()));
                    }
                    else
                    {
                        keys.add(new java.util.ArrayList<>());
                        loops.add(new java.util.ArrayList<>());
                    }
                }

                for (int point = 0; point < n; point++)
                {
                    String path = this.channelPath(point, component);

                    if (layer.hasScalarChannel(path))
                    {
                        layer.scalarChannel(path).removeAll();
                    }
                }

                for (int point = 0; point < n; point++)
                {
                    int source = (point + rotated) % n;
                    java.util.List<Keyframe<Float>> sourceKeys = keys.get(source);
                    java.util.List<KeyframeLoop> sourceLoops = loops.get(source);

                    if (sourceKeys.isEmpty() && sourceLoops.isEmpty())
                    {
                        continue;
                    }

                    KeyframeChannel<Float> destination = layer.scalarChannel(this.channelPath(point, component));

                    for (Keyframe<Float> key : sourceKeys)
                    {
                        int index = destination.insert(key.getTick(), key.getValue());
                        Keyframe<Float> copy = destination.get(index);

                        if (copy != null && copy != key)
                        {
                            copy.copyOverExtra(key);
                        }
                    }

                    for (KeyframeLoop loop : sourceLoops)
                    {
                        destination.putLoop(loop);
                    }
                }
            }
        }

        /**
         * Moves one channel, preserving ticks, values, interpolation, handles, style, duration
         * <b>and its repeat loops</b>. Used for index shifts during a vertex-count change; the
         * destination is cleared first so a half-written channel can never mix two vertices' keys.
         */
        private void moveChannel(String fromPath, String toPath)
        {
            CompLayer layer = this.channelLayer();

            if (!layer.hasScalarChannel(fromPath))
            {
                if (layer.hasScalarChannel(toPath))
                {
                    layer.scalarChannel(toPath).removeAll();
                }

                return;
            }

            KeyframeChannel<Float> source = layer.scalarChannel(fromPath);
            java.util.List<Keyframe<Float>> keys = new java.util.ArrayList<>(source.getKeyframes());
            /* Loops are absolute-time repeat blocks; they belong to the channel and have to follow
             * it. removeAll() below clears the destination's loops, so snapshot them first. */
            java.util.List<KeyframeLoop> loops = new java.util.ArrayList<>(source.getLoops());
            KeyframeChannel<Float> destination = layer.scalarChannel(toPath);

            destination.removeAll();

            for (Keyframe<Float> key : keys)
            {
                int index = destination.insert(key.getTick(), key.getValue());
                Keyframe<Float> copy = destination.get(index);

                if (copy != null && copy != key)
                {
                    copy.copyOverExtra(key);
                }
            }

            for (KeyframeLoop loop : loops)
            {
                destination.putLoop(loop);
            }

            source.removeAll();
        }
    }

    /** Pen adapter over a mask: reads base values, writes through the animation-aware scalar path. */
    private final class MaskTarget implements CompPenSession.Target
    {
        private final CompLayer layer;
        private final CompMask mask;
        final java.util.Set<Integer> handles;
        private CompPenSession.Animation animation;

        MaskTarget(CompLayer layer, CompMask mask, java.util.Set<Integer> handles)
        {
            this.layer = layer;
            this.mask = mask;
            this.handles = handles;
        }

        MaskTarget(CompLayer layer, CompMask mask)
        {
            this(layer, mask, null);
        }

        @Override
        public int count()
        {
            return this.mask.points.size();
        }

        @Override
        public float x(int index) { return evaluated(index, CompPenSession.COMPONENT_X); }

        @Override
        public float y(int index) { return evaluated(index, CompPenSession.COMPONENT_Y); }

        @Override
        public float inX(int index) { return evaluated(index, CompPenSession.COMPONENT_IN_X); }

        @Override
        public float inY(int index) { return evaluated(index, CompPenSession.COMPONENT_IN_Y); }

        @Override
        public float outX(int index) { return evaluated(index, CompPenSession.COMPONENT_OUT_X); }

        @Override
        public float outY(int index) { return evaluated(index, CompPenSession.COMPONENT_OUT_Y); }

        /**
         * Value of one component at the current playhead, through the shared frame cache.
         *
         * <p>The overlay, the hit test and the drag snapshots all have to see the path <b>as
         * rendered</b>: for an animated path the authored base values are the state at t=0, not
         * what is on screen now. {@link EvaluationCache} resolves all six components of every point
         * once per (layer, path, frame, revision), so a frame that draws and hit-tests the same
         * path does not re-interpolate its channels dozens of times.</p>
         */
        private float evaluated(int index, int component)
        {
            int count = this.mask.points.size();

            if (count == 0 || index < 0 || index >= count)
            {
                return 0F;
            }

            float[] values = this.values();
            int at = index * CompPenSession.COMPONENT_COUNT + component;

            return at < values.length ? values[at] : 0F;
        }

        private float[] values()
        {
            int count = this.mask.points.size();
            int length = count * CompPenSession.COMPONENT_COUNT;
            float frame = UIPenTool.this.panel.getFrame();
            float[] cached = UIPenTool.this.evaluations.fetch(
                this.layer, this.mask, frame, UIPenTool.this.evaluationRevision, length);

            if (cached != null)
            {
                return cached;
            }

            float[] values = new float[length];

            for (int i = 0; i < count; i++)
            {
                CompMask.CompMaskPoint point = this.mask.points.get(i);
                String base = "mask/" + this.mask.id + "/p/" + i + "/";

                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_X] =
                    this.layer.scalarAt(base + "x", point.x, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_Y] =
                    this.layer.scalarAt(base + "y", point.y, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_IN_X] =
                    this.layer.scalarAt(base + "inX", point.inX, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_IN_Y] =
                    this.layer.scalarAt(base + "inY", point.inY, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_OUT_X] =
                    this.layer.scalarAt(base + "outX", point.outX, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_OUT_Y] =
                    this.layer.scalarAt(base + "outY", point.outY, frame);
            }

            UIPenTool.this.evaluations.store(this.layer, this.mask, frame, UIPenTool.this.evaluationRevision, values);

            return values;
        }

        private CompMask.CompMaskPoint point(int index)
        {
            return this.mask.points.get(Math.max(0, Math.min(this.mask.points.size() - 1, index)));
        }

        @Override
        public boolean rotatePoints(int shift)
        {
            java.util.Collections.rotate(this.mask.points, shift);

            return true;
        }

        @Override
        public boolean canRotatePoints()
        {
            return true;
        }

        @Override
        public boolean rotoBezier()
        {
            return this.mask.rotoBezier;
        }

        @Override
        public void setRotoBezier(boolean rotoBezier)
        {
            this.mask.rotoBezier = rotoBezier;
        }

        @Override
        public boolean closed()
        {
            return this.mask.closed;
        }

        @Override
        public void setPoint(int index, float x, float y)
        {
            String base = "mask/" + this.mask.id + "/p/" + index + "/";

            UIPenTool.this.panel.penSetScalar(this.layer, base + "x", x);
            UIPenTool.this.panel.penSetScalar(this.layer, base + "y", y);
        }

        @Override
        public void setHandle(int index, boolean out, float dx, float dy)
        {
            String base = "mask/" + this.mask.id + "/p/" + index + "/";

            UIPenTool.this.panel.penSetScalar(this.layer, base + (out ? "outX" : "inX"), dx);
            UIPenTool.this.panel.penSetScalar(this.layer, base + (out ? "outY" : "inY"), dy);
        }

        @Override
        public void insert(int index, float x, float y, float inX, float inY, float outX, float outY)
        {
            int at = Math.max(0, Math.min(this.mask.points.size(), index));

            this.mask.points.add(at, new CompMask.CompMaskPoint(x, y, inX, inY, outX, outY));
        }

        @Override
        public void remove(int index)
        {
            if (index >= 0 && index < this.mask.points.size())
            {
                this.mask.points.remove(index);
            }
        }

        @Override
        public void setClosed(boolean closed)
        {
            this.mask.closed = closed;
        }

        @Override
        public boolean animated()
        {
            for (int i = 0; i < this.mask.points.size(); i++)
            {
                String base = "mask/" + this.mask.id + "/p/" + i + "/";

                for (String channel : new String[] {"x", "y", "inX", "inY", "outX", "outY"})
                {
                    if (this.layer.hasScalarChannel(base + channel))
                    {
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public String label()
        {
            return "蒙版 " + (this.mask.name == null || this.mask.name.isEmpty() ? this.mask.id : this.mask.name);
        }

        @Override
        public CompPenSession.Animation animation()
        {
            if (this.animation == null)
            {
                this.animation = new ChannelAnimation()
                {
                    @Override
                    protected CompLayer channelLayer()
                    {
                        return MaskTarget.this.layer;
                    }

                    @Override
                    protected String channelBase()
                    {
                        return "mask/" + MaskTarget.this.mask.id + "/p/";
                    }

                    @Override
                    protected float baseValue(int point, int component)
                    {
                        CompMask.CompMaskPoint value = MaskTarget.this.point(point);

                        return switch (component)
                        {
                            case CompPenSession.COMPONENT_X -> value.x;
                            case CompPenSession.COMPONENT_Y -> value.y;
                            case CompPenSession.COMPONENT_IN_X -> value.inX;
                            case CompPenSession.COMPONENT_IN_Y -> value.inY;
                            case CompPenSession.COMPONENT_OUT_X -> value.outX;
                            default -> value.outY;
                        };
                    }
                };
            }

            return this.animation;
        }
    }

    /** Pen adapter over a shape path. */
    private final class ShapeTarget implements CompPenSession.Target
    {
        private final CompLayer layer;
        private final CompShape shape;
        final java.util.Set<Integer> handles;
        private CompPenSession.Animation animation;

        ShapeTarget(CompLayer layer, CompShape shape, java.util.Set<Integer> handles)
        {
            this.layer = layer;
            this.shape = shape;
            this.handles = handles;
        }

        ShapeTarget(CompLayer layer, CompShape shape)
        {
            this(layer, shape, null);
        }

        @Override
        public int count()
        {
            return this.shape.points.size();
        }

        @Override
        public float x(int index) { return evaluated(index, CompPenSession.COMPONENT_X); }

        @Override
        public float y(int index) { return evaluated(index, CompPenSession.COMPONENT_Y); }

        @Override
        public float inX(int index) { return evaluated(index, CompPenSession.COMPONENT_IN_X); }

        @Override
        public float inY(int index) { return evaluated(index, CompPenSession.COMPONENT_IN_Y); }

        @Override
        public float outX(int index) { return evaluated(index, CompPenSession.COMPONENT_OUT_X); }

        @Override
        public float outY(int index) { return evaluated(index, CompPenSession.COMPONENT_OUT_Y); }

        /** See {@code MaskTarget#evaluated}: show/hit/drag the path as rendered at the playhead. */
        private float evaluated(int index, int component)
        {
            int count = this.shape.points.size();

            if (count == 0 || index < 0 || index >= count)
            {
                return 0F;
            }

            float[] values = this.values();
            int at = index * CompPenSession.COMPONENT_COUNT + component;

            return at < values.length ? values[at] : 0F;
        }

        private float[] values()
        {
            int count = this.shape.points.size();
            int length = count * CompPenSession.COMPONENT_COUNT;
            float frame = UIPenTool.this.panel.getFrame();
            float[] cached = UIPenTool.this.evaluations.fetch(
                this.layer, this.shape, frame, UIPenTool.this.evaluationRevision, length);

            if (cached != null)
            {
                return cached;
            }

            float[] values = new float[length];

            for (int i = 0; i < count; i++)
            {
                CompShape.Point point = this.shape.points.get(i);
                String base = "shape/" + this.shape.id + "/p/" + i + "/";

                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_X] =
                    this.layer.scalarAt(base + "x", point.x, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_Y] =
                    this.layer.scalarAt(base + "y", point.y, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_IN_X] =
                    this.layer.scalarAt(base + "inX", point.inX, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_IN_Y] =
                    this.layer.scalarAt(base + "inY", point.inY, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_OUT_X] =
                    this.layer.scalarAt(base + "outX", point.outX, frame);
                values[i * CompPenSession.COMPONENT_COUNT + CompPenSession.COMPONENT_OUT_Y] =
                    this.layer.scalarAt(base + "outY", point.outY, frame);
            }

            UIPenTool.this.evaluations.store(this.layer, this.shape, frame, UIPenTool.this.evaluationRevision, values);

            return values;
        }

        private CompShape.Point point(int index)
        {
            return this.shape.points.get(Math.max(0, Math.min(this.shape.points.size() - 1, index)));
        }

        @Override
        public boolean rotatePoints(int shift)
        {
            java.util.Collections.rotate(this.shape.points, shift);
            this.shape.ensureCorners();

            return true;
        }

        @Override
        public boolean canRotatePoints()
        {
            return true;
        }

        @Override
        public boolean rotoBezier()
        {
            return this.shape.rotoBezier;
        }

        @Override
        public void setRotoBezier(boolean rotoBezier)
        {
            this.shape.rotoBezier = rotoBezier;
        }

        @Override
        public boolean closed()
        {
            return this.shape.closed;
        }

        @Override
        public void setPoint(int index, float x, float y)
        {
            String base = "shape/" + this.shape.id + "/p/" + index + "/";

            UIPenTool.this.panel.penSetScalar(this.layer, base + "x", x);
            UIPenTool.this.panel.penSetScalar(this.layer, base + "y", y);
        }

        @Override
        public void setHandle(int index, boolean out, float dx, float dy)
        {
            String base = "shape/" + this.shape.id + "/p/" + index + "/";

            UIPenTool.this.panel.penSetScalar(this.layer, base + (out ? "outX" : "inX"), dx);
            UIPenTool.this.panel.penSetScalar(this.layer, base + (out ? "outY" : "inY"), dy);
        }

        @Override
        public void insert(int index, float x, float y, float inX, float inY, float outX, float outY)
        {
            int at = Math.max(0, Math.min(this.shape.points.size(), index));

            this.shape.points.add(at, new CompShape.Point(x, y, inX, inY, outX, outY));
            this.shape.ensureCorners();
        }

        @Override
        public void remove(int index)
        {
            if (index >= 0 && index < this.shape.points.size())
            {
                this.shape.points.remove(index);
                this.shape.ensureCorners();
            }
        }

        @Override
        public void setClosed(boolean closed)
        {
            this.shape.closed = closed;
        }

        @Override
        public boolean animated()
        {
            for (int i = 0; i < this.shape.points.size(); i++)
            {
                String base = "shape/" + this.shape.id + "/p/" + i + "/";

                for (String channel : new String[] {"x", "y", "inX", "inY", "outX", "outY"})
                {
                    if (this.layer.hasScalarChannel(base + channel))
                    {
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public String label()
        {
            return "路径 " + this.shape.id;
        }

        @Override
        public CompPenSession.Animation animation()
        {
            if (this.animation == null)
            {
                this.animation = new ChannelAnimation()
                {
                    @Override
                    protected CompLayer channelLayer()
                    {
                        return ShapeTarget.this.layer;
                    }

                    @Override
                    protected String channelBase()
                    {
                        return "shape/" + ShapeTarget.this.shape.id + "/p/";
                    }

                    @Override
                    protected float baseValue(int point, int component)
                    {
                        CompShape.Point value = ShapeTarget.this.point(point);

                        return switch (component)
                        {
                            case CompPenSession.COMPONENT_X -> value.x;
                            case CompPenSession.COMPONENT_Y -> value.y;
                            case CompPenSession.COMPONENT_IN_X -> value.inX;
                            case CompPenSession.COMPONENT_IN_Y -> value.inY;
                            case CompPenSession.COMPONENT_OUT_X -> value.outX;
                            default -> value.outY;
                        };
                    }
                };
            }

            return this.animation;
        }
    }
}
