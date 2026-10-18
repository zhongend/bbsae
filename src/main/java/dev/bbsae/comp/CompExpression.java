package dev.bbsae.comp;

import java.util.Map;

/**
 * A deliberately small, safe expression language for properties. No JavaScript engine: the grammar
 * is numbers, {@code + - * / % ^}, parentheses, variables and a handful of math functions.
 *
 * <p>Supported variables: {@code time}, {@code frame}, {@code value}, {@code fps},
 * {@code comp.<parameter>}, {@code layer.<layerId>.<property>}. Functions: sin, cos, tan, abs,
 * min, max, clamp, floor, ceil, round, sqrt, pow, noise, wiggle. Anything unknown falls back to the
 * property's static value instead of breaking the frame.</p>
 */
public final class CompExpression
{
    public interface Context
    {
        float time();
        float frame();
        float fps();
        float value();
        float variable(String path);
    }

    private final String source;
    private int cursor;
    private final Context context;

    private CompExpression(String source, Context context)
    {
        this.source = source == null ? "" : source;
        this.context = context;
    }

    public static float eval(String source, Context context, float fallback)
    {
        if (source == null || source.isBlank())
        {
            return fallback;
        }

        try
        {
            CompExpression expression = new CompExpression(source, context);
            float value = expression.expression();

            expression.skipWhitespace();

            if (expression.cursor < source.length())
            {
                return fallback;
            }

            return Float.isFinite(value) ? value : fallback;
        }
        catch (RuntimeException e)
        {
            return fallback;
        }
    }

    private float expression()
    {
        float value = term();

        while (true)
        {
            this.skipWhitespace();

            if (this.eat('+'))
            {
                value += term();
            }
            else if (this.eat('-'))
            {
                value -= term();
            }
            else
            {
                return value;
            }
        }
    }

    private float term()
    {
        float value = power();

        while (true)
        {
            this.skipWhitespace();

            if (this.eat('*'))
            {
                value *= power();
            }
            else if (this.eat('/'))
            {
                float right = power();
                value = right == 0F ? 0F : value / right;
            }
            else if (this.eat('%'))
            {
                float right = power();
                value = right == 0F ? 0F : value % right;
            }
            else
            {
                return value;
            }
        }
    }

    private float power()
    {
        float value = unary();

        if (this.eat('^'))
        {
            value = (float) Math.pow(value, power());
        }

        return value;
    }

    private float unary()
    {
        this.skipWhitespace();

        if (this.eat('-'))
        {
            return -unary();
        }

        if (this.eat('+'))
        {
            return unary();
        }

        return primary();
    }

    private float primary()
    {
        this.skipWhitespace();

        if (this.eat('('))
        {
            float value = this.expression();
            this.eat(')');
            return value;
        }

        if (this.cursor < this.source.length() && (Character.isDigit(this.source.charAt(this.cursor)) || this.source.charAt(this.cursor) == '.'))
        {
            int start = this.cursor;

            while (this.cursor < this.source.length())
            {
                char c = this.source.charAt(this.cursor);

                if (Character.isDigit(c) || c == '.')
                {
                    this.cursor++;
                }
                else
                {
                    break;
                }
            }

            return Float.parseFloat(this.source.substring(start, this.cursor));
        }

        String name = this.identifier();

        if (name.isEmpty())
        {
            throw new IllegalArgumentException("Expected value");
        }

        this.skipWhitespace();

        if (this.eat('('))
        {
            float a = this.expression();
            float b = 0F;
            float c = 0F;
            boolean hasB = false;
            boolean hasC = false;

            if (this.eat(','))
            {
                b = this.expression();
                hasB = true;

                if (this.eat(','))
                {
                    c = this.expression();
                    hasC = true;
                }
            }

            this.eat(')');

            return function(name, a, b, c, hasB, hasC);
        }

        return variable(name);
    }

    private float function(String name, float a, float b, float c, boolean hasB, boolean hasC)
    {
        switch (name)
        {
            case "sin": return (float) Math.sin(Math.toRadians(a));
            case "cos": return (float) Math.cos(Math.toRadians(a));
            case "tan": return (float) Math.tan(Math.toRadians(a));
            case "abs": return Math.abs(a);
            case "min": return hasB ? Math.min(a, b) : a;
            case "max": return hasB ? Math.max(a, b) : a;
            case "clamp": return hasC ? Math.max(b, Math.min(c, a)) : Math.max(0F, Math.min(1F, a));
            case "floor": return (float) Math.floor(a);
            case "ceil": return (float) Math.ceil(a);
            case "round": return Math.round(a);
            case "sqrt": return (float) Math.sqrt(Math.max(0F, a));
            case "pow": return (float) Math.pow(a, b);
            case "noise": return deterministicNoise(a, b);
            case "wiggle": return deterministicNoise(this.context.frame() * 0.35F, a + b * 37F) * 2F - 1F;
            case "deg": return (float) Math.toDegrees(a);
            case "rad": return (float) Math.toRadians(a);
            default: return 0F;
        }
    }

    private float variable(String name)
    {
        switch (name)
        {
            case "time": return this.context.time();
            case "frame": return this.context.frame();
            case "value": return this.context.value();
            case "fps": return this.context.fps();
            case "pi": return (float) Math.PI;
            default: return this.context.variable(name);
        }
    }

    private String identifier()
    {
        this.skipWhitespace();

        int start = this.cursor;

        while (this.cursor < this.source.length())
        {
            char c = this.source.charAt(this.cursor);

            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':')
            {
                this.cursor++;
            }
            else
            {
                break;
            }
        }

        return this.source.substring(start, this.cursor);
    }

    private void skipWhitespace()
    {
        while (this.cursor < this.source.length() && Character.isWhitespace(this.source.charAt(this.cursor)))
        {
            this.cursor++;
        }
    }

    private boolean eat(char c)
    {
        this.skipWhitespace();

        if (this.cursor < this.source.length() && this.source.charAt(this.cursor) == c)
        {
            this.cursor++;
            return true;
        }

        return false;
    }

    public static float deterministicNoise(float x, float seed)
    {
        double value = Math.sin(x * 12.9898D + seed * 78.233D) * 43758.5453D;

        return (float) (value - Math.floor(value));
    }

    /** Resolves {@code comp.foo} and {@code layer.id.prop} against a project/document. */
    public static float variable(CompProject project, CompDocument comp, String path, float layerValue)
    {
        return variable(project, comp, path, layerValue, 0F);
    }

    /** Resolves layer properties at {@code frame}, so expressions see the current animation. */
    public static float variable(CompProject project, CompDocument comp, String path, float layerValue, float frame)
    {
        if (path == null)
        {
            return 0F;
        }

        if (path.startsWith("comp."))
        {
            CompParameter parameter = comp == null ? null : comp.getParameter(path.substring(5));

            return parameter == null ? 0F : parameter.value;
        }

        if (path.startsWith("layer.") && project != null && comp != null)
        {
            String rest = path.substring(6);
            int dot = rest.lastIndexOf('.');

            if (dot > 0)
            {
                CompLayer layer = comp.getLayer(rest.substring(0, dot));

                if (layer != null)
                {
                    return layerProperty(layer, rest.substring(dot + 1), frame, layerValue);
                }
            }
        }

        if (path.startsWith("param."))
        {
            String[] parts = path.split("\\.", 3);

            if (parts.length == 3 && comp != null)
            {
                CompLayer layer = comp.getLayer(parts[1]);

                if (layer != null)
                {
                    return layerProperty(layer, parts[2], frame, layerValue);
                }
            }
        }

        return 0F;
    }

    private static float layerProperty(CompLayer layer, String property, float frame, float fallback)
    {
        if ("opacity".equals(property))
        {
            return layer.opacityFrame(frame);
        }

        if ("x".equals(property) || "y".equals(property) || "rotation".equals(property)
            || "scaleX".equals(property) || "scaleY".equals(property))
        {
            mchorse.bbs_mod.utils.pose.Transform transform = layer.transformAt(frame);

            return switch (property)
            {
                case "x" -> transform.translate.x;
                case "y" -> transform.translate.y;
                case "rotation" -> transform.rotate.z;
                case "scaleX" -> transform.scale.x;
                default -> transform.scale.y;
            };
        }

        return layer.scalarAt(property, layer.scalar(property, fallback), frame);
    }

    public static Map<String, String> emptyExpressions()
    {
        return Map.of();
    }
}
