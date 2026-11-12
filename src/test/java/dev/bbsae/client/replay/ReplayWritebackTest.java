package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay placement write rules, tested through the channel interface so no film, BBS value
 * system or GL context is needed: all three position axes are keyed together, and a turn writes
 * {@code yaw = -y} plus the head/body offsets.
 */
public class ReplayWritebackTest
{
    private static final class RecordingChannel implements ReplayWriteback.Channel
    {
        private final Map<Float, Double> values = new LinkedHashMap<>();

        @Override
        public void insert(float tick, double value)
        {
            this.values.put(tick, value);
        }

        double at(float tick)
        {
            return this.values.get(tick);
        }

        int count()
        {
            return this.values.size();
        }
    }

    @Test
    public void aPositionWriteKeysAllThreeAxesAtTheTick()
    {
        RecordingChannel x = new RecordingChannel();
        RecordingChannel y = new RecordingChannel();
        RecordingChannel z = new RecordingChannel();

        ReplayWriteback.position(42F, 1.5D, 64D, -3D, x, y, z);

        assertEquals(1, x.count());
        assertEquals(1, y.count());
        assertEquals(1, z.count());
        assertEquals(1.5D, x.at(42F));
        assertEquals(64D, y.at(42F));
        assertEquals(-3D, z.at(42F));
    }

    @Test
    public void aTurnWritesTheNegatedYawAndKeepsTheHeadBodyOffsets()
    {
        RecordingChannel yaw = new RecordingChannel();
        RecordingChannel head = new RecordingChannel();
        RecordingChannel body = new RecordingChannel();
        RecordingChannel pitch = new RecordingChannel();

        ReplayWriteback.rotation(10F, 25D, 30D, 5D, -10D, yaw, head, body, pitch);

        assertEquals(-30D, yaw.at(10F), 0.0001D);
        assertEquals(-25D, head.at(10F), 0.0001D);
        assertEquals(-40D, body.at(10F), 0.0001D);
        assertEquals(25D, pitch.at(10F), 0.0001D);
    }

    @Test
    public void aSecondWriteAtAnotherTickKeepsBothKeys()
    {
        RecordingChannel x = new RecordingChannel();
        RecordingChannel y = new RecordingChannel();
        RecordingChannel z = new RecordingChannel();

        ReplayWriteback.position(0F, 0D, 0D, 0D, x, y, z);
        ReplayWriteback.position(20F, 5D, 6D, 7D, x, y, z);

        assertEquals(2, x.count());
        assertEquals(0D, x.at(0F));
        assertEquals(5D, x.at(20F));
        assertTrue(y.count() == 2 && z.count() == 2);
    }
}
