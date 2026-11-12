package dev.bbsae.client.replay;

/**
 * Writes a replay's own placement into its keyframe channels.
 *
 * <p>This is the pure half of what {@code UIReplayPropTransform} does for the original gizmo: a
 * replay has no transform object, only the channels {@code x/y/z} and {@code yaw/pitch/headYaw/
 * bodyYaw}, so every write has to spell out the same rules in the same way:</p>
 * <ul>
 *   <li>a position write always writes <b>all three</b> axes at the tick — a half-keyed position
 *       would read one axis from here and the other two from whatever the neighbouring keyframes
 *       interpolate to (the original editor comments this exact reason);</li>
 *   <li>{@code yaw = −y}, because the renderer places the actor with {@code rotateY(−bodyYaw)}
 *       (see {@code UIReplayPropTransform.setR});</li>
 *   <li>turning the actor turns head and body by the same amount, keeping the offsets they had
 *       when the write started — otherwise only the body turns and the head stays behind.</li>
 * </ul>
 *
 * <p>The channel sinks are plain interfaces so the semantics can be unit-tested without a film,
 * a GL context or the BBS value system; {@link ReplayEditSession} binds them to
 * {@code KeyframeChannel#insert}.</p>
 */
public final class ReplayWriteback
{
    /** One keyframe channel, reduced to the one operation a placement write needs. */
    public interface Channel
    {
        void insert(float tick, double value);
    }

    private ReplayWriteback()
    {}

    public static void position(float tick, double x, double y, double z,
                                Channel xChannel, Channel yChannel, Channel zChannel)
    {
        xChannel.insert(tick, x);
        yChannel.insert(tick, y);
        zChannel.insert(tick, z);
    }

    /**
     * @param headYawOffset {@code headYaw − yaw} at the moment the edit started
     * @param bodyYawOffset {@code bodyYaw − yaw} at the moment the edit started
     */
    public static void rotation(float tick, double pitch, double yaw,
                                double headYawOffset, double bodyYawOffset,
                                Channel yawChannel, Channel headYawChannel,
                                Channel bodyYawChannel, Channel pitchChannel)
    {
        double writtenYaw = -yaw;

        yawChannel.insert(tick, writtenYaw);
        headYawChannel.insert(tick, writtenYaw + headYawOffset);
        bodyYawChannel.insert(tick, writtenYaw + bodyYawOffset);
        pitchChannel.insert(tick, pitch);
    }
}
