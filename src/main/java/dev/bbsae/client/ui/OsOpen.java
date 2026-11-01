package dev.bbsae.client.ui;

import dev.bbsae.BbsAeAddon;
import net.minecraft.util.Util;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Opens a file or folder in the operating system's shell, without assuming AWT works.
 *
 * <p>The old buttons used {@code Desktop.getDesktop().open(...)} and swallowed every exception.
 * A Minecraft launcher is free to start the JVM with {@code -Djava.awt.headless=true}, and even
 * without it {@code Desktop} can be unsupported (some Linux sessions, PCL's bundled JRE). The
 * result was a button that did nothing and said nothing.</p>
 *
 * <p>The order is: Minecraft's own {@link Util.OperatingSystem} integration first (it uses
 * {@code rundll32}/{@code open}/{@code xdg-open} and does not need AWT), then AWT, then a direct
 * process launch. Every step is logged, and the caller gets a boolean so it can show a status
 * instead of pretending the folder opened.</p>
 */
public final class OsOpen
{
    private OsOpen()
    {}

    /** Opens a file with its default application, or a folder in the file manager. */
    public static boolean open(Path path)
    {
        if (path == null)
        {
            return false;
        }

        Path absolute = path.toAbsolutePath().normalize();

        try
        {
            Util.getOperatingSystem().open(absolute.toFile());

            return true;
        }
        catch (Throwable t)
        {
            BbsAeAddon.LOGGER.warn("Minecraft's OS integration could not open {}", absolute, t);
        }

        try
        {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported())
            {
                Desktop desktop = Desktop.getDesktop();

                if (desktop.isSupported(Desktop.Action.OPEN))
                {
                    desktop.open(absolute.toFile());

                    return true;
                }
            }
        }
        catch (Throwable t)
        {
            BbsAeAddon.LOGGER.warn("AWT could not open {}", absolute, t);
        }

        try
        {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder builder;

            if (os.contains("win"))
            {
                /* explorer.exe returns exit code 1 even when it succeeds, so only the launch
                 * can be reported; the process is detached and not waited for. */
                builder = new ProcessBuilder("explorer.exe", absolute.toString());
            }
            else if (os.contains("mac"))
            {
                builder = new ProcessBuilder("open", absolute.toString());
            }
            else
            {
                builder = new ProcessBuilder("xdg-open", absolute.toString());
            }

            builder.start();

            return true;
        }
        catch (Throwable t)
        {
            BbsAeAddon.LOGGER.warn("No shell command could open {}", absolute, t);
        }

        return false;
    }

    /** Creates the folder if needed, then opens it. */
    public static boolean openFolder(Path folder)
    {
        if (folder == null)
        {
            return false;
        }

        try
        {
            Files.createDirectories(folder);
        }
        catch (Throwable t)
        {
            BbsAeAddon.LOGGER.warn("Could not create the folder {}", folder, t);

            return false;
        }

        return open(folder);
    }
}
