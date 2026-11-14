package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for the four replay reports that are hard to pin with a headless behavioural test:
 * the missing gizmo context, the per-frame recreation of stand-in actors, the body-part pick and
 * the internal page splitters.
 */
public class ReplayInteractionContractsTest
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
    public void theReplayGizmoTransformHasAContext() throws IOException
    {
        String session = source("client", "replay", "ReplayEditSession.java");
        String transform = source("client", "replay", "ReplaySessionTransform.java");

        assertTrue(session.contains("new ReplaySessionTransform"), "the session must own a context-aware transform");
        assertTrue(session.contains("setContextSupplier"), "the page must hand its context to the session");
        assertTrue(transform.contains("public UIContext getContext()"),
            "UIPropTransform gestures only start when getContext() is non-null");
        assertTrue(transform.contains("public boolean isVisible()"),
            "an invisible host is pumped by GizmoInteraction#pumpIfHidden");
    }

    @Test
    public void standInActorsArePersistentAndAdvancedPerTick() throws IOException
    {
        String pass = source("client", "render", "ReplayActorPass.java");

        assertTrue(pass.contains("advanceStub("), "a private actor must be advanced, not recreated");
        assertTrue(pass.contains("entity.update()"), "age/limb/form state advance through entity.update()");
        assertTrue(pass.contains("filmEntities("), "the overlay path must use the playing film's controller");
        assertTrue(pass.contains("context.film != null ? context.film : BbsPreviewBridge.film()"),
            "a composition rendered without the compositor panel still knows its film");
        assertTrue(pass.contains("renderEntities.get(replay.getId())"),
            "the render pass must look stand-ins up in the map it posed");
    }

    @Test
    public void theSelectedBodyPartOwnsThePickAndTheGizmo() throws IOException
    {
        String pick = source("client", "replay", "ReplayPickPass.java");
        String panel = source("client", "replay", "UIReplayModePanel.java");
        String viewport = source("client", "replay", "UIReplayViewport.java");

        assertTrue(pick.contains("setIncrement(increment)") && pick.contains("replays.size() + ReplayPickIds.offsetFor"),
            "the selected actor needs per-part stencil ids, drawn after the other actors");
        assertTrue(pick.contains("pickedForm()"), "the pick pass must expose the form part");
        assertTrue(panel.contains("selectPartFromViewport"), "the page must handle a part click");
        assertTrue(panel.contains("UIReplaysEditorUtils.pickForm"), "the original pick logic must be reused");
        assertTrue(viewport.contains("setBoneTarget"), "a picked bone must become the gizmo target");
        assertTrue(viewport.contains("boneGizmoDrag"), "a bone drag must use the bone's matrices");
    }

    @Test
    public void nestedCompositionsSampleReplayAnimationOnTheirLocalClock()
    {
        dev.bbsae.comp.CompDocument doc = new dev.bbsae.comp.CompDocument();
        dev.bbsae.comp.CompProject project = new dev.bbsae.comp.CompProject();
        dev.bbsae.client.render.BindingResolver.setFilm(null);
        dev.bbsae.client.render.BindingResolver.setFrameTick(120F);

        try
        {
            dev.bbsae.client.render.CompRenderContext top =
                new dev.bbsae.client.render.CompRenderContext(project, doc, 2F, null, null, null);

            org.junit.jupiter.api.Assertions.assertEquals(120F,
                dev.bbsae.client.render.ReplayActorPass.replayTick(top), 0.001F,
                "the top-level composition uses the overlay's absolute film tick");

            dev.bbsae.client.render.CompRenderContext.pushNested();

            try
            {
                dev.bbsae.client.render.CompRenderContext nested =
                    new dev.bbsae.client.render.CompRenderContext(project, doc, 2F, null, null, null);

                org.junit.jupiter.api.Assertions.assertEquals(40F,
                    dev.bbsae.client.render.ReplayActorPass.replayTick(nested), 0.001F,
                    "a precomp has already applied its own offset/time scale");
            }
            finally
            {
                dev.bbsae.client.render.CompRenderContext.popNested();
            }
        }
        finally
        {
            dev.bbsae.client.render.BindingResolver.setFrameTick(-1F);
        }
    }

    @Test
    public void theReplayPageHasItsOwnResizableColumnsAndTimeline() throws IOException
    {
        String panel = source("client", "replay", "UIReplayModePanel.java");

        assertTrue(panel.contains("buildReplaySplitters"), "the page needs internal splitters");
        assertTrue(panel.contains("layoutReplayColumns"), "column widths must be applied from state");
        assertTrue(panel.contains("replayListWidth"), "the list width must be adjustable");
        assertTrue(panel.contains("replayPropsWidth"), "the properties width must be adjustable");
        assertTrue(panel.contains("replayTimelineHeight"), "the keyframe strip must be adjustable");
        assertTrue(panel.contains("timelineSplitter"), "a horizontal splitter is required for the timeline");
    }
}
