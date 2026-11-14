package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expression grammar: unary precedence and AE-style wiggle(frequency, amplitude). */
public class CompExpressionTest
{
    private static final CompExpression.Context CONTEXT = new CompExpression.Context()
    {
        @Override
        public float time()
        {
            return 10F / 30F;
        }

        @Override
        public float frame()
        {
            return 10F;
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
    };

    private static float eval(String expression)
    {
        return CompExpression.eval(expression, CONTEXT, 0F);
    }

    @Test
    public void unaryMinusBindsBelowExponentiation()
    {
        assertEquals(-4F, eval("-2^2"), 0.0001F);
        assertEquals(4F, eval("(-2)^2"), 0.0001F);
        assertEquals(512F, eval("2^3^2"), 0.0001F, "exponentiation is right-associative");
        assertEquals(0.25F, eval("2^-2"), 0.0001F);
    }

    @Test
    public void wiggleUsesFrequencyAndAmplitude()
    {
        assertEquals(0F, eval("wiggle(8, 0)"), 0.0001F, "zero amplitude is zero");
        assertTrue(Math.abs(eval("wiggle(8, 3)")) <= 3.0001F, "amplitude bounds the result");

        float atTen = eval("wiggle(8, 3)");
        float differentFrequency = eval("wiggle(2, 3)");

        assertTrue(Math.abs(atTen - differentFrequency) > 0.0001F,
            "frequency must change the sampled noise, not just its seed");
    }
}
