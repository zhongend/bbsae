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
 * Pixel proof of the replay entity matte.
 *
 * <p>The feature is a claim about three buffers: the world colour, the world depth and a
 * depth-only pass of the selected entities. The claim is "keep the colour where the entity depth
 * equals the world depth". These tests build exactly those three buffers with known values and
 * read the result back, so the claim is verified on a real GL context instead of being inferred
 * from the code.</p>
 *
 * <p>Scene (32×32): an entity square at x/y ∈ [8,24) ten blocks away. A "block" covers its left
 * half at seven blocks, i.e. in front. The expected matte is therefore the right half of the
 * square only — that single assertion covers keying, occlusion and the "nothing outside the
 * entity" rule at once.</p>
 */
public class ReplayEntityKeyPixelTest
{
    private static final int SIZE = 32;

    /** NDC depth for a point ~10 blocks away with Minecraft's near/far. */
    private static final float FAR_DEPTH = 0.995F;
    /** NDC depth for a point ~7 blocks away (the occluding block). */
    private static final float NEAR_DEPTH = 0.993F;
    /** Cleared depth = nothing rendered. */
    private static final float EMPTY = 1F;

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

    /** Colour of the world frame: red where the entity is, grey everywhere else. */
    private static int worldColor(int x, int y)
    {
        boolean entity = x >= 8 && x < 24 && y >= 8 && y < 24;

        return entity ? 0xFFFF0000 : 0xFF808080;
    }

    private static float worldDepth(int x, int y)
    {
        boolean occluder = x >= 8 && x < 16 && y >= 8 && y < 24;

        if (occluder)
        {
            return NEAR_DEPTH;
        }

        boolean entity = x >= 8 && x < 24 && y >= 8 && y < 24;

        return entity ? FAR_DEPTH : EMPTY;
    }

    private static float entityDepth(int x, int y)
    {
        boolean entity = x >= 8 && x < 24 && y >= 8 && y < 24;

        return entity ? FAR_DEPTH : EMPTY;
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
    public void keysTheEntityAndKeepsWhatIsInFront() throws Exception
    {
        int[] pixels = render(true, 0F, 0F);

        /* Right half of the square: entity depth == world depth -> kept. */
        assertEquals(255, alpha(pixels[16 * SIZE + 20]), "the visible half of the entity must be opaque");
        assertTrue(red(pixels[16 * SIZE + 20]) > 200, "the kept pixel must be the world colour");

        /* Left half: a block is in front, so the world depth is nearer -> rejected. */
        assertEquals(0, alpha(pixels[16 * SIZE + 10]), "a block in front must hide the entity");

        /* Outside the entity: nothing was rendered into the mask. */
        assertEquals(0, alpha(pixels[2 * SIZE + 2]), "background must stay transparent");
        assertEquals(0, alpha(pixels[30 * SIZE + 30]), "background must stay transparent");
    }

    @Test
    public void withoutOcclusionTheWholeEntityIsKept() throws Exception
    {
        int[] pixels = render(false, 0F, 0F);

        assertEquals(255, alpha(pixels[16 * SIZE + 10]), "occlusion off must keep the covered half");
        assertEquals(255, alpha(pixels[16 * SIZE + 20]), "occlusion off must keep the visible half");
        assertEquals(0, alpha(pixels[2 * SIZE + 2]), "the mask still bounds the entity");
    }

    @Test
    public void erodeShrinksAndFeatherSoftensTheEdge() throws Exception
    {
        int[] hard = render(true, 0F, 0F);
        int[] eroded = render(true, 2F, 0F);
        int[] feathered = render(true, 0F, 2F);

        /* The matte boundary is at x = 16 (occluder edge) and x = 24 (entity edge). Erode samples
         * two texels away, so the pixel just inside the entity edge must lose its coverage. */
        assertEquals(255, alpha(hard[16 * SIZE + 22]), "the hard matte covers the pixel next to the edge");
        assertEquals(0, alpha(eroded[16 * SIZE + 22]), "erode must pull the matte in");
        assertTrue(alpha(eroded[16 * SIZE + 20]) > 200, "erode must not erase the interior");

        /* Feather averages neighbours, so a pixel on the boundary becomes partially covered. */
        int edge = alpha(feathered[16 * SIZE + 23]);

        assertTrue(edge > 0 && edge < 255, "feather must produce a partial edge, got " + edge);
    }

    @Test
    public void samplesTheWorldFrameRightSideUp() throws Exception
    {
        /* A colour ramp by texture row: the shader flips V, so the top of the composition must
         * show the top of the texture (row 31), not the bottom. */
        int[] pixels = renderWithRamp();

        /* Inside the entity, above and below its centre; the ramp is only visible there. */
        int top = red(pixels[22 * SIZE + 20]);
        int bottom = red(pixels[9 * SIZE + 20]);

        assertTrue(top > bottom, "the world frame must not be upside down (top=" + top + ", bottom=" + bottom + ")");
    }

    /* ------------------------------------------------------------------ GL */

    private static int[] render(boolean occlusion, float erode, float feather) throws Exception
    {
        return renderScene(occlusion, erode, feather, ReplayEntityKeyPixelTest::worldColor);
    }

    private static int[] renderWithRamp() throws Exception
    {
        return renderScene(true, 0F, 0F, (x, y) -> 0xFF000000 | ((y * 8) << 16));
    }

    private static int[] renderScene(boolean occlusion, float erode, float feather, ColorSource source) throws Exception
    {
        int program = link(ShaderSources.ENTITY_KEY);

        assertEquals(0, program == 0 ? 1 : 0, "the entity key shader must compile and link");

        int colorTexture = colorTexture(source);
        int worldDepth = depthTexture(ReplayEntityKeyPixelTest::worldDepth);
        int entityDepth = depthTexture(ReplayEntityKeyPixelTest::entityDepth);
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
            bind(program, "WorldDepth", 1, worldDepth);
            bind(program, "EntityDepth", 2, entityDepth);

            /* Layer-local mapping: NDC -> a 32x32 layer centred on its anchor, exactly what
             * inverse(projection * model) produces for a full-frame layer. Y is negated because
             * the composition ortho is top-down (comp y = 0 is NDC y = +1). */
            FloatBuffer inverse = BufferUtils.createFloatBuffer(16);

            new Matrix4f().scale(16F, -16F, 1F).get(inverse);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "InvMvp"), false, inverse);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Rect"), -16F, -16F, 32F, 32F);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "DepthTexel"), 1F / SIZE, 1F / SIZE);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Near"), 0.05F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Far"), 1000F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Tolerance"), 0.08F);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Erode"), erode);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Feather"), feather);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "UseOcclusion"), occlusion ? 1 : 0);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Tint"), 1F, 1F, 1F, 1F);

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
            GL11.glDeleteTextures(worldDepth);
            GL11.glDeleteTextures(entityDepth);
            GL11.glDeleteTextures(target);
            GL20.glDeleteProgram(program);
        }
    }

    private interface ColorSource
    {
        int argb(int x, int y);
    }

    private interface DepthSource
    {
        float depth(int x, int y);
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

    private static int depthTexture(DepthSource source)
    {
        int texture = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, SIZE, SIZE, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);

        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE);

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                pixels.put(source.depth(x, y));
            }
        }

        pixels.flip();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);

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
