package dev.bbsae.comp;

/**
 * Per-character text animation. Selection is a range along the string; wiggle mode adds a
 * deterministic noise offset instead of a fixed range. The renderer applies the transform to each
 * glyph, so this is real per-character motion rather than a whole-layer shake.
 */
public class CompTextAnimator
{
    public String id = "animator";
    public String name = "Animator";
    public boolean enabled = true;

    /** Range selector. */
    public boolean wiggle = false;
    public float start = 0F;
    public float end = 1F;
    public float offset = 0F;
    public float randomness = 0F;
    public int seed = 1;
    /** Random order: 0 = sequential, 1 = random. */
    public boolean randomOrder = false;

    public float positionX = 0F;
    public float positionY = 0F;
    public float scale = 1F;
    public float rotation = 0F;
    public float opacity = 1F;
    public int color = 0xFFFFFFFF;
    public boolean useColor = false;

    public CompTextAnimator copy()
    {
        CompTextAnimator copy = new CompTextAnimator();

        copy.id = this.id;
        copy.name = this.name;
        copy.enabled = this.enabled;
        copy.wiggle = this.wiggle;
        copy.start = this.start;
        copy.end = this.end;
        copy.offset = this.offset;
        copy.randomness = this.randomness;
        copy.seed = this.seed;
        copy.randomOrder = this.randomOrder;
        copy.positionX = this.positionX;
        copy.positionY = this.positionY;
        copy.scale = this.scale;
        copy.rotation = this.rotation;
        copy.opacity = this.opacity;
        copy.color = this.color;
        copy.useColor = this.useColor;

        return copy;
    }
}
