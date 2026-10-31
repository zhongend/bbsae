package dev.bbsae.client.ui;

import dev.bbsae.BbsAeAddon;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.Sound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Turns a sound id into a file the composition can actually use.
 *
 * <h2>Why a sound "cannot be imported"</h2>
 * <p>A sound is not a file path: {@code minecraft:block.note_block.bell} is an <b>event</b> that
 * resolves to one of several ogg files through the sound manager, and BBS's library hands out
 * {@code bbs:sounds/...} links that live in its own asset provider. The compositor's audio track
 * stores project-relative files, so "insert this sound" needs one explicit step: resolve the id to
 * bytes, write them into {@code assets/audio}, and add a clip. Without it the picker could only
 * copy an id to the clipboard, which is why the sound category felt dead.</p>
 */
public final class SoundImport
{
    private SoundImport()
    {}

    /**
     * Writes the sound behind {@code id} into a temporary ogg file.
     *
     * @param id a sound event id ({@code minecraft:block.note_block.bell}) or an explicit
     *           {@code namespace:path.ogg} link
     * @throws IOException when the id cannot be resolved to a readable resource
     */
    public static File materialize(String id) throws IOException
    {
        if (id == null || id.isBlank())
        {
            throw new IOException("音效 ID 为空");
        }

        String normalized = id.trim();

        if (!normalized.contains(":"))
        {
            normalized = "minecraft:" + normalized;
        }

        Identifier identifier = Identifier.tryParse(normalized);

        if (identifier == null)
        {
            throw new IOException("无效的音效 ID：" + id);
        }

        InputStream stream = open(normalized, identifier);

        if (stream == null)
        {
            throw new IOException("找不到音效资源：" + normalized);
        }

        Path temp = Files.createTempFile("bbsae_sound_", ".ogg");

        try (InputStream in = stream)
        {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }

        temp.toFile().deleteOnExit();

        return temp.toFile();
    }

    private static InputStream open(String id, Identifier identifier)
    {
        /* 1. An explicit ogg link: BBS's provider knows its own packs, the vanilla resource
         *    manager knows the game's. Try both, because a {@code bbs:} link is not in the vanilla
         *    pack and a {@code minecraft:} file is not in BBS's. */
        if (identifier.getPath().endsWith(".ogg"))
        {
            try
            {
                InputStream in = BBSMod.getProvider().getAsset(new Link(identifier.getNamespace(), identifier.getPath()));

                if (in != null)
                {
                    return in;
                }
            }
            catch (Exception ignored)
            {}

            try
            {
                var resource = MinecraftClient.getInstance().getResourceManager().getResource(identifier);

                if (resource.isPresent())
                {
                    return resource.get().getInputStream();
                }
            }
            catch (Exception e)
            {
                BbsAeAddon.LOGGER.warn("Could not open sound resource {}", identifier, e);
            }

            return null;
        }

        /* 2. A sound event: ask the sound manager which file the event actually plays. */
        try
        {
            SoundEvent event = Registries.SOUND_EVENT.get(identifier);

            if (event == null)
            {
                BbsAeAddon.LOGGER.warn("Unknown sound event {}", id);

                return null;
            }

            net.minecraft.client.sound.WeightedSoundSet set = MinecraftClient.getInstance().getSoundManager().get(identifier);
            Sound sound = set == null ? null : set.getSound(net.minecraft.util.math.random.Random.create());

            if (sound == null)
            {
                BbsAeAddon.LOGGER.warn("Sound event {} has no loaded sound", id);

                return null;
            }

            Identifier location = sound.getLocation();
            var resource = MinecraftClient.getInstance().getResourceManager().getResource(location);

            return resource.isPresent() ? resource.get().getInputStream() : null;
        }
        catch (Exception e)
        {
            BbsAeAddon.LOGGER.warn("Could not resolve sound event {}", id, e);

            return null;
        }
    }

    /** True when the id looks like something {@link #materialize(String)} can try. */
    public static boolean looksLikeSound(String id)
    {
        return id != null && !id.isBlank() && (id.contains(":") || !id.contains("."));
    }
}
