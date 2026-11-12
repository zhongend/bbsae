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
            Double value = this.values.get(tick);

            return value == null ? Double.NaN : value;
        }

        int count()
        {
            return this.values.size();
        }

        Map<Float, Double> snapshot()
        {
            return new LinkedHashMap<>(this.values);
        }

        void restore(Map<Float, Double> snapshot)
        {
            this.values.clear();
            this.values.putAll(snapshot);
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

    /** What the gizmo's "one gesture = one key at the playhead" contract writes, all at one tick. */
    @Test
    public void aWholePlacementWritesAllSevenChannelsAtTheSameTick()
    {
        RecordingChannel x = new RecordingChannel();
        RecordingChannel y = new RecordingChannel();
        RecordingChannel z = new RecordingChannel();
        RecordingChannel yaw = new RecordingChannel();
        RecordingChannel head = new RecordingChannel();
        RecordingChannel body = new RecordingChannel();
        RecordingChannel pitch = new RecordingChannel();

        ReplayWriteback.placement(7F, 1D, 2D, 3D, 15D, 90D, 5D, -10D,
            x, y, z, yaw, head, body, pitch);

        assertEquals(1D, x.at(7F), 0.0001D);
        assertEquals(2D, y.at(7F), 0.0001D);
        assertEquals(3D, z.at(7F), 0.0001D);
        assertEquals(-90D, yaw.at(7F), 0.0001D);
        assertEquals(-85D, head.at(7F), 0.0001D);
        assertEquals(-100D, body.at(7F), 0.0001D);
        assertEquals(15D, pitch.at(7F), 0.0001D);
        assertEquals(7, x.count() + y.count() + z.count() + yaw.count() + head.count() + body.count() + pitch.count(),
            "exactly one key per channel at the tick");
    }

    @Test
    public void writingThePlayheadAgainOverwritesThatKeyInsteadOfAppending()
    {
        RecordingChannel x = new RecordingChannel();
        RecordingChannel y = new RecordingChannel();
        RecordingChannel z = new RecordingChannel();

        ReplayWriteback.placement(0F, 0D, 0D, 0D, 0D, 0D, 0D, 0D,
            x, y, z, new RecordingChannel(), new RecordingChannel(), new RecordingChannel(), new RecordingChannel());
        ReplayWriteback.placement(0F, 4D, 5D, 6D, 30D, 45D, 0D, 0D,
            x, y, z, new RecordingChannel(), new RecordingChannel(), new RecordingChannel(), new RecordingChannel());

        assertEquals(1, x.count(), "a key at the same tick is replaced, not duplicated");
        assertEquals(4D, x.at(0F), 0.0001D);
        assertEquals(5D, y.at(0F), 0.0001D);
        assertEquals(6D, z.at(0F), 0.0001D);
    }

    /**
     * Undo/redo consistency at the writeback layer: one gesture writes, undo restores the snapshot
     * taken before it, redo re-applies the same values. (The real film undo swaps a JSON snapshot
     * in {@code UIFilmPanel}; that path needs a client and is listed as a real-machine item.)
     */
    @Test
    public void undoRestoresThePreGestureValuesAndRedoReappliesThem()
    {
        RecordingChannel x = new RecordingChannel();
        RecordingChannel y = new RecordingChannel();
        RecordingChannel z = new RecordingChannel();
        RecordingChannel yaw = new RecordingChannel();

        ReplayWriteback.placement(0F, 1D, 2D, 3D, 0D, 10D, 0D, 0D,
            x, y, z, yaw, new RecordingChannel(), new RecordingChannel(), new RecordingChannel());

        Map<Float, Double> beforeX = x.snapshot();
        Map<Float, Double> beforeYaw = yaw.snapshot();

        ReplayWriteback.placement(20F, 9D, 9D, 9D, 45D, 90D, 0D, 0D,
            x, y, z, yaw, new RecordingChannel(), new RecordingChannel(), new RecordingChannel());

        /* undo */
        x.restore(beforeX);
        yaw.restore(beforeYaw);

        assertEquals(1, x.count(), "the gesture's key is gone after undo");
        assertEquals(1D, x.at(0F), 0.0001D);
        assertEquals(10D, yaw.at(0F) * -1D, 0.0001D, "yaw was written as -yaw");

        /* redo */
        ReplayWriteback.placement(20F, 9D, 9D, 9D, 45D, 90D, 0D, 0D,
            x, y, z, yaw, new RecordingChannel(), new RecordingChannel(), new RecordingChannel());

        assertEquals(2, x.count());
        assertEquals(9D, x.at(20F), 0.0001D);
        assertEquals(-90D, yaw.at(20F), 0.0001D);
    }
}
