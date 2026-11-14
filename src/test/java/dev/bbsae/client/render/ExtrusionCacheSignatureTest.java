package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The parked-playhead frame cache must notice every extrusion edit.
 *
 * <p>{@code CompRenderCache.signature} is what decides whether the preview re-renders or keeps the
 * previous texture. A geometry field that is missing from it produces the classic "I changed the
 * thickness and nothing happened until I moved the playhead" bug, so all of them are pinned here
 * one by one.</p>
 */
public class ExtrusionCacheSignatureTest
{
    private static CompProject project()
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.id = "main";
        comp.width = 1920;
        comp.height = 1080;
        comp.fps = 30F;
        comp.duration = 10F;
        project.comps.add(comp);

        return project;
    }

    private static CompLayer layer(CompDocument comp)
    {
        CompLayer layer = new CompLayer("layer", "Layer", "text");

        layer.threeD = true;
        layer.text = "EXTRUDE";
        layer.out = comp.duration;
        comp.layers.add(layer);

        return layer;
    }

    private static long signature(CompProject project, CompDocument comp)
    {
        return CompRenderCache.signature(project, comp, 0F, true, true, 0, 1280, 720, false);
    }

    @Test
    public void everyExtrusionFieldInvalidatesTheCachedFrame()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer layer = layer(comp);

        long base = signature(project, comp);

        layer.extrude = true;
        long on = signature(project, comp);
        assertNotEquals(base, on, "turning extrusion on must re-render");

        layer.extrudeDepth = 120F;
        long depth = signature(project, comp);
        assertNotEquals(on, depth, "thickness changes the silhouette");

        layer.extrudeSlices = 24;
        long slices = signature(project, comp);
        assertNotEquals(depth, slices, "slice count changes the side shading");

        layer.extrudeShade = 0.8F;
        long shade = signature(project, comp);
        assertNotEquals(slices, shade, "side shading changes pixels");

        layer.extrudeUseLayerColor = false;
        long ownColour = signature(project, comp);
        assertNotEquals(shade, ownColour, "the side colour source changes pixels");

        layer.extrudeColor = 0xFF00FF00;
        long colour = signature(project, comp);
        assertNotEquals(ownColour, colour, "the thickness colour changes pixels");

        layer.extrudeDirection = dev.bbsae.comp.CompExtrusion.DIRECTION_FRONT;
        long direction = signature(project, comp);
        assertNotEquals(colour, direction, "the extrusion direction moves the volume");

        layer.extrudeCap = false;
        long cap = signature(project, comp);
        assertNotEquals(direction, cap, "removing the cap removes a plane");
    }

    @Test
    public void collapseTransformationsInvalidatesTheCachedFrame()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer precomp = new CompLayer("pc", "Precomp", "precomp");

        precomp.threeD = true;
        precomp.out = comp.duration;
        comp.layers.add(precomp);

        long before = signature(project, comp);

        precomp.collapse = true;

        assertNotEquals(before, signature(project, comp),
            "collapse changes the nested projection, so the parked frame cannot be reused");
    }

    @Test
    public void linkedReplayIdentityInvalidatesTheCachedFrame()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer actor = new CompLayer("actor", "Actor", "replay");

        actor.threeD = true;
        actor.out = comp.duration;
        actor.replayId = "replay_a";
        comp.layers.add(actor);

        long before = signature(project, comp);

        actor.replayId = "replay_b";

        assertNotEquals(before, signature(project, comp),
            "a different linked replay is a different actor");
    }
}
