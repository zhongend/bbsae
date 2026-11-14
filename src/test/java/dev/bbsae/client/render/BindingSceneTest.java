package dev.bbsae.client.render;

import dev.bbsae.comp.CompBinding;
import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding is a composition-space anchor, so it must survive the parent chain and must not be
 * camera-projected twice.
 *
 * <p>The old scene graph resolved the binding into the layer's <b>local</b> translation and then
 * multiplied the parent's world matrix, so a parented tracked layer landed at parent-transformed
 * coordinates instead of the projected target. A 3D bound layer was also fed back through the
 * camera projection, moving the anchor again.</p>
 */
public class BindingSceneTest
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

    private static CompLayer layer(CompDocument comp, String id, float x, float y)
    {
        CompLayer layer = new CompLayer(id, id, "solid");

        layer.ensureInitialized();
        layer.x = x;
        layer.y = y;
        layer.width = 100F;
        layer.height = 100F;
        layer.out = comp.duration;
        comp.layers.add(layer);

        return layer;
    }

    private static CompRenderContext context(CompProject project, CompDocument comp)
    {
        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    @Test
    public void aParentedBoundLayerLandsExactlyOnTheTarget()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer parent = layer(comp, "parent", 100F, 10F);
        CompLayer target = layer(comp, "target", 300F, 40F);
        CompLayer child = layer(comp, "child", 0F, 0F);

        child.parentId = parent.id;
        child.binding.enabled = true;
        child.binding.type = CompBinding.LAYER;
        child.binding.target = target.id;

        CompRenderContext ctx = context(project, comp);
        Matrix4f world = CompScene.world(ctx, child);

        /* The binding point is the target layer's position in composition space. The parent's
         * transform must not shift it. */
        assertEquals(300F, world.m30(), 0.01F);
        assertEquals(40F, world.m31(), 0.01F);
    }

    @Test
    public void aBoundThreeDLayerStaysScreenSpace()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer target = layer(comp, "target", 300F, 40F);
        CompLayer bound = layer(comp, "bound", 0F, 0F);

        bound.threeD = true;
        bound.z = 500F;
        bound.binding.enabled = true;
        bound.binding.type = CompBinding.LAYER;
        bound.binding.target = target.id;

        CompRenderContext ctx = context(project, comp);

        assertFalse(CompScene.cameraAffects(ctx, bound),
            "a bound layer is pinned to a screen point, not to the camera");
        assertFalse(CompScene.usePerspective(ctx, bound),
            "projecting the already-projected anchor would move it a second time");
        assertFalse(CompScene.sortsIn3D(ctx, bound),
            "a flat overlay should not be depth-sorted behind geometry");

        Matrix4f world = CompScene.world(ctx, bound);

        assertEquals(300F, world.m30(), 0.01F);
        assertEquals(40F, world.m31(), 0.01F);
    }

    @Test
    public void anUnboundThreeDLayerStillFollowsTheCamera()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer layer = layer(comp, "free", 0F, 0F);
        CompRenderContext ctx = context(project, comp);

        layer.threeD = true;

        assertTrue(CompScene.cameraAffects(ctx, layer));
        assertTrue(CompScene.sortsIn3D(ctx, layer));
    }

    @Test
    public void anUnresolvableBindingLeavesTheLayerUnchanged()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer bound = layer(comp, "bound", 0F, 0F);

        bound.threeD = true;
        bound.binding.enabled = true;
        bound.binding.type = CompBinding.REPLAY;
        bound.binding.target = "missing";

        /* Tests share one JVM; the resolver's film is process-wide. */
        BindingResolver.setFilm(null);

        /* No film/controller/projection in this render path: the binding cannot resolve. The layer
         * must then behave exactly like an unbound 3D layer instead of freezing itself in screen
         * space (the old isBound() check changed camera/sort semantics even when resolution failed,
         * which is why a bound layer seemed to vanish during BBS film playback). */
        CompRenderContext ctx = context(project, comp);

        assertTrue(CompScene.cameraAffects(ctx, bound),
            "an unresolved binding must keep camera behaviour");
        assertTrue(CompScene.usePerspective(ctx, bound),
            "an unresolved binding must keep its 3D projection");
        assertTrue(CompScene.sortsIn3D(ctx, bound),
            "an unresolved binding must keep depth sorting");
    }

    @Test
    public void theBindingPointIsResolvedOncePerFrame()
    {
        CompProject project = project();
        CompDocument comp = project.getCompOrFirst("main");
        CompLayer target = layer(comp, "target", 300F, 40F);
        CompLayer bound = layer(comp, "bound", 0F, 0F);

        bound.binding.enabled = true;
        bound.binding.type = CompBinding.LAYER;
        bound.binding.target = target.id;
        /* Smoothing makes a second resolution observable: it would lerp the point again. */
        bound.binding.smoothing = 0.9F;

        CompRenderContext ctx = context(project, comp);
        Matrix4f first = CompScene.world(ctx, bound);
        Matrix4f second = CompScene.world(ctx, bound);

        assertEquals(first.m30(), second.m30(), 0.0001F);
        assertEquals(first.m31(), second.m31(), 0.0001F);
    }
}
