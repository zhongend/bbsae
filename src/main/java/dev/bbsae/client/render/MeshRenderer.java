package dev.bbsae.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.bbsae.comp.CompEffect;
import dev.bbsae.comp.CompLayer;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders the 3D shape effects ({@code s3d_*}).
 *
 * <p>A shape effect replaces the layer's flat raster with a textured primitive: the layer content
 * is the surface texture, the primitive is placed at the layer's bounds. Meshes are convex, so
 * back-face culling alone resolves occlusion and the colour-only framebuffers are enough.</p>
 *
 * <p>Two entry paths exist:</p>
 * <ul>
 *   <li><b>layer-local bake</b> (3D layers): the input is the layer's own raster, so the primitive
 *       is drawn with an ortho projection that fills it;</li>
 *   <li><b>composition pass</b> (2D layers): the input is a composition-sized framebuffer, so the
 *       primitive is first drawn into a layer-sized target and that target is then drawn as a quad
 *       at the layer's projected rectangle. Sampling the composition directly stretched the whole
 *       composition across every face, which was the "3D effect looks wrong on 2D layers" bug.</li>
 * </ul>
 */
public final class MeshRenderer
{
    private static final Map<String, Mesh> MESHES = new HashMap<>();
    private static final String[] SHAPES =
        {"cube", "cylinder", "sphere", "pyramid", "cone", "torus", "ring", "plus", "octahedron",
            "hexprism", "triprism", "starprism", "heart", "hollowbox", "starpoly", "quad"};

    private MeshRenderer()
    {}

    public static boolean supports(String type)
    {
        if (type == null || !type.startsWith("s3d_"))
        {
            return false;
        }

        String shape = type.substring(4);

        for (String candidate : SHAPES)
        {
            if (candidate.equals(shape))
            {
                return true;
            }
        }

        return false;
    }

    public static GlFramebuffer apply(CompLayer layer, CompEffect effect, GlFramebuffer input, CompRenderContext context)
    {
        if (!supports(effect == null ? null : effect.type) || input == null || context == null)
        {
            return input;
        }

        GlslProgram program = CompShaders.mesh;

        if (program == null)
        {
            return input;
        }

        String shape = effect.type.substring(4);
        Mesh mesh = mesh(shape);
        float scale = Math.max(0.01F, effect.valueF("scale", 1F));
        float depthFactor = Math.max(0.05F, effect.valueF("depth", 0.5F));

        Matrix4f layerProjection = CompScene.projection(context, layer);
        Matrix4f layerModel = CompScene.model(context, layer);
        Matrix4f layerMvp = new Matrix4f(layerProjection).mul(layerModel);
        float compWidth = Math.max(1F, context.width);
        float compHeight = Math.max(1F, context.height);
        float halfW = Math.abs(layer.width) * 0.5F;
        float halfH = Math.abs(layer.height) * 0.5F;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int i = 0; i < 4; i++)
        {
            float x = (i == 0 || i == 3) ? -halfW : halfW;
            float y = (i < 2) ? -halfH : halfH;
            org.joml.Vector4f p = layerMvp.transform(new org.joml.Vector4f(x, y, 0F, 1F));
            float w = Math.abs(p.w) < 0.0001F ? 1F : p.w;
            float ndcX = p.x / w;
            float ndcY = p.y / w;
            float px = (ndcX * 0.5F + 0.5F) * compWidth;
            float py = (1F - (ndcY * 0.5F + 0.5F)) * compHeight;

            minX = Math.min(minX, px);
            maxX = Math.max(maxX, px);
            minY = Math.min(minY, py);
            maxY = Math.max(maxY, py);
        }

        float rectWidth = Math.max(1F, maxX - minX);
        float rectHeight = Math.max(1F, maxY - minY);
        Vector2f surfaceUvScale = new Vector2f(rectWidth / compWidth, -rectHeight / compHeight);
        Vector2f surfaceUvOffset = new Vector2f(minX / compWidth, 1F - minY / compHeight);

        if (context.layerLocalPass)
        {
            /* The input is the layer raster: fill it with the primitive. */
            float width = Math.max(1F, input.width);
            float height = Math.max(1F, input.height);
            GlFramebuffer output = FboPool.acquire(input.width, input.height);
            Matrix4f projection = new Matrix4f().setOrtho(0F, width, height, 0F, -10000F, 10000F);
            Matrix4f model = new Matrix4f()
                .translate(width * 0.5F, height * 0.5F, 0F)
                .rotateX((float) Math.toRadians(effect.valueF("rotate_x", 0F)))
                .rotateY((float) Math.toRadians(effect.valueF("rotate_y", 0F)))
                .rotateZ((float) Math.toRadians(effect.valueF("rotate_z", 0F)))
                .scale(width * scale, height * scale, Math.max(1F, height * depthFactor) * scale);

            drawMesh(program, mesh, new Matrix4f(projection).mul(model), input, output, effect, false,
                new Vector2f(1F, 1F), new Vector2f(0F, 0F));

            return output;
        }

        /* Composition pass: draw the primitive into a layer-sized target first, then place that
         * target as a quad at the layer's projected rectangle. */
        float scaleX = input.width / compWidth;
        float scaleY = input.height / compHeight;
        int layerWidth = Math.max(1, Math.round(Math.abs(layer.width) * scaleX));
        int layerHeight = Math.max(1, Math.round(Math.abs(layer.height) * scaleY));
        GlFramebuffer surface = FboPool.acquire(layerWidth, layerHeight);
        Matrix4f surfaceProjection = new Matrix4f().setOrtho(0F, layerWidth, layerHeight, 0F, -10000F, 10000F);
        Matrix4f surfaceModel = new Matrix4f()
            .translate(layerWidth * 0.5F, layerHeight * 0.5F, 0F)
            .rotateX((float) Math.toRadians(effect.valueF("rotate_x", 0F)))
            .rotateY((float) Math.toRadians(effect.valueF("rotate_y", 0F)))
            .rotateZ((float) Math.toRadians(effect.valueF("rotate_z", 0F)))
            .scale(layerWidth * scale, layerHeight * scale, Math.max(1F, layerHeight * depthFactor) * scale);

        drawMesh(program, surfaceMesh(shape), new Matrix4f(surfaceProjection).mul(surfaceModel), input, surface, effect, false,
            surfaceUvScale, surfaceUvOffset);

        if (Boolean.getBoolean("bbsae.debugDumpSurface"))
        {
            try
            {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    surface.width, surface.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, surface.width, surface.height, surface.readTopDown(), 0, surface.width);
                java.io.File file = new java.io.File(
                    net.minecraft.client.MinecraftClient.getInstance().runDirectory, "screenshots/mesh-surface.png");
                file.getParentFile().mkdirs();
                javax.imageio.ImageIO.write(image, "png", file);
                org.slf4j.LoggerFactory.getLogger("bbsae").info(
                    "mesh surface dump {}x{} rect [{},{}]-[{},{}] uv {}x{} off {}x{}",
                    surface.width, surface.height, minX, minY, maxX, maxY,
                    surfaceUvScale.x, surfaceUvScale.y, surfaceUvOffset.x, surfaceUvOffset.y);
            }
            catch (Exception e)
            {
                org.slf4j.LoggerFactory.getLogger("bbsae").error("mesh surface dump failed", e);
            }
        }

        GlFramebuffer output = FboPool.acquire(input.width, input.height);
        Matrix4f quadProjection = new Matrix4f().setOrtho(0F, compWidth, compHeight, 0F, -10000F, 10000F);
        Matrix4f quadModel = new Matrix4f()
            .translate((minX + maxX) * 0.5F, (minY + maxY) * 0.5F, 0F)
            .scale(rectWidth, rectHeight, 1F);

        drawMesh(program, surfaceQuad(), new Matrix4f(quadProjection).mul(quadModel), surface, output, effect, true,
            new Vector2f(1F, 1F), new Vector2f(0F, 0F));
        FboPool.release(surface);

        return output;
    }

    private static void drawMesh(GlslProgram program, Mesh mesh, Matrix4f mvp, GlFramebuffer source,
                                 GlFramebuffer target, CompEffect effect, boolean unlit,
                                 Vector2f uvScale, Vector2f uvOffset)
    {
        target.begin();
        GlStateManager._disableBlend();
        GlStateManager._disableDepthTest();
        /* No back-face culling: the painter sort draws every triangle far-to-near, so back faces
         * are covered by front faces instead of needing consistent winding. Culling a wrongly
         * wound primitive used to punch sawtooth notches into ring/torus walls. */
        GlStateManager._disableCull();
        program.use();
        PassRenderer.bind(program, "Source", 0, source);
        program.set("Mvp", mvp);

        Matrix4f normalMatrix = new Matrix4f(mvp).invert().transpose();
        program.set("NormalMatrix", normalMatrix);

        float azimuth = (float) Math.toRadians(effect.valueF("light_angle", 135F));
        float elevation = (float) Math.toRadians(effect.valueF("light_elevation", 35F));
        program.set("LightDir",
            (float) Math.cos(elevation) * (float) Math.cos(azimuth),
            (float) Math.sin(elevation),
            (float) Math.cos(elevation) * (float) Math.sin(azimuth));
        program.set("Ambient", unlit ? 1F : Math.max(0F, effect.valueF("ambient", 0.35F)));
        program.set("Intensity", unlit ? 0F : Math.max(0F, effect.valueF("light_intensity", 0.9F)));
        program.set("Shine", Math.max(1F, effect.valueF("shine", 24F)));
        float repeat = Math.max(0.01F, effect.valueF("tex_repeat", 1F));
        program.set("TexRepeat", repeat, repeat);
        program.set("TexOffset", effect.valueF("tex_offset_x", 0F), effect.valueF("tex_offset_y", 0F));
        program.set("TexAngle", effect.valueF("tex_angle", 0F));
        program.set("UvScale", uvScale.x, uvScale.y);
        program.set("UvOffset", uvOffset.x, uvOffset.y);
        mesh.drawSorted(mvp);
    }

    private static Mesh mesh(String shape)
    {
        return MESHES.computeIfAbsent(shape, (key) -> switch (key)
        {
            case "cylinder" -> MeshShapes.cylinder(24);
            case "sphere" -> MeshShapes.sphere(16, 24);
            case "pyramid" -> MeshShapes.pyramid(4);
            case "cone" -> MeshShapes.cone(24);
            case "torus" -> MeshShapes.torus(24, 12);
            case "ring" -> MeshShapes.ring(32);
            case "plus" -> MeshShapes.plus();
            case "octahedron" -> MeshShapes.octahedron();
            case "hexprism" -> MeshShapes.prism(6);
            case "triprism" -> MeshShapes.prism(3);
            case "starprism" -> MeshShapes.starPrism(5);
            case "heart" -> MeshShapes.heart(64);
            case "hollowbox" -> MeshShapes.hollowBox(0.08F);
            case "starpoly" -> MeshShapes.starPoly(5);
            case "quad" -> MeshShapes.quad();
            default -> MeshShapes.cube();
        });
    }

    /** Shape used to fill the layer-sized surface: same primitive. */
    private static Mesh surfaceMesh(String shape)
    {
        return mesh(shape);
    }

    /** Unit quad used to place the finished surface into the composition. */
    private static Mesh surfaceQuad()
    {
        return mesh("quad");
    }

    public static void delete()
    {
        for (Mesh mesh : MESHES.values())
        {
            mesh.delete();
        }

        MESHES.clear();
    }
}
