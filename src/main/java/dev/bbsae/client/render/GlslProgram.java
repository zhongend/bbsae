package dev.bbsae.client.render;

import dev.bbsae.BbsAeAddon;
import com.mojang.blaze3d.platform.GlStateManager;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny runtime GLSL program used by the composition render graph.
 *
 * <p>Deliberately not a Minecraft {@code ShaderProgram}: composition shaders have no need to be in
 * the core shader registry, and compiling them here means an effect can be added without a
 * resource-pack reload. The full-screen triangle lives in its own VBO/VAO.</p>
 */
public final class GlslProgram
{
    private final int id;
    private final int vao;
    private final int vbo;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public GlslProgram(String name, String vertex, String fragment)
    {
        int vs = compile(GL20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);

        this.id = GL20.glCreateProgram();
        GL20.glAttachShader(this.id, vs);
        GL20.glAttachShader(this.id, fs);
        GL20.glBindAttribLocation(this.id, 0, "Position");
        GL20.glLinkProgram(this.id);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        if (GL20.glGetProgrami(this.id, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            String log = GL20.glGetProgramInfoLog(this.id);

            BbsAeAddon.LOGGER.error("Could not link composition shader {}: {}", name, log);
            GL20.glDeleteProgram(this.id);
            this.vao = 0;
            this.vbo = 0;
            return;
        }

        this.vao = GlStateManager._glGenVertexArrays();
        this.vbo = GL30.glGenBuffers();
        GlStateManager._glBindVertexArray(this.vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);

        java.nio.FloatBuffer vertices = org.lwjgl.BufferUtils.createFloatBuffer(6);

        vertices.put(-1F).put(-1F);
        vertices.put(3F).put(-1F);
        vertices.put(-1F).put(3F);
        vertices.flip();

        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
        GlStateManager._glBindVertexArray(0);
    }

    private static int compile(int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            String log = GL20.glGetShaderInfoLog(shader);

            BbsAeAddon.LOGGER.error("Could not compile composition shader: {}", log);
            GL20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    public boolean isValid()
    {
        return this.vao != 0;
    }

    public void use()
    {
        GlStateManager._glUseProgram(this.id);
    }

    public int uniform(String name)
    {
        return this.uniforms.computeIfAbsent(name, (n) -> GL20.glGetUniformLocation(this.id, n));
    }

    public GlslProgram set(String name, float value)
    {
        GL20.glUniform1f(this.uniform(name), value);
        return this;
    }

    public GlslProgram set(String name, int value)
    {
        GL20.glUniform1i(this.uniform(name), value);
        return this;
    }

    public GlslProgram set(String name, float x, float y)
    {
        GL20.glUniform2f(this.uniform(name), x, y);
        return this;
    }

    public GlslProgram set(String name, float x, float y, float z)
    {
        GL20.glUniform3f(this.uniform(name), x, y, z);
        return this;
    }

    public GlslProgram set(String name, float x, float y, float z, float w)
    {
        GL20.glUniform4f(this.uniform(name), x, y, z, w);
        return this;
    }

    public GlslProgram set(String name, Matrix4f matrix)
    {
        FloatBuffer buffer = org.lwjgl.BufferUtils.createFloatBuffer(16);

        matrix.get(buffer);
        GL20.glUniformMatrix4fv(this.uniform(name), false, buffer);
        return this;
    }

    public void drawFullscreen()
    {
        GlStateManager._glBindVertexArray(this.vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GlStateManager._glBindVertexArray(0);
    }

    public void delete()
    {
        GL20.glDeleteProgram(this.id);

        if (this.vao != 0)
        {
            GlStateManager._glDeleteVertexArrays(this.vao);
        }

        if (this.vbo != 0)
        {
            GlStateManager._glDeleteBuffers(this.vbo);
        }
    }
}
