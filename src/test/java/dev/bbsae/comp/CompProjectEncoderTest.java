package dev.bbsae.comp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AE rendering is frame-driven; BBS preview/entity layers are tick-driven and cannot be reproduced. */
public class CompProjectEncoderTest
{
    private static CompLayer layer(String type)
    {
        return new CompLayer("id", "name", type);
    }

    @Test
    public void pureAnimationProjectsCanUseTheAeRenderer()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);

        comp.layers.add(layer("shape"));
        comp.layers.add(layer("text"));
        comp.layers.add(layer("solid"));

        assertFalse(project.needsBbsWorldRender());
    }

    @Test
    public void aBbsPreviewLayerRequiresTheBbsRenderer()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);

        comp.layers.add(layer("shape"));
        comp.layers.add(layer("bbs"));

        assertTrue(project.needsBbsWorldRender());
    }

    @Test
    public void aReplayEntityKeyLayerRequiresTheBbsRenderer()
    {
        CompProject project = new CompProject();
        CompDocument comp = project.addComp("Main", 1920, 1080, 60, 10F);

        comp.layers.add(layer("replay"));

        assertTrue(project.needsBbsWorldRender());
    }

    @Test
    public void nestedCompsAreInspectedToo()
    {
        CompProject project = new CompProject();
        CompDocument main = project.addComp("Main", 1920, 1080, 60, 10F);
        CompDocument nested = project.addComp("Nested", 1920, 1080, 60, 10F);

        nested.layers.add(layer("bbs"));
        main.layers.add(layer("shape"));

        assertTrue(project.needsBbsWorldRender());
    }
}
