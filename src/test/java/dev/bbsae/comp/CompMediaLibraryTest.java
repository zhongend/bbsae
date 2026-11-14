package dev.bbsae.comp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project panel's index: sequences become one item, bins only organise, missing external files
 * stay visible, and a rescan never duplicates an entry.
 */
public class CompMediaLibraryTest
{
    @AfterEach
    public void cleanup()
    {
        CompStore.clearRootForTests();
    }

    private CompProject project(Path root) throws Exception
    {
        CompStore.useRootForTests(root);

        CompProject project = CompStore.newProject("lib", "Library");
        Path assets = CompStore.assetRoot(project.id);

        Files.createDirectories(assets.resolve("images"));
        Files.createDirectories(assets.resolve("audio"));
        Files.createDirectories(assets.resolve("frames/clip"));
        Files.write(assets.resolve("images/a.png"), new byte[] {1, 2, 3});
        Files.write(assets.resolve("audio/song.wav"), new byte[] {1, 2, 3});

        for (int i = 0; i < 12; i++)
        {
            Files.write(assets.resolve("frames/clip/frame_" + String.format("%04d", i) + ".png"), new byte[] {1});
        }

        Path external = root.resolve("outside.mp4");

        Files.write(external, new byte[] {1});
        project.settings.externalAssets.add(CompStore.externalToken(external.toFile()));
        project.settings.externalAssets.add("external:" + root.resolve("missing.png"));

        return project;
    }

    @Test
    public void scanGroupsFramesAndListsExternalMedia(@TempDir Path root) throws Exception
    {
        CompProject project = this.project(root);
        List<CompMediaLibrary.Item> items = CompMediaLibrary.scan(project);

        CompMediaLibrary.Item sequence = null;
        CompMediaLibrary.Item image = null;
        CompMediaLibrary.Item audio = null;
        CompMediaLibrary.Item video = null;
        CompMediaLibrary.Item missing = null;

        for (CompMediaLibrary.Item item : items)
        {
            if (item.kind == CompMediaLibrary.Kind.SEQUENCE) sequence = item;
            if (item.kind == CompMediaLibrary.Kind.IMAGE) image = item;
            if (item.kind == CompMediaLibrary.Kind.AUDIO) audio = item;
            if (item.kind == CompMediaLibrary.Kind.VIDEO) video = item;
            if (item.offline) missing = item;
        }

        assertNotNull(sequence, "a frame folder must be one sequence item");
        assertEquals(12, CompMediaLibrary.sequenceFrames(sequence));
        assertTrue(sequence.path.endsWith("frame_%04d.png"));
        assertEquals("frames/clip/frame_0000.png".replace("frames/", "assets/frames/"),
            CompMediaLibrary.sequenceFrame(sequence, 0));
        assertEquals("frames/clip/frame_0011.png".replace("frames/", "assets/frames/"),
            CompMediaLibrary.sequenceFrame(sequence, 11));

        assertNotNull(image);
        assertNotNull(audio);
        assertNotNull(video);
        assertNotNull(missing, "an offline external link must remain visible");
        assertEquals(CompMediaLibrary.Kind.VIDEO, video.kind);
    }

    @Test
    public void individualFramesAreNotListedAsImages(@TempDir Path root) throws Exception
    {
        CompProject project = this.project(root);
        List<CompMediaLibrary.Item> items = CompMediaLibrary.scan(project);
        int images = 0;

        for (CompMediaLibrary.Item item : items)
        {
            if (item.kind == CompMediaLibrary.Kind.IMAGE && !item.external)
            {
                images++;
                assertFalse(item.path.contains("frames/"), "extracted frames must stay inside their sequence");
            }
        }

        assertEquals(1, images);
    }

    @Test
    public void binsStartWithADefaultAndMoveItemsWithoutDeletingThem(@TempDir Path root) throws Exception
    {
        CompProject project = this.project(root);

        CompMediaLibrary.ensureDefaultBin(project);
        assertEquals(1, project.settings.mediaBins.size());
        assertEquals(CompMediaLibrary.DEFAULT_BIN_ID, project.settings.mediaBins.get(0).id);

        CompMediaBin bin = CompMediaLibrary.addBin(project, "Music");

        assertNotNull(bin);
        assertEquals(2, project.settings.mediaBins.size());

        CompMediaLibrary.Item audio = null;

        for (CompMediaLibrary.Item item : CompMediaLibrary.scan(project))
        {
            if (item.kind == CompMediaLibrary.Kind.AUDIO)
            {
                audio = item;
            }
        }

        assertNotNull(audio);
        CompMediaLibrary.assign(project, audio.path, bin.id);
        assertEquals(bin.id, CompMediaLibrary.binOf(project, audio.path));

        List<CompMediaLibrary.Item> inMusic = CompMediaLibrary.inBin(CompMediaLibrary.scan(project), bin.id);

        assertEquals(1, inMusic.size());
        assertEquals(audio.path, inMusic.get(0).path);

        assertTrue(CompMediaLibrary.removeBin(project, bin.id));
        assertEquals(CompMediaLibrary.DEFAULT_BIN_ID, CompMediaLibrary.binOf(project, audio.path));
        assertTrue(Files.isRegularFile(root.resolve("lib/assets/audio/song.wav")),
            "deleting a bin must not delete media");
    }

    @Test
    public void theDefaultBinCannotBeRemoved(@TempDir Path root) throws Exception
    {
        CompProject project = this.project(root);

        CompMediaLibrary.ensureDefaultBin(project);
        assertFalse(CompMediaLibrary.removeBin(project, CompMediaLibrary.DEFAULT_BIN_ID));
    }
}
