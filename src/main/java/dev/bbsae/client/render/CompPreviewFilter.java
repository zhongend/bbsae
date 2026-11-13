package dev.bbsae.client.render;

import dev.bbsae.comp.CompLayer;

import java.util.function.Predicate;

/**
 * A thread-scoped layer filter for the replay editor's composition preview.
 *
 * <p>The replay editor reuses the compositor's renderer, but it must not show a BBS live-world
 * preview layer: that is the real Minecraft world, not the bbsae virtual world. Rather than
 * mutating {@code CompLayer.visible} (which would be saved and would poison compositor caches),
 * the replay preview pushes a filter for the duration of one composition render. All compositor
 * layer loops consult {@link #visible(CompLayer)}; normal compositor rendering has no filter.</p>
 */
public final class CompPreviewFilter
{
    private static final ThreadLocal<Predicate<CompLayer>> FILTER = new ThreadLocal<>();

    private CompPreviewFilter()
    {}

    public static void push(Predicate<CompLayer> filter)
    {
        FILTER.set(filter);
    }

    public static void pop()
    {
        FILTER.remove();
    }

    public static boolean visible(CompLayer layer)
    {
        Predicate<CompLayer> filter = FILTER.get();

        return filter == null || (layer != null && filter.test(layer));
    }

    /** True while a replay-preview filter is active; compositor caches must not be reused. */
    public static boolean active()
    {
        return FILTER.get() != null;
    }
}
