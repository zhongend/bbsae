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
 * is the surface texture, the primitive is placed at the layer's bounds and transformed by the same
 * world matrix and projection the layer itself uses. Meshes are convex, so back-face culling alone
 * resolves occlusion and the existing colour-only framebuffers are enough (no depth attachment, no
 * changes to the render graph).</p>
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
        GlFramebuffer output = FboPool.acquire(input.width, input.height);

        /* Two coordinate spaces reach this renderer:
         * - the layer-local bake (3D layers, styles): the input IS the layer raster, so the
         *   primitive simply fills it with an ortho projection;
         * - the composition pass (2D layers): the input is comp-space, so the primitive is placed
         *   inside the layer's projected screen bounds.
         * Using the camera/model matrix in the bake was the bug that made 3D shapes tiny and
         * offset: their pixels were layer-local but the matrix was composition-space. */
        boolean local = context.layerLocalPass;
        float targetWidth = Math.max(1F, output.width);
        float targetHeight = Math.max(1F, output.height);
        Matrix4f projection;
        float centreX;
        float centreY;
        float width;
        float height;

        if (local)
        {
            projection = new Matrix4f().setOrtho(0F, targetWidth, targetHeight, 0F, -10000F, 10000F);
            centreX = targetWidth * 0.5F;
            centreY = targetHeight * 0.5F;
            width = targetWidth;
            height = targetHeight;
        }
        else
        {
            projection = new Matrix4f().setOrtho(0F, targetWidth, targetHeight, 0F, -10000F, 10000F);
            float halfW = Math.max(1F, Math.abs(layer.width)) * 0.5F;
            float halfH = Math.max(1F, Math.abs(layer.height)) * 0.5F;
            Vector2f centre = CompScene.project(context, layer, 0F, 0F, 0F);
            float scaleX = targetWidth / Math.max(1F, context.width);
            float scaleY = targetHeight / Math.max(1F, context.height);
            centreX = centre.x * scaleX;
            centreY = centre.y * scaleY;
            width = Math.max(1F, halfW * 2F * scaleX);
            height = Math.max(1F, halfH * 2F * scaleY);
        }

        float depth = Math.max(1F, height * Math.max(0.05F, effect.valueF("depth", 0.5F)));
        float scale = Math.max(0.01F, effect.valueF("scale", 1F));

        Matrix4f localMatrix = new Matrix4f()
            .translate(centreX, centreY, 0F)
            .rotateX((float) Math.toRadians(effect.valueF("rotate_x", 0F)))
            .rotateY((float) Math.toRadians(effect.valueF("rotate_y", 0F)))
            .rotateZ((float) Math.toRadians(effect.valueF("rotate_z", 0F)))
            .scale(width * scale, height * scale, depth * scale);
        Matrix4f mvp = new Matrix4f(projection).mul(localMatrix);

        output.begin();
        GlStateManager._disableBlend();
        GlStateManager._disableDepthTest();
        GlStateManager._enableCull();
        GL11.glFrontFace(GL11.GL_CW);
        program.use();
        PassRenderer.bind(program, "Source", 0, input);
        program.set("Mvp", mvp);

        Matrix4f normalMatrix = new Matrix4f(mvp).invert().transpose();
        program.set("NormalMatrix", normalMatrix);
        float azimuth = (float) Math.toRadians(effect.valueF("light_angle", 135F));
        float elevation = (float) Math.toRadians(effect.valueF("light_elevation", 35F));
        program.set("LightDir",
            (float) Math.cos(elevation) * (float) Math.cos(azimuth),
            (float) Math.sin(elevation),
            (float) Math.cos(elevation) * (float) Math.sin(azimuth));
        program.set("Ambient", Math.max(0F, effect.valueF("ambient", 0.35F)));
        program.set("Intensity", Math.max(0F, effect.valueF("light_intensity", 0.9F)));
        program.set("Shine", Math.max(1F, effect.valueF("shine", 24F)));
        program.set("TexRepeat", Math.max(0.01F, effect.valueF("tex_repeat", 1F)),
            Math.max(0.01F, effect.valueF("tex_repeat", 1F)));
        program.set("TexOffset", effect.valueF("tex_offset_x", 0F), effect.valueF("tex_offset_y", 0F));
        program.set("TexAngle", effect.valueF("tex_angle", 0F));
        mesh.draw();
        GL11.glFrontFace(GL11.GL_CCW);
        GlStateManager._disableCull();

        return output;
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

    public static void delete()
    {
        for (Mesh mesh : MESHES.values())
        {
            mesh.delete();
        }

        MESHES.clear();
    }
}
