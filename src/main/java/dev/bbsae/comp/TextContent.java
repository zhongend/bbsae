package dev.bbsae.comp;

/**
 * Content-level text effects: what the text layer should say at a given frame.
 *
 * <p>The reference app has text effects that change the string rather than the pixels — a timecode
 * readout, a counter, a random-glyph scramble and a typewriter reveal. They are applied here, in
 * data space, before layout/drawing, so they compose with the existing per-character animators
 * (which already cover the per-glyph transform and spacing text effects).</p>
 *
 * <p>Fields are nullable on {@link CompLayer} so projects saved before these features existed keep
 * their exact text: a missing value means "off"/"1", not the zero value of an Unsafe-deserialised
 * primitive.</p>
 */
public final class TextContent
{
    private static final String RANDOM_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&@";

    private TextContent()
    {}

    public static String resolve(CompLayer layer, float time, int fps, int frame)
    {
        if (layer == null)
        {
            return "";
        }

        String text = layer.text == null ? "" : layer.text;
        int mode = layer.textMode == null ? 0 : layer.textMode;

        if (mode == 1)
        {
            text = timecode(time, Math.max(1, fps));
        }
        else if (mode == 2)
        {
            text = counter(layer, time);
        }

        float random = layer.textRandom == null ? 0F : layer.textRandom;

        if (random > 0.0001F)
        {
            text = randomize(text, random, layer.textRandomSeed == null ? 0 : layer.textRandomSeed, frame);
        }

        float progress = layer.textProgress == null ? 1F : layer.textProgress;

        if (progress < 0.999F)
        {
            text = reveal(text, progress);
        }

        return text;
    }

    /** {@code HH:MM:SS:FF} at the composition frame rate. */
    public static String timecode(float time, int fps)
    {
        long total = Math.max(0L, Math.round(time * fps));
        long frames = total % fps;
        long seconds = (total / fps) % 60L;
        long minutes = (total / (fps * 60L)) % 60L;
        long hours = total / (fps * 3600L);

        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames);
    }

    public static String counter(CompLayer layer, float time)
    {
        float offset = layer.textCounterOffset == null ? 0F : layer.textCounterOffset;
        float scale = layer.textCounterScale == null ? 1F : layer.textCounterScale;
        double value = Math.round(offset + time * scale);

        return String.format(java.util.Locale.ROOT, "%.0f", value);
    }

    /** Replaces a deterministic fraction of the glyphs with random ones. */
    public static String randomize(String text, float amount, int seed, int frame)
    {
        float clamped = Math.max(0F, Math.min(1F, amount));
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;

        for (int i = 0; i < text.length(); )
        {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));

            if (Character.isWhitespace(codePoint))
            {
                out.append(glyph);
            }
            else
            {
                float rnd = hash01(index * 31 + frame * 17 + seed * 97);

                if (rnd < clamped)
                {
                    out.append(RANDOM_CHARSET.charAt((int) (hash01(seed * 13 + index * 7 + frame) * RANDOM_CHARSET.length())));
                }
                else
                {
                    out.append(glyph);
                }
            }

            index++;
            i += Character.charCount(codePoint);
        }

        return out.toString();
    }

    /** Typewriter reveal over code points (not UTF-16 units). */
    public static String reveal(String text, float progress)
    {
        float clamped = Math.max(0F, Math.min(1F, progress));
        int total = text.codePointCount(0, text.length());
        int count = Math.round(total * clamped);

        if (count <= 0)
        {
            return "";
        }

        if (count >= total)
        {
            return text;
        }

        int end = text.offsetByCodePoints(0, count);

        return text.substring(0, end);
    }

    /** Deterministic 0..1 hash; the same text/seed/frame must always scramble the same way. */
    private static float hash01(int value)
    {
        int h = value * 0x27D4EB2D;

        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;

        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /**
     * True when {@link #resolve} can return a different string on another frame without any edit.
     *
     * <p>Render caches that key by "document content" must treat such layers as time dependent;
     * a typewriter reveal, a timecode, a counter or a glyph scramble all change with the frame
     * even though the layer fields stay identical. Data helper for the render-cache fingerprints
     * ({@code CompRenderCache}, {@code CompLayerSourceCache}); it does not change text semantics.</p>
     */
    public static boolean isTimeDependent(CompLayer layer)
    {
        if (layer == null)
        {
            return false;
        }

        int mode = layer.textMode == null ? 0 : layer.textMode;

        if (mode == 1 || mode == 2)
        {
            return true;
        }

        if (layer.textRandom != null && layer.textRandom > 0.0001F)
        {
            return true;
        }

        return layer.textProgress != null && layer.textProgress < 0.999F;
    }

    /**
     * Fingerprint of the content-level text fields (timecode/counter/random/reveal). Nullable
     * fields are normalised to their documented "off" values so {@code null} and an explicit 0
     * hash the same way the renderer resolves them.
     */
    public static long contentHash(CompLayer layer)
    {
        if (layer == null)
        {
            return 0L;
        }

        long h = 0x9E3779B97F4A7C15L;

        h = mix(h, layer.textMode == null ? 0 : layer.textMode);
        h = mix(h, layer.textRandom == null ? 0F : layer.textRandom);
        h = mix(h, layer.textRandomSeed == null ? 0 : layer.textRandomSeed);
        h = mix(h, layer.textProgress == null ? 1F : layer.textProgress);
        h = mix(h, layer.textCounterOffset == null ? 0F : layer.textCounterOffset);
        h = mix(h, layer.textCounterScale == null ? 1F : layer.textCounterScale);

        return h;
    }

    private static long mix(long hash, long value)
    {
        long h = hash ^ value;

        h *= 0x100000001B3L;
        h ^= h >>> 29;

        return h;
    }

    private static long mix(long hash, float value)
    {
        return mix(hash, Float.floatToIntBits(value));
    }

    private static long mix(long hash, int value)
    {
        return mix(hash, (long) value);
    }
}
