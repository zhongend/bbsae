package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Textual contracts for three user-reported regressions that are otherwise hard to assert without a
 * Minecraft client.
 *
 * <p>The panel is not constructible headless, but the exact call shapes that caused the bugs are
 * recognisable in the source. These assertions fail if one of them is reintroduced:</p>
 *
 * <ul>
 *   <li>effect stopwatch forced the curve page ({@code showCurveFor(path)});</li>
 *   <li>opening the compositor added a film clip (extra {@code ensureForFilm} call sites);</li>
 *   <li>the film length was copied into the composition duration ({@code Math.max(doc.duration, …)}).</li>
 * </ul>
 */
public class RegressionContractsTest
{
    private static String panelSource() throws IOException
    {
        return Files.readString(Path.of("src", "main", "java", "dev", "bbsae", "client", "ui",
            "UICompositorPanel.java"), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle)
    {
        int count = 0;
        int index = 0;

        while ((index = text.indexOf(needle, index)) >= 0)
        {
            count++;
            index += needle.length();
        }

        return count;
    }

    @Test
    public void anEffectKeyframeNeverForcesTheCurvePage() throws IOException
    {
        String source = panelSource();

        assertFalse(source.contains("showCurveFor(path)"),
            "addScalarKey used to call showCurveFor(path); effect keys must open the keyframe drawer");
        assertTrue(source.contains("this.openKeyframes();"),
            "the drawer entry point must still be used by the keyframe actions");
    }

    @Test
    public void onlyTheExplicitAddCreatesAFilmClip() throws IOException
    {
        String source = panelSource();

        assertEquals(1, count(source, "CompositionClip.ensureForFilm("),
            "the only creator in the panel is the explicit 'add to film timeline' action; "
                + "view/duration paths must use updateForFilm");
    }

    @Test
    public void theFilmLengthIsNeverWrittenIntoTheCompositionDuration() throws IOException
    {
        String source = panelSource();

        assertFalse(source.contains("Math.max(this.doc.duration, duration)"),
            "bind/selectReplay must not grow the composition to the film length");
        assertFalse(source.contains("Math.max(this.doc.workAreaOut, duration)"),
            "the work area follows the composition, not the film length");
    }
}
