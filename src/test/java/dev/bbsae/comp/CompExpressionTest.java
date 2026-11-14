package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expression grammar: unary precedence and AE-style wiggle(frequency, amplitude). */
public class CompExpressionTest
{
    private static CompExpression.Context context(float frame, int seed)
    {
        return new CompExpression.Context()
        {
            @Override
            public float time()
            {
                return frame / 30F;
            }

            @Override
            public float frame()
            {
                return frame;
            }

            @Override
            public float fps()
            {
                return 30F;
            }

            @Override
            public float value()
            {
                return 0F;
            }

            @Override
            public float variable(String path)
            {
                return 0F;
            }

            @Override
            public int seed()
            {
                return seed;
            }
        };
    }

    private static float eval(CompExpression.Context context, String expression)
    {
        return CompExpression.eval(expression, context, 0F);
    }

    private static float eval(String expression)
    {
        return eval(context(10F, 0), expression);
    }

    @Test
    public void unaryMinusBindsBelowExponentiationAndChains()
    {
        assertEquals(-4F, eval("-2^2"), 0.0001F);
        assertEquals(4F, eval("(-2)^2"), 0.0001F);
        assertEquals(512F, eval("2^3^2"), 0.0001F, "exponentiation is right-associative");
        assertEquals(0.25F, eval("2^-2"), 0.0001F);
        assertEquals(0.125F, eval("2^-3"), 0.0001F);
        assertEquals(5F, eval("--5"), 0.0001F);
        assertEquals(-5F, eval("+-5"), 0.0001F);
        assertEquals(-5F, eval("-(2+3)"), 0.0001F);
    }

    @Test
    public void wiggleIsSmoothAndBounded()
    {
        float frame10 = eval(context(10F, 0), "wiggle(2, 50)");
        float frame11 = eval(context(11F, 0), "wiggle(2, 50)");

        assertTrue(Math.abs(frame10) <= 50.0001F, "amplitude bounds the result");
        assertTrue(Math.abs(frame11) <= 50.0001F, "amplitude bounds the result");
        assertTrue(Math.abs(frame11 - frame10) < 20F,
            "adjacent frames at 2 Hz must be continuous, not independent random jumps");
    }

    @Test
    public void wiggleSeedSeparatesProperties()
    {
        float x = eval(context(10F, "x".hashCode()), "wiggle(2, 50)");
        float y = eval(context(10F, "y".hashCode()), "wiggle(2, 50)");

        assertTrue(Math.abs(x - y) > 0.0001F,
            "different properties must not share the same wiggle sequence");
    }

    @Test
    public void wiggleZeroAmplitudeIsZero()
    {
        assertEquals(0F, eval("wiggle(8, 0)"), 0.0001F);
    }
}
