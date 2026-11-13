package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code extrude3d} is a geometry effect, not a pixel filter: the registry entry must exist, keep
 * its depth/slices/shade parameters, and stay out of the layer-local bake.
 */
public class ExtrudeEffectTest
{
    @Test
    public void extrudeEffectIsRegisteredWithItsGeometryParameters()
    {
        CompEffectRegistry.Definition definition = CompEffectRegistry.get("extrude3d");

        assertNotNull(definition, "the extrusion effect must be discoverable in the library");
        assertEquals("挤压体积", definition.name);
        assertEquals("AM · 3D 形状", definition.category);
        assertTrue(definition.params.stream().anyMatch((param) -> "depth".equals(param.key)));
        assertTrue(definition.params.stream().anyMatch((param) -> "slices".equals(param.key)));
        assertTrue(definition.params.stream().anyMatch((param) -> "shade".equals(param.key)));

        CompEffect effect = CompEffectRegistry.create("extrude3d", 1);

        assertEquals(60F, effect.valueF("depth", 0F), 0.001F);
        assertEquals(16F, effect.valueF("slices", 0F), 0.001F);
        assertEquals(0.35F, effect.valueF("shade", 0F), 0.001F);
    }
}
