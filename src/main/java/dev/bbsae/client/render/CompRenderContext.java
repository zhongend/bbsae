package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompProject;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;

/** Per-render state: comp time, size and the BBS camera/projection needed by bindings. */
public final class CompRenderContext
{
    public final CompProject project;
    public final CompDocument comp;
    public final float time;
    public final float frame;
    public final int width;
    public final int height;
    public final Matrix4f projection;
    public final Camera camera;
    public final SceneDepth depth;
    public final Set<String> stack = new HashSet<>();

    public CompRenderContext(CompProject project, CompDocument comp, float time, Matrix4f projection, Camera camera, SceneDepth depth)
    {
        this.project = project;
        this.comp = comp;
        this.time = Math.max(0F, time);
        this.frame = this.time * Math.max(1F, comp.fps);
        this.width = Math.max(1, comp.width);
        this.height = Math.max(1, comp.height);
        this.projection = projection;
        this.camera = camera;
        this.depth = depth;
    }

    public boolean hasDepth()
    {
        return this.depth != null && this.depth.isAvailable();
    }
}
