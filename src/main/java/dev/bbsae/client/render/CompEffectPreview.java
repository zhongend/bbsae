package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import dev.bbsae.comp.CompShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny rendered thumbnails of an effect, for the effect library and the effect stack.
 *
 * <h2>Why render instead of drawing an icon</h2>
 * <p>An effect's identity <em>is</em> its output: "VHS" and "Glitch" are words, but what the user is
 * choosing between is two pictures. A hand-drawn icon would be a second, drifting description of the
 * shader; rendering the real effect on a real test card is the same code path the timeline uses, so
 * the thumbnail cannot lie.</p>
 *
 * <p>The card is deliberately small (96×54) and fixed: a gradient band, a white disc, three colour
 * bars and a checker corner, which makes blur, distort, pixel, colour and keying effects all
 * distinguishable. One composition is built once and reused; each thumbnail is one
 * {@link CompRenderer#renderFrame} call, cached by effect type <b>and parameters</b> (so moving a
 * slider updates the preview, and coming back to the same value costs nothing).</p>
 */
public final class CompEffectPreview
{
    public static final int WIDTH = 96;
    public static final int HEIGHT = 54;

    private static final int CACHE_LIMIT = 40;

    private static CompProject project;
    private static CompDocument doc;
    private static CompLayer card;

    private static final LinkedHashMap<String, GlFramebuffer> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    private CompEffectPreview()
    {}

    /** Builds the test card composition once. */
    private static void ensure()
    {
        if (project != null)
        {
            return;
        }

        project = new CompProject();
        project.id = "effect_preview";

        doc = project.addComp("preview", WIDTH, HEIGHT, 30F, 1F);
        doc.background = 0xFF12161C;

        card = new CompLayer("card", "Card", "shape");
        card.x = WIDTH / 2F;
        card.y = HEIGHT / 2F;
        card.width = WIDTH;
        card.height = HEIGHT;
        card.out = 1F;

        /* A card that every family of effect can change visibly: a bright ramp (colour/gradient
         * effects), a white disc (blur/emboss/halftone), saturated bars (hue/keying) and a dark
         * corner (level/contrast/vignette). */
        card.shapes.add(CompShape.rect("bg", 0F, 0F, WIDTH, HEIGHT, 0xFF1B2530));
        card.shapes.add(CompShape.rect("bar_r", -WIDTH / 2F + 6F, -HEIGHT / 2F + 6F, 18F, HEIGHT - 12F, 0xFFE0574F));
        card.shapes.add(CompShape.rect("bar_g", -WIDTH / 2F + 28F, -HEIGHT / 2F + 6F, 18F, HEIGHT - 12F, 0xFF57C46B));
        card.shapes.add(CompShape.rect("bar_b", -WIDTH / 2F + 50F, -HEIGHT / 2F + 6F, 18F, HEIGHT - 12F, 0xFF4C8BE0));
        card.shapes.add(CompShape.ellipse("disc", 12F, -6F, 30F, 30F, 0xFFFFFFFF));
        card.shapes.add(CompShape.rect("strip", -WIDTH / 2F + 6F, HEIGHT / 2F - 14F, WIDTH - 12F, 8F, 0xFFF2D06B));

        doc.addLayer(card);
    }

    /**
     * Thumbnail for an effect, or null when there is no GL context yet.
     *
     * @param effect the effect to preview (its parameters are used; the layer is not)
     */
    public static GlFramebuffer frame(CompEffect effect)
    {
        if (effect == null || effect.type == null)
        {
            return null;
        }

        ensure();

        String key = key(effect);
        GlFramebuffer cached = CACHE.get(key);

        if (cached != null)
        {
            return cached;
        }

        CompEffect copy = effect.copy();

        copy.enabled = true;

        card.effects.clear();
        card.effects.add(copy);

        GlFramebuffer rendered = null;

        try
        {
            rendered = CompRenderer.renderFrame(project, doc, 0, false);
        }
        catch (RuntimeException e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not render effect preview for {}", effect.type, e);
        }

        card.effects.clear();

        if (rendered == null)
        {
            return null;
        }

        CACHE.put(key, rendered);

        while (CACHE.size() > CACHE_LIMIT)
        {
            String oldest = CACHE.keySet().iterator().next();

            FboPool.release(CACHE.remove(oldest));
        }

        return rendered;
    }

    /** Thumbnail for a type with its default parameters. */
    public static GlFramebuffer frame(String type)
    {
        CompEffect effect = CompEffectRegistry.create(type, 1);

        return effect == null ? null : frame(effect);
    }

    /** Type + every parameter, so a slider drag produces a new key (and a new picture). */
    private static String key(CompEffect effect)
    {
        StringBuilder key = new StringBuilder(effect.type);

        List<Map.Entry<String, Double>> params = new ArrayList<>(effect.params.entrySet());

        params.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, Double> entry : params)
        {
            key.append('|').append(entry.getKey()).append('=').append(Math.round(entry.getValue() * 1000D));
        }

        List<Map.Entry<String, Integer>> colors = new ArrayList<>(effect.colors.entrySet());

        colors.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, Integer> entry : colors)
        {
            key.append('|').append(entry.getKey()).append('#').append(entry.getValue());
        }

        return key.toString();
    }

    public static int size()
    {
        return CACHE.size();
    }

    /** Drops every thumbnail; the GPU objects go back to the pool. */
    public static void clear()
    {
        for (GlFramebuffer framebuffer : CACHE.values())
        {
            FboPool.release(framebuffer);
        }

        CACHE.clear();
    }
}
