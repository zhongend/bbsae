package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text content effects are data-space transforms of the layer's string. Keeping them pure makes
 * them cheap to test and independent of font rendering.
 */
public class TextContentTest
{
    private static CompLayer textLayer(String text)
    {
        CompLayer layer = new CompLayer();

        layer.text = text;

        return layer;
    }

    @Test
    public void missingFieldsKeepTheOriginalText()
    {
        CompLayer layer = textLayer("你好 Alight");

        assertEquals("你好 Alight", TextContent.resolve(layer, 12.5F, 30, 375));
    }

    @Test
    public void timecodeFormatsHoursMinutesSecondsAndFrames()
    {
        assertEquals("00:00:00:00", TextContent.timecode(0F, 30));
        assertEquals("00:01:05:15", TextContent.timecode(65.5F, 30));
        assertEquals("01:00:00:00", TextContent.timecode(3600F, 30));
    }

    @Test
    public void timecodeModeIgnoresTheRawString()
    {
        CompLayer layer = textLayer("ignored");

        layer.textMode = 1;

        assertEquals("00:00:02:00", TextContent.resolve(layer, 2F, 30, 60));
    }

    @Test
    public void counterUsesOffsetAndScale()
    {
        CompLayer layer = textLayer("ignored");

        layer.textMode = 2;
        layer.textCounterOffset = 100F;
        layer.textCounterScale = 2F;

        assertEquals("105", TextContent.resolve(layer, 2.4F, 30, 72));
    }

    @Test
    public void revealCountsCodePointsNotUtf16Units()
    {
        assertEquals("", TextContent.reveal("中文ABC", 0F));
        assertEquals("中文A", TextContent.reveal("中文ABC", 0.5F));
        assertEquals("中文ABC", TextContent.reveal("中文ABC", 1F));
    }

    @Test
    public void randomGlyphsAreDeterministicPerFrame()
    {
        CompLayer layer = textLayer("HELLO");

        layer.textRandom = 1F;
        layer.textRandomSeed = 7;

        String first = TextContent.resolve(layer, 0F, 30, 10);
        String again = TextContent.resolve(layer, 0F, 30, 10);
        String next = TextContent.resolve(layer, 0F, 30, 11);

        assertEquals(first, again, "same frame and seed must scramble identically");
        assertNotEquals(first, next, "a different frame should produce different glyphs");
        assertEquals(5, first.length());
    }

    @Test
    public void randomAmountZeroLeavesTextUntouched()
    {
        CompLayer layer = textLayer("HELLO");

        layer.textRandom = 0F;

        assertEquals("HELLO", TextContent.resolve(layer, 0F, 30, 10));
    }

    @Test
    public void progressAndScrambleCompose()
    {
        CompLayer layer = textLayer("ABCDEF");

        layer.textProgress = 0.5F;
        layer.textRandom = 0.5F;
        layer.textRandomSeed = 3;

        String result = TextContent.resolve(layer, 0F, 30, 4);

        assertEquals(3, result.length());
        assertTrue(result.chars().allMatch(ch -> ch == ' ' || !Character.isLowerCase(ch)));
    }
}
