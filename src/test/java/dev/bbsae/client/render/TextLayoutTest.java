package dev.bbsae.client.render;

import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text layout engine without a client or a GL context.
 *
 * <p>Three properties are pinned here:</p>
 * <ul>
 *   <li>the handwritten wrap produces exactly the line strings the old
 *       {@code text.split("\\n", -1)} / {@code raw.split(" ")} / character-overflow algorithm
 *       produced — the performance rewrite must not move a single line break;</li>
 *   <li>the LRU layout cache is keyed by every parameter that changes line breaks, so a font,
 *       size, tracking or wrap change is a miss rather than a stale reuse;</li>
 *   <li>run planning only merges glyphs into one drawn string when the merged advances are
 *       provably pixel-identical to the old per-glyph loop.</li>
 * </ul>
 */
public class TextLayoutTest
{
    private static final float EPSILON = 0.0001F;

    /**
     * Deterministic font oracle: proportional Latin (w/W wider), 12 px CJK, 14 px emoji, 4 px
     * space. No renderer or texture atlas is touched.
     */
    private static final LayerContentRenderer.GlyphAdvance FIXED = codePoint ->
    {
        if (codePoint == ' ')
        {
            return 4F;
        }

        if (codePoint >= 'a' && codePoint <= 'z')
        {
            return codePoint == 'w' ? 9F : 6F;
        }

        if (codePoint >= 'A' && codePoint <= 'Z')
        {
            return codePoint == 'W' ? 9F : 6F;
        }

        if (codePoint >= '0' && codePoint <= '9')
        {
            return 7F;
        }

        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
        {
            return 12F;
        }

        if (codePoint > 0xFFFF)
        {
            return 14F;
        }

        return 5F;
    };

    /* ------------------------------------------------------------ legacy oracle */

    /** Verbatim port of the old {@code lineWidth()}, using the same advance oracle. */
    private static float legacyWidth(String line, float tracking, LayerContentRenderer.GlyphAdvance advance)
    {
        if (line.isEmpty())
        {
            return 0F;
        }

        float width = 0F;
        int count = 0;

        for (int i = 0; i < line.length(); )
        {
            int codePoint = line.codePointAt(i);

            width += advance.advance(codePoint);
            count += 1;
            i += Character.charCount(codePoint);
        }

        return width + tracking * Math.max(0, count - 1);
    }

    /** Verbatim port of the old {@code layout()} (regex split, string concatenation, O(n²)). */
    private static List<String> legacyLayout(String text, boolean wrap, float maxWidth, float tracking,
                                             LayerContentRenderer.GlyphAdvance advance)
    {
        List<String> lines = new ArrayList<>();

        for (String raw : text.split("\\n", -1))
        {
            if (!wrap)
            {
                lines.add(raw);
                continue;
            }

            String[] words = raw.split(" ");
            StringBuilder line = new StringBuilder();

            for (String word : words)
            {
                String candidate = line.isEmpty() ? word : line + " " + word;

                if (!line.isEmpty() && legacyWidth(candidate, tracking, advance) > maxWidth)
                {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
                else
                {
                    line.setLength(0);
                    line.append(candidate);
                }

                if (legacyWidth(line.toString(), tracking, advance) > maxWidth)
                {
                    String overflow = line.toString();
                    StringBuilder chunk = new StringBuilder();

                    line.setLength(0);

                    for (int i = 0; i < overflow.length(); i++)
                    {
                        char c = overflow.charAt(i);

                        if (chunk.length() > 0 && legacyWidth(chunk.toString() + c, tracking, advance) > maxWidth)
                        {
                            lines.add(chunk.toString());
                            chunk.setLength(0);
                        }

                        chunk.append(c);
                    }

                    line.append(chunk);
                }
            }

            lines.add(line.toString());
        }

        return lines;
    }

    private static int legacyGlyphCount(List<String> lines)
    {
        int count = 0;

        for (String line : lines)
        {
            for (int i = 0; i < line.length(); )
            {
                int codePoint = line.codePointAt(i);

                if (!Character.isWhitespace(codePoint))
                {
                    count += 1;
                }

                i += Character.charCount(codePoint);
            }
        }

        return count;
    }

    /* ------------------------------------------------------------ wrap parity */

    @Test
    public void wrapMatchesTheLegacyAlgorithmExactly()
    {
        String[] texts = {
            "", "Hello world", "a  b", " a", "a ", " a ", "a b ", "\n", "\n\n", "a\n",
            "\na", "a\n\nb", "a\r\nb", "中文没有空格但有宽度", "word word word word",
            "abc def ghi jkl", "😀😀 a", "a\tb c", "supercalifragilisticexpialidocious",
            "  leading spaces", "trailing spaces   ", "one two  three   four"
        };
        float[] widths = { -10F, 0F, 1F, 7F, 12F, 20F, 30F, 50F, 100F, 500F };
        float[] trackings = { 0F, 0.5F, 2F, -1F };

        for (String text : texts)
        {
            for (float maxWidth : widths)
            {
                for (float tracking : trackings)
                {
                    for (boolean wrap : new boolean[] { true, false })
                    {
                        String message = "text=[" + text.replace("\n", "\\n") + "] wrap=" + wrap
                            + " max=" + maxWidth + " tracking=" + tracking;
                        List<String> expected = legacyLayout(text, wrap, maxWidth, tracking, FIXED);
                        List<String> actual = LayerContentRenderer.lineStrings(text, wrap, maxWidth, tracking, FIXED);

                        assertEquals(expected, actual, message);
                    }
                }
            }
        }
    }

    @Test
    public void wrapMatchesTheLegacyAlgorithmOnRandomText()
    {
        Random random = new Random(0x5EEDL);
        String alphabet = "ab cwW012\u4e2d\u6587\n\t.";

        for (int round = 0; round < 250; round++)
        {
            int length = random.nextInt(60);
            StringBuilder text = new StringBuilder(length);

            for (int i = 0; i < length; i++)
            {
                int index = random.nextInt(alphabet.length());

                text.append(alphabet.charAt(index));

                if (alphabet.charAt(index) > 0xD7FF)
                {
                    /* The alphabet is BMP-only; keep this branch explicit for readability. */
                    continue;
                }
            }

            float maxWidth = random.nextInt(120) - 10F;
            float tracking = random.nextFloat() * 2F;
            boolean wrap = random.nextBoolean();
            String value = text.toString();
            String message = "text=[" + value.replace("\n", "\\n") + "] wrap=" + wrap
                + " max=" + maxWidth + " tracking=" + tracking;

            assertEquals(legacyLayout(value, wrap, maxWidth, tracking, FIXED),
                LayerContentRenderer.lineStrings(value, wrap, maxWidth, tracking, FIXED), message);
        }
    }

    @Test
    public void wrapIsBitIdenticalWithFractionalAdvanceWidths()
    {
        /* Fallback glyphs are drawn at customHeight/defaultHeight, a fractional scale, so their
         * advances are fractional floats. The wrap decision must round exactly like the old
         * "sum the whole candidate from scratch" code, not merely approximately. */
        LayerContentRenderer.GlyphAdvance fractional = codePoint ->
        {
            if (codePoint == ' ')
            {
                return 4.35F;
            }

            return 3.7F + (codePoint % 7) * 1.3F;
        };
        Random random = new Random(0xF10A7L);
        String alphabet = "abcwWXY 012";

        for (int round = 0; round < 300; round++)
        {
            StringBuilder text = new StringBuilder();

            for (int i = 0; i < random.nextInt(50); i++)
            {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }

            float maxWidth = random.nextFloat() * 120F;
            float tracking = random.nextFloat() * 1.5F;
            String value = text.toString();

            assertEquals(legacyLayout(value, true, maxWidth, tracking, fractional),
                LayerContentRenderer.lineStrings(value, true, maxWidth, tracking, fractional),
                "fractional widths, text=[" + value + "] max=" + maxWidth + " tracking=" + tracking);
        }
    }

    @Test
    public void splitHelpersMatchJavaSplit()
    {
        Random random = new Random(0xC0FFEEL);
        String[] alphabets = { "ab ", "ab\n", "abc \n" };

        for (String alphabet : alphabets)
        {
            for (int round = 0; round < 400; round++)
            {
                StringBuilder text = new StringBuilder(random.nextInt(14));

                for (int i = 0; i < 14; i++)
                {
                    if (random.nextBoolean())
                    {
                        text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                    }
                }

                String value = text.toString();

                assertEquals(Arrays.asList(value.split("\n", -1)),
                    LayerContentRenderer.splitNewlines(value), "newline split of [" + value + "]");
                assertEquals(Arrays.asList(value.split(" ")),
                    Arrays.asList(LayerContentRenderer.splitSpaces(value)), "space split of [" + value + "]");
            }
        }
    }

    /* ------------------------------------------------------------ counts and widths */

    @Test
    public void layoutReportsTheSameLineAndGlyphCounts()
    {
        LayerContentRenderer.TextLayout layout = LayerContentRenderer.layoutText("ab\n\ncd", false, 100F, 0F, FIXED);

        assertEquals(3, layout.lines.length);
        assertEquals("ab", layout.lines[0].text);
        assertEquals("", layout.lines[1].text);
        assertEquals("cd", layout.lines[2].text);
        assertEquals(4, layout.glyphCount, "blank lines contribute no glyphs");
        assertEquals(12F, layout.width, EPSILON);

        LayerContentRenderer.TextLayout spaced = LayerContentRenderer.layoutText("a b", false, 100F, 0F, FIXED);

        assertEquals(1, spaced.lines.length);
        assertEquals(2, spaced.glyphCount, "the space is layout, not a drawn glyph");
        assertEquals(16F, spaced.width, EPSILON);
        assertEquals(16F, spaced.lines[0].width, EPSILON);

        LayerContentRenderer.TextLayout cjk = LayerContentRenderer.layoutText("中文", false, 100F, 0F, FIXED);

        assertEquals(2, cjk.glyphCount);
        assertEquals(24F, cjk.width, EPSILON);

        LayerContentRenderer.TextLayout wrapped = LayerContentRenderer.layoutText("aa bb cc", true, 20F, 0F, FIXED);

        assertEquals(Arrays.asList("aa", "bb", "cc"), strings(wrapped));
        assertEquals(6, wrapped.glyphCount);
    }

    @Test
    public void layoutMatchesLegacyGlyphCountsOnRandomText()
    {
        Random random = new Random(42L);
        String alphabet = "ab c\n\u4e2d";

        for (int round = 0; round < 120; round++)
        {
            StringBuilder text = new StringBuilder();

            for (int i = 0; i < random.nextInt(40); i++)
            {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }

            float maxWidth = random.nextInt(80);
            float tracking = random.nextFloat();

            LayerContentRenderer.TextLayout layout = LayerContentRenderer.layoutText(
                text.toString(), true, maxWidth, tracking, FIXED);

            assertEquals(legacyGlyphCount(legacyLayout(text.toString(), true, maxWidth, tracking, FIXED)),
                layout.glyphCount, "glyph count of [" + text + "]");
        }
    }

    private static List<String> strings(LayerContentRenderer.TextLayout layout)
    {
        List<String> lines = new ArrayList<>();

        for (LayerContentRenderer.TextLine line : layout.lines)
        {
            lines.add(line.text);
        }

        return lines;
    }

    /* ------------------------------------------------------------ layout cache */

    private static LayerContentRenderer.LayoutKey key(String text, String font, long fontId,
                                                      float size, float tracking, float width,
                                                      boolean wrap, int align)
    {
        return new LayerContentRenderer.LayoutKey(text, font, fontId, 99L, size, tracking, width, wrap, align, 0);
    }

    @Test
    public void layoutCacheHitsOnIdenticalParametersAndMissesOnAnyChange()
    {
        LayerContentRenderer.LruCache<LayerContentRenderer.LayoutKey, String> cache =
            new LayerContentRenderer.LruCache<>(4);
        LayerContentRenderer.LayoutKey key = key("Hello", "Arial", 7L, 48F, 1F, 200F, true, 1);

        assertNull(cache.get(key), "first lookup must be a miss");
        cache.put(key, "layout");

        assertEquals("layout", cache.get(key("Hello", "Arial", 7L, 48F, 1F, 200F, true, 1)),
            "equal parameters must hit");
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());

        assertNull(cache.get(key("Hello", "Arial", 7L, 49F, 1F, 200F, true, 1)), "font size is part of the key");
        assertNull(cache.get(key("Hello", "Arial", 7L, 48F, 2F, 200F, true, 1)), "tracking is part of the key");
        assertNull(cache.get(key("Hello", "Arial", 7L, 48F, 1F, 201F, true, 1)), "wrap width is part of the key");
        assertNull(cache.get(key("Hello", "Arial", 7L, 48F, 1F, 200F, false, 1)), "wrap flag is part of the key");
        assertNull(cache.get(key("Hello", "Arial", 7L, 48F, 1F, 200F, true, 0)), "align is part of the key");
        assertNull(cache.get(key("Hello", "Arial", 8L, 48F, 1F, 200F, true, 1)), "font identity is part of the key");
        assertNull(cache.get(key("Hello", "Times", 7L, 48F, 1F, 200F, true, 1)), "font name is part of the key");
        assertNull(cache.get(key("Hello!", "Arial", 7L, 48F, 1F, 200F, true, 1)), "text is part of the key");

        assertEquals(key, key("Hello", "Arial", 7L, 48F, 1F, 200F, true, 1));
        assertNotEquals(key, key("Hello", "Arial", 7L, 48F, 1F, 200F, true, 0));
    }

    @Test
    public void layoutCacheEvictsTheLeastRecentlyUsedEntry()
    {
        LayerContentRenderer.LruCache<LayerContentRenderer.LayoutKey, String> cache =
            new LayerContentRenderer.LruCache<>(2);
        LayerContentRenderer.LayoutKey first = key("one", "Arial", 1L, 20F, 0F, 50F, true, 1);
        LayerContentRenderer.LayoutKey second = key("two", "Arial", 1L, 20F, 0F, 50F, true, 1);
        LayerContentRenderer.LayoutKey third = key("three", "Arial", 1L, 20F, 0F, 50F, true, 1);

        cache.put(first, "1");
        cache.put(second, "2");
        assertEquals("1", cache.get(first), "touching an entry must refresh it");

        cache.put(third, "3");

        assertEquals(2, cache.size());
        assertEquals("1", cache.get(first), "recently used entry survives");
        assertNull(cache.get(second), "least recently used entry is evicted");
        assertEquals("3", cache.get(third));

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    public void layoutCacheCapacityIsBounded()
    {
        LayerContentRenderer.LruCache<String, String> cache = new LayerContentRenderer.LruCache<>(64);

        for (int i = 0; i < 65; i++)
        {
            cache.put("key-" + i, "value-" + i);
        }

        assertEquals(64, cache.size());
        assertNull(cache.get("key-0"), "the oldest entry is gone");
        assertEquals("value-64", cache.get("key-64"));
    }

    /* ------------------------------------------------------------ run planning */

    private static LayerContentRenderer.Glyph glyph(int codePoint, FontRenderer font, float scale, float advance, float x)
    {
        return new LayerContentRenderer.Glyph(codePoint, LayerContentRenderer.glyphString(codePoint),
            font, scale, advance, x);
    }

    private static List<LayerContentRenderer.Glyph> glyphs(FontRenderer font, String text)
    {
        List<LayerContentRenderer.Glyph> glyphs = new ArrayList<>();
        float x = 0F;

        for (int i = 0; i < text.length(); )
        {
            int codePoint = text.codePointAt(i);
            int width = codePoint == ' ' ? 4 : 6;

            glyphs.add(glyph(codePoint, font, 1F, width, x));
            x += width;
            i += Character.charCount(codePoint);
        }

        return glyphs;
    }

    @Test
    public void runPlanningMergesOnlyWhenTheMeasuredWidthsAgree()
    {
        FontRenderer font = new FontRenderer();
        LayerContentRenderer.WidthProbe honest = (probeFont, text) -> text.length() * 6F;
        LayerContentRenderer.WidthProbe lying = (probeFont, text) -> text.equals("ab") ? 11F : text.length() * 6F;

        List<LayerContentRenderer.GlyphRun> merged = LayerContentRenderer.planRuns(
            glyphs(font, "Hello"), 0F, honest, true);

        assertEquals(1, merged.size());
        assertEquals("Hello", merged.get(0).text);
        assertTrue(merged.get(0).mergeable, "five 6 px glyphs sum to the 30 px whole-string width");
        assertEquals(0F, merged.get(0).x, EPSILON);

        List<LayerContentRenderer.GlyphRun> split = LayerContentRenderer.planRuns(
            glyphs(font, "ab"), 0F, lying, true);

        assertEquals(1, split.size(), "no font/scale change means one run");
        assertEquals(2, split.get(0).glyphs.size());
        assertFalse(split.get(0).mergeable, "a width mismatch falls back to per-glyph drawing");
    }

    @Test
    public void runPlanningBreaksOnTrackingScaleFontAndFormatting()
    {
        FontRenderer first = new FontRenderer();
        FontRenderer second = new FontRenderer();
        LayerContentRenderer.WidthProbe probe = (font, text) -> text.length() * 6F;

        assertFalse(LayerContentRenderer.planRuns(glyphs(first, "ab"), 1F, probe, true).get(0).mergeable,
            "tracking inserts a gap the renderer does not advance, so runs cannot merge");

        List<LayerContentRenderer.Glyph> scaled = new ArrayList<>();

        scaled.add(glyph('a', first, 2F, 12F, 0F));
        scaled.add(glyph('b', first, 2F, 12F, 13F));

        List<LayerContentRenderer.GlyphRun> scaledRuns = LayerContentRenderer.planRuns(scaled, 0F, probe, true);

        assertEquals(2, scaledRuns.size(), "scaled fallback glyphs cannot share one draw");
        assertFalse(scaledRuns.get(0).mergeable);

        List<LayerContentRenderer.Glyph> mixed = new ArrayList<>();

        mixed.add(glyph('a', first, 1F, 6F, 0F));
        mixed.add(glyph('b', second, 1F, 6F, 7F));

        List<LayerContentRenderer.GlyphRun> fontRuns = LayerContentRenderer.planRuns(mixed, 0F, probe, true);

        assertEquals(2, fontRuns.size(), "a font switch starts a new run");
        assertEquals("a", fontRuns.get(0).text);
        assertEquals("b", fontRuns.get(1).text);

        List<LayerContentRenderer.GlyphRun> formatting = LayerContentRenderer.planRuns(
            glyphs(first, "a\u00a7b"), 0F, probe, true);

        assertEquals(3, formatting.size(), "a section sign is drawn alone, exactly like the old loop");
        assertEquals("a", formatting.get(0).text);
        assertEquals("\u00a7", formatting.get(1).text);
        assertFalse(formatting.get(1).mergeable, "a merged section sign would become a formatting code");
        assertEquals("b", formatting.get(2).text);
    }

    @Test
    public void runPlanningKeepsSpacesInsideMergedRuns()
    {
        FontRenderer font = new FontRenderer();
        LayerContentRenderer.WidthProbe probe = (probeFont, text) -> text.length() * 6F;

        List<LayerContentRenderer.Glyph> glyphs = new ArrayList<>();

        glyphs.add(glyph('a', font, 1F, 6F, 0F));
        glyphs.add(glyph(' ', font, 1F, 4F, 6F));
        glyphs.add(glyph('b', font, 1F, 6F, 10F));

        List<LayerContentRenderer.GlyphRun> runs = LayerContentRenderer.planRuns(glyphs, 0F, probe, true);

        assertEquals(1, runs.size());
        assertEquals("a b", runs.get(0).text, "the space advances the renderer exactly like the layout");
        assertTrue(runs.get(0).mergeable);
        assertEquals(10F, runs.get(0).glyphs.get(2).x, EPSILON);
    }

    @Test
    public void glyphStringsAreCachedAndCorrect()
    {
        assertSame(LayerContentRenderer.glyphString('A'), LayerContentRenderer.glyphString('A'));
        assertEquals("A", LayerContentRenderer.glyphString('A'));
        assertEquals("\u4e2d", LayerContentRenderer.glyphString('\u4e2d'));
        assertEquals(new String(Character.toChars(0x1F600)), LayerContentRenderer.glyphString(0x1F600));
    }
}
