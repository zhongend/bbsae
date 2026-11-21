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
    public void freshBbsCompositionClipsAreTreatedAsUnbound()
    {
        assertEquals(true, UICompositorPanel.isDefaultClipBinding("demo", "main", "Compositor"));
        assertEquals(true, UICompositorPanel.isDefaultClipBinding("demo", "main", ""));
        assertEquals(false, UICompositorPanel.isDefaultClipBinding("my-project", "main", "Compositor"),
            "a real project id must be honoured even if the clip title is still default");
        assertEquals(false, UICompositorPanel.isDefaultClipBinding("demo", "custom", "Compositor"),
            "only the factory demo/main pair is the empty binding");
        assertEquals(false, UICompositorPanel.isDefaultClipBinding("demo", "main", "Demo / Main"),
            "after an explicit binding the title marks the layer as bound");
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
