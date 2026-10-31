package dev.bbsae.comp;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Parenting that does not move the child.
 *
 * <p>The bug this fixes: a parent's transform is a <b>matrix</b>, and a child's own transform is
 * expressed in the parent's space. Binding a layer that already sat at comp coordinates
 * (960, 540) to a parent that sits at (200, 200) therefore teleports the child by the parent's
 * whole transform — in AE the child stays where it is and its local values are re-expressed.
 * Position, rotation, scale and the parent's own chain all contribute, so adding a hand-written
 * "minus parent position" fudge would break the moment the parent rotates or scales. The correct
 * operation is a change of basis:</p>
 *
 * <pre>local' = parentWorld⁻¹ · childWorld</pre>
 *
 * <p>and then re-writing the child's transform from that matrix. Keyframes are shifted by the same
 * delta (and scales multiplied by the same ratio) instead of only touching the current frame, so an
 * animated child keeps its animation curve after being bound.</p>
 */
public final class CompParenting
{
    private CompParenting()
    {}

    /**
     * The local matrix a layer contributes to its world matrix, at one frame.
     *
     * <p>Same composition as {@code CompScene.local}: translate · rotateZ · rotateX · rotateY ·
     * scale, in comp space (Y down, Z towards the viewer). A 2D layer pins z / tilt / scaleZ to
     * identity so the two paths cannot drift apart.</p>
     */
    public static Matrix4f localMatrix(CompLayer layer, float frame)
    {
        Transform transform = layer.transformAt(frame);
        float sx = Math.max(0.0001F, transform.scale.x);
        float sy = Math.max(0.0001F, transform.scale.y);
        float z = 0F;
        float rx = 0F;
        float ry = 0F;
        float sz = 1F;

        if (layer.threeD)
        {
            z = layer.scalarAt("z", layer.z, frame);
            rx = layer.scalarAt("rotationX", layer.rotationX, frame);
            ry = layer.scalarAt("rotationY", layer.rotationY, frame);
            sz = Math.max(0.0001F, layer.scalarAt("scaleZ", layer.scaleZ, frame));
        }

        return new Matrix4f()
            .translate(transform.translate.x, transform.translate.y, z)
            .rotateZ((float) Math.toRadians(transform.rotate.z))
            .rotateX((float) Math.toRadians(rx))
            .rotateY((float) Math.toRadians(ry))
            .scale(sx, sy, sz);
    }

    /**
     * Full parent chain matrix (2D). Matches {@code CompRenderer.worldMatrix} for 2D layers,
     * which is what makes the compensation exact instead of approximate.
     */
    public static Matrix4f worldMatrix(CompDocument doc, CompLayer layer, float frame)
    {
        return worldMatrix(doc, layer, frame, new java.util.HashSet<>());
    }

    private static Matrix4f worldMatrix(CompDocument doc, CompLayer layer, float frame, java.util.Set<String> seen)
    {
        if (layer == null || !seen.add(layer.id))
        {
            return new Matrix4f();
        }

        Matrix4f local = localMatrix(layer, frame);
        CompLayer parent = parentOf(doc, layer);

        if (parent == null)
        {
            return local;
        }

        return worldMatrix(doc, parent, frame, seen).mul(local);
    }

    public static CompLayer parentOf(CompDocument doc, CompLayer layer)
    {
        if (doc == null || layer == null || layer.parentId == null || layer.parentId.isEmpty())
        {
            return null;
        }

        CompLayer parent = doc.getLayer(layer.parentId);

        return parent == layer ? null : parent;
    }

    /** True when {@code candidate} is inside {@code layer}'s subtree (used to prevent cycles). */
    public static boolean isDescendant(CompDocument doc, CompLayer candidate, CompLayer layer)
    {
        java.util.Set<String> seen = new java.util.HashSet<>();
        CompLayer current = candidate;

        while (current != null && current.parentId != null && !current.parentId.isEmpty() && seen.add(current.id))
        {
            if (current.parentId.equals(layer.id))
            {
                return true;
            }

            current = doc.getLayer(current.parentId);
        }

        return false;
    }

    /**
     * Binds {@code child} to {@code parentId} while keeping its on-screen transform.
     *
     * @param parentId empty string to unbind (the child keeps its world position as well).
     * @return false when the binding was refused (self parent, cycle, missing comp).
     */
    public static boolean bind(CompDocument doc, CompLayer child, String parentId, float frame)
    {
        if (doc == null || child == null)
        {
            return false;
        }

        String targetId = parentId == null ? "" : parentId;

        if (!targetId.isEmpty())
        {
            CompLayer parent = doc.getLayer(targetId);

            if (parent == null || parent == child || isDescendant(doc, parent, child))
            {
                return false;
            }
        }

        Matrix4f childWorld = worldMatrix(doc, child, frame);
        Matrix4f parentWorld = targetId.isEmpty()
            ? new Matrix4f()
            : worldMatrix(doc, doc.getLayer(targetId), frame);
        Matrix4f local = new Matrix4f(parentWorld).invert().mul(childWorld);
        float[] decomposed = decompose(local);
        Transform current = child.transformAt(frame);
        float currentZ = child.threeD ? child.scalarAt("z", child.z, frame) : 0F;
        float currentRotX = child.threeD ? child.scalarAt("rotationX", child.rotationX, frame) : 0F;
        float currentRotY = child.threeD ? child.scalarAt("rotationY", child.rotationY, frame) : 0F;
        float currentScaleZ = child.threeD ? Math.max(0.0001F, child.scalarAt("scaleZ", child.scaleZ, frame)) : 1F;

        float dx = decomposed[0] - current.translate.x;
        float dy = decomposed[1] - current.translate.y;
        float dz = decomposed[2] - currentZ;
        float drz = decomposed[3] - current.rotate.z;
        float drx = decomposed[4] - currentRotX;
        float dry = decomposed[5] - currentRotY;
        float ratioX = Math.abs(current.scale.x) < 0.0001F ? 1F : decomposed[6] / current.scale.x;
        float ratioY = Math.abs(current.scale.y) < 0.0001F ? 1F : decomposed[7] / current.scale.y;
        float ratioZ = Math.abs(currentScaleZ) < 0.0001F ? 1F : decomposed[8] / currentScaleZ;

        applyDelta(child, dx, dy, dz, drz, drx, dry, ratioX, ratioY, ratioZ);
        child.parentId = targetId;

        return true;
    }

    /** Re-expresses a layer's transform when its parent changed, without moving it. */
    public static void applyDelta(CompLayer layer, float dx, float dy, float dz, float drz, float drx, float dry,
                                  float ratioX, float ratioY, float ratioZ)
    {
        layer.ensureIndependentAxes();

        /* Each component is shifted/scaled on its own curve. This keeps X, Y and Z key times
         * independent while still applying the parent-basis correction to every existing key. */
        applyAxis(layer, CompCurves.AXIS_X, dx, 1F);
        applyAxis(layer, CompCurves.AXIS_Y, dy, 1F);
        applyAxis(layer, CompCurves.AXIS_ROTATION, drz, 1F);
        applyAxis(layer, CompCurves.AXIS_SCALE_X, 0F, ratioX);

        if (layer.threeD)
        {
            applyAxis(layer, CompCurves.AXIS_Z, dz, 1F);
            applyAxis(layer, CompCurves.AXIS_ROTATION_X, drx, 1F);
            applyAxis(layer, CompCurves.AXIS_ROTATION_Y, dry, 1F);
            applyAxis(layer, CompCurves.AXIS_SCALE_Z, 0F, ratioZ);
        }

        /* Base values follow the delta too: deleting every key must not snap the layer back to a
         * transform that predates the binding. */
        layer.x += dx;
        layer.y += dy;
        layer.rotation += drz;
        layer.scaleX *= ratioX;
        layer.scaleY *= ratioY;

        if (layer.threeD)
        {
            layer.z += dz;
            layer.rotationX += drx;
            layer.rotationY += dry;
            layer.scaleZ = Math.max(0.0001F, layer.scaleZ * ratioZ);
        }
    }

    private static void applyAxis(CompLayer layer, int axis, float delta, float ratio)
    {
        KeyframeChannel<Float> channel = layer.axisChannel(axis);

        if (channel.getKeyframes().isEmpty())
        {
            return;
        }

        boolean scale = CompCurves.isScaleAxis(axis);

        for (Keyframe<Float> key : channel.getKeyframes())
        {
            float value = key.getValue() == null ? 0F : key.getValue();

            value = scale ? Math.max(0.0001F, value * ratio + delta) : value * ratio + delta;
            key.setValue(value);

            if (Math.abs(ratio - 1F) > 0.00001F)
            {
                key.ry *= ratio;
                key.ly *= ratio;
            }
        }

        channel.sort();
    }

    /**
     * Decomposes a scene matrix into
     * {@code {x, y, z, rotationZ, rotationX, rotationY, scaleX, scaleY, scaleZ}} (degrees).
     *
     * <p>The layer composes as {@code Rz · Rx · Ry}, so with JOML's column-major names the
     * rotation terms are {@code m12 = sin(pitch)}, {@code atan2(-m02, m22) = yaw} and
     * {@code atan2(-m10, m11) = roll}. Everything else (shear from a non-uniformly scaled,
     * rotated parent) is not representable and is absorbed by the scale magnitudes — position is
     * always exact, which is the part the artist sees.</p>
     */
    public static float[] decompose(Matrix4f matrix)
    {
        float[] scale = new float[3];

        scale[0] = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02());
        scale[1] = (float) Math.sqrt(matrix.m10() * matrix.m10() + matrix.m11() * matrix.m11() + matrix.m12() * matrix.m12());
        scale[2] = (float) Math.sqrt(matrix.m20() * matrix.m20() + matrix.m21() * matrix.m21() + matrix.m22() * matrix.m22());

        scale[0] = Math.max(0.0001F, scale[0]);
        scale[1] = Math.max(0.0001F, scale[1]);
        scale[2] = Math.max(0.0001F, scale[2]);

        float r00 = matrix.m00() / scale[0];
        float r01 = matrix.m01() / scale[0];
        float r02 = matrix.m02() / scale[0];
        float r10 = matrix.m10() / scale[1];
        float r11 = matrix.m11() / scale[1];
        float r12 = matrix.m12() / scale[1];
        float r20 = matrix.m20() / scale[2];
        float r21 = matrix.m21() / scale[2];
        float r22 = matrix.m22() / scale[2];

        float pitch = (float) Math.asin(Math.max(-1F, Math.min(1F, r12)));
        float cosPitch = (float) Math.cos(pitch);
        float yaw;
        float roll;

        if (Math.abs(cosPitch) < 0.0001F)
        {
            /* Gimbal lock: only yaw+roll is observable, pin yaw to zero. */
            yaw = 0F;
            roll = (float) Math.atan2(-r01, r00);
        }
        else
        {
            yaw = (float) Math.atan2(-r02, r22);
            roll = (float) Math.atan2(-r10, r11);
        }

        return new float[] {
            matrix.m30(), matrix.m31(), matrix.m32(),
            (float) Math.toDegrees(roll), (float) Math.toDegrees(pitch), (float) Math.toDegrees(yaw),
            scale[0], scale[1], scale[2]
        };
    }

    /**
     * Linear part of the parent chain (rotation + scale, no translation), inverted. Dragging a
     * parented child must convert a screen delta into the parent's space, otherwise a scaled or
     * rotated parent makes the layer move faster, slower or in the wrong direction than the cursor.
     */
    public static Matrix4f parentLinearInverse(CompDocument doc, CompLayer layer, float frame)
    {
        CompLayer parent = parentOf(doc, layer);
        Matrix4f inverse = new Matrix4f();

        if (parent == null)
        {
            return inverse;
        }

        Matrix4f world = worldMatrix(doc, parent, frame);
        Matrix4f linear = new Matrix4f(world);

        linear.m30(0F);
        linear.m31(0F);
        linear.m32(0F);

        if (Math.abs(linear.determinant()) < 0.000001F)
        {
            return inverse;
        }

        linear.invert();

        return linear;
    }

    /** Converts a composition-space delta into the layer's own parent space. */
    public static org.joml.Vector2f toLocalDelta(CompDocument doc, CompLayer layer, float frame, float dx, float dy)
    {
        Matrix4f inverse = parentLinearInverse(doc, layer, frame);

        /* 2D direction transform: x' = m00*x + m10*y, y' = m01*x + m11*y (column-major). */
        return new org.joml.Vector2f(
            inverse.m00() * dx + inverse.m10() * dy,
            inverse.m01() * dx + inverse.m11() * dy
        );
    }
}
