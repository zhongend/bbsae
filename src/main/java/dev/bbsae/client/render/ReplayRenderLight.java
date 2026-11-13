package dev.bbsae.client.render;

import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;

/**
 * Forces virtual replay actors to full-bright while bbsae renders them.
 *
 * <p>BBS' {@link FilmEntityRenderer} samples the <b>world</b> light at the actor's block position
 * before it builds the form context. That is correct for a real film actor standing in the world,
 * but bbsae's virtual replay actor is only a world-space coordinate used by the composition; its
 * position can be inside a cave/under a block, so the composition preview turned the model black
 * in dark scenes even though bbsae already binds a white lightmap texture.</p>
 *
 * <p>The render-side switch is a counter rather than a boolean because the compositor's linked
 * model pass, the replay editor pass and the entity mask can nest. The mixin only overrides the
 * lightmap packing inside {@code FilmEntityRenderer}; real film playback and BBS preview layers are
 * untouched.</p>
 */
public final class ReplayRenderLight
{
    private static int depth;

    private ReplayRenderLight()
    {}

    public static void begin()
    {
        depth += 1;
    }

    public static void end()
    {
        if (depth > 0)
        {
            depth -= 1;
        }
    }

    public static boolean fullbright()
    {
        return depth > 0;
    }

    /** Render one actor through BBS' renderer with the virtual-world full-bright override. */
    public static void renderEntity(FilmControllerContext context)
    {
        begin();

        try
        {
            FilmEntityRenderer.renderEntity(context);
        }
        finally
        {
            end();
        }
    }
}
