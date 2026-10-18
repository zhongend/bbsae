package dev.bbsae.client.render;

import dev.bbsae.comp.CompBinding;
import dev.bbsae.comp.CompLayer;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.forms.entities.IEntity;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Projects BBS data into screen space. This is the part an AE user would have to solve with a
 * tracker: the film already knows the camera and the replay positions, so a HUD label can be
 * attached to an actor (or to the camera) exactly.
 */
public final class BindingResolver
{
    private static final Map<String, Vector2f> SMOOTHED = new HashMap<>();

    private BindingResolver()
    {}

    public static Vector2f resolve(CompLayer layer, CompRenderContext context)
    {
        CompBinding binding = layer.binding;

        if (binding == null || !binding.enabled)
        {
            return null;
        }

        Vector2f point = null;

        switch (binding.type)
        {
            case CompBinding.CAMERA:
                point = new Vector2f(context.width / 2F, context.height / 2F);
                break;

            case CompBinding.REPLAY:
                point = replay(context, binding);
                break;

            case CompBinding.LAYER:
                CompLayer target = context.comp.getLayer(binding.target);

                if (target != null)
                {
                    point = layerPosition(context.comp, target, context, new java.util.HashSet<>());
                }
                break;

            case CompBinding.SCREEN:
            case CompBinding.NONE:
            default:
                return null;
        }

        if (point == null)
        {
            return null;
        }

        point.x += binding.offsetX;
        point.y += binding.offsetY;

        if (binding.smoothing > 0F)
        {
            String key = context.comp.id + "/" + layer.id;
            Vector2f last = SMOOTHED.get(key);
            float factor = Math.max(0.001F, Math.min(1F, 1F - binding.smoothing));

            if (last == null)
            {
                SMOOTHED.put(key, new Vector2f(point));
            }
            else
            {
                last.lerp(point, factor);
                point = new Vector2f(last);
            }
        }

        return point;
    }

    private static Vector2f layerPosition(dev.bbsae.comp.CompDocument comp, CompLayer layer, CompRenderContext context, java.util.Set<String> seen)
    {
        if (layer == null || !seen.add(layer.id))
        {
            return null;
        }

        mchorse.bbs_mod.utils.pose.Transform transform = layer.transformAt(context.frame);
        Matrix4f local = new Matrix4f()
            .translate(transform.translate.x, transform.translate.y, 0F)
            .rotate(new org.joml.Quaternionf().rotateZ((float) Math.toRadians(transform.rotate.z)))
            .scale(Math.max(0.0001F, transform.scale.x), Math.max(0.0001F, transform.scale.y), 1F);
        CompLayer parent = layer.parentId == null || layer.parentId.isEmpty() ? null : comp.getLayer(layer.parentId);

        if (parent == null || parent == layer)
        {
            return new Vector2f(local.m30(), local.m31());
        }

        Vector2f parentPosition = layerPosition(comp, parent, context, seen);

        if (parentPosition == null)
        {
            return new Vector2f(local.m30(), local.m31());
        }

        mchorse.bbs_mod.utils.pose.Transform parentTransform = parent.transformAt(context.frame);
        Matrix4f parentMatrix = new Matrix4f()
            .translate(parentPosition.x, parentPosition.y, 0F)
            .rotate(new org.joml.Quaternionf().rotateZ((float) Math.toRadians(parentTransform.rotate.z)))
            .scale(Math.max(0.0001F, parentTransform.scale.x), Math.max(0.0001F, parentTransform.scale.y), 1F);
        Matrix4f world = parentMatrix.mul(local);

        return new Vector2f(world.m30(), world.m31());
    }

    private static Vector2f replay(CompRenderContext context, CompBinding binding)
    {
        if (binding.target == null || binding.target.isEmpty())
        {
            return null;
        }

        for (BaseFilmController controller : BBSModClient.getFilms().getControllers())
        {
            IEntity entity = controller.getEntities().get(binding.target);

            if (entity == null)
            {
                continue;
            }

            Vector4f projected = project(
                context.projection,
                context.camera,
                entity.getX(),
                entity.getY() + entity.getEyeHeight() + binding.worldOffsetY,
                entity.getZ(),
                context.width,
                context.height
            );

            if (projected == null)
            {
                return null;
            }

            return new Vector2f(projected.x, projected.y);
        }

        return null;
    }

    private static Vector4f project(Matrix4f projection, Camera camera, double x, double y, double z, float width, float height)
    {
        if (projection == null || camera == null)
        {
            return null;
        }

        Vector4f point = new Vector4f((float) x, (float) y, (float) z, 1F);
        Matrix4f view = new Matrix4f().rotate(camera.getRotation()).translate(
            (float) -camera.getPos().x,
            (float) -camera.getPos().y,
            (float) -camera.getPos().z
        );

        point.mul(view);
        point.mul(projection);

        if (point.w <= 0.0001F)
        {
            return null;
        }

        float ndcX = point.x / point.w;
        float ndcY = point.y / point.w;

        return new Vector4f(
            (ndcX * 0.5F + 0.5F) * width,
            (1F - (ndcY * 0.5F + 0.5F)) * height,
            0F,
            1F
        );
    }

    public static void resetSmoothing()
    {
        SMOOTHED.clear();
    }
}
