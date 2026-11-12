package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;

/** Per-render state: comp time, size and the BBS camera/projection needed by bindings. */
public final class CompRenderContext
{
    public final CompProject project;
    public final CompDocument comp;
    public final float time;
    public final float frame;
    public final int width;
    public final int height;
    public final Matrix4f projection;
    public final Camera camera;
    public final SceneDepth depth;
    public final Set<String> stack = new HashSet<>();

    /**
     * Editor preview vs export. Editor-only annotations (for example the "empty precomp" text)
     * must never be baked into an exported frame; nested contexts inherit this flag.
     */
    public boolean preview = true;

    /**
     * True only for the free 3D workspace draft viewport ({@link CompViewportRenderer}).
     *
     * <p>The 3D workspace draws a cheap <b>draft</b>: a precomp layer is represented by a blue
     * "预合成（草稿）" plate instead of rendering the nested composition (that would be a second
     * full graph per orbit frame). That plate is editor chrome and must never leak into the
     * composition panel preview or the encoder — those render the real nested comp. Before this
     * flag existed, {@code CompLayerSourceCache} keyed the plate off {@code preview} alone, so the
     * panel preview (also "preview") showed a blue placeholder instead of the actual precomp.</p>
     */
    public boolean draftViewport = false;

    /**
     * Whether this pass applies the effect stack and masks.
     *
     * <p>A sub-composition must inherit the parent's answer: a depth or guide pass renders the
     * same graph with effects off, and a precomp used to hardcode {@code true} when it recursed,
     * quietly bringing effects (and their GPU cost) back into a pass that asked for none.</p>
     */
    public boolean renderEffects = true;
    public boolean renderMasks = true;

    /**
     * Resolution factor of the render targets. 1 = composition resolution.
     *
     * <p>The composition coordinate space is always {@link #width}×{@link #height} comp pixels;
     * this only shrinks the framebuffers. Because every pass maps NDC onto the bound viewport,
     * halving the target halves the pixel cost without changing a single coordinate — which is
     * what makes a preview proxy possible without a second render path.</p>
     */
    public float scale = 1F;

    /** The camera of this frame; resolved lazily by {@link CompScene#camera}. */
    public CompCamera cameraView;
    /** Cached {@link CompScene#spatial} answer for this frame. */
    public Boolean spatial;
    /** Per-frame scene graph caches: one matrix per layer, not one per call site. */
    /**
     * The accumulated composition below the layer currently being processed. Effect programmes
     * that read the backdrop (displacement maps, glass, light wrap) sample this; it is null while
     * no layer pass is active (for example inside the 3D layer bake), where such effects fall back
     * to their own source texture.
     */
    public GlFramebuffer backdrop;

    /** Depth compositor for 3D layers; null for 2D compositions. */
    public DepthPlaneCompositor spatialDepth;

    /**
     * True while effects run on a layer-local raster (the 3D bake) rather than on a
     * composition-sized target. Layer-space shaders then address the source in its own 0..1 UVs
     * instead of mapping layer pixels back through the composition.
     */
    public boolean layerLocalPass;

    public final java.util.IdentityHashMap<CompLayer, Matrix4f> localCache = new java.util.IdentityHashMap<>();
    public final java.util.IdentityHashMap<CompLayer, Matrix4f> worldCache = new java.util.IdentityHashMap<>();
    public final java.util.IdentityHashMap<CompLayer, Matrix4f> modelCache = new java.util.IdentityHashMap<>();
    public final java.util.IdentityHashMap<CompLayer, Float> depthCache = new java.util.IdentityHashMap<>();

    public CompRenderContext(CompProject project, CompDocument comp, float time, Matrix4f projection, Camera camera, SceneDepth depth)
    {
        this.project = project;
        this.comp = comp;
        this.time = Math.max(0F, time);
        this.frame = this.time * Math.max(1F, comp.fps);
        this.width = Math.max(1, comp.width);
        this.height = Math.max(1, comp.height);
        this.projection = projection;
        this.camera = camera;
        this.depth = depth;
    }

    public boolean hasDepth()
    {
        return this.depth != null && this.depth.isAvailable();
    }

    /** Width of the render target in pixels (composition width × {@link #scale}). */
    public int renderWidth()
    {
        return Math.max(1, Math.round(this.width * this.scale));
    }

    public int renderHeight()
    {
        return Math.max(1, Math.round(this.height * this.scale));
    }
}
