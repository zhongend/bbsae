package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The replay page's bottom timeline must never overlap the list/properties body. */
public class ReplayTimelineLayoutTest
{
    @Test
    public void timelineLeavesTheBodyUsableAtEveryHeight()
    {
        for (boolean embedded : new boolean[] {false, true})
        {
            for (int page : new int[] {120, 160, 200, 240, 300, 400, 540, 720, 900, 1080})
            {
                int header = embedded ? 0 : 26;
                int available = page - header;
                int timeline = UIReplayModePanel.timelineHeightFor(page, embedded);
                int body = available - timeline;

                assertTrue(timeline >= 80, "timeline too small at " + page + " embedded=" + embedded);
                assertTrue(timeline <= 480, "timeline too tall at " + page);
                assertTrue(body >= 0, "timeline overlaps the body at " + page + " embedded=" + embedded
                    + " (timeline=" + timeline + ", body=" + body + ")");

                if (available >= 260)
                {
                    assertTrue(body >= 180, "body squeezed at " + page + " embedded=" + embedded
                        + " (body=" + body + ")");
                }
            }
        }
    }

    @Test
    public void normalWindowKeepsTheDesignedRatio()
    {
        int timeline = UIReplayModePanel.timelineHeightFor(900, true);

        assertTrue(timeline >= 300 && timeline <= 360, "900px page timeline was " + timeline);
    }
}
