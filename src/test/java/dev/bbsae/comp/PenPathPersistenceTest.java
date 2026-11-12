package dev.bbsae.comp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence contract for the pen tool's new data.
 *
 * <p>Two new boolean fields were added to project files: {@code "closed"} on a mask and on a shape
 * path. Both default to {@code true} so a project written by any earlier build keeps rendering
 * exactly as before (every mask used to be rendered closed). This test pins both directions of the
 * contract: the flag survives a JSON round trip, and a JSON without the key deserializes as closed.</p>
 */
public class PenPathPersistenceTest
{
    private static CompProject project(boolean closed)
    {
        CompProject project = new CompProject();
        CompDocument doc = new CompDocument();

        doc.id = "comp_1";
        doc.width = 1920;
        doc.height = 1080;
        project.comps.add(doc);

        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");

        layer.width = 400F;
        layer.height = 200F;
        doc.layers.add(layer);

        CompMask mask = new CompMask("mask_1");

        mask.closed = closed;
        mask.rectangle(-200F, -100F, 400F, 200F);
        layer.masks.add(mask);

        CompShape shape = new CompShape("path_1", CompShape.PATH);

        shape.closed = closed;
        shape.add(0F, 0F);
        shape.add(100F, 0F);
        shape.add(100F, 100F);
        layer.shapes.add(shape);

        return project;
    }

    private static JsonArray layers(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonArray("comps").get(0).getAsJsonObject()
            .getAsJsonArray("layers").get(0).getAsJsonArray();
    }

    @Test
    public void closedFlagsSurviveAJsonRoundTrip()
    {
        Gson gson = new Gson();
        CompProject project = project(false);
        String json = gson.toJson(project);

        assertTrue(json.contains("\"closed\":false"), "field name and value are part of the file format");

        CompProject loaded = gson.fromJson(json, CompProject.class);

        assertNotNull(loaded);
        assertEquals(1, loaded.comps.size());

        CompLayer layer = loaded.comps.get(0).layers.get(0);

        assertFalse(layer.masks.get(0).closed, "an open mask must stay open");
        assertFalse(layer.shapes.get(0).closed, "an open shape path must stay open");

        CompProject closedProject = gson.fromJson(gson.toJson(project(true)), CompProject.class);
        CompLayer closedLayer = closedProject.comps.get(0).layers.get(0);

        assertTrue(closedLayer.masks.get(0).closed);
        assertTrue(closedLayer.shapes.get(0).closed);
    }

    @Test
    public void legacyJsonWithoutClosedDeserializesClosed()
    {
        Gson gson = new Gson();
        String json = gson.toJson(project(false));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject layer = root.getAsJsonArray("comps").get(0).getAsJsonObject()
            .getAsJsonArray("layers").get(0).getAsJsonObject();

        /* Simulate a file written before the field existed. */
        layer.getAsJsonArray("masks").get(0).getAsJsonObject().remove("closed");
        layer.getAsJsonArray("shapes").get(0).getAsJsonObject().remove("closed");

        CompProject loaded = gson.fromJson(root.toString(), CompProject.class);
        CompLayer loadedLayer = loaded.comps.get(0).layers.get(0);

        assertTrue(loadedLayer.masks.get(0).closed, "old projects must keep their (always-closed) masks");
        assertTrue(loadedLayer.shapes.get(0).closed, "old shape paths were always closed");
    }

    @Test
    public void closedFlagIsOneFieldNotAChannel()
    {
        /* The field is deliberately not animatable: AE's mask path closure is a property of the
         * path value, and this project stores path animation as per-coordinate channels. Keeping it
         * out of the channel namespace is what lets the pen refuse insert/delete on animated paths
         * without also refusing to open them. */
        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");

        assertFalse(layer.hasScalarChannel("mask/mask_1/closed"));
        assertFalse(layer.hasScalarChannel("shape/path_1/closed"));
    }

    @Test
    public void snapshotContainsTheField()
    {
        CompProject project = project(false);

        project.id = "pen_persistence";
        project.name = "Pen Persistence";

        String snapshot = CompStore.snapshot(project);

        assertNotNull(snapshot);
        assertFalse(snapshot.isEmpty());
        assertTrue(snapshot.contains("\"closed\""), "the project serializer must persist the new flags");
    }

    @Test
    public void maskAndShapeDefaultsStayClosed()
    {
        assertEquals(true, new CompMask("fresh").closed);
        assertEquals(true, new CompShape("fresh", CompShape.PATH).closed);

        CompLayer layer = new CompLayer("layer_1", "Layer", "solid");
        CompMask mask = new CompMask("m");

        mask.rectangle(0F, 0F, 10F, 10F);
        layer.masks.add(mask);

        assertTrue(layer.maskAt(mask, 0F).closed);
    }
}
