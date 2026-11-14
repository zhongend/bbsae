package dev.bbsae.comp;

import java.util.ArrayList;
import java.util.List;

/**
 * Extrusion geometry for a flat 3D layer.
 *
 * <h2>Why a pure geometry helper</h2>
 * <p>An extruded layer is not a pixel effect: the layer's raster becomes the <em>face</em> of a
 * volume, and the volume is drawn as a stack of copies along the layer's local Z. The renderer, the
 * 3D workspace draft and the tests all have to agree on where those copies sit, which face keeps the
 * artwork's colours and how much each side is shaded. They live here, in composition space, with no
 * OpenGL and no UI, so the same maths can be asserted without a client.</p>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li>Composition space is X right, Y down, Z towards the viewer; a slice at {@code z = 0} is the
 *       layer's own artwork plane.</li>
 *   <li>{@link Direction#BACK} pushes the volume away from the viewer (the artwork stays the front
 *       face); {@link Direction#FRONT} extrudes towards the viewer (the artwork becomes the back
 *       face); {@link Direction#BOTH} centres the volume on the artwork plane.</li>
 *   <li>Slices are returned <b>farthest first</b>, so a caller can paint them in list order and get
 *       the correct alpha/painter order without knowing which direction was chosen.</li>
 * </ul>
 *
 * <p>The legacy {@code extrude3d} effect is still honoured through {@link #slices(CompLayer,
 * CompEffect)} so a project created before the dedicated 3D page keeps its volume. The layer fields
 * win when both exist: the inspector edits one source of truth, and an old effect left in the stack
 * cannot silently double the depth.</p>
 */
public final class CompExtrusion
{
    /** Same ceiling as the old effect; beyond this the slice stack is a wall of quads. */
    public static final float MAX_DEPTH = 400F;
    public static final int MIN_SLICES = 2;
    public static final int MAX_SLICES = 48;
    public static final float DEFAULT_DEPTH = 60F;
    public static final int DEFAULT_SLICES = 16;
    public static final float DEFAULT_SHADE = 0.35F;

    public static final int DIRECTION_BACK = 0;
    public static final int DIRECTION_FRONT = 1;
    public static final int DIRECTION_BOTH = 2;

    /** One plane of the volume, in layer-local space. */
    public record Slice(float z, int tint, boolean artwork)
    {}

    private CompExtrusion()
    {}

    /**
     * True when the layer should render as a volume.
     *
     * <p>Only a visible 3D layer can be extruded: a 2D layer is screen-attached, so a stack along Z
     * would collapse into the same quad. Cameras, nulls and adjustment layers have no pixels to
     * extrude.</p>
     */
    public static boolean isEnabled(CompLayer layer)
    {
        if (layer == null || !layer.extrude || !layer.threeD)
        {
            return false;
        }

        if (layer.isCamera() || "null".equals(layer.type) || "adjustment".equals(layer.type)
            || "camera".equals(layer.type) || layer.isEntityLayer())
        {
            return false;
        }

        return depth(layer) > 0.5F;
    }

    public static float depth(CompLayer layer)
    {
        if (layer == null || Float.isNaN(layer.extrudeDepth))
        {
            return 0F;
        }

        return Math.max(0F, Math.min(MAX_DEPTH, layer.extrudeDepth));
    }

    public static int sliceCount(CompLayer layer)
    {
        if (layer == null || Float.isNaN(layer.extrudeSlices))
        {
            return DEFAULT_SLICES;
        }

        return Math.max(MIN_SLICES, Math.min(MAX_SLICES, Math.round(layer.extrudeSlices)));
    }

    public static float shade(CompLayer layer)
    {
        if (layer == null || Float.isNaN(layer.extrudeShade))
        {
            return DEFAULT_SHADE;
        }

        return Math.max(0F, Math.min(1F, layer.extrudeShade));
    }

    public static int direction(CompLayer layer)
    {
        if (layer == null)
        {
            return DIRECTION_BACK;
        }

        return Math.max(DIRECTION_BACK, Math.min(DIRECTION_BOTH, layer.extrudeDirection));
    }

    /** Colour the sides are tinted with: the layer's own colour or the dedicated thickness colour. */
    public static int sideColor(CompLayer layer)
    {
        if (layer == null)
        {
            return 0xFFFFFFFF;
        }

        return layer.extrudeUseLayerColor ? layer.color : layer.extrudeColor;
    }

    /**
     * Slices for the layer's own extrusion, or for a legacy {@code extrude3d} effect when the layer
     * fields are off. Empty when there is nothing to extrude.
     */
    public static List<Slice> slices(CompLayer layer)
    {
        return slices(layer, null);
    }

    /**
     * @param legacy the resolved legacy effect, or null. Ignored as soon as the layer has its own
     *               extrusion enabled.
     */
    public static List<Slice> slices(CompLayer layer, CompEffect legacy)
    {
        if (layer == null)
        {
            return List.of();
        }

        /* A layer switch that is on but has zero depth means "no volume", not "fall back to the
         * legacy effect": the inspector owns the geometry now. Only a layer whose switch is off may
         * use the old effect as its volume. */
        boolean modern = layer.extrude && layer.threeD;

        if (!modern && (legacy == null || !layer.threeD))
        {
            return List.of();
        }

        float depth;
        int count;
        float shade;
        int side;
        int direction;
        boolean cap;

        if (modern)
        {
            depth = depth(layer);
            count = sliceCount(layer);
            shade = shade(layer);
            side = sideColor(layer);
            direction = direction(layer);
            cap = layer.extrudeCap;
        }
        else
        {
            depth = Math.max(0F, Math.min(MAX_DEPTH, legacy.valueF("depth", DEFAULT_DEPTH)));
            count = Math.max(MIN_SLICES, Math.min(MAX_SLICES, Math.round(legacy.valueF("slices", DEFAULT_SLICES))));
            shade = Math.max(0F, Math.min(1F, legacy.valueF("shade", DEFAULT_SHADE)));
            /* The legacy effect modelled the sides as a darker copy of the texture, i.e. a white
             * tint. Keeping that value makes an untouched old project render exactly as before. */
            side = 0xFFFFFFFF;
            direction = DIRECTION_BACK;
            cap = true;
        }

        if (depth <= 0.5F)
        {
            return List.of();
        }

        List<Slice> slices = new ArrayList<>(count + 2);

        if (direction == DIRECTION_FRONT)
        {
            /* Far end (z = 0) first; the artwork itself sits on the near face. */
            for (int i = 0; i < count; i++)
            {
                float t = i / (float) (count - 1);
                boolean artwork = i == count - 1;

                if (i == 0 && !cap)
                {
                    continue;
                }

                slices.add(new Slice(depth * t, artwork ? 0xFFFFFFFF : shaded(side, shade * (1F - t)), artwork));
            }

            return slices;
        }

        if (direction == DIRECTION_BOTH)
        {
            float half = depth * 0.5F;
            int back = Math.max(1, count / 2);
            int front = Math.max(1, count - back);

            for (int i = back; i >= 1; i--)
            {
                float t = i / (float) back;

                if (i == back && !cap)
                {
                    continue;
                }

                slices.add(new Slice(-half * t, shaded(side, shade * t), false));
            }

            /* The artwork plane is the nucleus of a centred volume. */
            slices.add(new Slice(0F, 0xFFFFFFFF, true));

            for (int i = 1; i <= front; i++)
            {
                float t = i / (float) front;

                if (i == front && !cap)
                {
                    continue;
                }

                slices.add(new Slice(half * t, shaded(side, shade * t), false));
            }

            return slices;
        }

        /* Back (the default): the artwork is the front face and the volume recedes. */
        for (int i = 0; i < count; i++)
        {
            float t = i / (float) (count - 1);
            boolean artwork = i == 0;

            if (i == count - 1 && !cap)
            {
                continue;
            }

            slices.add(new Slice(-depth * t, artwork ? 0xFFFFFFFF : shaded(side, shade * t), artwork));
        }

        return slices;
    }

    /**
     * Multiplies an ARGB colour's RGB by {@code 1 - amount}, keeping it opaque.
     *
     * <p>Rounding each channel (instead of truncating) matters for a 16-step gradient: truncation
     * biases the whole side darker by up to one code value per step and the volume's shading bands
     * become visible.</p>
     */
    public static int shaded(int argb, float amount)
    {
        float keep = 1F - Math.max(0F, Math.min(1F, amount));
        int r = Math.round(((argb >> 16) & 0xFF) * keep);
        int g = Math.round(((argb >> 8) & 0xFF) * keep);
        int b = Math.round((argb & 0xFF) * keep);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
