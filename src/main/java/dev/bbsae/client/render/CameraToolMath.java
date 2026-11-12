package dev.bbsae.client.render;

/**
 * Pure math for the AE-style camera tools.
 *
 * <p>The camera tools run on the 2D preview, whose pixels are scaled by the preview fit factor, and
 * the camera itself magnifies the composition plane by {@code focal / distance} (its {@code zoom}).
 * A pointer delta therefore corresponds to {@code 1 / (fit * zoom)} world units per screen pixel.
 * Missing the zoom term made Track XY move the camera faster than the cursor after any dolly —
 * exactly the kind of hand-feel bug that is invisible in a unit test unless the conversion lives
 * in its own function.</p>
 */
public final class CameraToolMath
{
    private CameraToolMath() {}

    /** World (comp-plane) units per preview-screen pixel for the current fit scale and camera zoom. */
    public static float worldPerScreenPixel(float fitScale, float cameraZoom)
    {
        float scale = Math.max(0.0001F, fitScale) * Math.max(0.0001F, cameraZoom);

        return 1F / scale;
    }

    /** Dolly distance after a horizontal drag, clamped to the camera's near/far range. */
    public static float dollyDistance(float startRadius, float dollyFactor, float near, float far)
    {
        float distance = Math.max(near + 10F, Math.min(far - 10F, startRadius * dollyFactor));

        return Math.max(1F, distance);
    }
}
