package dev.bbsae.client.replay;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;

/**
 * Pure geometry for the replay editor's camera preview.
 *
 * <p>The viewport is an editor panel, not the film's raster: its aspect ratio almost never equals
 * the composition's. The old preview blitted the composition into the whole viewport rectangle, so
 * a 16:9 composition in a 4:3-ish viewport was stretched. BBS' own film preview letterboxes the
 * film frame instead, and the replay preview now follows the same rule:</p>
 *
 * <ul>
 *   <li>{@link #fit(CompDocument, int, int, int, int, int)} returns the composition rectangle inside
 *       the viewport, preserving the document aspect and centring the result;</li>
 *   <li>{@link #contain(int, int, Rect)} returns where a world frame with a different aspect (the
 *       window framebuffer is window-shaped, not necessarily film-shaped) is painted inside that
 *       composition rectangle. Contain, never cover: cropping the world frame would silently change
 *       the framing the director composed.</li>
 * </ul>
 *
 * <p>Both functions are integer-only, clamped against degenerate panels and documents, and have no
 * GL dependency — which is what makes them testable without launching the client.</p>
 */
public final class ReplayPreviewLayout
{
    /** Smallest composition rectangle worth drawing; below this the viewport shows its hint. */
    public static final int MIN_CONTENT = 16;

    private ReplayPreviewLayout()
    {}

    /**
     * Whether the project explicitly asked for the BBS world behind the whole composition
     * ({@code 预览背景 → BBS}). This is the only mode in which the captured world may become the
     * viewport background; a `bbs` layer is still a normal layer inside the composition.
     */
    public static boolean usesBbsBackdrop(CompProject project)
    {
        return project != null && project.settings != null && project.settings.previewBackground == 1;
    }

    /** A screen-space rectangle. Integer because the UI batcher clips on whole pixels. */
    public record Rect(int x, int y, int w, int h)
    {
        public int ex()
        {
            return this.x + this.w;
        }

        public int ey()
        {
            return this.y + this.h;
        }
    }

    /**
     * Largest rectangle with the document's aspect ratio that fits inside the padded viewport.
     *
     * <p>When the document has no usable size (a project that was never configured) the full padded
     * viewport is returned instead of a 1×1 pixel, so the world backdrop can still be previewed.</p>
     */
    public static Rect fit(CompDocument doc, int x, int y, int width, int height, int padding)
    {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int pad = Math.max(0, Math.min(padding, Math.min(w, h) / 2));
        int availW = Math.max(1, w - pad * 2);
        int availH = Math.max(1, h - pad * 2);

        if (doc == null || doc.width <= 0 || doc.height <= 0)
        {
            return new Rect(x + pad, y + pad, availW, availH);
        }

        double aspect = doc.width / (double) doc.height;

        if (!Double.isFinite(aspect) || aspect <= 0D)
        {
            return new Rect(x + pad, y + pad, availW, availH);
        }

        int contentW;
        int contentH;

        if (availW / (double) availH > aspect)
        {
            contentH = availH;
            contentW = Math.max(1, (int) Math.round(availH * aspect));
        }
        else
        {
            contentW = availW;
            contentH = Math.max(1, (int) Math.round(availW / aspect));
        }

        contentW = Math.min(availW, contentW);
        contentH = Math.min(availH, contentH);

        return new Rect(
            x + (w - contentW) / 2,
            y + (h - contentH) / 2,
            contentW,
            contentH
        );
    }

    /**
     * Paint rectangle for a texture with its own aspect inside {@code bounds}, preserving aspect.
     *
     * <p>Falls back to {@code bounds} for a zero-sized texture: callers use the result for a
     * letterboxed plate, and a degenerate texture must not produce a zero-width quad.</p>
     */
    public static Rect contain(int textureWidth, int textureHeight, Rect bounds)
    {
        if (bounds == null || bounds.w <= 0 || bounds.h <= 0)
        {
            return new Rect(0, 0, 1, 1);
        }

        if (textureWidth <= 0 || textureHeight <= 0)
        {
            return bounds;
        }

        double aspect = textureWidth / (double) textureHeight;
        int w;
        int h;

        if (bounds.w / (double) bounds.h > aspect)
        {
            h = bounds.h;
            w = Math.max(1, (int) Math.round(bounds.h * aspect));
        }
        else
        {
            w = bounds.w;
            h = Math.max(1, (int) Math.round(bounds.w / aspect));
        }

        w = Math.min(bounds.w, w);
        h = Math.min(bounds.h, h);

        return new Rect(
            bounds.x + (bounds.w - w) / 2,
            bounds.y + (bounds.h - h) / 2,
            w,
            h
        );
    }

    /**
     * True when a cached composition frame can still be shown.
     *
     * <p>Deliberately a pure predicate: the cache in {@link UIReplayViewport} is an optimisation
     * only, and every reason to invalidate it must be visible in one place. A frame is reused when
     * the playhead's frame, the document signature and the render raster are unchanged. A
     * composition that samples the live world adds one more condition: the first valid world copy
     * must have existed when the frame was baked, otherwise the BBS-preview layer in it is empty and
     * the frame would stay empty forever.</p>
     */
    public static boolean canReuse(boolean hasFrame, int cachedFrame, long cachedSignature, int cachedWidth,
                                   int cachedHeight, int frame, long signature, int width, int height,
                                   boolean needsLiveWorld, boolean worldWasReady)
    {
        if (!hasFrame || cachedFrame != frame || cachedSignature != signature
            || cachedWidth != width || cachedHeight != height)
        {
            return false;
        }

        return !needsLiveWorld || worldWasReady;
    }
}
