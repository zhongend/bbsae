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

        /* A group of 3D layers must remain a 3D layer in the parent. Leaving it at the default
         * twoD made the precomp screen-fixed in the free 3D workspace: it did not follow the
         * camera, which read as the pre-rendered layer being wrong. The nested content is still
         * flattened (Collapse Transformations is a separate opt-in), but the resulting plane now
         * belongs to the same 3D space as the layers it replaced. */
        for (CompLayer layer : ordered)
        {
            if (layer.threeD)
            {
                precomp.threeD = true;
                break;
            }
        }

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
        return duplicate(null, doc, selection);
    }

    /**
     * Duplicates the selection and gives every copied precomp layer its own nested composition
     * tree. Without the project argument a precomp copy would keep the original {@code precompId}
     * and edits to either copy would show up in both.
     */
    public static List<CompLayer> duplicate(CompProject project, CompDocument doc, List<CompLayer> selection)
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

            CompLayer copy = duplicateLayer(project, layer);

            copy.id = CompStore.uniqueId(layer.id + "_copy", doc.layers, (candidate) -> candidate.id);
            copy.name = layer.name + " 副本";
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

        doc.extendDurationToContent();

        return copies;
    }

    /** One layer duplicate, including a private copy of its precomp tree. */
    public static CompLayer duplicateLayer(CompProject project, CompLayer source)
    {
        if (source == null)
        {
            return null;
        }

        CompLayer copy = source.duplicate();

        if ("precomp".equals(source.type) && source.precompId != null && !source.precompId.isEmpty())
        {
            if (project != null)
            {
                copy.precompId = clonePrecompTree(project, source.precompId,
                    new java.util.HashMap<>(), new java.util.HashSet<>());

                /* One log line per copied precomp: when a user reports a shared copy, the log
                 * says which target the copy got. Cheap, and the alternative is guessing. */
                dev.bbsae.BbsAeAddon.LOGGER.info("precomp duplicate: layer={} target {} -> {}",
                    source.id, source.precompId,
                    copy.precompId.isEmpty() ? "<copy failed>" : copy.precompId);
            }
            else
            {
                /* Legacy API without a project: this is the only case where a copy can still share
                 * its target. The UI never takes this path; say so loudly if something does. */
                dev.bbsae.BbsAeAddon.LOGGER.warn(
                    "precomp duplicate without project context: layer={} keeps shared target {}",
                    source.id, source.precompId);
            }
        }

        return copy;
    }

    /**
     * Clones a composition and every precomp it references. Returns the new composition id, or the
     * old id when the target is missing (a dangling precomp should stay visibly dangling, not
     * silently become a different composition).
     */
    private static String clonePrecompTree(CompProject project, String sourceCompId,
                                           java.util.Map<String, String> done,
                                           java.util.Set<String> visiting)
    {
        String existing = done.get(sourceCompId);

        if (existing != null)
        {
            return existing;
        }

        CompDocument source = project.getComp(sourceCompId);

        if (source == null)
        {
            return sourceCompId;
        }

        if (!visiting.add(sourceCompId))
        {
            /* A cycle cannot be cloned safely; leaving the clone transparent is better than
             * pointing it at the original tree and re-sharing it. */
            return "";
        }

        CompDocument clone = CompStore.duplicateDocument(source);

        if (clone == null)
        {
            visiting.remove(sourceCompId);

            /* Refusing to copy is honest; silently reusing the source would recreate the shared
             * precomp this whole path exists to prevent. An empty target renders the precomp
             * placeholder, which tells the user something went wrong. */
            return "";
        }

        if (project.comps == null)
        {
            project.comps = new ArrayList<>();
        }

        clone.id = CompStore.uniqueId(source.id + "_copy", project.comps, (comp) -> comp == null ? "" : comp.id);
        clone.name = (source.name == null || source.name.isBlank() ? "预合成" : source.name) + " 副本";
        project.comps.add(clone);
        done.put(sourceCompId, clone.id);

        if (clone.layers != null)
        {
            for (CompLayer layer : clone.layers)
            {
                if (layer != null && "precomp".equals(layer.type)
                    && layer.precompId != null && !layer.precompId.isEmpty())
                {
                    layer.precompId = clonePrecompTree(project, layer.precompId, done, visiting);
                }
            }
        }

        visiting.remove(sourceCompId);

        return clone.id;
    }

    /**
     * Deep-copies one composition (including every nested precomp it references) and registers the
     * clone in the project. The source is untouched.
     *
     * <p>This is the project-level operation behind "make this precomp independent": a precomp
     * layer only stores a {@code precompId}, so two layers can legitimately point at the same
     * document. When the user wants a private copy, the right primitive is "clone this document",
     * not "clone this layer" (which would also copy the layer's transform and timing).</p>
     *
     * @return the new composition, or null when the source cannot be resolved
     */
    public static CompDocument duplicateComposition(CompProject project, CompDocument source)
    {
        if (project == null || source == null || source.id == null || source.id.isEmpty())
        {
            return null;
        }

        String id = clonePrecompTree(project, source.id,
            new java.util.HashMap<>(), new java.util.HashSet<>());

        /* An empty id is the "clone failed" signal. project.getComp("") returns the FIRST
         * composition by design (the default-comp fallback), so asking it here would hand back an
         * unrelated document — and could even rename it through the lines below. */
        if (id == null || id.isEmpty())
        {
            return null;
        }

        CompDocument clone = project.getComp(id);

        if (clone == null || clone == source)
        {
            return null;
        }

        String base = source.name == null || source.name.isBlank() ? "预合成" : source.name;

        clone.name = base + " 副本";
        clone.id = id;

        return clone;
    }

    /**
     * Gives one precomp layer a private copy of its target and re-points the layer at it.
     *
     * <p>Used by the inspector's "复制目标预合成（独立副本）" action. It is the escape hatch for a
     * project that already shares one nested composition between two layers: after it, editing the
     * copy can never change the other layer's picture, and the rest of the project keeps pointing at
     * the original.</p>
     *
     * @return the new composition, or null when the layer is not a precomp with a valid target
     */
    public static CompDocument duplicatePrecompTarget(CompProject project, CompLayer layer)
    {
        if (project == null || layer == null || !"precomp".equals(layer.type)
            || layer.precompId == null || layer.precompId.isEmpty())
        {
            return null;
        }

        CompDocument source = project.getComp(layer.precompId);

        if (source == null)
        {
            return null;
        }

        CompDocument clone = duplicateComposition(project, source);

        if (clone != null)
        {
            layer.precompId = clone.id;
        }

        return clone;
    }

    /** Number of other precomp layers in {@code doc} that point at the same composition. */
    public static int countSharedSiblings(CompDocument doc, CompLayer layer)
    {
        if (doc == null || layer == null || layer.precompId == null || layer.precompId.isEmpty())
        {
            return 0;
        }

        int count = 0;

        for (CompLayer sibling : doc.layers)
        {
            if (sibling != null && sibling != layer && "precomp".equals(sibling.type)
                && layer.precompId.equals(sibling.precompId))
            {
                count += 1;
            }
        }

        return count;
    }

    /**
     * Detaches a copy-looking precomp layer from a target it shares with a sibling.
     *
     * <p>Called right before the editor opens a nested composition. A project saved before the
     * deep-copy fix can still contain such a pair until the load-time repair runs (or when the pair
     * was created in a session whose snapshot is restored); opening the copy is the exact moment the
     * user is about to edit it, so that is the last responsible place to guarantee independence.</p>
     *
     * @return true when the layer now owns a private composition
     */
    public static boolean detachIfSharedCopy(CompProject project, CompDocument doc, CompLayer layer)
    {
        if (project == null || doc == null || layer == null || !looksLikeCopyLayer(layer)
            || countSharedSiblings(doc, layer) <= 0)
        {
            return false;
        }

        return duplicatePrecompTarget(project, layer) != null;
    }

    /**
     * True for a layer that was produced by a duplicate command: its id or name carries the copy
     * marker the editor has always written.
     *
     * <p>Used only by the legacy-share repair below. It is a heuristic on purpose: sharing one
     * composition between two layers is a legitimate feature (two instances of the same nested
     * comp), so the repair must not touch a relationship the user created deliberately. A layer
     * named "… 副本" / "… copy" that points at the same target as a sibling is the exact shape the
     * old duplicate command produced.</p>
     */
    public static boolean looksLikeCopyLayer(CompLayer layer)
    {
        if (layer == null)
        {
            return false;
        }

        String id = layer.id == null ? "" : layer.id.toLowerCase(java.util.Locale.ROOT);
        String name = layer.name == null ? "" : layer.name.toLowerCase(java.util.Locale.ROOT);

        return id.contains("_copy") || name.contains("副本") || name.contains("copy");
    }

    /**
     * Repairs the legacy "copied precomp still shares its composition" state.
     *
     * <p>Before the deep-copy fix a duplicate only copied the {@code precompId} string, so a copy
     * and its source pointed at one {@link CompDocument}: editing either changed both. Projects
     * saved in that state still contain the shared pair, and no new code path can repair them
     * because the data is already shared. This pass walks every composition, and for each precomp
     * target used by more than one sibling layer it gives every <b>copy-looking</b> layer after the
     * first one its own cloned tree.</p>
     *
     * <p>Scope is intentionally narrow:</p>
     * <ul>
     *   <li>only layers inside the <b>same</b> composition count as a duplicate pair (reuse across
     *       compositions is normal project structure);</li>
     *   <li>only layers whose id/name carries the duplicate marker are split, so a deliberately
     *       shared instance is left alone;</li>
     *   <li>the first layer of each group keeps the original target, so the "original" is stable
     *       and references from other compositions do not change meaning.</li>
     * </ul>
     *
     * @return number of layers that received a private composition
     */
    public static int repairSharedPrecompCopies(CompProject project)
    {
        return repairSharedPrecompCopies(project, true);
    }

    /** Count-only variant: how many layers the repair would detach. */
    public static int countRepairableSharedPrecompCopies(CompProject project)
    {
        return repairSharedPrecompCopies(project, false);
    }

    private static int repairSharedPrecompCopies(CompProject project, boolean apply)
    {
        if (project == null || project.comps == null)
        {
            return 0;
        }

        int repaired = 0;

        /* Index-based walk, not a snapshot: duplicateComposition appends the private copies and
         * those copies can themselves contain a shared pair inherited from their source. Visiting
         * the new documents in the same pass repairs the whole tree instead of only the first
         * level. The hard stop is a cycle guard for pathological hand-edited projects. */
        for (int index = 0; index < project.comps.size() && index < 4096; index++)
        {
            CompDocument comp = project.comps.get(index);

            if (comp == null || comp.layers == null)
            {
                continue;
            }

            java.util.Map<String, java.util.List<CompLayer>> groups = new java.util.LinkedHashMap<>();

            for (CompLayer layer : comp.layers)
            {
                if (layer == null || !"precomp".equals(layer.type)
                    || layer.precompId == null || layer.precompId.isEmpty())
                {
                    continue;
                }

                groups.computeIfAbsent(layer.precompId, (key) -> new ArrayList<>()).add(layer);
            }

            for (java.util.Map.Entry<String, java.util.List<CompLayer>> entry : groups.entrySet())
            {
                if (entry.getValue().size() < 2)
                {
                    continue;
                }

                boolean first = true;

                for (CompLayer layer : entry.getValue())
                {
                    if (first)
                    {
                        first = false;

                        continue;
                    }

                    if (!looksLikeCopyLayer(layer))
                    {
                        continue;
                    }

                    CompDocument source = project.getComp(layer.precompId);

                    if (source == null)
                    {
                        continue;
                    }

                    if (!apply)
                    {
                        repaired += 1;

                        continue;
                    }

                    CompDocument clone = duplicateComposition(project, source);

                    if (clone != null)
                    {
                        layer.precompId = clone.id;
                        repaired += 1;
                    }
                }
            }
        }

        return repaired;
    }

    /**
     * Inserts clipboard templates as independent layers, without an artificial pixel offset.
     *
     * <p>AE's copy/paste and Ctrl+D both keep the copy in place; moving it is a separate user
     * action. The old +24px "so you can see it" offset was wrong for parented/3D layers and read
     * as a copy bug. References between clipped layers are remapped to the pasted copies.</p>
     */
    public static List<CompLayer> paste(CompDocument doc, List<CompLayer> templates, int insertIndex)
    {
        return paste(null, doc, templates, insertIndex);
    }

    /** Paste variant that also gives copied precomp layers their own nested composition trees. */
    public static List<CompLayer> paste(CompProject project, CompDocument doc, List<CompLayer> templates, int insertIndex)
    {
        List<CompLayer> copies = new ArrayList<>();

        if (doc == null || templates == null || templates.isEmpty())
        {
            return copies;
        }

        java.util.Map<String, String> remap = new java.util.LinkedHashMap<>();
        int at = Math.max(0, Math.min(insertIndex, doc.layers.size()));

        for (CompLayer template : templates)
        {
            if (template == null)
            {
                continue;
            }

            CompLayer copy = duplicateLayer(project, template);

            copy.id = CompStore.uniqueId(
                (template.id == null || template.id.isEmpty() ? "layer" : template.id) + "_copy",
                doc.layers, (candidate) -> candidate.id);
            copy.name = (template.name == null || template.name.isEmpty() ? "Layer" : template.name) + " 副本";
            doc.layers.add(at++, copy);
            copies.add(copy);
            remap.put(template.id, copy.id);
        }

        for (CompLayer copy : copies)
        {
            if (copy.parentId != null && remap.containsKey(copy.parentId))
            {
                copy.parentId = remap.get(copy.parentId);
            }
            else if (copy.parentId != null && !copy.parentId.isEmpty() && doc.getLayer(copy.parentId) == null)
            {
                copy.parentId = "";
            }

            if (copy.trackMatteId != null && remap.containsKey(copy.trackMatteId))
            {
                copy.trackMatteId = remap.get(copy.trackMatteId);
            }
            else if (copy.trackMatteId != null && !copy.trackMatteId.isEmpty() && doc.getLayer(copy.trackMatteId) == null)
            {
                copy.trackMatteId = "";
                copy.trackMatteEnabled = false;
            }
        }

        doc.extendDurationToContent();

        return copies;
    }

    /**
     * Legacy helper for callers that explicitly want a moved copy. Duplicate/paste no longer use
     * it: an implicit +24 px offset made the copy look misaligned from its source.
     *
     * <p>{@code x/y} alone are not enough once a layer has transform keyframes...</p>
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
