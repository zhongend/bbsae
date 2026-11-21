package dev.bbsae.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tracking is authored in composition pixels, for the default atlas and imported fonts alike. */
public class TextTrackingUnitsTest
{
    @Test
    public void defaultBitmapFontKeepsTrackingInCompositionPixels()
    {
        float fontSize = 72F;
        float fontHeight = 7F; // BBS' default bitmap renderer (fontHeight - 2)
        float layoutUnits = LayerContentRenderer.trackingInFontUnits(9F, fontSize, fontHeight);
        float renderedPixels = layoutUnits * (fontSize / fontHeight);

        assertEquals(9F, renderedPixels, 0.0001F,
            "9 tracking units must be 9 composition pixels, not 9 font cells");
    }

    @Test
    public void importedFontKeepsTrackingInCompositionPixels()
    {
        float fontSize = 72F;
        float fontHeight = 64F; // an oversampled imported font's ascent
        float layoutUnits = LayerContentRenderer.trackingInFontUnits(9F, fontSize, fontHeight);
        float renderedPixels = layoutUnits * (fontSize / fontHeight);

        assertEquals(9F, renderedPixels, 0.0001F);
    }

    @Test
    public void zeroAndNegativeTrackingStayProportional()
    {
        assertEquals(0F, LayerContentRenderer.trackingInFontUnits(0F, 72F, 7F), 0.0001F);
        assertEquals(-5F, LayerContentRenderer.trackingInFontUnits(-5F, 72F, 7F) * (72F / 7F), 0.0001F);
    }
}
