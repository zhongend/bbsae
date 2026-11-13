package dev.bbsae.client.replay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calibration knobs are the plan's R4 escape hatch: if the real-machine comparison shows the
 * models facing the wrong way or at the wrong scale, a -D property adjusts it without a rebuild.
 * These tests pin the defaults and the safe parsing/clamping, because a typo in a calibration
 * property must not silently move the whole scene.
 */
public class ReplayCalibrationTest
{
    @AfterEach
    public void clearProperties()
    {
        System.clearProperty(ReplayCalibration.PIXELS_PER_BLOCK);
        System.clearProperty(ReplayCalibration.FLIP_Z);
        System.clearProperty(ReplayCalibration.YAW_OFFSET);
        System.clearProperty(ReplayCalibration.PITCH_OFFSET);
        System.clearProperty(ReplayCalibration.CULL_THRESHOLD);
        System.clearProperty(ReplayCalibration.CULL_RADIUS);
        System.clearProperty(ReplayCalibration.FRAME_MIN_DISTANCE);
    }

    @Test
    public void theDefaultsAreTheDocumentedOnes()
    {
        assertEquals(64F, ReplayCalibration.pixelsPerBlock(), 0.0001F);
        assertFalse(ReplayCalibration.flipZ());
        assertEquals(180F, ReplayCalibration.yawOffset(), 0.0001F);
        assertEquals(0F, ReplayCalibration.pitchOffset(), 0.0001F);
        assertEquals(32, ReplayCalibration.cullThreshold());
        assertEquals(3F, ReplayCalibration.cullRadius(), 0.0001F);
        assertEquals(600F, ReplayCalibration.frameMinDistance(), 0.0001F);
    }

    @Test
    public void propertiesOverrideTheDefaults()
    {
        System.setProperty(ReplayCalibration.PIXELS_PER_BLOCK, "32.5");
        System.setProperty(ReplayCalibration.FLIP_Z, "false");
        System.setProperty(ReplayCalibration.YAW_OFFSET, "0");
        System.setProperty(ReplayCalibration.PITCH_OFFSET, "-90");
        System.setProperty(ReplayCalibration.CULL_THRESHOLD, "200");
        System.setProperty(ReplayCalibration.CULL_RADIUS, "1.5");
        System.setProperty(ReplayCalibration.FRAME_MIN_DISTANCE, "900");

        assertEquals(32.5F, ReplayCalibration.pixelsPerBlock(), 0.0001F);
        assertFalse(ReplayCalibration.flipZ());
        assertEquals(0F, ReplayCalibration.yawOffset(), 0.0001F);
        assertEquals(-90F, ReplayCalibration.pitchOffset(), 0.0001F);
        assertEquals(200, ReplayCalibration.cullThreshold());
        assertEquals(1.5F, ReplayCalibration.cullRadius(), 0.0001F);
        assertEquals(900F, ReplayCalibration.frameMinDistance(), 0.0001F);
    }

    @Test
    public void theAutoFrameDistanceScalesWithTheCompositionPixelDensity()
    {
        /* 64 px/block (1080p) keeps the historic ~512 px editing distance; a 320x180 comp at
         * 22 px/block must not leave a 40 px actor 600 px away. */
        assertEquals(512F, ReplayCalibration.frameMinDistance(64F), 0.0001F);
        assertEquals(176F, ReplayCalibration.frameMinDistance(22F), 0.0001F);
        assertEquals(80F, ReplayCalibration.frameMinDistance(1F), 0.0001F);
        assertEquals(1600F, ReplayCalibration.frameMinDistance(4096F), 0.0001F);
    }

    @Test
    public void anExplicitFrameDistanceStillWins()
    {
        System.setProperty(ReplayCalibration.FRAME_MIN_DISTANCE, "900");

        assertEquals(900F, ReplayCalibration.frameMinDistance(22F), 0.0001F);
    }

    @Test
    public void invalidValuesFallBackInsteadOfBreakingTheScene()
    {
        System.setProperty(ReplayCalibration.PIXELS_PER_BLOCK, "not a number");
        System.setProperty(ReplayCalibration.FLIP_Z, "maybe");
        System.setProperty(ReplayCalibration.CULL_THRESHOLD, "-4");

        assertEquals(64F, ReplayCalibration.pixelsPerBlock(), 0.0001F);
        assertFalse(ReplayCalibration.flipZ());
        assertEquals(0, ReplayCalibration.cullThreshold());
    }

    @Test
    public void outOfRangeValuesAreClamped()
    {
        System.setProperty(ReplayCalibration.PIXELS_PER_BLOCK, "999999");
        System.setProperty(ReplayCalibration.YAW_OFFSET, "9999");

        assertEquals(4096F, ReplayCalibration.pixelsPerBlock(), 0.0001F);
        assertEquals(360F, ReplayCalibration.yawOffset(), 0.0001F);
    }

    @Test
    public void theSceneSpaceDefaultsFollowTheCalibrationProperties()
    {
        System.setProperty(ReplayCalibration.PIXELS_PER_BLOCK, "16");
        System.setProperty(ReplayCalibration.FLIP_Z, "false");

        ReplaySceneSpace space = new ReplaySceneSpace();

        assertEquals(16F, space.pixelsPerBlock, 0.0001F);
        assertFalse(space.flipZ);

        System.clearProperty(ReplayCalibration.PIXELS_PER_BLOCK);
        System.clearProperty(ReplayCalibration.FLIP_Z);

        ReplaySceneSpace defaults = new ReplaySceneSpace();

        assertEquals(64F, defaults.pixelsPerBlock, 0.0001F);
        assertFalse(defaults.flipZ);
    }

    @Test
    public void thePackageLevelParsersAreStrict()
    {
        System.setProperty("bbsae.test.float", "12.25");
        System.setProperty("bbsae.test.bool", "TRUE");

        try
        {
            assertEquals(12.25F, ReplayCalibration.floatProperty("bbsae.test.float", 1F, 0F, 100F), 0.0001F);
            assertTrue(ReplayCalibration.booleanProperty("bbsae.test.bool", false));
            assertEquals(5F, ReplayCalibration.floatProperty("bbsae.test.missing", 5F, 0F, 100F), 0.0001F);
        }
        finally
        {
            System.clearProperty("bbsae.test.float");
            System.clearProperty("bbsae.test.bool");
        }
    }
}
