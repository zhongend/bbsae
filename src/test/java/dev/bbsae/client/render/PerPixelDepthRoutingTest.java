package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic contract for the {@code CompPrefs.perPixelDepth} switch: which layers the renderer
 * sends through {@link DepthPlaneCompositor} when the preference is on.
 *
 * <p>{@code CompRenderer}'s gate is:</p>
 * <pre>
 * frame:  CompScene.spatial(context) &amp;&amp; (perPixelDepth || -Dbbsae.debugForceDepthPlane)
 * layer:  frame &amp;&amp; CompScene.usePerspective(context, layer) &amp;&amp; !-Dbbsae.debugNoDepthPlane
 * </pre>
 *
 * <p>The two scene functions are the only inputs of that decision besides the preference, so this
 * class pins them for the four project shapes the switch can meet: an AE 3D frame, an AE mixed
 * 2D/3D frame, a legacy-3D frame and a plain 2D frame. No GL context is needed, so these run on
 * every machine (including ones without a display).</p>
 */
public class PerPixelDepthRoutingTest
{
    private static List<CompLayer> depthRoutedLayers(CompProject project, CompDocument comp)
    {
        CompRenderContext context = DepthPlanePixelHarness.context(project, comp);
        List<CompLayer> routed = new ArrayList<>();

        if (!CompScene.spatial(context))
        {
            return routed;
        }

        for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
        {
            if (CompScene.usePerspective(context, layer))
            {
                routed.add(layer);
            }
        }

        return routed;
    }

    @Test
    public void aeLogicRoutesOnlyThe3dLayersOfTheBugProject()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();
        CompProject project = DepthPlanePixelHarness.project(false);

        List<CompLayer> routed = depthRoutedLayers(project, comp);

        assertEquals(2, routed.size(), "the two 3D shape layers take the depth path");
        assertEquals("polygon", routed.get(0).id);
        assertEquals("star", routed.get(1).id);
    }

    @Test
    public void aeLogicKeepsThe2dLayerOnThePainterPath()
    {
        CompDocument comp = DepthPlanePixelHarness.twoDPlusThreeDScene();

        assertEquals(2, depthRoutedLayers(DepthPlanePixelHarness.project(false), comp).size());
    }

    @Test
    public void legacyLogicRoutesEveryLayer()
    {
        CompDocument comp = DepthPlanePixelHarness.twoDPlusThreeDScene();
        CompProject legacy = DepthPlanePixelHarness.project(true);

        assertEquals(3, depthRoutedLayers(legacy, comp).size(),
            "legacy 3D logic projects the 2D layer as well, so it takes the depth path too");
    }

    @Test
    public void all2dCompositionRoutesNothingEvenWhenSpatial()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();

        for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
        {
            layer.threeD = false;
        }

        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertTrue(CompScene.spatial(context), "the tilted camera makes the frame spatial");
        assertEquals(0, depthRoutedLayers(DepthPlanePixelHarness.project(false), comp).size());
    }

    @Test
    public void aFlatFrameNeverAcquiresTheDepthTarget()
    {
        CompDocument comp = DepthPlanePixelHarness.bugScene();

        /* No camera and everything on the composition plane: nothing needs perspective. */
        comp.removeLayer("camera");
        comp.activeCameraId = "";

        for (CompLayer layer : DepthPlanePixelHarness.layersOf(comp))
        {
            layer.z = 0F;
        }

        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertFalse(CompScene.spatial(context),
            "a flat frame must not acquire the depth target even with the preference on");
        assertEquals(0, depthRoutedLayers(DepthPlanePixelHarness.project(false), comp).size());
    }

    @Test
    public void perspectiveDecisionIsPerLayerNotPerFrame()
    {
        CompDocument comp = DepthPlanePixelHarness.twoDPlusThreeDScene();
        CompRenderContext context = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(false), comp);

        assertFalse(CompScene.usePerspective(context, comp.getLayer("flat2d")));
        assertTrue(CompScene.usePerspective(context, comp.getLayer("polygon")));
        assertTrue(CompScene.usePerspective(context, comp.getLayer("star")));

        /* Switching the developer logic flips only the decision, not the layer data. */
        CompRenderContext legacy = DepthPlanePixelHarness.context(
            DepthPlanePixelHarness.project(true), comp);

        assertTrue(CompScene.usePerspective(legacy, comp.getLayer("flat2d")));
        assertEquals(0F, comp.getLayer("flat2d").z, 0.0001F);
    }
}
