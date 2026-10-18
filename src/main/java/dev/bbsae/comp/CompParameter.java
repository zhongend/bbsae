package dev.bbsae.comp;

/**
 * A master property. Compositions can expose parameters here and layers can read them through an
 * expression (for example {@code comp.titleColor} is not a float, but {@code comp.padding} is).
 */
public class CompParameter
{
    public String name = "parameter";
    public String label = "Parameter";
    public String type = "float";
    public float value = 0F;
    public int color = 0xFFFFFFFF;
    public String text = "";
    public float min = -100000F;
    public float max = 100000F;
    public boolean animatable = true;

    public CompParameter()
    {}

    public CompParameter(String name, float value)
    {
        this.name = name;
        this.label = name;
        this.value = value;
    }
}
