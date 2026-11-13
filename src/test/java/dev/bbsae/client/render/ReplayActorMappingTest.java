package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The comp-camera actor mapping must place the actor group at the layer origin and convert blocks
 * to composition pixels with Y inverted. This is the one piece of {@link ReplayActorPass} that can
 * be proven without a film, a GL context or a running client.
 */
public class ReplayActorMappingTest
{
    @Test
    public void actorCentreLandsOnTheLayerOrigin()
    {
        CompLayer layer = layer();
        CompRenderContext context = context();

        Vector3f atCentre = ReplayActorPass.actorMapping(layer, context, 10D, 20D, 30D)
            .transformPosition(new Vector3f(10F, 20F, 30F));

        assertEquals(50F, atCentre.x, 0.001F);
        assertEquals(50F, atCentre.y, 0.001F);
        assertEquals(0F, atCentre.z, 0.001F);
    }

    @Test
    public void oneBlockUsesTwelvePixelsAndFlipsY()
    {
        CompLayer layer = layer();
        CompRenderContext context = context();

        Vector3f oneBlock = ReplayActorPass.actorMapping(layer, context, 0D, 0D, 0D)
            .transformPosition(new Vector3f(1F, 1F, 0F));

        /* 100 px comp height * 0.12 = 12 px/block; comp Y is down. */
        assertEquals(62F, oneBlock.x, 0.001F);
        assertEquals(38F, oneBlock.y, 0.001F);
    }

    private static CompLayer layer()
    {
        CompLayer layer = new CompLayer("replay", "Replay", "replay");

        layer.x = 50F;
        layer.y = 50F;
        layer.width = 50F;
        layer.height = 50F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;
        layer.threeD = true;

        return layer;
    }

    private static CompRenderContext context()
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = 100;
        comp.height = 100;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, 0F, null, null, null);
    }
}
