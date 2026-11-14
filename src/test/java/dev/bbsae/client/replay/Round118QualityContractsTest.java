package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for the round-118 reports: the stencil pick must use the rendered camera (Alt/body
 * parts), text must leave the point-sampled atlas for imported fonts, blur must be dense enough
 * to stop combing, and the film page must expose the audio browser.
 */
public class Round118QualityContractsTest
{
    private static String source(String... path) throws IOException
    {
        Path file = Path.of("src", "main", "java", "dev", "bbsae");

        for (String part : path)
        {
            file = file.resolve(part);
        }

        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    public void thePickPassUsesTheVisibleCameraView() throws IOException
    {
        String pick = source("client", "replay", "ReplayPickPass.java");

        assertTrue(pick.contains("modelView.peek().getPositionMatrix().set(viewCamera.view)"),
            "the id pass must use the view matrix ReplayModelPass uses; identity left every "
                + "stencil pixel at 0 and made Alt/component selection impossible");
        assertTrue(pick.contains("GL30.GL_VERTEX_ARRAY_BINDING"),
            "the actor VAO must be restored before BBS resumes its UI/world pass");
        assertTrue(pick.contains("lastSignature") && pick.contains("renderCount"),
            "a parked replay editor must not re-render the whole pick scene every frame");
    }

    @Test
    public void importedTextGoesThroughAVectorRaster() throws IOException
    {
        String layer = source("client", "render", "LayerContentRenderer.java");
        String vector = source("client", "render", "VectorTextRaster.java");

        assertTrue(layer.contains("VectorTextRaster.draw"), "the custom-font path must be vector");
        assertTrue(vector.contains("GlyphVector") && vector.contains("getOutline"),
            "glyph outlines, not a point-sampled atlas");
        assertTrue(vector.contains("fallbackFont"), "fallback CJK glyphs need a vector font too");
        assertTrue(layer.contains("hasAnimator(layer)"), "animated glyphs keep their per-glyph path");
    }

    @Test
    public void blurKernelsAreDenseAndDitherTheirBands() throws IOException
    {
        String shaders = source("client", "render", "ShaderSources.java");

        assertTrue(shaders.contains("ceil(Radius * 0.5)"),
            "the blur tap count must follow the radius instead of a fixed sparse kernel");
        assertTrue(shaders.contains("gl_FragCoord.xy, vec2(12.9898, 78.233)"),
            "a sub-LSB dither breaks 8-bit banding");
        assertTrue(shaders.contains("const int TAPS = 32"), "radial blur needs enough samples");
        assertTrue(shaders.contains("for (int i = -16; i <= 16; i++)"),
            "per-channel blur had only 17 taps");
    }

    @Test
    public void theFilmPageHasAnAudioBrowserEntry() throws IOException
    {
        String mixin = Files.readString(Path.of("src", "main", "java", "dev", "bbsae", "mixin",
            "UIFilmPanelAudioMixin.java"), StandardCharsets.UTF_8);
        String config = Files.readString(Path.of("src", "main", "resources", "bbsae.mixins.json"),
            StandardCharsets.UTF_8);
        String audition = Files.readString(Path.of("src", "main", "java", "dev", "bbsae", "client",
            "audio", "FilmAudioAudition.java"), StandardCharsets.UTF_8);

        assertTrue(mixin.contains("Icons.SOUND") && mixin.contains("FilmAudioAudition.open"),
            "the original film page needs a visible audio entry");
        assertTrue(config.contains("UIFilmPanelAudioMixin"), "the mixin must be registered");
        assertTrue(audition.contains("CompAudioPreview.play") && audition.contains("CompAudioPlayer.play"),
            "browsing files and auditioning the composition mix are different operations");
    }
}
