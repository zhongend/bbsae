package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * New text layers use a readable default letter spacing.
 *
 * <p>The old default was 0, which made a freshly created text layer look cramped next to BBS'
 * defaults and against the imported fonts people usually use. Nine composition pixels is the
 * value the text inspector starts at and what the user asked for.</p>
 */
public class TextSpacingDefaultTest
{
    @Test
    public void newTextLayersDefaultToNineUnitsOfTracking()
    {
        CompLayer layer = new CompLayer("text", "Text", "text");

        assertEquals(9F, layer.tracking, 0.0001F);
        assertEquals(9F, layer.trackingAt(0F), 0.0001F,
            "a layer without a tracking channel must use the default");
    }

    @Test
    public void explicitTrackingStillWins()
    {
        CompLayer layer = new CompLayer("text", "Text", "text");

        layer.tracking = 24F;

        assertEquals(24F, layer.trackingAt(0F), 0.0001F);
    }

    @Test
    public void defaultSpacingIsNotNegativeOrExtreme()
    {
        CompLayer layer = new CompLayer("text", "Text", "text");

        assertTrue(layer.tracking > 0F && layer.tracking <= 100F,
            "default tracking must be a small positive gap; got " + layer.tracking);
    }
}
