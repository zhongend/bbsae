package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Fit" must follow the last layer/audio end, not the composition canvas.
 *
 * <p>The canvas may legitimately carry an empty tail (and old builds inflated it to a film
 * placeholder). The track lane and the keyframe drawer are two views of the same interval, so both
 * must use {@code CompDocument.fitEnd()} and both must anchor the view at time 0.</p>
 */
public class FitContentContractsTest
{
    private static String source(String... path) throws IOException
    {
        Path file = Path.of("src", "main", "java", "dev", "bbsae");

        for (String part : path)
        {
            file = file.resolve(part);
        }

        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    public void theTrackTimelineFitsTheContentEnd() throws IOException
    {
        String timeline = source("client", "ui", "UICompTrackTimeline.java");

        assertTrue(timeline.contains("this.doc().fitEnd()"),
            "fitDuration must be based on the last layer/audio end");
        assertTrue(timeline.contains("applyFit(this.xAxis, this.fitDuration()"),
            "the fitted axis must use fitDuration, not the canvas duration");
        assertFalse(timeline.contains("applyFit(this.xAxis, this.duration()"),
            "using the canvas duration here is the reported 'timeline stretched huge' bug");
    }

    @Test
    public void theKeyframeDrawerFitsTheContentEnd() throws IOException
    {
        String panel = source("client", "ui", "UICompositorPanel.java");

        assertTrue(panel.contains("this.doc.fitEnd()"),
            "fitDurationTicks must use the last layer/audio end");
        assertTrue(panel.contains("fitKeyframeTimeline()"),
            "all keyframe fit entry points must share one helper");
        assertFalse(panel.contains("getXAxis().view(0D, Math.max(1, this.getDurationTicks()))"),
            "the drawer used to fit to the canvas duration; that is the reported bug");
    }

    @Test
    public void everyKeyframeFitAnchorsAtTimeZero() throws IOException
    {
        String panel = source("client", "ui", "UICompositorPanel.java");
        int helper = panel.indexOf("public void fitKeyframeTimeline()");

        assertTrue(helper > 0);
        int end = panel.indexOf("\n    }", helper);
        String body = panel.substring(helper, end);

        assertTrue(body.contains("axis.anchor(0F)"),
            "view() must be anchored at the beginning, otherwise the drawer can show the end only");
        assertTrue(body.contains("axis.view(0D,"),
            "the fitted interval must start at time 0");
    }
}
