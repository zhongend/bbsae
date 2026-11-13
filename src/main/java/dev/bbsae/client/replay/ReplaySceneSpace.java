package dev.bbsae.client.replay;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The mapping between the film's world (Minecraft blocks, Y up, X east, Z south) and bbsae's
 * composition space (pixels, Y down, Z towards the viewer).
 *
 * <h2>Why a mapping exists at all</h2>
 * <p>The replay editor is a bbsae panel, so its free camera is a {@code CompViewportCamera}:
 * pixels, Y down, Z towards the viewer. The models it edits, however, are BBS film actors whose
 * positions are Minecraft world coordinates. A viewport that mixes the two without a declared
 * mapping draws actors somewhere the camera never looks and drags gizmo handles along axes that
 * do not correspond to the keyframe channels.</p>
 *
 * <h2>The sign choice (and why it is not a mirror)</h2>
 * <p>{@code (x, y, z)_comp = (S·x, −S·y, ∓S·z)_world + anchor}. Y must flip (Y up → Y down). The
 * Z sign selects the handedness of the mapping:</p>
 * <ul>
 *   <li>{@code −S·z} (the default, {@link #flipZ} true): determinant {@code +S³} — a pure rotation
 *       ({@code rotateX(180°)}), so triangle winding and back-face culling keep working. "Front"
 *       and "back" of the model are swapped relative to the world camera, which is what
 *       {@link #flipZ} exists to switch if a future calibration run proves the other axis is the
 *       right one.</li>
 *   <li>{@code +S·z}: determinant {@code −S³} — a mirror, which needs {@code disableCull()} and
 *       makes models look inside-out. Deliberately not the default; see the replay mode plan
 *       (risk R4).</li>
 * </ul>
 *
 * <p>Pure JOML on purpose: the mapping is the one thing about the 3D editor that can be pinned by
 * a unit test without a GL context, and it is the first thing that goes wrong when someone
 * "cleans up" a sign.</p>
 */
public final class ReplaySceneSpace
{
    /** 64 composition pixels per block puts a 1.8-block player at roughly 115 px — fight-frame-ish. */
    public static final float DEFAULT_PIXELS_PER_BLOCK = ReplayCalibration.DEFAULT_PIXELS_PER_BLOCK;

    /** Composition pixels per Minecraft block (system property {@code bbsae.replayPixelsPerBlock}). */
    public float pixelsPerBlock = ReplayCalibration.pixelsPerBlock();

    /** Where the world origin lands in composition space. */
    public final Vector3f anchor = new Vector3f();

    /**
     * Optional whole-layer linear transform (rotation/scale) applied after the block→pixel scale.
     *
     * <p>An independent replay layer renders its actor through the layer's full world matrix:
     * {@code layerWorld · S · (entity - replayAnchor)}. The replay editor's actor/gizmo mapping
     * used only {@code anchor + S · entity}, so a scaled or rotated replay layer put the actor at
     * one place and its gizmo at another. The free/orbit views install the linked layer's linear
     * part here; camera view keeps the base scalar mapping (its pose is the composition camera).</p>
     */
    private Matrix4f linearOverride;

    /**
     * Z sign: {@code true} maps world Z to {@code −S·z} (positive determinant, culling safe),
     * {@code false} to {@code +S·z} (a mirror; diagnostic calibration only). Default comes from
     * {@code -Dbbsae.replayFlipZ}.
     */
    public boolean flipZ = ReplayCalibration.flipZ();

    public ReplaySceneSpace()
    {
        this(ReplayCalibration.pixelsPerBlock());
    }

    public ReplaySceneSpace(float pixelsPerBlock)
    {
        this.pixelsPerBlock = pixelsPerBlock;
    }

    /** Install the linked layer's linear transform for free/orbit actor mapping. */
    public void setLinearOverride(Matrix4f linear)
    {
        this.linearOverride = linear == null ? null : new Matrix4f(linear);
    }

    /** Restore the plain block→pixel mapping (camera view and unrelated films). */
    public void clearLinearOverride()
    {
        this.linearOverride = null;
    }

    public boolean hasLinearOverride()
    {
        return this.linearOverride != null;
    }

    /**
     * The rotation/scale part of {@link #compFromWorld()} with no translation.
     *
     * <p>BBS' {@code Camera.view} is a rotation-only matrix (the eye position lives in
     * {@code Camera.position}) and the film actor pass receives camera-relative geometry, so the
     * viewport camera's block→view matrix must be exactly this product without any translation.
     * Using the full {@code compFromWorld} (which carries the framing anchor) shifts the actors by
     * the target distance — the "viewport is empty" bug of the real-machine runs.</p>
     *
     * <p>Free/orbit can install the linked layer's linear part (including its scale/rotation and
     * the block→pixel scale) through {@link #setLinearOverride(Matrix4f)}; camera view clears it
     * and uses the plain scalar mapping.</p>
     */
    public Matrix4f linearFromWorld()
    {
        if (this.linearOverride != null)
        {
            return new Matrix4f(this.linearOverride);
        }

        float s = this.pixelsPerBlock;
        float z = this.flipZ ? -s : s;

        return new Matrix4f().scale(s, -s, z);
    }

    /** World → composition. Determinant positive while {@link #flipZ} is on. */
    public Matrix4f compFromWorld()
    {
        return new Matrix4f().translation(this.anchor).mul(this.linearFromWorld());
    }

    /** Composition → world: the inverse of {@link #compFromWorld()}. */
    public Matrix4f worldFromComp()
    {
        return this.compFromWorld().invert();
    }

    public Vector3f toComp(Vector3d world)
    {
        return this.toComp(world.x, world.y, world.z);
    }

    public Vector3f toComp(double x, double y, double z)
    {
        Vector3f comp = this.linearFromWorld().transformPosition(new Vector3f((float) x, (float) y, (float) z));

        return comp.add(this.anchor);
    }

    public Vector3f toWorld(Vector3f comp)
    {
        return this.toWorld(comp.x, comp.y, comp.z);
    }

    public Vector3f toWorld(float x, float y, float z)
    {
        Vector3f local = new Vector3f(x, y, z).sub(this.anchor);

        return this.linearFromWorld().invert().transformPosition(local);
    }

    /**
     * Moves the anchor so {@code worldPoint} lands on the composition origin.
     *
     * <p>Used to centre the replay viewport on the selected actor: the camera then orbits around
     * (0, 0, 0) in composition space while the actor itself stays exactly where the film puts
     * it.</p>
     */
    public void centerOn(Vector3d worldPoint)
    {
        this.centerOn(worldPoint.x, worldPoint.y, worldPoint.z);
    }

    public void centerOn(double x, double y, double z)
    {
        Vector3f comp = this.linearFromWorld().transformPosition(new Vector3f((float) x, (float) y, (float) z));

        this.anchor.set(comp.negate());
    }

    /** Blocks per composition pixel, for readouts and drag scaling. */
    public float blocksPerPixel()
    {
        return 1F / Math.max(0.0001F, this.pixelsPerBlock);
    }
}
