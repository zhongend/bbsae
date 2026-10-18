package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompEffectRegistry;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the PIXEL-family uniform plan.
 *
 * <p>These exist because a shader parameter can be silently dead: {@code glUniform*} on a
 * location that the compiler removed (or a renderer branch that never reads an Effect field)
 * does not raise an error. The generic "every parameter changes the plan" test below would have
 * caught the original bugs where glitch.speed and vhs.speed/noise were ignored.</p>
 */
public class PixelPlanTest
{
    private static final String[] ALIGHT_MOTION_EFFECTS = {
        "scanlines", "glitch", "vhs", "halftone", "emboss", "mirror", "kaleidoscope"
    };

    private static CompRenderContext context(float time)
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = 1920;
        comp.height = 1080;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, time, null, null, null);
    }

    private static float changedValue(CompEffectRegistry.Param param)
    {
        return param.defaultValue == param.min ? param.max : param.min;
    }

    @Test
    public void everyAlightMotionEffectResolvesToAPlan()
    {
        for (String id : ALIGHT_MOTION_EFFECTS)
        {
            CompEffect effect = CompEffectRegistry.create(id, 1);
            EffectRenderer.PixelPlan plan = EffectRenderer.planPixel(effect, context(2.5F));

            assertNotNull(plan, "no PIXEL plan for " + id);
            assertTrue(plan.mode >= 2 && plan.mode <= 8, id + " must use one of the new PIXEL modes");
        }
    }

    @Test
    public void everyAlightMotionParameterChangesThePlan()
    {
        CompRenderContext context = context(2.5F);

        for (String id : ALIGHT_MOTION_EFFECTS)
        {
            CompEffectRegistry.Definition definition = CompEffectRegistry.get(id);

            assertNotNull(definition, id);

            for (CompEffectRegistry.Param param : definition.params)
            {
                CompEffect baselineEffect = CompEffectRegistry.create(id, 1);
                List<Object> baseline = EffectRenderer.planPixel(baselineEffect, context).signature();
                CompEffect changedEffect = CompEffectRegistry.create(id, 1);

                changedEffect.put(param.key, (double) changedValue(param));

                List<Object> changed = EffectRenderer.planPixel(changedEffect, context).signature();

                assertNotEquals(baseline, changed,
                    id + "/" + param.key + " is declared in the registry but never reaches the shader");
            }

            for (CompEffectRegistry.ColorParam color : definition.colorParams)
            {
                CompEffect baselineEffect = CompEffectRegistry.create(id, 1);
                List<Object> baseline = EffectRenderer.planPixel(baselineEffect, context).signature();
                CompEffect changedEffect = CompEffectRegistry.create(id, 1);
                int replacement = color.defaultValue == 0xFF00FF00 ? 0xFFFF00FF : 0xFF00FF00;

                changedEffect.putColor(color.key, replacement);

                List<Object> changed = EffectRenderer.planPixel(changedEffect, context).signature();

                assertNotEquals(baseline, changed,
                    id + "/" + color.key + " is declared in the registry but never reaches the shader");
            }
        }
    }

    @Test
    public void timeBasedEffectsUseContextTimeAndSpeed()
    {
        CompEffect glitch = CompEffectRegistry.create("glitch", 1);

        glitch.put("speed", 2D);

        assertEquals(6F, EffectRenderer.planPixel(glitch, context(3F)).phase, 0.0001F,
            "glitch phase must be time * speed");

        CompEffect vhs = CompEffectRegistry.create("vhs", 1);

        vhs.put("speed", 0.5D);

        assertEquals(1.5F, EffectRenderer.planPixel(vhs, context(3F)).phase, 0.0001F,
            "vhs phase must be time * speed");
    }

    @Test
    public void halftoneDefaultsMatchTheAlightMotionProperties()
    {
        CompEffect effect = CompEffectRegistry.create("halftone", 1);
        EffectRenderer.PixelPlan plan = EffectRenderer.planPixel(effect, context(0F));

        assertEquals(4, plan.mode);
        assertEquals(0.5F, plan.amount, 0.0001F, "strength");
        assertEquals(100F, plan.p1, 0.0001F, "dots across the project");
        assertEquals(0.5F, plan.p2, 0.0001F, "row phase");
        assertEquals(45F, plan.p3, 0.0001F, "angle");
        assertEquals(0xFF000000, plan.dotColor);
        assertEquals(0xFFFFFFFF, plan.paperColor);
    }

    @Test
    public void unsupportedTypeHasNoPlan()
    {
        CompEffect effect = new CompEffect("fx1", "not_a_real_effect");

        assertNull(EffectRenderer.planPixel(effect, context(0F)));
    }
}
