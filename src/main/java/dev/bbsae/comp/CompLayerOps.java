package dev.bbsae.comp;

import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Batch operations on a multi-layer selection.
 *
 * <p>First principle: a selection is a set of layers, and every batch operation is the same
 * operation applied to each member with one shared reference frame. Painting them as separate
 * features (delete, duplicate, pre-compose, group, align) would spread the same bookkeeping —
 * ordering, parenting, mattes, time rebasing — over five places, and each one would get one of
 * those details wrong. They live here instead.</p>
 */
public final class CompLayerOps
{
    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER_H = 1;
    public static final int ALIGN_RIGHT = 2;
    public static final int ALIGN_TOP = 3;
    public static final int ALIGN_CENTER_V = 4;
    public static final int ALIGN_BOTTOM = 5;

    private CompLayerOps()
    {}

    /**
     * Visual bounds of the layer's content in its <b>own</b> space, relative to the layer position
     * (i.e. before the layer's own translate/rotate/scale). {left, top, right, bottom}.
     */
    public static float[] localBounds(CompLayer layer)
    {
        if ("shape".equals(layer.type) && layer.shapes != null && !layer.shapes.isEmpty())
        {
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (CompShape shape : layer.shapes)
            {
                if (shape == null || !shape.enabled)
                {
                    continue;
                }

                /* Shape x/y is its centre; rotation is ignored (a rotated bounding box would only
                 * make alignment feel unpredictable). */
                float halfW = Math.max(1F, shape.width) * 0.5F;
                float halfH = Math.max(1F, shape.height) * 0.5F;

                minX = Math.min(minX, shape.x - halfW);
                maxX = Math.max(maxX, shape.x + halfW);
                minY = Math.min(minY, shape.y - halfH);
                maxY = Math.max(maxY, shape.y + halfH);
            }

            if (minX < Float.MAX_VALUE)
            {
                return new float[] {minX, minY, maxX, maxY};
            }
        }

        float w = Math.max(0F, layer.width);
        float h = Math.max(0F, layer.height);
        float left = -w * layer.anchorX;
        float top = -h * layer.anchorY;

        return new float[] {left, top, left + w, top + h};
    }

    /**
     * Visual bounds in <b>composition space</b>, after the layer's own transform and its whole
     * parent chain. This is what alignment must use: parent-space bounds would put a child of a
     * moved group in the wrong place.
     */
    public static float[] bounds(CompDocument doc, CompLayer layer)
    {
        return bounds(doc, layer, 0F);
    }

    /**
     * Composition-space bounds at a specific frame.
     *
     * <p>The original overload sampled the parent chain at frame 0. For snapping that is wrong the
     * moment a parent is animated: the layer is visibly somewhere else, but the guide would target
     * its old frame-0 rectangle. Alignment commands historically used frame 0 on purpose; they keep
     * the old overload.</p>
     */
    public static float[] bounds(CompDocument doc, CompLayer layer, float frame)
    {
        float[] local = localBounds(layer);

        if (doc == null)
        {
            return new float[] {
                layer.x + local[0], layer.y + local[1], layer.x + local[2], layer.y + local[3]
            };
        }

        org.joml.Matrix4f world = CompParenting.worldMatrix(doc, layer, frame);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int i = 0; i < 4; i++)
        {
            org.joml.Vector3f point = new org.joml.Vector3f(
                i == 1 || i == 2 ? local[2] : local[0],
                i >= 2 ? local[3] : local[1],
                0F
            );

            world.transformPosition(point);

            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }

        return new float[] {minX, minY, maxX, maxY};
    }

    public static float centerX(CompDocument doc, CompLayer layer)
    {
        float[] bounds = bounds(doc, layer);

        return (bounds[0] + bounds[2]) * 0.5F;
    }

    public static float centerY(CompDocument doc, CompLayer layer)
    {
        float[] bounds = bounds(doc, layer);

        return (bounds[1] + bounds[3]) * 0.5F;
    }

    /**
     * Aligns every selected layer to an edge or centre of the composition. Translation only — the
     * operation must never change a layer's size, rotation or animation curve.
     */
    public static void align(CompDocument doc, List<CompLayer> layers, int edge)
    {
        if (doc == null || layers == null || layers.isEmpty())
        {
            return;
        }

        for (CompLayer layer : layers)
        {
            if (layer == null || layer.locked)
            {
                continue;
            }

            float[] bounds = bounds(doc, layer);
            float dx = 0F;
            float dy = 0F;

            switch (edge)
            {
                case ALIGN_LEFT -> dx = -bounds[0];
                case ALIGN_CENTER_H -> dx = doc.width * 0.5F - (bounds[0] + bounds[2]) * 0.5F;
                case ALIGN_RIGHT -> dx = doc.width - bounds[2];
                case ALIGN_TOP -> dy = -bounds[1];
                case ALIGN_CENTER_V -> dy = doc.height * 0.5F - (bounds[1] + bounds[3]) * 0.5F;
                case ALIGN_BOTTOM -> dy = doc.height - bounds[3];
                default -> {}
            }

            translate(doc, layer, dx, dy);
        }
    }

    /** Even spacing between the first and last selected layer, by centre. */
    public static void distribute(CompDocument doc, List<CompLayer> layers, boolean horizontal)
    {
        if (doc == null || layers == null || layers.size() < 3)
        {
            return;
        }

        List<CompLayer> sorted = new ArrayList<>(layers);

        sorted.removeIf((layer) -> layer == null || layer.locked);
        sorted.sort(Comparator.comparingDouble((layer) -> horizontal ? centerX(doc, layer) : centerY(doc, layer)));

        if (sorted.size() < 3)
        {
            return;
        }

        float first = horizontal ? centerX(doc, sorted.get(0)) : centerY(doc, sorted.get(0));
        float last = horizontal ? centerX(doc, sorted.get(sorted.size() - 1)) : centerY(doc, sorted.get(sorted.size() - 1));
        float step = (last - first) / (sorted.size() - 1);

        for (int i = 1; i < sorted.size() - 1; i++)
        {
            CompLayer layer = sorted.get(i);
            float target = first + step * i;
            float current = horizontal ? centerX(doc, layer) : centerY(doc, layer);
            float delta = target - current;

            translate(doc, layer, horizontal ? delta : 0F, horizontal ? 0F : delta);
        }
    }

    /** Moves a layer by a comp-space delta, converted into its parent's space. */
    public static void translate(CompDocument doc, CompLayer layer, float dx, float dy)
    {
        if (layer == null || (Math.abs(dx) < 0.0001F && Math.abs(dy) < 0.0001F))
        {
            return;
        }

        float localX = dx;
        float localY = dy;

        if (doc != null && layer.parentId != null && !layer.parentId.isEmpty())
        {
            Vector2f local = CompParenting.toLocalDelta(doc, layer, 0F, dx, dy);

            localX = local.x;
            localY = local.y;
        }

        CompParenting.applyDelta(layer, localX, localY, 0F, 0F, 0F, 0F, 1F, 1F, 1F);
    }

    /**
     * Pre-composes the selection into one nested composition.
     *
     * <p>AE's "Pre-compose" semantics, done explicitly: the new comp keeps the parent's size and
     * frame rate; the selected layers move in with their in-points rebased so the earliest one
     * starts at 0; parenting and track mattes are preserved <b>inside</b> the group and dropped
     * when they pointed outside it; one precomp layer replaces the group at the topmost selected
     * slot and takes over any external parent/matte references.</p>
     */
    public static CompLayer precompose(CompProject project, CompDocument doc, List<CompLayer> selection, String baseName)
    {
        if (project == null || doc == null || selection == null || selection.isEmpty())
        {
            return null;
        }

        List<CompLayer> ordered = new ArrayList<>();

        for (CompLayer layer : doc.layers)
        {
            if (selection.contains(layer) && !"camera".equals(layer.type))
            {
                ordered.add(layer);
            }
        }

        if (ordered.isEmpty())
        {
            return null;
        }

        float fps = Math.max(1F, doc.fps);
        float start = Float.MAX_VALUE;
        float end = -Float.MAX_VALUE;

        for (CompLayer layer : ordered)
        {
            start = Math.min(start, layer.in);
            end = Math.max(end, layer.out);
        }

        if (end <= start)
        {
            end = start + 1F / fps;
        }

        int index = doc.layers.indexOf(ordered.get(ordered.size() - 1));

        /* How many selected layers sit BELOW the topmost one. Removing them shifts the insertion
         * point they leave behind, and using the pre-removal index directly put the new precomp
         * one slot too high: [A,B,C,D] with {A,B} became [C,precomp,D] instead of [precomp,C,D],
         * a silent z-order change. */
        int removedBelow = 0;

        for (CompLayer layer : ordered)
        {
            int at = doc.layers.indexOf(layer);

            if (at >= 0 && at < index)
            {
                removedBelow++;
            }
        }

        List<String> inside = new ArrayList<>();

        for (CompLayer layer : ordered)
        {
            inside.add(layer.id);
        }

        List<CompLayer> copies = new ArrayList<>();

        /* Duplicate first, mutate the parent only after every copy succeeded. The old loop
         * interleaved duplicate() and doc.layers.remove(); one malformed layer in the middle
         * therefore left the already-processed layers deleted while the precomp was incomplete —
         * exactly the "precompose made my graphics disappear" failure mode. */
        for (CompLayer layer : ordered)
        {
            CompLayer copy = layer.duplicate();

            copy.in = Math.max(0F, layer.in - start);
            copy.out = Math.max(copy.in + 1F / fps, layer.out - start);
            copy.shiftKeyframes(-start * fps);
            copy.shiftMarkers(-start);

            if (copy.parentId != null && !copy.parentId.isEmpty() && !inside.contains(copy.parentId))
            {
                copy.parentId = "";
            }

            if (copy.trackMatteId != null && !copy.trackMatteId.isEmpty() && !inside.contains(copy.trackMatteId))
            {
                copy.trackMatteId = "";
                copy.trackMatteEnabled = false;
            }

            copies.add(copy);
        }

        int serial = project.comps.size() + 1;
        String name = (baseName == null || baseName.isBlank() ? "预合成" : baseName) + " " + serial;
        CompDocument sub = project.addComp("precomp_" + serial, doc.width, doc.height, doc.fps, doc.duration);

        sub.name = name;
        sub.duration = doc.duration;
        sub.workAreaOut = doc.duration;
        sub.layers.addAll(copies);

        for (CompLayer layer : ordered)
        {
            doc.layers.remove(layer);
        }

        inheritActiveCamera(doc, sub, start, fps);

        CompLayer precomp = new CompLayer("precomp", name, "precomp");

        precomp.id = CompStore.uniqueId("precomp", doc.layers, (candidate) -> candidate.id);
        precomp.precompId = sub.id;
        precomp.x = doc.width / 2F;
        precomp.y = doc.height / 2F;
        precomp.width = doc.width;
        precomp.height = doc.height;
        precomp.anchorX = 0.5F;
        precomp.anchorY = 0.5F;
        precomp.in = start;
        precomp.out = end;
        precomp.startTime = 0F;

        /* The group must keep the parent/matte context of the layer it replaces. Losing a parent
         * moves the whole nested composition (often off-screen), which reads as "precompose made
         * my graphics disappear"; losing a matte silently changes the alpha. Only transfer a
         * relation shared by every selected layer — a mixed set has no single correct parent. */
        String sharedParent = ordered.get(0).parentId;
        String sharedMatte = ordered.get(0).trackMatteId;

        for (CompLayer layer : ordered)
        {
            if (sharedParent == null ? layer.parentId != null && !layer.parentId.isEmpty()
                : !sharedParent.equals(layer.parentId))
            {
                sharedParent = null;
            }

            if (sharedMatte == null ? layer.trackMatteId != null && !layer.trackMatteId.isEmpty()
                : !sharedMatte.equals(layer.trackMatteId))
            {
                sharedMatte = null;
            }
        }

        precomp.parentId = sharedParent != null && !sharedParent.isEmpty() && !inside.contains(sharedParent)
            ? sharedParent : "";
        precomp.trackMatteId = sharedMatte != null && !sharedMatte.isEmpty() && !inside.contains(sharedMatte)
            ? sharedMatte : "";
        precomp.trackMatteEnabled = !precomp.trackMatteId.isEmpty() && ordered.get(0).trackMatteEnabled;
        precomp.trackMatteMode = ordered.get(0).trackMatteMode;

        doc.layers.add(Math.max(0, Math.min(index - removedBelow, doc.layers.size())), precomp);

        /* Everything that pointed at one of the moved layers now points at the precomp, otherwise
         * the parent/matte relationship would silently vanish. */
        for (CompLayer candidate : doc.layers)
        {
            if (candidate == precomp)
            {
                continue;
            }

            if (candidate.parentId != null && inside.contains(candidate.parentId))
            {
                candidate.parentId = precomp.id;
            }

            if (candidate.trackMatteId != null && inside.contains(candidate.trackMatteId))
            {
                candidate.trackMatteId = precomp.id;
            }
        }

        return precomp;
    }

    /**
     * A precomp has its own camera namespace. Dropping the parent's active camera made a nested
     * 3D scene fall back to the default 1:1 view: x/y/z survived, but the projection changed the
     * moment the layers entered the precomp. Copy the active camera and its animation, rebased by
     * the same start time as the layers.
     */
    public static void inheritActiveCamera(CompDocument parent, CompDocument sub, float start, float fps)
    {
        if (parent == null || sub == null || sub.activeCameraId != null && !sub.activeCameraId.isEmpty())
        {
            return;
        }

        CompLayer sourceCamera = parent.activeCamera(start);

        if (sourceCamera == null)
        {
            /* A camera trimmed to start later than the selected layers is still the comp's 3D
             * view; copy it so the child evaluates its own in/out instead of losing perspective. */
            sourceCamera = parent.activeCamera(0F);
        }

        if (sourceCamera == null)
        {
            return;
        }

        CompLayer camera = sourceCamera.duplicate();

        camera.id = CompStore.uniqueId("camera", sub.layers, (candidate) -> candidate.id);
        camera.name = sourceCamera.name;
        camera.in = Math.max(0F, sourceCamera.in - start);
        camera.out = Math.max(camera.in + 1F / Math.max(1F, fps), sourceCamera.out - start);
        camera.parentId = "";
        camera.trackMatteId = "";
        camera.trackMatteEnabled = false;
        camera.shiftKeyframes(-start * fps);
        camera.shiftMarkers(-start);
        sub.layers.add(camera);
        sub.activeCameraId = camera.id;
    }

    /**
     * Parents the selection to a fresh null at the centre of its combined bounds — AE's "group into
     * a null". Positions are preserved by {@link CompParenting#bind}.
     */
    public static CompLayer groupUnderNull(CompDocument doc, List<CompLayer> selection)
    {
        if (doc == null || selection == null || selection.isEmpty())
        {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (CompLayer layer : selection)
        {
            if (layer == null || "camera".equals(layer.type))
            {
                continue;
            }

            float[] bounds = bounds(doc, layer);

            minX = Math.min(minX, bounds[0]);
            minY = Math.min(minY, bounds[1]);
            maxX = Math.max(maxX, bounds[2]);
            maxY = Math.max(maxY, bounds[3]);
        }

        if (minX == Float.MAX_VALUE)
        {
            return null;
        }

        CompLayer nullLayer = new CompLayer("group", "空对象", "null");

        nullLayer.id = CompStore.uniqueId("group", doc.layers, (candidate) -> candidate.id);
        nullLayer.x = (minX + maxX) * 0.5F;
        nullLayer.y = (minY + maxY) * 0.5F;
        nullLayer.width = 120F;
        nullLayer.height = 120F;

        int index = doc.layers.size();

        for (CompLayer layer : selection)
        {
            int layerIndex = doc.layers.indexOf(layer);

            if (layerIndex >= 0)
            {
                index = Math.min(index, layerIndex);
            }
        }

        doc.layers.add(Math.max(0, Math.min(index, doc.layers.size())), nullLayer);

        for (CompLayer layer : selection)
        {
            if (layer != null && !"camera".equals(layer.type) && layer != nullLayer)
            {
                CompParenting.bind(doc, layer, nullLayer.id, 0F);
            }
        }

        return nullLayer;
    }

    /** Duplicates the selection in place; the copies become the new selection. */
    public static List<CompLayer> duplicate(CompDocument doc, List<CompLayer> selection)
    {
        List<CompLayer> copies = new ArrayList<>();

        if (doc == null || selection == null)
        {
            return copies;
        }

        java.util.Map<String, String> remap = new java.util.LinkedHashMap<>();

        for (CompLayer layer : new ArrayList<>(selection))
        {
            int index = doc.layers.indexOf(layer);

            if (index < 0)
            {
                continue;
            }

            CompLayer copy = layer.duplicate();

            copy.id = CompStore.uniqueId(layer.id + "_copy", doc.layers, (candidate) -> candidate.id);
            copy.name = layer.name + " 副本";
            offsetCopy(copy, 24F, 24F);
            doc.layers.add(Math.min(doc.layers.size(), index + 1), copy);
            copies.add(copy);
            remap.put(layer.id, copy.id);
        }

        /* References between duplicated layers must point inside the copy, not at the originals.
         * Without the remap a duplicated null + its children still drove the ORIGINAL null, so the
         * copy looked like a still image ("duplicating a group breaks the copy"). */
        for (CompLayer copy : copies)
        {
            if (copy.parentId != null && remap.containsKey(copy.parentId))
            {
                copy.parentId = remap.get(copy.parentId);
            }

            if (copy.trackMatteId != null && remap.containsKey(copy.trackMatteId))
            {
                copy.trackMatteId = remap.get(copy.trackMatteId);
            }
        }

        return copies;
    }

    /**
     * Moves a fresh copy so it is visibly not the original.
     *
     * <p>{@code x/y} alone are not enough: once a layer has transform keyframes, {@code transformAt}
     * evaluates the axis channels and ignores the base fields, so the "+24 px" offset was invisible
     * and Ctrl+D looked like it had done nothing. The keyed values are shifted by the same delta,
     * which translates the whole copied animation.</p>
     */
    public static void offsetCopy(CompLayer copy, float dx, float dy)
    {
        if (copy == null)
        {
            return;
        }

        copy.x += dx;
        copy.y += dy;

        /* Legacy shared Transform keys have to become independent axes before they can move. */
        if (copy.transformData != null && !copy.transformData.isEmpty())
        {
            copy.ensureIndependentAxes();
        }

        shiftAxis(copy, CompCurves.AXIS_X, dx);
        shiftAxis(copy, CompCurves.AXIS_Y, dy);
    }

    private static void shiftAxis(CompLayer layer, int axis, float delta)
    {
        if (delta == 0F)
        {
            return;
        }

        String path = CompCurves.axisPath(axis);

        /* hasScalarChannel does not create the channel, so an un-animated copy does not end up with
         * empty x/y channels that later look like keyframed properties. */
        if (!layer.hasScalarChannel(path))
        {
            return;
        }

        for (Object entry : layer.axisChannel(axis).getKeyframes())
        {
            @SuppressWarnings("unchecked")
            mchorse.bbs_mod.utils.keyframes.Keyframe<Float> keyframe =
                (mchorse.bbs_mod.utils.keyframes.Keyframe<Float>) entry;

            keyframe.setValue(keyframe.getValue() + delta);
        }
    }
}
