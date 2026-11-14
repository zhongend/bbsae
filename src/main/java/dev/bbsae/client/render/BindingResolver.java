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
    /**
     * Film whose replays this resolver may bind to. Set by the compositor when it binds a film and
     * cleared when the panel closes, so the fallback below cannot read a stale film.
     */
    private static mchorse.bbs_mod.film.Film boundFilm;
    /**
     * Absolute film tick for the overlay currently being drawn.
     *
     * <p>A split or time-scaled composition clip no longer maps one-to-one to film time, so the
     * model-level fallback cannot derive the film tick from the composition seconds. The overlay
     * knows the exact clip tick and sets it here for the duration of one instance.</p>
     */
    private static float frameTick = -1F;

    private BindingResolver()
    {}

    /** The film used for model-level replay fallback (null = standalone compositor). */
    public static void setFilm(mchorse.bbs_mod.film.Film film)
    {
        boundFilm = film;

        if (film == null)
        {
            SMOOTHED.clear();
            frameTick = -1F;
        }
    }

    public static mchorse.bbs_mod.film.Film getFilm()
    {
        return boundFilm;
    }

    /**
     * Restores a previously active film without clearing the smoothing state.
     *
     * <p>The overlay temporarily installs the film it is drawing and restores the panel's film on
     * exit. Using {@link #setFilm(mchorse.bbs_mod.film.Film)} there would clear the filter every
     * frame and make the "smooth" binding option do nothing.</p>
     */
    public static void restoreFilm(mchorse.bbs_mod.film.Film film)
    {
        boundFilm = film;
    }

    /** Sets the absolute film tick for the next binding resolution; negative clears it. */
    public static void setFrameTick(float tick)
    {
        frameTick = tick;
    }

    public static float frameTick()
    {
        return frameTick;
    }

    /** True when the layer has a binding switch that actually asks for an external position. */
    public static boolean isBound(CompLayer layer)
    {
        return layer != null && layer.binding != null && layer.binding.enabled
            && !CompBinding.NONE.equals(layer.binding.type)
            && !CompBinding.SCREEN.equals(layer.binding.type);
    }

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

        for (BaseFilmController controller : controllers())
        {
            /* Prefer the film the compositor is bound to. Another film playing in the world could
             * contain a replay with the same id, and projecting through it put the layer at a
             * different actor's position. */
            if (boundFilm != null && controller.film != boundFilm)
            {
                continue;
            }

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

        /* No live controller (export frame, paused world, just-opened project): evaluate the
         * replay's own position channels at the composition time. Without this a binding looked
         * correct in the editor (live entities exist) and silently fell back to the layer's own
         * X/Y in the encoded frame. */
        return replayFromFilm(context, binding);
    }

    /**
     * Live film controllers, or an empty list when the client managers are not up yet (headless
     * tests, the first frames of a session). A binding must never crash the renderer.
     */
    private static java.util.List<BaseFilmController> controllers()
    {
        try
        {
            mchorse.bbs_mod.film.Films films = BBSModClient.getFilms();

            if (films != null && films.getControllers() != null)
            {
                return films.getControllers();
            }
        }
        catch (RuntimeException ignored)
        {}

        return java.util.Collections.emptyList();
    }

    /**
     * Model-level fallback: the replay's curated x/y/z channels at the film tick.
     *
     * <p>The compositor time is in seconds and BBS films are in 20 ticks per second, the same
     * mapping the clip/loop synchronisation uses. Eye height is approximated (the model form is not
     * instantiated here); it is a HUD anchor, not a physical contact point.</p>
     */
    private static Vector2f replayFromFilm(CompRenderContext context, CompBinding binding)
    {
        if (boundFilm == null)
        {
            return null;
        }

        mchorse.bbs_mod.film.replays.Replay replay = boundFilm.replays.getById(binding.target);

        if (replay == null || replay.keyframes == null)
        {
            return null;
        }

        /* The overlay supplies the absolute camera-timeline tick. Composition time is the right
         * fallback for an unsplit clip in the editor, but it is wrong for a split/time-scaled clip
         * (the right half restarts at 0 and only its offset carries the film time). */
        double tick = frameTick >= 0F ? frameTick : Math.max(0D, context.time * 20D);
        int at = replay.getTick((int) Math.floor(tick));
        double fraction = tick - Math.floor(tick);

        /* Pose a stub through the replay's own channels instead of reading x/y/z directly: this
         * gives relative replays their origin, looping its wrapped tick and the entity's real eye
         * height, so the anchor lands where the actor's body is. */
        try
        {
            mchorse.bbs_mod.forms.entities.StubEntity entity =
                new mchorse.bbs_mod.forms.entities.StubEntity(null);

            replay.keyframes.apply((float) (at + fraction), entity);

            double x = entity.getX();
            double y = entity.getY() + Math.max(0D, entity.getEyeHeight()) + binding.worldOffsetY;
            double z = entity.getZ();

            if (replay.relative.get() && replay.relativeOffset.get() != null)
            {
                org.joml.Vector3d origin = replay.getRelativeOrigin();

                x += origin.x;
                y += origin.y;
                z += origin.z;
            }

            Vector4f projected = project(context.projection, context.camera, x, y, z,
                context.width, context.height);

            if (projected != null)
            {
                return new Vector2f(projected.x, projected.y);
            }
        }
        catch (RuntimeException ignored)
        {
            /* A form/property channel that cannot apply on a stub must not kill the whole frame;
             * the channel-level fallback below is still usable. */
        }

        double x = channel(replay.keyframes.x, (float) tick);
        double y = channel(replay.keyframes.y, (float) tick);
        double z = channel(replay.keyframes.z, (float) tick);

        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z))
        {
            return null;
        }

        if (replay.relative.get())
        {
            org.joml.Vector3d origin = replay.getRelativeOrigin();

            x += origin.x;
            y += origin.y;
            z += origin.z;
        }

        Vector4f projected = project(context.projection, context.camera, x,
            y + 1.6F + binding.worldOffsetY, z, context.width, context.height);

        return projected == null ? null : new Vector2f(projected.x, projected.y);
    }

    /** Value of one replay position channel at a tick; 0 when it has no keys. */
    private static double channel(mchorse.bbs_mod.utils.keyframes.KeyframeChannel<Double> channel, float tick)
    {
        if (channel == null)
        {
            return 0D;
        }

        if (channel.isEmpty())
        {
            return 0D;
        }

        Double value = channel.interpolate(tick);

        return value == null ? Double.NaN : value;
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

        if (point.w <= 0.0001F
            || Float.isNaN(point.x) || Float.isNaN(point.y) || Float.isNaN(point.z)
            || Float.isInfinite(point.x) || Float.isInfinite(point.y))
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
