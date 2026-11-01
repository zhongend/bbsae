package dev.bbsae.client.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel proof of the coverage-keyed replay entity matte.
 *
 * <p>The layer is a claim about two buffers: the world colour and an actor-only RGBA coverage
 * texture. These tests build exactly those two buffers with known values and read the result back,
 * so the claim is verified on a real GL context instead of being inferred from the code.</p>
 *
 * <p>Scene (32×32): an entity square at x/y ∈ [8,24). The world colour is red inside the square and
 * grey outside; the coverage texture is opaque inside and transparent outside. The expected matte is
 * therefore the square only, in the world colour. A second test proves that an additive renderer
 * (RGB with alpha 0) is still treated as coverage, because some form renderers emit exactly that.</p>
 */
public class ReplayEntityKeyPixelTest
{
    private static final int SIZE = 32;

    private static long window;

    @BeforeAll
    public static void createContext()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "bbsae entity key", 0L, 0L);

        Assumptions.assumeTrue(window != 0L, "could not create hidden GL context");

        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll
    public static void destroyContext()
    {
        if (window != 0L)
        {
            GLFW.glfwDestroyWindow(window);
            window = 0L;
        }

        if (GLFW.glfwInit())
        {
            GLFW.glfwTerminate();
        }
    }

    /* ------------------------------------------------------------------ scene */

    private static boolean entity(int x, int y)
    {
        return x >= 8 && x < 24 && y >= 8 && y < 24;
    }

    /** World colour: red where the entity is, grey everywhere else. */
    private static int worldColor(int x, int y)
    {
        return entity(x, y) ? 0xFFFF0000 : 0xFF808080;
    }

    /** Normal coverage: alpha 1 inside the entity, 0 outside. */
    private static int coverage(int x, int y)
    {
        return entity(x, y) ? 0xFFFFFFFF : 0x00000000;
    }

    /** Additive renderer: strong RGB with no alpha. */
    private static int additiveCoverage(int x, int y)
    {
        return entity(x, y) ? 0x00FFFFFF : 0x00000000;
    }

    private static int alpha(int color)
    {
        return (color >>> 24) & 0xFF;
    }

    private static int red(int color)
    {
        return (color >> 16) & 0xFF;
    }

    /* ------------------------------------------------------------------ tests */

    @Test
    public void keysTheEntityAndNothingElse() throws Exception
    {
        int[] pixels = render(false, 0F, 0F, ReplayEntityKeyPixelTest::coverage);

        assertEquals(255, alpha(pixels[16 * SIZE + 20]), "the entity must be opaque");
        assertTrue(red(pixels[16 * SIZE + 20]) > 200, "the kept pixel must be the world colour");

        assertEquals(0, alpha(pixels[2 * SIZE + 2]), "background must stay transparent");
        assertEquals(0, alpha(pixels[30 * SIZE + 30]), "background must stay transparent");
    }

    @Test
    public void additiveColourCountsAsCoverage() throws Exception
    {
        int[] pixels = render(false, 0F, 0F, ReplayEntityKeyPixelTest::additiveCoverage);

        assertEquals(255, alpha(pixels[16 * SIZE + 20]),
            "a form that writes RGB with alpha 0 must still be keyed");
        assertEquals(0, alpha(pixels[2 * SIZE + 2]), "the additive case must not leak the background");
    }

    @Test
    public void erodeShrinksAndFeatherSoftensTheEdge() throws Exception
    {
        int[] hard = render(false, 0F, 0F, ReplayEntityKeyPixelTest::coverage);
        int[] eroded = render(false, 2F, 0F, ReplayEntityKeyPixelTest::coverage);
        int[] feathered = render(false, 0F, 2F, ReplayEntityKeyPixelTest::coverage);

        assertEquals(255, alpha(hard[16 * SIZE + 22]), "the hard matte covers the pixel next to the edge");
        assertEquals(0, alpha(eroded[16 * SIZE + 22]), "erode must pull the matte in");
        assertTrue(alpha(eroded[16 * SIZE + 20]) > 200, "erode must not erase the interior");

        int edge = alpha(feathered[16 * SIZE + 23]);

        assertTrue(edge > 0 && edge < 255, "feather must produce a partial edge, got " + edge);
    }

    @Test
    public void samplesTheWorldFrameRightSideUp() throws Exception
    {
        /* A colour ramp by texture row: the shader flips V, so the top of the composition must
         * show the top of the texture (row 31), not the bottom. */
        int[] pixels = renderScene(false, 0F, 0F, (x, y) -> 0xFF000000 | ((y * 8) << 16),
            ReplayEntityKeyPixelTest::coverage);

        int top = red(pixels[22 * SIZE + 20]);
        int bottom = red(pixels[9 * SIZE + 20]);

        assertTrue(top > bottom, "the world frame must not be upside down (top=" + top + ", bottom=" + bottom + ")");
    }

    /* ------------------------------------------------------------------ GL */

    private static int[] render(boolean occlusion, float erode, float feather, ColorSource maskSource) throws Exception
    {
        return renderScene(occlusion, erode, feather, ReplayEntityKeyPixelTest::worldColor, maskSource);
    }

    private static int[] renderScene(boolean occlusion, float erode, float feather, ColorSource worldSource,
                                     ColorSource maskSource) throws Exception
    {
        int program = link(ShaderSources.ENTITY_KEY);

        assertEquals(0, program == 0 ? 1 : 0, "the entity key shader must compile and link");

        int colorTexture = colorTexture(worldSource);
        int maskTexture = colorTexture(maskSource);
        int target = colorTexture((x, y) -> 0);
        int fbo = GL30.glGenFramebuffers();
        int vao = GL30.glGenVertexArrays();
        int vbo = GL30.glGenBuffers();

        try
        {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);

            GL30.glBindVertexArray(vao);
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            FloatBuffer vertices = BufferUtils.createFloatBuffer(6);

            vertices.put(-1F).put(-1F).put(3F).put(-1F).put(-1F).put(3F);
            vertices.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);

            GL20.glUseProgram(program);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            bind(program, "WorldColor", 0, colorTexture);
            bind(program, "EntityMask", 1, maskTexture);

            /* Layer-local mapping: NDC -> a 32x32 layer centred on its anchor, exactly what
             * inverse(projection * model) produces for a full-frame layer. Y is negated because
             * the composition ortho is top-down (comp y = 0 is NDC y = +1). */
            FloatBuffer inverse = BufferUtils.createFloatBuffer(16);

            new Matrix4f().scale(16F, -16F, 1F).get(inverse);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "InvMvp"), false, inverse);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Rect"), -16F, -16F, 32F, 32F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "DepthTexel"), 1F / SIZE, 1F / SIZE);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Erode"), erode);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Feather"), feather);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Tint"), 1F, 1F, 1F, 1F);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "DebugView"), 0);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            ByteBuffer output = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, output);

            int[] result = new int[SIZE * SIZE];

            for (int i = 0; i < result.length; i++)
            {
                int r = output.get(i * 4) & 0xFF;
                int g = output.get(i * 4 + 1) & 0xFF;
                int b = output.get(i * 4 + 2) & 0xFF;
                int a = output.get(i * 4 + 3) & 0xFF;

                result[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            return result;
        }
        finally
        {
            GL15.glDeleteBuffers(vbo);
            GL30.glDeleteVertexArrays(vao);
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(colorTexture);
            GL11.glDeleteTextures(maskTexture);
            GL11.glDeleteTextures(target);
            GL20.glDeleteProgram(program);
        }
    }

    private interface ColorSource
    {
        int argb(int x, int y);
    }

    private static void bind(int program, String sampler, int unit, int texture)
    {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, sampler), unit);
    }

    private static int colorTexture(ColorSource source)
    {
        int texture = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                int color = source.argb(x, y);

                pixels.put((byte) ((color >> 16) & 0xFF));
                pixels.put((byte) ((color >> 8) & 0xFF));
                pixels.put((byte) (color & 0xFF));
                pixels.put((byte) ((color >>> 24) & 0xFF));
            }
        }

        pixels.flip();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        return texture;
    }

    private static int link(String fragmentSource)
    {
        int vertex = compile(GL20.GL_VERTEX_SHADER, ShaderSources.VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);

        int program = GL20.glCreateProgram();

        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("Entity key link failed: " + GL20.glGetProgramInfoLog(program));
            return 0;
        }

        return program;
    }

    private static int compile(int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("Entity key compile failed: " + GL20.glGetShaderInfoLog(shader));
            return 0;
        }

        return shader;
    }
}
