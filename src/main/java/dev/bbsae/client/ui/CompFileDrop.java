package dev.bbsae.client.ui;

import dev.bbsae.BbsAeAddon;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFWDropCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Operating-system file drag & drop.
 *
 * <p>GLFW already delivers dropped paths — the game just never asks for them. Installing one drop
 * callback is enough to make "drag a png onto the window" work, which is the gesture every editor
 * user tries first.</p>
 *
 * <p>The callback runs from {@code glfwPollEvents} on the render thread, so it is safe to touch the
 * UI from it; no {@code MinecraftClient.execute} hop (and its one-frame delay) is needed.</p>
 */
public final class CompFileDrop
{
    private static Consumer<List<Path>> handler;
    private static boolean installed;

    private CompFileDrop()
    {}

    /** Installs the callback once. Later calls only replace the handler. */
    public static void install(Consumer<List<Path>> consumer)
    {
        handler = consumer;

        if (installed)
        {
            return;
        }

        /* BBS posts its client-ready event from onInitializeClient, before MinecraftClient has a
         * window. Throwing here aborted the rest of the addon's registration (preview bridge, export
         * tick), so a null window simply means "try again on the next call". */
        net.minecraft.client.util.Window clientWindow = MinecraftClient.getInstance().getWindow();

        if (clientWindow == null)
        {
            return;
        }

        long window = clientWindow.getHandle();

        if (window == 0L)
        {
            return;
        }

        installed = true;

        GLFWDropCallback previous = org.lwjgl.glfw.GLFW.glfwSetDropCallback(window, (handle, count, names) ->
        {
            List<Path> files = new ArrayList<>(count);

            for (int i = 0; i < count; i++)
            {
                String name = GLFWDropCallback.getName(names, i);

                if (name != null && !name.isBlank())
                {
                    files.add(Path.of(name));
                }
            }

            Consumer<List<Path>> target = handler;

            if (target == null || files.isEmpty())
            {
                return;
            }

            BbsAeAddon.LOGGER.info("Dropped {} file(s): {}", files.size(), files);

            try
            {
                target.accept(files);
            }
            catch (RuntimeException e)
            {
                BbsAeAddon.LOGGER.error("Could not handle dropped files", e);
            }
        });

        if (previous != null)
        {
            previous.free();
        }
    }
}
