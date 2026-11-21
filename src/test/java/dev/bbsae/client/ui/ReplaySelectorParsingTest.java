package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The top-bar project → replay selector parses its two row spellings without name lookups. */
public class ReplaySelectorParsingTest
{
    @Test
    public void filmIdIsExtractedFromProjectRows()
    {
        assertEquals("my-film", UICompositorPanel.filmIdFromSelector("影片 · my-film"));
        assertEquals("my-film", UICompositorPanel.filmIdFromSelector("影片 · my-film（当前）"));
        assertEquals("", UICompositorPanel.filmIdFromSelector("bad row"));
        assertEquals("", UICompositorPanel.filmIdFromSelector(null));
    }

    @Test
    public void replayIndexIsZeroBasedAndNameIndependent()
    {
        assertEquals(0, UICompositorPanel.replayIndexFromSelector("回放 · 1 · Alice"));
        assertEquals(4, UICompositorPanel.replayIndexFromSelector("回放 · 5 · Same Name"));
        assertEquals(4, UICompositorPanel.replayIndexFromSelector("回放 · 5"));
        assertEquals(1, UICompositorPanel.replayIndexFromSelector("图层 · 2 · 主合成"));
        assertEquals(-1, UICompositorPanel.replayIndexFromSelector("回放 · x · Alice"));
        assertEquals(-1, UICompositorPanel.replayIndexFromSelector(null));
    }
}
