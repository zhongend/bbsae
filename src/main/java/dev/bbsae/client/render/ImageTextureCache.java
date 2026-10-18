package dev.bbsae.client.render;

import dev.bbsae.comp.CompStore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads image layers from the project's asset folder. Missing files are a soft failure. */
public final class ImageTextureCache
{
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final int MAX = 32;

    private ImageTextureCache()
    {}

    public static final class Entry
    {
        public final GlTexture texture;
        public final int width;
        public final int height;

        private Entry(GlTexture texture, int width, int height)
        {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    public static Entry get(String projectId, String path)
    {
        if (path == null || path.isBlank())
        {
            return null;
        }

        Path file = resolve(projectId, path);

        if (file == null || !Files.isRegularFile(file))
        {
            return null;
        }

        try
        {
            long modified = Files.getLastModifiedTime(file).toMillis();
            String key = file.toAbsolutePath() + ":" + modified;
            Entry entry = CACHE.get(key);

            if (entry != null)
            {
                return entry;
            }

            BufferedImage image = ImageIO.read(file.toFile());

            if (image == null)
            {
                return null;
            }

            int w = image.getWidth();
            int h = image.getHeight();
            int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
            GlTexture texture = new GlTexture();

            texture.allocate(w, h);
            texture.uploadRaw(pixels);

            entry = new Entry(texture, w, h);
            CACHE.put(key, entry);

            while (CACHE.size() > MAX)
            {
                String oldest = CACHE.keySet().iterator().next();
                CACHE.remove(oldest).texture.delete();
            }

            return entry;
        }
        catch (Exception e)
        {
            dev.bbsae.BbsAeAddon.LOGGER.warn("Could not load composition image {}", file, e);

            return null;
        }
    }

    public static Path resolve(String projectId, String path)
    {
        try
        {
            Path raw = Path.of(path);

            if (raw.isAbsolute())
            {
                return raw;
            }

            Path projectRoot = CompStore.file(projectId).getParent();
            Path projectRelative = projectRoot.resolve(path);

            if (Files.isRegularFile(projectRelative))
            {
                return projectRelative;
            }

            String assetPath = path.startsWith("assets/") ? path.substring("assets/".length()) : path;
            Path asset = CompStore.assetRoot(projectId).resolve(assetPath);

            if (Files.isRegularFile(asset))
            {
                return asset;
            }

            return projectRelative;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static void clear()
    {
        for (Entry entry : CACHE.values())
        {
            entry.texture.delete();
        }

        CACHE.clear();
    }
}
