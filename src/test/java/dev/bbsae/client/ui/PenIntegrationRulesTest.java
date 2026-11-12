package dev.bbsae.client.ui;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompMask;
import dev.bbsae.comp.CompShape;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration rules of the pen tool that can be checked on plain model objects: is the edited path
 * still alive after layer/path deletion and composition switches, and which key switches back to
 * the selection tool.
 */
public class PenIntegrationRulesTest
{
    private static CompDocument document(CompLayer layer)
    {
        CompDocument doc = new CompDocument();

        doc.layers.add(layer);

        return doc;
    }

    @Test
    public void targetStopsExistingWhenLayerOrPathGoesAway()
    {
        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");
        CompMask mask = new CompMask("mask_1");
        CompShape path = new CompShape("path_1", CompShape.PATH);

        mask.rectangle(0F, 0F, 100F, 100F);
        path.add(0F, 0F);
        path.add(100F, 0F);
        path.add(100F, 100F);

        layer.masks.add(mask);
        layer.shapes.add(path);

        CompDocument doc = document(layer);

        assertTrue(UIPenTool.targetExists(doc, layer, true, 0), "mask path is alive");
        assertTrue(UIPenTool.targetExists(doc, layer, false, 0), "shape path is alive");

        /* Another composition (precomp switch / project reload) holds other layers: the target is
         * stale even though the layer object still exists in memory. */
        assertFalse(UIPenTool.targetExists(document(new CompLayer("other", "Other", "solid")), layer, true, 0));

        /* Layer deleted from the edited composition. */
        doc.layers.remove(layer);
        assertFalse(UIPenTool.targetExists(doc, layer, true, 0));

        doc.layers.add(layer);

        /* Path deleted but the layer survives. */
        layer.masks.clear();
        layer.shapes.clear();

        assertFalse(UIPenTool.targetExists(doc, layer, true, 0));
        assertFalse(UIPenTool.targetExists(doc, layer, false, 0));

        /* Out-of-range index after a point-list edit. */
        CompMask shortMask = new CompMask("mask_2");

        layer.masks.add(shortMask);
        assertTrue(UIPenTool.targetExists(doc, layer, true, 0));
        assertFalse(UIPenTool.targetExists(doc, layer, true, 3));

        /* A non-path shape slot is not a pen target. */
        layer.shapes.add(new CompShape("rect_1", CompShape.RECT));
        assertFalse(UIPenTool.targetExists(doc, layer, false, 0));

        assertFalse(UIPenTool.targetExists(null, layer, true, 0));
        assertFalse(UIPenTool.targetExists(doc, null, true, 0));
    }

    @Test
    public void vOrAReturnsToTheSelectionTool()
    {
        assertTrue(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_V, false, false, false));
        assertTrue(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_A, false, false, false));

        /* Modifier combinations belong to the shortcut table (Ctrl+A select all, etc). */
        assertFalse(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_A, true, false, false));
        assertFalse(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_V, false, true, false));
        assertFalse(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_A, false, false, true));
        assertFalse(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_G, false, false, false));
        assertFalse(UIPenTool.isSelectionToolSwitchKey(GLFW.GLFW_KEY_DELETE, false, false, false));
    }
}
