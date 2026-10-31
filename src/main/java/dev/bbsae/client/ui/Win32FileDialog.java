package dev.bbsae.client.ui;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.Native;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The real Windows Open dialog, owned by the game window.
 *
 * <h2>Why not AWT here</h2>
 * <p>{@link java.awt.FileDialog} is also native, but it is owned by a hidden helper frame that has
 * no relationship to the GLFW window. On a borderless fullscreen game Windows is free to place it
 * behind the game — the dialog exists, the callback never fires, and from the player's side the
 * import button is simply dead. An <b>owned</b> dialog cannot go behind its owner, so passing the
 * game's HWND to {@code GetOpenFileNameW} removes the whole class of failure.</p>
 *
 * <p>JNA ships with Minecraft (it is what BBS's own folder picker uses), so this adds no
 * dependency. Everything is optional: if the call fails for any reason the caller falls back to
 * AWT, then to the in-game browser.</p>
 */
final class Win32FileDialog
{
    private static final int OFN_READONLY = 0x00000001;
    private static final int OFN_OVERWRITEPROMPT = 0x00000002;
    private static final int OFN_HIDEREADONLY = 0x00000004;
    private static final int OFN_NOCHANGEDIR = 0x00000008;
    private static final int OFN_PATHMUSTEXIST = 0x00000800;
    private static final int OFN_FILEMUSTEXIST = 0x00001000;
    private static final int OFN_EXPLORER = 0x00080000;

    private Win32FileDialog()
    {}

    interface Comdlg32 extends StdCallLibrary
    {
        Comdlg32 INSTANCE = Native.load("comdlg32", Comdlg32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean GetOpenFileNameW(OpenFileName.ByReference ofn);

        int CommDlgExtendedError();
    }

    /** {@code OPENFILENAMEW}; field order and widths must match the Win32 header exactly. */
    public static class OpenFileName extends Structure
    {
        public WinDef.DWORD lStructSize;
        public WinDef.HWND hwndOwner;
        public Pointer hInstance;
        public Pointer lpstrFilter;
        public Pointer lpstrCustomFilter;
        public WinDef.DWORD nMaxCustFilter;
        public WinDef.DWORD nFilterIndex;
        public Pointer lpstrFile;
        public WinDef.DWORD nMaxFile;
        public Pointer lpstrFileTitle;
        public WinDef.DWORD nMaxFileTitle;
        public Pointer lpstrInitialDir;
        public Pointer lpstrTitle;
        public WinDef.DWORD Flags;
        public WinDef.WORD nFileOffset;
        public WinDef.WORD nFileExtension;
        public Pointer lpstrDefExt;
        public WinBase.LPARAM lCustData;
        public Pointer lpfnHook;
        public Pointer lpTemplateName;
        public Pointer pvReserved;
        public WinDef.DWORD dwReserved;
        public WinDef.DWORD FlagsEx;

        public static class ByReference extends OpenFileName implements Structure.ByReference
        {}

        @Override
        protected List<String> getFieldOrder()
        {
            List<String> order = new ArrayList<>();

            order.add("lStructSize");
            order.add("hwndOwner");
            order.add("hInstance");
            order.add("lpstrFilter");
            order.add("lpstrCustomFilter");
            order.add("nMaxCustFilter");
            order.add("nFilterIndex");
            order.add("lpstrFile");
            order.add("nMaxFile");
            order.add("lpstrFileTitle");
            order.add("nMaxFileTitle");
            order.add("lpstrInitialDir");
            order.add("lpstrTitle");
            order.add("Flags");
            order.add("nFileOffset");
            order.add("nFileExtension");
            order.add("lpstrDefExt");
            order.add("lCustData");
            order.add("lpfnHook");
            order.add("lpTemplateName");
            order.add("pvReserved");
            order.add("dwReserved");
            order.add("FlagsEx");

            return order;
        }
    }

    /**
     * @param owner  the GLFW window handle (0 = unowned)
     * @return the chosen file, or null when the user cancelled
     * @throws IllegalStateException when the dialog could not be shown at all
     */
    static File open(long owner, String title, String[] extensions, File initialDirectory)
    {
        if (!isWindows())
        {
            throw new IllegalStateException("not Windows");
        }

        Memory fileBuffer = new Memory(4096L * 2L);
        Memory filterBuffer = filter(extensions);
        Memory titleBuffer = title == null || title.isBlank() ? null : utf16(title);
        Memory directoryBuffer = initialDirectory == null || !initialDirectory.isDirectory()
            ? null
            : utf16(initialDirectory.getAbsolutePath());

        fileBuffer.clear();

        OpenFileName.ByReference ofn = new OpenFileName.ByReference();

        ofn.lStructSize = new WinDef.DWORD(ofn.size());
        ofn.hwndOwner = owner == 0L ? null : new WinDef.HWND(Pointer.createConstant(owner));
        ofn.lpstrFilter = filterBuffer;
        ofn.lpstrFile = fileBuffer;
        ofn.nMaxFile = new WinDef.DWORD(4096);
        ofn.lpstrInitialDir = directoryBuffer;
        ofn.lpstrTitle = titleBuffer;
        ofn.Flags = new WinDef.DWORD(OFN_EXPLORER | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST
            | OFN_NOCHANGEDIR | OFN_HIDEREADONLY | OFN_READONLY);
        ofn.write();

        boolean ok = Comdlg32.INSTANCE.GetOpenFileNameW(ofn);
        int error = ok ? 0 : Comdlg32.INSTANCE.CommDlgExtendedError();

        if (!ok)
        {
            /* 0 = the user pressed Cancel, which is a normal outcome, not a failure. */
            if (error == 0)
            {
                return null;
            }

            throw new IllegalStateException("GetOpenFileNameW failed, error 0x" + Integer.toHexString(error));
        }

        ofn.read();

        String path = fileBuffer.getWideString(0);

        return path == null || path.isEmpty() ? null : new File(path);
    }

    static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** {@code "PNG (*.png)\0*.png\0All files (*.*)\0*.*\0\0"} in UTF-16. */
    private static Memory filter(String[] extensions)
    {
        StringBuilder patterns = new StringBuilder();
        StringBuilder labels = new StringBuilder();

        if (extensions != null)
        {
            for (String extension : extensions)
            {
                if (extension == null || extension.isBlank())
                {
                    continue;
                }

                String suffix = extension.startsWith(".") ? extension.substring(1) : extension;

                if (patterns.length() > 0)
                {
                    patterns.append(';');
                    labels.append(", ");
                }

                patterns.append("*.").append(suffix);
                labels.append("*.").append(suffix);
            }
        }

        if (patterns.length() == 0)
        {
            patterns.append("*.*");
            labels.append("*.*");
        }

        String text = labels + "\0" + patterns + "\0All files (*.*)\0*.*\0\0";

        return utf16(text);
    }

    private static Memory utf16(String text)
    {
        Memory memory = new Memory((text.length() + 1L) * 2L);

        memory.setWideString(0L, new WString(text).toString());

        return memory;
    }
}
