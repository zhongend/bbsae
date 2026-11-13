package dev.bbsae.client.replay;

/**
 * The replay viewport's calibration knobs, all overridable by system properties.
 *
 * <p>The comp↔world mapping has a few signs and constants that cannot be proven from unit tests
 * (plan R4): which way a model faces, whether Z is mirrored, and how many composition pixels a
 * block is worth. They are collected here so a real-machine calibration run changes one system
 * property instead of hunting constants across three classes:</p>
 *
 * <pre>
 *   -Dbbsae.replayPixelsPerBlock=64    composition pixels per Minecraft block
 *   -Dbbsae.replayFlipZ=false         true flips Z (diagnostic only; default matches the composition)
 *   -Dbbsae.replayYawOffset=180        added to the viewport yaw when building the MC camera
 *   -Dbbsae.replayPitchOffset=0        added to the viewport pitch when building the MC camera
 *   -Dbbsae.replayCullThreshold=32     films with more replays get frustum culling
 *   -Dbbsae.replayCullRadius=3.0       actor bounding radius in blocks for the culling test
 *   -Dbbsae.replayFrameMinDistance=600 smallest camera distance after the auto-frame
 * </pre>
 *
 * <p>Properties are read lazily (once per frame at most), so a calibration can be re-run by
 * restarting the client with a different {@code -D} without rebuilding.</p>
 */
public final class ReplayCalibration
{
    public static final String PIXELS_PER_BLOCK = "bbsae.replayPixelsPerBlock";
    public static final String FLIP_Z = "bbsae.replayFlipZ";
    public static final String YAW_OFFSET = "bbsae.replayYawOffset";
    public static final String PITCH_OFFSET = "bbsae.replayPitchOffset";
    public static final String CULL_THRESHOLD = "bbsae.replayCullThreshold";
    public static final String CULL_RADIUS = "bbsae.replayCullRadius";
    public static final String FRAME_MIN_DISTANCE = "bbsae.replayFrameMinDistance";

    public static final float DEFAULT_PIXELS_PER_BLOCK = 64F;
    public static final boolean DEFAULT_FLIP_Z = false;
    public static final float DEFAULT_YAW_OFFSET = 180F;
    public static final float DEFAULT_PITCH_OFFSET = 0F;
    public static final int DEFAULT_CULL_THRESHOLD = 32;
    public static final float DEFAULT_CULL_RADIUS = 3F;
    /** Smallest framed camera distance in composition pixels: one 1.8-block actor is ~115 px,
     *  so a bare-bounds frame (a single actor) would otherwise sit inside the model. */
    public static final float DEFAULT_FRAME_MIN_DISTANCE = 600F;

    private ReplayCalibration()
    {}

    public static float pixelsPerBlock()
    {
        return floatProperty(PIXELS_PER_BLOCK, DEFAULT_PIXELS_PER_BLOCK, 1F, 4096F);
    }

    public static boolean flipZ()
    {
        return booleanProperty(FLIP_Z, DEFAULT_FLIP_Z);
    }

    public static float yawOffset()
    {
        return floatProperty(YAW_OFFSET, DEFAULT_YAW_OFFSET, -360F, 360F);
    }

    public static float pitchOffset()
    {
        return floatProperty(PITCH_OFFSET, DEFAULT_PITCH_OFFSET, -180F, 180F);
    }

    public static int cullThreshold()
    {
        int value = Integer.getInteger(CULL_THRESHOLD, DEFAULT_CULL_THRESHOLD);

        return Math.max(0, value);
    }

    public static float cullRadius()
    {
        return floatProperty(CULL_RADIUS, DEFAULT_CULL_RADIUS, 0F, 256F);
    }

    /** Smallest camera distance after auto-framing; see {@link #DEFAULT_FRAME_MIN_DISTANCE}. */
    public static float frameMinDistance()
    {
        return floatProperty(FRAME_MIN_DISTANCE, DEFAULT_FRAME_MIN_DISTANCE, 1F, 1_000_000F);
    }

    /**
     * Scale-aware default for auto-framing a film whose composition has its own pixels-per-block.
     *
     * <p>The historic 600 px constant was calibrated for a 1080p composition (64 px/block): a
     * 1.8-block actor is 115 px tall, so 600 px is a comfortable editing distance. A 320×180
     * composition uses ~22 px/block, which makes the same actor 40 px tall — at 600 px away it
     * becomes a dot. Scaling the default with the composition's scale (while an explicit
     * {@code -Dbbsae.replayFrameMinDistance} still wins) keeps the actor readable in both.</p>
     */
    public static float frameMinDistance(float pixelsPerBlock)
    {
        String raw = System.getProperty(FRAME_MIN_DISTANCE);

        if (raw != null && !raw.isBlank())
        {
            return frameMinDistance();
        }

        float scale = Math.max(1F, Math.min(4096F, pixelsPerBlock));

        return Math.max(80F, Math.min(1600F, scale * 8F));
    }

    /** System property as a finite float clamped to {@code [min, max]}, or the fallback. */
    static float floatProperty(String key, float fallback, float min, float max)
    {
        String raw = System.getProperty(key);

        if (raw == null || raw.isBlank())
        {
            return fallback;
        }

        try
        {
            float value = Float.parseFloat(raw.trim());

            if (!Float.isFinite(value))
            {
                return fallback;
            }

            return Math.max(min, Math.min(max, value));
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    /** System property as a boolean; only "true"/"false" (case-insensitive) are accepted. */
    static boolean booleanProperty(String key, boolean fallback)
    {
        String raw = System.getProperty(key);

        if (raw == null || raw.isBlank())
        {
            return fallback;
        }

        String value = raw.trim();

        if (value.equalsIgnoreCase("true"))
        {
            return true;
        }

        if (value.equalsIgnoreCase("false"))
        {
            return false;
        }

        return fallback;
    }
}
