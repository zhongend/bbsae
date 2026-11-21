package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 3D text raster must reserve room for the whole unwrapped line, not only the layer box.
 * The estimate is deliberately conservative: excess is transparent padding, a shortfall cuts ink.
 */
public class TextLayerBoundsTest
{
    @Test
    public void cjkTakesAWiderAdvanceThanLatin()
    {
        float cjk = CompLayerSourceCache.estimatedTextWidth("中文中文", 100F, 0F);
        float latin = CompLayerSourceCache.estimatedTextWidth("abcd", 100F, 0F);

        assertTrue(cjk > latin, "four CJK glyphs must be wider than four lowercase Latin glyphs");
        assertTrue(cjk >= 4 * 95F, "CJK estimate is too narrow: " + cjk);
    }

    @Test
    public void mixedTextCoversBothScripts()
    {
        float width = CompLayerSourceCache.estimatedTextWidth("中文ABC", 80F, 6F);

        assertTrue(width >= 2 * 78F + 3 * 49F,
            "mixed text estimate must cover CJK (one em) and Latin: " + width);
        assertTrue(width >= 6F * 2F, "stroke must be reserved on both sides");
    }

    @Test
    public void emptyTextStillHasAUsableRaster()
    {
        assertTrue(CompLayerSourceCache.estimatedTextWidth("", 100F, 4F) >= 12F);
    }

    @Test
    public void trackingIsReservedBetweenGlyphs()
    {
        float plain = CompLayerSourceCache.estimatedTextWidth("abcde", 80F, 0F);
        float spaced = CompLayerSourceCache.estimatedTextWidth("abcde", 80F, 0F, 9F);

        assertTrue(spaced >= plain + 4F * 9F,
            "five glyphs need four 9 px gaps: plain=" + plain + " spaced=" + spaced);
    }

    @Test
    public void widestLineWinsWithTracking()
    {
        float single = CompLayerSourceCache.estimatedTextWidth("AAAA", 80F, 0F, 9F);
        float multi = CompLayerSourceCache.estimatedTextWidth("A\nAAAA", 80F, 0F, 9F);

        assertTrue(multi >= single,
            "a newline must not be summed into the line width; the widest line is used");
    }

    @Test
    public void negativeTrackingDoesNotMakeTheEstimateNarrowerThanInk()
    {
        float plain = CompLayerSourceCache.estimatedTextWidth("abcd", 80F, 0F);
        float tightened = CompLayerSourceCache.estimatedTextWidth("abcd", 80F, 0F, -20F);

        assertTrue(tightened >= plain, "a negative gap must not shrink the safety estimate");
    }
}
