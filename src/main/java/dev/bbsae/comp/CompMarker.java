package dev.bbsae.comp;

/** A composition marker. Film camera clip boundaries can be imported as markers. */
public class CompMarker
{
    public String id = "";
    public String name = "Marker";
    /** Seconds. */
    public float time = 0F;
    public int color = 0xFF55AAFF;
    public String comment = "";

    public CompMarker()
    {}

    public CompMarker(String id, String name, float time)
    {
        this.id = id;
        this.name = name;
        this.time = time;
    }
}
