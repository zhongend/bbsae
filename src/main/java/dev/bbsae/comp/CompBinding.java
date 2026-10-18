package dev.bbsae.comp;

/**
 * Binds a layer (or a point) to something BBS already knows. This is the structural advantage over
 * AE: screen-space elements can be driven by the film camera, a replay actor or a layer without
 * solving tracking from pixels.
 */
public class CompBinding
{
    public static final String NONE = "none";
    public static final String CAMERA = "camera";
    public static final String REPLAY = "replay";
    public static final String LAYER = "layer";
    public static final String SCREEN = "screen";

    public boolean enabled = false;
    public String type = NONE;
    public String target = "";
    public float offsetX = 0F;
    public float offsetY = 0F;
    /** World-space vertical offset, before projection. */
    public float worldOffsetY = 0F;
    public boolean scaleWithDistance = false;
    public float distanceScale = 1F;
    public float smoothing = 0F;

    public CompBinding copy()
    {
        CompBinding copy = new CompBinding();

        copy.enabled = this.enabled;
        copy.type = this.type;
        copy.target = this.target;
        copy.offsetX = this.offsetX;
        copy.offsetY = this.offsetY;
        copy.worldOffsetY = this.worldOffsetY;
        copy.scaleWithDistance = this.scaleWithDistance;
        copy.distanceScale = this.distanceScale;
        copy.smoothing = this.smoothing;

        return copy;
    }
}
