package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the layer/effect coordinate space.
 *
 * <p>The composition FBO is drawn with a y-down ortho, so its texture V axis is flipped relative to
 * composition coordinates. Every effect that maps comp UV into layer space has to account for that;
 * otherwise a gradient stays glued to the comp and a moved layer acts like a mask over it.</p>
 */
public class EffectSpaceTest
{
    private static final float EPS = 0.001F;

    private static CompRenderContext context()
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = 1920;
        comp.height = 1080;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    private static CompLayer solid(float x, float y)
    {
        CompLayer layer = new CompLayer("solid", "Solid", "solid");

        layer.x = x;
        layer.y = y;
        layer.width = 200F;
        layer.height = 100F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;

        return layer;
    }

    private static Matrix4f translate(float x, float y)
    {
        return new Matrix4f().translate(x, y, 0F);
    }

    @Test
    public void layerCentreMapsToHalfUv()
    {
        CompRenderContext context = context();
        EffectSpace space = EffectSpace.ofBounds(solid(500F, 400F), translate(500F, 400F), context, -100F, -50F, 200F, 100F);

        /* Composition (500, 400) is the layer centre; comp uv is (x / w, 1 - y / h). */
        float u = 500F / 1920F;
        float v = 1F - 400F / 1080F;
        Vector2f local = space.localPixel(u, v);

        assertEquals(0.5F, local.x / 200F, EPS, "local x at layer centre");
        assertEquals(0.5F, local.y / 100F, EPS, "local y at layer centre");
    }

    @Test
    public void movingLayerKeepsEffectAttachedToIt()
    {
        CompRenderContext context = context();
        Matrix4f moved = translate(700F, 650F);
        EffectSpace space = EffectSpace.ofBounds(solid(700F, 650F), moved, context, -100F, -50F, 200F, 100F);

        float u = 700F / 1920F;
        float v = 1F - 650F / 1080F;
        Vector2f local = space.localPixel(u, v);

        assertEquals(0.5F, local.x / 200F, EPS);
        assertEquals(0.5F, local.y / 100F, EPS);

        /* And the inverse mapping must land back on the moved centre. */
        Vector2f compUv = space.localToCompUv(0.5F, 0.5F);

        assertEquals(u, compUv.x, EPS, "localToCompUv x");
        assertEquals(v, compUv.y, EPS, "localToCompUv y");
    }

    @Test
    public void verticalMotionIsNotMirrored()
    {
        CompRenderContext context = context();
        EffectSpace up = EffectSpace.ofBounds(solid(960F, 440F), translate(960F, 440F), context, -100F, -50F, 200F, 100F);
        EffectSpace down = EffectSpace.ofBounds(solid(960F, 640F), translate(960F, 640F), context, -100F, -50F, 200F, 100F);

        /* One fixed screen point (y=540): as the layer moves up, that point falls lower inside the
         * layer (local v grows); as the layer moves down it falls above the layer (local v shrinks).
         * A flipped V axis reverses both signs. */
        float u = 960F / 1920F;
        float v = 1F - 540F / 1080F;
        float upV = up.localPixel(u, v).y / 100F;
        float downV = down.localPixel(u, v).y / 100F;

        assertEquals(1.5F, upV, EPS, "layer at y=440, point y=540 -> local 1.5");
        assertEquals(-0.5F, downV, EPS, "layer at y=640, point y=540 -> local -0.5");
    }

    @Test
    public void identitySpaceMatchesCompUv()
    {
        CompRenderContext context = context();
        EffectSpace space = EffectSpace.identity(context);

        Vector2f compUv = space.localToCompUv(0.25F, 0.75F);

        assertEquals(0.25F, compUv.x, EPS);
        /* Identity space still obeys the FBO V flip: local v=0.75 is comp v=0.25. */
        assertEquals(0.25F, compUv.y, EPS);
    }

    @Test
    public void identityLayerUvAndLocalToCompUvAreInverse()
    {
        CompRenderContext context = context();
        EffectSpace space = EffectSpace.identity(context);
        float u = 0.3F;
        float v = 0.2F;
        Vector2f local = space.localPixel(u, v);

        /* Identity space still grows local y downwards, so local v is 1 - comp v. */
        assertEquals(u, local.x / 1920F, EPS);
        assertEquals(1F - v, local.y / 1080F, EPS);

        Vector2f roundTrip = space.localToCompUv(u, 1F - v);

        assertEquals(u, roundTrip.x, EPS);
        assertEquals(v, roundTrip.y, EPS);
    }
}
