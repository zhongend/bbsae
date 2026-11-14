package dev.bbsae.comp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One path resolver for every media token. These cases were each a real "the file exists but the
 * editor says it is missing" bug: a Windows drive parsed as a resource namespace, an old
 * {@code assets/} prefix resolved against the wrong root, or an external link treated as a pack id.
 */
public class CompStorePathTest
{
    @AfterEach
    public void cleanup()
    {
        CompStore.clearRootForTests();
    }

    @Test
    public void windowsDrivesAreFilesNotNamespaces()
    {
        assertFalse(CompStore.isResourcePath("C:/Pictures/a.png"));
        assertFalse(CompStore.isResourcePath("C:\\Pictures\\a.png"));
        assertFalse(CompStore.isResourcePath("external:C:/Pictures/a.png"));
        assertTrue(CompStore.isResourcePath("mc:textures/item/apple.png"));
        assertTrue(CompStore.isResourcePath("bbsae:icon.png"));
    }

    @Test
    public void resolveHandlesExternalProjectRelativeAndLegacyAssetsPrefix(@TempDir Path root) throws Exception
    {
        CompStore.useRootForTests(root);

        CompProject project = CompStore.newProject("paths", "Paths");
        Path assets = CompStore.assetRoot(project.id);

        Files.createDirectories(assets.resolve("images"));
        Files.write(assets.resolve("images/a.png"), new byte[] {1});

        assertEquals(assets.resolve("images/a.png"),
            CompStore.resolveAsset(project.id, "images/a.png"));
        assertEquals(assets.resolve("images/a.png"),
            CompStore.resolveAsset(project.id, "assets/images/a.png"));

        Path outside = root.resolve("outside.png");

        Files.write(outside, new byte[] {1});

        assertEquals(outside.toAbsolutePath(),
            CompStore.resolveAsset(project.id, CompStore.externalToken(outside.toFile())));
        assertNull(CompStore.resolveAsset(project.id, "mc:textures/item/apple.png"));
    }

    @Test
    public void externalTokensAreStable()
    {
        Path file = Path.of("C:/Media/Clip 1.mp4");

        String token = CompStore.externalToken(file.toFile());

        assertTrue(token.startsWith("external:"));
        assertEquals(file.toAbsolutePath().normalize().toString(), CompStore.externalRaw(token));
    }
}
