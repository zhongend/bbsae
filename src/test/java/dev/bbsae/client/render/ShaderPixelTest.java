package dev.bbsae.client.render;

import dev.bbsae.comp.CompDocument;
import dev.bbsae.comp.CompLayer;
import dev.bbsae.comp.CompProject;
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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel-level checks for the PIXEL shader family.
 *
 * <p>Compiling a shader is not enough: these tests prove the actual behaviour on a real GL 3.2
 * context — scanlines darken rows, mirror really folds, halftone really produces ink dots,
 * glitch/VHS are identity at amount 0 and animate with time, and mirror uses layer-local space
 * instead of the composition centre.</p>
 */
public class ShaderPixelTest
{
    private static final int SIZE = 32;

    private static long window;

    /**
     * One hidden window for the whole class. Repeatedly calling glfwInit/glfwTerminate per test
     * eventually deadlocks inside glfwCreateWindow on Windows; a shared context is both faster
     * and deterministic.
     */
    @BeforeAll
    public static void createContext()
    {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW/display unavailable");

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(64, 64, "bbsae pixel check", 0L, 0L);

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

    private interface Source
    {
        int[] color(int x, int y);
    }

    private static int[] argb(int r, int g, int b)
    {
        return new int[] {r, g, b, 255};
    }

    private static int[] randomSource(int x, int y)
    {
        int hash = (x * 73856093) ^ (y * 19349663);

        hash ^= hash >>> 13;
        hash *= 0x5bd1e995;
        hash ^= hash >>> 15;

        return argb(hash & 0xFF, (hash >>> 8) & 0xFF, (hash >>> 16) & 0xFF);
    }

    private static int[] stripes(int x, int y)
    {
        return switch (x / 4)
        {
            case 0 -> argb(255, 0, 0);
            case 1 -> argb(0, 255, 0);
            case 2 -> argb(0, 0, 255);
            case 3 -> argb(255, 255, 255);
            case 4 -> argb(255, 200, 0);
            default -> argb(0, 200, 255);
        };
    }

    private static int pixel(int[] pixels, int x, int y)
    {
        return pixels[y * SIZE + x];
    }

    private static int[] sourcePixels(Source source)
    {
        int[] pixels = new int[SIZE * SIZE];

        for (int y = 0; y < SIZE; y++)
        {
            for (int x = 0; x < SIZE; x++)
            {
                int[] color = source.color(x, y);

                pixels[y * SIZE + x] = (color[3] << 24) | (color[0] << 16) | (color[1] << 8) | color[2];
            }
        }

        return pixels;
    }

    private static int red(int color)
    {
        return (color >> 16) & 0xFF;
    }

    private static int green(int color)
    {
        return (color >> 8) & 0xFF;
    }

    private static int blue(int color)
    {
        return color & 0xFF;
    }

    private static int alpha(int color)
    {
        return (color >>> 24) & 0xFF;
    }

    private static CompRenderContext context(int width, int height)
    {
        CompProject project = new CompProject();
        CompDocument comp = new CompDocument();

        comp.width = width;
        comp.height = height;
        project.comps.add(comp);

        return new CompRenderContext(project, comp, 0F, null, null, null);
    }

    private static EffectSpace identitySpace()
    {
        return EffectSpace.identity(context(SIZE, SIZE));
    }

    /** A 16x16 layer in the centre of the 32x32 composition. */
    private static EffectSpace layerSpace()
    {
        CompLayer layer = new CompLayer("solid", "Layer", "solid");

        layer.width = 16F;
        layer.height = 16F;
        layer.anchorX = 0.5F;
        layer.anchorY = 0.5F;
        layer.x = 16F;
        layer.y = 16F;

        return EffectSpace.ofBounds(layer, new Matrix4f().translate(16F, 16F, 0F),
            context(SIZE, SIZE), -8F, -8F, 16F, 16F);
    }

    private static final class Setup
    {
        final int mode;
        float amount = 1F;
        float invert;
        float phase;
        float p1;
        float p2;
        float p3;
        float p4;
        int dotColor = 0xFF000000;
        int paperColor = 0xFFFFFFFF;
        EffectSpace space;

        Setup(int mode)
        {
            this.mode = mode;
        }

        Setup amount(float value) { this.amount = value; return this; }
        Setup invert(float value) { this.invert = value; return this; }
        Setup phase(float value) { this.phase = value; return this; }
        Setup p1(float value) { this.p1 = value; return this; }
        Setup p2(float value) { this.p2 = value; return this; }
        Setup p3(float value) { this.p3 = value; return this; }
        Setup p4(float value) { this.p4 = value; return this; }
        Setup dotColor(int value) { this.dotColor = value; return this; }
        Setup paperColor(int value) { this.paperColor = value; return this; }
        Setup space(EffectSpace value) { this.space = value; return this; }
    }

    @Test
    public void scanlinesDarkenAlternatingRows() throws Exception
    {
        int[] pixels = render((x, y) -> argb(200, 200, 200), new Setup(2).amount(0.8F).p1(4F));
        int bright = 0;
        int dark = 0;

        for (int y = 0; y < SIZE; y++)
        {
            int value = red(pixel(pixels, 8, y));

            if (value > 170) bright++;
            if (value < 140) dark++;
        }

        assertTrue(bright > 4, "scanlines must leave bright rows, got " + bright);
        assertTrue(dark > 4, "scanlines must darken rows, got " + dark);
    }

    @Test
    public void mirrorOverlaysAFoldedCopy() throws Exception
    {
        int[] pixels = render((x, y) -> x < SIZE / 2 ? argb(255, 0, 0) : argb(0, 0, 255),
            new Setup(6).amount(1F).p1(0F));

        int left = pixel(pixels, 1, SIZE / 2);
        int right = pixel(pixels, SIZE - 2, SIZE / 2);

        /* The fold mirrors the right (blue) half over the left half. */
        assertTrue(blue(right) > 200, "right edge should be blue");
        assertTrue(blue(left) > 200, "left edge should sample the folded right half");
        assertEquals(0, red(left), "left edge must not stay red");
    }

    @Test
    public void mirrorOpacityZeroIsIdentity() throws Exception
    {
        int[] pixels = render((x, y) -> x < SIZE / 2 ? argb(255, 0, 0) : argb(0, 0, 255),
            new Setup(6).amount(0F).p1(0F));

        assertTrue(red(pixel(pixels, 1, SIZE / 2)) > 200, "opacity 0 must preserve the left half");
        assertTrue(blue(pixel(pixels, SIZE - 2, SIZE / 2)) > 200, "opacity 0 must preserve the right half");
    }

    @Test
    public void mirrorUsesLayerSpaceInsteadOfCompositionSpace() throws Exception
    {
        int[] pixels = render((x, y) -> x < SIZE / 2 ? argb(255, 0, 0) : argb(0, 0, 255),
            new Setup(6).amount(1F).p1(0F).space(layerSpace()));

        /* At x=10 the layer local u is 0.156, so the fold samples the layer's right half (blue).
         * At x=2 the pixel is outside the layer: it must be transparent, not an edge smear. */
        assertTrue(blue(pixel(pixels, 10, SIZE / 2)) > 200, "layer-local fold must be blue");
        assertEquals(0, alpha(pixel(pixels, 2, SIZE / 2)), "outside the layer must stay transparent");
        assertEquals(0, alpha(pixel(pixels, SIZE - 3, SIZE / 2)), "outside the layer must stay transparent");
    }

    @Test
    public void halftoneProducesInkDotsWithSeparatePaper() throws Exception
    {
        int[] pixels = render((x, y) -> argb(128, 128, 128),
            new Setup(4).amount(1F).p1(4F).p2(0F).p3(0F)
                .dotColor(0xFFFF0000).paperColor(0xFFFFFFFF));

        int center = pixel(pixels, 4, 4);
        int corner = pixel(pixels, 1, 1);

        assertTrue(red(center) > 180 && green(center) < 80 && blue(center) < 80,
            "the dot centre must use the ink colour, got " + Integer.toHexString(center));
        assertTrue(red(corner) > 200 && green(corner) > 200 && blue(corner) > 200,
            "the cell corner must use the paper colour, got " + Integer.toHexString(corner));
    }

    @Test
    public void halftoneStrengthZeroIsIdentity() throws Exception
    {
        Source source = (x, y) -> argb(128, 64, 32);

        assertArrayEquals(sourcePixels(source), render(source,
            new Setup(4).amount(0F).p1(4F).p2(0F).p3(0F)),
            "strength 0 must be a no-op");
    }

    @Test
    public void embossFlatImageIsNeutralGrey() throws Exception
    {
        int[] pixels = render((x, y) -> argb(160, 160, 160),
            new Setup(5).amount(1F).p3(0F));

        for (int y = 2; y < SIZE - 2; y++)
        {
            for (int x = 2; x < SIZE - 2; x++)
            {
                int value = red(pixel(pixels, x, y));

                assertTrue(value >= 126 && value <= 129,
                    "flat emboss should be neutral grey inside the layer, got " + value
                        + " at " + x + "," + y);
            }
        }
    }

    @Test
    public void embossAmountZeroIsIdentity() throws Exception
    {
        Source source = (x, y) -> argb(128, 64, 32);

        assertArrayEquals(sourcePixels(source),
            render(source, new Setup(5).amount(0F).p3(0F)),
            "emboss amount 0 must be a no-op");
    }

    @Test
    public void glitchIsIdentityAtZeroAmountAndAnimatesWithPhase() throws Exception
    {
        int[] identity = render(ShaderPixelTest::randomSource, new Setup(3).amount(0F).p1(8F).p2(3F).phase(100F));

        assertArrayEquals(sourcePixels(ShaderPixelTest::randomSource), identity,
            "glitch amount 0 must be a no-op");

        int[] first = render(ShaderPixelTest::randomSource, new Setup(3).amount(40F).p1(8F).p2(3F).phase(0F));
        int[] second = render(ShaderPixelTest::randomSource, new Setup(3).amount(40F).p1(8F).p2(3F).phase(20F));

        assertNotEquals(Arrays.hashCode(first), Arrays.hashCode(second),
            "glitch must change when time advances (speed is not a dead parameter)");

        int changed = 0;

        for (int i = 0; i < first.length; i++)
        {
            if (first[i] != identity[i])
            {
                changed++;
            }
        }

        assertTrue(changed > 20, "glitch should move some bands, changed=" + changed);
    }

    @Test
    public void vhsIsIdentityAtZeroAmountAndUsesNoise() throws Exception
    {
        int[] identity = render(ShaderPixelTest::randomSource, new Setup(8).amount(0F).p3(1F).phase(12F));

        assertArrayEquals(sourcePixels(ShaderPixelTest::randomSource), identity,
            "vhs amount 0 must be a no-op");

        int[] clean = render(ShaderPixelTest::randomSource, new Setup(8).amount(1F).p3(0F).phase(3F));
        int[] noisy = render(ShaderPixelTest::randomSource, new Setup(8).amount(1F).p3(1F).phase(3F));

        assertNotEquals(Arrays.hashCode(clean), Arrays.hashCode(noisy),
            "vhs noise must change the image (noise is not a dead parameter)");
    }

    @Test
    public void kaleidoscopeFoldsIntoFourSymmetricSectors() throws Exception
    {
        int[] pixels = render((x, y) ->
        {
            if (x < SIZE / 2)
            {
                return y < SIZE / 2 ? argb(255, 0, 0) : argb(0, 0, 255);
            }

            return y < SIZE / 2 ? argb(0, 255, 0) : argb(255, 255, 255);
        }, new Setup(7).p1(4F).p3(0F));

        for (int y = 2; y < SIZE - 2; y += 3)
        {
            for (int x = 2; x < SIZE / 2; x += 3)
            {
                int a = pixel(pixels, x, y);
                int b = pixel(pixels, SIZE - 1 - x, y);
                int c = pixel(pixels, x, SIZE - 1 - y);

                assertChannelNear(a, b, 2, "kaleidoscope horizontal symmetry at " + x + "," + y);
                assertChannelNear(a, c, 2, "kaleidoscope vertical symmetry at " + x + "," + y);
            }
        }
    }

    @Test
    public void writeEffectContactSheet() throws Exception
    {
        Source source = (x, y) ->
        {
            float u = x / (float) (SIZE - 1);
            float v = y / (float) (SIZE - 1);
            int r = Math.round(40F + 200F * u);
            int g = Math.round(30F + 180F * v);
            int b = Math.round(50F + 190F * (1F - u));

            if (((x / 4) + (y / 4)) % 2 == 1)
            {
                r /= 2;
                g /= 2;
                b /= 2;
            }

            if (Math.abs(x - y) < 2 || Math.abs(x + y - (SIZE - 1)) < 2)
            {
                r = 245;
                g = 245;
                b = 245;
            }

            return new int[] {r, g, b, 255};
        };

        String[] names = {"Source", "扫描线", "故障 Glitch", "VHS", "半色调", "浮雕", "镜像", "万花筒"};
        int[][] tiles = new int[names.length][];

        tiles[0] = sourcePixels(source);
        tiles[1] = render(source, new Setup(2).amount(0.75F).p1(3F).phase(1F));
        tiles[2] = render(source, new Setup(3).amount(8F).p1(8F).p2(1F).phase(5F));
        tiles[3] = render(source, new Setup(8).amount(0.8F).p3(0.5F).phase(3F));
        tiles[4] = render(source, new Setup(4).amount(0.85F).p1(8F).p2(0.5F).p3(45F));
        tiles[5] = render(source, new Setup(5).amount(1F).p3(45F));
        tiles[6] = render(source, new Setup(6).amount(0.85F).p1(0F));
        tiles[7] = render(source, new Setup(7).p1(6F).p3(0F));

        int scale = 5;
        int tile = SIZE * scale;
        int label = 22;
        int columns = 4;
        int rows = 2;
        java.awt.image.BufferedImage sheet =
            new java.awt.image.BufferedImage(columns * tile, rows * (tile + label),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = sheet.createGraphics();

        graphics.setColor(new java.awt.Color(18, 20, 26));
        graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));

        for (int i = 0; i < names.length; i++)
        {
            int originX = (i % columns) * tile;
            int originY = (i / columns) * (tile + label);

            for (int y = 0; y < SIZE; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    int color = tiles[i][y * SIZE + x];

                    for (int sy = 0; sy < scale; sy++)
                    {
                        for (int sx = 0; sx < scale; sx++)
                        {
                            sheet.setRGB(originX + x * scale + sx, originY + y * scale + sy, color);
                        }
                    }
                }
            }

            graphics.setColor(new java.awt.Color(230, 234, 242));
            graphics.drawString(names[i], originX + 8, originY + tile + 16);
        }

        graphics.dispose();

        java.io.File output = new java.io.File("docs/verify/round8-pixel-effects.png");

        output.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(sheet, "png", output);

        assertTrue(output.isFile() && output.length() > 0, "contact sheet was not written");
    }

    private static void assertChannelNear(int a, int b, int tolerance, String message)
    {
        assertTrue(Math.abs(red(a) - red(b)) <= tolerance, message + " (R)");
        assertTrue(Math.abs(green(a) - green(b)) <= tolerance, message + " (G)");
        assertTrue(Math.abs(blue(a) - blue(b)) <= tolerance, message + " (B)");
        assertTrue(Math.abs(alpha(a) - alpha(b)) <= tolerance, message + " (A)");
    }

    private int[] render(Source source, Setup setup) throws Exception
    {
        int program = 0;
        int vao = 0;
        int vbo = 0;
        int texture = 0;
        int target = 0;
        int fbo = 0;

        try
        {
            GLFW.glfwMakeContextCurrent(window);

            program = link(ShaderSources.PIXEL);
            assertTrue(program != 0, "pixel program failed to link");
            assertTrue(GL20.glGetUniformLocation(program, "DotColor") >= 0, "DotColor uniform is missing");
            assertTrue(GL20.glGetUniformLocation(program, "PaperColor") >= 0, "PaperColor uniform is missing");

            texture = texture();
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

            for (int y = 0; y < SIZE; y++)
            {
                for (int x = 0; x < SIZE; x++)
                {
                    int[] color = source.color(x, y);

                    pixels.put((byte) color[0]).put((byte) color[1]).put((byte) color[2]).put((byte) color[3]);
                }
            }

            pixels.flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

            target = texture();
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);

            vao = GL30.glGenVertexArrays();
            vbo = GL30.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            java.nio.FloatBuffer vertices = BufferUtils.createFloatBuffer(6);

            vertices.put(-1F).put(-1F).put(3F).put(-1F).put(-1F).put(3F);
            vertices.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);

            EffectSpace space = setup.space == null ? identitySpace() : setup.space;

            GL20.glUseProgram(program);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glViewport(0, 0, SIZE, SIZE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Source"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "Size"), SIZE, SIZE);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Mode"), setup.mode);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Amount"), setup.amount);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Invert"), setup.invert);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "Phase"), setup.phase);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "Param"), setup.p1, setup.p2, setup.p3, setup.p4);
            setColor(program, "DotColor", setup.dotColor);
            setColor(program, "PaperColor", setup.paperColor);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerU"), space.ux, space.uy, space.uz);
            GL20.glUniform3f(GL20.glGetUniformLocation(program, "LayerV"), space.vx, space.vy, space.vz);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerSize"), space.width, space.height);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerX"), space.m00, space.m01);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerY"), space.m10, space.m11);
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "LayerOrigin"), space.originCompX, space.originCompY);
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
            if (vbo != 0) GL15.glDeleteBuffers(vbo);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (target != 0) GL11.glDeleteTextures(target);
            if (program != 0) GL20.glDeleteProgram(program);
        }
    }

    private static void setColor(int program, String name, int argb)
    {
        GL20.glUniform4f(GL20.glGetUniformLocation(program, name),
            ((argb >> 16) & 0xFF) / 255F,
            ((argb >> 8) & 0xFF) / 255F,
            (argb & 0xFF) / 255F,
            ((argb >>> 24) & 0xFF) / 255F);
    }

    private static int texture()
    {
        int texture = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

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

        return GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE ? 0 : program;
    }

    private static int compile(int type, String source)
    {
        int shader = GL20.glCreateShader(type);

        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("Compile failed: " + GL20.glGetShaderInfoLog(shader));
        }

        return GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE ? 0 : shader;
    }
}
