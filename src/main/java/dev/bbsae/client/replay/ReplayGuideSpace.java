package dev.bbsae.client.replay;

import dev.bbsae.client.render.CompCamera;
import dev.bbsae.client.render.CompRenderContext;
import dev.bbsae.client.render.CompScene;
import dev.bbsae.client.render.CompViewportCamera;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import mchorse.bbs_mod.ui.utils.Area;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Maps composition-space guide geometry onto the replay viewport's picture rectangle.
 *
 * <h2>The bug this removes</h2>
 * <p>The free/orbit picture is already a finished composition frame: 2D layers are screen-fixed in
 * composition pixels, 3D layers are projected by the free camera, and the whole raster is then
 * letterboxed into {@code freePlate}. The guide overlay used to project <b>every</b> layer through
 * the 3D camera and draw it at the viewport origin, so a 2D text/shape label (and the composition
 * frame itself) drifted away from the picture as soon as the camera was orbited. Two coordinate
 * systems were mixed: camera pixels for 3D geometry, composition pixels for 2D geometry, and a
 * letterbox that neither path applied consistently.</p>
 *
 * <p>This class uses exactly the renderer's own predicates:</p>
 * <ul>
 *   <li>{@link CompScene#project(CompRenderContext, CompLayer, float, float, float)} returns
 *       composition pixels for a 2D layer and camera-projected composition pixels for a 3D layer;</li>
 *   <li>those composition pixels are then mapped through the same plate rectangle the finished
 *       composition raster is painted into ({@code plate.w / doc.width}, {@code plate.h / doc.height}).</li>
 * </ul>
 *
 * <p>Pure JOML/comp geometry: no GL, so the mapping can be pinned by a unit test. The overlay is
 * the only caller; keeping it separate is what makes "the guide lands where the renderer put the
 * layer" a testable property instead of a screenshot comparison.</p>
 */
public final class ReplayGuideSpace
{
    private final CompRenderContext context;
    private final CompCamera camera;
    private final CompDocument doc;
    private final Area plate;
    private final float pixelsPerCompX;
    private final float pixelsPerCompY;

    public ReplayGuideSpace(CompProject project, CompDocument doc, CompViewportCamera free,
                            float time, Area plate)
    {
        this.doc = doc;
        this.plate = plate;
        this.context = new CompRenderContext(project, doc, time, null, null, null);
        /* The same camera the free-view composition raster was rendered with. Setting it on the
         * context (instead of the thread-local override) keeps this purely local to the guide
         * geometry and safe to call from any render path. */
        this.camera = CompCamera.ofFree(free, doc.width, doc.height);
        this.context.cameraView = this.camera;
        this.pixelsPerCompX = plate.w / Math.max(1F, doc.width);
        this.pixelsPerCompY = plate.h / Math.max(1F, doc.height);
    }

    public CompRenderContext context()
    {
        return this.context;
    }

    public CompCamera camera()
    {
        return this.camera;
    }

    /**
     * Local layer point -> screen pixel. A 2D layer stays in composition pixels; a 3D layer goes
     * through the free camera exactly as {@code CompRenderer} did when it painted the raster.
     */
    public Vector2f projectLayer(CompLayer layer, float localX, float localY, float localZ)
    {
        Vector2f comp = CompScene.project(this.context, layer, localX, localY, localZ);

        return comp == null ? null : this.toScreen(comp.x, comp.y);
    }

    /** Composition-space point of a 3D guide (camera eye/target/motion path) -> screen pixel. */
    public Vector2f projectComp(Vector3f point)
    {
        Vector2f comp = this.camera.project(point, this.doc.width, this.doc.height);

        return comp == null ? null : this.toScreen(comp.x, comp.y);
    }

    /** Composition pixels -> the picture rectangle on screen. */
    public Vector2f toScreen(float compX, float compY)
    {
        return new Vector2f(this.plate.x + compX * this.pixelsPerCompX,
            this.plate.y + compY * this.pixelsPerCompY);
    }

    public float pixelsPerCompX()
    {
        return this.pixelsPerCompX;
    }

    public float pixelsPerCompY()
    {
        return this.pixelsPerCompY;
    }
}
