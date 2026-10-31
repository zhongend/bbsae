package dev.bbsae.client.ui;

import dev.bbsae.BbsAeAddon;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The operating system's own file picker, with a fallback that cannot fail silently.
 *
 * <h2>Why not BBS's {@code UIFileDialogs}</h2>
 * <p>It calls LWJGL's tinyfd on a background thread. tinyfd is a native window with no owner
 * relationship to the game: in a fullscreen/borderless Minecraft window it can open <em>behind</em>
 * the game, and when the platform refuses to present it the call simply returns null. From the
 * game's point of view nothing failed, the callback never fires, and the import button reads as
 * dead. That is exactly the reported bug.</p>
 *
 * <h2>What this does instead</h2>
 * <ul>
 *   <li>{@link java.awt.FileDialog} — the <b>native</b> Open dialog of the platform (the same one
 *       every Java application shows), created with an always-on-top owner so a borderless game
 *       cannot cover it, on a worker thread so the render thread keeps ticking.</li>
 *   <li>If AWT is unavailable (headless JRE, a platform without a toolkit, a driver conflict),
 *       the caller is told so and opens the in-game browser instead. A cancel is reported as a
 *       cancel, not as a failure — that distinction is the whole reason this is a callback pair
 *       instead of a {@code File} return value.</li>
 * </ul>
 */
public final class NativeFileDialog
{
    /** One dialog at a time: two native modals would fight over focus. */
    private static final AtomicBoolean OPEN = new AtomicBoolean();

    private static File lastDirectory;

    private NativeFileDialog()
    {}

    /**
     * @param onPick       called on the client thread with the chosen file; never null
     * @param onUnavailable called on the client thread when the OS dialog could not be shown
     */
    public static void pickFile(String title, String[] extensions, Consumer<File> onPick, Runnable onUnavailable)
    {
        if (!OPEN.compareAndSet(false, true))
        {
            return;
        }

        Thread thread = new Thread(() ->
        {
            File picked = null;
            boolean unavailable = false;

            /* Three backends, in order of how much they own the dialog:
             *
             *   1. Win32 GetOpenFileNameW with the game's HWND as owner — an owned dialog cannot
             *      be placed behind the game window, which is the failure AWT and tinyfd share.
             *   2. AWT FileDialog — the native dialog on every other platform.
             *   3. Nothing: report it so the caller can open the in-game browser.
             *
             * A backend that returns null did show a dialog and the user cancelled; only an
             * exception means "unavailable", and only then is the next backend tried. */
            try
            {
                picked = show(title, extensions);
            }
            catch (Throwable t)
            {
                BbsAeAddon.LOGGER.warn("The OS file dialog is unavailable; falling back to the in-game browser", t);
                unavailable = true;
            }
            finally
            {
                OPEN.set(false);
            }

            final File result = picked;
            final boolean failed = unavailable;
            MinecraftClient client = MinecraftClient.getInstance();

            if (client == null)
            {
                return;
            }

            client.execute(() ->
            {
                focusGameWindow();

                if (failed)
                {
                    onUnavailable.run();
                }
                else if (result != null)
                {
                    onPick.accept(result);
                }
            });
        }, "bbsae-file-dialog");

        thread.setDaemon(true);
        thread.start();
    }

    private static File show(String title, String[] extensions) throws Exception
    {
        if (Win32FileDialog.isWindows())
        {
            /* Owned by the game window: the one thing neither AWT nor tinyfd can promise. */
            File picked = Win32FileDialog.open(windowHandle(), title, extensions, lastDirectory);

            if (picked != null)
            {
                lastDirectory = picked.getParentFile();
            }

            return picked;
        }

        return showAwt(title, extensions);
    }

    /** The GLFW window as a Win32 HWND, or 0 when it cannot be resolved. */
    private static long windowHandle()
    {
        try
        {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client == null || client.getWindow() == null)
            {
                return 0L;
            }

            return org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(client.getWindow().getHandle());
        }
        catch (Throwable t)
        {
            BbsAeAddon.LOGGER.warn("Could not resolve the game window handle for the file dialog", t);

            return 0L;
        }
    }

    private static File showAwt(String title, String[] extensions) throws Exception
    {
        if (GraphicsEnvironment.isHeadless())
        {
            throw new IllegalStateException("AWT is headless");
        }

        Frame owner = new Frame();

        owner.setUndecorated(true);
        owner.setSize(1, 1);
        owner.setLocationRelativeTo(null);
        owner.setAlwaysOnTop(true);

        FileDialog dialog = new FileDialog(owner, title, FileDialog.LOAD);

        dialog.setMultipleMode(false);
        dialog.setAlwaysOnTop(true);
        dialog.setDirectory(directory());
        dialog.setFilenameFilter((dir, name) -> accepts(name, extensions));

        String pattern = pattern(extensions);

        if (pattern != null)
        {
            /* The native dialog treats the initial file name as a filter pattern on Windows and
             * as a suggestion elsewhere; either way it saves the user from typing "*.mp4". */
            dialog.setFile(pattern);
        }

        try
        {
            dialog.setVisible(true);
        }
        finally
        {
            String name = dialog.getFile();
            String folder = dialog.getDirectory();

            dialog.dispose();
            owner.dispose();

            if (name == null || name.isBlank())
            {
                return null;
            }

            File file = new File(folder == null ? "." : folder, name);

            if (file.isDirectory())
            {
                return null;
            }

            lastDirectory = file.getParentFile();

            return file;
        }
    }

    private static String directory()
    {
        if (lastDirectory != null && lastDirectory.isDirectory())
        {
            return lastDirectory.getAbsolutePath();
        }

        String home = System.getProperty("user.home");
        String[] candidates = {"Pictures", "Videos", "Music", "Desktop", "Downloads"};

        for (String candidate : candidates)
        {
            File folder = new File(home, candidate);

            if (folder.isDirectory())
            {
                return folder.getAbsolutePath();
            }
        }

        return home == null ? "." : home;
    }

    /** {@code ["png","jpg"]} -> {@code "*.png;*.jpg"}; null when the filter is "everything". */
    private static String pattern(String[] extensions)
    {
        if (extensions == null || extensions.length == 0)
        {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        for (String extension : extensions)
        {
            if (extension == null || extension.isBlank())
            {
                continue;
            }

            if (builder.length() > 0)
            {
                builder.append(';');
            }

            builder.append("*.").append(extension.startsWith(".") ? extension.substring(1) : extension);
        }

        return builder.length() == 0 ? null : builder.toString();
    }

    private static boolean accepts(String name, String[] extensions)
    {
        if (extensions == null || extensions.length == 0 || name == null)
        {
            return true;
        }

        String lower = name.toLowerCase(Locale.ROOT);

        for (String extension : extensions)
        {
            if (extension == null || extension.isBlank())
            {
                continue;
            }

            String suffix = extension.startsWith(".") ? extension.substring(1) : extension;

            if (lower.endsWith("." + suffix.toLowerCase(Locale.ROOT)))
            {
                return true;
            }
        }

        return false;
    }

    /** A native dialog steals focus; give it back so the next key press reaches the game. */
    private static void focusGameWindow()
    {
        try
        {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client != null && client.getWindow() != null)
            {
                GLFW.glfwFocusWindow(client.getWindow().getHandle());
            }
        }
        catch (RuntimeException ignored)
        {}
    }

    /** True while a dialog is on screen; the panel uses it to keep the game paused-ish. */
    public static boolean isOpen()
    {
        return OPEN.get();
    }
}
